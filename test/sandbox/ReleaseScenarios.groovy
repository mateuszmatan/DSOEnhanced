import devsecops.test.FakeAbort
import devsecops.test.FakeCpsScript
import devsecops.test.FakeScript
import devsecops.test.SandboxHarness
import devsecops.test.ScenarioRunner
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.json.JsonSlurperClassic

File root = new File(args.length > 0 ? args[0] : '.').canonicalFile
File oneViewFixture = new File(root, 'test/fixtures/oneview/certscanner-gui-full.json')
boolean withOneView = oneViewFixture.isFile()
String securityJob = 'DevSecOps/CertScanner-security-pipeline'
SandboxHarness harness = new SandboxHarness(new File(root, 'src'), new File(root, 'test/sandbox/stub'))

String SNAPSHOT = 'Nexus delivery (Static analysis passed)'
String PULL = 'Download artifacts from Nexus Repo'
String RELEASE = 'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities'
String QC = 'Higher test environment deployment'

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
                                 'a2a-certs/PROXY_ASOCJenk.cert.pem', 'a2a-certs/PROXY_ASOCJenk.key.pem', 'a2a-certs/PROXY_ASOCJenk.keypsw']
Map context = [root           : root, defaultsText: new File(root, 'test/fixtures/defaults.yaml').text,
               configText     : new File(root, 'test/fixtures/CertScanner/config.yaml').text, securityJob: securityJob, dataFor: dataFor,
               issuesHtml     : issuesHtml, secretResources: secretResources, archive: { Map projects, Map s -> },
               platform       : withOneView ? (new JsonSlurperClassic().parseText(oneViewFixture.text) as Map).platform as Map : [:],
               variants       : [devSecOpsPipeline: 'full', devSecOpsSecurityPipeline: 'security', devSecOpsExtendedPipeline: 'extended']]

String releases = 'https://tools.bbh.com/nexus/repository/releases'
String snapshots = 'https://tools.bbh.com/nexus/repository/snapshots'
String manifestFile = '/ws@tmp/nexus-gradle.Ab12Cd34/manifest.json'
String upstreamTag = '213-20260919-081500'
Closure sha = { String text -> java.security.MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).encodeHex().toString() }
Closure relative = { String path -> path.startsWith('/ws/') ? path.substring(4) : path }
Closure keyOf = { def classifier, def extension -> "${classifier ?: ''}.${extension}".toString() }
Closure releaseDefaults = { Map d -> d.delivery = [release: [enabled: true, repositoryUrl: releases, repositoryId: 'bbh-releases', credentialsId: '']] }
Map guiPush = [group: 'com.bbh.certscanner', artifact: 'certscanner-gui', repositoryUrl: snapshots, artifacts: [[path: 'build/libs/*.jar']]]
Map apiPush = [goals    : ['deploy:deploy-file'],
               flags    : ['-DrepositoryId=bbh-snapshots', "-Durl=${snapshots}".toString(), '-DgroupId=com.bbh.certscanner', '-DartifactId=certscanner-api', '-DgeneratePom=true'],
               artifacts: [[path: 'target/certscanner-api.jar'], [path: 'target/config.zip', classifier: 'config']]]

Closure responder = { Map spec, List<Map> gradleRuns, List<String> mavenGets ->
    boolean changed = false
    return { FakeScript j, Map a ->
        String text = (a.script ?: '') as String
        String label = (a.label ?: '') as String
        if (spec.changeAfterUpload && !changed && j.files.containsKey('nexus-delivery.json')) {
            changed = true
            (spec.changeAfterUpload as Map).each { k, v -> j.files[k as String] = v as String }
        }
        def shaCall = text =~ /sha256sum -- '([^']+)'/
        if (shaCall.find()) {
            String file = relative(shaCall.group(1))
            return [j.files.containsKey(file) ? "${sha(j.files[file])}\n".toString() : '']
        }
        def hashCall = text =~ /Get-FileHash -Algorithm SHA256 -LiteralPath '([^']+)'/
        if (hashCall.find()) {
            String file = hashCall.group(1).startsWith('C:/ws/') ? hashCall.group(1).substring(6) : hashCall.group(1)
            return [j.files.containsKey(file) ? sha(j.files[file]).toUpperCase() : '']
        }
        if (text.contains("'./gradlew'") && (label.startsWith('Nexus Gradle') || label.startsWith('Nexus release:'))) {
            Map manifest = new JsonSlurper().parseText(j.files[manifestFile]) as Map
            gradleRuns << [label: label, manifest: manifest, credentials: j.env.vars.get('ORG_GRADLE_PROJECT_nexusUsername')]
            if (text.contains("'pullNexusArtifacts'")) {
                boolean lookup = label.startsWith('Nexus release: look for')
                Map contents = [:] + ((lookup ? spec.released : spec.snapshot) ?: [:]) as Map
                List items = manifest.artifacts as List
                if (!lookup) items.each { Map item -> if (!contents.containsKey(keyOf(item.classifier, item.extension))) contents[keyOf(item.classifier, item.extension)] = 'jar' }
                if (items.any { Map item -> !contents.containsKey(keyOf(item.classifier, item.extension)) }) throw new FakeAbort('script returned exit code 1')
                items.each { Map item -> j.files[relative("${manifest.outputDirectory}/${item.fileName}".toString())] = contents[keyOf(item.classifier, item.extension)] as String }
            }
            return [null]
        }
        def fetched = text =~ /-Dartifact=([^:\s']+):([^:\s']+):([^:\s']+):([^:\s']+)(?::([^:\s']+))?'? '?-Dmaven\.repo\.local=([^\s']+)/
        if (fetched.find()) {
            String g = fetched.group(1), art = fetched.group(2), v = fetched.group(3), ext = fetched.group(4), cls = fetched.group(5) ?: ''
            boolean release = !v.endsWith('-SNAPSHOT')
            Map contents = ((release ? spec.released : spec.snapshot) ?: [:]) as Map
            String key = keyOf(cls, ext)
            String content = contents.containsKey(key) ? contents[key] as String : (release ? null : (ext == 'zip' ? 'zip' : 'jar'))
            mavenGets << text
            if (content == null) throw new FakeAbort('script returned exit code 1')
            j.files["${fetched.group(6)}/${g.replace('.', '/')}/${art}/${v}/${art}-${v}${cls ? '-' + cls : ''}.${ext}".toString()] = content
            return [null]
        }
        return null
    }
}

