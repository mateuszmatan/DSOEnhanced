# DevSecOps: to-do list

Open work for the DSOEnhanced library (branches `DSOEnhancedWithoutPortal` and `main`) and the DevSecOps Management Portal, after the review of the three sources on 2026-11-20. The same file is kept in all three sources. What was delivered is in `roadmap.md` and the changelogs; the overview for senior management is `docs/MANAGEMENT_OVERVIEW.md`.

Rules for every item: change `DSOEnhancedWithoutPortal` first, then `main` and the portal, so that the three stay consistent; keep `config.yaml`, `devsecops_config.yaml` and the `Jenkinsfile` backward compatible; a setting that is given wins, a wrong one falls back to the default with a log line, and the build fails only when the default fails as well.

## Priority 1: before the first production use

| # | Item | Where | Why |
|---|------|-------|-----|
| 1.1 | **Pilot on real BBH infrastructure.** Run the security, extended and full pipelines of two or three pilot projects (Java, database, .NET or Angular) against the real systems and correct what they find. Points to confirm: Liquibase and utPLSQL against Oracle for the four database layouts, among them `buildTool: sql` with its generated `dso-changelog.xml`; the Gradle and Maven plugin repositories of the BBH Nexus (`pluginRepositories`, the URLs are assumed defaults); the SonarQube PL/SQL analyzer; AppScan on Cloud SAST and DAST with the key of the functional user; the OpenShift deployment and the deployment repository check; the UrbanCode Deploy (DOD) deployment to the lower test region; the Nexus delivery upload and download (`delivery.push`, `delivery.pull`); a Windows agent with a .NET service. | Library, both branches | Every check of the suites runs against simulated BBH systems and the real Liquibase command line only. |
| 1.2 | **Portal sign-in.** BBH single sign-on, roles and an audit trail. | Portal | Until then every action is recorded as the default administrator and the portal must stay inside the test network. |
| 1.3 | **Portal build tools npm and pip.** A service that is only Angular or Node.js, or only Python, cannot be chosen in the service form, and an import of `buildTool: npm` or `pip` is noted as not imported, so the service takes the default build tool. The library builds, tests and scans both from `devsecops_config.yaml` or `config.yaml`. Needed: the enum values, the database constraint, the commands of the build and the unit tests, the import and the rendering, the detection result in the form. | Portal | Part of the BBH projects is Python or Angular only. |
| 1.4 | **Release delivery of the safe artifact (stage 12)** for Gradle, Maven, npm, pip and Liquibase. Today only the NuGet package of a .NET service is pushed; for the other build tools the snapshot of stage 6 stays the delivered artifact. | Library, both branches | The release must hold the artifact that passed the gate. |
| 1.5 | **Continuous integration of the three sources.** A Jenkins job that runs `test/run-all.sh` of each library branch and the backend and front-end tests of the portal on every push. | All three | The suites are run by hand today. |

## Priority 2: consistency of the three sources

| # | Item | Where | Why |
|---|------|-------|-----|
| 2.1 | **Pull request decoration.** `main` has the limit `pullRequestDecoration.maxComments`, the check for comments that already exist, and the decoration for every service of the run. Bring `DSOEnhancedWithoutPortal` to the same behaviour. | Library, without portal | The two branches differ in a feature that does not depend on the portal. |
| 2.2 | **SonarQube runner.** `main` runs the sonar-scanner CLI unless the service has sonar commands of its build tool, and retries without the branch name when the server refuses it. `DSOEnhancedWithoutPortal` runs the Gradle or Maven plugin and the CLI for pip, npm and iOS, without the retry. Decide which one is the standard and apply it to both. | Library, both branches | Same reason. |
| 2.3 | **Shared version catalog repository.** `main` clones a catalog repository with SSH, bearer or basic credentials for GoldenFix; align `DSOEnhancedWithoutPortal`. | Library, without portal | Same reason. |
| 2.4 | **Detection in the portal.** The repository scan of the portal has no iOS, Android and npm result, returns a Python result the form ignores, and takes `gradlew` and not `settings.gradle` as a Gradle marker. Align it with the order of the library (chapters 14 and 16 of the library documentation). | Portal | The scan of the form and the pipeline must give the same build tool. |
| 2.5 | **Portal build tools Android and iOS**, and MSBuild as a choice of its own (an import of `msbuild` becomes `dotnet` today and the library detects the mode). | Portal | Completes the list of the library. |
| 2.6 | **One source for the user guides.** `docs/USER_GUIDE_PORTAL` and `docs/USER_GUIDE_WITHOUT_PORTAL` exist in the library and in the portal. The copies of the two library branches are equal; the copies of the portal have chapters 14 to 18 that the library copies lack and lack the Quick Reference Index of the library copies. Merge them and generate the copies from one source. | All three | The copies have drifted. |
| 2.7 | **Liquibase step of a Gradle or Maven service in the portal** (`build.liquibase`, `tests.unitTests.liquibase`), and the rendering of `delivery.liquibase` and `tools.sonar.sonarScannerVersion` of a Liquibase project. | Portal | Read by the import, without a field in the form. |
| 2.8 | **`security_findings` points with `scanner=niq`** carry every count as 0; the counts are only in `vulnerabilities` with `scanner=nexusiq`. The correction changes recorded golden runs. | Library, both branches | Dashboards read the wrong series. |

## Priority 3: improvements

| # | Item | Where | Why |
|---|------|-------|-----|
| 3.1 | **Static analysis at the same time on Windows agents.** They keep the order one after another. | Library, both branches | .NET services gain the same time as the others. |
| 3.2 | **Time recovered across projects.** Send the totals of the tables *Parallel stages* and *Manual actions vs DevSecOps Automated Actions* as a metric and show them per product and for BBH in Grafana and in the portal. | All three | Gives management the saving as one figure. |
| 3.3 | **Calibrate `report.manualEffort`** with measured BBH times (10 minutes per manual start and 6 x 60 minutes for a DAST are the agreed assumptions). | Library defaults, Global Settings | The saving in the report is as good as these values. |
| 3.4 | **GoldenFix beyond the repository of the service**: a catalog published as an artifact or kept in another repository, a BOM or parent from Nexus, a constant in `buildSrc`; one pull request for the services of one repository. | Library, both branches | Today such a component is named with the reason and not raised. |
| 3.5 | **Front-end formatting.** Four files of the portal front end do not pass the Prettier check. | Portal | Keeps the lint run clean. |
| 3.6 | **Dart rules in SonarQube**, and the name of a pub component in a real Nexus IQ report. | Library, both branches | Known limitations of Flutter projects. |

## Done in the review of 2026-11-20

- Detection matrix check for every supported technology in both library branches (`StaticAnalysisScenarios.groovy`).
- The corrections of the BBH pull requests 70 to 76 compared with both library branches: none is missing.
- `src/com/bbh/deploy` (OpenShift and UrbanCode Deploy) is the same file by file in both library branches.
- The stale known limitation about Nexus IQ and SonarQube one after another was corrected in `roadmap.md`.
