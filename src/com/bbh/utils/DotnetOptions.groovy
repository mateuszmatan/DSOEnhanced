package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class DotnetOptions implements Serializable {

    @NonCPS
    static List<String> projectGlobs() {
        return ['*.csproj', '**/*.csproj', '*.fsproj', '**/*.fsproj', '*.vbproj', '**/*.vbproj']
    }

    @NonCPS
    static String mode(def raw) {
        String value = (raw ?: '').toString().trim().toLowerCase()
        if (value in ['cli', 'dotnet', 'sdk', 'dotnet-cli']) return 'dotnet'
        if (value in ['msbuild', 'classic', 'framework', 'vs', 'visualstudio', 'netfx']) return 'msbuild'
        if (value in ['hybrid', 'cake', 'nuke']) return value
        return ''
    }

    @NonCPS
    static String runner(def raw) {
        String value = (raw ?: '').toString().trim().toLowerCase()
        if (value in ['dotnet', 'cli', 'sdk', 'dotnet-test']) return 'dotnet'
        if (value in ['vstest', 'vstest.console']) return 'vstest'
        if (value in ['mstest', 'nunit', 'xunit', 'hybrid']) return value
        return ''
    }

    @NonCPS
    static String coverage(def raw) {
        String value = (raw ?: '').toString().trim().toLowerCase()
        if (value in ['coverlet', 'xplat']) return 'coverlet'
        if (value in ['cobertura', 'opencover', 'dotcover']) return value
        if (value in ['none', 'false', 'off', 'disabled']) return 'none'
        return ''
    }

    @NonCPS
    static boolean isAuto(def raw) {
        String value = (raw ?: '').toString().trim().toLowerCase()
        return !value || value == 'auto'
    }

    @NonCPS
    static boolean isBoolean(def raw) {
        if (raw instanceof Boolean) return true
        return (raw ?: '').toString().trim().toLowerCase() in ['true', 'false', '1', '0', 'yes', 'no']
    }

    @NonCPS
    static boolean isClassicProject(String text) {
        String body = text ?: ''
        return body.contains('<TargetFrameworkVersion>') || body =~ /(?i)<TargetFrameworks?>\s*net4[0-9]/ || (!body.contains('Sdk=') && body.contains('<Project'))
    }

    @NonCPS
    static boolean isSdkProject(String text) {
        String body = text ?: ''
        return body.contains('Sdk=') && !(body =~ /(?i)<TargetFramework>\s*net4[0-9]/)
    }
}
