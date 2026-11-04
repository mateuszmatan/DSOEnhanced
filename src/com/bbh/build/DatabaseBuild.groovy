package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.BuildUtils
import com.bbh.utils.DatabaseSettings
import com.bbh.utils.LiquibaseOutput

class DatabaseBuild implements Serializable {

    private final def             script
    private final PipelineState   state
    private final OsHelper        os
    private final PolicyEngine    policy
    private final BuildService    build
    private final LiquibaseRunner liquibaseRunner

    DatabaseBuild(def script, PipelineState state, OsHelper os, PolicyEngine policy, BuildService build) {
        this.script          = script
        this.state           = state
        this.os              = os
        this.policy          = policy
        this.build           = build
        this.liquibaseRunner = new LiquibaseRunner(script, state, os, build)
    }

    LiquibaseRunner runner() {
        return liquibaseRunner
    }

    void validateServices(String variant) {
        Map all = (state.projectsAllCfg ?: [:]) as Map
        if (!DatabaseSettings.concerns(all, state.cfgDefaults)) return
        List names = all.keySet().collect { it as String }
        List databases = names.findAll { BuildUtils.databaseBlock(all[it] as Map) }
        int count = 0
        if (databases) {
            List global = DatabaseSettings.globalProblems(state.cfgDefaults)
            for (String problem : global) {
                script.echo "[DB] Global Settings: ${problem}"
            }
            count += global.size()
        }
        String java = ''
        List order = []
        for (String name : names) {
            Map cfg = (all[name] ?: [:]) as Map
            String tool = DatabaseSettings.str(cfg.buildTool)
            if (!BuildUtils.databaseBlock(cfg)) {
                if (DatabaseSettings.unfinished(cfg, state.cfgDefaults)) {
                    script.echo "[DB] ${name}: ${DatabaseSettings.changelogRequired()}"
                    count++
                } else if (DatabaseSettings.str(DatabaseSettings.map(cfg.database).changelogFile)) {
                    script.echo "[DB] ${name}: database.changelogFile is set, but buildTool is ${tool}: the database block is ignored; give the changelog its own service with buildTool liquibase, or run Liquibase in this service with build.liquibase"
                }
                if (!java && tool in ['gradle', 'maven', 'flutter']) java = name
                continue
            }
            if (os.isWindows()) {
                script.error "[DB] ${name}: a Liquibase service with a database block runs on Linux or macOS agents only: choose a Linux agent in AGENT_NAME"
            }
            List problems = DatabaseSettings.problems(name, cfg, state.cfgDefaults, variant)
            def ownTargets = DatabaseSettings.map(cfg.database).allowedTargets
            if (ownTargets != null && ownTargets != DatabaseSettings.map(state.cfgDefaults?.database).allowedTargets) {
                script.echo "[DB] ${name}: database.allowedTargets is a Global Setting (defaults.database.allowedTargets in the DevSecOps portal); the value set for the service is ignored"
            }
            if (!problems && variant in ['full', 'security']) {
                Map s = DatabaseSettings.resolve(name, cfg)
                String out = script.sh(returnStdout: true, label: "DB: check the files of ${name}", script: DatabaseSettings.filesCheckScript(s))
                problems = DatabaseSettings.missingFiles(s, out)
            }
            for (String problem : problems) {
                script.echo "[DB] ${name}: ${problem}"
            }
            count += problems.size()
            if (java) {
                order << "[DB] ${name} comes after ${java} in the list of services: the database changes are then deployed after that application in RD and QC; list the SQL and PL/SQL service first".toString()
            }
        }
        if (count) {
            script.error "[DB] The SQL and PL/SQL settings in ${state.settingsPlace()} have ${count} ${count == 1 ? 'problem' : 'problems'}, listed above: fix them and run the build again"
        }
        for (String line : order) {
            script.echo line
        }
        for (String name : databases) {
            script.echo DatabaseSettings.summary(name, DatabaseSettings.resolve(name, all[name] as Map))
        }
    }

    void build(String variant) {
        boolean step = build.hasLiquibaseStep(state.cfg.build?.liquibase)
        if (step) liquibaseStep { build.runLiquibaseBuild() }
        if (!(variant in ['full', 'security'])) {
            if (!step) {
                script.echo "[BUILD] ${state.currentProjectName}: the ${variant == 'sast' ? 'SAST' : 'Nexus IQ GoldenFix'} pipeline does not package the database changes - not required"
                state.projectNotRequired()
            }
            return
        }
        Map c = context()
        try {
            Map pkg = packageOnly(c, DatabaseSettings.version(script.env.BUILD_NUMBER as String, stamp()))
            archive([pkg.path, pkg.reviewPath, pkg.rollbackPath, "${c.out}/static-checks.txt"])
        } finally {
            clean(c)
        }
    }

