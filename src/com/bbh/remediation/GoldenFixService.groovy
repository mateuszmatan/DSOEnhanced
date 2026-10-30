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
                notes       : [],
                prUrl       : '',
                prTitle     : '',
                verified    : null,
                verifyLog   : '',
                branch      : '',
                targetBranch: ''
        ]
        try {
            execute(cfg, scanRefs ?: [], result)
        } catch (Throwable e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            result.status  = 'ERROR'
            result.message = ((e instanceof Exception ? e.message : null) ?: e.getClass().getSimpleName()) as String
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

        Map prsByRepo = (state.goldenFixPullRequestsByRepo ?: [:]) as Map
        String repoKey = normalizeRepoKey((scmCfg.url ?: scmCfg.cloneUrl ?: '') as String)
        Map pr = (prsByRepo[repoKey] ?: [:]) as Map
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
            List<String> refreshed = (attempt.refreshed ?: []) as List<String>
            result.changes    = applied.changes
            result.unresolved = (applied.unresolved as List) + (attempt.dropped as List)
            result.notes      = (applied.notes as List) + refreshedNotes(refreshed)
            result.verified   = attempt.verified
            result.verifyLog  = attempt.log
            if (attempt.verified == false) {
                finish(result, 'BUILD_FAILED', "The proposed upgrades do not build: ${attempt.reason}".toString())
                return
            }
            List<Map> covered = pr.url ? coveredFixes(fixes, applied.found as List, applied.changes as List<Map>, (pr.raised ?: []) as List<Map>, applied.shared as List<String>) : []
            String raised = alreadyRaised(covered, pr.url as String)
            if (covered) {
                result.unresolved = withoutCovered(result.unresolved as List<Map>, covered)
                result.notes      = (result.notes as List) + [raised]
            }
            String commitId = applied.files
                    ? repository.commit(dir, (applied.files as List<String>) + refreshed, commitMessage(title, applied.changes as List, scanRefs), [name: cfg.commitAuthorName, email: cfg.commitAuthorEmail])
                    : ''
            if (!commitId && covered && covered.size() == fixes.size()) {
                result.prUrl = pr.url
                finish(result, 'PR_UPDATED', raised)
                return
            }
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
                pr.raised = ((pr.raised ?: []) as List) + (applied.changes as List)
                comment(scmCfg, pr, pullRequestComment(state.currentProjectName as String, applied.changes as List, result.unresolved as List, result.notes as List))
                finish(result, 'PR_UPDATED', "GoldenFix pull request ${title} updated with ${applied.changes.size()} change(s): ${pr.url}")
            } else {
                Map created = publisher.createPullRequest(scmCfg, [
                        title       : title,
                        description : pullRequestDescription(title, applied.changes as List, applied.unresolved as List, scanRefs, buildContext(),
                                result.notes as List, !refreshed.isEmpty()),
                        sourceBranch: title,
                        targetBranch: targetBranch
                ])
                Map prEntry = [title: title, branch: title, targetBranch: targetBranch, url: created.url, id: created.id, raised: applied.changes]
                state.goldenFixPullRequest = prEntry
                if (state.goldenFixPullRequestsByRepo == null) state.goldenFixPullRequestsByRepo = [:]
                state.goldenFixPullRequestsByRepo[repoKey] = prEntry
                result.prUrl = created.url
                finish(result, 'PR_CREATED', "GoldenFix pull request ${title} raised: ${created.url}")
            }
        } finally {
            repository.cleanup(dir)
        }
    }

    private void comment(Map scmCfg, Map pr, String text) {
        try {
            publisher.addComment(scmCfg, pr, text)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[GOLDENFIX] The pull request comment could not be added: ${e.message ?: e.getClass().getSimpleName()}"
        }
    }

    private Map applyVerifiedFixes(String dir, List<Map> fixes, Map cfg) {
        Map verifyCfg = (cfg.verify ?: [:]) as Map
        boolean enabled = BuildUtils.booleanValue(verifyCfg.enabled, true)
        int maxAttempts = (verifyCfg.maxAttempts != null ? verifyCfg.maxAttempts.toString().toInteger() : 3)
        if (maxAttempts < 1) maxAttempts = 1

        List<Map> current = fixes
        List dropped = []
        boolean align = true
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
            script.echo "[GOLDENFIX] Pre-check build ${attempt} of ${maxAttempts} for ${describeVersions(applied.effective as List<Map>)}"
            Map verdict = verifier.verify(dir, applied.files as List<String>,
                    verifyCfg + [catalogConsumers: applied.consumers, lockFiles: applied.locks, node: state.cfg?.build?.node])
            log = verdict.log as String
            if (verdict.ok) {
                script.echo verdict.skipped
                        ? "[GOLDENFIX] Pre-check build skipped - ${log}"
                        : "[GOLDENFIX] Pre-check build passed (${log}) with ${describeVersions(applied.effective as List<Map>)}"
                return [applied: applied, verified: verdict.skipped ? null : true, dropped: dropped, reason: '', log: log, refreshed: verdict.files ?: []]
            }
            reason = "the build failed with ${describeVersions(applied.effective as List<Map>)} (${log})".toString()
            if (align && applied.alignedUp) {
                script.echo '[GOLDENFIX] Pre-check build failed - retrying with the Nexus IQ versions instead of the catalog versions'
                align = false
                repository.revert(dir)
                applied = applyFixes(dir, current, cfg, false)
                continue
            }
            script.echo "[GOLDENFIX] Pre-check build failed - lowering the proposed versions so they stay backward compatible"
            Map lowered = lowerFixes(current)
            if (!lowered.changed) {
                return [applied: applied, verified: false, dropped: dropped,
                        reason: "${reason} and no lower backward compatible version is offered".toString(), log: log]
            }
            current = lowered.fixes as List<Map>
            dropped.addAll(lowered.dropped as List)
            repository.revert(dir)
            applied = applyFixes(dir, current, cfg, align)
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

    private Map applyFixes(String dir, List<Map> fixes, Map cfg, boolean align = true) {
        List<String> excluded = (cfg.excludeDirs ?: defaultExcludedDirs()) as List<String>
        List<ManifestUpdater> active = []
        List<String> patterns = []
        for (ManifestUpdater updater : updaters) {
            if (fixesFor(fixes, updater.ecosystem())) {
                active << updater
                patterns.addAll(updater.filePatterns())
            }
        }
        List<String> scanned = active ? repository.findFiles(dir, patterns.unique(), excluded) : []
        Map texts = [:]
        Map catalogs = fixesFor(fixes, 'maven') ? discover(dir, scanned, cfg, excluded, texts)
                : [files: scanned, entries: [:], notes: [], externals: [:], holders: [:]]
        List<String> files = catalogs.files as List<String>
        Map entries = catalogs.entries as Map
        script.echo "[GOLDENFIX] Inspecting ${files.size()} dependency manifest file(s) in the source tree"
        for (ManifestUpdater updater : active) updater.useCatalogs(entries)

        Map run = [changes: [], notes: [], requests: [], found: [], files: []]
        List<String> shared = []
        List<String> others = []
        for (String f : files) {
            if (entries.containsKey(f)) shared << f else others << f
        }
        applyRound(dir, active, fixes, shared, files, texts, run)
        Map aligned = alignToCatalogs(fixes, run.found as List, shared)
        List<Map> effective = align ? aligned.fixes as List<Map> : fixes
        applyRound(dir, active, effective, others, files, texts, run)
        run.changes = collapseChanges(run.changes as List<Map>)

        for (Map c : (run.changes as List<Map>)) {
            String what = c.property ? "property ${c.property} (${c.component})" : c.component
            script.echo "[GOLDENFIX]   ${c.file}: ${what} ${c.from} -> ${c.to}"
        }
        return [files     : run.files, changes: run.changes, found: run.found,
                unresolved: unresolvedFixes(fixes, run.changes as List<Map>, run.notes as List<Map>, run.requests as List<Map>, catalogs),
                notes     : (catalogs.notes as List) + outsideCatalogs(run.changes as List<Map>, aligned.versions as Map, shared),
                consumers : CatalogDiscovery.consumers(entries), locks: lockFiles(scanned), effective: effective, alignedUp: align && effective != fixes, shared: shared]
    }

    private Map discover(String dir, List<String> scanned, Map cfg, List<String> excluded, Map texts) {
        List<String> listed = CatalogDiscovery.listed(cfg.versionCatalogs)
        for (String f : CatalogDiscovery.toRead(scanned)) read(dir, texts, f)
        List<String> wanted = CatalogDiscovery.wanted(scanned, texts, listed)
        List<String> extra = wanted ? repository.findFiles(dir, wanted, excluded) : []
        for (String f : CatalogDiscovery.toRead(extra)) read(dir, texts, f)
        return CatalogDiscovery.discover(scanned, extra, texts, listed)
    }

    private void applyRound(String dir, List<ManifestUpdater> active, List<Map> fixes, List<String> roundFiles, List<String> files, Map texts, Map run) {
        if (!roundFiles) return
        for (ManifestUpdater updater : active) {
            List<Map> ecosystemFixes = fixesFor(fixes, updater.ecosystem())
            List<String> ownFiles = []
            for (String f : files) {
                if (updater.supports(f)) ownFiles << f
            }

            List properties = []
            for (String f : ownFiles) {
                if (!roundFiles.contains(f)) continue
                String text = read(dir, texts, f)
                Map r = updater.updateDeclarations(f, text, ecosystemFixes)
                collect(run, r)
                properties.addAll(r.properties as List)
                write(dir, texts, f, text, r.content as String, run)
            }

            if (properties) {
                List<Map> merged = GoldenFix.mergePropertyRequests(properties)
                (run.requests as List).addAll(merged)
                for (String f : ownFiles) {
                    String text = read(dir, texts, f)
                    Map r = updater.updateProperties(f, text, merged)
                    collect(run, r)
                    write(dir, texts, f, text, r.content as String, run)
                }
            }
        }
    }

    private String read(String dir, Map texts, String file) {
        if (!texts.containsKey(file)) texts[file] = repository.readText(dir, file)
        return texts[file] as String
    }

    private void write(String dir, Map texts, String file, String before, String after, Map run) {
        if (after == before) return
        repository.writeText(dir, file, after)
        texts[file] = after
        if (!(run.files as List).contains(file)) (run.files as List) << file
    }

    @NonCPS
    private static void collect(Map run, Map r) {
        (run.changes as List).addAll((r.changes ?: []) as List)
        (run.notes as List).addAll((r.notes ?: []) as List)
        (run.found as List).addAll((r.found ?: []) as List)
    }

    @NonCPS
    static Map alignToCatalogs(List<Map> fixes, List found, List<String> shared) {
        Map versions = [:]
        for (def f : found) {
            Map entry = f as Map
            if (!shared.contains(entry.file)) continue
            for (def key : (entry.componentKeys as List)) {
                Map known = versions[key] as Map
                if (!known || GoldenFix.compare(entry.version as String, known.version as String) > 0) versions[key] = entry
            }
        }
        List<Map> out = []
        for (Map fix : fixes) {
            Map known = versions[fix.key] as Map
            out << (known && GoldenFix.isUpgrade(fix.targetVersion as String, known.version as String) ? fix + [targetVersion: known.version] : fix)
        }
        return [fixes: out, versions: versions]
    }

    @NonCPS
    static List<Map> collapseChanges(List<Map> changes) {
        List<Map> out = []
        for (Map c : changes) {
            Map earlier = c.property ? out.find { it.file == c.file && it.property == c.property && it.to == c.from } as Map : null
            if (earlier) earlier.putAll(c + [from: earlier.from]) else out << c
        }
        return out
    }

    @NonCPS
    static List<String> outsideCatalogs(List<Map> changes, Map versions, List<String> shared) {
        List<String> out = []
        for (Map c : changes) {
            if (shared.contains(c.file)) continue
            for (def key : (c.componentKeys as List)) {
                Map known = versions[key] as Map
                if (!known) continue
                String note = "${c.file}: ${c.component} declared outside the catalog ${known.file}${known.alias ? " (${known.alias})" : ''}".toString()
                if (!out.contains(note)) out << note
            }
        }
        return out
    }

    @NonCPS
    static List<Map> coveredFixes(List<Map> fixes, List found, List<Map> changes, List<Map> raised, List<String> shared) {
        List changed = []
        for (Map c : changes) changed.addAll(c.componentKeys as List)
        List<Map> out = []
        for (Map fix : fixes) {
            if (changed.contains(fix.key)) continue
            String best = null
            for (def f : found) {
                Map e = f as Map
                if (!shared.contains(e.file) || !(e.componentKeys as List).contains(fix.key)) continue
                boolean onBranch = raised.any { it.file == e.file && ((it.componentKeys as List).contains(fix.key) || (e.alias && it.property == e.alias)) }
                if (onBranch) best = GoldenFix.max(best, e.version as String)
            }
            if (best && !GoldenFix.isUpgrade(best, fix.targetVersion as String)) out << [component: fix.displayName, version: best]
        }
        return out
    }

    @NonCPS
    private static String alreadyRaised(List<Map> covered, String url) {
        if (!covered) return ''
        return "Already raised to ${covered.collect { "${it.component} ${it.version}" }.join(', ')} in the shared pull request ${url}".toString()
    }

    @NonCPS
    private static List<Map> withoutCovered(List<Map> unresolved, List<Map> covered) {
        List names = covered.collect { it.component }
        return unresolved.findAll { !names.contains(it.component) }
    }

    @NonCPS
    private static List<String> refreshedNotes(List<String> files) {
        return files.collect { "${it} refreshed".toString() }
    }

    @NonCPS
    private static List<String> lockFiles(List<String> files) {
        List<String> out = []
        for (String f : files) {
            if (f == 'package-lock.json' || f.endsWith('/package-lock.json')) out << f
        }
        return out
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
    static List<Map> unresolvedFixes(List<Map> fixes, List<Map> changes, List<Map> notes, List<Map> propertyRequests, Map discovery = [:]) {
        Map externals = (discovery?.externals ?: [:]) as Map
        List resolved = []
        for (Map c : changes) resolved.addAll(c.componentKeys as List)
        List<Map> out = []
        for (Map fix : fixes) {
            if (resolved.contains(fix.key)) continue
            List<String> reasons = notes.findAll { it.componentKey == fix.key }.collect { (it.file ? "${it.file}: ${it.reason}" : it.reason).toString() }.unique()
            List<Map> props = propertyRequests.findAll { (it.componentKeys as List).contains(fix.key) }
            if (props && !reasons) reasons.addAll(propertyReasons(props, externals, (discovery?.holders ?: [:]) as Map))
            if (!props && !reasons && externals) {
                reasons << "managed by the version catalog artifact ${(externals.values() as List).unique().join(', ')} outside this repository".toString()
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

    @NonCPS
    static List<String> propertyReasons(List<Map> props, Map externals, Map holders) {
        List<String> out = []
        List<String> plain = []
        for (Map p : props) {
            Map catalog = p.catalog as Map
            if (catalog && !catalog.file && externals[catalog.name]) {
                out << "managed by the version catalog artifact ${externals[catalog.name]} outside this repository".toString()
            } else if (catalog) {
                out << "managed by version catalog ${catalog.file ?: catalog.name} (${p.name}): not found or already newer".toString()
            } else if (holders[p.name]) {
                out << "version ${p.name} is defined in ${holders[p.name]}: not updated automatically".toString()
            } else {
                plain << (p.name as String)
            }
        }
        if (plain) out.add(0, "version property ${plain.join(', ')} not found or already newer".toString())
        return out.unique()
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
    static String pullRequestDescription(String title, List<Map> changes, List<Map> unresolved, List<Map> scanRefs, Map ctx,
                                         List notes = [], boolean lockRefreshed = false) {
        StringBuilder sb = new StringBuilder()
        sb.append("## ${title} - Nexus IQ GoldenFix\n\n")
        sb.append("Raised automatically by the DevSecOps pipeline because the Nexus IQ policy was violated ")
        sb.append("(Critical: ${ctx.critical}, High: ${ctx.high}, Medium: ${ctx.medium}).\n\n")
        if (ctx.buildUrl) sb.append("- Jenkins build: [${ctx.jobName} #${ctx.buildNumber}](${ctx.buildUrl})\n")
        for (Map ref : scanRefs) {
            if (ref.reportUrl) sb.append("- Nexus IQ report (${ref.application}): ${ref.reportUrl}\n")
        }
        sb.append("\n### Applied upgrades\n\n")
        appendDetails(sb, changes, unresolved, notes)
        sb.append("\n> Lock files (${lockRefreshed ? '' : 'package-lock.json, '}yarn.lock, poetry.lock, pinned requirements) are not regenerated. ")
        sb.append("Rebuild and run the tests before merging.\n")
        return sb.toString()
    }

    @NonCPS
    static String pullRequestComment(String project, List<Map> changes, List<Map> unresolved, List notes) {
        StringBuilder sb = new StringBuilder()
        sb.append("### GoldenFix for ${project}\n\n")
        sb.append("The pipeline added ${changes.size()} upgrade(s) for ${project} to this pull request.\n\n")
        appendDetails(sb, changes, unresolved, notes)
        return sb.toString()
    }

    @NonCPS
    private static void appendDetails(StringBuilder sb, List<Map> changes, List<Map> unresolved, List notes) {
        sb.append("| File | Component | From | To | Version chosen by |\n|---|---|---|---|---|\n")
        for (Map c : changes) {
            String what = c.property ? "${c.component} (property `${c.property}`)" : c.component
            sb.append("| `${c.file}` | ${what} | ${c.from} | **${c.to}** | ${GoldenFix.selectionLabel(c) ?: '-'} |\n")
        }
        if (unresolved) {
            sb.append("\n### Not applied automatically\n\n")
            for (Map u : unresolved) sb.append("- ${u.component} ${u.from} -> ${u.to}: ${u.reason}\n")
        }
        if (notes) {
            sb.append("\n### Notes\n\n")
            for (def n : notes) sb.append("- ${n}\n")
        }
    }

    @NonCPS
    private static String normalizeRepoKey(String url) {
        return (url ?: '').trim().toLowerCase().replaceAll(/\.git$/, '').replaceAll(/\/+$/, '')
    }
}
