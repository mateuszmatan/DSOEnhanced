import devsecops.test.FakeAbort
import devsecops.test.FakeIqEvaluation
import devsecops.test.FakeQualityGate
import devsecops.test.FakeRemoteHandle
import devsecops.test.FakeRun
import devsecops.test.FakeScript
import devsecops.test.GoldenFixResponder
import devsecops.test.PortalFixtures
import devsecops.test.SandboxHarness
import org.yaml.snakeyaml.Yaml

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File reportsDir = new File(root, 'examples/reports')
reportsDir.mkdirs()

String MONITOR = 'Monitor source changes (download sources)'
String UNIT = 'Unit tests'
String BUILD = 'Build artifact'
String NIQ = 'Dependencies scan (Nexus IQ)'
String SAST = 'SAST - Static Application Security Tests - HCL AppScan'
String SONAR = 'SCA (SonarQube)'
String SNAPSHOT = 'Nexus delivery (Static analysis passed)'
String RD = 'Lower test region deployment'
String REGRESSION = 'Regression tests (>60% user stories coverage)'
String SMOKE = 'Smoke tests'
String PERFORMANCE = 'Performance tests'
String DAST = 'DAST - Dynamic Application Security Tests - HCL AppScan'
String RELEASE = 'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities'
String QC = 'Higher test environment deployment'

int failures = 0
Closure check = { String name, boolean ok, Object detail ->
    if (ok) {
        println "PASS  ${name}"
    } else {
        failures++
        println "FAIL  ${name}"
        if (detail) println "      ${detail}"
    }
}

String defaultsText = new File(root, 'test/fixtures/defaults.yaml').text
String configText = new File(root, 'test/fixtures/CertScanner/config.yaml').text
Map platform = (new groovy.json.JsonSlurperClassic().parseText(new File(root, 'test/fixtures/portal/certscanner-gui-full.json').text) as Map).platform as Map

List<String> classNames = []
srcDir.eachFileRecurse { File f ->
    if (f.name.endsWith('.groovy')) {
        classNames << f.absolutePath.substring(srcDir.absolutePath.length() + 1).replace('/', '.').replace('.groovy', '')
    }
}
classNames.sort()

SandboxHarness initHarness = new SandboxHarness(srcDir, stubDir)
List<String> initProblems = []
classNames.each { String name ->
    try {
        initHarness.runWith(initHarness.pipelineWhitelist) { Class.forName(name, true, initHarness.loader) }
    } catch (Throwable t) {
        initProblems << "${name}: ${SandboxHarness.rejectionOf(t) ?: t.toString()}".toString()
    }
}
check("every library class initialises under the whitelist used while the CPS program is saved (${classNames.size()} classes)",
        initProblems.isEmpty(), initProblems.join('\n      '))

File baselineFile = new File(root, 'test/sandbox/baseline/appscan-commands.txt')
SandboxHarness appHarness = new SandboxHarness(srcDir, stubDir)
FakeScript appScript = new FakeScript()
appScript.env.vars.putAll([
        WORKSPACE         : '/ws', APPSCAN_LOG_DIR: '/ws/.appscan-logs', APPSCAN_BIN_DIR: '/ws/.appscan-bin',
        APPSCAN_HOME_DIR  : '/ws/.appscan-home', APPSCAN_TOOLS_DIR: '/ws/.appscan-tools', APPSCAN_SCAN_NAME: 'demo-scan',
        APPSCAN_SERVER_URL: 'https://bbh.cloud.appscan.com', APPSCAN_HOST: 'bbh.cloud.appscan.com', APPSCAN_KEY_ID: 'keyid',
        APPSCAN_KEY_SECRET: 'secret', PROXY_HOST: 'tstproxy.bbh.com', PROXY_PORT: '9090', PROXY_USER: 'PROXY_ASOCJenk',
        PROXY_PASS        : 'proxypass', BUILD_NUMBER: '7'
])
appScript.files['.appscan-logs/appscan.cmd'] = '/opt/appscan/appscan.sh'
appScript.files['demo-scan.irx'] = 'irx'
appScript.readFileHandler = { String path -> 'content' }
appScript.recordEvents = true
String appscanHtmlReport = '<html><body><h3>Summary of security issues</h3><table>' +
        '<tr><td>Total security issues:</td><td>2</td></tr><tr><td>Critical severity issues:</td><td>0</td></tr>' +
        '<tr><td>High severity issues:</td><td>0</td></tr><tr><td>Medium severity issues:</td><td>0</td></tr>' +
        '<tr><td>Low severity issues:</td><td>2</td></tr></table></body></html>'
appScript.shHandler = { Map a ->
    String text = String.valueOf(a.script ?: '')
    appScript.events << "[SH]\n${text}".toString()
    def outFile = text =~ /-o "([^"]+)"/
    if (outFile.find()) {
        String target = outFile.group(1)
        appScript.files[target] = target.endsWith('.pdf') ? '%PDF-1.7 fake report' : appscanHtmlReport
    }
    if (a.returnStatus) return 0
    if (!a.returnStdout) return null
    if (text.contains('ApiKeyLogin')) return '{"Token":"BEARER-TOKEN"}'
    if (text.contains('/api/v4/Scans/Dast/')) return '{"LatestExecution":{"Status":"Ready"}}'
    if (text.contains('/api/v4/Scans/Dast')) return '{"Id":"DAST-SCAN-ID"}'
    if (text.contains('/api/v4/Reports/Security/Scan/')) return '{"Id":"REPORT-ID"}'
    if (text.contains('/api/v4/Reports/REPORT-ID')) return '{"Status":"Ready"}'
    if (text.contains('queue_analysis')) return 'Scan id: 12345678-1234-1234-1234-123456789abc'
    return ''
}
List<String> appErrors = []
appHarness.run {
    def state = appHarness.type('com.bbh.core.PipelineState').newInstance()
    state.cfg = [appId: 'APP-ID', buildTool: 'gradle', asoc: [token: 'asoc-token'],
                 dast : [enabled: true, targetUrl: 'http://rd.example.com', presenceId: 'PRES-1']]
    state.cfgDefaults = [dast: [pollTimeoutMin: 60, pollIntervalSec: 60], sast: [pollTimeoutMin: 50, pollIntervalSec: 30]]
    state.currentProjectName = 'gui'
    def os = appHarness.type('com.bbh.core.OsHelper').newInstance(appScript)
    def policy = appHarness.type('com.bbh.core.PolicyEngine').newInstance(appScript, state, os)
    def build = appHarness.type('com.bbh.build.BuildService').newInstance(appScript, state, os, policy)
    def appscan = appHarness.type('com.bbh.scanner.AppScanService').newInstance(appScript, state, os, policy, build)
    [
            ['cliLogin', { appscan.cliLogin() }],
            ['apiLogin', { appscan.apiLogin() }],
            ['dastStartScan', { appScript.events << "scanId=${appscan.dastStartScan()}".toString() }],
            ['dastWait', { appscan.dastWait('DAST-SCAN-ID', 1, 1) }],
            ['dastDownloadReport', { appscan.dastDownloadReport('DAST-SCAN-ID') }],
            ['renameDastReport', { appscan.renameDastReport() }],
            ['renameSastReport', { appscan.renameSastReport() }],
            ['queueSast', { appscan.queueSast([appId: 'APP-ID']) }],
            ['waitSast', { appscan.waitSast() }],
            ['downloadSastReports', { appscan.downloadSastReports() }]
    ].each { List step ->
        appScript.events << "===== ${step[0]} =====".toString()
        try {
            (step[1] as Closure).call()
        } catch (Throwable t) {
            appErrors << "${step[0]}: ${SandboxHarness.rejectionOf(t) ?: t.toString()}".toString()
            appScript.events << "${step[0]}: ${t.getClass().simpleName}: ${t.message}".toString()
        }
    }
}
String commands = appScript.events.join('\n---\n')
check('AppScan service: the SAST and DAST calls run without a rejection or an exception', appErrors.isEmpty(), appErrors.join('\n      '))
if (!baselineFile.exists()) {
    baselineFile.parentFile.mkdirs()
    baselineFile.text = commands
    println "      note: AppScan command baseline created at ${baselineFile}"
}
boolean sameCommands = baselineFile.text == commands
if (!sameCommands) new File(baselineFile.parentFile, 'appscan-commands.actual.txt').text = commands
check('AppScan service: generated shell and REST commands match the baseline', sameCommands,
        'compare test/sandbox/baseline/appscan-commands.txt with appscan-commands.actual.txt')

SandboxHarness parserHarness = new SandboxHarness(srcDir, stubDir)
String hclSummaryReport = '''<html><body><h3>Summary of security issues</h3><table>
<tr><td>Total security issues:</td><td>7</td></tr>
<tr><td>Critical severity issues:</td><td>0</td></tr>
<tr><td>High severity issues:</td><td>2</td></tr>
<tr><td>Medium severity issues:</td><td>3</td></tr>
<tr><td>Low severity issues:</td><td>2</td></tr>
</table></body></html>'''
Map summaryCounts = parserHarness.run { parserHarness.type('com.bbh.utils.AppScanReportParser').severities(hclSummaryReport) } as Map
Map escapedCounts = parserHarness.run { parserHarness.type('com.bbh.utils.AppScanReportParser').severities(hclSummaryReport.replace('<', '&lt;').replace('>', '&gt;')) } as Map
String hclIssueReport = '''<html><body><div>Issue ID: 1</div><div>Severity: High</div><div>Status: Open</div>
<div>Issue ID: 2</div><div>Severity: High</div><div>Status: Open</div>
<div>Issue ID: 3</div><div>Severity: Medium</div><div>Status: Fixed</div></body></html>'''
Map issueCounts = parserHarness.run { parserHarness.type('com.bbh.utils.AppScanReportParser').severities(hclIssueReport) } as Map
check('AppScan report parser: the severity totals of the HTML report are the ones the pipeline reports',
        summaryCounts.high == 2 && summaryCounts.medium == 3 && summaryCounts.low == 2 && summaryCounts.critical == 0
                && escapedCounts.high == 2 && issueCounts.high == 2 && issueCounts.medium == 0,
        "summary ${summaryCounts}, escaped ${escapedCounts}, issue blocks ${issueCounts}")

def jsonNull = Class.forName('net.sf.json.JSONNull').getInstance()
Map iqReportWithNulls = [components: [
        [componentIdentifier: jsonNull, packageUrl: 'pkg:generic/unknown', violations: [[policyThreatLevel: 9]]],
        [componentIdentifier: [format: 'maven', coordinates: [groupId: 'org.yaml', artifactId: 'snakeyaml', version: '1.30']],
         dependencyData     : jsonNull, violations: [[policyThreatLevel: 7]], packageUrl: 'pkg:maven/org.yaml/snakeyaml@1.30']]]
String rawFailure = ''
try {
    parserHarness.run { parserHarness.type('com.bbh.scanner.NexusIqGoldenFixSource').selectCandidates(iqReportWithNulls, 2, false, ['maven', 'npm', 'pypi']) }
} catch (Throwable t) {
    rawFailure = t.message ?: t.getClass().simpleName
}
List cleanedCandidates = []
String cleanedFailure = ''
try {
    def cleaned = parserHarness.run { parserHarness.type('com.bbh.utils.RestClient').withoutJsonNulls(iqReportWithNulls) }
    cleanedCandidates = parserHarness.run { parserHarness.type('com.bbh.scanner.NexusIqGoldenFixSource').selectCandidates(cleaned, 2, false, ['maven', 'npm', 'pypi']) } as List
} catch (Throwable t) {
    cleanedFailure = t.message ?: t.getClass().simpleName
}
check('GoldenFix: a Nexus IQ report carrying JSON nulls still yields the components to upgrade',
        rawFailure.contains('JSONNull') && !cleanedFailure && cleanedCandidates.size() == 1
                && cleanedCandidates[0].name == 'snakeyaml',
        "without normalisation: '${rawFailure}', with normalisation: '${cleanedFailure}' ${cleanedCandidates}")

SandboxHarness dastHarness = new SandboxHarness(srcDir, stubDir)
Closure dastRun = { Closure downloadBody ->
    FakeScript d = new FakeScript()
    d.env.vars.putAll([WORKSPACE: '/ws', APPSCAN_LOG_DIR: '/ws/.appscan-logs', APPSCAN_SCAN_NAME: 'demo-scan',
                       APPSCAN_SERVER_URL: 'https://bbh.cloud.appscan.com', APPSCAN_KEY_ID: 'keyid', BUILD_NUMBER: '9'])
    d.shHandler = { Map a ->
        String text = String.valueOf(a.script ?: '')
        def outFile = text =~ /-o "([^"]+)"/
        if (outFile.find()) downloadBody.call(d, outFile.group(1))
        def move = text =~ /mv '([^']+)' '([^']+)'/
        if (move.find()) {
            String from = move.group(1).replace('/ws/', '')
            String to = move.group(2).replace('/ws/', '')
            if (d.files.containsKey(from)) d.files[to] = d.files.remove(from)
        }
        if (a.returnStatus) return 0
        if (!a.returnStdout) return null
        if (text.contains('ApiKeyLogin')) return '{"Token":"BEARER-TOKEN"}'
        if (text.contains('/api/v4/Scans/Dast/')) return '{"LatestExecution":{"Status":"Ready"}}'
        if (text.contains('/api/v4/Scans/Dast')) return '{"Id":"DAST-SCAN-ID"}'
        if (text.contains('/api/v4/Reports/Security/Scan/')) return '{"Id":"REPORT-ID"}'
        if (text.contains('/api/v4/Reports/REPORT-ID')) return '{"Status":"Ready"}'
        return ''
    }
    String failure = ''
    Map counted = [:]
    dastHarness.run {
        def state = dastHarness.type('com.bbh.core.PipelineState').newInstance()
        state.cfg = [appId: 'APP-ID', buildTool: 'gradle', dast: [enabled: true, targetUrl: 'http://rd.example.com']]
        state.cfgDefaults = [dast: [pollTimeoutMin: 1, pollIntervalSec: 1, reportTimeoutMin: 1, reportIntervalSec: 30]]
        state.currentProjectName = 'gui'
        def os = dastHarness.type('com.bbh.core.OsHelper').newInstance(d)
        def policy = dastHarness.type('com.bbh.core.PolicyEngine').newInstance(d, state, os)
        def build = dastHarness.type('com.bbh.build.BuildService').newInstance(d, state, os, policy)
        def appscan = dastHarness.type('com.bbh.scanner.AppScanService').newInstance(d, state, os, policy, build)
        try {
            appscan.dastScan()
            counted.putAll((state.vulnCounts.dast ?: [:]) as Map)
        } catch (Throwable t) {
            failure = t.message ?: t.getClass().simpleName
        }
    }
    return [failure: failure, state: d, counts: counted]
}
String goodHtml = '<html><body><h3>Summary of security issues</h3><table>' +
        '<tr><td>High severity issues:</td><td>3</td></tr></table></body></html>'
Map errorPayload = dastRun.call({ FakeScript d, String target ->
    d.files[target] = target.endsWith('.pdf') ? '%PDF-1.7' : '{"Message":"Report is not ready yet","Code":409}'
})
Map noFile = dastRun.call({ FakeScript d, String target -> })
Map healthy = dastRun.call({ FakeScript d, String target ->
    d.files[target] = target.endsWith('.pdf') ? '%PDF-1.7' : goodHtml
})
check('DAST: an AppScan error message saved in place of the report fails the stage instead of passing it as zero findings',
        (errorPayload.failure as String).contains('HTML report could not be produced')
                && (errorPayload.failure as String).contains('Report is not ready yet'),
        "error payload: '${errorPayload.failure}'")
check('DAST: a download that produces no report file fails the stage',
        (noFile.failure as String).contains('HTML report could not be produced'), "no file: '${noFile.failure}'")
check('DAST: a real report is accepted and its findings are counted',
        !healthy.failure && ((healthy.state as FakeScript).files.containsKey('appscan-dast-report-demo-scan.html'))
                && ((healthy.counts as Map).high as int) == 3,
        "healthy: '${healthy.failure}', counts ${healthy.counts}")

File fixturesDir = new File(root, 'test/sandbox/fixtures')
SandboxHarness gfHarness = new SandboxHarness(srcDir, stubDir, fixturesDir)
FakeScript gfScript = new FakeScript()
gfScript.env.vars.putAll([WORKSPACE: '/ws', JOB_NAME: 'DevSecOps/CertScanner-security-pipeline', BUILD_NUMBER: '213',
                          BUILD_URL: 'https://jenkins.bbh.com/job/DevSecOps/job/CertScanner-security-pipeline/213/'])
