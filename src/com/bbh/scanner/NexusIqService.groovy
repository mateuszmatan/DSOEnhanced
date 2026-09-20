package com.bbh.scanner

import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.remediation.port.DependencyRemediation

class NexusIqService implements Serializable {

    private final def         script
    private final PipelineState state
    private final PolicyEngine policy
    private final DependencyRemediation goldenFix

    NexusIqService(def script, PipelineState state, PolicyEngine policy, DependencyRemediation goldenFix = null) {
        this.script    = script
        this.state     = state
        this.policy    = policy
        this.goldenFix = goldenFix
    }

    void scan() {
        String niqStage  = policy.scannerStageName('niq')
        state.nexusIqResults = [:]
        def niqCfg       = state.cfg.tools?.nexusIq ?: [:]
        Map<String, Map> apps = normalize(niqCfg) as Map<String, Map>

        def violMsg        = ''
        List<Map> scanRefs = []
        try {
            apps.each {application, params ->
                def scanPatterns = params.scanPatterns
                if (!scanPatterns) {
                    script.echo "[DEP-SCAN] Nexus IQ not configured - skipping."
                    state.policyStatus['iast'] = 'SKIP'
                    return
                }

                String nexusIqServerUrl = params.serverUrl?.trim() ?: ''
                String credId       = params.credentialsId?.trim() ?: 'nexusiqP'
                def iqStage      = params.stage?.trim()         ?: 'build'
                def failOnNetErr = (params.failOnNetworkError != null) ? params.failOnNetworkError as boolean : false

                script.echo "[DEP-SCAN] Application: ${application} | Server: ${nexusIqServerUrl}"

                    def iqResult = script.nexusPolicyEvaluation(
                            jobCredentialsId:        credId,
                            iqStage:                 iqStage,
                            failBuildOnNetworkError: failOnNetErr,
                            advancedProperties:      "",
                            iqApplication:           script.selectedApplication(application),
                            iqScanPatterns:          scanPatterns.collect { [scanPattern: it] },
                            unstableBuildOnScanningWarnings: false
                    )

                try {
                    state.nexusIqResults['critical'] = (state.nexusIqResults.get('critical') ?: 0) + (iqResult?.criticalComponentCount ?: 0) as int
                    state.nexusIqResults['high']     = (state.nexusIqResults.get('high') ?: 0) + (iqResult?.severeComponentCount   ?: 0) as int
                    state.nexusIqResults['medium']   = (state.nexusIqResults.get('medium') ?: 0) + (iqResult?.moderateComponentCount ?: 0) as int
                    script.echo "[DEP-SCAN] Application: ${application} - C:${state.nexusIqResults['critical']} H:${state.nexusIqResults['high']} M:${state.nexusIqResults['medium']}"
                } catch (sandboxEx) {
                    script.echo "[DEP-SCAN] Application: ${application} - Sandbox blocked direct result access (${sandboxEx.getClass().getSimpleName()}) - parsing build log"
                    parseFromBuildLog(nexusIqServerUrl, application, credId)
                }
                def nexusIqUrl = iqResult?.applicationCompositionReportUrl
                if (nexusIqServerUrl && nexusIqUrl) {
                    if(nexusIqUrl.contains('/report/')) {
                        state.nexusIqResults['url'] = nexusIqUrl
                        def scanID = nexusIqUrl.split('/report/')[1]
                        def fixUrl = nexusIqServerUrl + '/assets/index.html#/developer/priorities/' + application + '/' + scanID
                        state.nexusIqResults['fix_url'] = fixUrl
                    } else {
                        state.nexusIqResults['fix_url'] = nexusIqUrl
                        def scanID = nexusIqUrl.split("/${application}/")[1]
                        def reportUrl = nexusIqServerUrl + '/ui/links/application/' + application + '/report/' + scanID
                        state.nexusIqResults['url'] = reportUrl
                    }
                }
                scanRefs << [
                        application  : application as String,
                        serverUrl    : nexusIqServerUrl,
                        credentialsId: credId,
                        stage        : iqStage as String,
                        scanId       : extractScanId(nexusIqUrl as String),
                        reportUrl    : (state.nexusIqResults['url'] ?: '') as String
                ]
            }
            def c  = (state.nexusIqResults['critical'] ?: 0) as int
            def h  = (state.nexusIqResults['high']     ?: 0) as int
            def m  = (state.nexusIqResults['medium']   ?: 0) as int
            def lc = (state.policyLimits.get('niq')?.maxCritical ?: 0) as int
            def lh = (state.policyLimits.get('niq')?.maxHigh     ?: 0) as int
            def lm = (state.policyLimits.get('niq')?.maxMedium   ?: 0) as int

            List violations = policy.countViolations([critical: c, high: h, medium: m], [maxCritical: lc, maxHigh: lh, maxMedium: lm])

            if (violations) {
                violMsg = "Dependencies (Nexus IQ) policy not met: ${violations.join(', ')}"
                state.nexusIqResults['status'] = 'WARN'
                state.policyStatus['iast']     = 'WARN'
                state.recordNexusIq('WARN')
                state.recordScan('niq', 'WARN')
                if (goldenFix != null) {
                    try {
                        goldenFix.remediate(scanRefs)
                        def gf = state.projectsGoldenFix[state.currentProjectName]
                        if (gf?.prUrl) violMsg = "${violMsg} | GoldenFix pull request ${gf.prTitle} raised: ${gf.prUrl}"
                    } catch (Throwable gfError) {
                        if (gfError.getClass().getName().endsWith('FlowInterruptedException')) throw gfError
                        script.echo "[GOLDENFIX] Remediation skipped: ${gfError.getClass().getSimpleName()}: ${gfError.message}"
                    }
                }
                policy.warn(niqStage, "${violMsg}.")
            } else {
                state.nexusIqResults['status'] = 'PASS'
                state.policyStatus['iast']     = 'PASS'
                state.recordNexusIq('PASS')
                state.recordScan('niq', 'PASS')
                script.echo "[DEP-SCAN] Policy satisfied."
            }
        } catch (ex) {
            def reason = ex.message ?: ex.getClass().getSimpleName()
            state.nexusIqResults['status'] = 'WARN'
            state.policyStatus['iast']     = 'WARN'
            state.recordNexusIq('WARN')
            state.recordScan('niq', 'WARN')
            policy.warn(niqStage, "Nexus IQ scan could not be completed: ${reason}.")
        }
    }

