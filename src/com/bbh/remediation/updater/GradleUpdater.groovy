package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.model.VersionCatalogs
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class GradleUpdater implements ManifestUpdater {

    private Map catalogs = [:]

    @NonCPS
    private static String propertyRefRe() { return '^\\$\\{?([A-Za-z_][\\w.]*)\\}?$' }

    @NonCPS
    private static String catalogRefRe() { return '^\\$\\{?([A-Za-z_]\\w*)\\.versions\\.([\\w.]+?)(?:\\.get\\(\\))?\\}?$' }

    @NonCPS
    private static String tomlVersionsRe() { return '(?ms)(^[ \\t]*\\[[ \\t]*versions[ \\t]*\\][ \\t]*(?:#[^\\r\\n]*)?$)(.*?)(?=^[ \\t]*\\[|\\z)' }

    @NonCPS
    private static String tomlLibrariesRe() { return '(?ms)(^[ \\t]*\\[[ \\t]*libraries[ \\t]*\\][ \\t]*(?:#[^\\r\\n]*)?$)(.*?)(?=^[ \\t]*\\[|\\z)' }

    @NonCPS
    private static String tomlTableRe() { return '(?ms)(^[ \\t]*\\[[ \\t]*libraries[ \\t]*\\.[ \\t]*([\'"]?)([\\w.-]+)\\2[ \\t]*\\][ \\t]*(?:#[^\\r\\n]*)?$)(.*?)(?=^[ \\t]*\\[|\\z)' }

    @NonCPS
    private static String tomlEntryRe() { return '(?m)(^[ \\t]*[\'"]?([\\w.-]+)[\'"]?[ \\t]*=[ \\t]*)(\\{[^{}\\r\\n]*(?:\\{[^{}\\r\\n]*\\}[^{}\\r\\n]*)*\\}|"[^"\\r\\n]*"|\'[^\'\\r\\n]*\')' }

    @NonCPS
    private static String inlineRe(String group, String artifact) {
        return '([\'"]' + group + ':' + artifact + ':)([^\'":@\\s]+)((?::[^\'"@\\s]*)?(?:@[^\'"\\s]*)?[\'"])'
    }

    @NonCPS
    private static String richRe() { return '(\\b(?:strictly|require|prefer)\\s*[=(]?\\s*[\'"])([^\'"]+)([\'"])' }

    @NonCPS
    private static String versionRefRe() { return '\\bversion(?:\\.ref\\s*=|\\s*=\\s*\\{\\s*ref\\s*=)\\s*[\'"]([^\'"]+)[\'"]' }

    String ecosystem() { return 'maven' }

    List<String> filePatterns() { return ['build.gradle', 'build.gradle.kts', 'gradle.properties', '*.versions.toml', 'settings.gradle', 'settings.gradle.kts'] }

    void useCatalogs(Map catalogs) { this.catalogs = catalogs ?: [:] }

    @NonCPS
    boolean supports(String relativePath) {
        String name = UpdaterSupport.fileName(relativePath)
        return name in ['build.gradle', 'build.gradle.kts', 'gradle.properties', 'settings.gradle', 'settings.gradle.kts'] ||
                name.endsWith('.versions.toml') || (catalogs[relativePath] as Map)?.kind in ['toml', 'gradle']
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        Map r = [content: content, changes: [], properties: [], notes: [], found: []]
        String format = formatOf(relativePath)
        if (format == 'properties') return r

        String updated = content
        fixes.each { fix ->
            String group = UpdaterSupport.quote(fix.group as String)
            String artifact = UpdaterSupport.quote(fix.name as String)
            List declarations = format == 'toml' ? [] : [inlineRe(group, artifact),
                    '(group\\s*[:=]\\s*[\'"]' + group + '[\'"]\\s*,\\s*name\\s*[:=]\\s*[\'"]' + artifact + '[\'"]\\s*,\\s*version\\s*[:=]\\s*[\'"])([^\'"]+)([\'"])']
            declarations.each { regex ->
                updated = UpdaterSupport.replaceValue(updated, regex) { String declared -> declare(relativePath, fix, declared, '', r) }
            }
            if (format == 'toml') {
                updated = tomlLibraries(relativePath, updated, fix, r)
                UpdaterSupport.bomNotes(relativePath, fix, tomlBoms(updated), r.notes as List)
            } else {
                updated = UpdaterSupport.replaceValue(updated, '([\'"]' + group + ':' + artifact + '[\'"]\\s*\\)?\\s*\\{\\s*version\\s*\\{)([^{}]*)(\\})') { String rich ->
                    richVersions(relativePath, fix, rich, '', r)
                }
                List boms = []
                def platform = updated =~ /(?:enforcedPlatform|platform)\s*\(\s*['"]([^'":]+):([^'":]+):([^'"]+)['"]/
                while (platform.find()) boms << [platform.group(1), platform.group(2), platform.group(3)]
                UpdaterSupport.bomNotes(relativePath, fix, boms, r.notes as List)
            }
            if (format == 'settings') updated = settingsLibraries(relativePath, updated, fix, group, artifact, r)
        }
        r.content = updated
        return r
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        Map r = [content: content, changes: [], notes: [], found: []]
        String format = formatOf(relativePath)
        String updated = content

        properties.each { property ->
            Map catalog = property.catalog as Map
            if (catalog) {
                if (format == 'toml' && inCatalog(catalog, relativePath)) updated = tomlVersions(relativePath, updated, property, r)
                if (format == 'settings' && inCatalog(catalog, relativePath)) updated = settingsVersions(relativePath, updated, property, r)
                return
            }
            String name = UpdaterSupport.quote(property.name as String)
            Closure bump = { String value -> raise(relativePath, property, value, property.name as String, r) }

            if (format == 'properties') {
                updated = UpdaterSupport.replaceValue(updated, '(?m)(^[ \\t]*' + name + '[ \\t]*[=:][ \\t]*)([^\\s#]+)', 1, 2, 0, bump)
            } else if (format == 'toml') {
                updated = UpdaterSupport.replaceValue(updated, tomlVersionsRe(), 1, 2, 0) { String section ->
                    String replaced = UpdaterSupport.replaceValue(section, '(?m)(^[ \\t]*"?' + name + '"?[ \\t]*=[ \\t]*")([^"]+)(")', bump)
                    return replaced == section ? null : replaced
                }
            } else {
                List patterns = [
                        '(?<![\\w.])((?:(?:rootProject|project)\\.)?(?:ext(?:ra)?\\.)?' + name + '\\s*=\\s*[\'"])([^\'"\\r\\n]+)([\'"])',
                        '((?:extra\\[\\s*"' + name + '"\\s*\\]\\s*=|set\\(\\s*[\'"]' + name + '[\'"]\\s*,|' + name + '\\s+by\\s+extra\\()\\s*[\'"])([^\'"\\r\\n]+)([\'"])',
                        '(?<![\\w.])([\'"]?' + name + '[\'"]?\\s*:\\s*[\'"])([^\'"\\r\\n]+)([\'"])'
                ]
                patterns.each { regex -> updated = UpdaterSupport.replaceValue(updated, regex, bump) }
            }
        }
        r.content = updated
        return r
    }

    @NonCPS
    private String declare(String path, Map fix, String declared, String alias, Map r) {
        def reference = (declared ?: '') =~ catalogRefRe()
        if (reference.matches() && knownCatalog(reference.group(1))) {
            String catalog = reference.group(1)
            r.properties << UpdaterSupport.propertyRequest(reference.group(2), fix, path, [name: catalog, file: catalogFile(catalog, path)])
            return null
        }
        reference = (declared ?: '') =~ propertyRefRe()
        if (reference.matches()) {
            String name = reference.group(1)
            if (!name.startsWith('libs.')) {
                List segments = name.tokenize('.')
                r.properties << UpdaterSupport.propertyRequest(segments[segments.size() - 1] as String, fix, path)
            }
            return null
        }
        if (!GoldenFix.isConcreteVersion(declared)) {
            r.notes << note(path, fix, alias, "declared version '${declared}' is dynamic or a range".toString())
            return null
        }
        String target = fix.targetVersion as String
        boolean upgrade = GoldenFix.isUpgrade(declared, target)
        r.found << UpdaterSupport.found(path, [fix.key], upgrade ? target : declared, alias)
        if (!upgrade) return null
        Map change = UpdaterSupport.change(path, fix, declared, target)
        if (alias) change.property = alias
        r.changes << change
        return target
    }

    @NonCPS
    private String raise(String path, Map property, String value, String key, Map r) {
        String target = property.targetVersion as String
        if (!GoldenFix.isConcreteVersion(value)) {
            if (property.catalog) {
                r.notes.addAll(UpdaterSupport.propertyNotes(property, UpdaterSupport.catalogReason(path, key, "declared version '${value}' is dynamic or a range")))
            }
            return null
        }
        boolean upgrade = GoldenFix.isUpgrade(value, target)
        r.found << UpdaterSupport.found(path, property.componentKeys as List, upgrade ? target : value, key)
        if (!upgrade) return null
        r.changes << UpdaterSupport.propertyChange(path, property + [name: key], value)
        return target
    }

    @NonCPS
    private String tomlLibraries(String path, String text, Map fix, Map r) {
        String updated = UpdaterSupport.replaceValue(text, tomlLibrariesRe(), 1, 2, 0) { String section ->
            String replaced = UpdaterSupport.replaceMatch(section, tomlEntryRe()) { List g ->
                String alias = g[2] as String
                String value = g[3] as String
                String entry = value.startsWith('{') ? libraryEntry(path, alias, value, fix, r)
                        : UpdaterSupport.replaceValue(value, inlineRe(UpdaterSupport.quote(fix.group as String), UpdaterSupport.quote(fix.name as String))) { String declared ->
                    declare(path, fix, declared, alias, r)
                }
                return entry == null || entry == value ? null : (g[1] as String) + entry
            }
            return replaced == section ? null : replaced
        }
        return UpdaterSupport.replaceMatch(updated, tomlTableRe()) { List g ->
            String entry = libraryEntry(path, g[3] as String, g[4] as String, fix, r)
            return entry == null ? null : (g[1] as String) + entry
        }
    }

    @NonCPS
    private String libraryEntry(String path, String alias, String entry, Map fix, Map r) {
        String group = UpdaterSupport.quote(fix.group as String)
        String artifact = UpdaterSupport.quote(fix.name as String)
        boolean module = (entry =~ ('\\bmodule\\s*=\\s*[\'"]' + group + ':' + artifact + '[\'"]')).find() ||
                ((entry =~ ('\\bgroup\\s*=\\s*[\'"]' + group + '[\'"]')).find() && (entry =~ ('\\bname\\s*=\\s*[\'"]' + artifact + '[\'"]')).find())
        if (!module) return null
        def ref = entry =~ versionRefRe()
        if (ref.find()) {
            r.properties << UpdaterSupport.propertyRequest(ref.group(1), fix, path, [name: catalogName(path), file: path])
            return null
        }
        if (excluded(path, fix, entry, alias, r)) return null
        String updated = UpdaterSupport.replaceValue(entry, '(\\bversion\\s*=\\s*[\'"])([^\'"]+)([\'"])') { String declared -> declare(path, fix, declared, alias, r) }
        updated = UpdaterSupport.replaceValue(updated, '(\\bversion\\s*=\\s*\\{)([^}]*)(\\})') { String rich -> richVersions(path, fix, rich, alias, r) }
        updated = UpdaterSupport.replaceValue(updated, '(\\bversion\\.(?:strictly|require|prefer)\\s*=\\s*[\'"])([^\'"]+)([\'"])') { String declared ->
            declare(path, fix, declared, alias, r)
        }
        return updated == entry ? null : updated
    }

    @NonCPS
    private String richVersions(String path, Map fix, String rich, String alias, Map r) {
        if (excluded(path, fix, rich, alias, r)) return null
        String updated = UpdaterSupport.replaceValue(rich, richRe()) { String declared -> declare(path, fix, declared, alias, r) }
        return updated == rich ? null : updated
    }

    @NonCPS
    private String settingsLibraries(String path, String text, Map fix, String group, String artifact, Map r) {
        String library = 'library\\s*\\(\\s*[\'"]([^\'"]+)[\'"]\\s*,\\s*[\'"]' + group + '[\'"]\\s*,\\s*[\'"]' + artifact + '[\'"]\\s*\\)\\s*\\.version'
        String updated = UpdaterSupport.replaceMatch(text, '(' + library + '\\s*\\(\\s*[\'"])([^\'"]+)([\'"])') { List g ->
            String raised = declare(path, fix, g[3] as String, g[2] as String, r)
            return raised == null ? null : (g[1] as String) + raised + (g[4] as String)
        }
        List<Map> blocks = VersionCatalogs.settingsBlocks(updated)
        def reference = updated =~ (library + 'Ref\\s*\\(\\s*[\'"]([^\'"]+)[\'"]')
        while (reference.find()) {
            int at = reference.start()
            Map block = blocks.find { (it.start as int) <= at && at < (it.end as int) } as Map
            String catalog = (block?.name ?: 'libs') as String
            r.properties << UpdaterSupport.propertyRequest(reference.group(2), fix, path, [name: catalog, file: path])
        }
        return updated
    }

    @NonCPS
    private String tomlVersions(String path, String text, Map property, Map r) {
        String wanted = VersionCatalogs.alias(property.name as String)
        return UpdaterSupport.replaceValue(text, tomlVersionsRe(), 1, 2, 0) { String section ->
            String replaced = UpdaterSupport.replaceMatch(section, tomlEntryRe()) { List g ->
                String key = g[2] as String
                if (VersionCatalogs.alias(key) != wanted) return null
                String value = g[3] as String
                String range = excludingRange(value, property.targetVersion as String)
                if (range) {
                    r.notes.addAll(UpdaterSupport.propertyNotes(property, UpdaterSupport.catalogReason(path, key, "${range} excludes ${property.targetVersion}")))
                    return null
                }
                String next = UpdaterSupport.replaceValue(value, value.startsWith('{') ? richRe() : '([\'"])([^\'"]*)([\'"])') { String v ->
                    raise(path, property, v, key, r)
                }
                return next == value ? null : (g[1] as String) + next
            }
            return replaced == section ? null : replaced
        }
    }

    @NonCPS
    private String settingsVersions(String path, String text, Map property, Map r) {
        String wanted = VersionCatalogs.alias(property.name as String)
        String name = (property.catalog as Map).name
        Map block = VersionCatalogs.settingsBlocks(text).find { it.name == name } as Map
        if (!block) return text
        int start = block.start as int
        int end = block.end as int
        String inner = UpdaterSupport.replaceMatch(text.substring(start, end), '(\\bversion\\s*\\(\\s*[\'"]([\\w.-]+)[\'"]\\s*,\\s*[\'"])([^\'"]+)([\'"])') { List g ->
            if (VersionCatalogs.alias(g[2] as String) != wanted) return null
            String raised = raise(path, property, g[3] as String, g[2] as String, r)
            return raised == null ? null : (g[1] as String) + raised + (g[4] as String)
        }
        return text.substring(0, start) + inner + text.substring(end)
    }

    @NonCPS
    private List tomlBoms(String text) {
        List out = []
        def bom = text =~ /(?m)^.*"([^":\s]+):([^":\s]+-bom)(?::([^"]+))?".*$/
        while (bom.find()) {
            String line = bom.group(0)
            String version = bom.group(3)
            def direct = line =~ /\bversion\s*=\s*"([^"]+)"/
            def ref = line =~ versionRefRe()
            if (!version && direct.find()) version = direct.group(1)
            if (!version && ref.find()) version = tomlVersion(text, ref.group(1))
            out << [bom.group(1), bom.group(2), version]
        }
        return out
    }

    @NonCPS
    private String tomlVersion(String text, String name) {
        def section = text =~ tomlVersionsRe()
        if (!section.find()) return null
        def entry = section.group(2) =~ /(?m)^[ \t]*['"]?([\w.-]+)['"]?[ \t]*=[ \t]*"([^"]+)"/
        while (entry.find()) {
            if (VersionCatalogs.alias(entry.group(1)) == VersionCatalogs.alias(name)) return entry.group(2)
        }
        return null
    }

    @NonCPS
    private boolean excluded(String path, Map fix, String rich, String alias, Map r) {
        String range = excludingRange(rich, fix.targetVersion as String)
        if (range) r.notes << note(path, fix, alias, "${range} excludes ${fix.targetVersion}".toString())
        return range != null
    }

    @NonCPS
    private static String excludingRange(String rich, String target) {
        def declared = (rich ?: '') =~ /\b(strictly|require)\s*[=(]?\s*['"]([^'"]+)['"]/
        String kind = null
        String range = null
        while (declared.find() && kind != 'strictly') {
            kind = declared.group(1)
            range = declared.group(2).trim()
        }
        if (range == null) return null
        def bounds = range =~ /^([\[(\]])\s*([^,]*?)\s*,\s*([^,]*?)\s*([\])\[])$/
        if (!bounds.matches()) return null
        int low = bounds.group(2) ? gradleOrder(target, bounds.group(2)) : 1
        int high = bounds.group(3) ? gradleOrder(target, bounds.group(3)) : -1
        boolean outside = low < 0 || (low == 0 && bounds.group(1) != '[') || high > 0 || (high == 0 && bounds.group(4) != ']')
        return outside ? "${kind} range '${range}'".toString() : null
    }

    @NonCPS
    private static int gradleOrder(String version, String bound) {
        List a = versionParts(version)
        List b = versionParts(bound)
        int common = Math.min(a.size(), b.size())
        for (int i = 0; i < common; i++) {
            String x = a[i] as String
            String y = b[i] as String
            boolean numeric = x ==~ /\d+/
            if (numeric != (y ==~ /\d+/)) return numeric ? 1 : -1
            int order = numeric ? GoldenFix.compare(x, y) : (qualifierRank(x) - qualifierRank(y) ?: x.compareTo(y))
            if (order != 0) return order
        }
        if (a.size() == b.size()) return 0
        boolean longer = a.size() > b.size()
        String extra = (longer ? a[common] : b[common]) as String
        return (extra ==~ /\d+/) == longer ? 1 : -1
    }

    @NonCPS
    private static List versionParts(String version) {
        List out = []
        def part = (version ?: '') =~ /\d+|[^\d.\-_+]+/
        while (part.find()) out << part.group(0)
        return out
    }

    @NonCPS
    private static int qualifierRank(String part) {
        int rank = ['dev', 'rc', 'snapshot', 'final', 'ga', 'release', 'sp'].indexOf(part.toLowerCase())
        return rank == 0 ? -1 : Math.max(rank, 0)
    }

    @NonCPS
    private Map note(String path, Map fix, String alias, String why) {
        if (!catalogs.containsKey(path)) return UpdaterSupport.note(path, fix, why)
        return UpdaterSupport.note('', fix, UpdaterSupport.catalogReason(path, alias, why))
    }

    @NonCPS
    private boolean knownCatalog(String name) {
        if (name == 'libs') return true
        for (def entry : catalogs.values()) {
            if (((entry as Map).names as List)?.contains(name)) return true
        }
        return false
    }

    @NonCPS
    private String catalogFile(String name, String path) {
        String best = ''
        int depth = -1
        for (def entry : catalogs.values()) {
            Map e = entry as Map
            if (!(e.names as List)?.contains(name)) continue
            if (!best) best = e.path as String
            for (def root : (e.consumers as List)) {
                String folder = root as String
                if ((folder == '' || path.startsWith(folder + '/')) && folder.length() > depth) {
                    best = e.path as String
                    depth = folder.length()
                }
            }
        }
        return best
    }

    @NonCPS
    private String catalogName(String path) {
        List names = ((catalogs[path] as Map)?.names ?: []) as List
        return names ? names[0] as String : VersionCatalogs.baseName(path)
    }

    @NonCPS
    private boolean inCatalog(Map catalog, String path) {
        if (catalog.file) return catalog.file == path
        List names = ((catalogs[path] as Map)?.names ?: [VersionCatalogs.baseName(path)]) as List
        return names.contains(catalog.name)
    }

    @NonCPS
    private static String formatOf(String path) {
        String name = UpdaterSupport.fileName(path)
        if (name.endsWith('.properties')) return 'properties'
        if (name.endsWith('.toml')) return 'toml'
        if (name in ['settings.gradle', 'settings.gradle.kts']) return 'settings'
        return 'script'
    }
}
