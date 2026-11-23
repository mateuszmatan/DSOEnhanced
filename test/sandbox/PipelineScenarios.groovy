import devsecops.test.FakeAbort
import devsecops.test.FakeCpsScript
import devsecops.test.FakeIqEvaluation
import devsecops.test.FakeOpenShift
import devsecops.test.FakeQualityGate
import devsecops.test.FakeRemoteHandle
import devsecops.test.FakeRun
import devsecops.test.FakeScript
import devsecops.test.GoldenFixResponder
import devsecops.test.OneViewFixtures
import devsecops.test.SandboxHarness
import devsecops.test.ScenarioRunner
import org.yaml.snakeyaml.Yaml

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File varsDir = new File(root, 'vars')
String defaultsText = new File(root, 'test/fixtures/defaults.yaml').text
String configText = new File(root, 'test/fixtures/CertScanner/config.yaml').text
Map platform = (new groovy.json.JsonSlurperClassic().parseText(new File(root, 'test/fixtures/oneview/certscanner-gui-full.json').text) as Map).platform as Map
Map variants = [devSecOpsPipeline: 'full', devSecOpsSecurityPipeline: 'security', devSecOpsExtendedPipeline: 'extended', devSecOpsSASTScanningPipeline: 'sast',
                devSecOpsNexusIqGoldenFixPipeline: 'nexusiq']
String securityJob = 'DevSecOps/CertScanner-security-pipeline'

String MONITOR = 'Monitor source changes (download sources)'
String NODE = 'Node.js build'
String UNIT = 'Unit tests'
String BUILD = 'Build artifact'
String NIQ = 'Dependencies scan (Nexus IQ)'
String SAST = 'SAST - Static Application Security Tests - HCL AppScan'
String SONAR = 'SCA (SonarQube)'
String STATIC = 'Static analysis'
String DYNAMIC = 'Dynamic analysis'
String SNAPSHOT = 'Nexus delivery (Static analysis passed)'
String PULL = 'Download artifacts from Nexus Repo'
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

Closure issuesHtml = { List counts ->
    StringBuilder sb = new StringBuilder('<html><body>')
    int id = 1
    ['Critical', 'High', 'Medium', 'Low'].eachWithIndex { String severity, int i ->
        (counts[i] as int).times {
            sb.append("<div>Issue ID: ${id++}</div><div>Severity: ${severity}</div><div>Status: Open</div>")
        }
    }
    sb.append('</body></html>').toString()
}

Closure dataFor = { boolean fail ->
    [
            'gui'        : [coverage: fail ? [45.0, 450, 550] : [82.4, 824, 176], niq: fail ? [2, 5, 9] : [0, 0, 0],
                            sast    : fail ? [0, 3, 4, 6] : [0, 0, 0, 5], sonar: [0, 0, 0, 1, 'OK'], dast: fail ? [1, 2, 3, 4] : [0, 0, 0, 2]],
            'backend-api': [coverage: [84.0, 840, 160], niq: [0, 0, 0], sast: fail ? [0, 1, 2, 3] : [0, 0, 0, 2],
                            sonar   : fail ? [1, 2, 3, 4, 'ERROR'] : [0, 0, 0, 0, 'OK'], dast: [0, 0, 0, 1]]
    ]
}

List<String> secretResources = ['a2a-certs/a2a.sh', 'a2a-certs/get-a2a-password.sh', 'a2a-certs/loginfile.sh',
                                 'a2a-certs/PROXY_ASOCJenk.cert.pem', 'a2a-certs/PROXY_ASOCJenk.key.pem', 'a2a-certs/PROXY_ASOCJenk.keypsw']

Map runnerContext = [root: root, defaultsText: defaultsText, configText: configText, platform: platform, variants: variants, securityJob: securityJob,
                     dataFor: dataFor, issuesHtml: issuesHtml, secretResources: secretResources, archive: { Map projects, Map spec ->
    Map rd = ((((projects['backend-api'] as Map).deploy as Map).openshift as Map).rd as Map)
    rd.buildTag = '213-20260919-081500'
    rd.internalDockerUrl = 'image-registry.openshift-image-registry.svc:5000/ta-certscanner-build/certscanner-api@sha256:5f1c0ffee'
}]
Closure runPipeline = { Map spec -> ScenarioRunner.runPipeline(spec, runnerContext) }

