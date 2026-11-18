package com.bbh.remediation

import com.bbh.core.PipelineState
import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.model.VersionCatalogs
import com.bbh.remediation.port.ManifestUpdater
import com.bbh.remediation.port.PullRequestPublisher
import com.bbh.remediation.port.SourceRepository
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class SharedCatalogRepositoryService implements Serializable {

    private final def script
    private final PipelineState state
    private final SourceRepository repository
    private final PullRequestPublisher publisher
    private final List<ManifestUpdater> updaters

    SharedCatalogRepositoryService(def script, PipelineState state, SourceRepository repository,
                                   PullRequestPublisher publisher, List<ManifestUpdater> updaters) {
        this.script = script
        this.state = state
        this.repository = repository
        this.publisher = publisher
        this.updaters = updaters
    }

    Map resolveVersionCatalogRepo(Map cfg) {
        def raw = cfg?.versionCatalogRepo ?: cfg?.versionCatalogRepository ?: state.cfg?.versionCatalogRepo ?: state.cfg?.versionCatalogRepository ?: state.cfg?.tools?.nexusIq?.versionCatalogRepo ?: state.cfg?.tools?.nexusIq?.versionCatalogRepository ?: cfg?.goldenFix?.versionCatalogRepo ?: cfg?.goldenFix?.versionCatalogRepository
        if (raw instanceof CharSequence) {
            String u = raw.toString().trim()
            return u ? [url: u] : [:]
        }
        if (raw instanceof Map) {
            Map m = raw as Map
            String u = ((m.url ?: m.repositoryUrl ?: m.cloneUrl ?: '') as String).trim()
            return u ? ([:] + m + [url: u]) : [:]
        }
        return [:]
    }

    Map executeExternalCatalogRepo(Map vcRepo, Map scmCfg, List<Map> fixes, List<Map> scanRefs, Map cfg,
                                   String formattedTimestamp, Map ctx) {
        String repoUrl = vcRepo.url as String
        String repoKey = GoldenFixPullRequestView.normalizeRepoKey(repoUrl)
        String targetBranch = (vcRepo.branch ?: vcRepo.targetBranch ?: 'main') as String
        String credId = (vcRepo.credentialsId ?: scmCfg.credentialsId ?: '') as String
        Map vcScmCfg = [
                url          : repoUrl,
                cloneUrl     : vcRepo.cloneUrl ?: repoUrl,
                credentialsId: credId,
                authType     : vcRepo.authType ?: scmCfg.authType,
                targetBranch : targetBranch
        ]
        Map prsByRepo = (state.goldenFixPullRequestsByRepo ?: [:]) as Map
        Map vcPr = (prsByRepo[repoKey] ?: [:]) as Map
        String title = (vcPr.title ?: "GoldenFix-${formattedTimestamp}") as String
        script.echo "[GOLDENFIX] Using shared version catalog repository: ${repoUrl} (branch: ${targetBranch})"
        String extDir = (vcPr.extDir ?: prepareExternalCatalog(repoUrl, targetBranch, credId)) as String
        if (!extDir) {
            return [handledKeys: [] as Set, changes: [], notes: ["Could not prepare external version catalog repository ${repoUrl}".toString()]]
        }

        List<String> excluded = (cfg.excludeDirs ?: GoldenFixService.defaultExcludedDirs()) as List<String>
        List<String> explicitPaths = CatalogDiscovery.listed(vcRepo.paths ?: vcRepo.path)
        List<String> extFiles = []
        Map extTexts = [:]
        for (String p : explicitPaths) {
            String rel = p.startsWith('/') ? p.substring(1) : p
            String txt = readQuiet(extDir, rel)
            if (txt) {
                extFiles << rel
                extTexts[rel] = txt
            }
        }
        List<String> searchPatterns = ['*.versions.toml', 'Directory.Packages.props', 'Directory.Build.props', '*.props', 'pom.xml', '*bom*.xml', 'gradle.properties', 'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'package.json', 'requirements.txt', 'pubspec.yaml']
        try {
            for (String found : repository.findFiles(extDir, searchPatterns, excluded)) {
                if (!extFiles.contains(found)) {
                    String txt = readQuiet(extDir, found)
                    if (txt) {
                        extFiles << found
                        extTexts[found] = txt
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (extFiles.isEmpty()) {
            for (String fallback : ['gradle/libs.versions.toml', 'libs.versions.toml', 'Directory.Packages.props', 'Directory.Build.props', 'pom.xml', 'gradle.properties']) {
                String txt = readQuiet(extDir, fallback)
                if (txt) {
                    extFiles << fallback
                    extTexts[fallback] = txt
                }
            }
        }
        if (extFiles.isEmpty()) {
            return [handledKeys: [] as Set, changes: [], notes: ["No version catalog files found in ${repoUrl}".toString()]]
        }

        Map extRun = applyExternalCatalogFixes(extDir, extFiles, extTexts, fixes, excluded)
        List<Map> covered = vcPr.url ? GoldenFixService.coveredFixes(fixes, extRun.found as List, extRun.changes as List<Map>, (vcPr.raised ?: []) as List<Map>, extFiles) : []
        String raisedNote = GoldenFixPullRequestView.alreadyRaised(covered, vcPr.url as String)
        Set<String> handledKeys = [] as Set
        for (Map c : (extRun.changes as List<Map>)) {
            for (def k : ((c.componentKeys ?: []) as List)) handledKeys << (k as String)
        }
        for (Map fix : fixes) {
            if (covered.any { it.component == fix.displayName }) handledKeys << (fix.key as String)
        }

        List<String> notes = [] + (extRun.notes as List<String>)
        if (covered && raisedNote) notes << raisedNote

        String commitId = extRun.files
                ? repository.commit(extDir, extRun.files as List<String>, GoldenFixPullRequestView.commitMessage(title, extRun.changes as List, scanRefs), [name: cfg.commitAuthorName, email: cfg.commitAuthorEmail])
                : ''
        if (!commitId && covered) {
            return [
                    handledKeys : handledKeys,
                    changes     : extRun.changes,
                    notes       : notes,
                    status      : 'PR_UPDATED',
                    message     : raisedNote,
                    prUrl       : vcPr.url,
                    prId        : vcPr.id,
                    prTitle     : title,
                    branch      : title,
                    targetBranch: targetBranch,
                    verified    : true
            ]
        }
        if (!commitId) {
            return [handledKeys: handledKeys, changes: [], notes: notes]
        }

        Map repoInfo = publisher.repositoryInfo(vcScmCfg)
        repository.push(extDir, title, [
                url          : vcScmCfg.cloneUrl ?: repoInfo.cloneUrl,
                credentialsId: vcScmCfg.credentialsId,
                authType     : vcScmCfg.authType,
                cloud        : repoInfo.cloud
        ])

        if (vcPr.url) {
            vcPr.raised = ((vcPr.raised ?: []) as List) + (extRun.changes as List)
            vcPr.extDir = extDir
            addCommentSafe(vcScmCfg, vcPr, GoldenFixPullRequestView.pullRequestComment(state.currentProjectName as String, extRun.changes as List, [], notes))
            return [
                    handledKeys : handledKeys,
                    changes     : extRun.changes,
                    notes       : notes,
                    status      : 'PR_UPDATED',
                    message     : "GoldenFix pull request ${title} updated in shared version catalog repository with ${(extRun.changes as List).size()} change(s): ${vcPr.url}".toString(),
                    prUrl       : vcPr.url,
                    prId        : vcPr.id,
                    prTitle     : title,
                    branch      : title,
                    targetBranch: targetBranch,
                    verified    : true
            ]
        }

        Map created = publisher.createPullRequest(vcScmCfg, [
                title       : title,
                description : GoldenFixPullRequestView.pullRequestDescription(title, extRun.changes as List, [], scanRefs, ctx, notes, false),
                sourceBranch: title,
                targetBranch: targetBranch
        ])
        Map prEntry = [title: title, branch: title, targetBranch: targetBranch, url: created.url, id: created.id, raised: extRun.changes, extDir: extDir]
        state.goldenFixPullRequest = prEntry
        if (state.goldenFixPullRequestsByRepo == null) state.goldenFixPullRequestsByRepo = [:]
        state.goldenFixPullRequestsByRepo[repoKey] = prEntry
        return [
                handledKeys : handledKeys,
                changes     : extRun.changes,
                notes       : notes,
                status      : 'PR_CREATED',
                message     : "GoldenFix pull request ${title} raised in shared version catalog repository: ${created.url}".toString(),
                prUrl       : created.url,
                prId        : created.id,
                prTitle     : title,
                branch      : title,
                targetBranch: targetBranch,
                verified    : true
        ]
    }

    private Map applyExternalCatalogFixes(String extDir, List<String> extFiles, Map extTexts, List<Map> fixes, List<String> excluded) {
        Map discovered = CatalogDiscovery.discover(extFiles, extFiles, extTexts, extFiles)
        Map entries = [:] + (discovered.entries as Map)
        for (String f : extFiles) {
            if (!entries.containsKey(f)) {
                String low = BuildUtils.fileNameOf(f).toLowerCase()
                String kind = low.endsWith('.toml') ? 'toml'
                        : (low.endsWith('.props') ? 'nuget'
                        : (low.endsWith('.xml') ? 'maven' : 'gradle'))
                entries[f] = [path: f, kind: kind, names: kind == 'toml' ? [VersionCatalogs.baseName(f)] : [], consumers: []]
            }
        }
        List<ManifestUpdater> active = []
        for (ManifestUpdater updater : updaters) {
            if (GoldenFixService.fixesFor(fixes, updater.ecosystem())) {
                updater.useCatalogs(entries)
                active << updater
            }
        }
        Map extRun = [changes: [], notes: [], requests: [], references: [], found: [], files: []]
        for (ManifestUpdater updater : active) {
            List<Map> ecosystemFixes = GoldenFixService.fixesFor(fixes, updater.ecosystem())
            List<String> ownExtFiles = filesOf(updater, extFiles)
            if (!ownExtFiles) continue
            List properties = []
            for (String f : ownExtFiles) {
                String text = read(extDir, extTexts, f)
                Map r = updater.updateDeclarations(f, text, ecosystemFixes)
                collect(extRun, r)
                properties.addAll((r.properties ?: []) as List)
                write(extDir, extTexts, f, text, r.content as String, extRun)
            }
            Set directKeys = [] as Set
            for (Map c : (extRun.changes as List<Map>)) directKeys.addAll((c.componentKeys ?: []) as List)
            for (Map p : (properties as List<Map>)) directKeys.addAll((p.componentKeys ?: []) as List)
            List<Map> remainingEco = ecosystemFixes.findAll { !directKeys.contains(it.key) }
            if (remainingEco) {
                String ws = (script.env?.WORKSPACE ?: '') as String
                String sd = (state.cfg?.sourceDir ?: '').toString().trim()
                String wsRoot = (ws && sd && sd != '.') ? "${ws}/${sd}" : ws
                try {
                    for (String lf : repository.findFiles(wsRoot, updater.filePatterns(), excluded)) {
                        String ltext = readQuiet(wsRoot, lf)
                        if (ltext) {
                            Map lr = updater.updateDeclarations(lf, ltext, remainingEco)
                            properties.addAll((lr.properties ?: []) as List)
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (properties) {
                List<Map> merged = GoldenFix.mergePropertyRequests(properties)
                (extRun.requests as List).addAll(merged)
                (extRun.references as List).addAll(properties)
                for (String f : ownExtFiles) {
                    String text = read(extDir, extTexts, f)
                    Map r = updater.updateProperties(f, text, merged)
                    collect(extRun, r)
                    write(extDir, extTexts, f, text, r.content as String, extRun)
                }
            }
        }
        extRun.changes = GoldenFixService.collapseChanges(extRun.changes as List<Map>)
        for (ManifestUpdater updater : active) {
            GoldenFixService.labelProperties(extRun.changes as List<Map>, extRun.references as List<Map>, filesOf(updater, extFiles), [:])
        }
        for (Map c : (extRun.changes as List<Map>)) {
            if (c.property && !c.component) {
                List names = []
                for (Map ref : (extRun.references as List<Map>)) {
                    if (ref.name == c.property) GoldenFix.addAllUnique(names, (ref.components ?: []) as List)
                }
                if (names) c.component = names.join(', ')
            }
            String what = c.property ? "property ${c.property} (${c.component})" : c.component
            script.echo "[GOLDENFIX]   [catalog-repo] ${c.file}: ${what} ${c.from} -> ${c.to}"
        }
        return [files: extRun.files, changes: extRun.changes, found: extRun.found, notes: []]
    }

    private List<String> filesOf(ManifestUpdater updater, List<String> files) {
        List<String> own = []
        for (String f : files) {
            if (updater.supports(f)) own << f
        }
        return own
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

    private String readQuiet(String dir, String file) {
        try {
            return repository.readText(dir, file) ?: ''
        } catch (Throwable ignored) {
            return ''
        }
    }

    private String prepareExternalCatalog(String url, String branch, String credentialsId) {
        try {
            return repository.prepareExternalRepository(url, branch, credentialsId)
        } catch (Throwable t) {
            script.echo "[GOLDENFIX] Warning: could not clone external version catalog repository ${url}: ${t.message}"
            return null
        }
    }

    private void addCommentSafe(Map scmCfg, Map pr, String text) {
        try {
            publisher.addComment(scmCfg, pr, text)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[GOLDENFIX] The pull request comment could not be added: ${e.message ?: e.getClass().getSimpleName()}"
        }
    }

    @NonCPS
    private static void collect(Map run, Map r) {
        (run.changes as List).addAll((r.changes ?: []) as List)
        (run.notes as List).addAll((r.notes ?: []) as List)
        (run.found as List).addAll((r.found ?: []) as List)
    }
}
