import groovy.transform.Field
import com.bbh.build.BuildService
import com.bbh.build.DatabaseBuild
import com.bbh.build.ManifestBuildVerifier
import com.bbh.build.NodeBuildRunner
import com.bbh.config.ConfigLoader
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.core.ReleaseGate
import com.bbh.deploy.DatabaseDeployService
import com.bbh.deploy.NexusDeliveryService
import com.bbh.deploy.OpenshiftService
import com.bbh.deploy.VmDeployService
import com.bbh.metrics.InfluxDbService
import com.bbh.metrics.PipelineMetrics
import com.bbh.remediation.GoldenFixService
import com.bbh.remediation.SecurityPullRequestService
import com.bbh.remediation.updater.GradleUpdater
import com.bbh.remediation.updater.MavenPomUpdater
import com.bbh.remediation.updater.NpmPackageJsonUpdater
import com.bbh.remediation.updater.NugetUpdater
import com.bbh.remediation.updater.PipUpdater
import com.bbh.remediation.updater.PubUpdater
import com.bbh.scanner.NexusIqGoldenFixSource
import com.bbh.scm.BitbucketPullRequestPublisher
import com.bbh.scm.GitSourceRepository
import com.bbh.report.HtmlReportService
import com.bbh.report.PortalReportService
import com.bbh.scanner.AppScanService
import com.bbh.scanner.NexusIqService
import com.bbh.scanner.SonarService
import com.bbh.utils.BuildUtils

@Field PipelineState       _state
@Field OsHelper            _os
@Field PolicyEngine        _policy
@Field ConfigLoader        _config
@Field BuildService        _build
@Field AppScanService      _appScan
@Field SonarService        _sonar
@Field NexusIqService      _nexusIq
@Field VmDeployService     _vmDeploy
@Field NexusDeliveryService _delivery
@Field OpenshiftService    _openshift
@Field InfluxDbService     _influx
@Field PortalReportService _portalReport
@Field DatabaseBuild       _database
@Field DatabaseDeployService _databaseDeploy
@Field SecurityPullRequestService _securityPr
@Field Map                 _pipelineConfig = [:]
@Field String              _variant = 'full'
@Field Boolean             _parallelScans = null
@Field Boolean             _parallelTests = null
@Field Map                 _main = [:]
@Field Map                 _branches = [:]
@Field Map                 _branchEnv = [:]
@Field List                _unitTestsDone = []
@Field List                _branchesEnded = []
@Field String              _sastWorkspace = ''

private void _setup() {
    if (_state != null) return
    _state     = new PipelineState()
    _os        = new OsHelper(this)
    _config    = new ConfigLoader(this, _state)
    _main      = _kitFor(_state)
    _policy    = _main.policy as PolicyEngine
    _build     = _main.build as BuildService
    _appScan   = _main.appScan as AppScanService
    _sonar     = _main.sonar as SonarService
    _securityPr = _main.securityPr as SecurityPullRequestService
    _nexusIq   = _main.nexusIq as NexusIqService
    _vmDeploy  = new VmDeployService(this, _state, _os)
    _delivery  = new NexusDeliveryService(this, _state, _os)
    _openshift = new OpenshiftService(this, _state)
    _influx    = new InfluxDbService(this, _state, _os)
    _portalReport = new PortalReportService(this, _state, _os)
    _database       = _main.database as DatabaseBuild
    _databaseDeploy = _main.databaseDeploy as DatabaseDeployService
}

