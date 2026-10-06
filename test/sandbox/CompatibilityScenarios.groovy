import devsecops.test.FakeScript
import devsecops.test.PortalFixtures
import devsecops.test.SandboxHarness
import groovy.json.JsonSlurperClassic
import org.yaml.snakeyaml.Yaml

static List differences(Object a, Object b, String path) {
    if (a instanceof Map && b instanceof Map) {
        return (a.keySet() + b.keySet()).unique().collectMany { k -> differences(a[k], b[k], "${path}.${k}".toString()) }
    }
    if (a instanceof List && b instanceof List && a.size() == b.size()) {
        return (0..<a.size()).collectMany { int i -> differences(a[i], b[i], "${path}[${i}]".toString()) }
    }
    return a == b ? [] : ["${path}: ${a} <> ${b}".toString()]
}

static int dropPath(Map map, List path, Closure when) {
    String head = path[0]
    if (map == null || !map.containsKey(head)) return 0
    if (path.size() == 1) {
        if (!when(map[head])) return 0
        map.remove(head)
        return 1
    }
    if (!(map[head] instanceof Map)) return 0
    int removed = dropPath(map[head], path.drop(1), when)
    if (removed && map[head].isEmpty()) map.remove(head)
    return removed
}

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File oldDir = new File(root, 'test/fixtures/b815d55')
File renderedDir = new File(root, 'test/fixtures/portal/rendered')

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
String defaultsText = new File(root, 'test/fixtures/defaults.yaml').getText('UTF-8')
String configText = new File(root, 'test/fixtures/CertScanner/config.yaml').getText('UTF-8')
Map defaults = (yaml.load(defaultsText) as Map).defaults as Map
Map projects = (yaml.load(configText) as Map).projects as Map
Map platform = (new JsonSlurperClassic().parseText(new File(root, 'test/fixtures/portal/certscanner-gui-full.json').getText('UTF-8')) as Map).platform as Map

Closure jenkins = {
    FakeScript j = new FakeScript()
    j.resourcesDir = new File(root, 'resources')
    j.env.vars.putAll([BUILD_NUMBER: '42', JOB_NAME: 'DevSecOps/CertScanner', DSO_PORTAL_URL: PortalFixtures.PORTAL_URL])
    return j
}

Closure observed = { FakeScript j, def state, Map runState ->
    PortalFixtures.copy([order         : (state.projectsAllCfg as Map).keySet().toList(),
                         projectsAllCfg: state.projectsAllCfg, cfgDefaults: state.cfgDefaults, cfg: state.cfg,
                         policyLimits  : state.policyLimits, minRequired: state.coverage.minRequired,
                         primary       : state.currentProjectName, runState: runState,
                         env           : ['PROJECT_NAMES', 'CURRENT_PROJECT_NAME', 'APPSCAN_SCAN_NAME', 'APPSCAN_KEY_ID'].collectEntries { [(it): j.env.vars.get(it)] },
                         log           : j.log.findAll { it.startsWith('[INIT]') || it.startsWith('[POLICY]') }])
}

Closure runOld = { List names ->
    FakeScript j = jenkins()
    j.env.vars.put('PROJECT_NAMES', names.join(','))
    j.resources['defaults.yaml'] = defaultsText
    j.files['config.yaml'] = configText
    SandboxHarness harness = new SandboxHarness(oldDir, stubDir, srcDir)
    def state = null
    harness.run {
        state = harness.type('com.bbh.core.PipelineState').newInstance()
        harness.type('com.bbh.config.ConfigLoader').newInstance(j, state).initialize()
    }
    return observed(j, state, (yaml.load(j.files['config.yaml']) as Map).projects as Map)
}

Closure runNew = { String variant, List<Map> documents ->
    FakeScript j = jenkins()
    List<String> keys = documents.collect { PortalFixtures.publish(j, it) }
    SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
    def state = null
    harness.run {
        state = harness.type('com.bbh.core.PipelineState').newInstance()
        def loader = harness.type('com.bbh.config.ConfigLoader').newInstance(j, state)
        loader.load(variant, [pipelineKeys: keys])
        loader.initialize()
    }
    return observed(j, state, (yaml.load(j.files['pipeline-config.yaml']) as Map).projects as Map)
}

