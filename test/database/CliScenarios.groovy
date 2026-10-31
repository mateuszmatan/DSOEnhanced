package devsecops.test

import org.yaml.snakeyaml.Yaml

import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class RealScript extends FakeScript {
    File workspace
    Map<String, String> agent = [:]
    Map<String, String> login = [:]
    String killLabel = ''
    boolean stepFileSeen = false
    List<String> console = []
    Set<String> processes = [] as Set

    File here() {
        return !cwd ? workspace : (cwd.startsWith('/') ? new File(cwd) : new File(workspace, cwd))
    }

    File real(Object path) {
        String p = String.valueOf(path)
        return p.startsWith('/') ? new File(p) : new File(here(), p)
    }

    boolean fileExists(Object path) { real(path).exists() }

    String readFile(Object arg) {
        File file = real(arg instanceof Map ? ((Map) arg).file : arg)
        if (!file.file) throw new FileNotFoundException(file.path)
        return file.getText('UTF-8')
    }

    void writeFile(Map args) {
        File file = real(args.file)
        file.parentFile.mkdirs()
        file.setText(String.valueOf(args.text), 'UTF-8')
    }

    def deleteDir() {
        calls << "deleteDir ${cwd}".toString()
        here().deleteDir()
    }

    def zip(Map args) {
        calls << "zip ${args.zipFile}".toString()
        File from = real(args.dir)
        File to = real(args.zipFile)
        to.parentFile.mkdirs()
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(to))
        try {
            from.eachFileRecurse(groovy.io.FileType.FILES) { File f ->
                out.putNextEntry(new ZipEntry(from.toPath().relativize(f.toPath()).toString()))
                out.write(f.bytes)
                out.closeEntry()
            }
        } finally {
            out.close()
        }
    }

    def withCredentials(List credentials, Closure body) {
        calls << "withCredentials ${credentials.collect { it instanceof Map ? ((Map) it).credentialsId : it }}".toString()
        Map<String, String> bound = [:]
        credentials.each { c ->
            if (c instanceof Map && c.usernameVariable) bound[c.usernameVariable as String] = login.user
            if (c instanceof Map && c.passwordVariable) bound[c.passwordVariable as String] = login.password
        }
        Map<String, String> saved = bound.collectEntries { k, v -> [(k): env.vars.get(k)] }
        env.vars.putAll(bound)
        try {
            return body.call()
        } finally {
            saved.each { k, v -> if (v == null) env.vars.remove(k) else env.vars.put(k, v) }
        }
    }

    def sh(Object arg) {
        Map a = arg instanceof Map ? (Map) arg : [script: String.valueOf(arg)]
        calls << "sh [${a.label ?: ''}]".toString()
        Map<String, String> vars = [:] + agent
        env.vars.each { k, v -> if (v != null && !k.contains('+')) vars[k] = v }
        env.vars.each { k, v ->
            if (v == null || !k.contains('+')) return
            String name = k.substring(0, k.indexOf('+'))
            vars[name] = vars[name] ? "${v}:${vars[name]}".toString() : v
        }
        here().mkdirs()
        ProcessBuilder builder = new ProcessBuilder('bash', '-c', String.valueOf(a.script)).directory(here())
        if (a.returnStdout) builder.redirectError(ProcessBuilder.Redirect.DISCARD) else builder.redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(vars)
        Process process = builder.start()
        StringBuilder out = new StringBuilder()
        Thread reader = Thread.start { out.append(process.inputStream.getText('UTF-8')) }
        long started = System.currentTimeMillis()
        String stepDir = vars.DSO_TMP
        while (!process.waitFor(40, TimeUnit.MILLISECONDS)) {
            List handles = [process.toHandle()] + process.toHandle().descendants().iterator().toList()
            handles.each { h ->
                String command = sample(new File("/proc/${h.pid()}/cmdline"))
                if (command && !command.startsWith('bash\n')) sample(new File("/proc/${h.pid()}/environ"))
            }
            if (stepDir && new File(stepDir).list()?.any { it.startsWith('step.') || it.startsWith('ut.') }) stepFileSeen = true
            if (a.label == killLabel && System.currentTimeMillis() - started > 2500L) handles.each { it.destroy() }
        }
        reader.join()
        console << out.toString()
        int status = process.exitValue()
        if (a.returnStatus) return status
        if (status != 0) throw new FakeAbort("script returned exit code ${status}".toString())
        return a.returnStdout ? out.toString() : null
    }

    String sample(File file) {
        try {
            String text = new String(file.bytes, 'UTF-8').replace((char) 0, (char) 10)
            processes << text
            return text
        } catch (IOException ignored) {
            return ''
        }
    }
}

