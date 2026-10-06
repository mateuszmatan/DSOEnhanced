package com.bbh.utils

class FlutterUtils implements Serializable {

    static def initializeFlutter(Map cfg, def steps) {
        flutterVersion(steps)
        readVersionToEnv(steps)
        prepareFlutterModules(cfg, steps)
    }

    static def flutterVersion(def steps) {
        try {
            def versionOutput = steps.sh(script: "flutter --version --no-version-check", returnStdout: true).trim()
            steps.echo "Flutter Version Info:"
            steps.echo versionOutput
            return versionOutput

        } catch (Exception e) {
            steps.error "Failed to fetch Flutter version: ${e.message}"
        }
    }

    static def readVersionToEnv(def steps) {
        try{
            steps.env.version = steps.sh(script: '''awk '/version: /' pubspec.yaml | cut -d ":" -f 2 | xargs''', returnStdout: true).trim()
            steps.echo steps.env.version
            def readVersion = steps.env.version
            return readVersion
        } catch (Exception e) {
            steps.error "Failed to get the version: ${e.message}"
        }
    }

    static def prepareFlutterModules(Map cfg, def steps) {
        def modules = cfg.tools.flutter.flutterModules
        steps.echo "Preparing flutter for modules: ${modules}"
        steps.sh 'flutter pub get'
        for (String module : modules) {
            steps.sh "cd modules/${module} && flutter pub get && dart run build_runner build -r"
        }
        steps.sh 'flutter pub run build_runner build -r'
    }

    static def unitTestsFlutter(Map cfg, def steps) {
        runFullTestSuite(cfg, steps)
    }


    static void runFullTestSuite(def cfg, def steps) {
        steps.echo "Running tests for modules: ${cfg.tests.modules[0]} and ${cfg.tests.modules[1]}"

        runCodeFormatting(steps)

        try {
            runStaticCodeAnalysis(steps)
            runTestFilesVerification(steps)

            def pluginsLcov = runPluginTests(cfg, steps)
            def modulesLcov = runModuleTests(cfg, steps)
            def mainLcov = runMainTests(steps)

            steps.echo "Running tests finished."

            mergeLcovFiles(pluginsLcov, modulesLcov, mainLcov, steps)
        } catch (Exception e) {
            steps.error "Tests status is: FAILED"
        }

        steps.archiveArtifacts artifacts: "total_lcov.info", fingerprint: true
    }

    static void runCodeFormatting(def steps) {
        steps.echo "Running code formatting."
        steps.sh 'dart format --output=none --set-exit-if-changed .'
        steps.echo "End of code formatting."
    }

    static void runStaticCodeAnalysis(def steps) {
        steps.echo "Running static code analysis."
        def result = steps.sh(script: "flutter analyze --verbose > analysis-report.txt", returnStatus: true)
        steps.archiveArtifacts artifacts: "analysis-report.txt", fingerprint: true
        if (result != 0) {
            throw new Exception("Static code analysis failed")
        }
        steps.echo "End of static code analysis."
    }

    static void runTestFilesVerification(def steps) {
        steps.echo "Running test files verification."
        steps.sh 'chmod +x ./scripts/verify_test_files.sh'
        steps.sh './scripts/verify_test_files.sh --throw true'
        steps.echo "End of test files verification."
    }

    static String runPluginTests(def cfg, def steps) {
        def pluginsLcov = "plugins_lcov.info"
        steps.sh "echo -n > ${pluginsLcov}"
        steps.dir("plugins"){
            for (String pl : cfg.tests.subplugins) {
                FlutterUtils.runFlutterTestWithCoverage(pl, "", "../../${pluginsLcov}", steps)
            }
        }
        return pluginsLcov
    }

    static String runModuleTests(def cfg, def steps) {
        def modulesLcov = "modules_lcov.info"
        steps.sh "echo -n > ${modulesLcov}"
        steps.dir("modules"){
                for (String mod : cfg.tests.submodules) {
                    FlutterUtils.runFlutterTestWithCoverage(mod, "", "../../${modulesLcov}", steps)
                }
        }
        return modulesLcov
    }