gfScript.shHandler = { Map a -> a.returnStdout ? '202609191405' : (a.returnStatus ? 0 : null) }
Map<String, String> manifests = [
        'pom.xml'               : """<project>
  <properties>
    <jackson.version>2.13.4</jackson.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-text</artifactId>
      <version>1.9</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>\${jackson.version}</version>
    </dependency>
  </dependencies>
</project>
""".toString(),
        'gui/build.gradle'      : 'dependencies {\n    implementation "io.netty:netty-codec-http:${nettyVersion}"\n}\n',
        'gradle.properties'     : 'nettyVersion=4.1.86.Final\n',
        'web/package.json'      : '{\n  "dependencies": {\n    "lodash": "^4.17.15"\n  }\n}\n',
        'tools/requirements.txt': 'requests==2.25.1\nurllib3==1.26.5\n',
        'mobile/pubspec.yaml'   : 'name: cert_scanner\nversion: 1.4.0\n\ndependencies:\n  flutter:\n    sdk: flutter\n  http: ^0.13.4\n  intl: 0.17.0\n'
]
Map gfResult = [:]
Map gfFiles = [:]
List gfPrs = []
List<String> gfErrors = []
try {
    gfHarness.run {
        def goldenFix = gfHarness.type('com.bbh.remediation.model.GoldenFix')
        String app = 'CertValidityMonitoring-GUI'
        List fixes = [
                goldenFix.create('maven', 'org.apache.commons', 'commons-text', '1.9', '1.10.0', 'recommended-non-breaking-with-dependencies', '', 8, true, app, true),
                goldenFix.create('maven', 'com.fasterxml.jackson.core', 'jackson-databind', '2.13.4', '2.15.4', 'next-no-violations', '', 8, true, app),
                goldenFix.create('maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final', 'next-no-violations', '', 4, true, app),
                goldenFix.create('npm', '', 'lodash', '4.17.15', '4.17.21', 'next-no-violations', '', 4, true, app),
                goldenFix.create('pypi', '', 'requests', '2.25.1', '2.32.3', 'next-no-violations', '', 4, true, app),
                goldenFix.create('pub', '', 'http', '0.13.4', '0.13.6', 'next-no-violations', '', 4, true, app)
        ]
        def state = gfHarness.type('com.bbh.core.PipelineState').newInstance()
        state.currentProjectName = 'gui'
        state.cfg = [goldenFix: [enabled: true],
                     scm      : [bitbucket: [url: 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner', credentialsId: 'bitbucket-http-credentials']]]
        def repository = gfHarness.type('com.bbh.fixture.InMemorySourceRepository').newInstance([manifests] as Object[])
        def source = gfHarness.type('com.bbh.fixture.StaticGoldenFixSource').newInstance([fixes] as Object[])
        def publisher = gfHarness.type('com.bbh.fixture.RecordingPullRequestPublisher').newInstance()
        List updaters = ['MavenPomUpdater', 'GradleUpdater', 'NpmPackageJsonUpdater', 'PipUpdater', 'PubUpdater'].collect {
            gfHarness.type("com.bbh.remediation.updater.${it}".toString()).newInstance()
        }
        def service = gfHarness.type('com.bbh.remediation.GoldenFixService').newInstance(gfScript, state, source, repository, publisher, updaters)
        service.remediate([[application: app, serverUrl: 'https://tools.bbh.com/IQ', scanId: 'a' * 32, reportUrl: 'https://tools.bbh.com/IQ/report']])
        gfResult = (state.projectsGoldenFix['gui'] ?: [:]) as Map
        gfFiles = repository.written
        gfPrs = publisher.created
    }
} catch (Throwable t) {
    gfErrors << (SandboxHarness.rejectionOf(t) ?: t.toString())
}
SandboxHarness metricsHarness = new SandboxHarness(srcDir, stubDir)
List metricLines = metricsHarness.run {
    def st = metricsHarness.type('com.bbh.core.PipelineState').newInstance()
    st.commitTime = System.currentTimeMillis() - 7200000L
    st.commitSha = 'ab12cd34ef567890'
    st.projectsAllCfg = [gui: [deploy: [openshift: [rd: [buildTag: '212-20261006-081500']]]], 'backend-api': [:]]
    st.portalDocuments = [[hint: '5e0f0001...0001', renderedAt: '2026-10-06T08:14:19.475Z', sha256: '0f1e2d3c4b5a6901', config: [projects: [gui: [:]]]]]
    st.projectsUnitTests = [gui: [total: 214, failed: 2, skipped: 3, durationMs: 42000L]]
    st.projectsSonarResults = [gui: [status: 'PASS', qualityGate: 'OK', url: 'https://tools.bbh.com/sonar/dashboard?id=cert-scanner-gui']]
    st.projectsNexusIqResults = [gui: [url: 'https://tools.bbh.com/IQ/ui/links/application/cert-scanner-gui/report/0f1e2d3c']]
    st.stageResults = ['Monitor source changes (download sources)': 'PASS', 'Unit tests': 'WARN',
                       'SAST - Static Application Security Tests - HCL AppScan': 'WARN',
                       'Lower test region deployment': 'PASS', 'Smoke tests': 'PASS',
                       'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities': 'BLOCKED']
    long t = System.currentTimeMillis()
    st.stageTimes['Unit tests'] = [start: t - 120000L, end: t - 60000L]
    st.stageErrors['Unit tests'] = 'gui: Line coverage 45.0% is below the required 75%.'
    st.policyLimits = [sast: [maxCritical: 0, maxHigh: 0, maxMedium: 0], sca: [maxCritical: 0, maxHigh: 0, maxMedium: 0],
                       dast: [maxCritical: 0, maxHigh: 0, maxMedium: 0], niq: [maxCritical: 0, maxHigh: 0, maxMedium: 0]]
    st.policyStatus = [sast: 'WARN', sca: 'PASS', dast: 'SKIP', coverage: 'WARN', sonar: 'PASS']
    st.projectsVulnCounts = [gui: [sast: [critical: 0, high: 2, medium: 3, low: 5]]]
    st.projectsScanResults = [gui: [sast: 'WARN', sast_file: 'appscan-report-gui.html']]
    st.projectsCoverage = [gui: [enabled: true, line: 45.0, covered: 450, missed: 550, total: 1000, minRequired: 75]]
    st.projectsRemoteTestResults = [gui: ['Smoke tests': [[name: 'gui smoke, one', status: 'SUCCESS', durationMs: 64000L, type: 'remote'],
                                                          [name: 'gui-smoke-2', status: 'FAILURE', durationMs: 12000L, type: 'local']]]]
    st.projectsGoldenFix = [gui          : [status: 'PR_CREATED', fixes: [1, 2, 3], changes: [1, 2], unresolved: [1], verified: true,
                                           prUrl : GoldenFixResponder.PULL_REQUEST_URL, prTitle: "GoldenFix-${GoldenFixResponder.STAMP}".toString()],
                            'backend-api': [status: 'BUILD_FAILED', fixes: [1], changes: [1], unresolved: [], verified: false,
                                           prTitle: "GoldenFix-${GoldenFixResponder.STAMP}".toString()]]
    Map ctx = [project: 'Cert Scanner', env: 'test', variant: 'security', result: 'UNSTABLE', failed: true,
               durationSeconds: 512L, buildNumber: '212', job: 'DevSecOps/CertScanner', branch: 'develop',
               buildUrl: 'https://jenkins.bbh.com/job/DevSecOps/job/CertScanner/212/',
               timestamp: (t / 1000L) as long, deployed: true, released: false,
               releaseGate: [allowed: false, violations: ['gui SAST high 2 > 0'], reason: 'blocked']]
    metricsHarness.type('com.bbh.metrics.PipelineMetrics').lines(st, ctx)
} as List
List measurements = metricLines.collect { (it as String).split(/[, ]/)[0] }.unique().sort()
String doraLine = metricLines.find { (it as String).startsWith('dora,') } as String
String stageLine = metricLines.find { (it as String).startsWith('stage_event,') && it.contains('Unit\\ tests') } as String
String jobLine = metricLines.find { (it as String).startsWith('test_job,') } as String
boolean wellFormed = metricLines.every { String l ->
    List sections = l.split(' ') as List
    sections.size() >= 3 && (sections[-1] ==~ /\d+/) && l.contains('=')
}
check('InfluxDB: the whole pipeline is covered - run, DORA, stages, findings, coverage, tests, gate, remediation and the change evidence (build_evidence, added for the portal Change Evidence tab)',
        measurements == ['build_duration', 'build_evidence', 'change_failure', 'code_coverage', 'deployments', 'dora', 'goldenfix',
                         'pipeline_run', 'policy_status', 'release_gate', 'security_findings', 'stage_event',
                         'stage_metric', 'test_execution', 'test_job', 'vulnerabilities'],
        measurements)
check('InfluxDB: every line is valid line protocol with an explicit timestamp and escaped tags',
        wellFormed && metricLines.every { !(it as String).contains('project=Cert Scanner') }
                && metricLines.any { (it as String).contains('project=Cert\\ Scanner') },
        metricLines.findAll { !((it as String).split(' ') as List)[-1].matches(/\d+/) })
check('InfluxDB: DORA carries deployment, lead time from the commit, change failure and release',
        doraLine && doraLine.contains('deployment=1i') && doraLine.contains('change_failure=1i')
                && doraLine.contains('released=0i') && (doraLine =~ /lead_time_s=(\d+)i/) .with { it.find() && (it.group(1) as long) >= 7000L },
        doraLine)
check('InfluxDB: a stage point is stamped at the end of that stage and carries its reason',
        stageLine && stageLine.contains('status=WARN') && stageLine.contains('duration_ms=60000i')
                && stageLine.contains('ok=0i') && stageLine.contains('below the required 75'),
        stageLine)
check('InfluxDB: a test job name with a comma is escaped rather than breaking the line',
        jobLine && metricLines.any { (it as String).contains('name="gui smoke, one"') }, jobLine)
String unitLine = metricLines.find { (it as String).startsWith('test_execution,') && it.contains('suite=unit') } as String
check('InfluxDB: the unit test counts of a module are a test_execution point with suite=unit',
        unitLine && unitLine.startsWith('test_execution,project=Cert\\ Scanner,env=test,module=gui,suite=unit ')
                && unitLine.contains('total=214i,passed=209i,failed=2i,skipped=3i,not_configured=0i,duration_ms=42000i,success_rate=97.66'),
        unitLine)
List<String> evidenceLines = metricLines.findAll { (it as String).startsWith('build_evidence,') } as List<String>
String guiEvidence = evidenceLines.find { it.contains('module=gui ') }
String apiEvidence = evidenceLines.find { it.contains('module=backend-api ') }
check('InfluxDB: build_evidence records the artifact version, the quality gate, the report links and the portal document of each module',
        evidenceLines.size() == 2 && guiEvidence && guiEvidence.contains('artifact_version="212-20261006-081500"')
                && guiEvidence.contains('sonar_quality_gate="OK"')
                && guiEvidence.contains('sast_report_url="https://jenkins.bbh.com/job/DevSecOps/job/CertScanner/212/artifact/appscan-report-gui.html"')
                && guiEvidence.contains('nexusiq_report_url="https://tools.bbh.com/IQ/ui/links/application/cert-scanner-gui/report/0f1e2d3c"')
                && guiEvidence.contains('sonar_report_url="https://tools.bbh.com/sonar/dashboard?id=cert-scanner-gui"')
                && guiEvidence.contains('config_rendered_at="2026-10-06T08:14:19.475Z"') && guiEvidence.contains('config_sha256="0f1e2d3c4b5a6901"')
                && !guiEvidence.contains('dast_report_url'),
        guiEvidence)
check('InfluxDB: build_evidence leaves empty values out and falls back to the build number and NONE',
        apiEvidence && apiEvidence.contains('artifact_version="212",sonar_quality_gate="NONE" ') && !apiEvidence.contains('_url') && !apiEvidence.contains('config_'),
        apiEvidence)
List<String> goldenFixLines = metricLines.findAll { (it as String).startsWith('goldenfix,') } as List<String>
String raisedLine = goldenFixLines.find { it.contains(',module=gui,') }
String notRaisedLine = goldenFixLines.find { it.contains(',module=backend-api,') }
check('InfluxDB: the goldenfix point carries pr_url and pr_title when a pull request was raised and leaves them out otherwise',
        raisedLine && raisedLine.contains("pr_raised=1i,build_check=1i,build_failed=0i,pr_url=\"${GoldenFixResponder.PULL_REQUEST_URL}\",pr_title=\"GoldenFix-${GoldenFixResponder.STAMP}\" ".toString())
                && notRaisedLine && notRaisedLine.contains('pr_raised=0i,build_check=0i,build_failed=1i ') && !notRaisedLine.contains('pr_url') && !notRaisedLine.contains('pr_title'),
        goldenFixLines.join('\n      '))

SandboxHarness flutterHarness = new SandboxHarness(srcDir, stubDir)
String flutterLcov = 'SF:/ws/lib/main.dart\nDA:1,3\nDA:2,0\nend_of_record\nSF:/ws/lib/api/client.dart\nDA:4,1\nend_of_record\n'
String flutterAnalyze = "INFO|HINT|UNUSED_IMPORT|/ws/lib/main.dart|3|8|20|Unused import: 'dart:io'.\n" +
        "ERROR|COMPILE_TIME_ERROR|UNDEFINED_METHOD|/ws/lib/api/client.dart|42|5|11|The method 'foo' isn't defined.\n" +
        "WARNING|WARNING|UNUSED_LOCAL_VARIABLE|/ws/lib/main.dart|9|7|3|The value of 'x' isn't used.\n"
String flutterCoverageXml = flutterHarness.run {
    flutterHarness.type('com.bbh.utils.FlutterSonarReports').coverageXml(flutterLcov, '/ws')
} as String
String flutterIssuesJson = flutterHarness.run {
    flutterHarness.type('com.bbh.utils.FlutterSonarReports').issuesJson(flutterAnalyze, '/ws')
} as String
boolean coverageWellFormed = true
try {
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(flutterCoverageXml.getBytes('UTF-8')))
} catch (Throwable t) {
    coverageWellFormed = false
}
def flutterIssues = new groovy.json.JsonSlurper().parseText(flutterIssuesJson)
check('Flutter without the SonarQube plugin: LCOV becomes a well formed generic coverage report with relative paths',
        coverageWellFormed && flutterCoverageXml.contains('<file path="lib/main.dart">')
                && flutterCoverageXml.contains('<lineToCover lineNumber="1" covered="true"/>')
                && flutterCoverageXml.contains('<lineToCover lineNumber="2" covered="false"/>')
                && flutterCoverageXml.contains('<file path="lib/api/client.dart">'),
        flutterCoverageXml)
check('Flutter without the SonarQube plugin: dart analyze becomes generic external issues with mapped severities',
        flutterIssues.issues.size() == 3
                && flutterIssues.issues.every { !(it.primaryLocation.filePath as String).startsWith('/') }
                && flutterIssues.issues.collect { it.severity } == ['INFO', 'MAJOR', 'MINOR']
                && flutterIssues.issues.collect { it.type } == ['CODE_SMELL', 'BUG', 'CODE_SMELL']
                && flutterIssues.issues[1].ruleId == 'UNDEFINED_METHOD'
                && flutterIssues.issues[1].primaryLocation.textRange.startLine == 42,
        flutterIssuesJson)

SandboxHarness verifierHarness = new SandboxHarness(srcDir, stubDir)
List verifierPlan = verifierHarness.run {
    verifierHarness.type('com.bbh.build.ManifestBuildVerifier').newInstance(new FakeScript()).plan(
            ['pom.xml', 'web/package.json', 'tools/requirements.txt', 'gui/build.gradle', 'gradle.properties',
             'mobile/pubspec.yaml', 'docs/readme.md', 'api/pyproject.toml'], [:])
} as List
List verifierKinds = verifierPlan.collect { (it as Map).name }.unique().sort()
List verifierDirs = verifierPlan.collect { "${(it as Map).name}@${(it as Map).subDir}".toString() }.sort()
List overridden = verifierHarness.run {
    verifierHarness.type('com.bbh.build.ManifestBuildVerifier').newInstance(new FakeScript()).plan(
            ['web/package.json'], [npm: 'npm run build'])
} as List
check('GoldenFix pre-check: every ecosystem whose manifest changed is verified, not only Maven',
        verifierKinds == ['gradle', 'maven', 'npm', 'pip', 'pub'],
        "planned ${verifierKinds} from ${verifierPlan.collect { (it as Map).command }}")
check('GoldenFix pre-check: each changed manifest is verified in its own directory and a file it does not know is ignored',
        verifierDirs == ['gradle@', 'gradle@gui', 'maven@', 'npm@web', 'pip@api', 'pip@tools', 'pub@mobile'],
        verifierDirs)
check('GoldenFix pre-check: the command of an ecosystem can be overridden per service',
        overridden.size() == 1 && (overridden[0] as Map).command == 'npm run build', overridden)

Closure preCheckRun = { Closure buildOutcome ->
    SandboxHarness h = new SandboxHarness(srcDir, stubDir, fixturesDir)
    FakeScript sc = new FakeScript()
    sc.env.vars.putAll([WORKSPACE: '/ws', JOB_NAME: 'DevSecOps/CertScanner-security-pipeline', BUILD_NUMBER: '213'])
    List<String> builds = []
    sc.shHandler = { Map a ->
        String text = String.valueOf(a.script ?: '')
        if (a.returnStdout) return '202609191405'
        if (String.valueOf(a.label ?: '').startsWith('GoldenFix: pre-check ')) {
            builds << text
            return buildOutcome.call(builds.size())
        }
        return a.returnStatus ? 0 : null
    }
    Map out = [:]
    List<String> errs = []
    try {
        h.run {
            def goldenFix = h.type('com.bbh.remediation.model.GoldenFix')
            String app = 'CertValidityMonitoring-GUI'
            List fixes = [goldenFix.create('maven', 'com.fasterxml.jackson.core', 'jackson-databind', '2.13.4',
                    '2.13.9', 'next-non-failing', '', 8, true, app, false, true, ['2.13.7', '2.13.5'])]
            def state = h.type('com.bbh.core.PipelineState').newInstance()
            state.currentProjectName = 'gui'
            state.cfg = [buildTool: 'maven', goldenFix: [enabled: true, verify: [enabled: true, maxAttempts: 3]],
                         scm: [bitbucket: [url: 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner', credentialsId: 'bb']]]
            def repository = h.type('com.bbh.fixture.InMemorySourceRepository').newInstance([[
                    'pom.xml': '<project><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId>' +
                               '<artifactId>jackson-databind</artifactId><version>2.13.4</version></dependency></dependencies></project>']] as Object[])
            def source = h.type('com.bbh.fixture.StaticGoldenFixSource').newInstance([fixes] as Object[])
            def publisher = h.type('com.bbh.fixture.RecordingPullRequestPublisher').newInstance()
            List updaters = ['MavenPomUpdater'].collect { h.type("com.bbh.remediation.updater.${it}".toString()).newInstance() }
            def verifier = h.type('com.bbh.build.ManifestBuildVerifier').newInstance(sc)
            def service = h.type('com.bbh.remediation.GoldenFixService').newInstance(sc, state, source, repository, publisher, updaters, verifier)
            service.remediate([[application: app, serverUrl: 'https://tools.bbh.com/IQ', scanId: 'a' * 32]])
            out.putAll((state.projectsGoldenFix['gui'] ?: [:]) as Map)
            out.pom = repository.written['pom.xml']
            out.reverts = repository.reverts
            out.prs = publisher.created.size()
        }
    } catch (Throwable t) {
        errs << (SandboxHarness.rejectionOf(t) ?: t.toString())
    }
    return [result: out, builds: builds.size(), errors: errs, log: sc.log.findAll { it.contains('Pre-check') }]
}
Map firstTry = preCheckRun.call({ int n -> 0 })
Map secondTry = preCheckRun.call({ int n -> n == 1 ? 1 : 0 })
Map neverBuilds = preCheckRun.call({ int n -> 1 })

check('GoldenFix pre-check: a green build opens the pull request with the version that was proposed',
        firstTry.errors.isEmpty() && firstTry.builds == 1 && (firstTry.result as Map).status == 'PR_CREATED'
                && ((firstTry.result as Map).pom as String).contains('<version>2.13.9</version>') && (firstTry.result as Map).prs == 1,
        "${firstTry.errors} builds=${firstTry.builds} status=${(firstTry.result as Map).status}")
check('GoldenFix pre-check: a failing build lowers the version and the pull request carries the one that builds',
        secondTry.errors.isEmpty() && secondTry.builds == 2 && (secondTry.result as Map).status == 'PR_CREATED'
                && ((secondTry.result as Map).pom as String).contains('<version>2.13.7</version>')
                && (secondTry.result as Map).reverts == 1,
        "${secondTry.errors} builds=${secondTry.builds} status=${(secondTry.result as Map).status} pom=${(secondTry.result as Map).pom}")
check('GoldenFix pre-check: when no offered version builds, no pull request is opened and the run says why',
        neverBuilds.errors.isEmpty() && (neverBuilds.result as Map).status == 'BUILD_FAILED'
                && (neverBuilds.result as Map).prs == 0
                && ((neverBuilds.result as Map).message as String).contains('do not build'),
        "${neverBuilds.errors} status=${(neverBuilds.result as Map).status} msg=${(neverBuilds.result as Map).message}")

List<String> gfLog = gfScript.log.findAll { it.contains('GOLDENFIX') }
check('GoldenFix: remediation runs under the sandbox without a rejection', gfErrors.isEmpty() && !gfLog.any { it.contains('Remediation failed') },
        (gfErrors + gfLog).join('\n      '))
check('GoldenFix: pull request raised with the GoldenFix-YYYYMMDDHHMM name', gfResult.status == 'PR_CREATED' && ((gfResult.prTitle ?: '') as String) ==~ /GoldenFix-\d{12}/,
        gfResult)
check('GoldenFix: Maven dependency and version property upgraded in pom.xml', (gfFiles['pom.xml'] ?: '').contains('<version>1.10.0</version>')
        && (gfFiles['pom.xml'] ?: '').contains('<jackson.version>2.15.4</jackson.version>'), gfFiles['pom.xml'])
check('GoldenFix: Gradle version property upgraded in gradle.properties', (gfFiles['gradle.properties'] ?: '').contains('nettyVersion=4.1.108.Final'), gfFiles['gradle.properties'])
check('GoldenFix: npm dependency upgraded keeping the range operator', (gfFiles['web/package.json'] ?: '').contains('"lodash": "^4.17.21"'), gfFiles['web/package.json'])
check('GoldenFix: pip requirement upgraded, other pins untouched', (gfFiles['tools/requirements.txt'] ?: '').contains('requests==2.32.3')
        && (gfFiles['tools/requirements.txt'] ?: '').contains('urllib3==1.26.5'), gfFiles['tools/requirements.txt'])
check('GoldenFix: Flutter pubspec dependency upgraded keeping the caret', (gfFiles['mobile/pubspec.yaml'] ?: '').contains('http: ^0.13.6')
        && (gfFiles['mobile/pubspec.yaml'] ?: '').contains('intl: 0.17.0') && (gfFiles['mobile/pubspec.yaml'] ?: '').contains('version: 1.4.0'),
        gfFiles['mobile/pubspec.yaml'])
check('GoldenFix: pull request description lists every upgrade and how the version was chosen', gfPrs
        && (((gfPrs[0] as Map).description ?: '') as String).count('| `') >= 6
        && (((gfPrs[0] as Map).description ?: '') as String).contains('Nexus IQ Golden Version'),
        gfPrs ? (gfPrs[0] as Map).description : 'no pull request')

Closure catalogRun = { Map<String, String> files, List<Map> services, Map options = [:] ->
    SandboxHarness h = new SandboxHarness(srcDir, stubDir, fixturesDir)
    FakeScript sc = new FakeScript()
    sc.env.vars.putAll([WORKSPACE: '/ws', JOB_NAME: 'DevSecOps/CertScanner-nexusiq-pipeline', BUILD_NUMBER: '62'])
    List<String> builds = []
    def repository = null
    sc.shHandler = { Map a ->
        if (a.returnStdout) return '202609191405'
        if (String.valueOf(a.label ?: '').startsWith('GoldenFix: pre-check ')) {
            builds << String.valueOf(a.script).readLines().findAll { it.startsWith('cd ') || it.startsWith('npm install') }.join(' / ')
            if (options.fails) return (options.fails as Closure).call(repository.written) ? 1 : 0
        }
        return a.returnStatus ? 0 : null
    }
    Map out = [errors: [], results: [:], written: [:], commits: [], prs: [], comments: []]
    try {
        h.run {
            def model = h.type('com.bbh.remediation.model.GoldenFix')
            def state = h.type('com.bbh.core.PipelineState').newInstance()
            repository = h.type('com.bbh.fixture.InMemorySourceRepository').newInstance([files] as Object[])
            def publisher = h.type('com.bbh.fixture.RecordingPullRequestPublisher').newInstance()
            publisher.commentFailure = (options.commentFailure ?: '') as String
            List updaters = ['MavenPomUpdater', 'GradleUpdater', 'NpmPackageJsonUpdater', 'PipUpdater', 'PubUpdater'].collect {
                h.type("com.bbh.remediation.updater.${it}".toString()).newInstance()
            }
            def verifier = options.verify ? h.type('com.bbh.build.ManifestBuildVerifier').newInstance(sc) : null
            services.each { Map svc ->
                state.currentProjectName = svc.name
                state.cfg = [goldenFix: [enabled: true, verify: [enabled: options.verify as boolean]] + ((svc.goldenFix ?: [:]) as Map),
                             scm      : [bitbucket: [url: 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner', credentialsId: 'bb']]]
                List fixes = (svc.fixes as List).collect { List f -> model.create(f[0], f[1], f[2], f[3], f[4], 'next-no-violations', '', 8, true, 'APP', false, null, f.size() > 5 ? f[5] : []) }
                def source = h.type('com.bbh.fixture.StaticGoldenFixSource').newInstance([fixes] as Object[])
                source.failure = options.failure as Throwable
                h.type('com.bbh.remediation.GoldenFixService').newInstance(sc, state, source, repository, publisher, updaters, verifier)
                        .remediate([[application: 'APP', serverUrl: 'https://tools.bbh.com/IQ', scanId: 'a' * 32]])
            }
            out.results = state.projectsGoldenFix
            out.written = repository.written
            out.commits = repository.commits
            out.prs = publisher.created
            out.comments = publisher.comments
        }
    } catch (Throwable t) {
        out.errors << (SandboxHarness.rejectionOf(t) ?: t.toString())
    }
    out.builds = builds
    out.log = sc.log.findAll { it.contains('GOLDENFIX') || it.startsWith('GoldenFix') }
    return out
}
Closure gfOf = { Map r, String service -> ((r.results as Map)[service] ?: [:]) as Map }
Closure reasonsOf = { Map r, String service -> ((gfOf(r, service).unresolved ?: []) as List).collect { "${it.component}: ${it.reason}".toString() } }
Closure cdTo = { String sub -> "cd '/ws@tmp/goldenfix-GoldenFix-202609191405${sub ? '/' + sub : ''}' || exit 1".toString() }
String jackson = 'com.fasterxml.jackson.core'
String jacksonToml = '[versions]\njackson = "2.13.4"\n\n[libraries]\njackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }\n'

Map tomlRun = catalogRun([
        'settings.gradle'          : "rootProject.name = 'cert-scanner'\ninclude 'gui', 'api'\n",
        'gradle/libs.versions.toml': '''[versions]
jackson = "2.13.4" # shared
netty = { strictly = "4.1.86.Final" }
logback = { require = "1.4.5" }
guava = { strictly = "[30.0,32.0[", prefer = "31.0-jre" }
jackson_core = "2.13.4"
slf4j = { strictly = "[2.0,3.0[" }

[libraries]
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
netty-codec-http = { version = { ref = "netty" }, name = "netty-codec-http", group = "io.netty" }
postgresql = { name = "postgresql", version = "42.5.0", group = "org.postgresql" }
snakeyaml = { version = { strictly = "1.33" }, module = "org.yaml:snakeyaml" }
commons-text = "org.apache.commons:commons-text:1.9"
guava = { module = "com.google.guava:guava", version.ref = "guava" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }

[libraries.logback-classic]
module = "ch.qos.logback:logback-classic"
version.ref = "logback"

[libraries."commons-io"]
group = "commons-io"
name = "commons-io"
version = "2.11.0"

[plugins]
spring = { id = "org.springframework.boot", version = "3.1.0" }
''',
        'gui/build.gradle'         : 'dependencies {\n    implementation libs.jackson.databind\n    implementation "com.fasterxml.jackson.core:jackson-core:${libs.versions.jackson.core.get()}"\n}\n'
], [[name: 'gui', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5'], ['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final'],
                          ['maven', 'ch.qos.logback', 'logback-classic', '1.4.5', '1.4.14'], ['maven', 'com.google.guava', 'guava', '31.0-jre', '31.1-jre'],
                          ['maven', jackson, 'jackson-core', '2.13.4', '2.13.5'], ['maven', 'org.postgresql', 'postgresql', '42.5.0', '42.5.6'],
                          ['maven', 'org.yaml', 'snakeyaml', '1.33', '2.2'], ['maven', 'org.apache.commons', 'commons-text', '1.9', '1.10.0'],
                          ['maven', 'commons-io', 'commons-io', '2.11.0', '2.14.0'], ['maven', 'org.slf4j', 'slf4j-api', '2.0.1', '2.0.9']]]], [verify: true])
String toml = ((tomlRun.written as Map)['gradle/libs.versions.toml'] ?: '') as String
check('GoldenFix catalogs: every [versions] and [libraries] form of a Gradle TOML catalog is raised in place, in any key order and with rich versions',
        tomlRun.errors.isEmpty() && ['jackson = "2.13.5" # shared', 'netty = { strictly = "4.1.108.Final" }', 'logback = { require = "1.4.14" }',
                                     'guava = { strictly = "[30.0,32.0[", prefer = "31.1-jre" }', 'jackson_core = "2.13.5"',
                                     'postgresql = { name = "postgresql", version = "42.5.6", group = "org.postgresql" }',
                                     'snakeyaml = { version = { strictly = "2.2" }, module = "org.yaml:snakeyaml" }', '"org.apache.commons:commons-text:1.10.0"',
                                     'version = "2.14.0"', 'spring = { id = "org.springframework.boot", version = "3.1.0" }'].every { toml.contains(it) }
                && !(tomlRun.written as Map).containsKey('gui/build.gradle'),
        "${tomlRun.errors} ${toml}")
check('GoldenFix catalogs: a catalog alias written with - _ or . is one version key, and the change shows the alias of the catalog entry',
        ((gfOf(tomlRun, 'gui').changes ?: []) as List).any { it.property == 'jackson_core' && it.component == 'com.fasterxml.jackson.core:jackson-core' }
                && ((gfOf(tomlRun, 'gui').changes ?: []) as List).any { it.property == 'postgresql' && it.file == 'gradle/libs.versions.toml' },
        gfOf(tomlRun, 'gui').changes)
check('GoldenFix catalogs: a range in the catalog is left alone and the reason names the catalog',
        reasonsOf(tomlRun, 'gui') == ["org.slf4j:slf4j-api: managed by version catalog gradle/libs.versions.toml (slf4j): declared version '[2.0,3.0[' is dynamic or a range"],
        reasonsOf(tomlRun, 'gui'))
check('GoldenFix catalogs: the root catalog is verified in the root build, not in the gradle folder', tomlRun.builds == [cdTo('')], tomlRun.builds)

Map dslRun = catalogRun([
        'svc-a/settings.gradle' : '''dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('../gradle/shared.toml'))
        }
        create("deps") {
            version("jackson", "2.13.4")
            version('pg', '42.5.0')
            library("jackson-databind", "com.fasterxml.jackson.core", "jackson-databind").versionRef("jackson")
            library("commons-text", "org.apache.commons:commons-text:1.9")
            library("snakeyaml", "org.yaml", "snakeyaml").version("1.33")
        }
    }
}
include 'app'
''',
        'gradle/shared.toml'    : '[versions]\nnetty = "4.1.86.Final"\n\n[libraries]\nnetty-codec-http = { module = "io.netty:netty-codec-http", version.ref = "netty" }\n',
        'svc-a/app/build.gradle': 'dependencies {\n    implementation deps.jackson.databind\n    implementation "org.postgresql:postgresql:$deps.versions.pg"\n    implementation libs.netty.codec.http\n}\n'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5'], ['maven', 'org.postgresql', 'postgresql', '42.5.0', '42.5.6'],
                            ['maven', 'org.yaml', 'snakeyaml', '1.33', '2.2'], ['maven', 'org.apache.commons', 'commons-text', '1.9', '1.10.0'],
                            ['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final']]]], [verify: true])
String dslSettings = ((dslRun.written as Map)['svc-a/settings.gradle'] ?: '') as String
check('GoldenFix catalogs: the settings.gradle catalog DSL and a catalog imported with libs { from(files(..)) } under another file name are raised',
        dslRun.errors.isEmpty() && ['version("jackson", "2.13.5")', "version('pg', '42.5.6')", '"org.apache.commons:commons-text:1.10.0"',
                                    '"snakeyaml").version("2.2")', '.versionRef("jackson")'].every { dslSettings.contains(it) }
                && ((dslRun.written as Map)['gradle/shared.toml'] as String)?.contains('netty = "4.1.108.Final"') && !reasonsOf(dslRun, 'svc-a')
                && dslRun.builds == [cdTo('svc-a')],
        "${dslRun.errors} ${dslSettings} ${dslRun.written} ${reasonsOf(dslRun, 'svc-a')} ${dslRun.builds}")

Map separateRun = catalogRun([
        'svc-a/settings.gradle'    : "dependencyResolutionManagement { versionCatalogs { libs { from(files('../gradle/libs.versions.toml')) } } }\n",
        'svc-b/settings.gradle.kts': 'dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } } }\n',
        'gradle/libs.versions.toml': jacksonToml,
        'svc-a/build.gradle'       : 'dependencies { implementation libs.jackson.databind }\n',
        'svc-b/build.gradle.kts'   : 'dependencies { implementation(libs.jackson.databind) }\n'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]]], [verify: true])
check('GoldenFix catalogs: two separate builds that import one root catalog are both verified, and nothing runs at the root without a build',
        separateRun.errors.isEmpty() && ((separateRun.written as Map)['gradle/libs.versions.toml'] as String)?.contains('jackson = "2.13.5"')
                && separateRun.builds == [cdTo('svc-a'), cdTo('svc-b')],
        "${separateRun.errors} ${separateRun.builds}")

Map listedRun = catalogRun([
        'catalog/deps.toml'            : '[libraries]\njackson = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.13.4" }\n',
        'build/generated.versions.toml': '[versions]\nx = "1"\n',
        'svc-a/build.gradle'           : 'dependencies { }\n'
], [[name: 'svc-a', goldenFix: [versionCatalogs: ['catalog/deps.toml', 'missing/*.toml', '../outside.toml', 'build/generated.versions.toml']],
     fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]]], [verify: true])
check('GoldenFix catalogs: a listed catalog with another name is raised, a missing, excluded or outside path is a run note, and a catalog no build imports is not verified',
        listedRun.errors.isEmpty() && ((listedRun.written as Map)['catalog/deps.toml'] as String)?.contains('version = "2.13.5"')
                && gfOf(listedRun, 'svc-a').notes == ['version catalog missing/*.toml not found in the repository', 'version catalog ../outside.toml is outside the repository',
                                                      'version catalog build/generated.versions.toml not found in the repository']
                && !gfOf(listedRun, 'svc-a').unresolved && listedRun.builds.isEmpty()
                && (listedRun.log as List).contains('GoldenFix: no Gradle build found for catalog/deps.toml - not verified')
                && ((listedRun.prs as List)[0]?.description as String)?.contains('- version catalog ../outside.toml is outside the repository'),
        "${listedRun.errors} ${gfOf(listedRun, 'svc-a').notes} ${listedRun.builds} ${listedRun.log}")

Map scriptRun = catalogRun([
        'settings.gradle'           : "include 'svc-a'\n",
        'svc-a/build.gradle'        : 'apply from: "$rootDir/gradle/dependencies.gradle"\ndependencies {\n    implementation "com.fasterxml.jackson.core:jackson-databind:${versions.jackson}"\n' +
                                      '    implementation "org.yaml:snakeyaml:${snakeyamlVersion}"\n    implementation(\'io.netty:netty-codec-http\') { version { strictly \'4.1.86.Final\' } }\n}\n',
        'svc-a/sub/build.gradle.kts': 'dependencies {\n    implementation("org.apache.commons:commons-text") { version { require("1.9") } }\n}\n',
        'gradle/dependencies.gradle': "ext.versions = [jackson: '2.13.4', netty: '4.1.86.Final']\next {\n    snakeyamlVersion = '1.33'\n}\n"
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5'], ['maven', 'org.yaml', 'snakeyaml', '1.33', '2.2'],
                            ['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final'], ['maven', 'org.apache.commons', 'commons-text', '1.9', '1.10.0']]]], [verify: true])
