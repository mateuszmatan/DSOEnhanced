package devsecops.test

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class GoldenFixResponder {

    static final String STAMP = '202609191405'
    static final String WORKTREE = "/ws@tmp/goldenfix/GoldenFix-${STAMP}"
    static final String PULL_REQUEST_URL = 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner/pull-requests/318'
    static final Map<String, String> MANIFESTS = [
            'gradle.properties': 'org.gradle.jvmargs=-Xmx2g\nnettyVersion=4.1.86.Final\n',
            'gui/build.gradle' : """plugins {
    id 'java'
    id 'jacoco'
}

dependencies {
    implementation 'org.apache.commons:commons-text:1.9'
    implementation "com.fasterxml.jackson.core:jackson-databind:2.13.4"
    implementation "io.netty:netty-codec-http:\${nettyVersion}"
    implementation 'org.springframework.boot:spring-boot-starter-web'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}
""".toString()
    ]
    static final Map POLICY_REPORT = [components: [
            component('org.apache.commons', 'commons-text', '1.9', 10, true, false),
            component('com.fasterxml.jackson.core', 'jackson-databind', '2.13.4', 8, true, false),
            component('io.netty', 'netty-codec-http', '4.1.86.Final', 7, true, false),
            component('org.yaml', 'snakeyaml', '1.33', 8, true, false),
            component('com.google.protobuf', 'protobuf-java', '3.21.7', 8, false, false),
            component('ch.qos.logback', 'logback-classic', '1.2.11', 7, true, true),
            component('org.apache.commons', 'commons-compress', '1.21', 1, true, false)
    ]]
    static final Map REMEDIATIONS = [
            'commons-text'    : [['next-no-violations', '1.10.0']],
            'jackson-databind': [['next-non-failing', '2.13.5'], ['next-no-violations', '2.15.4']],
            'netty-codec-http': [['next-no-violations-with-dependencies', '4.1.108.Final']],
            'snakeyaml'       : [['next-no-violations', '2.2']]
    ]

    static Map component(String g, String a, String v, int threat, Boolean direct, boolean waived) {
        return [packageUrl         : "pkg:maven/${g}/${a}@${v}?type=jar".toString(),
                componentIdentifier: [format: 'maven', coordinates: [groupId: g, artifactId: a, version: v, classifier: '', extension: 'jar']],
                dependencyData     : [directDependency: direct],
                violations         : [[policyName: threat >= 8 ? 'Security-Critical' : 'Security-High', policyThreatLevel: threat, waived: waived]]]
    }

    static void plantManifests(FakeScript s) {
        MANIFESTS.each { String file, String text -> s.files["${WORKTREE}/${file}".toString()] = text }
    }

    static Object sh(FakeScript s, Map a, List<String> calls) {
        String text = String.valueOf(a.script ?: '')
        String label = String.valueOf(a.label ?: '')
        if (label.startsWith('GoldenFix:')) calls << label
        if (text.contains('date +%Y%m%d%H%M')) return STAMP + '\n'
        if (label == 'GoldenFix: find dependency manifests') return MANIFESTS.keySet().sort().join('\n') + '\n'
        if (label == 'GoldenFix: commit changes') return 'c0ffee1d2e3f40516273849a5b6c7d8e9f001122\n'
        if (text.contains('run_one() {')) {
            StringBuilder batch = new StringBuilder()
            int batched = 0
            def counter = (text =~ /(?m)^run_one (\d+) '([^']+)' &$/)
            while (counter.find()) batched++
            calls << "BATCH sh with ${batched} request(s)".toString()
            def requests = (text =~ /(?m)^run_one (\d+) '([^']+)' &$/)
            while (requests.find()) {
                int index = requests.group(1) as int
                String batchUrl = requests.group(2)
                def body = (text =~ /(?s)cat > body-${index}\.json <<'DEVSECOPS_BODY_${index}'\n(.*?)\nDEVSECOPS_BODY_${index}\n/)
                def parsedBody = body.find() ? new JsonSlurper().parseText(body.group(1)) : null
                calls << "POST ${batchUrl}".toString()
                batch.append("===DEVSECOPS-RESPONSE ${index}===\n")
                batch.append(respond('POST', batchUrl, parsedBody, calls)).append('\n')
            }
            return batch.toString()
        }
        def curl = text =~ /(?m)curl -sS -X '(\w+)'.*'(https?:\/\/[^']+)'\s*$/
        if (!curl.find()) return null
        String method = curl.group(1)
        String url = curl.group(2)
        def bodyFile = text =~ /@'([^']+\.json)'/
        def payload = bodyFile.find() ? s.jsonFiles[bodyFile.group(1)] : null
        calls << "${method} ${url}".toString()
        return respond(method, url, payload, calls)
    }

    static String respond(String method, String url, def payload, List<String> calls) {
        if (url.contains('/api/v2/applications?publicId=')) {
            String app = url.substring(url.indexOf('publicId=') + 9)
            return JsonOutput.toJson([applications: [[id: '7d3b2c1a9e8f4a6b8c0d1e2f3a4b5c6d', publicId: app, name: app]]]) + '\n200'
        }
        if (url.contains('/reports/') && url.endsWith('/policy')) {
            return JsonOutput.toJson(url.contains('CertValidityMonitoring-GUI') ? POLICY_REPORT : [components: []]) + '\n200'
        }
        if (url.contains('/api/v2/components/remediation/application/')) {
            Map coordinates = (payload?.componentIdentifier?.coordinates ?: [:]) as Map
            List changes = ((REMEDIATIONS[coordinates.artifactId] ?: []) as List).collect { List t ->
                [type: t[0], data: [component: [componentIdentifier: [format: 'maven', coordinates: [groupId: coordinates.groupId, artifactId: coordinates.artifactId, version: t[1]]],
                                                packageUrl         : "pkg:maven/${coordinates.groupId}/${coordinates.artifactId}@${t[1]}?type=jar".toString()]]]
            }
            return JsonOutput.toJson([remediation: [versionChanges: changes]]) + '\n200'
        }
        if (url.endsWith('/rest/api/1.0/projects/TA/repos/cert-scanner/pull-requests') && method == 'POST') {
            calls << "PR ${payload?.title} ${payload?.fromRef?.id} -> ${payload?.toRef?.id}".toString()
            return JsonOutput.toJson([id: 318, title: payload?.title, links: [self: [[href: PULL_REQUEST_URL]]]]) + '\n201'
        }
        return '{}\n404'
    }
}
