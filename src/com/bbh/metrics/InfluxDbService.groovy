package com.bbh.metrics

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.ReleaseGate

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
        def influxCfg = state.cfg.influx ?: [:]
        if (!(influxCfg.enabled as boolean)) { script.echo "[INFLUX] InfluxDB disabled or not configured."; return }
        def url     = influxCfg.url
        def project = influxCfg.project ?: state.cfg.tools?.sonar?.projectName ?: script.env.PROJECT_NAME ?: "UnknownProject"
        def envName = influxCfg.env     ?: "dev"
        def token   = influxCfg.token
        def credId  = influxCfg.credentialsId ?: ''
        if (!url || (!token && !credId)) { script.echo "[INFLUX] Missing url or token - skipping."; return }

        Map context = buildContext(project as String, pipelineType, envName as String)
        List<String> lines = PipelineMetrics.lines(state, context)
        String payload = lines.join('\n')
        script.echo "[INFLUX] ${lines.size()} metric line(s) for ${context.project} (${context.env}), build ${context.buildNumber}, result ${context.result}"
        script.echo "[INFLUX] Sending metrics to ${url}"

        def tmpFile = "influx_payload_${System.currentTimeMillis()}.txt"
        script.writeFile file: tmpFile, text: payload
        try {
            if (credId) {
                script.withCredentials([script.string(credentialsId: credId, variable: 'INFLUX_TOKEN')]) {
                    sendPayload(url, script.env.INFLUX_TOKEN, tmpFile)
                }
            } else {
                sendPayload(url, token, tmpFile)
            }
        } catch (e) { script.echo "[INFLUX] Failed to send metrics: ${e.message}" }
        finally {
            if (os.isWindows()) script.powershell "Remove-Item -Force '${tmpFile}' -ErrorAction SilentlyContinue"
            else                script.sh "rm -f '${tmpFile}'"
        }
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
            if (PipelineMetrics.green(state.stageStatus(name))) return true
        }
        return false
    }

    private void sendPayload(String url, String token, String tmpFile) {
        if (os.isWindows()) script.powershell "curl.exe -s -X POST \"${url}\" -H \"Authorization: Token ${token}\" --data-binary \"@${tmpFile}\""
        else                script.sh "curl -s -X POST \"${url}\" -H \"Authorization: Token ${token}\" --data-binary \"@${tmpFile}\""
    }
}
