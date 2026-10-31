package com.bbh.config

import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class RepositoryConfigReader implements Serializable {

    private final def script

    RepositoryConfigReader(def script) {
        this.script = script
    }

    Map read() {
        List<String> candidates = BuildUtils.repositoryConfigFiles()
        List<String> found = []
        for (String name : candidates) {
            if (script.fileExists(name)) found << name
        }
        if (!found) {
            script.error "[INIT] Neither ${candidates.join(' nor ')} is at the root of the repository: commit ${candidates[0]} with a projects: section " +
                    "for the services of this repository (examples/CertScanner/${candidates[0]} shows one)"
        }
        String file = found[0]
        script.echo "[INIT] The services come from ${file} in the repository, the global settings from the library (resources/${BuildUtils.defaultsResource()})"
        if (found.size() > 1) script.echo "[INIT] ${found.join(' and ')} are both in the repository: ${file} is read, ${found[1]} is not"
        def raw = null
        try {
            raw = script.readYaml(file: file)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.error "[INIT] ${file} is not valid YAML: ${e.message}"
        }
        Map projects = (raw instanceof Map && (raw as Map).projects instanceof Map ? (raw as Map).projects : [:]) as Map
        if (!projects) script.error "[INIT] ${file} must contain a 'projects:' section."
        List others = otherKeys(raw as Map)
        if (others) script.echo "[INIT] ${file} also sets ${others.join(', ')}: only its projects: section is read"
        Map bundled = bundledSettings()
        return [file: file, projects: projects, defaults: (bundled.defaults ?: [:]) as Map, platform: (bundled.platform ?: [:]) as Map]
    }

    private Map bundledSettings() {
        String name = BuildUtils.defaultsResource()
        String text = ''
        try {
            text = script.libraryResource(name)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.error "[INIT] The library resource ${name} cannot be read (${e.message}): the global settings come from it; ask the DevSecOps team"
        }
        def bundled = script.readYaml(text: text)
        return (bundled instanceof Map ? bundled : [:]) as Map
    }

    @NonCPS
    private List<String> otherKeys(Map raw) {
        return raw.keySet().findAll { it != 'projects' }.collect { it as String }
    }
}
