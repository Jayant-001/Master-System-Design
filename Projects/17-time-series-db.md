# 17: Time-Series Metrics DB

> In project 05 you **used** Prometheus and Grafana. Here you build the database behind them. You'll learn why monitoring systems compress so well, why one bad label can take them down, and why "average of p99s" is a lie.

---

## The story

You run monitoring for **10,000 servers**. Each one reports **500 metrics every 10 seconds**: CPU, memory, request counts, latencies. That's **500,000 samples per second**, all day, every day. Engineers want dashboards for the last hour (fast), alerts that fire within 30 seconds, and the ability to look back **13 months**.

A normal database row per sample won't survive this. You'll build a time-series database.

## How it works (plain words)

- A **series** is one metric for one thing, identified by a name and **labels**: `http_requests_total{service="cart", host="web-12", status="500"}`.
- A **sample** is one point in a series: a timestamp and a number.
- New samples for each series are kept in memory and **compressed** in small blocks. Since neighboring samples are very similar, they compress very well.
- An **index** maps labels → series, so a query like "all `cart` hosts" finds its series quickly.
- Old data is **downsampled** (for example, one point per hour instead of every 10 seconds) so long-range queries stay fast and cheap.

## What you'll learn

The time-series data model · high-rate ingestion · delta-of-delta and XOR compression · head blocks and WALs · inverted indexes and posting lists · cardinality (the real cost) · a small query engine (`rate`, `sum by`) · counters and resets · mergeable histograms and sketches · why percentiles can't be averaged · downsampling and retention · block compaction · alert evaluation at scale · grouping and deduplicating alerts.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Metric** | A named measurement, e.g. `cpu_usage`. |
| **Label** | A key=value tag on a metric, e.g. `host="web-12"`. |
| **Series** | One unique combination of metric name + labels. |
| **Sample** | One (timestamp, value) point. |
| **Cardinality** | The number of unique series. This, more than the number of samples, decides memory and cost. |
| **Counter / gauge** | A counter only goes up (and resets on restart). A gauge goes up and down. |
| **rate()** | "How fast is this counter increasing per second?" |
| **Histogram** | Counts of values in buckets (e.g. latency < 10ms, < 50ms, ...). |
| **Sketch (t-digest, DDSketch)** | A small structure that estimates percentiles and can be merged. |
| **Downsampling / rollup** | Storing a summary over a time window instead of every raw point. |
| **Head block** | The in-memory, most recent data, still being written. |
| **Posting list** | A sorted list of series IDs that have a certain label value. |

## Scope

| In | Out |
|---|---|
| Ingest API, storage engine, label index, small query language, downsampling, alert evaluator | Full PromQL, a dashboard UI (use Grafana with a simple data source, or AI-built charts) |
| A **fleet simulator** load generator | Logs and traces |

**Tech:** Java or Go. Plain files for storage. Postgres only for the naive L0. k6 or your own simulator. Prometheus itself for comparison at the end.

---

## Base system spec

### Ingest API

```
POST /write    (batch, one sample per line)
  cpu_usage{host="web-12",region="ap-south-1"} 0.72 1731500000000
  http_requests_total{service="cart",status="200",host="web-12"} 184223 1731500000000
```

### Query API (a small subset of PromQL)

```
GET /query_range?expr=...&start=...&end=...&step=...
Supported:
  selector         cpu_usage{region="ap-south-1", host=~"web-.*"}
  rate(x[5m])
  sum by (label) (x),  avg, max, min, count
  histogram_quantile(0.99, x)        (L3)
```

### Fleet simulator (build it first)
- N hosts × M metrics, with realistic shapes: counters that go up (and reset on "restart"), gauges as random walks, latency histograms.
- A switch to add a **high-cardinality label** (e.g. `request_id` or `user_id`). You'll need it in L2.
- A switch for **late** and out-of-order samples.

### Query checker (your referee)
For a smaller dataset (e.g. 100 hosts × 1 hour), also store every raw sample in a **simple, slow, obviously correct** store (a sorted file or SQLite). Run the same queries on both. Results must match, within a small float tolerance.

| ID | Invariant | From |
|---|---|---|
| T1 | No accepted sample is lost (count in = count stored) | L0 |
| T2 | Query results = reference results | L0 |
| T3 | Downsampled results = the same aggregation computed on raw data | L4 |
| T4 | Every alert fires within its deadline after the condition is true | L5 |

### Results table: `docs/results.md`

| Level | Scenario | Samples/sec | Bytes/sample on disk | Memory/series | Query p99 (1h, 100 series) | Query p99 (30d, 10k series) | T1 | T2 | T3 | T4 |
|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: The naive way (one row per sample)

**Incident:** None. Store every sample as a row in Postgres. Find out how far that goes.

