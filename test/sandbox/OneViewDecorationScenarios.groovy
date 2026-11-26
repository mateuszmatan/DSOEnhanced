import devsecops.test.FakeAbort
import devsecops.test.FakeCpsScript
import devsecops.test.FakeFlowInterruptedException
import devsecops.test.FakeScript
import devsecops.test.OneViewFixtures
import devsecops.test.SandboxHarness
import groovy.json.JsonSlurperClassic

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File srcDir = new File(root, 'src')
File stubDir = new File(root, 'test/sandbox/stub')
File varsDir = new File(root, 'vars')
File fixturesDir = new File(root, 'test/sandbox/fixtures')

int failures = 0
Closure check = { String name, boolean ok, Object detail ->
    if (ok) {
        println "PASS  ${name}"
    } else {
        failures++
        println "FAIL  ${name}"
        if (detail) println "      ${detail}"
    }
}

Map gui = new JsonSlurperClassic().parseText(new File(root, 'test/fixtures/oneview/certscanner-gui-full.json').text) as Map
Map backend = new JsonSlurperClassic().parseText(new File(root, 'test/fixtures/oneview/certscanner-backend-api-full.json').text) as Map
String defaultsText = new File(root, 'test/fixtures/defaults.yaml').text
String decoratePath = '/api/dso/bitbucket/pull-requests/decorate'
String oneViewTarget = "${OneViewFixtures.ONEVIEW_URL}${decoratePath}".toString()
String repository = 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner'
String commit = '3f2c1a9e5b7d4c2a1f0e9d8c7b6a5f4e3d2c1b0a'
String addressForm = 'https://<host>[:<port>][/<path>], without a user, password, query or spaces'
String variablePlace = 'in Manage Jenkins > System > Global properties > Environment variables'
String abortMessage = 'the build was aborted'

Closure jenkins = { Map env ->
    FakeScript j = new FakeScript()
    j.resourcesDir = new File(root, 'resources')
    j.resources['com/bbh/devsecops/defaults.yaml'] = defaultsText
    j.env.vars.putAll([BUILD_NUMBER: '42', JOB_NAME: 'CertScanner-build', BRANCH_NAME: 'feature/login', ONEVIEW_URL: OneViewFixtures.ONEVIEW_URL])
    (env ?: [:]).each { k, v -> if (v == null) j.env.vars.remove(k) else j.env.vars.put(k as String, v as String) }
    return j
}
Closure failureOf = { Throwable t -> SandboxHarness.rejectionOf(t) ?: t.message ?: t.toString() }
Closure withDecoration = { Map document, String service ->
    Map copy = OneViewFixtures.copy(document)
    ((copy.projects as Map)[service] as Map).pullRequestDecoration = [enabled: true]
    return copy
}
Closure recordPosts = { FakeScript s, List<Map> posts, Closure failFor ->
    Closure previous = s.shHandler
    s.shHandler = { Map a ->
        if (!String.valueOf(a.script).contains(decoratePath)) return previous.call(a)
        String file = s.env.vars.get('ONEVIEW_FILE')
        Map payload = new JsonSlurperClassic().parseText(s.files[file] ?: '{}') as Map
        posts << [address: s.env.vars.get('ONEVIEW_URL'), file: file, response: s.env.vars.get('ONEVIEW_RESPONSE'), key: payload.pipelineKey, payload: payload, json: s.files[file]]
        def verdict = failFor == null ? null : failFor(posts.size(), payload)
        if (verdict instanceof Throwable) throw (verdict as Throwable)
        if (verdict) throw new FakeAbort("script returned exit code 22 after ${s.env.vars.get('ONEVIEW_URL')}${decoratePath} answered".toString())
        return ''
    }
}

