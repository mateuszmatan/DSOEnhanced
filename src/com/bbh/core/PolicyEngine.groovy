package com.bbh.core

import com.cloudbees.groovy.cps.NonCPS

class PolicyEngine implements Serializable {

    private final def           script
    private final PipelineState state
    private final OsHelper      os
    private final String        bar         = '------------------------------------------------------------'
    private final String        sectionLine = '-----------------------------------------------------------------'

    PolicyEngine(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    @NonCPS
    static String blockNote() {
        return 'The Nexus release and the QC deployment stay blocked until this is fixed.'
    }

    @NonCPS
    static String scannerLabel(String key) {
        Map labels = [
                sast: 'SAST (AppScan)',
                sca : 'SCA (SonarQube)',
                niq : 'Dependencies (Nexus IQ)',
                dast: 'DAST (AppScan)'
        ]
        return (labels[key] ?: key ?: '') as String
    }

    @NonCPS
    static String stageNameFor(String key) {
        Map names = [
                sast: 'SAST - Static Application Security Tests - HCL AppScan',
                sca : 'SCA (SonarQube)',
                niq : 'Dependencies scan (Nexus IQ)',
                dast: 'DAST - Dynamic Application Security Tests - HCL AppScan'
        ]
        return (names[key] ?: '') as String
    }

    void enforceScanner(String scannerKey) {
        if (!scannerKey) script.error "enforceScanner: scannerKey required (sast|sca|dast)"
        if (scannerKey == 'sca') {
            enforceQualityGate()
            return
        }
        registerFindings(scannerKey, state.vulnCounts[scannerKey] as Map, reportFileName(scannerKey))
    }

    void registerFindings(String scannerKey, Map counts, String reportFile) {
        String stageName = scannerStageName(scannerKey)
        Map limits = (state.policyLimits[scannerKey] ?: [:]) as Map
        List violations = countViolations(counts ?: [:], limits)
        if (violations) {
            state.policyStatus[scannerKey] = 'WARN'
            state.recordScan(scannerKey, 'WARN', reportFile)
            warn(stageName, "${scannerLabel(scannerKey)} policy not met: ${violations.join(', ')}.")
            return
        }
        state.policyStatus[scannerKey] = 'PASS'
        state.recordScan(scannerKey, 'PASS', reportFile)
        script.echo "[POLICY] ${scannerLabel(scannerKey)}: policy satisfied."
    }

    void missingCoverage(String reason) {
        int required = (state.coverage.minRequired ?: 60) as int
        state.recordCoverage([enabled: false, minRequired: required])
        state.policyStatus['coverage'] = 'WARN'
        warn('Unit tests', "${reason} - the required ${required}% line coverage cannot be verified.")
    }

    void checkCoverage() {
        String stageName = 'Unit tests'
        int required = (state.coverage.minRequired ?: 60) as int
        Map coverage = (state.projectsCoverage[state.currentProjectName] ?: state.coverage) as Map
        if (!coverage.enabled) {
            missingCoverage('No coverage report found')
            return
        }
        double line = (coverage.line ?: 0.0) as double
        if (line < (required as double)) {
            state.policyStatus['coverage'] = 'WARN'
            warn(stageName, "Line coverage ${line}% is below the required ${required}%.")
            return
        }
        if (state.policyStatus['coverage'] != 'WARN') state.policyStatus['coverage'] = 'PASS'
        script.echo "[COVERAGE] ${projectScope()}Line coverage ${line}% meets the required ${required}%."
    }

    void warn(String stageName, String message) {
        String note = blockNote()
        String scoped = "${projectScope()}${message}".toString()
        String previous = ((state.stageErrors[stageName] ?: '') as String).replace(" ${note}".toString(), '')
        String combined = (previous && !previous.contains(scoped)) ? "${previous} || ${scoped}".toString() : scoped
        state.stageWarn(stageName)
        state.stageError(stageName, "${combined} ${note}".toString())
        script.unstable("[POLICY] ${scoped} ${note}")
    }

    private String projectScope() {
        return ((state.projectsAllCfg ?: [:]).size() > 1) ? "${state.currentProjectName}: ".toString() : ''
    }

    @NonCPS
    List countViolations(Map counts, Map limits) {
        List violations = []
        [['critical', 'maxCritical'], ['high', 'maxHigh'], ['medium', 'maxMedium']].each { entry ->
            int value = (counts[entry[0]] ?: 0) as int
            int limit = (limits[entry[1]] ?: 0) as int
            if (value > limit) violations << "${entry[0]} ${value} of max ${limit}".toString()
        }
        return violations
    }

    String scannerStageName(String key) {
        return stageNameFor(key)
    }

    String reportPath(String key) {
        String scanName = script.env.APPSCAN_SCAN_NAME ?: 'report'
        Map paths = [
                sast: "${script.env.WORKSPACE}/appscan-report-${scanName}.html",
                sca : "${script.env.WORKSPACE}/appscan-sca-report-${scanName}.html",
                dast: "${script.env.WORKSPACE}/appscan-dast-report-${scanName}.html"
        ]
        return paths[key]
    }

    String reportFileName(String key) {
        String scanName = (script.env?.APPSCAN_SCAN_NAME ?: '').trim()
        if (!scanName) return reportPath(key).tokenize('/').last()
        return key == 'dast' ? "appscan-dast-report-${scanName}.html" : "appscan-report-${scanName}.html"
    }

    private void enforceQualityGate() {
        String stageName = scannerStageName('sca')
        String reportFile = reportFileName('sca')
        Map counts = (state.vulnCounts.sca ?: [critical: 0, high: 0, medium: 0, low: 0]) as Map
        state.recordVulns('sca', counts)
        List problems = []
        List violations = countViolations(counts, (state.policyLimits.sca ?: [:]) as Map)
        if (violations) problems << "SonarQube vulnerabilities above the policy: ${violations.join(', ')}".toString()
        def required = state.cfgDefaults.tools?.sonar?.qualityGate?.waitForQualityGate
        required = (required == null) ? true : required
        if (required) {
            def timeoutMinutes = state.cfgDefaults.tools?.sonar?.qualityGate?.timeoutMinutes ?: 5
            String status = 'UNKNOWN'
            try {
                script.timeout(time: timeoutMinutes, unit: 'MINUTES') {
                    status = script.waitForQualityGate()?.status ?: 'UNKNOWN'
                }
            } catch (Exception e) {
                status = 'TIMEOUT'
                script.echo "[POLICY] SonarQube quality gate could not be read: ${e.message}"
            }
            script.echo "[POLICY] SonarQube quality gate status: ${status}"
            if (status != 'OK') problems << "SonarQube quality gate is ${status}".toString()
        } else {
            script.echo "[POLICY] SonarQube quality gate not required."
        }
        String result = problems ? 'WARN' : 'PASS'
        state.sonarResults['status'] = result
        state.policyStatus['sonar'] = result
        state.recordSonar(result)
        state.recordScan('sonar', result, reportFile)
        if (problems) warn(stageName, "${problems.join('; ')}.")
    }

    void finishStage(String stageName) {
        state.stagePass(stageName)
        logStageResult(stageName, state.stageStatus(stageName))
    }

    void failStage(String stageName) {
        state.stageFail(stageName)
        logStageResult(stageName, 'FAIL')
    }

    void logStageResult(String stageName, String status) {
        script.echo bar
        script.echo "[STAGE]  ${stageName}"
        script.echo "[RESULT] ${status}"
        if (stageName.contains('SAST')) {
            logScanner('sast', 'SAST')
        } else if (stageName.contains('SCA')) {
            logSonar()
        } else if (stageName.contains('DAST')) {
            logScanner('dast', 'DAST')
        } else if (stageName == 'Unit tests' && state.coverage.enabled) {
            logCoverage()
        }
        script.echo bar
    }

    void section(String text)      { script.echo "${sectionLine}\n--- ${text} ---\n${sectionLine}" }
    void startSection(String text) { script.echo "${sectionLine}\n--- Starting: ${text} ---\n${sectionLine}" }
    void endSection(String text)   { script.echo "${sectionLine}\n--- End of: ${text} ---\n${sectionLine}" }

    private void logScanner(String key, String label) {
        def counts = state.vulnCounts[key] ?: [critical: 0, high: 0, medium: 0, low: 0]
        def limits = state.policyLimits[key] ?: [maxCritical: 0, maxHigh: 0, maxMedium: 0]
        script.echo "[${label}]   Found:  Critical=${counts.critical}  High=${counts.high}  Medium=${counts.medium}  Low=${counts.low}"
        script.echo "[${label}]   Limits: Critical<=${limits.maxCritical}  High<=${limits.maxHigh}  Medium<=${limits.maxMedium}"
    }

    private void logSonar() {
        def counts = state.vulnCounts.sca ?: [critical: 0, high: 0, medium: 0, low: 0]
        def limits = state.policyLimits.sca ?: [maxCritical: 0, maxHigh: 0]
        state.sonarResults['critical'] = counts.critical as int
        state.sonarResults['high']     = counts.high as int
        state.sonarResults['medium']   = counts.medium as int
        state.sonarResults['low']      = counts.low as int
        script.echo "[SCA]    Found:  Critical=${counts.critical}  High=${counts.high}  Medium=${counts.medium}  Low=${counts.low}"
        script.echo "[SCA]    Limits: Critical<=${limits.maxCritical}  High<=${limits.maxHigh}"
    }

    private void logCoverage() {
        boolean ok = (state.coverage.line as double) >= (state.coverage.minRequired as double)
        script.echo "[COV]    Line: ${state.coverage.line}%  required: ${state.coverage.minRequired}%  ${ok ? 'OK' : 'BELOW THRESHOLD'}"
        script.echo "[COV]    Covered: ${state.coverage.covered}  Missed: ${state.coverage.missed}  Total: ${state.coverage.total}"
    }
}