**Done when:**
- [ ] Ingest and queries work. T1 and T2 pass at small scale.
- [ ] Maximum ingest rate measured.
- [ ] Bytes per sample on disk measured, including indexes.
- [ ] You calculated the rows and storage per day at the target rate.

**Predict first:** Max samples/sec into Postgres on your laptop? Bytes per sample?

**Design questions:**
1. How many rows per day at 500k samples/sec? How big is that with indexes?
2. What does the **access pattern** look like? (Writes always "now"; reads by series over a time range.) Why is a general-purpose database not built for this?
3. How would you store labels: as columns, as JSON, or as a separate table?
4. What does a query like "rate over 5 minutes for 1,000 series" cost here?

**Search:** `time series data model`, `Prometheus data model labels`, `write-heavy workload database`, `time series database why`.

**Self-evaluation:**
- 🟢 You have a measured ceiling, and you can explain what limits it.
- 🔴 You "fixed" it by adding more indexes.

---

## Level 1: Compression

**Incident:** 500k samples/sec × 16 raw bytes each. Work out the disk per day and per year. The storage bill is huge.

**Done when:**
- [ ] An in-memory **head block** per series, with a WAL for crash safety.
- [ ] **Delta-of-delta** encoding for timestamps and **XOR** encoding for values, written yourself.
- [ ] Bytes per sample measured on your simulator data. (Real systems get around 1-2 bytes.)
- [ ] Head data is cut into time blocks (e.g. 2 hours) and written to disk as immutable files.
- [ ] T1 and T2 pass, including after a crash.

**Predict first:** Bytes per sample for a steady gauge? For a noisy random value?

**Design questions:**
1. Samples arrive every 10s, so timestamps are very regular. Why does storing "the change in the gap" make most timestamps nearly free?
2. Why is the XOR of two neighboring float values usually mostly zeros? When isn't it?
3. Test it on bad data: random noise, values that jump around. How does compression change?
4. Chunk size: more samples per chunk compress better, but what does that cost queries?
5. What goes in the WAL, and how do you rebuild the head block after a crash?

**Search:** `Gorilla paper Facebook time series compression`, `delta-of-delta encoding`, `XOR float compression`, `Prometheus TSDB head block`, `varint encoding`, `bit packing`.

**Self-evaluation:**
- 🟢 Bytes per sample measured on 3 data shapes (steady, normal, noisy), and you can explain each.
- 🔴 You used a general-purpose compressor (gzip) and stopped there.

---

## Level 2: The label index, and the cardinality bomb

**Incident:** (a) The query `sum by (region) (cpu_usage)` scans **every series** to find matching ones. (b) A developer adds `request_id` as a label. **50 million** new series appear in an hour. Memory explodes, ingest falls over, and **all** monitoring goes dark during an incident.

**Done when:**
- [ ] An **inverted index**: label=value → sorted list of series IDs.
- [ ] Queries with several label matchers **intersect** lists efficiently. Regex matchers work.
- [ ] Memory per series measured.
- [ ] **Cardinality limits** per metric (and per team): new series beyond the limit are rejected with a clear error, and **existing** series keep working.
- [ ] A "top cardinality" report: which metrics and labels have the most series.

**Predict first:** Memory per series in your engine? How many series before your machine runs out?

**Design questions:**
1. How do you intersect two sorted lists of IDs quickly? (DSA: merge-style intersection.)
2. Regex matchers like `host=~"web-.*"`: how do you avoid checking every label value?
3. Why does **cardinality**, not sample count, decide memory and cost?
4. How do you detect a cardinality explosion **before** it kills the system?
5. Rejecting new series protects the system, but someone loses data. How do you tell them, and what do you suggest instead?

**Search:** `inverted index posting list intersection`, `Prometheus cardinality`, `high cardinality labels problem`, `roaring bitmaps`, `series limit per metric`.

**Self-evaluation:**
- 🟢 Turning on the `request_id` label doesn't take down ingest for other metrics.
- 🔴 Your only defense is "tell developers not to do that".

---

## Level 3: Percentiles (the averaging lie)

**Incident:** The fleet dashboard shows "p99 latency" as the **average of each host's p99**. It says 120 ms. Users are complaining about 2-second requests. The number is wrong.

**Done when:**
- [ ] You **demonstrated** the wrong math with your own data: average of per-host p99s vs the true fleet-wide p99.
- [ ] Histogram metrics (bucket counters) are supported, and `histogram_quantile` works across hosts.
- [ ] (Choose one) **t-digest** or **DDSketch** implemented, with accuracy measured against the exact answer.
- [ ] A chart of error vs bucket layout (or vs sketch size).

**Predict first:** With 1 slow host out of 20, how far off is the "average of p99s"?

