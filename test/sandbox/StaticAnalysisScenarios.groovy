import devsecops.test.FakeScript
import devsecops.test.SandboxHarness

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')

String MONITOR = 'Monitor source changes (download sources)'
String UNIT = 'Unit tests'
String NIQ = 'Dependencies scan (Nexus IQ)'
String SAST = 'SAST - Static Application Security Tests - HCL AppScan'
String SONAR = 'SCA (SonarQube)'

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

SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
Closure utils = { -> harness.type('com.bbh.utils.BuildUtils') }

Map on = [staticAnalysis: [parallel: true]]
List<List> switchCases = [
        ['no services', [:], 'true', false],
        ['no services map', null, null, false],
        ['every service true', [gui: on, api: on], null, true],
        ['every service true, as text', [gui: [staticAnalysis: [parallel: 'true']], api: on], null, true],
        ['one service without the key', [gui: on, api: [:]], null, false],
        ['one service false', [gui: on, api: [staticAnalysis: [parallel: false]]], null, false],
        ['a scalar staticAnalysis', [gui: on, api: [staticAnalysis: true]], null, false],
        ['no key, env true', [gui: [:], api: [:]], 'true', true],
        ['no key, env TRUE with spaces', [gui: [:]], ' TRUE ', true],
        ['every service true, env false', [gui: on, api: on], 'false', false],
        ['every service true, env blank', [gui: on, api: on], '  ', true],
        ['one service false, env blank', [gui: on, api: [staticAnalysis: [parallel: false]]], '', false]
]
List switchWrong = switchCases.findAll { List c -> harness.run { utils().parallelStaticAnalysis(c[1] as Map, c[2]) } != c[3] }.collect { it[0] }
check('BuildUtils.parallelStaticAnalysis: DSO_STATIC_ANALYSIS_PARALLEL decides when it is set, otherwise every service of the run needs staticAnalysis.parallel true, and a run without services is off',
        switchWrong.isEmpty(), switchWrong)

Map gradle = [tasks: ['clean', 'build', 'bootJar'], flags: ['--refresh-dependencies']]
Map maven = [goals: ['clean', 'verify'], flags: ['-B', '-U']]
Closure service = { Map asoc -> [build: [gradle: gradle, maven: maven], asoc: [keyId: 'k'] + asoc] }
List<List> compileCases = [
        ['no asoc block', [gui: [build: [gradle: gradle]]], []],
        ['an asoc block without a build tool', [gui: service([:])], []],
        ['a block equal to the build command', [gui: service([gradle: [tasks: ['clean', 'build', 'bootJar'], flags: ['--refresh-dependencies']]])], []],
        ['other tasks', [gui: service([gradle: [tasks: ['compileJava']]])], ['gui']],
        ['other flags', [gui: service([gradle: [tasks: ['clean', 'build', 'bootJar'], flags: ['--offline']]])], ['gui']],
        ['other tasks and doCompile false', [gui: service([gradle: [tasks: ['compileJava']], doCompile: false])], []],
        ['other tasks and doCompile false as text', [gui: service([gradle: [tasks: ['compileJava']], doCompile: 'false'])], []],
        ['both tools differ', [gui: service([gradle: [tasks: ['compileJava']], maven: [goals: ['compile']]])], ['gui']],
        ['two services in the order of the run', ['backend-api': service([maven: [goals: ['compile']]]), gui: service([gradle: [tasks: ['assemble']]]),
                                                  web: service([:])], ['backend-api', 'gui']]
]
List compileWrong = compileCases.collect { List c -> [c[0], harness.run { utils().sastCompileOverrides(c[1] as Map) }, c[2]] }.findAll { it[1] != it[2] }
check('BuildUtils.sastCompileOverrides: a service whose asoc.gradle or asoc.maven block differs from build.<tool> is named once, in the order of the run, unless asoc.doCompile is false',
        compileWrong.isEmpty(), compileWrong)

