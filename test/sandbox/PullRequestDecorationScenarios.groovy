import devsecops.test.FakeCpsScript
import devsecops.test.FakeFlowInterruptedException
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import groovy.json.JsonOutput
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

String repository = 'https://bitbucket.bbh.com/projects/PAY/repos/payments'
String pullRequests = "${repository}/pull-requests"
String pom = '<project>\n<dependency>\n<artifactId>snakeyaml</artifactId>\n<version>1.33</version>\n</dependency>\n</project>'
String injectionMessage = 'Make sure that this user-controlled command argument does not lead to unwanted behavior.'
Map changeEnv = [CHANGE_ID: '77', CHANGE_URL: "${pullRequests}/77".toString(), CHANGE_TITLE: 'Login with a one-time code', CHANGE_BRANCH: 'feature/login', CHANGE_TARGET: 'develop']
List<Map> everyDecoration = []

Closure sast = { String id, String file, int line, String severity, String type, String remediation ->
    [id: id, typeKey: type, file: file, line: line, severity: severity, type: type, tool: 'AppScan SAST',
     description: "Found by HCL AppScan in ${file.replace('/ws/', '')}:${line}".toString(), remediation: remediation]
}
List<Map> sastFindings = [sast('i-1', '/ws/src/main/java/A.java', 42, 'HIGH', 'SQL Injection', 'Use a PreparedStatement.'),
                          sast('i-2', 'src/main/java/B.java', 7, 'CRITICAL', 'Command Injection', 'Do not pass input to a shell.'),
                          sast('i-3', 'src/main/java/B.java', 19, 'MEDIUM', 'Path Traversal', ''),
                          sast('i-4', 'src/main/java/C.java', 3, 'LOW', 'Weak Hash', 'Use SHA-256.')]
List<Map> sonarIssues = [[component: 'com.bbh:payments:src/main/java/A.java', line: 12, severity: 'CRITICAL', rule: 'java:S2076', message: injectionMessage, type: 'VULNERABILITY'],
                         [component: 'com.bbh:payments:src/main/java/A.java', line: 30, severity: 'MINOR', rule: 'java:S1481', message: 'Remove this unused local variable.', type: 'CODE_SMELL']]
Map goldenFix = [prUrl  : "${pullRequests}/76".toString(), prId: '76', prTitle: 'GoldenFix-202611201200', branch: 'GoldenFix-202611201200', targetBranch: 'develop', status: 'PR_CREATED',
                 changes: [[file: 'pom.xml', component: 'org.yaml:snakeyaml', from: '1.33', to: '1.34', property: '']],
                 unresolved: [[component: 'com.h2database:h2', from: '1.4.200', to: '2.2.224', reason: 'Nexus IQ offers only 2.2.224']]]
Closure serviceCfg = { Map extra = [:] ->
    [scm                 : [bitbucket: [url: repository, credentialsId: 'bb']],
     pullRequestDecoration: [enabled: true],
     tools               : [sonar: [projectKey: 'payments', authToken: 'sonar-token', serverUrl: 'https://sonar.bbh.com']]] + extra
}

