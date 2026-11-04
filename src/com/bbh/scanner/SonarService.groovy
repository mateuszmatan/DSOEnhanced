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
        String branch = resolveBranch()
        if (!BuildUtils.databaseBlock(state.cfg as Map)) applyDefaultSonarProperties(sonarCfg as Map, tool as String, branch)
        String effectiveProjectKey = projectKey ?: resolveProjectKey(sonarCfg as Map)
        String reportedKey = projectKey ?: effectiveProjectKey
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
            } else {
                Map runCfg = effectiveSonarCfg(sonarCfg as Map, tool as String)
                if (credentialsId) {
                    script.withSonarQubeEnv(credentialsId: "${credentialsId}", installationName: "${installationName}") {
                        new BuildRunner(script, runCfg, tool).run()
                        if (addBadges) {
                            script.addSonarBadgesToDescription(sonarUrl, effectiveProjectKey, fullBadges)
                        }
                    }
                } else {
                    script.withSonarQubeEnv("${installationName}") {
                        new BuildRunner(script, runCfg, tool).run()
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
            state.stageWarn('SCA (SonarQube)')
            state.stageError('SCA (SonarQube)', scaFailReason)
            script.unstable(scaFailReason)
            return
        }
        fetchIssueCounts(sonarUrl, reportedKey)
        state.sonarResults['status'] = 'PASS'
        state.recordSonar('PASS')
        state.recordScan('sonar', 'PASS')
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

    String resolveBranch() {
        String raw = ((script.env.CHANGE_BRANCH ?: script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: state.cfg?.scm?.bitbucket?.branch ?: state.cfg?.scm?.bitbucket?.targetBranch ?: state.cfg?.scm?.branch ?: state.cfg?.scm?.targetBranch ?: '') as String).trim()
        return BuildUtils.normalizeBranch(raw)
    }

    String resolveProjectKey(Map sonarCfg) {
        String key = ((sonarCfg?.projectKey ?: '') as String).trim()
        if (key) {
            return key.startsWith('com.bbh:') ? key : "com.bbh:${key}"
        }
        String fallback = ((sonarCfg?.projectName ?: state.cfg?.appName ?: state.currentProjectName ?: state.cfg?.primaryProject ?: 'app') as String).trim()
        return fallback.startsWith('com.bbh:') ? fallback : "com.bbh:${fallback}"
    }

    List<String> detectModules() {
        List<String> modules = []
        try {
            if (script.fileExists('pom.xml')) {
                String pom = script.readFile('pom.xml') as String
                def matcher = pom =~ /<module>\s*([^<\s]+)\s*<\/module>/
                while (matcher.find()) {
                    String mod = matcher.group(1).trim()
                    if (mod && !modules.contains(mod)) modules << mod
                }
            }
            if (!modules && (script.fileExists('settings.gradle') || script.fileExists('settings.gradle.kts'))) {
                String settingsFile = script.fileExists('settings.gradle') ? 'settings.gradle' : 'settings.gradle.kts'
                String settings = script.readFile(settingsFile) as String
                def matcher = settings =~ /include\s*\(?\s*['"](?::)?([^'",\s\)]+)['"]/
                while (matcher.find()) {
                    String mod = matcher.group(1).trim()
                    if (mod && !modules.contains(mod)) modules << mod
                }
            }
        } catch (Exception ignored) {}
        return modules
    }

    void applyDefaultSonarProperties(Map sonarCfg, String tool, String branch) {
        boolean exists = script.fileExists('sonar-project.properties')
        String dir = ((sonarCfg?.dir ?: '') as String).trim()
        if (dir && !exists) {
            exists = script.fileExists("${dir}/sonar-project.properties")
        }
        if (exists) {
            if (branch) {
                String targetPath = (dir && script.fileExists("${dir}/sonar-project.properties"))
                        ? "${dir}/sonar-project.properties" : 'sonar-project.properties'
                try {
                    String existing = script.readFile(targetPath) as String
                    if (!existing.contains('sonar.branch.name=')) {
                        String updated = existing.trim() + "\nsonar.branch.name=${branch}\n"
                        script.writeFile file: targetPath, text: updated
                    }
                } catch (Exception ignored) {}
            }
            return
        }

        String projectKey = resolveProjectKey(sonarCfg)
        List<String> modules = detectModules()
        String modulesProp = modules ? modules.join(',') : ''

        String binaries = 'target/classes'
        if (tool == 'gradle' || script.fileExists('build.gradle') || script.fileExists('build.gradle.kts')) {
            binaries = 'target/classes,build/classes/java/main'
        }

        String pomText = ''
        if (script.fileExists('pom.xml')) {
            try { pomText = script.readFile('pom.xml') as String } catch (Exception ignored) {}
        }

        String lang = 'java'
        if (tool == 'flutter' || script.fileExists('pubspec.yaml')) {
            lang = 'dart'
        } else if (tool == 'liquibase' || script.fileExists('changelog.xml')) {
            lang = 'sql'
        } else if (script.fileExists('requirements.txt') || script.fileExists('pyproject.toml') || script.fileExists('setup.py')) {
            lang = 'py'
        } else if (script.fileExists('src/main/kotlin') || script.fileExists('build.gradle.kts') || pomText.contains('kotlin')) {
            lang = 'kotlin'
        }

        String sources = ((sonarCfg?.sources ?: '') as String).trim()
        if (!sources) {
            if (lang == 'dart') {
                sources = 'lib'
            } else if (lang == 'sql') {
                sources = 'db'
            } else if (lang == 'py') {
                sources = script.fileExists('src') ? 'src' : '.'
            } else if (lang == 'kotlin') {
                sources = script.fileExists('src/main/java') ? 'src/main/java,src/main/kotlin' : 'src/main/kotlin'
            } else {
                sources = 'src/main/java'
            }
        }

        String tests = ((sonarCfg?.tests ?: '') as String).trim()
        if (!tests) {
            if (lang == 'dart') {
                tests = 'test'
            } else if (lang == 'sql') {
                tests = ''
            } else if (lang == 'py') {
                tests = script.fileExists('tests') ? 'tests' : (script.fileExists('test') ? 'test' : '')
            } else if (lang == 'kotlin') {
                tests = script.fileExists('src/test/java') ? 'src/test/java,src/test/kotlin' : 'src/test/kotlin'
            } else {
                tests = 'src/test/java'
            }
        }

        List<String> lines = [
            "sonar.java.binaries=${binaries}",
            'sonar.java.coveragePlugin=jacoco',
            "sonar.language=${lang}",
            'sonar.jacoco.reportPaths=target/jacoco.exec',
            'sonar.junit.reportPaths=target/surefire-reports',
            "sonar.projectKey=${projectKey}",
            "sonar.sources=${sources}",
            'sonar.sourceEncoding=UTF-8',
            "sonar.tests=${tests}",
            'sonar.dynamicAnalysis=reuseReports',
            "sonar.modules=${modulesProp}"
        ]
        if (branch) {
            lines << "sonar.branch.name=${branch}"
        }
        for (String mod : modules) {
            lines << "${mod}.sonar.projectName=${mod}"
            lines << "${mod}.sonar.sources=${mod}/src/main/java"
            lines << "${mod}.sonar.tests=${mod}/src/test/java"
            lines << "${mod}.sonar.java.binaries=${mod}/target/classes,${mod}/build/classes/java/main"
        }

        String content = lines.join('\n') + '\n'
        String targetPath = dir ? "${dir}/sonar-project.properties" : 'sonar-project.properties'
        script.writeFile file: targetPath, text: content
        if (dir && targetPath != 'sonar-project.properties') {
            script.writeFile file: 'sonar-project.properties', text: content
        }
    }
}
