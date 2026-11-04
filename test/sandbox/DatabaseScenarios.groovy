import devsecops.test.DatabaseResponder
import devsecops.test.FakeCpsScript
import devsecops.test.FakeScript
import devsecops.test.FakeTestSummary
import devsecops.test.SandboxHarness
import devsecops.test.ScenarioRunner
import org.yaml.snakeyaml.Yaml

class OwnedList extends ArrayList<String> {
    FakeScript j
    List<String> owners = []

    boolean add(Object value) {
        owners << ((j.env.CURRENT_PROJECT_NAME ?: '') as String)
        return super.add(value as String)
    }

    List<String> ownedBy(String owner) {
        return (0..<size()).findAll { owners[it] == owner }.collect { get(it) }
    }
}

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File fixtures = new File(root, 'test/fixtures/database')
File goldenDir = new File(root, 'test/sandbox/golden')
String defaultsText = new File(root, 'test/fixtures/defaults.yaml').text
Map platform = (new groovy.json.JsonSlurperClassic().parseText(new File(root, 'test/fixtures/portal/certscanner-gui-full.json').text) as Map).platform as Map
Map variants = [devSecOpsPipeline: 'full', devSecOpsSecurityPipeline: 'security', devSecOpsExtendedPipeline: 'extended', devSecOpsSASTScanningPipeline: 'sast',
                devSecOpsNexusIqGoldenFixPipeline: 'nexusiq']
String securityJob = 'DevSecOps/orders-db-security-pipeline'
String delivered = '213-20261031-094500'
SandboxHarness harness = new SandboxHarness(new File(root, 'src'), new File(root, 'test/sandbox/stub'))
String onlinePrelude = harness.run { harness.type('com.bbh.utils.LiquibaseOutput').onlinePrelude() } as String
String utplsqlPrelude = harness.run { harness.type('com.bbh.utils.LiquibaseOutput').utplsqlScript() } as String

String MONITOR = 'Monitor source changes (download sources)'
String UNIT = 'Unit tests'
String BUILD = 'Build artifact'
String NIQ = 'Dependencies scan (Nexus IQ)'
String SAST = 'SAST - Static Application Security Tests - HCL AppScan'
String SONAR = 'SCA (SonarQube)'
String STATIC = 'Static analysis'
String SNAPSHOT = 'Nexus delivery (Static analysis passed)'
String RD = 'Lower test region deployment'
String REGRESSION = 'Regression tests (>60% user stories coverage)'
String SMOKE = 'Smoke tests'
String PERFORMANCE = 'Performance tests'
String DAST = 'DAST - Dynamic Application Security Tests - HCL AppScan'
String RELEASE = 'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities'
String QC = 'Higher test environment deployment'
List<String> securityStages = [MONITOR, BUILD, UNIT, NIQ, SAST, SONAR, SNAPSHOT]
List<String> extendedStages = [MONITOR, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]
List<String> fullStages = [MONITOR, BUILD, UNIT, NIQ, SAST, SONAR, SNAPSHOT, RD, REGRESSION, SMOKE, PERFORMANCE, DAST, RELEASE, QC]

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

Closure issuesHtml = { List counts ->
    StringBuilder sb = new StringBuilder('<html><body>')
    int id = 1
    ['Critical', 'High', 'Medium', 'Low'].eachWithIndex { String severity, int i ->
        (counts[i] as int).times { sb.append("<div>Issue ID: ${id++}</div><div>Severity: ${severity}</div><div>Status: Open</div>") }
    }
    sb.append('</body></html>').toString()
}
Map dbData = [coverage: [0.0, 0, 0], niq: [0, 0, 0], sast: [0, 0, 0, 1], sonar: [0, 0, 0, 0, 'OK'], dast: [0, 0, 0, 0]]
Closure dataFor = { boolean fail ->
    ['gui'       : dbData, 'orders-db': dbData, 'audit-db': dbData,
     'orders-app': [coverage: [82.4, 824, 176], niq: [0, 0, 0], sast: [0, 0, 0, 2], sonar: [0, 0, 0, 1, 'OK'], dast: [0, 0, 0, 1]],
     'orders-api': [coverage: [84.0, 840, 160], niq: [0, 0, 0], sast: [0, 0, 0, 2], sonar: [0, 0, 0, 0, 'OK'], dast: [0, 0, 0, 1]]]
}
List<String> secretResources = ['a2a-certs/a2a.sh', 'a2a-certs/get-a2a-password.sh', 'a2a-certs/loginfile.sh',
                                 'a2a-certs/PROXY_ASOCJenk.cert.pem', 'a2a-certs/PROXY_ASOCJenk.key.pem', 'a2a-certs/PROXY_ASOCJenk.keypsw']

Closure layoutFiles = { String layout ->
    File dir = new File(fixtures, "layout-${layout}")
    Map<String, String> files = [:]
    dir.eachFileRecurse(groovy.io.FileType.FILES) { File f -> files[dir.toPath().relativize(f.toPath()).toString()] = f.getText('UTF-8') }
    if (layout != 'a') {
        String into = layout == 'b' ? 'orders-db/src/main/resources/db/changelog/' : 'orders-db/'
        File sources = new File(fixtures, 'layout-a/db')
        ['tables', 'code', 'test'].each { String sub -> new File(sources, sub).eachFile { File f -> files["${into}${sub}/${f.name}".toString()] = f.getText('UTF-8') } }
    }
    return files
}
Closure toolDefaults = { Map d ->
    Map tools = d.tools as Map
    tools.liquibase = [home: '/opt/liquibase/4.33.0']
    tools.oracleJdbc = [path: '/opt/oracle/ojdbc11.jar']
    tools.utplsql = [home: '/opt/utPLSQL-cli']
    d.database = [allowedTargets: ['dev-db.bbh.com', 'rd-db.bbh.com', 'qc-db.bbh.com'],
                  package       : [snapshotRepository: [id: 'bbh-db-snapshots', url: 'https://tools.bbh.com/nexus/repository/db-snapshots'],
                                   releaseRepository : [id: 'bbh-db-releases', url: 'https://tools.bbh.com/nexus/repository/db-releases']]]
}