    void tests() {
        if (build.hasLiquibaseStep(state.cfg.tests?.unitTests?.liquibase)) liquibaseStep { build.runLiquibaseTests() }
        long startMs = System.currentTimeMillis()
        Map test = (settings().targets as Map).test as Map
        if (!test) {
            script.echo "[DB] ${state.currentProjectName}: no test database (database.test.url) - no unit tests, coverage is not required"
            coverageNotRequired(startMs)
            return
        }
        Map c = context()
        try {
            Map s = c.s as Map
            Map pkg = packaged(c)
            if (test.reset) {
                prepare(c, test)
                Map dropped = online(c, test, pkg, [label: 'DB: Liquibase drop-all on the test database', command: 'drop-all', args: [], out: 'test-drop-all.out', lock: true])
                if (dropped.status != 0) {
                    fail(c, 'test', startMs, [:], "[DB] ${c.name} test: drop-all failed: ${failure(dropped.text as String)}")
                }
                String sql = "${c.dir}/test-reset.sql"
                script.writeFile(file: sql, text: DatabaseSettings.resetSql(test))
                Map rest = online(c, test, pkg, [label: 'DB: drop the code, types and synonyms left on the test database', command: 'execute-sql',
                                                 args: ['--delimiter=/', "--sql-file=${sql}".toString()], out: 'test-reset.out', lock: true])
                if (rest.status != 0) {
                    fail(c, 'test', startMs, [:], "[DB] ${c.name} test: drop-all failed: the packages, procedures, functions, triggers, types and synonyms that it leaves could not be dropped: ${failure(rest.text as String)}")
                }
                script.echo "[DB] ${c.name} test: removed every object of the test schema before the deployment (database.test.reset)"
            }
            deployTo(c, 'test')
            Map found = online(c, test, pkg, [label: 'DB: Liquibase unexpected-changesets on the test database', command: 'unexpected-changesets',
                                              args: ['--verbose=true', "--changelog-file=${s.changelog}".toString()], out: 'test-unexpected.out', quiet: true])
            Map unexpected = found.status == 0 ? LiquibaseOutput.unexpected(found.text as String) : [count: 0]
            state.recordDatabaseTarget('test', [unexpected: unexpected.count])
            if ((unexpected.count as int) > 0) {
                script.echo "[DB] ${c.name} test: ${DatabaseSettings.changesetsText(unexpected.count as int)} in the test database ${unexpected.count == 1 ? 'is' : 'are'} not in this changelog (another branch or job?) - set database.test.reset to true, or give each pipeline its own test schema"
            }
            if ((test.utplsql as Map).enabled) {
                runUtplsql(c, test, startMs)
            } else {
                script.echo "[DB] ${c.name}: deployed to the test database; utPLSQL is off (database.test.utplsql.enabled) - coverage is not required"
                coverageNotRequired(startMs)
            }
            archive(["${c.out}/utplsql-junit.xml", "${c.out}/utplsql-cobertura.xml", "${c.out}/utplsql-coverage-sonar.xml", "${c.out}/utplsql-sonar-tests.xml",
                     "${c.out}/${(s.package as Map).artifactId}-${pkg.version}-test-changes.txt"])
        } finally {
            clean(c)
        }
    }

    private void liquibaseStep(Closure body) {
        script.withEnv(liquibaseRunner.baseEnv(liquibaseRunner.javaHome(state.currentProjectName, state.cfg))) {
            body()
        }
    }

    private void archive(List paths) {
        List files = []
        for (def file : paths) {
            if (file && script.fileExists(file as String)) files << (file as String)
        }
        if (files) script.archiveArtifacts(artifacts: files.join(','), allowEmptyArchive: true)
    }

    Map ensurePackage() {
        Map c = context()
        try {
            return packaged(c)
        } finally {
            clean(c)
        }
    }

    Map applyChanges(String target) {
        Map c = context()
        try {
            return deployTo(c, target)
        } finally {
            clean(c)
        }
    }

    private Map settings() {
        return DatabaseSettings.resolve(state.currentProjectName, state.cfg)
    }

    private Map context() {
        String name = state.currentProjectName
        String jdk = liquibaseRunner.javaHome(name, state.cfg)
        Map liquibase = liquibaseRunner.tool('liquibase', state.cfg)
        Map jar = liquibaseRunner.tool('oracleJdbc', state.cfg)
        return [name: name, s: settings(), jdk: jdk, liquibase: liquibase.path, home: liquibase.home, jar: jar.path,
                dir: liquibaseRunner.workDir(name), out: ".dso-db/${name}".toString()]
    }

