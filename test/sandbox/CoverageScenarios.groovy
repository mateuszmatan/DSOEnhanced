import devsecops.test.FakeAbort
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')

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

SandboxHarness coverageHarness = new SandboxHarness(srcDir, stubDir)
String scanLabel = 'Coverage: find and read the reports'
Closure realScan = { File tree, String text ->
    File file = File.createTempFile('scan', '.sh')
    file.text = text
    Process process = new ProcessBuilder('sh', file.absolutePath).directory(tree).start()
    String out = process.inputStream.getText('UTF-8')
    process.waitFor()
    file.delete()
    return out
}
Closure coverageRun = { Map projects, Map answers, Map options = [:] ->
    FakeScript sc = new FakeScript(unix: options.windows ? false : true)
    sc.env.vars.putAll([WORKSPACE: '/ws'])
    sc.files.putAll((options.files ?: [:]) as Map)
    List<String> scans = []
    sc.shHandler = { Map a ->
        String text = String.valueOf(a.script ?: '')
        if (String.valueOf(a.label ?: '') == scanLabel) {
            String head = text.readLines()[1]
            scans << head
            if (options.tree) return realScan(options.tree as File, text)
            if (options.abort) throw new FakeAbort('script returned exit code 1')
            def answer = answers.find { k, v -> head.contains(k as String) }
            return answer ? answer.value : 'S\t0'
        }
        if (text.contains('uname')) return 'Linux'
        if (text.contains("findall('package')")) return 'com.bbh.x,Legacy,Legacy.java,50.0,5,5,10'
        if (text.contains('xml.etree.ElementTree')) return '50.0,5,5'
        if (text.contains("awk -F:") || text.contains('-match')) return '75.0,3,1'
        if (text.contains('[xml]$doc')) return '50.0,5,5'
        return a.returnStdout ? '' : null
    }
    Map out = [errors: []]
    try {
        coverageHarness.run {
            def state = coverageHarness.type('com.bbh.core.PipelineState').newInstance()
            def os = coverageHarness.type('com.bbh.core.OsHelper').newInstance(sc)
            os.detect()
            def policy = coverageHarness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os)
            def build = coverageHarness.type('com.bbh.build.BuildService').newInstance(sc, state, os, policy)
            state.projectsAllCfg = projects
            state.coverage.minRequired = 60
            projects.each { String name, Map cfg ->
                state.currentProjectName = name
                state.cfg = cfg
                build.checkCoverage()
            }
            def report = coverageHarness.type('com.bbh.report.HtmlReportService').newInstance(sc, state, os, policy, 'security')
            out.projects = state.projectsCoverage
            out.run = state.coverage
            out.status = state.policyStatus.coverage
            out.reason = (state.stageErrors['Unit tests'] ?: '') as String
            out.metrics = coverageHarness.type('com.bbh.metrics.PipelineMetrics').coverage(state, [project: 'P', env: 'test'], 1L)
            out.card = report.coverageCard(state.coverage, state.projectsCoverage)
            out.gate = coverageHarness.type('com.bbh.core.ReleaseGate').decide([:], [:], [], state.coverage, [], state.projectsCoverage)
        }
    } catch (Throwable t) {
        (out.errors as List) << (SandboxHarness.rejectionOf(t) ?: t.toString())
    }
    return out + [log: sc.log, scans: scans, calls: sc.calls]
}
Closure reportsOf = { Map r, String project -> ((((r.projects ?: [:]) as Map)[project] ?: [:]) as Map).reports as List ?: [] }
Closure rowsOf = { Map r, String project -> reportsOf(r, project).collect { "${it.tool} ${it.path} ${it.line} ${it.counted} ${it.reason ?: it.detail}".toString() } }

