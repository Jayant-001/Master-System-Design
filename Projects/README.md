# System Design by Building

Nineteen projects. Each starts as a small **base system**. After that, every **level** is a real production incident: make it break, measure it, fix it, and write down what you learned.

These are the distributed, production versions of what you already built in one process in `../../Concurrency/` (TicketMaster, Mini Redis, Rate Limiter, Circuit Breaker, Load Balancer, Message Queue...). Your `synchronized` and `ReentrantLock` stop protecting anything once a second instance is running.

> These files don't give answers. They give you requirements, the questions to answer, what to search for, and how to check your own work. If you get stuck, open the hints, which are nudges and not solutions.

> 🛠️ **Real build (open source):** [Code Gym](code-gym/README.md) is a tool for practicing on your own codebase. It has its own plan, milestones, checkpoints, and progress board in `code-gym/`.

---

## Projects

| # | Project | Core theme | Builds on | Base effort | Levels |
|---|---|---|---|---|---|
| 01 | [Ticket Drop](01-ticket-drop.md) | Concurrency, consistency, idempotency | `Concurrency/TicketMaster`, `Kafka/1-order-checkout-service` | 1 evening | 7 + bonus |
| 02 | [Build Your Own Redis](02-build-your-own-redis.md) | Storage, durability, replication, sharding | `Concurrency/Mini Redis`, `TCP Server` | 1 weekend | 6 |
| 03 | [Webhook Delivery Service](03-webhook-delivery.md) | Reliable async work, retries, fairness | `Concurrency/Message Queue`, `Task Scheduler` | 1 evening | 7 |
| 04 | [Live Canvas](04-live-canvas.md) | Real-time push, fan-out, stateful scaling | `Concurrency/WebSocket Server`, `Pub/Sub` | 1 weekend | 7 |
| 05 | [Cascading Failure Lab](05-cascading-failure-lab.md) | Resilience: timeouts, retries, rate limits, load shedding | `Concurrency/Rate Limiter`, `Circuit Breaker`, `Load Balancer` | 1 weekend | 6 |
| 06 | [Mini Kafka](06-mini-kafka.md) | Logs, partitions, consumer groups, replication | `Kafka/learn-kafka` | 1 weekend | 6 |
| 07 | [Gossip Glomers + Raft](07-gossip-glomers-and-raft.md) | Distributed algorithms, consensus | All of the above | Puzzles | 6 + 4 |
| 08 | [LLM Gateway](08-llm-gateway.md) | Streaming, token-based limits, failover, semantic cache, metering | 05, 01 | 1 evening | 5 + stretch |
| 09 | [Mini Vector DB](09-mini-vector-db.md) | ANN search (HNSW), recall vs speed, quantization, filtered search | 02 | 1 evening | 5 |
| 10 | [Stock Exchange Matching Engine](10-stock-exchange.md) | Single-threaded speed, determinism, event sourcing, replication by replay | `Concurrency/` | 1 weekend | 5 |
| 11 | [Digital Wallet / Ledger](11-digital-wallet-ledger.md) | Double-entry, hot accounts, cross-shard transfers, reconciliation | 01 | 1 evening | 5 |
| 12 | [Durable Workflow Engine](12-durable-workflow-engine.md) | Event history, replay, determinism, timers, sagas, versioning | 03, 11 | 1 weekend | 6 |
| 13 | [Distributed Build Farm](13-distributed-build-farm.md) | DAG scheduling, content-addressable cache, hermeticity, stragglers | `Concurrency/Thread Pool`, 03 | 1 evening | 5 |
| 14 | [Mini CDN](14-mini-cdn.md) | HTTP caching, stampedes, shields, purge, serving stale | 05, 02 | 1 evening | 6 |
| 15 | [Mini S3](15-mini-s3.md) | Durability math, erasure coding, repair, scrubbing, multipart, GC | 02, 06 | 1 weekend | 5 |
| 16 | [LSM-Tree Storage Engine](16-lsm-storage-engine.md) | WAL, SSTables, bloom filters, compaction, amplification | 02, 06 | 1 weekend | 5 |
| 17 | [Time-Series Metrics DB](17-time-series-db.md) | Compression, cardinality, percentiles, downsampling, alerting | 05, 16 | 1 evening | 5 |
| 18 | [Ride Matching](18-ride-matching.md) | Geo indexes, hot cells, matching races, ETA graphs, surge | 01, 04 | 1 evening | 5 |
| 19 | [Collaborative Doc Editor](19-collaborative-editor.md) | OT, CRDTs, causality, offline sync, presence | 04, 07 | 1 weekend | 6 |