Closure world = { Map options = [:] ->
    Map from = (options.from ?: [:]) as Map
    SandboxHarness h = (from.h ?: new SandboxHarness(srcDir, stubDir, fixturesDir)) as SandboxHarness
    FakeScript s = new FakeScript(unix: true)
    s.env.vars.putAll([WORKSPACE: '/ws', JOB_NAME: 'payments-build', BUILD_NUMBER: '42'] + ((options.env ?: [:]) as Map))
    s.files.putAll(((options.files ?: [:]) as Map) + ['pom.xml': pom])
    List sonar = (options.sonar ?: []) as List
    s.shHandler = { Map a -> a.label == 'SonarQube: search issues' ? JsonOutput.toJson([issues: sonar]) + '\n200' : '' }
    Map w = [h: h, s: s]
    h.run {
        w.state = h.type('com.bbh.core.PipelineState').newInstance()
        w.publisher = from.publisher ?: h.type('com.bbh.fixture.InMemoryPullRequestPublisher').newInstance()
        w.recorder = h.type('com.bbh.fixture.RecordingDecorationRecorder').newInstance()
        w.repo = h.type('com.bbh.fixture.InMemorySourceRepository').newInstance([[:]] as Object[])
        Class type = h.type('com.bbh.remediation.SecurityPullRequestService')
        w.service = options.withoutRecorder ? type.newInstance(s, w.state, w.publisher, w.repo) : type.newInstance(s, w.state, w.publisher, w.repo, w.recorder)
    }
    Map<String, Map> services = (options.services ?: [payments: serviceCfg()]) as Map<String, Map>
    w.state.projectsAllCfg = services
    w.state.currentProjectName = services.keySet().iterator().next()
    w.state.cfg = services[w.state.currentProjectName]
    w.publisher.changed = options.containsKey('changed') ? options.changed : ['src/main/java/A.java', 'pom.xml']
    return w
}
Closure staticFindings = { Map w, String service, List<Map> findings ->
    w.state.projectsScanResults[service] = [sast: 'WARN']
    w.state.projectsSastFindings[service] = [source: 'api', scanId: "scan-${service}".toString(), findings: findings]
}
Closure attempt = { Map w, Closure body ->
    try {
        w.h.run { body.call() }
        w.error = null
    } catch (Throwable t) {
        w.error = t
    }
    everyDecoration.addAll(w.recorder.decorations as List)
    return w
}
Closure publishStatic = { Map w -> attempt(w) { w.service.publishStaticSecurityPullRequest() } }
Closure publishDast = { Map w -> attempt(w) { w.service.publishDastPullRequest() } }
Closure securityLines = { Map w -> (w.s.log as List<String>).findAll { it.startsWith('[SECURITY-PR]') || it.startsWith('[WARN] [SECURITY-PR]') } }
Closure posted = { Map w -> [lines: w.publisher.lineComments.collect { [it.id, it.path, it.line, it.text] }, comments: w.publisher.comments.collect { [it.id, it.text] }] }
Closure opened = { Map w, String branch -> w.h.run { w.publisher.open(branch) } as Map }
Closure addedTo = { Map w, String id ->
    [lines: w.publisher.lineComments.count { it.id == id }, comments: w.publisher.comments.count { it.id == id }]
}

Map gfFinding = { String file, Integer line, String title, String description, String remediation ->
    [tool: 'Nexus IQ', severity: '', file: file, line: line, title: title, description: description, remediation: remediation]
}.with { Closure nexus ->
    [applied: nexus('pom.xml', 4, 'org.yaml:snakeyaml 1.33', "The GoldenFix pull request ${pullRequests}/76 makes this change.".toString(), 'Upgrade to 1.34'),
     untouched: nexus('', null, 'com.h2database:h2 1.4.200', 'Not changed automatically: Nexus IQ offers only 2.2.224.', 'Upgrade to 2.2.224')]
}
List<Map> sastExpected = [[tool: 'AppScan SAST', severity: 'HIGH', file: 'src/main/java/A.java', line: 42, title: 'SQL Injection', description: 'Found by HCL AppScan in src/main/java/A.java:42', remediation: 'Use a PreparedStatement.'],
                          [tool: 'AppScan SAST', severity: 'CRITICAL', file: 'src/main/java/B.java', line: 7, title: 'Command Injection', description: 'Found by HCL AppScan in src/main/java/B.java:7', remediation: 'Do not pass input to a shell.'],
                          [tool: 'AppScan SAST', severity: 'MEDIUM', file: 'src/main/java/B.java', line: 19, title: 'Path Traversal', description: 'Found by HCL AppScan in src/main/java/B.java:19', remediation: '']]
Map sonarExpected = [tool: 'SonarQube', severity: 'CRITICAL', file: 'src/main/java/A.java', line: 12, title: "java:S2076: ${injectionMessage}".toString(), description: injectionMessage,
                     remediation: 'Resolve SonarQube rule java:S2076 violation (VULNERABILITY).']

Closure decorated = { Map options = [:] ->
    Map w = world([env: changeEnv, sonar: sonarIssues] + options)
    staticFindings(w, 'payments', sastFindings)
    w.state.projectsGoldenFix = [payments: goldenFix + [:]]
    publishStatic(w)
}
Map first = decorated()
List<Map> recorded = first.recorder.decorations as List<Map>
String summary = first.publisher.comments.findAll { it.id == '77' }.collect { it.text as String }.find { it.startsWith('**DevSecOps static security results - payments**') }
Map branchExpected = [service        : 'payments', kind: 'BRANCH', repositoryUrl: repository, pullRequestId: '77', pullRequestUrl: "${pullRequests}/77".toString(),
                      title          : 'Login with a one-time code', sourceBranch: 'feature/login', targetBranch: 'develop', created: false,
                      lineComments   : 3, generalComments: 3, limited: false, maxComments: 25,
                      findings       : sastExpected + [gfFinding.applied, gfFinding.untouched, sonarExpected], findingsTotal: 6, summary: summary]
