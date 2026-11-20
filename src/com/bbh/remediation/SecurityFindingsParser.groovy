package com.bbh.remediation

import com.bbh.core.PipelineState
import com.bbh.utils.AppScanReportParser
import com.bbh.utils.BuildUtils
import com.bbh.utils.RestClient
import com.cloudbees.groovy.cps.NonCPS

class SecurityFindingsParser implements Serializable {

    private final def script
    private final PipelineState state
    private final RestClient rest

    SecurityFindingsParser(def script, PipelineState state, RestClient rest) {
        this.script = script
        this.state = state
        this.rest = rest
    }

    String resolveReportPath(String prefix, String projectName, Map cfg) {
        String ws = (script.env.WORKSPACE ?: '').trim()
        Map projScan = (state.projectsScanResults?.get(projectName) ?: [:]) as Map
        String recorded = (prefix == 'appscan-report' ? projScan.get('sast_file') : projScan.get('dast_file')) as String
        if (recorded) {
            String p = ws ? "${ws}/${recorded}" : recorded
            if (script.fileExists(p)) return p
        }
        String derivedScan = BuildUtils.scanName(cfg?.tools?.sonar?.projectName ?: projectName)
        String envScan = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        for (String sName : [derivedScan, envScan].findAll { it }.unique()) {
            String candidate = ws ? "${ws}/${prefix}-${sName}.html" : "${prefix}-${sName}.html"
            if (script.fileExists(candidate)) return candidate
        }
        return ''
    }

    List<Map> extractSastFindings(String projectName = state.currentProjectName, Map cfg = state.cfg) {
        List<Map> out = []
        String ws = (script.env.WORKSPACE ?: '').trim()
        String repoPrefix = resolveRepoPrefix(cfg)
        String reportFile = resolveReportPath('appscan-report', projectName, cfg)
        if (!reportFile) return out

        try {
            out.addAll(SecurityFindingsParser.sastFindingsFromHtml(AppScanReportParser.maybeUnescape(script.readFile(reportFile) ?: ''), ws, repoPrefix))
        } catch (Exception ignored) {
        }
        return out
    }

