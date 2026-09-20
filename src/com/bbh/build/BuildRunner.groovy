package com.bbh.build

import com.bbh.utils.BuildUtils

class BuildRunner implements Serializable {

    private final def    steps
    private final Map    cfg
    private final String tool
    private final String mvnPath

    BuildRunner(def steps, Map cfg, String tool) {
        this.steps   = steps
        this.tool    = tool ?: ''
        this.cfg     = ((cfg?.get(this.tool)) ?: [:]) as Map
        this.mvnPath = (this.cfg.mvnPath ?: '') as String
        if (!(this.tool in ['gradle', 'maven'])) {
            steps.error "Build Tool is not specified in configuration"
        }
    }

    boolean ifExist() {
        if (tool == 'gradle') {
            return steps.sh(script: '[ -f "./gradlew" ]', returnStatus: true) == 0
        }
        String check = mvnPath ? "[ -x '${mvnPath}/bin/mvn' ] && ${mvnPath}/bin/mvn -v" : "${defaultMaven()} -v"
        boolean installed = steps.sh(script: check, returnStatus: true) == 0
        if (installed) {
            steps.echo "Maven is installed on this agent"
            steps.sh(mvnPath ? "${mvnPath}/bin/mvn -version" : "${defaultMaven()} -version")
        } else {
            steps.echo "Maven is NOT installed on this agent"
        }
        return installed
    }

    def run() {
        boolean gradle = tool == 'gradle'
        def commands = BuildUtils.normalizeTokens(gradle ? cfg.tasks : cfg.goals)
        if (!commands) {
            steps.error "${gradle ? 'Gradle Runner: tasks' : 'MavenRunner: goals'} must be provided (e.g. ['clean','build'] or 'clean build')"
        }
        List flags = BuildUtils.normalizeTokens(cfg.flags)
        String executable = gradle ? './gradlew' : (mvnPath ? 'mvn' : defaultMaven())
        String cmd = ([executable] + commands + flags).collect { BuildUtils.shellQuoteIfNeeded(it as String) }.join(' ')
        String label = (cfg.label ?: "${gradle ? 'Gradle' : 'Maven'}: ${commands.join(' ')}") as String
        boolean returnStdout = (cfg.returnStdout ?: false) as boolean

        steps.echo "Execute ${gradle ? 'Gradle' : 'Maven'} Command: ${cmd}"
        if (mvnPath) steps.echo "M2_HOME=${mvnPath}"
        if (gradle) steps.sh(script: 'chmod +x gradlew', returnStdout: true)

        Closure body = {
            List<String> env = mvnPath && !gradle ? ["M2_HOME=${mvnPath}".toString(), "PATH+MAVEN=${mvnPath}/bin".toString()] : []
            if (cfg.env instanceof Map && !cfg.env.isEmpty()) {
                env.addAll(cfg.env.collect { k, v -> "${k}=${v}".toString() } as List<String>)
            }
            if (!env) return BuildUtils.execSh(steps, cmd, label, returnStdout)
            return steps.withEnv(env) {
                BuildUtils.execSh(steps, cmd, label, returnStdout)
            }
        }
        if (cfg.dir) {
            return steps.dir(cfg.dir as String) {
                body()
            }
        }
        return body()
    }

    private String defaultMaven() {
        return steps.fileExists('mvnw') ? './mvnw' : 'mvn'
    }
}
