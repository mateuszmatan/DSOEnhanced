package devsecops.test

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

class PortalFixtures {

    static final String RENDERED_AT = '2026-10-06T08:14:19.475Z'
    static final String PORTAL_URL = 'https://dso-portal.apps.bbh.com'

    static String key(int n) {
        return String.format('5e0f%04d-8c2b-4f6a-9d3e-1a7b2c4d%04d', n, n)
    }

    static String hint(String key) {
        return key.take(8) + '...' + key.substring(key.length() - 4)
    }

    static String sha256(int n) {
        return String.format('%016x', 0x0f1e2d3c4b5a6900L + n)
    }

    static Map copy(Map value) {
        return new JsonSlurperClassic().parseText(JsonOutput.toJson(value)) as Map
    }

    static Map document(String type, Map defaults, Map platform, String service, Map project, Map pipeline = [:]) {
        return copy([pipeline: [type: type, product: 'CERTSCANNER', agentNames: ['linux-agent', 'windows-agent']] + pipeline,
                     platform: platform, defaults: defaults, projects: [(service): project]])
    }

    static String publish(FakeScript j, Map config) {
        int n = j.portal.size() + 1
        String key = key(n)
        j.portal[key] = [keyStatus: 'ACTIVE', renderedAt: RENDERED_AT, config: copy(config), sha256: sha256(n)]
        return key
    }

    static List<String> publishAll(FakeScript j, String type, Map defaults, Map platform, Map projects, Map pipeline = [:]) {
        List<String> keys = []
        projects.each { name, project ->
            keys << publish(j, document(type, defaults, platform, name as String, project as Map, pipeline))
        }
        return keys
    }
}
