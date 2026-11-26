package com.bbh.fixture

class DecorationStepsApi {

    def service
    def state
    Map<String, Map> dastCounts = [:]
    List<String> calls = []

    def runStage(String name, Closure body) {
        calls << "runStage ${name}".toString()
        body.call()
    }

    def eachProject(Closure body) {
        for (def name : state.projectsAllCfg.keySet().toList()) {
            state.currentProjectName = name
            state.cfg = state.projectsAllCfg[name]
            body.call(name)
        }
    }

    def publishStaticSecurityPullRequest() {
        calls << "publishStaticSecurityPullRequest ${state.currentProjectName}".toString()
        service.publishStaticSecurityPullRequest()
    }

    def dastScan() {
        String name = state.currentProjectName as String
        calls << "dastScan ${name}".toString()
        if (!dastCounts.containsKey(name)) return
        state.projectsScanResults[name] = ((state.projectsScanResults[name] ?: [:]) as Map) + [dast: 'FAIL', dast_file: "appscan-dast-report-${name}.html".toString()]
        state.projectsVulnCounts[name] = [dast: dastCounts[name]]
    }

    def publishDastPullRequest() {
        calls << "publishDastPullRequest ${state.currentProjectName}".toString()
        service.publishDastPullRequest()
    }
}