private Map _kitFor(PipelineState state) {
    PolicyEngine policy = new PolicyEngine(this, state, _os)
    BuildService build = new BuildService(this, state, _os, policy)
    BitbucketPullRequestPublisher publisher = new BitbucketPullRequestPublisher(this)
    GitSourceRepository gitRepo = new GitSourceRepository(this, state)
    NexusIqService nexusIq = new NexusIqService(this, state, policy, new GoldenFixService(this, state,
            new NexusIqGoldenFixSource(this), gitRepo, publisher,
            [new MavenPomUpdater(), new GradleUpdater(), new NpmPackageJsonUpdater(), new PipUpdater(), new PubUpdater(), new NugetUpdater()],
            new ManifestBuildVerifier(this, new NodeBuildRunner(this, _os))))
    DatabaseBuild database = new DatabaseBuild(this, state, _os, policy, build)
    return [state     : state,
            policy    : policy,
            build     : build,
            database  : database,
            databaseDeploy: new DatabaseDeployService(this, state, policy, database),
            appScan   : new AppScanService(this, state, _os, policy, build),
            sonar     : new SonarService(this, state, _os, build),
            securityPr: new SecurityPullRequestService(this, state, publisher, gitRepo),
            nexusIq   : nexusIq]
}

private Map _kit() {
    if (!_branches) return _main
    String branch = (env.DSO_PARALLEL_BRANCH ?: '') as String
    Map kit = branch ? (_branches[branch] as Map) : null
    return kit ?: _main
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

def settingsPlace() { _setup(); return _state.settingsPlace() }

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
    _database.validateServices(_variant)
}

def setupJavaVersion(String javaHome = '/usr/lib/jvm/java-17-openjdk-17.0.19.0.10-2.el9.x86_64') {
    env.JAVA_HOME = javaHome
    env.PATH = "${env.JAVA_HOME}/bin:${env.PATH}"
}

def runStage(String name, Closure body) {
    _setup()
    Map kit = _kit()
    PipelineState state = kit.state as PipelineState
    PolicyEngine policy = kit.policy as PolicyEngine
    state.stageStart(name)
    try {
        body()
        policy.finishStage(name)
    } catch (Throwable t) {
        policy.failStage(name)
        throw t
    } finally {
        state.stageDone(name)
    }
}

def eachProject(Closure body) {
    _setup()
    def projects = _config.resolveProjectNames()
    Map kit = _kit()
    if (!kit.branch) {
        for (pName in projects) {
            _config.switchProject(pName)
            body(pName)
        }
        return
    }
    for (pName in projects) {
        List vars = _config.switchProject(pName as String, kit.state as PipelineState) + _branchToolEnv((kit.state as PipelineState).cfg)
        withEnv(vars) {
            body(pName)
        }
    }
}

private List _branchToolEnv(Map cfg) {
    String tool = ((cfg?.buildTool ?: '') as String).toLowerCase()
    String javaPath = ((cfg?.javaPath ?: '') as String).trim()
    String javaHome = (_branchEnv.JAVA_HOME ?: '') as String
    String path = (_branchEnv.PATH ?: '') as String
    if (javaPath && (tool in ['liquibase', 'npm', 'node', 'pip', 'ios'] || BuildUtils.isDotnetTool(tool))) {
        javaHome = javaPath
        path = "${javaPath}/bin:${path}"
    }
    List vars = ["PATH=${path}".toString()]
    if (javaHome) vars << "JAVA_HOME=${javaHome}".toString()
    return vars
}