Closure resultOf = { Map r, String stage ->
    List<String> log = (r.j as FakeScript).log
    int i = log.indexOf("[STAGE]  ${stage}".toString())
    i < 0 ? 'not run' : log[i + 1].replace('[RESULT] ', '')
}
Closure nexusLog = { Map r -> (r.j as FakeScript).log.findAll { it.contains('[NEXUS]') } }
Closure recordOf = { Map r -> String text = (r.j as FakeScript).files['nexus-delivery.json']; text ? new JsonSlurper().parseText(text) as Map : [:] }
Closure callsLike = { Map r, String part -> (r.j as FakeScript).calls.findAll { it.contains(part) } }

Closure pipeline = { Map spec ->
    List<Map> gradleRuns = []
    List<String> mavenGets = []
    Map full = [var: 'devSecOpsPipeline', fail: false, job: 'CertScanner-devsecops-pipeline', params: [DEPLOY_HIGHER_ENV: true]] + spec
    full.files = ['devsecops_config.yaml': null] + ((spec.files ?: [:]) as Map)
    full.respond = responder(spec, gradleRuns, mavenGets)
    Map r = ScenarioRunner.runPipeline(full, context) + [gradleRuns: gradleRuns, mavenGets: mavenGets]
    check("${spec.name}: the pipeline var runs to the end under the sandbox", !r.crash, r.crash)
    return r
}
Closure extended = { Map spec ->
    pipeline([var: 'devSecOpsExtendedPipeline', job: 'CertScanner-extended-pipeline', params: [DEPLOY_HIGHER_ENV: true]] + spec)
}

Map gradleRelease = pipeline([name: 'full pipeline, Gradle release', buildNumber: 401, defaults: releaseDefaults, configure: { Map p ->
    p.remove('backend-api')
    (p.gui as Map).delivery = [push: [gradle: guiPush]]
}])
FakeScript grJenkins = gradleRelease.j as FakeScript
String grTag = (grJenkins.files['build.tag'] ?: '') as String
List<Map> grRuns = gradleRelease.gradleRuns as List<Map>
String grCoordinates = "com.bbh.certscanner:certscanner-gui:${grTag}".toString()
check('full pipeline, Gradle release: stage 6 publishes the snapshot and stage 12 the release, both PASS',
        grTag ==~ /401-\d{8}-\d{6}/ && resultOf(gradleRelease, SNAPSHOT) == 'PASS' && resultOf(gradleRelease, RELEASE) == 'PASS',
        [grTag, resultOf(gradleRelease, SNAPSHOT), resultOf(gradleRelease, RELEASE)])
check('full pipeline, Gradle release: nexus-delivery.json records the tag of the run, the coordinates and the SHA-256 of every file stage 6 uploaded',
        recordOf(gradleRelease) == [buildTag: grTag, services: [gui: [upload  : 'gradle', group: 'com.bbh.certscanner', artifact: 'certscanner-gui', version: "${grTag}-SNAPSHOT".toString(),
                                                                      repositoryId: '', repositoryUrl: snapshots, allowInsecureProtocol: false,
                                                                      files   : [[path: 'build/libs/cert-scanner-gui.jar', fileName: 'cert-scanner-gui.jar', classifier: 'cert-scanner-gui',
                                                                                  extension: 'jar', sha256: sha('jar')]]]]]
                && grJenkins.log.contains('[NEXUS] gui: nexus-delivery.json records the SHA-256 of the 1 uploaded file(s) for the release'),
        recordOf(gradleRelease))
check('full pipeline, Gradle release: stage 12 looks for the tag in the release repository with refreshed dependencies, then publishes the workspace file that passed the gate as the tag, without downloading the snapshot',
        grRuns.collect { [it.label, it.manifest.version, it.manifest.repositoryUrl] } == [['Nexus Gradle push', "${grTag}-SNAPSHOT".toString(), snapshots],
                                                                                         ["Nexus release: look for ${grCoordinates} in the release repository".toString(), grTag, releases],
                                                                                         ["Nexus release: publish ${grCoordinates}".toString(), grTag, releases]]
                && grRuns[1].manifest.outputDirectory == '/ws@tmp/nexus-release/gui/released'
                && grRuns[2].manifest.artifacts == [[fileName: 'cert-scanner-gui.jar', extension: 'jar', classifier: 'cert-scanner-gui', path: '/ws/build/libs/cert-scanner-gui.jar']]
                && grRuns.every { it.credentials == null && !it.manifest.credentials }
                && grJenkins.calls.find { it.startsWith('sh [Nexus release: look for') }?.contains("'--refresh-dependencies'")
                && !grJenkins.calls.find { it.startsWith('sh [Nexus release: publish') }?.contains("'--refresh-dependencies'")
                && nexusLog(gradleRelease).containsAll(["[NEXUS] gui: ${grCoordinates} is not in ${releases} yet".toString(),
                                                        "[NEXUS] gui: releasing 1 artifact(s) of ${grTag}-SNAPSHOT as ${grCoordinates} to ${releases}".toString(),
                                                        "[NEXUS] gui: released ${grCoordinates}, the files that passed the gate".toString()]),
        grRuns.collect { [it.label, it.manifest] } + nexusLog(gradleRelease))

Map changedFile = pipeline([name: 'full pipeline, Gradle release of a file changed after stage 6', buildNumber: 402, defaults: releaseDefaults,
                            changeAfterUpload: ['build/libs/cert-scanner-gui.jar': 'jar rebuilt by a later stage'], configure: { Map p ->
    p.remove('backend-api')
    (p.gui as Map).delivery = [push: [gradle: guiPush], release: [credentialsId: 'nexus-release-user']]
}])
FakeScript cfJenkins = changedFile.j as FakeScript
String cfTag = (cfJenkins.files['build.tag'] ?: '') as String
List<Map> cfRuns = changedFile.gradleRuns as List<Map>
String cfCoordinates = "com.bbh.certscanner:certscanner-gui:${cfTag}".toString()
check('full pipeline, Gradle release of a file changed after stage 6: the [WARN] names the file, the snapshot that passed the gate is downloaded again and released',
        resultOf(changedFile, RELEASE) == 'PASS'
                && cfRuns.collect { it.label } == ['Nexus Gradle push', "Nexus release: download com.bbh.certscanner:certscanner-gui:${cfTag}-SNAPSHOT".toString(),
                                                   "Nexus release: look for ${cfCoordinates} in the release repository".toString(), "Nexus release: publish ${cfCoordinates}".toString()]
                && cfRuns[1].manifest.repositoryUrl == snapshots && cfRuns[1].manifest.version == "${cfTag}-SNAPSHOT".toString()
                && cfRuns[1].manifest.outputDirectory == '/ws@tmp/nexus-release/gui/snapshot'
                && (cfRuns[3].manifest.artifacts as List<Map>)*.path == ['/ws@tmp/nexus-release/gui/snapshot/cert-scanner-gui.jar']
                && nexusLog(changedFile).containsAll(["[NEXUS] [WARN] gui: build/libs/cert-scanner-gui.jar is not the cert-scanner-gui.jar that passed the gate - it is downloaded from ${snapshots}".toString(),
                                                      "[NEXUS] gui: downloaded 1 file(s) of com.bbh.certscanner:certscanner-gui:${cfTag}-SNAPSHOT that passed the gate".toString()]),
        cfRuns.collect { [it.label, it.manifest] } + nexusLog(changedFile))
