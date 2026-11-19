package com.bbh.deploy

import com.bbh.build.BuildRunner
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils
import com.bbh.utils.FlutterUtils

class NexusDeliveryService implements Serializable {
    private final def           script
    private final PipelineState state
    private final OsHelper      os

    NexusDeliveryService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    void pushToNexus(String projectName) {
        String tool = ((state.cfg.buildTool ?: '') as String).trim()
        if (BuildUtils.isDotnetTool(tool)) {
            deliverDotnetNuget()
            return
        }
        if (tool == 'maven') {
            pushMaven(projectName)
            return
        }
        if (tool == 'gradle') {
            pushGradle(projectName)
            return
        }
        if (tool == 'flutter') {
            String buildNumber = script.env.BUILD_NUMBER as String
            FlutterUtils.deliverToNexusAndroid(buildNumber, script, state.cfg, projectName, state.platform)
            FlutterUtils.deliverToNexusIOS(buildNumber, script, state.cfg, projectName, state.platform)
            return
        }
        notRequired("[NEXUS] ${projectName}: the build tool ${tool ?: 'of the service'} has no Nexus snapshot delivery - the artifact is not published")
    }

    boolean releaseAvailable() {
        return BuildUtils.isDotnetTool(((state.cfg.buildTool ?: '') as String).trim()) && nugetSource()
    }

    void publishRelease(String projectName) {
        String tool = ((state.cfg.buildTool ?: '') as String).trim()
        if (releaseAvailable()) {
            deliverDotnetNuget()
            return
        }
        notRequired("[NEXUS] ${projectName}: the release delivery of the safe artifact is not implemented yet for the build tool ${tool ?: 'of the service'} - the snapshot published by the security pipeline stays the delivered artifact")
    }

    void pullFromNexus(String projectName) {
        if ((state.cfg.deployTarget ?: 'vm') == 'openshift') {
            notRequired("[NEXUS] ${projectName}: an OpenShift service deploys its image - no artifact is downloaded from Nexus")
            return
        }
        if (!BuildUtils.nexusDeliveryEnabled(state.cfg)) {
            notRequired("[NEXUS] ${projectName}: Nexus delivery is disabled by ${BuildUtils.nexusDeliverySetting(state.cfg)} - no artifact is downloaded from Nexus")
            return
        }
        Map pullCfg = mapOf(deliveryCfg().pull)
        if (!pullCfg) {
            notRequired("[NEXUS] ${projectName}: delivery.pull is not set - no artifact is downloaded from Nexus")
            return
        }
        String tool = ((state.cfg.buildTool ?: '') as String).trim()
        if (tool == 'maven') {
            pullMaven(projectName, pullCfg)
            return
        }
        if (tool == 'gradle') {
            pullGradle(projectName, pullCfg)
            return
        }
        notRequired("[NEXUS] ${projectName}: the build tool ${tool ?: 'of the service'} has no Nexus download - no artifact is downloaded from Nexus")
    }

    String deliveredBuildTag() {
        if (state.buildTag) return state.buildTag
        if (script.fileExists(buildTagFile())) {
            String tag = ((script.readFile(file: buildTagFile()) ?: '') as String).trim()
            if (tag) state.buildTag = tag
        }
        return state.buildTag
    }

    private String runBuildTag() {
        if (!state.buildTag) {
            String stamp = new Date().format('yyyyMMdd-HHmmss', TimeZone.getTimeZone('UTC'))
            state.buildTag = "${script.env.BUILD_NUMBER}-${stamp}".toString()
        }
        return state.buildTag
    }

    private void writeBuildTag(String projectName, String buildTag) {
        script.writeFile(file: buildTagFile(), text: buildTag)
        state.deliveredBuildTags[projectName] = buildTag
    }

