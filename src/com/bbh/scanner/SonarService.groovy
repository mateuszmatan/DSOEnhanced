package com.bbh.scanner

import com.bbh.build.BuildRunner
import com.bbh.build.LiquibaseRunner
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.build.BuildService
import com.bbh.utils.BuildUtils
import com.bbh.utils.DatabaseSettings
import com.bbh.utils.FlutterSonarReports
import com.bbh.utils.LiquibaseOutput
import com.cloudbees.groovy.cps.NonCPS

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
        String badgeToken = resolveBadgeToken(sonarCfg)
        state.sonarResults['badgeToken'] = badgeToken
        String branch = resolveBranch(sonarCfg as Map)
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
        boolean pluginRun = (tool in ['gradle', 'maven']) && hasPluginCommands(sonarCfg as Map, tool as String)
        boolean mavenPluginRun = tool == 'maven' && hasPluginCommands(sonarCfg as Map, 'maven')
        boolean scannerRun = !(tool in ['flutter', 'liquibase']) && !mavenPluginRun && !BuildUtils.databaseBlock(state.cfg as Map)
        if (scannerRun) applyDefaultSonarProperties(sonarCfg as Map, tool as String, resolveProjectKey(sonarCfg as Map))
        boolean passKey = !pluginRun || autoResolvedKey()
        def sonarArgs = [
            "-Dsonar.host.url=${sonarUrl}",
            (effectiveProjectKey && passKey) ? "-Dsonar.projectKey=${effectiveProjectKey}"    : null,
            projectName ? "-Dsonar.projectName=${projectName}"  : null
        ].findAll { it }
        List branchArgs = branch ? ["-Dsonar.branch.name=${branch}".toString()] : []
        def scaFailReason = null
        boolean branchUsed = false
        try {
            if (tool == 'flutter') {
                branchUsed = withBranchFallback(branchArgs) { List extra -> scanFlutter(sonarCfg, sonarArgs + extra, installationName) }
            } else if (tool == 'liquibase') {
                boolean ok = true
                branchUsed = withBranchFallback(branchArgs) { List extra ->
                    ok = scanLiquibase(sonarCfg, sonarArgs + extra, installationName, credentialsId, addBadges, sonarUrl, effectiveProjectKey, fullBadges)
                    return ok
                }
                if (!ok) return
            } else {
                branchUsed = withBranchFallback(branchArgs) { List extra ->
                    inSonarEnv(credentialsId as String, installationName as String) {
                        if (pluginRun) {
                            new BuildRunner(script, pluginCfg(effectiveSonarCfg(sonarCfg as Map, tool as String), tool as String, sonarArgs.findAll { (it as String).startsWith('-Dsonar.projectKey=') } + extra), tool).run()
                        } else {
                            runScanner(sonarCfg as Map, sonarArgs + extra)
                        }
                        if (addBadges) {
                            script.addSonarBadgesToDescription(sonarUrl, effectiveProjectKey, fullBadges)
                        }
                    }
                }
            }
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
        fetchIssueCounts(sonarUrl, reportedKey, branchUsed ? branch : '')
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

    private void fetchIssueCounts(String sonarUrl, String projectKey, String branch = '') {
        String branchQuery = branch ? '&branch=' + com.bbh.utils.RestClient.urlEncode(branch) : ''
        if (!projectKey) return
        try {
            def counts = ''
            script.withCredentials([script.string(credentialsId: state.cfg.tools?.sonar?.authToken, variable: "authToken")]) {
                if (os.isWindows()) {
                    counts = script.powershell(returnStdout: true, script: """
try {
    \$resp = Invoke-RestMethod -Uri "${sonarUrl}/api/issues/search?componentKeys=${projectKey}${branchQuery}&types=VULNERABILITY&resolved=false&facets=severities&ps=1" -ErrorAction Stop
    \$c = @{}; foreach (\$item in \$resp.facets) { if (\$item.property -eq 'severities') { foreach (\$v in \$item.values) { \$c[\$v.val] = \$v.count } } }
    Write-Output "\$([int](\$c['BLOCKER'] + \$c['CRITICAL'])),\$([int]\$c['MAJOR']),\$([int]\$c['MINOR']),\$([int]\$c['INFO'])"
} catch { Write-Output '0,0,0,0' }
""").trim()
                } else {
                    counts = script.sh(returnStdout: true, script: """
_TMP=\$(mktemp)
curl -f -H "Authorization: Bearer \${authToken}" \\
"${sonarUrl}/api/issues/search?componentKeys=${projectKey}${branchQuery}&types=VULNERABILITY&resolved=false&facets=severities&ps=1" > "\${_TMP}" 2>/dev/null || echo '{}' > "\${_TMP}"
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

    private Map effectiveSonarCfg(Map sonarCfg, String tool) {
        if (!(sonarCfg.liquibase instanceof Map)) return sonarCfg
        Map liqCfg = sonarCfg.liquibase as Map
        List extraFlags = []
        if (liqCfg.sources) {
            String src = (liqCfg.sources instanceof Collection ? (liqCfg.sources as Collection).join(',') : liqCfg.sources) as String
            extraFlags << "-Dsonar.sources=${src}".toString()
        }
        if (liqCfg.inclusions) {
            String inc = (liqCfg.inclusions instanceof Collection ? (liqCfg.inclusions as Collection).join(',') : liqCfg.inclusions) as String
            extraFlags << "-Dsonar.inclusions=${inc}".toString()
        }
        if (liqCfg.exclusions) {
            String exc = (liqCfg.exclusions instanceof Collection ? (liqCfg.exclusions as Collection).join(',') : liqCfg.exclusions) as String
            extraFlags << "-Dsonar.exclusions=${exc}".toString()
        }
        extraFlags.addAll(BuildUtils.normalizeTokens(liqCfg.flags))
        if (!extraFlags) return sonarCfg
        Map toolBlock = [:]
        if (sonarCfg.get(tool) instanceof Map) toolBlock.putAll(sonarCfg.get(tool) as Map)
        List existingFlags = BuildUtils.normalizeTokens(toolBlock.flags)
        for (String flag : extraFlags) {
            String prefix = flag.contains('=') ? flag.substring(0, flag.indexOf('=') + 1) : flag
            if (!existingFlags.any { (it as String).startsWith(prefix) }) {
                existingFlags << flag
            }
        }
        toolBlock.flags = existingFlags
        Map copy = [:]
        copy.putAll(sonarCfg)
        copy[tool] = toolBlock
        return copy
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

    String resolveBranch(Map sonarCfg = [:]) {
        if (sonarCfg?.containsKey('branchAnalysis') && !BuildUtils.booleanValue(sonarCfg.branchAnalysis, true)) return ''
        String raw = ((sonarCfg?.branch ?: script.env.CHANGE_BRANCH ?: script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: state.cfg?.scm?.bitbucket?.branch ?: state.cfg?.scm?.branch ?: '') as String).trim()
        if (!raw && script.isUnix()) {
            try {
                raw = (script.sh(returnStdout: true, script: '''#!/bin/bash
b="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
if [ -z "$b" ] || [ "$b" = "HEAD" ]; then
  b="$(git for-each-ref --points-at HEAD --format='%(refname:short)' refs/remotes 2>/dev/null | grep -v '/HEAD$' | head -n 1 || true)"
fi
echo "$b"
''') ?: '') as String
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                raw = ''
            }
        }
        String branch = BuildUtils.normalizeBranch(raw.trim())
        return branch == 'HEAD' ? '' : branch
    }

    String resolveProjectKey(Map sonarCfg) {
        String key = ((sonarCfg?.projectKey ?: '') as String).trim()
        if (key) {
            return key.startsWith('com.bbh:') ? key : "com.bbh:${key}"
        }
        String fallback = ((sonarCfg?.projectName ?: state.cfg?.appName ?: state.currentProjectName ?: state.cfg?.primaryProject ?: 'app') as String).trim()
        return fallback.startsWith('com.bbh:') ? fallback : "com.bbh:${fallback}"
    }

    static boolean hasPluginCommands(Map sonarCfg, String tool) {
        Map block = (sonarCfg?.get(tool) instanceof Map) ? sonarCfg.get(tool) as Map : [:]
        def commands = tool == 'gradle' ? block.tasks : block.goals
        return BuildUtils.normalizeTokens(commands) ? true : false
    }

    private boolean autoResolvedKey() {
        List resolved = (state.projectsAutoResolved?.get(state.currentProjectName) ?: []) as List
        for (def r : resolved) {
            if ((r as Map).field == 'tools.sonar.projectKey') return true
        }
        return false
    }

    static Map pluginCfg(Map sonarCfg, String tool, List extraFlags) {
        Map block = [:]
        if (sonarCfg?.get(tool) instanceof Map) block.putAll(sonarCfg.get(tool) as Map)
        List flags = BuildUtils.normalizeTokens(block.flags)
        for (def flag : extraFlags) {
            String f = flag as String
            String prefix = f.contains('=') ? f.substring(0, f.indexOf('=') + 1) : f
            boolean present = false
            for (def existing : flags) {
                if ((existing as String).startsWith(prefix)) present = true
            }
            if (!present) flags << f
        }
        block.flags = flags
        Map copy = [:]
        copy.putAll(sonarCfg ?: [:])
        copy[tool] = block
        return copy
    }

    private void inSonarEnv(String credentialsId, String installationName, Closure body) {
        if (credentialsId) {
            script.withSonarQubeEnv(credentialsId: credentialsId, installationName: installationName) {
                body()
            }
        } else {
            script.withSonarQubeEnv(installationName) {
                body()
            }
        }
    }

    private boolean withBranchFallback(List branchArgs, Closure body) {
        if (!branchArgs) {
            body([])
            return false
        }
        try {
            body(branchArgs)
            return true
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[SONAR] The analysis with ${branchArgs[0]} failed (${e.message ?: e.getClass().getSimpleName()}) - running it again without the branch: SonarQube Community Edition analyses only the main branch, set tools.sonar.branchAnalysis: false to skip this attempt"
            body([])
            return false
        }
    }

    Map detectLayout(String tool) {
        List<String> modules = []
        try {
            if (script.fileExists('pom.xml')) modules.addAll(mavenModules(script.readFile('pom.xml') as String))
            if (!modules) {
                String settingsFile = script.fileExists('settings.gradle') ? 'settings.gradle' : (script.fileExists('settings.gradle.kts') ? 'settings.gradle.kts' : '')
                if (settingsFile) modules.addAll(gradleModules(script.readFile(settingsFile) as String))
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
        }
        List<String> excluded = []
        if (state.cfg?.excludedDirs) excluded.addAll(BuildUtils.normalizeTokens(state.cfg.excludedDirs))
        for (def entry : (state.projectsAllCfg ?: [:]).entrySet()) {
            Map p = (entry.value instanceof Map) ? (entry.value as Map) : [:]
            if (BuildUtils.databaseBlock(p)) {
                String dir = (p.sourceDir ?: p.database?.searchPath ?: entry.key) as String
                if (dir) excluded << dir
            }
        }
        if (excluded) {
            modules.removeAll { String m -> excluded.any { String ex -> m == ex || m.startsWith("${ex}/") } }
        }
        Map dirsByModule = [:]
        List<String> roots = modules ? modules : ['']
        for (String m : roots) {
            Map dirs = [:]
            for (String d : candidateDirs()) {
                if (script.fileExists(m ? "${m}/${d}".toString() : d)) dirs[d] = true
            }
            dirsByModule[m] = dirs
        }
        return [tool   : tool, modules: modules, dirs: dirsByModule,
                kotlin : script.fileExists('build.gradle.kts') || script.fileExists('src/main/kotlin'),
                python : script.fileExists('requirements.txt') || script.fileExists('pyproject.toml') || script.fileExists('setup.py'),
                flutter: script.fileExists('pubspec.yaml')]
    }

    @NonCPS
    static List<String> candidateDirs() {
        return ['src/main/java', 'src/main/kotlin', 'src/test/java', 'src/test/kotlin', 'src', 'test', 'tests', 'lib', 'db', 'sql',
                'target/classes', 'build/classes/java/main', 'build/classes/kotlin/main']
    }

    @NonCPS
    static List<String> mavenModules(String pom) {
        List<String> modules = []
        def m = pom =~ /<module>\s*([^<\s]+)\s*<\/module>/
        while (m.find()) {
            String mod = m.group(1).trim()
            if (mod && !modules.contains(mod)) modules << mod
        }
        return modules
    }

    @NonCPS
    static List<String> gradleModules(String settings) {
        List<String> modules = []
        def statement = settings =~ /(?m)^\s*include\s*\(?([^\n]*)/
        while (statement.find()) {
            def names = statement.group(1) =~ /['"]:?([^'",\s\)]+)['"]/
            while (names.find()) {
                String mod = names.group(1).replace(':', '/')
                if (mod && !modules.contains(mod)) modules << mod
            }
        }
        return modules
    }

    @NonCPS
    static String language(Map layout) {
        String tool = (layout.tool ?: '') as String
        if (layout.flutter) return 'dart'
        if (tool in ['npm', 'node']) return 'js'
        if (tool == 'liquibase') return 'plsql'
        if (layout.python && !(tool in ['maven', 'gradle'])) return 'py'
        if (layout.kotlin) return 'kotlin'
        return 'java'
    }

    @NonCPS
    static String defaultProperties(Map layout, String projectKey) {
        String tool = (layout.tool ?: '') as String
        Map dirsByModule = (layout.dirs ?: [:]) as Map
        List<String> modules = (layout.modules ?: []) as List<String>
        Map probe = (dirsByModule[modules ? modules[0] : ''] ?: [:]) as Map
        String lang = language(layout)
        boolean jvm = lang in ['java', 'kotlin']
        boolean gradle = tool == 'gradle'
        List<String> lines = ['# Some properties that will be inherited by the modules']
        if (jvm) {
            lines << "sonar.java.binaries=${gradle ? (lang == 'kotlin' ? 'build/classes/kotlin/main,build/classes/java/main' : 'build/classes/java/main') : 'target/classes'}".toString()
            lines << 'sonar.java.coveragePlugin=jacoco'
        }
        lines << "sonar.language=${lang}".toString()
        if (jvm) {
            lines << (gradle ? 'sonar.jacoco.reportPaths=build/jacoco/test.exec' : 'sonar.jacoco.reportPaths=target/jacoco.exec')
            lines << (gradle ? 'sonar.coverage.jacoco.xmlReportPaths=build/reports/jacoco/test/jacocoTestReport.xml' : 'sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml')
            lines << (gradle ? 'sonar.junit.reportPaths=build/test-results/test' : 'sonar.junit.reportPaths=target/surefire-reports')
        }
        if (lang == 'js') lines << 'sonar.javascript.lcov.reportPaths=coverage/lcov.info'
        lines << "sonar.projectKey=${projectKey}".toString()
        lines << "sonar.sources=${sourcesOf(lang, probe)}".toString()
        lines << 'sonar.sourceEncoding=UTF-8'
        String tests = testsOf(lang, probe)
        if (tests) lines << "sonar.tests=${tests}".toString()
        if (jvm) lines << 'sonar.dynamicAnalysis=reuseReports'
        if (modules) {
            lines << '# List of the module identifiers'
            lines << ('sonar.modules=' + modules.collect { (it as String).replace('/', '_') }.join(','))
            for (String m : modules) {
                String id = m.replace('/', '_')
                Map d = (dirsByModule[m] ?: [:]) as Map
                if (id != m) lines << "${id}.sonar.projectBaseDir=${m}".toString()
                String prefix = gradle ? (m ? "${m}/" : '') : ''
                lines << "${id}.sonar.sources=${prefix}${sourcesOf(lang, d)}".toString()
                lines << "${id}.sonar.tests=${prefix}${testsOf(lang, d)}".toString()
            }
        }
        return lines.join('\n') + '\n'
    }

    @NonCPS
    static String sourcesOf(String lang, Map d) {
        if (lang in ['java', 'kotlin']) {
            if (!d) return lang == 'kotlin' ? 'src/main/kotlin' : 'src/main/java'
            List<String> src = []
            if (d['src/main/java']) src << 'src/main/java'
            if (d['src/main/kotlin']) src << 'src/main/kotlin'
            return src ? src.join(',') : (d['src'] ? 'src' : '.')
        }
        if (lang == 'dart') return 'lib'
        if (lang == 'plsql') return d['db'] ? 'db' : (d['sql'] ? 'sql' : '.')
        return d['src'] ? 'src' : '.'
    }

    @NonCPS
    static String testsOf(String lang, Map d) {
        if (lang in ['java', 'kotlin']) {
            if (!d) return lang == 'kotlin' ? 'src/test/kotlin' : 'src/test/java'
            List<String> tst = []
            if (d['src/test/java']) tst << 'src/test/java'
            if (d['src/test/kotlin']) tst << 'src/test/kotlin'
            return tst.join(',')
        }
        if (lang == 'plsql') return ''
        if (d['tests']) return 'tests'
        return d['test'] ? 'test' : ''
    }

    void applyDefaultSonarProperties(Map sonarCfg, String tool, String projectKey) {
        String dir = ((sonarCfg?.dir ?: '') as String).trim()
        if (script.fileExists('sonar-project.properties') || (dir && script.fileExists("${dir}/sonar-project.properties"))) {
            return
        }
        String content = defaultProperties(detectLayout(tool), projectKey)
        script.writeFile file: 'sonar-project.properties', text: content
    }
}