check('BRANCH: the decoration of the pull request of the analysed branch (CHANGE_ID) is recorded once with its pull request, the comments it added under a line and on the pull request (the summary counted with them), and its SAST, Nexus IQ and SonarQube findings in the order of the comments - the findings below the minimum severity are not part of it',
        !first.error && recorded.size() == 2 && recorded[0] == branchExpected && summary != null
                && addedTo(first, '77') == [lines: 3, comments: 3],
        first.error ?: (recorded ? recorded[0].findAll { k, v -> branchExpected[k] != v } : recorded))
Map staticExpected = [service        : 'payments', kind: 'STATIC', repositoryUrl: repository, pullRequestId: '76', pullRequestUrl: "${pullRequests}/76".toString(),
                      title          : 'GoldenFix-202611201200', sourceBranch: 'GoldenFix-202611201200', targetBranch: 'develop', created: false,
                      lineComments   : 2, generalComments: 1, limited: false, maxComments: 25,
                      findings       : sastExpected + [sonarExpected], findingsTotal: 4, summary: '']
check('STATIC: the decoration of the GoldenFix pull request of the repository is recorded once, after the one of the analysed branch, with the SAST and SonarQube findings and no summary; the library did not create that pull request',
        !first.error && recorded.size() == 2 && recorded[1] == staticExpected && addedTo(first, '76') == [lines: 2, comments: 1],
        first.error ?: (recorded.size() > 1 ? recorded[1].findAll { k, v -> staticExpected[k] != v } : recorded))
check('the console of a decoration that is recorded has no new line: the [SECURITY-PR] lines are those of the decoration',
        !first.error && !securityLines(first).any { it.contains('recorded') }
                && securityLines(first).contains("[SECURITY-PR] payments: pull request ${pullRequests}/77 of the analysed branch decorated with 3 SAST and 1 SonarQube finding(s)".toString()),
        securityLines(first))

Map plain = decorated([withoutRecorder: true])
check('no recorder at all (DSOEnhancedWithoutPortal): the same comments, the same console lines and the same state as with a recorder, and nothing is recorded',
        !plain.error && posted(plain) == posted(first) && plain.s.log == first.s.log && plain.s.calls == first.s.calls
                && plain.recorder.decorations.isEmpty() && plain.state.projectsGoldenFix == first.state.projectsGoldenFix,
        plain.error ?: [posted(plain) == posted(first), plain.s.log - first.s.log, first.s.log - plain.s.log])

Map again = world([from: first, env: changeEnv, sonar: sonarIssues])
staticFindings(again, 'payments', sastFindings)
again.state.projectsGoldenFix = [payments: goldenFix + [:]]
publishStatic(again)
check('a second run whose comments are already on both pull requests adds no comment and records no decoration',
        !again.error && again.recorder.decorations.isEmpty() && posted(again) == posted(first)
                && securityLines(again).contains("[SECURITY-PR] payments: pull request ${pullRequests}/77 of the analysed branch decorated with 3 SAST and 1 SonarQube finding(s)".toString()),
        again.error ?: again.recorder.decorations)

Map failingPublisher = world([env: changeEnv, sonar: sonarIssues])
failingPublisher.publisher.failure = 'Bitbucket answered 503'
staticFindings(failingPublisher, 'payments', sastFindings)
failingPublisher.state.projectsGoldenFix = [payments: goldenFix + [:]]
publishStatic(failingPublisher)
check('every comment fails: the decoration adds nothing, prints its failures as before and records nothing',
        !failingPublisher.error && failingPublisher.recorder.decorations.isEmpty() && failingPublisher.publisher.comments.isEmpty() && failingPublisher.publisher.lineComments.isEmpty()
                && securityLines(failingPublisher).count { it.startsWith('[SECURITY-PR] Could not add ') } == 8
                && securityLines(failingPublisher).contains("[SECURITY-PR] Comment on pull request ${pullRequests}/77 not added: Bitbucket answered 503".toString()),
        failingPublisher.error ?: securityLines(failingPublisher))

Map nothing = world([sonar: []])
nothing.state.projectsGoldenFix = [payments: goldenFix + [changes: [], unresolved: []]]
nothing.state.projectsScanResults.payments = [sast: 'PASS']
publishStatic(nothing)
check('nothing to post: a GoldenFix pull request without SAST and SonarQube findings and no pull request of the analysed branch - no comment, no decoration recorded',
        !nothing.error && nothing.recorder.decorations.isEmpty() && nothing.publisher.comments.isEmpty() && nothing.publisher.lineComments.isEmpty(),
        nothing.error ?: nothing.recorder.decorations)