Map shared = coverageRun([gui          : [buildTool: 'gradle', sourceDir: '.'],
                          'backend-api': [buildTool: 'maven', sourceDir: './'],
                          web          : [buildTool: 'gradle', sourceDir: '.', tests: [unitTests: [gradle: [dir: 'web']]],
                                          build: [node: [enabled: true, dir: 'web/ui']]]],
        ["--own 'gradle' --others 'maven'": "S\t0\n"
                + "R\tjacoco\tbuild/reports/jacoco/test/jacocoTestReport.xml\t824\t1000\t\t\n"
                + "R\tlcov\tgui/coverage/gui/lcov.info\t320\t4000\t\tnode\n"
                + "R\tjacoco\trelease/build/reports/jacoco/test/jacocoTestReport.xml\t824\t1000\tcopy:build/reports/jacoco/test/jacocoTestReport.xml\t\n"
                + "R\tjacoco\tbuild/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml\t900\t1150\taggregate:2\t\n"
                + "R\tjacoco\tbuild/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml\t0\t50\trest:3\t\n"
                + "R\tjacoco\tempty/build/jacoco.xml\t0\t0\tempty\t\n"
                + "C\tPackage: com.bbh.gui, Class: Cert, Coverage: 82.4, Missed: 176, Covered: 824",
         "--own 'maven' --others 'gradle'": "S\t0\nR\tjacoco\ttarget/site/jacoco/jacoco.xml\t840\t1000\t\t",
         "--root 'web' --own 'gradle'"    : "S\t1\nR\tlcov\tcoverage/web/lcov.info\t50\t100\t\tnode"])
check('Coverage: each service searches the folders of its unit tests and its Node.js build without the folders of the other services, and services that share a folder take the reports of their own build tool',
        shared.errors.isEmpty() && shared.scans == ["python3 -I - --root '.' --own 'gradle' --others 'maven' --exclude 'web' --exclude 'web/ui' <<'PYEOF'",
                                                     "python3 -I - --root '.' --own 'maven' --others 'gradle' --exclude 'web' --exclude 'web/ui' <<'PYEOF'",
                                                     "python3 -I - --root 'web' --own 'gradle' --exclude 'web/ui' --and --root 'web/ui' --own 'node' --then --root '.' --own 'gradle' --own 'node' --others 'gradle' --others 'maven' <<'PYEOF'"]
                && shared.log.join('\n').contains('[COVERAGE] Searching the workspace (without web, web/ui) for JaCoCo and LCOV reports; backend-api uses the same folder: each service takes the reports of its own build tool')
                && shared.log.contains('[COVERAGE] Searching web and web/ui for JaCoCo and LCOV reports\n[COVERAGE] No report in web and web/ui; searching the workspace for JaCoCo and LCOV reports; gui, backend-api use the same folder: each service takes the reports of its own build tool\n[COVERAGE] LCOV coverage/web/lcov.info: 50.0% (50 of 100 lines) - also counted for gui, backend-api'),
        shared.errors + shared.scans + shared.log.findAll { it.startsWith('[COVERAGE]') })
