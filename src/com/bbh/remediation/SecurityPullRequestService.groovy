package com.bbh.remediation

import com.bbh.core.PipelineState
import com.bbh.remediation.port.PullRequestPublisher
import com.bbh.remediation.port.SourceRepository
import com.bbh.utils.AppScanReportParser
import com.bbh.utils.BuildUtils
import com.bbh.utils.RestClient
import com.cloudbees.groovy.cps.NonCPS

class SecurityPullRequestService implements Serializable {

    private final def script
    private final PipelineState state
    private final PullRequestPublisher publisher
    private final SourceRepository repository
    private final RestClient rest

    SecurityPullRequestService(def script, PipelineState state, PullRequestPublisher publisher, SourceRepository repository) {
        this.script = script
        this.state = state
        this.publisher = publisher
        this.repository = repository
        this.rest = new RestClient(script)
    }

    void publishStaticSecurityPullRequest() {
        String origName = state.currentProjectName
        Map origCfg = state.cfg
        try {
            Map<String, Map> allProjects = (state.projectsAllCfg && !state.projectsAllCfg.isEmpty())
                    ? (state.projectsAllCfg as Map<String, Map>)
                    : [(origName ?: 'service'): (origCfg ?: [:])]
            for (def entry : allProjects.entrySet()) {
                String pName = entry.key as String
                Map pCfg = (entry.value ?: [:]) as Map
                state.currentProjectName = pName
                state.cfg = pCfg
                publishStaticForProject(pName, pCfg)
            }
        } finally {
            state.currentProjectName = origName
            state.cfg = origCfg
        }
    }

    private void publishStaticForProject(String projectName, Map cfg) {
        try {
            Map scmCfg = (cfg?.scm?.bitbucket ?: [:]) as Map
            if (!scmCfg.url && !scmCfg.cloneUrl) return

            Map pr = resolveProjectPullRequest(projectName, cfg)
            List<Map> gfChanges = (state.projectsGoldenFix?.get(projectName)?.changes ?: []) as List<Map>
            Map projScan = (state.projectsScanResults?.get(projectName) ?: [:]) as Map
            boolean hasSast = (projScan.get('sast') ?: state.policyStatus?.get('sast')) in ['WARN', 'FAIL']
            boolean hasSonar = ((projScan.get('sonar') ?: state.policyStatus?.get('sonar')) in ['WARN', 'FAIL']) || (state.policyStatus?.get('sca') in ['WARN', 'FAIL'])

            Map prCfg = (cfg?.pullRequestDecoration ?: cfg?.securityPullRequest ?: [:]) as Map
            boolean prExplicitlyEnabled = BuildUtils.booleanValue(prCfg.enabled, false) || BuildUtils.booleanValue(scmCfg.decoratePullRequests, false)

            if (!pr.url && !prExplicitlyEnabled) return
            if (!pr.url && !gfChanges && !hasSast && !hasSonar) return

            String targetBranch = (pr.targetBranch ?: scmCfg.targetBranch ?: repository.resolveCurrentBranch()) as String
            List<Map> sastFindings = extractSastFindings(projectName, cfg)
            List<Map> sonarIssues = extractSonarIssues(projectName, cfg)

            if (!pr.url && !gfChanges && sastFindings.isEmpty() && sonarIssues.isEmpty()) return

            if (!pr.url) {
                String branch = "security-static-${projectName}".toString()
                Map existingPr = publisher.findOpenPullRequest(scmCfg, branch, targetBranch)
                if (existingPr) {
                    pr = existingPr + [title: existingPr.title ?: "[Security] Static analysis findings - ${projectName}".toString(), branch: branch, targetBranch: targetBranch]
                } else {
                    String dir = repository.prepareWorkingCopy(branch)
                    try {
                        String reportPath = "${dir}/security/static-security-report.md"
                        ensureSecurityDir(dir)
                        script.writeFile file: reportPath, text: generateStaticReport(gfChanges, sastFindings, sonarIssues)
                        repository.commit(dir, ['security/static-security-report.md'], '[Security] Static analysis findings and recommendations', [name: 'DevSecOps Bot', email: 'devsecops@bbh.com'])
                        Map repoInfo = publisher.repositoryInfo(scmCfg)
                        repository.push(dir, branch, [
                                url          : scmCfg.cloneUrl ?: repoInfo.cloneUrl,
                                credentialsId: scmCfg.credentialsId,
                                authType     : scmCfg.authType,
                                cloud        : repoInfo.cloud
                        ])
                        String title = "[Security] Static analysis findings - ${projectName}".toString()
                        Map created = publisher.createPullRequest(scmCfg, [
                                title       : title,
                                description : 'Automated Pull Request containing static analysis findings from Nexus IQ, AppScan SAST, and SonarQube.',
                                sourceBranch: branch,
                                targetBranch: targetBranch
                        ])
                        pr = [id: created.id, url: created.url, title: title, branch: branch, targetBranch: targetBranch]
                    } finally {
                        repository.cleanup(dir)
                    }
                }
            }

            if (!pr.id && !pr.url) return
            if (!pr.id && pr.url) {
                def idM = (pr.url as String) =~ /\/pull-requests\/(\d+)/
                if (idM.find()) pr = [:] + pr + [id: idM.group(1)]
            }
            decoratePullRequestWithFindings(scmCfg, pr, sastFindings, sonarIssues)
            recordProjectPullRequestComments(projectName, pr, sastFindings)
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[SECURITY-PR] Static security pull request skipped: ${t.message}"
        }
    }

