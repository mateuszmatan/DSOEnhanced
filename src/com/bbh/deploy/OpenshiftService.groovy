package com.bbh.deploy

import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils

class OpenshiftService implements Serializable {
    private final def         script
    private final PipelineState state

    OpenshiftService(def script, PipelineState state) {
        this.script = script
        this.state  = state
    }

    void buildDockerImage(String projectName) {
        if (!BuildUtils.nexusDeliveryEnabled(state.cfg)) {
            script.echo "[OPENSHIFT] ${projectName}: Nexus delivery is disabled by ${BuildUtils.nexusDeliverySetting(state.cfg)} - no image is built"
            return
        }
        String baseArtifactName = state.cfg.baseArtifactName
        def baseConfig = script.readYaml(file: BuildUtils.runStateFile())
        def osCfg = state.cfg.deploy?.openshift?.rd ?: [:]
        String rawBuildPath = (state.cfg.build?.buildPath ?: '') as String
        int lastSlash = rawBuildPath.lastIndexOf('/')
        String fallbackBuildPath = BuildUtils.isDotnetTool(state.cfg.buildTool as String)
                ? ((state.cfg.build?.dotnet?.publishDir ?: state.cfg.build?.msbuild?.publishDir ?: 'bin/Release') as String)
                : 'build/libs'
        String buildPath = lastSlash >= 0 ? rawBuildPath.substring(0, lastSlash) : fallbackBuildPath
        String appName = state.cfg.appName
        String artifactName = state.cfg.artifactName
        String dockerFilePath = osCfg.dockerFilePath
        String buildConfigPath = osCfg.buildConfigPath
        String buildContext = osCfg.buildContext
        String projectBuildR = osCfg.projectBuildR
        String appVersion = script.env.BUILD_NUMBER
        String addFile = (osCfg.addFile ?: '') as String

        script.echo """
The parameter 'appName'             = ${appName}
The parameter 'appVersion'          = ${appVersion}
The parameter 'dockerFilePath'      = ${dockerFilePath}
The parameter 'buildContext'        = ${buildContext}
The parameter 'projectBuildR'       = ${projectBuildR}
The parameter 'buildPath'           = ${buildPath}
The parameter 'baseArtifactName'    = ${baseArtifactName}
The parameter 'artifactName'        = ${artifactName}
        """
        if (baseArtifactName && baseArtifactName != artifactName) {
            String baseFileName = baseArtifactName.contains('/') ? baseArtifactName.substring(baseArtifactName.lastIndexOf('/') + 1) : baseArtifactName
            script.sh "find . -type f -name '${baseFileName}' -execdir mv -- '{}' '${artifactName}' ';'"
        }
        String addFileCopy = addFile.trim() ? """
            #Copy additional files
            for full_path in ${addFile}; do
                file_name="\${full_path##*/}"
                cp "\${full_path}" ${buildContext}/\$file_name
            done
""" : ''
        script.sh """
            ART_FILE=\$(ls ${buildPath}/${artifactName} 2>/dev/null | head -n 1)
            if [ -z "\$ART_FILE" ]; then ART_FILE=\$(find . -type f -name '${artifactName}' | head -n 1); fi

            echo "Detected artifact: \$ART_FILE"
            echo "Dockerfile path: ${dockerFilePath}"

            mkdir -p ${buildContext}

            # Copy application artifact    
            cp "\$ART_FILE" ${buildContext}/${artifactName}

            #Copy Dockerfile
            cp ${dockerFilePath} ${buildContext}/Dockerfile

${addFileCopy}
            echo "Build context content:"
            ls -la "${buildContext}"
        """

        script.openshift.withCluster() {
            script.openshift.withProject(projectBuildR) {
                script.echo "Project ${script.openshift.project()} in cluster ${script.openshift.cluster()}"
                def imageInfo = buildImage(appName, appVersion, buildConfigPath, dockerFilePath, buildContext)
                def internalDockerUrl = imageInfo[0]
                def buildTag = imageInfo[1]
                osCfg.internalDockerUrl = internalDockerUrl.toString()
                osCfg.buildTag = buildTag.toString()
            }
        }
        baseConfig.projects[projectName].deploy.openshift.rd.internalDockerUrl = osCfg.internalDockerUrl
        baseConfig.projects[projectName].deploy.openshift.rd.buildTag = osCfg.buildTag
        script.writeYaml file: BuildUtils.runStateFile(), data: baseConfig, overwrite: true
    }