check('full pipeline, Gradle release of a file changed after stage 6: delivery.release.credentialsId logs in to the release repository only, for the look-up and the publish',
        cfRuns.collect { it.credentials != null } == [false, false, true, true] && cfRuns.collect { it.manifest.credentials == true } == [false, false, true, true]
                && cfJenkins.calls.count('withCredentials [nexus-release-user]') == 2,
        cfRuns.collect { [it.label, it.credentials, it.manifest.credentials] })

Map mavenRelease = pipeline([name: 'full pipeline, Maven release of two files', buildNumber: 403, defaults: releaseDefaults, files: ['target/config.zip': 'zip'], configure: { Map p ->
    Map backend = p['backend-api'] as Map
    backend.deployTarget = 'vm'
    (backend.deploy as Map).vm = ((p.gui as Map).deploy as Map).vm
    backend.delivery = [push: [maven: apiPush]]
    p.remove('gui')
}])
FakeScript mrJenkins = mavenRelease.j as FakeScript
String mrTag = (mrJenkins.files['build.tag'] ?: '') as String
List<String> mrGets = mavenRelease.mavenGets as List<String>
String mrUpload = mrJenkins.calls.find { it.startsWith("sh [Nexus release: publish com.bbh.certscanner:certscanner-api:${mrTag}]".toString()) } ?: ''
check('full pipeline, Maven release of two files: nexus-delivery.json takes the group, the artifact and the server id from the Maven flags of stage 6',
        resultOf(mavenRelease, SNAPSHOT) == 'PASS' && recordOf(mavenRelease).services == ['backend-api': [
                upload       : 'maven', group: 'com.bbh.certscanner', artifact: 'certscanner-api', version: "${mrTag}-SNAPSHOT".toString(), repositoryId: 'bbh-snapshots',
                repositoryUrl: snapshots, allowInsecureProtocol: false,
                files        : [[path: 'target/certscanner-api.jar', fileName: 'certscanner-api.jar', classifier: 'certscanner-api', extension: 'jar', sha256: sha('jar')],
                                [path: 'target/config.zip', fileName: 'config.zip', classifier: 'config', extension: 'zip', sha256: sha('zip')]]]],
        recordOf(mavenRelease))
check('full pipeline, Maven release of two files: Maven looks for the main file in the release repository with -U, then deploys both workspace files as the tag with the release server id',
        resultOf(mavenRelease, RELEASE) == 'PASS' && mrGets.size() == 1
                && mrGets[0].contains("'org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get' '-U' '-Dartifact=com.bbh.certscanner:certscanner-api:${mrTag}:jar:certscanner-api' " +
                "'-Dmaven.repo.local=/home/jenkins/.m2/repository' '-Dtransitive=false' '-DremoteRepositories=bbh-releases::default::${releases}'")
                && mrUpload.contains("'mvn' 'deploy:deploy-file' '-DrepositoryId=bbh-releases' '-Durl=${releases}' '-DgroupId=com.bbh.certscanner' '-DartifactId=certscanner-api' " +
                "'-Dversion=${mrTag}' '-Dfile=target/certscanner-api.jar' '-Dpackaging=jar' '-DgeneratePom=true' '-Dclassifier=certscanner-api' '-Dfiles=target/config.zip' '-Dtypes=zip' '-Dclassifiers=config'")
                && nexusLog(mavenRelease).contains("[NEXUS] backend-api: released com.bbh.certscanner:certscanner-api:${mrTag}, the files that passed the gate".toString()),
        mrGets + [mrUpload] + nexusLog(mavenRelease))

Map legacyRelease = pipeline([name: 'full pipeline, Maven release of delivery.maven without a release server id', buildNumber: 404, mavenVmDelivery: true,
                              defaults: { Map d -> d.delivery = [release: [repositoryUrl: releases]] }, configure: { Map p ->
    ((p['backend-api'] as Map).deploy as Map).vm = ((p.gui as Map).deploy as Map).vm
    p.remove('gui')
}])
FakeScript lrJenkins = legacyRelease.j as FakeScript
String lrTag = (lrJenkins.files['build.tag'] ?: '') as String
List<String> lrGets = legacyRelease.mavenGets as List<String>
String lrUpload = lrJenkins.calls.find { it.startsWith("sh [Nexus release: publish com.bbh.certscanner:certscanner-api:${lrTag}]".toString()) } ?: ''
check('full pipeline, Maven release of delivery.maven without a release server id: Maven logs in with the server id of the snapshot upload and the file has no classifier, as in stage 6',
        resultOf(legacyRelease, RELEASE) == 'PASS'
                && (recordOf(legacyRelease).services as Map)['backend-api'].upload == 'maven-legacy'
                && nexusLog(legacyRelease).contains("[NEXUS] backend-api: delivery.release.repositoryId is not set - Maven logs in to ${releases} with the server bbh-snapshots of the snapshot upload".toString())
                && lrGets.size() == 1 && lrGets[0].contains("'-Dartifact=com.bbh.certscanner:certscanner-api:${lrTag}:jar' '-Dmaven.repo.local=/home/jenkins/.m2/repository' '-Dtransitive=false' '-DremoteRepositories=bbh-snapshots::default::${releases}'")
                && lrUpload.contains("'-DrepositoryId=bbh-snapshots' '-Durl=${releases}' '-DgroupId=com.bbh.certscanner' '-DartifactId=certscanner-api' '-Dversion=${lrTag}' '-Dfile=target/certscanner-api.jar' '-Dpackaging=jar' '-DgeneratePom=true'")
                && !lrUpload.contains('-Dclassifier='),
        lrGets + [lrUpload] + nexusLog(legacyRelease))

