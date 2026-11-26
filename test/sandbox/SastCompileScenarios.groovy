import devsecops.test.FakeCpsScript
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import devsecops.test.ScenarioRunner

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File oneViewFixture = new File(root, 'test/fixtures/oneview/certscanner-gui-full.json')
boolean withOneView = oneViewFixture.isFile()
SandboxHarness harness = new SandboxHarness(new File(root, 'src'), new File(root, 'test/sandbox/stub'))
Closure utils = { -> harness.type('com.bbh.utils.BuildUtils') }

String MONITOR = 'Monitor source changes (download sources)'
String BUILD = 'Build artifact'
String STATIC = 'Static analysis'
String SAST = 'SAST - Static Application Security Tests - HCL AppScan'
String UNIT = 'Unit tests'
String NIQ = 'Dependencies scan (Nexus IQ)'
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

String irxEnv = 'withEnv [APPSCAN_CMD_RUNTIME=/ws/bin/appscan.sh, APPSCAN_SOURCE_DIR_RUNTIME=/ws, APPSCAN_SOURCE_FOLDERS=, APPSCAN_EXCLUDED_FOLDERS=, APPSCAN_OPTS=' +
        '-DBLUEMIX_SERVER=null -Dhttp.proxyHost=null -Dhttp.proxyPort=null -Dhttp.proxyUser=null -Dhttps.proxyHost=null -Dhttps.proxyPort=null -Dhttps.proxyUser=null ' +
        '-Djava.net.useSystemProxies=false -Dhttp.auth.preference=Basic -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes=]'
Closure view = { String call ->
    if (call == irxEnv) return 'ENV'
    if (call.contains(' prepare -s thorough ')) return call.startsWith('powershell ') ? 'POWERSHELL IRX' : 'IRX'
    String text = call.replaceAll(/\s+/, ' ').trim()
    if (text.startsWith('sh [DB: ')) return text.substring(0, text.indexOf('] ') + 1)
    return text.replace(' #!/usr/bin/env bash set -euo pipefail ', ' ')
}
Closure generateIrx = { String name, Map cfg, boolean unix ->
    FakeScript sc = new FakeScript(unix: unix)
    String ws = unix ? '/ws' : 'C:/ws'
    sc.env.vars.putAll([WORKSPACE: ws, APPSCAN_LOG_DIR: "${ws}/logs".toString(), APPSCAN_HOME_DIR: "${ws}/home".toString(), APPSCAN_BIN_DIR: "${ws}/bin".toString(),
                        APPSCAN_TOOLS_DIR: "${ws}/tools".toString(), APPSCAN_SCAN_NAME: name, APPSCAN_SOURCE_DIR_RESOLVED: ws])
    sc.files['logs/appscan.cmd'] = unix ? '/ws/bin/appscan.sh' : 'C:\\ws\\.appscan-tools\\bin\\appscan.bat'
    sc.files['gradlew'] = '#!/bin/sh'
    sc.shHandler = { Map a -> a.returnStatus ? 0 : '' }
    String error = ''
    try {
        harness.run {
            def state = harness.type('com.bbh.core.PipelineState').newInstance()
            def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
            os.detect()
            def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os)
            def build = harness.type('com.bbh.build.BuildService').newInstance(sc, state, os, policy)
            def appScan = harness.type('com.bbh.scanner.AppScanService').newInstance(sc, state, os, policy, build)
            state.currentProjectName = name
            state.cfg = cfg
            appScan.generateIrx()
        }
    } catch (Throwable t) {
        error = SandboxHarness.rejectionOf(t) ?: (t.message ?: t.toString())
    }
    List<String> calls = sc.calls.findAll { !it.startsWith('sh uname ') }.collect { view(it as String) }
    return [error: error, calls: calls, log: sc.log, irx: sc.calls.find { it.contains(' prepare -s thorough ') }]
}
Closure linux = { String name, Map cfg -> generateIrx(name, cfg, true) }
Closure differences = { List<List> cases ->
    cases.collect { List c ->
        Map r = linux(c[0] as String, c[1] as Map)
        [name: c[0], error: r.error, calls: r.calls, log: r.log, wantedCalls: c[2], wantedLog: c[3]]
    }.findAll { Map d -> d.error != '' || d.calls != d.wantedCalls || d.log != d.wantedLog }
}

