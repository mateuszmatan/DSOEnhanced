package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.BuildUtils
import com.bbh.utils.FlutterUtils
import com.cloudbees.groovy.cps.NonCPS

class BuildService implements Serializable {
    private final def                  script
    private final PipelineState        state
    private final OsHelper             os
    private final PolicyEngine         policy
    private final JdkToolchainSetup    toolchain
    private final RemoteTestJobRunner  remoteRunner
    private final LiquibaseBuildRunner liquibaseRunner
    private final UnitTestReportParser reportParser

    BuildService(def script, PipelineState state, OsHelper os, PolicyEngine policy) {
        this.script          = script
        this.state           = state
        this.os              = os
        this.policy          = policy
        this.toolchain       = new JdkToolchainSetup(script, state)
        this.remoteRunner    = new RemoteTestJobRunner(script, state, policy)
        this.liquibaseRunner = new LiquibaseBuildRunner(script, state, os, policy)
        this.reportParser    = new UnitTestReportParser(script, state, os)
    }

    void nodeBuild() {
        new NodeBuildRunner(script, os).build(state.currentProjectName, (state.cfg.build?.node ?: [:]) as Map)
    }

    void buildArtifact() {
        def tool = setupBuildTool()
        script.echo "[BUILD] Tool: ${tool} OS: ${os.getType()}"
        if (BuildUtils.isDotnetTool(tool as String)) {
            new DotnetBuildRunner(script, os, state.cfg).build(null, tool as String)
            if (hasLiquibaseStep(state.cfg.build?.liquibase)) {
                script.echo "[BUILD] Running additional Liquibase step for multi-module project"
                runLiquibaseBuild()
            }
            return
        }
        switch (tool) {
            case ['gradle', 'android', 'maven']:
                String runnerTool = tool == 'android' ? 'gradle' : (tool as String)
                BuildRunner runner = new BuildRunner(script, state.cfg.build as Map, runnerTool)
                if (!runner.ifExist()) {
                    script.error "[BUILD] Build tool is NOT installed on an agent"
                }
                runner.run()
                if (hasLiquibaseStep(state.cfg.build?.liquibase)) {
                    script.echo "[BUILD] Running additional Liquibase step for multi-module project"
                    runLiquibaseBuild()
                }
                break
            case 'flutter':
                script.withCredentials([
                        script.string(credentialsId: "${state.cfg.build.credentialsId[0]}", variable: 'password'),
                        script.string(credentialsId: "${state.cfg.build.credentialsId[1]}", variable: 'prodLicense'),
                        script.string(credentialsId: "${state.cfg.build.credentialsId[2]}", variable: 'testLicense')
                ]) {
                    String flavour = 'qc'
                    def flavorLicense = flavour == "prod" ? script.prodLicense : script.testLicense
                    script.echo "[BUILD] Cannot run build ios on windows agent. Changing to a MAC agent for current step"
                    script.timeout(time: 30, unit: 'MINUTES'){
                        script.node((state.platform?.iosBuildAgent ?: 'mac002.bbh.com') as String){
                            script.checkout script.scm
                            FlutterUtils.initializeFlutter(state.cfg, script)
                            script.sh("flutter build ipa --flavor ${flavour} -t lib/main_${flavour}.dart --export-options-plist=ios/ExportOptions.plist --verbose --obfuscate --split-debug-info=build/ios/release/debug-info --no-tree-shake-icons --dart-define=CERTIFICATE_PASSWORD=${script.password} --dart-define=MISNAP_LICENSE=${flavorLicense}")
                            script.sh "mkdir build/ios/release/${flavour} && cp -f build/ios/ipa/BBH.ipa build/ios/release/${flavour}/BBH.ipa"
                            script.stash name: 'ios_artifact', includes: 'build/ios/release/qc/BBH.ipa'
                            script.cleanWs()
                        }
                    }
                    FlutterUtils.initializeFlutter(state.cfg, script)
                    script.sh("flutter build apk --flavor ${flavour} -t lib/main_${flavour}.dart --obfuscate --split-debug-info=build/app/outputs/flutter-apk/debug-info --no-tree-shake-icons --dart-define=CERTIFICATE_PASSWORD=${script.password} --dart-define=MISNAP_LICENSE=${flavorLicense}")
                }
                break
            case ['npm', 'node']:
                nodeBuild()
                break
            case 'pip':
                runCustomCommands((state.cfg.build?.pip?.commands ?: state.cfg.build?.commands) ?: [], state.cfg.sourceDir ?: '.', 'Python (pip)')
                break
            case 'ios':
                runCustomCommands((state.cfg.build?.ios?.commands ?: state.cfg.build?.commands) ?: [], state.cfg.sourceDir ?: '.', 'iOS')
                break
            case 'liquibase':
                runLiquibaseBuild()
                break
            default:
                script.error "[BUILD] Unknown build tool: ${tool}"
        }
    }

