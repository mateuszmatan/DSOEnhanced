# DevSecOps Jenkins Shared Library - Documentation

*Complete documentation, generated from `documentation.confluence` by `tools/build-documentation-html.py`; the same content is published as `documentation.html` and in Confluence. Edit the source, not this file.*

Jenkins Shared Library implementing the Brown Brothers Harriman DevSecOps delivery pipeline: build, unit testing with coverage enforcement, dependency composition analysis (Nexus IQ), static code analysis (SonarQube), static and dynamic application security testing (HCL AppScan), artifact delivery (Nexus), deployment to virtual machine and OpenShift targets, functional and performance testing, HTML reporting, automated dependency remediation and InfluxDB telemetry.

This document is the complete documentation for the library. Part I states the purpose, business case and capabilities of the solution. Part II is the procedure for integrating a new application. Part III is the configuration and behaviour reference. Part IV covers operational monitoring and fault diagnosis. Part V is intended for engineers modifying the library itself.

> **Note:** **This is the `DSOEnhancedWithoutPortal` branch, DevSecOps version 0.** A pipeline is configured by its `Jenkinsfile` and the `devsecops_config.yaml` of its repository only, with the global settings that the library bundles. The `main` branch of the repository is the release with the DevSecOps portal. The features of `main` are carried over to this branch.

## Contents

**Part I - High Level Overview**

