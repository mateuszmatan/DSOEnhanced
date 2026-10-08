package com.bbh.metrics

import com.bbh.core.PipelineState
import com.cloudbees.groovy.cps.NonCPS

class PipelineMetrics implements Serializable {

    @NonCPS
    static List<String> lines(PipelineState state, Map context) {
        List<String> out = []
        Map base = [project: context.project, env: context.env]
        long now = context.timestamp as long

        out.addAll(legacy(state, base, context, now))
        out.addAll(run(state, base, context, now))
        out.addAll(dora(state, base, context, now))
        out.addAll(stages(state, base, now))
        out.addAll(findings(state, base, now))
        out.addAll(coverage(state, base, now))
        out.addAll(tests(state, base, now))
        out.addAll(unitTests(state, base, now))
        out.addAll(evidence(state, base, context, now))
        out.addAll(gate(state, base, context, now))
        out.addAll(goldenFix(state, base, now))
        return out.findAll { it }
    }

    @NonCPS
    static List<String> legacy(PipelineState state, Map base, Map context, long now) {
        List<String> out = []
        out << MetricLine.of('deployments', base, [count: '1'], now)
        out << MetricLine.of('change_failure', base, [value: MetricLine.integer(context.failed ? 1 : 0)], now)
        out << MetricLine.of('build_duration', base, [value: MetricLine.integer(context.durationSeconds)], now)
        for (def entry : (state.stageResults ?: [:]).entrySet()) {
            String stage = entry.key as String
            String status = entry.value as String
            Map times = timesOf(state, stage)
            out << MetricLine.of('stage_metric', base + [stage: stage], [
                    result     : MetricLine.text(green(status) ? 'success' : 'failure'),
                    start_time : MetricLine.integer(times.startSeconds),
                    end_time   : MetricLine.integer(times.endSeconds),
                    duration_ms: MetricLine.integer(times.durationMs)
            ], now)
        }
        for (String scanner : ['sast', 'dast']) {
            Map counts = (state.vulnCounts[scanner] ?: [:]) as Map
            out << MetricLine.of('vulnerabilities', base + [scanner: scanner], severityFields(counts), now)
        }
        Map niq = nexusIqTotals(state)
        if (niq) out << MetricLine.of('vulnerabilities', base + [scanner: 'nexusiq'], severityFields(niq), now)
        Map sonar = totals((state.projectsSonarResults ?: [:]) as Map, (state.sonarResults ?: [:]) as Map)
        if (sonar) out << MetricLine.of('vulnerabilities', base + [scanner: 'sonar'], severityFields(sonar), now)
        if (state.coverage && state.coverage.enabled) {
            out << MetricLine.of('test_coverage', base, [
                    line_pct: MetricLine.number(state.coverage.line),
                    covered : MetricLine.integer(state.coverage.covered),
                    total   : MetricLine.integer(state.coverage.total)
            ], now)
        }
        return out
    }

    @NonCPS
    static List<String> run(PipelineState state, Map base, Map context, long now) {
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
        Map tags = base + [variant: context.variant, result: context.result, branch: context.branch]
        return [MetricLine.of('pipeline_run', tags, [
                build        : MetricLine.integer(context.buildNumber),
                duration_s   : MetricLine.integer(context.durationSeconds),
                success      : MetricLine.flag(!(context.failed as boolean)),
                unstable     : MetricLine.flag(context.result == 'UNSTABLE'),
                stages_total : MetricLine.integer((state.stageResults ?: [:]).size()),
                passed       : MetricLine.integer(tally.passed),
                warned       : MetricLine.integer(tally.warned),
                failed       : MetricLine.integer(tally.failed),
                blocked      : MetricLine.integer(tally.blocked),
                skipped      : MetricLine.integer(tally.skipped),
                not_required : MetricLine.integer(tally.notRequired),
                modules      : MetricLine.integer((state.projectsAllCfg ?: [:]).size()),
                commit       : MetricLine.text((state.commitSha ?: '').take(12)),
                job          : MetricLine.text(context.job as String)
        ], now)]
    }

    @NonCPS
    static List<String> dora(PipelineState state, Map base, Map context, long now) {
        long leadSeconds = 0L
        long commit = (state.commitTime ?: 0L) as long
        if (commit > 0L) {
            long delta = ((now * 1000L) - commit) / 1000L
            leadSeconds = delta > 0L ? delta : 0L
        }
        boolean deployed = context.deployed as boolean
        Map fields = [
                deployment    : MetricLine.flag(deployed),
                change_failure: MetricLine.flag(context.failed as boolean),
                lead_time_s   : MetricLine.integer(leadSeconds),
                duration_s    : MetricLine.integer(context.durationSeconds),
                released      : MetricLine.flag(context.released as boolean)
        ]
        return [MetricLine.of('dora', base + [variant: context.variant], fields, now)]
    }

