# 03: Webhook Delivery Service

> You built a Message Queue and a Task Scheduler in `../../Concurrency/`. Here the consumers are **other companies' servers on the internet**: slow, flaky, rate-limited, sometimes dead, and sometimes malicious.

---

## The story

You're on the platform team of **Shopsy**, a fictional e-commerce SaaS with 1,000 merchants. When something happens (an order is created, paid, or refunded), Shopsy must `POST` a JSON event to each merchant's registered webhook URL. That's **1M events/day** on average, with spikes of 50× during flash sales.

Merchants build their businesses on these events. A missing `order.paid` means an order never ships. An event delivered twice can mean an order ships twice. Merchants will judge you by the worst day of the year, not the average one.

## What you'll learn

Database-backed queues · `SKIP LOCKED` · MVCC bloat · leases / visibility timeouts · heartbeats · fencing · at-least-once vs exactly-once · idempotent consumers · retry classification · exponential backoff + jitter · dead-letter queues · circuit breakers · multi-tenant fairness · noisy neighbours · shuffle sharding · per-key ordering · head-of-line blocking · delayed jobs · timing wheels · HMAC signatures · SSRF.

## Scope

| In | Out |
|---|---|
| Ingest API, queue, workers, retries, DLQ, scheduler, signing | Merchant-facing UI (optional) |
| A **merchant simulator** that misbehaves on demand | Real internet delivery |

**Tech:** Java or Go, Postgres. Later Redis and/or a broker if *you* decide it's needed. Prometheus + Grafana (reuse from project 05). k6 for ingest load.

---

## Base system spec

### Functional requirements
1. Merchants register subscriptions: URL, event types, and a signing secret.
2. Internal producers submit events. The event is durably accepted before the API returns.
3. Workers deliver each event to every matching subscription with an HTTP `POST`. **Success = 2xx within timeout.**
4. Anyone can query delivery status and attempt history for an event or merchant.
5. (From L2) Operators can replay events from the dead-letter queue.

### API contract

```
POST /subscriptions   {merchantId, url, eventTypes[], secret}      -> 201 {subscriptionId}
POST /events          {merchantId, type, payload}                  -> 202 {eventId}
GET  /events/{id}/deliveries                                       -> 200 [{subscriptionId, status, attempts[]}]
GET  /merchants/{id}/deliveries?status=&since=                     -> 200 [...]
POST /dlq/{deliveryId}/replay                                      -> 202            (L2)
```

### Merchant simulator (build this first; it's your chaos lab)

One HTTP server that pretends to be many merchants. Each merchant ID gets a configurable **behavior**:

| Behavior | What it does |
|---|---|
| `healthy` | 200 in ~20ms |
| `slow` | 200, but p99 = 5s |
| `flaky` | 30% return 500 |
| `dead` | Connection refused |
| `blackhole` | Accepts the connection, never responds |
| `ratelimited` | 429 with `Retry-After: N` above X req/s |
| `recovering` | Dead for N minutes, then healthy |

It **logs every received event ID with a timestamp**. That log is how you count lost and duplicate deliveries.

### Delivery checker (your referee)

Compares events accepted by your API against events received by the simulator:

| Metric | Target |
|---|---|
| **Lost**: accepted, never delivered, and not in DLQ | **0, always** |
| **Duplicates**: delivered > 1 time | Measured and explained (not necessarily 0) |
| **Delivery delay**: received − accepted, per merchant | p50 / p99 per merchant class |
| **Out-of-order** (L4) | 0 for ordered subscriptions |

### Results table: `docs/results.md`

| Level | Scenario | Events | Workers | Throughput | Lost | Dupes | p99 delay (healthy) | p99 delay (others) |
|---|---|---|---|---|---|---|---|---|

---

## Level 0: Postgres as a queue

**Incident:** None. Get events from the API into the simulator with N workers, using **only Postgres** as the queue.

**Done when:**
- [ ] Throughput measured with 1, 4, 16, and 64 workers. Chart it.
- [ ] No event is processed by two workers at the same time (when nothing crashes).
- [ ] All `healthy` merchants: 0 lost, 0 duplicates.

**Predict first:** Will throughput scale linearly with workers? Where will it flatten?

