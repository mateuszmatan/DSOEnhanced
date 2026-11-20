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
    private final SecurityFindingsParser findingsParser

    SecurityPullRequestService(def script, PipelineState state, PullRequestPublisher publisher, SourceRepository repository) {
        this.script = script
        this.state = state
        this.publisher = publisher
        this.repository = repository
        this.rest = new RestClient(script)
        this.findingsParser = new SecurityFindingsParser(script, state, rest)
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
            Map limits = decorationLimits(prCfg)
            for (def warning : (limits.warnings as List)) script.echo "[WARN] [SECURITY-PR] ${projectName}: ${warning}"
            for (def note : (limits.notes as List)) script.echo "[INFO] [SECURITY-PR] ${projectName}: ${note}"
            List<Map> sastFindings = atLeast(findingsParser.extractSastFindings(projectName, cfg), limits.sastMinSeverity as String, sastOrder())
            List<Map> sonarIssues = atLeast(findingsParser.extractSonarIssues(projectName, cfg), limits.sonarMinSeverity as String, sonarOrder())
            decorateDeveloperPullRequest(scmCfg, projectName, gfChanges, sastFindings, sonarIssues, limits.maxComments as int)

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
                        script.writeFile file: reportPath, text: SecurityPullRequestView.generateStaticReport(gfChanges, sastFindings, sonarIssues)
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
            decoratePullRequestWithFindings(scmCfg, pr, sastFindings, sonarIssues, limits.maxComments as int)
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
            List<Map> dastFindings = findingsParser.extractDastFindings(projectName, cfg)

            Map existingPr = publisher.findOpenPullRequest(scmCfg, branch, targetBranch)
            if (existingPr && existingPr.id) {
                publisher.addComment(scmCfg, existingPr, SecurityPullRequestView.generateDastReport(dastFindings, counts))
                return
            }

            String dir = repository.prepareWorkingCopy(branch)
            try {
                String reportPath = "${dir}/security/dast-findings.md"
                ensureSecurityDir(dir)
                String md = SecurityPullRequestView.generateDastReport(dastFindings, counts)
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

    private void decoratePullRequestWithFindings(Map scmCfg, Map pr, List<Map> sastFindings, List<Map> sonarIssues, int maxComments) {
        List<String> posted = publisher.existingComments(scmCfg, pr) ?: []
        List<Map> comments = sastFindings.collect { [finding: it, text: SecurityPullRequestView.sastLineComment(it), tool: 'SAST'] } +
                sonarIssues.collect { [finding: it, text: SecurityPullRequestView.sonarLineComment(it), tool: 'SonarQube'] }
        int added = 0
        for (Map c : comments) {
            Map f = c.finding as Map
            if (!f.file || !f.line || alreadyPosted(posted, c.text as String)) continue
            if (added >= maxComments) {
                script.echo "[SECURITY-PR] Further findings are listed in the report only (pullRequestDecoration.maxComments: ${maxComments})"
                break
            }
            try {
                publisher.addLineComment(scmCfg, pr, f.file as String, (f.line as Number).intValue(), c.text as String)
                posted << (c.text as String)
                added++
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                script.echo "[SECURITY-PR] Could not add ${c.tool} line comment: ${e.message}"
            }
        }
    }

    private void decorateDeveloperPullRequest(Map scmCfg, String projectName, List<Map> gfChanges, List<Map> sastFindings, List<Map> sonarIssues, int maxComments) {
        Map pr = developerPullRequest(scmCfg)
        if (!pr?.id) return
        decoratePullRequestWithFindings(scmCfg, pr, sastFindings, sonarIssues, maxComments)
        String summary = SecurityPullRequestView.staticSummary(projectName, gfChanges, sastFindings, sonarIssues)
        if (!alreadyPosted(publisher.existingComments(scmCfg, pr) ?: [], summary)) {
            try {
                publisher.addComment(scmCfg, pr, summary)
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                script.echo "[SECURITY-PR] Comment on pull request ${pr.url ?: pr.id} not added: ${e.message}"
            }
        }
        script.echo "[SECURITY-PR] ${projectName}: pull request ${pr.url ?: pr.id} of the analysed branch decorated with ${sastFindings.size()} SAST and ${sonarIssues.size()} SonarQube finding(s)"
    }

    private Map developerPullRequest(Map scmCfg) {
        String changeId = (script.env.CHANGE_ID ?: '') as String
        if (changeId) return [id: changeId, url: (script.env.CHANGE_URL ?: '') as String]
        String branch = BuildUtils.normalizeBranch((script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String)
        if (!branch || branch == 'HEAD' || branch.startsWith('GoldenFix-') || branch.startsWith('security-')) return null
        return publisher.findOpenPullRequest(scmCfg, branch, '')
    }

    @NonCPS
    static Map decorationLimits(Map prCfg) {
        List<String> warnings = []
        List<String> notes = []
        int maxComments = 25
        String rawMax = (prCfg?.maxComments == null ? '' : prCfg.maxComments as String).trim()
        if (rawMax) {
            if (rawMax ==~ /\d{1,6}/) {
                maxComments = rawMax.toInteger()
                if (maxComments == 25) notes << 'pullRequestDecoration.maxComments: 25 equals the default - the key can be removed'
            } else {
                warnings << "pullRequestDecoration.maxComments: '${rawMax}' is not a number of comments - using the default 25".toString()
            }
        }
        return [maxComments     : maxComments,
                sastMinSeverity : severityLimit(prCfg, 'sastMinSeverity', 'MEDIUM', sastOrder(), warnings, notes),
                sonarMinSeverity: severityLimit(prCfg, 'sonarMinSeverity', 'MAJOR', sonarOrder(), warnings, notes),
                warnings        : warnings,
                notes           : notes]
    }

    @NonCPS
    static String severityLimit(Map prCfg, String key, String fallback, List<String> order, List<String> warnings, List<String> notes) {
        String raw = ((prCfg?.get(key) ?: '') as String).trim().toUpperCase()
        if (!raw) return fallback
        if (!order.contains(raw)) {
            warnings << "pullRequestDecoration.${key}: '${prCfg.get(key)}' is not one of ${order.join(', ')} - using the default ${fallback}".toString()
            return fallback
        }
        if (raw == fallback) notes << "pullRequestDecoration.${key}: ${fallback} equals the default - the key can be removed".toString()
        return raw
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
        int min = Math.max(order.indexOf(minimum), 0)
        return (findings ?: []).findAll { order.indexOf(((it.severity ?: '') as String).toUpperCase()) >= min } as List<Map>
    }

    private void ensureSecurityDir(String dir) {
        if (script.isUnix()) {
            script.sh "mkdir -p '${BuildUtils.escapeForSingleQuotes(dir)}/security'"
        } else {
            script.powershell "New-Item -ItemType Directory -Force -Path '${BuildUtils.psSingleQuote(dir)}\\security' | Out-Null"
        }
    }

    @NonCPS
    static String normalizeRepoPath(String path, String ws = '', String repoPrefix = '') {
        return SecurityFindingsParser.normalizeRepoPath(path, ws, repoPrefix)
    }
}
