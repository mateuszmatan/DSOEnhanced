package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.utils.BuildUtils
import com.bbh.utils.DotnetOptions
import com.cloudbees.groovy.cps.NonCPS

class DotnetProjectResolver implements Serializable {

    private final def script
    private final OsHelper os
    private final Map stateCfg

    DotnetProjectResolver(def script, OsHelper os, Map stateCfg = [:]) {
        this.script = script
        this.os = os
        this.stateCfg = stateCfg ?: [:]
    }

    String resolveMode(Map cfg, Map buildCfg, String tool) {
        String rawTool = (tool ?: '').trim().toLowerCase()
        if (rawTool in ['cake', 'nuke']) return rawTool
        def explicit = cfg?.mode ?: cfg?.tool ?: buildCfg?.mode ?: buildCfg?.tool
        if (!DotnetOptions.isAuto(explicit)) {
            String mapped = DotnetOptions.mode(explicit)
            if (mapped) return mapped
            return warnFallback("Invalid .NET build mode '${explicit}' in configuration", 'mode', autoDetectMode(cfg, buildCfg, tool))
        }
        def rawCli = cfg?.useDotnetCli != null ? cfg.useDotnetCli : buildCfg?.useDotnetCli
        if (rawCli != null) {
            if (DotnetOptions.isBoolean(rawCli)) return BuildUtils.booleanValue(rawCli, false) ? 'dotnet' : 'msbuild'
            return warnFallback("Invalid useDotnetCli value '${rawCli}' in configuration", 'mode', autoDetectMode(cfg, buildCfg, tool))
        }
        return autoDetectMode(cfg, buildCfg, tool)
    }

    private String warnFallback(String problem, String what, String fallback) {
        script.echo "[WARN] ${problem}. Falling back to auto-detected ${what} '${fallback}'."
        return fallback
    }

    String autoDetectMode(Map cfg, Map buildCfg, String tool) {
        String lowTool = (tool ?: '').trim().toLowerCase()
        if (lowTool in ['msbuild', 'vs', 'visualstudio', 'netfx']) return 'msbuild'
        if (lowTool in ['cake', 'nuke']) return lowTool
        Map utCfg = DotnetProjectResolver.toolCfg(stateCfg?.tests?.unitTests as Map, tool)
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
        List<String> projs = findAllMatching(DotnetOptions.projectGlobs())
        if (!projs && script.fileExists('build.cake')) return 'cake'
        if (!projs && script.fileExists('.nuke')) return 'nuke'
        if (!os.isWindows()) return 'dotnet'

        boolean classic = script.fileExists('packages.config') || findFirst('**/packages.config') != ''
        boolean modern = script.fileExists('global.json') || script.fileExists('Directory.Packages.props')
        for (String p : projs) {
            String text = readProjectQuiet(p)
            classic = classic || DotnetOptions.isClassicProject(text)
            modern = modern || DotnetOptions.isSdkProject(text)
        }
        if (classic && modern) return 'hybrid'
        if (modern && !classic) return 'dotnet'
        return 'msbuild'
    }

    String resolveTestRunner(Map cfg, Map buildCfg, String mode) {
        def raw = cfg?.runner ?: cfg?.testRunner ?: buildCfg?.testRunner
        String fallback = mode in ['dotnet', 'hybrid'] ? mode : 'vstest'
        if (DotnetOptions.isAuto(raw)) return fallback
        return DotnetOptions.runner(raw) ?: warnFallback("Invalid .NET test runner '${raw}' in configuration", 'runner', fallback)
    }

    String resolveCoverageTool(Map cfg, Map buildCfg, String runner) {
        def raw = cfg?.coverageTool ?: buildCfg?.coverageTool
        if (DotnetOptions.isAuto(raw)) return defaultCoverageTool(cfg, runner)
        String tool = DotnetOptions.coverage(raw)
        if (!tool) return warnFallback("Invalid .NET coverageTool '${raw}' in configuration", 'coverage tool', defaultCoverageTool(cfg, runner))
        if (tool == 'cobertura') script.echo "[WARN] .NET coverageTool 'cobertura' writes a report SonarQube cannot import for C#; use 'coverlet' (OpenCover format) to see the coverage in SonarQube too."
        return tool
    }

    String defaultCoverageTool(Map cfg, String runner) {
        String ocPath = ((cfg?.opencoverPath ?: '') as String).trim()
        if ((ocPath || BuildUtils.booleanValue(cfg?.opencover, false)) && os.isWindows()) return 'opencover'
        if (runner == 'dotnet') return BuildUtils.booleanValue(cfg?.coverlet, true) ? 'coverlet' : 'none'
        return 'none'
    }

    boolean shouldRunNugetRestore(String target) {
        if (target) return true
        return script.fileExists('packages.config') || findFirst('**/packages.config') != '' || findFirst('*.sln') != '' || findFirst('**/*.sln') != ''
    }

    boolean hasSdkStyleProjects() {
        for (String p : findAllMatching(DotnetOptions.projectGlobs())) {
            if (readProjectQuiet(p).contains('Sdk=')) return true
        }
        return false
    }

    String readProjectQuiet(String path) {
        try {
            return script.readFile(path) ?: ''
        } catch (Exception ignored) {
            return ''
        }
    }

    List<String> resolveTestAssemblies(Map cfg, String configName) {
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

    String resolveTarget(Map cfg, Map buildCfg, boolean forTests) {
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

    String findFirst(String glob) {
        List<String> matches = findAllMatching([glob])
        return matches ? matches[0] : ''
    }

    List<String> findAllMatching(List<String> globs) {
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

    @NonCPS
    static Map toolCfg(Map parentCfg, String tool) {
        if (!(parentCfg instanceof Map)) return [:]
        String low = (tool ?: '').trim().toLowerCase()
        if (low == 'msbuild' && parentCfg.msbuild instanceof Map) return parentCfg.msbuild as Map
        if (parentCfg.dotnet instanceof Map) return parentCfg.dotnet as Map
        if (parentCfg.msbuild instanceof Map) return parentCfg.msbuild as Map
        return [:]
    }
}