List<String> staticStages = [MONITOR, BUILD, UNIT, NIQ, SAST, SONAR, SNAPSHOT]
List<String> extendedStages = [MONITOR, PULL, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
List<String> fullStages = [MONITOR, BUILD, UNIT, NIQ, SAST, SONAR, SNAPSHOT, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
List<String> declaredFull = [MONITOR, BUILD, UNIT, NIQ, SAST, SONAR, SNAPSHOT, RD, DYNAMIC, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
List<String> testStages = [REGRESSION, SMOKE, PERFORMANCE, DAST]

File goldenDir = new File(root, 'test/sandbox/golden')

Closure influxOf = { FakeScript j -> ScenarioRunner.influxOf(j) }

Closure sectionsOf = { String view ->
    Map<String, List<String>> out = [:]
    String current = null
    view.readLines().each { String line ->
        if (line ==~ /== (calls|log|release-gate\.json|influx|report)/) {
            current = line.substring(3)
            out[current] = []
        } else {
            out[current] << line
        }
    }
    return out
}
String stagedCopy = 'sh rm -rf "$WORKSPACE/appscan-source-staged"'
String orderLine = '[STATIC] SAST queues the scan of every service first; Unit tests, Nexus IQ and SonarQube then run one after another while AppScan scans, and SAST reads the results last'
String stopLine = '[STATIC] SAST stopped while it prepared its scans: Unit tests and Nexus IQ do not run in this build'
String sastCopy = '/ws@tmp/dso-sast-workspace'
List<String> parallelOnly = ['[STATIC] ', '[DYNAMIC] ', '[PARALLEL] ', '[INFO] [STATIC] ', '[INFO] [DYNAMIC] ', 'parallel ', 'withEnv [DSO_PARALLEL_BRANCH=',
                             'withEnv [CURRENT_PROJECT_NAME=', 'withEnv [DSO_SAST_WORKSPACE=', 'withEnv [WORKSPACE=',
                             'sh [Copy the workspace for the SAST compile and scan]', 'sh [Remove the SAST copy of the workspace]']
List<String> copyScript = ['set -euo pipefail', 'rm -rf "$DSO_SAST_WORKSPACE"', 'mkdir -p "$DSO_SAST_WORKSPACE"', '']
Closure reordered = { List<String> lines ->
    List<String> kept = lines.findAll { String line -> line != stagedCopy && !parallelOnly.any { line.startsWith(it) } && !line.startsWith('tar -C "$WORKSPACE" --exclude=./.appscan-tools ') }
    int copies = lines.count { it.startsWith('sh [Copy the workspace for the SAST compile and scan]') } as int
    copyScript.each { String line -> copies.times { kept.remove(line) } }
    kept.sort()
}
Closure sequentialView = { String text -> text.replace(sastCopy, '/ws') }

Closure goldenView = { Map r -> ScenarioRunner.goldenView(r) }

Closure boxesOf = { String html -> (html =~ />([0-9.]+)<\/span><span style='overflow-wrap:anywhere;word-break:break-word;min-width:0;'>([^<]+)<\/span>/).collect { "${it[1]} ${it[2]}".toString() } }
Closure boxOf = { String html, String name ->
    int at = html.indexOf(">${name}</span>".toString())
    int next = html.indexOf("<div style='border:1px solid", at)
    at < 0 ? '' : html.substring(at, next < 0 ? html.length() : next)
}
Closure rule = { String reason, String pattern, String replacement -> ScenarioRunner.rule(reason, pattern, replacement) }
List<Map> baseRules = ScenarioRunner.baseRules()
Map bootstrapRule = rule('configure() reads OneView on a bootstrap agent before the pipeline starts its own agent (L4)',
        /(?ms)\A== calls\n.*?^(?=agent )/, '== calls\n')
Map oneViewLogRule = rule('the OneView read logs one [ONEVIEW] line per key (L2)', /(?m)^\[ONEVIEW\].*\n/, '')
Map headerRule = rule('the report header names the OneView keys or keyless marker (accepted difference 1)',
        /<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>(OneView|No pipeline key): [^\n]*?<\/div>/, '')
List<Map> oneViewRules = [
        bootstrapRule,
        oneViewLogRule,
        rule('the run names the order of its static analysis and of its tests and DAST, which were not switchable then',
                /(?m)^\[(STATIC|DYNAMIC)\] [^\n]*\n/, ''),
        rule('ConfigLoader.initialize() writes the run-state file right after reading the commit (L6)',
                /(?m)(^sh \[Read the commit under test\].*\n)writeYaml pipeline-config\.yaml\n/, '$1'),
        rule('the run-state file is pipeline-config.yaml instead of config.yaml (accepted difference 3)',
                /(?<![-\w])config\.yaml/, 'pipeline-config.yaml'),
        rule('build_evidence is a new InfluxDB measurement (L10)', /(?m)^build_evidence,.*\n/, ''),
        rule('the unit test counts are a new test_execution point with suite=unit (L10)', /(?m)^test_execution,[^ ]*,suite=unit .*\n/, ''),
        rule('the logged number of InfluxDB lines counts those new points (L10)', /(?m)^\[INFLUX\] \d+ metric line/, '[INFLUX] <n> metric line'),
        headerRule
]
List<Map> keylessMarks = [
        rule('a run without a key names its settings file in the report header (M25)',
                /<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>No pipeline key: [^\n]*?<\/div>/, ''),
        rule('build_evidence names the settings file of a run without a key', /,config_file="[^"]*"/, ''),
        rule('a run without a key has no OneView to send the report to', /(?ms)^withEnv \[ONEVIEW_URL=[^\n]*\n.*?^sh rm -f 'oneview_report_[^\n]*\n/, ''),
        rule('a run without a key names its settings file in the warning about an AppScan key of a service',
                /(?m)^(\[WARN\] [^:\n]+: [^\n]*?) in [\w-]*config\.yaml ignored - /, '$1 in OneView ignored - '),
        rule('a run without a key names its settings file where a Gradle service lacks the artifacts of its Nexus delivery',
                /(?m)^(\[NEXUS\] [^:\n]+: delivery\.push\.gradle\.artifacts is not set) in [\w-]*config\.yaml - /, '$1 in OneView - ')
]
List<Map> keylessRules = [bootstrapRule, oneViewLogRule,
                          rule('build_evidence has no config_rendered_at and config_sha256 without OneView documents (L10)', /,config_rendered_at="[^"]*",config_sha256="[^"]*"/, ''),
                          headerRule] + keylessMarks

List<Map> stackRules = [
        rule('the auto-resolve logs what it filled in for each service (autoresolveParameters)', /(?m)^\[AUTO\] .*\n/, ''),
        rule('SonarQube logs the project, the branch and how it is analysed', /(?m)^\[SONAR\] (Project|sonar-project\.properties|No sonar-project\.properties) .*\n/, ''),
        rule('the SonarQube Gradle and Maven plugins scan the branch of the build', / '-Dsonar\.branch\.name=[^']*'/, ''),
        rule('pull request decoration looks for the pull request of the branch and reads its comments',
                /(?ms)^withCredentials \[[^\]\n]*\]\nsh \[Bitbucket: (find open pull request|comments of pull request [^\]]*)\] .*?\n\n/, ''),
        rule('the static and DAST findings pull requests are prepared in their own worktree',
                /(?ms)^sh \[GoldenFix: prepare worktree security-(dast|static)-[^\]]*\].*?^git worktree prune 2>\/dev\/null \|\| true\n\n/, ''),
        rule('the SonarQube issue counts are those of the branch of the build', /(api\/issues\/search\?componentKeys=[^&"']*)&branch=[^&"']*/, '$1'),
        rule('pull request decoration reads the open SonarQube issues of the branch',
                /(?ms)^withCredentials \[[^\]\n]*\]\nsh \[SonarQube: open issues of the analysed branch\] .*?\n\n/, ''),
        rule('a SonarQube that does not answer the issue search is logged', /(?m)^\[error\] \[HTTP\] SonarQube: open issues of the analysed branch.*\n/, ''),
        rule('pull request decoration logs what it did', /(?m)^(\[error\] \[HTTP\] Bitbucket: (find open pull request|comments of pull request)|\[SECURITY-PR\]|\[DAST-PR\]).*\n/, ''),
        rule('SAST reads the findings and their recommended fix from the AppScan API after the reports', /(?m)^sh set \+x\ncurl [^\n]*\/api\/v4\/Issues\/[^\n]*\n/, ''),
        rule('pull request decoration reads the files a pull request changes and warns when it cannot', /(?m)^(\[error\] \[HTTP\] Bitbucket: files changed in pull request|\[WARN\] \[SECURITY-PR\]).*\n/, ''),
        rule('SAST logs what it read from the AppScan API', /(?m)^(\[WARN\] )?\[SAST\] [^\n]*(read from the AppScan API|AppScan API Issues\/|from the AppScan API \()[^\n]*\n/, '')
]

Closure normalise = { String text, List<Map> rules -> ScenarioRunner.normalise(text, stackRules + rules) }

Map baselines = [
        b815d55: [recorded: 'on the library before the OneView integration', record: 'record it on b815d55 with RECORD_GOLDEN=1', rules: oneViewRules],
        nexusiq: [recorded: 'when the Nexus IQ GoldenFix pipeline was added', record: 'record it with RECORD_GOLDEN=1 and review it', rules: []],
        catalog: [recorded: 'when shared version catalogs were added', record: 'record it with RECORD_GOLDEN=1 and review it', rules: []],
        parallel: [recorded: 'when the static analysis and the tests with DAST became parallel branches', record: 'record it with RECORD_GOLDEN=1 and review it', rules: []]
]

List<Map> specs = [
        [name: 'security pipeline, green', var: 'devSecOpsSecurityPipeline', fail: false, job: 'CertScanner-security-pipeline', buildNumber: 212,
         params: [RUN_EXTENDED_PIPELINE: true], executed: staticStages, skipped: [STATIC], result: 'SUCCESS', post: ['always', 'success'],
         coverageMinLine: 75],
        [name: 'security pipeline, orange', var: 'devSecOpsSecurityPipeline', fail: true, job: 'CertScanner-security-pipeline', buildNumber: 213,
         params: [RUN_EXTENDED_PIPELINE: true], executed: staticStages, skipped: [STATIC], result: 'UNSTABLE', post: ['always', 'unstable']],
        [name: 'security pipeline, Maven artifact to Nexus', var: 'devSecOpsSecurityPipeline', fail: false, job: 'CertScanner-security-pipeline',
         buildNumber: 214, params: [RUN_EXTENDED_PIPELINE: false], executed: staticStages, skipped: [STATIC], result: 'SUCCESS',
         post: ['always', 'success'], mavenVmDelivery: true],
        [name: 'SAST pipeline, green', var: 'devSecOpsSASTScanningPipeline', fail: false, job: 'CertScanner-sast-pipeline', buildNumber: 87,
         params: [:], executed: [MONITOR, BUILD, SAST], skipped: [], result: 'SUCCESS', post: ['always', 'success']],
        [name: 'SAST pipeline, orange', var: 'devSecOpsSASTScanningPipeline', fail: true, job: 'CertScanner-sast-pipeline', buildNumber: 88,
         params: [:], executed: [MONITOR, BUILD, SAST], skipped: [], result: 'UNSTABLE', post: ['always', 'unstable']],
        [name: 'Nexus IQ GoldenFix pipeline, green', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: false, job: 'CertScanner-nexusiq-pipeline', buildNumber: 61,
         params: [:], executed: [MONITOR, BUILD, NIQ], skipped: [], result: 'SUCCESS', post: ['always', 'success'], baseline: 'nexusiq'],
        [name: 'Nexus IQ GoldenFix pipeline, orange', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: true, goldenFix: true, job: 'CertScanner-nexusiq-pipeline',
         buildNumber: 62, params: [:], executed: [MONITOR, BUILD, NIQ], skipped: [], result: 'UNSTABLE', post: ['always', 'unstable'], baseline: 'nexusiq'],
        [name: 'Nexus IQ GoldenFix pipeline, build failure', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: false, breakBuild: true, job: 'CertScanner-nexusiq-pipeline',
         buildNumber: 63, params: [:], executed: [MONITOR], skipped: [NIQ], result: 'FAILURE', post: ['always', 'failure'], failedStage: BUILD, baseline: 'nexusiq'],
        [name: 'Nexus IQ GoldenFix pipeline, shared catalog', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: true, goldenFix: true, sharedCatalog: true,
         job: 'CertScanner-nexusiq-pipeline', buildNumber: 64, params: [:], executed: [MONITOR, BUILD, NIQ], skipped: [], result: 'UNSTABLE',
         post: ['always', 'unstable'], baseline: 'catalog'],
        [name: 'extended pipeline, green', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 118,
         params: [DEPLOY_HIGHER_ENV: true],
         executed: extendedStages, skipped: [DYNAMIC], result: 'SUCCESS', post: ['always', 'success'], dastDisabledFor: 'backend-api'],
        [name: 'extended pipeline, orange', var: 'devSecOpsExtendedPipeline', fail: true, job: 'CertScanner-extended-pipeline', buildNumber: 119,
         params: [DEPLOY_HIGHER_ENV: true],
         upstreamViolations: ['gui SAST (AppScan) high 3 > 0'], executed: extendedStages - [RELEASE, QC], skipped: [DYNAMIC, RELEASE, QC], result: 'UNSTABLE',
         post: ['always', 'unstable']],
        [name: 'full pipeline, green', var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', buildNumber: 341,
         params: [DEPLOY_HIGHER_ENV: true], executed: fullStages, skipped: [STATIC, DYNAMIC], result: 'SUCCESS', post: ['always', 'success'],
         files: ['devsecops_config.yaml': 'projects: [', 'config.yaml': 'projects: [']],
        [name: 'full pipeline, orange', var: 'devSecOpsPipeline', fail: true, job: 'CertScanner-devsecops-pipeline', buildNumber: 342,
         params: [DEPLOY_HIGHER_ENV: true], executed: fullStages - [RELEASE, QC], skipped: [STATIC, DYNAMIC, RELEASE, QC], result: 'UNSTABLE',
         post: ['always', 'unstable']],
        [name: 'full pipeline, green but QC not selected', var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', buildNumber: 343,
         params: [DEPLOY_HIGHER_ENV: false], executed: fullStages - [QC], skipped: [STATIC, DYNAMIC, QC], result: 'SUCCESS', post: ['always', 'success']],
        [name: 'full pipeline, stage failure', var: 'devSecOpsPipeline', fail: false, breakSast: true, job: 'CertScanner-devsecops-pipeline', buildNumber: 344,
         params: [DEPLOY_HIGHER_ENV: true], executed: [MONITOR, BUILD, UNIT, NIQ], skipped: [STATIC] + (declaredFull - [MONITOR, BUILD, UNIT, NIQ, SAST]),
         result: 'FAILURE', post: ['always', 'failure'], failedStage: SAST]
]

List<String> repositoryFiles = ['devsecops_config.yaml', 'config.yaml']
Closure verify = { Map spec ->
    Map r = runPipeline(spec)
    FakeScript j = r.j as FakeScript
    FakeCpsScript var = r.var as FakeCpsScript
    String html = r.html as String
    List<String> log = j.log
    String name = spec.name as String
    check("${name}: the pipeline var runs to the end under the sandbox", !r.crash, r.crash)
    if (spec.failedStage) {
        check("${name}: the stage fails, the pipeline stops and the report is still written", var.stageFailures.size() == 1
                && (var.stageFailures[0] as String).startsWith(spec.failedStage as String) && var.postFailures.isEmpty()
                && html.contains('<h2>Pipeline Stages</h2>') && j.calls.contains('publishHTML pipeline-report.html'),
                (var.stageFailures + var.postFailures).join('\n      '))
    }
    check("${name}: no stage threw and no post block failed", (spec.failedStage ? var.stageFailures.size() == 1 : var.stageFailures.isEmpty()) && var.postFailures.isEmpty(),
            (var.stageFailures + var.postFailures + ['--- last log lines ---'] + log.takeRight(12)).join('\n      '))
    check("${name}: no sandbox rejection anywhere in the build log", !log.any { it.contains('not permitted') || it.contains('RejectedAccess') },
            log.findAll { it.contains('not permitted') || it.contains('RejectedAccess') }.join('\n      '))
    check("${name}: stages ${spec.skipped ? 'run and skipped' : 'run'} as the declarative pipeline defines them",
            var.executedStages == spec.executed && var.skippedStages == spec.skipped,
            "executed ${var.executedStages}, skipped ${var.skippedStages}")
    check("${name}: build result ${spec.result} and post conditions ${spec.post}", j.currentBuild.currentResult == spec.result && var.postConditions == spec.post,
            "${j.currentBuild.currentResult} ${var.postConditions}")
    check("${name}: the full report template is rendered, archived and published", html.contains('<h2>Pipeline Stages</h2>')
            && !log.any { it.contains('Full report could not be built') || it.startsWith('[WARN] Could not') }
            && j.calls.any { it.startsWith('archiveArtifacts') && it.contains('report/pipeline-report.html') } && j.calls.contains('publishHTML pipeline-report.html'),
            log.findAll { it.contains('[REPORT]') || it.startsWith('[WARN]') }.join('\n      '))
    // The symptom of a split devSecOpsApi instance: the template renders, but the stage flow is
    // built from an untouched PipelineState, so every box is grey SKIP and the coverage is unknown.
    String flow = html.contains('<h2>Pipeline Stages</h2>') && html.contains("<div style='flex:1.4;min-width:620px;'>")
            ? html.substring(html.indexOf('<h2>Pipeline Stages</h2>'), html.indexOf("<div style='flex:1.4;min-width:620px;'>"))
            : ''
    int greyBoxes = flow.split('>SKIP<', -1).length - 1
    // Only a stage that never started may stay grey, and a blocked or skipped one is amber or red,
    // so the bound holds from both sides: a state that was never filled turns every box grey.
    int expectedGrey = ((spec.skipped as List) - NODE - STATIC - DYNAMIC - (spec.executed.contains(STATIC) ? [NIQ, SAST, SONAR] : [])
            - (spec.executed.contains(DYNAMIC) ? testStages : [])).size() * 2
    check("${name}: the stage flow shows what the stages collected, not empty grey boxes",
            flow && greyBoxes <= expectedGrey && flow.contains('&#9201;')
                    && (!spec.executed.contains(UNIT) || !flow.contains('not measured')),
            "grey SKIP boxes ${greyBoxes} (at most ${expectedGrey}), any stage duration ${flow.contains('&#9201;')}, coverage not measured ${flow.contains('not measured')}")
    check("${name}: release gate verdict written for the downstream pipeline", j.jsonFiles.containsKey('release-gate.json')
            && ((j.jsonFiles['release-gate.json'] as Map).allowed as boolean) == (spec.result == 'SUCCESS' && !spec.failedStage),
            j.jsonFiles['release-gate.json'])
    List<String> metricLines = influxOf(j).readLines()
    String nexusIqTotal = metricLines.find { it.startsWith('vulnerabilities,') && it.contains(',scanner=nexusiq ') }
    if (nexusIqTotal) {
        Closure countOf = { String line, String key -> java.util.regex.Matcher m = (line =~ /[ ,]${key}=(\d+)i/); m.find() ? (m.group(1) as int) : -1 }
        List<String> nexusIqFindings = metricLines.findAll { it.startsWith('security_findings,') && it.contains(',scanner=niq,') }
        Map total = ['critical', 'high', 'medium'].collectEntries { String k -> [(k): countOf(nexusIqTotal, k)] }
        Map summed = ['critical', 'high', 'medium'].collectEntries { String k -> [(k): nexusIqFindings.sum(0) { String line -> countOf(line, k) }] }
        check("${name}: the security_findings points of Nexus IQ add up to the vulnerabilities point of Nexus IQ", total == summed,
                "vulnerabilities ${total}, security_findings ${summed}")
    }
    if (spec.executed.contains(UNIT)) {
        check("${name}: each service reads its coverage reports with one scan and the report lists them",
                j.calls.count { it.startsWith('sh [Coverage: find and read the reports] ') } == 2
                        && html.contains('<th>Report</th>') && html.contains('>All services</div>'),
                j.calls.findAll { it.contains('Coverage: ') })
    }
    if (spec.coverageMinLine) {
        check("${name}: the report shows and checks the coverage required by the global settings of OneView (${spec.coverageMinLine}%)",
                html.contains("required&nbsp;<b>${spec.coverageMinLine}%</b>".toString()) && !html.contains('required&nbsp;<b>60%</b>'),
                'the report does not use the required coverage from the OneView defaults')
    }
    if (spec.var == 'devSecOpsSecurityPipeline') {
        String description = "Trigger the extended pipeline named in jenkins.pipeline.extendedPipeline of the service in ${spec.keyless ? 'devsecops_config.yaml or config.yaml' : 'OneView'}"
        check("${name}: the RUN_EXTENDED_PIPELINE parameter says where jenkins.pipeline.extendedPipeline is set",
                var.parameterDescriptions.RUN_EXTENDED_PIPELINE == description, var.parameterDescriptions.RUN_EXTENDED_PIPELINE)
        boolean triggered = j.calls.contains('build DevSecOps/CertScanner-extended-pipeline (no wait)')
        if ((spec.params as Map).RUN_EXTENDED_PIPELINE) {
            check("${name}: the extended pipeline is started without waiting", triggered, j.calls.findAll { it.startsWith('build ') })
        } else {
            check("${name}: the extended pipeline is not started when the parameter is not selected", !triggered
                    && log.any { it.contains('Skipping extended pipeline') }, j.calls.findAll { it.startsWith('build ') })
        }
    }
    if (spec.var == 'devSecOpsSASTScanningPipeline') {
        check("${name}: sources are checked out and only the SAST content is reported", j.calls.contains('checkout')
                && !html.contains('Nexus IQ') && !html.contains('Release policy'), 'checkout missing or foreign content rendered')
    }
    if (spec.var == 'devSecOpsNexusIqGoldenFixPipeline') {
        List builds = j.calls.findIndexValues { it.startsWith('sh [Gradle: ') || it.startsWith('sh [Maven: ') }
        List evaluatedAt = r.evaluatedAt as List
        if (spec.failedStage) {
            check("${name}: the failed build stops the pipeline before Nexus IQ evaluates anything and the report keeps the Nexus IQ summary row",
                    j.calls.contains('checkout') && builds.size() == 1 && evaluatedAt.isEmpty() && html.contains("<td style='padding:10px 14px;font-weight:600;'>Nexus IQ</td>"),
                    "builds at ${builds}, Nexus IQ evaluations at ${evaluatedAt}")
        } else {
            check("${name}: sources are checked out and every service is built before Nexus IQ evaluates the built artifacts",
                    j.calls.contains('checkout') && builds.size() == 2 && evaluatedAt.size() == 2 && (builds.max() as int) < (evaluatedAt.min() as int),
                    "builds at ${builds}, Nexus IQ evaluations at ${evaluatedAt}")
        }
        check("${name}: the report shows the Nexus IQ scan${spec.goldenFix ? ', the Nexus IQ GoldenFix card and the pull request' : ''}, no SAST, SonarQube or release policy, and the missing test banner only when the scan did not run",
                html.contains('Nexus IQ') && (!spec.goldenFix || (html.contains('<h2>Nexus IQ GoldenFix</h2>') && html.contains(GoldenFixResponder.PULL_REQUEST_URL)))
                        && !html.contains('SAST (AppScan)') && !html.contains('SCA (SonarQube)') && !html.contains('Release policy')
                        && html.contains('TESTS WERE NOT EXECUTED') == (spec.failedStage != null),
                'Nexus IQ content missing or foreign content rendered')
        String influx = influxOf(j)
        String goldenFixLine = influx.readLines().find { it.startsWith('goldenfix,') && it.contains(',module=gui,') } ?: ''
        check("${name}: the metrics carry project=CertScannernexusiq and variant=nexusiq${spec.goldenFix ? ', the goldenfix point the pull request' : ''}",
                influx.contains('pipeline_run,project=CertScannernexusiq,env=test,variant=nexusiq,') && influx.contains('dora,project=CertScannernexusiq,env=test,variant=nexusiq ')
                        && (!spec.goldenFix || (goldenFixLine.contains(',status=PR_CREATED ')
                        && goldenFixLine.contains(",pr_url=\"${GoldenFixResponder.PULL_REQUEST_URL}\",pr_title=\"GoldenFix-${GoldenFixResponder.STAMP}\" ".toString()))),
                influx.readLines().findAll { it.startsWith('pipeline_run,') || it.startsWith('goldenfix,') }.join('\n      '))
    }
    if (spec.goldenFix) {
        List<String> gfCalls = r.goldenFixCalls as List<String>
        check("${name}: the Nexus IQ policy violation raises the golden pull request through the real vars and production wiring",
                gfCalls.contains("PR GoldenFix-${GoldenFixResponder.STAMP} refs/heads/GoldenFix-${GoldenFixResponder.STAMP} -> refs/heads/develop".toString())
                        && gfCalls.contains('BATCH sh with 4 request(s)')
                        && ['GoldenFix: prepare worktree', 'GoldenFix: pre-check gradle', 'GoldenFix: git push', 'GoldenFix: remove worktree'].every { String step -> j.calls.any { it.startsWith("sh [${step}".toString()) } }
                        && log.contains("[GOLDENFIX] GoldenFix pull request GoldenFix-${GoldenFixResponder.STAMP} raised: ${GoldenFixResponder.PULL_REQUEST_URL}".toString()),
                (gfCalls + log.findAll { it.startsWith('[GOLDENFIX]') }).join('\n      '))
    }
    if (spec.sharedCatalog) {
        List<String> gfCalls = r.goldenFixCalls as List<String>
        String catalog = (j.files["${GoldenFixResponder.WORKTREE}/gradle/libs.versions.toml".toString()] ?: '') as String
        List<String> preChecks = j.calls.findAll { it.startsWith('sh [GoldenFix: pre-check gradle]') }
        check("${name}: both services raise the shared catalog in one pull request and the second service adds a comment listing its upgrade",
                catalog.contains('jackson = "2.13.5"') && catalog.contains('strictly = "4.1.108.Final"') && catalog.contains('commons-text = "org.apache.commons:commons-text:1.9"')
                        && catalog.contains('version = "42.5.6"') && gfCalls.count { it.startsWith('PR ') } == 1
                        && gfCalls.contains('COMMENT ### GoldenFix for backend-api')
                        && influxOf(j).readLines().any { it.startsWith('goldenfix,') && it.contains(',module=backend-api,') && it.contains(',status=PR_UPDATED ') }
                        && log.contains("[GOLDENFIX] GoldenFix pull request GoldenFix-${GoldenFixResponder.STAMP} updated with 1 change(s): ${GoldenFixResponder.PULL_REQUEST_URL}".toString()),
                (gfCalls + log.findAll { it.startsWith('[GOLDENFIX]') }).join('\n      '))
        check("${name}: the catalog change is verified in the build that uses it, never in the gradle folder",
                preChecks.size() == 2 && preChecks.every { it.contains("cd '${GoldenFixResponder.WORKTREE}' || exit 1".toString()) }, preChecks)
    }
    if (spec.dastDisabledFor) {
        check("${name}: DAST is marked as not required for the project that does not configure it and nothing is blocked",
                log.any { it.contains('[DAST] Disabled in config') } && html.contains('NOT REQUIRED')
                        && ((j.jsonFiles['release-gate.json'] as Map).allowed as boolean),
                log.findAll { it.startsWith('[DAST]') }.join(' | '))
    }
    if (spec.var == 'devSecOpsExtendedPipeline') {
        check("${name}: the run-state file, the release gate and the build tag are taken from the security pipeline", j.calls.contains('copyArtifacts DevSecOps/CertScanner-security-pipeline filter=pipeline-config.yaml,release-gate.json,build.tag'),
                j.calls.findAll { it.startsWith('copyArtifacts') })
    }
    if (spec.mavenVmDelivery) {
        String deliveredTag = (j.files['build.tag'] ?: '') as String
        check("${name}: the Maven artifact is deployed to the Nexus snapshot repository under the tag of the run, which goes to build.tag and not into the run-state file",
                j.calls.count('writeYaml pipeline-config.yaml') == 1 && deliveredTag ==~ /${spec.buildNumber}-\d{8}-\d{6}/
                        && j.log.any { it.contains('deploy:deploy-file') && it.contains("'-Dversion=${deliveredTag}-SNAPSHOT' '-Dpackaging=jar' '-Dfile=target/certscanner-api.jar'") }
                        && !j.log.any { it.contains('-Dclassifier=') },
                [deliveredTag] + j.log.findAll { it.contains('Execute Maven Command') })
    }
    if (spec.executed.contains(SNAPSHOT) && !spec.mavenVmDelivery) {
        check("${name}: the OpenShift image is built and copied to Nexus", (r.openshift as FakeOpenShift).calls.contains('startBuild certscanner-api')
                && log.any { it.contains('Copying blob') }, (r.openshift as FakeOpenShift).calls)
    }
    if (spec.executed.contains(RD) && spec.var == 'devSecOpsPipeline') {
        check("${name}: the VM project is deployed through UrbanCode Deploy", j.calls.count('step UCDeployPublisher') == 2, j.calls.findAll { it.startsWith('step') })
    }
    if (spec.executed.contains(QC)) {
        check("${name}: QC deployment rolls out the OpenShift image built in this run", (r.openshift as FakeOpenShift).calls.any { it.startsWith('rollout status') }
                && !log.any { it.contains('buildTag) is not defined') }, (r.openshift as FakeOpenShift).calls)
    }
    if (spec.fail && spec.executed.contains(UNIT)) {
        check("${name}: failing unit tests keep the pipeline going with an orange stage", html.contains('Unit tests failed (script returned exit code 1)'),
                'unit test failure not reported')
    }
    if (spec.var in ['devSecOpsPipeline', 'devSecOpsSecurityPipeline']) {
        String tools = j.env.vars.APPSCAN_TOOLS_DIR
        check("${name}: the AppScan client is unpacked in the workspace", tools == '/ws/.appscan-tools', tools)
    }
    if (spec.executed.contains(STATIC)) {
        List<String> calls = j.calls
        int copied = calls.findIndexOf { it.startsWith('sh [Copy the workspace for the SAST compile and scan]') }
        int started = calls.indexOf('parallel 4')
        List<Integer> branches = [UNIT, NIQ, SAST, SONAR].collect { calls.indexOf("withEnv [DSO_PARALLEL_BRANCH=${it}]".toString()) }
        int intoCopy = calls.indexOf("withEnv [WORKSPACE=${sastCopy}]".toString())
        int removed = calls.findIndexOf { it.startsWith('sh [Remove the SAST copy of the workspace]') }
        int chmod = calls.indexOf('sh chmod +x "$APPSCAN_BIN_DIR/gradle"')
        List<Integer> queued = calls.findIndexValues { it.endsWith('/api/v4/Scans/Sast"') } as List<Integer>
        List<Integer> prepared = calls.findIndexValues { it.startsWith('withEnv [APPSCAN_CMD_RUNTIME=') && it.contains("APPSCAN_SOURCE_DIR_RUNTIME=${sastCopy},".toString()) } as List<Integer>
        int polled = calls.findIndexOf { it.contains('/api/v4/Scans/Sast/') }
        List<Integer> evaluated = r.evaluatedAt as List<Integer>
        int sonarFirst = calls.findIndexOf { it.startsWith('sh [Gradle: sonarqube]') }
        int unitLast = calls.findLastIndexOf { it.startsWith('sh [Maven: test') || it.startsWith('sh [Gradle: test') }
        check("${name}: the workspace is copied before Unit tests, Nexus IQ, SAST and SonarQube start in parallel, SAST compiles, prepares and queues every scan in the copy, removes it and only then polls, and SonarQube runs after the unit tests",
                copied >= 0 && copied < started && branches == branches.sort(false) && branches[0] > started && evaluated && evaluated.min() > branches[1]
                        && chmod > branches[2] && chmod < intoCopy && prepared.size() == 2 && prepared.min() > intoCopy && queued.size() == 2 && queued.max() < removed
                        && removed < polled && sonarFirst > branches[3] && sonarFirst > unitLast,
                [copied: copied, started: started, branches: branches, chmod: chmod, intoCopy: intoCopy, prepared: prepared, queued: queued, removed: removed, polled: polled,
                 evaluated: evaluated, sonar: sonarFirst, unitLast: unitLast])
        List<String> staticLines = log.findAll { it.startsWith('[STATIC] ') || it.startsWith('[PARALLEL] Static analysis') }
        check("${name}: the log says that the four stages run at the same time by default, where SAST works, and how long they took one after another and in parallel",
                staticLines.size() == 4 && staticLines[0] == '[STATIC] Unit tests, Nexus IQ, SAST and SonarQube run at the same time (staticAnalysis.parallel: true, the default)'
                        && staticLines[1] == "[STATIC] SAST compiles and prepares its scans in a copy of the workspace (${sastCopy}), so Unit tests, Nexus IQ and SonarQube run at the same time on the workspace".toString()
                        && staticLines[2] == '[PARALLEL] Static analysis: Unit tests, Dependencies scan (Nexus IQ), SAST - Static Application Security Tests - HCL AppScan, SCA (SonarQube) run at the same time'
                        && staticLines[3] ==~ /\[PARALLEL\] Static analysis: one after another \d+s, in parallel \d+s.*/,
                staticLines)
        check("${name}: the report has no table of the time one after another and in parallel, and the Manual Actions vs DevSecOps table marks the steps of the static analysis as parallel",
                !html.contains("<section id='parallel-stages'") && html.contains('>Static analysis: runs in parallel</span>') && html.contains('>the longest of the '),
                'the parallel steps are not marked in the report')
    }
    if (spec.executed.contains(DYNAMIC)) {
        List<String> calls = j.calls
        List<Integer> branches = testStages.collect { calls.indexOf("withEnv [DSO_PARALLEL_BRANCH=${it}]".toString()) }
        List<String> dynamicLines = log.findAll { it.startsWith('[DYNAMIC] ') || it.startsWith('[PARALLEL] Dynamic analysis') }
        check("${name}: Regression, Smoke and Performance tests and DAST run as four parallel branches, which the log and the report name with their times",
                branches.every { it >= 0 } && branches == branches.sort(false)
                        && dynamicLines[0] == '[DYNAMIC] Regression, Smoke and Performance tests and DAST run at the same time (dynamicAnalysis.parallel: true, the default)'
                        && dynamicLines[1] == "[PARALLEL] Dynamic analysis: ${testStages.join(', ')} run at the same time".toString()
                        && dynamicLines[2] ==~ /\[PARALLEL\] Dynamic analysis: one after another \d+s, in parallel \d+s.*/
                        && html.contains('>Dynamic analysis: runs in parallel</span>'),
                [branches: branches, log: dynamicLines])
    }
    Map loggedResults = [:]
    (log.join('\n') =~ /\[STAGE\]  ([^\n]+)\n\[RESULT\] (\S+)/).each { loggedResults[it[1] as String] = it[2] as String }
    Closure loggedRun = { String stage -> loggedResults.containsKey(stage) && !(loggedResults[stage] in ['SKIP', 'NOT_REQUIRED', 'BLOCKED']) }
    List<String> expectedRows = [[NODE, 'Node.js build'], [BUILD, 'Build'], [UNIT, 'Unit test with Code Coverage'], [NIQ, 'Nexus IQ'], [SAST, 'SAST'], [SONAR, 'SonarQube'],
                                 [SNAPSHOT, 'Nexus delivery (snapshot)'], [PULL, 'Nexus delivery (download)'], [RD, 'Lower region test deployment'],
                                 [REGRESSION, 'Regression tests'], [SMOKE, 'Smoke tests'], [PERFORMANCE, 'Performance tests'], [DAST, 'DAST'],
                                 [RELEASE, 'Nexus delivery (release)'], [QC, 'Higher region test deployment']].findAll { List step ->
        step[0] == BUILD ? (loggedResults.containsKey(BUILD) && !(loggedResults[BUILD] in ['SKIP', 'BLOCKED'])) : loggedRun(step[0] as String)
    }.collect { it[1] as String }
    int manualAt = html.indexOf("<section id='manual-vs-automated'")
    String manualSection = manualAt < 0 ? '' : html.substring(manualAt, html.indexOf('</section>', manualAt))
    List<String> manualRows = (manualSection =~ /<tr><td style='[^']*'><span style='font-weight:600;'>([^<]+)<\/span>/).collect { it[1] as String }
    List<String> listedServices = (manualSection =~ /padding-left:14px;'>(\S+) /).collect { it[1] as String }
    check("${name}: the report ends with the Manual Actions vs DevSecOps table, one line per step that ran in the order of the pipeline (${expectedRows.join('; ')}), the time gained, the summary in seconds and minutes and, with several services, every service under every step",
            manualRows == expectedRows && manualAt > html.indexOf("<div style='flex:1.4;min-width:620px;'>") && manualAt < html.indexOf('</body>')
                    && manualSection.contains('<h2>Manual Actions vs DevSecOps</h2>') && manualSection.contains('>Summary</td>')
                    && manualSection.contains('>Time gained</th>') && !manualSection.contains('DevSecOps gained') && !manualSection.contains('Recalculated on every run')
                    && manualSection =~ /\d s = \d+ min<\/span>/ && (!expectedRows.contains('SAST') || manualSection.contains('>the run + 10 min manual trigger</span>'))
                    && listedServices.size() == listedServices.unique(false).size() * manualRows.size() && listedServices.unique(false).size() != 1
                    && (!expectedRows.contains('DAST') || manualSection.contains('>the run + 6 h ticket to CyberTrack</span>'))
                    && (!expectedRows.contains('Higher region test deployment') || manualSection.contains('>the run + 0 min ticket to SRM</span>')),
            [rows: manualRows, expected: expectedRows, services: listedServices, results: loggedResults])
    Map files = (spec.files ?: [:]) as Map
    List<String> probed = j.probes.findAll { it.tokenize(' ').last() in repositoryFiles }
    if (files && !spec.keyless) {
        check("${name}: a run with a key never looks at devsecops_config.yaml or config.yaml, even when both are in the workspace", !probed, probed)
    }
    if (spec.keyless) {
        String file = files.containsKey(repositoryFiles[0]) ? repositoryFiles[0] : repositoryFiles[1]
        List<String> evidence = influxOf(j).readLines().findAll { it.startsWith('build_evidence,') }
        check("${name}: OneView is not read and the console says which file was read${files.size() > 1 ? ' and which was not' : ''}",
                !j.calls.any { it.contains('/api/dso/config/') || it.contains('ONEVIEW_KEYS') }
                        && log.contains("[INIT] No pipeline key in the Jenkinsfile: the services come from ${file} in the repository, the global settings from the library (resources/com/bbh/devsecops/defaults.yaml)".toString())
                        && (files.size() < 2 || log.contains("[INIT] ${repositoryFiles.join(' and ')} are both in the repository: ${file} is read, ${repositoryFiles[1]} is not".toString()))
                        && probed.containsAll(repositoryFiles.collect { "fileExists ${it}".toString() } + ["readYaml ${file}".toString()]),
                log.findAll { it.startsWith('[INIT] No pipeline key') || it.contains('are both in the repository') } + probed)
        check("${name}: the report header and every build_evidence line name ${file}",
                html.contains("<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>No pipeline key: settings from ${file} in the repository</div>".toString())
                        && evidence && evidence.every { it.contains(",config_file=\"${file}\"".toString()) },
                evidence)
    }
    List<String> ownKeys = spec.keyless ? [] : j.oneView.keySet().toList()
    List<Map> posts = r.reportPosts as List<Map>
    List<String> uploadLines = log.findAll { it.startsWith('[ONEVIEW] Uploading pipeline report ') }
    check(spec.keyless ? "${name}: a run without a pipeline key and without ONEVIEW_URL uploads no report"
            : "${name}: the report is uploaded once for each pipeline key of the Jenkinsfile, in their order, with nothing set but the address of OneView, and the keys are in the payloads only - " +
            "not in the log, the report, the release gate, the InfluxDB lines or the run-state file",
            posts*.key == ownKeys && posts.every { it.address == OneViewFixtures.ONEVIEW_URL && it.html == html } && (spec.keyless || ownKeys.size() == 2)
                    && uploadLines == ownKeys.collect { "[ONEVIEW] Uploading pipeline report to ${OneViewFixtures.ONEVIEW_URL}/api/dso/reports for job DevSecOps/${spec.job} build ${spec.buildNumber} with pipeline key ${OneViewFixtures.hint(it)}".toString() }
                    && ![log.join('\n'), html, influxOf(j), (j.files['release-gate.json'] ?: '') as String, (j.files['pipeline-config.yaml'] ?: '') as String].any { String text -> ownKeys.any { text.contains(it) } },
            "${posts*.key} | ${uploadLines}")
    List unhandled = j.unhandled.unique()
    if (unhandled) println "      note: steps not emulated by the fake Jenkins: ${unhandled}"
    Closure slugOf = { String text -> text.toLowerCase().replaceAll(/[^a-z0-9]+/, '-') }
    Map baseline = baselines[spec.baseline ?: 'b815d55'] as Map
    List<Map> rules = (spec.keyless ? (spec.baseline ? keylessRules : oneViewRules + keylessMarks) : baseline.rules) as List<Map>
    String actual = normalise(goldenView(r), baseRules)
    File diffFile = new File(goldenDir, "${slugOf(name)}.actual.txt")
    boolean sectionsDiffer = false
    if (spec.compare == 'sections') {
        File sequential = new File(goldenDir, "${slugOf(spec.golden as String)}.txt")
        Map expected = sectionsOf(normalise(sequential.text, oneViewRules + keylessMarks))
        Map got = sectionsOf(normalise(sequentialView(actual), oneViewRules + keylessMarks))
        boolean sameOutputs = ['release-gate.json', 'influx', 'report'].every { expected[it] == got[it] }
        boolean sameSteps = ['calls', 'log'].every { reordered(expected[it] as List<String>) == reordered(got[it] as List<String>) }
        sectionsDiffer = !(sameOutputs && sameSteps)
        if (sectionsDiffer) diffFile.text = actual else diffFile.delete()
        check("${name}: release-gate.json, the InfluxDB lines and the report equal those of ${spec.golden}", sameOutputs, "compare ${sequential} with ${diffFile}")
        check("${name}: the steps and the log are those of ${spec.golden} in another order, without the lines of the parallel stages and of the SAST copy of the workspace",
                sameSteps, "compare ${sequential} with ${diffFile}")
        if (!spec.baseline) return
    }
    boolean owner = spec.compare || !spec.golden
    File golden = new File(goldenDir, "${slugOf((owner ? name : spec.golden) as String)}.txt")
    if (owner && System.getenv('RECORD_GOLDEN') == '1') {
        golden.parentFile.mkdirs()
        golden.text = actual
        println "      note: golden output recorded at ${golden}"
    }
    if (!golden.exists()) {
        diffFile.text = actual
        check("${name}: a golden output recorded ${baseline.recorded} exists", false, "${golden} is missing; ${baseline.record}, or review ${diffFile} and rename it")
        return
    }
    boolean same = normalise(golden.text, rules) == normalise(actual, rules)
    if (same && !sectionsDiffer) diffFile.delete() else diffFile.text = actual
    check("${name}: the run matches the golden output recorded ${baseline.recorded}", same,
            "compare ${golden} with ${diffFile}")
}
specs.each(verify)

String readByMistake = 'projects:\n  gui:\n    appId: read-config-yaml-by-mistake\n'
[[from: 'full pipeline, orange', files: ['devsecops_config.yaml': null]],
 [from: 'full pipeline, green', files: ['devsecops_config.yaml': null, 'config.yaml': readByMistake], autoDetect: true],
 [from: 'security pipeline, orange', files: ['config.yaml': null]],
 [from: 'extended pipeline, orange', files: ['devsecops_config.yaml': null]],
 [from: 'SAST pipeline, green', files: ['config.yaml': null]],
 [from: 'Nexus IQ GoldenFix pipeline, orange', files: ['devsecops_config.yaml': null]]
].each { Map k ->
    Map base = specs.find { it.name == k.from } as Map
    verify(base + k + [keyless: true, golden: k.from, name: "${k.from}, without a key (${(k.files as Map).keySet().join(' and ')})".toString()])
}

Closure inParallel = { List stages -> stages.collectMany { it == NIQ ? [STATIC] : (it == REGRESSION ? [DYNAMIC] : (it in [UNIT, SAST, SONAR, SMOKE, PERFORMANCE, DAST] ? [] : [it])) } }
Closure skippedInParallel = { Map base ->
    List left = (base.skipped as List) - [STATIC, DYNAMIC]
    List declared = (base.var == 'devSecOpsExtendedPipeline' ? [] : [UNIT, NIQ, SAST, SONAR]) + ((base.executed as List).contains(REGRESSION) ? testStages : [])
    declared + left
}
['security pipeline, green', 'security pipeline, orange', 'full pipeline, green', 'full pipeline, orange', 'extended pipeline, green', 'extended pipeline, orange'].each { String from ->
    Map base = specs.find { it.name == from } as Map
    verify(base + [name: "${from}, parallel stages".toString(), parallel: true, golden: from, compare: 'sections',
                   executed: inParallel(base.executed as List), skipped: skippedInParallel(base)] + (from == 'full pipeline, orange' ? [baseline: 'parallel'] : [:]))
}
verify((specs.find { it.name == 'full pipeline, green' } as Map) + [name: 'full pipeline, green, parallel stages switched off by the environment',
                                                                     golden: 'full pipeline, green', parallel: true,
                                                                     env: [DSO_STATIC_ANALYSIS_PARALLEL: 'false', DSO_DYNAMIC_ANALYSIS_PARALLEL: 'false']])

Map formerNames = (specs.find { it.name == 'full pipeline, green' } as Map) + [name: 'full pipeline, green, on a Jenkins that sets only DSO_PORTAL_URL and DSO_PORTAL_AGENT',
                                                                               golden: 'full pipeline, green',
                                                                               env: [ONEVIEW_URL: null, DSO_PORTAL_URL: OneViewFixtures.ONEVIEW_URL, DSO_PORTAL_AGENT: 'linux-agent']]
verify(formerNames)
List<String> formerLines = ((runPipeline(formerNames).j as FakeScript).log.findAll { it.contains(' former name') }) as List<String>
check("${formerNames.name}: the console names each former variable once, with the way to rename it",
        formerLines == ['[ONEVIEW] DSO_PORTAL_URL is the former name of ONEVIEW_URL: it still works; rename it in Manage Jenkins > System > Global properties > Environment variables',
                        '[ONEVIEW] DSO_PORTAL_AGENT is the former name of ONEVIEW_AGENT: it still works; rename it in Manage Jenkins > System > Global properties > Environment variables'],
        formerLines)

Map noFile = runPipeline([name: 'full pipeline without a key and without a file', var: 'devSecOpsPipeline', keyless: true, fail: false,
                          job: 'CertScanner-devsecops-pipeline', buildNumber: 345, params: [DEPLOY_HIGHER_ENV: true]])
FakeCpsScript noFileVar = noFile.var as FakeCpsScript
FakeScript noFileJenkins = noFile.j as FakeScript
String noFileHtml = noFile.html as String
String neitherFile = "[INIT] No pipeline key in the Jenkinsfile, and neither devsecops_config.yaml nor config.yaml is at the root of the repository: " +
        "commit devsecops_config.yaml, which the product page of OneView downloads, or give the Jenkinsfile the pipeline key of the service " +
        "(a custom Jenkinsfile calls devSecOpsApi.configure('full', [pipelineKey: '<key from OneView>']) before devSecOpsApi.initialize())"
check('full pipeline without a key and without a file: the Monitor stage fails with the message that names both files, the report and the release gate are still written, and OneView is not read',
        !noFile.crash && noFileVar.stageFailures.size() == 1 && noFileVar.stageFailures[0].startsWith("${MONITOR}: ".toString()) && noFileVar.stageFailures[0].contains(neitherFile)
                && noFileJenkins.currentBuild.currentResult == 'FAILURE' && noFileVar.postConditions.contains('always')
                && noFileHtml.contains('<h2>Pipeline Stages</h2>') && noFileHtml.contains('No pipeline key: settings from devsecops_config.yaml or config.yaml in the repository</div>')
                && noFileJenkins.jsonFiles.containsKey('release-gate.json') && !noFileJenkins.calls.any { it.contains('/api/dso/config/') },
        [noFile.crash] + noFileVar.stageFailures + [noFileJenkins.currentBuild.currentResult])

Closure focused = { Map spec ->
    Map r = runPipeline([var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', params: [DEPLOY_HIGHER_ENV: true]] + spec)
    check("${spec.name}: the pipeline var runs to the end without a stage failure", !r.crash && (r.var as FakeCpsScript).stageFailures.isEmpty(),
            ([r.crash] + (r.var as FakeCpsScript).stageFailures).join('\n      '))
    return r
}
Closure resultOf = { Map r, String stage ->
    List<String> log = (r.j as FakeScript).log
    int i = log.indexOf("[STAGE]  ${stage}".toString())
    i < 0 ? 'not run' : log[i + 1].replace('[RESULT] ', '')
}
Closure sonarCfgOf = { Map p, String project -> (((p[project] as Map).tools as Map).sonar as Map) }
Closure rdOf = { Map p, String project, String target -> ((((p[project] as Map).deploy as Map)[target] as Map).rd as Map) }

String guiKey = 'bbh_b81fbc9f-39c1-8eb4-38b5-b702268969b9'
String backendKey = 'bbh_0c1d2e3f-4a5b-6c7d-8e9f-a0b1c2d3e4f5'
Closure perServiceSettings = { Map p ->
    ((p.gui as Map).asoc as Map).token = 'gui-appscan-key-secret'
    ((p.gui as Map).sast as Map).remove('scanName')
    ((p['backend-api'] as Map).asoc as Map).keyId = backendKey
    ((p['backend-api'] as Map).sast as Map).scanName = 'CertScanner Backend.Nightly'
}
Map perService = focused([name: 'full pipeline, AppScan settings per service', buildNumber: 351, configure: perServiceSettings])
FakeScript perServiceJenkins = perService.j as FakeScript
String functionalKey = 'bbh_asoc-functional-user-key'
Closure loginsOf = { FakeScript j ->
    j.calls.findIndexValues { it.contains('Account/ApiKeyLogin') }.collect { idx -> j.calls.subList(0, idx as int).findAll { it.startsWith('withCredentials [') }.last() }
}
List apiLogins = loginsOf(perServiceJenkins)
String loginBody = perServiceJenkins.files['/ws@tmp/asoc-api-key-login.json'] ?: ''
List keyWarnings = perServiceJenkins.log.findAll { it.startsWith('[WARN] ') && it.contains('the AppScan on Cloud API key of the functional user is read only from asoc.keyId and asoc.token') }
check('full pipeline, AppScan settings per service: SAST and DAST sign in once with the API key of the functional user from the library defaults, '
        + 'the key ID and the Secret text a service sets are ignored and named in a warning',
        apiLogins == ['withCredentials [asoc-functional-user-secret]']
                && loginBody == "{\"KeyId\":\"${functionalKey}\",\"KeySecret\":\"fake-APPSCAN_KEY_SECRET\"}".toString()
                && !perServiceJenkins.calls.any { it.contains(guiKey) || it.contains(backendKey) || it.contains('gui-appscan-key-secret') || it.contains('hcl-app-scan-acount') }
                && keyWarnings.size() == 2 && keyWarnings[0].startsWith('[WARN] gui: asoc.keyId, asoc.token in OneView ignored')
                && keyWarnings[1].startsWith('[WARN] backend-api: asoc.keyId in OneView ignored'),
        apiLogins + [loginBody] + keyWarnings)
List queued = perService.sastQueued as List
String dastPayload = perServiceJenkins.files.find { it.key.endsWith('dast_payload.json') }?.value ?: ''
check('full pipeline, AppScan settings per service: sast.scanName names the SAST scan of its service, sanitised, and a service without it keeps <scan name>-sast; '
        + 'the IRX, the scan ID file, the report names and the DAST scan name stay as they were',
        queued == ['/ws/certscanner-gui.irx certscanner-gui-sast 209f44ac-dd06-4ca0-884e-d944904f8020', '/ws/certscanner-backend.irx certscanner-backend-nightly 209f44ac-dd06-4ca0-884e-d944904f8021']
                && ['scan-sast-certscanner-gui.id', 'scan-sast-certscanner-backend.id'].every { String id -> perServiceJenkins.files.keySet().any { it.endsWith(id) } }
                && ['appscan-report-certscanner-backend.html', 'appscan-dast-report-certscanner-backend.html'].every { perServiceJenkins.files.containsKey(it) }
                && dastPayload.contains('"ScanName": "certscanner-backend-dast_351"'),
        queued + [dastPayload.readLines().find { it.contains('ScanName') }])

Closure badgesOf = { Map r -> ((r.html as String) =~ /project=([^&']+)&metric=[^&']+&token=([^&']+)'/).collect { "${it[1]} ${it[2]}".toString() }.unique() }
Map badges = focused([name: 'full pipeline, SonarQube badge token from credentials', buildNumber: 352, configure: { Map p ->
    sonarCfgOf(p, 'gui').badgeCredentialsId = 'sonar-badge-token'
    sonarCfgOf(p, 'gui').badgeToken = 'gui-literal-badge-token'
    sonarCfgOf(p, 'backend-api').badgeToken = 'backend-literal-badge-token'
}])
check('full pipeline, SonarQube badge token from credentials: tools.sonar.badgeCredentialsId is read as Secret text and wins over a literal badgeToken, which is used without it',
        (badges.j as FakeScript).calls.count('withCredentials [sonar-badge-token]') == 1
                && badgesOf(badges) == ['cert-scanner-gui fake-SONAR_BADGE_TOKEN', 'cert-scanner-backend backend-literal-badge-token'],
        badgesOf(badges))
Map missingBadge = focused([name: 'full pipeline, SonarQube badge credentials missing', buildNumber: 353, missingCredentials: ['sonar-badge-token'], configure: { Map p ->
    sonarCfgOf(p, 'gui').badgeCredentialsId = 'sonar-badge-token'
    sonarCfgOf(p, 'gui').badgeToken = 'gui-literal-badge-token'
    sonarCfgOf(p, 'backend-api').badgeCredentialsId = 'sonar-badge-token'
}])
List sonarLines = (missingBadge.j as FakeScript).log.findAll { it.startsWith('[SONAR] Badge token') }
String notFound = "[SONAR] Badge token credentials sonar-badge-token could not be read (Could not find credentials entry with ID 'sonar-badge-token')"
check('full pipeline, SonarQube badge credentials missing: the run says so, falls back to the literal badgeToken or shows no badges, and the SCA stage result does not change',
        sonarLines == ["${notFound} - the badges use tools.sonar.badgeToken".toString(), "${notFound} - the report shows no SonarQube badges".toString()]
                && badgesOf(missingBadge) == ['cert-scanner-gui gui-literal-badge-token']
                && resultOf(missingBadge, SONAR) == 'PASS' && resultOf(badges, SONAR) == 'PASS',
        sonarLines + badgesOf(missingBadge) + [resultOf(missingBadge, SONAR)])

String pinnedTag = '340-20261020-101500'
Map noDelivery = focused([name: 'full pipeline, Nexus delivery disabled for every service', buildNumber: 354, configure: { Map p ->
    rdOf(p, 'gui', 'vm').nexusDeliveryEnabled = false
    rdOf(p, 'backend-api', 'openshift').nexusDeliveryEnabled = false
    rdOf(p, 'backend-api', 'openshift').buildTag = pinnedTag
}])
FakeScript noDeliveryJenkins = noDelivery.j as FakeScript
FakeOpenShift noDeliveryOpenShift = noDelivery.openshift as FakeOpenShift
check('full pipeline, Nexus delivery disabled for every service: the snapshot stage is NOT_REQUIRED, publishes nothing and still reports the build and the unit tests',
        resultOf(noDelivery, SNAPSHOT) == 'NOT_REQUIRED'
                && noDeliveryJenkins.log.containsAll(['[NEXUS] gui: Nexus delivery is disabled by deploy.vm.rd.nexusDeliveryEnabled - the artifact is not published',
                                                      '[NEXUS] backend-api: Nexus delivery is disabled by deploy.openshift.rd.nexusDeliveryEnabled - the artifact is not published'])
                && !noDeliveryOpenShift.calls.any { it.startsWith('startBuild') } && !noDeliveryJenkins.calls.any { it.contains('skopeo copy') }
                && ['reportBuild', 'reportUnitTest'].every { noDeliveryJenkins.calls.contains(it) },
        [resultOf(noDelivery, SNAPSHOT)] + noDeliveryOpenShift.calls.findAll { it.startsWith('startBuild') })
check('full pipeline, Nexus delivery disabled for every service: a pinned rd.buildTag is still deployed to RD and QC',
        resultOf(noDelivery, RD) == 'PASS' && resultOf(noDelivery, QC) == 'PASS'
                && noDeliveryJenkins.log.count { it.contains("Set image on image stream: docker-qc.tools.bbh.com/ta/certscanner-api:${pinnedTag}") } == 2,
        [resultOf(noDelivery, RD), resultOf(noDelivery, QC)])

Map onlyBackend = focused([name: 'full pipeline, Nexus delivery disabled for the only service', buildNumber: 355, configure: { Map p ->
    p.remove('gui')
    rdOf(p, 'backend-api', 'openshift').nexusDeliveryEnabled = false
}])
List<String> notDeployed = (onlyBackend.j as FakeScript).log.findAll { it.startsWith('[OPENSHIFT]') }
check('full pipeline, Nexus delivery disabled for the only service: without a pinned buildTag the snapshot, RD and QC stages are NOT_REQUIRED and nothing is rolled out',
        [SNAPSHOT, RD, QC].collect { resultOf(onlyBackend, it) } == ['NOT_REQUIRED'] * 3
                && notDeployed == ['RD', 'QC'].collect { "[OPENSHIFT] backend-api: ${it} deployment not required - Nexus delivery is disabled by deploy.openshift.rd.nexusDeliveryEnabled and no buildTag is pinned".toString() }
                && !(onlyBackend.openshift as FakeOpenShift).calls.any { it.startsWith('rollout') },
        [SNAPSHOT, RD, QC].collect { resultOf(onlyBackend, it) } + notDeployed)
String onlyBackendDora = influxOf(onlyBackend.j as FakeScript).readLines().find { it.startsWith('dora,') } ?: ''
check('full pipeline, Nexus delivery disabled for the only service: the dora point counts no deployment, because the deployment stages are NOT_REQUIRED',
        onlyBackendDora.contains(' deployment=0i,'), onlyBackendDora)

Map guiGradlePush = [group: 'com.bbh.certscanner', artifact: 'certscanner-gui', repositoryUrl: 'https://tools.bbh.com/nexus/repository/snapshots',
                     artifacts: [[path: 'build/libs/*.jar']]]
Map oneDelivery = focused([name: 'full pipeline, Nexus delivery disabled for one service', buildNumber: 356, configure: { Map p ->
    rdOf(p, 'backend-api', 'openshift').nexusDeliveryEnabled = false
    (p.gui as Map).delivery = [push: [gradle: guiGradlePush]]
}])
FakeScript oneDeliveryJenkins = oneDelivery.j as FakeScript
String oneDeliveryTag = (oneDeliveryJenkins.files['build.tag'] ?: '') as String
Map gradleManifest = (oneDeliveryJenkins.jsonFiles['/ws@tmp/nexus-gradle.Ab12Cd34/manifest.json'] ?: [:]) as Map
String gradlePush = oneDeliveryJenkins.calls.find { it.startsWith('sh [Nexus Gradle push] ') } ?: ''
check('full pipeline, Nexus delivery disabled for one service: the stages stay PASS for the service that delivered and deployed, and the other service is skipped',
        [SNAPSHOT, RD, QC].collect { resultOf(oneDelivery, it) } == ['PASS'] * 3
                && !(oneDelivery.openshift as FakeOpenShift).calls.any { it.startsWith('startBuild') || it.startsWith('rollout') }
                && oneDeliveryJenkins.calls.count('step UCDeployPublisher') == 2
                && oneDeliveryJenkins.log.count { it.startsWith('[OPENSHIFT] backend-api:') } == 2,
        [SNAPSHOT, RD, QC].collect { resultOf(oneDelivery, it) })
check('full pipeline, Gradle Nexus delivery: the wrapper of the service publishes the matched files under the tag of the run on a project of the temporary folder, which is removed, and build.tag names the tag',
        oneDeliveryTag ==~ /356-\d{8}-\d{6}/
                && gradlePush.contains("'./gradlew' 'publishNexusDeliveryPublicationToNexusRepository' '--no-daemon' '--no-configuration-cache' '--project-dir' '/ws@tmp/nexus-gradle.Ab12Cd34' '-PnexusManifest=/ws@tmp/nexus-gradle.Ab12Cd34/manifest.json'")
                && gradleManifest == [group: 'com.bbh.certscanner', artifact: 'certscanner-gui', repositoryUrl: 'https://tools.bbh.com/nexus/repository/snapshots',
                                      allowInsecureProtocol: false, version: "${oneDeliveryTag}-SNAPSHOT".toString(),
                                      artifacts: [[fileName: 'cert-scanner-gui.jar', extension: 'jar', classifier: 'cert-scanner-gui', path: '/ws/build/libs/cert-scanner-gui.jar']]]
                && oneDeliveryJenkins.files['/ws@tmp/nexus-gradle.Ab12Cd34/build.gradle']?.contains("tasks.register('pullNexusArtifacts')")
                && oneDeliveryJenkins.calls.contains("sh [Nexus Gradle push: remove the project folder] rm -rf -- '/ws@tmp/nexus-gradle.Ab12Cd34'")
                && oneDeliveryJenkins.log.any { it == "[NEXUS] gui: uploaded 1 artifact(s) as ${oneDeliveryTag}-SNAPSHOT".toString() }
                && oneDeliveryJenkins.log.any { it == "[DOD] Build tag: ${oneDeliveryTag}".toString() },
        [oneDeliveryTag, gradlePush, gradleManifest] + oneDeliveryJenkins.log.findAll { it.startsWith('[NEXUS]') || it.startsWith('[DOD] Build') })
List<Map> dodPushes = oneDeliveryJenkins.stepArgs.findAll { it.get('$class') == 'UCDeployPublisher' && (it.component as Map)?.delivery }.collect { ((it.component as Map).delivery as Map) }
check('full pipeline, Gradle Nexus delivery: DOD pushes the component from the absolute base directory of the workspace with a version of the build tag',
        dodPushes.size() == 1 && dodPushes.every { it.baseDir == '/ws/gui/build/libs' && it.pushVersion == "*.jar_${oneDeliveryTag}".toString() },
        dodPushes.collect { [it.baseDir, it.pushVersion] })
Map gradleEvidence = focused([name: 'full pipeline, Gradle Nexus delivery without artifacts', buildNumber: 358, configure: { Map p ->
    rdOf(p, 'backend-api', 'openshift').nexusDeliveryEnabled = false
}])
check('full pipeline, Gradle Nexus delivery without artifacts: the service is not required in stage 6, nothing is published and the UrbanCode versions use the build number',
        resultOf(gradleEvidence, SNAPSHOT) == 'NOT_REQUIRED' && !(gradleEvidence.j as FakeScript).files.containsKey('build.tag')
                && (gradleEvidence.j as FakeScript).log.contains('[NEXUS] gui: delivery.push.gradle.artifacts is not set in OneView - the Gradle artifact is not published')
                && (gradleEvidence.j as FakeScript).log.any { it == '[DOD] No build.tag from a Nexus delivery: the versions use the build number 358' },
        [resultOf(gradleEvidence, SNAPSHOT)] + (gradleEvidence.j as FakeScript).log.findAll { it.startsWith('[NEXUS]') || it.startsWith('[DOD] No') })

Map pulled = runPipeline([name: 'extended pipeline, Nexus download', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 121,
                          params: [:], upstreamFiles: ['build.tag': '213-20260919-081500'], configure: { Map p ->
    (p.gui as Map).delivery = [pull: [outputDirectory: 'downloaded-artifacts', gradle: guiGradlePush + [artifacts: [[path: 'cert-scanner-gui.jar']]]]]
}])
FakeScript pulledJenkins = pulled.j as FakeScript
Map pullManifest = (pulledJenkins.jsonFiles['/ws@tmp/nexus-gradle.Ab12Cd34/manifest.json'] ?: [:]) as Map
check('extended pipeline, Nexus download: the snapshot named in build.tag of the security run is downloaded for the VM service, the OpenShift service is not required, and UrbanCode Deploy versions the components with the same tag',
        !pulled.crash && (pulled.var as FakeCpsScript).executedStages[0..2] == [MONITOR, PULL, RD] && resultOf(pulled, PULL) == 'PASS'
                && pulledJenkins.calls.any { it.startsWith('sh [Nexus Gradle pull] ') && it.contains("'./gradlew' 'pullNexusArtifacts' '--no-daemon'") }
                && pullManifest.version == '213-20260919-081500-SNAPSHOT' && pullManifest.outputDirectory == '/ws/downloaded-artifacts'
                && pullManifest.artifacts == [[fileName: 'cert-scanner-gui.jar', extension: 'jar', classifier: 'cert-scanner-gui']]
                && pulledJenkins.log.contains('[NEXUS] backend-api: an OpenShift service deploys its image - no artifact is downloaded from Nexus')
                && pulledJenkins.log.contains('[DOD] Build tag: 213-20260919-081500')
                && pulledJenkins.calls.count('step UCDeployPublisher') == 2
                && influxOf(pulledJenkins).readLines().any { it.startsWith('build_evidence,') && it.contains('module=gui artifact_version="213-20260919-081500"') },
        [pulled.crash, (pulled.var as FakeCpsScript).executedStages, resultOf(pulled, PULL), pullManifest] + pulledJenkins.log.findAll { it.startsWith('[NEXUS]') || it.startsWith('[DOD]') })

Map mavenPulled = runPipeline([name: 'extended pipeline, Maven Nexus download', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 122,
                               params: [:], upstreamFiles: ['build.tag': '213-20260919-081500'], configure: { Map p ->
    Map backend = p['backend-api'] as Map
    backend.deployTarget = 'vm'
    (backend.deploy as Map).vm = [dod: [siteName: 'deploy.bbh.com', applications: [[applicationName: 'CertScanner-API', components: [[componentName: 'CertScanner-API-app', baseDir: 'gui/build/libs', fileIncludePatterns: '*.jar']]]]]]
    backend.delivery = [pull: [group: 'com.bbh.certscanner', artifact: 'certscanner-api',
                               maven: [goals: ['org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get'], artifacts: [[path: 'certscanner-api.jar'], [path: 'config.zip', classifier: 'config']]]]]
}])
FakeScript mavenPulledJenkins = mavenPulled.j as FakeScript
List<String> mavenGets = mavenPulledJenkins.calls.findAll { it.contains("'-Dartifact=") }
check('extended pipeline, Maven Nexus download: every artifact is fetched by its classifier into the local repository Maven reports and copied into the output folder under its file name',
        !mavenPulled.crash && resultOf(mavenPulled, PULL) == 'PASS' && mavenGets.size() == 2
                && mavenGets[0].contains("'org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get' '-Dartifact=com.bbh.certscanner:certscanner-api:213-20260919-081500-SNAPSHOT:jar:certscanner-api' '-Dmaven.repo.local=/home/jenkins/.m2/repository'")
                && mavenGets[1].contains("'-Dartifact=com.bbh.certscanner:certscanner-api:213-20260919-081500-SNAPSHOT:zip:config' ")
                && mavenPulledJenkins.calls.any { it.contains("'org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate' '-Dexpression=settings.localRepository' '-Doutput=/ws@tmp/nexus-local-repository.txt'") }
                && mavenPulledJenkins.calls.contains("sh cp -- '/home/jenkins/.m2/repository/com/bbh/certscanner/certscanner-api/213-20260919-081500-SNAPSHOT/certscanner-api-213-20260919-081500-SNAPSHOT-config.zip' 'downloaded-artifacts/config.zip'")
                && mavenPulledJenkins.log.contains('[NEXUS] backend-api: downloaded 2 artifact(s) of 213-20260919-081500-SNAPSHOT to downloaded-artifacts')
                && mavenPulledJenkins.log.contains('[NEXUS] gui: delivery.pull is not set - no artifact is downloaded from Nexus'),
        [mavenPulled.crash, resultOf(mavenPulled, PULL)] + mavenGets + mavenPulledJenkins.log.findAll { it.startsWith('[NEXUS]') })

Map pullWithoutTag = runPipeline([name: 'extended pipeline, Nexus download without build.tag', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 123,
                                  params: [:], configure: { Map p ->
    (p.gui as Map).delivery = [pull: [gradle: guiGradlePush + [artifacts: [[path: 'cert-scanner-gui.jar']]]]]
}])
check('extended pipeline, Nexus download without build.tag: the stage fails with the file it misses and what writes it',
        (pullWithoutTag.j as FakeScript).log.any { it.contains('[NEXUS] gui: there is no build.tag - the security pipeline writes it when it publishes the snapshot') }
                && resultOf(pullWithoutTag, PULL) == 'FAIL',
        [resultOf(pullWithoutTag, PULL)] + (pullWithoutTag.j as FakeScript).log.findAll { it.contains('[NEXUS]') })

Closure nodeOf = { Map p, String project -> (((p[project] as Map).build ?: [:]) as Map) }
Closure firstCall = { FakeScript j, String prefix -> j.calls.findIndexOf { it.startsWith(prefix) } }
String registry = 'https://tools.bbh.com/nexus/repository/npm-group/'
Closure nodeDefaults = { Map d -> d.build = [node: [installationName: 'NodeJS-20', registry: registry]] }

Map nodeRun = focused([name: 'full pipeline, Node.js build for one service', buildNumber: 357, defaults: nodeDefaults, configure: { Map p ->
    nodeOf(p, 'gui').node = [enabled: true, dir: 'gui/frontend', env: [NODE_OPTIONS: '--max-old-space-size=4096'], nexusIqScan: false]
}])
FakeScript nodeJenkins = nodeRun.j as FakeScript
List<String> nodeSteps = nodeJenkins.calls.findAll { it.startsWith('withEnv [PATH+NODEJS') || it.startsWith('sh [Node.js: ') }.collect { it.readLines()[0].trim() }
check('full pipeline, Node.js build for one service: the stage runs after Monitor with the installation, registry and env of the service, and its commands run before the first Gradle build',
        (nodeRun.var as FakeCpsScript).executedStages == fullStages
                && nodeSteps == ["withEnv [PATH+NODEJS=/opt/tools/NodeJS-20/bin, npm_config_registry=${registry}, NODE_OPTIONS=--max-old-space-size=4096]".toString(),
                                 'sh [Node.js: npm ci]', 'sh [Node.js: npm run build]']
                && firstCall(nodeJenkins, 'sh [Node.js: npm run build]') < firstCall(nodeJenkins, 'sh [Gradle: ')
                && nodeJenkins.calls.count { it.startsWith('sh [Node.js: npm ci]') } == 1,
        nodeSteps + [(nodeRun.var as FakeCpsScript).executedStages])
check('full pipeline, Node.js build for one service: one [NODE] line per service, the other service is not required and the release gate allows',
        nodeJenkins.log.findAll { it.startsWith('[NODE]') } == ['[NODE] gui: Node.js from installation NodeJS-20 in gui/frontend: npm ci && npm run build',
                                                              '[NODE] backend-api: build.node.enabled is not set - no Node.js build']
                && ((nodeJenkins.jsonFiles['release-gate.json'] as Map).allowed as boolean),
        nodeJenkins.log.findAll { it.startsWith('[NODE]') })

Map nodeFailure = runPipeline([name: 'full pipeline, Node.js build failure', var: 'devSecOpsPipeline', fail: false, breakNode: true, job: 'CertScanner-devsecops-pipeline',
                               buildNumber: 358, params: [DEPLOY_HIGHER_ENV: true], configure: { Map p -> nodeOf(p, 'gui').node = [enabled: true] }])
FakeCpsScript nodeFailureVar = nodeFailure.var as FakeCpsScript
check('full pipeline, Node.js build failure: the stage fails hard and the later stages are skipped',
        !nodeFailure.crash && nodeFailureVar.executedStages == [MONITOR] && nodeFailureVar.skippedStages == [STATIC] + declaredFull.drop(2)
                && nodeFailureVar.stageFailures.size() == 1 && nodeFailureVar.stageFailures[0].startsWith(BUILD)
                && (nodeFailure.j as FakeScript).currentBuild.currentResult == 'FAILURE',
        [nodeFailure.crash, nodeFailureVar.executedStages, nodeFailureVar.stageFailures])

Closure irxExcluded = { FakeScript j ->
    j.calls.findAll { it.startsWith('withEnv [APPSCAN_CMD_RUNTIME=') && it.contains('APPSCAN_SOURCE_DIR_RUNTIME=') }.collect { (it =~ /APPSCAN_EXCLUDED_FOLDERS=(.*?), APPSCAN_OPTS=/)[0][1] }
}
Closure sastRun = { String name, int buildNumber, Closure configure ->
    Map r = runPipeline([name: name, var: 'devSecOpsSASTScanningPipeline', fail: false, job: 'CertScanner-sast-pipeline', buildNumber: buildNumber,
                         params: [:], defaults: nodeDefaults, configure: configure])
    check("${name}: the pipeline var runs to the end without a stage failure, with the Node.js build in Build artifact after Monitor",
            !r.crash && (r.var as FakeCpsScript).stageFailures.isEmpty() && (r.var as FakeCpsScript).executedStages == [MONITOR, BUILD, SAST],
            ([r.crash] + (r.var as FakeCpsScript).stageFailures + (r.var as FakeCpsScript).executedStages).join('\n      '))
    return r
}
Map sastFirst = sastRun('SAST pipeline, Node.js build for the first service', 89) { Map p ->
    nodeOf(p, 'gui').node = [enabled: true, dir: 'gui/frontend']
    nodeOf(p, 'backend-api').node = [enabled: true]
    ((p['backend-api'] as Map).asoc as Map).doCompile = false
    (p['backend-api'] as Map).excludedDirs = 'test'
}
FakeScript sastFirstJenkins = sastFirst.j as FakeScript
check('SAST pipeline, Node.js build for the first service: the Node.js build runs before the SAST compile, a service whose SAST scan does not compile is not built, '
        + 'and every IRX excludes node_modules',
        firstCall(sastFirstJenkins, 'sh [Node.js: npm run build]') < firstCall(sastFirstJenkins, 'sh [Gradle: ')
                && sastFirstJenkins.calls.count { it.startsWith('sh [Node.js: npm ci]') } == 1
                && sastFirstJenkins.log.contains('[NODE] backend-api: the SAST scan does not compile this service - no Node.js build')
                && irxExcluded(sastFirstJenkins) == ['node_modules', 'test,node_modules'],
        irxExcluded(sastFirstJenkins) + sastFirstJenkins.log.findAll { it.startsWith('[NODE]') })
Map sastLast = sastRun('SAST pipeline, Node.js build for the last service', 90) { Map p ->
    nodeOf(p, 'backend-api').node = [enabled: true, nodePath: '/opt/node-20', npmrcCredentialsId: 'npmrc-file']
    (p.gui as Map).excludedDirs = 'node_modules'
}
FakeScript sastLastJenkins = sastLast.j as FakeScript
int npmrcAt = sastLastJenkins.calls.indexOf('withCredentials [npmrc-file]')
check('SAST pipeline, Node.js build for the last service: nodePath wins over the Library default installation, the .npmrc credentials replace the registry export, '
        + 'Library defaults alone do not enable the build, and every IRX excludes node_modules',
        npmrcAt >= 0 && sastLastJenkins.calls[npmrcAt + 1] == 'withEnv [PATH+NODEJS=/opt/node-20/bin]'
                && sastLastJenkins.calls[npmrcAt + 2].startsWith('sh [Node.js: npm ci]')
                && sastLastJenkins.log.findAll { it.startsWith('[NODE]') } == ['[NODE] gui: build.node.enabled is not set - no Node.js build',
                                                                            '[NODE] backend-api: Node.js from /opt/node-20 in the workspace: npm ci && npm run build']
                && irxExcluded(sastLastJenkins) == ['node_modules', 'node_modules'],
        irxExcluded(sastLastJenkins) + sastLastJenkins.log.findAll { it.startsWith('[NODE]') } + (npmrcAt >= 0 ? sastLastJenkins.calls.subList(npmrcAt, npmrcAt + 3) : []))
Map sastIncluded = sastRun('SAST pipeline, Node.js build with included folders', 91) { Map p ->
    nodeOf(p, 'gui').node = [enabled: true]
    (p['backend-api'] as Map).includedDirs = 'src'
}
check('SAST pipeline, Node.js build with included folders: the IRX of the service with includedDirs keeps its folders, and the other service excludes node_modules',
        irxExcluded(sastIncluded.j as FakeScript) == ['node_modules', ''], irxExcluded(sastIncluded.j as FakeScript))

check('full pipeline, Node.js build for one service: nexusIqScan false keeps the Nexus IQ scan patterns of the service',
        (nodeRun.evaluations as List<Map>).collect { it.iqScanPatterns } == [[[scanPattern: '**/gui/build/libs/*.jar']], [[scanPattern: '**/api/target/*.jar']]],
        (nodeRun.evaluations as List<Map>).collect { it.iqScanPatterns })

Map nexusIqNode = runPipeline([name: 'Nexus IQ GoldenFix pipeline, Node.js build', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: false, job: 'CertScanner-nexusiq-pipeline',
                               buildNumber: 64, params: [:], configure: { Map p -> nodeOf(p, 'gui').node = [enabled: true, dir: 'gui/frontend'] }])
FakeScript nexusIqNodeJenkins = nexusIqNode.j as FakeScript
check('Nexus IQ GoldenFix pipeline, Node.js build: the Node.js build runs before Build artifact and package-lock.json of its folder joins the Nexus IQ scan of that service only',
        !nexusIqNode.crash && (nexusIqNode.var as FakeCpsScript).executedStages == [MONITOR, BUILD, NIQ]
                && firstCall(nexusIqNodeJenkins, 'sh [Node.js: npm run build]') < firstCall(nexusIqNodeJenkins, 'sh [Gradle: ')
                && (nexusIqNode.evaluations as List<Map>).collect { it.iqScanPatterns } == [[[scanPattern: '**/gui/build/libs/*.jar'], [scanPattern: 'gui/frontend/package-lock.json']],
                                                                                          [[scanPattern: '**/api/target/*.jar']]],
        [nexusIqNode.crash, (nexusIqNode.var as FakeCpsScript).executedStages, (nexusIqNode.evaluations as List<Map>).collect { it.iqScanPatterns }])

Map extendedNode = runPipeline([name: 'extended pipeline, Node.js build', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 120,
                                params: [DEPLOY_HIGHER_ENV: true], dastDisabledFor: 'backend-api', configure: { Map p -> nodeOf(p, 'gui').node = [enabled: true] }])
FakeScript extendedNodeJenkins = extendedNode.j as FakeScript
check('extended pipeline, Node.js build: while no release delivery is implemented for Gradle, the release stage builds no Node.js frontend and runs no publish, and says so for every service',
        !extendedNode.crash && (extendedNode.var as FakeCpsScript).executedStages == extendedStages && resultOf(extendedNode, RELEASE) == 'NOT_REQUIRED'
                && !extendedNodeJenkins.calls.any { it.startsWith('sh [Node.js: ') || it.contains('gradlew publish') || it.contains('mvn deploy') }
                && extendedNodeJenkins.log.count { it.contains('the release delivery of the safe artifact is not implemented yet for the build tool') } == 2,
        [extendedNode.crash, resultOf(extendedNode, RELEASE)] + extendedNodeJenkins.log.findAll { it.startsWith('[NODE]') || it.startsWith('[NEXUS]') })

Closure staticLog = { Map r -> (r.j as FakeScript).log.findAll { it.startsWith('[STATIC] ') } }
Closure stagesOf = { Map r -> [(r.var as FakeCpsScript).executedStages, (r.var as FakeCpsScript).skippedStages] }
Map otherTasks = focused([name: 'full pipeline, parallel stages, SAST compile with other Gradle tasks', buildNumber: 361, parallel: true,
                          configure: { Map p -> ((p.gui as Map).asoc as Map).gradle = [tasks: ['compileJava']] }])
FakeScript otherTasksJenkins = otherTasks.j as FakeScript
int otherCopy = otherTasksJenkins.calls.indexOf("withEnv [WORKSPACE=${sastCopy}]".toString())
int otherRemoved = otherTasksJenkins.calls.findIndexOf { it.startsWith('sh [Remove the SAST copy of the workspace]') }
int otherCompile = otherTasksJenkins.calls.findIndexOf { it.startsWith('sh [Gradle: compileJava]') }
check('full pipeline, parallel stages, SAST compile with other Gradle tasks: the SAST compile runs in the copy of the workspace, so the four stages stay parallel and Nexus IQ scans the files of Build artifact',
        stagesOf(otherTasks)[0].contains(STATIC) && !staticLog(otherTasks).any { it.contains('other Gradle tasks') }
                && otherCopy >= 0 && otherCopy < otherCompile && otherCompile < otherRemoved
                && (otherTasks.evaluatedAt as List<Integer>).max() < otherCompile,
        staticLog(otherTasks) + stagesOf(otherTasks) + [otherCopy, otherCompile, otherRemoved])
Map noCopy = focused([name: 'full pipeline, parallel stages, the SAST copy cannot be made', buildNumber: 368, parallel: true, breakSastCopy: true])
FakeScript noCopyJenkins = noCopy.j as FakeScript
check('full pipeline, parallel stages, the SAST copy cannot be made: the [WARN] names the reason and the fallback, and SAST queues first while Unit tests, Nexus IQ and SonarQube run one after another as before',
        stagesOf(noCopy)[0].contains(STATIC) && !noCopyJenkins.calls.contains("withEnv [DSO_PARALLEL_BRANCH=${UNIT}]".toString()) && noCopyJenkins.log.contains(orderLine)
                && noCopyJenkins.log.contains('[WARN] [STATIC] The workspace could not be copied for SAST (tar: write error: No space left on device) - falling back: SAST queues its scans first and Unit tests, Nexus IQ and SonarQube run one after another while AppScan scans'),
        staticLog(noCopy) + noCopyJenkins.log.findAll { it.startsWith('[WARN] [STATIC]') })
Map noCopyOther = focused([name: 'full pipeline, parallel stages, no SAST copy and a SAST compile with other Gradle tasks', buildNumber: 369, parallel: true, breakSastCopy: true,
                           configure: { Map p -> ((p.gui as Map).asoc as Map).gradle = [tasks: ['compileJava']] }])
FakeScript noCopyOtherJenkins = noCopyOther.j as FakeScript
check('full pipeline, parallel stages, no SAST copy and a SAST compile with other Gradle tasks: the run names the service and runs the four stages one after another, Nexus IQ before the SAST compile',
        staticLog(noCopyOther).contains('[STATIC] gui: the SAST compile runs other Gradle tasks or Maven goals than the build command in the workspace, so it may not rebuild the files Nexus IQ scans: ' +
                                        'Unit tests, Nexus IQ, SAST and SonarQube run one after another')
                && (noCopyOther.evaluatedAt as List<Integer>).max() < noCopyOtherJenkins.calls.findIndexOf { it.startsWith('sh [Gradle: compileJava]') }
                && !noCopyOtherJenkins.calls.contains("withEnv [DSO_PARALLEL_BRANCH=${UNIT}]".toString()),
        staticLog(noCopyOther))
String defaultLine = '[STATIC] Unit tests, Nexus IQ, SAST and SonarQube run at the same time (staticAnalysis.parallel: true, the default)'
Map equalCompile = focused([name: 'full pipeline, parallel stages, SAST compile equal to the build command', buildNumber: 362, parallel: true,
                            configure: { Map p -> ((p.gui as Map).asoc as Map).gradle = OneViewFixtures.copy((((p.gui as Map).build as Map).gradle) as Map) }])
Map envOn = focused([name: 'full pipeline, parallel stages switched on by the environment', buildNumber: 363, env: [DSO_STATIC_ANALYSIS_PARALLEL: 'true']])
Map optOut = focused([name: 'full pipeline, parallel stages, one service opts out', buildNumber: 364, parallel: true,
                      configure: { Map p -> (p['backend-api'] as Map).staticAnalysis = [parallel: false] }])
Map wrongValue = focused([name: 'full pipeline, parallel stages, a value that is not a boolean', buildNumber: 370, parallel: true,
                          env: [DSO_DYNAMIC_ANALYSIS_PARALLEL: 'sometimes'], configure: { Map p -> (p.gui as Map).staticAnalysis = [parallel: 'often'] }])
List<String> wrongLines = (wrongValue.j as FakeScript).log.findAll { it.startsWith('[WARN] [STATIC]') || it.startsWith('[WARN] [DYNAMIC]') }
check('full pipeline, parallel stages: a SAST compile equal to the build command runs the Static analysis stage by default, DSO_STATIC_ANALYSIS_PARALLEL=true over a false default says that the variable decides, ' +
        'and one service with staticAnalysis.parallel false keeps the four stages one after another and names itself',
        stagesOf(equalCompile)[0].contains(STATIC) && staticLog(equalCompile)[0] == defaultLine
                && stagesOf(envOn)[0].contains(STATIC) && staticLog(envOn)[0] == '[STATIC] Unit tests, Nexus IQ, SAST and SonarQube run at the same time (DSO_STATIC_ANALYSIS_PARALLEL=true)'
                && !stagesOf(optOut)[0].contains(STATIC) && stagesOf(optOut)[0].contains(DYNAMIC)
                && staticLog(optOut) == ['[STATIC] backend-api: staticAnalysis.parallel: false - Unit tests, Nexus IQ, SAST and SonarQube run one after another'],
        [equalCompile, envOn, optOut].collect { stagesOf(it)[0] + staticLog(it) })
check('full pipeline, parallel stages, a value that is not a boolean: each wrong value gets a [WARN] with the default it falls back to, and the run stays parallel',
        stagesOf(wrongValue)[0].containsAll([STATIC, DYNAMIC])
                && wrongLines == ["[WARN] [STATIC] gui: staticAnalysis.parallel: 'often' is neither true nor false - the default true is used",
                                  "[WARN] [DYNAMIC] DSO_DYNAMIC_ANALYSIS_PARALLEL='sometimes' is neither true nor false - it is ignored and dynamicAnalysis.parallel of the services decides, by default true"],
        wrongLines)

Closure parallelRun = { Map spec -> runPipeline([var: 'devSecOpsPipeline', fail: false, parallel: true, job: 'CertScanner-devsecops-pipeline', params: [DEPLOY_HIGHER_ENV: true]] + spec) }
Closure viewOf = { Map r -> sectionsOf(normalise(sequentialView(normalise(goldenView(r), baseRules)), oneViewRules)) }
Map stopped = parallelRun([name: 'full pipeline, stage failure, parallel stages', breakSast: true, buildNumber: 344])
FakeScript stoppedJenkins = stopped.j as FakeScript
List<String> stoppedFailures = (stopped.var as FakeCpsScript).stageFailures
String stoppedHtml = stopped.html as String
check('full pipeline, stage failure, parallel stages: a SAST failure before the scans are queued fails Static analysis after Unit tests, Nexus IQ and SonarQube have finished, and the later stages are skipped',
        !stopped.crash && stoppedFailures.size() == 1 && stoppedFailures[0].startsWith(STATIC)
                && stagesOf(stopped) == [[MONITOR, BUILD], [UNIT, NIQ, SAST, SONAR] + (declaredFull - [MONITOR, BUILD, UNIT, NIQ, SAST, SONAR])]
                && (stopped.evaluatedAt as List).size() == 2 && stoppedJenkins.calls.any { it.startsWith('sh [Gradle: sonarqube]') }
                && [UNIT, NIQ, SAST, SONAR].collect { resultOf(stopped, it) } == ['PASS', 'PASS', 'FAIL', 'PASS']
                && stoppedJenkins.calls.any { it.startsWith('sh [Remove the SAST copy of the workspace]') },
        [stopped.crash, stoppedFailures, stagesOf(stopped), [UNIT, NIQ, SAST, SONAR].collect { resultOf(stopped, it) }])
check('full pipeline, stage failure, parallel stages: release-gate.json lists the SAST failure, and pipeline_run counts the six stages that ran, Nexus IQ of the OpenShift service not required',
        (((stoppedJenkins.jsonFiles['release-gate.json'] as Map).violations as List).contains("stage '${SAST}' is FAIL".toString()))
                && influxOf(stoppedJenkins).readLines().any { it.startsWith('pipeline_run,') && it.contains(',stages_total=6i,passed=4i,warned=0i,failed=1i,blocked=0i,skipped=0i,not_required=1i,') },
        [stoppedJenkins.jsonFiles['release-gate.json']] + influxOf(stoppedJenkins).readLines().findAll { it.startsWith('pipeline_run,') })

Map waitFails = parallelRun([name: 'full pipeline, SAST wait fails, parallel stages', breakSastWait: true, buildNumber: 366])
List<String> waitFailures = (waitFails.var as FakeCpsScript).stageFailures
check('full pipeline, SAST wait fails, parallel stages: SAST fails after its scans are queued, Nexus IQ and SonarQube keep the results they had before the wait, and the release gate lists the SAST failure',
        !waitFails.crash && waitFailures.size() == 1 && waitFailures[0].startsWith(STATIC) && [NIQ, SAST, SONAR].collect { resultOf(waitFails, it) } == ['PASS', 'FAIL', 'PASS']
                && (((waitFails.j as FakeScript).jsonFiles['release-gate.json'] as Map).violations as List).contains("stage '${SAST}' is FAIL".toString()),
        [waitFails.crash, waitFailures] + [NIQ, SAST, SONAR].collect { resultOf(waitFails, it) })

List<Map> goldenFixRuns = [false, true].collect { boolean on -> parallelRun([name: 'full pipeline, orange, GoldenFix', fail: true, goldenFix: true, parallel: on, buildNumber: 367]) }
List<Map> goldenFixViews = goldenFixRuns.collect { viewOf(it) }
check('full pipeline, orange, GoldenFix, parallel stages: the release gate, the InfluxDB lines, the report and the GoldenFix pull request equal those of the run one scan after another',
        ['release-gate.json', 'influx', 'report'].every { goldenFixViews[0][it] == goldenFixViews[1][it] } && goldenFixRuns.every { (it.goldenFixCalls as List).any { it.startsWith('PR ') } }
                && goldenFixRuns[0].goldenFixCalls == goldenFixRuns[1].goldenFixCalls && stagesOf(goldenFixRuns[1])[0].contains(STATIC),
        goldenFixRuns.collect { it.goldenFixCalls })

Closure sastNamesOf = { Map r ->
    FakeScript j = r.j as FakeScript
    [loginsOf(j), (r.sastQueued as List<String>).collect { it.replace(sastCopy, '/ws') }, j.files.keySet().findAll { it.contains('scan-sast-') || it.startsWith('appscan-report-') }.sort()]
}
Map perServiceParallel = focused([name: 'full pipeline, AppScan settings per service, parallel stages', buildNumber: 351, parallel: true, configure: perServiceSettings])
check('full pipeline, AppScan settings per service, parallel stages: the same logins, queued scan names, scan ID files and report names as one scan after another',
        stagesOf(perServiceParallel)[0].contains(STATIC) && sastNamesOf(perServiceParallel) == sastNamesOf(perService), sastNamesOf(perServiceParallel))

println "\n${failures == 0 ? 'ALL PIPELINE SCENARIOS PASSED' : failures + ' PIPELINE SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
