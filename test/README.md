# Library test suite

Everything runs with one command, without a Jenkins instance:

```bash
bash test/run-all.sh
```

The first run downloads the tools into `${DEVSECOPS_TOOLS:-$HOME/.cache/devsecops-library-tools}`: JDK 21, Groovy 2.4.21, the Jenkins 2.479.3 libraries and the `script-security` plugin (the same versions the customer runs). Later runs reuse them. The script prints `ALL CHECKS PASSED` when the library is releasable, and it regenerates `examples/reports`, `documentation.html` and `documentation.md` on the way.

## What runs

### Static checks (`test/checks`)

| Check | What it proves |
|-------|----------------|
| `sandbox-lint.sh` | No construct that needs an administrator approval, no static field, no `map."${key}"` |
| `undefined-calls.sh` | Every implicit call resolves to a declared method |
| `undefined-variables.groovy` | Every name in a library class is a local, a parameter or a field (catches `steps.error` in a class that has `script`) |
| `api-consistency.sh` | Every call on a library type matches a method with that argument count |
| `closure-calls.groovy` | No unqualified call inside a closure of a static method |
| `closure-field-writes.groovy` | No closure assigns a field of its own class |
| `noncps-calls.groovy` | No `@NonCPS` method calls a library method that is CPS-transformed (Jenkins would run it with the wrong result) |
| `jdk-whitelist.groovy` | Every JDK constructor, static method and static field used by the library is on the real script-security whitelist |
| `architecture.sh` | Adapters depend on the core and the ports, never the other way round |
| `vars-api.sh` | The shared methods resolve, every entry point declares its stages in order (the full pipeline equals security plus extended), and every manifest updater is wired into the GoldenFix service |
| `no-comments.sh` | No comments and no commented-out code in `src` and `vars` |
| `ascii.sh` | `src` and `vars` hold printable ASCII, tabs and newlines only |
| `docs-html.sh` | `documentation.html` is the rendering of `documentation.confluence` (regenerates it when it is not) |
| `docs-md.sh` | `documentation.md` is the Markdown rendering of `documentation.confluence` for Bitbucket (regenerates it when it is not) |

### Compilation

`src` and `vars` are compiled with the compiler configuration of the sandbox (`GroovySandbox.createSecureCompilerConfiguration`), so a syntax error or an unsupported construct fails here.

### The portal query script (`test/portal/query-scenarios.sh`)

Takes the query script out of `PortalConfigReader.groovy` and runs it with `sh` and the real `curl` against a small HTTP portal written in Python on `127.0.0.1`, which answers only `GET <path>/api/dso/config/<key>?format=json` with `Accept: application/json`. The scenarios check: one status, body and error file per key in key order, in a directory only the agent user can read; HTTP 200 with the sha256 hint of the body and the body byte for byte, UTF-8 included; the 403 and 404 problem details of the portal; a portal that answers 503 twice and is asked again until it answers; an unreachable portal reported as status `000` after three attempts, with the `curl` errors and without the key. The retries make this step take about 20 seconds.

### Sandbox scenarios (`test/sandbox/SandboxScenarios.groovy`)

Runs the library classes inside `GroovySandbox.runInSandbox` with the real `generic-whitelist` and `jenkins-whitelist` and a fake Jenkins:

