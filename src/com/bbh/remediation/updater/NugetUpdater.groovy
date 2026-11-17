package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class NugetUpdater implements ManifestUpdater {

    @NonCPS
    private static String packageRefRe() {
        return '(?is)<(PackageReference|PackageVersion)\\b([^>]*?)(?:\\s*/>|>(.*?)</\\1\\s*>)'
    }

    @NonCPS
    private static String packagesConfigRe() {
        return '(?is)<package\\b([^>]*?)/?>'
    }

    @NonCPS
    private static String propertyGroupRe() {
        return '(?is)(<PropertyGroup\\b[^>]*>)(.*?)(</PropertyGroup\\s*>)'
    }

    @NonCPS
    private static String attrRe(String attrName) {
        return '(?i)(\\b' + attrName + '\\s*=\\s*)(["\'])([^"\']*)(\\2)'
    }

    @NonCPS
    private static String tagRe(String tagName) {
        return '(?is)(<' + UpdaterSupport.quote(tagName) + '\\s*>)([^<]*?)(</' + UpdaterSupport.quote(tagName) + '\\s*>)'
    }

    @NonCPS
    private static String propertyRefRe() {
        return '^\\$\\(([A-Za-z_][A-Za-z0-9_.-]*)\\)$'
    }

    private Map catalogs = [:]

    String ecosystem() { return 'nuget' }

    List<String> filePatterns() {
        return ['*.csproj', '*.fsproj', '*.vbproj', 'Directory.Packages.props', 'Directory.Build.props', 'packages.config']
    }

    void useCatalogs(Map catalogs) { this.catalogs = catalogs ?: [:] }

    @NonCPS
    boolean supports(String relativePath) {
        String name = UpdaterSupport.fileName(relativePath).toLowerCase()
        if (name == 'directory.packages.props' || name == 'directory.build.props' || name == 'packages.config') return true
        if (name.endsWith('.csproj') || name.endsWith('.fsproj') || name.endsWith('.vbproj')) return true
        return (catalogs[relativePath] as Map)?.kind == 'nuget'
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        Map byKey = UpdaterSupport.indexByKey(fixes)
        String name = UpdaterSupport.fileName(relativePath).toLowerCase()
        if (name == 'packages.config') {
            return updatePackagesConfig(relativePath, content, byKey)
        }
        return updateMsbuildProject(relativePath, content, byKey)
    }

    @NonCPS
    private Map updatePackagesConfig(String relativePath, String content, Map byKey) {
        List changes = []
        List notes = []
        List found = []
        String updated = UpdaterSupport.replaceMatch(content, packagesConfigRe()) { List groups ->
            String attrs = groups[1] as String
            String pkgId = attrValue(attrs, 'id')
            if (!pkgId) return null
            Map fix = byKey[GoldenFix.key('nuget', '', pkgId)] as Map
            if (!fix) return null

            String declared = attrValue(attrs, 'version')
            if (!declared) {
                notes << UpdaterSupport.note(relativePath, fix, 'version attribute is missing in packages.config')
                return null
            }
            if (!GoldenFix.isConcreteVersion(declared)) {
                notes << UpdaterSupport.note(relativePath, fix, "declared version '${declared}' is a range or floating version".toString())
                return null
            }
            String target = fix.targetVersion as String
            boolean upgrade = GoldenFix.isUpgrade(declared, target)
            found << UpdaterSupport.found(relativePath, [fix.key], upgrade ? target : declared, '')
            if (!upgrade) return null

            changes << UpdaterSupport.change(relativePath, fix, declared, target)
            String replacedAttrs = replaceAttr(attrs, 'version', declared, target)
            String full = groups[0] as String
            return full.endsWith('/>') ? "<package${replacedAttrs}/>" : "<package${replacedAttrs}>"
        }
        return [content: updated, changes: changes, properties: [], notes: notes, found: found]
    }

    @NonCPS
    private Map updateMsbuildProject(String relativePath, String content, Map byKey) {
        List changes = []
        List properties = []
        List notes = []
        List found = []
        String updated = UpdaterSupport.replaceMatch(content, packageRefRe()) { List groups ->
            String tagName = groups[1] as String
            String attrs = groups[2] as String
            String inner = groups[3] as String
            String pkgId = attrValue(attrs, 'Include') ?: attrValue(attrs, 'Update')
            if (!pkgId) return null
            Map fix = byKey[GoldenFix.key('nuget', '', pkgId)] as Map
            if (!fix) return null

            String attrName = attrValue(attrs, 'VersionOverride') != null ? 'VersionOverride' : 'Version'
            String declared = attrValue(attrs, attrName)
            boolean inInner = false
            String innerTag = 'Version'
            if (declared == null && inner != null) {
                if (tagValue(inner, 'VersionOverride') != null) {
                    innerTag = 'VersionOverride'
                    declared = tagValue(inner, 'VersionOverride')
                    inInner = true
                } else if (tagValue(inner, 'Version') != null) {
                    innerTag = 'Version'
                    declared = tagValue(inner, 'Version')
                    inInner = true
                }
            }
            if (declared == null) {
                if (tagName.equalsIgnoreCase('PackageReference')) {
                    notes << UpdaterSupport.note(relativePath, fix, 'version is not declared here (managed via Directory.Packages.props or SDK)')
                }
                return null
            }
            String prop = propertyName(declared)
            if (prop) {
                properties << UpdaterSupport.propertyRequest(prop, fix, relativePath)
                return null
            }
            if (!GoldenFix.isConcreteVersion(declared)) {
                notes << UpdaterSupport.note(relativePath, fix, "declared version '${declared}' is a range or floating version".toString())
                return null
            }
            String target = fix.targetVersion as String
            boolean upgrade = GoldenFix.isUpgrade(declared, target)
            found << UpdaterSupport.found(relativePath, [fix.key], upgrade ? target : declared, '')
            if (!upgrade) return null

            changes << UpdaterSupport.change(relativePath, fix, declared, target)
            if (inInner) {
                String newInner = replaceTag(inner, innerTag, declared, target)
                return "<${tagName}${attrs}>${newInner}</${tagName}>"
            }
            String newAttrs = replaceAttr(attrs, attrName, declared, target)
            if (inner == null) {
                String full = groups[0] as String
                String ending = full.endsWith(' />') ? ' />' : '/>'
                return "<${tagName}${newAttrs}${ending}"
            }
            return "<${tagName}${newAttrs}>${inner}</${tagName}>"
        }
        return [content: updated, changes: changes, properties: properties, notes: notes, found: found]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        List changes = []
        List found = []
        String updated = UpdaterSupport.replaceValue(content, propertyGroupRe()) { String section ->
            String newSection = section
            properties.each { property ->
                String name = property.name as String
                String target = property.targetVersion as String
                newSection = UpdaterSupport.replaceValue(newSection, tagRe(name)) { String value ->
                    String trimmed = (value ?: '').trim()
                    if (!GoldenFix.isConcreteVersion(trimmed)) return null
                    boolean upgrade = GoldenFix.isUpgrade(trimmed, target)
                    found << UpdaterSupport.found(relativePath, property.componentKeys as List, upgrade ? target : trimmed, name)
                    if (!upgrade) return null
                    changes << UpdaterSupport.propertyChange(relativePath, property, trimmed)
                    return target
                }
            }
            return newSection == section ? null : newSection
        }
        return [content: updated, changes: changes, found: found]
    }

    @NonCPS
    private static String attrValue(String attrs, String name) {
        def m = (attrs ?: '') =~ attrRe(name)
        return m.find() ? ((m[0] as List)[3] as String)?.trim() : null
    }

    @NonCPS
    private static String replaceAttr(String attrs, String name, String expected, String target) {
        return UpdaterSupport.replaceMatch(attrs, attrRe(name)) { List groups ->
            String val = ((groups[3] ?: '') as String).trim()
            if (val != expected) return null
            return "${groups[1]}${groups[2]}${target}${groups[4]}"
        }
    }

    @NonCPS
    private static String tagValue(String body, String name) {
        def m = (body ?: '') =~ tagRe(name)
        return m.find() ? ((m[0] as List)[2] as String)?.trim() : null
    }

    @NonCPS
    private static String replaceTag(String body, String name, String expected, String target) {
        return UpdaterSupport.replaceValue(body, tagRe(name)) { String value ->
            return (value ?: '').trim() == expected ? target : null
        }
    }

    @NonCPS
    private static String propertyName(String declared) {
        def m = (declared ?: '').trim() =~ propertyRefRe()
        return m.find() ? ((m[0] as List)[1] as String) : null
    }
}
