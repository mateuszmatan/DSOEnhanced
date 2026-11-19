package devsecops.test

class DatabaseResponder {

    static final List<String> IDS = ['db/tables/001-create-orders.sql::001-create-orders-seq::orders-team', 'db/tables/001-create-orders.sql::001-create-orders::orders-team',
                                     'db/tables/001-create-orders.sql::001-create-orders-idx::orders-team', 'db/tables/002-create-order-items.sql::002-create-order-items::orders-team',
                                     'db/changelog-master.xml::pkg_orders.pks::orders-team', 'db/changelog-master.xml::pkg_orders.pkb::orders-team',
                                     'db/changelog-master.xml::v_order_totals.vw::orders-team', 'db/changelog-master.xml::trg_orders_bi.trg::orders-team',
                                     'db/changelog-master.xml::ut_pkg_orders.pks::orders-team', 'db/changelog-master.xml::ut_pkg_orders.pkb::orders-team']
    static final String CHECKSUM = 'db/tables/001-create-orders.sql::001-create-orders-idx::orders-team was: 9:e6c4133fbc5666d9b9dcf08e7c86a390 but is now: 9:e3df5f8c791313f6920abde45f9636c6'
    static final Map<String, List<String>> ERRORS = [
            E1         : ["Unexpected formatting in formatted changelog 'db/tables/002-create-order-items.sql' at line 3.",
                          "Formatted SQL changelogs require known formats, such as '--changeset <authorname>:<changesetId>' and others to be recognized and run.\nLearn all the options at https://docs.liquibase.com/concepts/changelogs/sql-format.html\n"],
            E2         : ['Validation Failed:', '     1 changesets had duplicate identifiers\n          db/tables/002-create-order-items.sql::002-create-order-items::orders-team\n'],
            E3         : ['Validation Failed:', "     1 changes have validation failures\n          The file code/pkg_orderz.pkb was not found in the configured search path:\n    - /ws\n    - /opt/liquibase/4.33.0/lib\nMore locations can be added with the 'searchPath' parameter., db/changelog-master.xml::pkg_orders.pkb::orders-team\n"],
            E4         : ["Error parsing command line: Using 'include in Formatted SQL changelog' requires a valid Liquibase license key. Get a Liquibase license key and free trial at https://liquibase.com/trial.", ''],
            E5         : ["Error parsing line 13 column 76 of db/changelog-master.xml: cvc-complex-type.2.4.a: Invalid content was found starting with element '{\"http://www.liquibase.org/xml/ns/dbchangelog\":changeSet}'. One of '{...}' is expected.", ''],
            checksum   : ['Validation Failed:', "     1 changesets check sum\n          ${CHECKSUM}\n".toString()],
            lock       : ['Could not acquire change log lock.  Currently locked by jenkins-agent-07 (10.20.30.40) since 10/30/26, 11:48 PM', ''],
            'ora-00955': ['Migration failed for changeset db/tables/002-create-order-items.sql::002-create-order-items::orders-team:',
                          '     Reason: liquibase.exception.DatabaseException: ORA-00955: name is already used by an existing object\n [Failed SQL: (955) CREATE TABLE order_items (item_id NUMBER(19) NOT NULL)]\n'],
            'ora-01031': ['ORA-01031: insufficient privileges', 'https://docs.oracle.com/error-help/db/ora-01031/\n'],
            rollback   : ['Liquibase does not support automatic rollback generation for raw sql changes (did you mean to specify keyword "empty" to ignore rolling back this change?)', '']
    ]

