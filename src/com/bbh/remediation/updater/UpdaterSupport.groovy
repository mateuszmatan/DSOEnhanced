package com.bbh.remediation.updater

import com.cloudbees.groovy.cps.NonCPS

class UpdaterSupport implements Serializable {

    @NonCPS
    static String fileName(String path) {
        String normalized = (path ?: '').replace('\\', '/')
        int index = normalized.lastIndexOf('/')
        return index >= 0 ? normalized.substring(index + 1) : normalized
    }

    @NonCPS
    static Map indexByKey(List fixes) {
        Map index = [:]
        (fixes ?: []).each { index[it.key as String] = it }
        return index
    }

    @NonCPS
    static String quote(String literal) {
        return (literal ?: '').replaceAll(/([\\\.\[\]\{\}\(\)\*\+\-\?\^\$\|\/])/, '\\\\$1')
    }

    @NonCPS
    static String replaceValue(String text, String regex, int prefixGroup, int valueGroup, int suffixGroup, Closure replacement) {
        if (text == null) return null
        return text.replaceAll(regex) { List groups ->
            String value = groups[valueGroup] as String
            String replaced = replacement.call(value) as String
            if (replaced == null) return groups[0] as String
            String prefix = prefixGroup > 0 ? (groups[prefixGroup] ?: '') as String : ''
            String suffix = suffixGroup > 0 ? (groups[suffixGroup] ?: '') as String : ''
            return prefix + replaced + suffix
        }
    }

    @NonCPS
    static String replaceValue(String text, String regex, Closure replacement) {
        return replaceValue(text, regex, 1, 2, 3, replacement)
    }

    @NonCPS
    static String replaceMatch(String text, String regex, Closure replacement) {
        if (text == null) return null
        return text.replaceAll(regex) { List groups ->
            String replaced = replacement.call(groups) as String
            return replaced == null ? (groups[0] as String) : replaced
        }
    }

    @NonCPS
    static List findAll(String text, String regex, int group) {
        List out = []
        def matcher = (text ?: '') =~ regex
        matcher.each { match -> out << ((match instanceof List) ? match[group] : match) }
        return out
    }

    @NonCPS
    static Map change(String path, Map fix, String from, String to) {
        return [
                file           : path,
                ecosystem      : fix.ecosystem,
                component      : fix.displayName,
                componentKeys  : [fix.key],
                from           : from,
                to             : to,
                property       : '',
                remediationType: fix.remediationType ?: '',
                golden         : fix.golden ? true : false
        ]
    }

    @NonCPS
    static Map propertyChange(String path, Map property, String from) {
        return [
                file           : path,
                ecosystem      : property.ecosystem,
                component      : (property.components as List).join(', '),
                componentKeys  : [] + (property.componentKeys as List),
                from           : from,
                to             : property.targetVersion,
                property       : property.name,
                remediationType: ''
        ]
    }

    @NonCPS
    static Map note(String path, Map fix, String reason) {
        return [
                file        : path,
                component   : fix.displayName,
                componentKey: fix.key,
                from        : fix.currentVersion,
                to          : fix.targetVersion,
                reason      : reason
        ]
    }

    @NonCPS
    static Map propertyRequest(String name, Map fix, String path) {
        return [
                name         : name,
                ecosystem    : fix.ecosystem,
                targetVersion: fix.targetVersion,
                componentKeys: [fix.key],
                components   : [fix.displayName],
                referencedIn : [path]
        ]
    }
}