- [1. High Level Overview](#1-high-level-overview)
- [2. Benefits of DevSecOps at BBH](#2-benefits-of-devsecops-at-bbh)
- [3. DevSecOps Library Features](#3-devsecops-library-features)
- [4. Solution Architecture](#4-solution-architecture)

**Part II - Application Integration Guide**

- [5. Application Prerequisites](#5-application-prerequisites)
- [6. Platform Prerequisites and the BBH Toolchain](#6-platform-prerequisites-and-the-bbh-toolchain)
- [7. Third-Party System Registration Procedure](#7-third-party-system-registration-procedure)
- [8. Detailed Description Guide for Application Integration](#8-detailed-description-guide-for-application-integration)
- [9. Integration Guide: Full Pipeline](#9-integration-guide-full-pipeline)
- [10. Integration Guide: Security Pipeline](#10-integration-guide-security-pipeline)
- [11. Integration Guide: Extended Pipeline](#11-integration-guide-extended-pipeline)
- [12. Integration Guide: SAST Scanning Pipeline](#12-integration-guide-sast-scanning-pipeline)
- [13. Integration Guide: Nexus IQ GoldenFix Pipeline](#13-integration-guide-nexus-iq-goldenfix-pipeline)
- [14. Reference Implementation Examples](#14-reference-implementation-examples)

**Part III - Configuration and Behaviour Reference**

- [15. Configuration Reference: devsecops_config.yaml](#15-configuration-reference-devsecops_configyaml)
- [16. Pipeline Stage Reference](#16-pipeline-stage-reference)
- [17. Security Policy Thresholds](#17-security-policy-thresholds)
- [18. Supported Build Tools](#18-supported-build-tools)
- [19. Supported Deployment Targets](#19-supported-deployment-targets)
- [20. Programmatic API Reference](#20-programmatic-api-reference)

**Part IV - Operations**

- [21. Monitoring and Metrics](#21-monitoring-and-metrics)
- [22. Grafana Dashboard Query Reference](#22-grafana-dashboard-query-reference)
- [23. Troubleshooting Guide](#23-troubleshooting-guide)

**Part V - Library Development**

- [24. Library Development and Test Suite](#24-library-development-and-test-suite)

## 1. High Level Overview

The library executes the following pipeline automatically when you call `devSecOpsPipeline()` from your Jenkinsfile:

| # | Stage | What it does |
|---|---|---|
| 1 | Monitor source changes | Reads `devsecops_config.yaml` (else `config.yaml`) of the repository, merges its services with the global defaults that the library bundles and sets the tool environment |
| 0 | Node.js build (optional) | Runs the npm commands of each service that sets `build.node.enabled: true`, for example to build an Angular frontend, before the Gradle or Maven build; the stage is left out when no service of the run sets it |
| 2 | Unit tests | Build tool tests, then the line coverage of every JaCoCo and LCOV report of the service, whose mean is checked against the library minimum; a failed test run or missing coverage turns the stage orange and the pipeline continues |
| 3 | Dependencies scan (Nexus IQ) | Dependency scan, an orange stage on a policy violation and an automatic GoldenFix pull request |
| 4 | SAST – HCL AppScan | Static Application Security Testing: compile, IRX archive and scan, queued per project |
| 5 | SCA (SonarQube) | Static code analysis and quality gate |
| 6 | Nexus delivery (static analysis passed) | Publish the snapshot artifact, even when an earlier stage is orange, unless `deploy.<target>.rd.nexusDeliveryEnabled` is false for the service |
| 7 | Lower test region deployment | Deploy to the lower test region: VM with UrbanCode Deploy (`devSecOpsPipeline`) or over SSH (`devSecOpsExtendedPipeline`), or OpenShift |
| 8 | Regression tests | Trigger regression jobs, at least one job must be configured |
| 9 | Smoke tests | Trigger smoke jobs, at least one job must be configured |
| 10 | Performance tests | Trigger performance jobs, at least one job must be configured |
| 11 | DAST – HCL AppScan | Dynamic Application Security Testing, HTML report, HCL console link and PDF report |
| 12 | Nexus delivery (safe artifact) | Publish the release artifact, blocked when any earlier stage is orange |
| 13 | Higher test environment deployment | Deploy to QC, only when every earlier stage is green and the checkbox is selected |

The Node.js build runs after stage 1 and before stage 2. The report numbers it 0, so every other stage keeps its number whether a run has a Node.js build or not.

The full and security pipelines declare one more Jenkins stage, **Static analysis**. When `staticAnalysis.parallel` is `true` for every service of the run, set in `devsecops_config.yaml` or in the bundled defaults, or the environment variable `DSO_STATIC_ANALYSIS_PARALLEL` is `true`, it runs stages 3, 4 and 5: SAST sends every service to AppScan first, Nexus IQ and SonarQube run while AppScan scans, and SAST reads the results last. Jenkins then shows the three stages as skipped; otherwise it shows **Static analysis** as skipped. The report, the metrics and the release gate always show stages 3, 4 and 5 with their numbers.

### Which pipeline to use

The library ships five entry points. They share the same services, thresholds, release gate and report, and all of them are built from the same stage methods in `vars/devSecOpsSteps.groovy`. Each stage method takes the `devSecOpsApi` instance of its entry point as the first argument, for the reason explained in [one devSecOpsApi instance per build](#20-programmatic-api-reference).

| Entry point | Runs | Use it when |
|---|---|---|
| `devSecOpsPipeline` | All thirteen stages in one build, after the optional Node.js build: unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery, RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment | One job should cover the whole flow |
| `devSecOpsSecurityPipeline` | The optional Node.js build, unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery; archives `pipeline-config.yaml` and the release gate verdict, and triggers the extended job when `RUN_EXTENDED_PIPELINE` is selected | The static part runs on every commit and the rest is a separate job |
| `devSecOpsExtendedPipeline` | RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment; it has no Node.js build stage, and its release delivery runs the Node.js build of a service again before it publishes | Downstream of the security pipeline, which its Jenkinsfile names in `securityPipeline`; it inherits the release verdict through the copied `release-gate.json` |
| `devSecOpsSASTScanningPipeline` | Monitor sources, the optional Node.js build and SAST | A job that executes the AppScan static scan only; its report contains the SAST stage and the SAST findings |
| `devSecOpsNexusIqGoldenFixPipeline` | Monitor sources, the optional Node.js build, artifact build and Nexus IQ with GoldenFix | A job that checks the dependencies of the built artifact and raises the GoldenFix pull request with the safe versions; its report contains the Nexus IQ stage, the Nexus IQ findings and the GoldenFix card |

After all stages, the library always:

- Generates an HTML pipeline report with the stage flow, vulnerability counts, policy status, SonarQube badges and coverage, with a row for every coverage report
- Adds a **Smoke tests** table with one row per executed job and a link to each build
- Adds a **Nexus IQ GoldenFix** card with the pull request link, the applied dependency upgrades and the fixes that could not be applied automatically
- Adds a **Release policy** card stating whether the artifact may be released to Nexus and deployed to QC
- Archives the HTML reports, the DAST PDF report and the release gate verdict (`release-gate.json`) as build artifacts
- Sends the full pipeline telemetry and the four DORA metrics to InfluxDB (if configured), see [Monitoring and metrics](#21-monitoring-and-metrics)

## 2. Benefits of DevSecOps at BBH

This chapter states the business case for operating application delivery through the shared DevSecOps pipeline rather than through pipelines maintained independently by each application team. The figures quoted are published industry benchmarks, cited for order of magnitude; they are not measurements of BBH systems. Section [Monitoring and Metrics](#21-monitoring-and-metrics) describes how the equivalent figures are measured for BBH applications once the pipeline is in operation.

### Risk reduction

**Vulnerabilities are detected before the artifact is released, not after deployment.**

The pipeline evaluates four independent controls on every commit: dependency composition analysis (Nexus IQ), static application security testing (HCL AppScan), dynamic application security testing (HCL AppScan) and static code analysis (SonarQube). A finding above the governing threshold blocks the release artifact and the promotion to the higher environment automatically, without depending on a reviewer to remember the rule.

The relevance of dependency scanning in particular has increased materially. The Verizon 2025 Data Breach Investigations Report records exploitation of vulnerabilities as the initial access vector in 20% of breaches, a 34% year-on-year increase, and third-party involvement in 30% of breaches, approximately double the prior year.

**The financial exposure avoided is significant.**

The IBM Cost of a Data Breach Report 2025 places the global average cost of a breach at USD 4.44 million, and the average for United States organisations at USD 10.22 million. The same study reports a mean time to identify and contain of 241 days. A control that prevents a single vulnerable dependency from reaching production therefore offsets a substantial multiple of its operating cost.

**The security policy is enforced uniformly and cannot be relaxed locally.**

Severity thresholds and the minimum line coverage are defined in the library and cannot be overridden by an application's configuration. Every build records the thresholds it was evaluated against, which provides an auditable position for each release rather than a per-team interpretation.

### Cost reduction

**Defects are corrected at the phase in which they are cheapest to correct.**

The widely cited IBM Systems Sciences Institute cost model places the relative cost of correcting a defect at approximately 1x during design, 6x during implementation, 15x during testing and 60x to 100x after release. The precise multipliers vary between studies and should be treated as indicative rather than exact; the direction is consistent across all of them. By executing dependency, static and dynamic analysis on every commit, the pipeline moves the point of detection from the later phases to the earlier ones.

**Remediation effort is automated rather than manual.**

When Nexus IQ identifies a vulnerable direct dependency, the library selects a backward-compatible version, applies it across every manifest in the repository, builds the application to confirm that the change compiles, and raises a pull request for review. The manual equivalent of this work — locating the dependency, determining a safe version, editing the manifests, verifying the build — is eliminated for every upgrade the automation completes successfully.

**Pipeline engineering is performed once and reused.**

Application teams supply two files: a `Jenkinsfile` of a few lines that names the services and the build agents, and a `devsecops_config.yaml` that describes the services. The stage definitions, tool integrations, policy engine, report and metrics are maintained centrally. A change to a tool integration is implemented once in the library rather than in each application's pipeline.

### Delivery acceleration

**The pipeline is structured to support a high deployment cadence.**

The 2024 DORA State of DevOps Report finds that elite performing organisations deploy on demand, maintain a lead time for change of under one day, a change failure rate of approximately 5%, and recover from a failed deployment in under one hour. The same study reports that elite performers deploy 182 times more frequently than low performers, with change lead times 127 times shorter.

The library supports this operating model directly. The security pipeline entry point executes the complete static analysis set without requiring a deployed environment, which allows it to run on every commit and return a security verdict within minutes. The extended pipeline executes deployment, functional testing and dynamic analysis separately, on demand.

**Failures are reported completely rather than sequentially.**

A stage that breaches policy is marked as a warning and the pipeline continues, so that a single run reports every defect present. Teams are not required to execute repeated builds to discover successive failures.

**Diagnostic effort is reduced.**

Each build produces a single report containing the stage outcome, the finding counts relative to policy, the coverage position, the release decision and direct links to each tool's own report. A developer is not required to consult four separate consoles to establish the state of a build.

### Measurement and governance

Every run writes a complete telemetry record to InfluxDB, comprising the pipeline outcome, per-stage timing, findings per scanner per module, coverage, test execution, the release gate decision and the remediation outcome. The four DORA metrics are derived from this record.

This provides:

- An objective measurement of delivery performance per application and across the portfolio.
- Evidence, retained over time, of the controls executed on each release.
- Cumulative reporting of the findings prevented from reaching production and the dependency upgrades applied, available from the first recorded build.

### Summary of the position

| Dimension | Without the shared pipeline | With the shared pipeline |
|---|---|---|
| Security control coverage | Determined per team; varies by application | Four controls on every commit, uniformly applied |
| Policy enforcement | Dependent on review discipline | Automatic; the release is blocked by the gate |
| Dependency remediation | Manual identification and correction | Automated, build-verified pull request |
| Defect detection point | Frequently post-deployment | Pre-release, at lower correction cost |
| Pipeline maintenance | Duplicated across application teams | Maintained once in the shared library |
| Delivery measurement | Not systematically captured | DORA metrics and full telemetry per run |
| Audit evidence | Assembled on request | Recorded automatically for every build |

### Referenced sources

| Source | Figures referenced |
|---|---|
| IBM, `Cost of a Data Breach Report 2025` | Global average breach cost USD 4.44 million; United States average USD 10.22 million; mean time to identify and contain 241 days |
| Verizon, `2025 Data Breach Investigations Report` | Exploitation of vulnerabilities as initial access vector in 20% of breaches, a 34% year-on-year increase; third-party involvement in 30% of breaches |
| Google Cloud / DORA, `2024 State of DevOps Report` | Elite performer benchmarks for deployment frequency, lead time for change, change failure rate and failed deployment recovery time |
| IBM Systems Sciences Institute defect cost model | Relative cost of defect correction by development phase; widely cited, multipliers vary between studies |

## 3. DevSecOps Library Features

This chapter is the high level answer to "what can it do". Every item is covered in detail later in the document.

### One policy for every application

Severity thresholds and the required line coverage are global settings of the library, held in the defaults it bundles (`resources/com/bbh/devsecops/defaults.yaml`), which the DevSecOps team owns. A team cannot set its own thresholds in the settings of its services, and every build logs the thresholds it was measured against.

### Configuration in the repository

The settings of the services of a repository live in `devsecops_config.yaml` at its root, next to the `Jenkinsfile`; a repository that still holds the former `config.yaml` is read from that file when `devsecops_config.yaml` is absent. The Jenkinsfile names the services of the run and the Jenkins agent labels. The settings therefore follow the branch and the commit, and the console, the report header and the metrics name the file a build read (see [How a pipeline reads its configuration](#4-solution-architecture)).

### Nothing stops silently

Every stage runs on every build. A stage that breaks the policy is marked **orange** (`WARN`), the build becomes `UNSTABLE`, and the pipeline carries on so that one run surfaces every problem instead of stopping at the first. What a finding blocks is the **release**, not the run.

### Security scanning

| Scan | Tool | Capabilities |
|---|---|---|
| Dependency / SCA | Sonatype Nexus IQ | Policy evaluation of the whole dependency tree, per severity counts, a link to the composition report, and automated remediation pull requests |
| SAST | HCL AppScan on Cloud | IRX generation on the agent, queued analysis, polling until the scan finishes, HTML report archived, link to the AppScan console |
| DAST | HCL AppScan on Cloud | Scan of the deployed application through an AppScan Presence, polling until the scan finishes, HTML and PDF report archived |
| Code quality | SonarQube | Analysis, quality gate, vulnerability and hotspot counts, metric badges in the report |

The severity counts the pipeline reports are the ones printed in the tool's own report, and they are verified a second time against the archived report while the HTML report is written, so a report and a pipeline verdict can never disagree. A missing or unreadable AppScan report fails the stage rather than being recorded as zero findings.

- When `staticAnalysis.parallel` is switched on, the full and security pipelines run Nexus IQ and SonarQube while AppScan scans in its cloud: SAST sends every service first and reads the results last, and the results, the report and the release gate stay the same.

### Code quality and coverage

Unit tests run through the project's own Gradle, Maven or Flutter build. The pipeline then finds every JaCoCo XML and LCOV report of the service, in `build`, `target`, `release` or any other folder, and reads the line coverage of each. The report shows one row per report; the coverage of the service is the mean of its reports and is checked against the minimum required by the library, and the summary of the run is the mean of all reports. A failing test suite turns the stage orange and coverage is still measured on whatever reports were produced.

Flutter projects are analysed by SonarQube **without the commercial Dart plugin**: coverage and `dart analyze` findings are converted into SonarQube's generic import formats, which every edition supports.

### Release gate

The gate collects every verdict of the run and blocks the release artifact and the higher environment deployment when a scanner count is above the policy, the coverage is below the minimum, or any stage is not green. The verdict is written to `release-gate.json` and archived, so a downstream pipeline inherits the decision instead of re-deciding it.

### Automated dependency remediation (GoldenFix)

When Nexus IQ reports a policy violation the library prepares the upgrade itself and opens a pull request for review. It never merges anything.

- Only **direct dependencies** are touched; transitive ones are upgraded by upgrading whatever pulls them in.
- Manifests are updated across the whole source tree: `pom.xml`, `build.gradle`/`.kts`, `gradle.properties`, Gradle version catalogs, `package.json` (with its `overrides` and `resolutions`), `requirements*.txt`, `pyproject.toml` and `pubspec.yaml`.
- **Shared version catalogs are raised once.** In a repository that holds several services, a version that a shared catalog defines is raised in the catalog, consistently for every service that uses it: a Gradle version catalog, the catalog of `settings.gradle`, a build file that other builds apply, a `java-platform` module, or a Maven parent or BOM pom. A declaration of the same dependency outside the catalog is raised to the same version and named in the pull request, so that a reviewer can move it into the catalog.
- The version is chosen by two rules: **only the patch version may change**, and of those the **newest one without known vulnerabilities** wins.
- Before anything is proposed the project is **built with the new versions**: every changed manifest in its own folder, and a shared catalog in every build that uses it. A failing build lowers the versions and retries, and if nothing builds, no pull request is opened.
- The services of one build that share a repository share one pull request. Each later service adds its upgrades to it and says so in a comment.

The Nexus IQ GoldenFix pipeline runs the dependency scan and this remediation on their own, after building the artifact, without the other stages.

### Build, test and delivery

Gradle, Maven and Flutter builds with the project's own tasks. An optional Node.js build runs the npm commands of a frontend first, as stage 0, so that the Gradle or Maven build can package its output. Three independent test job sets — smoke, regression and performance — running locally or on another Jenkins addressed by full URL, with configurable parallelism. Snapshot delivery after static analysis, release delivery only when the gate allows it, and container images built and copied to Nexus for OpenShift projects.

### Deployment

Virtual machines over SSH or through UrbanCode Deploy, and OpenShift with image build, copy to Nexus, deployment and rollout status per environment. The higher environment is deployed only when the gate allows it **and** an operator selected the parameter.

### Reporting and metrics

A single HTML report per build with the stage flow, finding counts against the policy, coverage bars with a row per coverage report, SonarQube badges, the remediation outcome, the release policy card and the full list of test jobs — written, archived and published even when the run failed. Every run also writes a full set of metrics to InfluxDB, including the four DORA figures.

### Platform support

| Dimension | Supported |
|---|---|
| Agent operating system | Linux, macOS and Windows; the library detects the agent and uses the right shell |
| Build tools | Gradle, Maven, Flutter; Node.js with npm as an optional stage 0 before Gradle or Maven |
| Dependency ecosystems for remediation | Maven, npm, PyPI, Dart/Flutter pub |
| Shared version catalogs for remediation | Inside the repository: Gradle version catalogs (`*.versions.toml`, the files `settings.gradle` imports and its catalog DSL), Gradle build files named by `apply from`, `java-platform` modules, and Maven parent and BOM poms |
| Projects per build | One or many, each with its own configuration, scans, coverage and report rows |
| Source control | Git; Bitbucket for GoldenFix pull requests |

GoldenFix remediation needs a Linux or macOS agent. Everything else runs on Windows agents as well.

### Script security

The library runs inside the Jenkins script sandbox and uses no construct that requires an administrator to approve a signature, so onboarding a new project needs no script approval.

## 4. Solution Architecture

```text
DevSecOpsJenkinsLibrary/           <- library repository root
├── documentation.confluence       <- this document, Confluence wiki markup, the single source
├── documentation.html             <- the same documentation as a single HTML page, generated from it
├── documentation.md               <- the same documentation as Markdown for Bitbucket, generated from it
├── roadmap.md                     <- the record of the changes delivered on the library
├── vars/                         <- Jenkins global variables: the entry points and the composition root
│   ├── devSecOpsPipeline.groovy                  <- entry point: all thirteen stages
│   ├── devSecOpsSecurityPipeline.groovy          <- entry point: static analysis part
│   ├── devSecOpsExtendedPipeline.groovy          <- entry point: deployment and test part
│   ├── devSecOpsSASTScanningPipeline.groovy      <- entry point: SAST only
│   ├── devSecOpsNexusIqGoldenFixPipeline.groovy  <- entry point: build, Nexus IQ and the GoldenFix pull request
│   ├── devSecOpsSteps.groovy      <- one method per stage, included by every entry point
│   └── devSecOpsApi.groovy        <- composition root: builds every service and exposes the helper methods
├── src/com/bbh/
│   ├── core/                               <- the domain core, no Jenkins plugin knowledge
│   │   ├── PipelineState.groovy            <- everything the stages record: results, counts, coverage, links
│   │   ├── PolicyEngine.groovy             <- library policy, orange stages and the stage result log
│   │   ├── ReleaseGate.groovy              <- blocks the Nexus release and the QC deployment when a stage is not green
│   │   └── OsHelper.groovy
│   ├── config/ConfigLoader.groovy          <- the Jenkinsfile, devsecops_config.yaml and the bundled defaults -> PipelineState and the run-state file pipeline-config.yaml
│   ├── config/RepositoryConfigReader.groovy <- devsecops_config.yaml or config.yaml of the repository and the bundled defaults
│   ├── build/
│   │   ├── BuildService.groovy             <- build, unit tests, coverage, remote test jobs, the Node.js build
│   │   ├── BuildRunner.groovy              <- one runner for the Gradle and Maven commands
│   │   ├── NodeBuildRunner.groovy          <- the Node.js build of stage 0 and the Node.js environment of the npm pre-check
│   │   └── ManifestBuildVerifier.groovy    <- adapter: the GoldenFix pre-check build, one check per folder or catalog build
│   ├── scanner/                            <- adapters for the scanners
│   │   ├── AppScanService.groovy           <- HCL AppScan SAST and DAST
│   │   ├── NexusIqService.groovy           <- Nexus IQ scan, calls the remediation port on a violation
│   │   ├── NexusIqGoldenFixSource.groovy   <- adapter: Nexus IQ report + Component Remediation REST API
│   │   └── SonarService.groovy
│   ├── remediation/                        <- GoldenFix, ports and adapters
│   │   ├── GoldenFixService.groovy         <- application service: fetch -> patch manifests -> commit/push -> pull request
│   │   ├── CatalogDiscovery.groovy         <- the shared version catalogs of the repository and the builds that use them
│   │   ├── model/GoldenFix.groovy          <- the remediation domain: the fix record, version semantics and the version choice
│   │   ├── model/VersionCatalogs.groovy    <- catalog aliases, path globs and the catalog blocks of settings.gradle
│   │   ├── port/DependencyRemediation.groovy  <- inbound port used by NexusIqService
│   │   ├── port/GoldenFixSource.groovy        <- outbound port: where the proposed versions come from
│   │   ├── port/SourceRepository.groovy       <- outbound port: source tree, commit, push
│   │   ├── port/PullRequestPublisher.groovy   <- outbound port: pull request
│   │   ├── port/ManifestUpdater.groovy        <- outbound port: one build manifest format
│   │   ├── port/BuildVerifier.groovy          <- outbound port: pre-check build of the proposed versions
│   │   └── updater/                        <- MavenPomUpdater, GradleUpdater, NpmPackageJsonUpdater, PipUpdater, PubUpdater
│   ├── scm/                                <- adapters: GitSourceRepository (git worktree), Bitbucket pull requests
│   ├── deploy/                             <- adapters: VmDeployService (SSH, UrbanCode Deploy), OpenshiftService
│   ├── metrics/
│   │   ├── InfluxDbService.groovy          <- adapter: writes the batch to InfluxDB
│   │   ├── PipelineMetrics.groovy          <- builds every measurement from the pipeline state
│   │   └── MetricLine.groovy               <- line protocol formatting and escaping
│   ├── report/HtmlReportService.groovy     <- one report template, the variant only selects the stage layout
│   └── utils/                              <- RestClient (curl JSON client), AppScanReportParser, FlutterSonarReports, BuildUtils, FlutterUtils
├── resources/com/bbh/devsecops/defaults.yaml <- the global settings: thresholds, coverage minimum, timeouts, GoldenFix policy, tool defaults
├── Jenkinsfile.template           <- the Jenkinsfile of a project: services and agent labels
├── devsecops_config.yaml.template <- one commented service with every key the library reads
├── tools/build-documentation-html.py       <- renders documentation.confluence into documentation.html and documentation.md
├── grafana/                       <- the dashboard definitions devSecOpsDashboard and devSecOpsSecurityDashboard
├── test/                          <- the library test suite, see section 24
│   ├── README.md                  <- what every check and scenario proves
│   ├── run-all.sh                 <- one command: static checks, compilation, scenarios, demo reports, documentation
│   ├── checks/                    <- static checks of the sources and of the generated documentation
│   ├── coverage/                  <- the coverage scan script, run with python3 on report trees
│   ├── fixtures/                  <- the configuration files and the loader of release b815d55, the reference of the compatibility checks
│   └── sandbox/                   <- the library running under the real Jenkins script sandbox
└── examples/
    ├── CertScanner/               <- a complete two project example: the Jenkinsfile and devsecops_config.yaml
    └── reports/                   <- generated demo reports, one HTML page per pipeline and outcome
```

**How the parts fit together (hexagonal):** `core` holds the state, the policy and the release gate and knows nothing about Jenkins plugins. The GoldenFix feature is a full hexagon: the application service in `remediation` talks only to the ports in `remediation/port`, the domain rules (which version to take, how versions compare, how catalog aliases match) live in `remediation/model`, and the adapters sit in `scanner` (Nexus IQ), `scm` (git, Bitbucket), `build` (the pre-check build) and `remediation/updater` (one per manifest format). `vars/devSecOpsApi.groovy` is the single composition root that assembles every service; the services in `scanner`, `build`, `deploy`, `metrics` and `report` are adapters around the core, each receiving the pipeline script object and translating between the tools and the state. A check in the test suite enforces these dependency directions.

**Config split:**

| Source | Who controls it | Contains |
|---|---|---|
| The `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml` in the library | DevSecOps team, with a library release | Security thresholds, required coverage, timeouts, the release gate, the GoldenFix policy and the tool defaults |
| The optional `platform:` section of the same file | DevSecOps team, with a library release | The platform values: the AppScan client, proxy and AppScan addresses, the OIS host, the Nexus snapshot repository of the Flutter delivery and the iOS build agent. The production file has none, so the library's built-in BBH values apply |
| The environment of the agent and the Jenkins global environment variables | Jenkins administrators | Tool environment values that win over the platform values, such as `PROXY_HOST` or `APPSCAN_SERVER_URL`, and the order of the static scans (`DSO_STATIC_ANALYSIS_PARALLEL`) |
| `devsecops_config.yaml`, else `config.yaml`, in the repository | Project team, per branch | The `projects:` section: the service settings (appId, the AppScan key ID, servers, test jobs, deploy targets) |
| The `Jenkinsfile` in the repository | Project team, per branch | The entry point, the services of the run (`projectNames`), the agent labels (`agentNames`) and, for an extended pipeline, the security job (`securityPipeline`) |

Projects **cannot** override the global thresholds. Thresholds for SAST, SCA, Nexus IQ, DAST, the required line coverage, the scan timeouts and the release gate exist in one place only, the `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml`, and the library reads them from there only; a file in the repository cannot change them. To change one, the DevSecOps team changes that file and releases the library, and every build that loads the new version uses the new value. The other defaults, such as the GoldenFix policy, `tests.maxParallel` and `staticAnalysis.parallel`, are merged under every service as its starting values, and a service of `devsecops_config.yaml` may set its own.

### How a pipeline reads its configuration

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent']
)
```

1. The entry point calls `devSecOpsApi.configure('<variant>', <the map of the Jenkinsfile>)` before `pipeline {}`. It keeps `projectNames`, `agentNames` and `securityPipeline` of the Jenkinsfile and reads nothing yet, because the repository is checked out on the agent in the first stage.
2. `agentNames` is required: the Jenkins agent labels offered as the `AGENT_NAME` build parameter. Without it the build stops before `pipeline {}` with a message that shows the Jenkinsfile to write. A Jenkinsfile that names `pipelineKey` or `pipelineKeys`, also with an empty value, stops there as well, with the message described under [Troubleshooting](#23-troubleshooting-guide).
3. `projectNames` names the services to build, the primary project first, as one string separated by commas or as a list. Without it the library builds the services of the `PROJECT_NAMES` or `PROJECT_NAME` environment variable or, without those, every service of the file in the order of the file.
4. `securityPipeline` names the security job whose `pipeline-config.yaml` and `release-gate.json` an extended pipeline copies. In any entry point it also hides the Nexus IQ and SonarQube summary rows of the report.
5. In the Monitor stage, after the checkout, `ConfigLoader.initialize()` reads `devsecops_config.yaml` at the root of the workspace or, when there is none, `config.yaml`, the name the file had in the earlier releases of the library. When both are there it reads `devsecops_config.yaml` and says so in the console; when neither is there the stage fails with a message that names both. The names are exact and case-sensitive: a Windows agent also finds `Config.yaml`, a Linux agent does not.
6. Only the `projects:` section of the file is read. The thresholds, the required coverage, the timeouts, the GoldenFix policy and the tool defaults come from the `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml` of the library; the console says so: "\[INIT\] The services come from devsecops_config.yaml in the repository, the global settings from the library (resources/com/bbh/devsecops/defaults.yaml)". A `defaults:`, `platform:` or `pipeline:` section of the file is ignored and named in the console. The AppScan, proxy and Nexus addresses, the OIS host and the iOS build agent are the values of the agent environment, of the optional `platform:` section of the bundled defaults, or the library's own.
7. The services of the run are merged with the global defaults, the values of a service winning over the defaults, and written to `pipeline-config.yaml`, the run-state file in which the snapshot stages record their build tags. Every message that says where a setting lives names the file that was read.
8. An extended pipeline whose Jenkinsfile names a security job copies `pipeline-config.yaml` and `release-gate.json` from the last successful build of that job and takes from the copy only the run-time tags of its own services: `deploy.openshift.rd.buildTag`, `deploy.openshift.rd.internalDockerUrl`, the VM delivery version in `delivery`, `delivery.buildTagAndroid` and `delivery.buildTagIOS`. Each must be a plain tag of letters, digits and `. _ : / @ + -`. Every other setting comes from the file of its own checkout, so its Jenkinsfile lives in the repository of the service.
9. The report header says "Settings from devsecops_config.yaml in the repository", with the name of the file that was read, and `build_evidence` carries `config_file`. The settings follow the branch and the commit: a replayed build reads the file of its commit.
10. A custom Jenkinsfile that calls `devSecOpsApi.initialize()` without `configure()` reads the file too. The Nexus IQ GoldenFix pipeline needs `tools.nexusIq.application` and its `scanPatterns` for every service of the run.

Compared with release b815d55 of the library, which read `config.yaml` and `resources/defaults.yaml`, a run of this release: reads `devsecops_config.yaml` first and `config.yaml` only without it; writes its build tags to `pipeline-config.yaml` instead of the repository file, so the first extended build after the update needs one security build made by this release; needs `asoc.keyId` for every service, because SAST and DAST sign in with the key of each service; requires `agentNames`; accepts `projectNames` as a list; reads its defaults from `com/bbh/devsecops/defaults.yaml`; names the file in the report header and in `build_evidence`; and names a file that is not valid YAML or has no `projects:`.

## 5. Application Prerequisites

Before onboarding a project to the DevSecOps pipeline, the application and its repository must meet the following requirements:

- **Git repository accessible from Jenkins.** The project must live in a Git repository that the Jenkins controller can clone. The `Jenkinsfile` must be committed at the repository root and pushed to the branch configured in the Jenkins job. `devsecops_config.yaml`, or the former `config.yaml`, is committed at the root as well.
- **Supported build tool: Gradle, Maven, or Flutter.** The codebase must use one of the three supported build systems. Gradle wrapper (`gradlew`) and Maven wrapper (`mvnw`) are preferred and must be executable and committed to the repository. The build tool is either set for the service in `devsecops_config.yaml` (`buildTool`) or auto-detected.
- **Optional: a frontend that npm builds first.** When the repository holds a frontend, for example an Angular application that the Gradle or Maven build packages into the jar, the Node.js build of stage 0 can build it first (`build.node.enabled: true`). It needs the folder of the `package.json` (`build.node.dir`) and, for the default command `npm ci`, a committed `package-lock.json`. The frontend build must write its output outside `build/` and `target/`, because the `clean` task of Gradle and Maven deletes those folders, and the Gradle or Maven build must copy that output into the artifact, see [Troubleshooting](#23-troubleshooting-guide). Leave it off when the Gradle or Maven build already runs npm itself, for example through a Node plugin of the build.
- **Code must compile successfully.** The SAST stage (stage 4) compiles the project before it generates the IRX archive, unless `asoc.doCompile: false`. The compile command comes from `asoc.gradle` / `asoc.maven`, or from the `build` section when `asoc` has none. A compilation error fails the SAST stage, so make sure every compile-time dependency is resolvable from the configured Nexus repositories.
- **Unit tests with JaCoCo coverage (Gradle/Maven) or lcov (Flutter).** The project must have runnable unit tests. For Gradle and Maven, JaCoCo must be configured in the build script to produce a coverage XML report. An LCOV report, for example of an Angular frontend, counts as well, and a service with several reports is measured by their mean. The library requires a minimum line coverage of 60 %, taken from the defaults that the library bundles only; a project cannot set its own value. Below that level the Unit tests stage turns orange, the pipeline keeps running and reaches RD, and the Nexus release and the QC deployment stay blocked. A failing test run is treated the same way: the stage turns orange with the exit code as the reason, the coverage is still evaluated on the reports that were produced, and every later stage still runs. For Flutter the library itself runs `flutter test --coverage` in the main package and in each tested plugin and module, and reads the `coverage/lcov.info` of each.
- **A JDK on the agent, named in the configuration.** Set `javaPath` in the project configuration to the JDK the build and the tests must use, or set `buildToolAutoSetup: true` to let the library detect the required Java version from `pom.xml` or `build.gradle` and pick a matching JDK from the agent. Without either, the Unit tests stage stops with **JAVA_HOME parameter is not specified**, unless the agent already exports `HAS_BUILD_TOOL_INSTALLED=true`.
- **HCL AppScan on Cloud (ASoC) application registered.** The application must be registered in ASoC and have a known application ID (UUID). An API key pair must be obtained from ASoC for every service: the key ID is set for the service in `devsecops_config.yaml` (`asoc.keyId`), the key secret stays in a Jenkins **Secret text** credential named in `asoc.token`. Both SAST and DAST scans are uploaded to this application with the key of the service.
- **SonarQube project registered.** A project must exist in SonarQube with a unique `projectKey`. A badge token is required for SonarQube metric badges to appear in the HTML pipeline report: a Jenkins **Secret text** credential named in `badgeCredentialsId`, or the token itself in `badgeToken`. The SonarQube server must be reachable from the Jenkins agent.
- **Nexus IQ application registered.** The application must be registered in Nexus IQ with its public application ID. Build artifacts (JARs, WARs) must match the `scanPatterns` configured for the service in `devsecops_config.yaml`. The Nexus IQ server must be reachable from the Jenkins agent.
- **Build artifacts produced in expected locations.** Gradle projects must produce artifacts under `build/libs/`. Maven projects under `target/`. These paths must match the `scanPatterns` configured for Nexus IQ and be valid artifact outputs for publishing. A service with a Node.js build also gets the `package-lock.json` of its frontend folder scanned, see [the stage reference](#16-pipeline-stage-reference).
- **Several services in one repository.** The services of a multimodule repository share one Jenkins job per pipeline type, whose Jenkinsfile names all of them in `projectNames` and whose `devsecops_config.yaml` holds the settings of all of them. GoldenFix then raises a shared version catalog of the repository once for all of them, see [Integration Guide: Nexus IQ GoldenFix Pipeline](#13-integration-guide-nexus-iq-goldenfix-pipeline).
- **Deployment script and version file (VM deployment over SSH).** For SSH deployment, the repository must contain a zero-downtime deployment shell script and a `version.properties` file with an `APP_VERSION=<version>` entry. The Jenkins agent must have SSH key-based (passwordless) access to all configured deployment hosts. The extended pipeline and the QC deployment use this path.
- **UrbanCode Deploy application (VM deployment in `devSecOpsPipeline`).** The lower test region deployment of the full pipeline publishes the artifact as a UrbanCode Deploy component and starts the deployment process, so `deploy.vm.dod` must name the site, the application and at least one component. The UrbanCode Deploy plugin (`UCDeployPublisher`) and the configured site must exist in Jenkins.
- **OpenShift templates and a Dockerfile (OpenShift deployment).** For OpenShift-based deployment the repository must contain the BuildConfig template, the Dockerfile, the deployment template and the config template referenced by `deploy.openshift.rd.buildConfigPath`, `dockerFilePath`, `deployConfigPath` and `configPathR`. The snapshot stage builds the image in the build namespace and copies it to the Nexus docker registry with `skopeo`, so the agent needs `skopeo`, `oc` and the authfile named in `deploy.openshift.rd.nexus.authfile`.
- **Test jobs exist in Jenkins.** Smoke, regression, and performance test jobs must be created as Jenkins jobs before the pipeline reaches those stages. Their full job paths must be configured for the service in `devsecops_config.yaml`. All three test types require at least one job entry — placeholder entries with `job: ""` are allowed initially and will show `NOT_CONFIGURED` without failing.
- **DAST target accessible from Jenkins agent (DAST only).** When `dast.enabled: true`, the configured `dast.targetUrl` must be reachable from the Jenkins agent running the pipeline. For internal (non-internet) targets, an AppScan Presence must be deployed and its ID provided in `dast.presenceId`.
- **InfluxDB endpoint accessible (optional).** If DORA metrics are required, an InfluxDB v2 instance must be reachable from the Jenkins agent and the configured write endpoint must accept the provided auth token with write permissions to the target bucket.

## 6. Platform Prerequisites and the BBH Toolchain

The Jenkins controller and build agents must have the following installed and configured:

### Jenkins plugins (required)

| Plugin | Purpose |
|---|---|
| Pipeline | Core declarative pipeline support |
| Pipeline: Shared Groovy Libraries | Load this shared library |
| Git | Source checkout |
| HTML Publisher | Publish HTML pipeline report |
| JUnit | Publish unit test results |
| SonarQube Scanner | SonarQube integration |
| Nexus Platform | Nexus IQ integration (`nexusPolicyEvaluation`) |
| Parameterized Remote Trigger | Remote smoke, regression and performance jobs (also by full URL) |
| Pipeline Utility Steps | `readYaml`, `readJSON`, `writeFile` |
| Credentials Binding | `withCredentials` for Nexus IQ, ASoC, Bitbucket, remote Jenkins and the `.npmrc` of the Node.js build |
| Copy Artifact | Passing `pipeline-config.yaml` and `release-gate.json` to the extended pipeline |
| OpenShift Client (optional) | OpenShift `openshift.withCluster()` |
| NodeJS (optional) | The Node.js installations a service names in `build.node.installationName`, under **Manage Jenkins → Tools → NodeJS installations**; not needed when the service names `build.node.nodePath` or the agent has Node.js on its `PATH` |

### Steps the library calls

Besides the standard pipeline steps, the library calls the following. A missing one fails the stage that uses it, so check them when onboarding a new Jenkins instance.

| Step | Provided by | Used for |
|---|---|---|
| `nexusPolicyEvaluation`, `selectedApplication` | Nexus Platform plugin | Dependency scan |
| `withSonarQubeEnv`, `waitForQualityGate` | SonarQube Scanner plugin | SonarQube analysis and quality gate |
| `triggerRemoteJob` | Parameterized Remote Trigger plugin | Remote smoke, regression and performance jobs |
| `copyArtifacts`, `lastSuccessful` | Copy Artifact plugin | Passing `pipeline-config.yaml` and the release gate verdict downstream |
| `publishHTML` | HTML Publisher plugin | Publishing the pipeline report |
| `junit` | JUnit plugin | Unit test results |
| `readYaml`, `readJSON`, `writeJSON`, `findFiles` | Pipeline Utility Steps plugin | Configuration and report state |
| `openshift` | OpenShift Client plugin | OpenShift deployment |
| `sshagent` | SSH Agent plugin | Pushing the GoldenFix branch |
| `git`, `checkout` | Git plugin | Source checkout |
| `tool` with `type: 'nodejs'` | Pipeline: Basic Steps, with the tool type of the NodeJS plugin | The Node.js home of `build.node.installationName` |
| `withCredentials` with a `file` binding | Credentials Binding plugin | The `.npmrc` Secret file of `build.node.npmrcCredentialsId` |
| `reportBuild`, `reportUnitTest`, `reportSurefireTest`, `addSonarBadgesToDescription` | **Global steps of the BBH Jenkins installation** | Build reporting and SonarQube badges |

The last row is not a public plugin. Those four steps must exist as global variables in the Jenkins instance, otherwise the Nexus delivery and SonarQube stages fail.

### Agent tools

- Java JDK (for Gradle/Maven builds)
- Gradle or Maven wrapper (or system-installed)
- Flutter SDK (if building Flutter applications)
- Node.js with `npm`, for a service with `build.node.enabled: true`: in the folder of `build.node.nodePath`, from the NodeJS installation of `build.node.installationName`, or on the agent `PATH`
- `curl`, `python3`, `ssh`, `scp` (Linux agents)
- PowerShell (Windows agents)

### Script security

The library runs inside the Jenkins script sandbox and uses no construct that requires an administrator to approve a signature. Collections are built from literals, JSON is read and written with the `readJSON` and `writeJSON` steps, and regular expressions use the Groovy operators. Nothing has to be approved under **Manage Jenkins, In-process Script Approval** to onboard a new project.

Every class of the library is loaded and every pipeline is executed under the real script-security sandbox by the test suite before a release, so a rejected signature is found in the test run and never in your build. [Library Development and Test Suite](#24-library-development-and-test-suite) lists the rules this imposes on the library code.

### Jenkins global configuration

1. **SonarQube server** must be registered in **Manage Jenkins → Configure System → SonarQube servers** with the name `SonarQube`.
2. **Nexus IQ server** must be registered in **Manage Jenkins → Configure System → Sonatype Nexus Platform**.
3. The following credentials must exist in Jenkins Credentials Store:

| Credential ID | Type | Used for |
|---|---|---|
| `nexusiqP` | Username/Password | Nexus IQ authentication (also used by GoldenFix to read reports and remediation versions) |
| `asoc.token` of the service, otherwise a credential named like its `asoc.keyId` | Secret text | The AppScan key secret of the service, for the SAST login and the DAST sign-in; `devsecops_config.yaml` holds only the key ID and the credentials ID |
| `tools.sonar.badgeCredentialsId` of the service (optional) | Secret text | The SonarQube badge token for the badges in the report |
| `scm.bitbucket.credentialsId` | Username/Password (or Secret text with `authType: bearer`) | GoldenFix: push of branch `GoldenFix-YYYYMMDDHHMM`, pull request creation and comments on the pull request (repository write permission) |
| `build.node.npmrcCredentialsId` of the service or of the bundled defaults (optional) | Secret file | An `.npmrc` for the Node.js build and the npm pre-check of GoldenFix, bound as `NPM_CONFIG_USERCONFIG` |
| `tests.*.jobs[].credentialsId` (optional) | Username/Password (user + API token) | Triggering remote test jobs referenced by full URL |
| `openshift-rd-token` | Secret text | OpenShift RD cluster token |
| `openshift-qc-token` | Secret text | OpenShift QC cluster token |
| `influx.credentialsId` of the service | Secret text | The InfluxDB token; `devsecops_config.yaml` holds only the credentials ID |
| `tests.*.jobs[].tokenCredentialsId` (optional) | Secret text | The build token of a remote test job, which never goes into `devsecops_config.yaml` |

**Global environment variables** go into **Manage Jenkins → System → Global properties → Environment variables**. The environment variables of the node properties of an agent set the same values for the runs on that agent.

| Variable | Required | Value |
|---|---|---|
| `DSO_STATIC_ANALYSIS_PARALLEL` | no | `true` or `false` decides, before `staticAnalysis.parallel` of the services, whether the full and security pipelines run Nexus IQ and SonarQube while AppScan scans. Unset, `staticAnalysis.parallel` of the services decides ([the configuration reference](#15-configuration-reference-devsecops_configyaml)) |
| `SA_LINUX_URL`, `SA_WIN_URL`, `PROXY_HOST`, `PROXY_PORT`, `PROXY_USER`, `APPSCAN_HOST`, `APPSCAN_SERVER_URL` | no | The tool environment of AppScan: the download of the AppScan client for Linux and Windows, the BBH proxy and the AppScan host. A value set here wins over `platform.environment` of the bundled defaults and over the library's own BBH value, see [Stage 1](#16-pipeline-stage-reference) |

### Global settings: the bundled defaults

The global settings of every pipeline are the `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml` in the library: the security thresholds, the required coverage, the timeouts, the release gate, the GoldenFix policy and the tool defaults. The DevSecOps team owns the file and changes it with a library release; a project neither copies nor overrides the thresholds. A job uses the defaults of the library version it loads: the default version of **Global Pipeline Libraries**, or the version pinned in `@Library('DevSecOpsJenkinsLibrary@<version>') _`.

The optional `platform:` section of the same file holds the platform values. The production file has no such section, so the library uses its built-in BBH values, and a value of the agent or Jenkins environment still wins for the tool environment. [The configuration reference](#15-configuration-reference-devsecops_configyaml) lists both sections.

---

### The BBH systems your pipeline will use

All of these run inside the BBH network. You need an account or an entry in each of the ones your pipeline uses.

| System | Address | What it does for you |
|---|---|---|
| Bitbucket | `rdtools.testbbh.com:7990` | Stores your source code, your `Jenkinsfile` and your `devsecops_config.yaml`, and receives the automatic dependency upgrade pull requests |
| Jenkins | your BBH Jenkins instance | Runs the pipeline, on OpenShift agents or on VM agents |
| SonarQube Community | `tools.bbh.com/sonar` | Code quality analysis and the quality gate |
| Nexus IQ | `tools.bbh.com/IQ` | Scans your third party dependencies for known vulnerabilities |
| Nexus repository | `tools.bbh.com/nexus` | Stores the artifacts and container images your pipeline publishes |
| HCL AppScan on Cloud | `bbh.cloud.appscan.com` | Security scanning of your source code (SAST) and of your running application (DAST) |
| InfluxDB / Grafana | BBH monitoring instance | Optional. Collects the pipeline metrics and the DORA figures |

### How AppScan is reached

AppScan is outside the BBH network, so the pipeline goes through the BBH proxy `tstproxy.bbh.com:9090` using the dedicated service account `PROXY_ASOCJenk`. The pipeline obtains that account's password at run time from OIS (`oisapi.bbh.com`) using a certificate and the internal helper scripts.

**An application team configures none of this.** The certificates and scripts are maintained by the DevSecOps platform team and are deliberately not stored in the library repository. If an agent cannot reach AppScan, this is the item to escalate to that team, quoting the agent label.

### Who does what

| Step | Who |
|---|---|
| Registering the library in Jenkins | Jenkins administrators, once per instance |
| Creating the shared credentials | Jenkins administrators |
| Creating the Nexus IQ application and assigning a policy | You, or the Nexus IQ administrators |
| Creating the SonarQube project and tokens | You |
| Creating the AppScan application, key pair and DAST Presence | DevSecOps team |
| Proxy account, OIS certificates, agent connectivity | DevSecOps platform team |
| Nexus repositories and publishing accounts | Nexus administrators |
| Deployment hosts and accounts | The owners of your test environments |
| The global environment variables, such as `DSO_STATIC_ANALYSIS_PARALLEL` | Jenkins administrators |
| The global settings and the platform values of the bundled defaults (`resources/com/bbh/devsecops/defaults.yaml`) | DevSecOps team, with a library release |
| `devsecops_config.yaml`, the `Jenkinsfile`, the Jenkins job, the test jobs | You |

## 7. Third-Party System Registration Procedure

Do this before the first pipeline run. Each tool gives you an identifier that goes into the settings of your service in `devsecops_config.yaml`. Keep a note of the values as you go; the checklist at the end of this chapter lists them.

### Nexus IQ — dependency scanning

**Where:** `tools.bbh.com/IQ` · **Who:** you, or the Nexus IQ administrators if you may not create applications

1. **Sign in and open Organizations & Policies.** Find the organisation your team belongs to. If you do not know which one, ask your team lead.
2. **Create a new application.** Use a clear name such as `MyApp-GUI`. Nexus IQ also asks for an **Application ID**, the short public identifier — that is the value you need.
3. **Confirm a policy is assigned.** The application must inherit your organisation's policy, otherwise the scan reports nothing.
4. **Write down the Application ID.** It goes into `devsecops_config.yaml` as `tools.nexusIq.application`.

The pipeline fails the dependency stage if the application does not exist in Nexus IQ, so do this before the first run.

### SonarQube — code quality

**Where:** `tools.bbh.com/sonar` · **Who:** you

1. **Create the project.** Choose **Create Project → Manually**, give it a display name such as `MyApp-GUI` and a project key such as `myapp-gui`. The key must be unique across BBH, so prefix it with your application name.
2. **Generate a token to read results back.** **My Account → Security → Generate token**. Give it to the Jenkins administrators to store as a **Secret text** credential and note the credential ID.
3. **Optional: generate a badge token.** **Project Settings → Badges**. Give it to the Jenkins administrators to store as a **Secret text** credential and name it in `tools.sonar.badgeCredentialsId`; the quality metrics then appear in the pipeline report.
4. **Write down the project key and the credential ID.** They go into `devsecops_config.yaml` as `tools.sonar.projectKey` and `tools.sonar.authToken`.

**Flutter applications:** SonarQube Community has no Dart analyser and the library does not need one. It converts the coverage report and the `dart analyze` findings into the generic import formats SonarQube accepts for any language. No additional component is required; create the project as described above.

### HCL AppScan — SAST

**Where:** `bbh.cloud.appscan.com` · **Who:** the DevSecOps team creates the application, you note the identifiers

1. **Request an AppScan application.** Ask the DevSecOps team to create one for your product, giving them the application name and your team.
2. **Receive the Application ID.** It looks like `109f44ac-cc06-4ca0-884e-d944904f7019` and goes into `devsecops_config.yaml` as `appId`.
3. **Receive the API key ID and its secret.** The key ID looks like `bbh_b81fbc9f-...` and goes into `devsecops_config.yaml` as `asoc.keyId` of the service. The matching secret is **not** put in the file — the Jenkins administrators store it as a **Secret text** credential, and its ID goes into the file as `asoc.token`. Without `asoc.token` the library reads the credential whose ID is the key ID. SAST and DAST both use this key.
4. **Confirm your agent can reach AppScan.** Ask the DevSecOps team to confirm the proxy service account and the OIS certificate are installed on your agent label. This is the most common reason a first SAST run fails.

> **Note:** **Never** put the AppScan key secret, the proxy password or any certificate into `devsecops_config.yaml` or anywhere else in Bitbucket. Secrets belong in the Jenkins credentials store, and the library reads them from there at run time.

### HCL AppScan — DAST

**Where:** `bbh.cloud.appscan.com` · **Who:** you and the DevSecOps team

DAST scans a **running** instance, so it only applies if you have one deployed in a test environment. If you do not, set `dast.enabled: false` and the pipeline reports the stage as not required instead of failing.

1. **Have a deployed test instance** and note its URL, for example `http://rdltaapps1.testbbh.com`. It goes into `devsecops_config.yaml` as `dast.targetUrl`.
2. **Request an AppScan Presence.** Your application is inside the BBH network and AppScan is outside it, so AppScan needs a Presence — a small agent inside the network that performs the scan. Ask the DevSecOps team for one and for its ID.
3. **Decide whether the scan needs to log in.** If the interesting parts are behind a login, provide a dedicated test account and give the credentials to the DevSecOps team rather than putting them in the file.
4. **Write down the target URL and the Presence ID.** They go into `devsecops_config.yaml` as `dast.targetUrl` and `dast.presenceId`.

### Nexus repository — where artifacts are published

**Where:** `tools.bbh.com/nexus` · **Who:** ask the Nexus administrators

Only needed if your pipeline publishes artifacts or container images. Ask for a snapshot repository, a release repository and an account that may deploy to them. If you deploy to OpenShift, ask for the Docker registry path as well.

### Credentials that must exist in Jenkins

Most of these are shared across all applications and will already exist. Check with the administrators and request only what is missing.

| Credential ID | Type | Needed for | Scope |
|---|---|---|---|
| your AppScan key secret (`asoc.token`) | Secret text | The AppScan login of SAST and DAST, with the key ID of the service | per service |
| `nexusiqP` | Username with password | Nexus IQ evaluation and remediation lookups | shared |
| your Bitbucket credential | Username with password, or HTTP access token | Pushing the upgrade branch, opening the pull request and commenting on it; needs **write** access | per application |
| your `.npmrc` (`build.node.npmrcCredentialsId`) | Secret file | Only if the Node.js build installs from an npm registry that needs a login | optional |
| your SonarQube token | Secret text | Reading issue counts back after the analysis | per application |
| your SonarQube badge token (`tools.sonar.badgeCredentialsId`) | Secret text | The SonarQube badges in the pipeline report | optional |
| `remote-jenkins-api-token` | Username with API token | Only if your tests run on another Jenkins instance | optional |
| `openshift-rd-token`, `openshift-qc-token` | Secret text | Only if you deploy to OpenShift | optional |

### Checklist before you continue

You should now have written down: the **Nexus IQ application ID**, the **SonarQube project key** and token credential ID, the **AppScan application ID** and key ID, and — if you use DAST — the **target URL** and **Presence ID**. If any is still missing, finish this chapter before going on.

## 8. Detailed Description Guide for Application Integration

This chapter is the same for all five pipelines. Do it once, then go to the chapter of the pipeline you chose.

**You do not write any pipeline code.** Your application needs two new files: a `Jenkinsfile` of a few lines and a `devsecops_config.yaml` that describes its services. Everything else comes from the library.

### Step 1 — Register the library in Jenkins

**Who:** Jenkins administrators, once per Jenkins instance, not once per application.

**Manage Jenkins → System → Global Pipeline Libraries**:

| Field | Value |
|---|---|
| Name | `DevSecOpsJenkinsLibrary` |
| Default version | `main` (or a release tag) |
| Retrieval method | Modern SCM → Git |
| Project repository | the library repository on `rdtools.testbbh.com:7990` |
| Load implicitly | off — projects declare `@Library(...)` themselves |
| Allow default version to be overridden | on, so a project can pin a version while testing |

Ask them to confirm this is done before you create your job. The global settings come with the library version a job loads, see [Platform Prerequisites](#6-platform-prerequisites-and-the-bbh-toolchain).

### Step 2 — Write devsecops_config.yaml and the Jenkinsfile

**Who:** a developer on your team.

Both files go in the **root** of the repository, on the branch the pipeline will build.

**`devsecops_config.yaml` describes the services.** Copy `devsecops_config.yaml.template` from the library, keep one entry under `projects:` for each service of the repository and fill in the values you collected in the previous chapter. A repository that already holds a `config.yaml` of an earlier release can rename it to `devsecops_config.yaml`; until then the library reads `config.yaml`. **Do not add thresholds or a coverage minimum**: they are global settings of the library and a project cannot change them, and a `defaults:` section in the file is ignored. The worked example for your pipeline is in your chapter, and every key the library reads is listed in the [configuration reference](#15-configuration-reference-devsecops_configyaml).

```yaml
projects:
  my-app:
    appId:     "<AppScan application ID>"
    buildTool: gradle
    javaPath:  /usr/lib/jvm/java-17-openjdk
    asoc:
      keyId: "<AppScan key ID>"
      token: "<Jenkins credential ID of your AppScan key secret>"
```

**The `Jenkinsfile` starts the pipeline.** It names the entry point of your pipeline, the services of the run and the Jenkins agent labels; take it from your chapter:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```

**Commit both files.** The next build of the branch reads them. A later change of `devsecops_config.yaml` needs no change of the Jenkinsfile: every build reads the file of the commit it builds.

The entry point is the one of your pipeline: `devSecOpsPipeline`, `devSecOpsSecurityPipeline`, `devSecOpsExtendedPipeline`, `devSecOpsSASTScanningPipeline` or `devSecOpsNexusIqGoldenFixPipeline`. `projectNames` must match the names under `projects:` in `devsecops_config.yaml`; several services run in one build with `projectNames: 'gui,backend-api'` or `projectNames: ['gui', 'backend-api']`, the first one is the primary project. `agentNames` are the Jenkins agent labels offered as the `AGENT_NAME` build parameter, and the build stops before it starts without them. An extended pipeline names its security job in `securityPipeline`. Services that share one repository, such as the modules of a multimodule repository, take one Jenkinsfile that names all of them and one Jenkins job per pipeline type; one GoldenFix pull request then raises a shared version catalog once, see [Integration Guide: Nexus IQ GoldenFix Pipeline](#13-integration-guide-nexus-iq-goldenfix-pipeline). [How a pipeline reads its configuration](#4-solution-architecture) describes how the library reads both files.

### Step 3 — Create the test jobs

**Who:** you, with your QA colleagues. Only for pipelines that run tests.

Smoke, regression and performance jobs must exist before the pipeline reaches those stages. They may live on this Jenkins (`type: local`, `job: "folder/job-name"`) or on any other instance, referenced by full URL. A stage may hold hundreds of jobs; at most `tests.*.maxParallel` run at the same time.

An entry with an empty `job:` is reported as `NOT_CONFIGURED`, counts as a job that did not succeed and turns the stage orange without failing the run — which is an acceptable initial state while a suite is still being written.

### Step 4 — Create the Jenkins job

**Who:** you, or the Jenkins administrators.

**New Item** → name it, for example `MyApp-devsecops-pipeline` → choose **Pipeline**. Create one job per repository and pipeline type, not one per service, when several services share the repository. Then in the job configuration:

| Field | Value |
|---|---|
| Definition | Pipeline script from SCM |
| SCM | Git |
| Repository URL | your Bitbucket repository |
| Credentials | your Bitbucket credential |
| Branch | the branch to build |
| Script Path | `Jenkinsfile` |

Nothing else has to be configured. Save.

### Step 5 — Run it

**Who:** you.

Select **Build Now**. The first run registers the build parameters and may stop early — that is normal. Run it a second time and the interface presents **Build with Parameters** with `AGENT_NAME` and whatever else your pipeline defines.

### Step 6 — Read the report

On the build page choose **Pipeline Report** in the left menu.

1. **Read the colours.** Green means the stage met the policy. **Orange** means it ran but broke a rule — the build is unstable and the release is blocked, but the pipeline carried on so you can see every problem at once. Red means the stage failed and the pipeline stopped there. In the **Static analysis** stage, the report shows which of stages 3 to 5 failed; Nexus IQ and SonarQube have already run when the SAST results fail.
2. **Read the reasons.** Each orange or red stage carries its reason underneath, naming the counts and the consequence.
3. **Check the Release policy card.** It lists every reason the release and the higher environment deployment are blocked. When it is empty, your application is releasable.

**An initial run is unlikely to complete without findings.** The usual first run findings are coverage below the required minimum, dependencies with known vulnerabilities, and test jobs that do not exist yet. Work through them one at a time — the pipeline provides value before every finding is resolved. [Troubleshooting](#23-troubleshooting-guide) lists what each message means.

## 9. Integration Guide: Full Pipeline

**Entry point:** `devSecOpsPipeline` · **Parameters:** `AGENT_NAME`, `DEPLOY_HIGHER_ENV`

### Applicability

A single Jenkins job executing the complete delivery flow: build, unit testing, all three security controls, code quality analysis, artifact publication, deployment to two environments, functional and performance testing, and dynamic security testing. Select this entry point where a single job covering the entire flow is acceptable and the resulting execution time is acceptable to the team.

**Thirteen stages:** source checkout · unit tests with the coverage gate · Nexus IQ · AppScan SAST · SonarQube · Nexus snapshot delivery · lower environment deployment · regression tests · smoke tests · performance tests · AppScan DAST · Nexus release delivery · higher environment deployment. Jenkins declares one more, **Static analysis**, which runs stages 3 to 5 together when `staticAnalysis.parallel` is on ([the stage reference](#16-pipeline-stage-reference)).

### Jenkinsfile

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```

### Settings in devsecops_config.yaml

Put these under `projects:` in `devsecops_config.yaml` at the root of the repository. The name of the service, here `my-app`, is the name that `projectNames` of the Jenkinsfile gives.

```yaml
projects:
  my-app:
    appId:        "<AppScan application ID>"
    buildTool:    gradle
    deployTarget: vm
    javaPath:     /usr/lib/jvm/java-17-openjdk

    asoc:
      url:   "https://bbh.cloud.appscan.com"
      keyId: "<AppScan key ID>"
      token: "<Jenkins credential ID of your AppScan key secret>"

    tools:
      sonar:
        projectName: "MyApp"
        projectKey:  "myapp"
        serverUrl:   "https://tools.bbh.com/sonar"
        authToken:   "<Jenkins credential ID of your Sonar token>"
      nexusIq:
        application:   "<Nexus IQ application ID>"
        serverUrl:     "https://tools.bbh.com/IQ"
        credentialsId: "nexusiqP"
        scanPatterns:  ["**/build/libs/*.jar"]

    build:
      gradle:
        tasks: ['clean', 'build']

    tests:
      unitTests:
        gradle:
          tasks: ['test', 'jacocoTestReport']
        unitTestResult: 'build/test-results/test/*.xml'
      smoke:
        jobs:
          - name: "MyApp - smoke"
            type: local
            job:  "myapp/smoke-tests"
      regression:
        jobs:
          - name: "MyApp - regression"
            type: local
            job:  "myapp/regression-tests"
      performance:
        jobs:
          - name: "MyApp - performance"
            type: local
            job:  "myapp/performance-tests"

    dast:
      enabled:    true
      targetUrl:  "http://rdltaapps1.testbbh.com"
      presenceId: "<AppScan Presence ID>"

    scm:
      bitbucket:
        url:           "http://rdtools.testbbh.com:7990/projects/MYAPP/repos/myapp"
        credentialsId: "<your Bitbucket credential ID>"

    deploy:
      vm:
        rd:
          host:         "rdltaapps1.testbbh.com"
          user:         "taadmin"
          deployDir:    "/opt/ta/MyApp/deployment"
          deployScript: "scripts/deployment/deploy.sh"
        qc:
          host:         "qcltaapps1.testbbh.com"
          user:         "taadmin"
          deployDir:    "/opt/ta/MyApp/deployment"
          deployScript: "scripts/deployment/deploy.sh"
```

Use `buildTool: maven` or `flutter` if that is what your project uses and replace the `build` and `tests` blocks accordingly.

The listing leaves out the optional keys of a service: `sast.scanName` names the SAST scan in AppScan, `tools.sonar.badgeCredentialsId` names the Secret text with the SonarQube badge token, and `deploy.<target>.rd.nexusDeliveryEnabled: false` stops the snapshot delivery of the service. The [configuration reference](#15-configuration-reference-devsecops_configyaml) describes them.

### Optional: a frontend built with Node.js first

A service whose repository holds a frontend that npm builds, for example an Angular application that the Gradle or Maven build packages into the jar, switches on the Node.js build in the `build` section of the service, with the folder of the `package.json`:

```yaml
    build:
      node:
        enabled: true
        dir: frontend
```

The **Node.js build** stage then runs `npm ci` and `npm run build` in `frontend` right after the first stage, and the unit tests and every later stage use its output. The report numbers the stage 0. Nothing else is required. Without `build.node.installationName` or `build.node.nodePath` the commands use the installation that the bundled defaults name in `build.node.installationName`, and without one the `node` and `npm` on the `PATH` of the agent. The other keys, such as `commands`, `env` and the `.npmrc` credentials, are in [the configuration reference](#15-configuration-reference-devsecops_configyaml). How the output reaches the jar is described under [Troubleshooting](#23-troubleshooting-guide), **The frontend is missing from the jar**.

### Specific to this pipeline

- **VM deployment uses UrbanCode Deploy** here, unlike the extended pipeline which deploys over SSH. Set `deployTarget: openshift` for an OpenShift project.
- **`DEPLOY_HIGHER_ENV` is off by default.** The pipeline deploys there only when you tick it **and** everything else is green, so a promotion is always deliberate.

## 10. Integration Guide: Security Pipeline

**Entry point:** `devSecOpsSecurityPipeline` · **Parameters:** `AGENT_NAME`, `RUN_EXTENDED_PIPELINE`

### Applicability

The first half of the delivery flow, intended for execution on **every commit**. It requires no deployed environment and returns a security verdict within a short execution time. It is operated together with the entry point described in the following chapter, which executes the remaining stages on demand.

**Six stages:** source checkout · unit tests with the coverage gate · Nexus IQ · AppScan SAST · SonarQube · Nexus snapshot delivery. A service with `build.node.enabled: true` adds the Node.js build before the unit tests, as in the full pipeline. Jenkins declares one more, **Static analysis**, which runs stages 3 to 5 together when `staticAnalysis.parallel` is on ([the stage reference](#16-pipeline-stage-reference)).

It also archives `pipeline-config.yaml`, which holds the build tags of its snapshot stage, and the release decision so the extended pipeline can pick them up, and can start the extended job automatically.

### Jenkinsfile

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsSecurityPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```

### Settings in devsecops_config.yaml

Start from the settings in the previous chapter; this pipeline does not use the `dast`, `deploy` and `tests.smoke` / `regression` / `performance` blocks. It needs `appId`, `asoc`, `tools.sonar`, `tools.nexusIq`, `build`, `tests.unitTests` and `scm.bitbucket`.

If you will also run the extended pipeline, name that job under the service so this one can start it:

```yaml
    jenkins:
      pipeline:
        extendedPipeline: "MyApp/MyApp-extended-pipeline"
```

You can add this later, once the extended job exists.

### Specific to this pipeline

- **Turn on automatic builds.** Because it is meant to run on every commit, enable a build trigger in the job configuration — a Bitbucket webhook or **Poll SCM**. Ask the Jenkins administrators which method your instance uses.
- **Leave `RUN_EXTENDED_PIPELINE` off** until the extended job exists.
- **Rationale for separating the two pipelines:** developers receive the security verdict within minutes of a push, rather than after deployment and performance testing that are not material to the change under review.

## 11. Integration Guide: Extended Pipeline

**Entry point:** `devSecOpsExtendedPipeline` · **Parameters:** `AGENT_NAME`, `DEPLOY_HIGHER_ENV`

### Applicability

The second half of the flow, started after the security pipeline. It deploys, runs the test suites, performs the dynamic security scan and then decides about the release and the promotion.

**Eight stages:** source checkout · lower environment deployment · regression tests · smoke tests · performance tests · AppScan DAST · Nexus release delivery · higher environment deployment.

> **Note:** **The security pipeline must be working first.** This one copies the archived `pipeline-config.yaml` and the release decision from that job. It **inherits** the verdict rather than deciding again, so a security finding that blocked the release there also blocks it here.

### Jenkinsfile

The Jenkinsfile names the full Jenkins path of the security job, for example `MyApp/MyApp-security-pipeline`, in `securityPipeline`:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsExtendedPipeline(
    projectNames:     'my-app',
    agentNames:       ['linux-agent'],
    securityPipeline: 'MyApp/MyApp-security-pipeline'
)
```

Keep this Jenkinsfile in the repository of the service: the job reads `devsecops_config.yaml` (else `config.yaml`) of its own checkout and takes only the run-time tags of its own services from the security build. If you keep both pipelines in one repository, give this file a different name such as `Jenkinsfile.extended` and point the job's **Script Path** at it.

### Settings in devsecops_config.yaml

This pipeline needs the `deploy`, `tests.smoke`, `tests.regression`, `tests.performance` and `dast` blocks shown in the full pipeline chapter. It reads them from the file of its own checkout, so a change of the file applies at its next build. From the security job it takes only the run-time tags of the snapshot stage: the OpenShift build tag and image reference, the version of the VM delivery and the Flutter build tags. Run the security job once with this release of the library before the first extended build; a build of an earlier release archived `config.yaml` instead of `pipeline-config.yaml`, and the extended build stops with "\[INIT\] The last successful build of &lt;job&gt; has no pipeline-config.yaml: it was built by a library release that archived config.yaml instead; run &lt;job&gt; once with this release".

Without `securityPipeline` in the Jenkinsfile the extended pipeline runs on its own: it copies nothing and deploys what its own settings name, including a build tag pinned in `devsecops_config.yaml`.

### Specific to this pipeline

- **VM deployment is over SSH** here, not UrbanCode Deploy. For OpenShift set `deployTarget: openshift` and provide the cluster tokens as credentials.
- **Do not add a commit trigger.** This job is started by the security pipeline or by a person.
- **Copy Artifact permission.** This job reads files archived by the security job, which on some Jenkins instances requires the upstream job to permit it. If the first run reports that it cannot copy artifacts, that is the setting to ask the administrators about.
- **Connect the two jobs** by naming this job in `jenkins.pipeline.extendedPipeline` of the service, in the `devsecops_config.yaml` that the security job reads. From then on, running the security pipeline with `RUN_EXTENDED_PIPELINE` ticked starts this one automatically.
- **A frontend built with Node.js.** This pipeline has no Node.js build stage, because it builds nothing until the release. The release delivery stage runs the Node.js build of each service with `build.node.enabled: true` right before `./gradlew publish` or `mvn deploy` rebuilds the artifact, so the released artifact holds the frontend. A service without it publishes as before.

## 12. Integration Guide: SAST Scanning Pipeline

**Entry point:** `devSecOpsSASTScanningPipeline` · **Parameters:** `AGENT_NAME`

### Applicability

The smallest option: it checks out your code and runs the AppScan static security scan, nothing else. Use it when you want a security scan of the source without building, testing or deploying — typically on a weekly schedule, for an application whose main pipeline lives elsewhere, or as the very first step of onboarding.

**Two stages:** source checkout · AppScan SAST. A service with `build.node.enabled: true` adds the Node.js build before the SAST stage when the SAST scan compiles it.

> **Note:** **Recommended starting point.** If you are onboarding a large application, running this pipeline first proves that the AppScan connection, the proxy and the certificates all work, before you invest time in the rest of the configuration.

### Jenkinsfile

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsSASTScanningPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```

### Settings in devsecops_config.yaml

This pipeline needs very little — only the AppScan identifiers and, if your source is not at the repository root, where to find it.

```yaml
projects:
  my-app:
    appId:     "<AppScan application ID>"
    buildTool: gradle
    sourceDir: "."
    javaPath:  /usr/lib/jvm/java-17-openjdk

    asoc:
      url:   "https://bbh.cloud.appscan.com"
      keyId: "<AppScan key ID>"
      token: "<Jenkins credential ID of your AppScan key secret>"
```

### Specific to this pipeline

- **This pipeline checks out the source itself** and sets up the JDK, because there is no build stage to do it.
- **Put it on a schedule.** In the job configuration tick **Build periodically** and enter something like `H 2 * * 1`, which runs it once a week early on Monday morning. Scanning source code does not need to happen on every commit.
- The report shows only the source checkout and the SAST stage, with a link to the AppScan report. There is no release gate card, because this pipeline releases nothing.
- **A frontend built with Node.js.** The Node.js build runs for a service only while the SAST stage compiles it: `asoc.doCompile` is not `false` and the agent is Linux or macOS. Otherwise the console says `[NODE] <service>: the SAST scan does not compile this service - no Node.js build`. After a Node.js build every IRX archive of the run leaves out `node_modules`, see [the stage reference](#16-pipeline-stage-reference).
- **Optional scan name.** `sast.scanName` names the scan in AppScan; without it the scan is named after `tools.sonar.projectName` or the service, followed by `-sast`.

## 13. Integration Guide: Nexus IQ GoldenFix Pipeline

**Entry point:** `devSecOpsNexusIqGoldenFixPipeline` · **Parameters:** `AGENT_NAME`

### Applicability

The dependency check on its own: it builds the artifact, has Nexus IQ evaluate it, and when the policy is violated GoldenFix raises the golden pull request with the safe dependency versions. Use it when the third party dependencies of an application should be checked and upgraded on their own schedule, without the unit tests, AppScan, SonarQube, delivery or deployment of the other pipelines — typically nightly, for an application whose main pipeline lives elsewhere, or beside the SAST scanning pipeline, which covers the source code.

**Three stages:** source checkout · artifact build · Nexus IQ with GoldenFix. A service with `build.node.enabled: true` adds the Node.js build before the artifact build.

### Jenkinsfile

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsNexusIqGoldenFixPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```

Services that share a repository share one Jenkinsfile and one job, see below.

### Settings in devsecops_config.yaml

This pipeline needs the Nexus IQ application and the artifact it scans, the build that produces that artifact, and the repository GoldenFix raises the pull request in.

```yaml
projects:
  my-app:
    appId:     "<AppScan application ID>"
    buildTool: gradle
    javaPath:  /usr/lib/jvm/java-17-openjdk

    asoc:
      url:   "https://bbh.cloud.appscan.com"
      keyId: "<AppScan key ID>"
      token: "<Jenkins credential ID of your AppScan key secret>"

    tools:
      nexusIq:
        application:   "<Nexus IQ application ID>"
        serverUrl:     "https://tools.bbh.com/IQ"
        credentialsId: "nexusiqP"
        scanPatterns:  ["**/build/libs/*.jar"]

    build:
      gradle:
        tasks: ['clean', 'assemble']

    scm:
      bitbucket:
        url:           "http://rdtools.testbbh.com:7990/projects/MYAPP/repos/myapp"
        credentialsId: "<your Bitbucket credential ID>"
        targetBranch:  "develop"

    goldenFix:
      enabled: true
```

- `tools.nexusIq` names the Nexus IQ application and the credential, and `scanPatterns` must match the artifacts the build produces. A service without the application or its scan patterns stops the pipeline at the start with **The Nexus IQ GoldenFix pipeline would scan nothing for &lt;service&gt;**, because a green run that scanned nothing would prove nothing.
- `buildTool`, `build` and `javaPath` (or `buildToolAutoSetup: true`) build the artifact; without a JDK the Build artifact stage stops with **JAVA_HOME parameter is not specified**.
- `scm.bitbucket` is where the `GoldenFix-YYYYMMDDHHMM` branch is pushed and the pull request is raised, into `targetBranch` or, when it is empty, into the branch that triggered the build. The credential needs write access to the repository. Without it the upgrades are listed in the report and no pull request is raised.
- `goldenFix` is on by default; every setting of [the configuration reference](#15-configuration-reference-devsecops_configyaml) applies, such as `minThreatLevel`, `ecosystems` and the pre-check build under `goldenFix.verify`.
- `asoc.keyId` **stays required** although this pipeline runs no AppScan scan: the library checks `asoc.keyId` of every service at the start of every pipeline. `appId` is shown so that one service entry serves every pipeline of the service.
- `build.node` builds a frontend with Node.js before the artifact build, as in [the full pipeline](#9-integration-guide-full-pipeline). While `build.node.nexusIqScan` is not `false`, the `package-lock.json` of `build.node.dir` joins the scan patterns of the service, so that Nexus IQ also reports the npm dependencies and GoldenFix can raise them in `package.json`. The npm pre-check of GoldenFix then runs with the Node.js, the registry and the `.npmrc` of that build.

### Several services in one repository: one job and shared version catalogs

In a multimodule repository several services often take their dependency versions from one shared catalog, for example a Gradle version catalog `gradle/libs.versions.toml` or a Maven parent pom. GoldenFix raises such a version **in the catalog**, once, for every service that uses it. For that, the services of the repository must run in one build.

**One Jenkins job for the repository.** Put the settings of all services of the repository into its `devsecops_config.yaml`, put one Jenkinsfile that names all of them in `projectNames` into the repository, and ask for one Jenkins job for it, not one per service:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsNexusIqGoldenFixPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent']
)
```

Without `projectNames` the job builds every service of the file, in the order of the file.

**Nothing to list for the usual catalogs.** GoldenFix finds by itself every `*.versions.toml`, the catalogs that a `settings.gradle` or `settings.gradle.kts` imports or declares, the files that a build names in `apply from`, `java-platform` modules, and the poms that another pom of the repository names as `<parent>` or imports with `<scope>import</scope>`.

**List the others.** A shared file that GoldenFix cannot recognise goes into `goldenFix.versionCatalogs` of the service, one repository-relative path or glob per entry (`*` within a folder name, `**` across folders). A listed `.toml` file is read as a Gradle catalog whatever its name, an `.xml` file as a Maven pom and any other file as a Gradle build file. Listing a file makes it the place where its versions are raised. A listed Gradle file is verified only in a build that uses it; a listed pom in the aggregator that builds it with the poms that use it, otherwise in its own folder, as the [Pipeline Stage Reference](#16-pipeline-stage-reference) describes. For example:

```yaml
    goldenFix:
      versionCatalogs:
      - gradle/libs.versions.toml
      - platform/*.versions.toml
```

What GoldenFix then does in one run:

- The first service that needs an upgrade raises it in the catalog, aligns declarations of the same dependency outside the catalog with it, verifies every build that uses the catalog and opens the pull request (`PR_CREATED`).
- Each later service of the same repository adds its upgrades to the same branch, never lowers a version an earlier service raised, and adds a comment to the pull request that lists its upgrades (`PR_UPDATED`). The catalog entry therefore ends at the highest version any service needs.
- A later service whose upgrades the branch already carries, because an earlier service of the run raised them in a shared catalog, makes no commit and no comment. It ends `PR_UPDATED` with the link and the message `Already raised to <component> <version> in the shared pull request <url>`.

Separate jobs per service still work, but each raises its own pull request, and the pull requests change the same catalog line. Two such jobs that start in the same minute compute the same branch name; the second push is then refused with `[GOLDENFIX] The branch <branch> already exists in <repository> - another GoldenFix job of this repository ran in the same minute; run one GoldenFix job for all services of a repository (projectNames)`.

The catalog must live in the same repository as the services. A catalog published as an artifact (`from("com.bbh:platform-catalog:1.2")` in `settings.gradle`), a BOM resolved from Nexus and a catalog in another repository are not raised; the card names them as the reason, see [the stage reference](#16-pipeline-stage-reference).

### Specific to this pipeline

- **Linux or macOS agent.** GoldenFix works in a git worktree and runs the pre-check build with the shell, so the agent of `AGENT_NAME` needs git, `curl` and the build tool.
- **This pipeline checks out the source itself** and sets up the JDK, because there is no unit test stage. The **Build artifact** stage then builds every project with the command of its `build` section, so Nexus IQ evaluates what was built. It checks no coverage, but the command runs as configured: Gradle `build` and Maven `verify` or `package` run the unit tests as well, so use Gradle `assemble` or Maven `package -DskipTests` to build without them. A build error, a failing test included, is a red stage and stops the pipeline, because there is nothing to scan.
- **The Nexus IQ stage is the one of the other pipelines.** It turns orange on a policy violation and GoldenFix raises one pull request for the projects of the build that share a repository, as described in [the stage reference](#16-pipeline-stage-reference). GoldenFix never changes the stage result.
- **Put it on a schedule** if the dependencies should be checked independently of commits, for example `H 3 * * *` under **Build periodically** for a nightly run: Nexus IQ learns about new vulnerabilities in components that did not change.
- **The report** shows the source checkout, the Node.js build when it ran, the Build artifact stage and the Nexus IQ stage, one Nexus IQ row per project in the Security Gates table with a link to the Nexus IQ report, and on a violation the **Nexus IQ GoldenFix** card with the pull request, the applied upgrades, the fixes that could not be applied automatically and the notes of the run. There is no release policy card, because this pipeline releases nothing, and no SAST, SonarQube or test rows.
- **Release gate.** The pipeline writes `release-gate.json` like every pipeline and does not archive it, because no pipeline takes it over. Its verdict follows the stages: allowed when every stage is green, blocked when the Nexus IQ stage is orange, including a scan that could not be completed.
- **Metrics.** Every run writes the measurements of [Monitoring and Metrics](#21-monitoring-and-metrics) with `variant=nexusiq` and the project tag `influx.project` followed by `nexusiq`, for example `MyAppnexusiq`. The `goldenfix` point of a project also carries the address and the title of the pull request (`pr_url`, `pr_title`) when one was raised. The Nexus IQ counts of the run are in `vulnerabilities` with `scanner=nexusiq`; the `security_findings` points with `scanner=niq` carry the status of the stage with every count at 0, as in every pipeline, so panels built on `security_findings` show no Nexus IQ findings.

## 14. Reference Implementation Examples

The library ships a complete, working example under `examples/CertScanner/`, and the test suite regenerates a set of demo reports under `examples/reports/` on every run so you can see exactly what the pipeline produces before you run it yourself.

`CertScanner/` is a complete, working example of a project onboarded to the library. It shows two projects in one repository:

| Project | Build tool | Deployment | Scans configured |
|---|---|---|---|
| `gui` | Gradle | VM: UrbanCode Deploy in the full pipeline, SSH in the extended pipeline and for QC | SAST, DAST, SonarQube, Nexus IQ with GoldenFix |
| `backend-api` | Maven | OpenShift: image build, copy to the Nexus docker registry, rollout on RD and QC | SAST, DAST, SonarQube, Nexus IQ with GoldenFix |

Both projects configure the JDK (`javaPath`), unit tests with coverage, regression, smoke and performance jobs, including local jobs, remote jobs by full URL, a remote job through a Remote Jenkins name and a plain URL list.

### Files

| File | Purpose |
|---|---|
| `CertScanner/Jenkinsfile` | Loads the library and calls the full pipeline for both services and two agent labels |
| `CertScanner/devsecops_config.yaml` | The settings of both services, the only file you adapt |

The former `config.yaml` of the example is kept unchanged as a test fixture, `test/fixtures/CertScanner/config.yaml`, as it was at release b815d55: the test suite proves that this release reads it, under either file name, into the configuration that release read, and that `CertScanner/devsecops_config.yaml` loads.

### Choosing the entry point

Every entry point takes `projectNames`, the names under `projects:` of `devsecops_config.yaml`, the primary project first, and `agentNames`, the Jenkins agent labels offered as the `AGENT_NAME` build parameter.

The full pipeline runs all thirteen stages in one job:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent', 'windows-agent']
)
```

Split the flow into two jobs when the static part should run on every commit and the deployment and test part on demand. The first job runs unit tests, Nexus IQ, SAST, SonarQube and the snapshot delivery, and archives `pipeline-config.yaml` together with the release gate verdict:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsSecurityPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent']
)
```

The second job deploys to RD, runs regression, smoke and performance tests and DAST, and then decides about the release and the QC deployment. It copies the archived files from the first job, so `securityPipeline` must name that job:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsExtendedPipeline(
    projectNames:     'gui,backend-api',
    agentNames:       ['linux-agent'],
    securityPipeline: 'CertScanner-security'
)
```

The static job starts the extended one when its `RUN_EXTENDED_PIPELINE` parameter is selected. The job it starts is the one named in `jenkins.pipeline.extendedPipeline` of `devsecops_config.yaml`, and it is started after the static job finishes, also when that job ended unstable. The extended job inherits the verdict of the static one through the copied `release-gate.json`, so findings from the static part still block the Nexus release and the QC deployment.

A separate job that only builds the artifacts, checks their dependencies with Nexus IQ and raises the GoldenFix pull request runs the Nexus IQ GoldenFix pipeline:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsNexusIqGoldenFixPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent']
)
```

### What the thresholds are

Nothing in these files sets a security threshold or a coverage minimum. Both come from the defaults that the library bundles (`resources/com/bbh/devsecops/defaults.yaml`) and cannot be changed by a project. A violation colours the stage orange, the pipeline keeps running, and the Nexus release plus the QC deployment stay blocked.

Step by step onboarding instructions are in [Detailed Description Guide for Application Integration](#8-detailed-description-guide-for-application-integration) and in the five integration guide chapters that follow it.

### Demo reports

`reports/` holds one generated HTML page per pipeline and outcome, plus `index.html` as the overview. They are produced by the report template of the library from randomised data when `test/run-all.sh` runs, so they always match the current template:

| Page | Pipeline | Outcome |
|---|---|---|
| `security-pipeline-pass.html` | static security | every stage green |
| `security-pipeline-fail.html` | static security | orange stages, release and QC blocked |
| `sast-pipeline-pass.html` | SAST only | no findings |
| `sast-pipeline-fail.html` | SAST only | findings above the policy |
| `nexusiq-pipeline-pass.html` | Nexus IQ GoldenFix | no dependency above the policy |
| `nexusiq-pipeline-fail.html` | Nexus IQ GoldenFix | dependencies above the policy, GoldenFix pull request |
| `full-pipeline-pass.html` | all thirteen stages | released to Nexus and deployed to QC |
| `full-pipeline-fail.html` | all thirteen stages | orange stages, GoldenFix pull request, release and QC blocked |

## 15. Configuration Reference: devsecops_config.yaml

`devsecops_config.yaml` at the root of the repository holds the settings of the services of the repository under `projects:`. A repository without it is read from `config.yaml`, the name the file had in the earlier releases of the library, with the same content. Only the `projects:` section is read: a `defaults:`, `platform:` or `pipeline:` section of the file is ignored and named in the console.

The global settings are the library resource `resources/com/bbh/devsecops/defaults.yaml`, which the DevSecOps team owns and changes with a library release. Its `defaults:` section is merged under every service, and its optional `platform:` section holds the platform values (see **The bundled defaults** below). The thresholds, the required coverage, the scan timeouts and the release gate are read from the bundled defaults only, so a project cannot change them, also not by setting the same keys for a service; the other keys of the defaults are starting values that a service may set for itself.

Below is the `projects:` section of `devsecops_config.yaml` with every key the library reads and its default; a key shown as a comment has no default and is set only when the service needs it. `devsecops_config.yaml.template` at the root of the library holds the same keys as a commented service to copy.

```yaml
projects:

  <project-name>:                    # the service name, as projectNames of the Jenkinsfile gives it

    appId:        ""                 # HCL AppScan application ID (UUID)
    buildTool:    gradle             # gradle | maven | flutter  [default: gradle]
    deployTarget: vm                 # vm | openshift            [default: vm]
    sourceDir:    "."                # source root for IRX generation [default: .]
    javaPath:     ""                 # JDK used for the build and the tests (exported as JAVA_HOME)
    appscanPath:  ""                 # Windows agents only (required there): path of appscan.bat below the workspace, e.g. /.appscan-tools/<SAClientUtil folder>/bin/appscan.bat
    buildToolAutoSetup: false        # true: detect the required Java version from pom.xml / build.gradle instead
    includedDirs: ""                 # optional: comma separated folders to scan (SAST)
    excludedDirs: ""                 # optional: comma separated folders to skip (SAST)

    jenkins:
      pipeline:
        extendedPipeline: ""         # security pipeline only: the extended job started when RUN_EXTENDED_PIPELINE is selected;
                                     # read from the current service, otherwise from any service of the run

    asoc:                            # HCL AppScan on Cloud (required)
      url:         "https://bbh.cloud.appscan.com"   # only the link to the DAST scan in the AppScan console [default: APPSCAN_SERVER_URL]
      keyId:       ""                # AppScan API key ID of this service, for SAST and DAST
      token:       ""                # Jenkins Secret text credential holding the key secret [default: a credential named like keyId]
      insecureTls: false             # true adds curl -k to the ASoC REST calls (TLS verification off) - keep false
      doCompile:   true              # compile the project before the IRX is generated
      sourceCodeOnly: false          # true: -sco, no compiled classes in the IRX
      useAppScanConfig: false        # true: use appscan-config.xml from the repository
      # gradle: {tasks: ['classes', 'testClasses']}   # optional SAST compile instead of build.gradle; differing from build keeps Nexus IQ, SAST and SonarQube one after another on Linux/macOS
      # maven:  {goals: ['compile']}                   # optional SAST compile instead of build.maven; never 'clean', which deletes target/ before the Nexus delivery

    sast:
      scanName:    ""                # optional: name of the SAST scan in AppScan, sanitised [default: <tools.sonar.projectName or service>-sast]

    staticAnalysis:
      parallel:    false             # true in every service of the run: Nexus IQ and SonarQube run while AppScan scans [default: the bundled defaults]
                                     # (on Linux/macOS only when asoc.gradle/asoc.maven is absent or equal to build)

    influx:                          # InfluxDB DORA metrics (optional)
      enabled:   false
      project:   ""                  # measurement tag [default: tools.sonar.projectName]
      env:       "dev"               # measurement tag
      url:       ""                  # InfluxDB v2 write endpoint
      credentialsId: ""              # Jenkins Secret text credential with the token
      token:     ""                  # the token itself, read only without credentialsId; keep tokens out of the repository

    coverage:
      reportPath: ""                 # usually empty: every JaCoCo XML and LCOV report of the service is searched; else the reports that count, as a list, lines or commas; *, ? and ** allowed
                                     # the required 60% comes from the library defaults and cannot be set here

    tools:
      sonar:
        projectName:      ""         # display name in SonarQube
        projectKey:       ""         # unique project key in SonarQube
        serverUrl:        "https://tools.bbh.com/sonar"
        installationName: "SonarQube"  # SonarQube installation registered in Jenkins
        credentialsId:    ""         # Jenkins credential with the SonarQube token for the analysis
        authToken:        ""         # Jenkins Secret text credential for the issue counts the report queries
        badgeCredentialsId: ""       # Jenkins Secret text credential with the badge token, wins over badgeToken
        badgeToken:       ""         # the badge token itself, for the SonarQube badges in the HTML report
        addBadges:        false      # add the badges to the build description
        fullBadges:       false      # all badges instead of the quality gate badge only
        gradle:                      # the analysis command of a Gradle service (tasks, flags, dir, env as in build.gradle)
          tasks: ['sonarqube']
        maven:                       # the analysis command of a Maven service (goals, flags, mvnPath, env as in build.maven)
          goals: ['sonar:sonar']
        sonarScannerVersion: ""      # Flutter: the sonar-scanner-cli-<version> tool of Jenkins [default: sonar-scanner on the PATH]
        sonarQubeEnv:
          installationName: ""       # Flutter: SonarQube installation [default: installationName]
        flutterPlugin:    false      # Flutter: true passes the reports of the sonar-flutter plugin instead of the generic formats
        sources:          "lib"      # Flutter: sonar.sources
        tests:            "test"     # Flutter: sonar.tests
        dartAnalyzeCommand: "dart analyze --format=machine"   # Flutter: the findings imported into SonarQube
      # flutter:                     # Flutter only
      #   flutterModules: []         # folders under modules/ that get flutter pub get and build_runner before the build
      nexusIq:
        application:        ""       # application public ID in Nexus IQ
        serverUrl:          "https://tools.bbh.com/IQ"
        credentialsId:      "nexusiqP"
        scanPatterns:       []       # list of Ant patterns, e.g. ["**/build/libs/*.jar"]
        stage:              "build"
        failOnNetworkError: false

    dast:
      enabled:    false              # set true to run DAST
      targetUrl:  ""                 # URL to scan
      presenceId: ""                 # AppScan Presence ID (for internal networks, optional)

    build:                           # how the artifact is built (stage 2)
      buildPath: ""                  # artifact path or glob, e.g. "build/libs/*.jar" or "target/*.jar"
      # credentialsId: ['<certificate password>', '<prod licence>', '<test licence>']   # Flutter only: three Jenkins Secret text IDs
      gradle:
        tasks:  ['clean', 'build']
        flags:  ['--refresh-dependencies']
        dir:    ""                   # optional subdirectory to build in
        env: {}                      # optional environment variables for the build
      maven:
        goals:    ['clean', 'package']
        flags:    ['-B', '-U']
        mvnPath:  ""                 # Maven installation directory; empty uses ./mvnw when present, otherwise mvn
        env:
          MAVEN_OPTS: "-Xms512m -Xmx1g"
      node:                          # optional Node.js build, stage 0, before the Gradle or Maven build
        enabled:          false      # true: build this service with Node.js first [default: false]
        dir:              ""         # folder with package.json, relative to the workspace [default: the workspace]
        commands:         ['npm ci', 'npm run build']   # one shell command per entry, run in order (sh, bat on Windows)
        installationName: ""         # NodeJS installation of Manage Jenkins > Tools [default: the bundled defaults]
        nodePath:         ""         # Node.js home on the agent; wins over installationName
        env: {}                      # environment variables of the commands, e.g. NODE_OPTIONS: "--max-old-space-size=4096"
        registry:         ""         # exported as npm_config_registry unless an .npmrc credentials ID is set; wins over the registry line of an .npmrc in the repository [default: the bundled defaults]
        npmrcCredentialsId: ""       # Jenkins Secret file with an .npmrc, bound as NPM_CONFIG_USERCONFIG [default: the bundled defaults]
        timeoutMin:       30         # minutes for all commands of the service together
        nexusIqScan:      true       # false: <dir>/package-lock.json is not added to the Nexus IQ scan patterns

    delivery:                        # Maven upload of the snapshot artifact (deployTarget: vm, buildTool: maven)
      maven:
        goals: ['deploy:deploy-file']
        flags: ['-DrepositoryId=bbh-snapshots', '-Durl=https://tools.bbh.com/nexus/repository/snapshots',
                '-DgroupId=com.bbh.myapp', '-DartifactId=myapp', '-DgeneratePom=true']
      # group:    ""                 # Flutter only: groupId of the APK and IPA upload
      # artifact: ""                 # Flutter only: artifactId, -android or -ios is appended
      # plugin:   "deploy:deploy-file"   # Flutter only: the Maven goal of the upload

    scm:
      bitbucket:                     # required to raise GoldenFix pull requests
        url:           ""            # repository link: https://<host>/projects/KEY/repos/slug, .../scm/key/slug.git or https://bitbucket.org/ws/slug
        credentialsId: ""            # Jenkins credentials: Username with password (or HTTP access token as password)
        authType:      basic         # basic | bearer (Secret text with an HTTP access token)
        type:          ""            # server | cloud  [auto-detected from url]
        targetBranch:  ""            # PR target branch  [default: branch that triggered the build]
        cloneUrl:      ""            # push URL override  [default: derived from url]
        reviewers:     []            # Bitbucket user names (server) / account UUIDs (cloud)
        projectKey:    ""            # server: project key  [default: from url]
        repoSlug:      ""            # repository slug  [default: from url]
        workspace:     ""            # cloud: workspace  [default: from url]
        apiUrl:        ""            # server: base URL  [default: from url]; cloud: REST API  [default: https://api.bitbucket.org/2.0]

    goldenFix:                       # the bundled defaults shown, project values override them
      enabled:                true
      onlyDirectDependencies: true
      minThreatLevel:         2      # 2+ medium, 4+ high, 8+ critical
      ecosystems:             [maven, npm, pypi]   # supported: maven, npm, pypi, pub; a Flutter service adds pub
      goldenVersionTypes:            # Nexus IQ remediation types taken as they are (the Golden Version)
        - recommended-non-breaking-with-dependencies
        - recommended-non-breaking
      excludeDirs:            []     # [default: .git, node_modules, target, build, .gradle, dist, venv, ...]
      verify:                        # the pre-check build of the proposed versions
        enabled:        true         # false: no pre-check, the pull request is raised unverified
        maxAttempts:    3            # version sets tried; each failed build lowers every proposed version; the build with the Nexus IQ
                                     # version instead of a catalog version comes on top
        timeoutMinutes: 20           # for each check, that is each kind of manifest and folder
        commands:                    # replaces the default check of one ecosystem; runs with bash in the folder of the check
          maven:  ""                 # [default: mvn -B -q -DskipTests compile]
          gradle: ""                 # [default: ./gradlew --no-daemon --console=plain classes, or gradle without a wrapper]
          npm:    ""                 # [default: npm ci --ignore-scripts, or npm install --ignore-scripts without a lock file]
          pip:    ""                 # [default: python3 -m pip install --dry-run -r <file>, or --dry-run . for pyproject.toml]
          pub:    ""                 # [default: flutter pub get, or dart pub get]
      commitAuthorName:       "DevSecOps GoldenFix"
      commitAuthorEmail:      "devsecops-goldenfix@noreply.local"
      timeZone:               ""     # time zone of the GoldenFix-YYYYMMDDHHMM name
      versionCatalogs:        []     # optional, this service only: shared catalog files the search does not find by itself,
                                     # repository-relative paths or globs (* within a folder name, ** across folders)

    tests:
      maxParallel: 20                # [default: the bundled defaults] max jobs of one test stage running at the same time
      unitTests:                     # stage 2: the test command and where the results are
        gradle:
          tasks: ['test', 'jacocoTestReport']
        maven:
          goals: ['test', 'jacoco:report']
        unitTestResult:    ""        # JUnit results pattern [default: **/build/test-results/test/*.xml]
        rootDir:           "."       # Maven: root of the module scan for surefire reports
        reportOutDir:      "surefire-reports"   # Maven: collected surefire reports
        allowEmptyResults: false     # Maven: accept a module without test results
      # modules:    ['<module>', '<module>']   # Flutter only: two module names, named in the log of the unit tests
      # subplugins: []               # Flutter only: folders under plugins/ tested with coverage
      # submodules: []               # Flutter only: folders under modules/ tested with coverage
      smoke:
        required:    true            # false: the stage records 'Test not required' and runs no job (also for regression and performance)
        maxParallel: 20              # optional per-stage override
        pollIntervalSec: 15          # seconds between two status polls of a job (also for regression and performance)
        defaults:                    # optional: fields merged into every job entry
          type:          remote
          timeoutMin:    15
          credentialsId: "remote-jenkins-api-token"
        urls: []                     # optional shorthand: list of remote job URLs
        jobs:
          - name:       ""           # display name (derived from the URL when empty)
            type:       local        # local | remote  [default: remote when job/url is a full URL]
            job:        ""           # Jenkins job path ("folder/job-name") or full job URL
            url:        ""           # alias of job for full remote job URLs
            timeoutMin: 15
            parameters: ""           # optional, key=value lines
            remoteJenkins: ""        # Remote Jenkins name from global config (not needed with a full URL)
            remoteJenkinsUrl: ""     # optional remote Jenkins base URL override
            credentialsId: ""        # optional: credentials (user + API token) for the remote Jenkins
            tokenCredentialsId: ""   # optional: Secret text credential with the build token of the remote job
            token:      ""           # the build token itself, read only without tokenCredentialsId; prefer tokenCredentialsId
            # pollIntervalSec: 15    # optional: seconds between two status polls  [default: pollIntervalSec of the stage]
            # auth: {}               # optional: the auth of the remote trigger, wins over credentialsId
            abortTriggeredJob:            false   # remote: options of the Parameterized Remote Trigger plugin
            trustAllCertificates:         false
            overrideTrustAllCertificates: false
            preventRemoteBuildQueue:      false
            useCrumbCache:                false
            useJobInfoCache:              false
          - "https://jenkins.example.com/job/smoke/job/login"   # an entry may also be the job URL alone
      regression:
        maxParallel: 20              # optional per-stage override
        jobs:
          - name:       ""
            type:       local
            job:        ""
            timeoutMin: 60
      performance:
        jobs:
          - name:       ""
            type:       local
            job:        ""
            timeoutMin: 120

    deploy:
      vm:                            # used when deployTarget: vm
        dod:                         # UrbanCode Deploy, used by devSecOpsPipeline for the lower test region
          siteName:      "deploy.bbh.com"        # UrbanCode Deploy site configured in Jenkins
          deployProcess: "tomcat-app-process"
          skipWait:            false
          deployWithSnapshot:  true
          updateSnapshotComp:  false
          includeOnlyDeployVersions: true
          deployOnlyChanged:   false
          deployDescription:   ""
          requestProperties:   ""
          # applicationName: ""                  # a single application without applications: (its snapshotName and components sit here)
          applications:                          # one entry per UrbanCode application
            - applicationName: ""
              order:        1                    # optional deployment order
              environments: ['DV', 'RD', 'QC']   # optional filter; leave the key out for every environment (an empty list deploys to none)
              snapshotName: ""                   # [default: <applicationName>_snapshot]
              # siteName, deployProcess, skipWait, deployWithSnapshot, updateSnapshotComp, includeOnlyDeployVersions,
              # deployOnlyChanged, deployDescription (or description) and requestProperties of dod: may be set here for this application
              components:
                - componentName:       ""
                  baseDir:             "build/libs"
                  fileIncludePatterns: "*.jar"
                  fileExcludePatterns: ""
                  versionPrefix:       ""        # [default: fileIncludePatterns]
                  version:             ""        # [default: <versionPrefix>_<BUILD_ID>]
                  incrementalVersion:  true
                  pushDescription:     ""        # optional fields of the UrbanCode component push
                  extensions:          ""
                  charset:             ""
                  versionProperties:   ""
                  versionDescription:  ""
        rd:                          # lower test environment over SSH (devSecOpsExtendedPipeline)
          nexusDeliveryEnabled: true # false: stage 6 does not publish this service to Nexus
          host:         ""
          user:         "taadmin"
          deployDir:    ""           # remote directory for deployment scripts
          deployScript: "scripts/deployment/zero-downtime-deployment.sh"
          versionFile:  "scripts/deployment/version.properties"
        qc:                          # higher test environment over SSH
          host:         ""
          user:         "taadmin"
          deployDir:    ""
          deployScript: "scripts/deployment/zero-downtime-deployment.sh"
          versionFile:  "scripts/deployment/version.properties"
      openshift:                     # used when deployTarget: openshift
        rd:
          nexusDeliveryEnabled: true # false: stage 6 builds and publishes no image of this service
          buildTag:           ""     # the image tag RD, and QC without its own buildTag, deploy when nexusDeliveryEnabled is false; a region without a tag is skipped as not required
          projectBuildR:      ""     # namespace where the image is built
          buildConfigPath:    ""     # BuildConfig template in the repository
          dockerFilePath:     ""     # Dockerfile in the repository
          buildContext:       ""     # directory prepared for the binary build
          # addFile: ""              # optional extra file copied into the build context; leave the key out when there is none
          qcDockerRepoPush:   ""     # Nexus docker repository the image is pushed to
          qcDockerRepoPull:   ""     # docker repository the deployment pulls from
          openshiftCertDir:   ""     # certificate directory for skopeo
          nexus:
            authfile:         ""     # docker authfile used by skopeo
          projectDeploymentR: ""     # namespace of the deployment
          deployConfigPath:   ""     # deployment template in the repository
          configPathR:        ""     # config template applied before the deployment
          skipConfigDeploy:   false
          healthCheckUrl:     ""     # optional HEALTH_URL template parameter
          routeHostnameR:     ""     # optional ROUTE_HOST template parameter
          deploymentPath:     ""     # optional: directory the deployment repository is cloned into
          deploymentRepo:
            url:         ""
            branch:      ""
            credentials: ""
        qc:                          # the same keys; buildTag pins the QC image even when the run builds one, empty means rd.buildTag
          projectDeploymentR: ""
          qcDockerRepoPull:   ""
          deployConfigPath:   ""
          configPathR:        ""
          healthCheckUrl:     ""
          routeHostnameR:     ""

    appName:      ""                 # OpenShift application name (BuildConfig, deployment, image stream)
    artifactName: ""                 # OpenShift: artifact file name copied into the build context
    baseArtifactName: ""             # OpenShift: original artifact name, renamed to artifactName when set

    flutter:                         # Flutter-specific settings (only when buildTool: flutter)
      platform: apk                  # apk | appbundle | ios | macos | linux | windows | web
```

### The bundled defaults

`resources/com/bbh/devsecops/defaults.yaml` of the library holds the global settings in its `defaults:` section. The library reads it with `libraryResource` in the Monitor stage of every build, so a job uses the file of the library version it loads. The `defaults:` section of the production file:

```yaml
defaults:                            # merged under every service of the run

  buildTool:    gradle               # starting values of every service
  deployTarget: vm
  sourceDir:    "."
  coverage:
    minLine: 60                      # required line coverage of a service, the mean of its reports; read from here only

  tools:
    sonar:
      qualityGate:                   # read from here only
        waitForQualityGate: true
        timeoutMinutes:     5
    nexusIq:                         # Nexus IQ thresholds, read from here only
      maxCritical: 0
      maxHigh:     0
      maxMedium:   0

  sast:                              # read from here only
    maxCritical:       0
    maxHigh:           0
    maxMedium:         0
    prepareTimeoutMin: 120           # not read: the IRX generation waits at most 120 minutes
    pollTimeoutMin:    50            # wait for the SAST scan
    pollIntervalSec:   30

  sca:                               # SonarQube thresholds, read from here only
    enabled:         true            # not read by the library
    maxCritical:     0
    maxHigh:         0
    maxMedium:       0
    pollTimeoutMin:  40              # not read by the library
    pollIntervalSec: 30              # not read by the library

  dast:                              # read from here only
    maxCritical:       0
    maxHigh:           0
    maxMedium:         0
    pollTimeoutMin:    60            # wait for the DAST scan
    pollIntervalSec:   60
    reportTimeoutMin:  30            # wait for AppScan to render the HTML and PDF report
    reportIntervalSec: 30            # delay between two attempts to fetch the rendered report

  tests:
    maxParallel: 20                  # max test jobs of one stage running at the same time; a service may set its own

  releaseGate:                       # read from here only; a stage that breaks the policy blocks the release and QC
    scanners:        [sast, sca, niq, dast]
    requireCoverage: true            # line coverage must reach coverage.minLine
    stateFile:       release-gate.json

  goldenFix:                         # the GoldenFix policy; a service may set its own values
    enabled:                true
    onlyDirectDependencies: true
    minThreatLevel:         2        # 2+ medium, 4+ high, 8+ critical
    ecosystems:             [maven, npm, pypi]
    goldenVersionTypes:
      - recommended-non-breaking-with-dependencies
      - recommended-non-breaking
    verify:
      enabled:        true
      maxAttempts:    3
      timeoutMinutes: 20
      commands:                      # empty: the default check of each ecosystem
        maven:  ""
        gradle: ""
        npm:    ""
        pip:    ""
        pub:    ""
    commitAuthorName:  "DevSecOps GoldenFix"
    commitAuthorEmail: "devsecops-goldenfix@noreply.local"
    timeZone:          ""            # time zone of the GoldenFix-YYYYMMDDHHMM name, empty = agent default
```

The production file sets no `staticAnalysis.parallel` and no `build.node` defaults. A key the DevSecOps team adds to `defaults:`, such as `staticAnalysis.parallel: true` or `build.node.installationName`, is merged under every service like the keys above.

The optional `platform:` section, a sibling of `defaults:`, holds the platform values. The production file has no such section, so the library uses its built-in BBH values, shown here:

```yaml
platform:                            # optional; without it the values below apply
  iosBuildAgent:              "mac002.bbh.com"   # agent of the iOS build of a Flutter service
  oisHost:                    "oisapi.bbh.com"   # OIS host that releases the password of the proxy account
  nexusSnapshotRepositoryId:  "bbh-snapshots"    # Nexus snapshot repository of the Flutter delivery
  nexusSnapshotRepositoryUrl: "http://tools.bbh.com/nexus/content/repositories/snapshots/"
  environment:                       # the tool environment; a value of the agent or Jenkins environment wins
    SA_LINUX_URL:       "https://tools.bbh.com/nexus/repository/releases/com/bbh/appscan/SAClientUtil/8.0.1646_Linux/SAClientUtil-8.0.1646_Linux-SAClientUtil_8.0.1646_Linux.zip"
    SA_WIN_URL:         "https://tools.bbh.com/nexus/repository/releases/com/bbh/appscan/SAClientUtil/8.0.1646_Win/SAClientUtil-8.0.1646_Win-SAClientUtil_8.0.1646_Win.zip"
    PROXY_HOST:         "tstproxy.bbh.com"
    PROXY_PORT:         "9090"
    PROXY_USER:         "PROXY_ASOCJenk"
    APPSCAN_HOST:       "bbh.cloud.appscan.com"
    APPSCAN_SERVER_URL: "https://bbh.cloud.appscan.com"
```

`platform.oisHost` must be a host name; otherwise the AppScan setup (`appscanSetup()`) stops with "\[APPSCAN\] platform.oisHost '&lt;host&gt;' of the library defaults is not a host name".

### Where each key lives

| Keys | File | Who changes them |
|---|---|---|
| `projects.<service>.*` | `devsecops_config.yaml`, else `config.yaml`, at the root of the repository | The project team, per branch |
| `coverage.minLine`; `maxCritical`, `maxHigh` and `maxMedium` of `sast`, `sca`, `dast` and `tools.nexusIq`; the polling and report timeouts of `sast` and `dast`; `tools.sonar.qualityGate`; `releaseGate` | `defaults:` of `resources/com/bbh/devsecops/defaults.yaml` in the library | The DevSecOps team only. The library reads them from there only, so the same keys under a service change nothing |
| Every other key of `defaults:`, such as `goldenFix`, `tests.maxParallel`, `buildTool` and, when the team adds them, `staticAnalysis.parallel` and `build.node` | `defaults:` of the same file, merged under every service | The DevSecOps team; a service of `devsecops_config.yaml` may set its own value |
| `platform.*` | The optional `platform:` section of the same file | The DevSecOps team; a value of the agent or Jenkins environment wins for `platform.environment` |
| `projectNames`, `agentNames`, `securityPipeline` | The `Jenkinsfile` | The project team |
| `DSO_STATIC_ANALYSIS_PARALLEL` and the tool environment variables | The Jenkins global properties, the node properties of an agent, or the `environment` block of a custom Jenkinsfile | The Jenkins administrators, or the project team in a custom Jenkinsfile |

### Defaults of the Node.js build

The bundled defaults may hold three defaults of the Node.js build, `defaults.build.node.installationName`, `defaults.build.node.registry` and `defaults.build.node.npmrcCredentialsId`, merged under every service; the production file holds none. A value of the service replaces them, and `build.node.nodePath` of the service wins over any installation. The bundled defaults do not set `build.node.enabled`, so the Node.js build runs only for a service that switches it on. The registry is exported as `npm_config_registry` only while no `.npmrc` credentials ID is in effect, one of the bundled defaults included, because that `.npmrc` names its own registry. Once exported, it wins over the registry line of an `.npmrc` in the repository. Leave `build.node` off for a Flutter service: a Flutter build has no Gradle or Maven stage for it to run before.

`goldenFix.versionCatalogs` is a setting of the service only. The bundled defaults do not set it, and it does not depend on whether the service keeps or overrides the GoldenFix rules of the bundled defaults.

### Nexus IQ and SonarQube while AppScan scans

`staticAnalysis.parallel` decides the order of the three static scans. With `true` for every service of the run the full and security pipelines run their three static scans in the **Static analysis** stage: SAST queues the scan of every service first, Nexus IQ and SonarQube then run while AppScan scans, and SAST reads the results last ([the stage reference](#16-pipeline-stage-reference)). The SAST, Nexus IQ GoldenFix and extended pipelines are not affected.

- An absent key means one after another. The production bundled defaults leave the key out, so nothing changes until a service, the bundled defaults or the environment turns it on.
- A service of `devsecops_config.yaml` or `config.yaml` sets `staticAnalysis.parallel: true` or `false` for itself, and a `defaults.staticAnalysis.parallel` of the bundled defaults is merged under every service. One service with `false` or without the key keeps the whole run one after another, so every service of the run needs `true`.
- The environment variable `DSO_STATIC_ANALYSIS_PARALLEL` decides first: `true` or `false`, whatever the settings say. The library reads it once per run, on the agent, when Jenkins decides whether to run the **Static analysis** stage. It therefore counts when it is set in **Manage Jenkins → System → Global properties → Environment variables**, in the environment variables of the agent's node properties, or in the top-level `environment` block of a custom Jenkinsfile ([the API reference](#20-programmatic-api-reference)). A value set on a Jenkins folder or job counts only when a plugin puts it into the environment of the build; the library calls no step such as `withFolderProperties`.
- On a Linux or macOS agent, a service whose SAST compile is not its build command (`asoc.gradle` or `asoc.maven` differs from `build.gradle` or `build.maven`, and `asoc.doCompile` is not `false`) keeps the run one after another, also when the environment variable is `true`, because Nexus IQ must evaluate only files the build command built. The whole block is compared, so other flags, environment variables or folders count too. The console names those services: "\[STATIC\] gui: the SAST compile runs other Gradle tasks or Maven goals than the build command, so it may not rebuild the files Nexus IQ scans: Nexus IQ, SAST and SonarQube run one after another". On Windows the IRX step compiles nothing, so the rule does not apply there.

### Which fields are required

| Field | Required | Notes |
|---|---|---|
| `asoc.keyId` | YES, for every service | Pipeline fails at init without it |
| `asoc.token` | Recommended | Secret text credential with the AppScan key secret of the service, for SAST and DAST; without it the credential named like `asoc.keyId` is used |
| `appId` | YES | Needed for AppScan SAST and DAST |
| `tools.sonar.projectKey` | Recommended | Without it the analysis still runs, but the report has no SonarQube link, issue counts or badges |
| `tools.nexusIq.application` | Recommended; YES with `scanPatterns` for `devSecOpsNexusIqGoldenFixPipeline` | Nexus IQ scan is skipped silently without it; the Nexus IQ GoldenFix pipeline stops at the start without it |
| `dast.targetUrl` | Only if `dast.enabled: true` | Pipeline fails at DAST stage without it |
| `javaPath` or `buildToolAutoSetup: true` | YES | Without one of them the Unit tests stage, or the Build artifact stage of the Nexus IQ GoldenFix pipeline, stops with **JAVA_HOME parameter is not specified** |
| `deploy.vm.dod` | Only if `deployTarget: vm` and you use `devSecOpsPipeline` | The lower test region deployment of the full pipeline uses UrbanCode Deploy |
| `deploy.vm.rd`, `deploy.vm.qc` | Only if `deployTarget: vm` | SSH deployment of the extended pipeline and of the QC stage |
| `deploy.openshift.*` | Only if `deployTarget: openshift` | `appName` and `artifactName` are required as well |
| `delivery.maven` | Only if `deployTarget: vm` and `buildTool: maven` | Snapshot upload of the artifact to Nexus |
| `jenkins.pipeline.extendedPipeline` | Only for `devSecOpsSecurityPipeline` | The job it starts when `RUN_EXTENDED_PIPELINE` is selected; it may be set in any service of the run, the current service first |
| `scm.bitbucket.url` | Only for GoldenFix | Without it the fixes are listed in the report but no pull request is raised |
| `scm.bitbucket.credentialsId` | Only for GoldenFix | Needs push rights and permission to create and comment on pull requests |
| `build.node.enabled` | Only for a Node.js build | `true` adds the Node.js build of stage 0 for the service; every other `build.node` key is optional |
| `build.node.dir` | Only if `package.json` is not at the repository root | The commands run in the workspace otherwise |
| `build.node.installationName` or `build.node.nodePath` | No | Without both, and without an installation in the bundled defaults, the commands use `node` and `npm` on the agent `PATH`; `nodePath` wins over any installation |
| `goldenFix.versionCatalogs` | No | Only for a shared catalog that GoldenFix does not find by itself, see [Integration Guide: Nexus IQ GoldenFix Pipeline](#13-integration-guide-nexus-iq-goldenfix-pipeline) |

## 16. Pipeline Stage Reference

### Stage 1: Monitor source changes (download sources)

Performs:

- Reads `devsecops_config.yaml`, else `config.yaml`, at the workspace root (`projects:` only), merges the services of the run with the `defaults:` of `resources/com/bbh/devsecops/defaults.yaml` of the library (see [How a pipeline reads its configuration](#4-solution-architecture)) and writes them to `pipeline-config.yaml`; an extended pipeline first lays the run-time tags of its security job over them
- Applies the library policy to the state, so every later stage compares against the same thresholds
- Detects the agent operating system and makes `gradlew` executable
- Sets the tool environment: ASoC URLs, proxy host, port and user (a value in the agent's environment first, then `platform.environment` of the bundled defaults, then the library's own BBH value), and the AppScan working directories
- `devSecOpsSASTScanningPipeline` and `devSecOpsNexusIqGoldenFixPipeline` also run `checkout scm` and pin `JAVA_HOME` before this

Fails if:

- neither `devsecops_config.yaml` nor `config.yaml` is at the workspace root, or the file read is not valid YAML or has no `projects:` section
- the library resource `com/bbh/devsecops/defaults.yaml` cannot be read
- a name in `projectNames`, `PROJECT_NAMES` or `PROJECT_NAME` is not a service of the file
- an extended pipeline finds no `pipeline-config.yaml` in the last successful build of its security job, a service of the run is missing from it, or a tag in it is not a plain tag
- `asoc.keyId` is empty for a service of the run

### Stage 0: Node.js build (optional)

Runs right after stage 1 in the full, security, SAST scanning and Nexus IQ GoldenFix pipelines, when at least one service of the run needs it. When no service needs it, the stage is left out entirely: no step runs, nothing is logged, and the report, the release gate and the metrics are the same as without the stage. The report numbers it 0, or 0.1, 0.2 and so on with several services, so every other stage keeps its number. The extended pipeline has no such stage, see stage 12.

Performs, once per service:

- A service with `build.node.enabled: true` runs its Node.js build. In the SAST scanning pipeline it does so only when the SAST stage compiles it: `asoc.doCompile` is not `false` and the agent is Linux or macOS.
- Every other service is marked not required, with the console line `[NODE] <service>: build.node.enabled is not set - no Node.js build`, or in the SAST scanning pipeline `[NODE] <service>: the SAST scan does not compile this service - no Node.js build`.

How the Node.js build of a service runs:

1. **Node.js:** the folder of `build.node.nodePath`; otherwise the NodeJS installation named in `build.node.installationName`, which the `tool` step resolves; otherwise `node` and `npm` from the agent `PATH`. A Node.js home goes first on the `PATH` of the commands, as `PATH+NODEJS=<home>/bin` on Linux and macOS and `PATH+NODEJS=<home>` on Windows.
2. **Registry and login:** with `build.node.npmrcCredentialsId` the Secret file is bound as `NPM_CONFIG_USERCONFIG`, so npm reads that `.npmrc` and its registry; a registry line of an `.npmrc` in the repository still wins over it, because npm reads the project file first. Without it, `build.node.registry` is exported as `npm_config_registry`, which wins over the registry line of an `.npmrc` in the repository; the scoped `@scope:registry` and login lines of that file still apply.
3. **Environment:** the entries of `build.node.env` are set for the commands.
4. **Commands:** each entry of `build.node.commands` runs in order, by default `npm ci` and then `npm run build`, in `build.node.dir` or in the workspace, with `sh` on Linux and macOS and `bat` on Windows. Each step is labelled `Node.js: <command>`. All commands of the service together must finish within `build.node.timeoutMin` minutes (default 30).
5. **Console:** one line names what ran, `[NODE] <service>: Node.js from <home> in <folder>: <command> && <command>`, where the home is the `nodePath`, `installation <name>` or `the agent PATH` and the folder is `build.node.dir` or `the workspace`.

The stage is green when the services that need it have built. A failing command, a missing installation or the timeout turns the stage red and stops the pipeline: the later stages do not run and the report shows them blocked, and the report, the release gate and the metrics are still written. The Node.js build changes no JDK and runs no Gradle or Maven command.

Its effect on the later stages:

- **Nexus IQ (stage 3):** the `package-lock.json` of the frontend folder joins the scan patterns of the service.
- **SAST (stage 4):** every IRX archive of the run leaves out `node_modules`.
- **GoldenFix:** the npm pre-check of the service runs with the Node.js, the registry or `.npmrc` and the environment of its Node.js build.
- **Release delivery (stage 12):** the extended pipeline runs the Node.js build again before it publishes.

### Stage 2: Unit tests

Performs, once per project:

- Builds the artifact with the command of the `build` section
- Runs the unit tests with the command of `tests.unitTests`, then reads every JaCoCo and LCOV coverage report of the project (see **Coverage reports** below)
- Compares the line coverage of that project, the mean of its reports, with `coverage.minLine` of the bundled defaults (60 %)

The stage never fails the pipeline. Below the required value the stage turns **orange**, the report box of that project shows the measured value next to the required one, and the reason states that the step failed and that the Nexus release and the QC deployment are blocked until the coverage is raised. A missing coverage report is treated the same way, because the required level cannot be proven. A failing test run is also caught: the stage turns orange with the exit code as the reason, the coverage of the reports that were produced is still evaluated, and Nexus IQ, SAST, SonarQube, the snapshot delivery and the RD deployment still run. The required value shown in the report is always `coverage.minLine` of the bundled defaults, so a library release that changes it changes both the check and the report.

#### Coverage reports

Where the pipeline looks:

1. When `coverage.reportPath` is set, the reports it names count, and only those, so a frontend report must be named as well. It holds one path, several paths one per line or separated by commas, or a YAML list, relative to the workspace; `*` matches within a folder or file name, `?` one character and `**` any number of folders, for example `**/build/reports/jacoco/test/*.xml`. When none of them exists, the pipeline searches as if it were empty. Leave it empty unless the search takes the wrong reports.
2. Otherwise the pipeline searches the folders in which the service is tested and every folder below them, such as `build`, `target`, `release` and the folders of the modules: the folder of the unit test command (`tests.unitTests.gradle.dir` or `tests.unitTests.maven.dir`), otherwise the folder of the build command (`build.gradle.dir` or `build.maven.dir`), otherwise the source folder (`sourceDir`), otherwise the workspace; and, with the Node.js build, the folder of its `package.json` (`build.node.dir`, the workspace by default). The folders of the other services of the build are left out. When these folders hold no report and none of them is the workspace, the whole workspace is searched the same way.
3. A JaCoCo report is an XML file that holds a JaCoCo report and has `jacoco` in its path, such as `jacoco.xml` or `jacocoTestReport.xml`, or lies under `reports/coverage` (Android) or `reports/kover` (Kover). An LCOV report is a file ending in `.info` or `.lcov`, such as `lcov.info`, that starts with an LCOV record. An LCOV report whose source files are mostly Dart files is shown as Flutter.
4. Tool and dependency folders are never searched (`.git`, `node_modules`, `.gradle`, `.m2`, `.dart_tool`, `.pub-cache`, `.symlinks`, `Pods`, `.angular`, `.next`, `.yarn`, `.cache`, `venv`, `__pycache__` and the like), nor are `DevSecOpsJenkinsLibrary`, `appscan-source-staged`, `.appscan-logs`, `.scannerwork` and the Jenkins folders whose name ends in `@tmp`, `@script` or `@libs`.
5. Services that are tested in the same folder each take the reports of their own build tool: a JaCoCo report below a `target` folder belongs to a Maven service, one below a `build` folder to a Gradle service, a Dart LCOV report to a Flutter service and any other LCOV report to a service with the Node.js build. A report that no service of the folder owns, or that two of them own, counts for each of them, and its row says `also counted for` the others. A service that names its reports in `coverage.reportPath` counts only those, so a row names it only for a report it names. When its paths name no report, it searches like the others, but the rows of the others do not name it. Give such services their own test folders, or name their reports in `coverage.reportPath`.

Which reports count:

- Every report is one row of the **Code Coverage** card, with its service, its path, its tool, its line coverage and its covered and total lines.
- A copy of another report (the same file, or the same classes or source files with the same numbers, for example a copy under `release`) and a report that combines other reports found with it (a Maven `report-aggregate` or a Gradle `testCodeCoverageReport`, recognised because at least two of the other reports hold separate parts of its classes) are shown but not counted, and so are a report without lines and a file that cannot be read. Each such row names the reason. An older report of the same module with other numbers, for example under `release/2.3`, counts.
- A report that lies in the folders of a service counts for that service, also when it is a copy of the report of another service outside the folders of that other service, for example under `release/<other service>`. Name the reports of the service in `coverage.reportPath` to leave it out.
- When a combined report holds classes that no other report holds, for example of a module without tests, those classes count as one more row of that report, so a module whose tests are removed still lowers the coverage.
- The reports named by `coverage.reportPath` always count.
- The files that the Flutter unit tests merge (`total_lcov.info`, `main_lcov.info`, `plugins_lcov.info` and `modules_lcov.info`) are not searched, because the `coverage/lcov.info` files of the packages they merge are. Name `total_lcov.info` in `coverage.reportPath` to measure a Flutter project as one report with the value of before: each of its lines is then counted as the earlier reader counted it.

How the values are computed:

- The line coverage of a report is its covered lines over its lines, rounded to one decimal.
- The line coverage of a service is the mean of its counted reports, rounded once to one decimal. Every report weighs the same, whatever its size: 82.4 % and 8.0 % give 45.2 %. The minimum of the bundled defaults, the colour of the stage and the release gate use this mean.
- The summary of the run, the **All services** bar of the card and the `[COV]` line of the log, is the mean of every counted report of the run, each report once: a report with the same path or the same bytes as a report already counted for another service is not counted again; the bar also says how many services meet the minimum. The covered, missed and total lines shown next to a mean are the sums over the counted reports.
- A service with one report gets the value it got before.

Frontend tests: an LCOV report counts when the unit test step writes it, for example through a task of the Node plugin of the Gradle build or the `frontend-maven-plugin` of the Maven build that runs `ng test --code-coverage`. Do not add the frontend tests to the commands of the Node.js build: those run in every pipeline, also before SAST, Nexus IQ GoldenFix and the release delivery, and a failing command stops the pipeline.

The search is one `python3` step per service on Linux and macOS agents. When `python3` is missing, or the search step fails or takes longer than 15 minutes, the log says so and the pipeline reads the reports as before: the paths of `coverage.reportPath` (with `findFiles` for a pattern), otherwise `total_lcov.info` for Flutter or the usual JaCoCo paths of the build tool, then every `jacoco.xml` of the workspace; see **Coverage report detection** in section 18. A JaCoCo report still needs `python3` there, as before. Windows agents always read the reports this way, and the class listing of a JaCoCo report still runs `sh` and `python3` on them, as before.

A compilation error of the artifact build is a real failure and stops the pipeline, because nothing can be scanned or deployed without an artifact.

The Nexus IQ GoldenFix pipeline runs a **Build artifact** stage in place of the unit tests: it builds every project with the command of the `build` section, so that Nexus IQ evaluates what was built, and runs no unit test command and no coverage check. A build command that runs the tests, such as Gradle `build` or Maven `verify`, still runs them, and a build error or a failing test stops that pipeline in the same way.

Both stages run after the Node.js build, in the same workspace, so the Gradle or Maven build can package the output of the frontend.

### Stages 3 to 5 in one stage: Static analysis

When `staticAnalysis.parallel` is on ([the configuration reference](#15-configuration-reference-devsecops_configyaml)), the full and security pipelines run stages 3, 4 and 5 in the Jenkins stage **Static analysis** and show the three stages as skipped; otherwise they run the three stages and show **Static analysis** as skipped. The stage runs these steps one after another:

1. The SAST preparation of stage 4: the AppScan client, then for every service the login, the compile, the IRX archive, the upload and the queued scan.
2. The removal of the SAST staging copy.
3. Stage 3, Nexus IQ, with GoldenFix.
4. Stage 5, SonarQube.
5. The SAST results of stage 4: for every service the wait for the scan, the download of the report and the policy.

AppScan scans in its cloud while Nexus IQ and SonarQube run, so the three scans take the SAST preparation plus the longer of the AppScan scan and Nexus IQ with SonarQube, instead of the sum of all four. Each scanner runs the same steps as one after another, and the console starts the stage with "\[STATIC\] SAST queues the scan of every service first; Nexus IQ and SonarQube then run while AppScan scans, and SAST reads the results last". The report, the InfluxDB lines and `release-gate.json` list the stages, the services and the findings in the order of stages 3, 4 and 5, with the same results as one after another.

| Where it fails | What happens |
|---|---|
| SAST before every scan is queued: the client, the login, the compile, the IRX archive, the upload, the queue or the removal | SAST is red and the console says "\[STATIC\] SAST stopped while it prepared its scans: Nexus IQ and SonarQube do not run in this build". Nexus IQ and GoldenFix do not run, because the workspace may be half built: the report shows Nexus IQ as SKIP and SonarQube as blocked, and the release gate lists the SAST failure as one after another |
| Nexus IQ or SonarQube stops with an error | That stage is red, as one after another; the steps after it do not run and SAST has no result |
| The SAST results: a failed or timed-out scan, the download or the policy | SAST is red; Nexus IQ and SonarQube keep their results, where one after another SonarQube would be blocked |
| An abort | The running step stops, as one after another |

In every case **Static analysis** is red, the later stages are skipped, and the report and the release gate are still written.

The new order changes what the scanners see in the workspace:

- The SAST staging copy `appscan-source-staged` (Windows: `sast_directory`) is removed once every scan is queued, so Nexus IQ does not count the jars in it a second time and SonarQube does not see it.
- The AppScan client in `.appscan-tools`, the IRX archives, `.appscan-logs`, `.appscan-home`, `.appscan-bin` and `DevSecOpsJenkinsLibrary/` are in the workspace while Nexus IQ and SonarQube run. Only broad scan patterns match them: `**/*` matches them all, `**/*.jar` also matches the jars of the AppScan client, and `**/*.zip` its archive `.appscan-tools/sa.zip`. The client is unpacked where a run one after another unpacks it, so the `appscanPath` of a Windows service still finds it, and the workspace cleanup at the end of every build removes it.
- The SAST reports `appscan-report-*.html` are written after SonarQube.
- Nexus IQ evaluates the jars that the SAST compile built with the build command of the service.
- The SAST status poll starts after SonarQube and reuses the AppScan login of the preparation; its timeout counts from then.

Restart from Stage at **Static analysis** fails as a restart at stage 3, 4 or 5 does: the Monitor stage is skipped, so no service is loaded.

### Stage 3: Dependencies scan (Nexus IQ)

Runs `nexusPolicyEvaluation` with your configured scan patterns and compares the critical, severe and moderate counts with the library thresholds (`tools.nexusIq.max*` of the bundled defaults, all 0). A violation turns the stage orange with the counts in the reason, starts the GoldenFix remediation described below and blocks the release and QC, while the pipeline keeps running.

For a service with `build.node.enabled: true`, and `build.node.nexusIqScan` not `false`, the library adds `<dir>/package-lock.json` (`package-lock.json` without a `dir`) to the scan patterns of every Nexus IQ application of the service that has scan patterns, also when they are a single pattern instead of a list. A `./`, a doubled `/` and a trailing `/` of `build.node.dir` are left out of the pattern, because the Ant patterns of Nexus IQ would not match them. Nexus IQ then also evaluates the npm dependencies of the frontend, and GoldenFix can raise them. An application without scan patterns gets no scan, and `devsecops_config.yaml` stays as it is. New npm findings count like any other finding and can turn the stage orange.

### GoldenFix remediation (automatic pull request)

When the Nexus IQ policy is violated (critical, high or medium findings above the limits), GoldenFix prepares the upgrades of each service of the run in five steps.

#### Step 1: the fixes and their versions

1. Fetches the violating components of the evaluation from Nexus IQ (`/api/v2/applications/{app}/reports/{scanId}/policy`) and keeps only **direct dependencies** with a non-waived violation of at least medium threat level.
2. Asks the Component Remediation API (`/api/v2/components/remediation/application/{id}`) for every version Nexus IQ proposes, for **all eligible components of a project in one shell step** with up to eight requests in flight at a time, and picks one of the proposals by two rules, in this order:
   1. **Do not break the build: only the patch version may change.** For `x.y.z` the major `x` and the minor `y` have to stay as they are, so `2.13.4` may become `2.13.5` but never `2.15.4`. A two-part version behaves the same way: `1.9` may become `1.9.1` but not `1.10.0`.
   2. **Among those, take the newest one that carries no known vulnerabilities.** The proposals are walked from the newest downwards, and the first one Nexus IQ reports as free of violations wins. When none of them is clean, the one with the fewest known issues is taken, and if Nexus IQ reports no counts, the newest one.

A Golden Version that changes more than the patch version therefore loses to a plain patch upgrade. Only when Nexus IQ proposes no patch-level version at all does the library fall back to the newest clean proposal of any level; the pull request and the console then say that the upgrade `changes more than the patch version, no patch-level upgrade is offered`, so a reviewer sees it has to be checked by hand.

GoldenFix takes the name of a pub component from `coordinates.name`, or from `coordinates.packageId` when there is none. This has not been checked against a real Nexus IQ report of a pub component.

#### Step 2: the manifests

GoldenFix searches the whole source tree of the repository, multi-module projects included, and skips the folders of `goldenFix.excludeDirs` (by default `node_modules`, `target`, `build`, `dist`, virtualenvs and the like). It updates:

- **Maven:** `pom.xml` dependencies and dependencyManagement, and the `<properties>` they reference, in every `pom.xml` of the repository that defines a property of that name. A parent pom of the repository is therefore covered; a parent or BOM resolved from Nexus is not.
- **Gradle:** `build.gradle`, `build.gradle.kts` and `gradle.properties`: inline coordinates, `ext`, `extra` and `val` version variables, `ext` map literals such as `ext.versions = [jackson: '2.13.4']`, and rich versions (`strictly`, `require`, `prefer`) in the Groovy and the Kotlin DSL; in `settings.gradle` and `settings.gradle.kts` only the catalogs of the `versionCatalogs` block, so a `buildscript` classpath or a plugin version there stays as it is; and the catalogs described under **Shared version catalogs** below.
- **npm:** `package.json` dependencies, devDependencies, peerDependencies and optionalDependencies (range operator preserved), and the entries of `overrides` (npm) and `resolutions` (yarn) that pin the package itself; a nested override is left alone.
- **pip:** `requirements*.txt`, `constraints*.txt`, `pyproject.toml` (PEP 621 and Poetry)
- **Flutter:** `pubspec.yaml` dependencies and dev_dependencies (the `^` constraint is preserved); the Android and iOS parts of a Flutter app are covered by the Gradle and Maven manifests

A version is only written when the manifest declares a plain version that is lower than the target. Ranges, hash-pinned requirements, dynamic versions and versions managed by a BOM are listed under "not applied automatically", with the reason, instead.

#### Shared version catalogs

A **catalog** is a file of the repository that holds versions other builds use. GoldenFix treats these files as catalogs:

- every `*.versions.toml` file, which Gradle reads as the catalog named after the file (`gradle/libs.versions.toml` is `libs`);
- a file that a `settings.gradle` or `settings.gradle.kts` imports in its `versionCatalogs` block, as `create("deps") { from(files("...")) }`, `libs { from(files('...')) }` or the Groovy form `from files('...')`, under any file name, relative to the folder of the settings file, to `$rootDir` or to `$settingsDir`, and the catalog that the block declares itself with `version(...)` and `library(...)`;
- a file that a Gradle build applies with `apply from: '...'` or `apply(from = "...")`, relative to the build, to `$rootDir` or to `rootProject.file(...)`;
- a Gradle build file that applies the `java-platform` plugin;
- a `pom.xml` that another pom of the repository names as its `<parent>` (through `relativePath`, by default `../pom.xml`) or imports with `<scope>import</scope>`;
- every file listed in `goldenFix.versionCatalogs` of the service: a `.toml` file is read as a Gradle catalog whatever its name, an `.xml` file as a Maven pom, such as `platform/bom.xml`, and any other file as a Gradle build file.

GoldenFix changes the catalogs first and then the other manifests. A dependency that a catalog defines is raised in the catalog. A declaration of the same dependency elsewhere that is raised as well takes at least the version of the catalog, and the notes name it as `<file>: <component> declared outside the catalog <catalog file> (<alias>)`, so that a reviewer can move it into the catalog. When the catalog version is the higher one, its row in the pull request says `version of the shared catalog <catalog file>` where the other rows name the rule of Nexus IQ that chose the version.

In a Gradle TOML catalog GoldenFix changes only the version text; the formatting, the key order and the comments stay as they are. A section header may be indented, hold spaces inside its brackets (`[ libraries ]`) and carry a comment (`[libraries] # third-party`); a key may be bare or in double or single quotes, and a value may be a string in double quotes or a literal string in single quotes. It reads these forms:

| Table | Forms |
|---|---|
| `[versions]` | `x = "1.2"`; `x = { strictly = "1.2" }`, `{ require = "1.2" }` and `{ prefer = "1.2" }`; in `{ strictly = "[1.0,2.0[", prefer = "1.2" }` the range stays and the concrete `prefer` is raised while the target lies inside the range; a target outside it leaves the entry as it is, with the reason below. A `require` range is read the same way when there is no `strictly`; with both, `strictly` decides |
| `[libraries]` | `a = "g:a:1.2"`; `{ module = "g:a", version = "1.2" }`; `{ group = "g", name = "a", version = "1.2" }` in any key order; `version.ref = "x"` and `version = { ref = "x" }`; `version = { strictly = "1.2" }` and `version.strictly = "1.2"`; a table `[libraries.a]` with `module`, `group`, `name`, `version` or `version.ref` on lines of their own |
| `[plugins]`, `[bundles]` | Not changed |

Gradle treats `-`, `_` and `.` in an alias alike, and so does GoldenFix: `jackson-core`, `jackson_core` and `jackson.core` are one key. In a build script, `libs.versions.jackson.core`, `"$libs.versions.jackson.core"` and `"${libs.versions.jackson.core.get()}"` ask for that key of the catalog `libs` only; the name of another catalog of `settings.gradle` asks that catalog. A plain `${x}` or `$x` reference is matched by name in every Gradle file, as before. In the catalog block of `settings.gradle`, `version("x", "1.2")` is a version of that catalog, `library("a", "g", "a").versionRef("x")` asks for it, and `library("a", "g:a:1.2")` and `library("a", "g", "a").version("1.2")` are raised in place.

GoldenFix reads the catalogs only when Nexus IQ reported Maven or Gradle fixes. The files that the search of the manifests did not return, such as an imported catalog with another name, an applied build file or a listed path, are located with one more search. A listed path is relative to the root of the repository; `./`, a doubled `/` and a trailing `/` are ignored, so `./gradle/deps.toml` is `gradle/deps.toml`. A listed path that matches nothing, or lies in a folder of `goldenFix.excludeDirs`, gives the note `version catalog <path> not found in the repository`; an absolute path, a Windows path or a path with `..` gives `version catalog <path> is outside the repository`. Notes appear in the report card and in the pull request. They are not fixes and are not counted in the metrics.

What GoldenFix does not raise, and the reason the report gives instead:

| Case | Reason under "not applied automatically" |
|---|---|
| The catalog declares a range or a dynamic version | `managed by version catalog <file> (<alias>): declared version '<v>' is dynamic or a range`; a version of a shared `pom.xml` has no alias, so the part in brackets is left out |
| A `strictly` range, or a `require` range without `strictly`, excludes the target, for example `{ strictly = "[4.1,4.2[", prefer = "4.1.86.Final" }` and the fix `4.2.1.Final`. The bounds are compared as Gradle orders versions: a version with one more number lies above the bound even when that number is 0, so `32.0.0-jre` lies above `32.0` and `4.2.0.Final` above `4.2` | `managed by version catalog <file> (<alias>): strictly range '<range>' excludes <target>`; in a build file outside the catalogs, `<file>: strictly range '<range>' excludes <target>`; a `require` range says `require range` |
| The catalog key or property that a build asks for is missing or already newer | `managed by version catalog <file> (<key>): not found or already newer` |
| A BOM or platform that the build imports manages the component (Gradle `platform()` or `enforcedPlatform()`, a `*-bom` entry of a catalog, a Maven `<scope>import</scope>`) and its group is the group of the BOM or below it | `managed by BOM <group>:<artifact>:<version> imported in <file>: raise the BOM or add an override to the catalog` |
| The catalog is an artifact that `settings.gradle` imports with `from("<group>:<artifact>:<version>")` | `managed by the version catalog artifact <group>:<artifact>:<version> outside this repository` |
| The version is a constant in `buildSrc` that a build reads as `Versions.<name>` (GoldenFix reads `buildSrc` only when a build script names such a holder; `System`, `JavaVersion`, `JavaLanguageVersion`, `Project`, `Gradle`, `Math`, `Integer`, `Long`, `String`, `Boolean` and `File` do not count) | `version <name> is defined in buildSrc/<file>: not updated automatically` |

A catalog in another repository and a BOM or parent resolved from Nexus are not raised: GoldenFix changes only the repository of the service.

#### Step 3: the pre-check build

GoldenFix builds the project with the new versions before it proposes anything, so a pull request never breaks the build. **Every ecosystem whose manifest was changed is verified**, not only the main build tool of the service, and each check runs where the change takes effect, once for each kind of manifest and folder:

| Changed file | Where the check runs | Default pre-check |
|---|---|---|
| `pom.xml` | Its own folder | `mvn -B -q -DskipTests compile` |
| A parent or BOM `pom.xml` of the repository, or a listed `.xml` file such as `platform/bom.xml` | The aggregator that builds it with the poms that use it: the folder of the nearest `pom.xml` whose `<modules>` reach both the shared pom and a pom that uses it. Without such an aggregator, the folder of the root `pom.xml` when the repository has one, otherwise the folder of the shared pom; there only the shared pom itself is built, not the poms that use it | `mvn -B -q -DskipTests compile`; in the folder of a shared pom with another name than `pom.xml`, `mvn -B -q -DskipTests compile -f '<file>'` |
| `build.gradle`, `build.gradle.kts`, `gradle.properties`, `settings.gradle`, `settings.gradle.kts` | Its own folder | `./gradlew --no-daemon --console=plain classes` (or `gradle` without a wrapper) |
| A Gradle catalog: a `*.versions.toml`, an imported or listed catalog, an applied build file, a `java-platform` module | Every build that uses it: the folder of each `settings.gradle` that imports it; for `<root>/gradle/libs.versions.toml` the folder `<root>` when `<root>/settings.gradle` exists; for an applied file the folder of the nearest `settings.gradle` of the build that applies it; for a `java-platform` module the folder of its nearest `settings.gradle`. Without such a build the check is left out, with the console line `GoldenFix: no Gradle build found for <file> - not verified` | The Gradle pre-check above |
| `package.json` | Its own folder. When a `package-lock.json` lies next to it and `goldenFix.verify.commands.npm` is not set, `npm install --package-lock-only --ignore-scripts` refreshes the lock file first | `npm ci --ignore-scripts`, or `npm install --ignore-scripts` without a lock file |
| `requirements*.txt`, `constraints*.txt` | Its own folder | `python3 -m pip install --dry-run -r <file>` |
| `pyproject.toml` | Its own folder | `python3 -m pip install --dry-run .` |
| `pubspec.yaml` | Its own folder | `flutter pub get` (or `dart pub get`) |

A refreshed `package-lock.json` is committed with the change, the notes say `<folder>/package-lock.json refreshed`, and the lock file note of the pull request leaves `package-lock.json` out. While `build.node.enabled` is true for the service, the npm check, the refresh included, runs with the Node.js, the registry or `.npmrc` and the environment of its Node.js build (stage 0); otherwise with the `npm` of the agent.

Each command is replaceable per ecosystem under `goldenFix.verify.commands`, for example `commands: { npm: "npm run build" }` to compile an Angular application rather than only resolving its dependencies. A Maven command set there gets the same `-f '<file>'` at its end in the folder of a shared pom with another name than `pom.xml`, so write it as one `mvn` call, such as `mvn -B -q -DskipTests verify`. An npm command set there runs as it is: no lock file refresh runs in front of it and `package-lock.json` is not committed, so the pull request keeps `package-lock.json` in its lock file note. A command that needs a current lock file refreshes it itself, for example `npm install --package-lock-only --legacy-peer-deps --ignore-scripts && npm ci --legacy-peer-deps --ignore-scripts`; to have the refreshed lock file in the pull request, leave `goldenFix.verify.commands.npm` empty. When a build fails while a declaration outside a catalog carries the version of the catalog (see **Shared version catalogs**), the next attempt builds that declaration with the Nexus IQ version instead, and the later attempts keep it so; the note `declared outside the catalog` stays. That build comes on top of `goldenFix.verify.maxAttempts`, so the lower versions get as many builds as without a catalog, and the console counts one build more, for example `Pre-check build 2 of 4`. The console lines `[GOLDENFIX] Pre-check build <n> of <m> for ...` name the versions that were built. When a build fails otherwise, every proposed version is lowered to the next lower one Nexus IQ offers, the worktree is reverted, the manifests are updated again and the build is repeated, up to `goldenFix.verify.maxAttempts` times (default 3). A dependency that runs out of lower versions is dropped from the change set and listed as not applied, so its vulnerability stays visible. If no version set builds, **no pull request is opened** and the run ends with `BUILD_FAILED`. Each check may take `goldenFix.verify.timeoutMinutes` (default 20). Set `goldenFix.verify.enabled: false` to skip the pre-check; the lock file is then not refreshed.

#### Step 4: the commit and the pull request

GoldenFix commits the changes in a separate git worktree (the pipeline workspace is not modified), pushes the branch `GoldenFix-YYYYMMDDHHMM` and raises a Bitbucket pull request of the same name into `scm.bitbucket.targetBranch`, or into the branch that triggered the build. The description and the commit message list every upgrade with its file, the property, the catalog alias or the catalog key it changed, the version before and after, and the rule that chose it. The row of a property or a catalog key names every upgraded component whose version it sets, in that file or in another manifest, for example a build script that reads the key as `libs.versions.jackson.get()`, and a property that both rounds raise in one file is one row, from the version before the run to the version after it. The description then lists the fixes not applied automatically and the notes of the run.

The services of one build that share a repository share one branch and one pull request:

- The first service that changes something raises the pull request (`PR_CREATED`).
- Each later service works on the same branch, adds its commit and comments on the pull request under the heading `GoldenFix for <service>` with its upgrades, the fixes not applied and the notes (`PR_UPDATED`). The comment works on Bitbucket Server and Bitbucket Cloud, with the credentials of the pull request. When the comment fails, the console says `[GOLDENFIX] The pull request comment could not be added: <reason>` and the service stays `PR_UPDATED`.
- A version already raised on the branch is never lowered, so a shared catalog ends at the highest version any service needs.
- A later service whose upgrades the branch already carries, because an earlier service of the run raised the same entry of a shared catalog on it to the target or higher, makes no commit and no comment. It ends `PR_UPDATED` with the link and the message `Already raised to <component> <version> in the shared pull request <url>`. A newer version declared elsewhere in the repository that the branch did not change, and a declaration that an earlier service raised in a build file that is not a catalog, do not count: the fix stays under "not applied automatically" with its reason.

Separate Jenkins jobs for the services of one repository each raise their own pull request. Two of them that start in the same minute compute the same branch name, and the second push fails with `[GOLDENFIX] The branch <branch> already exists in <repository> - another GoldenFix job of this repository ran in the same minute; run one GoldenFix job for all services of a repository (projectNames)`, see [Integration Guide: Nexus IQ GoldenFix Pipeline](#13-integration-guide-nexus-iq-goldenfix-pipeline). A push that Bitbucket refuses for another reason, for example a branch permission or a hook, ends with `[GOLDENFIX] git push of <branch> to <repository> failed (exit code <n>)`, followed by the `remote:` and `! [remote rejected]` lines of git. GoldenFix tells the two apart from the output of `git push`.

#### Step 5: the report

The HTML report shows the pull request link in the Nexus IQ stage, in the Security Gates table and in the **Nexus IQ GoldenFix** card. The card lists the applied changes, with the property, the catalog alias or the catalog key under the component, the fixes that could not be applied automatically with their reasons, and the notes of the run.

GoldenFix never changes the result of the Nexus IQ stage: the stage stays orange on the policy violation, and a GoldenFix error is only reported. The npm pre-check refreshes a `package-lock.json` as described above; `yarn.lock`, `poetry.lock` and pinned requirements are not regenerated. Requires `scm.bitbucket.url` and `scm.bitbucket.credentialsId` and a Linux/macOS agent with git.

### Stage 4: SAST – HCL AppScan

Downloads and unpacks SAClientUtil (the AppScan CLI), compiles every project and generates its IRX archive, queues all projects for the scan, and then waits for the results one by one. Before a project is queued and before its results are read, the CLI logs in with the key of that project (`asoc.keyId` and the secret of `asoc.token`). The scan is named `sast.scanName` when it is set, otherwise `<scan name>-sast`, where the scan name comes from `tools.sonar.projectName` or the service name; the file names keep the scan name. The HTML report of each project is downloaded and parsed, and the counts are compared with `sast.maxCritical`, `sast.maxHigh` and `sast.maxMedium` of the bundled defaults (all 0). Above the limits the stage turns orange with the counts, and the release and QC stay blocked.

After a Node.js build in the same run, the folders that an IRX archive leaves out also hold `node_modules`, so the installed npm packages are not scanned as source code. This applies to every IRX archive of the run, unless included folders (`includedDirs`) are set or `node_modules` is already among the excluded folders (`excludedDirs`). Without a Node.js build nothing changes. The rule has limits:

- With included folders set, only those folders are scanned and the excluded folders do not apply.
- On Windows agents the IRX is prepared from the included folders with `robocopy`, which leaves out only test folders.
- With `asoc.useAppScanConfig: true` the `appscan-config.xml` of the repository decides what is scanned, and the excluded folders do not apply.
- The output of the frontend build, such as the minified bundles in `dist`, is scanned like any other file.

In the **Static analysis** stage the staging copy `appscan-source-staged` (Windows: `sast_directory`) is removed once every scan is queued, so Nexus IQ and SonarQube do not see it. The AppScan client stays in `.appscan-tools` of the workspace, as one after another.

### Stage 5: SCA (SonarQube)

Runs the SonarQube analysis and waits for the quality gate. It then queries the SonarQube API for the open **vulnerabilities** of the project and maps them to the report counts: Blocker and Critical become critical, Major becomes high, Minor becomes medium and Info becomes low. Both results are enforced: a failed quality gate and vulnerability counts above `sca.maxCritical`, `sca.maxHigh` and `sca.maxMedium` of the bundled defaults (all 0) turn the stage orange with the reason and block the release and QC, without stopping the pipeline.

### Stages 6–10: Delivery and tests

Stage 6 publishes the snapshot artifact and stage 7 deploys to the lower test region. Both always run, even when an earlier stage is orange, so developers always get a build on the lower test region.

A service with `deploy.<target>.rd.nexusDeliveryEnabled: false` is not published to Nexus: stage 6 still reports its build and unit tests and logs `[NEXUS] <service>: Nexus delivery is disabled`, and the stage is `NOT_REQUIRED` when Nexus delivery is off for every service of the run. An OpenShift service without delivery has no image of this run: RD deploys a pinned `deploy.openshift.rd.buildTag`, and QC its own `deploy.openshift.qc.buildTag` or, without it, the RD tag. A region without a tag is skipped as not required with an `[OPENSHIFT]` line, and its deployment stage is `NOT_REQUIRED` when every service of the run skipped it. The release delivery of stage 12 is not affected.

What stage 6 does depends on the project: a Maven project on a VM is uploaded to the Nexus snapshot repository with the `delivery.maven` command and the delivered version is written back into `pipeline-config.yaml`; an OpenShift project has its image built in the build namespace and copied to the Nexus docker registry with `skopeo`; a Gradle project on a VM reports the build and the unit test results, and is published to Nexus by the release delivery (`./gradlew publish`). Stage 7 deploys with UrbanCode Deploy (`devSecOpsPipeline`, `deploy.vm.dod`), over SSH (`devSecOpsExtendedPipeline`, `deploy.vm.rd`) or to OpenShift (`deploy.openshift.rd`).

Then the tests run in the order regression, smoke, performance, followed by DAST. Each test stage needs at least one configured job.

Every test stage runs **all** configured jobs, even when some of them fail, and then turns orange with a summary of the failed jobs. A stage may contain any number of jobs (e.g. 200 smoke tests on different remote Jenkins instances). At most `tests.maxParallel` (default 20, per-stage override `tests.<type>.maxParallel`) jobs run at the same time. Remote jobs can be referenced by full job URL (`url:` or `job: https://...`) without a Remote Jenkins global configuration; `credentialsId` provides user + API token. The HTML report shows per-project counters (total / passed / failed / not configured), the failed jobs, a collapsible list of all jobs and a **Smoke tests** table with a build link for every job.

### Stage 11: DAST – HCL AppScan

Runs only for projects with `dast.enabled: true`. Signs in to the AppScan REST API with the key of the project (`asoc.keyId` and the secret of `asoc.token`) and queues a DAST scan against `dast.targetUrl`. Uses optional `dast.presenceId` for scanning internal (non-internet-accessible) environments.

The report is downloaded twice: as HTML (parsed for the vulnerability counts) and as **PDF**. Both are archived as build artifacts and linked from the pipeline report: in the DAST stage box and in the Security Gates table, which offers three entries, the HTML report, the **HCL AppScan** console page of the scan (under `asoc.url`, otherwise `APPSCAN_SERVER_URL`) and the PDF report. Above the DAST thresholds of the bundled defaults the stage turns orange with the critical, high and medium counts, and the release and QC stay blocked.

### Stage 12: Nexus delivery (safe artifact)

This is the second delivery to Nexus, into the release repository. It is skipped as soon as **any** earlier stage is orange, so a build with open findings, failed tests or missing coverage is never released.

In the extended pipeline, which has no stage 0, this stage first runs the Node.js build of each service with `build.node.enabled: true`, exactly as stage 0 would, and then publishes the service; a service without it publishes as before. The full pipeline built the frontend in stage 0 of the same workspace and does not build it twice.

### Stage 13: Higher test environment deployment

Runs only when `DEPLOY_HIGHER_ENV=true` is selected **and** every earlier stage is green.

### How a failure is reported: orange stages and the release gate

No security or test finding stops the pipeline. Every stage from the unit tests to DAST is executed on every build; only a build failure, such as a compilation error or a failing Node.js command, stops it. A stage that breaks the library policy is marked **orange** (`WARN`), the build becomes UNSTABLE, and the reason printed in the console and shown in the report names the failed step, the counts behind it and the consequence.

| Stage | On a policy violation | Consequence |
|---|---|---|
| Node.js build (optional) | No policy applies; a failing command is red and stops the pipeline | The later stages do not run |
| Unit tests | Orange, with the line coverage of the service (the mean of its reports) and the required one, or with the exit code of a failed test run | Release and QC blocked |
| Dependencies scan (Nexus IQ) | Orange, with critical / high / medium counts, GoldenFix pull request raised | Release and QC blocked |
| SAST | Orange, with critical / high / medium counts | Release and QC blocked |
| SCA (SonarQube) | Orange, with the quality gate status | Release and QC blocked |
| Nexus delivery (snapshot) | Always runs; `NOT_REQUIRED` when no service has Nexus delivery on | – |
| Lower test region deployment (RD) | Always runs | – |
| Regression, smoke, performance | Orange, with the failed jobs | Release and QC blocked |
| DAST | Orange, with critical / high / medium counts | Release and QC blocked |
| Nexus delivery (release) | Skipped when anything above is orange | No release artifact |
| Higher test environment deployment (QC) | Skipped unless everything is green and the checkbox is selected | No QC deployment |

The **release gate** collects the verdict. It blocks the second Nexus delivery and the QC deployment when any stage is not green, when a scanner count exceeds the library policy, or when the line coverage of a service is below the required 60 %. The HTML report shows a **Release policy** card listing every violation, for example `gui Dependencies (Nexus IQ) critical 1 > 0` or `line coverage 41.0% below the required 60%`.

Each pipeline evaluates the results it owns and hands its verdict to the next one through the archived `release-gate.json`, so a security pipeline with an orange stage also blocks the release in the extended pipeline.

## 17. Security Policy Thresholds

All thresholds are global settings in the defaults that the library bundles (`resources/com/bbh/devsecops/defaults.yaml`), which only the DevSecOps team changes, with a library release. A project **cannot** change any of them: the library takes them from the bundled defaults only, and the same keys under a service of `devsecops_config.yaml` change nothing. Exceeding a threshold colours the stage orange and blocks the Nexus release and the QC deployment, and never fails the build.

| Scanner | Metric | Library policy | Overridable by a project |
|---|---|---|---|
| SAST | maxCritical / maxHigh / maxMedium | 0 | no |
| SCA (SonarQube) | maxCritical / maxHigh / maxMedium | 0 | no |
| Dependencies (Nexus IQ) | maxCritical / maxHigh / maxMedium | 0 | no |
| DAST | maxCritical / maxHigh / maxMedium | 0 | no |
| Coverage | minLine | 60 % | no |
| Test jobs | maxParallel | 20 | yes, per stage in `tests.*.maxParallel` |
| SAST IRX generation | prepareTimeoutMin | 120 min | no |
| SAST result polling | pollTimeoutMin / pollIntervalSec | 50 min / 30 s | no |
| DAST result polling | pollTimeoutMin / pollIntervalSec | 60 min / 60 s | no |
| Static scan order | staticAnalysis.parallel | one after another; the bundled defaults leave the key out | yes: true in every service of `devsecops_config.yaml` or `config.yaml` runs Nexus IQ and SonarQube while AppScan scans, false in any service keeps the run one after another |

The required coverage is shown in the report exactly as configured in the bundled defaults, so a library release that raises or lowers it changes the value the report checks against and displays from the first build that loads it.

## 18. Supported Build Tools

| Tool | Build | Unit tests | Coverage | Release publish |
|---|---|---|---|---|
| Gradle | `./gradlew` + `build.gradle.tasks` and `flags` | `./gradlew` + `tests.unitTests.gradle.tasks` | JaCoCo XML and LCOV | `./gradlew publish` |
| Maven | `mvn` + `build.maven.goals` and `flags` | `mvn` + `tests.unitTests.maven.goals` | JaCoCo XML and LCOV | `mvn deploy` |
| Flutter | `flutter build <platform>` | `flutter test --coverage --concurrency=32 --reporter expanded` in the main package and in each of `tests.subplugins` and `tests.submodules` | LCOV of each package | Nexus upload of the APK/IPA |
| Node.js (stage 0, optional) | `build.node.commands`, by default `npm ci` and `npm run build` | – | – | – (the Gradle or Maven build packages the output) |

Every command comes from the settings of the service; the table shows where the library reads it. The build tool is `buildTool` of the service in `devsecops_config.yaml`; when it is empty, the library detects it by file existence (`pubspec.yaml` → Flutter, `pom.xml` → Maven, `build.gradle` → Gradle).

Gradle always runs through the `./gradlew` wrapper, which the library makes executable. Maven uses the installation of `build.maven.mvnPath` when it is set, otherwise `./mvnw` when the wrapper is committed, otherwise the `mvn` on the agent `PATH`.

The JDK comes from `javaPath` of the project, which the library exports as `JAVA_HOME` and prepends to `PATH`. With `buildToolAutoSetup: true` the library instead reads the required Java version from `pom.xml` or `build.gradle` and searches the agent for a matching JDK.

A failing unit test run does not stop the pipeline: the stage turns orange, the coverage of the reports that were produced is still checked, and the release gate blocks the Nexus release and the QC deployment.

### Node.js before Gradle or Maven

Node.js is not a build tool of its own: `buildTool` stays `gradle` or `maven`, and `build.node.enabled: true` adds a Node.js build that runs first, in stage 0. It suits a repository whose frontend npm builds and whose Gradle or Maven build then packages it, for example an Angular application served from a Spring Boot jar. Leave it off when the Gradle or Maven build already runs npm, for example through a Node plugin of the build.

- **Node.js:** `build.node.nodePath`, otherwise the NodeJS installation `build.node.installationName` (NodeJS plugin), otherwise the `node` and `npm` of the agent `PATH`.
- **npm registry:** an `.npmrc` Secret file (`build.node.npmrcCredentialsId`), otherwise `build.node.registry`.
- **Commands:** `build.node.commands` in `build.node.dir`, with `sh` or, on Windows, `bat`; at most `build.node.timeoutMin` minutes in all.
- **Output:** outside `build/` and `target/`, and copied into the artifact by the Gradle or Maven build, see [Troubleshooting](#23-troubleshooting-guide), **The frontend is missing from the jar**.

The GoldenFix pre-check runs in a git worktree of its own, without the output of the Node.js build, so a Gradle or Maven build that fails without the frontend output also fails its pre-check. A Flutter service has no Node.js build: leave `build.node` off for it.

### Coverage report detection

On Linux and macOS agents every JaCoCo and LCOV report of the service counts, as described under **Coverage reports** in Stage 2. On Windows agents, on agents without `python3` and when the search step fails, the pipeline reads, in order:

1. the paths of `coverage.reportPath` of the service that exist, a pattern (`*`, `?`, `**`) through `findFiles`, each file once;
2. Flutter: `total_lcov.info`;
3. Gradle: the first of `build/jacoco/jacoco.xml`, `build/reports/jacoco/test/jacocoTestReport.xml` and `build/reports/jacoco/jacocoTestReport.xml` that exists; Maven: the first of `target/site/jacoco/jacoco.xml` and `target/jacoco/jacoco.xml`;
4. every `jacoco.xml` of the workspace.

## 19. Supported Deployment Targets

### VM deployment over SSH

Used by `devSecOpsExtendedPipeline` for the lower test region and by every pipeline for the QC deployment. Set `deployTarget: vm` (the default) and fill in `deploy.vm.rd` and `deploy.vm.qc`.

The library connects over SSH and:

1. Verifies connectivity (`ssh ... uptime`)
2. Creates the deploy directory on the remote host
3. Copies `deployScript` and `versionFile` with `scp`
4. Executes `deployScript` on the remote host

For this to work:

- The Jenkins agent must have key-based SSH access to the deployment host
- `deployScript` must exist in your project and be executable
- `versionFile` must contain `APP_VERSION=<version>`

### VM deployment with UrbanCode Deploy

Used by `devSecOpsPipeline` for the lower test region (environment `DV`). Fill in `deploy.vm.dod`.

For every application of `deploy.vm.dod.applications`, ordered by `order`, the library:

1. Checks that each component has files matching `fileIncludePatterns` under `baseDir`
2. Publishes every component as a new version named `<versionPrefix>_<BUILD_ID>` (`UCDeployPublisher` push)
3. Creates the snapshot `<applicationName>_snapshot_<BUILD_ID>` and starts `deployProcess` on the environment

For this to work:

- The UrbanCode Deploy plugin must be installed and the site of `siteName` configured in Jenkins
- The artifact must exist under `baseDir` when the stage runs

### OpenShift deployment

Set `deployTarget: openshift` and fill in `deploy.openshift.rd` (and `qc`). The library uses the OpenShift Client plugin (`openshift.withCluster()`).

The snapshot stage:

1. Copies the artifact and the Dockerfile into `buildContext`
2. Creates the BuildConfig from `buildConfigPath` when it does not exist yet, and starts a binary build in `projectBuildR`
3. Waits for the build, reads the image digest and stores the image reference and the build tag `<BUILD_NUMBER>-<yyyyMMdd-HHmmss>` in `pipeline-config.yaml`
4. Copies the image with `skopeo` to `qcDockerRepoPush` under the build tag, the build number and `latest`

The deployment stage:

1. Clones the deployment repository into `deploymentPath` when `deploymentRepo` is configured
2. Applies the config template `configPathR` unless `skipConfigDeploy: true`
3. Applies the deployment template `deployConfigPath`, tags `qcDockerRepoPull:<buildTag>` as `<appName>:latest`, restarts the rollout and waits for it

The QC deployment reuses the build tag produced by the snapshot stage of the same flow, so `deploy.openshift.qc.buildTag` only has to be set when you want to deploy a different image.

For this to work:

- The OpenShift Client plugin must be installed and the cluster credentials stored in Jenkins
- The agent needs `oc` and `skopeo`, plus the docker authfile of `nexus.authfile`
- The templates named in `buildConfigPath`, `dockerFilePath`, `deployConfigPath` and `configPathR` must exist in the repository

## 20. Programmatic API Reference

### One devSecOpsApi instance per build

Jenkins keeps a library global in the binding of the script that first reads it, and every script under `vars/` owns a private binding. A `vars` script that reads `devSecOpsApi` therefore does not get the instance the Jenkinsfile holds, it gets a second one, with its own empty `PipelineState`.

That is why the stage methods take the instance as an argument instead of reading the global. When they read it instead, the build looks healthy in the log, because the stages run and report their result against the copy they created, while the entry point keeps reading its own untouched state. The visible result is a pipeline where:

- every box in the report is a grey `SKIP` and the coverage reads `not measured`, even though the stages ran,
- the Security Gates table has no counts and no report links,
- `release-gate.json` says `allowed: true` with no violations, so a build with findings above the policy is never blocked,
- `when { expression { devSecOpsApi.releaseAllowed(...) } }` lets the release and QC stages through,
- the security pipeline never triggers the extended pipeline, because the configuration it reads is empty.

The rule to follow when adding a stage or an entry point: the entry point resolves `devSecOpsApi` once and hands it to `devSecOpsSteps`, and nothing under `vars/` other than the entry points reads the global. `test/checks/vars-api.sh` fails the build when that rule is broken, and the pipeline scenarios in `test/sandbox/PipelineScenarios.groovy` resolve globals the way Jenkins does, so a second instance shows up there as a report full of grey boxes.

If the standard pipeline does not fit your project structure, you can call individual library methods from a custom Jenkinsfile. Import the library and use `devSecOpsApi`, the global variable that holds the services and every helper method. The five entry points are thin declarative skeletons built on top of it and on `devSecOpsSteps`, which carries one method per stage. `devSecOpsSteps` holds no state of its own: every one of its methods takes the `devSecOpsApi` instance to work with, so a custom pipeline calls `devSecOpsSteps.sast(devSecOpsApi)`, never `devSecOpsSteps.sast()`. A custom Jenkinsfile can call `devSecOpsSteps.staticAnalysis(devSecOpsApi)` in one stage instead of the three scan calls; it follows the same switch.

```groovy
@Library('DevSecOpsJenkinsLibrary') _

pipeline {
    agent any

    environment {
        SA_LINUX_URL       = 'https://tools.bbh.com/nexus/repository/releases/...'
        PROXY_HOST         = 'tstproxy.bbh.com'
        PROXY_PORT         = '9090'
        PROXY_USER         = 'PROXY_ASOCJenk'
        APPSCAN_HOST       = 'bbh.cloud.appscan.com'
        APPSCAN_SERVER_URL = 'https://bbh.cloud.appscan.com'
        APPSCAN_TOOLS_DIR  = "${WORKSPACE}/.appscan-tools"
        APPSCAN_LOG_DIR    = "${WORKSPACE}/.appscan-logs"
        APPSCAN_HOME_DIR   = "${WORKSPACE}/.appscan-home"
        APPSCAN_BIN_DIR    = "${WORKSPACE}/.appscan-bin"
    }

    stages {
        stage('Init') {
            steps {
                script {
                    checkout scm
                    devSecOpsApi.initialize()
                    devSecOpsApi.appscanSetup()
                    def projects = devSecOpsApi.getProjects()
                    for (p in projects) {
                        devSecOpsApi.switchProject(p)
                        devSecOpsApi.appscanResolveSourceDir()
                        devSecOpsApi.appscanGenerateIRX()
                    }
                    devSecOpsApi.appscanLogin()
                }
            }
        }

        stage('Build and test') {
            steps {
                script {
                    devSecOpsApi.buildArtifact()
                    devSecOpsApi.unitTests()
                    devSecOpsApi.checkCoverage()
                    devSecOpsApi.depVulnScan()
                    devSecOpsApi.codeQualityScan()
                }
            }
        }
    }

    post {
        always {
            script {
                devSecOpsApi.generateHtmlReport()
                devSecOpsApi.feedInfluxDB()
            }
        }
    }
}
```

`initialize()` reads `devsecops_config.yaml`, else `config.yaml`, of the workspace, so the `checkout scm` comes first, and builds the services of the `PROJECT_NAMES` or `PROJECT_NAME` environment variable or, without those, every service of the file. To name the services and the variant of the report, call `devSecOpsApi.configure('<variant>', [projectNames: 'my-app', agentNames: ['linux-agent']])` before `pipeline {}`, as the entry points do; `agentNames` is then required, and `securityPipeline` names the security job of an extended run.

The tool environment variables such as `APPSCAN_SERVER_URL` or the proxy settings are set by `initialize()`: a value already in the environment wins, then `platform.environment` of the bundled defaults, then the library's own default, so the `environment` block above is needed only when you want different values.

### Complete method reference

| Method | Description |
|---|---|
| `initialize()` | Set the tool environment variables, read `devsecops_config.yaml` (else `config.yaml`) and merge its services with the bundled defaults, write `pipeline-config.yaml`, check that the Nexus IQ GoldenFix pipeline has an application and scan patterns for every service, apply the library policy, detect OS |
| `configure(String variant, Map config)` | Keep `projectNames`, `agentNames` and `securityPipeline` of the Jenkinsfile (`agentNames` is required; `pipelineKey` and `pipelineKeys` stop the build), select the variant (`full`, `security`, `extended`, `sast`, `nexusiq`) and with it the report layout, and keep the entry point configuration; call it before `initialize()` |
| `settingsPlace()` | Where the settings of the run live, for messages: the file that was read, or before `initialize()` both names, `devsecops_config.yaml or config.yaml` |
| `runStage(String name, Closure body)` | Run a stage body with the bookkeeping: start, result, duration, and a red stage on an exception |
| `eachProject(Closure body)` | Run the body once per configured project, switching the project context first |
| `finishPipeline(Map options)` | Report, release gate verdict, metrics, artifact archiving and workspace cleanup in one call |
| `deployTarget()` | `vm` or `openshift` for the current project |
| `nexusDeliveryEnabled()` | `false` when `deploy.<target>.rd.nexusDeliveryEnabled` is false for the current project |
| `notRequired(String reason)` | Log the reason and mark the current project not required in the current stage; the stage ends `NOT_REQUIRED` when every project is |
| `nodeBuildRequired()` | `true` when a service of the run needs the Node.js build in this pipeline; the `when` guard of the Node.js build stage. It runs no step and logs nothing |
| `nodeBuildEnabled()` | `true` when `build.node.enabled` is true for the current project |
| `nodeBuildNeeded()` | `true` when the current project needs the Node.js build in this pipeline: enabled, and in the SAST scanning pipeline only while `asoc.doCompile` is not false on a Linux or macOS agent |
| `nodeBuild()` | Run the Node.js build of the current project (`build.node`): Node.js home, registry or `.npmrc`, environment, commands, timeout |
| `appscanSetup()` | Download and extract SAClientUtil |
| `appscanLogin()` | Authenticate the AppScan CLI |
| `appscanResolveSourceDir(String path = null)` | Set the IRX source directory |
| `appscanGenerateIRX(int timeoutMin = 120)` | Compile and generate the IRX archive |
| `appscanQueue(Map config)` | Queue the SAST scan for the current project |
| `appscanWait()` | Poll ASoC until the SAST scan completes |
| `appscanDownloadReports()` | Download the SAST report and parse the counts |
| `appscanRenameSastReport()` | Rename the SAST report with the scan name |
| `appscanRenameDastReport()` | Rename the DAST HTML and PDF reports with the scan name |
| `appscanEnforcePolicy()` | Check the SAST findings against the library policy, returns critical + high + medium |
| `sonarscanEnforcePolicy()` | Enforce the SonarQube quality gate |
| `dastScan()` | Full DAST run, downloads the HTML and PDF reports, returns the count |
| `buildArtifact()` | Build with Gradle, Maven or Flutter |
| `unitTests()` | Run unit tests with coverage collection |
| `checkCoverage()` | Read every coverage report of the project and compare their mean with the required value of the bundled defaults |
| `depVulnScan()` | Nexus IQ scan, with the `package-lock.json` of a Node.js build among the scan patterns; raises or updates the GoldenFix pull request on a violation |
| `codeQualityScan()` | SonarQube analysis |
| `reportArtifactBuild()`, `reportUnitTests()` | BBH build reporting |
| `smokeTests()`, `regressionTests()`, `performanceTests()` | Run all configured jobs with the configured parallelism |
| `releaseAllowed(String stageName)` | Release gate check; marks the stage `BLOCKED` and the build UNSTABLE when an earlier stage is not green |
| `publishReleaseGate()` | Write `release-gate.json` so the downstream pipeline inherits the verdict |
| `deployRD()` | Deploy to the lower test region over SSH (`deploy.vm.rd`) |
| `deployDvWithDodPlugin()` | Deploy to the lower test region with UrbanCode Deploy (`deploy.vm.dod`) |
| `deployQC()` | Deploy to the QC environment |
| `deployOpenshift(String env)` | Deploy to OpenShift, env `rd` or `qc` |
| `publishArtifactQC()` | Release delivery: `./gradlew publish` or `mvn deploy` |
| `pushToNexus(String projectName)` | Snapshot delivery of a Maven or Flutter artifact with the `delivery` command. It does not check `nexusDeliveryEnabled()`: a custom stage guards it with that method, as `devSecOpsSteps.nexusSnapshotDelivery` does |
| `vulnerabilitySummary()` | One line with the SAST and DAST findings above the policy severity; in the Nexus IQ GoldenFix pipeline the Nexus IQ critical, high and medium counts |
| `runExtendedPipeline()` | Start the job of `jenkins.pipeline.extendedPipeline` when `RUN_EXTENDED_PIPELINE` is selected |
| `nexusDelivery(String env)` | Tag the image in the registry |
| `buildDockerImage(String projectName)`, `copyImageToNexus()`, `checkDeploymentRepo()` | OpenShift image build and promotion. `buildDockerImage` and `copyImageToNexus` do not check `nexusDeliveryEnabled()`: a custom stage guards them with that method, as `devSecOpsSteps.nexusSnapshotDelivery` does |
| `bumpVersion(String file, String version)` | Update `APP_VERSION` in version.properties |
| `generateHtmlReport()` | Generate the HTML pipeline report |
| `feedInfluxDB(String pipelineType)` | Send DORA metrics to InfluxDB |
| `getProjects()`, `getCFG()` | Project names and the current project config |
| `switchProject(String name)` | Switch the project context |
| `stageStart/stageDone/stagePass/stageFail/stageWarn/stageError` | Stage bookkeeping for the report |
| `finishStage(String name)` | Close a stage: green unless the stage already recorded an orange or red result |
| `failStage(String name)` | Close a stage as failed and log the result |
| `logStageResult(String name, String status)` | Print the stage result and the scanner summary |
| `section(String text)`, `startSection(String text)`, `endSection(String text)` | Console separators |

`devSecOpsSteps.nodeBuild(devSecOpsApi)` is the body of the Node.js build stage: it runs `nodeBuild()` for every project that needs it and marks the others not required. `devSecOpsSteps.nexusReleaseDelivery(devSecOpsApi, [nodeBuild: true])` runs the Node.js build of each project that enables it before `publishArtifactQC()`, as the extended pipeline does; without the option it publishes only.

## 21. Monitoring and Metrics

Every run writes one batch of InfluxDB line protocol from `post { always }`, so a failed run is recorded exactly like a successful one. Nothing is sampled: the batch covers the run, every stage, every scanner, every module, every test suite, the release gate and the remediation outcome.

### What is written

| Measurement | Tags | Answers |
|---|---|---|
| `pipeline_run` | project, env, variant, result, branch | One row per run: outcome, duration, and how many stages passed, warned, failed, were blocked or skipped |
| `dora` | project, env, variant | Deployment, lead time from the commit under test, change failure and whether a release was published |
| `stage_event` | project, env, stage, status | Per stage: duration, whether it was executed, its order and the reason when it was not green. **Stamped at the end of that stage**, so the timeline is accurate |
| `security_findings` | project, env, module, scanner, status | Severity counts per scanner per module, the policy limits, and how many findings are above them; for `scanner=niq` only the status, the Nexus IQ counts are in `vulnerabilities` with `scanner=nexusiq` |
| `policy_status` | project, env, scanner, status | Which gates were evaluated and which held |
| `code_coverage` | project, env, module, measured | Line coverage of each module, the mean of its reports, against the required minimum, and the shortfall |
| `coverage_report` | project, env, module, report, tool, counted | One point per coverage report: its line coverage and whether it counts in the mean of its module |
| `test_execution` / `test_job` | project, env, module, suite, status, type | Suite totals and success rate, and each individual job |
| `release_gate` | project, env, allowed | Whether the release was permitted, and how many violations blocked it |
| `goldenfix` | project, env, module, status | Upgrades offered, applied and left to a developer, whether a pull request was raised or updated and whether the pre-check build withheld one, and the address and title of the pull request |
| `test_execution` with `suite=unit` | project, env, module, suite | Unit test totals of each module: total, passed, failed, skipped, success rate and duration |
| `build_evidence` | project, env, module | The change evidence of a build: the artifact version, the SonarQube quality gate, the links to the SAST, DAST, Nexus IQ and SonarQube reports, and the settings file the build read (`config_file`). Empty values are left out |

The measurements written before this revision - `deployments`, `change_failure`, `build_duration`, `stage_metric`, `vulnerabilities` and `test_coverage` - are still written unchanged, so dashboards built against them keep working. The `line_pct` of `test_coverage` and `code_coverage` is the mean of the reports of the run and of the module, which is the value written before when there is one report; their `covered`, `missed` and `total` are the sums over the same reports.

A run with a Node.js build writes its stage as `stage=Node.js build` in `stage_event` and `stage_metric` and counts it in `pipeline_run`; a run without one writes exactly what it wrote before. When Nexus IQ and SonarQube run while AppScan scans, the SAST duration covers the whole **Static analysis** stage, so the durations of stages 3 to 5 add up to more than the stage took. The stage order and the names do not change. In the `goldenfix` point a later service of a shared pull request has `status=PR_UPDATED` and `pr_raised` 1, and a service whose upgrades the shared pull request already carried has `applied` 0. The notes of a GoldenFix run are not written to InfluxDB.

### DORA

The four metrics are derived from the `dora` measurement:

- **Deployment frequency** - the `deployment` field is 1 when a deployment stage passed (`PASS`). A `NOT_REQUIRED` deployment stage rolled nothing out and is not counted.
- **Lead time for changes** - `lead_time_s` measures from the timestamp of the commit under test, read with `git log -1` during initialisation, to the end of the run. When the commit cannot be read the build start is used instead and the console says so.
- **Change failure rate** - `change_failure` is 1 for any run that did not end `SUCCESS`.
- **Time to restore** - derived in Grafana from the `change_failure` series; the query is in [Grafana dashboards and queries](#22-grafana-dashboard-query-reference).

### Configuration

```yaml
influx:
  enabled: true
  project: "MyApp"
  env:     "test"
  url:     "http://influx.example.com:8086/api/v2/write?org=DevSecOps&bucket=DORA-metrics&precision=s"
  credentialsId: "influxdb-token"    # Jenkins Secret text credential with the token
```

These are settings of the service in `devsecops_config.yaml`; an `influx` section in `defaults:` of the bundled defaults would be merged under every service, and the production file has none. A run with several services writes its metrics with the settings of the primary service, the first name of `projectNames`.

The `variant` tag names the pipeline that wrote the run: `full`, `security`, `extended`, `sast` or `nexusiq`. Every pipeline except the full one also appends its variant to the `project` tag, so the Nexus IQ GoldenFix pipeline of `influx.project: MyApp` writes `project=MyAppnexusiq`.

A bucket keeps the data for its retention period, and the cumulative "since the beginning" panels reach only as far back as that. Set the retention to infinite, or to the period the organisation reports on.

### Dashboards

[Grafana dashboards and queries](#22-grafana-dashboard-query-reference) holds ready Flux for every measurement above: the DORA row, pipeline health, security posture, quality and tests, remediation, and a set of cumulative panels that answer what the pipeline has delivered over successive runs and since the first build. The two dashboard definitions under `grafana/` continue to work; the stage panels of `devSecOpsDashboard` show the Build artifact stage of the Nexus IQ GoldenFix pipeline as `Build` and the Node.js build as `Node.js`, with the sort key of `Download sources`.

## 22. Grafana Dashboard Query Reference

Every build writes one batch of InfluxDB line protocol from `post { always }`, so a run that failed is recorded exactly like a run that succeeded. This document lists what is written and the Flux to put on a dashboard.

- **Bucket:** `DORA-metrics` (the `bucket=` parameter of `influx.url` of the service)
- **Written by:** `com.bbh.metrics.PipelineMetrics`, sent by `com.bbh.metrics.InfluxDbService`
- **Cadence:** one write per pipeline run, containing every measurement below

### Dashboard variables

Create these as **Query** variables so the panels below work unchanged.

| Variable | Type | Definition |
|---|---|---|
| `project` | Query | `import "influxdata/influxdb/schema"`, then `schema.tagValues(bucket: "DORA-metrics", tag: "project")` |
| `env` | Query | `schema.tagValues(bucket: "DORA-metrics", tag: "env")` |
| `module` | Query, multi, include All | `schema.tagValues(bucket: "DORA-metrics", tag: "module")` |
| `stage` | Query, multi, include All | `schema.tagValues(bucket: "DORA-metrics", tag: "stage")` |

For the "since the beginning" panels set the panel time range to `From: now-5y` or use the explicit `range(start: 0)` shown in section 7.

### What the pipeline writes

| Measurement | Tags | Key fields | Answers |
|---|---|---|---|
| `pipeline_run` | project, env, variant, result, branch | build, duration_s, success, unstable, stages_total, passed, warned, failed, blocked, skipped, not_required, modules, commit, job | One row per run: outcome and shape |
| `dora` | project, env, variant | deployment, lead_time_s, change_failure, duration_s, released | The four DORA metrics |
| `stage_event` | project, env, stage, status | duration_ms, duration_s, ok, executed, order, reason | Per stage, stamped at the end of that stage |
| `security_findings` | project, env, module, scanner, status | critical, high, medium, low, total, above_policy, max\_\*, exceeded; every count is 0 for `scanner=niq` | Findings per scanner per module against policy |
| `policy_status` | project, env, scanner, status | ok, measured | Which gates were evaluated and which held |
| `code_coverage` | project, env, module, measured | line_pct, covered, missed, total, required, met, gap | Coverage per module against the required minimum |
| `coverage_report` | project, env, module, report, tool, counted | line_pct (left out for a report without lines), covered, missed, total | Coverage per report; `counted=no` marks a copy, an aggregate, a report without lines or one that cannot be read |
| `test_execution` | project, env, module, suite | total, passed, failed, not_configured, duration_ms, success_rate; skipped for `suite=unit` | Smoke, regression and performance suites, and the unit tests of each module (`suite=unit`) |
| `build_evidence` | project, env, module | artifact_version, sonar_quality_gate, sast_report_url, dast_report_url, nexusiq_report_url, sonar_report_url, config_file | The change evidence of a build and the settings file it read |
| `test_job` | project, env, module, suite, status, type | duration_ms, name, ok | Individual test jobs |
| `release_gate` | project, env, allowed | allowed, blocked, violations, reason | Whether the release was permitted |
| `goldenfix` | project, env, module, status | offered, applied, unresolved, pr_raised, build_check, build_failed; pr_url and pr_title only when a pull request was raised | Automated dependency remediation |
| `deployments`, `change_failure`, `build_duration`, `stage_metric`, `vulnerabilities`, `test_coverage` | — | — | Kept unchanged so dashboards built before this revision keep working |

> **Note:** `stage_event` points carry the **end time of the stage** as their timestamp, not the time of the write, so a stage timeline is accurate to the second.

### DORA metrics

### 2.1 Deployment frequency

**Visualization: Time series (bars)**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "deployment")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: true)
  |> yield(name: "Deployments per day")
```

### 2.2 Lead time for changes

Time from the commit under test to the end of the run. **Visualization: Time series, unit `seconds (s)`; add a median reduction for a Stat panel.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "lead_time_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value > 0)
  |> aggregateWindow(every: v.windowPeriod, fn: median, createEmpty: false)
  |> yield(name: "Lead time (median)")
```

### 2.3 Change failure rate

**Visualization: Gauge, unit `percent (0-100)`, thresholds 15 / 30.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "change_failure")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> mean()
  |> map(fn: (r) => ({ r with _value: r._value * 100.0 }))
  |> yield(name: "Change failure rate %")
```

### 2.4 Time to restore service

The gap between a failing run and the next green one. **Visualization: Time series, unit `seconds (s)`.**

```text
import "experimental"

from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "change_failure")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> sort(columns: ["_time"])
  |> stateDuration(fn: (r) => r._value == 1, column: "broken_for", unit: 1s)
  |> filter(fn: (r) => r._value == 0 and r.broken_for >= 0)
  |> map(fn: (r) => ({ r with _value: float(v: r.broken_for) }))
  |> yield(name: "Time to restore")
```

> **Note:** `stateDuration` resets on every green run, so the value on a recovery point is how long the pipeline had been failing. Reduce with `mean()` for MTTR on a Stat panel.

### Pipeline health

### 3.1 Run outcome over time

**Visualization: State timeline, tag `result` as the value.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run" and r._field == "success")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> keep(columns: ["_time", "_value", "result", "branch"])
  |> yield(name: "Run outcome")
```

### 3.2 Build duration trend

**Visualization: Time series, unit `seconds (s)`.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run" and r._field == "duration_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: v.windowPeriod, fn: mean, createEmpty: false)
  |> yield(name: "Build duration")
```

### 3.3 Stage status breakdown of the latest run

**Visualization: Bar gauge or table.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(passed|warned|failed|blocked|skipped|not_required)$/)
  |> last()
  |> keep(columns: ["_field", "_value"])
  |> yield(name: "Stages of the last run")
```

### 3.4 Where the time goes — slowest stages

**Visualization: Bar chart, unit `seconds (s)`.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "stage_event" and r._field == "duration_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value > 0)
  |> group(columns: ["stage"])
  |> mean()
  |> group()
  |> sort(columns: ["_value"], desc: true)
  |> yield(name: "Average stage duration")
```

### 3.5 Which stage breaks most often

**Visualization: Bar chart.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "stage_event" and r._field == "ok")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value == 0)
  |> group(columns: ["stage"])
  |> count()
  |> group()
  |> sort(columns: ["_value"], desc: true)
  |> yield(name: "Not green, by stage")
```

### 3.6 Why it broke — the reasons behind the last failures

**Visualization: Table.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "stage_event" and r._field == "reason")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value != "")
  |> keep(columns: ["_time", "stage", "status", "_value"])
  |> sort(columns: ["_time"], desc: true)
  |> limit(n: 50)
  |> yield(name: "Failure reasons")
```

### Security posture

### 4.1 Open findings by scanner

**Visualization: Time series, stacked.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "total")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r.module =~ /^${module:regex}$/)
  |> group(columns: ["scanner"])
  |> aggregateWindow(every: v.windowPeriod, fn: last, createEmpty: false)
  |> yield(name: "Findings by scanner")
```

### 4.2 Findings above policy — the number that blocks a release

**Visualization: Time series with a threshold at 0.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "above_policy")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group(columns: ["scanner"])
  |> aggregateWindow(every: v.windowPeriod, fn: sum, createEmpty: false)
  |> yield(name: "Above policy")
```

### 4.3 Severity heatmap of the latest scan

**Visualization: Table or heatmap, group by module.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "security_findings")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(critical|high|medium|low)$/)
  |> last()
  |> pivot(rowKey: ["module", "scanner"], columnKey: ["_field"], valueColumn: "_value")
  |> yield(name: "Latest severities")
```

### 4.4 Gate health — which policies held

**Visualization: State timeline by `scanner`.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "policy_status" and r._field == "ok")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => exists r.scanner)
  |> yield(name: "Policy status")
```

### 4.5 Releases blocked by the gate

**Visualization: Stat (count) plus a table of reasons.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "release_gate" and r._field == "blocked")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> sum()
  |> yield(name: "Blocked releases")
```

### Quality and tests

### 5.1 Coverage per module against the required minimum

**Visualization: Time series, unit `percent (0-100)`, with a threshold line at the required value.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "code_coverage" and r._field == "line_pct")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}" and r.measured == "yes")
  |> group(columns: ["module"])
  |> aggregateWindow(every: v.windowPeriod, fn: last, createEmpty: false)
  |> yield(name: "Line coverage")
```

### 5.2 How far below the bar

**Visualization: Bar gauge, unit `percent (0-100)`.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "code_coverage" and r._field == "gap")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> last()
  |> filter(fn: (r) => r._value > 0)
  |> keep(columns: ["module", "_value"])
  |> yield(name: "Coverage shortfall")
```

### 5.3 Test suite success rate

**Visualization: Time series, unit `percent (0-100)`.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "test_execution" and r._field == "success_rate")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group(columns: ["suite"])
  |> aggregateWindow(every: v.windowPeriod, fn: mean, createEmpty: false)
  |> yield(name: "Suite success rate")
```

### 5.4 The test jobs that fail most

**Visualization: Table.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "test_job" and r._field == "name")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r.status != "SUCCESS" and r.status != "ALREADY IMPLEMENTED")
  |> group(columns: ["_value", "suite"])
  |> count()
  |> group()
  |> sort(columns: ["_value"], desc: true)
  |> limit(n: 20)
  |> yield(name: "Flaky or failing jobs")
```

### 5.5 Coverage of each report in the latest run

**Visualization: Table.**

All points of one run carry the same time, so the query keeps the points of the newest time and puts their fields side by side; a report without lines has an empty `line_pct`.

```text
reports = from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "coverage_report")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
latest = (reports
  |> filter(fn: (r) => r._field == "total")
  |> group()
  |> sort(columns: ["_time"])
  |> last()
  |> findRecord(fn: (key) => true, idx: 0))._time
reports
  |> filter(fn: (r) => r._time == latest)
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> group()
  |> keep(columns: ["module", "report", "tool", "counted", "line_pct", "covered", "total"])
  |> sort(columns: ["module", "report"])
  |> yield(name: "Coverage per report")
```

### Automated remediation (GoldenFix)

### 6.1 Pull requests raised

**Visualization: Time series (bars).**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "goldenfix" and r._field == "pr_raised")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: true)
  |> yield(name: "GoldenFix pull requests")
```

### 6.2 Upgrades offered, applied and left to a developer

**Visualization: Time series, stacked.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "goldenfix")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(offered|applied|unresolved)$/)
  |> group(columns: ["_field"])
  |> aggregateWindow(every: v.windowPeriod, fn: sum, createEmpty: false)
  |> yield(name: "Remediation funnel")
```

### 6.3 How often the pre-check build saved a broken pull request

**Visualization: Stat.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "goldenfix" and r._field == "build_failed")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> sum()
  |> yield(name: "Upgrades withheld because they did not build")
```

### What DevSecOps has delivered — cumulative views

These panels answer "what has this given us over successive runs, and since the beginning". Set the panel range to `now-5y`, or keep `range(start: 0)` as written to ignore the dashboard picker entirely.

### 7.1 Runs, deployments and failures since the beginning

**Visualization: Stat row, four panels sharing this query with a different `_field` filter.**

```text
from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "dora")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(deployment|change_failure|released)$/)
  |> group(columns: ["_field"])
  |> sum()
  |> yield(name: "Totals since day one")
```

### 7.2 Cumulative findings caught above policy

The running total of findings the gate has caught — the clearest single number for "what the pipeline prevented". **Visualization: Time series with `Fill opacity`.**

```text
from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "above_policy")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group()
  |> sort(columns: ["_time"])
  |> cumulativeSum(columns: ["_value"])
  |> yield(name: "Findings caught, cumulative")
```

### 7.3 Cumulative dependency upgrades merged into the codebase

```text
from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "goldenfix" and r._field == "applied")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group()
  |> sort(columns: ["_time"])
  |> cumulativeSum(columns: ["_value"])
  |> yield(name: "Dependency upgrades proposed, cumulative")
```

### 7.4 Coverage: where we started against where we are

**Visualization: Stat with `Text mode: value and name`.**

```text
base = from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "code_coverage" and r._field == "line_pct")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}" and r.measured == "yes")
  |> group()

first = base |> first() |> map(fn: (r) => ({ r with _field: "first recorded" }))
now   = base |> last()  |> map(fn: (r) => ({ r with _field: "latest" }))

union(tables: [first, now]) |> yield(name: "Coverage then and now")
```

### 7.5 Trend of the quarter — is the pipeline getting faster and safer

**Visualization: Time series, two axes.**

```text
duration = from(bucket: "DORA-metrics")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "pipeline_run" and r._field == "duration_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1w, fn: mean, createEmpty: false)
  |> map(fn: (r) => ({ r with _field: "build duration (s)" }))

failures = from(bucket: "DORA-metrics")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "above_policy")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1w, fn: sum, createEmpty: false)
  |> map(fn: (r) => ({ r with _field: "findings above policy" }))

union(tables: [duration, failures]) |> yield(name: "Quarterly trend")
```

### 7.6 Per-run comparison table — the last twenty builds side by side

**Visualization: Table, `Organize fields` to order the columns.**

```text
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(build|duration_s|passed|warned|failed|blocked)$/)
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> sort(columns: ["_time"], desc: true)
  |> limit(n: 20)
  |> yield(name: "Recent builds")
```

### Suggested dashboard layout

| Row | Panels |
|---|---|
| **Executive** | Deployment frequency · Lead time (median) · Change failure rate · Time to restore |
| **Value delivered** | Findings caught cumulatively (7.2) · Dependency upgrades cumulative (7.3) · Coverage then and now (7.4) · Blocked releases (4.5) |
| **Pipeline health** | Run outcome timeline (3.1) · Build duration trend (3.2) · Slowest stages (3.4) · Most broken stages (3.5) |
| **Security** | Findings by scanner (4.1) · Above policy (4.2) · Severity table (4.3) · Policy status timeline (4.4) |
| **Quality** | Coverage per module (5.1) · Coverage shortfall (5.2) · Suite success rate (5.3) · Failing jobs (5.4) · Coverage per report (5.5) |
| **Remediation** | Pull requests raised (6.1) · Remediation funnel (6.2) · Upgrades withheld (6.3) |
| **Detail** | Failure reasons (3.6) · Recent builds table (7.6) |

The two dashboard definitions under `grafana/` — `devSecOpsDashboard` and `devSecOpsSecurityDashboard` — were built against the measurements marked as kept in section 1 and continue to work. The stage panels of `devSecOpsDashboard` also map the Build artifact stage of the Nexus IQ GoldenFix pipeline to `Build` and the `Node.js build` stage to `Node.js`, with the same sort key 1 as `Download sources`. Panels from sections 2 to 7 are additions rather than replacements. The stage panels above group by the `stage` tag, so the Node.js build appears there under its full name in the runs that had one.

### Retention

The cumulative panels in section 7 read from the beginning of the bucket, so the bucket's retention period is what limits how far back "since the beginning" reaches. A bucket created with the default 30 day retention will silently shorten these panels; set the retention to `0` (infinite) or to the period the organisation wants to report on.

## 23. Troubleshooting Guide

### "\[INIT\] pipelineKey and pipelineKeys are settings of the DevSecOps portal ..."

The Jenkinsfile was written for the `main` release of the library and names a pipeline key. This release reads no key, also no empty one: the build stops before `pipeline {}` and runs nothing. The message names the entry point of the Jenkinsfile, for example "\[INIT\] pipelineKey and pipelineKeys are settings of the DevSecOps portal, which this release of the library does not read: write devSecOpsPipeline(projectNames: '&lt;services of this repository&gt;', agentNames: \['&lt;agent label&gt;'\]) and commit devsecops_config.yaml at the root of the repository"; for an extended pipeline it adds `, securityPipeline: '<security job>'`. Name the services of the repository and the agents in the Jenkinsfile, and commit `devsecops_config.yaml` with the settings of the services at the root of the repository:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(projectNames: 'gateway', agentNames: ['linux-agent'])
```

### "\[INIT\] No agentNames in the Jenkinsfile"

The Jenkinsfile names no Jenkins agent labels, so the build has no choices for its `AGENT_NAME` parameter and stops before `pipeline {}`: "\[INIT\] No agentNames in the Jenkinsfile: write devSecOpsPipeline(projectNames: '&lt;services of this repository&gt;', agentNames: \['&lt;agent label&gt;'\]) and commit devsecops_config.yaml or config.yaml at the root of the repository". The message names the entry point of the Jenkinsfile and, for an extended pipeline, adds `, securityPipeline: '<security job>'`. Add `agentNames` to the Jenkinsfile.

### "\[INIT\] Neither devsecops_config.yaml nor config.yaml is at the root of the repository"

"\[INIT\] Neither devsecops_config.yaml nor config.yaml is at the root of the repository: commit devsecops_config.yaml with a projects: section for the services of this repository (examples/CertScanner/devsecops_config.yaml shows one)". The checkout has neither file at its root. The file is not committed at the root, the job builds another branch, or the file is named in another case: the names are exact and case-sensitive. A Windows agent finds `Config.yaml` as `config.yaml` and a Linux agent does not, so a build that passes on `windows-agent` and fails on `linux-agent` has the file in another case; rename it to `devsecops_config.yaml`.

### "... must contain a 'projects:' section" or "not found in devsecops_config.yaml"

The file read has no `projects:` map, or the service named in `projectNames`, `PROJECT_NAMES` or `PROJECT_NAME` is not in it; the message lists the services of the file. Add the service to the file, or correct the name in the Jenkinsfile.

### "The library resource com/bbh/devsecops/defaults.yaml cannot be read"

"\[INIT\] The library resource com/bbh/devsecops/defaults.yaml cannot be read (&lt;cause&gt;): the global settings come from it; ask the DevSecOps team". Every run takes its global settings from this file of the library. The library version the job loads lacks the file, or another library loaded in the same build shadows it. Ask the DevSecOps team.

### "The last successful build of ... has no pipeline-config.yaml"

"\[INIT\] The last successful build of &lt;job&gt; has no pipeline-config.yaml: it was built by a library release that archived config.yaml instead; run &lt;job&gt; once with this release". The extended pipeline copies `pipeline-config.yaml` from the security job that its Jenkinsfile names in `securityPipeline`, and the last successful build of that job was made by an earlier release of the library, which archived `config.yaml`. Run the security job once with this release, then the extended job.

### "\[INIT\] Security pipeline &lt;job&gt; builds &lt;services&gt;, this run builds &lt;service&gt;"

The security build copied by the extended pipeline holds no run-time tags for a service of the extended run. The two Jenkinsfiles name other services; give both the same `projectNames`, or name the security job of these services in `securityPipeline`.

### "asoc.keyId must be set for the service &lt;service&gt; in ..."

Set the AppScan key ID of the named service in the file the message names (`devsecops_config.yaml` or `config.yaml` of the repository). Every service of the run needs its own. The key secret is a Jenkins **Secret text** credential named in `asoc.token`, never a value of the file.

### "JAVA_HOME parameter is not specified"

The project has neither `javaPath` nor `buildToolAutoSetup: true`, and the agent does not export `HAS_BUILD_TOOL_INSTALLED=true`. Add the JDK path of the agent to your project configuration:

```yaml
javaPath: /usr/lib/jvm/java-17-openjdk
```

### "Unit tests failed (script returned exit code 1)"

The test command returned a non-zero exit code. The stage is orange, not red: Nexus IQ, SAST, SonarQube, the snapshot delivery and the RD deployment still run, and only the Nexus release and the QC deployment are blocked. Open the console output of the stage for the failing tests. When Gradle stops at `jacocoTestCoverageVerification`, the coverage rule of your own build is stricter than the run allows; the library checks the coverage itself, so the task is not required in `tests.unitTests`.

### "\[NODE\] &lt;service&gt;: build.node.enabled is not set - no Node.js build"

Not an error. Another service of the run has a Node.js build, so the stage runs, and this service has none. When the service has a frontend that npm must build first, set `build.node.enabled: true` for it in `devsecops_config.yaml`.

### "\[NODE\] &lt;service&gt;: the SAST scan does not compile this service - no Node.js build"

Not an error. In the SAST scanning pipeline the Node.js build of a service runs only when its SAST scan compiles it, and this one does not: `asoc.doCompile` is `false`, or the agent is Windows. The IRX archive is prepared from the sources only.

### The Node.js build stage is red

A command of `build.node.commands` failed, or the Node.js build could not start. The console shows the step `Node.js: <command>` with the output of npm, after the line `[NODE] <service>: Node.js from ... in ...` that names the Node.js and the folder used. The pipeline stops there; the report and the metrics are still written.

- `npm ci` needs a committed `package-lock.json` in `build.node.dir` that matches `package.json`. Commit the lock file, or set the commands to `npm install` and `npm run build`.
- A wrong folder: `build.node.dir` is relative to the workspace, which is the repository root.
- No Node.js: the `tool` step stops when no NodeJS installation of the name in `build.node.installationName` exists under **Manage Jenkins → Tools**, and `node: not found` or `npm: not found` means the agent has none on its `PATH`. Name an existing installation, set `build.node.nodePath` to the Node.js folder of the agent, or ask the Jenkins administrators to install Node.js.
- npm cannot reach or log in to the registry: set `build.node.registry`, or an `.npmrc` Secret file in `build.node.npmrcCredentialsId` when the registry needs a login. A package that exists only in the registry of an `.npmrc` in the repository fails with 404 while `build.node.registry` (or its value in the bundled defaults) is set, because `npm_config_registry` wins over that file: leave the registry empty, or name a registry that holds every package.
- The build ran out of time: raise `build.node.timeoutMin` (default 30 minutes, for all commands together), or out of memory: add `NODE_OPTIONS: "--max-old-space-size=4096"` to `build.node.env`.

### The frontend is missing from the jar

The Node.js build ran, but the artifact holds no frontend. The Node.js build only leaves its output in the workspace; the Gradle or Maven build must copy it into the artifact, and nothing else does:

- Write the output outside `build/` and `target/`. The `clean` task that Gradle and Maven usually run first deletes those folders, and the output with them.
- Copy the output into the jar. With Gradle, in the `build.gradle` of the service, for an Angular output folder `frontend/dist/my-app` served as static content:

```groovy
processResources {
    from('frontend/dist/my-app') {
        into 'static'
    }
}
```

With Maven, in the `pom.xml` of the service:

```xml
<build>
  <resources>
    <resource>
      <directory>src/main/resources</directory>
    </resource>
    <resource>
      <directory>frontend/dist/my-app</directory>
      <targetPath>static</targetPath>
    </resource>
  </resources>
</build>
```

- OpenShift: the image is built from the artifact alone (`artifactName` in the build context), so the frontend reaches the image only inside the jar.
- GoldenFix verifies its upgrades in a worktree of its own, without the output of the Node.js build, so its Gradle or Maven pre-check must build without it as well.
- When the Gradle or Maven build already runs npm itself, for example through a Node plugin of the build, switch the Node.js build off.

### "\[DOD\] No UrbanCode Deploy applications are configured"

`devSecOpsPipeline` deploys VM projects to the lower test region with UrbanCode Deploy. Add `deploy.vm.dod` with a site, an application and at least one component, or use `devSecOpsSecurityPipeline` plus `devSecOpsExtendedPipeline`, which deploys over SSH with `deploy.vm.rd`.

### "The required parameter (buildTag) is not defined"

An OpenShift deployment ran without the image built by the snapshot stage. In the split setup the build tag travels in `pipeline-config.yaml`, so the extended job must copy it from the static job: name the static job in `securityPipeline` of the Jenkinsfile of the extended job, and run the static job once with this release of the library.

### "Scripts not permitted to use ..."

The Jenkins sandbox rejected a signature. The library is tested against the real sandbox before a release, so this points at a library bug rather than at your configuration: send the whole message, including the signature in the brackets, to the library team. Do not approve the signature in **In-process Script Approval**.

### SAST stage takes very long or times out

- Default `sast.pollTimeoutMin` is 50 minutes. This is a global setting of the bundled defaults that only the DevSecOps team can change.
- The IRX generation step (compile via Gradle + AppScan prepare) can take 10–20 minutes on large projects.
- In the **Static analysis** stage the status poll starts after Nexus IQ and SonarQube; its timeout counts from then.

### "\[STATIC\] &lt;services&gt;: the SAST compile runs other Gradle tasks or Maven goals than the build command"

Nexus IQ and SonarQube run while AppScan scans only when the SAST compile builds the same files as the build command. The named services set `asoc.gradle` or `asoc.maven` to other tasks, goals, flags or settings than `build.gradle` or `build.maven`, so the run keeps Nexus IQ, SAST and SonarQube one after another. To use the new order, make the compile command of the service equal to its build command, leave it empty (the SAST compile then runs the build command), or set `asoc.doCompile: false`.

### "\[STATIC\] SAST stopped while it prepared its scans: Nexus IQ and SonarQube do not run in this build"

SAST failed before every scan was queued, in the AppScan client, the login, the compile, the IRX archive, the upload or the queue, so the workspace may be half built and Nexus IQ and SonarQube do not run on it. The report shows Nexus IQ as SKIP and SonarQube as blocked, and the release gate lists the SAST failure. Fix the SAST failure the console shows above the message; Nexus IQ and SonarQube run again in the next build.

### How to switch the order off for all jobs, one agent or one job

Set the environment variable `DSO_STATIC_ANALYSIS_PARALLEL=false` in **Manage Jenkins → System → Global properties → Environment variables** for all jobs, or in the environment variables of the node properties of an agent for the runs on that agent: the full and security pipelines then run Nexus IQ, SAST and SonarQube one after another, whatever `staticAnalysis.parallel` says. `true` turns the order on without the setting. A value set on a Jenkins folder or job counts only when a plugin puts it into the environment of the build ([the configuration reference](#15-configuration-reference-devsecops_configyaml)). For one job, set `staticAnalysis.parallel: false` on one of its services in `devsecops_config.yaml` or `config.yaml`: one such service keeps the whole run one after another. A custom Jenkinsfile built on `devSecOpsApi` ([the API reference](#20-programmatic-api-reference)) can also set `DSO_STATIC_ANALYSIS_PARALLEL = 'false'` in its top-level `environment` block. When the bundled defaults set `staticAnalysis.parallel: true`, every job without its own value follows them, and the DevSecOps team switches the order off for all of them with a library release that removes it.

### "IRX not found after prepare"

- The Gradle/Maven build during IRX generation failed. Check the `prepare.log` file in `.appscan-logs/`.
- Ensure the Gradle wrapper (`gradlew`) is executable and committed to the repository.
- The library sets `chmod +x gradlew` automatically on Linux/Mac.

### SonarQube stage fails with "SonarQube server not configured"

The SonarQube server must be registered in Jenkins as `SonarQube` (exactly this name) under **Manage Jenkins → Configure System → SonarQube servers**.

### Nexus IQ stage shows 0 vulnerabilities but the build is unstable

The Jenkins sandbox may have blocked direct access to the `nexusPolicyEvaluation` result object. The library has a fallback that parses the build console log. If that also fails, counts remain 0 but the build result (UNSTABLE/FAILURE) set by the Nexus IQ plugin itself is still respected.

### HTML report is empty or shows "Report could not be generated"

This happens when the pipeline fails very early (before `initialize()` completes). A fallback minimal HTML file is always written. Check the stage 1 console output for the root cause.

### Tests stage is orange with "no test jobs configured"

All three test stages (smoke, regression, performance) require at least one configured job:

```yaml
tests:
  smoke:
    jobs:
      - name: "My job"
        type: local
        job:  "path/to/job"
```

An entry with an empty `job:` is reported as `NOT_CONFIGURED` and counts as a job that did not succeed, so the stage turns orange. When the whole `jobs:` list is missing, the stage turns orange as well and the release and QC stay blocked.

### Nexus release or QC deployment was skipped

The release gate blocked it because at least one earlier stage is orange. The **Release policy** card of the report lists every violation, for example `gui Dependencies (Nexus IQ) critical 1 > 0`, `stage 'Smoke tests' is WARN` or `line coverage 41.0% below the required 60%`. Remediate the findings, merge the GoldenFix pull request, or accept that this build stays on RD. The snapshot delivery and the RD deployment are never blocked.

### A stage is orange and the build is UNSTABLE

That is the normal reaction to a policy violation or a failed test job. The stage box in the report carries a **Reason** line with the failed step, the vulnerability counts and the sentence that the Nexus release and the QC deployment stay blocked. The pipeline continues with every remaining stage.

### GoldenFix pull request was not raised

The GoldenFix card explains the outcome in plain language, so that a reader who does not work on the pipeline can act on it. The wording follows the internal status:

| Status | What the card says, and what to do |
|---|---|
| `PR_CREATED` / `PR_UPDATED` | The upgrade is waiting for review, with a link to the pull request. `PR_UPDATED` means that an earlier service of the same build and repository raised the pull request: this service added its upgrades to it with a comment, or found them there already (`Already raised to ...`) |
| `NOT_CONFIGURED` | The upgrades are ready but the repository address is missing. Fill in `scm.bitbucket.url` with the repository link and `scm.bitbucket.credentialsId` with the Jenkins credentials allowed to write to it, and the pull request is raised on the next run |
| `NO_FIXES` | The supplier has published no safe version yet, so the components have to be replaced or the risk accepted |
| `NO_MANIFEST_CHANGES` | The vulnerable versions are not chosen in a file the pipeline can change. They arrive through another library, typically managed by a parent POM or a BOM, or they are set in a way GoldenFix does not edit, such as a range, so the upgrade belongs there. The reasons under "not applied automatically" name the BOM, the catalog or the catalog artifact when GoldenFix found one; the entries below explain each of them |
| `BUILD_FAILED` | The pre-check build failed with the proposed versions and again in every further attempt, with the Nexus IQ version instead of a catalog version or with lower versions, up to `goldenFix.verify.maxAttempts`, so no pull request was opened. The console lines `[GOLDENFIX] Pre-check build ...` and the build output above them show what failed; see [Pipeline Stage Reference](#16-pipeline-stage-reference) |
| `SKIPPED` | Automatic upgrades are switched off for this project |
| `ERROR` | The run stopped before it could propose anything and the repository was not touched. The card names it as a fault in the pipeline run and carries the technical detail for the DevSecOps team; the `[GOLDENFIX]` lines in the console hold the full context, usually Nexus IQ or Bitbucket permissions. An internal error of the library, such as a `StackOverflowError`, is named by its class |

The entries below explain the reasons, notes and console lines of GoldenFix one by one. [Pipeline Stage Reference](#16-pipeline-stage-reference) describes the whole remediation.

### "managed by version catalog &lt;file&gt; (&lt;alias&gt;): ..."

The component is defined in a version catalog of the repository, and GoldenFix could not raise it there:

- `declared version '<v>' is dynamic or a range`: the catalog declares a range such as `[1.0,2.0[` or a dynamic version such as `2.+`. Write a concrete version in the catalog, or raise the range by hand.
- `strictly range '<range>' excludes <target>`: a rich version holds a `strictly` range that the fixed version lies outside, so raising its `prefer` or `require` would not change what Gradle resolves. Widen or remove the range by hand. In a build file outside the catalogs the same reason reads `<file>: strictly range '<range>' excludes <target>`.
- `not found or already newer`: a build asks the catalog for a key, for example `libs.versions.jackson`, and the catalog has no such key, or already holds a version at least as new as the fix. Check the spelling of the key; `-`, `_` and `.` count as the same character.

### "managed by BOM &lt;group&gt;:&lt;artifact&gt;:&lt;version&gt; imported in &lt;file&gt;: raise the BOM or add an override to the catalog"

A BOM or platform that `<file>` imports manages the version of the component, so the component has no version of its own to raise. GoldenFix names a BOM when the group of the component is the group of the BOM or lies below it. Raise the BOM to a version that brings the fix, or declare the component with the fixed version in the catalog or the build, which overrides the BOM. GoldenFix raises such a declaration itself on later runs.

### "managed by the version catalog artifact &lt;group&gt;:&lt;artifact&gt;:&lt;version&gt; outside this repository"

`settings.gradle` imports the catalog from a published artifact (`from("<group>:<artifact>:<version>")`), not from a file of the repository. GoldenFix changes only the repository of the service. Raise the version in the repository that publishes the catalog, publish it, and raise the catalog version in `settings.gradle`.

### "version &lt;name&gt; is defined in buildSrc/&lt;file&gt;: not updated automatically"

The build reads the version as a constant of `buildSrc`, for example `Versions.jackson`. GoldenFix finds the file that defines it but does not change `buildSrc` sources. Raise the constant by hand, or move the version into a `gradle/libs.versions.toml` catalog, which GoldenFix raises.

### "version catalog &lt;path&gt; not found in the repository" or "version catalog &lt;path&gt; is outside the repository"

A path of `goldenFix.versionCatalogs` of the service matched no file, or matched only files in a folder of `goldenFix.excludeDirs`; or it is an absolute path, a Windows path or a path with `..`. Paths are relative to the root of the repository, with `/` between folders, and may use `*`, `**` and `?`; `./`, a doubled `/` and a trailing `/` are ignored. Correct the path in `devsecops_config.yaml`, or remove it: the usual catalogs are found without being listed. The run continues; the note only appears in the report card and in the pull request.

### "&lt;file&gt;: &lt;component&gt; declared outside the catalog &lt;catalog file&gt; (&lt;alias&gt;)"

Not an error. A build file declares a version of a component that a catalog of the repository also defines. GoldenFix raised both, and the build file to at least the version of the catalog, so that the two stay aligned. Move the declaration into the catalog when you review the pull request.

### "&lt;folder&gt;/package-lock.json refreshed"

Not an error. The npm pre-check refreshed the lock file next to a changed `package.json` with `npm install --package-lock-only --ignore-scripts`, and GoldenFix committed it with the change. Review it with the pull request.

### "GoldenFix: no Gradle build found for &lt;file&gt; - not verified"

GoldenFix changed a file that it treats as a Gradle catalog, and found no Gradle build that uses it. This is the case for a `*.versions.toml` that no `settings.gradle` imports (other than `gradle/libs.versions.toml` next to a `settings.gradle`), for a file of `goldenFix.versionCatalogs` that no build imports or applies, and for a `java-platform` module outside every folder that holds a `settings.gradle`. The change is in the pull request without a pre-check build. Import the catalog in the `settings.gradle` of the build that uses it, or build the branch yourself before merging.

### "Already raised to &lt;component&gt; &lt;version&gt; in the shared pull request &lt;url&gt;"

Not an error. An earlier service of the same build and repository raised the same entries of a shared catalog on the shared branch, to the version shown or higher. The service makes no commit and no comment, ends `PR_UPDATED`, and its card links the shared pull request.

### "\[GOLDENFIX\] The pull request comment could not be added: &lt;reason&gt;"

The service added its commit to the shared pull request, but Bitbucket refused the comment that lists its upgrades under `GoldenFix for <service>`. The upgrades are on the branch and the service stays `PR_UPDATED`; the card of the service still lists them. The reason quotes the answer of Bitbucket. Usually the credentials of `scm.bitbucket.credentialsId` may push but not comment: give them the permission to comment on pull requests in the repository.

### "\[GOLDENFIX\] The branch &lt;branch&gt; already exists in &lt;repository&gt; - another GoldenFix job of this repository ran in the same minute; ..."

Two GoldenFix jobs for services of one repository started in the same minute, computed the same branch name `GoldenFix-YYYYMMDDHHMM`, and git refused the second push because the branch exists (`! [rejected] ... (fetch first)` or `(non-fast-forward)` in the output of `git push`). The first job raised its pull request; the second one ends with `ERROR`. Run one Nexus IQ GoldenFix job for all services of the repository, named together in `projectNames`, as described in [Integration Guide: Nexus IQ GoldenFix Pipeline](#13-integration-guide-nexus-iq-goldenfix-pipeline). The services then share one branch and one pull request.

### "\[GOLDENFIX\] git push of &lt;branch&gt; to &lt;repository&gt; failed (exit code &lt;n&gt;): ..."

Git or Bitbucket refused the push of the GoldenFix branch for another reason than an existing branch. The message quotes the `remote:` and `! [remote rejected]` lines of git, for example `! [remote rejected] HEAD -> GoldenFix-202609191405 (pre-receive hook declined)`; the step `GoldenFix: git push` above it shows the whole output. Usual causes are a branch permission that does not let the credentials of `scm.bitbucket.credentialsId` create `GoldenFix-*` branches, a hook that checks commit messages or file sizes, and wrong credentials (exit code 128). The service ends with `ERROR` and no pull request. Ask the Bitbucket administrators to let these credentials push `GoldenFix-*` branches.

### DAST PDF report is missing in the pipeline report

The stage downloads the report as HTML and as PDF and archives both. The PDF is not required: when AppScan does not render it in time the console carries `[DAST] PDF report unavailable`, only the HTML link is shown and the stage is unaffected.

### DAST fails with "the HTML report could not be produced"

The HTML report **is** required, because the findings of the scan are read from it. After asking AppScan to render the report the library polls until the report is ready, downloads it and verifies that the file constitutes a report. A build fails this way when:

- AppScan answered with a message instead of a rendered report, which the console quotes;
- the report was still not ready when `dast.reportTimeoutMin` ran out;
- AppScan reported the rendering as `Failed`.

`dast.reportTimeoutMin` (default 30 minutes) and `dast.reportIntervalSec` (default 30 seconds) are global settings of the bundled defaults: ask the DevSecOps team to raise them for scans whose reports take longer to render. The stage deliberately fails instead of recording zero findings: a green DAST stage always means a real report was parsed.

### Hundreds of smoke jobs

All jobs are executed even when some fail, and at most `tests.maxParallel` run at the same time. Raise `maxParallel` only when the remote Jenkins instances have enough executors.

---

### Problems on a first run

| What you see | What it means and what to do |
|---|---|
| `[INIT] pipelineKey and pipelineKeys are settings of ...` | The `Jenkinsfile` was written for the `main` release and names a pipeline key. Name the services in `projectNames` and the agents in `agentNames`, and commit `devsecops_config.yaml`. |
| `No agentNames in the Jenkinsfile` | The Jenkinsfile names no agent labels. Add `agentNames`. |
| `Neither devsecops_config.yaml nor config.yaml is at the root of the repository` | The repository needs one of the two files, spelled exactly so, at its root, on the branch the job builds. |
| `asoc.keyId must be set` | The AppScan key ID is missing from the named service in the file the message names, `devsecops_config.yaml` or `config.yaml`. |
| `[SONAR] Badge token credentials ... could not be read` | The credential named in `tools.sonar.badgeCredentialsId` does not exist or is not Secret text. The stage result does not change; the report uses `badgeToken` or shows no badges. |
| `[OPENSHIFT] <service>: RD deployment not required`, `QC deployment not required` | Nexus delivery is off for the service and the region has no build tag, so there is no image to deploy: RD needs `deploy.openshift.rd.buildTag`, QC its own `deploy.openshift.qc.buildTag` or the RD tag. Pin a tag or turn the delivery on. |
| `The Nexus IQ GoldenFix pipeline would scan nothing` | The service has no Nexus IQ application or no scan patterns in the file the message names, `devsecops_config.yaml` or `config.yaml`. Set both under `tools.nexusIq`. |
| SAST fails while connecting, or times out | The agent cannot reach AppScan through the proxy. Raise it with the DevSecOps team quoting the agent label — the proxy account, the OIS certificate or the network route needs attention. Nothing in your configuration fixes this. |
| Nexus IQ reports the application does not exist | `tools.nexusIq.application` does not match an application in Nexus IQ. |
| Coverage shows `not measured` | No JaCoCo or LCOV report with measured lines was found. The log of the Unit tests stage names the folders searched and every report read. Confirm your test task generates a JaCoCo XML or LCOV report, or point `coverage.reportPath` at it. |
| A coverage report is marked `not counted` | It is a copy of another report, combines other reports, has no lines or cannot be read; the row names the reason. To count other reports, name them in `coverage.reportPath`. |
| A coverage report says `also counted for` another service | The services are tested in the same folder, and the report belongs to no build tool of them, or to the build tool of both, so it counts for each. A service that names its reports in `coverage.reportPath` is named only for those reports; when its paths name no report, it searches like the others and is not named. Set the folder of the unit test command or of the Node.js build of each service, or name the reports of each in `coverage.reportPath`. |
| The coverage of a service changed after the library update | The service has more than one report (modules, a frontend LCOV report, an integration test report, an older report under `release`), and its coverage is now their mean; a Flutter service is measured per package. The log and the card list them; set `coverage.reportPath` to the reports that should count, for Flutter `total_lcov.info`. |
| A service counts a report of another service | The report, or a copy of it, for example under `release/<other service>`, lies in a folder in which the service is tested, outside the folders of the other service. The summary of the run counts a byte copy once. Name the reports of the service in `coverage.reportPath`, or test it in a folder of its own. |
| The log says `The coverage scan failed` | The `python3` of the agent could not run the search, for example a shim without a configured version, or the search took longer than 15 minutes. The reports were read as before; the reason follows in the log. If `python3` could not run, fix it on the agent to get every report. If the search took longer than 15 minutes, name the reports of the service in `coverage.reportPath`: a path without `*` or `?` is read without a search, and a pattern searches only the folder before its first wildcard, so start it with a folder, such as `build/reports/jacoco/**/*.xml`. |
| DAST fails saying the HTML report could not be produced | AppScan did not render the report in time or returned a message instead. The stage deliberately fails rather than recording zero findings. Re-run; if it repeats, ask for a higher `dast.reportTimeoutMin`. |
| A test stage is orange with `NOT_CONFIGURED` | A test job named for the service in `devsecops_config.yaml` does not exist in Jenkins yet. Create it, or accept the orange stage until the suite is ready. |
| No dependency upgrade pull request appears | The pipeline needs `scm.bitbucket.url` and a credential with write access. The report states this in plain language when it is the cause. |
| The Node.js build stage fails at `npm ci` | `npm ci` needs a committed `package-lock.json` in `build.node.dir`. Commit it, or set `build.node.commands` to `npm install` and `npm run build`. |
| The frontend is missing from the jar | The Node.js build leaves its output in the workspace; the Gradle or Maven build must copy it into the jar. See **The frontend is missing from the jar** above. |
| `[GOLDENFIX] The branch ... already exists` | Two GoldenFix jobs of one repository started in the same minute. Run one job for all services of the repository, named together in `projectNames`. |
| `[GOLDENFIX] git push of ... failed` | Bitbucket refused the push, for example through a branch permission or a hook; the message quotes the answer of the remote. |
| The extended pipeline cannot copy artifacts | The Copy Artifact permission on the upstream security job. Ask the Jenkins administrators. |

### Where to go for help

| Question about | Ask |
|---|---|
| The library, the report, a stage that behaves unexpectedly | The DevSecOps team |
| The bundled defaults: thresholds, coverage minimum, timeouts, platform values | The DevSecOps team |
| AppScan connectivity, the proxy account, OIS certificates, a DAST Presence | The DevSecOps team |
| Agents, credentials, registering the shared library, Copy Artifact permissions | The Jenkins administrators |
| Nexus IQ applications and policies | The Nexus IQ administrators |
| Nexus repositories and publishing accounts | The Nexus administrators |
| Deployment targets, hosts and deployment accounts | The owners of your test environments |

## 24. Library Development and Test Suite

This section is for the team that changes the library, not for the projects that use it.

### One command

```bash
bash test/run-all.sh
```

The script downloads the tools it needs once (JDK 21, Groovy 2.4.21, the Jenkins 2.479.3 libraries and the `script-security` plugin) into `${DEVSECOPS_TOOLS:-$HOME/.cache/devsecops-library-tools}`, then runs everything below and regenerates the demo reports in `examples/reports`, `documentation.html` and `documentation.md`. It prints `ALL CHECKS PASSED` when the library is releasable.

### What it runs

| Step | What it proves |
|---|---|
| `test/checks/sandbox-lint.sh` | No construct that needs an administrator approval, no static field, no `map."${key}"` |
| `test/checks/undefined-calls.sh` | Every implicit call resolves to a declared method |
| `test/checks/undefined-variables.groovy` | Every name in a library class is a local, a parameter or a field |
| `test/checks/api-consistency.sh` | Every call on a library type matches a method with that argument count |
| `test/checks/closure-calls.groovy` | No unqualified call inside a closure of a static method |
| `test/checks/closure-field-writes.groovy` | No closure assigns a field of its own class |
| `test/checks/noncps-calls.groovy` | No `@NonCPS` method calls a library method that is CPS-transformed (Jenkins would run it with the wrong result) |
| `test/checks/jdk-whitelist.groovy` | Every JDK constructor, static method and static field is on the script-security whitelist |
| `test/checks/architecture.sh` | Adapters depend on the core and the ports, never the other way round |
| `test/checks/vars-api.sh` | The shared methods resolve, every entry point declares its stages in order (the full pipeline equals security plus extended), and every manifest updater is wired into the GoldenFix service |
| `test/checks/no-comments.sh` | No comments and no commented-out code in `src` and `vars` |
| `test/checks/ascii.sh` | `src` and `vars` hold printable ASCII only |
| `test/checks/docs-html.sh` | `documentation.html` is the rendering of `documentation.confluence`; the check regenerates it when it is not |
| `test/checks/docs-md.sh` | `documentation.md` is the Markdown rendering of `documentation.confluence`; the check regenerates it when it is not |
| compilation | `src` and `vars` compile with the sandbox compiler configuration |
| `test/coverage/scan-scenarios.sh` | The scan script of `CoverageReports`, run with `python3 -I` on report trees: services that share a folder, modules with release folders, copies, aggregates and their rest, patterns, Android, Kover, Flutter and LCOV variants, non-ASCII names, the SHA-1 of each report, a report of another service skipped before it is read, the time limit, the class listing of the earlier reader and 400 module reports |
| `test/sandbox/SandboxScenarios.groovy` | Every class initialises under the whitelist used while the CPS program is saved; the AppScan commands match the recorded baseline; the AppScan severity totals come from the report summary; a Nexus IQ report with JSON nulls still yields components; the GoldenFix version choice only changes the patch version and takes the newest clean one; all remediation lookups of a project are one shell step; GoldenFix patches a real `pom.xml`, `build.gradle`, `gradle.properties`, `package.json`, `requirements.txt` and `pubspec.yaml`; GoldenFix raises shared version catalogs in every supported form, aligns the other declarations with them, names the reason for what it cannot raise, verifies each shared file in the builds that use it and shares one pull request between the services of a repository; it raises a catalog of 300 libraries and a `resolutions` block of 300 entries on a 1 MB thread stack, reads TOML headers with comments, indentation or spaces, keys in quotes and literal strings, leaves a `strictly` or `require` range that excludes the target alone (bounds in the version order of Gradle), changes only the catalog block of a settings file, ignores `./` in listed paths, reads a listed pom with another name and builds it with `mvn -f`, also with a Maven command of `goldenFix.verify.commands`, resolves `from files(..)` and `$rootDir` imports, shows a property raised in both rounds as one row with the components of both, builds the Nexus IQ version when the catalog version breaks the build without using up an attempt, labels a declaration or a property aligned with a catalog, counts only what the shared branch changed in a shared catalog as already raised, looks in `buildSrc` only for a holder it can declare and records an escaping `Error` as `ERROR`; it refreshes a `package-lock.json` (not in front of an npm command of `goldenFix.verify.commands`), raises npm `overrides` and `resolutions`, tells a branch that exists from a push a hook declines and names pub components; the `package-lock.json` pattern of a Node.js build names its folder without `./`; the Node.js build runner sets up Node.js on Linux and Windows, and the npm pre-check of GoldenFix runs inside it; 200 smoke jobs run with the configured parallelism; the `goldenfix` point carries `pr_url` and `pr_title` only when a pull request was raised; eight service level pipelines produce the demo reports |
| `test/sandbox/CoverageScenarios.groovy` | `checkCoverage`, the coverage card, the metrics and the release gate with a fake scan: one row per report, the mean of a service and of the run, the reports of shared folders and of `coverage.reportPath`, a copy of the report of another service, the fallbacks without `python3` or after a failed scan, Windows, and one real `sh` run of the scan step |
| `test/sandbox/StaticAnalysisScenarios.groovy` | The switch of the static scan order (the environment variable first, then `staticAnalysis.parallel` of every service), the services whose SAST compile differs from their build command, the order `PipelineState.settle` restores, and the removal of the SAST staging copy on Linux and Windows |
| `test/sandbox/PipelineScenarios.groovy` | The five entry points run end to end under the real sandbox, green and orange, the Nexus IQ GoldenFix pipeline also with a failing build, including the report, the release gate, the metrics, the deployments and the GoldenFix pull request of the Nexus IQ GoldenFix pipeline, and match their golden runs. Every run reads a repository file: it plants `devsecops_config.yaml` or `config.yaml` in the workspace, takes the defaults of the run as the bundled resource and gives the entry point `projectNames`, `agentNames` and, for the extended pipeline, `securityPipeline`; the runs cover both file names, both files at once, a Jenkinsfile without `projectNames` and another `appId` in a `config.yaml` that is not read. The eleven golden runs of the first four entry points, recorded at release b815d55, are unchanged byte for byte and are compared after declared rules for what this release adds: the run-state file `pipeline-config.yaml`, the `build_evidence` and unit `test_execution` points, the InfluxDB line count, the settings line of the report header and `config_file`. The golden runs of the Nexus IQ GoldenFix pipeline (green, orange, build failure, the shared catalog run of two services) and `full-pipeline-orange-parallel-scans.txt` were recorded again for this branch; before they were committed, a one-off comparison proved that each new file equals the file recorded on `main` with the steps and lines of the key read removed and the lines that name the settings file added. Runs without a golden file check single features, among them the Node.js build in the full, SAST, Nexus IQ GoldenFix and extended pipelines. Four twins of the green and orange full and security runs run Nexus IQ and SonarQube while AppScan scans and give the release gate, the metrics and the report of their golden file and its steps in the new order, which the orange full twin also pins in its own golden file; focused runs cover the compile fallback, the environment override, a service that opts out, a SAST failure before and after its scans are queued, GoldenFix and the AppScan settings per service |
| `test/sandbox/ConfigScenarios.groovy` | The configuration of a run: `projectNames` as a string or a list, `securityPipeline`, a Jenkinsfile that names `pipelineKey` or `pipelineKeys`, also empty, which stops with its message and runs nothing, a Jenkinsfile without `agentNames`, `DSO_REQUIRE_PIPELINE_KEY=true`, which changes nothing, `configure()` twice and `initialize()` without `configure()`, each of the two file names, the file errors and their messages; the `platform:` section of the bundled defaults, which changes the iOS build agent, the OIS host, the Flutter snapshot repository and the tool environment, with an environment variable that wins over `platform.environment`; the InfluxDB settings of the primary service and the upload on Linux and Windows; the build token of remote test jobs (`tokenCredentialsId`); the extended handoff; and the Nexus IQ GoldenFix entry point, which names its own call and stops at the start for a service it would not scan |
| `test/sandbox/CompatibilityScenarios.groovy` | The loader of release b815d55 and the loader of this release produce the same configuration, exactly, from `devsecops_config.yaml` and from `config.yaml` over the same defaults (`devsecops_config.yaml` first when both exist); the production bundled defaults have the structure the library reads, with an optional `platform:` map, and can be applied; `examples/CertScanner/devsecops_config.yaml` loads |

The sandbox tests execute the library inside `GroovySandbox.runInSandbox` with the real `script-security` whitelists and a fake Jenkins (`test/sandbox/harness`), so a rejected signature, an invalid closure or a missing method fails the test run instead of a customer build.

### Rules the sandbox imposes on the library code

| Rule | Why |
|---|---|
| No static fields | Reading one while the CPS engine saves the program is rejected (`staticField ...`) |
| Qualify every call inside a closure of a static method | Otherwise the sandbox sees `DefaultGroovyMethods invokeMethod` and rejects it |
| Never assign a field of the class inside a closure | The sandbox transformer produces invalid bytecode (`VerifyError`) |
| Use `map[key]`, never `map."${key}"` | Under the sandbox the GString is looked up as a key and the result is `null` |
| No multiple assignment (`def (a, b) = ...`) | The sandbox transformer does not support it |
| Only whitelisted JDK API | `Boolean.FALSE`, `new LinkedHashMap()`, `JsonSlurper`, `Pattern.compile` and friends need an administrator approval |
| Build collections from literals, parse JSON with `readJSON` | Same reason |
| Keep the report generation defensive | The pipeline report is written on every build, even when a scan or a test failed |

Every rule above is enforced by one of the checks, so breaking it fails `test/run-all.sh`.

### Demo reports

`test/run-all.sh` regenerates `examples/reports`: one page per pipeline and outcome plus an `index.html` overview. They are produced by the report template of the library from randomised but realistic data, so they can be shown in a demo and they change whenever the template changes.