    private void clean(Map c) {
        script.dir(c.dir as String) {
            script.deleteDir()
        }
    }

    private Map step(Map c, Map values) {
        return [dir: c.dir, jdk: c.jdk, liquibase: c.liquibase, jar: c.jar, lockWait: (c.s as Map).lockWait,
                properties: "${c.dir}/offline.properties".toString(), quiet: false, lock: false] + values
    }

    private Map offline(Map c, String label, String search, String command, List args, String name, Map extra = [:]) {
        String csv = "${c.dir}/${name}.csv"
        List all = ["--changelog-file=${(c.s as Map).changelog}".toString()] + args +
                ["--url=${DatabaseSettings.offlineUrl((c.s as Map).oracleVersion as String, csv)}".toString()]
        return liquibaseRunner.liquibase(step(c, [label: label, search: search, command: command, args: all, out: "${name}.out".toString(), csv: csv] + extra))
    }

    private Map online(Map c, Map target, Map pkg, Map values) {
        return liquibaseRunner.liquibase(step(c, [search: pkg.path, target: target] + values))
    }

    private void prepare(Map c, Map target) {
        String version = ((state.projectsDatabase[c.name] ?: [:]) as Map).liquibase as String
        if ((version ?: '').startsWith('5.') && !c.jar) {
            script.error "[DB] ${c.name} ${target.name}: Liquibase ${version} has no Oracle driver: set tools.oracleJdbc.path, or tools.oracleJdbc.url with its sha256, in the Global Settings of the DevSecOps portal"
        }
        Map values = ['liquibase.command.url': target.url] + DatabaseSettings.parameterValues(target.parameters as Map)
        script.writeFile(file: "${c.dir}/${target.name}.properties", text: DatabaseSettings.propertiesText(values))
    }

    private static List filters(Map profile) {
        List args = []
        if (profile.contexts) args << "--context-filter=${profile.contexts}".toString()
        if (profile.labels) args << "--label-filter=${profile.labels}".toString()
        return args
    }

    private static String failure(String text) {
        String reason = LiquibaseOutput.reason(text)
        return reason + LiquibaseOutput.hintText(reason)
    }

    private String stamp() {
        return new Date().format('yyyyMMdd-HHmmss', TimeZone.getTimeZone('UTC'))
    }

    private String checkVersion(Map c, String text) {
        String version = LiquibaseOutput.version(text)
        if (!version || LiquibaseOutput.compare(version, '4.33.0') < 0 || LiquibaseOutput.compare(version, '6') >= 0) {
            script.error "[DB] ${c.name}: Liquibase ${version ?: 'of an unknown version'} is not supported: use Liquibase 4.33 or 5.0"
        }
        String licence = LiquibaseOutput.licence(version)
        state.recordDatabase([liquibase: version, licence: licence])
        script.echo "[DB] ${c.name}: Liquibase ${version}, licence ${licence}, JDK ${c.jdk}"
        if (version.startsWith('4.') && c.jar && c.home && script.fileExists("${c.home}/internal/lib/ojdbc8.jar")) {
            script.echo "[DB] ${c.name}: Liquibase 4.33 uses its bundled Oracle driver ${c.home}/internal/lib/ojdbc8.jar before tools.oracleJdbc.path - remove that jar to use the configured driver (chapter 14.2, trimmed install)"
        }
        if (!(version ==~ /(4\.33|5\.0)(\..*)?/)) {
            script.echo "[DB] ${c.name}: Liquibase ${version} has not been tested with the library (tested: 4.33.0 and 5.0.4)"
        }
        return version
    }

    private Map packaged(Map c) {
        Map recorded = ((state.projectsDatabase[c.name] ?: [:]) as Map).package as Map
        if (recorded) return recorded
        Map s = c.s as Map
        if ((s.package as Map).nexusDelivery) return fetchDelivered(c)
        String version = DatabaseSettings.version(script.env.BUILD_NUMBER as String, stamp())
        script.echo "[DB] ${c.name}: database.package.nexusDelivery is false, so this run packages its own checkout as ${(s.package as Map).artifactId}-${version} and deploys that"
        return packageOnly(c, version)
    }