Map upstreamRecord = [buildTag: upstreamTag, services: [gui: [upload  : 'gradle', group: 'com.bbh.certscanner', artifact: 'certscanner-gui', version: "${upstreamTag}-SNAPSHOT".toString(),
                                                               repositoryId: '', repositoryUrl: snapshots, allowInsecureProtocol: false,
                                                               files   : [[path: 'build/libs/cert-scanner-gui.jar', fileName: 'cert-scanner-gui.jar', classifier: 'cert-scanner-gui', extension: 'jar',
                                                                           sha256: sha('jar')]]]]]
Closure extendedGui = { Map p ->
    p.remove('backend-api')
    (p.gui as Map).delivery = [push: [gradle: guiPush], pull: [outputDirectory: 'downloaded-artifacts', gradle: guiPush + [artifacts: [[path: 'cert-scanner-gui.jar']]]]]
}
Map upstreamFiles = ['build.tag': upstreamTag, 'nexus-delivery.json': JsonOutput.toJson(upstreamRecord)]
String upCoordinates = "com.bbh.certscanner:certscanner-gui:${upstreamTag}".toString()

Map extendedRelease = extended([name: 'extended pipeline, Gradle release of the downloaded files', buildNumber: 410, defaults: releaseDefaults, upstreamFiles: upstreamFiles,
                                configure: extendedGui])
List<Map> erRuns = extendedRelease.gradleRuns as List<Map>
check('extended pipeline, Gradle release of the downloaded files: nexus-delivery.json comes with build.tag from the security pipeline, and the files stage 5 downloaded are released as the tag',
        resultOf(extendedRelease, PULL) == 'PASS' && resultOf(extendedRelease, RELEASE) == 'PASS'
                && (extendedRelease.j as FakeScript).calls.contains('copyArtifacts DevSecOps/CertScanner-security-pipeline filter=pipeline-config.yaml,release-gate.json,build.tag,nexus-delivery.json')
                && erRuns.collect { it.label } == ['Nexus Gradle pull', "Nexus release: look for ${upCoordinates} in the release repository".toString(), "Nexus release: publish ${upCoordinates}".toString()]
                && erRuns[2].manifest.version == upstreamTag && (erRuns[2].manifest.artifacts as List<Map>)*.path == ['/ws/downloaded-artifacts/cert-scanner-gui.jar'],
        erRuns.collect { [it.label, it.manifest] } + nexusLog(extendedRelease))

Map sameRelease = extended([name: 'extended pipeline, release already published with the same files', buildNumber: 411, defaults: releaseDefaults, upstreamFiles: upstreamFiles,
                            released: ['cert-scanner-gui.jar': 'jar'], configure: extendedGui])
check('extended pipeline, release already published with the same files: a run again of the same security build uploads nothing and the stage stays PASS',
        resultOf(sameRelease, RELEASE) == 'PASS' && !(sameRelease.gradleRuns as List<Map>).any { (it.label as String).startsWith('Nexus release: publish') }
                && nexusLog(sameRelease).contains("[NEXUS] gui: ${upCoordinates} is already in ${releases} with the files that passed the gate - nothing is uploaded".toString()),
        nexusLog(sameRelease))

Map otherRelease = extended([name: 'extended pipeline, release already published with other files', buildNumber: 412, defaults: releaseDefaults, upstreamFiles: upstreamFiles,
                             released: ['cert-scanner-gui.jar': 'another jar'], configure: extendedGui])
check('extended pipeline, release already published with other files: the stage fails, the release is not replaced and the higher environment is not deployed',
        resultOf(otherRelease, RELEASE) == 'FAIL' && resultOf(otherRelease, QC) != 'PASS'
                && !(otherRelease.gradleRuns as List<Map>).any { (it.label as String).startsWith('Nexus release: publish') }
                && (otherRelease.j as FakeScript).log.contains("[error] [NEXUS] gui: ${upCoordinates} is already in ${releases} with other files than those that passed the gate - the release is not replaced".toString()),
        [resultOf(otherRelease, RELEASE), resultOf(otherRelease, QC)] + nexusLog(otherRelease))

Map olderUpstream = extended([name: 'extended pipeline, security build of an older library', buildNumber: 413, defaults: releaseDefaults, upstreamFiles: ['build.tag': upstreamTag],
                              configure: extendedGui])
check('extended pipeline, security build of an older library: without nexus-delivery.json the copy still succeeds, the release stage is NOT_REQUIRED and says why',
        resultOf(olderUpstream, PULL) == 'PASS' && resultOf(olderUpstream, RELEASE) == 'NOT_REQUIRED'
                && nexusLog(olderUpstream).contains("[NEXUS] gui: '${SNAPSHOT}' published no artifact of the service as ${upstreamTag}-SNAPSHOT (nexus-delivery.json) - nothing is released".toString()),
        [resultOf(olderUpstream, PULL), resultOf(olderUpstream, RELEASE)] + nexusLog(olderUpstream))

