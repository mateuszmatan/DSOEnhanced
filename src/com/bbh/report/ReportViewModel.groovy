package com.bbh.report

import com.bbh.core.PolicyEngine
import com.cloudbees.groovy.cps.NonCPS

class ReportViewModel implements Serializable {

    @NonCPS
    static List runStatuses() {
        return ['PASS', 'FAIL', 'WARN']
    }

    @NonCPS
    static List sonarBadges() {
        return [
                [label: 'Duplicated lines', metric: 'duplicated_lines_density'], [label: 'Lines of code', metric: 'ncloc'],
                [label: 'Quality gate status', metric: 'alert_status'], [label: 'Security hotspots', metric: 'security_hotspots'],
                [label: 'Bugs', metric: 'bugs'], [label: 'Code smells', metric: 'code_smells'],
                [label: 'Vulnerabilities', metric: 'vulnerabilities'], [label: 'Maintainability rating', metric: 'sqale_rating'],
                [label: 'Reliability rating', metric: 'reliability_rating'], [label: 'Security rating', metric: 'security_rating'],
                [label: 'Technical debt', metric: 'sqale_index']
        ]
    }

    @NonCPS
    static Map layout(String variant, boolean nodeBuild = false) {
        Map chosen = ReportViewModel.stageLayout(variant)
        if (nodeBuild) {
            Map first = (chosen.phaseGroups as List)[0] as Map
            chosen.stageOrder = ReportViewModel.withNodeBuild(chosen.stageOrder as List)
            first.stages = ReportViewModel.withNodeBuild(first.stages as List)
        }
        return chosen
    }

    @NonCPS
    static List withNodeBuild(List stages) {
        return [stages[0], 'Node.js build'] + stages.drop(1)
    }

    @NonCPS
    static Map stageLayout(String variant) {
        String monitor     = 'Monitor source changes (download sources)'
        String unit        = 'Unit tests'
        String build       = 'Build artifact'
        String niq         = 'Dependencies scan (Nexus IQ)'
        String sast        = 'SAST - Static Application Security Tests - HCL AppScan'
        String sonar       = 'SCA (SonarQube)'
        String snapshot    = 'Nexus delivery (Static analysis passed)'
        String pull        = 'Download artifacts from Nexus Repo'
        String deployRd    = 'Lower test region deployment'
        String regression  = 'Regression tests (>60% user stories coverage)'
        String smoke       = 'Smoke tests'
        String performance = 'Performance tests'
        String dast        = 'DAST - Dynamic Application Security Tests - HCL AppScan'
        String release     = 'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities'
        String deployQc    = 'Higher test environment deployment'

        Map sastScanner = [key: 'sast', label: 'SAST (AppScan)', file: 'appscan-report.html', stage: sast]
        Map dastScanner = [key: 'dast', label: 'DAST (AppScan)', file: 'appscan-dast-report.html', pdf: 'appscan-dast-report.pdf', stage: dast]

        if (variant == 'sast') {
            return [
                    appscanScnrs  : [sastScanner],
                    stageOrder    : [monitor, sast],
                    secStageNames : [sast],
                    phaseGroups   : [
                            [label: 'Dev', color: '#3b82f6', stages: [monitor]],
                            [label: 'Static security scan', color: '#2563eb', stages: [sast]]
                    ],
                    reportsToParse: ['sast']
            ]
        }
        if (variant == 'nexusiq') {
            return [
                    appscanScnrs  : [],
                    stageOrder    : [monitor, build, niq],
                    secStageNames : [niq],
                    phaseGroups   : [
                            [label: 'Dev', color: '#3b82f6', stages: [monitor, build]],
                            [label: 'Dependency scan & GoldenFix', color: '#2563eb', stages: [niq]]
                    ],
                    reportsToParse: []
            ]
        }
        if (variant == 'security') {
            return [
                    appscanScnrs  : [sastScanner],
                    stageOrder    : [monitor, unit, niq, sast, sonar, snapshot],
                    secStageNames : [sast, sonar, niq],
                    phaseGroups   : [
                            [label: 'Dev', color: '#3b82f6', stages: [monitor, unit]],
                            [label: 'Static security & code quality scans', color: '#2563eb', stages: [niq, sast, sonar]],
                            [label: 'Delivery', color: '#172554', stages: [snapshot]]
                    ],
                    reportsToParse: ['sast']
            ]
        }
        if (variant == 'extended') {
            return [
                    appscanScnrs  : [dastScanner],
                    stageOrder    : [monitor, pull, deployRd, regression, smoke, performance, dast, release, deployQc],
                    secStageNames : [dast],
                    phaseGroups   : [
                            [label: 'Dev', color: '#3b82f6', stages: [monitor]],
                            [label: 'Delivery', color: '#1d4ed8', stages: [pull, deployRd]],
                            [label: 'Tests', color: '#1e40af', stages: [regression, smoke, performance]],
                            [label: 'Security', color: '#1e3a8a', stages: [dast]],
                            [label: 'Delivery', color: '#172554', stages: [release, deployQc]]
                    ],
                    reportsToParse: ['dast']
            ]
        }
        return [
                appscanScnrs  : [sastScanner, dastScanner],
                stageOrder    : [monitor, unit, niq, sast, sonar, snapshot, deployRd, regression, smoke, performance, dast, release, deployQc],
                secStageNames : [sast, sonar, dast, niq],
                phaseGroups   : [
                        [label: 'Development', color: '#3b82f6', stages: [monitor, unit]],
                        [label: 'Static security & code quality scans', color: '#2563eb', stages: [niq, sast, sonar]],
                        [label: 'Delivery', color: '#1d4ed8', stages: [snapshot, deployRd]],
                        [label: 'Tests', color: '#1e40af', stages: [regression, smoke, performance]],
                        [label: 'Security', color: '#1e3a8a', stages: [dast]],
                        [label: 'Delivery', color: '#172554', stages: [release, deployQc]]
                ],
                reportsToParse: ['sast', 'dast']
        ]
    }