- every class initialises under the whitelist that applies while the CPS program is saved,
- the AppScan SAST and DAST calls run without a rejection and the generated shell and REST commands match `baseline/appscan-commands.txt`,
- the GoldenFix version choice follows the rules (the Nexus IQ Golden Version first, otherwise the nearest backward compatible version without violations),
- GoldenFix patches a real `pom.xml`, `build.gradle`, `gradle.properties`, `package.json`, `requirements.txt` and `pubspec.yaml` through the ports and raises the pull request,
- GoldenFix raises shared version catalogs: every `[versions]` and `[libraries]` form of a Gradle TOML catalog, the `settings.gradle` catalog DSL, imported and listed catalogs, script plugins found through `apply from`, `java-platform` modules and a Maven parent or imported BOM of the repository; it aligns inline declarations with the catalog, names a version held in `buildSrc`, an imported BOM or a catalog artifact outside the repository, verifies each shared file once in every build that uses it, raises one pull request for the services that share a catalog, comments on it for the later service and links a service whose versions it already carries (and only those the pull request changed),
- the GoldenFix edge cases: a catalog of 300 libraries and an npm `resolutions` block of 300 entries raised on a thread with a 1 MB stack (the size of a Jenkins CPS thread), section headers with a trailing comment and TOML literal strings in single quotes, a `strictly` range that excludes the target (left alone, with the reason; its bounds ordered as Gradle orders versions, so `32.0.0-jre` lies above `32.0`), listed catalogs written with `./`, `//` or a trailing `/`, settings imports written as `from files(..)` or with a `$rootDir` path, one row for a property raised in both rounds, a pre-check that fails with the catalog version and is tried again with the Nexus IQ version, and an `Error` that escapes the remediation (recorded as `ERROR` with its class name),
- GoldenFix refreshes the sibling `package-lock.json` in the npm pre-check, raises npm `overrides` and `resolutions`, tells a push rejected because the branch exists from one that a hook or a branch permission declines (from the output of `git push`), and names pub components by `packageId` or `name`,
- the `package-lock.json` pattern that a Node.js build adds to the Nexus IQ scan names the folder without `./`, `//` or a trailing `/`, and a single scan pattern also gets it,
- 200 smoke jobs run with at most `maxParallel` at a time and every one of them reaches the report,
- the `goldenfix` InfluxDB point carries `pr_url` and `pr_title` when a pull request was raised and leaves them out otherwise,
- the Node.js build runner puts the Node.js home on `PATH` (the home itself on Windows, where each line of `build.node.commands` runs with `bat` in `build.node.dir`); with `build.node.enabled` the npm pre-check of GoldenFix runs inside that environment (installation, `.npmrc` credentials instead of the registry export), handed over by `GoldenFixService`, while the Gradle check and a service without it run as before,
- eight service level pipelines (static security, SAST, Nexus IQ GoldenFix and full, each green and orange) produce `examples/reports`; the Nexus IQ GoldenFix pages show the Build artifact and Nexus IQ stages and, when orange, the GoldenFix card, and no SAST, SonarQube, test or release policy content.

### Pipeline scenarios (`test/sandbox/PipelineScenarios.groovy`)

Loads the real `vars` under the sandbox with a fake declarative engine and runs all five entry points, green and orange, and the Nexus IQ GoldenFix pipeline also with a failing build: stage order, skipped stages, `post` conditions, the report, the release gate verdict, the archived artifacts, the InfluxDB metrics, the UrbanCode Deploy and OpenShift calls, the GoldenFix pull request and the trigger of the extended pipeline. The `Node.js build` stage of the full, security, SAST and Nexus IQ GoldenFix pipelines is in the `skipped` list of these runs: its `when` guard (`devSecOpsApi.nodeBuildRequired()`) is false when no service sets `build.node.enabled`, runs no step and logs nothing, so the golden files stay as they were.

The orange run of the Nexus IQ GoldenFix pipeline raises the golden pull request through the real wiring of `devSecOpsApi`: the Nexus IQ policy report and the batched remediation lookups, the GoldenFix worktree, the pre-check build of each changed manifest, the commit, the push and the Bitbucket pull request, answered by `GoldenFixResponder`. The scenario also checks that the checkout and the artifact build run before the Nexus IQ evaluation, that the report holds no SAST, SonarQube, test or release policy content, that a failing build stops the pipeline before Nexus IQ evaluates anything while the report keeps its Nexus IQ row and no SonarQube row, and that the InfluxDB lines carry `variant=nexusiq`, the project tag with `nexusiq` appended, and `pr_url` and `pr_title` on the `goldenfix` point. The shared catalog run gives `gui` and `backend-api` one root catalog `gradle/libs.versions.toml`: both raise it in one pull request, verified in the root build, `backend-api` comments on the pull request with its upgrade and links the upgrade `gui` already raised.

