import devsecops.test.FakeAbort
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import org.yaml.snakeyaml.Yaml

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')

int failures = 0
Closure check = { String name, boolean ok, Object detail ->
    if (ok) {
        println "PASS  ${name}"
    } else {
        failures++
        println "FAIL  ${name}"
        if (detail) println "      ${detail}"
    }
}

String GRADLE_URL = 'https://tools.bbh.com/nexus/repository/gradle-plugins/'
String MAVEN_URL = 'https://tools.bbh.com/nexus/repository/maven-public/'
Map repositories = [gradle: GRADLE_URL, maven: MAVEN_URL]
String FOLDER = '/ws@tmp/dso-plugin-repositories'
String INIT = "${FOLDER}/bbh-nexus-plugins.gradle"
String SETTINGS = "${FOLDER}/bbh-nexus-plugins-settings.xml"
String MARKER = "${FOLDER}/gradle-ws.used"

String gradleMissing = '''FAILURE: Build failed with an exception.

* Where:
Build file '/ws/build.gradle' line: 2

* What went wrong:
Plugin [id: 'org.springframework.boot', version: '3.3.4'] was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Plugin Repositories (could not resolve plugin artifact 'org.springframework.boot:org.springframework.boot.gradle.plugin:3.3.4')
  Searched in the following repositories:
    Gradle Central Plugin Repository
'''
String gradleClasspath = '''* What went wrong:
A problem occurred configuring root project 'payments'.
> Could not resolve all files for configuration ':classpath'.
   > Could not find com.bbh.gradle:bbh-conventions:2.1.0.
     Searched in the following locations:
'''
String gradleCompile = '''> Task :compileJava FAILED
/ws/src/main/java/App.java:3: error: ';' expected
BUILD SUCCESSFUL in 2s
'''
String mavenMissing = '''[ERROR] Plugin org.jacoco:jacoco-maven-plugin:0.8.12 or one of its dependencies could not be resolved: Could not transfer artifact org.jacoco:jacoco-maven-plugin:pom:0.8.12 from/to central (https://repo.maven.apache.org/maven2): Connect timed out -> [Help 1]
[ERROR] No plugin found for prefix 'spotless' in the current project and in the plugin groups [org.apache.maven.plugins, org.codehaus.mojo] available from the repositories
'''
String ownSettings = '''<?xml version="1.0" encoding="UTF-8"?>
<settings>
  <servers>
    <server><id>bbh-releases</id><username>svc</username></server>
  </servers>
  <profiles>
    <profile><id>team</id></profile>
  </profiles>
</settings>
'''

Closure run = { String tool, Map options ->
    FakeScript j = new FakeScript()
    j.env.vars.putAll([WORKSPACE: '/ws', WORKSPACE_TMP: '/ws@tmp'])
    j.files.putAll((options.files ?: [:]) as Map)
    List<String> builds = []
    Map<String, String> seen = [:]
    j.shHandler = { Map args ->
        String script = args.script as String
        if (args.returnStatus) return 0
        if (script.contains("'help' 2>&1 || true") || script.contains(":resolve-plugins' 2>&1 || true")) {
            builds << 'check'
            return (options.diagnosis ?: '') as String
        }
        if (script.contains('cat "$HOME/.m2/settings.xml"')) return (options.m2 ?: '') as String
        if (script.startsWith('rm -f -- ')) {
            seen.settings = j.files[SETTINGS]
            j.files.remove(SETTINGS)
            return ''
        }
        if (script.contains("'clean'")) {
            boolean withRepository = script.contains('--init-script') || script.contains(' -s ')
            builds << (withRepository ? 'with BBH Nexus' : 'as set')
            if (withRepository) seen.settings = j.files[SETTINGS]
            if (!withRepository && options.failFirst) throw new FakeAbort('script returned exit code 1')
            if (withRepository && options.failSecond) throw new FakeAbort('script returned exit code 1')
        }
        return ''
    }
    SandboxHarness h = new SandboxHarness(srcDir, stubDir)
    String error = ''
    Map cfg = tool == 'gradle' ? [gradle: [tasks: ['clean', 'build'], flags: (options.flags ?: [])]] : [maven: [goals: ['clean', 'install'], flags: (options.flags ?: ['-B'])]]
    try {
        h.run {
            int times = (options.times ?: 1) as int
            for (int i = 0; i < times; i++) {
                h.type('com.bbh.build.BuildRunner').newInstance(j, cfg, tool, options.containsKey('repositories') ? options.repositories : repositories).run()
            }
        }
    } catch (Throwable t) {
        error = SandboxHarness.rejectionOf(t) ?: t.message
    }
    return [j: j, error: error, builds: builds, seen: seen, warnings: j.log.findAll { it.startsWith('[WARN] [BUILD]') || it.startsWith('[BUILD]') }]
}