def runInParallel(String group, List names, Map bodies, List scanners = []) {
    _setup()
    if (_branches) error "[PARALLEL] ${group} cannot start inside another group of parallel stages"
    _branchEnv = [JAVA_HOME: (env.JAVA_HOME ?: '') as String, PATH: (env.PATH ?: '') as String]
    _unitTestsDone = []
    _branchesEnded = []
    Map timings = [:]
    Map branches = [failFast: false]
    for (int i = 0; i < names.size(); i++) {
        String name = names[i] as String
        Closure body = bodies[name] as Closure
        Map kit = _kitFor(_state.fork())
        (kit.appScan as AppScanService).shareSession(_appScan)
        kit.waitMs = 0L
        kit.branch = name
        _branches[name] = kit
        branches[name] = {
            long start = System.currentTimeMillis()
            try {
                withEnv(["DSO_PARALLEL_BRANCH=${name}"]) {
                    body()
                }
            } finally {
                timings[name] = [stage: name, ms: System.currentTimeMillis() - start, waitMs: (kit.waitMs ?: 0L) as long]
                _branchesEnded << name
            }
        }
    }
    echo "[PARALLEL] ${group}: ${names.join(', ')} run at the same time"
    long started = System.currentTimeMillis()
    try {
        parallel(branches)
    } finally {
        long ended = System.currentTimeMillis()
        for (int i = 0; i < names.size(); i++) {
            Map kit = _branches[names[i] as String] as Map
            _state.absorb(kit.state as PipelineState)
            _appScan.shareSession(kit.appScan as AppScanService)
        }
        _branches = [:]
        _state.settle(names, scanners)
        List measured = []
        for (int i = 0; i < names.size(); i++) {
            if (timings[names[i] as String]) measured << timings[names[i] as String]
        }
        _state.recordParallelGroup(group, started, ended, measured)
        Map recorded = _state.parallelGroups[-1] as Map
        echo "[PARALLEL] ${group}: one after another ${BuildUtils.formatDuration(recorded.sequentialMs as long)}, in parallel ${BuildUtils.formatDuration(recorded.actualMs as long)}${BuildUtils.parallelSaving(recorded.sequentialMs as long, recorded.actualMs as long)}"
    }
}

def unitTestsFinished() {
    _setup()
    if (_branches) _unitTestsDone << (_kit().state as PipelineState).currentProjectName
}

def awaitUnitTests(String unitBranch) {
    _setup()
    if (!_branches) return
    Map kit = _kit()
    String name = (kit.state as PipelineState).currentProjectName
    List peers = BuildUtils.sameSourceProjects(_state.projectsAllCfg, name)
    if (_unitTestsReady(unitBranch, peers)) return
    echo "[STATIC] ${name}: SonarQube waits until the unit tests of ${peers.join(', ')} have written their coverage"
    long start = System.currentTimeMillis()
    waitUntil(quiet: true) { _unitTestsReady(unitBranch, peers) }
    long waited = System.currentTimeMillis() - start
    kit.waitMs = ((kit.waitMs ?: 0L) as long) + waited
    echo "[STATIC] ${name}: SonarQube starts after waiting ${BuildUtils.formatDuration(waited)} for the unit tests"
}

private boolean _unitTestsReady(String unitBranch, List peers) {
    if (_branchesEnded.contains(unitBranch)) return true
    for (int i = 0; i < peers.size(); i++) {
        if (!_unitTestsDone.contains(peers[i])) return false
    }
    return true
}

def prepareSastWorkspace() {
    _setup()
    _sastWorkspace = ''
    String base = (env.WORKSPACE_TMP ?: "${env.WORKSPACE}@tmp") as String
    String copy = "${base}/dso-sast-workspace".toString()
    try {
        withEnv(["DSO_SAST_WORKSPACE=${copy}"]) {
            sh(label: 'Copy the workspace for the SAST compile and scan', script: '''#!/usr/bin/env bash
set -euo pipefail
rm -rf "$DSO_SAST_WORKSPACE"
mkdir -p "$DSO_SAST_WORKSPACE"
tar -C "$WORKSPACE" --exclude=./.appscan-tools --exclude=./.appscan-logs --exclude=./.appscan-home --exclude=./.appscan-bin --exclude=./appscan-source-staged -cf - . | tar -C "$DSO_SAST_WORKSPACE" -xf -
''')
        }
        _sastWorkspace = copy
        echo "[STATIC] SAST compiles and prepares its scans in a copy of the workspace (${copy}), so Unit tests, Nexus IQ and SonarQube run at the same time on the workspace"
        return true
    } catch (Throwable t) {
        if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
        echo "[WARN] [STATIC] The workspace could not be copied for SAST (${t.message ?: t.getClass().getSimpleName()}) - falling back: SAST queues its scans first and Unit tests, Nexus IQ and SonarQube run one after another while AppScan scans"
        return false
    }
}

