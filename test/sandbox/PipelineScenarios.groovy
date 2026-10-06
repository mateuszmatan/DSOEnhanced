import devsecops.test.FakeAbort
import devsecops.test.FakeCpsScript
import devsecops.test.FakeIqEvaluation
import devsecops.test.FakeOpenShift
import devsecops.test.FakeQualityGate
import devsecops.test.FakeRemoteHandle
import devsecops.test.FakeRun
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import org.yaml.snakeyaml.Yaml

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File varsDir = new File(root, 'vars')
String defaultsText = new File(root, 'resources/defaults.yaml').text
String configText = new File(root, 'examples/CertScanner/config.yaml').text

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

Closure runPipeline = { Map spec ->
    SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
    FakeScript j = new FakeScript()
    FakeOpenShift openshift = new FakeOpenShift()
    Yaml yaml = new Yaml()
    Map defaults = yaml.load(defaultsText) as Map
    Map projects = yaml.load(configText) as Map
    if (spec.coverageMinLine) (((defaults.defaults as Map).coverage as Map)).minLine = spec.coverageMinLine
    if (spec.dastDisabledFor) (((projects.projects as Map)[spec.dastDisabledFor] as Map).dast as Map).enabled = false
    if (spec.mavenVmDelivery) {
        Map backend = (projects.projects as Map)['backend-api'] as Map
        backend.deployTarget = 'vm'
        backend.delivery = [maven: [goals: ['deploy:deploy-file'],
                                    flags: ['-DrepositoryId=bbh-snapshots', '-Durl=https://tools.bbh.com/nexus/repository/snapshots',
                                            '-DgroupId=com.bbh.certscanner', '-DartifactId=certscanner-api', '-DgeneratePom=true']]]
    }
    (projects.projects as Map).each { k, v -> Map sonarCfg = (((v as Map).tools ?: [:]) as Map).sonar as Map; if (sonarCfg) sonarCfg.badgeToken = '' }
    if (spec.var == 'devSecOpsExtendedPipeline') {
        Map archived = yaml.load(yaml.dump(projects)) as Map
        Map rd = ((((archived.projects as Map)['backend-api'] as Map).deploy as Map).openshift as Map).rd as Map
        rd.buildTag = '213-20260919-081500'
        rd.internalDockerUrl = 'image-registry.openshift-image-registry.svc:5000/ta-certscanner-build/certscanner-api@sha256:5f1c0ffee'
        Map gate = [job: 'DevSecOps/CertScanner-security-pipeline', build: '213', allowed: !spec.upstreamViolations, violations: spec.upstreamViolations ?: []]
        j.upstream['DevSecOps/CertScanner-security-pipeline'] = ['config.yaml': yaml.dump(archived), 'release-gate.json': groovy.json.JsonOutput.toJson(gate)]
    }
    Map<String, Map> data = dataFor(spec.fail as boolean) as Map<String, Map>

    j.env.vars.putAll([
            BUILD_URL   : "https://jenkins.bbh.com/job/DevSecOps/job/${spec.job}/${spec.buildNumber}/".toString(),
            JOB_NAME    : "DevSecOps/${spec.job}".toString(),
            BUILD_NUMBER: String.valueOf(spec.buildNumber),
            BUILD_ID    : String.valueOf(spec.buildNumber),
            GIT_BRANCH  : 'origin/develop',
            PATH        : '/usr/bin'
    ])
    j.params.putAll((spec.params ?: [:]) as Map)
    j.resourcesDir = new File(root, 'resources')
    if (spec.coverageMinLine) j.resources['defaults.yaml'] = yaml.dump(defaults)
    secretResources.each { String name -> j.resources[name] = "fake ${name}".toString() }
    j.files['config.yaml'] = yaml.dump(projects)
    j.files['gradlew'] = '#!/bin/sh'
    j.files['build/reports/jacoco/test/jacocoTestReport.xml'] = '<report/>'
    j.files['target/site/jacoco/jacoco.xml'] = '<report/>'
    j.files['gui/build/libs'] = ''
    j.files['build/libs/cert-scanner-gui.jar'] = 'jar'
    j.files['target/certscanner-api.jar'] = 'jar'
    j.files['.appscan-logs/appscan.cmd'] = '/opt/appscan/bin/appscan.sh'
    j.files['.appscan-logs/proxy.pass'] = 'proxy-secret'
    j.readFileHandler = { String path -> 'apiVersion: v1\nkind: Template\n' }

    Closure project = { -> (j.env.CURRENT_PROJECT_NAME ?: 'gui') as String }
    Closure relative = { String path -> path.startsWith('/ws/') ? path.substring(4) : path }
    j.shHandler = { Map a ->
        String text = String.valueOf(a.script ?: '')
        String label = String.valueOf(a.label ?: '')
        Map d = data[project()] ?: data.gui
        if (spec.fail && project() == 'backend-api' && label.startsWith('Maven: test')) throw new FakeAbort('script returned exit code 1')
        if (spec.breakSast && text.contains('api_login')) throw new FakeAbort('script returned exit code 1')
        if (a.returnStatus) return 0
        def move = text =~ /mv '([^']+)' '([^']+)'/
        if (move.find() && j.files.containsKey(relative(move.group(1)))) j.files[relative(move.group(2))] = j.files.remove(relative(move.group(1)))
        def sastDownload = text =~ /get_result -i "[^"]*" -t html -d "([^"]+)"/
        if (sastDownload.find()) j.files[relative(sastDownload.group(1))] = issuesHtml(d.sast as List)
        def dastDownload = text =~ /-o "([^"]+)"/
        if (dastDownload.find()) j.files[relative(dastDownload.group(1))] = dastDownload.group(1).endsWith('.pdf') ? '%PDF-1.7' : issuesHtml(d.dast as List)
        if (text.contains('.irx')) j.files["${j.env.APPSCAN_SCAN_NAME}.irx".toString()] = 'irx'
        if (!a.returnStdout) return null
        List c = d.coverage as List
        if (text.contains("findall('package')")) return "com.bbh.certscanner,CertificateService,CertificateService.java,${c[0]},${c[1]},${c[2]},${(c[1] as int) + (c[2] as int)}"
        if (text.contains('xml.etree.ElementTree')) return "${c[0]},${c[1]},${c[2]}"
        List s = d.sonar as List
        if (text.contains('facets=severities')) return "${s[0]},${s[1]},${s[2]},${s[3]}"
        if (text.contains('queue_analysis')) return 'Scan id: 5f3c8a2e-1b4d-4c6e-9a7b-2d8e0f1a3b5c'
        if (text.contains('ApiKeyLogin')) return '{"Token":"BEARER-TOKEN"}'
        if (text.contains('/api/v4/Scans/Dast/')) return '{"LatestExecution":{"Status":"Ready"}}'
        if (text.contains('/api/v4/Scans/Dast')) return '{"Id":"DAST-SCAN-ID"}'
        if (text.contains('/api/v4/Reports/Security/Scan/')) return '{"Id":"REPORT-ID"}'
        if (text.contains('/api/v2/applications?publicId=')) return '{"applications":[{"id":"7d3b2c1a9e8f4a6b"}]}\n200'
        if (text.contains('/policy')) return '{"components":[]}\n200'
        if (text.contains('print -quit')) return '0'
        if (text.contains('date +%Y%m%d%H%M')) return '202609191405'
        if (text.contains('skopeo copy')) return 'Copying blob sha256:5f1c done'
        if (text.contains('head -n 1')) return project() == 'gui' ? 'build/libs/cert-scanner-gui.jar' : 'target/certscanner-api.jar'
        return ''
    }
    j.steps['nexusPolicyEvaluation'] = { Map a ->
        List n = data[project()].niq as List
        new FakeIqEvaluation(criticalComponentCount: n[0] as int, severeComponentCount: n[1] as int, moderateComponentCount: n[2] as int,
                applicationCompositionReportUrl: "https://tools.bbh.com/IQ/ui/links/application/${a.iqApplication}/report/0f1e2d3c4b5a69788796a5b4c3d2e1f0".toString())
    }
    j.steps['waitForQualityGate'] = { a -> new FakeQualityGate(status: (data[project()].sonar as List)[4]) }
    j.steps['build'] = { Map a ->
        String job = String.valueOf(a.job)
        boolean failing = spec.fail && project() == 'gui' && job.contains('smoke')
        new FakeRun(result: failing ? 'FAILURE' : 'SUCCESS', absoluteUrl: "https://jenkins.bbh.com/job/${job.split('/').join('/job/')}/57/".toString(), number: 57, duration: 64000L)
    }
    ['reportBuild', 'reportUnitTest', 'reportSurefireTest'].each { String step -> j.steps[step] = { a -> j.calls << step; null } }
    j.steps['triggerRemoteJob'] = { Map a ->
        new FakeRemoteHandle(buildResult: 'SUCCESS', buildUrl: "https://jenkins-b.bbh.com/job/${String.valueOf(a.job).split('/').join('/job/')}/12/".toString())
    }

    Map<String, Object> globals = harness.loadVars(varsDir, j)
    globals['openshift'] = openshift
    FakeCpsScript pipelineVar = globals[spec.var as String] as FakeCpsScript
    String crash = ''
    try {
        harness.run { pipelineVar.call(spec.config as Map) }
    } catch (Throwable t) {
        crash = SandboxHarness.rejectionOf(t) ?: t.toString()
    }
    return [j: j, var: pipelineVar, openshift: openshift, crash: crash, html: (j.files['report/pipeline-report.html'] ?: '') as String]
}

