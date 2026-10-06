package devsecops.test

import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

class FakeAbort extends RuntimeException {
    FakeAbort(String message) { super(message) }
}

class FakeEnv extends GroovyObjectSupport implements Serializable {
    Map<String, String> vars = [:]

    Object getProperty(String name) {
        if (name == 'vars') return vars
        return vars.get(name)
    }

    void setProperty(String name, Object value) {
        if (name == 'vars') { vars = (Map) value; return }
        vars.put(name, value == null ? null : value.toString())
    }
}

class FakeBuild implements Serializable {
    String result
    String description
    long duration = 0L
    Map rawBuild = [:]

    String getCurrentResult() { result ?: 'SUCCESS' }
}

class FakeRun implements Serializable {
    String result
    String absoluteUrl
    int number
    long duration

    String getResult() { result }
    String getAbsoluteUrl() { absoluteUrl }
    int getNumber() { number }
    long getDuration() { duration }
    String getCurrentResult() { result }
    Object getBuildVariables() { [:] }
}

class FakeRemoteHandle implements Serializable {
    String buildResult
    String buildUrl

    String getBuildResult() { buildResult }
    URL getBuildUrl() { buildUrl ? new URL(buildUrl) : null }
}

class FakeQualityGate implements Serializable {
    String status
}

class FakeIqEvaluation implements Serializable {
    int criticalComponentCount
    int severeComponentCount
    int moderateComponentCount
    String applicationCompositionReportUrl
}

class FakeTestSummary implements Serializable {
    int totalCount
    int failCount
    int skipCount
    int passCount
}

class FakeFile implements Serializable {
    String path
    String name
    boolean directory
    String toString() { path }
}

class FakeOpenShift implements Serializable {
    List<String> calls = []
    Map buildStatus = [phase: 'Complete', outputDockerImageReference: 'image-registry.openshift-image-registry.svc:5000/ta-certscanner-build/certscanner-api:latest',
                       output: [to: [imageDigest: 'sha256:5f1c0ffee2d4a6b8c0e1f3a5b7d9e0f2a4c6e8b0d2f4a6c8e0b2d4f6a8c0e2f4']]]

    def withCluster(Closure body) { calls << 'withCluster'; body.call() }
    def withCluster(String name, Closure body) { calls << "withCluster ${name}".toString(); body.call() }
    def withCluster(String name, String credentialsId, Closure body) { calls << "withCluster ${name}".toString(); body.call() }
    def withProject(String name, Closure body) { calls << "withProject ${name}".toString(); body.call() }
    def withProject(Closure body) { body.call() }
    String project() { 'ta-certscanner' }
    String cluster() { 'ocp' }
    FakeOpenShiftSelector selector(String kind, String name) { new FakeOpenShiftSelector(shift: this, kind: kind, name: name) }
    List process(Object... args) { calls << 'process'; [[kind: 'Deployment']] }
    FakeOpenShiftSelector apply(Object models) { calls << 'apply'; new FakeOpenShiftSelector(shift: this, kind: 'list', name: 'applied') }
    def tag(Object... args) { calls << "tag ${args.join(' ')}".toString() }
    def raw(Object... args) { calls << "raw ${args.join(' ')}".toString() }
}

class FakeOpenShiftSelector implements Serializable {
    FakeOpenShift shift
    String kind
    String name

    boolean exists() { true }
    List names() { ["${kind}/${name}".toString()] }
    FakeOpenShiftSelector startBuild(Object... args) { shift.calls << "startBuild ${name}".toString(); new FakeOpenShiftSelector(shift: shift, kind: 'build', name: "${name}-1".toString()) }
    def logs(Object... args) { shift.calls << "logs ${name}".toString() }
    Map object() { [status: shift.buildStatus] }
    FakeOpenShiftSelector rollout() { this }
    def status(Object... args) { shift.calls << "rollout status ${name}".toString() }
}

