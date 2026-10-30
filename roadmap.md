# DevSecOps Jenkins Shared Library — Change Roadmap

A record of the work delivered on this library, grouped by theme and ordered as it was released. Each entry states what changed and why it mattered. Details for any item are in `documentation.confluence`, the complete single-page documentation of the library, and in the `documentation.html` and `documentation.md` rendered from it.

**Sections 1 to 8, delivered across six releases** — 30 files changed, six new components, and the automated check suite grown from 176 to 191 checks, all passing. Sections 9 to 12 bring the suite to 448 checks: 14 static checks, 11 query script scenarios, 164 sandbox scenarios, 205 pipeline scenarios, 47 portal scenarios and 7 compatibility scenarios, all passing.

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
| **Tests** | Golden runs recorded on the previous release pin every pipeline's steps, log, release gate, metrics and report; new scenarios cover every portal failure, the query script with the real `curl` against a local HTTP portal, the extended handoff and the compatibility comparison. The check suite then held 266 checks, all passing. |

## 10. Nexus IQ GoldenFix pipeline

| Change | Description |
|--------|-------------|
| **A fifth entry point** | `devSecOpsNexusIqGoldenFixPipeline` runs the dependency side of the flow on its own: source checkout, an artifact build and the Nexus IQ scan, after which GoldenFix raises the golden pull request with the safe versions when the policy is violated. It is configured by a pipeline key of the new portal pipeline type `nexusiq`, refuses a key of any other type and a service without a Nexus IQ application or scan patterns, so a green run always means a scan, and its Jenkins job is `DevSecOps/<CODE>/<service>-nexusiq`. The GoldenFix mechanism and the Nexus IQ stage are unchanged; the only new stage method is `buildArtifacts`. |
| **Its own report layout** | The report shows the checkout, the Build artifact and the Nexus IQ stages, one Nexus IQ row per project and the GoldenFix card with the pull request. It has no SonarQube rows and no release policy card, because the pipeline releases nothing, and the closing console line gives the Nexus IQ critical, high and medium counts instead of the SAST and DAST totals. |
| **Pull request in the metrics** | Runs are tagged `variant=nexusiq` and `project=<influx.project>nexusiq`. The `goldenfix` point carries `pr_url` and `pr_title` when a pull request was raised, so the portal and dashboards can link it; a point without a pull request is written exactly as before. The pipeline dashboard shows the Build artifact stage as `Build`. |
| **Tests** | The pipeline runs green, orange and with a failing build under the real sandbox; the orange run raises the pull request through the real wiring, and the three runs are pinned by new golden files, recorded when the pipeline was added and compared exactly. The portal scenarios refuse a mismatched key in either direction and a service the pipeline would not scan, and the demo reports gain a passing and a failing Nexus IQ GoldenFix page. The check suite then held 329 checks, all passing. |
| **Documentation** | A new chapter, *Integration Guide: Nexus IQ GoldenFix Pipeline*, follows the SAST chapter; the later chapters are renumbered, and the entry point tables, the stage, API and metrics references, the demo reports and the test suite description name the new pipeline. |

## 11. Service settings per service (2026-10-29)

Library pull request #6 and portal pull request #4.

| Change | Description |
|--------|-------------|
| **AppScan key per service** | SAST logs in with the key ID and the `asoc.token` secret of each service before its IRX is queued and before its results are read, and DAST signs in with the same credentials, instead of the shared `hcl-app-scan-acount` account. A service without `asoc.keyId` stops the build at the start. |
| **SAST scan name** | `sast.scanName` names the scan of a service in AppScan on Cloud. The IRX, the queue log, the scan ID file, the reports and the DAST scan name keep the service name. |
| **SonarQube badge credentials** | `tools.sonar.badgeCredentialsId` names a Secret text credential that wins over a literal `badgeToken`. A credential that cannot be read is logged with `[SONAR]` and leaves the stage result unchanged. |
| **Nexus delivery switch** | `deploy.<target>.rd.nexusDeliveryEnabled: false` turns off the snapshot delivery of a service; the build and the unit test reports still run. An OpenShift RD or QC deployment of such a service without a pinned build tag is `NOT_REQUIRED` instead of failing, and the `dora` point counts only deployments that passed. The release delivery is unchanged. |
| **Service forms in the portal** | A service can be imported from a `config.yaml` or copied from another service. The AppScan key moved to the services, and the forms gained the SAST scan name, the badge credentials and the Disable Nexus Delivery switch. Every field has a help text; the Library defaults are shown in the fields and saved as empty when they are not changed; the QC rules are marked where they apply; a service scans one Nexus IQ application; and the build agents are asked when the product is created. |
| **Tests** | New scenarios cover the per-service AppScan login, the scan name, the badge credentials (with a fake `withCredentials` that can fail) and the Nexus delivery switch. The DAST sign-in lines of five golden runs and the AppScan baseline were changed by hand for the intended change. The check suite then held 363 checks, all passing. |
| **Documentation** | The guide no longer names the shared AppScan account and documents the new keys. |

## 12. Shared version catalogs and an optional Node.js build