String applied = ((scriptRun.written as Map)['gradle/dependencies.gradle'] ?: '') as String
check('GoldenFix catalogs: a script plugin found through apply from, its ext map, and rich versions of the Groovy and Kotlin DSL are raised; the script is verified in the root build',
        scriptRun.errors.isEmpty() && applied.contains("[jackson: '2.13.5', netty: '4.1.86.Final']") && applied.contains("snakeyamlVersion = '2.2'")
                && ((scriptRun.written as Map)['svc-a/build.gradle'] as String)?.contains("version { strictly '4.1.108.Final' }")
                && ((scriptRun.written as Map)['svc-a/sub/build.gradle.kts'] as String)?.contains('version { require("1.10.0") }')
                && scriptRun.builds == [cdTo('svc-a'), cdTo('svc-a/sub'), cdTo('')],
        "${scriptRun.errors} ${scriptRun.written} ${scriptRun.builds}")

Map platformRun = catalogRun([
        'settings.gradle'      : "include 'platform', 'svc-a', 'svc-b'\n",
        'platform/build.gradle': "plugins { id 'java-platform' }\ndependencies {\n    constraints {\n        api 'com.example:lib:2.0'\n        api 'com.fasterxml.jackson.core:jackson-databind:2.13.4'\n    }\n}\n",
        'svc-a/build.gradle'   : "dependencies { implementation platform(project(':platform')) }\n",
        'svc-b/build.gradle'   : "dependencies {\n    implementation 'com.example:lib:1.0'\n}\n"
], [[name: 'svc-b', fixes: [['maven', 'com.example', 'lib', '1.0', '1.9'], ['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]]], [verify: true])
check('GoldenFix catalogs: a java-platform module is raised and verified in its build root, and an inline declaration takes the catalog version with a note',
        platformRun.errors.isEmpty() && ((platformRun.written as Map)['platform/build.gradle'] as String)?.contains("api 'com.fasterxml.jackson.core:jackson-databind:2.13.5'")
                && ((platformRun.written as Map)['svc-b/build.gradle'] as String)?.contains("'com.example:lib:2.0'")
                && gfOf(platformRun, 'svc-b').notes == ['svc-b/build.gradle: com.example:lib declared outside the catalog platform/build.gradle']
                && platformRun.builds == [cdTo(''), cdTo('svc-b')],
        "${platformRun.errors} ${platformRun.written} ${gfOf(platformRun, 'svc-b').notes} ${platformRun.builds}")

Map alignedRun = catalogRun([
        'settings.gradle'          : "include 'svc-a', 'svc-b'\n",
        'gradle/libs.versions.toml': '[libraries]\nlib = "com.example:lib:2.0"\n',
        'svc-b/build.gradle'       : "dependencies {\n    implementation 'com.example:lib:1.0'\n}\n"
], [[name: 'svc-b', fixes: [['maven', 'com.example', 'lib', '1.0', '1.9']]]])
check('GoldenFix catalogs: catalog 2.0, inline 1.0 and Nexus IQ target 1.9 raise the inline declaration to 2.0 and name the catalog entry in the pull request',
        alignedRun.errors.isEmpty() && !(alignedRun.written as Map).containsKey('gradle/libs.versions.toml')
                && ((alignedRun.written as Map)['svc-b/build.gradle'] as String)?.contains("'com.example:lib:2.0'")
                && ((alignedRun.prs as List)[0]?.description as String)?.contains('- svc-b/build.gradle: com.example:lib declared outside the catalog gradle/libs.versions.toml (lib)'),
        "${alignedRun.errors} ${alignedRun.written} ${gfOf(alignedRun, 'svc-b').notes}")

Map buildSrcRun = catalogRun([
        'settings.gradle.kts'                 : 'include("svc-a")\n',
        'svc-a/build.gradle.kts'              : 'dependencies {\n    implementation("com.fasterxml.jackson.core:jackson-databind:${Versions.jackson}")\n}\n',
        'buildSrc/src/main/kotlin/Versions.kt': 'object Versions {\n    const val jackson = "2.13.4"\n}\n'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]]])
check('GoldenFix catalogs: a version held in buildSrc is not edited and the reason names the file',
        buildSrcRun.errors.isEmpty() && !buildSrcRun.written && reasonsOf(buildSrcRun, 'svc-a') ==
                ['com.fasterxml.jackson.core:jackson-databind: version jackson is defined in buildSrc/src/main/kotlin/Versions.kt: not updated automatically'],
        "${buildSrcRun.errors} ${reasonsOf(buildSrcRun, 'svc-a')}")

Map bomRun = catalogRun([
        'settings.gradle'          : 'dependencyResolutionManagement {\n    versionCatalogs {\n        create("company") {\n            from("com.bbh:platform-catalog:1.2")\n        }\n    }\n}\n',
        'gradle/libs.versions.toml': '[versions]\njackson = "2.13.4"\n\n[libraries]\njackson-bom = { module = "com.fasterxml.jackson:jackson-bom", version.ref = "jackson" }\n',
        'svc-a/build.gradle'       : 'dependencies {\n    implementation platform(libs.jackson.bom)\n    implementation enforcedPlatform("io.netty:netty-bom:4.1.86.Final")\n}\n',
        'svc-b/pom.xml'            : '<project><dependencyManagement><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId>' +
                                     '<version>3.1.0</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></project>'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5'], ['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final'],
                            ['maven', 'org.springframework.boot', 'spring-boot-starter-web', '3.1.0', '3.1.5'], ['maven', 'org.postgresql', 'postgresql', '42.5.0', '42.5.6']]]])
check('GoldenFix catalogs: a component managed by an imported BOM or platform, or by a catalog artifact outside the repository, says so',
        bomRun.errors.isEmpty() && reasonsOf(bomRun, 'svc-a') == [
                'com.fasterxml.jackson.core:jackson-databind: managed by BOM com.fasterxml.jackson:jackson-bom:2.13.4 imported in gradle/libs.versions.toml: raise the BOM or add an override to the catalog',
                'io.netty:netty-codec-http: managed by BOM io.netty:netty-bom:4.1.86.Final imported in svc-a/build.gradle: raise the BOM or add an override to the catalog',
                'org.springframework.boot:spring-boot-starter-web: managed by BOM org.springframework.boot:spring-boot-dependencies:3.1.0 imported in svc-b/pom.xml: raise the BOM or add an override to the catalog',
                'org.postgresql:postgresql: managed by the version catalog artifact com.bbh:platform-catalog:1.2 outside this repository'],
        "${bomRun.errors} ${reasonsOf(bomRun, 'svc-a')}")

Map<String, String> sharedFiles = [
        'settings.gradle'          : "include 'svc-a', 'svc-b', 'svc-c'\n",
        'gradle/libs.versions.toml': jacksonToml + 'postgresql = "org.postgresql:postgresql:42.5.0"\n',
        'svc-a/build.gradle'       : 'dependencies { implementation libs.jackson.databind }\n',
        'svc-b/build.gradle'       : 'dependencies { implementation libs.jackson.databind; implementation libs.postgresql }\n',
        'svc-c/build.gradle'       : 'dependencies { implementation libs.jackson.databind }\n'
]
List<Map> sharedServices = [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]],
                            [name: 'svc-b', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.9'], ['maven', 'org.postgresql', 'postgresql', '42.5.0', '42.5.6']]],
                            [name: 'svc-c', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.7']]]]
Map sharedRun = catalogRun(sharedFiles, sharedServices)
String sharedToml = ((sharedRun.written as Map)['gradle/libs.versions.toml'] ?: '') as String
String sharedComment = (((sharedRun.comments as List)[0] as Map)?.text ?: '') as String
check('GoldenFix catalogs: services of one run that share a catalog raise it once to the highest version, in one pull request that the later service comments on',
        sharedRun.errors.isEmpty() && gfOf(sharedRun, 'svc-a').status == 'PR_CREATED' && gfOf(sharedRun, 'svc-b').status == 'PR_UPDATED'
                && sharedToml.contains('jackson = "2.13.9"') && sharedToml.contains('org.postgresql:postgresql:42.5.6')
                && (sharedRun.prs as List).size() == 1 && (sharedRun.comments as List).size() == 1
                && sharedComment.contains('### GoldenFix for svc-b') && sharedComment.contains('| 2.13.5 | **2.13.9** |') && sharedComment.contains('| 42.5.0 | **42.5.6** |')
                && (sharedRun.commits as List).collect { it.files } == [['gradle/libs.versions.toml'], ['gradle/libs.versions.toml']],
        "${sharedRun.errors} ${sharedRun.results} ${sharedToml} ${sharedComment} ${(sharedRun.commits as List).collect { it.files }}")
check('GoldenFix catalogs: a service whose versions the shared pull request already carries links it without a commit or a comment',
        gfOf(sharedRun, 'svc-c').status == 'PR_UPDATED' && gfOf(sharedRun, 'svc-c').prUrl == 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner/pull-requests/318'
                && gfOf(sharedRun, 'svc-c').message == 'Already raised to com.fasterxml.jackson.core:jackson-databind 2.13.9 in the shared pull request https://bitbucket.bbh.com/projects/TA/repos/cert-scanner/pull-requests/318'
                && !gfOf(sharedRun, 'svc-c').unresolved && (sharedRun.commits as List).size() == 2,
        gfOf(sharedRun, 'svc-c'))
Map failedComment = catalogRun(sharedFiles, sharedServices.take(2), [commentFailure: 'HTTP 403 Forbidden'])
check('GoldenFix catalogs: a pull request comment that fails is logged and the service stays PR_UPDATED',
        failedComment.errors.isEmpty() && gfOf(failedComment, 'svc-b').status == 'PR_UPDATED'
                && (failedComment.log as List).contains('[GOLDENFIX] The pull request comment could not be added: HTTP 403 Forbidden'),
        "${failedComment.errors} ${gfOf(failedComment, 'svc-b').status} ${failedComment.log}")

Map mavenRun = catalogRun([
        'services/pom.xml'       : '<project><groupId>com.bbh</groupId><artifactId>services</artifactId><packaging>pom</packaging>' +
                                   '<modules><module>parent</module><module>bom</module><module>svc-a</module><module>svc-b</module></modules></project>',
        'services/parent/pom.xml': '<project><groupId>com.bbh</groupId><artifactId>parent</artifactId><packaging>pom</packaging><properties><jackson.version>2.13.4</jackson.version></properties>' +
                                   '<dependencyManagement><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId>' +
                                   '<version>${jackson.version}</version></dependency></dependencies></dependencyManagement></project>',
        'services/bom/pom.xml'   : '<project><groupId>com.bbh</groupId><artifactId>platform-bom</artifactId><packaging>pom</packaging><dependencyManagement><dependencies><dependency>' +
                                   '<groupId>io.netty</groupId><artifactId>netty-codec-http</artifactId><version>4.1.86.Final</version></dependency></dependencies></dependencyManagement></project>',
        'services/svc-a/pom.xml' : '<project><parent><groupId>com.bbh</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../parent/pom.xml</relativePath></parent>' +
                                   '<artifactId>svc-a</artifactId><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency></dependencies></project>',
        'services/svc-b/pom.xml' : '<project><groupId>com.bbh</groupId><artifactId>svc-b</artifactId><dependencyManagement><dependencies><dependency><groupId>com.bbh</groupId>' +
                                   '<artifactId>platform-bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>' +
                                   '<dependencies><dependency><groupId>io.netty</groupId><artifactId>netty-codec-http</artifactId></dependency></dependencies></project>'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5'], ['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final']]]], [verify: true])
check('GoldenFix catalogs: a Maven parent and an imported BOM of the repository are raised and verified in the aggregator that builds them with their consumers',
        mavenRun.errors.isEmpty() && ((mavenRun.written as Map)['services/parent/pom.xml'] as String)?.contains('<jackson.version>2.13.5</jackson.version>')
                && ((mavenRun.written as Map)['services/bom/pom.xml'] as String)?.contains('<version>4.1.108.Final</version>')
                && !reasonsOf(mavenRun, 'svc-a') && mavenRun.builds == [cdTo('services')],
        "${mavenRun.errors} ${reasonsOf(mavenRun, 'svc-a')} ${mavenRun.builds}")

String bigToml = '[versions]\n' + (1..300).collect { "v${it} = \"1.0.${it}\"\n" }.join('') + 'jackson = "2.13.4"\n\n[libraries]\n' +
        (1..300).collect { "lib-${it} = { module = \"com.example:lib-${it}\", version.ref = \"v${it}\" }\n" }.join('') +
        'jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }\n\n' +
        '[libraries.netty-codec-http]\nmodule = "io.netty:netty-codec-http"\nversion = "4.1.86.Final"\n' + (1..300).collect { "# platform note ${it}\n" }.join('')
Map bigRun = [:]
Thread cpsThread = new Thread(null, {
    bigRun = catalogRun(['settings.gradle'          : "include 'gui'\n", 'gradle/libs.versions.toml': bigToml,
                         'gui/build.gradle'         : 'dependencies {\n    implementation libs.jackson.databind\n    implementation libs.netty.codec.http\n}\n'],
            [[name: 'gui', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5'], ['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final']]]])
} as Runnable, 'cps-vm', 1024L * 1024L)
cpsThread.start()
cpsThread.join()
String bigWritten = ((bigRun.written as Map)?.get('gradle/libs.versions.toml') ?: '') as String
check('GoldenFix catalogs: a catalog of 300 libraries is raised on a 1 MB thread stack, the size of a Jenkins CPS thread',
        bigRun.errors == [] && gfOf(bigRun, 'gui').status == 'PR_CREATED' && bigWritten == bigToml.replace('jackson = "2.13.4"', 'jackson = "2.13.5"').replace('version = "4.1.86.Final"', 'version = "4.1.108.Final"'),
        "${bigRun.errors} ${gfOf(bigRun, 'gui').status} ${gfOf(bigRun, 'gui').message}")
Map errorRun = catalogRun(sharedFiles, [sharedServices[0]], [failure: new StackOverflowError()])
check('GoldenFix: an Error that escapes the remediation is recorded as ERROR with its class name',
        errorRun.errors.isEmpty() && gfOf(errorRun, 'svc-a').status == 'ERROR' && gfOf(errorRun, 'svc-a').message == 'StackOverflowError'
                && (errorRun.log as List).contains('[GOLDENFIX] Remediation failed: StackOverflowError'),
        "${errorRun.errors} ${gfOf(errorRun, 'svc-a')} ${errorRun.log}")

Map literalRun = catalogRun([
        'settings.gradle'          : "include 'gui'\n",
        'gradle/libs.versions.toml': "[versions] # pinned by the platform team\nyaml = '1.33'\n\n[libraries] # third-party\ncommons = 'org.apache.commons:commons-text:1.9'\n" +
                                     'jackson = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.13.4" }\n' +
                                     "pg = { module = 'org.postgresql:postgresql', version = '42.5.0' }\nsnakeyaml = { module = 'org.yaml:snakeyaml', version.ref = 'yaml' }\n\n" +
                                     '[libraries.netty] # json\nmodule = "io.netty:netty-codec-http"\nversion = "4.1.86.Final"\n',
        'gui/build.gradle'         : 'dependencies { implementation libs.commons }\n'
], [[name: 'gui', fixes: [['maven', 'org.apache.commons', 'commons-text', '1.9', '1.10.0'], ['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5'],
                          ['maven', 'org.postgresql', 'postgresql', '42.5.0', '42.5.6'], ['maven', 'org.yaml', 'snakeyaml', '1.33', '2.2'],
                          ['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.1.108.Final']]]])
String literalToml = ((literalRun.written as Map)['gradle/libs.versions.toml'] ?: '') as String
check('GoldenFix catalogs: section headers with a trailing comment and TOML literal strings in single quotes are raised as on the main branch',
        literalRun.errors.isEmpty() && ["yaml = '2.2'", "commons = 'org.apache.commons:commons-text:1.10.0'", 'version = "2.13.5" }', "version = '42.5.6' }",
                                        '[libraries.netty] # json\nmodule = "io.netty:netty-codec-http"\nversion = "4.1.108.Final"'].every { literalToml.contains(it) }
                && !gfOf(literalRun, 'gui').unresolved,
        "${literalRun.errors} ${literalToml} ${reasonsOf(literalRun, 'gui')}")

Map rangeRun = catalogRun([
        'settings.gradle'          : "include 'gui'\n",
        'gradle/libs.versions.toml': '[versions]\nnetty = { strictly = "[4.1,4.2[", prefer = "4.1.86.Final" }\n\n[libraries]\nnetty-codec-http = { module = "io.netty:netty-codec-http", version.ref = "netty" }\n' +
                                     'guava = { module = "com.google.guava:guava", version = { strictly = "[30.0,31.0[", prefer = "30.1-jre" } }\n',
        'gui/build.gradle'         : "dependencies {\n    implementation libs.netty.codec.http\n    implementation('org.yaml:snakeyaml') { version { strictly '(1.30,2.0]'; prefer '1.33' } }\n}\n"
], [[name: 'gui', fixes: [['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.2.1.Final'], ['maven', 'com.google.guava', 'guava', '30.1-jre', '32.0.0-jre'],
                          ['maven', 'org.yaml', 'snakeyaml', '1.33', '2.2']]]])
check('GoldenFix catalogs: a strictly range that excludes the target leaves the rich version alone and the reason names the range',
        rangeRun.errors.isEmpty() && !rangeRun.written && gfOf(rangeRun, 'gui').status == 'NO_MANIFEST_CHANGES' && reasonsOf(rangeRun, 'gui') == [
                "io.netty:netty-codec-http: managed by version catalog gradle/libs.versions.toml (netty): strictly range '[4.1,4.2[' excludes 4.2.1.Final",
                "com.google.guava:guava: managed by version catalog gradle/libs.versions.toml (guava): strictly range '[30.0,31.0[' excludes 32.0.0-jre",
                "org.yaml:snakeyaml: gui/build.gradle: strictly range '(1.30,2.0]' excludes 2.2"],
        "${rangeRun.errors} ${rangeRun.written} ${reasonsOf(rangeRun, 'gui')}")
Map boundRun = catalogRun([
        'settings.gradle'          : "include 'gui', 'api'\n",
        'gradle/libs.versions.toml': '[versions]\nguava = { strictly = "[31.0,32.0[", prefer = "31.1-jre" }\n\n[libraries]\nguava = { module = "com.google.guava:guava", version.ref = "guava" }\n',
        'gui/build.gradle'         : 'dependencies {\n    implementation libs.guava\n}\n',
        'api/build.gradle.kts'     : 'dependencies {\n    implementation("io.netty:netty-handler") { version { strictly("[4.1,4.2]"); prefer("4.1.86.Final") } }\n}\n'
], [[name: 'gui', fixes: [['maven', 'com.google.guava', 'guava', '31.1-jre', '32.0.0-jre'], ['maven', 'io.netty', 'netty-handler', '4.1.86.Final', '4.2.0.Final']]]])
check('GoldenFix catalogs: a range bound is compared as Gradle orders versions, so 32.0.0-jre lies above 32.0 and 4.2.0.Final above 4.2',
        boundRun.errors.isEmpty() && !boundRun.written && gfOf(boundRun, 'gui').status == 'NO_MANIFEST_CHANGES' && reasonsOf(boundRun, 'gui') == [
                "com.google.guava:guava: managed by version catalog gradle/libs.versions.toml (guava): strictly range '[31.0,32.0[' excludes 32.0.0-jre",
                "io.netty:netty-handler: api/build.gradle.kts: strictly range '[4.1,4.2]' excludes 4.2.0.Final"],
        "${boundRun.errors} ${boundRun.written} ${reasonsOf(boundRun, 'gui')}")
Map requireRun = catalogRun([
        'settings.gradle'          : "include 'gui'\n",
        'gradle/libs.versions.toml': '[versions]\nnetty = { require = "[4.1,4.2[", prefer = "4.1.86.Final" }\nlogback = { require = "[1.4,1.5[", strictly = "[1.4,2.0[", prefer = "1.4.5" }\n\n' +
                                     '[libraries]\nnetty-codec-http = { module = "io.netty:netty-codec-http", version.ref = "netty" }\nlogback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }\n',
        'gui/build.gradle'         : "dependencies {\n    implementation libs.netty.codec.http\n    implementation libs.logback.classic\n" +
                                     "    implementation('org.yaml:snakeyaml') { version { require '[1.30,2.0['; prefer '1.33' } }\n}\n"
], [[name: 'gui', fixes: [['maven', 'io.netty', 'netty-codec-http', '4.1.86.Final', '4.2.1.Final'], ['maven', 'ch.qos.logback', 'logback-classic', '1.4.5', '1.5.6'],
                          ['maven', 'org.yaml', 'snakeyaml', '1.33', '2.2']]]])
String requireToml = ((requireRun.written as Map)['gradle/libs.versions.toml'] ?: '') as String
check('GoldenFix catalogs: without strictly, a require range that excludes the target leaves the rich version alone; strictly wins when both are set',
        requireRun.errors.isEmpty() && requireToml.contains('logback = { require = "[1.4,1.5[", strictly = "[1.4,2.0[", prefer = "1.5.6" }')
                && requireToml.contains('netty = { require = "[4.1,4.2[", prefer = "4.1.86.Final" }') && !(requireRun.written as Map).containsKey('gui/build.gradle')
                && reasonsOf(requireRun, 'gui') == [
                "io.netty:netty-codec-http: managed by version catalog gradle/libs.versions.toml (netty): require range '[4.1,4.2[' excludes 4.2.1.Final",
                "org.yaml:snakeyaml: gui/build.gradle: require range '[1.30,2.0[' excludes 2.2"],
        "${requireRun.errors} ${requireToml} ${reasonsOf(requireRun, 'gui')}")

Map pomRangeRun = catalogRun([
        'pom.xml'      : '<project><groupId>com.bbh</groupId><artifactId>root</artifactId><packaging>pom</packaging><modules><module>svc-a</module></modules>' +
                         '<dependencyManagement><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId>' +
                         '<version>[2.13,2.14)</version></dependency></dependencies></dependencyManagement></project>',
        'svc-a/pom.xml': '<project><parent><groupId>com.bbh</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>svc-a</artifactId><dependencies>' +
                         '<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency></dependencies></project>'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]]])
check('GoldenFix catalogs: a range in a shared pom.xml gets the reason in the form of the Gradle catalogs',
        pomRangeRun.errors.isEmpty() && reasonsOf(pomRangeRun, 'svc-a') == ["com.fasterxml.jackson.core:jackson-databind: managed by version catalog pom.xml: declared version '[2.13,2.14)' " +
                'is dynamic or a range; svc-a/pom.xml: version is not declared here (managed by a parent POM or BOM)'],
        "${pomRangeRun.errors} ${reasonsOf(pomRangeRun, 'svc-a')}")

Map listedSpellings = catalogRun([
        'catalog/deps.toml' : '[libraries]\njackson = { module = "com.fasterxml.jackson.core:jackson-databind", version = "2.13.4" }\n',
        'svc-a/build.gradle': 'dependencies { }\n'
], [[name: 'svc-a', goldenFix: [versionCatalogs: ['./catalog/deps.toml', 'catalog//deps.toml/', 'catalog/./deps.toml']],
     fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]]])
check('GoldenFix catalogs: a listed catalog written with ./, // or a trailing / is the same file as its plain path',
        listedSpellings.errors.isEmpty() && ((listedSpellings.written as Map)['catalog/deps.toml'] as String)?.contains('version = "2.13.5"')
                && !gfOf(listedSpellings, 'svc-a').notes && gfOf(listedSpellings, 'svc-a').status == 'PR_CREATED',
        "${listedSpellings.errors} ${gfOf(listedSpellings, 'svc-a').notes} ${listedSpellings.written}")

Map importForms = catalogRun([
        'platform/deps.versions.toml': jacksonToml,
        'svc-a/settings.gradle'      : "dependencyResolutionManagement {\n    versionCatalogs {\n        create('deps') { from files('../platform/deps.versions.toml') }\n    }\n}\n",
        'svc-b/settings.gradle.kts'  : 'dependencyResolutionManagement {\n    versionCatalogs {\n        create("deps") { from(files("$rootDir/../platform/deps.versions.toml")) }\n    }\n}\n',
        'svc-a/build.gradle'         : 'dependencies { implementation deps.jackson.databind }\n',
        'svc-b/build.gradle.kts'     : 'dependencies { implementation(deps.jackson.databind) }\n'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5']]]], [verify: true])
check('GoldenFix catalogs: a settings import written as from files(..) or with a $rootDir path is verified in the build that imports it',
        importForms.errors.isEmpty() && ((importForms.written as Map)['platform/deps.versions.toml'] as String)?.contains('jackson = "2.13.5"')
                && (importForms.written as Map).keySet() == ['platform/deps.versions.toml'] as Set && importForms.builds == [cdTo('svc-a'), cdTo('svc-b')],
        "${importForms.errors} ${importForms.written} ${importForms.builds}")

Map<String, String> parentPoms = [
        'pom.xml'      : '<project><groupId>com.bbh</groupId><artifactId>root</artifactId><packaging>pom</packaging><modules><module>svc-a</module></modules>' +
                         '<properties><jackson.version>2.17.0</jackson.version></properties><dependencyManagement><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId>' +
                         '<artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency></dependencies></dependencyManagement></project>',
        'svc-a/pom.xml': '<project><parent><groupId>com.bbh</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>svc-a</artifactId>' +
                         '<properties><jackson.version>2.13.4</jackson.version></properties><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId>' +
                         '<artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency></dependencies></project>'
]
Map chainedRun = catalogRun(parentPoms, [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.15.4']]]])
check('GoldenFix catalogs: a property raised in both rounds is one row from the declared version to the catalog version',
        chainedRun.errors.isEmpty() && (gfOf(chainedRun, 'svc-a').changes as List).collect { "${it.file} ${it.property} ${it.from} -> ${it.to}".toString() } ==
                ['svc-a/pom.xml jackson.version 2.13.4 -> 2.17.0'] && ((chainedRun.written as Map)['svc-a/pom.xml'] as String)?.contains('<jackson.version>2.17.0</jackson.version>'),
        "${chainedRun.errors} ${gfOf(chainedRun, 'svc-a').changes}")
Map alignedRetry = catalogRun([
        'pom.xml'      : '<project><groupId>com.bbh</groupId><artifactId>root</artifactId><packaging>pom</packaging><modules><module>svc-a</module></modules>' +
                         '<dependencyManagement><dependencies><dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId>' +
                         '<version>2.17.0</version></dependency></dependencies></dependencyManagement></project>',
        'svc-a/pom.xml': '<project><parent><groupId>com.bbh</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>svc-a</artifactId><dependencies>' +
                         '<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.13.4</version></dependency></dependencies></project>'
], [[name: 'svc-a', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.13.5', ['2.13.4.2']]]]], [verify: true, fails: { Map w -> (w['svc-a/pom.xml'] ?: '').contains('2.17.0') }])
check('GoldenFix pre-check: when the build fails with the catalog version, it is tried again with the Nexus IQ version before a lower one, and the log names the versions built',
        alignedRetry.errors.isEmpty() && gfOf(alignedRetry, 'svc-a').status == 'PR_CREATED'
                && ((alignedRetry.written as Map)['svc-a/pom.xml'] as String)?.contains('<version>2.13.5</version>')
                && gfOf(alignedRetry, 'svc-a').notes == ['svc-a/pom.xml: com.fasterxml.jackson.core:jackson-databind declared outside the catalog pom.xml']
                && (alignedRetry.log as List).findAll { it.contains('Pre-check build') } == [
                '[GOLDENFIX] Pre-check build 1 of 3 for com.fasterxml.jackson.core:jackson-databind 2.13.4 -> 2.17.0',
                '[GOLDENFIX] Pre-check build failed - retrying with the Nexus IQ versions instead of the catalog versions',
                '[GOLDENFIX] Pre-check build 2 of 3 for com.fasterxml.jackson.core:jackson-databind 2.13.4 -> 2.13.5',
                '[GOLDENFIX] Pre-check build passed (maven) with com.fasterxml.jackson.core:jackson-databind 2.13.4 -> 2.13.5'],
        "${alignedRetry.errors} ${gfOf(alignedRetry, 'svc-a').status} ${gfOf(alignedRetry, 'svc-a').message} ${alignedRetry.log}")

Map unrelatedRun = catalogRun([
        'settings.gradle'   : "include 'svc-a', 'svc-b', 'svc-c'\n",
        'svc-a/build.gradle': "dependencies { implementation 'org.apache.commons:commons-text:1.9' }\n",
        'svc-b/build.gradle': "dependencies { implementation 'com.fasterxml.jackson.core:jackson-databind' }\n",
        'svc-c/build.gradle': "dependencies { implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0' }\n"
], [[name: 'svc-a', fixes: [['maven', 'org.apache.commons', 'commons-text', '1.9', '1.10.0']]], [name: 'svc-b', fixes: [['maven', jackson, 'jackson-databind', '2.13.4', '2.15.4']]]])
check('GoldenFix catalogs: a newer declaration that the shared pull request did not change does not count as already raised',
        unrelatedRun.errors.isEmpty() && gfOf(unrelatedRun, 'svc-a').status == 'PR_CREATED' && gfOf(unrelatedRun, 'svc-b').status == 'NO_MANIFEST_CHANGES'
                && reasonsOf(unrelatedRun, 'svc-b') == ['com.fasterxml.jackson.core:jackson-databind: not declared in any supported manifest (BOM/parent managed, or already upgraded)'],
        "${unrelatedRun.errors} ${gfOf(unrelatedRun, 'svc-b')}")

Map<String, String> npmFiles = [
        'web/package.json'     : '{\n  "dependencies": {\n    "lodash": "^4.17.15"\n  },\n  "overrides": {\n    "lodash": "4.17.15",\n    "foo": {\n      "lodash": "4.17.10"\n    }\n  },\n' +
                                 '  "resolutions": {\n    "lodash": "4.17.15"\n  }\n}\n',
        'web/package-lock.json': '{}\n'
]
Map npmRun = catalogRun(npmFiles, [[name: 'web', fixes: [['npm', '', 'lodash', '4.17.15', '4.17.21']]]], [verify: true])
String npmDescription = ((npmRun.prs as List)[0]?.description ?: '') as String
check('GoldenFix npm: with the pre-check the sibling package-lock.json is refreshed first, committed and named in the pull request',
        npmRun.errors.isEmpty() && npmRun.builds == ["${cdTo('web')} / npm install --package-lock-only --ignore-scripts || exit \$?".toString()]
                && (npmRun.commits as List)[0]?.files == ['web/package.json', 'web/package-lock.json']
                && npmDescription.contains('- web/package-lock.json refreshed') && npmDescription.contains('> Lock files (yarn.lock, poetry.lock'),
        "${npmRun.errors} ${npmRun.builds} ${(npmRun.commits as List)[0]?.files} ${npmDescription}")
check('GoldenFix npm: overrides and resolutions that pin the package are raised, a nested override is left alone',
        ((npmRun.written as Map)['web/package.json'] as String) == npmFiles['web/package.json'].replace('"^4.17.15"', '"^4.17.21"').replace('"lodash": "4.17.15"', '"lodash": "4.17.21"'),
        (npmRun.written as Map)['web/package.json'])
Map npmUnverified = catalogRun(npmFiles, [[name: 'web', fixes: [['npm', '', 'lodash', '4.17.15', '4.17.21']]]])
check('GoldenFix npm: without the pre-check the lock file is not touched and the pull request says so',
        npmUnverified.errors.isEmpty() && (npmUnverified.commits as List)[0]?.files == ['web/package.json']
                && ((npmUnverified.prs as List)[0]?.description as String)?.contains('> Lock files (package-lock.json, yarn.lock'),
        "${npmUnverified.errors} ${(npmUnverified.commits as List)[0]?.files}")
String bigPackage = '{\n  "dependencies": {\n    "lodash": "^4.17.15"\n  },\n  "resolutions": {\n' + (1..300).collect { "    \"@bbh/package-${it}\": \"1.0.${it}\",\n" }.join('') +
        '    "lodash": "4.17.15"\n  }\n}\n'
Map bigNpm = [:]
Thread npmThread = new Thread(null, {
    bigNpm = catalogRun(['web/package.json': bigPackage], [[name: 'web', fixes: [['npm', '', 'lodash', '4.17.15', '4.17.21']]]])
} as Runnable, 'cps-vm', 1024L * 1024L)
npmThread.start()
npmThread.join()
check('GoldenFix npm: a resolutions block of 300 entries is raised on a 1 MB thread stack, the size of a Jenkins CPS thread',
        bigNpm.errors == [] && gfOf(bigNpm, 'web').status == 'PR_CREATED' && (bigNpm.written as Map)['web/package.json'] == bigPackage.replace('4.17.15', '4.17.21'),
        "${bigNpm.errors} ${gfOf(bigNpm, 'web').status} ${gfOf(bigNpm, 'web').message}")

List planned = verifierHarness.run {
    verifierHarness.type('com.bbh.build.ManifestBuildVerifier').newInstance(new FakeScript()).plan(
            ['gradle/libs.versions.toml', 'settings.gradle', 'gui/build.gradle', 'gradle.properties', 'catalog/deps.toml', 'parent/pom.xml'], [:],
            ['gradle/libs.versions.toml': ['svc-a', 'svc-b'], 'catalog/deps.toml': [], 'parent/pom.xml': ['']])
} as List
check('GoldenFix pre-check: a shared catalog is verified once in each build that uses it, build files keep their own folder',
        planned.collect { "${it.name}@${it.subDir}".toString() } == ['gradle@svc-a', 'gradle@svc-b', 'gradle@', 'gradle@gui', 'maven@'],
        planned.collect { "${it.name}@${it.subDir}".toString() })

Closure pushFailure = { String output ->
    FakeScript pushScript = new FakeScript()
    pushScript.env.vars.putAll([WORKSPACE: '/ws'])
    List<String> scripts = []
    pushScript.shHandler = { Map a ->
        scripts << String.valueOf(a.script)
        String.valueOf(a.label) == 'GoldenFix: git push' ? 1 : (a.returnStatus ? 0 : output)
    }
    String message = ''
    try {
        verifierHarness.run {
            verifierHarness.type('com.bbh.scm.GitSourceRepository').newInstance(pushScript).push('/ws@tmp/goldenfix/GoldenFix-202609191405', 'GoldenFix-202609191405',
                    [url: 'https://bitbucket.bbh.com/scm/ta/cert-scanner.git', credentialsId: 'bb'])
        }
    } catch (Throwable t) {
        message = t.message ?: t.toString()
    }
    return [message: message, scripts: scripts]
}
Map rejected = pushFailure.call('To https://bitbucket.bbh.com/scm/ta/cert-scanner.git\n ! [rejected]        HEAD -> GoldenFix-202609191405 (fetch first)\n' +
        "error: failed to push some refs to 'https://bitbucket.bbh.com/scm/ta/cert-scanner.git'\n")
check('GoldenFix: a push rejected because the branch exists names the cause and the fix instead of the exit code',
        rejected.message == '[GOLDENFIX] The branch GoldenFix-202609191405 already exists in https://bitbucket.bbh.com/scm/ta/cert-scanner.git - another GoldenFix job ' +
                'of this repository ran in the same minute; run one GoldenFix job for all services of a repository (pipelineKeys)'
                && (rejected.scripts as List)[0].contains("push 'https://bitbucket.bbh.com/scm/ta/cert-scanner.git' 'HEAD:refs/heads/GoldenFix-202609191405' 2>&1 | tee '/ws@tmp/goldenfix/GoldenFix-202609191405.push.log'")
                && (rejected.scripts as List)[1] == "cat '/ws@tmp/goldenfix/GoldenFix-202609191405.push.log' 2>/dev/null || true; rm -f '/ws@tmp/goldenfix/GoldenFix-202609191405.push.log'",
        rejected)
Map declined = pushFailure.call('remote: ERROR: the commit message needs a JIRA key\nTo https://bitbucket.bbh.com/scm/ta/cert-scanner.git\n' +
        ' ! [remote rejected] HEAD -> GoldenFix-202609191405 (pre-receive hook declined)\n')
check('GoldenFix: a push that a hook or a branch permission declines says so with the answer of the remote, not that the branch exists',
        declined.message == '[GOLDENFIX] git push of GoldenFix-202609191405 to https://bitbucket.bbh.com/scm/ta/cert-scanner.git failed (exit code 1): ' +
                'remote: ERROR: the commit message needs a JIRA key ! [remote rejected] HEAD -> GoldenFix-202609191405 (pre-receive hook declined)',
        declined.message)

List pubCandidates = parserHarness.run {
    parserHarness.type('com.bbh.scanner.NexusIqGoldenFixSource').selectCandidates([components: [
            [componentIdentifier: [format: 'pub', coordinates: [packageId: 'http', version: '0.13.4']], violations: [[policyThreatLevel: 7]]],
            [componentIdentifier: [format: 'pub', coordinates: [name: 'intl', version: '0.17.0']], violations: [[policyThreatLevel: 7]]]]], 2, false, ['pub'])
} as List
check('GoldenFix: pub components of an assumed Nexus IQ payload are named by packageId or name',
        pubCandidates.collect { "${it.name}@${it.version}".toString() } == ['http@0.13.4', 'intl@0.17.0'], pubCandidates)
List lockPatterns = parserHarness.run {
    def state = parserHarness.type('com.bbh.core.PipelineState').newInstance()
    def nexusIq = parserHarness.type('com.bbh.scanner.NexusIqService').newInstance(new FakeScript(), state, null)
    [[['**/*.jar'], '.'], [['**/*.jar'], './'], [['**/*.jar'], './frontend'], [['**/*.jar'], 'frontend/'], [['**/*.jar'], 'web//frontend'], ['**/*.jar', null]].collect { List c ->
        state.cfg = [build: [node: [enabled: true, dir: c[1]]]]
        nexusIq.scanPatternsOf([scanPatterns: c[0]])
    }
} as List
check('Nexus IQ, Node.js build: the lock pattern names the folder without ./ or a trailing /, and a single scan pattern also gets it',
        lockPatterns == [['**/*.jar', 'package-lock.json'], ['**/*.jar', 'package-lock.json'], ['**/*.jar', 'frontend/package-lock.json'],
                         ['**/*.jar', 'frontend/package-lock.json'], ['**/*.jar', 'web/frontend/package-lock.json'], ['**/*.jar', 'package-lock.json']],
        lockPatterns)
Map cardTexts = parserHarness.run {
    FakeScript sc = new FakeScript()
    def state = parserHarness.type('com.bbh.core.PipelineState').newInstance()
    def os = parserHarness.type('com.bbh.core.OsHelper').newInstance(sc)
    def report = parserHarness.type('com.bbh.report.HtmlReportService').newInstance(sc, state, os, parserHarness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os), 'nexusiq')
    ['NO_MANIFEST_CHANGES', 'BUILD_FAILED'].collectEntries { [(it): report.goldenFixPlainText([status: it]).explanation] }
} as Map
check('GoldenFix card: NO_MANIFEST_CHANGES names more places than another library, and BUILD_FAILED claims only the versions the pipeline tried',
        (cardTexts.NO_MANIFEST_CHANGES as String).contains('or a BOM that this project depends on, or they are set in a way the pipeline does not edit, such as a version range')
                && (cardTexts.BUILD_FAILED as String).contains('failed again with the other versions the pipeline tried'),
        cardTexts)
SandboxHarness nodeHarness = new SandboxHarness(srcDir, stubDir, fixturesDir)
Closure nodeRunnerFor = { FakeScript sc ->
    def os = nodeHarness.type('com.bbh.core.OsHelper').newInstance(sc)
    os.detect()
    nodeHarness.type('com.bbh.build.NodeBuildRunner').newInstance(sc, os)
}
Closure nodeEnvOf = { FakeScript sc, Map a ->
    "${String.valueOf(a.label).replace('GoldenFix: pre-check ', '')} ${sc.env.vars['PATH+NODEJS'] ?: 'agent PATH'} ${sc.env.vars['NPM_CONFIG_USERCONFIG'] ?: 'no npmrc'}".toString()
}
List<String> nodeErrors = []
FakeScript windowsNode = new FakeScript(unix: false)
FakeScript verifyNode = new FakeScript()
FakeScript serviceNode = new FakeScript()
List<String> verifyEnvs = []
List<String> serviceEnvs = []
List<Map> nodeVerdicts = []
List<String> windowsDirs = []
windowsNode.shHandler = { Map a -> windowsDirs << windowsNode.cwd; null }
verifyNode.shHandler = { Map a -> if (a.returnStatus) verifyEnvs << nodeEnvOf(verifyNode, a); a.returnStatus ? 0 : 'Linux' }
serviceNode.env.vars.putAll([WORKSPACE: '/ws', JOB_NAME: 'DevSecOps/CertScanner-nexusiq-pipeline', BUILD_NUMBER: '64'])
serviceNode.shHandler = { Map a ->
    if (String.valueOf(a.label ?: '').startsWith('GoldenFix: pre-check ')) serviceEnvs << nodeEnvOf(serviceNode, a)
    a.returnStdout ? '202609191405' : (a.returnStatus ? 0 : null)
}
Map npmrcNode = [enabled: true, installationName: 'NodeJS-20', npmrcCredentialsId: 'npmrc-file', registry: 'https://tools.bbh.com/nexus/repository/npm-group/']
String serviceStatus = ''
try {
    nodeHarness.run {
        nodeRunnerFor(windowsNode).build('web', [enabled: true, nodePath: 'C:\\Program Files\\nodejs', dir: 'web', commands: 'npm ci\nnpm run build:prod'])
        def verifier = nodeHarness.type('com.bbh.build.ManifestBuildVerifier').newInstance(verifyNode, nodeRunnerFor(verifyNode))
        nodeVerdicts << verifier.verify('/ws@tmp/gf', ['web/package.json', 'gui/build.gradle'], [node: npmrcNode])
        nodeVerdicts << verifier.verify('/ws@tmp/gf', ['web/package.json'], [node: npmrcNode + [enabled: false]])
        nodeVerdicts << verifier.verify('/ws@tmp/gf', ['web/package.json'], [:])
        def state = nodeHarness.type('com.bbh.core.PipelineState').newInstance()
        state.currentProjectName = 'web'
        state.cfg = [goldenFix: [enabled: true], build: [node: [enabled: true, nodePath: '/opt/node-20']],
                     scm: [bitbucket: [url: 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner', credentialsId: 'bb']]]
        String app = 'CertScanner-Web'
        List fixes = [nodeHarness.type('com.bbh.remediation.model.GoldenFix').create('npm', '', 'lodash', '4.17.15', '4.17.21', 'next-no-violations', '', 4, true, app)]
        def repository = nodeHarness.type('com.bbh.fixture.InMemorySourceRepository').newInstance([['web/package.json': '{\n  "dependencies": {\n    "lodash": "^4.17.15"\n  }\n}\n']] as Object[])
        def service = nodeHarness.type('com.bbh.remediation.GoldenFixService').newInstance(serviceNode, state,
                nodeHarness.type('com.bbh.fixture.StaticGoldenFixSource').newInstance([fixes] as Object[]), repository,
                nodeHarness.type('com.bbh.fixture.RecordingPullRequestPublisher').newInstance(),
                [nodeHarness.type('com.bbh.remediation.updater.NpmPackageJsonUpdater').newInstance()],
                nodeHarness.type('com.bbh.build.ManifestBuildVerifier').newInstance(serviceNode, nodeRunnerFor(serviceNode)))
        service.remediate([[application: app, serverUrl: 'https://tools.bbh.com/IQ', scanId: 'a' * 32]])
        serviceStatus = (state.projectsGoldenFix['web']?.status ?: '') as String
    }
} catch (Throwable t) {
    nodeErrors << (SandboxHarness.rejectionOf(t) ?: t.toString())
}
check('Node.js build: on Windows the Node.js home itself goes on PATH and each line of build.node.commands runs with bat in build.node.dir',
        nodeErrors.isEmpty() && windowsNode.calls == ['withEnv [PATH+NODEJS=C:\\Program Files\\nodejs]', 'bat [Node.js: npm ci] npm ci', 'bat [Node.js: npm run build:prod] npm run build:prod']
                && windowsDirs == ['web', 'web'] && windowsNode.log == ['[NODE] web: Node.js from C:\\Program Files\\nodejs in web: npm ci && npm run build:prod'],
        nodeErrors + windowsNode.calls + windowsDirs + windowsNode.log)
check('GoldenFix pre-check: with build.node.enabled the npm check runs inside the Node.js environment of the service (installation, .npmrc instead of the registry), '
        + 'the Gradle check and a service without it run as before',
        nodeErrors.isEmpty() && nodeVerdicts.every { it.ok } && verifyEnvs == ['npm /opt/tools/NodeJS-20/bin fake-NPM_CONFIG_USERCONFIG', 'gradle agent PATH no npmrc',
                                                                             'npm agent PATH no npmrc', 'npm agent PATH no npmrc']
                && verifyNode.calls.count { it.startsWith('withEnv') } == 1 && verifyNode.calls.contains('withEnv [PATH+NODEJS=/opt/tools/NodeJS-20/bin]'),
        nodeErrors + verifyEnvs + verifyNode.calls.findAll { it.startsWith('with') })
check('GoldenFix pre-check: GoldenFixService hands build.node of the service to the verifier, so the npm pre-check of the pull request runs with its Node.js',
        nodeErrors.isEmpty() && serviceEnvs == ['npm /opt/node-20/bin no npmrc'] && serviceStatus == 'PR_CREATED',
        nodeErrors + serviceEnvs + [serviceStatus])

SandboxHarness pickHarness = new SandboxHarness(srcDir, stubDir)
List<String> pickProblems = []
pickHarness.run {
    def model = pickHarness.type('com.bbh.remediation.model.GoldenFix')
    Closure choice = { String current, List candidates ->
        Map picked = model.selectRemediation(candidates.collect { List c -> [type: c[0], version: c[1]] }, current, null) as Map
        return picked == null ? 'none' : "${picked.version}${picked.golden ? ' (golden)' : ''}".toString()
    }
    Closure choiceWithIssues = { String current, List<List> candidates ->
        Map picked = model.selectRemediation(candidates.collect { List c -> [type: c[0], version: c[1], issues: c[2]] }, current, null) as Map
        return picked == null ? 'none' : "${picked.version}".toString()
    }
    Closure expect = { String name, String actual, String wanted ->
        if (actual != wanted) pickProblems << "${name}: chose ${actual}, expected ${wanted}".toString()
    }
    expect('only the patch changes, so a minor bump never wins', choice('1.2.3', [['next-non-failing', '1.2.4'],
            ['next-no-violations', '1.3.0']]), '1.2.4')
    expect('a patch upgrade beats the Golden Version when the Golden Version changes the minor',
            choice('1.9', [['next-no-violations', '1.9.1'], ['recommended-non-breaking', '1.10.0'],
                           ['next-non-failing', '1.9.2']]), '1.9.1')
    expect('among patch versions the newest one without vulnerabilities wins', choice('1.2.0',
            [['next-non-failing', '1.2.9'], ['next-no-violations', '1.2.5'], ['next-no-violations', '1.2.2']]), '1.2.5')
    expect('the newest patch wins when every offer is clean', choice('1.2.3', [['next-no-violations', '1.2.4'],
            ['next-no-violations', '1.2.9']]), '1.2.9')
    expect('with no clean patch offer the fewest vulnerabilities win', choiceWithIssues('1.2.3',
            [['next-non-failing', '1.2.9', 4], ['next-non-failing', '1.2.7', 1], ['next-non-failing', '1.2.5', 3]]), '1.2.7')
    expect('a patch upgrade beats a clean major upgrade', choice('1.2.3', [['next-non-failing', '1.2.4'],
            ['next-no-violations', '2.0.0']]), '1.2.4')
    expect('a 0.x minor bump changes more than the patch', choice('0.13.4', [['next-non-failing', '0.13.6'],
            ['next-no-violations', '0.14.0']]), '0.13.6')
    expect('without any patch offer the newest clean version is proposed instead', choice('1.2.3',
            [['next-no-violations', '1.9.0'], ['next-no-violations', '1.4.0']]), '1.9.0')
    expect('without any patch offer a clean major upgrade is still proposed', choice('1.2.3',
            [['next-non-failing', '2.0.0'], ['next-no-violations', '3.1.0']]), '3.1.0')
    expect('nothing is chosen when every offer is older', choice('2.0.0', [['next-no-violations', '1.9.0']]), 'none')
}
check('GoldenFix: the upgrade only changes the patch version, and is the newest such version without vulnerabilities',
        pickProblems.isEmpty(), pickProblems.join('\n      '))

SandboxHarness scaleHarness = new SandboxHarness(srcDir, stubDir)
FakeScript scaleScript = new FakeScript()
scaleScript.env.vars.putAll([WORKSPACE: '/ws', BUILD_URL: 'https://jenkins.bbh.com/job/DevSecOps/job/CertScanner-extended-pipeline/118/',
                             JOB_NAME  : 'DevSecOps/CertScanner-extended-pipeline', BUILD_NUMBER: '118'])
scaleScript.steps['triggerRemoteJob'] = { Map a ->
    new FakeRemoteHandle(buildResult: 'SUCCESS', buildUrl: "${String.valueOf(a.job ?: a.remoteJenkinsUrl)}/41/".toString())
}
List<String> scaleErrors = []
Map scaleState = [jobs: 0, rows: 0]
try {
    scaleHarness.run {
        def state = scaleHarness.type('com.bbh.core.PipelineState').newInstance()
        state.currentProjectName = 'gui'
        List smokeUrls = (1..200).collect { int i -> "https://jenkins-${(i % 4) + 1}.bbh.com/job/smoke/job/cert-scanner-check-${String.format('%03d', i)}".toString() }
        state.cfg = [tests: [maxParallel: 20, smoke: [urls: smokeUrls]], tools: [sonar: [projectName: 'CertScanner-GUI']]]
        state.cfgDefaults = [tests: [maxParallel: 20]]
        def os = scaleHarness.type('com.bbh.core.OsHelper').newInstance(scaleScript)
        def policy = scaleHarness.type('com.bbh.core.PolicyEngine').newInstance(scaleScript, state, os)
        def build = scaleHarness.type('com.bbh.build.BuildService').newInstance(scaleScript, state, os, policy)
        build.runTestJobs('Smoke tests', 'Smoke Tests', state.cfg.tests.smoke as Map)
        List jobs = ((state.projectsRemoteTestResults['gui'] as Map)['Smoke tests'] ?: []) as List
        scaleState.jobs = jobs.size()
        def report = scaleHarness.type('com.bbh.report.HtmlReportService').newInstance(scaleScript, state, os, policy, 'extended')
        scaleState.rows = (report.testJobsHtml(jobs) as String).count('cert-scanner-check-')
    }
} catch (Throwable t) {
    scaleErrors << (SandboxHarness.rejectionOf(t) ?: t.toString())
}
check('Smoke tests: 200 remote jobs from 200 URLs all run, at most maxParallel at a time', scaleErrors.isEmpty()
        && scaleState.jobs == 200 && scaleScript.calls.contains('parallel 20'),
        (scaleErrors + ["jobs=${scaleState.jobs}", scaleScript.calls.findAll { it.startsWith('parallel') }]).join(' '))
check('Smoke tests: every one of the 200 jobs is listed in the report', (scaleState.rows as int) >= 200, scaleState.rows)

Closure rnd = { Random r, int lo, int hi -> lo + r.nextInt(hi - lo + 1) }
Closure hex32 = { Random r -> (1..32).collect { Integer.toHexString(r.nextInt(16)) }.join('') }

Closure dataFor = { Random r, boolean fail, String project ->
    boolean hot = fail && (project == 'gui' || r.nextBoolean())
    int total = rnd(r, 1400, 4800)
    int pct = (fail && project == 'gui') ? rnd(r, 36, 52) : rnd(r, 67, 91)
    int covered = (int) Math.round(total * pct / 100.0d)
    return [
            coverage: [covered: covered, missed: total - covered],
            niq     : hot ? [c: rnd(r, 1, 4), h: rnd(r, 3, 9), m: rnd(r, 8, 29)] : [c: 0, h: 0, m: 0],
            sast    : hot ? [c: rnd(r, 0, 2), h: rnd(r, 1, 6), m: rnd(r, 3, 12), l: rnd(r, 4, 18)] : [c: 0, h: 0, m: 0, l: rnd(r, 2, 14)],
            sonar   : (fail && project == 'backend-api') ? [c: rnd(r, 1, 2), h: rnd(r, 1, 4), m: rnd(r, 2, 7), l: rnd(r, 3, 12), gate: 'ERROR']
                                                               : [c: 0, h: 0, m: 0, l: rnd(r, 0, 4), gate: 'OK'],
            dast    : (fail && project == 'gui') ? [c: 1, h: rnd(r, 2, 4), m: rnd(r, 4, 9), l: rnd(r, 3, 11)] : [c: 0, h: 0, m: 0, l: rnd(r, 1, 6)],
            failRate: fail ? 0.18d : 0.0d
    ]
}

Map<String, List<String>> stagesOf = [
        security: [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT],
        sast    : [MONITOR, SAST],
        nexusiq : [MONITOR, BUILD, NIQ],
        full    : [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
]
Map<String, List<Integer>> minutesOf = [
        (MONITOR): [1, 2], (UNIT): [4, 9], (BUILD): [3, 7], (NIQ): [2, 4], (SAST): [18, 34], (SONAR): [3, 6], (SNAPSHOT): [1, 3],
        (RD): [2, 5], (REGRESSION): [11, 24], (SMOKE): [3, 8], (PERFORMANCE): [12, 19], (DAST): [26, 44],
        (RELEASE): [1, 2], (QC): [2, 4]
]

Closure runScenario = { Map spec ->
    Random random = new Random(spec.seed as long)
    SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
    FakeScript script = new FakeScript()
    Yaml yaml = new Yaml()
    Map defaults = yaml.load(defaultsText) as Map
    Map projects = yaml.load(configText) as Map
    if (spec.extraSmokeUrls) {
        Map smoke = ((projects.projects as Map).gui as Map).tests.smoke as Map
        List urls = (smoke.urls ?: []) as List
        List hosts = ['a', 'b', 'c', 'd']
        (1..(spec.extraSmokeUrls as int)).each { int i ->
            urls << "https://jenkins-${hosts[i % 4]}.bbh.com/job/smoke/job/cert-scanner-check-${String.format('%02d', i)}".toString()
        }
        smoke.urls = urls
    }

    (projects.projects as Map).each { k, v -> Map sonarCfg = (((v as Map).tools ?: [:]) as Map).sonar as Map; if (sonarCfg) sonarCfg.badgeToken = '' }
    Map<String, Map> data = ['gui': dataFor(random, spec.fail as boolean, 'gui'), 'backend-api': dataFor(random, spec.fail as boolean, 'backend-api')]
    def state = null
    List<String> stageErrors = []
    List<String> goldenFixCalls = []
    GoldenFixResponder.plantManifests(script)

    script.env.vars.putAll([
            WORKSPACE               : '/ws',
            BUILD_URL               : "https://jenkins.bbh.com/job/DevSecOps/job/${spec.job}/${spec.buildNumber}/".toString(),
            JOB_NAME                : "DevSecOps/${spec.job}".toString(),
            BUILD_NUMBER            : String.valueOf(spec.buildNumber),
            PROJECT_NAMES           : 'gui,backend-api',
            HAS_BUILD_TOOL_INSTALLED: 'true',
            PATH                    : '/usr/bin',
            GIT_BRANCH              : 'origin/develop',
            APPSCAN_SERVER_URL      : 'https://bbh.cloud.appscan.com',
            DSO_PORTAL_URL          : PortalFixtures.PORTAL_URL
    ])
    script.params.DEPLOY_HIGHER_ENV = spec.deployHigherEnv ?: false
    script.resourcesDir = new File(root, 'resources')
    List<String> keys = PortalFixtures.publishAll(script, spec.variant as String, defaults.defaults as Map, platform, projects.projects as Map)
    script.shHandler = { Map a ->
        if (!a.returnStdout && String.valueOf(a.label ?: '').startsWith('GoldenFix:')) goldenFixCalls << String.valueOf(a.label)
        if (a.returnStatus) return 0
        if (spec.fail && state?.currentProjectName == 'backend-api' && String.valueOf(a.label ?: '').startsWith('Maven: test')) {
            throw new FakeAbort('script returned exit code 1')
        }
        if (!a.returnStdout) return null
        String text = String.valueOf(a.script ?: '')
        String project = state?.currentProjectName
        Map d = data[project] ?: data.gui
        if (text.contains("findall('package')")) {
            int c = (d.coverage.covered as int).intdiv(3)
            int m = (d.coverage.missed as int).intdiv(3)
            return "com.bbh.certscanner,CertificateService,CertificateService.java,${Math.round(c * 1000.0 / Math.max(c + m, 1)) / 10.0},${c},${m},${c + m}"
        }
        if (text.contains('xml.etree.ElementTree')) {
            int c = d.coverage.covered as int
            int m = d.coverage.missed as int
            return "${Math.round(c * 1000.0 / (c + m)) / 10.0},${c},${m}"
        }
        if (text.contains('facets=severities')) return "${d.sonar.c},${d.sonar.h},${d.sonar.m},${d.sonar.l}"
        return GoldenFixResponder.sh(script, a, goldenFixCalls) ?: ''
    }
    Closure jobStatus = { -> random.nextDouble() < (data[state.currentProjectName].failRate as double) ? (random.nextBoolean() ? 'FAILURE' : 'UNSTABLE') : 'SUCCESS' }
    script.steps['build'] = { Map a ->
        int number = rnd(random, 180, 960)
        String path = String.valueOf(a.job).split('/').collect { it }.join('/job/')
        return new FakeRun(result: jobStatus(), absoluteUrl: "https://jenkins.bbh.com/job/${path}/${number}/".toString(), number: number)
    }
    script.steps['triggerRemoteJob'] = { Map a ->
        int number = rnd(random, 40, 420)
        String job = String.valueOf(a.job)
        String url = job.startsWith('http') ? "${job.replaceAll('/+$', '')}/${number}/" :
                "https://${a.remoteJenkinsName ?: 'jenkins-b'}.bbh.com/job/${job.split('/').join('/job/')}/${number}/"
        return new FakeRemoteHandle(buildResult: jobStatus(), buildUrl: url.toString())
    }

    String html = ''
    Map decision = [:]
    harness.run {
        state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(script)
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(script, state, os)
        def config = harness.type('com.bbh.config.ConfigLoader').newInstance(script, state)
        def build = harness.type('com.bbh.build.BuildService').newInstance(script, state, os, policy)
        def sonar = harness.type('com.bbh.scanner.SonarService').newInstance(script, state, os, build)
        def remediation = harness.type('com.bbh.remediation.GoldenFixService').newInstance(script, state,
                harness.type('com.bbh.scanner.NexusIqGoldenFixSource').newInstance(script),
                harness.type('com.bbh.scm.GitSourceRepository').newInstance(script),
                harness.type('com.bbh.scm.BitbucketPullRequestPublisher').newInstance(script),
                ['MavenPomUpdater', 'GradleUpdater', 'NpmPackageJsonUpdater', 'PipUpdater', 'PubUpdater'].collect {
                    harness.type("com.bbh.remediation.updater.${it}".toString()).newInstance()
                })
        def nexusIq = harness.type('com.bbh.scanner.NexusIqService').newInstance(script, state, policy, remediation)
        def influx = harness.type('com.bbh.metrics.InfluxDbService').newInstance(script, state, os)
        def report = harness.type('com.bbh.report.HtmlReportService').newInstance(script, state, os, policy, spec.variant as String)
        Closure gate = { -> harness.type('com.bbh.core.ReleaseGate').newInstance(script, state) }

        Closure stage = { String name, Closure body ->
            state.stageStart(name)
            try {
                body.call()
                policy.finishStage(name)
            } catch (Throwable t) {
                stageErrors << "${name}: ${SandboxHarness.rejectionOf(t) ?: t.toString()}".toString()
                policy.failStage(name)
            } finally {
                state.stageDone(name)
            }
        }
        Closure eachProject = { Closure body ->
            config.resolveProjectNames().each { String p ->
                config.switchProject(p)
                body.call(p)
            }
        }
        List<String> stages = stagesOf[spec.variant as String]
        config.load(spec.variant as String, [pipelineKeys: keys])

        stage(MONITOR) {
            os.detect()
            script.env.OS_TYPE = os.getType()
            os.chmodX('gradlew')
            config.initialize()
        }
        if (stages.contains(UNIT)) stage(UNIT) {
            eachProject { String p ->
                script.files[state.cfg.buildTool == 'maven' ? 'target/site/jacoco/jacoco.xml' : 'build/reports/jacoco/test/jacocoTestReport.xml'] = '<report/>'
                build.buildArtifact()
                build.unitTests()
                build.checkCoverage()
            }
        }
        if (stages.contains(BUILD)) stage(BUILD) {
            eachProject { String p -> build.buildArtifact() }
        }
        if (stages.contains(NIQ)) {
            stage(NIQ) {
                eachProject { String p ->
                    Map n = data[p].niq as Map
                    String app = state.cfg.tools.nexusIq.application
                    String scanId = hex32(random)
                    script.steps['nexusPolicyEvaluation'] = { Map a ->
                        new FakeIqEvaluation(criticalComponentCount: n.c as int, severeComponentCount: n.h as int, moderateComponentCount: n.m as int,
                                applicationCompositionReportUrl: "https://tools.bbh.com/IQ/ui/links/application/${app}/report/${scanId}".toString())
                    }
                    nexusIq.scan()
                }
            }
        }
        if (stages.contains(SAST)) stage(SAST) {
            eachProject { String p ->
                Map s = data[p].sast as Map
                state.vulnCounts.sast = [critical: s.c, high: s.h, medium: s.m, low: s.l]
                policy.enforceScanner('sast')
            }
        }
        if (stages.contains(SONAR)) stage(SONAR) {
            eachProject { String p ->
                String gateStatus = data[p].sonar.gate
                script.steps['waitForQualityGate'] = { a -> new FakeQualityGate(status: gateStatus) }
                sonar.scan()
                policy.enforceScanner('sca')
            }
        }
        if (stages.contains(SNAPSHOT)) stage(SNAPSHOT) {}
        if (stages.contains(RD)) stage(RD) {}
        [[REGRESSION, 'Regression Tests', 'regression'], [SMOKE, 'Smoke Tests', 'smoke'], [PERFORMANCE, 'Performance Tests', 'performance']].each { List t ->
            if (!stages.contains(t[0])) return
            stage(t[0] as String) {
                eachProject { String p ->
                    build.runTestJobs(t[0] as String, t[1] as String, (state.cfg.tests?."${t[2]}" ?: [:]) as Map)
                }
            }
        }
        if (stages.contains(DAST)) stage(DAST) {
            eachProject { String p ->
                Map d = data[p].dast as Map
                String scanName = script.env.APPSCAN_SCAN_NAME
                state.vulnCounts.dast = [critical: d.c, high: d.h, medium: d.m, low: d.l]
                state.recordScanArtifact('dast', 'pdf', "appscan-dast-report-${scanName}.pdf".toString())
                state.recordScanArtifact('dast', 'hcl', "https://bbh.cloud.appscan.com/main/myapps/${state.cfg.appId}/scans/${hex32(random)}".toString())
                policy.registerFindings('dast', state.vulnCounts.dast as Map, "appscan-dast-report-${scanName}.html".toString())
            }
        }
        if (stages.contains(RELEASE) && gate().allowed(RELEASE)) stage(RELEASE) {}
        if (stages.contains(QC) && script.params.DEPLOY_HIGHER_ENV && gate().allowed(QC)) stage(QC) {}

        long clock = Date.parse('yyyy-MM-dd HH:mm', '2026-09-19 08:30').time
        stages.each { String name ->
            if (!state.stageTimes.containsKey(name)) return
            List<Integer> range = minutesOf[name]
            long duration = (rnd(random, range[0] * 60, range[1] * 60) as long) * 1000L
            state.stageTimes[name] = [start: clock, end: clock + duration]
            clock += duration + 4000L
        }
        (state.projectsRemoteTestResults as Map).each { p, byStage ->
            (byStage as Map).each { s, jobs -> (jobs as List).each { Map j -> j.durationMs = (rnd(random, 45, 1260) as long) * 1000L } }
        }
        script.currentBuild.duration = clock - Date.parse('yyyy-MM-dd HH:mm', '2026-09-19 08:30').time

        report.generate()
        decision = gate().evaluate() as Map
        gate().publish()
        influx.send(spec.influx as String)
        html = script.files['report/pipeline-report.html'] ?: ''
    }

    new File(reportsDir, "${spec.name}.html").text = html
    return [spec: spec, html: html, log: script.log, errors: stageErrors, unhandled: script.unhandled.unique(),
            decision: decision, result: script.currentBuild.result ?: 'SUCCESS', jsonFiles: script.jsonFiles,
            goldenFix: state.projectsGoldenFix, goldenFixCalls: goldenFixCalls, files: script.files]
}

List<Map> scenarios = [
        [name: 'security-pipeline-pass', title: 'Static security pipeline', outcome: 'pass', variant: 'security', fail: false, seed: 1101L,
         job: 'CertScanner-security-pipeline', buildNumber: 212, influx: 'security'],
        [name: 'security-pipeline-fail', title: 'Static security pipeline', outcome: 'fail', variant: 'security', fail: true, seed: 1102L,
         job: 'CertScanner-security-pipeline', buildNumber: 213, influx: 'security'],
        [name: 'sast-pipeline-pass', title: 'SAST pipeline', outcome: 'pass', variant: 'sast', fail: false, seed: 2201L,
         job: 'CertScanner-sast-pipeline', buildNumber: 87, influx: 'sast'],
        [name: 'sast-pipeline-fail', title: 'SAST pipeline', outcome: 'fail', variant: 'sast', fail: true, seed: 2202L,
         job: 'CertScanner-sast-pipeline', buildNumber: 88, influx: 'sast'],
        [name: 'nexusiq-pipeline-pass', title: 'Nexus IQ GoldenFix pipeline', outcome: 'pass', variant: 'nexusiq', fail: false, seed: 4401L,
         job: 'CertScanner-nexusiq-pipeline', buildNumber: 61, influx: 'nexusiq'],
        [name: 'nexusiq-pipeline-fail', title: 'Nexus IQ GoldenFix pipeline', outcome: 'fail', variant: 'nexusiq', fail: true, seed: 4402L,
         job: 'CertScanner-nexusiq-pipeline', buildNumber: 62, influx: 'nexusiq'],
        [name: 'full-pipeline-pass', title: 'Full DevSecOps pipeline', outcome: 'pass', variant: 'full', fail: false, seed: 3301L, deployHigherEnv: true,
         extraSmokeUrls: 6, job: 'CertScanner-devsecops-pipeline', buildNumber: 341, influx: ''],
        [name: 'full-pipeline-fail', title: 'Full DevSecOps pipeline', outcome: 'fail', variant: 'full', fail: true, seed: 3302L, deployHigherEnv: true,
         extraSmokeUrls: 18, job: 'CertScanner-devsecops-pipeline', buildNumber: 342, influx: '']
]

List<Map> results = []
scenarios.each { Map spec ->
    Map r = runScenario(spec)
    results << r
    String html = r.html as String
    boolean pass = spec.outcome == 'pass'
    check("${spec.name}: every stage ran without a sandbox rejection or exception", (r.errors as List).isEmpty(), (r.errors as List).join('\n      '))
    check("${spec.name}: the full report template was rendered", html.contains('<h2>Pipeline Stages</h2>')
            && !(r.log as List).any { it.contains('Full report could not be built') },
            (r.log as List).findAll { it.contains('[REPORT]') }.join(' | '))
    check("${spec.name}: release gate ${pass ? 'allows' : 'blocks'} the release", (r.decision.allowed as boolean) == pass, r.decision.reason)
    check("${spec.name}: release gate verdict written for the downstream pipeline", (r.jsonFiles as Map).containsKey('release-gate.json'), (r.jsonFiles as Map).keySet())
    check("${spec.name}: build result is ${pass ? 'SUCCESS' : 'UNSTABLE'}", r.result == (pass ? 'SUCCESS' : 'UNSTABLE'), r.result)
    if (spec.variant == 'sast') {
        check("${spec.name}: only SAST content in the SAST report", !html.contains('Nexus IQ') && !html.contains('SCA (SonarQube)')
                && !html.contains('Smoke tests') && !html.contains('Release policy') && !html.contains('TESTS WERE NOT EXECUTED'), 'foreign content rendered')
    }
    if (spec.variant == 'nexusiq') {
        check("${spec.name}: the build and the Nexus IQ scan${pass ? '' : ' with its GoldenFix card'} and no SAST, SonarQube or release content in the report",
                html.contains('Build artifact') && html.contains('Nexus IQ') && (pass || html.contains('<h2>Nexus IQ GoldenFix</h2>'))
                        && !html.contains('SAST (AppScan)') && !html.contains('SCA (SonarQube)') && !html.contains('Smoke tests')
                        && !html.contains('Release policy') && !html.contains('TESTS WERE NOT EXECUTED'), 'Nexus IQ content missing or foreign content rendered')
    }
    if (spec.variant == 'full' && !pass) {
        check("${spec.name}: release and QC blocked with the banner", html.contains('NEXUS RELEASE AND QC DEPLOYMENT BLOCKED'), 'banner missing')
        check("${spec.name}: failing unit tests turn the stage orange and the pipeline goes on", html.contains('Unit tests failed (script returned exit code 1)')
                && (r.decision.violations as List).any { it.toString().contains("stage 'Unit tests' is WARN") } && html.contains('SCA (SonarQube)'),
                (r.decision.violations as List).join(' | '))
        check("${spec.name}: DAST offers HTML report, HCL AppScan page and PDF", html.contains('HCL AppScan') && html.contains('DAST Report (PDF)'), 'DAST links missing')
        check("${spec.name}: HCL AppScan link in the stage boxes and in the Security Gates table", html.count('>HCL AppScan</a>') >= 4, html.count('>HCL AppScan</a>'))
        check("${spec.name}: the coverage reason is shown only in the box of the failing project", html.count('Line coverage ') == 1, html.count('Line coverage '))
    }
    if (spec.variant != 'sast' && !pass) {
        Map gui = (((r.goldenFix ?: [:]) as Map).gui ?: [:]) as Map
        List<String> calls = r.goldenFixCalls as List<String>
        Map files = r.files as Map
        String gradle = (files["${GoldenFixResponder.WORKTREE}/gui/build.gradle".toString()] ?: '') as String
        String properties = (files["${GoldenFixResponder.WORKTREE}/gradle.properties".toString()] ?: '') as String
        check("${spec.name}: GoldenFix runs through the production wiring and raises the pull request", gui.status == 'PR_CREATED'
                && gui.prTitle == "GoldenFix-${GoldenFixResponder.STAMP}".toString() && gui.targetBranch == 'develop' && gui.prUrl == GoldenFixResponder.PULL_REQUEST_URL,
                "${gui.status}: ${gui.message}")
        check("${spec.name}: every Nexus IQ remediation lookup of a project is one sh step, not one per component",
                calls.count { it.startsWith('BATCH sh with ') } == 1 && calls.contains('BATCH sh with 4 request(s)'),
                calls.findAll { it.startsWith('BATCH sh with ') })
        check("${spec.name}: GoldenFix asks Nexus IQ for the application, the policy report and a remediation per eligible component",
                calls.count { it.startsWith('GET https://tools.bbh.com/IQ/api/v2/applications?publicId=') } >= 1
                        && calls.count { it.startsWith('POST https://tools.bbh.com/IQ/api/v2/components/remediation/application/') } == 4, calls.join('\n      '))
        check("${spec.name}: GoldenFix upgrades build.gradle to the patch version and the version property in gradle.properties",
                gradle.contains("'org.apache.commons:commons-text:1.10.0'")
                && gradle.contains('"com.fasterxml.jackson.core:jackson-databind:2.13.5"') && gradle.contains('netty-codec-http:\${nettyVersion}')
                && properties.contains('nettyVersion=4.1.108.Final') && properties.contains('org.gradle.jvmargs=-Xmx2g'), gradle + properties)
        check("${spec.name}: GoldenFix lists the BOM managed component as not applied", ((gui.unresolved ?: []) as List).any { it.component == 'org.yaml:snakeyaml' }, gui.unresolved)
        check("${spec.name}: GoldenFix prepares the worktree, commits, pushes, opens the pull request and removes the worktree",
                ['GoldenFix: prepare worktree', 'GoldenFix: commit changes', 'GoldenFix: git push', 'POST https://bitbucket.bbh.com/rest/api/1.0/projects/TA/repos/cert-scanner/pull-requests',
                 "PR GoldenFix-${GoldenFixResponder.STAMP} refs/heads/GoldenFix-${GoldenFixResponder.STAMP} -> refs/heads/develop", 'GoldenFix: remove worktree'].every { String step -> calls.any { it.startsWith(step) } },
                calls.join('\n      '))
        check("${spec.name}: GoldenFix pull request is linked in the report", html.contains('pull-requests/318') && html.contains("GoldenFix-${GoldenFixResponder.STAMP}")
                && html.contains('nettyVersion') && html.contains('org.yaml:snakeyaml'), 'GoldenFix card incomplete')
        check("${spec.name}: the GoldenFix pull request is shown only for the project that raised it", html.count('Automatic dependency upgrade proposed for review') == 1, html.count('Automatic dependency upgrade proposed for review'))
    }
    if (r.unhandled) println "      note: steps not emulated by the fake Jenkins: ${r.unhandled}"
}

Closure esc = { String s -> (s ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }
StringBuilder cards = new StringBuilder()
['security', 'sast', 'nexusiq', 'full'].each { String variant ->
    results.findAll { it.spec.variant == variant }.each { Map r ->
        Map spec = r.spec as Map
        boolean pass = spec.outcome == 'pass'
        List reasons = ((r.decision.violations ?: []) as List).take(4)
        Map verdicts = [
                security: ['Every stage green. The snapshot is published to Nexus and nothing blocks the release.',
                           'Orange stages keep the build running. The snapshot is still published, the release and the QC deployment are blocked.'],
                sast    : ['SAST found nothing above the policy.',
                           'SAST findings above the policy. They will block the Nexus release and the QC deployment.'],
                nexusiq : ['Nexus IQ found no dependency above the policy, so GoldenFix had nothing to upgrade.',
                           'Dependencies above the Nexus IQ policy turn the scan orange. GoldenFix raised a pull request with the safe versions.'],
                full    : ['Every stage green. The artifact is released to Nexus and deployed to QC.',
                           'Orange stages keep the build running. The Nexus release and the QC deployment stay blocked.']
        ]
        String verdict = (verdicts[variant] as List)[pass ? 0 : 1] as String
        String reasonHtml = pass
                ? "<p class='verdict ok'>${verdict}</p>"
                : "<p class='verdict warn'>${verdict}</p><ul>${reasons.collect { "<li>${esc(it as String)}</li>" }.join('')}</ul>"
        cards.append("""<a class='card ${pass ? 'pass' : 'fail'}' href='${spec.name}.html'>
  <div class='top'><span class='chip'>${pass ? 'SUCCESS' : 'UNSTABLE'}</span><span class='build'>#${spec.buildNumber}</span></div>
  <h2>${esc(spec.title as String)}</h2>
  <p class='job'>${esc(spec.job as String)}</p>
  ${reasonHtml}
  <span class='open'>Open report &rarr;</span>
</a>
""")
    }
}
new File(reportsDir, 'index.html').text = """<!DOCTYPE html>
<html lang='en'>
<head>
<meta charset='UTF-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>DevSecOps pipeline reports</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; color: #1f2937; }
.wrap { max-width: 1180px; margin: 0 auto; padding: 32px 16px 56px; }
header { background: linear-gradient(135deg,#1d4ed8,#3b82f6); color: #fff; border-radius: 6px; padding: 24px 28px; box-shadow: 0 2px 8px rgba(0,0,0,.15); }
header h1 { font-size: 1.5rem; font-weight: 800; letter-spacing: -.3px; }
header p { margin-top: 6px; opacity: .9; font-size: .9rem; max-width: 760px; line-height: 1.5; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; margin-top: 22px; }
.card { display: flex; flex-direction: column; gap: 8px; background: #fff; border: 1px solid #e2e8f0; border-left: 4px solid #16a34a; border-radius: 6px; padding: 16px 18px; color: inherit; text-decoration: none; box-shadow: 0 1px 3px rgba(0,0,0,.06); transition: box-shadow .15s, transform .15s; }
.card:hover { box-shadow: 0 6px 18px rgba(15,23,42,.12); transform: translateY(-1px); }
.card.fail { border-left-color: #d97706; }
.top { display: flex; justify-content: space-between; align-items: center; }
.chip { font-size: .66rem; font-weight: 800; letter-spacing: .6px; color: #fff; background: #16a34a; padding: 3px 8px; border-radius: 3px; }
.card.fail .chip { background: #d97706; }
.build { font-size: .78rem; color: #64748b; font-weight: 600; }
.card h2 { font-size: 1.02rem; color: #0f172a; }
.job { font-size: .78rem; color: #64748b; font-family: ui-monospace, Menlo, Consolas, monospace; }
.verdict { font-size: .84rem; line-height: 1.45; }
.verdict.ok { color: #166534; }
.verdict.warn { color: #92400e; }
.card ul { margin: 0 0 0 18px; font-size: .76rem; color: #475569; line-height: 1.5; }
.open { margin-top: auto; font-size: .8rem; font-weight: 700; color: #1d4ed8; }
footer { margin-top: 28px; font-size: .76rem; color: #64748b; line-height: 1.5; }
</style>
</head>
<body>
<div class='wrap'>
<header>
  <h1>DevSecOps pipeline reports</h1>
  <p>Eight builds of the CertScanner example with two projects, a Gradle GUI deployed to a VM and a Maven API deployed to OpenShift. Each pipeline is shown once with every stage green and once with policy violations, which turn stages orange and block the Nexus release and the QC deployment.</p>
</header>
<div class='grid'>
${cards.toString()}</div>
<footer>Generated by <code>test/run-all.sh</code>: the library code runs under the Jenkins script-security sandbox with randomised scan and test results, and every page is produced by the report template of the library.</footer>
</div>
</body>
</html>
"""

println "\n${failures == 0 ? 'ALL SANDBOX SCENARIOS PASSED' : failures + ' SANDBOX SCENARIO CHECK(S) FAILED'}"
println "Reports written to ${reportsDir}"
System.exit(failures == 0 ? 0 : 1)
