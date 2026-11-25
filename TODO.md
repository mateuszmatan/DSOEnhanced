# DevSecOps: to-do list

Open work for the DSOEnhanced library (branches `DSOEnhancedWithoutPortal` and `main`) and ITSS COE OneView, after the review of the three sources on 2026-11-20. The same file is kept in all three sources. What was delivered is in `roadmap.md` and the changelogs; the overview for senior management is `docs/MANAGEMENT_OVERVIEW.md`.

Rules for every item: change `DSOEnhancedWithoutPortal` first, then `main` and the portal, so that the three stay consistent; keep `config.yaml`, `devsecops_config.yaml` and the `Jenkinsfile` backward compatible; a setting that is given wins, a wrong one falls back to the default with a log line, and the build fails only when the default fails as well.

## Priority 1: before the first production use

| # | Item | Where | Why |
|---|------|-------|-----|
| 1.1 | **Pilot on real BBH infrastructure.** Run the security, extended and full pipelines of two or three pilot projects (Java, database, .NET or Angular) against the real systems and correct what they find. Points to confirm: the answers of the AppScan API for the pull request comments (`Issues/Scan/<scan id>` and `Issues/<issue id>/Details`: field names, the fix recommendation) and the line and file comments on the Bitbucket of BBH; Liquibase and utPLSQL against Oracle for the four database layouts, among them `buildTool: sql` with its generated `dso-changelog.xml`; the Gradle and Maven plugin repositories of the BBH Nexus (`pluginRepositories`, the URLs are assumed defaults); the SonarQube PL/SQL analyzer; AppScan on Cloud SAST and DAST with the key of the functional user; the OpenShift deployment and the deployment repository check; the UrbanCode Deploy (DOD) deployment to the lower test region; the Nexus delivery upload, download and release (`delivery.push`, `delivery.pull`, `delivery.release`, the NuGet release feed `delivery.nuget.releaseSource`); a Windows agent with a .NET service; the CI jobs of both library branches and of OneView (`ci/Jenkinsfile`) on the BBH Jenkins. | All three | Every check of the suites runs against simulated BBH systems and the real Liquibase command line only. |
| 1.2 | **Portal sign-in.** BBH single sign-on, roles and an audit trail. | Portal | Until then every action is recorded as the default administrator and the portal must stay inside the test network. |

## ITSS COE OneView: the three areas

OneView joins the areas DevSecOps, QM Metric Tool and Agile Assessment & Adoption. The Main Landing Page with a card per area, the menu on the left and the bar that chooses a business service and one of its applications exist. The QM Metric Tool keeps, for every application, whether it takes part, its QA lead, its SonarQube and defect projects, its Grafana dashboard and its quality targets under the BBH targets; Agile Assessment & Adoption keeps the team of every application and the assessments of its agile coach. The items below are open and need their scope agreed first.

| # | Item | Where | Why |
|---|------|-------|-----|
| O.1 | **QM Metric Tool: the results.** Agree the metrics (test results of the pipelines, coverage and the SonarQube quality gate, defects), their InfluxDB measurements and the Grafana dashboards; then read them for every application that takes part, from the SonarQube project, the defect project and the dashboard it already keeps, show them with their trend on its page and compare them with the quality targets it works to. | Portal, area QM Metric Tool | The page names who leads the quality of an application, where its results are and its targets, but shows no result yet. |
| O.2 | **Agile Assessment & Adoption: the BBH maturity model.** Agree the dimensions, the levels, who assesses and how often; OneView starts with the five dimensions and the levels 1 to 5 of the ITSS COE. Then the chart of the trend of a team and of a business service, and for every dimension what the next level asks for. | Portal, area Agile Assessment & Adoption | The assessments are recorded against a starting model that BBH has not agreed yet. |
| O.3 | **SAMM charts in Grafana and InfluxDB.** Write the SAMM scores to InfluxDB as a time series and link their Grafana dashboard from the SAMM page. | Portal, area DevSecOps | SAMM is calculated at the moment of the request and has no history. |
| O.5 | **Area of a section under its own address** (`/devsecops/...`), once the QM Metric Tool and Agile Assessment & Adoption areas have more than one section. | Portal | The sections of DevSecOps keep their addresses today. |

