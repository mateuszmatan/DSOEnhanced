# Pull request comments: examples

What the pipeline writes into a Bitbucket pull request after the static analysis. The findings of HCL AppScan and the fix AppScan
recommends are read from the AppScan on Cloud REST API (`GET /api/v4/Issues/Scan/<scan id>` and `GET /api/v4/Issues/<issue id>/Details`);
no PDF or HTML report is parsed. The fix of a Nexus IQ finding is the version Nexus IQ recommends. The texts below are examples:
the wording of a recommended fix is whatever AppScan returns for the issue type.

The example pull request changes `src/main/java/com/bbh/certscanner/CertificateDao.java` and `pom.xml`. It does not change
`src/main/java/com/bbh/certscanner/ReportExporter.java`.

## 1. Finding in a file the pull request changes: a comment under the line

Bitbucket shows this comment in the diff of `CertificateDao.java`, under line 42.

> **[AppScan SAST] HIGH: SQL Injection**
>
> Found by HCL AppScan in src/main/java/com/bbh/certscanner/CertificateDao.java:42 (CWE-89, API java.sql.Statement.executeQuery)
>
> **Recommended fix (AppScan):**
> Use a parameterised query (PreparedStatement) and bind every value that comes from the request.
> - Do not build SQL text by concatenating input
> - Validate the input against the expected type and length

When Bitbucket does not take a comment on that line (the line is outside the lines the diff shows), the same text becomes a
comment of the file and starts with `src/main/java/com/bbh/certscanner/CertificateDao.java:42`.

## 2. Findings in a file the pull request does not change: one comment of the pull request per file

Bitbucket shows this comment in the overview of the pull request.

> **[DevSecOps] Findings in `src/main/java/com/bbh/certscanner/ReportExporter.java`** (this pull request does not change the file)
>
> ---
> `src/main/java/com/bbh/certscanner/ReportExporter.java:88`
>
> **[AppScan SAST] HIGH: Path Traversal**
>
> Found by HCL AppScan in src/main/java/com/bbh/certscanner/ReportExporter.java:88 (CWE-22, API java.io.File.<init>)
>
> **Recommended fix (AppScan):**
> Resolve the path against a fixed base folder and refuse a name that leaves it.
>
> ---
> `src/main/java/com/bbh/certscanner/ReportExporter.java:131`
>
> **[AppScan SAST] MEDIUM: Information Leakage**
>
> Found by HCL AppScan in src/main/java/com/bbh/certscanner/ReportExporter.java:131 (CWE-209)
>
> **Recommended fix (AppScan):**
> Log the exception on the server and return a general message to the caller.

## 3. Nexus IQ: the upgrade it recommends

In the pull request of the developer, under the line of `pom.xml` that declares the version (the pull request changes `pom.xml`).
When the pull request does not change the manifest, the same text is part of a comment of the pull request that names the manifest.

> **[Nexus IQ] org.yaml:snakeyaml 1.33**
>
> **Recommended fix (Nexus IQ):** upgrade to `1.34`. The GoldenFix pull request https://bitbucket.bbh.com/projects/TA/repos/cert-scanner/pull-requests/318 makes this change.

A dependency that GoldenFix leaves untouched (only the last number of a version may change) is listed in a comment of the pull request:

> **[DevSecOps] Findings without a file of this repository**
>
> ---
> **[Nexus IQ] com.h2database:h2 1.4.200**
>
> **Recommended fix (Nexus IQ):** upgrade to `2.2.224`. Not changed automatically: Nexus IQ offers only 2.2.224, which changes more than the last number of the version, so it is left for a developer.

## 4. When AppScan returns no fix for an issue type

> **[AppScan SAST] LOW: Missing Validation**
>
> Found by HCL AppScan in src/main/java/com/bbh/certscanner/CertificateDao.java:17
>
> **Recommended fix (AppScan):** AppScan returned no fix recommendation for this issue type - open the issue in AppScan for the advisory.

## 5. Summary comment

> **DevSecOps static security results - gui**
>
> - Nexus IQ GoldenFix: 1 dependency upgrade(s)
> - AppScan SAST: 3 finding(s) with the fix AppScan recommends
> - SonarQube: 2 open issue(s) on the analysed branch

## Limits

`pullRequestDecoration.maxComments` (default 25) is the number of comments one run adds to one pull request,
`pullRequestDecoration.sastMinSeverity` (default `MEDIUM`) leaves out the findings below it, and a comment that is already
on the pull request is not written again.