Map byBranch = world([env: [BRANCH_NAME: 'feature/statement-export'], sonar: []])
Map branchPullRequest = opened(byBranch, 'feature/statement-export')
staticFindings(byBranch, 'payments', sastFindings.take(1))
publishStatic(byBranch)
List<Map> byBranchRecorded = byBranch.recorder.decorations as List<Map>
Map securityStatic = byBranch.publisher.openPullRequests['security-static-payments'] as Map
check('BRANCH found by its branch: without CHANGE_ID the open pull request of BRANCH_NAME is decorated; its source branch is that branch, its title and target branch are unknown and empty',
        !byBranch.error && byBranchRecorded.size() == 2 && byBranchRecorded[0].kind == 'BRANCH' && byBranchRecorded[0].pullRequestId == branchPullRequest.id
                && byBranchRecorded[0].pullRequestUrl == branchPullRequest.url && byBranchRecorded[0].sourceBranch == 'feature/statement-export'
                && byBranchRecorded[0].title == '' && byBranchRecorded[0].targetBranch == '' && byBranchRecorded[0].lineComments == 1 && byBranchRecorded[0].generalComments == 1,
        byBranch.error ?: byBranchRecorded)
check('STATIC created by the library: without a GoldenFix pull request the library opens security-static-<service> and records it as created, with the title it gave it and the target branch of the repository',
        !byBranch.error && securityStatic != null && byBranchRecorded.size() == 2
                && byBranchRecorded[1].subMap(['kind', 'pullRequestId', 'pullRequestUrl', 'title', 'sourceBranch', 'targetBranch', 'created', 'lineComments', 'generalComments', 'findingsTotal', 'summary'])
                == [kind        : 'STATIC', pullRequestId: securityStatic.id, pullRequestUrl: securityStatic.url, title: '[Security] Static analysis findings - payments',
                    sourceBranch: 'security-static-payments', targetBranch: 'develop', created: true, lineComments: 1, generalComments: 0, findingsTotal: 1, summary: ''],
        byBranch.error ?: byBranchRecorded)

Map conflict = world([sonar: []])
conflict.publisher.answerExisting = true
staticFindings(conflict, 'payments', sastFindings.take(1))
publishStatic(conflict)
check('STATIC: when Bitbucket answers that security-static-<service> exists already, the library did not create it - the decoration of its comments is recorded with created false',
        !conflict.error && conflict.recorder.decorations.size() == 1 && conflict.recorder.decorations[0].created == false && conflict.recorder.decorations[0].lineComments == 1,
        conflict.error ?: conflict.recorder.decorations)

Map foundOpen = world([sonar: []])
Map securityOpen = opened(foundOpen, 'security-static-payments')
staticFindings(foundOpen, 'payments', sastFindings.take(1))
publishStatic(foundOpen)
Map foundOpenExpected = [service        : 'payments', kind: 'STATIC', repositoryUrl: repository, pullRequestId: securityOpen.id, pullRequestUrl: securityOpen.url,
                         title          : '[Security] Static analysis findings - payments', sourceBranch: 'security-static-payments', targetBranch: 'develop', created: false,
                         lineComments   : 1, generalComments: 0, limited: false, maxComments: 25, findings: sastExpected.take(1), findingsTotal: 1, summary: '']
check('STATIC found open: without a GoldenFix pull request the open security-static-<service> of an earlier run gets the new comments, and its record carries the title the library gives that pull request, its source branch and the target branch of the repository - the library did not create it',
        !foundOpen.error && foundOpen.publisher.created.isEmpty() && foundOpen.recorder.decorations.size() == 1 && foundOpen.recorder.decorations[0] == foundOpenExpected,
        foundOpen.error ?: foundOpen.recorder.decorations.collect { it.findAll { k, v -> foundOpenExpected[k] != v } })