Map gradleBuild = [tasks: ['clean', 'build'], flags: ['--offline']]
Map liquibaseStep = [commands: ['validate'], flags: ['--changelog-file=db/changelog.xml']]
String gradleCheck = 'sh [ -f "./gradlew" ]'
String gradleChmod = 'sh chmod +x gradlew'
String buildCompile = "sh [Gradle: clean build] './gradlew' 'clean' 'build' '--offline'"
String buildLine = "Execute Gradle Command: './gradlew' 'clean' 'build' '--offline'"
String liquibaseSast = "sh [Liquibase SAST: validate] 'liquibase' '--changelog-file=db/changelog.xml' 'validate'"
String liquibaseLine = "Execute Liquibase Command: 'liquibase' '--changelog-file=db/changelog.xml' 'validate'"
Closure sourcesLine = { String name, String tool -> "[SAST] ${name}: build tool ${tool} - the scan has no compile step, the IRX is prepared from the sources".toString() }

List<List> androidCases = [
        ['android-asoc', [buildTool: 'android', build: [gradle: gradleBuild], asoc: [gradle: [tasks: ['compileReleaseSources']]]],
         ['ENV', gradleCheck, gradleChmod, "sh [Gradle: compileReleaseSources] './gradlew' 'compileReleaseSources'", 'IRX'],
         ['Detected tool from config file is android', "Execute Gradle Command: './gradlew' 'compileReleaseSources'"]],
        ['android-build', [buildTool: 'android', build: [gradle: gradleBuild]],
         ['ENV', gradleCheck, gradleChmod, buildCompile, 'IRX'],
         ['Detected tool from config file is android', buildLine]],
        ['android-liquibase', [buildTool: 'android', build: [gradle: gradleBuild, liquibase: liquibaseStep]],
         ['ENV', gradleCheck, gradleChmod, buildCompile, liquibaseSast, 'IRX'],
         ['Detected tool from config file is android', buildLine, liquibaseLine]],
        ['android-asoc-liquibase', [buildTool: 'android', build: [gradle: gradleBuild, liquibase: liquibaseStep], asoc: [liquibase: [commands: ['status']]]],
         ['ENV', gradleCheck, gradleChmod, buildCompile, "sh [Liquibase SAST: status] 'liquibase' 'status'", 'IRX'],
         ['Detected tool from config file is android', buildLine, "Execute Liquibase Command: 'liquibase' 'status'"]]
]
List androidWrong = differences(androidCases)
check('AppScanService.generateIrx on a Linux agent: an Android service compiles as Gradle, as its build does, with asoc.gradle when the service sets it and build.gradle otherwise, and then runs the Liquibase SAST step of asoc.liquibase, else build.liquibase, as after a Gradle compile',
        androidWrong.isEmpty(), androidWrong)

Map strayBlocks = [build: [gradle: gradleBuild, liquibase: liquibaseStep], asoc: [gradle: [tasks: ['classes']], maven: [goals: ['compile']], liquibase: [commands: ['status']]]]
List<List> sourcesCases = [['npm', 'npm', 'npm'], ['pip', 'pip', 'pip'], ['ios', 'ios', 'ios'], ['flutter', 'flutter', 'flutter'],
                           ['angular', 'angular', 'npm'], ['python', 'python', 'pip'], ['xcode', 'xcode', 'ios'], ['dart', 'dart', 'flutter']].collect { List t ->
    String name = "${t[0]}-svc".toString()
    [name, [buildTool: t[1]] + strayBlocks, ['ENV', 'IRX'], ["Detected tool from config file is ${t[1]}".toString(), sourcesLine(name, t[2] as String)]]
}
List sourcesWrong = differences(sourcesCases)
check('AppScanService.generateIrx on a Linux agent: an npm, pip, iOS or Flutter service, also named by an alias such as angular, python, xcode or dart, runs no Gradle, Maven or Liquibase step before the IRX, even with asoc.gradle, asoc.maven, asoc.liquibase or build.liquibase, and says in one [SAST] line that the IRX is prepared from the sources',
        sourcesWrong.isEmpty(), sourcesWrong)

List<List> offCases = ['android', 'npm', 'pip', 'ios', 'flutter'].collect { String tool ->
    ["${tool}-off".toString(), [buildTool: tool, build: [gradle: gradleBuild, liquibase: liquibaseStep], asoc: [doCompile: false]], ['ENV', 'IRX'], []]
} + [['android-off-text', [buildTool: 'android', build: [gradle: gradleBuild], asoc: [doCompile: 'false']], ['ENV', 'IRX'], []]]
List offWrong = differences(offCases)
check('AppScanService.generateIrx on a Linux agent: with asoc.doCompile false, also as text, an Android, npm, pip, iOS or Flutter service prepares the IRX without a compile, without a Liquibase step and without a new line, as before',
        offWrong.isEmpty(), offWrong)