check('Coverage: one row per report; copies, aggregates and reports without lines are shown but not counted, the classes only an aggregate holds count, and a report of a shared folder names the other services',
        rowsOf(shared, 'gui') == ['JaCoCo build/reports/jacoco/test/jacocoTestReport.xml 82.4 true ',
                                  'LCOV gui/coverage/gui/lcov.info 8.0 true also counted for backend-api',
                                  'JaCoCo release/build/reports/jacoco/test/jacocoTestReport.xml 82.4 false a copy of build/reports/jacoco/test/jacocoTestReport.xml',
                                  'JaCoCo build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml 78.3 false it combines 2 other reports',
                                  'JaCoCo build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml 0.0 true only its 3 classes that no other report holds',
                                  'JaCoCo empty/build/jacoco.xml null false no lines']
                && shared.log.contains('[COVERAGE] Searching the workspace (without web, web/ui) for JaCoCo and LCOV reports; backend-api uses the same folder: each service takes the reports of its own build tool\n'
                + '[COVERAGE] JaCoCo build/reports/jacoco/test/jacocoTestReport.xml: 82.4% (824 of 1000 lines)\n'
                + '[COVERAGE] LCOV gui/coverage/gui/lcov.info: 8.0% (320 of 4000 lines) - also counted for backend-api\n'
                + '[COVERAGE] JaCoCo release/build/reports/jacoco/test/jacocoTestReport.xml: 82.4% (824 of 1000 lines) - not counted, a copy of build/reports/jacoco/test/jacocoTestReport.xml\n'
                + '[COVERAGE] JaCoCo build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml: 78.3% (900 of 1150 lines) - not counted, it combines 2 other reports\n'
                + '[COVERAGE] JaCoCo build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml: 0.0% (0 of 50 lines) - only its 3 classes that no other report holds\n'
                + '[COVERAGE] JaCoCo empty/build/jacoco.xml: no lines, not counted')
                && shared.log.contains('Class coverage: \nPackage: com.bbh.gui, Class: Cert, Coverage: 82.4, Missed: 176, Covered: 824\n========================='),
        rowsOf(shared, 'gui') + shared.log.findAll { it.startsWith('[COVERAGE]') })
Map guiCoverage = ((shared.projects ?: [:]) as Map).gui as Map ?: [:]
check('Coverage: the coverage of a service is the mean of its reports, the minimum and the release gate apply to that mean',
        guiCoverage.line == 30.1d && guiCoverage.counted == 3 && guiCoverage.covered == 1144 && guiCoverage.total == 5050 && guiCoverage.enabled
                && shared.log.contains('[FAIL] LINE COVERAGE gui: 30.1%  (mean of 3 reports, min required: 60%)')
                && shared.reason.contains('gui: Line coverage 30.1% (mean of 3 reports) is below the required 60%.')
                && shared.log.contains('[unstable] [POLICY] gui: Line coverage 30.1% (mean of 3 reports) is below the required 60%. The Nexus release and the QC deployment stay blocked until this is fixed.')
                && shared.log.contains('[COVERAGE] backend-api: Line coverage 84.0% meets the required 60%.')
                && shared.gate.violations == ['gui line coverage 30.1% below the required 60%', 'web line coverage 50.0% below the required 60%'],
        [guiCoverage.findAll { k, v -> k != 'reports' }, shared.reason, shared.gate])
check('Coverage: the summary of the run is the mean of every counted report of the run, each report counted once',
        shared.run.line == 44.9d && shared.run.counted == 5 && shared.run.covered == 2034 && shared.run.total == 6150 && shared.status == 'WARN',
        [shared.run, shared.status])
List<String> reportLines = (shared.metrics as List<String>).findAll { it.startsWith('coverage_report,') }
check('Coverage: InfluxDB gets one coverage_report point per row, next to code_coverage of each service',
        reportLines.size() == 8 && (shared.metrics as List<String>).count { it.startsWith('code_coverage,') } == 3
                && reportLines.contains('coverage_report,project=P,env=test,module=gui,report=gui/coverage/gui/lcov.info,tool=lcov,counted=yes line_pct=8.0,covered=320i,missed=3680i,total=4000i 1')
                && reportLines.contains('coverage_report,project=P,env=test,module=gui,report=empty/build/jacoco.xml,tool=jacoco,counted=no covered=0i,missed=0i,total=0i 1')
                && reportLines.contains('coverage_report,project=P,env=test,module=gui,report=build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml,tool=jacoco,counted=yes line_pct=0.0,covered=0i,missed=50i,total=50i 1')
                && (shared.metrics as List<String>).any { it.startsWith('code_coverage,project=P,env=test,module=gui,measured=yes line_pct=30.1,covered=1144i,missed=3906i,total=5050i,required=60.0,met=0i,gap=') },
        shared.metrics)
