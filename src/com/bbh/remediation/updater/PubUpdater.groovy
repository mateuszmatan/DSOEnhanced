package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class PubUpdater implements ManifestUpdater {

    String ecosystem() { return 'pub' }

    List<String> filePatterns() { return ['pubspec.yaml', 'pubspec.yml'] }

    @NonCPS
    boolean supports(String relativePath) {
        return UpdaterSupport.fileName(relativePath) ==~ /pubspec\.ya?ml/
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        List changes = []
        List notes = []
        String updated = content

        fixes.each { fix ->
            String name = UpdaterSupport.quote(fix.name as String)
            String target = fix.targetVersion as String
            String regex = '(?m)(^[ \\t]+["\']?' + name + '["\']?[ \\t]*:[ \\t]*["\']?[\\^~]?[ \\t]*)([^\\s"\'#]+)(["\']?[ \\t]*(?:#[^\\r\\n]*)?$)'
            boolean handled = false
            updated = UpdaterSupport.replaceMatch(updated, regex) { List groups ->
                handled = true
                String declared = groups[2] as String
                if (!GoldenFix.isConcreteVersion(declared)) {
                    notes << UpdaterSupport.note(relativePath, fix, "version constraint '${declared}' is not a simple version".toString())
                    return null
                }
                if (!GoldenFix.isUpgrade(declared, target)) return null
                changes << UpdaterSupport.change(relativePath, fix, declared, target)
                return (groups[1] as String) + target + (groups[3] as String)
            }
            if (!handled && UpdaterSupport.findAll(updated, '(?m)^[ \\t]+["\']?' + name + '["\']?[ \\t]*:', 0)) {
                notes << UpdaterSupport.note(relativePath, fix, 'the package is declared without a plain version, update it manually')
            }
        }
        return [content: updated, changes: changes, properties: [], notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        return [content: content, changes: []]
    }
}