    private Map packageOnly(Map c, String version) {
        Map s = c.s as Map
        String name = c.name
        String root = s.searchRoot
        String base = "${(s.package as Map).artifactId}-${version}"
        state.recordDatabase([version: version])
        state.cfg.delivery = version
        script.writeFile(file: "${c.dir}/offline.properties", text: DatabaseSettings.propertiesText(DatabaseSettings.parameterValues(s.parameters as Map)))
        Map rd = (s.profiles as Map).rd as Map
        script.writeFile(file: "${c.dir}/review.properties", text: DatabaseSettings.propertiesText(DatabaseSettings.parameterValues(rd.parameters as Map)))
        Map validated = offline(c, 'DB: Liquibase validate (offline)', root, 'validate', [], 'validate')
        if (validated.status != 0) {
            script.error "[DB] ${name}: the changelog ${s.changelog} is not valid: ${failure(validated.text as String)}"
        }
        String liquibaseVersion = checkVersion(c, validated.text as String)
        script.echo "[DB] ${name}: the changelog is valid (offline, Oracle ${s.oracleVersion})"
        List findings = staticChecks(c)
        Map all = offline(c, 'DB: Liquibase update-sql of all changesets (offline)', root, 'update-sql', ["--output-file=${c.dir}/all.sql".toString()], 'all')
        if (all.status != 0) script.error "[DB] ${name}: update-sql of the changelog failed: ${failure(all.text as String)}"
        int changesets = LiquibaseOutput.changesets(script.readFile("${c.dir}/all.sql") as String)
        script.echo "[DB] ${name}: ${DatabaseSettings.changesetsText(changesets)} in the changelog"
        String review = "${c.out}/${base}-update.sql"
        Map reviewed = offline(c, 'DB: Liquibase update-sql for review (offline)', root, 'update-sql', filters(rd) + ["--output-file=${review}".toString()], 'review',
                [properties: "${c.dir}/review.properties".toString(), mkdir: c.out])
        if (reviewed.status != 0) script.error "[DB] ${name}: update-sql of the changelog failed: ${failure(reviewed.text as String)}"
        int reviewChangesets = LiquibaseOutput.changesets(script.readFile(review) as String)
        script.echo "[DB] ${name}: review SQL ${review}: ${DatabaseSettings.changesetsText(reviewChangesets)} for contexts ${rd.contexts ?: 'all'}"
        String rollback = ''
        if (s.requireRollback) {
            rollback = "${c.out}/${base}-rollback.sql"
            Map rolled = offline(c, 'DB: Liquibase future-rollback-sql (offline)', root, 'future-rollback-sql', filters(rd) + ["--output-file=${rollback}".toString()], 'rollback',
                    [properties: "${c.dir}/review.properties".toString()])
            if (rolled.status != 0) {
                policy.warn(state.currentStage, "future-rollback-sql failed: ${LiquibaseOutput.reason(rolled.text as String)} - every changeset needs a rollback because database.requireRollback is true")
            }
        }
        Map pkg = makePackage(c, base, version, liquibaseVersion) + [fetched: false, changesets: changesets, reviewChangesets: reviewChangesets, reviewPath: review,
                                                                     rollbackPath: rollback, findings: findings.size(),
                                                                     staticChecksPath: findings ? "${c.out}/static-checks.txt".toString() : '', version: version]
        state.recordDatabase([package: pkg])
        return pkg
    }

    private Map makePackage(Map c, String base, String version, String liquibaseVersion) {
        Map s = c.s as Map
        String folder = s.packageFolder
        String root = s.searchRoot
        String stage = "${c.out}/package"
        String zip = "${c.out}/${base}.zip"
        String place = folder ? "${folder} of ${root}" : root
        String copy = folder
                ? "cp -R ${DatabaseSettings.quote(DatabaseSettings.join(root, folder))} ${DatabaseSettings.quote("${stage}/${folder}")}"
                : "cp -R ${DatabaseSettings.quote("${root}/.")} ${DatabaseSettings.quote("${stage}/")}"
        String text = "#!/usr/bin/env bash\nset -euo pipefail; set +x\nrm -rf ${DatabaseSettings.quote(stage)}; mkdir -p ${DatabaseSettings.quote(stage)}\n${copy}\n" +
                "find ${DatabaseSettings.quote(stage)} -type f -name 'liquibase*.properties' -print -delete | wc -l | tr -d ' ' > ${DatabaseSettings.quote("${c.dir}/package-removed.txt")}\n"
        int staged = script.sh(returnStatus: true, label: "DB: stage the package ${base}", script: text) as int
        if (staged != 0) {
            script.error "[DB] ${c.name}: the package ${zip} is not usable: the copy of the package folder failed (exit code ${staged}) - every file the changelog uses must be under ${place}"
        }
        script.writeFile(file: "${stage}/dso-deploy.properties", text: DatabaseSettings.manifest(s, version, liquibaseVersion))
        try {
            script.zip(zipFile: zip, dir: stage, archive: false)
        } catch (Exception e) {
            if (BuildService.interruptionKind(e)) throw e
            script.error "[DB] ${c.name}: the package ${zip} is not usable: ${e.message} - every file the changelog uses must be under ${place}"
        }
        script.sh(label: 'DB: remove the package stage', script: "rm -rf ${DatabaseSettings.quote(stage)}")
        String count = (script.readFile("${c.dir}/package-removed.txt") as String).trim()
        int removed = count ==~ /\d{1,9}/ ? Integer.parseInt(count) : 0
        Map validated = offline(c, 'DB: Liquibase validate the package (offline)', zip, 'validate', [], 'package')
        if (validated.status != 0) {
            script.error "[DB] ${c.name}: the package ${zip} is not usable: ${failure(validated.text as String)} - every file the changelog uses must be under ${place}"
        }
        script.echo "[DB] ${c.name}: package ${zip} (${folder ? "folder ${place}" : "the whole search root ${root}"}), validated from the package"
        if (removed > 0) {
            script.echo "[DB] ${c.name}: ${removed} liquibase*.properties file(s) were left out of the package: the pipeline passes every Liquibase setting itself, and a properties file may hold a password"
        }
        return [file: "${base}.zip".toString(), path: zip, removedProperties: removed]
    }

