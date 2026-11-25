package com.bbh.deploy

import com.bbh.build.BuildRunner
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils
import com.bbh.utils.FlutterUtils
import com.bbh.utils.RestClient

class NexusDeliveryService implements Serializable {
    private final def           script
    private final PipelineState state
    private final OsHelper      os
    private boolean             sha256Step = true

    NexusDeliveryService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    void pushToNexus(String projectName) {
        String tool = buildTool()
        if (BuildUtils.isDotnetTool(tool)) {
            deliverDotnetNuget(nugetSource(), nugetCredentialsId(), false)
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
        if (tool in ['npm', 'pip'] && mapOf(mapOf(deliveryCfg().push).maven)) {
            pushMaven(projectName)
            return
        }
        if (tool == 'flutter') {
            String buildNumber = script.env.BUILD_NUMBER as String
            FlutterUtils.deliverToNexusAndroid(buildNumber, script, state.cfg, projectName, state.platform)
            FlutterUtils.deliverToNexusIOS(buildNumber, script, state.cfg, projectName, state.platform)
            return
        }
        if (tool in ['npm', 'pip']) {
            notRequired("[NEXUS] ${projectName}: set delivery.push.maven in ${state.settingsPlace()} to publish the ${tool} package with Maven - the artifact is not published")
            return
        }
        notRequired("[NEXUS] ${projectName}: the build tool ${tool ?: 'of the service'} has no Nexus snapshot delivery - the artifact is not published")
    }

    boolean releaseAvailable() {
        if (BuildUtils.isDotnetTool(buildTool())) return (nugetSource() || nugetReleaseSource()) as boolean
        String given = trimmed(mapOf(deliveryCfg().release).repositoryUrl)
        String url = given && validUrl(given) ? given : (releaseFallback('repositoryUrl', given, true, '').value as String)
        return (releaseEnabled(state.currentProjectName) && recordedUpload(state.buildTag ?: deliveredBuildTag(), state.currentProjectName) && url) as boolean
    }

    void publishRelease(String projectName, String variant = 'full') {
        String tool = buildTool()
        if (BuildUtils.isDotnetTool(tool)) {
            releaseDotnet(projectName, variant)
            return
        }
        if (!BuildUtils.nexusDeliveryEnabled(state.cfg)) {
            notRequired("[NEXUS] ${projectName}: Nexus delivery is disabled by ${BuildUtils.nexusDeliverySetting(state.cfg)} - no release is published")
            return
        }
        if (!releaseEnabled(projectName)) {
            notRequired("[NEXUS] ${projectName}: delivery.release.enabled is false - the snapshot of '${snapshotStage()}' stays the delivered artifact")
            return
        }
        if ((state.cfg.deployTarget ?: 'vm') == 'openshift') {
            notRequired("[NEXUS] ${projectName}: an OpenShift service delivers the image that '${snapshotStage()}' copied to Nexus - no artifact is released")
            return
        }
        if (tool == 'flutter') {
            notRequired("[NEXUS] ${projectName}: the Flutter APK and IPA of '${snapshotStage()}' stay the delivered artifacts - no release is published")
            return
        }
        String tag = variant == 'extended' ? deliveredBuildTag() : state.buildTag
        Map record = recordedUpload(tag, projectName)
        if (!record) {
            notRequired("[NEXUS] ${projectName}: '${snapshotStage()}' published no artifact of the service${tag ? ' as ' + tag + '-SNAPSHOT' : ''} (${recordFile()}) - nothing is released")
            return
        }
        if (!record.group || !record.artifact) {
            notRequired("[NEXUS] ${projectName}: the Maven flags of '${snapshotStage()}' name no -DgroupId and -DartifactId, so the release cannot name ${record.version} - the snapshot stays the delivered artifact")
            return
        }
        String coordinates = "${record.group}:${record.artifact}:${tag}".toString()
        Map release = releaseRepository(projectName, record)
        if (!release.url) {
            notRequired("[NEXUS] ${projectName}: set delivery.release.repositoryUrl in ${state.settingsPlace()} or in ${state.defaultsPlace()} to release ${coordinates} - the snapshot ${record.version} stays the delivered artifact")
            return
        }
        String localRepository = record.upload == 'gradle' ? '' : mavenLocalRepository(fetchMavenConfig(record))
        List files = releaseFiles(projectName, record, variant, localRepository)
        Map first = files[0] as Map
        String released = releasedChecksum(projectName, record, release, tag, first, localRepository)
        if (released) {
            if (released == first.sha256) {
                script.echo "[NEXUS] ${projectName}: ${coordinates} is already in ${release.url} with the files that passed the gate - nothing is uploaded"
                return
            }
            script.error "[NEXUS] ${projectName}: ${coordinates} is already in ${release.url} with other files than those that passed the gate - the release is not replaced"
        }
        script.echo "[NEXUS] ${projectName}: releasing ${files.size()} artifact(s) of ${record.version} as ${coordinates} to ${release.url}"
        if (record.upload == 'gradle') {
            uploadGradle(record, release, tag, files, "Nexus release: publish ${coordinates}".toString())
        } else {
            uploadMaven(projectName, record, release, tag, files)
        }
        script.echo "[NEXUS] ${projectName}: released ${coordinates}, the files that passed the gate"
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
        recordUpload(projectName, 'maven', mavenCoordinates(mavenCfg), version, artifacts)
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
        recordUpload(projectName, 'maven-legacy', mavenCoordinates(mavenCfg), version,
                [[path: file, fileName: BuildUtils.fileNameOf(file), extension: extension, classifier: '']])
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
        recordUpload(projectName, 'gradle', coordinates, version, artifacts)
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

    private void runGradle(Map cfg, Map manifest, String action, String credentialsId = '', String label = '') {
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
            gradleCfg.label = label ?: "Nexus Gradle ${action}".toString()
            gradleCfg.returnStdout = false
            gradleCfg.remove('dir')
            BuildRunner runner = new BuildRunner(script, [gradle: gradleCfg], 'gradle')
            if (!runner.ifExist()) {
                script.error "[NEXUS] The Gradle wrapper of ${applicationDir} cannot be run"
            }
            if (credentialsId) {
                script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'ORG_GRADLE_PROJECT_nexusUsername',
                        passwordVariable: 'ORG_GRADLE_PROJECT_nexusPassword')]) {
                    runner.run()
                }
            } else {
                runner.run()
            }
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

