package com.bbh.scanner

import com.bbh.remediation.model.GoldenFix
import com.bbh.remediation.port.GoldenFixSource
import com.bbh.utils.BuildUtils
import com.bbh.utils.RestClient
import com.cloudbees.groovy.cps.NonCPS

class NexusIqGoldenFixSource implements GoldenFixSource {

    private final def        script
    private final RestClient rest

    NexusIqGoldenFixSource(def script) {
        this.script = script
        this.rest   = new RestClient(script)
    }

    List<Map> fetchGoldenFixes(Map scanRef, Map cfg) {
        String server      = ((scanRef.serverUrl ?: '') as String).replaceAll('/+$', '')
        String application = (scanRef.application ?: '') as String
        String scanId      = (scanRef.scanId ?: '') as String
        if (!server || !application || !scanId) {
            script.echo "[GOLDENFIX] Nexus IQ serverUrl, application or scan id missing for '${application}' - cannot fetch the GoldenFix list"
            return []
        }
        int minThreat          = cfg.minThreatLevel != null ? cfg.minThreatLevel.toString().toInteger() : 2
        boolean onlyDirect     = BuildUtils.booleanValue(cfg.onlyDirectDependencies, true)
        List ecosystems        = (cfg.ecosystems ?: GoldenFix.supportedEcosystems()) as List
        List goldenTypes       = (cfg.goldenVersionTypes ?: GoldenFix.defaultGoldenVersionTypes()) as List
        String stageId         = (scanRef.stage ?: 'build') as String
        String credentialsId   = (scanRef.credentialsId ?: 'nexusiqP') as String

        List<Map> fixes = []
        script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'NIQ_GF_USER', passwordVariable: 'NIQ_GF_PASS')]) {
            Map auth = [type: 'basic', userVar: 'NIQ_GF_USER', passVar: 'NIQ_GF_PASS']
            String appEnc = RestClient.urlEncode(application)

            def apps = rest.getJson("${server}/api/v2/applications?publicId=${appEnc}", auth, "Nexus IQ: resolve application ${application}")
            String internalId = firstApplicationId(apps)
            if (!internalId) script.error("[GOLDENFIX] Nexus IQ application '${application}' not found")

            def report = rest.getJson("${server}/api/v2/applications/${appEnc}/reports/${RestClient.urlEncode(scanId)}/policy", auth, "Nexus IQ: policy report ${scanId}")
            List<Map> candidates = selectCandidates(report, minThreat, onlyDirect, ecosystems)
            script.echo "[GOLDENFIX] ${application}: ${candidates.size()} violating ${onlyDirect ? 'direct ' : ''}component(s) eligible for GoldenFix (threat level >= ${minThreat})"

            String remediationUrl = "${server}/api/v2/components/remediation/application/${RestClient.urlEncode(internalId)}?stageId=${RestClient.urlEncode(stageId)}"
            List requests = []
            for (int i = 0; i < candidates.size(); i++) {
                Map c = candidates[i]
                requests << [url: remediationUrl, payload: c.componentIdentifier ? [componentIdentifier: c.componentIdentifier] : [packageUrl: c.packageUrl]]
            }
            List responses = rest.postJsonBatch(requests, auth, "Nexus IQ: remediation for ${requests.size()} component(s)")

            List lines = []
            for (int i = 0; i < candidates.size(); i++) {
                Map c = candidates[i]
                Map response = (i < responses.size() ? responses[i] : [:]) as Map
                if (!response.ok) {
                    lines << "[GOLDENFIX]   ${c.displayName} ${c.version}: remediation lookup failed - HTTP ${response.status ?: 0} ${response.error ?: ''}".toString()
                    continue
                }
                Map pick = GoldenFix.selectRemediation(candidateVersions(response.json), c.version as String, goldenTypes)
                if (!pick) {
                    lines << "[GOLDENFIX]   ${c.displayName} ${c.version}: Nexus IQ offers no remediation version".toString()
                    continue
                }
                Map fix = GoldenFix.create(c.ecosystem as String, c.group as String, c.name as String, c.version as String,
                        pick.version as String, pick.type as String, c.packageUrl as String, c.threatLevel as int,
                        c.direct as Boolean, application, pick.golden as boolean, pick.nonBreaking as Boolean,
                        (pick.alternatives ?: []) as List)
                fixes << fix
                lines << "[GOLDENFIX]   ${c.displayName} ${c.version} -> ${pick.version}: ${GoldenFix.selectionLabel(fix)}".toString()
            }
            if (lines) script.echo lines.join('\n')
        }
        return fixes
    }

    @NonCPS
    static String firstApplicationId(def json) {
        def apps = json?.applications
        return (apps instanceof List && !apps.isEmpty()) ? (apps[0]?.id as String) : null
    }

    @NonCPS
    static List<Map> selectCandidates(def report, int minThreat, boolean onlyDirect, List ecosystems) {
        List<Map> out = []
        List seen = []
        List components = (report?.components ?: []) as List
        for (def comp : components) {
            def identifier = comp?.componentIdentifier
            String format  = identifier?.format as String
            if (!format || !ecosystems.contains(format)) continue

            List active = ((comp.violations ?: []) as List).findAll { !(it?.waived) }
            if (active.isEmpty()) continue
            int threat = 0
            for (def v : active) threat = Math.max(threat, (v?.policyThreatLevel ?: 0) as int)
            if (threat < minThreat) continue

            Boolean direct = comp?.dependencyData != null ? (comp.dependencyData.directDependency as Boolean) : null
            if (onlyDirect && direct == false) continue

            Map coordinates = (identifier.coordinates ?: [:]) as Map
            String group = ''
            String name  = ''
            if (format == 'maven') {
                group = coordinates.groupId as String
                name  = coordinates.artifactId as String
            } else if (format == 'npm') {
                name = coordinates.packageId as String
            } else if (format == 'pypi') {
                name = coordinates.name as String
            }
            String version = coordinates.version as String
            if (!name || !version) continue
            String seenKey = GoldenFix.key(format, group, name) + '@' + version
            if (seen.contains(seenKey)) continue
            seen << seenKey

            out << [
                    ecosystem          : format,
                    group              : group ?: '',
                    name               : name,
                    version            : version,
                    displayName        : GoldenFix.displayName(format, group, name),
                    componentIdentifier: identifier,
                    packageUrl         : (comp.packageUrl ?: '') as String,
                    threatLevel        : threat,
                    direct             : direct
            ]
        }
        return out
    }

    @NonCPS
    static List candidateVersions(def response) {
        List out = []
        List changes = (response?.remediation?.versionChanges ?: []) as List
        for (def change : changes) {
            def component = change?.data?.component
            String version = component?.componentIdentifier?.coordinates?.version as String
            if (!version) version = versionFromPurl(component?.packageUrl as String)
            if (version) out << [type: (change?.type ?: '') as String, version: version, issues: issuesOf(component)]
        }
        return out
    }

    @NonCPS
    static Integer issuesOf(def component) {
        def issues = component?.securityData?.securityIssues
        if (issues instanceof List) return (issues as List).size()
        def counts = component?.securityData?.securityIssueCount
        if (counts instanceof Number) return (counts as int)
        return null
    }

    @NonCPS
    static String versionFromPurl(String purl) {
        if (!purl) return null
        def m = (purl =~ /@([^?#]+)/)
        return m.find() ? (m.group(1) ?: '').replace('%20', ' ') : null
    }
}