    @NonCPS
    static List<String> stages(PipelineState state, Map base, long now) {
        List<String> out = []
        int order = 0
        for (def entry : (state.stageResults ?: [:]).entrySet()) {
            String stage = entry.key as String
            String status = entry.value as String
            order++
            Map times = timesOf(state, stage)
            long at = (times.endSeconds as long) > 0L ? (times.endSeconds as long) : now
            String reason = ((state.stageErrors ?: [:]).get(stage) ?: '') as String
            out << MetricLine.of('stage_event', base + [stage: stage, status: status], [
                    duration_ms: MetricLine.integer(times.durationMs),
                    duration_s : MetricLine.integer(((times.durationMs ?: 0L) as long) / 1000L),
                    ok         : MetricLine.flag(green(status)),
                    executed   : MetricLine.flag(status != 'SKIP'),
                    order      : MetricLine.integer(order),
                    reason     : MetricLine.text(reason)
            ], at)
        }
        return out
    }

    @NonCPS
    static List<String> findings(PipelineState state, Map base, long now) {
        List<String> out = []
        Map perProject = (state.projectsVulnCounts ?: [:]) as Map
        for (def projectEntry : perProject.entrySet()) {
            String module = projectEntry.key as String
            Map scanners = (projectEntry.value ?: [:]) as Map
            for (def scannerEntry : scanners.entrySet()) {
                String scanner = scannerEntry.key as String
                Map counts = (scannerEntry.value ?: [:]) as Map
                Map limits = ((state.policyLimits ?: [:]).get(limitKey(scanner)) ?: [:]) as Map
                int critical = intOf(counts.critical)
                int high = intOf(counts.high)
                int medium = intOf(counts.medium)
                boolean exceeded = critical > intOf(limits.maxCritical) || high > intOf(limits.maxHigh) || medium > intOf(limits.maxMedium)
                String status = ((state.projectsScanResults ?: [:]).get(module) ?: [:])?.get(scanner) ?: 'SKIP'
                out << MetricLine.of('security_findings', base + [module: module, scanner: scanner, status: status as String], [
                        critical    : MetricLine.integer(critical),
                        high        : MetricLine.integer(high),
                        medium      : MetricLine.integer(medium),
                        low         : MetricLine.integer(counts.low),
                        total       : MetricLine.integer(critical + high + medium + intOf(counts.low)),
                        above_policy: MetricLine.integer(overBy(critical, limits.maxCritical) + overBy(high, limits.maxHigh) + overBy(medium, limits.maxMedium)),
                        max_critical: MetricLine.integer(limits.maxCritical),
                        max_high    : MetricLine.integer(limits.maxHigh),
                        max_medium  : MetricLine.integer(limits.maxMedium),
                        exceeded    : MetricLine.flag(exceeded)
                ], now)
            }
        }
        for (def entry : (state.policyStatus ?: [:]).entrySet()) {
            String scanner = entry.key as String
            String status = entry.value as String
            out << MetricLine.of('policy_status', base + [scanner: scanner, status: status], [
                    ok      : MetricLine.flag(status == 'PASS' || status == 'NOT_REQUIRED'),
                    measured: MetricLine.flag(status != 'SKIP')
            ], now)
        }
        return out
    }

    @NonCPS
    static List<String> coverage(PipelineState state, Map base, long now) {
        List<String> out = []
        Map perProject = (state.projectsCoverage ?: [:]) as Map
        Map rows = perProject ? perProject : ((state.coverage ?: [:]).get('enabled') ? ['': state.coverage] : [:])
        for (def entry : rows.entrySet()) {
            Map value = (entry.value ?: [:]) as Map
            double line = ((value.line ?: 0.0) as Number).doubleValue()
            double required = ((value.minRequired ?: 60) as Number).doubleValue()
            boolean enabled = value.enabled ? true : false
            out << MetricLine.of('code_coverage', base + [module: (entry.key as String) ?: '', measured: enabled ? 'yes' : 'no'], [
                    line_pct: MetricLine.number(line),
                    covered : MetricLine.integer(value.covered),
                    missed  : MetricLine.integer(value.missed),
                    total   : MetricLine.integer(value.total),
                    required: MetricLine.number(required),
                    met     : MetricLine.flag(enabled && line >= required),
                    gap     : MetricLine.number(enabled && line < required ? (required - line) : 0.0d)
            ], now)
        }
        return out
    }

