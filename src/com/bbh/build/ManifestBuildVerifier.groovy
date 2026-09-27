package com.bbh.build

import com.bbh.remediation.port.BuildVerifier
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class ManifestBuildVerifier implements BuildVerifier {

    private final def script

    ManifestBuildVerifier(def script) {
        this.script = script
    }

    Map verify(String dir, List<String> changedFiles, Map cfg) {
        List checks = plan(changedFiles, (cfg?.commands ?: [:]) as Map)
        if (checks.isEmpty()) {
            return [ok: true, skipped: true, log: 'no manifest that the library knows how to verify was changed', checks: []]
        }
        int timeoutMin = (cfg?.timeoutMinutes != null ? cfg.timeoutMinutes.toString().toInteger() : 20)
        List results = []
        for (Map check : checks) {
            Map outcome = run(dir, check, timeoutMin)
            results << outcome
            if (!outcome.ok) {
                return [ok: false, skipped: false, log: "${check.name}: ${outcome.log}".toString(), checks: results]
            }
        }
        return [ok: true, skipped: false, log: describe(results), checks: results]
    }

    private Map run(String dir, Map check, int timeoutMin) {
        try {
            int status = 1
            script.timeout(time: timeoutMin, unit: 'MINUTES') {
                status = script.sh(label: "GoldenFix: pre-check ${check.name}", returnStatus: true, script: """#!/bin/bash
set +e
cd '${BuildUtils.escapeForSingleQuotes(dir)}${check.subDir ? '/' + BuildUtils.escapeForSingleQuotes(check.subDir as String) : ''}' || exit 1
${check.command}
""") as int
            }
            return [name: check.name, command: check.command, ok: status == 0, log: "exit code ${status}".toString()]
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return [name: check.name, command: check.command, ok: false, log: (e.message ?: e.getClass().getSimpleName()) as String]
        }
    }

    @NonCPS
    private String describe(List results) {
        List names = []
        for (Map r : (results ?: [])) names << r.name
        return names.join(', ')
    }

    @NonCPS
    List plan(List<String> changedFiles, Map overrides) {
        List checks = []
        List seen = []
        for (String path : (changedFiles ?: [])) {
            String name = BuildUtils.fileNameOf(path)
            String kind = kindOf(name)
            if (!kind) continue
            String subDir = BuildUtils.parentOf(path)
            String key = kind + '@' + subDir
            if (seen.contains(key)) continue
            seen << key
            String command = (overrides?.get(kind) ?: defaultCommand(kind, name)) as String
            if (!command) continue
            checks << [name: kind, command: command, subDir: subDir]
        }
        return checks
    }

    @NonCPS
    String kindOf(String name) {
        if (name == 'pom.xml') return 'maven'
        if (name == 'build.gradle' || name == 'build.gradle.kts' || name == 'gradle.properties' || name.endsWith('.versions.toml')) return 'gradle'
        if (name == 'package.json') return 'npm'
        if (name == 'pyproject.toml' || (name.startsWith('requirements') && name.endsWith('.txt')) || (name.startsWith('constraints') && name.endsWith('.txt'))) return 'pip'
        if (name == 'pubspec.yaml' || name == 'pubspec.yml') return 'pub'
        return ''
    }

    @NonCPS
    String defaultCommand(String kind, String name) {
        if (kind == 'maven') return 'mvn -B -q -DskipTests compile'
        if (kind == 'gradle') return '[ -x ./gradlew ] && ./gradlew --no-daemon --console=plain classes || gradle --no-daemon --console=plain classes'
        if (kind == 'npm') return '[ -f package-lock.json ] && npm ci --ignore-scripts || npm install --ignore-scripts'
        if (kind == 'pip') return name == 'pyproject.toml' ? 'python3 -m pip install --dry-run .' : "python3 -m pip install --dry-run -r '${name}'".toString()
        if (kind == 'pub') return '[ -f pubspec.yaml ] && flutter pub get || dart pub get'
        return ''
    }

}
