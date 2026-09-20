# Examples

`CertScanner/` is a complete, working example of a project onboarded to the library. It shows two projects in one repository:

| Project | Build tool | Deployment | Scans configured |
|---------|-----------|------------|------------------|
| `gui` | Gradle | VM: UrbanCode Deploy in the full pipeline, SSH in the extended pipeline and for QC | SAST, DAST, SonarQube, Nexus IQ with GoldenFix |
| `backend-api` | Maven | OpenShift: image build, copy to the Nexus docker registry, rollout on RD and QC | SAST, DAST, SonarQube, Nexus IQ with GoldenFix |

Both projects configure the JDK (`javaPath`), unit tests with coverage, regression, smoke and performance jobs, including local jobs, remote jobs by full URL, a remote job through a Remote Jenkins name and a plain URL list.

## Files

| File | Purpose |
|------|---------|
| `CertScanner/Jenkinsfile` | Loads the library and calls the full pipeline |
| `CertScanner/config.yaml` | Full project configuration, the only file you adapt |

## Choosing the entry point

Every entry point takes `projectNames`, the keys of the `projects:` section of your `config.yaml`, and `agentNames`, the Jenkins agent labels offered as the `AGENT_NAME` build parameter.

The full pipeline runs all thirteen stages in one job:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent', 'windows-agent']
)
```

Split the flow into two jobs when the static part should run on every commit and the deployment and test part on demand. The first job runs unit tests, Nexus IQ, SAST, SonarQube and the snapshot delivery, and archives `config.yaml` together with the release gate verdict:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsSecurityPipeline(
    projectNames: 'gui,backend-api',
    agentNames:   ['linux-agent']
)
```

The second job deploys to RD, runs regression, smoke and performance tests and DAST, and then decides about the release and the QC deployment. It copies the archived files from the first job, so `securityPipeline` must name that job:

```groovy
@Library('DevSecOpsJenkinsLibrary') _

devSecOpsExtendedPipeline(
    projectNames:     'gui,backend-api',
    agentNames:       ['linux-agent'],
    securityPipeline: 'CertScanner-security'
)
```

The static job starts the extended one when its `RUN_EXTENDED_PIPELINE` parameter is selected. The job it starts is the one named in `jenkins.pipeline.extendedPipeline` of `config.yaml`, and it is started after the static job finishes, also when that job ended unstable. The extended job inherits the verdict of the static one through the copied `release-gate.json`, so findings from the static part still block the Nexus release and the QC deployment.

## What the thresholds are

Nothing in these files sets a security threshold or a coverage minimum. Both come from `resources/defaults.yaml` inside the library and cannot be changed by a project. A violation colours the stage orange, the pipeline keeps running, and the Nexus release plus the QC deployment stay blocked.

Step by step onboarding instructions are in the SelfService section of `DOCUMENTATION.md` and `documentation.html`.

## Demo reports

`reports/` holds one generated HTML page per pipeline and outcome, plus `index.html` as the overview. They are produced by the report template of the library from randomised data when `test/run-all.sh` runs, so they always match the current template:

| Page | Pipeline | Outcome |
|------|----------|---------|
| `security-pipeline-pass.html` | static security | every stage green |
| `security-pipeline-fail.html` | static security | orange stages, release and QC blocked |
| `sast-pipeline-pass.html` | SAST only | no findings |
| `sast-pipeline-fail.html` | SAST only | findings above the policy |
| `full-pipeline-pass.html` | all thirteen stages | released to Nexus and deployed to QC |
| `full-pipeline-fail.html` | all thirteen stages | orange stages, GoldenFix pull request, release and QC blocked |
