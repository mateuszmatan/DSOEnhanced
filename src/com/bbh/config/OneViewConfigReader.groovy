package com.bbh.config

import com.bbh.core.OneViewSettings
import com.bbh.utils.RestClient

class OneViewConfigReader implements Serializable {

    private final def script
    private final OneViewSettings settings
    private String address = ''

    OneViewConfigReader(def script, OneViewSettings settings) {
        this.script = script
        this.settings = settings
    }

    String agent() { settings.agent() }

    String hint(String key) { settings.hint(key) }

    List<String> normalised(List rawKeys) {
        List<String> keys = []
        for (def raw : rawKeys) {
            String key = ((raw ?: '') as String).trim().toLowerCase()
            if (!(key ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)) {
                script.error "[ONEVIEW] Pipeline key ${hint(key)} is not a key of OneView: copy the key of the service from its product page in OneView"
            }
            keys << key
        }
        return keys
    }

    List<Map> read(List rawKeys) {
        List<String> keys = normalised(rawKeys)
        address = settings.address()
        List<Map> documents = []
        if (script.env.NODE_NAME && script.isUnix()) {
            documents = query(keys)
        } else {
            script.timeout(time: 5, unit: 'MINUTES') {
                script.node(agent()) {
                    documents = query(keys)
                }
            }
        }
        return documents
    }

    List<Map> query(List<String> keys) {
        if (!script.isUnix()) {
            script.error "[ONEVIEW] Agent ${script.env.NODE_NAME} cannot read OneView because it runs Windows: set ONEVIEW_AGENT to a Linux or macOS label"
        }
        String dir = "${script.pwd(tmp: true)}/oneview-${script.env.BUILD_NUMBER}"
        List<Map> answers = []
        address = settings.address()
        script.withEnv(["ONEVIEW_DIR=${dir}", "ONEVIEW_URL=${address}", "ONEVIEW_KEYS=${keys.join(' ')}"]) {
            try {
                script.sh(label: 'Read the configuration from OneView', script: queryScript())
                for (int i = 0; i < keys.size(); i++) {
                    List status = script.readFile("${dir}/status-${i}").trim().tokenize(' ')
                    answers << [status: status ? status[0] : '000', sha256: status.size() > 1 ? status[1] : '',
                                body  : script.readFile("${dir}/body-${i}"), error: script.readFile("${dir}/error-${i}")]
                }
            } finally {
                script.dir(dir) { script.deleteDir() }
            }
        }
        List<Map> documents = []
        for (int i = 0; i < keys.size(); i++) {
            documents << document(keys[i], answers[i])
        }
        return documents
    }

    private Map document(String key, Map answer) {
        String keyHint = hint(key)
        String status = answer.status as String
        String body = (answer.body ?: '') as String
        if (status == '200') {
            def config = null
            try {
                config = RestClient.withoutJsonNulls(script.readJSON(text: body))
            } catch (Exception ignored) {
                fail("the answer for key ${keyHint} is not JSON (${body.length()} characters)")
            }
            if (!(config instanceof Map) || !config) fail("the answer for key ${keyHint} holds no configuration")
            Map document = config as Map
            String renderedAt = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
            String services = ((document.projects ?: [:]) as Map).keySet().join(', ')
            script.echo "[ONEVIEW] key ${keyHint}: ${services} of ${(document.pipeline as Map)?.product}, rendered ${renderedAt}, sha256 ${answer.sha256}"
            return [hint: keyHint, renderedAt: renderedAt, sha256: answer.sha256, config: document]
        }
        String detail = problemDetail(body).replace(key, keyHint)
        if (status == '403') {
            script.error "[ONEVIEW] Key ${keyHint} was invalidated in OneView (${detail ?: 'no reason given'}); regenerate it in OneView and put the new key in the Jenkinsfile"
        }
        if (status == '404' && detail.startsWith('Unknown ') && detail.endsWith(' pipeline key')) {
            script.error "[ONEVIEW] Key ${keyHint} was not issued by OneView at ${address}"
        }
        if (status == '000') {
            String cause = 'no answer'
            for (String line : ((answer.error ?: '') as String).replace(key, keyHint).tokenize('\n')) {
                if (line.startsWith('curl: (') || cause == 'no answer') cause = line.trim()
            }
            fail("agent ${script.env.NODE_NAME} cannot reach it (${cause})")
        }
        fail("it answered HTTP ${status}${detail ? ' (' + detail + ')' : ''}")
    }

    private String problemDetail(String body) {
        try {
            def problem = script.readJSON(text: body ?: '{}')
            return problem instanceof Map && problem.detail instanceof String ? (problem.detail as String).take(300) : ''
        } catch (Exception ignored) {
            return ''
        }
    }

    private void fail(String message) {
        script.currentBuild.description = 'OneView unavailable'
        script.error "[ONEVIEW] The configuration could not be read from OneView at ${address}: ${message}; ask the DevSecOps team"
    }

    private String queryScript() {
        return '''#!/bin/sh
set +x
umask 077
mkdir -p "$ONEVIEW_DIR" && cd "$ONEVIEW_DIR" || exit 1
i=0
for key in $ONEVIEW_KEYS; do
  : > "body-$i"
  status=$(curl -sS --connect-timeout 10 --max-time 60 --retry 2 --retry-delay 5 \\
    -H 'Accept: application/json' -o "body-$i" -w '%{http_code}' "$ONEVIEW_URL/api/dso/config/$key?format=json" 2>"error-$i") || status=000
  if command -v sha256sum >/dev/null 2>&1; then sum=$(sha256sum "body-$i"); else sum=$(shasum -a 256 "body-$i"); fi
  printf '%s %.16s\\n' "${status:-000}" "$sum" > "status-$i"
  i=$((i + 1))
done
'''
    }
}
