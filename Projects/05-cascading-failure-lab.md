# 05: Cascading Failure Lab

> You built a Rate Limiter, Circuit Breaker, and Load Balancer as single-process classes in `../Concurrency/`. Here they run on the network, between real services, while you break things on purpose and watch one slow dependency take down everything.

---

## The story

Your company runs a small microservice system behind an API gateway called **Gatekeeper**, which you build. One Tuesday, the payments provider gets slow. Within 3 minutes, **the whole site is down**, including pages that never touch payments. After payments recovers, the site **stays down**.

This project is a lab: you build the system, then inject failures and watch them spread. Then you add resilience patterns one at a time and **measure** what each one buys you.

## What you'll learn

Reverse proxies · connection pooling and keep-alive · health checks · Little's Law · timeouts and deadline propagation · bulkheads · retry amplification · retry budgets · circuit breakers · metastable failures · rate-limiting algorithms · distributed rate limiting · fail open vs fail closed · load-balancing algorithms (round robin, least outstanding requests, power of two choices, EWMA) · outlier ejection · goodput · load shedding · adaptive concurrency limits · priority shedding.

## Scope

| In | Out |
|---|---|
| Gateway (you build it), 3 toy services, chaos injection, dashboards | Using Envoy/nginx as *the* gateway. You build it, then compare with them. |
| Open-model load tests | Service mesh, Kubernetes (stretch) |

