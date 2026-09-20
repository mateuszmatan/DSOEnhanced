def monitorSources(Map options = [:]) {
    devSecOpsApi.runStage('Monitor source changes (download sources)') {
        if (options.checkoutScm) {
            checkout scm
        }
        if (options.setupJava) {
            devSecOpsApi.setupJavaVersion()
        }
        String upstream = (options.copyArtifactsFrom ?: '') as String
        if (upstream) {
            copyArtifacts(projectName: upstream, filter: 'config.yaml,release-gate.json', selector: lastSuccessful())
        }
        devSecOpsApi.initialize()
    }
}

def unitTests() {
    devSecOpsApi.runStage('Unit tests') {
        devSecOpsApi.eachProject {
            devSecOpsApi.buildArtifact()
            devSecOpsApi.unitTests()
            devSecOpsApi.checkCoverage()
        }
    }
}

def dependenciesScan() {
    devSecOpsApi.runStage('Dependencies scan (Nexus IQ)') {
        devSecOpsApi.eachProject {
            devSecOpsApi.depVulnScan()
        }
    }
}

def sast() {
    devSecOpsApi.runStage('SAST - Static Application Security Tests - HCL AppScan') {
        Map config = devSecOpsApi.pipelineConfig()
        devSecOpsApi.appscanSetup()
        devSecOpsApi.eachProject {
            devSecOpsApi.appscanResolveSourceDir()
        }
        devSecOpsApi.appscanLogin()
        devSecOpsApi.eachProject {
            devSecOpsApi.appscanGenerateIRX()
            devSecOpsApi.appscanQueue(config)
        }
        devSecOpsApi.eachProject {
            devSecOpsApi.appscanWait()
            devSecOpsApi.appscanDownloadReports()
            devSecOpsApi.appscanRenameSastReport()
            devSecOpsApi.appscanEnforcePolicy()
        }
    }
}

def sonarQube() {
    devSecOpsApi.runStage('SCA (SonarQube)') {
        devSecOpsApi.eachProject {
            devSecOpsApi.codeQualityScan()
            devSecOpsApi.sonarscanEnforcePolicy()
        }
    }
}

def nexusSnapshotDelivery() {
    devSecOpsApi.runStage('Nexus delivery (Static analysis passed)') {
        devSecOpsApi.eachProject { String projectName ->
            devSecOpsApi.reportArtifactBuild()
            devSecOpsApi.reportUnitTests()
            if (devSecOpsApi.deployTarget() == 'openshift') {
                devSecOpsApi.buildDockerImage(projectName)
                devSecOpsApi.copyImageToNexus()
            } else {
                devSecOpsApi.pushToNexus(projectName)
            }
        }
    }
}

def lowerRegionDeployment(String vmMode = 'ssh') {
    devSecOpsApi.runStage('Lower test region deployment') {
        devSecOpsApi.eachProject {
            if (devSecOpsApi.deployTarget() == 'openshift') {
                devSecOpsApi.checkDeploymentRepo()
                devSecOpsApi.deployOpenshift('rd')
            } else if (vmMode == 'dod') {
                devSecOpsApi.deployDvWithDodPlugin()
            } else {
                devSecOpsApi.deployRD()
            }
        }
    }
}

def regressionTests() {
    devSecOpsApi.runStage('Regression tests (>60% user stories coverage)') {
        devSecOpsApi.eachProject {
            devSecOpsApi.regressionTests()
        }
    }
}

def smokeTests() {
    devSecOpsApi.runStage('Smoke tests') {
        devSecOpsApi.eachProject {
            devSecOpsApi.smokeTests()
        }
    }
}

def performanceTests() {
    devSecOpsApi.runStage('Performance tests') {
        devSecOpsApi.eachProject {
            devSecOpsApi.performanceTests()
        }
    }
}

def dast(Map options = [:]) {
    devSecOpsApi.runStage('DAST - Dynamic Application Security Tests - HCL AppScan') {
        if (options.setupTools) {
            devSecOpsApi.appscanSetup()
        }
        devSecOpsApi.eachProject {
            devSecOpsApi.dastScan()
        }
    }
}

def nexusReleaseDelivery() {
    devSecOpsApi.runStage('Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities') {
        devSecOpsApi.eachProject {
            devSecOpsApi.publishArtifactQC()
        }
    }
}

def higherEnvironmentDeployment() {
    devSecOpsApi.runStage('Higher test environment deployment') {
        devSecOpsApi.eachProject {
            if (devSecOpsApi.deployTarget() == 'openshift') {
                devSecOpsApi.deployOpenshift('qc')
            } else {
                devSecOpsApi.deployQC()
            }
        }
    }
}
