# DevSecOps Jenkins Shared Library – Standalone User Guide (Without Portal)

> **Comprehensive Guide for Pipelines Running Without the Portal (`DSOEnhanced` library on `DSOEnhancedWithoutPortal` branch).**
> Fully autonomous delivery pipeline configured via code using `Jenkinsfile` and `devsecops_config.yaml` in your Git repository.
> Designed for non-technical users, Product Owners, business analysts, and engineers.

---

## Contents

- [1. Overview and Standalone Model](#1-overview-and-standalone-model)
- [2. The Jenkinsfile: Defining Your Pipeline](#2-the-jenkinsfile-defining-your-pipeline)
- [3. The devsecops_config.yaml Configuration File](#3-the-devsecops_configyaml-configuration-file)
- [4. Multi-Service and Multi-Repository Bitbucket Support](#4-multi-service-and-multi-repository-bitbucket-support)
- [5. Supported Languages and Build Tools](#5-supported-languages-and-build-tools)
- [6. Unit Testing and Multi-Report Code Coverage](#6-unit-testing-and-multi-report-code-coverage)
- [7. Static Application Security Testing (SAST) – HCL AppScan](#7-static-application-security-testing-sast--hcl-appscan)
- [8. Dynamic Application Security Testing (DAST) – HCL AppScan](#8-dynamic-application-security-testing-dast--hcl-appscan)
- [9. Code Quality – SonarQube & Alternative Badge Tokens](#9-code-quality--sonarqube--alternative-badge-tokens)
- [10. Dependency Security & GoldenFix – Nexus IQ](#10-dependency-security--goldenfix--nexus-iq)
- [11. Deployments and "Disable Nexus Delivery" (OpenShift & VM)](#11-deployments-and-disable-nexus-delivery-openshift--vm)
- [12. Telemetry, DORA Metrics, InfluxDB, and Grafana](#12-telemetry-dora-metrics-influxdb-and-grafana)
- [13. Complete devsecops_config.yaml Template with Field Reference](#13-complete-devsecops_configyaml-template-with-field-reference)

---

## 1. Overview and Standalone Model

In the `DSOEnhancedWithoutPortal` branch, the library operates independently of the management portal:
- **No Portal Dependency:** The pipeline never polls external portal APIs for configuration.
- **Configuration as Code:** All pipeline parameters are stored in `devsecops_config.yaml` (or backward-compatible `config.yaml`) at the root of your application Git repository.
- **Embedded Library Defaults:** Global enterprise standards (coverage thresholds, timeouts, InfluxDB telemetry endpoints) are bundled inside the library (`defaults.yaml`), meaning you only specify settings unique to your application.

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
- `devSecOpsSecurityPipeline`: Fast security validation (Build, SAST, SonarQube, Nexus IQ).
- `devSecOpsNexusIqGoldenFixPipeline`: Dedicated automated dependency remediation pipeline.

---

## 3. The devsecops_config.yaml Configuration File

The `devsecops_config.yaml` file resides in the root directory of your repository.

### File Evaluation Order:
- The library looks for `devsecops_config.yaml` first.
- If not found, it checks for `config.yaml`.
- The library reads the `projects:` section where each service of your application is declared.

---

## 4. Multi-Service and Multi-Repository Bitbucket Support

The standalone pipeline natively supports complex project architectures:

### 1. Multiple Services in One Pipeline (Multi-Service)
- Declare multiple services under `projects:` (e.g., `backend-api` and `web-gui`).
- Each service can point to **its own independent Bitbucket repository** (`scm.bitbucket.repositoryUrl`).
- Each service maintains its own target branch (`scm.bitbucket.targetBranch`) and pull request reviewers list (`scm.bitbucket.reviewers`).

### 2. Multi-Module Projects
- Multi-module Maven projects (`pom.xml`) and Gradle subprojects are automatically discovered and built.
- Version catalogs (`gradle/libs.versions.toml`, `platform/build.gradle`) are parsed and updated in a single consolidated Pull Request by GoldenFix.

---

## 5. Supported Languages and Build Tools

The pipeline supports all enterprise programming languages and build tools:

- **Java / Kotlin / Groovy:**
  - Build via Maven (`tool: 'maven'`) or Gradle (`tool: 'gradle'`).
  - Standard command: `clean package -DskipTests` or `clean assemble -x test`.
- **Node.js / Angular:**
  - Build via npm (`tool: 'npm'`).
  - Standard command: `npm ci && npm run build --prod`.
  - Can be run as **Stage 0** before Maven/Gradle backend builds.
- **Python:**
  - Build via pip (`tool: 'pip'`).
  - Command: `pip install -r requirements.txt`.
- **SQL / PL-SQL (Liquibase):**
  - Automatic detection of database changelogs (`detectTool()`).
  - Command: `liquibase validate` (the default of `build.liquibase`).
  - With `database.changelogFile` the service gets the database block: the Build artifact stage validates and packages the changes offline, Unit tests deploys them to a test database and runs utPLSQL with coverage, and the zip is delivered to Nexus and deployed to RD and QC with Jenkins credentials and a compile check. See [chapter 14 of the library documentation](../documentation.html#ch14).
  - The database block is for Oracle. In a repository with an application, the folder of the changelogs is a Liquibase service of its own, listed before the application service; chapter 14 shows three complete repository layouts.
  - Static code analysis and validation through SonarQube.
- **Flutter / Dart:**
  - Mobile and desktop application packaging with LCOV test coverage generation.

---

## 6. Unit Testing and Multi-Report Code Coverage

- **Automated Multi-Report Discovery:**
  - The pipeline automatically searches all workspace directories for test reports:
    - **JaCoCo XML:** Java, Kotlin, Groovy test coverage reports.
    - **LCOV (`lcov.info`):** JavaScript, TypeScript, Angular, Python, Flutter, and Cypress reports.
- **Node.js & Cypress Unit Testing:**
  - Dedicated support for Node.js unit and component tests via Cypress (`tests.unitTests.cypress`).
  - Seamless execution of Cypress tests (`npx cypress run --component` or custom command) within the Node.js environment.
  - Ingestion of JUnit XML test results and automatic aggregation of Cypress LCOV coverage data (`coverage/lcov.info`).
- **Per-Module Reporting:**
  - Every individual report has a dedicated log and report line showing file path, line count, and percentage.
- **Project Run Summary:**
  - Calculates an aggregated average line for the entire project (`All services`).
- **Release Quality Gate:**
  - `coverage.minimum: 80` enforces code coverage. If the project average is below 80%, the build fails, protecting production.

---

## 7. Static Application Security Testing (SAST) – HCL AppScan

Static security analysis executed in the cloud:

- **Parallel Background Scanning:**
  - Generates the IRX archive and submits the scan to HCL ASOC in the background.
  - Unit tests and Nexus IQ execute concurrently while ASOC processes the code. Results are retrieved at the end of the static stage.
- **Credentials Configuration:**
  - Configured per service in the `asoc:` section:
    ```yaml
    asoc:
      keyId: 'asoc-key-id-backend'
      token: 'asoc-secret-id-backend'
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
  - Creates a dedicated branch and opens a Bitbucket Pull Request with the updated `pom.xml`, `build.gradle`, `package.json`, `requirements.txt`, or `libs.versions.toml`.

- **External Version Catalog Repository:**
  - When version catalogs or BOMs reside in an external Bitbucket repository:
    ```yaml
    goldenFix:
      versionCatalogRepo:
        url: 'ssh://git@bitbucket.bbh.com/shared/version-catalogs.git'
        credentialsId: 'bitbucket-http-credentials'
        branch: 'main'
        path: 'catalogs/libs.versions.toml'
    ```
  - GoldenFix checks out the external repository, upgrades dependency versions, validates changes, and submits the Pull Request directly to the external catalog repository.

### Decoupled Pipeline Execution Order:
1. **Stage 1: Build Artifact (without tests):** The pipeline first builds application artifacts without executing unit tests (e.g., `-DskipTests` / `-x test`). If a Node.js build is configured (`build.node.enabled: true`), npm commands run as step 0 at the start of this stage before compiling the backend application.
2. **Stage 2: Concurrent Verification (Unit Tests + SAST + Nexus IQ):**
   - **Unit Tests:** Runs automated unit tests (including Node.js & Cypress tests) and aggregates JaCoCo & LCOV code coverage.
   - **SAST (HCL AppScan):** Queues static application security analysis in the background and retrieves findings.
   - **Nexus IQ:** Evaluates open-source dependencies against security policies and prepares GoldenFix remediation.
3. **Stage 3: SonarQube (Static Code Analysis & Quality Gate):**
   - Runs last after Unit tests, SAST, and Nexus IQ have concluded.
   - Ingests test execution results and JaCoCo/LCOV/Cypress coverage reports to enforce the SonarQube Quality Gate.

---

## 11. Deployments and "Disable Nexus Delivery" (OpenShift & VM)

### Skipping Nexus Artifact Publishing:
If your deployment does not require publishing binaries to Nexus, toggle delivery off:
- For Virtual Machines (VM): `deploy.vm.rd.nexusDeliveryEnabled: false`
- For OpenShift containers: `deploy.openshift.rd.nexusDeliveryEnabled: false`
- The pipeline skips artifact upload and suppresses delivery validation errors.

### Optional OpenShift QC Region:
- The `deploy.openshift.rd.regions.qc` block is entirely optional. Omitting it will not cause validation errors.

---

## 12. Telemetry, DORA Metrics, InfluxDB, and Grafana

Every stage automatically emits telemetry points to InfluxDB:
- **DORA Metrics:** Deployment Frequency, Lead Time for Changes, Change Failure Rate, Time to Restore Service (MTTR).
- **Quality & Duration Metrics:** Build duration, test counts, JaCoCo/LCOV coverage points, vulnerability totals.
- Visualize results across BBH enterprise Grafana dashboards.

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
      tool: 'maven'                               # Supported: maven, gradle, npm, pip
      tasks: 'clean package -DskipTests'          # Build command

    # 3. Unit Tests & Code Coverage
    unitTests:
      tasks: 'test'                               # Test execution command
    coverage:
      minimum: 80                                 # Minimum required coverage (%)
      # reportPath: empty = auto-detect JaCoCo XML & LCOV throughout project

    # 4. Static Application Security Testing (HCL AppScan SAST)
    asoc:
      keyId: 'asoc-key-id-backend'                # Jenkins credentials ID
      token: 'asoc-secret-id-backend'             # Jenkins credentials ID
      appId: '10982'                              # Application ID in HCL ASOC
    sast:
      scanName: 'PAYHUB-BACKEND-API'              # Optional custom scan label

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
      keyId: 'asoc-key-id-gui'
      token: 'asoc-secret-id-gui'
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
| `build.tool` | Compilation tool | `maven`, `gradle`, `npm`, `pip` | <span class="badge badge-required">YES</span> |
| `build.tasks` | Build command string | `clean package -DskipTests` | <span class="badge badge-required">YES</span> |
| `unitTests.tasks` | Test command string | `test` or `npm test` | <span class="badge badge-optional">OPTIONAL</span> |
| `unitTests.cypress.enabled` | Enable Cypress component tests | `true` or `false` | <span class="badge badge-optional">OPTIONAL</span> |
| `unitTests.cypress.command` | Custom Cypress command | `npx cypress run --component` | <span class="badge badge-optional">OPTIONAL</span> |
| `unitTests.cypress.reportsDir` | Cypress JUnit reports dir | `cypress/results` | <span class="badge badge-optional">OPTIONAL</span> |
| `coverage.minimum` | Quality gate threshold | `80` | <span class="badge badge-default">DEFAULT 80</span> |
| `asoc.keyId` & `asoc.token` | AppScan SAST credentials | `asoc-cred-id` (from Jenkins) | <span class="badge badge-required">YES</span> |
| `sast.scanName` | ASOC scan display name | `PAYHUB-NIGHTLY` | <span class="badge badge-optional">OPTIONAL</span> |
| `tools.sonar.authToken` | SonarQube authentication secret | `sonar-token-id` | <span class="badge badge-required">YES</span> |
| `tools.sonar.badgeToken` | Sonar badge token (raw) | `raw-token-string` | <span class="badge badge-optional">1 OF 2</span> |
| `tools.sonar.badgeCredentialsId` | Sonar badge secret in Jenkins | `sonar-badge-cred` | <span class="badge badge-optional">1 OF 2</span> |
| `tools.nexusIq.applicationId` | Single app ID in Nexus IQ | `payhub-backend-api` | <span class="badge badge-required">YES</span> |
| `goldenFix.pullRequest` | Auto-create remediation PR | `true` | <span class="badge badge-default">DEFAULT TRUE</span> |
| `nexusDeliveryEnabled` | Toggle Nexus delivery | `false` | <span class="badge badge-optional">OPTIONAL</span> |