    def checkDeploymentRepo(Map cfg = null) {
        def osCfg = (cfg ?: state.cfg).deploy?.openshift?.rd ?: [:]
        String deploymentPath = osCfg?.deploymentPath
        if (deploymentPath) {
            script.dir(deploymentPath) {
                script.git(
                    url: osCfg.deploymentRepo.url,
                    branch: osCfg.deploymentRepo.branch,
                    credentialsId: osCfg.deploymentRepo.credentials
                )
            }
        }
    }

    private def buildImage(String appName,
                   String appVersion,
                   String bcConfPath,
                   String dockerfilePath,
                   String buildSourcePath,
                   String templateBaseImage = null) {
        def openshift = script.openshift
        def now = new Date()
        def build
        def nowFormat = now.format("yyyyMMdd-HHmmss", TimeZone.getTimeZone('UTC'))
        def  buildTag= "$appVersion-$nowFormat"

        if ( ! openshift.selector("bc",appName).exists()) {
            script.echo "Creating BuildConfig $appName"
            def models = openshift.process( script.readFile("${bcConfPath}") , "-p" , "APP_NAME=$appName")
            script.echo "Created: ${openshift.apply( models ).names()}"
        }

        script.sh "echo >> ${dockerfilePath}"
        script.sh "echo LABEL com.bbh.app.name=$appName >> ${dockerfilePath}"
        script.sh "echo LABEL com.bbh.app.version=$appVersion >> ${dockerfilePath}"
        script.sh "echo LABEL com.bbh.image.build=$buildTag >> ${dockerfilePath}"

        def buildConfig = openshift.selector("bc",appName)

        if(templateBaseImage == null) {
            build = buildConfig.startBuild("--from-dir=${buildSourcePath}")
        }
        else {
            script.echo "Template Docker base image: $templateBaseImage"
            script.sh "sed -i 's|ARG BASE_IMAGE=.*|ARG BASE_IMAGE=${templateBaseImage}|' ${dockerfilePath}"
            build = buildConfig.startBuild("--from-dir=.", "--build-arg BASE_IMAGE=${templateBaseImage}")
        }
        build.logs("-f")
        def status = waitForBuildCompletion(build, 10, 2)

        def imageStreamTagName = status.outputDockerImageReference
        def sha256Id = status.output.to.imageDigest
        def internalDockerUrl = imageStreamTagName.replaceAll(':[^:]*$',"@$sha256Id")
        script.echo "Build output ImageStream: $imageStreamTagName = $internalDockerUrl"

        return [internalDockerUrl, buildTag]
    }

    private Map waitForBuildCompletion(def build, int timeoutMinutes, int pollSeconds) {
        Map finalStatus = null
        script.timeout(time: timeoutMinutes, unit: 'MINUTES') {
            script.waitUntil {
                Map status = (build.object()?.status ?: [:]) as Map
                String phase = (status.phase ?: "Unknown") as String

                script.echo "OpenShift build phase: ${phase}"
                if (phase in ["Complete", "Failed", "Error", "Cancelled"]) {
                    finalStatus = status
                    return true
                }

                script.sleep time: pollSeconds, unit: 'SECONDS'
                return false
            }
        }

        String phase = (finalStatus?.phase ?: "Unknown") as String
        if (phase != "Complete") {
            script.error "OpenShift build ended with phase='${phase}'"
        }

        return finalStatus
    }