Closure world = { Map options = [:] ->
    FakeScript s = jenkins((options.env ?: [:]) as Map)
    s.shHandler = { Map a -> a.label == 'Read the commit under test' ? "1764000000|${commit}|dev@bbh.com".toString() : '' }
    List<String> keys = ((options.documents ?: []) as List<Map>).collect { OneViewFixtures.publish(s, it) }
    SandboxHarness h = new SandboxHarness(srcDir, stubDir, fixturesDir)
    Map w = [h: h, s: s, keys: keys, posts: [], error: '']
    try {
        h.run {
            w.state = h.type('com.bbh.core.PipelineState').newInstance()
            if (keys) {
                def loader = h.type('com.bbh.config.ConfigLoader').newInstance(s, w.state)
                loader.load('full', keys.size() == 1 ? [pipelineKey: keys[0]] : [pipelineKeys: keys])
            }
            w.state.commitSha = commit
            if (options.services) {
                w.state.projectsAllCfg = options.services
                w.state.configFile = 'devsecops_config.yaml'
            }
        }
        if (options.windows) s.unix = false
        h.run {
            def os = h.type('com.bbh.core.OsHelper').newInstance(s)
            os.detect()
            w.reporter = h.type('com.bbh.remediation.OneViewDecorationReporter').newInstance(s, w.state, os)
        }
    } catch (Throwable t) {
        w.error = failureOf(t)
    }
    (options.afterLoad ?: { FakeScript x -> }).call(s)
    s.log.clear()
    s.calls.clear()
    recordPosts(s, w.posts as List<Map>, options.failFor as Closure)
    return w
}
Closure report = { Map w, List<Map> decorations ->
    try {
        w.h.run { decorations.each { Map d -> w.reporter.recorded(d) } }
    } catch (Throwable t) {
        w.error = t
    }
    return w
}
Closure decorationOf = { Map w, Map facts -> w.h.run { w.h.type('com.bbh.remediation.model.PullRequestDecoration').create(facts) } as Map }
Map branchFacts = [kind       : 'BRANCH', service: 'gui', scm: [url: repository, credentialsId: 'bitbucket-http-credentials'],
                   pullRequest: [id: '77', url: "${repository}/pull-requests/77".toString(), title: 'Login with a one-time code', branch: 'feature/login', targetBranch: 'develop'],
                   created    : false, added: [lineComments: 2, generalComments: 2, limited: false], maxComments: 25,
                   sast       : [[tool: 'AppScan SAST', severity: 'HIGH', file: 'src/main/java/A.java', line: 42, type: 'SQL Injection', description: 'Found by HCL AppScan in src/main/java/A.java:42', remediation: 'Use a PreparedStatement.']],
                   dependencies: [[component: 'com.h2database:h2', from: '1.4.200', to: '2.2.224', reason: 'Nexus IQ offers only 2.2.224', applied: false]],
                   sonar      : [[severity: 'CRITICAL', file: 'src/main/java/A.java', line: 12, rule: 'java:S2076', message: 'Make sure that this command argument is safe.', remediation: 'Resolve SonarQube rule java:S2076 violation (VULNERABILITY).']],
                   summary    : '**DevSecOps static security results - gui**']
Map dastFacts = [kind: 'DAST', service: 'backend-api', scm: [url: repository], pullRequest: [id: '12', url: "${repository}/pull-requests/12".toString(), title: '[Security] DAST findings - backend-api', branch: 'security-dast-backend-api', targetBranch: 'develop'],
                 created: true, maxComments: 25, dast: [[severity: 'HIGH', type: 'Cross-Site Scripting', endpoint: 'https://backend.bbh.com/login', description: 'Reflected input.', remediation: 'Encode the output.']],
                 summary: '# HCL AppScan DAST Dynamic Security Report']
