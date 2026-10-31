import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import groovy.json.JsonOutput
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

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File oldDir = new File(root, 'test/fixtures/b815d55')

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

Closure jenkins = {
    FakeScript j = new FakeScript()
    j.resourcesDir = new File(root, 'resources')
    j.env.vars.putAll([BUILD_NUMBER: '42', JOB_NAME: 'DevSecOps/CertScanner'])
    return j
}

Closure copy = { Map value -> new JsonSlurperClassic().parseText(JsonOutput.toJson(value)) as Map }
Closure observed = { FakeScript j, def state, Map runState ->
    copy([order         : (state.projectsAllCfg as Map).keySet().toList(),
          projectsAllCfg: state.projectsAllCfg, cfgDefaults: state.cfgDefaults, cfg: state.cfg,
          policyLimits  : state.policyLimits, minRequired: state.coverage.minRequired,
          primary       : state.currentProjectName, runState: runState,
          env           : ['PROJECT_NAMES', 'CURRENT_PROJECT_NAME', 'APPSCAN_SCAN_NAME', 'APPSCAN_KEY_ID'].collectEntries { [(it): j.env.vars.get(it)] },
          log           : j.log.findAll { it.startsWith('[INIT]') || it.startsWith('[POLICY]') }])
}

Closure traced = { FakeScript j, Map run -> run + [trace: [calls: j.calls, probes: j.probes]] }
Closure loaded = { Map run -> run.findAll { !(it.key in ['log', 'trace']) } }