    private Map fetchDelivered(Map c) {
        Map s = c.s as Map
        Map pkg = s.package as Map
        String delivery = state.cfg.delivery instanceof String ? DatabaseSettings.str(state.cfg.delivery) : ''
        if (!delivery) {
            script.error "[DB] ${c.name}: the security pipeline delivered no version of ${c.name} (delivery in ${BuildUtils.runStateFile()}) - run the security pipeline first, or set database.package.nexusDelivery to false"
        }
        Map snapshot = pkg.snapshot as Map
        String coordinates = "${pkg.groupId}:${pkg.artifactId}:${delivery}-SNAPSHOT"
        String file = "${pkg.artifactId}-${delivery}-SNAPSHOT.zip"
        String zip = "${c.out}/${file}"
        script.echo "[DB] ${c.name}: deploying the delivered package ${coordinates}"
        String label = "DB: fetch the delivered package ${file}"
        try {
            liquibaseRunner.maven(c.jdk as String, ['dependency:get'], ["-Dartifact=${coordinates}:zip".toString(), '-Dtransitive=false',
                                                                       "-DremoteRepositories=${snapshot.id}::default::${snapshot.url}".toString()], pkg.mvnPath as String, label)
            liquibaseRunner.maven(c.jdk as String, ['dependency:copy'], ['-o', "-Dartifact=${coordinates}:zip".toString(), '-Dmdep.useBaseVersion=true',
                                                                        "-DoutputDirectory=${script.env.WORKSPACE}/${c.out}".toString()], pkg.mvnPath as String, label)
        } catch (Exception e) {
            if (BuildService.interruptionKind(e) || ((e.message ?: '') as String).startsWith('[NEXUS]')) throw e
            script.error "[DB] ${c.name}: the delivered package ${coordinates} could not be fetched from ${snapshot.id}: ${e.message} - check that it is in Nexus and that the agent's Maven settings can read the repository"
        }
        script.writeFile(file: "${c.dir}/offline.properties", text: DatabaseSettings.propertiesText(DatabaseSettings.parameterValues(s.parameters as Map)))
        String place = s.packageFolder ? "${s.packageFolder} of ${s.searchRoot}" : s.searchRoot
        Map validated = offline(c, 'DB: Liquibase validate the package (offline)', zip, 'validate', [], 'package')
        if (validated.status != 0) {
            script.error "[DB] ${c.name}: the package ${zip} is not usable: ${failure(validated.text as String)} - every file the changelog uses must be under ${place}"
        }
        checkVersion(c, validated.text as String)
        Map all = offline(c, 'DB: Liquibase update-sql of all changesets (offline)', zip, 'update-sql', ["--output-file=${c.dir}/all.sql".toString()], 'all')
        if (all.status != 0) script.error "[DB] ${c.name}: update-sql of the changelog failed: ${failure(all.text as String)}"
        Map fetched = [file: file, path: zip, fetched: true, changesets: LiquibaseOutput.changesets(script.readFile("${c.dir}/all.sql") as String), reviewChangesets: 0,
                       reviewPath: '', rollbackPath: '', findings: 0, staticChecksPath: '', removedProperties: 0, version: delivery]
        state.recordDatabase([version: delivery, package: fetched])
        script.archiveArtifacts(artifacts: zip, allowEmptyArchive: true)
        return fetched
    }

