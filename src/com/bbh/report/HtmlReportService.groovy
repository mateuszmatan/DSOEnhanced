package com.bbh.report

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

import java.text.SimpleDateFormat

class HtmlReportService implements Serializable {

    protected final int TEST_JOBS_INLINE_LIMIT = 10

    protected final String CELL       = 'text-align:center;padding:6px 10px;'
    protected final String CELL_NOWRAP = 'text-align:center;padding:6px 10px;white-space:nowrap;'
    protected final String LINK_COLOR = '#0369a1'
    protected final String PDF_COLOR  = '#b91c1c'
    protected final String PR_COLOR   = '#15803d'
    protected final String MUTED      = '#9ca3af'
    protected final String OK_BG      = '#f0fdf4'
    protected final String OK_COLOR   = '#166534'
    protected final String BAD_BG     = '#fee2e2'
    protected final String BAD_COLOR  = '#991b1b'

    protected final String NIQ_STAGE  = 'Dependencies scan (Nexus IQ)'
    protected final String DAST_STAGE = 'DAST - Dynamic Application Security Tests - HCL AppScan'
    protected final List TEST_STAGES  = ['Smoke tests', 'Regression tests (>60% user stories coverage)', 'Performance tests']

    protected final def          script
    protected final PipelineState state
    protected final OsHelper     os
    protected final PolicyEngine policy

    protected final String variant
    protected final List runStatuses
    protected final List sonarBadges
    protected final def appscanScnrs
    protected final def stageOrder
    protected final def secStageNames
    protected final def phaseGroups
    protected final def reportsToParse

    HtmlReportService(def script, PipelineState state, OsHelper os, PolicyEngine policy, String variant = 'full') {
        this.script = script
        this.state  = state
        this.os     = os
        this.policy = policy
        this.variant = variant ?: 'full'
        Map stageLayout = layout(this.variant)
        this.appscanScnrs = stageLayout.appscanScnrs
        this.stageOrder = stageLayout.stageOrder
        this.secStageNames = stageLayout.secStageNames
        this.phaseGroups = stageLayout.phaseGroups
        this.reportsToParse = stageLayout.reportsToParse
        this.runStatuses = runStatuses()
        this.sonarBadges = sonarBadges()
    }

    void generate() {
        try {
            generateFullReport()
        } catch (Throwable t) {
            script.echo "[REPORT] Full report could not be built (${t.getClass().getSimpleName()}: ${t.message}) - writing the minimal report"
            writeMinimalReport(t)
        }
    }

    protected void writeMinimalReport(Throwable cause) {
        try {
            StringBuilder rows = new StringBuilder()
            (state.stageResults ?: [:]).each { name, status ->
                String reason = (state.stageErrors ?: [:])?.get(name)?.toString() ?: ''
                rows.append("<tr><td>${esc(name as String)}</td><td>${esc(status as String)}</td><td>${esc(reason)}</td></tr>")
            }
            String html = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><title>Pipeline Report</title></head>" +
                    "<body style='font-family:sans-serif;padding:24px;background:#f1f5f9;'>" +
                    "<h2>Pipeline Report</h2>" +
                    "<p style='color:#92400e;'>The full report could not be rendered: ${esc(cause?.message ?: '')}. The stage results are listed below.</p>" +
                    "<table border='1' cellpadding='6' cellspacing='0'><thead><tr><th>Stage</th><th>Result</th><th>Reason</th></tr></thead>" +
                    "<tbody>${rows.toString()}</tbody></table></body></html>"
            script.writeFile file: "${script.env.WORKSPACE}/report/pipeline-report.html", text: html
            script.echo "[REPORT] Minimal pipeline report written to pipeline-report.html"
        } catch (Throwable t) {
            script.echo "[REPORT] Even the minimal report could not be written: ${t.message}"
        }
    }

    protected void generateFullReport() {
        warnAboutEmptyState()
        def buildResult  = script.currentBuild.result ?: (script.currentBuild.currentResult ?: 'IN_PROGRESS')
        def now          = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
        def buildUrl     = ((script.env.BUILD_URL ?: '').replaceAll('/+$', '')) + '/'
        def jobName      = script.env.JOB_NAME     ?: 'unknown'
        def buildNumber  = script.env.BUILD_NUMBER ?: '?'
        def artifactBase = buildUrl + 'artifact/'

        def vulnCounts = new HashMap(state.vulnCounts)
        parseAllAppScanReports(vulnCounts)
        if (state.nexusIqResults) {
            vulnCounts['niq'] = getNexusIqResults()
        }
        def stageLinks  = buildStageLinks(buildUrl, artifactBase, vulnCounts)
        def projectKeys = state.projectsAllCfg?.keySet()?.toList() ?: []

        def html = buildHtml(
                buildResult, now, jobName, buildNumber, buildUrl,
                new HashMap(state.stageResults),
                new HashMap(state.stageErrors),
                new HashMap(state.stageTimes ?: [:]),
                vulnCounts,
                new HashMap(state.policyLimits),
                new HashMap(state.coverage),
                buildStageDetails(state.stageResults, vulnCounts),
                stageLinks,
                new HashMap(state.sonarResults ?: [:]),
                new HashMap(state.nexusIqResults ?: [:]),
                new HashMap(state.remoteTestResults ?: [:]),
                (state.cfg?.tools?.sonar?.projectKey  ?: '') as String,
                (state.cfg?.tools?.sonar?.badgeToken  ?: '') as String,
                new HashMap(state.projectsVulnCounts     ?: [:]),
                new HashMap(state.projectsNexusIqResults ?: [:]),
                new HashMap(state.projectsScanResults    ?: [:]),
                new HashMap(state.projectsSonarResults   ?: [:]),
                projectKeys,
                new HashMap(state.projectsAllCfg ?: [:]),
                new com.bbh.core.ReleaseGate(script, state).evaluate(),
                new HashMap(state.projectsRemoteTestResults ?: [:]),
                new HashMap(state.projectsGoldenFix ?: [:]),
                new HashMap(state.projectsCoverage ?: [:])
        )
        script.sh "mkdir -p ${script.env.WORKSPACE}/report"
        script.writeFile file: "${script.env.WORKSPACE}/report/pipeline-report.html", text: html
        script.echo "[REPORT] Pipeline report written to pipeline-report.html"
    }

    protected void warnAboutEmptyState() {
        if (state.stageResults) return
        script.echo "[REPORT] WARNING: no stage result was recorded, so every stage will be reported as SKIP."
        script.echo "[REPORT] WARNING: if the stages did run, the report is reading a different devSecOpsApi " +
                "instance than they wrote to - every entry point must hand its own devSecOpsApi to devSecOpsSteps."
    }

    protected def getNexusIqResults() {
        return [
                critical: (state.nexusIqResults.get('critical') ?: 0) as int,
                high:     (state.nexusIqResults.get('high')     ?: 0) as int,
                medium:   (state.nexusIqResults.get('medium')   ?: 0) as int,
                low: 0
        ]
    }

    protected void parseAllAppScanReports(Map vulnCountsLocal) {
        reportsToParse.each { k ->
            def path   = policy.reportPath(k)
            def parsed = parseAppScanReport(path)
            if (parsed?.parsed) {
                def counts = [
                        critical: (parsed.critical ?: 0) as int,
                        high:     (parsed.high     ?: 0) as int,
                        medium:   (parsed.medium   ?: 0) as int,
                        low:      (parsed.low      ?: 0) as int
                ]
                state.vulnCounts[k] = counts
                vulnCountsLocal[k]  = counts
            }
        }
    }

    protected Map parseAppScanReport(String path) {
        if (!path || !script.fileExists(path)) return [parsed: false]
        try {
            return parseHclAppScanHtml(script.readFile(path))
        } catch (Throwable t) {
            script.echo "[REPORT] Failed to parse ${path}: ${t.message}"
            return [parsed: false]
        }
    }

    protected Map buildStageLinks(String buildUrl, String artifactBase, Map vulnCounts) {
        def ran   = ['PASS', 'FAIL', 'WARN'] as Set
        def links = [:]
        def sast  = policy.scannerStageName('sast')
        def sca   = policy.scannerStageName('sca')
        def dast  = policy.scannerStageName('dast')

        if (ran.contains(state.stageResults[sast])) {
            links[sast] = scanReportLinks(artifactBase, 'sast_file', '', 'sast_hcl', 'SAST', 'appscan-report.html')
        }
        if (ran.contains(state.stageResults[sca])) {
            def sonarUrl = state.sonarResults.url
            if (sonarUrl) links[sca] = [[url: sonarUrl, label: 'SonarQube']]
        }
        if (ran.contains(state.stageResults[dast])) {
            links[dast] = scanReportLinks(artifactBase, 'dast_file', 'dast_pdf', 'dast_hcl', 'DAST', 'appscan-dast-report.html')
        }
        if (ran.contains(state.stageResults[NIQ_STAGE])) {
            def niqUrl = state.nexusIqResults.url
            if (niqUrl) links[NIQ_STAGE] = [[url: niqUrl, label: 'Nexus IQ']]
        }
        TEST_STAGES.each { sn ->
            def jobs = state.remoteTestResults.get(sn)
            if (!(jobs instanceof List)) return
            def l = []
            (jobs as List).eachWithIndex { job, ji ->
                def jUrl = job?.get('url')?.toString()?.trim() ?: ''
                if (jUrl) l << [url: jUrl, label: job?.get('name')?.toString() ?: "Job ${ji + 1}"]
            }
            if (l) links[sn] = l
        }
        return links
    }

    protected List scanReportLinks(String artifactBase, String fileKey, String pdfKey, String consoleKey, String label, String fallbackFile) {
        def l = []
        state.projectsScanResults.each { pn, pr ->
            def file = pr?.get(fileKey)?.toString() ?: ''
            if (file) l << [url: "${artifactBase}${file}", label: "${label}: ${pn}"]
            def pdf = pdfKey ? (pr?.get(pdfKey)?.toString() ?: '') : ''
            if (pdf) l << [url: "${artifactBase}${pdf}", label: "${label} PDF: ${pn}"]
            def console = consoleKey ? (pr?.get(consoleKey)?.toString() ?: '') : ''
            if (console) l << [url: console, label: "HCL AppScan: ${pn}"]
        }
        if (!l) l << [url: "${artifactBase}${fallbackFile}", label: "${label} Report"]
        return l
    }

