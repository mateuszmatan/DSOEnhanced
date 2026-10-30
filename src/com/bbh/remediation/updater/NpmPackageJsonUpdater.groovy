package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class NpmPackageJsonUpdater implements ManifestUpdater {

    @NonCPS
    private static String sectionRe() {
        return '(?s)("(?:dependencies|devDependencies|peerDependencies|optionalDependencies)"\\s*:\\s*\\{)([^{}]*)(\\})'
    }

    @NonCPS
    private static String overridesRe() {
        return '(?s)("(overrides|resolutions)"\\s*:\\s*\\{)((?:[^{}]|\\{[^{}]*\\})*)(\\})'
    }

    String ecosystem() { return 'npm' }

    List<String> filePatterns() { return ['package.json', 'package-lock.json'] }

    void useCatalogs(Map catalogs) { }

    @NonCPS
    boolean supports(String relativePath) {
        String normalized = (relativePath ?: '').replace('\\', '/')
        return UpdaterSupport.fileName(normalized) == 'package.json' && !normalized.contains('node_modules/')
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        List changes = []
        List notes = []

        String updated = UpdaterSupport.replaceValue(content, sectionRe()) { String section ->
            String newSection = section
            fixes.each { fix ->
                String name = UpdaterSupport.quote(fix.name as String)
                String target = fix.targetVersion as String
                boolean handled = false
                newSection = UpdaterSupport.replaceValue(newSection, '("' + name + '"\\s*:\\s*"(?:\\^|~|>=|=)?\\s*v?)(\\d[\\w.\\-+]*)(")') { String declared ->
                    handled = true
                    if (!GoldenFix.isConcreteVersion(declared) || !GoldenFix.isUpgrade(declared, target)) return null
                    changes << UpdaterSupport.change(relativePath, fix, declared, target)
                    return target
                }
                if (!handled) {
                    List declared = UpdaterSupport.findAll(newSection, '"' + name + '"\\s*:\\s*"([^"]*)"', 1)
                    if (declared) {
                        notes << UpdaterSupport.note(relativePath, fix, "version spec '${declared[0]}' is not a simple version".toString())
                    }
                }
            }
            return newSection == section ? null : newSection
        }
        updated = UpdaterSupport.replaceMatch(updated, overridesRe()) { List s ->
            String section = s[3] as String
            String newSection = section
            fixes.each { fix ->
                String regex = '(\\{[^{}]*\\})|("' + UpdaterSupport.quote(fix.name as String) + '"\\s*:\\s*"(?:\\^|~|>=|=)?\\s*v?)(\\d[\\w.\\-+]*)(")'
                newSection = UpdaterSupport.replaceMatch(newSection, regex) { List g ->
                    String declared = g[3] as String
                    if (g[1] || !GoldenFix.isConcreteVersion(declared) || !GoldenFix.isUpgrade(declared, fix.targetVersion as String)) return null
                    changes << UpdaterSupport.change(relativePath, fix, declared, fix.targetVersion as String) + [property: s[2]]
                    return (g[2] as String) + fix.targetVersion + (g[4] as String)
                }
            }
            return newSection == section ? null : (s[1] as String) + newSection + (s[4] as String)
        }
        return [content: updated, changes: changes, properties: [], notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        return [content: content, changes: []]
    }
}
