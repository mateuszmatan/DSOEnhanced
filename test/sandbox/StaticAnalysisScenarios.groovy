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
Map off = [staticAnalysis: [parallel: false]]
List<List> switchCases = [
        ['no services', [:], 'true', false],
        ['no services map', null, null, false],
        ['no service sets the key', [gui: [:], api: [:]], null, true],
        ['every service true', [gui: on, api: on], null, true],
        ['every service true, as text', [gui: [staticAnalysis: [parallel: 'true']], api: on], null, true],
        ['one service without the key', [gui: on, api: [:]], null, true],
        ['one service false', [gui: on, api: off], null, false],
        ['one service false, as text', [gui: [:], api: [staticAnalysis: [parallel: 'FALSE']]], null, false],
        ['a scalar staticAnalysis falls back to the default', [gui: on, api: [staticAnalysis: true]], null, true],
        ['a value that is not a boolean falls back to the default', [gui: [staticAnalysis: [parallel: 'sometimes']]], null, true],
        ['no key, env true', [gui: [:], api: [:]], 'true', true],
        ['no key, env TRUE with spaces', [gui: [:]], ' TRUE ', true],
        ['every service true, env false', [gui: on, api: on], 'false', false],
        ['every service true, env blank', [gui: on, api: on], '  ', true],
        ['one service false, env blank', [gui: on, api: off], '', false],
        ['one service false, env true', [gui: on, api: off], 'true', true],
        ['env that is not a boolean falls back to the services', [gui: on, api: off], 'maybe', false]
]
List switchWrong = switchCases.findAll { List c -> harness.run { utils().parallelStaticAnalysis(c[1] as Map, c[2]) } != c[3] }.collect { it[0] }
check('BuildUtils.parallelStaticAnalysis: DSO_STATIC_ANALYSIS_PARALLEL decides when it is true or false, otherwise the run is parallel unless a service sets staticAnalysis.parallel false, a value that is not a boolean falls back to the default true, and a run without services is off',
        switchWrong.isEmpty(), switchWrong)

Closure decide = { Map services, Map defaults, def forced -> harness.run { utils().parallelSwitch(services, defaults, 'staticAnalysis', forced, 'DSO_STATIC_ANALYSIS_PARALLEL') } as Map }
Map wrongValues = decide([gui: [staticAnalysis: [parallel: 'sometimes']], api: [staticAnalysis: true], web: on], [:], 'maybe')
check('BuildUtils.parallelSwitch: a wrong variable and wrong service values are each named in a warning with the default used, and a service that sets the default is told it can remove the key',
        wrongValues.enabled == true && wrongValues.source == 'default'
                && wrongValues.warnings == ["DSO_STATIC_ANALYSIS_PARALLEL='maybe' is neither true nor false - it is ignored and staticAnalysis.parallel of the services decides, by default true",
                                           "gui: staticAnalysis.parallel: 'sometimes' is neither true nor false - the default true is used",
                                           "api: staticAnalysis must be a map with the key parallel, not 'true' - the default staticAnalysis.parallel: true is used"]
                && wrongValues.notes == ['web: staticAnalysis.parallel: true equals the default - the key can be removed'],
        wrongValues)
Map inherited = decide([gui: on, api: on], [staticAnalysis: [parallel: true]], null)
Map optedOut = decide([gui: on, api: off, web: off], [:], null)
Map sharedOff = decide([gui: off, api: off], [staticAnalysis: [parallel: false]], null)
Map envOn = decide([gui: [:]], [:], 'true')
Map envOff = decide([gui: on], [:], 'false')
check('BuildUtils.parallelSwitch: true in the defaults is named once instead of each service, services that set false are listed, false in the defaults is named as the source, and the variable is reported as the source',
        inherited.notes == ['defaults.staticAnalysis.parallel: true equals the built-in default - the key can be removed from the defaults'] && inherited.enabled == true
                && optedOut.enabled == false && optedOut.optedOut == ['api', 'web'] && optedOut.source == 'staticAnalysis.parallel'
                && sharedOff.enabled == false && sharedOff.source == 'defaults' && sharedOff.notes == [] && sharedOff.warnings == []
                && envOn.enabled == true && envOn.source == 'DSO_STATIC_ANALYSIS_PARALLEL' && envOn.notes == ['DSO_STATIC_ANALYSIS_PARALLEL=true equals the default staticAnalysis.parallel: true - the variable can be removed']
                && envOff.enabled == false && envOff.notes == [],
        [inherited, optedOut, sharedOff, envOn, envOff])

Map timeText = harness.run {
    [zero: utils().formatDuration(0L), seconds: utils().formatDuration(42999L), minutes: utils().formatDuration(185000L), hours: utils().formatDuration(7380000L),
     saved: utils().parallelSaving(600000L, 450000L), none: utils().parallelSaving(300000L, 300000L), empty: utils().parallelSaving(0L, 1000L)]
} as Map
check('BuildUtils.formatDuration and parallelSaving: seconds, minutes with seconds, hours with minutes, and the saved time with its share only when the parallel run was shorter',
        timeText == [zero: '0s', seconds: '42s', minutes: '3m 5s', hours: '2h 3m', saved: ' - 2m 30s (25%) saved', none: '', empty: ''], timeText)

Closure viewModel = { -> harness.type('com.bbh.report.ReportViewModel') }
String DEPLOY = 'Lower test region deployment'
String REGRESSION = 'Regression tests (>60% user stories coverage)'
String SMOKE = 'Smoke tests'
String PERFORMANCE = 'Performance tests'
String DAST = 'DAST - Dynamic Application Security Tests - HCL AppScan'
long MIN = 60000L
Closure span = { long startMin, long minutes -> [start: startMin * MIN, end: (startMin + minutes) * MIN] }
Map effort = [build: 10L, unitTests: 0L, nexusIq: 10L, sast: 10L, sonar: 10L, nexusDelivery: 0L, lowerDeployment: 0L, regression: 10L, smoke: 10L, performance: 10L, dast: 360L, higherDeployment: 0L]
Map fullTimes = ['Build artifact': span(0, 4), (UNIT): span(4, 6), (NIQ): span(4, 3), (SAST): span(4, 20), (SONAR): span(4, 8), (DEPLOY): span(30, 5),
                 (REGRESSION): span(40, 15), (SMOKE): span(40, 2), (PERFORMANCE): span(40, 12), (DAST): span(40, 45), 'Smoke tests (skipped)': span(0, 1)]
Map fullResults = ['Build artifact': 'PASS', (UNIT): 'PASS', (NIQ): 'PASS', (SAST): 'WARN', (SONAR): 'PASS', (DEPLOY): 'PASS',
                   (REGRESSION): 'PASS', (SMOKE): 'PASS', (PERFORMANCE): 'FAIL', (DAST): 'PASS']
List groups = [[name: 'Static analysis', branches: [[stage: UNIT, ms: 6 * MIN, waitMs: 0L], [stage: NIQ, ms: 3 * MIN, waitMs: 0L],
                                                    [stage: SAST, ms: 20 * MIN, waitMs: 0L], [stage: SONAR, ms: 8 * MIN, waitMs: 6 * MIN]]],
               [name: 'Dynamic analysis', branches: [[stage: REGRESSION, ms: 15 * MIN], [stage: SMOKE, ms: 2 * MIN], [stage: PERFORMANCE, ms: 12 * MIN], [stage: DAST, ms: 45 * MIN]]]]
Map comparison = harness.run {
    Map parallelRun = viewModel().manualComparison(fullTimes, fullResults, groups, effort) as Map
    Map sequentialRun = viewModel().manualComparison(fullTimes, fullResults, [], effort) as Map
    Map partial = viewModel().manualComparison(['Build artifact': span(20, 1), (SAST): span(0, 7), (NIQ): span(7, 2), (DEPLOY): span(9, 1), (REGRESSION): span(10, 3), (DAST): span(13, 2)],
            ['Build artifact': 'NOT_REQUIRED', (SAST): 'PASS', (NIQ): 'NOT_REQUIRED', (DEPLOY): 'SKIP', (REGRESSION): 'PASS', (DAST): 'PASS'], [], effort) as Map
    Map none = viewModel().manualComparison([:], [:], null, effort) as Map
    [parallel: parallelRun, sequential: sequentialRun, partial: partial, none: none]
} as Map
Closure rowsOf = { Map table -> (table.rows as List).collect { Map r -> [r.step, (r.ms as long).intdiv(MIN), (r.manual as long).intdiv(MIN), r.automated == null ? null : (r.automated as long).intdiv(MIN)] } }
Closure gainsOf = { Map table -> (table.rows as List).collect { Map r -> [r.span, r.gainPercent] } }
check('ReportViewModel.manualComparison: one line per step that ran, in the order of the pipeline, with its own time; the manual column adds the minutes of the step (trigger by hand, 6 h of the CyberTrack ticket for the DAST); of the steps of one parallel group only the longest has a time in the DevSecOps column and the group shares one gain; after a run one after another every step has its time; skipped steps are left out, except the build, which is NOT_REQUIRED when only its Node.js part was',
        rowsOf(comparison.parallel as Map) == [['Build', 4L, 14L, 4L], ['Unit test with Code Coverage', 6L, 6L, null], ['Nexus IQ', 3L, 13L, null], ['SAST', 20L, 30L, 20L], ['SonarQube', 2L, 12L, null],
                                               ['Lower region test deployment', 5L, 5L, 5L], ['Regression tests', 15L, 25L, null], ['Smoke tests', 2L, 12L, null],
                                               ['Performance tests', 12L, 22L, null], ['DAST', 45L, 405L, 45L]]
                && gainsOf(comparison.parallel as Map) == [[1, 71L], [4, 67L], [0, 67L], [0, 67L], [0, 67L], [1, 0L], [4, 90L], [0, 90L], [0, 90L], [0, 90L]]
                && (comparison.parallel as Map).manual == 544L * MIN && (comparison.parallel as Map).automated == 74L * MIN && (comparison.parallel as Map).recovered == 470L * MIN
                && (comparison.parallel as Map).percent == 86L
                && ((comparison.parallel as Map).rows as List).collect { it.manualNote } == ['the run + 10 min manual trigger', 'the same as the run', 'the run + 10 min manual trigger', 'the run + 10 min manual trigger',
                                                                                              'the run + 10 min manual trigger', 'the same as the run', 'the run + 10 min manual trigger', 'the run + 10 min manual trigger',
                                                                                              'the run + 10 min manual trigger', 'the run + 6 h ticket to CyberTrack']
                && ((comparison.parallel as Map).rows as List)[3].automatedNote == 'the longest of the 4 in parallel'
                && ((comparison.parallel as Map).rows as List)[2].automatedNote == 'in parallel, within the time of SAST'
                && ((comparison.parallel as Map).rows as List)[1].block == 'Static analysis' && ((comparison.parallel as Map).rows as List)[0].block == null
                && rowsOf(comparison.sequential as Map) == [['Build', 4L, 14L, 4L], ['Unit test with Code Coverage', 6L, 6L, 6L], ['Nexus IQ', 3L, 13L, 3L], ['SAST', 20L, 30L, 20L], ['SonarQube', 8L, 18L, 8L],
                                                            ['Lower region test deployment', 5L, 5L, 5L], ['Regression tests', 15L, 25L, 15L], ['Smoke tests', 2L, 12L, 2L],
                                                            ['Performance tests', 12L, 22L, 12L], ['DAST', 45L, 405L, 45L]]
                && (comparison.sequential as Map).manual == 550L * MIN && (comparison.sequential as Map).automated == 120L * MIN
                && ((comparison.sequential as Map).rows as List).every { it.span == 1 && it.block == null }
                && rowsOf(comparison.partial as Map) == [['Build', 1L, 11L, 1L], ['SAST', 7L, 17L, 7L], ['Regression tests', 3L, 13L, 3L], ['DAST', 2L, 362L, 2L]]
                && (comparison.none as Map).rows == [] && (comparison.none as Map).recovered == 0L,
        comparison)