class FakeScript extends GroovyObjectSupport implements Serializable {
    FakeEnv env = new FakeEnv()
    List<String> calls = []
    Map params = [:]
    FakeBuild currentBuild = new FakeBuild()
    Map scm = [:]
    List<String> log = []
    List<String> unhandled = []
    Map<String, String> files = [:]
    Map<String, Object> jsonFiles = [:]
    Map<String, String> resources = [:]
    File resourcesDir = null
    Map<String, Map<String, String>> upstream = [:]
    boolean unix = true
    boolean jsonLib = true
    Map<String, Map> portal = [:]
    Object portalAnswer = null
    int portalExit = 0
    String cwd = ''
    Closure shHandler = { Map args -> '' }
    Closure readFileHandler = null
    boolean recordEvents = false
    List<String> events = []
    Map<String, Closure> steps = [:]

    private String norm(Object path) {
        String p = String.valueOf(path)
        String ws = env.vars.get('WORKSPACE') ?: ''
        if (ws && p.startsWith(ws + '/')) p = p.substring(ws.length() + 1)
        return p.startsWith('./') ? p.substring(2) : p
    }

    Object step(String name, Object args) {
        Closure handler = steps.get(name)
        if (handler != null) return handler.call(args)
        unhandled << name
        return null
    }

