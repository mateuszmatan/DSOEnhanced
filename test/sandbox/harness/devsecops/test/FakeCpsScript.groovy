package devsecops.test

abstract class FakeCpsScript extends Script {

    FakeScript jenkins
    Map<String, Object> globals = [:]
    Map model
    Map currentStage
    boolean whenResult = true
    List<String> executedStages = []
    List<String> skippedStages = []
    List<String> stageFailures = []
    List<String> postFailures = []
    List<String> postConditions = []

    void attach(FakeScript fake, Map<String, Object> vars) {
        this.@jenkins = fake
        this.@globals = vars
    }

    def getEnv() { this.@jenkins.env }

    def getParams() { this.@jenkins.params }

    def getCurrentBuild() { this.@jenkins.currentBuild }

    def getScm() { this.@jenkins.scm }

    Object getProperty(String name) {
        Map<String, Object> vars = this.@globals
        if (vars != null && vars.containsKey(name)) return vars.get(name)
        return super.getProperty(name)
    }

    Object invokeMethod(String name, Object args) {
        Object[] arguments = args instanceof Object[] ? (Object[]) args : ([args] as Object[])
        if (getMetaClass().respondsTo(this, name, arguments)) return getMetaClass().invokeMethod(this, name, arguments)
        return this.@jenkins.invokeMethod(name, args)
    }

    def pipeline(Closure body) {
        model = [stages: [], post: [:]]
        body.call()
        runModel()
    }

    def options(Closure body) { body.call() }

    def parameters(Closure body) { body.call() }

    def agent(Closure body) { body.call() }

    def label(Object value) { value }

    def disableConcurrentBuilds() { null }

    def timestamps() { null }

    def logRotator(Map args) { args }

    def buildDiscarder(Object value) { null }

    def booleanParam(Map args) {
        if (!jenkins.params.containsKey(args.name)) jenkins.params[args.name as String] = args.defaultValue
    }

    def choice(Map args) {
        List choices = args.choices instanceof List ? (List) args.choices : []
        if (!jenkins.params.containsKey(args.name)) jenkins.params[args.name as String] = choices ? choices[0] : args.choices
    }

    def stages(Closure body) { body.call() }

    def stage(String name, Closure body) {
        currentStage = [name: name]
        body.call()
        model.stages << currentStage
    }

    def when(Closure body) { currentStage.when = body }

    def steps(Closure body) { currentStage.steps = body }

    def script(Closure body) { body.call() }

    def expression(Closure body) { whenResult = body.call() as boolean }

    def post(Closure body) { body.call() }

    def always(Closure body) { model.post.always = body }

    def success(Closure body) { model.post.success = body }

    def failure(Closure body) { model.post.failure = body }

    def unstable(Closure body) { model.post.unstable = body }

    private void runModel() {
        boolean failed = false
        for (Map st : (model.stages as List<Map>)) {
            String name = st.name as String
            if (failed) {
                skippedStages << name
                continue
            }
            try {
                if (st.when) {
                    whenResult = true
                    ((Closure) st.when).call()
                    if (!whenResult) {
                        skippedStages << name
                        continue
                    }
                }
                ((Closure) st.steps).call()
                executedStages << name
            } catch (Throwable t) {
                failed = true
                jenkins.currentBuild.result = 'FAILURE'
                stageFailures << "${name}: ${SandboxHarness.rejectionOf(t) ?: t.toString()}".toString()
            }
        }
        runPost('always')
        String result = jenkins.currentBuild.currentResult
        if (result == 'SUCCESS') runPost('success')
        if (result == 'UNSTABLE') runPost('unstable')
        if (result == 'FAILURE') runPost('failure')
    }

    private void runPost(String condition) {
        Closure body = (model.post as Map)[condition] as Closure
        if (body == null) return
        postConditions << condition
        try {
            body.call()
        } catch (Throwable t) {
            postFailures << "${condition}: ${SandboxHarness.rejectionOf(t) ?: t.toString()}".toString()
        }
    }
}