Map waitingRun = harness.run {
    viewModel().manualComparison([(UNIT): span(0, 6), (NIQ): span(0, 3), (SAST): span(0, 7), (SONAR): span(0, 11), 'Higher test environment deployment': span(20, 3),
                                  'Nexus delivery (Static analysis passed)': span(12, 2)],
            [(UNIT): 'PASS', (NIQ): 'PASS', (SAST): 'PASS', (SONAR): 'PASS', 'Higher test environment deployment': 'PASS', 'Nexus delivery (Static analysis passed)': 'PASS'],
            [[name: 'Static analysis', branches: [[stage: UNIT, waitMs: 0L], [stage: NIQ, waitMs: 0L], [stage: SAST, waitMs: 0L], [stage: SONAR, waitMs: 6 * MIN]]]], effort + [higherDeployment: 90L]) as Map
} as Map
check('ReportViewModel.manualComparison: SonarQube that waited for the unit tests is the longest of its group with the waiting counted, the Nexus delivery and the higher region deployment follow in the order of the pipeline, and the summary is written in seconds and minutes',
        rowsOf(waitingRun) == [['Unit test with Code Coverage', 6L, 6L, null], ['Nexus IQ', 3L, 13L, null], ['SAST', 7L, 17L, null], ['SonarQube', 5L, 15L, 11L],
                               ['Nexus delivery (snapshot)', 2L, 2L, 2L], ['Higher region test deployment', 3L, 93L, 3L]]
                && (waitingRun.rows as List)[3].automatedNote == 'the longest of the 4 in parallel, with 6m 0s of waiting for the unit tests'
                && (waitingRun.rows as List)[5].manualNote == 'the run + 90 min ticket to SRM'
                && harness.run { [viewModel().secondsAndMinutes(534L * MIN), viewModel().secondsAndMinutes(0L), viewModel().minutesText(360L), viewModel().minutesText(90L)] } == ['32 040 s = 534 min', '0 s = 0 min', '6 h', '90 min'],
        waitingRun)

Map serviceRun = harness.run {
    def state = harness.type('com.bbh.core.PipelineState').newInstance()
    def branch = state.fork()
    state.addProjectTime(SAST, 'api', 4 * MIN)
    branch.addProjectTime(SAST, 'api', 2 * MIN)
    branch.addProjectTime(SAST, 'gui', 3 * MIN)
    branch.addProjectTime(SAST, 'gui', -5 * MIN)
    state.addProjectTime(null, 'gui', MIN)
    state.addProjectTime(UNIT, 'api', 6 * MIN)
    Map many = viewModel().manualComparison([(UNIT): span(0, 6), (SAST): span(6, 9)], [(UNIT): 'PASS', (SAST): 'PASS'], [], effort, ['api', 'gui', 'db'], state.projectStageTimes) as Map
    Map one = viewModel().manualComparison([(UNIT): span(0, 6)], [(UNIT): 'PASS'], [], effort, ['api'], state.projectStageTimes) as Map
    [times: state.projectStageTimes, many: (many.rows as List).collect { it.services }, one: (one.rows as List).collect { it.services }]
} as Map
check('Manual Actions vs DevSecOps with several services: the time of every service is added up per step, also from a parallel branch, never below zero; every line lists all configured services, those the step did not run for marked as such; one service alone is not listed',
        serviceRun.times == [(SAST): [api: 6 * MIN, gui: 0L], (UNIT): [api: 6 * MIN]]
                && serviceRun.many == [[[name: 'api', ran: true, ms: 6 * MIN], [name: 'gui', ran: false, ms: 0L], [name: 'db', ran: false, ms: 0L]],
                                       [[name: 'api', ran: true, ms: 6 * MIN], [name: 'gui', ran: true, ms: 0L], [name: 'db', ran: false, ms: 0L]]]
                && serviceRun.one == [[]],
        serviceRun)

Map effortRead = harness.run {
    [builtIn: utils().manualEffort(null), given: utils().manualEffort([report: [manualEffort: [build: 5, dast: '240', smoke: 'ten', sonar: -1, sast: 15]]]),
     former: utils().manualEffort([report: [manualEffort: [buildTrigger: 5, scanTrigger: 20, sonar: 7, deployTrigger: 'x', testTrigger: 15, dast: 300]]])]
} as Map
Map builtInEffort = [build: 10L, unitTests: 0L, nexusIq: 10L, sast: 10L, sonar: 10L, nexusDelivery: 0L, lowerDeployment: 0L, regression: 10L, smoke: 10L, performance: 10L, dast: 360L, higherDeployment: 0L]
check('BuildUtils.manualEffort: the built-in minutes of every step, minutes given in the defaults as numbers or text, a value that is not a number of minutes named in a warning with the default used, and the former names buildTrigger, scanTrigger, deployTrigger and testTrigger still read, each with a line that asks to rename it',
        (effortRead.builtIn as Map).minutes == builtInEffort && (effortRead.builtIn as Map).warnings == []
                && (effortRead.given as Map).minutes == builtInEffort + [build: 5L, dast: 240L, sast: 15L]
                && (effortRead.given as Map).warnings == ["report.manualEffort.sonar: '-1' is not a number of minutes - the default 10 of report.manualEffort.sonar is used",
                                                         "report.manualEffort.smoke: 'ten' is not a number of minutes - the default 10 of report.manualEffort.smoke is used"]
                && (effortRead.former as Map).minutes == builtInEffort + [build: 5L, nexusIq: 20L, sonar: 7L, regression: 15L, smoke: 15L, performance: 15L, dast: 300L]
                && (effortRead.former as Map).warnings == ["report.manualEffort.buildTrigger is the former name of report.manualEffort.build - its value '5' is used, rename it",
                                                          "report.manualEffort.scanTrigger is the former name of report.manualEffort.nexusIq - its value '20' is used, rename it",
                                                          "report.manualEffort.deployTrigger is the former name of report.manualEffort.lowerDeployment - its value 'x' is used, rename it",
                                                          "report.manualEffort.deployTrigger: 'x' is not a number of minutes - the default 0 of report.manualEffort.lowerDeployment is used",
                                                          "report.manualEffort.testTrigger is the former name of report.manualEffort.regression - its value '15' is used, rename it",
                                                          "report.manualEffort.testTrigger is the former name of report.manualEffort.smoke - its value '15' is used, rename it",
                                                          "report.manualEffort.testTrigger is the former name of report.manualEffort.performance - its value '15' is used, rename it"],
        effortRead)

Map sources = [gui: [sourceDir: '.'], api: [sourceDir: './'], web: [sourceDir: 'web/'], docs: [:], mobile: [sourceDir: 'mobile']]
Map peers = harness.run { [gui: utils().sameSourceProjects(sources, 'gui'), web: utils().sameSourceProjects(sources, 'web'), lone: utils().sameSourceProjects(sources, 'lone')] } as Map
check('BuildUtils.sameSourceProjects: the services of one source directory, the repository root written as ., ./ or left out, in the order of the run',
        peers == [gui: ['gui', 'api', 'docs'], web: ['web'], lone: ['lone']], peers)

Map forked = harness.run {
    def state = harness.type('com.bbh.core.PipelineState').newInstance()
    state.projectsAllCfg.gui = [buildTool: 'gradle']
    state.cfg = state.projectsAllCfg.gui
    state.currentProjectName = 'gui'
    def branch = state.fork()
    branch.cfg = [buildTool: 'maven']
    branch.currentProjectName = 'api'
    branch.currentStage = 'SCA (SonarQube)'
    branch.stageResults['SCA (SonarQube)'] = 'PASS'
    branch.sonarResults = [status: 'OK']
    branch.vulnCounts.sca.high = 2
    branch.buildTag = '213-20260919'
    state.absorb(branch)
    [cfg: state.cfg, name: state.currentProjectName, stage: state.currentStage, result: state.stageResults['SCA (SonarQube)'],
     sonar: state.sonarResults, high: state.vulnCounts.sca.high, tag: state.buildTag, shared: { branch.projectsAllCfg.api = [buildTool: 'maven']; state.projectsAllCfg.containsKey('api') }()]
} as Map
check('PipelineState.fork and absorb: a branch shares the result maps, keeps its own service and stage, and hands back what it replaced',
        forked == [cfg: [buildTool: 'gradle'], name: 'gui', stage: '', result: 'PASS', sonar: [status: 'OK'], high: 2, tag: '213-20260919', shared: true], forked)

