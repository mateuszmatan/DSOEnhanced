# DevSecOps Jenkins Shared Library - Documentation

*Complete documentation, generated from `documentation.confluence` by `tools/build-documentation-html.py`; the same content is published as `documentation.html` and in Confluence. Edit the source, not this file.*

Jenkins Shared Library engineered and maintained by the **DevSecOps team in ITSS COE at Brown Brothers Harriman (BBH)**, implementing the enterprise DevSecOps delivery pipeline: build, unit testing with coverage enforcement, dependency composition analysis (Nexus IQ), static code analysis (SonarQube), static and dynamic application security testing (HCL AppScan), artifact delivery (Nexus), deployment to virtual machine and OpenShift targets, functional and performance testing, HTML reporting, automated dependency remediation and InfluxDB telemetry.

This document is the complete documentation for the library. Part I states the purpose, business case and capabilities of the solution. Part II is the procedure for integrating a new application. Part III is the configuration and behaviour reference. Part IV covers operational monitoring and fault diagnosis. Part V is intended for engineers modifying the library itself.

> **Note:** **This is the `DSOEnhancedWithoutPortal` branch, DevSecOps version 0.** A pipeline is configured by its `Jenkinsfile` and the `devsecops_config.yaml` of its repository only, with the global settings that the library bundles. The `main` branch of the repository is the release with ITSS COE OneView (OneView), the portal that was called DevSecOps Management Portal before. The features of `main` are carried over to this branch.

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
- [14. Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase)
- [15. Reference Implementation Examples](#15-reference-implementation-examples)

**Part III - Configuration and Behaviour Reference**

- [16. Configuration Reference: devsecops_config.yaml](#16-configuration-reference-devsecops_configyaml)
- [17. Pipeline Stage Reference](#17-pipeline-stage-reference)
- [18. Security Policy Thresholds](#18-security-policy-thresholds)
- [19. Supported Build Tools](#19-supported-build-tools)
- [20. Supported Deployment Targets](#20-supported-deployment-targets)
- [21. Programmatic API Reference](#21-programmatic-api-reference)

**Part IV - Operations**

- [22. Monitoring and Metrics](#22-monitoring-and-metrics)
- [23. Grafana Dashboard Query Reference](#23-grafana-dashboard-query-reference)
- [24. Troubleshooting Guide](#24-troubleshooting-guide)

**Part V - Library Development**

- [25. Library Development and Test Suite](#25-library-development-and-test-suite)

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
| 6 | Nexus delivery (static analysis passed) | Publish the snapshot artifact, even when an earlier stage is orange, unless `delivery.nexusDeliveryEnabled` (or `deploy.<target>.rd.nexusDeliveryEnabled`) is false for the service |
| 7 | Lower test region deployment | Deploy to the lower test region: VM with UrbanCode Deploy (`devSecOpsPipeline` and `devSecOpsExtendedPipeline`), or OpenShift |
| 8 | Regression tests | Trigger regression jobs, at least one job must be configured |
| 9 | Smoke tests | Trigger smoke jobs, at least one job must be configured |
| 10 | Performance tests | Trigger performance jobs, at least one job must be configured |
| 11 | DAST – HCL AppScan | Dynamic Application Security Testing, HTML report, HCL console link and PDF report |
| 12 | Nexus delivery (safe artifact) | Publish the release artifact, blocked when any earlier stage is orange |
| 13 | Higher test environment deployment | Deploy to QC, only when every earlier stage is green and the checkbox is selected |

The Node.js build runs after stage 1 and before stage 2. The report numbers it 0, so every other stage keeps its number whether a run has a Node.js build or not.

The full and security pipelines declare one more Jenkins stage, **Static analysis**, and the full and extended pipelines one more, **Dynamic analysis**. By default **Static analysis** runs stages 2 to 5 at the same time, the unit tests, Nexus IQ, SAST and SonarQube, and **Dynamic analysis** runs stages 8 to 11 at the same time, the regression, smoke and performance tests and DAST; Jenkins then shows the stages they run as skipped. `staticAnalysis.parallel: false` or `dynamicAnalysis.parallel: false` in any service of the run, or the environment variable `DSO_STATIC_ANALYSIS_PARALLEL` or `DSO_DYNAMIC_ANALYSIS_PARALLEL` set to `false`, runs those stages one after another as before, and Jenkins shows **Static analysis** or **Dynamic analysis** as skipped. The report, the metrics and the release gate always show the stages with their numbers.

### Which pipeline to use

The library ships five entry points. They share the same services, thresholds, release gate and report, and all of them are built from the same stage methods in `vars/devSecOpsSteps.groovy`. Each stage method takes the `devSecOpsApi` instance of its entry point as the first argument, for the reason explained in [one devSecOpsApi instance per build](#21-programmatic-api-reference).

| Entry point | Runs | Use it when |
|---|---|---|
| `devSecOpsPipeline` | All thirteen stages in one build, after the optional Node.js build: unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery, RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment | One job should cover the whole flow |
| `devSecOpsSecurityPipeline` | The optional Node.js build, unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery; archives `pipeline-config.yaml` and the release gate verdict, and triggers the extended job when `RUN_EXTENDED_PIPELINE` is selected | The static part runs on every commit and the rest is a separate job |
| `devSecOpsExtendedPipeline` | RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment; it has no Node.js build stage, and its release delivery runs the Node.js build of a service again only before a release that is implemented, today the NuGet push of a .NET service | Downstream of the security pipeline, which its Jenkinsfile names in `securityPipeline`; it inherits the release verdict through the copied `release-gate.json` |
| `devSecOpsSASTScanningPipeline` | Monitor sources, the optional Node.js build and SAST | A job that executes the AppScan static scan only; its report contains the SAST stage and the SAST findings |
| `devSecOpsNexusIqGoldenFixPipeline` | Monitor sources, the optional Node.js build, artifact build and Nexus IQ with GoldenFix | A job that checks the dependencies of the built artifact and raises the GoldenFix pull request with the safe versions; its report contains the Nexus IQ stage, the Nexus IQ findings and the GoldenFix card |

After all stages, the library always:

- Generates an HTML pipeline report with the stage flow, vulnerability counts, policy status, SonarQube badges and coverage, with a row for every coverage report
- Adds a **Smoke tests** table with one row per executed job and a link to each build
- Adds a **Nexus IQ GoldenFix** card with the pull request link, the applied dependency upgrades and the fixes that could not be applied automatically
- Adds a **Release policy** card stating whether the artifact may be released to Nexus and deployed to QC
- Archives the HTML reports, the DAST PDF report and the release gate verdict (`release-gate.json`) as build artifacts
- Sends the full pipeline telemetry and the four DORA metrics to InfluxDB (if configured), see [Monitoring and metrics](#22-monitoring-and-metrics)

### Quick Reference Index

### 1. Supported Technologies, Languages, Build Tools & Catalogs

The library reads `devsecops_config.yaml` (or `config.yaml` for full backward compatibility). When `buildTool`, `deployTarget`, or tool-specific sub-parameters (such as `build.dotnet.mode`, `tests.unitTests.dotnet.runner`, or `coverageTool`) are omitted, `RepoStackAutoResolver` automatically detects them from the repository. If an invalid or unrecognized value is configured, the pipeline logs a `[WARN]` message and falls back to the auto-detected setting.

| Technology / Stack | Languages | Build Tool (`buildTool`) | Auto-Detection Triggers | Unit Tests & Coverage | SonarQube Scanner | GoldenFix & Version Catalogs | SAST Inline PR Comments |
|---|---|---|---|---|---|---|---|
| Java / Enterprise JVM | Java, Kotlin, Groovy | `maven` (alias `mvn`) | `pom.xml` | Surefire/Failsafe JUnit XML + JaCoCo XML | `mvn sonar:sonar` | `pom.xml`, parent POMs, BOM imports, `<properties>` | `.java`, `.kt`, `.groovy`, `.jsp`, `.xml`, `.properties` |
| JVM / Spring / Kotlin / Android | Java, Kotlin, Groovy | `gradle` (alias `gradlew`), `android` | `build.gradle`, `build.gradle.kts`, `gradle-wrapper.properties`, `AndroidManifest.xml` | Gradle Test JUnit XML + JaCoCo / Kover XML | `./gradlew sonarqube` | `build.gradle(.kts)`, `gradle.properties`, `gradle/libs.versions.toml`, `settings.gradle(.kts)`, `java-platform`, `buildSrc` | `.java`, `.kt`, `.kts`, `.groovy`, `.xml`, `.gradle` |
| C# and .NET (.NET Framework 4.x, .NET Core, .NET 6/7/8/9+) | C#, F#, VB.NET, ASP.NET / Razor | `dotnet` (aliases `csharp`, `c#`, `net`, `fsharp`, `vbnet`), `msbuild` (aliases `vs`, `netfx`) | `*.sln`, `*.csproj`, `*.fsproj`, `*.vbproj`, `global.json`, `Directory.Packages.props`, `Directory.Build.props`, `packages.config`, `build.cake`, `.nuke` | Modes: `auto`, `dotnet`, `msbuild`, `hybrid`, `cake`, `nuke`. Runners: `dotnet test`, `vstest.console.exe`, `MSTest.exe`, `nunit3-console.exe`, `xunit.console.exe` (`.trx` / XML). Coverage: Coverlet in OpenCover format (default, read by SonarQube), Cobertura, OpenCover.Console, dotCover | `SonarScanner.MSBuild.exe` / `dotnet sonarscanner` (or `sonar-scanner` with `useGenericScanner: true`) | `*.csproj`, `*.fsproj`, `*.vbproj`, `Directory.Packages.props`, `Directory.Build.props`, custom `.props`, `packages.config` | `.cs`, `.fs`, `.vb`, `.cshtml`, `.razor`, `.aspx`, `.config`, `.csproj`, `.props` |
| Node.js / Frontend SPA (Angular, React, Vue) | JavaScript, TypeScript, HTML, CSS/SCSS | `npm` (aliases `node`, `nodejs`, `yarn`, `pnpm`, `angular`, `react`, `vue`) or Stage 0 `build.node.enabled: true` | `package.json`, `angular.json` (root or `frontend/`, `web/`, `client/`, `ui/`) | Jest / Karma / Vitest / Cypress + LCOV (`lcov.info`) / Cobertura / Clover | `sonar-scanner` with `-Dsonar.javascript.lcov.reportPaths` & `-Dsonar.typescript.lcov.reportPaths` | `package.json`, `package-lock.json`, `overrides`, `resolutions` | `.js`, `.jsx`, `.ts`, `.tsx`, `.mjs`, `.cjs`, `.vue`, `.svelte`, `.html`, `.css`, `.scss`, `.json` |
| Python | Python | `pip` (aliases `python`, `poetry`, `pipenv`) | `requirements.txt`, `pyproject.toml`, `setup.py`, `Pipfile` | `pytest` / `unittest` JUnit XML + `coverage.xml` (Cobertura) / `lcov.info` | `sonar-scanner` with `-Dsonar.python.coverage.reportPaths=coverage.xml` | `requirements.txt`, `requirements/*.txt`, `constraints.txt` | `.py`, `.pyw` |
| Flutter / Dart (Mobile & Web) | Dart | `flutter` (alias `dart`) | `pubspec.yaml` | `flutter test --coverage` (`test_report.json` + `lcov.info` / `total_lcov.info`) | `sonar-scanner` (generic coverage XML + `dart analyze` issues or Flutter plugin) | `pubspec.yaml` | `.dart`, `.yaml`, `.yml` |
| iOS / macOS | Swift, Objective-C, C/C++ | `ios` (aliases `xcode`, `swift`, `cocoapods`) | `Podfile`, `Package.swift`, `*.xcodeproj`, `*.xcworkspace` | `xcodebuild test` / `swift test` | `sonar-scanner` with Swift/Objective-C sources | CocoaPods / SwiftPM manifests | `.swift`, `.m`, `.mm`, `.h`, `.hpp`, `.c`, `.cpp` |
| Database / SQL & PL/SQL | SQL, PL/SQL, T-SQL, XML/YAML changelogs | `liquibase` (aliases `sql`, `plsql`) or combined block on Maven/Gradle service | `liquibase.properties`, `changelog.xml`, `changelog.yaml`, `changelog.sql`, `db/changelog/db.changelog-master.xml` | `liquibase validate` (coverage gate automatically marked `NOT_REQUIRED` for standalone SQL) | `sonar-scanner` with `-Dsonar.sources`, `inclusions`, `exclusions`, and PL/SQL suffixes | JDBC/extension JARs scanned via Nexus IQ | `.sql`, `.pls`, `.pks`, `.pkb`, `.plb`, `.prc`, `.fnc`, `.trg`, `.xml`, `.yaml` |

### 2. Requirements per Stage and Security / Quality Tool

| Capability / Stage | Required Configuration (`devsecops_config.yaml` / `config.yaml`) | Agent / Environment Prerequisites | What the Stage Produces & Enforces |
|---|---|---|---|
| **Build & Unit Tests** (Stages 0 & 2) | Optional `buildTool` (auto-detected if omitted or invalid). Optional `build.<tool>`, `tests.unitTests.<tool>`, `coverage.minLine` (default `75`% in bundled defaults, `60`% fallback) | Matching compiler/SDK on agent: JDK (`javaPath` or auto-detected), Gradle/Maven, .NET SDK or Visual Studio BuildTools (`MSBuild.exe`, `NuGet.exe`, `vstest.console.exe`, `OpenCover.Console.exe`), Node.js/npm, Python/pip, Flutter, or Liquibase | Compiles artifact, runs unit tests, aggregates all JaCoCo, LCOV, Cobertura, and OpenCover reports, and checks line coverage against the threshold |
| **Dependencies Scan — Nexus IQ & GoldenFix** (Stage 3) | `tools.nexusIq.application` (or `applications`), `tools.nexusIq.credentialsId`, `tools.nexusIq.scanPatterns`. For GoldenFix PRs: `goldenFix.enabled: true` and `scm.bitbucket.url` + `credentialsId` | Jenkins Nexus Platform Plugin (`nexusPolicyEvaluation`), `git`, and `curl` / `curl.exe` for Nexus IQ remediation REST API and Bitbucket PR API | Evaluates open-source dependencies against Nexus IQ policy, runs GoldenFix pre-check build in an isolated Git worktree, upgrades manifests/version catalogs, and opens/updates a Bitbucket Pull Request |
| **SAST — HCL AppScan** (Stage 4) | `appId` (or `asoc.appId`). Optional `asoc.doCompile`, `asoc.<tool>`, `sast.sourceFolders`, `sast.excludedFolders`. The API key (`asoc.keyId`, `asoc.token`) is a setting of the library defaults | HCL AppScan SAClientUtil for the IRX (installed automatically from `SA_LINUX_URL` / `SA_WIN_URL` or pre-installed at `appscanPath`), `curl` on the agent (`curl.exe` on Windows), outbound access (or proxy) to `bbh.cloud.appscan.com` | Compiles sources (when `doCompile: true`), generates the `.irx` package, uploads it and queues the scan through the AppScan REST API v4, downloads the HTML and PDF reports without Noise issues, enforces the severity policy, and posts inline PR comments on vulnerable files/lines |
| **SCA — SonarQube** (Stage 5) | `tools.sonar.projectKey` (auto-generated as `com.bbh:<service>` if omitted), `tools.sonar.installationName`, `tools.sonar.authToken` / `credentialsId` | SonarQube Scanner for Jenkins (`withSonarQubeEnv`), plus `SonarScanner.MSBuild.exe` / `dotnet-sonarscanner` for .NET or `sonar-scanner` for Flutter/Liquibase/Python/Node/iOS | Executes static code quality & security hotspot analysis, fetches blocker/critical/major/minor counts via REST API, enforces policy, and posts inline PR comments |
| **Artifact Delivery & Deployment** (Stages 6, 7, 12, 13) | `deployTarget`: `openshift` or `vm` (auto-detected from `Dockerfile` / `openshift` / `k8s` / `helm` if omitted). `deploy.openshift.<rd\|qc>` or `deploy.vm.<rd\|qc\|dod>`, optional `delivery.<maven\|gradle\|nuget>` | For OpenShift: `oc`, `skopeo`, cluster credentials. For VM: SSH credentials or UrbanCode Deploy (DOD) plugin. For NuGet: `nuget.exe` or `dotnet` | Publishes snapshot/release artifacts or container images to Nexus and deploys to RD (lower environment) and QC (higher environment, gated by `release-gate.json`) |
| **Functional & Performance Tests** (Stages 8, 9, 10) | `tests.regression.jobs` (or `urls`), `tests.smoke.jobs` (or `urls`), `tests.performance.jobs` (or `urls`), optional `tokenCredentialsId` and `maxParallel` | Local Jenkins jobs or Parameterized Remote Trigger plugin for remote Jenkins URLs | Triggers downstream test jobs in parallel batches, collects build status and duration, and records results in HTML report and InfluxDB |
| **DAST — HCL AppScan** (Stage 11) | `dast.enabled: true`, `dast.targetUrl`, `dast.scanName` (or `dast.scanFile`); the API key comes from the library defaults | Running application in RD reachable at `dast.targetUrl` (via optional `dast.presenceId` tunnel) | Runs dynamic runtime security scan against `targetUrl`, downloads HTML and PDF reports, enforces DAST policy, and opens a DAST findings Pull Request if violations exist |

### 3. Breakdown per Pipeline Type

| Pipeline Entry Point | Stages Executed | Required Sections in `devsecops_config.yaml` / `config.yaml` | Artifacts & Pull Requests Produced |
|---|---|---|---|
| `devSecOpsPipeline` **(Full Pipeline)** | All stages: 1 (Monitor), 0 (Node.js optional), 2 (Build + Unit Tests + Coverage), 3 (Nexus IQ + GoldenFix), 4 (AppScan SAST), 5 (SonarQube), 6 (Nexus Snapshot), 7 (RD Deploy), 8 (Regression), 9 (Smoke), 10 (Performance), 11 (AppScan DAST), 12 (Nexus Release), 13 (QC Deploy) | `appId`, `tools.nexusIq`, `tools.sonar`, `deploy.<target>`, `tests.<regression\|smoke\|performance>`, `dast` (`buildTool` and `deployTarget` auto-resolved if omitted) | `report/pipeline-report.html`, `appscan-report*.html`, `appscan-dast-report*.pdf`, `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR, Static Security PR (inline SAST + SonarQube comments), DAST PR |
| `devSecOpsSecurityPipeline` **(Security Pipeline)** | Static & snapshot stages: 1 (Monitor), 0 (Node.js optional), 2 (Build + Unit Tests + Coverage), 3 (Nexus IQ + GoldenFix), 4 (AppScan SAST), 5 (SonarQube), 6 (Nexus Snapshot); optionally triggers `extendedPipeline` | `appId`, `tools.nexusIq`, `tools.sonar`, `deploy.<target>.rd` (`buildTool` and `deployTarget` auto-resolved if omitted) | `report/pipeline-report.html`, `appscan-report*.html`, `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR, Static Security PR (inline SAST + SonarQube comments) |
| `devSecOpsExtendedPipeline` **(Extended Pipeline)** | Dynamic & release stages: 1 (Monitor + copy `pipeline-config.yaml`, `release-gate.json` & `build.tag` from `securityPipeline`), Download artifacts from Nexus Repo, 7 (RD Deploy), 8 (Regression), 9 (Smoke), 10 (Performance), 11 (AppScan DAST), 12 (Nexus Release), 13 (QC Deploy) | `appId`, `deploy.<target>`, `tests.<regression\|smoke\|performance>`, `dast`; Jenkinsfile passes `securityPipeline: '<job>'` (or runs standalone) | `report/pipeline-report.html`, `appscan-dast-report*.html/.pdf`, `pipeline-config.yaml`, `release-gate.json`, DAST PR |
| `devSecOpsSASTScanningPipeline` **(SAST-Only Pipeline)** | Stages: 1 (Monitor), 0 (Node.js optional when `asoc.doCompile: true` on Linux), 4 (AppScan SAST) | `appId` (and `build.<tool>` / `asoc.<tool>` when `asoc.doCompile: true`) | `report/pipeline-report.html` (SAST-only view), `appscan-report*.html`, `pipeline-config.yaml`, `release-gate.json` |
| `devSecOpsNexusIqGoldenFixPipeline` **(Nexus IQ + GoldenFix Pipeline)** | Stages: 1 (Monitor), 0 (Node.js optional), 2 (Build artifact), 3 (Nexus IQ scan + GoldenFix remediation & PR) | `tools.nexusIq.application` + `scanPatterns`, `goldenFix`, `scm.bitbucket` (`buildTool` auto-resolved if omitted) | `report/pipeline-report.html` (Nexus IQ + GoldenFix view), `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR |

## 2. Benefits of DevSecOps at BBH

This chapter states the business case for operating application delivery through the shared DevSecOps pipeline rather than through pipelines maintained independently by each application team. The figures quoted are published industry benchmarks, cited for order of magnitude; they are not measurements of BBH systems. Section [Monitoring and Metrics](#22-monitoring-and-metrics) describes how the equivalent figures are measured for BBH applications once the pipeline is in operation.

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

- By default the full and security pipelines run the unit tests, Nexus IQ, SAST and SonarQube at the same time, and the full and extended pipelines run the regression, smoke and performance tests and DAST at the same time. The results, the release gate and the metrics are those of a run one after another; the report adds how long the stages took one after another and in parallel.

### Code quality and coverage

Unit tests run through the project's own Gradle, Maven or Flutter build. The pipeline then finds every JaCoCo XML and LCOV report of the service, in `build`, `target`, `release` or any other folder, and reads the line coverage of each. The report shows one row per report; the coverage of the service is the mean of its reports and is checked against the minimum required by the library, and the summary of the run is the mean of all reports. A failing test suite turns the stage orange and coverage is still measured on whatever reports were produced.

Flutter projects are analysed by SonarQube **without the commercial Dart plugin**: coverage and `dart analyze` findings are converted into SonarQube's generic import formats, which every edition supports.

### Release gate

The gate collects every verdict of the run and blocks the release artifact and the higher environment deployment when a scanner count is above the policy, the coverage is below the minimum, or any stage is not green. The verdict is written to `release-gate.json` and archived, so a downstream pipeline inherits the decision instead of re-deciding it.

### Automated dependency remediation (GoldenFix)

When Nexus IQ reports a policy violation the library prepares the upgrade itself and opens a pull request for review. It never merges anything.

- Only **direct dependencies** are touched; transitive ones are upgraded by upgrading whatever pulls them in. A component that Nexus IQ marks as transitive is never proposed, and the entries that pin transitive versions (`overrides` and `resolutions` of `package.json`, `constraints*.txt`) are changed only for a component that Nexus IQ marks as direct.
- Manifests are updated across the whole source tree: `pom.xml`, `build.gradle`/`.kts`, `gradle.properties`, Gradle version catalogs, `package.json` (with its `overrides` and `resolutions`), `requirements*.txt`, `pyproject.toml`, `pubspec.yaml`, and NuGet / .NET manifests (`*.csproj`, `*.fsproj`, `*.vbproj`, `Directory.Packages.props`, `Directory.Build.props`, and `packages.config`).
- **Shared version catalogs are raised once.** In a repository that holds several services, a version that a shared catalog defines is raised in the catalog, consistently for every service that uses it: a Gradle version catalog, the catalog of `settings.gradle`, a build file that other builds apply, a `java-platform` module, a Maven parent or BOM pom, or a .NET `Directory.Packages.props` / `Directory.Build.props`. A declaration of the same dependency outside the catalog is raised to the same version and named in the pull request, so that a reviewer can move it into the catalog.
- The version is chosen by two rules: **only the last number of the version (the patch) may change**, so `1.2.3` may become `1.2.4` but never `1.3.0`, and of those the **newest one without known vulnerabilities** wins. A dependency without such a version is left untouched and listed for a developer.
- Before anything is proposed the project is **built with the new versions and its unit tests are run**: every changed manifest in its own folder, and a shared catalog in every build that uses it. A failing build or test lowers the versions and retries, and if nothing builds, no pull request is opened. No pull request is ever opened without a passing pre-check build: when no build can be run for a changed file, the run ends `NOT_VERIFIED`.
- The services of one build that share a repository share one pull request. Each later service adds its upgrades to it and says so in a comment.

The Nexus IQ GoldenFix pipeline runs the dependency scan and this remediation on their own, after building the artifact, without the other stages.

### Build, test and delivery

Gradle, Maven, C#/.NET (classic `MSBuild.exe` + `NuGet.exe` + `vstest.console.exe` on Windows or `dotnet` SDK CLI on Windows/Linux) and Flutter builds with the project's own tasks, and Liquibase commands for SQL and PL/SQL; for Oracle a database block packages, tests, delivers and deploys the changes ([Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase)). An optional Node.js build runs the npm commands of a frontend first, as stage 0, so that the Gradle or Maven build can package its output. Three independent test job sets — smoke, regression and performance — running locally or on another Jenkins addressed by full URL, with configurable parallelism. Snapshot delivery after static analysis, release delivery only when the gate allows it (including NuGet push for .NET and Maven/Gradle publish), and container images built and copied to Nexus for OpenShift projects.

### Deployment

Virtual machines over SSH or through UrbanCode Deploy, and OpenShift with image build, copy to Nexus, deployment and rollout status per environment. The higher environment is deployed only when the gate allows it **and** an operator selected the parameter.

### Reporting and metrics

A single HTML report per build with the stage flow, finding counts against the policy, coverage bars with a row per coverage report (JaCoCo XML, Cobertura XML, OpenCover XML, LCOV), SonarQube badges, the remediation outcome, the release policy card and the full list of test jobs — written, archived and published even when the run failed. Every run also writes a full set of metrics to InfluxDB, including the four DORA figures.

The report ends with the table **Manual Actions vs DevSecOps**: recalculated on every run from the stage durations of that run, it lists every step of the run in the order of the pipeline with its own time, the time the step takes by hand, the time the pipeline needed (of the steps that run in parallel only the longest) and the time gained in percent, and closes with a summary in seconds, minutes and percent ([the stage reference](#17-pipeline-stage-reference)).

### Automated Stack Discovery and Smart Auto-Resolve

To remove over 80% of onboarding friction for application teams, `RepoStackAutoResolver` inspects repository workspace files dynamically. Instead of requiring engineers to know compiler versions, Node.js directories, Liquibase flags or OpenShift manifests, missing fields are auto-resolved:

- Maven (`pom.xml`) and Gradle (`build.gradle`) with exact compiler JDK and wrapper versions.
- C# and .NET solutions and projects (`*.sln`, `*.csproj`, `*.fsproj`, `*.vbproj`, `Directory.Packages.props`, `Directory.Build.props`, `packages.config`, `global.json`).
- Node.js Stage 0 (`package.json`) with detected directory, engine version and test runners (Cypress, Jest).
- Database changes via Liquibase (`changelog.xml`, `liquibase.properties`).
- Multi-language support: C# / .NET, Kotlin, Python (pip), Dart/Flutter.
- Target environment auto-detection: OpenShift when container files/manifests exist; virtual machines otherwise.
- Fully backward compatible: explicit parameters in configuration files always take precedence.
- Every value the auto-resolve fills in or corrects is listed: the build log prints `[INIT] Auto-resolved for <service>: <setting> = <value> (<why>)` and the pipeline report shows the card **Auto-resolved settings** (service, setting, value and why), so a team can copy the values into `devsecops_config.yaml` to pin them.

### Bitbucket Pull Request Decoration and Shift-Left Feedback

Developers receive instant, actionable feedback directly in Bitbucket pull requests:

- **Consolidated Static Security PR**: Static analysis combines Nexus IQ GoldenFix safe dependency upgrades, AppScan SAST vulnerability comments, and SonarQube quality findings into a single consolidated Bitbucket Pull Request. SAST findings marked with status `Noise` are automatically excluded, and comments are placed inline on the exact source file and line.
- **Dedicated DAST PR**: Dynamic security findings from enhanced pipelines create a separate, isolated Pull Request, separating operational defect remediation from source code changes.

### SonarQube Default Configuration, Heuristic Module Discovery and Branch Pinning

When `sonar-project.properties` is omitted from a repository, `SonarService` synthesizes standard BBH enterprise defaults automatically. It detects multi-module structures in Maven and Gradle, assigns language-appropriate source/test directories (including `SonarScanner.MSBuild.exe` begin/build/end or `dotnet sonarscanner` with Cobertura/OpenCover/VSTest reports for C#/.NET), and pins `sonar.branch.name` to the active evaluated pipeline branch rather than defaulting to master.

### Continuous Software Assurance Maturity Model (OWASP SAMM v2.0)

The DevSecOps architecture aligns with the OWASP SAMM v2.0 framework across 5 business functions (Governance, Design, Implementation, Verification, Operations), evaluating continuous pipeline execution evidence against maturity benchmarks.

### Platform support

| Dimension | Supported |
|---|---|
| Agent operating system | Linux, macOS and Windows; the library detects the agent and uses bash or PowerShell cleanly |
| Build tools | Gradle, Maven, C# / .NET (`dotnet` / `msbuild`), Flutter, Liquibase (SQL / PL/SQL); Node.js with npm as an optional stage 0 before Gradle or Maven |
| Dependency ecosystems for remediation | Maven, npm, PyPI, NuGet, Dart/Flutter pub |
| Shared version catalogs for remediation | Inside the repository: Gradle version catalogs (`*.versions.toml`, the files `settings.gradle` imports and its catalog DSL), Gradle build files named by `apply from`, `java-platform` modules, Maven parent and BOM poms, and .NET `Directory.Packages.props` / `Directory.Build.props` |
| Projects per build | One or many, each with its own configuration, scans, coverage and report rows |
| Source control | Git; Bitbucket for GoldenFix and security pull requests |

All pipeline stages, including GoldenFix dependency remediation and SAST IRX generation, run natively on both Linux/macOS agents and Windows Jenkins agents.

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
│   ├── build/                              <- hexagonal build & test runners, toolchain setup and report parsers
│   │   ├── BuildService.groovy             <- controller coordinating build, unit tests, coverage and remote test jobs
│   │   ├── BuildRunner.groovy              <- one runner for the Gradle and Maven commands
│   │   ├── DotnetBuildRunner.groovy        <- adapter: .NET CLI, MSBuild, Hybrid, Cake and NUKE build and test execution
│   │   ├── DotnetProjectResolver.groovy    <- model/resolver: .NET mode, test runner, coverage tool and project discovery
│   │   ├── JdkToolchainSetup.groovy        <- adapter: build tool detection and JDK environment provisioning
│   │   ├── LiquibaseBuildRunner.groovy     <- adapter: Liquibase changelog validation and test execution
│   │   ├── NodeBuildRunner.groovy          <- the Node.js build of stage 0 and the Node.js environment of the npm pre-check
│   │   ├── RemoteTestJobRunner.groovy      <- adapter: local and remote Jenkins test job orchestration
│   │   ├── UnitTestReportParser.groovy     <- model/parser: TRX, Flutter, JaCoCo, Cobertura, OpenCover and LCOV reports
│   │   ├── CoverageReports.groovy          <- multi-report coverage discovery and aggregation model
│   │   └── ManifestBuildVerifier.groovy    <- adapter: the GoldenFix pre-check build, one check per folder or catalog build
│   ├── scanner/                            <- hexagonal adapters and builders for the security and quality scanners
│   │   ├── AppScanService.groovy           <- HCL AppScan SAST and DAST orchestration
│   │   ├── AppScanEnvironmentAdapter.groovy <- adapter: SAClientUtil installation, A2A proxy password and appscan.properties
│   │   ├── NexusIqService.groovy           <- Nexus IQ scan, calls the remediation port on a violation
│   │   ├── NexusIqGoldenFixSource.groovy   <- adapter: Nexus IQ report + Component Remediation REST API
│   │   ├── SonarService.groovy             <- SonarQube scanner execution and quality gate evaluation
│   │   └── SonarScannerCommandBuilder.groovy <- model/builder: SonarQube properties, report paths and command formatting
│   ├── remediation/                        <- GoldenFix & Security PR MVC controllers, views, services, ports and adapters
│   │   ├── GoldenFixService.groovy         <- application service / controller: fetch -> patch manifests -> commit/push -> pull request
│   │   ├── SharedCatalogRepositoryService.groovy <- service: shared external versionCatalogRepo priority upgrade and shared PR coordination
│   │   ├── GoldenFixPullRequestView.groovy <- MVC view: GoldenFix commit messages, PR descriptions and comments
│   │   ├── SecurityPullRequestService.groovy <- MVC controller: static (SAST + SonarQube + GoldenFix) and DAST pull request decoration
│   │   ├── SecurityFindingsParser.groovy   <- MVC model/parser: SAST HTML, SonarQube and DAST finding extraction and path normalization
│   │   ├── SecurityPullRequestView.groovy  <- MVC view: static & DAST PR markdown reports and inline line comments
│   │   ├── CatalogDiscovery.groovy         <- the shared version catalogs of the repository and the builds that use them
│   │   ├── model/GoldenFix.groovy          <- the remediation domain: the fix record, version semantics and the version choice
│   │   ├── model/VersionCatalogs.groovy    <- catalog aliases, path globs and the catalog blocks of settings.gradle
│   │   ├── port/DependencyRemediation.groovy  <- inbound port used by NexusIqService
│   │   ├── port/GoldenFixSource.groovy        <- outbound port: where the proposed versions come from
│   │   ├── port/SourceRepository.groovy       <- outbound port: source tree, commit, push
│   │   ├── port/PullRequestPublisher.groovy   <- outbound port: pull request
│   │   ├── port/ManifestUpdater.groovy        <- outbound port: one build manifest format
│   │   ├── port/BuildVerifier.groovy          <- outbound port: pre-check build of the proposed versions
│   │   └── updater/                        <- MavenPomUpdater, GradleUpdater, NpmPackageJsonUpdater, PipUpdater, PubUpdater, NugetUpdater
│   ├── scm/                                <- adapters: GitSourceRepository (git worktree), Bitbucket pull requests
│   ├── deploy/                             <- adapters: VmDeployService (SSH, UrbanCode Deploy), OpenshiftService
│   ├── metrics/
│   │   ├── InfluxDbService.groovy          <- adapter: writes the batch to InfluxDB
│   │   ├── PipelineMetrics.groovy          <- builds every measurement from the pipeline state
│   │   └── MetricLine.groovy               <- line protocol formatting and escaping
│   ├── report/                             <- MVC pipeline report engine
│   │   ├── HtmlReportService.groovy        <- MVC controller: report lifecycle, file persistence and stage layout orchestration
│   │   ├── ReportViewModel.groovy          <- MVC model: stage layouts, policy calculations, test job summaries and GoldenFix state
│   │   └── HtmlReportCardsView.groovy      <- MVC view: HTML cards, tables, banners and badges
│   └── utils/                              <- RestClient (curl JSON client), AppScanReportParser, FlutterSonarReports, BuildUtils, FlutterUtils, RepoStackAutoResolver
├── resources/com/bbh/devsecops/defaults.yaml <- the global settings: thresholds, coverage minimum, timeouts, GoldenFix policy, tool defaults
├── Jenkinsfile.template           <- the Jenkinsfile of a project: services and agent labels
├── devsecops_config.yaml.template <- one commented service with every key the library reads
├── tools/build-documentation-html.py       <- renders documentation.confluence into documentation.html and documentation.md
├── grafana/                       <- the dashboard definitions devSecOpsDashboard and devSecOpsSecurityDashboard
├── test/                          <- the library test suite, see section 25
│   ├── README.md                  <- what every check and scenario proves
│   ├── run-all.sh                 <- one command: static checks, compilation, scenarios, demo reports, documentation
│   ├── checks/                    <- static checks of the sources and of the generated documentation
│   ├── coverage/                  <- the coverage scan script, run with python3 on report trees
│   ├── fixtures/                  <- the configuration files and the loader of release b815d55, the reference of the compatibility checks
│   └── sandbox/                   <- the library running under the real Jenkins script sandbox
└── examples/
    ├── CertScanner/               <- a complete two project example: the Jenkinsfile and devsecops_config.yaml
    ├── pull-request-comments.md   <- examples of the comments the pipeline writes into a pull request
    └── reports/                   <- generated demo reports, one HTML page per pipeline and outcome
```

**How the parts fit together (MVC & Hexagonal Architecture):** `core` holds the state, the policy and the release gate and knows nothing about Jenkins plugins. The GoldenFix and Security PR features combine Hexagonal Architecture with MVC: the application controllers (`GoldenFixService`, `SharedCatalogRepositoryService`, `SecurityPullRequestService`) talk to the ports in `remediation/port`, delegate domain parsing and rules to models (`remediation/model`, `SecurityFindingsParser`, `CatalogDiscovery`), render pull request content through dedicated MVC views (`GoldenFixPullRequestView`, `SecurityPullRequestView`), and execute through adapters in `scanner` (Nexus IQ, AppScan, SonarQube), `scm` (git worktrees, Bitbucket PRs), `build` (pre-check verification and tool runners) and `remediation/updater` (Maven, Gradle, npm, pip, Flutter pub, and NuGet/.NET). Similarly, the HTML reporting subsystem is structured in MVC with `ReportViewModel` (Model), `HtmlReportCardsView` (View), and `HtmlReportService` (Controller). `vars/devSecOpsApi.groovy` is the single composition root that assembles every service. Automated architecture checks in the test suite enforce these dependency directions.

**Config split:**

| Source | Who controls it | Contains |
|---|---|---|
| The `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml` in the library | DevSecOps team, with a library release | Security thresholds, required coverage, timeouts, the release gate, the GoldenFix policy and the tool defaults |
| The optional `platform:` section of the same file | DevSecOps team, with a library release | The platform values: the AppScan client, proxy and AppScan addresses, the OIS host, the Nexus snapshot repository of the Flutter delivery and the iOS build agent. The production file has none, so the library's built-in BBH values apply |
| The environment of the agent and the Jenkins global environment variables | Jenkins administrators | Tool environment values that win over the platform values, such as `PROXY_HOST` or `APPSCAN_SERVER_URL`, and the order of the static scans (`DSO_STATIC_ANALYSIS_PARALLEL`) |
| `devsecops_config.yaml`, else `config.yaml`, in the repository | Project team, per branch | The `projects:` section: the service settings (appId, the AppScan key ID, servers, test jobs, deploy targets) |
| The `Jenkinsfile` in the repository | Project team, per branch | The entry point, the services of the run (`projectNames`), the agent labels (`agentNames`) and, for an extended pipeline, the security job (`securityPipeline`) |

Projects **cannot** override the global thresholds. Thresholds for SAST, SCA, Nexus IQ, DAST, the required line coverage, the scan timeouts, the release gate and the minutes of the manual steps of the report (`report.manualEffort`) exist in one place only, the `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml`, and the library reads them from there only; a file in the repository cannot change them. To change one, the DevSecOps team changes that file and releases the library, and every build that loads the new version uses the new value. The other defaults, such as the GoldenFix policy, `tests.maxParallel`, `staticAnalysis.parallel` and `dynamicAnalysis.parallel`, are merged under every service as its starting values, and a service of `devsecops_config.yaml` may set its own.

### How a pipeline reads its configuration

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent']
)
```

1. The entry point calls `devSecOpsApi.configure('<variant>', <the map of the Jenkinsfile>)` before `pipeline {}`. It keeps `projectNames`, `agentNames` and `securityPipeline` of the Jenkinsfile and reads nothing yet, because the repository is checked out on the agent in the first stage.
2. `agentNames` is required: the Jenkins agent labels offered as the `AGENT_NAME` build parameter. Without it the build stops before `pipeline {}` with a message that shows the Jenkinsfile to write. A Jenkinsfile that names `pipelineKey` or `pipelineKeys`, also with an empty value, stops there as well, with the message described under [Troubleshooting](#24-troubleshooting-guide).
3. `projectNames` names the services to build, the primary project first, as one string separated by commas or as a list. Without it the library builds the services of the `PROJECT_NAMES` or `PROJECT_NAME` environment variable or, without those, every service of the file in the order of the file. A name that is not a service of the file is left out with a `[WARN]` line, and when no name is left the run builds every service of the file, as without the parameter (see **How the library chooses a value** below).
4. `securityPipeline` names the security job whose `pipeline-config.yaml` and `release-gate.json` an extended pipeline copies. In any entry point it also hides the Nexus IQ and SonarQube summary rows of the report.
5. In the Monitor stage, after the checkout, `ConfigLoader.initialize()` reads `devsecops_config.yaml` at the root of the workspace or, when there is none, `config.yaml`, the name the file had in the earlier releases of the library. When both are there it reads `devsecops_config.yaml` and says so in the console; when neither is there the stage fails with a message that names both. The names are exact and case-sensitive: a Windows agent also finds `Config.yaml`, a Linux agent does not.
6. Only the `projects:` section of the file is read. The thresholds, the required coverage, the timeouts, the GoldenFix policy and the tool defaults come from the `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml` of the library; the console says so: "\[INIT\] The services come from devsecops_config.yaml in the repository, the global settings from the library (resources/com/bbh/devsecops/defaults.yaml)". A `defaults:`, `platform:` or `pipeline:` section of the file is ignored and named in the console. The AppScan, proxy and Nexus addresses, the OIS host and the iOS build agent are the values of the agent environment, of the optional `platform:` section of the bundled defaults, or the library's own.
7. The services of the run are merged with the global defaults, the values of a service winning over the defaults, and written to `pipeline-config.yaml`, the run-state file in which the snapshot stages record their build tags. Every message that says where a setting lives names the file that was read.
8. An extended pipeline whose Jenkinsfile names a security job copies `pipeline-config.yaml`, `release-gate.json` and, when the security build published a snapshot, `build.tag` from the last successful build of that job and takes from the copy only the run-time tags of its own services: `deploy.openshift.rd.buildTag`, `deploy.openshift.rd.internalDockerUrl`, the delivered version in `delivery` of a Liquibase service, `delivery.buildTagAndroid` and `delivery.buildTagIOS`. Each must be a plain tag of letters, digits and `. _ : / @ + -`. A VM service gets the snapshot version from `build.tag`, and its `delivery` settings are never replaced by the copy. Every other setting comes from the file of its own checkout, so its Jenkinsfile lives in the repository of the service.
9. The report header says "Settings from devsecops_config.yaml in the repository", with the name of the file that was read, and `build_evidence` carries `config_file`. The settings follow the branch and the commit: a replayed build reads the file of its commit.
10. A custom Jenkinsfile that calls `devSecOpsApi.initialize()` without `configure()` reads the file too. The Nexus IQ GoldenFix pipeline needs `tools.nexusIq.application` and its `scanPatterns` for every service of the run.

### How the library chooses a value

Every setting keeps working as it was written: a key that is set wins over the default, also when it equals the default, so a `devsecops_config.yaml`, a `config.yaml` and a `Jenkinsfile` of an earlier release load unchanged. The library then goes three steps further, and says so in the Monitor stage, in lines that start with `[INIT]`:

1. **A value of the wrong kind falls back to the default.** When the bundled defaults hold a number, `true` or `false`, or a list for a key and a service sets something else, such as `sast.maxCritical: none`, `sca.enabled: enabled` or a map for `releaseGate.scanners`, the key of the service is left out, the default applies and the console says `[INIT] [WARN] <service>: <key>: <value> in devsecops_config.yaml is not a number - the library default <key>: <default> is used instead` (`is not true or false`, `is not a list`). A number in quotes, such as `"45"`, and `yes`, `no`, `on`, `off`, `1` and `0` for `true` and `false` are kept. An invalid `buildTool`, `deployTarget` or .NET build mode falls back to the auto-detected value, with a `[WARN] Invalid ...` line (**Automated Stack Discovery and Smart Auto-Resolve** in [chapter 16](#16-configuration-reference-devsecops_configyaml)). Only when the default does not work either does the stage that uses the value fail, with its own message.
2. **A name of the Jenkinsfile that the file does not have falls back too.** `projectNames`, `PROJECT_NAMES` or `PROJECT_NAME` that names a service the file does not have leaves it out: `[INIT] [WARN] projectNames names web, which devsecops_config.yaml does not have (it has gui, backend-api) - the run goes on with backend-api`. When none of the names is a service of the file, the run builds every service of the file: `[INIT] [WARN] projectNames names web, which devsecops_config.yaml does not have - the run goes on with the default, every service of the file: gui, backend-api`.
3. **A key that the library would take anyway is named, so that it can be removed.** For every key of a service whose value is the value the library uses without it, the console says `[INIT] [INFO] <service>: <key>: <value> equals the library default - the key can be removed from devsecops_config.yaml`, or `equals the auto-detected value` when the auto-resolve of the settings ([chapter 16](#16-configuration-reference-devsecops_configyaml)) would find the same value in the repository, for example `buildTool: maven` next to a `pom.xml`, `deployTarget: openshift` next to a `Dockerfile`, or `tools.sonar.projectKey: com.bbh:<service>`. A `projectNames` that lists every service of the file in the order of the file gives `[INIT] [INFO] projectNames: gui, backend-api equals the default, every service of devsecops_config.yaml in its order - the parameter can be removed from the Jenkinsfile`. Removing such a key changes nothing in the run. The switches of the stages at the same time (`staticAnalysis.parallel`, `dynamicAnalysis.parallel`) have lines of their own (**Stages at the same time** in [chapter 16](#16-configuration-reference-devsecops_configyaml)), and the AppScan key settings that a service cannot set are named as ignored.

Compared with release b815d55 of the library, which read `config.yaml` and `resources/defaults.yaml`, a run of this release: reads `devsecops_config.yaml` first and `config.yaml` only without it; writes its build tags to `pipeline-config.yaml` instead of the repository file, so the first extended build after the update needs one security build made by this release; signs in to AppScan on Cloud with the one API key of the functional user from `asoc.keyId` and `asoc.token` of the library defaults, so a service sets only its `appId`; requires `agentNames`; accepts `projectNames` as a list; reads its defaults from `com/bbh/devsecops/defaults.yaml`; names the file in the report header and in `build_evidence`; and names a file that is not valid YAML or has no `projects:`.

## 5. Application Prerequisites

Before onboarding a project to the DevSecOps pipeline, the application and its repository must meet the following requirements:

- **Git repository accessible from Jenkins.** The project must live in a Git repository that the Jenkins controller can clone. The `Jenkinsfile` must be committed at the repository root and pushed to the branch configured in the Jenkins job. `devsecops_config.yaml`, or the former `config.yaml`, is committed at the root as well.
- **Supported build tool: Gradle, Maven, C# / .NET (`dotnet` / `msbuild`), Liquibase, or Flutter.** The codebase must use one of the supported build systems. Gradle wrapper (`gradlew`) and Maven wrapper (`mvnw`) are preferred and must be executable and committed to the repository; for C#/.NET on Windows, classic `MSBuild.exe` + `NuGet.exe` + `vstest.console.exe` works out of the box (with `dotnet` SDK CLI available via `useDotnetCli: true` or on Linux). The build tool is either set for the service in `devsecops_config.yaml` (`buildTool`) or auto-detected by `RepoStackAutoResolver`.
- **SQL and PL/SQL with Liquibase.** A Liquibase service runs the `liquibase` CLI of the agent. With a database block, which delivers and deploys Oracle changes, a master changelog that includes other files is XML, YAML or JSON, the agent is Linux or macOS and the service names a JDK; see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase).
- **Optional: a frontend that npm builds first.** When the repository holds a frontend, for example an Angular application that the Gradle or Maven build packages into the jar, the Node.js build of stage 0 can build it first (`build.node.enabled: true`). It needs the folder of the `package.json` (`build.node.dir`) and, for the default command `npm ci`, a committed `package-lock.json`. The frontend build must write its output outside `build/` and `target/`, because the `clean` task of Gradle and Maven deletes those folders, and the Gradle or Maven build must copy that output into the artifact, see [Troubleshooting](#24-troubleshooting-guide). Leave it off when the Gradle or Maven build already runs npm itself, for example through a Node plugin of the build.
- **Code must compile successfully.** The SAST stage (stage 4) compiles the project before it generates the IRX archive, unless `asoc.doCompile: false`. The compile command comes from `asoc.gradle` / `asoc.maven` / `asoc.dotnet`, or from the `build` section when `asoc` has none. A compilation error fails the SAST stage, so make sure every compile-time dependency is resolvable from the configured Nexus or NuGet repositories.
- **Unit tests with JaCoCo (Gradle/Maven), Cobertura/OpenCover (C#/.NET), or LCOV (Flutter/JS/Python) coverage.** The project must have runnable unit tests. For Gradle and Maven, JaCoCo must be configured in the build script to produce a coverage XML report. For C#/.NET, `vstest.console.exe` + `OpenCover.Console.exe` (or `dotnet test --collect:"XPlat Code Coverage"`) produces OpenCover/Cobertura XML coverage and `.trx` test results automatically parsed by the pipeline. An LCOV report, for example of an Angular frontend, counts as well, and a service with several reports is measured by their mean. The library requires a minimum line coverage of 60 %, taken from the defaults that the library bundles only; a project cannot set its own value. Below that level the Unit tests stage turns orange, the pipeline keeps running and reaches RD, and the Nexus release and the QC deployment stay blocked. A failing test run is treated the same way: the stage turns orange with the exit code as the reason, the coverage is still evaluated on the reports that were produced, and every later stage still runs. For Flutter the library itself runs `flutter test --coverage` in the main package and in each tested plugin and module, and reads the `coverage/lcov.info` of each.
- **A JDK on the agent, named in the configuration (not required for C#/.NET or Liquibase).** Set `javaPath` in the project configuration to the JDK the build and the tests must use, or set `buildToolAutoSetup: true` to let the library detect the required Java version from `pom.xml` or `build.gradle` and pick a matching JDK from the agent. Without either, Java/Gradle/Maven/Flutter Unit tests stop with **JAVA_HOME parameter is not specified**, unless the agent already exports `HAS_BUILD_TOOL_INSTALLED=true`. Services with `buildTool: dotnet` or `msbuild` never require `JAVA_HOME`.
- **HCL AppScan on Cloud (ASoC) application registered.** The application must be registered in ASoC and have a known application ID (UUID), which is the `appId` of the service in `devsecops_config.yaml`. Every pipeline signs in with the one API key of the functional ASoC user: its key ID is `asoc.keyId` of the library defaults (`resources/com/bbh/devsecops/defaults.yaml`), and its secret stays in the Jenkins **Secret text** credential named in `asoc.token` of the same file. A service cannot change the key; a `keyId`, `token` or `credentialsId` under `asoc`, `sast` or `dast` of a service is ignored with a warning. Both SAST and DAST scans are uploaded to the application of the service.
- **SonarQube project registered.** A project must exist in SonarQube with a unique `projectKey`. A badge token is required for SonarQube metric badges to appear in the HTML pipeline report: a Jenkins **Secret text** credential named in `badgeCredentialsId`, or the token itself in `badgeToken`. The SonarQube server must be reachable from the Jenkins agent.
- **Nexus IQ application registered.** The application must be registered in Nexus IQ with its public application ID. Build artifacts (JARs, WARs, DLLs, `*.nupkg`, `packages.config`, `*.csproj`, `Directory.Packages.props`) must match the `scanPatterns` configured for the service in `devsecops_config.yaml`. The Nexus IQ server must be reachable from the Jenkins agent.
- **Build artifacts produced in expected locations.** Gradle projects produce artifacts under `build/libs/`, Maven projects under `target/`, and .NET projects under `bin/Release/` (or `build.dotnet.publishDir` / `packages.config` for NuGet dependency scanning). These paths must match the `scanPatterns` configured for Nexus IQ and be valid artifact outputs for publishing. A service with a Node.js build also gets the `package-lock.json` of its frontend folder scanned, see [the stage reference](#17-pipeline-stage-reference).
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
| `copyArtifacts`, `lastSuccessful` | Copy Artifact plugin | Passing `pipeline-config.yaml`, `build.tag` and the release gate verdict downstream |
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
- For a Liquibase service: the `liquibase` CLI; with a database block Liquibase 4.33 or 5.0, an Oracle driver and utPLSQL-cli on the agent or downloaded from Nexus (`tools.liquibase`, `tools.oracleJdbc`, `tools.utplsql`), Maven for the zip, and Nexus repositories for database zips, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase)
- PowerShell (Windows agents)

### Script security

The library runs inside the Jenkins script sandbox and uses no construct that requires an administrator to approve a signature. Collections are built from literals, JSON is read and written with the `readJSON` and `writeJSON` steps, and regular expressions use the Groovy operators. Nothing has to be approved under **Manage Jenkins, In-process Script Approval** to onboard a new project.

Every class of the library is loaded and every pipeline is executed under the real script-security sandbox by the test suite before a release, so a rejected signature is found in the test run and never in your build. [Library Development and Test Suite](#25-library-development-and-test-suite) lists the rules this imposes on the library code.

### Jenkins global configuration

1. **SonarQube server** must be registered in **Manage Jenkins → Configure System → SonarQube servers** with the name `SonarQube`.
2. **Nexus IQ server** must be registered in **Manage Jenkins → Configure System → Sonatype Nexus Platform**.
3. The following credentials must exist in Jenkins Credentials Store:

| Credential ID | Type | Used for |
|---|---|---|
| `nexusiqP` | Username/Password | Nexus IQ authentication (also used by GoldenFix to read reports and remediation versions) |
| `asoc.token` of the library defaults, otherwise a credential named like `asoc.keyId` of the library defaults | Secret text | The API key secret of the functional AppScan on Cloud user, for the SAST and DAST sign-in of every service; `devsecops_config.yaml` holds only the application ID (`appId`) |
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
| `DSO_STATIC_ANALYSIS_PARALLEL` | no | `true` or `false` decides, before `staticAnalysis.parallel` of the services, whether the full and security pipelines run the unit tests, Nexus IQ, SAST and SonarQube at the same time. Unset, blank or another value (which the console names in a `[WARN]` line), `staticAnalysis.parallel` of the services decides, by default `true` ([the configuration reference](#16-configuration-reference-devsecops_configyaml)) |
| `DSO_DYNAMIC_ANALYSIS_PARALLEL` | no | `true` or `false` decides, before `dynamicAnalysis.parallel` of the services, whether the full and extended pipelines run the regression, smoke and performance tests and DAST at the same time. Unset, blank or another value, `dynamicAnalysis.parallel` of the services decides, by default `true` |
| `SA_LINUX_URL`, `SA_WIN_URL`, `PROXY_HOST`, `PROXY_PORT`, `PROXY_USER`, `APPSCAN_HOST`, `APPSCAN_SERVER_URL` | no | The tool environment of AppScan: the download of the AppScan client for Linux and Windows, the BBH proxy and the AppScan host. A value set here wins over `platform.environment` of the bundled defaults and over the library's own BBH value, see [Stage 1](#17-pipeline-stage-reference) |

### Global settings: the bundled defaults

The global settings of every pipeline are the `defaults:` section of `resources/com/bbh/devsecops/defaults.yaml` in the library: the security thresholds, the required coverage, the timeouts, the release gate, the GoldenFix policy and the tool defaults. The DevSecOps team owns the file and changes it with a library release; a project neither copies nor overrides the thresholds. A job uses the defaults of the library version it loads: the default version of **Global Pipeline Libraries**, or the version pinned in `@Library('DevSecOpsJenkinsLibrary@<version>') _`.

The optional `platform:` section of the same file holds the platform values. The production file has no such section, so the library uses its built-in BBH values, and a value of the agent or Jenkins environment still wins for the tool environment. [The configuration reference](#16-configuration-reference-devsecops_configyaml) lists both sections.

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

**C# and .NET applications:** Create the SonarQube project manually with its `projectKey` and credential ID. For classic MSBuild projects on Windows agents, the library wraps the build with `SonarScanner.MSBuild.exe begin` / `end`; for `dotnet` SDK CLI projects (`useDotnetCli: true`), it uses `dotnet sonarscanner begin` / `end`. When your SonarQube server uses Scanner for .NET in solution mode, `Coverage` (OpenCover / Visual Studio Coverage XML) and VSTest (`*.trx`) reports are automatically passed via `/d:sonar.cs.opencover.reportsPaths`, `/d:sonar.cs.vscoveragexml.reportsPaths`, and `/d:sonar.cs.vstest.reportsPaths`.

### HCL AppScan — SAST

**Where:** `bbh.cloud.appscan.com` · **Who:** the DevSecOps team creates the application, you note the identifiers

1. **Request an AppScan application.** Ask the DevSecOps team to create one for your product, giving them the application name and your team.
2. **Receive the Application ID.** It looks like `109f44ac-cc06-4ca0-884e-d944904f7019` and goes into `devsecops_config.yaml` as `appId`.
3. **No API key of your own.** SAST and DAST sign in with the API key of the functional AppScan on Cloud user, which the DevSecOps team keeps in the library defaults (`asoc.keyId` and the Jenkins **Secret text** credential named in `asoc.token`). Do not put a key ID or a secret in `devsecops_config.yaml`: the library ignores them and says so in the console. Ask the DevSecOps team to add your application to the functional user.
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
| the API key secret of the functional AppScan on Cloud user (`asoc.token` of the library defaults) | Secret text | The AppScan sign-in of SAST and DAST for every service, created once by the DevSecOps team | once for the library |
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

**`devsecops_config.yaml` describes the services.** Copy `devsecops_config.yaml.template` from the library, keep one entry under `projects:` for each service of the repository and fill in the values you collected in the previous chapter. A repository that already holds a `config.yaml` of an earlier release can rename it to `devsecops_config.yaml`; until then the library reads `config.yaml`. **Do not add thresholds or a coverage minimum**: they are global settings of the library and a project cannot change them, and a `defaults:` section in the file is ignored. The worked example for your pipeline is in your chapter, and every key the library reads is listed in the [configuration reference](#16-configuration-reference-devsecops_configyaml).

```yaml
projects:
  my-app:
    appId:     "<AppScan application ID>"
    buildTool: gradle
    javaPath:  /usr/lib/jvm/java-17-openjdk
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

1. **Read the colours.** Green means the stage met the policy. **Orange** means it ran but broke a rule — the build is unstable and the release is blocked, but the pipeline carried on so you can see every problem at once. Red means the stage failed and the pipeline stopped there. In the **Static analysis** and **Dynamic analysis** stages, the report shows which of the stages that ran at the same time failed; the others ran to their end and keep their results.
2. **Read the reasons.** Each orange or red stage carries its reason underneath, naming the counts and the consequence.
3. **Check the Release policy card.** It lists every reason the release and the higher environment deployment are blocked. When it is empty, your application is releasable.
4. **Read the times.** The table **Manual Actions vs DevSecOps** at the bottom of the report lists every step of the run with its own time, the time by hand, the time with DevSecOps and the time gained, and closes with the summary of the run in seconds, minutes and percent.

**An initial run is unlikely to complete without findings.** The usual first run findings are coverage below the required minimum, dependencies with known vulnerabilities, and test jobs that do not exist yet. Work through them one at a time — the pipeline provides value before every finding is resolved. [Troubleshooting](#24-troubleshooting-guide) lists what each message means.

## 9. Integration Guide: Full Pipeline

**Entry point:** `devSecOpsPipeline` · **Parameters:** `AGENT_NAME`, `DEPLOY_HIGHER_ENV`

### Applicability

A single Jenkins job executing the complete delivery flow: build, unit testing, all three security controls, code quality analysis, artifact publication, deployment to two environments, functional and performance testing, and dynamic security testing. Select this entry point where a single job covering the entire flow is acceptable and the resulting execution time is acceptable to the team.

**Thirteen stages:** source checkout · unit tests with the coverage gate · Nexus IQ · AppScan SAST · SonarQube · Nexus snapshot delivery · lower environment deployment · regression tests · smoke tests · performance tests · AppScan DAST · Nexus release delivery · higher environment deployment. Jenkins declares two more: **Static analysis**, which runs stages 2 to 5 at the same time, and **Dynamic analysis**, which runs stages 8 to 11 at the same time, both by default ([the stage reference](#17-pipeline-stage-reference)). For a Liquibase service with a database block every stage is described in [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase).

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

The listing leaves out the optional keys of a service: `sast.scanName` names the SAST scan in AppScan, `tools.sonar.badgeCredentialsId` names the Secret text with the SonarQube badge token, and `deploy.<target>.rd.nexusDeliveryEnabled: false` stops the snapshot delivery of the service. The [configuration reference](#16-configuration-reference-devsecops_configyaml) describes them.

### Optional: a frontend built with Node.js first

A service whose repository holds a frontend that npm builds, for example an Angular application that the Gradle or Maven build packages into the jar, switches on the Node.js build in the `build` section of the service, with the folder of the `package.json`:

```yaml
    build:
      node:
        enabled: true
        dir: frontend
```

The **Node.js build** stage then runs `npm ci` and `npm run build` in `frontend` right after the first stage, and the unit tests and every later stage use its output. The report numbers the stage 0. Nothing else is required. Without `build.node.installationName` or `build.node.nodePath` the commands use the installation that the bundled defaults name in `build.node.installationName`, and without one the `node` and `npm` on the `PATH` of the agent. The other keys, such as `commands`, `env` and the `.npmrc` credentials, are in [the configuration reference](#16-configuration-reference-devsecops_configyaml). How the output reaches the jar is described under [Troubleshooting](#24-troubleshooting-guide), **The frontend is missing from the jar**.

### Specific to this pipeline

- **VM deployment uses UrbanCode Deploy** here, unlike the extended pipeline which deploys over SSH. Set `deployTarget: openshift` for an OpenShift project.
- **`DEPLOY_HIGHER_ENV` is off by default.** The pipeline deploys there only when you tick it **and** everything else is green, so a promotion is always deliberate.

## 10. Integration Guide: Security Pipeline

**Entry point:** `devSecOpsSecurityPipeline` · **Parameters:** `AGENT_NAME`, `RUN_EXTENDED_PIPELINE`

### Applicability

The first half of the delivery flow, intended for execution on **every commit**. It requires no deployed environment and returns a security verdict within a short execution time. It is operated together with the entry point described in the following chapter, which executes the remaining stages on demand.

**Six stages:** source checkout · unit tests with the coverage gate · Nexus IQ · AppScan SAST · SonarQube · Nexus snapshot delivery. A service with `build.node.enabled: true` adds the Node.js build before the unit tests, as in the full pipeline. Jenkins declares one more, **Static analysis**, which by default runs stages 2 to 5 at the same time ([the stage reference](#17-pipeline-stage-reference)). For a Liquibase service with a database block it packages the changes, tests them on the test database and delivers the zip as a snapshot, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase).

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

**Eight stages:** source checkout · lower environment deployment · regression tests · smoke tests · performance tests · AppScan DAST · Nexus release delivery · higher environment deployment. Jenkins declares one more, **Dynamic analysis**, which by default runs the tests and DAST at the same time ([the stage reference](#17-pipeline-stage-reference)). For a Liquibase service with a database block it fetches the zip that the security pipeline delivered and deploys and releases exactly that zip, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase).

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

This pipeline needs the `deploy`, `tests.smoke`, `tests.regression`, `tests.performance` and `dast` blocks shown in the full pipeline chapter. It reads them from the file of its own checkout, so a change of the file applies at its next build. From the security job it takes only the run-time tags of the snapshot stage: the OpenShift build tag and image reference, the delivered version of a Liquibase service and the Flutter build tags, and `build.tag` with the snapshot version of a VM service. Run the security job once with this release of the library before the first extended build; a build of an earlier release archived `config.yaml` instead of `pipeline-config.yaml`, and the extended build stops with "\[INIT\] The last successful build of &lt;job&gt; has no pipeline-config.yaml: it was built by a library release that archived config.yaml instead; run &lt;job&gt; once with this release".

Without `securityPipeline` in the Jenkinsfile the extended pipeline runs on its own: it copies nothing and deploys what its own settings name, including a build tag pinned in `devsecops_config.yaml`.

### Specific to this pipeline

- **VM deployment is over SSH** here, not UrbanCode Deploy. For OpenShift set `deployTarget: openshift` and provide the cluster tokens as credentials.
- **Do not add a commit trigger.** This job is started by the security pipeline or by a person.
- **Copy Artifact permission.** This job reads files archived by the security job, which on some Jenkins instances requires the upstream job to permit it. If the first run reports that it cannot copy artifacts, that is the setting to ask the administrators about.
- **Connect the two jobs** by naming this job in `jenkins.pipeline.extendedPipeline` of the service, in the `devsecops_config.yaml` that the security job reads. From then on, running the security pipeline with `RUN_EXTENDED_PIPELINE` ticked starts this one automatically.
- **A frontend built with Node.js.** This pipeline has no Node.js build stage, because it builds nothing until the release. The release delivery stage runs the Node.js build of each service with `build.node.enabled: true` only when a release delivery is available for a service of the run, today the NuGet push of a .NET service with `delivery.nuget.source`. For Gradle and Maven the release delivery is not implemented yet, so no Node.js build runs and the snapshot of the security pipeline, which already holds the frontend, stays the delivered artifact.

## 12. Integration Guide: SAST Scanning Pipeline

**Entry point:** `devSecOpsSASTScanningPipeline` · **Parameters:** `AGENT_NAME`

### Applicability

The smallest option: it checks out your code and runs the AppScan static security scan, nothing else. Use it when you want a security scan of the source without building, testing or deploying — typically on a weekly schedule, for an application whose main pipeline lives elsewhere, or as the very first step of onboarding.

**Two stages:** source checkout · AppScan SAST. A service with `build.node.enabled: true` adds the Node.js build before the SAST stage when the SAST scan compiles it. A Liquibase service with a database block is scanned without a compile step, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase).

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
```

### Specific to this pipeline

- **This pipeline checks out the source itself** and sets up the JDK, because there is no build stage to do it.
- **Put it on a schedule.** In the job configuration tick **Build periodically** and enter something like `H 2 * * 1`, which runs it once a week early on Monday morning. Scanning source code does not need to happen on every commit.
- The report shows only the source checkout and the SAST stage, with a link to the AppScan report. There is no release gate card, because this pipeline releases nothing.
- **A frontend built with Node.js.** The Node.js build runs for a service only while the SAST stage compiles it: `asoc.doCompile` is not `false` and the agent is Linux or macOS. Otherwise the console says `[NODE] <service>: the SAST scan does not compile this service - no Node.js build`. After a Node.js build every IRX archive of the run leaves out `node_modules`, see [the stage reference](#17-pipeline-stage-reference).
- **Optional scan name.** `sast.scanName` names the scan in AppScan; without it the scan is named after `tools.sonar.projectName` or the service, followed by `-sast`.

## 13. Integration Guide: Nexus IQ GoldenFix Pipeline

**Entry point:** `devSecOpsNexusIqGoldenFixPipeline` · **Parameters:** `AGENT_NAME`

### Applicability

The dependency check on its own: it builds the artifact, has Nexus IQ evaluate it, and when the policy is violated GoldenFix raises the golden pull request with the safe dependency versions. Use it when the third party dependencies of an application should be checked and upgraded on their own schedule, without the unit tests, AppScan, SonarQube, delivery or deployment of the other pipelines — typically nightly, for an application whose main pipeline lives elsewhere, or beside the SAST scanning pipeline, which covers the source code.

**Three stages:** source checkout · artifact build · Nexus IQ with GoldenFix. A service with `build.node.enabled: true` adds the Node.js build before the artifact build. A Liquibase service with a database block is not required in both stages, and a run of such services only is refused, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase).

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
- `goldenFix` is on by default; every setting of [the configuration reference](#16-configuration-reference-devsecops_configyaml) applies, such as `minThreatLevel`, `ecosystems` and the pre-check build under `goldenFix.verify`.
- No AppScan key is needed: the key of the functional user is a setting of the library defaults and only AppScan stages read it. `appId` is shown so that one service entry serves every pipeline of the service.
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

**List the others.** A shared file that GoldenFix cannot recognise goes into `goldenFix.versionCatalogs` of the service, one repository-relative path or glob per entry (`*` within a folder name, `**` across folders). A listed `.toml` file is read as a Gradle catalog whatever its name, an `.xml` file as a Maven pom and any other file as a Gradle build file. Listing a file makes it the place where its versions are raised. A listed Gradle file is verified only in a build that uses it; a listed pom in the aggregator that builds it with the poms that use it, otherwise in its own folder, as the [Pipeline Stage Reference](#17-pipeline-stage-reference) describes. For example:

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

The catalog must live in the same repository as the services. A catalog published as an artifact (`from("com.bbh:platform-catalog:1.2")` in `settings.gradle`), a BOM resolved from Nexus and a catalog in another repository are not raised; the card names them as the reason, see [the stage reference](#17-pipeline-stage-reference).

### Specific to this pipeline

- **Linux or macOS agent.** GoldenFix works in a git worktree and runs the pre-check build with the shell, so the agent of `AGENT_NAME` needs git, `curl`, the build tool and what the unit tests of the service need, such as a headless browser for Karma or `python3` with `venv`.
- **This pipeline checks out the source itself** and sets up the JDK, because there is no unit test stage. The **Build artifact** stage then builds every project with the command of its `build` section, so Nexus IQ evaluates what was built. It checks no coverage, but the command runs as configured: Gradle `build` and Maven `verify` or `package` run the unit tests as well, so use Gradle `assemble` or Maven `package -DskipTests` to build without them. A build error, a failing test included, is a red stage and stops the pipeline, because there is nothing to scan.
- **The Nexus IQ stage is the one of the other pipelines.** It turns orange on a policy violation and GoldenFix raises one pull request for the projects of the build that share a repository, as described in [the stage reference](#17-pipeline-stage-reference). GoldenFix never changes the stage result.
- **Put it on a schedule** if the dependencies should be checked independently of commits, for example `H 3 * * *` under **Build periodically** for a nightly run: Nexus IQ learns about new vulnerabilities in components that did not change.
- **The report** shows the source checkout, the Node.js build when it ran, the Build artifact stage and the Nexus IQ stage, one Nexus IQ row per project in the Security Gates table with a link to the Nexus IQ report, and on a violation the **Nexus IQ GoldenFix** card with the pull request, the applied upgrades, the fixes that could not be applied automatically and the notes of the run. There is no release policy card, because this pipeline releases nothing, and no SAST, SonarQube or test rows.
- **Release gate.** The pipeline writes `release-gate.json` like every pipeline and does not archive it, because no pipeline takes it over. Its verdict follows the stages: allowed when every stage is green, blocked when the Nexus IQ stage is orange, including a scan that could not be completed.
- **Metrics.** Every run writes the measurements of [Monitoring and Metrics](#22-monitoring-and-metrics) with `variant=nexusiq` and the project tag `influx.project` followed by `nexusiq`, for example `MyAppnexusiq`. The `goldenfix` point of a project also carries the address and the title of the pull request (`pr_url`, `pr_title`) when one was raised. The Nexus IQ counts of the run are in `vulnerabilities` with `scanner=nexusiq`, and the `security_findings` point of each service with `scanner=niq` carries the counts of that service against the Nexus IQ limits of the library policy, as in every pipeline.

## 14. Integration Guide: SQL and PL/SQL with Liquibase

**Build tool:** `liquibase` or `sql` · **Pipelines:** all five · **Agents:** Linux, macOS or Windows for the Liquibase commands; Linux or macOS for a database block

This chapter is the complete description of services made of SQL and PL/SQL: what each pipeline does with them, the three ways to configure them, four complete repository layouts, the rules for a changelog, every setting, the credentials, the deployments, the report, the metrics and the messages. It assumes no earlier use of the library with a database. The general concepts of the pipelines are in chapters 9 to 13, and the settings of `devsecops_config.yaml` in chapter 16.

### 14.1 What the library does with SQL and PL/SQL

A service with `buildTool: liquibase` is a folder of Liquibase changelogs and the SQL and PL/SQL sources they deploy. A service with `buildTool: sql` is a folder of SQL and PL/SQL files without a changelog: the library writes the changelog itself. A database service is configured in one of three ways:

| Way | What the service sets | What the pipelines do | Databases |
|---|---|---|---|
| **Liquibase commands** | `buildTool: liquibase` and, as needed, the commands of `build.liquibase`, `tests.unitTests.liquibase`, `asoc.liquibase` and `tools.sonar.liquibase` | run the `liquibase` CLI with these commands in the Build artifact, Unit tests and SAST stages, scan the sources with AppScan and `sonar-scanner`, and skip the coverage requirement; the library packages, delivers and deploys nothing | every database that Liquibase supports, with the commands of the team |
| **Database block** | the same, and `database.changelogFile` with the other keys of the `database:` block | in addition: in Build artifact an offline validation, static checks of the sources, the review SQL and a zip with a deployment manifest; in Unit tests the deployment to a test database and utPLSQL with coverage; the zip to Nexus as a snapshot and a release; the deployment of the delivered zip to RD and QC with Jenkins credentials and a compile check | Oracle only |

|**SQL and PL/SQL files only**|`buildTool: sql` (also written `plsql`, `pl/sql` or `pl-sql`), or no `buildTool` in a repository of only SQL and PL/SQL files, and the keys of the `database:` block without `database.changelogFile`|everything a database block does; at the start of the run the library writes the changelog `dso-changelog.xml` from the files of the search root (14.4, **The changelog of buildTool sql**)|Oracle only|

Choose the Liquibase commands when the team deploys the changes itself, or for a database other than Oracle. Choose the database block when the pipelines are to test, deliver and deploy Oracle changes that a Liquibase changelog describes. Choose `buildTool: sql` when the repository holds only `.sql` and PL/SQL files and nobody maintains a changelog: every rule of this chapter for a database block then applies, and `buildTool: sql` is always a database block.

The commands of the first way stay in force with a database block: a command of `build.liquibase` runs first in Build artifact, a command of `tests.unitTests.liquibase` runs first in Unit tests, the command of `asoc.liquibase` runs before the SAST scan, and the arguments of `tools.sonar.liquibase` win over those the block adds. With a block, a command of `build.liquibase` or `tests.unitTests.liquibase` runs only when the service sets its commands or its flags. Without a block, the auto-resolve of the settings ([chapter 16](#16-configuration-reference-devsecops_configyaml), on unless the service sets `autoresolve parameters: false`) fills a missing `build.liquibase.commands` with `updateSQL`, a missing `tests.unitTests.liquibase.commands` with `validate`, and their missing flags with `--changelog-file=` and the changelog it finds in the repository (`src/main/resources/changelog.xml` when it finds none); with the auto-resolve off, Build artifact runs `liquibase validate` when `build.liquibase` sets no command, and Unit tests runs `liquibase validate` when `tests.unitTests.liquibase` sets flags but no command, else nothing.

The library never deploys to production and never rolls back. A database block deploys only to the databases that the DevSecOps team allows in the library defaults (`defaults.database.allowedTargets` of `resources/com/bbh/devsecops/defaults.yaml`).

**Detection of the build tool.** When `buildTool` is empty, the library detects it from the files at the root of the workspace, in one of two orders:

- **With the auto-resolve of the settings on** (the default, [chapter 16](#16-configuration-reference-devsecops_configyaml)), it decides at the start of the run, in this order: `pubspec.yaml` → `flutter`; then `Podfile`, `Package.swift`, a `*.xcodeproj` or a `*.xcworkspace` → `ios`; then `pom.xml` → `maven`; then `build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts` or `gradle/wrapper/gradle-wrapper.properties` → `gradle` (`android` with an `AndroidManifest.xml`); then `global.json`, `packages.config`, `Directory.Packages.props`, `Directory.Build.props`, `build.cake`, `.nuke`, a `*.sln` or a `*.csproj` / `*.fsproj` / `*.vbproj` → `dotnet`; then `liquibase.properties`, `changelog.xml`, `changelog.yaml`, `changelog.yml`, `changelog.json`, `db/changelog.xml`, `db/changelog/db.changelog-master.xml`, `src/main/resources/db/changelog/db.changelog-master.xml` or `src/main/resources/changelog.xml` → `liquibase`; then `requirements.txt`, `pyproject.toml`, `setup.py` or `Pipfile` → `pip`; then `package.json` or `angular.json` → `npm`; then, when the folder has none of the Liquibase files of the longer list below, any `.sql`, `.pks`, `.pkb`, `.pls`, `.plb`, `.pck`, `.prc`, `.fnc`, `.trg`, `.tps`, `.tpb`, `.typ`, `.bdy` or `.vw` file in the folder of the service or below it, outside folders whose name starts with a dot → `sql`. The application build files come **before** the Liquibase files, and the SQL files come last: one build file anywhere in the list makes the service that kind of service, whatever SQL it also holds. When none of them is there, `buildTool` stays empty and the order below applies. For a service it finds to be `sql` it also sets `buildToolAutoSetup: true` when the service has no `javaPath`, and `database.searchPath` to the one top folder that holds all of the files when the service sets neither `sourceDir` nor `database.searchPath`; each value it sets is one **Auto-resolved** line of the console and one row of the report. The auto-resolve fills the Liquibase commands and flags only for a service whose `buildTool` is `liquibase` at the start of the run, set in its settings or found from the list above. A service that the stages find to be Liquibase only from the longer list below, such as one with only `sql/changelog.xml`, runs `liquibase validate` in Build artifact and no Unit tests command, as with the auto-resolve off.
- **With the auto-resolve off** (`autoresolve parameters: false`), `sql` is never detected: set `buildTool: sql`. Each stage detects the other tools in this order: `pubspec.yaml` → Flutter, `pom.xml` → Maven, `build.gradle` or `build.gradle.kts` → Gradle; then `liquibase.properties`, `changelog.xml`, `changelog.yaml`, `changelog.yml`, `changelog.json`, `changelog.sql`, `db/changelog.xml`, `db/changelog.yaml`, `db/changelog.sql`, `db/changelog/db.changelog-master.xml`, `db/changelog/db.changelog-master.yaml`, `sql/changelog.xml`, `sql/changelog.sql`, `liquibase/changelog.xml`, `src/main/resources/db/changelog/db.changelog-master.xml`, `src/main/resources/db/changelog/db.changelog-master.yaml` or `src/main/resources/changelog.xml` → Liquibase; otherwise Gradle.

Because the application build files come first, a Maven or Gradle application that ships a Liquibase changelog stays a Maven or Gradle service: a Spring Boot application with the standard `src/main/resources/db/changelog/db.changelog-master.xml` next to its `pom.xml` is built with Maven. Set `buildTool: liquibase` for a database service with a changelog. Detection never switches the database block of a changelog on: that block needs `buildTool: liquibase` in the settings of the service, and a service with `database.changelogFile` and no `buildTool` is never detected as `sql`. Only a repository without any build file and without a Liquibase changelog is detected as `sql`, which is a database block by itself. The auto-resolve keeps to this: for a service with `database.changelogFile` and one of its Liquibase files it leaves `buildTool` empty, so that service runs without the block, with the tool that the stages detect in the second order; and it adds no Liquibase command, no Node.js stage 0 and no `deployTarget` to a service with a block.

| Stage | Liquibase commands only | With a database block |
|---|---|---|
| Monitor source changes | as for every service | checks the settings of every service with a block and, in the full and security pipelines, that the changelog and the utPLSQL folders exist; each problem is one line, and the build stops after the list; a Windows agent stops it as well |
| Build artifact | `liquibase <flags> <commands>` of `build.liquibase`; without commands, with the auto-resolve on and `buildTool: liquibase` at the start of the run `updateSQL` (and `--changelog-file=` of the changelog it finds when no flags are set), otherwise `validate` | the command of `build.liquibase` when it is set; in the full and security pipelines the offline validation, the static checks, the review SQL, the zip and its validation; in the SAST and Nexus IQ GoldenFix pipelines **not required** unless `build.liquibase` ran |
| Unit tests | the command of `tests.unitTests.liquibase`; without commands, with the auto-resolve on and `buildTool: liquibase` at the start of the run `validate` (and `--changelog-file=` of the changelog it finds when no flags are set), otherwise `validate` when its flags are set, else none; the coverage requirement is skipped when no report has measured lines | the command of `tests.unitTests.liquibase` when it is set; then the deployment to the test database and utPLSQL with coverage, or **not required** without a test database |
| Dependencies scan (Nexus IQ) | as for every service (`tools.nexusIq`) | **not required**: SQL and PL/SQL have no dependency components, unless `tools.nexusIq.scanPatterns` is set |
| SAST - HCL AppScan | the command of `asoc.liquibase`, else of `build.liquibase`, when one is set and `asoc.doCompile` is not false; then the scan of the sources without a Gradle or Maven compile | the same; the scan is always of source code only (`-sco`) |
| SCA (SonarQube) | `sonar-scanner` on the sources, with the arguments of `tools.sonar.liquibase` | `sonar-scanner` when the service has `tools.sonar.projectKey` (the auto-resolve sets `com.bbh:<service>` when it is missing, so it is missing only with `autoresolve parameters: false`) and the server has a PL/SQL analyzer, else **not required** |
| Nexus delivery (Static analysis passed) | publishes nothing for a Liquibase service | the zip as `<version>-SNAPSHOT` to the snapshot repository |
| Lower test region deployment | the deployment of `deployTarget` and `deploy`, as for every service | the delivered zip to the RD database; `deployTarget` and `deploy` are not used |
| Regression, smoke and performance tests | the jobs of `tests.<suite>`, as for every service | the jobs of `tests.<suite>`; without jobs **not required**, unless `tests.<suite>.required` is true |
| DAST - HCL AppScan | as for every service | **not required**, unless `dast.enabled` is true |
| Nexus delivery - Safe Artifact | publishes nothing for a Liquibase service | the same zip as `<version>` to the release repository, when the release gate allows it |
| Higher test environment deployment | the deployment of `deployTarget` and `deploy`, as for every service | the delivered zip to the QC database, with `DEPLOY_HIGHER_ENV` and an allowed release gate |

A Liquibase service without a database block and without deployment settings therefore fails **Lower test region deployment** of the full pipeline (in the test suite with **\[DOD\] No UrbanCode Deploy applications are configured**). Run such a service in the security or SAST pipeline, give it the deployment settings of its `deployTarget`, or give it a database block.

In a run of several services each stage result is formed from all services, as for any run: a stage is **not required** only when it is not required for every service.

### 14.2 Before you start

**For the Liquibase commands:**

- The `liquibase` CLI on the `PATH` of the agent (Linux, macOS or Windows), with the JDBC driver that its commands need.
- Java for Liquibase: `javaPath` is optional; when it is set, the library exports it as `JAVA_HOME`, otherwise Liquibase uses the Java runtime of the agent.

**For a database block:**

- **Agent.** Linux or macOS: a service with a database block on a Windows agent stops in the Monitor stage. JDK 17 or newer, named by `javaPath`, or found by `buildToolAutoSetup: true` with the Java version of `tools.liquibase.javaVersion` (17). Maven for the Nexus upload and fetch (`database.package.mvnPath`, else `build.maven.mvnPath`, else `mvn` of the agent), and `curl`, `tar` and `unzip` when the tools are downloaded.
- **Tools.** Liquibase 4.33.x or 5.0.x (`tools.liquibase`), an Oracle driver ojdbc11 or ojdbc17 (`tools.oracleJdbc`, required by Liquibase 5, which ships no driver, and by a trimmed Liquibase 4.33.0), and utPLSQL-cli 3.2.2 when utPLSQL runs (`tools.utplsql`). Each comes from `home` or `path` on the agent, else from a Nexus `url` with its `sha256` (downloaded into `<workspace>@tmp/dso-tools` and kept there), else from the `PATH` of the agent.
- **The DevSecOps team** fills the library defaults (`resources/com/bbh/devsecops/defaults.yaml` of the library release): `defaults.database.allowedTargets`, the tool locations and the Nexus repositories for database zips (`defaults.database.package`). The agents reach only the BBH network, so the team downloads the tools from GitHub and Maven Central and uploads them to a raw hosted Nexus repository.
- **The DBA** creates a schema and a deploy user per target (the owner, or a proxy user, 14.8), one test schema per pipeline with the utPLSQL framework installed, and the grants of 14.8 and 14.9, and opens the database ports (usually 1521, or 2484 for TCPS) to the agents.
- **Jenkins.** One **Username with password** credential per target in the folder of the product. No folder that runs the library may hold production credentials.

**Which Liquibase.** Liquibase 4.33.0 installed on the agents is recommended: it is the last release whose core is under the Apache-2.0 licence. Its bundle also holds `internal/lib/liquibase-commercial.jar` and the folder `internal/extensions` under the Liquibase Pro licence, and JDBC drivers under other licences. The **trimmed install** removes those two, `internal/lib/ojdbc8.jar` and the drivers of the other databases, keeps `LICENSE.txt` and the folder `licenses`, and takes its Oracle driver from `tools.oracleJdbc`. A full 4.33.0 uses its bundled `internal/lib/ojdbc8.jar` before the configured driver, and the console says so. Liquibase 5.0.x is under the Functional Source License FSL-1.1-ALv2, which becomes Apache-2.0 two years after each release: internal use is allowed, offering Liquibase itself as a competing product is not. Use Liquibase 5.x only after the BBH open-source office has accepted FSL-1.1-ALv2.

| Component | Licence | Note |
|---|---|---|
| Liquibase 4.33.0 | core Apache-2.0; the bundle also holds commercial jars under the Liquibase Pro licence and drivers under the IPLA, FUTC, LGPL, EPL and CDDL licences | recommended, as the trimmed install |
| Liquibase 5.0.x | FSL-1.1-ALv2 | only after the BBH open-source office has accepted the licence; needs `tools.oracleJdbc` |
| utPLSQL-cli 3.2.2 | Apache-2.0 | bundles its own Oracle driver |
| ojdbc11, ojdbc17 | Oracle Free Use Terms and Conditions | – |

The SHA-256 of the files from GitHub and Maven Central, which a copy in Nexus keeps; a trimmed 4.33.0 is a new file whose SHA-256 the DevSecOps team computes with `sha256sum`:

| File | SHA-256 |
|---|---|
| Liquibase 4.33.0, `liquibase-4.33.0.tar.gz` | `689acfcdc97bad0d4c150d1efab9c851e251b398cb3d6326f75e8aafe40ed578` |
| Liquibase 5.0.4, `liquibase-5.0.4.tar.gz` | `bb08638d70dddd6af8cca6e033148576f8214b5996831b1f8864eb3528f4cfce` |
| utPLSQL-cli 3.2.2, `utPLSQL-cli.zip` | `15a3addd921b7f2e415537b47c18a4a9d3e0b70ff0e403943fd4c03b0ba02006` |
| ojdbc11 23.26.4.0.0, `ojdbc11-23.26.4.0.0.jar` | `df977e9291499cd4ceee7d577462c285987dd2ad0216637c0865eb98956a9e6e` |

The library prints the licence it found, for example `Liquibase 4.33.0, licence Apache-2.0 (core; bundle includes third-party and commercial components)`. It refuses Liquibase older than 4.33.0 and Liquibase 6 or newer; a version other than 4.33.x and 5.0.x runs with a line that it has not been tested.

### 14.3 Repository layouts

Each folder of changelogs is a service of its own. A repository of only PL/SQL is one service. A repository with an application and a database folder has two or more services: the Liquibase service of the database folder, and the Gradle or Maven service of the application. **List the database service first**: the services of a run run in their order, so the database changes then reach RD and QC before the application that needs them; otherwise the console warns at the start of the build.

Three terms describe where Liquibase finds the files:

- **Search root:** `sourceDir`, joined with `database.searchPath` (default `.`). It is the folder that Liquibase sees as its root, and every path of the changelog is relative to it.
- **Changelog:** `database.changelogFile`, the master changelog, relative to the search root. Liquibase records this path with every applied changeset, unless the master sets `logicalFilePath`.
- **Package folder:** the first folder of the changelog path (`db` for `db/changelog-master.xml`). The zip holds that folder, so the changelog path is the same inside the zip. A changelog without a folder puts the whole search root into the zip; the search root must then not be the repository root.

The four layouts below are complete: each file shown is a file of `test/fixtures/database`, and the test suite runs layout (a) in all five pipelines, layout (b) in the full, security and Nexus IQ GoldenFix pipelines, layout (c) in the security pipeline and layout (d) in the full, security and SAST pipelines. The SQL and PL/SQL files of layouts (b) and (c) are those of layout (a), in the folder of their changelog.

#### Layout (a): only PL/SQL packages

One service, `orders-db`, with `sourceDir: "."`: the search root is the repository root, the package folder is `db`, and the utPLSQL folders are relative to the search root (`db/code`, `db/test`).

Files of layout (a) (`test/fixtures/database/layout-a`):

```text
devsecops_config.yaml
Jenkinsfile
db/changelog-master.xml
db/tables/001-create-orders.sql
db/tables/002-create-order-items.sql
db/code/pkg_orders.pks
db/code/pkg_orders.pkb
db/code/v_order_totals.vw
db/code/trg_orders_bi.trg
db/test/ut_pkg_orders.pks
db/test/ut_pkg_orders.pkb
```

File `devsecops_config.yaml` (`test/fixtures/database/layout-a/devsecops_config.yaml`):

```yaml
projects:
  orders-db:
    appId:     "6f0c2a9e-4b1d-4e57-9a43-0d8e2f6b7c10"
    buildTool: liquibase
    sourceDir: "."
    javaPath:  /usr/lib/jvm/java-17-openjdk
    asoc:
      url:   "https://bbh.cloud.appscan.com"
    database:
      changelogFile: db/changelog-master.xml
      parameters:
        app_role: orders_app_role
      package:
        groupId: com.bbh.orders
        snapshotRepository: {id: bbh-db-snapshots, url: "https://tools.bbh.com/nexus/repository/db-snapshots"}
        releaseRepository:  {id: bbh-db-releases,  url: "https://tools.bbh.com/nexus/repository/db-releases"}
      test:
        url:           "jdbc:oracle:thin:@//dev-db.bbh.com:1521/ORDERS_CI"
        credentialsId: orders-db-test
        utplsql:
          enabled:    true
          sourcePath: db/code
          testPath:   db/test
      rd:
        url:           "jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD"
        credentialsId: orders-db-rd
      qc:
        url:           "jdbc:oracle:thin:@tcps://qc-db.bbh.com:2484/ORDERS_QC?ssl_server_dn_match=true"
        credentialsId: orders-db-qc
        parameters:
          app_role: orders_qc_role
```

- The three database hosts must be in `defaults.database.allowedTargets`.
- The `asoc` block holds no API key: SAST signs in with the API key of the functional user, `asoc.keyId` and `asoc.token` of the library defaults, as for every service.
- `database.test.utplsql.paths` is left out, so utPLSQL runs the suites of the login schema, which is the owner with a proxy login (14.8).
- QC has a changelog parameter of its own; the other targets use `database.parameters`.

The Jenkinsfile names the service:

File `Jenkinsfile` (`test/fixtures/database/layout-a/Jenkinsfile`):

```groovy
@Library('DevSecOpsJenkinsLibrary') _
devSecOpsPipeline(projectNames: 'orders-db', agentNames: ['linux-agent'])
```

**The library defaults** come from `resources/com/bbh/devsecops/defaults.yaml` of the library release (chapter 4). This release ships it without allowed databases, tools and Nexus repositories for database packages, so the file above stops in Monitor source changes with **defaults.database.allowedTargets is empty** until the DevSecOps team fills them in. Until then, leave out `test`, `rd` and `qc`, put Liquibase on the `PATH` of the agent or set `tools.liquibase` and `tools.oracleJdbc` under the service, and set `database.package.snapshotRepository` and `database.package.releaseRepository` under the service, or `database.package.nexusDelivery: false`. The pipelines then validate, package and deliver the changes, and deploy them nowhere.

The master changelog deploys the tables through `includeAll` and every code object through a `sqlFile` changeset of its own (14.4):

File `db/changelog-master.xml` (`test/fixtures/database/layout-a/db/changelog-master.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <includeAll path="tables/" relativeToChangelogFile="true" errorIfMissingOrEmpty="true"/>

    <changeSet id="pkg_orders.pks" author="orders-team" runOnChange="true">
        <sqlFile path="code/pkg_orders.pks" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="pkg_orders.pkb" author="orders-team" runOnChange="true">
        <sqlFile path="code/pkg_orders.pkb" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="v_order_totals.vw" author="orders-team" runOnChange="true">
        <sqlFile path="code/v_order_totals.vw" relativeToChangelogFile="true" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="trg_orders_bi.trg" author="orders-team" runOnChange="true">
        <sqlFile path="code/trg_orders_bi.trg" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>

    <changeSet id="ut_pkg_orders.pks" author="orders-team" runOnChange="true" context="test">
        <sqlFile path="test/ut_pkg_orders.pks" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="ut_pkg_orders.pkb" author="orders-team" runOnChange="true" context="test">
        <sqlFile path="test/ut_pkg_orders.pkb" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
</databaseChangeLog>
```

A table file in formatted SQL, one DDL per changeset, each with its rollback:

File `db/tables/001-create-orders.sql` (`test/fixtures/database/layout-a/db/tables/001-create-orders.sql`):

```text
--liquibase formatted sql

--changeset orders-team:001-create-orders-seq
--comment: Sequence for order ids
CREATE SEQUENCE orders_seq START WITH 1 INCREMENT BY 1;
--rollback DROP SEQUENCE orders_seq;

--changeset orders-team:001-create-orders
CREATE TABLE orders (
  order_id    NUMBER(19)    NOT NULL,
  customer    VARCHAR2(100) NOT NULL,
  amount      NUMBER(12,2)  DEFAULT 0 NOT NULL,
  created_at  DATE,
  CONSTRAINT orders_pk PRIMARY KEY (order_id)
);
--rollback DROP TABLE orders;

--changeset orders-team:001-create-orders-idx
CREATE INDEX orders_customer_ix ON orders (customer);
--rollback DROP INDEX orders_customer_ix;
```

A package specification, with the `/` on a line of its own at the end:

File `db/code/pkg_orders.pks` (`test/fixtures/database/layout-a/db/code/pkg_orders.pks`):

```text
CREATE OR REPLACE PACKAGE pkg_orders AS
  -- Public API for orders. This comment must stay in USER_SOURCE.
  FUNCTION total_for(p_customer IN VARCHAR2) RETURN NUMBER;
  PROCEDURE add_order(p_customer IN VARCHAR2, p_amount IN NUMBER);
END pkg_orders;
/
```

A utPLSQL test package, deployed only with the context `test`:

File `db/test/ut_pkg_orders.pks` (`test/fixtures/database/layout-a/db/test/ut_pkg_orders.pks`):

```text
CREATE OR REPLACE PACKAGE ut_pkg_orders AS
  --%suite(Orders package)
  --%suitepath(orders)

  --%test(Total of a customer without orders is zero)
  PROCEDURE total_without_orders;

  --%test(Adding an order raises the total)
  PROCEDURE add_order_raises_total;
END ut_pkg_orders;
/
```

#### Layout (b): Gradle multi-module with a Java module, a Liquibase module and a Node.js frontend

A Gradle build with the Java module `app`, the Liquibase module `orders-db` and an npm frontend in `frontend`. Two services, in this order:

1. `orders-db`: `buildTool: liquibase`, `sourceDir: orders-db`, `database.searchPath: src/main/resources` and `database.changelogFile: db/changelog/db.changelog-master.xml`. The search root is `orders-db/src/main/resources` and the package folder `db`. The master sets `logicalFilePath="orders-db"`, so the identity of its changesets does not depend on the folder.
2. `orders-app`: `buildTool: gradle`, `sourceDir: "."`, the Gradle build of the whole repository and the Node.js build of `frontend` (`build.node`). It is the service `gui` of the reference implementation ([chapter 15](#15-reference-implementation-examples), `test/fixtures/CertScanner/config.yaml`) with these changes: `excludedDirs: orders-db`, so that its SAST scan leaves the database folder to `orders-db`; `build.node` with `enabled: true` and `dir: frontend`; `app/build/libs`, the folder of the jar of `app`, in `tools.nexusIq.scanPatterns` and as the `baseDir` of its UrbanCode Deploy component; no AppScan key (`asoc.keyId`, `asoc.token`, `asoc.keySecret`), because SAST signs in with the key of the functional user from the library defaults; and no `influx` block, because a documented example holds no secret value.

The pipeline never runs the Liquibase Gradle plugin of `orders-db/build.gradle`: it is there for the developers. A Spring Boot application of the same repository sets `spring.liquibase.enabled=false`, because the pipeline deploys the changes.

Files of layout (b) (`test/fixtures/database/layout-b`):

```text
devsecops_config.yaml
Jenkinsfile
settings.gradle
app/build.gradle
frontend/package.json
orders-db/build.gradle
orders-db/src/main/resources/db/changelog/db.changelog-master.xml
orders-db/src/main/resources/db/changelog/tables/001-create-orders.sql
orders-db/src/main/resources/db/changelog/tables/002-create-order-items.sql
orders-db/src/main/resources/db/changelog/code/pkg_orders.pks
orders-db/src/main/resources/db/changelog/code/pkg_orders.pkb
orders-db/src/main/resources/db/changelog/code/v_order_totals.vw
orders-db/src/main/resources/db/changelog/code/trg_orders_bi.trg
orders-db/src/main/resources/db/changelog/test/ut_pkg_orders.pks
orders-db/src/main/resources/db/changelog/test/ut_pkg_orders.pkb
```

File `settings.gradle` (`test/fixtures/database/layout-b/settings.gradle`):

```groovy
include 'app', 'orders-db'
```

File `orders-db/build.gradle` (`test/fixtures/database/layout-b/orders-db/build.gradle`):

```groovy
plugins { id 'org.liquibase.gradle' version '3.0.2' }
liquibase { activities { main { changelogFile 'db/changelog/db.changelog-master.xml' } } }
```

File `devsecops_config.yaml` (`test/fixtures/database/layout-b/devsecops_config.yaml`):

```yaml
projects:
  orders-db:
    appId:     "6f0c2a9e-4b1d-4e57-9a43-0d8e2f6b7c10"
    buildTool: liquibase
    sourceDir: orders-db
    buildToolAutoSetup: true
    asoc:
      url:   "https://bbh.cloud.appscan.com"
    database:
      searchPath:    src/main/resources
      changelogFile: db/changelog/db.changelog-master.xml
      package:
        groupId: com.bbh.orders
        snapshotRepository: {id: bbh-db-snapshots, url: "https://tools.bbh.com/nexus/repository/db-snapshots"}
        releaseRepository:  {id: bbh-db-releases,  url: "https://tools.bbh.com/nexus/repository/db-releases"}
      test:
        url:           "jdbc:oracle:thin:@//dev-db.bbh.com:1521/ORDERS_CI"
        credentialsId: orders-db-test
        utplsql:
          enabled:    true
          sourcePath: db/changelog/code
          testPath:   db/changelog/test
      rd:
        url:           "jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD"
        credentialsId: orders-db-rd
      qc:
        url:           "jdbc:oracle:thin:@//qc-db.bbh.com:1521/ORDERS_QC"
        credentialsId: orders-db-qc

  orders-app:
    appId:        "209f44ac-dd06-4ca0-884e-d944904f8020"
    buildTool:    gradle
    deployTarget: vm
    sourceDir:    "."
    javaPath:     /usr/lib/jvm/java-17-openjdk
    excludedDirs: orders-db

    jenkins:
      pipeline:
        extendedPipeline: "DevSecOps/CertScanner-extended-pipeline"

    asoc:
      url:         "https://bbh.cloud.appscan.com"
      insecureTls: false

    tools:
      sonar:
        projectName:      "CertScanner-GUI"
        projectKey:       "cert-scanner-gui"
        serverUrl:        "https://tools.bbh.com/sonar"
        credentialsId:    "sonarqube-token"
        installationName: "SonarQube"
        authToken:        "sonarqube-token"
        badgeToken:       "sqb_95b6b9cfb2fe8f3fa0e45261ece1856d3fb1ebc"
        addBadges:        true
        fullBadges:       true
        gradle:
          tasks: ['sonarqube']
      nexusIq:
        application:        "CertValidityMonitoring-GUI"
        serverUrl:          "https://tools.bbh.com/IQ"
        credentialsId:      "nexusiqP"
        scanPatterns:       ["**/app/build/libs/*.jar"]
        stage:              "build"
        failOnNetworkError: false

    sast:
      scanName: "CertScanner-GUI-SAST"

    sca:
      scanName: "CertScanner-GUI-SCA"

    dast:
      enabled:    true
      scanName:   "CertScanner-GUI-DAST"
      targetUrl:  "http://rdltaapps1.testbbh.com"
      presenceId: ""

    build:
      node: {enabled: true, dir: frontend}
      gradle:
        tasks: ['clean','build','bootJar']
        flags: ['--refresh-dependencies']

    scm:
      bitbucket:
        url:           "https://bitbucket.bbh.com/projects/TA/repos/cert-scanner"
        credentialsId: "bitbucket-http-credentials"
        authType:      basic
        targetBranch:  ""
        reviewers:     []

    goldenFix:
      enabled: true

    tests:
      unitTests:
        gradle:
          tasks: ['test','jacocoTestReport','jacocoTestCoverageVerification']
        unitTestResult: 'build/test-results/test/*.xml'
      regression:
        maxParallel: 5
        jobs:
          - name:       "CertScanner-GUI - regression"
            type:       local
            job:        "cert-scanner/regression-tests"
            timeoutMin: 60
          - name:       "CertScanner-GUI - regression (certificates)"
            type:       local
            job:        "cert-scanner/regression-certificates"
            parameters: "ENV=rd"
            timeoutMin: 60
      smoke:
        maxParallel: 20
        defaults:
          type:          remote
          timeoutMin:    15
          credentialsId: "remote-jenkins-api-token"
        jobs:
          - name: "CertScanner-GUI - login smoke"
            url:  "https://jenkins-a.bbh.com/job/smoke/job/cert-scanner-login"
          - name: "CertScanner-GUI - certificate list smoke"
            url:  "https://jenkins-a.bbh.com/job/smoke/job/cert-scanner-list"
          - name:          "CertScanner-GUI - notification smoke"
            job:           "smoke/cert-scanner-notifications"
            remoteJenkins: "jenkins-b"
            parameters:    "ENV=rd"
          - name:       "CertScanner-GUI - local smoke"
            type:       local
            job:        "cert-scanner/smoke-tests"
            timeoutMin: 15
          - "https://jenkins-c.bbh.com/job/smoke/job/cert-scanner-dashboard"
        urls:
          - "https://jenkins-b.bbh.com/job/smoke/job/cert-scanner-expiry"
      performance:
        jobs:
          - name:       "CertScanner-GUI - performance"
            type:       local
            job:        "cert-scanner/performance-tests"
            timeoutMin: 120

    deploy:
      vm:
        dod:
          siteName:      "deploy.bbh.com"
          deployProcess: "tomcat-app-process"
          applications:
            - applicationName: "CertScanner-GUI"
              components:
                - componentName:       "CertScanner-GUI-app"
                  baseDir:             "app/build/libs"
                  fileIncludePatterns: "*.jar"
        rd:
          host:         "rdltaapps1.testbbh.com"
          user:         "taadmin"
          deployDir:    "/opt/ta/CertScanner/gui/deployment"
          deployScript: "scripts/deployment/zero-downtime-deployment.sh"
          versionFile:  "scripts/deployment/version.properties"
        qc:
          host:         "qcltaapps1.testbbh.com"
          user:         "taadmin"
          deployDir:    "/opt/ta/CertScanner/gui/deployment"
          deployScript: "scripts/deployment/zero-downtime-deployment.sh"
          versionFile:  "scripts/deployment/version.properties"
```

File `orders-db/src/main/resources/db/changelog/db.changelog-master.xml` (`test/fixtures/database/layout-b/orders-db/src/main/resources/db/changelog/db.changelog-master.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog logicalFilePath="orders-db"
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <includeAll path="tables/" relativeToChangelogFile="true" errorIfMissingOrEmpty="true"/>

    <changeSet id="pkg_orders.pks" author="orders-team" runOnChange="true">
        <sqlFile path="code/pkg_orders.pks" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="pkg_orders.pkb" author="orders-team" runOnChange="true">
        <sqlFile path="code/pkg_orders.pkb" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="v_order_totals.vw" author="orders-team" runOnChange="true">
        <sqlFile path="code/v_order_totals.vw" relativeToChangelogFile="true" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="trg_orders_bi.trg" author="orders-team" runOnChange="true">
        <sqlFile path="code/trg_orders_bi.trg" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>

    <changeSet id="ut_pkg_orders.pks" author="orders-team" runOnChange="true" context="test">
        <sqlFile path="test/ut_pkg_orders.pks" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="ut_pkg_orders.pkb" author="orders-team" runOnChange="true" context="test">
        <sqlFile path="test/ut_pkg_orders.pkb" relativeToChangelogFile="true"
                 splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
</databaseChangeLog>
```

File `Jenkinsfile` (`test/fixtures/database/layout-b/Jenkinsfile`):

```groovy
@Library('DevSecOpsJenkinsLibrary') _
devSecOpsPipeline(projectNames: 'orders-db,orders-app', agentNames: ['linux-agent'])
```

Console of `test/sandbox/golden/database-full-gradle-module-green.txt` (shortened):

```text
[DB] orders-db: Liquibase service with a database block - changelog db/changelog/db.changelog-master.xml in orders-db/src/main/resources, package folder db, test database yes, RD yes, QC yes
...
[DB] orders-db: package .dso-db/orders-db/orders-db-341-<stamp>.zip (folder db of orders-db/src/main/resources), validated from the package
[NODE] orders-app: Node.js from the agent PATH in frontend: npm ci && npm run build
[BUILD] Tool: gradle OS: linux
Execute Gradle Command: './gradlew' 'clean' 'build' 'bootJar' '--refresh-dependencies'
...
[COVERAGE] orders-db: Line coverage 88.2% meets the required 60%.
...
[COVERAGE] orders-app: Line coverage 82.4% meets the required 60%.
...
[DEP-SCAN] orders-db: SQL and PL/SQL have no dependency components - Nexus IQ is not required
[DEP-SCAN] Application: CertValidityMonitoring-GUI | Server: https://tools.bbh.com/IQ
```

#### Layout (c): Maven with a database module

Independent folders: the changelog in `orders-db` next to a Maven API in `api` and an npm frontend in `web`. Two services, in this order:

1. `orders-db`: `buildTool: liquibase`, `sourceDir: orders-db` and `database.changelogFile: changelog-master.yaml`. The changelog has no folder, so the zip holds the whole search root `orders-db`. No test database is configured, so tests and coverage are **not required** for it.
2. `orders-api`: `buildTool: maven`, `sourceDir: "."`. It is the service `backend-api` of the reference implementation with these changes: `excludedDirs: orders-db`; `build.maven.dir`, `tests.unitTests.maven.dir` and `tools.sonar.maven.dir` set to `api`, the folder of the `pom.xml`, and `tests.unitTests.unitTestResult` set to `api/target/surefire-reports/*.xml`; `build.node` with `enabled: true` and `dir: web`; and no AppScan key and no `influx` block. Its SAST scope is the repository without `orders-db`, so `api` and `web` are scanned with it, and the PL/SQL is scanned once, by `orders-db`.

The identity of a changeset of this layout is `changelog-master.yaml` for the master and `tables/...` for the table files: valid, but not the identities that layout (a) gives the same files. Choose the layout before the first deployment and do not change it afterwards.

Files of layout (c) (`test/fixtures/database/layout-c`):

```text
devsecops_config.yaml
Jenkinsfile
api/pom.xml
web/package.json
orders-db/changelog-master.yaml
orders-db/tables/001-create-orders.sql
orders-db/tables/002-create-order-items.sql
orders-db/code/pkg_orders.pks
orders-db/code/pkg_orders.pkb
orders-db/code/v_order_totals.vw
orders-db/code/trg_orders_bi.trg
orders-db/test/ut_pkg_orders.pks
orders-db/test/ut_pkg_orders.pkb
```

File `devsecops_config.yaml` (`test/fixtures/database/layout-c/devsecops_config.yaml`):

```yaml
projects:
  orders-db:
    appId:     "6f0c2a9e-4b1d-4e57-9a43-0d8e2f6b7c10"
    buildTool: liquibase
    sourceDir: orders-db
    javaPath:  /usr/lib/jvm/java-17-openjdk
    asoc:
      url:   "https://bbh.cloud.appscan.com"
    database:
      changelogFile: changelog-master.yaml
      package:
        groupId: com.bbh.orders
        snapshotRepository: {id: bbh-db-snapshots, url: "https://tools.bbh.com/nexus/repository/db-snapshots"}
        releaseRepository:  {id: bbh-db-releases,  url: "https://tools.bbh.com/nexus/repository/db-releases"}
      rd:
        url:           "jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD"
        credentialsId: orders-db-rd
      qc:
        url:           "jdbc:oracle:thin:@//qc-db.bbh.com:1521/ORDERS_QC"
        credentialsId: orders-db-qc

  orders-api:
    appId:        "209f44ac-dd06-4ca0-884e-d944904f8021"
    buildTool:    maven
    deployTarget: openshift
    sourceDir:    "."
    javaPath:     /usr/lib/jvm/java-17-openjdk
    excludedDirs: orders-db
    appName:      "certscanner-api"
    artifactName: "certscanner-api.jar"

    asoc:
      url:       "https://bbh.cloud.appscan.com"

    tools:
      sonar:
        projectName:      "CertScanner-Backend"
        projectKey:       "cert-scanner-backend"
        serverUrl:        "https://tools.bbh.com/sonar"
        installationName: "SonarQube"
        authToken:        "sonarqube-token"
        badgeToken:       "sqb_95b6b9cfb2fe8f3fa0e45261ece1856d3fb1ebc"
        addBadges:        true
        maven:
          dir:   api
          goals: ['sonar:sonar']
      nexusIq:
        application:        "CertValidityMonitoring-Backend"
        serverUrl:          "https://tools.bbh.com/IQ"
        credentialsId:      "nexusiqP"
        scanPatterns:       ["**/api/target/*.jar"]
        stage:              "build"
        failOnNetworkError: false

    sast:
      scanName: "CertScanner-Backend-SAST"

    sca:
      scanName: "CertScanner-Backend-SCA"

    dast:
      enabled:    true
      scanName:   "CertScanner-Backend-DAST"
      targetUrl:  "http://rdltaapps1.testbbh.com:8080/api"
      presenceId: ""

    build:
      node: {enabled: true, dir: web}
      buildPath: "target/*.jar"
      maven:
        dir: api
        javaPath: /usr/lib/jvm/java-17-openjdk
        goals:    ['clean','verify']
        flags:    ['-B','-U']
        env:
          MAVEN_OPTS: "-Xms512m -Xmx1g"

    scm:
      bitbucket:
        url:           "https://bitbucket.bbh.com/projects/TA/repos/cert-scanner"
        credentialsId: "bitbucket-http-credentials"
        authType:      basic

    goldenFix:
      enabled: true

    tests:
      unitTests:
        maven:
          dir:   api
          goals: ['test','jacoco:report']
        unitTestResult: 'api/target/surefire-reports/*.xml'
      regression:
        jobs:
          - name:       "CertScanner-Backend - regression"
            type:       local
            job:        "cert-scanner/api-regression-tests"
            timeoutMin: 60
      smoke:
        jobs:
          - name:       "CertScanner-Backend - smoke"
            type:       local
            job:        "cert-scanner/api-smoke-tests"
            timeoutMin: 15
          - name: "CertScanner-Backend - contract smoke"
            url:  "https://jenkins-a.bbh.com/job/smoke/job/cert-scanner-api-contract"
            credentialsId: "remote-jenkins-api-token"
      performance:
        jobs:
          - name:       "CertScanner-Backend - performance"
            type:       local
            job:        "cert-scanner/api-performance-tests"
            timeoutMin: 120

    deploy:
      openshift:
        rd:
          projectBuildR:      "ta-certscanner-build"
          buildConfigPath:    "openshift/buildconfig.yaml"
          dockerFilePath:     "openshift/Dockerfile"
          buildContext:       "target/docker"
          qcDockerRepoPush:   "docker-qc.tools.bbh.com/ta/certscanner-api"
          openshiftCertDir:   "/etc/pki/openshift"
          nexus:
            authfile:         "/home/jenkins/.docker/nexus-auth.json"
          projectDeploymentR: "ta-certscanner-rd"
          qcDockerRepoPull:   "docker-qc.tools.bbh.com/ta/certscanner-api"
          deployConfigPath:   "openshift/deployment.yaml"
          configPathR:        "openshift/config-rd.yaml"
          healthCheckUrl:     "/actuator/health"
          routeHostnameR:     "certscanner-api-rd.apps.ocp-rd.testbbh.com"
        qc:
          projectDeploymentR: "ta-certscanner-qc"
          qcDockerRepoPull:   "docker-qc.tools.bbh.com/ta/certscanner-api"
          deployConfigPath:   "openshift/deployment.yaml"
          configPathR:        "openshift/config-qc.yaml"
          healthCheckUrl:     "/actuator/health"
          routeHostnameR:     "certscanner-api-qc.apps.ocp-qc.testbbh.com"
```

File `orders-db/changelog-master.yaml` (`test/fixtures/database/layout-c/orders-db/changelog-master.yaml`):

```yaml
databaseChangeLog:
  - includeAll:
      path: tables/
      relativeToChangelogFile: true
      errorIfMissingOrEmpty: true
  - changeSet:
      id: pkg_orders.pks
      author: orders-team
      runOnChange: true
      changes:
        - sqlFile:
            path: code/pkg_orders.pks
            relativeToChangelogFile: true
            splitStatements: false
            stripComments: false
      rollback: []
  - changeSet:
      id: pkg_orders.pkb
      author: orders-team
      runOnChange: true
      changes:
        - sqlFile:
            path: code/pkg_orders.pkb
            relativeToChangelogFile: true
            splitStatements: false
            stripComments: false
      rollback: []
  - changeSet:
      id: v_order_totals.vw
      author: orders-team
      runOnChange: true
      changes:
        - sqlFile:
            path: code/v_order_totals.vw
            relativeToChangelogFile: true
            stripComments: false
      rollback: []
  - changeSet:
      id: trg_orders_bi.trg
      author: orders-team
      runOnChange: true
      changes:
        - sqlFile:
            path: code/trg_orders_bi.trg
            relativeToChangelogFile: true
            splitStatements: false
            stripComments: false
      rollback: []
  - changeSet:
      id: ut_pkg_orders.pks
      author: orders-team
      runOnChange: true
      context: test
      changes:
        - sqlFile:
            path: test/ut_pkg_orders.pks
            relativeToChangelogFile: true
            splitStatements: false
            stripComments: false
      rollback: []
  - changeSet:
      id: ut_pkg_orders.pkb
      author: orders-team
      runOnChange: true
      context: test
      changes:
        - sqlFile:
            path: test/ut_pkg_orders.pkb
            relativeToChangelogFile: true
            splitStatements: false
            stripComments: false
      rollback: []
```

File `Jenkinsfile` (`test/fixtures/database/layout-c/Jenkinsfile`):

```groovy
@Library('DevSecOpsJenkinsLibrary') _
devSecOpsPipeline(projectNames: 'orders-db,orders-api', agentNames: ['linux-agent'])
```

Console of `test/sandbox/golden/database-security-maven-green.txt` (shortened):

```text
[DB] orders-db: Liquibase service with a database block - changelog changelog-master.yaml in orders-db, package folder the whole search root, test database no, RD yes, QC yes
...
[DB] orders-db: package .dso-db/orders-db/orders-db-212-<stamp>.zip (the whole search root orders-db), validated from the package
[NODE] orders-api: Node.js from the agent PATH in web: npm ci && npm run build
[BUILD] Tool: maven OS: linux
...
[DB] orders-db: no test database (database.test.url) - no unit tests, coverage is not required
...
[SAST] orders-db: SQL and PL/SQL sources - the scan has no compile step
...
[NEXUS] orders-db: publishing orders-db-212-<stamp>.zip as com.bbh.orders:orders-db:212-<stamp>-SNAPSHOT to bbh-db-snapshots
```

#### Liquibase commands only, next to a Gradle service

Without a database block a multi-module repository registers the database module as a Liquibase service next to the application services, and runs them together in one pipeline with `projectNames` **(not run with these values)**:

```yaml
projects:
  db-migrations:
    appId:     "<AppScan application ID>"
    buildTool: liquibase
    sourceDir: "db"
    build:
      liquibase:
        dir:      "db"
        commands: ['updateSQL']
        flags:    ['--changelog-file=changelog/db.changelog-master.xml']
    tests:
      unitTests:
        liquibase:
          dir:      "db"
          commands: ['validate']
          flags:    ['--changelog-file=changelog/db.changelog-master.xml']
    tools:
      sonar:
        projectKey:  "myproduct-db"
        projectName: "MyProduct-DB"
        liquibase:
          dir:   "db"
          flags: ['-Dsonar.plsql.file.suffixes=.sql,.pks,.pkb']

  backend-api:
    appId:     "<AppScan application ID>"
    buildTool: gradle
    sourceDir: "backend"
    javaPath:  /usr/lib/jvm/java-21-openjdk
    build:
      gradle:
        dir:   "backend"
        tasks: ['clean', 'build']
      node:
        enabled:  true
        dir:      "frontend"
        commands: ['npm ci', 'npm run build']
```

When both services run in one pipeline:

1. **Build artifact:** builds the frontend in `frontend/` and then runs the Gradle build in `backend/` for `backend-api`, and runs `liquibase updateSQL` in `db/` for `db-migrations`.
2. **Unit tests:** runs `liquibase validate` in `db/` for `db-migrations` (skipping the coverage requirement for the database service) and runs Gradle tests in `backend/` for `backend-api`, aggregating JaCoCo XML reports from the Java modules and LCOV reports from the frontend while scoping each service's search to its own folders.
3. **Nexus IQ & GoldenFix:** scans the backend JARs, `frontend/package-lock.json`, Python `requirements.txt` / `pyproject.toml` and any JDBC drivers or Liquibase extensions matching `scanPatterns`, and raises one shared GoldenFix pull request across Maven, Gradle, version catalogs, npm and pip manifests.
4. **SAST and SonarQube:** scans `db/` as SQL / PL/SQL via AppScan IRX (after `liquibase updateSQL` of `build.liquibase`) and `sonar-scanner`, and scans `backend/` as compiled Java + frontend bundles. With `staticAnalysis.parallel` the Unit tests and Nexus IQ run while AppScan scans; SonarQube runs last in both orders.

The console of a Liquibase service without a database block shows the commands as they run:

Console of `test/sandbox/golden/database-full-liquibase-commands-rd-failure.txt` (shortened):

```text
[BUILD] Tool: liquibase OS: linux
Execute Liquibase Command: 'liquibase' '--changelog-file=db/changelog-master.xml' '--url=offline:oracle' 'updateSQL'
...
[TEST] Tool: liquibase
Execute Liquibase Command: 'liquibase' '--changelog-file=db/changelog-master.xml' 'validate'
...
[COVERAGE] Liquibase: no covered lines found, skipping coverage requirement
```

**A Liquibase step on a Gradle or Maven service.** A Gradle or Maven service can run Liquibase too: `build.liquibase` runs after its Gradle or Maven build (**Running additional Liquibase step for multi-module project**), `tests.unitTests.liquibase` after its unit tests, and `asoc.liquibase`, else `build.liquibase`, after the compile of the SAST stage. The service stays a Gradle or Maven service: a `database` block on it is ignored with a line in the console, and the library never deploys its changesets.

#### Layout (d): only SQL and PL/SQL files, without a changelog

One service, `orders-db`, with no `buildTool`, no `sourceDir` and no changelog in the repository. The library finds only SQL and PL/SQL files, so the service is `buildTool: sql`. All of them lie under `db`, so the search root is `db` (`database.searchPath: db`), and the JDK comes from the auto setup. At the start of the run the library writes `db/dso-changelog.xml` (14.4, **The changelog of buildTool sql**) and names every file in the console. The utPLSQL folders are relative to the search root (`code`, `test`), and the files under `test` are deployed to the test database only.

Files of layout (d) (`test/fixtures/database/layout-d`):

```text
devsecops_config.yaml
Jenkinsfile
README.md
db/types/t_order_line.tps
db/migrations/1_orders.sql
db/migrations/2_order_items.sql
db/migrations/10_order_status.sql
db/migrations/11_order_notes.sql
db/migrations/20_grant_app_role.sql
db/code/pkg_orders.pks
db/code/pkg_orders.pkb
db/code/v_order_totals.vw
db/code/trg_orders_bi.trg
db/test/ut_pkg_orders.pks
db/test/ut_pkg_orders.pkb
```

File `devsecops_config.yaml` (`test/fixtures/database/layout-d/devsecops_config.yaml`):

```yaml
projects:
  orders-db:
    appId:     "6f0c2a9e-4b1d-4e57-9a43-0d8e2f6b7c10"
    asoc:
      url:   "https://bbh.cloud.appscan.com"
    database:
      parameters:
        app_role: orders_app_role
      package:
        groupId: com.bbh.orders
        snapshotRepository: {id: bbh-db-snapshots, url: "https://tools.bbh.com/nexus/repository/db-snapshots"}
        releaseRepository:  {id: bbh-db-releases,  url: "https://tools.bbh.com/nexus/repository/db-releases"}
      test:
        url:           "jdbc:oracle:thin:@//dev-db.bbh.com:1521/ORDERS_CI"
        credentialsId: orders-db-test
        utplsql:
          enabled:    true
          sourcePath: code
          testPath:   test
      rd:
        url:           "jdbc:oracle:thin:@//rd-db.bbh.com:1521/ORDERS_RD"
        credentialsId: orders-db-rd
      qc:
        url:           "jdbc:oracle:thin:@tcps://qc-db.bbh.com:2484/ORDERS_QC?ssl_server_dn_match=true"
        credentialsId: orders-db-qc
        parameters:
          app_role: orders_qc_role
```

The same service with the detected keys written out; the console then says that each of them equals the detected value and can be removed:

```yaml
projects:
  orders-db:
    buildTool: sql
    buildToolAutoSetup: true
    database:
      searchPath: db
```

File `Jenkinsfile` (`test/fixtures/database/layout-d/Jenkinsfile`):

```groovy
@Library('DevSecOpsJenkinsLibrary') _
devSecOpsPipeline(projectNames: 'orders-db', agentNames: ['linux-agent'])
```

The changelog that the pipeline writes for these files. It is not a file of the repository:

File `db/dso-changelog.xml` (`test/fixtures/database/dso-changelog-layout-d.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- DSO-GENERATED-CHANGELOG: written by the DevSecOps pipeline for buildTool sql from the SQL and PL/SQL files of this folder; do not edit or commit it -->
<databaseChangeLog
    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd"
    logicalFilePath="dso-changelog.xml">

    <changeSet id="types/t_order_line.tps" author="devsecops" runOnChange="true">
        <sqlFile path="types/t_order_line.tps" relativeToChangelogFile="true" encoding="UTF-8" splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="migrations/1_orders.sql" author="devsecops">
        <sqlFile path="migrations/1_orders.sql" relativeToChangelogFile="true" encoding="UTF-8"/>
    </changeSet>
    <changeSet id="migrations/2_order_items.sql" author="devsecops">
        <sqlFile path="migrations/2_order_items.sql" relativeToChangelogFile="true" encoding="UTF-8"/>
    </changeSet>
    <changeSet id="migrations/10_order_status.sql" author="devsecops">
        <sqlFile path="migrations/10_order_status.sql" relativeToChangelogFile="true" encoding="UTF-8"/>
    </changeSet>
    <include file="migrations/11_order_notes.sql" relativeToChangelogFile="true"/>
    <changeSet id="migrations/20_grant_app_role.sql" author="devsecops">
        <sqlFile path="migrations/20_grant_app_role.sql" relativeToChangelogFile="true" encoding="UTF-8" splitStatements="false" stripComments="false"/>
    </changeSet>
    <changeSet id="code/pkg_orders.pks" author="devsecops" runOnChange="true">
        <sqlFile path="code/pkg_orders.pks" relativeToChangelogFile="true" encoding="UTF-8" splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="test/ut_pkg_orders.pks" author="devsecops" runOnChange="true" context="test">
        <sqlFile path="test/ut_pkg_orders.pks" relativeToChangelogFile="true" encoding="UTF-8" splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="code/v_order_totals.vw" author="devsecops" runOnChange="true">
        <sqlFile path="code/v_order_totals.vw" relativeToChangelogFile="true" encoding="UTF-8" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="code/pkg_orders.pkb" author="devsecops" runOnChange="true">
        <sqlFile path="code/pkg_orders.pkb" relativeToChangelogFile="true" encoding="UTF-8" splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="test/ut_pkg_orders.pkb" author="devsecops" runOnChange="true" context="test">
        <sqlFile path="test/ut_pkg_orders.pkb" relativeToChangelogFile="true" encoding="UTF-8" splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
    <changeSet id="code/trg_orders_bi.trg" author="devsecops" runOnChange="true">
        <sqlFile path="code/trg_orders_bi.trg" relativeToChangelogFile="true" encoding="UTF-8" splitStatements="false" stripComments="false"/>
        <rollback/>
    </changeSet>
</databaseChangeLog>
```

Console of `test/sandbox/golden/database-full-sql-only-green.txt` (shortened):

```text
[DB] orders-db: buildTool sql - dso-changelog.xml generated in db from 12 SQL and PL/SQL files: 4 run once, 7 run again when they change, 1 Liquibase formatted SQL file included as it is, 2 only on the test database (context test)
[DB] orders-db: 1. types/t_order_line.tps - create or replace type, runs again when it changes
[DB] orders-db: 2. migrations/1_orders.sql - create sequence, runs once
[DB] orders-db: 3. migrations/2_order_items.sql - create table, runs once
[DB] orders-db: 4. migrations/10_order_status.sql - SQL script, runs once
[DB] orders-db: 5. migrations/11_order_notes.sql - Liquibase formatted SQL, included as it is, its own changesets
[DB] orders-db: 6. migrations/20_grant_app_role.sql - PL/SQL block, runs once
[DB] orders-db: 7. code/pkg_orders.pks - create or replace package, runs again when it changes
[DB] orders-db: 8. test/ut_pkg_orders.pks - create or replace package, runs again when it changes, test database only
[DB] orders-db: 9. code/v_order_totals.vw - create or replace view, runs again when it changes
[DB] orders-db: 10. code/pkg_orders.pkb - create or replace package body, runs again when it changes
[DB] orders-db: 11. test/ut_pkg_orders.pkb - create or replace package body, runs again when it changes, test database only
[DB] orders-db: 12. code/trg_orders_bi.trg - create or replace trigger, runs again when it changes
[DB] orders-db: SQL and PL/SQL service (buildTool sql) - changelog dso-changelog.xml generated by the pipeline in db, package folder the whole search root, test database yes, RD yes, QC yes
...
[DB] orders-db: the changelog is valid (offline, Oracle 19.0)
[DB] orders-db: 12 changesets in the changelog
[DB] orders-db: review SQL .dso-db/orders-db/orders-db-341-<stamp>-update.sql: 10 changesets for contexts !test
[DB] orders-db: package .dso-db/orders-db/orders-db-341-<stamp>.zip (the whole search root db), validated from the package
...
[DB] orders-db test: update for contexts test: run 12, previously run 0, filtered out 0
```

#### Rules for every layout

- **The search root stays inside the service folder:** `database.searchPath` and `sourceDir` are relative paths without `..`.
- **One folder per SAST scope.** Every source folder belongs to the SAST scan of exactly one service. `excludedDirs` of the application names the database folder. AppScan applies it as a substring test, so `excludedDirs: db` would also leave out `src/main/java/com/bbh/orders/db/`: use a distinctive, hyphenated folder name such as `orders-db`, which no Java package can have. `includedDirs` matches folder names anywhere in the tree, not paths.
- **The identity of a changeset** is its id, its author and the changelog path relative to the search root, or `logicalFilePath`. Fix the search root, the path and the format (XML or YAML) before the first deployment; a later move, or a `logicalFilePath` added later, makes Liquibase run every changeset again.
- **Gradle and Maven plugins of Liquibase are never run by the pipeline.** A folder of changelogs that the pipeline deploys is its own Liquibase service, also when it is a module of a Gradle or Maven build.
- **Spring Boot.** An application with `liquibase-core` on its classpath runs the changelog itself when it starts, with its own user, and competes with the pipeline in RD and QC: set `spring.liquibase.enabled=false`, or keep the changelog out of the application jar.

### 14.4 Writing a changelog for PL/SQL

These rules apply to a changelog that a database block deploys. They follow from the way Liquibase sends a file to Oracle: it splits the text at every `;` unless `splitStatements` is false, it removes the comments when `stripComments` is true, and it sends every line as SQL, also a SQL\*Plus command.

1. **One PL/SQL unit per file**, deployed by `sqlFile` with `splitStatements="false"` and `stripComments="false"`, with a `/` on a line of its own at the end. A TOAD-style `.pck` with specification and body works with `endDelimiter="\n/"`.
2. **Code objects are `runOnChange="true"` and `CREATE OR REPLACE`**: packages, views, triggers and types. A change of whitespace only does not run them again. Oracle replaces a type that other types use only with `CREATE OR REPLACE TYPE ... FORCE`, and never one that a table uses (ORA-02303): change such a type with `ALTER TYPE ... CASCADE` in a new changeset, and leave its `runOnChange` file as it is.
3. **Tables in formatted SQL, one DDL per changeset, each with `--rollback`.** Oracle commits each DDL, so a changeset with two statements that fails halfway is left half applied. **Never edit an applied changeset**: add a new one.
4. **Formatted SQL that holds a PL/SQL unit** needs `splitStatements:false` and `stripComments:false` on its `--changeset` line.
5. **Views and plain SQL keep the default splitting.** With `splitStatements="false"` the statement would reach Oracle with its `;`, which Oracle refuses for a view.
6. **No client commands.** Liquibase sends the commands of SQL\*Plus (`SET`, `SHOW ERRORS`, `@file`, `PROMPT`, `EXIT`) to the database as SQL.
7. **`includeAll` deploys only `.sql`, `.xml`, `.yaml`, `.yml` and `.json` files**, so every `.pks`, `.pkb`, `.vw`, `.trg` or `.prc` file needs a `sqlFile` changeset.
8. **Test packages carry `context="test"`.** The test database deploys the context `test`; RD, QC and the review SQL use `!test`, and QC must not select `test`.
9. **A master changelog that includes other files is XML, YAML or JSON.** Liquibase Community has no `include` inside formatted SQL, so a formatted SQL master holds all its changesets itself.
10. **`<rollback/>` on code changesets** keeps `future-rollback-sql` working when `database.requireRollback` is true; such a rollback leaves the new code in place.
11. **No password and no agent code in a changeset.** Values that differ between databases are `database.parameters`, written `${app_role}` in the changelog.
12. **`runAlways` changesets** (grants, synonyms) run on every deployment.

**Static checks.** In the full and security pipelines the Build artifact stage reads every file under the package folder before it packages them. Each finding is one line of `.dso-db/<service>/static-checks.txt`, which the stage archives; the console shows the first 20 as `[DB] <service>: static check: <finding>`. The PL/SQL files are those with the extensions `.pks`, `.pkb`, `.pls`, `.plb`, `.pck`, `.prc`, `.fnc`, `.trg`, `.tps`, `.tpb`, `.typ` and `.bdy`.

| Finding | Rule | Result |
|---|---|---|
| a SQL\*Plus command at the start of a line of a PL/SQL, `.sql` or `.vw` file, outside a `--` comment: `SET DEFINE`, `SET ECHO`, `SET SERVEROUTPUT` and the other `SET` options of SQL\*Plus, `SHOW ERRORS`, `PROMPT`, `SPOOL`, `WHENEVER SQLERROR`, `EXIT SUCCESS`, `DEFINE name = value`, `EXEC procedure`, `@file` and `@@file` | 6 | warning |
| a `/` line before the end of a PL/SQL file whose `sqlFile` has no `endDelimiter` | 1 | warning |
| a `/` on the line of `END;` at the end of a PL/SQL file | 1 | warning |
| a `sqlFile` of a PL/SQL file without `splitStatements="false"` and without `endDelimiter` | 1 | warning |
| a `sqlFile` of a PL/SQL file with `stripComments="true"` | 1 | warning |
| a `sqlFile` of a view or plain SQL file with `splitStatements="false"` whose statement ends with `;` | 5 | warning |
| a `--changeset` of formatted SQL that holds a PL/SQL unit without `splitStatements:false` (or `endDelimiter:`), or without `stripComments:false` | 4 | warning |
| a PL/SQL or `.vw` file that no `sqlFile` of a changelog deploys | 7 | warning |
| `IDENTIFIED BY` followed by a password: a name, a quoted name, a `${...}` or the end of the line (not `IDENTIFIED BY VALUES`, nor a bind, a variable or a concatenation in dynamic SQL) | 11 | stops the build |
| `executeCommand` or `customChange` in a changelog: it runs code on the Jenkins agent | 11 | stops the build |
| `${...}` whose name holds `password`, `passwd`, `pwd`, `secret`, `token`, `credential`, `key` or `user`, or starts with `LIQUIBASE_`, `DSO_` or `JENKINS` | 11 | stops the build |

A warning turns the stage orange (**unstable**), and the release gate then blocks the Nexus release and the QC deployment until the finding is fixed. `database.checks.ignoreFiles` lists files or folders, relative to the search root, that the warnings skip; the findings that stop the build are never skipped.

Console of `test/sandbox/golden/database-security-static-checks-orange.txt` (shortened):

```text
[DB] orders-db: static check: db/code/pkg_bad.pkb line 1: SQL*Plus command "SHOW ERRORS" - Liquibase sends it to the database as SQL; remove it
[DB] orders-db: static check: db/code/pkg_bad.pkb line 9: "/" on the line of END; - put the "/" on a line of its own
[DB] orders-db: static check: db/tables/003-pkg-audit.sql line 3: changeset 003-pkg-audit holds a PL/SQL unit but no splitStatements:false - Liquibase splits it at every ";"
[DB] orders-db: static check: db/tables/003-pkg-audit.sql line 3: changeset 003-pkg-audit holds a PL/SQL unit without stripComments:false - its comments and utPLSQL annotations are removed
[DB] orders-db: static check: db/code/orders_purge.prc is in no sqlFile of the changelog - includeAll does not deploy .prc files, so it is never deployed
[DB] orders-db: static check: db/code/pkg_bad.pkb is in no sqlFile of the changelog - includeAll does not deploy .pkb files, so it is never deployed
[unstable] [POLICY] 6 static check finding(s) in the SQL and PL/SQL sources (.dso-db/orders-db/static-checks.txt): fix them, or list the file in database.checks.ignoreFiles The Nexus release and the QC deployment stay blocked until this is fixed.
```

#### The changelog of buildTool sql

A service with `buildTool: sql` has no changelog of its own. At the start of a full or security run, and before a package is built, the library reads the SQL and PL/SQL files of the search root and writes the changelog `dso-changelog.xml` into the search root of the workspace. Every later step, the offline validation, the static checks, the review SQL, the zip and the deployments, uses that file as it uses a changelog of the team. The console names every file with its kind and when it runs, and the zip holds the changelog next to the files.

**Which files.** Every `.sql`, `.pks`, `.pkb`, `.pls`, `.plb`, `.pck`, `.prc`, `.fnc`, `.trg`, `.tps`, `.tpb`, `.typ`, `.bdy` and `.vw` file under the search root, with the extension in lower case. Left out are folders whose name starts with a dot, the files and folders of `database.checks.ignoreFiles`, and empty files, which the console names. A search root without any such file stops the build.

**How each file is deployed.** The library reads the first `CREATE` statement of a file:

| File | Changeset | Runs |
|---|---|---|
| its first line is `--liquibase formatted sql` | none: the file is included as it is | as its own changesets say |
| `CREATE OR REPLACE` of a type, synonym, package, view, function, procedure, type body, package body or trigger | one changeset with the path as its id, author `devsecops`, `runOnChange="true"` and an empty rollback | at the first deployment, and again in every database whenever the file changes |
| everything else: `CREATE TABLE`, `CREATE SEQUENCE`, `CREATE INDEX`, `ALTER`, `INSERT`, grants, an anonymous block, a `CREATE` without `OR REPLACE` | one changeset with the path as its id and author `devsecops` | once in each database |

**Never change a run-once file after it has been deployed:** Liquibase stops with a checksum error. Add a new file for the next change, as with any migration. Change the `CREATE OR REPLACE` files freely.

**The order.**

1. The `CREATE OR REPLACE TYPE` specifications, because tables may use them.
2. The run-once files and the formatted SQL files, in the natural order of their paths: `2_items.sql` comes before `10_status.sql`, and the folder names count, so `data/1.sql` comes before `tables/1.sql`. Name the folders and files so that this order is the order in which the changes must run, for example with a number at the start.
3. The other `CREATE OR REPLACE` files by kind: synonyms, package specifications, views, functions, procedures, type bodies, package bodies, triggers; within a kind by path.

The library does not analyse which object needs which. After every deployment the compile check recompiles the invalid objects (`database.compileCheck.recompile`), so code that was created before the code it calls becomes valid, and an object that stays invalid stops the deployment (14.8).

**PL/SQL.** A file with a PL/SQL unit or an anonymous block is sent as one statement (`splitStatements="false"`) with its comments (`stripComments="false"`); a file with several units, each ended by a line with only `/`, is split at those lines (`endDelimiter="\n/"`); a view keeps its comments; every other file is split at each `;`. The rules of this section for `/`, SQL\*Plus commands and passwords apply, and the static checks report what breaks them. Keep one object in a file: the kind of a file is the kind of its first `CREATE`.

**The utPLSQL tests.** A file under `database.test.utplsql.testPath` gets `context="test"`: it is deployed to the test database only, as in the changelog of layout (a). Without that key the test packages would be deployed to RD and QC.

**The identity of a changeset** is `dso-changelog.xml`, the path of the file relative to the search root, and `devsecops`. Moving or renaming a run-once file after its deployment therefore makes Liquibase run it again, and so does a change of `sourceDir` or `database.searchPath`.

**The file `dso-changelog.xml`** is written on every run and carries the mark `DSO-GENERATED-CHANGELOG` in its second line. Do not commit it; a workspace that still holds the file of an earlier build is fine. A file of that name without the mark stops the build, so that the library never overwrites a changelog of the team.

**The search root must be a folder of its own,** because the zip holds the whole search root: a search root that is the repository root stops the build. With the auto-resolve on, the library sets `database.searchPath` to the one top folder that holds all of the SQL and PL/SQL files; when the files lie in several top folders, or at the root, set `sourceDir` or `database.searchPath`.

**A service that has a changelog after all** runs as `buildTool: liquibase`, with a warning in the console that names the keys to set: when `database.changelogFile`, `build.liquibase` or `tests.unitTests.liquibase` is set for it, or when its search root holds one of the Liquibase changelogs of 14.1, which then becomes its `database.changelogFile`. A configuration written for Liquibase therefore keeps working when its `buildTool` is changed to `sql` by mistake.

**From a generated changelog to one of the team.** Copy `dso-changelog.xml` from the zip into the search root, remove the line with the mark, commit it, and set `buildTool: liquibase` and `database.changelogFile: dso-changelog.xml`. Its `logicalFilePath` keeps the identity of every changeset, so no database runs anything again.

### 14.5 Settings

Every key of the first two tables is a key of the service, under `projects.<service>` of `devsecops_config.yaml`. In the tables, `<target>` stands for `test`, `rd` or `qc`.

**The Liquibase commands.** They apply to every Liquibase service, with and without a database block:

| Key | Default | Meaning |
|---|---|---|
| `buildTool` | detected (14.1) | `liquibase`, which a database block with a changelog needs set; or `sql`, also written `plsql`, `pl/sql` or `pl-sql`, for SQL and PL/SQL files without a changelog, which is set or detected |
| `sourceDir` | `.` | the folder of the service: the SAST scope, and the base of the search root of a block |
| `build.liquibase.commands` | `updateSQL` from the auto-resolve, `validate` with `autoresolve parameters: false`; with a block none, unless `build.liquibase.flags` is set | the Liquibase commands of the Build artifact stage, `liquibase <flags> <commands>`; `tasks` is a synonym |
| `build.liquibase.flags` | – | the arguments before the commands, for example `--changelog-file=db/changelog-master.xml` and `--url=offline:oracle` |
| `build.liquibase.dir` | the workspace | the folder the command runs in |
| `build.liquibase.env` | – | environment variables of the command, as a map |
| `build.liquibase.label` | `Liquibase: <commands>` | the name of the step in Jenkins |
| `tests.unitTests.liquibase.commands` | `validate` from the auto-resolve; with a block or with `autoresolve parameters: false`, `validate` when only `flags` are set, else none | the Liquibase command of the Unit tests stage; a failure turns the stage orange |
| `tests.unitTests.liquibase.flags`, `tests.unitTests.liquibase.dir`, `tests.unitTests.liquibase.env` | – | as for `build.liquibase` |
| `asoc.liquibase.commands`, `asoc.liquibase.flags`, `asoc.liquibase.dir`, `asoc.liquibase.env` | the map of `build.liquibase` | the Liquibase command that runs before AppScan prepares the IRX |
| `asoc.doCompile` | `true` | `false`: no Liquibase command before the IRX |
| `tools.sonar.liquibase.dir` | – | the folder `sonar-scanner` runs in; with a block, `.` or a relative path inside the repository that holds the package folder and the utPLSQL test folder, from which the block gives its folders and reports (14.10) |
| `tools.sonar.liquibase.sources` | `tools.sonar.sources`; else, with a block, the package folder under the search root, from `tools.sonar.liquibase.dir` when it is set; else `.` when `tools.sonar.liquibase.dir` is set; else `build.liquibase.dir`, else `sourceDir` | `-Dsonar.sources`, read from `tools.sonar.liquibase.dir` when it is set |
| `tools.sonar.liquibase.inclusions`, `tools.sonar.liquibase.exclusions` | – | `-Dsonar.inclusions` and `-Dsonar.exclusions` |
| `tools.sonar.liquibase.flags` | – | more `sonar-scanner` arguments, for example `-Dsonar.plsql.file.suffixes=sql,pks,pkb`; an argument of these keys wins over the same argument of the block |
| `tools.sonar.liquibase.env` | – | environment variables of the scanner |
| `javaPath` | – | the JDK, exported as `JAVA_HOME`; optional without a block |
| `buildToolAutoSetup` | `false` | `true`: the library looks for a JDK on the agent; with a block, `javaPath` or `buildToolAutoSetup: true` is required |

**The database block.** `database.changelogFile` switches it on:

| Key | Default | Meaning |
|---|---|---|
| `database.changelogFile` | – ; with `buildTool: sql` always `dso-changelog.xml`, written by the library | the master changelog, relative to the search root; `.xml`, `.yaml`, `.yml`, `.json` or `.sql`. Do not set it with `buildTool: sql`: the service would run as `buildTool: liquibase` |
| `database.searchPath` | `.`; with `buildTool: sql` and the auto-resolve on, the one top folder of the SQL and PL/SQL files | the search root, relative to `sourceDir` |
| `database.oracleVersion` | `19.0` | the Oracle version of the offline validation and the review SQL |
| `database.defaultSchema` | – | `--default-schema-name` of every online step: the schema of the objects, when the deploy user is not their owner |
| `database.liquibaseSchema` | – | `--liquibase-schema-name`: the schema of `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` |
| `database.contexts` | `!test` | the context filter of RD, QC and the review SQL |
| `database.labels` | – | the label filter of the test database, RD, QC and the review SQL |
| `database.parameters` | – | changelog parameters, at most 50, written `${name}` in the changelog; a name that holds `password`, `passwd`, `pwd`, `secret`, `token`, `credential`, `key` or `user`, or starts with `liquibase_`, `dso_` or `jenkins`, is refused, as by the static checks of 14.4: use a name such as `app_role` or `app_schema` |
| `database.requireRollback` | `false` | `true`: the Build artifact stage writes the rollback SQL, and a changeset without a rollback turns it orange |
| `database.tag` | `true` | tag RD and QC with `<artifactId>-<version>` when a changeset ran |
| `database.lockWaitMinutes` | `5` | how long Liquibase waits for its lock, from 0 to 60 |
| `database.compileCheck.enabled` | `true` | the compile check after each deployment (14.8) |
| `database.compileCheck.recompile` | `true` | recompile the invalid objects of the schema before the check |
| `database.compileCheck.ignoreObjects` | – | object names that never count as invalid |
| `database.checks.ignoreFiles` | – | files or folders, relative to the search root, that the static check warnings skip |
| `database.package.groupId` | – | the Maven group of the zip; required when the pipeline delivers to Nexus |
| `database.package.artifactId` | the service name | the Maven artifact of the zip |
| `database.package.nexusDelivery` | `true` | `false`: no snapshot or release delivery; the extended pipeline then packages its own checkout |
| `database.package.mvnPath` | `build.maven.mvnPath`, else `mvn` of the agent | the Maven of the upload and the fetch |
| `database.package.snapshotRepository.id`, `database.package.snapshotRepository.url` | the library defaults | the Nexus repository of the snapshots |
| `database.package.releaseRepository.id`, `database.package.releaseRepository.url` | the library defaults | the Nexus repository of the releases |
| `database.<target>.url` | – | the Oracle thin URL of the target, without a user or a password; without it the target is **not required** |
| `database.<target>.credentialsId` | – | the Jenkins **Username with password** credential, required with the URL |
| `database.<target>.contexts` | test: `test`; RD and QC: `database.contexts` | the context filter of the target |
| `database.<target>.labels`, `database.<target>.defaultSchema`, `database.<target>.liquibaseSchema` | the value of the service | the label filter and the schemas of the target |
| `database.<target>.parameters` | – | changelog parameters of the target, merged over `database.parameters` |
| `database.<target>.timeoutMinutes` | `30` | the timeout of each online step of the target, from 1 to 240 |
| `database.test.reset` | `false` | `true`: empty the test schema before the deployment: `drop-all`, then a drop of the packages, procedures, functions, triggers, types and synonyms, which `drop-all` of Liquibase Community leaves |
| `database.test.utplsql.enabled` | `false` | run utPLSQL on the test database (14.9) |
| `database.test.utplsql.paths` | the suites of `database.test.utplsql.owner` | the utPLSQL paths, such as `orders_owner:ut_orders` |
| `database.test.utplsql.owner` | `database.test.defaultSchema`, else `database.defaultSchema`, else the login schema | the schema whose suites run |
| `database.test.utplsql.sourcePath`, `database.test.utplsql.testPath` | – | the folders of the packages and of the test packages, relative to the search root, which map the reports to the files |
| `database.test.utplsql.timeoutMinutes` | `30` | the timeout of the utPLSQL run (`-t`), from 1 to 240; the Jenkins step around it ends 5 minutes after the larger of this and `database.test.timeoutMinutes`, so that utPLSQL ends first, with its reports |

There is no production target: `database.prod`, `database.production`, `database.prd` and `database.live` are refused. With a block, `deployTarget` and `deploy` are not used, and a `database.allowedTargets` of the service is ignored with a line in the console.

**The library defaults** (`defaults.*`), which the DevSecOps team fills in `resources/com/bbh/devsecops/defaults.yaml` of the library release (chapter 4):

| Key | Default | Meaning |
|---|---|---|
| `defaults.tools.liquibase.home` | – | the Liquibase folder on the agent, which holds the `liquibase` launcher |
| `defaults.tools.liquibase.url`, `defaults.tools.liquibase.sha256` | – | the Nexus download of a `.tar.gz`, `.tgz` or `.zip`, and its SHA-256 |
| `defaults.tools.liquibase.javaVersion` | `17` | the Java version, from 17 to 25, that `buildToolAutoSetup` looks for with a block |
| `defaults.tools.oracleJdbc.path` | – | the Oracle driver jar on the agent |
| `defaults.tools.oracleJdbc.url`, `defaults.tools.oracleJdbc.sha256` | – | the Nexus download of the driver jar |
| `defaults.tools.utplsql.home` | – | the utPLSQL-cli folder on the agent, which holds `bin/utplsql` |
| `defaults.tools.utplsql.url`, `defaults.tools.utplsql.sha256` | – | the Nexus download of `utPLSQL-cli.zip` |
| `defaults.database.allowedTargets` | empty | the databases the library may deploy to; a library default only |
| `defaults.database.package.snapshotRepository.id`, `defaults.database.package.snapshotRepository.url` | – | the Nexus repository of the database snapshots |
| `defaults.database.package.releaseRepository.id`, `defaults.database.package.releaseRepository.url` | – | the Nexus repository of the database releases |

A local folder wins over the download, and the download over the `PATH` of the agent; the driver has no `PATH` fallback. A service may set its own `tools.liquibase`, `tools.oracleJdbc` and `tools.utplsql`, which the settings check then checks as well. These three are the tools of the block only: the commands of `build.liquibase` always run the `liquibase` of the `PATH`, and `tools.sonar.liquibase` is the SonarQube scan of every Liquibase service.

**URL forms.** `jdbc:oracle:thin:@//host:1521/SERVICE` with the EZConnect Plus parameters `ssl_server_dn_match`, `ssl_server_cert_dn`, `connect_timeout`, `transport_connect_timeout`, `retry_count` and `retry_delay`; `@tcps://host:2484/SERVICE`; several hosts, `@host1:1521,host2:1521/SERVICE`; `@host:1521:SID`; and `@(DESCRIPTION=...)`. A URL with a user or a password, a TNS alias and an IPv6 address are refused. The test database must not have the URL of RD or QC.

**The allowed databases.** Each entry of `defaults.database.allowedTargets` is `host` or `host/SERVICE`; `*` matches any run of letters, digits, dots and hyphens, and case does not matter. A URL is allowed when every host of it matches an entry that names no service or names the service of the URL. An empty list allows no deployment.

| Entries | URL | Allowed |
|---|---|---|
| `rd-db*.bbh.com` | `jdbc:oracle:thin:@//RD-DB1.bbh.com:1521/ORDERS_RD` | yes |
| `qc-db.bbh.com/ORDERS_QC` | `jdbc:oracle:thin:@//qc-db.bbh.com:1521/ORDERS_RD` | no: another service |
| `rd-db1.bbh.com` | `jdbc:oracle:thin:@rd-db1.bbh.com:1521,rd-db2.bbh.com:1521/ORDERS` | no: `rd-db2` is not allowed |
| `*.rd.bbh.com` | `jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=a.rd.bbh.com)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=ORDERS)))` | yes |
| none | any | no |

The list is a guard against mistakes, not an access control: a listed name that points to production is not detected. The control is that no Jenkins folder of a product holds production credentials.

**In devsecops_config.yaml and the library defaults:**

1. The DevSecOps team fills the database part of the library defaults (`resources/com/bbh/devsecops/defaults.yaml`): `defaults.tools.liquibase` (the Liquibase folder or download and the Java version), `defaults.tools.oracleJdbc`, `defaults.tools.utplsql`, `defaults.database.allowedTargets` and the Nexus repositories of the database packages (`defaults.database.package`).
2. The Jenkins administrator adds one **Username with password** credential per database to the Jenkins folder of the product.
3. The product team adds the service under `projects:` of `devsecops_config.yaml` with `buildTool: liquibase` and `sourceDir`. In a repository with an application, it lists the Liquibase service first.
4. For a database block, it sets no Liquibase commands under `build.liquibase`, and sets `database.changelogFile`, and `database.searchPath` for a module of a Gradle or Maven build. `deployTarget` and `deploy` are then not used.
5. It sets `database.<target>.url` and `database.<target>.credentialsId` of each database the pipelines deploy to: `test`, the test database for utPLSQL, `rd`, the lower test region, and `qc`, the higher test region; `database.test.utplsql.enabled: true` with `database.test.utplsql.sourcePath` and `database.test.utplsql.testPath`; and `database.package.groupId`.
6. It sets the AppScan application ID (`appId`), and for the security and full pipelines the SonarQube project key (`tools.sonar.projectKey`), as for every service.
7. The Jenkinsfile of the repository names the services in `projectNames`, as in 14.3.

### 14.6 What each stage does

Without a database block every stage runs as for any service, with the Liquibase commands of 14.5 in place of a Gradle or Maven build; the table of 14.1 lists the differences. This section describes a service with a block.

**Monitor source changes.** The settings of every service with a block are checked; each problem is one line `[DB] <service>: <problem>`, and the build stops after the list. In the full and security pipelines the changelog, and the folders of `database.test.utplsql.sourcePath` and `database.test.utplsql.testPath` when they are set, must exist in the checkout. A Windows agent stops the build. A database service that comes after a Gradle, Maven or Flutter service of the run is named in the console.

**Build artifact.** The command of `build.liquibase` runs first when it is set. In the full and security pipelines the block then validates the changelog offline, checks the Liquibase version, runs the static checks (14.4), counts the changesets with `update-sql`, writes the review SQL (`update-sql` with the filters and the parameters of RD), writes the rollback SQL when `requireRollback` is true, stages the package folder with the manifest, zips it and validates the zip offline. The stage archives the zip, the review SQL, the rollback SQL and `static-checks.txt`. In the SAST and Nexus IQ GoldenFix pipelines nothing is packaged, and the service is **not required** unless `build.liquibase` ran.

Console of `test/sandbox/golden/database-full-plsql-only-green.txt` (shortened):

```text
[DB] orders-db: Liquibase service with a database block - changelog db/changelog-master.xml in ., package folder db, test database yes, RD yes, QC yes
...
[DB] orders-db: the changelog is valid (offline, Oracle 19.0)
[DB] orders-db: 10 changesets in the changelog
[DB] orders-db: review SQL .dso-db/orders-db/orders-db-341-<stamp>-update.sql: 8 changesets for contexts !test
[DB] orders-db: package .dso-db/orders-db/orders-db-341-<stamp>.zip (folder db of .), validated from the package
...
[DB] orders-db test: update for contexts test: run 10, previously run 0, filtered out 0
[DB] orders-db test: compile check: 0 invalid objects, 0 compile errors
[DB] orders-db: utPLSQL: 214 tests, 0 failed, 3 skipped
[COVERAGE] orders-db: utPLSQL Cobertura .dso-db/orders-db/utplsql-cobertura.xml: 150 of 170 lines
```

**Unit tests.** The command of `tests.unitTests.liquibase` runs first when it is set. With `database.test.url` the block deploys the package of the Build artifact stage to the test database with the context `test`, reports the changesets of the test database that the changelog does not have, and runs utPLSQL when `database.test.utplsql.enabled` is true (14.9). Without a test database the console says **no test database (database.test.url) - no unit tests, coverage is not required**; with utPLSQL off it says **utPLSQL is off (database.test.utplsql.enabled) - coverage is not required**.

**Dependencies scan (Nexus IQ).** **Not required**: SQL and PL/SQL have no dependency components. A service that sets `tools.nexusIq.scanPatterns` is scanned as any other. The Nexus IQ GoldenFix pipeline refuses a run of database services only.

**SAST - HCL AppScan.** The command of `asoc.liquibase`, else of `build.liquibase`, runs before the IRX unless `asoc.doCompile` is false. The IRX has no compile step (`-sco`) and holds the sources of `sourceDir` with `includedDirs` and `excludedDirs`; the console lists the SQL and PL/SQL files with extensions AppScan does not scan (`.prc .fnc .trg .vw .tps .tpb .typ .bdy`). Every IRX of a run leaves out `.dso-db`.

**SCA (SonarQube)** runs last, so the utPLSQL reports are there (14.10).

**Nexus delivery.** The snapshot stage publishes the zip; the release stage publishes the same file once the release gate allows it (14.7). With `database.package.nexusDelivery: false` both are **not required**.

**Download artifacts from Nexus Repo** (extended pipeline). **Not required**: the deployment fetches the delivered zip itself, with the version that the snapshot stage wrote to `delivery` of `pipeline-config.yaml` and the extended pipeline copied from its security job.

**Lower test region and Higher test environment deployment.** The delivered zip is deployed to RD, and to QC with `DEPLOY_HIGHER_ENV` and an allowed release gate (14.8). A target without a URL is **not required**.

**Regression, smoke and performance tests** run the jobs of `tests.<suite>`; without jobs they are **not required**, unless `tests.<suite>.required` is true. **DAST** is **not required** unless `dast.enabled: true`.

### 14.7 Version, package and Nexus

- **Version.** `<BUILD_NUMBER>-<yyyyMMdd-HHmmss UTC>` of the build that made the zip, for example `341-20261031-101500`. The zip is `<artifactId>-<version>.zip`.
- **Content.** The package folder (the first folder of the changelog path under the search root, or the whole search root) and the manifest `dso-deploy.properties`. Every `liquibase*.properties` file is left out, with a line in the console: the pipeline passes every setting itself, and such a file may hold a password.
- **Offline validation.** The zip is validated offline from the zip itself, so a file the changelog needs outside the package folder stops the build.
- **Coordinates.** `<groupId>:<artifactId>:<version>-SNAPSHOT:zip` in the snapshot repository and `<groupId>:<artifactId>:<version>:zip` in the release repository, published with `mvn deploy:deploy-file` from the work folder of the step, so no `pom.xml` of the repository takes part. The Maven settings of the agent hold the Nexus login of the repository `id`.
- **Delivery.** The security pipeline writes the version into the run state (`projects.<service>.delivery` of `pipeline-config.yaml`); the extended pipeline fetches exactly that snapshot from Nexus with `mvn dependency:get`, validates it offline and deploys those bytes, and its release publishes them with the version without `-SNAPSHOT`. Without a delivered version the RD stage stops. The full pipeline deploys the zip of its own Build artifact stage.

File `dso-deploy.properties` (`test/fixtures/database/dso-deploy.properties`):

```text
# dso-deploy.properties - written by the DevSecOps library for orders-db 341-20261031-101500 (Liquibase 4.33.0)
# It holds no URL, user or password. Run Liquibase from the folder that holds the zip, for example:
#   liquibase --defaults-file=dso-deploy.properties --search-path=orders-db-341-20261031-101500.zip --url=<URL> --username=<USER> --password=<PASSWORD> status --verbose
liquibase.command.changelogFile=db/changelog-master.xml
liquibase.command.contextFilter=!test
liquibase.command.labelFilter=release-1
liquibase.command.defaultSchemaName=ORDERS_OWNER
liquibase.liquibaseSchemaName=ORDERS_OWNER
# changelog parameters to set for each database (the values of RD and QC are not production values):
#   parameter.app_role
```

The filters and the schemas of the manifest are those of QC, because production follows QC.

### 14.8 Deployments to RD and QC

Each deployment runs, in this order: `status`; a baseline of the invalid objects; `update` with the context and label filters of the target; the compile check; `status` again; and `tag <artifactId>-<version>` when a changeset ran (a tag with nothing run would replace the previous tag). The changesets that ran are archived as `<artifactId>-<version>-<target>-changes.txt`. The library never runs `rollback`, `changelog-sync` or `clear-checksums`.

Console of `test/sandbox/golden/database-full-plsql-only-green.txt` (shortened):

```text
[NEXUS] orders-db: publishing orders-db-341-<stamp>.zip as com.bbh.orders:orders-db:341-<stamp>-SNAPSHOT to bbh-db-snapshots
...
[DB] orders-db rd: 8 changesets pending before the update
[DB] orders-db rd: update for contexts !test: run 8, previously run 0, filtered out 2
[DB] orders-db rd: compile check: 0 invalid objects, 0 compile errors
[DB] orders-db rd: up to date after the update
[DB] orders-db rd: tagged orders-db-341-<stamp>
```

**The compile check.** Before the update the library records the invalid objects of the schema with the time of their last change. After the update it recompiles the schema when `compileCheck.recompile` is true (`DBMS_UTILITY.COMPILE_SCHEMA` with `compile_all => FALSE` and `reuse_settings => TRUE`), and then reads the invalid objects and the compile errors. Only an object that is invalid after the update and was not invalid before it, or was changed by it, fails the stage; objects that were invalid before are listed and not counted. `compileCheck.ignoreObjects` names objects that never count. Without a baseline every invalid object counts. With `defaultSchema` the queries read `all_objects` and `all_errors` of that owner instead of `user_objects` and `user_errors`.

File `<DB_TMP>/rd-compile-check.sql` (`test/fixtures/database/compile-check-default.sql`):

```text
SELECT object_type || ' ' || object_name || ' ' || TO_CHAR(last_ddl_time, 'YYYYMMDDHH24MISS') AS INVALID_UPDATED FROM user_objects WHERE status = 'INVALID' ORDER BY 1
/
BEGIN
  DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE, reuse_settings => TRUE);
END;
/
SELECT object_type || ' ' || object_name AS INVALID_OBJECT FROM user_objects WHERE status = 'INVALID' ORDER BY 1
/
SELECT type || ' ' || name || ' line ' || line || ': ' || REPLACE(text, CHR(10), ' ') AS COMPILE_ERROR FROM user_errors WHERE attribute = 'ERROR' ORDER BY type, name, sequence
/
```

A deployment that leaves a new invalid object fails the stage and names the object and its error:

Console of `test/sandbox/golden/database-full-rd-compile-failure.txt` (shortened):

```text
[DB] orders-db rd: 8 changesets pending before the update
[DB] orders-db rd: update for contexts !test: run 8, previously run 0, filtered out 2
[DB] orders-db rd: compile check: 1 invalid objects, 1 compile errors (1 invalid before the update, not counted)
[DB] orders-db rd: invalid before this deployment and not changed by it: VIEW V_LEGACY_REPORT
[error] [DB] orders-db rd: the deployment left invalid objects: PACKAGE BODY PKG_ORDERS | PACKAGE BODY PKG_ORDERS line 12: PLS-00201: identifier 'ORDER_ITEMZ' must be declared - fix the source and deploy again; the library does not roll back
```

The changesets that ran stay applied: fix the source and run the stage again.

**Privileges.** Log in as the owner of the schema, or, recommended, as a proxy user after `ALTER USER orders_owner GRANT CONNECT THROUGH deployer`, with the credential user `deployer[ORDERS_OWNER]`: the deployment then works as the owner and needs no `defaultSchema`. A deploy user that is not the owner needs the `ANY` privileges of every change the changelog makes in the schema of the owner, such as `CREATE ANY TABLE`, `ALTER ANY TABLE`, `CREATE ANY INDEX`, `CREATE ANY SEQUENCE`, `CREATE ANY VIEW`, `CREATE ANY PROCEDURE`, `CREATE ANY TRIGGER` and `CREATE ANY TYPE`, which reach every schema of the database: that is why the owner or a proxy user is recommended. It also needs `ALTER ANY PROCEDURE`, `ALTER ANY TRIGGER` and `ALTER ANY TYPE` and read access to the rows of the schema in `ALL_OBJECTS` and `ALL_ERRORS` for the compile check, with `defaultSchema` and `liquibaseSchema` set to the owner. Liquibase needs `CREATE TABLE` in the Liquibase schema for `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK`.

**Packages in use.** A package that sessions hold cannot be replaced (ORA-04021), and sessions see ORA-04068 afterwards. Deploy QC when the application is idle; edition-based redefinition is the long-term answer.

**The lock and the timeouts.** Liquibase waits `lockWaitMinutes` for its lock. Each online step ends after `<target>.timeoutMinutes`; after such an abort the library releases the lock only when this build held it, and otherwise names `DATABASECHANGELOGLOCK` for the DBA. utPLSQL ends its run itself after `test.utplsql.timeoutMinutes`, which turns Unit tests orange with the reports written so far; its Jenkins step ends 5 minutes after the larger of the two values.

**The tag and a manual rollback.** `rollback --tag <tag>` undoes the changesets after the tag, newest first, and stops with an error at the first one without a rollback, after the newer ones were undone; Oracle commits each DDL, so they stay undone. Every changeset therefore needs a rollback: `--rollback` for a table, an empty `<rollback/>` for code, which keeps the new code. `database.requireRollback: true` checks this in Build artifact. The DBA runs the rollback by hand from the delivered zip.

**The first deployment of an existing schema.** The DBA runs `changelog-sync-sql` and then `changelog-sync` from the delivered zip with the manifest, or each table changeset carries a precondition that marks it as run when the table exists. In formatted SQL these are two lines after the `--changeset` line: `--preconditions onFail:MARK_RAN` and `--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM user_tables WHERE table_name = 'ORDERS'`; a deploy user that is not the owner reads `all_tables` with `owner = 'ORDERS_OWNER'` instead. In XML the changeset holds `<preConditions onFail="MARK_RAN"><not><tableExists tableName="ORDERS"/></not></preConditions>`. The library never runs `changelog-sync` itself.

### 14.9 Unit tests and coverage with utPLSQL

- **The test target** is a schema of its own per pipeline, with the utPLSQL framework installed. The Unit tests stage deploys the package there with the context `test`, so the test packages are deployed too. With `database.test.reset: true` the schema is emptied first: `drop-all` removes the tables, views, sequences and the Liquibase tables, and the library then drops the packages, procedures, functions, triggers, types (`FORCE`) and synonyms that `drop-all` of Liquibase Community leaves; database links, scheduler jobs and queues stay. Without it, changesets of another branch in the test schema are reported.
- **utPLSQL** runs the suites of `database.test.utplsql.paths`, else of `database.test.utplsql.owner` (default `database.test.defaultSchema`, else `database.defaultSchema`), else of the login schema. `sourcePath` and `testPath` map the reports to the files of the repository.
- **Reports:** `utplsql-junit.xml` (published with `junit`), `utplsql-cobertura.xml`, `utplsql-coverage-sonar.xml` and `utplsql-sonar-tests.xml` in `.dso-db/<service>/`, archived.
- **Coverage** is the line coverage of the Cobertura report, checked against the required line coverage of the library defaults (`coverage.minLine`, 60 % in the bundled defaults). A missing report, a report without lines or one that cannot be read turns the stage orange: the required coverage cannot be verified. Failed tests turn the stage orange; tests that cannot run, or no test at all, too.
- **Not required.** Without a test database, or with utPLSQL off, coverage is recorded as **not required**: the report says so and the release gate is not blocked.
- **Privileges.** Coverage of code in another schema needs `CREATE ANY PROCEDURE` for the test user; natively compiled code reports no coverage.

### 14.10 SonarQube

PL/SQL rules need SonarQube Developer Edition, or the ZPA plugin. With a block, the library asks the server (`api/languages/list`): the language `plsql` is SonarSource PL/SQL, `plsqlopen` the ZPA plugin **(not run)**. The auto-resolve of the settings gives a service without `tools.sonar.projectKey` the key `com.bbh:<service>`, so the block asks the server unless the service sets `autoresolve parameters: false`. Only then can the project key be missing: with the auto-resolve off and without `tools.sonar.projectKey`, the block asks the server nothing, and the console shows **no SonarQube project for this service (tools.sonar.projectKey)**. Without a project key, without a PL/SQL analyzer or when the server does not answer, SonarQube is **not required** for the service, with the reason and the exit code of `curl` in the console. The language list is read with the token of the analysis: that of the SonarQube installation in Jenkins, or of `tools.sonar.credentialsId`; `tools.sonar.authToken` is only for the issue counts of the report.

`sonar-scanner` gets the arguments of `tools.sonar.liquibase`, and the block adds only what they leave out: `-Dsonar.sources` (the package folder), `-Dsonar.<analyzer>.file.suffixes=sql,pks,pkb,pls,plb,pck,prc,fnc,trg,vw,tps,tpb,typ,bdy`, `-Dsonar.tests` (the utPLSQL `testPath`), `-Dsonar.exclusions` of the test folder, the utPLSQL coverage and test execution reports, and `-Dproject.settings` (an empty file in the work folder of the step, see below). When the block sets `-Dsonar.tests`, it also adds the test folder to `-Dsonar.exclusions` of `tools.sonar.liquibase`, so that no file is both a source and a test. With a block every argument is quoted; without a block the arguments are passed to the shell as they are, so a value must not hold a blank. Like every `sonar-scanner` run, the scan also gets `-Dsonar.projectKey` and `-Dsonar.branch.name` of the build branch.

**sonar-project.properties.** For a service without a database block, the library looks for a `sonar-project.properties` at the root of the workspace and in `tools.sonar.dir` before the scan. When one is there, it is left as it is, and the branch of the build is passed on the command line; when none is there, it writes one with its defaults ([chapter 16](#16-configuration-reference-devsecops_configyaml), among them `sonar.projectKey`, `sonar.language`, `sonar.sources`, `sonar.tests`, and `sonar.modules` with `<module>.sonar.sources` and `<module>.sonar.tests` for every module of `pom.xml` or `settings.gradle`) at the root of the workspace. For a service with a database block the library neither writes nor changes such a file, and the scanner of the block reads none: it gets `-Dproject.settings` with an empty file in the work folder of the step, so every setting of the scan is on its command line. This matters where the block scans from the root of a build with modules, as in layout (b) without `tools.sonar.liquibase.dir`: the defaults that the library writes there for `orders-app` name `sonar.modules=app` from `settings.gradle`, and a scanner that read them would analyse the Java sources of the module `app` under the project key of `orders-db`, or fail. Neither these defaults, written for another service of the run in the same folder, nor a `sonar-project.properties` of the repository reach the block. A block that is to read a file of its own names it in `tools.sonar.liquibase.flags` with `-Dproject.settings=<file>`; the block then writes no empty file, and the arguments on the command line still win over that file.

With `tools.sonar.liquibase.dir` the scanner runs in that folder, and SonarQube analyses only the files under it. The settings check therefore refuses a block whose package folder or utPLSQL test folder lies outside it, and the block gives every path it adds from that folder: in layout (a) with `dir: db`, `-Dsonar.sources=.`, `-Dsonar.tests=test` and `-Dsonar.exclusions=test/**`. utPLSQL names the files of its reports from the workspace (`db/code/pkg_orders.pkb`), so the block checks the reports in `.dso-db/<service>/` before the scanner enters the folder and gives the scanner copies in the work folder of the step, in which the file paths start below that folder (`code/pkg_orders.pkb`). The arguments of `tools.sonar.liquibase`, `sources` among them, are given as they are and are read from that folder too.

### 14.11 Credentials and safety

For the steps of the database block:

- The settings, the log, the report and the archive hold Jenkins credentials IDs only.
- The password reaches Liquibase through a properties file of mode 0600 that exists only during the step, and utPLSQL through an argument file of mode 0600; never through the environment, a command line or a system property, so a changelog cannot read it as a parameter. Every output line passes a mask.
- A JDBC URL with a user or a password is refused. A changeset with a password, `executeCommand` or `customChange` stops the build.
- `liquibase.properties` of the repository is never read and never packaged; every inherited `LIQUIBASE_*` variable is unset, and Liquibase analytics are off.
- `allowedTargets` is a guard; the control is that no Jenkins folder holds production credentials.

The Liquibase commands of `build.liquibase`, `tests.unitTests.liquibase` and `asoc.liquibase`, and the scanner arguments of `tools.sonar.liquibase`, run as without a block, also next to one: the console shows their whole command line, and Liquibase reads the `liquibase.properties` of their folder and the `LIQUIBASE_*` variables of the agent. Their flags, that file and those variables must never hold a password, or a URL with one; the steps of the block need none of them.

### 14.12 Report and metrics

**The report** has a card **Database changes** for the services with a block. See `examples/reports/database-plsql-green.html` and `examples/reports/database-static-checks-orange.html`.

| Table | Columns |
|---|---|
| Package | Service; Package, the zip with a link to the archive; Changesets / for RD and QC, all changesets and those of the review SQL; Review, the review SQL, or **delivered snapshot** in the extended pipeline; Static checks, the number of findings with a link to `static-checks.txt`; utPLSQL, tests and failures, or **not run**; Liquibase, the version |
| Deployments | Service; Database, with the reason of a failure; Status; Contexts; Pending / applied / left; New invalid / errors, with the objects invalid before; Tag |

The card ends with **The package is the zip delivered to Nexus; RD and QC are deployed from it. The library never rolls back.**

**InfluxDB** gets one `database_deployment` point per service and target that was deployed, tagged with `module`, `target` and `status`, with the fields `pending_before`, `applied`, `previously_run`, `filtered_out`, `pending_after`, `invalid_objects`, `invalid_before`, `compile_errors`, `changesets`, `tagged`, `ok`, `duration_ms`, `version` and `liquibase` ([Grafana Dashboard Query Reference](#23-grafana-dashboard-query-reference)). The `code_coverage` point of a service carries `measured=yes`, `measured=not_required` without a test database or utPLSQL, or `measured=no`.

### 14.13 Production (by hand, outside the library)

The DBA deploys production from the release zip in Nexus **(not run against a production database)**:

1. Download `<artifactId>-<version>.zip` and extract `dso-deploy.properties` next to it.
2. `liquibase --defaults-file=dso-deploy.properties --search-path=<zip> --url=<URL> --username=<USER> --password=<PASSWORD> status --verbose`
3. `update-sql` for the change ticket, then `update` and `tag <artifactId>-<version>`, with the changelog parameters of production.

The changelog path and the search path must be exactly those of the manifest, or every changeset runs again.

### 14.14 Troubleshooting

The messages below are quoted with `...` for the parts that change.

| Message | Cause | Fix |
|---|---|---|
| **a Liquibase service with a database block runs on Linux or macOS agents only** | Windows agent | choose a Linux agent in `AGENT_NAME` |
| **The SQL and PL/SQL settings in ... listed above: fix them** | wrong settings | each line above names the key; fix it in `devsecops_config.yaml` |
| **database.changelogFile is required for the database block** | a `database` block without a changelog | set `database.changelogFile`, or remove the block to run only the commands of `build.liquibase` |
| **database.changelogFile is set, but buildTool is ...** | a changelog on a Gradle, Maven or Flutter service | give the changelog its own Liquibase service, or use `build.liquibase` |
| **... which defaults.database.allowedTargets does not allow**, **allowedTargets is empty** | the database is not allowed | the DevSecOps team adds it to `defaults.database.allowedTargets` of `resources/com/bbh/devsecops/defaults.yaml` of the library release (14.3, layout (a)) |
| **database.allowedTargets is a library default** | the service sets `database.allowedTargets` | remove it; the DevSecOps team keeps the list |
| **comes after ... in the list of services** | the database service is not first | list it first |
| **No Liquibase CLI on agent**, **No utPLSQL-cli on agent**, **does not exist on agent** | a tool is missing | install it, or set `home`, `path`, or `url` with `sha256` |
| **does not have the sha256 of**, **The download of ... failed** | wrong checksum or URL | compare `sha256sum` of the Nexus file |
| **is not supported: use Liquibase 4.33 or 5.0**, **has not been tested** | an old or an untested version | 4.33.x or 5.0.x |
| **uses its bundled Oracle driver** | the full 4.33.0 bundle | trim the install (14.2) |
| **has no Oracle driver** | Liquibase 5 without a driver | `tools.oracleJdbc` |
| **the changelog ... is not valid**, **update-sql of the changelog failed** | an invalid changelog: a bad `--changeset` line, a duplicate id, a missing file, a Pro feature, bad XML | the reason quotes the file and the line |
| **static check finding(s)** | SQL\*Plus, a misplaced `/`, split or stripped PL/SQL, an undeployed file | the rules of 14.4 |
| **finding(s) that the library does not deploy** | a password or agent code in a changeset | remove it; use `database.parameters` |
| **the package ... is not usable** | the changelog uses a file outside the package folder | keep every file under the package folder |
| **future-rollback-sql failed** | a changeset without a rollback | add `--rollback` or `<rollback/>`, or set `requireRollback: false` |
| **utPLSQL: ... tests failed**, **utPLSQL could not run the tests**, **utPLSQL ran no test** | failed tests, utPLSQL missing or not allowed, no suite found | read the console; check the framework, the grants, the annotations and `paths` |
| **No utPLSQL coverage report**, **No coverage report with measured lines** | utPLSQL wrote no coverage, or none for the sources | check `sourcePath` and the privileges of 14.9 |
| **the Jenkins credentials ... cannot be used** | an unknown credentials ID | add **Username with password** credentials to the folder of the job |
| **cannot be passed to** | the user or the password is empty, has a leading or trailing blank, or a character outside printable ASCII | correct the Jenkins credential |
| **drop-all failed** | `database.test.reset` could not empty the test schema: `drop-all`, or the drop of the code, types and synonyms it leaves, failed | check the privileges of the test user on its schema |
| **in the test database ... not in this changelog** | another branch shares the test schema | `database.test.reset: true`, or one schema per pipeline |
| **status failed** | Liquibase could not read the target | the reason follows; check the URL, the network and the credential |
| **update failed after** | a changeset failed; the ones before it stay applied | fix the cause and run the stage again; the library does not roll back |
| **the compile check has no baseline** | the invalid objects could not be read before the update | every invalid object counts; check the privileges of 14.8 |
| **the compile check could not run** | the check after the update failed | check the privileges of 14.8 |
| **the deployment left invalid objects** | new invalid objects | fix the PL/SQL and run the stage again; the library does not roll back |
| **invalid before this deployment and not changed by it** | old invalid objects | information; the owning team fixes them |
| **still pending after the update** | contexts, labels or preconditions | check the filters of the target |
| **did not end within ... minutes**, **the Liquibase lock** | a timeout or a held lock | `<target>.timeoutMinutes`, for utPLSQL `test.utplsql.timeoutMinutes`; the DBA checks `DATABASECHANGELOGLOCK` |
| **the tag ... could not be set** | the tag failed | the changes are deployed; run again only to tag |
| **the security pipeline delivered no version**, **could not be fetched** | no delivered zip | run the security pipeline; check Nexus and the Maven settings of the agent |
| **Maven is not installed on agent** | Maven missing | `database.package.mvnPath` or `build.maven.mvnPath` |
| **SonarQube is not required for SQL and PL/SQL** | no project key (with `autoresolve parameters: false`) or no PL/SQL analyzer | set `tools.sonar.projectKey` and use a server that analyses PL/SQL |
| **tools.sonar.liquibase.dir ... does not hold the package folder**, **does not hold the utPLSQL test folder** | `sonar-scanner` runs in `tools.sonar.liquibase.dir`, and SonarQube analyses only the files under it (14.10) | set `tools.sonar.liquibase.dir` to a folder that holds the package folder and the `testPath` of utPLSQL, or remove it |
| **did not list its languages** | the language request of 14.10 failed: no route to the server, a wrong URL, or a server that needs a login and got no token | check the `curl` exit code; give the SonarQube installation in Jenkins a token, or set `tools.sonar.credentialsId` |
| **The Nexus IQ GoldenFix pipeline would scan nothing** | a run of database services only | run the pipeline for a service with a Nexus IQ application |
| **No UrbanCode Deploy applications are configured** | a Liquibase service without a block and without deployment settings in the full pipeline | use the security or SAST pipeline, add the deployment settings, or add a database block (14.1) |
| **buildTool sql found no SQL or PL/SQL file** | the search root of a `buildTool: sql` service holds no file with one of the extensions of 14.4 | set `sourceDir` or `database.searchPath` to the folder of the files; the extensions are lower case |
| **buildTool sql writes its changelog ... into the search root and packages the whole search root, which is the root of the repository** | the SQL and PL/SQL files lie at the root of the repository or in several top folders | move them into one folder, or set `sourceDir` or `database.searchPath` |
| **is in the repository: buildTool sql writes this changelog itself** | the search root holds a `dso-changelog.xml` without the mark of the library | rename the file, or keep it and set `buildTool: liquibase` with `database.changelogFile: dso-changelog.xml` |
| **is for SQL and PL/SQL files without a Liquibase changelog, but ... the service runs as buildTool liquibase** | `buildTool: sql` on a service that has a changelog or Liquibase commands | set `buildTool: liquibase`, with the `database.changelogFile` that the line names |
| **is empty - left out of the generated changelog** | an empty SQL or PL/SQL file in a `buildTool: sql` service | remove the file, or ignore the line |
| every changeset runs again | the identity changed | restore the path or `logicalFilePath`; never `changelog-sync` without the DBA |

An Oracle error of an update is quoted with a hint: ORA-04021 (a package in use: deploy when idle), ORA-00955 (the object existed before Liquibase: 14.8, first deployment), ORA-01031 (a missing grant), ORA-00900, ORA-00911 or ORA-00933 (delimiters or SQL\*Plus: 14.4), and a checksum error (an applied changeset was edited: undo the edit and add a new changeset).

### 14.15 Not supported

Liquibase Pro and Secure features (flows, policy checks, `runWith`, `include` in formatted SQL); a database block for a database other than Oracle; deployment to production; automatic rollback; SQL\*Plus scripts; TNS aliases, `TNS_ADMIN`, wallets and Kerberos; IPv6 addresses; Windows agents for the database block; the Liquibase Gradle and Maven plugins as a deployment; installing utPLSQL into a database; for `buildTool: sql`: file extensions in upper case, an order from the dependencies between objects, and the detection of `sql` with `autoresolve parameters: false`.

### 14.16 What was tested

`test/sandbox/DatabaseScenarios.groovy` runs the four layouts under the sandbox, layout (a) in all five pipelines and layout (d), the SQL and PL/SQL files without a changelog, in the full, security and SAST pipelines, with Liquibase and utPLSQL output taken from real runs, and pins them with the golden files `test/sandbox/golden/database-*.txt`; it also runs a Liquibase service without a database block, whose calls equal those of the same run before the block existed. It compares this chapter with the repository: every file shown with its fixture, every console with its golden file, the file trees of the layouts with `test/fixtures/database`, the settings of the application services with the reference implementation, the keys of 14.5 and the messages of 14.14 with the library. `test/database/cli-scenarios.sh` runs the real Liquibase 4.33.0 and 5.0.4 CLIs offline and against H2 when they are in the tools cache; both validate the generated changelog of layout (d) and write its update SQL. The compile check, utPLSQL, the Nexus upload and fetch and the SonarQube PL/SQL analysis were not run against a real Oracle database, Nexus or SonarQube server.

## 15. Reference Implementation Examples

The library ships a complete, working example under `examples/CertScanner/`, and the test suite regenerates a set of demo reports under `examples/reports/` on every run so you can see exactly what the pipeline produces before you run it yourself. The reports there have the stages of the pipelines as they run now, with the build before the static analysis, and the full and security reports are those of a run with the stages in parallel, the default, so their table **Manual Actions vs DevSecOps** shows the steps of a parallel group with the time of the longest one. `examples/pull-request-comments.md` shows the comments of a pull request.

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

## 16. Configuration Reference: devsecops_config.yaml

`devsecops_config.yaml` at the root of the repository holds the settings of the services of the repository under `projects:`. A repository without it is read from `config.yaml`, the name the file had in the earlier releases of the library, with the same content. Only the `projects:` section is read: a `defaults:`, `platform:` or `pipeline:` section of the file is ignored and named in the console.

The global settings are the library resource `resources/com/bbh/devsecops/defaults.yaml`, which the DevSecOps team owns and changes with a library release. Its `defaults:` section is merged under every service, and its optional `platform:` section holds the platform values (see **The bundled defaults** below). The thresholds, the required coverage, the scan timeouts and the release gate are read from the bundled defaults only, so a project cannot change them, also not by setting the same keys for a service; the other keys of the defaults are starting values that a service may set for itself.

Below is the `projects:` section of `devsecops_config.yaml` with every key the library reads and its default; a key shown as a comment has no default and is set only when the service needs it. `devsecops_config.yaml.template` at the root of the library holds the same keys as a commented service to copy.

```yaml
projects:

  <project-name>:                    # the service name, as projectNames of the Jenkinsfile gives it

    appId:        ""                 # HCL AppScan application ID (UUID)
    buildTool:    gradle             # gradle | maven | flutter | liquibase | sql (plsql)  [default: gradle]
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
                                     # the API key (keyId, token) is the functional user's one in the library defaults; a service value is ignored
      insecureTls: false             # true adds curl -k to the ASoC REST calls (TLS verification off) - keep false
      doCompile:   true              # compile the project before the IRX is generated
      sourceCodeOnly: false          # true: -sco, no compiled classes in the IRX
      useAppScanConfig: false        # true: use appscan-config.xml from the repository
      # gradle: {tasks: ['classes', 'testClasses']}   # optional SAST compile instead of build.gradle; differing from build keeps Nexus IQ, SAST and SonarQube one after another on Linux/macOS
      # maven:  {goals: ['compile']}                   # optional SAST compile instead of build.maven; never 'clean', which deletes target/ before the Nexus delivery
      # liquibase: {commands: ['updateSQL'], flags: ['--changelog-file=db/changelog.xml'], dir: 'db', env: {}}   # optional SQL/PL-SQL SAST generation before IRX; also runs alongside Gradle or Maven in multi-module services

    sast:
      scanName:    ""                # optional: name of the SAST scan in AppScan, sanitised [default: <tools.sonar.projectName or service>-sast]

    staticAnalysis:
      parallel:    true              # unit tests, Nexus IQ, SAST and SonarQube at the same time; false in any service of the run: one after another
                                     # [default: true; DSO_STATIC_ANALYSIS_PARALLEL decides first; a Windows agent runs them one after another]
    dynamicAnalysis:
      parallel:    true              # regression, smoke and performance tests and DAST at the same time; false in any service: one after another
                                     # [default: true; DSO_DYNAMIC_ANALYSIS_PARALLEL decides first]

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
        credentialsId:    ""         # Jenkins credential with the SonarQube token for the analysis and, with a database block, the language list
        authToken:        ""         # Jenkins Secret text credential for the issue counts the report queries
        badgeCredentialsId: ""       # Jenkins Secret text credential with the badge token, wins over badgeToken
        badgeToken:       ""         # the badge token itself, for the SonarQube badges in the HTML report
        addBadges:        false      # add the badges to the build description
        fullBadges:       false      # all badges instead of the quality gate badge only
        gradle:                      # the analysis command of a Gradle service (tasks, flags, dir, env as in build.gradle)
          tasks: ['sonarqube']
        maven:                       # the analysis command of a Maven service (goals, flags, mvnPath, env as in build.maven)
          goals: ['sonar:sonar']
        liquibase:                   # the analysis settings of a SQL/PL-SQL (Liquibase) service, or additional SQL sources in a multi-module service
          sources:    "."            # comma-separated directories with SQL / PL/SQL scripts and changelogs [default: tools.sonar.sources, else . inside dir, else build.liquibase.dir or sourceDir]
          inclusions: ""             # optional sonar.inclusions pattern (e.g. "**/*.sql,**/*.pls,**/*.pkb,**/*.pks")
          exclusions: ""             # optional sonar.exclusions pattern
          flags:      []             # optional additional sonar-scanner CLI flags (e.g. ["-Dsonar.plsql.file.suffixes=.sql,.pls,.pkb,.pks"])
          dir:        ""             # optional working directory for sonar-scanner
          env:        {}             # optional environment variables for sonar-scanner
        npm:                         # npm, pip and iOS services, analysed with the sonar-scanner CLI (the same keys under pip and ios)
          sources:    ""             # optional sonar.sources [default: sonar-project.properties of the repository or of the library]
          flags:      []             # optional additional sonar-scanner arguments
        branch:           ""         # the branch to analyse [default: CHANGE_BRANCH, BRANCH_NAME, GIT_BRANCH, the branch of the checkout]
        branchAnalysis:   true       # false: never pass sonar.branch.name (SonarQube Community Edition); with true a refused branch is retried without it
        sonarScannerVersion: ""      # the sonar-scanner-cli-<version> tool of Jenkins for every sonar-scanner run [default: sonar-scanner on the PATH]
        sonarQubeEnv:
          installationName: ""       # Flutter / Liquibase: SonarQube installation [default: installationName]
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
      liquibase:
        commands: ['updateSQL']      # [default: updateSQL from the auto-resolve; validate with autoresolve parameters: false; with a database block validate when only flags are set, else none]
        flags:    ['--changelog-file=src/main/resources/changelog.xml']   # [default from the auto-resolve: --changelog-file= of the changelog it finds; none with a database block]
        dir:      ""                 # optional subdirectory to run liquibase in
        env: {}                      # optional environment variables
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

    database:                        # buildTool: liquibase, where changelogFile switches the database block on, and buildTool: sql (chapter 14)
      changelogFile:   ""            # master changelog, relative to the search root
      searchPath:      "."           # search root, relative to sourceDir
      oracleVersion:   "19.0"        # Oracle version of the offline validation
      defaultSchema:   ""            # --default-schema-name of every online step
      liquibaseSchema: ""            # --liquibase-schema-name of every online step
      contexts:        "!test"       # context filter of RD, QC and the review SQL
      labels:          ""            # label filter of the test database, RD, QC and the review SQL
      parameters:      {}            # changelog parameters, no secrets
      requireRollback: false         # true: a changeset without a rollback turns Build artifact orange
      tag:             true          # tag RD and QC with <artifactId>-<version> when a changeset ran
      lockWaitMinutes: 5
      compileCheck:    {enabled: true, recompile: true, ignoreObjects: []}
      checks:          {ignoreFiles: []}
      package:
        groupId:       ""            # required when the pipeline delivers the zip to Nexus
        artifactId:    ""            # [default: the service name]
        nexusDelivery: true
        mvnPath:       ""            # [default: build.maven.mvnPath]
        snapshotRepository: {id: "", url: ""}   # [library default]
        releaseRepository:  {id: "", url: ""}   # [library default]
      test:                          # optional test database of this pipeline
        url:           ""            # jdbc:oracle:thin:@//host:1521/SERVICE, no user or password
        credentialsId: ""            # Jenkins Username with password
        contexts:      "test"
        timeoutMinutes: 30
        reset:         false         # true: empty the test schema before the deployment
        utplsql:       {enabled: false, paths: "", owner: "", sourcePath: "", testPath: "", timeoutMinutes: 30}
      rd:                            # and qc: the same keys as test, without reset and utplsql; contexts [default: database.contexts]
        url:           ""
        credentialsId: ""

    delivery:                        # Nexus snapshot delivery of stage 6 and the download of the extended pipeline (deployTarget: vm)
      nexusDeliveryEnabled: true     # false: stage 6 publishes nothing and the extended pipeline downloads nothing [default: deploy.<target>.rd.nexusDeliveryEnabled, then true]
      push:
        maven:                       # buildTool: maven - one deploy-file upload, every file with its own classifier
          goals: ['deploy:deploy-file']
          flags: ['-DrepositoryId=bbh-snapshots', '-Durl=https://tools.bbh.com/nexus/repository/snapshots',
                  '-DgroupId=com.bbh.myapp', '-DartifactId=myapp', '-DgeneratePom=true']
          artifacts:
            - path: "target/*.jar"   # glob; the classifier is the file name without its extension
            # classifier: "app"     # optional; the path must then match one file
        # gradle:                    # buildTool: gradle - maven-publish run with the Gradle wrapper of the service
        #   group:         "com.bbh.myapp"
        #   artifact:      "myapp"
        #   repositoryUrl: "https://tools.bbh.com/nexus/repository/snapshots"
        #   artifacts: [{path: "build/libs/*.jar"}]
      pull:                          # devSecOpsExtendedPipeline, stage Download artifacts from Nexus Repo
        outputDirectory: "downloaded-artifacts"
        group:    "com.bbh.myapp"    # Maven: the groupId and artifactId of the upload
        artifact: "myapp"
        maven:
          goals: ['org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get']
          flags: ['-DremoteRepositories=bbh-snapshots::default::https://tools.bbh.com/nexus/repository/snapshots']
          artifacts:
            - path: "myapp.jar"      # file name in outputDirectory; the classifier is the name without its extension
        # gradle: {group: "com.bbh.myapp", artifact: "myapp", repositoryUrl: "https://...", artifacts: [{path: "myapp.jar"}]}
      # maven: {goals: [...], flags: [...]}   # without push.maven: one upload of build.buildPath without a classifier, as before
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
      minThreatLevel:         2      # 2+ medium, 4+ high, 8+ critical
      ecosystems:             [maven, npm, pypi, nuget]   # supported: maven, npm, pypi, nuget, pub; a Flutter service adds pub
      goldenVersionTypes:            # Nexus IQ remediation types taken as they are (the Golden Version)
        - recommended-non-breaking-with-dependencies
        - recommended-non-breaking
      excludeDirs:            []     # [default: .git, node_modules, target, build, .gradle, dist, venv, ...]
      verify:                        # the pre-check build and unit tests of the proposed versions
        maxAttempts:    3            # version sets tried; each failed build or test run lowers every proposed version; the build with the Nexus IQ
                                     # version instead of a catalog version comes on top
        timeoutMinutes: 20           # for each check, that is each kind of manifest and folder
        commands:                    # replaces the default check of one ecosystem; runs in the folder of the check
          maven:  ""                 # [default: mvn -B -q test]
          gradle: ""                 # [default: ./gradlew --no-daemon --console=plain test, or gradle without a wrapper]
          npm:    ""                 # [default: npm ci --ignore-scripts, or npm install --ignore-scripts without a lock file, then npm test]
          pip:    ""                 # [default: pip install -r <file> (or . for pyproject.toml) in a temporary venv, then pytest]
          pub:    ""                 # [default: flutter pub get and flutter test, or dart for a Dart package]
          nuget:  ""                 # [default: dotnet test, or nuget restore for packages.config; on Windows with MSBuild.exe: nuget restore & MSBuild.exe, build only]
      commitAuthorName:       "DevSecOps GoldenFix"
      commitAuthorEmail:      "devsecops-goldenfix@noreply.local"
      timeZone:               ""     # time zone of the GoldenFix-YYYYMMDDHHMM name
      versionCatalogs:        []     # optional, this service only: shared catalog files the search does not find by itself,
                                     # repository-relative paths or globs (* within a folder name, ** across folders)
      # versionCatalogRepo:          # optional: the repository that holds the shared version catalog of several repositories
      #   url:           ""          # its clone URL (SSH or HTTPS); a plain string instead of the map is read as the URL
      #   branch:        "main"      # [default: main]
      #   paths:         []          # the catalog files in it
      #   credentialsId: ""          # [default: scm.bitbucket.credentialsId]
      #   authType:      ""          # basic or bearer [default: scm.bitbucket.authType, else basic]; an SSH URL uses the SSH key credential

    pullRequestDecoration:           # comments of SAST, Nexus IQ and SonarQube findings on pull requests
      enabled:          false        # true: decorate also when the run raised no GoldenFix pull request
      maxComments:      25           # comments one run adds to one pull request
      sastMinSeverity:  "MEDIUM"     # LOW, MEDIUM, HIGH or CRITICAL
      sonarMinSeverity: "MAJOR"      # INFO, MINOR, MAJOR, CRITICAL or BLOCKER

    tests:
      maxParallel: 20                # [default: the bundled defaults] max jobs of one test stage running at the same time
      unitTests:                     # stage 2: the test command and where the results are
        gradle:
          tasks: ['test', 'jacocoTestReport']
        maven:
          goals: ['test', 'jacoco:report']
        liquibase:
          commands: ['validate']     # [default: validate from the auto-resolve; with autoresolve parameters: false or a database block, validate when only flags are set, else none]
          flags:    ['--changelog-file=src/main/resources/changelog.xml']   # [default from the auto-resolve: --changelog-file= of the changelog it finds; none with a database block]
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
        dod:                         # UrbanCode Deploy, used by devSecOpsPipeline and devSecOpsExtendedPipeline for the lower test region
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
                  version:             ""        # [default: <versionPrefix>_<build tag of build.tag>, without it <versionPrefix>_<BUILD_ID>]
                  incrementalVersion:  true
                  pushDescription:     ""        # optional fields of the UrbanCode component push
                  extensions:          ""
                  charset:             ""
                  versionProperties:   ""
                  versionDescription:  ""
        rd:                          # lower test environment over SSH (deployRD() of a custom pipeline)
          nexusDeliveryEnabled: true # false: stage 6 does not publish this service to Nexus; delivery.nexusDeliveryEnabled wins when set
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
          nexusDeliveryEnabled: true # false: stage 6 builds and publishes no image of this service; delivery.nexusDeliveryEnabled wins when set
          buildTag:           ""     # the image tag RD, and QC without its own buildTag, deploy when nexusDeliveryEnabled is false; a region without a tag is skipped as not required
          projectBuildR:      ""     # namespace where the image is built
          buildConfigPath:    ""     # BuildConfig template in the repository
          dockerFilePath:     ""     # Dockerfile in the repository
          buildContext:       ""     # directory prepared for the binary build
          # addFile: ""              # optional extra files copied into the build context, separated by spaces; each keeps only its file name
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
    baseArtifactName: ""             # OpenShift: original artifact name, found anywhere in the workspace and renamed to artifactName when set

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

  pluginRepositories:                # the BBH Nexus that a failed Gradle or Maven step takes a missing plugin from (chapter 19); a service may set its own
    enabled: true
    gradle:  "https://tools.bbh.com/nexus/repository/gradle-plugins/"
    maven:   "https://tools.bbh.com/nexus/repository/maven-public/"

  report:
    manualEffort:                    # read from here only: minutes a person needs to start a step by hand or to raise its ticket, the Manual actions column of the report
      build:            10           # manual trigger of the build
      unitTests:        0            # the unit tests with code coverage run with the build
      nexusIq:          10           # manual trigger of the Nexus IQ scan
      sast:             10           # manual trigger of the SAST
      sonar:            10           # manual trigger of the SonarQube analysis
      nexusDelivery:    0            # upload to or download from the Nexus
      lowerDeployment:  0            # deployment to the lower test region
      regression:       10           # manual trigger of the regression tests
      smoke:            10           # manual trigger of the smoke tests
      performance:      10           # manual trigger of the performance tests
      dast:             360          # ticket to CyberTrack for a DAST
      higherDeployment: 0            # ticket to SRM for the deployment to the higher test region

  releaseGate:                       # read from here only; a stage that breaks the policy blocks the release and QC
    scanners:        [sast, sca, niq, dast]
    requireCoverage: true            # line coverage must reach coverage.minLine
    stateFile:       release-gate.json

  goldenFix:                         # the GoldenFix policy; a service may set its own values
    enabled:                true
    minThreatLevel:         2        # 2+ medium, 4+ high, 8+ critical
    ecosystems:             [maven, npm, pypi, nuget]
    goldenVersionTypes:
      - recommended-non-breaking-with-dependencies
      - recommended-non-breaking
    verify:
      maxAttempts:    3
      timeoutMinutes: 20
      commands:                      # empty: the default check of each ecosystem
        maven:  ""
        gradle: ""
        npm:    ""
        pip:    ""
        pub:    ""
        nuget:  ""
    commitAuthorName:  "DevSecOps GoldenFix"
    commitAuthorEmail: "devsecops-goldenfix@noreply.local"
    timeZone:          ""            # time zone of the GoldenFix-YYYYMMDDHHMM name, empty = agent default
```

The production file sets no `staticAnalysis.parallel`, no `dynamicAnalysis.parallel` and no `build.node` defaults; both switches are `true` in the library itself. A key the DevSecOps team adds to `defaults:`, such as `staticAnalysis.parallel: false` or `build.node.installationName`, is merged under every service like the keys above. A `defaults.staticAnalysis.parallel: true` changes nothing, and the console says that it can be removed.

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
| Every other key of `defaults:`, such as `goldenFix`, `tests.maxParallel`, `buildTool` and, when the team adds them, `staticAnalysis.parallel`, `dynamicAnalysis.parallel` and `build.node` | `defaults:` of the same file, merged under every service | The DevSecOps team; a service of `devsecops_config.yaml` may set its own value |
| `platform.*` | The optional `platform:` section of the same file | The DevSecOps team; a value of the agent or Jenkins environment wins for `platform.environment` |
| `projectNames`, `agentNames`, `securityPipeline` | The `Jenkinsfile` | The project team |
| `DSO_STATIC_ANALYSIS_PARALLEL`, `DSO_DYNAMIC_ANALYSIS_PARALLEL` and the tool environment variables | The Jenkins global properties, the node properties of an agent, or the `environment` block of a custom Jenkinsfile | The Jenkins administrators, or the project team in a custom Jenkinsfile |

### Defaults of the Node.js build

The bundled defaults may hold three defaults of the Node.js build, `defaults.build.node.installationName`, `defaults.build.node.registry` and `defaults.build.node.npmrcCredentialsId`, merged under every service; the production file holds none. A value of the service replaces them, and `build.node.nodePath` of the service wins over any installation. The bundled defaults do not set `build.node.enabled`, so the Node.js build runs only for a service that switches it on. The registry is exported as `npm_config_registry` only while no `.npmrc` credentials ID is in effect, one of the bundled defaults included, because that `.npmrc` names its own registry. Once exported, it wins over the registry line of an `.npmrc` in the repository. Leave `build.node` off for a Flutter service: a Flutter build has no Gradle or Maven stage for it to run before.

`goldenFix.versionCatalogs` is a setting of the service only. The bundled defaults do not set it, and it does not depend on whether the service keeps or overrides the GoldenFix rules of the bundled defaults.

### Stages at the same time: staticAnalysis.parallel and dynamicAnalysis.parallel

`staticAnalysis.parallel` decides whether the full and security pipelines run the unit tests, Nexus IQ, SAST and SonarQube at the same time in the **Static analysis** stage, and `dynamicAnalysis.parallel` whether the full and extended pipelines run the regression, smoke and performance tests and DAST at the same time in the **Dynamic analysis** stage ([the stage reference](#17-pipeline-stage-reference)). The SAST and Nexus IQ GoldenFix pipelines are not affected.

- Both switches are `true` by default: a run without the keys runs the stages at the same time. `false` is the way back to the order one after another.
- A service of `devsecops_config.yaml` or `config.yaml` sets `staticAnalysis.parallel` or `dynamicAnalysis.parallel` for itself, and a value under `defaults:` of the bundled defaults is merged under every service. One service with `false` keeps the whole run one after another, and the console names it: "\[STATIC\] backend-api: staticAnalysis.parallel: false - Unit tests, Nexus IQ, SAST and SonarQube run one after another". `false` under `defaults:` of the bundled defaults is named once: "\[STATIC\] defaults.staticAnalysis.parallel: false - Unit tests, Nexus IQ, SAST and SonarQube run one after another".
- A value that is neither `true` nor `false`, such as `'sometimes'`, or a `staticAnalysis` that is not a map, is not an error: the console names it in a `[WARN]` line and the default `true` is used. A service that sets `true`, the default, gets an `[INFO]` line that says the key can be removed, and so does `true` under `defaults:` of the bundled defaults.
- The environment variables `DSO_STATIC_ANALYSIS_PARALLEL` and `DSO_DYNAMIC_ANALYSIS_PARALLEL` decide first when they are `true` or `false`, whatever the settings say; another value is named in a `[WARN]` line and the settings decide. `true` gets an `[INFO]` line that says the variable can be removed. The library reads them once per run, on the agent, when Jenkins decides whether to run the stage. They therefore count when they are set in **Manage Jenkins → System → Global properties → Environment variables**, in the environment variables of the agent's node properties, or in the top-level `environment` block of a custom Jenkinsfile ([the API reference](#21-programmatic-api-reference)). A value set on a Jenkins folder or job counts only when a plugin puts it into the environment of the build; the library calls no step such as `withFolderProperties`.
- On a Windows agent the static stages run one after another, because the SAST staging of Windows uses the workspace, and the console says so.
- SAST compiles and prepares its scans in a copy of the workspace, `<WORKSPACE_TMP>/dso-sast-workspace` (`<WORKSPACE>@tmp/dso-sast-workspace` without `WORKSPACE_TMP`), so a SAST compile with other Gradle tasks or Maven goals than the build command (`asoc.gradle`, `asoc.maven`, `asoc.dotnet`) never changes the files Nexus IQ scans. When the copy cannot be made, the console says why in a `[WARN]` line and the run falls back to the earlier order: SAST queues its scans first, and the unit tests, Nexus IQ and SonarQube run one after another while AppScan scans. A service whose SAST compile differs from its build command then keeps the four stages one after another, and the console names it: "\[STATIC\] gui: the SAST compile runs other Gradle tasks or Maven goals than the build command in the workspace, so it may not rebuild the files Nexus IQ scans: Unit tests, Nexus IQ, SAST and SonarQube run one after another".

### Which fields are required

| Field | Required | Notes |
|---|---|---|
| `asoc.keyId` | NO, library defaults only | The key ID of the functional AppScan on Cloud user in `resources/com/bbh/devsecops/defaults.yaml`; a value of a service is ignored with a warning, SAST and DAST stop with a message while the defaults hold none |
| `asoc.token` | NO, library defaults only | Secret text credential with the key secret of the functional user, in the library defaults; without it the credential named like `asoc.keyId` is used; a value of a service is ignored with a warning |
| `appId` | YES | Needed for AppScan SAST and DAST |
| `tools.sonar.projectKey` | Auto-resolved / Recommended | When auto-resolve is enabled (default), automatically synthesizes `com.bbh:<projectName>` |
| `tools.nexusIq.application` | Recommended; YES with `scanPatterns` for `devSecOpsNexusIqGoldenFixPipeline` | Nexus IQ scan is skipped silently without it; the Nexus IQ GoldenFix pipeline stops at the start without it |
| `dast.targetUrl` | Only if `dast.enabled: true` | Pipeline fails at DAST stage without it |
| `javaPath` or `buildToolAutoSetup: true` | Auto-resolved or YES for Gradle/Maven/Flutter; not required for `dotnet` / `msbuild`; optional for Liquibase; required (or `buildToolAutoSetup`) with a database block | When auto-resolve is active (default), `buildToolAutoSetup: true` and the compiler JDK are automatically inferred from `pom.xml` / `build.gradle`; otherwise required for Gradle/Maven/Flutter: without one of them the Unit tests stage, or the Build artifact stage of the Nexus IQ GoldenFix pipeline, stops with **JAVA_HOME parameter is not specified** for Java builds. Auto-resolve sets neither for `buildTool: liquibase`: an explicit `javaPath` is exported as `JAVA_HOME` when set, and omitted otherwise |
| `deploy.vm.dod` | Only if `deployTarget: vm` and you use `devSecOpsPipeline` | The lower test region deployment of the full pipeline uses UrbanCode Deploy |
| `deploy.vm.rd`, `deploy.vm.qc` | Only if `deployTarget: vm` | SSH deployment of the extended pipeline and of the QC stage |
| `deploy.openshift.*` | Only if `deployTarget: openshift` | Auto-detected when container files or manifests are found; `appName` and `artifactName` are required as well |
| `delivery.nexusDeliveryEnabled` | NO (default `true`) | `false`: stage 6 publishes nothing for the service and the extended pipeline downloads nothing; wins over `deploy.<target>.rd.nexusDeliveryEnabled`, which is read without it |
| `delivery.push.maven` | Only if `deployTarget: vm` and `buildTool: maven` | Snapshot upload with `goals` (e.g. `deploy:deploy-file`) and `flags`; `artifacts` lists globs, and every file is uploaded under the one snapshot version of the run with the classifier of its entry or, without it, its file name without the extension |
| `delivery.push.gradle` | Only if `deployTarget: vm` and `buildTool: gradle` | Snapshot upload with `maven-publish`, run by the Gradle wrapper of the service on a project the library writes to the temporary folder of the build: `group`, `artifact`, `repositoryUrl` and `artifacts` as for Maven; without `artifacts` the service is not required in stage 6 |
| `delivery.pull` | Only for the extended pipeline | The download of the snapshot of the security run into `outputDirectory` (default `downloaded-artifacts`): `maven` with `goals` (e.g. `maven-dependency-plugin:get`), `flags` and `artifacts` plus `group` and `artifact`, or `gradle` with `group`, `artifact`, `repositoryUrl` and `artifacts`; each artifact is named by its file name, and the classifier is its name without the extension unless the entry sets one |
| `delivery.maven` | Only without `delivery.push.maven` | The upload of release b815d55: one `deploy-file` of `build.buildPath` without a classifier |
| `delivery.nuget` | Only if `buildTool: dotnet` / `msbuild` and publishing `*.nupkg` to Nexus | NuGet package push via `nuget.exe push` or `dotnet nuget push` |
| `jenkins.pipeline.extendedPipeline` | Only for `devSecOpsSecurityPipeline` | The job it starts when `RUN_EXTENDED_PIPELINE` is selected; it may be set in any service of the run, the current service first |
| `scm.bitbucket.url` | Only for GoldenFix | Without it the fixes are listed in the report but no pull request is raised |
| `scm.bitbucket.credentialsId` | Only for GoldenFix | Needs push rights and permission to create and comment on pull requests |
| `build.node.enabled` | Auto-resolved or YES | Auto-detected when `package.json` is found in repository or frontend folders; `true` adds Stage 0 Node.js build |
| `build.node.dir` | Auto-resolved | Auto-detected as `.`, `frontend`, `web`, `client`, or `ui` when `package.json` exists |
| `build.node.installationName` or `build.node.nodePath` | No | Without both, and without an installation in the bundled defaults, the commands use `node` and `npm` on the agent `PATH`; `nodePath` wins over any installation |
| `goldenFix.versionCatalogs` | No | Only for a shared catalog that GoldenFix does not find by itself, see [Integration Guide: Nexus IQ GoldenFix Pipeline](#13-integration-guide-nexus-iq-goldenfix-pipeline) |
| `database.changelogFile` | Only for a database block with a changelog | With `buildTool: liquibase` it switches the block on; a service with `buildTool: sql` is a database block without it, and the library writes its changelog; the other `database` keys and the library defaults `tools.liquibase`, `tools.oracleJdbc`, `tools.utplsql`, `database.allowedTargets` and `database.package` are described in [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase) |

### Automated Stack Discovery and Smart Auto-Resolve (RepoStackAutoResolver)

The `RepoStackAutoResolver` component eliminates over 80% of manual onboarding friction by automatically inspecting workspace files at the beginning of each pipeline run. Application teams are not required to look up compiler versions, Gradle wrappers, .NET solutions, Node.js directories, Liquibase flags or OpenShift deployment targets.

It resolves each service into its own copy of the settings, so a key it fills for one service, such as the SonarQube project key or the Liquibase commands, never reaches the next service through the library defaults they share. For a service with a database block it adds only `tools.sonar.projectKey`: no Liquibase command, no Node.js stage 0 or Cypress switch and no `deployTarget` or `appName`; and it never sets `buildTool: liquibase` for a service with `database.changelogFile` ([chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase)).

The rows of the build tool are tried in this order: `pubspec.yaml`, the iOS files, `pom.xml`, `build.gradle`, the .NET files (`*.sln`, `*.csproj`, `global.json` and the like), the Liquibase files, the Python files, `package.json`; the first that matches wins, so a Maven or Gradle application that ships a Liquibase changelog stays a Maven or Gradle service (the files and the order without the auto-resolve are in [chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase), 14.1). The SonarQube rows from `sonar.language` on are the defaults of the `sonar-project.properties` that the library writes before the scan of a service without a database block when there is none; for a service with a block it writes none (14.10).

| Setting / Parameter | YAML Key | Workspace Trigger / Manifest | Resolved Default Value |
|---|---|---|---|
| Build Tool | `buildTool` | `pubspec.yaml` | `flutter` |
| Build Tool | `buildTool` | `pom.xml` | `maven` |
| Build Tool | `buildTool` | `build.gradle`, `build.gradle.kts`, or `gradle/wrapper/gradle-wrapper.properties` | `gradle` |
| Build Tool | `buildTool` | `*.sln`, `*.csproj`, `*.fsproj`, `*.vbproj`, `Directory.Packages.props`, `Directory.Build.props`, `packages.config`, or `global.json` | `dotnet` |
| Build Tool | `buildTool` | `changelog.xml`, `changelog.yaml`, or `liquibase.properties` | `liquibase` |
| Build Tool | `buildTool` | `requirements.txt`, `pyproject.toml`, `setup.py`, or `Pipfile` | `pip` |
| Build Tool | `buildTool` | only SQL and PL/SQL files (`.sql`, `.pks`, `.pkb` and the other extensions of [chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase)), no build file and no Liquibase changelog | `sql` |
| Search Path | `database.searchPath` | `buildTool: sql`, no `sourceDir`, every SQL and PL/SQL file under one top folder | that folder |
| JDK Auto Setup | `buildToolAutoSetup` | `buildTool: sql` without `javaPath` | `true` |
| Build Tool Auto Setup | `buildToolAutoSetup` | Maven or Gradle build tool detected | `true` |
| Java Compiler Version | `build.maven.javaVersion` / `build.gradle.javaVersion` | `<java.version>`, `<maven.compiler.source>`, `<maven.compiler.target>`, `<release>` in `pom.xml`; `jvmToolchain`, `sourceCompatibility` in `build.gradle` | Matched JDK version (`17`, `21`, `11`, `8`) |
| Gradle Wrapper Version | `build.gradle.version` | `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` | Exact Gradle wrapper version (e.g. `8.7`, `7.6`) |
| Node.js Stage 0 Pre-Build | `build.node.enabled` | `package.json` present in root or frontend subfolder | `true` |
| Node.js Directory | `build.node.dir` | Detected directory containing `package.json` | `.`, `frontend`, `web`, `client`, or `ui` |
| Node.js Version | `build.node.version` | `engines.node` in `package.json` (e.g. `>=20.0.0`) | Major version (e.g. `20`) |
| Cypress Unit Testing | `tests.unitTests.node.cypress` | `cypress` in `devDependencies` or `dependencies` | `true` |
| Jest Unit Testing | `tests.unitTests.node.jest` | `jest` in `devDependencies` or `dependencies` | `true` |
| Liquibase Build Command | `build.liquibase.commands` | Liquibase changelog detected | `['updateSQL']`; none for a service with a database block ([chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase)) |
| Liquibase Validate Command | `tests.unitTests.liquibase.commands` | Liquibase build tool | `['validate']`; none for a service with a database block ([chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase)) |
| Deployment Target | `deployTarget` | `Dockerfile`, `Containerfile`, `openshift/`, `k8s/`, or `helm/` | `openshift` (otherwise defaults to `vm`) |
| SonarQube Project Key | `tools.sonar.projectKey` | Omitted or empty in configuration | `com.bbh:<projectName>` or `com.bbh:<appName>` |
| SonarQube Language | `sonar.language` | Source code tree inspection (`pubspec.yaml` -&gt; `dart`, `requirements.txt` -&gt; `py`, C#/.NET -&gt; `cs`, Kotlin -&gt; `kotlin`, Liquibase -&gt; `sql`, else `java`) | Language identifier |
| SonarQube Source Paths | `sonar.sources` | Missing `sonar-project.properties` | `src/main/java` (Java), `src/main/kotlin` (Kotlin), `lib` (Dart), `db` (SQL), `src` or `.` (Python / .NET generic) |
| SonarQube Test Paths | `sonar.tests` | Missing `sonar-project.properties` | `src/test/java` (Java), `src/test/kotlin` (Kotlin), `test` (Dart), `tests` (Python) |
| SonarQube Java Binaries | `sonar.java.binaries` | Build tool classes folder | Maven: `target/classes`; Gradle: `target/classes,build/classes/java/main` |
| SonarQube Modules | `sonar.modules` | Multi-module Maven (`<module>`) or Gradle (`settings.gradle` `include`) | Comma-separated module names |
| SonarQube Active Branch | `sonar.branch.name` | Active pipeline execution branch (`CHANGE_BRANCH`, `BRANCH_NAME`, `GIT_BRANCH`) | Evaluated branch name (never defaults to master) |

#### How to use in standalone mode (devsecops_config.yaml)

1. **Default Behaviour**: Auto-resolve is enabled by default (`autoresolve parameters: true`). You can omit build tool details, compiler paths, Node.js directories, and Sonar properties from `devsecops_config.yaml`.
2. **Minimal Configuration Example**: Application teams need to provide only the application ID in `devsecops_config.yaml`; the AppScan API key is the one of the functional user in the library defaults:

```yaml
projects:
  payment-service:
    appId: "APP-54321"
```

The pipeline automatically resolves the build tool (Maven / Gradle / C#.NET / Liquibase / Flutter / Python), compiler version, Stage 0 Node.js frontend, OpenShift target, and SonarQube properties from the repository workspace files.

1. **Disabling Auto-Resolve**: To mandate strict manual configuration and disable heuristic resolution, set `autoresolve parameters: false`:

```yaml
projects:
  payment-service:
    autoresolve parameters: false
    buildTool: maven
    deployTarget: vm
    appId: "APP-54321"
```

## 17. Pipeline Stage Reference

What each stage does for a Liquibase service with a database block, and why a stage is **not required** for it, is described in [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase), 14.6.

### Stage 1: Monitor source changes (download sources)

Performs:

- Reads `devsecops_config.yaml`, else `config.yaml`, at the workspace root (`projects:` only), merges the services of the run with the `defaults:` of `resources/com/bbh/devsecops/defaults.yaml` of the library (see [How a pipeline reads its configuration](#4-solution-architecture)) and writes them to `pipeline-config.yaml`; an extended pipeline first lays the run-time tags of its security job over them
- Applies the library policy to the state, so every later stage compares against the same thresholds
- Detects the agent operating system and makes `gradlew` executable
- Sets the tool environment: ASoC URLs, proxy host, port and user (a value in the agent's environment first, then `platform.environment` of the bundled defaults, then the library's own BBH value), and the AppScan working directories
- `devSecOpsSASTScanningPipeline` and `devSecOpsNexusIqGoldenFixPipeline` also run `checkout scm` and pin `JAVA_HOME` before this
- Checks out the deployment repository of every service that sets `deploy.openshift.rd.deploymentPath` (`deploymentRepo.url`, `branch`, `credentials`) into that folder, so the Dockerfile and the templates are there before stage 6 builds the image; the SAST-only and the Nexus IQ GoldenFix pipelines do not
- `devSecOpsExtendedPipeline` copies `pipeline-config.yaml`, `release-gate.json` and `build.tag` from the last successful build of its security job first
- Leaves out a name of `projectNames`, `PROJECT_NAMES` or `PROJECT_NAME` that is not a service of the file, and builds every service of the file when none is left; replaces a value of the wrong kind with the library default; and names the keys whose value is the one the library uses without them ([How the library chooses a value](#4-solution-architecture))

Fails if:

- neither `devsecops_config.yaml` nor `config.yaml` is at the workspace root, or the file read is not valid YAML or has no `projects:` section
- the library resource `com/bbh/devsecops/defaults.yaml` cannot be read
- an extended pipeline finds no `pipeline-config.yaml` in the last successful build of its security job, a service of the run is missing from it, or a tag in it is not a plain tag

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
- **Release delivery (stage 12):** the extended pipeline runs the Node.js build again before a release delivery that is available, today the NuGet push of a .NET service.

### Stage 2: Unit tests

Performs, once per project:

- Builds the artifact with the command of the `build` section
- Runs the unit tests with the command of `tests.unitTests` (including Visual Studio `.trx` / JUnit XML reporting for C#/.NET), then reads every JaCoCo, Cobertura, OpenCover and LCOV coverage report of the project (see **Coverage reports** below)
- Compares the line coverage of that project, the mean of its reports, with `coverage.minLine` of the bundled defaults (60 %)

The stage never fails the pipeline. Below the required value the stage turns **orange**, the report box of that project shows the measured value next to the required one, and the reason states that the step failed and that the Nexus release and the QC deployment are blocked until the coverage is raised. A missing coverage report is treated the same way, because the required level cannot be proven. A failing test run is also caught: the stage turns orange with the exit code as the reason, the coverage of the reports that were produced is still evaluated, and Nexus IQ, SAST, SonarQube, the snapshot delivery and the RD deployment still run. The required value shown in the report is always `coverage.minLine` of the bundled defaults, so a library release that changes it changes both the check and the report.

#### Coverage reports

Where the pipeline looks:

1. When `coverage.reportPath` is set, the reports it names count, and only those, so a frontend report must be named as well. It holds one path, several paths one per line or separated by commas, or a YAML list, relative to the workspace; `*` matches within a folder or file name, `?` one character and `**` any number of folders, for example `**/build/reports/jacoco/test/*.xml` or `**/TestResults/**/coverage.cobertura.xml`. When none of them exists, the pipeline searches as if it were empty. Leave it empty unless the search takes the wrong reports.
2. Otherwise the pipeline searches the folders in which the service is tested and every folder below them, such as `build`, `target`, `release`, `TestResults` and the folders of the modules: the folder of the unit test command (`tests.unitTests.gradle.dir`, `tests.unitTests.maven.dir`, or `tests.unitTests.dotnet.dir`), otherwise the folder of the build command (`build.gradle.dir`, `build.maven.dir`, or `build.dotnet.dir`), otherwise the source folder (`sourceDir`), otherwise the workspace; and, with the Node.js build, the folder of its `package.json` (`build.node.dir`, the workspace by default). The folders of the other services of the build are left out. When these folders hold no report and none of them is the workspace, the whole workspace is searched the same way.
3. A JaCoCo report is an XML file that holds a JaCoCo report and has `jacoco` in its path, such as `jacoco.xml` or `jacocoTestReport.xml`, or lies under `reports/coverage` (Android) or `reports/kover` (Kover). A Cobertura report is a `coverage.cobertura.xml` / `*cobertura*.xml` / `coverage.xml` file (or under `TestResults` / `coverage`) with root `<coverage>` and `line-rate` / `lines-valid` attributes or `<line>` elements; an OpenCover report is a `coverage.opencover.xml` / `*opencover*.xml` file with root `<CoverageSession>` and `<Summary numSequencePoints=... visitedSequencePoints=... />`. An LCOV report is a file ending in `.info` or `.lcov`, such as `lcov.info`, that starts with an LCOV record. An LCOV report whose source files are mostly Dart files is shown as Flutter.
4. Tool and dependency folders are never searched (`.git`, `node_modules`, `.gradle`, `.m2`, `.dart_tool`, `.pub-cache`, `.symlinks`, `Pods`, `.angular`, `.next`, `.yarn`, `.cache`, `venv`, `__pycache__` and the like), nor are `DevSecOpsJenkinsLibrary`, `appscan-source-staged`, `.appscan-logs`, `.scannerwork` and the Jenkins folders whose name ends in `@tmp`, `@script` or `@libs`.
5. Services that are tested in the same folder each take the reports of their own build tool: a JaCoCo report below a `target` folder belongs to a Maven service, one below a `build` folder to a Gradle service, a Cobertura or OpenCover report to a `dotnet` / `msbuild` service, a Dart LCOV report to a Flutter service and any other LCOV report to a service with the Node.js build. A report that no service of the folder owns, or that two of them own, counts for each of them, and its row says `also counted for` the others. A service that names its reports in `coverage.reportPath` counts only those, so a row names it only for a report it names. When its paths name no report, it searches like the others, but the rows of the others do not name it. Give such services their own test folders, or name their reports in `coverage.reportPath`.

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

The search is one `python3` step per service on Linux and macOS agents. When `python3` is missing, or the search step fails or takes longer than 15 minutes, the log says so and the pipeline reads the reports via fallback: the paths of `coverage.reportPath` (with `findFiles` for a pattern), otherwise `total_lcov.info` for Flutter, the usual Cobertura/OpenCover paths for `dotnet`/`msbuild` (`**/coverage.cobertura.xml`, `**/coverage.opencover.xml`, `TestResults/coverage.cobertura.xml`), or the usual JaCoCo paths of the build tool, then every matching XML report of the workspace; see **Coverage report detection** in section 19. On Windows agents the fallback uses PowerShell `[xml]` parsing natively for JaCoCo, Cobertura, and OpenCover reports without requiring `python3`.

A compilation error of the artifact build is a real failure and stops the pipeline, because nothing can be scanned or deployed without an artifact.

The Nexus IQ GoldenFix pipeline runs a **Build artifact** stage in place of the unit tests: it builds every project with the command of the `build` section, so that Nexus IQ evaluates what was built, and runs no unit test command and no coverage check. A build command that runs the tests, such as Gradle `build` or Maven `verify`, still runs them, and a build error or a failing test stops that pipeline in the same way.

Both stages run after the Node.js build, in the same workspace, so the Gradle or Maven build can package the output of the frontend.

### Stages 2 to 5 at the same time: Static analysis

By default ([the configuration reference](#16-configuration-reference-devsecops_configyaml)) the full and security pipelines run stages 2, 3, 4 and 5 in the Jenkins stage **Static analysis**, as four parallel branches, and show the four stages as skipped; with `staticAnalysis.parallel: false` they run the four stages one after another and show **Static analysis** as skipped. The console starts the stage with "\[STATIC\] Unit tests, Nexus IQ, SAST and SonarQube run at the same time (staticAnalysis.parallel: true, the default)".

1. The workspace is copied to `<WORKSPACE_TMP>/dso-sast-workspace` with `tar`, without the AppScan client and its logs (step "Copy the workspace for the SAST compile and scan").
2. Four branches start at the same time:
   - **Unit tests**, stage 2, in the workspace, with the coverage gate.
   - **Nexus IQ**, stage 3, in the workspace, with GoldenFix.
   - **SAST**, stage 4: the AppScan client in the workspace, then the compile, the IRX archive, the upload and the queued scan of every service in the copy, the removal of the copy (step "Remove the SAST copy of the workspace"), and the wait for the scans, the reports and the policy in the workspace.
   - **SonarQube**, stage 5, in the workspace. It starts a service once the unit tests of every service with the same source folder have written their coverage ("\[STATIC\] gui: SonarQube waits until the unit tests of gui, api have written their coverage"), or once the unit tests branch has ended.
3. The run waits for the four branches, then the static security pull request is raised as one after another.

Each branch has its own current service and stage, so the console lines, the stage results and the findings of every service stay with their stage; the report, the InfluxDB lines and `release-gate.json` list them in the order of stages 2 to 5, with the same results as one after another. The console ends the stage with the time one after another and in parallel: "\[PARALLEL\] Static analysis: one after another 41m 5s, in parallel 23m 10s - 17m 55s (44%) saved"; the time SonarQube waited for the unit tests is not counted as its work.

| Where it fails | What happens |
|---|---|
| One branch stops with an error | Its stage is red; the other branches run to their end and keep their results |
| SAST before every scan is queued | SAST is red; the unit tests, Nexus IQ and SonarQube are not touched, because SAST builds in its copy |
| The SAST results: a failed or timed-out scan, the download or the policy | SAST is red; the other three keep their results, where one after another SonarQube would be blocked |
| An abort | Every branch stops |

In every case **Static analysis** is red, the later stages are skipped, and the report and the release gate are still written.

When the workspace cannot be copied, the console says why in a `[WARN]` line and the stage falls back to the earlier order: SAST queues the scan of every service first, the unit tests, Nexus IQ and SonarQube run one after another while AppScan scans in its cloud, and SAST reads the results last ("\[STATIC\] SAST queues the scan of every service first; Unit tests, Nexus IQ and SonarQube then run one after another while AppScan scans, and SAST reads the results last"). In that order a SAST failure before every scan is queued stops the stage with "\[STATIC\] SAST stopped while it prepared its scans: Unit tests and Nexus IQ do not run in this build", the SAST staging copy `appscan-source-staged` is removed once every scan is queued, and a service whose SAST compile differs from its build command keeps the four stages one after another.

Restart from Stage at **Static analysis** fails as a restart at stage 2, 3, 4 or 5 does: the Monitor stage is skipped, so no service is loaded.

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

A Golden Version that changes more than the patch version is therefore never taken. When Nexus IQ proposes no patch-level version at all, the dependency is left untouched, the console says `Nexus IQ offers only <version>, which changes more than the patch level (only the last number of the version may change), left untouched`, and the card lists it under the upgrades that were not applied automatically. A version with more than three numbers, such as the .NET `1.2.3.4`, may change only its last number, and a pre-release (`-rc1`, `-beta`, `-SNAPSHOT`) is never proposed. Each manifest applies the same rule to the version it declares, so a declaration whose leading numbers differ from those of the proposal stays as it is.

GoldenFix takes the name of a pub component from `coordinates.name`, or from `coordinates.packageId` when there is none. This has not been checked against a real Nexus IQ report of a pub component.

#### Step 2: the manifests

GoldenFix searches the whole source tree of the repository, multi-module projects included, and skips the folders of `goldenFix.excludeDirs` (by default `node_modules`, `target`, `build`, `dist`, virtualenvs and the like, plus `obj`, `packages`, `TestResults` when NuGet fixes apply). It updates:

- **Maven:** `pom.xml` dependencies and dependencyManagement, and the `<properties>` they reference, in every `pom.xml` of the repository that defines a property of that name. A parent pom of the repository is therefore covered; a parent or BOM resolved from Nexus is not.
- **Gradle:** `build.gradle`, `build.gradle.kts` and `gradle.properties`: inline coordinates, `ext`, `extra` and `val` version variables, `ext` map literals such as `ext.versions = [jackson: '2.13.4']`, and rich versions (`strictly`, `require`, `prefer`) in the Groovy and the Kotlin DSL; in `settings.gradle` and `settings.gradle.kts` only the catalogs of the `versionCatalogs` block, so a `buildscript` classpath or a plugin version there stays as it is; and the catalogs described under **Shared version catalogs** below.
- **npm:** `package.json` dependencies, devDependencies, peerDependencies and optionalDependencies (range operator preserved), and the entries of `overrides` (npm) and `resolutions` (yarn) that pin the package itself; a nested override is left alone.
- **pip:** `requirements*.txt`, `constraints*.txt`, `pyproject.toml` (PEP 621 and Poetry)
- **NuGet (C# / .NET):** SDK-style project files (`*.csproj`, `*.fsproj`, `*.vbproj`) with `<PackageReference>` attributes (`Version` / `VersionOverride`) or nested `<Version>` elements, Central Package Management (`Directory.Packages.props` `<PackageVersion>`), shared MSBuild props (`Directory.Build.props`), MSBuild `<PropertyGroup>` `$(PropName)` property references resolved across project and props files, and legacy `packages.config` (`<package id="..." version="..." />`)
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

#### Step 3: the pre-check build and unit tests

GoldenFix builds the project with the new versions and runs its unit tests before it proposes anything, so a pull request never breaks the build or the tests. A failing test counts as a failed build: the versions are lowered and the build is repeated as described below. The pre-check cannot be switched off: when no build can be run for a changed file, nothing is proposed and the run ends `NOT_VERIFIED`.

A shared version catalog repository (`goldenFix.versionCatalogRepo`) is verified the same way before its pull request is raised: its own build runs when it has one (a Maven BOM, a Gradle platform or catalog build, a .NET solution), otherwise the application is built in a worktree of its own with the changed catalog files copied over its copies of them. When neither is possible, or the build fails, no pull request is raised in the catalog repository. **Every ecosystem whose manifest was changed is verified**, not only the main build tool of the service, and each check runs where the change takes effect, once for each kind of manifest and folder:

**Sign-in to the shared version catalog repository.** `goldenFix.versionCatalogRepo` is the URL of the repository, or a map with `url` (`repositoryUrl` and `cloneUrl` are read as well), `branch` (default `main`), `paths` of the catalog files, `credentialsId` and `authType`. The clone signs in the way the push of the GoldenFix branch does: an SSH URL uses the SSH key credential through `sshagent`; an HTTPS URL with `authType: bearer` (or `token`) uses a Secret text credential as the access token; any other HTTPS URL uses a username and password credential; without a credential the clone runs with what the agent already has. `credentialsId` and `authType` of the catalog fall back to those of `scm.bitbucket` of the service. The credential never appears in the log, the URL is logged without its user part, and a clone that fails stops the upgrade of the shared catalog with the last lines of the `git` output, while the service keeps its own GoldenFix result. Each run clones into a folder of its own under the temporary folder of the build, so two services of one run do not share a checkout.

| Changed file | Where the check runs | Default pre-check |
|---|---|---|
| `pom.xml` | Its own folder | `mvn -B -q test` |
| A parent or BOM `pom.xml` of the repository, or a listed `.xml` file such as `platform/bom.xml` | The aggregator that builds it with the poms that use it: the folder of the nearest `pom.xml` whose `<modules>` reach both the shared pom and a pom that uses it. Without such an aggregator, the folder of the root `pom.xml` when the repository has one, otherwise the folder of the shared pom; there only the shared pom itself is built, not the poms that use it | `mvn -B -q test`; in the folder of a shared pom with another name than `pom.xml`, `mvn -B -q test -f '<file>'` |
| `build.gradle`, `build.gradle.kts`, `gradle.properties`, `settings.gradle`, `settings.gradle.kts` | Its own folder | `./gradlew --no-daemon --console=plain test` (or `gradle` without a wrapper; `gradlew.bat` on Windows) |
| A Gradle catalog: a `*.versions.toml`, an imported or listed catalog, an applied build file, a `java-platform` module | Every build that uses it: the folder of each `settings.gradle` that imports it; for `<root>/gradle/libs.versions.toml` the folder `<root>` when `<root>/settings.gradle` exists; for an applied file the folder of the nearest `settings.gradle` of the build that applies it; for a `java-platform` module the folder of its nearest `settings.gradle`. Without such a build the check is left out, with the console line `GoldenFix: no Gradle build found for <file> - not verified` | The Gradle pre-check above |
| `package.json` | Its own folder. When a `package-lock.json` lies next to it and `goldenFix.verify.commands.npm` is not set, `npm install --package-lock-only --ignore-scripts` refreshes the lock file first | `npm ci --ignore-scripts`, or `npm install --ignore-scripts` without a lock file, then `npm test` with `CI=true` when `package.json` has a `test` script other than the `no test specified` placeholder of `npm init` |
| `requirements*.txt`, `constraints*.txt` | Its own folder | In a new virtual environment of the temporary folder, removed afterwards: `pip install -q -r <file>`, `pip install -q pytest` and `python -m pytest -q`; no collected test (exit code 5) passes |
| `pyproject.toml` | Its own folder | As above with `pip install -q .` |
| `*.csproj`, `*.fsproj`, `*.vbproj` | Its own folder | `dotnet test <file>`, which builds the project and runs the tests of a test project; on Windows with `MSBuild.exe`: `nuget restore` & `MSBuild.exe <file> /t:Build /nologo /v:minimal`, a build without tests |
| `Directory.Packages.props`, `Directory.Build.props` | Its own folder | `dotnet test`, the solution or project of that folder; on Windows with `MSBuild.exe`: `MSBuild.exe /t:Restore,Build /nologo /v:minimal`, a build without tests |
| `packages.config` | Its own folder | On Windows: `nuget restore <file> -NonInteractive` & `msbuild /t:Build /nologo /v:minimal`; on Linux: `nuget restore <file> -NonInteractive`; no tests |
| `pubspec.yaml` | Its own folder | `flutter pub get`, then `flutter test` when a `test` folder exists; `dart` instead of `flutter` when `pubspec.yaml` does not name `sdk: flutter` |

A refreshed `package-lock.json` is committed with the change, the notes say `<folder>/package-lock.json refreshed`, and the lock file note of the pull request leaves `package-lock.json` out. While `build.node.enabled` is true for the service, the npm check, the refresh included, runs with the Node.js, the registry or `.npmrc` and the environment of its Node.js build (stage 0); otherwise with the `npm` of the agent.

Each command is replaceable per ecosystem under `goldenFix.verify.commands`, for example `commands: { npm: "npm ci --ignore-scripts && npm run build && npm test -- --watch=false" }` to also compile an Angular application, or `commands: { maven: "mvn -B -q verify" }` to run the integration tests as well. The unit tests run with the default timeout of `goldenFix.verify.timeoutMinutes` (20 minutes) for each check, so raise it for a slow test suite. On a Windows agent with `MSBuild.exe`, a .NET project is built without its tests, because the classic `vstest.console.exe` run needs the test assemblies of `tests.unitTests.dotnet.testAssemblies`; set `goldenFix.verify.commands.nuget` to run them, or let the agent use `dotnet test`. A Maven command set there gets the same `-f '<file>'` at its end in the folder of a shared pom with another name than `pom.xml`, so write it as one `mvn` call, such as `mvn -B -q -DskipTests verify`. An npm command set there runs as it is: no lock file refresh runs in front of it and `package-lock.json` is not committed, so the pull request keeps `package-lock.json` in its lock file note. A command that needs a current lock file refreshes it itself, for example `npm install --package-lock-only --legacy-peer-deps --ignore-scripts && npm ci --legacy-peer-deps --ignore-scripts`; to have the refreshed lock file in the pull request, leave `goldenFix.verify.commands.npm` empty. When a build fails while a declaration outside a catalog carries the version of the catalog (see **Shared version catalogs**), the next attempt builds that declaration with the Nexus IQ version instead, and the later attempts keep it so; the note `declared outside the catalog` stays. That build comes on top of `goldenFix.verify.maxAttempts`, so the lower versions get as many builds as without a catalog, and the console counts one build more, for example `Pre-check build 2 of 4`. The console lines `[GOLDENFIX] Pre-check build <n> of <m> for ...` name the versions that were built. When a build fails otherwise, every proposed version is lowered to the next lower one Nexus IQ offers, the worktree is reverted, the manifests are updated again and the build is repeated, up to `goldenFix.verify.maxAttempts` times (default 3). A dependency that runs out of lower versions is dropped from the change set and listed as not applied, so its vulnerability stays visible. If no version set builds, **no pull request is opened** and the run ends with `BUILD_FAILED`. Each check may take `goldenFix.verify.timeoutMinutes` (default 20). The pre-check cannot be switched off: without a passing build no pull request is opened.

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

Downloads and unpacks SAClientUtil, which compiles every project and generates its IRX archive. Everything else goes through the AppScan on Cloud REST API v4 with `curl` (`curl.exe` from a batch step on Windows agents): the IRX of each project is uploaded (`FileUpload`), all projects are queued for the scan (`Scans/Sast`), and the results are then read one by one: the scan status is polled until it is `Ready`, and the HTML and the PDF report are generated and downloaded. The pipeline signs in once with the API key of the functional user from the library defaults (`asoc.keyId` and the secret of `asoc.token`), keeps the bearer token for the next calls and signs in again only after 90 minutes; the request body with the secret is written to the temporary folder of the build and removed after the call, so the secret never appears on a command line or in the console, and the proxy password and the token are not echoed. The scan is named `sast.scanName` when it is set, otherwise `<scan name>-sast`, where the scan name comes from `tools.sonar.projectName` or the service name; the file names keep the scan name. The reports leave out the issues with the `Noise` status, the HTML report of each project is parsed without them, and the counts are compared with `sast.maxCritical`, `sast.maxHigh` and `sast.maxMedium` of the bundled defaults (all 0). Above the limits the stage turns orange with the counts, and the release and QC stay blocked.

After a Node.js build in the same run, the folders that an IRX archive leaves out also hold `node_modules`, so the installed npm packages are not scanned as source code. This applies to every IRX archive of the run, unless included folders (`includedDirs`) are set or `node_modules` is already among the excluded folders (`excludedDirs`). Without a Node.js build nothing changes. The rule has limits:

- With included folders set, only those folders are scanned and the excluded folders do not apply.
- On Windows agents the IRX is prepared from the included folders with `robocopy`, which leaves out only test folders.
- With `asoc.useAppScanConfig: true` the `appscan-config.xml` of the repository decides what is scanned, and the excluded folders do not apply.
- The output of the frontend build, such as the minified bundles in `dist`, is scanned like any other file.

In the **Static analysis** stage the staging copy `appscan-source-staged` (Windows: `sast_directory`) is removed once every scan is queued, so Nexus IQ and SonarQube do not see it. The AppScan client stays in `.appscan-tools` of the workspace, as one after another.

### Stage 5: SCA (SonarQube)

Runs the SonarQube analysis and waits for the quality gate. It then queries the SonarQube API for the open **vulnerabilities** of the project and maps them to the report counts: Blocker and Critical become critical, Major becomes high, Minor becomes medium and Info becomes low. Both results are enforced: a failed quality gate and vulnerability counts above `sca.maxCritical`, `sca.maxHigh` and `sca.maxMedium` of the bundled defaults (all 0) turn the stage orange with the reason and block the release and QC, without stopping the pipeline.

#### How the analysis runs

The runner follows the settings of the service; a setting that is given wins, and what is missing falls back to the default:

- **A Gradle, Android or Maven service with analysis commands of its own** (`tools.sonar.gradle.tasks`, for Android as well, or `tools.sonar.maven.goals`) runs them with the plugin of its build. The plugin keeps the project key of the build; `-Dsonar.projectKey` is added only when the auto-resolve filled in `tools.sonar.projectKey`.
- **Every other Gradle, Android and Maven service, and every npm, pip and iOS service** is analysed with the `sonar-scanner` CLI: the Jenkins tool `sonar-scanner-cli-<version>` named in `tools.sonar.sonarScannerVersion`, otherwise `sonar-scanner` on the `PATH` of the agent. The CLI reads `sonar-project.properties` (below) and gets, when they are set, `tools.sonar.<tool>.flags` (for npm also `tools.sonar.node.flags`; of a Gradle, Android or Maven block only the arguments that start with `-Dsonar.`, since the other flags belong to the build tool), `tools.sonar.<tool>.sources` or `tools.sonar.sources` as `-Dsonar.sources`, `coverage.xml` of a pip service as `-Dsonar.python.coverage.reportPaths` and `coverage/lcov.info` of an npm service as `-Dsonar.javascript.lcov.reportPaths` and `-Dsonar.typescript.lcov.reportPaths`. An argument of the service wins over the same argument the library would add.
- **Flutter, Liquibase and .NET services** run as the chapters of these build tools describe.

**The branch and the retry without it.** The branch of the build is passed on the command line as `-Dsonar.branch.name`: `tools.sonar.branch` when it is set, otherwise `CHANGE_BRANCH`, `BRANCH_NAME`, `GIT_BRANCH`, the branch of `scm` and at last the branch of the checkout. A SonarQube server that refuses the branch name, as the Community Edition does, which analyses only the main branch, fails that attempt: the library logs a `[SONAR]` line with the reason and runs the analysis once more without the branch, and the stage fails only when this second attempt fails as well. `tools.sonar.branchAnalysis: false` skips the first attempt for such a server. The issue counts of the report are read for the branch that was analysed.

#### Automatic SonarQube Default Configuration and Branch Analysis

When a repository does not include an explicit `sonar-project.properties` file, the pipeline automatically generates and applies enterprise default configuration before invoking SonarQube (or runs the Scanner for .NET `begin`/build/`end` workflow when `buildTool` is `dotnet` / `msbuild`):

- **Default properties**:
  - `sonar.java.binaries=target/classes` (augmented with `build/classes/java/main` for Gradle builds)
  - `sonar.java.coveragePlugin=jacoco`
  - `sonar.language=java` (or detected language: `cs`, `kotlin`, `dart`, `py`, `sql`)
  - `sonar.jacoco.reportPaths=target/jacoco.exec`
  - `sonar.junit.reportPaths=target/surefire-reports`
  - `sonar.projectKey=com.bbh:<resolvedKey>` (prefixed with `com.bbh:` if not present)
  - `sonar.sources=src/main/java` (or detected sources: `lib`, `db`, `src/main/kotlin`, `src`, `.`)
  - `sonar.sourceEncoding=UTF-8`
  - `sonar.tests=src/test/java` (or detected test suites)
  - `sonar.dynamicAnalysis=reuseReports`
  - `sonar.modules=<detected modules>` (automatically detected from `pom.xml` module elements or `settings.gradle` includes)
  - **.NET Scanner parameters**: `SonarScanner.MSBuild.exe` / `dotnet sonarscanner` automatically attaches `/d:sonar.cs.opencover.reportsPaths`, `/d:sonar.cs.vscoveragexml.reportsPaths`, and `/d:sonar.cs.vstest.reportsPaths` when reports exist.
- **Branch-specific scanning**: SonarQube analyzes the active pipeline branch (`CHANGE_BRANCH` in Pull Request builds, or normalized `BRANCH_NAME` / `GIT_BRANCH`) through `-Dsonar.branch.name` on the command line, so a scan never falls back to `master` or `main` by itself; a `sonar-project.properties` of the repository is left as it is. When the server refuses the branch, the analysis runs once more without it (see **How the analysis runs**).

### Bitbucket Pull Request Decoration and Shift-Left Feedback

To empower developers to review and remediate security defects directly within their daily Bitbucket code review workflows, the pipeline provides automated inline Pull Request decoration:

#### Static Security Pull Request (Single Consolidated PR)

During the static security pipeline, all findings and remediation changes are consolidated into a single Bitbucket Pull Request:

- **Nexus IQ GoldenFix**: Automatically applies safe dependency upgrades to manifests (`pom.xml`, `build.gradle`, `package.json`, `requirements.txt`, `pubspec.yaml`, `*.csproj`, `*.fsproj`, `*.vbproj`, `Directory.Packages.props`, `Directory.Build.props`, and `packages.config`) on branch `GoldenFix-YYYYMMDDHHMM`.
- **HCL AppScan SAST**: Reads the open findings of the scan from the AppScan on Cloud REST API (`GET /api/v4/Issues/Scan/<scan id>`), leaves out the issues with the status `Noise`, `Passed` or `Fixed`, and reads the fix that AppScan recommends for every issue type from the API as well (`GET /api/v4/Issues/<issue id>/Details`). Neither the PDF nor the HTML report is parsed for the comments. Each finding becomes a comment with the severity, the issue type, the place, the CWE and **Recommended fix (AppScan)**.
- **SonarQube**: Fetches open code quality, vulnerability, and bug issues and decorates the pull request with line comments on the specific source lines.

#### Pull request of the analysed branch, limits and repeated runs

The decoration runs for every service of the run, and also when the service has no GoldenFix pull request:

- **Where a comment goes.** The library reads the files that the pull request changes from Bitbucket. A finding in a changed file is a comment of that file, under the line of the finding; when Bitbucket does not take a comment on that line, it becomes a comment of the file that names the line. A finding in a file that the pull request does not change is written in one comment of the pull request per file, which names the file and the line of every finding in it. When the changed files cannot be read, every finding with a line is tried as a line comment and the console says so in a `[WARN]` line.
- **Nexus IQ.** AppScan knows only the findings of its own scans, so the fix of a Nexus IQ finding comes from Nexus IQ: the version GoldenFix chose. In the pull request of the developer every upgrade is a comment under the line of the manifest that declares the dependency when the pull request changes that manifest, otherwise a comment of the pull request that names the manifest; the dependencies that GoldenFix left untouched are listed with the version Nexus IQ offers and the reason.
- **When the API gives no findings.** If the list of issues cannot be read from the AppScan API, the console names it in a `[WARN]` line and the comments are built from the findings of the HTML report of the scan, as before. When AppScan returns no fix recommendation for an issue type, the comment says so and points to the issue in AppScan.
- **The pull request of the developer.** In a pull request build (`CHANGE_ID`) or when the analysed branch has an open pull request in Bitbucket, that pull request gets the comments of the SAST, Nexus IQ and SonarQube findings and one summary comment with the number of dependency upgrades, SAST findings and SonarQube issues. Branches that the library creates itself (`GoldenFix-*`, `security-*`) are left out.
- **Limits.** `pullRequestDecoration.maxComments` (default 25) is the number of comments one run adds to one pull request, line comments and comments for unchanged files together; the findings beyond it stay in the report, and the log says so. `pullRequestDecoration.sastMinSeverity` (default `MEDIUM`) and `pullRequestDecoration.sonarMinSeverity` (default `MAJOR`) leave out the findings below them. A value that is not a number or not a severity falls back to its default with a `[WARN]` line, and a value equal to the default is named in an `[INFO]` line as a key that can be removed.
- **Repeated runs.** Before it comments, the library reads the comments that are already on the pull request and adds only those that are missing, so a second run of the same commit adds no duplicate; when the comments cannot be read, it comments as on a first run.
- A comment that Bitbucket refuses is logged as `[SECURITY-PR]` and does not fail the stage.

#### DAST Dynamic Security Pull Request (Dedicated PR)

During dynamic security testing (DAST in enhanced pipelines), when policy violations are detected or `dast.createPullRequest: true` is set, a dedicated second Pull Request (`security-dast-<project>`) is opened. This cleanly segregates operational and runtime infrastructure defects (e.g. HTTP headers, SSL/TLS configurations, cookie flags) from source code dependency upgrades and static flaws.

### Stages 6–10: Delivery and tests

Stage 6 publishes the snapshot artifact and stage 7 deploys to the lower test region. Both always run, even when an earlier stage is orange, so developers always get a build on the lower test region.

A service with `delivery.nexusDeliveryEnabled: false` (or, without that key, `deploy.<target>.rd.nexusDeliveryEnabled: false`) is not published to Nexus: stage 6 still reports its build and unit tests and logs `[NEXUS] <service>: Nexus delivery is disabled by <setting>`, and the stage is `NOT_REQUIRED` when Nexus delivery is off for every service of the run. An OpenShift service without delivery has no image of this run: RD deploys a pinned `deploy.openshift.rd.buildTag`, and QC its own `deploy.openshift.qc.buildTag` or, without it, the RD tag. A region without a tag is skipped as not required with an `[OPENSHIFT]` line, and its deployment stage is `NOT_REQUIRED` when every service of the run skipped it. The release delivery of stage 12 is not affected.

What stage 6 does depends on the project. A Maven project on a VM is uploaded to the Nexus snapshot repository with `delivery.push.maven` (or, without it, the single `build.buildPath` upload of `delivery.maven`), a Gradle project on a VM with `delivery.push.gradle`; a Gradle project without `delivery.push.gradle.artifacts` is not required in stage 6. Every service of the run is uploaded under one snapshot version, `<BUILD_NUMBER>-<yyyyMMdd-HHmmss UTC>-SNAPSHOT`, and the tag without `-SNAPSHOT` is written to `build.tag`, which the security pipeline archives for its extended pipeline. A .NET project pushes its `*.nupkg` with `delivery.nuget`, a Flutter project its APK and IPA. An OpenShift project has its image built in the build namespace, from the artifact at `build.buildPath` or, when it is not there, the first file named `artifactName` in the workspace (`baseArtifactName` is renamed to it wherever it is), with every file of `addFile` in the build context, and copied to the Nexus docker registry with `skopeo`.

The extended pipeline then downloads the snapshot of its security run in the stage **Download artifacts from Nexus Repo**: a VM service with `delivery.pull` gets the files of `delivery.pull.maven` or `delivery.pull.gradle` of the version in `build.tag` in `delivery.pull.outputDirectory`. An OpenShift service, a Liquibase service with a database block (its deployment fetches the delivered package itself, see chapter 14), a service without Nexus delivery and a service without `delivery.pull` are not required in that stage; a `delivery.pull` without its group, artifact, goals or artifacts stops it with a message that names the missing keys, and so does a run without `build.tag`. Stage 7 deploys with UrbanCode Deploy (`devSecOpsPipeline` and `devSecOpsExtendedPipeline`, `deploy.vm.dod`) or to OpenShift (`deploy.openshift.rd`).

Then, by default, the regression, smoke and performance tests and DAST run at the same time in **Dynamic analysis** ([below](#17-pipeline-stage-reference)); with `dynamicAnalysis.parallel: false` they run in the order regression, smoke, performance, followed by DAST. Each test stage needs at least one configured job.

Every test stage runs **all** configured jobs, even when some of them fail, and then turns orange with a summary of the failed jobs. A stage may contain any number of jobs (e.g. 200 smoke tests on different remote Jenkins instances). At most `tests.maxParallel` (default 20, per-stage override `tests.<type>.maxParallel`) jobs run at the same time. Remote jobs can be referenced by full job URL (`url:` or `job: https://...`) without a Remote Jenkins global configuration; `credentialsId` provides user + API token. The HTML report shows per-project counters (total / passed / failed / not configured), the failed jobs, a collapsible list of all jobs and a **Smoke tests** table with a build link for every job.

### Stages 8 to 11 at the same time: Dynamic analysis

By default ([the configuration reference](#16-configuration-reference-devsecops_configyaml)) the full and extended pipelines run stages 8, 9, 10 and 11 in the Jenkins stage **Dynamic analysis**, as four parallel branches: the regression tests, the smoke tests, the performance tests and DAST. Jenkins shows the four stages as skipped; with `dynamicAnalysis.parallel: false` it runs them one after another and shows **Dynamic analysis** as skipped. The console starts the stage with "\[DYNAMIC\] Regression, Smoke and Performance tests and DAST run at the same time (dynamicAnalysis.parallel: true, the default)" and ends it with "\[PARALLEL\] Dynamic analysis: one after another …, in parallel …". Each test stage keeps `tests.maxParallel` for its own jobs. A branch that fails is red, the other branches run to their end, and **Dynamic analysis** is red.

### Stage 11: DAST – HCL AppScan

Runs only for projects with `dast.enabled: true`. Signs in to the AppScan REST API with the API key of the functional user from the library defaults (`asoc.keyId` and the secret of `asoc.token`), reusing the token of SAST when the run already holds one, and queues a DAST scan against `dast.targetUrl`. Uses optional `dast.presenceId` for scanning internal (non-internet-accessible) environments.

The report is downloaded twice: as HTML (parsed for the vulnerability counts) and as **PDF**. Both are archived as build artifacts and linked from the pipeline report: in the DAST stage box and in the Security Gates table, which offers three entries, the HTML report, the **HCL AppScan** console page of the scan (under `asoc.url`, otherwise `APPSCAN_SERVER_URL`) and the PDF report. Above the DAST thresholds of the bundled defaults the stage turns orange with the critical, high and medium counts, and the release and QC stay blocked.

### Stage 12: Nexus delivery (safe artifact)

This is the second delivery to Nexus, into the release repository. It is skipped as soon as **any** earlier stage is orange, so a build with open findings, failed tests or missing coverage is never released. A .NET service with `delivery.nuget.source` pushes its `*.nupkg`; for every other service the release delivery of the safe artifact is not implemented yet, so the service is not required with a `[NEXUS]` line and the snapshot of stage 6 stays the delivered artifact. The stage no longer runs `./gradlew publish` or `mvn deploy`.

In the extended pipeline, which has no stage 0, this stage first runs the Node.js build of each service with `build.node.enabled: true`, exactly as stage 0 would, when a release delivery is available for a service of the run (the NuGet push); while every service is not required, no Node.js build runs. The full pipeline built the frontend in stage 0 of the same workspace and does not build it twice.

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

Each pipeline evaluates the results it owns and hands its verdict to the next one through the archived `release-gate.json`, so a security pipeline with an orange stage also blocks the release in the extended pipeline. A pipeline reads that file once, before it writes its own, so every violation of the previous pipeline is listed once with `(previous pipeline)` and the violations of the run itself are never counted twice.

### The time of the parallel stages and of the manual steps

Every report of the full, security, extended, SAST and Nexus IQ GoldenFix pipelines ends with the table **Manual Actions vs DevSecOps**, recalculated on every run from the stage durations of that run. It has one line per step, in the order the pipeline runs them. A line appears only for the steps that ran in this run, so each pipeline shows its own steps; a stage that was skipped or not required is left out, except the artifact build, which builds also when its Node.js part is not required.

| Column | What it shows |
|---|---|
| Step | The name of the step and, next to it, the time of its own run, without the time it waited for another step |
| Manual actions | The time of the run plus the minutes a person needs to start the step by hand or to raise its ticket |
| DevSecOps Automated Actions | The time the pipeline needed. Of the steps that ran at the same time only the longest has a time; the others say within which step they ran. After a run one after another (`staticAnalysis.parallel: false` or `dynamicAnalysis.parallel: false`) every step has its time |
| Time gained | The share of the manual time the pipeline saved: for a single step of its own time, for the steps of a parallel group of the group together |

| Step | Added in Manual actions | Setting under `report.manualEffort` |
|---|---|---|
| Build (and Node.js build) | 10 min manual trigger, once per run | `build` |
| Unit test with Code Coverage | nothing | `unitTests` |
| Nexus IQ | 10 min manual trigger | `nexusIq` |
| SAST | 10 min manual trigger | `sast` |
| SonarQube | 10 min manual trigger | `sonar` |
| Nexus delivery (snapshot, download, release) | nothing | `nexusDelivery` |
| Lower region test deployment | nothing | `lowerDeployment` |
| Regression tests | 10 min manual trigger | `regression` |
| Smoke tests | 10 min manual trigger | `smoke` |
| Performance tests | 10 min manual trigger | `performance` |
| DAST | 6 h ticket to CyberTrack | `dast` |
| Higher region test deployment | 0 min ticket to SRM | `higherDeployment` |

The parallel groups are **Static analysis** (unit tests, Nexus IQ, SAST, SonarQube) and **Dynamic analysis** (regression, smoke and performance tests, DAST). When SonarQube, which waits for the coverage of the unit tests, ends last, its time in the DevSecOps column includes that wait. The line **Summary** adds up the manual and the DevSecOps column and shows each in seconds and minutes, with the time gained in percent and in seconds and minutes. Nothing is written under the table. When more than one service is configured, every step lists all configured services under its name, each with the time the step took for it, or *not run for this service*.

The minutes are `report.manualEffort` of the bundled defaults, which only the DevSecOps team changes; a value that is not a whole number of minutes is named in a `[WARN] [REPORT]` line and its default is used. The former names `buildTrigger`, `scanTrigger` (Nexus IQ and SonarQube), `deployTrigger` (lower region deployment) and `testTrigger` (regression, smoke and performance tests) are still read when the new name is not set, each with a `[WARN] [REPORT]` line that asks to rename it.

## 18. Security Policy Thresholds

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
| Unit tests, Nexus IQ, SAST and SonarQube at the same time | staticAnalysis.parallel | true; the bundled defaults leave the key out | yes: false in any service of `devsecops_config.yaml` or `config.yaml` runs them one after another |
| Tests and DAST at the same time | dynamicAnalysis.parallel | true; the bundled defaults leave the key out | yes: false in any service runs them one after another |
| Minutes of the manual steps in the report | report.manualEffort | 10 min manual trigger of the build, Nexus IQ, SAST, SonarQube and each test, 360 min for the CyberTrack ticket of a DAST, 0 for the other steps | no |

The required coverage is shown in the report exactly as configured in the bundled defaults, so a library release that raises or lowers it changes the value the report checks against and displays from the first build that loads it.

## 19. Supported Build Tools

| Tool | Build | Unit tests | Coverage | Release publish |
|---|---|---|---|---|
| Gradle | `./gradlew` + `build.gradle.tasks` and `flags` | `./gradlew` + `tests.unitTests.gradle.tasks` | JaCoCo XML and LCOV | `./gradlew publish` |
| Maven | `mvn` + `build.maven.goals` and `flags` | `mvn` + `tests.unitTests.maven.goals` | JaCoCo XML and LCOV | `mvn deploy` |
| C# and .NET (`dotnet` / `msbuild`) | Windows default: `NuGet.exe restore` & `MSBuild.exe`; CLI/Linux: `dotnet restore/build/publish` | Windows default: `vstest.console.exe` (+ optional `OpenCover.Console.exe`) with `.trx`; CLI/Linux: `dotnet test` | Cobertura XML and OpenCover XML | `nuget.exe push` / `dotnet nuget push` |
| Flutter | `flutter build <platform>` | `flutter test --coverage --concurrency=32 --reporter expanded` in the main package and in each of `tests.subplugins` and `tests.submodules` | LCOV of each package | Nexus upload of the APK/IPA |
| Liquibase (SQL / PL/SQL) | `liquibase` + `build.liquibase.commands` (default `updateSQL` from the auto-resolve, `validate` without it) and `flags` | `liquibase` + `tests.unitTests.liquibase.commands`, when set | – (the coverage requirement is skipped); with a database block: the utPLSQL Cobertura report | –; with a database block: the zip to Nexus and the RD and QC deployment ([chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase)) |
| SQL and PL/SQL files (`sql`) | the library writes `dso-changelog.xml`, then the offline validation, the static checks, the review SQL and the zip of a database block | the deployment to the test database and utPLSQL | the utPLSQL Cobertura report | the zip to Nexus and the RD and QC deployment ([chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase)) |
| Node.js (stage 0, optional) | `build.node.commands`, by default `npm ci` and `npm run build` | – | – | – (the Gradle or Maven build packages the output) |

Every command comes from the settings of the service; the table shows where the library reads it. The build tool is `buildTool` of the service in `devsecops_config.yaml`; when it is empty, the library detects it by file existence (`pubspec.yaml` → Flutter, `pom.xml` → Maven, `build.gradle` → Gradle, `*.sln` / `*.csproj` / `*.fsproj` / `*.vbproj` / `Directory.Packages.props` / `packages.config` → C#/.NET `dotnet`, `liquibase.properties` / `changelog.xml` / `changelog.yaml` / `changelog.json` / `changelog.sql` → Liquibase, `requirements.txt` / `pyproject.toml` / `setup.py` / `Pipfile` → Python, and only SQL and PL/SQL files → `sql`). The full lists and the order with the auto-resolve of the settings on and off are in [chapter 14](#14-integration-guide-sql-and-plsql-with-liquibase), 14.1: the application build files come before the Liquibase files, so a Maven or Gradle application that ships a Liquibase changelog stays a Maven or Gradle service.

Gradle always runs through the `./gradlew` wrapper, which the library makes executable. Maven uses the installation of `build.maven.mvnPath` when it is set, otherwise `./mvnw` when the wrapper is committed, otherwise the `mvn` on the agent `PATH`.

The JDK comes from `javaPath` of the project, which the library exports as `JAVA_HOME` and prepends to `PATH`. With `buildToolAutoSetup: true` the library instead reads the required Java version from `pom.xml` or `build.gradle` and searches the agent for a matching JDK. For `buildTool: dotnet` / `msbuild` and standalone `buildTool: liquibase` services, `javaPath` is never required.

A failing unit test run does not stop the pipeline: the stage turns orange, the coverage of the reports that were produced is still checked, and the release gate blocks the Nexus release and the QC deployment.

### Gradle and Maven plugins from the BBH Nexus

The agents reach only the BBH network, so a Gradle plugin that the build takes from the Gradle Plugin Portal, or a Maven plugin that is not in the repositories of the build, is not found. The library then takes the plugin from the BBH Nexus by itself, in every Gradle and Maven step of a service: the build, the unit tests, the SonarQube analysis and the SAST compile.

1. The step runs as the service sets it. When it succeeds, nothing else happens, so a build that finds its plugins is not changed.
2. When it fails, the library asks the build which plugins it misses: `./gradlew --console=plain help`, or `mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:resolve-plugins`, each with the `-D` and `-P` flags of the step. Gradle names a missing plugin with `Plugin [id: '<id>', version: '<version>'] was not found` or a missing module of the `classpath` configuration, Maven with `Plugin <group>:<artifact>:<version> or one of its dependencies could not be resolved`, `Failed to retrieve plugin descriptor for ...` or `No plugin found for prefix '<prefix>'`. Any other failure is reported as before.
3. For missing plugins the console says `[WARN] [BUILD] Gradle did not find the plugin(s) <plugins> in the repositories of the build - trying again with the plugin repository <url> of the BBH Nexus`, and the step runs once more: Gradle with `--init-script <workspace>@tmp/dso-plugin-repositories/bbh-nexus-plugins.gradle`, which puts the repository first in `pluginManagement.repositories` of the settings and in the `buildscript` repositories of every project (and keeps the Gradle Plugin Portal when the settings name no repository); Maven with `-s <workspace>@tmp/dso-plugin-repositories/bbh-nexus-plugins-settings.xml`, a copy of `~/.m2/settings.xml` of the agent, or a new file, with an active profile `bbh-nexus-plugins` that adds the repository to `<pluginRepositories>`. The settings copy is removed after the step.
4. When the second run succeeds, the console says `[WARN] [BUILD] The plugin(s) <plugins> came from <url> of the BBH Nexus: add the repository to pluginManagement { repositories { ... } } of settings.gradle so that the first attempt does not fail` (`<pluginRepositories> of pom.xml` for Maven), and every later Gradle or Maven step of the same folder in the build uses the repository at once. When it fails too, the step fails with the error of the second run.
5. A Maven step whose flags name its own settings file (`-s`, `--settings`) is not run again: the console names the missing plugins and the repository to add to that file.

The repositories are `pluginRepositories` of the bundled defaults, which the DevSecOps team keeps; a service may set its own values or switch the retry off:

```yaml
pluginRepositories:
  enabled: true                                                    # false: a failing step is never run again
  gradle:  "https://tools.bbh.com/nexus/repository/gradle-plugins/" # empty in the bundled defaults: no retry for Gradle
  maven:   "https://tools.bbh.com/nexus/repository/maven-public/"   # empty in the bundled defaults: no retry for Maven
```

Each repository falls back on its own. A service that leaves `gradle` or `maven` empty gets the repository of the bundled defaults. An address that is not a plain http or https address of letters, digits and `. _ / + @ : = , ~ % -` is not used, because the library writes it into a Gradle init script and a Maven settings file: when the step needs it, the console says `[WARN] [BUILD] pluginRepositories.gradle: <value> is not an http or https address of letters, digits and . _ / + @ : = , ~ % - only - the library default <url> is used instead`, and the step runs again with the repository of the bundled defaults.

The Nexus delivery of stage 6 and stage 9 runs a Gradle or Maven project of the library and does not retry.

### C# and .NET (Auto-Detection, Classic MSBuild + NuGet, .NET SDK CLI, Hybrid, Cake & NUKE)

The library provides full first-class support for C#, F#, and VB.NET applications across .NET Framework 4.x, .NET Core, and .NET 6–9+ on **Windows Jenkins agents** as well as cross-platform SDK builds on Linux agents:

- **Smart Auto-Detection & Validation (`mode: auto` by default):**

When `build.dotnet.mode` (or `useDotnetCli`) is omitted in `devsecops_config.yaml` or `config.yaml`, `DotnetBuildRunner` and `RepoStackAutoResolver` automatically inspect the project files and agent environment: \*\* **`dotnet` (SDK CLI mode):** selected automatically on Linux agents, or on Windows when SDK-style project files (`<Project Sdk="...">` targeting `net5.0`+ / `netcoreapp` / `netstandard`), `global.json`, or `Directory.Packages.props` are present without legacy .NET Framework projects or explicit MSBuild/OpenCover flags. \*\* **`msbuild` (Classic Windows toolchain):** selected automatically on Windows when `packages.config`, `<TargetFrameworkVersion>v4.*`, `net48` / `net4*`, non-SDK `.csproj` files, or explicit MSBuild/VSTest/OpenCover settings (`msbuildPath`, `vstestPath`, `opencover: true`, `sonarScannerMsbuildPath`) are present, or when `buildTool: msbuild` is set. \*\* **`hybrid` (Mixed solutions):** selected automatically on Windows when a solution combines both legacy .NET Framework (`packages.config` / `net4*`) and modern SDK-style (`net6.0`+ / `net8.0`) projects, or when `build.dotnet.mode: hybrid` is set. Runs `dotnet restore` + `NuGet.exe restore`, compiles with `MSBuild.exe`, and executes both `vstest.console.exe` and `dotnet test`. \*\* **`cake` and `nuke` build automation:** selected when `build.cake` or `.nuke` is present (or `mode: cake` / `mode: nuke`), invoking `dotnet cake` or `build.cmd` / `./build.sh` / `nuke`. \*\* **Invalid parameter fallback:** if an unrecognized value is provided for `buildTool`, `build.dotnet.mode`, `useDotnetCli`, `tests.unitTests.dotnet.runner`, or `coverageTool`, the pipeline logs a `[WARN]` message and falls back to the auto-detected default.

- **Classic Windows MSBuild toolchain (`mode: msbuild`):**

\*\* **Build (Stage 2):** runs `NuGet.exe restore` (with optional `build.dotnet.nugetConfig`) followed by `MSBuild.exe` with `build.dotnet.solution` (auto-detecting `*.sln` / `*.csproj` / `*.fsproj` / `*.vbproj` when omitted), `build.dotnet.configuration` (default `Release`), optional `platform`, and `flags`. Executable paths can be pinned with `nugetPath` and `msbuildPath` or resolved from the agent `PATH`. \*\* **Unit tests & Coverage (Stage 2):** supports `runner: vstest` (`vstest.console.exe`, default for `msbuild` mode), `mstest` (`MSTest.exe`), `nunit` (`nunit3-console.exe`), `xunit` (`xunit.console.exe`), or `dotnet` (`dotnet test`), and coverage collection via `coverageTool: opencover` (`OpenCover.Console.exe`), `dotcover` (`dotCover.exe`), `coverlet` (`/Collect:"XPlat Code Coverage;Format=opencover"`), `cobertura` (report only, not imported by SonarQube), or `none`. Visual Studio `.trx` files are parsed natively via PowerShell into unit-test metrics without Python, and Cobertura/OpenCover XML reports are checked against the coverage gate.

- **.NET SDK CLI mode (`mode: dotnet` or `useDotnetCli: true`):**

\*\* **Build (Stage 2):** executes `dotnet restore`, `dotnet build --configuration Release --no-restore`, optional `dotnet pack` (`pack: true`), and optional `dotnet publish` (`publish: true`, `publishDir`). \*\* **Unit tests & Coverage (Stage 2):** runs `dotnet test --configuration Release --logger trx --collect "XPlat Code Coverage;Format=opencover"` (Coverlet, `coverageTool: coverlet`, the default) producing `*.trx` and `coverage.opencover.xml`, which both the pipeline report and SonarQube read. `coverageTool: cobertura` writes `coverage.cobertura.xml` instead: the report counts it, but SonarQube cannot import Cobertura for C#, so the pipeline logs a `[WARN]`. `opencover` / `dotcover` wrap the test run with OpenCover.Console.exe / dotCover.exe.

- **SAST (Stage 4):** on Windows and Linux agents, when `asoc.doCompile` is not `false`, pre-compiles using `asoc.dotnet` (falling back to `build.dotnet`), stages sources cleanly with `robocopy` / `rsync` excluding `obj`, `packages`, `TestResults`, and `.git` (retaining `bin` binaries needed by .NET IRX extraction), and invokes `appscan.bat prepare`.
- **SonarQube SCA (Stage 5):** runs the Scanner for .NET three-step workflow (`SonarScanner.MSBuild.exe begin` -&gt; build -&gt; `SonarScanner.MSBuild.exe end`, or `dotnet sonarscanner` in CLI mode) inside `withSonarQubeEnv`, automatically attaching OpenCover, VS Coverage XML, dotCover, VSTest `.trx`, NUnit, xUnit, and VB.NET report parameters when matching reports exist.
- **Nexus IQ GoldenFix, Version Catalogs & Release Publish:** remediates NuGet vulnerabilities in `*.csproj`, `*.fsproj`, `*.vbproj`, central version catalogs (`Directory.Packages.props`, `Directory.Build.props`, and custom `.props` files), and `packages.config` natively on Windows or Linux agents; release delivery supports `delivery.nuget` (`nuget.exe push` / `dotnet nuget push`).

Example C# / .NET Windows classic MSBuild and SDK CLI configurations in `examples/DotNetApp/devsecops_config.yaml`:

```yaml
projects:
  orders-framework-api:
    appId:        "33333333-4444-5555-6666-777777777777"
    buildTool:    dotnet
    deployTarget: vm
    asoc:
      doCompile: true
    build:
      buildPath: "src/Orders.Api/bin/Release/**/*.dll"
      dotnet:
        solution:      "OrdersSolution.sln"
        configuration: "Release"
        platform:      "Any CPU"
        nugetPath:     "C:\\tools\\nuget\\nuget.exe"
        msbuildPath:   "C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\MSBuild\\Current\\Bin\\MSBuild.exe"
    tests:
      unitTests:
        unitTestResult: "**/TestResults/*.trx"
        dotnet:
          vstestPath:     "C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\Common7\\IDE\\Extensions\\TestPlatform\\vstest.console.exe"
          opencoverPath:  "C:\\tools\\opencover\\OpenCover.Console.exe"
          testAssemblies: ["tests/Orders.Tests/bin/Release/Orders.Tests.dll"]
    tools:
      sonar:
        projectName:             "Orders Framework API"
        projectKey:              "com.bbh.orders:orders-framework-api"
        authToken:               "sonar-auth-token"
        sonarScannerMsbuildPath: "C:\\tools\\sonar-scanner-msbuild\\SonarScanner.MSBuild.exe"
      nexusIq:
        application:  "OrdersFrameworkAPI"
        scanPatterns: ["src/Orders.Api/bin/Release/**", "**/packages.config"]
    goldenFix:
      enabled:    true
      ecosystems: [nuget, npm]
    delivery:
      nuget:
        source:         "https://tools.bbh.com/nexus/repository/nuget-hosted/"
        credentialsId:  "nexus-nuget-api-key"
        packagePattern: "**/*.nupkg"

  billing-dotnet8-worker:
    appId:     "44444444-5555-6666-7777-888888888888"
    buildTool: dotnet
    build:
      buildPath: "src/Billing.Worker/bin/Release/net8.0/publish/**"
      dotnet:
        useDotnetCli: true
        solution:     "src/Billing.Worker/Billing.Worker.csproj"
        publish:      true
        publishDir:   "src/Billing.Worker/bin/Release/net8.0/publish"
    tests:
      unitTests:
        dotnet:
          useDotnetCli: true
          coverlet:     true
    tools:
      sonar:
        projectKey: "com.bbh.orders:billing-dotnet8-worker"
        authToken:  "sonar-auth-token"
        dotnet:
          useDotnetCli: true
```

### SQL and PL/SQL with Liquibase (single-module and multi-module)

The library supports SQL and PL/SQL database migration and stored-procedure projects managed by **Liquibase** both as **standalone single-module services** (`buildTool: liquibase`, or `buildTool: sql` for SQL and PL/SQL files without a changelog) and as **multi-module / polyglot repositories** where SQL/PL-SQL lives alongside Java, Kotlin, Gradle, Maven, Node.js/NPM/Angular, or Python code.

#### 1. Standalone single-module SQL / PL/SQL service (`buildTool: liquibase`)

Set `buildTool: liquibase` when the service is a dedicated database schema, PL/SQL package, or Liquibase migration service:

- **Build (Stage 2):** runs `liquibase` with `build.liquibase.flags` and `build.liquibase.commands` (default `['updateSQL']`) in `build.liquibase.dir` (or the workspace root) with any `build.liquibase.env` environment variables. Notice that in Liquibase CLI syntax, global flags such as `--changelog-file=...` or `--defaults-file=...` are placed **before** the command name (`liquibase --changelog-file=db/changelog.xml updateSQL`).
- **Unit tests & Coverage (Stage 2):** runs `liquibase` with `tests.unitTests.liquibase.flags` and `tests.unitTests.liquibase.commands` (default `['validate']`). Because SQL and PL/SQL migrations do not produce JaCoCo XML or LCOV line-coverage reports, the pipeline automatically marks code coverage as `NOT_REQUIRED` (`[COV] Skipping code coverage verification for Liquibase (SQL/PL-SQL) project`) so the 60 % coverage gate never blocks a pure database service.
- **SAST (Stage 4):** when `asoc.doCompile` is not `false`, the staging step runs `asoc.liquibase` (or `build.liquibase` when `asoc.liquibase` is not set, defaulting to `validate`) before packaging the `.irx` archive so generated SQL (such as `updateSQL` output) and PL/SQL scripts (\`.sql\`, \`.pls\`, \`.pks\`, \`.pkb\`, \`.pck\`, \`.prc\`, \`.fnc\`, \`.trg\`, \`.vw\`, \`.xml\`, \`.yaml\`) are included in the HCL AppScan on Cloud SAST scan.
- **SonarQube SCA (Stage 5):** runs `sonar-scanner` (using `tools.sonar.sonarScannerVersion` from Jenkins tools when set, otherwise `sonar-scanner` on the agent `PATH`) inside `withSonarQubeEnv`. It scans the directories in `tools.sonar.liquibase.sources` (defaulting to `tools.sonar.sources`, `sourceDir`, or `.`) and passes optional `tools.sonar.liquibase.inclusions` (`-Dsonar.inclusions=...`), `exclusions` (`-Dsonar.exclusions=...`), and extra `flags` (for example `-Dsonar.plsql.file.suffixes=.sql,.pls,.pks,.pkb`).
- **Nexus IQ (Stage 3):** scans any JDBC drivers, Liquibase extensions, or distribution archives matched by `tools.nexusIq.scanPatterns` (for example `["**/lib/*.jar", "**/*.zip"]`).

Example standalone SQL / PL/SQL service in `devsecops_config.yaml`:

```yaml
projects:
  oracle-payments-db:
    appId:        "11111111-2222-3333-4444-555555555555"
    buildTool:    liquibase
    deployTarget: vm
    sourceDir:    "db"
    asoc:
      doCompile:  true
      liquibase:
        commands: ["updateSQL"]
        flags:    ["--changelog-file=changelog/db.changelog-master.xml", "--url=offline:oracle"]
        dir:      "db"
    build:
      liquibase:
        commands: ["updateSQL"]
        flags:    ["--changelog-file=changelog/db.changelog-master.xml", "--url=offline:oracle"]
        dir:      "db"
    tests:
      unitTests:
        liquibase:
          commands: ["validate"]
          flags:    ["--changelog-file=changelog/db.changelog-master.xml"]
          dir:      "db"
    tools:
      sonar:
        projectKey:  "com.bbh.payments:oracle-payments-db"
        projectName: "Oracle Payments DB"
        liquibase:
          sources:    "db/changelog,db/plsql"
          inclusions: "**/*.sql,**/*.pls,**/*.pks,**/*.pkb,**/*.xml"
          flags:
            - "-Dsonar.plsql.file.suffixes=.sql,.pls,.pks,.pkb"
      nexusIq:
        application:  "oracle-payments-db"
        scanPatterns: ["db/lib/*.jar"]
    deploy:
      vm:
        rd:
          nexusDeliveryEnabled: false
          host:         "ora-rd.bbh.com"
          user:         "taadmin"
          deployDir:    "/opt/deploy/oracle-payments-db"
          deployScript: "db/scripts/deploy-liquibase.sh"
          versionFile:  "db/scripts/version.properties"
```

#### 2. Multi-module and polyglot projects (Java / Gradle / Maven / Node.js + SQL / PL/SQL Liquibase)

Many repositories combine application code (Java/Spring Boot built with Gradle or Maven, optionally with an Angular/React/Node.js frontend in Stage 0) and SQL/PL-SQL database migrations managed by Liquibase. The library supports two ways to model this:

1. **Two services in the same repository (multi-service pipeline run):**

Define the application service (`buildTool: gradle` or `maven`, optionally with `build.node.enabled: true`) and the database service (`buildTool: liquibase`) as two entries under `projects:` in `devsecops_config.yaml` and list both in `projectNames` of the `Jenkinsfile` (for example `projectNames: ['payments-api', 'payments-db']`). Each service gets its own build, unit test, SonarQube project, SAST scan, and deployment target, while the Java/Node.js service enforces the 60 % JaCoCo/LCOV coverage gate and the Liquibase service skips coverage verification automatically.

1. **Single combined service (`buildTool: gradle` or `maven` + `build.liquibase` / `tests.unitTests.liquibase` / `asoc.liquibase` / `tools.sonar.liquibase`):**

Keep `buildTool: gradle` or `buildTool: maven` on a single service and add any of the Liquibase blocks alongside the Gradle or Maven blocks: \*\* **Stage 0 (optional):** `build.node.enabled: true` builds the Node.js/NPM frontend first. \*\* **Build (Stage 2):** the pipeline runs the Gradle or Maven build first, and when `build.liquibase.commands` (or `flags`) is also configured on that service, it immediately runs the Liquibase CLI step in the same stage (`[BUILD] Running additional Liquibase step for multi-module project`). \*\* **Unit tests (Stage 2):** the pipeline runs the Gradle or Maven unit tests first, and when `tests.unitTests.liquibase.commands` (or `flags`) is also configured, it runs the Liquibase validation/test step right after (`[TEST] Running additional Liquibase validation/test step for multi-module project`). JaCoCo and LCOV coverage reports from the Java/Kotlin and Node.js modules are still collected and checked against the 60 % minimum. \*\* **SAST (Stage 4):** during `appscanGenerateIRX`, after the Gradle or Maven SAST compile runs in the staging directory, if `asoc.liquibase` (or `build.liquibase`) is configured on the service, the library also executes the Liquibase command in the staging directory before `appscan prepare`, so both compiled Java bytecode and rendered SQL/PL-SQL scripts are packaged into the IRX archive. \*\* **SonarQube SCA (Stage 5):** when `tools.sonar.liquibase` is configured on a Gradle or Maven service (with `sources`, `inclusions`, `exclusions`, or `flags`), the library appends `-Dsonar.sources=...`, `-Dsonar.inclusions=...`, `-Dsonar.exclusions=...`, and any extra flags to the `./gradlew sonarqube` or `mvn sonar:sonar` command so SonarQube analyzes Java/Kotlin, TypeScript/JavaScript, and SQL/PL-SQL in a single scan.

Example polyglot single-service configuration (Gradle + Node.js + Liquibase SQL/PL-SQL):

```yaml
projects:
  payments-fullstack:
    appId:        "22222222-3333-4444-5555-666666666666"
    buildTool:    gradle
    deployTarget: openshift
    javaPath:     "/usr/lib/jvm/java-21"
    asoc:
      doCompile:  true
      liquibase:
        commands: ["updateSQL"]
        flags:    ["--changelog-file=db/changelog-master.xml", "--url=offline:oracle"]
    build:
      node:
        enabled:  true
        dir:      "frontend"
        commands: ["npm ci", "npm run build"]
      gradle:
        tasks:    ["clean", "build"]
        flags:    ["-x", "test"]
      liquibase:
        commands: ["updateSQL"]
        flags:    ["--changelog-file=db/changelog-master.xml", "--url=offline:oracle"]
    tests:
      unitTests:
        gradle:
          tasks:  ["test", "jacocoTestReport"]
        liquibase:
          commands: ["validate"]
          flags:    ["--changelog-file=db/changelog-master.xml"]
    tools:
      sonar:
        projectKey:  "com.bbh.payments:payments-fullstack"
        projectName: "Payments Fullstack"
        gradle:
          tasks:     ["sonarqube"]
        liquibase:
          sources:    "src/main/java,frontend/src,db/changelog,db/plsql"
          inclusions: "**/*.java,**/*.ts,**/*.sql,**/*.pls,**/*.pks,**/*.pkb"
          flags:
            - "-Dsonar.plsql.file.suffixes=.sql,.pls,.pks,.pkb"
      nexusIq:
        application:  "payments-fullstack"
        scanPatterns: ["**/build/libs/*.jar", "db/lib/*.jar"]
```

### Node.js before Gradle or Maven

Node.js is not a build tool of its own: `buildTool` stays `gradle` or `maven`, and `build.node.enabled: true` adds a Node.js build that runs first, in stage 0. It suits a repository whose frontend npm builds and whose Gradle or Maven build then packages it, for example an Angular application served from a Spring Boot jar. Leave it off when the Gradle or Maven build already runs npm, for example through a Node plugin of the build.

- **Node.js:** `build.node.nodePath`, otherwise the NodeJS installation `build.node.installationName` (NodeJS plugin), otherwise the `node` and `npm` of the agent `PATH`.
- **npm registry:** an `.npmrc` Secret file (`build.node.npmrcCredentialsId`), otherwise `build.node.registry`.
- **Commands:** `build.node.commands` in `build.node.dir`, with `sh` or, on Windows, `bat`; at most `build.node.timeoutMin` minutes in all.
- **Output:** outside `build/` and `target/`, and copied into the artifact by the Gradle or Maven build, see [Troubleshooting](#24-troubleshooting-guide), **The frontend is missing from the jar**.

The GoldenFix pre-check runs in a git worktree of its own, without the output of the Node.js build, so a Gradle or Maven build that fails without the frontend output also fails its pre-check. A Flutter service has no Node.js build: leave `build.node` off for it.

### SQL and PL/SQL with Liquibase in this chapter and in chapter 14

A Liquibase service runs the `liquibase` CLI of the agent with the commands of `build.liquibase`, `tests.unitTests.liquibase`, `asoc.liquibase` and `tools.sonar.liquibase`, for every database that Liquibase supports. A `database:` block adds the offline package, the utPLSQL tests, the Nexus delivery and the RD and QC deployment of Oracle changes. A service with `buildTool: sql` holds only SQL and PL/SQL files: the library writes its changelog and treats it as a database block. All three ways, the detection of the build tool, every setting and four complete repository layouts, among them a Gradle multi-module repository with Java and Node.js and a repository of SQL and PL/SQL files without a changelog, are described in [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase).

### Coverage report detection

On Linux and macOS agents every JaCoCo, Cobertura, OpenCover and LCOV report of the service counts, as described under **Coverage reports** in Stage 2. On Windows agents, on agents without `python3` and when the search step fails, the pipeline reads, in order:

1. the paths of `coverage.reportPath` of the service that exist, a pattern (`*`, `?`, `**`) through `findFiles`, each file once;
2. Flutter: `total_lcov.info`;
3. Gradle: the first of `build/jacoco/jacoco.xml`, `build/reports/jacoco/test/jacocoTestReport.xml` and `build/reports/jacoco/jacocoTestReport.xml` that exists; Maven: the first of `target/site/jacoco/jacoco.xml` and `target/jacoco/jacoco.xml`; C# / .NET (`dotnet` / `msbuild`): `**/coverage.cobertura.xml`, `**/coverage.opencover.xml`, `coverage.cobertura.xml`, `coverage.opencover.xml`, `TestResults/coverage.cobertura.xml`;
4. every matching coverage XML report of the workspace (`coverage.cobertura.xml`, `coverage.opencover.xml`, `*cobertura*.xml`, `*opencover*.xml` for .NET; `jacoco.xml` for Java). On Windows agents, JaCoCo, Cobertura and OpenCover XML reports are parsed natively using PowerShell `[xml]` without requiring `python3`.

## 20. Supported Deployment Targets

A Liquibase service with a database block deploys to an Oracle schema in RD and QC with Liquibase, never to production; its `deployTarget` and `deploy` settings are not used, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase), 14.8.

### VM deployment over SSH

Used by every pipeline for the QC deployment, and by `deployRD()` of a custom pipeline for the lower test region. Set `deployTarget: vm` (the default) and fill in `deploy.vm.qc` (and `deploy.vm.rd` for `deployRD()`).

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

Used by `devSecOpsPipeline` and `devSecOpsExtendedPipeline` for the lower test region (environment `DV`). Fill in `deploy.vm.dod`.

For every application of `deploy.vm.dod.applications`, ordered by `order`, the library:

1. Checks that each component has files matching `fileIncludePatterns` under `baseDir`
2. Publishes every component as a new version named `<versionPrefix>_<build tag>` (`UCDeployPublisher` push, with `baseDir` made absolute below the workspace)
3. Creates the snapshot `<applicationName>_snapshot_<build tag>` and starts `deployProcess` on the environment

The build tag is the tag of the Nexus snapshot of the run, read from `build.tag`, so the UrbanCode versions match the Nexus version; without `build.tag` the library logs `[DOD] No build.tag ...` and uses `BUILD_ID` as before.

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

## 21. Programmatic API Reference

### One devSecOpsApi instance per build

Jenkins keeps a library global in the binding of the script that first reads it, and every script under `vars/` owns a private binding. A `vars` script that reads `devSecOpsApi` therefore does not get the instance the Jenkinsfile holds, it gets a second one, with its own empty `PipelineState`.

That is why the stage methods take the instance as an argument instead of reading the global. When they read it instead, the build looks healthy in the log, because the stages run and report their result against the copy they created, while the entry point keeps reading its own untouched state. The visible result is a pipeline where:

- every box in the report is a grey `SKIP` and the coverage reads `not measured`, even though the stages ran,
- the Security Gates table has no counts and no report links,
- `release-gate.json` says `allowed: true` with no violations, so a build with findings above the policy is never blocked,
- `when { expression { devSecOpsApi.releaseAllowed(...) } }` lets the release and QC stages through,
- the security pipeline never triggers the extended pipeline, because the configuration it reads is empty.

The rule to follow when adding a stage or an entry point: the entry point resolves `devSecOpsApi` once and hands it to `devSecOpsSteps`, and nothing under `vars/` other than the entry points reads the global. `test/checks/vars-api.sh` fails the build when that rule is broken, and the pipeline scenarios in `test/sandbox/PipelineScenarios.groovy` resolve globals the way Jenkins does, so a second instance shows up there as a report full of grey boxes.

If the standard pipeline does not fit your project structure, you can call individual library methods from a custom Jenkinsfile. Import the library and use `devSecOpsApi`, the global variable that holds the services and every helper method. The five entry points are thin declarative skeletons built on top of it and on `devSecOpsSteps`, which carries one method per stage. `devSecOpsSteps` holds no state of its own: every one of its methods takes the `devSecOpsApi` instance to work with, so a custom pipeline calls `devSecOpsSteps.sast(devSecOpsApi)`, never `devSecOpsSteps.sast()`. A custom Jenkinsfile can call `devSecOpsSteps.staticAnalysis(devSecOpsApi)` in one stage instead of the unit test and the three scan calls, and `devSecOpsSteps.dynamicAnalysis(devSecOpsApi)` instead of the three test calls and DAST; they follow the same switches, `devSecOpsApi.parallelScans()` and `devSecOpsApi.parallelTests()`.

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
| `nexusDeliveryEnabled()` | `false` when `delivery.nexusDeliveryEnabled`, or without it `deploy.<target>.rd.nexusDeliveryEnabled`, is false for the current project |
| `nexusDeliverySetting()` | The name of the setting `nexusDeliveryEnabled()` read, for a log line |
| `notRequired(String reason)` | Log the reason and mark the current project not required in the current stage; the stage ends `NOT_REQUIRED` when every project is |
| `nodeBuildRequired()` | `true` when a service of the run needs the Node.js build in this pipeline; the `when` guard of the Node.js build stage. It runs no step and logs nothing |
| `nodeBuildEnabled()` | `true` when `build.node.enabled` is true for the current project |
| `databaseBlock()` | `true` when the current project has `buildTool: liquibase` and a `database.changelogFile`, or `buildTool: sql` |
| `databaseBuild()`, `databaseTests()` | The Build artifact and Unit tests work of the database block: the package, the test deployment and utPLSQL |
| `databaseDelivery(String kind)`, `databaseDeploy(String envName)` | The snapshot or release delivery of the zip (`snapshot`, `release`) and the deployment to `rd` or `qc`; the version is the run-state key `projects.<service>.delivery` |
| `nodeBuildNeeded()` | `true` when the current project needs the Node.js build in this pipeline: enabled, and in the SAST scanning pipeline only while `asoc.doCompile` is not false on a Linux or macOS agent |
| `nodeBuild()` | Run the Node.js build of the current project (`build.node`): Node.js home, registry or `.npmrc`, environment, commands, timeout |
| `appscanSetup()` | Download and extract SAClientUtil |
| `appscanLogin()` | Sign in to the AppScan REST API with the key of the library defaults, once per token |
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
| `publishArtifactQC()` | Release delivery: the NuGet push of a .NET service with `delivery.nuget.source`; any other service is marked not required |
| `releaseDeliveryAvailable()` | True when the current project has a release delivery that is implemented, today a .NET service with `delivery.nuget.source` |
| `pushToNexus(String projectName)` | Snapshot delivery of a Maven, Gradle, .NET or Flutter artifact (`delivery.push`, `delivery.maven`, `delivery.nuget`) that writes `build.tag`. It does not check `nexusDeliveryEnabled()`: a custom stage guards it with that method, as `devSecOpsSteps.nexusSnapshotDelivery` does |
| `pullFromNexus(String projectName)` | Download of the snapshot named in `build.tag` with `delivery.pull`; marks the project not required for OpenShift, without Nexus delivery or without `delivery.pull` |
| `vulnerabilitySummary()` | One line with the SAST and DAST findings above the policy severity; in the Nexus IQ GoldenFix pipeline the Nexus IQ critical, high and medium counts |
| `runExtendedPipeline()` | Start the job of `jenkins.pipeline.extendedPipeline` when `RUN_EXTENDED_PIPELINE` is selected |
| `nexusDelivery(String env)` | Tag the image in the registry |
| `buildDockerImage(String projectName)`, `copyImageToNexus()`, `checkDeploymentRepo()` | OpenShift image build and promotion; both skip the service with an `[OPENSHIFT]` line when `nexusDeliveryEnabled()` is false |
| `checkDeploymentRepos()` | Check out the deployment repository of every service that sets `deploy.openshift.rd.deploymentPath`, without switching the project; the Monitor stage calls it |
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

`devSecOpsSteps.pullNexusSnapshot(devSecOpsApi)` is the body of the stage **Download artifacts from Nexus Repo**: it runs `pullFromNexus()` for every project except a Liquibase service with a database block, which it marks not required. `devSecOpsSteps.monitorSources(devSecOpsApi, [skipDeploymentRepo: true])` leaves the deployment repositories out. `devSecOpsSteps.nodeBuild(devSecOpsApi)` is the body of the Node.js build stage: it runs `nodeBuild()` for every project that needs it and marks the others not required. `devSecOpsSteps.nexusReleaseDelivery(devSecOpsApi, [nodeBuild: true])` runs the Node.js build of each project that enables it before `publishArtifactQC()`, as the extended pipeline does, but only when `releaseDeliveryAvailable()` is true for a project of the run; without the option it publishes only.

## 22. Monitoring and Metrics

Every run writes one batch of InfluxDB line protocol from `post { always }`, so a failed run is recorded exactly like a successful one. Nothing is sampled: the batch covers the run, every stage, every scanner, every module, every test suite, the release gate and the remediation outcome.

### What is written

| Measurement | Tags | Answers |
|---|---|---|
| `pipeline_run` | project, env, variant, result, branch | One row per run: outcome, duration, and how many stages passed, warned, failed, were blocked or skipped |
| `dora` | project, env, variant | Deployment, lead time from the commit under test, change failure and whether a release was published |
| `stage_event` | project, env, stage, status | Per stage: duration, whether it was executed, its order and the reason when it was not green. **Stamped at the end of that stage**, so the timeline is accurate |
| `security_findings` | project, env, module, scanner, status | Severity counts per scanner per module, the policy limits, and how many findings are above them; for `scanner=niq` the Nexus IQ counts of the service, which add up to `vulnerabilities` with `scanner=nexusiq` (points written before 2026-11-23 carry 0 for `scanner=niq`) |
| `policy_status` | project, env, scanner, status | Which gates were evaluated and which held |
| `code_coverage` | project, env, module, measured | Line coverage of each module, the mean of its reports, against the required minimum, and the shortfall |
| `coverage_report` | project, env, module, report, tool, counted | One point per coverage report: its line coverage and whether it counts in the mean of its module |
| `test_execution` / `test_job` | project, env, module, suite, status, type | Suite totals and success rate, and each individual job |
| `release_gate` | project, env, allowed | Whether the release was permitted, and how many violations blocked it, each counted once (points written before 2026-11-23 count the violations of the run twice) |
| `goldenfix` | project, env, module, status | Upgrades offered, applied and left to a developer, whether a pull request was raised or updated and whether the pre-check build withheld one, and the address and title of the pull request |
| `test_execution` with `suite=unit` | project, env, module, suite | Unit test totals of each module: total, passed, failed, skipped, success rate and duration |
| `database_deployment` | project, env, module, target, status | One point per database target a Liquibase service with a database block deployed: changesets applied, tagged, the version and the Liquibase version, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase) |
| `build_evidence` | project, env, module | The change evidence of a build: the artifact version, the SonarQube quality gate, the links to the SAST, DAST, Nexus IQ and SonarQube reports, and the settings file the build read (`config_file`). Empty values are left out |

The measurements written before this revision - `deployments`, `change_failure`, `build_duration`, `stage_metric`, `vulnerabilities` and `test_coverage` - are still written unchanged, so dashboards built against them keep working. The `line_pct` of `test_coverage` and `code_coverage` is the mean of the reports of the run and of the module, which is the value written before when there is one report; their `covered`, `missed` and `total` are the sums over the same reports.

A run with a Node.js build writes its stage as `stage=Node.js build` in `stage_event` and `stage_metric` and counts it in `pipeline_run`; a run without one writes exactly what it wrote before. When Nexus IQ and SonarQube run while AppScan scans, the SAST duration covers the whole **Static analysis** stage, so the durations of stages 3 to 5 add up to more than the stage took. The stage order and the names do not change. In the `goldenfix` point a later service of a shared pull request has `status=PR_UPDATED` and `pr_raised` 1, and a service whose upgrades the shared pull request already carried has `applied` 0. The notes of a GoldenFix run are not written to InfluxDB.

### DORA

The four metrics are derived from the `dora` measurement:

- **Deployment frequency** - the `deployment` field is 1 when a deployment stage passed (`PASS`). A `NOT_REQUIRED` deployment stage rolled nothing out and is not counted.
- **Lead time for changes** - `lead_time_s` measures from the timestamp of the commit under test, read with `git log -1` during initialisation, to the end of the run. When the commit cannot be read the build start is used instead and the console says so.
- **Change failure rate** - `change_failure` is 1 for any run that did not end `SUCCESS`.
- **Time to restore** - derived in Grafana from the `change_failure` series; the query is in [Grafana dashboards and queries](#23-grafana-dashboard-query-reference).

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

[Grafana dashboards and queries](#23-grafana-dashboard-query-reference) holds ready Flux for every measurement above: the DORA row, pipeline health, security posture, quality and tests, remediation, and a set of cumulative panels that answer what the pipeline has delivered over successive runs and since the first build. The two dashboard definitions under `grafana/` continue to work; the stage panels of `devSecOpsDashboard` show the Build artifact stage of the Nexus IQ GoldenFix pipeline as `Build` and the Node.js build as `Node.js`, with the sort key of `Download sources`.

## 23. Grafana Dashboard Query Reference

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
| `security_findings` | project, env, module, scanner, status | critical, high, medium, low, total, above_policy, max\_\*, exceeded; for `scanner=niq` the Nexus IQ counts of the service (0 in points written before 2026-11-23) | Findings per scanner per module against policy |
| `policy_status` | project, env, scanner, status | ok, measured | Which gates were evaluated and which held |
| `code_coverage` | project, env, module, measured | line_pct, covered, missed, total, required, met, gap | Coverage per module against the required minimum |
| `coverage_report` | project, env, module, report, tool, counted | line_pct (left out for a report without lines), covered, missed, total | Coverage per report; `counted=no` marks a copy, an aggregate, a report without lines or one that cannot be read |
| `test_execution` | project, env, module, suite | total, passed, failed, not_configured, duration_ms, success_rate; skipped for `suite=unit` | Smoke, regression and performance suites, and the unit tests of each module (`suite=unit`) |
| `database_deployment` | project, env, module, target, status | pending_before, applied, previously_run, filtered_out, pending_after, invalid_objects, invalid_before, compile_errors, changesets, tagged, ok, duration_ms, version, liquibase | The changesets each database target received, see [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase) |
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

## 24. Troubleshooting Guide

The messages of a Liquibase service with a database block (`[DB]`, and the database lines of `[NEXUS]`, `[DEPLOY]`, `[SONAR]` and `[SAST]`) are listed with their cause and fix in [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase), 14.14.

### "\[INIT\] pipelineKey and pipelineKeys are settings of OneView ..."

The Jenkinsfile was written for the `main` release of the library, the release with ITSS COE OneView, and names a pipeline key. This release reads no key, also no empty one: the build stops before `pipeline {}` and runs nothing. The message names the entry point of the Jenkinsfile, for example "\[INIT\] pipelineKey and pipelineKeys are settings of OneView, which this release of the library does not read: write devSecOpsPipeline(projectNames: '&lt;services of this repository&gt;', agentNames: \['&lt;agent label&gt;'\]) and commit devsecops_config.yaml at the root of the repository"; for an extended pipeline it adds `, securityPipeline: '<security job>'`. Name the services of the repository and the agents in the Jenkinsfile, and commit `devsecops_config.yaml` with the settings of the services at the root of the repository:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(projectNames: 'gateway', agentNames: ['linux-agent'])
```

### "\[INIT\] No agentNames in the Jenkinsfile"

The Jenkinsfile names no Jenkins agent labels, so the build has no choices for its `AGENT_NAME` parameter and stops before `pipeline {}`: "\[INIT\] No agentNames in the Jenkinsfile: write devSecOpsPipeline(projectNames: '&lt;services of this repository&gt;', agentNames: \['&lt;agent label&gt;'\]) and commit devsecops_config.yaml or config.yaml at the root of the repository". The message names the entry point of the Jenkinsfile and, for an extended pipeline, adds `, securityPipeline: '<security job>'`. Add `agentNames` to the Jenkinsfile.

### "\[INIT\] Neither devsecops_config.yaml nor config.yaml is at the root of the repository"

"\[INIT\] Neither devsecops_config.yaml nor config.yaml is at the root of the repository: commit devsecops_config.yaml with a projects: section for the services of this repository (examples/CertScanner/devsecops_config.yaml shows one)". The checkout has neither file at its root. The file is not committed at the root, the job builds another branch, or the file is named in another case: the names are exact and case-sensitive. A Windows agent finds `Config.yaml` as `config.yaml` and a Linux agent does not, so a build that passes on `windows-agent` and fails on `linux-agent` has the file in another case; rename it to `devsecops_config.yaml`.

### "... must contain a 'projects:' section"

The file read has no `projects:` map. Add the services of the repository under `projects:`.

### "\[INIT\] \[WARN\] projectNames names &lt;name&gt;, which devsecops_config.yaml does not have"

A name of `projectNames`, `PROJECT_NAMES` or `PROJECT_NAME` is not a service of the file. The run leaves it out and goes on with the other names, or, when none is left, with every service of the file; the line lists the services of the file. Correct the name in the Jenkinsfile or in the job, or add the service to the file.

### "\[INIT\] \[WARN\] &lt;service&gt;: &lt;key&gt;: &lt;value&gt; in devsecops_config.yaml is not a number"

The key holds a value of another kind than its library default (`is not a number`, `is not true or false`, `is not a list`), so the run uses the default the line names. Correct the value or remove the key.

### "\[INIT\] \[INFO\] &lt;service&gt;: &lt;key&gt;: &lt;value&gt; equals the library default - the key can be removed"

Nothing is wrong: the library would use the same value without the key (`equals the auto-detected value` when the auto-resolve finds it in the repository). Remove the key to keep the file short, or keep it to pin the value.

### "\[WARN\] \[BUILD\] Gradle did not find the plugin(s) ..." or "Maven did not find the plugin(s) ..."

A Gradle or Maven step failed because a plugin is not in the repositories of the build, which the agents on the BBH network cannot leave. The step runs once more with `pluginRepositories` of the service or of the bundled defaults ([Gradle and Maven plugins from the BBH Nexus](#19-supported-build-tools)); a line "pluginRepositories.gradle: ... is not an http or https address" names a value of the service that the library replaced by the library default. When it then passes, the line "The plugin(s) ... came from ..." names where to add the repository so that the first attempt does not fail. When it fails again, the plugin is not in that repository either: ask the DevSecOps team to add it to the BBH Nexus. A Maven step with its own `-s` settings file is not run again; add the repository to that file.

### "The library resource com/bbh/devsecops/defaults.yaml cannot be read"

"\[INIT\] The library resource com/bbh/devsecops/defaults.yaml cannot be read (&lt;cause&gt;): the global settings come from it; ask the DevSecOps team". Every run takes its global settings from this file of the library. The library version the job loads lacks the file, or another library loaded in the same build shadows it. Ask the DevSecOps team.

### "The last successful build of ... has no pipeline-config.yaml"

"\[INIT\] The last successful build of &lt;job&gt; has no pipeline-config.yaml: it was built by a library release that archived config.yaml instead; run &lt;job&gt; once with this release". The extended pipeline copies `pipeline-config.yaml` from the security job that its Jenkinsfile names in `securityPipeline`, and the last successful build of that job was made by an earlier release of the library, which archived `config.yaml`. Run the security job once with this release, then the extended job.

### "\[INIT\] Security pipeline &lt;job&gt; builds &lt;services&gt;, this run builds &lt;service&gt;"

The security build copied by the extended pipeline holds no run-time tags for a service of the extended run. The two Jenkinsfiles name other services; give both the same `projectNames`, or name the security job of these services in `securityPipeline`.

### "\[SAST\] asoc.keyId is not set in resources/com/bbh/devsecops/defaults.yaml of the library ..."

The library defaults hold no key ID of the functional AppScan on Cloud user; DAST says the same with `[DAST]`. The DevSecOps team sets `asoc.keyId` and `asoc.token` (the Jenkins **Secret text** credential with the key secret) in `resources/com/bbh/devsecops/defaults.yaml` of the library. A key ID in `devsecops_config.yaml` or `config.yaml` of a service does not help: it is ignored with a `[WARN]` line that names it.

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
- In the **Static analysis** stage the status poll runs in the SAST branch right after the queue, while the other branches run; in the fallback order, when the workspace could not be copied, it starts after SonarQube and its timeout counts from then.

### "\[STATIC\] &lt;services&gt;: the SAST compile runs other Gradle tasks or Maven goals than the build command in the workspace"

The workspace could not be copied for SAST (the `[WARN] [STATIC]` line above says why), so the run fell back to the order in which SAST compiles in the workspace. The named services set `asoc.gradle`, `asoc.maven` or `asoc.dotnet` to other tasks, goals, flags or settings than their build command, so the unit tests, Nexus IQ, SAST and SonarQube run one after another. Free the disk space of the agent's temporary folder of the build, or make the compile command of the service equal to its build command, leave it empty (the SAST compile then runs the build command), or set `asoc.doCompile: false`.

### "\[STATIC\] SAST stopped while it prepared its scans: Unit tests and Nexus IQ do not run in this build"

The workspace could not be copied, and in the fallback order SAST failed before every scan was queued, in the AppScan client, the login, the compile, the IRX archive, the upload or the queue, so the workspace may be half built and the unit tests, Nexus IQ and SonarQube do not run on it. The report shows them as SKIP or blocked, and the release gate lists the SAST failure. Fix the SAST failure the console shows above the message; the stages run again in the next build. With the copy, a SAST failure leaves the other three stages untouched.

### "\[WARN\] \[STATIC\] …" or "\[INFO\] \[STATIC\] …" at the start of the run

`[WARN]` names a value of `staticAnalysis.parallel`, `dynamicAnalysis.parallel` (`[DYNAMIC]`) or of their environment variables that is neither `true` nor `false`; the run uses the default `true` and goes on. `[INFO]` names a value equal to the default, which can be removed from `devsecops_config.yaml`, `config.yaml`, the bundled defaults or the Jenkins environment.

### How to switch the order off for all jobs, one agent or one job

Set the environment variable `DSO_STATIC_ANALYSIS_PARALLEL=false` (or `DSO_DYNAMIC_ANALYSIS_PARALLEL=false` for the tests and DAST) in **Manage Jenkins → System → Global properties → Environment variables** for all jobs, or in the environment variables of the node properties of an agent for the runs on that agent: the pipelines then run those stages one after another, whatever the settings say. A value set on a Jenkins folder or job counts only when a plugin puts it into the environment of the build ([the configuration reference](#16-configuration-reference-devsecops_configyaml)). For one job, set `staticAnalysis.parallel: false` or `dynamicAnalysis.parallel: false` on one of its services in `devsecops_config.yaml` or `config.yaml`: one such service keeps the whole run one after another. A custom Jenkinsfile built on `devSecOpsApi` ([the API reference](#21-programmatic-api-reference)) can also set the variable in its top-level `environment` block. `staticAnalysis.parallel: false` under `defaults:` of the bundled defaults switches the static order off for every job without its own value.

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
| `BUILD_FAILED` | The pre-check build failed with the proposed versions and again in every further attempt, with the Nexus IQ version instead of a catalog version or with lower versions, up to `goldenFix.verify.maxAttempts`, so no pull request was opened. The console lines `[GOLDENFIX] Pre-check build ...` and the build output above them show what failed; see [Pipeline Stage Reference](#17-pipeline-stage-reference) |
| `NOT_VERIFIED` | The upgrades were prepared but no pre-check build could be run for a changed file, so no pull request was opened. The console line `GoldenFix: no build found for <file> - not verified` names the file |
| `SKIPPED` | Automatic upgrades are switched off for this project |
| `ERROR` | The run stopped before it could propose anything and the repository was not touched. The card names it as a fault in the pipeline run and carries the technical detail for the DevSecOps team; the `[GOLDENFIX]` lines in the console hold the full context, usually Nexus IQ or Bitbucket permissions. An internal error of the library, such as a `StackOverflowError`, is named by its class |

The entries below explain the reasons, notes and console lines of GoldenFix one by one. [Pipeline Stage Reference](#17-pipeline-stage-reference) describes the whole remediation.

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

### "GoldenFix: no build found for &lt;file&gt; - not verified"

GoldenFix changed a file that it treats as a Gradle catalog, and found no Gradle build that uses it. This is the case for a `*.versions.toml` that no `settings.gradle` imports (other than `gradle/libs.versions.toml` next to a `settings.gradle`), for a file of `goldenFix.versionCatalogs` that no build imports or applies, and for a `java-platform` module outside every folder that holds a `settings.gradle`. No pull request is raised without a pre-check build, so the run ends `NOT_VERIFIED`. Import the catalog in the `settings.gradle` of the build that uses it, so that the next run can verify it.

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
| `asoc.keyId is not set in resources/com/bbh/devsecops/defaults.yaml` | The library defaults hold no key ID of the functional AppScan on Cloud user. The DevSecOps team sets `asoc.keyId` and `asoc.token` there; a service sets only its `appId`. |
| `[WARN] <service>: asoc.keyId ... ignored` | The service configuration names an AppScan key, token or credential. Remove it: SAST and DAST always sign in with the key of the functional user from the library defaults. |
| `[SONAR] Badge token credentials ... could not be read` | The credential named in `tools.sonar.badgeCredentialsId` does not exist or is not Secret text. The stage result does not change; the report uses `badgeToken` or shows no badges. |
| `[OPENSHIFT] <service>: RD deployment not required`, `QC deployment not required` | Nexus delivery is off for the service and the region has no build tag, so there is no image to deploy: RD needs `deploy.openshift.rd.buildTag`, QC its own `deploy.openshift.qc.buildTag` or the RD tag. Pin a tag or turn the delivery on. |
| `The Nexus IQ GoldenFix pipeline would scan nothing` | The service has no Nexus IQ application or no scan patterns in the file the message names, `devsecops_config.yaml` or `config.yaml`. Set both under `tools.nexusIq`. |
| SAST fails while connecting, or times out | The agent cannot reach AppScan through the proxy. Raise it with the DevSecOps team quoting the agent label — the proxy account, the OIS certificate or the network route needs attention. Nothing in your configuration fixes this. |
| Nexus IQ reports the application does not exist | `tools.nexusIq.application` does not match an application in Nexus IQ. |
| Coverage shows `not measured` | No JaCoCo, Cobertura, OpenCover or LCOV report with measured lines was found. The log of the Unit tests stage names the folders searched and every report read. Confirm your test task generates a supported coverage report, or point `coverage.reportPath` at it. |
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

## 25. Library Development and Test Suite

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
| `test/coverage/scan-scenarios.sh` | The scan script of `CoverageReports`, run with `python3 -I` on report trees: services that share a folder, modules with release folders, copies, aggregates and their rest, patterns, Android, Kover, Cobertura, OpenCover, Flutter and LCOV variants, non-ASCII names, the SHA-1 of each report, a report of another service skipped before it is read, the time limit, the class listing of the earlier reader and 400 module reports |
| `test/sandbox/SandboxScenarios.groovy` | Every class initialises under the whitelist used while the CPS program is saved; the AppScan commands match the recorded baseline; the AppScan severity totals come from the report summary; a Nexus IQ report with JSON nulls still yields components; the GoldenFix version choice only changes the patch version and takes the newest clean one; all remediation lookups of a project are one shell step; GoldenFix patches a real `pom.xml`, `build.gradle`, `gradle.properties`, `package.json`, `requirements.txt`, `pubspec.yaml`, `*.csproj`, `Directory.Packages.props` and `packages.config`; GoldenFix raises shared version catalogs in every supported form, aligns the other declarations with them, names the reason for what it cannot raise, verifies each shared file in the builds that use it and shares one pull request between the services of a repository; it raises a catalog of 300 libraries and a `resolutions` block of 300 entries on a 1 MB thread stack, reads TOML headers with comments, indentation or spaces, keys in quotes and literal strings, leaves a `strictly` or `require` range that excludes the target alone (bounds in the version order of Gradle), changes only the catalog block of a settings file, ignores `./` in listed paths, reads a listed pom with another name and builds it with `mvn -f`, also with a Maven command of `goldenFix.verify.commands`, resolves `from files(..)` and `$rootDir` imports, shows a property raised in both rounds as one row with the components of both, builds the Nexus IQ version when the catalog version breaks the build without using up an attempt, labels a declaration or a property aligned with a catalog, counts only what the shared branch changed in a shared catalog as already raised, looks in `buildSrc` only for a holder it can declare and records an escaping `Error` as `ERROR`; it refreshes a `package-lock.json` (not in front of an npm command of `goldenFix.verify.commands`), raises npm `overrides` and `resolutions`, tells a branch that exists from a push a hook declines and names pub components; C#/.NET classic Windows MSBuild/NuGet/VSTest+OpenCover/TRX and CLI builds, Windows GoldenFix + PowerShell git/Bitbucket PR + HTML report rendering, NuGet publish, Windows DOD file check and OpenShift `publishDir`; the `package-lock.json` pattern of a Node.js build names its folder without `./`; the Node.js build runner sets up Node.js on Linux and Windows, and the npm pre-check of GoldenFix runs inside it; 200 smoke jobs run with the configured parallelism; the `goldenfix` point carries `pr_url` and `pr_title` only when a pull request was raised; eight service level pipelines produce the demo reports |
| `test/sandbox/CoverageScenarios.groovy` | `checkCoverage`, the coverage card, the metrics and the release gate with a fake scan: one row per report, the mean of a service and of the run, the reports of shared folders and of `coverage.reportPath`, a copy of the report of another service, the fallbacks without `python3` or after a failed scan, C#/.NET Cobertura and OpenCover XML on Linux and Windows, and real `sh` runs of the scan step |
| `test/sandbox/StaticAnalysisScenarios.groovy` | The switches of the stages at the same time (the environment variable first, then `staticAnalysis.parallel` of every service, by default true), the `[WARN]` lines of wrong values and the `[INFO]` lines of values equal to the default, the durations and the saved time of the report, the services that share a source folder, the branch state `PipelineState.fork`/`absorb` and `recordParallelGroup`, the Manual Actions vs DevSecOps table (`ReportViewModel.manualComparison`, `BuildUtils.manualEffort`), the services whose SAST compile differs from their build command (`asoc.gradle`, `asoc.maven`, `asoc.dotnet`), C#/.NET auto-resolve, SonarQube (`SonarScanner.MSBuild.exe` / `dotnet sonarscanner`) and Windows SAST IRX staging, the order `PipelineState.settle` restores, and the removal of the SAST staging copy on Linux and Windows |
| `test/sandbox/PipelineScenarios.groovy` | The five entry points run end to end under the real sandbox, green and orange, the Nexus IQ GoldenFix pipeline also with a failing build, including the report, the release gate, the metrics, the deployments and the GoldenFix pull request of the Nexus IQ GoldenFix pipeline, and match their golden runs. Every run reads a repository file: it plants `devsecops_config.yaml` or `config.yaml` in the workspace, takes the defaults of the run as the bundled resource and gives the entry point `projectNames`, `agentNames` and, for the extended pipeline, `securityPipeline`; the runs cover both file names, both files at once, a Jenkinsfile without `projectNames` and another `appId` in a `config.yaml` that is not read. The eleven golden runs of the first four entry points, recorded at release b815d55, are unchanged byte for byte and are compared after declared rules for what this release adds: the run-state file `pipeline-config.yaml`, the `build_evidence` and unit `test_execution` points, the InfluxDB line count, the settings line of the report header and `config_file`. The golden runs of the Nexus IQ GoldenFix pipeline (green, orange, build failure, the shared catalog run of two services) were recorded again for this branch; before they were committed, a one-off comparison proved that each new file equals the file recorded on `main` with the steps and lines of the key read removed and the lines that name the settings file added. Runs without a golden file check single features, among them the Node.js build in the full, SAST, Nexus IQ GoldenFix and extended pipelines. The base runs set `staticAnalysis.parallel` and `dynamicAnalysis.parallel` to false in the defaults of the run, so they keep the order of the golden runs. Twins of the green and orange full, security and extended runs run their stages at the same time and give the release gate, the metrics and the report of their golden file and its steps in another order, which the orange full twin also pins in `full-pipeline-orange-parallel-stages.txt`; focused runs cover the failed copy of the workspace, the compile fallback, the environment override, a service that opts out, a wrong value, a SAST failure before and after its scans are queued, SonarQube waiting for the unit tests, GoldenFix and the AppScan settings per service. Every run checks the Manual actions vs DevSecOps Automated Actions table against the stage results of its log |
| `test/sandbox/DatabaseUnitScenarios.groovy`, `test/sandbox/DatabaseScenarios.groovy` | The settings, parsers, static checks and scripts of the database block, and the three repository layouts of [Integration Guide: SQL and PL/SQL with Liquibase](#14-integration-guide-sql-and-plsql-with-liquibase) in the five pipelines with fake Liquibase and utPLSQL output, pinned by `test/sandbox/golden/database-*.txt`; a Liquibase service without a database block, and the examples of the chapter compared with `test/fixtures/database` |
| `test/database/cli-scenarios.sh` | Optional: the real Liquibase and utPLSQL CLIs from `~/.cache/devsecops-library-tools/database`, offline and against H2; skipped with a line when they are not there |
| `test/sandbox/ConfigScenarios.groovy` | The configuration of a run: `projectNames` as a string or a list, `securityPipeline`, a Jenkinsfile that names `pipelineKey` or `pipelineKeys`, also empty, which stops with its message and runs nothing, a Jenkinsfile without `agentNames`, `DSO_REQUIRE_PIPELINE_KEY=true`, which changes nothing, `configure()` twice and `initialize()` without `configure()`, each of the two file names, the file errors and their messages; the `platform:` section of the bundled defaults, which changes the iOS build agent, the OIS host, the Flutter snapshot repository and the tool environment, with an environment variable that wins over `platform.environment`; the InfluxDB settings of the primary service and the upload on Linux and Windows; the build token of remote test jobs (`tokenCredentialsId`); the extended handoff; the Nexus IQ GoldenFix entry point, which names its own call and stops at the start for a service it would not scan; and how the library chooses a value: a name of `projectNames` that the file does not have, a value of the wrong kind that falls back to the default, and the keys that equal the library default or the auto-detected value |
| `test/sandbox/PluginRepositoryScenarios.groovy` | The Gradle and Maven plugins from the BBH Nexus: a step that finds its plugins runs once and unchanged; a missing plugin (the Plugin Portal message, a missing `classpath` module, the Maven plugin, descriptor and prefix messages) runs the step again with the init script or the settings copy, which the tests read; later steps of the folder use the repository at once; another failure, a second failure, a switched-off or empty repository and a Maven step with its own settings file fail as before; an empty repository of a service takes the one of the bundled defaults, and an address that is not a plain http or https address is named and replaced by it; the bundled defaults and the callers that give the repositories to every Gradle and Maven step |
| `test/sandbox/CompatibilityScenarios.groovy` | The loader of release b815d55 and the loader of this release produce the same configuration, exactly, from `devsecops_config.yaml` and from `config.yaml` over the same defaults (`devsecops_config.yaml` first when both exist); the production bundled defaults have the structure the library reads, with an optional `platform:` map, and can be applied; `examples/CertScanner/devsecops_config.yaml` and `examples/DotNetApp/devsecops_config.yaml` load |

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