File root = new File(args[0]).canonicalFile
File tools = new File(args[1])
String jdk = args[2]
File fixtures = new File(root, 'test/fixtures/database')
File base = Files.createTempDirectory('dso-cli').toFile()
addShutdownHook { base.deleteDir() }
SandboxHarness harness = new SandboxHarness(new File(root, 'src'), new File(root, 'test/sandbox/stub'))
Closure ds = { -> harness.type('com.bbh.utils.DatabaseSettings') }

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

String user = 'dso_cli_user'
String password = 'Cli\\pw"q\'u@h/x'
Map<String, String> agent = [PATH                            : System.getenv('PATH') ?: '/usr/bin:/bin', HOME: System.getenv('HOME') ?: base.path,
                             LIQUIBASE_COMMAND_URL           : 'jdbc:h2:mem:agent', LIQUIBASE_COMMAND_CONTEXT_FILTER: 'agent',
                             LIQUIBASE_COMMAND_CHANGELOG_FILE: 'agent.xml']
List<RealScript> scripts = []

Closure copyTree = { File from, File to ->
    from.eachFileRecurse(groovy.io.FileType.FILES) { File f ->
        File target = new File(to, from.toPath().relativize(f.toPath()).toString())
        target.parentFile.mkdirs()
        target.bytes = f.bytes
    }
}
Closure layout = { String name, String kind ->
    File ws = new File(base, "work space+${name}")
    copyTree(new File(fixtures, "layout-${kind}"), ws)
    String into = kind == 'b' ? 'orders-db/src/main/resources/db/changelog' : 'orders-db'
    if (kind != 'a') ['tables', 'code', 'test'].each { copyTree(new File(fixtures, "layout-a/db/${it}"), new File(ws, "${into}/${it}")) }
    return ws
}
Closure toolsOf = { String version ->
    [liquibase: [home: "${tools}/liquibase-${version}".toString()], oracleJdbc: [path: "${tools}/ojdbc11.jar".toString()], utplsql: [home: "${tools}/utPLSQL-cli".toString()]]
}
Closure ordersCfg = { String kind, String version ->
    Map cfg = ((new Yaml().load(new File(fixtures, "layout-${kind}/devsecops_config.yaml").text) as Map).projects as Map)['orders-db'] as Map
    cfg.javaPath = jdk
    cfg.tools = toolsOf(version)
    ['test', 'rd', 'qc'].each { (cfg.database as Map).remove(it) }
    return cfg
}
Closure service = { File ws, String name, Map cfg ->
    RealScript j = new RealScript(workspace: ws, agent: agent, login: [user: user, password: password])
    scripts << j
    j.env.vars.putAll([WORKSPACE: ws.path, BUILD_NUMBER: '341', BUILD_TAG: "jenkins-DevSecOps-${name}-341".toString(), NODE_NAME: 'cli-agent', CURRENT_PROJECT_NAME: name])
    j.steps['junit'] = { a -> new FakeTestSummary(totalCount: 0, failCount: 0, skipCount: 0, passCount: 0) }
    Map objects = harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        state.configFile = 'devsecops_config.yaml'
        state.currentProjectName = name
        state.currentStage = 'Unit tests'
        state.cfg = cfg
        state.cfgDefaults = [tools: cfg.tools]
        state.projectsAllCfg = [(name): cfg]
        def os = harness.type('com.bbh.core.OsHelper').newInstance(j)
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(j, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(j, state, os, policy)
        [state: state, database: harness.type('com.bbh.build.DatabaseBuild').newInstance(j, state, os, policy, build)]
    } as Map
    return [j: j] + objects
}
Closure attempt = { Closure body ->
    String failure = ''
    harness.run {
        try {
            body.call()
        } catch (FakeAbort e) {
            failure = e.message
        }
    }
    return failure
}
Closure entriesOf = { File zip ->
    ZipFile file = new ZipFile(zip)
    try {
        return file.entries().toList().collect { it.name }
    } finally {
        file.close()
    }
}
Closure h2 = { String version, String url, String sql ->
    ProcessBuilder builder = new ProcessBuilder("${jdk}/bin/java".toString(), '-cp', "${tools}/liquibase-${version}/internal/lib/h2.jar".toString(), 'org.h2.tools.Shell',
            '-url', url, '-user', user, '-password', password, '-sql', sql).redirectErrorStream(true)
    builder.environment().remove('JAVA_TOOL_OPTIONS')
    Process process = builder.start()
    String out = process.inputStream.getText('UTF-8')
    process.waitFor()
    return out
}

