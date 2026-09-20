package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class PipUpdater implements ManifestUpdater {

    String ecosystem() { return 'pypi' }

    List<String> filePatterns() { return ['requirements*.txt', 'constraints*.txt', 'pyproject.toml'] }

    @NonCPS
    boolean supports(String relativePath) {
        String name = UpdaterSupport.fileName(relativePath)
        return (name ==~ /(?i)(requirements|constraints).*\.txt/) || name == 'pyproject.toml'
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        List changes = []
        List notes = []
        boolean pyproject = UpdaterSupport.fileName(relativePath) == 'pyproject.toml'
        String updated = content

        fixes.each { fix ->
            String namePattern = namePattern(fix.name as String)
            if (!pyproject) {
                String regex = '(?im)(^[ \\t]*' + namePattern + '(?:[ \\t]*\\[[^\\]\\r\\n]*\\])?[ \\t]*)(===|==|~=|>=)([ \\t]*)([^\\s;,#\\\\]+)([^\\r\\n]*)'
                if (content.contains('--hash=')) {
                    if (UpdaterSupport.findAll(updated, regex, 4)) {
                        notes << UpdaterSupport.note(relativePath, fix, 'file uses --hash pinning - regenerate it with pip-compile')
                    }
                    return
                }
                updated = UpdaterSupport.replaceMatch(updated, regex) { List g ->
                    String replaced = bump(relativePath, fix, g[2] as String, g[4] as String, g[5] as String, changes, notes)
                    return replaced == null ? null : (g[1] as String) + (g[2] as String) + (g[3] as String) + replaced + (g[5] as String)
                }
            } else {
                String pep508 = '(?i)([\'"]' + namePattern + '(?:\\[[^\\]\'"]*\\])?\\s*)(===|==|~=|>=)(\\s*)([^\'",;\\s]+)([^\'"]*[\'"])'
                updated = UpdaterSupport.replaceMatch(updated, pep508) { List g ->
                    String replaced = bump(relativePath, fix, g[2] as String, g[4] as String, g[5] as String, changes, notes)
                    return replaced == null ? null : (g[1] as String) + (g[2] as String) + (g[3] as String) + replaced + (g[5] as String)
                }
                List poetry = [
                        '(?im)(^[ \\t]*[\'"]?' + namePattern + '[\'"]?[ \\t]*=[ \\t]*"(?:\\^|~|==|>=)?[ \\t]*)(\\d[^"]*)(")',
                        '(?im)(^[ \\t]*[\'"]?' + namePattern + '[\'"]?[ \\t]*=[ \\t]*\\{[^}\\r\\n]*?version[ \\t]*=[ \\t]*"(?:\\^|~|==|>=)?[ \\t]*)(\\d[^"]*)(")'
                ]
                poetry.each { regex ->
                    updated = UpdaterSupport.replaceValue(updated, regex) { String declared ->
                        return bump(relativePath, fix, '==', declared, '', changes, notes)
                    }
                }
            }
        }
        return [content: updated, changes: changes, properties: [], notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        return [content: content, changes: []]
    }

    @NonCPS
    private static String bump(String path, Map fix, String operator, String declared, String rest, List changes, List notes) {
        String target = fix.targetVersion as String
        if (operator == '>=' && (rest ?: '').contains('<')) {
            notes << UpdaterSupport.note(path, fix, "requirement '${operator}${declared}${rest.trim()}' has an upper bound - update it manually".toString())
            return null
        }
        if (!GoldenFix.isConcreteVersion(declared)) {
            notes << UpdaterSupport.note(path, fix, "declared version '${declared}' is not a simple version".toString())
            return null
        }
        if (!GoldenFix.isUpgrade(declared, target)) return null
        changes << UpdaterSupport.change(path, fix, declared, target)
        return target
    }

    @NonCPS
    private static String namePattern(String name) {
        return GoldenFix.normalizePypiName(name).tokenize('-').collect { UpdaterSupport.quote(it as String) }.join('[-_.]+')
    }
}
