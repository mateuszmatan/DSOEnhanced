# DevSecOps Jenkins Shared Library — Change Roadmap

A record of the work delivered on this library, grouped by theme and ordered as it was released. Each entry states what changed and why it mattered. Details for any item are in `documentation.confluence`, the complete single-page documentation of the library, and in the `documentation.html` rendered from it.

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
| **Single-page documentation** | The overview, the onboarding guide with a chapter per pipeline, the worked examples and the Grafana queries were consolidated into one document, replacing five separate files. The source is `documentation.confluence`, written in Confluence wiki markup so it can be published without conversion, and `documentation.html` is generated from it. |
| **Reference documentation** | The documentation, `defaults.yaml` and `config.yaml.template` were updated for every behavioural change, including the new configuration surface for report polling and pre-check commands. A chapter stating the business case for the shared pipeline, with published industry benchmarks, was added to the overview. |

## 9. Configuration from the DevSecOps portal

| Change | Description |
|--------|-------------|
| **Pipeline keys instead of config.yaml** | A Jenkinsfile now names only the pipeline key the DevSecOps portal issued for a service (`pipelineKey`, or `pipelineKeys` for several services, primary first). Every pipeline and step setting, and the global thresholds that lived in `resources/defaults.yaml`, come from the portal; `config.yaml`, `config.yaml.template` and `resources/defaults.yaml` are gone from the library. Invalidating a key in the portal stops its pipeline. |
| **Read-only HTTPS read** | `PortalConfigReader` asks the portal for the configuration of each key with one `GET /api/dso/config/<key>` per key, sent by `curl` from a Linux or macOS agent and asked again twice when the portal is briefly unavailable. Jenkins holds no database account, credentials or driver: the only setting is the portal address `DSO_PORTAL_URL`, and no key appears in any script or log. |
| **Fail-closed checks** | A missing, malformed, unknown or invalidated key, a key of another pipeline type or product, a service given twice, an unreachable portal and every portal error stop the build before it starts, each with a message that names what to do and whom to ask. |
| **Same configuration semantics** | The services are merged with the global defaults exactly as `config.yaml` was merged with `defaults.yaml`. The run-state file is now `pipeline-config.yaml`; the extended pipeline takes its own key's settings plus only the run-time build tags of its security run. A compatibility test runs the former loader beside the new one and proves both produce the same configuration. |
| **Platform values and secrets** | The AppScan, proxy and Nexus addresses and the iOS build agent come from the portal, with the agent's own environment still winning and today's values as the fallback. Remote test job tokens are bound from Jenkins credentials (`tokenCredentialsId`). |
| **Change evidence** | Each build records which configuration it used (key hint, rendering time, sha256 hint) in the console, the report header and a new `build_evidence` InfluxDB measurement, together with the artifact version, the SonarQube quality gate and the report links; unit test totals are written as `test_execution` with `suite=unit`. |
| **Tests** | Golden runs recorded on the previous release pin every pipeline's steps, log, release gate, metrics and report; new scenarios cover every portal failure, the query script with the real `curl` against a local HTTP portal, the extended handoff and the compatibility comparison. The check suite now holds 266 checks, all passing. |

---

## Known limitations

- The AppScan parser is matched to the standard HCL report layout; a materially different template would need a sample report to support.
- The GoldenFix pre-check compiles and resolves dependencies by default rather than running a full build with tests. A stronger check is one configuration setting away, at a cost in runtime.
- Upgrade candidates are limited to the versions Nexus IQ proposes; the library does not enumerate a component's full version history.
- Every build depends on the portal at its start, and the portal must sit behind BBH SSO with role-based access and an audit trail before the portal-integrated library is used outside test.
- SonarQube indexes Dart as an unknown language, so a Flutter project's quality gate rests on imported coverage and imported findings rather than on rules SonarQube executes itself.