    private Map<String, Object> normalize(def niqCfg) {
        def app = niqCfg.application

        if (app instanceof String) {
            return [(app): niqCfg]
        }
        return app as Map<String, Object>
    }

    @NonCPS
    static String extractScanId(String url) {
        def m = ((url ?: '') =~ /([0-9a-fA-F]{32})/)
        return m.find() ? m.group(1) : ''
    }

    private void parseFromBuildLog(String serverUrl, String applicationPublicId, String credId) {
        try {
            def lines = script.currentBuild.rawBuild.getLog(500)
            if (extractLogSummary(lines)) {
                script.echo "[DEP-SCAN] Log parse OK - C:${state.nexusIqResults['critical']} H:${state.nexusIqResults['high']} M:${state.nexusIqResults['medium']}"
                return
            }
        } catch (e) {
            script.echo "[DEP-SCAN] rawBuild log blocked (${e.getClass().getSimpleName()}) - trying console REST"
        }
        try {
            def text = script.sh(returnStdout: true, script: "curl -f '${script.env.BUILD_URL}consoleText' || true").trim()
            if (text && extractLogSummary(text.tokenize('\n'))) {
                script.echo "[DEP-SCAN] Console parse OK - C:${state.nexusIqResults['critical']} H:${state.nexusIqResults['high']} M:${state.nexusIqResults['medium']}"
                return
            }
            script.echo "[DEP-SCAN] Console parse found no summary - counts remain 0"
        } catch (e) {
            script.echo "[DEP-SCAN] Console parse failed (${e.message ?: e.getClass().getSimpleName()}) - counts remain 0"
        }
    }

    @NonCPS
    private boolean extractLogSummary(List lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            def line = lines.get(i) as String
            def m = (line =~ /(?i)Summary of policy violations:\s*(\d+)\s*critical[^,]*,\s*(\d+)\s*severe[^,]*,\s*(\d+)\s*moderate/)
            if (m.find()) {
                state.nexusIqResults['critical'] = m.group(1) as int
                state.nexusIqResults['high']     = m.group(2) as int
                state.nexusIqResults['medium']   = m.group(3) as int
                return true
            }
        }
        return false
    }
}
