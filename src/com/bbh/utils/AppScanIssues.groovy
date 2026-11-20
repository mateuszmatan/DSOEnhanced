package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class AppScanIssues implements Serializable {

    @NonCPS
    static int pageSize() {
        return 500
    }

    @NonCPS
    static int maxIssues() {
        return 5000
    }

    @NonCPS
    static int maxAdvisories() {
        return 40
    }

    @NonCPS
    static List items(def page) {
        if (page instanceof List) return page as List
        def values = (page instanceof Map) ? (page as Map).get('Items') : null
        return (values instanceof List) ? (values as List) : null
    }

    @NonCPS
    static List<Map> findings(List issues) {
        List<Map> out = []
        for (def raw : (issues ?: [])) {
            if (!(raw instanceof Map)) continue
            Map issue = raw as Map
            String status = text(issue.get('Status')).toLowerCase().replaceAll(/\s+/, '')
            if (status in ['noise', 'passed', 'fixed']) continue
            String severity = text(issue.get('Severity')).toUpperCase()
            if (!severity) continue
            Map place = location(text(issue.get('SourceFile')), issue.get('Line'), text(issue.get('Location')))
            String type = text(issue.get('IssueType')) ?: text(issue.get('IssueTypeId')) ?: 'Security issue'
            String discovery = (text(issue.get('DiscoveryMethod')) ?: text(issue.get('Scanner'))).toUpperCase()
            String cwe = text(issue.get('Cwe'))
            String api = text(issue.get('Api'))
            List details = []
            if (cwe) details << "CWE-${cwe.replaceFirst(/(?i)^CWE-?/, '')}".toString()
            if (api) details << "API ${api}".toString()
            String where = place.file ? (place.line ? "${place.file}:${place.line}".toString() : place.file as String) : text(issue.get('Location'))
            out << [
                    id         : text(issue.get('Id')),
                    typeKey    : text(issue.get('IssueTypeId')) ?: type,
                    file       : place.file,
                    line       : place.line,
                    severity   : severity,
                    type       : type,
                    tool       : discovery.contains('SCA') ? 'AppScan SCA' : 'AppScan SAST',
                    description: ("Found by HCL AppScan${where ? ' in ' + where : ''}${details ? ' (' + details.join(', ') + ')' : ''}").toString(),
                    remediation: ''
            ]
        }
        return out
    }

    @NonCPS
    static Map location(String sourceFile, def lineValue, String location) {
        String file = (sourceFile ?: '').trim()
        Integer line = number(lineValue)
        String place = (location ?: '').trim()
        def colon = place =~ /^(.+?):(\d+)(?::\d+)?$/
        def bracket = place =~ /^(.+?)\((\d+)\)$/
        if (colon.find()) {
            if (!file) file = colon.group(1).trim()
            if (line == null) line = Integer.parseInt(colon.group(2))
        } else if (bracket.find()) {
            if (!file) file = bracket.group(1).trim()
            if (line == null) line = Integer.parseInt(bracket.group(2))
        } else if (!file && place ==~ /^[^\s:]+\.[A-Za-z0-9]{1,8}$/) {
            file = place
        }
        return [file: file ?: null, line: (line != null && line > 0) ? line : null]
    }

    @NonCPS
    static List<Map> advisoryRequests(List<Map> findings, int limit) {
        List<String> order = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
        List<Map> out = []
        List<String> seen = []
        for (String severity : order) {
            for (Map f : (findings ?: [])) {
                if (f.severity != severity || !f.id || seen.contains(f.typeKey as String)) continue
                if (out.size() >= limit) return out
                seen << (f.typeKey as String)
                out << [typeKey: f.typeKey, id: f.id]
            }
        }
        return out
    }

    @NonCPS
    static int applyAdvisories(List<Map> findings, Map advisories) {
        int applied = 0
        for (Map f : (findings ?: [])) {
            String fix = (advisories?.get(f.typeKey as String) ?: '') as String
            if (fix) {
                f.remediation = fix
                applied++
            }
        }
        return applied
    }

    @NonCPS
    static String fixRecommendation(String body) {
        String raw = (body ?: '').trim()
        if (!raw) return ''
        String names = 'fix[_\\-\\s]?recommendations?(?:[_\\-\\s]?text)?|how[_\\-\\s]?to[_\\-\\s]?fix|remediation|recommendations?'
        String found = ''
        if (raw.startsWith('{') || raw.startsWith('[')) {
            def json = raw =~ ('(?is)"(?:' + names + ')"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"')
            if (json.find()) found = unescapeJson(json.group(1))
        }
        if (!found) {
            def xml = raw =~ ('(?is)<(?:[\\w.\\-]+:)?(' + names + ')\\b[^>]*>(.*?)</(?:[\\w.\\-]+:)?\\1\\s*>')
            if (xml.find()) found = xml.group(2)
        }
        if (!found) {
            def html = raw =~ '(?is)<(h[1-6]|b|strong|th|dt)\\b[^>]*>[^<]*(?:fix\\s*recommendations?|how\\s*to\\s*fix|remediation)[^<]*</\\1\\s*>(.*?)(?=<h[1-6]\\b|\\z)'
            if (html.find()) found = html.group(2)
        }
        return clean(found)
    }

    @NonCPS
    static String clean(String fragment) {
        String t = fragment ?: ''
        t = t.replaceAll(/(?s)<!\[CDATA\[(.*?)\]\]>/, '$1')
        if (t.contains('&lt;')) t = unescapeXml(t)
        t = t.replaceAll(/(?is)<(script|style)\b.*?<\/\1\s*>/, ' ')
        t = t.replaceAll(/(?i)<li\b[^>]*>/, '\n- ').replaceAll(/(?i)<(?:br|\/p|\/div|\/li|\/tr|\/h[1-6])\b[^>]*>/, '\n')
        t = t.replaceAll(/(?s)<[^>]+>/, ' ')
        t = unescapeXml(t)
        List<String> lines = []
        for (String line : t.split('\n')) {
            String trimmed = line.replaceAll(/[ \t\r]+/, ' ').trim()
            if (trimmed && trimmed != '-') lines << trimmed
        }
        String joined = lines.join('\n')
        return joined.length() > 1500 ? joined.substring(0, 1500).trim() + ' ...' : joined
    }

    @NonCPS
    static String unescapeXml(String value) {
        return (value ?: '').replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"').replace('&apos;', "'")
                .replace('&#39;', "'").replace('&nbsp;', ' ').replace('&#160;', ' ').replace('&amp;', '&')
    }

    @NonCPS
    static String unescapeJson(String value) {
        return (value ?: '').replace('\\r', '').replace('\\n', '\n').replace('\\t', ' ').replace('\\"', '"').replace('\\/', '/').replace('\\\\', '\\')
    }

    @NonCPS
    private static String text(def value) {
        if (value == null) return ''
        String s = value.toString().trim()
        return s == 'null' ? '' : s
    }

    @NonCPS
    private static Integer number(def value) {
        String s = text(value)
        return (s ==~ /\d{1,9}/) ? Integer.parseInt(s) : null
    }
}