String validLine = 'validated from the package'
['4.33.0', '5.0.4'].each { String version ->
    ['a', 'b', 'c'].each { String kind ->
        Map cfg = ordersCfg(kind, version)
        File ws = layout("${version}-${kind}", kind)
        Map run = service(ws, 'orders-db', cfg)
        String failure = attempt { run.database.build() }
        Map record = (run.state.projectsDatabase['orders-db'] ?: [:]) as Map
        Map pkg = (record.package ?: [:]) as Map
        List<String> entries = pkg.path && new File(ws, pkg.path as String).file ? entriesOf(new File(ws, pkg.path as String)) : []
        String changelog = (cfg.database as Map).changelogFile
        check("Liquibase ${version}, layout ${kind}: validate, the static facts, both update-sql runs, the package and its validation run with the real CLI in a workspace path with + and a blank",
                !failure && record.liquibase == version && pkg.changesets == 10 && pkg.reviewChangesets == 8 && pkg.findings == 0 && (run.j as RealScript).log.any { it.contains(validLine) },
                [failure, record, (run.j as RealScript).log.findAll { it.startsWith('[DB]') }])
        check("Liquibase ${version}, layout ${kind}: the zip holds the changelog and dso-deploy.properties and no directory entry, and the DB work folder is removed",
                entries.contains(changelog) && entries.contains('dso-deploy.properties') && !entries.any { it.endsWith('/') } && !new File("${ws.path}@tmp/dso-db/orders-db").exists(),
                entries)
    }

    String searchPath = 'Validation Failed: 1 changes have validation failures The file code/pkg_orderz.pkb was not found in the configured search path: - '
    Map variants = [
            E1: ['db/tables/002-create-order-items.sql', { String t -> t.replace('--changeset orders-team:002-create-order-items', '--changeset orders-team 002-create-order-items') },
                 "Unexpected formatting in formatted changelog 'db/tables/002-create-order-items.sql' at line 3."],
            E2: ['db/tables/002-create-order-items.sql', { String t -> t + '\n--changeset orders-team:002-create-order-items\nCREATE INDEX order_items_sku_ix ON order_items (sku);\n--rollback DROP INDEX order_items_sku_ix;\n' },
                 'Validation Failed: 1 changesets had duplicate identifiers db/tables/002-create-order-items.sql::002-create-order-items::orders-team'],
            E3: ['db/changelog-master.xml', { String t -> t.replace('path="code/pkg_orders.pkb"', 'path="code/pkg_orderz.pkb"') }, searchPath],
            E4: ['db/tables/002-create-order-items.sql', { String t -> t + '\n--include file:003-more.sql relativeToChangelogFile:true\n' },
                 version.startsWith('5.') ? "Error parsing command line: Using 'include in Formatted SQL changelog' requires a valid Liquibase license key. Get a Liquibase license key and free trial at https://liquibase.com/trial."
                         : "ERROR: Using 'include' with formatted SQL requires a valid Liquibase Pro key. Get a free license key at https://liquibase.com/trial."],
            E5: ['db/changelog-master.xml', { String t -> t.replaceFirst('        <rollback/>\n    </changeSet>\n', '') },
                 "Error parsing line 13 column 76 of db/changelog-master.xml: cvc-complex-type.2.4.a: Invalid content was found starting with element '{\"http://www.liquibase.org/xml/ns/dbchangelog\":changeSet}'. One of '{"]
    ]
    variants.each { String id, List variant ->
        File ws = layout("${version}-${id}", 'a')
        File file = new File(ws, variant[0] as String)
        file.text = (variant[1] as Closure).call(file.text)
        Map run = service(ws, 'orders-db', ordersCfg('a', version))
        String failure = attempt { run.database.build() }
        String prefix = '[DB] orders-db: the changelog db/changelog-master.xml is not valid: '
        String reason = failure.startsWith(prefix) ? failure.substring(prefix.length()) : ''
        String hint = harness.run { harness.type('com.bbh.utils.LiquibaseOutput').hintText(reason) } as String
        check("Liquibase ${version}, ${id}: the invalid changelog stops the build with M-DB-20, the reason of the real CLI and its hint",
                reason.startsWith(variant[2] as String) && reason.endsWith(hint) && (id in ['E1', 'E2', 'E5'] || hint),
                failure)
    }

    File dbDir = new File(base, "h2-${version}")
    File ws = new File(base, "work space+${version}-h2")
    new File(ws, 'db').mkdirs()
    new File(ws, 'db/changelog.xml').text = '''<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <changeSet id="001-create-orders" author="cli">
        <createTable tableName="orders"><column name="order_id" type="NUMBER(19)"/></createTable>
    </changeSet>
    <changeSet id="002-seed-orders" author="cli" context="test">
        <insert tableName="orders"><column name="order_id" valueNumeric="1"/></insert>
    </changeSet>
    <changeSet id="003-create-items" author="cli">
        <createTable tableName="order_items"><column name="order_id" type="NUMBER(19)"/></createTable>
    </changeSet>
</databaseChangeLog>
'''
    File leak = new File(base, "leak-${version}")
    leak.mkdirs()
    String placeholders = '${DSO_P}|${DSO_DB_PASSWORD}|${LIQUIBASE_COMMAND_PASSWORD}|${DSO_U}|${DSO_DB_USER}'
    new File(leak, 'leak.xml').text = """<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <changeSet id="leak" author="cli">
        <createTable tableName="leak"><column name="v" type="VARCHAR(400)"/></createTable>
        <insert tableName="leak"><column name="v" value="${placeholders}"/></insert>
    </changeSet>
</databaseChangeLog>
"""
    String rdUrl = "jdbc:h2:file:${dbDir}/rd;INIT=CREATE SCHEMA IF NOT EXISTS LB".toString()
    Map h2Cfg = [buildTool: 'liquibase', sourceDir: '.', javaPath: jdk, tools: toolsOf(version),
                 database : [changelogFile: 'db/changelog.xml', compileCheck: [enabled: false], lockWaitMinutes: 1,
                             package      : [groupId: 'com.bbh.cli', artifactId: 'cli-db', nexusDelivery: false],
                             test         : [url: "jdbc:h2:file:${dbDir}/test".toString(), credentialsId: 'cli-test', utplsql: [enabled: false]],
                             rd           : [url: rdUrl, credentialsId: 'cli-rd', liquibaseSchema: 'LB']]]
    Map run = service(ws, 'cli-db', h2Cfg)
    RealScript j = run.j as RealScript
    String built = attempt { run.database.build() }
    String applied = attempt { run.database.applyChanges('rd') }
    Map record = (run.state.projectsDatabase['cli-db'] ?: [:]) as Map
    Map rows = (record.targets ?: [:]) as Map
    Map test = (rows.test ?: [:]) as Map
    Map rd = (rows.rd ?: [:]) as Map
    String tag = "cli-db-${record.version}".toString()
    check("Liquibase ${version} on H2: status, update, status after the update and unexpected-changesets of the test database parse, through the online prelude and the step file",
            !built && test.status == 'PASS' && test.pendingBefore == 3 && test.applied == 3 && test.pendingAfter == 0 && test.unexpected == 0 && j.stepFileSeen,
            [built, test, j.log.findAll { it.startsWith('[DB]') }])
    String tags = h2(version, rdUrl, "SELECT ID, TAG FROM LB.DATABASECHANGELOG WHERE TAG IS NOT NULL; SELECT COUNT(*) AS N FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'DATABASECHANGELOG'")
    check("Liquibase ${version} on H2: RD runs the changesets of its contexts and is tagged, and --liquibase-schema-name puts the tag into the LB schema",
            !applied && rd.status == 'PASS' && rd.pendingBefore == 2 && rd.applied == 2 && rd.filteredOut == 1 && rd.tag == tag && tags.contains("003-create-items | ${tag}") && tags.contains('\n1\n'),
            [applied, rd, tags])

    Map s = harness.run { ds().resolve('cli-db', h2Cfg) } as Map
    Map testTarget = (s.targets as Map).test as Map
    Map rdTarget = (s.targets as Map).rd as Map
    def runner = harness.run { run.database.runner() }
    String dir = harness.run { runner.workDir('cli-db') }
    Map step = [dir: dir, jdk: jdk, liquibase: "${tools}/liquibase-${version}/liquibase".toString(), jar: "${tools}/ojdbc11.jar".toString(), lockWait: 1, quiet: false, search: leak.path]
    Map leaked = harness.run {
        ['test', 'rd'].each { String t -> j.writeFile(file: "${dir}/${t}.properties", text: ds().propertiesText(['liquibase.command.url': (s.targets as Map)[t].url])) }
        runner.liquibase(step + [label: 'DB: Liquibase status on test', target: testTarget, command: 'status', args: ['--changelog-file=leak.xml'], out: 'leak-status.out'])
        Map updated = runner.liquibase(step + [label: 'DB: Liquibase update on test', target: testTarget, command: 'update', args: ['--changelog-file=leak.xml'], out: 'leak-update.out', lock: true])
        Map selected = runner.liquibase(step + [label: 'DB: read the leak table on test', target: testTarget, command: 'execute-sql', args: ['--sql=SELECT V FROM LEAK'], out: 'leak-select.out', lock: true])
        [updated: updated.status, selected: selected.status, text: selected.text]
    } as Map
    check("Liquibase ${version} on H2: a changeset that names \${DSO_P}, \${DSO_DB_PASSWORD} and \${LIQUIBASE_COMMAND_PASSWORD} stores the names, not the password, and status masks the user",
            leaked.updated == 0 && leaked.selected == 0 && (leaked.text as String).contains(placeholders) && !(leaked.text as String).contains(password)
                    && new File(dir, 'leak-status.out').text.contains('****@jdbc:h2:file:') && !new File(dir, 'leak-status.out').text.toUpperCase().contains(user.toUpperCase()),
            leaked)

    Map lockStep = step + [label: 'DB: Liquibase update on rd', target: rdTarget, lock: true]
    String lockId = harness.run { ds().lockId(j.env.BUILD_TAG as String) } as String
    Closure lockAs = { String owner -> h2(version, rdUrl, "UPDATE LB.DATABASECHANGELOGLOCK SET LOCKED = TRUE, LOCKGRANTED = CURRENT_TIMESTAMP, LOCKEDBY = '${owner}' WHERE ID = 1") }
    List lockEnv = ["DSO_LOCK_ID=${lockId}".toString(), "DSO_TMP=${dir}".toString(), 'DSO_PROPS=rd.properties']
    Closure release = { -> harness.run { runner.online(rdTarget, runner.baseEnv(jdk) + lockEnv) { runner.releaseOwnLock(rdTarget, lockStep) } } }
    lockAs('jenkins-agent-07#jenkins-DevSecOps-other-12 (10.20.30.40)')
    release()
    String foreign = h2(version, rdUrl, 'SELECT LOCKED FROM LB.DATABASECHANGELOGLOCK')
    lockAs("cli-agent#${lockId} (127.0.0.1)")
    release()
    String own = h2(version, rdUrl, 'SELECT LOCKED FROM LB.DATABASECHANGELOGLOCK')
    check("Liquibase ${version} on H2: list-locks finds the lock of this build in the LB schema and release-locks frees it, and a lock of another build is left alone",
            j.log.contains('[DB] cli-db rd: DB: Liquibase update on rd stopped; no Liquibase lock of this build was held') && foreign.contains('TRUE')
                    && j.log.contains('[DB] cli-db rd: released the Liquibase lock that this build held after DB: Liquibase update on rd stopped') && own.contains('FALSE'),
            [j.log.findAll { it.contains('lock') }, foreign, own])

    lockAs('jenkins-agent-07#jenkins-DevSecOps-other-12 (10.20.30.40)')
    j.stepFileSeen = false
    j.killLabel = 'DB: Liquibase update on rd'
    Map killed = harness.run { runner.liquibase(lockStep + [command: 'update', args: ['--changelog-file=leak.xml'], out: 'rd-killed.out']) } as Map
    j.killLabel = ''
    check("Liquibase ${version} on H2: an update that waits for the lock and is stopped with SIGTERM removes its step file",
            killed.status != 0 && j.stepFileSeen && !new File(dir).list().any { it.startsWith('step.') }, [killed.status, j.stepFileSeen, new File(dir).list()])
}

