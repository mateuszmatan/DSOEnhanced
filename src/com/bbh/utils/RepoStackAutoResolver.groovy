package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class RepoStackAutoResolver implements Serializable {
    private final def script

    RepoStackAutoResolver(def script) {
        this.script = script
    }

    @NonCPS
    static List<Map> changes(Map before, Map after) {
        Map<String, Object> old = [:]
        Map<String, Object> now = [:]
        flatten(before, '', old)
        flatten(after, '', now)
        List<Map> out = []
        for (def entry : now.entrySet()) {
            String field = entry.key as String
            def value = entry.value
            if (!old.containsKey(field)) {
                out << [field: field, value: display(value), reason: 'not set - detected from the repository']
            } else if (display(old[field]) != display(value)) {
                out << [field: field, value: display(value), reason: "replaces the invalid or unsupported value '${display(old[field])}'".toString()]
            }
        }
        return out
    }

    @NonCPS
    private static void flatten(def node, String prefix, Map<String, Object> out) {
        if (!(node instanceof Map)) {
            if (prefix) out[prefix] = node
            return
        }
        for (def entry : (node as Map).entrySet()) {
            String key = prefix ? "${prefix}.${entry.key}".toString() : entry.key.toString()
            flatten(entry.value, key, out)
        }
    }

    @NonCPS
    private static String display(def value) {
        if (value instanceof Collection) return "[${(value as Collection).join(', ')}]".toString()
        return value == null ? '' : value.toString()
    }

    Map resolve(Map currentCfg, String serviceName = '', Map explicitCfg = null) {
        Map cfg = currentCfg != null ? [:] + currentCfg : [:]
        def flag = cfg.get('autoresolve parameters')
        if (flag == null) flag = cfg.autoresolveParameters
        if (flag == null) flag = cfg.autoresolve
        if (flag == null) flag = cfg.autoResolve
        if (flag == null) flag = cfg.autoResolveParameters
        if (!BuildUtils.booleanValue(flag != null ? flag : true, true)) return cfg

        resolveBuildTool(cfg, explicitCfg)
        resolveDotnet(cfg)
        resolveNodeStage0(cfg)
        resolveDeployTarget(cfg, serviceName, explicitCfg)
        resolveLiquibase(cfg)
        resolveFlutter(cfg)
        resolveSonar(cfg, serviceName)
        return cfg
    }

    private void resolveBuildTool(Map cfg, Map explicitCfg) {
        def rawExplicit = explicitCfg != null ? explicitCfg.buildTool : cfg.buildTool
        String existing = ((rawExplicit ?: '') as String).trim()
        String sd = sourceRoot(cfg)
        if (existing && !existing.equalsIgnoreCase('auto')) {
            if (BuildUtils.isValidBuildTool(existing)) {
                String normalized = BuildUtils.normalizeToolName(existing)
                cfg.buildTool = normalized
                checkJdkAutoSetup(cfg, normalized, sd)
                return
            }
            String detected = detectToolFromRepo(sd) ?: (((cfg.buildTool as String) && BuildUtils.isValidBuildTool(cfg.buildTool as String)) ? BuildUtils.normalizeToolName(cfg.buildTool as String) : 'gradle')
            script.echo "[WARN] Invalid buildTool '${existing}' in configuration. Falling back to auto-detected buildTool '${detected}'."
            cfg.buildTool = detected
            checkJdkAutoSetup(cfg, detected, sd)
            return
        }
        String detected = detectToolFromRepo(sd)
        if (detected) {
            cfg.buildTool = detected
            checkJdkAutoSetup(cfg, detected, sd)
        }
    }

    private String sourceRoot(Map cfg) {
        String sd = ((cfg?.sourceDir ?: '') as String).trim().replace('\\', '/')
        if (!sd || sd == '.' || sd == './') return ''
        return sd.replaceAll('^\\./|/+$', '')
    }

    private boolean existsIn(String sd, String relPath) {
        return script.fileExists(sd ? "${sd}/${relPath}" : relPath)
    }

    private String readIn(String sd, String relPath) {
        return readFileQuiet(sd ? "${sd}/${relPath}" : relPath)
    }

    private String detectToolFromRepo(String sd) {
        if (existsIn(sd, 'pubspec.yaml')) return 'flutter'
        if (isIosPresent(sd)) return 'ios'
        if (existsIn(sd, 'pom.xml')) return 'maven'
        if (existsIn(sd, 'build.gradle') || existsIn(sd, 'build.gradle.kts') || existsIn(sd, 'gradle/wrapper/gradle-wrapper.properties')) {
            return (existsIn(sd, 'AndroidManifest.xml') || existsIn(sd, 'app/src/main/AndroidManifest.xml')) ? 'android' : 'gradle'
        }
        if (isDotnetPresent(sd)) return 'dotnet'
        if (isLiquibasePresent(sd)) return 'liquibase'
        if (isPipPresent(sd)) return 'pip'
        if (isNpmPresent(sd)) return 'npm'
        return null
    }

    private void checkJdkAutoSetup(Map cfg, String tool, String sd) {
        if (cfg.javaPath || cfg.buildToolAutoSetup != null) return
        if (tool == 'maven' && existsIn(sd, 'pom.xml')) {
            if (readIn(sd, 'pom.xml') =~ /<java\.version>|<maven\.compiler\.source>|<maven\.compiler\.target>|<release>/) {
                cfg.buildToolAutoSetup = true
            }
        } else if (tool in ['gradle', 'android']) {
            if (existsIn(sd, 'gradle/wrapper/gradle-wrapper.properties') || existsIn(sd, 'build.gradle') || existsIn(sd, 'build.gradle.kts')) {
                cfg.buildToolAutoSetup = true
            }
        }
    }

    private void resolveDotnet(Map cfg) {
        if (!BuildUtils.isDotnetTool(cfg.buildTool as String)) return
        Map build = (cfg.build ?: [:]) as Map
        String toolKey = (cfg.buildTool == 'msbuild' && build.msbuild instanceof Map) ? 'msbuild' : 'dotnet'
        Map dnBuild = build.get(toolKey) instanceof Map ? ([:] + (build.get(toolKey) as Map)) : [:]
        boolean buildChanged = false
        def rawMode = dnBuild.mode ?: dnBuild.tool
        if (!DotnetOptions.isAuto(rawMode) && !DotnetOptions.mode(rawMode)) {
            dnBuild.mode = warnFallback("Invalid .NET build mode '${rawMode}' in configuration", 'mode', detectDotnetModeFromRepo(sourceRoot(cfg)))
            buildChanged = true
        }
        if (dnBuild.useDotnetCli != null && !DotnetOptions.isBoolean(dnBuild.useDotnetCli)) {
            dnBuild.mode = warnFallback("Invalid useDotnetCli value '${dnBuild.useDotnetCli}' in configuration", 'mode', detectDotnetModeFromRepo(sourceRoot(cfg)))
            dnBuild.remove('useDotnetCli')
            buildChanged = true
        }
        buildChanged = resetInvalidCoverage(dnBuild) || buildChanged
        if (buildChanged) cfg.build = [:] + build + [(toolKey): dnBuild]

        Map tests = (cfg.tests ?: [:]) as Map
        Map ut = (tests.unitTests ?: [:]) as Map
        if (!(ut.get(toolKey) instanceof Map)) return
        Map dnUt = [:] + (ut.get(toolKey) as Map)
        boolean utChanged = false
        def rawRunner = dnUt.runner ?: dnUt.testRunner
        if (!DotnetOptions.isAuto(rawRunner) && !DotnetOptions.runner(rawRunner)) {
            dnUt.runner = warnFallback("Invalid .NET test runner '${rawRunner}' in configuration", 'runner', 'auto')
            dnUt.remove('testRunner')
            utChanged = true
        }
        utChanged = resetInvalidCoverage(dnUt) || utChanged
        if (utChanged) cfg.tests = [:] + tests + [unitTests: [:] + ut + [(toolKey): dnUt]]
    }

    private boolean resetInvalidCoverage(Map toolCfg) {
        def raw = toolCfg.coverageTool
        if (DotnetOptions.isAuto(raw) || DotnetOptions.coverage(raw)) return false
        toolCfg.coverageTool = warnFallback("Invalid .NET coverageTool '${raw}' in configuration", 'coverage tool', 'auto')
        return true
    }

    private String warnFallback(String problem, String what, String fallback) {
        script.echo "[WARN] ${problem}. Falling back to auto-detected ${what} '${fallback}'."
        return fallback
    }

    private String detectDotnetModeFromRepo(String sd) {
        if (script.isUnix()) return 'dotnet'
        if (existsIn(sd, 'packages.config')) return 'msbuild'
        for (String glob : DotnetOptions.projectGlobs()) {
            for (String p : findPaths(sd ? "${sd}/${glob}" : glob)) {
                String text = readFileQuiet(p)
                if (DotnetOptions.isClassicProject(text)) return 'msbuild'
                if (DotnetOptions.isSdkProject(text)) return 'dotnet'
            }
        }
        return 'msbuild'
    }

    private void resolveNodeStage0(Map cfg) {
        Map build = (cfg.build ?: [:]) as Map
        Map node = (build.node ?: [:]) as Map
        if (node.enabled != null) return

        String sd = sourceRoot(cfg)
        String foundDir = null
        String foundPkgJson = null
        for (String d : ['.', 'frontend', 'web', 'client', 'ui']) {
            String rel = d == '.' ? 'package.json' : "${d}/package.json"
            String p = sd ? "${sd}/${rel}" : rel
            if (script.fileExists(p)) {
                foundDir = sd && d == '.' ? sd : (sd ? "${sd}/${d}" : d)
                foundPkgJson = p
                break
            } else if (!sd && script.fileExists(rel)) {
                foundDir = d
                foundPkgJson = rel
                break
            }
        }
        if (foundDir == null) return

        Map newBuild = [:] + build
        Map newNode = [:] + node
        newNode.enabled = true
        if (!newNode.dir) newNode.dir = foundDir

        String pkgContent = readFileQuiet(foundPkgJson)
        if (pkgContent) {
            String nodeVer = extractNodeEngineVersion(pkgContent)
            if (nodeVer && !newNode.version && !newNode.installationName) {
                newNode.version = nodeVer
            }
            if (pkgContent.contains('"cypress"')) {
                Map tests = (cfg.tests ?: [:]) as Map
                Map unitTests = (tests.unitTests ?: [:]) as Map
                Map nodeTests = (unitTests.node ?: [:]) as Map
                if (nodeTests.cypress == null) {
                    nodeTests.cypress = true
                    unitTests.node = nodeTests
                    tests.unitTests = unitTests
                    cfg.tests = tests
                }
            }
        }
        newBuild.node = newNode
        cfg.build = newBuild
    }

    private String extractNodeEngineVersion(String json) {
        def m = json =~ /"node"\s*:\s*"([^"]+)"/
        if (m.find()) {
            String raw = ((m[0] as List)[1] ?: '') as String
            def num = raw =~ /(\d+)/
            if (num.find()) return ((num[0] as List)[1] ?: '') as String
        }
        return null
    }

    private void resolveDeployTarget(Map cfg, String serviceName, Map explicitCfg) {
        def rawExplicit = explicitCfg != null ? explicitCfg.deployTarget : cfg.deployTarget
        String existing = ((rawExplicit ?: '') as String).trim()
        String sd = sourceRoot(cfg)
        boolean hasContainer = existsIn(sd, 'Dockerfile') || existsIn(sd, 'Containerfile') ||
                existsIn(sd, 'openshift') || existsIn(sd, 'k8s') || existsIn(sd, 'helm')
        if (existing && !existing.equalsIgnoreCase('auto')) {
            String low = existing.toLowerCase()
            if (low in ['openshift', 'ocp', 'k8s', 'kubernetes', 'vm', 'dod', 'nexus', 'none']) {
                if (low in ['ocp', 'k8s', 'kubernetes']) cfg.deployTarget = 'openshift'
                return
            }
            String fallback = hasContainer ? 'openshift' : ((cfg.deployTarget as String) ?: 'vm')
            script.echo "[WARN] Invalid deployTarget '${existing}' in configuration. Falling back to auto-detected deployTarget '${fallback}'."
            cfg.deployTarget = fallback
            if (fallback == 'openshift' && !cfg.appName) cfg.appName = serviceName ?: 'app'
            return
        }
        if (hasContainer) {
            cfg.deployTarget = 'openshift'
            if (!cfg.appName) cfg.appName = serviceName ?: 'app'
        } else if (existing.equalsIgnoreCase('auto')) {
            cfg.deployTarget = 'vm'
        }
    }

    private void resolveLiquibase(Map cfg) {
        if (cfg.buildTool != 'liquibase') return
        String changelog = detectChangelogPath(sourceRoot(cfg))
        Map build = (cfg.build ?: [:]) as Map
        Map liqBuild = (build.liquibase ?: [:]) as Map
        if (!liqBuild.commands) liqBuild.commands = ['updateSQL']
        if (!liqBuild.flags && changelog) liqBuild.flags = ["--changelog-file=${changelog}"]
        build.liquibase = liqBuild
        cfg.build = build

        Map tests = (cfg.tests ?: [:]) as Map
        Map unitTests = (tests.unitTests ?: [:]) as Map
        Map liqTests = (unitTests.liquibase ?: [:]) as Map
        if (!liqTests.commands) liqTests.commands = ['validate']
        if (!liqTests.flags && changelog) liqTests.flags = ["--changelog-file=${changelog}"]
        unitTests.liquibase = liqTests
        tests.unitTests = unitTests
        cfg.tests = tests
    }

    private String detectChangelogPath(String sd) {
        for (String c : [
                'changelog.xml', 'liquibase.properties', 'db/changelog.xml',
                'src/main/resources/changelog.xml', 'src/main/resources/db/changelog/db.changelog-master.xml',
                'db/changelog/db.changelog-master.xml', 'sql/changelog.xml'
        ]) {
            if (existsIn(sd, c)) return sd && script.fileExists("${sd}/${c}") ? "${sd}/${c}" : c
        }
        return 'src/main/resources/changelog.xml'
    }

    private boolean isLiquibasePresent(String sd) {
        return ['liquibase.properties', 'changelog.xml', 'changelog.yaml', 'changelog.yml', 'changelog.json',
                'db/changelog.xml', 'db/changelog/db.changelog-master.xml',
                'src/main/resources/db/changelog/db.changelog-master.xml',
                'src/main/resources/changelog.xml'].any { String f -> existsIn(sd, f) }
    }

    private boolean isPipPresent(String sd) {
        return ['requirements.txt', 'pyproject.toml', 'setup.py', 'Pipfile'].any { String f -> existsIn(sd, f) }
    }

    private boolean isNpmPresent(String sd) {
        return ['package.json', 'angular.json'].any { String f -> existsIn(sd, f) }
    }

    private boolean isIosPresent(String sd) {
        if (['Podfile', 'Package.swift'].any { String f -> existsIn(sd, f) }) return true
        return findPaths(sd ? "${sd}/*.xcodeproj" : '*.xcodeproj').size() > 0 || findPaths(sd ? "${sd}/*.xcworkspace" : '*.xcworkspace').size() > 0
    }

    private boolean isDotnetPresent(String sd) {
        if (['global.json', 'packages.config', 'Directory.Packages.props', 'Directory.Build.props', 'build.cake', '.nuke'].any { String f -> existsIn(sd, f) }) {
            return true
        }
        for (String glob : ['*.sln', '*.csproj', '*.fsproj', '*.vbproj', '**/*.sln', '**/*.csproj', '**/*.fsproj', '**/*.vbproj']) {
            if (findPaths(sd ? "${sd}/${glob}" : glob)) return true
        }
        return false
    }

    private List<String> findPaths(String glob) {
        List<String> out = []
        try {
            def hits = script.findFiles(glob: glob)
            if (hits) {
                for (def h : hits) {
                    String p = (h?.path ?: '') as String
                    if (p) out << p
                }
            }
        } catch (Exception ignored) {}
        return out
    }

    private void resolveFlutter(Map cfg) {
        if (cfg.buildTool != 'flutter') return
        Map fl = (cfg.flutter ?: [:]) as Map
        if (!fl.platform) fl.platform = 'apk'
        cfg.flutter = fl
    }

    private void resolveSonar(Map cfg, String serviceName) {
        Map tools = (cfg.tools ?: [:]) as Map
        Map sonar = (tools.sonar ?: [:]) as Map
        if (!sonar.projectKey) {
            String pKey = serviceName ?: 'service'
            if (!pKey.startsWith('com.bbh:')) pKey = "com.bbh:${pKey}"
            sonar.projectKey = pKey
        }
        tools.sonar = sonar
        cfg.tools = tools
    }

    private String readFileQuiet(String path) {
        try {
            return script.readFile(path) ?: ''
        } catch (Exception ignored) {
            return ''
        }
    }
}
