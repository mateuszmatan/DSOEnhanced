package com.bbh.deploy

import com.bbh.build.DatabaseBuild
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.BuildUtils
import com.bbh.utils.DatabaseSettings

class DatabaseDeployService implements Serializable {

    private final def           script
    private final PipelineState state
    private final PolicyEngine  policy
    private final DatabaseBuild database

    DatabaseDeployService(def script, PipelineState state, PolicyEngine policy, DatabaseBuild database) {
        this.script   = script
        this.state    = state
        this.policy   = policy
        this.database = database
    }

    void deliver(String kind) {
        String name = state.currentProjectName
        Map pkg = DatabaseSettings.resolve(name, state.cfg).package as Map
        if (!pkg.nexusDelivery) {
            script.echo "[NEXUS] ${name}: the Nexus delivery of the SQL and PL/SQL package is off (database.package.nexusDelivery) - not required"
            state.projectNotRequired()
            return
        }
        try {
            Map built = database.ensurePackage()
            boolean snapshot = kind == 'snapshot'
            String version = ((state.projectsDatabase[name] as Map).version as String) + (snapshot ? '-SNAPSHOT' : '')
            Map repository = (snapshot ? pkg.snapshot : pkg.release) as Map
            String coordinates = "${pkg.groupId}:${pkg.artifactId}:${version}"
            script.echo "[NEXUS] ${name}: publishing ${built.file} as ${coordinates} to ${repository.id}"
            publish(built, repository, version, "DB: publish ${built.file} to Nexus".toString())
            if (snapshot) {
                def runState = script.readYaml(file: BuildUtils.runStateFile())
                runState.projects[name].delivery = (state.projectsDatabase[name] as Map).version
                script.writeYaml file: BuildUtils.runStateFile(), data: runState, overwrite: true
            }
            Map delivery = [snapshot: '', release: ''] + ((((state.projectsDatabase[name] as Map).delivery) ?: [:]) as Map)
            state.recordDatabase([delivery: delivery + [(kind): coordinates]])
        } finally {
            script.dir(database.runner().workDir(name)) {
                script.deleteDir()
            }
        }
    }

    void deploy(String target) {
        String name = state.currentProjectName
        Map s = DatabaseSettings.resolve(name, state.cfg)
        if (!(s.targets as Map)[target]) {
            script.echo "[DEPLOY] ${name}: no ${target.toUpperCase()} database (database.${target}.url) - not required"
            state.projectNotRequired()
            return
        }
        database.applyChanges(target)
    }

    private void publish(Map pkg, Map repository, String version, String label) {
        Map settings = DatabaseSettings.resolve(state.currentProjectName, state.cfg).package as Map
        List flags = ["-DrepositoryId=${repository.id}", "-Durl=${repository.url}", "-DgroupId=${settings.groupId}", "-DartifactId=${settings.artifactId}",
                      "-Dversion=${version}", '-Dpackaging=zip', "-Dfile=${script.env.WORKSPACE}/${pkg.path}", '-DgeneratePom=true'].collect { it.toString() }
        String jdk = database.runner().javaHome(state.currentProjectName, state.cfg)
        database.runner().maven(jdk, ['deploy:deploy-file'], flags, settings.mvnPath as String, label)
    }
}