## Priority 2: consistency of the three sources

| # | Item | Where | Why |
|---|------|-------|-----|
| 2.5 | **Portal build tool MSBuild as a choice of its own.** An import of `msbuild` becomes `dotnet` today and the library detects the mode. | Portal | Completes the list of the library. |
| 2.7 | **Liquibase step of a Gradle or Maven service in the portal** (`build.liquibase`, `tests.unitTests.liquibase`), and the rendering of `delivery.liquibase` and `tools.sonar.sonarScannerVersion` of a Liquibase project. | Portal | Read by the import, without a field in the form. |
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
| 3.7 | **NuGet release in the extended pipeline.** Download the package that the security pipeline pushed to `delivery.nuget.source` and push it to `delivery.nuget.releaseSource` after the release gate, as stage 12 does for the files of a Maven, Gradle, npm or pip service. | Library, both branches; OneView help texts | Today only the full pipeline pushes a .NET package to the release feed. |

## Done on 2026-11-23

- 1.4: stage 12 releases the safe artifact. A Maven, Gradle, npm or pip service records the files of its snapshot upload of stage 6 with their SHA-256 in `nexus-delivery.json`, and stage 12 publishes the same files once more as the release of the build tag to the repository of `delivery.release`, without building again; a release that exists with the same files passes and one with other files is never replaced. npm and pip upload their package with `delivery.push.maven`, and a Liquibase service with a database block releases its zip as before (`database.package`); for these build tools nothing changes without a release repository. In the full pipeline a .NET service pushes its NuGet package to `delivery.nuget.releaseSource`; without a release feed the package that stage 6 pushed is the delivered package and is no longer pushed twice, and the extended pipeline, which builds no package, pushes none. Both library branches; OneView sets `delivery.release` in the Library defaults and for every Maven, Gradle, npm and pip service on virtual machines, and the NuGet feeds of a .NET service (changeset `049-release-delivery`).
- 1.5: `ci/Jenkinsfile` is the CI build of a multibranch job in each of the three sources, on every push and pull request and every night. In both library branches it runs `test/run-all.sh`, in OneView `./gradlew build` with the backend and front-end tests and the GUI suites; it fails when a run changes a committed file and compares the shared files of the three sources with `tools/check-shared-files.sh`.
- 2.8: the `security_findings` point with `scanner=niq` carries the Nexus IQ counts of the service, the release gate names them, and the `release_gate` point counts every violation once. Both library branches.
- The bar above the pages chooses a business service and, optionally, one of its applications; every product of the catalog is an application. With no application chosen, the Main Landing Page, Pipelines, Pipeline Monitoring, OWASP SAMM, the QM Metric Tool, Agile Assessment & Adoption, the Action history and the Admin tabs of the products and applications sum up every application of the business service, or of BBH with All business services; with one chosen, they show it alone. The choice stays from page to page and the browser remembers it.
- QM Metric Tool: an administrator sets up every application with its QA lead, its SonarQube and defect projects, its Grafana dashboard and quality targets of its own, and sets the BBH targets (code coverage, test pass rate, open critical defects) that the others work to; the page sums up the applications and shows one application with the targets it works to (tables `QA_`, changeset `047-qm-metric-tool-and-agile`).
- Agile Assessment & Adoption: an administrator sets up the team of every application (its name, way of working and agile coach) and records its assessments, a level from 1 to 5 in five dimensions; the page shows the mean level by dimension and by business service and how a team changed since its assessment before (tables `AGL_`, changeset 047).
- Action history and Admin are in the menu on the left. OneView Admin has six tabs in four groups (Catalog, DevSecOps, QM Metric Tool, Agile Assessment & Adoption), and the action history records the business service and the application of every action (changeset `048-action-log-context`).

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