    static void runFlutterTestWithCoverage(String subdir, String extraFlags, String targetLcov, def steps) {
        steps.dir(subdir) {
            def flags = extraFlags ? " $extraFlags" : ""
            def result = steps.sh(script: "flutter test --coverage --concurrency=32 --reporter expanded${flags}", returnStatus: true)
            if (result != 0) {
                throw new Exception("Tests failed in: $subdir")
            }
            steps.sh "cat coverage/lcov.info >> ${targetLcov}"
            steps.echo "Coverage merged for: $subdir"
        }
    }

    static String runMainTests(def steps) {
        def mainLcov = "main_lcov.info"
        steps.sh "echo -n > ${mainLcov}"

        def result = steps.sh(script: "flutter test --coverage --concurrency=32 --reporter expanded", returnStatus: true)
        if (result != 0) {
            throw new Exception("Main module tests failed")
        }
        steps.sh "cat coverage/lcov.info >> ${mainLcov}"
        return mainLcov
    }

    static void mergeLcovFiles(String pluginsLcov, String modulesLcov, String mainLcov, def steps) {
        def totalLcov = "total_lcov.info"
        steps.sh "echo -n > ${totalLcov}"
        steps.sh "cat ${pluginsLcov} >> ${totalLcov}"
        steps.sh "cat ${modulesLcov} >> ${totalLcov}"
        steps.sh "cat ${mainLcov}    >> ${totalLcov}"
        steps.echo "All lcov files merged into: $totalLcov"
    }

    static def deliverToNexusAndroid(String flavour = 'qc', String version, def steps, Map cfg, String projectName) {
        String path = "flutter-apk"
        String extension = "apk"
        String releaseVersion = "release"
        def baseConfig = steps.readYaml(file: BuildUtils.runStateFile())
        def now = new Date()
        def nowFormat = now.format("yyyyMMdd-HHmmss", TimeZone.getTimeZone('UTC'))
        def  buildTag= "$version-$nowFormat"
        def file = "app-${flavour}-${releaseVersion}.${extension}"
        def group = cfg.delivery.group
        def artifact = cfg.delivery.artifact
        def plugin = cfg.delivery.plugin
        steps.sh """
        mvn -B ${plugin} -DgroupId=${group} \
        -DartifactId=${artifact}-android -Dversion=${buildTag}-SNAPSHOT -Dpackaging=${extension} \
        -Dfile=build/app/outputs/${path}/${file} -DgeneratePom=false -DrepositoryId=bbh-snapshots \
        -Durl=http://tools.bbh.com/nexus/content/repositories/snapshots/
    """
        baseConfig.projects[projectName].delivery.buildTagAndroid = buildTag
        steps.writeYaml file: BuildUtils.runStateFile(), data: baseConfig, overwrite: true
    }

    static def deliverToNexusIOS(String flavor= 'qc', String version, def steps, Map cfg, String projectName) {
        steps.unstash 'ios_artifact'
        String extension = "ipa"
        String file = "BBH.${extension}"
        def baseConfig = steps.readYaml(file: BuildUtils.runStateFile())
        def now = new Date()
        def nowFormat = now.format("yyyyMMdd-HHmmss", TimeZone.getTimeZone('UTC'))
        def  buildTag= "$version-$nowFormat"
        def group = cfg.delivery.group
        def artifact = cfg.delivery.artifact
        def plugin = cfg.delivery.plugin
        steps.sh """
        mvn -B ${plugin} -DgroupId=${group} \
        -DartifactId=${artifact}-ios -Dversion=${buildTag}-SNAPSHOT -Dpackaging=${extension} \
        -Dfile="build/ios/release/${flavor}/${file}" -DgeneratePom=false -DrepositoryId=bbh-snapshots \
        -Durl=http://tools.bbh.com/nexus/content/repositories/snapshots/
    """
        baseConfig.projects[projectName].delivery.buildTagIOS = buildTag
        steps.writeYaml file: BuildUtils.runStateFile(), data: baseConfig, overwrite: true
    }

}