Map settled = harness.run {
    def state = harness.type('com.bbh.core.PipelineState').newInstance()
    Map monitorTime = [start: 1L, end: 2L]
    [MONITOR, UNIT, NIQ, SONAR, SAST].each { state.stageResults[it] = 'PASS' }
    [MONITOR, UNIT, SAST, NIQ, SONAR].each { state.stageTimes[it] = it == MONITOR ? monitorTime : [start: 3L, end: 4L] }
    [UNIT, SONAR, SAST].each { state.stageErrors[it] = "${it} reason".toString() }
    state.projectsVulnCounts.gui = [coverage: [line: 80], niq: [high: 1], sca: [high: 2], sast: [high: 3]]
    state.projectsVulnCounts['backend-api'] = [niq: [high: 0], sast: [high: 0]]
    state.projectsScanResults.gui = [coverage: 'PASS', niq: 'PASS', niq_file: 'niq.html', sonar: 'PASS', sonar_file: 'sonar.html', sca: 'WARN',
                                     sast: 'WARN', sast_file: 'appscan-report-gui.html', sastx: 'kept']
    state.projectsScanResults.note = 'not a map'
    state.settle([NIQ, SAST, SONAR], [['niq'], ['sast'], ['sonar', 'sca']])
    [results: state.stageResults.keySet().toList(), times: state.stageTimes.keySet().toList(), errors: state.stageErrors.keySet().toList(),
     monitorTime: state.stageTimes[MONITOR].is(monitorTime), errorText: state.stageErrors[SAST],
     vulns: state.projectsVulnCounts.collectEntries { k, v -> [(k): (v as Map).keySet().toList()] }, high: state.projectsVulnCounts.gui.sast.high,
     scans: state.projectsScanResults.gui.keySet().toList(), sastFile: state.projectsScanResults.gui.sast_file, note: state.projectsScanResults.note]
} as Map
check('PipelineState.settle: the stage maps get Nexus IQ, SAST and SonarQube last in that order and keep the other stages and the values in place',
        settled.results == [MONITOR, UNIT, NIQ, SAST, SONAR] && settled.times == [MONITOR, UNIT, NIQ, SAST, SONAR] && settled.errors == [UNIT, SAST, SONAR]
                && settled.monitorTime && settled.errorText == "${SAST} reason".toString(),
        settled.subMap(['results', 'times', 'errors', 'monitorTime', 'errorText']))
check('PipelineState.settle: each service map gets the keys of no scanner first, then niq, sast, then sonar and sca, each group in its own order, and a value that is not a map is left alone',
        settled.vulns == [gui: ['coverage', 'niq', 'sast', 'sca'], 'backend-api': ['niq', 'sast']] && settled.high == 3
                && settled.scans == ['coverage', 'sastx', 'niq', 'niq_file', 'sast', 'sast_file', 'sonar', 'sonar_file', 'sca']
                && settled.sastFile == 'appscan-report-gui.html' && settled.note == 'not a map',
        settled.subMap(['vulns', 'high', 'scans', 'sastFile', 'note']))

Closure removal = { boolean unix ->
    FakeScript sc = new FakeScript(unix: unix)
    sc.env.vars.putAll([WORKSPACE: '/ws'])
    sc.shHandler = { Map a -> a.returnStdout ? 'Linux' : null }
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(sc, state, os, policy)
        sc.calls.clear()
        harness.type('com.bbh.scanner.AppScanService').newInstance(sc, state, os, policy, build).removeStagedSources()
    }
    return sc.calls
}
check('AppScanService.removeStagedSources: one step removes the SAST staging copy, appscan-source-staged on Linux and sast_directory on Windows',
        removal(true) == ['sh rm -rf "$WORKSPACE/appscan-source-staged"']
                && removal(false) == ['powershell Remove-Item -Path "$env:WORKSPACE\\sast_directory" -Recurse -Force -ErrorAction SilentlyContinue'],
        removal(true) + removal(false))

println "\n${failures == 0 ? 'ALL STATIC ANALYSIS SCENARIOS PASSED' : failures + ' STATIC ANALYSIS SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