Closure runOld = { List names ->
    FakeScript j = jenkins()
    if (names) j.env.vars.put('PROJECT_NAMES', names.join(','))
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

String bundledName = 'com/bbh/devsecops/defaults.yaml'
Closure runRepo = { String variant, Map jenkinsfile, Map files, boolean bundled = false, File resourcesDir = new File(root, 'resources') ->
    FakeScript j = jenkins()
    j.resourcesDir = resourcesDir
    j.files.putAll(files)
    if (!bundled) j.resources[bundledName] = defaultsText
    SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
    def state = null
    try {
        harness.run {
            state = harness.type('com.bbh.core.PipelineState').newInstance()
            def loader = harness.type('com.bbh.config.ConfigLoader').newInstance(j, state)
            loader.load(variant, jenkinsfile)
            loader.initialize()
        }
    } catch (Throwable t) {
        return [error: SandboxHarness.rejectionOf(t) ?: t.message]
    }
    return traced(j, observed(j, state, (yaml.load(j.files['pipeline-config.yaml']) as Map).projects as Map))
}

Map logRules = [
        'L1 the loader names the file it read, a second file and the parts it ignores': { List log ->
            log.findAll { String line ->
                !(line.startsWith('[INIT] The services come from ') || line.contains(' are both in the repository: ')
                        || line.endsWith(': only its projects: section is read'))
            }
        },
        'L2 the auto-detect line names the file read'                  : { List log -> log.collect { (it as String).replace('devsecops_config.yaml', 'config.yaml') } },
        'L3 the bundled defaults moved to a namespaced resource path'  : { List log ->
            log.collect { (it as String).replace("resources/${bundledName}", 'resources/defaults.yaml') }
        }
]
Closure normaliseLog = { Map run -> logRules.values().inject((run.log ?: []) as List) { List log, Closure rule -> rule(log) } }
Closure sameAsOld = { Map old, Map run ->
    run.error ? [run.error] : differences(loaded(old), loaded(run), '') + differences(old.log, normaliseLog(run), 'log')
}
logRules.keySet().each { println "      ${it}" }
List<String> fileNames = ['devsecops_config.yaml', 'config.yaml']
String bothFiles = "[INIT] devsecops_config.yaml and config.yaml are both in the repository: devsecops_config.yaml is read, config.yaml is not"

[[variant: 'full', names: ['gui', 'backend-api']], [variant: 'security', names: ['backend-api', 'gui']]].each { Map spec ->
    Map old = runOld(spec.names as List)
    fileNames.each { String file ->
        Map run = runRepo(spec.variant as String, [projectNames: (spec.names as List).join(','), agentNames: ['linux-agent']], [(file): configText])
        List found = sameAsOld(old, run)
        check("A' ${spec.variant} ${(spec.names as List).join(',')} over ${file}: the loader over ${file} and the bundled defaults loads what the b815d55 loader loaded over config.yaml and defaults.yaml, with no rule",
                !found, found.take(10))
    }
}

Map autoDetected = runOld([])
fileNames.each { String file ->
    Map run = runRepo('full', [agentNames: ['linux-agent']], [(file): configText])
    List found = sameAsOld(autoDetected, run)
    String autoLine = "[INIT] PROJECT_NAMES not set - auto-detected from ${file}: gui, backend-api".toString()
    check("A' full without projectNames over ${file}: the services and their order are auto-detected from the file as the b815d55 loader did, logged once",
            !found && (run.log as List).count(autoLine) == 1 && (autoDetected.order as List) == ['gui', 'backend-api'], found.take(10) + [run.log])
}

Map changedAppId = yaml.load(configText) as Map
(((changedAppId.projects as Map).gui as Map)).appId = 'read-config-yaml-by-mistake'
String mistaken = yaml.dump(changedAppId)
Map oldBoth = runOld(['gui', 'backend-api'])
Map both = runRepo('full', [projectNames: 'gui,backend-api', agentNames: ['linux-agent']], ['devsecops_config.yaml': configText, 'config.yaml': mistaken])
List bothDiff = sameAsOld(oldBoth, both)
List bothProbes = ((both.trace ?: [:]) as Map).probes as List ?: []
check('A\' both files: devsecops_config.yaml is read and config.yaml is not, the console says so once, and the configuration is that of the b815d55 loader',
        !bothDiff && (both.log as List).count(bothFiles) == 1
                && bothProbes.containsAll(['fileExists devsecops_config.yaml', 'fileExists config.yaml', 'readYaml devsecops_config.yaml'])
                && !bothProbes.contains('readYaml config.yaml'),
        bothDiff.take(10) + [bothProbes])

Map bundledFile = yaml.load(new File(root, "resources/${bundledName}").getText('UTF-8')) as Map
Map bundledDefaults = (bundledFile?.defaults ?: [:]) as Map
Closure limitsOf = { def cfg -> ['maxCritical', 'maxHigh', 'maxMedium'].every { (cfg instanceof Map) && (cfg as Map)[it] instanceof Integer } }
boolean wellFormed = (bundledFile.containsKey('defaults') && (bundledFile.keySet() - ['defaults', 'platform']).isEmpty()
        && (bundledFile.platform == null || bundledFile.platform instanceof Map) && bundledDefaults.keySet().containsAll(defaults.keySet())
        && [bundledDefaults.sast, bundledDefaults.sca, bundledDefaults.dast, (bundledDefaults.tools as Map)?.nexusIq].every { limitsOf(it) }
        && (bundledDefaults.coverage as Map)?.minLine instanceof Integer)
Map applied = runRepo('full', [projectNames: 'gui,backend-api', agentNames: ['linux-agent']], ['devsecops_config.yaml': configText], true)
Closure limit = { def cfg -> [maxCritical: cfg.maxCritical, maxHigh: cfg.maxHigh, maxMedium: cfg.maxMedium] }
check('the library bundles global defaults (and an optional platform: map) that every run applies',
        wellFormed && !applied.error && applied.minRequired == (bundledDefaults.coverage as Map).minLine
                && applied.policyLimits == [sast: limit(bundledDefaults.sast), sca: limit(bundledDefaults.sca), dast: limit(bundledDefaults.dast),
                                            niq : limit((bundledDefaults.tools as Map).nexusIq)],
        [wellFormed, applied.error, applied.policyLimits, applied.minRequired])

String exampleJenkinsfile = new File(root, 'examples/CertScanner/Jenkinsfile').getText('UTF-8')
String exampleNames = (exampleJenkinsfile =~ /projectNames:\s*'([^']+)'/)[0][1]
String exampleText = new File(root, 'examples/CertScanner/devsecops_config.yaml').getText('UTF-8')
Map example = runRepo('full', [projectNames: exampleNames, agentNames: ['linux-agent']], ['devsecops_config.yaml': exampleText], true)
check("the example examples/CertScanner/devsecops_config.yaml loads with the services of the example Jenkinsfile (${exampleNames}) and the bundled defaults",
        !example.error && (example.order as List) == exampleNames.tokenize(',') && (example.runState as Map).keySet() == exampleNames.tokenize(',') as Set
                && (example.log as List).contains("[INIT] The services come from devsecops_config.yaml in the repository, the global settings from the library (resources/${bundledName})".toString())
                && !(example.log as List).any { (it as String).endsWith(': only its projects: section is read') },
        [example.error, example.order, (example.runState as Map)?.keySet(), example.log])

