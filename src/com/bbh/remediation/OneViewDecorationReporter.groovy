package com.bbh.remediation

import com.bbh.core.OneViewPost
import com.bbh.core.OneViewSettings
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.remediation.port.DecorationRecorder
import com.cloudbees.groovy.cps.NonCPS

class OneViewDecorationReporter implements DecorationRecorder {

    private final def script
    private final PipelineState state
    private final OneViewSettings settings
    private final OneViewPost post
    private boolean stopped = false

    OneViewDecorationReporter(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state = state
        this.settings = new OneViewSettings(script, state)
        this.post = new OneViewPost(script, os)
    }

    void recorded(Map decoration) {
        if (stopped) return
        Map entry = settings.decorationKey((decoration.service ?: '') as String)
        if (!entry.key) return
        Map target = settings.reportTarget('The pull request decorations are not reported to OneView')
        String address = (target.address ?: '') as String
        if (!address) return
        String path = '/api/dso/bitbucket/pull-requests/decorate'
        String shown = target.strict ? "${address}${path}".toString() : "the address in ${target.name} (path ${path})".toString()
        String keyHint = (entry.hint ?: '') as String
        String where = (decoration.pullRequestUrl ?: decoration.pullRequestId ?: '') as String
        long stamp = System.currentTimeMillis()
        String tmpFile = "oneview_decoration_${stamp}.json"
        String responseFile = "oneview_response_${stamp}.json"
        try {
            Map payload = buildPayload(decoration, entry.key as String)
            script.writeJSON(file: tmpFile, json: payload)
            script.echo "[ONEVIEW] Reporting the decoration of pull request ${where} of ${decoration.service} to ${shown} for job ${payload.job} build ${payload.build}${keyHint ? ' with pipeline key ' + keyHint : ''}"
            post.send(["ONEVIEW_URL=${address}", "ONEVIEW_FILE=${tmpFile}", "ONEVIEW_RESPONSE=${responseFile}"], path)
        } catch (Throwable e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            stopped = true
            String reason = (e.message ?: e.getClass().getName()) as String
            if (!target.strict) reason = reason.replace(address, "the address in ${target.name}".toString())
            script.echo "[ONEVIEW] Failed to report the decoration of pull request ${where} to OneView${keyHint ? ' for pipeline key ' + keyHint : ''}: ${reason}"
            script.echo '[ONEVIEW] The next pull request decorations of this run are not reported to OneView after the failure above'
        } finally {
            post.cleanup(tmpFile, responseFile, true)
        }
    }

    @NonCPS
    private Map buildPayload(Map decoration, String pipelineKey) {
        return decoration + [pipelineKey: pipelineKey,
                             job        : (script.env.JOB_NAME ?: '') as String,
                             build      : OneViewPost.buildNumber(script.env.BUILD_NUMBER),
                             branch     : (script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String,
                             commit     : (state.commitSha ?: '') as String]
    }
}
