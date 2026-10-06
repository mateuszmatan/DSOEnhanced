package com.bbh.deploy

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils
import com.bbh.utils.FlutterUtils
import com.bbh.build.BuildRunner

class VmDeployService implements Serializable {
    private final def         script
    private final PipelineState state
    private final OsHelper    os

    VmDeployService(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state  = state
        this.os     = os
    }

    void deployRd() {
        deployOverSsh('rd', 'rdltaapps1.testbbh.com')
    }

    void deployQc() {
        deployOverSsh('qc', 'qcltaapps1.testbbh.com')
    }

    private void deployOverSsh(String environment, String defaultHost) {
        def vmCfg   = state.cfg.deploy?.vm?.get(environment) ?: [:]
        def host    = vmCfg.host         ?: defaultHost
        def user    = vmCfg.user         ?: 'taadmin'
        def dir     = vmCfg.deployDir    ?: '/opt/ta/deployment'
        def scr     = vmCfg.deployScript ?: 'scripts/deployment/zero-downtime-deployment.sh'
        def verFile = vmCfg.versionFile  ?: 'scripts/deployment/version.properties'
        os.run("ssh -o StrictHostKeyChecking=no ${user}@${host} uptime")
        os.chmodX(scr)
        sshRun(host, user, "mkdir -p ${dir}")
        scpTo("./${scr}",     host, user, "${dir}/")
        scpTo("./${verFile}", host, user, "${dir}/")
        sshRun(host, user, "${dir}/${scr.tokenize('/').last()}")
    }

    void publishToNexus() {
        def tool = state.cfg.buildTool ?: 'gradle'
        def cmd
        if (os.isWindows()) {
            cmd = (tool == 'maven')
                ? (script.fileExists('mvnw.cmd') ? '.\\mvnw.cmd' : 'mvn')
                : (script.fileExists('gradlew.bat') ? '.\\gradlew.bat' : 'gradle')
        } else {
            cmd = (tool == 'maven')
                ? (script.fileExists('mvnw') ? './mvnw' : 'mvn')
                : (script.fileExists('gradlew') ? './gradlew' : 'gradle')
        }
        switch (tool) {
            case 'gradle': os.run("${cmd} publish"); break
            case 'maven':  os.run("${cmd} deploy");  break
            default:       script.echo "[NEXUS] Publish not configured for build tool: ${tool}"
        }
    }

    void pushToNexus(String projectName) {
        def buildNumber = script.env.BUILD_NUMBER
        if (state.cfg.buildTool?.trim() == 'maven') {
            String filePathPattern = (state.cfg.build?.buildPath ?: '') as String
            if (!filePathPattern) {
                script.error "[NEXUS] build.buildPath must point at the artifact to publish, for example target/*.jar"
            }
            def file = filePathPattern
            if (filePathPattern.contains("*")){
                def files = script.findFiles(glob: filePathPattern)
                if (files.length == 0){
                    script.error("[NEXUS] No artifact matches build.buildPath '${filePathPattern}'")
                }
                file = files[0].path
                script.echo "Found a file: ${file}."
            }
            deliverToNexusMaven(buildNumber, file, projectName)
        }
        if (state.cfg.buildTool?.trim() == 'flutter') {
            script.echo "${script.env.version}"
            FlutterUtils.deliverToNexusAndroid(buildNumber, script, state.cfg, projectName, state.platform)
            FlutterUtils.deliverToNexusIOS(buildNumber, script, state.cfg, projectName, state.platform)
        }
    }

    void deliverToNexusMaven(String version, String filePath, String projectName){
        String extension = filePath.substring(filePath.lastIndexOf('.')+1)
        Map mavenCfg = ((state.cfg.delivery ?: [:]).maven ?: [:]) as Map
        if (!mavenCfg.goals) {
            script.error "[NEXUS] delivery.maven.goals must be set for the service in the DevSecOps portal to publish the Maven artifact"
        }
        def baseConfig = script.readYaml(file: BuildUtils.runStateFile())
        def now = new Date()
        def nowFormat = now.format("yyyyMMdd-HHmmss", TimeZone.getTimeZone('UTC'))
        def  buildTag= "$version-$nowFormat"
        Map runnerCfg = [maven: [:] + mavenCfg]
        runnerCfg.maven.flags = ((mavenCfg.flags ?: []) as List) + ["-Dversion=${buildTag}-SNAPSHOT".toString(),
                                                                    "-Dpackaging=${extension}".toString(),
                                                                    "-Dfile=${filePath}".toString()]
        BuildRunner runner = new BuildRunner(script, runnerCfg, 'maven')
        if (!runner.ifExist()) {
            script.error "[BUILD] Build tool is NOT installed on an agent"
        }
        runner.run()
        baseConfig.projects[projectName].delivery = buildTag
        script.writeYaml file: BuildUtils.runStateFile(), data: baseConfig, overwrite: true
    }