Closure reportingLine = { String url, String service, String target, String keyHint = '' ->
    "[ONEVIEW] Reporting the decoration of pull request ${url} of ${service} to ${target} for job CertScanner-build build 42${keyHint ? ' with pipeline key ' + keyHint : ''}".toString()
}
Closure failedLine = { String url, String keyHint, String reason ->
    "[ONEVIEW] Failed to report the decoration of pull request ${url} to OneView${keyHint ? ' for pipeline key ' + keyHint : ''}: ${reason}".toString()
}
String stopLine = '[ONEVIEW] The next pull request decorations of this run are not reported to OneView after the failure above'
Closure oneViewLines = { Map w -> (w.s as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') } }
Closure keyPrinted = { Map w -> ((w.s as FakeScript).log + (w.s as FakeScript).calls + (w.posts as List<Map>).collect { [it.file, it.response] }.flatten()).any { String line -> (w.keys + ['pipe-key-99', 'pipe-key-77', 'pipe-key-55']).any { line.contains(it as String) } } }
Closure removed = { Map w -> (w.s as FakeScript).calls.findAll { it.startsWith("sh rm -f 'oneview_decoration_") || it.startsWith("powershell Remove-Item -Force 'oneview_decoration_") } }
String unixScript = '''set +x
code=$(curl -sS -k --connect-timeout 10 --max-time 60 -o "$ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$ONEVIEW_URL/api/dso/bitbucket/pull-requests/decorate" -H "Content-Type: application/json" --data-binary "@$ONEVIEW_FILE")
rc=$?
if [ $rc -ne 0 ]; then
  exit $rc
fi
echo "[ONEVIEW] HTTP $code $(sed 's/"pipelineKey" *: *"[^"]*"/"pipelineKey":"(not shown)"/g' "$ONEVIEW_RESPONSE" 2>/dev/null)"
if [ "$code" -ge 300 ]; then
  exit 1
fi
'''
String windowsScript = '''$code = curl.exe -sS -k --connect-timeout 10 --max-time 60 -o "$env:ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$env:ONEVIEW_URL/api/dso/bitbucket/pull-requests/decorate" -H "Content-Type: application/json" --data-binary "@$env:ONEVIEW_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$answer = (Get-Content -Raw $env:ONEVIEW_RESPONSE -ErrorAction SilentlyContinue) -replace '"pipelineKey" *: *"[^"]*"', '"pipelineKey":"(not shown)"'
Write-Host "[ONEVIEW] HTTP $code $answer"
if ([int]$code -ge 300) { exit 1 }
'''

Map one = world([documents: [gui]])
Map branchDecoration = decorationOf(one, branchFacts)
report(one, [branchDecoration])
Map onePost = one.posts ? (one.posts as List<Map>)[0] : [:]
Map expectedPayload = branchDecoration + [pipelineKey: one.keys[0], job: 'CertScanner-build', build: 42, branch: 'feature/login', commit: commit]
check('one decoration, one POST: the decoration of a run with the pipeline key of its service goes to <ONEVIEW_URL>/api/dso/bitbucket/pull-requests/decorate with the record of the pull request, the pipeline key, the job, the build number, the branch and the commit, and the line names the pull request, the service, the address, the job and the hint of the key',
        !one.error && one.posts.size() == 1 && onePost.address == OneViewFixtures.ONEVIEW_URL && onePost.payload == expectedPayload
                && oneViewLines(one) == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', oneViewTarget, OneViewFixtures.hint(one.keys[0] as String))],
        "${one.error} | ${onePost.payload?.findAll { k, v -> expectedPayload[k] != v }} | ${oneViewLines(one)}")
check('the step of a decoration: the address, the payload file and the answer file reach curl through the environment, curl gives up after 10 seconds without a connection and after 60 seconds in all, the answer is printed without the pipeline key, and both files are removed',
        !one.error && (one.s as FakeScript).calls.findAll { it.startsWith('withEnv [ONEVIEW_') || it.startsWith('sh ') }.size() == 3
                && (one.s as FakeScript).calls.any { it ==~ /withEnv \[ONEVIEW_URL=https:\/\/oneview\.apps\.bbh\.com, ONEVIEW_FILE=oneview_decoration_(\d+)\.json, ONEVIEW_RESPONSE=oneview_response_\1\.json\]/ }
                && (one.s as FakeScript).calls.contains('sh ' + unixScript)
                && removed(one).size() == 1 && removed(one)[0] ==~ /sh rm -f 'oneview_decoration_(\d+)\.json' 'oneview_response_\1\.json'/,
        (one.s as FakeScript).calls)
check('the pipeline key is never printed and is no part of a file name or a step: only the payload file holds it',
        !one.error && !keyPrinted(one) && (onePost.json as String)?.contains(one.keys[0] as String) && !(one.s as FakeScript).calls.any { it.contains('[') && it.startsWith('sh [') },
        (one.s as FakeScript).log)
Map read = onePost.json ? new JsonSlurperClassic().parseText(onePost.json as String) as Map : [:]
List<String> documentKeys = ['service', 'kind', 'repositoryUrl', 'pullRequestId', 'pullRequestUrl', 'title', 'sourceBranch', 'targetBranch', 'created',
                             'lineComments', 'generalComments', 'limited', 'maxComments', 'findings', 'findingsTotal', 'summary', 'pipelineKey', 'job', 'build', 'branch', 'commit']
List<Integer> keyPlaces = documentKeys.collect { String k -> ((onePost.json ?: '') as String).indexOf("\"${k}\":".toString()) }
check('the JSON document: every key of the record and of the run in this order, the build as a number, the findings in their order with line null for the finding without a line, and the summary of the pull request of the analysed branch',
        !one.error && read.keySet() == documentKeys as Set && !keyPlaces.contains(-1) && keyPlaces == keyPlaces.sort(false)
                && read.build == 42 && read.findings*.tool == ['AppScan SAST', 'Nexus IQ', 'SonarQube'] && read.findings[1].line == null && read.findings[1].file == ''
                && read.summary == '**DevSecOps static security results - gui**' && read.lineComments == 2 && read.generalComments == 2,
        onePost.json)

Map several = world([documents: [gui, backend]])
report(several, [decorationOf(several, branchFacts), decorationOf(several, dastFacts), decorationOf(several, branchFacts + [service: 'payments'])])
check('several pipeline keys: each decoration is reported with the key of the Jenkinsfile whose configuration holds its service, each line with its own hint, and a service that no key configures is not reported and gives no line',
        !several.error && several.posts*.key == several.keys && several.posts*.payload*.service == ['gui', 'backend-api']
                && oneViewLines(several) == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', oneViewTarget, OneViewFixtures.hint(several.keys[0] as String)),
                                             reportingLine("${repository}/pull-requests/12".toString(), 'backend-api', oneViewTarget, OneViewFixtures.hint(several.keys[1] as String))]
                && !keyPrinted(several),
        "${several.error} | ${several.posts*.key} | ${oneViewLines(several)}")

[[name: 'DSO_PIPELINE_KEY', env: [DSO_PIPELINE_KEY: 'pipe-key-99'], key: 'pipe-key-99'],
 [name: 'PIPELINE_KEY', env: [PIPELINE_KEY: ' pipe-key-77 '], key: 'pipe-key-77']].each { Map spec ->
    Map given = world([documents: [gui, backend], env: spec.env])
    report(given, [decorationOf(given, branchFacts), decorationOf(given, dastFacts)])
    check("${spec.name} of the job that is no key of the Jenkinsfile comes first, as for the report upload: every decoration is reported with it, the lines show no hint and no other line is printed",
            !given.error && given.posts*.key == [spec.key] * 2
                    && oneViewLines(given) == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', oneViewTarget), reportingLine("${repository}/pull-requests/12".toString(), 'backend-api', oneViewTarget)]
                    && !keyPrinted(given),
            "${given.error} | ${given.posts*.key} | ${oneViewLines(given)}")
}

Map same = world([documents: [gui, backend], env: [DSO_PIPELINE_KEY: '']])
(same.s as FakeScript).env.vars.put('DSO_PIPELINE_KEY', ' ' + (same.keys[1] as String).toUpperCase() + ' ')
report(same, [decorationOf(same, branchFacts), decorationOf(same, dastFacts)])
List<String> afterDecorations = oneViewLines(same)
same.h.run { same.h.type('com.bbh.core.OneViewSettings').newInstance(same.s, same.state).reportKeys() }
String sameLine = "[ONEVIEW] DSO_PIPELINE_KEY holds one of the 2 pipeline keys of the Jenkinsfile (${OneViewFixtures.hint(same.keys[1] as String)}), so the report is uploaded for that key only: it can be removed from the job, and the report is then uploaded once for each key of the Jenkinsfile".toString()
check('DSO_PIPELINE_KEY that holds one of the keys of the Jenkinsfile: both decorations are reported with that key as the run read it, and the line of the report upload that says it can be removed is printed once for the decorations and the report upload together',
        !same.error && same.posts*.key == [same.keys[1]] * 2 && afterDecorations.findAll { it == sameLine }.size() == 1 && oneViewLines(same).findAll { it == sameLine }.size() == 1
                && !keyPrinted(same),
        "${same.error} | ${same.posts*.key} | ${oneViewLines(same)}")

Map configured = world([services: [gui: [oneview: [pipelineKey: 'pipe-key-55']]]])
report(configured, [decorationOf(configured, branchFacts), decorationOf(configured, dastFacts)])
check('without a key of the Jenkinsfile and of the job, oneview.pipelineKey of the configuration is the key of every decoration, as for the report upload, without a hint',
        !configured.error && configured.posts*.key == ['pipe-key-55'] * 2
                && oneViewLines(configured) == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', oneViewTarget), reportingLine("${repository}/pull-requests/12".toString(), 'backend-api', oneViewTarget)],
        "${configured.error} | ${configured.posts*.key} | ${oneViewLines(configured)}")
Map formerBlock = world([services: [gui: [portal: [pipelineKey: 'pipe-key-55']]]])
report(formerBlock, [decorationOf(formerBlock, branchFacts), decorationOf(formerBlock, dastFacts)])
String renameBlock = '[ONEVIEW] portal.pipelineKey is the former name of oneview.pipelineKey: it still works; rename the block portal: to oneview: in devsecops_config.yaml'
check('portal.pipelineKey, the former name of oneview.pipelineKey, still gives the key, and its line is printed once for two decorations',
        !formerBlock.error && formerBlock.posts*.key == ['pipe-key-55'] * 2 && oneViewLines(formerBlock).count { it == renameBlock } == 1,
        "${formerBlock.error} | ${formerBlock.posts*.key} | ${oneViewLines(formerBlock)}")

Map noKey = world([:])
report(noKey, [decorationOf(noKey, branchFacts), decorationOf(noKey, dastFacts)])
check('no key at all: nothing is sent and nothing is printed',
        !noKey.error && !noKey.posts && !oneViewLines(noKey) && !(noKey.s as FakeScript).calls,
        "${noKey.error} | ${(noKey.s as FakeScript).log} | ${(noKey.s as FakeScript).calls}")

Map noAddress = world([documents: [gui], afterLoad: { FakeScript s -> s.env.vars.remove('ONEVIEW_URL') }])
report(noAddress, [decorationOf(noAddress, branchFacts)])
check('no address of OneView: nothing is sent, and with no variable and no setting at all nothing is printed either',
        !noAddress.error && !noAddress.posts && !oneViewLines(noAddress) && !(noAddress.s as FakeScript).calls,
        "${noAddress.error} | ${oneViewLines(noAddress)}")
Map wrongAddress = world([documents: [gui], afterLoad: { FakeScript s -> s.env.vars.put('ONEVIEW_URL', 'https://reader:s3cret@oneview.apps.bbh.com') }])
report(wrongAddress, [decorationOf(wrongAddress, branchFacts), decorationOf(wrongAddress, branchFacts)])
wrongAddress.h.run { wrongAddress.h.type('com.bbh.core.OneViewSettings').newInstance(wrongAddress.s, wrongAddress.state).reportTarget() }
check('an ONEVIEW_URL that is no address of OneView: nothing is sent, one line in the words of the decorations is printed once for two decorations, the line of the report upload keeps its own words, and the password is printed nowhere',
        !wrongAddress.error && !wrongAddress.posts
                && oneViewLines(wrongAddress) == ["[ONEVIEW] The pull request decorations are not reported to OneView: ONEVIEW_URL holds no address of OneView; a Jenkins administrator writes ONEVIEW_URL as ${addressForm}, ${variablePlace}".toString(),
                                                  "[ONEVIEW] The pipeline report is not uploaded: ONEVIEW_URL holds no address of OneView; a Jenkins administrator writes ONEVIEW_URL as ${addressForm}, ${variablePlace}".toString()]
                && !((wrongAddress.s as FakeScript).log + (wrongAddress.s as FakeScript).calls).any { it.contains('s3cret') },
        "${wrongAddress.error} | ${oneViewLines(wrongAddress)}")

Map former = world([documents: [gui], afterLoad: { FakeScript s -> s.env.vars.remove('ONEVIEW_URL'); s.env.vars.put('DSO_PORTAL_URL', 'https://dso-portal.apps.bbh.com') }])
report(former, [decorationOf(former, branchFacts), decorationOf(former, branchFacts)])
check('DSO_PORTAL_URL, the former name of ONEVIEW_URL, holds the address: the decorations go there, and its line is printed once',
        !former.error && former.posts*.address == ['https://dso-portal.apps.bbh.com'] * 2
                && oneViewLines(former).count { it == "[ONEVIEW] DSO_PORTAL_URL is the former name of ONEVIEW_URL: it still works; rename it ${variablePlace}".toString() } == 1
                && oneViewLines(former).count { it == reportingLine("${repository}/pull-requests/77".toString(), 'gui', "https://dso-portal.apps.bbh.com${decoratePath}".toString(), OneViewFixtures.hint(former.keys[0] as String)) } == 2,
        "${former.error} | ${former.posts*.address} | ${oneViewLines(former)}")
String loose = 'http://dso-portal:8080/oneview?tenant=bbh'
Map formerLoose = world([documents: [gui], afterLoad: { FakeScript s -> s.env.vars.remove('ONEVIEW_URL'); s.env.vars.put('PORTAL_URL', loose) }, failFor: { int n, Map p -> true }])
report(formerLoose, [decorationOf(formerLoose, branchFacts)])
check('PORTAL_URL with an address that only a former name may hold: the decoration goes there, the lines name the variable instead of the address, also in the reason of a failure',
        !formerLoose.error && formerLoose.posts*.address == [loose]
                && oneViewLines(formerLoose).findAll { !it.contains('former name') } == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', "the address in PORTAL_URL (path ${decoratePath})".toString(), OneViewFixtures.hint(formerLoose.keys[0] as String)),
                                                                                       failedLine("${repository}/pull-requests/77".toString(), OneViewFixtures.hint(formerLoose.keys[0] as String), "script returned exit code 22 after the address in PORTAL_URL${decoratePath} answered".toString()),
                                                                                       stopLine]
                && !(formerLoose.s as FakeScript).log.any { it.contains('tenant=bbh') },
        "${formerLoose.error} | ${oneViewLines(formerLoose)}")

