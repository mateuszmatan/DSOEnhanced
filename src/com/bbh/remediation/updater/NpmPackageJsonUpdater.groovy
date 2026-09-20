package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class NpmPackageJsonUpdater implements ManifestUpdater {

    @NonCPS
    private static String sectionRe() {
        return '(?s)("(?:dependencies|devDependencies|peerDependencies|optionalDependencies)"\\s*:\\s*\\{)([^{}]*)(\\})'
    }

    String ecosystem() { return 'npm' }

    List<String> filePatterns() { return ['package.json'] }

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
        return [content: updated, changes: changes, properties: [], notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        return [content: content, changes: []]
    }
}