**Design questions:**
1. Why can't you average percentiles? Build a tiny example with 2 hosts.
2. Why **can** you add histogram bucket counts across hosts? What does that give you?
3. Bucket boundaries: how do they limit accuracy? How do you choose them?
4. Sketches promise "relative error". What does that mean, and why is it useful for latency?
5. Which of these can be **merged** across hosts and time windows: averages, percentiles, histograms, sketches?

**Search:** `why you can't average percentiles`, `histogram_quantile`, `t-digest`, `DDSketch relative error`, `HdrHistogram`, `mergeable summaries`.

**Self-evaluation:**
- 🟢 You have the "wrong vs right" chart from your own data.
- 🔴 Your fleet p99 is still computed from per-host p99s.

---

## Level 4: Long time ranges (downsampling)

**Incident:** "Show CPU for 1,000 hosts over the last **13 months**." It reads billions of raw samples and takes minutes. Also, keeping 13 months of 10-second data costs too much.

**Done when:**
- [ ] **Rollups** at 5 minutes and 1 hour, keeping enough aggregates to answer avg, max, min, sum, and rate correctly.
- [ ] The query engine **picks the right resolution** based on time range and step.
- [ ] **Retention:** raw for 15 days, 5-minute for 90 days, 1-hour for 13 months (or your own plan).
- [ ] Old blocks are **compacted** into bigger blocks.
- [ ] T3 passes. Query p99 for 13-month queries measured.

**Predict first:** Storage saved by your retention plan, compared with keeping raw for 13 months?

**Design questions:**
1. If you keep only the **average** per hour, which questions can you no longer answer? What must you keep instead?
2. Counters reset when a process restarts. How do you compute `rate` correctly over rolled-up data?
3. How does the query planner choose the resolution? What if the range covers both raw and rolled-up data?
4. Compacting small blocks into big ones: what does it improve, and what does it cost?
5. Can a rolled-up histogram still answer percentile queries?

**Search:** `downsampling rollups time series`, `Thanos downsampling`, `counter reset rate calculation`, `TSDB block compaction`, `retention policies`.

**Self-evaluation:**
- 🟢 T3 passes, and long queries are fast.
- 🔴 Rollups keep only averages.

---

## Level 5: Alerting at scale

**Incident:** 10,000 alert rules are checked every 15 seconds. Work out the queries per second. Evaluations fall behind, so alerts fire minutes late. Then a whole region goes down, and on-call gets **500 pages in one minute**.

**Done when:**
- [ ] A rule evaluator that spreads evaluations evenly across each interval (no spikes).
- [ ] **Evaluation lag** is exported as a metric. T4 passes.
- [ ] `for: 5m` (pending → firing) works. Flapping is reduced with a rule you choose.
- [ ] Rules are **sharded** across several evaluators. One evaluator dying doesn't lose any rules.
- [ ] Notifications are **grouped and deduplicated**: one page for "region down", not 500.
- [ ] An alert fires when data is **missing**. And you have a way to know if **the monitoring system itself** is down.

**Predict first:** Queries per second for 10k rules every 15s? Can one evaluator keep up?

**Design questions:**
1. Why do evaluations at the same moment hurt? How do you spread them?
2. Expensive rule expressions: could you **precompute** them (recording rules)?
3. Sharding rules across evaluators: how do you make sure each rule runs exactly once per interval, even when an evaluator dies? (Leases, again.)
4. Flapping: an alert that fires, resolves, and fires again every minute. How do you calm it down?
5. How do you alert when the **alerting system** is broken? (Search: dead man's switch.)

**Search:** `Prometheus alerting rules for clause`, `recording rules`, `Alertmanager grouping inhibition`, `alert flapping hysteresis`, `dead man's switch monitoring`, `alert fatigue`.

**Self-evaluation:**
- 🟢 You have an evaluation-lag chart at 10k rules, and the region-down test produces one grouped page.
- 🔴 Killing an evaluator silently stops some alerts.

---

## Stretch levels
- **Late and out-of-order samples** (devices that send in batches every hour).
- **Multi-tenancy:** many teams with separate limits and data.
- **Push vs pull:** add a "scrape" mode that pulls from targets. Compare.
- **Grafana data source:** point real Grafana at your DB.

## Graduation: answer out loud
1. Design a metrics system for 10,000 servers. Draw it.
2. Why do time series compress so well? Explain delta-of-delta and XOR.
3. What's cardinality, and why is it the biggest risk?
4. Why can't you average percentiles? What do you do instead?
5. How do you make 13-month queries fast and cheap?
6. How do you run 10k alert rules reliably and avoid paging storms?

## Taking it further
Point Grafana at it and use it for your other projects' dashboards · a logs or traces store next to it · compare with Prometheus/VictoriaMetrics on the same data · a blog post: "the averaging lie", with your charts.