    private void pushMaven(String projectName) {
        Map mavenCfg = mapOf(mapOf(deliveryCfg().push).maven)
        if (!mavenCfg) {
            pushMavenFromBuildPath(projectName)
            return
        }
        if (!mavenCfg.goals) {
            script.error "[NEXUS] delivery.push.maven.goals must be set for ${projectName} in ${state.settingsPlace()} to publish the Maven artifacts"
        }
        List entries = listOf(mavenCfg.artifacts)
        if (!entries) {
            script.error "[NEXUS] delivery.push.maven.artifacts must list the artifact paths of ${projectName} in ${state.settingsPlace()}"
        }
        List artifacts = matchArtifacts(entries, 'delivery.push.maven.artifacts', false)
        String version = "${runBuildTag()}-SNAPSHOT".toString()
        Map first = artifacts[0] as Map
        List flags = [mavenProperty('version', version), mavenProperty('file', first.path), mavenProperty('packaging', first.extension),
                      mavenProperty('classifier', first.classifier)]
        if (artifacts.size() > 1) {
            List rest = []
            for (int i = 1; i < artifacts.size(); i++) rest << artifacts[i]
            flags << mavenProperty('files', joined(rest, 'path'))
            flags << mavenProperty('types', joined(rest, 'extension'))
            flags << mavenProperty('classifiers', joined(rest, 'classifier'))
        }
        script.echo "[NEXUS] ${projectName}: uploading ${artifacts.size()} artifact(s) as ${version}"
        runMaven(mavenCfg, flags)
        writeBuildTag(projectName, state.buildTag)
    }

    private void pushMavenFromBuildPath(String projectName) {
        String pattern = (state.cfg.build?.buildPath ?: '') as String
        if (!pattern) {
            script.error "[NEXUS] build.buildPath must point at the artifact to publish, for example target/*.jar"
        }
        String file = pattern
        if (pattern.contains('*')) {
            def files = script.findFiles(glob: pattern)
            if (files.length == 0) {
                script.error "[NEXUS] No artifact matches build.buildPath '${pattern}'"
            }
            file = files[0].path as String
            script.echo "Found a file: ${file}."
        }
        Map mavenCfg = mapOf(deliveryCfg().maven)
        if (!mavenCfg.goals) {
            script.error "[NEXUS] delivery.maven.goals (or delivery.push.maven) must be set for the service in ${state.settingsPlace()} to publish the Maven artifact"
        }
        String extension = file.substring(file.lastIndexOf('.') + 1)
        String version = "${runBuildTag()}-SNAPSHOT".toString()
        runMaven(mavenCfg, [mavenProperty('version', version), mavenProperty('packaging', extension), mavenProperty('file', file)])
        writeBuildTag(projectName, state.buildTag)
    }

    private void pushGradle(String projectName) {
        Map cfg = mapOf(mapOf(deliveryCfg().push).gradle)
        List entries = listOf(cfg.artifacts)
        if (!entries) {
            notRequired("[NEXUS] ${projectName}: delivery.push.gradle.artifacts is not set in ${state.settingsPlace()} - the Gradle artifact is not published")
            return
        }
        Map coordinates = gradleCoordinates(cfg, [:], 'delivery.push.gradle')
        List artifacts = matchArtifacts(entries, 'delivery.push.gradle.artifacts', true)
        String version = "${runBuildTag()}-SNAPSHOT".toString()
        runGradle(cfg, coordinates + [version: version, artifacts: artifacts], 'push')
        writeBuildTag(projectName, state.buildTag)
        script.echo "[NEXUS] ${projectName}: uploaded ${artifacts.size()} artifact(s) as ${version}"
    }

