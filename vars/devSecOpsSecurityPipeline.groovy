def call(Map config = [:]) {
    devSecOpsApi.configure('security', config)

    pipeline {

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
            timestamps()
        }

        parameters {
            booleanParam(
                name:         'RUN_EXTENDED_PIPELINE',
                defaultValue: false,
                description:  'Trigger the extended pipeline named in jenkins.pipeline.extendedPipeline of config.yaml'
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
                steps { script { devSecOpsSteps.monitorSources() } }
            }

            stage('Unit tests') {
                steps { script { devSecOpsSteps.unitTests() } }
            }

            stage('Dependencies scan (Nexus IQ)') {
                steps { script { devSecOpsSteps.dependenciesScan() } }
            }

            stage('SAST - Static Application Security Tests - HCL AppScan') {
                steps { script { devSecOpsSteps.sast() } }
            }

            stage('SCA (SonarQube)') {
                steps { script { devSecOpsSteps.sonarQube() } }
            }

            stage('Nexus delivery (Static analysis passed)') {
                steps { script { devSecOpsSteps.nexusSnapshotDelivery() } }
            }
        }

        post {
            always {
                script {
                    devSecOpsApi.finishPipeline(
                        type:      'security',
                        artifacts: 'report/pipeline-report.html,appscan-report*.html,config.yaml,release-gate.json'
                    )
                }
            }
            success {
                script {
                    devSecOpsApi.runExtendedPipeline()
                    devSecOpsApi.section('Pipeline completed successfully!')
                }
            }
            failure {
                script { devSecOpsApi.section('Pipeline FAILED') }
            }
            unstable {
                script {
                    devSecOpsApi.runExtendedPipeline()
                    devSecOpsApi.section('Pipeline completed with warnings (unstable).')
                }
            }
        }
    }
}