Map utCfg = ordersCfg('a', '5.0.4')
(utCfg.database as Map).test = [url: 'jdbc:oracle:thin:@//127.0.0.1:1/NONE', credentialsId: 'cli-test', utplsql: [enabled: true, sourcePath: 'db/code', testPath: 'db/test', timeoutMinutes: 1]]
File utWs = layout('utplsql', 'a')
Map ut = service(utWs, 'orders-db', utCfg)
RealScript utScript = ut.j as RealScript
Map utContext = harness.run { ut.database.context() } as Map
new File(utContext.dir as String).mkdirs()
harness.run { ut.database.runUtplsql(utContext, ((utContext.s as Map).targets as Map).test as Map, System.currentTimeMillis()) }
String utOut = new File("${utWs.path}@tmp/dso-db/orders-db/utplsql.out").text
String utWarning = utScript.log.find { it.startsWith('[unstable] [POLICY] utPLSQL could not run the tests (exit code 1): ') } ?: ''
check('utPLSQL-cli: a test database that does not answer gives exit code 1 and M-DB-36, the CLI prints ****/****, and the argument file is removed',
        utWarning.contains('Could not establish connection to database. Reason: ORA-12541') && utOut.contains('****/****') && !utOut.contains(password) && utScript.stepFileSeen
                && !new File("${utWs.path}@tmp/dso-db/orders-db").list().any { it.startsWith('ut.') },
        [utWarning, utOut.readLines().findAll { it.contains('****') || it.contains('Could not') }.take(3)])

