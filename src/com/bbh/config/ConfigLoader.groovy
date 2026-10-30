package com.bbh.config

import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class ConfigLoader implements Serializable {

    private final def                    script
    private final PipelineState          state
    private final PortalConfigReader     reader
    private final RepositoryConfigReader repositoryReader
    private String                       variant = ''
    private Map                          repository = [:]

    ConfigLoader(def script, PipelineState state) {
        this.script           = script
        this.state            = state
        this.reader           = new PortalConfigReader(script)
        this.repositoryReader = new RepositoryConfigReader(script)
    }

    Map load(String variant, Map jenkinsfile) {
        this.variant = variant
        repository = [:]
        state.configFile = ''
        state.portalDocuments = []
        state.platform = [:]
        Map entryPoints = [full: 'devSecOpsPipeline', security: 'devSecOpsSecurityPipeline', extended: 'devSecOpsExtendedPipeline',
                           sast: 'devSecOpsSASTScanningPipeline', nexusiq: 'devSecOpsNexusIqGoldenFixPipeline']
        def given = jenkinsfile.pipelineKeys ?: jenkinsfile.pipelineKey
        List keys = given instanceof List ? (given as List) : (given ? [given] : [])
        if (!keys && !jenkinsfile.containsKey('pipelineKey') && !jenkinsfile.containsKey('pipelineKeys') && !keyRequired()) {
            return loadRepository(jenkinsfile, (entryPoints[variant] ?: 'devSecOpsPipeline') as String)
        }
        if (!keys) {
            script.error "[PORTAL] No pipeline key: the DevSecOps portal issues one per service on the product page. " +
                    "Write ${entryPoints[variant] ?: 'devSecOpsPipeline'}(pipelineKey: '<key from the DevSecOps portal>') in the Jenkinsfile, " +
                    "or call devSecOpsApi.configure('${variant}', [pipelineKey: '<key from the DevSecOps portal>']) in a custom Jenkinsfile"
        }
        List ignored = []
        for (String name : ['projectNames', 'agentNames', 'securityPipeline']) {
            if (jenkinsfile.containsKey(name)) ignored << name
        }
        if (ignored) script.echo "[PORTAL] The Jenkinsfile sets ${ignored.join(', ')}; the values from the DevSecOps portal are used instead"

        List<Map> documents = reader.read(keys)
        Map first    = documents[0]
        Map pipeline = (((first.config as Map).pipeline) ?: [:]) as Map
        Map services = [:]
        for (Map document : documents) {
            Map config = document.config as Map
            Map own    = (config.pipeline ?: [:]) as Map
            if (own.type != variant) {
                script.error "[PORTAL] Key ${document.hint} configures a ${own.type} pipeline, ${entryPoints[variant] ?: variant} runs the ${variant} pipeline: use the key of the ${variant} pipeline of the service"
            }
            if (own.product != pipeline.product) {
                script.error "[PORTAL] Key ${document.hint} belongs to product ${own.product}, key ${first.hint} to product ${pipeline.product}: the keys of one run must belong to one product"
            }
            List names = ((config.projects ?: [:]) as Map).keySet().toList()
            for (def name : names) {
                if (services.containsKey(name)) script.error "[PORTAL] Keys ${services[name]} and ${document.hint} both configure service ${name}: give each service once"
                services[name] = document.hint
            }
        }
        state.portalDocuments = documents
        state.platform = (((first.config as Map).platform) ?: [:]) as Map
        script.env.PROJECT_NAMES = services.keySet().join(',')
        if (pipeline.securityPipeline) script.env.Security_Pipeline = pipeline.securityPipeline as String
        return pipeline
    }

    private boolean keyRequired() {
        return ((script.env.DSO_REQUIRE_PIPELINE_KEY ?: '') as String).trim().toLowerCase() == 'true'
    }

    private Map loadRepository(Map jenkinsfile, String call) {
        if (!jenkinsfile.agentNames) {
            String security = variant == 'extended' ? ", securityPipeline: '<security job>'" : ''
            script.error "[PORTAL] No pipeline key and no agentNames: the DevSecOps portal issues one key per service on the product page. " +
                    "Write ${call}(pipelineKey: '<key from the DevSecOps portal>') in the Jenkinsfile, " +
                    "or call devSecOpsApi.configure('${variant}', [pipelineKey: '<key from the DevSecOps portal>']) in a custom Jenkinsfile. " +
                    "Without a key, write ${call}(projectNames: '<services of this repository>', agentNames: ['<agent label>']${security}) " +
                    "and commit ${repositoryReader.names()} at the root of the repository"
        }
        def names = jenkinsfile.projectNames
        if (names) script.env.PROJECT_NAMES = (names instanceof List ? (names as List).join(',') : names) as String
        if (jenkinsfile.securityPipeline) script.env.Security_Pipeline = jenkinsfile.securityPipeline as String
        repository.securityPipeline = variant == 'extended' ? (jenkinsfile.securityPipeline ?: '') as String : ''
        state.configFile = repositoryReader.names()
        return [agentNames: jenkinsfile.agentNames, securityPipeline: jenkinsfile.securityPipeline]
    }

    private Map repositoryConfig() {
        Map read = repositoryReader.read(variant)
        state.configFile = read.file as String
        repository.projects = read.projects
        return [defaults: read.defaults, pipeline: [securityPipeline: (repository.securityPipeline ?: '') as String]]
    }

    private List<String> requestedProjectNames() {
        def raw = (script.env.PROJECT_NAMES ?: script.env.PROJECT_NAME ?: '').trim()
        return raw ? raw.split(',').collect { it.trim() }.findAll { it } : []
    }

    void initialize() {
        if (!state.portalDocuments && !state.configFile) {
            if (keyRequired()) script.error "[INIT] No configuration from the DevSecOps portal is loaded: call devSecOpsApi.configure('<variant>', [pipelineKey: '<key from the DevSecOps portal>']) before devSecOpsApi.initialize()"
            state.configFile = repositoryReader.names()
        }
        readCommitMetadata()

        Map primary = state.configFile ? repositoryConfig() : (state.portalDocuments[0] as Map).config as Map
        state.cfgDefaults = (primary.defaults ?: [:]) as Map
        if (!state.cfgDefaults && !state.configFile) {
            script.error "[INIT] The DevSecOps portal sent no global defaults for key ${(state.portalDocuments[0] as Map).hint}: ask the DevSecOps team to save the Global Settings in the portal"
        }
        Map projects = loadedProjects()
        String securityJob = ((primary.pipeline as Map)?.securityPipeline ?: '') as String
        Map own = state.configFile ? subset(projects, requestedProjectNames()) : projects
        if (securityJob) overlayRunState(own, securityJob)
        script.writeYaml(file: BuildUtils.runStateFile(), data: [projects: own], overwrite: true)

        def projectNames = resolveProjectNames()
        if (!projectNames) {
            script.echo "[INIT] No PROJECT_NAMES defined - using defaults."
            state.cfg = [:]
        } else {
            for (int i = 0; i < projectNames.size(); i++) {
                def pName = projectNames[i]
                if (!projects.containsKey(pName)) {
                    script.error(state.configFile ? "[INIT] Project '${pName}' not found in ${state.configFile}. Available: ${projects.keySet().join(', ')}"
                            : "[INIT] Project '${pName}' is not configured by the pipeline keys of this run. Available: ${projects.keySet().join(', ')}")
                }
                state.projectsAllCfg[pName] = deepMerge(state.cfgDefaults, projects[pName] as Map)
                appScanKeyId(pName)
            }
            def primaryName = projectNames[0]
            state.cfg = state.projectsAllCfg[primaryName]
            state.currentProjectName = primaryName
            script.env.CURRENT_PROJECT_NAME = primaryName
            script.echo "[INIT] Projects: ${projectNames.join(', ')} | primary: ${primaryName} | buildTool: ${state.cfg.buildTool ?: 'gradle'}"
            script.env.APPSCAN_SCAN_NAME = BuildUtils.scanName(state.cfg.tools?.sonar?.projectName ?: primaryName)
            script.echo "[INIT] APPSCAN_SCAN_NAME=${script.env.APPSCAN_SCAN_NAME}"
        }

        script.env.APPSCAN_KEY_ID = appScanKeyId(state.currentProjectName)
        List unscanned = variant == 'nexusiq' ? withoutNexusIqScan(projectNames as List) : []
        if (unscanned) script.error "[INIT] The Nexus IQ GoldenFix pipeline would scan nothing for ${unscanned.join(', ')}: set tools.nexusIq.application and its scanPatterns for the service in ${state.settingsPlace()}"

        applyPolicy()
        script.echo "[INIT] OS: ${script.env.OS_TYPE ?: 'linux'}"
    }

    private void overlayRunState(Map projects, String job) {
        String file = BuildUtils.runStateFile()
        if (!script.fileExists(file)) {
            script.error "[INIT] The last successful build of ${job} has no ${file}: it predates the DevSecOps portal integration; run ${job} once"
        }
        def copied = script.readYaml(file: file)
        String problem = overlay(projects, ((copied instanceof Map ? copied.projects : null) ?: [:]) as Map, job)
        if (problem) script.error "[INIT] ${problem}"
    }

    @NonCPS
    private String overlay(Map projects, Map copied, String job) {
        List paths = [['deploy', 'openshift', 'rd', 'buildTag'], ['deploy', 'openshift', 'rd', 'internalDockerUrl'],
                      ['delivery'], ['delivery', 'buildTagAndroid'], ['delivery', 'buildTagIOS']]
        for (def name : projects.keySet()) {
            if (!(copied[name] instanceof Map)) {
                return "Security pipeline ${job} builds ${copied.keySet().join(', ')}, ${state.configFile ? 'this run' : 'this key'} builds ${name}".toString()
            }
            for (def p : paths) {
                List path = p as List
                def value = copied[name]
                for (def step : path) value = value instanceof Map ? (value as Map)[step] : null
                if (!(value instanceof String)) continue
                if (!((value as String) ==~ /[A-Za-z0-9._:\/@+-]{1,512}/)) {
                    return "${BuildUtils.runStateFile()} of ${job} holds an invalid ${path.join('.')} for ${name}: a run-time tag has 1 to 512 letters, digits and . _ : / @ + -".toString()
                }
                Map target = projects[name] as Map
                for (int i = 0; i < path.size() - 1; i++) {
                    if (!(target[path[i]] instanceof Map)) target[path[i]] = [:]
                    target = target[path[i]] as Map
                }
                target[path[path.size() - 1]] = value
            }
        }
        return ''
    }

    @NonCPS
    private List withoutNexusIqScan(List names) {
        return names.findAll { name ->
            Map niq = (state.projectsAllCfg[name]?.tools?.nexusIq ?: [:]) as Map
            def app = niq.application
            Map apps = app instanceof Map ? app as Map : (app ? [(app): niq] : [:])
            !apps || apps.values().any { !(it instanceof Map) || !(it as Map).scanPatterns }
        }
    }

    @NonCPS
    private Map subset(Map projects, List names) {
        return names ? projects.findAll { k, v -> names.contains(k) } : projects
    }

    @NonCPS
    private Map loadedProjects() {
        Map projects = [:]
        projects.putAll((repository.projects ?: [:]) as Map)
        for (def document : state.portalDocuments) {
            projects.putAll((((document as Map).config as Map).projects ?: [:]) as Map)
        }
        return projects
    }

    void readCommitMetadata() {
        state.commitTime = System.currentTimeMillis()
        try {
            String raw = script.sh(returnStdout: true, label: 'Read the commit under test',
                    script: "git log -1 --format='%ct|%H|%ae' 2>/dev/null || true").trim()
            List parts = raw ? (raw.split(/\|/) as List) : []
            if (parts.size() >= 3 && ((parts[0] as String) ==~ /\d+/)) {
                state.commitTime   = ((parts[0] as String) as long) * 1000L
                state.commitSha    = (parts[1] as String)
                state.commitAuthor = (parts[2] as String)
                script.echo "[INIT] Commit under test: ${state.commitSha.take(8)} by ${state.commitAuthor}"
            } else {
                script.echo '[INIT] Commit metadata unavailable - the lead time is measured from the start of the build'
            }
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.echo "[INIT] Commit metadata could not be read (${e.message}) - the lead time is measured from the start of the build"
        }
    }

    List<String> resolveProjectNames() {
        List<String> requested = requestedProjectNames()
        if (requested) return requested
        List keys = loadedProjects().keySet().toList()
        if (keys) script.echo(state.configFile ? "[INIT] PROJECT_NAMES not set - auto-detected from ${state.configFile}: ${keys.join(', ')}" : "[INIT] PROJECT_NAMES not set - taken from the pipeline keys of this run: ${keys.join(', ')}")
        return keys
    }

    void switchProject(String projectName) {
        if (!state.projectsAllCfg.containsKey(projectName)) {
            script.error "[SWITCH] Project '${projectName}' not loaded."
        }
        state.cfg = state.projectsAllCfg[projectName]
        state.currentProjectName = projectName
        script.env.CURRENT_PROJECT_NAME = projectName
        script.env.APPSCAN_SCAN_NAME = BuildUtils.scanName(state.cfg.tools?.sonar?.projectName ?: projectName)
        script.env.APPSCAN_KEY_ID = appScanKeyId(projectName)
    }

    private String appScanKeyId(String projectName) {
        String keyId = ((state.projectsAllCfg[projectName]?.asoc?.keyId ?: '') as String).trim()
        if (!keyId) script.error "[INIT] asoc.keyId must be set for the service ${projectName} in ${state.settingsPlace()}"
        return keyId
    }

    void applyPolicy() {
        state.policyLimits = [
                sast: limits(state.cfgDefaults.sast),
                sca : limits(state.cfgDefaults.sca),
                dast: limits(state.cfgDefaults.dast),
                niq : limits(state.cfgDefaults.tools?.nexusIq)
        ]
        state.coverage.minRequired = (state.cfgDefaults.coverage?.minLine ?: 60) as int
        logPolicy()
    }

    private void logPolicy() {
        script.echo(state.configFile ? "[POLICY] Library security policy (resources/${BuildUtils.defaultsResource()}), projects cannot change it:" : '[POLICY] Global settings from the DevSecOps portal, projects cannot change them:')
        ['sast', 'sca', 'niq', 'dast'].each { key ->
            def limit = state.policyLimits[key] ?: [:]
            script.echo "[POLICY]   ${key.toUpperCase()}: critical<=${limit.maxCritical} high<=${limit.maxHigh} medium<=${limit.maxMedium}"
        }
        script.echo "[POLICY]   Coverage: line coverage >= ${state.coverage.minRequired}%"
        script.echo "[POLICY] A violation marks the stage unstable, the build continues, the Nexus release and the QC deployment stay blocked."
    }

    private Map limits(def cfg) {
        return [
                maxCritical: (cfg?.maxCritical ?: 0) as int,
                maxHigh    : (cfg?.maxHigh ?: 0) as int,
                maxMedium  : (cfg?.maxMedium ?: 0) as int
        ]
    }

    private Map deepMerge(Map base, Map override) {
        def result = [:]
        result.putAll(base)
        override?.each { k, v ->
            result[k] = (v instanceof Map && result[k] instanceof Map)
                    ? deepMerge(result[k] as Map, v as Map) : v
        }
        return result
    }
}
