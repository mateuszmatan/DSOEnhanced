# DevSecOps: to-do list

Open work for the DSOEnhanced library (branches `DSOEnhancedWithoutPortal` and `main`) and ITSS COE OneView, after the review of the three sources on 2026-11-20. The same file is kept in all three sources. What was delivered is in `roadmap.md` and the changelogs; the overview for senior management is `docs/MANAGEMENT_OVERVIEW.md`.

Rules for every item: change `DSOEnhancedWithoutPortal` first, then `main` and the portal, so that the three stay consistent; keep `config.yaml`, `devsecops_config.yaml` and the `Jenkinsfile` backward compatible; a setting that is given wins, a wrong one falls back to the default with a log line, and the build fails only when the default fails as well.

## Priority 1: before the first production use

| # | Item | Where | Why |
|---|------|-------|-----|
| 1.1 | **Pilot on real BBH infrastructure.** Run the security, extended and full pipelines of two or three pilot projects (Java, database, .NET or Angular) against the real systems and correct what they find. Points to confirm: the answers of the AppScan API for the pull request comments (`Issues/Scan/<scan id>` and `Issues/<issue id>/Details`: field names, the fix recommendation) and the line and file comments on the Bitbucket of BBH; Liquibase and utPLSQL against Oracle for the four database layouts, among them `buildTool: sql` with its generated `dso-changelog.xml`; the Gradle and Maven plugin repositories of the BBH Nexus (`pluginRepositories`, the URLs are assumed defaults); the SonarQube PL/SQL analyzer; AppScan on Cloud SAST and DAST with the key of the functional user; the OpenShift deployment and the deployment repository check; the UrbanCode Deploy (DOD) deployment to the lower test region; the Nexus delivery upload and download (`delivery.push`, `delivery.pull`); a Windows agent with a .NET service. | Library, both branches | Every check of the suites runs against simulated BBH systems and the real Liquibase command line only. |
| 1.2 | **Portal sign-in.** BBH single sign-on, roles and an audit trail. | Portal | Until then every action is recorded as the default administrator and the portal must stay inside the test network. |
| 1.4 | **Release delivery of the safe artifact (stage 12)** for Gradle, Maven, npm, pip and Liquibase. Today only the NuGet package of a .NET service is pushed; for the other build tools the snapshot of stage 6 stays the delivered artifact. | Library, both branches | The release must hold the artifact that passed the gate. |
| 1.5 | **Continuous integration of the three sources.** A Jenkins job that runs `test/run-all.sh` of each library branch and the backend and front-end tests of the portal on every push. | All three | The suites are run by hand today. |

## ITSS COE OneView: the three areas

OneView joins the areas DevSecOps, QA and Agile. The area switch, the pages `/qa` and `/agile` and the packages `domain.qa`, `application.qa`, `domain.agile` and `application.agile` exist; the functions below are open and need their scope agreed first.

| # | Item | Where | Why |
|---|------|-------|-----|
| O.1 | **QA metrics.** Agree the metrics (test results of the pipelines, coverage and the SonarQube quality gate, defects), their InfluxDB measurements and the Grafana dashboards; then the use cases, the InfluxDB and Grafana adapters, the REST API and the page. | Portal, area QA | The page of the area only names what is planned. |
| O.2 | **Agile maturity.** Agree the maturity model (dimensions, levels, who assesses and how often); then the tables (`AGL_`), the use cases, the REST API and the page with the trend and the comparison of business services. | Portal, area Agile | The page of the area only names what is planned. |
| O.3 | **SAMM charts in Grafana and InfluxDB.** Write the SAMM scores to InfluxDB as a time series and link their Grafana dashboard from the SAMM page. | Portal, area DevSecOps | SAMM is calculated at the moment of the request and has no history. |
| O.5 | **Area of a section under its own address** (`/devsecops/...`), once the QA and Agile areas have more than one section. | Portal | The sections of DevSecOps keep their addresses today. |

## Priority 2: consistency of the three sources

| # | Item | Where | Why |
|---|------|-------|-----|
| 2.5 | **Portal build tool MSBuild as a choice of its own.** An import of `msbuild` becomes `dotnet` today and the library detects the mode. | Portal | Completes the list of the library. |
| 2.7 | **Liquibase step of a Gradle or Maven service in the portal** (`build.liquibase`, `tests.unitTests.liquibase`), and the rendering of `delivery.liquibase` and `tools.sonar.sonarScannerVersion` of a Liquibase project. | Portal | Read by the import, without a field in the form. |
| 2.8 | **`security_findings` points with `scanner=niq`** carry every count as 0; the counts are only in `vulnerabilities` with `scanner=nexusiq`. The correction changes recorded golden runs. | Library, both branches | Dashboards read the wrong series. |
| 2.9 | **One name for the Library defaults.** The messages of the version of the library with the portal say "Global Settings of OneView" (for example for `tools.oracleJdbc.path`), while the page of OneView is labelled *Library defaults* (Admin, Library defaults) and no menu entry is called Global Settings. Agree on one name and use it in the messages of the library, in OneView and in the documents. | All three | A reader of a build log looks for a page that has another name. |
| 2.10 | **Pull request decorations and OneView.** OneView offers `POST /api/dso/bitbucket/pull-requests/decorate`, but the library never calls it: it decorates pull requests through Bitbucket itself, although `roadmap.md` (*Bitbucket PR Decoration and Shift-Left Feedback*) says that the decoration events are synchronized with OneView through it. Decide whether the library reports its pull request decorations to OneView or the endpoint is removed, and correct the roadmap. | All three | An endpoint that no build calls, and a statement in the roadmap of both library branches that does not hold. |

