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

    List postJsonBatch(List requests, Map auth, String label, int parallelism = 8) {
        List pending = (requests ?: []) as List
        if (pending.isEmpty()) return []
        if (pending.size() == 1) {
            Map only = pending.get(0) as Map
            Map single = request('POST', only.url as String, only.payload, auth, label)
            return [responseOf(single, label)]
        }
        String tmpDir = script.env.WORKSPACE_TMP ?: "${script.env.WORKSPACE}@tmp"
        String dir = "${tmpDir}/rest-batch-${System.currentTimeMillis()}"
        String out = script.sh(label: label ?: "HTTP POST x${pending.size()}", returnStdout: true,
                script: batchScript(dir, pending, auth, parallelism < 1 ? 1 : parallelism))
        List bodies = splitBatch(out, pending.size())
        List parsed = []
        for (int i = 0; i < bodies.size(); i++) {
            parsed << responseOf(bodies.get(i) as Map, label)
        }
        return parsed
    }

    private Map responseOf(Map response, String label) {
        int status = (response.status ?: 0) as int
        if (status < 200 || status > 299) {
            return [ok: false, status: status, json: null, error: abbreviate(response.body as String, 300)]
        }
        return [ok: true, status: status, json: parseJson(response.body as String), error: '']
    }

    @NonCPS
    private String batchScript(String dir, List requests, Map auth, int parallelism) {
        StringBuilder sb = new StringBuilder()
        sb.append("#!/bin/bash\nset +x\nset +e\n")
        sb.append("mkdir -p '").append(BuildUtils.escapeForSingleQuotes(dir)).append("'\n")
        sb.append("cd '").append(BuildUtils.escapeForSingleQuotes(dir)).append("'\n")
        for (int i = 0; i < requests.size(); i++) {
            Map r = requests.get(i) as Map
            sb.append("cat > body-").append(i).append(".json <<'DEVSECOPS_BODY_").append(i).append("'\n")
            sb.append(toJson(r.payload)).append("\n")
            sb.append("DEVSECOPS_BODY_").append(i).append("\n")
        }
        sb.append("run_one() {\n  curl -sS -X POST ").append(authArgs(auth))
        sb.append(" -H 'Accept: application/json' -H 'Content-Type: application/json'")
        sb.append(" --data-binary @\"body-\$1.json\" -w '\\n%{http_code}' \"\$2\" > \"resp-\$1.out\" 2>&1\n}\n")
        for (int i = 0; i < requests.size(); i++) {
            Map r = requests.get(i) as Map
            sb.append("run_one ").append(i).append(" '").append(BuildUtils.escapeForSingleQuotes(r.url as String)).append("' &\n")
            if ((i + 1) % parallelism == 0) sb.append("wait\n")
        }
        sb.append("wait\n")
        for (int i = 0; i < requests.size(); i++) {
            sb.append("echo '===DEVSECOPS-RESPONSE ").append(i).append("==='\n")
            sb.append("cat \"resp-").append(i).append(".out\" 2>/dev/null\n")
            sb.append("echo\n")
        }
        sb.append("cd / && rm -rf '").append(BuildUtils.escapeForSingleQuotes(dir)).append("'\nexit 0\n")
        return sb.toString()
    }

    @NonCPS
    static List splitBatch(String out, int expected) {
        List parts = []
        String text = out ?: ''
        for (int i = 0; i < expected; i++) {
            String marker = "===DEVSECOPS-RESPONSE ${i}===".toString()
            int start = text.indexOf(marker)
            if (start < 0) {
                parts << [status: 0, body: '']
                continue
            }
            start = start + marker.length()
            int end = text.indexOf('===DEVSECOPS-RESPONSE ', start)
            String chunk = (end < 0 ? text.substring(start) : text.substring(start, end))
            parts << splitStatus(chunk.trim())
        }
        return parts
    }

    @NonCPS
    static String toJson(def value) {
        StringBuilder sb = new StringBuilder()
        writeJson(sb, value)
        return sb.toString()
    }

    @NonCPS
    private static void writeJson(StringBuilder sb, def value) {
        if (value == null) {
            sb.append('null')
        } else if (value instanceof Map) {
            sb.append('{')
            boolean first = true
            for (def entry : ((Map) value).entrySet()) {
                if (!first) sb.append(',')
                first = false
                writeJsonString(sb, entry.key as String)
                sb.append(':')
                writeJson(sb, entry.value)
            }
            sb.append('}')
        } else if (value instanceof List) {
            sb.append('[')
            boolean first = true
            for (def item : ((List) value)) {
                if (!first) sb.append(',')
                first = false
                writeJson(sb, item)
            }
            sb.append(']')
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString())
        } else {
            writeJsonString(sb, value.toString())
        }
    }

    @NonCPS
    private static void writeJsonString(StringBuilder sb, String value) {
        sb.append('"')
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i)
            if (c == ('"' as char)) sb.append('\\"')
            else if (c == ('\\' as char)) sb.append('\\\\')
            else if (c == ('\n' as char)) sb.append('\\n')
            else if (c == ('\r' as char)) sb.append('\\r')
            else if (c == ('\t' as char)) sb.append('\\t')
            else if ((c as int) < 32) sb.append(unicodeEscape(c as int))
            else sb.append(c)
        }
        sb.append('"')
    }

    @NonCPS
    private static String unicodeEscape(int code) {
        String digits = '0123456789abcdef'
        String hex = ''
        int value = code
        for (int i = 0; i < 4; i++) {
            hex = digits.charAt(value % 16) + hex
            value = value.intdiv(16)
        }
        return '\\u' + hex
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
            for (def key : ((Map) value).keySet()) {
                out[key] = withoutJsonNulls(((Map) value).get(key))
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
