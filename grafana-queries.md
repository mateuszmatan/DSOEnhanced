# Grafana queries for the DevSecOps pipeline

Every build writes one batch of InfluxDB line protocol from `post { always }`, so a run that failed is recorded exactly like a run that succeeded. This document lists what is written and the Flux to put on a dashboard.

- **Bucket:** `DORA-metrics` (the `bucket=` parameter of `influx.url` in `config.yaml`)
- **Written by:** `com.bbh.metrics.PipelineMetrics`, sent by `com.bbh.metrics.InfluxDbService`
- **Cadence:** one write per pipeline run, containing every measurement below

## Dashboard variables

Create these as *Query* variables so the panels below work unchanged.

| Variable | Type | Definition |
|----------|------|------------|
| `project` | Query | `import "influxdata/influxdb/schema"`<br>`schema.tagValues(bucket: "DORA-metrics", tag: "project")` |
| `env` | Query | `schema.tagValues(bucket: "DORA-metrics", tag: "env")` |
| `module` | Query, multi, include All | `schema.tagValues(bucket: "DORA-metrics", tag: "module")` |
| `stage` | Query, multi, include All | `schema.tagValues(bucket: "DORA-metrics", tag: "stage")` |

For the "since the beginning" panels set the panel time range to `From: now-5y` or use the explicit `range(start: 0)` shown in section 7.

---

## 1. What the pipeline writes

| Measurement | Tags | Key fields | Answers |
|-------------|------|-----------|---------|
| `pipeline_run` | project, env, variant, result, branch | build, duration_s, success, unstable, stages_total, passed, warned, failed, blocked, skipped, not_required, modules, commit, job | One row per run: outcome and shape |
| `dora` | project, env, variant | deployment, lead_time_s, change_failure, duration_s, released | The four DORA metrics |
| `stage_event` | project, env, stage, status | duration_ms, duration_s, ok, executed, order, reason | Per stage, stamped at the end of that stage |
| `security_findings` | project, env, module, scanner, status | critical, high, medium, low, total, above_policy, max_*, exceeded | Findings per scanner per module against policy |
| `policy_status` | project, env, scanner, status | ok, measured | Which gates were evaluated and which held |
| `code_coverage` | project, env, module, measured | line_pct, covered, missed, total, required, met, gap | Coverage per module against the required minimum |
| `test_execution` | project, env, module, suite | total, passed, failed, not_configured, duration_ms, success_rate | Smoke, regression and performance suites |
| `test_job` | project, env, module, suite, status, type | duration_ms, name, ok | Individual test jobs |
| `release_gate` | project, env, allowed | allowed, blocked, violations, reason | Whether the release was permitted |
| `goldenfix` | project, env, module, status | offered, applied, unresolved, pr_raised, build_check, build_failed | Automated dependency remediation |
| `deployments`, `change_failure`, `build_duration`, `stage_metric`, `vulnerabilities`, `test_coverage` | — | — | Kept unchanged so dashboards built before this revision keep working |

> `stage_event` points carry the **end time of the stage** as their timestamp, not the time of the write, so a stage timeline is accurate to the second.

---

## 2. DORA metrics

### 2.1 Deployment frequency

*Visualization: Time series (bars)*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "deployment")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: true)
  |> yield(name: "Deployments per day")
```

### 2.2 Lead time for changes

Time from the commit under test to the end of the run. *Visualization: Time series, unit `seconds (s)`; add a median reduction for a Stat panel.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "lead_time_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value > 0)
  |> aggregateWindow(every: v.windowPeriod, fn: median, createEmpty: false)
  |> yield(name: "Lead time (median)")
```

### 2.3 Change failure rate

*Visualization: Gauge, unit `percent (0-100)`, thresholds 15 / 30.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "change_failure")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> mean()
  |> map(fn: (r) => ({ r with _value: r._value * 100.0 }))
  |> yield(name: "Change failure rate %")
```

### 2.4 Time to restore service

The gap between a failing run and the next green one. *Visualization: Time series, unit `seconds (s)`.*

```flux
import "experimental"

from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "dora" and r._field == "change_failure")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> sort(columns: ["_time"])
  |> stateDuration(fn: (r) => r._value == 1, column: "broken_for", unit: 1s)
  |> filter(fn: (r) => r._value == 0 and r.broken_for >= 0)
  |> map(fn: (r) => ({ r with _value: float(v: r.broken_for) }))
  |> yield(name: "Time to restore")
```

> `stateDuration` resets on every green run, so the value on a recovery point is how long the pipeline had been failing. Reduce with `mean()` for MTTR on a Stat panel.

---

## 3. Pipeline health

### 3.1 Run outcome over time

*Visualization: State timeline, tag `result` as the value.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run" and r._field == "success")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> keep(columns: ["_time", "_value", "result", "branch"])
  |> yield(name: "Run outcome")
```