    static List sh(FakeScript j, Map a, Map spec) {
        String label = String.valueOf(a.label ?: '')
        if (!a.label && a.returnStdout && j.env.BUILD_SYSTEM == 'liquibase') return ['/usr/lib/jvm/java-17-openjdk\n']
        if (!label.startsWith('DB: ')) return null
        String text = String.valueOf(a.script ?: '')
        if (spec.timeoutAt == label) throw new FakeAbort('Timeout has been exceeded')
        def found = label =~ /^DB: find (\S+) on the PATH$/
        if (found.find()) return [spec.missingTool == found.group(1) ? 1 : 0]
        if (label.startsWith('DB: download ')) {
            j.files["${j.env.DSO_TOOL_DIR}/.sha256".toString()] = j.env.DSO_TOOL_SHA256 as String
            if (label.endsWith(' liquibase')) j.files["${j.env.DSO_TOOL_DIR}/liquibase".toString()] = '#!/bin/sh'
            return [spec.badSha ? 3 : 0]
        }
        if (label.startsWith('DB: check the files of ')) return [checkFiles(j, text, spec)]
        if (label.startsWith('DB: read the SQL and PL/SQL sources of ')) return [facts(j, text)]
        if (label.startsWith('DB: list the files AppScan does not scan for ')) return [unscanned(j, text)]
        if (label.startsWith('DB: stage the package ')) {
            String source = quoted(text, /cp -R '([^']+)'/).replaceFirst(/\/\.$/, '')
            String prefix = source == '.' ? '' : source + '/'
            int removed = j.files.keySet().count { it.startsWith(prefix) && it.tokenize('/').last() ==~ /liquibase.*\.properties/ } as int
            put(j, quoted(text, /> '([^']+package-removed\.txt)'/), "${removed}\n")
            return [0]
        }
        if (label == 'DB: remove the package stage') {
            String stage = quoted(text, /rm -rf '([^']+)'/)
            j.files.keySet().removeAll { it.startsWith(stage + '/') }
            return [null]
        }
        if (label == 'DB: ask SonarQube for its PL/SQL analyzer') {
            if (spec.sonarDown) return [22]
            List languages = ['java', 'js', 'ts', 'xml'] + (spec.sonarPlsql ? [spec.sonarPlsql] : [])
            put(j, quoted(text, /-o '([^']+)'/), '{"languages":[' + languages.collect { "{\"key\":\"${it}\",\"name\":\"${it}\"}" }.join(',') + ']}')
            return [0]
        }
        if (label.startsWith('DB: publish ') || label.startsWith('DB: fetch the delivered package ')) return [maven(j, text, label, spec)]
        if (label == 'DB: utPLSQL tests on the test database') return [utplsql(j, text, spec)]
        if (label.startsWith('DB: ') && text.contains('--defaults-file=')) return [liquibase(j, text, label, spec)]
        return null
    }

    static String quoted(String text, String pattern) {
        def m = text =~ pattern
        return m.find() ? m.group(1) : ''
    }

    static void put(FakeScript j, String path, String text) {
        j.writeFile(file: path, text: text)
    }

    static String checkFiles(FakeScript j, String text, Map spec) {
        List out = []
        def m = text =~ /if \[ (-[fd]) '([^']*)' \]/
        while (m.find()) {
            String path = m.group(2)
            boolean there = m.group(1) == '-f' ? j.files.containsKey(path) : j.files.keySet().any { it.startsWith(path + '/') }
            out << "${there && path != spec.missingFile ? 1 : 0} ${path}"
        }
        return out.join('\n') + '\n'
    }

    static List<String> planted(FakeScript j, String root, String folder, String text) {
        List names = []
        def m = text =~ /-name '\*\.(\w+)'/
        while (m.find()) names << m.group(1)
        String prefix = root == '.' ? '' : root + '/'
        String start = folder == '.' ? prefix : prefix + folder + '/'
        boolean skip = text.contains("-not -path '*/.dso-db/*'")
        return j.files.keySet().findAll { it.startsWith(start) && !(skip && ('/' + it).contains('/.dso-db/')) && names.contains(it.tokenize('.').last()) }
                .collect { it.substring(prefix.length()) }.sort()
    }

    static String facts(FakeScript j, String text) {
        String root = quoted(text, /(?m)^cd '([^']*)'$/)
        String folder = quoted(text, /(?m)^find '([^']*)' -type f/)
        List<String> paths = planted(j, root, folder, text)
        String prefix = root == '.' ? '' : root + '/'
        put(j, quoted(text, /> '([^']+static-files\.txt)'/), paths ? paths.join('\n') + '\n' : '')
        StringBuilder out = new StringBuilder()
        paths.each { out << "F\t${it}\n" }
        paths.each { String path ->
            List<String> lines = j.files[prefix + path].readLines()
            lines.eachWithIndex { String line, int i -> out << "L\t${path}\t${i + 1}\t${line}\n" }
            int last = lines.findLastIndexOf { it =~ /[^ \t]/ }
            out << "E\t${path}\t${last + 1}\t${last >= 0 ? lines[last] : ''}\n"
        }
        put(j, quoted(text, /\} > '([^']+)'/), out.toString())
        return 0
    }

    static String unscanned(FakeScript j, String text) {
        String dir = quoted(text, /find '([^']*)' -type f/)
        List<String> paths = planted(j, dir, '.', text).collect { dir == '.' ? it : "${dir}/${it}".toString() }
        List<String> skipped = paths.findAll { it ==~ /.*\.(prc|fnc|trg|vw|tps|tpb|typ|bdy)/ }
        return (["${skipped.size()} ${paths.size()}"] + skipped.take(10)).join('\n') + '\n'
    }

    static String header(String version) {
        return version.startsWith('5.')
                ? "Starting Liquibase at 23:38:28 using Java 21.0.12.1 (version ${version} #11282 built at 2026-08-18 12:10:05 UTC)\nLiquibase Version: ${version}\n\n"
                : "Starting Liquibase at 23:38:57 using Java 21.0.12.1 (version ${version} #8744 built at 2025-07-08 20:43+0000)\nLiquibase Version: ${version}\nLiquibase Open Source ${version} by Liquibase\n"
    }

    static String failed(String version, String name, String database) {
        List<String> error = ERRORS[name] ?: ["Connection could not be created to ${database} with driver oracle.jdbc.OracleDriver.  ORA-12541: Cannot connect. No listener at host ${database.replaceAll(/.*@\/\/([^:\/]+).*/, '$1')} port 1521.".toString(),
                                              'https://docs.oracle.com/error-help/db/ora-12541/\n  - Caused by: Connection refused\n']
        return header(version) + "ERROR: Exception Details\nERROR: Exception Primary Reason:  ${error[0]}\n\nUnexpected error running Liquibase: ${error[0]}\n${error[1]}For more information, please use the --log-level flag\n"
    }

    static String done(String command) {
        return "Liquibase command '${command}' was executed successfully.\n"
    }

    static String updateSql(List<String> ids, String version, String changelog) {
        String head = "-- *********************************************************************\n-- Update Database Script\n-- *********************************************************************\n" +
                "-- Change Log: ${changelog}\n-- Ran at: 10/31/26, 10:15 AM\n-- Against: null@offline:oracle?version=19.0\n-- Liquibase version: ${version}\n" +
                "-- *********************************************************************\n\n"
        return head + ids.collect { "-- Changeset ${it}\nCREATE OR REPLACE PACKAGE pkg_orders AS\n  FUNCTION total_for(p_customer IN VARCHAR2) RETURN NUMBER;\nEND pkg_orders;\n" }.join('\n')
    }

    static String executeSql(String sql, Map<String, List> rows, String version) {
        StringBuilder out = new StringBuilder()
        sql.split(/\n\/\n/).collect { it.trim() }.findAll { it }.each { String statement ->
            if (!statement.toLowerCase().startsWith('select')) {
                out << (version.startsWith('5.') ? "Successfully Executed: \n${statement}\n" : "Successfully Executed: ${statement}\n")
                return
            }
            String column = (statement =~ / AS (\w+) FROM /)[0][1]
            List values = rows[column] ?: []
            out << "Output of ${statement}:\n" << (values ? "${column} |\n" + values.collect { "${it} | " }.join('\n') + '\n' : '-- Empty Resultset --\n') << '\n'
        }
        return out.toString()
    }

    static String liquibase(FakeScript j, String text, String label, Map spec) {
        String version = quoted(text, /'\/opt\/liquibase\/([0-9.]+)\/liquibase'/) ?: '4.33.0'
        String out = quoted(text, /(?:tee|>) '([^']+)'\s*$/)
        String target = label.endsWith(' on the test database') ? 'test' : quoted(label, / on (\w+)$/)
        String props = j.files["${j.env.DSO_TMP}/${j.env.DSO_PROPS}".toString()] ?: ''
        String database = 'ORDERS_OWNER@' + (props.readLines().find { it.startsWith('liquibase.command.url=') } ?: '').replaceFirst(/^[^=]*=/, '').replace('\\\\', '\\')
        String changelog = quoted(text, /'--changelog-file=([^']+)'/)
        List<String> filtered = text.contains("'--context-filter=!test'") ? IDS.take(8) : IDS
        Map<String, Object> answer = [status: 0, text: '']
        Closure reply = { int status, String output -> answer.status = status; answer.text = output }
        Map compile = ((spec.compile ?: [:]) as Map)[target] as Map ?: [:]
        switch (label) {
            case 'DB: Liquibase validate (offline)':
                spec.validateError ? reply(1, failed(version, spec.validateError as String, '')) : reply(0, header(version) + 'No validation errors found.\n' + done('validate'))
                break
            case 'DB: Liquibase validate the package (offline)':
                spec.badPackage ? reply(1, failed(version, 'E3', '')) : reply(0, header(version) + 'No validation errors found.\n' + done('validate'))
                break
            case 'DB: Liquibase update-sql of all changesets (offline)':
            case 'DB: Liquibase update-sql for review (offline)':
                String file = quoted(text, /'--output-file=([^']+)'/)
                put(j, file, updateSql(filtered, version, changelog))
                reply(0, header(version) + "Output saved to ${file}\n" + done('update-sql'))
                break
            case 'DB: Liquibase future-rollback-sql (offline)':
                spec.noRollback ? reply(1, failed(version, 'rollback', '')) : reply(0, header(version) + done('future-rollback-sql'))
                if (!spec.noRollback) put(j, quoted(text, /'--output-file=([^']+)'/), filtered.reverse().collect { "-- Rolling Back ChangeSet: ${it}\n" }.join(''))
                break
            case "DB: Liquibase status on ${target}".toString():
                if (spec.connectFails == target) reply(1, failed(version, 'connect', database.substring(database.indexOf('@') + 1)))
                else if (spec.nothingRan == target) reply(0, "${database} is up to date\n" + done('status'))
                else reply(0, "${filtered.size()} changesets have not been applied to ${database}\n${filtered.collect { '     ' + it }.join('\n')}\n" + done('status'))
                break
            case "DB: invalid objects before the update on ${target}".toString():
                if (spec.noBaseline == target) {
                    reply(0, header(version) + done('execute-sql'))
                    break
                }
                reply(0, header(version) + executeSql(j.files[quoted(text, /'--sql-file=([^']+)'/)], [INVALID_BEFORE: compile.before as List], version) + done('execute-sql'))
                break
            case "DB: Liquibase update on ${target}".toString():
                if ((spec.updateError as Map)?.get(target)) {
                    String name = (spec.updateError as Map)[target] as String
                    reply(1, (name == 'lock' ? 'Waiting for changelog lock....\n' : '') + (name == 'ora-00955' ? IDS.take(3).collect { "Running Changeset: ${it}\n" }.join('') : '') + failed(version, name, ''))
                    break
                }
                List<String> ran = spec.nothingRan == target ? [] : filtered
                reply(0, header(version) + ran.collect { "Running Changeset: ${it}" }.join('\n') + "\n\nUPDATE SUMMARY\nRun:                          ${ran.size()}\n" +
                        "Previously run:               ${filtered.size() - ran.size()}\nFiltered out:                 ${IDS.size() - filtered.size()}\n-------------------------------\n" +
                        "Total change sets:           ${IDS.size()}\n\n" + (IDS.size() > filtered.size() ? "\nFILTERED CHANGE SETS SUMMARY\nContext mismatch:             ${IDS.size() - filtered.size()}\n\n" : '') +
                        'Liquibase: Update has been successful. Rows affected: 0\n' + done('update'))
                break
            case "DB: compile check on ${target}".toString():
                reply(0, header(version) + executeSql(j.files[quoted(text, /'--sql-file=([^']+)'/)],
                        [INVALID_UPDATED: compile.updated as List, INVALID_OBJECT: compile.invalid as List, COMPILE_ERROR: compile.errors as List], version) + done('execute-sql'))
                break
            case "DB: Liquibase status after the update on ${target}".toString():
                List<String> left = spec.stillPending == target ? [IDS[8]] : (spec.runAlways == target ? [filtered.last()] : [])
                reply(0, (left ? "${left.size()} changeset has not been applied to ${database}\n${left.collect { '     ' + it }.join('\n')}\n" : "${database} is up to date\n") + done('status'))
                break
            case "DB: Liquibase tag on ${target}".toString():
                spec.tagFails == target ? reply(1, failed(version, 'lock', '')) : reply(0, "Successfully tagged '${database}'\n" + done('tag'))
                break
            case 'DB: Liquibase unexpected-changesets on the test database':
                reply(0, spec.unexpected ? "2 unexpected changes were found in ${database}\n     db/changelog-master.xml::old-1::orders-team\n     db/changelog-master.xml::old-2::orders-team\n" + done('unexpected-changesets')
                        : "${database} contains no unexpected changes!\n" + done('unexpected-changesets'))
                break
            case 'DB: Liquibase drop-all on the test database':
                reply(0, "All objects dropped from ${database}\n" + done('drop-all'))
                break
            case 'DB: drop the code, types and synonyms left on the test database':
                spec.resetFails ? reply(1, failed(version, 'ora-01031', '')) : reply(0, header(version) + done('execute-sql'))
                break
            case "DB: Liquibase list-locks on ${target}".toString():
                reply(0, "Database change log locks for ${database}\n" + (spec.ownLock ? " - jenkins-agent-07#${j.env.DSO_LOCK_ID} (10.20.30.40) at Oct 31, 2026, 10:16:02 AM\n" : ' - No locks\n') + done('list-locks'))
                break
            case "DB: Liquibase release-locks on ${target}".toString():
                reply(0, "Successfully released all database change log locks for '${database}'\n" + done('release-locks'))
                break
            default:
                return null
        }
        put(j, out, answer.text as String)
        return answer.status
    }

    static Object utplsql(FakeScript j, String text, Map spec) {
        if (spec.badPassword) return 64
        String out = quoted(text, /tee '([^']+utplsql\.out)'/)
        if (spec.utNoConnect) {
            put(j, out, 'jdbc:oracle:thin:****/****@//dev-db.bbh.com:1521/ORDERS_CI: ORA-12541: Cannot connect. No listener at host dev-db.bbh.com port 1521.\n' +
                    'https://docs.oracle.com/error-help/db/ora-12541/\nCould not establish connection to database. Reason: ORA-12541: Cannot connect. No listener at host dev-db.bbh.com port 1521.\n' +
                    'https://docs.oracle.com/error-help/db/ora-12541/\n')
            return 1
        }
        String dir = quoted(text, /mkdir -p '([^']+)'/)
        put(j, "${dir}/utplsql-junit.xml", '<testsuites tests="2" skipped="0" error="0" failure="' + (spec.utFail ? 1 : 0) + '" name="" time=".031" >\n</testsuites>\n')
        put(j, "${dir}/utplsql-cobertura.xml", (spec.cobertura ?: '<?xml version="1.0"?>\n<!DOCTYPE coverage SYSTEM "http://cobertura.sourceforge.net/xml/coverage-04.dtd">\n' +
                '<coverage line-rate="0.8823529411764705882352941176470588235294" branch-rate="0.0" lines-covered="150" lines-valid="170" branches-covered="0" branches-valid="0" complexity="0" version="1" timestamp="1793441702">\n</coverage>\n') as String)
        put(j, "${dir}/utplsql-coverage-sonar.xml", '<coverage version="1">\n<file path="db/code/pkg_orders.pkb">\n<lineToCover lineNumber="3" covered="true"/>\n</file>\n</coverage>\n')
        put(j, "${dir}/utplsql-sonar-tests.xml", '<testExecutions version="1">\n<file path="db/test/ut_pkg_orders.pkb">\n<testCase name="total_without_orders" duration="12" >\n</testCase>\n</file>\n</testExecutions>\n')
        put(j, out, 'orders\n  Orders package\n    total_for returns 0 without orders [.012 sec]\n    add_order stores the order [.008 sec]\n \nFinished in .031 seconds\n' +
                "2 tests, ${spec.utFail ? 1 : 0} failed, 0 errored, 0 disabled, 0 warning(s)\n")
        return spec.utFail ? 2 : 0
    }

    static Object maven(FakeScript j, String text, String label, Map spec) {
        if (!text.contains('dependency:copy')) return null
        if (spec.fetchFails) throw new FakeAbort('script returned exit code 1')
        put(j, "${quoted(text, /-DoutputDirectory=(\S+)/)}/${label.substring('DB: fetch the delivered package '.length())}", 'PK-fake')
        return null
    }
}
