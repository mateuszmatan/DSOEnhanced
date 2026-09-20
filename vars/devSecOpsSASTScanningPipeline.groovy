def call(Map config = [:]) {
    devSecOpsApi.configure('sast', config)

    pipeline {

        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
            timestamps()
        }

        parameters {
            choice(
                name:         'AGENT_NAME',
                choices:      config.agentNames,
                description:  'Jenkins agent label'
            )
        }

        agent { label params.AGENT_NAME }

        stages {
            stage('Monitor source changes (download sources)') {
                steps { script { devSecOpsSteps.monitorSources(checkoutScm: true, setupJava: true) } }
            }

            stage('SAST - Static Application Security Tests - HCL AppScan') {
                steps { script { devSecOpsSteps.sast() } }
            }
        }

        post {
            always {
                script {
                    devSecOpsApi.finishPipeline(
                        type:      'sast',
                        artifacts: 'report/pipeline-report.html,appscan-report*.html'
                    )
                }
            }
            success  { script { devSecOpsApi.section('Pipeline completed successfully!') } }
            failure  { script { devSecOpsApi.section('Pipeline FAILED') } }
            unstable { script { devSecOpsApi.section('Pipeline completed with warnings (unstable).') } }
        }
    }
}