List<List> unchangedCases = [
        ['gradle-svc', [buildTool: 'gradle', build: [gradle: gradleBuild, liquibase: liquibaseStep], asoc: [gradle: [tasks: ['classes']]]],
         ['ENV', gradleCheck, gradleChmod, "sh [Gradle: classes] './gradlew' 'classes'", liquibaseSast, 'IRX'],
         ['Detected tool from config file is gradle', "Execute Gradle Command: './gradlew' 'classes'", liquibaseLine]],
        ['gradle-build', [buildTool: 'gradle', build: [gradle: gradleBuild]],
         ['ENV', gradleCheck, gradleChmod, buildCompile, 'IRX'],
         ['Detected tool from config file is gradle', buildLine]],
        ['maven-svc', [buildTool: 'maven', build: [maven: [goals: ['clean', 'verify'], flags: ['-B']], liquibase: liquibaseStep], asoc: [liquibase: [commands: ['status']]]],
         ['ENV', 'sh mvn -v', 'sh mvn -version', "sh [Maven: clean verify] 'mvn' 'clean' 'verify' '-B'", "sh [Liquibase SAST: status] 'liquibase' 'status'", 'IRX'],
         ['Detected tool from config file is maven', 'Maven is installed on this agent', "Execute Maven Command: 'mvn' 'clean' 'verify' '-B'", "Execute Liquibase Command: 'liquibase' 'status'"]],
        ['liquibase-svc', [buildTool: 'liquibase', build: [liquibase: liquibaseStep]],
         ['ENV', liquibaseSast, 'IRX'],
         ['Detected tool from config file is liquibase', liquibaseLine]],
        ['sql-svc', [buildTool: 'sql', asoc: [liquibase: [commands: ['validate']]]],
         ['sh [DB: list the files AppScan does not scan for sql-svc]', 'ENV', "sh [Liquibase SAST: validate] 'liquibase' 'validate'", 'IRX'],
         ['[SAST] sql-svc: SQL and PL/SQL sources - the scan has no compile step', 'Detected tool from config file is sql', "Execute Liquibase Command: 'liquibase' 'validate'"]],
        ['dotnet-svc', [buildTool: 'dotnet', build: [dotnet: [solution: 'App.sln', configuration: 'Release']]],
         ['ENV', "sh [dotnet restore] 'dotnet' 'restore' 'App.sln'", "sh [dotnet build] 'dotnet' 'build' 'App.sln' '--configuration' 'Release' '--no-restore'", 'IRX'],
         ['Detected tool from config file is dotnet', "Execute .NET Command: 'dotnet' 'restore' 'App.sln'", "Execute .NET Command: 'dotnet' 'build' 'App.sln' '--configuration' 'Release' '--no-restore'"]],
        ['gradle-off', [buildTool: 'gradle', build: [gradle: gradleBuild, liquibase: liquibaseStep], asoc: [doCompile: false]], ['ENV', 'IRX'], []]
]
List unchangedWrong = differences(unchangedCases)
check('AppScanService.generateIrx on a Linux agent: Gradle, Maven, Liquibase, SQL and .NET services run the same steps with the same lines as before, with their asoc and build blocks and with asoc.doCompile false',
        unchangedWrong.isEmpty(), unchangedWrong)

List irxWrong = ['android', 'npm', 'pip', 'ios', 'flutter'].findAll { String tool ->
    Map cfg = [buildTool: tool, build: [gradle: gradleBuild]]
    String compiled = linux("${tool}-irx".toString(), cfg).irx as String
    String plain = linux("${tool}-irx".toString(), cfg + [asoc: [doCompile: false]]).irx as String
    !compiled || compiled != plain
}
check('AppScanService.generateIrx on a Linux agent: the IRX script of an Android, npm, pip, iOS or Flutter service is the script of the same service with asoc.doCompile false',
        irxWrong.isEmpty(), irxWrong)

List windowsWrong = ['android', 'npm', 'pip', 'ios', 'flutter'].collect { String tool ->
    Map r = generateIrx("${tool}-win".toString(), [buildTool: tool, build: [gradle: gradleBuild, liquibase: liquibaseStep]], false)
    [tool: tool, error: r.error, calls: (r.calls as List).findAll { !(it as String).startsWith('withEnv ') }, log: r.log]
}.findAll { Map d -> d.error != '' || d.calls != ['POWERSHELL IRX'] || d.log != ["Detected tool from config file is ${d.tool}".toString()] }
check('AppScanService.generateIrx on a Windows agent: an Android, npm, pip, iOS or Flutter service still runs no compile before the IRX, as before: only .NET compiles there',
        windowsWrong.isEmpty(), windowsWrong)