    @NonCPS
    static boolean sameCounts(Map a, Map b) {
        List keys = ['critical', 'high', 'medium', 'low']
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i) as String
            if (((a?.get(k) ?: 0) as int) != ((b?.get(k) ?: 0) as int)) return false
        }
        return true
    }

    @NonCPS
    static String formatDuration(long ms) {
        if (ms <= 0) return ''
        long s = ms / 1000
        if (s < 60) return "${s}s"
        long m = s.intdiv(60); long rem = s % 60
        if (m < 60) return "${m}m ${rem}s"
        return "${m.intdiv(60)}h ${m % 60}m"
    }

    @NonCPS
    static String policyStatusFor(String key, Map counts, Map policyLimits) {
        def limits = policyLimits.get(key); if (!limits) return 'SKIP'
        return ReportViewModel.computePolicy((counts.get(key) ?: [critical: 0, high: 0, medium: 0]) as Map, limits as Map)
    }

    @NonCPS
    static String computePolicy(Map counts, Map limits) {
        def c = (counts?.get('critical') ?: 0) as int; def h = (counts?.get('high') ?: 0) as int; def m = (counts?.get('medium') ?: 0) as int
        def lc = (limits?.get('maxCritical') ?: 0) as int; def lh = (limits?.get('maxHigh') ?: 0) as int; def lm = (limits?.get('maxMedium') ?: 0) as int
        return (c <= lc && h <= lh && m <= lm) ? 'PASS' : 'WARN'
    }

    @NonCPS
    static boolean isTestJobOk(String status) {
        return status == 'SUCCESS' || status == 'ALREADY IMPLEMENTED'
    }

    @NonCPS
    static Map testJobsSummary(List jobs) {
        int passed = 0; int failed = 0; int notConfigured = 0
        for (def job : (jobs ?: [])) {
            def status = job?.get('status') as String
            if (ReportViewModel.isTestJobOk(status)) passed++
            else if (status == 'NOT_CONFIGURED') notConfigured++
            else failed++
        }
        return [total: (jobs ?: []).size(), passed: passed, failed: failed, notConfigured: notConfigured]
    }

    @NonCPS
    static String testJobStatusColor(String status) {
        if (ReportViewModel.isTestJobOk(status)) return '#16a34a'
        return status == 'NOT_CONFIGURED' ? '#94a3b8' : '#dc2626'
    }

    @NonCPS
    static Map testJobGroups(String stageName, Map projectsRemoteTestResults, Map remoteTestResults) {
        def groups = [:]
        (projectsRemoteTestResults ?: [:]).each { p, stages ->
            def l = (stages instanceof Map) ? stages.get(stageName) : null
            if (l instanceof List && !l.isEmpty()) groups[p] = l
        }
        if (groups.isEmpty()) {
            def l = remoteTestResults?.get(stageName)
            if (l instanceof List && !l.isEmpty()) groups[''] = l
        }
        return groups
    }

    @NonCPS
    static Map goldenFixFor(Map projectsGoldenFix, String projectName) {
        if (!projectsGoldenFix) return null
        if (projectName) return projectsGoldenFix.get(projectName) as Map
        return projectsGoldenFix.size() == 1 ? projectsGoldenFix.values().iterator().next() as Map : null
    }

    @NonCPS
    static boolean goldenFixHasPr(Map gf) {
        def status = gf?.get('status')
        return (status == 'PR_CREATED' || status == 'PR_UPDATED') && (gf?.get('prUrl')?.toString() ?: '')
    }

    @NonCPS
    static List projectReasonParts(String error, String project) {
        List parts = []
        if (!error || !project) return parts
        String prefix = project + ': '
        for (String part : error.replace(' ' + PolicyEngine.blockNote(), '').split(/ \|\| /)) {
            if (part.startsWith(prefix)) parts << part.substring(prefix.length())
        }
        return parts
    }

    @NonCPS
    static String projectReason(String error, String project) {
        if (!error || !project) return error ?: ''
        List parts = ReportViewModel.projectReasonParts(error, project)
        if (parts.isEmpty()) return error.replace(' || ', ' | ')
        return parts.join(' | ') + ' ' + PolicyEngine.blockNote()
    }

    @NonCPS
    static Map goldenFixPlainText(Map gf, String settingsPlace) {
        String status = (gf?.get('status') ?: '') as String
        int available = ((gf?.get('fixes') ?: []) as List).size()
        if (status == 'NOT_CONFIGURED') {
            return [headline   : 'No upgrade request was opened - the repository address is missing.',
                    explanation: "The pipeline prepared ${available} dependency upgrade(s) but does not know where this project's " +
                            "Bitbucket repository is, so it had nowhere to propose them. Someone who can change ${settingsPlace} in the repository has to fill in " +
                            'scm.bitbucket.url with the repository link and scm.bitbucket.credentialsId with the Jenkins credentials that may ' +
                            'write to it. The upgrades are then proposed automatically on the next run.']
        }
        if (status == 'NO_FIXES') {
            return [headline   : 'No upgrade is available yet.',
                    explanation: 'The supplier of the vulnerable components has not published a safe version, so there is nothing to upgrade to ' +
                            'automatically. The components have to be replaced or the risk accepted by the security team.']
        }
        if (status == 'NO_MANIFEST_CHANGES') {
            return [headline   : 'Nothing could be upgraded in this project.',
                    explanation: 'The vulnerable versions are not chosen in a file that the pipeline can change. They arrive through another library ' +
                            'or a BOM that this project depends on, or they are set in a way the pipeline does not edit, such as a version range. ' +
                            'The reasons below say where each one is set, and the upgrade has to be made there.']
        }
        if (status == 'BUILD_FAILED') {
            return [headline   : 'The upgrade was not proposed because the application stopped building with it.',
                    explanation: 'Before proposing anything the pipeline builds the application with the new versions. That build failed, and it ' +
                            'failed again with the other versions the pipeline tried, so nothing was proposed rather than handing over a change ' +
                            'that breaks the application. The vulnerabilities above are still there and the upgrade has to be done by a developer.']
        }
        if (status == 'NOT_VERIFIED') {
            return [headline   : 'The upgrade was not proposed because the pipeline could not build the application with it.',
                    explanation: 'An upgrade is only proposed after a pre-check build with the new versions has passed. The pipeline found no ' +
                            'build it could run for the changed files, so nothing was proposed. ' + ReportViewModel.goldenFixDetail(gf)]
        }
        if (status == 'SKIPPED') {
            return [headline   : 'Automatic upgrades are switched off for this project.',
                    explanation: ReportViewModel.goldenFixDetail(gf)]
        }
        if (status == 'ERROR') {
            return [headline   : 'The automatic upgrade could not be finished.',
                    explanation: 'The pipeline stopped before it could propose the upgrade, so nothing was changed in the repository and the ' +
                            'vulnerabilities above are still there. This is a fault in the pipeline run itself, not in the application - pass ' +
                            'the technical detail below to the DevSecOps team. Technical detail: ' + ReportViewModel.goldenFixDetail(gf)]
        }
        return [headline: 'Automatic dependency upgrade', explanation: ReportViewModel.goldenFixDetail(gf)]
    }

    @NonCPS
    static String goldenFixDetail(Map gf) {
        String message = (gf?.get('message') ?: '') as String
        return message ?: 'no further detail was reported.'
    }
}
