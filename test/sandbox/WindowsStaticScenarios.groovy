import devsecops.test.FakeAbort
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import devsecops.test.ScenarioRunner

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File oneViewFixture = new File(root, 'test/fixtures/oneview/certscanner-gui-full.json')
boolean withOneView = oneViewFixture.isFile()
SandboxHarness harness = new SandboxHarness(new File(root, 'src'), new File(root, 'test/sandbox/stub'))
Closure utils = { -> harness.type('com.bbh.utils.BuildUtils') }

String UNIT = 'Unit tests'
String NIQ = 'Dependencies scan (Nexus IQ)'
String SAST = 'SAST - Static Application Security Tests - HCL AppScan'
String SONAR = 'SCA (SonarQube)'
String STATIC = 'Static analysis'
String DYNAMIC = 'Dynamic analysis'
String REGRESSION = 'Regression tests (>60% user stories coverage)'
String SMOKE = 'Smoke tests'
String PERFORMANCE = 'Performance tests'
String DAST = 'DAST - Dynamic Application Security Tests - HCL AppScan'

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

Closure issuesHtml = { List counts ->
    StringBuilder sb = new StringBuilder('<html><body>')
    int id = 1
    ['Critical', 'High', 'Medium', 'Low'].eachWithIndex { String severity, int i ->
        (counts[i] as int).times { sb.append("<div>Issue ID: ${id++}</div><div>Severity: ${severity}</div><div>Status: Open</div>") }
    }
    sb.append('</body></html>').toString()
}
Closure dataFor = { boolean fail ->
    ['gui'        : [coverage: [82.4, 824, 176], niq: [0, 0, 0], sast: [0, 0, 0, 5], sonar: [0, 0, 0, 1, 'OK'], dast: [0, 0, 0, 2]],
     'backend-api': [coverage: [84.0, 840, 160], niq: [0, 0, 0], sast: [0, 0, 0, 2], sonar: [0, 0, 0, 0, 'OK'], dast: [0, 0, 0, 1]]]
}
List<String> secretResources = ['a2a-certs/a2a.sh', 'a2a-certs/get-a2a-password.sh', 'a2a-certs/loginfile.sh',
                                 'a2a-certs/PROXY_ASOCJenk.cert.pem', 'a2a-certs/PROXY_ASOCJenk.key.pem', 'a2a-certs/PROXY_ASOCJenk.keypsw',
                                 'a2a-certs/PROXY_ASOCJenk.exppsw', 'a2a-certs/PROXY_ASOCJenk.p12.b64', 'a2a-certs/PROXY_ASOCJenk.thumb',
                                 'ps-modules/safeguard-ps.zip.b64']
Map context = [root           : root, defaultsText: new File(root, 'test/fixtures/defaults.yaml').text,
               configText     : new File(root, 'test/fixtures/CertScanner/config.yaml').text, securityJob: 'DevSecOps/CertScanner-security-pipeline',
               dataFor        : dataFor, issuesHtml: issuesHtml, secretResources: secretResources, archive: { Map projects, Map s -> },
               platform       : withOneView ? (new groovy.json.JsonSlurperClassic().parseText(oneViewFixture.text) as Map).platform as Map : [:],
               variants       : [devSecOpsPipeline: 'full', devSecOpsSecurityPipeline: 'security', devSecOpsExtendedPipeline: 'extended']]

String TMP = 'C:\\Jenkins\\workspace\\CertScanner@tmp'
String COPY = TMP + '\\dso-sast'
String copyLabel = 'Copy the workspace for the SAST compile and scan'
String removeLabel = 'Remove the SAST copy of the workspace'
String stagingLabel = 'Remove the SAST staging folder'
String defaultLine = '[STATIC] Unit tests, Nexus IQ, SAST and SonarQube run at the same time (staticAnalysis.parallel: true, the default)'
String copyLine = "[STATIC] SAST compiles and prepares its scans in a copy of the workspace (${COPY}) at the same time as the other stages; on this Windows agent Unit tests, Nexus IQ and SonarQube take turns on the workspace, as a build there locks and rewrites the files Nexus IQ scans".toString()
String oldLine = '[STATIC] Windows agent: Unit tests, Nexus IQ, SAST and SonarQube run one after another, as the SAST staging of Windows uses the workspace'
String orderLine = '[STATIC] SAST queues the scan of every service first; Unit tests, Nexus IQ and SonarQube then run one after another while AppScan scans, and SAST reads the results last'
String groupLine = "[PARALLEL] ${STATIC}: ${UNIT}, ${NIQ}, ${SAST}, ${SONAR} run at the same time".toString()
String sequentialLine = '[STATIC] DSO_STATIC_ANALYSIS_PARALLEL=false: Unit tests, Nexus IQ, SAST and SonarQube run one after another'
String copyEnv = "withEnv [WORKSPACE=${COPY}, DSO_WORKSPACE=/ws, MSBUILDDISABLENODEREUSE=1, UseSharedCompilation=false]".toString()
Closure warnLine = { String reason -> "[WARN] [STATIC] The workspace could not be copied for SAST (${reason}) - falling back: SAST queues its scans first and Unit tests, Nexus IQ and SonarQube run one after another while AppScan scans".toString() }

