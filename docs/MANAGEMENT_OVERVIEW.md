# DevSecOps at BBH: overview for senior management

Status of 2026-11-20, names as of 2026-11-22. Prepared by the DevSecOps team, ITSS COE.

## 1. What it is, in one paragraph

DevSecOps at BBH is one standard way to build, test, scan, deliver and deploy software. A project adds a short configuration to its repository, or fills in a form in ITSS COE OneView, and every build then runs the same sequence of quality and security steps in Jenkins, without anyone starting them by hand. The result of every build is one report that says whether the release may go on, and why.

It consists of two products that are developed together:

| Product | What it is | Who uses it |
|---------|------------|-------------|
| **DSOEnhanced** | The Jenkins shared library that runs the pipeline. It exists in two versions that behave the same: one that reads its settings from files in the repository (*without portal*), and one that reads them from the portal (*with portal*). | Every project team, through its Jenkins jobs |
| **ITSS COE OneView** | The portal of the ITSS Center of Excellence, formerly the DevSecOps Management Portal. It joins three areas: **DevSecOps**, a web application in which the settings of products and services, and the BBH-wide defaults (Global Settings), are kept, checked and handed to the pipeline; **QA**, the quality assurance metrics of the products; and **Agile**, the agile maturity of teams and business services. DevSecOps is in use; the pages of QA and Agile name what is planned. | Project teams and the DevSecOps team |

## 2. What a build does

| Step | What happens | Tool |
|------|--------------|------|
| Build and unit tests | The code is compiled, unit tests run, code coverage is measured against the agreed minimum. | Gradle, Maven, .NET, npm, pip, Liquibase |
| Open-source check | Third-party components are checked for known vulnerabilities and license problems. Safe upgrades are proposed as a pull request (*GoldenFix*), after the library has proved that the project still builds and its tests still pass with them. | Nexus IQ |
| Static security scan (SAST) | The source code is scanned for security defects. | HCL AppScan on Cloud |
| Code quality | The quality gate of the project is evaluated. | SonarQube |
| Delivery | The build result is stored as a versioned artifact. | BBH Nexus |
| Deployment to the lower test region | The same artifact is deployed. | OpenShift, IBM DevOps Deploy (UrbanCode) |
| Regression, smoke and performance tests | The test jobs of the project are started and their results collected. | Jenkins test jobs |
| Dynamic security scan (DAST) | The running application is attacked in a controlled way. | HCL AppScan on Cloud |
| Release gate and report | All results are compared with the BBH policy. Findings above the policy stop the release. One HTML report and the metrics for the dashboards are produced. | DSOEnhanced, Grafana |

A project chooses how much of this a job runs: only the static security scan, the security pipeline (up to delivery), the extended pipeline (deployment, tests and DAST) or the full pipeline (everything).

## 3. Why it pays off

**Time.** The steps that do not depend on each other run at the same time: unit tests, Nexus IQ, SAST and SonarQube as one group, and regression, smoke and performance tests with DAST as a second group. Every report shows, for that very build, how long the groups would have taken one after another, how long they took, and the time saved.

**Manual effort.** Every report ends with the table *Manual Actions vs DevSecOps*. For every step of the run it sets the time the same work costs when people start it by hand next to the time the pipeline took, and shows the time gained in percent. With the assumptions agreed with the DevSecOps team (10 minutes of a specialist for each manual trigger of the build, Nexus IQ, SAST, SonarQube and each of the three tests, and 6 hours for the CyberTrack ticket of a DAST), the manual overhead of one full release candidate is about 7 hours 10 minutes, on top of the time the tools themselves need; because the scans and the tests run in parallel, the pipeline also needs only the time of the longest of them. The assumptions are settings, so the table can be recalibrated with measured BBH figures.

**Risk.** Every project is measured against the same policy, on every build, and the evidence is kept with the build. A release with findings above the policy is stopped by the pipeline and not by a later review.

**Cost of fixing.** Vulnerable components are upgraded by a ready pull request, limited to low-risk patch upgrades of direct dependencies that were proved to build and pass the unit tests. Developers review a change instead of researching one.

**Cost of onboarding.** The library detects the technology of a repository and fills in most settings itself. Settings that equal the detected or default value are named in the build log, so the configuration of a project stays short. Existing configurations keep working: every change is backward compatible, a wrong value falls back to the default with a warning, and the build fails only when the default does not work either.