Map plainRun = run('gradle', [repositories: [:]])
Map found = run('gradle', [:])
check('G1 a Gradle build that finds its plugins runs once as set: the same steps as without the BBH Nexus, no check and no file',
        !found.error && found.builds == ['as set'] && found.j.calls == plainRun.j.calls && !found.j.files.keySet().any { it.contains('dso-plugin-repositories') }
                && found.warnings == [],
        "${found.error} | ${found.builds} | ${found.j.calls} | ${found.j.files.keySet()}")

Map missing = run('gradle', [failFirst: true, diagnosis: gradleMissing, times: 2])
String initScript = (missing.j.files[INIT] ?: '') as String
check('G2 a Gradle plugin that the build does not find: the check names it, the step runs again with the init script of the BBH Nexus, and says where to add the repository',
        !missing.error && missing.builds.take(3) == ['as set', 'check', 'with BBH Nexus']
                && missing.warnings.contains("[WARN] [BUILD] Gradle did not find the plugin(s) org.springframework.boot:3.3.4 in the repositories of the build - trying again with the plugin repository ${GRADLE_URL} of the BBH Nexus".toString())
                && missing.warnings.contains("[WARN] [BUILD] The plugin(s) org.springframework.boot:3.3.4 came from ${GRADLE_URL} of the BBH Nexus: add the repository to pluginManagement { repositories { ... } } of settings.gradle so that the first attempt does not fail".toString())
                && missing.j.calls.any { it.contains("--init-script '${INIT}'") && it.contains('Gradle: clean build (BBH Nexus plugins)') }
                && missing.j.files[MARKER] == "${GRADLE_URL}\n".toString(),
        "${missing.error} | ${missing.builds} | ${missing.warnings} | ${missing.j.files.keySet()}")
check('G3 the init script puts the BBH Nexus first for the plugins of the settings and of every buildscript, and keeps the Gradle Plugin Portal when the settings name none',
        initScript.startsWith("def bbhNexusPlugins = '${GRADLE_URL}'".toString()) && initScript.contains('beforeSettings { settings ->')
                && initScript.contains('repositories.addFirst(bbh)') && initScript.contains('settingsEvaluated { settings ->')
                && initScript.contains("if (repositories.findAll { it.name != 'bbhNexusPlugins' }.isEmpty()) repositories.gradlePluginPortal()")
                && initScript.contains('bbhNexusFirst(project.buildscript.repositories)'),
        initScript)
check('G4 a later Gradle step of the same folder uses the BBH Nexus at once, without a failed first attempt',
        missing.builds.drop(3) == ['with BBH Nexus']
                && missing.warnings.contains("[BUILD] Gradle: clean build: with the plugin repository ${GRADLE_URL} of the BBH Nexus, which an earlier step of this build needed".toString()),
        "${missing.builds} | ${missing.warnings}")

Map classpath = run('gradle', [failFirst: true, diagnosis: gradleClasspath])
check('G5 a buildscript classpath module that is missing counts as a missing plugin',
        !classpath.error && classpath.builds == ['as set', 'check', 'with BBH Nexus']
                && classpath.warnings.any { it.contains('did not find the plugin(s) com.bbh.gradle:bbh-conventions:2.1.0 in the repositories of the build') },
        "${classpath.error} | ${classpath.builds} | ${classpath.warnings}")

