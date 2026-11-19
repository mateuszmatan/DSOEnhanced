package devsecops.test

import org.yaml.snakeyaml.Yaml

class ScenarioRunner {

    static Map runPipeline(Map spec, Map context) {
        File root = context.root as File
        File srcDir = new File(root, 'src')
        File stubDir = new File(root, 'test/sandbox/stub')
        File varsDir = new File(root, 'vars')
        String defaultsText = context.defaultsText as String
        String configText = context.configText as String
        String securityJob = context.securityJob as String
        Closure dataFor = context.dataFor as Closure
        Closure issuesHtml = context.issuesHtml as Closure
        List<String> secretResources = context.secretResources as List<String>
        SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
        FakeScript j = new FakeScript()
        FakeOpenShift openshift = new FakeOpenShift()
        Yaml yaml = new Yaml()
        Map defaults = yaml.load(defaultsText) as Map
        Map projects = yaml.load(configText) as Map
        if (spec.coverageMinLine) (((defaults.defaults as Map).coverage as Map)).minLine = spec.coverageMinLine
        if (spec.defaults) (spec.defaults as Closure).call(defaults.defaults as Map)
        if (!spec.parallel) {
            (defaults.defaults as Map).staticAnalysis = [parallel: false]
            (defaults.defaults as Map).dynamicAnalysis = [parallel: false]
        }
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
            (context.archive as Closure).call(archived.projects as Map, spec)
            Map gate = [job: securityJob, build: '213', allowed: !spec.upstreamViolations, violations: spec.upstreamViolations ?: []]
            j.upstream[securityJob] = ['pipeline-config.yaml': yaml.dump(archived), 'release-gate.json': groovy.json.JsonOutput.toJson(gate)] + ((spec.upstreamFiles ?: [:]) as Map)
        }
        Map upstreamJob = spec.var == 'devSecOpsExtendedPipeline' ? [securityPipeline: securityJob] : [:]
        Map jenkinsfile = [agentNames: ['linux-agent', 'windows-agent']] + (spec.autoDetect ? [:] : [projectNames: (projects.projects as Map).keySet().join(',')]) + upstreamJob
        j.resources['com/bbh/devsecops/defaults.yaml'] = yaml.dump(defaults)
        Map<String, Map> data = dataFor(spec.fail as boolean) as Map<String, Map>

        j.env.vars.putAll([
                BUILD_URL   : "https://jenkins.bbh.com/job/DevSecOps/job/${spec.job}/${spec.buildNumber}/".toString(),
                JOB_NAME    : "DevSecOps/${spec.job}".toString(),
                BUILD_NUMBER: String.valueOf(spec.buildNumber),
                BUILD_ID    : String.valueOf(spec.buildNumber),
                GIT_BRANCH  : 'origin/develop',
                PATH        : '/usr/bin'
        ])
        j.env.vars.putAll((spec.env ?: [:]) as Map)
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
        List<String> sastQueued = []
        Map<String, String> manifests = spec.sharedCatalog ? GoldenFixResponder.SHARED_CATALOG : GoldenFixResponder.MANIFESTS
        Map reports = spec.sharedCatalog ? GoldenFixResponder.SHARED_CATALOG_REPORTS : GoldenFixResponder.REPORTS
        if (spec.goldenFix) GoldenFixResponder.plantManifests(j, manifests)
        if (spec.sharedCatalog) data['backend-api'].niq = [0, 2, 1]