File tarball = new File(tools, 'liquibase-5.0.4.tar.gz')
String sha = MessageDigest.getInstance('SHA-256').digest(tarball.bytes).collect { String.format('%02x', it) }.join('')
Closure download = { String sum ->
    Map cfg = ordersCfg('a', '5.0.4')
    (cfg.tools as Map).liquibase = [url: "file://${tarball.path}".toString(), sha256: sum]
    Map run = service(new File(base, 'work space+download'), 'orders-db', cfg)
    Map found = [:]
    String failure = attempt { found = run.database.runner().tool('liquibase', cfg) as Map }
    return [found: found, failure: failure]
}
Map fetched = download(sha)
Map again = download(sha)
Map wrong = download('b' * 64)
File folder = new File((fetched.found as Map).home as String ?: base.path)
check('The download script of 6.1: the tarball with its sha256 unpacks to an executable launcher with the .sha256 marker, a later build reuses it, and a wrong sha256 gives M-DB-13',
        !fetched.failure && new File((fetched.found as Map).path as String ?: '/').canExecute() && new File(folder, '.sha256').text == sha && !again.failure
                && (again.found as Map).path == (fetched.found as Map).path
                && wrong.failure == '[DB] The file at defaults.tools.liquibase.url does not have the sha256 of defaults.tools.liquibase.sha256 and was removed: check both settings',
        [fetched, again.failure, wrong.failure])