    void bumpVersion(String versionFile, String newVersion) {
        if (!newVersion) { script.echo "[VERSION] No version specified - skipping."; return }
        if (os.isWindows()) {
            script.powershell "(Get-Content '${versionFile}') -replace 'APP_VERSION=.*', 'APP_VERSION=${newVersion}' | Set-Content '${versionFile}'"
        } else {
            script.sh "sed -i 's/APP_VERSION=.*/APP_VERSION=${newVersion}/' '${versionFile}'"
        }
        script.echo "[VERSION] Bumped to ${newVersion} in ${versionFile}"
    }

    private List<Map> resolveDodApplications(Map dodCfg, String environment) {
        def configuredApplications = dodCfg.applications
        if (configuredApplications instanceof Collection && !configuredApplications.isEmpty()) {

            List<Map> applications = configuredApplications.collect { def application ->
                if (!(application instanceof Map)) {
                    script.error "[DOD] Every item in 'dod.applications' must be a map."
                }
                application as Map
            }

            List<Map> filteredApplications = applications.findAll { Map application ->
                def configuredEnvironments = application.environments

                if (configuredEnvironments == null) {
                    return true
                }

                if (!(configuredEnvironments instanceof Collection)) {
                    script.error "[DOD] 'environments' for application '${application.applicationName}' must be a list."
                }

                return configuredEnvironments.collect { def configuredEnvironment ->
                        configuredEnvironment?.toString()?.trim()?.toUpperCase()
                    }.contains(environment)
            }

            filteredApplications.sort { Map applicationA, Map applicationB ->
                int orderA = getApplicationOrder(applicationA)
                int orderB = getApplicationOrder(applicationB)

                return orderA <=> orderB
            }
            return filteredApplications
        }

        if (dodCfg.applicationName) {
            return [dodCfg]
        }
        return []
    }

    private void deployDodApplication(Map globalDodCfg, Map applicationCfg, String environment, String buildId) {
        String applicationName = BuildUtils.requiredString(script, applicationCfg.applicationName, "applicationName")
        String siteName = (applicationCfg.siteName ?: globalDodCfg.siteName ?: "deploy.bbh.com") as String
        String deployProcess = (applicationCfg.deployProcess ?: globalDodCfg.deployProcess ?: "tomcat-app-process") as String
        String snapshotBase = (applicationCfg.snapshotName ?: "${applicationName}_snapshot") as String
        String snapshotName = "${snapshotBase}_${buildId}"
        boolean skipWait = BuildUtils.booleanValue(applicationCfg.skipWait, BuildUtils.booleanValue(globalDodCfg.skipWait, false))
        boolean deployWithSnapshot = BuildUtils.booleanValue(applicationCfg.deployWithSnapshot, BuildUtils.booleanValue(globalDodCfg.deployWithSnapshot, true))
        boolean updateSnapshotComponents = BuildUtils.booleanValue(applicationCfg.updateSnapshotComp, BuildUtils.booleanValue(globalDodCfg.updateSnapshotComp, false))
        boolean includeOnlyDeployVersions = BuildUtils.booleanValue(applicationCfg.includeOnlyDeployVersions, BuildUtils.booleanValue(globalDodCfg.includeOnlyDeployVersions, true))
        boolean deployOnlyChanged = BuildUtils.booleanValue(applicationCfg.deployOnlyChanged, BuildUtils.booleanValue(globalDodCfg.deployOnlyChanged, false))
        String deployDescription = (applicationCfg.deployDescription ?: applicationCfg.description ?: globalDodCfg.deployDescription ?: globalDodCfg.description ?: "") as String
        String requestProperties = (applicationCfg.requestProperties ?: globalDodCfg.requestProperties ?: "") as String

        List<Map> components = resolveDodComponents(applicationCfg, buildId, applicationName)

        if (!components) {
            script.error "[DOD] No components configured for application '${applicationName}'"
        }
        script.echo "[DOD] Publishing ${components.size()} component(s) for ${applicationName}"

        components.each { Map component ->
            publishDodComponent(siteName, component)
        }
        String deployVersions =
            components.collect { Map component ->
                "${component.componentName}:${component.version}"
            }.join("\n")

        script.echo "[DOD] Deploy versions:\n${deployVersions}"
        script.echo "[DOD] Creating snapshot '${snapshotName}'"
        script.echo "[DOD] Deploying application '${applicationName}' to '${environment}'"

        script.step([
            $class  : "UCDeployPublisher",
            siteName: siteName,
            deploy: [
                deployApp : applicationName,
                deployEnv : environment,
                deployProc: deployProcess,
                skipWait  : skipWait,
                createSnapshot: [
                    snapshotName             : snapshotName,
                    deployWithSnapshot       : deployWithSnapshot,
                    updateSnapshotComp       : updateSnapshotComponents,
                    includeOnlyDeployVersions: includeOnlyDeployVersions
                ],
                deployVersions   : deployVersions,
                deployDesc       : deployDescription,
                requestProperties: requestProperties,
                deployOnlyChanged: deployOnlyChanged
            ]
        ])
        script.echo "[DOD] Application '${applicationName}' deployed successfully."
    }