    @NonCPS
    static List<String> tests(PipelineState state, Map base, long now) {
        List<String> out = []
        Map perProject = (state.projectsRemoteTestResults ?: [:]) as Map
        for (def projectEntry : perProject.entrySet()) {
            String module = projectEntry.key as String
            Map suites = (projectEntry.value ?: [:]) as Map
            for (def suiteEntry : suites.entrySet()) {
                String suite = suiteEntry.key as String
                List jobs = (suiteEntry.value ?: []) as List
                int passed = 0
                int failed = 0
                int notConfigured = 0
                long duration = 0L
                for (def job : jobs) {
                    Map row = (job ?: [:]) as Map
                    String status = (row.status ?: 'UNKNOWN') as String
                    duration += ((row.durationMs ?: 0L) as Number).longValue()
                    if (status == 'SUCCESS' || status == 'ALREADY IMPLEMENTED') passed++
                    else if (status == 'NOT_CONFIGURED') notConfigured++
                    else failed++
                    out << MetricLine.of('test_job', base + [module: module, suite: suite, status: status, type: (row.type ?: 'local') as String], [
                            duration_ms: MetricLine.integer(row.durationMs),
                            name       : MetricLine.text((row.name ?: '') as String),
                            ok         : MetricLine.flag(status == 'SUCCESS' || status == 'ALREADY IMPLEMENTED')
                    ], now)
                }
                int total = jobs.size()
                out << MetricLine.of('test_execution', base + [module: module, suite: suite], [
                        total         : MetricLine.integer(total),
                        passed        : MetricLine.integer(passed),
                        failed        : MetricLine.integer(failed),
                        not_configured: MetricLine.integer(notConfigured),
                        duration_ms   : MetricLine.integer(duration),
                        success_rate  : MetricLine.number(total > 0 ? (passed * 100.0d / total) : 0.0d)
                ], now)
            }
        }
        return out
    }

    @NonCPS
    static List<String> unitTests(PipelineState state, Map base, long now) {
        List<String> out = []
        for (def entry : ((state.projectsUnitTests ?: [:]) as Map).entrySet()) {
            Map value = (entry.value ?: [:]) as Map
            if (!value.containsKey('total')) continue
            int total = intOf(value.total)
            int failed = intOf(value.failed)
            int skipped = intOf(value.skipped)
            int passed = total - failed - skipped
            out << MetricLine.of('test_execution', base + [module: entry.key as String, suite: 'unit'], [
                    total         : MetricLine.integer(total),
                    passed        : MetricLine.integer(passed),
                    failed        : MetricLine.integer(failed),
                    skipped       : MetricLine.integer(skipped),
                    not_configured: MetricLine.integer(0),
                    duration_ms   : MetricLine.integer(value.durationMs),
                    success_rate  : MetricLine.number(total > 0 ? (passed * 100.0d / total) : 0.0d)
            ], now)
        }
        return out
    }

    @NonCPS
    static List<String> evidence(PipelineState state, Map base, Map context, long now) {
        List<String> out = []
        String artifactBase = context.buildUrl ? "${context.buildUrl}artifact/".toString() : ''
        Map documents = [:]
        for (def item : (state.portalDocuments ?: [])) {
            Map document = (item ?: [:]) as Map
            for (def name : ((((document.config ?: [:]) as Map).projects ?: [:]) as Map).keySet()) documents[name] = document
        }
        for (def entry : ((state.projectsAllCfg ?: [:]) as Map).entrySet()) {
            String module = entry.key as String
            Map cfg = (entry.value ?: [:]) as Map
            Map scans = ((state.projectsScanResults ?: [:]).get(module) ?: [:]) as Map
            Map sonar = ((state.projectsSonarResults ?: [:]).get(module) ?: [:]) as Map
            Map niq = ((state.projectsNexusIqResults ?: [:]).get(module) ?: [:]) as Map
            Map document = (documents.get(module) ?: [:]) as Map
            def delivery = cfg.delivery
            String version = (cfg.deploy?.openshift?.rd?.buildTag ?: (delivery instanceof String ? delivery : null) ?: context.buildNumber ?: '') as String
            String qualityGate = (sonar.qualityGate ?: '') as String
            out << MetricLine.of('build_evidence', base + [module: module], [
                    artifact_version  : optional(version),
                    sonar_quality_gate: MetricLine.text(qualityGate in ['OK', 'WARN', 'ERROR'] ? qualityGate : 'NONE'),
                    sast_report_url   : optional(artifactBase && scans.sast_file ? artifactBase + scans.sast_file : ''),
                    dast_report_url   : optional(artifactBase && scans.dast_file ? artifactBase + scans.dast_file : ''),
                    nexusiq_report_url: optional(niq.url),
                    sonar_report_url  : optional(sonar.url),
                    config_rendered_at: optional(document.renderedAt),
                    config_sha256     : optional(document.sha256)
            ], now)
        }
        return out
    }

