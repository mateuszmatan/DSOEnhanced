package com.bbh.core

import com.cloudbees.groovy.cps.NonCPS

class PipelineState implements Serializable {
    Map  cfg                = [:]
    Map  cfgDefaults        = [:]
    Map  libraryDefaults    = [:]
    long commitTime         = 0L
    String commitSha        = ''
    String commitAuthor     = ''
    String currentProjectName = 'unknown'
    List oneViewDocuments   = []
    List oneViewKeys        = []
    List oneViewNotices     = []
    Map  platform           = [:]
    String configFile       = ''
    String buildTag         = ''
    Map  deliveredBuildTags = [:]

    String currentStage = ''
    Map stageResults = [:]
    Map stageErrors  = [:]
    Map stageTimes   = [:]
    Map notRequiredProjects = [:]

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
        line: 0.0, covered: 0, missed: 0, total: 0, counted: 0, minRequired: 60
    ]

    Map projectsVulnCounts     = [:]
    Map projectsNexusIqResults = [:]
    Map projectsScanResults    = [:]
    Map projectsSonarResults   = [:]
    Map projectsAllCfg         = [:]
    Map projectsAutoResolved   = [:]

    Map sonarResults      = [:]
    Map nexusIqResults    = [:]
    Map remoteTestResults = [:]
    Map projectsRemoteTestResults = [:]
    Map projectsGoldenFix         = [:]
    Map projectsCoverage          = [:]
    Map projectsUnitTests         = [:]
    Map goldenFixPullRequest        = [:]
    Map goldenFixPullRequestsByRepo = [:]
    Map projectsDatabase = [:]
    Map databaseTools    = [:]
    Map sqlChangelogs    = [:]
    Map sastScansByAppId = [:]
    Map sonarScansByProjectKey = [:]
    Map nexusIqScansByApp      = [:]
    List parallelGroups        = []
    Map projectsSastFindings   = [:]
    Map projectStageTimes      = [:]
    List upstreamGateViolations = null

    @NonCPS
    PipelineState fork() {
        PipelineState branch = new PipelineState()
        branch.cfg = cfg
        branch.cfgDefaults = cfgDefaults
        branch.libraryDefaults = libraryDefaults
        branch.commitTime = commitTime
        branch.commitSha = commitSha
        branch.commitAuthor = commitAuthor
        branch.currentProjectName = currentProjectName
        branch.oneViewDocuments = oneViewDocuments
        branch.oneViewKeys = oneViewKeys
        branch.oneViewNotices = oneViewNotices
        branch.platform = platform
        branch.configFile = configFile
        branch.buildTag = buildTag
        branch.deliveredBuildTags = deliveredBuildTags
        branch.currentStage = currentStage
        branch.stageResults = stageResults
        branch.stageErrors = stageErrors
        branch.stageTimes = stageTimes
        branch.notRequiredProjects = notRequiredProjects
        branch.vulnCounts = vulnCounts
        branch.policyLimits = policyLimits
        branch.policyStatus = policyStatus
        branch.coverage = coverage
        branch.projectsVulnCounts = projectsVulnCounts
        branch.projectsNexusIqResults = projectsNexusIqResults
        branch.projectsScanResults = projectsScanResults
        branch.projectsSonarResults = projectsSonarResults
        branch.projectsAllCfg = projectsAllCfg
        branch.projectsAutoResolved = projectsAutoResolved
        branch.sonarResults = sonarResults
        branch.nexusIqResults = nexusIqResults
        branch.remoteTestResults = remoteTestResults
        branch.projectsRemoteTestResults = projectsRemoteTestResults
        branch.projectsGoldenFix = projectsGoldenFix
        branch.projectsCoverage = projectsCoverage
        branch.projectsUnitTests = projectsUnitTests
        branch.goldenFixPullRequest = goldenFixPullRequest
        branch.goldenFixPullRequestsByRepo = goldenFixPullRequestsByRepo
        branch.projectsDatabase = projectsDatabase
        branch.databaseTools = databaseTools
        branch.sqlChangelogs = sqlChangelogs
        branch.sastScansByAppId = sastScansByAppId
        branch.sonarScansByProjectKey = sonarScansByProjectKey
        branch.nexusIqScansByApp = nexusIqScansByApp
        branch.parallelGroups = parallelGroups
        branch.projectsSastFindings = projectsSastFindings
        branch.projectStageTimes = projectStageTimes
        branch.upstreamGateViolations = upstreamGateViolations
        return branch
    }

    @NonCPS
    void absorb(PipelineState branch) {
        if (branch.cfgDefaults != cfgDefaults) cfgDefaults = branch.cfgDefaults
        if (branch.libraryDefaults != libraryDefaults) libraryDefaults = branch.libraryDefaults
        if (branch.oneViewDocuments != oneViewDocuments) oneViewDocuments = branch.oneViewDocuments
        if (branch.oneViewKeys != oneViewKeys) oneViewKeys = branch.oneViewKeys
        if (branch.commitTime != commitTime) commitTime = branch.commitTime
        if (branch.commitSha != commitSha) commitSha = branch.commitSha
        if (branch.commitAuthor != commitAuthor) commitAuthor = branch.commitAuthor
        if (branch.platform != platform) platform = branch.platform
        if (branch.configFile != configFile) configFile = branch.configFile
        if (branch.buildTag != buildTag) buildTag = branch.buildTag
        if (branch.deliveredBuildTags != deliveredBuildTags) deliveredBuildTags = branch.deliveredBuildTags
        if (branch.policyLimits != policyLimits) policyLimits = branch.policyLimits
        if (branch.policyStatus != policyStatus) policyStatus = branch.policyStatus
        if (branch.coverage != coverage) coverage = branch.coverage
        if (branch.vulnCounts != vulnCounts) vulnCounts = branch.vulnCounts
        if (branch.sonarResults != sonarResults) sonarResults = branch.sonarResults
        if (branch.nexusIqResults != nexusIqResults) nexusIqResults = branch.nexusIqResults
        if (branch.remoteTestResults != remoteTestResults) remoteTestResults = branch.remoteTestResults
        if (branch.projectsGoldenFix != projectsGoldenFix) projectsGoldenFix = branch.projectsGoldenFix
        if (branch.goldenFixPullRequest != goldenFixPullRequest) goldenFixPullRequest = branch.goldenFixPullRequest
        if (branch.goldenFixPullRequestsByRepo != goldenFixPullRequestsByRepo) goldenFixPullRequestsByRepo = branch.goldenFixPullRequestsByRepo
        if (branch.databaseTools != databaseTools) databaseTools = branch.databaseTools
        if (branch.upstreamGateViolations != upstreamGateViolations) upstreamGateViolations = branch.upstreamGateViolations
    }

    @NonCPS
    void addProjectTime(String stage, String project, long ms) {
        if (!stage || !project) return
        Map byProject = projectStageTimes[stage] as Map
        if (byProject == null) {
            byProject = [:]
            projectStageTimes[stage] = byProject
        }
        byProject[project] = Math.max(0L, ((byProject[project] ?: 0L) as long) + ms)
    }

    @NonCPS
    void recordParallelGroup(String name, long start, long end, List branches) {
        long sequential = 0L
        for (def b : (branches ?: [])) {
            Map branch = b as Map
            sequential += Math.max(0L, ((branch.ms ?: 0L) as long) - ((branch.waitMs ?: 0L) as long))
        }
        parallelGroups << [name: name, start: start, end: end, actualMs: Math.max(0L, end - start), sequentialMs: sequential, branches: branches ?: []]
    }

    @NonCPS
    String settingsPlace() {
        return configFile ?: 'OneView'
    }

    @NonCPS
    String defaultsPlace() {
        return configFile ? 'the library defaults' : 'the Library defaults of OneView'
    }

    @NonCPS
    Map bundledDefaults() {
        return libraryDefaults ?: cfgDefaults
    }

    @NonCPS
    Map appScanKeyDefaults() {
        return libraryDefaults ?: cfgDefaults
    }

    void stagePass(String name) {
        List kept = ['NOT_REQUIRED', 'BLOCKED', 'WARN', 'FAIL']
        if (!kept.contains(stageResults[name] as String)) {
            stageResults[name] = notRequiredForAll(name) ? 'NOT_REQUIRED' : 'PASS'
            stageErrors.remove(name)
        }
    }

    void projectNotRequired() {
        List names = (notRequiredProjects[currentStage] ?: []) as List
        names << currentProjectName
        notRequiredProjects[currentStage] = names
    }

    @NonCPS
    private boolean notRequiredForAll(String name) {
        List names = (notRequiredProjects[name] ?: []) as List
        return names && projectsAllCfg.keySet().every { names.contains(it) }
    }

    String stageStatus(String name) {
        return (stageResults[name] ?: 'SKIP') as String
    }

    void stageFail(String name)                 { stageResults[name] = 'FAIL' }
    void stageWarn(String name)                 { if (stageResults[name] != 'BLOCKED') stageResults[name] = 'WARN' }
    void stageError(String name, String reason) { stageErrors[name] = reason }
    void stageStart(String name)                { currentStage = name; stageTimes[name] = [start: System.currentTimeMillis(), end: 0L] }
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
        Map recorded = projectsVulnCounts[currentProjectName] as Map
        if (!vulnCounts.containsKey(scanner) && recorded.containsKey(scanner)) return
        recorded[scanner] = new HashMap(vulnCounts[scanner] ?: [critical: 0, high: 0, medium: 0, low: 0])
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
    void recordDatabase(Map values) {
        Map current = (projectsDatabase[currentProjectName] ?: [:]) as Map
        projectsDatabase[currentProjectName] = current + (values ?: [:])
    }

    @NonCPS
    void recordDatabaseTarget(String target, Map values) {
        Map current = (projectsDatabase[currentProjectName] ?: [:]) as Map
        Map targets = [:]
        targets.putAll((current.targets ?: [:]) as Map)
        targets[target] = ((targets[target] ?: [:]) as Map) + (values ?: [:])
        projectsDatabase[currentProjectName] = current + [targets: targets]
    }

    @NonCPS
    void recordGoldenFix(Map result) {
        projectsGoldenFix[currentProjectName] = new HashMap(result ?: [:])
    }

    @NonCPS
    void settle(List stages, List scanners) {
        List stageGroups = []
        for (def name : stages) stageGroups << [name]
        for (Map map : [stageResults, stageErrors, stageTimes]) reorder(map, ownedLast(map.keySet(), stageGroups))
        for (Map map : [projectsVulnCounts, projectsScanResults]) {
            for (def value : map.values()) {
                if (value instanceof Map) reorder(value as Map, ownedLast((value as Map).keySet(), scanners))
            }
        }
    }

    @NonCPS
    private static List ownedLast(Collection keys, List groups) {
        List result = []
        for (def k : keys) {
            if (!owns(k, groups)) result << k
        }
        for (def group : groups) {
            for (def k : keys) {
                if (!result.contains(k) && owns(k, [group])) result << k
            }
        }
        return result
    }

    @NonCPS
    private static boolean owns(def key, List groups) {
        for (def group : groups) {
            for (def prefix : (group as List)) {
                if (key == prefix || (key as String).startsWith("${prefix}_".toString())) return true
            }
        }
        return false
    }

    @NonCPS
    private static void reorder(Map map, List keys) {
        Map copy = [:]
        copy.putAll(map)
        map.clear()
        for (def k : keys) map.put(k, copy.get(k))
    }
}