String sharedCard = (shared.card ?: '') as String
check('Coverage: the card shows the mean of all reports with the services that meet the minimum, a bar per service and a row per report',
        sharedCard.contains('All services') && sharedCard.contains('44.9%') && sharedCard.contains('mean of 5 reports &nbsp;&bull;&nbsp; required: 60% &nbsp;&bull;&nbsp; 1 of 3 services meet it')
                && sharedCard.contains('<th>Report</th>') && sharedCard.count('<tr><td') == 8
                && sharedCard.contains('not counted: a copy of build/reports/jacoco/test/jacocoTestReport.xml')
                && sharedCard.contains('>also counted for backend-api</div>') && sharedCard.contains('>only its 3 classes that no other report holds</div>')
                && sharedCard.contains('>backend-api</div>') && sharedCard.contains('>web</div>') && !sharedCard.contains('not measured'),
        sharedCard)

Map listed = coverageRun([api: [buildTool: 'maven', coverage: [reportPath: ['a/target/site/jacoco/jacoco.xml', ' **/b/*.xml ']]],
                          cli: [buildTool: 'maven', sourceDir: 'cli', coverage: [reportPath: 'x/jacoco.xml\ny/lcov.info, z/lcov.info']]],
        ["--pattern 'a/target"  : "S\t0\nR\tjacoco\ta/target/site/jacoco/jacoco.xml\t10\t20\t\t\nR\tjacoco\tz/b/report.xml\t30\t40\t\t",
         "--pattern 'x/jacoco": "S\t1\nR\tjacoco\tcli/target/site/jacoco/jacoco.xml\t1\t4\t\t"])
check('Coverage: coverage.reportPath, a list or a text of lines or commas with * and **, names the reports; when it names none the folders are searched in the same step',
        listed.errors.isEmpty() && listed.scans == ["python3 -I - --pattern 'a/target/site/jacoco/jacoco.xml' --pattern '**/b/*.xml' --then --root '.' --own 'maven' --exclude 'cli' <<'PYEOF'",
                                                     "python3 -I - --pattern 'x/jacoco.xml' --pattern 'y/lcov.info' --pattern 'z/lcov.info' --then --root 'cli' --own 'maven' --then --root '.' --own 'maven' --others 'maven' <<'PYEOF'"]
                && listed.log.contains('[COVERAGE] Reading the reports named by coverage.reportPath: a/target/site/jacoco/jacoco.xml, **/b/*.xml\n[COVERAGE] JaCoCo a/target/site/jacoco/jacoco.xml: 50.0% (10 of 20 lines)\n[COVERAGE] JaCoCo z/b/report.xml: 75.0% (30 of 40 lines)')
                && listed.log.contains('[COVERAGE] Reading the reports named by coverage.reportPath: x/jacoco.xml, y/lcov.info, z/lcov.info\n[COVERAGE] coverage.reportPath names no report; searching cli for JaCoCo and LCOV reports\n[COVERAGE] JaCoCo cli/target/site/jacoco/jacoco.xml: 25.0% (1 of 4 lines)')
                && (((listed.projects ?: [:]) as Map).api as Map)?.line == 62.5d,
        listed.errors + listed.scans + listed.log.findAll { it.startsWith('[COVERAGE]') })

Map none = coverageRun([svc: [buildTool: 'gradle', sourceDir: 'svc']], [:])
Map empty = coverageRun([svc: [buildTool: 'gradle']], ['--root': "S\t0\nR\tjacoco\tbuild/a.xml\t0\t0\tempty\t\nR\tlcov\tweb/lcov.info\t0\t0\tunreadable\t"])
check('Coverage: no report, or only reports without lines, turn the stage orange with the reason and keep the rows',
        none.errors.isEmpty() && none.status == 'WARN' && none.card == ''
                && none.reason.startsWith('No JaCoCo or LCOV coverage report found in the workspace, set coverage.reportPath for the service in the DevSecOps portal to point at it - the required 60% line coverage cannot be verified.')
                && empty.status == 'WARN' && empty.reason.startsWith('No coverage report with measured lines (build/a.xml, web/lcov.info) - the required 60% line coverage cannot be verified.')
                && empty.log.contains('[COVERAGE] Searching the workspace for JaCoCo and LCOV reports\n[COVERAGE] JaCoCo build/a.xml: no lines, not counted\n[COVERAGE] LCOV web/lcov.info: could not be read, not counted')
                && (empty.card as String).contains('n/a') && (empty.card as String).contains('not counted: could not be read'),
        none.errors + [none.reason, empty.reason, empty.card])

