package com.bbh.build

import com.bbh.remediation.port.BuildVerifier
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class ManifestBuildVerifier implements BuildVerifier {

    private final def             script
    private final NodeBuildRunner nodeRunner

    ManifestBuildVerifier(def script, NodeBuildRunner nodeRunner = null) {
        this.script     = script
        this.nodeRunner = nodeRunner
    }

    Map verify(String dir, List<String> changedFiles, Map cfg) {
        Map consumers = (cfg?.catalogConsumers ?: [:]) as Map
        for (String file : unverified(changedFiles, consumers)) script.echo "GoldenFix: no Gradle build found for ${file} - not verified"
        List checks = plan(changedFiles, (cfg?.commands ?: [:]) as Map, consumers, (cfg?.lockFiles ?: []) as List)
        if (checks.isEmpty()) {
            return [ok: true, skipped: true, log: 'no manifest that the library knows how to verify was changed', checks: [], files: []]
        }
        int timeoutMin = (cfg?.timeoutMinutes != null ? cfg.timeoutMinutes.toString().toInteger() : 20)
        List results = []
        List files = []
        for (Map check : checks) {
            Map outcome = run(dir, check, timeoutMin, cfg?.node as Map)
            results << outcome
            if (!outcome.ok) {
                return [ok: false, skipped: false, log: "${check.name}: ${outcome.log}".toString(), checks: results, files: []]
            }
            files.addAll((check.files ?: []) as List)
        }
        return [ok: true, skipped: false, log: describe(results), checks: results, files: files]
    }

    private Map run(String dir, Map check, int timeoutMin, Map node) {
        try {
            int status = 1
            String targetDir = "${dir}${check.subDir ? '/' + check.subDir : ''}".toString()
            script.timeout(time: timeoutMin, unit: 'MINUTES') {
                status = inNodeEnvironment(check.name == 'npm' ? node : null) {
                    if (!script.isUnix()) {
                        return runWindows(targetDir, check)
                    }
                    return script.sh(label: "GoldenFix: pre-check ${check.name}", returnStatus: true, script: """#!/bin/bash
set +e
cd '${BuildUtils.escapeForSingleQuotes(dir)}${check.subDir ? '/' + BuildUtils.escapeForSingleQuotes(check.subDir as String) : ''}' || exit 1
${check.command}
""")
                } as int
            }
            return [name: check.name, command: check.command, ok: status == 0, log: "exit code ${status}".toString()]
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return [name: check.name, command: check.command, ok: false, log: (e.message ?: e.getClass().getSimpleName()) as String]
        }
    }

    private int runWindows(String targetDir, Map check) {
        String command = check.overrideCommand ? (check.overrideCommand as String) : windowsPrecheckCommand(check)
        String out = script.powershell(label: "GoldenFix: pre-check ${check.name}", returnStdout: true, script: """
\$ErrorActionPreference = 'Continue'
Set-Location -Path '${BuildUtils.psSingleQuote(targetDir)}'
if (-not \$?) { Write-Output 'DEVSECOPS_EXIT=1'; exit 0 }
${command}
Write-Output "DEVSECOPS_EXIT=\$LASTEXITCODE"
""") as String
        def m = (out ?: '') =~ /DEVSECOPS_EXIT=(-?\d+)/
        return m.find() ? m.group(1).toInteger() : 1
    }

    @NonCPS
    private static String windowsPrecheckCommand(Map check) {
        String kind = check.name as String
        String file = (check.file ?: '') as String
        if (kind == 'nuget') {
            if (file.equalsIgnoreCase('packages.config')) {
                return "nuget restore '${BuildUtils.psSingleQuote(file)}' -NonInteractive\nif (\$LASTEXITCODE -eq 0) { msbuild /t:Build /nologo /v:minimal }"
            }
            return "if (Test-Path 'packages.config') { nuget restore -NonInteractive }\n" +
                    "if (Get-Command MSBuild.exe -ErrorAction SilentlyContinue) { " +
                    (file.toLowerCase().endsWith('.props') ? 'MSBuild.exe /t:Restore,Build /nologo /v:minimal' : "nuget restore '${BuildUtils.psSingleQuote(file)}' -NonInteractive; MSBuild.exe '${BuildUtils.psSingleQuote(file)}' /t:Build /nologo /v:minimal") +
                    " } else { dotnet build --no-incremental" + (file.toLowerCase().endsWith('.props') ? '' : " '${BuildUtils.psSingleQuote(file)}'") + " }"
        }
        return check.command as String
    }

    private def inNodeEnvironment(Map node, Closure body) {
        return nodeRunner != null && BuildUtils.booleanValue(node?.enabled, false) ? nodeRunner.withNode(node, body) : body()
    }

    @NonCPS
    private String describe(List results) {
        List names = []
        for (Map r : (results ?: [])) names << r.name
        return names.join(', ')
    }

    @NonCPS
    List plan(List<String> changedFiles, Map overrides, Map consumers = [:], List lockFiles = []) {
        List checks = []
        List seen = []
        for (String path : (changedFiles ?: [])) {
            String name = BuildUtils.fileNameOf(path)
            boolean shared = consumers?.containsKey(path)
            String kind = kindOf(name) ?: (shared ? (name.endsWith('.xml') ? 'maven' : 'gradle') : '')
            if (!kind) continue
            for (def root : (shared ? (consumers[path] as List) : [BuildUtils.parentOf(path)])) {
                String subDir = root == path ? BuildUtils.parentOf(path) : root as String
                String key = kind + '@' + root
                if (seen.contains(key)) continue
                seen << key
                String override = overrides?.get(kind) as String
                String command = (override ?: defaultCommand(kind, name)) as String
                if (!command) continue
                if (kind == 'maven' && shared && root == path && name != 'pom.xml') command += " -f '${BuildUtils.escapeForSingleQuotes(name)}'"
                String lock = (subDir ? subDir + '/' : '') + 'package-lock.json'
                if (kind == 'npm' && !override && lockFiles?.contains(lock)) {
                    checks << [name: kind, command: "npm install --package-lock-only --ignore-scripts || exit \$?\n${command}".toString(), subDir: subDir, files: [lock], file: name, overrideCommand: override]
                } else {
                    checks << [name: kind, command: command, subDir: subDir, file: name, overrideCommand: override]
                }
            }
        }
        return checks
    }

    @NonCPS
    List<String> unverified(List<String> changedFiles, Map consumers) {
        List<String> out = []
        for (String path : (changedFiles ?: [])) {
            if (consumers?.containsKey(path) && !(consumers[path] as List)) out << path
        }
        return out
    }

    @NonCPS
    String kindOf(String name) {
        if (name == 'pom.xml') return 'maven'
        if (name in ['build.gradle', 'build.gradle.kts', 'gradle.properties', 'settings.gradle', 'settings.gradle.kts'] || name.endsWith('.versions.toml')) return 'gradle'
        if (name == 'package.json') return 'npm'
        if (name == 'pyproject.toml' || (name.startsWith('requirements') && name.endsWith('.txt')) || (name.startsWith('constraints') && name.endsWith('.txt'))) return 'pip'
        if (name == 'pubspec.yaml' || name == 'pubspec.yml') return 'pub'
        String low = (name ?: '').toLowerCase()
        if (low.endsWith('.csproj') || low.endsWith('.fsproj') || low.endsWith('.vbproj') || low.endsWith('.props') || low == 'packages.config') return 'nuget'
        return ''
    }

    @NonCPS
    String defaultCommand(String kind, String name) {
        if (kind == 'maven') return 'mvn -B -q -DskipTests compile'
        if (kind == 'gradle') return '[ -x ./gradlew ] && ./gradlew --no-daemon --console=plain classes || gradle --no-daemon --console=plain classes'
        if (kind == 'npm') return '[ -f package-lock.json ] && npm ci --ignore-scripts || npm install --ignore-scripts'
        if (kind == 'pip') return name == 'pyproject.toml' ? 'python3 -m pip install --dry-run .' : "python3 -m pip install --dry-run -r '${name}'".toString()
        if (kind == 'pub') return '[ -f pubspec.yaml ] && flutter pub get || dart pub get'
        if (kind == 'nuget') {
            String low = (name ?: '').toLowerCase()
            if (low == 'packages.config') return "nuget restore '${BuildUtils.escapeForSingleQuotes(name)}' -NonInteractive"
            if (low.endsWith('.props')) return 'dotnet build --no-incremental'
            return "dotnet build --no-incremental '${BuildUtils.escapeForSingleQuotes(name)}'".toString()
        }
        return ''
    }

}
