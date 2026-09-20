package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class RestClient implements Serializable {

    private final def script

    RestClient(def script) {
        this.script = script
    }

    def getJson(String url, Map auth, String label) {
        Map response = request('GET', url, null, auth, label)
        ensureSuccess(response, label)
        return parseJson(response.body as String)
    }

    def postJson(String url, def payload, Map auth, String label) {
        Map response = request('POST', url, payload, auth, label)
        ensureSuccess(response, label)
        return parseJson(response.body as String)
    }

    Map request(String method, String url, def payload, Map auth, String label) {
        String tmpDir = script.env.WORKSPACE_TMP ?: "${script.env.WORKSPACE}@tmp"
        String bodyFile = ''
        if (payload != null) {
            bodyFile = "${tmpDir}/rest-body-${System.currentTimeMillis()}.json"
            script.writeJSON(file: bodyFile, json: payload)
        }
        String bodyArgs = bodyFile ? "-H 'Content-Type: application/json' --data-binary @'${BuildUtils.escapeForSingleQuotes(bodyFile)}'" : ''
        String out
        try {
            out = script.sh(label: label ?: "HTTP ${method}", returnStdout: true, script: """#!/bin/bash
set +x
set -euo pipefail
curl -sS -X '${method}' ${authArgs(auth)} -H 'Accept: application/json' ${bodyArgs} -w '\\n%{http_code}' '${BuildUtils.escapeForSingleQuotes(url)}'
""")
        } finally {
            if (bodyFile) script.sh(label: 'Remove request body', script: "rm -f '${BuildUtils.escapeForSingleQuotes(bodyFile)}'")
        }
        return splitStatus(out)
    }

    def parseJson(String text) {
        if (!text?.trim()) return null
        return withoutJsonNulls(script.readJSON(text: text))
    }

    @NonCPS
    static def withoutJsonNulls(def value) {
        if (value == null) return null
        if (value.getClass().getName() == 'net.sf.json.JSONNull') return null
        if (value instanceof Map) {
            Map out = [:]
            for (def entry : ((Map) value).entrySet()) {
                out[entry.key] = withoutJsonNulls(entry.value)
            }
            return out
        }
        if (value instanceof List) {
            List out = []
            for (def item : ((List) value)) {
                out << withoutJsonNulls(item)
            }
            return out
        }
        return value
    }

    void ensureSuccess(Map response, String label) {
        int status = (response.status ?: 0) as int
        if (status < 200 || status > 299) {
            script.error("[HTTP] ${label ?: 'Request'} failed with HTTP ${status}: ${abbreviate(response.body as String, 500)}")
        }
    }

    @NonCPS
    static String urlEncode(String value) {
        return (value ?: '')
                .replace('%', '%25').replace(' ', '%20').replace('"', '%22').replace('#', '%23')
                .replace('&', '%26').replace('+', '%2B').replace('/', '%2F').replace(':', '%3A')
                .replace(';', '%3B').replace('<', '%3C').replace('=', '%3D').replace('>', '%3E')
                .replace('?', '%3F').replace('@', '%40').replace('\\', '%5C')
                .replace('{', '%7B').replace('|', '%7C').replace('}', '%7D')
    }

    @NonCPS
    static String abbreviate(String text, int max) {
        if (text == null) return ''
        return text.length() > max ? text.substring(0, max) + '...' : text
    }

    @NonCPS
    private static String authArgs(Map auth) {
        if (!auth) return ''
        if (auth.type == 'bearer') return "-H \"Authorization: Bearer \$${auth.tokenVar}\""
        return "-u \"\$${auth.userVar}:\$${auth.passVar}\""
    }

    @NonCPS
    private static Map splitStatus(String out) {
        String text = out ?: ''
        while (text.endsWith('\n') || text.endsWith('\r')) text = text.substring(0, text.length() - 1)
        int idx = text.lastIndexOf('\n')
        String code = (idx >= 0 ? text.substring(idx + 1) : text).trim()
        String body = idx >= 0 ? text.substring(0, idx) : ''
        return [status: (code ==~ /\d+/) ? code.toInteger() : 0, body: body]
    }
}
