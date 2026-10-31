# Changelog

All notable changes to the DSOEnhanced Jenkins Shared Library are documented in this file.

## [Unreleased]

### Added
- **Liquibase (SQL / PL/SQL) Build Tool Support**:
  - Full first-class support for database migration services via `buildTool: 'liquibase'`.
  - Auto-detection of Liquibase services through `liquibase.properties`, `changelog.xml`, `changelog.yaml`, or `changelog.sql`.
  - Configurable build commands (`build.liquibase.commands`, default: `updateSQL`) and arguments/flags (`build.liquibase.flags`, e.g. `--changelog-file=...`).
  - Unit tests / changelog validation stage (`tests.unitTests.liquibase.commands`, default: `validate`), skipping gracefully when no validation commands are configured.
  - Automatic code coverage bypass: database changelog projects without test coverage reports bypass the coverage gate without failing the pipeline.
  - SAST AppScan optimization: bytecode compilation runner (`doCompile`) is skipped for Liquibase services since SQL/PL-SQL does not require Java compilation.
  - SonarQube analysis for PL/SQL: invokes `sonar-scanner` directly with `-Dsonar.sources` and configured Sonar properties without invoking Java build runners.
  - Flexible Java configuration: `javaPath` is exported as `JAVA_HOME` if specified, but missing JDK does not halt Liquibase services.
- **Multi-Report Code Coverage**:
  - JaCoCo XML and LCOV report discovery across all workspace subdirectories (including `build/`, `target/`, and `release/`).
  - Per-report breakdown and combined line coverage calculation for multi-module repositories.
  - Fallback report resolution for Windows agents and environments without python3.
- **Modern Configuration Discovery (`devsecops_config.yaml`)**:
  - Native loading of `devsecops_config.yaml` as the standard configuration file with seamless fallback to `config.yaml`.
- **Parallel Static Analysis**:
  - Support for `staticAnalysis.parallel` and `DSO_STATIC_ANALYSIS_PARALLEL` environment variable.
  - Enables running Nexus IQ and SonarQube analysis concurrently while HCL AppScan SAST runs in the background.
- **Node.js Pre-Build (Stage 0)**:
  - Optional Stage 0 for frontend builds (`npm ci`, `npm run build`) before backend Gradle or Maven compilation.
  - Configurable Node.js installations, custom agent paths, `.npmrc` credentials, and timeout limits.
- **Nexus IQ GoldenFix Version Catalogs**:
  - Full support for Gradle version catalogs (`libs.versions.toml`) and shared repository catalogs during automated remediation PRs.
  - Multi-ecosystem remediation across Maven, npm, PyPI, and Pub.
- **Standalone Library Distribution (`DSOEnhancedWithoutPortal`)**:
  - Zero-portal standalone branch operating purely via `Jenkinsfile` and `devsecops_config.yaml` without portal dependencies or pipeline keys.
