import devsecops.test.FakeAbort
import devsecops.test.FakeIqEvaluation
import devsecops.test.FakeQualityGate
import devsecops.test.FakeRemoteHandle
import devsecops.test.FakeRun
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import org.yaml.snakeyaml.Yaml

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File reportsDir = new File(root, 'examples/reports')
reportsDir.mkdirs()

String MONITOR = 'Monitor source changes (download sources)'
String UNIT = 'Unit tests'
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

String defaultsText = new File(root, 'resources/defaults.yaml').text
String configText = new File(root, 'examples/CertScanner/config.yaml').text

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
check('GoldenFix pre-check: the command of an ecosystem can be overridden in config.yaml',
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
        full    : [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
]
Map<String, List<Integer>> minutesOf = [
        (MONITOR): [1, 2], (UNIT): [4, 9], (NIQ): [2, 4], (SAST): [18, 34], (SONAR): [3, 6], (SNAPSHOT): [1, 3],
        (RD): [2, 5], (REGRESSION): [11, 24], (SMOKE): [3, 8], (PERFORMANCE): [12, 19], (DAST): [26, 44],
        (RELEASE): [1, 2], (QC): [2, 4]
]

String gfStamp = '202609191405'
String gfWorktree = "/ws@tmp/goldenfix/GoldenFix-${gfStamp}".toString()
String gfPullRequestUrl = 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner/pull-requests/318'
Map<String, String> gfManifests = [
        'gradle.properties': 'org.gradle.jvmargs=-Xmx2g\nnettyVersion=4.1.86.Final\n',
        'gui/build.gradle' : """plugins {
    id 'java'
    id 'jacoco'
}

dependencies {
    implementation 'org.apache.commons:commons-text:1.9'
    implementation "com.fasterxml.jackson.core:jackson-databind:2.13.4"
    implementation "io.netty:netty-codec-http:\${nettyVersion}"
    implementation 'org.springframework.boot:spring-boot-starter-web'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}
""".toString()
]
Closure iqComponent = { String g, String a, String v, int threat, Boolean direct, boolean waived ->
    [packageUrl         : "pkg:maven/${g}/${a}@${v}?type=jar".toString(),
     componentIdentifier: [format: 'maven', coordinates: [groupId: g, artifactId: a, version: v, classifier: '', extension: 'jar']],
     dependencyData     : [directDependency: direct],
     violations         : [[policyName: threat >= 8 ? 'Security-Critical' : 'Security-High', policyThreatLevel: threat, waived: waived]]]
}
Map iqPolicyReport = [components: [
        iqComponent('org.apache.commons', 'commons-text', '1.9', 10, true, false),
        iqComponent('com.fasterxml.jackson.core', 'jackson-databind', '2.13.4', 8, true, false),
        iqComponent('io.netty', 'netty-codec-http', '4.1.86.Final', 7, true, false),
        iqComponent('org.yaml', 'snakeyaml', '1.33', 8, true, false),
        iqComponent('com.google.protobuf', 'protobuf-java', '3.21.7', 8, false, false),
        iqComponent('ch.qos.logback', 'logback-classic', '1.2.11', 7, true, true),
        iqComponent('org.apache.commons', 'commons-compress', '1.21', 1, true, false)
]]
Map iqRemediations = [
        'commons-text'    : [['next-no-violations', '1.10.0']],
        'jackson-databind': [['next-non-failing', '2.13.5'], ['next-no-violations', '2.15.4']],
        'netty-codec-http': [['next-no-violations-with-dependencies', '4.1.108.Final']],
        'snakeyaml'       : [['next-no-violations', '2.2']]
]
Closure iqRespond
Closure goldenFixSh = { FakeScript s, Map a, List<String> calls ->
    String text = String.valueOf(a.script ?: '')
    String label = String.valueOf(a.label ?: '')
    if (label.startsWith('GoldenFix:')) calls << label
    if (text.contains('date +%Y%m%d%H%M')) return gfStamp + '\n'
    if (label == 'GoldenFix: find dependency manifests') return gfManifests.keySet().sort().join('\n') + '\n'
    if (label == 'GoldenFix: commit changes') return 'c0ffee1d2e3f40516273849a5b6c7d8e9f001122\n'
    if (text.contains('run_one() {')) {
        StringBuilder batch = new StringBuilder()
        int batched = 0
        def counter = (text =~ /(?m)^run_one (\d+) '([^']+)' &$/)
        while (counter.find()) batched++
        calls << "BATCH sh with ${batched} request(s)".toString()
        def calls2 = (text =~ /(?m)^run_one (\d+) '([^']+)' &$/)
        while (calls2.find()) {
            int index = calls2.group(1) as int
            String batchUrl = calls2.group(2)
            def body = (text =~ /(?s)cat > body-${index}\.json <<'DEVSECOPS_BODY_${index}'\n(.*?)\nDEVSECOPS_BODY_${index}\n/)
            def parsedBody = body.find() ? new groovy.json.JsonSlurper().parseText(body.group(1)) : null
            calls << "POST ${batchUrl}".toString()
            batch.append("===DEVSECOPS-RESPONSE ${index}===\n")
            batch.append(iqRespond('POST', batchUrl, parsedBody, calls)).append('\n')
        }
        return batch.toString()
    }
    def curl = text =~ /(?m)curl -sS -X '(\w+)'.*'(https?:\/\/[^']+)'\s*$/
    if (!curl.find()) return null
    String method = curl.group(1)
    String url = curl.group(2)
    def bodyFile = text =~ /@'([^']+\.json)'/
    def payload = bodyFile.find() ? s.jsonFiles[bodyFile.group(1)] : null
    calls << "${method} ${url}".toString()
    return iqRespond(method, url, payload, calls)
}

