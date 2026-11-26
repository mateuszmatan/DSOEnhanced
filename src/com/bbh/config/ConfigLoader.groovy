package com.bbh.config

import com.bbh.core.OneViewSettings
import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils
import com.bbh.utils.DatabaseSettings
import com.bbh.utils.RepoStackAutoResolver
import com.cloudbees.groovy.cps.NonCPS

class ConfigLoader implements Serializable {

    private final def                    script
    private final PipelineState          state
    private final OneViewConfigReader    reader
    private final RepositoryConfigReader repositoryReader
    private final RepoStackAutoResolver  autoResolver
    private String                       variant = ''
    private Map                          repository = [:]

    ConfigLoader(def script, PipelineState state) {
        this.script           = script
        this.state            = state
        this.reader           = new OneViewConfigReader(script, new OneViewSettings(script, state))
        this.repositoryReader = new RepositoryConfigReader(script)
        this.autoResolver     = new RepoStackAutoResolver(script)
    }

    Map load(String variant, Map jenkinsfile) {
        this.variant = variant
        repository = [:]
        state.configFile = ''
        state.oneViewDocuments = []
        state.oneViewKeys = []
        state.platform = [:]
        Map entryPoints = [full: 'devSecOpsPipeline', security: 'devSecOpsSecurityPipeline', extended: 'devSecOpsExtendedPipeline',
                           sast: 'devSecOpsSASTScanningPipeline', nexusiq: 'devSecOpsNexusIqGoldenFixPipeline']
        def given = jenkinsfile.pipelineKeys ?: jenkinsfile.pipelineKey
        List keys = given instanceof List ? (given as List) : (given ? [given] : [])
        if (!keys && !jenkinsfile.containsKey('pipelineKey') && !jenkinsfile.containsKey('pipelineKeys') && !keyRequired()) {
            return loadRepository(jenkinsfile, (entryPoints[variant] ?: 'devSecOpsPipeline') as String)
        }
        if (!keys) {
            script.error "[ONEVIEW] No pipeline key: OneView issues one per service on the product page. " +
                    "Write ${entryPoints[variant] ?: 'devSecOpsPipeline'}(pipelineKey: '<key from OneView>') in the Jenkinsfile, " +
                    "or call devSecOpsApi.configure('${variant}', [pipelineKey: '<key from OneView>']) in a custom Jenkinsfile"
        }
        List ignored = []
        for (String name : ['projectNames', 'agentNames', 'securityPipeline']) {
            if (jenkinsfile.containsKey(name)) ignored << name
        }
        if (ignored) script.echo "[ONEVIEW] The Jenkinsfile sets ${ignored.join(', ')}; the values from OneView are used instead"

        List<Map> documents = reader.read(keys)
        Map first    = documents[0]
        Map pipeline = (((first.config as Map).pipeline) ?: [:]) as Map
        Map services = [:]
        for (Map document : documents) {
            Map config = document.config as Map
            Map own    = (config.pipeline ?: [:]) as Map
            if (own.type != variant) {
                script.error "[ONEVIEW] Key ${document.hint} configures a ${own.type} pipeline, ${entryPoints[variant] ?: variant} runs the ${variant} pipeline: use the key of the ${variant} pipeline of the service"
            }
            if (own.product != pipeline.product) {
                script.error "[ONEVIEW] Key ${document.hint} belongs to product ${own.product}, key ${first.hint} to product ${pipeline.product}: the keys of one run must belong to one product"
            }
            List names = ((config.projects ?: [:]) as Map).keySet().toList()
            for (def name : names) {
                if (services.containsKey(name)) script.error "[ONEVIEW] Keys ${services[name]} and ${document.hint} both configure service ${name}: give each service once"
                services[name] = document.hint
            }
        }
        state.oneViewDocuments = documents
        state.oneViewKeys = reader.normalised(keys)
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
            script.error "[ONEVIEW] No pipeline key and no agentNames: OneView issues one key per service on the product page. " +
                    "Write ${call}(pipelineKey: '<key from OneView>') in the Jenkinsfile, " +
                    "or call devSecOpsApi.configure('${variant}', [pipelineKey: '<key from OneView>']) in a custom Jenkinsfile. " +
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
        if (!state.oneViewDocuments && !state.configFile) {
            if (keyRequired()) script.error "[INIT] No configuration from OneView is loaded: call devSecOpsApi.configure('<variant>', [pipelineKey: '<key from OneView>']) before devSecOpsApi.initialize()"
            state.configFile = repositoryReader.names()
        }
        readCommitMetadata()

        Map primary = state.configFile ? repositoryConfig() : (state.oneViewDocuments[0] as Map).config as Map
        state.cfgDefaults = (primary.defaults ?: [:]) as Map
        state.libraryDefaults = state.configFile ? state.cfgDefaults : repositoryReader.libraryOnlyDefaults()
        if (!state.cfgDefaults && !state.configFile) {
            script.error "[INIT] OneView sent no Library defaults for key ${(state.oneViewDocuments[0] as Map).hint}: ask the DevSecOps team to save them in OneView under Admin, Library defaults"
        }
        Map projects = loadedProjects()
        if (state.configFile) checkRequestedNames(projects)
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
                Map rawProj = ownSettings(pName as String, (projects[pName] ?: [:]) as Map)
                Map merged = SettingsAdvisor.copy(deepMerge(state.cfgDefaults, rawProj))
                Map auto = sqlOrLiquibase(pName as String, rawProj, autoResolver.resolve(state.cfgDefaults, rawProj, merged, pName as String), false)
                state.projectsAllCfg[pName] = auto.cfg
                state.projectsAutoResolved[pName] = auto.resolved
                warnIgnoredAppScanKey(pName as String, rawProj)
                if (state.configFile) adviseRemovableKeys(pName as String, rawProj, auto.cfg as Map)
            }
            def primaryName = projectNames[0]
            state.cfg = state.projectsAllCfg[primaryName]
            state.currentProjectName = primaryName
            script.env.CURRENT_PROJECT_NAME = primaryName
            script.echo "[INIT] Projects: ${projectNames.join(', ')} | primary: ${primaryName} | buildTool: ${state.cfg.buildTool ?: 'gradle'}"
            script.env.APPSCAN_SCAN_NAME = BuildUtils.scanName(state.cfg.tools?.sonar?.projectName ?: primaryName)
            script.echo "[INIT] APPSCAN_SCAN_NAME=${script.env.APPSCAN_SCAN_NAME}"
        }

        script.env.APPSCAN_KEY_ID = BuildUtils.appScanKeyId(state.appScanKeyDefaults())
        validateCrossServiceRepositories(projectNames as List)
        List databases = (projectNames as List).findAll { BuildUtils.databaseBlock(state.projectsAllCfg[it] as Map) }
        if (variant == 'nexusiq' && databases && databases.size() == (projectNames as List).size()) {
            script.error "[INIT] The Nexus IQ GoldenFix pipeline would scan nothing: ${databases.join(', ')} ${databases.size() == 1 ? 'is' : 'are'} SQL and PL/SQL service(s), which have no dependency components; run it for a service with a Nexus IQ application"
        }
        List unscanned = variant == 'nexusiq' ? withoutNexusIqScan(projectNames as List) : []
        if (unscanned) script.error "[INIT] The Nexus IQ GoldenFix pipeline would scan nothing for ${unscanned.join(', ')}: set tools.nexusIq.application and its scanPatterns for the service in ${state.settingsPlace()}"

        applyPolicy()
        script.echo "[INIT] OS: ${script.env.OS_TYPE ?: 'linux'}"
    }

    private void checkRequestedNames(Map projects) {
        List<String> requested = requestedProjectNames()
        if (!requested) return
        List<String> available = projects.keySet().collect { it as String }
        List<String> unknown = requested.findAll { !available.contains(it) }
        String parameter = script.env.PROJECT_NAMES ? 'projectNames' : 'PROJECT_NAME'
        if (!unknown) {
            if (requested == available) {
                script.echo "[INIT] [INFO] ${parameter}: ${requested.join(', ')} equals the default, every service of ${state.configFile} in its order - the parameter can be removed from the Jenkinsfile"
            }
            return
        }
        List<String> known = requested.findAll { available.contains(it) }
        if (known) {
            script.echo "[INIT] [WARN] ${parameter} names ${unknown.join(', ')}, which ${state.configFile} does not have (it has ${available.join(', ')}) - the run goes on with ${known.join(', ')}"
        } else {
            known = available
            script.echo "[INIT] [WARN] ${parameter} names ${unknown.join(', ')}, which ${state.configFile} does not have - the run goes on with the default, every service of the file: ${available.join(', ')}"
        }
        script.env.PROJECT_NAMES = known.join(',')
    }

    private Map ownSettings(String projectName, Map raw) {
        List<Map> invalid = SettingsAdvisor.invalidValues(raw, state.cfgDefaults)
        if (!invalid) return raw
        List<String> fields = []
        String origin = state.configFile ? 'library default' : 'Library default of OneView'
        for (Map f : invalid) {
            fields << (f.field as String)
            script.echo "[INIT] [WARN] ${projectName}: ${f.field}: ${f.value} in ${state.settingsPlace()} is not ${f.kind} - the ${origin} ${f.field}: ${f.fallback} is used instead"
        }
        return SettingsAdvisor.without(raw, fields)
    }

    private void adviseRemovableKeys(String projectName, Map raw, Map resolved) {
        List<String> lines = []
        for (Map f : SettingsAdvisor.sameAsDefaults(raw, state.cfgDefaults)) {
            lines << "${f.field}: ${f.value} equals the library default".toString()
        }
        for (String field : SettingsAdvisor.setResolvedKeys(raw)) {
            Map stripped = SettingsAdvisor.without(raw, [field])
            Map baseline = autoResolver.resolveQuietly(state.cfgDefaults, stripped, SettingsAdvisor.copy(deepMerge(state.cfgDefaults, stripped)), projectName).cfg as Map
            String value = SettingsAdvisor.display(SettingsAdvisor.valueAt(resolved, field))
            if (SettingsAdvisor.display(SettingsAdvisor.valueAt(baseline, field)) != value) continue
            boolean builtIn = SettingsAdvisor.display(SettingsAdvisor.valueAt(state.cfgDefaults, field)) == value
            lines << "${field}: ${value} equals the ${builtIn ? 'library default' : 'auto-detected value'}".toString()
        }
        for (String line : lines) {
            script.echo "[INIT] [INFO] ${projectName}: ${line} - the key can be removed from ${state.configFile}"
        }
    }

    private void validateCrossServiceRepositories(List projectNames) {
        if (!projectNames || projectNames.size() < 2) return
        for (int i = 0; i < projectNames.size(); i++) {
            def nameA = projectNames[i]
            Map cfgA = (state.projectsAllCfg[nameA] ?: [:]) as Map
            String repoA = BuildUtils.normalizeRepositoryUrl(cfgA.scm)
            String sastA = BuildUtils.resolveAppId(cfgA)
            String sonarA = BuildUtils.resolveSonarProjectKey(cfgA)
            List<String> niqA = BuildUtils.resolveNexusIqApplications(cfgA)

            for (int j = i + 1; j < projectNames.size(); j++) {
                def nameB = projectNames[j]
                Map cfgB = (state.projectsAllCfg[nameB] ?: [:]) as Map
                String repoB = BuildUtils.normalizeRepositoryUrl(cfgB.scm)
                boolean diffRepo = !repoA.isEmpty() && !repoB.isEmpty() && !repoA.equalsIgnoreCase(repoB)
                if (diffRepo) {
                    String sastB = BuildUtils.resolveAppId(cfgB)
                    if (sastA && sastB && sastA.equalsIgnoreCase(sastB)) {
                        script.error "[INIT] Services '${nameA}' and '${nameB}' belong to different Bitbucket repositories ('${repoA}' vs '${repoB}') and cannot share AppScan application ID '${sastA}'"
                    }
                    String sonarB = BuildUtils.resolveSonarProjectKey(cfgB)
                    if (sonarA && sonarB && sonarA.equalsIgnoreCase(sonarB)) {
                        script.error "[INIT] Services '${nameA}' and '${nameB}' belong to different Bitbucket repositories ('${repoA}' vs '${repoB}') and cannot share SonarQube project key '${sonarA}'"
                    }
                    List<String> niqB = BuildUtils.resolveNexusIqApplications(cfgB)
                    for (String appA : niqA) {
                        for (String appB : niqB) {
                            if (appA.equalsIgnoreCase(appB)) {
                                script.error "[INIT] Services '${nameA}' and '${nameB}' belong to different Bitbucket repositories ('${repoA}' vs '${repoB}') and cannot share Nexus IQ application '${appA}'"
                            }
                        }
                    }
                }
            }
        }
    }

    private void overlayRunState(Map projects, String job) {
        String file = BuildUtils.runStateFile()
        if (!script.fileExists(file)) {
            script.error "[INIT] The last successful build of ${job} has no ${file}: it predates the OneView integration; run ${job} once"
        }
        def copied = script.readYaml(file: file)
        String problem = overlay(projects, ((copied instanceof Map ? copied.projects : null) ?: [:]) as Map, job)
        if (problem) script.error "[INIT] ${problem}"
    }

    @NonCPS
    private String overlay(Map projects, Map copied, String job) {
        List paths = [['deploy', 'openshift', 'rd', 'buildTag'], ['deploy', 'openshift', 'rd', 'internalDockerUrl'],
                      ['delivery', 'buildTagAndroid'], ['delivery', 'buildTagIOS']]
        for (def name : projects.keySet()) {
            if (!(copied[name] instanceof Map)) {
                return "Security pipeline ${job} builds ${copied.keySet().join(', ')}, ${state.configFile ? 'this run' : 'this key'} builds ${name}".toString()
            }
            List own = BuildUtils.databaseBlock(projects[name] as Map) ? paths + [['delivery']] : paths
            for (def p : own) {
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
            if (BuildUtils.databaseBlock(state.projectsAllCfg[name] as Map)) return false
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
        for (def document : state.oneViewDocuments) {
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
    }

    List<String> switchProject(String projectName, PipelineState branch) {
        if (!state.projectsAllCfg.containsKey(projectName)) {
            script.error "[SWITCH] Project '${projectName}' not loaded."
        }
        branch.cfg = state.projectsAllCfg[projectName]
        branch.currentProjectName = projectName
        return ["CURRENT_PROJECT_NAME=${projectName}".toString(),
                "APPSCAN_SCAN_NAME=${BuildUtils.scanName(branch.cfg.tools?.sonar?.projectName ?: projectName)}".toString()]
    }

    void refreshAutoResolvedConfig(String projectName, String targetDir = '') {
        Map projects = loadedProjects()
        Map rawProj = (projects[projectName] ?: state.projectsAllCfg[projectName] ?: [:]) as Map
        Map current = (state.projectsAllCfg[projectName] ?: [:]) as Map
        if (targetDir && (!current.sourceDir || current.sourceDir == '.')) {
            current = [:] + current + [sourceDir: targetDir]
        }
        Map auto = sqlOrLiquibase(projectName, [:] + rawProj + [sourceDir: current.sourceDir],
                autoResolver.resolve(state.cfgDefaults, [:] + rawProj + [sourceDir: current.sourceDir], current, projectName, targetDir ? true : false), targetDir ? true : false)
        Map<String, Map> byField = [:]
        for (def r : ((state.projectsAutoResolved[projectName] ?: []) as List) + ((auto.resolved ?: []) as List)) byField[(r as Map).field as String] = r as Map
        state.projectsAutoResolved[projectName] = byField.values() as List
        state.projectsAllCfg[projectName] = auto.cfg
        if (state.currentProjectName == projectName) state.cfg = auto.cfg
    }

    private Map sqlOrLiquibase(String projectName, Map rawProj, Map auto, boolean separateRepository) {
        Map resolved = (auto.cfg ?: [:]) as Map
        if (!BuildUtils.sqlSources(resolved)) return auto
        Map fallback = sqlFallback(rawProj, resolved)
        if (!fallback) return auto
        String tool = ((rawProj?.buildTool ?: resolved.buildTool) ?: 'sql') as String
        String changelog = (fallback.changelog ?: '') as String
        Map raw = [:] + (rawProj ?: [:]) + [buildTool: 'liquibase']
        if (changelog) raw.database = (rawProj?.database instanceof Map ? [:] + (rawProj.database as Map) : [:]) + [changelogFile: changelog]
        if (resolved.buildToolAutoSetup == true && raw.buildToolAutoSetup == null && !raw.javaPath) raw.buildToolAutoSetup = true
        String using = changelog ? " with database.changelogFile ${changelog}".toString() : ''
        String setting = changelog ? " and database.changelogFile: ${changelog}".toString() : ''
        script.echo "[INIT] [WARN] ${projectName}: buildTool ${tool} is for SQL and PL/SQL files without a Liquibase changelog, but ${fallback.reason}: the service runs as buildTool liquibase${using} - set buildTool: liquibase${setting} in ${state.settingsPlace()}"
        Map again = autoResolver.resolve(state.cfgDefaults, raw, SettingsAdvisor.copy(deepMerge(state.cfgDefaults, raw)), projectName, separateRepository)
        List<Map> named = [[field: 'buildTool', value: 'liquibase', reason: "'${tool}' is for SQL and PL/SQL files without a Liquibase changelog, and this service has one".toString()]]
        if (changelog) named << [field: 'database.changelogFile', value: changelog, reason: 'Liquibase changelog found in the search root']
        return [cfg: again.cfg, resolved: named + ((again.resolved ?: []) as List)]
    }

    private Map sqlFallback(Map rawProj, Map cfg) {
        Map db = cfg.database instanceof Map ? (cfg.database as Map) : [:]
        if (((db.changelogFile ?: '') as String).trim()) return [reason: 'database.changelogFile is set']
        Map build = rawProj?.build instanceof Map ? (rawProj.build as Map) : [:]
        if (build.liquibase) return [reason: 'build.liquibase is set']
        Map tests = rawProj?.tests instanceof Map ? (rawProj.tests as Map) : [:]
        Map unitTests = tests.unitTests instanceof Map ? (tests.unitTests as Map) : [:]
        if (unitTests.liquibase) return [reason: 'tests.unitTests.liquibase is set']
        Map rawDb = rawProj?.database instanceof Map ? (rawProj.database as Map) : [:]
        String root = DatabaseSettings.join(((cfg.sourceDir ?: '.') as String), ((rawDb.searchPath ?: '.') as String))
        String found = autoResolver.liquibaseChangelogIn(root)
        if (!found) return [:]
        return [reason: "the repository has the Liquibase changelog ${DatabaseSettings.join(root, found)}".toString(), changelog: found]
    }

    private void warnIgnoredAppScanKey(String projectName, Map rawProj) {
        List ignored = ignoredAppScanKeySettings(rawProj)
        if (!ignored) return
        script.echo "[WARN] ${projectName}: ${ignored.join(', ')} in ${state.settingsPlace()} ignored - the AppScan on Cloud API key of the functional user is read only from asoc.keyId and asoc.token of resources/${BuildUtils.defaultsResource()} of the library; a service sets only its application ID (appId)"
    }

    @NonCPS
    static List ignoredAppScanKeySettings(Map rawProj) {
        List found = []
        for (String block : ['asoc', 'sast', 'dast']) {
            def section = rawProj?.get(block)
            if (!(section instanceof Map)) continue
            for (String key : ['keyId', 'token', 'credentialsId']) {
                def value = (section as Map).get(key)
                if (value != null && value.toString().trim()) found << "${block}.${key}".toString()
            }
        }
        return found
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
        script.echo(state.configFile ? "[POLICY] Library security policy (resources/${BuildUtils.defaultsResource()}), projects cannot change it:" : '[POLICY] Library defaults from OneView, projects cannot change them:')
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
