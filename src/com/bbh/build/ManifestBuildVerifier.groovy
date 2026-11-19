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
        List<String> missing = unverified(changedFiles, consumers)
        for (String file : missing) script.echo "GoldenFix: no build found for ${file} - not verified"
        List checks = plan(changedFiles, (cfg?.commands ?: [:]) as Map, consumers, (cfg?.lockFiles ?: []) as List)
        if (missing || checks.isEmpty()) {
            String what = missing ? "no build uses ${missing.join(', ')}" : 'no manifest that the library knows how to verify was changed'
            return [ok: true, skipped: true, log: what, checks: [], files: []]
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
        String quoted = BuildUtils.psSingleQuote(file)
        if (kind == 'nuget') {
            if (file.equalsIgnoreCase('packages.config')) {
                return "nuget restore '${quoted}' -NonInteractive\nif (\$LASTEXITCODE -eq 0) { msbuild /t:Build /nologo /v:minimal }"
            }
            boolean props = file.toLowerCase().endsWith('.props')
            return "if (Test-Path 'packages.config') { nuget restore -NonInteractive }\n" +
                    "if (Get-Command MSBuild.exe -ErrorAction SilentlyContinue) { " +
                    (props ? 'MSBuild.exe /t:Restore,Build /nologo /v:minimal' : "nuget restore '${quoted}' -NonInteractive; MSBuild.exe '${quoted}' /t:Build /nologo /v:minimal") +
                    " } else { dotnet test" + (props ? '' : " '${quoted}'") + " }"
        }
        if (kind == 'gradle') {
            return "if (Test-Path '.\\gradlew.bat') { .\\gradlew.bat --no-daemon --console=plain test } else { gradle --no-daemon --console=plain test }"
        }
        if (kind == 'npm') {
            String install = "if (Test-Path 'package-lock.json') { npm ci --ignore-scripts } else { npm install --ignore-scripts }\n" +
                    "if (\$LASTEXITCODE -eq 0) {\n" +
                    "    \$test = ''\n" +
                    "    try { \$test = [string]((Get-Content 'package.json' -Raw | ConvertFrom-Json).scripts.test) } catch { \$test = '' }\n" +
                    "    if (\$test -and \$test -notmatch 'no test specified') { \$env:CI = 'true'; npm test }\n" +
                    "}"
            return check.lockRefresh ? "npm install --package-lock-only --ignore-scripts\nif (\$LASTEXITCODE -eq 0) {\n${install}\n}".toString() : install
        }
        if (kind == 'pip') {
            String target = file == 'pyproject.toml' ? '.' : "-r '${quoted}'".toString()
            return "\$venv = Join-Path ([System.IO.Path]::GetTempPath()) ('goldenfix-venv-' + [guid]::NewGuid().ToString('N'))\n" +
                    "python -m venv \$venv\n" +
                    "\$python = Join-Path \$venv 'Scripts\\python.exe'\n" +
                    "if (\$LASTEXITCODE -eq 0) { & \$python -m pip install -q ${target} }\n" +
                    "if (\$LASTEXITCODE -eq 0) { & \$python -m pip install -q pytest }\n" +
                    "if (\$LASTEXITCODE -eq 0) { & \$python -m pytest -q; if (\$LASTEXITCODE -eq 5) { \$global:LASTEXITCODE = 0 } }\n" +
                    "\$code = \$LASTEXITCODE\n" +
                    "Remove-Item -Recurse -Force \$venv -ErrorAction SilentlyContinue\n" +
                    "\$global:LASTEXITCODE = \$code"
        }
        if (kind == 'pub') {
            return "\$tool = 'dart'\n" +
                    "if (Select-String -Path '${quoted}' -Pattern 'sdk:\\s*flutter' -Quiet) { \$tool = 'flutter' }\n" +
                    "& \$tool pub get\n" +
                    "if (\$LASTEXITCODE -eq 0 -and (Test-Path 'test')) { & \$tool test }"
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
                    checks << [name: kind, command: "npm install --package-lock-only --ignore-scripts || exit \$?\n${command}".toString(), subDir: subDir, files: [lock], file: name, overrideCommand: override, lockRefresh: true]
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
            boolean shared = consumers?.containsKey(path)
            if (shared ? !(consumers[path] as List) : !kindOf(BuildUtils.fileNameOf(path))) out << path
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
        String quoted = BuildUtils.escapeForSingleQuotes(name)
        if (kind == 'maven') return 'mvn -B -q test'
        if (kind == 'gradle') return 'if [ -x ./gradlew ]; then ./gradlew --no-daemon --console=plain test; else gradle --no-daemon --console=plain test; fi'
        if (kind == 'npm') {
            return '{ [ -f package-lock.json ] && npm ci --ignore-scripts || npm install --ignore-scripts; } || exit $?\n' +
                    'if node -e "const t = (require(\'./package.json\').scripts || {}).test || \'\'; process.exit(t && !/no test specified/.test(t) ? 0 : 1)"; then CI=true npm test; fi'
        }
        if (kind == 'pip') {
            String target = name == 'pyproject.toml' ? '.' : "-r '${quoted}'".toString()
            return 'venv="$(mktemp -d)" || exit 1\n' +
                    'python3 -m venv "$venv" && "$venv/bin/python" -m pip install -q ' + target + ' && "$venv/bin/python" -m pip install -q pytest\n' +
                    'status=$?\n' +
                    'if [ "$status" -eq 0 ]; then "$venv/bin/python" -m pytest -q; status=$?; [ "$status" -eq 5 ] && status=0; fi\n' +
                    'rm -rf "$venv"\n' +
                    'exit "$status"'
        }
        if (kind == 'pub') {
            return "if grep -qs 'sdk: *flutter' '${quoted}'; then tool=flutter; else tool=dart; fi\n".toString() +
                    '"$tool" pub get || exit $?\n' +
                    'if [ -d test ]; then "$tool" test; fi'
        }
        if (kind == 'nuget') {
            String low = (name ?: '').toLowerCase()
            if (low == 'packages.config') return "nuget restore '${quoted}' -NonInteractive".toString()
            if (low.endsWith('.props')) return 'dotnet test'
            return "dotnet test '${quoted}'".toString()
        }
        return ''
    }

}
