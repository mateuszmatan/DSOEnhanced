package com.bbh.config

import com.bbh.utils.RestClient

class PortalConfigReader implements Serializable {

    private final def script

    PortalConfigReader(def script) {
        this.script = script
    }

    String databaseUrl()   { ((script.env.DSO_PORTAL_DB_URL ?: '') as String).trim() }
    String credentialsId() { (script.env.DSO_PORTAL_DB_CREDENTIALS_ID ?: 'dso-portal-db-reader') as String }
    String schema()        { (script.env.DSO_PORTAL_DB_SCHEMA ?: 'DSO_PORTAL') as String }
    String agent()         { (script.env.DSO_PORTAL_AGENT ?: 'linux-agent') as String }
    String driverUrl()     { (script.env.DSO_PORTAL_JDBC_DRIVER_URL ?: 'https://tools.bbh.com/nexus/repository/maven-central/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar') as String }
    String driverSha256()  { ((script.env.DSO_PORTAL_JDBC_DRIVER_SHA256 ?: '764cc3f88454d1be117e155d01ed617467b984c9c68f13090dd6083799d2bb8c') as String).toLowerCase() }

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
        String url = databaseUrl()
        if (!url) {
            script.error '[PORTAL] DSO_PORTAL_DB_URL is not set: a Jenkins administrator sets it once to the JDBC URL of the DevSecOps portal database in Manage Jenkins > System > Global properties > Environment variables'
        }
        if (url ==~ /(?is)jdbc:oracle:\w+:[^@(]+@.*/) {
            script.error "[PORTAL] DSO_PORTAL_DB_URL must not contain a user or password: the reader account comes from the Jenkins credentials ${credentialsId()}"
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
            script.error "[PORTAL] Agent ${script.env.NODE_NAME} cannot read the DevSecOps portal database because it runs Windows: set DSO_PORTAL_AGENT to a Linux or macOS label"
        }
        String dir = "${script.pwd(tmp: true)}/dso-portal-${script.env.BUILD_NUMBER}"
        String output = ''
        int status = 0
        script.withEnv(["DSO_PORTAL_DIR=${dir}", "DSO_PORTAL_OUTPUT=${dir}/result.json", "DSO_PORTAL_DB_URL=${databaseUrl()}",
                        "DSO_PORTAL_DB_SCHEMA=${schema()}", "DSO_PORTAL_KEYS=${keys.join(',')}",
                        "DSO_PORTAL_JDBC_DRIVER_URL=${driverUrl()}", "DSO_PORTAL_JDBC_DRIVER_SHA256=${driverSha256()}"]) {
            try {
                script.writeFile(file: "${dir}/PortalConfigQuery.java", text: script.libraryResource('com/bbh/config/PortalConfigQuery.java'))
                script.withCredentials([script.usernamePassword(credentialsId: credentialsId(),
                        usernameVariable: 'DSO_PORTAL_DB_USER', passwordVariable: 'DSO_PORTAL_DB_PASSWORD')]) {
                    status = script.sh(returnStatus: true, label: 'Read the configuration from the DevSecOps portal', script: queryScript()) as int
                }
                if (status == 0) output = script.readFile("${dir}/result.json")
            } finally {
                script.dir(dir) { script.deleteDir() }
            }
        }
        if (status != 0) fail('other', "the query program ended with exit code ${status}")
        Map answer = [:]
        try {
            answer = RestClient.withoutJsonNulls(script.readJSON(text: output)) as Map
        } catch (Exception ignored) {
            fail('other', "the answer of ${output.length()} characters is not JSON")
        }
        if (answer.error) fail(answer.error as String, (answer.message ?: '') as String)
        if (answer.warning) {
            script.echo "[PORTAL] WARNING: the password of ${credentialsId()} expires soon; rotate it in the database and in the Jenkins credentials together (ask the DevSecOps team)"
        }
        List results = (answer.results instanceof List ? answer.results : []) as List
        if (results.size() != keys.size()) fail('other', "the answer holds ${results.size()} results for ${keys.size()} keys")
        return checked(keys, results)
    }

