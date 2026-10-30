import devsecops.test.FakeAbort
import devsecops.test.FakeCpsScript
import devsecops.test.FakeIqEvaluation
import devsecops.test.FakeOpenShift
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
File varsDir = new File(root, 'vars')
String defaultsText = new File(root, 'test/fixtures/defaults.yaml').text
String configText = new File(root, 'test/fixtures/CertScanner/config.yaml').text
Map platform = (new groovy.json.JsonSlurperClassic().parseText(new File(root, 'test/fixtures/portal/certscanner-gui-full.json').text) as Map).platform as Map
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
    if (spec.defaults) (spec.defaults as Closure).call(defaults.defaults as Map)
    if (spec.dastDisabledFor) (((projects.projects as Map)[spec.dastDisabledFor] as Map).dast as Map).enabled = false
    if (spec.mavenVmDelivery) {
        Map backend = (projects.projects as Map)['backend-api'] as Map
        backend.deployTarget = 'vm'
        backend.delivery = [maven: [goals: ['deploy:deploy-file'],
                                    flags: ['-DrepositoryId=bbh-snapshots', '-Durl=https://tools.bbh.com/nexus/repository/snapshots',
                                            '-DgroupId=com.bbh.certscanner', '-DartifactId=certscanner-api', '-DgeneratePom=true']]]
    }
    (projects.projects as Map).each { k, v -> Map sonarCfg = (((v as Map).tools ?: [:]) as Map).sonar as Map; if (sonarCfg) sonarCfg.badgeToken = '' }
    if (spec.configure) (spec.configure as Closure).call(projects.projects as Map)
    if (spec.var == 'devSecOpsExtendedPipeline') {
        Map archived = yaml.load(yaml.dump(projects)) as Map
        Map rd = ((((archived.projects as Map)['backend-api'] as Map).deploy as Map).openshift as Map).rd as Map
        rd.buildTag = '213-20260919-081500'
        rd.internalDockerUrl = 'image-registry.openshift-image-registry.svc:5000/ta-certscanner-build/certscanner-api@sha256:5f1c0ffee'
        Map gate = [job: securityJob, build: '213', allowed: !spec.upstreamViolations, violations: spec.upstreamViolations ?: []]
        j.upstream[securityJob] = ['pipeline-config.yaml': yaml.dump(archived), 'release-gate.json': groovy.json.JsonOutput.toJson(gate)]
    }
    Map upstreamJob = spec.var == 'devSecOpsExtendedPipeline' ? [securityPipeline: securityJob] : [:]
    Map jenkinsfile = spec.keyless ?
            [agentNames: ['linux-agent', 'windows-agent']] + (spec.autoDetect ? [:] : [projectNames: (projects.projects as Map).keySet().join(',')]) + upstreamJob :
            [pipelineKeys: PortalFixtures.publishAll(j, variants[spec.var] as String, defaults.defaults as Map, platform, projects.projects as Map, upstreamJob)]
    if (spec.keyless) j.resources['com/bbh/devsecops/defaults.yaml'] = yaml.dump(defaults)
    Map<String, Map> data = dataFor(spec.fail as boolean) as Map<String, Map>

    j.env.vars.putAll([
            BUILD_URL   : "https://jenkins.bbh.com/job/DevSecOps/job/${spec.job}/${spec.buildNumber}/".toString(),
            JOB_NAME    : "DevSecOps/${spec.job}".toString(),
            BUILD_NUMBER: String.valueOf(spec.buildNumber),
            BUILD_ID    : String.valueOf(spec.buildNumber),
            GIT_BRANCH  : 'origin/develop',
            PATH        : '/usr/bin'
    ] + (spec.keyless ? [:] : [DSO_PORTAL_URL: PortalFixtures.PORTAL_URL]))
    j.params.putAll((spec.params ?: [:]) as Map)
    j.missingCredentials.addAll((spec.missingCredentials ?: []) as List)
    j.resourcesDir = new File(root, 'resources')
    secretResources.each { String name -> j.resources[name] = "fake ${name}".toString() }
    j.files['gradlew'] = '#!/bin/sh'
    j.files['build/reports/jacoco/test/jacocoTestReport.xml'] = '<report/>'
    j.files['target/site/jacoco/jacoco.xml'] = '<report/>'
    j.files['gui/build/libs'] = ''
    j.files['build/libs/cert-scanner-gui.jar'] = 'jar'
    j.files['target/certscanner-api.jar'] = 'jar'
    j.files['.appscan-logs/appscan.cmd'] = '/opt/appscan/bin/appscan.sh'
    j.files['.appscan-logs/proxy.pass'] = 'proxy-secret'
    ((spec.files ?: [:]) as Map).each { name, text -> j.files[name as String] = text == null ? yaml.dump(projects) : text as String }
    j.readFileHandler = { String path -> 'apiVersion: v1\nkind: Template\n' }
    List<String> goldenFixCalls = []
    Map<String, String> manifests = spec.sharedCatalog ? GoldenFixResponder.SHARED_CATALOG : GoldenFixResponder.MANIFESTS
    Map reports = spec.sharedCatalog ? GoldenFixResponder.SHARED_CATALOG_REPORTS : GoldenFixResponder.REPORTS
    if (spec.goldenFix) GoldenFixResponder.plantManifests(j, manifests)
    if (spec.sharedCatalog) data['backend-api'].niq = [0, 2, 1]

    Closure project = { -> (j.env.CURRENT_PROJECT_NAME ?: 'gui') as String }
    Closure relative = { String path -> path.startsWith('/ws/') ? path.substring(4) : path }
    j.shHandler = { Map a ->
        String text = String.valueOf(a.script ?: '')
        String label = String.valueOf(a.label ?: '')
        Map d = data[project()] ?: data.gui
        if (spec.fail && project() == 'backend-api' && label.startsWith('Maven: test')) throw new FakeAbort('script returned exit code 1')
        if (spec.breakSast && text.contains('api_login')) throw new FakeAbort('script returned exit code 1')
        if (spec.breakBuild && label.startsWith('Gradle: ')) throw new FakeAbort('script returned exit code 1')
        if (spec.breakNode && label.startsWith('Node.js: ')) throw new FakeAbort('script returned exit code 1')
        if (a.returnStatus) return 0
        def move = text =~ /mv '([^']+)' '([^']+)'/
        if (move.find() && j.files.containsKey(relative(move.group(1)))) j.files[relative(move.group(2))] = j.files.remove(relative(move.group(1)))
        def sastDownload = text =~ /get_result -i "[^"]*" -t html -d "([^"]+)"/
        if (sastDownload.find()) j.files[relative(sastDownload.group(1))] = issuesHtml(d.sast as List)
        def dastDownload = text =~ /-o "([^"]+)"/
        if (dastDownload.find()) j.files[relative(dastDownload.group(1))] = dastDownload.group(1).endsWith('.pdf') ? '%PDF-1.7' : issuesHtml(d.dast as List)
        if (text.contains('.irx')) j.files["${j.env.APPSCAN_SCAN_NAME}.irx".toString()] = 'irx'
        if (!a.returnStdout) return null
        Object goldenFix = spec.goldenFix ? GoldenFixResponder.sh(j, a, goldenFixCalls, manifests, reports) : null
        if (goldenFix != null) return goldenFix
        List c = d.coverage as List
        if (label == 'Coverage: find and read the reports') {
            String path = text.contains("--own 'maven'") ? 'target/site/jacoco/jacoco.xml' : 'build/reports/jacoco/test/jacocoTestReport.xml'
            return "S\t0\nR\tjacoco\t${path}\t${c[1]}\t${(c[1] as int) + (c[2] as int)}\t\t\nC\tPackage: com.bbh.certscanner, Class: CertificateService, Coverage: ${c[0]}, Missed: ${c[2]}, Covered: ${c[1]}"
        }
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
    List<Integer> evaluatedAt = []
    List<Map> evaluations = []
    j.steps['nexusPolicyEvaluation'] = { Map a ->
        evaluatedAt << j.calls.size()
        evaluations << a
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
        harness.run { pipelineVar.call(jenkinsfile) }
    } catch (Throwable t) {
        crash = SandboxHarness.rejectionOf(t) ?: t.toString()
    }
    return [j: j, var: pipelineVar, openshift: openshift, crash: crash, html: (j.files['report/pipeline-report.html'] ?: '') as String,
            goldenFixCalls: goldenFixCalls, evaluatedAt: evaluatedAt, evaluations: evaluations]
}

