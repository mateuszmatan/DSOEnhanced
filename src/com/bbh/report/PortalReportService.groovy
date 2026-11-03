package com.bbh.report

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.cloudbees.groovy.cps.NonCPS

class PortalReportService implements Serializable {
    private final def script
    private final PipelineState state
    private final OsHelper os

    PortalReportService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state = state
        this.os = os
    }

    void send(String pipelineType = '') {
        String portalUrl = resolvePortalUrl()
        if (!portalUrl) return

        String reportPath = 'report/pipeline-report.html'
        String reportHtml = ''
        if (script.fileExists(reportPath)) {
            try {
                reportHtml = script.readFile(file: reportPath)
            } catch (Throwable t) {
                script.echo "[PORTAL] Could not read ${reportPath}: ${t.message}"
            }
        }
        if (!reportHtml) {
            reportHtml = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><title>Pipeline Report</title></head><body style='font-family:sans-serif;padding:24px'><h2>Pipeline Report</h2><p>Report file was empty or missing.</p></body></html>"
        }

        Map payload = buildPayload(reportHtml)
        long stamp = System.currentTimeMillis()
        String tmpFile = "portal_report_${stamp}.json"
        String responseFile = "portal_response_${stamp}.json"
        script.writeJSON(file: tmpFile, json: payload)
        script.echo "[PORTAL] Uploading pipeline report to ${portalUrl}/api/dso/reports for job ${payload.job} build ${payload.build}"
        try {
            List vars = ["PORTAL_URL=${portalUrl}", "PORTAL_FILE=${tmpFile}", "PORTAL_RESPONSE=${responseFile}"]
            sendPayload(vars)
        } catch (Throwable e) {
            script.echo "[PORTAL] Failed to upload pipeline report to portal: ${e.message}"
        } finally {
            cleanup(tmpFile, responseFile)
        }
    }

    @NonCPS
    private String resolvePortalUrl() {
        def url = script.env.DSO_PORTAL_URL ?: script.env.PORTAL_URL
        if (url) return ((url as String).trim().replaceAll('/+$', ''))
        Map cfg = primaryCfg()
        def configured = cfg?.portal?.url ?: state.cfgDefaults?.portal?.url
        if (configured) return ((configured as String).trim().replaceAll('/+$', ''))
        return ''
    }

    @NonCPS
    private String resolvePipelineKey() {
        def key = script.env.DSO_PIPELINE_KEY ?: script.env.PIPELINE_KEY
        if (key) return (key as String).trim()
        Map cfg = primaryCfg()
        def configured = cfg?.portal?.pipelineKey ?: state.cfgDefaults?.portal?.pipelineKey
        if (configured) return (configured as String).trim()
        return ''
    }

    @NonCPS
    private Map primaryCfg() {
        Map all = (state.projectsAllCfg ?: [:]) as Map
        return (all ? all.values().iterator().next() : state.cfg) as Map
    }

    @NonCPS
    private Map buildPayload(String reportHtml) {
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
                pipelineKey    : resolvePipelineKey(),
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
                reportHtml     : reportHtml
        ]
    }

    private void sendPayload(List vars) {
        script.withEnv(vars) {
            if (os.isWindows()) {
                script.powershell '''$code = curl.exe -sS -k -o "$env:PORTAL_RESPONSE" -w '%{http_code}' -X POST "$env:PORTAL_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$env:PORTAL_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Write-Host "[PORTAL] HTTP $code $(Get-Content -Raw $env:PORTAL_RESPONSE -ErrorAction SilentlyContinue)"
if ([int]$code -ge 300) { exit 1 }
'''
            } else {
                script.sh '''set +x
code=$(curl -sS -k -o "$PORTAL_RESPONSE" -w '%{http_code}' -X POST "$PORTAL_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$PORTAL_FILE")
rc=$?
if [ $rc -ne 0 ]; then
  exit $rc
fi
echo "[PORTAL] HTTP $code $(cat "$PORTAL_RESPONSE" 2>/dev/null)"
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
