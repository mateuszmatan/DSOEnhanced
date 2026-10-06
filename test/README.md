# Library test suite

Everything runs with one command, without a Jenkins instance:

```bash
bash test/run-all.sh
```

The first run downloads the tools into `${DEVSECOPS_TOOLS:-$HOME/.cache/devsecops-library-tools}`: JDK 21, Groovy 2.4.21, the Jenkins 2.479.3 libraries and the `script-security` plugin (the same versions the customer runs). Later runs reuse them. The script prints `ALL CHECKS PASSED` when the library is releasable, and it regenerates `examples/reports` and `documentation.html` on the way.

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
| `jdk-whitelist.groovy` | Every JDK constructor, static method and static field used by the library is on the real script-security whitelist |
| `architecture.sh` | Adapters depend on the core and the ports, never the other way round |
| `vars-api.sh` | The shared methods resolve, the full pipeline equals security plus extended, and every manifest updater is wired into the GoldenFix service |
| `no-comments.sh` | No comments and no commented-out code in `src` and `vars` |
| `docs-html.sh` | `documentation.html` is the rendering of `documentation.confluence` (regenerates it when it is not) |

### Compilation

`src` and `vars` are compiled with the compiler configuration of the sandbox (`GroovySandbox.createSecureCompilerConfiguration`), so a syntax error or an unsupported construct fails here.

### Sandbox scenarios (`test/sandbox/SandboxScenarios.groovy`)

Runs the library classes inside `GroovySandbox.runInSandbox` with the real `generic-whitelist` and `jenkins-whitelist` and a fake Jenkins:

- every class initialises under the whitelist that applies while the CPS program is saved,
- the AppScan SAST and DAST calls run without a rejection and the generated shell and REST commands match `baseline/appscan-commands.txt`,
- the GoldenFix version choice follows the rules (the Nexus IQ Golden Version first, otherwise the nearest backward compatible version without violations),
- GoldenFix patches a real `pom.xml`, `build.gradle`, `gradle.properties`, `package.json`, `requirements.txt` and `pubspec.yaml` through the ports and raises the pull request,
- 200 smoke jobs run with at most `maxParallel` at a time and every one of them reaches the report,
- six service level pipelines (static security, SAST and full, each green and orange) produce `examples/reports`.

### Pipeline scenarios (`test/sandbox/PipelineScenarios.groovy`)

Loads the real `vars` under the sandbox with a fake declarative engine and runs all four entry points, green and orange: stage order, skipped stages, `post` conditions, the report, the release gate verdict, the archived artifacts, the InfluxDB metrics, the UrbanCode Deploy and OpenShift calls, the GoldenFix pull request and the trigger of the extended pipeline.

Every run is also compared with its golden file in `test/sandbox/golden`. A golden file holds the recorded steps (`node`, `sh`, `withCredentials`, `withEnv`, `writeYaml`, `copyArtifacts`, `archiveArtifacts`, ...), the build log without the `[INIT]` and `[POLICY]` lines, `release-gate.json`, the InfluxDB lines and the report. The files were recorded on the library before the DevSecOps portal integration (commit b815d55), so they pin today's behaviour. Values that depend on the wall clock are replaced before the comparison; the rules are the `baseRules` list of the script, each with its reason:

| Rule | Reason |
|------|--------|
| `yyyyMMdd-HHmmss` becomes `<stamp>` | build tags carry the UTC time of the run |
| 13 digit numbers become `<ms>` | temporary file names carry `System.currentTimeMillis()` |
| `yyyy-MM-dd HH:mm:ss` becomes `<time>` | the report header shows the wall-clock time of the run |
| the trailing epoch second of an InfluxDB line becomes `<s>` | InfluxDB points carry the epoch second of the run |
| `start_time`, `end_time`, `duration_ms`, `duration_s`, `lead_time_s` become `<n>` | stage and job timings are measured on the wall clock |
| `duration=...` in the log and rendered durations in the report become `<d>` | durations are measured on the wall clock |

When a run differs, the script writes `<name>.actual.txt` next to the golden file. Delete a golden file and run the suite again only when the behaviour changes on purpose, and review the new file before committing it.

## The fake Jenkins

`test/sandbox/harness/devsecops/test`:

| Class | Role |
|-------|------|
| `FakeScript` | The pipeline steps: `sh`, `readFile`, `writeFile`, `readYaml`, `withCredentials`, `build`, `junit`, `archiveArtifacts`, … Each scenario decides what a step returns |

The fake behaves like Jenkins where the library depends on it:

- `node` and the declarative `agent` set `NODE_NAME`, `WORKSPACE` and `WORKSPACE_TMP` only for their body, and `pwd()` fails outside a node; `pwd(tmp: true)` returns the workspace plus `@tmp`;
- `writeYaml` writes YAML into the in-memory workspace (and refuses to overwrite without `overwrite: true`), `readYaml(file:)` parses it and `fileExists` sees it;
- `copyArtifacts` copies the files that match the filter from the scenario's `upstream` map, records the filter and fails when nothing matches;
- `libraryResource` loads the real files under `resources/` and fails for a missing one; a scenario provides only the secret files that are not in the repository;
- `withEnv` and `withCredentials` set their variables only for their body;
- `isUnix()` is switchable (`unix`), and `readJSON` returns json-lib objects, like the real step, when `jsonLib` is set.
| `FakeCpsScript` | Base class of the loaded `vars` scripts: dispatches unknown calls to `FakeScript` like `CpsScript` does, and emulates the declarative `pipeline { }` block |
| `FakeOpenShift` | The `openshift` global variable of the OpenShift Client plugin |
| `SandboxHarness` | Compiles `src` and `vars` with the sandbox transformer and runs bodies inside the sandbox |
| `PipelineDslWhitelist`, `VarsWhitelist`, `LibraryClassesWhitelist` | What Jenkins permits for steps, global variables and library classes |

`test/sandbox/fixtures` holds in-memory port adapters for the GoldenFix end to end scenario.

## Adding a scenario

1. Add the data and the expected values to the scenario list of the matching script.
2. Run `bash test/run-all.sh`.
3. When the AppScan baseline changes on purpose, delete `test/sandbox/baseline/appscan-commands.txt`, run the suite again and review the regenerated file before committing it.
