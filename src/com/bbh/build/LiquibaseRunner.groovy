package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils
import com.bbh.utils.DatabaseSettings
import com.bbh.utils.LiquibaseOutput
import com.cloudbees.groovy.cps.NonCPS

class LiquibaseRunner implements Serializable {

    private final def           script
    private final PipelineState state
    private final OsHelper      os
    private final BuildService  build

    LiquibaseRunner(def script, PipelineState state, OsHelper os, BuildService build) {
        this.script = script
        this.state  = state
        this.os     = os
        this.build  = build
    }

    Map tool(String tool, Map cfg) {
        Map settings = DatabaseSettings.map(DatabaseSettings.map(cfg?.tools)[tool])
        String localKey = tool == 'oracleJdbc' ? 'path' : 'home'
        String local = DatabaseSettings.str(settings[localKey])
        String url = DatabaseSettings.str(settings.url)
        String key = "${tool}|${local}|${url}|${DatabaseSettings.str(settings.sha256)}".toString()
        if (state.databaseTools[key]) return state.databaseTools[key] as Map
        String prefix = toolKey(tool, cfg)
        String agent = script.env.NODE_NAME
        Map found = [path: '', home: '']
        if (local) {
            if (!script.fileExists(local)) {
                script.error "[DB] ${prefix}.${localKey} is ${local}, which does not exist on agent ${agent}: install it there or correct the setting"
            }
            found = [path: launcher(tool, local), home: tool == 'liquibase' ? local : '']
        } else if (url) {
            String dir = download(tool, settings, prefix)
            found = [path: tool == 'oracleJdbc' ? "${dir}/${BuildUtils.fileNameOf(url)}".toString() : launcher(tool, tool == 'utplsql' ? "${dir}/utPLSQL-cli".toString() : dir),
                     home: tool == 'liquibase' ? dir : '']
        } else if (tool != 'oracleJdbc') {
            String name = tool == 'liquibase' ? 'liquibase' : 'utplsql'
            int status = script.sh(returnStatus: true, label: "DB: find ${name} on the PATH", script: "command -v ${name} >/dev/null 2>&1") as int
            if (status != 0 && tool == 'liquibase') {
                script.error "[DB] No Liquibase CLI on agent ${agent}: set tools.liquibase.home, or tools.liquibase.url with its sha256, in the Global Settings of the DevSecOps portal, or put liquibase on the PATH of the agent"
            }
            if (status != 0) {
                script.error "[DB] No utPLSQL-cli on agent ${agent}, and ${state.currentProjectName} runs utPLSQL (database.test.utplsql.enabled): set tools.utplsql.home, or tools.utplsql.url with its sha256, or put utplsql on the PATH of the agent"
            }
            found = [path: name, home: '']
        }
        state.databaseTools[key] = found
        return found
    }

    @NonCPS
    private String toolKey(String tool, Map cfg) {
        Map own = DatabaseSettings.map(DatabaseSettings.map(cfg?.tools)[tool])
        Map defaults = DatabaseSettings.map(DatabaseSettings.map(state.cfgDefaults?.tools)[tool])
        return own == defaults ? "defaults.tools.${tool}".toString() : "tools.${tool}".toString()
    }

    @NonCPS
    private static String launcher(String tool, String home) {
        if (tool == 'oracleJdbc') return home
        return tool == 'liquibase' ? "${home}/liquibase".toString() : "${home}/bin/utplsql".toString()
    }

    private String download(String tool, Map settings, String prefix) {
        String sha = DatabaseSettings.str(settings.sha256)
        String dir = "${script.pwd(tmp: true)}/dso-tools/${tool}-${sha.length() >= 12 ? sha.substring(0, 12) : sha}"
        int status = script.withEnv(["DSO_TOOL_URL=${DatabaseSettings.str(settings.url)}", "DSO_TOOL_SHA256=${sha}", "DSO_TOOL_DIR=${dir}"]) {
            script.sh(returnStatus: true, label: "DB: download ${tool}", script: LiquibaseOutput.downloadScript())
        } as int
        if (status == 3) {
            script.error "[DB] The file at ${prefix}.url does not have the sha256 of ${prefix}.sha256 and was removed: check both settings"
        }
        if (status != 0) {
            script.error "[DB] The download of ${tool} from ${prefix}.url failed (exit code ${status}): check that agent ${script.env.NODE_NAME} reaches Nexus and that the URL names a .tar.gz, .tgz, .zip or .jar file"
        }
        return dir
    }

    String javaHome(String name, Map cfg) {
        Map current = (state.projectsDatabase[name] ?: [:]) as Map
        if (current.javaHome) return current.javaHome as String
        String jdk = DatabaseSettings.str(cfg?.javaPath)
        if (!jdk) {
            Integer version = DatabaseSettings.intOf(DatabaseSettings.map(DatabaseSettings.map(cfg?.tools).liquibase).javaVersion)
            jdk = build.autoSetupJavaHome('liquibase', [DSO_JAVA_VERSION: version ?: 17])
        }
        state.projectsDatabase[name] = current + [javaHome: jdk]
        return jdk
    }

