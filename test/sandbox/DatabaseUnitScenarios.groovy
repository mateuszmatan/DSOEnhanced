import devsecops.test.FakeAbort
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import org.yaml.snakeyaml.Yaml

import java.nio.file.Files
import java.security.MessageDigest

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File fixtures = new File(root, 'test/fixtures/database')

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
Closure ds = { -> harness.type('com.bbh.utils.DatabaseSettings') }
Closure lo = { -> harness.type('com.bbh.utils.LiquibaseOutput') }
Closure mismatches = { List cases, Closure actual -> cases.collect { List c -> [c[0], actual(c), c[-1]] }.findAll { it[1] != it[2] } }
Closure fixture = { String name -> new File(fixtures, name).getText('UTF-8') }

String layoutText = fixture('layout-a/devsecops_config.yaml')
Closure baseCfg = { -> ((new Yaml().load(layoutText) as Map).projects as Map)['orders-db'] as Map }
Map allowAll = [database: [allowedTargets: ['dev-db.bbh.com', 'rd-db.bbh.com', 'qc-db.bbh.com']]]

File tmp = Files.createTempDirectory('dso-db-unit').toFile()
Closure bash = { String text, Map env, File dir ->
    ProcessBuilder builder = new ProcessBuilder('bash', '-c', text).directory(dir).redirectErrorStream(true)
    Map processEnv = builder.environment()
    processEnv.keySet().removeAll(processEnv.keySet().findAll { it.startsWith('LIQUIBASE_') || it.startsWith('DSO_') || it == 'JAVA_OPTS' })
    env.each { k, v -> processEnv.put(k as String, v as String) }
    Process process = builder.start()
    String out = process.inputStream.getText('UTF-8')
    return [status: process.waitFor(), out: out]
}
Closure writeTree = { File dir, Map tree ->
    tree.each { path, text ->
        File file = new File(dir, path as String)
        file.parentFile.mkdirs()
        file.setText(text as String, 'UTF-8')
    }
}

Closure basics = {
    Closure utils = { -> harness.type('com.bbh.utils.BuildUtils') }
    Map changelog = [database: [changelogFile: 'db/changelog-master.xml']]
    List serviceCases = [['liquibase with a changelog', [buildTool: 'liquibase'] + changelog, true],
                         ['blanks and capitals', [buildTool: ' Liquibase ', database: [changelogFile: ' db/changelog-master.xml ']], true],
                         ['liquibase without a changelog', [buildTool: 'liquibase', database: [allowedTargets: ['rd-db.bbh.com']]], false],
                         ['liquibase with a blank changelog', [buildTool: 'liquibase', database: [changelogFile: ' ']], false],
                         ['liquibase without a database block', [buildTool: 'liquibase'], false], ['gradle with a changelog', [buildTool: 'gradle'] + changelog, false],
                         ['no buildTool', [buildTool: null], false], ['no cfg', null, false],
                         ['liquibase with a database key that is a text', [buildTool: 'liquibase', database: 'ORDERS'], false],
                         ['liquibase with a database key that is a list', [buildTool: 'liquibase', database: ['ORDERS']], false]]
    List serviceWrong = mismatches(serviceCases) { List c ->
        try {
            harness.run { utils().databaseBlock(c[1] as Map) }
        } catch (Throwable e) {
            e.getClass().simpleName
        }
    }
    check('BuildUtils.databaseBlock: buildTool liquibase, also with blanks and capitals, with a database.changelogFile, and nothing else; a database key that is not a map is ignored as on main',
            serviceWrong.isEmpty(), serviceWrong)

    Map node = [build: [node: [enabled: true]]]
    List nodeFlags = harness.run {
        [utils().nodeBuildEnabled(node + changelog + [buildTool: 'liquibase']), utils().nodeBuildNeeded(node + changelog + [buildTool: 'liquibase'], 'full', false),
         utils().nodeBuildEnabled(node + [buildTool: 'liquibase']), utils().nodeBuildEnabled(node + [buildTool: 'gradle']), utils().nodeBuildNeeded(node + [buildTool: 'gradle'], 'full', false)]
    } as List
    check('BuildUtils.nodeBuildEnabled and nodeBuildNeeded: a service with a database block never builds Node.js, even with build.node.enabled; a Liquibase service without one does as on main',
            nodeFlags == [false, false, true, true, true], nodeFlags)

    Map recorded = harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        state.currentProjectName = 'orders-db'
        state.recordDatabase([version: '341-20261031-101500'])
        state.recordDatabaseTarget('test', [result: 'PASS'])
        state.recordDatabaseTarget('rd', [result: 'FAIL', reason: 'ORA-01017'])
        state.recordDatabaseTarget('qc', [result: 'PASS'])
        state.recordDatabaseTarget('test', [changesets: 3])
        state.recordDatabase([delivery: [snapshot: 'com.bbh.orders:orders-db:341-20261031-101500-SNAPSHOT']])
        state.projectsDatabase
    } as Map
    Map orders = recorded['orders-db'] as Map
    check('PipelineState.recordDatabaseTarget: the targets keep the order test, RD, QC, an update merges into its target, and recordDatabase keeps the targets',
            (orders.targets as Map).keySet().toList() == ['test', 'rd', 'qc'] && (orders.targets as Map).test == [result: 'PASS', changesets: 3]
                    && (orders.targets as Map).rd == [result: 'FAIL', reason: 'ORA-01017'] && orders.version == '341-20261031-101500' && orders.delivery,
            recorded)

    def buildType = harness.type('com.bbh.build.BuildService')
    String literal = buildType.autoSetupScript()
    String anchor = buildType.autoSetupElseAnchor()
    String branch = buildType.autoSetupLiquibaseBranch()
    Closure autoSetup = { String tool, Map extra ->
        FakeScript j = new FakeScript()
        List scripts = []
        j.shHandler = { Map a -> scripts << a.script; '/opt/jdk-17\n' }
        String home = harness.run {
            def state = harness.type('com.bbh.core.PipelineState').newInstance()
            def os = harness.type('com.bbh.core.OsHelper').newInstance(j)
            def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(j, state, os)
            buildType.newInstance(j, state, os, policy).autoSetupJavaHome(tool, extra)
        }
        [home: home, script: scripts[0], env: j.calls.find { it.startsWith('withEnv ') }]
    }
    List javaWrong = ['gradle', 'maven', 'flutter'].collect { String tool -> [tool, autoSetup(tool, [:])] }
            .findAll { List r -> r[1] != [home: '/opt/jdk-17', script: literal, env: "withEnv [BUILD_SYSTEM=${r[0]}]".toString()] }
    Map liquibase = autoSetup('liquibase', [DSO_JAVA_VERSION: 21])
    String liquibaseScript = liquibase.script as String
    check('BuildService.autoSetupJavaHome: gradle, maven and flutter run the unchanged auto-setup literal; liquibase adds its branch once before the one else anchor and passes DSO_JAVA_VERSION',
            javaWrong.isEmpty() && literal.count(anchor) == 1 && liquibaseScript.count(branch) == 1 && liquibaseScript.count(anchor) == 1
                    && liquibaseScript.replace(branch, '') == literal && liquibaseScript.indexOf(branch) < liquibaseScript.indexOf(anchor)
                    && liquibase.env == 'withEnv [BUILD_SYSTEM=liquibase, DSO_JAVA_VERSION=21]' && liquibase.home == '/opt/jdk-17',
            javaWrong + [liquibase.env])

    FakeScript jdkScript = new FakeScript()
    jdkScript.shHandler = { Map a -> '/opt/jdk-21\n' }
    Map homes = harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(jdkScript)
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(jdkScript, state, os)
        def runner = harness.type('com.bbh.build.LiquibaseRunner').newInstance(jdkScript, state, os, buildType.newInstance(jdkScript, state, os, policy))
        Map auto = [buildToolAutoSetup: true, tools: [liquibase: [javaVersion: 21]]]
        [first: runner.javaHome('orders-db', auto), again: runner.javaHome('orders-db', auto), configured: runner.javaHome('api-db', [javaPath: '/usr/lib/jvm/java-17']),
         plain: runner.javaHome('web-db', [buildToolAutoSetup: true]), cached: state.projectsDatabase['orders-db'].javaHome]
    } as Map
    check('LiquibaseRunner.javaHome: javaPath wins, otherwise the auto setup for liquibase with tools.liquibase.javaVersion or 17, once per service',
            homes == [first: '/opt/jdk-21', again: '/opt/jdk-21', configured: '/usr/lib/jvm/java-17', plain: '/opt/jdk-21', cached: '/opt/jdk-21']
                    && jdkScript.calls.findAll { it.startsWith('withEnv ') } == ['withEnv [BUILD_SYSTEM=liquibase, DSO_JAVA_VERSION=21]', 'withEnv [BUILD_SYSTEM=liquibase, DSO_JAVA_VERSION=17]'],
            [homes, jdkScript.calls.findAll { it.startsWith('withEnv ') }])
}
basics()