    @NonCPS
    static String optional(def value) {
        String text = (value ?: '') as String
        return text ? MetricLine.text(text) : null
    }

    @NonCPS
    static List<String> gate(PipelineState state, Map base, Map context, long now) {
        Map decision = (context.releaseGate ?: [:]) as Map
        boolean allowed = decision.allowed ? true : false
        List violations = (decision.violations ?: []) as List
        return [MetricLine.of('release_gate', base + [allowed: allowed ? 'yes' : 'no'], [
                allowed   : MetricLine.flag(allowed),
                blocked   : MetricLine.flag(!allowed),
                violations: MetricLine.integer(violations.size()),
                reason    : MetricLine.text((decision.reason ?: '') as String)
        ], now)]
    }

    @NonCPS
    static List<String> goldenFix(PipelineState state, Map base, long now) {
        List<String> out = []
        for (def entry : ((state.projectsGoldenFix ?: [:]) as Map).entrySet()) {
            Map value = (entry.value ?: [:]) as Map
            String status = (value.status ?: 'SKIPPED') as String
            boolean raised = status == 'PR_CREATED' || status == 'PR_UPDATED'
            out << MetricLine.of('goldenfix', base + [module: entry.key as String, status: status], [
                    offered    : MetricLine.integer(((value.fixes ?: []) as List).size()),
                    applied    : MetricLine.integer(((value.changes ?: []) as List).size()),
                    unresolved : MetricLine.integer(((value.unresolved ?: []) as List).size()),
                    pr_raised  : MetricLine.flag(raised),
                    build_check: MetricLine.flag(value.verified == true),
                    build_failed: MetricLine.flag(status == 'BUILD_FAILED'),
                    pr_url     : optional(raised ? value.prUrl : null),
                    pr_title   : optional(raised ? value.prTitle : null)
            ], now)
        }
        return out
    }

    @NonCPS
    static Map timesOf(PipelineState state, String stage) {
        Map times = ((state.stageTimes ?: [:]).get(stage) ?: [:]) as Map
        long start = ((times.start ?: 0L) as Number).longValue()
        long end = ((times.end ?: 0L) as Number).longValue()
        long durationMs = (start > 0L && end > start) ? (end - start) : 0L
        return [startSeconds: start / 1000L, endSeconds: end / 1000L, durationMs: durationMs]
    }

    @NonCPS
    static boolean green(String status) {
        return status == 'PASS' || status == 'NOT_REQUIRED'
    }

    @NonCPS
    static String limitKey(String scanner) {
        return scanner == 'sonar' ? 'sca' : scanner
    }

    @NonCPS
    static int intOf(def value) {
        return ((value ?: 0) as Number).intValue()
    }

    @NonCPS
    static int overBy(int value, def limit) {
        int max = intOf(limit)
        return value > max ? (value - max) : 0
    }

    @NonCPS
    static Map severityFields(Map counts) {
        return [
                critical: MetricLine.integer(counts?.critical),
                high    : MetricLine.integer(counts?.high),
                medium  : MetricLine.integer(counts?.medium),
                low     : MetricLine.integer(counts?.low)
        ]
    }

    @NonCPS
    static Map nexusIqTotals(PipelineState state) {
        return totals((state.projectsNexusIqResults ?: [:]) as Map, (state.nexusIqResults ?: [:]) as Map)
    }

    @NonCPS
    static Map totals(Map perProject, Map fallback) {
        List sources = perProject ? (perProject.values() as List) : (fallback ? [fallback] : [])
        if (!sources) return [:]
        int critical = 0
        int high = 0
        int medium = 0
        for (def entry : sources) {
            Map values = (entry ?: [:]) as Map
            critical += intOf(values.critical)
            high += intOf(values.high)
            medium += intOf(values.medium)
        }
        return [critical: critical, high: high, medium: medium, low: 0]
    }
}