Map legacyFiles = ['build/reports/jacoco/test/jacocoTestReport.xml': '<report/>', 'total_lcov.info': 'SF:a\nDA:1,1\n']
Map broken = coverageRun([api: [buildTool: 'gradle'], app: [buildTool: 'flutter', sourceDir: 'app']], ['--root': "E\tunexpected end of data"], [files: legacyFiles])
Map aborted = coverageRun([app: [buildTool: 'flutter']], [:], [abort: true, files: legacyFiles])
check('Coverage: a scan that fails is named in the log, and the reports are read as before',
        broken.errors.isEmpty() && aborted.errors.isEmpty()
                && broken.log.count { it == '[COVERAGE] The coverage scan failed (unexpected end of data); reading the reports as before' } == 2
                && aborted.log.contains('[COVERAGE] The coverage scan failed (script returned exit code 1); reading the reports as before')
                && rowsOf(broken, 'api') == ['JaCoCo build/reports/jacoco/test/jacocoTestReport.xml 50.0 true ']
                && rowsOf(aborted, 'app') == ['Flutter total_lcov.info 75.0 true '] && aborted.status == 'PASS',
        broken.errors + aborted.errors + broken.log + aborted.log)

Map noPython = coverageRun([api: [buildTool: 'gradle'], app: [buildTool: 'flutter', sourceDir: 'app']], ['--root': 'NO-PYTHON3'], [files: legacyFiles])
check('Coverage: without python3 on the agent the usual report of the build tool is read as before',
        noPython.errors.isEmpty() && noPython.log.count { it == '[COVERAGE] python3 is not on the agent: the reports are looked for as before, and a JaCoCo report needs python3 on a Linux or macOS agent' } == 2
                && rowsOf(noPython, 'api') == ['JaCoCo build/reports/jacoco/test/jacocoTestReport.xml 50.0 true ']
                && rowsOf(noPython, 'app') == ['Flutter total_lcov.info 75.0 true ']
                && noPython.log.contains('Class coverage: \nPackage: com.bbh.x, Class: Legacy, Coverage: 50.0, Missed: 5, Covered: 5\n========================='),
        noPython.errors + noPython.log)

Map windows = coverageRun([svc: [buildTool: 'gradle', coverage: [reportPath: 'mod/build/reports/jacoco/test/*.xml, web/coverage/lcov.info, mod/build/reports/jacoco/test/jacocoTestReport.xml']]], [:],
        [windows: true, files: ['mod/build/reports/jacoco/test/jacocoTestReport.xml': '<report/>', 'web/coverage/lcov.info': 'SF:a\nDA:1,1\n']])
check('Coverage: on Windows the reports named by coverage.reportPath are read with PowerShell as before, each once, and their mean is taken',
        windows.errors.isEmpty() && !windows.calls.any { it.contains(scanLabel) } && windows.calls.count { it.startsWith('powershell ') } == 2
                && rowsOf(windows, 'svc').collect { it.tokenize(' ')[0..1].join(' ') } == ['JaCoCo mod/build/reports/jacoco/test/jacocoTestReport.xml', 'LCOV web/coverage/lcov.info'],
        windows.errors + windows.calls)