Closure validation = {
    String V6 = 'must be an Oracle thin URL: jdbc:oracle:thin:@//host:1521/SERVICE, @host:1521:SID, @tcps://host:2484/SERVICE?ssl_server_dn_match=true or @(DESCRIPTION=...)'
    String V45 = 'is set, but defaults.database.allowedTargets is empty: the library deploys only to databases the DevSecOps team allows in the Global Settings'
    String V37 = 'which must start with a lower-case letter and hold only lower-case letters, digits, "_" and "." (at most 64), and at most 50 names'
    String V38 = 'which looks like a secret or a variable of the agent: a name must not hold password, passwd, pwd, secret, token, credential, key or user, nor start with liquibase_, dso_ or jenkins; changelog parameters are written into the package, so keep secrets in Jenkins credentials'
    String V39 = 'must be printable ASCII of at most 500 characters, without a leading or trailing blank and without "${"'
    String sha = 'a' * 64
    List<List> cases = [
            ['the layout a settings', { Map cfg, Map db -> }, 'full', allowAll, []],
            ['V1 no changelog', { Map cfg, Map db -> db.remove('changelogFile') }, 'full', allowAll, ['database.changelogFile is required for the database block; without it the service runs the Liquibase commands of build.liquibase only']],
            ['V2 a changelog with ..', { Map cfg, Map db -> db.changelogFile = 'db/../changelog.xml' }, 'full', allowAll,
             ['database.changelogFile db/../changelog.xml must be a relative path of letters, digits, ".", "_" and "-", separated by "/", without ".." and not starting with "/"']],
            ['V3 sourceDir outside', { Map cfg, Map db -> cfg.sourceDir = '../other' }, 'full', allowAll,
             ['sourceDir ../other must be "." or a relative path inside the repository, without ".."']],
            ['V3 an absolute searchPath', { Map cfg, Map db -> db.searchPath = '/db' }, 'full', allowAll,
             ['database.searchPath /db must be "." or a relative path inside the repository, without ".."']],
            ['V4 a changelog of another type', { Map cfg, Map db -> db.changelogFile = 'db/changelog-master.txt' }, 'full', allowAll,
             ['database.changelogFile db/changelog-master.txt must end with .xml, .yaml, .yml, .json or .sql']],
            ['V5 a user in the URL', { Map cfg, Map db -> db.rd.url = 'jdbc:oracle:thin:scott/tiger@//rd-db.bbh.com:1521/ORDERS_RD' }, 'full', allowAll,
             ['database.rd.url must not contain a user or a password: keep them in the Jenkins credentials database.rd.credentialsId']],
            ['V6 a TNS alias', { Map cfg, Map db -> db.rd.url = 'jdbc:oracle:thin:@ORDERS' }, 'full', allowAll, ["database.rd.url ${V6}".toString()]],
            ['V7 no credentials', { Map cfg, Map db -> db.rd.remove('credentialsId') }, 'full', allowAll, ['database.rd.credentialsId is required with database.rd.url']],
            ['V8 a credentials ID with blanks', { Map cfg, Map db -> db.rd.credentialsId = 'orders db rd' }, 'full', allowAll,
             ['database.rd.credentialsId orders db rd must be a Jenkins credentials ID of letters, digits, ".", "_" and "-" (at most 200)']],
            ['V9 credentials without a URL', { Map cfg, Map db -> db.rd.remove('url') }, 'full', allowAll, ['database.rd.credentialsId is set without database.rd.url']],
            ['V10 production', { Map cfg, Map db -> db.prod = [url: 'jdbc:oracle:thin:@//prod-db.bbh.com:1521/ORDERS']; db.live = [:] }, 'full', allowAll,
             ['database.prod is not supported: the library never deploys to production', 'database.live is not supported: the library never deploys to production']],
            ['V11 a host that is not allowed', { Map cfg, Map db -> db.rd.url = 'jdbc:oracle:thin:@//rd-db.bbh.com:1521,prod-db.bbh.com:1521/ORDERS_RD' }, 'full', allowAll,
             ['database.rd.url names host prod-db.bbh.com, which defaults.database.allowedTargets does not allow: ask the DevSecOps team to add the RD, QC or test database to the Global Settings']],
            ['V12 RD is the test database', { Map cfg, Map db -> db.rd.url = 'jdbc:oracle:thin:@//DEV-DB.bbh.com:1521/ORDERS_CI' }, 'full', allowAll,
             ['database.test.url is also the URL of database.rd: the test database receives the test packages and must be a database of its own']],
            ['V13 QC contexts', { Map cfg, Map db -> db.qc.contexts = 'test, rd' }, 'full', allowAll,
             ['database.qc.contexts test, rd deploys the test changesets: QC needs a context filter such as "!test"']],
            ['V13 inherited contexts', { Map cfg, Map db -> db.contexts = 'rd or test' }, 'full', allowAll,
             ['database.contexts rd or test deploys the test changesets: QC needs a context filter such as "!test"']],
            ['V14 schemas', { Map cfg, Map db -> db.defaultSchema = '1ORDERS'; db.qc.liquibaseSchema = 'LB-OWNER' }, 'full', allowAll,
             ['database.defaultSchema 1ORDERS must be an Oracle schema name: a letter, then letters, digits, "_", "$" or "#", at most 128',
              'database.qc.liquibaseSchema LB-OWNER must be an Oracle schema name: a letter, then letters, digits, "_", "$" or "#", at most 128']],
            ['V15 a label filter', { Map cfg, Map db -> db.labels = 'release;1' }, 'full', allowAll,
             ['database.labels release;1 may hold only letters, digits, blanks, "_", "!", "@", "(", ")", "," and "-" (at most 200)']],
            ['V16 the lock wait', { Map cfg, Map db -> db.lockWaitMinutes = 61 }, 'full', allowAll, ['database.lockWaitMinutes 61 must be a whole number from 0 to 60']],
            ['V16 a lock wait of 0', { Map cfg, Map db -> db.lockWaitMinutes = 0 }, 'full', allowAll, []],
            ['V17 utPLSQL without a test database', { Map cfg, Map db -> db.test.remove('url'); db.test.remove('credentialsId') }, 'full', allowAll,
             ['database.test.utplsql.enabled needs database.test.url']],
            ['V18 utPLSQL paths', { Map cfg, Map db -> db.test.utplsql.paths = 'ut_*' }, 'full', allowAll,
             ['database.test.utplsql.paths ut_* may hold only letters, digits, "_", "$", "#", ".", ":" and "," (at most 400)']],
            ['V19 a source path outside', { Map cfg, Map db -> db.test.utplsql.sourcePath = '../code' }, 'full', allowAll,
             ['database.test.utplsql.sourcePath ../code must be a relative path inside the search root, without ".."']],
            ['V20 the utPLSQL timeout', { Map cfg, Map db -> db.test.utplsql.timeoutMinutes = 0 }, 'full', allowAll,
             ['database.test.utplsql.timeoutMinutes 0 must be a whole number from 1 to 240']],
            ['V21 no groupId', { Map cfg, Map db -> db.package.remove('groupId') }, 'full', allowAll,
             ['database.package.groupId is required to deliver the package to Nexus in the full pipeline; set it, or set database.package.nexusDelivery to false']],
            ['V21 no delivery', { Map cfg, Map db -> db.package = [nexusDelivery: false] }, 'full', allowAll, []],
            ['V21 the SAST pipeline delivers nothing', { Map cfg, Map db -> db.package = [:] }, 'sast', allowAll, []],
            ['V22 an artifactId with a blank', { Map cfg, Map db -> db.package.artifactId = 'orders db' }, 'full', allowAll,
             ['database.package.artifactId orders db may hold only letters, digits, ".", "_" and "-" (at most 200)']],
            ['V23 no javaPath', { Map cfg, Map db -> cfg.remove('javaPath') }, 'full', allowAll,
             ['javaPath is required unless buildToolAutoSetup is true: Liquibase and utPLSQL need Java 17 or newer']],
            ['V23 the auto setup', { Map cfg, Map db -> cfg.remove('javaPath'); cfg.buildToolAutoSetup = true }, 'full', allowAll, []],
            ['V24 an ignored object', { Map cfg, Map db -> db.compileCheck = [ignoreObjects: ['PKG_OLD', 'bad name']] }, 'full', allowAll,
             ['database.compileCheck.ignoreObjects entry bad name must be an Oracle object name']],
            ['V25 an ignored file', { Map cfg, Map db -> db.checks = [ignoreFiles: ['db/legacy', '../x']] }, 'full', allowAll,
             ['database.checks.ignoreFiles entry ../x must be a relative path inside the search root, without ".."']],
            ['V26 a relative javaPath', { Map cfg, Map db -> cfg.javaPath = 'jdk17' }, 'full', allowAll, ['javaPath jdk17 must be an absolute path of a JDK 17 or newer']],
            ['V27 the Oracle version', { Map cfg, Map db -> db.oracleVersion = '19c' }, 'full', allowAll,
             ['database.oracleVersion 19c must be an Oracle version such as 19.0 or 23.0']],
            ['V28 a repository over http', { Map cfg, Map db -> db.package.snapshotRepository.url = 'http://tools.bbh.com/nexus/repository/db-snapshots' }, 'full', allowAll,
             ['database.package.snapshotRepository.url must be an https URL such as https://nexus.bbh.com/repository/db-snapshots, without a user or a password']],
            ['V29 a URL without its sha256', { Map cfg, Map db -> cfg.tools = [liquibase: [url: 'https://tools.bbh.com/nexus/repository/raw/liquibase-4.33.0.tar.gz']] }, 'full', allowAll,
             ['tools.liquibase.sha256 must be the 64 lower-case hexadecimal digits of the file at tools.liquibase.url']],
            ['V30 Java 11', { Map cfg, Map db -> cfg.tools = [liquibase: [javaVersion: 11]] }, 'full', allowAll,
             ['tools.liquibase.javaVersion 11 must be a Java major version from 17 to 25']],
            ['V32 lists and maps', { Map cfg, Map db -> db.checks = [ignoreFiles: 'db/legacy']; db.parameters = 'app_role' }, 'full', allowAll,
             ['database.parameters must be a map of names to values', 'database.checks.ignoreFiles must be a list of at most 50 strings']],
            ['V33 a driver zip', { Map cfg, Map db -> cfg.tools = [oracleJdbc: [url: 'https://tools.bbh.com/nexus/ojdbc11.zip', sha256: sha]] }, 'full', allowAll,
             ['tools.oracleJdbc.url must be an https URL of a .tar.gz, .tgz, .zip or .jar file, without a user or a password']],
            ['V34 relative tools', { Map cfg, Map db -> cfg.tools = [utplsql: [home: 'opt/utplsql'], oracleJdbc: [path: '/opt/ojdbc11']] }, 'full', allowAll,
             ['tools.oracleJdbc.path /opt/ojdbc11 must be an absolute path ending with .jar', 'tools.utplsql.home opt/utplsql must be an absolute path']],
            ['V34 tools equal to the Global Settings are checked there', { Map cfg, Map db -> cfg.tools = [liquibase: [home: 'opt/lb']] }, 'full',
             allowAll + [tools: [liquibase: [home: 'opt/lb']]], []],
            ['V35 a changelog without a folder', { Map cfg, Map db -> db.changelogFile = 'changelog-master.xml' }, 'full', allowAll,
             ['database.changelogFile changelog-master.xml has no folder and the search root is the repository root: put the changelog in a folder, or set database.searchPath or sourceDir to the folder of the changelog']],
            ['V35 a changelog without a folder in a search path', { Map cfg, Map db -> db.changelogFile = 'changelog-master.xml'; db.searchPath = 'db' }, 'full', allowAll, []],
            ['V36 no release repository URL', { Map cfg, Map db -> db.package.releaseRepository.remove('url') }, 'full', allowAll,
             ['database.package.releaseRepository.url is required to deliver the package to Nexus in the full pipeline']],
            ['V36 the extended pipeline releases', { Map cfg, Map db -> db.package.remove('releaseRepository') }, 'extended', allowAll,
             ['database.package.releaseRepository.id is required to deliver the package to Nexus in the extended pipeline',
              'database.package.releaseRepository.url is required to deliver the package to Nexus in the extended pipeline']],
            ['V36 the security pipeline needs no release repository', { Map cfg, Map db -> db.package.remove('releaseRepository') }, 'security', allowAll, []],
            ['V37 a parameter name', { Map cfg, Map db -> db.parameters = [App_Role: 'orders_app_role'] }, 'full', allowAll,
             ["database.parameters holds the name App_Role, ${V37}".toString()]],
            ['V37 51 names', { Map cfg, Map db -> db.parameters = (1..51).collectEntries { ["p${it}".toString(), 'v'] } }, 'full', allowAll,
             ["database.parameters holds the name p51, ${V37}".toString()]],
            ['V38 a secret parameter', { Map cfg, Map db -> db.parameters = [db_password: 'x'] }, 'full', allowAll,
             ["database.parameters holds the name db_password, ${V38}".toString()]],
            ['V38 the names that the static checks refuse', { Map cfg, Map db -> db.parameters = [app_user: 'orders_app', api_key: 'k']; db.rd.parameters = [dso_host: 'h'] }, 'full', allowAll,
             ["database.parameters holds the name app_user, ${V38}".toString(), "database.parameters holds the name api_key, ${V38}".toString(),
              "database.rd.parameters holds the name dso_host, ${V38}".toString()]],
            ['V39 parameter values', { Map cfg, Map db -> db.qc.parameters = [app_role: ' orders_qc_role']; db.rd.parameters = [app_role: 'a${b}', note: 'a$b'] }, 'full', allowAll,
             ["database.rd.parameters app_role ${V39}".toString(), "database.qc.parameters app_role ${V39}".toString()]],
            ['V40 a target timeout', { Map cfg, Map db -> db.rd.timeoutMinutes = 241 }, 'full', allowAll, ['database.rd.timeoutMinutes 241 must be a whole number from 1 to 240']],
            ['V41 a reset without a test database', { Map cfg, Map db -> db.test = [reset: true] }, 'full', allowAll, ['database.test.reset needs database.test.url']],
            ['V44 a relative Maven', { Map cfg, Map db -> db.package.mvnPath = 'maven' }, 'full', allowAll,
             ['database.package.mvnPath maven must be an absolute path of a Maven installation']],
            ['V45 no allowed targets', { Map cfg, Map db -> }, 'full', [:],
             ["database.test.url ${V45}".toString(), "database.rd.url ${V45}".toString(), "database.qc.url ${V45}".toString()]],
            ['host globs', { Map cfg, Map db -> }, 'full', [database: [allowedTargets: ['*.bbh.com']]], []],
            ['the V order', { Map cfg, Map db -> db.oracleVersion = '19c'; db.changelogFile = 'db/changelog-master.txt' }, 'full', allowAll,
             ['database.changelogFile db/changelog-master.txt must end with .xml, .yaml, .yml, .json or .sql',
              'database.oracleVersion 19c must be an Oracle version such as 19.0 or 23.0']]
    ]
    List wrong = mismatches(cases) { List c ->
        Map cfg = baseCfg()
        (c[1] as Closure).call(cfg, cfg.database as Map)
        harness.run { ds().problems('orders-db', cfg, c[3] as Map, c[2] as String) }
    }
    check('DatabaseSettings.problems: each of V1-V41, V44 and V45 with its text, the variant of 4.4, and the cases that are fine', wrong.isEmpty(), wrong)

    List<List> globalCases = [
            ['no Global Settings', [:], []],
            ['good tools and targets', [tools: [liquibase: [home: '/opt/liquibase/4.33.0', url: '', sha256: '', javaVersion: 17], oracleJdbc: [path: '/opt/ojdbc/ojdbc11.jar', url: '', sha256: ''],
                                                utplsql: [home: '', url: 'https://tools.bbh.com/nexus/utPLSQL-cli.zip', sha256: sha]],
                                        database: [allowedTargets: ['rd-db*.bbh.com', 'qc-db.bbh.com/ORDERS_QC']]], []],
            ['V32 a target text', [database: [allowedTargets: 'rd-db.bbh.com']], ['defaults.database.allowedTargets must be a list of at most 200 strings']],
            ['V31 a target', [database: [allowedTargets: ['rd-db.bbh.com', 'bad host!']]],
             ['defaults.database.allowedTargets entry bad host! must be "host" or "host/SERVICE" of letters, digits, ".", "-" and "*"']],
            ['V29 V30 V33 V34 tools', [tools: [liquibase: [home: 'opt/lb', javaVersion: 26], oracleJdbc: [url: 'https://user:pw@tools.bbh.com/ojdbc11.jar', sha256: 'ABC']]],
             ['defaults.tools.oracleJdbc.sha256 must be the 64 lower-case hexadecimal digits of the file at defaults.tools.oracleJdbc.url',
              'defaults.tools.liquibase.javaVersion 26 must be a Java major version from 17 to 25',
              'defaults.tools.oracleJdbc.url must be an https URL of a .tar.gz, .tgz, .zip or .jar file, without a user or a password',
              'defaults.tools.liquibase.home opt/lb must be an absolute path']]
    ]
    List globalWrong = mismatches(globalCases) { List c -> harness.run { ds().globalProblems(c[1] as Map) } }
    check('DatabaseSettings.globalProblems: V29-V34 for defaults.tools and V31 and V32 for defaults.database.allowedTargets', globalWrong.isEmpty(), globalWrong)
}
validation()