List<String> staticStages = [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT]
List<String> extendedStages = [MONITOR, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
List<String> fullStages = [MONITOR, UNIT, NIQ, SAST, SONAR, SNAPSHOT, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]

File goldenDir = new File(root, 'test/sandbox/golden')

Closure influxOf = { FakeScript j ->
    j.files.findAll { k, v -> (k as String).startsWith('influx_payload_') }.collect { k, v -> v as String }.join('\n')
}

Closure goldenView = { Map r ->
    FakeScript j = r.j as FakeScript
    return ['== calls', j.calls.join('\n'),
            '== log', j.log.findAll { !it.startsWith('[INIT]') && !it.startsWith('[POLICY]') }.join('\n'),
            '== release-gate.json', (j.files['release-gate.json'] ?: '') as String,
            '== influx', influxOf(j),
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
        rule('durations in the report are measured on the wall clock', /(>|&nbsp;)(-|\d+s|\d+m(?: \d+s)?)</, '$1<d><'),
        rule('the portal documents are stamped with the UTC time they were read', /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/, '<time>'),
        rule('the coverage scan script is pinned by test/coverage/scan-scenarios.sh, so a golden names it only',
                /(?ms)(^sh \[Coverage: find and read the reports\] [^\n]*\npython3 -I - [^\n]*<<'PYEOF'\n).*?^PYEOF$/, '$1<scan script>\nPYEOF')
]
Map bootstrapRule = rule('configure() reads the portal on a bootstrap agent before the pipeline starts its own agent (L4)',
        /(?ms)\A== calls\n.*?^(?=agent )/, '== calls\n')
Map portalLogRule = rule('the portal read logs one [PORTAL] line per key (L2)', /(?m)^\[PORTAL\].*\n/, '')
Map headerRule = rule('the report header names the portal keys with renderedAt and sha256 (accepted difference 1)',
        /<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>DevSecOps portal: [^\n]*?<\/div>/, '')
List<Map> portalRules = [
        bootstrapRule,
        portalLogRule,
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
        rule('build_evidence names the settings file of a run without a key', /,config_file="[^"]*"/, '')
]
List<Map> keylessRules = [bootstrapRule, portalLogRule,
                          rule('build_evidence has no config_rendered_at and config_sha256 without portal documents (L10)', /,config_rendered_at="[^"]*",config_sha256="[^"]*"/, ''),
                          headerRule] + keylessMarks

Closure normalise = { String text, List<Map> rules ->
    String out = text
    rules.each { Map r -> out = (r.apply as Closure).call(out) as String }
    return out
}

Map baselines = [
        b815d55: [recorded: 'on the library before the portal integration', record: 'record it on b815d55 with RECORD_GOLDEN=1', rules: portalRules],
        nexusiq: [recorded: 'when the Nexus IQ GoldenFix pipeline was added', record: 'record it with RECORD_GOLDEN=1 and review it', rules: []],
        catalog: [recorded: 'when shared version catalogs were added', record: 'record it with RECORD_GOLDEN=1 and review it', rules: []]
]

List<Map> specs = [
        [name: 'security pipeline, green', var: 'devSecOpsSecurityPipeline', fail: false, job: 'CertScanner-security-pipeline', buildNumber: 212,
         params: [RUN_EXTENDED_PIPELINE: true], executed: staticStages, skipped: [NODE], result: 'SUCCESS', post: ['always', 'success'],
         coverageMinLine: 75],
        [name: 'security pipeline, orange', var: 'devSecOpsSecurityPipeline', fail: true, job: 'CertScanner-security-pipeline', buildNumber: 213,
         params: [RUN_EXTENDED_PIPELINE: true], executed: staticStages, skipped: [NODE], result: 'UNSTABLE', post: ['always', 'unstable']],
        [name: 'security pipeline, Maven artifact to Nexus', var: 'devSecOpsSecurityPipeline', fail: false, job: 'CertScanner-security-pipeline',
         buildNumber: 214, params: [RUN_EXTENDED_PIPELINE: false], executed: staticStages, skipped: [NODE], result: 'SUCCESS',
         post: ['always', 'success'], mavenVmDelivery: true],
        [name: 'SAST pipeline, green', var: 'devSecOpsSASTScanningPipeline', fail: false, job: 'CertScanner-sast-pipeline', buildNumber: 87,
         params: [:], executed: [MONITOR, SAST], skipped: [NODE], result: 'SUCCESS', post: ['always', 'success']],
        [name: 'SAST pipeline, orange', var: 'devSecOpsSASTScanningPipeline', fail: true, job: 'CertScanner-sast-pipeline', buildNumber: 88,
         params: [:], executed: [MONITOR, SAST], skipped: [NODE], result: 'UNSTABLE', post: ['always', 'unstable']],
        [name: 'Nexus IQ GoldenFix pipeline, green', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: false, job: 'CertScanner-nexusiq-pipeline', buildNumber: 61,
         params: [:], executed: [MONITOR, BUILD, NIQ], skipped: [NODE], result: 'SUCCESS', post: ['always', 'success'], baseline: 'nexusiq'],
        [name: 'Nexus IQ GoldenFix pipeline, orange', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: true, goldenFix: true, job: 'CertScanner-nexusiq-pipeline',
         buildNumber: 62, params: [:], executed: [MONITOR, BUILD, NIQ], skipped: [NODE], result: 'UNSTABLE', post: ['always', 'unstable'], baseline: 'nexusiq'],
        [name: 'Nexus IQ GoldenFix pipeline, build failure', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: false, breakBuild: true, job: 'CertScanner-nexusiq-pipeline',
         buildNumber: 63, params: [:], executed: [MONITOR], skipped: [NODE, NIQ], result: 'FAILURE', post: ['always', 'failure'], failedStage: BUILD, baseline: 'nexusiq'],
        [name: 'Nexus IQ GoldenFix pipeline, shared catalog', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: true, goldenFix: true, sharedCatalog: true,
         job: 'CertScanner-nexusiq-pipeline', buildNumber: 64, params: [:], executed: [MONITOR, BUILD, NIQ], skipped: [NODE], result: 'UNSTABLE',
         post: ['always', 'unstable'], baseline: 'catalog'],
        [name: 'extended pipeline, green', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 118,
         params: [DEPLOY_HIGHER_ENV: true],
         executed: extendedStages, skipped: [], result: 'SUCCESS', post: ['always', 'success'], dastDisabledFor: 'backend-api'],
        [name: 'extended pipeline, orange', var: 'devSecOpsExtendedPipeline', fail: true, job: 'CertScanner-extended-pipeline', buildNumber: 119,
         params: [DEPLOY_HIGHER_ENV: true],
         upstreamViolations: ['gui SAST (AppScan) high 3 > 0'], executed: extendedStages - [RELEASE, QC], skipped: [RELEASE, QC], result: 'UNSTABLE',
         post: ['always', 'unstable']],
        [name: 'full pipeline, green', var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', buildNumber: 341,
         params: [DEPLOY_HIGHER_ENV: true], executed: fullStages, skipped: [NODE], result: 'SUCCESS', post: ['always', 'success'],
         files: ['devsecops_config.yaml': 'projects: [', 'config.yaml': 'projects: [']],
        [name: 'full pipeline, orange', var: 'devSecOpsPipeline', fail: true, job: 'CertScanner-devsecops-pipeline', buildNumber: 342,
         params: [DEPLOY_HIGHER_ENV: true], executed: fullStages - [RELEASE, QC], skipped: [NODE, RELEASE, QC], result: 'UNSTABLE',
         post: ['always', 'unstable']],
        [name: 'full pipeline, green but QC not selected', var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', buildNumber: 343,
         params: [DEPLOY_HIGHER_ENV: false], executed: fullStages - [QC], skipped: [NODE, QC], result: 'SUCCESS', post: ['always', 'success']],
        [name: 'full pipeline, stage failure', var: 'devSecOpsPipeline', fail: false, breakSast: true, job: 'CertScanner-devsecops-pipeline', buildNumber: 344,
         params: [DEPLOY_HIGHER_ENV: true], executed: [MONITOR, UNIT, NIQ], skipped: [NODE] + (fullStages - [MONITOR, UNIT, NIQ, SAST]),
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
    int expectedGrey = ((spec.skipped as List) - NODE).size() * 2
    check("${name}: the stage flow shows what the stages collected, not empty grey boxes",
            flow && greyBoxes <= expectedGrey && flow.contains('&#9201;')
                    && (!spec.executed.contains(UNIT) || !flow.contains('not measured')),
            "grey SKIP boxes ${greyBoxes} (at most ${expectedGrey}), any stage duration ${flow.contains('&#9201;')}, coverage not measured ${flow.contains('not measured')}")
    check("${name}: release gate verdict written for the downstream pipeline", j.jsonFiles.containsKey('release-gate.json')
            && ((j.jsonFiles['release-gate.json'] as Map).allowed as boolean) == (spec.result == 'SUCCESS' && !spec.failedStage),
            j.jsonFiles['release-gate.json'])
    if (spec.executed.contains(UNIT)) {
        check("${name}: each service reads its coverage reports with one scan and the report lists them",
                j.calls.count { it.startsWith('sh [Coverage: find and read the reports] ') } == 2
                        && html.contains('<th>Report</th>') && html.contains('>All services</div>'),
                j.calls.findAll { it.contains('Coverage: ') })
    }
    if (spec.coverageMinLine) {
        check("${name}: the report shows and checks the coverage required by the portal's global settings (${spec.coverageMinLine}%)",
                html.contains("required&nbsp;<b>${spec.coverageMinLine}%</b>".toString()) && !html.contains('required&nbsp;<b>60%</b>'),
                'the report does not use the required coverage from the portal defaults')
    }
    if (spec.var == 'devSecOpsSecurityPipeline') {
        String description = "Trigger the extended pipeline named in jenkins.pipeline.extendedPipeline of the service in ${spec.keyless ? 'devsecops_config.yaml or config.yaml' : 'the DevSecOps portal'}"
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
                catalog.contains('jackson = "2.13.5"') && catalog.contains('strictly = "4.1.108.Final"') && catalog.contains('commons-text:1.10.0')
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
        check("${name}: the run-state file and the release gate are taken from the security pipeline", j.calls.contains('copyArtifacts DevSecOps/CertScanner-security-pipeline filter=pipeline-config.yaml,release-gate.json'),
                j.calls.findAll { it.startsWith('copyArtifacts') })
    }
    if (spec.mavenVmDelivery) {
        check("${name}: the Maven artifact is deployed to the Nexus snapshot repository and the version is written back",
                j.calls.count('writeYaml pipeline-config.yaml') == 2 && (j.log.any { it.contains('deploy:deploy-file') && it.contains('-Dfile=') }),
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
    Map files = (spec.files ?: [:]) as Map
    List<String> probed = j.probes.findAll { it.tokenize(' ').last() in repositoryFiles }
    if (files && !spec.keyless) {
        check("${name}: a run with a key never looks at devsecops_config.yaml or config.yaml, even when both are in the workspace", !probed, probed)
    }
    if (spec.keyless) {
        String file = files.containsKey(repositoryFiles[0]) ? repositoryFiles[0] : repositoryFiles[1]
        List<String> evidence = influxOf(j).readLines().findAll { it.startsWith('build_evidence,') }
        check("${name}: the portal is not read and the console says which file was read${files.size() > 1 ? ' and which was not' : ''}",
                !j.calls.any { it.contains('/api/dso/config/') || it.contains('DSO_PORTAL_KEYS') }
                        && log.contains("[INIT] No pipeline key in the Jenkinsfile: the services come from ${file} in the repository, the global settings from the library (resources/com/bbh/devsecops/defaults.yaml)".toString())
                        && (files.size() < 2 || log.contains("[INIT] ${repositoryFiles.join(' and ')} are both in the repository: ${file} is read, ${repositoryFiles[1]} is not".toString()))
                        && probed.containsAll(repositoryFiles.collect { "fileExists ${it}".toString() } + ["readYaml ${file}".toString()]),
                log.findAll { it.startsWith('[INIT] No pipeline key') || it.contains('are both in the repository') } + probed)
        check("${name}: the report header and every build_evidence line name ${file}",
                html.contains("<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>No pipeline key: settings from ${file} in the repository</div>".toString())
                        && evidence && evidence.every { it.contains(",config_file=\"${file}\"".toString()) },
                evidence)
    }
    List unhandled = j.unhandled.unique()
    if (unhandled) println "      note: steps not emulated by the fake Jenkins: ${unhandled}"
    Closure slugOf = { String text -> text.toLowerCase().replaceAll(/[^a-z0-9]+/, '-') }
    Map baseline = baselines[spec.baseline ?: 'b815d55'] as Map
    List<Map> rules = (spec.keyless ? (spec.baseline ? keylessRules : portalRules + keylessMarks) : baseline.rules) as List<Map>
    String actual = normalise(goldenView(r), baseRules)
    File golden = new File(goldenDir, "${slugOf((spec.golden ?: name) as String)}.txt")
    if (!golden.exists() && System.getenv('RECORD_GOLDEN') == '1') {
        golden.parentFile.mkdirs()
        golden.text = actual
        println "      note: golden output recorded at ${golden}"
    }
    if (!golden.exists()) {
        check("${name}: a golden output recorded ${baseline.recorded} exists", false, "${golden} is missing; ${baseline.record}")
        return
    }
    boolean same = normalise(golden.text, rules) == normalise(actual, rules)
    File diffFile = new File(goldenDir, "${slugOf(name)}.actual.txt")
    if (same) diffFile.delete() else diffFile.text = actual
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

Map noFile = runPipeline([name: 'full pipeline without a key and without a file', var: 'devSecOpsPipeline', keyless: true, fail: false,
                          job: 'CertScanner-devsecops-pipeline', buildNumber: 345, params: [DEPLOY_HIGHER_ENV: true]])
FakeCpsScript noFileVar = noFile.var as FakeCpsScript
FakeScript noFileJenkins = noFile.j as FakeScript
String noFileHtml = noFile.html as String
String neitherFile = "[INIT] No pipeline key in the Jenkinsfile, and neither devsecops_config.yaml nor config.yaml is at the root of the repository: " +
        "commit devsecops_config.yaml, which the product page of the DevSecOps portal downloads, or give the Jenkinsfile the pipeline key of the service " +
        "(a custom Jenkinsfile calls devSecOpsApi.configure('full', [pipelineKey: '<key from the DevSecOps portal>']) before devSecOpsApi.initialize())"
check('full pipeline without a key and without a file: the Monitor stage fails with the message that names both files, the report and the release gate are still written, and the portal is not read',
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
Closure loginsOf = { FakeScript j, String marker, String pattern ->
    j.calls.findIndexValues { it.contains(marker) }.collect { idx ->
        int i = idx as int
        "${j.calls[i - 1]} ${(j.calls[i] =~ pattern)[0][1]}".toString()
    }
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
Map perService = focused([name: 'full pipeline, AppScan settings per service', buildNumber: 351, configure: { Map p ->
    ((p.gui as Map).asoc as Map).token = 'gui-appscan-key-secret'
    ((p.gui as Map).sast as Map).remove('scanName')
    ((p['backend-api'] as Map).asoc as Map).keyId = backendKey
    ((p['backend-api'] as Map).sast as Map).scanName = 'CertScanner Backend.Nightly'
}])
FakeScript perServiceJenkins = perService.j as FakeScript
List sastLogins = loginsOf(perServiceJenkins, ' api_login ', /-u "([^"]+)"/)
List guiThenBackend = ["withCredentials [gui-appscan-key-secret] ${guiKey}".toString(), "withCredentials [${backendKey}] ${backendKey}".toString()]
check('full pipeline, AppScan settings per service: SAST logs in with the key of each service before its IRX is queued and before its results are read, '
        + 'the secret from asoc.token or, without it, from the credentials named like the key ID', sastLogins == guiThenBackend + guiThenBackend, sastLogins)
List dastLogins = loginsOf(perServiceJenkins, 'Account/ApiKeyLogin', /(\{.*\})/).collect { (it as String).replace('\\"', '"') }
check('full pipeline, AppScan settings per service: DAST signs in with the key ID and the Secret text of each service',
        dastLogins == ["withCredentials [gui-appscan-key-secret] {\"KeyId\":\"${guiKey}\",\"KeySecret\":\"fake-APPSCAN_KEY_SECRET\"}".toString(),
                       "withCredentials [${backendKey}] {\"KeyId\":\"${backendKey}\",\"KeySecret\":\"fake-APPSCAN_KEY_SECRET\"}".toString()]
                && !perServiceJenkins.calls.any { it.contains('hcl-app-scan-acount') },
        dastLogins)
List queued = perServiceJenkins.calls.findAll { it.contains('queue_analysis -a') }.collect { String c ->
    "${(c =~ /-f "([^"]+)"/)[0][1]} ${(c =~ /-n "([^"]+)"/)[0][1]} ${(c =~ /queue-([^"]+)\.log/)[0][1]}".toString()
}
String dastPayload = perServiceJenkins.files.find { it.key.endsWith('dast_payload.json') }?.value ?: ''
check('full pipeline, AppScan settings per service: sast.scanName names the SAST scan of its service, sanitised, and a service without it keeps <scan name>-sast; '
        + 'the IRX, the queue log, the scan ID file, the report names and the DAST scan name stay as they were',
        queued == ['/ws/certscanner-gui.irx certscanner-gui-sast certscanner-gui-sast', '/ws/certscanner-backend.irx certscanner-backend-nightly certscanner-backend-sast']
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

Map oneDelivery = focused([name: 'full pipeline, Nexus delivery disabled for one service', buildNumber: 356, configure: { Map p ->
    rdOf(p, 'backend-api', 'openshift').nexusDeliveryEnabled = false
}])
check('full pipeline, Nexus delivery disabled for one service: the stages stay PASS for the service that delivered and deployed, and the other service is skipped',
        [SNAPSHOT, RD, QC].collect { resultOf(oneDelivery, it) } == ['PASS'] * 3
                && !(oneDelivery.openshift as FakeOpenShift).calls.any { it.startsWith('startBuild') || it.startsWith('rollout') }
                && (oneDelivery.j as FakeScript).calls.count('step UCDeployPublisher') == 2
                && (oneDelivery.j as FakeScript).log.count { it.startsWith('[OPENSHIFT] backend-api:') } == 2,
        [SNAPSHOT, RD, QC].collect { resultOf(oneDelivery, it) })

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
        (nodeRun.var as FakeCpsScript).executedStages == [MONITOR, NODE] + fullStages.drop(1) && resultOf(nodeRun, NODE) == 'PASS'
                && nodeSteps == ["withEnv [PATH+NODEJS=/opt/tools/NodeJS-20/bin, npm_config_registry=${registry}, NODE_OPTIONS=--max-old-space-size=4096]".toString(),
                                 'sh [Node.js: npm ci]', 'sh [Node.js: npm run build]']
                && firstCall(nodeJenkins, 'sh [Node.js: npm run build]') < firstCall(nodeJenkins, 'sh [Gradle: ')
                && nodeJenkins.calls.count { it.startsWith('sh [Node.js: npm ci]') } == 1,
        nodeSteps + [resultOf(nodeRun, NODE)])
check('full pipeline, Node.js build for one service: one [NODE] line per service, the other service is not required and the release gate allows',
        nodeJenkins.log.findAll { it.startsWith('[NODE]') } == ['[NODE] gui: Node.js from installation NodeJS-20 in gui/frontend: npm ci && npm run build',
                                                              '[NODE] backend-api: build.node.enabled is not set - no Node.js build']
                && ((nodeJenkins.jsonFiles['release-gate.json'] as Map).allowed as boolean),
        nodeJenkins.log.findAll { it.startsWith('[NODE]') })

Closure boxesOf = { String html -> (html =~ />([0-9.]+)<\/span><span style='overflow-wrap:anywhere;word-break:break-word;min-width:0;'>([^<]+)<\/span>/).collect { "${it[1]} ${it[2]}".toString() } }
Closure boxOf = { String html, String name ->
    int at = html.indexOf(">${name}</span>".toString())
    int next = html.indexOf("<div style='border:1px solid", at)
    at < 0 ? '' : html.substring(at, next < 0 ? html.length() : next)
}
List<String> nodeBoxes = boxesOf(nodeRun.html as String)
check('full pipeline, Node.js build for one service: the report shows it as stage 0 after Monitor, the later stages keep their numbers, and InfluxDB gets its stage_event',
        nodeBoxes.take(6) == ['1.1 Monitor source changes (download sources) - gui', '1.2 Monitor source changes (download sources) - backend-api',
                              '0.1 Node.js build - gui', '0.2 Node.js build - backend-api', '2.1 Unit tests - gui', '2.2 Unit tests - backend-api']
                && nodeBoxes.contains('13.2 Higher test environment deployment - backend-api')
                && influxOf(nodeJenkins).readLines().any { it.startsWith('stage_event,') && it.contains('stage=Node.js\\ build') },
        nodeBoxes.take(6))

Map nodeFailure = runPipeline([name: 'full pipeline, Node.js build failure', var: 'devSecOpsPipeline', fail: false, breakNode: true, job: 'CertScanner-devsecops-pipeline',
                               buildNumber: 358, params: [DEPLOY_HIGHER_ENV: true], configure: { Map p -> nodeOf(p, 'gui').node = [enabled: true] }])
FakeCpsScript nodeFailureVar = nodeFailure.var as FakeCpsScript
String failureHtml = nodeFailure.html as String
check('full pipeline, Node.js build failure: the stage fails hard, the later stages are skipped, the report is written and Unit tests is numbered 2 and blocked',
        !nodeFailure.crash && nodeFailureVar.executedStages == [MONITOR] && nodeFailureVar.skippedStages == fullStages.drop(1)
                && nodeFailureVar.stageFailures.size() == 1 && nodeFailureVar.stageFailures[0].startsWith(NODE)
                && (nodeFailure.j as FakeScript).currentBuild.currentResult == 'FAILURE' && resultOf(nodeFailure, NODE) == 'FAIL'
                && boxesOf(failureHtml).take(6).drop(2) == ['0.1 Node.js build - gui', '0.2 Node.js build - backend-api', '2.1 Unit tests - gui', '2.2 Unit tests - backend-api']
                && boxOf(failureHtml, 'Unit tests - gui').contains('pipeline blocked by previous failure')
                && !boxOf(failureHtml, 'Node.js build - gui').contains('pipeline blocked by previous failure'),
        [nodeFailure.crash, nodeFailureVar.executedStages, nodeFailureVar.stageFailures, boxesOf(failureHtml).take(6)])

Closure irxExcluded = { FakeScript j ->
    j.calls.findAll { it.startsWith('withEnv [APPSCAN_CMD_RUNTIME=') && it.contains('APPSCAN_SOURCE_DIR_RUNTIME=') }.collect { (it =~ /APPSCAN_EXCLUDED_FOLDERS=(.*?), APPSCAN_OPTS=/)[0][1] }
}
Closure sastRun = { String name, int buildNumber, Closure configure ->
    Map r = runPipeline([name: name, var: 'devSecOpsSASTScanningPipeline', fail: false, job: 'CertScanner-sast-pipeline', buildNumber: buildNumber,
                         params: [:], defaults: nodeDefaults, configure: configure])
    check("${name}: the pipeline var runs to the end without a stage failure, with the Node.js build after Monitor",
            !r.crash && (r.var as FakeCpsScript).stageFailures.isEmpty() && (r.var as FakeCpsScript).executedStages == [MONITOR, NODE, SAST],
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
                && irxExcluded(sastFirstJenkins) == ['test,node_modules', 'test,node_modules'],
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
check('SAST pipeline, Node.js build with included folders: the IRX keeps its folders when includedDirs is set',
        irxExcluded(sastIncluded.j as FakeScript) == ['', ''], irxExcluded(sastIncluded.j as FakeScript))

check('full pipeline, Node.js build for one service: nexusIqScan false keeps the Nexus IQ scan patterns of the service',
        (nodeRun.evaluations as List<Map>).collect { it.iqScanPatterns } == [[[scanPattern: '**/gui/build/libs/*.jar']], [[scanPattern: '**/api/target/*.jar']]],
        (nodeRun.evaluations as List<Map>).collect { it.iqScanPatterns })

Map nexusIqNode = runPipeline([name: 'Nexus IQ GoldenFix pipeline, Node.js build', var: 'devSecOpsNexusIqGoldenFixPipeline', fail: false, job: 'CertScanner-nexusiq-pipeline',
                               buildNumber: 64, params: [:], configure: { Map p -> nodeOf(p, 'gui').node = [enabled: true, dir: 'gui/frontend'] }])
FakeScript nexusIqNodeJenkins = nexusIqNode.j as FakeScript
check('Nexus IQ GoldenFix pipeline, Node.js build: the Node.js build runs before Build artifact and package-lock.json of its folder joins the Nexus IQ scan of that service only',
        !nexusIqNode.crash && (nexusIqNode.var as FakeCpsScript).executedStages == [MONITOR, NODE, BUILD, NIQ]
                && firstCall(nexusIqNodeJenkins, 'sh [Node.js: npm run build]') < firstCall(nexusIqNodeJenkins, 'sh [Gradle: ')
                && (nexusIqNode.evaluations as List<Map>).collect { it.iqScanPatterns } == [[[scanPattern: '**/gui/build/libs/*.jar'], [scanPattern: 'gui/frontend/package-lock.json']],
                                                                                          [[scanPattern: '**/api/target/*.jar']]],
        [nexusIqNode.crash, (nexusIqNode.var as FakeCpsScript).executedStages, (nexusIqNode.evaluations as List<Map>).collect { it.iqScanPatterns }])

Map extendedNode = runPipeline([name: 'extended pipeline, Node.js build', var: 'devSecOpsExtendedPipeline', fail: false, job: 'CertScanner-extended-pipeline', buildNumber: 120,
                                params: [DEPLOY_HIGHER_ENV: true], dastDisabledFor: 'backend-api', configure: { Map p -> nodeOf(p, 'gui').node = [enabled: true] }])
FakeScript extendedNodeJenkins = extendedNode.j as FakeScript
check('extended pipeline, Node.js build: the release delivery builds Node.js once before the publish of the service that enables it, without a line for the other service',
        !extendedNode.crash && (extendedNode.var as FakeCpsScript).executedStages == extendedStages && resultOf(extendedNode, RELEASE) == 'PASS'
                && extendedNodeJenkins.calls.count { it.startsWith('sh [Node.js: npm ci]') } == 1
                && firstCall(extendedNodeJenkins, 'sh [Node.js: npm run build]') < firstCall(extendedNodeJenkins, 'sh ./gradlew publish')
                && extendedNodeJenkins.log.findAll { it.startsWith('[NODE]') } == ['[NODE] gui: Node.js from the agent PATH in the workspace: npm ci && npm run build'],
        [extendedNode.crash, resultOf(extendedNode, RELEASE)] + extendedNodeJenkins.log.findAll { it.startsWith('[NODE]') })

println "\n${failures == 0 ? 'ALL PIPELINE SCENARIOS PASSED' : failures + ' PIPELINE SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
