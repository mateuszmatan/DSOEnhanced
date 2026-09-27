package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class FlutterSonarReports implements Serializable {

    @NonCPS
    static String coverageXml(String lcov, String workspace) {
        StringBuilder sb = new StringBuilder()
        sb.append('<coverage version="1">\n')
        String path = ''
        List lines = []
        for (String raw : splitLines(lcov)) {
            String line = raw.trim()
            if (line.startsWith('SF:')) {
                path = relative(line.substring(3).trim(), workspace)
                lines = []
            } else if (line.startsWith('DA:')) {
                List parts = line.substring(3).split(',') as List
                if (parts.size() >= 2 && isNumber(parts[0] as String)) {
                    boolean covered = isNumber(parts[1] as String) && ((parts[1] as String) as int) > 0
                    lines << [number: (parts[0] as String) as int, covered: covered]
                }
            } else if (line == 'end_of_record') {
                appendFile(sb, path, lines)
                path = ''
                lines = []
            }
        }
        appendFile(sb, path, lines)
        sb.append('</coverage>\n')
        return sb.toString()
    }

    @NonCPS
    private static void appendFile(StringBuilder sb, String path, List lines) {
        if (!path || !lines) return
        sb.append('  <file path="').append(escapeXml(path)).append('">\n')
        for (Map line : lines) {
            sb.append('    <lineToCover lineNumber="').append(line.number)
              .append('" covered="').append(line.covered ? 'true' : 'false').append('"/>\n')
        }
        sb.append('  </file>\n')
    }

    @NonCPS
    static String issuesJson(String machineOutput, String workspace) {
        List issues = []
        for (String raw : splitLines(machineOutput)) {
            String line = raw.trim()
            if (!line || !line.contains('|')) continue
            List parts = line.split(/\|/) as List
            if (parts.size() < 8) continue
            String severity = (parts[0] as String).trim().toUpperCase()
            String ruleId = (parts[2] as String).trim()
            String file = relative((parts[3] as String).trim(), workspace)
            String lineNumber = (parts[4] as String).trim()
            String message = unescapeAnalyzer(parts.subList(7, parts.size()).join('|'))
            if (!ruleId || !file || !isNumber(lineNumber)) continue
            issues << [
                    engineId       : 'dart-analyze',
                    ruleId         : ruleId,
                    severity       : sonarSeverity(severity),
                    type           : sonarType(severity),
                    primaryLocation: [
                            message  : message ?: ruleId,
                            filePath : file,
                            textRange: [startLine: lineNumber as int]
                    ]
            ]
        }
        return RestClient.toJson([issues: issues])
    }

    @NonCPS
    static String sonarSeverity(String dartSeverity) {
        if (dartSeverity == 'ERROR') return 'MAJOR'
        if (dartSeverity == 'WARNING') return 'MINOR'
        return 'INFO'
    }

    @NonCPS
    static String sonarType(String dartSeverity) {
        return dartSeverity == 'ERROR' ? 'BUG' : 'CODE_SMELL'
    }

    @NonCPS
    static String relative(String path, String workspace) {
        String value = (path ?: '').replace('\\', '/').trim()
        String root = (workspace ?: '').replace('\\', '/').replaceAll('/+$', '')
        if (root && value.startsWith(root + '/')) value = value.substring(root.length() + 1)
        return value.startsWith('./') ? value.substring(2) : value
    }

    @NonCPS
    static String unescapeAnalyzer(String message) {
        return (message ?: '').replace('\\|', '|').replace('\\\\', '\\').trim()
    }

    @NonCPS
    static String escapeXml(String value) {
        return (value ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
                .replace('"', '&quot;').replace("'", '&apos;')
    }

    @NonCPS
    private static List splitLines(String text) {
        if (!text) return []
        return text.replace('\r\n', '\n').replace('\r', '\n').split('\n') as List
    }

    @NonCPS
    private static boolean isNumber(String value) {
        return value != null && (value.trim() ==~ /\d+/)
    }
}
