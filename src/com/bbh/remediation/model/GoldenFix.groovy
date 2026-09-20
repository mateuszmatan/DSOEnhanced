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
                      boolean golden = false, Boolean nonBreaking = null) {
        return [
                nonBreaking    : nonBreaking == null ? patchLevelChange(currentVersion, targetVersion) : nonBreaking,
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
                upgrades << [type: (candidate.type ?: '') as String, version: version,
                             issues: issueCount(candidate)]
            }
        }
        if (upgrades.isEmpty()) return null

        List patchOnly = []
        for (Map upgrade : upgrades) {
            if (patchLevelChange(currentVersion, upgrade.version as String)) patchOnly << upgrade
        }
        if (!patchOnly.isEmpty()) {
            Map picked = newestCleanest(patchOnly)
            return [type: picked.type, version: picked.version, golden: isGoldenType(picked.type as String, goldenVersionTypes),
                    nonBreaking: true, issues: picked.issues]
        }

        Map fallback = newestCleanest(upgrades)
        return [type: fallback.type, version: fallback.version, golden: isGoldenType(fallback.type as String, goldenVersionTypes),
                nonBreaking: false, issues: fallback.issues]
    }

    @NonCPS
    static Map newestCleanest(List upgrades) {
        List ordered = sortNewestFirst(upgrades)
        for (Map upgrade : ordered) {
            if (isClean(upgrade)) return upgrade
        }
        Map best = null
        for (Map upgrade : ordered) {
            if (best == null) {
                best = upgrade
            } else {
                int candidateIssues = (upgrade.issues ?: -1) as int
                int bestIssues = (best.issues ?: -1) as int
                if (candidateIssues >= 0 && bestIssues >= 0 && candidateIssues < bestIssues) best = upgrade
            }
        }
        return best
    }

    @NonCPS
    static List sortNewestFirst(List upgrades) {
        List source = []
        source.addAll(upgrades ?: [])
        List ordered = []
        List taken = []
        for (int n = 0; n < source.size(); n++) {
            int bestIdx = -1
            for (int i = 0; i < source.size(); i++) {
                if (taken.contains(i)) continue
                if (bestIdx < 0 || compare((source.get(i) as Map).version as String, (source.get(bestIdx) as Map).version as String) > 0) {
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            ordered << source.get(bestIdx)
            taken << bestIdx
        }
        return ordered
    }

    @NonCPS
    static boolean isClean(Map upgrade) {
        int issues = (upgrade?.issues ?: -1) as int
        if (issues >= 0) return issues == 0
        return ((upgrade?.type ?: '') as String).contains('no-violations')
    }

    @NonCPS
    static boolean isGoldenType(String type, List goldenVersionTypes) {
        List types = (goldenVersionTypes ?: defaultGoldenVersionTypes()) as List
        return types.contains(type ?: '')
    }

    @NonCPS
    static int issueCount(def candidate) {
        def value = candidate?.issues
        if (value == null) return -1
        return (value as int)
    }

    @NonCPS
    static boolean patchLevelChange(String current, String target) {
        List a = numericPrefix(current)
        List b = numericPrefix(target)
        if (a.size() < 2 || b.size() < 2) return false
        return a[0] == b[0] && a[1] == b[1]
    }

    @NonCPS
    static String selectionLabel(Map fix) {
        String type = (fix?.remediationType ?: '') as String
        String scope = (fix?.nonBreaking == false)
                ? 'changes more than the patch version, no patch-level upgrade is offered'
                : 'newest patch-level version'
        String clean = type.contains('no-violations') ? ', no known vulnerabilities' : ''
        if (fix?.golden) {
            return type ? "${scope}${clean}, Nexus IQ Golden Version (${type})".toString() : "${scope}${clean}".toString()
        }
        return type ? "${scope}${clean} (${type})".toString() : scope
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
