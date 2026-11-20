# DevSecOps Jenkins Shared Library – Standalone User Guide (Without Portal)

> **Comprehensive Guide for Pipelines Running Without the Portal (`DSOEnhanced` library on `DSOEnhancedWithoutPortal` branch).**
> Engineered and maintained by the **DevSecOps team in ITSS COE at Brown Brothers Harriman (BBH)**.
> Fully autonomous delivery pipeline configured via code using `Jenkinsfile` and `devsecops_config.yaml` in your Git repository.
> Designed for non-technical users, Product Owners, business analysts, and engineers.

---

## Contents

- [1. Overview and Standalone Model](#1-overview-and-standalone-model)
- [2. The Jenkinsfile: Defining Your Pipeline](#2-the-jenkinsfile-defining-your-pipeline)
- [3. The devsecops_config.yaml Configuration File](#3-the-devsecopsconfigyaml-configuration-file)
- [4. Multi-Service and Multi-Repository Bitbucket Support](#4-multi-service-and-multi-repository-bitbucket-support)
- [5. Supported Languages and Build Tools](#5-supported-languages-and-build-tools)
- [6. Unit Testing and Multi-Report Code Coverage](#6-unit-testing-and-multi-report-code-coverage)
- [7. Static Application Security Testing (SAST) – HCL AppScan](#7-static-application-security-testing-sast--hcl-appscan)
- [8. Dynamic Application Security Testing (DAST) – HCL AppScan](#8-dynamic-application-security-testing-dast--hcl-appscan)
- [9. Code Quality – SonarQube & Alternative Badge Tokens](#9-code-quality--sonarqube--alternative-badge-tokens)
- [10. Dependency Security & GoldenFix – Nexus IQ](#10-dependency-security--goldenfix--nexus-iq)
- [11. Deployments and "Disable Nexus Delivery" (OpenShift & VM)](#11-deployments-and-disable-nexus-delivery-openshift--vm)
- [12. Telemetry, DORA Metrics, InfluxDB, and Grafana](#12-telemetry-dora-metrics-influxdb-and-grafana)
- [13. Complete devsecops_config.yaml Template with Field Reference](#13-complete-devsecopsconfigyaml-template-with-field-reference)
- [14. Auto-Resolve in Standalone Mode (`autoresolve parameters: true`)](#14-auto-resolve-in-standalone-mode-autoresolve-parameters-true)
- [15. Auto-Resolved Parameters Reference](#15-auto-resolved-parameters-reference)
- [16. Bitbucket Pull Request Decoration & Shift-Left Feedback](#16-bitbucket-pull-request-decoration--shift-left-feedback)
- [17. SAST REST API and the AppScan API key](#17-sast-rest-api-and-the-appscan-api-key)

---

## 1. Overview and Standalone Model

In the `DSOEnhancedWithoutPortal` branch, the library operates independently of the management portal:
- **No Portal Dependency:** The pipeline never polls external portal APIs for configuration.
- **Configuration as Code:** All pipeline parameters are stored in `devsecops_config.yaml` (or backward-compatible `config.yaml`) at the root of your application Git repository.
- **Smart Auto-Detection & Validation (`[WARN]` Fallback):** When `buildTool`, `deployTarget`, or tool-specific sub-parameters (`build.dotnet.mode`, `tests.unitTests.dotnet.runner`, `coverageTool`) are omitted, `RepoStackAutoResolver` automatically detects them from repository files (strictly scoped to each service's `sourceDir` in multi-repository and multi-module setups). If an invalid or unrecognized value is provided, the pipeline logs a `[WARN]` message and falls back to the auto-detected setting.
- **Auto-resolved settings are visible:** every value the pipeline fills in or corrects is printed in the build log as `[INIT] Auto-resolved for <service>: <setting> = <value> (<why>)` and listed in the *Auto-resolved settings* card of the pipeline report, so you can copy it into `devsecops_config.yaml` to pin it.
- **MVC & Hexagonal Architecture:** The library is structured around a clean Domain Core (`com.bbh.core`), Inbound/Outbound Ports (`com.bbh.remediation.port`), Hexagonal Adapters (`SharedCatalogRepositoryService`, `JdkToolchainSetup`, `RemoteTestJobRunner`, `LiquibaseBuildRunner`, `DotnetProjectResolver`, `AppScanEnvironmentAdapter`, `SonarScannerCommandBuilder`), and MVC Model-View-Controller separation (`ReportViewModel`, `HtmlReportCardsView`, `HtmlReportService`; `GoldenFixPullRequestView`, `SecurityFindingsParser`, `SecurityPullRequestView`, `SecurityPullRequestService`).
- **Embedded Library Defaults:** Global enterprise standards (coverage thresholds, timeouts, InfluxDB telemetry endpoints) are bundled inside the library (`defaults.yaml`), meaning you only specify settings unique to your application.

### Quick Reference Index

#### A. Supported Technologies, Languages, Build Tools & Catalogs

| Technology / Stack | Languages | Build Tool (`buildTool`) | Auto-Detection Triggers | Unit Tests & Coverage | SonarQube Scanner | GoldenFix & Version Catalogs | SAST Inline PR Comments |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Java / Enterprise JVM** | Java, Kotlin, Groovy | `maven` (`mvn`) | `pom.xml` | Surefire/Failsafe JUnit XML + JaCoCo XML | `mvn sonar:sonar` | `pom.xml`, parent POMs, BOM imports, `<properties>` | `.java`, `.kt`, `.groovy`, `.jsp`, `.xml`, `.properties` |
| **JVM / Spring / Kotlin / Android** | Java, Kotlin, Groovy | `gradle` (`gradlew`), `android` | `build.gradle`, `build.gradle.kts`, `gradle-wrapper.properties`, `AndroidManifest.xml` | Gradle Test JUnit XML + JaCoCo / Kover XML | `./gradlew sonarqube` | `build.gradle(.kts)`, `gradle.properties`, `gradle/libs.versions.toml`, `settings.gradle(.kts)`, `java-platform`, `buildSrc` | `.java`, `.kt`, `.kts`, `.groovy`, `.xml`, `.gradle` |
| **C# and .NET** (.NET Framework 4.x, .NET Core, .NET 6–9+) | C#, F#, VB.NET, ASP.NET / Razor | `dotnet` (`csharp`, `c#`, `net`, `fsharp`, `vbnet`), `msbuild` (`vs`, `netfx`) | `*.sln`, `*.csproj`, `*.fsproj`, `*.vbproj`, `global.json`, `Directory.Packages.props`, `Directory.Build.props`, `packages.config`, `build.cake`, `.nuke` | Modes: `auto`, `dotnet`, `msbuild`, `hybrid`, `cake`, `nuke`. Runners: `dotnet test`, `vstest.console.exe`, `MSTest.exe`, `nunit3-console.exe`, `xunit.console.exe`. Coverage: Cobertura (`XPlat Code Coverage`), OpenCover, dotCover | `SonarScanner.MSBuild.exe` / `dotnet sonarscanner` (or `sonar-scanner` with `useGenericScanner: true`) | `*.csproj`, `*.fsproj`, `*.vbproj`, `Directory.Packages.props`, `Directory.Build.props`, custom `.props`, `packages.config` | `.cs`, `.fs`, `.vb`, `.cshtml`, `.razor`, `.aspx`, `.config`, `.csproj`, `.props` |
| **Node.js / SPA** (Angular, React, Vue) | JavaScript, TypeScript, HTML, CSS/SCSS | `npm` (`node`, `nodejs`, `yarn`, `pnpm`, `angular`, `react`, `vue`) or Stage 0 `build.node.enabled: true` | `package.json`, `angular.json` (root or `frontend/`, `web/`, `client/`, `ui/`) | Jest / Karma / Vitest / Cypress + LCOV (`lcov.info`) / Cobertura / Clover | `sonar-scanner` with `-Dsonar.javascript.lcov.reportPaths` & `-Dsonar.typescript.lcov.reportPaths` | `package.json`, `package-lock.json`, `overrides`, `resolutions` | `.js`, `.jsx`, `.ts`, `.tsx`, `.mjs`, `.cjs`, `.vue`, `.svelte`, `.html`, `.css`, `.scss`, `.json` |
| **Python** | Python | `pip` (`python`, `poetry`, `pipenv`) | `requirements.txt`, `pyproject.toml`, `setup.py`, `Pipfile` | `pytest` / `unittest` JUnit XML + `coverage.xml` (Cobertura) / `lcov.info` | `sonar-scanner` with `-Dsonar.python.coverage.reportPaths=coverage.xml` | `requirements.txt`, `requirements/*.txt`, `constraints.txt` | `.py`, `.pyw` |
| **Flutter / Dart** | Dart | `flutter` (`dart`) | `pubspec.yaml` | `flutter test --coverage` (`test_report.json` + `lcov.info` / `total_lcov.info`) | `sonar-scanner` (generic coverage XML + `dart analyze` issues or Flutter plugin) | `pubspec.yaml` | `.dart`, `.yaml`, `.yml` |
| **iOS / macOS** | Swift, Objective-C, C/C++ | `ios` (`xcode`, `swift`, `cocoapods`) | `Podfile`, `Package.swift`, `*.xcodeproj`, `*.xcworkspace` | `xcodebuild test` / `swift test` | `sonar-scanner` with Swift/Objective-C sources | CocoaPods / SwiftPM manifests | `.swift`, `.m`, `.mm`, `.h`, `.hpp`, `.c`, `.cpp` |
| **Database / SQL & PL/SQL** | SQL, PL/SQL, T-SQL, XML/YAML changelogs | `liquibase` for a changelog, `sql` (`plsql`) for SQL and PL/SQL files without one, or combined block on Maven/Gradle | `liquibase.properties`, `changelog.xml`, `changelog.yaml`, `changelog.sql`, `db/changelog/db.changelog-master.xml`; only SQL and PL/SQL files → `sql` | `liquibase validate` (coverage gate automatically marked `NOT_REQUIRED` for standalone SQL) | `sonar-scanner` with `-Dsonar.sources`, `inclusions`, `exclusions`, and PL/SQL suffixes | JDBC/extension JARs scanned via Nexus IQ | `.sql`, `.pls`, `.pks`, `.pkb`, `.plb`, `.prc`, `.fnc`, `.trg`, `.xml`, `.yaml` |

#### B. Requirements per Security / Quality Tool & Stage

| Stage / Tool | Required Configuration (`devsecops_config.yaml` / `config.yaml`) | Agent / Platform Prerequisites | Stage Outputs & Enforcement |
| :--- | :--- | :--- | :--- |
| **Build & Unit Tests** (Stages 0 & 2) | Optional `buildTool` (auto-detected if omitted/invalid), `build.<tool>`, `tests.unitTests.<tool>`, `coverage.minLine` | Matching SDK/compiler on agent (JDK, Gradle/Maven, .NET SDK or VS BuildTools `MSBuild.exe` / `NuGet.exe` / `vstest.console.exe` / `OpenCover.Console.exe`, Node.js, Python, Flutter, Liquibase) | Compiles artifact, runs unit tests, aggregates JaCoCo/LCOV/Cobertura/OpenCover reports, and enforces line coverage gate |
| **Nexus IQ & GoldenFix** (Stage 3) | `tools.nexusIq.application`, `credentialsId`, `scanPatterns`; optional `goldenFix.enabled: true` + `scm.bitbucket` | Jenkins Nexus Platform Plugin, `git`, `curl` / `curl.exe` | Evaluates open-source dependencies, verifies upgrades in an isolated Git worktree, updates manifests/version catalogs, and raises Bitbucket PR |
| **HCL AppScan SAST** (Stage 4) | `appId`; optional `asoc.doCompile`, `asoc.<tool>` (the API key is the functional user's one in the library defaults) | HCL AppScan SAClientUtil (`SA_LINUX_URL` / `SA_WIN_URL` or `appscanPath`) for the IRX, `curl` (`curl.exe` on Windows) | Compiles sources, generates `.irx`, uploads and queues it through the AppScan REST API v4, downloads reports without Noise issues, enforces severity limits, and posts inline PR comments on vulnerable lines |
| **SonarQube SCA** (Stage 5) | `tools.sonar.projectKey` (auto-generated as `com.bbh:<service>` if omitted), `installationName`, `authToken` / `credentialsId` | Jenkins SonarQube plugin (`withSonarQubeEnv`), `SonarScanner.MSBuild.exe` / `dotnet-sonarscanner` / `sonar-scanner` | Executes static code quality & security analysis, fetches issue counts via REST API, enforces quality gate, and posts inline PR comments |
| **Delivery & Deployment** (Stages 6, 7, 12, 13) | `deployTarget` (`openshift` or `vm`, auto-detected if omitted), `deploy.<target>.<rd\|qc>`, optional `delivery.push.<maven\|gradle>`, `delivery.pull`, `delivery.nuget`, `delivery.nexusDeliveryEnabled` | OpenShift (`oc`, `skopeo`) or VM (SSH / UrbanCode Deploy DOD plugin), Nexus credentials | Publishes the snapshot under one tag per run (`build.tag`) or the container images, downloads that snapshot in the extended pipeline, pushes the NuGet release of a .NET service and deploys to RD and QC (gated by `release-gate.json`) |
| **Regression / Smoke / Performance** (Stages 8, 9, 10) | `tests.regression.jobs` (`urls`), `tests.smoke.jobs` (`urls`), `tests.performance.jobs` (`urls`) | Local Jenkins jobs or Parameterized Remote Trigger plugin | Triggers test jobs in parallel batches and records results in HTML report and InfluxDB |
| **HCL AppScan DAST** (Stage 11) | `dast.enabled: true`, `dast.targetUrl`, `dast.scanName`, `appId` | Running service in RD reachable at `dast.targetUrl` (optional `dast.presenceId`) | Executes dynamic runtime security scan, downloads HTML/PDF reports, enforces policy, and raises DAST findings PR |

#### C. Breakdown per Pipeline Type

| Pipeline Entry Point | Stages Executed | Required Config Sections | Produced Artifacts & PRs |
| :--- | :--- | :--- | :--- |
| `devSecOpsPipeline` *(Full Pipeline)* | All stages: 1 (Monitor), 0 (Node.js optional), 2 (Build + Unit Tests + Coverage), 3 (Nexus IQ + GoldenFix), 4 (AppScan SAST), 5 (SonarQube), 6 (Nexus Snapshot), 7 (RD Deploy), 8 (Regression), 9 (Smoke), 10 (Performance), 11 (AppScan DAST), 12 (Nexus Release), 13 (QC Deploy) | `appId`, `tools.nexusIq`, `tools.sonar`, `deploy.<target>`, `tests.<regression\|smoke\|performance>`, `dast` | `report/pipeline-report.html`, `appscan-report*.html`, `appscan-dast-report*.pdf`, `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR, Static Security PR (inline SAST + SonarQube comments), DAST PR |
| `devSecOpsSecurityPipeline` *(Security Pipeline)* | Stages 1, 0 (optional), 2, 3, 4, 5, 6; optionally triggers `extendedPipeline` | `appId`, `tools.nexusIq`, `tools.sonar`, `deploy.<target>.rd` | `report/pipeline-report.html`, `appscan-report*.html`, `pipeline-config.yaml`, `release-gate.json`, `build.tag`, GoldenFix PR, Static Security PR |
| `devSecOpsExtendedPipeline` *(Extended Pipeline)* | Stages 1 (copies state and `build.tag` from `securityPipeline`), Download artifacts from Nexus Repo, 7, 8, 9, 10, 11, 12, 13 | `appId`, `deploy.<target>`, `tests.<regression\|smoke\|performance>`, `dast` | `report/pipeline-report.html`, `appscan-dast-report*.html/.pdf`, `pipeline-config.yaml`, `release-gate.json`, DAST PR |
| `devSecOpsSASTScanningPipeline` *(SAST-Only Pipeline)* | Stages 1, 0 (optional), 4 (AppScan SAST) | `appId` | `report/pipeline-report.html` (SAST view), `appscan-report*.html`, `pipeline-config.yaml`, `release-gate.json` |
| `devSecOpsNexusIqGoldenFixPipeline` *(Nexus IQ + GoldenFix)* | Stages 1, 0 (optional), 2 (Build), 3 (Nexus IQ + GoldenFix) | `tools.nexusIq.application` + `scanPatterns`, `goldenFix`, `scm.bitbucket` | `report/pipeline-report.html` (Nexus IQ + GoldenFix view), `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR |

---

## 2. The Jenkinsfile: Defining Your Pipeline

The `Jenkinsfile` in your repository root specifies the services and Jenkins agent labels:

```groovy
@Library('DSOEnhanced@DSOEnhancedWithoutPortal') _

devSecOpsPipeline(
    projectNames: 'backend-api,web-gui',
    agentNames: ['linux-rhel8', 'linux-docker']
)
```

### Available Pipeline Entry Points:
- `devSecOpsPipeline`: Complete delivery pipeline (Build, Unit Tests, SAST, DAST, SonarQube, Nexus IQ, Delivery, Deployment).
- `devSecOpsSecurityPipeline`: Fast security validation (Build, Unit Tests, SAST, SonarQube, Nexus IQ, Snapshot Delivery).
- `devSecOpsExtendedPipeline`: Downstream deployment, functional/performance testing, DAST, and release promotion pipeline.
- `devSecOpsSASTScanningPipeline`: Dedicated HCL AppScan SAST pipeline.
- `devSecOpsNexusIqGoldenFixPipeline`: Dedicated automated dependency scan and GoldenFix remediation pipeline.

---

## 3. The devsecops_config.yaml Configuration File

The `devsecops_config.yaml` file resides in the root directory of your repository.

### File Evaluation Order:
- The library looks for `devsecops_config.yaml` first.
- If not found, it checks for `config.yaml` (100% backward compatible).
- The library reads the `projects:` section where each service of your application is declared.

### How a value is chosen:
- A key you set is always used, also when it equals the default, so the files of earlier releases keep working.
- A value of the wrong kind (text for a number, a word for `true`/`false`, a map for a list) falls back to the library default, with an `[INIT] [WARN]` line that names the key, the value and the default.
- A name in `projectNames` that the file does not have is left out with an `[INIT] [WARN]` line; when no name is left, the run builds every service of the file.
- A key whose value the library would use anyway (the library default, or what the auto-detection finds in the repository, such as `buildTool: maven` next to a `pom.xml`) is named in an `[INIT] [INFO] ... - the key can be removed from devsecops_config.yaml` line, so you can shorten the file.

---

## 4. Multi-Service and Multi-Repository Bitbucket Support

The standalone pipeline natively supports complex project architectures:

### 1. Multiple Services in One Pipeline (Multi-Service)
- Declare multiple services under `projects:` (e.g., `backend-api` and `web-gui`).
- Each service can point to **its own independent Bitbucket repository** (`scm.bitbucket.repositoryUrl`).
- Each service maintains its own target branch (`scm.bitbucket.targetBranch`) and pull request reviewers list (`scm.bitbucket.reviewers`).

### 2. Multi-Module Projects & Version Catalogs
- Multi-module Maven projects (`pom.xml`), Gradle subprojects, and `.NET` solutions (`.sln`) are automatically discovered and built.
- Shared version catalogs across all ecosystems — Gradle (`gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle(.kts)`, `java-platform`, `buildSrc`), Maven (parent `pom.xml`, imported BOMs), and .NET (`Directory.Packages.props`, `Directory.Build.props`, custom `.props`), as well as external version catalog repositories (`versionCatalogRepo`) — are parsed and updated in a single consolidated Pull Request by GoldenFix.

---

## 5. Supported Languages and Build Tools

The pipeline supports all enterprise programming languages and build tools (with automatic detection and `[WARN]` fallback when an invalid value is configured):

- **Java / Kotlin / Groovy / Android:**
  - Build via Maven (`buildTool: 'maven'`) or Gradle (`buildTool: 'gradle'` / `'android'`).
  - Standard command: `clean package -DskipTests` or `clean assemble -x test`.
  - **Plugins from the BBH Nexus:** when a Gradle or Maven step fails because a plugin is not in the repositories of the build, the step runs once more with the plugin repository of the BBH Nexus (`pluginRepositories` of the library defaults), and the console says which plugins came from it and where to add the repository.
- **Node.js / TypeScript / Angular / React / Vue:**
  - Build via npm (`buildTool: 'npm'`).
  - Standard command: `npm ci && npm run build --prod`.
  - Can also run as **Stage 0** (`build.node.enabled: true`) before Maven, Gradle, or .NET backend builds.
- **Python:**
  - Build via pip (`buildTool: 'pip'`), auto-detected from `requirements.txt`, `pyproject.toml`, `setup.py`, or `Pipfile`.
- **SQL / PL-SQL (Liquibase):**
  - Automatic detection of database changelogs when `buildTool` is empty. With the auto-resolve of the settings on (the default), the application files come first (`pubspec.yaml`, the iOS files, `pom.xml`, `build.gradle` and the .NET files), then the Liquibase files (`liquibase.properties`, `changelog.xml`, `changelog.yaml`, `db/changelog/db.changelog-master.xml`, `src/main/resources/db/changelog/db.changelog-master.xml` and the others of chapter 14), then the Python and Node.js files, so a Maven or Gradle application that ships a Liquibase changelog stays a Maven or Gradle service; with `autoresolve parameters: false`, `detectTool()` checks them after Flutter, Maven, Gradle and .NET.
  - Command: `liquibase updateSQL` in Build artifact and `liquibase validate` in Unit tests, both with `--changelog-file=` and the changelog found in the repository, when the auto-resolve fills them (the default); with `autoresolve parameters: false`, `liquibase validate` with the flags of `build.liquibase` when it sets no command. A service with a database block gets no command from the auto-resolve: it runs `build.liquibase` and `tests.unitTests.liquibase` only when it sets them (`validate` when it sets only flags).
  - With `database.changelogFile` the service gets the database block: the Build artifact stage validates and packages the changes offline, Unit tests deploys them to a test database and runs utPLSQL with coverage, and the zip is delivered to Nexus and deployed to RD and QC with Jenkins credentials and a compile check. See [chapter 14 of the library documentation](../documentation.html#ch14).
  - The database block is for Oracle. In a repository with an application, the folder of the changelogs is a Liquibase service of its own, listed before the application service; chapter 14 shows four complete repository layouts.
  - `buildTool: sql` (also `plsql`) is for a repository of SQL and PL/SQL files without a changelog. The library writes the changelog `dso-changelog.xml` itself at the start of the run and then treats the service as a database block: files that start with `--liquibase formatted sql` are included as they are, `CREATE OR REPLACE` code runs again whenever its file changes, every other file runs once in the natural order of its path, and the files of the utPLSQL test folder go to the test database only. A repository with only such files and no `buildTool` is detected as `sql`, with `database.searchPath` set to the one top folder of the files and the JDK from the auto setup. A service with `buildTool: sql` that has a changelog after all runs as `buildTool: liquibase`, with a warning.
  - Static code analysis and validation through SonarQube (`sonar-scanner`) and AppScan SAST.
- **C# / F# / VB.NET and .NET (`dotnet` / `msbuild`):**
  - Build via `buildTool: 'dotnet'` (aliases: `'csharp'`, `'c#'`, `'net'`, `'fsharp'`, `'vbnet'`) or `'msbuild'` (aliases: `'vs'`, `'netfx'`), auto-detected from `.sln`, `.csproj`, `.fsproj`, `.vbproj`, `global.json`, `Directory.Packages.props`, `Directory.Build.props`, `packages.config`, `build.cake`, or `.nuke`.
  - **Build Modes (`build.dotnet.mode`, default `'auto'`):**
    - `'auto'`: automatically chooses `'dotnet'` for modern SDK-style projects (`<Project Sdk="...">` targeting `net5.0`+ / `net8.0`, `global.json`, `Directory.Packages.props`), `'msbuild'` on Windows for classic `.NET Framework` projects (`packages.config`, `net48`, `<TargetFrameworkVersion>v4.*`, or explicit MSBuild/OpenCover settings), `'hybrid'` on Windows when a solution mixes classic `.NET Framework` and modern SDK projects, or `'cake'` / `'nuke'` when Cake/NUKE build scripts are used.
    - `'msbuild'`: classic Windows `NuGet.exe restore` + `MSBuild.exe` (`JAVA_HOME` is not required).
    - `'dotnet'` (or `useDotnetCli: true`): `.NET` SDK CLI (`dotnet restore`, `dotnet build`, optional `dotnet pack` & `dotnet publish`).
    - `'hybrid'`: runs `dotnet restore` + `NuGet.exe restore`, builds with `MSBuild.exe`, and executes both `vstest.console.exe` and `dotnet test`.
    - `'cake'` / `'nuke'`: runs `dotnet cake` or `build.cmd` / `./build.sh` / `nuke`.
  - **Unit Test Runners (`tests.unitTests.dotnet.runner`, default `'auto'`):** `'auto'`, `'dotnet'` (`dotnet test`), `'vstest'` (`vstest.console.exe`), `'mstest'` (`MSTest.exe`), `'nunit'` (`nunit3-console.exe`), `'xunit'` (`xunit.console.exe`), `'hybrid'`.
  - **Coverage Tools (`tests.unitTests.dotnet.coverageTool`, default `'auto'`):** `'auto'`, `'coverlet'` / `'xplat'` (`XPlat Code Coverage` in OpenCover format, the default for `dotnet test`, read by the report and by SonarQube), `'cobertura'` (Cobertura XML, counted by the report but not imported by SonarQube for C#, so a `[WARN]` is logged), `'opencover'` (`OpenCover.Console.exe`), `'dotcover'` (`dotCover.exe`), `'none'`.
  - **Invalid Parameter Handling:** any invalid `buildTool`, `mode`, `useDotnetCli`, `runner`, or `coverageTool` logs a `[WARN]` message and falls back to the auto-detected value.
- **Flutter / Dart & iOS / Swift:**
  - Mobile, web, and desktop packaging with LCOV test coverage and SonarQube analysis.

---

## 6. Unit Testing and Multi-Report Code Coverage

- **Automated Multi-Report Discovery:**
  - The pipeline automatically searches all workspace directories for test reports:
    - **JaCoCo XML:** Java, Kotlin, Groovy test coverage reports.
    - **Cobertura XML & OpenCover XML:** C# / .NET Coverlet (`XPlat Code Coverage`) and `OpenCover.Console.exe` reports (supported on both Windows and Linux agents).
    - **LCOV (`lcov.info`):** JavaScript, TypeScript, Angular, Python, Flutter, and Cypress reports.
  - Visual Studio `.trx` test results (`**/TestResults/*.trx`) and JUnit XML are automatically parsed for `.NET` unit tests.
- **Node.js & Cypress Unit Testing:**
  - Dedicated support for Node.js unit and component tests via Cypress (`tests.unitTests.cypress`).
  - Seamless execution of Cypress tests (`npx cypress run --component` or custom command) within the Node.js environment.
  - Ingestion of JUnit XML test results and automatic aggregation of Cypress LCOV coverage data (`coverage/lcov.info`).
- **Per-Module Reporting:**
  - Every individual report has a dedicated log and report line showing file path, tool (`JaCoCo`, `Cobertura`, `OpenCover`, `LCOV`, `Flutter`), line count, and percentage.
- **Project Run Summary:**
  - Calculates an aggregated average line for the entire project (`All services`).
- **Release Quality Gate:**
  - `coverage.minLine` (enterprise default `60` in bundled `defaults.yaml`) enforces code coverage. If the project average is below the minimum, the release gate blocks promotion.

---

## 7. Static Application Security Testing (SAST) – HCL AppScan

Static security analysis executed in the cloud:

- **Stages at the same time:**
  - By default SAST runs at the same time as the unit tests, Nexus IQ and SonarQube, as a parallel branch of *Static analysis*: it generates the IRX archive in a copy of the workspace, submits the scan to HCL ASOC and reads the results when the scan ends.
  - `staticAnalysis.parallel: false` in any service runs the four stages one after another; the report shows how long they took one after another and in parallel.
- **Credentials Configuration:**
  - SAST and DAST of every service sign in with the one API key of the functional HCL ASOC user, kept by the DevSecOps team in the library defaults (`asoc.keyId` and the Jenkins Secret text credential named in `asoc.token` of `resources/com/bbh/devsecops/defaults.yaml`).
  - A service sets only its application ID; a `keyId`, `token` or `credentialsId` of a service is ignored with a `[WARN]` line:
    ```yaml
    asoc:
      appId: '10982'
    ```
- **Custom Scan Name:**
  - Optional `sast.scanName` defines custom scan naming in ASOC.

---

## 8. Dynamic Application Security Testing (DAST) – HCL AppScan

Runtime security scanning against deployed web applications:

```yaml
dast:
  targetUrl: 'https://dev-payhub.bbh.com'
  presenceId: 'presence-internal-bbh-id'
  credentialsId: 'dast-test-user-creds'
```
- `presenceId` allows secure scanning of internal applications behind the BBH firewall.

---

## 9. Code Quality – SonarQube & Alternative Badge Tokens

SonarQube analysis configuration:

```yaml
tools:
  sonar:
    installationName: 'BBH-SonarQube'
    authToken: 'sonar-auth-token-id'
    # Option 1: Direct raw badge token
    badgeToken: 'raw-token-from-sonarqube'
    # Option 2 (Alternative): Jenkins credentials secret
    badgeCredentialsId: 'jenkins-sonar-badge-secret-id'
```
> [!TIP]
> Provide **either** `badgeToken` OR `badgeCredentialsId`. Configuring both is not required.

### How the Analysis Runs:
- **Gradle, Android and Maven with analysis commands of their own** (`tools.sonar.gradle.tasks`, for Android as well, or `tools.sonar.maven.goals`) run them with the plugin of the build.
- **Every other Gradle, Android and Maven service, and every npm, pip and iOS service** is analysed with the `sonar-scanner` CLI: the Jenkins tool `sonar-scanner-cli-<version>` named in `tools.sonar.sonarScannerVersion`, otherwise `sonar-scanner` on the `PATH` of the agent. It gets `tools.sonar.<tool>.flags` (of a Gradle, Android or Maven block only the arguments that start with `-Dsonar.`), the sources of the service and the coverage report of pip (`coverage.xml`) and npm (`coverage/lcov.info`). An argument of the service wins over the one the library would add.
- **Branch and retry:** the branch of the build is passed as `-Dsonar.branch.name`. A server that refuses the branch name, as the Community Edition does, fails that attempt; the library logs a `[SONAR]` line and runs the analysis once more without the branch, and the stage fails only when the second attempt fails as well. `tools.sonar.branchAnalysis: false` skips the first attempt.

---

## 10. Dependency Security & GoldenFix – Nexus IQ

- **Nexus IQ:**
  - Open-source component vulnerability analysis:
    ```yaml
    tools:
      nexusIq:
        applicationId: 'payhub-backend-api'
    ```
- **GoldenFix Automated Pull Requests:**
  - Automatically raises vulnerable dependencies to safe versions:
    ```yaml
    goldenFix:
      enabled: true
      pullRequest: true
    ```
  - Creates a dedicated branch and opens a Bitbucket Pull Request with the updated `pom.xml`, `build.gradle`, `package.json`, `requirements.txt`, `libs.versions.toml`, `*.csproj` / `*.fsproj` / `*.vbproj`, `Directory.Packages.props`, or `packages.config` (supported on both Linux and Windows agents).

- **Shared External Version Catalog Repository (`versionCatalogRepo` Priority Upgrade):**
  - When version catalogs, parent POMs, imported BOMs, or Central Package Management files (`libs.versions.toml`, `Directory.Packages.props`, `Directory.Build.props`, `pom.xml`, `gradle.properties`) reside in a shared external Bitbucket repository used across multiple projects:
    ```yaml
    goldenFix:
      versionCatalogRepo:
        url: 'ssh://git@bitbucket.bbh.com/shared/version-catalogs.git'
        credentialsId: 'bitbucket-http-credentials'
        branch: 'main'
        path: 'catalogs/libs.versions.toml'
    ```
  - **Priority Upgrade Rule:** GoldenFix (`SharedCatalogRepositoryService`) inspects the shared `versionCatalogRepo` **first**. Any vulnerable dependency (or version property referenced by the project) that is managed in the shared catalog repository is upgraded there first, committed, pushed, and raised/updated as a shared Pull Request in the catalog repository across all participating projects.
  - Only remaining dependencies that are **not** managed in the shared version catalog are upgraded in the local project repository.

### Decoupled Multi-Module & Multi-Repository Static Security Flow:
For every module in a multi-module project and every repository attached to a multi-repository project:
1. **Stage 1: Build Artifact (without tests):** The pipeline first builds application artifacts for every service/module without executing unit tests (e.g., `-DskipTests` / `-x test`, or `NuGet.exe` + `MSBuild.exe` / `dotnet build` for .NET). If a Node.js build is configured (`build.node.enabled: true`), npm commands run as Stage 0 before compiling the backend application.
2. **Stage 2: Static analysis at the same time (Unit Tests with Coverage + Nexus IQ + SAST + SonarQube -> Golden PR):** by default the four run as parallel branches of the Jenkins stage *Static analysis* (`staticAnalysis.parallel: false` in any service runs them one after another).
   - **Unit Tests & Coverage (per module/repository):** Runs automated unit tests (`vstest.console.exe`, `dotnet test`, Gradle, Maven, pytest, npm, Flutter, Cypress), parses `.trx` and JUnit XML results, and aggregates JaCoCo, Cobertura, OpenCover & LCOV code coverage per module/repository.
   - **Nexus IQ & GoldenFix:** Evaluates open-source dependencies against security policies, upgrades shared `versionCatalogRepo` catalogs first and local manifests second, and raises/updates the Golden Pull Request.
   - **SAST (HCL AppScan):** Compiles and queues the scan of every service in a copy of the workspace (`<WORKSPACE_TMP>/dso-sast-workspace`), so its compile never touches the files the other branches read, then waits for the results and the reports.
   - **SonarQube:** Starts a service once the unit tests of every service in the same source folder have written their coverage. If code coverage was not yet calculated during Unit tests, SonarQube triggers coverage collection (`checkCoverage()`) automatically before executing the scanner, ingests test & coverage reports, and renders SonarQube badges.
3. **Stage 3: Results:** A failing branch turns its stage red while the other branches finish. The console and the report show how long the four stages would take one after another and how long they took at the same time.
   - The full and extended pipelines run the regression, smoke and performance tests and DAST at the same time in the same way (*Dynamic analysis*, `dynamicAnalysis.parallel`).
4. **Stage 4: Static Security PR Decoration & Final Report:**
   - Posts inline SAST and SonarQube vulnerability comments with suggested remediation directly on the exact file and line of the Golden Pull Request (resolved relative to each repository root).
   - Generates the final HTML report containing the **Unit Tests** card (listed per module/repository + all-modules summary), **Code Coverage** card (listed per module/repository + summary), **SAST** report links, **SonarQube** dashboard link & badges, and **Nexus IQ GoldenFix** card with Golden Fix link, Golden Pull Request link, and SAST inline comments list.

---

## 11. Deployments and "Disable Nexus Delivery" (OpenShift & VM)

### Skipping Nexus Artifact Publishing:
If your deployment does not require publishing binaries to Nexus, toggle delivery off:
- For Virtual Machines (VM): `deploy.vm.rd.nexusDeliveryEnabled: false`
- For OpenShift containers: `deploy.openshift.rd.nexusDeliveryEnabled: false`
- `delivery.nexusDeliveryEnabled` of the service wins over both settings.
- The pipeline skips artifact upload and suppresses delivery validation errors.

### Snapshot tag, download and release:
- Stage 6 publishes the snapshot of every service under one tag per run, `<BUILD_NUMBER>-<yyyyMMdd-HHmmss>` (UTC), and writes it to `build.tag`. A Gradle service names the files to publish in `delivery.push.gradle.artifacts`; without them it is not required. A Maven service uses `delivery.push.maven` (or the earlier `delivery.maven` with `build.buildPath`).
- The extended pipeline copies `build.tag` from the security job. Its stage *Download artifacts from Nexus Repo* downloads that snapshot into `delivery.pull.outputDirectory` when `delivery.pull` is set; an OpenShift service deploys its image instead.
- A VM service is deployed to the lower test region with UrbanCode Deploy (DOD); the component versions carry the tag of `build.tag`, otherwise the build number.
- Stage 12 pushes the NuGet release of a .NET service with `delivery.nuget.source`. For the other build tools the release delivery is not implemented yet: the stage is not required and the snapshot stays the delivered artifact.

### Optional OpenShift QC Region:
- The `deploy.openshift.rd.regions.qc` block is entirely optional. Omitting it will not cause validation errors.

---

## 12. Telemetry, DORA Metrics, InfluxDB, and Grafana

Every stage automatically emits telemetry points to InfluxDB:
- **DORA Metrics:** Deployment Frequency, Lead Time for Changes, Change Failure Rate, Time to Restore Service (MTTR).
- **Quality & Duration Metrics:** Build duration, test counts, JaCoCo/LCOV coverage points, vulnerability totals.
- Visualize results across BBH enterprise Grafana dashboards.

The pipeline report of every run also shows:
- **Manual Actions vs DevSecOps** (at the bottom): one line per step that ran, in the order of the pipeline, recalculated on every run. *Step* shows the step with its own time. *Manual actions* adds what a person needs to start it: 10 minutes of a manual trigger for the build, Nexus IQ, SAST, SonarQube and each of the regression, smoke and performance tests, 6 hours of the CyberTrack ticket for a DAST, and nothing for the unit tests, the Nexus delivery and the deployments (the SRM ticket of the higher region counts 0 minutes). *DevSecOps Automated Actions* shows the time of the pipeline; of the steps that run in parallel only the longest has a time. *Time gained* is the saving in percent, and the line *Summary* gives both totals and the gain in seconds, minutes and percent. With more than one service, every step lists all configured services with the time the step took for each. The minutes are `report.manualEffort` in the library defaults.

---

## 13. Complete devsecops_config.yaml Template with Field Reference

```yaml
# ==============================================================================
# BBH DevSecOps Pipeline Configuration (Standalone / Without Portal)
# Place this file at repository root: devsecops_config.yaml
# ==============================================================================

projects:
  # ----------------------------------------------------------------------------
  # SERVICE 1: Backend API (Java / Spring Boot with Maven)
  # ----------------------------------------------------------------------------
  backend-api:
    # 1. Source Control Management (Bitbucket)
    scm:
      bitbucket:
        repositoryUrl: 'ssh://git@bitbucket.bbh.com/pay/payhub-backend.git'
        credentialsId: 'bitbucket-ssh-key'
        targetBranch: 'main'
        reviewers: ['john.doe@bbh.com', 'jane.smith@bbh.com']

    # 2. Build Stage
    build:
      tool: 'maven'                               # Supported: maven, gradle, android, dotnet, msbuild, npm, pip, ios, liquibase, sql, flutter
      tasks: 'clean package -DskipTests'          # Build command

    # 3. Unit Tests & Code Coverage
    unitTests:
      tasks: 'test'                               # Test execution command
    coverage:
      minimum: 80                                 # Minimum required coverage (%)
      # reportPath: empty = auto-detect JaCoCo XML, Cobertura XML, OpenCover XML & LCOV throughout project

    # 4. Static Application Security Testing (HCL AppScan SAST)
    asoc:
      appId: '10982'                              # Application ID in HCL ASOC (the API key comes from the library defaults)
    sast:
      scanName: 'PAYHUB-BACKEND-API'              # Optional custom scan label

    # Stages at the same time: both are on by default, set false only to run the stages one after another
    # staticAnalysis:  {parallel: false}          # Unit tests, Nexus IQ, SAST and SonarQube one after another
    # dynamicAnalysis: {parallel: false}          # Regression, smoke and performance tests and DAST one after another

    # 5. Security & Code Quality Tools
    tools:
      nexusIq:
        applicationId: 'payhub-backend-api'       # Exactly ONE app per service
      sonar:
        installationName: 'BBH-SonarQube'         # Jenkins SonarQube tool name
        authToken: 'sonar-auth-token-id'          # Jenkins credentials ID
        badgeCredentialsId: 'sonar-badge-secret'  # Or use badgeToken: '...'

    # 6. Automated Vulnerability Remediation (GoldenFix)
    goldenFix:
      enabled: true                               # Run GoldenFix analysis
      pullRequest: true                           # Open automated Bitbucket PR
      versionCatalogRepo:
        url: 'ssh://git@bitbucket.bbh.com/shared/catalogs.git'
        credentialsId: 'bitbucket-http-credentials'
        branch: 'main'
        path: 'catalogs/libs.versions.toml'

    # 7. Deployment & Delivery
    deploy:
      openshift:
        rd:
          nexusDeliveryEnabled: false             # false = skip Nexus artifact push
          appName: 'payhub-backend'
          namespace: 'payhub-dev'
          deployment: 'payhub-backend-deployment'

  # ----------------------------------------------------------------------------
  # SERVICE 2: Web Frontend (Angular / Node.js)
  # ----------------------------------------------------------------------------
  web-gui:
    scm:
      bitbucket:
        repositoryUrl: 'ssh://git@bitbucket.bbh.com/pay/payhub-gui.git'
        credentialsId: 'bitbucket-ssh-key'
        targetBranch: 'main'
        reviewers: ['john.doe@bbh.com']

    build:
      tool: 'npm'
      tasks: 'npm ci && npm run build --prod'

    unitTests:
      tasks: 'npm test -- --watch=false --browsers=ChromeHeadless'
      cypress:
        enabled: true                             # Enable Cypress component/unit tests
        command: 'npx cypress run --component'    # Custom test execution command
        reportsDir: 'cypress/results'             # Folder containing JUnit XML results
    coverage:
      minimum: 80

    asoc:
      appId: '10983'

    tools:
      nexusIq:
        applicationId: 'payhub-web-gui'
      sonar:
        installationName: 'BBH-SonarQube'
        authToken: 'sonar-auth-token-id'
        badgeToken: 'raw-badge-token-gui'

    goldenFix:
      enabled: true
      pullRequest: true

    deploy:
      openshift:
        rd:
          nexusDeliveryEnabled: false
          appName: 'payhub-gui'
          namespace: 'payhub-dev'
          deployment: 'payhub-gui-deployment'
```

---

### Non-Technical Field Quick Reference:

| YAML Key | Meaning | Example Value | Required? |
|---|---|---|:---:|
| `projects.<service>` | Identifier name of your component | `backend-api` | <span class="badge badge-required">YES</span> |
| `scm.bitbucket.repositoryUrl` | Git repository SSH URL | `ssh://git@bitbucket.bbh.com/...` | <span class="badge badge-required">YES</span> |
| `scm.bitbucket.targetBranch` | Main branch to build | `main` | <span class="badge badge-required">YES</span> |
| `build.tool` | Compilation tool | `maven`, `gradle`, `android`, `dotnet`, `msbuild`, `npm`, `pip`, `ios`, `liquibase`, `sql`, `flutter` | <span class="badge badge-required">YES</span> |
| `build.tasks` | Build command string | `clean package -DskipTests` | <span class="badge badge-required">YES</span> |
| `unitTests.tasks` | Test command string | `test` or `npm test` | <span class="badge badge-optional">OPTIONAL</span> |
| `unitTests.cypress.enabled` | Enable Cypress component tests | `true` or `false` | <span class="badge badge-optional">OPTIONAL</span> |
| `unitTests.cypress.command` | Custom Cypress command | `npx cypress run --component` | <span class="badge badge-optional">OPTIONAL</span> |
| `unitTests.cypress.reportsDir` | Cypress JUnit reports dir | `cypress/results` | <span class="badge badge-optional">OPTIONAL</span> |
| `coverage.minimum` | Quality gate threshold | `80` | <span class="badge badge-default">DEFAULT 80</span> |
| `asoc.appId` | AppScan application ID of the service (SAST and DAST); the API key is set once in the library defaults | `10982` | <span class="badge badge-required">YES</span> |
| `sast.scanName` | ASOC scan display name | `PAYHUB-NIGHTLY` | <span class="badge badge-optional">OPTIONAL</span> |
| `tools.sonar.authToken` | SonarQube authentication secret | `sonar-token-id` | <span class="badge badge-required">YES</span> |
| `tools.sonar.badgeToken` | Sonar badge token (raw) | `raw-token-string` | <span class="badge badge-optional">1 OF 2</span> |
| `tools.sonar.badgeCredentialsId` | Sonar badge secret in Jenkins | `sonar-badge-cred` | <span class="badge badge-optional">1 OF 2</span> |
| `tools.nexusIq.applicationId` | Single app ID in Nexus IQ | `payhub-backend-api` | <span class="badge badge-required">YES</span> |
| `goldenFix.pullRequest` | Auto-create remediation PR | `true` | <span class="badge badge-default">DEFAULT TRUE</span> |
| `nexusDeliveryEnabled` | Toggle Nexus delivery | `false` | <span class="badge badge-optional">OPTIONAL</span> |

---

## 14. Auto-Resolve in Standalone Mode (`autoresolve parameters: true`)

The standalone library features automated repository stack detection (`RepoStackAutoResolver`).

### Default Behaviour:
Auto-resolve is enabled by default (`autoresolve parameters: true`). Application teams can omit build tool details, compiler paths, Node.js directories, and Sonar properties from `devsecops_config.yaml`.

### Minimal Configuration Example:
Application teams need to provide only the application ID in `devsecops_config.yaml`; the AppScan API key is the one of the functional user in the library defaults:
```yaml
projects:
  payment-service:
    appId: "APP-54321"
```

The pipeline automatically resolves the build tool (Maven, Gradle, Android, iOS, Flutter, C# / .NET, Liquibase, SQL / PL/SQL files, Python or npm), compiler version, Stage 0 Node.js frontend, OpenShift target, and SonarQube properties from the repository workspace files.

### Disabling Auto-Resolve:
To mandate strict manual configuration and disable heuristic resolution, set `autoresolve parameters: false`:
```yaml
projects:
  payment-service:
    autoresolve parameters: false
    buildTool: maven
    deployTarget: vm
    appId: "APP-54321"
```

---

## 15. Auto-Resolved Parameters Reference

The build tool is detected in this order, in the library and in **Scan & Auto-fill** of the Portal alike: `pubspec.yaml` (Flutter), the iOS files, `pom.xml` (Maven), the Gradle files (Android with an `AndroidManifest.xml`), the .NET files, a Liquibase changelog, the Python files, `package.json` or `angular.json` at the root (npm), and at last a repository of only SQL and PL/SQL files (`sql`).

| Setting / Field | Target Key / Form Field | Workspace Indicator / Manifest | Resolved Default Value |
|---|---|---|---|
| Build Tool | `buildTool` | `pom.xml` present | `maven` |
| Build Tool | `buildTool` | `Podfile`, `Package.swift`, `*.xcodeproj` or `*.xcworkspace` present | `ios` |
| Build Tool | `buildTool` | `build.gradle(.kts)`, `settings.gradle(.kts)` or `gradle/wrapper/gradle-wrapper.properties` present | `gradle` |
| Build Tool | `buildTool` | Gradle files and `AndroidManifest.xml` at the root or in `app/src/main` | `android` |
| Build Tool | `buildTool` | `changelog.xml`, `changelog.yaml`, `liquibase.properties` | `liquibase` |
| Build Tool | `buildTool` | `pubspec.yaml` present | `flutter` |
| Build Tool | `buildTool` | `requirements.txt`, `pyproject.toml`, `setup.py`, `Pipfile` | `pip` |
| Build Tool | `buildTool` | `package.json` or `angular.json` at the root and none of the files above | `npm` |
| Build Tool Auto Setup | `buildToolAutoSetup` | Set to `true` whenever Maven or Gradle is detected | `true` |
| Java / JDK Version | `build.maven.javaVersion` / `build.gradle.javaVersion` | `<java.version>`, `<maven.compiler.source>`, `<maven.compiler.target>`, `<release>` in `pom.xml`, or `jvmToolchain`, `sourceCompatibility` in `build.gradle` | Matched JDK version (`17`, `21`, `11`, `8`) |
| Gradle Version | `build.gradle.version` | `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` | Exact Gradle wrapper version (e.g. `8.7`, `7.6`) |
| Node.js Stage 0 Enabled | `build.node.enabled` | `package.json` exists in root or standard frontend folders | `true` |
| Node.js Directory | `build.node.dir` | Detected subfolder holding `package.json` | `.`, `frontend`, `web`, `client`, or `ui` |
| Node.js Engine Version | `build.node.version` | `engines.node` in `package.json` (e.g. `>=20.0.0`) | Major Node version (e.g. `20`) |
| Cypress Unit Testing | `tests.unitTests.node.cypress` | `cypress` in `devDependencies` / `dependencies` of `package.json` | `true` |
| Jest Unit Testing | `tests.unitTests.node.jest` | `jest` in `devDependencies` / `dependencies` of `package.json` | `true` |
| Liquibase Build Command | `build.liquibase.commands` | Detected `changelog.xml` / `liquibase.properties` | `['updateSQL']` |
| Liquibase Test Command | `tests.unitTests.liquibase.commands` | Liquibase build tool | `['validate']` |
| Deployment Target | `deployTarget` | `Dockerfile`, `Containerfile`, `openshift/`, `k8s/`, `helm/` present | `openshift` (otherwise defaults to `vm`) |
| Sonar Project Key | `tools.sonar.projectKey` | Empty or missing in config | `com.bbh:<projectName>` or `com.bbh:<appName>` |
| Sonar Language | `sonar.language` | Source tree inspection (`pubspec.yaml` -> `dart`, `requirements.txt` -> `py`, Kotlin files -> `kotlin`, Liquibase -> `sql`, else `java`) | Detected language code |
| Sonar Sources Directory | `sonar.sources` | Missing `sonar-project.properties` | `src/main/java` (Java), `src/main/kotlin` (Kotlin), `lib` (Dart), `db` (SQL), `src` or `.` (Python) |
| Sonar Tests Directory | `sonar.tests` | Missing `sonar-project.properties` | `src/test/java` (Java), `src/test/kotlin` (Kotlin), `test` (Dart), `tests` (Python) |
| Sonar Java Binaries | `sonar.java.binaries` | Maven: `target/classes`; Gradle: `target/classes,build/classes/java/main` | Standard build output directories |
| Sonar Submodules | `sonar.modules` | Submodules in `pom.xml` (`<module>`) or `settings.gradle` (`include '...'`) | Comma-separated module names |
| Sonar Branch Pinning | `sonar.branch.name` | Auto-detected from pipeline branch (`CHANGE_BRANCH`, `BRANCH_NAME`, `GIT_BRANCH`) | Active evaluated branch (never master) |

---

## 16. Bitbucket Pull Request Decoration & Shift-Left Feedback

Security feedback is reported directly in Bitbucket Pull Requests:
1. **Static Analysis Consolidated Pull Request**:
   - Nexus IQ GoldenFix safe dependency updates, AppScan SAST inline vulnerability comments, and SonarQube quality findings are presented together in a single Pull Request review.
   - Findings marked as `Noise` are automatically excluded.
2. **Dynamic Analysis Dedicated Pull Request**:
   - DAST dynamic runtime findings are placed in a dedicated separate Pull Request to isolate runtime operational issues from source code changes.

### Pull Request of the Analysed Branch, Limits and Repeated Runs:
- **Where a comment goes:** the findings and the fix AppScan recommends are read from the AppScan on Cloud REST API (`Issues/Scan/<scan id>` and `Issues/<issue id>/Details`), not from the PDF or HTML report. A finding in a file the pull request changes is a comment of that file under the line of the finding. A finding in a file the pull request does not change is written in one pull request comment per file, which names the file and the line. A Nexus IQ upgrade is commented in the same way on the manifest that declares the dependency, with the version Nexus IQ recommends; AppScan knows only its own findings, so that fix comes from Nexus IQ. If the AppScan API gives no list of issues, a `[WARN]` line says so and the comments are built from the HTML report as before.
- **The pull request of the developer:** in a pull request build, or when the analysed branch has an open pull request in Bitbucket, that pull request gets the comments of the SAST, Nexus IQ and SonarQube findings and one summary comment. Branches the library creates itself (`GoldenFix-*`, `security-*`) are left out.
- **Limits:** `pullRequestDecoration.maxComments` (default 25) is the number of comments one run adds to one pull request; `pullRequestDecoration.sastMinSeverity` (default `MEDIUM`) and `pullRequestDecoration.sonarMinSeverity` (default `MAJOR`) leave out the findings below them. A wrong value falls back to its default with a `[WARN]` line, and a value equal to the default is named in an `[INFO]` line as a key that can be removed.
- **Repeated runs:** the library reads the comments that are already on the pull request and adds only those that are missing, so a second run of the same commit adds no duplicate.
- **Shared version catalog repository:** the clone of `goldenFix.versionCatalogRepo` signs in the way the push of the GoldenFix branch does: an SSH URL with the SSH key credential, an HTTPS URL with `authType: bearer` with a Secret text credential as the access token, any other HTTPS URL with a username and password credential. `credentialsId` and `authType` of the catalog fall back to those of `scm.bitbucket` of the service, and the credential never appears in the log.

---

## 17. SAST REST API and the AppScan API key

Static analysis communicates directly with HCL AppScan on Cloud via REST API v4:
- **Authentication:** Obtains an API bearer token with the one API key of the functional user, `asoc.keyId` and the Secret text credential named in `asoc.token` of `resources/com/bbh/devsecops/defaults.yaml` of the library. DAST reuses the token of SAST. A `keyId`, `token` or `credentialsId` under `asoc`, `sast` or `dast` of a service is ignored with a `[WARN]` line.
- **No secret on the console:** the sign-in body is written to the temporary folder of the build and removed after the call.
- **Noise Filtering:** Findings marked with status `Noise` in the AppScan portal are filtered out and not counted against the release quality gate.