    protected Map buildStageDetails(Map stageResults, Map vulnCounts) {
        def details = [:]
        def stageMap = [
                sast: policy.scannerStageName('sast'),
                dast: policy.scannerStageName('dast'),
                niq:  NIQ_STAGE
        ]
        stageMap.each { key, stageName ->
            def status = stageResults[stageName] ?: 'SKIP'
            if (status != 'SKIP') {
                def counts = vulnCounts[key] ?: [critical: 0, high: 0, medium: 0, low: 0]
                details[stageName] = [
                        critical: (counts.critical ?: 0) as int,
                        high:     (counts.high     ?: 0) as int,
                        medium:   (counts.medium   ?: 0) as int,
                        low:      (counts.low      ?: 0) as int
                ]
            }
        }
        return details
    }

    @NonCPS
    protected Map parseHclAppScanHtml(String html) {
        if (!html) return [parsed: false]
        def n = maybeUnescape(html)
        n = n.replaceAll(/(?is)<script[^>]*>.*?<\/script>/, ' ').replaceAll(/(?is)<style[^>]*>.*?<\/style>/, ' ')
        def counts = [critical: null, high: null, medium: null, low: null, total: null]
        def tbl = extractSummaryTable(n)
        if (tbl) {
            def sev = extractSevCountsFromTable(tbl)
            counts.critical = sev.critical; counts.high = sev.high; counts.medium = sev.medium; counts.low = sev.low
            counts.total = extractTotalFromTable(tbl)
        }
        if (counts.total == null) counts.total = extractTotalFromExec(n)
        def fb = countSevFromHeaders(n)
        ['critical', 'high', 'medium', 'low'].each { k -> if (counts[k] == null) counts[k] = (fb[k] ?: 0) }
        if (counts.total == null) counts.total = (counts.critical as int) + (counts.high as int) + (counts.medium as int) + (counts.low as int)
        counts.parsed = true
        return counts
    }

