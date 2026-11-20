package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class SqlChangelog implements Serializable {

    @NonCPS
    static String fileName() {
        return 'dso-changelog.xml'
    }

    @NonCPS
    static String marker() {
        return 'DSO-GENERATED-CHANGELOG'
    }

    @NonCPS
    static List<String> extensions() {
        return (['sql'] + LiquibaseOutput.plsqlExtensions() + ['vw']) as List<String>
    }

    @NonCPS
    static List<String> codeKinds() {
        return ['type', 'synonym', 'package', 'view', 'function', 'procedure', 'type body', 'package body', 'trigger']
    }

    @NonCPS
    static List<String> plsqlKinds() {
        return ['type', 'package', 'function', 'procedure', 'type body', 'package body', 'trigger']
    }

    @NonCPS
    static int rank(String kind, boolean code) {
        if (!code) return 15
        Map ranks = [type: 10, synonym: 20, package: 30, view: 40, function: 50, procedure: 60, 'type body': 70, 'package body': 80, trigger: 90]
        return (ranks[kind] ?: 15) as int
    }

    @NonCPS
    static boolean source(String path) {
        return extensions().contains(LiquibaseOutput.extension(path ?: ''))
    }

    @NonCPS
    static boolean hidden(String path) {
        return (path ?: '').tokenize('/').any { String part -> part.startsWith('.') }
    }

    @NonCPS
    static Map kindOf(List sorted) {
        int at = -1
        for (int i = 0; i < sorted.size(); i++) {
            String text = ((sorted[i] as List)[1] ?: '') as String
            if (text ==~ /(?i)\s*create\b.*/) {
                at = i
                break
            }
        }
        if (at < 0) return [kind: '', replace: false]
        int number = ((sorted[at] as List)[0]) as int
        List parts = []
        for (int i = at; i < sorted.size() && parts.size() < 3; i++) {
            List line = sorted[i] as List
            if ((line[0] as int) > number + 10) break
            String part = ((line[1] ?: '') as String).replaceFirst(/--.*$/, '').trim()
            if (part) parts << part
        }
        String text = parts.join(' ').toLowerCase().replaceAll(/\s+/, ' ').trim()
        def m = text =~ /^create (or replace )?(?:(?:editionable|noneditionable|editioning|force|noforce|public|unique|bitmap|global temporary|private temporary|shared|immutable|blockchain|sharded|duplicated) )*(package body|package|type body|type|materialized view|view|function|procedure|trigger|table|sequence|index|synonym)\b.*/
        if (!m.matches()) return [kind: 'other', replace: false]
        return [kind: m.group(2) as String, replace: m.group(1) != null]
    }

    @NonCPS
    static Map entry(String path, List lines, String testPath) {
        List sorted = (lines ?: []).collect { it as List }.sort { a, b -> (a[0] as int) <=> (b[0] as int) }
        String first = sorted && ((sorted[0] as List)[0] as int) == 1 ? (((sorted[0] as List)[1] ?: '') as String) : ''
        boolean formatted = first.trim().toLowerCase().startsWith('--liquibase formatted sql')
        int slashes = sorted.count { List l -> ((l[1] ?: '') as String) ==~ /\s*\/\s*/ } as int
        Map kind = kindOf(sorted)
        String found = kind.kind as String
        boolean block = !found && sorted.any { List l -> ((l[1] ?: '') as String) ==~ /(?i)\s*(BEGIN|DECLARE)\b.*/ }
        String ext = LiquibaseOutput.extension(path)
        boolean unit = SqlChangelog.plsqlKinds().contains(found) || block ||
                (LiquibaseOutput.plsqlExtensions().contains(ext) && !(found in ['table', 'sequence', 'index', 'view', 'synonym', 'materialized view']))
        boolean code = SqlChangelog.codeKinds().contains(found) && (kind.replace as boolean) && !formatted
        boolean test = testPath ? (path == testPath || path.startsWith("${testPath}/".toString())) : false
        return [path: path, kind: found, replace: kind.replace, formatted: formatted, slashes: slashes, unit: unit && !formatted, block: block,
                view: found in ['view', 'synonym'] || ext == 'vw', runOnChange: code, rank: rank(found, code), test: test]
    }

    @NonCPS
    static Map entries(Map facts, String testPath, List ignore) {
        List files = (facts?.files ?: []) as List
        Map lines = (facts?.lines ?: [:]) as Map
        Map last = (facts?.last ?: [:]) as Map
        List skip = (ignore ?: []) as List
        List out = []
        List empty = []
        boolean foreign = false
        for (def item : files) {
            String path = item as String
            if (path == fileName()) {
                foreign = !(((lines[path] ?: []) as List).any { List l -> ((l[1] ?: '') as String).contains(SqlChangelog.marker()) })
                continue
            }
            if (!source(path) || hidden(path) || skip.any { path == it || path.startsWith("${it}/".toString()) }) continue
            List end = (last[path] ?: [0, '']) as List
            if (!(end[0] as int)) {
                empty << path
                continue
            }
            out << entry(path, (lines[path] ?: []) as List, testPath)
        }
        return [entries: ordered(out), empty: empty, foreign: foreign]
    }

    @NonCPS
    static List ordered(List entries) {
        return (entries ?: []).collect { it as Map }.sort { Map a, Map b ->
            (a.rank as int) <=> (b.rank as int) ?: SqlChangelog.naturalCompare(a.path as String, b.path as String)
        }
    }

    @NonCPS
    static boolean digit(String ch) {
        return ch >= '0' && ch <= '9'
    }

    @NonCPS
    static List<String> chunks(String text) {
        List<String> out = []
        String value = text ?: ''
        int start = 0
        for (int i = 1; i <= value.length(); i++) {
            if (i == value.length() || digit(value.substring(i, i + 1)) != digit(value.substring(i - 1, i))) {
                out << value.substring(start, i)
                start = i
            }
        }
        return out
    }

    @NonCPS
    static int naturalCompare(String a, String b) {
        List<String> x = chunks(a)
        List<String> y = chunks(b)
        for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
            String p = x[i]
            String q = y[i]
            int found
            if (digit(p.substring(0, 1)) && digit(q.substring(0, 1))) {
                String m = p.replaceFirst(/^0+(?=\d)/, '')
                String n = q.replaceFirst(/^0+(?=\d)/, '')
                found = m.length() <=> n.length() ?: m <=> n ?: p.length() <=> q.length()
            } else {
                found = p.toLowerCase() <=> q.toLowerCase() ?: p <=> q
            }
            if (found != 0) return found
        }
        return x.size() <=> y.size()
    }

    @NonCPS
    static String escape(String text) {
        return (text ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&apos;')
    }

    @NonCPS
    static String sqlFileOptions(Map e) {
        if (e.unit) return (e.slashes as int) > 1 ? ' endDelimiter="\\n/" stripComments="false"' : ' splitStatements="false" stripComments="false"'
        if (e.view) return ' stripComments="false"'
        return ''
    }

    @NonCPS
    static String changelog(List entries) {
        List out = ['<?xml version="1.0" encoding="UTF-8"?>',
                    "<!-- ${marker()}: written by the DevSecOps pipeline for buildTool sql from the SQL and PL/SQL files of this folder; do not edit or commit it -->".toString(),
                    '<databaseChangeLog',
                    '    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"',
                    '    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"',
                    '    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog',
                    '        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd"',
                    "    logicalFilePath=\"${fileName()}\">".toString(),
                    '']
        for (def item : entries ?: []) {
            Map e = item as Map
            String path = escape(e.path as String)
            String context = e.test ? ' context="test"' : ''
            if (e.formatted) {
                out << "    <include file=\"${path}\" relativeToChangelogFile=\"true\"${context}/>".toString()
                continue
            }
            out << "    <changeSet id=\"${path}\" author=\"devsecops\"${e.runOnChange ? ' runOnChange="true"' : ''}${context}>".toString()
            out << "        <sqlFile path=\"${path}\" relativeToChangelogFile=\"true\" encoding=\"UTF-8\"${sqlFileOptions(e)}/>".toString()
            if (e.runOnChange) out << '        <rollback/>'
            out << '    </changeSet>'
        }
        out << '</databaseChangeLog>'
        return out.join('\n') + '\n'
    }

    @NonCPS
    static String kindText(Map e) {
        if (e.formatted) return 'Liquibase formatted SQL, included as it is'
        String kind = e.kind as String
        if (e.block) return 'PL/SQL block'
        if (!kind || kind == 'other') return e.unit ? 'PL/SQL' : 'SQL script'
        return e.replace ? "create or replace ${kind}".toString() : "create ${kind}".toString()
    }

    @NonCPS
    static List<String> summary(String name, String root, Map found) {
        List entries = (found?.entries ?: []) as List
        int changes = entries.count { Map e -> e.runOnChange } as int
        int formatted = entries.count { Map e -> e.formatted } as int
        int tests = entries.count { Map e -> e.test } as int
        String included = formatted ? ", ${formatted} Liquibase formatted SQL ${formatted == 1 ? 'file' : 'files'} included as ${formatted == 1 ? 'it is' : 'they are'}".toString() : ''
        List<String> lines = ["[DB] ${name}: buildTool sql - ${fileName()} generated in ${root} from ${entries.size()} SQL and PL/SQL ${entries.size() == 1 ? 'file' : 'files'}: ${entries.size() - changes - formatted} run once, ${changes} run again when they change${included}, ${tests} only on the test database (context test)".toString()]
        for (int i = 0; i < entries.size() && i < 200; i++) {
            Map e = entries[i] as Map
            String when = e.formatted ? 'its own changesets' : (e.runOnChange ? 'runs again when it changes' : 'runs once')
            lines << "[DB] ${name}: ${i + 1}. ${e.path} - ${kindText(e)}, ${when}${e.test ? ', test database only' : ''}".toString()
        }
        if (entries.size() > 200) lines << "[DB] ${name}: ... and ${entries.size() - 200} more, in ${root}/${fileName()} of the package".toString()
        for (def path : (found?.empty ?: []) as List) {
            lines << "[DB] ${name}: ${path} is empty - left out of the generated changelog".toString()
        }
        return lines
    }
}