Closure project = { FakeScript j -> (j.env.CURRENT_PROJECT_NAME ?: 'gui') as String }
Closure relative = { String path -> path.startsWith('/ws/') ? path.substring(4) : path }
Closure runWindows = { Map spec ->
    Closure extra = spec.respondAlso as Closure
    Map full = [var: 'devSecOpsSecurityPipeline', job: 'CertScanner-security-pipeline', buildNumber: 400, parallel: true, keyless: true, files: ['devsecops_config.yaml': null]] + spec
    full.prepare = { FakeScript j ->
        j.unix = false
        if (spec.oneViewAgent) j.steps['isUnix'] = { a -> j.env.NODE_NAME == spec.oneViewAgent }
        j.agentTmp = spec.containsKey('agentTmp') ? spec.agentTmp as String : TMP
        if (spec.order) {
            j.interleave = true
            j.parallelOrder = spec.order as List
            j.waitSleepMs = (spec.waitSleepMs ?: 1100L) as long
        }
    }
    full.respond = { FakeScript j, Map a ->
        String text = String.valueOf(a.script ?: '')
        String label = String.valueOf(a.label ?: '')
        List answer = extra ? extra.call(j, a) as List : null
        if (answer != null) return answer
        if (text.contains('[xml]$doc = Get-Content')) return [project(j) == 'backend-api' ? 'jacoco,84.0,840,160' : 'jacoco,82.4,824,176']
        if (text.contains('$hit = Get-ChildItem')) return ['0']
        def move = text =~ /Move-Item '([^']+)' '([^']+)'/
        if (move.find() && j.files.containsKey(relative(move.group(1)))) j.files[relative(move.group(2))] = j.files.remove(relative(move.group(1)))
        return null
    }
    Map r = ScenarioRunner.runPipeline(full, context)
    FakeScript j = r.j as FakeScript
    r.log = j.log
    r.calls = j.calls
    r.staticLog = j.log.findAll { it.startsWith('[STATIC]') || it.startsWith('[WARN] [STATIC]') || it.startsWith('[INFO] [STATIC]') || it.startsWith("[PARALLEL] ${STATIC}") }
    r.executed = r.var.executedStages
    r.stageFailures = r.var.stageFailures
    return r
}
Closure indexOf = { List<String> lines, Closure match -> lines.findIndexOf(match) }
Closure stageResult = { Map r, String stage ->
    List<String> log = r.log as List<String>
    int at = log.indexOf("[STAGE]  ${stage}".toString())
    at < 0 ? '' : log[at + 1]
}
List<String> securityStages = ['Monitor source changes (download sources)', 'Build artifact', STATIC, 'Nexus delivery (Static analysis passed)']

Map byDefault = runWindows([:])
List<String> defaultCalls = byDefault.calls as List<String>
int copyAt = indexOf(defaultCalls) { String c -> c.startsWith("powershell [${copyLabel}] ".toString()) }
int groupAt = defaultCalls.indexOf('parallel 4')
int copyEnvAt = defaultCalls.indexOf(copyEnv)
int removeAt = indexOf(defaultCalls) { String c -> c.startsWith("powershell [${removeLabel}] ".toString()) }
List prepared = defaultCalls.findIndexValues { it.contains('& ') && it.contains(' prepare -s thorough') }
List queued = defaultCalls.findIndexValues { it.contains('/api/v4/FileUpload') || it.endsWith('/api/v4/Scans/Sast"') }
check('Windows agent, by default: the static analysis runs in parallel as on Linux, SAST prepares the IRX of both services, uploads them and queues their scans in a robocopy copy outside the workspace, and the copy is removed once, after the last upload and queued scan',
        byDefault.crash == '' && byDefault.executed == securityStages && byDefault.stageFailures == []
                && byDefault.staticLog == [defaultLine, copyLine, groupLine] + ((byDefault.log as List<String>).findAll { it.startsWith("[PARALLEL] ${STATIC}: one after another ") })
                && !(byDefault.log as List<String>).contains(oldLine)
                && copyAt > 0 && defaultCalls[copyAt - 1] == "withEnv [DSO_SAST_WORKSPACE=${COPY}]".toString()
                && defaultCalls[copyAt] == "powershell [${copyLabel}] ${harness.run { utils().windowsSastCopyScript() }}".toString()
                && groupAt > copyAt && copyEnvAt > groupAt && removeAt > copyEnvAt
                && defaultCalls[removeAt - 1] == "withEnv [DSO_SAST_WORKSPACE=${COPY}]".toString()
                && defaultCalls[removeAt] == "powershell [${removeLabel}] ${harness.run { utils().windowsCopyRemovalScript() }}".toString()
                && prepared.size() == 2 && prepared.every { it > copyEnvAt && it < removeAt }
                && queued.size() == 4 && queued.every { it > copyEnvAt && it < removeAt }
                && defaultCalls.count { it.startsWith("powershell [${removeLabel}] ".toString()) } == 1
                && defaultCalls.findAll { it.contains('/api/v4/FileUpload') }.every { it.contains("fileToUpload=@${COPY}/certscanner-".toString()) }
                && defaultCalls.count { it.contains('/api/v4/FileUpload') } == 2
                && !defaultCalls.any { it.startsWith("sh [${copyLabel}]".toString()) || it.contains('tar -C "$WORKSPACE"') || it.contains('dso-sast-workspace') }
                && !defaultCalls.any { it.contains('build-server shutdown') }
                && !defaultCalls.any { it.startsWith("powershell [${stagingLabel}]".toString()) }
                && !(byDefault.log as List<String>).any { it.contains('Windows agent:') }
                && (byDefault.log as List<String>).findAll { it.startsWith('[RESULT] ') }.every { it in ['[RESULT] PASS', '[RESULT] NOT_REQUIRED'] },
        [crash: byDefault.crash, failures: byDefault.stageFailures, staticLog: byDefault.staticLog, copyAt: copyAt, groupAt: groupAt, copyEnvAt: copyEnvAt, removeAt: removeAt,
         prepared: prepared, queued: queued])

Map noTmp = runWindows([agentTmp: null])
check('Windows agent without WORKSPACE_TMP: the copy is <WORKSPACE>@tmp\\dso-sast, written with backslashes',
        (noTmp.log as List<String>).contains(copyLine.replace(COPY, '\\ws@tmp\\dso-sast'))
                && (noTmp.calls as List<String>).contains('withEnv [DSO_SAST_WORKSPACE=\\ws@tmp\\dso-sast]'),
        noTmp.staticLog)