Map grouped = harness.run {
    def state = harness.type('com.bbh.core.PipelineState').newInstance()
    state.recordParallelGroup('Static analysis', 1000L, 61000L, [[stage: 'Unit tests', ms: 30000L, waitMs: 0L], [stage: 'SCA (SonarQube)', ms: 50000L, waitMs: 30000L],
                                                             [stage: 'SAST', ms: 60000L]])
    state.parallelGroups[0]
} as Map
check('PipelineState.recordParallelGroup: one after another adds the branches without the time SonarQube waited, in parallel is the wall time of the group',
        grouped.name == 'Static analysis' && grouped.actualMs == 60000L && grouped.sequentialMs == 110000L && (grouped.branches as List).size() == 3, grouped)

Map gradle = [tasks: ['clean', 'build', 'bootJar'], flags: ['--refresh-dependencies']]
Map maven = [goals: ['clean', 'verify'], flags: ['-B', '-U']]
Map dotnet = [solution: 'App.sln', configuration: 'Release']
Closure service = { Map asoc -> [build: [gradle: gradle, maven: maven, dotnet: dotnet], asoc: [keyId: 'k'] + asoc] }
List<List> compileCases = [
        ['no asoc block', [gui: [build: [gradle: gradle]]], []],
        ['an asoc block without a build tool', [gui: service([:])], []],
        ['a block equal to the build command', [gui: service([gradle: [tasks: ['clean', 'build', 'bootJar'], flags: ['--refresh-dependencies']]])], []],
        ['dotnet equal to build command', [api: service([dotnet: [solution: 'App.sln', configuration: 'Release']])], []],
        ['dotnet differs from build command', [api: service([dotnet: [solution: 'App.sln', configuration: 'Debug']])], ['api']],
        ['other tasks', [gui: service([gradle: [tasks: ['compileJava']]])], ['gui']],
        ['other flags', [gui: service([gradle: [tasks: ['clean', 'build', 'bootJar'], flags: ['--offline']]])], ['gui']],
        ['other tasks and doCompile false', [gui: service([gradle: [tasks: ['compileJava']], doCompile: false])], []],
        ['other tasks and doCompile false as text', [gui: service([gradle: [tasks: ['compileJava']], doCompile: 'false'])], []],
        ['both tools differ', [gui: service([gradle: [tasks: ['compileJava']], maven: [goals: ['compile']]])], ['gui']],
        ['two services in the order of the run', ['backend-api': service([maven: [goals: ['compile']]]), gui: service([gradle: [tasks: ['assemble']]]),
                                                  web: service([:])], ['backend-api', 'gui']]
]
List compileWrong = compileCases.collect { List c -> [c[0], harness.run { utils().sastCompileOverrides(c[1] as Map) }, c[2]] }.findAll { it[1] != it[2] }
check('BuildUtils.sastCompileOverrides: a service whose asoc.gradle, asoc.maven or asoc.dotnet block differs from build.<tool> is named once, in the order of the run, unless asoc.doCompile is false',
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

Map niqCounts = harness.run {
    def state = harness.type('com.bbh.core.PipelineState').newInstance()
    state.currentProjectName = 'gui'
    state.nexusIqResults = [critical: 2, high: 5, medium: 9]
    state.recordNexusIq('WARN')
    state.recordScan('niq', 'WARN')
    state.currentProjectName = 'orders-db'
    state.recordScan('niq', 'NOT_REQUIRED')
    state.currentProjectName = 'backend-api'
    state.vulnCounts.sast = [critical: 0, high: 1, medium: 2, low: 3]
    state.recordScan('sast', 'WARN', 'appscan-report-api.html')
    state.recordScan('sonar', 'PASS', 'sonar-report.html')
    Map decision = harness.type('com.bbh.core.ReleaseGate').decide(state.projectsVulnCounts, state.policyLimits, ['sast', 'sca', 'niq', 'dast'], [:], []) as Map
    List lines = harness.type('com.bbh.metrics.PipelineMetrics').findings(state, [project: 'demo'], 1000L) as List
    [counts: state.projectsVulnCounts, violations: decision.violations, line: lines.find { it.toString().contains('module=gui,scanner=niq,') }?.toString()]
} as Map
check('PipelineState.recordScan keeps the counts recordNexusIq wrote for the service, a service whose Nexus IQ is not required gets zeros, SAST copies its counts, and the release gate and the security_findings point list the Nexus IQ counts above the policy',
        niqCounts.counts == [gui: [niq: [critical: 2, high: 5, medium: 9, low: 0]], 'orders-db': [niq: [critical: 0, high: 0, medium: 0, low: 0]],
                             'backend-api': [sast: [critical: 0, high: 1, medium: 2, low: 3], sonar: [critical: 0, high: 0, medium: 0, low: 0]]]
                && niqCounts.violations == ['gui Dependencies (Nexus IQ) critical 2 > 0', 'gui Dependencies (Nexus IQ) high 5 > 0', 'gui Dependencies (Nexus IQ) medium 9 > 0',
                                            'backend-api SAST (AppScan) high 1 > 0', 'backend-api SAST (AppScan) medium 2 > 0']
                && (niqCounts.line ?: '').contains('module=gui,scanner=niq,status=WARN critical=2i,high=5i,medium=9i,low=0i,total=16i,above_policy=16i,')
                && (niqCounts.line ?: '').contains('exceeded=1i'),
        niqCounts)

Map gateRead = harness.run {
    FakeScript sc = new FakeScript()
    sc.files['release-gate.json'] = '{"allowed":false,"violations":["gui SAST (AppScan) high 3 > 0"]}'
    def state = harness.type('com.bbh.core.PipelineState').newInstance()
    state.stageResults[SAST] = 'WARN'
    def gate = harness.type('com.bbh.core.ReleaseGate')
    List first = gate.newInstance(sc, state).evaluate().violations as List
    gate.newInstance(sc, state).publish()
    List second = gate.newInstance(sc, state).evaluate().violations as List
    [first: first.collect { it.toString() }, second: second.collect { it.toString() }, written: (sc.jsonFiles['release-gate.json'] as Map).violations.collect { it.toString() }]
} as Map
List gateExpected = ['gui SAST (AppScan) high 3 > 0 (previous pipeline)', "stage '${SAST}' is WARN".toString()]
check('ReleaseGate reads the release-gate.json of the previous pipeline once: after the run wrote its own file, a later evaluation (InfluxDB, report) does not count the violations of the run a second time',
        gateRead.first == gateExpected && gateRead.second == gateExpected && gateRead.written == gateExpected, gateRead)

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

Closure sonarRun = { Map cfg, Map files, Closure shHandler = null ->
    FakeScript sc = new FakeScript()
    if (shHandler) sc.shHandler = shHandler
    sc.env.vars.putAll([WORKSPACE: '/ws', PATH: '/usr/bin', CHANGE_BRANCH: 'feature/DSO-2026', GIT_BRANCH: 'origin/master'])
    sc.files.putAll(files)
    Map out = [:]
    try {
        harness.run {
            def state = harness.type('com.bbh.core.PipelineState').newInstance()
            state.cfg = cfg
            state.currentProjectName = 'payments'
            def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
            os.detect()
            def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os)
            def build = harness.type('com.bbh.build.BuildService').newInstance(sc, state, os, policy)
            harness.type('com.bbh.scanner.SonarService').newInstance(sc, state, os, build).scan()
            out.url = state.sonarResults.url
        }
    } catch (Throwable t) {
        out.error = SandboxHarness.rejectionOf(t) ?: t.toString()
    }
    out.props = (sc.files['sonar-project.properties'] ?: '') as String
    out.log = sc.log.join('\n')
    out.calls = sc.calls.join('\n')
    return out
}
Map multiModule = ['pom.xml'                        : '<project><modules><module>core-module</module><module>api-module</module></modules></project>',
                   'core-module/src/main/java'      : '', 'core-module/src/test/java': '', 'api-module/src/main/kotlin': '',
                   'core-module/target/classes'     : '']
Map scanner = sonarRun([appName: 'my-payment-service', buildTool: 'maven', tools: [sonar: [installationName: 'SonarBBH']]], multiModule)
check('SonarQube without sonar-project.properties: the library writes its defaults (com.bbh: key, Maven paths, the modules with their own folders) and the SonarScanner scans the branch of the build',
        !scanner.error && scanner.props.contains('sonar.projectKey=com.bbh:my-payment-service')
                && scanner.props.contains('sonar.java.binaries=target/classes') && scanner.props.contains('sonar.java.coveragePlugin=jacoco')
                && scanner.props.contains('sonar.jacoco.reportPaths=target/jacoco.exec') && scanner.props.contains('sonar.junit.reportPaths=target/surefire-reports')
                && scanner.props.contains('sonar.language=java') && scanner.props.contains('sonar.sources=src/main/java')
                && scanner.props.contains('sonar.sourceEncoding=UTF-8') && scanner.props.contains('sonar.dynamicAnalysis=reuseReports')
                && scanner.props.contains('sonar.modules=core-module,api-module') && scanner.props.contains('core-module.sonar.tests=src/test/java')
                && scanner.props.contains('api-module.sonar.sources=src/main/kotlin') && scanner.props.contains('api-module.sonar.tests=\n')
                && !scanner.props.contains('sonar.branch.name')
                && scanner.calls.contains("'sonar-scanner' -Dsonar.host.url=https://tools.bbh.com/sonar -Dsonar.projectKey=com.bbh:my-payment-service -Dsonar.branch.name=feature/DSO-2026")
                && !scanner.calls.contains('sonar.branch.name=master') && (scanner.url as String).contains('dashboard?id=com.bbh:my-payment-service'),
        "error=${scanner.error}\nprops=${scanner.props}\ncalls=${scanner.calls}")

Map repoProps = sonarRun([appName: 'x', buildTool: 'maven', tools: [sonar: [installationName: 'SonarBBH']]], multiModule + ['sonar-project.properties': 'sonar.projectKey=team-key\n'])
check('SonarQube with sonar-project.properties: the file of the repository is left as it is',
        !repoProps.error && repoProps.props == 'sonar.projectKey=team-key\n', "error=${repoProps.error} props=${repoProps.props}")

Map plugin = sonarRun([appName: 'x', buildTool: 'maven', tools: [sonar: [installationName: 'SonarBBH', projectKey: 'team-key', maven: [goals: ['sonar:sonar']]]]], ['pom.xml': '<project/>'])
check('SonarQube with tools.sonar.maven.goals: the Maven plugin runs with the branch of the build and keeps its own project key, no properties file is written',
        !plugin.error && !plugin.props && plugin.calls.contains("'sonar:sonar' '-Dsonar.branch.name=feature/DSO-2026'") && !plugin.calls.contains('-Dsonar.projectKey'),
        "error=${plugin.error} calls=${plugin.calls}")

