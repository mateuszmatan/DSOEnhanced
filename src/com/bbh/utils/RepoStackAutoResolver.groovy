package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class RepoStackAutoResolver implements Serializable {
    private final def script

    RepoStackAutoResolver(def script) {
        this.script = script
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
        String sd = sourceRoot(cfg)
        Map build = (cfg.build ?: [:]) as Map
        String toolKey = (cfg.buildTool == 'msbuild' && build.msbuild instanceof Map) ? 'msbuild' : 'dotnet'
        Map dnBuild = (build.get(toolKey) instanceof Map ? ([:] + (build.get(toolKey) as Map)) : [:])
        boolean buildChanged = false

        String rawMode = ((dnBuild.mode ?: dnBuild.tool ?: '') as String).trim()
        if (rawMode && !rawMode.equalsIgnoreCase('auto') && !isValidDotnetMode(rawMode)) {
            String fallback = detectDotnetModeFromRepo(sd)
            script.echo "[WARN] Invalid .NET build mode '${rawMode}' in configuration. Falling back to auto-detected mode '${fallback}'."
            dnBuild.mode = fallback
            buildChanged = true
        }
        if (dnBuild.useDotnetCli != null && !isValidBoolean(dnBuild.useDotnetCli)) {
            String fallback = detectDotnetModeFromRepo(sd)
            script.echo "[WARN] Invalid useDotnetCli value '${dnBuild.useDotnetCli}' in configuration. Falling back to auto-detected mode '${fallback}'."
            dnBuild.remove('useDotnetCli')
            dnBuild.mode = fallback
            buildChanged = true
        }
        if (dnBuild.coverageTool && !isValidDotnetCoverage(dnBuild.coverageTool as String)) {
            script.echo "[WARN] Invalid .NET coverageTool '${dnBuild.coverageTool}' in configuration. Falling back to auto-detected coverage tool 'auto'."
            dnBuild.coverageTool = 'auto'
            buildChanged = true
        }
        if (buildChanged) {
            Map newBuild = [:] + build
            newBuild[toolKey] = dnBuild
            cfg.build = newBuild
        }

        Map tests = (cfg.tests ?: [:]) as Map
        Map ut = (tests.unitTests ?: [:]) as Map
        if (ut.get(toolKey) instanceof Map) {
            Map dnUt = [:] + (ut.get(toolKey) as Map)
            boolean utChanged = false
            String rawRunner = ((dnUt.runner ?: dnUt.testRunner ?: '') as String).trim()
            if (rawRunner && !isValidDotnetRunner(rawRunner)) {
                script.echo "[WARN] Invalid .NET test runner '${rawRunner}' in configuration. Falling back to auto-detected runner 'auto'."
                dnUt.runner = 'auto'
                dnUt.remove('testRunner')
                utChanged = true
            }
            if (dnUt.coverageTool && !isValidDotnetCoverage(dnUt.coverageTool as String)) {
                script.echo "[WARN] Invalid .NET coverageTool '${dnUt.coverageTool}' in configuration. Falling back to auto-detected coverage tool 'auto'."
                dnUt.coverageTool = 'auto'
                utChanged = true
            }
            if (utChanged) {
                Map newUt = [:] + ut
                newUt[toolKey] = dnUt
                Map newTests = [:] + tests
                newTests.unitTests = newUt
                cfg.tests = newTests
            }
        }
    }

    private String detectDotnetModeFromRepo(String sd) {
        if (!script.isUnix()) {
            if (existsIn(sd, 'packages.config')) return 'msbuild'
            for (String glob : ['*.csproj', '**/*.csproj', '*.fsproj', '**/*.fsproj', '*.vbproj', '**/*.vbproj']) {
                for (String p : findPaths(sd ? "${sd}/${glob}" : glob)) {
                    String text = readFileQuiet(p)
                    if (text.contains('<TargetFrameworkVersion>') || text =~ /(?i)<TargetFrameworks?>\s*net4[0-9]/) return 'msbuild'
                    if (text.contains('Sdk=')) return 'dotnet'
                }
            }
            return 'msbuild'
        }
        return 'dotnet'
    }

    @NonCPS
    private static boolean isValidDotnetMode(String mode) {
        return (mode ?: '').trim().toLowerCase() in ['auto', 'dotnet', 'cli', 'sdk', 'dotnet-cli', 'msbuild', 'classic', 'framework', 'vs', 'visualstudio', 'netfx', 'hybrid', 'cake', 'nuke']
    }

    @NonCPS
    private static boolean isValidDotnetRunner(String runner) {
        return (runner ?: '').trim().toLowerCase() in ['auto', 'dotnet', 'cli', 'sdk', 'dotnet-test', 'vstest', 'vstest.console', 'mstest', 'nunit', 'xunit', 'hybrid']
    }

    @NonCPS
    private static boolean isValidDotnetCoverage(String cov) {
        return (cov ?: '').trim().toLowerCase() in ['auto', 'cobertura', 'coverlet', 'xplat', 'opencover', 'dotcover', 'none', 'false', 'off', 'disabled']
    }

    @NonCPS
    private static boolean isValidBoolean(def val) {
        if (val instanceof Boolean) return true
        return (val ?: '').toString().trim().toLowerCase() in ['true', 'false', '1', '0', 'yes', 'no']
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