    String workDir(String name) {
        return "${script.pwd(tmp: true)}/dso-db/${name}".toString()
    }

    List<String> baseEnv(String jdk) {
        return ["JAVA_HOME=${jdk}".toString(), "PATH+DSOJDK=${jdk}/bin".toString(), 'LIQUIBASE_ANALYTICS_ENABLED=false']
    }

    String lockId() {
        return DatabaseSettings.lockId(script.env.BUILD_TAG as String)
    }

    Map liquibase(Map step) {
        String text = liquibaseScript(step)
        Map target = step.target as Map
        if (!target) {
            return script.withEnv(baseEnv(step.jdk as String)) {
                shell(step, text)
            } as Map
        }
        List env = baseEnv(step.jdk as String) + ["DSO_LOCK_ID=${lockId()}".toString(), "DSO_TMP=${step.dir}".toString(), "DSO_PROPS=${target.name}.properties".toString()]
        Map result = online(target, env) {
            guarded(target, step) {
                shell(step, text)
            }
        } as Map
        if (result.status == 64) refused(target, 'Liquibase')
        return result
    }

    Map utplsql(Map target, Map s, String jdk) {
        String dir = workDir(s.name as String)
        Map step = [label: 'DB: utPLSQL tests on the test database', dir: dir, out: 'utplsql.out', lock: false]
        String text = utplsqlCommand(tool('utplsql', state.cfg).path as String, s, target, dir)
        List env = baseEnv(jdk) + ["DSO_TMP=${dir}".toString(), "DSO_UT_CONNECT=${(target.parsed as Map).connect}".toString()]
        Map result = online(target, env) {
            guarded(target, step) {
                shell(step, text)
            }
        } as Map
        if (result.status == 64) refused(target, 'utPLSQL')
        return result
    }

    void refused(Map target, String toolName) {
        script.error "[DB] ${state.currentProjectName} ${target.name}: the user or the password of the Jenkins credentials ${target.credentialsId} cannot be passed to ${toolName}: it is empty, starts or ends with a blank, holds a character outside printable ASCII, or (utPLSQL) starts and ends with a double quote"
    }

    def online(Map target, List env, Closure body) {
        try {
            return script.withCredentials([script.usernamePassword(credentialsId: target.credentialsId as String,
                    usernameVariable: 'DSO_DB_USER', passwordVariable: 'DSO_DB_PASSWORD')]) {
                script.withEnv(env) {
                    body()
                }
            }
        } catch (Exception e) {
            if (!((e.message ?: '') as String).startsWith('Could not find credentials entry with ID')) throw e
            script.error "[DB] ${state.currentProjectName} ${target.name}: the Jenkins credentials ${target.credentialsId} (database.${target.name}.credentialsId) cannot be used: ${e.message} - add Username with password credentials with this ID to the folder of the job"
        }
    }

    def guarded(Map target, Map step, Closure body) {
        try {
            return script.timeout(time: target.timeoutMinutes as int, unit: 'MINUTES') {
                body()
            }
        } catch (Exception e) {
            if (BuildService.interruptionKind(e) == 'TIMEOUT' || ((e.message ?: '') as String).contains('Timeout has been exceeded')) {
                script.echo "[DB] ${state.currentProjectName} ${target.name}: ${step.label} did not end within ${target.timeoutMinutes} minutes (database.${target.name}.timeoutMinutes)"
            }
            if (step.lock) releaseOwnLock(target, step)
            throw e
        }
    }

    void releaseOwnLock(Map target, Map step) {
        String scope = "[DB] ${state.currentProjectName} ${target.name}"
        Map list = step + [label: "DB: Liquibase list-locks on ${target.name}".toString(), command: 'list-locks', args: [], out: "${target.name}-locks.out".toString(),
                           quiet: true, lock: false, csv: '', mkdir: '']
        Map release = list + [label: "DB: Liquibase release-locks on ${target.name}".toString(), command: 'release-locks', out: "${target.name}-release.out".toString(), quiet: false]
        try {
            script.timeout(time: 5, unit: 'MINUTES') {
                Map locks = shell(list, liquibaseScript(list))
                if (locks.status != 0) {
                    script.echo "${scope}: the Liquibase lock could not be checked after ${step.label} stopped (${LiquibaseOutput.reason(locks.text as String)}) - if later runs wait for the lock, ask the DBA to check DATABASECHANGELOGLOCK"
                } else if (!LiquibaseOutput.lockOf(locks.text as String, lockId())) {
                    script.echo "${scope}: ${step.label} stopped; no Liquibase lock of this build was held"
                } else {
                    Map released = shell(release, liquibaseScript(release))
                    script.echo(released.status == 0
                            ? "${scope}: released the Liquibase lock that this build held after ${step.label} stopped"
                            : "${scope}: the Liquibase lock could not be checked after ${step.label} stopped (${LiquibaseOutput.reason(released.text as String)}) - if later runs wait for the lock, ask the DBA to check DATABASECHANGELOGLOCK")
                }
            }
        } catch (Exception e) {
            script.echo "${scope}: the Liquibase lock could not be checked after ${step.label} stopped (${e.message ?: e.getClass().getSimpleName()}) - if later runs wait for the lock, ask the DBA to check DATABASECHANGELOGLOCK"
        }
    }