    private void pullMaven(String projectName, Map pullCfg) {
        Map mavenCfg = mapOf(pullCfg.maven)
        String groupId = ((mavenCfg.group ?: pullCfg.group ?: '') as String).trim()
        String artifactId = ((mavenCfg.artifact ?: pullCfg.artifact ?: '') as String).trim()
        List entries = listOf(mavenCfg.artifacts)
        if (!groupId || !artifactId || !mavenCfg.goals || !entries) {
            script.error "[NEXUS] ${projectName}: set delivery.pull.group, delivery.pull.artifact, delivery.pull.maven.goals and delivery.pull.maven.artifacts in ${state.settingsPlace()} to download the Maven artifacts"
        }
        String version = snapshotVersion(projectName)
        List artifacts = describeAll(entries, 'delivery.pull.maven.artifacts')
        String localRepository = mavenLocalRepository(mavenCfg)
        String output = (pullCfg.outputDirectory ?: 'downloaded-artifacts') as String
        script.echo "[NEXUS] ${projectName}: local Maven repository ${localRepository}"
        script.sh "mkdir -p -- '${BuildUtils.escapeForSingleQuotes(output)}'"
        for (int i = 0; i < artifacts.size(); i++) {
            Map artifact = artifacts[i] as Map
            String coordinates = "${groupId}:${artifactId}:${version}:${artifact.extension}:${artifact.classifier}".toString()
            script.echo "[NEXUS] ${projectName}: downloading ${coordinates}"
            runMaven(mavenCfg, [mavenProperty('artifact', coordinates), mavenProperty('maven.repo.local', localRepository)])
            String cached = "${localRepository}/${groupId.replace('.', '/')}/${artifactId}/${version}/${artifactId}-${version}-${artifact.classifier}.${artifact.extension}".toString()
            if (!script.fileExists(cached)) {
                script.error "[NEXUS] ${projectName}: the downloaded artifact is not in the local Maven repository: ${cached}"
            }
            script.sh "cp -- '${BuildUtils.escapeForSingleQuotes(cached)}' '${BuildUtils.escapeForSingleQuotes(output + '/' + artifact.fileName)}'"
        }
        script.echo "[NEXUS] ${projectName}: downloaded ${artifacts.size()} artifact(s) of ${version} to ${output}"
    }

    private void pullGradle(String projectName, Map pullCfg) {
        Map cfg = mapOf(pullCfg.gradle)
        List entries = listOf(cfg.artifacts)
        if (!entries) {
            script.error "[NEXUS] ${projectName}: set delivery.pull.gradle.artifacts in ${state.settingsPlace()} to download the Gradle artifacts"
        }
        Map coordinates = gradleCoordinates(cfg, pullCfg, 'delivery.pull.gradle')
        String version = snapshotVersion(projectName)
        List artifacts = describeAll(entries, 'delivery.pull.gradle.artifacts')
        String output = (pullCfg.outputDirectory ?: 'downloaded-artifacts') as String
        String absoluteOutput = output.startsWith('/') ? output : "${script.pwd()}/${output}".toString()
        runGradle(cfg, coordinates + [version: version, artifacts: artifacts, outputDirectory: absoluteOutput], 'pull')
        script.echo "[NEXUS] ${projectName}: downloaded ${artifacts.size()} artifact(s) of ${version} to ${output}"
    }

    private String snapshotVersion(String projectName) {
        String tag = deliveredBuildTag()
        if (!tag) {
            script.error "[NEXUS] ${projectName}: there is no ${buildTagFile()} - the security pipeline writes it when it publishes the snapshot, so run it with Nexus delivery on first"
        }
        state.deliveredBuildTags[projectName] = tag
        String version = tag.endsWith('-SNAPSHOT') ? tag : "${tag}-SNAPSHOT".toString()
        if (!(version ==~ /[A-Za-z0-9_.-]+/)) {
            script.error "[NEXUS] ${projectName}: ${buildTagFile()} holds an invalid tag: a build tag has letters, digits and . _ - only"
        }
        return version
    }