Closure dotnetRun = { int buildNumber, Map buildEnv, Map asocEnv ->
    List<Map> steps = []
    Map r = runWindows([buildNumber: buildNumber, files: ['devsecops_config.yaml': null, 'Orders.sln': 'Microsoft Visual Studio Solution File'],
                        configure  : { Map projects ->
                            Map gui = projects.gui as Map
                            gui.buildTool = 'dotnet'
                            gui.build = [dotnet: [solution: 'Orders.sln', mode: 'dotnet'] + (buildEnv ? [env: buildEnv] : [:])]
                            gui.tests = [unitTests: [dotnet: [mode: 'dotnet']]]
                            (gui.asoc as Map).doCompile = true
                            if (asocEnv) (gui.asoc as Map).dotnet = [env: asocEnv]
                        },
                        respondAlso: { FakeScript j, Map a ->
                            String text = String.valueOf(a.script ?: '')
                            if (text.startsWith('dotnet.exe ')) {
                                steps << [branch : (j.env.DSO_PARALLEL_BRANCH ?: '') as String, command: text.tokenize(' ')[1], at: j.calls.size() - 1, folder: j.cwd,
                                          workspace: j.env.WORKSPACE, nodeReuse: j.env.MSBUILDDISABLENODEREUSE, sharedCompilation: j.env.UseSharedCompilation]
                            }
                            null
                        }])
    r.steps = steps
    return r
}
Map dotnet = dotnetRun(470, null, null)
List<String> dotnetCalls = dotnet.calls as List<String>
int dotnetCopyEnvAt = dotnetCalls.indexOf(copyEnv)
int dotnetRemoveAt = indexOf(dotnetCalls) { String c -> c.startsWith("powershell [${removeLabel}] ".toString()) }
List<Map> sastSteps = (dotnet.steps as List<Map>).findAll { it.branch == SAST }
List<Map> workspaceSteps = (dotnet.steps as List<Map>).findAll { it.branch != SAST }
check('Windows agent, a .NET service: the SAST compile (dotnet.exe restore and build) runs in the copy, as its current folder and as WORKSPACE, with MSBUILDDISABLENODEREUSE=1 and UseSharedCompilation=false, after the copy is made and before it is removed; the .NET steps of Build artifact, Unit tests and SonarQube run in the workspace without the two variables',
        dotnet.crash == '' && dotnet.stageFailures == [] && dotnetCopyEnvAt > 0 && dotnetRemoveAt > dotnetCopyEnvAt
                && sastSteps.collect { [it.command, it.folder, it.workspace, it.nodeReuse, it.sharedCompilation] } == [['restore', COPY, COPY, '1', 'false'], ['build', COPY, COPY, '1', 'false']]
                && sastSteps.every { (it.at as int) > dotnetCopyEnvAt && (it.at as int) < dotnetRemoveAt }
                && workspaceSteps.collect { it.branch }.unique() == ['', UNIT, SONAR]
                && workspaceSteps.every { it.folder == '' && it.workspace == '/ws' && it.nodeReuse == null && it.sharedCompilation == null },
        [crash: dotnet.crash, failures: dotnet.stageFailures, copyEnvAt: dotnetCopyEnvAt, removeAt: dotnetRemoveAt, steps: dotnet.steps])

Map dotnetEnv = dotnetRun(471, [UseSharedCompilation: 'true'], [MSBUILDDISABLENODEREUSE: '0'])
List<Map> envBuilds = (dotnetEnv.steps as List<Map>).findAll { it.branch == SAST && it.command == 'build' }
check('Windows agent, a .NET service with build.dotnet.env and asoc.dotnet.env: they still win over the two variables of the copy (UseSharedCompilation of build.dotnet.env, MSBUILDDISABLENODEREUSE of asoc.dotnet.env), and the SAST compile still runs in the copy',
        dotnetEnv.crash == '' && dotnetEnv.stageFailures == []
                && envBuilds.collect { [it.folder, it.workspace, it.nodeReuse, it.sharedCompilation] } == [[COPY, COPY, '0', 'true']],
        [crash: dotnetEnv.crash, failures: dotnetEnv.stageFailures, steps: dotnetEnv.steps])