Object compiles
try {
    compiles = harness.run {
        ['gradle', 'gradlew', 'android', 'maven', 'mvn', 'dotnet', 'msbuild', 'liquibase', 'sql', 'auto', '', null, 'something else',
         'npm', 'node', 'angular', 'yarn', ' NPM ', 'pip', 'python', 'poetry', 'ios', 'xcode', 'swift', 'flutter', 'dart'].collectEntries { def t ->
            [(String.valueOf(t)): utils().sastCompiles(t as String)]
        }
    }
} catch (Throwable t) {
    compiles = t.toString()
}
List compileTools = compiles instanceof Map ? (compiles as Map).findAll { k, v -> v }.collect { it.key } : []
check('BuildUtils.sastCompiles: npm, pip, iOS and Flutter and their aliases have no SAST compile; every other build tool, a missing one and one the library does not know keep the compile path of before',
        compileTools == ['gradle', 'gradlew', 'android', 'maven', 'mvn', 'dotnet', 'msbuild', 'liquibase', 'sql', 'auto', '', 'null', 'something else'],
        compiles)

Map nodeOn = [build: [node: [enabled: true]]]
List<List> nodeCases = [
        ['android, SAST scanning pipeline', nodeOn + [buildTool: 'android'], 'sast', false, true],
        ['gradle, SAST scanning pipeline', nodeOn + [buildTool: 'gradle'], 'sast', false, true],
        ['maven, SAST scanning pipeline', nodeOn + [buildTool: 'maven'], 'sast', false, true],
        ['no build tool, SAST scanning pipeline', nodeOn, 'sast', false, true],
        ['npm, SAST scanning pipeline', nodeOn + [buildTool: 'npm'], 'sast', false, true],
        ['angular, SAST scanning pipeline', nodeOn + [buildTool: 'angular'], 'sast', false, true],
        ['pip, SAST scanning pipeline', nodeOn + [buildTool: 'pip'], 'sast', false, true],
        ['ios, SAST scanning pipeline', nodeOn + [buildTool: 'ios'], 'sast', false, true],
        ['flutter, SAST scanning pipeline', nodeOn + [buildTool: 'flutter'], 'sast', false, true],
        ['npm, full pipeline', nodeOn + [buildTool: 'npm'], 'full', false, true],
        ['pip, security pipeline on Windows', nodeOn + [buildTool: 'pip'], 'security', true, true],
        ['android, SAST scanning pipeline on Windows', nodeOn + [buildTool: 'android'], 'sast', true, false],
        ['npm, SAST scanning pipeline on Windows', nodeOn + [buildTool: 'npm'], 'sast', true, false],
        ['android, SAST scanning pipeline, asoc.doCompile false', nodeOn + [buildTool: 'android', asoc: [doCompile: false]], 'sast', false, false],
        ['npm, SAST scanning pipeline, asoc.doCompile false', nodeOn + [buildTool: 'npm', asoc: [doCompile: false]], 'sast', false, false],
        ['android without build.node.enabled', [buildTool: 'android'], 'sast', false, false]
]
List nodeWrong = nodeCases.findAll { List c -> harness.run { utils().nodeBuildNeeded(c[1] as Map, c[2] as String, c[3] as boolean) } != c[4] }.collect { it[0] }
check('BuildUtils.nodeBuildNeeded, as before: in the SAST scanning pipeline the Node.js build of every build tool, npm, pip, iOS and Flutter included, runs while asoc.doCompile is not false on a Linux or macOS agent, and the other pipelines are unchanged',
        nodeWrong.isEmpty(), nodeWrong)

Map buildGradle = [tasks: ['clean', 'build']]
Map strayAsoc = [gradle: [tasks: ['compileJava']], maven: [goals: ['compile']], liquibase: [commands: ['status']], dotnet: [configuration: 'Debug']]
List<List> overrideCases = [
        ['an Android service with other Gradle tasks', [app: [buildTool: 'android', build: [gradle: buildGradle], asoc: [gradle: [tasks: ['compileReleaseSources']]]]], ['app']],
        ['an Android service with another Liquibase step', [app: [buildTool: 'android', build: [gradle: buildGradle, liquibase: [commands: ['validate']]], asoc: [liquibase: [commands: ['status']]]]], ['app']],
        ['an Android service whose asoc.gradle equals build.gradle', [app: [buildTool: 'android', build: [gradle: buildGradle], asoc: [gradle: [tasks: ['clean', 'build']]]]], []],
        ['npm, pip, iOS and Flutter services with other asoc blocks', [web: [buildTool: 'npm', build: [gradle: buildGradle], asoc: strayAsoc],
                                                                       py : [buildTool: 'pip', asoc: strayAsoc], ios: [buildTool: 'ios', asoc: strayAsoc],
                                                                       app: [buildTool: 'flutter', asoc: strayAsoc], ui: [buildTool: 'angular', asoc: strayAsoc]], ['web', 'py', 'ios', 'app', 'ui']],
        ['a Gradle service after an npm service, both with other Gradle tasks', [web: [buildTool: 'npm', asoc: strayAsoc], api: [buildTool: 'gradle', build: [gradle: buildGradle], asoc: strayAsoc]], ['web', 'api']],
        ['a service without a build tool keeps the earlier answer', [svc: [build: [gradle: buildGradle], asoc: [gradle: [tasks: ['compileJava']]]]], ['svc']]
]
List overrideWrong = overrideCases.collect { List c -> [c[0], harness.run { utils().sastCompileOverrides(c[1] as Map) }, c[2]] }.findAll { it[1] != it[2] }
check('BuildUtils.sastCompileOverrides, as before: a service is named when an asoc block differs from its build block, whatever its build tool, so an Android service like a Gradle one and an npm, pip, iOS or Flutter service too, and a service whose asoc.gradle equals build.gradle is not named',
        overrideWrong.isEmpty(), overrideWrong)