    private List staticChecks(Map c) {
        Map s = c.s as Map
        String folder = s.packageFolder ?: '.'
        script.writeFile(file: "${c.dir}/static-facts.awk", text: LiquibaseOutput.factsAwk())
        int status = script.sh(returnStatus: true, label: "DB: read the SQL and PL/SQL sources of ${c.name}",
                script: LiquibaseOutput.factsScript(s.searchRoot as String, folder, c.dir as String)) as int
        if (status != 0) {
            script.error "[DB] ${c.name}: the static checks could not read the SQL and PL/SQL sources under ${folder} of ${s.searchRoot} (exit code ${status})"
        }
        List findings = LiquibaseOutput.staticFindings(LiquibaseOutput.facts(script.readFile("${c.dir}/static-facts.txt") as String), s)
        if (!findings) return findings
        String path = "${c.out}/static-checks.txt"
        script.writeFile(file: path, text: findings.join('\n') + '\n')
        script.archiveArtifacts(artifacts: path, allowEmptyArchive: true)
        List blocking = findings.findAll { LiquibaseOutput.blocks(it as String) }
        for (def finding : blocking ?: findings.take(20)) {
            script.echo "[DB] ${c.name}: static check: ${finding}"
        }
        if (blocking) {
            script.error "[DB] ${c.name}: ${blocking.size()} finding(s) that the library does not deploy (${path}): a password, executeCommand, customChange or a \${...} that names a secret - remove them"
        }
        policy.warn(state.currentStage, "${findings.size()} static check finding(s) in the SQL and PL/SQL sources (${path}): fix them, or list the file in database.checks.ignoreFiles")
        return findings
    }

    private Map deployTo(Map c, String t) {
        long startMs = System.currentTimeMillis()
        try {
            return deploySteps(c, t, startMs)
        } catch (Exception e) {
            Map row = (((((state.projectsDatabase[c.name] ?: [:]) as Map).targets ?: [:]) as Map)[t] ?: [:]) as Map
            if (row.status != 'FAIL') record(c, t, startMs, [status: 'FAIL', reason: LiquibaseOutput.cut((e.message ?: e.getClass().getSimpleName()) as String, 200)])
            throw e
        }
    }