    void deployWithDodPlugin(String environment) {
        Map dodCfg = (state.cfg.deploy?.vm?.dod ?: [:]) as Map
        if (!environment?.trim()) {
            script.error "[DOD] Environment is not defined"
        }
        List<Map> applications = resolveDodApplications(dodCfg, environment)

        if (!applications) {
            script.error "[DOD] No UrbanCode Deploy applications are configured"
        }
        String buildId = resolveBuildId()

        script.echo "[DOD] Environment: ${environment}"
        script.echo "[DOD] Applications to deploy: ${applications*.applicationName}"

        applications.each { Map applicationCfg ->
            deployDodApplication(dodCfg, applicationCfg, environment, buildId)
        }
        script.echo "[DOD] Deployment completed for ${applications.size()} application(s)"
    }

    void deployDvWithDodPlugin() {
        deployWithDodPlugin('DV')
    }

    private void publishDodComponent(String siteName, Map component) {
        String componentName = BuildUtils.requiredString(script, component.componentName, "componentName")
        String baseDir = BuildUtils.requiredString(script, component.baseDir, "baseDir")
        String fileIncludePatterns = BuildUtils.requiredString(script, component.fileIncludePatterns, "fileIncludePatterns")
        String version = BuildUtils.requiredString(script, component.version, "version")
        String pushDescription = (component.pushDescription ?: "") as String
        String fileExcludePatterns = (component.fileExcludePatterns ?: "") as String
        String extensions = (component.extensions ?: "") as String
        String charset = (component.charset ?: "") as String
        String versionProperties = (component.versionProperties ?: "") as String
        String versionDescription = (component.versionDescription ?: "") as String
        boolean incrementalVersion = BuildUtils.booleanValue(component.incrementalVersion,true)

        if (!script.fileExists(baseDir)) {
            script.error "[DOD] Base directory does not exist for component '${componentName}': ${baseDir}"
        }
        verifyComponentFiles(componentName, baseDir, fileIncludePatterns)

        script.echo "[DOD] Publishing component '${componentName}'"
        script.echo "[DOD] Base directory : ${baseDir}"
        script.echo "[DOD] File pattern   : ${fileIncludePatterns}"
        script.echo "[DOD] Version        : ${version}"

        script.step([
            $class  : "UCDeployPublisher",
            siteName: siteName,
            component: [
                componentName: componentName,
                delivery: [
                    $class              : "Push",
                    baseDir             : baseDir,
                    fileIncludePatterns : fileIncludePatterns,
                    fileExcludePatterns : fileExcludePatterns,
                    pushVersion         : version,
                    pushDescription     : pushDescription,
                    versionProperties   : versionProperties,
                    versionDescription  : versionDescription,
                    incrementalVersion  : incrementalVersion,
                    extensions          : extensions,
                    charset             : charset
                ]
            ]
        ])
        script.echo "[DOD] Component '${componentName}' published successfully."
    }

