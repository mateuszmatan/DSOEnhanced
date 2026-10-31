# Changelog

All notable changes to the DSOEnhanced Jenkins Shared Library are documented in this file.

## [Unreleased]

### Added
- **Liquibase (SQL / PL/SQL) Build Tool Support (Single-Module & Multi-Module / Polyglot)**:
  - Full first-class support for database migration and PL/SQL / T-SQL / SQL services via `buildTool: 'liquibase'`.
  - Auto-detection of Liquibase services in `BuildService.detectTool()` through `liquibase.properties`, `changelog.xml`, `changelog.yaml`, `changelog.yml`, `changelog.sql`, `db/changelog/db.changelog-master.xml`, or `db/changelog/db.changelog-master.yaml`.
  - Cross-platform command execution (`BuildService.runLiquibaseCommand`) supporting both Linux/macOS (`sh`) and Windows (`bat`), `dir` working directory overrides, and custom `env` variables.
  - Configurable build commands (`build.liquibase.commands`, default: `validate`) and arguments/flags (`build.liquibase.flags`, e.g. `--changelog-file=...`).
  - Unit tests / changelog validation stage (`tests.unitTests.liquibase.commands` and `flags`), skipping gracefully when no validation commands are configured.
  - Automatic code coverage bypass: database changelog projects without test coverage reports bypass the 60% coverage gate in `BuildService.checkCodeCoverage()` without failing or marking the stage unstable.
  - SAST AppScan support: Java bytecode compilation (`doCompile`) is skipped by default for Liquibase services, while optional pre-IRX Liquibase commands (`asoc.liquibase.commands` / `tasks` and `flags`) are supported and tracked by `BuildUtils.hasCustomSastCompile()` (`sastCompileOverrides`).
  - SonarQube analysis for SQL / PL/SQL (`SonarService.scanLiquibase`): invokes `sonar-scanner` directly with `-Dsonar.sources` (defaulting to `tools.sonar.liquibase.dir`, `build.liquibase.dir`, or `sourceDir`), custom `tools.sonar.liquibase.flags` (e.g. `-Dsonar.plsql.file.suffixes=.sql,.pks,.pkb`), `dir`, and `env` without invoking Java build runners.
  - Optional JDK configuration: `javaPath` is exported as `JAVA_HOME` when specified, while omitting `javaPath` and `buildToolAutoSetup` does not halt Liquibase services.
  - Seamless multi-module / polyglot support: Liquibase SQL / PL/SQL services can run in the same pipeline alongside Gradle, Maven, Java, Kotlin, Node.js / Angular (`build.node`), Python / pip, and Flutter modules.
- **Multi-Report Code Coverage (`CoverageReportScanner`)**:
  - Automatic discovery of all JaCoCo XML (`jacoco.xml`, `jacocoTestReport.xml`, Android `reports/coverage`, Kover `reports/kover`) and LCOV (`lcov.info`, `*.lcov`) reports across all workspace subdirectories (`build/`, `target/`, `release/`, and module folders).
  - Duplicate and aggregate report detection (`report-aggregate`, `testCodeCoverageReport`), counting uncovered module classes from aggregate reports when needed.
  - Per-report breakdown table in the HTML pipeline report and equal-weighted service mean calculation for multi-module and full-stack (Java + Node.js/Angular) services.
  - Fallback report resolution for Windows agents and environments without `python3`.
- **Modern Configuration Discovery (`devsecops_config.yaml`)**:
  - Native loading of `devsecops_config.yaml` as the primary repository configuration file with automatic fallback to `config.yaml` when running without a portal key.
- **Parallel Static Analysis (`staticAnalysis.parallel`)**:
  - Support for `staticAnalysis.parallel` in global defaults / service config and the `DSO_STATIC_ANALYSIS_PARALLEL` environment variable override.
  - Queues HCL AppScan SAST scans for all services first, removes staging directories (`appscan-source-staged`), runs Nexus IQ (with GoldenFix) and SonarQube while AppScan analyzes in the cloud, and polls SAST results last.
  - Automatic fallback to sequential execution when a service on Linux/macOS defines custom `asoc.gradle`, `asoc.maven`, or `asoc.liquibase` compile commands that differ from its `build` commands.
- **Node.js Pre-Build (Stage 0, `NodeBuildService`)**:
  - Optional Stage 0 (`build.node.enabled: true`) for frontend builds (`npm ci`, `npm run build`) before backend Gradle or Maven compilation, SAST compilation, Nexus IQ GoldenFix builds, and Extended pipeline release delivery.
  - Supports `build.node.dir`, `commands`, `installationName`, `nodePath`, `registry`, `npmrcCredentialsId`, `env`, `timeoutMin`, and automatic addition of `<dir>/package-lock.json` to Nexus IQ scan patterns (`nexusIqScan: true`).
  - Automatically excludes `node_modules` from AppScan SAST IRX archives after a Node.js build.
- **Nexus IQ GoldenFix Shared Version Catalogs (`goldenFix.versionCatalogs`)**:
  - Automatic discovery and updating of Gradle version catalogs (`*.versions.toml`, `settings.gradle(.kts)` `versionCatalogs` blocks, `apply from:` scripts, `java-platform` modules) and Maven parent/imported BOM POMs, plus explicit paths/globs in `goldenFix.versionCatalogs`.
  - Single shared branch and pull request (`PR_CREATED` / `PR_UPDATED`) across all services in a multi-module repository, preserving highest target versions across modules.
  - Multi-ecosystem pre-check verification across Maven, Gradle, npm (with automatic `package-lock.json` refresh), pip (`requirements*.txt`, `pyproject.toml`), and Flutter/Dart (`pubspec.yaml`).
- **Service Configuration & Deployment Enhancements**:
  - Support for `deploy.vm.rd.nexusDeliveryEnabled: false` and `deploy.openshift.rd.nexusDeliveryEnabled: false` to disable Stage 6 Nexus snapshot delivery and deploy pinned OpenShift `buildTag` images.
  - Custom AppScan SAST scan naming via `sast.scanName`.
  - Support for `tools.sonar.badgeCredentialsId` (Jenkins Secret text credential) as a secure alternative to plain-text `tools.sonar.badgeToken`.
- **Standalone Library Distribution (`DSOEnhancedWithoutPortal` branch)**:
  - Maintained in parity on `DSOEnhancedWithoutPortal` as a standalone "Version 0" shared library with zero portal integration, operating purely via `Jenkinsfile` (`projectNames`) and `devsecops_config.yaml` / `config.yaml`.
