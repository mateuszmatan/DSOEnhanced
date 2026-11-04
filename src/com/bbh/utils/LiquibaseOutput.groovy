package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class LiquibaseOutput implements Serializable {

    @NonCPS
    static String version(String text) {
        String line = (text ?: '').readLines().find { it.startsWith('Liquibase Version: ') }
        return line ? line.substring('Liquibase Version: '.length()).trim() : ''
    }

    @NonCPS
    static String licence(String version) {
        if ((version ?: '').startsWith('4.')) return 'Apache-2.0 (core; bundle includes third-party and commercial components)'
        return (version ?: '').startsWith('5.') ? 'FSL-1.1-ALv2' : ''
    }

    @NonCPS
    static int compare(String a, String b) {
        List left = (a ?: '').tokenize('.')
        List right = (b ?: '').tokenize('.')
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            int x = part(i < left.size() ? left[i] as String : '0')
            int y = part(i < right.size() ? right[i] as String : '0')
            if (x != y) return x < y ? -1 : 1
        }
        return 0
    }

    @NonCPS
    static int part(String text) {
        return text ==~ /\d{1,9}/ ? Integer.parseInt(text) : 0
    }

    @NonCPS
    static String errorReason(String text) {
        List lines = (text ?: '').readLines()
        String marker = 'Unexpected error running Liquibase: '
        int at = lines.findIndexOf { String line -> line.startsWith(marker) }
        if (at < 0) return ''
        List parts = [(lines[at] as String).substring(marker.length()).trim()]
        for (int i = at + 1; i < lines.size() && i <= at + 6 && (lines[i] as String).startsWith(' '); i++) {
            parts << (lines[i] as String).trim()
        }
        return parts.findAll { it }.join(' ')
    }

    @NonCPS
    static String lastLine(String text, int max) {
        String line = ((text ?: '').readLines().reverse().find { it.trim() && !it.startsWith('https://docs.oracle.com/error-help/') } ?: '').trim()
        return line.length() > max ? line.substring(0, max) : line
    }

    @NonCPS
    static String reason(String text) {
        return errorReason(text) ?: lastLine(text, 300)
    }

    @NonCPS
    static String hint(String reason) {
        List rows = [
                [['check sum'], 'an applied changeset was edited: undo the edit and add a new changeset, or mark the changeset runOnChange="true" when it holds a CREATE OR REPLACE object'],
                [['Could not acquire change log lock'], 'another deployment holds the Liquibase lock of this schema: wait for it to end; if none runs, ask the DBA to check DATABASECHANGELOGLOCK before anyone runs liquibase release-locks'],
                [['Cannot find database driver'], 'set tools.oracleJdbc in the Global Settings of the DevSecOps portal'],
                [['Connection could not be created'], 'check the JDBC URL of the target, the network route from the agent to the database and the Jenkins credentials of the target'],
                [['was not found in the configured search path'], 'the changelog or a file it uses is outside the search root: check database.changelogFile, database.searchPath and the path attributes of the changelog'],
                [['requires a valid Liquibase'], 'the changelog uses a Liquibase Pro feature, which the library does not support (for example include inside formatted SQL): remove it'],
                [['ORA-04021'], 'the object is in use by application sessions: deploy QC when the application is idle (chapter 14.8)'],
                [['ORA-00955'], 'the object already exists: for a schema that existed before Liquibase, the DBA marks the existing changesets as run with changelog-sync, or the changeset gets a MARK_RAN precondition (chapter 14.8)'],
                [['ORA-01017'], 'the user or the password of the Jenkins credentials of the target is wrong'],
                [['ORA-01031'], 'the deploy user lacks a privilege: see the grants in chapter 14.8'],
                [['ORA-00900', 'ORA-00911', 'ORA-00933'], 'the statement reached Oracle with a SQL*Plus command, a ";" or a split in the wrong place: check splitStatements, endDelimiter and the static checks S1 and S3']
        ]
        String text = reason ?: ''
        List row = rows.find { List r -> (r[0] as List).any { text.contains(it as String) } }
        return row ? row[1] as String : ''
    }

    @NonCPS
    static String hintText(String reason) {
        String text = hint(reason)
        return text ? " - ${text}".toString() : ''
    }

    @NonCPS
    static int changesets(String sql) {
        return (sql ?: '').readLines().count { it.startsWith('-- Changeset ') } as int
    }

    @NonCPS
    static List indented(List lines, int from) {
        List ids = []
        for (int i = from; i < lines.size() && (lines[i] as String).startsWith('     '); i++) {
            ids << (lines[i] as String).trim()
        }
        return ids
    }

    @NonCPS
    static Map status(String text) {
        List lines = (text ?: '').readLines()
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i] as String
            if (line ==~ '\\d+ changesets? ha(s|ve) not been applied to .*') {
                return [parsed: true, pending: Integer.parseInt(line.tokenize(' ')[0] as String), ids: indented(lines, i + 1)]
            }
            if (line.endsWith(' is up to date')) return [parsed: true, pending: 0, ids: []]
        }
        return [parsed: false, pending: 0, ids: []]
    }

    @NonCPS
    static Map update(String text) {
        Map result = [parsed: false, run: 0, previouslyRun: 0, filteredOut: 0, total: 0, ran: []]
        Map keys = ['Run:': 'run', 'Previously run:': 'previouslyRun', 'Filtered out:': 'filteredOut', 'Total change sets:': 'total']
        for (String line : (text ?: '').readLines()) {
            if (line.startsWith('Running Changeset: ')) {
                (result.ran as List) << line.substring('Running Changeset: '.length()).trim()
                continue
            }
            for (def entry : keys.entrySet()) {
                String rest = line.startsWith(entry.key as String) ? line.substring((entry.key as String).length()).trim() : ''
                if (rest ==~ /\d{1,9}/) {
                    result[entry.value] = Integer.parseInt(rest)
                    if (entry.value == 'run') result.parsed = true
                }
            }
        }
        return result
    }

    @NonCPS
    static Map resultSets(String text, List columns) {
        Map result = [parsed: true]
        List lines = (text ?: '').readLines()
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i] as String
            if (!line.startsWith('Output of ')) continue
            String column = columns.find { line.contains(" AS ${it} ".toString()) }
            if (!column) continue
            String next = i + 1 < lines.size() ? (lines[i + 1] as String).trim() : ''
            List values = []
            if (next == "${column} |".toString()) {
                for (int j = i + 2; j < lines.size(); j++) {
                    String row = lines[j] as String
                    if (!row.trim() || row.trim() == '-- Empty Resultset --') break
                    values << (row.endsWith(' | ') ? row.substring(0, row.length() - 3) : row.replaceFirst(/\s*\|\s*$/, '')).trim()
                }
            } else if (next != '-- Empty Resultset --') {
                result.parsed = false
            }
            result[column] = values
        }
        if (columns.any { !result.containsKey(it) }) result.parsed = false
        return result
    }

    @NonCPS
    static String objectOf(String row) {
        List tokens = (row ?: '').tokenize(' ')
        return tokens.size() > 1 ? tokens.subList(0, tokens.size() - 1).join(' ') : ''
    }

    @NonCPS
    static Map compileVerdict(Map before, Map after, boolean baseline) {
        List previous = baseline ? ((before?.INVALID_BEFORE ?: []) as List) : []
        Map updated = [:]
        for (def row : (after?.INVALID_UPDATED ?: []) as List) {
            updated[objectOf(row as String)] = row
        }
        List failing = []
        List preexisting = []
        for (def object : (after?.INVALID_OBJECT ?: []) as List) {
            String stamped = updated[(object as String).trim()] as String
            if (baseline && stamped && previous.contains(stamped)) preexisting << object else failing << object
        }
        List errors = ((after?.COMPILE_ERROR ?: []) as List).findAll { def row ->
            failing.any { row.toString().startsWith("${it} line ".toString()) }
        }
        return [failing: failing, preexisting: preexisting, errors: errors]
    }

    @NonCPS
    static List pendingAfter(List ids, List ran) {
        return (ids ?: []).findAll { !(ran ?: []).contains(it) }
    }

    @NonCPS
    static Map unexpected(String text) {
        List lines = (text ?: '').readLines()
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i] as String
            if (line ==~ /\d+ unexpected changes? (was|were) found in .*/) {
                return [parsed: true, count: Integer.parseInt(line.tokenize(' ')[0] as String), ids: indented(lines, i + 1)]
            }
            if (line.endsWith('contains no unexpected changes!')) return [parsed: true, count: 0, ids: []]
        }
        return [parsed: false, count: 0, ids: []]
    }

    @NonCPS
    static boolean lockOf(String text, String lockId) {
        return (text ?: '').contains('#' + lockId + ' (')
    }

    @NonCPS
    static Map cobertura(String xml) {
        String line = (xml ?: '').readLines().find { it.contains('<coverage ') }
        Integer covered = line ? attributeNumber(line, 'lines-covered') : null
        Integer valid = line ? attributeNumber(line, 'lines-valid') : null
        return covered != null && valid != null ? [parsed: true, covered: covered, valid: valid] : [parsed: false, covered: 0, valid: 0]
    }

    @NonCPS
    static Integer attributeNumber(String line, String name) {
        int at = line.indexOf(name + '="')
        if (at < 0) return null
        String rest = line.substring(at + name.length() + 2)
        int end = rest.indexOf('"')
        String value = end >= 0 ? rest.substring(0, end) : ''
        return value ==~ /\d{1,9}/ ? Integer.parseInt(value) : null
    }

    @NonCPS
    static Map sonarLanguages(String json) {
        String text = json ?: ''
        return [analyzer: text.contains('"key":"plsql"') ? 'plsql' : (text.contains('"key":"plsqlopen"') ? 'plsqlopen' : '')]
    }

    @NonCPS
    static Map facts(String text) {
        List files = []
        Map lines = [:]
        Map last = [:]
        for (String line : (text ?: '').readLines()) {
            if (line.startsWith('F\t')) {
                files << line.substring(2)
                continue
            }
            String[] parts = line.split('\t', 4)
            if (parts.length < 4 || !(parts[2] ==~ /\d{1,9}/)) continue
            List entry = [Integer.parseInt(parts[2]), parts[3]]
            if (parts[0] == 'L') {
                List found = (lines[parts[1]] ?: []) as List
                found << entry
                lines[parts[1]] = found
            } else if (parts[0] == 'E') {
                last[parts[1]] = entry
            }
        }
        return [files: files, lines: lines, last: last]
    }

    @NonCPS
    static boolean changelogFile(String path) {
        return path ==~ /.*\.(xml|yaml|yml|json)/
    }

    @NonCPS
    static boolean candidate(String path, int number, String text) {
        if (changelogFile(path) || number == 1) return true
        String lower = (text ?: '').toLowerCase()
        return lower ==~ /[ \t]*(\/|set[ \t]|show[ \t]|prompt|spool|whenever|exit[ \t]|define[ \t]|exec|@|--changeset|--liquibase).*/ ||
                ['identified', '${', 'create', 'begin', 'declare'].any { lower.contains(it as String) }
    }

    @NonCPS
    static String extension(String path) {
        int dot = (path ?: '').lastIndexOf('.')
        return dot >= 0 ? path.substring(dot + 1) : ''
    }

    @NonCPS
    static List plsqlExtensions() {
        return ['pks', 'pkb', 'pls', 'plb', 'pck', 'prc', 'fnc', 'trg', 'tps', 'tpb', 'typ', 'bdy']
    }

    @NonCPS
    static boolean plsqlUnit(String text) {
        return text ==~ /(?i)\s*CREATE\s+(OR\s+REPLACE\s+)?((NON)?EDITIONABLE\s+)?(PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE)\b.*/ ||
                text ==~ /(?i)\s*(BEGIN|DECLARE)\b.*/
    }

    @NonCPS
    static Map attributes(String text, String separator) {
        Map values = [:]
        for (String name : ['path', 'relativeToChangelogFile', 'splitStatements', 'stripComments', 'endDelimiter']) {
            int at = -1
            for (String before : [' ', '\t', '\n', '{', ',', '"']) {
                int found = text.indexOf(before + name + (before == '"' ? '"' : '') + separator)
                if (found >= 0 && (at < 0 || found < at)) at = found + before.length() + name.length() + (before == '"' ? 1 : 0)
            }
            if (at < 0) continue
            String rest = text.substring(at + separator.length()).replaceFirst(/^\s*:?\s*/, '')
            String quote = rest.startsWith('"') || rest.startsWith("'") ? rest.substring(0, 1) : ''
            int end = quote ? rest.indexOf(quote, 1) : -1
            String value = quote ? (end > 0 ? rest.substring(1, end) : '') : rest.tokenize(' \t\n,}>')[0]
            if (value) values[name] = value
        }
        return values
    }

    @NonCPS
    static List sqlFiles(String changelog, List lines) {
        List texts = lines.collect { (it as List)[1] as String }
        List elements = []
        if (changelog.endsWith('.xml')) {
            String all = texts.join('\n')
            int at = all.indexOf('<sqlFile')
            while (at >= 0) {
                int end = all.indexOf('>', at)
                elements << LiquibaseOutput.attributes(all.substring(at, end < 0 ? all.length() : end), '=')
                at = all.indexOf('<sqlFile', at + 1)
            }
        } else if (changelog.endsWith('.json')) {
            String all = texts.join('\n')
            int at = all.indexOf('"sqlFile"')
            while (at >= 0) {
                int open = all.indexOf('{', at)
                int close = open >= 0 ? all.indexOf('}', open) : -1
                if (close < 0) break
                elements << LiquibaseOutput.attributes(all.substring(open, close + 1), ':')
                at = all.indexOf('"sqlFile"', close)
            }
        } else {
            for (int i = 0; i < texts.size(); i++) {
                String line = texts[i] as String
                if (!(line ==~ /\s*(-\s+)?sqlFile\s*:.*/)) continue
                String rest = line.substring(line.indexOf('sqlFile') + 'sqlFile'.length()).replaceFirst(/^\s*:\s*/, '')
                if (rest.startsWith('{')) {
                    elements << LiquibaseOutput.attributes(' ' + rest, ':')
                    continue
                }
                int indent = line.length() - line.replaceFirst(/^\s+/, '').length()
                List block = []
                for (int j = i + 1; j < texts.size(); j++) {
                    String next = texts[j] as String
                    if (next.trim() && next.length() - next.replaceFirst(/^\s+/, '').length() <= indent) break
                    block << next
                }
                elements << LiquibaseOutput.attributes('\n' + block.join('\n'), ':')
            }
        }
        String folder = BuildUtils.parentOf(changelog)
        return elements.findAll { (it as Map).path }.collect { Map e ->
            e + [changelog: changelog, file: DatabaseSettings.join(BuildUtils.booleanValue(e.relativeToChangelogFile, false) ? folder : '.', e.path as String),
                 split: BuildUtils.booleanValue(e.splitStatements, true), strip: BuildUtils.booleanValue(e.stripComments, false), delimiter: (e.endDelimiter ?: '') as String]
        }
    }

    @NonCPS
    static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text
    }

    @NonCPS
    static List<String> staticFindings(Map facts, Map s) {
        List files = (facts?.files ?: []) as List
        Map last = (facts?.last ?: [:]) as Map
        Map candidates = [:]
        for (def entry : ((facts?.lines ?: [:]) as Map).entrySet()) {
            String path = entry.key as String
            candidates[path] = ((entry.value ?: []) as List).findAll { List l -> LiquibaseOutput.candidate(path, l[0] as int, l[1] as String) }
        }
        List ignore = (s?.ignoreFiles ?: []) as List
        Closure ignored = { String path -> ignore.any { path == it || path.startsWith("${it}/".toString()) } }
        List plsql = plsqlExtensions()
        List source = plsql + ['sql', 'vw']
        List refs = []
        for (def path : files) {
            if (changelogFile(path as String)) refs.addAll(sqlFiles(path as String, (candidates[path] ?: []) as List))
        }
        List found = []
        Closure add = { int rule, String file, int line, String text -> found << [rule, file, line, text] }
        for (def item : files) {
            String path = item as String
            String ext = extension(path)
            List lines = (candidates[path] ?: []) as List
            List end = (last[path] ?: [0, '']) as List
            boolean skip = ignored(path)
            List named = refs.findAll { (it as Map).file == path }
            for (List l : lines) {
                int n = l[0] as int
                String text = l[1] as String
                boolean comment = text.trim().startsWith('--')
                if (!skip && source.contains(ext) && !comment && LiquibaseOutput.sqlPlus(text)) {
                    add(1, path, n, "${path} line ${n}: SQL*Plus command \"${LiquibaseOutput.cut(text.trim(), 60)}\" - Liquibase sends it to the database as SQL; remove it".toString())
                }
                if (!skip && plsql.contains(ext) && text ==~ /\s*\/\s*/ && n < (end[0] as int)
                        && !(named && named.every { (it as Map).delimiter })) {
                    add(2, path, n, "${path} line ${n}: a \"/\" line before the end of the file - one sqlFile deploys one unit: put each unit in its own file, or give the sqlFile endDelimiter=\"\\n/\"".toString())
                }
                if (!comment && text ==~ /(?i).*\bIDENTIFIED\s+BY(\s*|\s+(?!VALUES\b)("[^"']+"|[A-Z][\w#\x24]*|\x24\{).*)/) {
                    add(8, path, n, "${path} line ${n}: a password is written in the source (IDENTIFIED BY) - it would go into the package, Nexus and the review SQL; use IDENTIFIED EXTERNALLY or GLOBALLY, or let the DBA set the password".toString())
                }
                if (changelogFile(path) && text ==~ /(?i).*(<\s*(executeCommand|customChange)\b|^\s*-?\s*(executeCommand|customChange)\s*:|"(executeCommand|customChange)"\s*:).*/) {
                    String kind = text.toLowerCase().contains('executecommand') ? 'executeCommand' : 'customChange'
                    add(9, path, n, "${path} line ${n}: ${kind} runs code on the Jenkins agent - the library does not deploy it".toString())
                }
                for (String name : LiquibaseOutput.placeholders(text)) {
                    if (DatabaseSettings.isSecretName(name) || name ==~ '(?i).*(key|user(name)?).*' || ['LIQUIBASE_', 'DSO_', 'JENKINS'].any { name.toUpperCase().startsWith(it as String) }) {
                        add(9, path, n, "${path} line ${n}: \${${name}} names a secret or a variable of the agent - use database.parameters for values that differ between databases".toString())
                    }
                }
            }
            if (!skip && plsql.contains(ext) && end[0] && (end[1] as String) ==~ /(?i).*END\b[^;]*;\s*\/\s*/) {
                add(3, path, end[0] as int, "${path} line ${end[0]}: \"/\" on the line of END; - put the \"/\" on a line of its own".toString())
            }
            if (!skip && ext == 'sql' && lines && (lines[0] as List)[0] == 1 && ((lines[0] as List)[1] as String).trim().toLowerCase().startsWith('--liquibase formatted sql')) {
                LiquibaseOutput.formattedFindings(path, lines, add)
            }
            if (!skip && (plsql + ['vw']).contains(ext) && !named) {
                add(7, path, 0, "${path} is in no sqlFile of the changelog - includeAll does not deploy .${ext} files, so it is never deployed".toString())
            }
        }
        for (def item : refs) {
            Map ref = item as Map
            String file = ref.file as String
            if (ignored(file) || ignored(ref.changelog as String)) continue
            String ext = extension(file)
            String text = "${ref.changelog}: sqlFile ${ref.path}".toString()
            if (plsql.contains(ext) && ref.split && !ref.delimiter) {
                add(4, ref.changelog as String, 0, "${text} has no splitStatements=\"false\" - Liquibase splits a PL/SQL unit at every \";\"".toString())
            }
            if (plsql.contains(ext) && ref.strip) {
                add(5, ref.changelog as String, 0, "${text} has stripComments=\"true\" - the comments of the unit, and the utPLSQL annotations, are not stored in the database".toString())
            }
            List end = (last[file] ?: [0, '']) as List
            boolean unit = ((candidates[file] ?: []) as List).any { List l -> LiquibaseOutput.plsqlUnit(l[1] as String) }
            if (['vw', 'sql'].contains(ext) && !ref.split && (end[1] as String).trim().endsWith(';') && !unit) {
                add(6, ref.changelog as String, 0, "${text} has splitStatements=\"false\" and its statement ends with \";\" - Oracle refuses that for a view or plain SQL; remove splitStatements=\"false\" or the final \";\"".toString())
            }
        }
        return found.sort { a, b -> (a[0] as int) <=> (b[0] as int) ?: (a[1] as String) <=> (b[1] as String) ?: (a[2] as int) <=> (b[2] as int) }
                .collect { (it as List)[3] as String }.unique() as List<String>
    }

    @NonCPS
    static void formattedFindings(String path, List lines, Closure add) {
        List changesets = []
        for (List l : lines) {
            String text = l[1] as String
            if (text.trim().toLowerCase().startsWith('--changeset')) {
                changesets << [line: l[0], text: text, unit: false]
            } else if (changesets && LiquibaseOutput.plsqlUnit(text)) {
                (changesets[-1] as Map).unit = true
            }
        }
        for (def item : changesets) {
            Map c = item as Map
            if (!c.unit) continue
            String lower = (c.text as String).toLowerCase()
            List words = (c.text as String).trim().tokenize(' \t')
            String id = words.size() > 1 ? (words[1] as String) : ''
            id = id.contains(':') ? id.substring(id.indexOf(':') + 1) : id
            if (!lower.contains('splitstatements:false') && !lower.contains('enddelimiter:')) {
                add(4, path, c.line as int, "${path} line ${c.line}: changeset ${id} holds a PL/SQL unit but no splitStatements:false - Liquibase splits it at every \";\"".toString())
            }
            if (!lower.contains('stripcomments:false')) {
                add(5, path, c.line as int, "${path} line ${c.line}: changeset ${id} holds a PL/SQL unit without stripComments:false - its comments and utPLSQL annotations are removed".toString())
            }
        }
    }

    @NonCPS
    static boolean sqlPlus(String text) {
        return text ==~ /(?i)\s*(SET\s+(DEFINE|ECHO|FEEDBACK|SERVEROUTPUT|VERIFY|TERMOUT|HEADING|PAGESIZE|LINESIZE|TIMING|SQLBLANKLINES|SCAN|ESCAPE|TRIMSPOOL|AUTOCOMMIT)\b(?!\s*=).*|SHOW\s+ERR(ORS)?\b.*|PROMPT(\s.*)?|SPOOL\b.*|WHENEVER\s+(SQLERROR|OSERROR)\b.*|EXIT\s+(SUCCESS|FAILURE|WARNING|SQL\.SQLCODE)\b.*|DEFINE\s+\w+\s*=.*|EXEC(UTE)?\s+(?!IMMEDIATE\b)\w.*|@@?[\w.\/].*)/
    }

    @NonCPS
    static List<String> placeholders(String text) {
        List<String> names = []
        int at = text.indexOf('${')
        while (at >= 0) {
            int end = text.indexOf('}', at)
            if (end < 0) break
            names << text.substring(at + 2, end).trim()
            at = text.indexOf('${', end)
        }
        return names
    }

    @NonCPS
    static boolean blocks(String finding) {
        return ['(IDENTIFIED BY) - it would go', ' runs code on the Jenkins agent - ', ' names a secret or a variable of the agent - '].any { finding.contains(it as String) }
    }

    @NonCPS
    static String factsAwk() {
        return '''FNR == 1 { if (NR > 1) dso_end(); f = FILENAME; full = (f ~ /\\.(xml|yaml|yml|json)$/); last = 0; lastText = "" }
{ sub(/\\r$/, ""); if ($0 ~ /[^ \\t]/) { last = FNR; lastText = $0 } }
full || FNR == 1 || tolower($0) ~ /^[ \\t]*(\\/|set[ \\t]|show[ \\t]|prompt|spool|whenever|exit[ \\t]|define[ \\t]|exec|@|--changeset|--liquibase)|identified|\\$\\{|create|begin|declare/ { print "L\\t" f "\\t" FNR "\\t" $0 }
END { if (NR > 0) dso_end() }
function dso_end() { print "E\\t" f "\\t" last "\\t" lastText }
'''
    }

    @NonCPS
    static String factsScript(String root, String folder, String dbTmp) {
        String files = DatabaseSettings.quote("${dbTmp}/static-files.txt")
        List names = plsqlExtensions() + ['sql', 'vw', 'xml', 'yaml', 'yml', 'json']
        return ['#!/usr/bin/env bash', 'set -euo pipefail; set +x', 'export LC_ALL=C', "cd ${DatabaseSettings.quote(root)}",
                "find ${DatabaseSettings.quote(folder ?: '.')} -type f \\( ${names.collect { "-name '*.${it}'" }.join(' -o ')} \\) | sed 's|^\\./||' | LC_ALL=C sort > ${files}",
                "{ awk '{print \"F\\t\" \$0}' ${files}",
                "  if [ -s ${files} ]; then tr '\\n' '\\0' < ${files} | xargs -0 awk -f ${DatabaseSettings.quote("${dbTmp}/static-facts.awk")}; fi",
                "} > ${DatabaseSettings.quote("${dbTmp}/static-facts.txt")}"].join('\n') + '\n'
    }

    @NonCPS
    static String unscannedScript(String sourceDir) {
        List names = plsqlExtensions() + ['sql', 'vw']
        return ['#!/usr/bin/env bash', 'set -euo pipefail; set +x',
                "find ${DatabaseSettings.quote(sourceDir)} -type f \\( ${names.collect { "-name '*.${it}'" }.join(' -o ')} \\) -not -path '*/.dso-db/*' | sed 's|^\\./||' | LC_ALL=C sort |",
                "  awk '/\\.(prc|fnc|trg|vw|tps|tpb|typ|bdy)\$/ { k++; if (k <= 10) p[k] = \$0 } END { print k + 0, NR; for (i = 1; i <= k && i <= 10; i++) print p[i] }'"].join('\n') + '\n'
    }

    @NonCPS
    static String unscanned(String name, String output) {
        List lines = (output ?: '').readLines()
        List counts = lines ? (lines[0] as String).tokenize(' ') : []
        if (counts.size() != 2 || !(counts[0] ==~ /[1-9]\d{0,8}/)) return ''
        return "[SAST] ${name}: ${counts[0]} of ${counts[1]} SQL and PL/SQL files have extensions AppScan does not scan (.prc .fnc .trg .vw .tps .tpb .typ .bdy): ${lines.subList(1, lines.size()).join(', ')}".toString()
    }

    @NonCPS
    static String sonarLanguagesScript(String serverUrl, String dbTmp) {
        String request = "\"\$DSO_URL/api/languages/list?ps=0\" -o ${DatabaseSettings.quote("${dbTmp}/sonar-languages.txt")}"
        return ['#!/usr/bin/env bash', 'set -euo pipefail; set +x', "mkdir -p ${DatabaseSettings.quote(dbTmp)}",
                "DSO_URL=\"\${SONAR_HOST_URL:-}\"; [ -n \"\$DSO_URL\" ] || DSO_URL=${DatabaseSettings.quote(serverUrl)}",
                'if [ -n "${SONAR_AUTH_TOKEN:-}" ]; then',
                "  printf 'header = \"Authorization: Bearer %s\"\\n' \"\$SONAR_AUTH_TOKEN\" | curl -fsS --connect-timeout 10 --max-time 30 -K - ${request}",
                'else', "  curl -fsS --connect-timeout 10 --max-time 30 ${request}", 'fi'].join('\n') + '\n'
    }

    @NonCPS
    static String sharedPrelude() {
        return '''#!/usr/bin/env bash
set -euo pipefail; set +x
umask 077
DSO_U="${DSO_DB_USER:-}"; DSO_P="${DSO_DB_PASSWORD:-}"; unset DSO_DB_USER DSO_DB_PASSWORD
for DSO_V in $(compgen -e); do case "$DSO_V" in LIQUIBASE_*) unset "$DSO_V" ;; esac; done
export LIQUIBASE_ANALYTICS_ENABLED=false
dso_bad() { case "$1" in ''|' '*|*' ') return 0 ;; esac; ( LC_ALL=C; [[ $1 == *[![:print:]]* ]] ); }
if dso_bad "$DSO_U" || dso_bad "$DSO_P"; then echo '[DB] the user or password of the Jenkins credentials cannot be passed to Liquibase' >&2; exit 64; fi
dso_esc() { printf '%s' "${1//\\\\/\\\\\\\\}"; }
DSO_UU="$(printf '%s' "$DSO_U" | tr '[:lower:]' '[:upper:]')"
dso_mask() { local l; while IFS= read -r l || [ -n "$l" ]; do l="${l//"$DSO_P"/****}"; l="${l//"$DSO_U@"/****@}"; l="${l//"$DSO_UU@"/****@}"; printf '%s\\n' "$l"; done; }
'''
    }

    @NonCPS
    static String onlinePrelude() {
        return sharedPrelude() + '''DSO_STEP="$(mktemp "$DSO_TMP/step.XXXXXX")"
trap 'rm -f "$DSO_STEP"' EXIT
cat "$DSO_TMP/$DSO_PROPS" > "$DSO_STEP"
printf 'liquibase.command.username=%s\\nliquibase.command.password=%s\\n' "$(dso_esc "$DSO_U")" "$(dso_esc "$DSO_P")" >> "$DSO_STEP"
export -n DSO_TMP DSO_PROPS
export JAVA_OPTS="${JAVA_OPTS:-} -Dliquibase.hostDescription=$DSO_LOCK_ID"
'''
    }

    @NonCPS
    static String offlinePrelude() {
        return '''#!/usr/bin/env bash
set -euo pipefail; set +x
umask 077
for DSO_V in $(compgen -e); do case "$DSO_V" in LIQUIBASE_*) unset "$DSO_V" ;; esac; done
export LIQUIBASE_ANALYTICS_ENABLED=false
'''
    }

    @NonCPS
    static String utplsqlScript() {
        return sharedPrelude() + '''case "$DSO_P" in \\"*\\") echo '[DB] the password of the Jenkins credentials cannot be passed to utPLSQL' >&2; exit 64 ;; esac
dso_q() { local s="${1//\\\\/\\\\\\\\}"; printf '%s' "${s//\\"/\\\\\\"}"; }
DSO_ARGS="$(mktemp "$DSO_TMP/ut.XXXXXX")"
trap 'rm -f "$DSO_ARGS"' EXIT
printf '"%s/%s@%s"\\n' "$(dso_q "$DSO_U")" "$(dso_q "$DSO_P")" "$DSO_UT_CONNECT" > "$DSO_ARGS"
'''
    }

    @NonCPS
    static String downloadScript() {
        return '''#!/usr/bin/env bash
set -euo pipefail
if [ -f "$DSO_TOOL_DIR/.sha256" ] && [ "$(cat "$DSO_TOOL_DIR/.sha256")" = "$DSO_TOOL_SHA256" ]; then exit 0; fi
rm -rf "$DSO_TOOL_DIR" "$DSO_TOOL_DIR.download"; mkdir -p "$DSO_TOOL_DIR"
curl -fsSL --connect-timeout 30 --max-time 600 --retry 2 "$DSO_TOOL_URL" -o "$DSO_TOOL_DIR.download"
if command -v sha256sum >/dev/null 2>&1; then SUM="$(sha256sum "$DSO_TOOL_DIR.download" | cut -d' ' -f1)"; else SUM="$(shasum -a 256 "$DSO_TOOL_DIR.download" | cut -d' ' -f1)"; fi
if [ "$SUM" != "$DSO_TOOL_SHA256" ]; then echo "[DB] sha256 $SUM of $DSO_TOOL_URL is not $DSO_TOOL_SHA256" >&2; rm -f "$DSO_TOOL_DIR.download"; exit 3; fi
case "$DSO_TOOL_URL" in
  *.tar.gz|*.tgz) tar -xzf "$DSO_TOOL_DIR.download" -C "$DSO_TOOL_DIR" ;;
  *.zip) unzip -oq "$DSO_TOOL_DIR.download" -d "$DSO_TOOL_DIR" ;;
  *.jar) mv "$DSO_TOOL_DIR.download" "$DSO_TOOL_DIR/${DSO_TOOL_URL##*/}" ;;
  *) echo "[DB] $DSO_TOOL_URL is not a .tar.gz, .tgz, .zip or .jar" >&2; exit 4 ;;
esac
rm -f "$DSO_TOOL_DIR.download"
[ -f "$DSO_TOOL_DIR/liquibase" ] && chmod +x "$DSO_TOOL_DIR/liquibase"
[ -f "$DSO_TOOL_DIR/utPLSQL-cli/bin/utplsql" ] && chmod +x "$DSO_TOOL_DIR/utPLSQL-cli/bin/utplsql"
printf '%s' "$DSO_TOOL_SHA256" > "$DSO_TOOL_DIR/.sha256"
'''
    }
}
