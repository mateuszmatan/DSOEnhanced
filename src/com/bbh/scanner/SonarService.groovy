package com.bbh.scanner

import com.bbh.build.BuildRunner
import com.bbh.build.LiquibaseRunner
import com.bbh.build.DotnetBuildRunner
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.build.BuildService
import com.bbh.utils.BuildUtils
import com.bbh.utils.DatabaseSettings
import com.bbh.utils.FlutterSonarReports
import com.bbh.utils.LiquibaseOutput
import com.cloudbees.groovy.cps.NonCPS

class SonarService implements Serializable {

    private final def                        script
    private final PipelineState              state
    private final OsHelper                   os
    private final BuildService               build
    private final SonarScannerCommandBuilder commandBuilder

    SonarService(def script, PipelineState state, OsHelper os, BuildService build) {
        this.script         = script
        this.state          = state
        this.os             = os
        this.build          = build
        this.commandBuilder = new SonarScannerCommandBuilder(script, state, os)
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
        boolean isDotnet = BuildUtils.isDotnetTool(tool as String)
        boolean useGenericDotnet = BuildUtils.booleanValue(sonarCfg.useGenericScanner, false)
        String badgeToken = resolveBadgeToken(sonarCfg)
        state.sonarResults['badgeToken'] = badgeToken
        String branch = resolveBranch()
        if ((!isDotnet || useGenericDotnet) && !BuildUtils.databaseBlock(state.cfg as Map)) {
            applyDefaultSonarProperties(sonarCfg as Map, tool as String, branch)
        }
        String effectiveProjectKey = projectKey ?: resolveProjectKey(sonarCfg as Map)
        String reportedKey = projectKey ?: effectiveProjectKey
        if (effectiveProjectKey && state.sonarScansByProjectKey.containsKey(effectiveProjectKey)) {
            Map prior = state.sonarScansByProjectKey[effectiveProjectKey] as Map
            script.echo "[SONAR] ${state.currentProjectName}: SonarQube analysis for projectKey '${effectiveProjectKey}' was already executed by service '${prior.serviceName}' - reusing results"
            state.sonarResults = [:] + (prior.sonarResults ?: [:])
            if (prior.vulnCounts) {
                state.vulnCounts['sonar'] = [:] + (prior.vulnCounts as Map)
            }
            String st = (prior.status ?: 'PASS') as String
            state.recordSonar(st)
            state.recordScan('sonar', st)
            return
        }
        def sonarArgs = [
            "-Dsonar.host.url=${sonarUrl}",
            effectiveProjectKey ? "-Dsonar.projectKey=${effectiveProjectKey}"    : null,
            projectName ? "-Dsonar.projectName=${projectName}"  : null,
            branch      ? "-Dsonar.branch.name=${branch}"        : null
        ].findAll { it }
        def scaFailReason = null
        try {
            if (tool == 'flutter') {
                scanFlutter(sonarCfg, sonarArgs, installationName)
            } else if (tool == 'liquibase') {
                if (!scanLiquibase(sonarCfg, sonarArgs, installationName, credentialsId, addBadges, sonarUrl, effectiveProjectKey, fullBadges)) return
            } else if (isDotnet) {
                if (useGenericDotnet) {
                    scanDotnetGeneric(sonarCfg as Map, sonarArgs, installationName, credentialsId, addBadges, sonarUrl, effectiveProjectKey, fullBadges)
                } else {
                    scanDotnet(sonarCfg as Map, tool as String, branch, installationName, credentialsId, addBadges, sonarUrl, effectiveProjectKey, projectName as String, fullBadges)
                }
            } else if (tool in ['pip', 'npm', 'ios']) {
                scanGenericCli(sonarCfg as Map, tool as String, sonarArgs, installationName, credentialsId, addBadges, sonarUrl, effectiveProjectKey, fullBadges)
            } else {
                String runnerTool = tool == 'android' ? 'gradle' : (tool as String)
                Map runCfg = effectiveSonarCfg(sonarCfg as Map, runnerTool)
                withSonarEnv(credentialsId as String, installationName as String) {
                    new BuildRunner(script, runCfg, runnerTool, BuildUtils.pluginRepositories(state.cfg, state.cfgDefaults)).run()
                    if (addBadges) {
                        script.addSonarBadgesToDescription(sonarUrl, effectiveProjectKey, fullBadges)
                    }
                }
            }
        state.sonarResults['projectKey'] = reportedKey
        state.sonarResults['url']      = reportedKey ? "${sonarUrl}/dashboard?id=${reportedKey}" : ''
        state.sonarResults['badgeUrl'] = (reportedKey && badgeToken)
                ? "${sonarUrl}/api/project_badges/quality_gate?project=${reportedKey}&token=${badgeToken}" : ''
        state.sonarResults['status']   = 'SKIP'
        } catch (e) {
            scaFailReason = "[SONAR] Scan failed: ${e.message ?: e.getClass().getSimpleName()}"
            state.sonarResults['status'] = 'WARN'
            state.policyStatus['sonar']  = 'WARN'
            state.recordSonar('WARN')
            state.recordScan('sonar', 'WARN')
            if (effectiveProjectKey) {
                state.sonarScansByProjectKey[effectiveProjectKey] = [
                    serviceName : state.currentProjectName,
                    sonarResults: [:] + (state.sonarResults ?: [:]),
                    vulnCounts  : [:] + ((state.vulnCounts?.sonar ?: [:]) as Map),
                    status      : 'WARN'
                ]
            }
            state.stageWarn('SCA (SonarQube)')
            state.stageError('SCA (SonarQube)', scaFailReason)
            script.unstable(scaFailReason)
            return
        }
        fetchIssueCounts(sonarUrl, reportedKey)
        calculateCoverageIfMissing(sonarCfg as Map)
        state.sonarResults['status'] = 'PASS'
        state.recordSonar('PASS')
        state.recordScan('sonar', 'PASS')
        if (effectiveProjectKey) {
            state.sonarScansByProjectKey[effectiveProjectKey] = [
                serviceName : state.currentProjectName,
                sonarResults: [:] + (state.sonarResults ?: [:]),
                vulnCounts  : [:] + ((state.vulnCounts?.sonar ?: [:]) as Map),
                status      : state.sonarResults['status'] ?: 'PASS'
            ]
        }
    }

