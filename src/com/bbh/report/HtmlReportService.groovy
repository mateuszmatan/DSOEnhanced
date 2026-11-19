package com.bbh.report

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.AppScanReportParser
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

import java.text.SimpleDateFormat

class HtmlReportService implements Serializable {

    protected final int TEST_JOBS_INLINE_LIMIT = 10

    protected final String CELL        = 'text-align:center;padding:6px 10px;'
    protected final String CELL_NOWRAP = 'text-align:center;padding:6px 10px;white-space:nowrap;'
    protected final String LINK_COLOR  = '#0369a1'
    protected final String PDF_COLOR   = '#b91c1c'
    protected final String PR_COLOR    = '#15803d'
    protected final String MUTED       = '#9ca3af'
    protected final String OK_BG       = '#f0fdf4'
    protected final String OK_COLOR    = '#166534'
    protected final String BAD_BG      = '#fee2e2'
    protected final String BAD_COLOR   = '#991b1b'

    protected final String NIQ_STAGE   = 'Dependencies scan (Nexus IQ)'
    protected final String NODE_STAGE  = 'Node.js build'
    protected final String DAST_STAGE  = 'DAST - Dynamic Application Security Tests - HCL AppScan'
    protected final List TEST_STAGES   = ['Smoke tests', 'Regression tests (>60% user stories coverage)', 'Performance tests']

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
        Map stageLayout = ReportViewModel.layout(this.variant, state.stageResults.containsKey(NODE_STAGE))
        this.appscanScnrs = stageLayout.appscanScnrs
        this.stageOrder = stageLayout.stageOrder
        this.secStageNames = stageLayout.secStageNames
        this.phaseGroups = stageLayout.phaseGroups
        this.reportsToParse = stageLayout.reportsToParse
        this.runStatuses = ReportViewModel.runStatuses()
        this.sonarBadges = ReportViewModel.sonarBadges()
    }

    void generate() {
        for (def warning : (BuildUtils.manualEffort(state.cfgDefaults ?: [:]).warnings as List)) {
            script.echo "[WARN] [REPORT] ${warning}"
        }
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
                (state.sonarResults?.badgeToken ?: state.cfg?.tools?.sonar?.badgeToken ?: '') as String,
                new HashMap(state.projectsVulnCounts     ?: [:]),
                new HashMap(state.projectsNexusIqResults ?: [:]),
                new HashMap(state.projectsScanResults    ?: [:]),
                new HashMap(state.projectsSonarResults   ?: [:]),
                projectKeys,
                new HashMap(state.projectsAllCfg ?: [:]),
                new com.bbh.core.ReleaseGate(script, state).evaluate(),
                new HashMap(state.projectsRemoteTestResults ?: [:]),
                new HashMap(state.projectsGoldenFix ?: [:]),
                new HashMap(state.projectsCoverage ?: [:]),
                new HashMap(state.projectsUnitTests ?: [:]),
                new HashMap(state.projectsDatabase ?: [:])
        )
        if (os.isWindows()) {
            os.mkdirP("${script.env.WORKSPACE}\\report")
        } else {
            script.sh "mkdir -p ${script.env.WORKSPACE}/report"
        }
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
        for (def k : (reportsToParse ?: [])) {
            String scanner = k as String
            Map counts = parseScannerReport(scanner, policy.reportPath(scanner))
            if (counts != null) {
                state.vulnCounts[scanner] = counts
                vulnCountsLocal[scanner]  = counts
            }
            reconcileProjects(scanner)
        }
    }

    protected Map parseScannerReport(String scanner, String path) {
        def parsed = parseAppScanReport(path)
        if (!parsed?.parsed) return null
        return [
                critical: (parsed.critical ?: 0) as int,
                high:     (parsed.high     ?: 0) as int,
                medium:   (parsed.medium   ?: 0) as int,
                low:      (parsed.low      ?: 0) as int
        ]
    }

    protected void reconcileProjects(String scanner) {
        for (def entry : (state.projectsScanResults ?: [:]).entrySet()) {
            String project = entry.key as String
            Map projScan = (entry.value ?: [:]) as Map
            String file = (projScan.get("${scanner}_file".toString()) ?: '') as String
            if (!file) continue
            Map counts = parseScannerReport(scanner, "${script.env.WORKSPACE}/${file}")
            if (counts == null) continue
            applyReconciled(project, scanner, projScan, counts)
        }
    }

    protected void applyReconciled(String project, String scanner, Map projScan, Map counts) {
        Map recorded = ((state.projectsVulnCounts[project] ?: [:]) as Map).get(scanner) as Map
        if (recorded != null && sameCounts(recorded, counts)) return
        if (!state.projectsVulnCounts[project]) state.projectsVulnCounts[project] = [:]
        state.projectsVulnCounts[project][scanner] = counts
        String status = computePolicy(counts, (state.policyLimits[scanner] ?: [:]) as Map)
        if (projScan.get(scanner) != 'NOT_REQUIRED') projScan[scanner] = status
        script.echo "[REPORT] ${project} ${scanner.toUpperCase()}: the downloaded report lists " +
                "Critical=${counts.critical} High=${counts.high} Medium=${counts.medium} Low=${counts.low}" +
                (recorded != null ? " instead of Critical=${recorded.critical ?: 0} High=${recorded.high ?: 0} " +
                        "Medium=${recorded.medium ?: 0} Low=${recorded.low ?: 0} recorded during the scan" : '') +
                " - the report and the release gate use the numbers from the report (policy ${status})."
    }

    @NonCPS protected boolean sameCounts(Map a, Map b) {
        return ReportViewModel.sameCounts(a, b)
    }

    protected Map parseAppScanReport(String path) {
        if (!path || !script.fileExists(path)) return [parsed: false]
        try {
            return AppScanReportParser.counts(script.readFile(path) as String)
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

    @NonCPS protected String esc(String s) { return HtmlReportCardsView.esc(s) }
    @NonCPS protected String td(String content, String style) { return HtmlReportCardsView.td(content, style) }
    @NonCPS protected String centerTd(String content, String extraStyle = '') { return HtmlReportCardsView.centerTd(content, extraStyle) }
    @NonCPS protected String dashTd(String extraStyle = '') { return HtmlReportCardsView.dashTd(extraStyle) }
    @NonCPS protected String link(String url, String label, String color = LINK_COLOR, String extraStyle = 'font-weight:600;') { return HtmlReportCardsView.link(url, label, color, extraStyle) }
    @NonCPS protected String mutedDash() { return HtmlReportCardsView.mutedDash() }
    @NonCPS protected String cardRaw(String titleHtml, String body, String extraStyle) { return HtmlReportCardsView.cardRaw(titleHtml, body, extraStyle) }
    @NonCPS protected String card(String title, String body) { return HtmlReportCardsView.card(title, body) }
    @NonCPS protected String banner(String text) { return HtmlReportCardsView.banner(text) }
    @NonCPS protected String badge(String status) { return HtmlReportCardsView.badge(status) }
    @NonCPS protected String vulnCell(int count, int limit) { return HtmlReportCardsView.vulnCell(count, limit) }
    @NonCPS protected String niqCountCell(int count, int limit) { return HtmlReportCardsView.niqCountCell(count, limit) }
    @NonCPS protected String sonarCountCell(int count, int limit) { return HtmlReportCardsView.sonarCountCell(count, limit) }
    @NonCPS protected String sonarCountCell(int count) { return HtmlReportCardsView.sonarCountCell(count) }
    @NonCPS protected String vulnCountBadge(int val) { return HtmlReportCardsView.vulnCountBadge(val) }
    @NonCPS protected String vulnLimitBadge(int val, int limit) { return HtmlReportCardsView.vulnLimitBadge(val, limit) }
    @NonCPS protected String formatDuration(long ms) { return ReportViewModel.formatDuration(ms) }
    @NonCPS protected String arrow() { return HtmlReportCardsView.arrow() }
    @NonCPS protected String linkBtn(String url, String label) { return HtmlReportCardsView.linkBtn(url, label) }
    @NonCPS protected String policyStatusFor(String key, Map counts, Map policyLimits) { return ReportViewModel.policyStatusFor(key, counts, policyLimits) }
    @NonCPS protected String computePolicy(Map counts, Map limits) { return ReportViewModel.computePolicy(counts, limits) }

    @NonCPS protected String testJobsHtml(List jobs) { return HtmlReportCardsView.testJobsHtml(jobs, TEST_JOBS_INLINE_LIMIT) }
    @NonCPS protected String testJobRowsHtml(List jobs) { return HtmlReportCardsView.testJobRowsHtml(jobs) }
    @NonCPS protected boolean isTestJobOk(String status) { return ReportViewModel.isTestJobOk(status) }
    @NonCPS protected Map testJobsSummary(List jobs) { return ReportViewModel.testJobsSummary(jobs) }
    @NonCPS protected String testJobsSummaryHtml(List jobs) { return HtmlReportCardsView.testJobsSummaryHtml(jobs) }
    @NonCPS protected String testJobStatusColor(String status) { return ReportViewModel.testJobStatusColor(status) }
    @NonCPS protected String testJobRowHtml(Map job, int i) { return HtmlReportCardsView.testJobRowHtml(job, i) }
    @NonCPS protected String testJobsCardHtml(String title, String stageName, Map projectsRemoteTestResults, Map remoteTestResults) { return HtmlReportCardsView.testJobsCardHtml(title, stageName, projectsRemoteTestResults, remoteTestResults) }
    @NonCPS protected Map testJobGroups(String stageName, Map projectsRemoteTestResults, Map remoteTestResults) { return ReportViewModel.testJobGroups(stageName, projectsRemoteTestResults, remoteTestResults) }
    @NonCPS protected String testJobsGroupHeader(String project, List jobs) { return HtmlReportCardsView.testJobsGroupHeader(project, jobs) }
    @NonCPS protected String testJobTableRow(Map job, int i) { return HtmlReportCardsView.testJobTableRow(job, i) }

    @NonCPS protected Map goldenFixFor(Map projectsGoldenFix, String projectName) { return ReportViewModel.goldenFixFor(projectsGoldenFix, projectName) }
    @NonCPS protected boolean goldenFixHasPr(Map gf) { return ReportViewModel.goldenFixHasPr(gf) }
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
    @NonCPS protected List projectReasonParts(String error, String project) { return ReportViewModel.projectReasonParts(error, project) }
    @NonCPS protected String projectReason(String error, String project) { return ReportViewModel.projectReason(error, project) }
    @NonCPS protected String goldenFixInlineHtml(Map gf) {
        if (!gf) return ''
        if (goldenFixHasPr(gf)) {
            def count = ((gf.get('changes') ?: []) as List).size()
            def prLink = "<a href='${esc(gf.get('prUrl') as String)}' target='_blank' style='color:${LINK_COLOR};'>${esc(gf.get('prTitle') as String)}</a>"
            return "<div style='margin-top:4px;padding-left:25px;font-size:0.72rem;color:${OK_COLOR};font-weight:600;'>Automatic dependency upgrade proposed for review: ${prLink} (${count} change(s))</div>"
        }
        Map plain = goldenFixPlainText(gf)
        def color = gf.get('status') == 'ERROR' ? BAD_COLOR : '#64748b'
        return "<div style='margin-top:4px;padding-left:25px;font-size:0.72rem;color:${color};'><b>${esc(plain.headline as String)}</b> ${esc(plain.explanation as String)}</div>"
    }
    @NonCPS protected Map goldenFixPlainText(Map gf) { return ReportViewModel.goldenFixPlainText(gf, state.settingsPlace()) }
    @NonCPS protected String goldenFixDetail(Map gf) { return ReportViewModel.goldenFixDetail(gf) }
    @NonCPS protected String goldenFixCardHtml(Map projectsGoldenFix, boolean isMulti) { return HtmlReportCardsView.goldenFixCardHtml(projectsGoldenFix, isMulti, state.settingsPlace()) }
    @NonCPS protected String goldenFixSastCommentsList(List comments) { return HtmlReportCardsView.goldenFixSastCommentsList(comments) }
    @NonCPS protected String goldenFixStatusBanner(Map gf) { return HtmlReportCardsView.goldenFixStatusBanner(gf, state.settingsPlace()) }
    @NonCPS protected String goldenFixChangesTable(List changes) { return HtmlReportCardsView.goldenFixChangesTable(changes) }
    @NonCPS protected String goldenFixUnresolvedList(List unresolved) { return HtmlReportCardsView.goldenFixUnresolvedList(unresolved) }
    @NonCPS protected String goldenFixNotes(List notes) { return HtmlReportCardsView.goldenFixNotes(notes) }
    @NonCPS protected String releaseGateBanner(Map releaseGate) { return HtmlReportCardsView.releaseGateBanner(releaseGate) }
    @NonCPS protected String releaseGateCardHtml(Map releaseGate) { return HtmlReportCardsView.releaseGateCardHtml(releaseGate) }

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
    @NonCPS protected Map stageBoxPalette(String status) { return HtmlReportCardsView.stageBoxPalette(status) }
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

    @NonCPS protected String buildProjectsSecRows(Map projectsVulnCounts, Map projectsNexusIQ, Map projectsScanResults,
                                                  Map projectsSonarResults, Map policyLimits, String artifactBase,
                                                  Map projectsGoldenFix = [:]) {
        def sb = new StringBuilder()
        projectsVulnCounts.each { projectName, scannersMap ->
            String project = projectName as String
            Map scanners = (scannersMap ?: [:]) as Map
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
            if (variant != 'nexusiq') sb.append(sonarSingleRow(ctx))
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
                    String number = baseName == NODE_STAGE ? '0' : "${stageNumber}"
                    String displayNum = isMulti ? "${number}.${pi + 1}" : number
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
                if (baseName != NODE_STAGE) stageNumber++
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
                boolean met = projectCoverage.get('notRequired') ? true : (projectCoverage.get('enabled') && ((projectCoverage.get('line') ?: 0.0) as double) >= ((projectCoverage.get('minRequired') ?: 60) as double))
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
        if (coverage?.get('notRequired')) return "<div style='margin-top:4px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding-left:25px;'><span style='font-size:1.05rem;font-weight:800;color:${MUTED};'>not required</span><span style='font-size:0.72rem;color:#64748b;'>Code Coverage &bull; no utPLSQL run for this SQL and PL/SQL service</span></div>"
        boolean enabled = coverage?.get('enabled') ? true : false
        Double pct = enabled ? ((coverage.get('line') ?: 0.0) as double) : null
        double minRequired = ((coverage?.get('minRequired') ?: 60) as double)
        boolean met = pct != null && pct >= minRequired
        def color = met ? '#16a34a' : '#d97706'
        def label = pct != null ? "${pct}%" : 'not measured'
        def note = met ? '' : "<span style='font-size:0.68rem;color:#92400e;font-weight:700;'>below the required level</span>"
        int counted = (coverage?.get('counted') ?: 0) as int
        String mean = counted > 1 ? "mean of ${counted} reports &bull; " : ''
        return "<div style='margin-top:4px;display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding-left:25px;'><span style='font-size:1.05rem;font-weight:800;color:${color};'>${label}</span><span style='font-size:0.72rem;color:#64748b;'>Code Coverage &bull; ${mean}required&nbsp;<b>${minRequired as int}%</b> &bull; BBH SLDC policy</span>${note}</div>"
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
    @NonCPS protected String coverageCard(Map coverage, Map projectsCoverage = [:]) { return HtmlReportCardsView.coverageCard(coverage, projectsCoverage) }
    @NonCPS protected String coverageBar(String project, Map coverage) { return HtmlReportCardsView.coverageBar(project, coverage) }
    @NonCPS protected String coverageReportsTable(Map projectsCoverage) { return HtmlReportCardsView.coverageReportsTable(projectsCoverage) }
    @NonCPS protected String unitTestsCard(Map projectsUnitTests) { return HtmlReportCardsView.unitTestsCard(projectsUnitTests) }

    @NonCPS protected String sonarBadgesCards(Map ctx) {
        Map projectsSonarResults = ctx.projectsSonarResults as Map
        if (projectsSonarResults.isEmpty()) return ''
        Map projectsAllCfg = ctx.projectsAllCfg as Map
        def sb = new StringBuilder()
        for (int i = 0; i < (ctx.projectKeys as List).size(); i++) {
            String project = (ctx.projectKeys as List).get(i)?.toString() ?: ''
            def cfg = project ? projectsAllCfg.get(project) : null
            def projSonar = project ? ((projectsSonarResults.get(project) ?: [:]) as Map) : (ctx.sonarResults as Map)
            def projectKey = (projSonar?.get('projectKey') ?: (cfg ? cfg.get('tools')?.get('sonar')?.get('projectKey') : null) ?: ctx.sonarProjectKey)
            def badgeToken = cfg ? (projSonar?.get('badgeToken') ?: cfg.get('tools')?.get('sonar')?.get('badgeToken')) : ctx.sonarBadgeToken
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
        return variant != 'sast' && variant != 'nexusiq'
    }
    @NonCPS protected String failureReasonHtml(String firstFailed) {
        if (!firstFailed) return ''
        if (secStageNames.contains(firstFailed)) {
            return "<div style='margin-top:6px;background:rgba(0,0,0,0.25);border-radius:3px;padding:5px 10px;font-size:0.8rem;letter-spacing:0.1px;'>Stage: ${esc(firstFailed)}<br><b>Build failed due to Security policies are not fulfilled!</b></div>"
        }
        return "<div style='margin-top:6px;background:rgba(0,0,0,0.25);border-radius:3px;padding:5px 10px;font-size:0.8rem;font-weight:600;letter-spacing:0.1px;'>Failed at stage: ${esc(firstFailed)}</div>"
    }
    @NonCPS protected String databaseCard(Map projectsDatabase, String artifactBase) {
        List packages = []
        List deployments = []
        (projectsDatabase ?: [:]).each { project, value ->
            Map db = (value ?: [:]) as Map
            Map pkg = (db.get('package') ?: [:]) as Map
            if (pkg.get('file')) {
                String file = "<a href='${esc(artifactBase + (pkg.get('path') as String))}' target='_blank' style='color:${LINK_COLOR};font-family:monospace;font-size:0.74rem;'>${esc(pkg.get('file') as String)}</a>"
                String review = pkg.get('reviewPath') ? "<a href='${esc(artifactBase + (pkg.get('reviewPath') as String))}' target='_blank' style='color:${LINK_COLOR};'>review SQL</a>" : (pkg.get('fetched') ? 'delivered snapshot' : '-')
                int findings = (pkg.get('findings') ?: 0) as int
                String checks = findings > 0 && pkg.get('staticChecksPath')
                        ? "<a href='${esc(artifactBase + (pkg.get('staticChecksPath') as String))}' target='_blank' style='color:#d97706;font-weight:700;'>${findings}</a>"
                        : String.valueOf(findings)
                Map tests = (db.get('tests') ?: [:]) as Map
                String ut = tests.get('ran') ? "${tests.get('total') ?: 0} / ${tests.get('failed') ?: 0} failed" : 'not run'
                packages << ("<tr>" + td(esc(project as String), 'padding:5px 10px;font-weight:600;')
                        + td(file, 'padding:5px 10px;overflow-wrap:anywhere;')
                        + td("${pkg.get('changesets') ?: 0} / ${pkg.get('reviewChangesets') ?: 0}", 'text-align:center;padding:5px 8px;white-space:nowrap;')
                        + td(review, 'padding:5px 10px;')
                        + td(checks, 'text-align:center;padding:5px 8px;')
                        + td(esc(ut), 'text-align:center;padding:5px 8px;white-space:nowrap;')
                        + td(esc("${db.get('liquibase') ?: ''}".toString()), 'padding:5px 10px;white-space:nowrap;')
                        + "</tr>")
            }
            ((db.get('targets') ?: [:]) as Map).each { target, row ->
                Map t = (row ?: [:]) as Map
                String reason = t.get('reason') ? "<div style='font-size:0.68rem;color:#64748b;'>${esc(t.get('reason') as String)}</div>" : ''
                int invalid = (t.get('invalidObjects') ?: 0) as int
                int errors = (t.get('compileErrors') ?: 0) as int
                int before = (t.get('invalidBefore') ?: 0) as int
                String beforeNote = before > 0 ? "<div style='font-size:0.68rem;color:#64748b;font-weight:400;'>${before} invalid before</div>" : ''
                deployments << ("<tr>" + td(esc(project as String), 'padding:5px 10px;font-weight:600;')
                        + td(esc((target as String).toUpperCase()) + reason, 'padding:5px 10px;')
                        + td(badge((t.get('status') ?: 'SKIP') as String), 'text-align:center;padding:5px 8px;')
                        + td(esc((t.get('contexts') ?: '-') as String), 'padding:5px 10px;font-family:monospace;font-size:0.74rem;')
                        + td("${t.get('pendingBefore') ?: 0} / ${t.get('applied') ?: 0} / ${t.get('pendingAfter') ?: 0}", 'text-align:center;padding:5px 8px;white-space:nowrap;')
                        + td("${invalid} / ${errors}${beforeNote}", "text-align:center;padding:5px 8px;font-weight:700;color:${invalid + errors > 0 ? '#dc2626' : '#334155'};")
                        + td(esc((t.get('tag') ?: '-') as String), 'padding:5px 10px;font-family:monospace;font-size:0.74rem;overflow-wrap:anywhere;')
                        + "</tr>")
            }
        }
        if (!packages && !deployments) return ''
        StringBuilder body = new StringBuilder()
        if (packages) body.append("<div style='overflow-x:auto;'><table style='min-width:620px;'><thead><tr><th>Service</th><th>Package</th><th style='text-align:center;'>Changesets / for RD and QC</th><th>Review</th><th style='text-align:center;'>Static checks</th><th style='text-align:center;'>utPLSQL</th><th>Liquibase</th></tr></thead><tbody>${packages.join('')}</tbody></table></div>")
        if (deployments) body.append("<div style='overflow-x:auto;margin-top:8px;'><table style='min-width:620px;'><thead><tr><th>Service</th><th>Database</th><th style='text-align:center;'>Status</th><th>Contexts</th><th style='text-align:center;'>Pending / applied / left</th><th style='text-align:center;'>New invalid / errors</th><th>Tag</th></tr></thead><tbody>${deployments.join('')}</tbody></table></div>")
        body.append("<div style='margin-top:6px;font-size:0.68rem;color:${MUTED};'>The package is the zip delivered to Nexus; RD and QC are deployed from it. The library never rolls back.</div>")
        return card('Database changes', body.toString())
    }

    @NonCPS protected String settingsHtml() {
        return "<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>Settings from ${esc(state.settingsPlace())} in the repository</div>"
    }
    @NonCPS protected String autoResolvedCardHtml() {
        return HtmlReportCardsView.autoResolvedCard((state.projectsAutoResolved ?: [:]) as Map)
    }
    @NonCPS protected String headerBackground(String buildResult) { return HtmlReportCardsView.headerBackground(buildResult) }

    @NonCPS
    String buildHtml(String buildResult, String now, String jobName, String buildNumber, String buildUrl,
                     Map stageResults, Map stageErrors, Map stageTimes, Map vulnCounts, Map policyLimits, Map coverage, Map stageDetails,
                     Map stageLinks, Map sonarResults, Map nexusIqResults, Map remoteTestResults,
                     String sonarProjectKey = '', String sonarBadgeToken = '',
                     Map projectsVulnCounts = [:], Map projectsNexusIQ = [:], Map projectsScanResults = [:],
                     Map projectsSonarResults = [:], List projectKeys = [], Map projectsAllCfg = [:], Map releaseGate = [:],
                     Map projectsRemoteTestResults = [:], Map projectsGoldenFix = [:], Map projectsCoverage = [:], Map projectsUnitTests = [:], Map projectsDatabase = [:]) {

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
<div style='margin-top:2px;opacity:0.7;font-size:0.75rem;'>${now}</div>${settingsHtml()}
${failureReasonHtml(firstFailed)}
</div>
<div style='display:flex;gap:20px;align-items:flex-start;flex-wrap:wrap;'>
<div style='flex:1;min-width:360px;margin-top:0;background:#f1f5f9;border-radius:0;padding:16px;box-shadow:0 2px 6px rgba(0,0,0,0.10);border:1px solid #cbd5e1;'>
<h2>Pipeline Stages</h2>
<div style='display:flex;flex-direction:column;gap:0;'>${stageFlowHtml(ctx)}</div>${parallelStagesHtml()}
</div>
<div style='flex:1.4;min-width:620px;'>
<div class='card' style='margin-top:0;'>
${securityGates}
</div>
${showsReleasePolicy() ? releaseGateCardHtml(releaseGate) : ''}
${goldenFixCardHtml(projectsGoldenFix, isMulti)}
${sonarBadgesCards(ctx)}
${coverageCard(coverage, projectsCoverage)}${databaseCard(projectsDatabase, buildUrl + 'artifact/')}${unitTestsCard(projectsUnitTests) ? '\n' + unitTestsCard(projectsUnitTests) : ''}
${testJobsCardHtml('Smoke tests', 'Smoke tests', projectsRemoteTestResults, remoteTestResults)}${autoResolvedCardHtml()}
</div>
</div>${manualComparisonHtml(stageTimes, stageResults)}
</div>
</body>
</html>"""
    }

    @NonCPS
    protected String parallelStagesHtml() {
        List groups = (state.parallelGroups ?: []) as List
        if (!groups) return ''
        StringBuilder html = new StringBuilder()
        html.append("<section id='parallel-stages' style='margin-top:14px;background:#fff;border:1px solid #cbd5e1;padding:12px 14px;'><h2>Parallel stages</h2>")
        for (def g : groups) {
            Map group = g as Map
            long sequential = (group.sequentialMs ?: 0L) as long
            long actual = (group.actualMs ?: 0L) as long
            StringBuilder rows = new StringBuilder()
            for (def b : (group.branches ?: [])) {
                Map branch = b as Map
                long ms = (branch.ms ?: 0L) as long
                long waited = Math.min(ms, (branch.waitMs ?: 0L) as long)
                String note = waited > 0L ? " <span style='color:#64748b;'>(after waiting <b>${BuildUtils.formatDuration(waited)}</b> for the unit tests)</span>" : ''
                rows.append("<tr><td style='padding:5px 8px;'>${esc(branch.stage as String)}${note}</td><td style='padding:5px 8px;text-align:right;white-space:nowrap;'><b>${BuildUtils.formatDuration(ms - waited)}</b></td></tr>")
            }
            String saved = BuildUtils.parallelSaving(sequential, actual)
            String savedHtml = saved ? "<div style='margin-top:4px;color:#15803d;font-weight:700;'>Saved <b>${BuildUtils.formatDuration(sequential - actual)}</b> (${Math.round((sequential - actual) * 100.0d / sequential)}%)</div>" : ''
            html.append("<div style='margin-top:6px;font-weight:700;color:#0f172a;font-size:0.82rem;'>${esc(group.name as String)}</div>")
                    .append("<table style='margin-top:4px;'><tbody>${rows}</tbody></table>")
                    .append("<div style='margin-top:6px;font-size:0.8rem;color:#334155;'>One after another <b>${BuildUtils.formatDuration(sequential)}</b> &nbsp;&bull;&nbsp; in parallel <b>${BuildUtils.formatDuration(actual)}</b></div>")
                    .append(savedHtml)
        }
        html.append('</section>')
        return html.toString()
    }

    @NonCPS
    protected String manualComparisonHtml(Map stageTimes, Map stageResults) {
        Map minutes = BuildUtils.manualEffort(state.cfgDefaults ?: [:]).minutes as Map
        Map table = ReportViewModel.manualComparison(stageTimes, stageResults, (state.parallelGroups ?: []) as List, minutes)
        List rows = (table.rows ?: []) as List
        if (!rows) return ''
        String cell = "padding:8px 12px;vertical-align:top;"
        String note = "display:block;margin-top:2px;color:#64748b;font-size:0.74rem;"
        StringBuilder body = new StringBuilder()
        for (def r : rows) {
            Map row = r as Map
            body.append("<tr><td style='${cell}font-weight:600;'>${esc(row.step as String)}</td>")
                    .append("<td style='${cell}text-align:right;white-space:nowrap;'><b>${BuildUtils.formatDuration(row.manual as long)}</b><span style='${note}'>${esc(row.manualNote as String)}</span></td>")
                    .append("<td style='${cell}text-align:right;white-space:nowrap;'><b>${BuildUtils.formatDuration(row.automated as long)}</b><span style='${note}'>${esc(row.automatedNote as String)}</span></td></tr>")
        }
        long manual = table.manual as long
        long automated = table.automated as long
        long recovered = table.recovered as long
        long percent = manual > 0L ? Math.round(recovered * 100.0d / manual) : 0L
        body.append("<tr><td style='${cell}font-weight:800;border-top:2px solid #e2e8f0;'>Total</td>")
                .append("<td style='${cell}text-align:right;font-weight:800;border-top:2px solid #e2e8f0;'>${BuildUtils.formatDuration(manual)}</td>")
                .append("<td style='${cell}text-align:right;font-weight:800;border-top:2px solid #e2e8f0;'>${BuildUtils.formatDuration(automated)}</td></tr>")
        return "\n<section id='manual-vs-automated' class='card' style='margin-top:20px;'><h2>Manual actions vs DevSecOps Automated Actions</h2>" +
                "<div style='overflow-x:auto;'><table style='min-width:560px;'><thead><tr><th>Step</th><th style='text-align:right;'>Manual actions</th>" +
                "<th style='text-align:right;'>DevSecOps Automated Actions</th></tr></thead><tbody>${body}</tbody></table></div>" +
                "<div style='margin-top:10px;font-size:0.9rem;color:#15803d;font-weight:700;'>Time recovered in this run: <b>${BuildUtils.formatDuration(recovered)}</b> (${percent}% of the manual time)</div>" +
                "<div style='margin-top:4px;font-size:0.74rem;color:#64748b;'>Recalculated on every run from the stage durations of this run; the manual overheads are report.manualEffort in the library defaults.</div></section>"
    }

    @NonCPS
    static List runStatuses() {
        return ReportViewModel.runStatuses()
    }

    @NonCPS
    static List sonarBadges() {
        return ReportViewModel.sonarBadges()
    }

    @NonCPS
    static Map layout(String variant, boolean nodeBuild = false) {
        return ReportViewModel.layout(variant, nodeBuild)
    }
}
