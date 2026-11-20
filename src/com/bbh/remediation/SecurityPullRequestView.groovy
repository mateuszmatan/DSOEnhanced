package com.bbh.remediation

import com.cloudbees.groovy.cps.NonCPS

class SecurityPullRequestView implements Serializable {

    @NonCPS
    static String sastLineComment(Map f) {
        return "**[${f.tool ?: 'AppScan SAST'}] ${f.severity}: ${f.type}**\n\n${f.description}\n\n${recommendedFix(f)}".toString()
    }

    @NonCPS
    static String recommendedFix(Map f) {
        String fix = ((f.remediation ?: '') as String).trim()
        if (fix) return "**Recommended fix (AppScan):**\n${fix}".toString()
        return '**Recommended fix (AppScan):** AppScan returned no fix recommendation for this issue type - open the issue in AppScan for the advisory.'
    }

    @NonCPS
    static String sonarLineComment(Map s) {
        return "**[SonarQube] ${s.severity}: ${s.rule}**\n\n${s.message}\n\n**Suggested remediation:** ${s.remediation}".toString()
    }

    @NonCPS
    static String dependencyComment(Map d) {
        String head = "**[Nexus IQ] ${d.component} ${d.from}**".toString()
        if (d.applied) {
            return "${head}\n\n**Recommended fix (Nexus IQ):** upgrade to `${d.to}`${d.property ? ' through `' + d.property + '`' : ''}.${d.prUrl ? ' The GoldenFix pull request ' + d.prUrl + ' makes this change.' : ''}".toString()
        }
        return "${head}\n\n**Recommended fix (Nexus IQ):** ${d.to ? 'upgrade to `' + d.to + '`' : 'no newer version is offered'}. Not changed automatically: ${d.reason ?: 'left for a developer'}.".toString()
    }

    @NonCPS
    static String unchangedFileComment(String file, List<Map> entries) {
        StringBuilder sb = new StringBuilder(file
                ? "**[DevSecOps] Findings in `${file}`** (this pull request does not change the file)\n".toString()
                : '**[DevSecOps] Findings without a file of this repository**\n')
        for (Map e : (entries ?: [])) {
            sb.append("\n---\n")
            if (file) sb.append(e.line ? "`${file}:${e.line}`\n\n".toString() : "`${file}`\n\n".toString())
            sb.append(e.text as String).append('\n')
        }
        return sb.toString()
    }

    @NonCPS
    static String staticSummary(String project, List<Map> gfChanges, List<Map> sastFindings, List<Map> sonarIssues) {
        StringBuilder sb = new StringBuilder("**DevSecOps static security results - ${project}**\n\n")
        sb.append("- Nexus IQ GoldenFix: ${gfChanges ? gfChanges.size() + ' dependency upgrade(s)' : 'no dependency upgrade'}\n")
        sb.append("- AppScan SAST: ${sastFindings.size()} finding(s) with the fix AppScan recommends\n")
        sb.append("- SonarQube: ${sonarIssues.size()} open issue(s) on the analysed branch\n")
        return sb.toString()
    }

    @NonCPS
    static String generateStaticReport(List<Map> gfChanges, List<Map> sastFindings, List<Map> sonarIssues) {
        StringBuilder sb = new StringBuilder("# DevSecOps Static Analysis & Quality Gate Report\n\n")
        sb.append("This Pull Request consolidates static security and code quality remediations from **Nexus IQ GoldenFix**, **AppScan SAST**, and **SonarQube**.\n\n")
        sb.append("### 1. Nexus IQ GoldenFix Upgrades\n")
        if (gfChanges) {
            for (Map c : gfChanges) {
                sb.append("- `${c.coordinate ?: c.component}`: upgraded `${c.from}` -> `${c.to}` in `${c.file}`\n")
            }
        } else {
            sb.append("- No direct dependency version upgrades applied.\n")
        }
        sb.append("\n### 2. HCL AppScan SAST Findings\n")
        if (sastFindings) {
            for (Map f : sastFindings) {
                sb.append("- **[${f.severity}]** `${f.file}:${f.line}` - ${f.type}: ${f.remediation}\n")
            }
        } else {
            sb.append("- 0 static security vulnerabilities found.\n")
        }
        sb.append("\n### 3. SonarQube Code Quality Issues\n")
        if (sonarIssues) {
            for (Map s : sonarIssues) {
                sb.append("- **[${s.severity}]** `${s.file}:${s.line}` - ${s.message}\n")
            }
        } else {
            sb.append("- 0 blocking quality gate issues found.\n")
        }
        return sb.toString()
    }

    @NonCPS
    static String generateDastReport(List<Map> dastFindings, Map counts) {
        StringBuilder sb = new StringBuilder("# HCL AppScan DAST Dynamic Security Report\n\n")
        sb.append("The enhanced pipeline executed Dynamic Application Security Tests (DAST) on the running service.\n\n")
        sb.append("### Summary of Vulnerabilities\n")
        sb.append("- **Critical:** ").append(counts.critical ?: 0).append("\n")
        sb.append("- **High:** ").append(counts.high ?: 0).append("\n")
        sb.append("- **Medium:** ").append(counts.medium ?: 0).append("\n")
        sb.append("- **Low:** ").append(counts.low ?: 0).append("\n\n")
        sb.append("### Affected Endpoints & Remediation Guidance\n")
        for (Map d : (dastFindings ?: [])) {
            sb.append("- **[${d.severity}]** `${d.endpoint}` - ${d.type}\n")
            sb.append("  - *Remediation:* ${d.remediation}\n")
        }
        return sb.toString()
    }
}