**Design questions:**
1. How do multiple workers grab different rows without stepping on each other? What exactly does `FOR UPDATE SKIP LOCKED` do, and what would happen without `SKIP LOCKED`?
2. Workers poll. What's the trade-off between poll interval, delivery latency, and DB load? What does `LISTEN/NOTIFY` change? What does it **not** guarantee?
3. Each delivery updates a row (status, attempts). What does Postgres MVCC do with every update? What happens to the table and its indexes after 10M updates? What cleans it up?
4. Should a worker hold a DB transaction open **while** making the HTTP call? What happens to the DB if it does and merchants are slow?
5. Fan-out: one event, 3 subscriptions. Is that one queue row or three? What does each choice mean for retries?
6. When is "Postgres as a queue" a good choice, and when is it a bad idea? Write your criteria **now** and revisit them at the end of the project.

**Search:** `Postgres SKIP LOCKED queue`, `LISTEN NOTIFY`, `Postgres MVCC dead tuples`, `autovacuum`, `HOT updates`, `queue in database antipattern`. After you finish, compare with existing libraries: `River`, `graphile-worker`, `Que`, `pg-boss`, `db-scheduler`.

**Self-evaluation:**
- 🟢 Your throughput curve has a knee, and you know which resource caused it.
- 🔴 A transaction stays open across an HTTP call.

---

## Level 1: Workers crash

**Incident:** You deploy (or a worker gets OOM-killed) mid-delivery. Events stay stuck `in_progress` forever. Merchants miss orders.

**Done when:**
- [ ] A crashed worker's in-flight events are picked up again automatically.
- [ ] Long deliveries don't get stolen by another worker while still running.
- [ ] **Chaos test:** under load, `kill -9` a random worker every 5s for 5 minutes. **0 lost**, and duplicates are measured.
- [ ] You can state your delivery guarantee in one sentence.

**Predict first:** With your fix, how many duplicates per 10,000 events under the chaos test?

**Design questions:**
1. How does the system know a worker died versus a worker that is just slow? Can it ever know for sure?
2. **Lease / visibility timeout:** how long should it be? What if a delivery takes longer than the lease?
3. A worker pauses (GC, VM freeze) past its lease. Another worker takes the job. The first one wakes up and *also* finishes. What prevents a stale worker from corrupting the result? (Search: fencing token.)
4. So what's your delivery guarantee: at-most-once, at-least-once, or exactly-once? Can you get exactly-once **delivery** to a third-party HTTP server at all? Why or why not?
5. Given your answer, what must you give merchants so *they* can dedupe? What should your docs tell them?
6. Graceful shutdown: on `SIGTERM`, what should a worker do with in-flight deliveries?

**Search:** `SQS visibility timeout`, `lease heartbeat`, `fencing token Kleppmann`, `How to do distributed locking`, `Two Generals problem`, `at-least-once delivery`, `idempotent consumer`, `graceful shutdown SIGTERM`.

**Self-evaluation:**
- 🟢 Chaos test: 0 lost, 5 runs in a row. You can explain every duplicate.
- 🟢 You can explain why "exactly-once delivery" here is impossible, and what's offered instead.
- 🔴 Your recovery requires a human to reset rows.

---

## Level 2: Endpoints fail

**Incident:** 10% of merchants are `dead` for 6 hours. Workers spend most of their time waiting on timeouts, and **healthy merchants' deliveries slow down**. When the dead merchants come back, the retry flood knocks them over again.

**Done when:**
- [ ] Retries with **exponential backoff + jitter**, capped by max attempts *and/or* max age, then → **DLQ**.
- [ ] DLQ replay endpoint works.
- [ ] `429` + `Retry-After` is respected.
- [ ] `blackhole` merchants don't eat your worker pool.
- [ ] A per-endpoint **circuit breaker** auto-pauses an endpoint after repeated failures, probes it periodically, and resumes.
- [ ] **With 10% dead + 5% blackhole, healthy merchants' p99 delay is within 10% of the all-healthy baseline.**
- [ ] A graph of request rate to a `recovering` merchant, with and without jitter.

**Predict first:** Without any fix, how much does healthy merchants' p99 degrade with 10% dead endpoints?