    @NonCPS protected String maybeUnescape(String s) {
        if (!s || !(s.contains('&lt;') && s.contains('&gt;'))) return s
        return s.replace('&lt;','<').replace('&gt;','>').replace('&quot;','"').replace('&#39;',"'").replace('&nbsp;',' ').replace('&amp;','&')
    }
    @NonCPS protected String extractSummaryTable(String html) {
        def m = (html =~ /(?is)<h3\b[^>]*>\s*Summary\s+of\s+security\s+issues\s*<\/h3>/); if (!m.find()) return null
        def t = (html.substring(m.end()) =~ /(?is)<table\b[^>]*>(.*?)<\/table>/); return t.find() ? "<table>${t.group(1)}</table>" : null
    }
    @NonCPS protected Map extractSevCountsFromTable(String tbl) {
        def out = [critical: null, high: null, medium: null, low: null]
        def m = (tbl =~ /(?is)<tr\b[^>]*>\s*<td\b[^>]*>\s*(Critical|High|Medium|Low)\s+severity\s+issues\s*:\s*<\/td>\s*<td\b[^>]*>\s*(\d+)\s*<\/td>\s*<\/tr>/)
        while (m.find()) { def sev = m.group(1).toLowerCase(); def val = m.group(2) as int; if (out.containsKey(sev)) out[sev] = val }
        return out
    }
    @NonCPS protected Integer extractTotalFromTable(String tbl) {
        def m = (tbl =~ /(?is)<td\b[^>]*>\s*Total\s+security\s+issues\s*:\s*<\/td>\s*<td\b[^>]*>\s*(\d+)\s*<\/td>/)
        return m.find() ? (m.group(1) as int) : null
    }
    @NonCPS protected Integer extractTotalFromExec(String html) {
        def m = (html =~ /(?is)Total\s+security\s+issues\s*:\s*<span\b[^>]*class\s*=\s*["']count["'][^>]*>\s*(\d+)\s*<\/span>/)
        return m.find() ? (m.group(1) as int) : null
    }
    @NonCPS protected Map countSevFromHeaders(String html) {
        def out = [critical: 0, high: 0, medium: 0, low: 0]
        def m = (html =~ /(?is)<div\b[^>]*class\s*=\s*["']name["'][^>]*>\s*Severity:\s*<\/div>\s*<div\b[^>]*class\s*=\s*["']value["'][^>]*>.*?<span\b[^>]*>\s*(Critical|High|Medium|Low)\s*<\/span>/)
        while (m.find()) { def sev = m.group(1).toLowerCase(); if (out.containsKey(sev)) out[sev] = (out[sev] as int) + 1 }
        return out
    }

    @NonCPS protected String esc(String s) {
        if (!s) return ''
        return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;').replace("'",'&#39;')
    }
    @NonCPS protected String td(String content, String style) {
        return "<td style='${style}'>${content}</td>"
    }
    @NonCPS protected String centerTd(String content, String extraStyle = '') {
        return td(content, CELL + extraStyle)
    }
    @NonCPS protected String dashTd(String extraStyle = '') {
        return td('-', "text-align:center;padding:6px 10px;color:${MUTED};${extraStyle}")
    }
    @NonCPS protected String link(String url, String label, String color = LINK_COLOR, String extraStyle = 'font-weight:600;') {
        return "<a href='${esc(url)}' target='_blank' style='font-size:0.78rem;color:${color};${extraStyle}'>${label}</a>"
    }
    @NonCPS protected String mutedDash() {
        return "<span style='font-size:0.78rem;color:${MUTED};'>-</span>"
    }
    @NonCPS protected String cardRaw(String titleHtml, String body, String extraStyle) {
        return "<div class='card' style='${extraStyle}'><h2>${titleHtml}</h2>${body}</div>"
    }
    @NonCPS protected String card(String title, String body) {
        return cardRaw(esc(title), body, 'margin-top:16px;')
    }
    @NonCPS protected String banner(String text) {
        return "<div style='background:#dc2626;color:#fff;font-weight:700;text-align:center;padding:8px 12px;border-radius:3px;margin-bottom:8px;font-size:0.85rem;letter-spacing:0.5px;'>${text}</div>"
    }
    @NonCPS protected String badge(String status) {
        def color = (status == 'PASS' || status == 'NOT_REQUIRED') ? '#16a34a' : status == 'FAIL' ? '#dc2626' : (status == 'WARN' || status == 'BLOCKED') ? '#d97706' : '#6b7280'
        def lbl   = status == 'NOT_REQUIRED' ? 'NOT REQUIRED' : (status ?: 'SKIP')
        return "<span style='background:${color};color:#fff;padding:2px 6px;border-radius:3px;font-size:0.6rem;font-weight:700;letter-spacing:0.5px;text-transform:uppercase;'>${lbl}</span>"
    }
    @NonCPS protected String vulnCell(int count, int limit) {
        boolean exceeded = count > limit
        return td("${count} / ${limit}", "text-align:center;background:${exceeded ? BAD_BG : OK_BG};color:${exceeded ? BAD_COLOR : OK_COLOR};font-weight:700;padding:6px 10px;")
    }
    @NonCPS protected String niqCountCell(int count, int limit) {
        boolean exceeded = count > limit
        def value = "<span style='color:${exceeded ? BAD_COLOR : OK_COLOR};font-weight:700;font-size:0.88rem;'>${count}&nbsp;/&nbsp;${limit}</span>"
        return td(value, "text-align:center;background:${exceeded ? BAD_BG : OK_BG};padding:5px 10px;")
    }
    @NonCPS protected String sonarCountCell(int count, int limit) {
        boolean exceeded = count > limit
        return td("${count} / ${limit}", "text-align:center;background:${exceeded ? BAD_BG : OK_BG};color:${exceeded ? BAD_COLOR : OK_COLOR};font-weight:700;padding:6px 10px;")
    }
    @NonCPS protected String sonarCountCell(int count) {
        boolean warn = count > 0
        return td("${count}", "text-align:center;background:${warn ? '#fff5c1' : OK_BG};color:${warn ? '#d97706' : OK_COLOR};font-weight:700;padding:6px 10px;")
    }
    @NonCPS protected String vulnCountBadge(int val) {
        if (val > 0) return "<b style='color:#dc2626;border:1px solid #1f2937;border-radius:2px;padding:0 3px;margin:0 1px;font-size:0.7rem;'>${val}</b>"
        return "<span style='color:#94a3b8;font-size:0.7rem;'>${val}</span>"
    }
    @NonCPS protected String vulnLimitBadge(int val, int limit) {
        boolean exceeded = val > limit
        def valStyle = exceeded
                ? "color:#dc2626;border:1px solid #1f2937;border-radius:2px;padding:0 3px;margin:0 1px;font-size:0.7rem;font-weight:700;"
                : "color:${OK_COLOR};font-size:0.7rem;font-weight:700;"
        return "<span style='${valStyle}'>${val}</span><span style='color:#94a3b8;font-size:0.7rem;'>&nbsp;/&nbsp;${limit}</span>"
    }
    @NonCPS protected String formatDuration(long ms) {
        if (ms <= 0) return ''
        long s = ms / 1000
        if (s < 60) return "${s}s"
        long m = s.intdiv(60); long rem = s % 60
        if (m < 60) return "${m}m ${rem}s"
        return "${m.intdiv(60)}h ${m % 60}m"
    }
    @NonCPS protected String arrow() {
        return "<div style='display:flex;flex-direction:column;align-items:center;height:10px;margin:1px 0;'><div style='width:2px;flex:1;background:#94a3b8;'></div><div style='width:0;height:0;border-left:4px solid transparent;border-right:4px solid transparent;border-top:5px solid #64748b;'></div></div>"
    }
    @NonCPS protected String linkBtn(String url, String label) {
        return "<a href='${url}' target='_blank' style='display:inline-block;background:#e0f2fe;color:#0369a1;border:1px solid #7dd3fc;border-radius:3px;padding:2px 7px;font-size:0.7rem;font-weight:600;text-decoration:none;margin-left:4px;'>${esc(label)}</a>"
    }
    @NonCPS protected String policyStatusFor(String key, Map counts, Map policyLimits) {
        def limits = policyLimits.get(key); if (!limits) return 'SKIP'
        return computePolicy((counts.get(key) ?: [critical: 0, high: 0, medium: 0]) as Map, limits as Map)
    }
    @NonCPS protected String computePolicy(Map counts, Map limits) {
        def c = (counts?.get('critical') ?: 0) as int; def h = (counts?.get('high') ?: 0) as int; def m = (counts?.get('medium') ?: 0) as int
        def lc = (limits?.get('maxCritical') ?: 0) as int; def lh = (limits?.get('maxHigh') ?: 0) as int; def lm = (limits?.get('maxMedium') ?: 0) as int
        return (c <= lc && h <= lh && m <= lm) ? 'PASS' : 'WARN'
    }

    @NonCPS protected String testJobsHtml(List jobs) {
        if (!jobs) return "<div style='margin-top:4px;padding-left:25px;font-size:0.72rem;color:#dc2626;font-weight:600;'>No jobs executed - tests are mandatory</div>"
        def sb = new StringBuilder()
        if (jobs.size() > 1) sb.append(testJobsSummaryHtml(jobs))
        sb.append("<div style='margin-top:5px;padding-left:25px;display:flex;flex-direction:column;gap:2px;'>")
        if (jobs.size() <= TEST_JOBS_INLINE_LIMIT) {
            sb.append(testJobRowsHtml(jobs))
        } else {
            def problems = jobs.findAll { !isTestJobOk(it?.get('status') as String) }
            sb.append(testJobRowsHtml(problems.size() > TEST_JOBS_INLINE_LIMIT ? problems.subList(0, TEST_JOBS_INLINE_LIMIT) : problems))
            if (problems.size() > TEST_JOBS_INLINE_LIMIT) {
                sb.append("<div style='font-size:0.68rem;color:${BAD_COLOR};padding-left:18px;'>... and ${problems.size() - TEST_JOBS_INLINE_LIMIT} more not successful job(s)</div>")
            }
            sb.append("<details style='margin-top:2px;'><summary style='cursor:pointer;font-size:0.7rem;color:${LINK_COLOR};font-weight:600;'>Show all ${jobs.size()} jobs</summary><div style='display:flex;flex-direction:column;gap:2px;margin-top:3px;'>")
            sb.append(testJobRowsHtml(jobs))
            sb.append("</div></details>")
        }
        sb.append("</div>")
        return sb.toString()
    }
    @NonCPS protected String testJobRowsHtml(List jobs) {
        def sb = new StringBuilder()
        for (int i = 0; i < jobs.size(); i++) sb.append(testJobRowHtml(jobs.get(i) as Map, i))
        return sb.toString()
    }
    @NonCPS protected boolean isTestJobOk(String status) {
        return status == 'SUCCESS' || status == 'ALREADY IMPLEMENTED'
    }
    @NonCPS protected Map testJobsSummary(List jobs) {
        int passed = 0; int failed = 0; int notConfigured = 0
        for (def job : (jobs ?: [])) {
            def status = job?.get('status') as String
            if (isTestJobOk(status)) passed++
            else if (status == 'NOT_CONFIGURED') notConfigured++
            else failed++
        }
        return [total: (jobs ?: []).size(), passed: passed, failed: failed, notConfigured: notConfigured]
    }
    @NonCPS protected String testJobsSummaryHtml(List jobs) {
        def s = testJobsSummary(jobs)
        def chip = { String label, int value, String color -> "<span style='display:inline-block;border:1px solid ${color};color:${color};border-radius:2px;padding:0 5px;margin-right:4px;font-size:0.65rem;font-weight:700;'>${label}&nbsp;${value}</span>" }
        return "<div style='margin-top:4px;padding-left:25px;'>${chip('Total', s.total as int, '#334155')}${chip('Passed', s.passed as int, '#16a34a')}${chip('Failed', s.failed as int, (s.failed as int) > 0 ? '#dc2626' : '#94a3b8')}${chip('Not configured', s.notConfigured as int, '#94a3b8')}</div>"
    }
    @NonCPS protected String testJobStatusColor(String status) {
        if (isTestJobOk(status)) return '#16a34a'
        return status == 'NOT_CONFIGURED' ? '#94a3b8' : '#dc2626'
    }
    @NonCPS protected String testJobRowHtml(Map job, int i) {
        def name   = job.get('name') ?: "job-${i + 1}"
        def status = (job.get('status') ?: 'UNKNOWN') as String
        def url    = job.get('url')?.toString()?.trim() ?: ''
        def type   = (job.get('type') ?: 'local') as String
        def color  = testJobStatusColor(status)
        def mark   = status == 'SUCCESS' ? '&#10003;' : status == 'NOT_CONFIGURED' ? '&#8212;' : '&#10007;'
        def dot     = "<span style='color:${color};font-size:0.8rem;font-weight:900;margin-right:5px;'>${mark}</span>"
        def typeTag = "<span style='font-size:0.6rem;color:#fff;background:${type == 'remote' ? '#7c3aed' : LINK_COLOR};padding:1px 4px;border-radius:2px;margin-left:4px;'>${esc(type)}</span>"
        def buildLink = url ? "&nbsp;<a href='${esc(url)}' target='_blank' style='font-size:0.68rem;color:${LINK_COLOR};'>Build</a>" : ''
        def stBadge = "<span style='font-size:0.6rem;font-weight:700;color:${color};margin-left:4px;'>${esc(status)}</span>"
        return "<div style='display:flex;align-items:center;flex-wrap:wrap;'>${dot}<span style='font-size:0.73rem;font-weight:600;color:#1e293b;'>${esc(name?.toString() ?: '')}</span>${typeTag}<span style='font-size:0.68rem;color:#64748b;margin-left:6px;'>${BuildUtils.msFmt((job.get('durationMs') ?: 0L) as long)}</span>${stBadge}${buildLink}</div>"
    }

    @NonCPS protected String testJobsCardHtml(String title, String stageName, Map projectsRemoteTestResults, Map remoteTestResults) {
        def groups = testJobGroups(stageName, projectsRemoteTestResults, remoteTestResults)
        if (groups.isEmpty()) return ''
        def sb = new StringBuilder()
        sb.append("<div style='overflow:auto;max-height:560px;'><table style='min-width:560px;'>")
        sb.append("<thead><tr><th style='text-align:center;width:44px;'>#</th><th style='text-align:left;'>Job</th><th style='text-align:center;'>Type</th><th style='text-align:center;'>Status</th><th style='text-align:center;'>Duration</th><th style='text-align:center;'>Build</th></tr></thead><tbody>")
        groups.each { project, list ->
            sb.append(testJobsGroupHeader(project as String, list as List))
            def problems = (list as List).findAll { !isTestJobOk(it?.get('status') as String) }
            def rest     = (list as List).findAll { isTestJobOk(it?.get('status') as String) }
            (problems + rest).eachWithIndex { job, i -> sb.append(testJobTableRow(job as Map, i)) }
        }
        sb.append("</tbody></table></div>")
        return card(title, sb.toString())
    }
    @NonCPS protected Map testJobGroups(String stageName, Map projectsRemoteTestResults, Map remoteTestResults) {
        def groups = [:]
        (projectsRemoteTestResults ?: [:]).each { p, stages ->
            def l = (stages instanceof Map) ? stages.get(stageName) : null
            if (l instanceof List && !l.isEmpty()) groups[p] = l
        }
        if (groups.isEmpty()) {
            def l = remoteTestResults?.get(stageName)
            if (l instanceof List && !l.isEmpty()) groups[''] = l
        }
        return groups
    }
    @NonCPS protected String testJobsGroupHeader(String project, List jobs) {
        def s = testJobsSummary(jobs)
        def label = project ? "${esc(project)} &nbsp;&bull;&nbsp; " : ''
        def text = "${label}${s.total} job(s): ${s.passed} passed, ${s.failed} failed, ${s.notConfigured} not configured"
        return "<tr style='background:#eff6ff;border-top:2px solid #bfdbfe;'><td colspan='6' style='padding:6px 14px;font-size:0.8rem;font-weight:700;color:#1d4ed8;'>${text}</td></tr>"
    }
    @NonCPS protected String testJobTableRow(Map job, int i) {
        def status = (job?.get('status') ?: 'UNKNOWN') as String
        def ok     = isTestJobOk(status)
        def color  = ok ? OK_COLOR : status == 'NOT_CONFIGURED' ? '#64748b' : BAD_COLOR
        def bg     = ok ? OK_BG : status == 'NOT_CONFIGURED' ? '#f8fafc' : BAD_BG
        def url    = job?.get('url')?.toString()?.trim() ?: ''
        def msg    = job?.get('message')?.toString() ?: ''
        def name   = esc((job?.get('name') ?: "job-${i + 1}") as String) + (msg ? "<div style='font-size:0.68rem;color:#64748b;font-weight:400;'>${esc(msg)}</div>" : '')
        def sb = new StringBuilder()
        sb.append("<tr>")
        sb.append(td("${job?.get('index') ?: i + 1}", 'text-align:center;padding:5px 8px;color:#64748b;'))
        sb.append(td(name, 'padding:5px 10px;font-weight:600;overflow-wrap:anywhere;'))
        sb.append(td(esc((job?.get('type') ?: 'local') as String), 'text-align:center;padding:5px 8px;'))
        sb.append(td(esc(status), "text-align:center;padding:5px 8px;background:${bg};color:${color};font-weight:700;"))
        sb.append(td(BuildUtils.msFmt((job?.get('durationMs') ?: 0L) as long), 'text-align:center;padding:5px 8px;white-space:nowrap;'))
        sb.append(td(url ? link(url, 'Build') : '-', 'text-align:center;padding:5px 8px;'))
        sb.append("</tr>")
        return sb.toString()
    }

    @NonCPS protected Map goldenFixFor(Map projectsGoldenFix, String projectName) {
        if (!projectsGoldenFix) return null
        if (projectName) return projectsGoldenFix.get(projectName) as Map
        return projectsGoldenFix.size() == 1 ? projectsGoldenFix.values().iterator().next() as Map : null
    }
    @NonCPS protected boolean goldenFixHasPr(Map gf) {
        def status = gf?.get('status')
        return (status == 'PR_CREATED' || status == 'PR_UPDATED') && (gf?.get('prUrl')?.toString() ?: '')
    }
    @NonCPS protected String goldenFixPrLink(Map gf) {
        def url = gf?.get('prUrl')?.toString() ?: ''
        return url ? link(url, 'GoldenFix PR', PR_COLOR, 'font-weight:700;') : ''
    }
    @NonCPS protected String linkStack(List parts) {
        List present = []
        for (def part : parts) {
            if (part) present << part
        }
        return present ? present.join('<br>') : mutedDash()
    }
    @NonCPS protected List projectReasonParts(String error, String project) {
        List parts = []
        if (!error || !project) return parts
        String prefix = project + ': '
        for (String part : error.replace(' ' + PolicyEngine.blockNote(), '').split(/ \|\| /)) {
            if (part.startsWith(prefix)) parts << part.substring(prefix.length())
        }
        return parts
    }
    @NonCPS protected String projectReason(String error, String project) {
        if (!error || !project) return error ?: ''
        List parts = projectReasonParts(error, project)
        if (parts.isEmpty()) return error.replace(' || ', ' | ')
        return parts.join(' | ') + ' ' + PolicyEngine.blockNote()
    }
    @NonCPS protected String goldenFixInlineHtml(Map gf) {
        if (!gf) return ''
        if (goldenFixHasPr(gf)) {
            def count = ((gf.get('changes') ?: []) as List).size()
            def prLink = "<a href='${esc(gf.get('prUrl') as String)}' target='_blank' style='color:${LINK_COLOR};'>${esc(gf.get('prTitle') as String)}</a>"
            return "<div style='margin-top:4px;padding-left:25px;font-size:0.72rem;color:${OK_COLOR};font-weight:600;'>Pull request raised with GoldenFix: ${prLink} (${count} change(s))</div>"
        }
        def color = gf.get('status') == 'ERROR' ? BAD_COLOR : '#64748b'
        return "<div style='margin-top:4px;padding-left:25px;font-size:0.72rem;color:${color};'>GoldenFix: ${esc(gf.get('message') as String)}</div>"
    }
    @NonCPS protected String goldenFixCardHtml(Map projectsGoldenFix, boolean isMulti) {
        if (!projectsGoldenFix) return ''
        def sb = new StringBuilder()
        projectsGoldenFix.each { project, value ->
            def gf = value as Map
            if (isMulti) sb.append("<div style='font-size:0.82rem;font-weight:700;color:#1d4ed8;margin:8px 0 4px;'>${esc(project as String)}</div>")
            sb.append(goldenFixStatusBanner(gf))
            sb.append(goldenFixChangesTable((gf.get('changes') ?: []) as List))
            sb.append(goldenFixUnresolvedList((gf.get('unresolved') ?: []) as List))
        }
        return card('Nexus IQ GoldenFix', sb.toString())
    }
    @NonCPS protected String goldenFixStatusBanner(Map gf) {
        if (goldenFixHasPr(gf)) {
            def prLink = "<a href='${esc(gf.get('prUrl') as String)}' target='_blank' style='color:${LINK_COLOR};font-weight:700;'>${esc(gf.get('prTitle') as String)}</a>"
            def updated = gf.get('status') == 'PR_UPDATED' ? ' (existing pull request updated)' : ''
            def details = "<div style='font-size:0.72rem;color:${OK_COLOR};margin-top:2px;'>Branch ${esc(gf.get('branch') as String)} &rarr; ${esc(gf.get('targetBranch') as String)}${updated}</div>"
            return "<div style='background:${OK_BG};border:1px solid #86efac;border-left:4px solid #16a34a;padding:8px 12px;font-size:0.85rem;color:${OK_COLOR};margin-bottom:8px;'><b>A pull request with GoldenFix upgrades was raised:</b> ${prLink}${details}</div>"
        }
        boolean error = gf.get('status') == 'ERROR'
        def style = "background:${error ? '#fef2f2' : '#f8fafc'};border:1px solid ${error ? '#fca5a5' : '#e2e8f0'};padding:8px 12px;font-size:0.82rem;color:${error ? BAD_COLOR : '#475569'};margin-bottom:8px;"
        return "<div style='${style}'><b>${esc(gf.get('status') as String)}</b>: ${esc(gf.get('message') as String)}</div>"
    }
    @NonCPS protected String goldenFixChangesTable(List changes) {
        if (!changes) return ''
        def sb = new StringBuilder()
        sb.append("<div style='overflow-x:auto;'><table style='min-width:560px;'><thead><tr><th>File</th><th>Component</th><th style='text-align:center;'>From</th><th style='text-align:center;'>To</th></tr></thead><tbody>")
        changes.each { c ->
            def property = c.get('property') ? "<div style='font-size:0.68rem;color:#64748b;'>property ${esc(c.get('property') as String)}</div>" : ''
            sb.append("<tr>")
            sb.append(td(esc(c.get('file') as String), 'padding:5px 10px;font-family:monospace;font-size:0.74rem;overflow-wrap:anywhere;'))
            sb.append(td(esc(c.get('component') as String) + property, 'padding:5px 10px;font-weight:600;'))
            sb.append(td(esc(c.get('from') as String), "text-align:center;padding:5px 8px;color:${BAD_COLOR};"))
            sb.append(td(esc(c.get('to') as String), "text-align:center;padding:5px 8px;color:${OK_COLOR};font-weight:700;"))
            sb.append("</tr>")
        }
        sb.append("</tbody></table></div>")
        return sb.toString()
    }
    @NonCPS protected String goldenFixUnresolvedList(List unresolved) {
        if (!unresolved) return ''
        def sb = new StringBuilder()
        sb.append("<div style='margin-top:8px;font-size:0.78rem;font-weight:700;color:#92400e;'>Not applied automatically</div><ul style='margin:4px 0 0 18px;font-size:0.74rem;color:#475569;'>")
        unresolved.each { u ->
            sb.append("<li><b>${esc(u.get('component') as String)}</b> ${esc(u.get('from') as String)} &rarr; ${esc(u.get('to') as String)}: ${esc(u.get('reason') as String)}</li>")
        }
        sb.append("</ul>")
        return sb.toString()
    }

    @NonCPS protected String releaseGateBanner(Map releaseGate) {
        if (!releaseGate || releaseGate.get('allowed')) return ''
        return "<div style='background:#b45309;color:#fff;font-weight:700;text-align:center;padding:8px 12px;border-radius:3px;margin-bottom:8px;font-size:0.85rem;letter-spacing:0.5px;'>NEXUS RELEASE AND QC DEPLOYMENT BLOCKED</div>"
    }

    @NonCPS protected String releaseGateCardHtml(Map releaseGate) {
        if (!releaseGate) return ''
        List violations = (releaseGate.get('violations') ?: []) as List
        if (releaseGate.get('allowed')) {
            def body = "<div style='background:${OK_BG};border:1px solid #86efac;border-left:4px solid #16a34a;padding:8px 12px;font-size:0.85rem;color:${OK_COLOR};'>The library security policy is met - the artifact is released to Nexus and the QC deployment is allowed.</div>"
            return card('Release policy', body)
        }
        def sb = new StringBuilder()
        sb.append("<div style='background:#fffbeb;border:1px solid #fcd34d;border-left:4px solid #d97706;padding:8px 12px;font-size:0.85rem;color:#92400e;'>")
        sb.append("<b>The artifact was not released to Nexus and the deployment to QC is blocked.</b>")
        sb.append("<div style='font-size:0.78rem;margin-top:2px;'>Every stage was executed and the build reached the lower test region (RD), but at least one stage is marked orange. Fix the findings listed below to unblock the Nexus release and the QC deployment.</div></div>")
        if (violations) {
            sb.append("<ul style='margin:8px 0 0 18px;font-size:0.78rem;color:#475569;'>")
            violations.each { sb.append("<li>${esc(it as String)}</li>") }
            sb.append("</ul>")
        }
        return card('Release policy', sb.toString())
    }

    @NonCPS protected String stageBox(String name, String status, String error, Map counts, List links, String extraHtml = '', String duration = '', String stageNumStr = '') {
        def palette = stageBoxPalette(status)
        def numHtml = stageNumStr ? "<span style='display:inline-flex;align-items:center;justify-content:center;padding:0 5px;height:18px;min-width:18px;background:#1e293b;color:#f8fafc;font-size:0.65rem;font-weight:700;margin-right:7px;flex-shrink:0;border-radius:2px;'>${stageNumStr}</span>" : ''
        def detHtml = counts ? severityLine(counts) : ''
        if (extraHtml) detHtml += extraHtml
        def linksHtml = ''
        if (links) {
            def sb = new StringBuilder()
            for (int i = 0; i < links.size(); i++) {
                def lnk = links.get(i)
                sb.append(linkBtn(lnk.get('url') as String, lnk.get('label') as String))
            }
            linksHtml = "<div style='margin-top:3px;padding-left:25px;'>${sb.toString()}</div>"
        }
        def durHtml = duration ? "<span style='font-size:0.67rem;color:#94a3b8;margin-left:6px;font-weight:400;'>&#9201;&nbsp;${esc(duration)}</span>" : ''
        def html = "<div style='border:1px solid ${palette.border};border-left:3px solid ${palette.bar};background:${palette.bg};padding:7px 10px;'><div style='display:flex;justify-content:space-between;align-items:flex-start;gap:10px;'><div style='min-width:0;flex:1;'><div style='font-weight:600;color:#0f172a;font-size:0.79rem;display:flex;align-items:center;flex-wrap:wrap;gap:0;'>${numHtml}<span style='overflow-wrap:anywhere;word-break:break-word;min-width:0;'>${esc(name)}</span>${durHtml}</div>${detHtml}${linksHtml}</div><div style='flex:0 0 auto;'>${badge(status ?: 'SKIP')}</div></div></div>"
        if (error && (status == 'FAIL' || status == 'WARN' || status == 'BLOCKED')) {
            boolean failed = status == 'FAIL'
            html += "<div style='background:${failed ? '#fef2f2' : '#fffbeb'};border:1px solid ${failed ? '#fca5a5' : '#fcd34d'};border-top:none;border-left:3px solid ${palette.bar};padding:5px 10px 5px 35px;font-size:0.78rem;color:${failed ? BAD_COLOR : '#92400e'};overflow-wrap:anywhere;word-break:break-word;'><strong>Reason:</strong> ${esc(error)}</div>"
        }
        return html
    }
    @NonCPS protected Map stageBoxPalette(String status) {
        if (status == 'PASS' || status == 'NOT_REQUIRED') return [bg: OK_BG, border: '#86efac', bar: '#16a34a']
        if (status == 'FAIL') return [bg: '#fef2f2', border: '#fca5a5', bar: '#dc2626']
        if (status == 'WARN' || status == 'BLOCKED') return [bg: '#fffbeb', border: '#fcd34d', bar: '#d97706']
        return [bg: '#f8fafc', border: '#e2e8f0', bar: '#94a3b8']
    }
    @NonCPS protected String severityLine(Map counts) {
        def c = (counts.get('critical') ?: 0) as int; def h = (counts.get('high') ?: 0) as int
        def m = (counts.get('medium') ?: 0) as int; def l = (counts.get('low') ?: 0) as int
        return "<div style='margin-top:3px;font-size:0.74rem;padding-left:25px;'>C:&nbsp;${vulnCountBadge(c)}&nbsp;&nbsp;H:&nbsp;${vulnCountBadge(h)}&nbsp;&nbsp;M:&nbsp;${vulnCountBadge(m)}&nbsp;&nbsp;L:&nbsp;${vulnCountBadge(l)}</div>"
    }

    @NonCPS protected String projectHeaderRow(String project) {
        return "<tr style='background:#eff6ff;border-top:2px solid #bfdbfe;'><td colspan='7' style='padding:6px 14px;font-size:0.82rem;font-weight:700;color:#1d4ed8;letter-spacing:0.3px;'>${esc(project)}</td></tr>"
    }
    @NonCPS protected String scannerLabelCell(String label, boolean indented) {
        def style = indented ? 'padding:8px 14px 8px 26px;font-weight:600;color:#374151;font-size:0.8rem;' : 'padding:10px 14px;font-weight:600;'
        return td(label, style)
    }
    @NonCPS protected String appscanRow(String label, Map counts, Map limits, String policyStatus, String reportCell) {
        def sb = new StringBuilder()
        sb.append("<tr>").append(scannerLabelCell(label, true))
        sb.append(vulnCell((counts.get('critical') ?: 0) as int, (limits.get('maxCritical') ?: 0) as int))
        sb.append(vulnCell((counts.get('high')     ?: 0) as int, (limits.get('maxHigh')     ?: 0) as int))
        sb.append(vulnCell((counts.get('medium')   ?: 0) as int, (limits.get('maxMedium')   ?: 0) as int))
        sb.append(centerTd("${counts.get('low') ?: 0}", 'font-weight:600;'))
        sb.append(centerTd(badge(policyStatus)))
        sb.append(td(reportCell, CELL_NOWRAP))
        sb.append("</tr>")
        return sb.toString()
    }
    @NonCPS protected String reportLinksCell(String artifactBase, String reportFile, String pdfFile, String consoleUrl, String suffix) {
        def html = link("${artifactBase}${reportFile}", 'Report')
        if (pdfFile) html += " / " + link("${artifactBase}${pdfFile}", 'PDF', PDF_COLOR)
        if (consoleUrl) html += " / " + link(consoleUrl, 'HCL AppScan')
        return html + (suffix ?: '')
    }

    @NonCPS protected String buildProjectsSecRows(Map projectsVulnCounts, Map projectsNexusIQ, Map projectsScanResults, Map projectsSonarResults, Map policyLimits, String artifactBase, Map projectsGoldenFix = [:]) {
        def sb = new StringBuilder()
        projectsVulnCounts.each { key, value ->
            String project = key?.toString() ?: ''
            Map scanners = (value ?: [:]) as Map
            Map projScan = (projectsScanResults?.get(project) ?: [:]) as Map
            Map projNiq  = (projectsNexusIQ?.get(project) ?: [:]) as Map
            sb.append(projectHeaderRow(project))
            if (scanners.containsKey('sast')) {
                sb.append(appscanProjectRow('SAST (AppScan)', 'sast', scanners, projScan, policyLimits, artifactBase, 'appscan-report.html', '', ''))
            }
            if (scanners.containsKey('dast')) {
                sb.append(appscanProjectRow('DAST (AppScan)', 'dast', scanners, projScan, policyLimits, artifactBase, 'appscan-dast-report.html', 'dast_pdf', ''))
            }
            Map niqCounts = (scanners.get('niq') ?: [:]) as Map
            if (!niqCounts.isEmpty() || projNiq) {
                sb.append(nexusIqProjectRow(niqCounts, projNiq, projScan, policyLimits, goldenFixFor(projectsGoldenFix, project)))
            }
            Map projSonar = (projectsSonarResults?.get(project) ?: [:]) as Map
            if (!projSonar.isEmpty() || projScan.containsKey('sonar')) {
                sb.append(sonarProjectRow(projSonar, projScan, policyLimits))
            }
        }
        return sb.toString()
    }
    @NonCPS protected String appscanProjectRow(String label, String key, Map scanners, Map projScan, Map policyLimits, String artifactBase, String fallbackFile, String pdfKey, String suffix) {
        Map counts = (scanners.get(key) ?: [:]) as Map
        Map limits = (policyLimits.get(key) ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]) as Map
        String policyStatus = (projScan.get(key) ?: computePolicy(counts, limits)) as String
        String reportFile = projScan.get("${key}_file".toString())?.toString() ?: fallbackFile
        String pdfFile = pdfKey ? (projScan.get(pdfKey)?.toString() ?: '') : ''
        String consoleUrl = projScan.get("${key}_hcl".toString())?.toString() ?: ''
        return appscanRow(label, counts, limits, policyStatus, reportLinksCell(artifactBase, reportFile, pdfFile, consoleUrl, suffix))
    }
    @NonCPS protected String nexusIqProjectRow(Map niqCounts, Map projNiq, Map projScan, Map policyLimits, Map goldenFix) {
        Map limits = (policyLimits.get('niq') ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]) as Map
        def status = projScan.get('niq') ?: projNiq.get('status') ?: computePolicy(niqCounts, limits)
        def dashboard = projNiq.get('url') ? link(projNiq.get('url') as String, 'Dashboard') : ''
        def goldenFixLink = projNiq.get('fix_url') ? link(projNiq.get('fix_url') as String, 'Golden Fix') : ''
        def sb = new StringBuilder()
        sb.append("<tr>").append(scannerLabelCell('Nexus IQ', true))
        if (projNiq.containsKey('critical')) {
            sb.append(niqCountCell(((projNiq.get('critical') ?: niqCounts.get('critical') ?: 0)) as int, (limits.get('maxCritical') ?: 0) as int))
            sb.append(niqCountCell(((projNiq.get('high')     ?: niqCounts.get('high')     ?: 0)) as int, (limits.get('maxHigh')     ?: 0) as int))
            sb.append(niqCountCell(((projNiq.get('medium')   ?: niqCounts.get('medium')   ?: 0)) as int, (limits.get('maxMedium')   ?: 0) as int))
        } else {
            sb.append(dashTd('font-weight:600')).append(dashTd('font-weight:600')).append(dashTd('font-weight:600'))
        }
        sb.append(dashTd('font-weight:600'))
        sb.append(centerTd(badge(status as String), 'font-weight:600;'))
        sb.append(td(linkStack([dashboard, goldenFixLink, goldenFixPrLink(goldenFix)]), CELL + 'font-weight:600;line-height:1.7;'))
        sb.append("</tr>")
        return sb.toString()
    }
    @NonCPS protected String sonarProjectRow(Map projSonar, Map projScan, Map policyLimits) {
        Map limits = (policyLimits.get('sca') ?: [maxCritical: 0, maxHigh: 0]) as Map
        def status = projScan.get('sonar') ?: projSonar.get('status') ?: 'SKIP'
        def dashboard = projSonar.get('url') ? link(projSonar.get('url') as String, 'Dashboard') : mutedDash()
        def sb = new StringBuilder()
        sb.append("<tr>").append(scannerLabelCell('SCA (SonarQube)', true))
        if (projSonar.containsKey('critical')) {
            sb.append(sonarCountCell((projSonar.get('critical') ?: 0) as int, (limits.get('maxCritical') ?: 0) as int))
            sb.append(sonarCountCell((projSonar.get('high') ?: 0) as int, (limits.get('maxHigh') ?: 0) as int))
            sb.append(sonarCountCell((projSonar.get('medium') ?: 0) as int, (limits.get('maxMedium') ?: 0) as int))
            sb.append(sonarCountCell((projSonar.get('low') ?: 0) as int))
        } else {
            sb.append(dashTd('font-weight:600')).append(dashTd('font-weight:600')).append(dashTd('font-weight:600')).append(dashTd('font-weight:600'))
        }
        sb.append(centerTd(badge(status as String)))
        sb.append(td(dashboard, CELL_NOWRAP))
        sb.append("</tr>")
        return sb.toString()
    }

    @NonCPS protected String scanArtifactUrl(Map ctx, String key) {
        Map all = (ctx.projectsScanResults ?: [:]) as Map
        for (def entry : all.entrySet()) {
            def value = ((entry.value ?: [:]) as Map).get(key)
            if (value) return value as String
        }
        return ''
    }

    @NonCPS protected String buildSingleProjectSecRows(Map ctx) {
        def sb = new StringBuilder()
        def stageResults = ctx.stageResults as Map
        def vulnCounts   = ctx.vulnCounts as Map
        def policyLimits = ctx.policyLimits as Map
        def artifactBase = ctx.artifactBase as String

        for (int i = 0; i < appscanScnrs.size(); i++) {
            def info = appscanScnrs.get(i)
            String key   = info.get('key')?.toString() ?: ''
            String label = info.get('label')?.toString() ?: ''
            String file  = info.get('file')?.toString() ?: ''
            String pdf   = info.get('pdf')?.toString() ?: ''
            String stageStatus = (stageResults.get(info.get('stage')?.toString() ?: '') ?: 'SKIP') as String
            boolean ran = runStatuses.contains(stageStatus)
            Map counts = (vulnCounts.get(key) ?: [critical: 0, high: 0, medium: 0, low: 0]) as Map
            Map limits = (policyLimits.get(key) ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]) as Map
            String policyStatus = (stageStatus == 'NOT_REQUIRED') ? 'NOT_REQUIRED' : ran ? policyStatusFor(key, vulnCounts, policyLimits) : 'SKIP'

            sb.append("<tr>").append(scannerLabelCell(label, false))
            sb.append(ran ? vulnCell(counts.get('critical') as int, limits.get('maxCritical') as int) : dashTd())
            sb.append(ran ? vulnCell(counts.get('high') as int, limits.get('maxHigh') as int) : dashTd())
            sb.append(ran ? vulnCell(counts.get('medium') as int, limits.get('maxMedium') as int) : dashTd())
            sb.append(ran ? centerTd("${counts.get('low') ?: 0}") : dashTd())
            sb.append(centerTd(badge(policyStatus)))
            String consoleUrl = scanArtifactUrl(ctx, "${key}_hcl")
            def reportCell = ran
                    ? (link("${artifactBase}${file}", 'Report', LINK_COLOR, 'white-space:nowrap;')
                        + (pdf ? " / " + link("${artifactBase}${pdf}", 'PDF', PDF_COLOR) : '')
                        + (consoleUrl ? " / " + link(consoleUrl, 'HCL AppScan') : ''))
                    : mutedDash()
            sb.append(td(reportCell, CELL_NOWRAP)).append("</tr>")
        }

        if (showScannerSummaryRows()) {
            sb.append(nexusIqSingleRow(ctx))
            sb.append(sonarSingleRow(ctx))
        }
        return sb.toString()
    }

    @NonCPS protected boolean showScannerSummaryRows() {
        return variant != 'sast' && script.env.Security_Pipeline == null
    }
    @NonCPS protected String nexusIqSingleRow(Map ctx) {
        Map results = (ctx.nexusIqResults ?: [:]) as Map
        Map limits  = ((ctx.policyLimits as Map).get('niq') ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]) as Map
        boolean ran = runStatuses.contains(((ctx.stageResults as Map).get(NIQ_STAGE) ?: 'SKIP') as String)
        String status = ran ? ((results.get('status') ?: 'SKIP') as String) : 'SKIP'
        String url    = (results.get('url') ?: '') as String
        String fixUrl = (results.get('fix_url') ?: '') as String
        def dashboard = url ? link(url, 'Dashboard') : ''
        def goldenFix = fixUrl ? link(fixUrl, 'Golden Fix') : ''

        def sb = new StringBuilder()
        sb.append("<tr>").append(scannerLabelCell('Nexus IQ', false))
        sb.append(ran ? niqCountCell((results.get('critical') ?: 0) as int, (limits.get('maxCritical') ?: 0) as int) : dashTd())
        sb.append(ran ? niqCountCell((results.get('high') ?: 0) as int, (limits.get('maxHigh') ?: 0) as int) : dashTd())
        sb.append(ran ? niqCountCell((results.get('medium') ?: 0) as int, (limits.get('maxMedium') ?: 0) as int) : dashTd())
        sb.append(dashTd())
        sb.append(centerTd(badge(status)))
        if (url || fixUrl) {
            def prLink = goldenFixPrLink(goldenFixFor(ctx.projectsGoldenFix as Map, ''))
            sb.append(td(linkStack([dashboard, goldenFix, prLink]), CELL + 'line-height:1.7;')).append("</tr>")
        } else {
            sb.append(dashTd()).append("</tr>")
        }
        return sb.toString()
    }
    @NonCPS protected String sonarSingleRow(Map ctx) {
        Map results = (ctx.sonarResults ?: [:]) as Map
        Map limits  = ((ctx.policyLimits as Map).get('sca') ?: [maxCritical: 0, maxHigh: 0]) as Map
        boolean ran = runStatuses.contains(((ctx.stageResults as Map).get('SCA (SonarQube)') ?: 'SKIP') as String)
        String status = ran ? ((results.get('status') ?: 'SKIP') as String) : 'SKIP'
        String url    = (results.get('url') ?: '') as String
        def dashboard = url ? link(url, 'Dashboard') : mutedDash()

        def sb = new StringBuilder()
        sb.append("<tr>").append(scannerLabelCell('SCA (SonarQube)', false))
        sb.append(ran ? sonarCountCell((results.get('critical') ?: 0) as int, (limits.get('maxCritical') ?: 0) as int) : dashTd())
        sb.append(ran ? sonarCountCell((results.get('high') ?: 0) as int, (limits.get('maxHigh') ?: 0) as int) : dashTd())
        sb.append(ran ? sonarCountCell((results.get('medium') ?: 0) as int, (limits.get('maxMedium') ?: 0) as int) : dashTd())
        sb.append(ran ? sonarCountCell((results.get('low') ?: 0) as int) : dashTd())
        sb.append(centerTd(badge(status)))
        sb.append(td(dashboard, CELL_NOWRAP)).append("</tr>")
        return sb.toString()
    }