Closure settings = {
    Map plain = harness.run { ds().resolve('svc', [buildTool: 'liquibase', database: [changelogFile: 'db/changelog.xml']]) } as Map
    Map rdProfile = [name: 'rd', url: '', credentialsId: '', contexts: '!test', labels: '', defaultSchema: '', liquibaseSchema: '', parameters: [:], timeoutMinutes: 30, parsed: [:]]
    Map expected = [name: 'svc', changelog: 'db/changelog.xml', sourceDir: '.', searchRoot: '.', packageFolder: 'db', oracleVersion: '19.0', contexts: '!test', labels: '',
                    defaultSchema: '', liquibaseSchema: '', parameters: [:], requireRollback: false, tag: true, lockWait: 5,
                    compileCheck: [enabled: true, recompile: true, ignoreObjects: []], ignoreFiles: [],
                    package: [groupId: '', artifactId: 'svc', nexusDelivery: true, mvnPath: '', snapshot: [id: '', url: ''], release: [id: '', url: '']],
                    targets: [test: null, rd: null, qc: null],
                    profiles: [test: rdProfile + [name: 'test', contexts: 'test', reset: false,
                                                  utplsql: [enabled: false, paths: '', owner: '', sourcePath: '', testPath: '', timeoutMinutes: 30]],
                               rd: rdProfile, qc: rdProfile + [name: 'qc']]]
    check('DatabaseSettings.resolve: every key of 4.1 has its default', plain == expected, plain)

    Map cfg = baseCfg()
    Map db = cfg.database as Map
    cfg.build = [maven: [mvnPath: '/opt/maven']]
    cfg.sourceDir = 'orders-db'
    db.putAll([searchPath: './src/main/resources', contexts: '!test and !demo', labels: 'release-1', defaultSchema: 'ORDERS_OWNER', liquibaseSchema: 'LB_OWNER',
               lockWaitMinutes: 0, tag: 'false', compileCheck: [ignoreObjects: ['pkg_old']], checks: [ignoreFiles: ['db/legacy']]])
    db.rd.defaultSchema = 'RD_OWNER'
    db.rd.timeoutMinutes = '45'
    Map s = harness.run { ds().resolve('orders-db', cfg) } as Map
    Map p = s.profiles as Map
    Map inherited = [searchRoot: s.searchRoot, lockWait: s.lockWait, tag: s.tag, ignoreObjects: (s.compileCheck as Map).ignoreObjects, ignoreFiles: s.ignoreFiles,
                     mvnPath: (s.package as Map).mvnPath, artifactId: (s.package as Map).artifactId,
                     contexts: p.collectEntries { k, v -> [(k): v.contexts] }, labels: p.collectEntries { k, v -> [(k): v.labels] },
                     schemas: p.collectEntries { k, v -> [(k): [v.defaultSchema, v.liquibaseSchema]] }, parameters: p.collectEntries { k, v -> [(k): v.parameters] },
                     timeouts: p.collectEntries { k, v -> [(k): v.timeoutMinutes] }, owner: p.test.utplsql.owner, rd: (s.targets as Map).rd.parsed]
    check('DatabaseSettings.resolve: the targets inherit contexts (test excepted), labels, schemas and parameters, and keep their own values',
            inherited == [searchRoot: 'orders-db/src/main/resources', lockWait: 0, tag: false, ignoreObjects: ['PKG_OLD'], ignoreFiles: ['db/legacy'], mvnPath: '/opt/maven',
                          artifactId: 'orders-db', contexts: [test: 'test', rd: '!test and !demo', qc: '!test and !demo'],
                          labels: [test: 'release-1', rd: 'release-1', qc: 'release-1'],
                          schemas: [test: ['ORDERS_OWNER', 'LB_OWNER'], rd: ['RD_OWNER', 'LB_OWNER'], qc: ['ORDERS_OWNER', 'LB_OWNER']],
                          parameters: [test: [app_role: 'orders_app_role'], rd: [app_role: 'orders_app_role'], qc: [app_role: 'orders_qc_role']],
                          timeouts: [test: 30, rd: 45, qc: 30], owner: 'ORDERS_OWNER',
                          rd: [form: 'ezconnect', hosts: ['rd-db.bbh.com'], service: 'ORDERS_RD', connect: '//rd-db.bbh.com:1521/ORDERS_RD']],
            inherited)

    List joinCases = [['.', '.', '.'], ['.', 'db', 'db'], ['orders-db', 'src/main/resources', 'orders-db/src/main/resources'], ['.', './db', 'db'],
                      ['db/changelog', '../code/pkg.pkb', 'db/code/pkg.pkb'], ['db', '../../x', '../x'], ['', '', '.']]
    List joinWrong = mismatches(joinCases) { List c -> harness.run { ds().join(c[0], c[1]) } }
    List folderCases = [['db/changelog-master.xml', 'db'], ['orders/db/changelog.yaml', 'orders'], ['changelog.xml', ''], ['', '']]
    List folderWrong = mismatches(folderCases) { List c -> harness.run { ds().packageFolder(c[0]) } }
    String offline = harness.run { ds().offlineUrl('19.0', '/ws@tmp/dso-db/a b/x+y%z&w=v.csv') }
    check('DatabaseSettings.join, packageFolder and offlineUrl: "." parts dropped, ".." resolved, the first folder of the changelog, and %, +, &, = and blanks encoded',
            joinWrong.isEmpty() && folderWrong.isEmpty() && offline == 'offline:oracle?version=19.0&changeLogFile=/ws@tmp/dso-db/a%20b/x%2By%25z%26w%3Dv.csv',
            joinWrong + folderWrong + [offline])

    String descriptor = '(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=a.rd.bbh.com)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=ORDERS)))'
    String list = '(DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)(HOST=rd-db1.bbh.com)(PORT=1521))(ADDRESS=(PROTOCOL=TCP)(HOST=rd-db2.bbh.com)(PORT=1521)))(CONNECT_DATA=(SID=ORCL)))'
    String spaced = '(DESCRIPTION=(ADDRESS = (PROTOCOL = TCP)(HOST = rd-db.bbh.com)(PORT = 1521))(CONNECT_DATA = (SERVICE_NAME = ORDERS_RD)))'
    String dn = '//qc-db.bbh.com:2484/ORDERS_QC?ssl_server_cert_dn="CN=qc-db.bbh.com, OU=IT"&SSL_SERVER_DN_MATCH=true'
    Closure form = { String f, List hosts, String service, String connect -> [form: f, hosts: hosts, service: service, connect: connect] }
    List urlCases = [
            ['EZConnect', '//dev-db.bbh.com:1521/ORDERS_CI', form('ezconnect', ['dev-db.bbh.com'], 'ORDERS_CI', '//dev-db.bbh.com:1521/ORDERS_CI')],
            ['EZConnect without //', 'dev-db.bbh.com/ORDERS_CI', form('ezconnect', ['dev-db.bbh.com'], 'ORDERS_CI', 'dev-db.bbh.com/ORDERS_CI')],
            ['TCPS', 'tcps://qc-db.bbh.com:2484/ORDERS_QC?ssl_server_dn_match=true',
             form('ezconnect', ['qc-db.bbh.com'], 'ORDERS_QC', 'tcps://qc-db.bbh.com:2484/ORDERS_QC?ssl_server_dn_match=true')],
            ['two hosts and parameters', 'tcp://rd-db1.bbh.com:1521,rd-db2.bbh.com/ORDERS?connect_timeout=10&RETRY_COUNT=3',
             form('ezconnect', ['rd-db1.bbh.com', 'rd-db2.bbh.com'], 'ORDERS', 'tcp://rd-db1.bbh.com:1521,rd-db2.bbh.com/ORDERS?connect_timeout=10&RETRY_COUNT=3')],
            ['a quoted DN', dn, form('ezconnect', ['qc-db.bbh.com'], 'ORDERS_QC', dn)],
            ['SID', 'rd-db.bbh.com:1521:ORCL', form('sid', ['rd-db.bbh.com'], 'ORCL', 'rd-db.bbh.com:1521:ORCL')],
            ['descriptor', descriptor, form('descriptor', ['a.rd.bbh.com'], 'ORDERS', descriptor)],
            ['descriptor with two addresses and a SID', list, form('descriptor', ['rd-db1.bbh.com', 'rd-db2.bbh.com'], 'ORCL', list)],
            ['descriptor with blanks', spaced, form('descriptor', ['rd-db.bbh.com'], 'ORDERS_RD', spaced)]
    ].collect { List c -> [c[0], "jdbc:oracle:thin:@${c[1]}".toString(), c[2]] } + [
            ['a user before @', 'jdbc:oracle:thin:scott/tiger@//h.bbh.com:1521/S', [problem: 'V5']],
            ['a second @', 'jdbc:oracle:thin:@//scott:tiger@h.bbh.com:1521/S', [problem: 'V5']],
            ['user=', 'jdbc:oracle:thin:@//h.bbh.com:1521/S?user=scott', [problem: 'V5']],
            ['PASSWORD=', 'jdbc:oracle:thin:@//h.bbh.com:1521/S?PASSWORD=x', [problem: 'V5']],
            ['pwd=', 'jdbc:oracle:thin:@//h.bbh.com:1521/S?pwd=x', [problem: 'V5']],
            ['a user in a descriptor', "jdbc:oracle:thin:@${descriptor.replace('(PORT=2484)', '(PORT=2484)(USERNAME=scott)')}".toString(), [problem: 'V5']],
            ['a TNS alias', 'jdbc:oracle:thin:@ORDERS', [problem: 'V6']],
            ['another driver', 'jdbc:mysql://h.bbh.com/orders', [problem: 'V6']],
            ['empty', '', [problem: 'V6']],
            ['port 0', 'jdbc:oracle:thin:@//h.bbh.com:0/S', [problem: 'V6']],
            ['port 70000', 'jdbc:oracle:thin:@//h.bbh.com:70000/S', [problem: 'V6']],
            ['a SID port 99999', 'jdbc:oracle:thin:@h.bbh.com:99999:ORCL', [problem: 'V6']],
            ['IPv6', 'jdbc:oracle:thin:@//[::1]:1521/S', [problem: 'V6']],
            ['an unknown parameter', 'jdbc:oracle:thin:@//h.bbh.com:1521/S?foo=bar', [problem: 'V6']],
            ['no service', 'jdbc:oracle:thin:@//h.bbh.com:1521/', [problem: 'V6']],
            ['a semicolon', 'jdbc:oracle:thin:@//h.bbh.com:1521/S;x', [problem: 'V6']],
            ['an unbalanced descriptor', 'jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=h.bbh.com)(PORT=1521))', [problem: 'V6']],
            ['a descriptor and more', 'jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=h.bbh.com)))(X=1)', [problem: 'V6']],
            ['a descriptor without a host', 'jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_DATA=(SERVICE_NAME=S)))', [problem: 'V6']]
    ]
    List urlWrong = mismatches(urlCases) { List c -> harness.run { ds().parseUrl(c[1]) } }
    check('DatabaseSettings.parseUrl: every form of 4.3 gives its hosts and service, and every refusal gives V5 or V6', urlWrong.isEmpty(), urlWrong)

    List allowCases = [
            ['a host glob', ['rd-db*.bbh.com'], '//RD-DB1.bbh.com:1521/ORDERS_RD', true],
            ['another service', ['qc-db.bbh.com/ORDERS_QC'], '//qc-db.bbh.com:1521/ORDERS_RD', false],
            ['one host of two', ['rd-db1.bbh.com'], 'rd-db1.bbh.com:1521,rd-db2.bbh.com:1521/ORDERS', false],
            ['both hosts', ['rd-db1.bbh.com', 'rd-db2.bbh.com'], 'rd-db1.bbh.com:1521,rd-db2.bbh.com:1521/ORDERS', true],
            ['a descriptor', ['*.rd.bbh.com'], descriptor, true],
            ['a service glob in any case', ['qc-db.bbh.com/orders_*'], '//qc-db.bbh.com:1521/ORDERS_QC', true],
            ['a dot is a dot', ['rd-db.bbh.com'], '//rd-dbxbbh.com:1521/ORDERS', false],
            ['no entries', [], '//rd-db.bbh.com:1521/ORDERS', false]
    ]
    List allowWrong = mismatches(allowCases) { List c -> harness.run { ds().allowed(ds().parseUrl("jdbc:oracle:thin:@${c[2]}".toString()), c[1] as List) } }
    check('DatabaseSettings.allowed: the examples of 4.3', allowWrong.isEmpty(), allowWrong)

    List lockCases = [['jenkins-orders-db-pipeline-341', 'jenkins-orders-db-pipeline-341'], ['jenkins-BBH \u00bb orders/main #12', 'jenkins-BBH---orders-main--12'],
                      ['x' * 150, 'x' * 100], [null, 'dso']]
    List lockWrong = mismatches(lockCases) { List c -> harness.run { ds().lockId(c[0]) } }
    String properties = harness.run { ds().propertiesText([b: 'x\\y', 'liquibase.command.url': 'jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD', a: null]) }
    check('DatabaseSettings.lockId and propertiesText: other characters become "-" and the id is cut to 100; keys sorted and "\\" doubled',
            lockWrong.isEmpty() && properties == 'a=\nb=x\\\\y\nliquibase.command.url=jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD\n' && harness.run { ds().propertiesText(null) } == '',
            lockWrong + [properties])

    Map labelled = baseCfg()
    (labelled.database as Map).putAll([labels: 'release-1', defaultSchema: 'ORDERS_OWNER', liquibaseSchema: 'ORDERS_OWNER'])
    String manifest = harness.run { ds().manifest(ds().resolve('orders-db', labelled), '341-20261031-101500', '4.33.0') }
    check('DatabaseSettings.manifest: equal to test/fixtures/database/dso-deploy.properties', manifest == fixture('dso-deploy.properties'), manifest)

    List sqlCases = [
            ['invalid-before-default.sql', { -> ds().invalidBeforeSql([defaultSchema: ''], [ignoreObjects: []]) }],
            ['compile-check-default.sql', { -> ds().compileCheckSql([:], [recompile: true, ignoreObjects: []]) }],
            ['compile-check-schema.sql', { -> ds().compileCheckSql([defaultSchema: 'orders_owner'], [recompile: true]) }],
            ['compile-check-norecompile.sql', { -> ds().compileCheckSql([:], [recompile: false]) }],
            ['compile-check-ignore.sql', { -> ds().compileCheckSql([:], [ignoreObjects: ['V_LEGACY_REPORT', 'PKG_OLD']]) }],
            ['test-reset-default.sql', { -> ds().resetSql([:]) }],
            ['test-reset-schema.sql', { -> ds().resetSql([defaultSchema: 'orders_owner']) }]
    ]
    List sqlWrong = sqlCases.collect { List c -> [c[0], harness.run { (c[1] as Closure).call() }] }.findAll { List r -> r[1] != fixture(r[0] as String) || (r[1] as String).contains('${') }
    String schemaBefore = harness.run { ds().invalidBeforeSql([defaultSchema: 'ORDERS_OWNER'], [ignoreObjects: ['PKG_OLD']]) }
    check('DatabaseSettings.invalidBeforeSql, compileCheckSql and resetSql: equal to the fixtures for the default, a schema, no recompile and ignored objects',
            sqlWrong.isEmpty() && schemaBefore == "SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_BEFORE FROM all_objects WHERE owner = 'ORDERS_OWNER' AND status = 'INVALID' AND object_name NOT IN ('PKG_OLD') ORDER BY 1\n/\n",
            sqlWrong + [schemaBefore])

    List filterCases = [['', true], ['test', true], ['TEST', true], ['test, rd', true], ['rd or test', true], ['!rd', true], ['!dev and !rd', true], ['(test)', true],
                        ['!nocontexts', true], ['!test', false], ['! test', false], ['not test', false], ['!test and !rd', false], ['(rd and !test)', false],
                        ['rd', false], ['test-heavy', false], ['@test', true], ['rd and test', false], ['()', true]]
    List filterWrong = mismatches(filterCases) { List c -> harness.run { ds().selectsTest(c[0]) } }
    check('DatabaseSettings.selectsTest: the case table of 9.4, and an unparsable filter counts as selecting', filterWrong.isEmpty(), filterWrong)

    Map minimal = harness.run { ds().resolve('svc', [buildTool: 'liquibase', database: [changelogFile: 'changelog.xml', searchPath: 'db']]) } as Map
    List summaries = harness.run { [ds().summary('orders-db', ds().resolve('orders-db', baseCfg())), ds().summary('svc', minimal)] } as List
    check('DatabaseSettings.summary: the M-DB-03 text with and without a package folder and targets',
            summaries == ['[DB] orders-db: Liquibase service with a database block - changelog db/changelog-master.xml in ., package folder db, test database yes, RD yes, QC yes',
                          '[DB] svc: Liquibase service with a database block - changelog changelog.xml in db, package folder the whole search root, test database no, RD no, QC no'],
            summaries)

    File layout = new File(tmp, 'layout-a')
    new File(fixtures, 'layout-a').eachFileRecurse { File f -> if (f.file) writeTree(layout, [(f.path.substring(new File(fixtures, 'layout-a').path.length() + 1)): f.getText('UTF-8')]) }
    Map layoutSettings = harness.run { ds().resolve('orders-db', baseCfg()) } as Map
    String filesScript = harness.run { ds().filesCheckScript(layoutSettings) }
    Map present = bash(filesScript, [:], layout)
    new File(layout, 'db/test').deleteDir()
    Map missing = bash(filesScript, [:], layout)
    List missingTexts = harness.run { [ds().missingFiles(layoutSettings, present.out as String), ds().missingFiles(layoutSettings, missing.out as String)] } as List
    check('DatabaseSettings.filesCheckScript: bash finds the changelog and the utPLSQL folders of layout a, and a removed folder gives V43',
            present.status == 0 && missing.status == 0 && missingTexts == [[], ['database.test.utplsql.testPath db/test is not a folder under . in this checkout']],
            [present, missing, missingTexts])
}
settings()

