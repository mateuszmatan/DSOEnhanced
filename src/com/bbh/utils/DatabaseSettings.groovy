package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class DatabaseSettings implements Serializable {

    @NonCPS
    static List<String> targetNames() {
        return ['test', 'rd', 'qc']
    }

    @NonCPS
    static String str(def value) {
        return value == null ? '' : value.toString().trim()
    }

    @NonCPS
    static Map map(def value) {
        return value instanceof Map ? (value as Map) : [:]
    }

    @NonCPS
    static Integer intOf(def value) {
        if (value instanceof Integer) return value as Integer
        String text = str(value)
        return text ==~ /\d{1,9}/ ? Integer.parseInt(text) : null
    }

    @NonCPS
    static boolean isPath(String value) {
        return value ==~ /[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*/ && !value.tokenize('/').contains('..')
    }

    @NonCPS
    static boolean isSchema(String value) {
        return value ==~ '[A-Za-z][A-Za-z0-9_$#]{0,127}'
    }

    @NonCPS
    static boolean isMaven(String value) {
        return value ==~ /[A-Za-z0-9._-]{1,200}/
    }

    @NonCPS
    static boolean isAbsolute(String value) {
        return value ==~ /\/[^'"\x00-\x1f\x7f]{0,499}/
    }

    @NonCPS
    static boolean isRepositoryUrl(String value) {
        return value.length() <= 1000 && value ==~ /https:\/\/[A-Za-z0-9.-]+(:[0-9]+)?(\/[A-Za-z0-9._~\/-]*)?/
    }

    @NonCPS
    static boolean isSha(String value) {
        return value ==~ /[0-9a-f]{64}/
    }

    @NonCPS
    static boolean isSecretName(String value) {
        String name = value ?: ''
        return name ==~ /(?i).*(password|passwd|pwd|secret|token|credential|key|user).*/ || ['LIQUIBASE_', 'DSO_', 'JENKINS'].any { name.toUpperCase().startsWith(it as String) }
    }

    @NonCPS
    static String quote(String value) {
        return "'" + BuildUtils.escapeForSingleQuotes(value ?: '') + "'"
    }

    @NonCPS
    static String changesetsText(int count) {
        return count == 1 ? '1 changeset' : "${count} changesets".toString()
    }

    @NonCPS
    static boolean concerns(Map projectsAllCfg, Map cfgDefaults) {
        return (projectsAllCfg ?: [:]).values().any { def cfg ->
            DatabaseSettings.str(DatabaseSettings.map(DatabaseSettings.map(cfg).database).changelogFile) || DatabaseSettings.unfinished(DatabaseSettings.map(cfg), cfgDefaults)
        }
    }

    @NonCPS
    static boolean unfinished(Map cfg, Map cfgDefaults) {
        Map db = map(cfg?.database)
        return str(cfg?.buildTool).equalsIgnoreCase('liquibase') && !str(db.changelogFile) && db && db != map(map(cfgDefaults).database)
    }

    @NonCPS
    static String changelogRequired() {
        return 'database.changelogFile is required for the database block; without it the service runs the Liquibase commands of build.liquibase only'
    }

    @NonCPS
    static List allowedTargets(Map cfgDefaults) {
        def entries = map(map(cfgDefaults).database).allowedTargets
        return entries instanceof List ? (entries as List) : []
    }

    @NonCPS
    static Map parameterMap(def raw) {
        Map values = [:]
        for (def entry : map(raw).entrySet()) {
            values[entry.key.toString()] = entry.value == null ? '' : entry.value.toString()
        }
        return values
    }

    @NonCPS
    static Map parameterValues(Map parameters) {
        Map values = [:]
        for (def entry : (parameters ?: [:]).entrySet()) {
            values["parameter.${entry.key}".toString()] = entry.value
        }
        return values
    }

    @NonCPS
    static void add(Map found, int id, String text) {
        List texts = (found[id] ?: []) as List
        texts << text
        found[id] = texts
    }

    @NonCPS
    static List<String> ordered(Map found) {
        List<String> texts = []
        for (int id = 1; id <= 46; id++) {
            texts.addAll((found[id] ?: []) as List)
        }
        return texts
    }

    @NonCPS
    static List<String> globalProblems(Map cfgDefaults) {
        Map found = [:]
        Map tools = map(map(cfgDefaults).tools)
        for (String tool : ['liquibase', 'oracleJdbc', 'utplsql']) {
            toolProblems(found, "defaults.tools.${tool}".toString(), tool, map(tools[tool]))
        }
        def entries = map(map(cfgDefaults).database).allowedTargets
        if (entries != null) {
            if (!(entries instanceof List) || (entries as List).size() > 200 || (entries as List).any { !(it instanceof String) }) {
                add(found, 32, 'defaults.database.allowedTargets must be a list of at most 200 strings')
            } else {
                for (def entry : entries as List) {
                    if (!(entry ==~ '[A-Za-z0-9*][A-Za-z0-9.*-]{0,252}(/[A-Za-z0-9_$#.*-]{1,128})?')) {
                        add(found, 31, "defaults.database.allowedTargets entry ${entry} must be \"host\" or \"host/SERVICE\" of letters, digits, \".\", \"-\" and \"*\"".toString())
                    }
                }
            }
        }
        return ordered(found)
    }

    @NonCPS
    static void toolProblems(Map found, String key, String tool, Map values) {
        String local = str(values[tool == 'oracleJdbc' ? 'path' : 'home'])
        String url = str(values.url)
        String sha = str(values.sha256)
        if (local) {
            if (tool == 'oracleJdbc' && !(isAbsolute(local) && local.endsWith('.jar'))) {
                add(found, 34, "${key}.path ${local} must be an absolute path ending with .jar".toString())
            } else if (!isAbsolute(local)) {
                add(found, 34, "${key}.home ${local} must be an absolute path".toString())
            }
        }
        List extensions = tool == 'liquibase' ? ['.tar.gz', '.tgz', '.zip'] : (tool == 'oracleJdbc' ? ['.jar'] : ['.zip'])
        if (url && !(isRepositoryUrl(url) && extensions.any { url.endsWith(it as String) })) {
            add(found, 33, "${key}.url must be an https URL of a .tar.gz, .tgz, .zip or .jar file, without a user or a password".toString())
        }
        if ((url || sha) && !isSha(sha)) {
            add(found, 29, "${key}.sha256 must be the 64 lower-case hexadecimal digits of the file at ${key}.url".toString())
        }
        if (tool == 'liquibase' && values.javaVersion != null) {
            Integer version = intOf(values.javaVersion)
            if (version == null || version < 17 || version > 25) {
                add(found, 30, "${key}.javaVersion ${values.javaVersion} must be a Java major version from 17 to 25".toString())
            }
        }
    }

    @NonCPS
    static void listProblems(Map found, String key, def raw, int id, String entryText, Closure valid) {
        if (raw == null) return
        if (!(raw instanceof List) || (raw as List).size() > 50 || (raw as List).any { !(it instanceof String) }) {
            add(found, 32, "${key} must be a list of at most 50 strings".toString())
            return
        }
        for (def entry : raw as List) {
            if (!valid(entry as String)) add(found, id, "${key} entry ${entry} ${entryText}".toString())
        }
    }

    @NonCPS
    static void parameterProblems(Map found, String key, def raw) {
        if (raw == null) return
        if (!(raw instanceof Map)) {
            add(found, 32, "${key} must be a map of names to values".toString())
            return
        }
        int count = 0
        for (def entry : (raw as Map).entrySet()) {
            String name = entry.key.toString()
            String value = entry.value == null ? '' : entry.value.toString()
            count++
            if (count == 51 || !(name ==~ /[a-z][a-z0-9_.]{0,63}/)) {
                add(found, 37, "${key} holds the name ${name}, which must start with a lower-case letter and hold only lower-case letters, digits, \"_\" and \".\" (at most 64), and at most 50 names".toString())
            } else if (isSecretName(name)) {
                add(found, 38, "${key} holds the name ${name}, which looks like a secret or a variable of the agent: a name must not hold password, passwd, pwd, secret, token, credential, key or user, nor start with liquibase_, dso_ or jenkins; changelog parameters are written into the package, so keep secrets in Jenkins credentials".toString())
            }
            if (!(value ==~ /([\x21-\x7e]([\x20-\x7e]{0,498}[\x21-\x7e])?)?/) || value.contains('${')) {
                add(found, 39, "${key} ${name} must be printable ASCII of at most 500 characters, without a leading or trailing blank and without \"\${\"".toString())
            }
        }
    }

    @NonCPS
    static void schemaProblem(Map found, String key, def raw) {
        String value = str(raw)
        if (value && !isSchema(value)) {
            add(found, 14, "${key} ${value} must be an Oracle schema name: a letter, then letters, digits, \"_\", \"\$\" or \"#\", at most 128".toString())
        }
    }

    @NonCPS
    static void filterProblem(Map found, String key, def raw) {
        String value = str(raw)
        if (value && !(value ==~ /[A-Za-z0-9_!@(), -]{0,200}/)) {
            add(found, 15, "${key} ${value} may hold only letters, digits, blanks, \"_\", \"!\", \"@\", \"(\", \")\", \",\" and \"-\" (at most 200)".toString())
        }
    }

    @NonCPS
    static void rangeProblem(Map found, int id, String key, def raw, int low, int high, String text) {
        if (raw == null || str(raw) == '') return
        Integer value = intOf(raw)
        if (value == null || value < low || value > high) add(found, id, "${key} ${raw} ${text}".toString())
    }

    @NonCPS
    static List deliveryRepositories(String variant) {
        return (([full: ['snapshot', 'release'], security: ['snapshot'], extended: ['snapshot', 'release']])[variant] ?: []) as List
    }

    @NonCPS
    static List<String> problems(String name, Map cfg, Map cfgDefaults, String variant) {
        Map found = [:]
        Map db = map(cfg?.database)
        Map s = resolve(name, cfg)
        String changelog = s.changelog as String
        if (!changelog) {
            add(found, 1, changelogRequired())
        } else {
            if (!isPath(changelog)) {
                add(found, 2, "database.changelogFile ${changelog} must be a relative path of letters, digits, \".\", \"_\" and \"-\", separated by \"/\", without \"..\" and not starting with \"/\"".toString())
            }
            if (!['.xml', '.yaml', '.yml', '.json', '.sql'].any { changelog.endsWith(it as String) }) {
                add(found, 4, "database.changelogFile ${changelog} must end with .xml, .yaml, .yml, .json or .sql".toString())
            }
            if (!s.packageFolder && s.searchRoot == '.') {
                add(found, 35, "database.changelogFile ${changelog} has no folder and the search root is the repository root: put the changelog in a folder, or set database.searchPath or sourceDir to the folder of the changelog".toString())
            }
        }
        String sonarDir = str(map(map(map(cfg?.tools).sonar).liquibase).dir)
        for (List entry : [['sourceDir', cfg?.sourceDir], ['database.searchPath', db.searchPath], ['tools.sonar.liquibase.dir', sonarDir]]) {
            String value = str(entry[1])
            if (value && value != '.' && !isPath(value)) {
                add(found, 3, "${entry[0]} ${value} must be \".\" or a relative path inside the repository, without \"..\"".toString())
            }
        }
        String oracleVersion = str(db.oracleVersion)
        if (oracleVersion && !(oracleVersion ==~ /[0-9]{1,2}(\.[0-9]{1,2})?/)) {
            add(found, 27, "database.oracleVersion ${oracleVersion} must be an Oracle version such as 19.0 or 23.0".toString())
        }
        schemaProblem(found, 'database.defaultSchema', db.defaultSchema)
        schemaProblem(found, 'database.liquibaseSchema', db.liquibaseSchema)
        filterProblem(found, 'database.contexts', db.contexts)
        filterProblem(found, 'database.labels', db.labels)
        rangeProblem(found, 16, 'database.lockWaitMinutes', db.lockWaitMinutes, 0, 60, 'must be a whole number from 0 to 60')
        parameterProblems(found, 'database.parameters', db.parameters)
        for (String key : ['prod', 'production', 'prd', 'live']) {
            if (db.containsKey(key)) add(found, 10, "database.${key} is not supported: the library never deploys to production".toString())
        }
        List entries = allowedTargets(cfgDefaults)
        for (String t : targetNames()) {
            Map raw = map(db[t])
            String url = str(raw.url)
            String credentials = str(raw.credentialsId)
            if (url) {
                Map parsed = parseUrl(url)
                if (parsed.problem == 'V5') {
                    add(found, 5, "database.${t}.url must not contain a user or a password: keep them in the Jenkins credentials database.${t}.credentialsId".toString())
                } else if (parsed.problem) {
                    add(found, 6, "database.${t}.url must be an Oracle thin URL: jdbc:oracle:thin:@//host:1521/SERVICE, @host:1521:SID, @tcps://host:2484/SERVICE?ssl_server_dn_match=true or @(DESCRIPTION=...)".toString())
                } else if (entries) {
                    String host = (parsed.hosts as List).find { String h -> !DatabaseSettings.allowed([hosts: [h], service: parsed.service], entries) }
                    if (host) {
                        add(found, 11, "database.${t}.url names host ${host}, which defaults.database.allowedTargets does not allow: ask the DevSecOps team to add the RD, QC or test database to the Global Settings".toString())
                    }
                }
                if (!entries) {
                    add(found, 45, "database.${t}.url is set, but defaults.database.allowedTargets is empty: the library deploys only to databases the DevSecOps team allows in the Global Settings".toString())
                }
                if (!credentials) add(found, 7, "database.${t}.credentialsId is required with database.${t}.url".toString())
            } else if (credentials) {
                add(found, 9, "database.${t}.credentialsId is set without database.${t}.url".toString())
            }
            if (credentials && !(credentials ==~ /[A-Za-z0-9._-]{1,200}/)) {
                add(found, 8, "database.${t}.credentialsId ${credentials} must be a Jenkins credentials ID of letters, digits, \".\", \"_\" and \"-\" (at most 200)".toString())
            }
            schemaProblem(found, "database.${t}.defaultSchema".toString(), raw.defaultSchema)
            schemaProblem(found, "database.${t}.liquibaseSchema".toString(), raw.liquibaseSchema)
            filterProblem(found, "database.${t}.contexts".toString(), raw.contexts)
            filterProblem(found, "database.${t}.labels".toString(), raw.labels)
            parameterProblems(found, "database.${t}.parameters".toString(), raw.parameters)
            rangeProblem(found, 40, "database.${t}.timeoutMinutes".toString(), raw.timeoutMinutes, 1, 240, 'must be a whole number from 1 to 240')
        }
        String testUrl = str(map(db.test).url).toLowerCase()
        for (String t : ['rd', 'qc']) {
            if (testUrl && testUrl == str(map(db[t]).url).toLowerCase()) {
                add(found, 12, "database.test.url is also the URL of database.${t}: the test database receives the test packages and must be a database of its own".toString())
            }
        }
        String qcContexts = str(map(db.qc).contexts)
        Map qc = (s.profiles as Map).qc as Map
        if (selectsTest(qc.contexts as String)) {
            add(found, 13, "${qcContexts ? 'database.qc.contexts' : 'database.contexts'} ${qc.contexts ?: '(empty)'} deploys the test changesets: QC needs a context filter such as \"!test\"".toString())
        }
        Map test = map(db.test)
        Map utplsql = map(test.utplsql)
        if (BuildUtils.booleanValue(utplsql.enabled, false) && !testUrl) add(found, 17, 'database.test.utplsql.enabled needs database.test.url')
        if (BuildUtils.booleanValue(test.reset, false) && !testUrl) add(found, 41, 'database.test.reset needs database.test.url')
        String paths = str(utplsql.paths)
        if (paths && !(paths ==~ '[A-Za-z0-9_$#.:,]{0,400}')) {
            add(found, 18, "database.test.utplsql.paths ${paths} may hold only letters, digits, \"_\", \"\$\", \"#\", \".\", \":\" and \",\" (at most 400)".toString())
        }
        schemaProblem(found, 'database.test.utplsql.owner', utplsql.owner)
        for (String key : ['sourcePath', 'testPath']) {
            String value = str(utplsql[key])
            if (value && !isPath(value)) {
                add(found, 19, "database.test.utplsql.${key} ${value} must be a relative path inside the search root, without \"..\"".toString())
            }
        }
        rangeProblem(found, 20, 'database.test.utplsql.timeoutMinutes', utplsql.timeoutMinutes, 1, 240, 'must be a whole number from 1 to 240')
        if (changelog && sonarDir && isPath(sonarDir)) {
            String folder = join(sonarDir, '.')
            String sources = join(s.searchRoot as String, s.packageFolder as String)
            String testPath = str(utplsql.testPath)
            String tests = testPath ? join(s.searchRoot as String, testPath) : ''
            String why = 'sonar-scanner runs in that folder, and SonarQube analyses only the files under it: set tools.sonar.liquibase.dir to a folder that holds the package folder and the utPLSQL test folder, or remove it'
            if (!relativeTo(folder, sources)) {
                add(found, 46, "tools.sonar.liquibase.dir ${sonarDir} does not hold the package folder ${sources}: ${why}".toString())
            }
            if (tests && !relativeTo(folder, tests)) {
                add(found, 46, "tools.sonar.liquibase.dir ${sonarDir} does not hold the utPLSQL test folder ${tests} (database.test.utplsql.testPath): ${why}".toString())
            }
        }
        Map pkg = s.package as Map
        List repositories = pkg.nexusDelivery ? deliveryRepositories(variant) : []
        if (repositories && !pkg.groupId) {
            add(found, 21, "database.package.groupId is required to deliver the package to Nexus in the ${variant} pipeline; set it, or set database.package.nexusDelivery to false".toString())
        }
        Map ids = ['database.package.groupId': pkg.groupId, 'database.package.artifactId': pkg.artifactId,
                   'database.package.snapshotRepository.id': map(pkg.snapshot).id, 'database.package.releaseRepository.id': map(pkg.release).id]
        for (def entry : ids.entrySet()) {
            String value = str(entry.value)
            if ((value || entry.key == 'database.package.artifactId') && !isMaven(value)) {
                add(found, 22, "${entry.key} ${value} may hold only letters, digits, \".\", \"_\" and \"-\" (at most 200)".toString())
            }
        }
        for (String kind : ['snapshot', 'release']) {
            Map repository = map(pkg[kind])
            String key = "database.package.${kind}Repository".toString()
            if (repository.url && !isRepositoryUrl(repository.url as String)) {
                add(found, 28, "${key}.url must be an https URL such as https://nexus.bbh.com/repository/db-snapshots, without a user or a password".toString())
            }
            for (String part : ['id', 'url']) {
                if (repositories.contains(kind) && !repository[part]) {
                    add(found, 36, "${key}.${part} is required to deliver the package to Nexus in the ${variant} pipeline".toString())
                }
            }
        }
        String javaPath = str(cfg?.javaPath)
        if (!javaPath && !BuildUtils.booleanValue(cfg?.buildToolAutoSetup, false)) {
            add(found, 23, 'javaPath is required unless buildToolAutoSetup is true: Liquibase and utPLSQL need Java 17 or newer')
        }
        if (javaPath && !isAbsolute(javaPath)) add(found, 26, "javaPath ${javaPath} must be an absolute path of a JDK 17 or newer".toString())
        listProblems(found, 'database.compileCheck.ignoreObjects', map(db.compileCheck).ignoreObjects, 24, 'must be an Oracle object name',
                { String v -> DatabaseSettings.isSchema(v) })
        listProblems(found, 'database.checks.ignoreFiles', map(db.checks).ignoreFiles, 25, 'must be a relative path inside the search root, without ".."',
                { String v -> DatabaseSettings.isPath(v) })
        String mvnPath = str(map(db.package).mvnPath)
        if (mvnPath && !isAbsolute(mvnPath)) {
            add(found, 44, "database.package.mvnPath ${mvnPath} must be an absolute path of a Maven installation".toString())
        }
        Map tools = map(cfg?.tools)
        Map defaultTools = map(map(cfgDefaults).tools)
        for (String tool : ['liquibase', 'oracleJdbc', 'utplsql']) {
            if (map(tools[tool]) != map(defaultTools[tool])) toolProblems(found, "tools.${tool}".toString(), tool, map(tools[tool]))
        }
        return ordered(found)
    }

    @NonCPS
    static Map resolve(String name, Map cfg) {
        Map db = map(cfg?.database)
        String sourceDir = str(cfg?.sourceDir) ?: '.'
        String changelog = str(db.changelogFile)
        String contexts = str(db.contexts) ?: '!test'
        String labels = str(db.labels)
        String defaultSchema = str(db.defaultSchema)
        String liquibaseSchema = str(db.liquibaseSchema)
        Map parameters = parameterMap(db.parameters)
        Map compileCheck = map(db.compileCheck)
        Map pkg = map(db.package)
        Integer lockWait = intOf(db.lockWaitMinutes)
        Map profiles = [:]
        Map targets = [:]
        for (String t : targetNames()) {
            Map raw = map(db[t])
            Map profile = [name: t, url: str(raw.url), credentialsId: str(raw.credentialsId),
                           contexts: str(raw.contexts) ?: (t == 'test' ? 'test' : contexts), labels: str(raw.labels) ?: labels,
                           defaultSchema: str(raw.defaultSchema) ?: defaultSchema, liquibaseSchema: str(raw.liquibaseSchema) ?: liquibaseSchema,
                           parameters: parameters + parameterMap(raw.parameters), timeoutMinutes: intOf(raw.timeoutMinutes) ?: 30]
            profile.parsed = profile.url ? parseUrl(profile.url as String) : [:]
            if (t == 'test') {
                Map utplsql = map(raw.utplsql)
                profile.reset = BuildUtils.booleanValue(raw.reset, false)
                profile.utplsql = [enabled: BuildUtils.booleanValue(utplsql.enabled, false), paths: str(utplsql.paths),
                                   owner: str(utplsql.owner) ?: profile.defaultSchema, sourcePath: str(utplsql.sourcePath),
                                   testPath: str(utplsql.testPath), timeoutMinutes: intOf(utplsql.timeoutMinutes) ?: 30]
            }
            profiles[t] = profile
            targets[t] = profile.url ? profile : null
        }
        return [name: name, changelog: changelog, sourceDir: sourceDir, searchRoot: join(sourceDir, str(db.searchPath) ?: '.'),
                packageFolder: packageFolder(changelog), oracleVersion: str(db.oracleVersion) ?: '19.0', contexts: contexts, labels: labels,
                defaultSchema: defaultSchema, liquibaseSchema: liquibaseSchema, parameters: parameters,
                requireRollback: BuildUtils.booleanValue(db.requireRollback, false), tag: BuildUtils.booleanValue(db.tag, true),
                lockWait: lockWait == null ? 5 : lockWait,
                compileCheck: [enabled: BuildUtils.booleanValue(compileCheck.enabled, true), recompile: BuildUtils.booleanValue(compileCheck.recompile, true),
                               ignoreObjects: stringList(compileCheck.ignoreObjects).collect { it.toUpperCase() }],
                ignoreFiles: stringList(map(db.checks).ignoreFiles),
                package: [groupId: str(pkg.groupId), artifactId: str(pkg.artifactId) ?: name, nexusDelivery: BuildUtils.booleanValue(pkg.nexusDelivery, true),
                          mvnPath: str(pkg.mvnPath) ?: str(map(map(cfg?.build).maven).mvnPath),
                          snapshot: [id: str(map(pkg.snapshotRepository).id), url: str(map(pkg.snapshotRepository).url)],
                          release: [id: str(map(pkg.releaseRepository).id), url: str(map(pkg.releaseRepository).url)]],
                targets: targets, profiles: profiles]
    }

    @NonCPS
    static List<String> stringList(def raw) {
        return raw instanceof List ? (raw as List).collect { DatabaseSettings.str(it) }.findAll { it } as List<String> : []
    }

    @NonCPS
    static String join(String a, String b) {
        List parts = []
        for (String part : ((a ?: '') + '/' + (b ?: '')).tokenize('/')) {
            if (part == '..' && parts && parts[-1] != '..') {
                parts.remove(parts.size() - 1)
            } else if (part != '.') {
                parts << part
            }
        }
        return parts ? parts.join('/') : '.'
    }

    @NonCPS
    static String relativeTo(String folder, String path) {
        if (folder == '.' || !folder) return path
        if (path == folder) return '.'
        return path.startsWith(folder + '/') ? path.substring(folder.length() + 1) : ''
    }

    @NonCPS
    static String packageFolder(String changelog) {
        int slash = (changelog ?: '').indexOf('/')
        String first = slash > 0 ? changelog.substring(0, slash) : ''
        return first == '.' ? '' : first
    }

    @NonCPS
    static String version(String buildNumber, String stamp) {
        return "${buildNumber ?: '0'}-${stamp}".toString()
    }

    @NonCPS
    static String offlineUrl(String oracleVersion, String csvPath) {
        String encoded = (csvPath ?: '').replace('%', '%25').replace('+', '%2B').replace('&', '%26').replace('=', '%3D').replace(' ', '%20')
        return "offline:oracle?version=${oracleVersion}&changeLogFile=${encoded}".toString()
    }

    @NonCPS
    static Map parseUrl(String url) {
        String text = str(url)
        String prefix = 'jdbc:oracle:thin:'
        if (!text.startsWith(prefix + '@')) {
            return [problem: text.startsWith(prefix) && text.indexOf('@') > prefix.length() ? 'V5' : 'V6']
        }
        String connect = text.substring(prefix.length() + 1)
        String lower = connect.toLowerCase()
        if (connect.contains('@') || ['user=', 'username=', 'password=', 'pwd='].any { lower.contains(it as String) }) return [problem: 'V5']
        if (connect.startsWith('(')) return descriptor(connect)
        String host = '[A-Za-z0-9][A-Za-z0-9.-]{0,252}'
        if (connect ==~ "${host}:[0-9]{1,5}:[A-Za-z0-9_\$#]{1,30}") {
            List parts = connect.tokenize(':')
            return port(parts[1] as String) ? [form: 'sid', hosts: [parts[0]], service: parts[2], connect: connect] : [problem: 'V6']
        }
        String rest = connect
        for (String scheme : ['//', 'tcp://', 'tcps://']) {
            if (lower.startsWith(scheme)) {
                rest = connect.substring(scheme.length())
                break
            }
        }
        int question = rest.indexOf('?')
        String params = question >= 0 ? rest.substring(question + 1) : ''
        String main = question >= 0 ? rest.substring(0, question) : rest
        int slash = main.indexOf('/')
        String hostList = slash >= 0 ? main.substring(0, slash) : ''
        String service = slash >= 0 ? main.substring(slash + 1) : ''
        String param = '(ssl_server_dn_match|ssl_server_cert_dn|connect_timeout|transport_connect_timeout|retry_count|retry_delay)="?[A-Za-z0-9._=, -]{1,200}"?'
        boolean ok = (hostList ==~ "${host}(:[0-9]{1,5})?(,${host}(:[0-9]{1,5})?)*" && service ==~ '[A-Za-z0-9_$#.-]{1,128}'
                && (question < 0 || params ==~ "(?i)${param}(&${param})*"))
        List hosts = []
        for (String entry : ok ? hostList.tokenize(',') : []) {
            List parts = entry.tokenize(':')
            if (parts.size() > 1 && !port(parts[1] as String)) ok = false
            hosts << parts[0]
        }
        return ok ? [form: 'ezconnect', hosts: hosts, service: service, connect: connect] : [problem: 'V6']
    }

    @NonCPS
    static boolean port(String text) {
        int value = Integer.parseInt(text)
        return value >= 1 && value <= 65535
    }

    @NonCPS
    static Map descriptor(String connect) {
        String upper = connect.toUpperCase()
        int depth = 0
        for (int i = 0; i < connect.length() && depth >= 0; i++) {
            String c = connect.substring(i, i + 1)
            depth += c == '(' ? 1 : (c == ')' ? -1 : 0)
            if (depth == 0 && i < connect.length() - 1) depth = -1
        }
        if (!(connect ==~ /(?i)\(DESCRIPTION=[A-Za-z0-9=()._,:\s-]+\)/) || depth != 0
                || ['(USER=', '(USERNAME=', '(PASSWORD=', '(PWD='].any { upper.replaceAll(/\s+/, '').contains(it as String) }) {
            return [problem: 'V6']
        }
        List hosts = valuesOf(connect, 'HOST')
        List services = valuesOf(connect, 'SERVICE_NAME') ?: valuesOf(connect, 'SID')
        return hosts ? [form: 'descriptor', hosts: hosts, service: services ? services[0] : '', connect: connect] : [problem: 'V6']
    }

    @NonCPS
    static List valuesOf(String connect, String name) {
        String compact = connect.replaceAll(/\s+/, '')
        String upper = compact.toUpperCase()
        String marker = "(${name}=".toString()
        List values = []
        int at = upper.indexOf(marker)
        while (at >= 0) {
            int end = compact.indexOf(')', at)
            if (end < 0) break
            values << compact.substring(at + marker.length(), end)
            at = upper.indexOf(marker, end)
        }
        return values
    }

    @NonCPS
    static boolean allowed(Map parsed, List entries) {
        List hosts = (parsed?.hosts ?: []) as List
        if (!hosts || !entries) return false
        String service = str(parsed.service).toLowerCase()
        return hosts.every { def host ->
            entries.any { def entry -> DatabaseSettings.entryAllows(DatabaseSettings.str(entry).toLowerCase(), host.toString().toLowerCase(), service) }
        }
    }

    @NonCPS
    static boolean entryAllows(String entry, String host, String service) {
        int slash = entry.indexOf('/')
        String hostGlob = slash >= 0 ? entry.substring(0, slash) : entry
        if (!(host ==~ glob(hostGlob, '[a-z0-9.-]*'))) return false
        return slash < 0 || service ==~ glob(entry.substring(slash + 1), '[a-z0-9_$#.-]*')
    }

    @NonCPS
    static String glob(String pattern, String star) {
        return pattern.replace('.', '\\.').replace('$', '\\$').replace('*', star)
    }

    @NonCPS
    static String lockId(String buildTag) {
        String id = (buildTag ?: '').replaceAll(/[^A-Za-z0-9._-]/, '-')
        id = id.length() > 100 ? id.substring(0, 100) : id
        return id ?: 'dso'
    }

    @NonCPS
    static String propertiesText(Map values) {
        StringBuilder text = new StringBuilder()
        for (String key : (values ?: [:]).keySet().collect { it.toString() }.sort()) {
            text.append(key).append('=').append((values[key] == null ? '' : values[key].toString()).replace('\\', '\\\\')).append('\n')
        }
        return text.toString()
    }

    @NonCPS
    static String manifest(Map s, String version, String liquibaseVersion) {
        Map qc = (s.profiles as Map).qc as Map
        String zip = "${(s.package as Map).artifactId}-${version}.zip"
        List lines = ["# dso-deploy.properties - written by the DevSecOps library for ${s.name} ${version} (Liquibase ${liquibaseVersion})",
                      '# It holds no URL, user or password. Run Liquibase from the folder that holds the zip, for example:',
                      "#   liquibase --defaults-file=dso-deploy.properties --search-path=${zip} --url=<URL> --username=<USER> --password=<PASSWORD> status --verbose"]
        Map values = ['liquibase.command.changelogFile': s.changelog, 'liquibase.command.contextFilter': qc.contexts,
                      'liquibase.command.labelFilter': qc.labels, 'liquibase.command.defaultSchemaName': qc.defaultSchema,
                      'liquibase.liquibaseSchemaName': qc.liquibaseSchema]
        for (def entry : values.entrySet()) {
            if (entry.value) lines << "${entry.key}=${entry.value}"
        }
        List names = ((qc.parameters ?: [:]) as Map).keySet().collect { it.toString() }.sort()
        if (names) {
            lines << '# changelog parameters to set for each database (the values of RD and QC are not production values):'
            for (String key : names) lines << "#   parameter.${key}"
        }
        return lines.join('\n') + '\n'
    }

    @NonCPS
    static Map sqlParts(Map target, Map compileCheck) {
        String schema = str(target?.defaultSchema).toUpperCase()
        List ignored = ((compileCheck?.ignoreObjects ?: []) as List).collect { "'${it.toString().toUpperCase()}'" }
        String names = ignored ? " NOT IN (${ignored.join(',')})" : ''
        return [objects: schema ? "FROM all_objects WHERE owner = '${schema}' AND" : 'FROM user_objects WHERE',
                errors: schema ? "FROM all_errors WHERE owner = '${schema}' AND" : 'FROM user_errors WHERE',
                schema: schema ? "'${schema}'" : 'USER', objectNames: names ? ' AND object_name' + names : '', errorNames: names ? ' AND name' + names : '']
    }

    @NonCPS
    static String invalidBeforeSql(Map target, Map compileCheck) {
        Map p = sqlParts(target, compileCheck)
        return "SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_BEFORE ${p.objects} status = 'INVALID'${p.objectNames} ORDER BY 1\n/\n".toString()
    }

    @NonCPS
    static String compileCheckSql(Map target, Map compileCheck) {
        Map p = sqlParts(target, compileCheck)
        String text = "SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_UPDATED ${p.objects} status = 'INVALID'${p.objectNames} ORDER BY 1\n/\n"
        if (BuildUtils.booleanValue(compileCheck?.recompile, true)) {
            text += "BEGIN\n  DBMS_UTILITY.COMPILE_SCHEMA(schema => ${p.schema}, compile_all => FALSE, reuse_settings => TRUE);\nEND;\n/\n"
        }
        text += "SELECT object_type || ' ' || object_name AS INVALID_OBJECT ${p.objects} status = 'INVALID'${p.objectNames} ORDER BY 1\n/\n"
        text += "SELECT type || ' ' || name || ' line ' || line || ': ' || REPLACE(text, CHR(10), ' ') AS COMPILE_ERROR ${p.errors} attribute = 'ERROR'${p.errorNames} ORDER BY type, name, sequence\n/\n"
        return text
    }

    @NonCPS
    static String resetSql(Map target) {
        String schema = sqlParts(target, [:]).schema as String
        return "BEGIN\n  FOR o IN (SELECT owner, object_name, object_type FROM all_objects WHERE owner = ${schema} AND object_type IN ('TRIGGER', 'PACKAGE', 'PROCEDURE', 'FUNCTION', 'SYNONYM', 'TYPE')\n" +
                "            AND object_name NOT LIKE 'BIN\$%' ORDER BY DECODE(object_type, 'TRIGGER', 1, 'TYPE', 3, 2), object_name) LOOP\n    BEGIN\n" +
                "      EXECUTE IMMEDIATE 'DROP ' || o.object_type || ' \"' || o.owner || '\".\"' || o.object_name || '\"' || CASE o.object_type WHEN 'TYPE' THEN ' FORCE' END;\n" +
                "    EXCEPTION\n      WHEN OTHERS THEN\n        IF SQLCODE <> -4043 THEN\n          RAISE;\n        END IF;\n    END;\n  END LOOP;\nEND;\n/\n"
    }

    @NonCPS
    static boolean selectsTest(String filter) {
        return !str(filter) || matches(filter, ['test'])
    }

    @NonCPS
    static boolean matches(String expression, List items) {
        Boolean result = evaluate(expression, items)
        return result == null ? true : result
    }

    @NonCPS
    static Boolean evaluate(String expression, List items) {
        String text = str(expression)
        if (text == ':TRUE') return true
        if (text == ':FALSE') return false
        while (text.contains('(')) {
            int open = -1
            int close = -1
            for (int i = 0; i < text.length() && close < 0; i++) {
                String c = text.substring(i, i + 1)
                if (c == '(') {
                    open = i
                } else if (c == ')') {
                    if (open >= 0 && i > open + 1) close = i else open = -1
                }
            }
            if (close < 0) return null
            Boolean nested = evaluate(text.substring(open + 1, close), items)
            if (nested == null) return null
            text = text.substring(0, open) + ' :' + nested.toString().toUpperCase() + ' ' + text.substring(close + 1)
        }
        text = text.replaceAll(/\s*,\s*/, ' or ')
        for (String operator : ['or', 'and']) {
            String[] parts = text.split("\\s+${operator}\\s+")
            if (parts.length > 1) {
                for (String part : parts) {
                    Boolean value = evaluate(part, items)
                    if (value == null) return null
                    if (value == (operator == 'or')) return value
                }
                return operator == 'and'
            }
        }
        boolean negated = false
        String requiredNot = '^@\\s*(!|not )'
        if (text.startsWith('!')) {
            negated = true
            text = text.substring(1).trim()
        } else if (text.toLowerCase().startsWith('not ')) {
            negated = true
            text = text.substring(4).trim()
        } else if (text.matches(requiredNot + '.+')) {
            negated = true
            text = text.replaceFirst(requiredNot, '').trim()
        }
        boolean required = false
        if (text.startsWith('@')) {
            required = true
            text = text.substring(1).trim()
        }
        if (!required && text.equalsIgnoreCase('nocontexts')) return negated ^ items.isEmpty()
        if (!required && items.isEmpty()) return true
        if (text.trim() == ':TRUE') return !negated
        if (text.trim() == ':FALSE') return negated
        for (def item : items) {
            String name = item.toString().startsWith('@') ? item.toString().substring(1).trim() : item.toString()
            if (name.equalsIgnoreCase(text)) return !negated
        }
        return negated
    }

    @NonCPS
    static List<List> fileChecks(Map s) {
        String root = s.searchRoot as String
        Map utplsql = ((((s.profiles as Map).test as Map).utplsql ?: [:]) as Map)
        List<List> checks = [['database.changelogFile', s.changelog, '-f', 42]]
        for (String key : ['sourcePath', 'testPath']) {
            if (utplsql[key]) checks << ["database.test.utplsql.${key}".toString(), utplsql[key], '-d', 43]
        }
        return checks.collect { List c -> c + [DatabaseSettings.join(root, c[1] as String)] }
    }

    @NonCPS
    static String filesCheckScript(Map s) {
        List lines = ['#!/usr/bin/env bash', 'set -euo pipefail; set +x']
        for (List c : fileChecks(s)) {
            String path = quote(c[4] as String)
            lines << "if [ ${c[2]} ${path} ]; then echo ${quote('1 ' + c[4])}; else echo ${quote('0 ' + c[4])}; fi".toString()
        }
        return lines.join('\n') + '\n'
    }

    @NonCPS
    static List<String> missingFiles(Map s, String output) {
        List lines = (output ?: '').readLines().collect { it.trim() }
        List<String> texts = []
        for (List c : fileChecks(s)) {
            if (lines.contains('1 ' + c[4])) continue
            texts << (c[3] == 42
                    ? "database.changelogFile ${c[1]} does not exist under ${s.searchRoot} in this checkout".toString()
                    : "${c[0]} ${c[1]} is not a folder under ${s.searchRoot} in this checkout".toString())
        }
        return texts
    }

    @NonCPS
    static String summary(String name, Map s) {
        Map targets = (s.targets ?: [:]) as Map
        Closure yes = { String t -> targets[t] ? 'yes' : 'no' }
        return "[DB] ${name}: Liquibase service with a database block - changelog ${s.changelog} in ${s.searchRoot}, package folder ${s.packageFolder ?: 'the whole search root'}, test database ${yes('test')}, RD ${yes('rd')}, QC ${yes('qc')}".toString()
    }
}
