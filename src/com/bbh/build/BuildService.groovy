package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.BuildUtils
import com.bbh.utils.FlutterUtils
import com.cloudbees.groovy.cps.NonCPS

class BuildService implements Serializable {
    private final def         script
    private final PipelineState state
    private final OsHelper    os
    private final PolicyEngine policy

    BuildService(def script, PipelineState state, OsHelper os, PolicyEngine policy) {
        this.script = script
        this.state  = state
        this.os     = os
        this.policy = policy
    }

    void buildArtifact() {
        def tool = setupBuildTool()
        script.echo "[BUILD] Tool: ${tool} OS: ${os.getType()}"
        switch (tool) {
            case ['gradle', 'maven']:
                BuildRunner runner = new BuildRunner(script, state.cfg.build as Map, tool)
                if (!runner.ifExist()) {
                    script.error "[BUILD] Build tool is NOT installed on an agent"
                }
                runner.run()
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
            default:
                script.error "[BUILD] Unknown build tool: ${tool}"
        }
    }

    void unitTests() {
        def tool = setupBuildTool()
        script.echo "[TEST] Tool: ${tool}"
        switch (tool) {
            case ['gradle', 'maven']:
                if (state.cfg.tests.unitTests) {
                    BuildRunner runner = new BuildRunner(script, state.cfg.tests.unitTests as Map, tool)
                    if (!runner.ifExist()) {
                        script.error "[TEST] Build tool is NOT installed on an agent"
                    }
                    try {
                        runner.run()
                    } catch (Exception e) {
                        if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                        policy.warn('Unit tests', "Unit tests failed (${e.message ?: e.getClass().getSimpleName()}), coverage is checked on the reports that were produced.")
                    }
                } else {
                    script.echo "[TEST] Skipping the step because it is not configured"
                }
                break

            case 'flutter':
                FlutterUtils.unitTestsFlutter(state.cfg, script)
                break

            default:
                script.error "[TEST] Unknown build tool: ${tool}"
        }
    }

    void checkCoverage() {
        int minLine = (state.coverage.minRequired ?: 60) as int
        def tool = detectTool()
        script.echo "[COVERAGE] Build tool: ${tool}  Required by the library policy: ${minLine}%"
        String covTool = 'none'
        int covered = 0
        int missed = 0

        if (tool == 'flutter') {
            def lcovPath = 'total_lcov.info'
            if (!script.fileExists(lcovPath)) {
                policy.missingCoverage('total_lcov.info not found')
                return
            }
            Map cov = readLcov(lcovPath)
            covTool = 'lcov'
            covered = (cov.covered ?: 0) as int
            missed = (cov.missed ?: 0) as int
        } else {
            List xmlPaths = []
            def xmlPath = resolveJacocoPath(tool)
            if (script.fileExists(xmlPath)) {
                xmlPaths.add(xmlPath)
            } else {
                script.echo "[COVERAGE] Looking for existing JaCoCo reports."
                xmlPaths = findCoverageReports()
            }

            if (!xmlPaths) {
                policy.missingCoverage("JaCoCo report not found (tried ${xmlPath}), add coverage.reportPath in config.yaml to point at it")
                return
            }

            List classCov = []
            for (def path : xmlPaths) {
                Map cov = readJacoco(path as String)
                classCov.addAll(readJaCoCoPerClass(path as String))
                covered += (cov.covered ?: 0) as int
                missed += (cov.missed ?: 0) as int
            }
            covTool = 'jacoco'

            script.echo "Class coverage: "
            for (def entry : classCov) {
                script.echo "Package: ${entry.packageName}, Class: ${entry.className}, Coverage: ${entry.pct}, Missed: ${entry.missed}, Covered: ${entry.covered}"
            }
            script.echo "========================="
        }

        int total = covered + missed
        double pct = total ? (((covered * 1000.0 / total) + 0.5) as long) / 10.0 : 0.0
        state.recordCoverage([enabled: true, line: pct, covered: covered, missed: missed, total: total,
                              tool: covTool, buildTool: tool, minRequired: minLine])
        state.coverage.covered = ((state.coverage.covered ?: 0) as int) + covered
        state.coverage.missed = ((state.coverage.missed ?: 0) as int) + missed
        state.coverage.total = ((state.coverage.total ?: 0) as int) + total
        int allTotal = state.coverage.total as int
        state.coverage.line = allTotal ? ((((state.coverage.covered as int) * 1000.0 / allTotal) + 0.5) as long) / 10.0 : 0.0
        state.coverage.enabled = true
        state.coverage.tool = covTool
        state.coverage.buildTool = tool

        String marker = pct >= (minLine as double) ? '[PASS]' : '[FAIL]'
        script.echo "----------------------------------------------------"
        script.echo "${marker} LINE COVERAGE ${state.currentProjectName}: ${pct}%  (min required: ${minLine}%)"
        script.echo "covered=${covered}  missed=${missed}  total=${total}"
        script.echo "----------------------------------------------------"
        policy.checkCoverage()
    }