    private Map nugetConfig() {
        return mapOf(deliveryCfg().nuget ?: deliveryCfg().dotnet)
    }

    private String nugetSource() {
        Map nugetCfg = nugetConfig()
        return (nugetCfg.source ?: nugetCfg.repositoryUrl ?: '') as String
    }

    private String nugetReleaseSource() {
        return trimmed(nugetConfig().releaseSource)
    }

    private String nugetCredentialsId() {
        Map nugetCfg = nugetConfig()
        return (nugetCfg.credentialsId ?: nugetCfg.apiKeyCredentialsId ?: '') as String
    }

    private void releaseDotnet(String projectName, String variant) {
        String source = nugetSource()
        String releaseSource = nugetReleaseSource()
        String releaseCredentialsId = trimmed(nugetConfig().releaseCredentialsId) ?: nugetCredentialsId()
        if (!source && !releaseSource) {
            notRequired("[NEXUS] ${projectName}: delivery.nuget.source is not set in ${state.settingsPlace()} - no NuGet package is released")
            return
        }
        if (variant == 'extended') {
            boolean pushed = source && BuildUtils.nexusDeliveryEnabled(state.cfg) && (state.cfg.deployTarget ?: 'vm') != 'openshift'
            notRequired(pushed
                    ? "[NEXUS] ${projectName}: the extended pipeline builds no NuGet package - the package that the security pipeline pushed to ${source} stays the delivered package"
                    : "[NEXUS] ${projectName}: the extended pipeline builds no NuGet package and the security pipeline pushed none - the full pipeline pushes the package in this stage")
            return
        }
        if (!BuildUtils.nexusDeliveryEnabled(state.cfg) || (state.cfg.deployTarget ?: 'vm') == 'openshift') {
            if (releaseSource) {
                deliverDotnetNuget(releaseSource, releaseCredentialsId, true)
            } else {
                deliverDotnetNuget(source, nugetCredentialsId(), false)
            }
            return
        }
        if (!releaseSource) {
            notRequired("[NEXUS] ${projectName}: the NuGet package that '${snapshotStage()}' pushed to ${source} is the delivered package - set delivery.nuget.releaseSource to push it to a release feed as well")
            return
        }
        deliverDotnetNuget(releaseSource, releaseCredentialsId, true)
    }