        Closure project = { -> (j.env.CURRENT_PROJECT_NAME ?: 'gui') as String }
        Closure relative = { String path -> path.startsWith('/ws/') ? path.substring(4) : path }
        j.shHandler = { Map a ->
            List answer = spec.respond ? (spec.respond as Closure).call(j, a) as List : null
            if (answer != null) return answer[0]
            String text = String.valueOf(a.script ?: '')
            String label = String.valueOf(a.label ?: '')
            Map d = data[project()] ?: data.gui
            if (spec.fail && project() == 'backend-api' && label.startsWith('Maven: test')) throw new FakeAbort('script returned exit code 1')
            if (spec.breakSast && text.contains('Account/ApiKeyLogin')) throw new FakeAbort('script returned exit code 1')
            if (spec.breakSastWait && text.contains('/api/v4/Scans/Sast/')) throw new FakeAbort('script returned exit code 1')
            if (spec.breakSastCopy && label == 'Copy the workspace for the SAST compile and scan') throw new FakeAbort('tar: write error: No space left on device')
            if (spec.breakBuild && label.startsWith('Gradle: ')) throw new FakeAbort('script returned exit code 1')
            if (spec.breakNode && label.startsWith('Node.js: ')) throw new FakeAbort('script returned exit code 1')
            if (a.returnStatus) return 0
            def move = text =~ /mv '([^']+)' '([^']+)'/
            if (move.find() && j.files.containsKey(relative(move.group(1)))) j.files[relative(move.group(2))] = j.files.remove(relative(move.group(1)))
            def reportDownload = text =~ /-o "([^"]+)"/
            if (reportDownload.find()) {
                String out = reportDownload.group(1)
                j.files[relative(out)] = out.endsWith('.pdf') ? '%PDF-1.7' : issuesHtml((out.contains('dast') ? d.dast : d.sast) as List)
            }
            if (text.contains('.irx')) j.files["${j.env.APPSCAN_SCAN_NAME}.irx".toString()] = 'irx'
            def helpOutput = text =~ /-Dexpression=settings\.localRepository'? '?-Doutput=([^\s']+)/
            if (helpOutput.find()) j.files[helpOutput.group(1)] = '/home/jenkins/.m2/repository\n'
            def fetched = text =~ /-Dartifact=([^:\s']+):([^:\s']+):([^:\s']+):([^:\s']+):([^:\s']+)'? '?-Dmaven\.repo\.local=([^\s']+)/
            if (fetched.find()) {
                String g = fetched.group(1), art = fetched.group(2), v = fetched.group(3), ext = fetched.group(4), cls = fetched.group(5)
                j.files["${fetched.group(6)}/${g.replace('.', '/')}/${art}/${v}/${art}-${v}-${cls}.${ext}".toString()] = 'downloaded'
            }
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
            if (text.contains('/api/v4/FileUpload')) return '{"FileId":"FILE-ID"}'
            if (text.contains('/api/v4/Scans/Sast/')) return '{"LatestExecution":{"Status":"Ready"}}'
            if (text.contains('/api/v4/Scans/Sast')) {
                Map payload = new groovy.json.JsonSlurper().parseText(j.files['sast_payload.json'] ?: '{}') as Map
                String upload = j.calls.findAll { it.contains('/api/v4/FileUpload') }.collect { (it =~ /fileToUpload=@([^"]+)"/)[0][1] }.last()
                sastQueued << "${upload} ${payload.ScanName} ${payload.AppId}".toString()
                return '{"Id":"5f3c8a2e-1b4d-4c6e-9a7b-2d8e0f1a3b5c"}'
            }
            if (text.contains('ApiKeyLogin')) return '{"Token":"BEARER-TOKEN"}'
            if (text.contains('/api/v4/Scans/Dast/')) return '{"LatestExecution":{"Status":"Ready"}}'
            if (text.contains('/api/v4/Scans/Dast')) return '{"Id":"DAST-SCAN-ID"}'
            if (text.contains('/api/v4/Reports/Security/Scan/')) return '{"Id":"REPORT-ID"}'
            if (text.contains('/api/v2/applications?publicId=')) return '{"applications":[{"id":"7d3b2c1a9e8f4a6b"}]}\n200'
            if (text.contains('/policy')) return '{"components":[]}\n200'
            if (text.contains('print -quit')) return '0'
            if (text.contains('date +%Y%m%d%H%M')) return '202609191405'
            if (text.contains('mktemp -d')) return '/ws@tmp/nexus-gradle.Ab12Cd34'
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

        if (spec.prepare) (spec.prepare as Closure).call(j)
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
                goldenFixCalls: goldenFixCalls, sastQueued: sastQueued, evaluatedAt: evaluatedAt, evaluations: evaluations]
    }

    static String influxOf(FakeScript j) {
        j.files.findAll { k, v -> (k as String).startsWith('influx_payload_') }.collect { k, v -> v as String }.join('\n')
    }

    static String goldenView(Map r) {
        FakeScript j = r.j as FakeScript
        return ['== calls', j.calls.join('\n'),
                '== log', j.log.findAll { !it.startsWith('[INIT]') && !it.startsWith('[POLICY]') }.join('\n'),
                '== release-gate.json', (j.files['release-gate.json'] ?: '') as String,
                '== influx', influxOf(j),
                '== report', r.html as String].join('\n') + '\n'
    }

    static Map rule(String reason, String pattern, String replacement) {
        [reason: reason, apply: { String text -> text.replaceAll(pattern, replacement) }]
    }

    static List<Map> baseRules() {
        return [
                rule('build tags carry the UTC time of the run', /\d{8}-\d{6}/, '<stamp>'),
                rule('temporary file names carry System.currentTimeMillis()', /(?<!\d)\d{13}(?!\d)/, '<ms>'),
                rule('the report header shows the wall-clock time of the run', /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/, '<time>'),
                rule('InfluxDB points carry the epoch second of the run', /(?m) \d{10}$/, ' <s>'),
                rule('stage and job timings are measured on the wall clock', /\b(start_time|end_time|duration_ms|duration_s|lead_time_s)=\d+i/, '$1=<n>i'),
                rule('durations in the log are measured on the wall clock', /duration=\S+/, 'duration=<d>'),
                rule('the times of the parallel stages are measured on the wall clock', /(?m)^(\[PARALLEL\] [^:\n]+: one after another ).*$/, '$1<times>'),
                rule('the Manual actions vs DevSecOps Automated Actions table at the bottom of the report is newer than every golden and is checked on its own',
                        /\n<section id='manual-vs-automated'.*?<\/section>/, ''),
                rule('durations in the report are measured on the wall clock', /(>|&nbsp;)(-|\d+s|\d+m(?: \d+s)?)</, '$1<d><'),
                rule('the coverage scan script is pinned by test/coverage/scan-scenarios.sh, so a golden names it only',
                        /(?ms)(^sh \[Coverage: find and read the reports\] [^\n]*\npython3 -I - [^\n]*<<'PYEOF'\n).*?^PYEOF$/, '$1<scan script>\nPYEOF'),
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
                rule('pull request decoration logs what it did', /(?m)^(\[error\] \[HTTP\] Bitbucket: (find open pull request|comments of pull request)|\[SECURITY-PR\]|\[DAST-PR\]).*\n/, '')
        ]
    }

    static String normalise(String text, List<Map> rules) {
        String out = text
        rules.each { Map r -> out = (r.apply as Closure).call(out) as String }
        return out
    }
}