Closure parsers = {
    String v433 = '''Starting Liquibase at 23:38:57 using Java 21.0.12.1 (version 4.33.0 #8744 built at 2025-07-08 20:43+0000)
Liquibase Version: 4.33.0
Liquibase Open Source 4.33.0 by Liquibase
No validation errors found.
Liquibase command 'validate' was executed successfully.
'''
    String v504 = '''Starting Liquibase at 23:38:28 using Java 21.0.12.1 (version 5.0.4 #11282 built at 2026-08-18 12:10:05 UTC)
Liquibase Version: 5.0.4

No validation errors found.
Liquibase command 'validate' was executed successfully.
'''
    List versions = harness.run {
        [lo().version(v433), lo().version(v504), lo().version('Liquibase command failed'), lo().licence('4.33.0'), lo().licence('5.0.4'), lo().licence('3.10.3'),
         lo().compare('4.33.0', '4.33'), lo().compare('4.9.1', '4.33.0'), lo().compare('5.0.4', '4.33.0'), lo().compare('6.0.0', '5.99')]
    } as List
    check('LiquibaseOutput.version, licence and compare: the 4.33.0 and 5.0.4 texts, the two licences, and a numeric comparison',
            versions == ['4.33.0', '5.0.4', '', 'Apache-2.0 (core; bundle includes third-party and commercial components)', 'FSL-1.1-ALv2', '', 0, -1, 1, 1], versions)

    Closure failed = { String reason, String tail = '' -> "Starting Liquibase at 23:50:11 using Java 21.0.12.1 (version 5.0.4 #11282 built at 2026-08-18 12:10:05 UTC)\nLiquibase Version: 5.0.4\n\nERROR: Exception Details\nERROR: Exception Primary Reason:  x\n\nUnexpected error running Liquibase: ${reason}\n${tail}\nFor more information, please use the --log-level flag\n".toString() }
    String checksum = 'db/tables/001-create-orders.sql::001-create-orders-idx::orders-team was: 9:e6c4133fbc5666d9b9dcf08e7c86a390 but is now: 9:e3df5f8c791313f6920abde45f9636c6'
    String lock = 'Could not acquire change log lock.  Currently locked by jenkins-agent-07 (10.20.30.40) since 10/30/26, 11:48 PM'
    String connect = 'Connection could not be created to jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1 with driver oracle.jdbc.OracleDriver.  ORA-12541: Cannot connect. No listener at host 127.0.0.1 port 1521.'
    String pro = "Error parsing command line: Using 'include in Formatted SQL changelog' requires a valid Liquibase license key. Get a Liquibase license key and free trial at https://liquibase.com/trial."
    String xml = "Error parsing line 13 column 76 of db/changelog-master.xml: cvc-complex-type.2.4.a: Invalid content was found starting with element '{\"http://www.liquibase.org/xml/ns/dbchangelog\":changeSet}'. One of '{...}' is expected."
    List reasonCases = [
            ['E1', failed("Unexpected formatting in formatted changelog 'db/tables/002-create-order-items.sql' at line 3.",
                    "Formatted SQL changelogs require known formats, such as '--changeset <authorname>:<changesetId>' and others to be recognized and run.\n"),
             "Unexpected formatting in formatted changelog 'db/tables/002-create-order-items.sql' at line 3."],
            ['E2', failed('Validation Failed:', '     1 changesets had duplicate identifiers\n          db/tables/002-create-order-items.sql::002-create-order-items::orders-team\n'),
             'Validation Failed: 1 changesets had duplicate identifiers db/tables/002-create-order-items.sql::002-create-order-items::orders-team'],
            ['E3', failed('Validation Failed:', "     1 changes have validation failures\n          The file code/pkg_orderz.pkb was not found in the configured search path:\n    - /ws\n    - /opt/liquibase/4.33.0/lib\nMore locations can be added with the 'searchPath' parameter., db/changelog-master.xml::pkg_orders.pkb::orders-team\n"),
             'Validation Failed: 1 changes have validation failures The file code/pkg_orderz.pkb was not found in the configured search path: - /ws - /opt/liquibase/4.33.0/lib'],
            ['E4', failed(pro), pro],
            ['E5', failed(xml), xml],
            ['checksum', failed('Validation Failed:', "     1 changesets check sum\n          ${checksum}\n"), "Validation Failed: 1 changesets check sum ${checksum}".toString()],
            ['lock', 'Waiting for changelog lock....\n' + failed(lock), lock],
            ['connect', failed(connect, 'https://docs.oracle.com/error-help/db/ora-12541/\n  - Caused by: Connection refused\n'), connect],
            ['no error line', "Liquibase Version: 5.0.4\nERROR: something else\n", '']
    ]
    List reasonWrong = mismatches(reasonCases) { List c -> harness.run { lo().errorReason(c[1]) } }
    List fallback = harness.run {
        [lo().reason("first\nlast line   \n\n"), lo().lastLine('x' * 400, 300).length(), lo().reason(failed(lock)),
         lo().lastLine('Could not establish connection to database.\nhttps://docs.oracle.com/error-help/db/ora-12541/\n', 200)]
    } as List
    check('LiquibaseOutput.errorReason and reason: E1-E5, checksum, lock and connect texts; without the error line the last non-blank line that is not an Oracle help link',
            reasonWrong.isEmpty() && fallback == ['last line', 300, lock, 'Could not establish connection to database.'], reasonWrong + [fallback])

    List hintCases = [
            ['H1', 'Validation Failed: 1 changesets check sum', 'an applied changeset was edited: undo the edit and add a new changeset, or mark the changeset runOnChange="true" when it holds a CREATE OR REPLACE object'],
            ['H2', lock, 'another deployment holds the Liquibase lock of this schema: wait for it to end; if none runs, ask the DBA to check DATABASECHANGELOGLOCK before anyone runs liquibase release-locks'],
            ['H3', 'Cannot find database driver: oracle.jdbc.OracleDriver', 'set tools.oracleJdbc in the Global Settings of the DevSecOps portal'],
            ['H4', connect, 'check the JDBC URL of the target, the network route from the agent to the database and the Jenkins credentials of the target'],
            ['H5', 'The file code/pkg_orderz.pkb was not found in the configured search path:', 'the changelog or a file it uses is outside the search root: check database.changelogFile, database.searchPath and the path attributes of the changelog'],
            ['H6', pro, 'the changelog uses a Liquibase Pro feature, which the library does not support (for example include inside formatted SQL): remove it'],
            ['H7', 'ORA-04021: timeout occurred while waiting to lock object', 'the object is in use by application sessions: deploy QC when the application is idle (chapter 14.8)'],
            ['H8', 'ORA-00955: name is already used by an existing object', 'the object already exists: for a schema that existed before Liquibase, the DBA marks the existing changesets as run with changelog-sync, or the changeset gets a MARK_RAN precondition (chapter 14.8)'],
            ['H9', 'ORA-01017: invalid credential or not authorized; logon denied', 'the user or the password of the Jenkins credentials of the target is wrong'],
            ['H10', 'ORA-01031: insufficient privileges', 'the deploy user lacks a privilege: see the grants in chapter 14.8'],
            ['H11 ORA-00900', 'ORA-00900: invalid SQL statement', 'the statement reached Oracle with a SQL*Plus command, a ";" or a split in the wrong place: check splitStatements, endDelimiter and the static checks for SQL*Plus commands and "/" lines (chapter 14.4)'],
            ['H11 ORA-00911', 'ORA-00911: invalid character', 'the statement reached Oracle with a SQL*Plus command, a ";" or a split in the wrong place: check splitStatements, endDelimiter and the static checks for SQL*Plus commands and "/" lines (chapter 14.4)'],
            ['H11 ORA-00933', 'ORA-00933: SQL command not properly ended', 'the statement reached Oracle with a SQL*Plus command, a ";" or a split in the wrong place: check splitStatements, endDelimiter and the static checks for SQL*Plus commands and "/" lines (chapter 14.4)'],
            ['none', xml, ''], ['empty', null, '']
    ]
    List hintWrong = mismatches(hintCases) { List c -> harness.run { lo().hint(c[1]) } }
    List hintTexts = harness.run { [lo().hintText('ORA-01031: insufficient privileges'), lo().hintText(xml)] } as List
    check('LiquibaseOutput.hint: H1-H11 of 18.5 and none, and hintText joins it with " - "',
            hintWrong.isEmpty() && hintTexts == [' - the deploy user lacks a privilege: see the grants in chapter 14.8', ''], hintWrong + [hintTexts])

    List ids = ['db/tables/001-create-orders.sql::001-create-orders-seq::orders-team', 'db/tables/001-create-orders.sql::001-create-orders::orders-team',
                'db/tables/001-create-orders.sql::001-create-orders-idx::orders-team', 'db/tables/002-create-order-items.sql::002-create-order-items::orders-team',
                'db/changelog-master.xml::pkg_orders.pks::orders-team', 'db/changelog-master.xml::pkg_orders.pkb::orders-team',
                'db/changelog-master.xml::v_order_totals.vw::orders-team', 'db/changelog-master.xml::trg_orders_bi.trg::orders-team',
                'db/changelog-master.xml::ut_pkg_orders.pks::orders-team', 'db/changelog-master.xml::ut_pkg_orders.pkb::orders-team']
    String sql = ids.collect { "-- Changeset ${it}\nCREATE TABLE x (id NUMBER);\n\nINSERT INTO DATABASECHANGELOG (ID) VALUES ('x');\n" }.join('\n')
    String review = ids.take(8).collect { "-- Changeset ${it}\n-- not a changeset line\n" }.join('')
    List counts = harness.run { [lo().changesets(sql), lo().changesets(review), lo().changesets('')] } as List
    check('LiquibaseOutput.changesets: 10 for the update-sql of layout a and 8 for its review SQL', counts == [10, 8, 0], counts)

    String db = 'SA@jdbc:h2:file:/ws/h2/h504'
    List statusCases = [
            ['pending 10', "${ids.size()} changesets have not been applied to ${db}\n${ids.collect { '     ' + it }.join('\n')}\nLiquibase command 'status' was executed successfully.\n",
             [parsed: true, pending: 10, ids: ids]],
            ['pending 1', "1 changeset has not been applied to ${db}\n     db/changelog-master.xml::pkg_orders.pkb::orders-team\nLiquibase command 'status' was executed successfully.\n",
             [parsed: true, pending: 1, ids: ['db/changelog-master.xml::pkg_orders.pkb::orders-team']]],
            ['up to date', "${db} is up to date\nLiquibase command 'status' was executed successfully.\n", [parsed: true, pending: 0, ids: []]],
            ['unparsable', failed(connect), [parsed: false, pending: 0, ids: []]]
    ]
    List statusWrong = mismatches(statusCases) { List c -> harness.run { lo().status(c[1].toString()) } }
    check('LiquibaseOutput.status: pending 10 with their ids, pending 1, up to date and an unparsable text', statusWrong.isEmpty(), statusWrong)

    List ran = ids.take(8)
    String first = ran.collect { "Running Changeset: ${it}" }.join('\n') + '''

UPDATE SUMMARY
Run:                          8
Previously run:               0
Filtered out:                 2
-------------------------------
Total change sets:           10


FILTERED CHANGE SETS SUMMARY
Context mismatch:             2

Liquibase: Update has been successful. Rows affected: 0
Liquibase command 'update' was executed successfully.
'''
    String rerun = '''Running Changeset: db/changelog-master.xml::pkg_orders.pkb::orders-team

UPDATE SUMMARY
Run:                          1
Previously run:               7
Filtered out:                 2
-------------------------------
Total change sets:           10
'''
    List updates = harness.run { [lo().update(first), lo().update(rerun), lo().update(failed(lock))] } as List
    check('LiquibaseOutput.update: the first update of probe 2.9 and the rerun of probe 2.10',
            updates == [[parsed: true, run: 8, previouslyRun: 0, filteredOut: 2, total: 10, ran: ran],
                        [parsed: true, run: 1, previouslyRun: 7, filteredOut: 2, total: 10, ran: ['db/changelog-master.xml::pkg_orders.pkb::orders-team']],
                        [parsed: false, run: 0, previouslyRun: 0, filteredOut: 0, total: 0, ran: []]], updates)

    String select = "SELECT object_type || ' ' || object_name"
    Closure block = { String column, String from, List rows ->
        "Output of ${select} AS ${column} ${from}:\n" + (rows ? "${column} |\n" + rows.collect { "${it} | " }.join('\n') + '\n' : '-- Empty Resultset --\n') + '\n'
    }
    Closure before = { List rows -> block('INVALID_BEFORE', "FROM user_objects WHERE status = 'INVALID' ORDER BY 1", rows) }
    Closure after = { List updated, List invalid, List errors ->
        block('INVALID_UPDATED', "FROM user_objects WHERE status = 'INVALID' ORDER BY 1", updated) +
                "Successfully Executed: \nBEGIN\n  DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE, reuse_settings => TRUE);\nEND;\n" +
                block('INVALID_OBJECT', "FROM user_objects WHERE status = 'INVALID' ORDER BY 1", invalid) +
                block('COMPILE_ERROR', "FROM user_errors WHERE attribute = 'ERROR' ORDER BY type, name, sequence", errors)
    }
    String pkgError = "PACKAGE BODY PKG_ORDERS line 12: PLS-00201: identifier 'ORDER_ITEMZ' must be declared"
    String viewError = 'VIEW V_LEGACY_REPORT line 0: ORA-00942: table or view does not exist'
    String oldView = 'VIEW V_LEGACY_REPORT 20250101120000'
    List afterColumns = ['INVALID_UPDATED', 'INVALID_OBJECT', 'COMPILE_ERROR']
    List verdictCases = [
            ['ok', before([]), after([], [], []), true, [failing: [], preexisting: [], errors: []]],
            ['a new invalid object', before([]), after(['PACKAGE BODY PKG_ORDERS 20261031101502'], ['PACKAGE BODY PKG_ORDERS'], [pkgError]), true,
             [failing: ['PACKAGE BODY PKG_ORDERS'], preexisting: [], errors: [pkgError]]],
            ['pre-existing only', before([oldView]), after([oldView], ['VIEW V_LEGACY_REPORT'], [viewError]), true,
             [failing: [], preexisting: ['VIEW V_LEGACY_REPORT'], errors: []]],
            ['a redefined object that was invalid before', before([oldView]), after(['VIEW V_LEGACY_REPORT 20261031101503'], ['VIEW V_LEGACY_REPORT'], [viewError]), true,
             [failing: ['VIEW V_LEGACY_REPORT'], preexisting: [], errors: [viewError]]],
            ['no baseline', before([oldView]), after([oldView, 'PACKAGE BODY PKG_ORDERS 20261031101502'], ['PACKAGE BODY PKG_ORDERS', 'VIEW V_LEGACY_REPORT'], [pkgError, viewError]), false,
             [failing: ['PACKAGE BODY PKG_ORDERS', 'VIEW V_LEGACY_REPORT'], preexisting: [], errors: [pkgError, viewError]]]
    ]
    List verdictWrong = mismatches(verdictCases) { List c ->
        harness.run { lo().compileVerdict(lo().resultSets(c[1], ['INVALID_BEFORE']), lo().resultSets(c[2], afterColumns), c[3] as boolean) }
    }
    List parsedFlags = harness.run {
        [lo().resultSets(after([oldView], [], []), afterColumns).parsed, lo().resultSets(before([oldView]) + block('INVALID_OBJECT', 'FROM user_objects', []), afterColumns).parsed,
         lo().resultSets(before([oldView]), ['INVALID_BEFORE']), lo().resultSets("Output of ${select} AS INVALID_BEFORE FROM user_objects:\nERROR\n".toString(), ['INVALID_BEFORE']).parsed]
    } as List
    check('LiquibaseOutput.resultSets and compileVerdict: ok, a new invalid object, pre-existing only, a redefined object that was invalid before, no baseline; a missing header is not parsed',
            verdictWrong.isEmpty() && parsedFlags == [true, false, [parsed: true, INVALID_BEFORE: [oldView]], false], verdictWrong + [parsedFlags])

    List pending = harness.run {
        [lo().pendingAfter(['db/changelog-master.xml::grants::orders-team', 'db/changelog-master.xml::stats::orders-team'], ['db/changelog-master.xml::stats::orders-team']),
         lo().pendingAfter(['a', 'b'], [])]
    } as List
    List unexpectedCases = [
            ['two', "2 unexpected changes were found in ${db}\n     db/changelog-master.xml::old-1::orders-team\n     db/changelog-master.xml::old-2::orders-team\nLiquibase command 'unexpected-changesets' was executed successfully.\n",
             [parsed: true, count: 2, ids: ['db/changelog-master.xml::old-1::orders-team', 'db/changelog-master.xml::old-2::orders-team']]],
            ['one', "1 unexpected change was found in ${db}\n     db/changelog-master.xml::old-1::orders-team\n", [parsed: true, count: 1, ids: ['db/changelog-master.xml::old-1::orders-team']]],
            ['none', "${db} contains no unexpected changes!\nLiquibase command 'unexpected-changesets' was executed successfully.\n", [parsed: true, count: 0, ids: []]],
            ['unparsable', failed(connect), [parsed: false, count: 0, ids: []]]
    ]
    List unexpectedWrong = mismatches(unexpectedCases) { List c -> harness.run { lo().unexpected(c[1].toString()) } }
    String locks = 'Database change log locks for SA@jdbc:h2:file:/ws/h2/h504\n - vm07#jenkins-orders-db-341 (10.20.30.40) at Oct 30, 2026, 11:47:43 PM\n'
    List lockFlags = harness.run { [lo().lockOf(locks, 'jenkins-orders-db-341'), lo().lockOf(locks, 'jenkins-orders-db-34'), lo().lockOf(' - No locks\n', 'jenkins-orders-db-341')] } as List
    check('LiquibaseOutput.pendingAfter, unexpected and lockOf: a runAlways changeset that ran is not pending; the exp5 texts; only this build\'s lock',
            pending == [['db/changelog-master.xml::grants::orders-team'], ['a', 'b']] && unexpectedWrong.isEmpty() && lockFlags == [true, false, false],
            [pending, unexpectedWrong, lockFlags])

    String cobertura = '''<?xml version="1.0"?>
<!DOCTYPE coverage SYSTEM "http://cobertura.sourceforge.net/xml/coverage-04.dtd">
<coverage line-rate="0.8" branch-rate="0.0" lines-covered="40" lines-valid="50" branches-covered="0" branches-valid="0" complexity="0" version="1" timestamp="1793442900000">
<sources>
<source>db/code</source>
</sources>
</coverage>
'''
    List coverage = harness.run {
        [lo().cobertura(cobertura), lo().cobertura('<coverage line-rate="0.8" lines-covered="x">'), lo().cobertura(''),
         lo().sonarLanguages('{"languages":[{"key":"java","name":"Java"},{"key":"plsql","name":"PL/SQL"}]}').analyzer,
         lo().sonarLanguages('{"languages":[{"key":"plsqlopen","name":"PL/SQL (ZPA)"}]}').analyzer, lo().sonarLanguages('{"languages":[{"key":"java","name":"Java"}]}').analyzer]
    } as List
    check('LiquibaseOutput.cobertura and sonarLanguages: a constructed Cobertura file, a broken one, and the PL/SQL analyzers',
            coverage == [[parsed: true, covered: 40, valid: 50], [parsed: false, covered: 0, valid: 0], [parsed: false, covered: 0, valid: 0], 'plsql', 'plsqlopen', ''], coverage)

    String yamlChangelog = '''databaseChangeLog:
  - changeSet:
      id: pkg
      author: orders-team
      changes:
        - sqlFile:
            path: code/pkg.pkb
            relativeToChangelogFile: true
            splitStatements: false
            stripComments: false
        - sqlFile: {path: code/v.vw, relativeToChangelogFile: true, endDelimiter: "\\n/"}
  - changeSet:
      id: grants
      author: orders-team
      changes:
        - sqlFile:
            path: "db/grants.sql"
'''
    String jsonChangelog = '''{"databaseChangeLog": [
  {"changeSet": {"id": "pkg", "author": "orders-team", "changes": [
    {"sqlFile": {"path": "code/pkg.pkb", "relativeToChangelogFile": true, "splitStatements": false, "stripComments": true}}
  ]}}
]}
'''
    Closure numbered = { String text -> text.readLines().withIndex().collect { String line, int i -> [i + 1, line] } }
    Closure reference = { String changelog, String path, String file, boolean split, boolean strip, String delimiter, Map attributes ->
        [path: path] + attributes + [changelog: changelog, file: file, split: split, strip: strip, delimiter: delimiter]
    }
    List refs = harness.run { [lo().sqlFiles('db/changelog.yaml', numbered(yamlChangelog)), lo().sqlFiles('db/changelog.json', numbered(jsonChangelog))] } as List
    check('LiquibaseOutput.sqlFiles: YAML blocks and one-line maps and JSON give the path, its file under the search root and the split, strip and delimiter attributes',
            refs == [[reference('db/changelog.yaml', 'code/pkg.pkb', 'db/code/pkg.pkb', false, false, '', [relativeToChangelogFile: 'true', splitStatements: 'false', stripComments: 'false']),
                      reference('db/changelog.yaml', 'code/v.vw', 'db/code/v.vw', true, false, '\\n/', [relativeToChangelogFile: 'true', endDelimiter: '\\n/']),
                      reference('db/changelog.yaml', 'db/grants.sql', 'db/grants.sql', true, false, '', [:])],
                     [reference('db/changelog.json', 'code/pkg.pkb', 'db/code/pkg.pkb', false, true, '', [relativeToChangelogFile: 'true', splitStatements: 'false', stripComments: 'true'])]],
            refs)
}
parsers()