    def echo(Object message) { log << String.valueOf(message) }
    def error(Object message) { log << "[error] ${message}".toString(); throw new FakeAbort(String.valueOf(message)) }
    def unstable(Object message) {
        log << "[unstable] ${message}".toString()
        if (currentBuild.result != 'FAILURE') currentBuild.result = 'UNSTABLE'
    }
    def sh(Object arg) { shell('sh', arg) }
    def bat(Object arg) { shell('bat', arg) }
    def powershell(Object arg) { shell('powershell', arg) }
    private Object shell(String kind, Object arg) {
        Map args = arg instanceof Map ? (Map) arg : [script: String.valueOf(arg)]
        calls << "${kind}${args.label ? ' [' + args.label + ']' : ''} ${args.script}".toString()
        if (String.valueOf(args.script).contains('PortalConfigQuery.java')) return answerPortal()
        return shHandler.call(args)
    }
    private Object answerPortal() {
        Object answer = portalAnswer != null ? portalAnswer
                : [results: (env.vars.get('DSO_PORTAL_KEYS') ?: '').tokenize(',').collect { portal.get(it) }]
        files.put(norm(env.vars.get('DSO_PORTAL_OUTPUT')), answer instanceof String ? (String) answer : groovy.json.JsonOutput.toJson(answer))
        return portalExit
    }
    boolean isUnix() { unix }
    boolean fileExists(Object path) { files.containsKey(norm(path)) }
    String readFile(Object arg) {
        String path = norm(arg instanceof Map ? ((Map) arg).file : arg)
        if (!files.containsKey(path)) {
            if (readFileHandler != null) return readFileHandler.call(path)
            throw new FileNotFoundException(path)
        }
        return files.get(path)
    }
    void writeFile(Map args) {
        if (recordEvents) events << "[WRITEFILE ${args.file}]\n${args.text}".toString()
        files.put(norm(args.file), String.valueOf(args.text))
    }
    Object readJSON(Map args) {
        String text = args.text != null ? String.valueOf(args.text) : readFile(args.file)
        if (jsonLib && !args.returnPojo) return net.sf.json.JSONSerializer.toJSON(text)
        return new groovy.json.JsonSlurperClassic().parseText(text)
    }
    void writeJSON(Map args) {
        jsonFiles.put(norm(args.file), args.json)
        files.put(norm(args.file), groovy.json.JsonOutput.toJson(args.json))
    }
    Object readYaml(Map args) {
        if (args.text != null) return new org.yaml.snakeyaml.Yaml().load(String.valueOf(args.text))
        String path = norm(args.file)
        if (!files.containsKey(path)) throw new FileNotFoundException(path)
        return new org.yaml.snakeyaml.Yaml().load(files.get(path))
    }
    void writeYaml(Map args) {
        calls << "writeYaml ${args.file}".toString()
        String path = norm(args.file)
        if (files.containsKey(path) && !args.overwrite) throw new FakeAbort("${path} already exists")
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions()
        options.defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
        files.put(path, new org.yaml.snakeyaml.Yaml(options).dump(args.data))
    }
    String libraryResource(String name) {
        if (resources.containsKey(name)) return resources.get(name)
        File file = resourcesDir == null ? null : new File(resourcesDir, name)
        if (file != null && file.file) return file.getText('UTF-8')
        throw new FakeAbort("No such library resource ${name} could be found.")
    }
    Object findFiles(Map args) {
        String glob = String.valueOf(args.glob ?: '*')
        String regex = glob.replace('.', '\\.').replace('**/', '(.*/)?').replace('*', '[^/]*')
        List<FakeFile> out = files.keySet().findAll { it ==~ regex }.sort().collect {
            new FakeFile(path: it, name: it.tokenize('/').last(), directory: false)
        }
        return out as FakeFile[]
    }
    def withCredentials(List credentials, Closure body) {
        calls << "withCredentials ${credentials.collect { it instanceof Map ? ((Map) it).credentialsId : it }}".toString()
        Map<String, String> saved = [:]
        credentials.each { c ->
            if (c instanceof Map) {
                ['variable', 'usernameVariable', 'passwordVariable'].each { k ->
                    if (c[k]) {
                        saved.put(c[k] as String, env.vars.get(c[k] as String))
                        env.vars.put(c[k] as String, "fake-${c[k]}".toString())
                    }
                }
            }
        }
        try {
            return body.call()
        } finally {
            saved.each { k, v -> if (v == null) env.vars.remove(k) else env.vars.put(k, v) }
        }
    }
    def withEnv(List vars, Closure body) {
        calls << "withEnv ${vars}".toString()
        if (recordEvents) events << "[WITHENV] ${vars.join(' | ')}".toString()
        Map<String, String> saved = [:]
        vars.each { v ->
            String text = String.valueOf(v)
            int eq = text.indexOf('=')
            String name = text.substring(0, eq)
            saved.put(name, env.vars.get(name))
            env.vars.put(name, text.substring(eq + 1))
        }
        try {
            return body.call()
        } finally {
            saved.each { k, v -> if (v == null) env.vars.remove(k) else env.vars.put(k, v) }
        }
    }
    def usernamePassword(Map args) { args }
    def string(Map args) { args }
    def timeout(Map args, Closure body) { body.call() }
    def sleep(Object args) {}
    def dir(String path, Closure body) {
        String saved = cwd
        cwd = norm(path)
        try {
            return body.call()
        } finally {
            cwd = saved
        }
    }
    def deleteDir() {
        calls << "deleteDir ${cwd}".toString()
        files.keySet().removeAll { it.startsWith(cwd + '/') }
    }
    def pwd() {
        String ws = env.vars.get('WORKSPACE')
        if (!ws) throw new FakeAbort('pwd needs a node: no workspace outside node')
        return ws
    }
    def pwd(Map args) { args.tmp ? pwd() + '@tmp' : pwd() }
    def node(Object label, Closure body) {
        calls << "node ${label}".toString()
        return onAgent(label, body)
    }
    def onAgent(Object label, Closure body) {
        Map<String, String> saved = [NODE_NAME: env.vars.get('NODE_NAME'), WORKSPACE: env.vars.get('WORKSPACE'), WORKSPACE_TMP: env.vars.get('WORKSPACE_TMP')]
        env.vars.putAll([NODE_NAME: String.valueOf(label), WORKSPACE: '/ws', WORKSPACE_TMP: '/ws@tmp'])
        try {
            return body.call()
        } finally {
            saved.each { k, v -> if (v == null) env.vars.remove(k) else env.vars.put(k, v) }
        }
    }
    def sshagent(Object credentials, Closure body) { body.call() }
    def checkout(Object args) { calls << 'checkout' }
    def git(Map args) { calls << "git ${args.url}".toString() }
    def junit(Object args) { calls << "junit ${args instanceof Map ? ((Map) args).testResults : args}".toString(); new FakeTestSummary(totalCount: 214, failCount: 0, skipCount: 3, passCount: 211) }
    def archiveArtifacts(Object args) { calls << "archiveArtifacts ${args instanceof Map ? ((Map) args).artifacts : args}".toString() }
    def publishHTML(Object args) { calls << "publishHTML ${args instanceof Map ? ((Map) args).reportFiles : args}".toString() }
    def cleanWs(Object args) { calls << 'cleanWs' }
    def stash(Map args) {}
    def unstash(Object name) {}
    def tool(Object args) { '/opt/tools/' + String.valueOf(args instanceof Map ? ((Map) args).name : args) }
    def nexusPolicyEvaluation(Map args) { step('nexusPolicyEvaluation', args) }
    def selectedApplication(Object application) { application }
    def waitForQualityGate() { step('waitForQualityGate', null) }
    def withSonarQubeEnv(Object config, Closure body) { body.call() }
    def withSonarQubeEnv(Closure body) { body.call() }
    def reportSurefireTest(Map args) { step('reportSurefireTest', args) }
    def reportUnitTest(Map args) { step('reportUnitTest', args) }
    def reportBuild(Map args) { step('reportBuild', args) }
    def addSonarBadgesToDescription(Object... args) {}
    def triggerRemoteJob(Map args) { step('triggerRemoteJob', args) }
    def build(Map args) { calls << "build ${args.job}${args.wait == false ? ' (no wait)' : ''}".toString(); step('build', args) }
    def copyArtifacts(Map args) {
        calls << "copyArtifacts ${args.projectName} filter=${args.filter}".toString()
        Map<String, String> archived = upstream.get(String.valueOf(args.projectName))
        if (archived == null) throw new FakeAbort("Unable to find project for artifact copy: ${args.projectName}")
        List<String> copied = []
        String.valueOf(args.filter ?: '**').split(',').each { String pattern ->
            String regex = pattern.trim().replace('.', '\\.').replace('**/', '(.*/)?').replace('*', '[^/]*')
            archived.each { String name, String text ->
                if (name ==~ regex) {
                    files.put(name, text)
                    copied << name
                }
            }
        }
        if (copied.isEmpty()) throw new FakeAbort("Failed to copy artifacts from ${args.projectName} with filter: ${args.filter}")
    }
    def step(Map args) { calls << "step ${args.get('$class')}".toString() }
    def waitUntil(Closure body) {
        int attempts = 0
        while (!(body.call() as boolean)) {
            if (++attempts > 20) throw new FakeAbort('waitUntil never became true')
        }
    }
    def waitUntil(Map args, Closure body) { waitUntil(body) }
    def lastSuccessful() { [:] }
    def parallel(Map branches) {
        calls << "parallel ${branches.keySet().findAll { it != 'failFast' }.size()}".toString()
        branches.each { name, body ->
            if (name != 'failFast' && body instanceof Closure) body.call()
        }
    }

