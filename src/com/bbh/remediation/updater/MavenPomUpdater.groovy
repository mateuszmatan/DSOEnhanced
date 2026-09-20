package com.bbh.remediation.updater

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.ManifestUpdater
import com.cloudbees.groovy.cps.NonCPS

class MavenPomUpdater implements ManifestUpdater {

    @NonCPS
    private static String dependencyRe() { return '(?s)(<dependency\\s*>)(.*?)(</dependency\\s*>)' }

    @NonCPS
    private static String versionRe() { return '(<version>\\s*)([^<]*?)(\\s*</version>)' }

    @NonCPS
    private static String propertiesRe() { return '(?s)(<properties\\s*>)(.*?)(</properties\\s*>)' }

    @NonCPS
    private static String exclusionsRe() { return '(?s)<exclusions\\s*>.*?</exclusions\\s*>' }

    @NonCPS
    private static String propertyRefRe() { return '^\\$\\{([^}]+)\\}$' }

    String ecosystem() { return 'maven' }

    List<String> filePatterns() { return ['pom.xml'] }

    @NonCPS
    boolean supports(String relativePath) {
        return UpdaterSupport.fileName(relativePath) == 'pom.xml'
    }

    @NonCPS
    Map updateDeclarations(String relativePath, String content, List<Map> fixes) {
        Map byKey = UpdaterSupport.indexByKey(fixes)
        List changes = []
        List properties = []
        List notes = []

        String updated = UpdaterSupport.replaceValue(content, dependencyRe()) { String block ->
            String header = block.replaceAll(exclusionsRe(), '')
            String groupId = tag(header, 'groupId')
            String artifactId = tag(header, 'artifactId')
            Map fix = (groupId && artifactId) ? byKey[GoldenFix.key('maven', groupId, artifactId)] as Map : null
            if (!fix) return null

            String declared = tag(header, 'version')
            if (declared == null) {
                notes << UpdaterSupport.note(relativePath, fix, 'version is not declared here (managed by a parent POM or BOM)')
                return null
            }
            String property = propertyName(declared)
            if (property) {
                properties << UpdaterSupport.propertyRequest(property, fix, relativePath)
                return null
            }
            if (!GoldenFix.isConcreteVersion(declared)) {
                notes << UpdaterSupport.note(relativePath, fix, "declared version '${declared}' is a range or dynamic version".toString())
                return null
            }
            String target = fix.targetVersion as String
            if (!GoldenFix.isUpgrade(declared, target)) return null

            changes << UpdaterSupport.change(relativePath, fix, declared, target)
            return UpdaterSupport.replaceValue(block, versionRe()) { String value -> value == declared ? target : null }
        }
        return [content: updated, changes: changes, properties: properties, notes: notes]
    }

    @NonCPS
    Map updateProperties(String relativePath, String content, List<Map> properties) {
        List changes = []
        String updated = UpdaterSupport.replaceValue(content, propertiesRe()) { String section ->
            String newSection = section
            properties.each { property ->
                String name = property.name as String
                String target = property.targetVersion as String
                String regex = '(<' + UpdaterSupport.quote(name) + '>\\s*)([^<]*?)(\\s*</' + UpdaterSupport.quote(name) + '>)'
                newSection = UpdaterSupport.replaceValue(newSection, regex) { String value ->
                    if (!GoldenFix.isConcreteVersion(value) || !GoldenFix.isUpgrade(value, target)) return null
                    changes << UpdaterSupport.propertyChange(relativePath, property, value)
                    return target
                }
            }
            return newSection == section ? null : newSection
        }
        return [content: updated, changes: changes]
    }

    @NonCPS
    private static String tag(String xml, String name) {
        def matcher = (xml ?: '') =~ ('<' + name + '>\\s*([^<]*?)\\s*</' + name + '>')
        return matcher ? ((matcher[0] as List)[1] as String) : null
    }

    @NonCPS
    private static String propertyName(String declared) {
        def matcher = (declared ?: '') =~ propertyRefRe()
        return matcher ? ((matcher[0] as List)[1] as String) : null
    }
}
