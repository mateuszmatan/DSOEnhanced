# ITSS COE OneView & DSOEnhanced – User Guide

> **Comprehensive Guide for the Portal-Integrated Version (`ITSS-COE-OneView` + `DSOEnhanced` library on `main` branch).**
> Designed for non-technical users, Product Owners, business analysts, and engineers onboarding applications at Brown Brothers Harriman (BBH).

---

## Contents

- [1. Overview and Architecture](#1-overview-and-architecture)
- [2. Managing Products in the Portal](#2-managing-products-in-the-portal)
- [3. Adding and Configuring Services](#3-adding-and-configuring-services)
- [4. Multi-Module and Multi-Repository Bitbucket Architectures](#4-multi-module-and-multi-repository-bitbucket-architectures)
- [5. Supported Languages, Build Tools, and Frameworks](#5-supported-languages-build-tools-and-frameworks)
- [6. Unit Testing and Multi-Report Code Coverage](#6-unit-testing-and-multi-report-code-coverage)
- [7. Static Application Security Testing (SAST) – HCL AppScan](#7-static-application-security-testing-sast--hcl-appscan)
- [8. Dynamic Application Security Testing (DAST) – HCL AppScan](#8-dynamic-application-security-testing-dast--hcl-appscan)
- [9. Code Quality – SonarQube & Sonar Badges](#9-code-quality--sonarqube--sonar-badges)
- [10. Software Composition Analysis & GoldenFix – Nexus IQ](#10-software-composition-analysis--goldenfix--nexus-iq)
- [11. Deployments & Delivery – OpenShift and Virtual Machines (VM)](#11-deployments--delivery--openshift-and-virtual-machines-vm)
- [12. Telemetry, DORA Metrics, InfluxDB, and Grafana](#12-telemetry-dora-metrics-influxdb-and-grafana)
- [13. Step-by-Step Form Reference: What to Enter Where](#13-step-by-step-form-reference-what-to-enter-where)
- [14. OWASP SAMM Software Assurance Maturity Model](#14-owasp-samm-software-assurance-maturity-model)
- [15. Repo Auto-Discovery and Smart Wizard (One-Click Inspect)](#15-repo-auto-discovery-and-smart-wizard-one-click-inspect)
- [16. Auto-Resolved Parameters Reference](#16-auto-resolved-parameters-reference)
- [17. Bitbucket Pull Request Decoration & Shift-Left Feedback](#17-bitbucket-pull-request-decoration--shift-left-feedback)
- [18. Deployment Modes and Configuration Guide](#18-deployment-modes-and-configuration-guide)

---

## 1. Overview and Architecture

ITSS COE OneView (short: OneView) is the portal of the ITSS Center of Excellence at BBH. It joins three areas, linked from the menu on the left and from the cards of its Main Landing Page: **DevSecOps**, the central management interface for BBH delivery pipelines that this guide describes; **QM Metric Tool**, the quality assurance metrics of the products; and **Agile Assessment & Adoption**, the agile maturity of teams and business services. A bar above the pages chooses a business service and, if wanted, one of its applications (a product of the catalog): with no application chosen, the pages sum up every application of the business service, and with one chosen they show it alone. The QM Metric Tool keeps, for every application, its QA lead, where its results are and the quality targets it works to; Agile Assessment & Adoption records the assessments of the agile coaches, a level from 1 to 5 in five dimensions. Chapter 2 of the OneView guide `docs/documentation.md` describes the bar, and its chapters 20 and 21 the two areas. Until 2026-11-22 OneView had the working name DevSecOps Management Portal; this guide calls it OneView or the portal.

**Jenkins setup, once:** a Jenkins administrator sets the global environment variable `ONEVIEW_URL` to the address of OneView, for example `https://oneview.apps.bbh.com` (Manage Jenkins, System, Global properties, Environment variables). The address is written as `https://<host>[:<port>][/<path>]`, without a user, password, query or spaces. `DSO_PORTAL_URL`, the former name of the variable, still works: the library reads it while `ONEVIEW_URL` is not set or cannot be used and says in the console of the build how to rename it. No `Jenkinsfile` changes. A job whose `Jenkinsfile` pins a release of the library from before the new name (`@Library('DSOEnhanced@<older tag>')`) reads only `DSO_PORTAL_URL`: keep both variables set, with the same value, until no job uses such a release. Meanwhile each build of the current library says that `DSO_PORTAL_URL` holds the same value and can be removed; that line is expected. The same holds for `ONEVIEW_AGENT` and its former name `DSO_PORTAL_AGENT`, the label of the agents that read OneView.

### Key Principles:
- **No secrets in source code:** No API keys, passwords, or tokens are ever committed to Git repositories. Credentials reference secure Jenkins credentials store IDs.
- **Single Pipeline Key (`pipelineKey`):** Each pipeline receives a unique secret key. The application's `Jenkinsfile` contains only this key:
  ```groovy
  @Library('DSOEnhanced@main') _
  devSecOpsPipeline(pipelineKey: '5e0f0001-8c2b-4f6a-9d3e-1a7b2c4d0001')
  ```
- **Automated Configuration Retrieval:** When a Jenkins build starts, the shared library fetches its configuration directly from OneView via REST API (`GET $ONEVIEW_URL/api/dso/config/<key>`), executes the required stages, and streams telemetry to InfluxDB.
- **Automated Report Upload:** After its stages the run posts its pipeline report to OneView (`POST $ONEVIEW_URL/api/dso/reports`) with the key of its `Jenkinsfile`, once for each key of `pipelineKeys`, so the page of every pipeline of the run shows the report. The job needs no setting for it, and the agent that ran the pipeline must reach OneView. The console shows `[ONEVIEW] Uploading pipeline report to <address>/api/dso/reports for job <job> build <number> with pipeline key <hint>`; the hint is the first 8 and the last 4 characters of the key, and the whole key is never printed. An upload that fails is one `[ONEVIEW] Failed to upload pipeline report to OneView for pipeline key <hint>: <reason>` line, after at most 60 seconds, and never changes the result of the build. A `DSO_PIPELINE_KEY` or `PIPELINE_KEY` in the environment of a job wins over the keys of the `Jenkinsfile`: the report is then posted once, with that key, and a job that sets it to its own key is told in the console that it can be removed.
- **Enterprise-Grade UI:** Designed specifically for BBH investment banking requirements, providing clear visual hierarchy, accessible validation, and immediate feedback.

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

| Stage / Tool | Required Configuration (portal service form; `devsecops_config.yaml` / `config.yaml` without a pipeline key) | Agent / Platform Prerequisites | Stage Outputs & Enforcement |
| :--- | :--- | :--- | :--- |
| **Build & Unit Tests** (Stages 0 & 2) | Optional *Build tool* / `buildTool` (auto-detected if omitted/invalid), `build.<tool>`, `tests.unitTests.<tool>`, `coverage.minLine` | Matching SDK/compiler on agent (JDK, Gradle/Maven, .NET SDK or VS BuildTools `MSBuild.exe` / `NuGet.exe` / `vstest.console.exe` / `OpenCover.Console.exe`, Node.js, Python, Flutter, Liquibase) | Compiles artifact, runs unit tests, aggregates JaCoCo/LCOV/Cobertura/OpenCover reports, and enforces line coverage gate |
| **Nexus IQ & GoldenFix** (Stage 3) | `tools.nexusIq.application`, `credentialsId`, `scanPatterns`; optional `goldenFix.enabled: true` + `scm.bitbucket` | Jenkins Nexus Platform Plugin, `git`, `curl` / `curl.exe` | Evaluates open-source dependencies, verifies upgrades in an isolated Git worktree, updates manifests/version catalogs, and raises Bitbucket PR |
| **HCL AppScan SAST** (Stage 4) | `appId` (the API key is the functional user's one in the library defaults); optional `asoc.doCompile`, `asoc.<tool>` | HCL AppScan SAClientUtil (`SA_LINUX_URL` / `SA_WIN_URL` or `appscanPath`) | Compiles sources, generates `.irx`, runs cloud SAST analysis, enforces severity limits, and posts inline PR comments on vulnerable lines |
| **SonarQube SCA** (Stage 5) | `tools.sonar.projectKey` (auto-generated as `com.bbh:<service>` if omitted), `installationName`, `authToken` / `credentialsId` | Jenkins SonarQube plugin (`withSonarQubeEnv`), `SonarScanner.MSBuild.exe` / `dotnet-sonarscanner` / `sonar-scanner` | Executes static code quality & security analysis, fetches issue counts via REST API, enforces quality gate, and posts inline PR comments |
| **Delivery & Deployment** (Stages 6, 7, 12, 13) | `deployTarget` (`openshift` or `vm`, auto-detected if omitted), `deploy.<target>.<rd\|qc>`, optional `delivery.<maven\|gradle\|nuget>` | OpenShift (`oc`, `skopeo`) or VM (SSH / UrbanCode Deploy DOD plugin), Nexus credentials | Publishes snapshot/release artifacts or container images and deploys to RD and QC (gated by `release-gate.json`) |
| **Regression / Smoke / Performance** (Stages 8, 9, 10) | `tests.regression.jobs` (`urls`), `tests.smoke.jobs` (`urls`), `tests.performance.jobs` (`urls`) | Local Jenkins jobs or Parameterized Remote Trigger plugin | Triggers test jobs in parallel batches and records results in HTML report and InfluxDB |
| **HCL AppScan DAST** (Stage 11) | `dast.enabled: true`, `dast.targetUrl`, `dast.scanName`, `appId` | Running service in RD reachable at `dast.targetUrl` (optional `dast.presenceId`) | Executes dynamic runtime security scan, downloads HTML/PDF reports, enforces policy, and raises DAST findings PR |

#### C. Breakdown per Pipeline Type

| Pipeline Entry Point | Stages Executed | Required Config Sections | Produced Artifacts & PRs |
| :--- | :--- | :--- | :--- |
| `devSecOpsPipeline` *(Full Pipeline)* | All stages: 1 (Monitor), 0 (Node.js optional), 2 (Build + Unit Tests + Coverage), 3 (Nexus IQ + GoldenFix), 4 (AppScan SAST), 5 (SonarQube), 6 (Nexus Snapshot), 7 (RD Deploy), 8 (Regression), 9 (Smoke), 10 (Performance), 11 (AppScan DAST), 12 (Nexus Release), 13 (QC Deploy) | `appId`, `tools.nexusIq`, `tools.sonar`, `deploy.<target>`, `tests.<regression\|smoke\|performance>`, `dast` | `report/pipeline-report.html`, `appscan-report*.html`, `appscan-dast-report*.pdf`, `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR, Static Security PR (inline SAST + SonarQube comments), DAST PR |
| `devSecOpsSecurityPipeline` *(Security Pipeline)* | Stages 1, 0 (optional), 2, 3, 4, 5, 6; optionally triggers `extendedPipeline` | `appId`, `tools.nexusIq`, `tools.sonar`, `deploy.<target>.rd` | `report/pipeline-report.html`, `appscan-report*.html`, `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR, Static Security PR |
| `devSecOpsExtendedPipeline` *(Extended Pipeline)* | Stages 1 (copies state from `securityPipeline`), 7, 8, 9, 10, 11, 12, 13 | `appId`, `deploy.<target>`, `tests.<regression\|smoke\|performance>`, `dast` | `report/pipeline-report.html`, `appscan-dast-report*.html/.pdf`, `pipeline-config.yaml`, `release-gate.json`, DAST PR |
| `devSecOpsSASTScanningPipeline` *(SAST-Only Pipeline)* | Stages 1, 0 (optional), 4 (AppScan SAST) | `appId` | `report/pipeline-report.html` (SAST view), `appscan-report*.html`, `pipeline-config.yaml`, `release-gate.json` |
| `devSecOpsNexusIqGoldenFixPipeline` *(Nexus IQ + GoldenFix)* | Stages 1, 0 (optional), 2 (Build), 3 (Nexus IQ + GoldenFix) | `tools.nexusIq.application` + `scanPatterns`, `goldenFix`, `scm.bitbucket` | `report/pipeline-report.html` (Nexus IQ + GoldenFix view), `pipeline-config.yaml`, `release-gate.json`, GoldenFix PR |

---

## 2. Managing Products in the Portal

A Product represents an overarching business application (e.g., *Payments Hub*, *Custody Reporting*).

### Product Form Fields:
- **Business Service:** Select the department from the dropdown (e.g., *Custody*, *Fund Services*).
- **Product Code:** Unique identifier (2–50 uppercase characters, digits, `-` or `_`, starting with a letter, e.g., `PAYHUB`).
- **Product Name:** Descriptive display name for the application.
- **Owner Team:** Responsible delivery team name.
- **Contact E-mail:** Group mailbox for communications and notifications.
- **Jenkins Agents:** Comma-separated labels of Jenkins nodes assigned to run the pipelines (the first agent is the default).

> [!NOTE]
> **Credentials Architecture:** Neither a Product nor a Service holds an AppScan API key. SAST and DAST of every service sign in with the one API key of the functional HCL ASOC user, which the DevSecOps team keeps in the library defaults (`asoc.keyId` and the Jenkins Secret text credential named in `asoc.token`). A Service holds only its AppScan application ID.

---

## 3. Adding and Configuring Services

A Service represents an independently deployable component (e.g., backend API, web frontend, microservice, or database migration package).

### Efficiency Features in the Portal Form:
- **Import Config from YAML:**
  - Import an existing `devsecops_config.yaml` or `config.yaml` file.
  - The Portal parses the document and automatically populates all form sections.
- **Copy Values from Existing Service:**
  - Replicate settings from an already configured service within the same product with one click.
  - Simply update the specific attributes (such as service name and repository URL).
- **Smart "Add Service" Button:**
  - The button is disabled whenever any required field is invalid or missing.
  - Displays a real-time counter of missing fields with one-click direct navigation to each unconfigured section.
- **Library Defaults Presentation:**
  - Fields with global defaults defined in *Library Defaults* (Global Settings) are automatically pre-filled.
  - Helper hints display the source value (e.g., *Library default: mvn clean package*).
- **Contextual Tooltips:**
  - Every field includes an intuitive tooltip explaining its purpose, requirements, and expected input format.
- **Auto-Discover from Bitbucket:**
  - *Inspect repository* reads the repository and fills the build tool (Gradle, Maven, Flutter, Liquibase or C# / .NET from `*.sln`, `*.csproj`, `global.json`, `Directory.Packages.props` or `packages.config`), the deployment target, the Node.js build and the SonarQube defaults.
  - Below the result the portal lists every auto-resolved setting with its value and why (for example `buildTool` = `DOTNET`, *App.sln found (C# / .NET)*), so you can check what was filled in before you save.
  - The pipeline report shows the same list for each run in the *Auto-resolved settings* card, and the report upload of a run with a pipeline key stores it with the run in the portal.

---

## 4. Multi-Module and Multi-Repository Bitbucket Architectures

The platform natively supports complex enterprise project structures:

### 1. Multi-Service within One Product
- Add as many services to a single product as necessary.
- Each service can be hosted in **a completely separate Bitbucket repository**.
- Each service specifies its own Bitbucket repository URL (`scm.bitbucket.repositoryUrl`), branch, credentials, and PR reviewers.

### 2. Multi-Module Single Service
- For projects with multiple sub-modules (e.g., Maven submodules, Gradle subprojects, or Liquibase changelogs):
  - The build stage compiles all modules automatically.
  - Shared version catalogs (e.g., `gradle/libs.versions.toml`, `platform/build.gradle`) are monitored and remediated together by GoldenFix in a single Pull Request.

---

## 5. Supported Languages, Build Tools, and Frameworks

The pipeline supports all standard BBH languages and build tools. In the Portal the **Build tool** of a service is Gradle, Maven, Flutter, C# / .NET, Liquibase (SQL / PL/SQL), SQL / PL/SQL files (no changelog), npm (Angular, Node.js), pip (Python), Android (Gradle) or iOS (Xcode):

| Language / Stack | Build Tool | Standard Build Command | Use Case |
|---|---|---|---|
| **Java** | Maven (`mvn`) | `clean package -DskipTests` | Enterprise backend APIs |
| **Kotlin** | Gradle (`gradle`) | `clean assemble -x test` | Modern microservices |
| **Groovy** | Gradle / Maven | `clean build -x test` | Automation utilities & libraries |
| **Node.js / TypeScript** | npm | `npm run build` | Web backends & BFF services |
| **Angular** | npm | `npm ci && npm run build --prod` | Client-facing web apps |
| **Python** | pip / python3 | `pip install -r requirements.txt` | Data analytics & automation |
| **SQL / PL-SQL** | Liquibase | `liquibase updateSQL` (default of the auto-resolve; `validate` with `autoresolve parameters: false`) | Oracle & PostgreSQL migrations |
| **Android** | Android (Gradle wrapper) | `clean assemble` | Android applications |
| **iOS / Swift** | iOS (Xcode) | one command line, for example `xcodebuild -scheme MyApp build` | iOS applications |
| **Flutter / Dart** | flutter / pub | `flutter build` | Mobile & desktop applications |
| **C# / F# / VB.NET** | *C# / .NET*: `dotnet` CLI or classic `MSBuild.exe` + `NuGet.exe` | detected: `dotnet build`, or `NuGet.exe restore` + `MSBuild.exe /t:Build` on Windows | .NET Framework 4.x, .NET Core and .NET 6–9+ services on Windows or Linux agents |

### Plugins from the BBH Nexus:
- When a Gradle or Maven step (build, unit tests, SonarQube, SAST compile) fails because a plugin is not in the repositories of the build, the step runs once more with the plugin repository of the BBH Nexus: *Gradle plugin repository* and *Maven plugin repository* of the Global Settings (`pluginRepositories`). The console says which plugins came from it and where to add the repository; unticking *Retry a Gradle or Maven step with these repositories when a plugin is missing* switches the retry off.
- A value of the wrong kind that reaches the library (text for a number, a word for `true`/`false`, a map for a list) falls back to the value of the Global Settings with an `[INIT] [WARN]` line.

### SQL and PL/SQL with a database block:
- A Liquibase service with `database.changelogFile` gets the database block: the Build artifact stage validates and packages the changes offline, Unit tests deploys them to a test database and runs utPLSQL with coverage, and the zip is delivered to Nexus and deployed to RD and QC with Jenkins credentials and a compile check. See [chapter 14 of the library documentation](../documentation.html#ch14).
- The database block is for Oracle. In a repository with an application, the folder of the changelogs is a Liquibase service of its own, listed before the application service; chapter 14 shows four complete repository layouts.
- *Build tool* **SQL** (`buildTool: sql`, also `plsql`) is for a repository of SQL and PL/SQL files without a changelog. The library writes the changelog `dso-changelog.xml` itself at the start of the run and then treats the service as a database block: files that start with `--liquibase formatted sql` are included as they are, `CREATE OR REPLACE` code runs again whenever its file changes, every other file runs once in the natural order of its path, and the files of the utPLSQL test folder go to the test database only. A repository with only such files and no build tool is detected as `sql`, with `database.searchPath` set to the one top folder of the files and the JDK from the auto setup. A service with build tool SQL that has a changelog after all runs as `buildTool: liquibase`, with a warning.

### npm, pip, Android and iOS in the Portal:
- **npm (Angular, Node.js):** the build of the service is its Node.js build (`build.node`): the folder with `package.json` and the commands, `npm ci` and `npm run build` when none are given. The unit tests run `npm test` unless the *Test command* names another line (`tests.unitTests.commands`), and the SonarScanner analyses the code with the *SonarScanner flags* (`tools.sonar.npm.flags`).
- **pip (Python):** the *Build command* is one command line (`build.pip.commands`), for example `pip install -r requirements.txt`; the unit tests run `pytest --junitxml=junit.xml` unless the *Test command* names another line, and the flags go to `tools.sonar.pip.flags`.
- **iOS (Xcode):** the *Build command* is one command line (`build.ios.commands`) and the flags go to `tools.sonar.ios.flags`.
- **Android (Gradle):** the Gradle tasks, flags and JDK of a Gradle service, written with `buildTool: android`.
- For npm, pip and iOS a command is one whole command line with its options; several commands are chained with `&&`. These tools need no JDK and no AppScan compile command, and the form shows only the fields the library reads for them.
- *Import config* reads the same names as the library: `python`, `poetry` and `pipenv` as pip; `node`, `nodejs`, `yarn`, `pnpm`, `angular`, `react` and `vue` as npm; `xcode`, `swift` and `cocoapods` as iOS.

### Stage 0 Frontend Pre-Build:
- For full-stack applications where Java/Kotlin packages serve Angular/Node.js assets, Stage 0 compiles the frontend before backend packaging.

---

## 6. Unit Testing and Multi-Report Code Coverage

The library automatically enforces test coverage across all project modules:

- **Automatic Report Detection:**
  - Scans all workspace subdirectories (`build/`, `target/`, `coverage/`, `cypress/`, etc.).
  - Parses **JaCoCo XML** reports for Java, Kotlin, and Groovy.
  - Parses **LCOV (`lcov.info`)** reports for JavaScript, TypeScript, Angular, Python, Flutter, and Cypress.
  - Parses **OpenCover and Cobertura XML** reports for C# / .NET (Coverlet `XPlat Code Coverage;Format=opencover`, `OpenCover.Console.exe`); SonarQube for C# reads the OpenCover reports, and Visual Studio `.trx` results give the test counts.
- **Node.js & Cypress Unit Testing:**
  - Dedicated support for Node.js unit and component tests via Cypress (`tests.unitTests.cypress`).
  - Seamless execution of Cypress tests (`npx cypress run --component` or custom command) within the Node.js environment.
  - Ingestion of JUnit XML test results and automatic aggregation of Cypress LCOV coverage data (`coverage/lcov.info`).
- **Granular Per-Module Reporting:**
  - Every discovered report receives its own line in the build log and HTML report with path, line count, and percentage.
- **Project Run Summary:**
  - Computes a weighted overall average line for the entire project (`All services`).
- **Release Quality Gate:**
  - If project coverage falls below the threshold (default: 80%), the pipeline halts and prevents release.

---

## 7. Static Application Security Testing (SAST) – HCL AppScan

Static security analysis inspects application source code for security vulnerabilities.

### Key Capabilities:
- **Stages at the same time (default):**
  - Unit tests, Nexus IQ, SAST and SonarQube run **in parallel** in the stage *Static analysis*; SAST compiles and queues its scans in a copy of the workspace, and SonarQube starts once the unit tests have written the coverage.
  - The full and extended pipelines also run the regression, smoke and performance tests and DAST in parallel (*Dynamic analysis*).
  - The report ends with the table **Manual Actions vs DevSecOps**: every step of the run with its own time, the time by hand, the time with DevSecOps (of the steps that run in parallel only the longest), the time gained in percent and a summary in seconds, minutes and percent; with more than one service, every step lists all configured services with their times.
  - Both are on by default. Unticking **Run unit tests, Nexus IQ, SAST and SonarQube at the same time** or **Run the regression, smoke and performance tests and DAST at the same time** in the Global Settings (`staticAnalysis.parallel`, `dynamicAnalysis.parallel`) runs the stages one after another again; without a key, `false` in `devsecops_config.yaml` does the same.
- **Streamlined Configuration:**
  - The **main SAST section** holds only the AppScan application ID (`appId`) of the service.
  - `sast.scanName` is available in **Advanced Settings** for custom scan naming.
  - The API key of the functional user (`asoc.keyId`, `asoc.token`) is a setting of the library defaults, shared by every service; the portal never asks for it.

---

## 8. Dynamic Application Security Testing (DAST) – HCL AppScan

Dynamic analysis scans running web applications in staging environments for runtime vulnerabilities:

- **Target URL (`dast.targetUrl`):** Deployed endpoint URL (e.g., `https://dev-payhub.bbh.com`).
- **Presence ID (`dast.presenceId`):** Internal AppScan proxy ID enabling scanning behind the BBH corporate firewall.
- **Credentials:** Jenkins credentials ID for authenticated crawler scanning.

---

## 9. Code Quality – SonarQube & Sonar Badges

SonarQube analyzes technical debt, cyclomatic complexity, and code smells.

### Key Features:
- **Main Section Access:**
  - `Installation Name`, `Auth Token`, and `Badge Token` are in the primary SonarQube configuration section.
- **Alternative Badge Token Configuration:**
  - Configure via raw token string: **Badge Token**
  - OR configure via Jenkins secret: **Badge Credentials ID**
  > [!TIP]
  > Provide **either** direct badge token OR Jenkins credentials ID. Do not configure both.
- **Full Language Coverage:** Includes native rules for Java, Kotlin, TypeScript, SQL/PL-SQL (Liquibase), and Flutter/Dart.

### How the Analysis Runs:
- **Gradle, Android and Maven with analysis commands of their own** (`tools.sonar.gradle.tasks`, for Android as well, or `tools.sonar.maven.goals`) run them with the plugin of the build.
- **Every other Gradle, Android and Maven service, and every npm, pip and iOS service** is analysed with the `sonar-scanner` CLI: the Jenkins tool `sonar-scanner-cli-<version>` named in `tools.sonar.sonarScannerVersion`, otherwise `sonar-scanner` on the `PATH` of the agent. It gets `tools.sonar.<tool>.flags` (of a Gradle, Android or Maven block only the arguments that start with `-Dsonar.`), the sources of the service and the coverage report of pip (`coverage.xml`) and npm (`coverage/lcov.info`). An argument of the service wins over the one the library would add.
- **Branch and retry:** the branch of the build is passed as `-Dsonar.branch.name`. A server that refuses the branch name, as the Community Edition does, fails that attempt; the library logs a `[SONAR]` line and runs the analysis once more without the branch, and the stage fails only when the second attempt fails as well. `tools.sonar.branchAnalysis: false` skips the first attempt.

---

## 10. Software Composition Analysis & GoldenFix – Nexus IQ

Nexus IQ identifies known open-source vulnerabilities (CVEs) and license compliance issues.

### Single-Application Constraint:
- The UI enforces **exactly one Nexus IQ application** (`applicationId`) per service, eliminating duplicate configuration errors.

### GoldenFix & Automated Pull Requests:
- When vulnerable dependencies are detected, GoldenFix finds compatible non-vulnerable versions.
- **Automated Remediation:** Opens a Bitbucket Pull Request upgrading dependencies in `pom.xml`, `build.gradle`, `package.json`, `requirements.txt`, or version catalogs (`libs.versions.toml`).
- Groups updates by repository, opening a single consolidated Pull Request for multi-module projects.

### External Version Catalog Repository:
- When dependency versions and catalogs are managed in a separate, dedicated Git/Bitbucket repository:
  - **Catalog Repository URL (`goldenFix.versionCatalogRepo.url`):** The clone address of the repository containing the version catalog.
  - **Credentials ID (`goldenFix.versionCatalogRepo.credentialsId`):** Jenkins credentials for the catalog repository (falls back to SCM credentials if empty).
  - **Branch (`goldenFix.versionCatalogRepo.branch`):** Target branch to update (falls back to default branch).
  - **Path (`goldenFix.versionCatalogRepo.path`):** Relative file or directory path within the external repository (e.g., `catalogs/libs.versions.toml`).
- GoldenFix checks out the external catalog repository, performs version upgrades, validates the build, and submits the remediation Pull Request directly to the catalog repository.

### Decoupled Pipeline Execution Order:
1. **Stage 1: Build Artifact (without tests):** The pipeline builds deployable artifacts without executing unit tests. If the optional Node.js build is enabled (`build.node.enabled: true`), npm commands run as step 0 at the start of this stage before compiling backend code.
2. **Stage 2: Static analysis at the same time (Unit Tests + Nexus IQ + SAST + SonarQube):** by default the four run as parallel branches of the Jenkins stage *Static analysis* (unticking *Run unit tests, Nexus IQ, SAST and SonarQube at the same time* in the Global Settings runs them one after another).
   - **Unit Tests:** Runs automated unit tests (including Node.js & Cypress tests) and aggregates JaCoCo & LCOV code coverage.
   - **Nexus IQ:** Evaluates open-source dependencies against security policies and prepares GoldenFix remediation.
   - **SAST (HCL AppScan):** Compiles and queues the scan of every service in a copy of the workspace (`<WORKSPACE_TMP>/dso-sast-workspace`), so its compile never touches the files the other branches read, then waits for the results and the reports.
   - **SonarQube:** Starts a service once the unit tests of every service in the same source folder have written their coverage, ingests the test execution results and the JaCoCo/LCOV/Cypress coverage reports and enforces the SonarQube Quality Gate.
3. **Stage 3: Results:** A failing branch turns its stage red while the other branches finish. The console and the report show how long the four stages would take one after another and how long they took at the same time.
   - The full and extended pipelines run the regression, smoke and performance tests and DAST at the same time in the same way (*Dynamic analysis*, *Run the regression, smoke and performance tests and DAST at the same time* in the Global Settings).

---

## 11. Deployments & Delivery – OpenShift and Virtual Machines (VM)

The delivery module packages artifacts and deploys services to target infrastructure.

### "Disable Nexus Delivery" Checkbox:
- Allows skipping artifact publishing to Nexus, on VM and OpenShift targets alike: the portal renders `delivery.nexusDeliveryEnabled: false`.
- The library reads `delivery.nexusDeliveryEnabled` before the older `deploy.vm.rd.nexusDeliveryEnabled` and `deploy.openshift.rd.nexusDeliveryEnabled`, so configs written before keep working, and *Import config* reads either key.
- When checked, the delivery fields are hidden and their validation is bypassed, and the Extended pipeline downloads nothing. The NuGet packages of a .NET service stay shown, because the Full pipeline still pushes the package in stage 12.

### Nexus Delivery of a Service on Virtual Machines (Maven, Gradle, npm and pip):
- **Delivery (upload, stage 6):** the files of the snapshot, one glob per line, optionally followed by a classifier (`target/docs.zip docs`). Every file goes up under the one snapshot version of the run, `<BUILD_NUMBER>-<yyyyMMdd-HHmmss>-SNAPSHOT`, and the tag is written to `build.tag`.
  - Maven: the goals and flags of the upload (for example `deploy:deploy-file` with `-DrepositoryId`, `-Durl`, `-DgroupId`, `-DartifactId`), rendered as `delivery.push.maven` once artifacts are listed. Without artifacts the one file of the artifact path is uploaded with `delivery.maven`, as before.
  - Gradle: group, artifact, repository URL and, for an `http://` repository, *Allow an http repository*, rendered as `delivery.push.gradle`. Only flags and environment variables can be added, because the library runs its own `maven-publish` task with the Gradle wrapper of the service. Without artifacts the service is not required in stage 6.
  - npm and pip: the package files (the `.tgz` of `npm pack`, a Python wheel) and the Maven goals that upload them, such as `deploy:deploy-file`, rendered as `delivery.push.maven`. Without package files the package is not published.
- **Release of the safe artifact (stage 12):** after the release gate the stage publishes the files of stage 6 once more as the release of the build tag of the run, without building again; it checks their SHA-256 first and never replaces a release. *Release in stage 12* (Library default, On or Off), the release repository URL and the login of the upload (the Maven server ID of a Maven, npm or pip service, the credentials ID of a Gradle service) are rendered as `delivery.release`. A value left empty comes from the Library defaults (Admin, Library defaults, Service defaults, *Release of the safe artifact*); without a release repository there either, the stage is not required and the snapshot stays the delivered artifact. The release repository is never the snapshot repository of the upload.
- **Download in the Extended pipeline (Maven and Gradle):** the file names of the snapshot to download, the download folder (`downloaded-artifacts` when left empty) and, for Maven, the group, artifact and goals of the download (for example `org.apache.maven.plugins:maven-dependency-plugin:3.6.1:get`). It is rendered as `delivery.pull` and runs in the stage *Download artifacts from Nexus Repo* with the version in `build.tag`. Without file names nothing is downloaded.

### NuGet Packages of a .NET Service:
- The NuGet feed and its API key credentials ID, the release feed and its key (left empty: the key of the feed) and the packages to push (left empty: the artifact path, else `**/*.nupkg`), rendered as `delivery.nuget`, on virtual machines and on OpenShift.
- On virtual machines stage 6 pushes the package to the NuGet feed, and stage 12 of the Full pipeline pushes it to the release feed after the release gate, with `--skip-duplicate`. With the Nexus delivery off, and on OpenShift, stage 12 of the Full pipeline pushes the package to the release feed, else to the NuGet feed, as before.
- The Extended pipeline builds no NuGet package, pushes none and does not use the release feed: on virtual machines with the Nexus delivery on, the package that the Security pipeline pushed to the NuGet feed stays the delivered package; with the Nexus delivery off, and on OpenShift, only stage 12 of the Full pipeline pushes the package.
- A feed is what `dotnet nuget push --source` takes: a feed address, a folder or the name of a source in `NuGet.Config`, without quotes.

### OpenShift QC Region Validation:
- The QC region configuration is **entirely optional**.
- Forms remain valid and savable if the QC region is left unconfigured.
- Validation for QC fields triggers only when user input is entered into that section.

### Deployment Targets:
1. **OpenShift (Containers):** Kubernetes namespace, deployment, and service management.
2. **Virtual Machines (VM):** Integration with IBM UrbanCode Deploy (IBM DevOps Delivery).

---

## 12. Telemetry, DORA Metrics, InfluxDB, and Grafana

Every pipeline execution streams performance and quality data to InfluxDB:

- **Four DORA Metrics:**
  1. **Deployment Frequency:** How often code reaches production.
  2. **Lead Time for Changes:** Commit to deployment latency.
  3. **Change Failure Rate:** Ratio of builds or deployments requiring remediation.
  4. **Time to Restore Service (MTTR):** Time required to recover from pipeline failures.
- **Stage Metrics:** Build durations, test executions, JaCoCo/LCOV coverage points, vulnerability counts.
- **Dashboards:**
  - Inside Portal: **Pipeline Monitoring** view.
  - Enterprise view: Dedicated Grafana departmental dashboards.

---

## 13. Step-by-Step Form Reference: What to Enter Where

| Portal Field | Description | Example Input | Source / Where to get it | Required? |
|---|---|---|---|:---:|
| **Service Name** | Unique component name | `backend-api` or `web-gui` | Team naming convention | <span class="badge badge-required">YES</span> |
| **Bitbucket Repo URL** | Git repository SSH URL | `ssh://git@bitbucket.bbh.com/pay/api.git` | Bitbucket (Clone -> SSH) | <span class="badge badge-required">YES</span> |
| **Git Branch** | Target build branch | `main` or `master` | Repository default branch | <span class="badge badge-required">YES</span> |
| **Build Tool** | Technology build system | Gradle, Maven, Flutter, C# / .NET, Liquibase (SQL / PL/SQL), SQL / PL/SQL files (no changelog), npm, pip, Android or iOS | Application runtime | <span class="badge badge-required">YES</span> |
| **Build Tasks / Command** | Build invocation arguments | `clean package -DskipTests` | Build tool command | <span class="badge badge-required">YES</span> |
| **Unit Test Tasks** | Test execution command | `test` or `test jacocoTestReport` | Build tool test task | <span class="badge badge-optional">OPTIONAL</span> |
| **Cypress Tests Enabled** | Run Cypress unit/component tests | Checked `[X]` / Unchecked `[ ]` | Test suite config | <span class="badge badge-optional">OPTIONAL</span> |
| **Cypress Command** | Cypress invocation command | `npx cypress run --component` | Project scripts | <span class="badge badge-optional">OPTIONAL</span> |
| **Cypress Reports Dir** | JUnit test reports folder | `cypress/results` | Cypress reporter | <span class="badge badge-optional">OPTIONAL</span> |
| **Coverage Minimum** | Enforced coverage percentage | `80` | BBH standard default (80) | <span class="badge badge-default">DEFAULT</span> |
| **AppScan Application ID** | ASOC application of the service (SAST and DAST) | `209f44ac-dd06-4ca0-884e-d944904f8020` | HCL ASOC application settings | <span class="badge badge-required">YES</span> |
| **SAST Scan Name** | Custom ASOC scan display name | `PAYHUB-API-Nightly` | Team preference (optional) | <span class="badge badge-optional">OPTIONAL</span> |
| **Sonar Installation** | SonarQube server in Jenkins | `BBH-SonarQube` | Standard Jenkins tool name | <span class="badge badge-default">DEFAULT</span> |
| **Sonar Auth Token** | SonarQube token credential ID | `sonar-auth-token-id` | Jenkins Credentials store | <span class="badge badge-required">YES</span> |
| **Sonar Badge Token** | Project badge token (direct) | `sonar-badge-raw-token` | SonarQube Project Settings | <span class="badge badge-optional">1 OF 2</span> |
| **Sonar Badge Creds ID** | Project badge secret in Jenkins | `sonar-badge-credentials-id` | Jenkins Credentials store | <span class="badge badge-optional">1 OF 2</span> |
| **Nexus IQ Application ID** | Unique app ID in Nexus IQ | `payhub-backend-api` | Nexus IQ Server Dashboard | <span class="badge badge-required">YES</span> |
| **External Catalog Repo URL** | Dedicated catalog repository | `ssh://git@bitbucket.bbh.com/shared/catalogs.git` | Bitbucket | <span class="badge badge-optional">OPTIONAL</span> |
| **External Catalog Branch** | Branch of external catalog repo | `main` | Bitbucket | <span class="badge badge-optional">OPTIONAL</span> |
| **External Catalog Path** | Relative path to catalog file | `catalogs/libs.versions.toml` | Bitbucket | <span class="badge badge-optional">OPTIONAL</span> |
| **Disable Nexus Delivery** | Toggle off Nexus artifact push | Checked `[X]` / Unchecked `[ ]` | Check if no artifact needed | <span class="badge badge-optional">OPTIONAL</span> |
| **OpenShift Namespace** | Target cluster project/namespace | `payhub-dev` | OpenShift web console | <span class="badge badge-optional">OpenShift</span> |
| **VM Application Name** | UrbanCode Deploy application | `PAYHUB_CORE` | IBM UrbanCode Deploy console | <span class="badge badge-optional">VM</span> |

---

### Step-by-Step Walkthrough:
1. **Navigate to Admin -> Products:** Click *Add Product*, supply your Product Code (e.g., `PAYHUB`), and set Jenkins Agent labels.
2. **Add Service:**
   - Click **Import config** (upload `devsecops_config.yaml` or `config.yaml`), OR
   - Click **Copy values** (replicate an existing service), OR
   - Fill in the form fields using contextual tooltips for guidance.
3. **Configure Security:**
   - Supply the AppScan application ID in the main SAST section; the API key comes from the library defaults.
   - Enter SonarQube credentials and choose either Badge Token or Badge Credentials ID.
   - Enter your single Nexus IQ Application ID.
4. **Configure Target Deployment:**
   - Set OpenShift namespace or VM UrbanCode application name.
   - Check *Disable Nexus Delivery* if you do not publish jars/wars to Nexus.
5. **Save:** The *Add service* button activates once all required fields are valid.
6. **Deploy Jenkinsfile:** Copy the generated single-line `Jenkinsfile` with its `pipelineKey` into your Git repository. The pipeline is ready to run!

---

## 14. OWASP SAMM Software Assurance Maturity Model

ITSS COE OneView includes a dedicated **OWASP SAMM** section assessing software assurance maturity across all applications and business services.

### Core Capabilities:
- **Continuous Maturity Assessment:** SAMM v2.0 posture across 5 business functions (Governance, Design, Implementation, Verification, Operations) and 11 automated practices.
- **Automated Evidence-Based Scoring:** Derived from active pipeline configuration (SAST, DAST, SCA Nexus IQ, SonarQube, unit tests, Cypress, coverage >= 60%, GoldenFix, InfluxDB telemetry).
- **Executive & Departmental Dashboards:** Organization-level overview, maturity distribution across Levels 0–3, and departmental benchmarking.
- **Application Scorecards:** Practice-level scores (0.0 to 3.0), criteria checklists, and concrete remediation roadmaps.
- **Pipeline Monitoring Integration:** One-click drill-down directly from any pipeline card in Pipeline Monitoring into its SAMM scorecard.

---

## 15. Repo Auto-Discovery and Smart Wizard (One-Click Inspect)

Onboarding services into a corporate DevSecOps pipeline historically created friction: engineers had to determine 15 to 20 granular configuration parameters, including build tools, compiler flags, test report paths, JaCoCo directories, Node.js versions, and OpenShift manifests.

The **Repo Auto-Discovery / Smart Wizard** removes over 80% of onboarding friction:

### Non-Technical Business Benefits:
- **Instant Onboarding:** Teams type their Bitbucket repository URL and branch and click **Scan & Auto-fill**, which pre-fills the build tool, the build and unit test commands, the Node.js build switch, the deployment target and an empty SonarQube project key. The other required fields, such as the service name and the AppScan settings, are still filled in by hand.
- **Error Reduction:** Manifests are inspected directly from source control, preventing misconfigurations and build breaks.
- **Immediate Time-to-Value:** Pipelines are configured, validated, and ready to execute within minutes.

### How it Works:
1. When creating or updating a service in the Self-service wizard or Admin editor, specify the **Bitbucket Repo URL** and target **Branch**.
2. Click **Scan & Auto-fill**.
3. The Portal executes an inspection (`/api/catalog/inspect-repo`) that reads only the root folder of the branch through the Bitbucket REST API, and fills in the build tool, the build and unit test commands, the Node.js build switch, the deployment target and an empty SonarQube project key; every other field keeps its value. A marker in a subfolder, such as `frontend/package.json` or `gradle/wrapper/gradle-wrapper.properties`, is never found through Bitbucket. A Liquibase service with database changes (one whose Changelog file is set; see chapter 7, "SQL and PL/SQL changes with Liquibase", of the OneView guide `docs/documentation.md`) keeps its build tool, its commands and its deployment settings; the scan fills in only an empty SonarQube project key.
4. Developers review the suggestions, make any project-specific adjustments, and click **Save**.

Below the result the form lists every setting the scan filled in, with its value and why (for example `buildTool` = `DOTNET`, *App.sln found (C# / .NET)*). The pipeline report of every run shows the settings the library auto-resolved in its *Auto-resolved settings* card, and the report upload stores the same list (service, setting, value, why) with the run, returned as `autoResolved` by `GET /api/pipelines/{id}/report`.

### Stack Detection Matrix:
The build tool is detected in this order, in the library and in **Scan & Auto-fill** of the Portal alike: `pubspec.yaml` (Flutter), the iOS files, `pom.xml` (Maven), the Gradle files (Android with an `AndroidManifest.xml`), the .NET files, a Liquibase changelog, the Python files, `package.json` or `angular.json` at the root (npm), and at last a repository of only SQL and PL/SQL files (`sql`).

- **Maven (`pom.xml`)**: Identifies the Maven build tool and fills in the goals `clean verify` and `test`, or `test jacoco:report` when the `pom.xml` mentions JaCoCo. It names the Java version of `<java.version>`, `<maven.compiler.source>`, `<maven.compiler.target>`, `<maven.compiler.release>` or `<jdk.version>` and the Kotlin plugins in the scan message, and sets no JDK and no coverage reports.
- **Gradle (`build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`)**: Identifies the Gradle build tool and fills in the tasks `clean build` and `test`, or `test jacocoTestReport` when the build file mentions JaCoCo. It names Kotlin DSL and a Java version in the scan message, and sets no JDK and no test reports. The Gradle wrapper version is in `gradle/wrapper/gradle-wrapper.properties`, a subfolder, so the scan never reads it through Bitbucket.
- **Android (Gradle files and `AndroidManifest.xml` at the root)**: Selects the Android build tool with the Gradle tasks of the Gradle row. An `AndroidManifest.xml` in `app/src/main` is in a subfolder, so the scan finds it only in a local checkout.
- **npm (`package.json` or `angular.json` at the root, and no other build)**: Selects the npm build tool. The build of the service is then its Node.js build, so the Stage 0 switch stays off, and the test command stays empty: the library runs `npm test`.
- **Node.js Stage 0 (`package.json`)**: Ticks the Stage 0 pre-build (`build.node.enabled: true`) of a service with another build tool when the root of the branch holds a `package.json`; a `package.json` in `frontend/`, `web/`, `client/` or `ui/` is never found through Bitbucket. It names Cypress or Jest in the scan message, and sets no Node.js version, no folder of the `package.json` and no test runner.
- **Liquibase (`changelog.xml`, `liquibase.properties`)**: Selects Liquibase when the repository has a changelog and no `pubspec.yaml`, iOS files, `pom.xml`, Gradle build or .NET files, with the build command `validate` and the unit test commands `updateSQL validate`. A service with database changes keeps its build tool, its commands and its deployment settings, also when the scan finds Gradle or Maven at the root of the repository.
- **Flutter / Dart (`pubspec.yaml`)**: Selects the Flutter build tool. It fills in no commands and no Flutter field, such as the platform.
- **C# / .NET (`*.sln`, `*.csproj`, `*.fsproj`, `*.vbproj`, `global.json`, `Directory.Packages.props`, `Directory.Build.props`, `packages.config`, `build.cake`)**: Selects the C# / .NET build tool after Maven and Gradle and before Liquibase. It fills in no commands: the library detects the mode (`dotnet` SDK CLI, classic MSBuild + NuGet, hybrid, Cake or NUKE) on the agent, and SonarQube uses `.` as sources.
- **Python / pip (`requirements.txt`, `pyproject.toml`, `setup.py`, `Pipfile`)**: Selects the pip build tool with the build command `pip install -r requirements.txt`, or `pip install .` for a `pyproject.toml`. The test command stays empty: the library runs `pytest --junitxml=junit.xml`.
- **iOS (`Podfile`, `Package.swift`, `*.xcodeproj`, `*.xcworkspace`)**: Selects the iOS build tool right after Flutter. It fills in no commands.
- **OpenShift Target**: Detects `Dockerfile`, `Containerfile`, or an `openshift/`, `k8s/` or `helm/` folder at the root by its name, and sets the target to OpenShift, else to Virtual machine. It fills in no OpenShift target, and a service with database changes keeps its deployment settings.

---

## 16. Auto-Resolved Parameters Reference

When auto-resolution is active (`autoresolve parameters: true`, the default, which the Portal never changes), the pipeline automatically evaluates workspace files at execution time. This is not **Scan & Auto-fill**, which fills in only the fields named in [chapter 15](#15-repo-auto-discovery-and-smart-wizard-one-click-inspect). Every service of the Portal has a build tool, so the Build Tool rows apply only to a `devsecops_config.yaml` without `buildTool`.

A Liquibase service without database changes gets the Liquibase rows: empty build commands become `updateSQL`, empty unit test commands `validate`, and empty Flags of either `--changelog-file=` with a changelog path that the library finds at the root of the repository, else `src/main/resources/changelog.xml` (chapter 7 of `docs/documentation.md` lists the paths). Its SonarQube stage writes the `sonar.*` rows to a `sonar-project.properties` at the root of the workspace when the workspace has none there.

The library leaves a Liquibase service with database changes alone: it resolves none of these settings for it except an empty SonarQube project key, which becomes `com.bbh:<service name>`, so it detects no Node.js build and no deployment target, and its SonarQube stage writes no `sonar-project.properties`. Such a service has no Deployment section: the Portal writes no deployment target and no deployment settings for it, because its database changes replace the deployment, and the library ignores any deployment target of such a service.

Any parameters omitted from the Portal or configuration file are dynamically resolved. The build tool is detected in this order, in the library and in **Scan & Auto-fill** of the Portal alike: `pubspec.yaml` (Flutter), the iOS files, `pom.xml` (Maven), the Gradle files (Android with an `AndroidManifest.xml`), the .NET files, a Liquibase changelog, the Python files, `package.json` or `angular.json` at the root (npm), and at last a repository of only SQL and PL/SQL files (`sql`).

| Setting / Field | Target Key / Form Field | Workspace Indicator / Manifest | Resolved Default Value |
|---|---|---|---|
| Build Tool | `buildTool` | `pom.xml` present | `maven` |
| Build Tool | `buildTool` | `Podfile`, `Package.swift`, `*.xcodeproj` or `*.xcworkspace` present | `ios` |
| Build Tool | `buildTool` | `build.gradle(.kts)`, `settings.gradle(.kts)` or `gradle/wrapper/gradle-wrapper.properties` present | `gradle` |
| Build Tool | `buildTool` | Gradle files and `AndroidManifest.xml` at the root or in `app/src/main` | `android` |
| Build Tool | `buildTool` | `changelog.xml`, `changelog.yaml`, `liquibase.properties` | `liquibase` |
| Build Tool | `buildTool` | `pubspec.yaml` present | `flutter` |
| Build Tool | `buildTool` | `*.sln`, `*.csproj`, `*.fsproj`, `*.vbproj`, `global.json`, `Directory.Packages.props`, `Directory.Build.props`, `packages.config`, `build.cake` | `dotnet` |
| .NET mode | `build.dotnet.mode` | Empty or invalid (logged as `[WARN]`): SDK-style projects or a Linux agent -> `dotnet`; `packages.config` or .NET Framework 4.x projects on Windows -> `msbuild`; both -> `hybrid`; `build.cake` / `.nuke` -> `cake` / `nuke` | `auto` |
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

## 17. Bitbucket Pull Request Decoration & Shift-Left Feedback

Direct feedback in developer pull requests accelerates remediation and prevents vulnerabilities from reaching production.

### Pull Request Separation Strategy:
1. **Static Security Pull Request (Consolidated PR)**:
   - Evaluates Nexus IQ SCA, AppScan SAST, and SonarQube.
   - GoldenFix automatically updates vulnerable package manifests (`pom.xml`, `build.gradle`, `package.json`, etc.) in a dedicated remediation branch.
   - AppScan SAST findings are added with the fix AppScan recommends, read from the AppScan API: under the line of a file the pull request changes, otherwise as a pull request comment that names the file and the line. Findings with status `Noise` are skipped.
   - SonarQube code quality issues, security vulnerabilities, and code smells are added as inline comments.
   - Developers review all static findings and dependency updates in a single, unified Pull Request.
2. **DAST Dynamic Security Pull Request (Dedicated PR)**:
   - Runtime web application scan results (e.g. missing HTTP security headers, cookie flags, TLS configuration) are placed into a separate, dedicated Pull Request.
   - Keeps infrastructure and runtime security separated from application source code reviews.

### Pull Request of the Analysed Branch, Limits and Repeated Runs:
- **Where a comment goes:** the findings and the fix AppScan recommends are read from the AppScan on Cloud REST API (`Issues/Scan/<scan id>` and `Issues/<issue id>/Details`), not from the PDF or HTML report. A finding in a file the pull request changes is a comment of that file under the line of the finding. A finding in a file the pull request does not change is written in one pull request comment per file, which names the file and the line. A Nexus IQ upgrade is commented in the same way on the manifest that declares the dependency, with the version Nexus IQ recommends; AppScan knows only its own findings, so that fix comes from Nexus IQ. If the AppScan API gives no list of issues, a `[WARN]` line says so and the comments are built from the HTML report as before.
- **The pull request of the developer:** in a pull request build, or when the analysed branch has an open pull request in Bitbucket, that pull request gets the comments of the SAST, Nexus IQ and SonarQube findings and one summary comment. Branches the library creates itself (`GoldenFix-*`, `security-*`) are left out.
- **Limits:** `pullRequestDecoration.maxComments` (default 25) is the number of comments one run adds to one pull request; `pullRequestDecoration.sastMinSeverity` (default `MEDIUM`) and `pullRequestDecoration.sonarMinSeverity` (default `MAJOR`) leave out the findings below them. A wrong value falls back to its default with a `[WARN]` line, and a value equal to the default is named in an `[INFO]` line as a key that can be removed.
- **Repeated runs:** the library reads the comments that are already on the pull request and adds only those that are missing, so a second run of the same commit adds no duplicate.
- **Shared version catalog repository:** the clone of `goldenFix.versionCatalogRepo` signs in the way the push of the GoldenFix branch does: an SSH URL with the SSH key credential, an HTTPS URL with `authType: bearer` with a Secret text credential as the access token, any other HTTPS URL with a username and password credential. `credentialsId` and `authType` of the catalog fall back to those of `scm.bitbucket` of the service, and the credential never appears in the log.

---

## 18. Deployment Modes and Configuration Guide

### Mode 1: With Portal (`main` branch)
* **Configuration Source:** Centrally managed in ITSS COE OneView, area DevSecOps.
* **Jenkins Setup:** The global environment variable `ONEVIEW_URL` holds the address of OneView, for example `https://oneview.apps.bbh.com`; the agents of the label in `ONEVIEW_AGENT` (`linux-agent` when it is not set) read the configuration. The former names `DSO_PORTAL_URL` and `DSO_PORTAL_AGENT` still work when the new ones are not set or cannot be used; while a job still uses a release of the library from before the new names, both variables of each pair stay set with the same value. After its stages a run posts its pipeline report to `$ONEVIEW_URL/api/dso/reports` with the key of its `Jenkinsfile`, once for each key, without a further setting.
* **Onboarding:** Use **Scan & Auto-fill** in the service editor to auto-populate fields from Bitbucket: the build tool, the build and unit test commands, the Node.js build switch, the deployment target and an empty SonarQube project key. A service with database changes keeps all of them except the SonarQube project key.
* **Pipeline Execution:** Application repository requires only a single-line `Jenkinsfile`:
  ```groovy
  @Library('DSOEnhanced@main') _
  devSecOpsPipeline(pipelineKey: '5e0f0001-8c2b-4f6a-9d3e-1a7b2c4d0001')
  ```
* **Dynamic Resolution:** Any fields left blank in the Portal are auto-resolved at pipeline runtime by `RepoStackAutoResolver`; of a Liquibase service with database changes it fills in only an empty SonarQube project key. Such a service has no Deployment section: its database changes replace the deployment.

### Mode 2: Standalone Without Portal (`DSOEnhancedWithoutPortal`, or a Jenkinsfile without a pipeline key)
* **Configuration Source:** `devsecops_config.yaml` in the application Git repository.
* **Pipeline Report in OneView:** The `DSOEnhancedWithoutPortal` branch never talks to OneView and posts no report. A `Jenkinsfile` without a pipeline key on the `main` branch posts its report only when the job gives it a pipeline key in `DSO_PIPELINE_KEY` (or its `devsecops_config.yaml` names one in `oneview.pipelineKey`) and an address in `ONEVIEW_URL` (or `oneview.url`); without a key nothing is posted.
* **Auto-Resolve Default:** Enabled by default (`autoresolve parameters: true`).
* **Minimal Configuration Example:**
  ```yaml
  projects:
    payment-service:
      appId: "APP-54321"
  ```
* **Disabling Auto-Resolve:** To enforce strict manual configuration, set `autoresolve parameters: false`:
  ```yaml
  projects:
    payment-service:
      autoresolve parameters: false
      buildTool: maven
      deployTarget: vm
      appId: "APP-54321"
  ```
* **One AppScan API key:** SAST and DAST sign in through the AppScan on Cloud REST API v4 with the API key of the functional user from the library defaults (`asoc.keyId`, `asoc.token`); DAST reuses the token of SAST, and a key of a service is ignored with a warning.
* **Static Scan Deduplication (SAST, SonarQube, Nexus IQ):** When multiple services sharing the same Git/Bitbucket repository (e.g. multi-module monorepos or shared sources) configure the same scan identifier (`appId` for AppScan SAST, `projectKey` for SonarQube, or `application` for Nexus IQ), the respective scan or policy evaluation is executed only once. Secondary services automatically reuse the existing scan IDs, findings, HTML/PDF reports, quality gate verdicts, and vulnerability counts.
* **Multi-Repository Products & ID Isolation:** Products can configure services across independent Bitbucket repositories (including separate Bitbucket projects and repository URLs). When services point to different Bitbucket repository URLs, they are strictly required to use distinct AppScan application IDs, SonarQube project keys, and Nexus IQ applications. Attempting to share scan identifiers across different repositories is prevented both in Portal validation and at pipeline initialization.