Closure issuesHtml = { List counts ->
    StringBuilder sb = new StringBuilder('<html><body>')
    int id = 1
    ['Critical', 'High', 'Medium', 'Low'].eachWithIndex { String severity, int i ->
        (counts[i] as int).times { sb.append("<div>Issue ID: ${id++}</div><div>Severity: ${severity}</div><div>Status: Open</div>") }
    }
    sb.append('</body></html>').toString()
}
Closure dataFor = { boolean fail ->
    ['gui'        : [coverage: [82.4, 824, 176], niq: [0, 0, 0], sast: [0, 0, 0, 5], sonar: [0, 0, 0, 1, 'OK'], dast: [0, 0, 0, 2]],
     'backend-api': [coverage: [84.0, 840, 160], niq: [0, 0, 0], sast: [0, 0, 0, 2], sonar: [0, 0, 0, 0, 'OK'], dast: [0, 0, 0, 1]]]
}
List<String> secretResources = ['a2a-certs/a2a.sh', 'a2a-certs/get-a2a-password.sh', 'a2a-certs/loginfile.sh',
                                 'a2a-certs/PROXY_ASOCJenk.cert.pem', 'a2a-certs/PROXY_ASOCJenk.key.pem', 'a2a-certs/PROXY_ASOCJenk.keypsw',
                                 'a2a-certs/PROXY_ASOCJenk.exppsw', 'a2a-certs/PROXY_ASOCJenk.p12.b64', 'a2a-certs/PROXY_ASOCJenk.thumb',
                                 'ps-modules/safeguard-ps.zip.b64']
Map context = [root           : root, defaultsText: new File(root, 'test/fixtures/defaults.yaml').text,
               configText     : new File(root, 'test/fixtures/CertScanner/config.yaml').text, securityJob: 'DevSecOps/CertScanner-security-pipeline',
               dataFor        : dataFor, issuesHtml: issuesHtml, secretResources: secretResources, archive: { Map projects, Map s -> },
               platform       : withOneView ? (new groovy.json.JsonSlurperClassic().parseText(oneViewFixture.text) as Map).platform as Map : [:],
               variants       : [devSecOpsSASTScanningPipeline: 'sast', devSecOpsSecurityPipeline: 'security']]
Closure pipeline = { Map spec ->
    Map full = [var: 'devSecOpsSASTScanningPipeline', job: 'CertScanner-sast-pipeline', buildNumber: 700, keyless: true, files: ['devsecops_config.yaml': null], params: [:]] + spec
    Map r = ScenarioRunner.runPipeline(full, context)
    FakeScript j = r.j as FakeScript
    r.log = j.log
    r.calls = j.calls
    r.executed = (r.var as FakeCpsScript).executedStages
    r.failed = (r.var as FakeCpsScript).stageFailures
    r.sources = j.log.findAll { (it as String).startsWith('[SAST] ') && (it as String).contains(' - the scan has no compile step, the IRX is prepared from the sources') }
    r.node = j.log.findAll { (it as String).startsWith('[NODE] ') }
    r.errors = j.log.findAll { (it as String).startsWith('[error] ') }
    return r
}
Closure count = { Map r, String prefix -> (r.calls as List<String>).count { it.startsWith(prefix) } }
Closure summary = { Map r -> [crash: r.crash, failed: r.failed, executed: r.executed, sources: r.sources, node: r.node, errors: r.errors] }
Closure service = { Map projects, String name, Map settings ->
    Map cfg = projects[name] as Map
    settings.each { k, v -> cfg[k] = v }
    cfg
}