Closure deepMerge
deepMerge = { Map base, Map over ->
    Map out = [:] + base
    over.each { k, v -> out[k] = (v instanceof Map && out[k] instanceof Map) ? deepMerge(out[k] as Map, v as Map) : v }
    return out
}
Closure unit = { Map spec ->
    FakeScript j = new FakeScript(unix: !spec.windows)
    j.env.vars.putAll([WORKSPACE: spec.windows ? 'C:/ws' : '/ws', BUILD_NUMBER: '77'])
    j.resourcesDir = new File(root, 'resources')
    j.files['gradlew'] = '#!/bin/sh'
    ((spec.files ?: [:]) as Map).each { k, v -> j.files[k as String] = v as String }
    List<Map> gradleRuns = []
    List<String> mavenGets = []
    Closure answer = responder(spec, gradleRuns, mavenGets)
    j.shHandler = { Map a ->
        List handled = answer.call(j, a) as List
        if (handled != null) return handled[0]
        String text = (a.script ?: '') as String
        if (a.returnStatus) return 0
        def help = text =~ /-Dexpression=settings\.localRepository'? '?-Doutput=([^\s']+)/
        if (help.find()) j.files[help.group(1)] = '/home/jenkins/.m2/repository\n'
        if (text.contains('mktemp -d')) return '/ws@tmp/nexus-gradle.Ab12Cd34'
        if (text.contains('uname -s')) return 'Linux'
        return a.returnStdout ? '' : null
    }
    if (spec.prepare) (spec.prepare as Closure).call(j)
    Map out = [j: j, gradleRuns: gradleRuns, mavenGets: mavenGets, crash: '']
    harness.run {
        def state = harness.type('com.bbh.core.PipelineState').newInstance()
        def os = harness.type('com.bbh.core.OsHelper').newInstance(j)
        os.detect()
        state.currentProjectName = 'svc'
        state.configFile = spec.containsKey('configFile') ? spec.configFile : 'devsecops_config.yaml'
        state.cfg = deepMerge((spec.cfgDefaults ?: [:]) as Map, spec.cfg as Map)
        state.cfgDefaults = (spec.cfgDefaults ?: [:]) as Map
        if (spec.libraryDefaults != null) state.libraryDefaults = spec.libraryDefaults as Map
        def delivery = harness.type('com.bbh.deploy.NexusDeliveryService').newInstance(j, state, os)
        try {
            if (spec.push) {
                state.currentStage = 'snapshot'
                delivery.pushToNexus('svc')
            }
            if (spec.buildTag) state.buildTag = spec.buildTag
            state.currentStage = 'release'
            delivery.publishRelease('svc', (spec.variant ?: 'full') as String)
        } catch (Throwable t) {
            out.crash = SandboxHarness.rejectionOf(t) ?: String.valueOf(t.message)
        }
        out.snapshotNotRequired = ((state.notRequiredProjects['snapshot'] ?: []) as List).contains('svc')
        out.notRequired = ((state.notRequiredProjects['release'] ?: []) as List).contains('svc')
        out.tag = state.buildTag
    }
    return out
}
Closure logOf = { Map r -> (r.j as FakeScript).log }
Map gradleCfg = [buildTool: 'gradle', deployTarget: 'vm', delivery: [push: [gradle: guiPush]]]
Map withRelease = [delivery: [release: [enabled: true, repositoryUrl: releases, repositoryId: 'bbh-releases']]]
String stage6 = "'${SNAPSHOT}'"

Map disabled = unit([cfg: [buildTool: 'gradle', deployTarget: 'vm', deploy: [vm: [rd: [nexusDeliveryEnabled: false]]]], cfgDefaults: withRelease])
check('release reasons, Nexus delivery disabled: NOT_REQUIRED, named by its setting',
        disabled.notRequired && !disabled.crash && logOf(disabled) == ['[NEXUS] svc: Nexus delivery is disabled by deploy.vm.rd.nexusDeliveryEnabled - no release is published'], logOf(disabled))
Map switchedOff = unit([cfg: gradleCfg + [delivery: [push: [gradle: guiPush], release: [enabled: false]]], cfgDefaults: withRelease])
check('release reasons, delivery.release.enabled false: NOT_REQUIRED, the snapshot stays the delivered artifact',
        switchedOff.notRequired && logOf(switchedOff) == ["[NEXUS] svc: delivery.release.enabled is false - the snapshot of ${stage6} stays the delivered artifact".toString()], logOf(switchedOff))
Map wrongSwitch = unit([cfg: gradleCfg + [delivery: [push: [gradle: guiPush], release: [enabled: 'sometimes']]], cfgDefaults: withRelease])
check('release reasons, delivery.release.enabled not a boolean: [WARN] with the default true, then the release goes on',
        wrongSwitch.notRequired && logOf(wrongSwitch) == ['[NEXUS] [WARN] svc: delivery.release.enabled: sometimes is not true or false - true from the library defaults is used instead',
                                                          "[NEXUS] svc: ${stage6} published no artifact of the service (nexus-delivery.json) - nothing is released".toString()], logOf(wrongSwitch))
Map wrongSwitchOff = unit([cfg: gradleCfg + [delivery: [push: [gradle: guiPush], release: [enabled: 'sometimes']]],
                          cfgDefaults: [delivery: [release: [enabled: false, repositoryUrl: releases, repositoryId: 'bbh-releases']]]])
check('release reasons, delivery.release.enabled not a boolean and false in the defaults: [WARN] with the default false, NOT_REQUIRED',
        wrongSwitchOff.notRequired && logOf(wrongSwitchOff) == ['[NEXUS] [WARN] svc: delivery.release.enabled: sometimes is not true or false - false from the library defaults is used instead',
                                                             "[NEXUS] svc: delivery.release.enabled is false - the snapshot of ${stage6} stays the delivered artifact".toString()], logOf(wrongSwitchOff))
Map openshift = unit([cfg: [buildTool: 'maven', deployTarget: 'openshift'], cfgDefaults: withRelease])
Map flutter = unit([cfg: [buildTool: 'flutter', deployTarget: 'vm'], cfgDefaults: withRelease])
check('release reasons, OpenShift and Flutter: NOT_REQUIRED, the image and the APK and IPA of stage 6 stay the delivered artifacts',
        openshift.notRequired && flutter.notRequired
                && logOf(openshift) == ["[NEXUS] svc: an OpenShift service delivers the image that ${stage6} copied to Nexus - no artifact is released".toString()]
                && logOf(flutter) == ["[NEXUS] svc: the Flutter APK and IPA of ${stage6} stay the delivered artifacts - no release is published".toString()],
        logOf(openshift) + logOf(flutter))

Map npmAlone = unit([cfg: [buildTool: 'npm', deployTarget: 'vm'], cfgDefaults: withRelease, push: true, files: ['dist/portal-1.4.0.tgz': 'tgz']])
check('npm and pip, no delivery.push.maven: stage 6 and stage 12 are NOT_REQUIRED and say what to set',
        npmAlone.snapshotNotRequired && npmAlone.notRequired
                && logOf(npmAlone) == ['[NEXUS] svc: set delivery.push.maven in devsecops_config.yaml to publish the npm package with Maven - the artifact is not published',
                                       "[NEXUS] svc: ${stage6} published no artifact of the service (nexus-delivery.json) - nothing is released".toString()], logOf(npmAlone))
Map npmPush = [goals    : ['deploy:deploy-file'], flags: ['-DrepositoryId=bbh-snapshots', "-Durl=${snapshots}".toString(), '-DgroupId=com.bbh.portal', '-DartifactId=portal-ui'],
               artifacts: [[path: 'dist/*.tgz', classifier: 'ui']]]
Map npmRelease = unit([cfg: [buildTool: 'npm', deployTarget: 'vm', delivery: [push: [maven: npmPush]]], cfgDefaults: withRelease, push: true, files: ['dist/portal-1.4.0.tgz': 'tgz']])
List<String> npmCalls = (npmRelease.j as FakeScript).calls.findAll { it.contains("'deploy:deploy-file'") }
check('npm and pip, delivery.push.maven: stage 6 deploys the package with Maven as <tag>-SNAPSHOT, and stage 12 releases the same file as the tag',
        !npmRelease.crash && !npmRelease.snapshotNotRequired && !npmRelease.notRequired && npmCalls.size() == 2
                && npmCalls[0].contains("'-Dversion=${npmRelease.tag}-SNAPSHOT' '-Dfile=dist/portal-1.4.0.tgz' '-Dpackaging=tgz' '-Dclassifier=ui'")
                && npmCalls[1].contains("'-DrepositoryId=bbh-releases' '-Durl=${releases}' '-DgroupId=com.bbh.portal' '-DartifactId=portal-ui' '-Dversion=${npmRelease.tag}' '-Dfile=dist/portal-1.4.0.tgz' '-Dpackaging=tgz' '-DgeneratePom=true' '-Dclassifier=ui'")
                && (npmRelease.mavenGets as List<String>).size() == 1 && (npmRelease.mavenGets as List<String>)[0].contains("'-Dartifact=com.bbh.portal:portal-ui:${npmRelease.tag}:tgz:ui'"),
        [npmRelease.crash] + npmCalls + logOf(npmRelease))
Map pipNoStep = unit([cfg   : [buildTool: 'pip', deployTarget: 'vm', delivery: [push: [maven: npmPush + [artifacts: [[path: 'dist/*.whl', classifier: 'wheel']]]]]], cfgDefaults: withRelease,
                      push  : true, files: ['dist/portal_api-1.4.0-py3-none-any.whl': 'wheel'],
                      prepare: { FakeScript j -> j.steps['sha256'] = { a -> throw new NoSuchMethodError("No such DSL method 'sha256' found among steps [sh, echo]") } }])
List<String> pipHashes = (pipNoStep.j as FakeScript).calls.findAll { it.startsWith('sh [Nexus delivery: SHA-256 of portal_api-1.4.0-py3-none-any.whl]') }
check('npm and pip, no sha256 step: the run says once that sha256sum computes the SHA-256 instead, and the release of the wheel goes on with it',
        !pipNoStep.crash && !pipNoStep.notRequired && pipHashes.size() == 2
                && logOf(pipNoStep).count { it.contains('cannot be used') } == 1
                && logOf(pipNoStep).contains('[NEXUS] The sha256 step of the Pipeline Utility Steps plugin cannot be used (NoSuchMethodError) - the SHA-256 of the delivered files is computed with sha256sum instead')
                && (recordOf(pipNoStep).services as Map).svc.files[0].sha256 == sha('wheel')
                && logOf(pipNoStep).any { it.startsWith('[NEXUS] svc: released com.bbh.portal:portal-api') || it.startsWith('[NEXUS] svc: released com.bbh.portal:portal-ui') },
        [pipNoStep.crash] + pipHashes + logOf(pipNoStep))
Map noHash = unit([cfg   : [buildTool: 'pip', deployTarget: 'vm', delivery: [push: [maven: npmPush + [artifacts: [[path: 'dist/*.whl', classifier: 'wheel']]]]]], cfgDefaults: withRelease,
                   push  : true, files: ['dist/portal_api-1.4.0-py3-none-any.whl': 'wheel'],
                   prepare: { FakeScript j ->
                       j.steps['sha256'] = { a -> throw new NoSuchMethodError("No such DSL method 'sha256' found among steps [sh, echo]") }
                       Closure base = j.shHandler
                       j.shHandler = { Map a -> String.valueOf(a.script ?: '').contains('sha256sum') ? 'sha256sum: command not found' : base.call(a) }
                   }])
check('npm and pip, no SHA-256 at all: stage 12 fails, as it cannot prove that the file is the one that passed the gate',
        noHash.crash == '[NEXUS] svc: nexus-delivery.json holds no SHA-256 of portal_api-1.4.0-py3-none-any.whl, so the release cannot prove that it holds the file that passed the gate'
                && logOf(noHash).count { it.startsWith('[NEXUS] [WARN] The SHA-256 of dist/portal_api-1.4.0-py3-none-any.whl cannot be computed') } == 1,
        [noHash.crash] + logOf(noHash))

Closure recorded = { Map service -> JsonOutput.toJson([buildTag: '77-20261123-101500', services: [svc: service]]) }
Map gradleService = [upload: 'gradle', group: 'com.bbh.certscanner', artifact: 'certscanner-gui', version: '77-20261123-101500-SNAPSHOT', repositoryId: '', repositoryUrl: snapshots,
                     allowInsecureProtocol: false, files: [[path: 'build/libs/cert-scanner-gui.jar', fileName: 'cert-scanner-gui.jar', classifier: 'cert-scanner-gui', extension: 'jar', sha256: sha('jar')]]]
Map unitFiles = ['build/libs/cert-scanner-gui.jar': 'jar', 'nexus-delivery.json': recorded(gradleService)]
Map invalidUrl = unit([cfg: gradleCfg + [delivery: [push: [gradle: guiPush], release: [repositoryUrl: 'nexus/releases', repositoryId: 'bbh-releases']]], cfgDefaults: withRelease,
                       buildTag: '77-20261123-101500', files: unitFiles])
check('release settings, repositoryUrl not an http or https address: [WARN] and the library default is used',
        !invalidUrl.crash && logOf(invalidUrl)[0] == "[NEXUS] [WARN] svc: delivery.release.repositoryUrl 'nexus/releases' is not an http or https address - ${releases} from the library defaults is used instead".toString()
                && (invalidUrl.gradleRuns as List<Map>).collect { it.manifest.repositoryUrl } == [releases, releases],
        [invalidUrl.crash] + logOf(invalidUrl))
Map keyed = unit([cfg: gradleCfg + [delivery: [push: [gradle: guiPush], release: [repositoryUrl: 'nexus/releases']]], cfgDefaults: withRelease, configFile: '',
                  buildTag: '77-20261123-101500', files: unitFiles])
String defaultsPlace = withOneView ? 'the Global Settings' : 'the library defaults'
check("release settings, a run ${withOneView ? 'with a pipeline key' : 'without the name of its settings file'}: the [WARN] names ${defaultsPlace} as the place of the default".toString(),
        !keyed.crash && logOf(keyed)[0] == "[NEXUS] [WARN] svc: delivery.release.repositoryUrl 'nexus/releases' is not an http or https address - ${releases} from ${defaultsPlace} is used instead".toString(),
        [keyed.crash] + logOf(keyed))
if (withOneView) {
    Map bundledOnly = unit([cfg: gradleCfg, cfgDefaults: [:], libraryDefaults: withRelease, configFile: '', buildTag: '77-20261123-101500', files: unitFiles])
    check('release settings, a run with a pipeline key and no release repository in the Global Settings: the release repository of the library defaults is used',
            !bundledOnly.crash && !bundledOnly.notRequired && (bundledOnly.gradleRuns as List<Map>).collect { it.manifest.repositoryUrl } == [releases, releases],
            [bundledOnly.crash] + logOf(bundledOnly))
    Map wrongGlobal = unit([cfg: gradleCfg, cfgDefaults: [delivery: [release: [repositoryUrl: 'nexus/releases', enabled: 'sometimes']]], libraryDefaults: withRelease, configFile: '',
                            buildTag: '77-20261123-101500', files: unitFiles])
    check('release settings, a wrong value in the Global Settings: [WARN], and the value of the library defaults is used',
            !wrongGlobal.crash && !wrongGlobal.notRequired
                    && logOf(wrongGlobal).take(2) == ['[NEXUS] [WARN] svc: delivery.release.enabled: sometimes is not true or false - true from the library defaults is used instead',
                                                      "[NEXUS] [WARN] svc: delivery.release.repositoryUrl 'nexus/releases' is not an http or https address - ${releases} from the library defaults is used instead".toString()],
            [wrongGlobal.crash] + logOf(wrongGlobal))
}
Map snapshotUrl = unit([cfg: gradleCfg + [delivery: [push: [gradle: guiPush], release: [repositoryUrl: "${snapshots}/".toString()]]], cfgDefaults: [:], buildTag: '77-20261123-101500', files: unitFiles])
check('release settings, repositoryUrl of the snapshot repository and no library default: [WARN], then NOT_REQUIRED with the setting to give',
        snapshotUrl.notRequired && logOf(snapshotUrl) == ["[NEXUS] [WARN] svc: delivery.release.repositoryUrl ${snapshots}/ is the snapshot repository of ${stage6} - a release needs a release repository".toString(),
                                                         "[NEXUS] svc: set delivery.release.repositoryUrl in devsecops_config.yaml or in the library defaults to release com.bbh.certscanner:certscanner-gui:77-20261123-101500 - the snapshot 77-20261123-101500-SNAPSHOT stays the delivered artifact".toString()],
        logOf(snapshotUrl))
Map mavenService = gradleService + [upload: 'maven', repositoryId: 'bbh-snapshots', files: [[path: 'target/certscanner-api.jar', fileName: 'certscanner-api.jar', classifier: 'certscanner-api',
                                                                                             extension: 'jar', sha256: sha('jar')]]]
Map badId = unit([cfg: [buildTool: 'maven', deployTarget: 'vm', delivery: [push: [maven: apiPush], release: [repositoryUrl: releases, repositoryId: 'bbh releases']]], cfgDefaults: [:],
                  buildTag: '77-20261123-101500', files: ['target/certscanner-api.jar': 'jar', 'nexus-delivery.json': recorded(mavenService)]])
check('release settings, repositoryId with a space: [WARN], and Maven logs in with the server id of the snapshot upload',
        !badId.crash && logOf(badId).containsAll(["[NEXUS] [WARN] svc: delivery.release.repositoryId 'bbh releases' is not a repository id of letters, digits and . _ - only - it is not used",
                                                  "[NEXUS] svc: delivery.release.repositoryId is not set - Maven logs in to ${releases} with the server bbh-snapshots of the snapshot upload".toString()])
                && (badId.j as FakeScript).calls.any { it.contains("'-DrepositoryId=bbh-snapshots' '-Durl=${releases}'".toString()) },
        [badId.crash] + logOf(badId))
Map noCoordinates = unit([cfg: [buildTool: 'maven', deployTarget: 'vm', delivery: [maven: [goals: ['deploy:deploy-file']]]], cfgDefaults: withRelease, buildTag: '77-20261123-101500',
                          files: ['target/certscanner-api.jar': 'jar', 'nexus-delivery.json': recorded(mavenService + [upload: 'maven-legacy', group: '', artifact: ''])]])
check('release settings, Maven flags without -DgroupId and -DartifactId: NOT_REQUIRED and named',
        noCoordinates.notRequired && logOf(noCoordinates) == ["[NEXUS] svc: the Maven flags of ${stage6} name no -DgroupId and -DartifactId, so the release cannot name 77-20261123-101500-SNAPSHOT - the snapshot stays the delivered artifact".toString()],
        logOf(noCoordinates))
Map wrongSnapshot = unit([cfg  : gradleCfg, cfgDefaults: withRelease, buildTag: '77-20261123-101500', snapshot: ['cert-scanner-gui.jar': 'a jar of another build'],
                          files: ['build/libs/cert-scanner-gui.jar': 'jar rebuilt', 'nexus-delivery.json': recorded(gradleService)]])
check('release safety, neither the workspace file nor the snapshot in Nexus is the file that passed the gate: the stage fails and nothing is published',
        wrongSnapshot.crash == "[NEXUS] svc: cert-scanner-gui.jar of com.bbh.certscanner:certscanner-gui:77-20261123-101500-SNAPSHOT in ${snapshots} is not the file that passed the gate - nothing is released".toString()
                && !(wrongSnapshot.gradleRuns as List<Map>).any { (it.label as String).startsWith('Nexus release: publish') },
        [wrongSnapshot.crash] + logOf(wrongSnapshot))
Map otherTag = unit([cfg: gradleCfg, cfgDefaults: withRelease, buildTag: '78-20261123-111500', files: unitFiles])
check('release safety, nexus-delivery.json of another build: NOT_REQUIRED, nothing is released',
        otherTag.notRequired && logOf(otherTag) == ["[NEXUS] svc: ${stage6} published no artifact of the service as 78-20261123-111500-SNAPSHOT (nexus-delivery.json) - nothing is released".toString()],
        logOf(otherTag))

String feed = 'https://tools.bbh.com/nexus/repository/nuget-hosted/'
String releaseFeed = 'https://tools.bbh.com/nexus/repository/nuget-releases/'
Map nugetFiles = ['dist/Orders.1.0.0.nupkg': 'nupkg']
Closure nugetCalls = { Map r -> (r.j as FakeScript).calls.findAll { it.contains(' push ') } }
Map dotnetRelease = unit([cfg  : [buildTool: 'dotnet', delivery: [nuget: [source: feed, credentialsId: 'nuget-key', releaseSource: releaseFeed, releaseCredentialsId: 'nuget-release-key']]],
                          files: nugetFiles])
check('.NET, delivery.nuget.releaseSource: stage 12 pushes the package to the release feed with its key and --skip-duplicate',
        !dotnetRelease.crash && !dotnetRelease.notRequired
                && nugetCalls(dotnetRelease) == ["sh dotnet nuget push 'dist/Orders.1.0.0.nupkg' --source '${releaseFeed}' --api-key \$NUGET_API_KEY --skip-duplicate".toString()]
                && (dotnetRelease.j as FakeScript).calls.contains('withCredentials [nuget-release-key]'),
        nugetCalls(dotnetRelease) + [dotnetRelease.crash])
Map dotnetSnapshotOnly = unit([cfg: [buildTool: 'dotnet', delivery: [nuget: [source: feed, credentialsId: 'nuget-key']]], files: nugetFiles])
check('.NET, no delivery.nuget.releaseSource: NOT_REQUIRED, the package stage 6 pushed is the delivered package and is not pushed twice',
        dotnetSnapshotOnly.notRequired && !nugetCalls(dotnetSnapshotOnly)
                && logOf(dotnetSnapshotOnly) == ["[NEXUS] svc: the NuGet package that ${stage6} pushed to ${feed} is the delivered package - set delivery.nuget.releaseSource to push it to a release feed as well".toString()],
        logOf(dotnetSnapshotOnly))
Map dotnetNoSnapshot = unit([cfg: [buildTool: 'dotnet', deployTarget: 'vm', deploy: [vm: [rd: [nexusDeliveryEnabled: false]]], delivery: [nuget: [source: feed, credentialsId: 'nuget-key']]],
                             files: nugetFiles])
check('.NET, Nexus delivery disabled: stage 12 pushes the package to delivery.nuget.source with its key, as before',
        !dotnetNoSnapshot.notRequired && nugetCalls(dotnetNoSnapshot) == ["sh dotnet nuget push 'dist/Orders.1.0.0.nupkg' --source '${feed}' --api-key \$NUGET_API_KEY".toString()]
                && (dotnetNoSnapshot.j as FakeScript).calls.contains('withCredentials [nuget-key]'),
        nugetCalls(dotnetNoSnapshot))
Map dotnetOpenShift = unit([cfg: [buildTool: 'dotnet', deployTarget: 'openshift', delivery: [nuget: [source: feed, credentialsId: 'nuget-key']]], files: nugetFiles])
Map dotnetOpenShiftRelease = unit([cfg  : [buildTool: 'dotnet', deployTarget: 'openshift', delivery: [nuget: [source: feed, credentialsId: 'nuget-key', releaseSource: releaseFeed]]],
                                   files: nugetFiles])
check('.NET on OpenShift, where stage 6 builds the image: stage 12 pushes the package to delivery.nuget.source, as before, and to the release feed when one is set',
        !dotnetOpenShift.notRequired && !dotnetOpenShiftRelease.notRequired
                && nugetCalls(dotnetOpenShift) == ["sh dotnet nuget push 'dist/Orders.1.0.0.nupkg' --source '${feed}' --api-key \$NUGET_API_KEY".toString()]
                && nugetCalls(dotnetOpenShiftRelease) == ["sh dotnet nuget push 'dist/Orders.1.0.0.nupkg' --source '${releaseFeed}' --api-key \$NUGET_API_KEY --skip-duplicate".toString()],
        nugetCalls(dotnetOpenShift) + nugetCalls(dotnetOpenShiftRelease) + [dotnetOpenShift.crash, dotnetOpenShiftRelease.crash])
Map dotnetExtended = unit([cfg: [buildTool: 'dotnet', delivery: [nuget: [source: feed, releaseSource: releaseFeed]]], variant: 'extended', files: nugetFiles])
Map dotnetNothing = unit([cfg: [buildTool: 'dotnet'], files: nugetFiles])
check('.NET, extended pipeline or no feed: NOT_REQUIRED and named, nothing is pushed',
        dotnetExtended.notRequired && dotnetNothing.notRequired && !nugetCalls(dotnetExtended) && !nugetCalls(dotnetNothing)
                && logOf(dotnetExtended) == ["[NEXUS] svc: the extended pipeline builds no NuGet package - the package that the security pipeline pushed to ${releaseFeed} stays the delivered package".toString()]
                && logOf(dotnetNothing) == ['[NEXUS] svc: delivery.nuget.source is not set in devsecops_config.yaml - no NuGet package is released'],
        logOf(dotnetExtended) + logOf(dotnetNothing))
Map dotnetWindows = unit([cfg  : [buildTool: 'dotnet', delivery: [nuget: [source: feed, credentialsId: 'nuget-key', releaseSource: releaseFeed]]], windows: true,
                          files: ['dist\\Orders.1.0.0.nupkg': 'nupkg']])
check('.NET on Windows with nuget.exe: -SkipDuplicate on the push to the release feed, with the key of delivery.nuget.credentialsId',
        nugetCalls(dotnetWindows) == ["powershell nuget.exe push 'dist\\Orders.1.0.0.nupkg' -Source '${releaseFeed}' -ApiKey \$env:NUGET_API_KEY -SkipDuplicate -NonInteractive".toString()]
                && (dotnetWindows.j as FakeScript).calls.contains('withCredentials [nuget-key]'),
        nugetCalls(dotnetWindows) + [dotnetWindows.crash])

println()
println(failures ? "${failures} RELEASE SCENARIO CHECK(S) FAILED" : 'ALL RELEASE SCENARIOS PASSED')
System.exit(failures ? 1 : 0)