String dsoFunction = '''function Remove-DsoFolder([string]$dsoFolder) {
    if (-not (Test-Path -LiteralPath $dsoFolder)) { return $true }
    $dsoEmpty = $dsoFolder + '.empty'
    New-Item -ItemType Directory -Force -Path $dsoEmpty | Out-Null
    robocopy $dsoEmpty $dsoFolder /MIR /R:2 /W:2 /NFL /NDL /NJH /NJS /NP | Out-Null
    Remove-Item -LiteralPath $dsoFolder -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $dsoEmpty -Recurse -Force -ErrorAction SilentlyContinue
    return (-not (Test-Path -LiteralPath $dsoFolder))
}
'''
String copyScript = harness.run { utils().windowsSastCopyScript() } as String
String removalScript = harness.run { utils().windowsCopyRemovalScript() } as String
List<String> excluded = ['.appscan-tools', '.appscan-logs', '.appscan-home', '.appscan-bin', 'appscan-source-staged', 'sast_directory', '.sonarqube', '.scannerwork']
Closure ps51 = { String text ->
    List<String> code = text.readLines().collect { String line -> line.replaceAll(/"[^"]*"|'[^']*'/, '') }
    !code.any { String line -> line.contains('&&') || line.contains('||') || line.contains('??') || line =~ /\s\?\s/ || line =~ /\?\./ }
}
check('the copy script: it removes the copy of an earlier build and a sast_directory left in the workspace with Remove-DsoFolder (robocopy /MIR from an empty folder, Remove-Item of the folder and of the empty folder, true only when the folder is gone), the same function as the removal script, turns the slashes of WORKSPACE into backslashes, walks the workspace without following a junction, checks the 259 characters of a path in the copy, copies with robocopy and ends with an explicit exit code',
        copyScript.startsWith("\$ErrorActionPreference = 'Stop'\n" + dsoFunction + "try {\n")
                && removalScript.startsWith("\$ErrorActionPreference = 'Stop'\n" + dsoFunction + "try {\n")
                && copyScript.contains("    \$source = \$env:WORKSPACE.Replace('/', '\\').TrimEnd('\\')\n    \$target = \$env:DSO_SAST_WORKSPACE.TrimEnd('\\')\n")
                && copyScript.contains("    \$excluded = @(${excluded.collect { "'${it}'" }.join(', ')})\n".toString())
                && copyScript.contains("    if (-not (Remove-DsoFolder \$target)) {\n        Write-Host \"[STATIC] The copy of an earlier build is still in \$target\"\n        exit 4\n    }\n")
                && copyScript.contains("    \$null = Remove-DsoFolder (Join-Path \$source 'sast_directory')\n")
                && copyScript.contains("\$root = '\\\\?\\' + \$source") && copyScript.contains("\$root = '\\\\?\\UNC\\' + \$source.Substring(2)")
                && copyScript.contains('[System.IO.FileAttributes]::ReparsePoint') && copyScript.contains("is a junction or symbolic link\"\n                exit 3\n")
                && copyScript.contains("    if (\$longest -gt 259) {\n") && copyScript.contains("characters long in the copy, and Windows allows 259\"\n        exit 2\n")
                && copyScript.contains("    robocopy \$source \$target /E /XJ /A-:R /R:2 /W:2 /MT:16 /NFL /NDL /NJH /NP /NS /XD ${excluded.join(' ')}\n".toString())
                && copyScript.contains("    \$copied = \$LASTEXITCODE\n    if (\$copied -ge 8) {\n        Write-Host \"[STATIC] robocopy ended with exit code \$copied\"\n        exit 1\n    }\n    exit 0\n")
                && copyScript.endsWith("} catch {\n    Write-Host \"[STATIC] \$(\$_.Exception.Message)\"\n    exit 5\n}\n")
                && removalScript.contains("    if (Remove-DsoFolder \$env:DSO_SAST_WORKSPACE) { exit 0 }\n    exit 1\n")
                && removalScript.endsWith("    exit 1\n}\n") && !copyScript.contains('build-server') && !removalScript.contains('build-server')
                && ps51(copyScript) && ps51(removalScript) && (copyScript + removalScript) ==~ /[\x00-\x7F]*/,
        copyScript)

Map reasons = harness.run { [1, 2, 3, 4, 5, 9].collectEntries { int code -> [(code): utils().windowsCopyFailure(code)] } } as Map
check('BuildUtils.windowsCopyFailure: each exit code of the copy script names its reason, an unknown one its number',
        reasons == [1: 'robocopy could not copy every file', 2: 'a path would be longer in the copy than the 259 characters Windows allows',
                    3: 'the workspace holds a junction or symbolic link', 4: 'the copy of an earlier build could not be removed', 5: 'PowerShell failed',
                    9: 'the copy ended with exit code 9'], reasons)

Map fallbacks = [:]
[1, 2, 3, 4, 5].each { int code ->
    Map r = runWindows([buildNumber: 410 + code, respondAlso: { FakeScript j, Map a -> a.label == copyLabel ? [code] : null }])
    fallbacks[code] = r
}
fallbacks[0] = runWindows([buildNumber: 416, respondAlso: { FakeScript j, Map a -> if (a.label == copyLabel) throw new FakeAbort('powershell.exe is not on the PATH'); null }])
List wrongFallbacks = fallbacks.findAll { def code, Map r ->
    String reason = code == 0 ? 'powershell.exe is not on the PATH' : reasons[code] as String
    List<String> calls = r.calls as List<String>
    int staging = indexOf(calls) { String c -> c.startsWith("powershell [${stagingLabel}] ".toString()) }
    int firstNiq = (r.evaluatedAt as List) ? (r.evaluatedAt as List)[0] as int : -1
    return !(r.crash == '' && r.executed == securityStages && r.stageFailures == []
            && r.staticLog == [defaultLine, warnLine(reason), orderLine]
            && !calls.contains('parallel 4') && !calls.any { it.startsWith('withEnv [WORKSPACE=') || it.startsWith("powershell [${removeLabel}]".toString()) }
            && staging > 0 && firstNiq > staging
            && stageResult(r, SAST) == '[RESULT] PASS' && stageResult(r, UNIT) == '[RESULT] PASS' && stageResult(r, NIQ) == '[RESULT] PASS' && stageResult(r, SONAR) == '[RESULT] PASS')
}.collect { def code, Map r -> [code: code, crash: r.crash, failures: r.stageFailures, staticLog: r.staticLog] }
check('Windows agent, the copy fails with each exit code of the script or the step throws: a [WARN] line names the reason and the static analysis falls back to the queued order, SAST queues first and removes its staging folder, and the build does not fail',
        wrongFallbacks.isEmpty(), wrongFallbacks)

String overrideLine = '[STATIC] gui: the SAST compile runs other Gradle tasks or Maven goals than the build command in the workspace, so it may not rebuild the files Nexus IQ scans: Unit tests, Nexus IQ, SAST and SonarQube run one after another'
Closure otherCompile = { Map projects -> ((projects.gui as Map).asoc as Map).gradle = [tasks: ['clean', 'assemble']] }
Map overrides = runWindows([buildNumber: 420, configure: otherCompile, respondAlso: { FakeScript j, Map a -> a.label == copyLabel ? [1] : null }])
List<String> overrideLog = overrides.log as List<String>
check('Windows agent, a service with asoc compile overrides and a failed copy: the order one after another, as on Linux',
        overrides.crash == '' && overrides.stageFailures == []
                && overrides.staticLog == [defaultLine, warnLine(reasons[1] as String), overrideLine]
                && overrideLog.indexOf("[STAGE]  ${UNIT}".toString()) < overrideLog.indexOf("[STAGE]  ${NIQ}".toString())
                && overrideLog.indexOf("[STAGE]  ${NIQ}".toString()) < overrideLog.indexOf("[STAGE]  ${SAST}".toString())
                && overrideLog.indexOf("[STAGE]  ${SAST}".toString()) < overrideLog.indexOf("[STAGE]  ${SONAR}".toString())
                && !(overrides.calls as List<String>).contains('parallel 4'),
        overrides.staticLog)

Map envOff = runWindows([buildNumber: 421, env: [DSO_STATIC_ANALYSIS_PARALLEL: 'false']])
Map keyOff = runWindows([buildNumber: 422, configure: { Map projects -> (projects['backend-api'] as Map).staticAnalysis = [parallel: false] }])
Map envOn = runWindows([buildNumber: 423, env: [DSO_STATIC_ANALYSIS_PARALLEL: 'true']])
Closure sequentialRun = { Map r ->
    List<String> log = r.log as List<String>
    List<String> calls = r.calls as List<String>
    return (r.crash == '' && r.stageFailures == [] && !calls.contains('parallel 4') && !calls.any { it.contains(copyLabel) || it.startsWith('withEnv [WORKSPACE=') }
            && log.indexOf("[STAGE]  ${UNIT}".toString()) < log.indexOf("[STAGE]  ${NIQ}".toString())
            && log.indexOf("[STAGE]  ${NIQ}".toString()) < log.indexOf("[STAGE]  ${SAST}".toString())
            && log.indexOf("[STAGE]  ${SAST}".toString()) < log.indexOf("[STAGE]  ${SONAR}".toString())
            && !log.any { it.contains('Windows agent:') })
}
check('Windows agent, DSO_STATIC_ANALYSIS_PARALLEL=false or staticAnalysis.parallel: false: the order one after another with the lines of Linux and no turn waits; DSO_STATIC_ANALYSIS_PARALLEL=true is the default again, so the [INFO] line that it can be removed is true on Windows too',
        sequentialRun(envOff) && envOff.staticLog == [sequentialLine]
                && sequentialRun(keyOff) && keyOff.staticLog == ['[STATIC] backend-api: staticAnalysis.parallel: false - Unit tests, Nexus IQ, SAST and SonarQube run one after another']
                && envOn.crash == '' && envOn.stageFailures == []
                && envOn.staticLog.take(3) == ['[INFO] [STATIC] DSO_STATIC_ANALYSIS_PARALLEL=true equals the default staticAnalysis.parallel: true - the variable can be removed',
                                               '[STATIC] Unit tests, Nexus IQ, SAST and SonarQube run at the same time (DSO_STATIC_ANALYSIS_PARALLEL=true)', copyLine]
                && (envOn.calls as List<String>).contains('parallel 4'),
        [envOff.staticLog, keyOff.staticLog, envOn.staticLog])

Map turns = runWindows([buildNumber: 430, order: [SONAR, NIQ]])
List<String> turnLog = turns.log as List<String>
List<String> turnLines = turnLog.findAll { it.contains('Windows agent:') }
int unitDone = turnLog.indexOf("[STAGE]  ${UNIT}".toString())
int niqStarts = indexOf(turnLog) { String l -> l.startsWith("[STATIC] Windows agent: ${NIQ} starts after waiting ".toString()) }
int niqDone = turnLog.indexOf("[STAGE]  ${NIQ}".toString())
int sonarStarts = indexOf(turnLog) { String l -> l.startsWith("[STATIC] Windows agent: ${SONAR} starts after waiting ".toString()) }
int sonarDone = turnLog.indexOf("[STAGE]  ${SONAR}".toString())
String turnHtml = turns.html as String
Closure timesOf = { String line ->
    def m = line =~ /one after another (\d+)s, in parallel (\d+)s/
    m.find() ? [m.group(1) as int, m.group(2) as int] : []
}
Closure waitedSeconds = { String line, String stage ->
    def m = (line ?: '') =~ /\[STATIC\] Windows agent: (.+) starts after waiting (?:(\d+)h (\d+)m|(?:(\d+)m )?(\d+)s)/
    if (!m.matches() || m.group(1) != stage) return -1
    m.group(2) != null ? (m.group(2) as int) * 3600 + (m.group(3) as int) * 60 : ((m.group(4) ?: '0') as int) * 60 + (m.group(5) as int)
}
check('Windows agent, the workspace turns: Nexus IQ waits until Unit tests has ended and SonarQube until Unit tests and Nexus IQ have ended, each names what it waits for and how long it waited (Nexus IQ at least 1 s, SonarQube at least 2 s, in any format of the duration), and SAST waits for nothing',
        turns.crash == '' && turns.executed == securityStages && turns.stageFailures == []
                && turnLines.size() == 4
                && turnLines[0] == "[STATIC] Windows agent: ${SONAR} waits until ${UNIT} and ${NIQ} have ended - one build at a time in the workspace".toString()
                && turnLines[1] == "[STATIC] Windows agent: ${NIQ} waits until ${UNIT} has ended - one build at a time in the workspace".toString()
                && waitedSeconds(turnLines[2], NIQ) >= 1
                && waitedSeconds(turnLines[3], SONAR) >= 2
                && unitDone < niqStarts && niqStarts < niqDone && niqDone < sonarStarts && sonarStarts < sonarDone
                && (turns.j as FakeScript).waitChecks == ["${SONAR} lets ${NIQ} run".toString(), "${NIQ} lets ${UNIT} run".toString()]
                && !turnLog.any { it.contains("Windows agent: ${SAST}".toString()) || it.contains('SonarQube waits until the unit tests') },
        turnLines)
check('Windows agent, the workspace turns in the report: SonarQube, the longest branch, names its wait for Unit tests and Nexus IQ in the Manual Actions vs DevSecOps table, and the time one after another of the Parallel stages line leaves the waits out',
        turnHtml.contains("of waiting for ${UNIT} and ${NIQ}".toString())
                && turnHtml.contains('the longest of the 4 in parallel, with ')
                && timesOf(turnLog.find { it.startsWith("[PARALLEL] ${STATIC}: one after another ".toString()) } ?: '').with { List t -> t.size() == 2 && t[0] < t[1] && t[1] >= 2 },
        [turnHtml.findAll(/the longest of the 4 in parallel[^<]*/), turnLog.find { it.startsWith("[PARALLEL] ${STATIC}: one after another ".toString()) }])

Map unitFirst = runWindows([buildNumber: 432, order: [SONAR, UNIT], waitSleepMs: 10L])
List<String> unitFirstLog = unitFirst.log as List<String>
List<String> unitFirstLines = unitFirstLog.findAll { it.contains('Windows agent:') }
int unitFirstNiqDone = unitFirstLog.indexOf("[STAGE]  ${NIQ}".toString())
int unitFirstSonarStarts = indexOf(unitFirstLog) { String l -> l.startsWith("[STATIC] Windows agent: ${SONAR} starts after waiting ".toString()) }
check('Windows agent, the workspace turns when the unit tests end before Nexus IQ starts: SonarQube, which started first, still waits for Nexus IQ after Unit tests has ended and starts only after Nexus IQ has ended; Nexus IQ does not wait',
        unitFirst.crash == '' && unitFirst.executed == securityStages && unitFirst.stageFailures == []
                && (unitFirst.j as FakeScript).waitChecks == ["${SONAR} lets ${UNIT} run".toString(), "${SONAR} lets ${NIQ} run".toString()]
                && unitFirstLines.size() == 2
                && unitFirstLines[0] == "[STATIC] Windows agent: ${SONAR} waits until ${UNIT} and ${NIQ} have ended - one build at a time in the workspace".toString()
                && waitedSeconds(unitFirstLines[1], SONAR) >= 0
                && unitFirstNiqDone > 0 && unitFirstSonarStarts > unitFirstNiqDone,
        [waitChecks: (unitFirst.j as FakeScript).waitChecks, turnLines: unitFirstLines, niqDone: unitFirstNiqDone, sonarStarts: unitFirstSonarStarts])

Map failedUnit = runWindows([buildNumber: 431, order: [SONAR, NIQ], waitSleepMs: 10L,
                             respondAlso: { FakeScript j, Map a ->
                                 if (j.env.DSO_PARALLEL_BRANCH == UNIT && String.valueOf(a.script).contains('[xml]$doc = Get-Content')) throw new FakeAbort('the coverage report cannot be read')
                                 null
                             }])
check('Windows agent, a failed Unit tests branch counts as ended: Nexus IQ and SonarQube take their turn and run, nothing waits forever',
        failedUnit.crash == '' && (failedUnit.stageFailures as List).size() == 1 && (failedUnit.stageFailures as List)[0].toString().startsWith("${STATIC}: ".toString())
                && stageResult(failedUnit, UNIT) == '[RESULT] FAIL' && stageResult(failedUnit, NIQ) == '[RESULT] PASS' && stageResult(failedUnit, SONAR) == '[RESULT] PASS'
                && stageResult(failedUnit, SAST) == '[RESULT] PASS'
                && (failedUnit.log as List<String>).count { it.contains('Windows agent:') && it.contains('starts after waiting') } == 2
                && !(failedUnit.log as List<String>).any { it.contains('waitUntil never became true') },
        [failedUnit.stageFailures, failedUnit.staticLog])

String removalWarn = "[WARN] [STATIC] The SAST copy of the workspace (${COPY}) could not be removed completely - the next build removes it before it copies the workspace".toString()
Map removalFails = runWindows([buildNumber: 440, respondAlso: { FakeScript j, Map a -> a.label == removeLabel ? [1] : null }])
Map removalThrows = runWindows([buildNumber: 441, respondAlso: { FakeScript j, Map a -> if (a.label == removeLabel) throw new FakeAbort('Access to the path is denied'); null }])
check('Windows agent, the copy cannot be removed: a [WARN] line names the copy, SAST passes and the build does not fail',
        [removalFails, removalThrows].every { Map r ->
            (r.crash == '' && r.executed == securityStages && r.stageFailures == [] && (r.log as List<String>).count { it == removalWarn } == 1
                    && stageResult(r, SAST) == '[RESULT] PASS' && (r.j as FakeScript).currentBuild.result != 'FAILURE')
        },
        [removalFails.staticLog, removalThrows.staticLog, removalFails.stageFailures, removalThrows.stageFailures])

Map relativePath = runWindows([buildNumber: 450, configure: { Map projects -> (projects.gui as Map).appscanPath = '/.appscan-tools/SAClientUtil/bin/appscan.bat' }])
List<String> guiIrx = (relativePath.calls as List<String>).findAll { it.contains(' prepare -s thorough -n "$env:APPSCAN_SCAN_NAME"') }
Map pathOf = harness.run {
    Closure resolve = { Map vars, String configured ->
        FakeScript sc = new FakeScript(unix: false)
        sc.env.vars.putAll(vars)
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        state.cfg = [appscanPath: configured]
        def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
        os.detect()
        harness.type('com.bbh.scanner.AppScanEnvironmentAdapter').newInstance(sc, state, os).windowsAppscanPath()
    }
    [copy    : resolve([WORKSPACE: COPY, DSO_WORKSPACE: 'C:\\Jenkins\\workspace\\CertScanner'], '/.appscan-tools/bin/appscan.bat'),
     plain   : resolve([WORKSPACE: 'C:\\Jenkins\\workspace\\CertScanner'], '.appscan-tools/bin/appscan.bat'),
     absolute: resolve([WORKSPACE: COPY, DSO_WORKSPACE: 'C:\\Jenkins\\workspace\\CertScanner'], 'D:\\SAClientUtil\\bin\\appscan.bat')]
} as Map
check('Windows agent, a relative appscanPath inside the copy is resolved against the workspace (DSO_WORKSPACE), not against the copy, which holds no .appscan-tools; outside the copy against WORKSPACE as before',
        relativePath.crash == '' && relativePath.stageFailures == []
                && guiIrx.size() == 2 && guiIrx[0].contains('& /ws/.appscan-tools/SAClientUtil/bin/appscan.bat prepare') && !guiIrx.any { it.contains('dso-sast/.appscan-tools') }
                && pathOf == [copy    : 'C:/Jenkins/workspace/CertScanner/.appscan-tools/bin/appscan.bat',
                              plain   : 'C:/Jenkins/workspace/CertScanner/.appscan-tools/bin/appscan.bat',
                              absolute: 'D:\\SAClientUtil\\bin\\appscan.bat'],
        [pathOf, guiIrx.collect { it.readLines().find { it.contains(' prepare ') } }])

Closure staging = { Map cfg ->
    FakeScript sc = new FakeScript(unix: false)
    sc.env.vars.putAll([WORKSPACE: 'C:/ws', APPSCAN_LOG_DIR: 'C:/ws/.appscan-logs', APPSCAN_HOME_DIR: 'C:/ws/.appscan-home', APPSCAN_BIN_DIR: 'C:/ws/.appscan-bin',
                        APPSCAN_TOOLS_DIR: 'C:/ws/.appscan-tools', APPSCAN_SCAN_NAME: 'orders-sast', APPSCAN_SOURCE_DIR_RESOLVED: 'C:/ws'])
    sc.files['.appscan-logs/appscan.cmd'] = 'C:\\ws\\.appscan-tools\\bin\\appscan.bat'
    sc.files['Orders.sln'] = 'Microsoft Visual Studio Solution File'
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(sc, state, os, policy)
        state.currentProjectName = 'orders'
        state.cfg = [appId: 'app-uuid', asoc: [keyId: 'k', doCompile: false]] + cfg
        harness.type('com.bbh.scanner.AppScanService').newInstance(sc, state, os, policy, build).generateIrx()
    }
    return sc.calls.find { it.contains(' prepare -s thorough') } ?: ''
}
String gradleStaging = staging([buildTool: 'gradle'])
String includedStaging = staging([buildTool: 'gradle', includedDirs: 'src,lib'])
String dotnetStaging = staging([buildTool: 'dotnet', build: [dotnet: [solution: 'Orders.sln']]])
String nonDotnetXd = '/XD test test_utils Tests sast_directory .appscan-tools .appscan-logs .appscan-home .appscan-bin /R:2 /W:2 /MT:16 /NFL /NDL /NJH /NP /NS'
check('the Windows IRX staging: robocopy retries twice for 2 seconds, an exit code of 8 or more stops SAST, the workspace tool folders and sast_directory are left out for every service, sast_directory is removed after prepare with Remove-DsoFolder, and the script ends with the exit code of the prepare, saved right after it, so a failed prepare still fails the step',
        gradleStaging.contains("robocopy \"\$env:APPSCAN_SOURCE_DIR_RUNTIME\" \"\$sastDirectory\" /E ${nonDotnetXd}\n".toString())
                && gradleStaging.contains('if ($LASTEXITCODE -ge 8) { Write-Error "[ERROR] robocopy could not stage $env:APPSCAN_SOURCE_DIR_RUNTIME for the IRX (exit code $LASTEXITCODE)"; exit 1 }')
                && includedStaging.contains("        robocopy \"\$srcPath\" \"\$targetPath\" /E ${nonDotnetXd}\n        if (\$LASTEXITCODE -ge 8) { Write-Error \"[ERROR] robocopy could not stage \$srcPath for the IRX (exit code \$LASTEXITCODE)\"; exit 1 }\n".toString())
                && dotnetStaging.contains('/XD sast_directory .git obj packages TestResults .appscan-tools .appscan-logs .appscan-home .appscan-bin .scannerwork .sonar node_modules DevSecOpsJenkinsLibrary /R:2 /W:2 /MT:16')
                && [gradleStaging, includedStaging, dotnetStaging].every { String s ->
            (s.contains("\$ErrorActionPreference = 'Stop'\n" + dsoFunction + "\$env:HOME        = \$env:APPSCAN_HOME_DIR\n")
                    && s.contains(" 2>&1 |\n    Out-File -FilePath \"\$env:APPSCAN_LOG_DIR/prepare.log\" -Encoding UTF8\n\$prepareCode = \$LASTEXITCODE\nif (-not (Test-Path \"\$env:WORKSPACE/\$env:APPSCAN_SCAN_NAME.irx\")) {")
                    && s.endsWith("Set-Location -Path \"\$env:WORKSPACE\"\nif (-not (Remove-DsoFolder \$sastDirectory)) { Write-Host \"[WARN] [SAST] \$sastDirectory could not be removed completely - the next SAST staging removes it first\" }\nexit \$prepareCode\n")
                    && s.count('$prepareCode') == 2 && !s.contains('exit 0')
                    && ps51(s))
        },
        gradleStaging)

