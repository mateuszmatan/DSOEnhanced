package com.bbh.core

import com.cloudbees.groovy.cps.NonCPS

class PipelineState implements Serializable {
    Map  cfg                = [:]
    Map  cfgDefaults        = [:]
    long commitTime         = 0L
    String commitSha        = ''
    String commitAuthor     = ''
    String currentProjectName = 'unknown'
    List portalDocuments    = []
    Map  platform           = [:]

    Map stageResults = [:]
    Map stageErrors  = [:]
    Map stageTimes   = [:]

    Map vulnCounts = [
        sast: [critical: 0, high: 0, medium: 0, low: 0],
        sca:  [critical: 0, high: 0, medium: 0, low: 0],
        dast: [critical: 0, high: 0, medium: 0, low: 0]
    ]

    Map policyLimits = [
        sast: [maxCritical: 0, maxHigh: 0, maxMedium: 0],
        sca:  [maxCritical: 0, maxHigh: 0, maxMedium: 0],
        dast: [maxCritical: 0, maxHigh: 0, maxMedium: 0],
        niq:  [maxCritical: 0, maxHigh: 0, maxMedium: 0]
    ]

    Map policyStatus = [sast: 'SKIP', sca: 'SKIP', dast: 'SKIP', iast: 'SKIP', coverage: 'SKIP', sonar: 'SKIP']

    Map coverage = [
        enabled: false, tool: 'none', buildTool: 'none',
        line: 0.0, covered: 0, missed: 0, total: 0, minRequired: 60
    ]

    Map projectsVulnCounts     = [:]
    Map projectsNexusIqResults = [:]
    Map projectsScanResults    = [:]
    Map projectsSonarResults   = [:]
    Map projectsAllCfg         = [:]

    Map sonarResults      = [:]
    Map nexusIqResults    = [:]
    Map remoteTestResults = [:]
    Map projectsRemoteTestResults = [:]
    Map projectsGoldenFix         = [:]
    Map projectsCoverage          = [:]
    Map projectsUnitTests         = [:]
    Map goldenFixPullRequest      = [:]

    void stagePass(String name) {
        List kept = ['NOT_REQUIRED', 'BLOCKED', 'WARN', 'FAIL']
        if (!kept.contains(stageResults[name] as String)) {
            stageResults[name] = 'PASS'
            stageErrors.remove(name)
        }
    }

    String stageStatus(String name) {
        return (stageResults[name] ?: 'SKIP') as String
    }

    void stageFail(String name)                 { stageResults[name] = 'FAIL' }
    void stageWarn(String name)                 { if (stageResults[name] != 'BLOCKED') stageResults[name] = 'WARN' }
    void stageError(String name, String reason) { stageErrors[name] = reason }
    void stageStart(String name)                { stageTimes[name] = [start: System.currentTimeMillis(), end: 0L] }
    void stageDone(String name)                 { if (stageTimes.containsKey(name)) stageTimes[name].end = System.currentTimeMillis() }

    void recordVulns(String scanner, Map counts) {
        if (!projectsVulnCounts[currentProjectName]) projectsVulnCounts[currentProjectName] = [:]
        projectsVulnCounts[currentProjectName][scanner] = new HashMap(counts)
    }

    void recordScan(String scanner, String result, String reportFile = '') {
        if (!currentProjectName) return
        if (!projectsScanResults[currentProjectName]) projectsScanResults[currentProjectName] = [:]
        projectsScanResults[currentProjectName][scanner] = result
        if (reportFile) projectsScanResults[currentProjectName]["${scanner}_file".toString()] = reportFile
        if (!projectsVulnCounts[currentProjectName]) projectsVulnCounts[currentProjectName] = [:]
        projectsVulnCounts[currentProjectName][scanner] = new HashMap(vulnCounts[scanner] ?: [critical: 0, high: 0, medium: 0, low: 0])
    }

    @NonCPS
    void recordScanArtifact(String scanner, String suffix, String fileName) {
        if (!currentProjectName || !fileName) return
        if (!projectsScanResults[currentProjectName]) projectsScanResults[currentProjectName] = [:]
        projectsScanResults[currentProjectName]["${scanner}_${suffix}".toString()] = fileName
    }

    void recordNexusIq(String result) {
        def niq = new HashMap(nexusIqResults ?: [:])
        niq.status = result
        projectsNexusIqResults[currentProjectName] = niq
        if (!projectsVulnCounts[currentProjectName]) projectsVulnCounts[currentProjectName] = [:]
        projectsVulnCounts[currentProjectName]['niq'] = [
            critical: (niq.get('critical') ?: 0) as int,
            high:     (niq.get('high')     ?: 0) as int,
            medium:   (niq.get('medium')   ?: 0) as int,
            low:      0
        ]
    }

    void recordSonar(String result) {
        def sonar = new HashMap(sonarResults ?: [:])
        sonar.status = result
        projectsSonarResults[currentProjectName] = sonar
    }

    @NonCPS
    void recordTestJobs(String stageName, List results) {
        List tagged = []
        for (def r : (results ?: [])) {
            Map copy = new HashMap(r as Map)
            copy.project = currentProjectName
            tagged << copy
        }
        if (!projectsRemoteTestResults[currentProjectName]) projectsRemoteTestResults[currentProjectName] = [:]
        projectsRemoteTestResults[currentProjectName][stageName] = tagged

        List all = []
        for (def entry : projectsRemoteTestResults.entrySet()) {
            def list = (entry.value as Map)?.get(stageName)
            if (list) all.addAll(list as List)
        }
        remoteTestResults[stageName] = all
    }

    @NonCPS
    void recordCoverage(Map coverageOfProject) {
        projectsCoverage[currentProjectName] = new HashMap(coverageOfProject ?: [:])
    }

    @NonCPS
    void recordUnitTests(Map values) {
        Map current = (projectsUnitTests[currentProjectName] ?: [:]) as Map
        projectsUnitTests[currentProjectName] = current + (values ?: [:])
    }

    @NonCPS
    void recordGoldenFix(Map result) {
        projectsGoldenFix[currentProjectName] = new HashMap(result ?: [:])
    }
}