Map npmFlags = sonarRun([appName: 'web', buildTool: 'npm', tools: [sonar: [installationName: 'SonarBBH', npm: [sources: 'app', flags: ['-Dsonar.exclusions=**/*.spec.ts']]]]],
        ['package.json': '{"name":"web"}', 'coverage/lcov.info': ''])
check('SonarQube of an npm service: the SonarScanner keeps the arguments, the sources and the coverage report of tools.sonar.npm',
        !npmFlags.error && npmFlags.calls.contains("'sonar-scanner' -Dsonar.host.url=https://tools.bbh.com/sonar -Dsonar.projectKey=com.bbh:web -Dsonar.branch.name=feature/DSO-2026 -Dsonar.exclusions=**/*.spec.ts -Dsonar.sources=app -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info -Dsonar.typescript.lcov.reportPaths=coverage/lcov.info"),
        "error=${npmFlags.error} calls=${npmFlags.calls}")

Map gradleFlags = sonarRun([appName: 'svc', buildTool: 'gradle', tools: [sonar: [installationName: 'SonarBBH', gradle: [flags: ['--info', '-Dsonar.exclusions=gen/**']]]]], ['build.gradle': ''])
check('SonarQube of a Gradle service without sonar tasks: the SonarScanner gets only the -Dsonar. arguments of tools.sonar.gradle.flags',
        !gradleFlags.error && gradleFlags.calls.contains("'sonar-scanner' ") && gradleFlags.calls.contains(' -Dsonar.exclusions=gen/**') && !gradleFlags.calls.contains('--info'),
        "error=${gradleFlags.error} calls=${gradleFlags.calls}")

Map androidPlugin = sonarRun([appName: 'app', buildTool: 'android', tools: [sonar: [installationName: 'SonarBBH', projectKey: 'team-key', gradle: [tasks: ['sonarqube']]]]], ['build.gradle': '', 'app/src/main/AndroidManifest.xml': ''])
check('SonarQube of an Android service with tools.sonar.gradle.tasks: the Gradle plugin runs, not the SonarScanner',
        !androidPlugin.error && androidPlugin.calls.contains('sonarqube') && androidPlugin.calls.contains('-Dsonar.branch.name=feature/DSO-2026') && !androidPlugin.calls.contains("'sonar-scanner'"),
        "error=${androidPlugin.error} calls=${androidPlugin.calls}")

List refused = []
Map noBranch = sonarRun([appName: 'web', buildTool: 'npm', tools: [sonar: [installationName: 'SonarBBH']]], ['package.json': '{"name":"web"}'], { Map args ->
    String text = (args.script ?: '') as String
    if (text.contains("'sonar-scanner'") && text.contains('-Dsonar.branch.name=')) {
        refused << text
        throw new RuntimeException('script returned exit code 2')
    }
    return ''
})
check('SonarQube that refuses the branch: the analysis runs again without -Dsonar.branch.name and passes',
        !noBranch.error && refused.size() == 1 && noBranch.calls.readLines().any { it.contains("'sonar-scanner'") && !it.contains('-Dsonar.branch.name=') },
        "error=${noBranch.error} refused=${refused} calls=${noBranch.calls}")

Map branchOff = sonarRun([appName: 'web', buildTool: 'npm', tools: [sonar: [installationName: 'SonarBBH', branchAnalysis: false]]], ['package.json': '{"name":"web"}'])
check('SonarQube with tools.sonar.branchAnalysis: false: no branch name is passed',
        !branchOff.error && branchOff.calls.contains("'sonar-scanner'") && !branchOff.calls.contains('sonar.branch.name'),
        "error=${branchOff.error} calls=${branchOff.calls}")

Map branchWrong = sonarRun([appName: 'web', buildTool: 'npm', tools: [sonar: [installationName: 'SonarBBH', branchAnalysis: 'sometimes']]], ['package.json': '{"name":"web"}'])
Map branchDefault = sonarRun([appName: 'web', buildTool: 'npm', tools: [sonar: [installationName: 'SonarBBH', branchAnalysis: true]]], ['package.json': '{"name":"web"}'])
check('SonarQube with a wrong tools.sonar.branchAnalysis: the default true is used with a warning, and an explicit true is named as a key that can be removed',
        !branchWrong.error && branchWrong.calls.contains('-Dsonar.branch.name=feature/DSO-2026')
                && branchWrong.log.contains("[WARN] [SONAR] payments: tools.sonar.branchAnalysis: 'sometimes' is not true or false - using the default true")
                && branchDefault.calls.contains('-Dsonar.branch.name=feature/DSO-2026')
                && branchDefault.log.contains('[INFO] [SONAR] payments: tools.sonar.branchAnalysis: true equals the default - the key can be removed'),
        "wrong=${branchWrong.log} default=${branchDefault.log}")

def autoScript = new FakeScript()
autoScript.files['pom.xml'] = '<project><properties><java.version>17</java.version></properties></project>'
autoScript.files['frontend/package.json'] = '{"devDependencies": {"cypress": "^13.0.0"}, "engines": {"node": ">=20.0.0"}}'
autoScript.files['Dockerfile'] = 'FROM registry.access.redhat.com/ubi9/ubi-minimal'

Map autoResult = [:]
try {
    harness.run {
        def resolver = harness.type('com.bbh.utils.RepoStackAutoResolver').newInstance(autoScript)
        autoResult.resolved = resolver.resolve([:], 'payments')
        autoResult.overridden = resolver.resolve([buildTool: 'gradle', deployTarget: 'vm'], 'payments')
        autoResult.disabled = resolver.resolve(['autoresolve parameters': false], 'payments')
    }
} catch (Throwable t) {
    autoResult.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}

check('RepoStackAutoResolver: discovers buildTool maven with java 17, node stage0 with cypress, and openshift deployTarget, respecting overrides and autoresolve: false',
        !autoResult.error && autoResult.resolved
                && autoResult.resolved.buildTool == 'maven'
                && autoResult.resolved.buildToolAutoSetup == true
                && autoResult.resolved.build?.node?.enabled == true
                && autoResult.resolved.build?.node?.dir == 'frontend'
                && autoResult.resolved.build?.node?.version == '20'
                && autoResult.resolved.tests?.unitTests?.node?.cypress == true
                && autoResult.resolved.deployTarget == 'openshift'
                && autoResult.resolved.appName == 'payments'
                && autoResult.resolved.tools?.sonar?.projectKey == 'com.bbh:payments'
                && autoResult.overridden.buildTool == 'gradle'
                && autoResult.overridden.deployTarget == 'vm'
                && autoResult.disabled.buildTool == null,
        "error=${autoResult.error} resolved=${autoResult.resolved} overridden=${autoResult.overridden} disabled=${autoResult.disabled}")

Map detectionMatrix = [
        'flutter'         : [tool: 'flutter', files: ['pubspec.yaml': 'name: app\n', 'pom.xml': '<project/>', 'package.json': '{}']],
        'ios-podfile'     : [tool: 'ios', files: ['Podfile': "platform :ios, '15.0'\n", 'package.json': '{}']],
        'ios-swift'       : [tool: 'ios', files: ['Package.swift': '// swift-tools-version:5.9\n']],
        'ios-xcodeproj'   : [tool: 'ios', files: ['Payments.xcodeproj': '']],
        'ios-xcworkspace' : [tool: 'ios', files: ['Payments.xcworkspace': '']],
        'maven'           : [tool: 'maven', files: ['pom.xml': '<project/>', 'build.gradle': '', 'requirements.txt': 'requests==2.31.0\n', 'package.json': '{}']],
        'gradle-groovy'   : [tool: 'gradle', files: ['build.gradle': "plugins { id 'java' }\n", 'package.json': '{}']],
        'gradle-kotlin'   : [tool: 'gradle', files: ['build.gradle.kts': 'plugins { java }\n']],
        'gradle-settings' : [tool: 'gradle', files: ['settings.gradle': "rootProject.name = 'payments'\n"]],
        'gradle-settings-kts': [tool: 'gradle', files: ['settings.gradle.kts': 'rootProject.name = "payments"\n']],
        'gradle-wrapper'  : [tool: 'gradle', files: ['gradle/wrapper/gradle-wrapper.properties': 'distributionUrl=gradle-8.5-bin.zip\n']],
        'android'         : [tool: 'android', files: ['build.gradle': '', 'app/src/main/AndroidManifest.xml': '<manifest/>']],
        'dotnet-sln'      : [tool: 'dotnet', files: ['Payments.sln': '', 'src/Payments/Payments.csproj': '<Project Sdk="Microsoft.NET.Sdk"/>', 'package.json': '{}']],
        'dotnet-csproj'   : [tool: 'dotnet', files: ['src/Payments/Payments.csproj': '<Project Sdk="Microsoft.NET.Sdk"/>']],
        'dotnet-global'   : [tool: 'dotnet', files: ['global.json': '{"sdk": {"version": "8.0.100"}}']],
        'liquibase'       : [tool: 'liquibase', files: ['db/changelog/db.changelog-master.xml': '<databaseChangeLog/>', 'db/tables/orders.sql': 'CREATE TABLE orders (id NUMBER);\n', 'requirements.txt': '', 'package.json': '{}']],
        'liquibase-props' : [tool: 'liquibase', files: ['liquibase.properties': 'changeLogFile=changelog.xml\n']],
        'pip-requirements': [tool: 'pip', files: ['requirements.txt': 'requests==2.31.0\n', 'package.json': '{}']],
        'pip-pyproject'   : [tool: 'pip', files: ['pyproject.toml': '[project]\nname = "payments"\n']],
        'pip-setup'       : [tool: 'pip', files: ['setup.py': 'from setuptools import setup\n']],
        'pip-pipfile'     : [tool: 'pip', files: ['Pipfile': '[packages]\n']],
        'npm'             : [tool: 'npm', files: ['package.json': '{"name": "web"}', 'db/tables/orders.sql': 'CREATE TABLE orders (id NUMBER);\n']],
        'angular'         : [tool: 'npm', files: ['angular.json': '{"version": 1}']],
        'sql'             : [tool: 'sql', files: ['db/tables/orders.sql': 'CREATE TABLE orders (id NUMBER);\n', 'db/packages/orders_pkg.pks': 'CREATE OR REPLACE PACKAGE orders_pkg AS END;\n/\n', 'README.md': '# orders\n']],
        'nothing'         : [tool: null, files: ['README.md': '# empty\n']]
]
Map detectionFound = [:]
try {
    detectionMatrix.each { String name, Map row ->
        FakeScript ds = new FakeScript()
        ds.deepGlob = true
        ds.files.putAll(row.files as Map)
        detectionFound[name] = (harness.run { harness.type('com.bbh.utils.RepoStackAutoResolver').newInstance(ds).resolve([:], 'payments') } as Map).buildTool
    }
} catch (Throwable t) {
    detectionFound.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}