    void unitTests() {
        def tool = setupBuildTool()
        script.echo "[TEST] Tool: ${tool}"
        long startMs = System.currentTimeMillis()
        if (BuildUtils.isDotnetTool(tool as String)) {
            if (state.cfg.tests?.unitTests) {
                try {
                    new DotnetBuildRunner(script, os, state.cfg).test(tool as String)
                } catch (Exception e) {
                    if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                    policy.warn('Unit tests', "Unit tests failed (${e.message ?: e.getClass().getSimpleName()}), coverage is checked on the reports that were produced.")
                }
            } else {
                script.echo "[TEST] Skipping the step because it is not configured"
            }
            if (hasLiquibaseStep(state.cfg.tests?.unitTests?.liquibase)) {
                script.echo "[TEST] Running additional Liquibase validation/test step for multi-module project"
                runLiquibaseTests()
            }
            if (isCypressEnabled()) {
                runCypressTests()
            }
            collectDotnetUnitTestCounts()
            state.recordUnitTests([durationMs: System.currentTimeMillis() - startMs])
            return
        }
        switch (tool) {
            case ['gradle', 'android', 'maven']:
                String runnerTool = tool == 'android' ? 'gradle' : (tool as String)
                if (state.cfg.tests.unitTests) {
                    BuildRunner runner = new BuildRunner(script, state.cfg.tests.unitTests as Map, runnerTool)
                    if (!runner.ifExist()) {
                        script.error "[TEST] Build tool is NOT installed on an agent"
                    }
                    try {
                        runner.run()
                    } catch (Exception e) {
                        if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                        policy.warn('Unit tests', "Unit tests failed (${e.message ?: e.getClass().getSimpleName()}), coverage is checked on the reports that were produced.")
                    }
                    collectJunitCountsIfPresent(runnerTool)
                } else {
                    script.echo "[TEST] Skipping the step because it is not configured"
                }
                if (hasLiquibaseStep(state.cfg.tests?.unitTests?.liquibase)) {
                    script.echo "[TEST] Running additional Liquibase validation/test step for multi-module project"
                    runLiquibaseTests()
                }
                if (isCypressEnabled()) {
                    runCypressTests()
                }
                break

            case ['npm', 'node']:
                runNodeTests()
                if (isCypressEnabled()) {
                    runCypressTests()
                }
                break

            case 'flutter':
                FlutterUtils.unitTestsFlutter(state.cfg, script)
                if (script.fileExists('test_report.json')) {
                    Map counts = reportParser.parseFlutterTestReport()
                    state.recordUnitTests([total: (counts.passed as int) + (counts.failed as int) + (counts.skipped as int), failed: counts.failed, skipped: counts.skipped])
                }
                if (isCypressEnabled()) {
                    runCypressTests()
                }
                break

            case 'pip':
                runPipTests()
                collectJunitCountsIfPresent('pip')
                if (isCypressEnabled()) {
                    runCypressTests()
                }
                break

            case 'ios':
                runCustomCommands((state.cfg.tests?.unitTests?.commands ?: state.cfg.tests?.unitTests?.tasks) ?: [], state.cfg.tests?.unitTests?.dir ?: (state.cfg.sourceDir ?: '.'), 'iOS')
                break

            case 'liquibase':
                runLiquibaseTests()
                break

            default:
                script.error "[TEST] Unknown build tool: ${tool}"
        }
        state.recordUnitTests([durationMs: System.currentTimeMillis() - startMs])
    }

