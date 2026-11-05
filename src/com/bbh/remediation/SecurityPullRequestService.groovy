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

    Map settings() {
        Map scmCfg = (state.cfg?.scm?.bitbucket ?: [:]) as Map
        Map prCfg = (state.cfg?.pullRequestDecoration ?: state.cfg?.securityPullRequest ?: [:]) as Map
        Map pr = (state.goldenFixPullRequest ?: [:]) as Map
        boolean explicit = prCfg.containsKey('enabled') ? BuildUtils.booleanValue(prCfg.enabled, false)
                : BuildUtils.booleanValue(scmCfg.decoratePullRequests, false)
        boolean enabled = (pr.url ? true : explicit) && (scmCfg.url || scmCfg.cloneUrl) ? true : false
        return [scm             : scmCfg,
                enabled         : enabled && (scmCfg.url || scmCfg.cloneUrl) ? true : false,
                findingsPrs     : BuildUtils.booleanValue(prCfg.createFindingsPullRequest, true),
                maxComments     : ((prCfg.maxComments ?: 25) as String).toInteger(),
                sastMinSeverity : ((prCfg.sastMinSeverity ?: 'MEDIUM') as String).toUpperCase(),
                sonarMinSeverity: ((prCfg.sonarMinSeverity ?: 'MAJOR') as String).toUpperCase()]
    }

    void publishStaticSecurityPullRequest() {
        try {
            Map s = settings()
            if (!s.enabled) return
            Map scmCfg = s.scm as Map
            String project = state.currentProjectName as String
            List<Map> gfChanges = (state.projectsGoldenFix?.get(project)?.changes ?: []) as List<Map>
            String gfUrl = (state.projectsGoldenFix?.get(project)?.prUrl ?: '') as String
            List<Map> sastFindings = atLeast(extractSastFindings(), s.sastMinSeverity as String, sastOrder())
            List<Map> sonarIssues = atLeast(extractSonarIssues(), s.sonarMinSeverity as String, sonarOrder())
            String summary = staticSummary(project, gfChanges, gfUrl, sastFindings, sonarIssues)

            Map developerPr = developerPullRequest(scmCfg)
            if (developerPr) {
                decorate(scmCfg, developerPr, sastFindings, sonarIssues, s.maxComments as int)
                commentOnce(scmCfg, developerPr, summary)
                script.echo "[SECURITY-PR] ${project}: pull request ${developerPr.url ?: developerPr.id} of the analysed branch decorated with ${sastFindings.size()} SAST and ${sonarIssues.size()} SonarQube finding(s)"
            }

            Map staticPr = (state.goldenFixPullRequest?.url ? state.goldenFixPullRequest : [:]) as Map
            if (!staticPr && !sastFindings && !sonarIssues) return
            if (!staticPr && s.findingsPrs) {
                staticPr = findingsPullRequest(scmCfg, "security-static-${project}".toString(),
                        "[Security] Static analysis findings - ${project}".toString(), 'security/static-security-report.md',
                        staticReport(gfChanges, sastFindings, sonarIssues), 'Static analysis findings of Nexus IQ, AppScan SAST and SonarQube')
            }
            if (staticPr) {
                commentOnce(scmCfg, staticPr, summary)
                script.echo "[SECURITY-PR] ${project}: static security pull request ${staticPr.url}"
            }
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[SECURITY-PR] Static security pull request skipped: ${t.message ?: t.getClass().getSimpleName()}"
        }
    }

    void publishDastPullRequest() {
        try {
            Map s = settings()
            if (!s.enabled || !s.findingsPrs) return
            if (!(state.policyStatus?.get('dast') in ['WARN', 'FAIL'])) return
            Map scmCfg = s.scm as Map
            String project = state.currentProjectName as String
            Map counts = (state.vulnCounts?.dast ?: [:]) as Map
            String report = dastReport(extractDastFindings(), counts, (state.cfg?.dast?.targetUrl ?: '') as String)
            Map pr = findingsPullRequest(scmCfg, "security-dast-${project}".toString(),
                    "[Security] DAST results - ${project}".toString(), 'security/dast-findings.md', report, 'Dynamic scan results of AppScan DAST')
            if (pr) {
                commentOnce(scmCfg, pr, report)
                script.echo "[DAST-PR] ${project}: DAST pull request ${pr.url}"
            }
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[DAST-PR] DAST pull request skipped: ${t.message ?: t.getClass().getSimpleName()}"
        }
    }

    private Map developerPullRequest(Map scmCfg) {
        String changeId = (script.env.CHANGE_ID ?: '') as String
        if (changeId) return [id: changeId, url: (script.env.CHANGE_URL ?: '') as String]
        String branch = BuildUtils.normalizeBranch((script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String)
        if (!branch && script.isUnix()) {
            branch = (script.sh(returnStdout: true, script: "git rev-parse --abbrev-ref HEAD 2>/dev/null || true") ?: '').trim()
        }
        if (!branch || branch == 'HEAD' || branch.startsWith('GoldenFix-') || branch.startsWith('security-')) return null
        return publisher.findOpenPullRequest(scmCfg, branch, '')
    }

    private Map findingsPullRequest(Map scmCfg, String branch, String title, String file, String content, String description) {
        String targetBranch = (scmCfg.targetBranch ?: repository.resolveCurrentBranch()) as String
        if (!targetBranch) return null
        Map existing = publisher.findOpenPullRequest(scmCfg, branch, targetBranch)
        if (existing) return existing
        Map gf = (state.cfg?.goldenFix ?: [:]) as Map
        String dir = repository.prepareWorkingCopy(branch)
        try {
            String folder = file.contains('/') ? file.substring(0, file.lastIndexOf('/')) : ''
            if (folder) script.sh "mkdir -p '${BuildUtils.escapeForSingleQuotes(dir + '/' + folder)}'"
            repository.writeText(dir, file, content)
            String commitId = repository.commit(dir, [file], title, [name: gf.commitAuthorName ?: 'DevSecOps GoldenFix', email: gf.commitAuthorEmail ?: 'devsecops-goldenfix@noreply.local'])
            if (!commitId) return null
            Map repoInfo = publisher.repositoryInfo(scmCfg)
            repository.push(dir, branch, [url: scmCfg.cloneUrl ?: repoInfo.cloneUrl, credentialsId: scmCfg.credentialsId, authType: scmCfg.authType, cloud: repoInfo.cloud])
            Map created = publisher.createPullRequest(scmCfg, [title: title, description: description, sourceBranch: branch, targetBranch: targetBranch])
            return [id: created.id, url: created.url]
        } finally {
            repository.cleanup(dir)
        }
    }

    private void decorate(Map scmCfg, Map pr, List<Map> sastFindings, List<Map> sonarIssues, int maxComments) {
        List<String> existing = publisher.existingComments(scmCfg, pr)
        List<Map> all = []
        for (Map f : sastFindings) all << [file: f.file, line: f.line, text: "**[AppScan SAST] ${f.severity}: ${f.type}**\n\n${f.description}\n\n**Suggested remediation:** ${f.remediation}".toString()]
        for (Map f : sonarIssues) all << [file: f.file, line: f.line, text: "**[SonarQube] ${f.severity}: ${f.rule}**\n\n${f.message}\n\n**Suggested remediation:** ${f.remediation}".toString()]
        int posted = 0
        for (Map c : all) {
            if (posted >= maxComments) {
                script.echo "[SECURITY-PR] ${all.size() - posted} more finding(s) are listed in the summary comment only (pullRequestDecoration.maxComments: ${maxComments})"
                break
            }
            if (alreadyPosted(existing, c.text as String)) continue
            try {
                publisher.addLineComment(scmCfg, pr, c.file as String, (c.line as Number).intValue(), c.text as String)
                posted++
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                script.echo "[SECURITY-PR] Line comment on ${c.file}:${c.line} not added: ${e.message}"
            }
        }
    }

    private void commentOnce(Map scmCfg, Map pr, String text) {
        if (!pr?.id || !text) return
        if (alreadyPosted(publisher.existingComments(scmCfg, pr), text)) return
        try {
            publisher.addComment(scmCfg, pr, text)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[SECURITY-PR] Comment on pull request ${pr.url ?: pr.id} not added: ${e.message}"
        }
    }

    @NonCPS
    static boolean alreadyPosted(List<String> existing, String text) {
        String wanted = (text ?: '').trim()
        for (String e : (existing ?: [])) {
            if ((e ?: '').trim() == wanted || (e ?: '').contains(wanted)) return true
        }
        return false
    }

    @NonCPS
    static List<String> sastOrder() {
        return ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
    }

    @NonCPS
    static List<String> sonarOrder() {
        return ['INFO', 'MINOR', 'MAJOR', 'CRITICAL', 'BLOCKER']
    }

    @NonCPS
    static List<Map> atLeast(List<Map> findings, String minimum, List<String> order) {
        int min = order.indexOf(minimum)
        if (min < 0) min = 0
        List<Map> kept = []
        for (Map f : findings) {
            if (order.indexOf(((f.severity ?: '') as String).toUpperCase()) >= min) kept << f
        }
        return kept
    }

    private List<Map> extractSastFindings() {
        String scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        String jsonFile = "${script.env.WORKSPACE}/appscan-findings-${scanName}.json"
        if (script.fileExists(jsonFile)) {
            try {
                def parsed = script.readJSON(file: jsonFile)
                return findingsFromJson(parsed)
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            }
        }
        String reportFile = "${script.env.WORKSPACE}/appscan-report-${scanName}.html"
        if (!script.fileExists(reportFile)) return []
        try {
            return sastFindingsFromHtml((script.readFile(reportFile) ?: '') as String)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return []
        }
    }

    @NonCPS
    static List<Map> findingsFromJson(def parsed) {
        List<Map> out = []
        if (!(parsed instanceof List)) return out
        for (def item : (parsed as List)) {
            if (!(item instanceof Map)) continue
            Map f = item as Map
            if (!f.file || !f.line) continue
            out << [file: normalizePath(f.file as String), line: (f.line as String).toInteger(), severity: ((f.severity ?: 'MEDIUM') as String).toUpperCase(),
                    type: (f.type ?: 'Security vulnerability') as String, description: (f.description ?: "AppScan SAST finding in ${f.file}:${f.line}") as String,
                    remediation: (f.remediation ?: 'Validate the input and apply the AppScan fix recommendation.') as String]
        }
        return out
    }

    @NonCPS
    static List<Map> sastFindingsFromHtml(String html) {
        List<Map> out = []
        String[] parts = html.split(/(?i)Issue\s*ID\s*:/)
        for (int i = 1; i < parts.length; i++) {
            String text = parts[i].replaceAll(/(?s)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
            def sev = text =~ /(?i)Severity\s*:?\s*(Critical|High|Medium|Low)/
            if (!sev.find()) continue
            def status = text =~ /(?i)Status\s*:?\s*(Open|New|InProgress|In\s*Progress|Reopened|Noise|Fixed|Passed)/
            if (status.find() && !(status.group(1).replaceAll(/\s+/, '').toLowerCase() in ['open', 'new', 'inprogress', 'reopened'])) continue
            def file = text =~ /(?i)(?:Source\s*)?File\s*:?\s*([A-Za-z0-9_\-\/\.\\]+\.[A-Za-z0-9]+)/
            def line = text =~ /(?i)Line\s*:?\s*(\d+)/
            if (!file.find() || !line.find()) continue
            def type = text =~ /(?i)Issue\s*Type\s*:?\s*([^,.]+)/
            def fix = text =~ /(?i)(?:Fix\s*Recommendation|Remediation)\s*:?\s*(.{1,300}?)(?:\s+(?:Issue\s*ID|Cause|Details)\b|$)/
            String path = normalizePath(file.group(1))
            out << [file: path, line: line.group(1).toInteger(), severity: sev.group(1).toUpperCase(), type: type.find() ? type.group(1).trim() : 'Security vulnerability',
                    description: "AppScan SAST finding in ${path}:${line.group(1)}".toString(),
                    remediation: fix.find() ? fix.group(1).trim() : 'Validate the input and apply the AppScan fix recommendation.']
        }
        return out
    }

    private List<Map> extractSonarIssues() {
        Map sonarCfg = (state.cfg?.tools?.sonar ?: [:]) as Map
        String projectKey = (sonarCfg.projectKey ?: '') as String
        String credentialsId = (sonarCfg.authToken ?: '') as String
        if (!projectKey || !credentialsId) return []
        String sonarUrl = ((sonarCfg.serverUrl ?: 'https://tools.bbh.com/sonar') as String).replaceAll('/+$', '')
        String branch = BuildUtils.normalizeBranch((sonarCfg.branch ?: script.env.CHANGE_BRANCH ?: script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String)
        String url = "${sonarUrl}/api/issues/search?componentKeys=${RestClient.urlEncode(projectKey)}&resolved=false&ps=100${branch ? '&branch=' + RestClient.urlEncode(branch) : ''}".toString()
        try {
            def json = null
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'SONAR_PR_TOKEN')]) {
                json = rest.getJson(url, [type: 'bearer', tokenVar: 'SONAR_PR_TOKEN'], 'SonarQube: open issues of the analysed branch')
            }
            return sonarIssuesFromJson(json, projectKey)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[SECURITY-PR] SonarQube issues not read: ${e.message}"
            return []
        }
    }

    @NonCPS
    static List<Map> sonarIssuesFromJson(def json, String projectKey = '') {
        List<Map> out = []
        def issues = (json instanceof Map) ? (json as Map).issues : null
        if (!(issues instanceof List)) return out
        for (def i : (issues as List)) {
            if (!(i instanceof Map)) continue
            Map issue = i as Map
            String component = (issue.component ?: '') as String
            String file = projectKey && component.startsWith(projectKey + ':') ? component.substring(projectKey.length() + 1)
                    : (component.contains(':') ? component.substring(component.lastIndexOf(':') + 1) : component)
            int line = issue.line ? (issue.line as String).toInteger() : 0
            if (!file || line <= 0) continue
            out << [file: normalizePath(file), line: line, severity: ((issue.severity ?: 'MAJOR') as String).toUpperCase(), rule: (issue.rule ?: 'rule') as String,
                    message: (issue.message ?: 'SonarQube issue') as String, remediation: "Follow SonarQube rule ${issue.rule}".toString()]
        }
        return out
    }

    private List<Map> extractDastFindings() {
        String scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        String reportFile = "${script.env.WORKSPACE}/appscan-dast-report-${scanName}.html"
        if (!script.fileExists(reportFile)) return []
        try {
            return dastFindingsFromHtml((script.readFile(reportFile) ?: '') as String, (state.cfg?.dast?.targetUrl ?: '') as String)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return []
        }
    }

    @NonCPS
    static List<Map> dastFindingsFromHtml(String html, String targetUrl) {
        List<Map> out = []
        String[] parts = html.split(/(?i)Issue\s*ID\s*:/)
        for (int i = 1; i < parts.length; i++) {
            String text = parts[i].replaceAll(/(?s)<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
            def sev = text =~ /(?i)Severity\s*:?\s*(Critical|High|Medium|Low)/
            if (!sev.find()) continue
            def type = text =~ /(?i)Issue\s*Type\s*:?\s*([^,.]+)/
            def url = text =~ /(?i)(https?:\/\/[^\s"'<>]+)/
            def fix = text =~ /(?i)(?:Fix\s*Recommendation|Remediation)\s*:?\s*(.{1,300}?)(?:\s+(?:Issue\s*ID|Cause|Details)\b|$)/
            out << [severity: sev.group(1).toUpperCase(), type: type.find() ? type.group(1).trim() : 'Dynamic security finding',
                    endpoint: url.find() ? url.group(1) : targetUrl, remediation: fix.find() ? fix.group(1).trim() : 'Apply the AppScan fix recommendation.']
        }
        return out
    }

    @NonCPS
    static String normalizePath(String path) {
        if (!path) return ''
        String p = path.replace('\\', '/').trim()
        while (p.startsWith('./')) p = p.substring(2)
        return p
    }

    @NonCPS
    static String staticSummary(String project, List<Map> gfChanges, String gfUrl, List<Map> sastFindings, List<Map> sonarIssues) {
        StringBuilder sb = new StringBuilder("**DevSecOps static security results - ${project}**\n\n")
        sb.append("- Nexus IQ GoldenFix: ")
        sb.append(gfChanges ? "${gfChanges.size()} dependency upgrade(s)${gfUrl ? ' in ' + gfUrl : ''}" : 'no dependency upgrade').append('\n')
        sb.append("- AppScan SAST: ${sastFindings.size()} finding(s) with a file and line\n")
        sb.append("- SonarQube: ${sonarIssues.size()} open issue(s) on the analysed branch\n")
        for (Map c : gfChanges) sb.append("  - `${c.component ?: c.coordinate}` ${c.from} -> ${c.to} in `${c.file}`\n")
        for (Map f : sastFindings) sb.append("  - [SAST ${f.severity}] `${f.file}:${f.line}` ${f.type}\n")
        for (Map s : sonarIssues) sb.append("  - [SonarQube ${s.severity}] `${s.file}:${s.line}` ${s.message}\n")
        return sb.toString()
    }

    @NonCPS
    static String staticReport(List<Map> gfChanges, List<Map> sastFindings, List<Map> sonarIssues) {
        StringBuilder sb = new StringBuilder("# Static security findings\n\n")
        sb.append("## Nexus IQ GoldenFix\n\n")
        if (gfChanges) {
            for (Map c : gfChanges) sb.append("- `${c.component ?: c.coordinate}`: ${c.from} -> ${c.to} in `${c.file}`\n")
        } else {
            sb.append("- No dependency upgrade.\n")
        }
        sb.append("\n## AppScan SAST\n\n")
        if (sastFindings) {
            for (Map f : sastFindings) sb.append("- **${f.severity}** `${f.file}:${f.line}` ${f.type}: ${f.remediation}\n")
        } else {
            sb.append("- No finding with a file and line.\n")
        }
        sb.append("\n## SonarQube\n\n")
        if (sonarIssues) {
            for (Map s : sonarIssues) sb.append("- **${s.severity}** `${s.file}:${s.line}` ${s.message}\n")
        } else {
            sb.append("- No open issue on the analysed branch.\n")
        }
        return sb.toString()
    }

    @NonCPS
    static String dastReport(List<Map> findings, Map counts, String targetUrl) {
        StringBuilder sb = new StringBuilder("# AppScan DAST results\n\n")
        sb.append("Target: ${targetUrl ?: 'not set'}\n\n")
        sb.append("| Critical | High | Medium | Low |\n|---|---|---|---|\n")
        sb.append("| ${counts.critical ?: 0} | ${counts.high ?: 0} | ${counts.medium ?: 0} | ${counts.low ?: 0} |\n\n")
        if (findings) {
            sb.append("## Findings\n\n")
            for (Map d : findings) sb.append("- **${d.severity}** `${d.endpoint}` ${d.type}: ${d.remediation}\n")
        } else {
            sb.append("The details are in the DAST report of the pipeline.\n")
        }
        return sb.toString()
    }
}