check('RepoStackAutoResolver: the detection matrix gives the build tool of every supported technology (Flutter, iOS, Maven, Gradle in Groovy and Kotlin, Android, .NET, Liquibase, Python, npm and Angular, SQL and PL/SQL files) in the documented order, and none for a repository without a marker',
        !detectionFound.error && detectionMatrix.every { String name, Map row -> detectionFound[name] == row.tool },
        detectionMatrix.findAll { String name, Map row -> detectionFound[name] != row.tool }.collect { String name, Map row -> "${name}: expected ${row.tool}, found ${detectionFound[name]}" } + [detectionFound.error])

Map prUnits = [:]
try {
    harness.run {
        def svc = harness.type('com.bbh.remediation.SecurityPullRequestService')
        def pub = harness.type('com.bbh.scm.BitbucketPullRequestPublisher')
        def parser = harness.type('com.bbh.remediation.SecurityFindingsParser')
        def catalogs = harness.type('com.bbh.remediation.SharedCatalogRepositoryService')
        prUnits.sonar = parser.sonarIssuesFromJson([issues: [[component: 'com.bbh:pay:src/main/java/A.java', line: 12, severity: 'CRITICAL', rule: 'java:S2076', message: 'Injection'],
                                                         [component: 'com.bbh:pay:pom.xml', severity: 'MAJOR', rule: 'xml:S1', message: 'no line']]])
        prUnits.kept = svc.atLeast([[severity: 'LOW'], [severity: 'MEDIUM'], [severity: 'CRITICAL']], 'MEDIUM', svc.sastOrder())
        prUnits.limits = svc.decorationLimits([maxComments: 'many', sastMinSeverity: 'urgent', sonarMinSeverity: 'major'])
        prUnits.noLimits = svc.decorationLimits([:])
        prUnits.ownLimits = svc.decorationLimits([maxComments: 5, sastMinSeverity: 'high', sonarMinSeverity: 'BLOCKER'])
        prUnits.posted = svc.alreadyPosted(['**[SonarQube] CRITICAL: java:S2076**\n\nInjection'], '**[SonarQube] CRITICAL: java:S2076**\n\nInjection')
        prUnits.fresh = svc.alreadyPosted(['other'], 'new text')
        prUnits.server = pub.commentTexts([values: [[action: 'COMMENTED', comment: [text: 'a']], [action: 'APPROVED']]], false)
        prUnits.cloud = pub.commentTexts([values: [[content: [raw: 'b']]]], true)
        prUnits.catalog = catalogs.catalogSettings([url: 'https://bitbucket.bbh.com/scm/plat/catalog.git', path: '/gradle/libs.versions.toml', credentialsId: 'bb'])
        prUnits.noCatalog = catalogs.catalogSettings([branch: 'main'])
        prUnits.html = parser.sastFindingsFromHtml('<p>Issue ID: 1</p><p>Severity: High</p><p>Status: Open</p><p>Issue Type: SQL Injection, x</p><p>File: src/main/java/A.java</p><p>Line: 42</p>' +
                '<p>Issue ID: 2</p><p>Severity: High</p><p>Status: Noise</p><p>File: src/B.java</p><p>Line: 3</p>')
    }
} catch (Throwable t) {
    prUnits.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}
check('pull request decoration: SonarQube issues with a line become line findings, the minimum severity filters, a comment already on the pull request is not posted again',
        !prUnits.error && prUnits.sonar.size() == 1 && prUnits.sonar[0].file == 'src/main/java/A.java' && prUnits.sonar[0].line == 12
                && prUnits.kept.size() == 2 && prUnits.posted == true && prUnits.fresh == false
                && prUnits.server == ['a'] && prUnits.cloud == ['b'],
        prUnits)
check('pull request decoration: a wrong limit falls back to its default with a warning, a limit equal to the default is named as removable, and a limit that is set wins',
        !prUnits.error && prUnits.limits.maxComments == 25 && prUnits.limits.sastMinSeverity == 'MEDIUM' && prUnits.limits.sonarMinSeverity == 'MAJOR'
                && prUnits.limits.warnings == ["pullRequestDecoration.maxComments: 'many' is not a number of comments - using the default 25",
                                               "pullRequestDecoration.sastMinSeverity: 'urgent' is not one of LOW, MEDIUM, HIGH, CRITICAL - using the default MEDIUM"]
                && prUnits.limits.notes == ['pullRequestDecoration.sonarMinSeverity: MAJOR equals the default - the key can be removed']
                && prUnits.noLimits.maxComments == 25 && !prUnits.noLimits.warnings && !prUnits.noLimits.notes
                && prUnits.ownLimits.maxComments == 5 && prUnits.ownLimits.sastMinSeverity == 'HIGH' && prUnits.ownLimits.sonarMinSeverity == 'BLOCKER' && !prUnits.ownLimits.warnings,
        prUnits)
check('pull request decoration: an AppScan SAST finding marked Noise is not commented',
        !prUnits.error && prUnits.html.size() == 1 && prUnits.html[0].file == 'src/main/java/A.java' && prUnits.html[0].line == 42 && prUnits.html[0].severity == 'HIGH',
        prUnits.html)
check('GoldenFix: goldenFix.versionCatalogRepo names the shared version catalog repository, its branch (main by default) and its files',
        !prUnits.error && prUnits.catalog.url == 'https://bitbucket.bbh.com/scm/plat/catalog.git' && prUnits.catalog.branch == 'main'
                && prUnits.catalog.paths == ['gradle/libs.versions.toml'] && prUnits.catalog.credentialsId == 'bb' && prUnits.noCatalog == [:],
        prUnits)

Map apiUnits = [:]
try {
    harness.run {
        def issues = harness.type('com.bbh.utils.AppScanIssues')
        def svc = harness.type('com.bbh.remediation.SecurityPullRequestService')
        def view = harness.type('com.bbh.remediation.SecurityPullRequestView')
        def pub = harness.type('com.bbh.scm.BitbucketPullRequestPublisher')
        apiUnits.findings = issues.findings(issues.items([Items: [
                [Id: 'i-1', Severity: 'High', Status: 'Open', IssueType: 'SQL Injection', IssueTypeId: 'Injection.SQL', SourceFile: 'src/main/java/A.java', Line: 42, Cwe: 89, Api: 'java.sql.Statement.executeQuery'],
                [Id: 'i-2', Severity: 'Critical', Status: 'New', IssueType: 'Command Injection', IssueTypeId: 'Injection.OS', Location: 'src/main/java/B.java:7'],
                [Id: 'i-3', Severity: 'High', Status: 'Noise', IssueType: 'SQL Injection', IssueTypeId: 'Injection.SQL', SourceFile: 'src/main/java/C.java', Line: 3],
                [Id: 'i-4', Severity: 'Medium', Status: 'Fixed', IssueType: 'XSS', SourceFile: 'web/a.js', Line: 1],
                [Id: 'i-5', Severity: 'Medium', Status: 'Reopened', IssueType: 'Open source component', DiscoveryMethod: 'SCA', Location: 'commons-text-1.9.jar'],
                [Id: 'i-6', Severity: 'High', Status: 'InProgress', IssueType: 'SQL Injection', IssueTypeId: 'Injection.SQL', Location: 'Dao.cs(88)']]]))
        apiUnits.bare = issues.items([[Id: 'x']])
        apiUnits.noList = issues.items([Message: 'Unauthorized'])
        apiUnits.requests = issues.advisoryRequests(apiUnits.findings as List, 40)
        apiUnits.limited = issues.advisoryRequests(apiUnits.findings as List, 1)
        apiUnits.xml = issues.fixRecommendation('<?xml version="1.0"?><IssueDetails><Cause>Unvalidated input</Cause><ns:FixRecommendation xmlns:ns="x"><![CDATA[<p>Use a <b>PreparedStatement</b>.</p><ul><li>Bind every parameter</li><li>Never concatenate</li></ul>]]></ns:FixRecommendation></IssueDetails>')
        apiUnits.escaped = issues.fixRecommendation('<advisory><fix_recommendation>&lt;p&gt;Encode the output &amp;amp; validate the input.&lt;/p&gt;</fix_recommendation></advisory>')
        apiUnits.json = issues.fixRecommendation('{"Cause":"x","FixRecommendation":"Line one.\\nUse \\"bound\\" parameters."}')
        apiUnits.html = issues.fixRecommendation('<html><body><h2>Cause</h2><p>x</p><h2>Fix Recommendation</h2><p>Validate the path.</p><h2>References</h2><p>y</p></body></html>')
        apiUnits.none = issues.fixRecommendation('<IssueDetails><Cause>x</Cause></IssueDetails>')
        apiUnits.empty = issues.fixRecommendation('')
        apiUnits.applied = issues.applyAdvisories(apiUnits.findings as List, ['Injection.SQL': 'Use a PreparedStatement.'])
        apiUnits.comment = view.sastLineComment((apiUnits.findings as List)[0] as Map)
        apiUnits.noFix = view.sastLineComment((apiUnits.findings as List)[1] as Map)
        List comments = [[file: 'src/main/java/A.java', line: 42, text: 'a', tool: 'AppScan SAST'], [file: 'src/main/java/B.java', line: 7, text: 'b', tool: 'AppScan SAST'],
                         [file: 'src/main/java/B.java', line: 9, text: 'b2', tool: 'SonarQube'], [file: 'A.java', line: 5, text: 'short', tool: 'AppScan SAST'],
                         [file: '', line: null, text: 'no file', tool: 'Nexus IQ'], [file: 'pom.xml', line: null, text: 'no line', tool: 'Nexus IQ']]
        apiUnits.plan = svc.commentPlan(comments, ['src/main/java/A.java', 'pom.xml'])
        apiUnits.unknown = svc.commentPlan(comments, null)
        apiUnits.unchanged = view.unchangedFileComment('src/main/java/B.java', [[line: 7, text: 'b'], [line: 9, text: 'b2']])
        apiUnits.ambiguous = svc.changedPath('A.java', ['x/A.java', 'y/A.java'])
        apiUnits.pomLine = svc.manifestLine('<project>\n<dependencies>\n<dependency>\n<groupId>org.yaml</groupId>\n<artifactId>snakeyaml</artifactId>\n<version>1.33</version>\n</dependency>\n</dependencies>\n</project>',
                [component: 'org.yaml:snakeyaml', from: '1.33', to: '1.34', property: ''])
        apiUnits.gradleLine = svc.manifestLine('plugins { id "java" }\ndependencies {\n  implementation "io.netty:netty-codec:4.1.100.Final"\n}', [component: 'io.netty:netty-codec', from: '4.1.100.Final', property: ''])
        apiUnits.propertyLine = svc.manifestLine('a=1\nnettyVersion=4.1.100.Final\n', [component: 'io.netty:netty-codec, io.netty:netty-handler', from: '4.1.100.Final', property: 'nettyVersion'])
        apiUnits.noLine = svc.manifestLine('nothing here', [component: 'org.yaml:snakeyaml', from: '1.33', property: ''])
        apiUnits.dependency = view.dependencyComment([component: 'org.yaml:snakeyaml', from: '1.33', to: '1.34', applied: true, prUrl: 'https://bitbucket.bbh.com/pr/1'])
        apiUnits.untouched = view.dependencyComment([component: 'com.h2database:h2', from: '1.4.200', to: '2.2.224', applied: false, reason: 'Nexus IQ offers only 2.2.224'])
        apiUnits.server = pub.changedPaths([values: [[path: [toString: 'src/A.java']], [path: [toString: 'pom.xml']]], isLastPage: false, nextPageStart: 25], false)
        apiUnits.cloud = pub.changedPaths([values: [['new': [path: 'src/A.java']], ['old': [path: 'gone.txt'], 'new': null]], next: 'https://api.bitbucket.org/next'], true)
        apiUnits.notAPage = pub.changedPaths([errors: [[message: 'no']]], false)
    }
} catch (Throwable t) {
    apiUnits.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}
