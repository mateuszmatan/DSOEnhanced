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
    static final Map<String, String> SHARED_CATALOG = [
            'settings.gradle'          : "rootProject.name = 'cert-scanner'\ninclude 'gui', 'api'\n",
            'gradle/libs.versions.toml': '''[versions]
jackson = "2.13.4"
netty = { strictly = "4.1.86.Final" }

[libraries]
commons-text = "org.apache.commons:commons-text:1.9"
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
netty-codec-http = { group = "io.netty", name = "netty-codec-http", version.ref = "netty" }
postgresql = { name = "postgresql", group = "org.postgresql", version = "42.5.0" }
''',
            'gui/build.gradle'         : "dependencies {\n    implementation libs.commons.text\n    implementation libs.jackson.databind\n    implementation libs.netty.codec.http\n}\n",
            'api/build.gradle'         : "dependencies {\n    implementation libs.jackson.databind\n    implementation libs.postgresql\n}\n"
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
    static final Map BACKEND_POLICY_REPORT = [components: [
            component('com.fasterxml.jackson.core', 'jackson-databind', '2.13.4', 8, true, false),
            component('org.postgresql', 'postgresql', '42.5.0', 7, true, false)
    ]]
    static final Map REPORTS = ['CertValidityMonitoring-GUI': POLICY_REPORT]
    static final Map SHARED_CATALOG_REPORTS = ['CertValidityMonitoring-GUI': POLICY_REPORT, 'CertValidityMonitoring-Backend': BACKEND_POLICY_REPORT]
    static final Map REMEDIATIONS = [
            'commons-text'    : [['next-no-violations', '1.10.0']],
            'jackson-databind': [['next-non-failing', '2.13.5'], ['next-no-violations', '2.15.4']],
            'netty-codec-http': [['next-no-violations-with-dependencies', '4.1.108.Final']],
            'snakeyaml'       : [['next-no-violations', '2.2']],
            'postgresql'      : [['next-no-violations', '42.5.6']]
    ]

    static Map component(String g, String a, String v, int threat, Boolean direct, boolean waived) {
        return [packageUrl         : "pkg:maven/${g}/${a}@${v}?type=jar".toString(),
                componentIdentifier: [format: 'maven', coordinates: [groupId: g, artifactId: a, version: v, classifier: '', extension: 'jar']],
                dependencyData     : [directDependency: direct],
                violations         : [[policyName: threat >= 8 ? 'Security-Critical' : 'Security-High', policyThreatLevel: threat, waived: waived]]]
    }

    static void plantManifests(FakeScript s, Map<String, String> manifests = MANIFESTS) {
        manifests.each { String file, String text -> s.files["${WORKTREE}/${file}".toString()] = text }
    }

    static Object sh(FakeScript s, Map a, List<String> calls, Map<String, String> manifests = MANIFESTS, Map reports = REPORTS) {
        String text = String.valueOf(a.script ?: '')
        String label = String.valueOf(a.label ?: '')
        if (label.startsWith('GoldenFix:')) calls << label
        if (text.contains('date +%Y%m%d%H%M')) return STAMP + '\n'
        if (label == 'GoldenFix: find dependency manifests') return manifests.keySet().sort().join('\n') + '\n'
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
                batch.append(respond('POST', batchUrl, parsedBody, calls, reports)).append('\n')
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
        return respond(method, url, payload, calls, reports)
    }

    static String respond(String method, String url, def payload, List<String> calls, Map reports) {
        if (url.contains('/api/v2/applications?publicId=')) {
            String app = url.substring(url.indexOf('publicId=') + 9)
            return JsonOutput.toJson([applications: [[id: '7d3b2c1a9e8f4a6b8c0d1e2f3a4b5c6d', publicId: app, name: app]]]) + '\n200'
        }
        if (url.contains('/reports/') && url.endsWith('/policy')) {
            Map report = reports.find { url.contains("/applications/${it.key}/".toString()) }?.value as Map
            return JsonOutput.toJson(report ?: [components: []]) + '\n200'
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
        if (url.endsWith('/rest/api/1.0/projects/TA/repos/cert-scanner/pull-requests/318/comments') && method == 'POST') {
            calls << "COMMENT ${String.valueOf(payload?.text).readLines()[0]}".toString()
            return JsonOutput.toJson([id: 7, text: payload?.text]) + '\n201'
        }
        return '{}\n404'
    }
}
