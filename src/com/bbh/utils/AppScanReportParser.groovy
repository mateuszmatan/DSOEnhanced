package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class AppScanReportParser implements Serializable {

    @NonCPS
    static Map counts(String html) {
        if (!html) return [parsed: false, critical: 0, high: 0, medium: 0, low: 0, total: 0]
        String n = maybeUnescape(html)
        n = n.replaceAll(/(?is)<script[^>]*>.*?<\/script>/, ' ').replaceAll(/(?is)<style[^>]*>.*?<\/style>/, ' ')

        Map out = [critical: null, high: null, medium: null, low: null, total: null]
        String table = extractSummaryTable(n)
        if (table) {
            Map sev = extractSevCountsFromTable(table)
            out.critical = sev.critical
            out.high     = sev.high
            out.medium   = sev.medium
            out.low      = sev.low
            out.total    = extractTotalFromTable(table)
        }
        if (out.total == null) out.total = extractTotalFromExec(n)

        Map headers = countSevFromHeaders(n)
        Map issues  = countSevFromIssueBlocks(n)
        List keys = ['critical', 'high', 'medium', 'low']
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i) as String
            if (out[k] == null) out[k] = (headers[k] ?: 0) as int
            if ((out[k] as int) == 0 && ((issues[k] ?: 0) as int) > 0) out[k] = (issues[k] ?: 0) as int
        }
        if (out.total == null) out.total = (out.critical as int) + (out.high as int) + (out.medium as int) + (out.low as int)
        out.parsed = true
        return out
    }

    @NonCPS
    static Map severities(String html) {
        Map parsed = counts(html)
        return [
                critical: (parsed.critical ?: 0) as int,
                high    : (parsed.high     ?: 0) as int,
                medium  : (parsed.medium   ?: 0) as int,
                low     : (parsed.low      ?: 0) as int
        ]
    }

    @NonCPS
    static String maybeUnescape(String s) {
        if (!s || !(s.contains('&lt;') && s.contains('&gt;'))) return s
        return s.replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"')
                .replace('&#39;', "'").replace('&nbsp;', ' ').replace('&amp;', '&')
    }

    @NonCPS
    static String extractSummaryTable(String html) {
        def m = (html =~ /(?is)<h3\b[^>]*>\s*Summary\s+of\s+security\s+issues\s*<\/h3>/)
        if (!m.find()) return null
        def t = (html.substring(m.end()) =~ /(?is)<table\b[^>]*>(.*?)<\/table>/)
        return t.find() ? "<table>${t.group(1)}</table>" : null
    }

    @NonCPS
    static Map extractSevCountsFromTable(String table) {
        Map out = [critical: null, high: null, medium: null, low: null]
        def m = (table =~ /(?is)<tr\b[^>]*>\s*<td\b[^>]*>\s*(Critical|High|Medium|Low)\s+severity\s+issues\s*:\s*<\/td>\s*<td\b[^>]*>\s*(\d+)\s*<\/td>\s*<\/tr>/)
        while (m.find()) {
            String sev = m.group(1).toLowerCase()
            if (out.containsKey(sev)) out[sev] = m.group(2) as int
        }
        return out
    }

    @NonCPS
    static Integer extractTotalFromTable(String table) {
        def m = (table =~ /(?is)<td\b[^>]*>\s*Total\s+security\s+issues\s*:\s*<\/td>\s*<td\b[^>]*>\s*(\d+)\s*<\/td>/)
        return m.find() ? (m.group(1) as int) : null
    }

    @NonCPS
    static Integer extractTotalFromExec(String html) {
        def m = (html =~ /(?is)Total\s+security\s+issues\s*:\s*<span\b[^>]*class\s*=\s*["']count["'][^>]*>\s*(\d+)\s*<\/span>/)
        return m.find() ? (m.group(1) as int) : null
    }

    @NonCPS
    static Map countSevFromHeaders(String html) {
        Map out = [critical: 0, high: 0, medium: 0, low: 0]
        def m = (html =~ /(?is)<div\b[^>]*class\s*=\s*["']name["'][^>]*>\s*Severity:\s*<\/div>\s*<div\b[^>]*class\s*=\s*["']value["'][^>]*>.*?<span\b[^>]*>\s*(Critical|High|Medium|Low)\s*<\/span>/)
        while (m.find()) {
            String sev = m.group(1).toLowerCase()
            if (out.containsKey(sev)) out[sev] = (out[sev] as int) + 1
        }
        return out
    }

    @NonCPS
    static Map countSevFromIssueBlocks(String html) {
        Map out = [critical: 0, high: 0, medium: 0, low: 0]
        String[] parts = html.split(/(?i)Issue\s*ID\s*:/)
        for (int i = 1; i < parts.length; i++) {
            String text = parts[i].replaceAll(/(?s)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
            def severityMatcher = text =~ /(?i)Severity\s*:?\s*(Critical|High|Medium|Low)/
            if (!severityMatcher.find()) continue
            def statusMatcher = text =~ /(?i)Status\s*:?\s*(Open|New|In\s*Progress|Passed|Noise|Fixed)/
            if (statusMatcher.find()) {
                String status = statusMatcher.group(1)
                if (!status.contains('Open') && !status.contains('New')) continue
            }
            String sev = severityMatcher.group(1).replaceAll(/\s+/, ' ').trim().toLowerCase()
            if (out.containsKey(sev)) out[sev] = (out[sev] as int) + 1
        }
        return out
    }
}
