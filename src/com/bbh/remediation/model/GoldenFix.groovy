package com.bbh.remediation.model

import com.cloudbees.groovy.cps.NonCPS

class GoldenFix implements Serializable {

    @NonCPS
    static List<String> supportedEcosystems() {
        return ['maven', 'npm', 'pypi', 'pub']
    }

    @NonCPS
    static List<String> defaultGoldenVersionTypes() {
        return ['recommended-non-breaking-with-dependencies', 'recommended-non-breaking']
    }

    @NonCPS
    static Map create(String ecosystem, String group, String name, String currentVersion, String targetVersion,
                      String remediationType, String packageUrl, int threatLevel, Boolean direct, String application,
                      boolean golden = false) {
        return [
                ecosystem      : ecosystem,
                group          : group ?: '',
                name           : name,
                currentVersion : currentVersion,
                targetVersion  : targetVersion,
                remediationType: remediationType ?: '',
                golden         : golden,
                packageUrl     : packageUrl ?: '',
                threatLevel    : threatLevel,
                direct         : direct,
                application    : application ?: '',
                key            : key(ecosystem, group, name),
                displayName    : displayName(ecosystem, group, name)
        ]
    }

    @NonCPS
    static String key(String ecosystem, String group, String name) {
        switch (ecosystem) {
            case 'pypi': return "pypi:${normalizePypiName(name)}".toString()
            case 'npm':  return "npm:${(name ?: '').toLowerCase()}".toString()
            case 'pub':  return "pub:${(name ?: '').toLowerCase()}".toString()
            default:     return "maven:${group ?: ''}:${name ?: ''}".toString()
        }
    }

    @NonCPS
    static String displayName(String ecosystem, String group, String name) {
        return (ecosystem == 'maven' && group) ? "${group}:${name}".toString() : (name ?: '')
    }

    @NonCPS
    static String normalizePypiName(String name) {
        return (name ?: '').toLowerCase().replaceAll(/[-_.]+/, '-')
    }

    @NonCPS
    static Map selectRemediation(List candidates, String currentVersion, List goldenVersionTypes) {
        List upgrades = []
        for (def candidate : (candidates ?: [])) {
            String version = (candidate?.version ?: '') as String
            if (version && isUpgrade(currentVersion, version)) {
                upgrades << [type: (candidate.type ?: '') as String, version: version]
            }
        }
        if (upgrades.isEmpty()) return null

        List goldenTypes = (goldenVersionTypes ?: defaultGoldenVersionTypes()) as List
        for (def goldenType : goldenTypes) {
            for (Map upgrade : upgrades) {
                if (upgrade.type == goldenType) {
                    return [type: upgrade.type, version: upgrade.version, golden: true]
                }
            }
        }

        Map best = null
        for (Map upgrade : upgrades) {
            if (best == null || preferredOver(upgrade, best, currentVersion)) best = upgrade
        }
        return [type: best.type, version: best.version, golden: false]
    }

    @NonCPS
    static String selectionLabel(Map fix) {
        String type = (fix?.remediationType ?: '') as String
        if (fix?.golden) return type ? "Nexus IQ Golden Version (${type})".toString() : 'Nexus IQ Golden Version'
        if (!type) return ''
        String reason = type.contains('no-violations') ? 'nearest version without violations' : 'nearest version offered by Nexus IQ'
        return "${reason} (${type})".toString()
    }