Every run is also compared with its golden file in `test/sandbox/golden`. A golden file holds the recorded steps (`node`, `sh`, `withCredentials`, `withEnv`, `writeYaml`, `copyArtifacts`, `archiveArtifacts`, ...), the build log without the `[INIT]` and `[POLICY]` lines, `release-gate.json`, the InfluxDB lines and the report. The eleven files of the full, security, extended and SAST pipelines were recorded on the library before the DevSecOps portal integration (commit b815d55), so they pin the behaviour of that release, except for the InfluxDB upload: its `sh curl` and `sh rm` lines were replaced by hand with the `withEnv`, `sh` and `sh rm` lines of the current upload, which passes the address, the payload and response files and the token through the environment, prints the answer of InfluxDB, fails on a `curl` error or an HTTP status of 300 or more, and removes both files, and for the DAST sign-in: its `withCredentials [hcl-app-scan-acount]` and `Account/ApiKeyLogin` lines were replaced by hand with the credentials and the key ID of the service, because DAST now signs in with the AppScan key of each service instead of one shared account. The three files of the Nexus IQ GoldenFix pipeline (`nexus-iq-goldenfix-pipeline-green.txt`, `-orange.txt` and `-build-failure.txt`) were recorded with `RECORD_GOLDEN=1` when that pipeline was added and reviewed by hand; they pin its behaviour from then on and are not evidence of the earlier release. `nexus-iq-goldenfix-pipeline-orange.txt` was recorded again when shared version catalogs were added; only the `find` pattern list of the manifest search changed, which now also names `settings.gradle` and `settings.gradle.kts`. `nexus-iq-goldenfix-pipeline-shared-catalog.txt` was recorded with `RECORD_GOLDEN=1` at that time and reviewed by hand. Both files were recorded again when the library started to read the answer of `git push`: only the `git push` line of the `GoldenFix: git push` step changed, which now ends with `2>&1 | tee '<worktree>.push.log'` and is followed by `rm -f` of that file, so that a branch that already exists can be told from a push that a hook or a branch permission declines. The `baselines` map of the script names, for every golden, when it was recorded and which extra rules apply. Values that depend on the wall clock are replaced before every comparison; the rules are the `baseRules` list of the script, each with its reason:

| Rule | Reason |
|------|--------|
| `yyyyMMdd-HHmmss` becomes `<stamp>` | build tags carry the UTC time of the run |
| 13 digit numbers become `<ms>` | temporary file names carry `System.currentTimeMillis()` |
| `yyyy-MM-dd HH:mm:ss` becomes `<time>` | the report header shows the wall-clock time of the run |
| the trailing epoch second of an InfluxDB line becomes `<s>` | InfluxDB points carry the epoch second of the run |
| `start_time`, `end_time`, `duration_ms`, `duration_s`, `lead_time_s` become `<n>` | stage and job timings are measured on the wall clock |
| `duration=...` in the log and rendered durations in the report become `<d>` | durations are measured on the wall clock |
| `yyyy-MM-ddTHH:mm:ssZ` becomes `<time>` | the portal documents are stamped with the UTC time they were read |

The runs on the portal branch read the same values from portal documents (built from `test/fixtures/defaults.yaml` and `test/fixtures/CertScanner/config.yaml`) instead of `config.yaml` and `defaults.yaml`. Before the comparison with a b815d55 golden both sides also pass through these rules; the Nexus IQ GoldenFix goldens are compared with the base rules only, so the portal read, the run-state file, `build_evidence` and the report header are pinned exactly:

| Rule | Reason |
|------|--------|
| the steps before the pipeline's own `agent` are dropped | `configure()` reads the portal on a bootstrap agent (`node`, `withEnv`, the query `sh`, `deleteDir`) before `pipeline {}` starts |
| `[PORTAL]` log lines are dropped | the portal read logs one line per key |
| the `writeYaml pipeline-config.yaml` right after the commit is read is dropped | `ConfigLoader.initialize()` writes the run-state file at the start of the Monitor stage |
| `config.yaml` becomes `pipeline-config.yaml` | the archived and copied run-state file is renamed (accepted difference 3) |
| `build_evidence` lines and `test_execution` lines with `suite=unit` are dropped, and the logged number of InfluxDB lines becomes `<n>` | the change evidence for the portal is new data (L10) |
| the `DevSecOps portal: key ..., rendered ..., sha256 ...` part of the report header is dropped | the report header names the documents the run used (accepted difference 1) |

When a run differs, the script writes `<name>.actual.txt` next to the golden file. A missing golden file fails the check. Record a new one with `RECORD_GOLDEN=1` only when the behaviour changes on purpose, and review the new file before committing it.

More runs have no golden file. Each changes the CertScanner configuration through `configure`, and the Library defaults through `defaults`, and checks one feature:

- AppScan settings per service: SAST logs in with the key and the secret of each service (`asoc.token`, or the credentials named like `asoc.keyId` without it) before its IRX is queued and before its results are read, DAST signs in with the same credentials, no step names `hcl-app-scan-acount`, and `sast.scanName` names the SAST scan of its service while the IRX, the queue log, the scan ID file, the report names and the DAST scan name keep `APPSCAN_SCAN_NAME`;
- the SonarQube badge token: `tools.sonar.badgeCredentialsId` is read as Secret text and wins over a literal `badgeToken`; when the credentials cannot be read (`missingCredentials`), the run logs a `[SONAR]` line, falls back to `badgeToken` or shows no badges, and the SCA stage result stays `PASS`;
- `deploy.<target>.rd.nexusDeliveryEnabled: false`: for every service, the snapshot stage is `NOT_REQUIRED`, publishes nothing and still reports the build and the unit tests, and a pinned `rd.buildTag` is still deployed; for the only service, the snapshot, RD and QC stages are `NOT_REQUIRED`, nothing is rolled out and the `dora` point counts no deployment; for one of two services, the stages stay `PASS`;
- the Node.js build (`build.node`): in the full pipeline for one of two services, the stage runs after Monitor with the Library default installation and registry and the `env` of the service, once and before the first Gradle build, the other service logs its `[NODE]` not-required line, the report numbers the stage `0.1` and `0.2` while Unit tests keeps `2.x`, InfluxDB gets its `stage_event`, the release gate allows and `nexusIqScan: false` keeps the scan patterns; a failing command fails the stage, skips the later stages and the report shows Unit tests numbered 2 and blocked; in the SAST pipeline it runs before the SAST compile, a service with `asoc.doCompile: false` is not built, every IRX excludes `node_modules` whether the Node.js service comes first or last and keeps its folders when `includedDirs` is set, `nodePath` wins over a Library default installation, `.npmrc` credentials replace the registry export and Library defaults alone build nothing; in the Nexus IQ GoldenFix pipeline it runs before Build artifact and `<dir>/package-lock.json` joins the scan patterns of that service only; in the extended pipeline the release delivery builds Node.js once, before the publish of the service that enables it.

### DevSecOps portal scenarios (`test/sandbox/PortalScenarios.groovy`)