**Tech:** Go or Java. Docker Compose. **Toxiproxy** between every hop. **Prometheus + Grafana** (you'll reuse this setup in every other project). k6 with `constant-arrival-rate`.

---

## Base system spec

### Topology

```
k6 ──▶ Gatekeeper (×1, later ×3) ──▶ [toxiproxy] ──▶ users-svc    (×3 replicas)
                                 ──▶ [toxiproxy] ──▶ orders-svc   (×3) ──▶ [toxiproxy] ──▶ payments-svc (×3)
                                 ──▶ [toxiproxy] ──▶ recs-svc     (×3)   # "recommendations", non-critical
```

### Toy services (about 30 lines each)
Each service has an endpoint that does fake work and is configurable through environment variables or an admin endpoint: `LATENCY_MS`, `JITTER_MS`, `ERROR_RATE`, `MAX_CONCURRENCY` (simulates limited capacity: over it, latency grows). `orders-svc` calls `payments-svc` on every request.

### Gateway requirements (L0)
- Route by path prefix: `/users/*`, `/orders/*`, `/recs/*`.
- Load-balance across replicas (round robin to start).
- Propagate a request ID header end to end, and include it in logs.
- Export metrics.

### Dashboard (build it in L0; it's your main instrument)
Per route **and** per upstream: RPS · error rate · p50/p99 · **in-flight requests** · connection-pool usage · retries/sec · circuit-breaker state · rate-limit rejections · shed requests.

### Chaos scenarios (scripts in `chaos/`)

| Scenario | Toxic |
|---|---|
| `slow-payments` | +2s latency on all payments replicas |
| `flaky-payments` | 30% errors |
| `one-slow-replica` | +300ms on just 1 of 3 users-svc replicas |
| `dead-replica` | Kill 1 replica |
| `overload` | 3× normal traffic |
| `recover` | Remove all toxics |

### Results table: `docs/results.md`

| Level | Scenario | Patterns on | users p99 | users err% | orders p99 | orders err% | Recovery time after `recover` |
|---|---|---|---|---|---|---|---|

---

## Level 0: Reverse proxy and load balancer

**Incident:** None yet. Build Gatekeeper, the services, and the dashboard.

**Done when:**
- [ ] All routes work through the gateway, and the dashboard shows live metrics.
- [ ] Measured: gateway overhead (p50/p99 added latency) with **new connection per request** vs **pooled keep-alive connections**.
- [ ] `dead-replica`: you measured how many requests fail before the gateway stops sending to it, and why that many.

**Predict first:** How much latency does opening a new TCP connection per request add? (Add TLS for a bigger difference.)

**Design questions:**
1. What does a reverse proxy do with connections to upstreams? What does connection pooling buy you, and what's the risk of a too-small or too-large pool?
2. **Active vs passive health checks:** what does each detect, and how fast? What's the cost of each?
3. A replica is removed. What happens to its **in-flight** requests?
4. Why propagate a request ID? Follow one request through all logs.
5. **Load test model:** why must you use an **open model** (constant arrival rate) for this lab instead of a fixed number of looping users? What would a closed model hide when a service gets slow?

**Search:** `reverse proxy connection pooling keep-alive`, `active vs passive health checks`, `Envoy outlier detection`, `correlation ID`, `coordinated omission`, `k6 constant-arrival-rate`.

**Self-evaluation:**
- 🟢 Your dashboard shows a dead replica being ejected, and you can explain the failure count.
- 🔴 Your load test is closed-model.

---

## Level 1: The slow dependency

**Incident:** Run `slow-payments`. Watch the dashboard. Orders slow down, which you'd expect. But within a minute **users-svc requests start failing too**, even though users-svc is perfectly healthy.

**Done when:**
- [ ] You **reproduced** the cascade and identified the **shared resource** that spread it.
- [ ] You calculated, with **Little's Law**, how many requests would be in flight, and the dashboard confirms it.
- [ ] With your fix: during `slow-payments`, users-svc p99 and error rate stay within 5% of baseline.
- [ ] Every hop has a timeout, and you can justify each value.

**Predict first:** How many seconds after `slow-payments` starts will users-svc begin failing?

**Design questions:**
1. Why did an **unrelated** route fail? What do users and orders share inside the gateway?
2. **Little's Law:** in-flight = arrival rate × latency. Plug in your numbers before and after the toxic.
3. How do you choose a timeout? From the dependency's p99? p99.9? From the caller's own deadline?
4. With 3 hops, should inner timeouts be shorter than outer ones? What goes wrong if they're not?
5. **Deadline propagation:** how does a downstream service know how much time the original caller has left?
6. **Bulkheads:** how do you stop one route from using all of a shared resource? How do you size each compartment?

**Search:** `cascading failure`, `Little's Law`, `bulkhead pattern`, `deadline propagation`, `gRPC deadlines`, `Amazon Builders' Library timeouts retries and backoff with jitter`, `Google SRE book addressing cascading failures`.

**Self-evaluation:**
- 🟢 Your Little's Law prediction matched the in-flight graph within 20%.
- 🟢 You can name the shared resource and show it saturating on the dashboard.
- 🔴 Your fix is "increase the thread pool".

<details><summary>Hint (finding the shared resource)</summary>
Plot in-flight requests at the gateway per route during the toxic. What has a fixed maximum?
</details>

---

## Level 2: The retry storm

**Incident:** To "improve reliability", someone adds 3 retries in k6 (the client), 3 in Gatekeeper, and 3 in orders-svc. Run `flaky-payments`. Payments gets far more traffic than real users send, falls over completely, and **after you run `recover`, it stays down**.

**Done when:**
- [ ] You calculated the worst-case **retry amplification** factor, and the dashboard confirms it.
- [ ] You reproduced a failure that **persists after the trigger is removed**.
- [ ] With your fix: after `recover`, the system returns to baseline **by itself**, within a bound you choose. Graph it.
- [ ] Circuit breaker with closed → open → half-open → closed, visible on the dashboard.

**Predict first:** Worst-case calls to payments per user request? Will the system recover after `recover`?

**Design questions:**
1. Amplification with retries at 3 layers: write the formula and plug in your numbers.
2. Retry at **which** layer, and why only one?
3. **Retry budget** (for example, retries ≤ 10% of requests) vs a fixed per-request retry count: what does each protect against?
4. **Circuit breaker:** what trips it (error rate, latency, consecutive failures)? How long does it stay open? How many probe requests in half-open? What does the caller get while it's open?
5. Why did the system **stay down** after the trigger was removed? What keeps the overload going? (Search: metastable failures.)
6. Which requests are **safe to retry**? What does that have to do with project 01 L3?

**Search:** `retry amplification`, `retry budget`, `Finagle retry budget`, `Envoy retry budget`, `circuit breaker half-open`, `Metastable Failures in Distributed Systems paper`, `Amazon Builders' Library`, `idempotent retries`.

**Self-evaluation:**
- 🟢 You have two graphs, one without and one with your fixes, showing recovery after `recover`.
- 🟢 You can explain the metastable state in one paragraph.
- 🔴 The circuit breaker flaps open and closed every second.

---

## Level 3: Rate limiting

**Incident:** One API key, a partner's buggy integration, sends 50× its normal traffic and uses all of orders-svc's capacity. You add rate limits. Then you scale Gatekeeper to **3 instances**, and the partner gets 3× their limit.

**Done when:**
- [ ] Per-API-key limits (for example 100 req/s, burst 200), configurable at runtime with no restart.
- [ ] `429` responses with `Retry-After` and rate-limit headers.
- [ ] **Boundary test:** a burst timed exactly at window edges doesn't let through more than allowed.
- [ ] **3 gateway instances:** the total admitted is within a tolerance you set of the limit. Measure the error.
- [ ] **Limiter store down:** behavior matches what you designed. Rate-limit-check latency p99 is recorded.

**Predict first:** With a fixed-window limiter, how many requests can a client get through in 1 second around a window boundary?

**Design questions:**
1. Compare fixed window, sliding log, sliding window counter, token bucket, leaky bucket, and GCRA. What's the boundary problem? Memory per key? CPU per check?
2. **Distributed:** a central store means a network round-trip on every request. Is that acceptable? What about local limits with periodic sync (approximate)? How wrong can approximate be?
3. Why must the check-and-update be **atomic**? How do you make it atomic in Redis?
4. The limiter store goes down. **Fail open or fail closed?** Should the answer be the same for every route? (Read *Avoiding fallback in distributed systems* afterwards.)
5. Whose clock does the limiter use? What if gateway clocks differ?
6. Rate limits vs **concurrency** limits: which protects a slow service better?

**Search:** `token bucket`, `sliding window rate limiter`, `GCRA generic cell rate algorithm`, `Redis Lua rate limiter`, `Stripe rate limiters blog`, `IETF RateLimit header fields`, `Amazon Builders' Library avoiding fallback in distributed systems`.

**Self-evaluation:**
- 🟢 The boundary test passes, and the 3-instance error is measured.
- 🟢 You can defend fail-open/closed **per route**.
- 🔴 Each gateway instance enforces the full limit on its own.

---

## Level 4: Smarter load balancing

**Incident:** Run `one-slow-replica`. One users-svc replica is on a noisy host and responds 300ms slower. Round robin keeps sending it a third of the traffic, and users p99 gets terrible, even though the replica isn't "down".

**Done when:**
- [ ] Implemented: round robin, **least outstanding requests**, **power of two choices (P2C)**, and an **EWMA latency**-based picker.
- [ ] Outlier ejection with a maximum ejection percentage.
- [ ] Table of p50/p99 for each algorithm under `one-slow-replica`, with **1** gateway instance and with **3**.

**Predict first:** Which algorithm wins with 1 gateway? Does the winner change with 3 gateways?

**Design questions:**
1. Why does round robin ignore slowness?
2. What information does each algorithm need? With 3 independent gateway instances, how stale is that information?
3. Why might "always pick the least-loaded replica" behave **worse** than "pick 2 at random and choose the better one" when information is stale? (Search: herd behavior.)
4. Outlier ejection: what if 2 of 3 replicas look bad? Why cap the ejection percentage?
5. What's the risk of a latency-based picker when a replica fails **fast** (returns errors in 1ms)?

**Search:** `power of two choices load balancing`, `least outstanding requests`, `EWMA load balancer`, `Finagle peak EWMA`, `herd behavior load balancing`, `Envoy outlier detection max ejection percent`.

**Self-evaluation:**
- 🟢 Your table has both 1- and 3-gateway results, and you can explain any difference.
- 🔴 Your latency-based picker routes all traffic to a replica that fails fast.

---

## Level 5: Load shedding

**Incident:** A marketing campaign sends **3× capacity**. Everything gets slow for everyone. Requests time out after doing most of their work, so the servers are 100% busy and **almost nothing succeeds**. Meanwhile checkout (critical) and recommendations (nice to have) are treated the same.

**Done when:**
- [ ] A chart of **goodput** (successful requests within deadline per second) against offered load from 0.5× to 3×. Without shedding it collapses. With shedding it **plateaus**.
- [ ] Checkout (`/orders`) success rate at 3× is above a target you set, while `/recs` is shed first.
- [ ] Shed requests are **cheap** (measure the cost of a rejection) and return a status that clients handle well.
- [ ] (Stretch) An **adaptive** concurrency limit that finds capacity by itself.

**Predict first:** At 2× offered load, without shedding, what percentage of requests succeed within deadline?

**Design questions:**
1. Throughput vs **goodput**: what's the difference, and why does goodput collapse under overload?
2. **Where** to shed: the gateway, or each service? What does each know that the other doesn't?
3. **What signal** triggers shedding: CPU, concurrency, queue time, latency? Which reacts fastest and most accurately?
4. A fixed concurrency limit vs an adaptive one (AIMD, TCP-Vegas-style, gradient): what's the trade-off?
5. **FIFO vs LIFO** for the request queue under overload: why might newest-first be better? What does CoDel do?
6. How do you rank traffic by priority, and how do you stop "everything is critical" from creeping in?
7. What should a client do when it's shed?

**Search:** `Amazon Builders' Library using load shedding to avoid overload`, `goodput`, `Netflix concurrency-limits`, `adaptive concurrency AIMD`, `CoDel queue management`, `LIFO queue under overload`, `Google SRE book handling overload`, `criticality-based load shedding`.

**Self-evaluation:**
- 🟢 Your goodput curve plateaus, and you have the before/after chart.
- 🟢 You can explain why rejecting fast helps the requests you *do* accept.
- 🔴 Rejecting a request costs nearly as much as serving it.

---

## Final deliverable: your resilience runbook

Write `docs/runbook.md`: for each chaos scenario, what the dashboard shows, which pattern handles it, which knob to turn, and what you'd do as the on-call. This is the doc you'll wish your team had.

## Graduation: answer out loud
1. One dependency is slow, and an unrelated route fails. Explain why, using Little's Law.
2. How do you choose timeouts in a 3-hop call chain?
3. Why are retries dangerous, and how do you make them safe?
4. What's a metastable failure? Give an example from your lab.
5. Design a distributed rate limiter. What happens when its store fails?
6. Why does power of two choices work so well?
7. How does load shedding *increase* the number of successful requests?

## Taking it further
Compare your gateway with Envoy using the same chaos scenarios · add OpenTelemetry tracing · run it on Kubernetes (kind) with HPA autoscaling and watch autoscaling interact with retries · write a chaos-experiment blog post with your graphs.