    Map shell(Map step, String text) {
        int status = script.sh(returnStatus: true, label: step.label as String, script: text) as int
        String out = "${step.dir}/${step.out}"
        return [status: status, text: script.fileExists(out) ? script.readFile(out) as String : '']
    }

    void maven(String jdk, List goals, List flags, String mvnPath, String label) {
        BuildRunner runner = new BuildRunner(script, [maven: [goals: goals, flags: flags, mvnPath: mvnPath ?: '', label: label]], 'maven')
        script.dir(workDir(state.currentProjectName)) {
            script.withEnv(baseEnv(jdk)) {
                if (!runner.ifExist()) {
                    script.error "[NEXUS] ${state.currentProjectName}: Maven is not installed on agent ${script.env.NODE_NAME}; it publishes and fetches the SQL and PL/SQL package: set database.package.mvnPath or build.maven.mvnPath"
                }
                runner.run()
            }
        }
    }

    @NonCPS
    static String liquibaseScript(Map step) {
        Map target = step.target as Map
        List tokens = [DatabaseSettings.quote(step.liquibase as String)]
        tokens << (target ? '"--defaults-file=$DSO_STEP"' : DatabaseSettings.quote("--defaults-file=${step.properties}"))
        tokens << DatabaseSettings.quote('--show-banner=false')
        tokens << DatabaseSettings.quote("--search-path=${step.search}")
        if (step.jar) tokens << DatabaseSettings.quote("--classpath=${step.jar}")
        if (target?.defaultSchema) tokens << DatabaseSettings.quote("--default-schema-name=${target.defaultSchema}")
        if (target?.liquibaseSchema) tokens << DatabaseSettings.quote("--liquibase-schema-name=${target.liquibaseSchema}")
        if (target && step.lock) tokens << DatabaseSettings.quote("--changelog-lock-wait-time-in-minutes=${step.lockWait}")
        tokens << DatabaseSettings.quote(step.command as String)
        for (def arg : (step.args ?: []) as List) {
            tokens << DatabaseSettings.quote(arg as String)
        }
        String out = DatabaseSettings.quote("${step.dir}/${step.out}")
        String sink = !target ? "2>&1 | tee ${out}" : (step.quiet ? "2>&1 | dso_mask > ${out}" : "2>&1 | dso_mask | tee ${out}")
        String text = target ? LiquibaseOutput.onlinePrelude() : LiquibaseOutput.offlinePrelude()
        if (step.csv) text += "rm -f ${DatabaseSettings.quote(step.csv as String)}\n"
        if (step.mkdir) text += "mkdir -p ${DatabaseSettings.quote(step.mkdir as String)}\n"
        return text + tokens.join(' ') + ' ' + sink + '\n'
    }

    @NonCPS
    static String utplsqlCommand(String utplsql, Map s, Map target, String dir) {
        Map ut = (target.utplsql ?: [:]) as Map
        String out = ".dso-db/${s.name}"
        List args = []
        String paths = (ut.paths ?: ut.owner ?: '') as String
        if (paths) args << "-p=${paths}"
        args += ['--failure-exit-code=2', "-t=${ut.timeoutMinutes ?: 30}", '-f=ut_documentation_reporter', '-s',
                 '-f=ut_junit_reporter', "-o=${out}/utplsql-junit.xml", '-f=ut_coverage_cobertura_reporter', "-o=${out}/utplsql-cobertura.xml",
                 '-f=ut_coverage_sonar_reporter', "-o=${out}/utplsql-coverage-sonar.xml", '-f=ut_sonar_test_reporter', "-o=${out}/utplsql-sonar-tests.xml"]
        for (String kind : ['source', 'test']) {
            String path = (ut["${kind}Path".toString()] ?: '') as String
            if (!path) continue
            args << "-${kind}_path=${DatabaseSettings.join(s.searchRoot as String, path)}"
            if (ut.owner) args << "-owner=${ut.owner}"
        }
        return LiquibaseOutput.utplsqlScript() + "mkdir -p ${DatabaseSettings.quote(out)}\n" +
                "${DatabaseSettings.quote(utplsql)} run \"@\$DSO_ARGS\" ${args.collect { DatabaseSettings.quote(it as String) }.join(' ')} 2>&1 | dso_mask | tee ${DatabaseSettings.quote("${dir}/utplsql.out")}\n"
    }
}