    private List<Map> checked(List<String> keys, List results) {
        List<Map> documents = []
        for (int i = 0; i < keys.size(); i++) {
            String keyHint = hint(keys[i])
            Map entry = (results[i] ?: [:]) as Map
            if (!entry) script.error "[PORTAL] Key ${keyHint} was not issued by the DevSecOps portal"
            if (entry.keyStatus != 'ACTIVE') {
                script.error "[PORTAL] Key ${keyHint} was invalidated in the DevSecOps portal: ${entry.revokeReason ?: entry.keyStatus}; regenerate it in the portal and put the new key in the Jenkinsfile"
            }
            if (!(entry.config instanceof Map) || !entry.config) {
                script.error "[PORTAL] Key ${keyHint} has no published configuration yet; save the service in the portal"
            }
            Map config = entry.config as Map
            String services = ((config.projects ?: [:]) as Map).keySet().join(', ')
            script.echo "[PORTAL] key ${keyHint}: ${services} of ${(config.pipeline as Map)?.product}, rendered ${entry.renderedAt}, sha256 ${entry.sha256}"
            documents << [hint: keyHint, renderedAt: entry.renderedAt, sha256: entry.sha256, config: config]
        }
        return documents
    }

    private void fail(String type, String message) {
        Map texts = [
                config : 'The DevSecOps portal database read is not set up correctly',
                driver : "The Oracle JDBC driver could not be loaded from DSO_PORTAL_JDBC_DRIVER_URL ${driverUrl()} (DSO_PORTAL_JDBC_DRIVER_SHA256 must match it)",
                network: "Agent ${script.env.NODE_NAME} cannot reach the DevSecOps portal database",
                auth   : 'The DevSecOps portal database refused the user name or password',
                locked : 'The DevSecOps portal reader account is locked',
                expired: 'The password of the DevSecOps portal reader account has expired',
                grant  : "The DevSecOps portal reader account may not run ${schema()}.DSO_LIBRARY_CONFIG"
        ]
        if (texts.containsKey(type)) script.currentBuild.description = 'DevSecOps portal database unavailable'
        script.error "[PORTAL] ${texts[type] ?: 'The DevSecOps portal database read failed'}: ${message} " +
                "(DSO_PORTAL_DB_URL ${target()}, DSO_PORTAL_DB_CREDENTIALS_ID ${credentialsId()}); ask the DevSecOps team"
    }

    private String target() {
        String url = databaseUrl()
        String rest = url.contains('@') ? url.substring(url.indexOf('@') + 1) : url
        return rest.contains('?') ? rest.substring(0, rest.indexOf('?')) : rest
    }

    private String queryScript() {
        return '''#!/bin/sh
set +x
umask 077
chmod 700 "$DSO_PORTAL_DIR"
fail() {
  printf '{"error":"%s","message":"%s"}' "$1" "$2" > "$DSO_PORTAL_OUTPUT"
  exit 0
}
JAR="$DSO_PORTAL_DIR/ojdbc.jar"
curl -fsS --connect-timeout 20 --max-time 300 -o "$JAR.part" "$DSO_PORTAL_JDBC_DRIVER_URL" || fail driver "driver download failed"
if command -v sha256sum >/dev/null 2>&1; then SUM=$(sha256sum "$JAR.part"); else SUM=$(shasum -a 256 "$JAR.part"); fi
[ "${SUM%% *}" = "$DSO_PORTAL_JDBC_DRIVER_SHA256" ] || fail driver "driver checksum mismatch"
mv "$JAR.part" "$JAR"
JAVA="${DSO_PORTAL_JAVA:-$(command -v java)}"
[ -n "$JAVA" ] || JAVA="$JAVA_HOME/bin/java"
env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u CLASSPATH "$JAVA" --list-modules 2>/dev/null | grep -q '^jdk.compiler' || fail config "needs a JDK 11+ with jdk.compiler: set DSO_PORTAL_JAVA"
exec env -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS -u CLASSPATH "$JAVA" -Duser.timezone=UTC -cp "$JAR" "$DSO_PORTAL_DIR/PortalConfigQuery.java"
'''
    }
}