    private List<Map> resolveDodComponents(Map applicationCfg, String buildId, String applicationName) {
        def configuredComponents = applicationCfg.components
        if (!(configuredComponents instanceof Collection) || configuredComponents.isEmpty()) {
            return []
        }

        return configuredComponents.collect { def rawComponent ->
            if (!(rawComponent instanceof Map)) {
                script.error "[DOD] Every component for application '${applicationName}' must be a map."
            }
            Map componentCfg = rawComponent as Map
            String componentName = componentCfg.componentName?.toString()?.trim()
            String baseDir = componentCfg.baseDir?.toString()?.trim()
            String fileIncludePatterns = componentCfg.fileIncludePatterns?.toString()?.trim()

            if (!componentName) {
                script.error "[DOD] 'componentName' is required for every component in application '${applicationName}'."
            }
            if (!baseDir) {
                script.error "[DOD] 'baseDir' is required for component '${componentName}' in application '${applicationName}'."
            }
            if (!fileIncludePatterns) {
                script.error "[DOD] 'fileIncludePatterns' is required for component '${componentName}' in application '${applicationName}'."
            }

            String versionPrefix = (componentCfg.versionPrefix ?: fileIncludePatterns) as String
            String version = (componentCfg.version ?: "${versionPrefix}_${buildId}") as String

            return [
                    componentName      : componentName,
                    baseDir            : baseDir,
                    fileIncludePatterns: fileIncludePatterns,
                    fileExcludePatterns: componentCfg.fileExcludePatterns ?: "",
                    extensions         : componentCfg.extensions ?: "",
                    charset            : componentCfg.charset ?: "",
                    pushDescription    : componentCfg.pushDescription ?: "",
                    versionProperties  : componentCfg.versionProperties ?: "",
                    versionDescription : componentCfg.versionDescription ?: "",
                    incrementalVersion : BuildUtils.booleanValue(componentCfg.incrementalVersion, true),
                    version            : version
            ]
        }
    }

    private void verifyComponentFiles(String componentName, String baseDir, String fileIncludePatterns) {
        String findStatus = script.sh(
            script: """
                set +e
                cd '${BuildUtils.escapeForSingleQuotes(baseDir)}'
                find . -type f -name '${BuildUtils.escapeForSingleQuotes(fileIncludePatterns)}' -print -quit | grep -q .
                echo \$?
            """,
            returnStdout: true
        ).trim()

        if (findStatus != "0") {
            script.echo "[DOD] No files matching '${fileIncludePatterns}' were found for component '${componentName}'."
            script.sh(
                script: """
                    echo "[DOD] Files available under '${BuildUtils.escapeForSingleQuotes(baseDir)}':"
                    find '${BuildUtils.escapeForSingleQuotes(baseDir)}' -maxdepth 4 -type f -print
                """
            )

            script.error "[DOD] Deployment artifact was not found. Component: '${componentName}', baseDir: '${baseDir}', pattern: '${fileIncludePatterns}'."
        }
        script.echo "[DOD] Artifact validation passed for component '${componentName}'."
    }

    private int getApplicationOrder(Map application) {
        def configuredOrder = application.order

        if (configuredOrder == null || configuredOrder.toString().trim().isEmpty()) {
            return Integer.MAX_VALUE
        }

        try {
            return configuredOrder.toString().trim().toInteger()

        } catch (NumberFormatException ex) {
            script.error "[DOD] Invalid order '${configuredOrder}' for application '${application.applicationName}'. Order must be an integer."
            return Integer.MAX_VALUE
        }
    }

    private String resolveBuildId() {
        String buildId = script.env.BUILD_ID ?: script.env.BUILD_NUMBER

        if (!buildId) {
            script.error '[DOD] Neither BUILD_ID nor BUILD_NUMBER is available'
        }

        return buildId
    }

    private void sshRun(String host, String user, String remoteCmd) {
        os.run("ssh -o StrictHostKeyChecking=no ${user}@${host} \"${remoteCmd}\"")
    }

    private void scpTo(String localPath, String host, String user, String remotePath) {
        os.run("scp ${localPath} ${user}@${host}:${remotePath}")
    }
}