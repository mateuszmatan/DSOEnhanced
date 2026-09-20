package com.bbh.config

import com.bbh.core.PipelineState

class ConfigLoader implements Serializable {

    private final def           script
    private final PipelineState state

    ConfigLoader(def script, PipelineState state) {
        this.script = script
        this.state  = state
    }

    void initialize() {
        state.commitTime = System.currentTimeMillis()

        String defaultsText = script.libraryResource('defaults.yaml')
        def defaultsYaml    = script.readYaml(text: defaultsText)
        state.cfgDefaults   = defaultsYaml?.defaults ?: [:]

        def projectNames = resolveProjectNames()
        if (!projectNames) {
            script.echo "[INIT] No PROJECT_NAMES defined - using defaults."
            state.cfg = [:]
        } else {
            if (!script.fileExists('config.yaml')) {
                script.error "[INIT] config.yaml not found in workspace. Projects must provide config.yaml with a 'projects:' section."
            }
            def raw = script.readYaml(file: 'config.yaml')
            if (!raw?.projects) {
                script.error "[INIT] config.yaml must contain a 'projects:' section."
            }
            for (int i = 0; i < projectNames.size(); i++) {
                def pName = projectNames[i]
                if (!raw.projects.containsKey(pName)) {
                    script.error "[INIT] Project '${pName}' not found in config.yaml. Available: ${raw.projects.keySet().join(', ')}"
                }
                state.projectsAllCfg[pName] = deepMerge(state.cfgDefaults, raw.projects[pName] as Map)
            }
            def primaryName = projectNames[0]
            state.cfg = state.projectsAllCfg[primaryName]
            state.currentProjectName = primaryName
            script.env.CURRENT_PROJECT_NAME = primaryName
            script.echo "[INIT] Projects: ${projectNames.join(', ')} | primary: ${primaryName} | buildTool: ${state.cfg.buildTool ?: 'gradle'}"
            script.env.APPSCAN_SCAN_NAME = buildScanName(state.cfg.tools?.sonar?.projectName ?: primaryName)
            script.echo "[INIT] APPSCAN_SCAN_NAME=${script.env.APPSCAN_SCAN_NAME}"
        }

        def keyId = state.cfg.asoc?.keyId?.trim()
        if (!keyId) script.error "[INIT] asoc.keyId must be set in config.yaml"
        script.env.APPSCAN_KEY_ID = keyId

        applyPolicy()
        script.echo "[INIT] OS: ${script.env.OS_TYPE ?: 'linux'}"
    }

    List<String> resolveProjectNames() {
        def raw = (script.env.PROJECT_NAMES ?: script.env.PROJECT_NAME ?: '').trim()
        if (raw) return raw.split(',').collect { it.trim() }.findAll { it }
        if (script.fileExists('config.yaml')) {
            try {
                def cfg = script.readYaml(file: 'config.yaml')
                def keys = cfg?.projects?.keySet()?.toList() ?: []
                if (keys) {
                    script.echo "[INIT] PROJECT_NAMES not set - auto-detected from config.yaml: ${keys.join(', ')}"
                    return keys
                }
            } catch (ignored) {}
        }
        return []
    }

    void switchProject(String projectName) {
        if (!state.projectsAllCfg.containsKey(projectName)) {
            script.error "[SWITCH] Project '${projectName}' not loaded."
        }
        state.cfg = state.projectsAllCfg[projectName]
        state.currentProjectName = projectName
        script.env.CURRENT_PROJECT_NAME = projectName
        script.env.APPSCAN_SCAN_NAME = buildScanName(state.cfg.tools?.sonar?.projectName ?: projectName)
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
        script.echo "[POLICY] Library security policy (resources/defaults.yaml), projects cannot change it:"
        ['sast', 'sca', 'niq', 'dast'].each { key ->
            def limit = state.policyLimits[key] ?: [:]
            script.echo "[POLICY]   ${key.toUpperCase()}: critical<=${limit.maxCritical} high<=${limit.maxHigh} medium<=${limit.maxMedium}"
        }
        script.echo "[POLICY]   Coverage: line coverage >= ${state.coverage.minRequired}%"
        script.echo "[POLICY] A violation marks the stage unstable, the build continues, the Nexus release and the QC deployment stay blocked."
    }

    private String buildScanName(String base) {
        return base.replaceAll(/[^a-zA-Z0-9_-]/, '-').replaceAll(/-+/, '-').toLowerCase().trim()
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
