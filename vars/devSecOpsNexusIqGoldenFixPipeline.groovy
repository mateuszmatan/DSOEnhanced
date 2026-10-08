def call(Map config = [:]) {
    devSecOpsApi.configure('nexusiq', config)

    pipeline {

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
            timestamps()
        }

        parameters {
            choice(
                name:         'AGENT_NAME',
                choices:      devSecOpsApi.pipelineConfig().agentNames,
                description:  'Jenkins agent label'
            )
        }

        agent { label params.AGENT_NAME }

        stages {
            stage('Monitor source changes (download sources)') {
                steps { script { devSecOpsSteps.monitorSources(devSecOpsApi, [checkoutScm: true, setupJava: true]) } }
            }

            stage('Build artifact') {
                steps { script { devSecOpsSteps.buildArtifacts(devSecOpsApi) } }
            }

            stage('Dependencies scan (Nexus IQ)') {
                steps { script { devSecOpsSteps.dependenciesScan(devSecOpsApi) } }
            }
        }

        post {
            always {
                script {
                    devSecOpsApi.finishPipeline(
                        type:      'nexusiq',
                        artifacts: 'report/pipeline-report.html'
                    )
                }
            }
            success  { script { devSecOpsApi.section('Pipeline completed successfully!') } }
            failure  { script { devSecOpsApi.section('Pipeline FAILED') } }
            unstable { script { devSecOpsApi.section('Pipeline completed with warnings (unstable).') } }
        }
    }
}