| Change | Description |
|--------|-------------|
| **Shared version catalogs in GoldenFix** | GoldenFix finds the catalogs of a repository by itself: every `*.versions.toml`, the catalogs a `settings.gradle` imports or declares, the files a build applies with `apply from`, `java-platform` modules, and the Maven parents and imported BOMs of the repository. Other shared files are listed in `goldenFix.versionCatalogs`. A version is raised in the catalog first, and a declaration of the same dependency elsewhere is aligned with it, named in the notes and labelled with the catalog in the pull request. A settings file changes only in its catalog block. What GoldenFix cannot raise, such as a range in a catalog, a `strictly` or `require` range that excludes the target in the version order of Gradle, a BOM, a catalog artifact or a constant in `buildSrc`, is named with its reason. |
| **One pull request per repository** | The services of one build that share a repository share one branch and one pull request. A later service adds its commit and a comment on Bitbucket Server or Bitbucket Cloud, never lowers a version, and links the pull request when a shared catalog on the branch already carries its upgrades. A push that collides with a parallel GoldenFix job of the same repository is named as such. |
| **Verification where the change takes effect** | A shared catalog is built in every build that uses it, and a Maven parent or BOM in the multi-module build above it, otherwise in its own folder, with `mvn -f` for a listed pom with another name. The build with the Nexus IQ version instead of a catalog version comes on top of `goldenFix.verify.maxAttempts`. The npm pre-check refreshes and commits the `package-lock.json` next to a changed `package.json`; an npm command of `goldenFix.verify.commands` runs as before, without the refresh. GoldenFix also raises npm `overrides` and yarn `resolutions`, and takes the name of a pub component from `name` or `packageId`. |
| **Optional Node.js build (stage 0)** | A service with `build.node.enabled` runs its npm commands, by default `npm ci` and `npm run build`, after Monitor in the full, security, SAST and Nexus IQ GoldenFix pipelines, and before the release publish of the extended pipeline. Node.js comes from `nodePath`, a Jenkins NodeJS installation or the agent `PATH`; npm reaches the registry or uses an `.npmrc` Secret file. The report numbers the stage 0, Grafana shows it as `Node.js`, the IRX leaves out `node_modules`, and the Nexus IQ scan adds the `package-lock.json` of the Node.js folder. The npm pre-check of GoldenFix runs in the same Node.js environment. Without a service that enables it the stage is skipped, and every golden run stays as it was. |
| **Portal** | The service forms offer the Node.js build of a frontend and the shared version catalogs. Services that share a repository are grouped under one Jenkinsfile and one job. |
| **Documentation** | `documentation.md`, GitHub Flavored Markdown without raw HTML, is now generated from `documentation.confluence` beside `documentation.html`, for Bitbucket, and the new `docs-md.sh` check keeps it current. The generator no longer leaks placeholders where code sits in bold text, keeps code spans that end in a brace, and keeps links in table cells. The manual gains the Node.js stage, the shared catalogs, the GoldenFix steps and a troubleshooting entry for every new message. |
| **Tests** | Scenarios cover every catalog form, the alignment and the reasons, the verification plan, the shared pull request with its comment, the lock file refresh, the overrides, the rejected push and the pub names, and the cases that must behave as on the main branch: a `resolutions` block of 300 entries on a 1 MB stack, indented TOML headers and quoted keys, a settings classpath, a service whose component comes from a BOM, an overridden npm command and the number of lowering attempts. A new golden run pins two services that share one catalog. Runs without a golden file cover the Node.js build in the full, SAST, Nexus IQ GoldenFix and extended pipelines, and a sandbox scenario covers it on a Windows agent. The check suite now holds 448 checks, all passing. |

---

## Known limitations

- The AppScan parser is matched to the standard HCL report layout; a materially different template would need a sample report to support.
- The GoldenFix pre-check compiles and resolves dependencies by default rather than running a full build with tests. A stronger check is one configuration setting away, at a cost in runtime.
- Upgrade candidates are limited to the versions Nexus IQ proposes; the library does not enumerate a component's full version history.
- Every build depends on the portal at its start, and the portal must sit behind BBH SSO with role-based access and an audit trail before the portal-integrated library is used outside test.
- The `security_findings` points with `scanner=niq` carry the Nexus IQ stage status with every count at 0, in every pipeline; the Nexus IQ counts are only in `vulnerabilities` with `scanner=nexusiq`. Correcting it changes the recorded golden runs of the security and full pipelines.
- SonarQube indexes Dart as an unknown language, so a Flutter project's quality gate rests on imported coverage and imported findings rather than on rules SonarQube executes itself.
- GoldenFix changes only the repository of the service. A catalog published as an artifact, a catalog in another repository, a BOM or parent resolved from Nexus and a version constant in `buildSrc` are named as the reason, not raised.
- Separate GoldenFix jobs for the services of one repository raise separate pull requests, and two of them that start in the same minute collide on the branch name. One job for the repository with `pipelineKeys` avoids both.
- The name of a pub component is taken from the Nexus IQ coordinates `name` or `packageId`; this has not been checked against a real Nexus IQ report of a pub component.
