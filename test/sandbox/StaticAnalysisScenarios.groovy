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

Closure sonarRun = { Map cfg, Map files ->
    FakeScript sc = new FakeScript()
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

def autoScript = new FakeScript()
autoScript.files['pom.xml'] = '<project><properties><java.version>17</java.version></properties><build><plugins><plugin><artifactId>jacoco-maven-plugin</artifactId></plugin></plugins></build></project>'
autoScript.files['frontend/package.json'] = '{"devDependencies": {"cypress": "^13.0.0"}, "engines": {"node": ">=20.0.0"}}'
autoScript.files['frontend/cypress.config.ts'] = 'export default {}'
autoScript.files['Dockerfile'] = 'FROM registry.access.redhat.com/ubi9/ubi-minimal'
def gradleScript = new FakeScript()
gradleScript.files['build.gradle.kts'] = 'plugins { jacoco }'
gradleScript.files['package.json'] = '{"name":"web"}'
def pythonScript = new FakeScript()
pythonScript.files['requirements.txt'] = 'requests==2.31.0'

Map defaults = [buildTool: 'gradle', deployTarget: 'vm', sourceDir: '.', tools: [nexusIq: [maxHigh: 0]]]
Closure merged = { Map own -> Map m = [:]; m.putAll(defaults); m.putAll(own); m }
Map autoResult = [:]
try {
    harness.run {
        def resolver = harness.type('com.bbh.utils.RepoStackAutoResolver').newInstance(autoScript)
        Map ownMaven = [tools: [nexusIq: [application: 'payments-app']]]
        autoResult.resolved = resolver.resolve(defaults, ownMaven, merged(ownMaven) + [tools: [nexusIq: [maxHigh: 0, application: 'payments-app']]], 'payments')
        Map ownOverride = [buildTool: 'gradle', build: [node: [enabled: false]], tools: [sonar: [projectKey: 'team-key']]]
        autoResult.overridden = resolver.resolve(defaults, ownOverride, merged(ownOverride), 'payments')
        Map ownOff = [autoresolveParameters: false]
        autoResult.disabled = resolver.resolve(defaults, ownOff, merged(ownOff), 'payments')
        autoResult.legacyOff = resolver.resolve(defaults, ['autoresolve parameters': false], merged(['autoresolve parameters': false]), 'payments')
        autoResult.gradle = harness.type('com.bbh.utils.RepoStackAutoResolver').newInstance(gradleScript).resolve(defaults, [:], merged([:]), 'web')
        autoResult.python = harness.type('com.bbh.utils.RepoStackAutoResolver').newInstance(pythonScript).resolve(defaults, [:], merged([:]), 'py')
    }
} catch (Throwable t) {
    autoResult.error = SandboxHarness.rejectionOf(t) ?: t.toString()
}
Map r = (autoResult.resolved?.cfg ?: [:]) as Map
check('RepoStackAutoResolver: with autoresolveParameters on by default, pom.xml beats buildTool: gradle of the library defaults, the JDK set-up, the Maven goals, the Node.js build of frontend/, Cypress and the Nexus IQ scan patterns are filled in and logged, the deploy target is only suggested',
        !autoResult.error && r.buildTool == 'maven' && r.buildToolAutoSetup == true
                && r.build?.maven?.goals == ['clean', 'package'] && r.tests?.unitTests?.maven?.goals == ['test', 'jacoco:report']
                && r.build?.node?.enabled == true && r.build?.node?.dir == 'frontend'
                && r.tests?.unitTests?.cypress?.enabled == true && r.tests?.unitTests?.cypress?.dir == 'frontend'
                && r.tools?.nexusIq?.scanPatterns == ['**/target/*.jar', 'frontend/package-lock.json'] && r.tools?.nexusIq?.maxHigh == 0
                && r.tools?.sonar?.projectKey == 'com.bbh:payments' && r.deployTarget == 'vm'
                && autoScript.log.any { it.contains('[AUTO] payments: buildTool = maven (pom.xml found)') }
                && autoScript.log.any { it.contains('Node.js 20 (engines.node)') }
                && autoScript.log.any { it.contains('set deployTarget: openshift') },
        "error=${autoResult.error} resolved=${autoResult.resolved}")
Map o = (autoResult.overridden?.cfg ?: [:]) as Map
check('RepoStackAutoResolver: every value set for the service wins over what the repository shows',
        o.buildTool == 'gradle' && o.build?.node?.enabled == false && o.tools?.sonar?.projectKey == 'team-key'
                && !((autoResult.overridden?.resolved ?: []) as List).any { it.field in ['buildTool', 'build.node.enabled', 'tools.sonar.projectKey'] },
        "overridden=${autoResult.overridden}")
check('RepoStackAutoResolver: autoresolveParameters: false (and the older autoresolve parameters: false) keeps only the service values and the library defaults',
        autoResult.disabled?.cfg?.buildTool == 'gradle' && !autoResult.disabled?.resolved && autoResult.legacyOff?.cfg?.buildTool == 'gradle' && !autoResult.legacyOff?.resolved,
        "disabled=${autoResult.disabled} legacy=${autoResult.legacyOff}")
Map g = (autoResult.gradle?.cfg ?: [:]) as Map
check('RepoStackAutoResolver: a Kotlin DSL Gradle build with JaCoCo and a root package.json gets the Gradle tasks, the JaCoCo report and the Node.js build in the workspace',
        g.buildTool == 'gradle' && g.build?.gradle?.tasks == ['clean', 'assemble'] && g.tests?.unitTests?.gradle?.tasks == ['test', 'jacocoTestReport']
                && g.build?.node?.enabled == true && !g.build?.node?.dir,
        "gradle=${autoResult.gradle}")
check('RepoStackAutoResolver: a Python repository keeps the build tool of the defaults and says how to set it',
        autoResult.python?.cfg?.buildTool == 'gradle' && pythonScript.log.any { it.contains('Python project detected') },
        "python=${autoResult.python}")


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
check('pull request decoration: an AppScan SAST finding marked Noise is not commented',
        !prUnits.error && prUnits.html.size() == 1 && prUnits.html[0].file == 'src/main/java/A.java' && prUnits.html[0].line == 42 && prUnits.html[0].severity == 'HIGH',
        prUnits.html)
check('GoldenFix: goldenFix.versionCatalogRepo names the shared version catalog repository, its branch (main by default) and its files',
        !prUnits.error && prUnits.catalog.url == 'https://bitbucket.bbh.com/scm/plat/catalog.git' && prUnits.catalog.branch == 'main'
                && prUnits.catalog.paths == ['gradle/libs.versions.toml'] && prUnits.catalog.credentialsId == 'bb' && prUnits.noCatalog == [:],
        prUnits)

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
