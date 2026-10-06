def monitorSources(def api, Map options = [:]) {
    api.runStage('Monitor source changes (download sources)') {
        if (options.checkoutScm) {
            checkout scm
        }
        if (options.setupJava) {
            api.setupJavaVersion()
        }
        String upstream = (options.copyArtifactsFrom ?: '') as String
        if (upstream) {
            copyArtifacts(projectName: upstream, filter: 'pipeline-config.yaml,release-gate.json', selector: lastSuccessful())
        }
        api.initialize()
    }
}

def unitTests(def api) {
    api.runStage('Unit tests') {
        api.eachProject {
            api.buildArtifact()
            api.unitTests()
            api.checkCoverage()
        }
    }
}

def dependenciesScan(def api) {
    api.runStage('Dependencies scan (Nexus IQ)') {
        api.eachProject {
            api.depVulnScan()
        }
    }
}

def sast(def api) {
    api.runStage('SAST - Static Application Security Tests - HCL AppScan') {
        Map config = api.pipelineConfig()
        api.appscanSetup()
        api.eachProject {
            api.appscanResolveSourceDir()
        }
        api.appscanLogin()
        api.eachProject {
            api.appscanGenerateIRX()
            api.appscanQueue(config)
        }
        api.eachProject {
            api.appscanWait()
            api.appscanDownloadReports()
            api.appscanRenameSastReport()
            api.appscanEnforcePolicy()
        }
    }
}

def sonarQube(def api) {
    api.runStage('SCA (SonarQube)') {
        api.eachProject {
            api.codeQualityScan()
            api.sonarscanEnforcePolicy()
        }
    }
}

def nexusSnapshotDelivery(def api) {
    api.runStage('Nexus delivery (Static analysis passed)') {
        api.eachProject { String projectName ->
            api.reportArtifactBuild()
            api.reportUnitTests()
            if (api.deployTarget() == 'openshift') {
                api.buildDockerImage(projectName)
                api.copyImageToNexus()
            } else {
                api.pushToNexus(projectName)
            }
        }
    }
}

def lowerRegionDeployment(def api, String vmMode = 'ssh') {
    api.runStage('Lower test region deployment') {
        api.eachProject {
            if (api.deployTarget() == 'openshift') {
                api.checkDeploymentRepo()
                api.deployOpenshift('rd')
            } else if (vmMode == 'dod') {
                api.deployDvWithDodPlugin()
            } else {
                api.deployRD()
            }
        }
    }
}

def regressionTests(def api) {
    api.runStage('Regression tests (>60% user stories coverage)') {
        api.eachProject {
            api.regressionTests()
        }
    }
}

def smokeTests(def api) {
    api.runStage('Smoke tests') {
        api.eachProject {
            api.smokeTests()
        }
    }
}

def performanceTests(def api) {
    api.runStage('Performance tests') {
        api.eachProject {
            api.performanceTests()
        }
    }
}

def dast(def api, Map options = [:]) {
    api.runStage('DAST - Dynamic Application Security Tests - HCL AppScan') {
        if (options.setupTools) {
            api.appscanSetup()
        }
        api.eachProject {
            api.dastScan()
        }
    }
}

def nexusReleaseDelivery(def api) {
    api.runStage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') {
        api.eachProject {
            api.publishArtifactQC()
        }
    }
}

def higherEnvironmentDeployment(def api) {
    api.runStage('Higher test environment deployment') {
        api.eachProject {
            if (api.deployTarget() == 'openshift') {
                api.deployOpenshift('qc')
            } else {
                api.deployQC()
            }
        }
    }
}
