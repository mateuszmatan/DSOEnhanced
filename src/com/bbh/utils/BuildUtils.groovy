package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class BuildUtils implements Serializable {

    @NonCPS
    static String runStateFile() {
        return 'pipeline-config.yaml'
    }

    @NonCPS
    static List<String> repositoryConfigFiles() {
        return ['devsecops_config.yaml', 'config.yaml']
    }

    @NonCPS
    static String defaultsResource() {
        return 'com/bbh/devsecops/defaults.yaml'
    }

    static def execSh(def steps, String cmd, String label, boolean returnStdout) {
        String script = """
            #!/usr/bin/env bash
            set -euo pipefail
            ${cmd}
        """

        if (returnStdout) {
            return steps.sh(label: label, script: script, returnStdout: true).trim()
        }
        steps.sh(label: label, script: script)
        return null
    }

    @NonCPS
    static List<String> normalizeTokens(def v) {
        if (v == null) return []
        if (v instanceof List) return v.collect { it.toString() }.findAll { it?.trim() }
        if (v instanceof String ) {
            String s = v.trim()
            if (!s) return []
            return s.split(/\s+/).toList()
        }
        return [v.toString()]
    }

    @NonCPS
    static List<String> normalizeLines(def v) {
        List items = v instanceof List ? (v as List) : (v ? v.toString().readLines() : [])
        return items.collect { it?.toString()?.trim() }.findAll { it } as List<String>
    }

    @NonCPS
    static String shellQuoteIfNeeded(String token) {
        if (token == null) return ""
        if (token ==~ /^A-Za-z0-9._\/:=+-$/) return token
        return "'" + escapeForSingleQuotes(token) + "'"
    }

    @NonCPS
    static String scanName(String base) {
        return base.replaceAll(/[^a-zA-Z0-9_-]/, '-').replaceAll(/-+/, '-').toLowerCase().trim()
    }

    @NonCPS
    static String fileNameOf(String path) {
        String normalized = (path ?: '').replace('\\', '/')
        int index = normalized.lastIndexOf('/')
        return index >= 0 ? normalized.substring(index + 1) : normalized
    }

    @NonCPS
    static String parentOf(String path) {
        String normalized = (path ?: '').replace('\\', '/')
        int index = normalized.lastIndexOf('/')
        return index >= 0 ? normalized.substring(0, index) : ''
    }

    @NonCPS
    static boolean isAbsolutePath(String path) {
        String p = (path ?: '').trim()
        return p.startsWith('/') || p.startsWith('\\') || (p ==~ /^[A-Za-z]:[\\\/].*/)
    }

    @NonCPS
    static String relativePath(String path) {
        String trimmed = (path ?: '').trim()
        return trimmed.startsWith('/') ? trimmed : trimmed.tokenize('/').findAll { it != '.' }.join('/')
    }

    @NonCPS
    static String escapeForSingleQuotes(String value) {
        return value.replace("'", "'\"'\"'")
    }

    @NonCPS
    static String psSingleQuote(String value) {
        return (value ?: '').replace("'", "''")
    }

    @NonCPS
    static String cmdQuoteIfNeeded(String token) {
        if (token == null) return ''
        if (token ==~ /^[A-Za-z0-9._\\\/:=+-]+$/) return token
        return '"' + token.replace('"', '\\"') + '"'
    }

    @NonCPS
    static boolean isDotnetTool(String tool) {
        String low = (tool ?: '').trim().toLowerCase()
        return low in ['dotnet', 'msbuild', 'csharp', 'c#', '.net', 'net', 'dotnet-cli', 'fsharp', 'vbnet', 'cake', 'nuke', 'vs', 'visualstudio', 'netfx']
    }

    @NonCPS
    static String normalizeToolName(String tool) {
        String low = (tool ?: '').trim().toLowerCase()
        if (!low) return ''
        if (low in ['mvn', 'maven']) return 'maven'
        if (low in ['gradle', 'gradlew']) return 'gradle'
        if (low in ['msbuild', 'vs', 'visualstudio', 'netfx']) return 'msbuild'
        if (low in ['dotnet', 'csharp', 'c#', '.net', 'net', 'dotnet-cli', 'fsharp', 'vbnet', 'cake', 'nuke']) return 'dotnet'
        if (low in ['pip', 'python', 'poetry', 'pipenv']) return 'pip'
        if (low in ['npm', 'node', 'nodejs', 'yarn', 'pnpm', 'angular', 'react', 'vue']) return 'npm'
        if (low in ['flutter', 'dart']) return 'flutter'
        if (low == 'android') return 'android'
        if (low in ['ios', 'xcode', 'swift', 'cocoapods']) return 'ios'
        if (low in ['liquibase', 'sql', 'plsql']) return 'liquibase'
        return (tool ?: '').trim()
    }

    @NonCPS
    static boolean isValidBuildTool(String tool) {
        return normalizeToolName(tool) in ['maven', 'gradle', 'dotnet', 'msbuild', 'pip', 'npm', 'flutter', 'android', 'ios', 'liquibase']
    }

    @NonCPS
    static boolean booleanValue(def value, boolean defaultValue) {
        if (value == null) {
            return defaultValue
        }

        if (value instanceof Boolean) {
            return value
        }

        return value.toString().toBoolean()
    }

    @NonCPS
    static boolean parallelStaticAnalysis(Map projectsAllCfg, def forced) {
        return parallelSwitch(projectsAllCfg, [:], 'staticAnalysis', forced, 'DSO_STATIC_ANALYSIS_PARALLEL').enabled as boolean
    }

    @NonCPS
    static Map parallelSwitch(Map projectsAllCfg, Map cfgDefaults, String block, def forced, String envName) {
        String key = "${block}.parallel".toString()
        Map result = [enabled: false, key: key, warnings: [], notes: [], optedOut: [], source: '']
        if (!projectsAllCfg) return result
        String wanted = ((forced ?: '') as String).trim()
        if (wanted) {
            Boolean value = strictBoolean(wanted)
            if (value != null) {
                result.enabled = value
                result.source = envName
                if (value) result.notes << "${envName}=true equals the default ${key}: true - the variable can be removed".toString()
                return result
            }
            result.warnings << "${envName}='${wanted}' is neither true nor false - it is ignored and ${key} of the services decides, by default true".toString()
        }
        def shared = ((cfgDefaults ?: [:]) as Map).get(block)
        boolean inherited = shared instanceof Map && (shared as Map).get('parallel') != null
        if (inherited && strictBoolean((shared as Map).get('parallel')) == true) {
            result.notes << "defaults.${key}: true equals the built-in default - the key can be removed from the defaults".toString()
        }
        boolean enabled = true
        for (def entry : projectsAllCfg.entrySet()) {
            String name = entry.key as String
            def analysis = ((entry.value ?: [:]) as Map).get(block)
            if (analysis == null) continue
            if (!(analysis instanceof Map)) {
                result.warnings << "${name}: ${block} must be a map with the key parallel, not '${analysis}' - the default ${key}: true is used".toString()
                continue
            }
            def raw = (analysis as Map).get('parallel')
            if (raw == null) continue
            Boolean value = strictBoolean(raw)
            if (value == null) {
                result.warnings << "${name}: ${key}: '${raw}' is neither true nor false - the default true is used".toString()
            } else if (!value) {
                enabled = false
                result.optedOut << name
            } else if (!inherited) {
                result.notes << "${name}: ${key}: true equals the default - the key can be removed".toString()
            }
        }
        result.enabled = enabled
        result.source = enabled ? 'default' : (inherited && strictBoolean((shared as Map).get('parallel')) == false ? 'defaults' : key)
        return result
    }

    @NonCPS
    static List sameSourceProjects(Map projectsAllCfg, String name) {
        Map all = (projectsAllCfg ?: [:]) as Map
        if (!all.containsKey(name)) return [name]
        String own = sourceKey((all.get(name) ?: [:]) as Map)
        List names = []
        for (def entry : all.entrySet()) {
            if (sourceKey((entry.value ?: [:]) as Map) == own) names << (entry.key as String)
        }
        return names
    }

    @NonCPS
    private static String sourceKey(Map cfg) {
        String dir = ((cfg?.sourceDir ?: '.') as String).trim().replace('\\', '/')
        while (dir.startsWith('./')) dir = dir.substring(2)
        while (dir.endsWith('/') && dir.length() > 1) dir = dir.substring(0, dir.length() - 1)
        return dir ?: '.'
    }

    @NonCPS
    static String formatDuration(long ms) {
        long s = Math.max(0L, ms).intdiv(1000L) as long
        if (s < 60L) return "${s}s".toString()
        long m = s.intdiv(60L) as long
        long rem = s % 60L
        if (m < 60L) return "${m}m ${rem}s".toString()
        return "${m.intdiv(60L)}h ${m % 60L}m".toString()
    }

    @NonCPS
    static String parallelSaving(long sequentialMs, long actualMs) {
        if (sequentialMs <= 0L || actualMs >= sequentialMs) return ''
        long saved = sequentialMs - actualMs
        long percent = Math.round(saved * 100.0d / sequentialMs)
        return " - ${formatDuration(saved)} (${percent}%) saved".toString()
    }

    @NonCPS
    static Map manualEffort(Map cfgDefaults) {
        Map builtIn = [buildTrigger: 10, scanTrigger: 10, deployTrigger: 10, testTrigger: 10, dast: 360]
        Map given = (((((cfgDefaults ?: [:]) as Map).get('report') ?: [:]) as Map).get('manualEffort') ?: [:]) as Map
        Map minutes = [:]
        List warnings = []
        for (def entry : builtIn.entrySet()) {
            String key = entry.key as String
            def raw = given.get(key)
            long value = entry.value as long
            if (raw != null) {
                String text = (raw as String).trim()
                if (text ==~ /\d{1,6}/) {
                    value = text as long
                } else {
                    warnings << "report.manualEffort.${key}: '${raw}' is not a number of minutes - the default ${entry.value} is used".toString()
                }
            }
            minutes[key] = value
        }
        return [minutes: minutes, warnings: warnings]
    }

    @NonCPS
    static Boolean strictBoolean(def value) {
        if (value instanceof Boolean) return value as Boolean
        String text = ((value == null ? '' : value) as String).trim().toLowerCase()
        if (text == 'true') return true
        if (text == 'false') return false
        return null
    }

    @NonCPS
    static List sastCompileOverrides(Map projectsAllCfg) {
        List names = []
        for (def entry : (projectsAllCfg ?: [:]).entrySet()) {
            Map cfg = (entry.value ?: [:]) as Map
            Map asoc = (cfg.asoc ?: [:]) as Map
            Map build = (cfg.build ?: [:]) as Map
            if (!booleanValue(asoc.doCompile, true)) continue
            for (String tool : ['gradle', 'maven', 'liquibase', 'dotnet', 'msbuild']) {
                if (asoc.get(tool) && asoc.get(tool) != build.get(tool) && !names.contains(entry.key)) names << (entry.key as String)
            }
        }
        return names
    }

    @NonCPS
    static boolean nexusDeliveryEnabled(Map cfg) {
        def delivery = cfg.delivery
        if (delivery instanceof Map && (delivery as Map).nexusDeliveryEnabled != null) {
            return booleanValue((delivery as Map).nexusDeliveryEnabled, true)
        }
        return booleanValue(cfg.deploy?.get(cfg.deployTarget ?: 'vm')?.rd?.nexusDeliveryEnabled, true)
    }

    @NonCPS
    static String nexusDeliverySetting(Map cfg) {
        def delivery = cfg.delivery
        if (delivery instanceof Map && (delivery as Map).nexusDeliveryEnabled != null) return 'delivery.nexusDeliveryEnabled'
        return "deploy.${cfg.deployTarget ?: 'vm'}.rd.nexusDeliveryEnabled".toString()
    }

    @NonCPS
    static String appScanKeyId(Map defaults) {
        return ((defaults?.asoc?.keyId ?: '') as String).trim()
    }

    @NonCPS
    static String appScanKeySecretCredentialsId(Map defaults) {
        Map asoc = (defaults?.asoc instanceof Map ? defaults.asoc : [:]) as Map
        return ((asoc.token ?: asoc.credentialsId ?: appScanKeyId(defaults)) as String).trim()
    }

    @NonCPS
    static boolean databaseBlock(Map cfg) {
        return ((cfg?.buildTool ?: '') as String).trim().equalsIgnoreCase('liquibase') && cfg.database instanceof Map && ((cfg.database.changelogFile ?: '') as String).trim()
    }

    @NonCPS
    static boolean nodeBuildEnabled(Map cfg) {
        return !databaseBlock(cfg) && booleanValue(cfg.build?.node?.enabled, false)
    }

    @NonCPS
    static boolean nodeBuildNeeded(Map cfg, String variant, boolean windows) {
        return nodeBuildEnabled(cfg) && (variant != 'sast' || (booleanValue(cfg.asoc?.doCompile, true) && !windows))
    }

    static String requiredString(def script, def value, String propertyName) {
        String result = value?.toString()?.trim()

        if (!result) {
            script.error "[DOD] '${propertyName}' is required."
        }
        return result
    }

    @NonCPS
    static String msFmt(long ms) {
        if (ms <= 0) return '-'
        int s = (ms / 1000) as int
        if (s < 60) return "${s}s"
        int m = s.intdiv(60)
        int sec = s % 60
        return sec > 0 ? "${m}m ${sec}s" : "${m}m"
    }

    @NonCPS
    static String normalizeBranch(String branch) {
        return (branch ?: '').trim().replaceFirst('^(refs/remotes/origin/|refs/heads/|origin/)', '')
    }

    @NonCPS
    static String normalizeRepositoryUrl(def scm) {
        String raw = ''
        if (scm instanceof Map) {
            raw = (scm.bitbucket?.url ?: scm.url ?: '') as String
        } else if (scm instanceof String) {
            raw = scm
        }
        String u = (raw ?: '').trim().toLowerCase()
        if (u.endsWith('.git')) {
            u = u.substring(0, u.length() - 4)
        }
        while (u.endsWith('/')) {
            u = u.substring(0, u.length() - 1)
        }
        return u
    }

    @NonCPS
    static String resolveAppId(Map cfg) {
        return ((cfg?.appId ?: cfg?.asoc?.appId ?: cfg?.sast?.appId ?: '') as String).trim()
    }

    @NonCPS
    static String resolveSonarProjectKey(Map cfg) {
        return ((cfg?.tools?.sonar?.projectKey ?: cfg?.sonar?.projectKey ?: '') as String).trim()
    }

    @NonCPS
    static List<String> resolveNexusIqApplications(Map cfg) {
        List<String> apps = []
        if (cfg?.tools?.nexusIq?.application) {
            apps << (cfg.tools.nexusIq.application as String).trim()
        }
        if (cfg?.tools?.nexusIq?.applications instanceof List) {
            for (def item : cfg.tools.nexusIq.applications) {
                if (item instanceof Map && item.application) {
                    apps << (item.application as String).trim()
                } else if (item instanceof String && item.trim()) {
                    apps << item.trim()
                }
            }
        }
        if (cfg?.nexusIq?.application) {
            apps << (cfg.nexusIq.application as String).trim()
        }
        return apps.findAll { it }.unique()
    }

    @NonCPS
    static Map pluginRepositories(Map cfg, Map fallback = null) {
        def ownRaw = cfg?.get('pluginRepositories')
        def otherRaw = fallback?.get('pluginRepositories')
        Map own = (ownRaw instanceof Map ? ownRaw : [:]) as Map
        Map other = (otherRaw instanceof Map ? otherRaw : [:]) as Map
        def enabled = own.get('enabled') != null ? own.get('enabled') : other.get('enabled')
        if (!booleanValue(enabled, true)) return [:]
        Map out = [:]
        Map invalid = [:]
        for (String tool : ['gradle', 'maven']) {
            String set = ((own.get(tool) ?: '') as String).trim()
            String standby = ((other.get(tool) ?: '') as String).trim()
            if (!pluginRepositoryUrl(standby)) standby = ''
            if (set && !pluginRepositoryUrl(set)) {
                invalid[tool] = set
                set = ''
            }
            out[tool] = set ?: standby
        }
        if (invalid) out.invalid = invalid
        return out
    }

    @NonCPS
    static boolean pluginRepositoryUrl(String url) {
        return (url ?: '') ==~ "https?://[A-Za-z0-9._/+@:=,~%-]+"
    }

    @NonCPS
    static String folderKey(String path) {
        String key = (path ?: 'workspace').replaceAll('[^A-Za-z0-9]+', '_').replaceAll('^_+|_+$', '')
        return key.length() > 80 ? key.substring(key.length() - 80) : (key ?: 'workspace')
    }

    @NonCPS
    static List<String> missingPlugins(String tool, String output) {
        List<String> found = []
        String text = output ?: ''
        if (tool == 'gradle') {
            def ids = text =~ "Plugin \\[id: '([^']+)'(?:, version: '([^']+)')?[^\\]]*\\] was not found"
            while (ids.find()) found << (ids.group(2) ? "${ids.group(1)}:${ids.group(2)}".toString() : ids.group(1) as String)
            if (text =~ "Could not resolve all (?:files|artifacts|dependencies) for configuration '[^']*classpath'") {
                def modules = text =~ "Could not (?:find|resolve) ([\\w.\\-]+:[\\w.\\-]+:[\\w.\\-+]*[\\w+])"
                while (modules.find()) found << modules.group(1)
            }
        } else {
            def plugins = text =~ "Plugin ([\\w.\\-]+:[\\w.\\-]+:[\\w.\\-]*\\w) or one of its dependencies could not be resolved"
            while (plugins.find()) found << plugins.group(1)
            def descriptors = text =~ "Failed to retrieve plugin descriptor for ([\\w.\\-]+:[\\w.\\-]+:[\\w.\\-]*\\w)"
            while (descriptors.find()) found << descriptors.group(1)
            def prefixes = text =~ "No plugin found for prefix '([^']+)'"
            while (prefixes.find()) found << "prefix ${prefixes.group(1)}".toString()
        }
        return found.unique()
    }

    @NonCPS
    static boolean ownMavenSettings(List flags) {
        return (flags ?: []).any { def f -> (f as String) in ['-s', '--settings'] || (f as String).startsWith('--settings=') }
    }

    @NonCPS
    static String gradlePluginInitScript(String url) {
        String quoted = "'" + url.replace('\\', '\\\\').replace("'", "\\'") + "'"
        return """def bbhNexusPlugins = ${quoted}
def bbhNexusFirst = { def repositories ->
    def bbh = repositories.maven {
        name = 'bbhNexusPlugins'
        url = bbhNexusPlugins
    }
    repositories.remove(bbh)
    repositories.addFirst(bbh)
}
beforeSettings { settings ->
    bbhNexusFirst(settings.pluginManagement.repositories)
}
settingsEvaluated { settings ->
    def repositories = settings.pluginManagement.repositories
    if (repositories.findAll { it.name != 'bbhNexusPlugins' }.isEmpty()) repositories.gradlePluginPortal()
}
allprojects { project ->
    bbhNexusFirst(project.buildscript.repositories)
}
""".toString()
    }

    @NonCPS
    static String mavenPluginSettings(String own, String url) {
        String escaped = url.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
        String profile = """<profile>
      <id>bbh-nexus-plugins</id>
      <pluginRepositories>
        <pluginRepository>
          <id>bbh-nexus-plugins</id>
          <url>${escaped}</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>""".toString()
        String active = '<activeProfile>bbh-nexus-plugins</activeProfile>'
        String text = (own ?: '').trim()
        int end = text.lastIndexOf('</settings>')
        if (end < 0) {
            return """<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <profiles>
    ${profile}
  </profiles>
  <activeProfiles>
    ${active}
  </activeProfiles>
</settings>
""".toString()
        }
        String head = text.substring(0, end)
        String tail = text.substring(end)
        int profiles = head.indexOf('<profiles>')
        head = profiles >= 0 ? head.substring(0, profiles + 10) + '\n    ' + profile + head.substring(profiles + 10)
                : head + '  <profiles>\n    ' + profile + '\n  </profiles>\n'
        int actives = head.indexOf('<activeProfiles>')
        head = actives >= 0 ? head.substring(0, actives + 16) + '\n    ' + active + head.substring(actives + 16)
                : head + '  <activeProfiles>\n    ' + active + '\n  </activeProfiles>\n'
        return head + tail + '\n'
    }
}