Closure removal = { boolean unix, int status ->
    FakeScript sc = new FakeScript(unix: unix)
    sc.env.vars.putAll([WORKSPACE: unix ? '/ws' : 'C:\\ws'])
    sc.shHandler = { Map a -> a.returnStatus ? status : (a.returnStdout ? 'Linux' : null) }
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(sc)
        os.detect()
        def policy = harness.type('com.bbh.core.PolicyEngine').newInstance(sc, state, os)
        def build = harness.type('com.bbh.build.BuildService').newInstance(sc, state, os, policy)
        sc.calls.clear()
        harness.type('com.bbh.scanner.AppScanService').newInstance(sc, state, os, policy, build).removeStagedSources()
    }
    return [calls: sc.calls, log: sc.log]
}
Map removedWin = removal(false, 0)
Map stayedWin = removal(false, 1)
Map removedLinux = removal(true, 0)
String stagingRemoval = harness.run { utils().windowsFolderRemovalScript("(Join-Path \$env:WORKSPACE 'sast_directory')") } as String
check('AppScanService.removeStagedSources on Windows: robocopy /MIR from an empty folder and Remove-Item (Remove-DsoFolder) in one step with returnStatus, a [WARN] line when sast_directory stays, and Linux unchanged',
        removedWin.calls == ["powershell [${stagingLabel}] ${stagingRemoval}".toString()] && removedWin.log == []
                && stagingRemoval.startsWith("\$ErrorActionPreference = 'Stop'\n" + dsoFunction + "try {\n")
                && stagingRemoval.contains("    if (Remove-DsoFolder (Join-Path \$env:WORKSPACE 'sast_directory')) { exit 0 }\n")
                && stayedWin.log == ['[WARN] [SAST] The SAST staging folder sast_directory could not be removed completely from the workspace - the next SAST staging removes it first']
                && removedLinux.calls == ['sh rm -rf "$WORKSPACE/appscan-source-staged"'] && removedLinux.log == [],
        [removedWin, stayedWin, removedLinux])