    Object methodMissing(String name, Object args) {
        unhandled << name
        return null
    }
}

class PipelineDslWhitelist extends Whitelist {

    private static boolean fake(Class type) {
        return type != null && type.name.startsWith('devsecops.test.')
    }

    boolean permitsMethod(Method method, Object receiver, Object[] args) {
        if (fake(method.declaringClass) || fake(receiver?.getClass())) return true
        return receiver instanceof FakeCpsScript && method.name in ['invokeMethod', 'getProperty', 'setProperty']
    }

    boolean permitsConstructor(Constructor<?> constructor, Object[] args) { fake(constructor.declaringClass) }

    boolean permitsStaticMethod(Method method, Object[] args) { fake(method.declaringClass) }

    boolean permitsFieldGet(Field field, Object receiver) { fake(field.declaringClass) || fake(receiver?.getClass()) }

    boolean permitsFieldSet(Field field, Object receiver, Object value) { fake(field.declaringClass) || fake(receiver?.getClass()) }

    boolean permitsStaticFieldGet(Field field) { fake(field.declaringClass) }

    boolean permitsStaticFieldSet(Field field, Object value) { fake(field.declaringClass) }
}

class VarsWhitelist extends Whitelist {

    private static boolean var(Class type) {
        return type != null && type != FakeCpsScript && FakeCpsScript.isAssignableFrom(type)
    }

