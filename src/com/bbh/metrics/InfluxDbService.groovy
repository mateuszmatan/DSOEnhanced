package com.bbh.metrics

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState

class InfluxDbService implements Serializable {
    private final def         script
    private final PipelineState state
    private final OsHelper    os

    InfluxDbService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    void send( String pipelineType) {
        def influxCfg = state.cfg.influx ?: [:]
        if (!(influxCfg.enabled as boolean)) { script.echo "[INFLUX] InfluxDB disabled or not configured."; return }
        def url     = influxCfg.url
        def project = influxCfg.project ?: state.cfg.tools?.sonar?.projectName ?: script.env.PROJECT_NAME ?: "UnknownProject"
        def envName = influxCfg.env     ?: "dev"
        def token   = influxCfg.token
        def credId  = influxCfg.credentialsId ?: ''
        if (!url || (!token && !credId)) { script.echo "[INFLUX] Missing url or token - skipping."; return }
        def safeProject = project.replaceAll(/[,= ]/, '\\\\$0') + pipelineType
        def safeEnv     = envName.replaceAll(/[,= ]/, '\\\\$0')
        long timestamp  = (System.currentTimeMillis() / 1000) as long
        int changeFailure   = (script.currentBuild.currentResult != 'SUCCESS') ? 1 : 0
        long buildDuration  = (script.currentBuild.duration ?: 0) / 1000
        def lines = []
        lines << "deployments,project=${safeProject},env=${safeEnv} count=1 ${timestamp}"
        lines << "change_failure,project=${safeProject},env=${safeEnv} value=${changeFailure} ${timestamp}"
        lines << "build_duration,project=${safeProject},env=${safeEnv} value=${buildDuration} ${timestamp}"
        state.stageResults.each { stageName, stageStatus ->
            def safeStage = stageName.replaceAll(/[,= ]/, '\\\\$0')
            def result    = (stageStatus == 'PASS' || stageStatus == 'NOT_REQUIRED') ? 'success' : 'failure'
            long startEpoch = 0L; long endEpoch = 0L; long durMs = 0L
            if (state.stageTimes[stageName]) {
                def st = (state.stageTimes[stageName].start ?: 0L) as long
                def en = (state.stageTimes[stageName].end   ?: 0L) as long
                startEpoch = (st / 1000L) as long; endEpoch = (en / 1000L) as long
                if (st > 0L && en > st) durMs = en - st
            }
            lines << "stage_metric,project=${safeProject},env=${safeEnv},stage=${safeStage} result=\"${result}\",start_time=${startEpoch},end_time=${endEpoch},duration_ms=${durMs} ${timestamp}"
        }
        ['sast', 'dast'].each { scanner ->
            def counts = state.vulnCounts[scanner] ?: [critical: 0, high: 0, medium: 0, low: 0]
            lines << "vulnerabilities,project=${safeProject},env=${safeEnv},scanner=${scanner} critical=${counts.critical ?: 0},high=${counts.high ?: 0},medium=${counts.medium ?: 0},low=${counts.low ?: 0} ${timestamp}"
        }
        Map niq = totalsOf((state.projectsNexusIqResults ?: [:]) as Map, (state.nexusIqResults ?: [:]) as Map)
        if (niq) {
            lines << "vulnerabilities,project=${safeProject},env=${safeEnv},scanner=nexusiq critical=${niq.critical},high=${niq.high},medium=${niq.medium},low=0 ${timestamp}"
        }
        Map sonar = totalsOf((state.projectsSonarResults ?: [:]) as Map, (state.sonarResults ?: [:]) as Map)
        if (sonar) {
            lines << "vulnerabilities,project=${safeProject},env=${safeEnv},scanner=sonar critical=${sonar.critical},high=${sonar.high},medium=${sonar.medium},low=0 ${timestamp}"
        }
        if (state.coverage && state.coverage.enabled) {
            lines << "test_coverage,project=${safeProject},env=${safeEnv} line_pct=${state.coverage.line ?: 0.0},covered=${(state.coverage.covered ?: 0) as int}i,total=${(state.coverage.total ?: 0) as int}i ${timestamp}"
        }
        def payload = lines.join('\n')
        script.echo "[INFLUX] Prepared metrics: ${payload}"
        script.echo "[INFLUX] Sending metrics to ${url} for project ${project} (${envName})"
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

    private Map totalsOf(Map perProject, Map fallback) {
        List sources = perProject ? (perProject.values() as List) : (fallback ? [fallback] : [])
        if (!sources) return [:]
        int critical = 0
        int high = 0
        int medium = 0
        for (def entry : sources) {
            Map values = (entry ?: [:]) as Map
            critical += (values.critical ?: 0) as int
            high += (values.high ?: 0) as int
            medium += (values.medium ?: 0) as int
        }
        return [critical: critical, high: high, medium: medium]
    }

    private void sendPayload(String url, String token, String tmpFile) {
        if (os.isWindows()) script.powershell "curl.exe -s -X POST \"${url}\" -H \"Authorization: Token ${token}\" --data-binary \"@${tmpFile}\""
        else                script.sh "curl -s -X POST \"${url}\" -H \"Authorization: Token ${token}\" --data-binary \"@${tmpFile}\""
    }
}