    private void deliverDotnetNuget(String source, String credId, boolean skipDuplicate) {
        Map nugetCfg = nugetConfig()
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
        boolean useCli = !os.isWindows() || BuildUtils.booleanValue(state.cfg.build?.dotnet?.useDotnetCli, false)
        if (credId) {
            script.withCredentials([script.string(credentialsId: credId, variable: 'NUGET_API_KEY')]) {
                pushNugetPackages(nupkgs, source, useCli, os.isWindows() ? '$env:NUGET_API_KEY' : '$NUGET_API_KEY', skipDuplicate)
            }
        } else {
            pushNugetPackages(nupkgs, source, useCli, '', skipDuplicate)
        }
    }

    private void pushNugetPackages(List<String> nupkgs, String source, boolean useCli, String keyExpr, boolean skipDuplicate) {
        for (String pkg : nupkgs) {
            if (useCli) {
                String args = (keyExpr ? " --api-key ${keyExpr}" : '') + (skipDuplicate ? ' --skip-duplicate' : '')
                os.run("dotnet nuget push '${pkg}' --source '${source}'${args}")
            } else {
                String args = (keyExpr ? " -ApiKey ${keyExpr}" : '') + (skipDuplicate ? ' -SkipDuplicate' : '')
                os.run("nuget.exe push '${pkg}' -Source '${source}'${args} -NonInteractive")
            }
        }
    }

    private void recordUpload(String projectName, String upload, Map coordinates, String version, List artifacts) {
        List files = []
        for (int i = 0; i < artifacts.size(); i++) {
            Map artifact = artifacts[i] as Map
            String path = relativePath(artifact.path as String)
            files << [path: path, fileName: artifact.fileName, classifier: trimmed(artifact.classifier), extension: artifact.extension, sha256: checksum(path)]
        }
        Map record = readRecord()
        Map services = trimmed(record.buildTag) == state.buildTag ? ([:] + mapOf(record.services)) : [:]
        services[projectName] = [upload: upload, group: trimmed(coordinates.group), artifact: trimmed(coordinates.artifact), version: version,
                                 repositoryId: trimmed(coordinates.repositoryId), repositoryUrl: trimmed(coordinates.repositoryUrl),
                                 allowInsecureProtocol: coordinates.allowInsecureProtocol == true, files: files]
        script.writeJSON(file: recordFile(), json: [buildTag: state.buildTag, services: services], pretty: 2)
        script.echo "[NEXUS] ${projectName}: ${recordFile()} records the SHA-256 of the ${files.size()} uploaded file(s) for the release"
    }

    private Map readRecord() {
        String file = recordFile()
        if (!script.fileExists(file)) return [:]
        try {
            return mapOf(RestClient.withoutJsonNulls(script.readJSON(file: file)))
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[NEXUS] [WARN] ${file} cannot be read: ${e.message}"
            return [:]
        }
    }

    private Map recordedUpload(String tag, String projectName) {
        if (!tag) return [:]
        Map record = readRecord()
        if (trimmed(record.buildTag) != tag) return [:]
        Map entry = mapOf(mapOf(record.services)[projectName])
        return listOf(entry.files) ? entry : [:]
    }

    private boolean releaseEnabled(String projectName) {
        def value = mapOf(deliveryCfg().release).enabled
        Boolean given = switchOf(value)
        if (given != null) return given
        boolean fallback = true
        String place = ''
        List<Map> places = releaseDefaultPlaces()
        for (int i = 0; i < places.size() && !place; i++) {
            Boolean builtIn = switchOf(mapOf((places[i] as Map).values).enabled)
            if (builtIn != null) {
                fallback = builtIn
                place = (places[i] as Map).place as String
            }
        }
        if (value != null) {
            script.echo "[NEXUS] [WARN] ${projectName}: delivery.release.enabled: ${value} is not true or false - ${fallback}${place ? ' from ' + place : ''} is used instead"
        }
        return fallback
    }