Drives `ConfigLoader.load()` and `initialize()`, the entry points and `devSecOpsApi` against the fake portal: a missing, malformed, unknown or revoked key; a missing key in the Nexus IQ GoldenFix entry point (the message names its call and its variant); an empty answer; a key of another pipeline type or product, including a Nexus IQ GoldenFix key in another entry point and another key in the Nexus IQ GoldenFix entry point; a service the Nexus IQ GoldenFix pipeline would not scan (no Nexus IQ application or no scan patterns), which stops it at the start and no other pipeline; two keys for one service; the AppScan key of each service (`APPSCAN_KEY_ID` follows `switchProject`, and a service without `asoc.keyId` stops the build at the start, also when it is not the primary one); several keys (the first is the primary project, the order is kept in `PROJECT_NAMES`, `projectsAllCfg` and `pipeline-config.yaml`, and the InfluxDB metrics carry the settings of the primary project after the last service ran; the upload passes the address, the payload and response files and the token to `curl` through the environment, prints the answer of InfluxDB, fails on a `curl` error or an HTTP status of 300 or more and removes both files, on Linux and on Windows); `initialize()` without `configure()`; a missing `DSO_PORTAL_URL` and addresses with a user, a query or no scheme (never repeated in the message); a trailing slash; an unreachable portal, an untrusted certificate, a router without a portal, a wrong address and a failing portal (one message with the cause, the address and the DevSecOps team, and the build description says the portal is unavailable); a `curl` error that holds the key; an answer that is not JSON; a Windows bootstrap agent; the read in place inside a `node`; the bootstrap agent `linux-agent` outside a node; the recorded query script (`#!/bin/sh`, `set +x`, no key, one GET per key with retries); the extended handoff (the overlay of the run-time tags, a missing `pipeline-config.yaml`, a service the security run did not build, a tag that is not a plain tag); a standalone extended pipeline; an agent-level `PROXY_HOST` over the portal value; and remote test jobs with `tokenCredentialsId`.

### Compatibility scenarios (`test/sandbox/CompatibilityScenarios.groovy`)

Runs the `ConfigLoader` of commit b815d55 (copied to `test/fixtures/b815d55`, compiled ahead of `src`) over `config.yaml` and `defaults.yaml`, and the portal `ConfigLoader` over portal documents, and compares what each leaves behind: `projectsAllCfg` and its order, `cfgDefaults`, `cfg`, the policy limits, the coverage minimum, the primary project, the run-state file, `PROJECT_NAMES`, `CURRENT_PROJECT_NAME`, `APPSCAN_SCAN_NAME` and `APPSCAN_KEY_ID`.

- A, exact: documents that carry the same values as the two files, for the full and the security variant. Only the `[INIT]` and `[POLICY]` log lines go through two rules: the `[PORTAL]` lines are dropped and the `[POLICY]` header names the portal (L9).
- B, normalised: the documents in `test/fixtures/portal/rendered`, which hold that `config.yaml` as the portal renders it. Both sides pass through the rules below before the comparison; the script prints each rule with the file and line of the code that reads the key, checks that every rule changes something, and checks that a key the portal dropped or a non-empty value of a listed key is still caught.

| Rule | Reason |
|------|--------|
| R1: drop `asoc.keySecret`, `sca.scanName`, `dast.scanName`, `build.maven.javaPath` | DEAD keys that no code reads |
| R2: `""`, `[]` and `false` count as absent for `dast.presenceId`, `scm.bitbucket.targetBranch`, `scm.bitbucket.reviewers`, `asoc.insecureTls`, `tools.nexusIq.failOnNetworkError`, `goldenFix.timeZone` and `goldenFix.verify.commands.<kind>` only | each reader treats them like a missing key |
| R3: `tests.<suite>` (all but `unitTests`) as `BuildService.normalizeTestJobs` expands it | the portal stores the expanded jobs (accepted difference 5) |
| R4: `jenkins.pipeline.extendedPipeline` is dropped outside the security variant | only the security pipeline triggers the extended job |
| R5: `incrementalVersion: true` of an UrbanCode Deploy component is dropped | it equals the code default |
| R6: a literal `influx.token` counts as `influx.credentialsId: influxdb-token` | the portal names the Secret text credentials instead of storing the token |

Documents exported from a real portal for the CertScanner demo product replace the files in `rendered` and must pass the same comparison.

## The fake Jenkins

`test/sandbox/harness/devsecops/test`:

| Class | Role |
|-------|------|
| `FakeScript` | The pipeline steps: `sh`, `readFile`, `writeFile`, `readYaml`, `withCredentials`, `build`, `junit`, `archiveArtifacts`, … Each scenario decides what a step returns |
| `FakeCpsScript` | Base class of the loaded `vars` scripts: dispatches unknown calls to `FakeScript` like `CpsScript` does, and emulates the declarative `pipeline { }` block |
| `FakeOpenShift` | The `openshift` global variable of the OpenShift Client plugin |
| `SandboxHarness` | Compiles `src` and `vars` with the sandbox transformer and runs bodies inside the sandbox |
| `PipelineDslWhitelist`, `VarsWhitelist`, `LibraryClassesWhitelist` | What Jenkins permits for steps, global variables and library classes |
| `PortalFixtures` | Builds the DevSecOps portal documents of a scenario and publishes them under generated pipeline keys |
| `GoldenFixResponder` | Answers the GoldenFix shell steps and the Nexus IQ and Bitbucket REST calls of the CertScanner `gui` service (policy report, remediation versions, worktree manifests, commit, pull request), and of `gui` and `backend-api` sharing one version catalog (`SHARED_CATALOG`, `SHARED_CATALOG_REPORTS`, the pull request comment); shared by the sandbox and the pipeline scenarios |

The fake behaves like Jenkins where the library depends on it:

- `node` and the declarative `agent` set `NODE_NAME`, `WORKSPACE` and `WORKSPACE_TMP` only for their body, and `pwd()` fails outside a node; `pwd(tmp: true)` returns the workspace plus `@tmp`;
- `writeYaml` writes YAML into the in-memory workspace (and refuses to overwrite without `overwrite: true`), `readYaml(file:)` parses it and `fileExists` sees it;
- `copyArtifacts` copies the files that match the filter from the scenario's `upstream` map, records the filter and fails when nothing matches;
- `libraryResource` loads the real files under `resources/` and fails for a missing one; a scenario provides only the secret files that are not in the repository;
- `withEnv` and `withCredentials` set their variables only for their body (`string`, `usernamePassword` and `file` credentials), and `withCredentials` fails like Jenkins for a credentials ID listed in `missingCredentials`;
- `isUnix()` is switchable (`unix`), and `readJSON` returns json-lib objects (JSONObject, JSONArray, JSONNull) like the real step; set `jsonLib = false` for plain Groovy maps;
- `dir` and `deleteDir` remove the files below a directory;
- a `sh` script that calls `/api/dso/config/` is answered by the fake portal: for each key in `DSO_PORTAL_KEYS` it writes the status, body and error files below `DSO_PORTAL_DIR` as the portal would answer from the scenario's `portal` map (key to key status, reason and configuration: 200, the 403 problem or the 404 problem), or from `portalAnswer` when a scenario wants another status, a `curl` error or a broken answer.

`test/sandbox/fixtures` holds in-memory port adapters for the GoldenFix end to end scenario. `InMemorySourceRepository` matches a pattern with a `/` against the whole path and skips the `excludeDirs` folders like `find`; `RecordingPullRequestPublisher` records the pull request comments (`comments`) and fails them when `commentFailure` is set.

`test/fixtures` holds the configuration files of the library before the portal integration: `defaults.yaml` (formerly `resources/defaults.yaml`), `CertScanner/config.yaml` (formerly `examples/CertScanner/config.yaml`) and `b815d55/com/bbh/config/ConfigLoader.groovy`, the loader that read them. `portal/` holds documents a real DevSecOps portal rendered for its demo services, and `portal/rendered` the CertScanner `config.yaml` as the portal renders it. The pipeline and sandbox scenarios build their portal documents from `defaults.yaml` and `config.yaml`, so every pipeline run reads the same values as before.

## Adding a scenario

1. Add the data and the expected values to the scenario list of the matching script.
2. Run `bash test/run-all.sh`.
3. When the AppScan baseline changes on purpose, delete `test/sandbox/baseline/appscan-commands.txt`, run the suite again and review the regenerated file before committing it.