Map single = coverageRun([gui: [buildTool: 'gradle']], ['--root': "S\t0\nR\tjacoco\tbuild/reports/jacoco/test/jacocoTestReport.xml\t824\t1000\t\t"])
check('Coverage: a service with one report reads, logs and records what it did before',
        single.errors.isEmpty() && single.log.contains('[PASS] LINE COVERAGE gui: 82.4%  (min required: 60%)') && single.log.contains('covered=824  missed=176  total=1000')
                && single.log.contains('[COVERAGE] Line coverage 82.4% meets the required 60%.') && single.run.line == 82.4d && single.run.tool == 'jacoco'
                && (single.card as String).contains('824 covered / 1000 total') && !(single.card as String).contains('All services'),
        single.errors + single.log)

File tree = File.createTempDir()
String odd = "it's \$HOME dir"
Closure write = { String rel, String text -> File f = new File(tree, rel); f.parentFile.mkdirs(); f.setText(text, 'UTF-8') }
Closure jacoco = { String name, int covered, int missed ->
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\"><report name=\"r\"><package name=\"com/bbh/x\"><class name=\"com/bbh/x/${name}\" sourcefilename=\"${name}.java\"><counter type=\"LINE\" missed=\"${missed}\" covered=\"${covered}\"/></class></package><counter type=\"LINE\" missed=\"${missed}\" covered=\"${covered}\"/></report>".toString()
}
write('build/reports/jacoco/test/jacocoTestReport.xml', jacoco('Cert', 824, 176))
write('target/site/jacoco/jacoco.xml', jacoco('Api', 840, 160))
write('gui/coverage/gui/lcov.info', 'TN:\nSF:src/app/a.ts\nDA:1,1\nDA:2,0\nend_of_record\n')
write('release/gui/coverage/gui/lcov.info', 'TN:\nSF:src/app/a.ts\nDA:1,1\nDA:2,0\nend_of_record\n')
write("${odd}/build/reports/jacoco/test/jacocoTestReport.xml", jacoco('Odd', 1, 3))
Map real = coverageRun([gui: [buildTool: 'gradle', sourceDir: '.'], 'backend-api': [buildTool: 'maven', sourceDir: '.'],
                        odd: [buildTool: 'gradle', tests: [unitTests: [gradle: [dir: odd]]]]], [:], [tree: tree])
tree.deleteDir()
check('Coverage: the real scan step, run by sh on a CertScanner tree with a frontend report and a folder named with a quote, a dollar and a space',
        real.errors.isEmpty() && real.scans[2] == "python3 -I - --root 'it'\"'\"'s \$HOME dir' --own 'gradle' --then --root '.' --own 'gradle' --others 'gradle' --others 'maven' <<'PYEOF'"
                && rowsOf(real, 'gui') == ['JaCoCo build/reports/jacoco/test/jacocoTestReport.xml 82.4 true ',
                                           'LCOV gui/coverage/gui/lcov.info 50.0 true also counted for backend-api',
                                           'LCOV release/gui/coverage/gui/lcov.info 50.0 false a copy of gui/coverage/gui/lcov.info']
                && rowsOf(real, 'backend-api') == ['LCOV gui/coverage/gui/lcov.info 50.0 true also counted for gui',
                                                   'LCOV release/gui/coverage/gui/lcov.info 50.0 false a copy of gui/coverage/gui/lcov.info',
                                                   'JaCoCo target/site/jacoco/jacoco.xml 84.0 true ']
                && rowsOf(real, 'odd') == ["JaCoCo ${odd}/build/reports/jacoco/test/jacocoTestReport.xml 25.0 true ".toString()]
                && real.log.contains('Class coverage: \nPackage: com.bbh.x, Class: com.bbh.x.Cert, Coverage: 82.4, Missed: 176, Covered: 824\n=========================')
                && real.run.line == 60.4d && real.run.counted == 4,
        real.errors + real.scans + real.log.findAll { it.startsWith('[COVERAGE]') } + [real.run])

println "\n${failures == 0 ? 'ALL COVERAGE SCENARIOS PASSED' : failures + ' COVERAGE SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