Map services = [gui: serviceCfg(), 'backend-api': serviceCfg()]
Map several = world([env: changeEnv, sonar: [], services: services])
staticFindings(several, 'gui', [sast('g-1', 'src/main/java/A.java', 5, 'HIGH', 'Cross-Site Scripting', 'Encode the output.')])
staticFindings(several, 'backend-api', [sast('b-1', 'src/main/java/A.java', 8, 'CRITICAL', 'SQL Injection', 'Use a PreparedStatement.')])
FakeCpsScript steps = several.h.loadVars(varsDir, several.s)['devSecOpsSteps'] as FakeCpsScript
def stepsApi = several.h.type('com.bbh.fixture.DecorationStepsApi').newInstance()
stepsApi.service = several.service
stepsApi.state = several.state
attempt(several) { steps.decorateStaticPullRequests(stepsApi) }
List<Map> severalRecorded = several.recorder.decorations as List<Map>
int severalAdded = severalRecorded.sum(0) { (it.lineComments as int) + (it.generalComments as int) } as int
check('devSecOpsSteps.decorateStaticPullRequests over two services calls publishStaticSecurityPullRequest once per service and each call goes over both services: only the first call adds comments, so each decoration is recorded once and every comment is in one record',
        !several.error && stepsApi.calls == ['publishStaticSecurityPullRequest gui', 'publishStaticSecurityPullRequest backend-api']
                && severalRecorded.collect { [it.kind, it.service] } == [['BRANCH', 'gui'], ['STATIC', 'gui'], ['BRANCH', 'backend-api'], ['STATIC', 'backend-api']]
                && severalRecorded.collect { it.pullRequestId }.unique().size() == 3 && severalRecorded.findAll { it.kind == 'STATIC' }.every { it.created == true }
                && severalAdded == several.publisher.comments.size() + several.publisher.lineComments.size() && severalAdded == 6,
        several.error ?: [stepsApi.calls, severalRecorded.collect { [it.kind, it.service, it.pullRequestId, it.lineComments, it.generalComments] }])

String dastHtml = { List<Map> issues ->
    issues.withIndex().collect { Map issue, int i ->
        "<p>Issue ID: d-${i + 1}</p><p>Severity: ${issue.severity}</p><p>Issue Type: ${issue.type}.</p><p>URL: ${issue.url} </p><p>Remediation: ${issue.remediation}</p>"
    }.join('\n')
}.call([[severity: 'High', type: 'Cross-Site Scripting', url: 'https://payments.bbh.com/login', remediation: 'Encode every value that the page writes back.'],
        [severity: 'Medium', type: 'Missing Content-Security-Policy header', url: 'https://payments.bbh.com/', remediation: 'Send a Content-Security-Policy header.']])
Map dastCounts = [critical: 0, high: 1, medium: 1, low: 0]
Closure dastWorld = { Map options = [:] ->
    Map w = world([sonar: [], files: ['appscan-dast-report-payments.html': dastHtml]] + options)
    w.state.projectsScanResults.payments = [dast: 'FAIL', dast_file: 'appscan-dast-report-payments.html']
    w.state.projectsVulnCounts.payments = [dast: dastCounts]
    return w
}
List<Map> dastExpected = [[tool: 'AppScan DAST', severity: 'HIGH', file: '', line: null, title: 'Cross-Site Scripting at https://payments.bbh.com/login',
                           description: 'AppScan DAST detected vulnerability during dynamic runtime analysis.', remediation: 'Encode every value that the page writes back.'],
                          [tool: 'AppScan DAST', severity: 'MEDIUM', file: '', line: null, title: 'Missing Content-Security-Policy header at https://payments.bbh.com/',
                           description: 'AppScan DAST detected vulnerability during dynamic runtime analysis.', remediation: 'Send a Content-Security-Policy header.']]

Map dastComment = dastWorld()
Map dastOpen = opened(dastComment, 'security-dast-payments')
publishDast(dastComment)
Map dastCommentExpected = [service        : 'payments', kind: 'DAST', repositoryUrl: repository, pullRequestId: dastOpen.id, pullRequestUrl: dastOpen.url,
                           title          : '[Security] DAST findings - payments', sourceBranch: 'security-dast-payments', targetBranch: 'develop', created: false,
                           lineComments   : 0, generalComments: 1, limited: false, maxComments: 25, findings: dastExpected, findingsTotal: 2,
                           summary        : dastComment.publisher.comments ? dastComment.publisher.comments[0].text : null]
check('DAST on an open pull request: the comment with the DAST report is one decoration, recorded with the report as its summary and the DAST findings of the report',
        !dastComment.error && dastComment.publisher.comments.size() == 1 && dastComment.recorder.decorations.size() == 1 && dastComment.recorder.decorations[0] == dastCommentExpected
                && (dastCommentExpected.summary as String).startsWith('# HCL AppScan DAST Dynamic Security Report'),
        dastComment.error ?: dastComment.recorder.decorations.collect { it.findAll { k, v -> dastCommentExpected[k] != v } })