    boolean isCypressEnabled() {
        Map unitTests = (state.cfg.tests?.unitTests ?: [:]) as Map
        if (unitTests.cypress?.enabled != null) return BuildUtils.booleanValue(unitTests.cypress.enabled, false)
        if (state.cfg.build?.node?.cypress?.enabled != null) return BuildUtils.booleanValue(state.cfg.build.node.cypress.enabled, false)
        if (state.cfg.cypress?.enabled != null) return BuildUtils.booleanValue(state.cfg.cypress.enabled, false)
        return script.fileExists('cypress.config.js') || script.fileExists('cypress.config.ts') || script.fileExists('cypress.json')
    }

    void runCypressTests() {
        Map unitTests = (state.cfg.tests?.unitTests ?: [:]) as Map
        Map cypressCfg = (unitTests.cypress ?: (state.cfg.build?.node?.cypress ?: (state.cfg.cypress ?: [:]))) as Map
        String dir = (cypressCfg.dir ?: (state.cfg.build?.node?.dir ?: '.')) as String
        String defaultCmd = 'npx cypress run'
        String command = (cypressCfg.command ?: cypressCfg.tasks ?: defaultCmd) as String
        String label = (cypressCfg.label ?: "Cypress: ${command}") as String
        script.echo "[TEST] Cypress: running ${command} in ${dir ?: 'the workspace'}"
        try {
            Map nodeCfg = (state.cfg.build?.node ?: [:]) as Map
            new NodeBuildRunner(script, os).withNode(nodeCfg) {
                if (dir && dir != '.') {
                    script.dir(dir) {
                        if (os.isWindows()) {
                            script.bat(label: label, script: command)
                        } else {
                            BuildUtils.execSh(script, command, label, false)
                        }
                    }
                } else {
                    if (os.isWindows()) {
                        script.bat(label: label, script: command)
                    } else {
                        BuildUtils.execSh(script, command, label, false)
                    }
                }
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            policy.warn('Unit tests', "Cypress tests failed (${e.message ?: e.getClass().getSimpleName()}), coverage is checked on the reports that were produced.")
        }
        String reportsDir = (cypressCfg.reportsDir ?: 'cypress/results') as String
        recordJunitResult(script.junit(allowEmptyResults: true, testResults: "${reportsDir}/*.xml, **/cypress/results/*.xml"))
    }

    void runNodeTests() {
        Map unitTests = (state.cfg.tests?.unitTests ?: [:]) as Map
        List commands = BuildUtils.normalizeTokens(unitTests.commands ?: unitTests.tasks ?: ['npm test'])
        String dir = (unitTests.dir ?: (state.cfg.sourceDir ?: '.')) as String
        Map nodeCfg = (state.cfg.build?.node ?: [:]) as Map
        script.echo "[TEST] Node.js: running ${commands.join(' && ')} in ${dir ?: 'the workspace'}"
        try {
            new NodeBuildRunner(script, os).withNode(nodeCfg) {
                Closure runCommands = {
                    for (String cmd : commands) {
                        if (os.isWindows()) {
                            script.bat(label: "Node.js: ${cmd}", script: cmd)
                        } else {
                            BuildUtils.execSh(script, cmd, "Node.js: ${cmd}", false)
                        }
                    }
                }
                if (dir && dir != '.') {
                    script.dir(dir) { runCommands() }
                } else {
                    runCommands()
                }
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            policy.warn('Unit tests', "Node.js unit tests failed (${e.message ?: e.getClass().getSimpleName()}), coverage is checked on the reports that were produced.")
        }
        recordJunitResult(script.junit(allowEmptyResults: true, testResults: (unitTests.unitTestResult ?: '**/test-results/*.xml, **/junit.xml') as String))
    }

    private void runPipTests() {
        Map unitTests = (state.cfg.tests?.unitTests ?: [:]) as Map
        List commands = BuildUtils.normalizeTokens(unitTests.commands ?: unitTests.tasks ?: ['pytest --junitxml=junit.xml'])
        String dir = (unitTests.dir ?: (state.cfg.sourceDir ?: '.')) as String
        runCustomCommands(commands, dir, 'Python (pip)', true)
    }

    private void runCustomCommands(def rawCommands, def dirVal, String labelPrefix, boolean warnOnFailure = false) {
        List commands = BuildUtils.normalizeTokens(rawCommands ?: [])
        String dir = (dirVal ?: '.') as String
        if (!commands) {
            script.echo "[BUILD] ${labelPrefix}: no custom commands configured, skipping"
            return
        }
        Closure execAll = {
            for (String cmd : commands) {
                if (os.isWindows()) {
                    script.bat(label: "${labelPrefix}: ${cmd}", script: cmd)
                } else {
                    BuildUtils.execSh(script, cmd, "${labelPrefix}: ${cmd}", false)
                }
            }
        }
        try {
            if (dir && dir != '.') {
                script.dir(dir) { execAll() }
            } else {
                execAll()
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            if (!warnOnFailure) throw e
            policy.warn('Unit tests', "${labelPrefix} step failed (${e.message ?: e.getClass().getSimpleName()}), coverage is checked on the reports that were produced.")
        }
    }

    private void recordJunitResult(def result) {
        if (!result) return
        int total = (result.totalCount ?: 0) as int
        int failed = (result.failCount ?: 0) as int
        int skipped = (result.skipCount ?: 0) as int
        state.recordUnitTests([total: total, failed: failed, skipped: skipped])
    }

    private boolean hasMatchingFiles(String pattern) {
        for (String glob : (pattern ?: '').split(',')) {
            String g = glob.trim()
            if (!g) continue
            try {
                def found = script.findFiles(glob: g)
                if (found && found.length > 0) return true
            } catch (Exception ignored) {}
        }
        return false
    }

    private void collectJunitCountsIfPresent(String tool) {
        String custom = (state.cfg.tests?.unitTests?.unitTestResult ?: '').toString().trim()
        String pattern = custom ?: (tool == 'maven' ? '**/target/surefire-reports/*.xml' : (tool == 'pip' ? '**/junit*.xml, **/pytest*.xml' : '**/build/test-results/test/*.xml'))
        if (hasMatchingFiles(pattern)) {
            recordJunitResult(script.junit(allowEmptyResults: true, testResults: pattern))
        }
    }

    private void collectDotnetUnitTestCounts() {
        String pattern = ((state.cfg.tests?.unitTests?.unitTestResult ?: '') as String).trim()
        List<String> trxFiles = []
        if (!pattern || pattern.toLowerCase().contains('.trx')) {
            String trxGlob = pattern ?: '**/TestResults/*.trx'
            try {
                for (def f : script.findFiles(glob: trxGlob)) trxFiles << (f.path as String)
            } catch (Exception ignored) {}
            if (!trxFiles && !pattern) {
                try {
                    for (def f : script.findFiles(glob: '**/*.trx')) trxFiles << (f.path as String)
                } catch (Exception ignored) {}
            }
        }
        if (trxFiles) {
            Map counts = reportParser.parseTrxReports(trxFiles)
            int total = (counts.passed as int) + (counts.failed as int) + (counts.skipped as int)
            script.echo "[TEST] .NET TRX report (${trxFiles.size()} file(s)): passed=${counts.passed} failed=${counts.failed} skipped=${counts.skipped}"
            state.recordUnitTests([total: total, failed: counts.failed, skipped: counts.skipped])
            return
        }
        String junitGlob = pattern && !pattern.toLowerCase().contains('.trx') ? pattern : '**/TestResults/*.xml'
        if (hasMatchingFiles(junitGlob)) {
            recordJunitResult(script.junit(allowEmptyResults: true, testResults: junitGlob))
        }
    }

    void checkCoverage() {
        int minLine = (state.coverage.minRequired ?: 60) as int
        String tool = detectTool()
        script.echo "[COVERAGE] Build tool: ${tool}  Required by the library policy: ${minLine}%"
        Map found = os.isWindows() ? legacyCoverage(tool) : scanCoverage(tool)
        List reports = (found.reports ?: []) as List
        String text = CoverageReports.logText((found.log ?: []) as List, reports)
        if (text) script.echo text
        String classes = CoverageReports.classText(reports, (found.classes ?: []) as List)
        if (classes) script.echo classes
        if (found.missing) {
            if (tool == 'liquibase') {
                script.echo "[COVERAGE] Liquibase: skipping coverage requirement (${found.missing})"
                return
            }
            policy.missingCoverage(found.missing as String, reports)
            return
        }
        if (tool == 'liquibase' && !CoverageReports.summary(reports).counted) {
            script.echo "[COVERAGE] Liquibase: no covered lines found, skipping coverage requirement"
            return
        }
        applyCoverage(tool, reports, minLine)
    }

    void applyCoverage(String tool, List reports, int minLine) {
        Map own = CoverageReports.summary(reports)
        if (!own.counted) {
            policy.missingCoverage(CoverageReports.noLines(reports), reports)
            return
        }
        String covTool = CoverageReports.kindsOf(reports)
        state.recordCoverage([enabled: true, line: own.line, covered: own.covered, missed: own.missed, total: own.total,
                              counted: own.counted, reports: reports, tool: covTool, buildTool: tool, minRequired: minLine])
        Map run = CoverageReports.runSummary(state.projectsCoverage)
        state.coverage.line = run.line
        state.coverage.covered = run.covered
        state.coverage.missed = run.missed
        state.coverage.total = run.total
        state.coverage.counted = run.counted
        state.coverage.enabled = true
        state.coverage.tool = covTool
        state.coverage.buildTool = tool

        String marker = (own.line as double) >= (minLine as double) ? '[PASS]' : '[FAIL]'
        String mean = (own.counted as int) > 1 ? "mean of ${own.counted} reports, " : ''
        script.echo "----------------------------------------------------"
        script.echo "${marker} LINE COVERAGE ${state.currentProjectName}: ${own.line}%  (${mean}min required: ${minLine}%)"
        script.echo "covered=${own.covered}  missed=${own.missed}  total=${own.total}"
        script.echo "----------------------------------------------------"
        policy.checkCoverage()
    }

    private Map scanCoverage(String tool) {
        List<Map> steps = CoverageReports.steps(state.currentProjectName, state.projectsAllCfg, tool, CoverageReports.paths(state.cfg.coverage?.reportPath))
        Map found = readReports(steps)
        if (found.noPython || found.error) {
            script.echo(found.noPython ? '[COVERAGE] python3 is not on the agent: the reports are looked for as before, and a JaCoCo report needs python3 on a Linux or macOS agent'
                    : "[COVERAGE] The coverage scan failed (${found.error}); reading the reports as before".toString())
            return legacyCoverage(tool)
        }
        List log = steps.subList(0, (found.step as int) + 1).collect { it.line }
        if (!found.reports) return [reports: [], log: log, missing: CoverageReports.noReport(steps[steps.size() - 1].search as Map, state.settingsPlace())]
        return found + [log: log]
    }

    private Map readReports(List<Map> steps) {
        try {
            String out = script.sh(label: 'Coverage: find and read the reports', returnStdout: true, encoding: 'UTF-8', script: CoverageReports.command(steps))
            return CoverageReports.parse(out, steps)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return [reports: [], classes: [], error: (e.message ?: e.getClass().getSimpleName()) as String, noPython: false]
        }
    }

    private Map legacyCoverage(String tool) {
        List<String> listed = CoverageReports.paths(state.cfg.coverage?.reportPath)
        List<String> candidates = UnitTestReportParser.jacocoCandidates(tool)
        List paths = []
        for (String p : listed) {
            if (p.contains('*') || p.contains('?')) {
                for (def f : script.findFiles(glob: p)) paths << (f.path as String)
            } else if (script.fileExists(p)) {
                paths << p
            }
        }
        if (!paths && tool == 'flutter') {
            if (!script.fileExists('total_lcov.info')) return [reports: [], missing: 'total_lcov.info not found']
            paths << 'total_lcov.info'
        }
        if (!paths && BuildUtils.isDotnetTool(tool)) {
            script.echo '[COVERAGE] Looking for existing Cobertura/OpenCover reports.'
            paths = reportParser.findCoverageReports(tool)
        } else {
            for (String p : candidates) {
                if (p.contains('*') || p.contains('?')) {
                    if (!paths) {
                        for (def f : script.findFiles(glob: p)) paths << (f.path as String)
                    }
                } else if (!paths && script.fileExists(p)) {
                    paths << p
                }
            }
            if (!paths) {
                script.echo '[COVERAGE] Looking for existing JaCoCo reports.'
                paths = reportParser.findCoverageReports(tool)
            }
        }
        if (!paths) {
            return [reports: [], missing: "${BuildUtils.isDotnetTool(tool) ? 'Coverage' : 'JaCoCo'} report not found (tried ${listed ? listed[0] : candidates[0]}), set coverage.reportPath for the service in ${state.settingsPlace()} to point at it".toString()]
        }
        List reports = []
        List classes = []
        for (def path : CoverageReports.distinct(paths)) {
            if (CoverageReports.isLcov(path as String)) {
                Map lcov = reportParser.readLcov(path as String)
                reports << CoverageReports.row(tool == 'flutter' ? 'flutter' : 'lcov', path as String, lcov.covered as int, lcov.total as int, '')
            } else {
                Map cov = reportParser.readXmlCoverage(path as String)
                String kind = (cov.kind ?: 'jacoco') as String
                reports << CoverageReports.row(kind, path as String, cov.covered as int, cov.total as int, '')
                if (kind == 'jacoco') {
                    for (def entry : reportParser.readJaCoCoPerClass(path as String)) {
                        classes << "Package: ${entry.packageName}, Class: ${entry.className}, Coverage: ${entry.pct}, Missed: ${entry.missed}, Covered: ${entry.covered}".toString()
                    }
                }
            }
        }
        return [reports: reports, classes: classes]
    }

    void reportArtifactBuild() {
        if (BuildUtils.databaseBlock(state.cfg)) {
            reportDatabasePackage()
            return
        }
        def tool     = detectTool()
        def platform = (state.cfg.flutter?.platform ?: 'apk') as String
        def artifact = ''

        if (os.isWindows()) {
            def pattern = tool == 'gradle'               ? 'build\\libs\\*.jar,build\\libs\\*.war'
                        : tool == 'maven'                ? 'target\\*.jar,target\\*.war'
                        : tool == 'flutter'              ? UnitTestReportParser.flutterArtifactGlob(platform, true)
                        : BuildUtils.isDotnetTool(tool as String) ? reportParser.dotnetArtifactGlob(true)
                        : ''
            if (pattern) artifact = script.powershell(returnStdout: true,
                script: "Get-ChildItem ${pattern} -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName").trim()
        } else {
            def pattern = tool == 'gradle'               ? state.cfg.build?.buildPath
                        : tool == 'maven'                ? 'target/*.jar target/*.war'
                        : tool == 'flutter'              ? UnitTestReportParser.flutterArtifactGlob(platform, false)
                        : BuildUtils.isDotnetTool(tool as String) ? reportParser.dotnetArtifactGlob(false)
                        : ''
            if (pattern) artifact = script.sh(script: "ls ${pattern} 2>/dev/null | head -n 1 || true", returnStdout: true).trim()
        }

        if (!artifact) { script.echo "[REPORT] No artifact found for ${tool}/${platform}"; return }
        script.reportBuild(
            applicationName:       state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
            applicationVersion:    script.env.BUILD_NUMBER,
            applicationComponent:  'core',
            artifactFileName:      artifact,
            artifactFileSizeLimit: 0
        )
    }

    private void reportDatabasePackage() {
        Map pkg = ((state.projectsDatabase[state.currentProjectName] ?: [:]) as Map).package as Map
        if (!pkg?.path) {
            script.echo "[BUILD] ${state.currentProjectName}: no SQL and PL/SQL package in this run - the build is not reported"
            return
        }
        script.reportBuild(
            applicationName:       state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
            applicationVersion:    script.env.BUILD_NUMBER,
            applicationComponent:  'core',
            artifactFileName:      pkg.path,
            artifactFileSizeLimit: 0
        )
    }

    void reportUnitTests() {
        if (BuildUtils.databaseBlock(state.cfg)) {
            reportDatabaseTests()
            return
        }
        def tool = detectTool()

        if (tool == 'flutter') {
            def counts = script.fileExists('test_report.json') ? reportParser.parseFlutterTestReport() : [passed: 0, failed: 0, skipped: 0]
            script.echo "[TEST] Flutter report: passed=${counts.passed} failed=${counts.failed} skipped=${counts.skipped}"
            state.recordUnitTests([total: (counts.passed as int) + (counts.failed as int) + (counts.skipped as int), failed: counts.failed, skipped: counts.skipped])
            script.reportUnitTest(
                applicationName:      state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
                applicationVersion:   script.env.BUILD_NUMBER,
                applicationComponent: 'core',
                testsPassed:          counts.passed,
                testsFailed:          counts.failed,
                testsIgnored:         counts.skipped
            )
            return
        } else if (tool == 'maven') {
            reportSurefireTests()
            return
        } else if (tool == 'liquibase') {
            state.recordUnitTests([total: 0, failed: 0, skipped: 0])
            return
        } else if (BuildUtils.isDotnetTool(tool as String)) {
            reportDotnetUnitTests()
            return
        }

        String pattern = (state.cfg.tests?.unitTests?.unitTestResult ?: '**/build/test-results/test/*.xml') as String
        def result  = script.junit allowEmptyResults: true, testResults: pattern
        state.recordUnitTests([total: result.totalCount, failed: result.failCount, skipped: result.skipCount])
        script.reportUnitTest(
            applicationName:      state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
            applicationVersion:   script.env.BUILD_NUMBER,
            applicationComponent: 'core',
            testsPassed:          result.totalCount - result.skipCount - result.failCount,
            testsFailed:          result.failCount,
            testsIgnored:         result.skipCount
        )
    }

    private void reportDatabaseTests() {
        Map tests = (state.projectsUnitTests[state.currentProjectName] ?: [:]) as Map
        if (tests.total == null) {
            script.echo "[TEST] ${state.currentProjectName}: no utPLSQL results in this run - the unit tests are not reported"
            return
        }
        int total = tests.total as int
        int failed = (tests.failed ?: 0) as int
        int skipped = (tests.skipped ?: 0) as int
        script.reportUnitTest(
            applicationName:      state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
            applicationVersion:   script.env.BUILD_NUMBER,
            applicationComponent: 'core',
            testsPassed:          Math.max(0, total - skipped - failed),
            testsFailed:          failed,
            testsIgnored:         skipped
        )
    }

    private void reportDotnetUnitTests() {
        String pattern = ((state.cfg.tests?.unitTests?.unitTestResult ?: '') as String).trim()
        List<String> trxFiles = []
        if (!pattern || pattern.toLowerCase().contains('.trx')) {
            String trxGlob = pattern ?: '**/TestResults/*.trx'
            try {
                for (def f : script.findFiles(glob: trxGlob)) trxFiles << (f.path as String)
            } catch (Exception ignored) {}
            if (!trxFiles && !pattern) {
                try {
                    for (def f : script.findFiles(glob: '**/*.trx')) trxFiles << (f.path as String)
                } catch (Exception ignored) {}
            }
        }
        if (trxFiles) {
            Map counts = reportParser.parseTrxReports(trxFiles)
            int total = (counts.passed as int) + (counts.failed as int) + (counts.skipped as int)
            script.echo "[TEST] .NET TRX report (${trxFiles.size()} file(s)): passed=${counts.passed} failed=${counts.failed} skipped=${counts.skipped}"
            state.recordUnitTests([total: total, failed: counts.failed, skipped: counts.skipped])
            script.reportUnitTest(
                applicationName:      state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
                applicationVersion:   script.env.BUILD_NUMBER,
                applicationComponent: 'core',
                testsPassed:          counts.passed,
                testsFailed:          counts.failed,
                testsIgnored:         counts.skipped
            )
            return
        }
        String junitGlob = pattern && !pattern.toLowerCase().contains('.trx') ? pattern : '**/TestResults/*.xml'
        def result = script.junit allowEmptyResults: true, testResults: junitGlob
        int total = (result?.totalCount ?: 0) as int
        int failed = (result?.failCount ?: 0) as int
        int skipped = (result?.skipCount ?: 0) as int
        state.recordUnitTests([total: total, failed: failed, skipped: skipped])
        script.reportUnitTest(
            applicationName:      state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
            applicationVersion:   script.env.BUILD_NUMBER,
            applicationComponent: 'core',
            testsPassed:          Math.max(0, total - skipped - failed),
            testsFailed:          failed,
            testsIgnored:         skipped
        )
    }

    void reportSurefireTests() {
        String rootDir = state.cfg.tests.unitTests?.rootDir ?: '.'
        String reportOutDir = state.cfg.tests.unitTests?.reportOutDir ?: 'surefire-reports'

        String safeRootDir = BuildUtils.escapeForSingleQuotes(rootDir)
        String safeReportOutDir = BuildUtils.escapeForSingleQuotes(reportOutDir)

        boolean allowEmptyResults = state.cfg.tests.unitTests?.allowEmptyResults ?: false

        String reportFilesRaw = script.sh(
            script: """
                set -eu +x
                find '${safeRootDir}' -type f -path '*/target/surefire-reports/*.xml' | sort 
             """.stripIndent(),
            returnStdout: true
        ).trim()

        List<Map> reportFiles = []
        if (reportFilesRaw) {
            reportFilesRaw.readLines().each { file ->
                String moduleDir = file.replaceFirst('/target/surefire-reports/.*$', '')
                reportFiles << [module: moduleDir, file: file]
            }
        }

        script.echo "Report files found: ${reportFiles.size()}"
        reportFiles.each { script.echo "Module: ${it.module}, File: ${it.file}" }

        script.sh(
            script: """
                set -eu +x
                mkdir -p '${safeReportOutDir}'
                find '${safeRootDir}' -type f -path '*/target/surefire-reports/*.xml' \\
                    | while IFS= read -r src; do
                        filename=\$(basename "\$src")
                        dest='${safeReportOutDir}/'"\$filename"
                        cp "\$src" "\$dest"
                    done    
            """.stripIndent()
        )

        def result = script.junit testResults: "${safeReportOutDir}/*.xml", allowEmptyResults: "${allowEmptyResults}"
        state.recordUnitTests([total: result.totalCount, failed: result.failCount, skipped: result.skipCount])
        script.reportSurefireTest(
                applicationName: 'data-service-api',
                applicationVersion: script.env.BUILD_NUMBER,
                applicationComponent: 'core',
                surefireReportPath: "${safeReportOutDir}/*.xml"
        )
    }

    void runTestJobs(String stageName, String label, Map testCfg) {
        remoteRunner.runTestJobs(stageName, label, testCfg)
    }

    Map runSingleTestJob(String label, Map jobCfg, int idx, int defaultPollSec) {
        return remoteRunner.runSingleTestJob(label, jobCfg, idx, defaultPollSec)
    }

    String detectTool() {
        return toolchain.detectTool()
    }

    String setupBuildTool() {
        return toolchain.setupBuildTool()
    }

    @NonCPS
    String autoSetupJavaHome(String buildTool, Map extraEnv) {
        return toolchain.autoSetupJavaHome(buildTool, extraEnv)
    }

    boolean hasLiquibaseStep(def rawCfg) {
        return LiquibaseBuildRunner.hasLiquibaseStep(rawCfg)
    }

    void runLiquibaseBuild() {
        liquibaseRunner.runLiquibaseBuild()
    }

    void runLiquibaseTests() {
        liquibaseRunner.runLiquibaseTests()
    }

    def runLiquibaseCommand(Map cfg, List defaultCommands, String defaultLabelPrefix) {
        return liquibaseRunner.runLiquibaseCommand(cfg, defaultCommands, defaultLabelPrefix)
    }

    @NonCPS
    static List<Map> normalizeTestJobs(Map testCfg) {
        return RemoteTestJobRunner.normalizeTestJobs(testCfg)
    }

    @NonCPS
    static String deriveJobName(String ref, int idx) {
        return RemoteTestJobRunner.deriveJobName(ref, idx)
    }

    @NonCPS
    static String decodeSegment(String value) {
        return RemoteTestJobRunner.decodeSegment(value)
    }

    @NonCPS
    static boolean isHttpUrl(String value) {
        return RemoteTestJobRunner.isHttpUrl(value)
    }

    @NonCPS
    static int resolveMaxParallel(Map testCfg, def globalDefault, int total) {
        return RemoteTestJobRunner.resolveMaxParallel(testCfg, globalDefault, total)
    }

    @NonCPS
    static String branchName(int idx, String name) {
        return RemoteTestJobRunner.branchName(idx, name)
    }

    @NonCPS
    static Map remoteTriggerArgs(Map jobCfg, String jobPath, String params, int pollSec, String remJenkins, String remJenkinsUrl) {
        return RemoteTestJobRunner.remoteTriggerArgs(jobCfg, jobPath, params, pollSec, remJenkins, remJenkinsUrl)
    }

    @NonCPS
    static def remoteAuth(Map jobCfg) {
        return RemoteTestJobRunner.remoteAuth(jobCfg)
    }

    @NonCPS
    static Map summarizeTestJobs(List results) {
        return RemoteTestJobRunner.summarizeTestJobs(results)
    }

    @NonCPS
    static String failedJobsMessage(List failed, int limit) {
        return RemoteTestJobRunner.failedJobsMessage(failed, limit)
    }

    @NonCPS
    static String interruptionKind(Throwable ex) {
        return RemoteTestJobRunner.interruptionKind(ex)
    }
}
