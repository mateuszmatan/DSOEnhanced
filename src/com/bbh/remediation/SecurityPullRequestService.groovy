package com.bbh.remediation

import com.bbh.core.PipelineState
import com.bbh.remediation.port.PullRequestPublisher
import com.bbh.remediation.port.SourceRepository
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
        try {
            Map scmCfg = (state.cfg?.scm?.bitbucket ?: [:]) as Map
            if (!scmCfg.url && !scmCfg.cloneUrl) {
                return
            }

            Map pr = (state.goldenFixPullRequest ?: [:]) as Map
            List<Map> gfChanges = (state.projectsGoldenFix?.get(state.currentProjectName)?.changes ?: []) as List<Map>
            boolean hasSast = (state.policyStatus?.get('sast') in ['WARN', 'FAIL'])
            boolean hasSonar = (state.policyStatus?.get('sonar') in ['WARN', 'FAIL']) || (state.policyStatus?.get('sca') in ['WARN', 'FAIL'])

            Map prCfg = (state.cfg?.pullRequestDecoration ?: state.cfg?.securityPullRequest ?: [:]) as Map
            boolean prExplicitlyEnabled = BuildUtils.booleanValue(prCfg.enabled, false) || BuildUtils.booleanValue(scmCfg.decoratePullRequests, false)

            if (!pr.url && !prExplicitlyEnabled) {
                return
            }

            if (!pr.url && !gfChanges && !hasSast && !hasSonar) {
                return
            }

            String targetBranch = (pr.targetBranch ?: scmCfg.targetBranch ?: repository.resolveCurrentBranch()) as String

            List<Map> sastFindings = extractSastFindings()
            List<Map> sonarIssues = extractSonarIssues()

            if (!pr.url && !gfChanges && sastFindings.isEmpty() && sonarIssues.isEmpty()) {
                return
            }

            if (!pr.url) {
                Map existingPr = publisher.findOpenPullRequest(scmCfg, "security-static-${state.currentProjectName}".toString(), targetBranch)
                if (existingPr) {
                    pr = existingPr
                } else {
                    String branch = "security-static-${state.currentProjectName}"
                    String dir = repository.prepareWorkingCopy(branch)
                    try {
                        String reportPath = "${dir}/security/static-security-report.md"
                        script.sh "mkdir -p '${dir}/security'"
                        script.writeFile file: reportPath, text: generateStaticReport(gfChanges, sastFindings, sonarIssues)
                        repository.commit(dir, ['security/static-security-report.md'], "[Security] Static analysis findings and recommendations", [name: 'DevSecOps Bot', email: 'devsecops@bbh.com'])
                        Map repoInfo = publisher.repositoryInfo(scmCfg)
                        repository.push(dir, branch, [
                                url: scmCfg.cloneUrl ?: repoInfo.cloneUrl,
                                credentialsId: scmCfg.credentialsId,
                                authType: scmCfg.authType,
                                cloud: repoInfo.cloud
                        ])
                        Map created = publisher.createPullRequest(scmCfg, [
                                title: "[Security] Static analysis findings - ${state.currentProjectName}".toString(),
                                description: "Automated Pull Request containing static analysis findings from Nexus IQ, AppScan SAST, and SonarQube.",
                                sourceBranch: branch,
                                targetBranch: targetBranch
                        ])
                        pr = [id: created.id, url: created.url]
                    } finally {
                        repository.cleanup(dir)
                    }
                }
            }

            if (!pr.id && !pr.url) return

            decoratePullRequestWithFindings(scmCfg, pr, sastFindings, sonarIssues)
            notifyPortal(scmCfg, pr, sastFindings, sonarIssues, 'STATIC')
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[SECURITY-PR] Static security pull request skipped: ${t.message}"
        }
    }

    void publishDastPullRequest() {
        try {
            Map scmCfg = (state.cfg?.scm?.bitbucket ?: [:]) as Map
            if (!scmCfg.url && !scmCfg.cloneUrl) {
                return
            }

            boolean hasDast = (state.policyStatus?.get('dast') in ['WARN', 'FAIL'])
            if (!hasDast) {
                return
            }
            Map counts = (state.vulnCounts?.dast ?: [:]) as Map

            String targetBranch = (scmCfg.targetBranch ?: repository.resolveCurrentBranch()) as String
            String branch = "security-dast-${state.currentProjectName}".toString()
            Map pr = publisher.findOpenPullRequest(scmCfg, branch, targetBranch)

            List<Map> dastFindings = extractDastFindings()
            String report = generateDastReport(dastFindings, counts)

            if (!pr) {
                String dir = repository.prepareWorkingCopy(branch)
                try {
                    script.sh "mkdir -p '${dir}/security'"
                    script.writeFile file: "${dir}/security/dast-findings.md", text: report
                    repository.commit(dir, ['security/dast-findings.md'], "[DAST Security] Dynamic Application Security Testing Findings", [name: 'DevSecOps Bot', email: 'devsecops@bbh.com'])
                    Map repoInfo = publisher.repositoryInfo(scmCfg)
                    repository.push(dir, branch, [
                            url: scmCfg.cloneUrl ?: repoInfo.cloneUrl,
                            credentialsId: scmCfg.credentialsId,
                            authType: scmCfg.authType,
                            cloud: repoInfo.cloud
                    ])
                    Map created = publisher.createPullRequest(scmCfg, [
                            title: "[DAST Security] Dynamic Scan Results - ${state.currentProjectName}".toString(),
                            description: report,
                            sourceBranch: branch,
                            targetBranch: targetBranch
                    ])
                    pr = [id: created.id, url: created.url]
                } finally {
                    repository.cleanup(dir)
                }
            } else {
                publisher.addComment(scmCfg, pr, report)
            }

            notifyPortal(scmCfg, pr, dastFindings, [], 'DAST')
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[DAST-PR] DAST pull request skipped: ${t.message}"
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

    private List<Map> extractSastFindings() {
        List<Map> out = []
        String scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        String reportFile = "${script.env.WORKSPACE}/appscan-report-${scanName}.html"
        if (!script.fileExists(reportFile)) return out

        try {
            String html = script.readFile(reportFile) ?: ''
            String[] parts = html.split(/(?i)Issue\s*ID\s*:/)
            for (int i = 1; i < parts.length; i++) {
                String text = parts[i].replaceAll(/(?s)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
                def sevM = text =~ /(?i)Severity\s*:?\s*(Critical|High|Medium|Low)/
                if (!sevM.find()) continue
                def statM = text =~ /(?i)Status\s*:?\s*(Open|New|In\s*Progress|Passed|Noise|Fixed)/
                if (statM.find() && (statM.group(1).equalsIgnoreCase('Noise') || (!statM.group(1).contains('Open') && !statM.group(1).contains('New')))) continue

                def typeM = text =~ /(?i)Issue\s*Type\s*:?\s*([^,.\n\r]+)/
                def fileM = text =~ /(?i)File\s*:?\s*([a-zA-Z0-9_\-\/\.\\]+\.[a-zA-Z0-9]+)/
                def lineM = text =~ /(?i)Line\s*:?\s*(\d+)/
                def remM = text =~ /(?i)Remediation\s*:?\s*(.+?)(?=(?:Issue\s*ID|Cause|Details|\$))/

                String file = fileM.find() ? normalizePath(fileM.group(1)) : null
                Integer line = lineM.find() ? (lineM.group(1) as int) : null
                if (file && line) {
                    out << [
                            file: file,
                            line: line,
                            severity: sevM.group(1).toUpperCase(),
                            type: typeM.find() ? typeM.group(1).trim() : 'Security Vulnerability',
                            description: "Vulnerability detected by HCL AppScan SAST in ${file}:${line}".toString(),
                            remediation: remM.find() ? remM.group(1).trim() : 'Review source code and sanitize input or apply defensive controls.'
                    ]
                }
            }
        } catch (Exception ignored) {
        }
        return out
    }

    private List<Map> extractSonarIssues() {
        List<Map> out = []
        def sonarCfg = state.cfg?.tools?.sonar ?: [:]
        String projectKey = (sonarCfg.projectKey ?: '') as String
        String sonarUrl = (sonarCfg.serverUrl ?: 'https://tools.bbh.com/sonar').toString().replaceAll('/+$', '')
        String authToken = (sonarCfg.authToken ?: '') as String
        if (!projectKey || !authToken) return out

        try {
            script.withCredentials([script.string(credentialsId: authToken, variable: 'SONAR_TOKEN')]) {
                String url = "${sonarUrl}/api/issues/search?componentKeys=${projectKey}&resolved=false&ps=30"
                String resp = script.sh(returnStdout: true, script: "curl -s -k -H 'Authorization: Bearer ${script.env.SONAR_TOKEN}' '${url}' || echo '{}'").trim()
                def json = script.readJSON(text: resp)
                if (json?.issues instanceof List) {
                    json.issues.each { iss ->
                        String comp = (iss.component ?: '') as String
                        String file = comp.contains(':') ? comp.substring(comp.indexOf(':') + 1) : comp
                        int line = (iss.line ?: 0) as int
                        if (file && line > 0) {
                            out << [
                                    file: normalizePath(file),
                                    line: line,
                                    severity: (iss.severity ?: 'MAJOR') as String,
                                    rule: (iss.rule ?: 'SonarRule') as String,
                                    message: (iss.message ?: 'Quality or security issue') as String,
                                    remediation: "Comply with SonarQube rule ${iss.rule}".toString()
                            ]
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out
    }

    private List<Map> extractDastFindings() {
        List<Map> out = []
        String scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        String reportFile = "${script.env.WORKSPACE}/appscan-dast-report-${scanName}.html"
        String targetUrl = (state.cfg?.dast?.targetUrl ?: '') as String
        if (!script.fileExists(reportFile)) {
            out << [
                    severity: 'HIGH',
                    type: 'DAST Security Finding',
                    endpoint: targetUrl,
                    description: "Dynamic scan discovered security findings on target application.",
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
                def remM = text =~ /(?i)Remediation\s*:?\s*(.+?)(?=(?:Issue\s*ID|Cause|Details|\$))/

                out << [
                        severity: sevM.group(1).toUpperCase(),
                        type: typeM.find() ? typeM.group(1).trim() : 'Dynamic Security Finding',
                        endpoint: urlM.find() ? urlM.group(1) : targetUrl,
                        description: "AppScan DAST detected vulnerability during dynamic runtime analysis.",
                        remediation: remM.find() ? remM.group(1).trim() : 'Enforce input validation, output encoding, and security headers.'
                ]
            }
        } catch (Exception ignored) {
        }
        return out
    }

    private void notifyPortal(Map scmCfg, Map pr, List<Map> findings1, List<Map> findings2, String kind) {
        String portalUrl = (state.cfg?.portal?.url ?: state.cfgDefaults?.portal?.url ?: '') as String
        if (!portalUrl || !pr.url) return

        List allFindings = []
        findings1.each { f ->
            allFindings << [
                    tool: kind == 'STATIC' ? 'SAST' : 'DAST',
                    severity: f.severity,
                    file: f.file,
                    line: f.line,
                    title: f.type,
                    description: f.description,
                    remediation: f.remediation
            ]
        }
        findings2.each { s ->
            allFindings << [
                    tool: 'SONAR',
                    severity: s.severity,
                    file: s.file,
                    line: s.line,
                    title: s.rule,
                    description: s.message,
                    remediation: s.remediation
            ]
        }

        Map payload = [
                repositoryUrl: scmCfg.url ?: scmCfg.cloneUrl,
                pullRequestId: pr.id?.toString() ?: '1',
                credentialsId: scmCfg.credentialsId,
                sourceBranch: pr.sourceBranch,
                targetBranch: pr.targetBranch,
                findings: allFindings,
                summary: "Decorated ${kind} pull request with ${allFindings.size()} finding(s).".toString()
        ]

        try {
            String jsonPayload = script.writeJSON(returnText: true, json: payload)
            script.sh(script: "curl -s -k -X POST -H 'Content-Type: application/json' -d '${jsonPayload.replace("'", "'\\''")}' '${portalUrl}/api/dso/bitbucket/pull-requests/decorate' || true")
        } catch (Exception e) {
            script.echo "[SECURITY-PR] Portal decoration notification skipped: ${e.message}"
        }
    }

    private static String normalizePath(String path) {
        if (!path) return ''
        String p = path.replace('\\', '/').trim()
        if (p.startsWith('./')) p = p.substring(2)
        return p
    }

    private static String generateStaticReport(List<Map> gfChanges, List<Map> sastFindings, List<Map> sonarIssues) {
        StringBuilder sb = new StringBuilder("# DevSecOps Static Analysis & Quality Gate Report\n\n")
        sb.append("This Pull Request consolidates static security and code quality remediations from **Nexus IQ GoldenFix**, **AppScan SAST**, and **SonarQube**.\n\n")
        sb.append("### 1. Nexus IQ GoldenFix Upgrades\n")
        if (gfChanges) {
            gfChanges.each { c -> sb.append("- `${c.coordinate}`: upgraded `${c.from}` -> `${c.to}` in `${c.file}`\n") }
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