Closure staticChecks = {
    Map planted = [
            'db/changelog.xml'  : '''<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
    <changeSet id="a" author="t" runOnChange="true">
        <sqlFile path="code/a.pks" relativeToChangelogFile="true" stripComments="false"/>
    </changeSet>
    <changeSet id="b" author="t" runOnChange="true">
        <sqlFile path="db/code/b.pkb" splitStatements="false" stripComments="true"/>
    </changeSet>
    <changeSet id="v" author="t" runOnChange="true">
        <sqlFile path="code/v.vw" relativeToChangelogFile="true" splitStatements="false"/>
    </changeSet>
    <changeSet id="two" author="t" runOnChange="true">
        <sqlFile path="code/two.pck" relativeToChangelogFile="true" splitStatements="false" endDelimiter="\\n/"/>
    </changeSet>
    <changeSet id="mid" author="t">
        <sqlFile path="code/mid.pkb" relativeToChangelogFile="true" splitStatements="false"/>
    </changeSet>
    <changeSet id="end" author="t">
        <sqlFile path="code/end.pkb" relativeToChangelogFile="true" splitStatements="false"/>
    </changeSet>
    <changeSet id="user" author="t">
        <sqlFile path="code/user.sql" relativeToChangelogFile="true"/>
    </changeSet>
    <include file="tables/f.sql" relativeToChangelogFile="true"/>
    <changeSet id="cmd" author="t">
        <executeCommand executable="/bin/true"/>
    </changeSet>
    <changeSet id="p" author="t">
        <sql>GRANT SELECT ON orders TO ${app_role}</sql>
        <sql>CALL audit('${db_password}')</sql>
    </changeSet>
    <changeSet id="block" author="t">
        <sqlFile path="code/block.sql" relativeToChangelogFile="true" splitStatements="false"/>
    </changeSet>
</databaseChangeLog>
''',
            'db/code/a.pks'     : 'CREATE OR REPLACE PACKAGE a AS\n  PROCEDURE p;\nEND a;\n/\n',
            'db/code/b.pkb'     : 'CREATE OR REPLACE PACKAGE BODY b AS\n  PROCEDURE p IS\n  BEGIN\n    NULL;\n  END p;\nEND b;\n-- SET DEFINE OFF\nSHOW ERRORS\nSET timing = 1\nSET DEFINE OFF\n\n',
            'db/code/v.vw'      : 'CREATE OR REPLACE VIEW v AS\nSELECT 1 AS n FROM dual;\n',
            'db/code/two.pck'   : 'CREATE OR REPLACE PACKAGE two AS\n  PROCEDURE p;\nEND two;\n/\nCREATE OR REPLACE PACKAGE BODY two AS\n  PROCEDURE p IS BEGIN NULL; END p;\nEND two;\n/\n',
            'db/code/mid.pkb'   : 'CREATE OR REPLACE PACKAGE BODY mid AS\n  PROCEDURE p IS BEGIN NULL; END p;\nEND mid;\n/\nCREATE OR REPLACE PROCEDURE mid_extra IS BEGIN NULL; END;\n',
            'db/code/end.pkb'   : 'CREATE OR REPLACE PACKAGE BODY e AS\n  PROCEDURE p IS BEGIN NULL; END p;\nEND e; /\n',
            'db/code/user.sql'  : 'CREATE USER app IDENTIFIED BY "secret1";\nALTER USER app IDENTIFIED BY VALUES \'S:ABC\';\nGRANT CONNECT TO ${DSO_USER};\n' +
                    'EXECUTE IMMEDIATE \'ALTER USER \' || p_user || \' IDENTIFIED BY \' || p_pwd;\nEXECUTE IMMEDIATE \'ALTER USER app IDENTIFIED BY :new_pwd\';\n' +
                    'v_sql := \'CREATE USER \' || p_user || \' IDENTIFIED BY "\' || p_pwd || \'"\';\nEXECUTE IMMEDIATE \'ALTER USER app IDENTIFIED BY Secret2\';\n' +
                    'ALTER USER app IDENTIFIED BY\n  secret3;\nALTER USER app IDENTIFIED BY ${app_pass};\n',
            'db/code/orphan.prc': 'CREATE OR REPLACE PROCEDURE orphan IS BEGIN NULL; END;\n/\n',
            'db/code/block.sql' : 'BEGIN\n  NULL;\nEND;\n',
            'db/tables/f.sql'   : '--liquibase formatted sql\n--changeset t:f1\nCREATE TABLE f (id NUMBER);\n--changeset t:f2\nCREATE OR REPLACE PROCEDURE f_p IS\nBEGIN\n  NULL;\nEND;\n/\n--changeset t:f3 splitStatements:false stripComments:false\nBEGIN\n  NULL;\nEND;\n/\n'
    ]
    List expected = [
            'db/code/b.pkb line 8: SQL*Plus command "SHOW ERRORS" - Liquibase sends it to the database as SQL; remove it',
            'db/code/b.pkb line 10: SQL*Plus command "SET DEFINE OFF" - Liquibase sends it to the database as SQL; remove it',
            'db/code/mid.pkb line 4: a "/" line before the end of the file - one sqlFile deploys one unit: put each unit in its own file, or give the sqlFile endDelimiter="\\n/"',
            'db/code/end.pkb line 3: "/" on the line of END; - put the "/" on a line of its own',
            'db/changelog.xml: sqlFile code/a.pks has no splitStatements="false" - Liquibase splits a PL/SQL unit at every ";"',
            'db/tables/f.sql line 4: changeset f2 holds a PL/SQL unit but no splitStatements:false - Liquibase splits it at every ";"',
            'db/changelog.xml: sqlFile db/code/b.pkb has stripComments="true" - the comments of the unit, and the utPLSQL annotations, are not stored in the database',
            'db/tables/f.sql line 4: changeset f2 holds a PL/SQL unit without stripComments:false - its comments and utPLSQL annotations are removed',
            'db/changelog.xml: sqlFile code/v.vw has splitStatements="false" and its statement ends with ";" - Oracle refuses that for a view or plain SQL; remove splitStatements="false" or the final ";"',
            'db/code/orphan.prc is in no sqlFile of the changelog - includeAll does not deploy .prc files, so it is never deployed',
            'db/code/user.sql line 1: a password is written in the source (IDENTIFIED BY) - it would go into the package, Nexus and the review SQL; use IDENTIFIED EXTERNALLY or GLOBALLY, or let the DBA set the password',
            'db/code/user.sql line 7: a password is written in the source (IDENTIFIED BY) - it would go into the package, Nexus and the review SQL; use IDENTIFIED EXTERNALLY or GLOBALLY, or let the DBA set the password',
            'db/code/user.sql line 8: a password is written in the source (IDENTIFIED BY) - it would go into the package, Nexus and the review SQL; use IDENTIFIED EXTERNALLY or GLOBALLY, or let the DBA set the password',
            'db/code/user.sql line 10: a password is written in the source (IDENTIFIED BY) - it would go into the package, Nexus and the review SQL; use IDENTIFIED EXTERNALLY or GLOBALLY, or let the DBA set the password',
            'db/changelog.xml line 26: executeCommand runs code on the Jenkins agent - the library does not deploy it',
            'db/changelog.xml line 30: ${db_password} names a secret or a variable of the agent - use database.parameters for values that differ between databases, with a name that holds no password, passwd, pwd, secret, token, credential, key or user and does not start with LIQUIBASE_, DSO_ or JENKINS',
            'db/code/user.sql line 3: ${DSO_USER} names a secret or a variable of the agent - use database.parameters for values that differ between databases, with a name that holds no password, passwd, pwd, secret, token, credential, key or user and does not start with LIQUIBASE_, DSO_ or JENKINS'
    ]
    Closure superset = { Map tree ->
        List paths = tree.keySet().toList().sort()
        List records = paths.collect { "F\t${it}" }
        paths.each { String path ->
            List lines = (tree[path] as String).readLines()
            lines.eachWithIndex { String line, int i -> records << "L\t${path}\t${i + 1}\t${line}" }
            int last = lines.findLastIndexOf { it.trim() }
            records << "E\t${path}\t${last + 1}\t${last >= 0 ? lines[last] : ''}"
        }
        records.join('\n') + '\n'
    }
    Closure realFacts = { String name, Map tree, String folder ->
        File dir = new File(tmp, name)
        File work = new File(tmp, "${name}-tmp")
        work.mkdirs()
        writeTree(dir, tree)
        new File(work, 'static-facts.awk').setText(harness.run { lo().factsAwk() } as String, 'UTF-8')
        Map ran = bash(harness.run { lo().factsScript(dir.path, folder, work.path) } as String, [:], tmp)
        [status: ran.status, out: ran.out, text: new File(work, 'static-facts.txt').getText('UTF-8')]
    }
    Map real = realFacts('planted', planted, 'db')
    Map none = [ignoreFiles: []]
    List findings = harness.run { [lo().staticFindings(lo().facts(superset(planted)), none), lo().staticFindings(lo().facts(real.text as String), none)] } as List
    check('LiquibaseOutput.staticFindings: every rule S1-S6 on the planted tree with its exact text, sorted by rule and file, and nothing for the negatives, such as IDENTIFIED BY with a variable, a bind or a concatenation',
            findings[0] == expected, [(findings[0] as List) - expected, expected - (findings[0] as List)])
    check('LiquibaseOutput.factsAwk and factsScript: the real awk facts give the findings of the full facts (the superset rule)',
            real.status == 0 && findings[1] == findings[0] && (real.text as String).readLines().findAll { it.startsWith('F\t') } == planted.keySet().toList().sort().collect { "F\t${it}".toString() },
            [real.status, real.out, findings[1]])

    List kept = harness.run { lo().staticFindings(lo().facts(superset(planted)), [ignoreFiles: ['db/code/b.pkb', 'db/code/user.sql', 'db/tables', 'db/code/orphan.prc']]) } as List
    check('LiquibaseOutput.staticFindings: database.checks.ignoreFiles removes S1-S4 of the files named, never S5 or S6',
            kept == expected.findAll { !(it.contains('b.pkb') || it.startsWith('db/tables/') || it.contains('orphan.prc')) || it.contains('IDENTIFIED BY') || it.contains('names a secret') },
            kept)

    Map layoutTree = [:]
    File layoutDir = new File(fixtures, 'layout-a')
    layoutDir.eachFileRecurse { File f -> if (f.file && f.path.contains('/db/')) layoutTree[f.path.substring(layoutDir.path.length() + 1)] = f.getText('UTF-8') }
    Map layoutReal = realFacts('layout-facts', layoutTree, 'db')
    List layoutFindings = harness.run { [lo().staticFindings(lo().facts(superset(layoutTree)), none), lo().staticFindings(lo().facts(layoutReal.text as String), none)] } as List
    List candidates = harness.run {
        [lo().candidate('db/code/x.pkb', 1, 'anything'), lo().candidate('db/changelog.xml', 9, 'anything'), lo().candidate('db/code/x.pkb', 9, '  SHOW ERRORS'),
         lo().candidate('db/code/x.pkb', 9, '    NULL;'), lo().blocks(expected[10] as String), lo().blocks(expected[0] as String)]
    } as List
    check('LiquibaseOutput.staticFindings: layout a has no finding from the full or the real facts, and candidate is the awk condition',
            layoutReal.status == 0 && layoutFindings == [[], []] && candidates == [true, true, true, false, true, false], [layoutReal.out, layoutFindings, candidates])
}
staticChecks()

