package com.bbh.remediation

import com.bbh.remediation.model.GoldenFix
import com.cloudbees.groovy.cps.NonCPS

class GoldenFixPullRequestView implements Serializable {

    @NonCPS
    static String describeVersions(List<Map> fixes) {
        List parts = []
        for (Map fix : (fixes ?: [])) {
            parts << "${fix.displayName} ${fix.currentVersion} -> ${fix.targetVersion}".toString()
        }
        return parts.join(', ')
    }

    @NonCPS
    static String alreadyRaised(List<Map> covered, String url) {
        if (!covered) return ''
        return "Already raised to ${covered.collect { "${it.component} ${it.version}" }.join(', ')} in the shared pull request ${url}".toString()
    }

    @NonCPS
    static List<Map> withoutCovered(List<Map> unresolved, List<Map> covered) {
        List names = covered.collect { it.component }
        return unresolved.findAll { !names.contains(it.component) }
    }

    @NonCPS
    static List<String> refreshedNotes(List<String> files) {
        return files.collect { "${it} refreshed".toString() }
    }

    @NonCPS
    static String normalizeRepoKey(String url) {
        return (url ?: '').trim().toLowerCase().replaceAll(/\.git$/, '').replaceAll(/\/+$/, '')
    }

    @NonCPS
    static String commitMessage(String title, List<Map> changes, List<Map> scanRefs) {
        StringBuilder sb = new StringBuilder()
        List components = []
        for (Map c : changes) {
            if (!components.contains(c.component)) components << c.component
        }
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
        GoldenFixPullRequestView.appendDetails(sb, changes, unresolved, notes)
        sb.append("\n> Lock files (${lockRefreshed ? '' : 'package-lock.json, '}yarn.lock, poetry.lock, pinned requirements) are not regenerated. ")
        sb.append("Rebuild and run the tests before merging.\n")
        return sb.toString()
    }

    @NonCPS
    static String pullRequestComment(String project, List<Map> changes, List<Map> unresolved, List notes) {
        StringBuilder sb = new StringBuilder()
        sb.append("### GoldenFix for ${project}\n\n")
        sb.append("The pipeline added ${changes.size()} upgrade(s) for ${project} to this pull request.\n\n")
        GoldenFixPullRequestView.appendDetails(sb, changes, unresolved, notes)
        return sb.toString()
    }

    @NonCPS
    static void appendDetails(StringBuilder sb, List<Map> changes, List<Map> unresolved, List notes) {
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
}
