# Changelog

All notable changes to the DSOEnhanced Jenkins Shared Library are documented in this file.

## [Unreleased]

### Added
- **Liquibase (SQL / PL/SQL) Build Tool Support (Single-Module & Multi-Module / Polyglot)**:
  - Full first-class support for database migration and PL/SQL / T-SQL / SQL services via `buildTool: 'liquibase'`.
  - Auto-detection of Liquibase services in `BuildService.detectTool()`, after the Flutter, Maven and Gradle markers, through `liquibase.properties`, `changelog.{xml,yaml,yml,json,sql}`, `db/changelog.{xml,yaml,sql}`, `db/changelog/db.changelog-master.{xml,yaml}`, `sql/changelog.{xml,sql}`, `liquibase/changelog.xml`, `src/main/resources/db/changelog/db.changelog-master.{xml,yaml}` or `src/main/resources/changelog.xml`.
  - Cross-platform command execution (`BuildService.runLiquibaseCommand`) supporting both Linux/macOS (`sh`) and Windows (`bat`), `dir` working directory overrides, and custom `env` variables.
  - Configurable build commands (`build.liquibase.commands`, default: `validate`) and arguments/flags (`build.liquibase.flags`, e.g. `--changelog-file=...`).
  - Unit tests / changelog validation stage (`tests.unitTests.liquibase.commands` and `flags`), skipping gracefully when no validation commands are configured.
  - Automatic code coverage bypass: database changelog projects without test coverage reports bypass the coverage gate in `BuildService.checkCoverage()` without failing or marking the stage unstable. A service with a database block measures its coverage with utPLSQL instead.
  - SAST AppScan support: no Gradle or Maven compile runs for a Liquibase service; with `asoc.doCompile` on (the default) the Liquibase commands of `asoc.liquibase` (`commands` / `tasks` and `flags`, else those of `build.liquibase`) run before the IRX is prepared, and `BuildUtils.sastCompileOverrides()` names a service whose `asoc.liquibase` differs from its `build.liquibase`.
  - SonarQube analysis for SQL / PL/SQL (`SonarService.scanLiquibase`): invokes `sonar-scanner` directly, inside `tools.sonar.liquibase.dir` when it is set, with `-Dsonar.sources` (`tools.sonar.liquibase.sources` or `tools.sonar.sources`; otherwise, with a database block, the package folder; otherwise `.` inside `tools.sonar.liquibase.dir`, else `build.liquibase.dir`, `sourceDir` or `.`), custom `tools.sonar.liquibase.flags` (e.g. `-Dsonar.plsql.file.suffixes=.sql,.pks,.pkb`), `dir`, and `env` without invoking Java build runners. With a database block and `tools.sonar.liquibase.dir`, every folder and report the block adds is given from that folder, and the settings check refuses a folder that does not hold the package folder and the utPLSQL test folder.
  - Optional JDK configuration: `javaPath` is exported as `JAVA_HOME` when specified, while omitting `javaPath` and `buildToolAutoSetup` does not halt Liquibase services.
  - Seamless multi-module / polyglot support: Liquibase SQL / PL/SQL services can run in the same pipeline alongside Gradle, Maven, Java, Kotlin, Node.js / Angular (`build.node`), Python / pip, and Flutter modules.
- **Liquibase database block (`database:`)**:
  - A `buildTool: liquibase` service with `database.changelogFile` gets the database block; without it the service runs the Liquibase commands above unchanged.
  - Build artifact: the changelog is validated offline, the review SQL is written, the changelog folder and a deployment manifest are packaged into a zip that is validated offline, and the SQL and PL/SQL sources are checked in one pass.
  - Unit tests: with `database.test` the changes are deployed to a test database with the `test` context and utPLSQL runs; its Cobertura report is the coverage of the service. Without a test database tests and coverage are not required.
  - Nexus delivery of the zip as a snapshot and a release (`database.package`), and RD and QC deployments (`database.rd`, `database.qc`) of the delivered zip with Jenkins credentials, a compile check that fails only on new invalid objects, and a tag; only to the hosts of `defaults.database.allowedTargets`, never to production.
  - SAST scans the database folder without a compile, SonarQube runs when the server has a PL/SQL analyzer, the report gets a Database changes card and InfluxDB the `database_deployment` measurement. The steps of `build.liquibase`, `tests.unitTests.liquibase`, `asoc.liquibase` and `tools.sonar.liquibase` still run, and their settings win where they overlap with the block.
  - Documented in chapter 14 of `documentation.confluence`, *Integration Guide: SQL and PL/SQL with Liquibase*: both ways to configure a Liquibase service, three complete repository layouts (`test/fixtures/database/layout-a`, `-b`, `-c`), every setting, the portal steps, the deployments, the report, the metrics and the messages. `DatabaseScenarios.groovy` checks the chapter against the fixtures, the goldens, the library and the settings the DevSecOps portal writes (`test/fixtures/database/portal`).
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

### Fixed
- **SAST folders of each service**: AppScan prepares the IRX of each service from that service's own `sourceDir`, `includedDirs` and `excludedDirs`. Before, every IRX of a multi-service run used the folders of the last service, so a database folder was never scanned and an application folder could be scanned twice. Products with several services in different folders can see different SAST counts after the upgrade.
- **SonarQube folder of a Liquibase service with `tools.sonar.liquibase.dir`**: the scanner runs inside that folder, and every path the library adds is given from it: `-Dsonar.sources=.` without a database block; with a block the package folder, the utPLSQL test folder and its exclusion, and the utPLSQL coverage and test execution reports as copies whose file paths start below that folder. The settings check refuses a block whose package folder or utPLSQL test folder lies outside `tools.sonar.liquibase.dir`. Before, the scanner got `-Dsonar.sources=<dir>` inside `<dir>`, a folder that does not exist, so the analysis failed unless `tools.sonar.liquibase.flags` moved `-Dsonar.projectBaseDir`. Remove such a workaround after the upgrade, or set `tools.sonar.liquibase.sources`.
