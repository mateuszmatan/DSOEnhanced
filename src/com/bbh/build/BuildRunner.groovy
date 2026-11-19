package com.bbh.build

import com.bbh.utils.BuildUtils

class BuildRunner implements Serializable {

    private final def    steps
    private final Map    cfg
    private final String tool
    private final String mvnPath
    private final Map    repositories

    BuildRunner(def steps, Map cfg, String tool, Map repositories = [:]) {
        this.steps        = steps
        this.tool         = tool ?: ''
        this.cfg          = ((cfg?.get(this.tool)) ?: [:]) as Map
        this.mvnPath      = (this.cfg.mvnPath ?: '') as String
        this.repositories = repositories ?: [:]
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
            if (!env) return execute(cmd, label, returnStdout, executable, flags)
            return steps.withEnv(env) {
                execute(cmd, label, returnStdout, executable, flags)
            }
        }
        if (cfg.dir) {
            return steps.dir(cfg.dir as String) {
                body()
            }
        }
        return body()
    }

    private def execute(String cmd, String label, boolean returnStdout, String executable, List flags) {
        String url = ((repositories[tool] ?: '') as String).trim()
        if (!url || returnStdout) return BuildUtils.execSh(steps, cmd, label, returnStdout)
        String folder = "${steps.pwd(tmp: true)}/dso-plugin-repositories".toString()
        String marker = "${folder}/${tool}-${BuildUtils.folderKey(steps.pwd() as String)}.used".toString()
        if (steps.fileExists(marker)) {
            steps.echo "[BUILD] ${label}: with the plugin repository ${url} of the BBH Nexus, which an earlier step of this build needed"
            return runWithPlugins(cmd, label, folder, url)
        }
        try {
            return BuildUtils.execSh(steps, cmd, label, false)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            List<String> missing = missingPlugins(executable, flags)
            if (!missing) throw e
            String name = tool == 'gradle' ? 'Gradle' : 'Maven'
            if (tool == 'maven' && BuildUtils.ownMavenSettings(flags)) {
                steps.echo "[WARN] [BUILD] ${name} did not find the plugin(s) ${missing.join(', ')} in the repositories of the build, which sets its own settings file - add the plugin repository ${url} of the BBH Nexus to it"
                throw e
            }
            steps.echo "[WARN] [BUILD] ${name} did not find the plugin(s) ${missing.join(', ')} in the repositories of the build - trying again with the plugin repository ${url} of the BBH Nexus"
            try {
                runWithPlugins(cmd, "${label} (BBH Nexus plugins)".toString(), folder, url)
            } catch (Exception again) {
                if (again.getClass().getName().endsWith('FlowInterruptedException')) throw again
                steps.echo "[BUILD] ${name} failed with the plugin repository ${url} of the BBH Nexus too - the build fails"
                throw again
            }
            steps.writeFile(file: marker, text: "${url}\n")
            String where = tool == 'gradle' ? 'pluginManagement { repositories { ... } } of settings.gradle' : '<pluginRepositories> of pom.xml'
            steps.echo "[WARN] [BUILD] The plugin(s) ${missing.join(', ')} came from ${url} of the BBH Nexus: add the repository to ${where} so that the first attempt does not fail"
            return null
        }
    }

    private List<String> missingPlugins(String executable, List flags) {
        List<String> properties = flags.findAll { String f -> f.startsWith('-D') || f.startsWith('-P') } as List<String>
        List<String> words = tool == 'gradle'
                ? [executable, '--console=plain', 'help'] + properties
                : [executable, '-B'] + properties + ['org.apache.maven.plugins:maven-dependency-plugin:3.8.1:resolve-plugins']
        String check = words.collect { BuildUtils.shellQuoteIfNeeded(it as String) }.join(' ')
        String output = (steps.sh(script: "${check} 2>&1 || true", returnStdout: true, label: "${tool == 'gradle' ? 'Gradle' : 'Maven'}: are the plugins there") ?: '') as String
        return BuildUtils.missingPlugins(tool, output)
    }

    private def runWithPlugins(String cmd, String label, String folder, String url) {
        String arguments = pluginArguments(folder, url)
        try {
            return BuildUtils.execSh(steps, "${cmd} ${arguments}".toString(), label, false)
        } finally {
            if (tool == 'maven') {
                steps.sh(script: "rm -f -- '${BuildUtils.escapeForSingleQuotes(folder)}/bbh-nexus-plugins-settings.xml'", label: 'Maven: remove the settings copy with the BBH Nexus plugins')
            }
        }
    }

    private String pluginArguments(String folder, String url) {
        if (tool == 'gradle') {
            String file = "${folder}/bbh-nexus-plugins.gradle".toString()
            if (!steps.fileExists(file)) steps.writeFile(file: file, text: BuildUtils.gradlePluginInitScript(url))
            return "--init-script ${BuildUtils.shellQuoteIfNeeded(file)}".toString()
        }
        String file = "${folder}/bbh-nexus-plugins-settings.xml".toString()
        String own = (steps.sh(script: 'cat "$HOME/.m2/settings.xml" 2>/dev/null || true', returnStdout: true, label: 'Maven: read the user settings') ?: '') as String
        steps.writeFile(file: file, text: BuildUtils.mavenPluginSettings(own, url))
        return "-s ${BuildUtils.shellQuoteIfNeeded(file)}".toString()
    }

    private String defaultMaven() {
        return steps.fileExists('mvnw') ? './mvnw' : 'mvn'
    }
}
