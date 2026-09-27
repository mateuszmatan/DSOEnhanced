package com.bbh.scanner

import com.bbh.build.BuildRunner
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.build.BuildService
import com.bbh.utils.FlutterSonarReports

class SonarService implements Serializable {

    private final def         script
    private final PipelineState state
    private final OsHelper    os
    private final BuildService build

    SonarService(def script, PipelineState state, OsHelper os, BuildService build) {
        this.script = script
        this.state  = state
        this.os     = os
        this.build  = build
    }

    void scan() {
        state.sonarResults = [:]
        def sonarCfg    = state.cfg.tools?.sonar ?: [:]
        def projectName = sonarCfg.projectName ?: ''
        def projectKey  = sonarCfg.projectKey  ?: ''
        def credentialsId = sonarCfg.credentialsId  ?: ''
        def installationName = sonarCfg.installationName  ?: ''
        boolean addBadges = sonarCfg.addBadges ?: false
        boolean fullBadges = sonarCfg.fullBadges ?: false
        def sonarUrl    = sonarCfg.serverUrl?.trim() ?: 'https://tools.bbh.com/sonar'
        def tool        = build.detectTool()
        def sonarArgs = [
            "-Dsonar.host.url=${sonarUrl}",
            projectKey  ? "-Dsonar.projectKey=${projectKey}"    : null,
            projectName ? "-Dsonar.projectName=${projectName}"  : null
        ].findAll { it }
        def scaFailReason = null
        try {
            if (tool == 'flutter') {
                scanFlutter(sonarCfg, sonarArgs, installationName)
            } else {
                if (credentialsId) {
                    script.withSonarQubeEnv(credentialsId: "${credentialsId}", installationName: "${installationName}") {
                        new BuildRunner(script, sonarCfg, tool).run()
                        if (addBadges) {
                            script.addSonarBadgesToDescription(sonarUrl, projectKey, fullBadges)
                        }
                    }
                } else {
                    script.withSonarQubeEnv("${installationName}") {
                        new BuildRunner(script, sonarCfg, tool).run()
                        if (addBadges) {
                            script.addSonarBadgesToDescription(sonarUrl, projectKey, fullBadges)
                        }
                    }
                }
            }
        state.sonarResults['url']      = projectKey ? "${sonarUrl}/dashboard?id=${projectKey}" : ''
        state.sonarResults['badgeUrl'] = (projectKey && sonarCfg.badgeToken?.trim())
                ? "${sonarUrl}/api/project_badges/quality_gate?project=${projectKey}&token=${sonarCfg.badgeToken.trim()}" : ''
        state.sonarResults['status']   = 'SKIP'
        } catch (e) {
            scaFailReason = "[SONAR] Scan failed: ${e.message ?: e.getClass().getSimpleName()}"
            state.sonarResults['status'] = 'WARN'
            state.policyStatus['sonar']  = 'WARN'
            state.recordSonar('WARN')
            state.recordScan('sonar', 'WARN')
            state.stageWarn('SCA (SonarQube)')
            state.stageError('SCA (SonarQube)', scaFailReason)
            script.unstable(scaFailReason)
            return
        }
        fetchIssueCounts(sonarUrl, projectKey)
        state.sonarResults['status'] = 'PASS'
        state.recordSonar('PASS')
        state.recordScan('sonar', 'PASS')
    }

    private void scanFlutter(Map sonarCfg, List sonarArgs, String installationName) {
        String env = (sonarCfg.sonarQubeEnv?.installationName ?: installationName ?: '') as String
        boolean plugin = sonarCfg.flutterPlugin ? true : false
        List args = sonarArgs + ["-Dsonar.sources=${sonarCfg.sources ?: 'lib'}".toString(),
                                 "-Dsonar.tests=${sonarCfg.tests ?: 'test'}".toString()]
        args.addAll(plugin ? flutterPluginArgs() : flutterGenericArgs(sonarCfg))
        script.withSonarQubeEnv(env ? [installationName: env] : [:]) {
            runScanner(sonarCfg, args.findAll { it })
        }
    }

    private List flutterPluginArgs() {
        return [
                script.fileExists('test_report.json') ? '-Dsonar.flutter.tests.reportPath=test_report.json' : null,
                script.fileExists('total_lcov.info') ? '-Dsonar.dart.lcov.reportPaths=total_lcov.info' : null
        ]
    }