    private Map resolveProjectPullRequest(String projectName, Map cfg) {
        String repoKey = BuildUtils.normalizeRepositoryUrl(cfg?.scm)
        if (repoKey && state.goldenFixPullRequestsByRepo?.containsKey(repoKey)) {
            return (state.goldenFixPullRequestsByRepo[repoKey] ?: [:]) as Map
        }
        Map gf = (state.projectsGoldenFix?.get(projectName) ?: [:]) as Map
        if (gf.prUrl) {
            String prRepoKey = BuildUtils.normalizeRepositoryUrl(gf.prUrl as String)
            if (!repoKey || !prRepoKey || prRepoKey.startsWith(repoKey)) {
                return [id: gf.prId, url: gf.prUrl, title: gf.prTitle, branch: gf.branch, targetBranch: gf.targetBranch]
            }
        }
        if (!state.projectsAllCfg || state.projectsAllCfg.size() <= 1) {
            return (state.goldenFixPullRequest ?: [:]) as Map
        }
        return [:]
    }

    private void recordProjectPullRequestComments(String projectName, Map pr, List<Map> sastFindings) {
        if (!sastFindings) return
        if (state.projectsGoldenFix == null) state.projectsGoldenFix = [:]
        Map gf = [:] + ((state.projectsGoldenFix[projectName] ?: [:]) as Map)
        gf.sastComments = sastFindings
        if (!gf.prUrl && pr.url) {
            gf.prUrl = pr.url
            gf.prId = pr.id
            gf.prTitle = pr.title ?: "security-static-${projectName}".toString()
            gf.branch = pr.branch ?: "security-static-${projectName}".toString()
            gf.targetBranch = pr.targetBranch ?: 'main'
            if (!gf.status || gf.status in ['SKIPPED', 'NO_FIXES', 'NO_MANIFEST_CHANGES']) {
                gf.status = 'PR_CREATED'
            }
        }
        state.projectsGoldenFix[projectName] = gf
    }

    void publishDastPullRequest() {
        String origName = state.currentProjectName
        Map origCfg = state.cfg
        try {
            Map<String, Map> allProjects = (state.projectsAllCfg && !state.projectsAllCfg.isEmpty())
                    ? (state.projectsAllCfg as Map<String, Map>)
                    : [(origName ?: 'service'): (origCfg ?: [:])]
            for (def entry : allProjects.entrySet()) {
                String pName = entry.key as String
                Map pCfg = (entry.value ?: [:]) as Map
                state.currentProjectName = pName
                state.cfg = pCfg
                publishDastForProject(pName, pCfg)
            }
        } finally {
            state.currentProjectName = origName
            state.cfg = origCfg
        }
    }