    private Map deploySteps(Map c, String t, long startMs) {
        Map s = c.s as Map
        Map target = (s.targets as Map)[t] as Map
        Map pkg = packaged(c)
        prepare(c, target)
        String scope = "[DB] ${c.name} ${t}"
        String version = ((state.projectsDatabase[c.name] ?: [:]) as Map).liquibase as String
        List status = ['--verbose=true', "--changelog-file=${s.changelog}".toString()] + filters(target)
        Map listed = online(c, target, pkg, [label: "DB: Liquibase status on ${t}".toString(), command: 'status', args: status, out: "${t}-status.out".toString(), quiet: true])
        Map before = LiquibaseOutput.status(listed.text as String)
        if (listed.status != 0) fail(c, t, startMs, [:], "${scope}: status failed: ${failure(listed.text as String)}")
        if (!before.parsed) fail(c, t, startMs, [:], "${scope}: the status output has a format the library does not know (Liquibase ${version})")
        int pending = before.pending as int
        script.echo(pending ? "${scope}: ${DatabaseSettings.changesetsText(pending)} pending before the update" : "${scope}: no changesets pending before the update")
        Map compileCheck = s.compileCheck as Map
        Map baseline = [parsed: false]
        if (compileCheck.enabled) {
            String sql = "${c.dir}/${t}-invalid-before.sql"
            script.writeFile(file: sql, text: DatabaseSettings.invalidBeforeSql(target, compileCheck))
            Map queried = online(c, target, pkg, [label: "DB: invalid objects before the update on ${t}".toString(), command: 'execute-sql',
                                                  args: ['--delimiter=/', "--sql-file=${sql}".toString()], out: "${t}-invalid-before.out".toString(), quiet: true, lock: true])
            baseline = queried.status == 0 ? LiquibaseOutput.resultSets(queried.text as String, ['INVALID_BEFORE']) : [parsed: false]
            if (!baseline.parsed) {
                String why = queried.status != 0 ? LiquibaseOutput.reason(queried.text as String) : 'the output has a format the library does not know'
                script.echo "${scope}: the compile check has no baseline (${why}) - every invalid object of the schema counts"
            }
        }
        Map updated = online(c, target, pkg, [label: "DB: Liquibase update on ${t}".toString(), command: 'update',
                                              args: ["--changelog-file=${s.changelog}".toString()] + filters(target) + ['--show-summary=summary'], out: "${t}-update.out".toString(), lock: true])
        Map applied = LiquibaseOutput.update(updated.text as String)
        List ran = applied.ran as List
        Map counts = [pendingBefore: pending, applied: applied.parsed ? applied.run : ran.size(), previouslyRun: applied.previouslyRun, filteredOut: applied.filteredOut]
        if (updated.status != 0) {
            fail(c, t, startMs, counts, "${scope}: update failed after ${ran.size()} of ${DatabaseSettings.changesetsText(pending)}: ${failure(updated.text as String)} - the changesets that ran stay applied; fix the cause and run the stage again; the library does not roll back")
        }
        script.echo "${scope}: update for contexts ${target.contexts ?: 'all'}: run ${applied.run}, previously run ${applied.previouslyRun}, filtered out ${applied.filteredOut}"
        Map verdict = [failing: [], preexisting: [], errors: []]
        if (compileCheck.enabled) {
            String sql = "${c.dir}/${t}-compile-check.sql"
            script.writeFile(file: sql, text: DatabaseSettings.compileCheckSql(target, compileCheck))
            Map checked = online(c, target, pkg, [label: "DB: compile check on ${t}".toString(), command: 'execute-sql',
                                                  args: ['--delimiter=/', "--sql-file=${sql}".toString()], out: "${t}-compile.out".toString(), lock: true])
            Map after = checked.status == 0 ? LiquibaseOutput.resultSets(checked.text as String, ['INVALID_UPDATED', 'INVALID_OBJECT', 'COMPILE_ERROR']) : [parsed: false]
            if (!after.parsed) {
                String why = checked.status != 0 ? failure(checked.text as String) : 'the output has a format the library does not know'
                fail(c, t, startMs, counts, "${scope}: the compile check could not run: ${why}")
            }
            verdict = LiquibaseOutput.compileVerdict(baseline, after, baseline.parsed as boolean)
            List failing = verdict.failing as List
            List preexisting = verdict.preexisting as List
            List errors = verdict.errors as List
            script.echo "${scope}: compile check: ${failing.size()} invalid objects, ${errors.size()} compile errors${preexisting ? " (${preexisting.size()} invalid before the update, not counted)" : ''}"
            if (preexisting) script.echo "${scope}: invalid before this deployment and not changed by it: ${preexisting.take(20).join(', ')}"
            counts += [invalidObjects: failing.size(), invalidBefore: preexisting.size(), compileErrors: errors.size()]
            if (failing || errors) {
                writeChanges(c, t, pkg, ran)
                List shown = failing + errors
                String more = shown.size() > 20 ? ", and ${shown.size() - 20} more" : ''
                fail(c, t, startMs, counts, "${scope}: the deployment left invalid objects: ${shown.take(20).join(' | ')}${more} - fix the source and deploy again; the library does not roll back")
            }
        }
        Map relisted = online(c, target, pkg, [label: "DB: Liquibase status after the update on ${t}".toString(), command: 'status', args: status,
                                               out: "${t}-status-after.out".toString(), quiet: true])
        Map after = LiquibaseOutput.status(relisted.text as String)
        if (relisted.status != 0) fail(c, t, startMs, counts, "${scope}: status failed: ${failure(relisted.text as String)}")
        if (!after.parsed) fail(c, t, startMs, counts, "${scope}: the status output has a format the library does not know (Liquibase ${version})")
        List left = LiquibaseOutput.pendingAfter(after.ids as List, ran)
        if (left) {
            fail(c, t, startMs, counts + [pendingAfter: left.size()], "${scope}: ${DatabaseSettings.changesetsText(left.size())} ${left.size() == 1 ? 'is' : 'are'} still pending after the update: ${left.take(10).join(', ')} - check their contexts, labels and preconditions")
        }
        int again = (after.ids as List).size()
        script.echo "${scope}: up to date after the update${again ? " (${again} runAlways changesets ran again and are always listed by status)" : ''}"
        String tag = ''
        if (t != 'test' && s.tag) {
            String name = "${(s.package as Map).artifactId}-${pkg.version}"
            if ((counts.applied as int) > 0) {
                Map tagged = online(c, target, pkg, [label: "DB: Liquibase tag on ${t}".toString(), command: 'tag', args: ["--tag=${name}".toString()],
                                                     out: "${t}-tag.out".toString(), quiet: true, lock: true])
                if (tagged.status != 0) {
                    policy.warn(state.currentStage, "the tag ${name} could not be set on ${t}: ${LiquibaseOutput.reason(tagged.text as String)} - the changes are deployed, only the tag is missing")
                } else {
                    tag = name
                    script.echo "${scope}: tagged ${name}"
                }
            } else {
                script.echo "${scope}: nothing ran, so the database keeps its previous tag (a tag with nothing run would replace it)"
            }
        }
        record(c, t, startMs, counts + [status: 'PASS', pendingAfter: 0, invalidObjects: (verdict.failing as List).size(), invalidBefore: (verdict.preexisting as List).size(),
                                        compileErrors: (verdict.errors as List).size(), tag: tag, reason: ''])
        String changes = writeChanges(c, t, pkg, ran)
        if (t != 'test') script.archiveArtifacts(artifacts: changes, allowEmptyArchive: true)
        return ((state.projectsDatabase[c.name] as Map).targets as Map)[t] as Map
    }

