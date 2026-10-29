package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.utils.BuildUtils

class NodeBuildRunner implements Serializable {

    private final def      script
    private final OsHelper os

    NodeBuildRunner(def script, OsHelper os) {
        this.script = script
        this.os     = os
    }

    void build(String projectName, Map node) {
        List<String> commands = BuildUtils.normalizeLines(node.commands) ?: ['npm ci', 'npm run build']
        String dir = (node.dir ?: '') as String
        String from = (node.nodePath ?: (node.installationName ? "installation ${node.installationName}" : 'the agent PATH')) as String
        script.echo "[NODE] ${projectName}: Node.js from ${from} in ${dir ?: 'the workspace'}: ${commands.join(' && ')}"
        script.timeout(time: (node.timeoutMin ?: 30).toString().toInteger(), unit: 'MINUTES') {
            withNode(node) {
                if (dir) {
                    script.dir(dir) {
                        run(commands)
                    }
                } else {
                    run(commands)
                }
            }
        }
    }

    def withNode(Map node, Closure body) {
        String home = (node.nodePath ?: (node.installationName ? script.tool(name: node.installationName as String, type: 'nodejs') : '')) as String
        String npmrc = (node.npmrcCredentialsId ?: '') as String
        List<String> env = home ? ["PATH+NODEJS=${os.isWindows() ? home : home + '/bin'}".toString()] : []
        if (node.registry && !npmrc) env << "npm_config_registry=${node.registry}".toString()
        if (node.env instanceof Map) env.addAll(node.env.collect { k, v -> "${k}=${v}".toString() } as List<String>)
        Closure inEnv = {
            if (!env) return body()
            return script.withEnv(env) {
                body()
            }
        }
        if (!npmrc) return inEnv()
        return script.withCredentials([script.file(credentialsId: npmrc, variable: 'NPM_CONFIG_USERCONFIG')]) {
            inEnv()
        }
    }

    private void run(List<String> commands) {
        for (String command : commands) {
            if (os.isWindows()) {
                script.bat(label: "Node.js: ${command}", script: command)
            } else {
                BuildUtils.execSh(script, command, "Node.js: ${command}", false)
            }
        }
    }
}
