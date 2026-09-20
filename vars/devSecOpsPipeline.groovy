def call(Map config = [:]) {
    devSecOpsApi.configure('full', config)

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
                steps { script { devSecOpsSteps.monitorSources(devSecOpsApi) } }
            }

            stage('Unit tests') {
                steps { script { devSecOpsSteps.unitTests(devSecOpsApi) } }
            }

            stage('Dependencies scan (Nexus IQ)') {
                steps { script { devSecOpsSteps.dependenciesScan(devSecOpsApi) } }
            }

            stage('SAST - Static Application Security Tests - HCL AppScan') {
                steps { script { devSecOpsSteps.sast(devSecOpsApi) } }
            }

            stage('SCA (SonarQube)') {
                steps { script { devSecOpsSteps.sonarQube(devSecOpsApi) } }
            }

            stage('Nexus delivery (Static analysis passed)') {
                steps { script { devSecOpsSteps.nexusSnapshotDelivery(devSecOpsApi) } }
            }

            stage('Lower test region deployment') {
                steps { script { devSecOpsSteps.lowerRegionDeployment(devSecOpsApi, 'dod') } }
            }

            stage('Regression tests (>60% user stories coverage)') {
                steps { script { devSecOpsSteps.regressionTests(devSecOpsApi) } }
            }

            stage('Smoke tests') {
                steps { script { devSecOpsSteps.smokeTests(devSecOpsApi) } }
            }

            stage('Performance tests') {
                steps { script { devSecOpsSteps.performanceTests(devSecOpsApi) } }
            }

            stage('DAST - Dynamic Application Security Tests - HCL AppScan') {
                steps { script { devSecOpsSteps.dast(devSecOpsApi) } }
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
            always   { script { devSecOpsApi.finishPipeline(type: '') } }
            success  { script { devSecOpsApi.section('Pipeline completed successfully!') } }
            failure  { script { devSecOpsApi.section('Pipeline FAILED') } }
            unstable { script { devSecOpsApi.section('Pipeline completed with warnings (unstable).') } }
        }
    }
}