Map turnOf = harness.run {
    List order = [UNIT, NIQ, SONAR]
    [unit: utils().workspaceTurn(UNIT, order, true), niq: utils().workspaceTurn(NIQ, order, true), sonar: utils().workspaceTurn(SONAR, order, true),
     sast: utils().workspaceTurn(SAST, order, true), linux: utils().workspaceTurn(SONAR, order, false),
     names: [utils().namesText([]), utils().namesText([UNIT]), utils().namesText([UNIT, NIQ]), utils().namesText(['a', 'b', 'c'])]]
} as Map
check('BuildUtils.workspaceTurn: on Windows Nexus IQ waits for Unit tests and SonarQube for Unit tests and Nexus IQ, Unit tests and SAST wait for nothing; on Linux nothing waits',
        turnOf == [unit: [], niq: [UNIT], sonar: [UNIT, NIQ], sast: [], linux: [],
                   names: ['', UNIT, "${UNIT} and ${NIQ}".toString(), 'a, b and c']], turnOf)

Map accounting = harness.run {
    def state = harness.type('com.bbh.core.PipelineState').newInstance()
    long MIN = 60000L
    List branches = [[stage: UNIT, ms: 6 * MIN, waitMs: 0L], [stage: NIQ, ms: 9 * MIN, waitMs: 6 * MIN, waitFor: UNIT],
                     [stage: SAST, ms: 12 * MIN, waitMs: 0L], [stage: SONAR, ms: 17 * MIN, waitMs: 9 * MIN, waitFor: "${UNIT} and ${NIQ}".toString()]]
    state.recordParallelGroup(STATIC, 0L, 17 * MIN, branches)
    Map times = [(UNIT): [start: 0L, end: 6 * MIN], (NIQ): [start: 0L, end: 9 * MIN], (SAST): [start: 0L, end: 12 * MIN], (SONAR): [start: 0L, end: 17 * MIN]]
    Map results = [(UNIT): 'PASS', (NIQ): 'PASS', (SAST): 'PASS', (SONAR): 'PASS']
    Map table = harness.type('com.bbh.report.ReportViewModel').manualComparison(times, results, state.parallelGroups, [unitTests: 0L, nexusIq: 10L, sast: 10L, sonar: 10L]) as Map
    Map linux = harness.type('com.bbh.report.ReportViewModel').manualComparison(times, results,
            [[name: STATIC, branches: [[stage: UNIT, waitMs: 0L], [stage: NIQ, waitMs: 0L], [stage: SAST, waitMs: 0L], [stage: SONAR, waitMs: 6 * MIN]]]],
            [unitTests: 0L, nexusIq: 10L, sast: 10L, sonar: 10L]) as Map
    [group: state.parallelGroups[0], rows: (table.rows as List).collect { [it.step, (it.ms as long).intdiv(MIN), it.automatedNote] },
     linuxNote: ((linux.rows as List)[3] as Map).automatedNote]
} as Map
check('the waits of the workspace turns in the report: the time one after another leaves out what Nexus IQ and SonarQube waited, each step keeps only its own work, and the longest names what it waited for; without a name the note keeps the unit tests of Linux',
        (accounting.group as Map).sequentialMs == 29L * 60000L && (accounting.group as Map).actualMs == 17L * 60000L
                && accounting.rows == [['Unit test with Code Coverage', 6L, 'in parallel, within the time of SonarQube'], ['Nexus IQ', 3L, 'in parallel, within the time of SonarQube'],
                                       ['SAST', 12L, 'in parallel, within the time of SonarQube'],
                                       ['SonarQube', 8L, "the longest of the 4 in parallel, with 9m 0s of waiting for ${UNIT} and ${NIQ}".toString()]]
                && accounting.linuxNote == 'the longest of the 4 in parallel, with 6m 0s of waiting for the unit tests',
        accounting)