List<String> leakedProcesses = scripts.collectMany { it.processes as List }.findAll { it.contains(password) || it.toLowerCase().contains(user) }
List<String> leakedOutput = scripts.collectMany { it.console + it.log }.findAll { it.contains(password) }
List<String> leakedFiles = []
List<String> leftOver = []
base.eachFileRecurse(groovy.io.FileType.FILES) { File f ->
    if (f.path.contains('/dso-tools/') || f.name ==~ /.*\.(mv\.db|trace\.db|zip|jar|gz)/) return
    if (f.name ==~ /(step|ut)\.\w{6}/) leftOver << f.path
    if (f.length() < 5_000_000L && f.getText('UTF-8').contains(password)) leakedFiles << f.path
}
check('No process argument or environment of any step holds the password or the user, no output, log line or file holds the password, and no step or argument file is left',
        scripts.any { !it.processes.isEmpty() } && !leakedProcesses && !leakedOutput && !leakedFiles && !leftOver,
        [leakedProcesses.take(2)*.take(300), leakedOutput.take(2)*.take(300), leakedFiles, leftOver])

println()
println(failures ? "${failures} DATABASE CLI CHECK(S) FAILED" : 'ALL DATABASE CLI CHECKS PASSED')
System.exit(failures ? 1 : 0)