Map logRules = [
        'the reader logs one [PORTAL] line per key (L2)'                       : { List log -> log.findAll { !(it as String).startsWith('[PORTAL]') } },
        'the [POLICY] header names the DevSecOps portal (L9, ConfigLoader.logPolicy)': { List log ->
            log.collect { (it as String).replace('Library security policy (resources/defaults.yaml), projects cannot change it:', 'Global settings from the DevSecOps portal, projects cannot change them:') }
        }
]
Closure normaliseLog = { Map run -> logRules.values().inject(run.log as List) { List log, Closure rule -> rule(log) } }

Closure always = { true }
Closure empty = { it == '' || it == [] || it == false }
SandboxHarness jobsHarness = new SandboxHarness(srcDir, stubDir)
def buildService = jobsHarness.type('com.bbh.build.BuildService')

List<Map> configRules = [
        [id    : 'R1',
         reason: 'DEAD keys no code reads: asoc.keySecret (AppScanService.groovy:47 binds asoc.token or APPSCAN_KEY_ID), ' +
                 'sast/sca/dast.scanName (the scan name comes from tools.sonar.projectName, ConfigLoader.groovy:94 and :187), ' +
                 'build.maven.javaPath (BuildService.groovy:590 reads javaPath of the service)',
         apply : { Map cfg, String variant ->
             [['asoc', 'keySecret'], ['sast', 'scanName'], ['sca', 'scanName'], ['dast', 'scanName'], ['build', 'maven', 'javaPath']].sum { dropPath(cfg, it, always) }
         }],
        [id    : 'R2',
         reason: 'for these keys only, "", [] and false read like a missing key: dast.presenceId (AppScanService.groovy:440), ' +
                 'scm.bitbucket.targetBranch (GoldenFixService.groovy:99), scm.bitbucket.reviewers (BitbucketPullRequestPublisher.groovy:28), ' +
                 'asoc.insecureTls (AppScanService.groovy:656), tools.nexusIq.failOnNetworkError (NexusIqService.groovy:41), ' +
                 'goldenFix.timeZone (GoldenFixService.groovy:357), goldenFix.verify.commands.<kind> (ManifestBuildVerifier.groovy:68)',
         apply : { Map cfg, String variant ->
             List paths = [['dast', 'presenceId'], ['scm', 'bitbucket', 'targetBranch'], ['scm', 'bitbucket', 'reviewers'], ['asoc', 'insecureTls'],
                           ['tools', 'nexusIq', 'failOnNetworkError'], ['goldenFix', 'timeZone']]
             paths += (((cfg.goldenFix as Map)?.verify as Map)?.commands as Map ?: [:]).keySet().collect { ['goldenFix', 'verify', 'commands', it] }
             paths.sum { dropPath(cfg, it as List, empty) }
         }],
        [id    : 'R3',
         reason: 'tests.<suite> compared as BuildService.normalizeTestJobs (BuildService.groovy:428, called at :288 for the suites ' +
                 'devSecOpsApi.groovy:209-211 run) expands it: the portal stores the expanded jobs (accepted difference 5)',
         apply : { Map cfg, String variant ->
             Map tests = (cfg.tests ?: [:]) as Map
             List suites = tests.keySet().findAll { it != 'unitTests' && tests[it] instanceof Map }.toList()
             suites.each { suite ->
                 Map suiteCfg = tests[suite] as Map
                 tests[suite] = suiteCfg.findAll { k, v -> !(k in ['defaults', 'urls', 'jobs']) } + [jobs: jobsHarness.run { buildService.normalizeTestJobs(suiteCfg) }]
             }
             suites.size()
         }],
        [id    : 'R4',
         reason: 'jenkins.pipeline.extendedPipeline is read only by OpenshiftService.groovy:319-322, which only devSecOpsSecurityPipeline.groovy:65 and :74 run; ' +
                 'the portal renders it into security documents only; a custom Jenkinsfile that calls devSecOpsApi.runExtendedPipeline() uses the security key (accepted difference 5)',
         apply : { Map cfg, String variant -> variant == 'security' ? 0 : dropPath(cfg, ['jenkins', 'pipeline', 'extendedPipeline'], always) }],
        [id    : 'R5',
         reason: 'SAME+: incrementalVersion true equals the code default of VmDeployService.groovy:254 and :327 (BuildUtils.booleanValue(..., true))',
         apply : { Map cfg, String variant ->
             List applications = ((((cfg.deploy as Map)?.vm as Map)?.dod as Map)?.applications ?: []) as List
             applications.sum(0) { application -> ((application as Map).components ?: []).sum(0) { dropPath(it as Map, ['incrementalVersion'], { it == true }) } }
         }],
        [id    : 'R6',
         reason: 'a literal influx.token counts as influx.credentialsId influxdb-token, the Secret text credentials the portal names instead ' +
                 '(InfluxDbService.groovy:24-25 accepts either, :37-42 binds the credentials)',
         apply : { Map cfg, String variant ->
             Map influx = cfg.influx as Map
             if (!influx?.token) return 0
             influx.remove('token')
             if (!influx.credentialsId) influx.credentialsId = 'influxdb-token'
             1
         }],
        [id    : 'R7',
         reason: 'SAME+: deploy.vm.dod skipWait false, deployWithSnapshot true, updateSnapshotComp false, includeOnlyDeployVersions true and ' +
                 'deployOnlyChanged false equal the code defaults of VmDeployService.groovy:168-172 (BuildUtils.booleanValue(..., <default>))',
         apply : { Map cfg, String variant ->
             Map dod = (((cfg.deploy as Map)?.vm as Map)?.dod ?: [:]) as Map
             [skipWait: false, deployWithSnapshot: true, updateSnapshotComp: false, includeOnlyDeployVersions: true, deployOnlyChanged: false]
                     .collect { key, value -> dropPath(dod, [key], { it == value }) }.sum(0)
         }]
]
Map hits = configRules.collectEntries { [(it.id): 0] }
Closure normalise = { Map run, String variant ->
    Map copy = PortalFixtures.copy(run)
    List<Map> configs = [copy.cfgDefaults as Map, copy.cfg as Map] + ((copy.projectsAllCfg as Map).values() as List) + ((copy.runState as Map).values() as List)
    configRules.each { Map rule ->
        configs.each { Map cfg ->
            int changed = (rule.apply as Closure).call(cfg, variant) as int
            hits[rule.id] = (hits[rule.id] as int) + changed
        }
    }
    copy.remove('log')
    return copy
}