iqRespond = { String method, String url, def payload, List calls ->
    if (url.contains('/api/v2/applications?publicId=')) {
        String app = url.substring(url.indexOf('publicId=') + 9)
        return groovy.json.JsonOutput.toJson([applications: [[id: '7d3b2c1a9e8f4a6b8c0d1e2f3a4b5c6d', publicId: app, name: app]]]) + '\n200'
    }
    if (url.contains('/reports/') && url.endsWith('/policy')) {
        return groovy.json.JsonOutput.toJson(url.contains('CertValidityMonitoring-GUI') ? iqPolicyReport : [components: []]) + '\n200'
    }
    if (url.contains('/api/v2/components/remediation/application/')) {
        Map coordinates = (payload?.componentIdentifier?.coordinates ?: [:]) as Map
        List changes = ((iqRemediations[coordinates.artifactId] ?: []) as List).collect { List t ->
            [type: t[0], data: [component: [componentIdentifier: [format: 'maven', coordinates: [groupId: coordinates.groupId, artifactId: coordinates.artifactId, version: t[1]]],
                                            packageUrl         : "pkg:maven/${coordinates.groupId}/${coordinates.artifactId}@${t[1]}?type=jar".toString()]]]
        }
        return groovy.json.JsonOutput.toJson([remediation: [versionChanges: changes]]) + '\n200'
    }
    if (url.endsWith('/rest/api/1.0/projects/TA/repos/cert-scanner/pull-requests') && method == 'POST') {
        calls << "PR ${payload?.title} ${payload?.fromRef?.id} -> ${payload?.toRef?.id}".toString()
        return groovy.json.JsonOutput.toJson([id: 318, title: payload?.title, links: [self: [[href: gfPullRequestUrl]]]]) + '\n201'
    }
    return '{}\n404'
}

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
    gfManifests.each { String file, String text -> script.files["${gfWorktree}/${file}".toString()] = text }

    script.env.vars.putAll([
            WORKSPACE               : '/ws',
            BUILD_URL               : "https://jenkins.bbh.com/job/DevSecOps/job/${spec.job}/${spec.buildNumber}/".toString(),
            JOB_NAME                : "DevSecOps/${spec.job}".toString(),
            BUILD_NUMBER            : String.valueOf(spec.buildNumber),
            PROJECT_NAMES           : 'gui,backend-api',
            HAS_BUILD_TOOL_INSTALLED: 'true',
            PATH                    : '/usr/bin',
            GIT_BRANCH              : 'origin/develop',
            APPSCAN_SERVER_URL      : 'https://bbh.cloud.appscan.com'
    ])
    script.params.DEPLOY_HIGHER_ENV = spec.deployHigherEnv ?: false
    script.resources['defaults.yaml'] = defaultsText
    script.files['config.yaml'] = configText
    script.yamlHandler = { Map a -> a.text != null ? defaults : projects }
    script.shHandler = { Map a ->
        if (a.returnStatus) return 0
        if (spec.fail && state?.currentProjectName == 'backend-api' && String.valueOf(a.label ?: '').startsWith('Maven: test')) {
            throw new FakeAbort('script returned exit code 1')
        }
        if (!a.returnStdout) {
            if (String.valueOf(a.label ?: '').startsWith('GoldenFix:')) goldenFixCalls << String.valueOf(a.label)
            return null
        }
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
        return goldenFixSh(script, a, goldenFixCalls) ?: ''
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
        String gradle = (files["${gfWorktree}/gui/build.gradle".toString()] ?: '') as String
        String properties = (files["${gfWorktree}/gradle.properties".toString()] ?: '') as String
        check("${spec.name}: GoldenFix runs through the production wiring and raises the pull request", gui.status == 'PR_CREATED'
                && gui.prTitle == "GoldenFix-${gfStamp}".toString() && gui.targetBranch == 'develop' && gui.prUrl == gfPullRequestUrl,
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
                 "PR GoldenFix-${gfStamp} refs/heads/GoldenFix-${gfStamp} -> refs/heads/develop", 'GoldenFix: remove worktree'].every { String step -> calls.any { it.startsWith(step) } },
                calls.join('\n      '))
        check("${spec.name}: GoldenFix pull request is linked in the report", html.contains('pull-requests/318') && html.contains("GoldenFix-${gfStamp}")
                && html.contains('nettyVersion') && html.contains('org.yaml:snakeyaml'), 'GoldenFix card incomplete')
        check("${spec.name}: the GoldenFix pull request is shown only for the project that raised it", html.count('Automatic dependency upgrade proposed for review') == 1, html.count('Automatic dependency upgrade proposed for review'))
    }
    if (r.unhandled) println "      note: steps not emulated by the fake Jenkins: ${r.unhandled}"
}

Closure esc = { String s -> (s ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }
StringBuilder cards = new StringBuilder()
['security', 'sast', 'full'].each { String variant ->
    results.findAll { it.spec.variant == variant }.each { Map r ->
        Map spec = r.spec as Map
        boolean pass = spec.outcome == 'pass'
        List reasons = ((r.decision.violations ?: []) as List).take(4)
        Map verdicts = [
                security: ['Every stage green. The snapshot is published to Nexus and nothing blocks the release.',
                           'Orange stages keep the build running. The snapshot is still published, the release and the QC deployment are blocked.'],
                sast    : ['SAST found nothing above the policy.',
                           'SAST findings above the policy. They will block the Nexus release and the QC deployment.'],
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
  <p>Six builds of the CertScanner example with two projects, a Gradle GUI deployed to a VM and a Maven API deployed to OpenShift. Each pipeline is shown once with every stage green and once with policy violations, which turn stages orange and block the Nexus release and the QC deployment.</p>
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