Closure keyPaths
keyPaths = { Map map, String prefix -> map.collectMany { k, v -> ["${prefix}${k}".toString()] + (v instanceof Map ? keyPaths(v as Map, "${prefix}${k}.") : []) } }
Map template = yaml.load(new File(root, 'devsecops_config.yaml.template').getText('UTF-8')) as Map
List templateKeys = keyPaths(((template.projects as Map).values() as List)[0] as Map, '')
List missing = ((yaml.load(exampleText) as Map).projects as Map).values().collectMany { keyPaths(it as Map, '') }.unique() - templateKeys
check('devsecops_config.yaml.template names every key the example sets', !missing && (template.projects as Map).keySet().toList() == ['my-app'], missing)

Map neither = runRepo('full', [projectNames: 'gui,backend-api', agentNames: ['linux-agent']], [:])
check('A\' neither file: the Monitor stage stops with a message that names both files and the example to start from',
        neither.error == "[INIT] Neither devsecops_config.yaml nor config.yaml is at the root of the repository: commit devsecops_config.yaml with a projects: section " +
                "for the services of this repository (examples/CertScanner/devsecops_config.yaml shows one)",
        neither.error)

String otherParts = configText + 'defaults: {sast: {maxCritical: 99}, coverage: {minLine: 1}}\nplatform: {oisHost: x}\npipeline: {type: sast}\n'
Map ignoring = runRepo('full', [projectNames: 'gui,backend-api', agentNames: ['linux-agent']], ['devsecops_config.yaml': otherParts])
List ignoringDiff = sameAsOld(oldBoth, ignoring)
check('A\' other parts of the file: defaults, platform and pipeline are ignored and named, the thresholds and the coverage minimum stay those of the library',
        !ignoringDiff && (ignoring.log as List).contains('[INIT] devsecops_config.yaml also sets defaults, platform, pipeline: only its projects: section is read')
                && (ignoring.policyLimits as Map)?.sast == [maxCritical: 0, maxHigh: 0, maxMedium: 0] && ignoring.minRequired == 60,
        ignoringDiff.take(10) + [ignoring.log])

Map unbundled = runRepo('full', [projectNames: 'gui,backend-api', agentNames: ['linux-agent']], ['devsecops_config.yaml': configText], true, null)
check('A\' the bundled defaults are missing: the run stops with the resource it could not read and whom to ask',
        unbundled.error == "[INIT] The library resource ${bundledName} cannot be read (No such library resource ${bundledName} could be found.): the global settings come from it; ask the DevSecOps team".toString(),
        unbundled.error)

println "\n${failures == 0 ? 'ALL COMPATIBILITY SCENARIOS PASSED' : failures + ' COMPATIBILITY SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