List apiFound = (apiUnits.findings ?: []) as List
check('AppScan API issues: an issue with the status Noise, Passed or Fixed is left out, the file and the line come from SourceFile and Line or from Location, an SCA issue is named AppScan SCA, and a page is Items or a bare list',
        !apiUnits.error && apiFound.collect { it.id } == ['i-1', 'i-2', 'i-5', 'i-6']
                && apiFound[0].file == 'src/main/java/A.java' && apiFound[0].line == 42 && apiFound[0].severity == 'HIGH' && apiFound[0].tool == 'AppScan SAST'
                && apiFound[0].description == 'Found by HCL AppScan in src/main/java/A.java:42 (CWE-89, API java.sql.Statement.executeQuery)'
                && apiFound[1].file == 'src/main/java/B.java' && apiFound[1].line == 7 && apiFound[2].tool == 'AppScan SCA' && apiFound[2].line == null
                && apiFound[3].file == 'Dao.cs' && apiFound[3].line == 88 && apiUnits.bare == [[Id: 'x']] && apiUnits.noList == null,
        apiUnits.error ?: apiFound)
check('AppScan API issues: the recommended fix is asked once per issue type, the most severe first and at most as many as the limit, and is set on every finding of that type',
        !apiUnits.error && apiUnits.requests == [[typeKey: 'Injection.OS', id: 'i-2'], [typeKey: 'Injection.SQL', id: 'i-1'], [typeKey: 'Open source component', id: 'i-5']]
                && apiUnits.limited == [[typeKey: 'Injection.OS', id: 'i-2']] && apiUnits.applied == 2
                && apiFound[0].remediation == 'Use a PreparedStatement.' && apiFound[3].remediation == 'Use a PreparedStatement.' && apiFound[1].remediation == '',
        apiUnits.error ?: [apiUnits.requests, apiUnits.limited, apiUnits.applied])
check('AppScan API issues: the recommended fix is read from an XML answer with a namespace and CDATA, from escaped markup, from JSON and from an HTML advisory, and is empty when the answer has none',
        !apiUnits.error && apiUnits.xml == 'Use a PreparedStatement .\n- Bind every parameter\n- Never concatenate'
                && apiUnits.escaped == 'Encode the output & validate the input.' && apiUnits.json == 'Line one.\nUse "bound" parameters.'
                && apiUnits.html == 'Validate the path.' && apiUnits.none == '' && apiUnits.empty == '',
        apiUnits.error ?: [apiUnits.xml, apiUnits.escaped, apiUnits.json, apiUnits.html, apiUnits.none])
check('pull request decoration: the comment of a SAST finding carries the fix AppScan recommends, and says so when AppScan returned none',
        !apiUnits.error && apiUnits.comment == '**[AppScan SAST] HIGH: SQL Injection**\n\nFound by HCL AppScan in src/main/java/A.java:42 (CWE-89, API java.sql.Statement.executeQuery)\n\n**Recommended fix (AppScan):**\nUse a PreparedStatement.'
                && (apiUnits.noFix as String).endsWith('**Recommended fix (AppScan):** AppScan returned no fix recommendation for this issue type - open the issue in AppScan for the advisory.'),
        apiUnits.error ?: [apiUnits.comment, apiUnits.noFix])
Map plan = (apiUnits.plan ?: [:]) as Map
Map unknownPlan = (apiUnits.unknown ?: [:]) as Map
check('pull request decoration: a finding in a file the pull request changes is a line comment of that file, also when AppScan names the file by the end of its path; findings in a file it does not change, without a line or without a file are one pull request comment per file that names the file and the lines',
        !apiUnits.error && (plan.inline as List).collect { [it.file, it.line] } == [['src/main/java/A.java', 42], ['src/main/java/A.java', 5]]
                && (plan.general as List).collect { [it.file, (it.entries as List).collect { e -> e.line }] } == [['src/main/java/B.java', [7, 9]], ['', [null]], ['pom.xml', [null]]]
                && apiUnits.unchanged == '**[DevSecOps] Findings in `src/main/java/B.java`** (this pull request does not change the file)\n\n---\n`src/main/java/B.java:7`\n\nb\n\n---\n`src/main/java/B.java:9`\n\nb2\n'
                && apiUnits.ambiguous == null,
        apiUnits.error ?: [plan, apiUnits.unchanged])
check('pull request decoration: when the changed files are unknown every finding with a file and a line is tried as a line comment',
        !apiUnits.error && (unknownPlan.inline as List).size() == 4 && (unknownPlan.general as List).collect { it.file } == ['', 'pom.xml'],
        apiUnits.error ?: unknownPlan)
check('pull request decoration: a Nexus IQ upgrade is placed on the line of the manifest that declares the version (pom.xml, build.gradle, a version property), and its comment names the version Nexus IQ recommends or why it was left untouched',
        !apiUnits.error && apiUnits.pomLine == 6 && apiUnits.gradleLine == 3 && apiUnits.propertyLine == 2 && apiUnits.noLine == null
                && apiUnits.dependency == '**[Nexus IQ] org.yaml:snakeyaml 1.33**\n\n**Recommended fix (Nexus IQ):** upgrade to `1.34`. The GoldenFix pull request https://bitbucket.bbh.com/pr/1 makes this change.'
                && apiUnits.untouched == '**[Nexus IQ] com.h2database:h2 1.4.200**\n\n**Recommended fix (Nexus IQ):** upgrade to `2.2.224`. Not changed automatically: Nexus IQ offers only 2.2.224.',
        apiUnits.error ?: [apiUnits.pomLine, apiUnits.gradleLine, apiUnits.propertyLine, apiUnits.noLine, apiUnits.dependency, apiUnits.untouched])
check('Bitbucket: the files a pull request changes are read from the changes of Bitbucket Server and from the diffstat of Bitbucket Cloud, page by page, and an answer that is not a page is not taken for an empty list',
        !apiUnits.error && apiUnits.server == [known: true, paths: ['src/A.java', 'pom.xml'], next: null, nextStart: 25]
                && apiUnits.cloud == [known: true, paths: ['src/A.java', 'gone.txt'], next: 'https://api.bitbucket.org/next', nextStart: null]
                && apiUnits.notAPage.known == false,
        apiUnits.error ?: [apiUnits.server, apiUnits.cloud, apiUnits.notAPage])

