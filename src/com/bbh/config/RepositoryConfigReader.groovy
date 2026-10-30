package com.bbh.config

import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class RepositoryConfigReader implements Serializable {

    private final def script

    RepositoryConfigReader(def script) {
        this.script = script
    }

    @NonCPS
    String names() {
        return BuildUtils.repositoryConfigFiles().join(' or ')
    }

    Map read(String variant) {
        List<String> candidates = BuildUtils.repositoryConfigFiles()
        List<String> found = []
        for (String name : candidates) {
            if (script.fileExists(name)) found << name
        }
        if (!found) {
            script.error "[INIT] No pipeline key in the Jenkinsfile, and neither ${candidates.join(' nor ')} is at the root of the repository: " +
                    "commit ${candidates[0]}, which the product page of the DevSecOps portal downloads, or give the Jenkinsfile the pipeline key of the service " +
                    "(a custom Jenkinsfile calls devSecOpsApi.configure('${variant ?: '<variant>'}', [pipelineKey: '<key from the DevSecOps portal>']) before devSecOpsApi.initialize())"
        }
        String file = found[0]
        script.echo "[INIT] No pipeline key in the Jenkinsfile: the services come from ${file} in the repository, the global settings from the library (resources/${BuildUtils.defaultsResource()})"
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
        if (others) script.echo "[INIT] ${file} also sets ${others.join(', ')}: without a pipeline key only its projects: section is read"
        return [file: file, projects: projects, defaults: bundledDefaults()]
    }

    private Map bundledDefaults() {
        String name = BuildUtils.defaultsResource()
        String text = ''
        try {
            text = script.libraryResource(name)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            script.error "[INIT] The library resource ${name} cannot be read (${e.message}): a run without a pipeline key takes its global settings from it; ask the DevSecOps team"
        }
        def bundled = script.readYaml(text: text)
        return ((bundled instanceof Map ? (bundled as Map).defaults : null) ?: [:]) as Map
    }

    @NonCPS
    private List<String> otherKeys(Map raw) {
        return raw.keySet().findAll { it != 'projects' }.collect { it as String }
    }
}
