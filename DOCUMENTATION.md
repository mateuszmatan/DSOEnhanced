# DevSecOpsJenkinsLibrary

Jenkins Shared Library implementing a full DevSecOps pipeline: build, unit tests, dependency scan (Nexus IQ), code quality (SonarQube), SAST (HCL AppScan), deployment (VM/OpenShift), smoke/regression/performance tests, DAST (HCL AppScan), HTML report, and InfluxDB metrics.

---

## Table of contents

1. [What the library provides](#1-what-the-library-provides)
2. [Application requirements](#2-application-requirements)
3. [Architecture overview](#3-architecture-overview)
4. [Prerequisites](#4-prerequisites)
5. [Step 1 – Register the library in Jenkins](#5-step-1--register-the-library-in-jenkins)
6. [Step 2 – Add two files to your project repository](#6-step-2--add-two-files-to-your-project-repository)
7. [Step 3 – Configure the Jenkins job](#7-step-3--configure-the-jenkins-job)
8. [Step 4 – First run](#8-step-4--first-run)
9. [config.yaml reference](#9-configyaml-reference)
10. [Pipeline stages](#10-pipeline-stages)
11. [Policy thresholds](#11-policy-thresholds)
12. [Advanced: using library methods directly](#12-advanced-using-library-methods-directly)
13. [Supported build tools](#13-supported-build-tools)
14. [Supported deployment targets](#14-supported-deployment-targets)
15. [Troubleshooting](#15-troubleshooting)
16. [SelfService – onboard a project step by step](#16-selfservice--onboard-a-project-step-by-step)
17. [Library development and tests](#17-library-development-and-tests)

---

## 1. What the library provides

The library executes the following pipeline automatically when you call `devSecOpsPipeline()` from your Jenkinsfile:

| # | Stage | What it does |
|---|-------|-------------|
| 1 | Monitor source changes | Checkout, AppScan setup, IRX generation per project |
| 2 | Unit tests | Build tool tests plus JaCoCo/lcov line coverage check against the library minimum; a failed test run or missing coverage turns the stage orange and the pipeline continues |
| 3 | Dependencies scan (Nexus IQ) | Dependency scan, an orange stage on a policy violation and an automatic GoldenFix pull request |
| 4 | SAST – HCL AppScan | Static Application Security Testing, queued per project |
| 5 | SCA (SonarQube) | Static code analysis and quality gate |
| 6 | Nexus delivery (static analysis passed) | Publish the snapshot artifact, always, even when an earlier stage is orange |
| 7 | Lower test region deployment | Deploy to the lower test region: VM with UrbanCode Deploy (`devSecOpsPipeline`) or over SSH (`devSecOpsExtendedPipeline`), or OpenShift |
| 8 | Regression tests | Trigger regression jobs, at least one job must be configured |
| 9 | Smoke tests | Trigger smoke jobs, at least one job must be configured |
| 10 | Performance tests | Trigger performance jobs, at least one job must be configured |
| 11 | DAST – HCL AppScan | Dynamic Application Security Testing, HTML report, HCL console link and PDF report |
| 12 | Nexus delivery (safe artifact) | Publish the release artifact, blocked when any earlier stage is orange |
| 13 | Higher test environment deployment | Deploy to QC, only when every earlier stage is green and the checkbox is selected |

### Which pipeline to use

The library ships four entry points. They share the same services, thresholds, release gate and report, and all of them are built from the same stage methods in `vars/devSecOpsSteps.groovy`. Each stage method takes the `devSecOpsApi` instance of its entry point as the first argument, for the reason explained in [one devSecOpsApi instance per build](#12-advanced-using-library-methods-directly).

| Entry point | Runs | Use it when |
|-------------|------|-------------|
| `devSecOpsPipeline` | All thirteen stages in one build: unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery, RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment | One job should cover the whole flow |
| `devSecOpsSecurityPipeline` | Unit tests, Nexus IQ, SAST, SonarQube, snapshot delivery; archives `config.yaml` and the release gate verdict, and triggers the extended job when `RUN_EXTENDED_PIPELINE` is selected | The static part runs on every commit and the rest is a separate job |
| `devSecOpsExtendedPipeline` | RD deployment, regression, smoke, performance, DAST, release delivery, QC deployment | Downstream of the security pipeline; name that job in `securityPipeline:` and it inherits the release verdict through the copied `release-gate.json` |
| `devSecOpsSASTScanningPipeline` | Monitor sources and SAST only | A job that runs just the AppScan static scan; its report shows only the SAST stage and the SAST findings |

After all stages, the library always:
- Generates an HTML pipeline report with the stage flow, vulnerability counts, policy status, SonarQube badges and coverage
- Adds a **Smoke tests** table with one row per executed job and a link to each build
- Adds a **Nexus IQ GoldenFix** card with the pull request link, the applied dependency upgrades and the fixes that could not be applied automatically
- Adds a **Release policy** card stating whether the artifact may be released to Nexus and deployed to QC
- Archives the HTML reports, the DAST PDF report and the release gate verdict (`release-gate.json`) as build artifacts
- Sends DORA metrics to InfluxDB (if configured)

---

## 2. Application requirements

Before onboarding a project to the DevSecOps pipeline, the application and its repository must meet the following requirements:

- **Git repository accessible from Jenkins.**
  The project must live in a Git repository that the Jenkins controller can clone. The `Jenkinsfile` and `config.yaml` must be committed at the repository root and pushed to the branch configured in the Jenkins job.

- **Supported build tool: Gradle, Maven, or Flutter.**
  The codebase must use one of the three supported build systems. Gradle wrapper (`gradlew`) and Maven wrapper (`mvnw`) are preferred and must be executable and committed to the repository. The build tool is either declared in `config.yaml` or auto-detected.

- **Code must compile successfully.**
  The SAST stage (stage 4) compiles the project before it generates the IRX archive, unless `asoc.doCompile: false`. The compile command comes from `asoc.gradle` / `asoc.maven`, or from the `build` section when `asoc` has none. A compilation error fails the SAST stage, so make sure every compile-time dependency is resolvable from the configured Nexus repositories.

- **Unit tests with JaCoCo coverage (Gradle/Maven) or lcov (Flutter).**
  The project must have runnable unit tests. For Gradle and Maven, JaCoCo must be configured in the build script to produce a coverage XML report. The library requires a minimum line coverage of 60 %, taken from `resources/defaults.yaml` only; a project cannot set its own value. Below that level the Unit tests stage turns orange, the pipeline keeps running and reaches RD, and the Nexus release and the QC deployment stay blocked. A failing test run is treated the same way: the stage turns orange with the exit code as the reason, the coverage is still evaluated on the reports that were produced, and every later stage still runs. Flutter projects must use the `--coverage` flag which produces `coverage/lcov.info`.

- **A JDK on the agent, named in the configuration.**
  Set `javaPath` in the project configuration to the JDK the build and the tests must use, or set `buildToolAutoSetup: true` to let the library detect the required Java version from `pom.xml` or `build.gradle` and pick a matching JDK from the agent. Without either, the Unit tests stage stops with *JAVA_HOME parameter is not specified*, unless the agent already exports `HAS_BUILD_TOOL_INSTALLED=true`.

- **HCL AppScan on Cloud (ASoC) application registered.**
  The application must be registered in ASoC and have a known application ID (UUID). An API key pair (`keyId` + `keySecret`) must be obtained from the ASoC portal and placed in `config.yaml`. Both SAST and DAST scans are uploaded to this application.

- **SonarQube project registered.**
  A project must exist in SonarQube with a unique `projectKey`. A badge token (`badgeToken`) is required for SonarQube metric badges to appear in the HTML pipeline report. The SonarQube server must be reachable from the Jenkins agent.

- **Nexus IQ application registered.**
  The application must be registered in Nexus IQ with its public application ID. Build artifacts (JARs, WARs) must match the configured `scanPatterns` in `config.yaml`. The Nexus IQ server must be reachable from the Jenkins agent.

- **Build artifacts produced in expected locations.**
  Gradle projects must produce artifacts under `build/libs/`. Maven projects under `target/`. These paths must match the `scanPatterns` configured for Nexus IQ and be valid artifact outputs for publishing.

- **Deployment script and version file (VM deployment over SSH).**
  For SSH deployment, the repository must contain a zero-downtime deployment shell script and a `version.properties` file with an `APP_VERSION=<version>` entry. The Jenkins agent must have SSH key-based (passwordless) access to all configured deployment hosts. The extended pipeline and the QC deployment use this path.

- **UrbanCode Deploy application (VM deployment in `devSecOpsPipeline`).**
  The lower test region deployment of the full pipeline publishes the artifact as a UrbanCode Deploy component and starts the deployment process, so `deploy.vm.dod` must name the site, the application and at least one component. The UrbanCode Deploy plugin (`UCDeployPublisher`) and the configured site must exist in Jenkins.

- **OpenShift templates and a Dockerfile (OpenShift deployment).**
  For OpenShift-based deployment the repository must contain the BuildConfig template, the Dockerfile, the deployment template and the config template referenced by `deploy.openshift.rd.buildConfigPath`, `dockerFilePath`, `deployConfigPath` and `configPathR`. The snapshot stage builds the image in the build namespace and copies it to the Nexus docker registry with `skopeo`, so the agent needs `skopeo`, `oc` and the authfile named in `deploy.openshift.rd.nexus.authfile`.

- **Test jobs exist in Jenkins.**
  Smoke, regression, and performance test jobs must be created as Jenkins jobs before the pipeline reaches those stages. Their full job paths must be configured in `config.yaml`. All three test types require at least one job entry — placeholder entries with `job: ""` are allowed initially and will show `NOT_CONFIGURED` without failing.

- **DAST target accessible from Jenkins agent (DAST only).**
  When `dast.enabled: true`, the configured `dast.targetUrl` must be reachable from the Jenkins agent running the pipeline. For internal (non-internet) targets, an AppScan Presence must be deployed and its ID provided in `dast.presenceId`.

- **InfluxDB endpoint accessible (optional).**
  If DORA metrics are required, an InfluxDB v2 instance must be reachable from the Jenkins agent and the configured write endpoint must accept the provided auth token with write permissions to the target bucket.

---

## 3. Architecture overview

```
DevSecOpsJenkinsLibrary/           <- library repository root
├── DOCUMENTATION.md               <- this file
├── documentation.html             <- the same documentation as a single HTML page, generated from it
├── config.yaml.template           <- copy into your project and fill in
├── grafana-queries.md             <- Flux queries for Grafana dashboards (InfluxDB metrics)
├── resources/
│   └── defaults.yaml              <- embedded non-overridable defaults (read via libraryResource)
├── vars/                          <- Jenkins global variables: the entry points and the composition root
│   ├── devSecOpsPipeline.groovy               <- entry point: all thirteen stages
│   ├── devSecOpsSecurityPipeline.groovy       <- entry point: static analysis part
│   ├── devSecOpsExtendedPipeline.groovy       <- entry point: deployment and test part
│   ├── devSecOpsSASTScanningPipeline.groovy   <- entry point: SAST only
│   ├── devSecOpsSteps.groovy      <- one method per stage, included by every entry point
│   └── devSecOpsApi.groovy        <- composition root: builds every service and exposes the helper methods
├── src/com/bbh/
│   ├── core/                               <- the domain core, no Jenkins plugin knowledge
│   │   ├── PipelineState.groovy            <- everything the stages record: results, counts, coverage, links
│   │   ├── PolicyEngine.groovy             <- library policy, orange stages and the stage result log
│   │   ├── ReleaseGate.groovy              <- blocks the Nexus release and the QC deployment when a stage is not green
│   │   └── OsHelper.groovy
│   ├── config/ConfigLoader.groovy          <- config.yaml + defaults.yaml -> PipelineState
│   ├── build/
│   │   ├── BuildService.groovy             <- build, unit tests, coverage, remote test jobs
│   │   └── BuildRunner.groovy              <- one runner for the Gradle and Maven commands
│   ├── scanner/                            <- adapters for the scanners
│   │   ├── AppScanService.groovy           <- HCL AppScan SAST and DAST
│   │   ├── NexusIqService.groovy           <- Nexus IQ scan, calls the remediation port on a violation
│   │   ├── NexusIqGoldenFixSource.groovy   <- adapter: Nexus IQ report + Component Remediation REST API
│   │   └── SonarService.groovy
│   ├── remediation/                        <- GoldenFix, ports and adapters
│   │   ├── GoldenFixService.groovy         <- application service: fetch -> patch manifests -> commit/push -> pull request
│   │   ├── model/GoldenFix.groovy          <- the remediation domain: the fix record, version semantics and the version choice
│   │   ├── port/DependencyRemediation.groovy  <- inbound port used by NexusIqService
│   │   ├── port/GoldenFixSource.groovy        <- outbound port: where the proposed versions come from
│   │   ├── port/SourceRepository.groovy       <- outbound port: source tree, commit, push
│   │   ├── port/PullRequestPublisher.groovy   <- outbound port: pull request
│   │   ├── port/ManifestUpdater.groovy        <- outbound port: one build manifest format
│   │   └── updater/                        <- MavenPomUpdater, GradleUpdater, NpmPackageJsonUpdater, PipUpdater, PubUpdater
│   ├── scm/                                <- adapters: GitSourceRepository (git worktree), Bitbucket pull requests
│   ├── deploy/                             <- adapters: VmDeployService (SSH, UrbanCode Deploy), OpenshiftService
│   ├── metrics/InfluxDbService.groovy      <- adapter: DORA metrics
│   ├── report/HtmlReportService.groovy     <- one report template, the variant only selects the stage layout
│   └── utils/                              <- RestClient (curl JSON client), BuildUtils, FlutterUtils
├── tools/build-documentation-html.py       <- renders DOCUMENTATION.md into documentation.html
├── test/                          <- the library test suite, see section 17
│   ├── run-all.sh                 <- one command: static checks, compilation, sandbox scenarios, demo reports
│   ├── checks/                    <- static checks of the sources
│   └── sandbox/                   <- the library running under the real Jenkins script sandbox
└── examples/
    ├── README.md                  <- how to split the pipeline into a static and an extended job
    ├── CertScanner/               <- a complete two project example: Jenkinsfile and config.yaml
    └── reports/                   <- generated demo reports, one HTML page per pipeline and outcome
```

**How the parts fit together (hexagonal):** `core` holds the state, the policy and the release gate and knows nothing about Jenkins plugins. The GoldenFix feature is a full hexagon: the application service in `remediation` talks only to the ports in `remediation/port`, the domain rules (which version to take, how versions compare) live in `remediation/model`, and the adapters sit in `scanner` (Nexus IQ), `scm` (git, Bitbucket) and `remediation/updater` (one per manifest format). `vars/devSecOpsApi.groovy` is the single composition root that assembles every service; the services in `scanner`, `build`, `deploy`, `metrics` and `report` are adapters around the core, each receiving the pipeline script object and translating between the tools and the state. A check in the test suite enforces these dependency directions.

**How the parts fit together (hexagonal):** `core` holds the state, the policy and the release gate and knows nothing about Jenkins plugins. The GoldenFix feature is a full hexagon: the application service in `remediation` talks only to the ports in `remediation/port`, and the adapters live in `scanner` (Nexus IQ), `scm` (git, Bitbucket) and `remediation/updater` (manifest formats). `vars/devSecOpsApi.groovy` and `wiring/GoldenFixFactory.groovy` are the composition roots that assemble the services; the remaining services in `scanner`, `build`, `deploy`, `metrics` and `report` are adapters around the core, each of them receiving the pipeline script object and translating between the tools and the state.

**Config split:**

| Source | Who controls it | Contains |
|--------|----------------|----------|
| `resources/defaults.yaml` (inside library) | Library team only | Security thresholds, required coverage, timeouts, tool defaults |
| `config.yaml` in your project repo | Project team | Project-specific settings (appId, servers, test jobs, deploy targets) |

Projects **cannot** override the `defaults` – they are embedded in the library. Thresholds for SAST, SCA, Nexus IQ, DAST and the required line coverage exist in one place only, `resources/defaults.yaml`. To change one, the library must be updated and redeployed.

---

## 4. Prerequisites

The Jenkins controller and build agents must have the following installed and configured:

### Jenkins plugins (required)

| Plugin | Purpose |
|--------|---------|
| Pipeline | Core declarative pipeline support |
| Pipeline: Shared Groovy Libraries | Load this shared library |
| Git | Source checkout |
| HTML Publisher | Publish HTML pipeline report |
| JUnit | Publish unit test results |
| SonarQube Scanner | SonarQube integration |
| Nexus Platform | Nexus IQ integration (`nexusPolicyEvaluation`) |
| Parameterized Remote Trigger | Remote smoke, regression and performance jobs (also by full URL) |
| Pipeline Utility Steps | `readYaml`, `readJSON`, `writeFile` |
| Credentials Binding | `withCredentials` for Nexus IQ, ASoC, Bitbucket, remote Jenkins |
| Copy Artifact | Passing `config.yaml` and `release-gate.json` to the extended pipeline |
| OpenShift Client (optional) | OpenShift `openshift.withCluster()` |

### Steps the library calls

Besides the standard pipeline steps, the library calls the following. A missing one fails the stage that uses it, so check them when onboarding a new Jenkins instance.

| Step | Provided by | Used for |
|------|-------------|----------|
| `nexusPolicyEvaluation`, `selectedApplication` | Nexus Platform plugin | Dependency scan |
| `withSonarQubeEnv`, `waitForQualityGate` | SonarQube Scanner plugin | SonarQube analysis and quality gate |
| `triggerRemoteJob` | Parameterized Remote Trigger plugin | Remote smoke, regression and performance jobs |
| `copyArtifacts`, `lastSuccessful` | Copy Artifact plugin | Passing `config.yaml` and the release gate verdict downstream |
| `publishHTML` | HTML Publisher plugin | Publishing the pipeline report |
| `junit` | JUnit plugin | Unit test results |
| `readYaml`, `readJSON`, `writeJSON`, `findFiles` | Pipeline Utility Steps plugin | Configuration and report state |
| `openshift` | OpenShift Client plugin | OpenShift deployment |
| `sshagent` | SSH Agent plugin | Pushing the GoldenFix branch |
| `git`, `checkout` | Git plugin | Source checkout |
| `reportBuild`, `reportUnitTest`, `reportSurefireTest`, `addSonarBadgesToDescription` | **Global steps of the BBH Jenkins installation** | Build reporting and SonarQube badges |

The last row is not a public plugin. Those four steps must exist as global variables in the Jenkins instance, otherwise the Nexus delivery and SonarQube stages fail.

### Agent tools

- Java JDK (for Gradle/Maven builds)
- Gradle or Maven wrapper (or system-installed)
- Flutter SDK (if building Flutter applications)
- `curl`, `python3`, `ssh`, `scp` (Linux agents)
- PowerShell (Windows agents)

### Script security

The library runs inside the Jenkins script sandbox and uses no construct that requires an administrator to approve a signature. Collections are built from literals, JSON is read and written with the `readJSON` and `writeJSON` steps, and regular expressions use the Groovy operators. Nothing has to be approved under *Manage Jenkins, In-process Script Approval* to onboard a new project.

Every class of the library is loaded and every pipeline is executed under the real script-security sandbox by the test suite before a release, so a rejected signature is found in the test run and never in your build. Section 17 lists the rules this imposes on the library code.

### Jenkins global configuration

1. **SonarQube server** must be registered in *Manage Jenkins → Configure System → SonarQube servers* with the name `SonarQube`.
2. **Nexus IQ server** must be registered in *Manage Jenkins → Configure System → Sonatype Nexus Platform*.
3. The following credentials must exist in Jenkins Credentials Store:

| Credential ID | Type | Used for |
|---------------|------|---------|
| `nexusiqP` | Username/Password | Nexus IQ authentication (also used by GoldenFix to read reports and remediation versions) |
| `scm.bitbucket.credentialsId` | Username/Password (or Secret text with `authType: bearer`) | GoldenFix: push of branch `GoldenFix-YYYYMMDDHHMM` and pull request creation (repository write permission) |
| `tests.*.jobs[].credentialsId` (optional) | Username/Password (user + API token) | Triggering remote test jobs referenced by full URL |
| `openshift-rd-token` | Secret text | OpenShift RD cluster token |
| `openshift-qc-token` | Secret text | OpenShift QC cluster token |

---

## 5. Step 1 – Register the library in Jenkins

1. Open Jenkins → **Manage Jenkins** → **Configure System**.
2. Scroll to **Global Pipeline Libraries** → click **Add**.
3. Fill in:

| Field | Value |
|-------|-------|
| Name | `DevSecOpsJenkinsLibrary` |
| Default version | `main` (or your release branch) |
| Load implicitly | unchecked |
| Allow default version override | unchecked |
| Retrieval method | **Modern SCM** |
| Source Code Management | **Git** |
| Project Repository | URL to this repository |
| Credentials | credentials to the library repo (if private) |

4. Click **Save**.

That is all you need to do in Jenkins for the library. You do not need to configure anything else globally.

---

## 6. Step 2 – Add two files to your project repository

Your project repository needs exactly **two files**:

### File 1: `Jenkinsfile` (at repository root)

The simplest possible Jenkinsfile:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```

Replace `my-app` with the key name(s) you will use in `config.yaml`, and `agentNames` with the Jenkins agent labels your builds may run on. Both entries are required: `agentNames` fills the `AGENT_NAME` build parameter.

For multiple projects (e.g. a monorepo with GUI and API):

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent', 'windows-agent']
)
```

### File 2: `config.yaml` (at repository root)

This file contains ONLY the `projects:` section. You do not need to specify `defaults:` — the library provides non-overridable defaults automatically.

Minimal example (single project, VM deployment):

```yaml
projects:

  my-app:
    appId:     "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    buildTool: gradle
    javaPath:  /usr/lib/jvm/java-17-openjdk     # JDK for the build and the tests

    asoc:
      keyId:     "bbh_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      keySecret: "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

    tools:
      sonar:
        projectName: "My Application"
        projectKey:  "my-application"
        serverUrl:   "https://tools.bbh.com/sonar"
        badgeToken:  "sqb_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      nexusIq:
        application:        "My-Application"
        serverUrl:          "https://tools.bbh.com/IQ"
        credentialsId:      "nexusiqP"
        scanPatterns:       ["**/build/libs/*.jar"]
        stage:              "build"
        failOnNetworkError: false

    dast:
      enabled:   true
      targetUrl: "http://my-rd-server.example.com"

    tests:
      smoke:
        jobs:
          - name:       "My App - smoke"
            type:       local
            job:        "my-app/smoke-tests"
            timeoutMin: 15
      regression:
        jobs:
          - name:       "My App - regression"
            type:       local
            job:        "my-app/regression-tests"
            timeoutMin: 60
      performance:
        jobs:
          - name:       "My App - performance"
            type:       local
            job:        "my-app/performance-tests"
            timeoutMin: 120

    deploy:
      vm:
        dod:                                   # lower test region of devSecOpsPipeline (UrbanCode Deploy)
          siteName:      "deploy.bbh.com"
          deployProcess: "tomcat-app-process"
          applications:
            - applicationName: "My-App"
              components:
                - componentName:       "My-App-app"
                  baseDir:             "build/libs"
                  fileIncludePatterns: "*.jar"
        rd:                                    # lower test region of devSecOpsExtendedPipeline (SSH)
          host:         "rdserver.example.com"
          user:         "deploy"
          deployDir:    "/opt/app/deployment"
          deployScript: "scripts/deployment/deploy.sh"
          versionFile:  "scripts/deployment/version.properties"
        qc:
          host:         "qcserver.example.com"
          user:         "deploy"
          deployDir:    "/opt/app/deployment"
          deployScript: "scripts/deployment/deploy.sh"
          versionFile:  "scripts/deployment/version.properties"
```

> **Important:** `asoc.keyId` and `asoc.keySecret` are required in every project. These are credentials for HCL AppScan on Cloud (ASoC). Obtain them from the ASoC portal under your organization's API key settings.

---

## 7. Step 3 – Configure the Jenkins job

1. Create a new **Pipeline** job in Jenkins.
2. In **Pipeline** section, select **Pipeline script from SCM**.
3. Set **SCM** to **Git** and enter your project repository URL.
4. Set **Branch Specifier** to your default branch (e.g. `*/main`).
5. Set **Script Path** to `Jenkinsfile`.
6. Click **Save**.

No environment variables need to be configured in the job definition. The library reads everything from `config.yaml`.

### Optional: expose parameters in the job

The entry points declare their build parameters, so they appear in the Jenkins UI after the first run:

| Parameter | Declared by | Default | Description |
|-----------|-------------|---------|-------------|
| `AGENT_NAME` | every entry point | first entry of `agentNames` | The agent label the build runs on, taken from `agentNames` of the Jenkinsfile call. |
| `DEPLOY_HIGHER_ENV` | `devSecOpsPipeline`, `devSecOpsExtendedPipeline` | `false` | When `true`, the pipeline deploys to the QC environment, provided every earlier stage is green. |
| `RUN_EXTENDED_PIPELINE` | `devSecOpsSecurityPipeline` | `false` | When `true`, the static pipeline starts the job named in `jenkins.pipeline.extendedPipeline` after the report is published. |

A policy violation never fails the pipeline. The stage turns orange, the build is marked UNSTABLE and keeps running, and the Nexus release plus the QC deployment stay blocked until the findings are fixed. There is no override parameter and no project threshold.

These appear in the Jenkins UI automatically after the first successful run.

---

## 8. Step 4 – First run

1. Open your pipeline job in Jenkins.
2. Click **Build Now** (or **Build with Parameters** if parameters have already been discovered).
3. Watch the **Stage View** – each stage turns green on success or red on failure.
4. After the build finishes, click **Pipeline Report** in the left sidebar to view the HTML security report.

### What to expect on first run

- **Stage 1** reads your `config.yaml` after the declarative checkout. If the file is missing or malformed, the stage fails with a clear error message.
- **Stage 2 (Unit tests)** turns orange instead of red when the tests fail or the coverage is below the required value, and the build carries on.
- **Stage 4 (SAST)** compiles the project, uploads it to ASoC and waits for the results. This typically takes 20–50 minutes.
- **Stage 11 (DAST)** is skipped for projects where `dast.enabled: false`.
- **Stage 12** is skipped when any earlier stage is orange.
- **Stage 13** only runs when `DEPLOY_HIGHER_ENV=true` is selected at build time and every earlier stage is green.
- Orange stages do not stop the run: every stage from unit tests to DAST is executed on every build.

---

## 9. config.yaml reference

The `config.yaml` in your project must contain only a `projects:` top-level key. Below is the full reference with all available fields and their defaults (coming from the library):

```yaml
projects:

  <project-name>:                    # arbitrary key, must match projectNames in Jenkinsfile

    appId:        ""                 # HCL AppScan application ID (UUID)
    buildTool:    gradle             # gradle | maven | flutter  [default: gradle]
    deployTarget: vm                 # vm | openshift            [default: vm]
    sourceDir:    "."                # source root for IRX generation [default: .]
    javaPath:     ""                 # JDK used for the build and the tests (exported as JAVA_HOME)
    buildToolAutoSetup: false        # true: detect the required Java version from pom.xml / build.gradle instead
    includedDirs: ""                 # optional: comma separated folders to scan (SAST)
    excludedDirs: ""                 # optional: comma separated folders to skip (SAST)

    jenkins:
      pipeline:
        extendedPipeline: ""         # job started by devSecOpsSecurityPipeline when RUN_EXTENDED_PIPELINE is selected

    asoc:                            # HCL AppScan on Cloud (required)
      url:         "https://bbh.cloud.appscan.com"
      keyId:       ""
      keySecret:   ""
      token:       ""                # optional: Jenkins Secret text credential holding the key secret
      insecureTls: false             # true adds curl -k to the ASoC REST calls (TLS verification off) - keep false
      doCompile:   true              # compile the project before the IRX is generated
      sourceCodeOnly: false          # true: -sco, no compiled classes in the IRX
      useAppScanConfig: false        # true: use appscan-config.xml from the repository
      gradle:                        # optional compile command for the IRX; the build section is used when absent
        tasks: ['classes', 'testClasses']
      maven:
        goals: ['clean', 'compile']

    influx:                          # InfluxDB DORA metrics (optional)
      enabled:   false
      project:   ""                  # measurement tag [default: tools.sonar.projectName]
      env:       "dev"               # measurement tag
      url:       ""                  # InfluxDB v2 write endpoint
      token:     ""                  # InfluxDB auth token
      credentialsId: ""              # alternatively: Jenkins credential ID for the token

    coverage:
      reportPath: ""                 # custom JaCoCo XML path (optional, auto-detected if empty)
                                     # the required 60% comes from the library defaults and cannot be set here

    tools:
      sonar:
        projectName:      ""         # display name in SonarQube
        projectKey:       ""         # unique project key in SonarQube
        serverUrl:        "https://tools.bbh.com/sonar"
        installationName: "SonarQube"  # SonarQube installation registered in Jenkins
        credentialsId:    ""         # Jenkins credential with the SonarQube token
        authToken:        ""         # alternative: credential ID used for the REST queries of the report
        badgeToken:       ""         # token for the SonarQube badges in the HTML report
        addBadges:        false      # add the badges to the build description
        fullBadges:       false      # all badges instead of the quality gate badge only
      nexusIq:
        application:        ""       # application public ID in Nexus IQ
        serverUrl:          "https://tools.bbh.com/IQ"
        credentialsId:      "nexusiqP"
        scanPatterns:       []       # list of Ant patterns, e.g. ["**/build/libs/*.jar"]
        stage:              "build"
        failOnNetworkError: false

    sast:
      scanName: ""                   # AppScan scan name (auto-generated from sonar.projectName if empty)

    sca:
      scanName: ""                   # name shown for the SonarQube findings in the report

    dast:
      enabled:    false              # set true to run DAST
      targetUrl:  ""                 # URL to scan
      presenceId: ""                 # AppScan Presence ID (for internal networks, optional)

    build:                           # how the artifact is built (stage 2)
      buildPath: ""                  # artifact path or glob, e.g. "build/libs/*.jar" or "target/*.jar"
      gradle:
        tasks:  ['clean', 'build']
        flags:  ['--refresh-dependencies']
        dir:    ""                   # optional subdirectory to build in
        env: {}                      # optional environment variables for the build
      maven:
        goals:    ['clean', 'package']
        flags:    ['-B', '-U']
        mvnPath:  ""                 # Maven installation directory; empty uses ./mvnw when present, otherwise mvn
        javaPath: ""                 # optional JDK for this command only
        env:
          MAVEN_OPTS: "-Xms512m -Xmx1g"

    delivery:                        # Maven upload of the snapshot artifact (deployTarget: vm, buildTool: maven)
      maven:
        goals: ['deploy:deploy-file']
        flags: ['-DrepositoryId=bbh-snapshots', '-Durl=https://tools.bbh.com/nexus/repository/snapshots',
                '-DgroupId=com.bbh.myapp', '-DartifactId=myapp', '-DgeneratePom=true']

    scm:
      bitbucket:                     # required to raise GoldenFix pull requests
        url:           ""            # repository link: https://<host>/projects/KEY/repos/slug, .../scm/key/slug.git or https://bitbucket.org/ws/slug
        credentialsId: ""            # Jenkins credentials: Username with password (or HTTP access token as password)
        authType:      basic         # basic | bearer (Secret text with an HTTP access token)
        type:          ""            # server | cloud  [auto-detected from url]
        targetBranch:  ""            # PR target branch  [default: branch that triggered the build]
        cloneUrl:      ""            # push URL override  [default: derived from url]
        reviewers:     []            # Bitbucket user names (server) / account UUIDs (cloud)

    goldenFix:                       # library defaults shown, project values override them
      enabled:                true
      onlyDirectDependencies: true
      minThreatLevel:         2      # 2+ medium, 4+ high, 8+ critical
      ecosystems:             [maven, npm, pypi, pub]
      goldenVersionTypes:            # Nexus IQ remediation types taken as they are (the Golden Version)
        - recommended-non-breaking-with-dependencies
        - recommended-non-breaking
      excludeDirs:            []     # [default: .git, node_modules, target, build, .gradle, dist, venv, ...]
      commitAuthorName:       "DevSecOps GoldenFix"
      commitAuthorEmail:      "devsecops-goldenfix@noreply.local"
      timeZone:               ""     # time zone of the GoldenFix-YYYYMMDDHHMM name

    tests:
      maxParallel: 20                # [library default] max jobs of one test stage running at the same time
      unitTests:                     # stage 2: the test command and where the results are
        gradle:
          tasks: ['test', 'jacocoTestReport']
        maven:
          goals: ['test', 'jacoco:report']
        unitTestResult:    ""        # JUnit results pattern [default: **/build/test-results/test/*.xml]
        rootDir:           "."       # Maven: root of the module scan for surefire reports
        reportOutDir:      "surefire-reports"   # Maven: collected surefire reports
        allowEmptyResults: false     # Maven: accept a module without test results
      smoke:
        maxParallel: 20              # optional per-stage override
        defaults: {}                 # optional: fields merged into every job entry
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
          - "https://jenkins.example.com/job/smoke/job/login"   # an entry can also be just the job URL
      regression:
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
          applications:                          # one entry per UrbanCode application
            - applicationName: ""
              order:        1                    # optional deployment order
              environments: ['DV', 'RD', 'QC']   # optional filter, empty means every environment
              snapshotName: ""                   # [default: <applicationName>_snapshot]
              components:
                - componentName:       ""
                  baseDir:             "build/libs"
                  fileIncludePatterns: "*.jar"
                  fileExcludePatterns: ""
                  versionPrefix:       ""        # [default: fileIncludePatterns]
                  version:             ""        # [default: <versionPrefix>_<BUILD_ID>]
                  incrementalVersion:  true
        rd:                          # lower test environment over SSH (devSecOpsExtendedPipeline)
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
          projectBuildR:      ""     # namespace where the image is built
          buildConfigPath:    ""     # BuildConfig template in the repository
          dockerFilePath:     ""     # Dockerfile in the repository
          buildContext:       ""     # directory prepared for the binary build
          addFile:            ""     # optional extra file copied into the build context
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
        qc:                          # the same keys; buildTag defaults to the image built in this run
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

### Which fields are required

| Field | Required | Notes |
|-------|----------|-------|
| `asoc.keyId` | YES | Pipeline fails at init without it |
| `asoc.keySecret` | YES | Pipeline fails at init without it |
| `appId` | YES | Needed for AppScan SAST and DAST |
| `tools.sonar.projectKey` | Recommended | SonarQube scan is skipped silently without it |
| `tools.nexusIq.application` | Recommended | Nexus IQ scan is skipped silently without it |
| `dast.targetUrl` | Only if `dast.enabled: true` | Pipeline fails at DAST stage without it |
| `javaPath` or `buildToolAutoSetup: true` | YES | Without one of them the Unit tests stage stops with *JAVA_HOME parameter is not specified* |
| `deploy.vm.dod` | Only if `deployTarget: vm` and you use `devSecOpsPipeline` | The lower test region deployment of the full pipeline uses UrbanCode Deploy |
| `deploy.vm.rd`, `deploy.vm.qc` | Only if `deployTarget: vm` | SSH deployment of the extended pipeline and of the QC stage |
| `deploy.openshift.*` | Only if `deployTarget: openshift` | `appName` and `artifactName` are required as well |
| `delivery.maven` | Only if `deployTarget: vm` and `buildTool: maven` | Snapshot upload of the artifact to Nexus |
| `jenkins.pipeline.extendedPipeline` | Only for `devSecOpsSecurityPipeline` | The job it starts when `RUN_EXTENDED_PIPELINE` is selected; it may be set in any project of the file |
| `scm.bitbucket.url` | Only for GoldenFix | Without it the fixes are listed in the report but no pull request is raised |
| `scm.bitbucket.credentialsId` | Only for GoldenFix | Needs push rights and permission to create pull requests |

---

## 10. Pipeline stages

### Stage 1: Monitor source changes (download sources)

Performs:
- Reads `config.yaml` (the declarative pipeline has already checked out the repository) and merges every project with the library defaults
- Applies the library policy to the state, so every later stage compares against the same thresholds
- Detects the agent operating system and makes `gradlew` executable
- Sets the tool environment: ASoC URLs, proxy host, port and user, and the AppScan working directories
- `devSecOpsSASTScanningPipeline` also runs `checkout scm` and pins `JAVA_HOME` before this

Fails if:
- `config.yaml` is missing or has no `projects:` section
- a name from `projectNames` is not a key of the `projects:` section
- `asoc.keyId` is empty

### Stage 2: Unit tests

Performs, once per project:
- Builds the artifact with the command of the `build` section
- Runs the unit tests with the command of `tests.unitTests` and collects coverage (JaCoCo for Gradle/Maven, lcov for Flutter)
- Compares the line coverage of that project with `coverage.minLine` of `resources/defaults.yaml` (60 %)

The stage never fails the pipeline. Below the required value the stage turns **orange**, the report box of that project shows the measured value next to the required one, and the reason states that the step failed and that the Nexus release and the QC deployment are blocked until the coverage is raised. A missing coverage report is treated the same way, because the required level cannot be proven. A failing test run is also caught: the stage turns orange with the exit code as the reason, the coverage of the reports that were produced is still evaluated, and Nexus IQ, SAST, SonarQube, the snapshot delivery and the RD deployment still run. The required value shown in the report is always the one from `resources/defaults.yaml`, so changing it there changes both the check and the report.

A compilation error of the artifact build is a real failure and stops the pipeline, because nothing can be scanned or deployed without an artifact.

### Stage 3: Dependencies scan (Nexus IQ)

Runs `nexusPolicyEvaluation` with your configured scan patterns and compares the critical, severe and moderate counts with the library thresholds (`tools.nexusIq.max*` of `resources/defaults.yaml`, all 0). A violation turns the stage orange with the counts in the reason, starts the GoldenFix remediation described below and blocks the release and QC, while the pipeline keeps running.

### Stage 4: SAST – HCL AppScan

Downloads and unpacks SAClientUtil (the AppScan CLI), logs in with the ASoC key pair, compiles every project and generates its IRX archive, queues all projects for the scan, and then waits for the results one by one. The HTML report of each project is downloaded and parsed, and the counts are compared with `sast.maxCritical`, `sast.maxHigh` and `sast.maxMedium` of `resources/defaults.yaml` (all 0). Above the limits the stage turns orange with the counts, and the release and QC stay blocked.

#### GoldenFix remediation (automatic pull request)

When the Nexus IQ policy is violated (critical, high or medium findings above the limits) the library:

1. Fetches the violating components of the evaluation from Nexus IQ (`/api/v2/applications/{app}/reports/{scanId}/policy`) and keeps only **direct dependencies** with a non-waived violation of at least medium threat level.
2. Asks the Component Remediation API (`/api/v2/components/remediation/application/{id}`) for every version Nexus IQ proposes and picks one of them:
   - the **Golden Version** when Nexus IQ offers it (`recommended-non-breaking-with-dependencies` or `recommended-non-breaking`, in practice Maven only, configurable in `goldenFix.goldenVersionTypes`),
   - otherwise the **nearest higher version** among the proposals, in this order: **backward compatible first** (same major version, and for a `0.x` version also the same minor), then **without known vulnerabilities** (`next-no-violations*`), then the smallest upgrade.

   The pull request table names the rule that picked each version, so a reviewer sees whether it is the Golden Version or the nearest compatible upgrade.
3. Searches the whole source tree (multi-module projects included, `node_modules`, `target`, `build`, virtualenvs skipped) and updates:
   - Maven: `pom.xml` dependencies, dependencyManagement and the `<properties>` they reference (also in parent POMs)
   - Gradle: `build.gradle`, `build.gradle.kts`, `gradle.properties`, `ext`/`val` version variables, version catalogs `*.versions.toml`
   - npm: `package.json` dependencies, devDependencies, peerDependencies, optionalDependencies (range operator preserved)
   - pip: `requirements*.txt`, `constraints*.txt`, `pyproject.toml` (PEP 621 and Poetry)
   - Flutter: `pubspec.yaml` dependencies and dev_dependencies (the `^` constraint is preserved); the Android and iOS parts of a Flutter app are covered by the Gradle and Maven manifests
4. Commits the changes in a separate git worktree (the pipeline workspace is not modified), pushes branch `GoldenFix-YYYYMMDDHHMM` and raises a Bitbucket pull request with the same name. All projects of one build share the pull request.
5. The HTML report shows the pull request link in the Nexus IQ stage, in the Security Gates table and in the **Nexus IQ GoldenFix** card together with the applied changes and the fixes that could not be applied automatically (e.g. versions managed by a BOM, ranges, hash-pinned requirements).

A version is only written when the manifest declares a plain version that is lower than the target; ranges, hash pinned requirements and versions managed by a BOM are listed under "not applied automatically" instead.

GoldenFix never changes the result of the Nexus IQ stage: the stage stays orange on the policy violation, and a GoldenFix error is only reported. Lock files (`package-lock.json`, `poetry.lock`, pinned requirements) are not regenerated. Requires `scm.bitbucket.url` and `scm.bitbucket.credentialsId` and a Linux/macOS agent with git.

### Stage 5: SCA (SonarQube)

Runs the SonarQube analysis and waits for the quality gate. It then queries the SonarQube API for the open **vulnerabilities** of the project and maps them to the report counts: Blocker and Critical become critical, Major becomes high, Minor becomes medium and Info becomes low. Both results are enforced: a failed quality gate and vulnerability counts above `sca.maxCritical`, `sca.maxHigh` and `sca.maxMedium` of `resources/defaults.yaml` (all 0) turn the stage orange with the reason and block the release and QC, without stopping the pipeline.

### Stages 6–10: Delivery and tests

Stage 6 publishes the snapshot artifact and stage 7 deploys to the lower test region. Both always run, even when an earlier stage is orange, so developers always get a build on the lower test region.

What stage 6 does depends on the project: a Maven project on a VM is uploaded to the Nexus snapshot repository with the `delivery.maven` command and the delivered version is written back into `config.yaml`; an OpenShift project has its image built in the build namespace and copied to the Nexus docker registry with `skopeo`; a Gradle project on a VM reports the build and the unit test results, and is published to Nexus by the release delivery (`./gradlew publish`). Stage 7 deploys with UrbanCode Deploy (`devSecOpsPipeline`, `deploy.vm.dod`), over SSH (`devSecOpsExtendedPipeline`, `deploy.vm.rd`) or to OpenShift (`deploy.openshift.rd`).

Then the tests run in the order regression, smoke, performance, followed by DAST. Each test stage needs at least one configured job.

Every test stage runs **all** configured jobs, even when some of them fail, and then turns orange with a summary of the failed jobs. A stage may contain any number of jobs (e.g. 200 smoke tests on different remote Jenkins instances). At most `tests.maxParallel` (default 20, per-stage override `tests.<type>.maxParallel`) jobs run at the same time. Remote jobs can be referenced by full job URL (`url:` or `job: https://...`) without a Remote Jenkins global configuration; `credentialsId` provides user + API token. The HTML report shows per-project counters (total / passed / failed / not configured), the failed jobs, a collapsible list of all jobs and a **Smoke tests** table with a build link for every job.

### Stage 11: DAST – HCL AppScan

Runs only for projects with `dast.enabled: true`. Queues a DAST scan against `dast.targetUrl`. Uses optional `dast.presenceId` for scanning internal (non-internet-accessible) environments.

The report is downloaded twice: as HTML (parsed for the vulnerability counts) and as **PDF**. Both are archived as build artifacts and linked from the pipeline report: in the DAST stage box and in the Security Gates table, which offers three entries, the HTML report, the **HCL AppScan** console page of the scan and the PDF report. Above the DAST thresholds of `resources/defaults.yaml` the stage turns orange with the critical, high and medium counts, and the release and QC stay blocked.

### Stage 12: Nexus delivery (safe artifact)

This is the second delivery to Nexus, into the release repository. It is skipped as soon as **any** earlier stage is orange, so a build with open findings, failed tests or missing coverage is never released.

### Stage 13: Higher test environment deployment

Runs only when `DEPLOY_HIGHER_ENV=true` is selected **and** every earlier stage is green.

### How a failure is reported: orange stages and the release gate

No security or test finding stops the pipeline. Every stage from the unit tests to DAST is executed on every build. A stage that breaks the library policy is marked **orange** (`WARN`), the build becomes UNSTABLE, and the reason printed in the console and shown in the report names the failed step, the counts behind it and the consequence.

| Stage | On a policy violation | Consequence |
|-------|-----------------------|-------------|
| Unit tests | Orange, with the measured and required line coverage, or with the exit code of a failed test run | Release and QC blocked |
| Dependencies scan (Nexus IQ) | Orange, with critical / high / medium counts, GoldenFix pull request raised | Release and QC blocked |
| SAST | Orange, with critical / high / medium counts | Release and QC blocked |
| SCA (SonarQube) | Orange, with the quality gate status | Release and QC blocked |
| Nexus delivery (snapshot) | Always runs | – |
| Lower test region deployment (RD) | Always runs | – |
| Regression, smoke, performance | Orange, with the failed jobs | Release and QC blocked |
| DAST | Orange, with critical / high / medium counts | Release and QC blocked |
| Nexus delivery (release) | Skipped when anything above is orange | No release artifact |
| Higher test environment deployment (QC) | Skipped unless everything is green and the checkbox is selected | No QC deployment |

The **release gate** collects the verdict. It blocks the second Nexus delivery and the QC deployment when any stage is not green, when a scanner count exceeds the library policy, or when the line coverage is below the required 60 %. The HTML report shows a **Release policy** card listing every violation, for example `gui Dependencies (Nexus IQ) critical 1 > 0` or `line coverage 41.0% below the required 60%`.

Each pipeline evaluates the results it owns and hands its verdict to the next one through the archived `release-gate.json`, so a security pipeline with an orange stage also blocks the release in the extended pipeline.

## 11. Policy thresholds

All thresholds live in `resources/defaults.yaml` inside the library. A project **cannot** change any of them in `config.yaml`: a value put there is ignored. Exceeding a threshold colours the stage orange and blocks the Nexus release and the QC deployment, and never fails the build.

| Scanner | Metric | Library policy | Overridable by a project |
|---------|--------|----------------|--------------------------|
| SAST | maxCritical / maxHigh / maxMedium | 0 | no |
| SCA (SonarQube) | maxCritical / maxHigh / maxMedium | 0 | no |
| Dependencies (Nexus IQ) | maxCritical / maxHigh / maxMedium | 0 | no |
| DAST | maxCritical / maxHigh / maxMedium | 0 | no |
| Coverage | minLine | 60 % | no |
| Test jobs | maxParallel | 20 | yes, per stage in `tests.*.maxParallel` |
| SAST IRX generation | prepareTimeoutMin | 120 min | no |
| SAST result polling | pollTimeoutMin / pollIntervalSec | 50 min / 30 s | no |
| DAST result polling | pollTimeoutMin / pollIntervalSec | 60 min / 60 s | no |

The required coverage is shown in the report exactly as configured in `resources/defaults.yaml`, so raising or lowering it there immediately changes the value the report checks against and displays.

---

## 12. Advanced: using library methods directly

### One devSecOpsApi instance per build

Jenkins keeps a library global in the binding of the script that first reads it, and every script under `vars/` owns a private binding. A `vars` script that reads `devSecOpsApi` therefore does not get the instance the Jenkinsfile holds, it gets a second one, with its own empty `PipelineState`.

That is why the stage methods take the instance as an argument instead of reading the global. When they read it instead, the build looks healthy in the log, because the stages run and report their result against the copy they created, while the entry point keeps reading its own untouched state. The visible result is a pipeline where:

- every box in the report is a grey `SKIP` and the coverage reads `not measured`, even though the stages ran,
- the Security Gates table has no counts and no report links,
- `release-gate.json` says `allowed: true` with no violations, so a build with findings above the policy is never blocked,
- `when { expression { devSecOpsApi.releaseAllowed(...) } }` lets the release and QC stages through,
- the security pipeline never triggers the extended pipeline, because the configuration it reads is empty.

The rule to follow when adding a stage or an entry point: the entry point resolves `devSecOpsApi` once and hands it to `devSecOpsSteps`, and nothing under `vars/` other than the entry points reads the global. `test/checks/vars-api.sh` fails the build when that rule is broken, and the pipeline scenarios in `test/sandbox/PipelineScenarios.groovy` resolve globals the way Jenkins does, so a second instance shows up there as a report full of grey boxes.


If the standard pipeline does not fit your project structure, you can call individual library methods from a custom Jenkinsfile. Import the library and use `devSecOpsApi`, the global variable that holds the services and every helper method. The four entry points are thin declarative skeletons built on top of it and on `devSecOpsSteps`, which carries one method per stage. `devSecOpsSteps` holds no state of its own: every one of its methods takes the `devSecOpsApi` instance to work with, so a custom pipeline calls `devSecOpsSteps.sast(devSecOpsApi)`, never `devSecOpsSteps.sast()`.

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

The tool environment variables such as `APPSCAN_SERVER_URL` or the proxy settings are set by `initialize()`, so the `environment` block above is needed only when you want different values.

### Complete method reference

| Method | Description |
|--------|-------------|
| `initialize()` | Set the tool environment variables, load config, apply the library policy, detect OS |
| `configure(String variant, Map config)` | Select the report variant (`full`, `security`, `extended`, `sast`) and keep the entry point configuration |
| `runStage(String name, Closure body)` | Run a stage body with the bookkeeping: start, result, duration, and a red stage on an exception |
| `eachProject(Closure body)` | Run the body once per configured project, switching the project context first |
| `finishPipeline(Map options)` | Report, release gate verdict, metrics, artifact archiving and workspace cleanup in one call |
| `deployTarget()` | `vm` or `openshift` for the current project |
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
| `checkCoverage()` | Compare the line coverage with the required value of `resources/defaults.yaml` |
| `depVulnScan()` | Nexus IQ scan; raises the GoldenFix pull request on a violation |
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
| `pushToNexus(String projectName)` | Snapshot delivery of a Maven or Flutter artifact with the `delivery` command |
| `vulnerabilitySummary()` | One line with the SAST and DAST findings above the policy severity |
| `runExtendedPipeline()` | Start the job of `jenkins.pipeline.extendedPipeline` when `RUN_EXTENDED_PIPELINE` is selected |
| `nexusDelivery(String env)` | Tag the image in the registry |
| `buildDockerImage(String projectName)`, `copyImageToNexus()`, `checkDeploymentRepo()` | OpenShift image build and promotion |
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

---

## 13. Supported build tools

| Tool | Build | Unit tests | Coverage | Release publish |
|------|-------|-----------|---------|---------|
| Gradle | `./gradlew` + `build.gradle.tasks` and `flags` | `./gradlew` + `tests.unitTests.gradle.tasks` | JaCoCo XML | `./gradlew publish` |
| Maven | `mvn` + `build.maven.goals` and `flags` | `mvn` + `tests.unitTests.maven.goals` | JaCoCo XML | `mvn deploy` |
| Flutter | `flutter build <platform>` | `flutter test --coverage --machine` | lcov | Nexus upload of the APK/IPA |

Every command comes from `config.yaml`; the table shows where the library reads it. The build tool is auto-detected from `buildTool` in config or by file existence (`pubspec.yaml` → Flutter, `pom.xml` → Maven, `build.gradle` → Gradle).

Gradle always runs through the `./gradlew` wrapper, which the library makes executable. Maven uses the installation of `build.maven.mvnPath` when it is set, otherwise `./mvnw` when the wrapper is committed, otherwise the `mvn` on the agent `PATH`.

The JDK comes from `javaPath` of the project, which the library exports as `JAVA_HOME` and prepends to `PATH`. With `buildToolAutoSetup: true` the library instead reads the required Java version from `pom.xml` or `build.gradle` and searches the agent for a matching JDK.

A failing unit test run does not stop the pipeline: the stage turns orange, the coverage of the reports that were produced is still checked, and the release gate blocks the Nexus release and the QC deployment.

### JaCoCo report auto-detection (Gradle)

Searched in order:
1. `build/jacoco/jacoco.xml`
2. `build/reports/jacoco/test/jacocoTestReport.xml`
3. `build/reports/jacoco/jacocoTestReport.xml`
4. `coverage.reportPath` from `config.yaml` (custom path)

### JaCoCo report auto-detection (Maven)

Searched in order:
1. `target/site/jacoco/jacoco.xml`
2. `target/jacoco/jacoco.xml`
3. `coverage.reportPath` from `config.yaml` (custom path)

---

## 14. Supported deployment targets

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
3. Waits for the build, reads the image digest and stores the image reference and the build tag `<BUILD_NUMBER>-<yyyyMMdd-HHmmss>` in `config.yaml`
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

---

## 15. Troubleshooting

### "config.yaml not found in workspace"

Your project repository must contain a `config.yaml` file at the repository root. The file must have a `projects:` section.

### "asoc.keyId and asoc.keySecret must be set in config.yaml"

Every project in `config.yaml` must have:
```yaml
asoc:
  keyId:     "bbh_..."
  keySecret: "..."
```
These credentials are obtained from the HCL AppScan on Cloud portal.

### "PROJECT_NAMES not set"

Either pass `projectNames` to `devSecOpsPipeline()`:
```groovy
devSecOpsPipeline(
    projectNames: 'my-app',
    agentNames:   ['linux-agent']
)
```
Or the library auto-detects all keys from the `projects:` section of your `config.yaml`.

### "JAVA_HOME parameter is not specified"

The project has neither `javaPath` nor `buildToolAutoSetup: true`, and the agent does not export `HAS_BUILD_TOOL_INSTALLED=true`. Add the JDK path of the agent to your project configuration:
```yaml
javaPath: /usr/lib/jvm/java-17-openjdk
```

### "Unit tests failed (script returned exit code 1)"

The test command returned a non-zero exit code. The stage is orange, not red: Nexus IQ, SAST, SonarQube, the snapshot delivery and the RD deployment still run, and only the Nexus release and the QC deployment are blocked. Open the console output of the stage for the failing tests. When Gradle stops at `jacocoTestCoverageVerification`, the coverage rule of your own build is stricter than the run allows; the library checks the coverage itself, so the task is not required in `tests.unitTests`.

### "[DOD] No UrbanCode Deploy applications are configured"

`devSecOpsPipeline` deploys VM projects to the lower test region with UrbanCode Deploy. Add `deploy.vm.dod` with a site, an application and at least one component, or use `devSecOpsSecurityPipeline` plus `devSecOpsExtendedPipeline`, which deploys over SSH with `deploy.vm.rd`.

### "The required parameter (buildTag) is not defined"

An OpenShift deployment ran without the image built by the snapshot stage. In the split setup the build tag travels in `config.yaml`, so the extended job must copy it from the static job: pass `securityPipeline: '<name of the static job>'` in the Jenkinsfile and keep `config.yaml` in the archived artifacts of the static job.

### "Scripts not permitted to use ..."

The Jenkins sandbox rejected a signature. The library is tested against the real sandbox before a release, so this points at a library bug rather than at your configuration: send the whole message, including the signature in the brackets, to the library team. Do not approve the signature in *In-process Script Approval*.

### SAST stage takes very long or times out

- Default `sast.pollTimeoutMin` is 50 minutes. This is a library default that only the library team can change.
- The IRX generation step (compile via Gradle + AppScan prepare) can take 10–20 minutes on large projects.

### "IRX not found after prepare"

- The Gradle/Maven build during IRX generation failed. Check the `prepare.log` file in `.appscan-logs/`.
- Ensure the Gradle wrapper (`gradlew`) is executable and committed to the repository.
- The library sets `chmod +x gradlew` automatically on Linux/Mac.

### SonarQube stage fails with "SonarQube server not configured"

The SonarQube server must be registered in Jenkins as `SonarQube` (exactly this name) under *Manage Jenkins → Configure System → SonarQube servers*.

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
|--------|------------------------------------|
| `PR_CREATED` / `PR_UPDATED` | The upgrade is waiting for review, with a link to the pull request |
| `NOT_CONFIGURED` | The upgrades are ready but the repository address is missing. Fill in `scm.bitbucket.url` with the repository link and `scm.bitbucket.credentialsId` with the Jenkins credentials allowed to write to it, and the pull request is raised on the next run |
| `NO_FIXES` | The supplier has published no safe version yet, so the components have to be replaced or the risk accepted |
| `NO_MANIFEST_CHANGES` | The vulnerable versions are not chosen by this project. They arrive through another library, typically managed by a parent POM or a BOM, so the upgrade belongs there |
| `SKIPPED` | Automatic upgrades are switched off for this project |
| `ERROR` | The run stopped before it could propose anything and the repository was not touched. The card names it as a fault in the pipeline run and carries the technical detail for the DevSecOps team; the `[GOLDENFIX]` lines in the console hold the full context, usually Nexus IQ or Bitbucket permissions |


### DAST PDF report is missing in the pipeline report

The stage downloads the report as HTML and as PDF and archives both. When only the HTML link is present, look for `[DAST] PDF report not found` in the console; the PDF generation in ASoC may have taken longer than the download window.

### Hundreds of smoke jobs

All jobs are executed even when some fail, and at most `tests.maxParallel` run at the same time. Use `tests.smoke.defaults` for shared settings and `tests.smoke.urls` for plain URL lists. Raise `maxParallel` only when the remote Jenkins instances have enough executors.

---

## 16. SelfService – onboard a project step by step

Everything below is done by the project team, without any request to the DevSecOps team.

### Step 1 – Check the prerequisites of your application

- The repository is reachable by Jenkins and builds with Gradle, Maven or Flutter.
- Unit tests produce a JaCoCo XML report (Gradle, Maven) or `coverage/lcov.info` (Flutter), with at least 60 % line coverage.
- The build produces a JAR or WAR under `build/libs` or `target`.
- The JDK of the agent is known, so you can put it in `javaPath` (or use `buildToolAutoSetup: true`).
- For VM deployment with `devSecOpsPipeline`: an UrbanCode Deploy application with at least one component.
- For VM deployment over SSH: a deployment script and a `version.properties` with `APP_VERSION=`, plus SSH access from the agent to the RD and QC hosts.
- For OpenShift deployment: the BuildConfig template, the Dockerfile, the deployment template and the config template in the repository, plus cluster tokens in Jenkins credentials.

### Step 2 – Register your application in the tools

| Tool | What you need | Where it goes in `config.yaml` |
|------|---------------|-------------------------------|
| HCL AppScan on Cloud | Application id (UUID) and an API key pair | `appId`, `asoc.keyId`, `asoc.keySecret` |
| SonarQube | Project key, project name, badge token | `tools.sonar.*` |
| Nexus IQ | Application public id | `tools.nexusIq.application` |
| Bitbucket | Repository URL and credentials with push and pull request rights | `scm.bitbucket.*` |
| InfluxDB (optional) | Write endpoint and token | `influx.*` |

### Step 3 – Create the test jobs

Create at least one Jenkins job for regression, one for smoke and one for performance tests. They may live on this Jenkins instance (`type: local`, `job: "folder/job-name"`) or on any other instance, referenced by full URL. A stage may hold hundreds of jobs; at most `tests.*.maxParallel` run at the same time.

### Step 4 – Add `config.yaml` to your repository root

Copy `config.yaml.template` from this library, keep only the `projects:` section and fill in your values. Do not add thresholds or a coverage minimum: they come from the library. A complete two project example with SAST, DAST, SonarQube, Nexus IQ, GoldenFix, smoke, regression, performance and both deployment targets is in `examples/CertScanner/config.yaml`.

### Step 5 – Add the `Jenkinsfile` to your repository root

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent', 'windows-agent']
)
```

Use `devSecOpsSecurityPipeline` plus `devSecOpsExtendedPipeline` instead when the static part should run on every commit and the rest in a separate job. The extended job takes `securityPipeline: '<name of the static job>'` and copies `config.yaml` and `release-gate.json` from it. The static job starts the extended one when the `RUN_EXTENDED_PIPELINE` parameter is selected, using the job name from `jenkins.pipeline.extendedPipeline` of `config.yaml`. A complete example of both is in `examples/README.md`.

### Step 6 – Create the Jenkins job

A Pipeline job with **Pipeline script from SCM**, your Git repository, your branch and the script path `Jenkinsfile`. Nothing else has to be configured.

### Step 7 – Run the pipeline

Click **Build Now**. Select `DEPLOY_HIGHER_ENV` only when you want the QC deployment; it happens only when every stage is green.

### Step 8 – Read the report

The severity counts shown for SAST and DAST are the ones printed in the AppScan HTML and PDF report: the pipeline reads the **Summary of security issues** table of that report, and only counts the individual issue blocks when a report carries no summary. The counts are verified once more while the report is written, against the report file archived with the build. When the archived report disagrees with what was recorded during the scan, the stage box, the Security Gates table and the release gate all follow the archived report, and the console carries a `[REPORT]` line naming both numbers.


Open **Pipeline Report** in the build sidebar:

- the stage flow with one box per stage and per project, green when the stage passed and orange when it broke the policy,
- the **Reason** line of each orange box, with the counts and the consequence,
- the **Security Gates** table with the findings of SAST, DAST, Nexus IQ and SonarQube, and links to the reports, to the HCL AppScan console and to the PDF,
- the **Release policy** card telling you whether the artifact was released to Nexus and whether QC is allowed,
- the **Nexus IQ GoldenFix** card with the pull request that upgrades the vulnerable dependencies,
- the **Smoke tests** table with one row per job.

### Step 9 – Fix what is orange

Merge the GoldenFix pull request, fix the SAST, SonarQube or DAST findings, raise the coverage or repair the failing test jobs. The next green build is released to Nexus and may be deployed to QC.

### Onboarding checklist

- [ ] `Jenkinsfile` and `config.yaml` committed at the repository root
- [ ] `appId`, `asoc.keyId` and `asoc.keySecret` filled in for every project
- [ ] `javaPath` (or `buildToolAutoSetup: true`) set for every project
- [ ] SonarQube project key and Nexus IQ application configured
- [ ] At least one job configured for regression, smoke and performance
- [ ] `scm.bitbucket.url` and `credentialsId` set, so GoldenFix can raise pull requests
- [ ] Deployment target configured: `deploy.vm.dod` for `devSecOpsPipeline`, `deploy.vm.rd` and `qc` for SSH, or `deploy.openshift.*`
- [ ] Unit tests reach 60 % line coverage
- [ ] First build green, report opened, release policy card checked

---

## 17. Library development and tests

This section is for the team that changes the library, not for the projects that use it.

### One command

```bash
bash test/run-all.sh
```

The script downloads the tools it needs once (JDK 21, Groovy 2.4.21, the Jenkins 2.479.3 libraries and the `script-security` plugin) into `${DEVSECOPS_TOOLS:-$HOME/.cache/devsecops-library-tools}`, then runs everything below and regenerates the demo reports in `examples/reports`. It prints `ALL CHECKS PASSED` when the library is releasable.

### What it runs

| Step | What it proves |
|------|----------------|
| `test/checks/sandbox-lint.sh` | No construct that needs an administrator approval, no static field, no `map."${key}"` |
| `test/checks/undefined-calls.sh` | Every implicit call resolves to a declared method |
| `test/checks/undefined-variables.groovy` | Every name in a library class is a local, a parameter or a field |
| `test/checks/api-consistency.sh` | Every call on a library type matches a method with that argument count |
| `test/checks/closure-calls.groovy` | No unqualified call inside a closure of a static method |
| `test/checks/closure-field-writes.groovy` | No closure assigns a field of its own class |
| `test/checks/jdk-whitelist.groovy` | Every JDK constructor, static method and static field is on the script-security whitelist |
| `test/checks/architecture.sh` | Adapters depend on the core and the ports, never the other way round |
| `test/checks/vars-api.sh` | The shared methods resolve, the full pipeline equals security plus extended, and every manifest updater is wired into the GoldenFix service |
| `test/checks/no-comments.sh` | No comments and no commented-out code in `src` and `vars` |
| compilation | `src` and `vars` compile with the sandbox compiler configuration |
| `test/sandbox/SandboxScenarios.groovy` | Every class initialises under the whitelist used while the CPS program is saved; the AppScan commands match the recorded baseline; the GoldenFix version choice follows the Golden Version and nearest compatible rules; GoldenFix patches a real `pom.xml`, `build.gradle`, `gradle.properties`, `package.json`, `requirements.txt` and `pubspec.yaml`; 200 smoke jobs run with the configured parallelism; six service level pipelines produce the demo reports |
| `test/sandbox/PipelineScenarios.groovy` | The four entry points run end to end under the real sandbox, green and orange, including the report, the release gate, the metrics and the deployments |

The sandbox tests execute the library inside `GroovySandbox.runInSandbox` with the real `script-security` whitelists and a fake Jenkins (`test/sandbox/harness`), so a rejected signature, an invalid closure or a missing method fails the test run instead of a customer build.

### Rules the sandbox imposes on the library code

| Rule | Why |
|------|-----|
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
