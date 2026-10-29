package com.bbh.metrics

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.ReleaseGate
import com.cloudbees.groovy.cps.NonCPS

class InfluxDbService implements Serializable {
    private final def         script
    private final PipelineState state
    private final OsHelper    os

    InfluxDbService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    void send(String pipelineType) {
        Map cfg = primaryCfg()
        def influxCfg = cfg.influx ?: [:]
        if (!(influxCfg.enabled as boolean)) { script.echo "[INFLUX] InfluxDB disabled or not configured."; return }
        def url     = influxCfg.url
        def project = influxCfg.project ?: cfg.tools?.sonar?.projectName ?: script.env.PROJECT_NAME ?: "UnknownProject"
        def envName = influxCfg.env     ?: "dev"
        def token   = influxCfg.token
        def credId  = influxCfg.credentialsId ?: ''
        if (!url || (!token && !credId)) { script.echo "[INFLUX] Missing url or token - skipping."; return }

        Map context = buildContext(project as String, pipelineType, envName as String)
        List<String> lines = PipelineMetrics.lines(state, context)
        String payload = lines.join('\n')
        script.echo "[INFLUX] ${lines.size()} metric line(s) for ${context.project} (${context.env}), build ${context.buildNumber}, result ${context.result}"
        script.echo "[INFLUX] Sending metrics to ${url}"

        long stamp = System.currentTimeMillis()
        def tmpFile = "influx_payload_${stamp}.txt"
        def responseFile = "influx_response_${stamp}.txt"
        script.writeFile file: tmpFile, text: payload
        try {
            List vars = ["INFLUX_URL=${url}", "INFLUX_FILE=${tmpFile}", "INFLUX_RESPONSE=${responseFile}"]
            if (credId) {
                script.withCredentials([script.string(credentialsId: credId, variable: 'INFLUX_TOKEN')]) {
                    sendPayload(vars)
                }
            } else {
                sendPayload(vars + ["INFLUX_TOKEN=${token}"])
            }
        } catch (e) { script.echo "[INFLUX] Failed to send metrics: ${e.message}" }
        finally {
            if (os.isWindows()) script.powershell "Remove-Item -Force '${tmpFile}', '${responseFile}' -ErrorAction SilentlyContinue"
            else                script.sh "rm -f '${tmpFile}' '${responseFile}'"
        }
    }

    @NonCPS
    private Map primaryCfg() {
        Map all = (state.projectsAllCfg ?: [:]) as Map
        return (all ? all.values().iterator().next() : state.cfg) as Map
    }

    private Map buildContext(String project, String pipelineType, String envName) {
        String result = (script.currentBuild.currentResult ?: 'SUCCESS') as String
        Map decision = new ReleaseGate(script, state).evaluate()
        return [
                project        : project + (pipelineType ?: ''),
                env            : envName,
                variant        : (pipelineType ?: 'full'),
                result         : result,
                failed         : result != 'SUCCESS',
                durationSeconds: ((script.currentBuild.duration ?: 0) as long) / 1000L,
                buildNumber    : (script.env.BUILD_NUMBER ?: '0') as String,
                job            : (script.env.JOB_NAME ?: '') as String,
                buildUrl       : (script.env.BUILD_URL ?: '') as String,
                branch         : (script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String,
                timestamp      : (System.currentTimeMillis() / 1000L) as long,
                deployed       : deployedStage(),
                released       : PipelineMetrics.green(state.stageStatus('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities')),
                releaseGate    : decision
        ]
    }

    private boolean deployedStage() {
        List names = ['Lower test region deployment', 'Higher test environment deployment']
        for (String name : names) {
            if (state.stageStatus(name) == 'PASS') return true
        }
        return false
    }

    private void sendPayload(List vars) {
        script.withEnv(vars) {
            if (os.isWindows()) {
                script.powershell '''$code = curl.exe -sS -o "$env:INFLUX_RESPONSE" -w '%{http_code}' -X POST "$env:INFLUX_URL" -H "Authorization: Token $env:INFLUX_TOKEN" --data-binary "@$env:INFLUX_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Write-Host "[INFLUX] HTTP $code $(Get-Content -Raw $env:INFLUX_RESPONSE -ErrorAction SilentlyContinue)"
if ([int]$code -ge 300) { exit 1 }
'''
            } else {
                script.sh '''set +x
code=$(curl -sS -o "$INFLUX_RESPONSE" -w '%{http_code}' -X POST "$INFLUX_URL" -H "Authorization: Token $INFLUX_TOKEN" --data-binary "@$INFLUX_FILE") || exit $?
echo "[INFLUX] HTTP $code $(cat "$INFLUX_RESPONSE" 2>/dev/null)"
[ "$code" -lt 300 ]
'''
            }
        }
    }
}