    @NonCPS protected String stageFlowHtml(Map ctx) {
        def stageResults = ctx.stageResults as Map
        def stageErrors  = ctx.stageErrors as Map
        def projectKeys  = ctx.projectKeys as List
        boolean isMulti  = ctx.isMulti as boolean
        int firstFailedIdx = ctx.firstFailedIdx as int

        def flow = new StringBuilder()
        int globalIdx = 0
        int stageNumber = 1
        for (int gi = 0; gi < phaseGroups.size(); gi++) {
            def group  = phaseGroups.get(gi)
            def stages = group.get('stages') as List
            flow.append("<div style='display:flex;border:2px solid #94a3b8;background:#fff;'>")
            flow.append("<div style='writing-mode:vertical-rl;transform:rotate(180deg);background:${group.get('color')};color:#fff;padding:12px 8px;font-size:0.79rem;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;display:flex;align-items:center;justify-content:center;min-width:32px;white-space:nowrap;'>${group.get('label')}</div>")
            flow.append("<div style='flex:1;padding:8px 8px;border-left:1px solid #e2e8f0;'>")
            for (int si = 0; si < stages.size(); si++) {
                String baseName = stages.get(si) as String
                String groupStatus = (stageResults.get(baseName) ?: 'SKIP') as String
                String groupError = (stageErrors.get(baseName) ?: '') as String
                if (groupStatus == 'SKIP' && firstFailedIdx >= 0 && globalIdx > firstFailedIdx) {
                    groupStatus = 'FAIL'
                    groupError = groupError ?: 'Stage not executed - pipeline blocked by previous failure'
                }
                for (int pi = 0; pi < projectKeys.size(); pi++) {
                    String project = projectKeys.get(pi)?.toString() ?: ''
                    Map box = stageBoxData(ctx, baseName, project, groupStatus)
                    String displayName = isMulti ? "${baseName} - ${project}" : baseName
                    String displayNum = isMulti ? "${stageNumber}.${pi + 1}" : "${stageNumber}"
                    String duration = ''
                    def timeInfo = (ctx.stageTimes as Map)?.get(baseName)
                    if (timeInfo != null && pi == projectKeys.size() - 1) {
                        long startMs = (timeInfo.get('start') ?: 0L) as long
                        long endMs = (timeInfo.get('end') ?: 0L) as long
                        if (startMs > 0 && endMs > startMs) duration = formatDuration(endMs - startMs)
                    }
                    String boxError = isMulti ? projectReason(groupError, project) : (groupError ?: '').replace(' || ', ' | ')
                    flow.append(stageBox(displayName, box.status as String, boxError, box.counts as Map, box.links as List, box.extra as String, duration, displayNum))
                    boolean isLastInGroup = (si == stages.size() - 1) && (pi == projectKeys.size() - 1)
                    if (!isLastInGroup) flow.append(arrow())
                }
                stageNumber++
                globalIdx++
            }
            flow.append("</div></div>")
            if (gi < phaseGroups.size() - 1) flow.append(arrow())
        }
        return flow.toString()
    }