## 4. Supported technologies

| Technology | Pipeline with files in the repository | Portal form |
|------------|----------------------------------------|-------------|
| Java, Kotlin, Groovy with Gradle or Maven | Yes | Yes |
| C# and .NET (SDK and classic MSBuild) | Yes | Yes |
| Angular and Node.js as the front end of a Gradle or Maven service | Yes | Yes |
| Angular and Node.js on their own (npm) | Yes | Yes |
| Python (pip) | Yes | Yes |
| Database projects with Liquibase | Yes | Yes |
| Database projects of SQL and PL/SQL files only | Yes | Yes |
| Flutter | Yes | Yes |
| Android and iOS | Yes | Yes |

Every technology the pipeline supports can now be configured either with the configuration file in the repository or in the portal form.

## 5. Where we are on 2026-11-20

Delivered in the last iteration:

- Database-only projects: Liquibase projects, and projects of plain SQL and PL/SQL files for which the library writes the Liquibase changelog itself. Validation, static checks, review SQL, utPLSQL tests, delivery and deployment to the lower test and QC regions.
- Automatic detection of the technology for every supported kind of project.
- Parallel static and dynamic analysis, on by default, with the time saved in every report.
- The table of manual against automated effort in every report.
- Gradle and Maven plugins taken from the BBH Nexus when a build cannot find them.
- Nexus delivery settings for upload and download (`delivery.push`, `delivery.pull`, `delivery.nexusDeliveryEnabled`).
- The corrections of the BBH pull requests 70 to 76 are present in both versions of the library.
- Portal forms for Angular and Node.js on their own (npm), Python (pip), Android and iOS, and a repository scan in the portal that recognises a project the same way the pipeline does.
- The two versions of the library now behave the same in pull request feedback, in the way SonarQube is run and in the sign-in to a shared version catalog repository.
- One shared set of user guides for the library and the portal.

Since then, on 2026-11-22: the portal is ITSS COE OneView (short: OneView), and both versions of the library name it so in their documents, logs and examples. Jenkins tells the library where OneView is with one variable, `ONEVIEW_URL`; the former variable `DSO_PORTAL_URL` and every existing `Jenkinsfile` and configuration file keep working without a change. A build whose `Jenkinsfile` holds a pipeline key now sends its report to OneView without a further setting, so OneView shows the report of the build on the page of each of its pipelines.

How it was verified: the three sources were reviewed against each other on 2026-11-20. The library is covered by an automated suite of more than 1,800 checks in the version without portal and more than 1,900 in the version with portal, which run the real pipeline code against simulated Jenkins, Nexus, AppScan, SonarQube, OpenShift and UrbanCode, and the real Liquibase command line. The portal is covered by its backend unit and regression tests and more than 850 front-end tests. All pass.

What that does not prove: nothing in this iteration was run against the real BBH systems. A pilot on real infrastructure is the next step and the first item of the to-do list.

## 6. Risks and decisions for management

| Topic | Why it matters | What is needed |
|-------|----------------|----------------|
| Pilot on real BBH infrastructure | The suite simulates the BBH systems. Real Oracle, Nexus, SonarQube, AppScan, OpenShift and UrbanCode can behave differently. | Two or three pilot projects (one Java, one database, one .NET or Angular) and access to the lower test region. |
| Portal sign-in | The portal has no sign-in yet and must stay inside the test network. | A decision to connect it to BBH single sign-on, with roles and an audit trail, before production use. |
| Ownership | The library and the portal will keep growing. | A named owner for the roadmap and for the BBH policy values (thresholds, manual effort assumptions). |

## 7. Next steps

1. **Now:** pilot on real BBH infrastructure and correction of what it finds.
2. **Next:** portal sign-in with roles; continuous integration of the three sources.
3. **Later:** release delivery of the approved artifact for every build tool; a management dashboard of the time recovered across all projects.

## 8. Where to read more

| Document | Content |
|----------|---------|
| `TODO.md` | The prioritised list of open work for the library and the portal |
| `roadmap.md` (library) | What was delivered, release by release, and the known limitations |
| `documentation.html` (library), `docs/documentation.html` (portal) | The complete technical documentation |
| `docs/USER_GUIDE_WITHOUT_PORTAL.md`, `docs/USER_GUIDE_PORTAL.md` | The guides for project teams |