Map windows = world([documents: [gui], windows: true])
report(windows, [decorationOf(windows, branchFacts)])
check('a Windows agent: the same environment reaches curl.exe from PowerShell with the same two time limits, the answer is printed without the pipeline key, and both files are removed',
        !windows.error && windows.posts.size() == 1 && (windows.s as FakeScript).calls.contains('powershell ' + windowsScript)
                && removed(windows).size() == 1 && removed(windows)[0] ==~ /powershell Remove-Item -Force 'oneview_decoration_(\d+)\.json', 'oneview_response_\1\.json' -ErrorAction SilentlyContinue/
                && !(windows.s as FakeScript).calls.any { it.startsWith('sh ') },
        (windows.s as FakeScript).calls)

Map failed = world([documents: [gui, backend], failFor: { int n, Map p -> n == 1 }])
report(failed, [decorationOf(failed, branchFacts), decorationOf(failed, dastFacts), decorationOf(failed, branchFacts)])
check('a report that fails: one line with the pull request, the hint of the key and the reason, then one line that the next decorations of the run are not reported; nothing more is sent or printed, the files are removed and the result of the build stays as it was',
        !failed.error && failed.posts.size() == 1 && (failed.s as FakeScript).currentBuild.result == null && removed(failed).size() == 1
                && oneViewLines(failed) == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', oneViewTarget, OneViewFixtures.hint(failed.keys[0] as String)),
                                            failedLine("${repository}/pull-requests/77".toString(), OneViewFixtures.hint(failed.keys[0] as String), "script returned exit code 22 after ${oneViewTarget} answered".toString()),
                                            stopLine],
        "${failed.error} | ${failed.posts.size()} | ${oneViewLines(failed)}")