    private String resolveBadgeToken(Map sonarCfg) {
        String token = ((sonarCfg.badgeToken ?: '') as String).trim()
        String credentialsId = ((sonarCfg.badgeCredentialsId ?: '') as String).trim()
        if (!credentialsId) return token
        String bound = ''
        try {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'SONAR_BADGE_TOKEN')]) {
                bound = ((script.env.SONAR_BADGE_TOKEN ?: '') as String).trim()
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[SONAR] Badge token credentials ${credentialsId} could not be read (${e.message}) - ${token ? 'the badges use tools.sonar.badgeToken' : 'the report shows no SonarQube badges'}"
        }
        return bound ?: token
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
    \$hdrs = if (\$env:authToken) { @{ Authorization = "Bearer \$(\$env:authToken)" } } else { @{} }
    \$resp = Invoke-RestMethod -Uri "${sonarUrl}/api/issues/search?componentKeys=${projectKey}&types=VULNERABILITY&resolved=false&facets=severities&ps=1" -Headers \$hdrs -ErrorAction Stop
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

    private void calculateCoverageIfMissing(Map sonarCfg) {
        if (state.cfg?.buildTool == 'liquibase' || BuildUtils.databaseBlock(state.cfg as Map)) return
        Map projCov = (state.projectsCoverage?.get(state.currentProjectName) ?: [:]) as Map
        boolean missingCov = !state.projectsCoverage?.containsKey(state.currentProjectName)
        if (!projCov.get('enabled') && (BuildUtils.booleanValue(sonarCfg.calculateCoverageIfMissing, false)
                || (missingCov && (!state.stageResults.isEmpty() || !state.stageTimes.isEmpty())))) {
            script.echo "[SONAR] ${state.currentProjectName}: Code coverage was not calculated in unit tests - calculating coverage in SonarQube stage"
            build.checkCoverage()
        }
    }

    private void scanDotnetGeneric(Map sonarCfg, List sonarArgs, String installationName, String credentialsId, boolean addBadges, String sonarUrl, String projectKey, boolean fullBadges) {
        List args = [] + sonarArgs
        withSonarEnv(credentialsId, installationName) {
            runScanner(sonarCfg, args)
            if (addBadges) {
                script.addSonarBadgesToDescription(sonarUrl, projectKey, fullBadges)
            }
        }
    }

    private void scanGenericCli(Map sonarCfg, String tool, List sonarArgs, String installationName, String credentialsId, boolean addBadges, String sonarUrl, String projectKey, boolean fullBadges) {
        Map toolBlock = (sonarCfg.get(tool) instanceof Map ? sonarCfg.get(tool) : (tool == 'npm' && sonarCfg.node instanceof Map ? sonarCfg.node : [:])) as Map
        List args = [] + sonarArgs
        String sources = ((toolBlock.sources ?: sonarCfg.sources ?: (script.fileExists('src') ? 'src' : '.')) as String).trim()
        if (sources && !args.any { (it as String).startsWith('-Dsonar.sources=') }) {
            args << "-Dsonar.sources=${sources}".toString()
        }
        if (tool == 'pip' && script.fileExists('coverage.xml') && !args.any { (it as String).startsWith('-Dsonar.python.coverage.reportPaths=') }) {
            args << '-Dsonar.python.coverage.reportPaths=coverage.xml'
        } else if (tool == 'npm' && script.fileExists('coverage/lcov.info') && !args.any { (it as String).startsWith('-Dsonar.javascript.lcov.reportPaths=') }) {
            args << '-Dsonar.javascript.lcov.reportPaths=coverage/lcov.info'
            args << '-Dsonar.typescript.lcov.reportPaths=coverage/lcov.info'
        }
        args.addAll(BuildUtils.normalizeTokens(toolBlock.flags ?: sonarCfg.flags))
        withSonarEnv(credentialsId, installationName) {
            runScanner(sonarCfg, args)
            if (addBadges) {
                script.addSonarBadgesToDescription(sonarUrl, projectKey, fullBadges)
            }
        }
    }

    private void scanDotnet(Map sonarCfg, String tool, String branch, String installationName, String credentialsId, boolean addBadges, String sonarUrl, String projectKey, String projectName, boolean fullBadges) {
        DotnetBuildRunner dotnetRunner = new DotnetBuildRunner(script, os, state.cfg)
        Map buildDotnetCfg = DotnetBuildRunner.toolCfg(state.cfg.build as Map, tool)
        Map sonarDotnetCfg = DotnetBuildRunner.toolCfg(sonarCfg, tool)
        if (sonarCfg.dotnetMode) {
            sonarDotnetCfg = [:] + sonarDotnetCfg + [mode: sonarCfg.dotnetMode]
        }
        String mode = dotnetRunner.resolveMode(sonarDotnetCfg, buildDotnetCfg, tool)
        boolean classic = mode in ['msbuild', 'hybrid']

        List beginTokens = []
        if (classic) {
            String scannerExe = ((sonarCfg.sonarScannerMsbuildPath ?: sonarDotnetCfg.scannerPath ?: '') as String).trim()
            if (!scannerExe) scannerExe = os.isWindows() ? 'SonarScanner.MSBuild.exe' : 'sonar-scanner-msbuild'
            beginTokens << scannerExe
        } else {
            String dotnetExe = ((sonarDotnetCfg.dotnetPath ?: buildDotnetCfg.dotnetPath ?: '') as String).trim()
            if (!dotnetExe) dotnetExe = os.isWindows() ? 'dotnet.exe' : 'dotnet'
            beginTokens.addAll([dotnetExe, 'sonarscanner'])
        }
        beginTokens << 'begin'
        if (projectKey) beginTokens << "/k:${projectKey}".toString()
        if (projectName) beginTokens << "/n:${projectName}".toString()
        beginTokens << "/d:sonar.host.url=${sonarUrl}".toString()
        if (branch) beginTokens << "/d:sonar.branch.name=${branch}".toString()
        beginTokens << '/d:sonar.sourceEncoding=UTF-8'

        boolean hasVb = resolveDotnetReportParam(null, ['*.vbproj', '**/*.vbproj']) != ''
        String opencover = resolveDotnetReportParam(sonarCfg.opencoverPaths ?: sonarDotnetCfg.opencoverPaths, ['**/coverage.opencover.xml', '**/*opencover*.xml'])
        if (opencover) {
            beginTokens << "/d:sonar.cs.opencover.reportsPaths=${opencover}".toString()
            if (hasVb) beginTokens << "/d:sonar.vbnet.opencover.reportsPaths=${opencover}".toString()
        }
        String vscover = resolveDotnetReportParam(sonarCfg.vscoveragexmlPaths ?: sonarDotnetCfg.vscoveragexmlPaths, ['**/*.coveragexml'])
        if (vscover) beginTokens << "/d:sonar.cs.vscoveragexml.reportsPaths=${vscover}".toString()
        String dotcover = resolveDotnetReportParam(sonarCfg.dotcoverPaths ?: sonarDotnetCfg.dotcoverPaths, [])
        if (dotcover) beginTokens << "/d:sonar.cs.dotcover.reportsPaths=${dotcover}".toString()
        String vstest = resolveDotnetReportParam(sonarCfg.vstestPaths ?: sonarDotnetCfg.vstestPaths, ['**/TestResults/*.trx', '**/*.trx'])
        if (vstest) {
            beginTokens << "/d:sonar.cs.vstest.reportsPaths=${vstest}".toString()
            if (hasVb) beginTokens << "/d:sonar.vbnet.vstest.reportsPaths=${vstest}".toString()
        }
        String nunit = resolveDotnetReportParam(sonarCfg.nunitPaths ?: sonarDotnetCfg.nunitPaths, [])
        if (nunit) beginTokens << "/d:sonar.cs.nunit.reportsPaths=${nunit}".toString()
        String xunit = resolveDotnetReportParam(sonarCfg.xunitPaths ?: sonarDotnetCfg.xunitPaths, [])
        if (xunit) beginTokens << "/d:sonar.cs.xunit.reportsPaths=${xunit}".toString()
        beginTokens.addAll(BuildUtils.normalizeTokens(sonarDotnetCfg.beginArgs ?: sonarDotnetCfg.flags ?: sonarCfg.flags))

        List endTokens = (classic ? [beginTokens[0], 'end'] : [beginTokens[0], 'sonarscanner', 'end'])
        endTokens.addAll(BuildUtils.normalizeTokens(sonarDotnetCfg.endArgs))

        String beginCmd = formatDotnetCmd(beginTokens)
        String endCmd = formatDotnetCmd(endTokens)
        Map compileCfg = [:] + buildDotnetCfg + sonarDotnetCfg
        if (compileCfg.rebuild == null) compileCfg.rebuild = true

        Closure invoke = {
            execDotnetSonarStep(beginCmd, 'SonarQube .NET: begin')
            dotnetRunner.build(compileCfg, tool)
            execDotnetSonarStep(endCmd, 'SonarQube .NET: end')
            if (addBadges) {
                script.addSonarBadgesToDescription(sonarUrl, projectKey, fullBadges)
            }
        }
        withSonarEnv(credentialsId, installationName, invoke)
    }

    private void withSonarEnv(String credentialsId, String installationName, Closure body) {
        if (credentialsId) {
            script.withSonarQubeEnv(credentialsId: "${credentialsId}", installationName: "${installationName}", body)
        } else {
            script.withSonarQubeEnv("${installationName}", body)
        }
    }

    private void execDotnetSonarStep(String cmd, String label) {
        script.echo "[SONAR] ${label}: ${cmd}"
        if (os.isWindows()) {
            script.bat(label: label, script: cmd)
        } else {
            BuildUtils.execSh(script, cmd, label, false)
        }
    }

    private String resolveDotnetReportParam(def configured, List<String> globs) {
        return commandBuilder.resolveDotnetReportParam(configured, globs)
    }

    private String formatDotnetCmd(List tokens) {
        return commandBuilder.formatDotnetCmd(tokens)
    }

    private Map effectiveSonarCfg(Map sonarCfg, String tool) {
        return commandBuilder.effectiveSonarCfg(sonarCfg, tool)
    }

    private boolean scanLiquibase(Map sonarCfg, List sonarArgs, String installationName, String credentialsId, boolean addBadges, String sonarUrl, String projectKey, boolean fullBadges) {
        Map liqCfg = (sonarCfg.liquibase ?: [:]) as Map
        boolean block = BuildUtils.databaseBlock(state.cfg)
        Map s = block ? DatabaseSettings.resolve(state.currentProjectName, state.cfg) : [:]
        String folder = block ? DatabaseSettings.join(DatabaseSettings.str(liqCfg.dir), '.') : '.'
        if (block) {
            state.vulnCounts.sca = [critical: 0, high: 0, medium: 0, low: 0]
            if (!sonarCfg.projectKey) {
                databaseNotRequired('no SonarQube project for this service (tools.sonar.projectKey) - SonarQube is not required for SQL and PL/SQL')
                return false
            }
        }
        List extra = BuildUtils.normalizeTokens(liqCfg.commands ?: liqCfg.tasks).findAll { !(it in ['sonar', 'sonarqube', 'sonar:sonar']) } + BuildUtils.normalizeTokens(liqCfg.flags)
        List args = [] + sonarArgs + extra
        if (!args.any { (it as String).startsWith('-Dsonar.sources=') }) {
            def rawSources = liqCfg.sources ?: sonarCfg.sources ?: (block ? DatabaseSettings.relativeTo(folder, DatabaseSettings.join(s.searchRoot as String, s.packageFolder as String)) : '') ?:
                    (liqCfg.dir ? '.' : state.cfg.build?.liquibase?.dir ?: state.cfg.sourceDir ?: '.')
            String sources = (rawSources instanceof Collection ? (rawSources as Collection).join(',') : rawSources) as String
            args.add("-Dsonar.sources=${sources}".toString())
        }
        if (liqCfg.inclusions && !args.any { (it as String).startsWith('-Dsonar.inclusions=') }) {
            String inc = (liqCfg.inclusions instanceof Collection ? (liqCfg.inclusions as Collection).join(',') : liqCfg.inclusions) as String
            args.add("-Dsonar.inclusions=${inc}".toString())
        }
        if (liqCfg.exclusions && !args.any { (it as String).startsWith('-Dsonar.exclusions=') }) {
            String exc = (liqCfg.exclusions instanceof Collection ? (liqCfg.exclusions as Collection).join(',') : liqCfg.exclusions) as String
            args.add("-Dsonar.exclusions=${exc}".toString())
        }
        boolean scanned = true
        Closure invoke = {
            if (block) {
                scanned = scanDatabase(sonarCfg, args, s, sonarUrl, folder)
            } else {
                runScanner(sonarCfg, args)
            }
            if (scanned && addBadges) {
                script.addSonarBadgesToDescription(sonarUrl, projectKey, fullBadges)
            }
        }
        Closure runBody = {
            List<String> env = []
            if (liqCfg.env instanceof Map && !liqCfg.env.isEmpty()) {
                env.addAll(liqCfg.env.collect { k, v -> "${k}=${v}".toString() } as List<String>)
            }
            if (liqCfg.dir && folder == '.') {
                script.dir(liqCfg.dir as String) {
                    if (env) script.withEnv(env) { invoke() } else invoke()
                }
            } else {
                if (env) script.withEnv(env) { invoke() } else invoke()
            }
        }
        if (credentialsId) {
            script.withSonarQubeEnv(credentialsId: "${credentialsId}", installationName: "${installationName}") {
                runBody()
            }
        } else {
            script.withSonarQubeEnv("${installationName}") {
                runBody()
            }
        }
        return scanned
    }

    private boolean scanDatabase(Map sonarCfg, List args, Map s, String sonarUrl, String folder) {
        String name = state.currentProjectName
        LiquibaseRunner runner = new LiquibaseRunner(script, state, os, build)
        String dir = runner.workDir(name)
        try {
            String languages = "${dir}/sonar-languages.txt"
            int status = script.sh(returnStatus: true, label: 'DB: ask SonarQube for its PL/SQL analyzer', script: LiquibaseOutput.sonarLanguagesScript(sonarUrl, dir)) as int
            String analyzer = status == 0 && script.fileExists(languages) ? LiquibaseOutput.sonarLanguages(script.readFile(languages) as String).analyzer : ''
            if (!analyzer) {
                databaseNotRequired(status != 0
                        ? "the SonarQube server did not list its languages (curl exit code ${status}) - SonarQube is not required for SQL and PL/SQL; when the server needs a login, give the SonarQube installation in Jenkins a token or set tools.sonar.credentialsId".toString()
                        : 'the SonarQube server has no PL/SQL analyzer (no language plsql or plsqlopen) - SonarQube is not required for SQL and PL/SQL')
                return false
            }
            script.echo "[SONAR] ${name}: the SonarQube server analyses PL/SQL with ${analyzer == 'plsql' ? 'SonarSource PL/SQL' : 'the ZPA plugin'} (language ${analyzer})"
            String testPath = ((((s.profiles as Map).test as Map).utplsql as Map).testPath ?: '') as String
            String tests = testPath ? DatabaseSettings.relativeTo(folder, DatabaseSettings.join(s.searchRoot as String, testPath)) : ''
            String out = ".dso-db/${name}"
            String settings = "${dir}/sonar-project.properties".toString()
            Map wanted = [("-Dsonar.${analyzer}.file.suffixes=".toString()): 'sql,pks,pkb,pls,plb,pck,prc,fnc,trg,vw,tps,tpb,typ,bdy',
                          '-Dsonar.tests=': tests, '-Dsonar.exclusions=': tests ? "${tests}/**".toString() : '',
                          '-Dsonar.coverageReportPaths=': utplsqlReport(out, dir, 'utplsql-coverage-sonar.xml', folder),
                          '-Dsonar.testExecutionReportPaths=': utplsqlReport(out, dir, 'utplsql-sonar-tests.xml', folder),
                          '-Dproject.settings=': settings]
            List own = testsExcluded(args, tests)
            List run = own + missingFlags(own, wanted)
            if (run.contains("-Dproject.settings=${settings}".toString())) script.writeFile(file: settings, text: '', encoding: 'UTF-8')
            script.withEnv(runner.baseEnv(runner.javaHome(name, state.cfg))) {
                if (folder == '.') {
                    runScanner(sonarCfg, run.collect { BuildUtils.shellQuoteIfNeeded(it as String) })
                } else {
                    script.dir(folder) {
                        runScanner(sonarCfg, run.collect { BuildUtils.shellQuoteIfNeeded(it as String) })
                    }
                }
            }
            return true
        } finally {
            script.dir(dir) {
                script.deleteDir()
            }
        }
    }

    private String utplsqlReport(String out, String dir, String report, String folder) {
        String path = "${out}/${report}".toString()
        if (!script.fileExists(path)) return ''
        if (folder == '.') return path
        String copy = "${dir}/${report}".toString()
        String text = script.readFile(file: path, encoding: 'UTF-8') as String
        script.writeFile(file: copy, text: LiquibaseOutput.sonarReportIn(text, folder), encoding: 'UTF-8')
        return copy
    }

    @NonCPS
    private static List testsExcluded(List args, String tests) {
        String prefix = '-Dsonar.exclusions='
        String pattern = "${tests}/**".toString()
        if (!tests || args.any { (it as String).startsWith('-Dsonar.tests=') }) return args
        return args.collect { def arg ->
            String text = arg as String
            List own = text.startsWith(prefix) ? text.substring(prefix.length()).tokenize(',') : []
            text.startsWith(prefix) && !own.contains(pattern) ? "${prefix}${(own + [pattern]).join(',')}".toString() : arg
        }
    }

    @NonCPS
    private static List missingFlags(List args, Map wanted) {
        List flags = []
        for (def entry : wanted.entrySet()) {
            String prefix = entry.key as String
            if (entry.value && !args.any { (it as String).startsWith(prefix) }) flags << "${prefix}${entry.value}".toString()
        }
        return flags
    }

    private void databaseNotRequired(String reason) {
        state.sonarResults['status'] = 'NOT_REQUIRED'
        state.recordScan('sonar', 'NOT_REQUIRED')
        state.projectNotRequired()
        if (state.policyStatus['sonar'] == 'SKIP') state.policyStatus['sonar'] = 'NOT_REQUIRED'
        script.echo "[SONAR] ${state.currentProjectName}: ${reason}"
    }

    String resolveBranch() {
        return commandBuilder.resolveBranch()
    }

    String resolveProjectKey(Map sonarCfg) {
        return commandBuilder.resolveProjectKey(sonarCfg)
    }

    List<String> detectModules() {
        return commandBuilder.detectModules()
    }

    void applyDefaultSonarProperties(Map sonarCfg, String tool, String branch) {
        commandBuilder.applyDefaultSonarProperties(sonarCfg, tool, branch)
    }
}