    private void publishDastForProject(String projectName, Map cfg) {
        try {
            Map scmCfg = (cfg?.scm?.bitbucket ?: [:]) as Map
            if (!scmCfg.url && !scmCfg.cloneUrl) return

            Map prCfg = (cfg?.pullRequestDecoration ?: cfg?.securityPullRequest ?: cfg?.dast?.pullRequest ?: [:]) as Map
            boolean prExplicitlyEnabled = BuildUtils.booleanValue(prCfg.enabled, false) || BuildUtils.booleanValue(scmCfg.decoratePullRequests, false) || BuildUtils.booleanValue(cfg?.dast?.createPullRequest, false)
            if (!prExplicitlyEnabled) return

            Map projScan = (state.projectsScanResults?.get(projectName) ?: [:]) as Map
            boolean hasDast = (projScan.get('dast') ?: state.policyStatus?.get('dast')) in ['WARN', 'FAIL']
            if (!hasDast) return
            Map counts = ((state.projectsVulnCounts?.get(projectName) as Map)?.get('dast') ?: state.vulnCounts?.dast ?: [:]) as Map
            int totalVulns = ((counts.critical ?: 0) as int) + ((counts.high ?: 0) as int) + ((counts.medium ?: 0) as int) + ((counts.low ?: 0) as int)
            if (totalVulns <= 0) return

            String targetBranch = (scmCfg.targetBranch ?: repository.resolveCurrentBranch()) as String
            String branch = "security-dast-${projectName}".toString()
            List<Map> dastFindings = extractDastFindings(projectName, cfg)

            Map existingPr = publisher.findOpenPullRequest(scmCfg, branch, targetBranch)
            if (existingPr && existingPr.id) {
                publisher.addComment(scmCfg, existingPr, generateDastReport(dastFindings, counts))
                return
            }

            String dir = repository.prepareWorkingCopy(branch)
            try {
                String reportPath = "${dir}/security/dast-findings.md"
                ensureSecurityDir(dir)
                String md = generateDastReport(dastFindings, counts)
                script.writeFile file: reportPath, text: md
                repository.commit(dir, ['security/dast-findings.md'], '[Security] DAST dynamic security scan findings', [name: 'DevSecOps Bot', email: 'devsecops@bbh.com'])
                Map repoInfo = publisher.repositoryInfo(scmCfg)
                repository.push(dir, branch, [
                        url          : scmCfg.cloneUrl ?: repoInfo.cloneUrl,
                        credentialsId: scmCfg.credentialsId,
                        authType     : scmCfg.authType,
                        cloud        : repoInfo.cloud
                ])
                publisher.createPullRequest(scmCfg, [
                        title       : "[Security] DAST findings - ${projectName}".toString(),
                        description : md,
                        sourceBranch: branch,
                        targetBranch: targetBranch
                ])
            } finally {
                repository.cleanup(dir)
            }
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[SECURITY-PR] DAST security pull request skipped: ${t.message}"
        }
    }

    private void decoratePullRequestWithFindings(Map scmCfg, Map pr, List<Map> sastFindings, List<Map> sonarIssues) {
        for (int i = 0; i < sastFindings.size(); i++) {
            Map f = sastFindings[i]
            if (f.file && f.line) {
                String text = "**[AppScan SAST] ${f.severity}: ${f.type}**\n\n${f.description}\n\n**Suggested remediation:** ${f.remediation}".toString()
                try {
                    publisher.addLineComment(scmCfg, pr, f.file as String, (f.line as Number).intValue(), text)
                } catch (Exception e) {
                    script.echo "[SECURITY-PR] Could not add SAST line comment: ${e.message}"
                }
            }
        }

        for (int i = 0; i < sonarIssues.size(); i++) {
            Map s = sonarIssues[i]
            if (s.file && s.line) {
                String text = "**[SonarQube] ${s.severity}: ${s.rule}**\n\n${s.message}\n\n**Suggested remediation:** ${s.remediation}".toString()
                try {
                    publisher.addLineComment(scmCfg, pr, s.file as String, (s.line as Number).intValue(), text)
                } catch (Exception e) {
                    script.echo "[SECURITY-PR] Could not add SonarQube line comment: ${e.message}"
                }
            }
        }
    }

