package com.bbh.remediation

import com.bbh.remediation.model.GoldenFix
import com.bbh.core.PipelineState
import com.bbh.remediation.port.BuildVerifier
import com.bbh.remediation.port.DependencyRemediation
import com.bbh.remediation.port.GoldenFixSource
import com.bbh.remediation.port.ManifestUpdater
import com.bbh.remediation.port.PullRequestPublisher
import com.bbh.remediation.port.SourceRepository
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class GoldenFixService implements DependencyRemediation {

    @NonCPS
    static List<String> defaultExcludedDirs() {
        return ['.git', 'node_modules', 'bower_components', 'target', 'build', '.gradle', 'dist',
                'venv', '.venv', '__pycache__', '.tox', 'site-packages']
    }

    private final def                    script
    private final PipelineState          state
    private final GoldenFixSource        source
    private final SourceRepository       repository
    private final PullRequestPublisher   publisher
    private final List<ManifestUpdater>  updaters
    private final BuildVerifier          verifier

    GoldenFixService(def script, PipelineState state, GoldenFixSource source, SourceRepository repository,
                     PullRequestPublisher publisher, List<ManifestUpdater> updaters, BuildVerifier verifier = null) {
        this.script     = script
        this.state      = state
        this.source     = source
        this.repository = repository
        this.publisher  = publisher
        this.updaters   = updaters
        this.verifier   = verifier
    }

    void remediate(List<Map> scanRefs) {
        Map cfg = (state.cfg?.goldenFix ?: [:]) as Map
        Map result = [
                project     : state.currentProjectName,
                status      : 'SKIPPED',
                message     : '',
                fixes       : [],
                changes     : [],
                unresolved  : [],
                prUrl       : '',
                prTitle     : '',
                verified    : null,
                verifyLog   : '',
                branch      : '',
                targetBranch: ''
        ]
        try {
            execute(cfg, scanRefs ?: [], result)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            result.status  = 'ERROR'
            result.message = (e.message ?: e.getClass().getSimpleName()) as String
            script.echo "[GOLDENFIX] Remediation failed: ${result.message}"
        } finally {
            state.recordGoldenFix(result)
        }
    }

    private void execute(Map cfg, List<Map> scanRefs, Map result) {
        if (!BuildUtils.booleanValue(cfg.enabled, true)) {
            finish(result, 'SKIPPED', 'GoldenFix is disabled (goldenFix.enabled: false)')
            return
        }
        if (!script.isUnix()) {
            finish(result, 'SKIPPED', 'GoldenFix remediation requires a Linux/macOS agent')
            return
        }

        script.echo "[GOLDENFIX] Nexus IQ policy violated - fetching GoldenFix list for ${scanRefs.size()} Nexus IQ application(s)"
        List<Map> fixes = []
        for (int i = 0; i < scanRefs.size(); i++) {
            fixes.addAll(source.fetchGoldenFixes(scanRefs[i], cfg))
        }
        fixes = mergeFixes(fixes)
        result.fixes = fixes
        if (!fixes) {
            finish(result, 'NO_FIXES', 'Nexus IQ returned no GoldenFix versions for the violating direct dependencies')
            return
        }

        Map scmCfg = (state.cfg?.scm?.bitbucket ?: [:]) as Map
        if (!scmCfg.url) {
            finish(result, 'NOT_CONFIGURED', "${fixes.size()} GoldenFix(es) available, but scm.bitbucket.url is not configured - pull request not raised")
            return
        }

        Map pr = (state.goldenFixPullRequest ?: [:]) as Map
        String title = (pr.title ?: "GoldenFix-${timestamp(cfg.timeZone as String)}") as String
        String targetBranch = (pr.targetBranch ?: scmCfg.targetBranch ?: repository.resolveCurrentBranch()) as String
        if (!targetBranch) {
            script.error("[GOLDENFIX] Cannot determine the pull request target branch - set scm.bitbucket.targetBranch for the service in the DevSecOps portal")
        }
        result.prTitle      = title
        result.branch       = title
        result.targetBranch = targetBranch

        String dir = repository.prepareWorkingCopy(title)
        try {
            Map attempt = applyVerifiedFixes(dir, fixes, cfg)
            Map applied = attempt.applied as Map
            result.changes    = applied.changes
            result.unresolved = (applied.unresolved as List) + (attempt.dropped as List)
            result.verified   = attempt.verified
            result.verifyLog  = attempt.log
            if (attempt.verified == false) {
                finish(result, 'BUILD_FAILED', "The proposed upgrades do not build: ${attempt.reason}".toString())
                return
            }
            String commitId = applied.files
                    ? repository.commit(dir, applied.files as List<String>, commitMessage(title, applied.changes as List, scanRefs), [name: cfg.commitAuthorName, email: cfg.commitAuthorEmail])
                    : ''
            if (!commitId) {
                finish(result, 'NO_MANIFEST_CHANGES', 'No Maven, Gradle, npm, pip or pub manifest declares the vulnerable versions - nothing to change')
                return
            }

            Map repoInfo = publisher.repositoryInfo(scmCfg)
            repository.push(dir, title, [
                    url          : scmCfg.cloneUrl ?: repoInfo.cloneUrl,
                    credentialsId: scmCfg.credentialsId,
                    authType     : scmCfg.authType,
                    cloud        : repoInfo.cloud
            ])

            if (pr.url) {
                result.prUrl = pr.url
                finish(result, 'PR_UPDATED', "GoldenFix pull request ${title} updated with ${applied.changes.size()} change(s): ${pr.url}")
            } else {
                Map created = publisher.createPullRequest(scmCfg, [
                        title       : title,
                        description : pullRequestDescription(title, applied.changes as List, applied.unresolved as List, scanRefs, buildContext()),
                        sourceBranch: title,
                        targetBranch: targetBranch
                ])
                state.goldenFixPullRequest = [title: title, branch: title, targetBranch: targetBranch, url: created.url, id: created.id]
                result.prUrl = created.url
                finish(result, 'PR_CREATED', "GoldenFix pull request ${title} raised: ${created.url}")
            }
        } finally {
            repository.cleanup(dir)
        }
    }

    private Map applyVerifiedFixes(String dir, List<Map> fixes, Map cfg) {
        Map verifyCfg = (cfg.verify ?: [:]) as Map
        boolean enabled = BuildUtils.booleanValue(verifyCfg.enabled, true)
        int maxAttempts = (verifyCfg.maxAttempts != null ? verifyCfg.maxAttempts.toString().toInteger() : 3)
        if (maxAttempts < 1) maxAttempts = 1

        List<Map> current = fixes
        List dropped = []
        Map applied = applyFixes(dir, current, cfg)
        if (!enabled || verifier == null) {
            script.echo "[GOLDENFIX] Pre-check build skipped (${enabled ? 'no build verifier is wired in' : 'goldenFix.verify.enabled is false'})"
            return [applied: applied, verified: null, dropped: dropped, reason: '', log: '']
        }

        String reason = ''
        String log = ''
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!applied.files) {
                script.echo '[GOLDENFIX] Pre-check build skipped - no manifest changed'
                return [applied: applied, verified: null, dropped: dropped, reason: '', log: '']
            }
            script.echo "[GOLDENFIX] Pre-check build ${attempt} of ${maxAttempts} for ${describeVersions(current)}"
            Map verdict = verifier.verify(dir, applied.files as List<String>, verifyCfg)
            log = verdict.log as String
            if (verdict.ok) {
                script.echo verdict.skipped
                        ? "[GOLDENFIX] Pre-check build skipped - ${log}"
                        : "[GOLDENFIX] Pre-check build passed (${log}) with ${describeVersions(current)}"
                return [applied: applied, verified: verdict.skipped ? null : true, dropped: dropped, reason: '', log: log]
            }
            reason = "the build failed with ${describeVersions(current)} (${log})".toString()
            script.echo "[GOLDENFIX] Pre-check build failed - lowering the proposed versions so they stay backward compatible"
            Map lowered = lowerFixes(current)
            if (!lowered.changed) {
                return [applied: applied, verified: false, dropped: dropped,
                        reason: "${reason} and no lower backward compatible version is offered".toString(), log: log]
            }
            current = lowered.fixes as List<Map>
            dropped.addAll(lowered.dropped as List)
            repository.revert(dir)
            applied = applyFixes(dir, current, cfg)
        }
        return [applied: applied, verified: false, dropped: dropped,
                reason: "${reason} after ${maxAttempts} attempt(s)".toString(), log: log]
    }

    @NonCPS
    private Map lowerFixes(List<Map> fixes) {
        List<Map> out = []
        List dropped = []
        boolean changed = false
        for (Map fix : (fixes ?: [])) {
            List alternatives = (fix.alternatives ?: []) as List
            if (alternatives.isEmpty()) {
                dropped << [component: fix.displayName, from: fix.currentVersion, to: fix.targetVersion,
                            reason: 'every offered version broke the pre-check build, so this dependency was left untouched']
                changed = true
                continue
            }
            Map lowered = [:]
            lowered.putAll(fix)
            lowered.targetVersion = alternatives.get(0)
            lowered.alternatives = alternatives.size() > 1 ? alternatives.subList(1, alternatives.size()) : []
            lowered.nonBreaking = GoldenFix.patchLevelChange(fix.currentVersion as String, lowered.targetVersion as String)
            out << lowered
            changed = true
        }
        return [fixes: out, dropped: dropped, changed: changed && !out.isEmpty()]
    }

    @NonCPS
    private String describeVersions(List<Map> fixes) {
        List parts = []
        for (Map fix : (fixes ?: [])) {
            parts << "${fix.displayName} ${fix.currentVersion} -> ${fix.targetVersion}".toString()
        }
        return parts.join(', ')
    }

    private Map applyFixes(String dir, List<Map> fixes, Map cfg) {
        List<String> excluded = (cfg.excludeDirs ?: defaultExcludedDirs()) as List<String>
        List<ManifestUpdater> active = []
        List<String> patterns = []
        for (ManifestUpdater updater : updaters) {
            if (fixesFor(fixes, updater.ecosystem())) {
                active << updater
                patterns.addAll(updater.filePatterns())
            }
        }
        List<String> files = active ? repository.findFiles(dir, patterns.unique(), excluded) : []
        script.echo "[GOLDENFIX] Inspecting ${files.size()} dependency manifest file(s) in the source tree"

        List changes = []
        List notes = []
        List propertyRequests = []
        List<String> changedFiles = []

        for (ManifestUpdater updater : active) {
            List<Map> ecosystemFixes = fixesFor(fixes, updater.ecosystem())
            List<String> ownFiles = []
            for (String f : files) {
                if (updater.supports(f)) ownFiles << f
            }

            List properties = []
            for (String f : ownFiles) {
                String text = repository.readText(dir, f)
                Map r = updater.updateDeclarations(f, text, ecosystemFixes)
                changes.addAll(r.changes as List)
                notes.addAll(r.notes as List)
                properties.addAll(r.properties as List)
                if (r.content != text) {
                    repository.writeText(dir, f, r.content as String)
                    if (!changedFiles.contains(f)) changedFiles << f
                }
            }

            if (properties) {
                List<Map> merged = GoldenFix.mergePropertyRequests(properties)
                propertyRequests.addAll(merged)
                for (String f : ownFiles) {
                    String text = repository.readText(dir, f)
                    Map r = updater.updateProperties(f, text, merged)
                    changes.addAll(r.changes as List)
                    if (r.content != text) {
                        repository.writeText(dir, f, r.content as String)
                        if (!changedFiles.contains(f)) changedFiles << f
                    }
                }
            }
        }

        for (Map c : (changes as List<Map>)) {
            String what = c.property ? "property ${c.property} (${c.component})" : c.component
            script.echo "[GOLDENFIX]   ${c.file}: ${what} ${c.from} -> ${c.to}"
        }
        return [files: changedFiles, changes: changes, unresolved: unresolvedFixes(fixes, changes, notes, propertyRequests)]
    }

    private void finish(Map result, String status, String message) {
        result.status  = status
        result.message = message
        script.echo "[GOLDENFIX] ${message}"
    }

    private Map buildContext() {
        return [
                jobName    : script.env.JOB_NAME ?: '',
                buildNumber: script.env.BUILD_NUMBER ?: '',
                buildUrl   : script.env.BUILD_URL ?: '',
                project    : state.currentProjectName,
                reportUrl  : state.nexusIqResults?.url ?: '',
                critical   : state.nexusIqResults?.critical ?: 0,
                high       : state.nexusIqResults?.high ?: 0,
                medium     : state.nexusIqResults?.medium ?: 0
        ]
    }

    @NonCPS
    static List<Map> fixesFor(List<Map> fixes, String ecosystem) {
        return fixes.findAll { it.ecosystem == ecosystem }
    }

    @NonCPS
    static List<Map> mergeFixes(List<Map> fixes) {
        Map byKey = [:]
        for (Map fix : fixes) {
            Map existing = byKey[fix.key] as Map
            if (!existing) {
                byKey[fix.key] = [:] + fix
            } else if (GoldenFix.isUpgrade(existing.targetVersion as String, fix.targetVersion as String)) {
                existing.targetVersion   = fix.targetVersion
                existing.remediationType = fix.remediationType
                existing.golden          = fix.golden
            }
        }
        return byKey.values().toList()
    }

    @NonCPS
    static List<Map> unresolvedFixes(List<Map> fixes, List<Map> changes, List<Map> notes, List<Map> propertyRequests) {
        List resolved = []
        for (Map c : changes) resolved.addAll(c.componentKeys as List)
        List<Map> out = []
        for (Map fix : fixes) {
            if (resolved.contains(fix.key)) continue
            List<String> reasons = notes.findAll { it.componentKey == fix.key }.collect { "${it.file}: ${it.reason}".toString() }.unique()
            List<Map> props = propertyRequests.findAll { (it.componentKeys as List).contains(fix.key) }
            if (props && !reasons) {
                reasons << "version property ${props.collect { it.name }.join(', ')} not found or already newer".toString()
            }
            out << [
                    component: fix.displayName,
                    ecosystem: fix.ecosystem,
                    from     : fix.currentVersion,
                    to       : fix.targetVersion,
                    reason   : reasons ? reasons.join('; ') : 'not declared in any supported manifest (BOM/parent managed, or already upgraded)'
            ]
        }
        return out
    }

    String timestamp(String timeZone) {
        String prefix = timeZone ? "TZ='${timeZone}' " : ''
        return script.sh(returnStdout: true, script: "${prefix}date +%Y%m%d%H%M").trim()
    }

    @NonCPS
    static String commitMessage(String title, List<Map> changes, List<Map> scanRefs) {
        StringBuilder sb = new StringBuilder()
        List components = []
        for (Map c : changes) { if (!components.contains(c.component)) components << c.component }
        sb.append("${title}: upgrade ${components.size()} vulnerable direct dependenc${components.size() == 1 ? 'y' : 'ies'}\n\n")
        sb.append("Automated Nexus IQ GoldenFix remediation for application(s): ${scanRefs.collect { it.application }.unique().join(', ')}\n\n")
        for (Map c : changes) {
            String what = c.property ? "${c.component} (property ${c.property})" : c.component
            sb.append("- ${what}: ${c.from} -> ${c.to} (${c.file})\n")
        }
        return sb.toString()
    }

    @NonCPS
    static String pullRequestDescription(String title, List<Map> changes, List<Map> unresolved, List<Map> scanRefs, Map ctx) {
        StringBuilder sb = new StringBuilder()
        sb.append("## ${title} - Nexus IQ GoldenFix\n\n")
        sb.append("Raised automatically by the DevSecOps pipeline because the Nexus IQ policy was violated ")
        sb.append("(Critical: ${ctx.critical}, High: ${ctx.high}, Medium: ${ctx.medium}).\n\n")
        if (ctx.buildUrl) sb.append("- Jenkins build: [${ctx.jobName} #${ctx.buildNumber}](${ctx.buildUrl})\n")
        for (Map ref : scanRefs) {
            if (ref.reportUrl) sb.append("- Nexus IQ report (${ref.application}): ${ref.reportUrl}\n")
        }
        sb.append("\n### Applied upgrades\n\n| File | Component | From | To | Version chosen by |\n|---|---|---|---|---|\n")
        for (Map c : changes) {
            String what = c.property ? "${c.component} (property `${c.property}`)" : c.component
            sb.append("| `${c.file}` | ${what} | ${c.from} | **${c.to}** | ${GoldenFix.selectionLabel(c) ?: '-'} |\n")
        }
        if (unresolved) {
            sb.append("\n### Not applied automatically\n\n")
            for (Map u : unresolved) sb.append("- ${u.component} ${u.from} -> ${u.to}: ${u.reason}\n")
        }
        sb.append("\n> Lock files (package-lock.json, yarn.lock, poetry.lock, pinned requirements) are not regenerated. ")
        sb.append("Rebuild and run the tests before merging.\n")
        return sb.toString()
    }
}
