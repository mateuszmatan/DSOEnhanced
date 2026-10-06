package com.bbh.config

import com.bbh.utils.RestClient

class PortalConfigReader implements Serializable {

    private final def script

    PortalConfigReader(def script) {
        this.script = script
    }

    String portalUrl() { ((script.env.DSO_PORTAL_URL ?: '') as String).trim().replaceAll('/+$', '') }
    String agent()     { (script.env.DSO_PORTAL_AGENT ?: 'linux-agent') as String }

    String hint(String key) {
        return key.length() < 12 ? '...' : key.take(8) + '...' + key.substring(key.length() - 4)
    }

    List<Map> read(List rawKeys) {
        List<String> keys = []
        for (def raw : rawKeys) {
            String key = ((raw ?: '') as String).trim().toLowerCase()
            if (!(key ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)) {
                script.error "[PORTAL] Pipeline key ${hint(key)} is not a key of the DevSecOps portal: copy the key of the service from its product page in the portal"
            }
            keys << key
        }
        String url = portalUrl()
        if (!url) {
            script.error '[PORTAL] DSO_PORTAL_URL is not set: a Jenkins administrator sets it once to the address of the DevSecOps portal, for example https://dso-portal.apps.bbh.com, in Manage Jenkins > System > Global properties > Environment variables'
        }
        if (!(url ==~ /https?:\/\/[A-Za-z0-9.-]+(:[0-9]+)?(\/[A-Za-z0-9._~\/-]*)?/)) {
            script.error '[PORTAL] DSO_PORTAL_URL is not the address of the DevSecOps portal: a Jenkins administrator writes it as https://<host>[:<port>][/<path>], without a user, password, query or spaces'
        }
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
            script.error "[PORTAL] Agent ${script.env.NODE_NAME} cannot read the DevSecOps portal because it runs Windows: set DSO_PORTAL_AGENT to a Linux or macOS label"
        }
        String dir = "${script.pwd(tmp: true)}/dso-portal-${script.env.BUILD_NUMBER}"
        List<Map> answers = []
        script.withEnv(["DSO_PORTAL_DIR=${dir}", "DSO_PORTAL_URL=${portalUrl()}", "DSO_PORTAL_KEYS=${keys.join(' ')}"]) {
            try {
                script.sh(label: 'Read the configuration from the DevSecOps portal', script: queryScript())
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
            script.echo "[PORTAL] key ${keyHint}: ${services} of ${(document.pipeline as Map)?.product}, rendered ${renderedAt}, sha256 ${answer.sha256}"
            return [hint: keyHint, renderedAt: renderedAt, sha256: answer.sha256, config: document]
        }
        String detail = problemDetail(body).replace(key, keyHint)
        if (status == '403') {
            script.error "[PORTAL] Key ${keyHint} was invalidated in the DevSecOps portal (${detail ?: 'no reason given'}); regenerate it in the portal and put the new key in the Jenkinsfile"
        }
        if (status == '404' && detail == 'Unknown DevSecOps pipeline key') {
            script.error "[PORTAL] Key ${keyHint} was not issued by the DevSecOps portal at ${portalUrl()}"
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
        script.currentBuild.description = 'DevSecOps portal unavailable'
        script.error "[PORTAL] The configuration could not be read from the DevSecOps portal at ${portalUrl()}: ${message}; ask the DevSecOps team"
    }

    private String queryScript() {
        return '''#!/bin/sh
set +x
umask 077
mkdir -p "$DSO_PORTAL_DIR" && cd "$DSO_PORTAL_DIR" || exit 1
i=0
for key in $DSO_PORTAL_KEYS; do
  : > "body-$i"
  status=$(curl -sS --connect-timeout 10 --max-time 60 --retry 2 --retry-delay 5 \\
    -H 'Accept: application/json' -o "body-$i" -w '%{http_code}' "$DSO_PORTAL_URL/api/dso/config/$key?format=json" 2>"error-$i") || status=000
  if command -v sha256sum >/dev/null 2>&1; then sum=$(sha256sum "body-$i"); else sum=$(shasum -a 256 "body-$i"); fi
  printf '%s %.16s\\n' "${status:-000}" "$sum" > "status-$i"
  i=$((i + 1))
done
'''
    }
}