## Priority 3: improvements

| # | Item | Where | Why |
|---|------|-------|-----|
| 3.1 | **Static analysis at the same time on Windows agents.** They keep the order one after another. | Library, both branches | .NET services gain the same time as the others. |
| 3.2 | **Time recovered across projects.** Send the summary of the table *Manual Actions vs DevSecOps* as a metric and show them per product and for BBH in Grafana and in the portal. | All three | Gives management the saving as one figure. |
| 3.3 | **Calibrate `report.manualEffort`** with measured BBH times (10 minutes of a manual trigger for the build, Nexus IQ, SAST, SonarQube and each test, 6 hours of the CyberTrack ticket for a DAST and 0 minutes for the SRM ticket are the agreed assumptions). | Library defaults, Global Settings | The saving in the report is as good as these values. |
| 3.4 | **GoldenFix beyond the repository of the service**: a catalog published as an artifact or kept in another repository, a BOM or parent from Nexus, a constant in `buildSrc`; one pull request for the services of one repository. | Library, both branches | Today such a component is named with the reason and not raised. |
| 3.5 | **Front-end formatting.** Four files of the portal front end do not pass the Prettier check. | Portal | Keeps the lint run clean. |
| 3.6 | **Dart rules in SonarQube**, and the name of a pub component in a real Nexus IQ report. | Library, both branches | Known limitations of Flutter projects. |

## Done on 2026-11-22

- O.4: the library names the portal ITSS COE OneView (short: OneView) and its repository `ITSS-COE-OneView` in its documents, logs (`[ONEVIEW]`) and examples, in both branches. The version with the portal reads the Jenkins variables `ONEVIEW_URL` and `ONEVIEW_AGENT` and the settings block `oneview:`; the former names `DSO_PORTAL_URL`, `PORTAL_URL` of the report upload, `DSO_PORTAL_AGENT` and `portal:` are read when the new name is not set or cannot be used, with a log line that says how to rename them, so every `Jenkinsfile`, configuration file and Jenkins installation works unchanged. The guide and the examples of OneView name `ONEVIEW_URL`, the screenshots `examples/screenshots/v19` show OneView under its new name, and the shared files `TODO.md`, `docs/MANAGEMENT_OVERVIEW.md` and the two user guides are the same again in the three sources.
- A run that read its configuration from OneView with the key or keys of its `Jenkinsfile` uploads its pipeline report to OneView without a further setting (`POST /api/dso/reports`, once for each key), so every pipeline of a shared job shows the report of the run. Until now a job had to set `DSO_PIPELINE_KEY`, which still wins when it is set. An upload waits at most 60 seconds and never changes the result of the build. Version with the portal.

## Done after the review, on 2026-11-20

- 1.3 and 2.5 (Android and iOS): the portal has the build tools npm, pip, Android and iOS, in the form, the import, the rendering and the database (changeset `045-npm-pip-android-ios-build-tools`).
- 2.1: pull request decoration is the same in both library branches, with `pullRequestDecoration.maxComments`, `sastMinSeverity` and `sonarMinSeverity`, the check for comments that already exist and the decoration for every service of the run.
- 2.2: the SonarQube runner is the same in both library branches: the plugin of the build for a Gradle, Android or Maven service with analysis commands of its own, otherwise the sonar-scanner CLI, with the retry without the branch name. The flags of an npm, pip or iOS service and the plugin tasks of an Android service are kept.
- 2.3: the sign-in to the shared version catalog repository (SSH, bearer or basic) is the same in both library branches.
- 2.4: the repository scan of the portal follows the order and the markers of the library.
- 2.6: the user guides are merged; `docs/USER_GUIDE_PORTAL` and `docs/USER_GUIDE_WITHOUT_PORTAL` are the same files in the three sources, their pages are rendered by `tools/render-user-guides.sh`, and the library suite regenerates a page that does not match its Markdown file (`test/checks/user-guides.sh`).

- Pull request comments carry the fix AppScan recommends, read from the AppScan API (no report is parsed): under the line of a file the pull request changes, otherwise in a pull request comment that names the file; Nexus IQ upgrades are commented with the version Nexus IQ recommends. Both library branches; the answers of the real AppScan tenant are part of 1.1.
- The report ends with the table *Manual Actions vs DevSecOps*: one line per step in the order of the pipeline, the time gained in percent, a summary in seconds, minutes and percent and, with several services, every configured service under every step; the demo reports of `examples/reports` show it with the stages the pipelines run now.

## Done in the review of 2026-11-20

- Detection matrix check for every supported technology in both library branches (`StaticAnalysisScenarios.groovy`).
- The corrections of the BBH pull requests 70 to 76 compared with both library branches: none is missing.
- `src/com/bbh/deploy` (OpenShift and UrbanCode Deploy) is the same file by file in both library branches.
- The stale known limitation about Nexus IQ and SonarQube one after another was corrected in `roadmap.md`.
