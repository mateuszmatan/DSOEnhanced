package com.bbh.config

import com.cloudbees.groovy.cps.NonCPS

class SettingsAdvisor implements Serializable {

    @NonCPS
    static List<String> ownKeys() {
        return ['staticAnalysis.parallel', 'dynamicAnalysis.parallel', 'asoc.keyId', 'asoc.token', 'asoc.credentialsId',
                'sast.keyId', 'sast.token', 'sast.credentialsId', 'dast.keyId', 'dast.token', 'dast.credentialsId']
    }

    @NonCPS
    static List<String> resolvedKeys() {
        return ['buildTool', 'deployTarget', 'appName', 'buildToolAutoSetup', 'tools.sonar.projectKey', 'tools.nexusIq.scanPatterns',
                'build.gradle.tasks', 'tests.unitTests.gradle.tasks', 'build.maven.goals', 'build.maven.flags', 'tests.unitTests.maven.goals',
                'build.node.enabled', 'build.node.dir', 'build.node.version', 'tests.unitTests.node.cypress', 'tests.unitTests.cypress.enabled',
                'build.liquibase.commands', 'build.liquibase.flags', 'tests.unitTests.liquibase.commands',
                'tests.unitTests.liquibase.flags', 'flutter.platform', 'database.searchPath']
    }

    @NonCPS
    static Map<String, Object> leaves(Map source) {
        Map<String, Object> out = [:]
        collect(source, '', out)
        return out
    }

    @NonCPS
    private static void collect(def node, String prefix, Map<String, Object> out) {
        if (!(node instanceof Map)) {
            if (prefix) out[prefix] = node
            return
        }
        for (def entry : (node as Map).entrySet()) {
            collect(entry.value, prefix ? "${prefix}.${entry.key}".toString() : entry.key.toString(), out)
        }
    }

    @NonCPS
    static String display(def value) {
        if (value instanceof Collection) return "[${(value as Collection).collect { SettingsAdvisor.display(it) }.join(', ')}]".toString()
        return value == null ? '' : value.toString()
    }

    @NonCPS
    static boolean has(Map source, String path) {
        def node = source
        for (String part : path.tokenize('.')) {
            if (!(node instanceof Map) || !(node as Map).containsKey(part)) return false
            node = (node as Map)[part]
        }
        if (node == null) return false
        if (node instanceof CharSequence) return node.toString().trim() as boolean
        if (node instanceof Collection || node instanceof Map) return !node.isEmpty()
        return true
    }

    @NonCPS
    static def valueAt(Map source, String path) {
        def node = source
        for (String part : path.tokenize('.')) {
            if (!(node instanceof Map)) return null
            node = (node as Map)[part]
        }
        return node
    }

    @NonCPS
    static Map copy(Map source) {
        Map out = [:]
        for (def entry : (source ?: [:]).entrySet()) out[entry.key] = copyValue(entry.value)
        return out
    }

    @NonCPS
    static def copyValue(def value) {
        if (value instanceof Map) return copy(value as Map)
        if (value instanceof List) return (value as List).collect { SettingsAdvisor.copyValue(it) }
        return value
    }

    @NonCPS
    static Map without(Map source, List<String> paths) {
        Map out = copy(source)
        for (String path : paths) {
            List<String> parts = path.tokenize('.')
            Map node = out
            for (int i = 0; i < parts.size() - 1 && node != null; i++) {
                def child = node[parts[i]]
                node = child instanceof Map ? child as Map : null
            }
            if (node != null) node.remove(parts[parts.size() - 1])
        }
        return out
    }

    @NonCPS
    static List<Map> invalidValues(Map own, Map defaults) {
        Map<String, Object> builtIn = leaves(defaults)
        List<Map> out = []
        for (def entry : leaves(own).entrySet()) {
            String field = entry.key as String
            if (ownKeys().contains(field) || !builtIn.containsKey(field) || entry.value == null) continue
            def fallback = builtIn[field]
            String kind = ''
            if (fallback instanceof Boolean && !isBoolean(entry.value)) kind = 'true or false'
            if (fallback instanceof Number && !isNumber(entry.value)) kind = 'a number'
            if (kind) out << [field: field, value: display(entry.value), kind: kind, fallback: display(fallback)]
        }
        for (def entry : builtIn.entrySet()) {
            def value = valueAt(own, entry.key as String)
            if (entry.value instanceof List && value instanceof Map) {
                out << [field: entry.key, value: display(leaves(value as Map).keySet()), kind: 'a list', fallback: display(entry.value)]
            }
        }
        return out
    }

    @NonCPS
    private static boolean isBoolean(def value) {
        if (value instanceof Boolean) return true
        return (value as String).trim().toLowerCase() in ['true', 'false', 'yes', 'no', 'y', 'n', 'on', 'off', '1', '0']
    }

    @NonCPS
    private static boolean isNumber(def value) {
        if (value instanceof Number) return true
        return (value as String).trim() ==~ /-?\d+(\.\d+)?/
    }

    @NonCPS
    static List<Map> sameAsDefaults(Map own, Map defaults) {
        Map<String, Object> builtIn = leaves(defaults)
        List<String> skipped = ownKeys() + resolvedKeys()
        List<Map> out = []
        for (def entry : leaves(own).entrySet()) {
            String field = entry.key as String
            if (skipped.contains(field) || !builtIn.containsKey(field) || entry.value == null) continue
            if (display(entry.value) == display(builtIn[field])) out << [field: field, value: display(entry.value)]
        }
        return out
    }

    @NonCPS
    static List<String> setResolvedKeys(Map own) {
        return resolvedKeys().findAll { String field -> SettingsAdvisor.has(own, field) }
    }
}
