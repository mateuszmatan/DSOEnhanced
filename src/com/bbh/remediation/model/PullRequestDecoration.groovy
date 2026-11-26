package com.bbh.remediation.model

import com.cloudbees.groovy.cps.NonCPS

class PullRequestDecoration implements Serializable {

    @NonCPS
    static Map create(Map facts) {
        Map pullRequest = (facts.pullRequest ?: [:]) as Map
        Map added = (facts.added ?: [:]) as Map
        List<Map> findings = facts.kind == 'DAST' ? dastFindings((facts.dast ?: []) as List<Map>)
                : staticFindings((facts.sast ?: []) as List<Map>, (facts.dependencies ?: []) as List<Map>, (facts.sonar ?: []) as List<Map>)
        List<Map> kept = []
        for (int i = 0; i < findings.size() && i < maxFindings(); i++) kept << findings[i]
        return [service        : text(facts.service),
                kind           : text(facts.kind),
                repositoryUrl  : repositoryUrl((facts.scm ?: [:]) as Map),
                pullRequestId  : text(pullRequest.id),
                pullRequestUrl : text(pullRequest.url),
                title          : cut(text(pullRequest.title), 500),
                sourceBranch   : text(pullRequest.branch),
                targetBranch   : text(pullRequest.targetBranch),
                created        : facts.created == true,
                lineComments   : count(added.lineComments),
                generalComments: count(added.generalComments),
                limited        : added.limited == true,
                maxComments    : count(facts.maxComments),
                findings       : kept,
                findingsTotal  : findings.size(),
                summary        : cut(text(facts.summary), 20000)]
    }

    @NonCPS
    static int maxFindings() {
        return 200
    }

    @NonCPS
    static List<Map> staticFindings(List<Map> sast, List<Map> dependencies, List<Map> sonar) {
        List<Map> out = []
        for (Map f : sast) out << finding(f.tool ?: 'AppScan SAST', f.severity, f.file, f.line, f.type, f.description, f.remediation)
        for (Map d : dependencies) out << finding('Nexus IQ', '', d.file, d.line, joined(d.component, ' ', d.from), dependencyNote(d), dependencyFix(d))
        for (Map s : sonar) out << finding('SonarQube', s.severity, s.file, s.line, joined(s.rule, ': ', s.message), s.message, s.remediation)
        return out
    }

    @NonCPS
    static List<Map> dastFindings(List<Map> dast) {
        List<Map> out = []
        for (Map d : dast) out << finding('AppScan DAST', d.severity, '', null, joined(d.type, ' at ', d.endpoint), d.description, d.remediation)
        return out
    }

    @NonCPS
    static String dependencyFix(Map d) {
        String to = text(d.to).trim()
        if (!to) return 'No newer version is offered'
        String property = text(d.property).trim()
        return d.applied && property ? "Upgrade to ${to} through ${property}".toString() : "Upgrade to ${to}".toString()
    }

    @NonCPS
    static String dependencyNote(Map d) {
        if (!d.applied) return "Not changed automatically: ${text(d.reason).trim() ?: 'left for a developer'}.".toString()
        String pullRequest = text(d.prUrl).trim()
        return pullRequest ? "The GoldenFix pull request ${pullRequest} makes this change.".toString() : ''
    }

    @NonCPS
    static Map finding(def tool, def severity, def file, def line, def title, def description, def remediation) {
        return [tool       : text(tool),
                severity   : text(severity).trim(),
                file       : text(file).trim(),
                line       : lineOf(line),
                title      : cut(text(title).trim(), 500),
                description: cut(text(description).trim(), 4000),
                remediation: cut(text(remediation).trim(), 4000)]
    }

    @NonCPS
    static String joined(def first, String separator, def second) {
        String head = text(first).trim()
        String tail = text(second).trim()
        return head && tail ? head + separator + tail : (head ?: tail)
    }

    @NonCPS
    static String repositoryUrl(Map scm) {
        String raw = text(scm.url).trim() ?: text(scm.cloneUrl).trim()
        int scheme = raw.indexOf('://')
        int start = scheme < 0 ? 0 : scheme + 3
        int path = raw.indexOf('/', start)
        String authority = path < 0 ? raw.substring(start) : raw.substring(start, path)
        int at = authority.lastIndexOf('@')
        String plain = at < 0 ? raw : raw.substring(0, start) + raw.substring(start + at + 1)
        return plain.replaceFirst(/[?#].*$/, '')
    }

    @NonCPS
    static String cut(String value, int limit) {
        return value.length() > limit ? value.substring(0, limit - 3) + '...' : value
    }

    @NonCPS
    static Integer lineOf(def value) {
        String raw = text(value).trim()
        if (!(raw ==~ /\d{1,9}/)) return null
        int line = raw.toInteger()
        return line > 0 ? line : null
    }

    @NonCPS
    static int count(def value) {
        return value instanceof Number ? (value as Number).intValue() : 0
    }

    @NonCPS
    static String text(def value) {
        return value == null ? '' : value.toString()
    }
}
