package com.bbh.report

import com.bbh.core.OneViewSettings
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.cloudbees.groovy.cps.NonCPS

class OneViewReportService implements Serializable {
    private final def script
    private final PipelineState state
    private final OsHelper os
    private final OneViewSettings settings

    OneViewReportService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state = state
        this.os = os
        this.settings = new OneViewSettings(script, state)
    }

    void send(String pipelineType = '') {
        List<Map> keys = settings.reportKeys()
        if (!keys) return
        Map target = settings.reportTarget()
        String address = (target.address ?: '') as String
        if (!address) return
        String shown = target.strict ? "${address}/api/dso/reports".toString() : "the address in ${target.name} (path /api/dso/reports)".toString()

        String reportPath = 'report/pipeline-report.html'
        String reportHtml = ''
        if (script.fileExists(reportPath)) {
            try {
                reportHtml = script.readFile(file: reportPath)
            } catch (Throwable t) {
                script.echo "[ONEVIEW] Could not read ${reportPath}: ${t.message}"
            }
        }
        if (!reportHtml) {
            reportHtml = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><title>Pipeline Report</title></head><body style='font-family:sans-serif;padding:24px'><h2>Pipeline Report</h2><p>Report file was empty or missing.</p></body></html>"
        }

        for (Map entry : keys) {
            if (!upload(entry, target, shown, reportHtml)) break
        }
    }

    private boolean upload(Map entry, Map target, String shown, String reportHtml) {
        String address = target.address as String
        String keyHint = (entry.hint ?: '') as String
        long stamp = System.currentTimeMillis()
        String tmpFile = "oneview_report_${stamp}.json"
        String responseFile = "oneview_response_${stamp}.json"
        boolean goOn = true
        try {
            Map payload = buildPayload(reportHtml, entry.key as String)
            script.writeJSON(file: tmpFile, json: payload)
            script.echo "[ONEVIEW] Uploading pipeline report to ${shown} for job ${payload.job} build ${payload.build}${keyHint ? ' with pipeline key ' + keyHint : ''}"
            List vars = ["ONEVIEW_URL=${address}", "ONEVIEW_FILE=${tmpFile}", "ONEVIEW_RESPONSE=${responseFile}"]
            sendPayload(vars)
        } catch (Throwable e) {
            goOn = !e.getClass().getName().endsWith('FlowInterruptedException')
            String reason = (e.message ?: e.getClass().getName()) as String
            if (!target.strict) reason = reason.replace(address, "the address in ${target.name}".toString())
            script.echo "[ONEVIEW] Failed to upload pipeline report to OneView${keyHint ? ' for pipeline key ' + keyHint : ''}: ${reason}"
        } finally {
            cleanup(tmpFile, responseFile)
        }
        return goOn
    }

    @NonCPS
    private Map buildPayload(String reportHtml, String pipelineKey) {
        String result = (script.currentBuild.result ?: (script.currentBuild.currentResult ?: 'SUCCESS')) as String
        Map tally = [passed: 0, warned: 0, failed: 0, blocked: 0, skipped: 0, notRequired: 0]
        for (def entry : (state.stageResults ?: [:]).entrySet()) {
            String status = entry.value as String
            if (status == 'PASS') tally.passed = (tally.passed as int) + 1
            else if (status == 'WARN') tally.warned = (tally.warned as int) + 1
            else if (status == 'FAIL') tally.failed = (tally.failed as int) + 1
            else if (status == 'BLOCKED') tally.blocked = (tally.blocked as int) + 1
            else if (status == 'NOT_REQUIRED') tally.notRequired = (tally.notRequired as int) + 1
            else tally.skipped = (tally.skipped as int) + 1
        }
        int stagesTotal = (state.stageResults ?: [:]).size()
        long durationSeconds = ((script.currentBuild.duration ?: 0) as long) / 1000L
        long buildNumber = 0L
        String bn = (script.env.BUILD_NUMBER ?: '').toString().trim()
        if (bn ==~ /\d+/) {
            buildNumber = bn.toLong()
        }

        return [
                pipelineKey    : pipelineKey,
                job            : (script.env.JOB_NAME ?: '') as String,
                build          : buildNumber,
                result         : result,
                branch         : (script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String,
                commit         : (state.commitSha ?: '') as String,
                durationSeconds: durationSeconds,
                stagesTotal    : (long) stagesTotal,
                passed         : (long) tally.passed,
                warned         : (long) tally.warned,
                failed         : (long) tally.failed,
                blocked        : (long) tally.blocked,
                skipped        : (long) (tally.skipped + tally.notRequired),
                reportHtml     : reportHtml,
                autoResolved   : autoResolvedRows()
        ]
    }

    @NonCPS
    private List autoResolvedRows() {
        List rows = []
        for (def entry : ((state.projectsAutoResolved ?: [:]) as Map).entrySet()) {
            for (def r : ((entry.value ?: []) as List)) {
                Map item = r as Map
                rows << [service: entry.key as String, field: (item.field ?: '') as String,
                         value: (item.value == null ? '' : item.value.toString()), reason: (item.reason ?: '') as String]
            }
        }
        return rows
    }

    private void sendPayload(List vars) {
        script.withEnv(vars) {
            if (os.isWindows()) {
                script.powershell '''$code = curl.exe -sS -k --connect-timeout 10 --max-time 60 -o "$env:ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$env:ONEVIEW_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$env:ONEVIEW_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$answer = (Get-Content -Raw $env:ONEVIEW_RESPONSE -ErrorAction SilentlyContinue) -replace '"pipelineKey" *: *"[^"]*"', '"pipelineKey":"(not shown)"'
Write-Host "[ONEVIEW] HTTP $code $answer"
if ([int]$code -ge 300) { exit 1 }
'''
            } else {
                script.sh '''set +x
code=$(curl -sS -k --connect-timeout 10 --max-time 60 -o "$ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$ONEVIEW_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$ONEVIEW_FILE")
rc=$?
if [ $rc -ne 0 ]; then
  exit $rc
fi
echo "[ONEVIEW] HTTP $code $(sed 's/"pipelineKey" *: *"[^"]*"/"pipelineKey":"(not shown)"/g' "$ONEVIEW_RESPONSE" 2>/dev/null)"
if [ "$code" -ge 300 ]; then
  exit 1
fi
'''
            }
        }
    }

    private void cleanup(String tmpFile, String responseFile) {
        try {
            if (os.isWindows()) script.powershell "Remove-Item -Force '${tmpFile}', '${responseFile}' -ErrorAction SilentlyContinue"
            else                script.sh "rm -f '${tmpFile}' '${responseFile}'"
        } catch (Throwable ignored) {}
    }
}
