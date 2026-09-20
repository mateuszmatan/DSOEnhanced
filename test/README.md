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
| `docs-html.sh` | `documentation.html` is the rendering of `DOCUMENTATION.md` (regenerates it when it is not) |

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

## The fake Jenkins

`test/sandbox/harness/devsecops/test`:

| Class | Role |
|-------|------|
| `FakeScript` | The pipeline steps: `sh`, `readFile`, `writeFile`, `readYaml`, `withCredentials`, `build`, `junit`, `archiveArtifacts`, … Each scenario decides what a step returns |
| `FakeCpsScript` | Base class of the loaded `vars` scripts: dispatches unknown calls to `FakeScript` like `CpsScript` does, and emulates the declarative `pipeline { }` block |
| `FakeOpenShift` | The `openshift` global variable of the OpenShift Client plugin |
| `SandboxHarness` | Compiles `src` and `vars` with the sandbox transformer and runs bodies inside the sandbox |
| `PipelineDslWhitelist`, `VarsWhitelist`, `LibraryClassesWhitelist` | What Jenkins permits for steps, global variables and library classes |

`test/sandbox/fixtures` holds in-memory port adapters for the GoldenFix end to end scenario.

## Adding a scenario

1. Add the data and the expected values to the scenario list of the matching script.
2. Run `bash test/run-all.sh`.
3. When the AppScan baseline changes on purpose, delete `test/sandbox/baseline/appscan-commands.txt`, run the suite again and review the regenerated file before committing it.