    def copyImageToNexus() {
        if (!BuildUtils.nexusDeliveryEnabled(state.cfg)) {
            script.echo "[OPENSHIFT] ${state.currentProjectName}: Nexus delivery is disabled by ${BuildUtils.nexusDeliverySetting(state.cfg)} - no image is copied to Nexus"
            state.projectNotRequired()
            return
        }
        def osCfg = state.cfg.deploy?.openshift?.rd ?: [:]
        String internalDockerUrl = osCfg.internalDockerUrl

        String buildTag = osCfg.buildTag
        String qcDockerRepoPush = osCfg.qcDockerRepoPush
        String appVersion = script.env.BUILD_NUMBER
        String authfile = osCfg.nexus.authfile
        String openshiftCertDir = osCfg.openshiftCertDir

        script.echo """
The parameter 'internalDockerUrl' = ${internalDockerUrl}
The parameter 'qcDockerRepoPush'  = ${qcDockerRepoPush}
The parameter 'appVersion'        = ${appVersion}
The parameter 'authfile'          = ${authfile}
The parameter 'openshiftCertDir'  = ${openshiftCertDir}
        """

        if ((!internalDockerUrl) || (!buildTag))
            script.error "The required parameter (internalDockerUrl/buildTag/qcDockerRepoPush) is not defined"

        copyImageFromImageStream("$internalDockerUrl", "$qcDockerRepoPush:$buildTag", authfile, openshiftCertDir)
        copyImageFromImageStream("$internalDockerUrl", "$qcDockerRepoPush:$appVersion", authfile, openshiftCertDir)
        copyImageFromImageStream("$internalDockerUrl", "$qcDockerRepoPush:latest", authfile, openshiftCertDir)
    }

    private def copyImageFromImageStream(String internalDockerUrl, String targetDockerUrl,
                                 String authfile, String openshiftCertDir) {

        script.echo "Image $internalDockerUrl -> $targetDockerUrl"

        def output = script.sh(
                script: "set +x; skopeo copy -q --src-cert-dir $openshiftCertDir --src-creds openshift:\$(oc whoami -t) --authfile $authfile docker://$internalDockerUrl docker://$targetDockerUrl",
                returnStdout: true
        ).trim()
        script.echo output
    }

    void deploy(String envName) {
        def osCfg = state.cfg.deploy?.openshift?.get(envName) ?: [:]
        String appName = state.cfg.appName
        String dcPath = osCfg.deployConfigPath
        String projectDeploymentR = osCfg.projectDeploymentR
        String qcDockerRepoPull = osCfg.qcDockerRepoPull
        String buildTag = (osCfg.buildTag ?: state.cfg.deploy?.openshift?.rd?.buildTag) as String
        String healthCheckUrl = osCfg.healthCheckUrl
        String routeHostname = osCfg.routeHostnameR
        String configPathR = osCfg.configPathR
        boolean skipConfigDeploy = osCfg.skipConfigDeploy

        if (!buildTag && !BuildUtils.nexusDeliveryEnabled(state.cfg)) {
            script.echo "[OPENSHIFT] ${state.currentProjectName}: ${envName.toUpperCase()} deployment not required - Nexus delivery is disabled by ${BuildUtils.nexusDeliverySetting(state.cfg)} and no buildTag is pinned"
            state.projectNotRequired()
            return
        }
        if (!buildTag)
            script.error "The required parameter (buildTag) is not defined"

        script.echo """
The parameter 'appName'            = ${appName}
The parameter 'dcPath'             = ${dcPath}
The parameter 'projectDeploymentR' = ${projectDeploymentR}
The parameter 'qcDockerRepoPull'   = ${qcDockerRepoPull}
The parameter 'buildTag'           = ${buildTag}
    """

        def parameters =[]
        if (appName) {
            parameters << "APP_NAME=$appName"
        }
        if (projectDeploymentR) {
            parameters << "NAMESPACE=$projectDeploymentR"
        }
        if (healthCheckUrl) {
            parameters << "HEALTH_URL=$healthCheckUrl"
        }
        if (routeHostname) {
            parameters << "ROUTE_HOST=$routeHostname"
        }

        if(appName == "maker-chat-gui") {
            parameters << "IMAGE=${qcDockerRepoPull}:${buildTag}"
            parameters << "SPRING_PROFILES_ACTIVE=$envName"
            parameters << "APP_VERSION=$buildTag"
        }

        script.openshift.withCluster() {
            script.openshift.withProject(projectDeploymentR) {
                if (!skipConfigDeploy) {
                    script.echo "Deploying config to ${projectDeploymentR}"
                    deployConfig(appName, "${configPathR}", "APP_NAME=$appName")
                } else {
                    script.echo "Skipping the config deployment"
                }
                script.echo "Deploying ${appName} to ${projectDeploymentR}"
                deployImage(appName, qcDockerRepoPull, buildTag, "${dcPath}", parameters)
            }
        }
    }