Map idOnly = world([documents: [gui], failFor: { int n, Map p -> true }])
report(idOnly, [decorationOf(idOnly, dastFacts + [service: 'gui', pullRequest: [id: '12']])])
check('a pull request known by its id only: both lines name it by its id',
        !idOnly.error && idOnly.posts.size() == 1
                && oneViewLines(idOnly) == [reportingLine('12', 'gui', oneViewTarget, OneViewFixtures.hint(idOnly.keys[0] as String)),
                                            failedLine('12', OneViewFixtures.hint(idOnly.keys[0] as String), "script returned exit code 22 after ${oneViewTarget} answered".toString()), stopLine],
        "${idOnly.error} | ${oneViewLines(idOnly)}")

Map aborted = world([documents: [gui], failFor: { int n, Map p -> new FakeFlowInterruptedException(abortMessage) }])
report(aborted, [decorationOf(aborted, branchFacts), decorationOf(aborted, branchFacts)])
check('an abort of the build during a report is rethrown: no failure line, the files of that report are removed and nothing more is sent',
        aborted.error instanceof FakeFlowInterruptedException && aborted.posts.size() == 1 && removed(aborted).size() == 1
                && oneViewLines(aborted) == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', oneViewTarget, OneViewFixtures.hint(aborted.keys[0] as String))],
        "${aborted.error} | ${oneViewLines(aborted)}")