Closure scripts = {
    String password = 'p\\a"b\'c@d/e'
    File work = new File(tmp, 'prelude')
    work.mkdirs()
    new File(work, 'rd.properties').setText('liquibase.command.url=jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD\nparameter.app_role=orders_app_role\n', 'UTF-8')
    String probe = '''cp "$DSO_STEP" "$DSO_TMP/copy.properties"
echo "$DSO_STEP" > "$DSO_TMP/step-name.txt"
env | LC_ALL=C sort > "$DSO_TMP/env.txt"
printf 'value %s and %s@db and %s@db\\n' "$DSO_P" "$DSO_U" "$DSO_UU" | dso_mask > "$DSO_TMP/masked.txt"
'''
    Map env = [DSO_DB_USER: 'app_user', DSO_DB_PASSWORD: password, DSO_TMP: work.path, DSO_PROPS: 'rd.properties', DSO_LOCK_ID: 'jenkins-orders-db-341',
               LIQUIBASE_COMMAND_URL: 'jdbc:h2:mem:other', LIQUIBASE_COMMAND_CHANGELOG_FILE: 'other.xml']
    Map ran = bash((harness.run { lo().onlinePrelude() } as String) + probe, env, work)
    List exported = new File(work, 'env.txt').readLines()
    String step = new File(work, 'step-name.txt').text.trim()
    Map facts = [status: ran.status, out: ran.out, copy: new File(work, 'copy.properties').text, masked: new File(work, 'masked.txt').text,
                 liquibase: exported.findAll { it.startsWith('LIQUIBASE_') }, dso: exported.findAll { it.startsWith('DSO_') && !it.startsWith('DSO_LOCK_ID=') },
                 secret: exported.findAll { it.contains(password) || it.contains('app_user') }, java: exported.findAll { it.startsWith('JAVA_OPTS=') },
                 stepGone: !new File(step).exists(), stepInTmp: step.startsWith(work.path + '/step.')]
    check('LiquibaseOutput.onlinePrelude in bash: the step file holds the escaped user and password and is removed, nothing secret or LIQUIBASE_* is exported, the output is masked',
            facts == [status: 0, out: '', copy: 'liquibase.command.url=jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD\nparameter.app_role=orders_app_role\nliquibase.command.username=app_user\nliquibase.command.password=p\\\\a"b\'c@d/e\n',
                      masked: 'value **** and ****@db and ****@db\n', liquibase: ['LIQUIBASE_ANALYTICS_ENABLED=false'], dso: [], secret: [],
                      java: ['JAVA_OPTS= -Dliquibase.hostDescription=jenkins-orders-db-341'], stepGone: true, stepInTmp: true],
            facts)

    List refusals = [['empty', ''], ['a leading blank', ' secret'], ['a trailing blank', 'secret '], ['a tab', 'sec\tret']].collect { List c ->
        [c[0], bash((harness.run { lo().onlinePrelude() } as String) + 'echo reached\n', env + [DSO_DB_PASSWORD: c[1]], work)]
    }.findAll { List r -> r[1].status != 64 || (r[1].out as String).contains('reached') }
    check('LiquibaseOutput.onlinePrelude in bash: a password that is empty, starts or ends with a blank or holds a control character gives exit code 64', refusals.isEmpty(), refusals)

    Map utEnv = [DSO_DB_USER: 'app_user', DSO_DB_PASSWORD: password, DSO_TMP: work.path, DSO_UT_CONNECT: '//dev-db.bbh.com:1521/ORDERS_CI']
    Map ut = bash((harness.run { lo().utplsqlScript() } as String) + 'cp "$DSO_ARGS" "$DSO_TMP/args.copy"\necho "$DSO_ARGS" > "$DSO_TMP/args-name.txt"\n', utEnv, work)
    String args = new File(work, 'args.copy').text
    boolean argsGone = !new File(new File(work, 'args-name.txt').text.trim()).exists()
    Map quoted = bash((harness.run { lo().utplsqlScript() } as String) + 'echo reached\n', utEnv + [DSO_DB_PASSWORD: '"quoted"'], work)
    check('LiquibaseOutput.utplsqlScript in bash: the argument file holds the quoted, escaped connect string and is removed; a password in double quotes gives exit code 64',
            ut.status == 0 && args == '"app_user/p\\\\a\\"b\'c@d/e@//dev-db.bbh.com:1521/ORDERS_CI"\n' && argsGone && quoted.status == 64 && !(quoted.out as String).contains('reached'),
            [ut, args, argsGone, quoted])

    File archive = new File(tmp, 'liquibase-4.33.0.tar.gz')
    File content = new File(tmp, 'archive')
    writeTree(content, [liquibase: '#!/bin/sh\necho liquibase\n'])
    bash("tar -czf '${archive.path}' -C '${content.path}' liquibase", [:], tmp)
    String sum = MessageDigest.getInstance('SHA-256').digest(archive.bytes).collect { String.format('%02x', it) }.join('')
    String download = harness.run { lo().downloadScript() }
    File toolDir = new File(tmp, 'tools/liquibase')
    Map dl = [DSO_TOOL_DIR: toolDir.path, DSO_TOOL_URL: "file://${archive.path}".toString(), DSO_TOOL_SHA256: sum]
    Map fetched = bash(download, dl, tmp)
    boolean unpacked = new File(toolDir, 'liquibase').canExecute() && new File(toolDir, '.sha256').text == sum && !new File(toolDir.path + '.download').exists()
    Map cached = bash(download, dl + [DSO_TOOL_URL: "file://${tmp.path}/missing.tar.gz".toString()], tmp)
    Map wrongSum = bash(download, dl + [DSO_TOOL_DIR: new File(tmp, 'tools/other').path, DSO_TOOL_SHA256: 'b' * 64], tmp)
    check('LiquibaseOutput.downloadScript in bash: a file URL with the right sha256 unpacks once and leaves the .sha256 marker; a wrong sha256 gives exit code 3',
            fetched.status == 0 && unpacked && cached.status == 0 && wrongSum.status == 3 && !new File(tmp, 'tools/other.download').exists(),
            [fetched, unpacked, cached, wrongSum])

    Map target = [name: 'rd', defaultSchema: 'ORDERS_OWNER', liquibaseSchema: 'LB_OWNER']
    String dir = '/ws@tmp/dso-db/orders-db'
    String zip = "${dir}/orders-db-341-20261031-101500.zip"
    Map common = [liquibase: '/opt/liquibase/4.33.0/liquibase', jar: '/opt/ojdbc/ojdbc11.jar', dir: dir, lockWait: 5, properties: "${dir}/offline.properties".toString()]
    List commands = harness.run {
        def runner = harness.type('com.bbh.build.LiquibaseRunner')
        [runner.liquibaseScript(common + [search: zip, target: target, lock: true, quiet: false, command: 'update', args: ['--changelog-file=db/changelog-master.xml', '--context-filter=!test'], out: 'rd-update.out']),
         runner.liquibaseScript(common + [jar: '', search: zip, target: [name: 'test'], lock: false, quiet: true, command: 'status', args: ['--verbose'], out: 'test-status.out']),
         runner.liquibaseScript(common + [search: '/ws/.', target: null, lock: true, command: 'execute-sql', args: ["--sql=SELECT 'x' FROM dual"], out: 'validate.out', csv: "${dir}/validate.csv".toString()])]
    } as List
    Map prelude = harness.run { [online: lo().onlinePrelude(), offline: lo().offlinePrelude()] } as Map
    check('LiquibaseRunner.liquibaseScript: the global options before the command, the lock wait only for a locking online step, the quiet and console sinks, and the offline form',
            commands == [prelude.online + "'/opt/liquibase/4.33.0/liquibase' \"--defaults-file=\$DSO_STEP\" '--show-banner=false' '--search-path=${zip}' '--classpath=/opt/ojdbc/ojdbc11.jar' '--default-schema-name=ORDERS_OWNER' '--liquibase-schema-name=LB_OWNER' '--changelog-lock-wait-time-in-minutes=5' 'update' '--changelog-file=db/changelog-master.xml' '--context-filter=!test' 2>&1 | dso_mask | tee '${dir}/rd-update.out'\n",
                         prelude.online + "'/opt/liquibase/4.33.0/liquibase' \"--defaults-file=\$DSO_STEP\" '--show-banner=false' '--search-path=${zip}' 'status' '--verbose' 2>&1 | dso_mask > '${dir}/test-status.out'\n",
                         prelude.offline + "rm -f '${dir}/validate.csv'\n'/opt/liquibase/4.33.0/liquibase' '--defaults-file=${dir}/offline.properties' '--show-banner=false' '--search-path=/ws/.' '--classpath=/opt/ojdbc/ojdbc11.jar' 'execute-sql' '--sql=SELECT '\"'\"'x'\"'\"' FROM dual' 2>&1 | tee '${dir}/validate.out'\n"]
                    .collect { it.toString() },
            commands)

    Map owned = baseCfg()
    owned.sourceDir = 'orders-db'
    (owned.database as Map).test.defaultSchema = 'ORDERS_OWNER'
    List utCommands = harness.run {
        def runner = harness.type('com.bbh.build.LiquibaseRunner')
        [baseCfg(), owned].collect { Map cfg ->
            Map s = ds().resolve('orders-db', cfg)
            runner.utplsqlCommand('/opt/utplsql/bin/utplsql', s, (s.targets as Map).test as Map, dir)
        }
    } as List
    String reporters = "'--failure-exit-code=2' '-t=30' '-f=ut_documentation_reporter' '-s' '-f=ut_junit_reporter' '-o=.dso-db/orders-db/utplsql-junit.xml' '-f=ut_coverage_cobertura_reporter' '-o=.dso-db/orders-db/utplsql-cobertura.xml' '-f=ut_coverage_sonar_reporter' '-o=.dso-db/orders-db/utplsql-coverage-sonar.xml' '-f=ut_sonar_test_reporter' '-o=.dso-db/orders-db/utplsql-sonar-tests.xml'"
    String head = (harness.run { lo().utplsqlScript() } as String) + "mkdir -p '.dso-db/orders-db'\n'/opt/utplsql/bin/utplsql' run \"@\$DSO_ARGS\" "
    String tail = " 2>&1 | dso_mask | tee '${dir}/utplsql.out'\n"
    check('LiquibaseRunner.utplsqlCommand: the arguments of 8.7 in order, the paths under the search root, and the owner as -p and after each path',
            utCommands == [head + reporters + " '-source_path=db/code' '-test_path=db/test'" + tail,
                           head + "'-p=ORDERS_OWNER' " + reporters + " '-source_path=orders-db/db/code' '-owner=ORDERS_OWNER' '-test_path=orders-db/db/test' '-owner=ORDERS_OWNER'" + tail],
            utCommands)
}
scripts()