    boolean permitsMethod(Method method, Object receiver, Object[] args) { var(method.declaringClass) }

    boolean permitsConstructor(Constructor<?> constructor, Object[] args) { var(constructor.declaringClass) }

    boolean permitsStaticMethod(Method method, Object[] args) { var(method.declaringClass) }

    boolean permitsFieldGet(Field field, Object receiver) { var(field.declaringClass) }

    boolean permitsFieldSet(Field field, Object receiver, Object value) { var(field.declaringClass) }

    boolean permitsStaticFieldGet(Field field) { var(field.declaringClass) }

    boolean permitsStaticFieldSet(Field field, Object value) { var(field.declaringClass) }
}

class LibraryClassesWhitelist extends Whitelist {

    private static boolean library(Class type) {
        return type != null && type.name.startsWith('com.bbh.')
    }

    boolean permitsMethod(Method method, Object receiver, Object[] args) { library(method.declaringClass) }

    boolean permitsConstructor(Constructor<?> constructor, Object[] args) { library(constructor.declaringClass) }

    boolean permitsStaticMethod(Method method, Object[] args) { library(method.declaringClass) }

    boolean permitsFieldGet(Field field, Object receiver) { library(field.declaringClass) }

    boolean permitsFieldSet(Field field, Object receiver, Object value) { library(field.declaringClass) }

    boolean permitsStaticFieldGet(Field field) { library(field.declaringClass) }

    boolean permitsStaticFieldSet(Field field, Object value) { library(field.declaringClass) }
}

/**
 * The library globals of one build. Jenkins keeps a global in the binding of the script that first
 * reads it, and every vars script owns a private binding, so the same name resolves to a different
 * instance depending on who asks. The registry reproduces that: {@code create} always hands out a
 * fresh script, while {@code shared} holds the globals contributed by plugins, which resolve from
 * the Run and are therefore the same object everywhere.
 */
class VarRegistry implements Serializable {

    Map<String, Class> types = [:]
    Map<String, Object> shared = [:]
    FakeScript jenkins
    SandboxHarness harness

    boolean knows(String name) { types.containsKey(name) }

    Object create(String name) {
        Class type = types.get(name)
        if (type == null) return null
        FakeCpsScript var = (FakeCpsScript) harness.run { type.newInstance() }
        var.attach(jenkins, this)
        return var
    }
}

/**
 * The binding of the Jenkinsfile itself: it caches the globals the Jenkinsfile reads, and anything
 * put into it stands for a plugin global and is visible to every script of the build.
 */
class VarBinding extends LinkedHashMap<String, Object> {

    VarRegistry registry

    VarBinding(VarRegistry registry) {
        super()
        this.registry = registry
    }

    @Override
    Object get(Object key) {
        String name = key == null ? null : key.toString()
        if (super.containsKey(name)) return super.get(name)
        if (registry.shared.containsKey(name)) return registry.shared.get(name)
        if (registry.knows(name)) {
            Object created = registry.create(name)
            super.put(name, created)
            return created
        }
        return null
    }

    @Override
    Object put(String key, Object value) {
        registry.shared.put(key, value)
        return super.put(key, value)
    }
}