**Design questions:**
1. Which failures are **retryable**: 400, 401, 404, 410, 429, 500, 503, timeout, connection refused, DNS failure, TLS error? Write your table and justify each row.
2. Why does jitter matter? **Simulate** it: 10,000 retries without jitter vs with it, plotted as requests per second over time. Compare full, equal, and decorrelated jitter.
3. Where do you store "next attempt at", and how do workers find due work efficiently?
4. **Connect timeout vs read timeout vs total deadline**: what does each protect you from? What values, and why? Which one handles `blackhole`?
5. How many workers can be stuck waiting on bad endpoints at once? What bounds that number?
6. What is a **poison message** in this system? (Hint: not every failure is the merchant's fault.)
7. When should you **tell the merchant** something's wrong, and when should you disable their endpoint entirely?

**Search:** `AWS Exponential Backoff and Jitter blog`, `Amazon Builders' Library timeouts retries backoff jitter`, `dead letter queue`, `Retry-After header`, `circuit breaker pattern`, `connect timeout vs read timeout`, `poison message`.

**Self-evaluation:**
- 🟢 The healthy-merchant p99 target is met, and you have the graph.
- 🟢 Your retry table has a reason for every row.
- 🔴 One global retry schedule treats a 404 the same as a 503.

<details><summary>Hint (healthy merchants still slow)</summary>
Count how many workers are waiting on bad merchants at any moment. What if bad merchants could only ever use a few of them?
</details>

---

## Level 3: The noisy neighbor

**Incident:** **BigMart**, your largest merchant, runs a flash sale: 500,000 events in 10 minutes. Small merchants' webhooks are delayed by **40 minutes**. Their support lines light up. Your SLO says 30 seconds.

**Done when:**
- [ ] Flood scenario: one merchant generates 500k events while 999 others send normal traffic.
- [ ] **Small merchants' p99 delay < 30s throughout the flood.**
- [ ] BigMart still gets delivered at a reasonable rate. Define "reasonable" and justify it.
- [ ] Per-merchant delay chart during the flood.
- [ ] You calculated your design's **blast radius**: if one merchant (or one bad endpoint) poisons its share, how many other merchants are affected?

**Predict first:** With one global FIFO queue, what's a small merchant's delay at minute 5 of the flood?

**Design questions:**
1. Why does a single global FIFO queue fail here? Explain it with **Little's Law** and your numbers.
2. Options: per-merchant queues + round-robin, weighted fair queuing, deficit round robin, per-merchant concurrency caps, a dedicated pool for big merchants, **shuffle sharding**. Cost and benefit of each?
3. With per-merchant queues, how do workers pick the next merchant efficiently when there are 1,000 (or 1M) of them?
4. **Shuffle sharding:** with 16 worker pools and each merchant assigned 2, what's the probability that two given merchants share both pools? How does that change with 8 pools and assignment of 4?
5. Should fairness be per merchant, or per **endpoint**? (One merchant could have one slow endpoint and one fast one.)
6. LIFO vs FIFO under a huge backlog: when does serving newest-first make more sense?

**Search:** `noisy neighbor multi-tenant`, `fair queuing`, `deficit round robin`, `Amazon Builders' Library workload isolation using shuffle-sharding`, `Amazon Builders' Library avoiding insurmountable queue backlogs`, `per-tenant concurrency limits`, `Little's Law`.

**Self-evaluation:**
- 🟢 SLO met during the flood, with the chart to show it.
- 🟢 You can explain shuffle sharding's blast radius with your own math.
- 🔴 The fix is "add more workers". Try it with a 5M-event flood.

---

## Level 4: Ordering

**Incident:** A merchant receives `order.cancelled` before `order.created`, or `order.paid` after `order.refunded`. Their system ends up in a state that shouldn't be possible.

**Done when:**
- [ ] Subscriptions can opt into **per-resource ordering** (e.g. all events for `order_123` in order).
- [ ] Under `flaky` merchants: **0 out-of-order deliveries** for ordered subscriptions.
- [ ] Throughput cost of ordering measured.
- [ ] You wrote down what ordering you guarantee **without** the opt-in.

**Predict first:** What ordering does your system give today, with retries and many workers?

**Design questions:**
1. With retries and multiple workers, can ordering ever hold by accident? Build a test that proves it doesn't.
2. Per-key ordering means event N+1 waits for event N. **Head-of-line blocking:** event N fails for 6 hours. What happens to N+1 … N+500? Is that acceptable? What would you offer instead?
3. Alternatives to strict ordering: sequence numbers so merchants can reorder, or **thin events** ("order_123 changed, fetch it") instead of **fat events**. What does each push onto the merchant?
4. How do you partition work so one key always goes to one place, without one hot key hogging a worker?
5. What does ordering mean when the producer itself emits events out of order?

**Search:** `partition key ordering`, `Kafka message ordering per partition`, `head-of-line blocking`, `thin vs fat events`, `event sequence number`, `Stripe webhook ordering`.

**Self-evaluation:**
- 🟢 You have ordered-subscription tests passing, and you can explain the HOL blocking trade-off.
- 🔴 Your ordering depends on there being only one worker.

---

## Level 5: Scheduled events

**Incident:** Two new features. (a) "Send `cart.abandoned` 24h after a cart is created, unless the order completes." (b) Marketing schedules a campaign event for **all 1M customers at exactly 09:00:00**. At 09:00, everything falls over. Separately, the scheduler was down from 08:58 to 09:03.

**Done when:**
- [ ] Delayed delivery with **≤ 1s** accuracy. The distribution of (actual − scheduled) is recorded.
- [ ] 10M scheduled events pending without slowing down normal traffic.
- [ ] Scheduled events can be **cancelled** (the cart completed).
- [ ] The 09:00 spike: 1M events don't take the system down, and normal traffic's SLO holds.
- [ ] Scheduler down for 5 min: nothing is lost, and the backlog drains without a stampede.
- [ ] Multiple scheduler instances run without double-firing.

**Predict first:** What will a `WHERE run_at <= now()` poll cost with 10M pending rows?

**Design questions:**
1. Index on `run_at` + polling: cost at 10M rows? What happens to that index under heavy insert and delete?
2. **Timing wheels** (hierarchical): how do they work, and why does Kafka use one internally? Memory and accuracy trade-offs?
3. A Redis sorted set as a delay queue: how do you pop due items atomically across many schedulers?
4. 1M events at 09:00:00 exactly. Is spreading them out (e.g. 09:00-09:05) a bug or a feature? Who should decide?
5. Several scheduler instances: how do they split the work? Leader election, partitioned time ranges, or row locking? What happens if two think they own the same range?
6. Cancellation: how do you cancel an event that is already being delivered?

**Search:** `hierarchical timing wheel`, `Kafka purgatory timing wheel`, `Redis sorted set delay queue`, `ZRANGEBYSCORE ZREM atomic`, `thundering herd`, `leader election lease`, `cron at scale`.

**Self-evaluation:**
- 🟢 You have an accuracy histogram, a 09:00 spike graph, and a recovery-after-downtime graph.
- 🔴 Scheduler correctness requires exactly one instance.

---

## Level 6: Security

**Incident:** (a) A merchant asks "how do I know this request came from Shopsy?" (b) An attacker registers `http://169.254.169.254/latest/meta-data/` as their webhook URL and watches what your workers fetch.

**Done when:**
- [ ] Every request is signed (HMAC) over **timestamp + body**. Merchants can verify it, and you publish verification sample code.
- [ ] Old timestamps are rejected (replay protection). Tampered bodies are rejected.
- [ ] **Secret rotation** with no downtime and an overlap window.
- [ ] **SSRF protection:** private and link-local IPs, `localhost`, and redirects into them are all blocked, *including* domains whose DNS resolves to private IPs.
- [ ] A test suite proves each item above.

**Design questions:**
1. Why sign the **timestamp** together with the body? What attack does that stop?
2. Why must signature comparison be **constant-time**?
3. How do you rotate secrets so that no event fails verification during the switch?
4. What can an attacker reach through your workers if you don't block SSRF? Why is checking the URL at **registration** time not enough?
5. What is DNS rebinding, and where in your HTTP client do you need to enforce the IP check?
6. Should workers follow redirects at all?

**Search:** `webhook HMAC signature verification`, `Standard Webhooks specification`, `timing attack constant time compare`, `SSRF webhook`, `cloud metadata endpoint SSRF IMDSv2`, `DNS rebinding`, `HTTP client custom dialer resolve IP check`.

**Self-evaluation:**
- 🟢 Every SSRF test is blocked, including the DNS-resolves-to-private case.
- 🔴 You validate the hostname string instead of the resolved IP.

---

## Graduation: answer out loud
1. Design a webhook delivery system for 1M events/day. Then for 1B.
2. What delivery guarantee do you offer, and what does the merchant have to do because of it?
3. How do you stop one merchant's bad endpoint from hurting everyone else?
4. Explain shuffle sharding and calculate its blast radius.
5. How would you deliver 1M events "at 9:00"?
6. When would you replace Postgres with SQS / Kafka here? What would you lose?
7. How do you prevent SSRF in a system that fetches user-supplied URLs?

## Taking it further
Merchant dashboard with delivery logs and one-click replay · self-serve endpoint testing · event-type schemas and versioning · per-merchant rate tiers · a public status page · open-source it as a self-hosted webhook service.