Map fullRun = runWindows([var: 'devSecOpsPipeline', job: 'CertScanner-devsecops-pipeline', buildNumber: 460, params: [DEPLOY_HIGHER_ENV: true]])
List<String> fullLog = fullRun.log as List<String>
check('Windows agent, the full pipeline: the static and the dynamic group both run in parallel; the dynamic group needs no copy and no turns',
        fullRun.crash == '' && fullRun.stageFailures == []
                && fullLog.contains(groupLine) && fullLog.contains(copyLine)
                && fullLog.contains('[DYNAMIC] Regression, Smoke and Performance tests and DAST run at the same time (dynamicAnalysis.parallel: true, the default)')
                && fullLog.contains("[PARALLEL] ${DYNAMIC}: ${REGRESSION}, ${SMOKE}, ${PERFORMANCE}, ${DAST} run at the same time".toString())
                && (fullRun.calls as List<String>).count { it == 'parallel 4' } == 2
                && !fullLog.any { it.contains('Windows agent:') && !it.contains(NIQ) && !it.contains(SONAR) },
        [fullRun.crash, fullRun.stageFailures, fullLog.findAll { it.startsWith('[PARALLEL]') || it.startsWith('[DYNAMIC]') }])

if (withOneView) {
    Map keyed = [keyless: false, env: [ONEVIEW_AGENT: 'oneview-agent'], oneViewAgent: 'oneview-agent']
    Map ticked = runWindows(keyed + [buildNumber: 480])
    List<String> tickedCalls = ticked.calls as List<String>
    int readAt = indexOf(tickedCalls) { String c -> c.startsWith('sh [Read the configuration from OneView] ') }
    int tickedCopyAt = indexOf(tickedCalls) { String c -> c.startsWith("powershell [${copyLabel}] ".toString()) }
    check('Windows agent, a run with a pipeline key: OneView is read on the Linux agent of ONEVIEW_AGENT, and with the Global Settings checkbox ticked, the default, the static analysis runs in parallel with the SAST copy and the turns, as without a key',
            ticked.crash == '' && ticked.stageFailures == [] && (ticked.executed as List).contains(STATIC)
                    && tickedCalls.indexOf('node oneview-agent') >= 0 && tickedCalls.indexOf('node oneview-agent') < readAt && readAt < tickedCopyAt
                    && (ticked.staticLog as List)[0] == defaultLine && (ticked.staticLog as List).contains(copyLine) && (ticked.staticLog as List).contains(groupLine),
            [ticked.crash, ticked.stageFailures, ticked.executed, ticked.staticLog, readAt, tickedCopyAt])
    Map unticked = runWindows(keyed + [buildNumber: 481, parallel: false])
    List<String> untickedCalls = unticked.calls as List<String>
    check('Windows agent, a run with a pipeline key and the Global Settings checkbox unticked: Unit tests, Nexus IQ, SAST and SonarQube run one after another, without the SAST copy and without turns',
            unticked.crash == '' && unticked.stageFailures == [] && !(unticked.executed as List).contains(STATIC)
                    && (unticked.executed as List).containsAll([UNIT, NIQ, SAST, SONAR])
                    && unticked.staticLog == ['[STATIC] The Global Settings set staticAnalysis.parallel: false - Unit tests, Nexus IQ, SAST and SonarQube run one after another']
                    && !untickedCalls.any { it.startsWith("powershell [${copyLabel}] ".toString()) }
                    && !(unticked.log as List<String>).any { it.contains('Windows agent:') },
            [unticked.crash, unticked.stageFailures, unticked.executed, unticked.staticLog])
}

println "\n${failures == 0 ? 'ALL WINDOWS STATIC SCENARIOS PASSED' : failures + ' WINDOWS STATIC SCENARIO CHECK(S) FAILED'}"
System.exit(failures == 0 ? 0 : 1)