Map buildOutputs = [b: ['app/build/libs': '']]
Closure runDatabase = { Map spec ->
    Map full = [:] + spec
    List<Map> shells = []
    full.defaults = { Map d -> toolDefaults(d); if (spec.defaults) (spec.defaults as Closure).call(d) }
    full.files = (layoutFiles(spec.layout as String) as Map) + (buildOutputs[spec.layout] ?: [:]) + ((spec.files ?: [:]) as Map)
    full.env = [BUILD_TAG: "jenkins-DevSecOps-${spec.job}-${spec.buildNumber}".toString()] + ((spec.env ?: [:]) as Map)
    full.respond = { FakeScript j, Map a ->
        shells << [label: (a.label ?: '') as String, script: (a.script ?: '') as String, cwd: j.cwd, javaHome: j.env.JAVA_HOME, owner: j.env.CURRENT_PROJECT_NAME,
                   sql: j.files[DatabaseResponder.quoted((a.script ?: '') as String, /'--sql-file=([^']+)'/)]]
        DatabaseResponder.sh(j, a, full)
    }
    full.prepare = { FakeScript j ->
        j.calls = new OwnedList(j: j)
        j.log = new OwnedList(j: j)
        ['/opt/liquibase/4.33.0', '/opt/liquibase/5.0.4', '/opt/oracle/ojdbc11.jar', '/opt/utPLSQL-cli'].each { j.files[it] = '' }
        if (spec.junit) {
            List n = spec.junit as List
            j.steps['junit'] = { a -> new FakeTestSummary(totalCount: n[0] as int, failCount: n[1] as int, skipCount: n[2] as int, passCount: (n[0] as int) - (n[1] as int) - (n[2] as int)) }
        }
        if (spec.prepare) (spec.prepare as Closure).call(j)
    }
    Map context = [root           : root, defaultsText: defaultsText, configText: new File(fixtures, "layout-${spec.layout}/devsecops_config.yaml").text,
                   platform       : platform, variants: variants, securityJob: securityJob, dataFor: dataFor, issuesHtml: issuesHtml,
                   secretResources: secretResources, archive: { Map projects, Map s -> if (s.delivery) (projects['orders-db'] as Map).delivery = s.delivery }]
    return ScenarioRunner.runPipeline(full, context) + [shells: shells]
}

List<Map> rules = ScenarioRunner.baseRules() + [ScenarioRunner.rule('the DB work folder is under the agent tmp folder of the workspace', /\/ws@tmp\/dso-db\//, '<tmp>/dso-db/')]
Closure golden = { String slug, String name, Map r ->
    String actual = ScenarioRunner.normalise(ScenarioRunner.goldenView(r), rules)
    File file = new File(goldenDir, "database-${slug}.txt")
    File diffFile = new File(goldenDir, "database-${slug}.actual.txt")
    if (!file.exists() && System.getenv('RECORD_GOLDEN') == '1') {
        file.text = actual
        println "      note: golden output recorded at ${file}"
    }
    if (!file.exists()) {
        check("${name}: a golden output exists", false, "${file} is missing; record it with RECORD_GOLDEN=1 and review it")
        return
    }
    boolean same = file.text == actual
    if (same) diffFile.delete() else diffFile.text = actual
    check("${name}: the run matches the golden output ${file.name}", same, "compare ${file} with ${diffFile}")
}

Closure scriptOf = { String call -> call.substring(call.indexOf('] ') + 2) }
Closure stageResults = { FakeScript j ->
    Map<String, String> out = [:]
    j.log.eachWithIndex { String line, int i ->
        if (line.startsWith('[STAGE]  ') && i + 1 < j.log.size() && j.log[i + 1].startsWith('[RESULT] ')) out[line.substring(9)] = j.log[i + 1].substring(9)
    }
    return out
}
Closure basics = { Map spec, Map r ->
    FakeScript j = r.j as FakeScript
    FakeCpsScript var = r.var as FakeCpsScript
    String name = spec.name as String
    check("${name}: the pipeline var runs to the end under the sandbox", !r.crash, r.crash)
    List failed = var.stageFailures
    check("${name}: ${spec.failedStage ? "only ${spec.failedStage} fails" : 'no stage threw'} and no post block failed",
            (spec.failedStage ? failed.size() == 1 && (failed[0] as String).startsWith(spec.failedStage as String) : failed.isEmpty()) && var.postFailures.isEmpty(),
            (failed + var.postFailures + ['--- last log lines ---'] + j.log.takeRight(8)).join('\n      '))
    check("${name}: no sandbox rejection anywhere in the build log", !j.log.any { it.contains('not permitted') || it.contains('RejectedAccess') },
            j.log.findAll { it.contains('not permitted') || it.contains('RejectedAccess') })
    if (spec.executed != null) {
        check("${name}: the stages run and skipped as the declarative pipeline defines them", var.executedStages == spec.executed && var.skippedStages == spec.skipped,
                "executed ${var.executedStages}, skipped ${var.skippedStages}")
    }
    if (spec.result) check("${name}: build result ${spec.result}", j.currentBuild.currentResult == spec.result, j.currentBuild.currentResult)

    List<String> carriers = j.calls + j.log + (j.files.values() as List<String>) + [ScenarioRunner.influxOf(j)]
    List<String> scripts = j.calls.findAll { it.startsWith('sh [DB: ') }
    List<String> named = scripts.findAll { s -> ['--password', '--username', '--url=jdbc', 'LIQUIBASE_COMMAND_USERNAME', 'LIQUIBASE_COMMAND_PASSWORD'].any { s.contains(it) } }
    boolean portal = j.files.keySet().any { it.startsWith('portal_report_') }
    check("${name}: D-SEC no call, withEnv, log line, file (the report sent to the portal too) or InfluxDB line holds a bound credential, and no DB script names a user, a password or a JDBC URL",
            !carriers.any { it.contains('fake-DSO_DB_') } && !named && (portal || !spec.golden), carriers.findAll { it.contains('fake-DSO_DB_') }.take(3) + named.take(3) + [portal])
    List<String> online = scripts.findAll { it.contains('"--defaults-file=$DSO_STEP"') }
    List<String> tests = scripts.findAll { it.startsWith('sh [DB: utPLSQL ') }
    check("${name}: D-SEC every online Liquibase script starts with the online prelude, and every utPLSQL script with its prefix",
            online.every { scriptOf(it).startsWith(onlinePrelude) } && tests.every { scriptOf(it).startsWith(utplsqlPrelude) },
            (online.findAll { !scriptOf(it).startsWith(onlinePrelude) } + tests.findAll { !scriptOf(it).startsWith(utplsqlPrelude) }).take(2))
    List<String> commands = scripts.collect { it.readLines().find { l -> l.contains("'--show-banner=false'") } }.findAll { it }
    List<String> disordered = commands.findAll { String l ->
        List<String> tokens = l.tokenize(' ')
        int command = (1..<tokens.size()).find { !tokens[it].startsWith("'--") && !tokens[it].startsWith('"--') } ?: 0
        !(command > 2 && tokens[1].contains('--defaults-file=') && tokens[2] == "'--show-banner=false'")
    }
    check("${name}: D-SEC and D-ORDER every Liquibase command line has --defaults-file and --show-banner=false first, and every global option before the command",
            !disordered, disordered.take(2))
    List<String> unguarded = []
    j.calls.eachWithIndex { String call, int i ->
        if (call.startsWith('sh [DB: Liquibase') || call.startsWith('sh [DB: utPLSQL') || call.startsWith('sh [DB: compile') || call.startsWith('sh [DB: invalid')) {
            String env = j.calls.subList(0, i).reverse().find { it.startsWith('withEnv ') }
            if (!env?.contains('LIQUIBASE_ANALYTICS_ENABLED=false')) unguarded << call.readLines()[0]
        }
    }
    check("${name}: D-SEC every Liquibase and utPLSQL step runs inside a withEnv with LIQUIBASE_ANALYTICS_ENABLED=false", !unguarded, unguarded.take(2))
    List<String> quiet = scripts.findAll { it.contains('| dso_mask > ') }
    List<String> echoed = j.log.findAll { l -> ['changesets have not been applied to', ' is up to date', 'Successfully tagged', 'Output of SELECT', 'contains no unexpected changes',
                                                 'unexpected changes were found', 'Database change log locks'].any { l.contains(it) } }
    check("${name}: D-QUIET the status, compile baseline, tag, unexpected-changesets and list-locks steps write only to their out file, and no log line repeats such an output",
            scripts.findAll { it =~ /^sh \[DB: (Liquibase status|invalid objects|Liquibase tag|Liquibase unexpected|Liquibase list-locks)/ }.every { quiet.contains(it) } && !echoed, echoed.take(3))
    List<String> left = j.files.keySet().findAll { it.startsWith('/ws@tmp/dso-db/') }.toList()
    check("${name}: D-CLEAN the DB work folder is removed at the end of every step that wrote into it, also after an error", !left, left.take(5))
}

Closure verify = { Map spec ->
    Map r = runDatabase(spec)
    basics(spec, r)
    if (spec.golden) golden(spec.golden as String, spec.name as String, r)
    if (spec.demo) new File(root, "examples/reports/${spec.demo}.html").text = r.html as String
    if (spec.checks) (spec.checks as Closure).call(r)
    return r
}
Closure logOf = { Map r -> (r.j as FakeScript).log }
Closure callsOf = { Map r -> (r.j as FakeScript).calls }
Closure has = { Map r, String line -> logOf(r).contains(line) }
Closure errorOf = { Map r -> logOf(r).find { it.startsWith('[error] ') } ?: '' }

Closure orders = { Closure change -> { Map p -> change((p['orders-db'] as Map).database as Map, p['orders-db'] as Map) } }
Closure dropTargets = { List targets -> orders { Map db, Map svc -> targets.each { db.remove(it) } } }
Closure sonarKey = { Map p -> (p['orders-db'] as Map).tools = [sonar: [projectKey: 'orders-db', serverUrl: 'https://tools.bbh.com/sonar', installationName: 'SonarQube', authToken: 'sonarqube-token']] }
String changelogFlag = '--changelog-file=db/changelog-master.xml'
Closure ownSteps = { Map svc ->
    svc.build = [liquibase: [commands: ['updateSQL'], flags: [changelogFlag, '--url=offline:oracle']]]
    svc.tests = [unitTests: [liquibase: [commands: ['validate'], flags: [changelogFlag]]]]
}
Closure noBlock = { Map p ->
    (p['orders-db'] as Map).remove('database')
    ownSteps(p['orders-db'] as Map)
    sonarKey(p)
}
Map full = [var: 'devSecOpsPipeline', job: 'orders-db-devsecops-pipeline', buildNumber: 341, params: [DEPLOY_HIGHER_ENV: true]]
Map security = [var: 'devSecOpsSecurityPipeline', job: 'orders-db-security-pipeline', buildNumber: 212, params: [RUN_EXTENDED_PIPELINE: false]]
Map extended = [var: 'devSecOpsExtendedPipeline', job: 'orders-db-extended-pipeline', buildNumber: 118, params: [DEPLOY_HIGHER_ENV: true], delivery: delivered]
Map sast = [var: 'devSecOpsSASTScanningPipeline', job: 'orders-db-sast-pipeline', buildNumber: 87, params: [:]]
Map nexusiq = [var: 'devSecOpsNexusIqGoldenFixPipeline', job: 'orders-db-nexusiq-pipeline', buildNumber: 61, params: [:]]
String pkgError = "PACKAGE BODY PKG_ORDERS line 12: PLS-00201: identifier 'ORDER_ITEMZ' must be declared"
String viewError = 'VIEW V_LEGACY_REPORT line 0: ORA-00942: table or view does not exist'
String oldView = 'VIEW V_LEGACY_REPORT 20250101120000'
String masterXml = new File(fixtures, 'layout-a/db/changelog-master.xml').text
Closure withChangeSets = { String xml -> masterXml.replace('</databaseChangeLog>', xml + '</databaseChangeLog>') }
Map staticTree = [
        'db/changelog-master.xml'    : withChangeSets('''    <changeSet id="pkg_toad.pck" author="orders-team" runOnChange="true">
        <sqlFile path="code/pkg_toad.pck" relativeToChangelogFile="true" endDelimiter="\\n/" stripComments="false"/>
        <rollback/>
    </changeSet>
'''),
        'db/code/pkg_bad.pkb'        : 'SHOW ERRORS\nCREATE OR REPLACE PACKAGE BODY pkg_bad AS\n  PROCEDURE touch IS\n  BEGIN\n    UPDATE orders\n    SET timing = 1\n    WHERE order_id = 0;\n  END touch;\nEND pkg_bad;/\n',
        'db/code/orders_purge.prc'   : 'CREATE OR REPLACE PROCEDURE orders_purge IS\nBEGIN\n  DELETE FROM orders WHERE created_at < SYSDATE - 3650;\nEND orders_purge;\n/\n',
        'db/code/pkg_toad.pck'       : 'CREATE OR REPLACE PACKAGE pkg_toad AS\n  PROCEDURE p;\nEND pkg_toad;\n/\nCREATE OR REPLACE PACKAGE BODY pkg_toad AS\n  PROCEDURE p IS BEGIN NULL; END p;\nEND pkg_toad;\n/\n',
        'db/tables/003-pkg-audit.sql': '--liquibase formatted sql\n\n--changeset orders-team:003-pkg-audit runOnChange:true\nCREATE OR REPLACE PACKAGE pkg_audit AS\n  PROCEDURE log_change(p_text IN VARCHAR2);\nEND pkg_audit;\n/\n'
]
List<String> staticFindings = [
        'db/code/pkg_bad.pkb line 1: SQL*Plus command "SHOW ERRORS" - Liquibase sends it to the database as SQL; remove it',
        'db/code/pkg_bad.pkb line 9: "/" on the line of END; - put the "/" on a line of its own',
        'db/tables/003-pkg-audit.sql line 3: changeset 003-pkg-audit holds a PL/SQL unit but no splitStatements:false - Liquibase splits it at every ";"',
        'db/tables/003-pkg-audit.sql line 3: changeset 003-pkg-audit holds a PL/SQL unit without stripComments:false - its comments and utPLSQL annotations are removed',
        'db/code/orders_purge.prc is in no sqlFile of the changelog - includeAll does not deploy .prc files, so it is never deployed',
        'db/code/pkg_bad.pkb is in no sqlFile of the changelog - includeAll does not deploy .pkb files, so it is never deployed'
]
List<String> fullSteps = ['DB: check the files of orders-db', 'DB: Liquibase validate (offline)', 'DB: read the SQL and PL/SQL sources of orders-db',
                          'DB: Liquibase update-sql of all changesets (offline)', 'DB: Liquibase update-sql for review (offline)', 'DB: stage the package orders-db-341-<stamp>',
                          'DB: remove the package stage', 'DB: Liquibase validate the package (offline)'] +
        ['test', 'rd', 'qc'].collectMany { String t ->
            List<String> deploy = ["DB: Liquibase status on ${t}", "DB: invalid objects before the update on ${t}", "DB: Liquibase update on ${t}", "DB: compile check on ${t}",
                                   "DB: Liquibase status after the update on ${t}"].collect { it.toString() }
            t == 'test' ? deploy + ['DB: Liquibase unexpected-changesets on the test database', 'DB: utPLSQL tests on the test database', 'DB: list the files AppScan does not scan for orders-db',
                                    'DB: publish orders-db-341-<stamp>.zip to Nexus']
                    : deploy + ["DB: Liquibase tag on ${t}".toString()] + (t == 'rd' ? ['DB: publish orders-db-341-<stamp>.zip to Nexus'] : [])
        }
Closure labels = { Map r -> callsOf(r).findAll { it.startsWith('sh [DB: ') }.collect { it.substring(4, it.indexOf(']')).replaceAll(/\d{8}-\d{6}/, '<stamp>') } }
Closure stageOf = { Map r, String stage -> stageResults(r.j as FakeScript)[stage] }
Closure dbCard = { Map r ->
    String html = r.html as String
    int start = html.indexOf('<h2>Database changes</h2>')
    start < 0 ? '' : html.substring(start, html.indexOf('The library never rolls back.', start))
}

Map d3Alone = null
List<Map> goldenSpecs = [
        security + [name: 'D1 security, PL/SQL only, green', layout: 'a', executed: securityStages, skipped: [STATIC], result: 'SUCCESS',
                    golden: 'security-plsql-only-green', configure: dropTargets(['test', 'rd', 'qc']), checks: { Map r ->
            Map results = stageResults(r.j as FakeScript)
            check('D1: the stage results of 10.15 for the security stages, and coverage is not required',
                    results == [(MONITOR): 'PASS', (BUILD): 'PASS', (UNIT): 'PASS', (NIQ): 'NOT_REQUIRED', (SAST): 'PASS', (SONAR): 'NOT_REQUIRED', (SNAPSHOT): 'PASS']
                            && has(r, '[DB] orders-db: no test database (database.test.url) - no unit tests, coverage is not required') && !logOf(r).any { it.startsWith('[COVERAGE]') },
                    results)
            List zips = callsOf(r).findAll { it.startsWith('archiveArtifacts ') && it.contains('.zip') }
            check('D1: no database credentials are bound, the zip is archived once, and the bundled driver of a full 4.33.0 is not named when it is not there',
                    !callsOf(r).any { it.startsWith('withCredentials ') && it.contains('orders-db-') } && zips.size() == 1 && !logOf(r).any { it.contains('bundled Oracle driver') },
                    zips)
            String card = dbCard(r)
            check('D1: the Database changes card has one package row, with the review SQL and utPLSQL not run, and no deployments table',
                    card.count('<tr><td') == 1 && card.contains('>review SQL</a>') && card.contains('>not run<') && !card.contains('<th>Database</th>'), card)
        }],
        full + [name: 'D2 full, PL/SQL only, Liquibase 5.0.4, green', layout: 'a', executed: fullStages, skipped: [STATIC], result: 'SUCCESS',
                golden: 'full-plsql-only-green', demo: 'database-plsql-green', defaults: { Map d -> (d.tools as Map).liquibase = [home: '/opt/liquibase/5.0.4'] },
                configure: { Map p -> (p['orders-db'] as Map).influx = [enabled: true, project: 'orders', env: 'dev', credentialsId: 'orders-influx-token',
                                                                        url: 'https://influx.bbh.com/api/v2/write?org=DevSecOps&bucket=devsecops&precision=s'] }, checks: { Map r ->
            check('D2: every database step of 10.1 and 10.6-10.9 in order, with the compile baseline before every update', labels(r) == fullSteps,
                    [labels(r) - fullSteps, fullSteps - labels(r)])
            List<String> liquibase = callsOf(r).findAll { it.startsWith('sh [DB: Liquibase') || it.startsWith('sh [DB: compile') || it.startsWith('sh [DB: invalid') }
            check('D2: every Liquibase 5.0.4 call passes the Oracle driver with --classpath', liquibase && liquibase.every { it.contains("'--classpath=/opt/oracle/ojdbc11.jar'") },
                    liquibase.findAll { !it.contains('--classpath') }.take(1))
            Map results = stageResults(r.j as FakeScript)
            check('D2: release-gate.json allows the release, the test suites without jobs are not required, and DAST is not required',
                    (r.j as FakeScript).files['release-gate.json'].contains('"allowed":true') && [REGRESSION, SMOKE, PERFORMANCE, DAST].every { results[it] == 'NOT_REQUIRED' }
                            && results.findAll { k, v -> !(k in [NIQ, SONAR, REGRESSION, SMOKE, PERFORMANCE, DAST]) }.values().every { it == 'PASS' }
                            && (r.html as String).count('Test not required') >= 3, results)
            List<String> points = ScenarioRunner.influxOf(r.j as FakeScript).readLines().findAll { it.startsWith('database_deployment,') }
            check('D2: one database_deployment point for each of test, RD and QC, with the changesets applied and the tag of RD and QC',
                    points.collect { it.substring(0, it.indexOf(' ')) } == ['test', 'rd', 'qc'].collect { "database_deployment,project=orders,env=dev,module=orders-db,target=${it},status=PASS".toString() }
                            && points.collect { [it.find(/applied=\d+i/), it.find(/tagged=\di/)] } == [['applied=10i', 'tagged=0i'], ['applied=8i', 'tagged=1i'], ['applied=8i', 'tagged=1i']]
                            && points.every { it.contains(',ok=1i,') && it.contains(',version="341-') && it.contains(',liquibase="5.0.4"') }, points)
            check('D2: the release publishes the zip of the snapshot under the version of the build',
                    callsOf(r).count { it.contains("'-Dversion=341-") && !it.contains('SNAPSHOT') && it.contains("'-Dfile=/ws/.dso-db/orders-db/orders-db-341-") } == 1, null)
        }],
        full + [name: 'D3 full, Gradle module and Node.js, green', layout: 'b', executed: fullStages, skipped: [STATIC], result: 'SUCCESS',
                golden: 'full-gradle-module-green', prepare: { FakeScript j -> j.files['/opt/liquibase/4.33.0/internal/lib/ojdbc8.jar'] = 'jar' }, checks: { Map r ->
            List<String> late = []
            List<String> block = []
            logOf(r).each { String line ->
                if (!line.startsWith('[STAGE]  ')) {
                    if (!line.startsWith('[PORTAL] ')) block << line
                    return
                }
                int db = block.findLastIndexOf { it.contains('orders-db') && !it.contains('orders-app') }
                int app = block.findIndexOf { it.contains('orders-app') && !it.contains('orders-db') }
                if (db >= 0 && app >= 0 && app < db) late << line
                block = []
            }
            check('D3: in every stage the orders-db lines come before the orders-app lines (the portal lines aside), and orders-db prints no [NODE] line',
                    !late && !logOf(r).any { it.startsWith('[NODE] orders-db') }, late)
            Map alone = runDatabase(full + [name: 'D3 without the database service', layout: 'b', configure: { Map p -> p.remove('orders-db') }])
            Closure appCalls = { Map run ->
                List<String> calls = ((run.j as FakeScript).calls as OwnedList).ownedBy('orders-app')
                List<Integer> logins = (0..<calls.size()).findAll { calls[it].contains('" api_login ') }.collectMany { [it - 2, it - 1, it] }
                (0..<calls.size()).findAll { !logins.contains(it) && !calls[it].contains('portal_re') }.collect { calls[it].replace(',.dso-db', '').replaceAll(/(?<!\d)\d{13}(?!\d)/, '<ms>') }
            }
            List<String> mixed = appCalls(r)
            List<String> single = appCalls(alone)
            check('D3: every orders-app call equals its call in a run without the database service, except .dso-db in APPSCAN_EXCLUDED_FOLDERS, the AppScan logins that the second AppScan key adds and the report sent to the portal, which names every service',
                    mixed == single && ((r.j as FakeScript).calls as OwnedList).ownedBy('orders-app').any { it.contains('APPSCAN_EXCLUDED_FOLDERS=orders-db,node_modules,.dso-db') },
                    [mixed.size(), single.size()] + (0..<Math.min(mixed.size(), single.size())).findAll { mixed[it] != single[it] }.take(1).collect { [mixed[it], single[it]]*.take(300) })
            List gradle = (r.shells as List<Map>).findAll { (it.label as String).startsWith('Gradle: ') }
            check('D3: the Gradle steps of orders-app run with its javaPath, and the run-level Nexus IQ card holds the orders-app results',
                    gradle && gradle.every { it.javaHome == '/usr/lib/jvm/java-17-openjdk' && it.owner == 'orders-app' } && (r.html as String).contains('CertValidityMonitoring-GUI'),
                    gradle.collect { [it.label, it.javaHome] }.take(2))
            check('D3: the bundled driver of a full Liquibase 4.33.0 is named (M-DB-17), and the JDK of the auto setup is used',
                    has(r, '[DB] orders-db: Liquibase 4.33 uses its bundled Oracle driver /opt/liquibase/4.33.0/internal/lib/ojdbc8.jar before tools.oracleJdbc.path - remove that jar to use the configured driver (chapter 14.2, trimmed install)')
                            && has(r, '[DB] orders-db: Liquibase 4.33.0, licence Apache-2.0 (core; bundle includes third-party and commercial components), JDK /usr/lib/jvm/java-17-openjdk'), null)
        }],
        security + [name: 'D4 security, Maven and Node.js in their own folders, green', layout: 'c', executed: securityStages, skipped: [STATIC],
                    result: 'SUCCESS', golden: 'security-maven-green', checks: { Map r ->
            List maven = (r.shells as List<Map>).findAll { (it.label as String).startsWith('Maven: ') && it.owner == 'orders-api' }
            String irx = ((r.j as FakeScript).calls as OwnedList).ownedBy('orders-api').find { it.startsWith('withEnv [APPSCAN_CMD_RUNTIME') && it.contains('APPSCAN_EXCLUDED_FOLDERS=') }
            check('D4: the package holds the whole search root and no liquibase*.properties file, Maven of orders-api runs in api, and its IRX excludes orders-db',
                    callsOf(r).any { it.contains("cp -R 'orders-db/.' '.dso-db/orders-db/package/'") } && !logOf(r).any { it.contains('liquibase*.properties') }
                            && maven && maven.every { it.cwd == 'api' } && irx?.contains('APPSCAN_EXCLUDED_FOLDERS=orders-db,'), [maven.collect { it.cwd }, irx?.take(300)])
            Map planted = runDatabase(security + [name: 'D4 with a liquibase.properties', layout: 'c', files: ['orders-db/liquibase.properties': 'liquibase.command.url=jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD\n']])
            check('D4: a planted liquibase.properties is left out of the package (M-DB-08)',
                    has(planted, '[DB] orders-db: 1 liquibase*.properties file(s) were left out of the package: the pipeline passes every Liquibase setting itself, and a properties file may hold a password'),
                    logOf(planted).findAll { it.startsWith('[DB]') })
        }],
        full + [name: 'D5 full, compile errors on RD', layout: 'a', executed: fullStages.take(7), skipped: [STATIC] + fullStages.drop(8), result: 'FAILURE',
                failedStage: RD, golden: 'full-rd-compile-failure',
                compile: [rd: [before: [oldView], updated: [oldView, 'PACKAGE BODY PKG_ORDERS 20261031101502'], invalid: ['PACKAGE BODY PKG_ORDERS', 'VIEW V_LEGACY_REPORT'],
                               errors: [pkgError, viewError]]], checks: { Map r ->
            check('D5: RD fails with M-DB-44 that names only the new invalid object, and M-DB-71 names the object that was invalid before',
                    errorOf(r) == "[error] [DB] orders-db rd: the deployment left invalid objects: PACKAGE BODY PKG_ORDERS | ${pkgError} - fix the source and deploy again; the library does not roll back".toString()
                            && has(r, '[DB] orders-db rd: invalid before this deployment and not changed by it: VIEW V_LEGACY_REPORT')
                            && has(r, '[DB] orders-db rd: compile check: 1 invalid objects, 1 compile errors (1 invalid before the update, not counted)')
                            && stageOf(r, RD) == 'FAIL', errorOf(r))
            String card = dbCard(r)
            check('D5: the Database changes card shows the RD row FAIL with its reason and the object invalid before, after the PASS row of the test database',
                    card.contains("RD<div style='font-size:0.68rem;color:#64748b;'>the deployment left invalid objects: PACKAGE BODY PKG_ORDERS | PACKAGE BODY PKG_ORDERS line 12: PLS-00201: identifier &#39;ORDER_ITEMZ&#39;")
                            && card.contains('>1 invalid before</div>') && card.indexOf('>PASS</span>') < card.indexOf('>FAIL</span>') && card.count('>FAIL</span>') == 1, card)
        }],
        security + [name: 'D6 security, static check findings', layout: 'a', executed: securityStages, skipped: [STATIC], result: 'UNSTABLE',
                    golden: 'security-static-checks-orange', demo: 'database-static-checks-orange', files: staticTree, configure: dropTargets(['test', 'rd', 'qc']), checks: { Map r ->
            String found = (r.j as FakeScript).files['.dso-db/orders-db/static-checks.txt'] ?: ''
            check('D6: Build artifact is WARN with M-DB-26, and static-checks.txt holds S1, S2b, S3a and S3b of the formatted SQL and S4, and nothing for the UPDATE or the TOAD package',
                    stageOf(r, BUILD) == 'WARN' && logOf(r).any { it.contains('6 static check finding(s) in the SQL and PL/SQL sources (.dso-db/orders-db/static-checks.txt)') }
                            && found.readLines() == staticFindings && callsOf(r).contains('archiveArtifacts .dso-db/orders-db/static-checks.txt'), found)
        }],
        extended + [name: 'D7 extended, the delivered package', layout: 'a', executed: extendedStages, skipped: [], result: 'SUCCESS', golden: 'extended-green', checks: { Map r ->
            List fetch = (r.shells as List<Map>).findAll { (it.label as String).startsWith('DB: fetch the delivered package ') && (it.script as String).contains("'dependency:") }
            String zip = ".dso-db/orders-db/orders-db-${delivered}-SNAPSHOT.zip"
            List<String> online = callsOf(r).findAll { it.startsWith('sh [DB: Liquibase') && it.contains('$DSO_STEP') }
            check('D7: dependency:get and dependency:copy fetch the coordinates of the delivery inside the DB work folder, and M-DB-63 names them',
                    fetch.size() == 2 && fetch.every { it.cwd == '/ws@tmp/dso-db/orders-db' && (it.script as String).contains("'-Dartifact=com.bbh.orders:orders-db:${delivered}-SNAPSHOT:zip'") }
                            && has(r, "[DB] orders-db: deploying the delivered package com.bbh.orders:orders-db:${delivered}-SNAPSHOT".toString()), fetch.collect { [it.cwd, it.script] })
            check('D7: RD, the release and QC use the fetched zip, and the run has no Unit tests stage',
                    online && online.every { it.contains("'--search-path=${zip}'") } && callsOf(r).any { it.contains("'-Dversion=${delivered}'") && it.contains("'-Dfile=/ws/${zip}'") }
                            && !stageResults(r.j as FakeScript).containsKey(UNIT) && !(r.j as FakeScript).files['release-gate.json'].contains(UNIT), online.take(1))
        }],
        sast + [name: 'D8 SAST, PL/SQL only', layout: 'a', executed: [MONITOR, BUILD, SAST], skipped: [], result: 'SUCCESS', golden: 'sast-green', checks: { Map r ->
            String prepare = callsOf(r).find { it.contains('" prepare ') } ?: ''
            check('D8: the IRX has no compile step (-sco), M-DB-51 and M-DB-50, no .dso-db in APPSCAN_EXCLUDED_FOLDERS, and no Liquibase step',
                    prepare.contains(' -sco') && has(r, '[SAST] orders-db: SQL and PL/SQL sources - the scan has no compile step')
                            && has(r, '[SAST] orders-db: 2 of 8 SQL and PL/SQL files have extensions AppScan does not scan (.prc .fnc .trg .vw .tps .tpb .typ .bdy): db/code/trg_orders_bi.trg, db/code/v_order_totals.vw')
                            && !callsOf(r).any { it.contains('APPSCAN_EXCLUDED_FOLDERS=') && it.contains('.dso-db') } && !callsOf(r).any { it.startsWith('sh [DB: Liquibase') },
                    prepare.readLines().find { it.contains(' prepare ') })
        }],
        nexusiq + [name: 'D9 Nexus IQ GoldenFix, Gradle module and Node.js', layout: 'b', executed: [MONITOR, BUILD, NIQ], skipped: [], result: 'SUCCESS',
                   golden: 'nexusiq-mixed-green', checks: { Map r ->
            check('D9: Build artifact and Nexus IQ are not required for orders-db, and Nexus IQ evaluates orders-app only',
                    has(r, '[BUILD] orders-db: the Nexus IQ GoldenFix pipeline does not package the database changes - not required')
                            && has(r, '[DEP-SCAN] orders-db: SQL and PL/SQL have no dependency components - Nexus IQ is not required')
                            && (r.evaluations as List<Map>).collect { it.iqApplication } == ['CertValidityMonitoring-GUI'], (r.evaluations as List<Map>).collect { it.iqApplication })
        }],
        full + [name: 'N1 full, a Liquibase service without a database block', layout: 'a', executed: fullStages.take(7), skipped: [STATIC] + fullStages.drop(8),
                result: 'FAILURE', failedStage: RD, golden: 'full-liquibase-commands-rd-failure',
                configure: { Map p -> noBlock(p); ((((p['orders-db'] as Map).tools as Map).sonar as Map)).liquibase = [sources: 'db'] }, checks: { Map r ->
            List<String> steps = callsOf(r).findAll { it.startsWith('sh [Liquibase') }.collect { it.substring(4, it.indexOf(']')) }
            check('N1: without a database block the service runs the Liquibase commands of build.liquibase, tests.unitTests.liquibase and asoc.liquibase, scans tools.sonar.liquibase.sources, and runs no database step',
                    steps == ['Liquibase: updateSQL', 'Liquibase test: validate', 'Liquibase SAST: updateSQL'] && callsOf(r).any { it.contains("'sonar-scanner' ") && it.contains(' -Dsonar.sources=db') }
                            && !callsOf(r).any { it.startsWith('sh [DB: ') || it.contains('.dso-db') } && !logOf(r).any { it.startsWith('[DB]') }, steps)
            check('N1: coverage is skipped as for every Liquibase service of main, and RD fails as on main, which has no database deployment',
                    has(r, '[COVERAGE] Liquibase: no covered lines found, skipping coverage requirement') && errorOf(r) == '[error] [DOD] No UrbanCode Deploy applications are configured',
                    errorOf(r))
        }]
]
goldenSpecs.each { verify(it) }
Map textKey = verify(full + [name: 'N1 full, a Liquibase service whose database key is a text', layout: 'a', executed: fullStages.take(7), skipped: [STATIC] + fullStages.drop(8),
                             result: 'FAILURE', failedStage: RD, configure: { Map p -> noBlock(p); (p['orders-db'] as Map).database = 'ORDERS' }])
check('N1: a database key that is not a map is ignored as on main: no database step, and RD fails as in N1',
        !logOf(textKey).any { it.startsWith('[DB]') } && errorOf(textKey) == '[error] [DOD] No UrbanCode Deploy applications are configured', errorOf(textKey))

Map plsql = security + [layout: 'a', configure: dropTargets(['test', 'rd', 'qc'])]
Closure focused = { String name, Map spec, String expected ->
    Map r = verify(spec + [name: name])
    List<String> lines = logOf(r).collect { it.replaceAll(/-${spec.buildNumber}-\d{8}-\d{6}/, "-${spec.buildNumber}-<stamp>") }
    check("${name}: ${expected.take(90)}", lines.any { it == expected || it == "[error] ${expected}".toString() || it.startsWith("[unstable] [POLICY] ${expected} ".toString()) },
            (errorOf(r) ?: logOf(r).findAll { it.startsWith('[DB]') || it.startsWith('[unstable]') }.takeRight(3)))
    return r
}

focused('D10 Nexus IQ GoldenFix with only a database service', nexusiq + [layout: 'a', failedStage: MONITOR],
        '[INIT] The Nexus IQ GoldenFix pipeline would scan nothing: orders-db is SQL and PL/SQL service(s), which have no dependency components; run it for a service with a Nexus IQ application')
focused('D11 a Windows agent', plsql + [failedStage: MONITOR, env: [DSO_PORTAL_AGENT: 'portal-agent'], prepare: { FakeScript j -> j.steps['isUnix'] = { a -> j.env.NODE_NAME == 'portal-agent' } }],
        '[DB] orders-db: a Liquibase service with a database block runs on Linux or macOS agents only: choose a Linux agent in AGENT_NAME')
focused('D13 the database service after the Java service', security + [layout: 'b', configure: { Map p -> p.put('orders-db', p.remove('orders-db')) }],
        '[DB] orders-db comes after orders-app in the list of services: the database changes are then deployed after that application in RD and QC; list the SQL and PL/SQL service first')

focused('D14 a Liquibase home that is not on the agent', plsql + [failedStage: BUILD, defaults: { Map d -> (d.tools as Map).liquibase = [home: '/opt/liquibase/4.30.0'] }],
        '[DB] defaults.tools.liquibase.home is /opt/liquibase/4.30.0, which does not exist on agent linux-agent: install it there or correct the setting')
focused('D14 no Liquibase CLI', plsql + [failedStage: BUILD, missingTool: 'liquibase', defaults: { Map d -> (d.tools as Map).liquibase = [:] }],
        '[DB] No Liquibase CLI on agent linux-agent: set tools.liquibase.home, or tools.liquibase.url with its sha256, in the Global Settings of the DevSecOps portal, or put liquibase on the PATH of the agent')
focused('D14 no utPLSQL-cli', security + [layout: 'a', failedStage: UNIT, missingTool: 'utplsql', defaults: { Map d -> (d.tools as Map).utplsql = [:] }],
        '[DB] No utPLSQL-cli on agent linux-agent, and orders-db runs utPLSQL (database.test.utplsql.enabled): set tools.utplsql.home, or tools.utplsql.url with its sha256, or put utplsql on the PATH of the agent')
String sha = 'a' * 64
Closure downloads = { Map d ->
    (d.tools as Map).liquibase = [url: 'https://tools.bbh.com/nexus/repository/tools/liquibase-4.33.0.tar.gz', sha256: sha]
    (d.tools as Map).utplsql = [url: 'https://tools.bbh.com/nexus/repository/tools/utPLSQL-cli.zip', sha256: 'b' * 64]
}
focused('D14 a download with the wrong sha256', plsql + [failedStage: BUILD, badSha: true, defaults: downloads],
        '[DB] The file at defaults.tools.liquibase.url does not have the sha256 of defaults.tools.liquibase.sha256 and was removed: check both settings')
Map twice = verify(security + [name: 'D14 two database services with the same tools', layout: 'a', defaults: downloads, configure: { Map p ->
    Map second = new Yaml().load(new Yaml().dump(p['orders-db'])) as Map
    ((second.database as Map).package as Map).artifactId = 'audit-db'
    (p['orders-db'].database as Map).remove('test')
    p['audit-db'] = second
}])
List<String> fetched = callsOf(twice).findAll { it.startsWith('sh [DB: download ') }.collect { it.readLines()[0] }
check('D14: the second service resolves Liquibase from the cache, and the later service that runs utPLSQL resolves utPLSQL',
        fetched == ['sh [DB: download liquibase] #!/usr/bin/env bash', 'sh [DB: download utplsql] #!/usr/bin/env bash']
                && callsOf(twice).findIndexOf { it.startsWith('sh [DB: download utplsql]') } > callsOf(twice).findIndexOf { it.startsWith('sh [DB: stage the package orders-db-') }, fetched)

focused('D15 Liquibase 4.32.0', plsql + [failedStage: BUILD, prepare: { FakeScript j -> j.files['/opt/liquibase/4.32.0'] = '' }, defaults: { Map d -> (d.tools as Map).liquibase = [home: '/opt/liquibase/4.32.0'] }],
        '[DB] orders-db: Liquibase 4.32.0 is not supported: use Liquibase 4.33 or 5.0')
focused('D15 Liquibase 4.34.0', plsql + [result: 'SUCCESS', prepare: { FakeScript j -> j.files['/opt/liquibase/4.34.0'] = '' }, defaults: { Map d -> (d.tools as Map).liquibase = [home: '/opt/liquibase/4.34.0'] }],
        '[DB] orders-db: Liquibase 4.34.0 has not been tested with the library (tested: 4.33.0 and 5.0.4)')
focused('D16 Liquibase 5.0.4 without a driver', security + [layout: 'a', failedStage: UNIT, defaults: { Map d -> (d.tools as Map).liquibase = [home: '/opt/liquibase/5.0.4']; (d.tools as Map).oracleJdbc = [:] }],
        '[DB] orders-db test: Liquibase 5.0.4 has no Oracle driver: set tools.oracleJdbc.path, or tools.oracleJdbc.url with its sha256, in the Global Settings of the DevSecOps portal')
[E1: "Unexpected formatting in formatted changelog 'db/tables/002-create-order-items.sql' at line 3.",
 E2: 'Validation Failed: 1 changesets had duplicate identifiers db/tables/002-create-order-items.sql::002-create-order-items::orders-team',
 E3: 'Validation Failed: 1 changes have validation failures The file code/pkg_orderz.pkb was not found in the configured search path: - /ws - /opt/liquibase/4.33.0/lib - the changelog or a file it uses is outside the search root: check database.changelogFile, database.searchPath and the path attributes of the changelog',
 E4: "Error parsing command line: Using 'include in Formatted SQL changelog' requires a valid Liquibase license key. Get a Liquibase license key and free trial at https://liquibase.com/trial. - the changelog uses a Liquibase Pro feature, which the library does not support (for example include inside formatted SQL): remove it",
 E5: "Error parsing line 13 column 76 of db/changelog-master.xml: cvc-complex-type.2.4.a: Invalid content was found starting with element '{\"http://www.liquibase.org/xml/ns/dbchangelog\":changeSet}'. One of '{...}' is expected."
].each { String error, String reason ->
    focused("D17 an invalid changelog (${error})", plsql + [failedStage: BUILD, validateError: error], "[DB] orders-db: the changelog db/changelog-master.xml is not valid: ${reason}".toString())
}
focused('D18 a package that does not validate', plsql + [failedStage: BUILD, badPackage: true],
        '[DB] orders-db: the package .dso-db/orders-db/orders-db-212-<stamp>.zip is not usable: Validation Failed: 1 changes have validation failures The file code/pkg_orderz.pkb was not found in the configured search path: - /ws - /opt/liquibase/4.33.0/lib - the changelog or a file it uses is outside the search root: check database.changelogFile, database.searchPath and the path attributes of the changelog - every file the changelog uses must be under db of .')

Map unit = security + [layout: 'a']
Map onRd = extended + [layout: 'a', failedStage: RD]
focused('D19 a changeset without a rollback', plsql + [result: 'UNSTABLE', noRollback: true,
                                                      configure: orders { Map db, Map svc -> ['test', 'rd', 'qc'].each { db.remove(it) }; db.requireRollback = true }],
        'future-rollback-sql failed: Liquibase does not support automatic rollback generation for raw sql changes (did you mean to specify keyword "empty" to ignore rolling back this change?) - every changeset needs a rollback because database.requireRollback is true')

focused('D20 a failing utPLSQL test', unit + [result: 'UNSTABLE', utFail: true, junit: [2, 1, 0]], 'utPLSQL: 1 of 2 tests failed (.dso-db/orders-db/utplsql-junit.xml)')
focused('D20 utPLSQL cannot connect', unit + [result: 'UNSTABLE', utNoConnect: true],
        'utPLSQL could not run the tests (exit code 1): Could not establish connection to database. Reason: ORA-12541: Cannot connect. No listener at host dev-db.bbh.com port 1521. - check that utPLSQL is installed in the test database and that the user may run it')
focused('D20 a password utPLSQL cannot take', unit + [failedStage: UNIT, badPassword: true],
        '[DB] orders-db test: the user or the password of the Jenkins credentials orders-db-test cannot be passed to utPLSQL: it is empty, starts or ends with a blank, holds a character outside printable ASCII, or (utPLSQL) starts and ends with a double quote')
focused('D20 utPLSQL ran no test', unit + [result: 'UNSTABLE', junit: [0, 0, 0]],
        'utPLSQL ran no test: check that the test packages keep their --%suite annotations (stripComments false) and that database.test.utplsql.paths names their schema')
focused('D21 credentials that do not exist', unit + [failedStage: UNIT, missingCredentials: ['orders-db-test']],
        "[DB] orders-db test: the Jenkins credentials orders-db-test (database.test.credentialsId) cannot be used: Could not find credentials entry with ID 'orders-db-test' - add Username with password credentials with this ID to the folder of the job")

focused('D22 the RD database cannot be reached', onRd + [connectFails: 'rd'],
        '[DB] orders-db rd: status failed: Connection could not be created to jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD with driver oracle.jdbc.OracleDriver.  ORA-12541: Cannot connect. No listener at host rd-db.bbh.com port 1521. - check the JDBC URL of the target, the network route from the agent to the database and the Jenkins credentials of the target')
String stays = ' - the changesets that ran stay applied; fix the cause and run the stage again; the library does not roll back'
[checksum   : '0 of 8 changesets: Validation Failed: 1 changesets check sum ' + DatabaseResponder.CHECKSUM +
        ' - an applied changeset was edited: undo the edit and add a new changeset, or mark the changeset runOnChange="true" when it holds a CREATE OR REPLACE object',
 lock       : '0 of 8 changesets: Could not acquire change log lock.  Currently locked by jenkins-agent-07 (10.20.30.40) since 10/30/26, 11:48 PM - another deployment holds the Liquibase lock of this schema: wait for it to end; if none runs, ask the DBA to check DATABASECHANGELOGLOCK before anyone runs liquibase release-locks',
 'ora-00955': '3 of 8 changesets: Migration failed for changeset db/tables/002-create-order-items.sql::002-create-order-items::orders-team: Reason: liquibase.exception.DatabaseException: ORA-00955: name is already used by an existing object [Failed SQL: (955) CREATE TABLE order_items (item_id NUMBER(19) NOT NULL)] - the object already exists: for a schema that existed before Liquibase, the DBA marks the existing changesets as run with changelog-sync, or the changeset gets a MARK_RAN precondition (chapter 14.8)'
].each { String error, String text ->
    focused("D22 the RD update fails (${error})", onRd + [updateError: [rd: error]], "[DB] orders-db rd: update failed after ${text}${stays}".toString())
}
focused('D22 a changeset still pending after the update', onRd + [stillPending: 'rd'],
        '[DB] orders-db rd: 1 changeset is still pending after the update: db/changelog-master.xml::ut_pkg_orders.pks::orders-team - check their contexts, labels and preconditions')
Map again = focused('D22 a runAlways changeset', extended + [layout: 'a', result: 'SUCCESS', runAlways: 'rd'],
        '[DB] orders-db rd: up to date after the update (1 runAlways changesets ran again and are always listed by status)')
check('D22: a runAlways changeset listed by status is not pending (no M-DB-48)', !logOf(again).any { it.contains('still pending after the update') }, null)
Map tagless = focused('D22 the tag cannot be set', extended + [layout: 'a', result: 'UNSTABLE', tagFails: 'rd'],
        "the tag orders-db-${delivered} could not be set on rd: Could not acquire change log lock.  Currently locked by jenkins-agent-07 (10.20.30.40) since 10/30/26, 11:48 PM - the changes are deployed, only the tag is missing".toString())
check('D22: a tag that cannot be set makes RD WARN', stageOf(tagless, RD) == 'WARN', stageOf(tagless, RD))
focused('D22 nothing to run', extended + [layout: 'a', result: 'SUCCESS', nothingRan: 'rd'],
        '[DB] orders-db rd: nothing ran, so the database keeps its previous tag (a tag with nothing run would replace it)')
focused('D22 no compile baseline', extended + [layout: 'a', result: 'SUCCESS', noBaseline: 'rd'],
        '[DB] orders-db rd: the compile check has no baseline (the output has a format the library does not know) - every invalid object of the schema counts')

focused('D23 the Nexus delivery is off', plsql + [result: 'SUCCESS', configure: orders { Map db, Map svc -> ['test', 'rd', 'qc'].each { db.remove(it) }; (db.package as Map).nexusDelivery = false }],
        '[NEXUS] orders-db: the Nexus delivery of the SQL and PL/SQL package is off (database.package.nexusDelivery) - not required')
Map local = focused('D23 no RD and no QC database', full + [layout: 'a', result: 'SUCCESS', configure: dropTargets(['rd', 'qc'])],
        '[DEPLOY] orders-db: no RD database (database.rd.url) - not required')
check('D23: no QC database is not required (M-DB-65)', has(local, '[DEPLOY] orders-db: no QC database (database.qc.url) - not required') && stageOf(local, QC) == 'NOT_REQUIRED', stageOf(local, QC))

Map wanted = focused('D24 regression tests required without jobs', extended + [layout: 'a', result: 'UNSTABLE', configure: { Map p -> (p['orders-db'] as Map).tests = [regression: [required: true]] }],
        'Regression Tests: no test jobs configured, at least one job is required in the DevSecOps portal.')
check('D24: required regression tests without jobs are WARN', stageOf(wanted, REGRESSION) == 'WARN', stageOf(wanted, REGRESSION))
Map jobs = verify(extended + [name: 'D24 regression jobs of a database service', layout: 'a', result: 'SUCCESS', configure: { Map p ->
    (p['orders-db'] as Map).tests = [regression: [jobs: [[name: 'orders-db - regression', type: 'local', job: 'orders/db-regression-tests']]]]
}])
check('D24: the regression jobs of a database service run', callsOf(jobs).contains('build orders/db-regression-tests') && stageOf(jobs, REGRESSION) == 'PASS', stageOf(jobs, REGRESSION))
Map dast = verify(extended + [name: 'D25 DAST on a database service', layout: 'a', result: 'SUCCESS', configure: { Map p ->
    (p['orders-db'] as Map).dast = [enabled: true, scanName: 'orders-db-dast', targetUrl: 'http://orders-rd.bbh.com', presenceId: '']
}])
check('D25: dast.enabled on a database service runs the existing DAST path', (logOf(dast) as OwnedList).ownedBy('orders-db').contains('[DAST] Scan ID: DAST-SCAN-ID')
        && !logOf(dast).any { it.contains('has no web application to scan') } && stageOf(dast, DAST) == 'PASS', stageOf(dast, DAST))

Map analysed = focused('D26 SonarQube with SonarSource PL/SQL', unit + [result: 'SUCCESS', sonarPlsql: 'plsql', configure: sonarKey, env: [SONAR_AUTH_TOKEN: 'fake-sonar-token']],
        '[SONAR] orders-db: the SonarQube server analyses PL/SQL with SonarSource PL/SQL (language plsql)')
String scanner = callsOf(analysed).find { it.contains("'sonar-scanner' ") } ?: ''
check('D26: the scanner arguments of a PL/SQL scan, with bdy and the utPLSQL reports, and no token in a recorded call',
        scanner == "sh 'sonar-scanner' '-Dsonar.host.url=https://tools.bbh.com/sonar' '-Dsonar.projectKey=orders-db' '-Dsonar.sources=db' " +
                "'-Dsonar.plsql.file.suffixes=sql,pks,pkb,pls,plb,pck,prc,fnc,trg,vw,tps,tpb,typ,bdy' '-Dsonar.tests=db/test' '-Dsonar.exclusions=db/test/**' " +
                "'-Dsonar.coverageReportPaths=.dso-db/orders-db/utplsql-coverage-sonar.xml' '-Dsonar.testExecutionReportPaths=.dso-db/orders-db/utplsql-sonar-tests.xml'"
                && stageOf(analysed, SONAR) == 'PASS' && !callsOf(analysed).any { it.contains('fake-sonar-token') }, scanner)
focused('D26 SonarQube without a PL/SQL analyzer', unit + [result: 'SUCCESS', configure: sonarKey],
        '[SONAR] orders-db: the SonarQube server has no PL/SQL analyzer (no language plsql or plsqlopen) - SonarQube is not required for SQL and PL/SQL')
focused('D26 SonarQube does not answer', unit + [result: 'SUCCESS', sonarDown: true, configure: sonarKey],
        '[SONAR] orders-db: the SonarQube server did not list its languages - SonarQube is not required for SQL and PL/SQL; set tools.sonar.authToken when the server needs a login')

Map own = focused('D27 extended without Nexus delivery, with a static check finding', extended + [layout: 'a', result: 'UNSTABLE', delivery: null,
                                                                                              files: ['db/code/orders_purge.prc': staticTree['db/code/orders_purge.prc']],
                                                                                              configure: orders { Map db, Map svc -> (db.package as Map).nexusDelivery = false }],
        '[DB] orders-db: database.package.nexusDelivery is false, so this run packages its own checkout as orders-db-118-<stamp> and deploys that')
check('D27: the static check WARN lands on Lower test region deployment, and no Unit tests result is recorded', stageOf(own, RD) == 'WARN'
        && !stageResults(own.j as FakeScript).containsKey(UNIT) && !(own.j as FakeScript).files['release-gate.json'].contains(UNIT), stageResults(own.j as FakeScript))
focused('D28 extended without a delivery', onRd + [delivery: null],
        '[DB] orders-db: the security pipeline delivered no version of orders-db (delivery in pipeline-config.yaml) - run the security pipeline first, or set database.package.nexusDelivery to false')
focused('D28 the delivered package cannot be fetched', onRd + [fetchFails: true],
        "[DB] orders-db: the delivered package com.bbh.orders:orders-db:${delivered}-SNAPSHOT could not be fetched from bbh-db-snapshots: script returned exit code 1 - check that it is in Nexus and that the agent's Maven settings can read the repository".toString())

String secret = 'Rd3r-Secret'
Map blocked = focused('D30 a password and an executeCommand in the sources', plsql + [failedStage: BUILD, files: [
        'db/changelog-master.xml'        : withChangeSets('    <changeSet id="run-script" author="orders-team">\n        <executeCommand executable="/bin/sh"/>\n    </changeSet>\n'),
        'db/tables/003-create-reader.sql': "--liquibase formatted sql\n\n--changeset orders-team:003-create-reader\nCREATE USER orders_reader IDENTIFIED BY \"${secret}\";\n".toString()]],
        '[DB] orders-db: 2 finding(s) that the library does not deploy (.dso-db/orders-db/static-checks.txt): a password, executeCommand, customChange or a ${...} that names a secret - remove them')
String blocking = (blocked.j as FakeScript).files['.dso-db/orders-db/static-checks.txt'] ?: ''
check('D30: static-checks.txt holds the S5 and S6 findings and is archived before the error, and no output quotes the password',
        blocking.readLines().size() == 2 && blocking.contains('db/tables/003-create-reader.sql line 4: a password is written in the source (IDENTIFIED BY)') && blocking.contains('executeCommand runs code on the Jenkins agent')
                && callsOf(blocked).contains('archiveArtifacts .dso-db/orders-db/static-checks.txt')
                && !(logOf(blocked) + callsOf(blocked) + [blocking]).any { it.contains(secret) }, blocking)

Map stopped = focused('D31 the RD update times out while this build holds the lock', onRd + [timeoutAt: 'DB: Liquibase update on rd', ownLock: true],
        '[DB] orders-db rd: released the Liquibase lock that this build held after DB: Liquibase update on rd stopped')
check('D31: M-DB-80, then list-locks and release-locks of this build',
        has(stopped, '[DB] orders-db rd: DB: Liquibase update on rd did not end within 30 minutes (database.rd.timeoutMinutes)')
                && labels(stopped).findAll { it.contains('locks on rd') } == ['DB: Liquibase list-locks on rd', 'DB: Liquibase release-locks on rd'], labels(stopped).takeRight(3))
Map unlocked = focused('D31 the RD update times out without a lock of this build', onRd + [timeoutAt: 'DB: Liquibase update on rd'],
        '[DB] orders-db rd: DB: Liquibase update on rd stopped; no Liquibase lock of this build was held')
check('D31: without a lock of this build, no release-locks', !labels(unlocked).contains('DB: Liquibase release-locks on rd'), labels(unlocked).takeRight(3))

Map reset = focused('D32 a reset of the test schema', unit + [result: 'SUCCESS', configure: orders { Map db, Map svc -> (db.test as Map).reset = true }],
        '[DB] orders-db test: removed every object of the test schema before the deployment (database.test.reset)')
int dropped = labels(reset).indexOf('DB: Liquibase drop-all on the test database')
int rest = labels(reset).indexOf('DB: drop the code, types and synonyms left on the test database')
Map restShell = (reset.shells as List<Map>).find { it.label == 'DB: drop the code, types and synonyms left on the test database' } ?: [:]
check('D32: drop-all, then the drop of the code, types and synonyms that drop-all leaves, run before the update of the test database',
        dropped >= 0 && dropped < rest && rest < labels(reset).indexOf('DB: Liquibase update on test') && (restShell.script as String)?.contains("'execute-sql' '--delimiter=/' '--sql-file=/ws@tmp/dso-db/orders-db/test-reset.sql'")
                && restShell.sql == new File(fixtures, 'test-reset-default.sql').text, [labels(reset), restShell.script])
focused('D32 the code left by drop-all cannot be dropped', unit + [failedStage: UNIT, resetFails: true, configure: orders { Map db, Map svc -> (db.test as Map).reset = true }],
        '[DB] orders-db test: drop-all failed: the packages, procedures, functions, triggers, types and synonyms that it leaves could not be dropped: ORA-01031: insufficient privileges - the deploy user lacks a privilege: see the grants in chapter 14.8')
focused('D32 changesets of another branch in the test database', unit + [result: 'SUCCESS', unexpected: true],
        '[DB] orders-db test: 2 changesets in the test database are not in this changelog (another branch or job?) - set database.test.reset to true, or give each pipeline its own test schema')

Closure before = { List<String> items, Closure first, Closure then -> int at = items.findIndexOf(first); at >= 0 && at < items.findIndexOf(then) }
Map withSteps = verify(unit + [name: 'N2 a database block with the Liquibase commands of build.liquibase and tests.unitTests.liquibase', result: 'SUCCESS',
                               configure: orders { Map db, Map svc -> ownSteps(svc) }])
List<String> order = (withSteps.shells as List<Map>).collect { it.label as String }
List<Map> ownShells = (withSteps.shells as List<Map>).findAll { it.label in ['Liquibase: updateSQL', 'Liquibase test: validate'] }
check('N2: the step of build.liquibase runs before the offline validation of the block, and the step of tests.unitTests.liquibase before the update of the test database, both with the JDK of the service',
        before(order, { it == 'Liquibase: updateSQL' }, { it == 'DB: Liquibase validate (offline)' }) && before(order, { it == 'Liquibase test: validate' }, { it == 'DB: Liquibase update on test' })
                && ownShells.size() == 2 && ownShells.every { it.javaHome == '/usr/lib/jvm/java-17-openjdk' }, order)
Map sastStep = verify(sast + [name: 'N3 a database block with asoc.liquibase', layout: 'a', result: 'SUCCESS',
                              configure: orders { Map db, Map svc -> (svc.asoc as Map).liquibase = [commands: ['validate'], flags: [changelogFlag]] }])
String irx = callsOf(sastStep).find { it.contains('" prepare ') } ?: ''
check('N3: the step of asoc.liquibase runs before the IRX is prepared, and the IRX has no compile step (-sco)',
        before(callsOf(sastStep), { it.startsWith('sh [Liquibase SAST: validate]') }, { it.contains('" prepare ') }) && irx.contains(' -sco'),
        callsOf(sastStep).findAll { it.startsWith('sh [Liquibase') } + [irx.readLines().find { it.contains(' prepare ') }])
Map tuned = verify(unit + [name: 'N4 a database block with tools.sonar.liquibase', result: 'SUCCESS', sonarPlsql: 'plsql', env: [SONAR_AUTH_TOKEN: 'fake-sonar-token'], configure: { Map p ->
    sonarKey(p)
    ((((p['orders-db'] as Map).tools as Map).sonar as Map)).liquibase = [sources: 'db/code', flags: ['-Dsonar.plsql.file.suffixes=pks,pkb'], exclusions: 'db/legacy/**']
}])
String tunedScan = callsOf(tuned).find { it.contains("'sonar-scanner' ") } ?: ''
check('N4: the sources, flags and exclusions of tools.sonar.liquibase win, the block adds the test folder to their exclusions, so that no file is a source and a test, and adds only the arguments they leave out',
        tunedScan == "sh 'sonar-scanner' '-Dsonar.host.url=https://tools.bbh.com/sonar' '-Dsonar.projectKey=orders-db' '-Dsonar.plsql.file.suffixes=pks,pkb' '-Dsonar.sources=db/code' " +
                "'-Dsonar.exclusions=db/legacy/**,db/test/**' '-Dsonar.tests=db/test' '-Dsonar.coverageReportPaths=.dso-db/orders-db/utplsql-coverage-sonar.xml' " +
                "'-Dsonar.testExecutionReportPaths=.dso-db/orders-db/utplsql-sonar-tests.xml'", tunedScan)
[['N5 a utPLSQL coverage report without lines', '<coverage line-rate="0.0" lines-covered="0" lines-valid="0" version="1">\n</coverage>\n'],
 ['N5 a utPLSQL coverage report that cannot be read', '<html><body>Bad Gateway</body></html>\n']].each { List c ->
    Map r = focused(c[0] as String, unit + [result: 'UNSTABLE', cobertura: c[1]],
            'No coverage report with measured lines (.dso-db/orders-db/utplsql-cobertura.xml) - the required 60% line coverage cannot be verified.')
    check("${c[0]}: Unit tests is WARN, and the Liquibase coverage skip of main does not apply", stageOf(r, UNIT) == 'WARN' && !logOf(r).any { it.startsWith('[COVERAGE] Liquibase:') }, stageOf(r, UNIT))
}
Map nextTo = verify(security + [name: 'N6 a Gradle service with build.liquibase next to a database service', layout: 'b', result: 'SUCCESS', configure: { Map p ->
    ((p['orders-app'] as Map).build as Map).liquibase = [commands: ['updateSQL'], flags: ['--changelog-file=orders-db/src/main/resources/db/changelog/db.changelog-master.xml', '--url=offline:oracle']]
}])
List<String> appSteps = (nextTo.shells as List<Map>).findAll { it.owner == 'orders-app' }.collect { it.label as String }
check('N6: the Gradle service runs its Gradle build and then the additional Liquibase step of main',
        (logOf(nextTo) as OwnedList).ownedBy('orders-app').contains('[BUILD] Running additional Liquibase step for multi-module project')
                && before(appSteps, { it.startsWith('Gradle: ') }, { it == 'Liquibase: updateSQL' }), appSteps)
Map folder = verify(security + [name: 'N7 tools.sonar.liquibase.dir without sources', layout: 'a', configure: { Map p ->
    noBlock(p)
    ((((p['orders-db'] as Map).tools as Map).sonar as Map)).liquibase = [dir: 'db']
}])
Map inDir = (folder.shells as List<Map>).find { (it.script as String).contains("'sonar-scanner' ") }
check('N7: with tools.sonar.liquibase.dir and no sources the scanner runs in that folder with -Dsonar.sources=., not the folder twice',
        inDir?.cwd == 'db' && (inDir.script as String).tokenize(' ').contains('-Dsonar.sources=.'), inDir)
focused('N8 a database block without a changelog', plsql + [failedStage: MONITOR, configure: dropTargets(['test', 'rd', 'qc', 'changelogFile'])],
        '[DB] orders-db: database.changelogFile is required for the database block; without it the service runs the Liquibase commands of build.liquibase only')

List<String> doc = new File(root, 'documentation.confluence').readLines()
Closure blockAfter = { int at ->
    int open = (at + 1..<doc.size()).find { doc[it].startsWith('{code') } as int
    doc.subList(open + 1, (open + 1..<doc.size()).find { doc[it] == '{code}' } as int)
}
Closure inOrder = { List<String> items, List<String> within ->
    int at = 0
    items.every { String w -> int found = within.subList(at, within.size()).indexOf(w); at += found + 1; found >= 0 }
}
List<String> examples = []
doc.eachWithIndex { String line, int i ->
    java.util.regex.Matcher file = line =~ /^File \{\{.+\}\} \(\{\{(test\/fixtures\/database\/.+)\}\}\):$/
    java.util.regex.Matcher console = line =~ /^Console of \{\{(test\/sandbox\/golden\/database-.+\.txt)\}\}/
    if (file.matches()) {
        examples << file.group(1)
        check("Chapter 14: the example of ${file.group(1)} equals the fixture file", blockAfter(i) == new File(root, file.group(1)).readLines(), blockAfter(i))
    } else if (console.find()) {
        List<String> log = new File(root, console.group(1)).readLines().dropWhile { it != '== log' }
        List<String> shown = blockAfter(i).findAll { it != '...' }
        check("Chapter 14: the console lines of ${console.group(1)} occur in that order in its golden log", shown && inOrder(shown, log), shown.findAll { !log.contains(it) })
    }
}
Yaml yaml = new Yaml()
Closure projectsOf = { File f -> (yaml.load(f.getText('UTF-8')) as Map).projects as Map }
Closure copyOf = { Map m -> yaml.load(yaml.dump(m)) as Map }
Map layoutProjects = ['a', 'b', 'c'].collectEntries { [(it): projectsOf(new File(fixtures, "layout-${it}/devsecops_config.yaml"))] }
layoutProjects.each { String layout, Map projects ->
    Map db = projects.values().find { (it as Map).buildTool == 'liquibase' } as Map
    String master = [db.sourceDir, db.database.searchPath, db.database.changelogFile].findAll { it && it != '.' }.join('/')
    String marker = "Files of layout (${layout}) ({{test/fixtures/database/layout-${layout}}}):".toString()
    List<String> tree = doc.contains(marker) ? blockAfter(doc.indexOf(marker)) : []
    List<String> shown = ['devsecops_config.yaml', master, 'Jenkinsfile'].collect { "test/fixtures/database/layout-${layout}/${it}".toString() }
    check("Chapter 14: the tree of layout (${layout}) lists every file of the layout once, and its configuration, changelog master and Jenkinsfile are examples",
            tree && tree.size() == tree.toSet().size() && tree.toSet() == (layoutFiles(layout) as Map).keySet() && examples.containsAll(shown), [tree: tree, missing: shown - examples])
    String jenkinsfile = new File(fixtures, "layout-${layout}/Jenkinsfile").getText('UTF-8')
    check("Chapter 14: the Jenkinsfile of layout (${layout}) names the services of its configuration in their order",
            jenkinsfile.contains("projectNames: '${projects.keySet().join(',')}'".toString()), jenkinsfile)
}

Map reference = projectsOf(new File(root, 'test/fixtures/CertScanner/config.yaml'))
Map app = copyOf(reference.gui as Map)
app.excludedDirs = 'orders-db'
app.build.node = [enabled: true, dir: 'frontend']
app.tools.nexusIq.scanPatterns = ['**/app/build/libs/*.jar']
app.deploy.vm.dod.applications[0].components[0].baseDir = 'app/build/libs'
Map api = copyOf(reference['backend-api'] as Map)
api.excludedDirs = 'orders-db'
api.build.maven.dir = 'api'
api.build.node = [enabled: true, dir: 'web']
api.tests.unitTests.maven.dir = 'api'
api.tests.unitTests.unitTestResult = 'api/target/surefire-reports/*.xml'
api.tools.sonar.maven.dir = 'api'
[app, api].each { Map service -> (service.asoc as Map).remove('keySecret'); service.remove('influx') }
[[layout: 'b', name: 'orders-app', reference: 'gui', expected: app], [layout: 'c', name: 'orders-api', reference: 'backend-api', expected: api]].each { Map c ->
    Map actual = (layoutProjects[c.layout] as Map)[c.name] as Map
    check("Chapter 14: ${c.name} of layout (${c.layout}) is ${c.reference} of the reference implementation with the changes that chapter 14 names",
            actual == c.expected, (actual.keySet() + (c.expected as Map).keySet()).findAll { actual[it] != (c.expected as Map)[it] })
}

Map globalDefaults = (yaml.load(defaultsText) as Map).defaults as Map
toolDefaults(globalDefaults)
Closure merged
merged = { Map base, Map over ->
    Map result = [:] + base
    over.each { k, v -> result[k] = v instanceof Map && result[k] instanceof Map ? merged(result[k] as Map, v as Map) : v }
    result
}
['database-product-config.yaml': 'a', 'database-gradle-product-config.yaml': 'b', 'database-maven-product-config.yaml': 'c'].each { String file, String layout ->
    Map written = projectsOf(new File(fixtures, "portal/${file}"))
    Map documented = layoutProjects[layout] as Map
    check("Chapter 14: the DevSecOps portal writes the services of layout (${layout}) in their order (portal/${file})", (written.keySet() as List) == (documented.keySet() as List), written.keySet())
    written.each { String name, Map cfg ->
        Map layoutService = documented[name] as Map
        if (!layoutService?.database) return
        List keys = ['appId', 'buildTool', 'sourceDir', 'javaPath', 'buildToolAutoSetup', 'asoc', 'database']
        Map expected = copyOf(layoutService).subMap(keys)
        ['snapshotRepository', 'releaseRepository'].each { expected.database.package.remove(it) }
        check("Chapter 14: the DevSecOps portal writes the database settings of ${name} of layout (${layout}), without the Nexus repositories of the Global Settings",
                cfg.subMap(keys) == expected, keys.findAll { cfg[it] != expected[it] })
        List problems = ['full', 'security'].collectMany { String variant ->
            harness.run { harness.type('com.bbh.utils.DatabaseSettings').problems(name, merged(globalDefaults, cfg), globalDefaults, variant) } as List
        }
        check("Chapter 14: the settings that the DevSecOps portal writes for ${name} of layout (${layout}) pass the settings check of the full and security pipelines", !problems, problems)
    }
}

String chapter = doc.subList(doc.indexOf('{anchor:ch14}'), doc.indexOf('{anchor:ch15}')).join('\n')
Closure flatKeys
flatKeys = { Map m, String prefix -> m.collectMany { k, v -> String key = "${prefix}${k}"; v instanceof Map && k != 'parameters' ? flatKeys(v as Map, "${key}.") : [key] } }
List<String> settingKeys = ['buildTool', 'sourceDir', 'javaPath', 'buildToolAutoSetup', 'asoc.doCompile', 'build.liquibase.label'] +
        ['build.liquibase', 'tests.unitTests.liquibase', 'asoc.liquibase'].collectMany { String step -> ['commands', 'flags', 'dir', 'env'].collect { "${step}.${it}".toString() } } +
        ['sources', 'inclusions', 'exclusions', 'flags', 'dir', 'env'].collect { "tools.sonar.liquibase.${it}".toString() } +
        ['changelogFile', 'searchPath', 'oracleVersion', 'defaultSchema', 'liquibaseSchema', 'contexts', 'labels', 'parameters', 'requireRollback', 'tag', 'lockWaitMinutes',
         'compileCheck.enabled', 'compileCheck.recompile', 'compileCheck.ignoreObjects', 'checks.ignoreFiles', 'package.groupId', 'package.artifactId', 'package.nexusDelivery',
         'package.mvnPath', 'package.snapshotRepository.id', 'package.snapshotRepository.url', 'package.releaseRepository.id', 'package.releaseRepository.url',
         '<target>.url', '<target>.credentialsId', '<target>.contexts', '<target>.labels', '<target>.defaultSchema', '<target>.liquibaseSchema', '<target>.parameters',
         '<target>.timeoutMinutes', 'test.reset', 'test.utplsql.enabled', 'test.utplsql.paths', 'test.utplsql.owner', 'test.utplsql.sourcePath', 'test.utplsql.testPath',
         'test.utplsql.timeoutMinutes'].collect { "database.${it}".toString() } +
        ['tools.liquibase.home', 'tools.liquibase.url', 'tools.liquibase.sha256', 'tools.liquibase.javaVersion', 'tools.oracleJdbc.path', 'tools.oracleJdbc.url',
         'tools.oracleJdbc.sha256', 'tools.utplsql.home', 'tools.utplsql.url', 'tools.utplsql.sha256', 'database.allowedTargets', 'database.package.snapshotRepository.id',
         'database.package.snapshotRepository.url', 'database.package.releaseRepository.id', 'database.package.releaseRepository.url'].collect { "defaults.${it}".toString() } +
        (layoutProjects.values() + ['database-product-config.yaml', 'database-gradle-product-config.yaml', 'database-maven-product-config.yaml'].collect { projectsOf(new File(fixtures, "portal/${it}")) })
                .collectMany { Map projects -> projects.values().findAll { (it as Map).database }.collectMany { flatKeys((it as Map).database as Map, 'database.') } }.unique()
List<String> undocumented = settingKeys.findAll { String key ->
    !chapter.contains("{{${key}}}") && !chapter.contains("{{${key.replaceFirst(/^database\.(test|rd|qc)\./, 'database.<target>.')}}}")
}
check('Chapter 14: every setting of a Liquibase service, of its database block and of the Global Settings is in chapter 14 with its full key', !undocumented, undocumented)

String library = ['src', 'vars'].collectMany { String dir ->
    List<String> texts = []
    new File(root, dir).eachFileRecurse(groovy.io.FileType.FILES) { File f -> if (f.name.endsWith('.groovy')) texts << f.getText('UTF-8') }
    texts
}.join('\n')
int messages = doc.indexOf('||Message||Cause||Fix||')
List<String> fragments = (messages < 0 ? [] : doc.subList(messages + 1, doc.size()).takeWhile { it.startsWith('|') })
        .collectMany { String row -> (row.tokenize('|')[0] =~ /\*([^*]+)\*/).collect { (it as List)[1] as String } }
        .collectMany { String text -> text.split(/\.\.\./).collect { it.trim() }.findAll { it } }
check('Chapter 14: every message of the troubleshooting table occurs in the library', fragments && fragments.every { library.contains(it) }, fragments.findAll { !library.contains(it) })

println()
println(failures ? "${failures} DATABASE SCENARIO CHECK(S) FAILED" : 'ALL DATABASE SCENARIOS PASSED')
System.exit(failures ? 1 : 0)