    private List matchArtifacts(List entries, String setting, boolean absolute) {
        List artifacts = []
        List paths = []
        List coordinates = []
        for (int i = 0; i < entries.size(); i++) {
            Map entry = artifactEntry(entries[i])
            String pattern = ((entry.path ?: '') as String).trim()
            if (!pattern) {
                script.error "[NEXUS] Every item of ${setting} needs a path"
            }
            def matches = script.findFiles(glob: pattern)
            if (matches.length == 0) {
                script.error "[NEXUS] No artifact matches '${pattern}' of ${setting}"
            }
            if (entry.classifier && matches.length > 1) {
                script.error "[NEXUS] '${pattern}' of ${setting} matches ${matches.length} files: an explicit classifier needs one file, so remove it or list the files one by one"
            }
            for (int m = 0; m < matches.length; m++) {
                def match = matches[m]
                String path = match.path as String
                if (match.directory) {
                    script.error "[NEXUS] The artifact ${path} of ${setting} is a directory"
                }
                if (paths.contains(path)) {
                    script.error "[NEXUS] The artifact ${path} is listed twice in ${setting}"
                }
                paths << path
                Map described = describeArtifact(match.name as String, entry.classifier)
                if (described.error) {
                    script.error "[NEXUS] ${described.error} (${setting})"
                }
                if (path.contains(',')) {
                    script.error "[NEXUS] The artifact path ${path} of ${setting} has a comma, which Maven cannot upload"
                }
                String coordinate = "${described.classifier}:${described.extension}".toString()
                if (coordinates.contains(coordinate)) {
                    script.error "[NEXUS] Two artifacts of ${setting} share the classifier and type ${coordinate}: give them distinct classifiers"
                }
                coordinates << coordinate
                artifacts << [fileName: described.fileName, extension: described.extension, classifier: described.classifier,
                              path: absolute ? "${script.pwd()}/${path}".toString() : path]
            }
        }
        return artifacts
    }

    private List describeAll(List entries, String setting) {
        List artifacts = []
        List names = []
        for (int i = 0; i < entries.size(); i++) {
            Map entry = artifactEntry(entries[i])
            String path = ((entry.path ?: '') as String).trim()
            if (!path) {
                script.error "[NEXUS] Every item of ${setting} needs a path"
            }
            String name = BuildUtils.fileNameOf(path)
            if (names.contains(name)) {
                script.error "[NEXUS] Two artifacts of ${setting} would be downloaded as ${name}"
            }
            names << name
            Map described = describeArtifact(name, entry.classifier)
            if (described.error) {
                script.error "[NEXUS] ${described.error} (${setting})"
            }
            artifacts << [fileName: described.fileName, extension: described.extension, classifier: described.classifier]
        }
        return artifacts
    }

    private Map gradleCoordinates(Map cfg, Map fallback, String setting) {
        String group = ((cfg.group ?: fallback.group ?: '') as String).trim()
        String artifact = ((cfg.artifact ?: fallback.artifact ?: '') as String).trim()
        String url = ((cfg.repositoryUrl ?: fallback.repositoryUrl ?: '') as String).trim()
        if (!group || !artifact || !url) {
            script.error "[NEXUS] Set ${setting}.group, ${setting}.artifact and ${setting}.repositoryUrl in ${state.settingsPlace()}"
        }
        if (!(group ==~ /[A-Za-z0-9_.-]+/) || !(artifact ==~ /[A-Za-z0-9_.-]+/)) {
            script.error "[NEXUS] ${setting}.group and ${setting}.artifact have letters, digits and . _ - only"
        }
        if (!(url.startsWith('https://') || url.startsWith('http://'))) {
            script.error "[NEXUS] ${setting}.repositoryUrl must be an http or https address"
        }
        return [group: group, artifact: artifact, repositoryUrl: url, allowInsecureProtocol: cfg.allowInsecureProtocol == true]
    }