### 3.2 Build duration trend

*Visualization: Time series, unit `seconds (s)`.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run" and r._field == "duration_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: v.windowPeriod, fn: mean, createEmpty: false)
  |> yield(name: "Build duration")
```

### 3.3 Stage status breakdown of the latest run

*Visualization: Bar gauge or table.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(passed|warned|failed|blocked|skipped|not_required)$/)
  |> last()
  |> keep(columns: ["_field", "_value"])
  |> yield(name: "Stages of the last run")
```

### 3.4 Where the time goes — slowest stages

*Visualization: Bar chart, unit `seconds (s)`.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "stage_event" and r._field == "duration_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value > 0)
  |> group(columns: ["stage"])
  |> mean()
  |> group()
  |> sort(columns: ["_value"], desc: true)
  |> yield(name: "Average stage duration")
```

### 3.5 Which stage breaks most often

*Visualization: Bar chart.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "stage_event" and r._field == "ok")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value == 0)
  |> group(columns: ["stage"])
  |> count()
  |> group()
  |> sort(columns: ["_value"], desc: true)
  |> yield(name: "Not green, by stage")
```

### 3.6 Why it broke — the reasons behind the last failures

*Visualization: Table.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "stage_event" and r._field == "reason")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._value != "")
  |> keep(columns: ["_time", "stage", "status", "_value"])
  |> sort(columns: ["_time"], desc: true)
  |> limit(n: 50)
  |> yield(name: "Failure reasons")
```

---

## 4. Security posture

### 4.1 Open findings by scanner

*Visualization: Time series, stacked.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "total")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r.module =~ /^${module:regex}$/)
  |> group(columns: ["scanner"])
  |> aggregateWindow(every: v.windowPeriod, fn: last, createEmpty: false)
  |> yield(name: "Findings by scanner")
```

### 4.2 Findings above policy — the number that blocks a release

*Visualization: Time series with a threshold at 0.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "above_policy")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group(columns: ["scanner"])
  |> aggregateWindow(every: v.windowPeriod, fn: sum, createEmpty: false)
  |> yield(name: "Above policy")
```

### 4.3 Severity heatmap of the latest scan

*Visualization: Table or heatmap, group by module.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "security_findings")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(critical|high|medium|low)$/)
  |> last()
  |> pivot(rowKey: ["module", "scanner"], columnKey: ["_field"], valueColumn: "_value")
  |> yield(name: "Latest severities")
```

### 4.4 Gate health — which policies held

*Visualization: State timeline by `scanner`.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "policy_status" and r._field == "ok")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => exists r.scanner)
  |> yield(name: "Policy status")
```

### 4.5 Releases blocked by the gate

*Visualization: Stat (count) plus a table of reasons.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "release_gate" and r._field == "blocked")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> sum()
  |> yield(name: "Blocked releases")
```

---

## 5. Quality and tests

### 5.1 Coverage per module against the required minimum

*Visualization: Time series, unit `percent (0-100)`, with a threshold line at the required value.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "code_coverage" and r._field == "line_pct")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}" and r.measured == "yes")
  |> group(columns: ["module"])
  |> aggregateWindow(every: v.windowPeriod, fn: last, createEmpty: false)
  |> yield(name: "Line coverage")
```

### 5.2 How far below the bar

*Visualization: Bar gauge, unit `percent (0-100)`.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "code_coverage" and r._field == "gap")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> last()
  |> filter(fn: (r) => r._value > 0)
  |> keep(columns: ["module", "_value"])
  |> yield(name: "Coverage shortfall")
```

### 5.3 Test suite success rate

*Visualization: Time series, unit `percent (0-100)`.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "test_execution" and r._field == "success_rate")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group(columns: ["suite"])
  |> aggregateWindow(every: v.windowPeriod, fn: mean, createEmpty: false)
  |> yield(name: "Suite success rate")
```

### 5.4 The test jobs that fail most

*Visualization: Table.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "test_job" and r._field == "name")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r.status != "SUCCESS" and r.status != "ALREADY IMPLEMENTED")
  |> group(columns: ["_value", "suite"])
  |> count()
  |> group()
  |> sort(columns: ["_value"], desc: true)
  |> limit(n: 20)
  |> yield(name: "Flaky or failing jobs")
```

---

## 6. Automated remediation (GoldenFix)

### 6.1 Pull requests raised

*Visualization: Time series (bars).*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "goldenfix" and r._field == "pr_raised")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1d, fn: sum, createEmpty: true)
  |> yield(name: "GoldenFix pull requests")
```

### 6.2 Upgrades offered, applied and left to a developer

*Visualization: Time series, stacked.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "goldenfix")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(offered|applied|unresolved)$/)
  |> group(columns: ["_field"])
  |> aggregateWindow(every: v.windowPeriod, fn: sum, createEmpty: false)
  |> yield(name: "Remediation funnel")
```

