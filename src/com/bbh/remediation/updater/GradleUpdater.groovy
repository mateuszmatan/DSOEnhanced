package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class GradleUpdater implements ManifestUpdater {

    @NonCPS
    private static String propertyRefRe() { return '^\\$\\{?([A-Za-z_][\\w.]*)\\}?$' }

    @NonCPS
    private static String tomlVersionsRe() { return '(?ms)(^\\[versions\\][ \\t]*$)((?:.|\\n)*?)(?=^\\[|\\z)' }

    String ecosystem() { return 'maven' }

    List<String> filePatterns() { return ['build.gradle', 'build.gradle.kts', 'gradle.properties', '*.versions.toml'] }

    @NonCPS
    boolean supports(String relativePath) {
        String name = UpdaterSupport.fileName(relativePath)
        return name in ['build.gradle', 'build.gradle.kts', 'gradle.properties'] || name.endsWith('.versions.toml')
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        List changes = []
        List properties = []
        List notes = []
        if (UpdaterSupport.fileName(relativePath) == 'gradle.properties') {
            return [content: content, changes: changes, properties: properties, notes: notes]
        }

        String updated = content
        fixes.each { fix ->
            String group = UpdaterSupport.quote(fix.group as String)
            String artifact = UpdaterSupport.quote(fix.name as String)

            List declarations = [
                    '([\'"]' + group + ':' + artifact + ':)([^\'":@\\s]+)((?::[^\'"@\\s]*)?(?:@[^\'"\\s]*)?[\'"])',
                    '(group\\s*[:=]\\s*[\'"]' + group + '[\'"]\\s*,\\s*name\\s*[:=]\\s*[\'"]' + artifact + '[\'"]\\s*,\\s*version\\s*[:=]\\s*[\'"])([^\'"]+)([\'"])',
                    '(module\\s*=\\s*"' + group + ':' + artifact + '"\\s*,\\s*version\\s*=\\s*")([^"]+)(")'
            ]
            declarations.each { regex ->
                updated = UpdaterSupport.replaceValue(updated, regex) { String declared ->
                    return handleDeclaredVersion(relativePath, fix, declared, changes, properties, notes)
                }
            }

            String refRegex = '(?:module\\s*=\\s*"' + group + ':' + artifact + '"|group\\s*=\\s*"' + group + '"\\s*,\\s*name\\s*=\\s*"' + artifact + '")\\s*,\\s*version\\.ref\\s*=\\s*"([^"]+)"'
            UpdaterSupport.findAll(updated, refRegex, 1).each { reference ->
                properties << UpdaterSupport.propertyRequest(reference as String, fix, relativePath)
            }
        }
        return [content: updated, changes: changes, properties: properties, notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        List changes = []
        String fileName = UpdaterSupport.fileName(relativePath)
        String updated = content

        properties.each { property ->
            String name = UpdaterSupport.quote(property.name as String)
            String target = property.targetVersion as String
            Closure bump = { String value ->
                if (!GoldenFix.isConcreteVersion(value) || !GoldenFix.isUpgrade(value, target)) return null
                changes << UpdaterSupport.propertyChange(relativePath, property, value)
                return target
            }

            if (fileName == 'gradle.properties') {
                updated = UpdaterSupport.replaceValue(updated, '(?m)(^[ \\t]*' + name + '[ \\t]*[=:][ \\t]*)([^\\s#]+)', 1, 2, 0, bump)
            } else if (fileName.endsWith('.versions.toml')) {
                updated = UpdaterSupport.replaceValue(updated, tomlVersionsRe(), 1, 2, 0) { String section ->
                    String replaced = UpdaterSupport.replaceValue(section, '(?m)(^[ \\t]*"?' + name + '"?[ \\t]*=[ \\t]*")([^"]+)(")', bump)
                    return replaced == section ? null : replaced
                }
            } else {
                List patterns = [
                        '(?<![\\w.])((?:(?:rootProject|project)\\.)?(?:ext(?:ra)?\\.)?' + name + '\\s*=\\s*[\'"])([^\'"\\r\\n]+)([\'"])',
                        '((?:extra\\[\\s*"' + name + '"\\s*\\]\\s*=|set\\(\\s*[\'"]' + name + '[\'"]\\s*,|' + name + '\\s+by\\s+extra\\()\\s*[\'"])([^\'"\\r\\n]+)([\'"])'
                ]
                patterns.each { regex -> updated = UpdaterSupport.replaceValue(updated, regex, bump) }
            }
        }
        return [content: updated, changes: changes]
    }

    @NonCPS
    private static String handleDeclaredVersion(String path, Map fix, String declared, List changes, List properties, List notes) {
        def reference = (declared ?: '') =~ propertyRefRe()
        if (reference) {
            String name = (reference[0] as List)[1] as String
            if (!name.startsWith('libs.')) {
                List segments = name.tokenize('.')
                properties << UpdaterSupport.propertyRequest(segments[segments.size() - 1] as String, fix, path)
            }
            return null
        }
        if (!GoldenFix.isConcreteVersion(declared)) {
            notes << UpdaterSupport.note(path, fix, "declared version '${declared}' is dynamic or a range".toString())
            return null
        }
        String target = fix.targetVersion as String
        if (!GoldenFix.isUpgrade(declared, target)) return null
        changes << UpdaterSupport.change(path, fix, declared, target)
        return target
    }
}