    private List flutterGenericArgs(Map sonarCfg) {
        List args = []
        String workspace = (script.env.WORKSPACE ?: '') as String
        if (script.fileExists('total_lcov.info')) {
            String xml = FlutterSonarReports.coverageXml(script.readFile('total_lcov.info') as String, workspace)
            script.writeFile file: 'sonar-generic-coverage.xml', text: xml
            args << '-Dsonar.coverageReportPaths=sonar-generic-coverage.xml'
            script.echo '[SONAR] Flutter line coverage converted to the SonarQube generic coverage format'
        } else {
            script.echo '[SONAR] total_lcov.info not found - Flutter coverage will not be imported into SonarQube'
        }
        String analysis = runDartAnalyze(sonarCfg)
        if (analysis) {
            String json = FlutterSonarReports.issuesJson(analysis, workspace)
            script.writeFile file: 'sonar-dart-issues.json', text: json
            args << '-Dsonar.externalIssuesReportPaths=sonar-dart-issues.json'
            script.echo '[SONAR] dart analyze findings converted to the SonarQube generic issue format'
        }
        return args
    }

    private String runDartAnalyze(Map sonarCfg) {
        String command = (sonarCfg.dartAnalyzeCommand ?: 'dart analyze --format=machine') as String
        try {
            if (os.isWindows()) {
                return script.bat(returnStdout: true, script: "@echo off\r\n${command} > dart-analyze.txt 2>&1 & type dart-analyze.txt & exit /b 0") as String
            }
            return script.sh(returnStdout: true, script: "${command} 2>/dev/null || true") as String
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[SONAR] dart analyze could not be run (${e.message}) - only coverage will be imported"
            return ''
        }
    }

    private void runScanner(Map sonarCfg, List args) {
        String version = (sonarCfg.sonarScannerVersion ?: '') as String
        String home = version ? (script.tool(name: "sonar-scanner-cli-${version}") as String) : ''
        String joined = args.join(' ')
        if (os.isWindows()) {
            String binary = home ? "${home}\\bin\\sonar-scanner.bat" : 'sonar-scanner.bat'
            script.bat "\"${binary}\" ${joined}"
        } else {
            String binary = home ? "${home}/bin/sonar-scanner" : 'sonar-scanner'
            script.sh "'${binary}' ${joined}"
        }
    }

    private void fetchIssueCounts(String sonarUrl, String projectKey) {
        if (!projectKey) return
        try {
            def counts = ''
            script.withCredentials([script.string(credentialsId: state.cfg.tools?.sonar?.authToken, variable: "authToken")]) {
                if (os.isWindows()) {
                    counts = script.powershell(returnStdout: true, script: """
try {
    \$resp = Invoke-RestMethod -Uri "${sonarUrl}/api/issues/search?componentKeys=${projectKey}&types=VULNERABILITY&resolved=false&facets=severities&ps=1" -ErrorAction Stop
    \$c = @{}; foreach (\$item in \$resp.facets) { if (\$item.property -eq 'severities') { foreach (\$v in \$item.values) { \$c[\$v.val] = \$v.count } } }
    Write-Output "\$([int](\$c['BLOCKER'] + \$c['CRITICAL'])),\$([int]\$c['MAJOR']),\$([int]\$c['MINOR']),\$([int]\$c['INFO'])"
} catch { Write-Output '0,0,0,0' }
""").trim()
                } else {
                    counts = script.sh(returnStdout: true, script: """
_TMP=\$(mktemp)
curl -f -H "Authorization: Bearer \${authToken}" \\
"${sonarUrl}/api/issues/search?componentKeys=${projectKey}&types=VULNERABILITY&resolved=false&facets=severities&ps=1" > "\${_TMP}" 2>/dev/null || echo '{}' > "\${_TMP}"
python3 - "\${_TMP}" <<'PYEOF'
import json, sys
try:
    with open(sys.argv[1]) as f: d = json.load(f)
    c = {}
    for item in d.get('facets', []):
        if item.get('property') == 'severities':
            for v in item.get('values', []): c[v['val']] = v['count']
    crit = c.get('BLOCKER', 0) + c.get('CRITICAL', 0)
    print(str(crit)+','+str(c.get('MAJOR',0))+','+str(c.get('MINOR',0))+','+str(c.get('INFO',0)))
except Exception: print('0,0,0,0')
PYEOF
rm -f "\${_TMP}"
""").trim()
                }
                def parts = counts.split(',')
                if (parts.length == 4) {
                    state.vulnCounts.sca['critical'] = parts[0] as int
                    state.vulnCounts.sca['high'] = parts[1] as int
                    state.vulnCounts.sca['medium'] = parts[2] as int
                    state.vulnCounts.sca['low'] = parts[3] as int
                    state.sonarResults['critical'] = parts[0] as int
                    state.sonarResults['high'] = parts[1] as int
                    state.sonarResults['medium'] = parts[2] as int
                    state.sonarResults['low'] = parts[3] as int
                }
            }
        } catch (e) { script.echo "[SONAR] Could not fetch issue counts: ${e.message}" }
    }
}
