package com.bbh.build

import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class RemoteTestJobRunner implements Serializable {

    private final def script
    private final PipelineState state
    private final PolicyEngine policy

    RemoteTestJobRunner(def script, PipelineState state, PolicyEngine policy) {
        this.script = script
        this.state = state
        this.policy = policy
    }

    void runTestJobs(String stageName, String label, Map testCfg) {
        def required = testCfg?.required == null ? true : testCfg?.required
        if (!required) {
            script.echo "This test is not required or implemented in other step"
            state.recordTestJobs(stageName, [[index: 1, name: 'Test not required', type: 'unknown', status: 'ALREADY IMPLEMENTED', url: '', durationMs: 0L, message: '']])
            script.echo "remoteTestResults: ${state.remoteTestResults[stageName]}"
            return
        }

        List<Map> jobs = RemoteTestJobRunner.normalizeTestJobs(testCfg)
        if (!jobs) {
            state.recordTestJobs(stageName, [])
            policy.warn(stageName, "${label}: no test jobs configured, at least one job is required in ${state.settingsPlace()}.")
            return
        }

        int defaultPollSec = (testCfg.pollIntervalSec ?: 15) as int
        int maxParallel = RemoteTestJobRunner.resolveMaxParallel(testCfg, state.cfg?.tests?.maxParallel, jobs.size())
        List results = []
        for (int i = 0; i < jobs.size(); i++) results << null

        script.echo "[${label}] Project '${state.currentProjectName}': ${jobs.size()} job(s), max ${maxParallel} running in parallel"
        def tasks = [:]
        if (jobs.size() <= maxParallel) {
            for (int i = 0; i < jobs.size(); i++) {
                int idx = i
                tasks[RemoteTestJobRunner.branchName(idx, jobs[idx].name as String)] = {
                    results[idx] = runSingleTestJob(label, jobs[idx], idx, defaultPollSec)
                }
            }
        } else {
            def cursor = [next: 0]
            for (int w = 0; w < maxParallel; w++) {
                tasks["${label} worker ${w + 1}".toString()] = {
                    while (true) {
                        int idx = cursor.next as int
                        if (idx >= jobs.size()) break
                        cursor.next = idx + 1
                        results[idx] = runSingleTestJob(label, jobs[idx], idx, defaultPollSec)
                    }
                }
            }
        }
        tasks.failFast = false
        script.parallel tasks

        List resultList = results.findAll { it != null }
        state.recordTestJobs(stageName, resultList)
        Map summary = RemoteTestJobRunner.summarizeTestJobs(resultList)
        script.echo "[${label}] Project '${state.currentProjectName}' summary: total=${summary.total} passed=${summary.passed} failed=${summary.failed} notConfigured=${summary.notConfigured}"

        List failed = resultList.findAll { it.status != 'SUCCESS' }
        if (failed) {
            String details = RemoteTestJobRunner.failedJobsMessage(failed, 10)
            String summary2 = failed.size() == 1 ? details : "${failed.size()} of ${resultList.size()} job(s) failed: ${details}"
            policy.warn(stageName, "${label} failed - ${summary2}.")
        }
    }

    Map runSingleTestJob(String label, Map jobCfg, int idx, int defaultPollSec) {
        String jobId         = jobCfg.name as String
        String type          = (jobCfg.type ?: 'local') as String
        String jobPath       = (jobCfg.job ?: '') as String
        String params        = (jobCfg.parameters?.toString()?.trim() ?: '') as String
        int tmMin            = (jobCfg.timeoutMin ?: 30) as int
        int pollSec          = (jobCfg.pollIntervalSec ?: defaultPollSec) as int
        String remJenkins    = (jobCfg.remoteJenkins?.toString()?.trim() ?: '') as String
        String remJenkinsUrl = (jobCfg.remoteJenkinsUrl?.toString()?.trim() ?: '') as String

        long startMs   = System.currentTimeMillis()
        String status  = 'UNKNOWN'
        String url     = ''
        String message = ''
        try {
            if (!jobPath) {
                status  = 'NOT_CONFIGURED'
                message = 'job path / url not set'
                script.echo "[${label}/${jobId}] Skipped - job path not set"
            } else if (type == 'remote') {
                if (!RemoteTestJobRunner.isHttpUrl(jobPath) && !remJenkins && !remJenkinsUrl) {
                    status  = 'NOT_CONFIGURED'
                    message = 'remoteJenkins, remoteJenkinsUrl or a full job URL is required for remote jobs'
                    script.echo "[${label}/${jobId}] Skipped - ${message}"
                } else {
                    script.echo "[${label}/${jobId}] Triggering remote job '${jobPath}'${remJenkins ? " on '" + remJenkins + "'" : ''}"
                    Map args = RemoteTestJobRunner.remoteTriggerArgs(jobCfg, jobPath, params, pollSec, remJenkins, remJenkinsUrl)
                    String tokenId = (jobCfg.tokenCredentialsId?.toString()?.trim() ?: '') as String
                    def handle
                    script.timeout(time: tmMin, unit: 'MINUTES') {
                        if (tokenId) {
                            script.withCredentials([script.string(credentialsId: tokenId, variable: 'REMOTE_JOB_TOKEN')]) {
                                args.token = script.env.REMOTE_JOB_TOKEN
                                handle = script.triggerRemoteJob(args)
                            }
                        } else {
                            handle = script.triggerRemoteJob(args)
                        }
                    }
                    try {
                        status = handle.getBuildResult()?.toString() ?: 'UNKNOWN'
                    } catch (se) {
                    }
                    try {
                        url = handle.getBuildUrl()?.toString() ?: ''
                    } catch (se) {
                    }
                    if (!status || status == 'UNKNOWN') {
                        try {
                            status = handle.getBuildResult() ? 'SUCCESS' : 'FAILURE'
                        } catch (se2) {
                            status = 'UNKNOWN'
                        }
                    }
                }
            } else {
                script.echo "[${label}/${jobId}] Triggering local job '${jobPath}'"
                def paramList = []
                if (params) {
                    for (String line : params.split('\n')) {
                        def t = line.trim()
                        if (t) {
                            def kv = t.split('=', 2)
                            paramList << script.string(name: kv[0].trim(), value: kv.length > 1 ? kv[1].trim() : '')
                        }
                    }
                }
                def handle
                script.timeout(time: tmMin, unit: 'MINUTES') {
                    handle = script.build(job: jobPath, parameters: paramList, wait: true, propagate: false)
                }
                status = handle?.getResult()?.toString() ?: 'UNKNOWN'
                try {
                    url = handle?.absoluteUrl?.toString() ?: ''
                } catch (se) {
                }
            }
        } catch (ex) {
            String kind = RemoteTestJobRunner.interruptionKind(ex)
            if (kind == 'ABORTED') throw ex
            status  = kind ?: 'ERROR'
            message = (ex.message ?: ex.getClass().getSimpleName()) as String
            script.echo "[${label}/${jobId}] ${status}: ${message}"
        }
        long durationMs = System.currentTimeMillis() - startMs
        script.echo "[${label}/${jobId}] type=${type} result=${status} duration=${BuildUtils.msFmt(durationMs)}"
        return [index: idx + 1, name: jobId, type: type, status: status, url: url, durationMs: durationMs, message: message]
    }

    @NonCPS
    static List<Map> normalizeTestJobs(Map testCfg) {
        List<Map> out = []
        if (!testCfg) return out
        Map defaults = (testCfg.defaults instanceof Map) ? (testCfg.defaults as Map) : [:]
        List raw = []
        if (testCfg.jobs instanceof List) raw.addAll(testCfg.jobs as List)
        if (testCfg.urls instanceof List) raw.addAll(testCfg.urls as List)

        Map nameCounts = [:]
        for (int i = 0; i < raw.size(); i++) {
            def entry = raw[i]
            if (entry == null) continue
            Map job = [:] + defaults
            if (entry instanceof Map) {
                job.putAll(entry as Map)
            } else {
                job.url = entry.toString().trim()
            }
            String ref = ((job.url ?: job.job ?: '') as String).trim()
            job.job  = ref
            job.type = ((job.type ?: (RemoteTestJobRunner.isHttpUrl(ref) ? 'remote' : 'local')) as String).trim()
            String name = ((job.name ?: '') as String).trim()
            if (!name) name = RemoteTestJobRunner.deriveJobName(ref, out.size())
            int seen = ((nameCounts[name] ?: 0) as int) + 1
            nameCounts[name] = seen
            job.name = seen > 1 ? "${name} (#${seen})".toString() : name
            out << job
        }
        return out
    }

    @NonCPS
    static String deriveJobName(String ref, int idx) {
        if (!ref) return "job-${idx + 1}".toString()
        def m = (ref =~ /\/job\/([^\/?#]+)/)
        List parts = []
        while (m.find()) parts << RemoteTestJobRunner.decodeSegment(m.group(1))
        if (parts) return parts.join('/')
        String trimmed = ref.replaceAll('/+$', '')
        return trimmed.contains('/') ? trimmed.substring(trimmed.lastIndexOf('/') + 1) : trimmed
    }

    @NonCPS
    static String decodeSegment(String value) {
        return (value ?: '').replace('%20', ' ').replace('+', ' ')
    }

    @NonCPS
    static boolean isHttpUrl(String value) {
        return (value ?: '') ==~ /(?i)^https?:\/\/\S+$/
    }

    @NonCPS
    static int resolveMaxParallel(Map testCfg, def globalDefault, int total) {
        def raw = testCfg?.maxParallel != null ? testCfg.maxParallel : (globalDefault != null ? globalDefault : 20)
        int n = 20
        try {
            n = raw.toString().trim().toInteger()
        } catch (ignored) {
        }
        if (n < 1) n = 1
        return Math.min(n, Math.max(total, 1))
    }

    @NonCPS
    static String branchName(int idx, String name) {
        String n = "${idx + 1}. ${name}".toString()
        return n.length() > 80 ? n.substring(0, 77) + '...' : n
    }

    @NonCPS
    static Map remoteTriggerArgs(Map jobCfg, String jobPath, String params, int pollSec, String remJenkins, String remJenkinsUrl) {
        Map args = [
                abortTriggeredJob           : BuildUtils.booleanValue(jobCfg.abortTriggeredJob, false),
                job                         : jobPath,
                overrideTrustAllCertificates: BuildUtils.booleanValue(jobCfg.overrideTrustAllCertificates, false),
                pollInterval                : pollSec,
                preventRemoteBuildQueue     : BuildUtils.booleanValue(jobCfg.preventRemoteBuildQueue, false),
                token                       : (jobCfg.token?.toString()?.trim() ?: ''),
                trustAllCertificates        : BuildUtils.booleanValue(jobCfg.trustAllCertificates, false),
                useCrumbCache               : BuildUtils.booleanValue(jobCfg.useCrumbCache, false),
                useJobInfoCache             : BuildUtils.booleanValue(jobCfg.useJobInfoCache, false),
                auth                        : RemoteTestJobRunner.remoteAuth(jobCfg),
                parameters                  : params,
                blockBuildUntilComplete     : true,
                shouldNotFailBuild          : true
        ]
        if (remJenkins) args.remoteJenkinsName = remJenkins
        if (remJenkinsUrl) args.remoteJenkinsUrl = remJenkinsUrl
        return args
    }

    @NonCPS
    static def remoteAuth(Map jobCfg) {
        if (jobCfg.auth) return jobCfg.auth
        if (jobCfg.credentialsId) return ['$class': 'CredentialsAuth', credentials: jobCfg.credentialsId.toString()]
        return [:]
    }

    @NonCPS
    static Map summarizeTestJobs(List results) {
        int passed = 0
        int failed = 0
        int notConfigured = 0
        for (def r : (results ?: [])) {
            String s = r?.status as String
            if (s == 'SUCCESS' || s == 'ALREADY IMPLEMENTED') passed++
            else if (s == 'NOT_CONFIGURED') notConfigured++
            else failed++
        }
        return [total: (results ?: []).size(), passed: passed, failed: failed, notConfigured: notConfigured]
    }

    @NonCPS
    static String failedJobsMessage(List failed, int limit) {
        List msgs = failed.collect { "${it.name}: ${it.status}".toString() }
        if (msgs.size() <= limit) return msgs.join(', ')
        return msgs.subList(0, limit).join(', ') + " ... and ${msgs.size() - limit} more".toString()
    }

    @NonCPS
    static String interruptionKind(Throwable ex) {
        if (ex == null || !ex.getClass().getName().endsWith('FlowInterruptedException')) return ''
        List causes = []
        try {
            causes = ex.getCauses() as List
        } catch (ignored) {
        }
        return causes.any { it?.getClass()?.getSimpleName() == 'ExceededTimeout' } ? 'TIMEOUT' : 'ABORTED'
    }
}