    private void runGradle(Map cfg, Map manifest, String action) {
        String task = action == 'push' ? 'publishNexusDeliveryPublicationToNexusRepository' : 'pullNexusArtifacts'
        List tasks = cfg.tasks ? BuildUtils.normalizeTokens(cfg.tasks) : [task]
        if (tasks != [task]) {
            script.error "[NEXUS] The Gradle ${action} of the Nexus delivery runs only the task ${task}"
        }
        List flags = BuildUtils.normalizeTokens(cfg.flags)
        String reserved = reservedGradleFlag(flags)
        if (reserved) {
            script.error "[NEXUS] The flag ${reserved} is set by the Nexus delivery and cannot be given in its Gradle flags"
        }
        String applicationDir = script.pwd()
        if (!script.fileExists('gradlew')) {
            script.error "[NEXUS] The Nexus delivery runs the Gradle wrapper of the service, and there is no gradlew in ${applicationDir}"
        }
        String tmp = script.pwd(tmp: true)
        String deliveryDir = (script.sh(script: "mkdir -p -- '${BuildUtils.escapeForSingleQuotes(tmp)}' && mktemp -d '${BuildUtils.escapeForSingleQuotes(tmp)}/nexus-gradle.XXXXXXXX'",
                returnStdout: true, label: "Nexus Gradle ${action}: project folder") as String).trim()
        if (!deliveryDir) {
            script.error "[NEXUS] The Nexus delivery could not create its Gradle project folder below ${tmp}"
        }
        try {
            script.writeFile(file: "${deliveryDir}/settings.gradle", text: "rootProject.name = 'nexus-delivery'\n")
            script.writeFile(file: "${deliveryDir}/build.gradle", text: script.libraryResource('com/bbh/devsecops/nexus-delivery.gradle'))
            script.writeJSON(file: "${deliveryDir}/manifest.json", json: manifest)
            Map gradleCfg = [:] + cfg
            gradleCfg.tasks = tasks
            gradleCfg.flags = flags + ['--no-daemon', '--no-configuration-cache', '--project-dir', deliveryDir, "-PnexusManifest=${deliveryDir}/manifest.json".toString()]
            gradleCfg.env = [:] + mapOf(cfg.env)
            gradleCfg.label = "Nexus Gradle ${action}".toString()
            gradleCfg.returnStdout = false
            gradleCfg.remove('dir')
            BuildRunner runner = new BuildRunner(script, [gradle: gradleCfg], 'gradle')
            if (!runner.ifExist()) {
                script.error "[NEXUS] The Gradle wrapper of ${applicationDir} cannot be run"
            }
            runner.run()
        } finally {
            script.sh(script: "rm -rf -- '${BuildUtils.escapeForSingleQuotes(deliveryDir)}'", label: "Nexus Gradle ${action}: remove the project folder")
        }
    }

    private String mavenLocalRepository(Map mavenCfg) {
        String tmp = script.pwd(tmp: true)
        String resultFile = "${tmp}/nexus-local-repository.txt".toString()
        script.sh "mkdir -p -- '${BuildUtils.escapeForSingleQuotes(tmp)}' && rm -f -- '${BuildUtils.escapeForSingleQuotes(resultFile)}'"
        Map helpCfg = [:] + mavenCfg
        helpCfg.goals = ['org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate']
        try {
            runMaven(helpCfg, [mavenProperty('expression', 'settings.localRepository'), mavenProperty('output', resultFile)])
            if (!script.fileExists(resultFile)) {
                script.error '[NEXUS] Maven did not report its local repository'
            }
            String localRepository = ((script.readFile(file: resultFile) ?: '') as String).trim()
            if (!localRepository.startsWith('/')) {
                script.error "[NEXUS] Maven reported a local repository that is not an absolute path: '${localRepository}'"
            }
            return localRepository
        } finally {
            script.sh "rm -f -- '${BuildUtils.escapeForSingleQuotes(resultFile)}'"
        }
    }

    private void runMaven(Map mavenCfg, List extraFlags) {
        Map runnerCfg = [maven: [:] + mavenCfg]
        runnerCfg.maven.flags = BuildUtils.normalizeTokens(mavenCfg.flags) + extraFlags
        BuildRunner runner = new BuildRunner(script, runnerCfg, 'maven')
        if (!runner.ifExist()) {
            script.error "[BUILD] Build tool is NOT installed on an agent"
        }
        runner.run()
    }

    private String nugetSource() {
        Map nugetCfg = (deliveryCfg().nuget ?: deliveryCfg().dotnet ?: [:]) as Map
        return (nugetCfg.source ?: nugetCfg.repositoryUrl ?: '') as String
    }

