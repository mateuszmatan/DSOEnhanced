package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class RepoStackAutoResolver implements Serializable {

    @NonCPS
    static List<String> flagKeys() {
        return ['autoresolveParameters', 'autoresolve parameters', 'autoResolveParameters', 'autoresolve', 'autoResolve']
    }

    @NonCPS
    static List<String> nodeDirs() {
        return ['frontend', 'web', 'client', 'ui', 'webapp', 'src/main/frontend', 'src/main/webapp']
    }

    @NonCPS
    static List<String> changelogs() {
        return ['changelog.xml', 'changelog.yaml', 'changelog.yml', 'changelog.json', 'changelog.sql',
                'db/changelog.xml', 'db/changelog.yaml', 'db/changelog.sql', 'db/changelog/db.changelog-master.xml',
                'db/changelog/db.changelog-master.yaml', 'sql/changelog.xml', 'sql/changelog.sql', 'liquibase/changelog.xml',
                'src/main/resources/db/changelog/db.changelog-master.xml',
                'src/main/resources/db/changelog/db.changelog-master.yaml', 'src/main/resources/changelog.xml']
    }

    private final def script

    RepoStackAutoResolver(def script) {
        this.script = script
    }

    @NonCPS
    static boolean enabled(Map own, Map defaults) {
        for (String key : flagKeys()) {
            if (own != null && own.containsKey(key)) return BuildUtils.booleanValue(own[key], true)
        }
        for (String key : flagKeys()) {
            if (defaults != null && defaults.containsKey(key)) return BuildUtils.booleanValue(defaults[key], true)
        }
        return true
    }

    @NonCPS
    static boolean has(Map source, String path) {
        def node = source
        for (String part : path.tokenize('.')) {
            if (!(node instanceof Map) || !(node as Map).containsKey(part)) return false
            node = (node as Map)[part]
        }
        if (node == null) return false
        if (node instanceof CharSequence) return node.toString().trim() as boolean
        if (node instanceof Collection || node instanceof Map) return !node.isEmpty()
        return true
    }

    @NonCPS
    static def valueAt(Map source, String path) {
        def node = source
        for (String part : path.tokenize('.')) {
            if (!(node instanceof Map)) return null
            node = (node as Map)[part]
        }
        return node
    }

    @NonCPS
    static void put(Map cfg, String path, def value) {
        List<String> parts = path.tokenize('.')
        Map node = cfg
        for (int i = 0; i < parts.size() - 1; i++) {
            def child = node[parts[i]]
            Map copy = child instanceof Map ? [:] + (child as Map) : [:]
            node[parts[i]] = copy
            node = copy
        }
        node[parts[parts.size() - 1]] = value
    }

    @NonCPS
    static String nodeEngine(String packageJson) {
        def block = packageJson =~ /(?s)"engines"\s*:\s*\{([^}]*)\}/
        if (!block.find()) return ''
        def node = block.group(1) =~ /"node"\s*:\s*"([^"]+)"/
        if (!node.find()) return ''
        def major = node.group(1) =~ /(\d+)/
        return major.find() ? major.group(1) : ''
    }

    @NonCPS
    static List<String> nexusIqPatterns(String tool, boolean node, String nodeDir) {
        List<String> patterns = []
        if (tool == 'maven') patterns << '**/target/*.jar'
        if (tool == 'gradle') patterns << '**/build/libs/*.jar'
        if (tool == 'flutter') patterns << 'pubspec.lock'
        if (tool in ['npm', 'node']) patterns << 'package-lock.json'
        if (node && !(tool in ['npm', 'node'])) patterns << (nodeDir && nodeDir != '.' ? "${nodeDir}/package-lock.json".toString() : 'package-lock.json')
        return patterns
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

        resolveBuildToolDirect(cfg)
        resolveNodeStage0(cfg)
        resolveDeployTarget(cfg, serviceName)
        resolveLiquibaseDirect(cfg)
        resolveFlutterDirect(cfg)
        resolveSonarDirect(cfg, serviceName)

        return cfg
    }

    private void resolveBuildToolDirect(Map cfg) {
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
            String pom = readQuiet('pom.xml')
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
        String pkgContent = readQuiet(foundPkgJson)
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

    private void resolveLiquibaseDirect(Map cfg) {
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

    private void resolveFlutterDirect(Map cfg) {
        if (cfg.buildTool != 'flutter') return
        Map fl = [:] + ((cfg.flutter ?: [:]) as Map)
        if (!fl.platform) fl.platform = 'apk'
        cfg.flutter = fl
    }

    private void resolveSonarDirect(Map cfg, String serviceName) {
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

    Map resolve(Map defaults, Map own, Map merged, String serviceName) {
        Map cfg = [:] + ((merged ?: [:]) as Map)
        Map ownCfg = (own ?: [:]) as Map
        List<Map> resolved = []
        if (!enabled(ownCfg, defaults ?: [:])) {
            script.echo "[AUTO] ${serviceName}: autoresolveParameters is false - only the values of ${serviceName} and the library defaults are used"
            return [cfg: cfg, resolved: resolved]
        }

        String base = baseDir(ownCfg)
        String tool = resolveBuildTool(cfg, ownCfg, base, resolved)
        resolveJdk(cfg, ownCfg, tool, resolved)
        resolveBuildCommands(cfg, ownCfg, tool, base, resolved)
        Map node = resolveNode(cfg, ownCfg, tool, base, resolved)
        resolveLiquibase(cfg, ownCfg, tool, base, resolved)
        resolveFlutter(cfg, ownCfg, tool, resolved)
        resolveSonar(cfg, ownCfg, serviceName, resolved)
        resolveNexusIq(cfg, ownCfg, tool, node, resolved)
        suggestDeployTarget(ownCfg, base, serviceName)

        if (!BuildUtils.databaseBlock(cfg)) {
            for (Map r : resolved) {
                script.echo "[AUTO] ${serviceName}: ${r.field} = ${r.value} (${r.reason})"
            }
        }
        return [cfg: cfg, resolved: resolved]
    }

    private String baseDir(Map own) {
        String dir = ((own.sourceDir ?: '') as String).trim().replaceAll('/+$', '')
        return (!dir || dir == '.') ? '' : dir
    }

    private String at(String base, String file) {
        return base ? "${base}/${file}".toString() : file
    }

    private boolean exists(String base, String file) {
        if (base && script.fileExists(at(base, file))) return true
        return script.fileExists(file)
    }

    private String found(String base, String file) {
        if (base && script.fileExists(at(base, file))) return at(base, file)
        return script.fileExists(file) ? file : ''
    }

    private String readQuiet(String path) {
        if (!path) return ''
        try {
            return (script.readFile(path) ?: '') as String
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return ''
        }
    }

    private String resolveBuildTool(Map cfg, Map own, String base, List<Map> resolved) {
        String explicit = ((own.buildTool ?: '') as String).trim()
        if (explicit) return explicit
        String tool = ''
        String reason = ''
        if (exists(base, 'pubspec.yaml')) {
            tool = 'flutter'; reason = 'pubspec.yaml found'
        } else if (exists(base, 'pom.xml')) {
            tool = 'maven'; reason = 'pom.xml found'
        } else if (exists(base, 'build.gradle') || exists(base, 'build.gradle.kts') || exists(base, 'settings.gradle') || exists(base, 'settings.gradle.kts')) {
            tool = 'gradle'; reason = exists(base, 'build.gradle.kts') ? 'build.gradle.kts found (Kotlin DSL)' : 'Gradle build script found'
        } else if (exists(base, 'package.json')) {
            tool = 'npm'; reason = exists(base, 'angular.json') ? 'package.json and angular.json found (Angular)' : 'package.json found (Node.js)'
        } else if (changelog(base)) {
            if (!BuildUtils.databaseBlock(cfg)) {
                tool = 'liquibase'; reason = "Liquibase changelog ${changelog(base)} found"
            }
        }
        if (!tool) {
            if (exists(base, 'requirements.txt') || exists(base, 'pyproject.toml') || exists(base, 'setup.py')) {
                script.echo "[AUTO] Python project detected - set buildTool for the service, the library builds Gradle, Maven, npm, Flutter and Liquibase"
            }
            return ((cfg.buildTool ?: '') as String).trim()
        }
        if (tool != cfg.buildTool) {
            cfg.buildTool = tool
            resolved << [field: 'buildTool', value: tool, reason: reason]
        }
        return tool
    }

    private String changelog(String base) {
        for (String c : changelogs()) {
            String f = found(base, c)
            if (f) return f
        }
        return found(base, 'liquibase.properties')
    }

    private void resolveJdk(Map cfg, Map own, String tool, List<Map> resolved) {
        if (!(tool in ['maven', 'gradle'])) return
        if (has(own, 'javaPath') || own.containsKey('buildToolAutoSetup') || has(cfg, 'javaPath') || cfg.buildToolAutoSetup != null) return
        cfg.buildToolAutoSetup = true
        resolved << [field: 'buildToolAutoSetup', value: true, reason: "no javaPath - the JDK${tool == 'gradle' ? ' and Gradle' : ' and Maven'} version are read from the build files"]
    }

    private void resolveBuildCommands(Map cfg, Map own, String tool, String base, List<Map> resolved) {
        if (tool == 'gradle') {
            if (!has(own, 'build.gradle.tasks') && !has(cfg, 'build.gradle.tasks')) {
                put(cfg, 'build.gradle.tasks', ['clean', 'assemble'])
                resolved << [field: 'build.gradle.tasks', value: '[clean, assemble]', reason: 'Gradle build without tests']
            }
            if (!has(own, 'tests.unitTests.gradle.tasks') && !has(cfg, 'tests.unitTests.gradle.tasks')) {
                String gradleScript = readQuiet(found(base, 'build.gradle')) + readQuiet(found(base, 'build.gradle.kts'))
                List tasks = gradleScript.contains('jacoco') ? ['test', 'jacocoTestReport'] : ['test']
                put(cfg, 'tests.unitTests.gradle.tasks', tasks)
                resolved << [field: 'tests.unitTests.gradle.tasks', value: "[${tasks.join(', ')}]".toString(), reason: gradleScript.contains('jacoco') ? 'JaCoCo plugin found' : 'Gradle test task']
            }
        } else if (tool == 'maven') {
            if (!has(own, 'build.maven.goals') && !has(cfg, 'build.maven.goals')) {
                put(cfg, 'build.maven.goals', ['clean', 'package'])
                if (!has(cfg, 'build.maven.flags')) put(cfg, 'build.maven.flags', ['-B', '-DskipTests'])
                resolved << [field: 'build.maven.goals', value: '[clean, package] -DskipTests', reason: 'Maven build without tests']
            }
            if (!has(own, 'tests.unitTests.maven.goals') && !has(cfg, 'tests.unitTests.maven.goals')) {
                String pom = readQuiet(found(base, 'pom.xml'))
                List goals = pom.contains('jacoco-maven-plugin') ? ['test', 'jacoco:report'] : ['test']
                put(cfg, 'tests.unitTests.maven.goals', goals)
                resolved << [field: 'tests.unitTests.maven.goals', value: "[${goals.join(', ')}]".toString(), reason: pom.contains('jacoco-maven-plugin') ? 'jacoco-maven-plugin found' : 'Maven test phase']
            }
        }
    }

    private Map resolveNode(Map cfg, Map own, String tool, String base, List<Map> resolved) {
        if (BuildUtils.databaseBlock(cfg)) return [enabled: false]
        String nodeDir = ''
        String packageJson = ''
        if (tool in ['npm', 'node']) {
            packageJson = found(base, 'package.json')
            nodeDir = base ?: '.'
        } else if (tool in ['maven', 'gradle']) {
            for (String d : nodeDirs()) {
                String p = found(base, "${d}/package.json".toString())
                if (p) {
                    packageJson = p
                    nodeDir = p.substring(0, p.length() - '/package.json'.length())
                    break
                }
            }
            if (!packageJson && script.fileExists('package.json')) {
                packageJson = 'package.json'
                nodeDir = '.'
            }
        }
        if (!packageJson) return [enabled: false]
        String content = readQuiet(packageJson)
        String engine = nodeEngine(content)
        if (engine) script.echo "[AUTO] ${packageJson} asks for Node.js ${engine} (engines.node) - build.node.installationName or build.node.nodePath selects it on the agent"
        if (tool in ['maven', 'gradle'] && !has(own, 'build.node.enabled') && valueAt(cfg, 'build.node.enabled') == null) {
            put(cfg, 'build.node.enabled', true)
            resolved << [field: 'build.node.enabled', value: true, reason: "${packageJson} found next to the ${tool == 'maven' ? 'Maven' : 'Gradle'} build (Node.js build first)"]
            if (!has(own, 'build.node.dir') && !has(cfg, 'build.node.dir') && nodeDir != '.') {
                put(cfg, 'build.node.dir', nodeDir)
                resolved << [field: 'build.node.dir', value: nodeDir, reason: 'folder of package.json']
            }
        } else if (tool in ['npm', 'node'] && nodeDir != '.' && !has(own, 'build.node.dir') && !has(cfg, 'build.node.dir')) {
            put(cfg, 'build.node.dir', nodeDir)
            resolved << [field: 'build.node.dir', value: nodeDir, reason: 'folder of package.json']
        }
        boolean cypressConfig = false
        for (String f : ['cypress.config.js', 'cypress.config.ts', 'cypress.config.mjs', 'cypress.config.cjs', 'cypress.json']) {
            if (script.fileExists(nodeDir == '.' ? f : "${nodeDir}/${f}".toString())) cypressConfig = true
        }
        if (cypressConfig && content.contains('"cypress"') && !has(own, 'tests.unitTests.cypress.enabled') && valueAt(cfg, 'tests.unitTests.cypress.enabled') == null) {
            put(cfg, 'tests.unitTests.cypress.enabled', true)
            resolved << [field: 'tests.unitTests.cypress.enabled', value: true, reason: 'cypress in package.json and a Cypress configuration file']
            if (nodeDir != '.' && !has(cfg, 'tests.unitTests.cypress.dir')) put(cfg, 'tests.unitTests.cypress.dir', nodeDir)
        } else if (content.contains('"jest"')) {
            script.echo "[AUTO] ${packageJson} uses Jest - its LCOV report is read by the Unit tests stage"
        }
        return [enabled: true, dir: nodeDir]
    }

    private void resolveLiquibase(Map cfg, Map own, String tool, String base, List<Map> resolved) {
        if (tool != 'liquibase') return
        if (cfg.database instanceof Map || BuildUtils.databaseBlock(cfg)) return
        String file = changelog(base)
        if (!file || file.endsWith('liquibase.properties')) return
        if (!has(own, 'build.liquibase') && !has(cfg, 'build.liquibase.commands') && !has(cfg, 'build.liquibase.flags')) {
            put(cfg, 'build.liquibase.commands', ['validate'])
            put(cfg, 'build.liquibase.flags', ["--changelog-file=${file}".toString()])
            resolved << [field: 'build.liquibase.flags', value: "--changelog-file=${file}".toString(), reason: 'Liquibase changelog found']
        }
        if (!has(own, 'tests.unitTests.liquibase') && !has(cfg, 'tests.unitTests.liquibase.commands')) {
            put(cfg, 'tests.unitTests.liquibase.commands', ['validate'])
            put(cfg, 'tests.unitTests.liquibase.flags', ["--changelog-file=${file}".toString()])
            resolved << [field: 'tests.unitTests.liquibase.commands', value: '[validate]', reason: 'Liquibase changelog found']
        }
    }

    private void resolveFlutter(Map cfg, Map own, String tool, List<Map> resolved) {
        if (tool != 'flutter') return
        if (has(own, 'flutter.platform') || has(cfg, 'flutter.platform')) return
        put(cfg, 'flutter.platform', 'apk')
        resolved << [field: 'flutter.platform', value: 'apk', reason: 'Flutter default platform']
    }

    private void resolveSonar(Map cfg, Map own, String serviceName, List<Map> resolved) {
        if (has(own, 'tools.sonar.projectKey') || has(cfg, 'tools.sonar.projectKey')) return
        String name = ((own.appName ?: serviceName ?: 'service') as String).trim()
        String key = name.startsWith('com.bbh:') ? name : "com.bbh:${name}".toString()
        put(cfg, 'tools.sonar.projectKey', key)
        resolved << [field: 'tools.sonar.projectKey', value: key, reason: 'com.bbh: and the service name']
    }

    private void resolveNexusIq(Map cfg, Map own, String tool, Map node, List<Map> resolved) {
        if (!has(cfg, 'tools.nexusIq.application')) return
        if (has(own, 'tools.nexusIq.scanPatterns') || has(cfg, 'tools.nexusIq.scanPatterns')) return
        if (valueAt(cfg, 'tools.nexusIq.application') instanceof Map) return
        List<String> patterns = nexusIqPatterns(tool, node.enabled as boolean, (node.dir ?: '') as String)
        if (!patterns) return
        put(cfg, 'tools.nexusIq.scanPatterns', patterns)
        resolved << [field: 'tools.nexusIq.scanPatterns', value: "[${patterns.join(', ')}]".toString(), reason: "artifacts of the ${tool} build"]
    }

    private void suggestDeployTarget(Map own, String base, String serviceName) {
        if (BuildUtils.databaseBlock(own)) return
        if (has(own, 'deployTarget')) return
        if (exists(base, 'Dockerfile') || exists(base, 'Containerfile') || exists(base, 'openshift')) {
            script.echo "[AUTO] ${serviceName}: a Dockerfile or OpenShift folder was found - set deployTarget: openshift for the service to deploy to OpenShift (the deploy target is never changed automatically)"
        }
    }
}