Closure services = {
    String present = '1 db/changelog-master.xml\n1 db/code\n1 db/test\n'
    Closure validate = { Map projects, Map defaults, String variant, boolean windows, String files ->
        FakeScript j = new FakeScript()
        j.env.vars.WORKSPACE = '/ws'
        j.unix = !windows
        j.shHandler = { Map a -> files }
        String failure = null
        harness.run {
            def state = harness.type('com.bbh.core.PipelineState').newInstance()
            state.configFile = 'devsecops_config.yaml'
            state.cfgDefaults = defaults
            state.projectsAllCfg = projects
            def os = harness.type('com.bbh.core.OsHelper').newInstance(j)
            if (windows) os.detect()
            def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(j, state, os)
            def build = harness.type('com.bbh.build.BuildService').newInstance(j, state, os, policy)
            def database = harness.type('com.bbh.build.DatabaseBuild').newInstance(j, state, os, policy, build)
            try {
                database.validateServices(variant)
            } catch (FakeAbort e) {
                failure = e.message
            }
        }
        [log: j.log, calls: j.calls, failure: failure]
    }
    String filesStep = "sh [DB: check the files of orders-db] ${harness.run { ds().filesCheckScript(ds().resolve('orders-db', baseCfg())) }}"
    String order = '[DB] orders-db comes after api in the list of services: the database changes are then deployed after that application in RD and QC; list the SQL and PL/SQL service first'
    String summary = '[DB] orders-db: Liquibase service with a database block - changelog db/changelog-master.xml in ., package folder db, test database yes, RD yes, QC yes'
    Closure stop = { int count -> "[DB] The SQL and PL/SQL settings in devsecops_config.yaml have ${count} ${count == 1 ? 'problem' : 'problems'}, listed above: fix them and run the build again".toString() }
    Map noJava = baseCfg()
    noJava.remove('javaPath')
    Map ownTargets = baseCfg()
    (ownTargets.database as Map).allowedTargets = ['other-db.bbh.com']
    Map sameTargets = baseCfg()
    (sameTargets.database as Map).allowedTargets = allowAll.database.allowedTargets
    List cases = [
            ['no database service', [api: [buildTool: 'gradle']], allowAll, 'full', false, present, [log: [], calls: [], failure: null]],
            ['a changelog in a Java service', [api: [buildTool: 'maven', database: [changelogFile: 'db/x.xml']]], allowAll, 'full', false, present,
             [log: ['[DB] api: database.changelogFile is set, but buildTool is maven: the database block is ignored; give the changelog its own service with buildTool liquibase, or run Liquibase in this service with build.liquibase'], calls: [], failure: null]],
            ['a Liquibase service without a database block', [db: [buildTool: 'liquibase', database: [:] + allowAll.database]], allowAll, 'full', false, present, [log: [], calls: [], failure: null]],
            ['a database block without a changelog', [db: [buildTool: 'liquibase', database: [contexts: '!test'] + allowAll.database]], allowAll, 'full', false, present,
             [log: ['[DB] db: database.changelogFile is required for the database block; without it the service runs the Liquibase commands of build.liquibase only', "[error] ${stop(1)}".toString()],
              calls: [], failure: stop(1)]],
            ['layout a after a Gradle service', [api: [buildTool: 'gradle'], 'orders-db': baseCfg()], allowAll, 'full', false, present,
             [log: [order, summary], calls: [filesStep], failure: null]],
            ['the extended pipeline checks no files', ['orders-db': baseCfg(), api: [buildTool: 'gradle']], allowAll, 'extended', false, '', [log: [summary], calls: [], failure: null]],
            ['missing files', ['orders-db': baseCfg()], allowAll, 'security', false, '0 db/changelog-master.xml\n1 db/code\n0 db/test\n',
             [log: ['[DB] orders-db: database.changelogFile db/changelog-master.xml does not exist under . in this checkout',
                    '[DB] orders-db: database.test.utplsql.testPath db/test is not a folder under . in this checkout', "[error] ${stop(2)}".toString()], calls: [filesStep], failure: stop(2)]],
            ['one problem and no files step', ['orders-db': noJava], allowAll, 'full', false, present,
             [log: ['[DB] orders-db: javaPath is required unless buildToolAutoSetup is true: Liquibase and utPLSQL need Java 17 or newer', "[error] ${stop(1)}".toString()], calls: [], failure: stop(1)]],
            ['a Global Settings problem', ['orders-db': baseCfg()], allowAll + [tools: [liquibase: [home: 'opt/lb']]], 'full', false, present,
             [log: ['[DB] Global Settings: defaults.tools.liquibase.home opt/lb must be an absolute path', "[error] ${stop(1)}".toString()], calls: [filesStep], failure: stop(1)]],
            ['a Windows agent', ['orders-db': baseCfg()], allowAll, 'full', true, present,
             [log: ['[error] [DB] orders-db: a Liquibase service with a database block runs on Linux or macOS agents only: choose a Linux agent in AGENT_NAME'], calls: [],
              failure: '[DB] orders-db: a Liquibase service with a database block runs on Linux or macOS agents only: choose a Linux agent in AGENT_NAME']],
            ['allowedTargets of the service', ['orders-db': ownTargets], allowAll, 'full', false, present,
             [log: ['[DB] orders-db: database.allowedTargets is a Global Setting (defaults.database.allowedTargets in the DevSecOps portal); the value set for the service is ignored', summary],
              calls: [filesStep], failure: null]],
            ['allowedTargets merged from the Global Settings', ['orders-db': sameTargets], allowAll, 'full', false, present, [log: [summary], calls: [filesStep], failure: null]]
    ]
    List wrong = mismatches(cases) { List c -> validate(c[1] as Map, c[2] as Map, c[3] as String, c[4] as boolean, c[5] as String) }
    check('DatabaseBuild.validateServices: nothing without a database service or for a Liquibase service without a database block, M-DB-06, V1 for a database block without a changelog, the files step, V42 and V43, M-DB-02 with its count, the global problems, M-DB-01, M-DB-05, M-DB-09 and M-DB-03',
            wrong.isEmpty(), wrong)
}
services()