def inSastWorkspace(Closure body) {
    _setup()
    String copy = _sastWorkspace
    if (!copy || !_branches) {
        body()
        return
    }
    try {
        dir(copy) {
            withEnv(["WORKSPACE=${copy}"]) {
                body()
            }
        }
    } finally {
        _sastWorkspace = ''
        withEnv(["DSO_SAST_WORKSPACE=${copy}"]) {
            sh(label: 'Remove the SAST copy of the workspace', script: 'rm -rf "$DSO_SAST_WORKSPACE"')
        }
    }
}

def checkoutAdditionalRepositories() {
    _setup()
    def projectNames = _config.resolveProjectNames()
    if (!projectNames || projectNames.size() < 2) return
    String primaryRepo = ''
    for (def name : projectNames) {
        Map cfg = (_state.projectsAllCfg[name] ?: [:]) as Map
        String dir = (cfg.sourceDir ?: '') as String
        if (dir == '.' || dir == '' || !primaryRepo) {
            String repo = BuildUtils.normalizeRepositoryUrl(cfg.scm)
            if (repo) {
                primaryRepo = repo
                if (dir == '.' || dir == '') break
            }
        }
    }
    for (int i = 0; i < projectNames.size(); i++) {
        def pName = projectNames[i]
        Map pCfg = (_state.projectsAllCfg[pName] ?: [:]) as Map
        String serviceRepo = BuildUtils.normalizeRepositoryUrl(pCfg?.scm)
        String targetDir = (pCfg.sourceDir ?: pName) as String
        if (targetDir != '.' && serviceRepo && primaryRepo && !serviceRepo.equalsIgnoreCase(primaryRepo)) {
            String rawUrl = ((pCfg.scm instanceof Map ? (pCfg.scm.bitbucket?.url ?: pCfg.scm.url) : pCfg.scm) ?: '') as String
            String credId = (pCfg.scm instanceof Map ? (pCfg.scm.bitbucket?.credentialsId ?: pCfg.scm.credentialsId) : '') as String
            String branch = (pCfg.scm instanceof Map ? (pCfg.scm.bitbucket?.targetBranch ?: pCfg.scm.targetBranch ?: pCfg.scm.branch) : '') as String
            echo "[SCM] Checking out repository '${rawUrl}' for service '${pName}' into '${targetDir}'"
            dir(targetDir) {
                if (credId) {
                    git url: rawUrl, branch: branch ?: 'master', credentialsId: credId
                } else {
                    git url: rawUrl, branch: branch ?: 'master'
                }
            }
            _config.refreshAutoResolvedConfig(pName, targetDir)
        }
    }
}

def parallelScans() {
    _setup()
    if (_parallelScans == null) {
        boolean wanted = _parallelSwitch('staticAnalysis', env.DSO_STATIC_ANALYSIS_PARALLEL, 'DSO_STATIC_ANALYSIS_PARALLEL',
                'Unit tests, Nexus IQ, SAST and SonarQube run at the same time', 'Unit tests, Nexus IQ, SAST and SonarQube run one after another')
        if (wanted && _os.isWindows()) {
            echo '[STATIC] Windows agent: Unit tests, Nexus IQ, SAST and SonarQube run one after another, as the SAST staging of Windows uses the workspace'
            wanted = false
        }
        _parallelScans = wanted
    }
    return _parallelScans
}

def parallelTests() {
    _setup()
    if (_parallelTests == null) {
        _parallelTests = _parallelSwitch('dynamicAnalysis', env.DSO_DYNAMIC_ANALYSIS_PARALLEL, 'DSO_DYNAMIC_ANALYSIS_PARALLEL',
                'Regression, Smoke and Performance tests and DAST run at the same time', 'Regression, Smoke and Performance tests and DAST run one after another')
    }
    return _parallelTests
}