Map dastCreated = dastWorld()
publishDast(dastCreated)
Map dastNew = dastCreated.publisher.openPullRequests['security-dast-payments'] as Map
check('DAST without an open pull request: the pull request the library creates with the report as its description is one decoration, recorded as created without comments',
        !dastCreated.error && dastNew != null && dastCreated.recorder.decorations.size() == 1
                && dastCreated.recorder.decorations[0] == dastCommentExpected + [pullRequestId: dastNew.id, pullRequestUrl: dastNew.url, created: true, generalComments: 0,
                                                                                summary: dastCreated.publisher.created[0].description],
        dastCreated.error ?: dastCreated.recorder.decorations)

Map dastConflict = dastWorld()
dastConflict.publisher.answerExisting = true
publishDast(dastConflict)
check('DAST: when Bitbucket answers that security-dast-<service> exists already, no comment was added and no pull request created - nothing is recorded',
        !dastConflict.error && dastConflict.publisher.created.size() == 1 && dastConflict.recorder.decorations.isEmpty(), dastConflict.error ?: dastConflict.recorder.decorations)

Map dastSteps = world([sonar: [], services: [gui: serviceCfg(), 'backend-api': serviceCfg()], files: ['appscan-dast-report-gui.html': dastHtml]])
opened(dastSteps, 'security-dast-gui')
FakeCpsScript dastVars = dastSteps.h.loadVars(varsDir, dastSteps.s)['devSecOpsSteps'] as FakeCpsScript
def dastApi = dastSteps.h.type('com.bbh.fixture.DecorationStepsApi').newInstance()
dastApi.service = dastSteps.service
dastApi.state = dastSteps.state
dastApi.dastCounts = [gui: dastCounts, 'backend-api': [critical: 1, high: 0, medium: 0, low: 0]]
attempt(dastSteps) { dastVars.dast(dastApi, [:]) }
List<Map> dastStepsRecorded = dastSteps.recorder.decorations as List<Map>
check('devSecOpsSteps.dast over two services: every DAST comment and every DAST pull request the library opens is recorded once - the step of the second service comments the report again on the open pull request of the first, as it did before, and that comment is a decoration too; the report of a service without a report file is the placeholder finding of the target',
        !dastSteps.error && dastStepsRecorded.size() == dastSteps.publisher.comments.size() + dastSteps.publisher.created.size()
                && dastStepsRecorded.collect { [it.service, it.created, it.generalComments] } == [['gui', false, 1], ['gui', false, 1], ['backend-api', true, 0]]
                && dastApi.calls == ['runStage DAST - Dynamic Application Security Tests - HCL AppScan', 'dastScan gui', 'publishDastPullRequest gui', 'dastScan backend-api', 'publishDastPullRequest backend-api']
                && dastStepsRecorded.findAll { it.service == 'gui' }.every { it.findingsTotal == 2 }
                && dastStepsRecorded.find { it.service == 'backend-api' }?.findings == [[tool: 'AppScan DAST', severity: 'HIGH', file: '', line: null, title: 'DAST Security Finding',
                                                                                        description: 'Dynamic scan discovered security findings on target application.',
                                                                                        remediation: 'Inspect HTTP response headers, configure CORS, CSRF tokens, and ensure secure cookies.']],
        dastSteps.error ?: dastStepsRecorded.collect { [it.service, it.created, it.generalComments, it.findingsTotal] })

String longRemediation = 'Validate the redirect target against a list of allowed hosts. ' * 130
Map dastLong = world([sonar: [], files: ['appscan-dast-report-payments.html': { ->
    (1..3).collect { int i -> "<p>Issue ID: l-${i}</p><p>Severity: High</p><p>Issue Type: Open Redirect ${i}.</p><p>URL: https://payments.bbh.com/r${i} </p><p>Remediation: ${longRemediation}</p>" }.join('\n')
}.call()]])
dastLong.state.projectsScanResults.payments = [dast: 'FAIL', dast_file: 'appscan-dast-report-payments.html']
dastLong.state.projectsVulnCounts.payments = [dast: [critical: 0, high: 3, medium: 0, low: 0]]
publishDast(dastLong)
Map longDecoration = dastLong.recorder.decorations ? dastLong.recorder.decorations[0] as Map : [:]
String fullReport = dastLong.publisher.created ? dastLong.publisher.created[0].description as String : ''
check('cuts: a DAST report of more than 20000 characters is the full description of the pull request and a summary of 20000 characters ending with ... in the record; a remediation is cut to 4000 characters the same way',
        !dastLong.error && fullReport.length() > 20000 && longDecoration.summary == fullReport.substring(0, 19997) + '...'
                && (longDecoration.findings as List).every { (it.remediation as String).length() == 4000 && (it.remediation as String).endsWith('...') }
                && (longDecoration.findings as List)[0].remediation == longRemediation.trim().substring(0, 3997) + '...',
        dastLong.error ?: [fullReport.length(), (longDecoration.summary as String)?.length()])