    private List<Map> releaseDefaultPlaces() {
        Map own = mapOf(mapOf(mapOf(state.cfgDefaults).delivery).release)
        Map bundled = mapOf(mapOf(mapOf(state.bundledDefaults()).delivery).release)
        List<Map> places = [[place: state.defaultsPlace(), values: own]]
        if (bundled != own) places << [place: 'the library defaults', values: bundled]
        return places
    }

    private Map releaseFallback(String key, String given, boolean url, String avoid) {
        List<Map> places = releaseDefaultPlaces()
        for (int i = 0; i < places.size(); i++) {
            Map place = places[i] as Map
            String value = trimmed(mapOf(place.values)[key])
            boolean valid = url ? validUrl(value) : validId(value)
            if (value && value != given && valid && !(avoid && sameUrl(value, avoid))) return [value: value, place: place.place]
        }
        return [value: '', place: '']
    }

    @NonCPS
    private static Boolean switchOf(def value) {
        if (value instanceof Boolean) return value as Boolean
        String text = value == null ? '' : value.toString().trim().toLowerCase()
        if (text in ['true', 'yes', 'y', 'on', '1']) return true
        if (text in ['false', 'no', 'n', 'off', '0']) return false
        return null
    }

    private Map releaseRepository(String projectName, Map record) {
        Map own = mapOf(deliveryCfg().release)
        String snapshotUrl = trimmed(record.repositoryUrl)
        String url = releaseValue(projectName, 'repositoryUrl', own.repositoryUrl, true, snapshotUrl)
        if (url && snapshotUrl && sameUrl(url, snapshotUrl)) {
            Map backup = releaseFallback('repositoryUrl', url, true, snapshotUrl)
            script.echo "[NEXUS] [WARN] ${projectName}: delivery.release.repositoryUrl ${url} is the snapshot repository of '${snapshotStage()}' - " +
                    (backup.value ? "${backup.value} from ${backup.place} is used instead".toString() : 'a release needs a release repository')
            url = backup.value as String
        }
        String id = releaseValue(projectName, 'repositoryId', own.repositoryId, false, '')
        if (url && !id && record.upload != 'gradle' && trimmed(record.repositoryId)) {
            id = trimmed(record.repositoryId)
            script.echo "[NEXUS] ${projectName}: delivery.release.repositoryId is not set - Maven logs in to ${url} with the server ${id} of the snapshot upload"
        }
        String credentialsId = trimmed(own.credentialsId)
        List<Map> places = releaseDefaultPlaces()
        for (int i = 0; i < places.size() && !credentialsId; i++) {
            credentialsId = trimmed(mapOf((places[i] as Map).values).credentialsId)
        }
        return [url: url, id: id, credentialsId: credentialsId, allowInsecureProtocol: record.allowInsecureProtocol == true]
    }

    private String releaseValue(String projectName, String key, def given, boolean url, String avoid) {
        String value = trimmed(given)
        if (value && (url ? validUrl(value) : validId(value))) return value
        Map backup = releaseFallback(key, value, url, value ? avoid : '')
        if (!value) return backup.value as String
        String kind = url ? 'an http or https address' : 'a repository id of letters, digits and . _ - only'
        String outcome = backup.value ? "${backup.value} from ${backup.place} is used instead".toString() : (url ? 'no release repository is left, so nothing is released' : 'it is not used')
        script.echo "[NEXUS] [WARN] ${projectName}: delivery.release.${key} '${value}' is not ${kind} - ${outcome}"
        return backup.value as String
    }