Map decorated = [:]
try {
    SandboxHarness dh = new SandboxHarness(srcDir, stubDir, new File(root, 'test/sandbox/fixtures'))
    FakeScript ds = new FakeScript(unix: true)
    ds.env.vars.putAll([WORKSPACE: '/ws', CHANGE_ID: '77', CHANGE_URL: 'https://bitbucket.bbh.com/projects/PAY/repos/payments/pull-requests/77'])
    ds.files['pom.xml'] = '<project>\n<dependency>\n<artifactId>snakeyaml</artifactId>\n<version>1.33</version>\n</dependency>\n</project>'
    dh.run {
        def state = dh.type('com.bbh.core.PipelineState').newInstance()
        def recorder = dh.type('com.bbh.fixture.RecordingPullRequestPublisher').newInstance()
        def repo = dh.type('com.bbh.fixture.InMemorySourceRepository').newInstance([[:]] as Object[])
        Map cfg = [scm: [bitbucket: [url: 'https://bitbucket.bbh.com/projects/PAY/repos/payments', credentialsId: 'bb']], pullRequestDecoration: [enabled: true, sastMinSeverity: 'LOW']]
        state.currentProjectName = 'payments'
        state.cfg = cfg
        state.projectsScanResults = [payments: [sast: 'WARN']]
        state.projectsSastFindings = [payments: [source: 'api', scanId: 'scan-1', findings: [
                [id: 'i-1', typeKey: 'Injection.SQL', file: '/ws/src/main/java/A.java', line: 42, severity: 'HIGH', type: 'SQL Injection', tool: 'AppScan SAST', description: 'Found by HCL AppScan in src/main/java/A.java:42', remediation: 'Use a PreparedStatement.'],
                [id: 'i-2', typeKey: 'Injection.OS', file: 'src/main/java/B.java', line: 7, severity: 'CRITICAL', type: 'Command Injection', tool: 'AppScan SAST', description: 'Found by HCL AppScan in src/main/java/B.java:7', remediation: 'Do not pass input to a shell.'],
                [id: 'i-3', typeKey: 'Injection.OS', file: 'src/main/java/B.java', line: 19, severity: 'CRITICAL', type: 'Command Injection', tool: 'AppScan SAST', description: 'Found by HCL AppScan in src/main/java/B.java:19', remediation: 'Do not pass input to a shell.']]]]
        state.projectsGoldenFix = [payments: [prUrl: 'https://bitbucket.bbh.com/projects/PAY/repos/payments/pull-requests/76', prId: '76', branch: 'GoldenFix-202611201200', status: 'PR_CREATED',
                                              changes: [[file: 'pom.xml', component: 'org.yaml:snakeyaml', from: '1.33', to: '1.34', property: '']],
                                              unresolved: [[component: 'com.h2database:h2', from: '1.4.200', to: '2.2.224', reason: 'Nexus IQ offers only 2.2.224']]]]
        recorder.changed = ['src/main/java/A.java', 'pom.xml']
        def prService = dh.type('com.bbh.remediation.SecurityPullRequestService').newInstance(ds, state, recorder, repo)
        prService.publishStaticSecurityPullRequest()
        decorated.first = [lines: recorder.lineComments.collect { [it.pullRequest.id, it.path, it.line] }, comments: recorder.comments.size()]
        prService.publishStaticSecurityPullRequest()
        decorated.lines = recorder.lineComments.collect { [it.pullRequest.id, it.path, it.line, it.text] }
        decorated.comments = recorder.comments.collect { it.text as String }
        state.projectsSastFindings = [:]
        decorated.fallback = dh.type('com.bbh.remediation.SecurityFindingsParser').newInstance(ds, state, null).extractSastFindings('payments', cfg)
    }
    decorated.log = ds.log
} catch (Throwable t) {
    decorated.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}
List decoratedLines = (decorated.lines ?: []) as List
List<String> decoratedComments = (decorated.comments ?: []) as List<String>
check('pull request decoration, end to end: the SAST findings of the AppScan API and the Nexus IQ upgrade are comments under the line of the files the pull request changes; the findings of the file it does not change are one pull request comment that names the file and both lines, the dependency left untouched is in a comment without a file, and a second run adds nothing',
        !decorated.error && decoratedLines.collect { it.subList(0, 3) } == [['77', 'src/main/java/A.java', 42], ['77', 'pom.xml', 4]]
                && (decoratedLines[0][3] as String).endsWith('**Recommended fix (AppScan):**\nUse a PreparedStatement.')
                && (decoratedLines[1][3] as String).endsWith('**Recommended fix (Nexus IQ):** upgrade to `1.34`. The GoldenFix pull request https://bitbucket.bbh.com/projects/PAY/repos/payments/pull-requests/76 makes this change.')
                && decoratedComments.size() == 3
                && decoratedComments[0].startsWith('**[DevSecOps] Findings in `src/main/java/B.java`** (this pull request does not change the file)')
                && decoratedComments[0].contains('`src/main/java/B.java:7`') && decoratedComments[0].contains('`src/main/java/B.java:19`') && decoratedComments[0].contains('Do not pass input to a shell.')
                && decoratedComments[1].startsWith('**[DevSecOps] Findings without a file of this repository**') && decoratedComments[1].contains('com.h2database:h2 1.4.200')
                && decoratedComments[2].startsWith('**DevSecOps static security results - payments**')
                && decorated.first == [lines: decoratedLines.collect { it.subList(0, 3) }, comments: 3]
                && (decorated.log as List<String>).contains('[SECURITY-PR] Pull request https://bitbucket.bbh.com/projects/PAY/repos/payments/pull-requests/77: 2 comment(s) under the line of a changed file, 2 pull request comment(s) for the files it does not change')
                && decorated.fallback == [],
        decorated.error ?: decorated)

Map credResult = [:]
try {
    FakeScript cs = new FakeScript(unix: true)
    cs.env.vars.putAll([WORKSPACE: '/ws', APPSCAN_SERVER_URL: 'https://bbh.cloud.appscan.com'])
    cs.shHandler = { Map a -> a.returnStdout && String.valueOf(a.script).contains('Account/ApiKeyLogin') ? '{"Token":"BEARER-TOKEN"}' : '' }
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        state.cfg = [
                asoc: [keyId: 'asoc-key', token: 'asoc-token'],
                sast: [keyId: 'sast-key', token: 'sast-token'],
                dast: [keyId: 'dast-key', credentialsId: 'dast-token']
        ]
        state.cfgDefaults = [asoc: [keyId: 'functional-key', token: 'functional-secret']]
        def os = harness.type('com.bbh.core.OsHelper').newInstance(cs)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(cs, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(cs, state, os, policy)
        def appScan = harness.type('com.bbh.scanner.AppScanService').newInstance(cs, state, os, policy, build)
        appScan.apiLogin('SAST')
        appScan.apiLogin('DAST')
        credResult.ignored = harness.type('com.bbh.config.ConfigLoader').ignoredAppScanKeySettings(state.cfg)
        credResult.secretOfKey = utils().appScanKeySecretCredentialsId([asoc: [keyId: 'functional-key']])
    }
    credResult.creds = cs.calls.findAll { it.startsWith('withCredentials') }
    credResult.body = cs.files['/ws@tmp/asoc-api-key-login.json']
    credResult.keys = cs.calls.findAll { it.contains('asoc-key') || it.contains('sast-key') || it.contains('dast-key') || it.contains('-token') }
} catch (Throwable t) {
    credResult.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}

check('AppScanService: SAST and DAST sign in with the API key of the functional user from the library defaults only; keyId, token and credentialsId of a service are ignored and listed',
        !credResult.error
                && credResult.creds == ['withCredentials [functional-secret]', 'withCredentials [functional-secret]']
                && credResult.body == '{"KeyId":"functional-key","KeySecret":"fake-APPSCAN_KEY_SECRET"}'
                && !credResult.keys
                && credResult.ignored == ['asoc.keyId', 'asoc.token', 'sast.keyId', 'sast.token', 'dast.keyId', 'dast.credentialsId']
                && credResult.secretOfKey == 'functional-key',
        credResult)

Map dedupResult = [:]
try {
    FakeScript sc = new FakeScript(unix: true)
    sc.env.vars.putAll([
            WORKSPACE: '/ws',
            APPSCAN_LOG_DIR: '/ws/logs',
            APPSCAN_HOME_DIR: '/ws/home',
            APPSCAN_BIN_DIR: '/ws/bin',
            APPSCAN_TOOLS_DIR: '/ws/tools',
            APPSCAN_SCAN_NAME: 'service1',
            APPSCAN_SOURCE_DIR_RESOLVED: '/ws/src1'
    ])
    sc.files['appscan-report-service1.html'] = '<!doctype html><html><body><h3>Summary of security issues</h3><table><tr><td>Critical severity issues:</td><td>1</td></tr><tr><td>High severity issues:</td><td>2</td></tr><tr><td>Medium severity issues:</td><td>3</td></tr><tr><td>Low severity issues:</td><td>4</td></tr><tr><td>Total security issues:</td><td>10</td></tr></table></body></html>'
    sc.files['service1.irx'] = 'dummy-irx'
    sc.files['logs/appscan.cmd'] = '/ws/bin/appscan.sh'
    sc.files['logs/proxy.pass'] = 'pass'
    sc.files['home/.appscan/appscan.properties'] = 'server.url=https://bbh.cloud.appscan.com'
    String sharedReport = sc.files.remove('appscan-report-service1.html')
    sc.shHandler = { Map a ->
        String cmd = a.script as String
        def out = cmd =~ /-o "([^"]+)"/
        if (out.find()) sc.files[out.group(1)] = out.group(1).endsWith('.pdf') ? '%PDF-1.7' : sharedReport
        def move = cmd =~ /mv '\/ws\/([^']+)' '\/ws\/([^']+)'/
        if (move.find() && sc.files.containsKey(move.group(1))) sc.files[move.group(2)] = sc.files.remove(move.group(1))
        def copy = cmd =~ /cp '\/ws\/([^']+)' '\/ws\/([^']+)'/
        if (copy.find() && sc.files.containsKey(copy.group(1))) sc.files[copy.group(2)] = sc.files[copy.group(1)]
        if (!a.returnStdout) return null
        if (cmd.contains('Account/ApiKeyLogin')) return '{"Token":"BEARER-TOKEN"}'
        if (cmd.contains('/api/v4/FileUpload')) return '{"FileId":"file-1234"}'
        if (cmd.contains('/api/v4/Scans/Sast/')) return '{"LatestExecution":{"Status":"Ready"}}'
        if (cmd.contains('/api/v4/Scans/Sast')) return '{"Id":"12345678-1234-1234-1234-123456789012"}'
        if (cmd.contains('/api/v4/Reports/Security/Scan/')) return '{"Id":"report-1234"}'
        if (cmd.contains('/api/v4/Reports/report-1234')) return '{"Status":"Ready"}'
        return ''
    }
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(sc, state, os, policy)
        def appScan = harness.type('com.bbh.scanner.AppScanService').newInstance(sc, state, os, policy, build)

        state.currentProjectName = 'service1'
        state.cfgDefaults = [asoc: [keyId: 'functional-key', token: 'functional-secret']]
        state.cfg = [appId: 'shared-app-uuid', asoc: [doCompile: false]]
        appScan.generateIrx()
        appScan.queueSast([:])
        appScan.waitSast()
        appScan.downloadSastReports()
        dedupResult.service1Counts = state.vulnCounts.sast

        sc.env.vars.APPSCAN_SCAN_NAME = 'service2'
        sc.env.vars.APPSCAN_SOURCE_DIR_RESOLVED = '/ws/src2'
        state.currentProjectName = 'service2'
        state.cfg = [appId: 'shared-app-uuid', asoc: [doCompile: false]]
        appScan.generateIrx()
        appScan.queueSast([:])
        appScan.waitSast()
        appScan.downloadSastReports()
        dedupResult.service2Counts = state.vulnCounts.sast
        dedupResult.echoes = sc.log
        dedupResult.service2Id = sc.files['logs/scan-sast-service2.id']
        dedupResult.uploads = sc.calls.count { it.contains('/api/v4/FileUpload') }
        dedupResult.polls = sc.calls.count { it.contains('/api/v4/Scans/Sast/') }
        dedupResult.reports = sc.calls.count { it.contains('/api/v4/Reports/Security/Scan/') }
        dedupResult.service2Report = sc.files['appscan-report-service2.html']
    }
} catch (Throwable t) {
    dedupResult.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}

