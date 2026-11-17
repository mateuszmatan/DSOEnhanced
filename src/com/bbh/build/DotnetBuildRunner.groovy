package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class DotnetBuildRunner implements Serializable {

    private final def      script
    private final OsHelper os
    private final Map      stateCfg

    DotnetBuildRunner(def script, OsHelper os, Map stateCfg = [:]) {
        this.script   = script
        this.os       = os
        this.stateCfg = stateCfg ?: [:]
    }

    void build(Map overrideCfg = null, String tool = 'dotnet') {
        Map baseCfg = toolCfg(stateCfg.build as Map, tool)
        Map cfg = overrideCfg ? ([:] + baseCfg + overrideCfg) : baseCfg
        String mode = resolveMode(cfg, baseCfg, tool)
        List commands = BuildUtils.normalizeTokens(cfg.commands ?: cfg.tasks ?: cfg.goals)
        withToolEnvironment(cfg, baseCfg) {
            if (commands) {
                runCustomCommands(commands, cfg, 'Build')
                return
            }
            String target = resolveTarget(cfg, baseCfg, false)
            String configName = ((cfg.configuration ?: baseCfg.configuration ?: 'Release') as String).trim()
            if (mode == 'cake') {
                runCake(cfg, baseCfg, configName, 'Build')
            } else if (mode == 'nuke') {
                runNuke(cfg, baseCfg, configName, 'Compile')
            } else if (mode == 'dotnet') {
                runDotnetBuild(cfg, baseCfg, target, configName)
            } else if (mode == 'hybrid') {
                runHybridBuild(cfg, baseCfg, target, configName)
            } else {
                runMsbuildBuild(cfg, baseCfg, target, configName)
            }
        }
    }

    void test(String tool = 'dotnet') {
        Map buildCfg = toolCfg(stateCfg.build as Map, tool)
        Map cfg = toolCfg(stateCfg.tests?.unitTests as Map, tool)
        String mode = resolveMode(cfg, buildCfg, tool)
        List commands = BuildUtils.normalizeTokens(cfg.commands ?: cfg.tasks ?: cfg.goals)
        withToolEnvironment(cfg, buildCfg) {
            if (commands) {
                runCustomCommands(commands, cfg, 'Test')
                return
            }
            String target = resolveTarget(cfg, buildCfg, true)
            String configName = ((cfg.configuration ?: buildCfg.configuration ?: 'Release') as String).trim()
            if (mode == 'cake') {
                runCake(cfg, buildCfg, configName, 'Test')
                return
            }
            if (mode == 'nuke') {
                runNuke(cfg, buildCfg, configName, 'Test')
                return
            }
            String runner = resolveTestRunner(cfg, buildCfg, mode)
            if (runner == 'dotnet') {
                runDotnetTest(cfg, buildCfg, target, configName)
            } else if (runner == 'hybrid') {
                List<String> dlls = resolveTestAssemblies(cfg, configName)
                if (dlls) runConsoleTestRunner('vstest', cfg, buildCfg, dlls)
                if (hasSdkStyleProjects() || !dlls) runDotnetTest(cfg, buildCfg, target, configName)
            } else {
                List<String> dlls = resolveTestAssemblies(cfg, configName)
                if (!dlls) {
                    if (hasSdkStyleProjects()) {
                        script.echo '[TEST] Test assemblies not configured; running dotnet test for SDK-style project'
                        runDotnetTest(cfg, buildCfg, target, configName)
                        return
                    }
                    script.echo '[TEST] .NET: No test assemblies found (configure tests.unitTests.dotnet.testAssemblies or tests.unitTests.dotnet.commands)'
                    return
                }
                runConsoleTestRunner(runner, cfg, buildCfg, dlls)
            }
        }
    }

    String resolveMode(Map cfg, Map buildCfg, String tool) {
        String rawTool = (tool ?: '').trim().toLowerCase()
        if (rawTool in ['cake', 'nuke']) return rawTool
        String explicit = ((cfg?.mode ?: cfg?.tool ?: buildCfg?.mode ?: buildCfg?.tool ?: '') as String).trim().toLowerCase()
        if (explicit && explicit != 'auto') {
            String mapped = normalizeMode(explicit)
            if (mapped) return mapped
            String fallback = autoDetectMode(cfg, buildCfg, tool)
            script.echo "[WARN] Invalid .NET build mode '${explicit}' in configuration. Falling back to auto-detected mode '${fallback}'."
            return fallback
        }
        def rawCli = cfg?.useDotnetCli != null ? cfg.useDotnetCli : buildCfg?.useDotnetCli
        if (rawCli != null) {
            if (isValidBoolean(rawCli)) {
                return BuildUtils.booleanValue(rawCli, false) ? 'dotnet' : 'msbuild'
            }
            String fallback = autoDetectMode(cfg, buildCfg, tool)
            script.echo "[WARN] Invalid useDotnetCli value '${rawCli}' in configuration. Falling back to auto-detected mode '${fallback}'."
            return fallback
        }
        return autoDetectMode(cfg, buildCfg, tool)
    }

    String autoDetectMode(Map cfg, Map buildCfg, String tool) {
        String lowTool = (tool ?: '').trim().toLowerCase()
        if (lowTool in ['msbuild', 'vs', 'visualstudio', 'netfx']) return 'msbuild'
        if (lowTool in ['cake', 'nuke']) return lowTool
        Map utCfg = toolCfg(stateCfg?.tests?.unitTests as Map, tool)
        Map sonarCfg = (stateCfg?.tools?.sonar ?: [:]) as Map
        if (cfg?.msbuildPath || buildCfg?.msbuildPath || cfg?.msbuildTarget || buildCfg?.msbuildTarget
                || cfg?.msbuildInstallationName || buildCfg?.msbuildInstallationName
                || cfg?.vstestPath || buildCfg?.vstestPath || utCfg?.vstestPath
                || cfg?.opencoverPath || utCfg?.opencoverPath
                || BuildUtils.booleanValue(cfg?.opencover, false) || BuildUtils.booleanValue(utCfg?.opencover, false)
                || BuildUtils.booleanValue(cfg?.useMsbuildRestore, false) || BuildUtils.booleanValue(buildCfg?.useMsbuildRestore, false)
                || sonarCfg?.sonarScannerMsbuildPath) {
            return 'msbuild'
        }
        if (cfg?.dotnetPath || buildCfg?.dotnetPath || cfg?.dotnetInstallationName || buildCfg?.dotnetInstallationName) {
            return 'dotnet'
        }
        List<String> projs = findAllMatching(['*.csproj', '**/*.csproj', '*.fsproj', '**/*.fsproj', '*.vbproj', '**/*.vbproj'])
        if (!projs && script.fileExists('build.cake')) return 'cake'
        if (!projs && script.fileExists('.nuke')) return 'nuke'
        if (!os.isWindows()) return 'dotnet'

        boolean classic = script.fileExists('packages.config') || findFirst('**/packages.config') != ''
        boolean modern = script.fileExists('global.json') || script.fileExists('Directory.Packages.props')
        for (String p : projs) {
            String text = readProjectQuiet(p)
            if (!text) continue
            if (text.contains('<TargetFrameworkVersion>') || text =~ /(?i)<TargetFrameworks?>\s*net4[0-9]/ || (!text.contains('Sdk=') && text.contains('<Project'))) {
                classic = true
            }
            if (text.contains('Sdk=') && !(text =~ /(?i)<TargetFramework>\s*net4[0-9]/)) {
                modern = true
            }
        }
        if (classic && modern) return 'hybrid'
        if (modern && !classic) return 'dotnet'
        return 'msbuild'
    }

    String resolveTestRunner(Map cfg, Map buildCfg, String mode) {
        String raw = ((cfg?.runner ?: cfg?.testRunner ?: buildCfg?.testRunner ?: '') as String).trim().toLowerCase()
        if (!raw || raw == 'auto') {
            if (mode == 'dotnet') return 'dotnet'
            if (mode == 'hybrid') return 'hybrid'
            return 'vstest'
        }
        if (raw in ['dotnet', 'cli', 'sdk', 'dotnet-test']) return 'dotnet'
        if (raw in ['vstest', 'vstest.console']) return 'vstest'
        if (raw in ['mstest', 'nunit', 'xunit', 'hybrid']) return raw
        String fallback = mode == 'dotnet' ? 'dotnet' : (mode == 'hybrid' ? 'hybrid' : 'vstest')
        script.echo "[WARN] Invalid .NET test runner '${raw}' in configuration. Falling back to auto-detected runner '${fallback}'."
        return fallback
    }

    String resolveCoverageTool(Map cfg, Map buildCfg, String runner) {
        String raw = ((cfg?.coverageTool ?: buildCfg?.coverageTool ?: '') as String).trim().toLowerCase()
        if (raw && raw != 'auto') {
            if (raw in ['cobertura', 'coverlet', 'xplat']) return 'cobertura'
            if (raw == 'opencover') return 'opencover'
            if (raw == 'dotcover') return 'dotcover'
            if (raw in ['none', 'false', 'off', 'disabled']) return 'none'
            String fallback = defaultCoverageTool(cfg, runner)
            script.echo "[WARN] Invalid .NET coverageTool '${raw}' in configuration. Falling back to auto-detected coverage tool '${fallback}'."
            return fallback
        }
        return defaultCoverageTool(cfg, runner)
    }

    private String defaultCoverageTool(Map cfg, String runner) {
        String ocPath = ((cfg?.opencoverPath ?: '') as String).trim()
        if ((ocPath || BuildUtils.booleanValue(cfg?.opencover, false)) && os.isWindows()) return 'opencover'
        if (runner == 'dotnet') return BuildUtils.booleanValue(cfg?.coverlet, true) ? 'cobertura' : 'none'
        return 'none'
    }

    @NonCPS
    private static String normalizeMode(String explicit) {
        if (explicit in ['cli', 'dotnet', 'sdk', 'dotnet-cli']) return 'dotnet'
        if (explicit in ['msbuild', 'classic', 'framework', 'vs', 'visualstudio', 'netfx']) return 'msbuild'
        if (explicit in ['hybrid', 'cake', 'nuke']) return explicit
        return ''
    }

    @NonCPS
    private static boolean isValidBoolean(def val) {
        if (val instanceof Boolean) return true
        String s = (val ?: '').toString().trim().toLowerCase()
        return s in ['true', 'false', '1', '0', 'yes', 'no']
    }

    @NonCPS
    static Map toolCfg(Map parentCfg, String tool) {
        if (!(parentCfg instanceof Map)) return [:]
        String low = (tool ?: '').trim().toLowerCase()
        if (low == 'msbuild' && parentCfg.msbuild instanceof Map) return parentCfg.msbuild as Map
        if (parentCfg.dotnet instanceof Map) return parentCfg.dotnet as Map
        if (parentCfg.msbuild instanceof Map) return parentCfg.msbuild as Map
        return [:]
    }

    private void runDotnetBuild(Map cfg, Map baseCfg, String target, String configName) {
        String dotnetExe = resolveExecutable(cfg.dotnetPath ?: baseCfg.dotnetPath, os.isWindows() ? 'dotnet.exe' : 'dotnet')
        String nugetConfig = ((cfg.nugetConfig ?: baseCfg.nugetConfig ?: '') as String).trim()
        boolean doRestore = BuildUtils.booleanValue(cfg.restore != null ? cfg.restore : baseCfg.restore, true)
        if (doRestore) {
            List restoreTokens = [dotnetExe, 'restore']
            if (target) restoreTokens << target
            if (nugetConfig) restoreTokens.addAll(['--configfile', nugetConfig])
            restoreTokens.addAll(BuildUtils.normalizeTokens(cfg.restoreFlags ?: baseCfg.restoreFlags))
            execCommand(formatCommand(restoreTokens), 'dotnet restore')
        }
        List buildTokens = [dotnetExe, 'build']
        if (target) buildTokens << target
        buildTokens.addAll(['--configuration', configName])
        if (doRestore) buildTokens << '--no-restore'
        buildTokens.addAll(BuildUtils.normalizeTokens(cfg.flags))
        execCommand(formatCommand(buildTokens), 'dotnet build')

        if (BuildUtils.booleanValue(cfg.pack != null ? cfg.pack : baseCfg.pack, false)) {
            List packTokens = [dotnetExe, 'pack']
            if (target) packTokens << target
            packTokens.addAll(['--configuration', configName, '--no-build'])
            execCommand(formatCommand(packTokens), 'dotnet pack')
        }
        if (BuildUtils.booleanValue(cfg.publish != null ? cfg.publish : baseCfg.publish, false)) {
            List pubTokens = [dotnetExe, 'publish']
            if (target) pubTokens << target
            pubTokens.addAll(['--configuration', configName, '--no-build'])
            String publishDir = ((cfg.publishDir ?: baseCfg.publishDir ?: '') as String).trim()
            if (publishDir) pubTokens.addAll(['-o', publishDir])
            execCommand(formatCommand(pubTokens), 'dotnet publish')
        }
    }

    private void runHybridBuild(Map cfg, Map baseCfg, String target, String configName) {
        boolean doRestore = BuildUtils.booleanValue(cfg.restore != null ? cfg.restore : baseCfg.restore, true)
        if (doRestore && hasSdkStyleProjects()) {
            String dotnetExe = resolveExecutable(cfg.dotnetPath ?: baseCfg.dotnetPath, os.isWindows() ? 'dotnet.exe' : 'dotnet')
            List restoreTokens = [dotnetExe, 'restore']
            if (target) restoreTokens << target
            String nugetConfig = ((cfg.nugetConfig ?: baseCfg.nugetConfig ?: '') as String).trim()
            if (nugetConfig) restoreTokens.addAll(['--configfile', nugetConfig])
            execCommand(formatCommand(restoreTokens), 'dotnet restore')
        }
        runMsbuildBuild(cfg, baseCfg, target, configName)
    }

    private void runMsbuildBuild(Map cfg, Map baseCfg, String target, String configName) {
        String nugetExe = resolveExecutable(cfg.nugetPath ?: baseCfg.nugetPath, os.isWindows() ? 'nuget.exe' : 'nuget')
        String msbuildExe = resolveExecutable(cfg.msbuildPath ?: baseCfg.msbuildPath, os.isWindows() ? 'MSBuild.exe' : 'msbuild')
        String nugetConfig = ((cfg.nugetConfig ?: baseCfg.nugetConfig ?: '') as String).trim()
        boolean doRestore = BuildUtils.booleanValue(cfg.restore != null ? cfg.restore : baseCfg.restore, true)
        boolean useMsbuildRestore = BuildUtils.booleanValue(cfg.useMsbuildRestore != null ? cfg.useMsbuildRestore : baseCfg.useMsbuildRestore, false)

        if (doRestore && !useMsbuildRestore && shouldRunNugetRestore(target)) {
            List restoreTokens = [nugetExe, 'restore']
            if (target) restoreTokens << target
            if (nugetConfig) restoreTokens.addAll(['-ConfigFile', nugetConfig])
            restoreTokens << '-NonInteractive'
            restoreTokens.addAll(BuildUtils.normalizeTokens(cfg.restoreFlags ?: baseCfg.restoreFlags))
            execCommand(formatCommand(restoreTokens), 'NuGet restore')
        }

        String msbuildTarget = ((cfg.msbuildTarget ?: baseCfg.msbuildTarget ?: '') as String).trim()
        if (!msbuildTarget) {
            boolean rebuild = BuildUtils.booleanValue(cfg.rebuild != null ? cfg.rebuild : baseCfg.rebuild, false)
            String baseTarget = rebuild ? 'Rebuild' : 'Build'
            msbuildTarget = (doRestore && useMsbuildRestore) ? "Restore,${baseTarget}" : baseTarget
        }

        List buildTokens = [msbuildExe]
        if (target) buildTokens << target
        buildTokens.addAll(["/t:${msbuildTarget}", "/p:Configuration=${configName}"])
        String platform = ((cfg.platform ?: baseCfg.platform ?: '') as String).trim()
        if (platform) buildTokens << "/p:Platform=${platform}"
        if (BuildUtils.booleanValue(cfg.publish != null ? cfg.publish : baseCfg.publish, false)) {
            buildTokens << '/p:DeployOnBuild=true'
            String publishDir = ((cfg.publishDir ?: baseCfg.publishDir ?: '') as String).trim()
            if (publishDir) buildTokens << "/p:PublishUrl=${publishDir}"
        }
        buildTokens.addAll(BuildUtils.normalizeTokens(cfg.flags))
        execCommand(formatCommand(buildTokens), "MSBuild ${msbuildTarget}")
    }

    private void runCake(Map cfg, Map baseCfg, String configName, String cakeTarget) {
        String scriptFile = ((cfg.cakeScript ?: baseCfg.cakeScript ?: 'build.cake') as String).trim()
        String target = ((cfg.cakeTarget ?: cakeTarget) as String).trim()
        String dotnetExe = resolveExecutable(cfg.dotnetPath ?: baseCfg.dotnetPath, os.isWindows() ? 'dotnet.exe' : 'dotnet')
        List tokens = [dotnetExe, 'cake', scriptFile, "--target=${target}", "--configuration=${configName}"]
        tokens.addAll(BuildUtils.normalizeTokens(cfg.flags))
        execCommand(formatCommand(tokens), "Cake ${target}")
    }

    private void runNuke(Map cfg, Map baseCfg, String configName, String nukeTarget) {
        String target = ((cfg.nukeTarget ?: nukeTarget) as String).trim()
        String exe = os.isWindows() && script.fileExists('build.cmd') ? 'build.cmd' : (!os.isWindows() && script.fileExists('build.sh') ? './build.sh' : 'nuke')
        List tokens = [exe, target, '--configuration', configName]
        tokens.addAll(BuildUtils.normalizeTokens(cfg.flags))
        execCommand(formatCommand(tokens), "NUKE ${target}")
    }

    private void runDotnetTest(Map cfg, Map buildCfg, String target, String configName) {
        String dotnetExe = resolveExecutable(cfg.dotnetPath ?: buildCfg?.dotnetPath, os.isWindows() ? 'dotnet.exe' : 'dotnet')
        String resultsDir = ((cfg.resultsDirectory ?: 'TestResults') as String).trim()
        String covTool = resolveCoverageTool(cfg, buildCfg, 'dotnet')
        List tokens = [dotnetExe, 'test']
        if (target) tokens << target
        tokens.addAll(['--configuration', configName, '--logger', 'trx;LogFileName=test-results.trx', '--results-directory', resultsDir])
        if (covTool == 'cobertura') {
            tokens.addAll(['--collect', 'XPlat Code Coverage'])
        }
        tokens.addAll(BuildUtils.normalizeTokens(cfg.flags))
        if (covTool == 'opencover' && os.isWindows()) {
            wrapWithOpenCover(dotnetExe, tokens.subList(1, tokens.size()), cfg)
            return
        }
        if (covTool == 'dotcover') {
            wrapWithDotCover(dotnetExe, tokens.subList(1, tokens.size()), cfg)
            return
        }
        execCommand(formatCommand(tokens), 'dotnet test')
    }

    private void runConsoleTestRunner(String runner, Map cfg, Map buildCfg, List<String> assemblies) {
        String resultsDir = ((cfg.resultsDirectory ?: 'TestResults') as String).trim()
        String exe
        List args
        String label
        if (runner == 'mstest') {
            exe = resolveExecutable(cfg.mstestPath ?: buildCfg?.mstestPath, os.isWindows() ? 'MSTest.exe' : 'mstest')
            args = assemblies.collect { "/testcontainer:${it}".toString() } + ["/resultsfile:${resultsDir}/test-results.trx".toString()]
            label = 'MSTest unit tests'
        } else if (runner == 'nunit') {
            exe = resolveExecutable(cfg.nunitPath ?: buildCfg?.nunitPath, os.isWindows() ? 'nunit3-console.exe' : 'nunit3-console')
            args = assemblies + ["--result=${resultsDir}/test-results.xml".toString()]
            label = 'NUnit unit tests'
        } else if (runner == 'xunit') {
            exe = resolveExecutable(cfg.xunitPath ?: buildCfg?.xunitPath, os.isWindows() ? 'xunit.console.exe' : 'xunit.console')
            args = assemblies + ['-xml', "${resultsDir}/test-results.xml".toString()]
            label = 'xUnit unit tests'
        } else {
            exe = resolveExecutable(cfg.vstestPath ?: buildCfg?.vstestPath, os.isWindows() ? 'vstest.console.exe' : 'vstest.console')
            args = assemblies + ['/Logger:trx;LogFileName=test-results.trx', "/ResultsDirectory:${resultsDir}".toString()]
            label = 'VSTest unit tests'
        }
        args.addAll(BuildUtils.normalizeTokens(cfg.flags))
        String covTool = resolveCoverageTool(cfg, buildCfg, runner)
        if (covTool == 'opencover' && os.isWindows()) {
            wrapWithOpenCover(exe, args, cfg)
            return
        }
        if (covTool == 'dotcover') {
            wrapWithDotCover(exe, args, cfg)
            return
        }
        if (covTool == 'cobertura' && runner == 'vstest') {
            args << '/Collect:XPlat Code Coverage'
        }
        execCommand(formatCommand([exe] + args), label)
    }

    private void wrapWithOpenCover(String targetExe, List targetArgsList, Map cfg) {
        String opencoverPath = ((cfg.opencoverPath ?: '') as String).trim()
        String openCoverExe = resolveExecutable(opencoverPath, 'OpenCover.Console.exe')
        String targetArgs = targetArgsList.collect { BuildUtils.cmdQuoteIfNeeded(it as String) }.join(' ')
        String coverOut = ((cfg.opencoverOutput ?: 'coverage.opencover.xml') as String).trim()
        List ocTokens = [openCoverExe, '-register:user', "-target:${targetExe}", "-targetargs:${targetArgs}", "-output:${coverOut}"]
        ocTokens.addAll(BuildUtils.normalizeTokens(cfg.opencoverFlags))
        execCommand(formatCommand(ocTokens), 'VSTest + OpenCover')
    }

    private void wrapWithDotCover(String targetExe, List targetArgsList, Map cfg) {
        String dotCoverExe = resolveExecutable(cfg.dotcoverPath, os.isWindows() ? 'dotCover.exe' : 'dotCover')
        String targetArgs = targetArgsList.collect { BuildUtils.cmdQuoteIfNeeded(it as String) }.join(' ')
        String coverOut = ((cfg.dotcoverOutput ?: cfg.opencoverOutput ?: 'coverage.opencover.xml') as String).trim()
        List dcTokens = [dotCoverExe, 'cover', "/TargetExecutable=${targetExe}", "/TargetArguments=${targetArgs}", "/Output=${coverOut}", '/ReportType=DetailedXML']
        dcTokens.addAll(BuildUtils.normalizeTokens(cfg.dotcoverFlags))
        execCommand(formatCommand(dcTokens), 'dotCover unit tests')
    }

    private boolean shouldRunNugetRestore(String target) {
        if (target) return true
        return script.fileExists('packages.config') || findFirst('**/packages.config') != '' || findFirst('*.sln') != '' || findFirst('**/*.sln') != ''
    }

    private boolean hasSdkStyleProjects() {
        for (String p : findAllMatching(['*.csproj', '**/*.csproj', '*.fsproj', '**/*.fsproj', '*.vbproj', '**/*.vbproj'])) {
            if (readProjectQuiet(p).contains('Sdk=')) return true
        }
        return false
    }

    private String readProjectQuiet(String path) {
        try {
            return script.readFile(path) ?: ''
        } catch (Exception ignored) {
            return ''
        }
    }

    private List<String> resolveTestAssemblies(Map cfg, String configName) {
        List<String> configured = BuildUtils.normalizeTokens(cfg.testAssemblies)
        List<String> out = []
        if (configured) {
            for (String pattern : configured) {
                if (pattern.contains('*') || pattern.contains('?')) {
                    out.addAll(findAllMatching([pattern]))
                } else if (script.fileExists(pattern)) {
                    out << pattern
                }
            }
            return out.unique()
        }
        List<String> patterns = [
            "**/bin/${configName}/*Tests.dll".toString(),
            "**/bin/${configName}/*Test.dll".toString(),
            "**/bin/${configName}/**/*Tests.dll".toString(),
            "**/bin/${configName}/**/*Test.dll".toString(),
            '**/bin/**/*Tests.dll',
            '**/bin/**/*Test.dll'
        ]
        for (String p : findAllMatching(patterns)) {
            String low = p.replace('\\', '/').toLowerCase()
            if (low.contains('/obj/') || low.contains('/packages/') || low.contains('/ref/') || low.startsWith('obj/') || low.startsWith('packages/')) continue
            String fileName = BuildUtils.fileNameOf(low)
            if (fileName.endsWith('.testadapter.dll') || fileName.startsWith('microsoft.') || fileName.startsWith('xunit.') || fileName.startsWith('nunit')) continue
            out << p
        }
        return out.unique()
    }

    private String resolveTarget(Map cfg, Map buildCfg, boolean forTests) {
        String configured = ((cfg?.solution ?: cfg?.project ?: buildCfg?.solution ?: buildCfg?.project ?: '') as String).trim()
        if (configured) return configured
        List<String> globs = forTests
                ? ['*.sln', '**/*.sln', '*Tests.csproj', '**/*Tests.csproj', '*Test.csproj', '**/*Test.csproj', '*.csproj', '**/*.csproj']
                : ['*.sln', '**/*.sln', '*.csproj', '**/*.csproj', '*.fsproj', '**/*.fsproj', '*.vbproj', '**/*.vbproj']
        for (String glob : globs) {
            String hit = findFirst(glob)
            if (hit) return hit
        }
        return ''
    }

    private String findFirst(String glob) {
        List<String> matches = findAllMatching([glob])
        return matches ? matches[0] : ''
    }

    private List<String> findAllMatching(List<String> globs) {
        List<String> hits = []
        for (String glob : globs) {
            try {
                def files = script.findFiles(glob: glob)
                if (files) {
                    for (def f : files) {
                        String path = (f?.path ?: '') as String
                        if (path && !hits.contains(path)) hits << path
                    }
                }
            } catch (Exception ignored) {}
        }
        return hits.sort()
    }

    private void runCustomCommands(List commands, Map cfg, String phase) {
        String label = (cfg.label ?: ".NET ${phase}: ${commands.join(' ')}") as String
        String joined = commands.join(' ')
        if (commands.size() > 1 && commands.every { !(it.toString().contains(' ')) }) {
            execCommand(joined, label)
            return
        }
        for (def c : commands) {
            String cmdText = c.toString().trim()
            if (cmdText) execCommand(cmdText, label)
        }
    }

    private def withToolEnvironment(Map cfg, Map baseCfg, Closure body) {
        List<String> extraEnv = []
        List<String> pathParts = []
        String sep = os.isWindows() ? ';' : ':'
        String pathJoiner = os.isWindows() ? '\\' : '/'

        for (String pathKey : ['dotnetPath', 'msbuildPath', 'nugetPath', 'vstestPath', 'mstestPath', 'nunitPath', 'xunitPath']) {
            String val = ((cfg?.get(pathKey) ?: baseCfg?.get(pathKey) ?: '') as String).trim()
            if (val) {
                String dirPart = isBinaryPath(val) ? parentDir(val) : val
                if (dirPart && !pathParts.contains(dirPart)) pathParts << dirPart
                if (pathKey == 'dotnetPath' && !isBinaryPath(val)) {
                    extraEnv << "DOTNET_ROOT=${val}".toString()
                }
            }
        }
        for (String toolNameKey : ['msbuildInstallationName', 'dotnetInstallationName', 'installationName']) {
            String inst = ((cfg?.get(toolNameKey) ?: baseCfg?.get(toolNameKey) ?: '') as String).trim()
            if (inst) {
                String toolHome = (script.tool(name: inst) ?: '') as String
                if (toolHome) {
                    pathParts << toolHome
                    pathParts << "${toolHome}${pathJoiner}bin".toString()
                }
            }
        }
        if (pathParts) {
            String currentPath = (script.env.PATH ?: '') as String
            extraEnv << ("PATH=${pathParts.join(sep)}${currentPath ? sep + currentPath : ''}").toString()
        }
        if (baseCfg?.env instanceof Map) {
            baseCfg.env.each { k, v -> extraEnv << "${k}=${v}".toString() }
        }
        if (cfg?.env instanceof Map) {
            cfg.env.each { k, v -> extraEnv << "${k}=${v}".toString() }
        }

        String workDir = ((cfg?.dir ?: baseCfg?.dir ?: '') as String).trim()
        Closure invoke = {
            if (!extraEnv) return body()
            return script.withEnv(extraEnv) { body() }
        }
        return workDir ? script.dir(workDir) { invoke() } : invoke()
    }

    private void execCommand(String cmd, String label) {
        script.echo "Execute .NET Command: ${cmd}"
        if (os.isWindows()) {
            script.bat(label: label, script: cmd)
        } else {
            BuildUtils.execSh(script, cmd, label, false)
        }
    }

    @NonCPS
    private String resolveExecutable(def configured, String fallbackName) {
        String trimmed = ((configured ?: '') as String).trim()
        if (!trimmed) return fallbackName
        if (isBinaryPath(trimmed.toLowerCase())) return trimmed
        String sep = os.isWindows() ? '\\' : '/'
        return (trimmed.endsWith('/') || trimmed.endsWith('\\')) ? "${trimmed}${fallbackName}" : "${trimmed}${sep}${fallbackName}"
    }

    @NonCPS
    private static boolean isBinaryPath(String val) {
        String low = (val ?: '').trim().toLowerCase()
        return low.endsWith('.exe') || low.endsWith('.bat') || low.endsWith('.cmd') || low.endsWith('/dotnet') || low.endsWith('/msbuild') || low.endsWith('/nuget')
    }

    @NonCPS
    private static String parentDir(String path) {
        String norm = (path ?: '').replace('\\', '/')
        int idx = norm.lastIndexOf('/')
        return idx > 0 ? path.substring(0, idx) : ''
    }

    private String formatCommand(List tokens) {
        List nonEmpty = tokens.findAll { (it ?: '').toString().trim() }
        return os.isWindows()
                ? nonEmpty.collect { BuildUtils.cmdQuoteIfNeeded(it.toString()) }.join(' ')
                : nonEmpty.collect { BuildUtils.shellQuoteIfNeeded(it.toString()) }.join(' ')
    }
}