    private List releaseFiles(String projectName, Map record, String variant, String localRepository) {
        List files = listOf(record.files)
        String folder = variant == 'extended' ? ((mapOf(deliveryCfg().pull).outputDirectory ?: 'downloaded-artifacts') as String) : ''
        Map found = [:]
        List missing = []
        for (int i = 0; i < files.size(); i++) {
            Map file = files[i] as Map
            if (!file.sha256) {
                script.error "[NEXUS] ${projectName}: ${recordFile()} holds no SHA-256 of ${file.fileName}, so the release cannot prove that it holds the file that passed the gate"
            }
            String candidate = folder ? "${folder}/${file.fileName}".toString() : (file.path as String)
            if (script.fileExists(candidate)) {
                if (checksum(candidate) == file.sha256) {
                    found[file.fileName] = candidate
                    continue
                }
                script.echo "[NEXUS] [WARN] ${projectName}: ${candidate} is not the ${file.fileName} that passed the gate - it is downloaded from ${record.repositoryUrl}"
            }
            missing << file
        }
        if (missing) found.putAll(fetchSnapshot(projectName, record, missing, localRepository))
        List out = []
        for (int i = 0; i < files.size(); i++) {
            Map file = files[i] as Map
            out << ([:] + file + [local: found[file.fileName]])
        }
        return out
    }

    private Map fetchSnapshot(String projectName, Map record, List files, String localRepository) {
        String coordinates = "${record.group}:${record.artifact}:${record.version}".toString()
        Map out = [:]
        if (record.upload == 'gradle') {
            String folder = "${releaseFolder(projectName)}/snapshot".toString()
            runGradle(gradlePullConfig(), [group: record.group, artifact: record.artifact, version: record.version, repositoryUrl: record.repositoryUrl,
                                           allowInsecureProtocol: record.allowInsecureProtocol == true, artifacts: manifestArtifacts(files, false),
                                           outputDirectory: folder], 'pull', '', "Nexus release: download ${coordinates}".toString())
            for (int i = 0; i < files.size(); i++) {
                Map file = files[i] as Map
                out[file.fileName] = "${folder}/${file.fileName}".toString()
            }
        } else {
            Map mavenCfg = fetchMavenConfig(record)
            for (int i = 0; i < files.size(); i++) {
                Map file = files[i] as Map
                out[file.fileName] = fetchMaven(mavenCfg, record, record.version, trimmed(record.repositoryId), trimmed(record.repositoryUrl), file, localRepository,
                        "Nexus release: download ${file.fileName} of ${coordinates}".toString())
            }
        }
        for (int i = 0; i < files.size(); i++) {
            Map file = files[i] as Map
            String path = (out[file.fileName] ?: '') as String
            if (!path || !script.fileExists(path) || checksum(path) != file.sha256) {
                script.error "[NEXUS] ${projectName}: ${file.fileName} of ${coordinates} in ${record.repositoryUrl} is not the file that passed the gate - nothing is released"
            }
        }
        script.echo "[NEXUS] ${projectName}: downloaded ${files.size()} file(s) of ${coordinates} that passed the gate"
        return out
    }