    @NonCPS protected Map stageBoxData(Map ctx, String baseName, String project, String defaultStatus) {
        String buildUrl = ctx.buildUrl as String
        Map projScan = project ? ((ctx.projectsScanResults as Map)?.get(project) ?: [:]) as Map : [:]
        Map projVuln = project ? ((ctx.projectsVulnCounts as Map)?.get(project) ?: [:]) as Map : [:]
        Map projNiq  = project ? ((ctx.projectsNexusIQ as Map)?.get(project) ?: [:]) as Map : [:]

        String status = defaultStatus
        Map counts = null
        List links = []
        String extra = ''

        if (baseName.contains('SAST')) {
            if (projScan.containsKey('sast')) status = projScan.get('sast') as String
            counts = projVuln.get('sast') as Map
            if (projScan.get('sast_file')) links << [url: "${buildUrl}artifact/${projScan.get('sast_file')}", label: 'SAST Report']
            if (projScan.get('sast_hcl')) links << [url: projScan.get('sast_hcl'), label: 'HCL AppScan']
        } else if (baseName.contains('DAST')) {
            if (projScan.containsKey('dast')) status = projScan.get('dast') as String
            counts = projVuln.get('dast') as Map
            if (projScan.get('dast_file')) links << [url: "${buildUrl}artifact/${projScan.get('dast_file')}", label: 'DAST Report']
            if (projScan.get('dast_pdf')) links << [url: "${buildUrl}artifact/${projScan.get('dast_pdf')}", label: 'DAST Report (PDF)']
            if (projScan.get('dast_hcl')) links << [url: projScan.get('dast_hcl'), label: 'HCL AppScan']
        } else if (baseName.contains('Nexus IQ')) {
            if (projScan.containsKey('niq')) status = projScan.get('niq') as String
            counts = nexusIqBoxCounts(projNiq, projVuln)
            if (projNiq.get('url')) links << [url: projNiq.get('url'), label: 'Nexus IQ']
        } else if (baseName.contains('SonarQube')) {
            if (projScan.containsKey('sonar')) status = projScan.get('sonar') as String
            Map projSonar = ((ctx.projectsSonarResults as Map)?.get(project) ?: [:]) as Map
            if (projSonar.get('url')) links << [url: projSonar.get('url'), label: 'SonarQube']
        }
        if (links.isEmpty() && !TEST_STAGES.contains(baseName) && (ctx.stageLinks as Map)?.get(baseName)) links = (ctx.stageLinks as Map).get(baseName) as List

        if (baseName == 'Unit tests') {
            Map projectCoverage = coverageFor(ctx, project)
            extra = coverageLine(projectCoverage)
            if (project && (ctx.isMulti as boolean) && (status == 'PASS' || status == 'WARN') && projectCoverage.containsKey('enabled')) {
                boolean met = projectCoverage.get('enabled') && ((projectCoverage.get('line') ?: 0.0) as double) >= ((projectCoverage.get('minRequired') ?: 60) as double)
                boolean flagged = !projectReasonParts(((ctx.stageErrors as Map)?.get(baseName) ?: '') as String, project).isEmpty()
                status = (met && !flagged) ? 'PASS' : 'WARN'
            }
        } else if (baseName == NIQ_STAGE && counts) {
            extra = nexusIqLimitLine(counts, (ctx.policyLimits as Map).get('niq') as Map)
            counts = null
        } else if (baseName == 'SCA (SonarQube)') {
            counts = null
        } else if (TEST_STAGES.contains(baseName)) {
            List jobs = testJobsFor(ctx, baseName, project)
            extra = testJobsHtml(jobs)
            counts = null
            if (project && (ctx.isMulti as boolean) && jobs && (status == 'PASS' || status == 'WARN')) {
                boolean allOk = true
                for (def job : jobs) {
                    if (!isTestJobOk(job?.get('status') as String)) allOk = false
                }
                status = allOk ? 'PASS' : 'WARN'
            }
        }
        if (baseName == NIQ_STAGE) {
            Map goldenFix = goldenFixFor(ctx.projectsGoldenFix as Map, project)
            extra += goldenFixInlineHtml(goldenFix)
            if (goldenFix?.get('prUrl')) links = links + [[url: goldenFix.get('prUrl'), label: 'GoldenFix PR']]
        }
        return [status: status, counts: counts, links: links, extra: extra]
    }
    @NonCPS protected Map nexusIqBoxCounts(Map projNiq, Map projVuln) {
        Map fallback = (projVuln.get('niq') ?: [:]) as Map
        if (!projNiq && !fallback) return null
        return [
                critical: projNiq.get('critical') != null ? projNiq.get('critical') : (fallback.get('critical') ?: 0),
                high:     projNiq.get('high')     != null ? projNiq.get('high')     : (fallback.get('high') ?: 0),
                medium:   projNiq.get('medium')   != null ? projNiq.get('medium')   : (fallback.get('medium') ?: 0),
                low:      0
        ]
    }
    @NonCPS protected List testJobsFor(Map ctx, String stageName, String project) {
        def perProject = project ? (ctx.projectsRemoteTestResults as Map)?.get(project) : null
        def jobs = (perProject instanceof Map && perProject.containsKey(stageName))
                ? perProject.get(stageName)
                : (ctx.remoteTestResults as Map)?.get(stageName)
        return (jobs instanceof List) ? (jobs as List) : []
    }
    @NonCPS protected String coverageLine(Map coverage) {
        boolean enabled = coverage?.get('enabled') ? true : false
        Double pct = enabled ? ((coverage.get('line') ?: 0.0) as double) : null
        double minRequired = ((coverage?.get('minRequired') ?: 60) as double)
        boolean met = pct != null && pct >= minRequired
        def color = met ? '#16a34a' : '#d97706'
        def label = pct != null ? "${pct}%" : 'not measured'
        def note = met ? '' : "<span style='font-size:0.68rem;color:#92400e;font-weight:700;'>below the required level</span>"
        return "<div style='margin-top:4px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding-left:25px;'><span style='font-size:1.05rem;font-weight:800;color:${color};'>${label}</span><span style='font-size:0.72rem;color:#64748b;'>line coverage &bull; required&nbsp;<b>${minRequired as int}%</b> (library policy)</span>${note}</div>"
    }
    @NonCPS protected String nexusIqLimitLine(Map counts, Map limits) {
        Map lim = limits ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]
        def c = vulnLimitBadge((counts.get('critical') ?: 0) as int, (lim.get('maxCritical') ?: 0) as int)
        def h = vulnLimitBadge((counts.get('high') ?: 0) as int, (lim.get('maxHigh') ?: 0) as int)
        def m = vulnLimitBadge((counts.get('medium') ?: 0) as int, (lim.get('maxMedium') ?: 0) as int)
        return "<div style='margin-top:2px;font-size:0.74rem;padding-left:25px;'>C:&nbsp;${c}&nbsp;&nbsp;S:&nbsp;${h}&nbsp;&nbsp;M:&nbsp;${m}</div>"
    }
    @NonCPS protected Map coverageFor(Map ctx, String project) {
        Map perProject = (ctx.projectsCoverage ?: [:]) as Map
        if (project && perProject.containsKey(project)) return perProject.get(project) as Map
        return (ctx.coverage ?: [:]) as Map
    }
    @NonCPS protected String coverageCard(Map coverage, Map projectsCoverage = [:]) {
        Map rows = [:]
        if (projectsCoverage && projectsCoverage.size() > 1) rows.putAll(projectsCoverage)
        else if (coverage && coverage.get('enabled')) rows.put('', coverage)
        if (rows.isEmpty()) return ''
        StringBuilder body = new StringBuilder()
        rows.each { project, value -> body.append(coverageBar(project as String, value as Map)) }
        return card('Code Coverage', body.toString())
    }
    @NonCPS protected String coverageBar(String project, Map coverage) {
        boolean measured = coverage?.get('enabled') ? true : false
        double pct = measured ? ((coverage.get('line') ?: 0.0) as double) : 0.0d
        def covered = coverage?.get('covered') ?: 0
        def total = coverage?.get('total') ?: 0
        def minRequired = coverage?.get('minRequired') ?: 60
        String color = (measured && pct >= (minRequired as double)) ? '#22c55e' : '#d97706'
        double width = pct > 100.0d ? 100.0d : pct
        String label = project ? "<div style='font-size:0.78rem;font-weight:700;color:#1d4ed8;margin:6px 0 2px;'>${esc(project)}</div>" : ''
        String value = measured ? "${pct}%" : 'n/a'
        String detail = measured ? "${covered} covered / ${total} total &nbsp;&bull;&nbsp; required: ${minRequired}%" : "no coverage report &nbsp;&bull;&nbsp; required: ${minRequired}%"
        return "${label}<div style='display:flex;align-items:center;gap:12px;margin-bottom:6px;'><div style='font-size:1.6rem;font-weight:800;color:${color};min-width:70px;'>${value}</div><div style='flex:1;'><div style='background:#e2e8f0;border-radius:3px;height:12px;overflow:hidden;'><div style='width:${width}%;height:100%;background:${color};border-radius:3px;'></div></div><div style='margin-top:4px;font-size:0.72rem;color:#64748b;'>${detail}</div></div></div>"
    }
    @NonCPS protected String sonarBadgesCards(Map ctx) {
        Map projectsSonarResults = ctx.projectsSonarResults as Map
        if (projectsSonarResults.isEmpty()) return ''
        Map projectsAllCfg = ctx.projectsAllCfg as Map
        def sb = new StringBuilder()
        for (int i = 0; i < (ctx.projectKeys as List).size(); i++) {
            String project = (ctx.projectKeys as List).get(i)?.toString() ?: ''
            def cfg = project ? projectsAllCfg.get(project) : null
            def projectKey = cfg ? cfg.get('tools')?.get('sonar')?.get('projectKey') : ctx.sonarProjectKey
            def badgeToken = cfg ? cfg.get('tools')?.get('sonar')?.get('badgeToken') : ctx.sonarBadgeToken
            def serverUrl = (cfg ? cfg.get('tools')?.get('sonar')?.get('serverUrl') : null) ?: 'https://tools.bbh.com/sonar'
            if (!projectKey || !badgeToken) continue
            def badgesHtml = new StringBuilder()
            for (int bi = 0; bi < sonarBadges.size(); bi++) {
                def badge = sonarBadges.get(bi)
                def label = esc(badge.get('label') as String)
                badgesHtml.append("<div style='display:flex;flex-direction:column;align-items:center;gap:3px;'><img src='${serverUrl}/api/project_badges/measure?project=${projectKey}&metric=${badge.get('metric')}&token=${badgeToken}' alt='${label}' style='height:22px;'><span style='font-size:0.65rem;color:#6b7280;text-align:center;'>${label}</span></div>")
            }
            def title = (ctx.isMulti as boolean) ? "SonarQube Metrics - ${esc(project)}" : 'SonarQube Metrics'
            def body = "<div style='display:flex;flex-wrap:wrap;gap:12px;align-items:flex-start;justify-content:flex-start;'>${badgesHtml.toString()}</div>"
            sb.append(cardRaw(title, body, 'margin-top:16px;width:100%;'))
        }
        return sb.toString()
    }
    @NonCPS protected String securityGatesBanner(Map stageResults) {
        for (def name : secStageNames) {
            String status = (stageResults.get(name) ?: 'SKIP') as String
            if (runStatuses.contains(status) || status == 'NOT_REQUIRED') return ''
        }
        return banner('TESTS WERE NOT EXECUTED')
    }
    @NonCPS protected boolean showsReleasePolicy() {
        return variant != 'sast'
    }
    @NonCPS protected String failureReasonHtml(String firstFailed) {
        if (!firstFailed) return ''
        if (secStageNames.contains(firstFailed)) {
            return "<div style='margin-top:6px;background:rgba(0,0,0,0.25);border-radius:3px;padding:5px 10px;font-size:0.8rem;letter-spacing:0.1px;'>Stage: ${esc(firstFailed)}<br><b>Build failed due to Security policies are not fulfilled!</b></div>"
        }
        return "<div style='margin-top:6px;background:rgba(0,0,0,0.25);border-radius:3px;padding:5px 10px;font-size:0.8rem;font-weight:600;letter-spacing:0.1px;'>Failed at stage: ${esc(firstFailed)}</div>"
    }
    @NonCPS protected String headerBackground(String buildResult) {
        if (buildResult == 'SUCCESS')  return 'linear-gradient(135deg,#15803d,#22c55e)'
        if (buildResult == 'FAILURE')  return 'linear-gradient(135deg,#b91c1c,#ef4444)'
        if (buildResult == 'UNSTABLE') return 'linear-gradient(135deg,#b45309,#f59e0b)'
        return 'linear-gradient(135deg,#1d4ed8,#3b82f6)'
    }

    @NonCPS
    String buildHtml(String buildResult, String now, String jobName, String buildNumber, String buildUrl,
                     Map stageResults, Map stageErrors, Map stageTimes, Map vulnCounts, Map policyLimits, Map coverage, Map stageDetails,
                     Map stageLinks, Map sonarResults, Map nexusIqResults, Map remoteTestResults,
                     String sonarProjectKey = '', String sonarBadgeToken = '',
                     Map projectsVulnCounts = [:], Map projectsNexusIQ = [:], Map projectsScanResults = [:],
                     Map projectsSonarResults = [:], List projectKeys = [], Map projectsAllCfg = [:], Map releaseGate = [:],
                     Map projectsRemoteTestResults = [:], Map projectsGoldenFix = [:], Map projectsCoverage = [:]) {

        String firstFailed = ''
        int firstFailedIdx = -1
        for (int i = 0; i < stageOrder.size(); i++) {
            if (stageResults.get(stageOrder.get(i)) == 'FAIL') {
                firstFailed = stageOrder.get(i)
                firstFailedIdx = i
                break
            }
        }
        List keys = projectKeys.isEmpty() ? [''] : projectKeys
        boolean isMulti = keys.size() > 1 && keys[0] != ''

        Map ctx = [
                buildUrl: buildUrl, artifactBase: buildUrl + 'artifact/',
                stageResults: stageResults, stageErrors: stageErrors, stageTimes: stageTimes,
                vulnCounts: vulnCounts, policyLimits: policyLimits, coverage: coverage, stageLinks: stageLinks,
                sonarResults: sonarResults, nexusIqResults: nexusIqResults, remoteTestResults: remoteTestResults,
                sonarProjectKey: sonarProjectKey, sonarBadgeToken: sonarBadgeToken,
                projectsVulnCounts: projectsVulnCounts, projectsNexusIQ: projectsNexusIQ,
                projectsScanResults: projectsScanResults, projectsSonarResults: projectsSonarResults,
                projectsRemoteTestResults: projectsRemoteTestResults, projectsGoldenFix: projectsGoldenFix, projectsCoverage: projectsCoverage,
                projectsAllCfg: projectsAllCfg, projectKeys: keys, isMulti: isMulti,
                releaseGate: releaseGate, firstFailedIdx: firstFailedIdx
        ]

        boolean hasMulti = projectsVulnCounts && !projectsVulnCounts.isEmpty()
        String secRows = hasMulti
                ? buildProjectsSecRows(projectsVulnCounts, projectsNexusIQ, projectsScanResults, projectsSonarResults, policyLimits, ctx.artifactBase as String, projectsGoldenFix)
                : buildSingleProjectSecRows(ctx)

        String securityGates = "<h2>Security Gates</h2>${showsReleasePolicy() ? releaseGateBanner(releaseGate) : ''}${securityGatesBanner(stageResults)}<div style='overflow-x:auto;'><table style='min-width:640px;'>" +
                "<thead><tr><th>${hasMulti ? 'Project / Scanner' : 'Scanner'}</th>" +
                "<th style='text-align:center;'>Critical</th><th style='text-align:center;'>High</th><th style='text-align:center;'>Medium</th><th style='text-align:center;'>Low</th>" +
                "<th style='text-align:center;'>Policy</th><th style='text-align:center;white-space:nowrap;'>Report / Dashboard</th></tr></thead>" +
                "<tbody>${secRows}</tbody></table></div>"

        return """<!DOCTYPE html>
<html lang='en'>
<head>
<meta charset='UTF-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Pipeline Report - ${esc(jobName)} #${esc(buildNumber)}</title>
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; color: #1f2937; min-height: 100vh; }
.container { margin: 0 auto; padding: 16px; }
h2 { font-size: 0.95rem; margin-bottom: 12px; color: #111827; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px; }
.card { background: #fff; border-radius: 4px; padding: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); border: 1px solid #e2e8f0; }
table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
th { background: #f8fafc; padding: 10px 12px; text-align: left; font-weight: 600; color: #374151; border-bottom: 2px solid #e2e8f0; white-space: nowrap; }
td { border-bottom: 1px solid #f1f5f9; }
tr:last-child td { border-bottom: none; }
</style>
</head>
<body>
<div class='container' style='max-width:1250px;width:100%;box-sizing:border-box;'>
<div style='background:${headerBackground(buildResult)};border-radius:4px;padding:12px 16px;color:#fff;box-shadow:0 2px 8px rgba(0,0,0,0.15);margin-bottom:16px;overflow:hidden;'>
<div style='font-size:1.4rem;font-weight:800;letter-spacing:-0.5px;'>${buildResult ?: 'IN_PROGRESS'}</div>
<div style='margin-top:4px;opacity:0.9;font-size:0.85rem;'>${esc(jobName)} &nbsp;&bull;&nbsp; Build #${esc(buildNumber)}</div>
<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>${now}</div>
${failureReasonHtml(firstFailed)}
</div>
<div style='display:flex;gap:20px;align-items:flex-start;flex-wrap:wrap;'>
<div style='flex:1;min-width:360px;margin-top:0;background:#f1f5f9;border-radius:0;padding:16px;box-shadow:0 2px 6px rgba(0,0,0,0.10);border:1px solid #cbd5e1;'>
<h2>Pipeline Stages</h2>
<div style='display:flex;flex-direction:column;gap:0;'>${stageFlowHtml(ctx)}</div>
</div>
<div style='flex:1.4;min-width:620px;'>
<div class='card' style='margin-top:0;'>
${securityGates}
</div>
${showsReleasePolicy() ? releaseGateCardHtml(releaseGate) : ''}
${goldenFixCardHtml(projectsGoldenFix, isMulti)}
${sonarBadgesCards(ctx)}
${coverageCard(coverage, projectsCoverage)}
${testJobsCardHtml('Smoke tests', 'Smoke tests', projectsRemoteTestResults, remoteTestResults)}
</div>
</div>
</div>
</body>
</html>"""
    }

    @NonCPS
    static List runStatuses() {
        return ['PASS', 'FAIL', 'WARN']
    }

    @NonCPS
    static List sonarBadges() {
        return [
                [label: 'Duplicated lines', metric: 'duplicated_lines_density'], [label: 'Lines of code', metric: 'ncloc'],
                [label: 'Quality gate status', metric: 'alert_status'], [label: 'Security hotspots', metric: 'security_hotspots'],
                [label: 'Bugs', metric: 'bugs'], [label: 'Code smells', metric: 'code_smells'],
                [label: 'Vulnerabilities', metric: 'vulnerabilities'], [label: 'Maintainability rating', metric: 'sqale_rating'],
                [label: 'Reliability rating', metric: 'reliability_rating'], [label: 'Security rating', metric: 'security_rating'],
                [label: 'Technical debt', metric: 'sqale_index']
        ]
    }

    @NonCPS
    static Map layout(String variant) {
        String monitor     = 'Monitor source changes (download sources)'
        String unit        = 'Unit tests'
        String niq         = 'Dependencies scan (Nexus IQ)'
        String sast        = 'SAST - Static Application Security Tests - HCL AppScan'
        String sonar       = 'SCA (SonarQube)'
        String snapshot    = 'Nexus delivery (Static analysis passed)'
        String deployRd    = 'Lower test region deployment'
        String regression  = 'Regression tests (>60% user stories coverage)'
        String smoke       = 'Smoke tests'
        String performance = 'Performance tests'
        String dast        = 'DAST - Dynamic Application Security Tests - HCL AppScan'
        String release     = 'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities'
        String deployQc    = 'Higher test environment deployment'

        Map sastScanner = [key: 'sast', label: 'SAST (AppScan)', file: 'appscan-report.html', stage: sast]
        Map dastScanner = [key: 'dast', label: 'DAST (AppScan)', file: 'appscan-dast-report.html', pdf: 'appscan-dast-report.pdf', stage: dast]

        if (variant == 'sast') {
            return [
                    appscanScnrs  : [sastScanner],
                    stageOrder    : [monitor, sast],
                    secStageNames : [sast],
                    phaseGroups   : [
                            [label: 'Dev', color: '#3b82f6', stages: [monitor]],
                            [label: 'Static security scan', color: '#2563eb', stages: [sast]]
                    ],
                    reportsToParse: ['sast']
            ]
        }
        if (variant == 'security') {
            return [
                    appscanScnrs  : [sastScanner],
                    stageOrder    : [monitor, unit, niq, sast, sonar, snapshot],
                    secStageNames : [sast, sonar, niq],
                    phaseGroups   : [
                            [label: 'Dev', color: '#3b82f6', stages: [monitor, unit]],
                            [label: 'Static security & code quality scans', color: '#2563eb', stages: [niq, sast, sonar]],
                            [label: 'Delivery', color: '#172554', stages: [snapshot]]
                    ],
                    reportsToParse: ['sast']
            ]
        }
        if (variant == 'extended') {
            return [
                    appscanScnrs  : [dastScanner],
                    stageOrder    : [monitor, deployRd, regression, smoke, performance, dast, release, deployQc],
                    secStageNames : [dast],
                    phaseGroups   : [
                            [label: 'Dev', color: '#3b82f6', stages: [monitor]],
                            [label: 'Delivery', color: '#1d4ed8', stages: [deployRd]],
                            [label: 'Tests', color: '#1e40af', stages: [regression, smoke, performance]],
                            [label: 'Security', color: '#1e3a8a', stages: [dast]],
                            [label: 'Delivery', color: '#172554', stages: [release, deployQc]]
                    ],
                    reportsToParse: ['dast']
            ]
        }
        return [
                appscanScnrs  : [sastScanner, dastScanner],
                stageOrder    : [monitor, unit, niq, sast, sonar, snapshot, deployRd, regression, smoke, performance, dast, release, deployQc],
                secStageNames : [sast, sonar, dast, niq],
                phaseGroups   : [
                        [label: 'Development', color: '#3b82f6', stages: [monitor, unit]],
                        [label: 'Static security & code quality scans', color: '#2563eb', stages: [niq, sast, sonar]],
                        [label: 'Delivery', color: '#1d4ed8', stages: [snapshot, deployRd]],
                        [label: 'Tests', color: '#1e40af', stages: [regression, smoke, performance]],
                        [label: 'Security', color: '#1e3a8a', stages: [dast]],
                        [label: 'Delivery', color: '#172554', stages: [release, deployQc]]
                ],
                reportsToParse: ['sast', 'dast']
        ]
    }
}