Map baseConfig = [projectNames: 'gui,backend-api', agentNames: ['linux-agent', 'windows-agent']]
List<String> staticStages = [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT]
List<String> extendedStages = [MONITOR, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
List<String> fullStages = [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]

File goldenDir = new File(root, 'test/sandbox/golden')

Closure goldenView = { Map r ->
    FakeScript j = r.j as FakeScript
    List<String> influx = j.files.findAll { k, v -> (k as String).startsWith('influx_payload_') }.collect { k, v -> v as String }
    return ['== calls', j.calls.join('\n'),
            '== log', j.log.findAll { !it.startsWith('[INIT]') && !it.startsWith('[POLICY]') }.join('\n'),
            '== release-gate.json', (j.files['release-gate.json'] ?: '') as String,
            '== influx', influx.join('\n'),
            '== report', r.html as String].join('\n') + '\n'
}

Closure rule = { String reason, String pattern, String replacement ->
    [reason: reason, apply: { String text -> text.replaceAll(pattern, replacement) }]
}
List<Map> baseRules = [
        rule('build tags carry the UTC time of the run', /\d{8}-\d{6}/, '<stamp>'),
        rule('temporary file names carry System.currentTimeMillis()', /(?<!\d)\d{13}(?!\d)/, '<ms>'),
        rule('the report header shows the wall-clock time of the run', /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/, '<time>'),
        rule('InfluxDB points carry the epoch second of the run', /(?m) \d{10}$/, ' <s>'),
        rule('stage and job timings are measured on the wall clock', /\b(start_time|end_time|duration_ms|duration_s|lead_time_s)=\d+i/, '$1=<n>i'),
        rule('durations in the log are measured on the wall clock', /duration=\S+/, 'duration=<d>'),
        rule('durations in the report are measured on the wall clock', /(>|&nbsp;)(-|\d+s|\d+m(?: \d+s)?)</, '$1<d><')
]
List<Map> portalRules = []

Closure normalise = { String text, List<Map> rules ->
    String out = text
    rules.each { Map r -> out = (r.apply as Closure).call(out) as String }
    return out
}

List<Map> specs = [
        [name: 'security pipeline, green', var: 'devSecOpsSecurityPipeline', fail: false, job: 'CertScanner-security-pipeline', buildNumber: 212,
         config: baseConfig, params: [RUN_EXTENDED_PIPELINE: true], executed: staticStages, skipped: [], result: 'SUCCESS', post: ['always', 'success'],
         coverageMinLine: 75],
        [name: 'security pipeline, orange', var: 'devSecOpsSecurityPipeline', fail: true, job: 'CertScanner-security-pipeline', buildNumber: 213,
         config: baseConfig, params: [RUN_EXTENDED_PIPELINE: true], executed: staticStages, skipped: [], result: 'UNSTABLE', post: ['always', 'unstable']],
        [name: 'security pipeline, Maven artifact to Nexus', var: 'devSecOpsSecurityPipeline', fail: false, job: 'CertScanner-security-pipeline',
         buildNumber: 214, config: baseConfig, params: [RUN_EXTENDED_PIPELINE: false], executed: staticStages, skipped: [], result: 'SUCCESS',
         post: ['always', 'success'], mavenVmDelivery: true],
        [name: 'SAST pipeline, green', var: 'devSecOpsSASTScanningPipeline', fail: false, job: 'CertScanner-sast-pipeline', buildNumber: 87,
         config: baseConfig, params: [:], executed: [MONITOR, SAST], skipped: [], result: 'SUCCESS', post: ['always', 'success']],
        [name: 'SAST pipeline, orange', var: 'devSecOpsSASTScanningPipeline', fail: true, job: 'CertScanner-sast-pipeline', buildNumber: 88,
         config: baseConfig, params: [:], executed: [MONITOR, SAST], skipped: [], result: 'UNSTABLE', post: ['always', 'unstable']],
        [name: 'extended pipeline, green', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 118,
         config: baseConfig + [securityPipeline: 'DevSecOps/CertScanner-security-pipeline'], params: [DEPLOY_HIGHER_ENV: true],
         executed: extendedStages, skipped: [], result: 'SUCCESS', post: ['always', 'success'], dastDisabledFor: 'backend-api'],
        [name: 'extended pipeline, orange', var: 'devSecOpsExtendedPipeline', fail: true, job: 'CertScanner-extended-pipeline', buildNumber: 119,
         config: baseConfig + [securityPipeline: 'DevSecOps/CertScanner-security-pipeline'], params: [DEPLOY_HIGHER_ENV: true],
         upstreamViolations: ['gui SAST (AppScan) high 3 > 0'], executed: extendedStages - [RELEASE, QC], skipped: [RELEASE, QC], result: 'UNSTABLE',
         post: ['always', 'unstable']],
        [name: 'full pipeline, green', var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', buildNumber: 341,
         config: baseConfig, params: [DEPLOY_HIGHER_ENV: true], executed: fullStages, skipped: [], result: 'SUCCESS', post: ['always', 'success']],
        [name: 'full pipeline, orange', var: 'devSecOpsPipeline', fail: true, job: 'CertScanner-devsecops-pipeline', buildNumber: 342,
         config: baseConfig, params: [DEPLOY_HIGHER_ENV: true], executed: fullStages - [RELEASE, QC], skipped: [RELEASE, QC], result: 'UNSTABLE',
         post: ['always', 'unstable']],
        [name: 'full pipeline, green but QC not selected', var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', buildNumber: 343,
         config: baseConfig, params: [DEPLOY_HIGHER_ENV: false], executed: fullStages - [QC], skipped: [QC], result: 'SUCCESS', post: ['always', 'success']],
        [name: 'full pipeline, stage failure', var: 'devSecOpsPipeline', fail: false, breakSast: true, job: 'CertScanner-devsecops-pipeline', buildNumber: 344,
         config: baseConfig, params: [DEPLOY_HIGHER_ENV: true], executed: [MONITOR, UNIT, NIQ], skipped: fullStages - [MONITOR, UNIT, NIQ, SAST],
         result: 'FAILURE', post: ['always', 'failure'], failedStage: SAST]
]

specs.each { Map spec ->
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
    int expectedGrey = (spec.skipped as List).size() * 2
    check("${name}: the stage flow shows what the stages collected, not empty grey boxes",
            flow && greyBoxes <= expectedGrey && flow.contains('&#9201;')
                    && (!spec.executed.contains(UNIT) || !flow.contains('not measured')),
            "grey SKIP boxes ${greyBoxes} (at most ${expectedGrey}), any stage duration ${flow.contains('&#9201;')}, coverage not measured ${flow.contains('not measured')}")
    check("${name}: release gate verdict written for the downstream pipeline", j.jsonFiles.containsKey('release-gate.json')
            && ((j.jsonFiles['release-gate.json'] as Map).allowed as boolean) == (spec.result == 'SUCCESS' && !spec.failedStage),
            j.jsonFiles['release-gate.json'])
    if (spec.coverageMinLine) {
        check("${name}: the report shows and checks the coverage required by defaults.yaml (${spec.coverageMinLine}%)",
                html.contains("required&nbsp;<b>${spec.coverageMinLine}%</b>".toString()) && !html.contains('required&nbsp;<b>60%</b>'),
                'the report does not use the required coverage from defaults.yaml')
    }
    if (spec.var == 'devSecOpsSecurityPipeline') {
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
    if (spec.dastDisabledFor) {
        check("${name}: DAST is marked as not required for the project that does not configure it and nothing is blocked",
                log.any { it.contains('[DAST] Disabled in config') } && html.contains('NOT REQUIRED')
                        && ((j.jsonFiles['release-gate.json'] as Map).allowed as boolean),
                log.findAll { it.startsWith('[DAST]') }.join(' | '))
    }
    if (spec.var == 'devSecOpsExtendedPipeline') {
        check("${name}: config.yaml and the release gate are taken from the security pipeline", j.calls.contains('copyArtifacts DevSecOps/CertScanner-security-pipeline filter=config.yaml,release-gate.json'),
                j.calls.findAll { it.startsWith('copyArtifacts') })
    }
    if (spec.mavenVmDelivery) {
        check("${name}: the Maven artifact is deployed to the Nexus snapshot repository and the version is written back",
                j.calls.any { it.startsWith('writeYaml config.yaml') } && (j.log.any { it.contains('deploy:deploy-file') && it.contains('-Dfile=') }),
                j.log.findAll { it.contains('Execute Maven Command') }.join(' | '))
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
    List unhandled = j.unhandled.unique()
    if (unhandled) println "      note: steps not emulated by the fake Jenkins: ${unhandled}"
    String slug = name.toLowerCase().replaceAll(/[^a-z0-9]+/, '-')
    String actual = normalise(goldenView(r), baseRules)
    File golden = new File(goldenDir, "${slug}.txt")
    if (!golden.exists()) {
        golden.parentFile.mkdirs()
        golden.text = actual
        println "      note: golden output recorded at ${golden}"
    }
    boolean same = normalise(golden.text, portalRules) == normalise(actual, portalRules)
    File diffFile = new File(goldenDir, "${slug}.actual.txt")
    if (same) diffFile.delete() else diffFile.text = actual
    check("${name}: the run matches the golden output recorded on the library before the portal integration", same,
            "compare ${golden} with ${diffFile}")
}

println "\n${failures == 0 ? 'ALL PIPELINE SCENARIOS PASSED' : failures + ' PIPELINE SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
