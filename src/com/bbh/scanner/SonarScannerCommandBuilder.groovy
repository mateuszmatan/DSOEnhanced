package com.bbh.scanner

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils

class SonarScannerCommandBuilder implements Serializable {

    private final def script
    private final PipelineState state
    private final OsHelper os

    SonarScannerCommandBuilder(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state = state
        this.os = os
    }

    String resolveDotnetReportParam(def configured, List<String> globs) {
        String raw = (configured instanceof Collection ? (configured as Collection).join(',') : (configured ?: '')) as String
        if (raw.trim()) return raw.trim()
        List<String> found = []
        for (String glob : globs) {
            try {
                def files = script.findFiles(glob: glob)
                if (files) {
                    for (def f : files) {
                        String p = (f?.path ?: '') as String
                        if (p && !found.contains(p)) found << p
                    }
                }
            } catch (Exception ignored) {}
        }
        return found.join(',')
    }

    String formatDotnetCmd(List tokens) {
        List nonEmpty = tokens.findAll { (it ?: '').toString().trim() }
        if (os.isWindows()) {
            return nonEmpty.collect { BuildUtils.cmdQuoteIfNeeded(it.toString()) }.join(' ')
        }
        return nonEmpty.collect { BuildUtils.shellQuoteIfNeeded(it.toString()) }.join(' ')
    }

    Map effectiveSonarCfg(Map sonarCfg, String tool) {
        Map toolBlock = [:]
        if (sonarCfg.get(tool) instanceof Map) toolBlock.putAll(sonarCfg.get(tool) as Map)
        boolean changed = false
        if (tool == 'gradle' && !toolBlock.tasks && !toolBlock.commands) {
            toolBlock.tasks = ['sonarqube']
            changed = true
        } else if (tool == 'maven' && !toolBlock.goals && !toolBlock.tasks && !toolBlock.commands) {
            toolBlock.goals = ['sonar:sonar']
            changed = true
        }
        if (sonarCfg.liquibase instanceof Map) {
            Map liqCfg = sonarCfg.liquibase as Map
            List extraFlags = []
            if (liqCfg.sources) {
                String src = (liqCfg.sources instanceof Collection ? (liqCfg.sources as Collection).join(',') : liqCfg.sources) as String
                extraFlags << "-Dsonar.sources=${src}".toString()
            }
            if (liqCfg.inclusions) {
                String inc = (liqCfg.inclusions instanceof Collection ? (liqCfg.inclusions as Collection).join(',') : liqCfg.inclusions) as String
                extraFlags << "-Dsonar.inclusions=${inc}".toString()
            }
            if (liqCfg.exclusions) {
                String exc = (liqCfg.exclusions instanceof Collection ? (liqCfg.exclusions as Collection).join(',') : liqCfg.exclusions) as String
                extraFlags << "-Dsonar.exclusions=${exc}".toString()
            }
            extraFlags.addAll(BuildUtils.normalizeTokens(liqCfg.flags))
            if (extraFlags) {
                List existingFlags = BuildUtils.normalizeTokens(toolBlock.flags)
                for (String flag : extraFlags) {
                    String prefix = flag.contains('=') ? flag.substring(0, flag.indexOf('=') + 1) : flag
                    if (!existingFlags.any { (it as String).startsWith(prefix) }) {
                        existingFlags << flag
                    }
                }
                toolBlock.flags = existingFlags
                changed = true
            }
        }
        if (!changed) return sonarCfg
        Map copy = [:]
        copy.putAll(sonarCfg)
        copy[tool] = toolBlock
        return copy
    }

    String resolveBranch() {
        String raw = ((script.env.CHANGE_BRANCH ?: script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: state.cfg?.scm?.bitbucket?.branch ?: state.cfg?.scm?.bitbucket?.targetBranch ?: state.cfg?.scm?.branch ?: state.cfg?.scm?.targetBranch ?: '') as String).trim()
        return BuildUtils.normalizeBranch(raw)
    }

    String resolveProjectKey(Map sonarCfg) {
        String key = ((sonarCfg?.projectKey ?: '') as String).trim()
        if (key) {
            return key.startsWith('com.bbh:') ? key : "com.bbh:${key}"
        }
        String fallback = ((sonarCfg?.projectName ?: state.cfg?.appName ?: state.currentProjectName ?: state.cfg?.primaryProject ?: 'app') as String).trim()
        return fallback.startsWith('com.bbh:') ? fallback : "com.bbh:${fallback}"
    }