    private def deployConfig(String appName, String configPath, Object... parameters){
        def openshift = script.openshift
        script.echo "Create or update config $appName"
        def configYaml = script.readFile(configPath)
        def configModel = openshift.process( configYaml, parameters )
        openshift.apply( configModel )
    }

    def deployImage(String appName, String dockerRepo, String buildTag, String dcPath, Object... parameters){
        def openshift = script.openshift

        script.echo "Creating or updating deployment $appName"
        def deployYaml = script.readFile(dcPath)
        def models = openshift.process( deployYaml , parameters)
        def created = openshift.apply( models )

        script.echo "Set image on image stream: $dockerRepo:$buildTag"
        openshift.tag("$dockerRepo:$buildTag", "$appName:latest", "--reference-policy=local")
        openshift.raw("label","deployment","$appName", "--overwrite=true", "tag=$buildTag")


        script.echo "Rollout deployment and wait"
        script.sleep (time: 30, unit: 'SECONDS')
        openshift.raw("rollout","restart","deployment/$appName")
        openshift.selector("deployment",appName).rollout().status()
    }

    void deliverToNexus(String envName) {
        def osCfg         = state.cfg.deploy?.openshift?.get(envName) ?: [:]
        def appName       = osCfg.appName
        def imageNs       = osCfg.imageNamespace
        def cluster       = osCfg.cluster
        def credId        = osCfg.credentialsId
        if (!appName) { script.echo "[NEXUS-DELIVERY] No OpenShift config for env: ${envName} - skipping."; return }
        script.openshift.withCluster(cluster, credId) {
            script.openshift.withProject(imageNs) {
                def tag = "${script.env.BUILD_NUMBER}"
                script.openshift.tag("${appName}:latest", "${appName}:${tag}")
                script.echo "[NEXUS-DELIVERY] Tagged ${appName}:latest as ${appName}:${tag}"
            }
        }
    }

    void runExtendedPipeline () {
        if (script.params.RUN_EXTENDED_PIPELINE) {
            String job = extendedPipelineJob()
            if (!job) {
                script.echo "Skipping extended pipeline - jenkins.pipeline.extendedPipeline is not set for the service in ${state.settingsPlace()}"
                return
            }
            script.echo "Running extended pipeline ${job}"
            script.build job: job,
                    wait: false,
                    parameters: [
                            script.string(
                                    name:         'AGENT_NAME',
                                    value:       script.params.AGENT_NAME
                            )
                    ]
        } else {
            script.echo "Skipping extended pipeline"
        }
    }

    private String extendedPipelineJob() {
        String own = (state.cfg?.jenkins?.pipeline?.extendedPipeline ?: '') as String
        if (own) return own
        for (def projectCfg : (state.projectsAllCfg ?: [:]).values()) {
            String job = (projectCfg?.jenkins?.pipeline?.extendedPipeline ?: '') as String
            if (job) return job
        }
        return ''
    }
}