    void reportArtifactBuild() {
        def tool     = detectTool()
        def platform = (state.cfg.flutter?.platform ?: 'apk') as String
        def artifact = ''

        if (os.isWindows()) {
            def pattern = tool == 'gradle'  ? 'build\\libs\\*.jar,build\\libs\\*.war'
                        : tool == 'maven'   ? 'target\\*.jar,target\\*.war'
                        : tool == 'flutter' ? flutterArtifactGlob(platform, true)
                        : ''
            if (pattern) artifact = script.powershell(returnStdout: true,
                script: "Get-ChildItem ${pattern} -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName").trim()
        } else {
            def pattern = tool == 'gradle'  ? state.cfg.build?.buildPath
                        : tool == 'maven'   ? 'target/*.jar target/*.war'
                        : tool == 'flutter' ? flutterArtifactGlob(platform, false)
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

    void reportUnitTests() {
        def tool = detectTool()

        if (tool == 'flutter') {
            def counts = script.fileExists('test_report.json') ? parseFlutterTestReport() : [passed: 0, failed: 0, skipped: 0]
            script.echo "[TEST] Flutter report: passed=${counts.passed} failed=${counts.failed} skipped=${counts.skipped}"
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
        }

        String pattern = (state.cfg.tests?.unitTests?.unitTestResult ?: '**/build/test-results/test/*.xml') as String
        def result  = script.junit allowEmptyResults: true, testResults: pattern
        script.reportUnitTest(
            applicationName:      state.cfg.tools?.sonar?.projectName ?: script.env.JOB_NAME,
            applicationVersion:   script.env.BUILD_NUMBER,
            applicationComponent: 'core',
            testsPassed:          result.totalCount - result.skipCount - result.failCount,
            testsFailed:          result.failCount,
            testsIgnored:         result.skipCount
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

        script.junit testResults: "${safeReportOutDir}/*.xml", allowEmptyResults: "${allowEmptyResults}"
        script.reportSurefireTest(
                applicationName: 'data-service-api',
                applicationVersion: script.env.BUILD_NUMBER,
                applicationComponent: 'core',
                surefireReportPath: "${safeReportOutDir}/*.xml"
        )
    }

    void runTestJobs(String stageName, String label, Map testCfg) {
        def required = testCfg?.required == null ? true : testCfg?.required
        if (!required) {
            script.echo "This test is not required or implemented in other step"
            state.recordTestJobs(stageName, [[index: 1, name: 'Test not required', type: 'unknown', status: 'ALREADY IMPLEMENTED', url: '', durationMs: 0L, message: '']])
            script.echo "remoteTestResults: ${state.remoteTestResults[stageName]}"
            return
        }

        List<Map> jobs = normalizeTestJobs(testCfg)
        if (!jobs) {
            state.recordTestJobs(stageName, [])
            policy.warn(stageName, "${label}: no test jobs configured, at least one job is required in config.yaml.")
            return
        }

        int defaultPollSec = (testCfg.pollIntervalSec ?: 15) as int
        int maxParallel = resolveMaxParallel(testCfg, state.cfg?.tests?.maxParallel, jobs.size())
        List results = []
        for (int i = 0; i < jobs.size(); i++) results << null

        script.echo "[${label}] Project '${state.currentProjectName}': ${jobs.size()} job(s), max ${maxParallel} running in parallel"
        def tasks = [:]
        if (jobs.size() <= maxParallel) {
            for (int i = 0; i < jobs.size(); i++) {
                int idx = i
                tasks[branchName(idx, jobs[idx].name as String)] = {
                    results[idx] = runSingleTestJob(label, jobs[idx], idx, defaultPollSec)
                }
            }
        } else {
            def cursor = [next: 0]
            for (int w = 0; w < maxParallel; w++) {
                tasks["${label} worker ${w + 1}".toString()] = {
                    while (true) {
                        int idx = cursor.next as int
                        if (idx >= jobs.size()) break
                        cursor.next = idx + 1
                        results[idx] = runSingleTestJob(label, jobs[idx], idx, defaultPollSec)
                    }
                }
            }
        }
        tasks.failFast = false
        script.parallel tasks

        List resultList = results.findAll { it != null }
        state.recordTestJobs(stageName, resultList)
        Map summary = summarizeTestJobs(resultList)
        script.echo "[${label}] Project '${state.currentProjectName}' summary: total=${summary.total} passed=${summary.passed} failed=${summary.failed} notConfigured=${summary.notConfigured}"

        List failed = resultList.findAll { it.status != 'SUCCESS' }
        if (failed) {
            String details = failedJobsMessage(failed, 10)
            String summary2 = failed.size() == 1 ? details : "${failed.size()} of ${resultList.size()} job(s) failed: ${details}"
            policy.warn(stageName, "${label} failed - ${summary2}.")
        }
    }

    Map runSingleTestJob(String label, Map jobCfg, int idx, int defaultPollSec) {
        String jobId         = jobCfg.name as String
        String type          = (jobCfg.type ?: 'local') as String
        String jobPath       = (jobCfg.job ?: '') as String
        String params        = (jobCfg.parameters?.toString()?.trim() ?: '') as String
        int tmMin            = (jobCfg.timeoutMin ?: 30) as int
        int pollSec          = (jobCfg.pollIntervalSec ?: defaultPollSec) as int
        String remJenkins    = (jobCfg.remoteJenkins?.toString()?.trim() ?: '') as String
        String remJenkinsUrl = (jobCfg.remoteJenkinsUrl?.toString()?.trim() ?: '') as String

        long startMs   = System.currentTimeMillis()
        String status  = 'UNKNOWN'
        String url     = ''
        String message = ''
        try {
            if (!jobPath) {
                status  = 'NOT_CONFIGURED'
                message = 'job path / url not set'
                script.echo "[${label}/${jobId}] Skipped - job path not set"
            } else if (type == 'remote') {
                if (!isHttpUrl(jobPath) && !remJenkins && !remJenkinsUrl) {
                    status  = 'NOT_CONFIGURED'
                    message = 'remoteJenkins, remoteJenkinsUrl or a full job URL is required for remote jobs'
                    script.echo "[${label}/${jobId}] Skipped - ${message}"
                } else {
                    script.echo "[${label}/${jobId}] Triggering remote job '${jobPath}'${remJenkins ? " on '" + remJenkins + "'" : ''}"
                    Map args = remoteTriggerArgs(jobCfg, jobPath, params, pollSec, remJenkins, remJenkinsUrl)
                    def handle
                    script.timeout(time: tmMin, unit: 'MINUTES') {
                        handle = script.triggerRemoteJob(args)
                    }
                    try {
                        status = handle.getBuildResult()?.toString() ?: 'UNKNOWN'
                    } catch (se) {
                    }
                    try {
                        url = handle.getBuildUrl()?.toString() ?: ''
                    } catch (se) {
                    }
                    if (!status || status == 'UNKNOWN') {
                        try {
                            status = handle.getBuildResult() ? 'SUCCESS' : 'FAILURE'
                        } catch (se2) {
                            status = 'UNKNOWN'
                        }
                    }
                }
            } else {
                script.echo "[${label}/${jobId}] Triggering local job '${jobPath}'"
                def paramList = []
                if (params) {
                    params.split('\n').each { line ->
                        def t = line.trim()
                        if (t) {
                            def kv = t.split('=', 2)
                            paramList << script.string(name: kv[0].trim(), value: kv.length > 1 ? kv[1].trim() : '')
                        }
                    }
                }
                def handle
                script.timeout(time: tmMin, unit: 'MINUTES') {
                    handle = script.build(job: jobPath, parameters: paramList, wait: true, propagate: false)
                }
                status = handle?.getResult()?.toString() ?: 'UNKNOWN'
                try {
                    url = handle?.absoluteUrl?.toString() ?: ''
                } catch (se) {
                }
            }
        } catch (ex) {
            String kind = interruptionKind(ex)
            if (kind == 'ABORTED') throw ex
            status  = kind ?: 'ERROR'
            message = (ex.message ?: ex.getClass().getSimpleName()) as String
            script.echo "[${label}/${jobId}] ${status}: ${message}"
        }
        long durationMs = System.currentTimeMillis() - startMs
        script.echo "[${label}/${jobId}] type=${type} result=${status} duration=${BuildUtils.msFmt(durationMs)}"
        return [index: idx + 1, name: jobId, type: type, status: status, url: url, durationMs: durationMs, message: message]
    }

    @NonCPS
    static List<Map> normalizeTestJobs(Map testCfg) {
        List<Map> out = []
        if (!testCfg) return out
        Map defaults = (testCfg.defaults instanceof Map) ? (testCfg.defaults as Map) : [:]
        List raw = []
        if (testCfg.jobs instanceof List) raw.addAll(testCfg.jobs as List)
        if (testCfg.urls instanceof List) raw.addAll(testCfg.urls as List)

        Map nameCounts = [:]
        for (int i = 0; i < raw.size(); i++) {
            def entry = raw[i]
            if (entry == null) continue
            Map job = [:] + defaults
            if (entry instanceof Map) {
                job.putAll(entry as Map)
            } else {
                job.url = entry.toString().trim()
            }
            String ref = ((job.url ?: job.job ?: '') as String).trim()
            job.job  = ref
            job.type = ((job.type ?: (isHttpUrl(ref) ? 'remote' : 'local')) as String).trim()
            String name = ((job.name ?: '') as String).trim()
            if (!name) name = deriveJobName(ref, out.size())
            int seen = ((nameCounts[name] ?: 0) as int) + 1
            nameCounts[name] = seen
            job.name = seen > 1 ? "${name} (#${seen})".toString() : name
            out << job
        }
        return out
    }

    @NonCPS
    static String deriveJobName(String ref, int idx) {
        if (!ref) return "job-${idx + 1}".toString()
        def m = (ref =~ /\/job\/([^\/?#]+)/)
        List parts = []
        while (m.find()) parts << decodeSegment(m.group(1))
        if (parts) return parts.join('/')
        String trimmed = ref.replaceAll('/+$', '')
        return trimmed.contains('/') ? trimmed.substring(trimmed.lastIndexOf('/') + 1) : trimmed
    }

    @NonCPS
    static String decodeSegment(String value) {
        return (value ?: '').replace('%20', ' ').replace('+', ' ')
    }

    @NonCPS
    static boolean isHttpUrl(String value) {
        return (value ?: '') ==~ /(?i)^https?:\/\/\S+$/
    }

    @NonCPS
    static int resolveMaxParallel(Map testCfg, def globalDefault, int total) {
        def raw = testCfg?.maxParallel != null ? testCfg.maxParallel : (globalDefault != null ? globalDefault : 20)
        int n = 20
        try {
            n = raw.toString().trim().toInteger()
        } catch (ignored) {
        }
        if (n < 1) n = 1
        return Math.min(n, Math.max(total, 1))
    }

    @NonCPS
    static String branchName(int idx, String name) {
        String n = "${idx + 1}. ${name}".toString()
        return n.length() > 80 ? n.substring(0, 77) + '...' : n
    }

    @NonCPS
    static Map remoteTriggerArgs(Map jobCfg, String jobPath, String params, int pollSec, String remJenkins, String remJenkinsUrl) {
        Map args = [
                abortTriggeredJob           : BuildUtils.booleanValue(jobCfg.abortTriggeredJob, false),
                job                         : jobPath,
                overrideTrustAllCertificates: BuildUtils.booleanValue(jobCfg.overrideTrustAllCertificates, false),
                pollInterval                : pollSec,
                preventRemoteBuildQueue     : BuildUtils.booleanValue(jobCfg.preventRemoteBuildQueue, false),
                token                       : (jobCfg.token?.toString()?.trim() ?: ''),
                trustAllCertificates        : BuildUtils.booleanValue(jobCfg.trustAllCertificates, false),
                useCrumbCache               : BuildUtils.booleanValue(jobCfg.useCrumbCache, false),
                useJobInfoCache             : BuildUtils.booleanValue(jobCfg.useJobInfoCache, false),
                auth                        : remoteAuth(jobCfg),
                parameters                  : params,
                blockBuildUntilComplete     : true,
                shouldNotFailBuild          : true
        ]
        if (remJenkins) args.remoteJenkinsName = remJenkins
        if (remJenkinsUrl) args.remoteJenkinsUrl = remJenkinsUrl
        return args
    }

    @NonCPS
    static def remoteAuth(Map jobCfg) {
        if (jobCfg.auth) return jobCfg.auth
        if (jobCfg.credentialsId) return ['$class': 'CredentialsAuth', credentials: jobCfg.credentialsId.toString()]
        return [:]
    }

    @NonCPS
    static Map summarizeTestJobs(List results) {
        int passed = 0
        int failed = 0
        int notConfigured = 0
        for (def r : (results ?: [])) {
            String s = r?.status as String
            if (s == 'SUCCESS' || s == 'ALREADY IMPLEMENTED') passed++
            else if (s == 'NOT_CONFIGURED') notConfigured++
            else failed++
        }
        return [total: (results ?: []).size(), passed: passed, failed: failed, notConfigured: notConfigured]
    }

    @NonCPS
    static String failedJobsMessage(List failed, int limit) {
        List msgs = failed.collect { "${it.name}: ${it.status}".toString() }
        if (msgs.size() <= limit) return msgs.join(', ')
        return msgs.subList(0, limit).join(', ') + " ... and ${msgs.size() - limit} more".toString()
    }

    @NonCPS
    static String interruptionKind(Throwable ex) {
        if (ex == null || !ex.getClass().getName().endsWith('FlowInterruptedException')) return ''
        List causes = []
        try {
            causes = ex.getCauses() as List
        } catch (ignored) {
        }
        return causes.any { it?.getClass()?.getSimpleName() == 'ExceededTimeout' } ? 'TIMEOUT' : 'ABORTED'
    }

    String detectTool() {
        def cfgTool = state.cfg.buildTool?.trim()
        if (cfgTool) {
            script.echo "Detected tool from config file is ${cfgTool}"
            return cfgTool
        }
        if (script.fileExists('pubspec.yaml'))  {
            script.echo "Detected tool is Flutter"
            return 'flutter'
        }
        if (script.fileExists('pom.xml')) {
            script.echo "Detected tool is Maven"
            return 'maven'
        }
        if (script.fileExists('build.gradle') || script.fileExists('build.gradle.kts')) {
            script.echo "Detected tool is Gradle"
            return 'gradle'
        }
        return 'gradle'
    }

    String setupBuildTool() {
        String buildTool = detectTool()
        boolean hasInstalled = BuildUtils.booleanValue(script.env.HAS_BUILD_TOOL_INSTALLED, false)
        if (hasInstalled) {
            return buildTool
        }
        boolean toolAutoSetup = BuildUtils.booleanValue(state.cfg.buildToolAutoSetup, false)

        String javaPath = ''
        if (!toolAutoSetup) {
            javaPath = state.cfg?.javaPath
            if (!javaPath) {
                script.error "JAVA_HOME parameter is not specified"
            }
            if (buildTool == "flutter"){
                return buildTool
            }
            script.env.JAVA_HOME = javaPath
            script.env.PATH = "${javaPath}/bin:${script.env.PATH}"
            script.env.HAS_BUILD_TOOL_INSTALLED = 'true'
            return buildTool
        } else {
            script.withEnv(["BUILD_SYSTEM=${buildTool}"]) {
                javaPath = script.sh(returnStdout: true, script: '''#!/usr/bin/env bash
                    set -euo pipefail
                     
                    echo "[INFO] Installing $BUILD_SYSTEM..." >&2
                    
                    JAVA_VERSION=""
                    JAVA_HOME_SELECTED=""
                    
                    if [[ $BUILD_SYSTEM == 'maven' ]]; then
                         # Try maven.compiler.release
                        JAVA_VERSION="$(
                            sed -n \
                                's:.*<maven.compiler.release>[[:space:]]*\\([^<]*\\)[[:space:]]*</maven.compiler.release>.*:\\1:p' \
                                pom.xml |
                            head -n 1
                        )"
         
                        # Try java.version
                        if [ -z "$JAVA_VERSION" ] || echo "$JAVA_VERSION" | grep -q '\\${'; then
                            JAVA_VERSION="$(
                                sed -n \
                                    's:.*<java.version>[[:space:]]*\\([^<]*\\)[[:space:]]*</java.version>.*:\\1:p' \
                                    pom.xml |
                                head -n 1
                            )"
                        fi
         
                        # Try maven.compiler.target
                        if [ -z "$JAVA_VERSION" ] || echo "$JAVA_VERSION" | grep -q '\\${'; then
                            JAVA_VERSION="$(
                                sed -n \
                                    's:.*<maven.compiler.target>[[:space:]]*\\([^<]*\\)[[:space:]]*</maven.compiler.target>.*:\\1:p' \
                                    pom.xml |
                                head -n 1
                            )"
                        fi
         
                        # Try maven.compiler.source
                        if [ -z "$JAVA_VERSION" ] || echo "$JAVA_VERSION" | grep -q '\\${'; then
                            JAVA_VERSION="$(
                                sed -n \
                                    's:.*<maven.compiler.source>[[:space:]]*\\([^<]*\\)[[:space:]]*</maven.compiler.source>.*:\\1:p' \
                                    pom.xml |
                                head -n 1
                            )"
                        fi
                    
                    elif [[ $BUILD_SYSTEM == 'gradle' ]]; then
                        if [ -f "build.gradle.kts" ]; then
                            GRADLE_BUILD_FILE="build.gradle.kts"
                        else
                            GRADLE_BUILD_FILE="build.gradle"
                        fi
         
                        JAVA_VERSION="$(
                            grep -Eo \
                                'JavaLanguageVersion\\.of\\([[:space:]]*[0-9]+' \
                                "$GRADLE_BUILD_FILE" 2>/dev/null |
                            grep -Eo '[0-9]+' |
                            head -n 1 ||
                            true
                        )"
         
                        if [ -z "$JAVA_VERSION" ]; then
                            JAVA_VERSION="$(
                                grep -Eo \
                                    'JavaVersion\\.VERSION_[0-9_]+' \
                                    "$GRADLE_BUILD_FILE" 2>/dev/null |
                                head -n 1 |
                                sed -E 's/.*VERSION_//; s/_/./g' ||
                                true
                            )"
                        fi
         
                        if [ -z "$JAVA_VERSION" ]; then
                            JAVA_VERSION="$(
                                sed -nE \
                                    's/.*(sourceCompatibility|targetCompatibility)[[:space:]]*=[[:space:]]*["'\\'']?([0-9]+(\\.[0-9]+)?).*/\\2/p' \
                                    "$GRADLE_BUILD_FILE" |
                                head -n 1
                            )"
                        fi
         
                        # Check gradle.properties as another source
                        if [ -z "$JAVA_VERSION" ] && [ -f "gradle.properties" ]; then
                            JAVA_VERSION="$(
                                sed -nE \
                                    's/^[[:space:]]*(javaVersion|java.version)[[:space:]]*=[[:space:]]*([^[:space:]]+).*/\\2/p' \
                                    gradle.properties |
                                head -n 1
                            )"
                        fi
                    else
                        echo "[ERROR] No pom.xml, build.gradle, or build.gradle.kts was found." >&2
                        exit 1
                    fi  
                    JAVA_VERSION="$(echo "$JAVA_VERSION" | tr -d '[:space:]')"
         
                    case "$JAVA_VERSION" in
                        1.8)
                            JAVA_VERSION="8"
                            ;;
                        1.*)
                            JAVA_VERSION="${JAVA_VERSION#1.}"
                            JAVA_VERSION="${JAVA_VERSION%%.*}"
                            ;;
                        *)
                            JAVA_VERSION="${JAVA_VERSION%%.*}"
                            ;;
                    esac
         
                    if [ -z "$JAVA_VERSION" ]; then
                        echo "[ERROR] Could not determine the required Java version." >&2
                        exit 1
                    fi  
                        
                    echo "[INFO] Required Java version: $JAVA_VERSION" >&2            
                    echo "[INFO] Searching for installed JDK matching Java $JAVA_VERSION..." >&2
         
                    JAVA_HOME_SELECTED=""
                    while IFS= read -r JAVA_BIN; do
                     
                        CANDIDATE_JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
                        CANDIDATE_VERSION="$(
                            "$JAVA_BIN" -version 2>&1 |
                            sed -nE 's/.*version "([^"]+)".*/\\1/p' |
                            head -n 1
                        )"
                     
                        if echo "$CANDIDATE_VERSION" | grep -q '^1\\.'; then
                            CANDIDATE_MAJOR="$(
                                echo "$CANDIDATE_VERSION" |
                                cut -d. -f2
                            )"
                        else
                            CANDIDATE_MAJOR="$(
                                echo "$CANDIDATE_VERSION" |
                                cut -d. -f1
                            )"
                        fi
                     
                        echo "[INFO] Found JDK:" >&2
                        echo " JAVA_HOME=$CANDIDATE_JAVA_HOME" >&2
                        echo " Version=$CANDIDATE_VERSION" >&2
                        echo " Major=$CANDIDATE_MAJOR" >&2
                     
                        if [ "$CANDIDATE_MAJOR" = "$JAVA_VERSION" ]; then
                            JAVA_HOME_SELECTED="$CANDIDATE_JAVA_HOME"
                     
                            echo "[INFO] Matching Java $JAVA_VERSION found." >&2
                            echo "JAVA_HOME=$JAVA_HOME_SELECTED" >&2
                            break
                        fi
                     
                    done < <(
                        find \
                            /usr/lib/jvm \
                            /opt \
                            /usr/local \
                            "$HOME" \
                            -type f \
                            -path '*/bin/java' \
                            -perm -u+x \
                            2>/dev/null
                    )
                     
                    if [ -z "$JAVA_HOME_SELECTED" ]; then
                        echo "[ERROR] Java $JAVA_VERSION is required but was not found." >&2
                        exit 1
                    fi
                     
                    echo "$JAVA_HOME_SELECTED"
                ''').trim()
            }
            script.env.JAVA_HOME = javaPath
            script.env.PATH = "${javaPath}/bin:${script.env.PATH}"
            script.env.HAS_BUILD_TOOL_INSTALLED = 'true'
            return buildTool
        }
    }

    private def findCoverageReports() {
        List paths = (script.sh(script: "find '${script.env.WORKSPACE}' -type f -iname 'jacoco.xml'", returnStdout: true).trim().split('\n') as List).findAll { it?.trim() }
        paths.each {
            script.echo "===== Jacoco coverage reports: ${it} ====="
        }
        return paths
    }

    private String resolveJacocoPath(String tool) {
        def cfgPath = state.cfg.coverage?.reportPath?.trim()
        if (cfgPath && script.fileExists(cfgPath)) return cfgPath
        def candidates = (tool == 'maven')
            ? ['target/site/jacoco/jacoco.xml', 'target/jacoco/jacoco.xml']
            : ['build/jacoco/jacoco.xml',
               'build/reports/jacoco/test/jacocoTestReport.xml',
               'build/reports/jacoco/jacocoTestReport.xml']
        for (String p : candidates) { if (script.fileExists(p)) return p }
        return cfgPath ?: candidates[0]
    }

    private List readJaCoCoPerClass(String xmlPath) {
        String result = script.sh(returnStdout: true, script: """
python3 - "${xmlPath}" <<'PYEOF'
import xml.etree.ElementTree as ET
import sys
 
try:
    tree = ET.parse(sys.argv[1])
    root = tree.getroot()
 
    for pkg in root.findall('package'):
        package_name = pkg.get('name', '').replace('/', '.')
 
        for cls in pkg.findall('class'):
            class_name = cls.get('name', '').replace('/', '.')
            source_file = cls.get('sourcefilename', '')
 
            covered = 0
            missed = 0
 
            for counter in cls.findall('counter'):
                if counter.get('type') == 'LINE':
                    covered = int(counter.get('covered', 0))
                    missed = int(counter.get('missed', 0))
 
            total = covered + missed
            pct = round(covered * 100.0 / total, 1) if total else 0.0
 
            print(f"{package_name},{class_name},{source_file},{pct},{covered},{missed},{total}")
 
except Exception:
    print("ERROR,,,,0.0,0,0,0")
PYEOF
""").trim()

        def stats = []

        result.split('\\n').each { line ->
            def parts = line.split(',')

            if (parts.size() >= 7 && parts[0] != 'ERROR') {
                stats << [
                        packageName: parts[0],
                        className  : parts[1],
                        sourceFile : parts[2],
                        pct        : parts[3] as double,
                        covered    : parts[4] as int,
                        missed     : parts[5] as int,
                        total      : parts[6] as int
                ]
            }
        }

        return stats
    }

    private Map readJacoco(String xmlPath) {
        String result
        if (os.isWindows()) {
            result = script.powershell(returnStdout: true, script: """
\$ErrorActionPreference = 'SilentlyContinue'
try {
    [xml]\$doc = Get-Content -Path '${xmlPath}' -Raw
    \$counter = \$doc.report.counter | Where-Object { \$_.type -eq 'LINE' } | Select-Object -First 1
    if (\$counter) {
        \$covered = [int]\$counter.covered; \$missed = [int]\$counter.missed; \$total = \$covered + \$missed
        \$pct = if (\$total -gt 0) { [math]::Round(\$covered * 100.0 / \$total, 1) } else { 0.0 }
        Write-Output "\$pct,\$covered,\$missed"
    } else { Write-Output '0.0,0,0' }
} catch { Write-Output '0.0,0,0' }
""").trim()
        } else {
            result = script.sh(returnStdout: true, script: """
python3 - "${xmlPath}" <<'PYEOF'
import xml.etree.ElementTree as ET, sys
try:
    tree = ET.parse(sys.argv[1])
    for c in tree.getroot().findall('counter'):
        if c.get('type') == 'LINE':
            covered = int(c.get('covered', 0)); missed = int(c.get('missed', 0))
            total = covered + missed
            pct = round(covered * 100.0 / total, 1) if total else 0.0
            print(f'{pct},{covered},{missed}'); sys.exit(0)
    print('0.0,0,0')
except Exception: print('0.0,0,0')
PYEOF
""").trim()
        }
        return coverageFrom(result)
    }

    private Map readLcov(String lcovPath) {
        String result
        if (os.isWindows()) {
            result = script.powershell(returnStdout: true, script: """
\$total = 0; \$covered = 0
Get-Content '${lcovPath}' | ForEach-Object {
    if (\$_ -match '^DA:\\d+,(\\d+)') { \$total++; if ([int]\$Matches[1] -gt 0) { \$covered++ } }
}
\$pct = if (\$total -gt 0) { [math]::Round(\$covered * 100.0 / \$total, 1) } else { 0.0 }
Write-Output "\$pct,\$covered,\$(\$total - \$covered)"
""").trim()
        } else {
            result = script.sh(returnStdout: true, script: """
awk -F: '/^DA:/{split(\$2,a,","); total++; if(a[2]>0) covered++} END{pct=(total>0)?(covered*100.0/total):0; printf "%.1f,%d,%d\\n",pct,covered,total-covered}' "${lcovPath}" 2>/dev/null || echo "0.0,0,0"
""").trim()
        }
        return coverageFrom(result)
    }

    private Map parseFlutterTestReport() {
        String out
        if (os.isWindows()) {
            out = script.powershell(returnStdout: true, script: '''
$passed = 0; $failed = 0; $skipped = 0
try {
    Get-Content test_report.json | ForEach-Object {
        try {
            $e = $_ | ConvertFrom-Json
            if ($e.type -eq 'testDone') {
                switch ($e.result) {
                    'success' { $passed++ }
                    'failure' { $failed++ }
                    'error'   { $failed++ }
                    'skipped' { $skipped++ }
                }
            }
        } catch {}
    }
} catch {}
Write-Output "$passed,$failed,$skipped"
''').trim()
        } else {
            out = script.sh(returnStdout: true, script: '''
python3 - <<'PYEOF'
import json
passed = failed = skipped = 0
try:
    for line in open('test_report.json'):
        try:
            e = json.loads(line)
            if e.get('type') == 'testDone':
                r = e.get('result', '')
                if r == 'success':             passed  += 1
                elif r in ('failure','error'): failed  += 1
                elif r == 'skipped':           skipped += 1
        except: pass
except: pass
print(f'{passed},{failed},{skipped}')
PYEOF
''').trim()
        }
        def parts = out.split(',')
        return [
            passed:  parts.length > 0 ? (parts[0] as int) : 0,
            failed:  parts.length > 1 ? (parts[1] as int) : 0,
            skipped: parts.length > 2 ? (parts[2] as int) : 0
        ]
    }

    private String flutterArtifactGlob(String platform, boolean windows) {
        def sep = windows ? '\\' : '/'
        def globs = [
            apk:       "build${sep}app${sep}outputs${sep}flutter-apk${sep}*.apk",
            appbundle: "build${sep}app${sep}outputs${sep}bundle${sep}release${sep}*.aab",
            ios:       "build${sep}ios${sep}iphoneos${sep}*.ipa",
            macos:     "build${sep}macos${sep}Build${sep}Products${sep}Release${sep}*.app",
            linux:     "build${sep}linux${sep}x64${sep}release${sep}bundle${sep}*",
            windows:   "build${sep}windows${sep}x64${sep}runner${sep}Release${sep}*.exe",
            web:       ''
        ]
        return globs.get(platform) ?: ''
    }

    @NonCPS
    private static Map coverageFrom(String result) {
        List parts = (result ?: '').split(',') as List
        double pct = parts.size() > 0 ? (parts[0] as double) : 0.0d
        int covered = parts.size() > 1 ? (parts[1] as int) : 0
        int missed = parts.size() > 2 ? (parts[2] as int) : 0
        return [line: pct, covered: covered, missed: missed, total: covered + missed]
    }
}
