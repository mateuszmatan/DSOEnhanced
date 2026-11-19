# DevSecOps Management Portal & DSOEnhanced – User Guide

> **Comprehensive Guide for the Portal-Integrated Version (`DevSecOpsManagementPortal` + `DSOEnhanced` library on `main` branch).**
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

---

## 1. Overview and Architecture

The DevSecOps Management Portal is the central management interface for BBH delivery pipelines.

### Key Principles:
- **No secrets in source code:** No API keys, passwords, or tokens are ever committed to Git repositories. Credentials reference secure Jenkins credentials store IDs.
- **Single Pipeline Key (`pipelineKey`):** Each pipeline receives a unique secret key. The application's `Jenkinsfile` contains only this key:
  ```groovy
  @Library('DSOEnhanced@main') _
  devSecOpsPipeline(pipelineKey: '5e0f0001-8c2b-4f6a-9d3e-1a7b2c4d0001')
  ```
- **Automated Configuration Retrieval:** When a Jenkins build starts, the shared library fetches its configuration directly from the Portal via REST API, executes the required stages, and streams telemetry to InfluxDB.
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
| **Database / SQL & PL/SQL** | SQL, PL/SQL, T-SQL, XML/YAML changelogs | `liquibase` (`sql`, `plsql`) or combined block on Maven/Gradle | `liquibase.properties`, `changelog.xml`, `changelog.yaml`, `changelog.sql`, `db/changelog/db.changelog-master.xml` | `liquibase validate` (coverage gate automatically marked `NOT_REQUIRED` for standalone SQL) | `sonar-scanner` with `-Dsonar.sources`, `inclusions`, `exclusions`, and PL/SQL suffixes | JDBC/extension JARs scanned via Nexus IQ | `.sql`, `.pls`, `.pks`, `.pkb`, `.plb`, `.prc`, `.fnc`, `.trg`, `.xml`, `.yaml` |

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
  - The pipeline report shows the same list for each run in the *Auto-resolved settings* card, and the report upload stores it with the run in the portal.

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

The pipeline supports all standard BBH languages and build tools:

| Language / Stack | Build Tool | Standard Build Command | Use Case |
|---|---|---|---|
| **Java** | Maven (`mvn`) | `clean package -DskipTests` | Enterprise backend APIs |
| **Kotlin** | Gradle (`gradle`) | `clean assemble -x test` | Modern microservices |
| **Groovy** | Gradle / Maven | `clean build -x test` | Automation utilities & libraries |
| **Node.js / TypeScript** | npm | `npm run build` | Web backends & BFF services |
| **Angular** | npm | `npm ci && npm run build --prod` | Client-facing web apps |
| **Python** | pip / python3 | `pip install -r requirements.txt` | Data analytics & automation |
| **SQL / PL-SQL** | Liquibase | `liquibase updateSQL` (default of the auto-resolve; `validate` with `autoresolve parameters: false`) | Oracle & PostgreSQL migrations |
| **Flutter / Dart** | flutter / pub | `flutter build` | Mobile & desktop applications |
| **C# / F# / VB.NET** | *C# / .NET*: `dotnet` CLI or classic `MSBuild.exe` + `NuGet.exe` | detected: `dotnet build`, or `NuGet.exe restore` + `MSBuild.exe /t:Build` on Windows | .NET Framework 4.x, .NET Core and .NET 6–9+ services on Windows or Linux agents |

### SQL and PL/SQL with a database block:
- A Liquibase service with `database.changelogFile` gets the database block: the Build artifact stage validates and packages the changes offline, Unit tests deploys them to a test database and runs utPLSQL with coverage, and the zip is delivered to Nexus and deployed to RD and QC with Jenkins credentials and a compile check. See [chapter 14 of the library documentation](../documentation.html#ch14).
- The database block is for Oracle. In a repository with an application, the folder of the changelogs is a Liquibase service of its own, listed before the application service; chapter 14 shows three complete repository layouts.

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
- **Background Parallel Scanning:**
  - The IRX archive is generated immediately after build.
  - The HCL ASOC cloud scan is queued in the background and runs **in parallel** with Nexus IQ and SonarQube.
  - Results are fetched at the end of the static analysis stage, significantly reducing overall pipeline duration.
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
2. **Stage 2: Concurrent Verification (Unit Tests + SAST + Nexus IQ):**
   - **Unit Tests:** Runs automated tests (including Node.js & Cypress tests) and aggregates JaCoCo & LCOV code coverage.
   - **SAST (HCL AppScan):** Queues static application security analysis in the background and retrieves findings.
   - **Nexus IQ:** Evaluates open-source dependencies against security policies and prepares GoldenFix remediation.
3. **Stage 3: SonarQube (Static Code Analysis & Quality Gate):**
   - Runs last after Unit tests, SAST, and Nexus IQ have concluded.
   - Ingests test execution results and JaCoCo/LCOV/Cypress coverage reports to enforce the SonarQube Quality Gate.

---

## 11. Deployments & Delivery – OpenShift and Virtual Machines (VM)

The delivery module packages artifacts and deploys services to target infrastructure.

### "Disable Nexus Delivery" Checkbox:
- Allows skipping artifact publishing to Nexus:
  - For VM targets: `deploy.vm.rd.nexusDeliveryEnabled: false`
  - For OpenShift targets: `deploy.openshift.rd.nexusDeliveryEnabled: false`
- When checked, delivery fields are hidden and their validation is bypassed.

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
| **Build Tool** | Technology build system | `maven`, `gradle`, `npm`, `pip` | Application runtime | <span class="badge badge-required">YES</span> |
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