String longTitle = 'Login with a one-time code ' * 25
String longType = 'Injection ' * 60
String longDescription = 'Found by HCL AppScan in src/main/java/A.java:1. ' * 100
List<Map> many = (1..250).collect { int i -> sast("m-${i}", 'src/main/java/A.java', i, 'HIGH', i == 1 ? longType.trim() : "Finding ${i}", i == 1 ? 'Use a PreparedStatement.' : '') }
many[0].description = longDescription
Map capped = world([env: changeEnv + [CHANGE_TITLE: longTitle], sonar: [], services: [payments: serviceCfg([pullRequestDecoration: [enabled: true, maxComments: 30]])]])
staticFindings(capped, 'payments', many)
publishStatic(capped)
List<Map> cappedRecorded = capped.recorder.decorations as List<Map>
Map cappedBranch = cappedRecorded ? cappedRecorded[0] : [:]
check('the cap: of 250 findings the record holds the first 200 in their order and findingsTotal 250; pullRequestDecoration.maxComments 30 stopped the comments, so the record says limited with maxComments 30',
        !capped.error && cappedRecorded.size() == 2 && cappedRecorded.every { (it.findings as List).size() == 200 && it.findingsTotal == 250 && it.limited == true && it.maxComments == 30 }
                && (cappedBranch.findings as List).collect { it.line } == (1..200).toList()
                && cappedBranch.lineComments == 30 && cappedBranch.generalComments == 1 && cappedRecorded[1].lineComments == 30 && cappedRecorded[1].generalComments == 0
                && securityLines(capped).contains('[SECURITY-PR] Further findings are listed in the report only (pullRequestDecoration.maxComments: 30)'),
        capped.error ?: cappedRecorded.collect { [it.kind, (it.findings as List).size(), it.findingsTotal, it.limited, it.maxComments, it.lineComments, it.generalComments] })
check('cuts: a title of the pull request is cut to 500 characters, a finding title to 500 and a description to 4000, each ending with ... - texts are cut, never refused',
        !capped.error && cappedBranch.title == longTitle.substring(0, 497) + '...' && (cappedBranch.title as String).length() == 500
                && (cappedBranch.findings as List)[0].title == longType.trim().substring(0, 497) + '...'
                && (cappedBranch.findings as List)[0].description == longDescription.trim().substring(0, 3997) + '...'
                && (cappedBranch.findings as List)[1].title == 'Finding 2',
        capped.error ?: [(cappedBranch.title as String)?.length(), ((cappedBranch.findings as List)?.getAt(0)?.title as String)?.length()])

Map addresses = [:]
SandboxHarness unitHarness = new SandboxHarness(srcDir, stubDir)
unitHarness.run {
    def decoration = unitHarness.type('com.bbh.remediation.model.PullRequestDecoration')
    [['https://deploy:s3cret@bitbucket.bbh.com/projects/PAY/repos/payments?at=refs%2Fheads%2Fmain#readme', null],
     ['https://deploy:p@ss:w0rd@bitbucket.bbh.com/scm/pay/payments.git', null],
     ['https://deploy:pa?ss#wd@bitbucket.bbh.com/scm/pay/payments.git', null],
     ['', 'ssh://git@bitbucket.bbh.com:7999/pay/payments.git'],
     ['  ', 'git@bitbucket.bbh.com:pay/payments.git'],
     [null, 'https://token@bitbucket.org/bbh/payments.git?private=1'],
     ['https://bitbucket.bbh.com/projects/PAY/repos/payments', 'https://deploy:s3cret@bitbucket.bbh.com/scm/pay/payments.git'],
     [null, null]].each { List pair -> addresses[pair] = decoration.repositoryUrl([url: pair[0], cloneUrl: pair[1]]) }
}
check('repositoryUrl: scm.bitbucket.url, else cloneUrl, without user name, password, query and fragment - also when the password holds @, ? or #',
        addresses.values().toList() == ['https://bitbucket.bbh.com/projects/PAY/repos/payments', 'https://bitbucket.bbh.com/scm/pay/payments.git',
                                         'https://bitbucket.bbh.com/scm/pay/payments.git', 'ssh://bitbucket.bbh.com:7999/pay/payments.git',
                                         'bitbucket.bbh.com:pay/payments.git', 'https://bitbucket.org/bbh/payments.git',
                                         'https://bitbucket.bbh.com/projects/PAY/repos/payments', ''],
        addresses)