Map androidNpm = pipeline([buildNumber: 701, configure: { Map p ->
    service(p, 'gui', [buildTool: 'android'])
    service(p, 'backend-api', [buildTool: 'npm'])
}])
check('SAST scanning pipeline on a Linux agent, an Android and an npm service with the default compile: the Android service compiles with build.gradle in Build artifact and before its IRX, the npm service builds with npm in Build artifact and its IRX is prepared from the sources, and both scans are queued',
        androidNpm.crash == '' && androidNpm.failed == [] && androidNpm.executed == [MONITOR, BUILD, SAST]
                && androidNpm.sources == [sourcesLine('backend-api', 'npm')]
                && count(androidNpm, 'sh [Gradle: clean build bootJar]') == 2 && count(androidNpm, 'sh [Node.js: npm ci]') == 1
                && count(androidNpm, 'sh [Maven: ') == 0 && (androidNpm.sastQueued as List).size() == 2,
        summary(androidNpm))

Map pipIos = pipeline([buildNumber: 702, configure: { Map p ->
    service(p, 'gui', [buildTool: 'pip', build: [pip: [commands: ['pip install -r requirements.txt']]]])
    service(p, 'backend-api', [buildTool: 'ios', build: [ios: [commands: ['xcodebuild -scheme CertScanner build']]]])
}])
check('SAST scanning pipeline on a Linux agent, a pip and an iOS service with the default compile: Build artifact runs their commands, neither runs a Gradle or Maven step, and the IRX of each is prepared from the sources and queued',
        pipIos.crash == '' && pipIos.failed == [] && pipIos.executed == [MONITOR, BUILD, SAST]
                && pipIos.sources == [sourcesLine('gui', 'pip'), sourcesLine('backend-api', 'ios')]
                && count(pipIos, 'sh [Python (pip): pip install -r requirements.txt]') == 1 && count(pipIos, 'sh [iOS: xcodebuild -scheme CertScanner build]') == 1
                && count(pipIos, 'sh [Gradle: ') == 0 && count(pipIos, 'sh [Maven: ') == 0 && (pipIos.sastQueued as List).size() == 2,
        summary(pipIos))

Map flutterAndroid = pipeline([buildNumber: 703, configure: { Map p ->
    Map gui = service(p, 'gui', [buildTool: 'flutter'])
    (gui.build as Map).credentialsId = ['flutter-certificate', 'misnap-prod-license', 'misnap-test-license']
    (gui.tools as Map).flutter = [flutterModules: []]
    service(p, 'backend-api', [buildTool: 'android', build: [gradle: [tasks: ['assembleRelease']], liquibase: liquibaseStep]])
}])
check('SAST scanning pipeline on a Linux agent, a Flutter service and an Android service with build.liquibase: the Flutter IRX is prepared from the sources after its Flutter build, and the Android service compiles with Gradle and runs the Liquibase SAST step before its IRX',
        flutterAndroid.crash == '' && flutterAndroid.failed == [] && flutterAndroid.executed == [MONITOR, BUILD, SAST]
                && flutterAndroid.sources == [sourcesLine('gui', 'flutter')]
                && count(flutterAndroid, 'sh flutter build apk ') == 1 && count(flutterAndroid, 'sh [Gradle: assembleRelease]') == 2
                && count(flutterAndroid, 'sh [Liquibase: validate]') == 1 && count(flutterAndroid, 'sh [Liquibase SAST: validate]') == 1
                && (flutterAndroid.calls as List<String>).findIndexOf { it.startsWith('sh [Liquibase SAST: validate]') } > (flutterAndroid.calls as List<String>).findLastIndexOf { it.startsWith('sh [Gradle: assembleRelease]') }
                && (flutterAndroid.sastQueued as List).size() == 2,
        summary(flutterAndroid))

Map nodeBuilds = pipeline([buildNumber: 704, configure: { Map p ->
    service(p, 'gui', [buildTool: 'android', build: [gradle: [tasks: ['assembleRelease']], node: [enabled: true, dir: 'frontend']]])
    service(p, 'backend-api', [buildTool: 'npm', build: [node: [enabled: true]]])
}])
List<String> nodeBuildCalls = nodeBuilds.calls as List<String>
check('SAST scanning pipeline on a Linux agent, Node.js builds: an Android service runs its Node.js build before its Gradle compile, and an npm service runs its Node.js build, as before, and then npm as its own build, while its IRX is prepared from the sources',
        nodeBuilds.crash == '' && nodeBuilds.failed == [] && nodeBuilds.executed == [MONITOR, BUILD, SAST]
                && nodeBuilds.node == ['[NODE] gui: Node.js from the agent PATH in frontend: npm ci && npm run build', '[NODE] backend-api: Node.js from the agent PATH in the workspace: npm ci && npm run build',
                                       '[NODE] backend-api: Node.js from the agent PATH in the workspace: npm ci && npm run build']
                && count(nodeBuilds, 'sh [Node.js: npm ci]') == 3
                && nodeBuildCalls.findIndexOf { it.startsWith('sh [Node.js: npm run build]') } < nodeBuildCalls.findIndexOf { it.startsWith('sh [Gradle: assembleRelease]') }
                && nodeBuilds.sources == [sourcesLine('backend-api', 'npm')] && (nodeBuilds.sastQueued as List).size() == 2,
        summary(nodeBuilds))