    private String writeChanges(Map c, String t, Map pkg, List ran) {
        String path = "${c.out}/${((c.s as Map).package as Map).artifactId}-${pkg.version}-${t}-changes.txt"
        script.writeFile(file: path, text: ran ? ran.join('\n') + '\n' : '')
        return path
    }

    private void record(Map c, String t, long startMs, Map values) {
        Map target = ((c.s as Map).profiles as Map)[t] as Map
        state.recordDatabaseTarget(t, [contexts: target.contexts, durationMs: System.currentTimeMillis() - startMs] + values)
    }

    private void fail(Map c, String t, long startMs, Map values, String message) {
        String prefix = "[DB] ${c.name} ${t}: "
        String reason = message.startsWith(prefix) ? message.substring(prefix.length()) : message
        record(c, t, startMs, values + [status: 'FAIL', reason: LiquibaseOutput.cut(reason, 200)])
        script.error message
    }

    private void runUtplsql(Map c, Map target, long startMs) {
        Map s = c.s as Map
        Map result = liquibaseRunner.utplsql(target, s, c.jdk as String)
        int code = result.status as int
        String junit = "${c.out}/utplsql-junit.xml"
        def summary = script.junit(allowEmptyResults: true, testResults: junit)
        int total = (summary?.totalCount ?: 0) as int
        int failed = (summary?.failCount ?: 0) as int
        int skipped = (summary?.skipCount ?: 0) as int
        state.recordUnitTests([total: total, failed: failed, skipped: skipped, durationMs: System.currentTimeMillis() - startMs])
        state.recordDatabase([tests: [ran: true, total: total, failed: failed, skipped: skipped, exitCode: code]])
        script.echo "[DB] ${c.name}: utPLSQL: ${total} tests, ${failed} failed, ${skipped} skipped"
        if (code == 2) {
            policy.warn(state.currentStage, "utPLSQL: ${failed} of ${total} tests failed (${junit})")
        } else if (code != 0) {
            policy.warn(state.currentStage, "utPLSQL could not run the tests (exit code ${code}): ${LiquibaseOutput.lastLine(result.text as String, 200)} - check that utPLSQL is installed in the test database and that the user may run it")
        } else if (total == 0) {
            policy.warn(state.currentStage, 'utPLSQL ran no test: check that the test packages keep their --%suite annotations (stripComments false) and that database.test.utplsql.paths names their schema')
        }
        int minLine = (state.coverage.minRequired ?: 60) as int
        script.echo "[COVERAGE] Build tool: liquibase  Required by the library policy: ${minLine}%"
        String cobertura = "${c.out}/utplsql-cobertura.xml"
        if (!script.fileExists(cobertura)) {
            policy.missingCoverage("No utPLSQL coverage report (${cobertura})", [])
            return
        }
        Map coverage = LiquibaseOutput.cobertura(script.readFile(cobertura) as String)
        script.echo(coverage.parsed
                ? "[COVERAGE] ${c.name}: utPLSQL Cobertura ${cobertura}: ${coverage.covered} of ${coverage.valid} lines"
                : "[COVERAGE] ${c.name}: utPLSQL Cobertura ${cobertura}: could not be read")
        build.applyCoverage('liquibase', [CoverageReports.row('utplsql', cobertura, coverage.covered as int, coverage.valid as int, coverage.parsed ? '' : 'unreadable')], minLine)
    }

    private void coverageNotRequired(long startMs) {
        int minLine = (state.coverage.minRequired ?: 60) as int
        state.recordCoverage([enabled: false, notRequired: true, minRequired: minLine, reports: []])
        state.recordUnitTests([notRequired: true, durationMs: System.currentTimeMillis() - startMs])
        if (state.policyStatus.coverage == 'SKIP') state.policyStatus.coverage = 'NOT_REQUIRED'
    }
}
