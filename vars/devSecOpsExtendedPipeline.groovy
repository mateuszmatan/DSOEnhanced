def call(Map config = [:]) {
    devSecOpsApi.configure('extended', config)

    pipeline {

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
            timestamps()
        }

        parameters {
            booleanParam(
                name:         'DEPLOY_HIGHER_ENV',
                defaultValue: false,
                description:  'Deploy to higher test environment (QC). Allowed only when every earlier stage is green'
            )

            choice(
                name:         'AGENT_NAME',
                choices:      devSecOpsApi.pipelineConfig().agentNames,
                description:  'Jenkins agent label'
            )
        }

        agent { label params.AGENT_NAME }

        stages {
            stage('Monitor source changes (download sources)') {
                steps { script { devSecOpsSteps.monitorSources(devSecOpsApi, [copyArtifactsFrom: devSecOpsApi.pipelineConfig().securityPipeline]) } }
            }

            stage('Download artifacts from Nexus Repo') {
                steps { script { devSecOpsSteps.pullNexusSnapshot(devSecOpsApi) } }
            }

            stage('Lower test region deployment') {
                steps { script { devSecOpsSteps.lowerRegionDeployment(devSecOpsApi, 'dod') } }
            }

            stage('Dynamic analysis') {
                when { expression { return devSecOpsApi.parallelTests() } }
                steps { script { devSecOpsSteps.dynamicAnalysis(devSecOpsApi, [setupTools: true]) } }
            }

            stage('Regression tests (>60% user stories coverage)') {
                when { expression { return !devSecOpsApi.parallelTests() } }
                steps { script { devSecOpsSteps.regressionTests(devSecOpsApi) } }
            }

            stage('Smoke tests') {
                when { expression { return !devSecOpsApi.parallelTests() } }
                steps { script { devSecOpsSteps.smokeTests(devSecOpsApi) } }
            }

            stage('Performance tests') {
                when { expression { return !devSecOpsApi.parallelTests() } }
                steps { script { devSecOpsSteps.performanceTests(devSecOpsApi) } }
            }

            stage('DAST - Dynamic Application Security Tests - HCL AppScan') {
                when { expression { return !devSecOpsApi.parallelTests() } }
                steps { script { devSecOpsSteps.dast(devSecOpsApi, [setupTools: true]) } }
            }

            stage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') {
                when { expression { return devSecOpsApi.releaseAllowed('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') } }
                steps { script { devSecOpsSteps.nexusReleaseDelivery(devSecOpsApi) } }
            }

            stage('Higher test environment deployment') {
                when { expression { return params.DEPLOY_HIGHER_ENV && devSecOpsApi.releaseAllowed('Higher test environment deployment') } }
                steps { script { devSecOpsSteps.higherEnvironmentDeployment(devSecOpsApi) } }
            }
        }

        post {
            always   { script { devSecOpsApi.finishPipeline(type: 'extended') } }
            success  { script { devSecOpsApi.section('Pipeline completed successfully!') } }
            failure  { script { devSecOpsApi.section('Pipeline FAILED') } }
            unstable { script { devSecOpsApi.section('Pipeline completed with warnings (unstable).') } }
        }
    }
}