[[variant: 'full', names: ['gui', 'backend-api']], [variant: 'security', names: ['backend-api', 'gui']]].each { Map spec ->
    String variant = spec.variant
    List names = spec.names as List
    Map before = runOld(names)
    Map after = runNew(variant, names.collect { PortalFixtures.document(variant, defaults, platform, it as String, projects[it] as Map) })
    List diff = differences(before.findAll { it.key != 'log' }, after.findAll { it.key != 'log' }, '')
    check("A ${variant} ${names.join(',')}: the b815d55 loader over config.yaml + defaults.yaml and the portal loader over documents of the same values load the same configuration",
            !diff && (before.log as List).any { (it as String).contains('resources/defaults.yaml') }, diff.take(10))
    List logDiff = differences(normaliseLog(before), normaliseLog(after), 'log')
    check("A ${variant} ${names.join(',')}: the [INIT] and [POLICY] lines match after the rules: ${logRules.keySet().join('; ')}", !logDiff, logDiff.take(10))
}

Closure rendered = { String name -> new JsonSlurperClassic().parseText(new File(renderedDir, "${name}.json").getText('UTF-8')) as Map }
List names = ['gui', 'backend-api']
Map before = normalise(runOld(names), 'full')
Map after = normalise(runNew('full', names.collect { rendered(it as String) }), 'full')
List diff = differences(before, after, '')
check('B full gui,backend-api: the b815d55 loader and the portal loader over the documents the portal renders from that config.yaml match after the declared rules',
        !diff, diff.take(20))
configRules.each { Map rule -> println "      ${rule.id}: ${rule.reason}" }
check('B: every declared rule changes something in these fixtures, so none is dead', hits.every { it.value > 0 }, hits)

Map mutated = rendered('gui')
((((mutated.projects as Map).gui as Map).scm as Map).bitbucket as Map).remove('authType')
(((mutated.projects as Map).gui as Map).asoc as Map).insecureTls = true
List caught = differences(before, normalise(runNew('full', [mutated, rendered('backend-api')]), 'full'), '')
check('B: the rules hide neither a key the portal dropped nor a non-empty value of a listed key',
        caught.any { (it as String).contains('authType') } && caught.any { (it as String).contains('insecureTls') }, caught)

println "\n${failures == 0 ? 'ALL COMPATIBILITY SCENARIOS PASSED' : failures + ' COMPATIBILITY SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