    @NonCPS
    static List<Map> sastFindingsFromHtml(String html, String ws = '', String repoPrefix = '') {
        List<Map> out = []
        String[] parts = (html ?: '').split(/(?i)(?:Issue\s*ID|Finding\s*ID)\s*:/)
        if (parts.length <= 1) {
            parts = (html ?: '').split(/(?is)(?=<div\b[^>]*class\s*=\s*["']name["'][^>]*>\s*Severity\s*:)/)
        }
        for (int i = 1; i < parts.length; i++) {
            String text = parts[i].replaceAll(/(?s)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
            def sevM = text =~ /(?i)Severity\s*:?\s*(Critical|High|Medium|Low|Informational|Info)/
            if (!sevM.find()) continue
            def statM = text =~ /(?i)Status\s*:?\s*(Open|New|In\s*Progress|Reopened|Passed|Noise|Fixed)/
            if (statM.find()) {
                String st = statM.group(1).toLowerCase()
                if (st in ['noise', 'passed', 'fixed']) continue
            }

            def typeM = text =~ /(?i)(?:Issue\s*Type|Vulnerability\s*Type|Type)\s*:?\s*(.+?)(?=\s+(?:Source\s*File|File\s*Name|Filename|File|Location|Path|Line|Severity|Status|Fix\s*Recommendation|How\s*to\s*Fix|Recommendation|Remediation|Cause|Details|CWE|CVSS)\s*:|[,\n\r]|$)/
            def fileM = text =~ /(?i)(?:Source\s*File|File\s*Name|Filename|File|Location|Path)\s*:?\s*([^\s,;<>'"]+?\.(?:java|kt|kts|groovy|scala|jsp|jspx|cs|csx|fs|fsx|vb|cshtml|vbhtml|razor|aspx|ascx|ashx|xaml|config|csproj|fsproj|vbproj|props|targets|py|pyw|js|jsx|ts|tsx|mjs|cjs|vue|svelte|html|htm|css|scss|dart|swift|m|mm|h|hpp|c|cc|cpp|sql|pls|plb|pks|pkb|pck|prc|fnc|trg|xml|yaml|yml|json|gradle|properties|sh|ps1))(?:[:\(](\d+)\)?|\b)/
            def lineM = text =~ /(?i)Line(?:\s*Number|\s*#)?\s*:?\s*(\d+)/
            def remM = text =~ /(?i)(?:Fix\s*Recommendation|How\s*to\s*Fix|Recommendation|Remediation)\s*:?\s*(.+?)(?=(?:Issue\s*ID|Finding\s*ID|Cause|Details|CWE|$))/

            boolean hasFile = fileM.find()
            String file = hasFile ? SecurityFindingsParser.normalizeRepoPath(fileM.group(1), ws, repoPrefix) : null
            String lineRaw = lineM.find() ? lineM.group(1) : (hasFile ? fileM.group(2) : null)
            Integer line = lineRaw ? Integer.parseInt(lineRaw) : (file ? 1 : null)
            if (file && line) {
                out << [
                        file       : file,
                        line       : line,
                        severity   : sevM.group(1).toUpperCase(),
                        type       : typeM.find() ? typeM.group(1).trim() : 'Security Vulnerability',
                        description: "Vulnerability detected by HCL AppScan SAST in ${file}:${line}".toString(),
                        remediation: remM.find() ? remM.group(1).trim() : 'Review source code and sanitize input or apply defensive controls.'
                ]
            }
        }
        return out
    }

    List<Map> extractSonarIssues(String projectName = state.currentProjectName, Map cfg = state.cfg) {
        List<Map> out = []
        def sonarCfg = cfg?.tools?.sonar ?: [:]
        String projectKey = (state.projectsSonarResults?.get(projectName)?.get('projectKey') ?: sonarCfg.projectKey ?: '') as String
        String sonarUrl = (sonarCfg.serverUrl ?: 'https://tools.bbh.com/sonar').toString().replaceAll('/+$', '')
        String authToken = (sonarCfg.authToken ?: '') as String
        String ws = (script.env.WORKSPACE ?: '').trim()
        String repoPrefix = resolveRepoPrefix(cfg)
        if (!projectKey || !authToken) return out

        try {
            script.withCredentials([script.string(credentialsId: authToken, variable: 'SONAR_TOKEN')]) {
                String url = "${sonarUrl}/api/issues/search?componentKeys=${projectKey}&resolved=false&ps=30"
                Map resp = rest.request('GET', url, null, [type: 'bearer', tokenVar: 'SONAR_TOKEN'], 'SonarQube: search issues')
                def json = (resp.status >= 200 && resp.status < 300) ? rest.parseJson(resp.body as String) : null
                out.addAll(SecurityFindingsParser.sonarIssuesFromJson(json, ws, repoPrefix))
            }
        } catch (Exception ignored) {
        }
        return out
    }

    @NonCPS
    static List<Map> sonarIssuesFromJson(def json, String ws = '', String repoPrefix = '') {
        List<Map> out = []
        if (!(json?.issues instanceof List)) return out
        for (def iss : (json.issues as List)) {
            String comp = (iss.component ?: '') as String
            String file = comp.contains(':') ? comp.substring(comp.lastIndexOf(':') + 1) : comp
            def rawLine = iss.line ?: iss.textRange?.startLine ?: 0
            int line = rawLine.toString().isInteger() ? Integer.parseInt(rawLine.toString()) : 0
            if (file && line > 0) {
                out << [
                        file       : SecurityFindingsParser.normalizeRepoPath(file, ws, repoPrefix),
                        line       : line,
                        severity   : (iss.severity ?: 'MAJOR') as String,
                        rule       : (iss.rule ?: 'SonarRule') as String,
                        message    : (iss.message ?: 'Quality or security issue') as String,
                        remediation: "Resolve SonarQube rule ${iss.rule ?: ''} violation (${iss.type ?: 'CODE_SMELL'}).".toString()
                ]
            }
        }
        return out
    }

    List<Map> extractDastFindings(String projectName = state.currentProjectName, Map cfg = state.cfg) {
        List<Map> out = []
        String reportFile = resolveReportPath('appscan-dast-report', projectName, cfg)
        String targetUrl = (cfg?.dast?.targetUrl ?: '') as String
        if (!reportFile) {
            out << [
                    severity   : 'HIGH',
                    type       : 'DAST Security Finding',
                    endpoint   : targetUrl,
                    description: 'Dynamic scan discovered security findings on target application.',
                    remediation: 'Inspect HTTP response headers, configure CORS, CSRF tokens, and ensure secure cookies.'
            ]
            return out
        }

        try {
            String html = script.readFile(reportFile) ?: ''
            String[] parts = html.split(/(?i)Issue\s*ID\s*:/)
            for (int i = 1; i < parts.length; i++) {
                String text = parts[i].replaceAll(/(?s)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
                def sevM = text =~ /(?i)Severity\s*:?\s*(Critical|High|Medium|Low)/
                if (!sevM.find()) continue
                def typeM = text =~ /(?i)Issue\s*Type\s*:?\s*([^,.\n\r]+)/
                def urlM = text =~ /(?i)(https?:\/\/[^\s"'<>]+)/
                def remM = text =~ /(?i)Remediation\s*:?\s*(.+?)(?=(?:Issue\s*ID|Cause|Details|$))/

                out << [
                        severity   : sevM.group(1).toUpperCase(),
                        type       : typeM.find() ? typeM.group(1).trim() : 'Dynamic Security Finding',
                        endpoint   : urlM.find() ? urlM.group(1) : targetUrl,
                        description: 'AppScan DAST detected vulnerability during dynamic runtime analysis.',
                        remediation: remM.find() ? remM.group(1).trim() : 'Enforce input validation, output encoding, and security headers.'
                ]
            }
        } catch (Exception ignored) {
        }
        return out
    }

    String resolveRepoPrefix(Map cfg) {
        String sd = (cfg?.sourceDir ?: '').toString().trim().replaceAll('^\\./+', '').replaceAll('/+$', '')
        if (sd && sd != '.' && !sd.startsWith('/')) {
            if (script.fileExists("${sd}/.git/HEAD") || script.fileExists("${sd}/.git")) {
                return sd
            }
        }
        return ''
    }

    @NonCPS
    static String normalizeRepoPath(String path, String ws = '', String repoPrefix = '') {
        if (!path) return ''
        String p = path.replace('\\', '/').trim()
        String normWs = (ws ?: '').replace('\\', '/').trim().replaceAll('/+$', '')
        if (normWs && p.toLowerCase().startsWith((normWs + '/').toLowerCase())) {
            p = p.substring(normWs.length() + 1)
        }
        p = p.replaceFirst(/^(?:\.\/|\/)+/, '')
        for (String stagePrefix : ['appscan-source-staged/', 'sast_directory/']) {
            if (p.toLowerCase().startsWith(stagePrefix)) {
                p = p.substring(stagePrefix.length())
            } else {
                int idx = p.toLowerCase().indexOf('/' + stagePrefix)
                if (idx >= 0) p = p.substring(idx + stagePrefix.length() + 1)
            }
        }
        p = p.replaceFirst(/^(?:\.\/|\/)+/, '')
        String pref = (repoPrefix ?: '').replace('\\', '/').trim().replaceAll('^\\./+|/+$', '')
        if (pref && p.toLowerCase().startsWith((pref + '/').toLowerCase())) {
            p = p.substring(pref.length() + 1)
        }
        return p.replaceFirst(/^(?:\.\/|\/)+/, '')
    }
}