    private String releasedChecksum(String projectName, Map record, Map release, String tag, Map first, String localRepository) {
        String coordinates = "${record.group}:${record.artifact}:${tag}".toString()
        String label = "Nexus release: look for ${coordinates} in the release repository".toString()
        String path = ''
        try {
            if (record.upload == 'gradle') {
                String folder = "${releaseFolder(projectName)}/released".toString()
                runGradle(gradlePullConfig(), [group: record.group, artifact: record.artifact, version: tag, repositoryUrl: release.url,
                                               allowInsecureProtocol: release.allowInsecureProtocol == true, credentials: release.credentialsId as boolean,
                                               artifacts: manifestArtifacts([first], false), outputDirectory: folder], 'pull', release.credentialsId as String, label)
                path = "${folder}/${first.fileName}".toString()
            } else {
                path = fetchMaven(fetchMavenConfig(record), record, tag, release.id as String, release.url as String, first, localRepository, label)
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            path = ''
        }
        if (!path || !script.fileExists(path)) {
            script.echo "[NEXUS] ${projectName}: ${coordinates} is not in ${release.url} yet"
            return ''
        }
        return checksum(path)
    }

    private String fetchMaven(Map mavenCfg, Map record, String version, String repositoryId, String repositoryUrl, Map file, String localRepository, String label) {
        String classifier = trimmed(file.classifier)
        String coordinates = "${record.group}:${record.artifact}:${version}:${file.extension}${classifier ? ':' + classifier : ''}".toString()
        Map getCfg = [:] + mavenCfg
        getCfg.goals = [dependencyGetGoal()]
        getCfg.label = label
        runMaven(getCfg, ['-U', mavenProperty('artifact', coordinates), mavenProperty('maven.repo.local', localRepository), mavenProperty('transitive', 'false'),
                          mavenProperty('remoteRepositories', "${repositoryId ?: 'nexus'}::default::${repositoryUrl}".toString())])
        return "${localRepository}/${(record.group as String).replace('.', '/')}/${record.artifact}/${version}/${record.artifact}-${version}${classifier ? '-' + classifier : ''}.${file.extension}".toString()
    }

    private void uploadMaven(String projectName, Map record, Map release, String tag, List files) {
        Map mavenCfg = uploadMavenConfig(record)
        if (!mavenCfg.goals) {
            script.error "[NEXUS] ${projectName}: ${record.upload == 'maven-legacy' ? 'delivery.maven' : 'delivery.push.maven'}.goals must be set in ${state.settingsPlace()} to release the Maven artifacts"
        }
        mavenCfg.label = "Nexus release: publish ${record.group}:${record.artifact}:${tag}".toString()
        Map first = files[0] as Map
        List flags = []
        if (release.id) flags << mavenProperty('repositoryId', release.id)
        flags << mavenProperty('url', release.url)
        flags << mavenProperty('groupId', record.group)
        flags << mavenProperty('artifactId', record.artifact)
        flags << mavenProperty('version', tag)
        flags << mavenProperty('file', first.local)
        flags << mavenProperty('packaging', first.extension)
        flags << mavenProperty('generatePom', 'true')
        if (first.classifier) flags << mavenProperty('classifier', first.classifier)
        if (files.size() > 1) {
            List rest = []
            for (int i = 1; i < files.size(); i++) rest << files[i]
            flags << mavenProperty('files', joined(rest, 'local'))
            flags << mavenProperty('types', joined(rest, 'extension'))
            flags << mavenProperty('classifiers', joined(rest, 'classifier'))
        }
        runMaven(mavenCfg, flags)
    }

    private void uploadGradle(Map record, Map release, String tag, List files, String label) {
        Map cfg = mapOf(mapOf(deliveryCfg().push).gradle)
        runGradle(cfg, [group: record.group, artifact: record.artifact, version: tag, repositoryUrl: release.url,
                        allowInsecureProtocol: release.allowInsecureProtocol == true, credentials: release.credentialsId as boolean,
                        artifacts: manifestArtifacts(files, true)], 'push', release.credentialsId as String, label)
    }

    private List manifestArtifacts(List files, boolean withPath) {
        List out = []
        for (int i = 0; i < files.size(); i++) {
            Map file = files[i] as Map
            Map artifact = [fileName: file.fileName, extension: file.extension, classifier: file.classifier]
            if (withPath) artifact.path = absolutePath(file.local as String)
            out << artifact
        }
        return out
    }

    private Map gradlePullConfig() {
        Map cfg = [:] + mapOf(mapOf(deliveryCfg().push).gradle)
        cfg.remove('tasks')
        List flags = BuildUtils.normalizeTokens(cfg.flags)
        if (!flags.contains('--refresh-dependencies')) flags << '--refresh-dependencies'
        cfg.flags = flags
        return cfg
    }

    private Map fetchMavenConfig(Map record) {
        Map cfg = [:] + mavenUploadSettings(record)
        cfg.flags = withoutDeployFlags(BuildUtils.normalizeTokens(cfg.flags))
        return cfg
    }

    private Map uploadMavenConfig(Map record) {
        Map cfg = [:] + mavenUploadSettings(record)
        cfg.flags = withoutDeployFlags(BuildUtils.normalizeTokens(cfg.flags))
        return cfg
    }

    private Map mavenUploadSettings(Map record) {
        if (record.upload == 'maven-legacy') return mapOf(deliveryCfg().maven)
        return mapOf(mapOf(deliveryCfg().push).maven)
    }

    private String releaseFolder(String projectName) {
        return "${script.pwd(tmp: true)}/nexus-release/${projectName}".toString()
    }

    private String relativePath(String path) {
        String root = "${script.pwd()}/".toString()
        return path && path.startsWith(root) ? path.substring(root.length()) : path
    }

    private String absolutePath(String path) {
        if (isAbsolute(path)) return path
        return "${script.pwd()}/${path}".toString()
    }

    private String checksum(String path) {
        if (sha256Step) {
            try {
                return trimmed(script.sha256(file: path)).toLowerCase()
            } catch (Throwable t) {
                if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
                sha256Step = false
                script.echo "[NEXUS] The sha256 step of the Pipeline Utility Steps plugin cannot be used (${t.getClass().getSimpleName()}) - " +
                        "the SHA-256 of the delivered files is computed with ${os.isWindows() ? 'Get-FileHash' : 'sha256sum'} instead"
            }
        }
        String hash = ''
        String label = "Nexus delivery: SHA-256 of ${BuildUtils.fileNameOf(path)}".toString()
        try {
            if (os.isWindows()) {
                hash = script.powershell(script: "(Get-FileHash -Algorithm SHA256 -LiteralPath '${path.replace("'", "''")}').Hash", returnStdout: true, label: label) as String
            } else {
                String quoted = BuildUtils.escapeForSingleQuotes(path)
                hash = script.sh(script: "(sha256sum -- '${quoted}' 2>/dev/null || shasum -a 256 -- '${quoted}') | cut -d ' ' -f 1", returnStdout: true, label: label) as String
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
        }
        hash = trimmed(hash).toLowerCase()
        if (hash ==~ /[0-9a-f]{64}/) return hash
        script.echo "[NEXUS] [WARN] The SHA-256 of ${path} cannot be computed - the file cannot be proven to be the one that passed the gate, so it is not released"
        return ''
    }

    private String buildTool() {
        return ((state.cfg.buildTool ?: '') as String).trim()
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
    static String recordFile() {
        return 'nexus-delivery.json'
    }

    @NonCPS
    static String snapshotStage() {
        return 'Nexus delivery (Static analysis passed)'
    }

    @NonCPS
    static String dependencyGetGoal() {
        return 'org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get'
    }

    @NonCPS
    static String trimmed(def value) {
        return value == null ? '' : value.toString().trim()
    }

    @NonCPS
    static boolean validUrl(String value) {
        return (value ==~ /https?:\/\/[^\s'"`]+/) as boolean
    }

    @NonCPS
    static boolean validId(String value) {
        return (value ==~ /[A-Za-z0-9_.-]+/) as boolean
    }

    @NonCPS
    static boolean sameUrl(String one, String other) {
        return one.replaceAll('/+$', '').toLowerCase() == other.replaceAll('/+$', '').toLowerCase()
    }

    @NonCPS
    static boolean isAbsolute(String path) {
        return path != null && (path.startsWith('/') || path.startsWith('\\') || (path ==~ /[A-Za-z]:[\\\/].*/))
    }

    @NonCPS
    static Map mavenCoordinates(Map mavenCfg) {
        Map out = [:]
        Map names = [groupId: 'group', artifactId: 'artifact', repositoryId: 'repositoryId', url: 'repositoryUrl']
        for (def flag : BuildUtils.normalizeTokens(mavenCfg?.flags)) {
            String value = flag as String
            for (def entry : names.entrySet()) {
                String prefix = "-D${entry.key}=".toString()
                if (value.startsWith(prefix)) out[entry.value] = value.substring(prefix.length())
            }
        }
        return out
    }

    @NonCPS
    static List withoutDeployFlags(List flags) {
        List reserved = ['repositoryId', 'url', 'groupId', 'artifactId', 'version', 'file', 'packaging', 'classifier', 'files', 'types',
                         'classifiers', 'pomFile', 'generatePom', 'artifact', 'maven.repo.local', 'transitive', 'remoteRepositories']
        List out = []
        for (def flag : flags) {
            String value = flag as String
            boolean drop = false
            for (def name : reserved) {
                if (value.startsWith("-D${name}=".toString())) drop = true
            }
            if (!drop) out << value
        }
        return out
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
