package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.utils.BuildUtils
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
        String explicit = ((cfg?.mode ?: cfg?.tool ?: buildCfg?.mode ?: buildCfg?.tool ?: '') as String).trim().toLowerCase()
        if (explicit && explicit != 'auto') {
            String mapped = DotnetProjectResolver.normalizeMode(explicit)
            if (mapped) return mapped
            String fallback = autoDetectMode(cfg, buildCfg, tool)
            script.echo "[WARN] Invalid .NET build mode '${explicit}' in configuration. Falling back to auto-detected mode '${fallback}'."
            return fallback
        }
        def rawCli = cfg?.useDotnetCli != null ? cfg.useDotnetCli : buildCfg?.useDotnetCli
        if (rawCli != null) {
            if (DotnetProjectResolver.isValidBoolean(rawCli)) {
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

    String defaultCoverageTool(Map cfg, String runner) {
        String ocPath = ((cfg?.opencoverPath ?: '') as String).trim()
        if ((ocPath || BuildUtils.booleanValue(cfg?.opencover, false)) && os.isWindows()) return 'opencover'
        if (runner == 'dotnet') return BuildUtils.booleanValue(cfg?.coverlet, true) ? 'cobertura' : 'none'
        return 'none'
    }

    boolean shouldRunNugetRestore(String target) {
        if (target) return true
        return script.fileExists('packages.config') || findFirst('**/packages.config') != '' || findFirst('*.sln') != '' || findFirst('**/*.sln') != ''
    }

    boolean hasSdkStyleProjects() {
        for (String p : findAllMatching(['*.csproj', '**/*.csproj', '*.fsproj', '**/*.fsproj', '*.vbproj', '**/*.vbproj'])) {
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
    static String normalizeMode(String explicit) {
        if (explicit in ['cli', 'dotnet', 'sdk', 'dotnet-cli']) return 'dotnet'
        if (explicit in ['msbuild', 'classic', 'framework', 'vs', 'visualstudio', 'netfx']) return 'msbuild'
        if (explicit in ['hybrid', 'cake', 'nuke']) return explicit
        return ''
    }

    @NonCPS
    static boolean isValidBoolean(def val) {
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
}
