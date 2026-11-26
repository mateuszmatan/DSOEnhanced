package com.bbh.core

import com.cloudbees.groovy.cps.NonCPS

class OneViewSettings implements Serializable {

    private final def script
    private final PipelineState state

    OneViewSettings(def script, PipelineState state) {
        this.script = script
        this.state = state
    }

    String address() {
        List<Map> candidates = [variable('ONEVIEW_URL', script.env.ONEVIEW_URL, '', 'strict'),
                                variable('DSO_PORTAL_URL', script.env.DSO_PORTAL_URL, 'ONEVIEW_URL', 'strict')]
        Map found = pick(candidates)
        if (!found.value) script.error(addressProblem(candidates))
        announce(found.notices as List)
        return found.value as String
    }

    Map reportTarget(String refused = 'The pipeline report is not uploaded') {
        List<Map> candidates = [variable('ONEVIEW_URL', script.env.ONEVIEW_URL, '', 'strict'),
                                variable('DSO_PORTAL_URL', script.env.DSO_PORTAL_URL, 'ONEVIEW_URL', 'any'),
                                variable('PORTAL_URL', script.env.PORTAL_URL, 'ONEVIEW_URL', 'any'),
                                setting('oneview.url', 'oneview', 'url', '', 'strict'),
                                setting('portal.url', 'portal', 'url', 'oneview.url', 'any')]
        Map found = pick(candidates)
        if (found.value) {
            announce(found.notices as List)
            return [address: found.value as String, name: found.name as String, strict: found.strict as boolean]
        }
        List<String> names = []
        for (Map c : candidates) {
            if (c.problem == 'unset' || (c.problem == 'blank' && c.quietBlank)) continue
            names << (c.name as String)
        }
        if (names) {
            announce([("[ONEVIEW] ${refused}: ${names.join(', ')} ${names.size() > 1 ? 'hold' : 'holds'} no address of OneView; " +
                    "a Jenkins administrator writes ONEVIEW_URL as ${addressForm()}, ${variablePlace()}").toString()])
        }
        return [address: '', name: '', strict: true]
    }

    String agent() {
        Map found = pick([variable('ONEVIEW_AGENT', script.env.ONEVIEW_AGENT, '', ''),
                          variable('DSO_PORTAL_AGENT', script.env.DSO_PORTAL_AGENT, 'ONEVIEW_AGENT', '')])
        announce(found.notices as List)
        return (found.value ?: 'linux-agent') as String
    }

    List<Map> reportKeys() {
        List<String> own = ownKeys()
        Map given = givenKey(own)
        if (given) return [given]
        List<Map> keys = []
        if (own) {
            for (String key : own) keys << [key: key, hint: hint(key)]
            return keys
        }
        Map configured = configuredKey()
        if (configured) keys << configured
        return keys
    }

    Map decorationKey(String service) {
        List<String> own = ownKeys()
        Map given = givenKey(own)
        if (given) return given
        if (own) {
            String key = keyOfService(own, service)
            return key ? [key: key, hint: hint(key)] : [:]
        }
        return configuredKey()
    }

    private Map givenKey(List<String> own) {
        String first = trimmed(script.env.DSO_PIPELINE_KEY)
        String second = trimmed(script.env.PIPELINE_KEY)
        if (!first && !second) return [:]
        String key = first ?: second
        if (!own.contains(key.toLowerCase())) return [key: key, hint: '']
        announce([sameKeyLine(first ? 'DSO_PIPELINE_KEY' : 'PIPELINE_KEY', key, own.size())])
        return [key: key.toLowerCase(), hint: '']
    }

    private Map configuredKey() {
        Map found = pick([setting('oneview.pipelineKey', 'oneview', 'pipelineKey', '', ''),
                          setting('portal.pipelineKey', 'portal', 'pipelineKey', 'oneview.pipelineKey', '')])
        announce(found.notices as List)
        return found.value ? [key: found.value as String, hint: ''] : [:]
    }

    @NonCPS
    String hint(String key) {
        return key.length() < 12 ? '...' : key.take(8) + '...' + key.substring(key.length() - 4)
    }

    private Map variable(String name, def value, String formerOf, String form) {
        return [name  : name, value: value == null ? null : (value as String), formerOf: formerOf, form: form, quietBlank: false,
                rename: "rename it ${variablePlace()}".toString(), where: variablePlace()]
    }

    private Map setting(String name, String block, String key, String formerOf, String form) {
        Map found = configured(block, key)
        String place = settingPlace(found.origin as String)
        return [name  : name, value: found.value == null ? null : (found.value as String), formerOf: formerOf, form: form, quietBlank: true,
                rename: "rename the block portal: to oneview: ${place}".toString(), where: place]
    }

    @NonCPS
    private String trimmed(def value) {
        return value == null ? '' : (value as String).trim()
    }

    @NonCPS
    private List<String> ownKeys() {
        List<String> keys = []
        for (def key : (state.oneViewKeys ?: [])) keys << (key as String)
        return keys
    }

    @NonCPS
    private String keyOfService(List<String> own, String service) {
        List documents = (state.oneViewDocuments ?: []) as List
        for (int i = 0; i < documents.size() && i < own.size(); i++) {
            def config = documents[i] instanceof Map ? (documents[i] as Map).config : null
            def projects = config instanceof Map ? (config as Map).projects : null
            if (projects instanceof Map && (projects as Map).containsKey(service)) return own[i]
        }
        return ''
    }