Map secret = dastWorld([services: [payments: serviceCfg([scm: [bitbucket: [url: 'https://deploy:s3cret@bitbucket.bbh.com/projects/PAY/repos/payments?at=refs%2Fheads%2Fmain#readme', credentialsId: 'bb']]])]])
opened(secret, 'security-dast-payments')
publishDast(secret)
check('repositoryUrl in a record: the user name and the password of scm.bitbucket.url are not part of the decoration',
        !secret.error && secret.recorder.decorations.size() == 1 && secret.recorder.decorations[0].repositoryUrl == repository
                && !JsonOutput.toJson(secret.recorder.decorations).contains('s3cret'),
        secret.error ?: secret.recorder.decorations*.repositoryUrl)

Map refused = world([env: changeEnv, sonar: sonarIssues])
refused.recorder.failure = 'OneView is down'
staticFindings(refused, 'payments', sastFindings)
refused.state.projectsGoldenFix = [payments: goldenFix + [:]]
publishStatic(refused)
check('a recorder that fails: one [SECURITY-PR] line per decoration names the pull request and the reason, the decoration goes on with the next pull request, the comments are those of a run without failure and the result of the build stays as it was',
        !refused.error && refused.recorder.decorations.size() == 2 && posted(refused) == posted(first) && refused.s.currentBuild.result == null
                && securityLines(refused).findAll { it.contains('was not recorded') } == ["[SECURITY-PR] The decoration of pull request ${pullRequests}/77 was not recorded: OneView is down".toString(),
                                                                                          "[SECURITY-PR] The decoration of pull request ${pullRequests}/76 was not recorded: OneView is down".toString()]
                && !securityLines(refused).any { it.contains('skipped') },
        refused.error ?: securityLines(refused))

Map aborted = world([env: changeEnv, sonar: sonarIssues])
aborted.recorder.abort = true
staticFindings(aborted, 'payments', sastFindings)
aborted.state.projectsGoldenFix = [payments: goldenFix + [:]]
publishStatic(aborted)
Map abortedDast = dastWorld()
abortedDast.recorder.abort = true
opened(abortedDast, 'security-dast-payments')
publishDast(abortedDast)
check('an abort of the build while a decoration is recorded is rethrown: the static decoration stops after the pull request of the analysed branch, the DAST step stops too, and neither prints a line about it',
        aborted.error instanceof FakeFlowInterruptedException && aborted.recorder.decorations.size() == 1 && addedTo(aborted, '76') == [lines: 0, comments: 0]
                && !securityLines(aborted).any { it.contains('was not recorded') || it.contains('skipped') }
                && abortedDast.error instanceof FakeFlowInterruptedException && !securityLines(abortedDast).any { it.contains('was not recorded') || it.contains('skipped') },
        [aborted.error, abortedDast.error, securityLines(aborted), securityLines(abortedDast)])

Closure allowed = null
allowed = { def value, boolean inFinding ->
    if (value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Boolean) return true
    if (value == null) return inFinding
    if (value instanceof List) return (value as List).every { allowed(it, inFinding) }
    if (value instanceof Map) return (value as Map).every { k, v -> k instanceof String && allowed(v, inFinding || k == 'line') }
    return false
}
List<String> keys = ['service', 'kind', 'repositoryUrl', 'pullRequestId', 'pullRequestUrl', 'title', 'sourceBranch', 'targetBranch', 'created',
                     'lineComments', 'generalComments', 'limited', 'maxComments', 'findings', 'findingsTotal', 'summary']
List<Map> badly = everyDecoration.findAll { Map d ->
    d.keySet().toList() != keys || !allowed(d, false) || !(d.findings as List).every { (it as Map).keySet().toList() == ['tool', 'severity', 'file', 'line', 'title', 'description', 'remediation'] } ||
            new JsonSlurperClassic().parseText(JsonOutput.toJson(d)) != d
}
check("every record of these scenarios (${everyDecoration.size()}) has the same keys in the same order, only String, Integer, Boolean, List and Map values (a finding without a line has line null), and reads back unchanged from JSON",
        everyDecoration.size() >= 20 && badly.isEmpty(), badly.collect { it.findAll { k, v -> k != 'findings' && k != 'summary' } })

println "\n${failures == 0 ? 'ALL PULL REQUEST DECORATION SCENARIOS PASSED' : failures + ' PULL REQUEST DECORATION SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