private boolean _parallelSwitch(String block, def forced, String envName, String parallelText, String sequentialText) {
    Map decision = BuildUtils.parallelSwitch(_state.projectsAllCfg, _state.cfgDefaults, block, forced, envName)
    String tag = block == 'staticAnalysis' ? '[STATIC]' : '[DYNAMIC]'
    for (def warning : (decision.warnings as List)) echo "[WARN] ${tag} ${warning}"
    for (def note : (decision.notes as List)) {
        if (_state.configFile || !(note as String).startsWith('defaults.')) echo "[INFO] ${tag} ${note}"
    }
    if (!_state.projectsAllCfg) return false
    if (decision.enabled) {
        echo "${tag} ${parallelText}${decision.source == envName ? " (${envName}=true)" : " (${decision.key}: true, the default)"}"
    } else if (decision.source == envName) {
        echo "${tag} ${envName}=false: ${sequentialText}"
    } else if (decision.source == 'defaults') {
        echo "${tag} ${_defaultsOrigin()}${decision.key}: false - ${sequentialText}"
    } else {
        echo "${tag} ${(decision.optedOut as List).join(', ')}: ${decision.key}: false - ${sequentialText}"
    }
    return decision.enabled as boolean
}

private String _defaultsOrigin() {
    return _state.configFile ? 'defaults.' : 'The Global Settings set '
}

def sastCompileOverrides() {
    _setup()
    return BuildUtils.sastCompileOverrides(_state.projectsAllCfg)
}

