import groovy.transform.Field
import com.bbh.build.BuildService
import com.bbh.build.ManifestBuildVerifier
import com.bbh.config.ConfigLoader
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.core.ReleaseGate
import com.bbh.deploy.OpenshiftService
import com.bbh.deploy.VmDeployService
import com.bbh.metrics.InfluxDbService
import com.bbh.remediation.GoldenFixService
import com.bbh.remediation.updater.GradleUpdater
import com.bbh.remediation.updater.MavenPomUpdater
import com.bbh.remediation.updater.NpmPackageJsonUpdater
import com.bbh.remediation.updater.PipUpdater
import com.bbh.remediation.updater.PubUpdater
import com.bbh.scanner.NexusIqGoldenFixSource
import com.bbh.scm.BitbucketPullRequestPublisher
import com.bbh.scm.GitSourceRepository
import com.bbh.report.HtmlReportService
import com.bbh.scanner.AppScanService
import com.bbh.scanner.NexusIqService
import com.bbh.scanner.SonarService

@Field PipelineState    _state
@Field OsHelper         _os
@Field PolicyEngine     _policy
@Field ConfigLoader     _config
@Field BuildService     _build
@Field AppScanService   _appScan
@Field SonarService     _sonar
@Field NexusIqService   _nexusIq
@Field VmDeployService  _vmDeploy
@Field OpenshiftService _openshift
@Field InfluxDbService  _influx
@Field Map              _pipelineConfig = [:]
@Field String           _variant = 'full'

private void _setup() {
    if (_state != null) return
    _state     = new PipelineState()
    _os        = new OsHelper(this)
    _policy    = new PolicyEngine(this, _state, _os)
    _config    = new ConfigLoader(this, _state)
    _build     = new BuildService(this, _state, _os, _policy)
    _appScan   = new AppScanService(this, _state, _os, _policy, _build)
    _sonar     = new SonarService(this, _state, _os, _build)
    _nexusIq   = new NexusIqService(this, _state, _policy, new GoldenFixService(this, _state,
            new NexusIqGoldenFixSource(this), new GitSourceRepository(this), new BitbucketPullRequestPublisher(this),
            [new MavenPomUpdater(), new GradleUpdater(), new NpmPackageJsonUpdater(), new PipUpdater(), new PubUpdater()],
            new ManifestBuildVerifier(this)))
    _vmDeploy  = new VmDeployService(this, _state, _os)
    _openshift = new OpenshiftService(this, _state)
    _influx    = new InfluxDbService(this, _state, _os)
}

def configure(String variant, Map config) {
    _setup()
    _variant = variant ?: 'full'
    Map pipeline = _config.load(_variant, config ?: [:])
    _pipelineConfig = [:]
    _pipelineConfig.putAll(config ?: [:])
    _pipelineConfig.projectNames     = env.PROJECT_NAMES
    _pipelineConfig.agentNames       = pipeline.agentNames
    _pipelineConfig.securityPipeline = pipeline.securityPipeline
}

def pipelineConfig() { _setup(); return _pipelineConfig }

private HtmlReportService reportService() {
    return new HtmlReportService(this, _state, _os, _policy, _variant)
}

private void applyToolEnvironment() {
    Map portal = (_state.platform?.environment ?: [:]) as Map
    env.SA_LINUX_URL       = env.SA_LINUX_URL       ?: (portal.SA_LINUX_URL       ?: 'https://tools.bbh.com/nexus/repository/releases/com/bbh/appscan/SAClientUtil/8.0.1646_Linux/SAClientUtil-8.0.1646_Linux-SAClientUtil_8.0.1646_Linux.zip') as String
    env.SA_WIN_URL         = env.SA_WIN_URL         ?: (portal.SA_WIN_URL         ?: 'https://tools.bbh.com/nexus/repository/releases/com/bbh/appscan/SAClientUtil/8.0.1646_Win/SAClientUtil-8.0.1646_Win-SAClientUtil_8.0.1646_Win.zip') as String
    env.PROXY_HOST         = env.PROXY_HOST         ?: (portal.PROXY_HOST         ?: 'tstproxy.bbh.com') as String
    env.PROXY_PORT         = env.PROXY_PORT         ?: (portal.PROXY_PORT         ?: '9090') as String
    env.PROXY_USER         = env.PROXY_USER         ?: (portal.PROXY_USER         ?: 'PROXY_ASOCJenk') as String
    env.APPSCAN_HOST       = env.APPSCAN_HOST       ?: (portal.APPSCAN_HOST       ?: 'bbh.cloud.appscan.com') as String
    env.APPSCAN_SERVER_URL = env.APPSCAN_SERVER_URL ?: (portal.APPSCAN_SERVER_URL ?: 'https://bbh.cloud.appscan.com') as String
    env.APPSCAN_TOOLS_DIR  = env.APPSCAN_TOOLS_DIR  ?: "${env.WORKSPACE}/.appscan-tools"
    env.APPSCAN_LOG_DIR    = env.APPSCAN_LOG_DIR    ?: "${env.WORKSPACE}/.appscan-logs"
    env.APPSCAN_HOME_DIR   = env.APPSCAN_HOME_DIR   ?: "${env.WORKSPACE}/.appscan-home"
    env.APPSCAN_BIN_DIR    = env.APPSCAN_BIN_DIR    ?: "${env.WORKSPACE}/.appscan-bin"
}