    @NonCPS
    private String sameKeyLine(String name, String key, int count) {
        if (count == 1) {
            return "[ONEVIEW] ${name} holds the pipeline key of the Jenkinsfile (${hint(key.toLowerCase())}): the report is uploaded with the key of the Jenkinsfile without it, so it can be removed from the job".toString()
        }
        return ("[ONEVIEW] ${name} holds one of the ${count} pipeline keys of the Jenkinsfile (${hint(key.toLowerCase())}), so the report is uploaded for that key only: " +
                "it can be removed from the job, and the report is then uploaded once for each key of the Jenkinsfile").toString()
    }

    @NonCPS
    private String variablePlace() {
        return 'in Manage Jenkins > System > Global properties > Environment variables'
    }

    @NonCPS
    private String settingPlace(String origin) {
        if (!state.configFile) return 'in the configuration that OneView sent for this run'
        if (origin == 'defaults') return 'in resources/com/bbh/devsecops/defaults.yaml of the library'
        return "in ${state.configFile}".toString()
    }

    @NonCPS
    private String addressForm() {
        return 'https://<host>[:<port>][/<path>], without a user, password, query or spaces'
    }

    @NonCPS
    private boolean strictForm(String text) {
        return text ==~ /https?:\/\/[A-Za-z0-9.-]+(:[0-9]+)?(\/[A-Za-z0-9._~\/-]*)?/
    }

    @NonCPS
    private Map configured(String block, String key) {
        Map all = (state.projectsAllCfg ?: [:]) as Map
        def primary = all ? all.values().iterator().next() : state.cfg
        def own = valueIn(primary, block, key)
        def shared = valueIn(state.cfgDefaults, block, key)
        boolean ownUsable = own != null && own.toString().trim()
        boolean sharedUsable = shared != null && shared.toString().trim()
        if (ownUsable) return [value: own, origin: sharedUsable && shared.toString() == own.toString() ? 'defaults' : 'service']
        if (sharedUsable) return [value: shared, origin: 'defaults']
        if (own != null) return [value: own, origin: shared != null ? 'defaults' : 'service']
        return [value: shared, origin: 'defaults']
    }

    @NonCPS
    private def valueIn(def source, String block, String key) {
        def section = source instanceof Map ? (source as Map)[block] : null
        return section instanceof Map ? (section as Map)[key] : null
    }

    @NonCPS
    private Map pick(List<Map> candidates) {
        Map winner = null
        for (Map c : candidates) {
            String text = c.value == null ? '' : (c.value as String).trim()
            if (c.form) text = text.replaceAll('/+$', '')
            String problem = ''
            if (c.value == null) problem = 'unset'
            else if (!text) problem = 'blank'
            else if (c.form == 'strict' && !strictForm(text)) problem = 'invalid'
            c.text = text
            c.problem = problem
            if (winner == null && !problem) winner = c
        }
        List<String> notices = []
        if (winner == null) return [value: '', name: '', strict: true, notices: notices]
        boolean strict = !winner.form || strictForm(winner.text as String)
        boolean before = true
        boolean currentUnset = false
        for (Map c : candidates) {
            if (c.name == winner.name) {
                before = false
                continue
            }
            if (c.name == winner.formerOf && c.problem == 'unset') currentUnset = true
            if (c.problem == 'unset') continue
            if (before) {
                String reason = c.problem == 'blank' ? 'it is blank' : "it is not an address of the form ${addressForm()}"
                notices << "[ONEVIEW] ${c.name} is ignored because ${reason}: ${winner.name}${winner.formerOf == c.name ? ', its former name,' : ''} is used instead; correct it ${c.where}".toString()
            } else if (c.formerOf == winner.name && c.problem != 'blank') {
                if (c.text == winner.text) notices << "[ONEVIEW] ${c.name} is the former name of ${winner.name} and holds the same value: it can be removed ${c.where}".toString()
                else notices << "[ONEVIEW] ${winner.name} is used: ${c.name}, its former name, holds another value and is ignored".toString()
            }
        }
        if (currentUnset && strict) {
            notices << "[ONEVIEW] ${winner.name} is the former name of ${winner.formerOf}: it still works; ${winner.rename}".toString()
        } else if (currentUnset) {
            notices << ("[ONEVIEW] ${winner.name} is the former name of ${winner.formerOf}: its value still works for the report upload as before; " +
                    "under the name ${winner.formerOf} it must be written as ${addressForm()}, ${winner.where}").toString()
        }
        return [value: winner.text, name: winner.name, strict: strict, notices: notices]
    }

    @NonCPS
    private String addressProblem(List<Map> candidates) {
        boolean current = candidates[0].problem == 'invalid'
        List<String> former = []
        for (int i = 1; i < candidates.size(); i++) {
            if (candidates[i].problem == 'invalid') former << (candidates[i].name as String)
        }
        if (!current && !former) {
            return "[ONEVIEW] ONEVIEW_URL is not set: a Jenkins administrator sets it once to the address of OneView, for example https://oneview.apps.bbh.com, ${variablePlace()}".toString()
        }
        if (!former) {
            return "[ONEVIEW] ONEVIEW_URL is not the address of OneView: a Jenkins administrator writes it as ${addressForm()}".toString()
        }
        if (!current) {
            return ("[ONEVIEW] ONEVIEW_URL is not set and ${former.join(', ')}, its former name, is not the address of OneView: " +
                    "a Jenkins administrator sets ONEVIEW_URL to ${addressForm()}, ${variablePlace()}").toString()
        }
        return ("[ONEVIEW] ONEVIEW_URL is not the address of OneView, and neither is ${former.join(', ')}, its former name: " +
                "a Jenkins administrator writes ONEVIEW_URL as ${addressForm()}").toString()
    }

    private void announce(List notices) {
        for (def notice : notices) {
            String line = notice as String
            if (state.oneViewNotices.contains(line)) continue
            state.oneViewNotices << line
            script.echo line
        }
    }
}