Map unticked = pipeline([buildNumber: 705, configure: { Map p ->
    Map gui = service(p, 'gui', [buildTool: 'android', build: [gradle: [tasks: ['assembleRelease']], node: [enabled: true]]])
    (gui.asoc as Map).doCompile = false
    Map api = service(p, 'backend-api', [buildTool: 'npm', build: [node: [enabled: true]]])
    (api.asoc as Map).doCompile = false
}])
check('SAST scanning pipeline on a Linux agent, an Android and an npm service with asoc.doCompile false: as before, neither builds Node.js for the scan, the npm service runs npm once, as its own build, the Android service compiles only in Build artifact and no IRX line is added',
        unticked.crash == '' && unticked.failed == [] && unticked.executed == [MONITOR, BUILD, SAST]
                && unticked.node == ['[NODE] gui: the SAST scan does not compile this service - no Node.js build', '[NODE] backend-api: the SAST scan does not compile this service - no Node.js build',
                                     '[NODE] backend-api: Node.js from the agent PATH in the workspace: npm ci && npm run build']
                && count(unticked, 'sh [Node.js: npm ci]') == 1
                && unticked.sources == [] && count(unticked, 'sh [Gradle: assembleRelease]') == 1 && (unticked.sastQueued as List).size() == 2,
        summary(unticked))

Map pipNpm = pipeline([buildNumber: 708, configure: { Map p ->
    service(p, 'gui', [buildTool: 'pip', build: [pip: [commands: ['pip install -r requirements.txt']], node: [enabled: true]]])
    service(p, 'backend-api', [buildTool: 'npm', build: [node: [enabled: true]]])
}])
List<String> irxExclusions = (pipNpm.calls as List<String>).findAll { it.startsWith('withEnv [APPSCAN_CMD_RUNTIME=') }.collect { String c ->
    java.util.regex.Matcher m = c =~ /APPSCAN_EXCLUDED_FOLDERS=([^,\]]*)/
    m.find() ? m.group(1) : '<none>'
}
check('SAST scanning pipeline on a Linux agent, a pip and an npm service with build.node.enabled and the default compile: both run their Node.js build, as before, the npm service then runs npm as its own build, the IRX of each is prepared from the sources, and every IRX archive of the run leaves out node_modules',
        pipNpm.crash == '' && pipNpm.failed == [] && pipNpm.executed == [MONITOR, BUILD, SAST]
                && pipNpm.node == ['[NODE] gui: Node.js from the agent PATH in the workspace: npm ci && npm run build', '[NODE] backend-api: Node.js from the agent PATH in the workspace: npm ci && npm run build',
                                   '[NODE] backend-api: Node.js from the agent PATH in the workspace: npm ci && npm run build']
                && count(pipNpm, 'sh [Node.js: npm ci]') == 3 && count(pipNpm, 'sh [Python (pip): pip install -r requirements.txt]') == 1
                && pipNpm.sources == [sourcesLine('gui', 'pip'), sourcesLine('backend-api', 'npm')]
                && irxExclusions == ['node_modules', 'node_modules'] && (pipNpm.sastQueued as List).size() == 2,
        summary(pipNpm) + [irxExclusions: irxExclusions])

String sastCopy = '/ws@tmp/dso-sast-workspace'
Map parallelRun = pipeline([var: 'devSecOpsSecurityPipeline', job: 'CertScanner-security-pipeline', buildNumber: 706, parallel: true, params: [RUN_EXTENDED_PIPELINE: false],
                            configure: { Map p ->
                                service(p, 'gui', [buildTool: 'android'])
                                service(p, 'backend-api', [buildTool: 'npm'])
                            }])