def initialize() {
    _setup()
    applyToolEnvironment()
    _os.detect()
    env.OS_TYPE = _os.getType()
    _os.chmodX('gradlew')
    _config.initialize()
}

def setupJavaVersion(String javaHome = '/usr/lib/jvm/java-17-openjdk-17.0.19.0.10-2.el9.x86_64') {
    env.JAVA_HOME = javaHome
    env.PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
}

def runStage(String name, Closure body) {
    _setup()
    _state.stageStart(name)
    try {
        body()
        _policy.finishStage(name)
    } catch (Throwable t) {
        _policy.failStage(name)
        throw t
    } finally {
        _state.stageDone(name)
    }
}

def eachProject(Closure body) {
    _setup()
    def projects = _config.resolveProjectNames()
    for (pName in projects) {
        _config.switchProject(pName)
        body(pName)
    }
}

def finishPipeline(Map options = [:]) {
    _setup()
    String type = (options.type ?: '') as String
    String artifacts = (options.artifacts ?: 'report/pipeline-report.html,appscan-report*.html,appscan-dast-report*.html,appscan-dast-report*.pdf') as String

    try { generateHtmlReport() }
    catch (Throwable t) { echo "[WARN] Could not generate HTML report: ${t.message}" }
    try { publishReleaseGate() }
    catch (Throwable t) { echo "[WARN] Could not write the release gate state: ${t.message}" }
    try { feedInfluxDB(type) }
    catch (Throwable t) { echo "[WARN] Could not feed InfluxDB metrics: ${t.message}" }

    if (!fileExists('report/pipeline-report.html')) {
        writeFile file: 'report/pipeline-report.html',
                  text: "<html><head><meta charset='UTF-8'><title>Pipeline Report</title></head><body style='font-family:sans-serif;padding:24px'><h2>Pipeline Report</h2><p>Report could not be generated - check build logs.</p></body></html>"
    }
    try { echo vulnerabilitySummary() }
    catch (Throwable t) { echo "[WARN] Could not summarise the vulnerabilities: ${t.message}" }
    section('Pipeline finished')

    archiveArtifacts(artifacts: artifacts, fingerprint: true, allowEmptyArchive: true)
    publishHTML([
            allowMissing         : true,
            alwaysLinkToLastBuild: true,
            keepAll              : true,
            reportDir            : 'report',
            reportFiles          : 'pipeline-report.html',
            reportName           : 'Pipeline Report',
            reportTitles         : ''
    ])
    cleanWs(notFailBuild: true)
}

def vulnerabilitySummary() {
    _setup()
    def sast = _state.vulnCounts.sast ?: [critical: 0, high: 0, medium: 0]
    def dast = _state.vulnCounts.dast ?: [critical: 0, high: 0, medium: 0]
    int sastTotal = ((sast.critical ?: 0) as int) + ((sast.high ?: 0) as int) + ((sast.medium ?: 0) as int)
    int dastTotal = ((dast.critical ?: 0) as int) + ((dast.high ?: 0) as int) + ((dast.medium ?: 0) as int)
    return "Vulnerabilities above the policy severity: SAST=${sastTotal}, DAST=${dastTotal}"
}