    private void deliverDotnetNuget() {
        Map nugetCfg = (deliveryCfg().nuget ?: deliveryCfg().dotnet ?: [:]) as Map
        String source = nugetSource()
        if (!source) {
            script.echo '[NEXUS] .NET NuGet publish not configured (set delivery.nuget.source)'
            return
        }
        String pattern = (nugetCfg.packagePattern ?: state.cfg.build?.buildPath ?: '**/*.nupkg') as String
        List<String> nupkgs = []
        try {
            for (def f : script.findFiles(glob: pattern)) {
                String p = (f?.path ?: '') as String
                if (p && p.toLowerCase().endsWith('.nupkg')) nupkgs << p
            }
        } catch (Exception ignored) {}
        if (!nupkgs) {
            script.error "[NEXUS] No .nupkg package matching '${pattern}' found for delivery.nuget"
        }
        String credId = (nugetCfg.credentialsId ?: nugetCfg.apiKeyCredentialsId ?: '') as String
        boolean useCli = !os.isWindows() || BuildUtils.booleanValue(state.cfg.build?.dotnet?.useDotnetCli, false)
        if (credId) {
            script.withCredentials([script.string(credentialsId: credId, variable: 'NUGET_API_KEY')]) {
                pushNugetPackages(nupkgs, source, useCli, os.isWindows() ? '$env:NUGET_API_KEY' : '$NUGET_API_KEY')
            }
        } else {
            pushNugetPackages(nupkgs, source, useCli, '')
        }
    }

    private void pushNugetPackages(List<String> nupkgs, String source, boolean useCli, String keyExpr) {
        for (String pkg : nupkgs) {
            if (useCli) {
                String args = keyExpr ? " --api-key ${keyExpr}" : ''
                os.run("dotnet nuget push '${pkg}' --source '${source}'${args}")
            } else {
                String args = keyExpr ? " -ApiKey ${keyExpr}" : ''
                os.run("nuget.exe push '${pkg}' -Source '${source}'${args} -NonInteractive")
            }
        }
    }

    private void notRequired(String reason) {
        script.echo reason
        state.projectNotRequired()
    }

    private Map deliveryCfg() {
        return mapOf(state.cfg.delivery)
    }

    @NonCPS
    static String buildTagFile() {
        return 'build.tag'
    }

    @NonCPS
    static Map mapOf(def value) {
        return value instanceof Map ? (value as Map) : [:]
    }

    @NonCPS
    static List listOf(def value) {
        List out = []
        if (value instanceof Collection) {
            out.addAll(value as Collection)
        } else if (value) {
            out << value
        }
        return out
    }

    @NonCPS
    static Map artifactEntry(def item) {
        if (item instanceof Map) return [path: (item as Map).path, classifier: (item as Map).classifier]
        return [path: item, classifier: null]
    }

    @NonCPS
    static Map describeArtifact(String name, def classifier) {
        int dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length() - 1) {
            return [error: "The artifact ${name} has no file extension".toString()]
        }
        String extension = name.substring(dot + 1)
        String chosen = classifier ? classifier.toString().trim() : name.substring(0, dot)
        if (!(extension ==~ /[A-Za-z0-9_-]+/)) {
            return [error: "The artifact ${name} has an extension Maven cannot upload".toString()]
        }
        if (!(chosen ==~ /[A-Za-z0-9_.-]+/)) {
            return [error: "The classifier '${chosen}' of ${name} must have letters, digits and . _ - only: set a classifier for the artifact".toString()]
        }
        return [fileName: name, extension: extension, classifier: chosen, error: '']
    }

    @NonCPS
    static String joined(List artifacts, String key) {
        return artifacts.collect { (it as Map).get(key) as String }.join(',')
    }

    @NonCPS
    static String mavenProperty(String name, Object value) {
        return "-D${name}=${value}".toString()
    }

    @NonCPS
    static String reservedGradleFlag(List flags) {
        for (def flag : flags) {
            String value = flag as String
            if (value in ['-p', '-b', '-x', '-m', '--dry-run'] || value.startsWith('-p=') || value.startsWith('--project-dir')
                    || value.startsWith('--build-file') || value.startsWith('-PnexusManifest') || value.startsWith('--exclude-task')) {
                return value
            }
        }
        return ''
    }
}