    private String resolveReportPath(String prefix, String projectName, Map cfg) {
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

    private List<Map> extractSastFindings(String projectName = state.currentProjectName, Map cfg = state.cfg) {
        List<Map> out = []
        String ws = (script.env.WORKSPACE ?: '').trim()
        String repoPrefix = resolveRepoPrefix(cfg)
        String reportFile = resolveReportPath('appscan-report', projectName, cfg)
        if (!reportFile) return out

        try {
            String html = AppScanReportParser.maybeUnescape(script.readFile(reportFile) ?: '')
            String[] parts = html.split(/(?i)(?:Issue\s*ID|Finding\s*ID)\s*:/)
            if (parts.length <= 1) {
                parts = html.split(/(?is)(?=<div\b[^>]*class\s*=\s*["']name["'][^>]*>\s*Severity\s*:)/)
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
                String file = hasFile ? normalizeRepoPath(fileM.group(1), ws, repoPrefix) : null
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
        } catch (Exception ignored) {
        }
        return out
    }

    private List<Map> extractSonarIssues(String projectName = state.currentProjectName, Map cfg = state.cfg) {
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
                if (json?.issues instanceof List) {
                    json.issues.each { iss ->
                        String comp = (iss.component ?: '') as String
                        String file = comp.contains(':') ? comp.substring(comp.lastIndexOf(':') + 1) : comp
                        def rawLine = iss.line ?: iss.textRange?.startLine ?: 0
                        int line = rawLine.toString().isInteger() ? Integer.parseInt(rawLine.toString()) : 0
                        if (file && line > 0) {
                            out << [
                                    file       : normalizeRepoPath(file, ws, repoPrefix),
                                    line       : line,
                                    severity   : (iss.severity ?: 'MAJOR') as String,
                                    rule       : (iss.rule ?: 'SonarRule') as String,
                                    message    : (iss.message ?: 'Quality or security issue') as String,
                                    remediation: "Resolve SonarQube rule ${iss.rule ?: ''} violation (${iss.type ?: 'CODE_SMELL'}).".toString()
                            ]
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out
    }

    private List<Map> extractDastFindings(String projectName = state.currentProjectName, Map cfg = state.cfg) {
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

    private void ensureSecurityDir(String dir) {
        if (script.isUnix()) {
            script.sh "mkdir -p '${BuildUtils.escapeForSingleQuotes(dir)}/security'"
        } else {
            script.powershell "New-Item -ItemType Directory -Force -Path '${BuildUtils.psSingleQuote(dir)}\\security' | Out-Null"
        }
    }

    private String resolveRepoPrefix(Map cfg) {
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

    private static String generateStaticReport(List<Map> gfChanges, List<Map> sastFindings, List<Map> sonarIssues) {
        StringBuilder sb = new StringBuilder("# DevSecOps Static Analysis & Quality Gate Report\n\n")
        sb.append("This Pull Request consolidates static security and code quality remediations from **Nexus IQ GoldenFix**, **AppScan SAST**, and **SonarQube**.\n\n")
        sb.append("### 1. Nexus IQ GoldenFix Upgrades\n")
        if (gfChanges) {
            gfChanges.each { c -> sb.append("- `${c.coordinate ?: c.component}`: upgraded `${c.from}` -> `${c.to}` in `${c.file}`\n") }
        } else {
            sb.append("- No direct dependency version upgrades applied.\n")
        }
        sb.append("\n### 2. HCL AppScan SAST Findings\n")
        if (sastFindings) {
            sastFindings.each { f -> sb.append("- **[${f.severity}]** `${f.file}:${f.line}` - ${f.type}: ${f.remediation}\n") }
        } else {
            sb.append("- 0 static security vulnerabilities found.\n")
        }
        sb.append("\n### 3. SonarQube Code Quality Issues\n")
        if (sonarIssues) {
            sonarIssues.each { s -> sb.append("- **[${s.severity}]** `${s.file}:${s.line}` - ${s.message}\n") }
        } else {
            sb.append("- 0 blocking quality gate issues found.\n")
        }
        return sb.toString()
    }

    private static String generateDastReport(List<Map> dastFindings, Map counts) {
        StringBuilder sb = new StringBuilder("# HCL AppScan DAST Dynamic Security Report\n\n")
        sb.append("The enhanced pipeline executed Dynamic Application Security Tests (DAST) on the running service.\n\n")
        sb.append("### Summary of Vulnerabilities\n")
        sb.append("- **Critical:** ").append(counts.critical ?: 0).append("\n")
        sb.append("- **High:** ").append(counts.high ?: 0).append("\n")
        sb.append("- **Medium:** ").append(counts.medium ?: 0).append("\n")
        sb.append("- **Low:** ").append(counts.low ?: 0).append("\n\n")
        sb.append("### Affected Endpoints & Remediation Guidance\n")
        dastFindings.each { d ->
            sb.append("- **[${d.severity}]** `${d.endpoint}` - ${d.type}\n")
            sb.append("  - *Remediation:* ${d.remediation}\n")
        }
        return sb.toString()
    }
}