def appscanSetup()                           { _setup(); _appScan.setup() }
def appscanLogin()                           { _setup(); _appScan.cliLogin() }
def appscanResolveSourceDir(String p = null) { _setup(); _appScan.resolveSourceDir(p) }
def appscanGenerateIRX(int timeoutMin = 120) { _setup(); _appScan.generateIrx(timeoutMin) }
def appscanQueue(Map config)                 { _setup(); _appScan.queueSast(config) }
def appscanWait()                            { _setup(); _appScan.waitSast() }
def appscanDownloadReports()                 { _setup(); _appScan.downloadSastReports() }
def appscanRenameSastReport()                { _setup(); _appScan.renameSastReport() }
def appscanRenameDastReport()                { _setup(); _appScan.renameDastReport() }

def appscanEnforcePolicy() {
    _setup()
    _policy.enforceScanner('sast')
    def counts = _state.vulnCounts.sast ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def sonarscanEnforcePolicy() {
    _setup()
    _policy.enforceScanner('sca')
    def counts = _state.vulnCounts.sca ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def dastScan() {
    _setup()
    _appScan.dastScan()
    def counts = _state.vulnCounts.dast ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def buildArtifact()       { _setup(); _build.buildArtifact() }
def unitTests()           { _setup(); _build.unitTests() }
def checkCoverage()       { _setup(); _build.checkCoverage() }
def reportArtifactBuild() { _setup(); _build.reportArtifactBuild() }
def reportUnitTests()     { _setup(); _build.reportUnitTests() }

def depVulnScan()         { _setup(); _nexusIq.scan() }
def codeQualityScan()     { _setup(); _sonar.scan() }

def smokeTests()       { _setup(); _build.runTestJobs('Smoke tests', 'Smoke Tests', _state.cfg.tests?.smoke ?: [:]) }
def regressionTests()  { _setup(); _build.runTestJobs('Regression tests (>60% user stories coverage)', 'Regression Tests', _state.cfg.tests?.regression ?: [:]) }
def performanceTests() { _setup(); _build.runTestJobs('Performance tests', 'Performance Tests', _state.cfg.tests?.performance ?: [:]) }

def deployRD()                         { _setup(); _vmDeploy.deployRd() }
def deployDvWithDodPlugin()            { _setup(); _vmDeploy.deployDvWithDodPlugin() }
def deployQC()                         { _setup(); _vmDeploy.deployQc() }
def publishArtifactQC()                { _setup(); _vmDeploy.publishToNexus() }
def pushToNexus(String projectName)    { _setup(); _vmDeploy.pushToNexus(projectName) }
def bumpVersion(String vf, String ver) { _setup(); _vmDeploy.bumpVersion(vf, ver) }

def buildDockerImage(String projectName) { _setup(); _openshift.buildDockerImage(projectName) }
def copyImageToNexus()                   { _setup(); _openshift.copyImageToNexus() }
def checkDeploymentRepo()                { _setup(); _openshift.checkDeploymentRepo() }
def deployOpenshift(String envName)      { _setup(); _openshift.deploy(envName) }
def nexusDelivery(String envName)        { _setup(); _openshift.deliverToNexus(envName) }
def runExtendedPipeline()                { _setup(); _openshift.runExtendedPipeline() }

def generateHtmlReport()              { _setup(); reportService().generate() }
def feedInfluxDB(String pipelineType) { _setup(); _influx.send(pipelineType) }

def releaseAllowed(String stageName) { _setup(); return new ReleaseGate(this, _state).allowed(stageName) }
def publishReleaseGate()             { _setup(); new ReleaseGate(this, _state).publish() }

def getProjects()                     { _setup(); return _config.resolveProjectNames() }
def getCFG()                          { _setup(); return _state.cfg }
def switchProject(String projectName) { _setup(); _config.switchProject(projectName) }
def deployTarget()                    { _setup(); return (_state.cfg.deployTarget ?: 'vm') as String }

def stagePass(String name)                 { _setup(); _state.stagePass(name) }
def stageFail(String name)                 { _setup(); _state.stageFail(name) }
def stageWarn(String name)                 { _setup(); _state.stageWarn(name) }
def stageError(String name, String reason) { _setup(); _state.stageError(name, reason) }
def stageStart(String name)                { _setup(); _state.stageStart(name) }
def stageDone(String name)                 { _setup(); _state.stageDone(name) }
def finishStage(String name)               { _setup(); _policy.finishStage(name) }
def failStage(String name)                 { _setup(); _policy.failStage(name) }

def logStageResult(String stageName, String status) { _setup(); _policy.logStageResult(stageName, status) }
def section(String text)      { _setup(); _policy.section(text) }
def startSection(String text) { _setup(); _policy.startSection(text) }
def endSection(String text)   { _setup(); _policy.endSection(text) }
