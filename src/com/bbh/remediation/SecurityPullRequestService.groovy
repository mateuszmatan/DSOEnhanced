package com.bbh.remediation

import com.bbh.core.PipelineState
import com.bbh.remediation.model.PullRequestDecoration
import com.bbh.remediation.port.DecorationRecorder
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
    private final DecorationRecorder recorder

    SecurityPullRequestService(def script, PipelineState state, PullRequestPublisher publisher, SourceRepository repository, DecorationRecorder recorder = null) {
        this.script = script
        this.state = state
        this.publisher = publisher
        this.repository = repository
        this.rest = new RestClient(script)
        this.findingsParser = new SecurityFindingsParser(script, state, rest)
        this.recorder = recorder
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

            boolean opened = false
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
                        opened = created.existing != true
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
            Map added = decoratePullRequestWithFindings(scmCfg, pr, sastFindings, sonarIssues, limits.maxComments as int)
            recordProjectPullRequestComments(projectName, pr, sastFindings)
            recordDecoration([kind: 'STATIC', service: projectName, scm: scmCfg, pullRequest: pr, created: opened, added: added,
                              sast: sastFindings, sonar: sonarIssues, maxComments: limits.maxComments])
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
            String title = "[Security] DAST findings - ${projectName}".toString()
            List<Map> dastFindings = findingsParser.extractDastFindings(projectName, cfg)
            Map decoration = [kind: 'DAST', service: projectName, scm: scmCfg, dast: dastFindings, maxComments: decorationLimits(prCfg).maxComments]

            Map existingPr = publisher.findOpenPullRequest(scmCfg, branch, targetBranch)
            if (existingPr && existingPr.id) {
                String report = SecurityPullRequestView.generateDastReport(dastFindings, counts)
                publisher.addComment(scmCfg, existingPr, report)
                recordDecoration(decoration + [pullRequest: existingPr + [title: existingPr.title ?: title, branch: branch, targetBranch: targetBranch],
                                               added: [generalComments: 1], summary: report])
                return
            }

            String dir = repository.prepareWorkingCopy(branch)
            String md = ''
            Map created = [:]
            try {
                String reportPath = "${dir}/security/dast-findings.md"
                ensureSecurityDir(dir)
                md = SecurityPullRequestView.generateDastReport(dastFindings, counts)
                script.writeFile file: reportPath, text: md
                repository.commit(dir, ['security/dast-findings.md'], '[Security] DAST dynamic security scan findings', [name: 'DevSecOps Bot', email: 'devsecops@bbh.com'])
                Map repoInfo = publisher.repositoryInfo(scmCfg)
                repository.push(dir, branch, [
                        url          : scmCfg.cloneUrl ?: repoInfo.cloneUrl,
                        credentialsId: scmCfg.credentialsId,
                        authType     : scmCfg.authType,
                        cloud        : repoInfo.cloud
                ])
                created = (publisher.createPullRequest(scmCfg, [
                        title       : title,
                        description : md,
                        sourceBranch: branch,
                        targetBranch: targetBranch
                ]) ?: [:]) as Map
            } finally {
                repository.cleanup(dir)
            }
            if (created.existing != true && (created.id || created.url)) {
                recordDecoration(decoration + [pullRequest: [id: created.id, url: created.url, title: title, branch: branch, targetBranch: targetBranch],
                                               created: true, summary: md])
            }
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[SECURITY-PR] DAST security pull request skipped: ${t.message}"
        }
    }

    private Map decoratePullRequestWithFindings(Map scmCfg, Map pr, List<Map> sastFindings, List<Map> sonarIssues, int maxComments, List<Map> dependencies = []) {
        List<String> posted = publisher.existingComments(scmCfg, pr) ?: []
        List<String> changed = publisher.changedFiles(scmCfg, pr)
        List<Map> comments = []
        for (Map f : sastFindings) comments << [file: f.file, line: f.line, text: SecurityPullRequestView.sastLineComment(f), tool: f.tool ?: 'AppScan SAST']
        for (Map d : dependencies) comments << [file: d.file, line: d.line, text: SecurityPullRequestView.dependencyComment(d), tool: 'Nexus IQ']
        for (Map i : sonarIssues) comments << [file: i.file, line: i.line, text: SecurityPullRequestView.sonarLineComment(i), tool: 'SonarQube']
        Map plan = SecurityPullRequestService.commentPlan(comments, changed)
        String where = (pr.url ?: pr.id) as String
        if (changed == null) {
            script.echo "[WARN] [SECURITY-PR] The files changed in pull request ${where} are unknown - every finding with a line is tried as a line comment"
        }
        int added = 0
        int inline = 0
        int general = 0
        boolean limited = false
        for (Map c : (plan.inline as List<Map>)) {
            if (alreadyPosted(posted, c.text as String)) continue
            if (added >= maxComments) {
                limited = true
                break
            }
            try {
                publisher.addLineComment(scmCfg, pr, c.file as String, (c.line as Number).intValue(), c.text as String)
                posted << (c.text as String)
                added++
                inline++
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                script.echo "[SECURITY-PR] Could not add ${c.tool} line comment: ${e.message}"
            }
        }
        for (Map g : (plan.general as List<Map>)) {
            if (limited) break
            String text = SecurityPullRequestView.unchangedFileComment(g.file as String, g.entries as List<Map>)
            if (alreadyPosted(posted, text)) continue
            if (added >= maxComments) {
                limited = true
                break
            }
            try {
                publisher.addComment(scmCfg, pr, text)
                posted << text
                added++
                general++
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                script.echo "[SECURITY-PR] Could not add the comment for ${g.file ?: 'the findings without a file'}: ${e.message}"
            }
        }
        if (limited) script.echo "[SECURITY-PR] Further findings are listed in the report only (pullRequestDecoration.maxComments: ${maxComments})"
        if (added > 0) {
            script.echo "[SECURITY-PR] Pull request ${where}: ${inline} comment(s) under the line of a changed file, ${general} pull request comment(s) for the files it does not change"
        }
        return [lineComments: inline, generalComments: general, limited: limited]
    }

    private void recordDecoration(Map facts) {
        if (recorder == null) return
        Map added = (facts.added ?: [:]) as Map
        if (facts.created != true && !added.lineComments && !added.generalComments) return
        Map pr = (facts.pullRequest ?: [:]) as Map
        try {
            recorder.recorded(PullRequestDecoration.create(facts))
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[SECURITY-PR] The decoration of pull request ${pr.url ?: pr.id} was not recorded: ${t.message ?: t.getClass().getName()}"
        }
    }

    @NonCPS
    static Map commentPlan(List<Map> comments, List<String> changed) {
        List<Map> inline = []
        Map byFile = [:]
        List<String> order = []
        for (Map c : (comments ?: [])) {
            String file = ((c.file ?: '') as String).trim()
            String inPullRequest = file ? SecurityPullRequestService.changedPath(file, changed) : null
            if (file && c.line && (changed == null || inPullRequest)) {
                inline << (c + [file: inPullRequest ?: file])
                continue
            }
            if (!byFile.containsKey(file)) {
                byFile[file] = []
                order << file
            }
            (byFile[file] as List) << [line: c.line, text: c.text, tool: c.tool]
        }
        List<Map> general = []
        for (String file : order) general << [file: file, entries: byFile[file]]
        return [inline: inline, general: general]
    }

    @NonCPS
    static String changedPath(String file, List<String> changed) {
        if (changed == null) return null
        if (changed.contains(file)) return file
        String wanted = file.replace('\\', '/').replaceFirst(/^(?:\.\/|\/)+/, '')
        List<String> matches = []
        for (String path : changed) {
            if (path == wanted || path.endsWith('/' + wanted) || wanted.endsWith('/' + path)) matches << path
        }
        return matches.size() == 1 ? matches[0] : null
    }

    private List<Map> dependencyFindings(String projectName) {
        Map gf = (state.projectsGoldenFix?.get(projectName) ?: [:]) as Map
        List<Map> out = []
        Map contents = [:]
        String goldenFixUrl = ((gf.branch ?: '') as String).startsWith('GoldenFix-') ? (gf.prUrl ?: '') as String : ''
        for (def raw : ((gf.changes ?: []) as List)) {
            Map c = raw as Map
            String file = (c.file ?: '') as String
            if (file && !contents.containsKey(file)) {
                contents[file] = script.fileExists(file) ? (script.readFile(file) ?: '') : null
            }
            boolean here = file && contents[file] != null
            out << [file: here ? file : '', line: here ? SecurityPullRequestService.manifestLine(contents[file] as String, c) : null,
                    component: c.component, from: c.from, to: c.to, property: c.property ?: '', applied: true, prUrl: goldenFixUrl,
                    reason: here ? '' : "declared in ${file ?: 'a file outside this repository'}".toString()]
        }
        for (def raw : ((gf.unresolved ?: []) as List)) {
            Map u = raw as Map
            out << [file: '', line: null, component: u.component, from: u.from, to: u.to, applied: false, reason: u.reason]
        }
        return out
    }

    @NonCPS
    static Integer manifestLine(String content, Map change) {
        if (!content) return null
        String property = ((change.property ?: '') as String).trim()
        String component = (((change.component ?: '') as String).split(', ')[0] ?: '').trim()
        String name = component.contains(':') ? component.substring(component.lastIndexOf(':') + 1) : component
        String from = ((change.from ?: '') as String).trim()
        List<String> lines = content.split('\n', -1) as List<String>
        List<String> needles = property ? [property] : [name]
        Integer first = null
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i]
            boolean named = false
            for (String needle : needles) {
                if (needle && line.contains(needle)) named = true
            }
            if (!named) continue
            if (from && line.contains(from)) return i + 1
            if (first == null) first = i + 1
        }
        if (first != null && from && !property) {
            for (int i = first; i < Math.min(lines.size(), first + 6); i++) {
                if (lines[i].contains(from)) return i + 1
            }
        }
        return first
    }

    private void decorateDeveloperPullRequest(Map scmCfg, String projectName, List<Map> gfChanges, List<Map> sastFindings, List<Map> sonarIssues, int maxComments) {
        Map pr = developerPullRequest(scmCfg)
        if (!pr?.id) return
        List<Map> dependencies = dependencyFindings(projectName)
        Map added = decoratePullRequestWithFindings(scmCfg, pr, sastFindings, sonarIssues, maxComments, dependencies)
        String summary = SecurityPullRequestView.staticSummary(projectName, gfChanges, sastFindings, sonarIssues)
        int summaries = 0
        if (!alreadyPosted(publisher.existingComments(scmCfg, pr) ?: [], summary)) {
            try {
                publisher.addComment(scmCfg, pr, summary)
                summaries = 1
            } catch (Exception e) {
                if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
                script.echo "[SECURITY-PR] Comment on pull request ${pr.url ?: pr.id} not added: ${e.message}"
            }
        }
        script.echo "[SECURITY-PR] ${projectName}: pull request ${pr.url ?: pr.id} of the analysed branch decorated with ${sastFindings.size()} SAST and ${sonarIssues.size()} SonarQube finding(s)"
        recordDecoration([kind: 'BRANCH', service: projectName, scm: scmCfg, pullRequest: developerBranches() + pr, created: false,
                          added: added + [generalComments: (added.generalComments as int) + summaries],
                          sast: sastFindings, dependencies: dependencies, sonar: sonarIssues, summary: summary, maxComments: maxComments])
    }

    private Map developerPullRequest(Map scmCfg) {
        String changeId = (script.env.CHANGE_ID ?: '') as String
        if (changeId) return [id: changeId, url: (script.env.CHANGE_URL ?: '') as String]
        String branch = BuildUtils.normalizeBranch((script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String)
        if (!branch || branch == 'HEAD' || branch.startsWith('GoldenFix-') || branch.startsWith('security-')) return null
        return publisher.findOpenPullRequest(scmCfg, branch, '')
    }

    private Map developerBranches() {
        if ((script.env.CHANGE_ID ?: '') as String) {
            return [title       : (script.env.CHANGE_TITLE ?: '') as String,
                    branch      : (script.env.CHANGE_BRANCH ?: '') as String,
                    targetBranch: (script.env.CHANGE_TARGET ?: '') as String]
        }
        return [title: '', branch: BuildUtils.normalizeBranch((script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String), targetBranch: '']
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
