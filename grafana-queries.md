Based on the data structure sent by the pipeline (defined in the `report.groovy` file), I have prepared a set of ready-to-use Flux queries for Grafana. They cover key DevSecOps metrics, including DORA statistics, vulnerability trends, code coverage, and pipeline performance analysis.

To fully utilize them, I recommend adding two variables to your Grafana dashboard:

${project} - to filter by project name.
${env} - to filter by environment (e.g., test, dev, prod).

Here are the queries ready to be pasted into Grafana panels:

1. DORA Metrics
A. Deployment Frequency

Visualization: Time series (Bar chart)
Description: Shows how many times the pipeline was triggered in a given time window (e.g., daily).
```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "deployments")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "count")
  |> aggregateWindow(every: v.windowPeriod, fn: sum, createEmpty: true)
  |> yield(name: "Deployment Count")
```

B. Change Failure Rate (CFR)

Visualization: Stat / Gauge (Unit: Percent 0-100)
Description: The percentage of pipeline runs that ended in failure. Since the pipeline sends 1 for errors and 0 for successes, the average of these values multiplied by 100 gives the exact CFR percentage.
```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "change_failure")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "value")
  |> mean()
  |> map(fn: (r) => ({ r with _value: r._value * 100.0 }))
  |> yield(name: "Change Failure Rate (%)")
```

C. Average Lead Time / Build Duration

Visualization: Time series (Line) or Stat (Unit: Seconds)
Description: Shows how the time required to build, test, and deploy the application has changed over time.
```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "build_duration")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "value")
  |> aggregateWindow(every: v.windowPeriod, fn: mean, createEmpty: false)
  |> yield(name: "Average Duration (s)")
```

2. Security Posture
A. Critical and High Vulnerabilities Trend over time

Visualization: Time series (Line)
Description: The evolution of security debt grouped by scanner type (SAST, DAST, NexusIQ). Shows whether the team is "paying off" the debt or if the application is becoming more vulnerable.
```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "vulnerabilities")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "critical" or r["_field"] == "high")
  |> aggregateWindow(every: v.windowPeriod, fn: last, createEmpty: false)
  |> yield(name: "Vulnerability Trend")
```
(In Grafana's "Transform" options, consider using "Rename by regex" to clean up names, e.g., displaying `sast - critical`).

B. Current security status (Latest scan)

Visualization: Bar Gauge (Horizontal orientation)
Description: The absolute, latest number of vulnerabilities at the current moment.
```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "vulnerabilities")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "critical" or r["_field"] == "high" or r["_field"] == "medium")
  |> last()
  |> yield(name: "Current Vulnerabilities")
```

3. Code Quality
Test Coverage Trend

Visualization: Time series (Line + Fill below to) (Unit: Percent 0-100)
Description: A line chart showing whether the team maintains unit tests. You can also set a red line (Threshold) at the 60% mark to easily visualize rule violations.
```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "test_coverage")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "line_pct")
  |> aggregateWindow(every: v.windowPeriod, fn: last, createEmpty: false)
  |> yield(name: "Line Coverage (%)")
```

4. Pipeline Profiling
A. Average duration of pipeline stages (Bottleneck Analysis)

Visualization: Bar Chart (Sort descending) (Unit: Seconds)
Description: Quickly identifies which step (e.g., waiting for AppScan or end-to-end tests) is the bottleneck slowing down the CI/CD flow.
```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "stage_metric")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "duration_ms")
  |> group(columns: ["stage"])
  |> mean()
  |> map(fn: (r) => ({ r with _value: r._value / 1000.0 })) // Convert ms to seconds
  |> yield(name: "Stage Duration")
```

B. Which steps fail most frequently? (Pie Chart — % share per stage)

Visualization: Pie Chart
Description: Shows the percentage share of failures for each pipeline stage over the selected time range.
Each slice represents one stage; the size is proportional to how many pipeline runs that stage recorded a failure result.
feedInfluxDB() writes result="failure" for any non-success status (FAIL, WARN, OVERRIDE, SKIP), so the chart covers all failure modes.

Grafana configuration:
- Panel type: Pie chart
- Legend: show percentage + value
- Tooltip: All series
- Unit: short (counts) — Grafana calculates and displays % automatically in labels/legend

```flux
from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "stage_metric")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "result")
  |> filter(fn: (r) => r["_value"] == "failure")
  |> group(columns: ["stage"])
  |> count()
  |> group()
  |> yield(name: "Stage Failure Distribution")
```

Optional — explicit percentage calculation (use when you need the % value in a Stat or Table panel instead of a Pie Chart):

```flux
import "math"

failures = from(bucket: "DORA-metrics")
  |> range(start: v.timeRangeStart, stop: v.timeRangeStop)
  |> filter(fn: (r) => r["_measurement"] == "stage_metric")
  |> filter(fn: (r) => r["project"] == "${project}")
  |> filter(fn: (r) => r["env"] == "${env}")
  |> filter(fn: (r) => r["_field"] == "result")
  |> filter(fn: (r) => r["_value"] == "failure")
  |> group(columns: ["stage"])
  |> count()
  |> group()

totalRow = failures
  |> sum(column: "_value")
  |> findRecord(fn: (key) => true, idx: 0)

failures
  |> map(fn: (r) => ({
      r with
      _value: math.round(x: float(v: r._value) / float(v: totalRow._value) * 1000.0) / 10.0,
      _field: "failure_pct"
  }))
  |> yield(name: "Stage Failure Percentage (%)")
```