    List<String> detectModules() {
        List<String> modules = []
        try {
            if (script.fileExists('pom.xml')) {
                String pom = script.readFile('pom.xml') as String
                def matcher = pom =~ /<module>\s*([^<\s]+)\s*<\/module>/
                while (matcher.find()) {
                    String mod = matcher.group(1).trim()
                    if (mod && !modules.contains(mod)) modules << mod
                }
            }
            if (!modules && (script.fileExists('settings.gradle') || script.fileExists('settings.gradle.kts'))) {
                String settingsFile = script.fileExists('settings.gradle') ? 'settings.gradle' : 'settings.gradle.kts'
                String settings = script.readFile(settingsFile) as String
                def matcher = settings =~ /include\s*\(?\s*['"](?::)?([^'",\s\)]+)['"]/
                while (matcher.find()) {
                    String mod = matcher.group(1).trim()
                    if (mod && !modules.contains(mod)) modules << mod
                }
            }
        } catch (Exception ignored) {}
        List<String> excluded = []
        for (def entry : (state.projectsAllCfg ?: [:]).entrySet()) {
            Map p = (entry.value instanceof Map) ? (entry.value as Map) : [:]
            if (BuildUtils.databaseBlock(p)) {
                String dir = (p.sourceDir ?: p.database?.searchPath ?: entry.key) as String
                if (dir) excluded << dir
            }
        }
        if (excluded) {
            modules.removeAll { String m -> excluded.any { String ex -> m == ex || m.startsWith("${ex}/") } }
        }
        return modules
    }

    void applyDefaultSonarProperties(Map sonarCfg, String tool, String branch) {
        boolean exists = script.fileExists('sonar-project.properties')
        String dir = ((sonarCfg?.dir ?: '') as String).trim()
        if (dir && !exists) {
            exists = script.fileExists("${dir}/sonar-project.properties")
        }
        if (exists) {
            if (branch) {
                String targetPath = (dir && script.fileExists("${dir}/sonar-project.properties"))
                        ? "${dir}/sonar-project.properties" : 'sonar-project.properties'
                try {
                    String existing = script.readFile(targetPath) as String
                    if (!existing.contains('sonar.branch.name=')) {
                        String updated = existing.trim() + "\nsonar.branch.name=${branch}\n"
                        script.writeFile file: targetPath, text: updated
                    }
                } catch (Exception ignored) {}
            }
            return
        }

        String projectKey = resolveProjectKey(sonarCfg)
        List<String> modules = detectModules()
        String modulesProp = modules ? modules.join(',') : ''

        String binaries = 'target/classes'
        if (tool == 'gradle' || script.fileExists('build.gradle') || script.fileExists('build.gradle.kts')) {
            binaries = 'target/classes,build/classes/java/main'
        }

        String pomText = ''
        if (script.fileExists('pom.xml')) {
            try { pomText = script.readFile('pom.xml') as String } catch (Exception ignored) {}
        }

        String lang = 'java'
        if (tool == 'flutter' || script.fileExists('pubspec.yaml')) {
            lang = 'dart'
        } else if (BuildUtils.isDotnetTool(tool)) {
            lang = 'cs'
        } else if (tool == 'liquibase' || script.fileExists('changelog.xml')) {
            lang = 'sql'
        } else if (script.fileExists('requirements.txt') || script.fileExists('pyproject.toml') || script.fileExists('setup.py')) {
            lang = 'py'
        } else if (script.fileExists('src/main/kotlin') || script.fileExists('build.gradle.kts') || pomText.contains('kotlin')) {
            lang = 'kotlin'
        }

        String sources = ((sonarCfg?.sources ?: '') as String).trim()
        if (!sources) {
            if (lang == 'dart') {
                sources = 'lib'
            } else if (lang == 'cs') {
                sources = '.'
            } else if (lang == 'sql') {
                sources = 'db'
            } else if (lang == 'py') {
                sources = script.fileExists('src') ? 'src' : '.'
            } else if (lang == 'kotlin') {
                sources = script.fileExists('src/main/java') ? 'src/main/java,src/main/kotlin' : 'src/main/kotlin'
            } else {
                sources = 'src/main/java'
            }
        }

        String tests = ((sonarCfg?.tests ?: '') as String).trim()
        if (!tests) {
            if (lang == 'dart') {
                tests = 'test'
            } else if (lang == 'cs' || lang == 'sql') {
                tests = ''
            } else if (lang == 'py') {
                tests = script.fileExists('tests') ? 'tests' : (script.fileExists('test') ? 'test' : '')
            } else if (lang == 'kotlin') {
                tests = script.fileExists('src/test/java') ? 'src/test/java,src/test/kotlin' : 'src/test/kotlin'
            } else {
                tests = 'src/test/java'
            }
        }

        List<String> lines = []
        if (lang == 'cs') {
            String opencover = resolveDotnetReportParam(sonarCfg?.opencoverPaths, ['**/coverage.opencover.xml', '**/*opencover*.xml'])
            String vstest = resolveDotnetReportParam(sonarCfg?.vstestPaths, ['**/TestResults/*.trx', '**/*.trx'])
            lines = [
                    "sonar.language=cs",
                    "sonar.projectKey=${projectKey}",
                    "sonar.sources=${sources}",
                    'sonar.sourceEncoding=UTF-8',
                    'sonar.dynamicAnalysis=reuseReports'
            ]
            if (tests) lines << "sonar.tests=${tests}"
            if (opencover) lines << "sonar.cs.opencover.reportsPaths=${opencover}"
            if (vstest) lines << "sonar.cs.vstest.reportsPaths=${vstest}"
        } else {
            lines = [
                    "sonar.java.binaries=${binaries}",
                    'sonar.java.coveragePlugin=jacoco',
                    "sonar.language=${lang}",
                    'sonar.jacoco.reportPaths=target/jacoco.exec',
                    'sonar.junit.reportPaths=target/surefire-reports',
                    "sonar.projectKey=${projectKey}",
                    "sonar.sources=${sources}",
                    'sonar.sourceEncoding=UTF-8',
                    "sonar.tests=${tests}",
                    'sonar.dynamicAnalysis=reuseReports',
                    "sonar.modules=${modulesProp}"
            ]
        }
        if (branch) {
            lines << "sonar.branch.name=${branch}"
        }
        if (lang != 'cs') {
            for (String mod : modules) {
                lines << "${mod}.sonar.projectName=${mod}"
                lines << "${mod}.sonar.sources=${mod}/src/main/java"
                lines << "${mod}.sonar.tests=${mod}/src/test/java"
                lines << "${mod}.sonar.java.binaries=${mod}/target/classes,${mod}/build/classes/java/main"
            }
        }

        String content = lines.join('\n') + '\n'
        String targetPath = dir ? "${dir}/sonar-project.properties" : 'sonar-project.properties'
        script.writeFile file: targetPath, text: content
        if (dir && targetPath != 'sonar-project.properties') {
            script.writeFile file: 'sonar-project.properties', text: content
        }
    }
}