Use [`_level-log-template.md`](_level-log-template.md) for every level.

### Suggested order

Start with the **core path**: `01 → 05 → 03 → 02 → 04 → 06 → 07`. Do Gossip Glomers challenges whenever you want a short weekend puzzle.

- **01** gives you the fastest "it broke" moment, and it's built on correctness, which everything else depends on.
- **05** gives you the observability setup (Prometheus/Grafana/Toxiproxy) that every later project reuses.
- **03** and **02** use the ideas from 01/05 under harder failure modes.
- **04** and **06** are about throughput and fan-out at scale.
- **07** comes last because by then the problems it covers will feel familiar.

After project 01 and project 05, you can also jump into a **track** that interests you. Each track reuses skills from the core path:

| Track | Projects | Good if you want... |
|---|---|---|
| **GenAI infra** | 08 → 09 | AI systems at work, and a micro-SaaS idea |
| **Money and markets** | 11 → 10 → 12 | Correctness under pressure; strong interview stories |
| **Storage internals** | 16 → 15 → 17 | To understand databases from the bytes up |
| **Platform / infra** | 12 → 13 → 14 | Systems that other engineers depend on |
| **Real-time and geo** | 04 → 18 → 19 | Live, visual systems you can show friends |

Projects link to each other on purpose. For example, 11 L3 (cross-shard transfers) is exactly what 12 (workflow engine) is built for, and 17 (time-series DB) reuses ideas from 16 (LSM). When a file says "project X again", go back and compare your two solutions.

---

## The rules

1. **You write the core logic by hand.** AI is fine for boilerplate: docker-compose, k6 scripts, HTML frontends, Grafana dashboards. It shouldn't write the thing the level is about.
2. **Predict before you test.** Write what will break, when, and why. Your wrong predictions teach you the most.
3. **Reproduce before you fix.** If you can't make it fail on demand, you can't prove you fixed it.
4. **Every level ends with a number.** If there's no number, the level isn't done.
5. **Write a 5-line postmortem.** It should name the *mechanism*, not the tool. "The lock was held across a network call" is a mechanism. "Used Redis" is not.
6. **Read reference implementations only after you finish the level** (Redis source, Kafka design docs, blog posts). Then compare them with what you built.

---

## The loop (do this for every level)

```
 1. Read the incident + acceptance criteria
 2. PREDICT          -> write in docs/LN.md (template section 1)
 3. ANSWER the design questions in writing (½ page max). Pick an approach. Say why.
 4. SEARCH the listed terms, only AFTER attempting the questions. Revise your answers.
 5. REPRODUCE the incident (failing load test / chaos test / unit test)
 6. BUILD the fix
 7. MEASURE, fill the results table, run at least 3 times
 8. COMPARE with your prediction
 9. POSTMORTEM, 5 lines
10. SELF-SCORE with the rubric below. Move on at >= 3.
```

---

## Self-evaluation rubric (every level)

| Score | Name | You can honestly say... |
|---|---|---|
| 1 | **It works** | "The symptom is gone on my laptop." |
| 2 | **Proven** | "I have a test that fails before the fix and passes after, at the stated load, run at least 3 times." |
| 3 | **Explained** | "Without notes, I can explain why it broke and why the fix works, with my own numbers." |
| 4 | **Traded off** | "I can name 2 alternatives and when each would beat mine, and I know what breaks next at 10×." |

Move to the next level at **3**. Come back for **4** before an interview.

### Signs you're doing well
- Your predictions get more accurate from level to level.
- You reach for a metric or a profiler before you reach for code.
- Your postmortems name mechanisms (lock hold time, queue depth, fan-out factor).
- You find a new failure mode the file didn't mention. Write it down. That's the real job.

### Signs you're off track
- You fixed it but can't make the original failure happen again.
- You tested with 10 users when the level says 2,000.
- Your results only have averages. Use p50/p99/max.
- You picked an approach because a blog post said so, and you can't say when it would be wrong.
- You used AI to write the core part. Throw that version away and rewrite it yourself.

