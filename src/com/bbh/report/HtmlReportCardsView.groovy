package com.bbh.report

import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class HtmlReportCardsView implements Serializable {

    @NonCPS
    static String esc(String s) {
        if (!s) return ''
        return s.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;').replace("'",'&#39;')
    }

    @NonCPS
    static String td(String content, String style) {
        return "<td style='${style}'>${content}</td>"
    }

    @NonCPS
    static String centerTd(String content, String extraStyle = '') {
        return HtmlReportCardsView.td(content, 'text-align:center;padding:6px 10px;' + extraStyle)
    }

    @NonCPS
    static String dashTd(String extraStyle = '') {
        return HtmlReportCardsView.td('-', "text-align:center;padding:6px 10px;color:#9ca3af;${extraStyle}")
    }

    @NonCPS
    static String link(String url, String label, String color = '#0369a1', String extraStyle = 'font-weight:600;') {
        return "<a href='${HtmlReportCardsView.esc(url)}' target='_blank' style='font-size:0.78rem;color:${color};${extraStyle}'>${label}</a>"
    }

    @NonCPS
    static String mutedDash() {
        return "<span style='font-size:0.78rem;color:#9ca3af;'>-</span>"
    }

    @NonCPS
    static String cardRaw(String titleHtml, String body, String extraStyle) {
        return "<div class='card' style='${extraStyle}'><h2>${titleHtml}</h2>${body}</div>"
    }

    @NonCPS
    static String card(String title, String body) {
        return HtmlReportCardsView.cardRaw(HtmlReportCardsView.esc(title), body, 'margin-top:16px;')
    }

    @NonCPS
    static String banner(String text) {
        return "<div style='background:#dc2626;color:#fff;font-weight:700;text-align:center;padding:8px 12px;border-radius:3px;margin-bottom:8px;font-size:0.85rem;letter-spacing:0.5px;'>${text}</div>"
    }

    @NonCPS
    static String badge(String status) {
        def color = (status == 'PASS' || status == 'NOT_REQUIRED') ? '#16a34a' : status == 'FAIL' ? '#dc2626' : (status == 'WARN' || status == 'BLOCKED') ? '#d97706' : '#6b7280'
        def lbl   = status == 'NOT_REQUIRED' ? 'NOT REQUIRED' : (status ?: 'SKIP')
        return "<span style='background:${color};color:#fff;padding:2px 6px;border-radius:3px;font-size:0.6rem;font-weight:700;letter-spacing:0.5px;text-transform:uppercase;'>${lbl}</span>"
    }

    @NonCPS
    static String vulnCell(int count, int limit) {
        boolean exceeded = count > limit
        return HtmlReportCardsView.td("${count} / ${limit}", "text-align:center;background:${exceeded ? '#fee2e2' : '#f0fdf4'};color:${exceeded ? '#991b1b' : '#166534'};font-weight:700;padding:6px 10px;")
    }

    @NonCPS
    static String niqCountCell(int count, int limit) {
        boolean exceeded = count > limit
        def value = "<span style='color:${exceeded ? '#991b1b' : '#166534'};font-weight:700;font-size:0.88rem;'>${count}&nbsp;/&nbsp;${limit}</span>"
        return HtmlReportCardsView.td(value, "text-align:center;background:${exceeded ? '#fee2e2' : '#f0fdf4'};padding:5px 10px;")
    }

    @NonCPS
    static String sonarCountCell(int count, int limit) {
        boolean exceeded = count > limit
        return HtmlReportCardsView.td("${count} / ${limit}", "text-align:center;background:${exceeded ? '#fee2e2' : '#f0fdf4'};color:${exceeded ? '#991b1b' : '#166534'};font-weight:700;padding:6px 10px;")
    }

    @NonCPS
    static String sonarCountCell(int count) {
        boolean warn = count > 0
        return HtmlReportCardsView.td("${count}", "text-align:center;background:${warn ? '#fff5c1' : '#f0fdf4'};color:${warn ? '#d97706' : '#166534'};font-weight:700;padding:6px 10px;")
    }

    @NonCPS
    static String vulnCountBadge(int val) {
        if (val > 0) return "<b style='color:#dc2626;border:1px solid #1f2937;border-radius:2px;padding:0 3px;margin:0 1px;font-size:0.7rem;'>${val}</b>"
        return "<span style='color:#94a3b8;font-size:0.7rem;'>${val}</span>"
    }

    @NonCPS
    static String vulnLimitBadge(int val, int limit) {
        boolean exceeded = val > limit
        def valStyle = exceeded
                ? "color:#dc2626;border:1px solid #1f2937;border-radius:2px;padding:0 3px;margin:0 1px;font-size:0.7rem;font-weight:700;"
                : "color:#166534;font-size:0.7rem;font-weight:700;"
        return "<span style='${valStyle}'>${val}</span><span style='color:#94a3b8;font-size:0.7rem;'>&nbsp;/&nbsp;${limit}</span>"
    }

    @NonCPS
    static String arrow() {
        return "<div style='display:flex;flex-direction:column;align-items:center;height:10px;margin:1px 0;'><div style='width:2px;flex:1;background:#94a3b8;'></div><div style='width:0;height:0;border-left:4px solid transparent;border-right:4px solid transparent;border-top:5px solid #64748b;'></div></div>"
    }

    @NonCPS
    static String linkBtn(String url, String label) {
        return "<a href='${url}' target='_blank' style='display:inline-block;background:#e0f2fe;color:#0369a1;border:1px solid #7dd3fc;border-radius:3px;padding:2px 7px;font-size:0.7rem;font-weight:600;text-decoration:none;margin-left:4px;'>${HtmlReportCardsView.esc(label)}</a>"
    }

    @NonCPS
    static Map stageBoxPalette(String status) {
        if (status == 'PASS' || status == 'NOT_REQUIRED') return [bg: '#f0fdf4', border: '#86efac', bar: '#16a34a']
        if (status == 'FAIL') return [bg: '#fef2f2', border: '#fca5a5', bar: '#dc2626']
        if (status == 'WARN' || status == 'BLOCKED') return [bg: '#fffbeb', border: '#fcd34d', bar: '#d97706']
        return [bg: '#f8fafc', border: '#e2e8f0', bar: '#94a3b8']
    }

    @NonCPS
    static String headerBackground(String buildResult) {
        if (buildResult == 'SUCCESS')  return 'linear-gradient(135deg,#15803d,#22c55e)'
        if (buildResult == 'FAILURE')  return 'linear-gradient(135deg,#b91c1c,#ef4444)'
        if (buildResult == 'UNSTABLE') return 'linear-gradient(135deg,#b45309,#f59e0b)'
        return 'linear-gradient(135deg,#1d4ed8,#3b82f6)'
    }

    @NonCPS
    static String releaseGateBanner(Map releaseGate) {
        if (!releaseGate || releaseGate.get('allowed')) return ''
        return "<div style='background:#b45309;color:#fff;font-weight:700;text-align:center;padding:8px 12px;border-radius:3px;margin-bottom:8px;font-size:0.85rem;letter-spacing:0.5px;'>NEXUS RELEASE AND QC DEPLOYMENT BLOCKED</div>"
    }

    @NonCPS
    static String releaseGateCardHtml(Map releaseGate) {
        if (!releaseGate) return ''
        List violations = (releaseGate.get('violations') ?: []) as List
        if (releaseGate.get('allowed')) {
            def body = "<div style='background:#f0fdf4;border:1px solid #86efac;border-left:4px solid #16a34a;padding:8px 12px;font-size:0.85rem;color:#166534;'>The library security policy is met - the artifact is released to Nexus and the QC deployment is allowed.</div>"
            return HtmlReportCardsView.card('Release policy', body)
        }
        def sb = new StringBuilder()
        sb.append("<div style='background:#fffbeb;border:1px solid #fcd34d;border-left:4px solid #d97706;padding:8px 12px;font-size:0.85rem;color:#92400e;'>")
        sb.append("<b>The artifact was not released to Nexus and the deployment to QC is blocked.</b>")
        sb.append("<div style='font-size:0.78rem;margin-top:2px;'>Every stage was executed and the build reached the lower test region (RD), but at least one stage is marked orange. Fix the findings listed below to unblock the Nexus release and the QC deployment.</div></div>")
        if (violations) {
            sb.append("<ul style='margin:8px 0 0 18px;font-size:0.78rem;color:#475569;'>")
            for (def v : violations) sb.append("<li>${HtmlReportCardsView.esc(v as String)}</li>")
            sb.append("</ul>")
        }
        return HtmlReportCardsView.card('Release policy', sb.toString())
    }

    @NonCPS
    static String goldenFixCardHtml(Map projectsGoldenFix, boolean isMulti, String settingsPlace) {
        if (!projectsGoldenFix) return ''
        def sb = new StringBuilder()
        projectsGoldenFix.each { project, value ->
            def gf = value as Map
            if (isMulti) sb.append("<div style='font-size:0.82rem;font-weight:700;color:#1d4ed8;margin:8px 0 4px;'>${HtmlReportCardsView.esc(project as String)}</div>")
            sb.append(HtmlReportCardsView.goldenFixStatusBanner(gf, settingsPlace))
            sb.append(HtmlReportCardsView.goldenFixChangesTable((gf.get('changes') ?: []) as List))
            sb.append(HtmlReportCardsView.goldenFixSastCommentsList((gf.get('sastComments') ?: []) as List))
            sb.append(HtmlReportCardsView.goldenFixUnresolvedList((gf.get('unresolved') ?: []) as List))
            sb.append(HtmlReportCardsView.goldenFixNotes((gf.get('notes') ?: []) as List))
        }
        return HtmlReportCardsView.card('Nexus IQ GoldenFix', sb.toString())
    }

    @NonCPS
    static String goldenFixSastCommentsList(List comments) {
        if (!comments) return ''
        def sb = new StringBuilder()
        sb.append("<div style='margin-top:8px;font-size:0.78rem;font-weight:700;color:#0369a1;'>SAST Inline Comments on Pull Request</div><ul style='margin:4px 0 0 18px;font-size:0.74rem;color:#334155;'>")
        for (def cObj : comments) {
            Map c = (cObj ?: [:]) as Map
            sb.append("<li><b>[${HtmlReportCardsView.esc((c.get('severity') ?: '') as String)}]</b> <code>${HtmlReportCardsView.esc((c.get('file') ?: '') as String)}:${c.get('line') ?: 1}</code> - ${HtmlReportCardsView.esc((c.get('type') ?: '') as String)}: <i>Suggested solution:</i> ${HtmlReportCardsView.esc((c.get('remediation') ?: '') as String)}</li>")
        }
        sb.append("</ul>")
        return sb.toString()
    }

    @NonCPS
    static String goldenFixStatusBanner(Map gf, String settingsPlace) {
        if (ReportViewModel.goldenFixHasPr(gf)) {
            def prLink = "<a href='${HtmlReportCardsView.esc(gf.get('prUrl') as String)}' target='_blank' style='color:#0369a1;font-weight:700;'>${HtmlReportCardsView.esc(gf.get('prTitle') as String)}</a>"
            def updated = gf.get('status') == 'PR_UPDATED' ? ' (existing pull request updated)' : ''
            def details = "<div style='font-size:0.72rem;color:#166534;margin-top:2px;'>Branch ${HtmlReportCardsView.esc(gf.get('branch') as String)} &rarr; ${HtmlReportCardsView.esc(gf.get('targetBranch') as String)}${updated}</div>"
            return "<div style='background:#f0fdf4;border:1px solid #86efac;border-left:4px solid #16a34a;padding:8px 12px;font-size:0.85rem;color:#166534;margin-bottom:8px;'><b>A pull request with GoldenFix upgrades was raised:</b> ${prLink}${details}</div>"
        }
        boolean error = gf.get('status') == 'ERROR'
        Map plain = ReportViewModel.goldenFixPlainText(gf, settingsPlace)
        def style = "background:${error ? '#fef2f2' : '#f8fafc'};border:1px solid ${error ? '#fca5a5' : '#e2e8f0'};padding:8px 12px;font-size:0.82rem;color:${error ? '#991b1b' : '#475569'};margin-bottom:8px;"
        return "<div style='${style}'><b>${HtmlReportCardsView.esc(plain.headline as String)}</b><div style='margin-top:3px;font-size:0.78rem;line-height:1.5;'>${HtmlReportCardsView.esc(plain.explanation as String)}</div></div>"
    }

    @NonCPS
    static String goldenFixChangesTable(List changes) {
        if (!changes) return ''
        def sb = new StringBuilder()
        sb.append("<div style='overflow-x:auto;'><table style='min-width:560px;'><thead><tr><th>File</th><th>Component</th><th style='text-align:center;'>From</th><th style='text-align:center;'>To</th></tr></thead><tbody>")
        for (def cObj : changes) {
            Map c = (cObj ?: [:]) as Map
            def property = c.get('property') ? "<div style='font-size:0.68rem;color:#64748b;'>property ${HtmlReportCardsView.esc(c.get('property') as String)}</div>" : ''
            sb.append("<tr>")
            sb.append(HtmlReportCardsView.td(HtmlReportCardsView.esc(c.get('file') as String), 'padding:5px 10px;font-family:monospace;font-size:0.74rem;overflow-wrap:anywhere;'))
            sb.append(HtmlReportCardsView.td(HtmlReportCardsView.esc(c.get('component') as String) + property, 'padding:5px 10px;font-weight:600;'))
            sb.append(HtmlReportCardsView.td(HtmlReportCardsView.esc(c.get('from') as String), 'text-align:center;padding:5px 8px;color:#991b1b;'))
            sb.append(HtmlReportCardsView.td(HtmlReportCardsView.esc(c.get('to') as String), 'text-align:center;padding:5px 8px;color:#166534;font-weight:700;'))
            sb.append("</tr>")
        }
        sb.append("</tbody></table></div>")
        return sb.toString()
    }

    @NonCPS
    static String goldenFixUnresolvedList(List unresolved) {
        if (!unresolved) return ''
        def sb = new StringBuilder()
        sb.append("<div style='margin-top:8px;font-size:0.78rem;font-weight:700;color:#92400e;'>Not applied automatically</div><ul style='margin:4px 0 0 18px;font-size:0.74rem;color:#475569;'>")
        for (def uObj : unresolved) {
            Map u = (uObj ?: [:]) as Map
            sb.append("<li><b>${HtmlReportCardsView.esc(u.get('component') as String)}</b> ${HtmlReportCardsView.esc(u.get('from') as String)} &rarr; ${HtmlReportCardsView.esc(u.get('to') as String)}: ${HtmlReportCardsView.esc(u.get('reason') as String)}</li>")
        }
        sb.append("</ul>")
        return sb.toString()
    }

    @NonCPS
    static String goldenFixNotes(List notes) {
        if (!notes) return ''
        def sb = new StringBuilder("<div style='margin-top:8px;font-size:0.74rem;color:#475569;'>")
        for (def n : notes) sb.append("<div>${HtmlReportCardsView.esc(n as String)}</div>")
        sb.append("</div>")
        return sb.toString()
    }

    @NonCPS
    static String coverageCard(Map coverage, Map projectsCoverage = [:]) {
        Map projects = projectsCoverage ?: [:]
        StringBuilder body = new StringBuilder()
        if (projects.size() > 1) {
            int met = projects.values().count { def p -> (p as Map)?.get('enabled') && (((p as Map).get('line') ?: 0.0) as double) >= (((p as Map).get('minRequired') ?: 60) as double) } as int
            if (coverage?.get('enabled')) body.append(HtmlReportCardsView.coverageBar('All services', coverage + [met: met == projects.size(), note: "${met} of ${projects.size()} services meet it".toString()]))
            projects.each { project, value -> body.append(HtmlReportCardsView.coverageBar(project as String, value as Map)) }
        } else if (coverage?.get('enabled')) {
            body.append(HtmlReportCardsView.coverageBar('', coverage))
        } else if (projects && (projects.values() as List)[0]?.get('reports')) {
            body.append(HtmlReportCardsView.coverageBar('', (projects.values() as List)[0] as Map))
        } else {
            return ''
        }
        body.append(HtmlReportCardsView.coverageReportsTable(projects))
        return HtmlReportCardsView.card('Code Coverage', body.toString())
    }

    @NonCPS
    static String coverageBar(String project, Map coverage) {
        boolean measured = coverage?.get('enabled') ? true : false
        double pct = measured ? ((coverage.get('line') ?: 0.0) as double) : 0.0d
        def covered = coverage?.get('covered') ?: 0
        def total = coverage?.get('total') ?: 0
        def minRequired = coverage?.get('minRequired') ?: 60
        boolean met = coverage?.containsKey('met') ? (coverage.get('met') ? true : false) : (measured && pct >= (minRequired as double))
        String color = met ? '#22c55e' : '#d97706'
        double width = pct > 100.0d ? 100.0d : pct
        String label = project ? "<div style='font-size:0.78rem;font-weight:700;color:#1d4ed8;margin:6px 0 2px;'>${HtmlReportCardsView.esc(project)}</div>" : ''
        String value = measured ? "${pct}%" : 'n/a'
        int counted = (coverage?.get('counted') ?: 0) as int
        String measure = counted > 1 ? "mean of ${counted} reports" : "${covered} covered / ${total} total"
        String note = coverage?.get('note') ? " &nbsp;&bull;&nbsp; ${coverage.get('note')}" : ''
        String detail = measured ? "${measure} &nbsp;&bull;&nbsp; required: ${minRequired}%${note}" : "no coverage report &nbsp;&bull;&nbsp; required: ${minRequired}%"
        return "${label}<div style='display:flex;align-items:center;gap:12px;margin-bottom:6px;'><div style='font-size:1.6rem;font-weight:800;color:${color};min-width:70px;'>${value}</div><div style='flex:1;'><div style='background:#e2e8f0;border-radius:3px;height:12px;overflow:hidden;'><div style='width:${width}%;height:100%;background:${color};border-radius:3px;'></div></div><div style='margin-top:4px;font-size:0.72rem;color:#64748b;'>${detail}</div></div></div>"
    }

    @NonCPS
    static String coverageReportsTable(Map projectsCoverage) {
        List rows = []
        (projectsCoverage ?: [:]).each { project, value ->
            ((((value ?: [:]) as Map).get('reports') ?: []) as List).each { def item ->
                Map report = item as Map
                String text = report.get('reason') ? "not counted: ${report.get('reason')}" : (report.get('detail') ?: '')
                String reason = text ? "<div style='font-size:0.68rem;color:#64748b;'>${HtmlReportCardsView.esc(text as String)}</div>" : ''
                String pct = report.get('line') != null ? "${report.get('line')}%" : '-'
                rows << ("<tr>" + HtmlReportCardsView.td(HtmlReportCardsView.esc(project as String), 'padding:5px 10px;font-weight:600;')
                        + HtmlReportCardsView.td("<span style='font-family:monospace;font-size:0.74rem;'>${HtmlReportCardsView.esc(report.get('path') as String)}</span>${reason}", 'padding:5px 10px;overflow-wrap:anywhere;')
                        + HtmlReportCardsView.td(HtmlReportCardsView.esc(report.get('tool') as String), 'padding:5px 10px;')
                        + HtmlReportCardsView.td(pct, "text-align:center;padding:5px 8px;font-weight:700;color:${report.get('counted') ? '#334155' : '#9ca3af'};")
                        + HtmlReportCardsView.td("${report.get('covered') ?: 0} of ${report.get('total') ?: 0}", 'text-align:center;padding:5px 8px;white-space:nowrap;')
                        + "</tr>")
            }
        }
        if (!rows) return ''
        return "<div style='overflow-x:auto;margin-top:8px;'><table style='min-width:560px;'><thead><tr><th>Service</th><th>Report</th><th>Tool</th><th style='text-align:center;'>Coverage</th><th style='text-align:center;'>Lines</th></tr></thead><tbody>${rows.join('')}</tbody></table></div>"
    }

    @NonCPS
    static String unitTestsCard(Map projectsUnitTests) {
        if (!projectsUnitTests || projectsUnitTests.isEmpty()) return ''
        boolean hasCounts = projectsUnitTests.any { k, v -> (v instanceof Map) && (v as Map).get('total') != null }
        if (!hasCounts) return ''
        def rows = []
        int sumTotal = 0, sumPassed = 0, sumFailed = 0, sumSkipped = 0
        long sumDuration = 0L
        int countedModules = 0
        projectsUnitTests.each { key, value ->
            String project = key?.toString() ?: 'service'
            Map u = (value ?: [:]) as Map
            Integer total = u.get('total') != null ? (u.get('total') as Number).intValue() : null
            int failed = u.get('failed') != null ? (u.get('failed') as Number).intValue() : 0
            int skipped = u.get('skipped') != null ? (u.get('skipped') as Number).intValue() : 0
            int passed = total != null ? Math.max(0, total - failed - skipped) : 0
            long durMs = u.get('durationMs') != null ? (u.get('durationMs') as Number).longValue() : 0L
            if (total != null) {
                countedModules++
                sumTotal += total
                sumPassed += passed
                sumFailed += failed
                sumSkipped += skipped
            }
            sumDuration += durMs
            String durText = durMs > 0L ? (ReportViewModel.formatDuration(durMs) ?: '-') : '-'
            String totalTd = total != null ? "${total}" : '-'
            String passedTd = total != null ? "<span style='color:#16a34a;font-weight:700;'>${passed}</span>" : '-'
            String failedTd = total != null ? "<span style='color:${failed > 0 ? '#991b1b' : '#334155'};font-weight:700;'>${failed}</span>" : '-'
            String skippedTd = total != null ? "${skipped}" : '-'
            rows << ("<tr>" + HtmlReportCardsView.td(HtmlReportCardsView.esc(project), 'padding:6px 10px;font-weight:600;')
                    + HtmlReportCardsView.td(totalTd, 'text-align:center;padding:6px 8px;font-weight:700;')
                    + HtmlReportCardsView.td(passedTd, 'text-align:center;padding:6px 8px;')
                    + HtmlReportCardsView.td(failedTd, 'text-align:center;padding:6px 8px;')
                    + HtmlReportCardsView.td(skippedTd, 'text-align:center;padding:6px 8px;')
                    + HtmlReportCardsView.td(HtmlReportCardsView.esc(durText), 'text-align:center;padding:6px 8px;color:#64748b;')
                    + "</tr>")
        }
        if (projectsUnitTests.size() > 1 || countedModules > 1) {
            String sumDur = sumDuration > 0L ? (ReportViewModel.formatDuration(sumDuration) ?: '-') : '-'
            rows << ("<tr style='background:#f8fafc;font-weight:700;'>"
                    + HtmlReportCardsView.td('All modules summary', 'padding:6px 10px;')
                    + HtmlReportCardsView.td("${sumTotal}", 'text-align:center;padding:6px 8px;')
                    + HtmlReportCardsView.td("<span style='color:#16a34a;'>${sumPassed}</span>", 'text-align:center;padding:6px 8px;')
                    + HtmlReportCardsView.td("<span style='color:${sumFailed > 0 ? '#991b1b' : '#334155'};'>${sumFailed}</span>", 'text-align:center;padding:6px 8px;')
                    + HtmlReportCardsView.td("${sumSkipped}", 'text-align:center;padding:6px 8px;')
                    + HtmlReportCardsView.td(HtmlReportCardsView.esc(sumDur), 'text-align:center;padding:6px 8px;color:#64748b;')
                    + "</tr>")
        }
        String table = "<div style='overflow-x:auto;'><table style='min-width:520px;'><thead><tr><th>Service / Module</th><th style='text-align:center;'>Total</th><th style='text-align:center;'>Passed</th><th style='text-align:center;'>Failed</th><th style='text-align:center;'>Skipped</th><th style='text-align:center;'>Duration</th></tr></thead><tbody>${rows.join('')}</tbody></table></div>"
        return HtmlReportCardsView.card('Unit Tests', table)
    }

    @NonCPS
    static String autoResolvedCard(Map projectsAutoResolved) {
        List rows = []
        (projectsAutoResolved ?: [:]).each { service, list ->
            for (def r : ((list ?: []) as List)) {
                Map item = r as Map
                rows << ("<tr>" + HtmlReportCardsView.td(HtmlReportCardsView.esc(service as String), 'padding:6px 10px;font-weight:600;')
                        + HtmlReportCardsView.td("<code>${HtmlReportCardsView.esc(item.field as String)}</code>", 'padding:6px 8px;')
                        + HtmlReportCardsView.td("<code>${HtmlReportCardsView.esc(item.value as String)}</code>", 'padding:6px 8px;')
                        + HtmlReportCardsView.td(HtmlReportCardsView.esc(item.reason as String), 'padding:6px 8px;color:#64748b;')
                        + "</tr>")
            }
        }
        if (!rows) return ''
        String table = "<div style='overflow-x:auto;'><table style='min-width:520px;'><thead><tr><th>Service</th><th>Setting</th><th>Auto-resolved value</th><th>Why</th></tr></thead><tbody>${rows.join('')}</tbody></table></div>"
        return HtmlReportCardsView.card('Auto-resolved settings', table)
    }

    @NonCPS
    static String testJobsHtml(List jobs, int inlineLimit = 10) {
        if (!jobs) return "<div style='margin-top:4px;padding-left:25px;font-size:0.72rem;color:#dc2626;font-weight:600;'>No jobs executed - tests are mandatory</div>"
        def sb = new StringBuilder()
        if (jobs.size() > 1) sb.append(HtmlReportCardsView.testJobsSummaryHtml(jobs))
        sb.append("<div style='margin-top:5px;padding-left:25px;display:flex;flex-direction:column;gap:2px;'>")
        if (jobs.size() <= inlineLimit) {
            sb.append(HtmlReportCardsView.testJobRowsHtml(jobs))
        } else {
            def problems = jobs.findAll { !ReportViewModel.isTestJobOk(it?.get('status') as String) }
            sb.append(HtmlReportCardsView.testJobRowsHtml(problems.size() > inlineLimit ? problems.subList(0, inlineLimit) : problems))
            if (problems.size() > inlineLimit) {
                sb.append("<div style='font-size:0.68rem;color:#991b1b;padding-left:18px;'>... and ${problems.size() - inlineLimit} more not successful job(s)</div>")
            }
            sb.append("<details style='margin-top:2px;'><summary style='cursor:pointer;font-size:0.7rem;color:#0369a1;font-weight:600;'>Show all ${jobs.size()} jobs</summary><div style='display:flex;flex-direction:column;gap:2px;margin-top:3px;'>")
            sb.append(HtmlReportCardsView.testJobRowsHtml(jobs))
            sb.append("</div></details>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    @NonCPS
    static String testJobRowsHtml(List jobs) {
        def sb = new StringBuilder()
        for (int i = 0; i < jobs.size(); i++) sb.append(HtmlReportCardsView.testJobRowHtml(jobs.get(i) as Map, i))
        return sb.toString()
    }

    @NonCPS
    static String testJobsSummaryHtml(List jobs) {
        def s = ReportViewModel.testJobsSummary(jobs)
        def chip = { String label, int value, String color -> "<span style='display:inline-block;border:1px solid ${color};color:${color};border-radius:2px;padding:0 5px;margin-right:4px;font-size:0.65rem;font-weight:700;'>${label}&nbsp;${value}</span>" }
        return "<div style='margin-top:4px;padding-left:25px;'>${chip('Total', s.total as int, '#334155')}${chip('Passed', s.passed as int, '#16a34a')}${chip('Failed', s.failed as int, (s.failed as int) > 0 ? '#dc2626' : '#94a3b8')}${chip('Not configured', s.notConfigured as int, '#94a3b8')}</div>"
    }

    @NonCPS
    static String testJobRowHtml(Map job, int i) {
        def name   = job.get('name') ?: "job-${i + 1}"
        def status = (job.get('status') ?: 'UNKNOWN') as String
        def url    = job.get('url')?.toString()?.trim() ?: ''
        def type   = (job.get('type') ?: 'local') as String
        def color  = ReportViewModel.testJobStatusColor(status)
        def mark   = status == 'SUCCESS' ? '&#10003;' : status == 'NOT_CONFIGURED' ? '&#8212;' : '&#10007;'
        def dot     = "<span style='color:${color};font-size:0.8rem;font-weight:900;margin-right:5px;'>${mark}</span>"
        def typeTag = "<span style='font-size:0.6rem;color:#fff;background:${type == 'remote' ? '#7c3aed' : '#0369a1'};padding:1px 4px;border-radius:2px;margin-left:4px;'>${HtmlReportCardsView.esc(type)}</span>"
        def buildLink = url ? "&nbsp;<a href='${HtmlReportCardsView.esc(url)}' target='_blank' style='font-size:0.68rem;color:#0369a1;'>Build</a>" : ''
        def stBadge = "<span style='font-size:0.6rem;font-weight:700;color:${color};margin-left:4px;'>${HtmlReportCardsView.esc(status)}</span>"
        return "<div style='display:flex;align-items:center;flex-wrap:wrap;'>${dot}<span style='font-size:0.73rem;font-weight:600;color:#1e293b;'>${HtmlReportCardsView.esc(name?.toString() ?: '')}</span>${typeTag}<span style='font-size:0.68rem;color:#64748b;margin-left:6px;'>${BuildUtils.msFmt((job.get('durationMs') ?: 0L) as long)}</span>${stBadge}${buildLink}</div>"
    }

    @NonCPS
    static String testJobsCardHtml(String title, String stageName, Map projectsRemoteTestResults, Map remoteTestResults) {
        def groups = ReportViewModel.testJobGroups(stageName, projectsRemoteTestResults, remoteTestResults)
        if (groups.isEmpty()) return ''
        def sb = new StringBuilder()
        sb.append("<div style='overflow:auto;max-height:560px;'><table style='min-width:560px;'>")
        sb.append("<thead><tr><th style='text-align:center;width:44px;'>#</th><th style='text-align:left;'>Job</th><th style='text-align:center;'>Type</th><th style='text-align:center;'>Status</th><th style='text-align:center;'>Duration</th><th style='text-align:center;'>Build</th></tr></thead><tbody>")
        groups.each { project, list ->
            sb.append(HtmlReportCardsView.testJobsGroupHeader(project as String, list as List))
            def problems = (list as List).findAll { !ReportViewModel.isTestJobOk(it?.get('status') as String) }
            def rest     = (list as List).findAll { ReportViewModel.isTestJobOk(it?.get('status') as String) }
            (problems + rest).eachWithIndex { job, i -> sb.append(HtmlReportCardsView.testJobTableRow(job as Map, i)) }
        }
        sb.append("</tbody></table></div>")
        return HtmlReportCardsView.card(title, sb.toString())
    }

    @NonCPS
    static String testJobsGroupHeader(String project, List jobs) {
        def s = ReportViewModel.testJobsSummary(jobs)
        def label = project ? "${HtmlReportCardsView.esc(project)} &nbsp;&bull;&nbsp; " : ''
        def text = "${label}${s.total} job(s): ${s.passed} passed, ${s.failed} failed, ${s.notConfigured} not configured"
        return "<tr style='background:#eff6ff;border-top:2px solid #bfdbfe;'><td colspan='6' style='padding:6px 14px;font-size:0.8rem;font-weight:700;color:#1d4ed8;'>${text}</td></tr>"
    }

    @NonCPS
    static String testJobTableRow(Map job, int i) {
        def status = (job?.get('status') ?: 'UNKNOWN') as String
        def ok     = ReportViewModel.isTestJobOk(status)
        def color  = ok ? '#166534' : status == 'NOT_CONFIGURED' ? '#64748b' : '#991b1b'
        def bg     = ok ? '#f0fdf4' : status == 'NOT_CONFIGURED' ? '#f8fafc' : '#fee2e2'
        def url    = job?.get('url')?.toString()?.trim() ?: ''
        def msg    = job?.get('message')?.toString() ?: ''
        def name   = HtmlReportCardsView.esc((job?.get('name') ?: "job-${i + 1}") as String) + (msg ? "<div style='font-size:0.68rem;color:#64748b;font-weight:400;'>${HtmlReportCardsView.esc(msg)}</div>" : '')
        def sb = new StringBuilder()
        sb.append("<tr>")
        sb.append(HtmlReportCardsView.td("${job?.get('index') ?: i + 1}", 'text-align:center;padding:5px 8px;color:#64748b;'))
        sb.append(HtmlReportCardsView.td(name, 'padding:5px 10px;font-weight:600;overflow-wrap:anywhere;'))
        sb.append(HtmlReportCardsView.td(HtmlReportCardsView.esc((job?.get('type') ?: 'local') as String), 'text-align:center;padding:5px 8px;'))
        sb.append(HtmlReportCardsView.td(HtmlReportCardsView.esc(status), "text-align:center;padding:5px 8px;background:${bg};color:${color};font-weight:700;"))
        sb.append(HtmlReportCardsView.td(BuildUtils.msFmt((job?.get('durationMs') ?: 0L) as long), 'text-align:center;padding:5px 8px;white-space:nowrap;'))
        sb.append(HtmlReportCardsView.td(url ? HtmlReportCardsView.link(url, 'Build') : '-', 'text-align:center;padding:5px 8px;'))
        sb.append("</tr>")
        return sb.toString()
    }
}