check('AppScanService: deduplicates SAST scan when services share the same AppScan appId',
        !dedupResult.error
                && dedupResult.service1Counts == [critical: 1, high: 2, medium: 3, low: 4]
                && dedupResult.service2Counts == [critical: 1, high: 2, medium: 3, low: 4]
                && dedupResult.service2Id == '12345678-1234-1234-1234-123456789012'
                && dedupResult.uploads == 1 && dedupResult.polls == 1 && dedupResult.reports == 2
                && dedupResult.service2Report?.contains('Critical severity issues:')
                && dedupResult.echoes.any { it.contains("shares AppScan application ID 'shared-app-uuid' with 'service1' - skipping duplicate IRX generation") }
                && dedupResult.echoes.any { it.contains("shares AppScan application ID 'shared-app-uuid' with 'service1' - reusing scan ID") }
                && dedupResult.echoes.any { it.contains("scan for application ID 'shared-app-uuid' already completed - skipping wait") }
                && dedupResult.echoes.any { it.contains("shares AppScan application ID 'shared-app-uuid' with 'service1' - reusing report and findings") },
        "error=${dedupResult.error} uploads=${dedupResult.uploads} polls=${dedupResult.polls} reports=${dedupResult.reports} echoes=${dedupResult.echoes}")

Map dotnetStatic = [:]
try {
    FakeScript resolverScript = new FakeScript(unix: false)
    resolverScript.files.putAll([
            'OrdersSolution.sln'             : 'Microsoft Visual Studio Solution File',
            'src/Orders.Api/Orders.Api.csproj': '<Project Sdk="Microsoft.NET.Sdk.Web"></Project>',
            'src/Orders.Api/Program.cs'      : 'Console.WriteLine("hi");'
    ])
    Map resolvedDotnet = harness.run {
        def resolver = harness.type('com.bbh.utils.RepoStackAutoResolver').newInstance(resolverScript)
        resolver.resolve([appId: 'dn-app', asoc: [keyId: 'dn-key']], 'orders-api')
    } as Map

    FakeScript winSonarClassic = new FakeScript(unix: false)
    winSonarClassic.env.vars.putAll([WORKSPACE: 'C:\\ws', BRANCH_NAME: 'feature/dotnet-win', JOB_NAME: 'orders-job', BUILD_NUMBER: '7'])
    winSonarClassic.files.putAll([
            'Orders.sln'            : 'Microsoft Visual Studio Solution File',
            'coverage.opencover.xml': '<CoverageSession/>',
            'TestResults/run.trx'   : '<TestRun/>'
    ])
    winSonarClassic.steps['waitForQualityGate'] = { [status: 'OK'] }
    winSonarClassic.shHandler = { Map a ->
        String cmd = String.valueOf(a.script ?: '')
        if (cmd.contains('Invoke-RestMethod') && cmd.contains('/api/issues/search')) return '1,2,3,4'
        return ''
    }
    Map classicOut = harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(winSonarClassic)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(winSonarClassic, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(winSonarClassic, state, os, policy)
        def sonar = harness.type('com.bbh.scanner.SonarService').newInstance(winSonarClassic, state, os, build)
        state.currentProjectName = 'orders-api'
        state.cfgDefaults = [sca: [enabled: true, maxCritical: 0, maxHigh: 0, maxMedium: 0], tools: [sonar: [qualityGate: [waitForQualityGate: true]]]]
        state.cfg = [
                buildTool: 'dotnet',
                build    : [dotnet: [solution: 'Orders.sln', configuration: 'Release']],
                tools    : [sonar: [
                        projectName            : 'Orders API',
                        projectKey             : 'com.bbh.orders:orders-api',
                        authToken              : 'sonar-auth',
                        sonarScannerMsbuildPath: 'C:\\tools\\SonarScanner.MSBuild.exe'
                ]]
        ]
        sonar.scan()
        [vuln: state.vulnCounts.sca, res: state.sonarResults]
    } as Map

    FakeScript winSonarCli = new FakeScript(unix: true)
    winSonarCli.env.vars.putAll([WORKSPACE: '/ws', BRANCH_NAME: 'main', JOB_NAME: 'billing-job', BUILD_NUMBER: '8'])
    winSonarCli.files['Billing.csproj'] = '<Project Sdk="Microsoft.NET.Sdk"/>'
    winSonarCli.steps['waitForQualityGate'] = { [status: 'OK'] }
    winSonarCli.shHandler = { Map a ->
        String cmd = String.valueOf(a.script ?: '')
        if (cmd.contains('curl')) return '0,0,0,0'
        return 'Linux'
    }
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(winSonarCli)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(winSonarCli, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(winSonarCli, state, os, policy)
        def sonar = harness.type('com.bbh.scanner.SonarService').newInstance(winSonarCli, state, os, build)
        state.currentProjectName = 'billing'
        state.cfgDefaults = [sca: [enabled: true, maxCritical: 0, maxHigh: 0, maxMedium: 0], tools: [sonar: [qualityGate: [waitForQualityGate: false]]]]
        state.cfg = [
                buildTool: 'dotnet',
                build    : [dotnet: [solution: 'Billing.csproj', useDotnetCli: true]],
                tools    : [sonar: [projectKey: 'com.bbh.orders:billing', authToken: 'sonar-auth', dotnet: [useDotnetCli: true]]]
        ]
        sonar.scan()
    }

    FakeScript winAppScan = new FakeScript(unix: false)
    winAppScan.env.vars.putAll([
            WORKSPACE                  : 'C:/ws',
            APPSCAN_LOG_DIR            : 'C:/ws/logs',
            APPSCAN_HOME_DIR           : 'C:/ws/home',
            APPSCAN_BIN_DIR            : 'C:/ws/bin',
            APPSCAN_TOOLS_DIR          : 'C:/ws/tools',
            APPSCAN_SCAN_NAME          : 'orders-sast',
            APPSCAN_SOURCE_DIR_RESOLVED: 'C:/ws'
    ])
    winAppScan.files['Orders.sln'] = 'Microsoft Visual Studio Solution File'
    winAppScan.files['logs/appscan.cmd'] = 'C:\\ws\\.appscan-tools\\bin\\appscan.bat'
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(winAppScan)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(winAppScan, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(winAppScan, state, os, policy)
        def appScan = harness.type('com.bbh.scanner.AppScanService').newInstance(winAppScan, state, os, policy, build)
        state.currentProjectName = 'orders-api'
        state.cfg = [
                appId      : 'dn-sast-uuid',
                buildTool  : 'dotnet',
                appscanPath: '/.appscan-tools/bin/appscan.bat',
                asoc       : [keyId: 'k', doCompile: true],
                build      : [dotnet: [solution: 'Orders.sln', configuration: 'Release']]
        ]
        appScan.generateIrx()
    }

    dotnetStatic = [
            resolved         : resolvedDotnet,
            classicCalls     : winSonarClassic.calls,
            classicVulns     : classicOut.vuln,
            classicStatus    : (classicOut.res as Map)?.status,
            cliCalls         : winSonarCli.calls,
            winAppScanCalls  : winAppScan.calls
    ]
} catch (Throwable t) {
    dotnetStatic.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}

check('RepoStackAutoResolver, SonarService and AppScanService support C#/.NET end-to-end on Windows classic MSBuild and SDK CLI',
        !dotnetStatic.error
                && (dotnetStatic.resolved as Map)?.buildTool == 'dotnet'
                && (dotnetStatic.resolved as Map)?.buildToolAutoSetup == null
                && ((dotnetStatic.resolved as Map)?.tools as Map)?.sonar?.projectKey == 'com.bbh:orders-api'
                && (dotnetStatic.classicCalls as List<String>).any { it.startsWith('bat ') && it.contains('SonarScanner.MSBuild.exe') && it.contains('begin') && it.contains('/d:sonar.cs.opencover.reportsPaths=coverage.opencover.xml') && it.contains('/d:sonar.cs.vstest.reportsPaths=TestResults/run.trx') && it.contains('/d:sonar.branch.name=feature/dotnet-win') }
                && (dotnetStatic.classicCalls as List<String>).any { it.startsWith('bat ') && it.contains('nuget.exe restore Orders.sln') }
                && (dotnetStatic.classicCalls as List<String>).any { it.startsWith('bat ') && it.contains('MSBuild.exe Orders.sln') }
                && (dotnetStatic.classicCalls as List<String>).any { it.startsWith('bat ') && it.contains('SonarScanner.MSBuild.exe') && it.contains('end') }
                && (dotnetStatic.classicCalls as List<String>).any { it.startsWith('powershell ') && it.contains('Authorization = "Bearer') && it.contains('/api/issues/search') }
                && dotnetStatic.classicVulns == [critical: 1, high: 2, medium: 3, low: 4]
                && (dotnetStatic.cliCalls as List<String>).any { it.contains("'dotnet' 'sonarscanner' 'begin'") && it.contains("'/k:com.bbh.orders:billing'") }
                && (dotnetStatic.cliCalls as List<String>).any { it.contains("'dotnet' 'build' 'Billing.csproj'") }
                && (dotnetStatic.cliCalls as List<String>).any { it.contains("'dotnet' 'sonarscanner' 'end'") }
                && (dotnetStatic.winAppScanCalls as List<String>).any { it.startsWith('bat ') && it.contains('MSBuild.exe Orders.sln') }
                && (dotnetStatic.winAppScanCalls as List<String>).any { it.startsWith('powershell ') && it.contains('robocopy "$env:APPSCAN_SOURCE_DIR_RUNTIME" "$sastDirectory"') && it.contains('/XD sast_directory .git obj packages TestResults') && !it.contains('-l "$env:APPSCAN_SOURCE_DIR_RUNTIME\\lib') },
        dotnetStatic)

println "\n${failures == 0 ? 'ALL STATIC ANALYSIS SCENARIOS PASSED' : failures + ' STATIC ANALYSIS SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