Map abortedCleanup = world([documents: [gui]])
Closure beforeCleanup = (abortedCleanup.s as FakeScript).shHandler
(abortedCleanup.s as FakeScript).shHandler = { Map a ->
    if (String.valueOf(a.script).startsWith("rm -f 'oneview_decoration_")) throw new FakeFlowInterruptedException(abortMessage)
    return beforeCleanup.call(a)
}
report(abortedCleanup, [decorationOf(abortedCleanup, branchFacts), decorationOf(abortedCleanup, branchFacts)])
check('an abort of the build while the files of a report are removed is rethrown too: no failure line and nothing more is sent',
        abortedCleanup.error instanceof FakeFlowInterruptedException && abortedCleanup.posts.size() == 1 && removed(abortedCleanup).size() == 1
                && oneViewLines(abortedCleanup) == [reportingLine("${repository}/pull-requests/77".toString(), 'gui', oneViewTarget, OneViewFixtures.hint(abortedCleanup.keys[0] as String))],
        "${abortedCleanup.error} | ${abortedCleanup.posts.size()} | ${oneViewLines(abortedCleanup)}")

Map service = world([documents: [gui], env: [CHANGE_ID: '77', CHANGE_URL: "${repository}/pull-requests/77".toString(), CHANGE_TITLE: 'Login', CHANGE_BRANCH: 'feature/login', CHANGE_TARGET: 'develop']])
Closure throughService = { Map w, Closure prepare ->
    try {
        w.h.run {
            w.publisher = w.h.type('com.bbh.fixture.InMemoryPullRequestPublisher').newInstance()
            w.publisher.repository = repository
            w.publisher.changed = ['src/main/java/A.java']
            def repo = w.h.type('com.bbh.fixture.InMemorySourceRepository').newInstance([[:]] as Object[])
            w.service = w.h.type('com.bbh.remediation.SecurityPullRequestService').newInstance(w.s, w.state, w.publisher, repo, w.reporter)
            w.state.projectsAllCfg = [gui: [scm: [bitbucket: [url: repository, credentialsId: 'bb']], pullRequestDecoration: [enabled: true]]]
            w.state.currentProjectName = 'gui'
            w.state.cfg = w.state.projectsAllCfg.gui
            w.state.projectsScanResults.gui = [sast: 'WARN']
            w.state.projectsSastFindings.gui = [source: 'api', scanId: 'scan-gui', findings: [[id: 'i-1', typeKey: 'SQL Injection', file: 'src/main/java/A.java', line: 42, severity: 'HIGH', type: 'SQL Injection', tool: 'AppScan SAST', description: 'Found by HCL AppScan', remediation: 'Use a PreparedStatement.']]]
            prepare.call(w)
            w.service.publishStaticSecurityPullRequest()
        }
    } catch (Throwable t) {
        w.error = t
    }
    return w
}
throughService(service) { Map w -> }
check('SecurityPullRequestService with this reporter: the decoration of the pull request of the analysed branch and that of security-static-<service>, which the library opens, are reported in that order with the key of the service, after the lines of each decoration',
        !service.error && service.posts*.payload*.kind == ['BRANCH', 'STATIC'] && service.posts*.key == [service.keys[0]] * 2
                && service.posts[1].payload.created == true && service.posts[1].payload.sourceBranch == 'security-static-gui'
                && (service.s as FakeScript).log.findIndexOf { it.startsWith('[SECURITY-PR] gui: pull request') } < (service.s as FakeScript).log.findIndexOf { it.startsWith('[ONEVIEW] Reporting the decoration of pull request') },
        "${service.error} | ${service.posts*.payload*.kind} | ${(service.s as FakeScript).log}")