Map compile = run('gradle', [failFirst: true, diagnosis: gradleCompile])
check('G6 a failure that is not a missing plugin fails as before, after one check, and the step is not run again',
        compile.error == 'script returned exit code 1' && compile.builds == ['as set', 'check'] && compile.warnings == []
                && !compile.j.files.containsKey(MARKER),
        "${compile.error} | ${compile.builds} | ${compile.warnings}")

Map stillMissing = run('gradle', [failFirst: true, failSecond: true, diagnosis: gradleMissing])
check('G7 when the BBH Nexus does not help either, the step fails with the error of the second run and says so',
        stillMissing.error == 'script returned exit code 1' && stillMissing.builds == ['as set', 'check', 'with BBH Nexus']
                && stillMissing.warnings.contains("[BUILD] Gradle failed with the plugin repository ${GRADLE_URL} of the BBH Nexus too - the build fails".toString())
                && !stillMissing.j.files.containsKey(MARKER),
        "${stillMissing.error} | ${stillMissing.builds} | ${stillMissing.warnings}")

Map off = run('gradle', [failFirst: true, diagnosis: gradleMissing, repositories: [:]])
Map noGradle = run('gradle', [failFirst: true, diagnosis: gradleMissing, repositories: [gradle: '', maven: MAVEN_URL]])
check('G8 without a Gradle repository (pluginRepositories.enabled: false, or gradle empty) a failed step fails as before, without a check',
        off.error == 'script returned exit code 1' && off.builds == ['as set'] && noGradle.error == 'script returned exit code 1' && noGradle.builds == ['as set'],
        "${off.builds} | ${noGradle.builds}")

Map maven = run('maven', [failFirst: true, diagnosis: mavenMissing, m2: ownSettings])
String merged = (maven.seen.settings ?: '') as String
check('M1 a Maven plugin that the build does not find: the step runs again with a copy of the user settings that adds the BBH Nexus in an active profile, and the copy is removed',
        !maven.error && maven.builds == ['as set', 'check', 'with BBH Nexus']
                && maven.warnings.contains("[WARN] [BUILD] Maven did not find the plugin(s) org.jacoco:jacoco-maven-plugin:0.8.12, prefix spotless in the repositories of the build - trying again with the plugin repository ${MAVEN_URL} of the BBH Nexus".toString())
                && maven.warnings.any { it.contains('add the repository to <pluginRepositories> of pom.xml') }
                && maven.j.calls.any { it.contains("-s '${SETTINGS}'") && it.contains('Maven: clean install (BBH Nexus plugins)') }
                && maven.j.calls.any { it.contains("maven-dependency-plugin:3.8.1:resolve-plugins' 2>&1 || true") }
                && merged.contains('<server><id>bbh-releases</id><username>svc</username></server>') && merged.contains('<profile><id>team</id></profile>')
                && merged.contains("<url>${MAVEN_URL}</url>".toString()) && merged.contains('<activeProfiles>\n    <activeProfile>bbh-nexus-plugins</activeProfile>\n  </activeProfiles>\n</settings>')
                && merged.indexOf('<id>bbh-nexus-plugins</id>') < merged.indexOf('<profile><id>team</id></profile>')
                && !maven.j.files.containsKey(SETTINGS),
        "${maven.error} | ${maven.builds} | ${maven.warnings} | ${merged}")

Map mavenNew = run('maven', [failFirst: true, diagnosis: mavenMissing, m2: ''])
String created = (mavenNew.seen.settings ?: '') as String
check('M2 without user settings the settings file is new and holds only the profile of the BBH Nexus',
        !mavenNew.error && created.startsWith('<?xml version="1.0" encoding="UTF-8"?>\n<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">')
                && created.contains("<url>${MAVEN_URL}</url>".toString()) && created.contains('<activeProfile>bbh-nexus-plugins</activeProfile>'),
        created)