---

## Toolbox (learn each one when a level needs it, not before)

| Tool | What for | First needed in |
|---|---|---|
| Docker Compose | Run Postgres, Redis, NATS, etc. locally | 01 L0 |
| k6 | Load tests (HTTP + WebSocket) | 01 L0 |
| `psql`, `pg_stat_activity`, `pg_locks` | See what Postgres is doing and waiting on | 01 L1 |
| Prometheus + Grafana | Metrics and dashboards | 05 L0 |
| Toxiproxy | Inject latency, drops, partitions between services | 05 L1, 02 L4 |
| `redis-cli`, `redis-benchmark` | Talk to and benchmark Redis (and your clone) | 02 L0 |
| async-profiler (Java) / pprof (Go) | Find where CPU time actually goes | 02 L1 |
| OpenTelemetry + Jaeger | Trace one request across services | 05 (stretch) |
| Redpanda / Kafka, Debezium | Broker; change data capture | 01 L6 |
| Maelstrom | Distributed systems test harness | 07 |

### Load-testing traps (read once, answer for yourself)
- A load generator that waits for each response before sending the next request: what does it hide when the server slows down? Search: `coordinated omission`, `k6 open model vs closed model`, `constant-arrival-rate`.
- Your load generator and your system share one laptop. What does that do to your numbers? Write it in your results notes.
- Why are averages misleading for latency? Search: `latency percentiles`, `HdrHistogram`.
- 10k connections on macOS will hit OS limits. Search: `macOS ulimit -n`, `kern.maxfiles`, `ephemeral port exhaustion`.
- Warm up first, run each config at least 3 times, and record p50 / p99 / max / error count.

---

## Reading map (read *after* the level, to compare)

| Topic | After level | Source |
|---|---|---|
| Transactions, isolation, lost updates | 01 L1 | *Designing Data-Intensive Applications* (DDIA), Transactions chapter |
| Idempotent APIs | 01 L3 | Amazon Builders' Library: *Making retries safe with idempotent APIs*; Stripe blog: *Designing robust and predictable APIs with idempotency* |
| Transactional outbox | 01 L6 | microservices.io: *Transactional outbox* |
| Timeouts, retries, jitter | 05 L1-L2, 03 L2 | Amazon Builders' Library: *Timeouts, retries, and backoff with jitter*; AWS Architecture blog: *Exponential Backoff and Jitter* |
| Cascading failures, overload | 05 | Google SRE book: *Addressing Cascading Failures*, *Handling Overload* |
| Load shedding | 05 L5 | Amazon Builders' Library: *Using load shedding to avoid overload* |
| Shuffle sharding, queue backlogs | 03 L3 | Amazon Builders' Library: *Workload isolation using shuffle-sharding*, *Avoiding insurmountable queue backlogs* |
| Replication | 02 L4, 06 L4 | DDIA, Replication chapter |
| Partitioning | 02 L5 | DDIA, Partitioning/Sharding chapter; Redis Cluster spec |
| Logs, streams | 06 | DDIA, Stream Processing chapter; Kafka docs "Design" section; Jay Kreps: *The Log* |
| Faults, clocks, leases | 03 L1 | DDIA, *The Trouble with Distributed Systems* |
| Consensus | 07 | Raft paper; DDIA, Consistency and Consensus chapter |

---

## Working with Claude on these

| Say this | You get |
|---|---|
| "Review my Ticket Drop L2 code" (paste or give path) | A review against the acceptance criteria. No rewrite. |
| "Hint 1 for Redis L3" | One nudge, nothing more |
| "Grade my L1 postmortem" | A score on the rubric, plus what's missing |
| "Next incident" | A surprise incident that isn't in the file |
| "Mock interview on Webhook Delivery" | Questions from that project's Graduation section, with follow-ups |

---

## Suggested repo layout per project

```
ticket-drop/
  README.md            # what it is, how to run, current level
  docker-compose.yml
  loadtest/            # k6 scripts
  checker/             # invariant checker (your referee)
  chaos/               # scripts that kill / slow / partition things
  docs/
    L0.md L1.md ...    # copy of _level-log-template.md per level
    results.md         # every number you measured
    decisions.md       # 1-line ADRs: "chose X over Y because Z"
  src/
```
