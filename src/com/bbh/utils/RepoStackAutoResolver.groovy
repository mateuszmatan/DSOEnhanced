package com.bbh.utils

class RepoStackAutoResolver implements Serializable {
    private final def script

    RepoStackAutoResolver(def script) {
        this.script = script
    }

    Map resolve(Map currentCfg, String serviceName = '') {
        Map cfg = currentCfg != null ? [:] + currentCfg : [:]
        def flag = cfg.get('autoresolve parameters')
        if (flag == null) flag = cfg.autoresolveParameters
        if (flag == null) flag = cfg.autoresolve
        if (flag == null) flag = cfg.autoResolve
        if (flag == null) flag = cfg.autoResolveParameters
        boolean enabled = BuildUtils.booleanValue(flag != null ? flag : true, true)
        if (!enabled) return cfg

        resolveBuildTool(cfg)
        resolveNodeStage0(cfg)
        resolveDeployTarget(cfg, serviceName)
        resolveLiquibase(cfg)
        resolveFlutter(cfg)
        resolvePip(cfg)
        resolveSonar(cfg, serviceName)

        return cfg
    }

    private void resolveBuildTool(Map cfg) {
        String existing = ((cfg.buildTool ?: '') as String).trim()
        if (existing) {
            checkJdkAutoSetup(cfg, existing)
            return
        }

        if (script.fileExists('pubspec.yaml')) {
            cfg.buildTool = 'flutter'
            return
        }

        if (isLiquibasePresent()) {
            if (!BuildUtils.databaseBlock([buildTool: 'liquibase', database: cfg.database])) cfg.buildTool = 'liquibase'
            return
        }

        if (script.fileExists('pom.xml')) {
            cfg.buildTool = 'maven'
            checkJdkAutoSetup(cfg, 'maven')
            return
        }

        if (script.fileExists('build.gradle') || script.fileExists('build.gradle.kts') || script.fileExists('gradle/wrapper/gradle-wrapper.properties')) {
            cfg.buildTool = 'gradle'
            checkJdkAutoSetup(cfg, 'gradle')
            return
        }

        if (isPipPresent()) {
            cfg.buildTool = 'pip'
            return
        }
    }

    private void checkJdkAutoSetup(Map cfg, String tool) {
        if (cfg.javaPath || cfg.buildToolAutoSetup != null) return

        if (tool == 'maven' && script.fileExists('pom.xml')) {
            String pom = readFileQuiet('pom.xml')
            if (pom =~ /<java\.version>|<maven\.compiler\.source>|<maven\.compiler\.target>|<release>/) {
                cfg.buildToolAutoSetup = true
            }
        } else if (tool == 'gradle') {
            if (script.fileExists('gradle/wrapper/gradle-wrapper.properties') || script.fileExists('build.gradle') || script.fileExists('build.gradle.kts')) {
                cfg.buildToolAutoSetup = true
            }
        }
    }

    private void resolveNodeStage0(Map cfg) {
        if (BuildUtils.databaseBlock(cfg)) return
        Map build = (cfg.build ?: [:]) as Map
        Map node = (build.node ?: [:]) as Map
        if (node.enabled != null) return

        List<String> dirs = ['.', 'frontend', 'web', 'client', 'ui']
        String foundDir = null
        String foundPkgJson = null

        for (String d : dirs) {
            String p = d == '.' ? 'package.json' : "${d}/package.json"
            if (script.fileExists(p)) {
                foundDir = d
                foundPkgJson = p
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
                Map tests = [:] + ((cfg.tests ?: [:]) as Map)
                Map unitTests = [:] + ((tests.unitTests ?: [:]) as Map)
                Map nodeTests = [:] + ((unitTests.node ?: [:]) as Map)
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

    private void resolveDeployTarget(Map cfg, String serviceName) {
        if (BuildUtils.databaseBlock(cfg)) return
        String existing = ((cfg.deployTarget ?: '') as String).trim()
        if (existing) return

        if (script.fileExists('Dockerfile') || script.fileExists('Containerfile') ||
                script.fileExists('openshift') || script.fileExists('k8s') || script.fileExists('helm')) {
            cfg.deployTarget = 'openshift'
            if (!cfg.appName) {
                cfg.appName = serviceName ?: 'app'
            }
        }
    }

    private void resolveLiquibase(Map cfg) {
        if (cfg.buildTool != 'liquibase' || BuildUtils.databaseBlock(cfg)) return

        String changelog = detectChangelogPath()
        Map build = [:] + ((cfg.build ?: [:]) as Map)
        Map liqBuild = [:] + ((build.liquibase ?: [:]) as Map)
        if (!liqBuild.commands) liqBuild.commands = ['updateSQL']
        if (!liqBuild.flags && changelog) liqBuild.flags = ["--changelog-file=${changelog}"]
        build.liquibase = liqBuild
        cfg.build = build

        Map tests = [:] + ((cfg.tests ?: [:]) as Map)
        Map unitTests = [:] + ((tests.unitTests ?: [:]) as Map)
        Map liqTests = [:] + ((unitTests.liquibase ?: [:]) as Map)
        if (!liqTests.commands) liqTests.commands = ['validate']
        if (!liqTests.flags && changelog) liqTests.flags = ["--changelog-file=${changelog}"]
        unitTests.liquibase = liqTests
        tests.unitTests = unitTests
        cfg.tests = tests
    }

    private String detectChangelogPath() {
        List<String> candidates = [
                'changelog.xml', 'liquibase.properties', 'db/changelog.xml',
                'src/main/resources/changelog.xml', 'src/main/resources/db/changelog/db.changelog-master.xml',
                'db/changelog/db.changelog-master.xml', 'sql/changelog.xml'
        ]
        for (String c : candidates) {
            if (script.fileExists(c)) return c
        }
        return 'src/main/resources/changelog.xml'
    }

    private boolean isLiquibasePresent() {
        return ['liquibase.properties', 'changelog.xml', 'changelog.yaml', 'changelog.yml', 'changelog.json',
                'db/changelog.xml', 'db/changelog/db.changelog-master.xml',
                'src/main/resources/db/changelog/db.changelog-master.xml',
                'src/main/resources/changelog.xml'].any { String f -> script.fileExists(f) }
    }

    private boolean isPipPresent() {
        return ['requirements.txt', 'pyproject.toml', 'setup.py', 'Pipfile'].any { String f -> script.fileExists(f) }
    }

    private void resolveFlutter(Map cfg) {
        if (cfg.buildTool != 'flutter') return
        Map fl = [:] + ((cfg.flutter ?: [:]) as Map)
        if (!fl.platform) fl.platform = 'apk'
        cfg.flutter = fl
    }

    private void resolvePip(Map cfg) {
        if (cfg.buildTool != 'pip') return
    }

    private void resolveSonar(Map cfg, String serviceName) {
        Map tools = [:] + ((cfg.tools ?: [:]) as Map)
        Map sonar = [:] + ((tools.sonar ?: [:]) as Map)
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
