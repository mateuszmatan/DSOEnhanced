# DevSecOps Jenkins Shared Library — Change Roadmap

A record of the work delivered on this library, grouped by theme and ordered as it was released. Each entry states what changed and why it mattered. Details for any item are in `DOCUMENTATION.md`; `library-overview.html` is the reader-friendly summary of the library as it now stands.

**Delivered across six releases** — 30 files changed, six new components, and the automated check suite grown from 176 to 191 checks, all passing.

---

## 1. Pipeline state integrity

| Change | Description |
|--------|-------------|
| **Single pipeline state per build** | Jenkins gives every shared-library global its own binding, so the stage layer and the reporting layer were each working against a separate, private copy of the pipeline state. Every stage now receives the entry point's own API instance, leaving exactly one state per build. |
| **Release gate restored** | The same defect meant the release gate evaluated an empty state and returned `allowed: true` with no violations on every run, so findings above policy never blocked a release or a QC deployment. Builds released while that defect was live should be reviewed. |
| **Report diagnostics** | The report now writes an explicit warning to the build log when it is handed a state no stage ever populated, so this class of failure announces itself instead of rendering a plausible-looking empty report. |

## 2. Security finding accuracy

| Change | Description |
|--------|-------------|
| **Single AppScan report parser** | SAST and DAST severity counts were read by two different parsers that disagreed, and a report whose findings live only in the summary table was counted as zero — a stage with real High findings reported green. Both paths now share one parser that reads the figures printed in the AppScan report itself. |
| **Count reconciliation at reporting time** | The archived report is re-read when the HTML report is produced; if it disagrees with what was recorded during the scan, the stage status, the Security Gates table and the release gate all follow the archived report and the discrepancy is logged. |
| **DAST report validation** | A failed report request previously wrote the error payload into the report file, which was then parsed as zero findings and passed the stage. The downloaded file is now validated as a genuine report, and a DAST stage can only be green when one was parsed. |
| **DAST report polling** | The fixed 30-second wait before download was replaced with polling against a configurable timeout, removing the timeout-driven failures on scans whose reports take longer to render. |

## 3. Automated dependency remediation (GoldenFix)

| Change | Description |
|--------|-------------|
| **Backward-compatible version policy** | Upgrade selection was rewritten to two explicit rules: only the patch version may change, and among those the newest version free of known vulnerabilities wins. A vendor-recommended version that would move the minor now loses to a plain patch upgrade. |
| **Pre-check build** | Proposed upgrades are applied in an isolated worktree and the project is built before anything is proposed; a failing build lowers the versions, reverts and retries, and if no version set builds, no pull request is opened at all. |
| **Full ecosystem coverage** | The pre-check now plans one check per changed manifest — Maven, Gradle, npm/Angular, pip/Python and Dart — each in its own directory, instead of only exercising the project's primary build tool. |
| **Remediation lookup performance** | Nexus IQ was queried once per component, each query costing three Jenkins steps executed serially. All lookups for a project now run in a single step with eight requests in flight, reducing a twenty-component project from sixty round trips to one. |
| **Resilience to incomplete Nexus IQ data** | Components that Nexus IQ returns without identification data aborted the whole remediation run. JSON null handling was corrected centrally, so partial data no longer prevents a pull request. |

## 4. Platform and toolchain support

| Change | Description |
|--------|-------------|
| **Flutter on SonarQube without a commercial plugin** | Flutter analysis depended on the paid `sonar-flutter` plugin and ran only on Windows agents. Coverage and static findings are now converted into SonarQube's generic import formats, which every edition supports, and the scanner runs on any agent operating system. |

## 5. Reporting and communication

| Change | Description |
|--------|-------------|
| **Plain-language remediation outcomes** | GoldenFix results were reported as raw status codes and exception text. Every outcome now opens with a sentence a non-specialist can act on, naming the missing configuration or the required follow-up, with technical detail kept as a secondary line. |
| **Coverage presentation** | The coverage line in the report was relabelled to state the governing standard explicitly. |

## 6. Architecture and code health

| Change | Description |
|--------|-------------|
| **Build verification behind a port** | Running the pre-check build was moved out of the remediation application service and behind a `BuildVerifier` port with a dedicated adapter, keeping shell execution and build-tool knowledge outside the domain layer in line with the library's ports-and-adapters structure. |
| **Redundancy removed** | Duplicated report parsing, string helpers and path helpers were consolidated into shared components, and report-shape knowledge was moved next to the parser that owns it. |
| **Packaging defect corrected** | An over-broad ignore rule excluded the library's own `build` package from version control, leaving a class that the library imports absent from the published repository; the rule was anchored and the file restored. |

## 7. Test infrastructure

| Change | Description |
|--------|-------------|
| **Harness fidelity** | The sandbox harness resolved every shared-library global from one shared map, which is not how Jenkins behaves and is why the state-integrity defect passed every test. It now reproduces Jenkins' per-binding resolution, and reverting the fix reproduces the reported failure exactly. |
| **Regression coverage** | Fifteen checks were added covering the defects above — empty reports, report validation, version selection, pre-check outcomes, ecosystem planning and the Flutter converters — so each remains a build failure if reintroduced. |
| **Static guards** | The API check now fails any pipeline that does not pass its own API instance to the stage layer, preventing the state-integrity defect from returning by construction. |

## 8. Documentation

| Change | Description |
|--------|-------------|
| **`library-overview.html`** | A new standalone page describing the library's capabilities and everything an application needs before adopting it, written for readers who are not pipeline engineers. |
| **Reference documentation** | `DOCUMENTATION.md`, the generated `documentation.html`, `defaults.yaml` and `config.yaml.template` were updated for every behavioural change, including the new configuration surface for report polling and pre-check commands. |

---

## Known limitations

- The AppScan parser is matched to the standard HCL report layout; a materially different template would need a sample report to support.
- The GoldenFix pre-check compiles and resolves dependencies by default rather than running a full build with tests. A stronger check is one configuration setting away, at a cost in runtime.
- Upgrade candidates are limited to the versions Nexus IQ proposes; the library does not enumerate a component's full version history.
- SonarQube indexes Dart as an unknown language, so a Flutter project's quality gate rests on imported coverage and imported findings rather than on rules SonarQube executes itself.