### 6.3 How often the pre-check build saved a broken pull request

*Visualization: Stat.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "goldenfix" and r._field == "build_failed")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> sum()
  |> yield(name: "Upgrades withheld because they did not build")
```

---

## 7. What DevSecOps has delivered — cumulative views

These panels answer "what has this given us over successive runs, and since the beginning". Set the panel range to `now-5y`, or keep `range(start: 0)` as written to ignore the dashboard picker entirely.

### 7.1 Runs, deployments and failures since the beginning

*Visualization: Stat row, four panels sharing this query with a different `_field` filter.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "dora")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(deployment|change_failure|released)$/)
  |> group(columns: ["_field"])
  |> sum()
  |> yield(name: "Totals since day one")
```

### 7.2 Cumulative findings caught above policy

The running total of findings the gate has caught — the clearest single number for "what the pipeline prevented". *Visualization: Time series with `Fill opacity`.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "above_policy")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group()
  |> sort(columns: ["_time"])
  |> cumulativeSum(columns: ["_value"])
  |> yield(name: "Findings caught, cumulative")
```

### 7.3 Cumulative dependency upgrades merged into the codebase

```flux
from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "goldenfix" and r._field == "applied")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> group()
  |> sort(columns: ["_time"])
  |> cumulativeSum(columns: ["_value"])
  |> yield(name: "Dependency upgrades proposed, cumulative")
```

### 7.4 Coverage: where we started against where we are

*Visualization: Stat with `Text mode: value and name`.*

```flux
base = from(bucket: "DORA-metrics")
  |> range(start: 0)
  |> filter(fn: (r) => r._measurement == "code_coverage" and r._field == "line_pct")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}" and r.measured == "yes")
  |> group()

first = base |> first() |> map(fn: (r) => ({ r with _field: "first recorded" }))
now   = base |> last()  |> map(fn: (r) => ({ r with _field: "latest" }))

union(tables: [first, now]) |> yield(name: "Coverage then and now")
```

### 7.5 Trend of the quarter — is the pipeline getting faster and safer

*Visualization: Time series, two axes.*

```flux
duration = from(bucket: "DORA-metrics")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "pipeline_run" and r._field == "duration_s")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1w, fn: mean, createEmpty: false)
  |> map(fn: (r) => ({ r with _field: "build duration (s)" }))

failures = from(bucket: "DORA-metrics")
  |> range(start: -90d)
  |> filter(fn: (r) => r._measurement == "security_findings" and r._field == "above_policy")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> aggregateWindow(every: 1w, fn: sum, createEmpty: false)
  |> map(fn: (r) => ({ r with _field: "findings above policy" }))

union(tables: [duration, failures]) |> yield(name: "Quarterly trend")
```

### 7.6 Per-run comparison table — the last twenty builds side by side

*Visualization: Table, `Organize fields` to order the columns.*

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r._measurement == "pipeline_run")
  |> filter(fn: (r) => r.project == "${project}" and r.env == "${env}")
  |> filter(fn: (r) => r._field =~ /^(build|duration_s|passed|warned|failed|blocked)$/)
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> sort(columns: ["_time"], desc: true)
  |> limit(n: 20)
  |> yield(name: "Recent builds")
```

---

## 8. Suggested dashboard layout

| Row | Panels |
|-----|--------|
| **Executive** | Deployment frequency · Lead time (median) · Change failure rate · Time to restore |
| **Value delivered** | Findings caught cumulatively (7.2) · Dependency upgrades cumulative (7.3) · Coverage then and now (7.4) · Blocked releases (4.5) |
| **Pipeline health** | Run outcome timeline (3.1) · Build duration trend (3.2) · Slowest stages (3.4) · Most broken stages (3.5) |
| **Security** | Findings by scanner (4.1) · Above policy (4.2) · Severity table (4.3) · Policy status timeline (4.4) |
| **Quality** | Coverage per module (5.1) · Coverage shortfall (5.2) · Suite success rate (5.3) · Failing jobs (5.4) |
| **Remediation** | Pull requests raised (6.1) · Remediation funnel (6.2) · Upgrades withheld (6.3) |
| **Detail** | Failure reasons (3.6) · Recent builds table (7.6) |

The two dashboard definitions under `grafana/` — `devSecOpsDashboard` and `devSecOpsSecurityDashboard` — were built against the measurements marked as kept in section 1 and continue to work unchanged. Panels from sections 2 to 7 are additions rather than replacements.

## 9. Retention

The cumulative panels in section 7 read from the beginning of the bucket, so the bucket's retention period is what limits how far back "since the beginning" reaches. A bucket created with the default 30 day retention will silently shorten these panels; set the retention to `0` (infinite) or to the period the organisation wants to report on.