List<String> parallelCalls = parallelRun.calls as List<String>
int copyAt = parallelCalls.indexOf("withEnv [WORKSPACE=${sastCopy}]".toString())
int removedAt = parallelCalls.findIndexOf { it.startsWith('sh [Remove the SAST copy of the workspace]') }
int compileAt = parallelCalls.findLastIndexOf { it.startsWith('sh [Gradle: clean build bootJar]') }
check('security pipeline on a Linux agent, the static analysis at the same time: the Android service compiles with Gradle in the SAST copy of the workspace, the IRX of the npm service is prepared from the sources there, and SAST passes',
        parallelRun.crash == '' && !(parallelRun.failed as List<String>).any { it.startsWith(SAST) || it.startsWith(STATIC) }
                && (parallelRun.executed as List).contains(STATIC) && copyAt >= 0 && copyAt < compileAt && compileAt < removedAt
                && parallelRun.sources == [sourcesLine('backend-api', 'npm')] && (parallelRun.sastQueued as List).size() == 2
                && !(parallelRun.errors as List<String>).any { it.contains('Build Tool is not specified') },
        summary(parallelRun) + [copyAt: copyAt, compileAt: compileAt, removedAt: removedAt])

Map noCopy = pipeline([var: 'devSecOpsSecurityPipeline', job: 'CertScanner-security-pipeline', buildNumber: 707, parallel: true, breakSastCopy: true, params: [RUN_EXTENDED_PIPELINE: false],
                       configure: { Map p ->
                           service(p, 'gui', [buildTool: 'android'])
                           Map api = service(p, 'backend-api', [buildTool: 'npm'])
                           (api.asoc as Map).gradle = [tasks: ['compileJava']]
                       }])
List<String> noCopyStatic = (noCopy.log as List<String>).findAll { it.startsWith('[STATIC] ') }
List<Integer> noCopyOrder = [UNIT, NIQ, SAST, SONAR].collect { String stage -> (noCopy.log as List<String>).indexOf("[STAGE]  ${stage}".toString()) }
int noCopySastAt = (noCopy.log as List<String>).indexOf("[STAGE]  ${SAST}".toString())
String noCopySastResult = noCopySastAt < 0 ? '' : (noCopy.log as List<String>)[noCopySastAt + 1]
check('security pipeline on a Linux agent, the workspace cannot be copied for SAST: an npm service with an asoc.gradle block is named as a service whose SAST compile differs from its build, as before, so Unit tests, Nexus IQ, SAST and SonarQube run one after another; its SAST stage runs no Gradle step, prepares the IRX from the sources and passes',
        noCopy.crash == '' && !(noCopy.failed as List<String>).any { it.startsWith(SAST) || it.startsWith(STATIC) }
                && noCopyStatic.contains('[STATIC] backend-api: the SAST compile runs other Gradle tasks or Maven goals than the build command in the workspace, so it may not rebuild the files Nexus IQ scans: Unit tests, Nexus IQ, SAST and SonarQube run one after another')
                && !noCopyStatic.any { it.startsWith('[STATIC] SAST queues the scan of every service first') }
                && noCopyOrder[0] >= 0 && noCopyOrder == noCopyOrder.sort(false) && noCopySastResult == '[RESULT] PASS'
                && noCopy.sources == [sourcesLine('backend-api', 'npm')] && count(noCopy, 'sh [Gradle: compileJava]') == 0,
        summary(noCopy) + [staticLog: noCopyStatic, order: noCopyOrder, sastResult: noCopySastResult])

if (withOneView) {
    Map keyed = pipeline([buildNumber: 709, keyless: false, configure: { Map p ->
        service(p, 'gui', [buildTool: 'android'])
        service(p, 'backend-api', [buildTool: 'npm'])
    }])
    List<String> keyedCalls = keyed.calls as List<String>
    int readAt = keyedCalls.findIndexOf { it.startsWith('sh [Read the configuration from OneView] ') }
    check('SAST scanning pipeline on a Linux agent, a run with a pipeline key: the Android and the npm service read from OneView compile and prepare their IRX as without a key, and both scans are queued',
            keyed.crash == '' && keyed.failed == [] && keyed.executed == [MONITOR, BUILD, SAST] && readAt >= 0
                    && readAt < keyedCalls.findIndexOf { it.startsWith('sh [Gradle: clean build bootJar]') }
                    && keyed.sources == [sourcesLine('backend-api', 'npm')]
                    && count(keyed, 'sh [Gradle: clean build bootJar]') == 2 && count(keyed, 'sh [Node.js: npm ci]') == 1 && count(keyed, 'sh [Maven: ') == 0
                    && (keyed.sastQueued as List).size() == 2,
            summary(keyed) + [readAt: readAt])
}

println "\n${failures == 0 ? 'ALL SAST COMPILE SCENARIOS PASSED' : failures + ' SAST COMPILE SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
