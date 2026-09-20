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
                choices:      config.agentNames,
                description:  'Jenkins agent label'
            )
        }

        agent { label params.AGENT_NAME }

        stages {
            stage('Monitor source changes (download sources)') {
                steps { script { devSecOpsSteps.monitorSources(copyArtifactsFrom: config.securityPipeline) } }
            }

            stage('Lower test region deployment') {
                steps { script { devSecOpsSteps.lowerRegionDeployment('ssh') } }
            }

            stage('Regression tests (>60% user stories coverage)') {
                steps { script { devSecOpsSteps.regressionTests() } }
            }

            stage('Smoke tests') {
                steps { script { devSecOpsSteps.smokeTests() } }
            }

            stage('Performance tests') {
                steps { script { devSecOpsSteps.performanceTests() } }
            }

            stage('DAST - Dynamic Application Security Tests - HCL AppScan') {
                steps { script { devSecOpsSteps.dast(setupTools: true) } }
            }

            stage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') {
                when { expression { return devSecOpsApi.releaseAllowed('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') } }
                steps { script { devSecOpsSteps.nexusReleaseDelivery() } }
            }

            stage('Higher test environment deployment') {
                when { expression { return params.DEPLOY_HIGHER_ENV && devSecOpsApi.releaseAllowed('Higher test environment deployment') } }
                steps { script { devSecOpsSteps.higherEnvironmentDeployment() } }
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