Closure reportAndMetrics = {
    String base = 'https://jenkins.bbh.com/job/DevSecOps/job/orders-db/341/artifact/'
    Map shipped = [version  : '341-20261031-101500', liquibase: '4.33.0',
                   package  : [file: 'orders-db-341-20261031-101500.zip', path: '.dso-db/orders-db/orders-db-341-20261031-101500.zip', fetched: false, changesets: 10,
                               reviewChangesets: 8, reviewPath: '.dso-db/orders-db/orders-db-341-20261031-101500-update.sql', findings: 2,
                               staticChecksPath: '.dso-db/orders-db/static-checks.txt'],
                   tests    : [ran: true, total: 2, failed: 0, skipped: 0, exitCode: 0],
                   targets  : [test: [status: 'PASS', contexts: 'test', pendingBefore: 10, applied: 10, pendingAfter: 0, tag: ''],
                               rd  : [status: 'FAIL', contexts: '!test', pendingBefore: 8, applied: 8, invalidObjects: 1, invalidBefore: 1, compileErrors: 1,
                                      reason: 'the deployment left invalid objects: PACKAGE BODY PKG_ORDERS <line 12>']]]
    Map fetched = [version: '213-20261031-094500', liquibase: '5.0.4', package: [file: 'orders-db-213-20261031-094500-SNAPSHOT.zip',
                                                                                path: '.dso-db/orders-db/orders-db-213-20261031-094500-SNAPSHOT.zip', fetched: true, changesets: 10, reviewChangesets: 8],
                   targets: [qc: [status: 'PASS', contexts: '!test', pendingBefore: 8, applied: 8, tag: 'orders-db-213-20261031-094500']]]
    Map html = harness.run {
        FakeScript sc = new FakeScript()
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
        def report = harness.type('com.bbh.report.HtmlReportService').newInstance(sc, state, os, harness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os), 'full')
        Map none = [enabled: false, notRequired: true, minRequired: 60, reports: []]
        Map met = [enabled: true, line: 82.4, covered: 824, total: 1000, minRequired: 60]
        [empty    : report.databaseCard([:], base), shipped: report.databaseCard(['orders-db': shipped], base), fetched: report.databaseCard(['orders-db': fetched], base),
         line     : report.coverageLine(none), bar: report.coverageBar('orders-db', none),
         card     : report.coverageCard(met, ['orders-db': none, 'orders-app': met]), cardWithout: report.coverageCard(met, ['orders-api': met, 'orders-app': met])]
    } as Map
    String card = html.shipped as String
    check('HtmlReportService.databaseCard: nothing without database data, and the card with the package and the deployment rows',
            html.empty == '' && card.startsWith("<div class='card' style='margin-top:16px;'><h2>Database changes</h2>") && card.count('<tr><td') == 3, card.take(300))
    check('HtmlReportService.databaseCard: the links point into the archived artifacts, and the static check findings link to their file',
            card.contains("<a href='${base}.dso-db/orders-db/orders-db-341-20261031-101500.zip' target='_blank'".toString())
                    && card.contains("<a href='${base}.dso-db/orders-db/orders-db-341-20261031-101500-update.sql' target='_blank' style='color:#0369a1;'>review SQL</a>".toString())
                    && card.contains("<a href='${base}.dso-db/orders-db/static-checks.txt' target='_blank' style='color:#d97706;font-weight:700;'>2</a>".toString())
                    && card.contains('>10 / 8<') && card.contains('>2 / 0 failed<'), card)
    check('HtmlReportService.databaseCard: a failed target shows its escaped reason, its new invalid objects and errors in red, and the objects invalid before',
            card.contains('RD<div style=\'font-size:0.68rem;color:#64748b;\'>the deployment left invalid objects: PACKAGE BODY PKG_ORDERS &lt;line 12&gt;</div>')
                    && card.contains("color:#dc2626;'>1 / 1<div style='font-size:0.68rem;color:#64748b;font-weight:400;'>1 invalid before</div>") && !card.contains('<line 12>')
                    && card.contains('>FAIL</span>') && card.contains('>10 / 10 / 0<'), card)
    check('HtmlReportService.databaseCard: the package of an extended run says delivered snapshot, has no utPLSQL run, and its QC row shows the tag',
            (html.fetched as String).contains("<td style='padding:5px 10px;'>delivered snapshot</td>") && (html.fetched as String).contains('>not run<')
                    && (html.fetched as String).contains('>orders-db-213-20261031-094500<') && (html.fetched as String).contains('>QC<'), html.fetched)
    check('HtmlReportService: coverage that is not required says so in the Unit tests box and the coverage bar, and the services that meet the level count only the scored ones',
            (html.line as String).contains('>not required</span>') && (html.bar as String).contains('not required &nbsp;&bull;&nbsp; no utPLSQL run for this SQL and PL/SQL service')
                    && (html.card as String).contains('1 of 1 services meet it') && (html.cardWithout as String).contains('2 of 2 services meet it'), html.card)

    Map metrics = harness.run {
        def type = harness.type('com.bbh.metrics.PipelineMetrics')
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        Map base2 = [project: 'orders', env: 'dev']
        List none = type.database(state, base2, 1793528100L) as List
        state.projectsDatabase = ['orders-db': [version: '341-20261031-101500', liquibase: '4.33.0', package: [changesets: 10],
                                                targets: [rd: [status: 'PASS', contexts: '!test', pendingBefore: 8, applied: 8, previouslyRun: 0, filteredOut: 2, pendingAfter: 0,
                                                               invalidObjects: 0, invalidBefore: 0, compileErrors: 0, tag: 'orders-db-341-20261031-101500', durationMs: 6123, reason: '']]]]
        state.projectsCoverage = ['orders-db': [enabled: false, notRequired: true, minRequired: 60, reports: []]]
        [none: none, lines: type.database(state, base2, 1793528100L), coverage: type.coverage(state, base2, 1793528100L)]
    } as Map
    check('PipelineMetrics.database: no point without database data, and the example line of the documentation for a deployment',
            metrics.none == [] && metrics.lines == ['database_deployment,project=orders,env=dev,module=orders-db,target=rd,status=PASS pending_before=8i,applied=8i,previously_run=0i,filtered_out=2i,pending_after=0i,invalid_objects=0i,invalid_before=0i,compile_errors=0i,changesets=10i,tagged=1i,ok=1i,duration_ms=6123i,version="341-20261031-101500",liquibase="4.33.0" 1793528100'],
            metrics.lines)
    check('PipelineMetrics.coverage: a service without utPLSQL is measured=not_required, with met 0 and gap 0',
            metrics.coverage == ['code_coverage,project=orders,env=dev,module=orders-db,measured=not_required line_pct=0.0,covered=0i,missed=0i,total=0i,required=60.0,met=0i,gap=0.0 1793528100'],
            metrics.coverage)
}
reportAndMetrics()

tmp.deleteDir()
println "\n${failures == 0 ? 'ALL DATABASE UNIT CHECKS PASSED' : failures + ' DATABASE UNIT CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