Map ownFile = run('maven', [failFirst: true, diagnosis: mavenMissing, flags: ['-B', '-s', 'ci/settings.xml']])
check('M3 a Maven step with its own settings file is not run again: the console names the plugins and the repository to add to that file',
        ownFile.error == 'script returned exit code 1' && ownFile.builds == ['as set', 'check']
                && ownFile.warnings.contains("[WARN] [BUILD] Maven did not find the plugin(s) org.jacoco:jacoco-maven-plugin:0.8.12, prefix spotless in the repositories of the build, which sets its own settings file - add the plugin repository ${MAVEN_URL} of the BBH Nexus to it".toString()),
        "${ownFile.builds} | ${ownFile.warnings}")

SandboxHarness harness = new SandboxHarness(srcDir, stubDir)
def utils = harness.type('com.bbh.utils.BuildUtils')
Closure inSandbox = { Closure body -> harness.run(body) }
Map defaults = (new Yaml().load(new File(root, 'resources/com/bbh/devsecops/defaults.yaml').getText('UTF-8')) as Map).defaults as Map
Map u = [:]
inSandbox {
    u.bundled = utils.pluginRepositories(defaults)
    u.off = utils.pluginRepositories([pluginRepositories: [enabled: false, gradle: GRADLE_URL]])
    u.offText = utils.pluginRepositories([pluginRepositories: [enabled: 'false']])
    u.none = utils.pluginRepositories([:])
    u.fallback = utils.pluginRepositories([:], [pluginRepositories: [gradle: GRADLE_URL]])
    u.ownWins = utils.pluginRepositories([pluginRepositories: [enabled: false]], defaults)
    u.gradle = utils.missingPlugins('gradle', gradleMissing)
    u.gradleNoVersion = utils.missingPlugins('gradle', "Plugin [id: 'com.github.ben-manes.versions'] was not found in any of the following sources:")
    u.gradleClasspath = utils.missingPlugins('gradle', gradleClasspath)
    u.gradleCompile = utils.missingPlugins('gradle', gradleCompile)
    u.gradleEmpty = utils.missingPlugins('gradle', '')
    u.maven = utils.missingPlugins('maven', mavenMissing)
    u.mavenDescriptor = utils.missingPlugins('maven', '[ERROR] Failed to retrieve plugin descriptor for org.example:demo-maven-plugin:1.0: Could not find artifact')
    u.mavenCompile = utils.missingPlugins('maven', '[ERROR] COMPILATION ERROR :')
}
check('U1 the bundled defaults hold the plugin repositories of the BBH Nexus, switched on; the settings of the run win over the library defaults, which apply only without them',
        u.bundled == [gradle: GRADLE_URL, maven: MAVEN_URL] && u.off == [:] && u.offText == [:] && u.none == [gradle: '', maven: '']
                && u.fallback == [gradle: GRADLE_URL, maven: ''] && u.ownWins == [:],
        u)
check('U2 a missing plugin is told apart from other failures',
        u.gradle == ['org.springframework.boot:3.3.4'] && u.gradleNoVersion == ['com.github.ben-manes.versions']
                && u.gradleClasspath == ['com.bbh.gradle:bbh-conventions:2.1.0'] && u.gradleCompile == [] && u.gradleEmpty == []
                && u.maven == ['org.jacoco:jacoco-maven-plugin:0.8.12', 'prefix spotless'] && u.mavenDescriptor == ['org.example:demo-maven-plugin:1.0']
                && u.mavenCompile == [],
        u)

List<String> callers = ['src/com/bbh/build/BuildService.groovy', 'src/com/bbh/scanner/SonarService.groovy', 'src/com/bbh/scanner/AppScanService.groovy']
Map<String, Integer> passing = callers.collectEntries { [(it): (new File(root, it).text =~ /new BuildRunner\([^\n]*BuildUtils\.pluginRepositories\(state\.cfg(, state\.libraryDefaults)?\)\)/).size()] }
check('U3 the build, the unit tests, SonarQube and the SAST compile give the plugin repositories of the service to every Gradle and Maven step',
        passing['src/com/bbh/build/BuildService.groovy'] == 2 && passing['src/com/bbh/scanner/SonarService.groovy'] == 1 && passing['src/com/bbh/scanner/AppScanService.groovy'] == 1,
        passing)

println "\n${failures == 0 ? 'ALL PLUGIN REPOSITORY SCENARIOS PASSED' : failures + ' PLUGIN REPOSITORY SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