Map serviceFails = world([documents: [gui], env: [CHANGE_ID: '77'], failFor: { int n, Map p -> true }])
throughService(serviceFails) { Map w -> }
check('SecurityPullRequestService with a report that fails: the decorations go on with the same comments, the second one is not reported, and no [SECURITY-PR] line says that a decoration was not recorded',
        !serviceFails.error && serviceFails.posts.size() == 1 && serviceFails.publisher.lineComments.size() == 2 && serviceFails.publisher.created.size() == 1
                && oneViewLines(serviceFails).count { it == stopLine } == 1 && !(serviceFails.s as FakeScript).log.any { it.contains('was not recorded') }
                && (serviceFails.s as FakeScript).currentBuild.result == null,
        "${serviceFails.error} | ${serviceFails.posts.size()} | ${(serviceFails.s as FakeScript).log}")

Closure apiWorld = { List<Map> documents, Closure body ->
    FakeScript s = jenkins([:])
    List<String> keys = documents.collect { OneViewFixtures.publish(s, it) }
    List<Map> posts = []
    s.shHandler = { Map a ->
        String label = String.valueOf(a.label)
        if (label == 'Read the commit under test') return "1764000000|${commit}|dev@bbh.com".toString()
        if (label == 'Bitbucket: find open pull request') {
            String id = String.valueOf(a.script).contains('security-dast-gui') ? '12' : '13'
            return "{\"values\":[{\"id\":${id},\"links\":{\"self\":[{\"href\":\"${repository}/pull-requests/${id}\"}]}}]}\n200".toString()
        }
        if (label.startsWith('Bitbucket: comment on pull request')) return '{"id":5}\n201'
        return ''
    }
    recordPosts(s, posts, null)
    SandboxHarness h = new SandboxHarness(srcDir, stubDir)
    FakeCpsScript api = h.loadVars(varsDir, s)['devSecOpsApi'] as FakeCpsScript
    Map w = [s: s, keys: keys, posts: posts, error: '']
    try {
        h.run {
            api.configure('full', keys.size() == 1 ? [pipelineKey: keys[0]] : [pipelineKeys: keys])
            s.onAgent('linux-agent') { api.initialize() }
        }
        def state = api.@_state
        for (String name : ['gui', 'backend-api']) {
            state.projectsScanResults[name] = [dast: 'FAIL']
            state.projectsVulnCounts[name] = [dast: [critical: 0, high: 1, medium: 0, low: 0]]
        }
        s.log.clear()
        h.run { s.onAgent('linux-agent') { body.call(api) } }
    } catch (Throwable t) {
        w.error = failureOf(t)
    }
    return w
}
Map viaApi = apiWorld([withDecoration(gui, 'gui'), withDecoration(backend, 'backend-api')]) { api -> api.publishDastPullRequest() }
check('devSecOpsApi passes the reporter to its SecurityPullRequestService: publishDastPullRequest() of a run with two keys comments the DAST report on the open security-dast-<service> of each service and reports each comment with the key of its service',
        !viaApi.error && viaApi.posts*.key == viaApi.keys && viaApi.posts*.payload*.kind == ['DAST', 'DAST'] && viaApi.posts*.payload*.pullRequestId == ['12', '13']
                && viaApi.posts*.payload*.commit == [commit, commit] && viaApi.posts*.payload*.generalComments == [1, 1],
        "${viaApi.error} | ${viaApi.posts*.key} | ${viaApi.posts*.payload*.pullRequestId} | ${(viaApi.s as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') || it.startsWith('[SECURITY-PR]') }}")
String dastStage = 'DAST - Dynamic Application Security Tests - HCL AppScan'
Map inBranch = apiWorld([withDecoration(gui, 'gui')]) { api ->
    api.runInParallel('Dynamic analysis', [dastStage], [(dastStage): { api.publishDastPullRequest() }], [['dast']])
}
check('a parallel branch of devSecOpsApi gets the same reporter: the DAST comment of the branch is reported, with the key of the service',
        !inBranch.error && inBranch.posts*.key == inBranch.keys && inBranch.posts*.payload*.kind == ['DAST'],
        "${inBranch.error} | ${inBranch.posts*.key} | ${(inBranch.s as FakeScript).log.findAll { it.startsWith('[ONEVIEW]') || it.startsWith('[SECURITY-PR]') || it.startsWith('[PARALLEL]') }}")

println "\n${failures == 0 ? 'ALL ONEVIEW DECORATION SCENARIOS PASSED' : failures + ' ONEVIEW DECORATION SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
