package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class LiquibaseBuildRunner implements Serializable {

    private final def script
    private final PipelineState state
    private final OsHelper os
    private final PolicyEngine policy

    LiquibaseBuildRunner(def script, PipelineState state, OsHelper os, PolicyEngine policy) {
        this.script = script
        this.state = state
        this.os = os
        this.policy = policy
    }

    @NonCPS
    static boolean hasLiquibaseStep(def rawCfg) {
        if (!(rawCfg instanceof Map)) return false
        Map cfg = rawCfg as Map
        return (cfg.commands || cfg.tasks || cfg.flags) as boolean
    }

    void runLiquibaseBuild() {
        Map cfg = ((state.cfg.build?.liquibase) ?: state.cfg.build ?: [:]) as Map
        runLiquibaseCommand(cfg, ['validate'], 'Liquibase')
    }

    void runLiquibaseTests() {
        Map cfg = ((state.cfg.tests?.unitTests?.liquibase) ?: state.cfg.tests?.unitTests ?: [:]) as Map
        List defaultCmds = cfg.flags ? ['validate'] : []
        List commands = BuildUtils.normalizeTokens(cfg.commands ?: cfg.tasks ?: defaultCmds)
        if (!commands) {
            script.echo "[TEST] Liquibase: No unit tests or changelog checks configured, skipping"
            return
        }
        try {
            runLiquibaseCommand(cfg, defaultCmds, 'Liquibase test')
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            policy.warn('Unit tests', "Liquibase test step failed (${e.message ?: e.getClass().getSimpleName()})")
        }
    }

    def runLiquibaseCommand(Map cfg, List defaultCommands, String defaultLabelPrefix) {
        List commands = BuildUtils.normalizeTokens(cfg.commands ?: cfg.tasks ?: defaultCommands)
        if (!commands) return null
        List flags = BuildUtils.normalizeTokens(cfg.flags)
        List tokens = ['liquibase'] + flags + commands
        String cmd = os.isWindows()
                ? tokens.join(' ')
                : tokens.collect { BuildUtils.shellQuoteIfNeeded(it as String) }.join(' ')
        String label = (cfg.label ?: "${defaultLabelPrefix}: ${commands.join(' ')}") as String
        boolean returnStdout = (cfg.returnStdout ?: false) as boolean
        script.echo "Execute Liquibase Command: ${cmd}"
        Closure execStep = {
            if (os.isWindows()) {
                if (returnStdout) return script.bat(label: label, script: cmd, returnStdout: true).trim()
                script.bat(label: label, script: cmd)
                return null
            }
            return BuildUtils.execSh(script, cmd, label, returnStdout)
        }
        Closure body = {
            List<String> env = []
            if (cfg.env instanceof Map && !cfg.env.isEmpty()) {
                env.addAll(cfg.env.collect { k, v -> "${k}=${v}".toString() } as List<String>)
            }
            if (!env) return execStep()
            return script.withEnv(env) {
                execStep()
            }
        }
        if (cfg.dir) {
            return script.dir(cfg.dir as String) {
                body()
            }
        }
        return body()
    }
}