def scanWhileSastRuns(Closure queue, Closure scans, Closure results) {
    _setup()
    String sast = PolicyEngine.stageNameFor('sast')
    echo '[STATIC] SAST queues the scan of every service first; Unit tests, Nexus IQ and SonarQube then run one after another while AppScan scans, and SAST reads the results last'
    String part = 'queue'
    _state.stageStart(sast)
    try {
        queue()
        _appScan.removeStagedSources()
        part = 'scans'
        scans()
        part = 'results'
        results()
        _policy.finishStage(sast)
    } catch (Throwable t) {
        if (part == 'scans') {
            _state.stageTimes.remove(sast)
        } else {
            _policy.failStage(sast)
        }
        if (part == 'queue') echo '[STATIC] SAST stopped while it prepared its scans: Unit tests and Nexus IQ do not run in this build'
        throw t
    } finally {
        _state.stageDone(sast)
        _state.settle([PolicyEngine.stageNameFor('unit'), PolicyEngine.stageNameFor('niq'), sast, PolicyEngine.stageNameFor('sca')], [['coverage', 'unit'], ['niq'], ['sast'], ['sonar', 'sca']])
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
    try { sendPortalReport(type) }
    catch (Throwable t) { echo "[WARN] Could not upload report to portal: ${t.message}" }
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
    if (_variant == 'nexusiq') {
        Map niq = PipelineMetrics.nexusIqTotals(_state)
        return "Vulnerabilities above the policy severity: Nexus IQ critical=${niq.critical ?: 0}, high=${niq.high ?: 0}, medium=${niq.medium ?: 0}"
    }
    def sast = _state.vulnCounts.sast ?: [critical: 0, high: 0, medium: 0]
    def dast = _state.vulnCounts.dast ?: [critical: 0, high: 0, medium: 0]
    int sastTotal = ((sast.critical ?: 0) as int) + ((sast.high ?: 0) as int) + ((sast.medium ?: 0) as int)
    int dastTotal = ((dast.critical ?: 0) as int) + ((dast.high ?: 0) as int) + ((dast.medium ?: 0) as int)
    return "Vulnerabilities above the policy severity: SAST=${sastTotal}, DAST=${dastTotal}"
}

def appscanSetup()                           { _setup(); (_kit().appScan as AppScanService).setup() }
def appscanLogin()                           { _setup(); (_kit().appScan as AppScanService).sastLogin() }
def appscanResolveSourceDir(String p = null) { _setup(); (_kit().appScan as AppScanService).resolveSourceDir(p) }
def appscanGenerateIRX(int timeoutMin = 120) { _setup(); (_kit().appScan as AppScanService).generateIrx(timeoutMin) }
def appscanQueue(Map config)                 { _setup(); (_kit().appScan as AppScanService).queueSast(config) }
def appscanWait()                            { _setup(); (_kit().appScan as AppScanService).waitSast() }
def appscanDownloadReports()                 { _setup(); (_kit().appScan as AppScanService).downloadSastReports() }
def appscanRenameSastReport()                { _setup(); (_kit().appScan as AppScanService).renameSastReport() }
def appscanRenameDastReport()                { _setup(); (_kit().appScan as AppScanService).renameDastReport() }

def appscanEnforcePolicy() {
    _setup()
    (_kit().policy as PolicyEngine).enforceScanner('sast')
    def counts = (_kit().state as PipelineState).vulnCounts.sast ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def sonarscanEnforcePolicy() {
    _setup()
    (_kit().policy as PolicyEngine).enforceScanner('sca')
    def counts = (_kit().state as PipelineState).vulnCounts.sca ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def dastScan() {
    _setup()
    (_kit().appScan as AppScanService).dastScan()
    def counts = (_kit().state as PipelineState).vulnCounts.dast ?: [critical: 0, high: 0, medium: 0, low: 0]
    return (counts.critical as int) + (counts.high as int) + (counts.medium as int)
}

def nodeBuild()           { _setup(); (_kit().state as PipelineState).stageResults['Node.js build'] = 'PASS'; (_kit().build as BuildService).nodeBuild() }
def buildArtifact()       { _setup(); (_kit().build as BuildService).buildArtifact() }
def unitTests()           { _setup(); (_kit().build as BuildService).unitTests() }
def checkCoverage()       { _setup(); (_kit().build as BuildService).checkCoverage() }
def reportArtifactBuild() { _setup(); (_kit().build as BuildService).reportArtifactBuild() }
def reportUnitTests()     { _setup(); (_kit().build as BuildService).reportUnitTests() }

def depVulnScan()         { _setup(); (_kit().nexusIq as NexusIqService).scan() }
def codeQualityScan()     { _setup(); (_kit().sonar as SonarService).scan() }

def smokeTests()       { _setup(); (_kit().build as BuildService).runTestJobs('Smoke tests', 'Smoke Tests', (_kit().state as PipelineState).cfg.tests?.smoke ?: [:]) }
def regressionTests()  { _setup(); (_kit().build as BuildService).runTestJobs('Regression tests (>60% user stories coverage)', 'Regression Tests', (_kit().state as PipelineState).cfg.tests?.regression ?: [:]) }
def performanceTests() { _setup(); (_kit().build as BuildService).runTestJobs('Performance tests', 'Performance Tests', (_kit().state as PipelineState).cfg.tests?.performance ?: [:]) }

def deployRD()                         { _setup(); _vmDeploy.deployRd() }
def deployDvWithDodPlugin()            { _setup(); _vmDeploy.deployDvWithDodPlugin() }
def deployQC()                         { _setup(); _vmDeploy.deployQc() }
def publishArtifactQC()                { _setup(); _delivery.publishRelease(_state.currentProjectName) }
def pushToNexus(String projectName)    { _setup(); _delivery.pushToNexus(projectName) }
def pullFromNexus(String projectName)  { _setup(); _delivery.pullFromNexus(projectName) }
def releaseDeliveryAvailable()         { _setup(); return _delivery.releaseAvailable() }
def bumpVersion(String vf, String ver) { _setup(); _vmDeploy.bumpVersion(vf, ver) }

def buildDockerImage(String projectName) { _setup(); _openshift.buildDockerImage(projectName) }
def copyImageToNexus()                   { _setup(); _openshift.copyImageToNexus() }
def checkDeploymentRepo()                { _setup(); _openshift.checkDeploymentRepo() }
def checkDeploymentRepos() {
    _setup()
    for (def name : _config.resolveProjectNames()) {
        _openshift.checkDeploymentRepo((_state.projectsAllCfg[name] ?: [:]) as Map)
    }
}
def deployOpenshift(String envName)      { _setup(); _openshift.deploy(envName) }
def nexusDelivery(String envName)        { _setup(); _openshift.deliverToNexus(envName) }
def runExtendedPipeline()                { _setup(); _openshift.runExtendedPipeline() }

def generateHtmlReport()                       { _setup(); reportService().generate() }
def feedInfluxDB(String pipelineType)          { _setup(); _influx.send(pipelineType) }
def sendPortalReport(String pipelineType = '') { _setup(); _portalReport.send(pipelineType) }
def publishStaticSecurityPullRequest()         { _setup(); _securityPr.publishStaticSecurityPullRequest() }
def publishDastPullRequest()                   { _setup(); (_kit().securityPr as SecurityPullRequestService).publishDastPullRequest() }

def releaseAllowed(String stageName) { _setup(); return new ReleaseGate(this, _state).allowed(stageName) }
def publishReleaseGate()             { _setup(); new ReleaseGate(this, _state).publish() }

def nodeBuildRequired() {
    _setup()
    for (def cfg : _state.projectsAllCfg.values()) {
        if (BuildUtils.nodeBuildNeeded(cfg as Map, _variant, _os.isWindows())) return true
    }
    return false
}

def getProjects()                     { _setup(); return _config.resolveProjectNames() }
def getCFG()                          { _setup(); return (_kit().state as PipelineState).cfg }
def switchProject(String projectName) { _setup(); _config.switchProject(projectName) }
def deployTarget()                    { _setup(); return ((_kit().state as PipelineState).cfg.deployTarget ?: 'vm') as String }
def nexusDeliveryEnabled()            { _setup(); return BuildUtils.nexusDeliveryEnabled((_kit().state as PipelineState).cfg) }
def nexusDeliverySetting()            { _setup(); return BuildUtils.nexusDeliverySetting((_kit().state as PipelineState).cfg) }
def databaseBlock()                   { _setup(); return BuildUtils.databaseBlock((_kit().state as PipelineState).cfg) }
def databaseBuild()                   { _setup(); (_kit().database as DatabaseBuild).build(_variant) }
def databaseTests()                   { _setup(); (_kit().database as DatabaseBuild).tests() }
def databaseDelivery(String kind)     { _setup(); (_kit().databaseDeploy as DatabaseDeployService).deliver(kind) }
def databaseDeploy(String envName)    { _setup(); (_kit().databaseDeploy as DatabaseDeployService).deploy(envName) }
def nodeBuildEnabled()                { _setup(); return BuildUtils.nodeBuildEnabled((_kit().state as PipelineState).cfg) }
def nodeBuildNeeded()                 { _setup(); return BuildUtils.nodeBuildNeeded((_kit().state as PipelineState).cfg, _variant, _os.isWindows()) }
def notRequired(String reason)        { _setup(); echo reason; (_kit().state as PipelineState).projectNotRequired() }

def stagePass(String name)                 { _setup(); (_kit().state as PipelineState).stagePass(name) }
def stageFail(String name)                 { _setup(); (_kit().state as PipelineState).stageFail(name) }
def stageWarn(String name)                 { _setup(); (_kit().state as PipelineState).stageWarn(name) }
def stageError(String name, String reason) { _setup(); (_kit().state as PipelineState).stageError(name, reason) }
def stageStart(String name)                { _setup(); (_kit().state as PipelineState).stageStart(name) }
def stageDone(String name)                 { _setup(); (_kit().state as PipelineState).stageDone(name) }
def finishStage(String name)               { _setup(); (_kit().policy as PolicyEngine).finishStage(name) }
def failStage(String name)                 { _setup(); (_kit().policy as PolicyEngine).failStage(name) }

def logStageResult(String stageName, String status) { _setup(); (_kit().policy as PolicyEngine).logStageResult(stageName, status) }
def section(String text)      { _setup(); (_kit().policy as PolicyEngine).section(text) }
def startSection(String text) { _setup(); (_kit().policy as PolicyEngine).startSection(text) }
def endSection(String text)   { _setup(); (_kit().policy as PolicyEngine).endSection(text) }