    @NonCPS
    static boolean backwardCompatible(String current, String target) {
        List a = numericPrefix(current)
        List b = numericPrefix(target)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a[0] != b[0]) return false
        if (a[0] == '0') return a.size() > 1 && b.size() > 1 && a[1] == b[1]
        return true
    }

    @NonCPS
    static int compare(String a, String b) {
        List ta = tokenize(a)
        List tb = tokenize(b)
        int size = Math.max(ta.size(), tb.size())
        for (int i = 0; i < size; i++) {
            def x = i < ta.size() ? ta[i] : null
            def y = i < tb.size() ? tb[i] : null
            int result = compareToken(x, y)
            if (result != 0) return result
        }
        return 0
    }

    @NonCPS
    static boolean isUpgrade(String current, String target) {
        if (!current || !target) return false
        return compare(current, target) < 0
    }

    @NonCPS
    static boolean isConcreteVersion(String value) {
        return value != null && (value.trim() ==~ /^[vV]?\d[0-9A-Za-z.\-_+]*$/)
    }

    @NonCPS
    static String max(String a, String b) {
        if (!a) return b
        if (!b) return a
        return compare(a, b) >= 0 ? a : b
    }

    @NonCPS
    static List mergePropertyRequests(List requests) {
        Map byName = [:]
        for (def request : (requests ?: [])) {
            Map existing = byName[request.name] as Map
            if (!existing) {
                byName[request.name] = [
                        name         : request.name,
                        ecosystem    : request.ecosystem,
                        targetVersion: request.targetVersion,
                        componentKeys: [] + (request.componentKeys as List),
                        components   : [] + (request.components as List),
                        referencedIn : [] + (request.referencedIn as List)
                ]
            } else {
                existing.targetVersion = max(existing.targetVersion as String, request.targetVersion as String)
                addAllUnique(existing.componentKeys as List, request.componentKeys as List)
                addAllUnique(existing.components as List, request.components as List)
                addAllUnique(existing.referencedIn as List, request.referencedIn as List)
            }
        }
        return byName.values().toList()
    }

    @NonCPS
    static void addAllUnique(List target, List source) {
        for (def item : (source ?: [])) {
            if (!target.contains(item)) target << item
        }
    }

    @NonCPS
    private static boolean preferredOver(Map candidate, Map best, String currentVersion) {
        boolean candidateCompatible = backwardCompatible(currentVersion, candidate.version as String)
        boolean bestCompatible = backwardCompatible(currentVersion, best.version as String)
        if (candidateCompatible != bestCompatible) return candidateCompatible
        boolean candidateClean = (candidate.type as String).contains('no-violations')
        boolean bestClean = (best.type as String).contains('no-violations')
        if (candidateClean != bestClean) return candidateClean
        return compare(candidate.version as String, best.version as String) < 0
    }

    @NonCPS
    private static List numericPrefix(String version) {
        List out = []
        for (def token : tokenize(version)) {
            if (!(token[0] as boolean)) return out
            out << stripLeadingZeros(token[1] as String)
        }
        return out
    }

    @NonCPS
    private static List tokenize(String version) {
        List out = []
        if (!version) return out
        for (String part : (version.trim().replaceFirst(/^[vV]/, '').split(/[.\-_+]/) as List)) {
            if (!part) continue
            def matcher = (part =~ /(\d+|[^\d]+)/)
            while (matcher.find()) {
                String token = matcher.group(1)
                if (token ==~ /\d+/) {
                    out << [true, token]
                } else if (!['final', 'release', 'ga'].contains(token.toLowerCase())) {
                    out << [false, token.toLowerCase()]
                }
            }
        }
        return out
    }

    @NonCPS
    private static int compareToken(def x, def y) {
        if (x == null && y == null) return 0
        if (x == null) return y[0] ? (isZero(y[1] as String) ? 0 : -1) : 1
        if (y == null) return -compareToken(y, x)
        boolean xNumeric = x[0] as boolean
        boolean yNumeric = y[0] as boolean
        if (xNumeric && yNumeric) return compareNumeric(x[1] as String, y[1] as String)
        if (xNumeric) return 1
        if (yNumeric) return -1
        return (x[1] as String).compareTo(y[1] as String)
    }

    @NonCPS
    private static int compareNumeric(String a, String b) {
        String x = stripLeadingZeros(a)
        String y = stripLeadingZeros(b)
        if (x.length() != y.length()) return x.length() < y.length() ? -1 : 1
        return x.compareTo(y)
    }

    @NonCPS
    private static String stripLeadingZeros(String value) {
        String out = value.replaceFirst(/^0+/, '')
        return out ? out : '0'
    }

    @NonCPS
    private static boolean isZero(String value) {
        return value ==~ /0+/
    }
}
