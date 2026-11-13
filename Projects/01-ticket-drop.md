# 01: Ticket Drop (500 Seats, 20,000 Fans)

> You already solved this inside one JVM (`../Concurrency/TicketMaster`). This version has 3 app instances, a real database, a payment provider that times out, and bots. Your `synchronized` blocks protect nothing here.

---

## The story

You run ticketing for a concert. The sale opens at **10:00:00**. Within seconds, thousands of fans hit **Buy**. The business has four rules:

1. **Never oversell.** Selling 501 tickets for 500 seats is a refund, an angry artist, and news coverage.
2. **Never double-charge.** Charging a fan twice for one ticket is worse.
3. **Stay up.** A sold-out page is fine. A 500 error page isn't.
4. **Be fair.** Bots and people who hammer refresh shouldn't win just by sending more requests.

## What you'll learn

Race conditions · transaction isolation · pessimistic vs optimistic locking · hot rows · connection pools · cache-as-counter consistency · reconciliation · idempotency · order state machines · reservations with expiry · admission control / waiting rooms · the dual-write problem · transactional outbox · change data capture.

## Scope

| In | Out |
|---|---|
| Backend APIs, database, cache, broker | Real payments (you build a fake one) |
| Load tests, chaos tests, invariant checker | Auth (send `X-User-Id` header) |
| Multiple app instances behind a load balancer | Frontend (optional; AI can build it) |

**Tech:** Java or Go, Postgres, Docker Compose, k6. Later you'll add Redis, Redpanda/Kafka, and Debezium.

---

## Base system spec

### Functional requirements
1. Admin creates an event with `N` general-admission tickets.
2. Anyone can see how many tickets remain.
3. A user buys `qty` tickets (1-4). The user is charged and gets an order.
4. Per-user limit: **at most 4 tickets per event**, across all their orders.
5. A user can list their orders.

### Non-functional targets (you work toward these level by level)
- 0 oversells, 0 double charges, 0 per-user-limit violations, under every test.
- Purchase p99 under a target **you set** in L1, then keep under 10× load.
- Runs as **≥ 2 app instances** from L0 onward. This is non-negotiable.

### API contract (implement this exactly; the internals are your design)

```
POST /events                      {name, totalTickets}         -> 201 {eventId}
GET  /events/{eventId}                                          -> 200 {eventId, name, remaining}
POST /events/{eventId}/purchase   {qty}   hdr: X-User-Id        -> 201 {orderId, qty, status}
                                                                 | 409 {error: "SOLD_OUT" | "LIMIT_EXCEEDED"}
GET  /users/{userId}/orders                                     -> 200 [{orderId, eventId, qty, status}]
```

### Fake payment service (build it yourself, about 50 lines)

```
POST /charge  {userId, amountCents, reference}  -> 200 {chargeId} | 402 {declined} | (hang)
GET  /ledger                                    -> 200 [all charges]   # used by the checker only
```

Configure its behavior with environment variables. You'll turn these up in later levels:

| Knob | Meaning | Default |
|---|---|---|
| `LATENCY_MS` | Base latency per charge | 50 |
| `JITTER_MS` | Random extra latency | 0 |
| `DECLINE_RATE` | Fraction returning 402 | 0 |
| `HANG_RATE` | Fraction that never respond (30s) | 0 |
| `CHARGE_THEN_DROP_RATE` | Charge **succeeds** but the response is never sent | 0 |

> That last knob is the nastiest real-world case. Think about why before L3.

### Invariant checker (the most important file in this project)

Write a script that runs **after every load test** and prints PASS/FAIL per invariant. It queries your DB, the payment `/ledger`, and (from L6) the event consumer's log.

| ID | Invariant | From |
|---|---|---|
| I1 | Tickets sold ≤ total tickets | L0 |
| I2 | Every successful charge maps to exactly one confirmed order, and vice versa | L0 |
| I3 | No user holds > 4 tickets for an event | L0 |
| I4 | No ticket is held/sold to two users at the same time | L4 |
| I5 | Every confirmed order has exactly one `TicketSold` event consumed; no event exists for a non-confirmed order | L6 |

> Treat the checker as the referee. If it isn't run, the test doesn't count.

### Load test (k6)
- A scenario where **all virtual users fire at the same instant**, like a sale opening.
- A scenario with a **sustained arrival rate** (open model) for throughput testing.
- Search: `k6 scenarios`, `k6 shared-iterations`, `k6 constant-arrival-rate`, `k6 ramping-arrival-rate`, `k6 thresholds`.

### Data model: your call. Answer these first:
- What tables do you need? Is "remaining" a **stored counter**, or **computed** by counting order rows? What does each choice cost under contention?
- Where is the per-user limit stored and checked?
- What statuses can an order have? (You'll revise this in L3.)

### Results table: keep in `docs/results.md`

| Level | Strategy | VUs / RPS | p50 | p99 | Errors | I1 | I2 | I3 | I4 | I5 |
|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: Build it the obvious way

**Incident:** None yet. Build the naive version: read `remaining`, and if it's enough, charge the user, insert the order, and decrement. Run **2 app instances** behind a simple load balancer (nginx, or k6 hitting both ports).

**Done when:**
- [ ] All 4 endpoints work. The checker passes at **1 VU**.
- [ ] You ran 50, 500, and 2,000 VUs, and recorded oversell count and per-user violations for each.
- [ ] You can reproduce an oversell in **3 out of 3** runs.

**Predict first:**
- Will it oversell at 50 VUs? At 2,000? By roughly how many?
- Will wrapping the logic in a DB transaction fix it?

**Design questions:**
1. Draw the exact **timeline of two requests** that produces an oversell. Mark each read, write, and commit.
2. What is your database's **default isolation level**? Is your naive code even inside a transaction?
3. Wrap it in a transaction and rerun. Did it fix the oversell? Explain the result with your timeline.
4. Why does the in-process lock you used in `Concurrency/TicketMaster` stop working here? Prove it by adding one and running 2 instances.
5. Does the per-user limit (I3) break the same way, or differently? Can one user break it alone?

**Search:** `read-modify-write race`, `check-then-act`, `lost update anomaly`, `write skew`, `Postgres transaction isolation levels`, `Postgres default isolation level`.

**Self-evaluation:**
- 🟢 You can name the anomaly behind the oversell and draw it.
- 🟢 You know which anomaly breaks I3, and whether it's the same anomaly.
- 🔴 You "fixed" it with an in-memory lock and only tested one instance.

<details><summary>Hint (only if you can't make it oversell)</summary>
Add a short sleep between the read and the write, then run with just 2 VUs. Once you see it, remove the sleep and increase the VUs.
</details>

---

## Level 1: Stop the overselling

**Incident:** The first test sale oversold by 37 tickets. Fix it at the database level, across all instances.

**Done when:**
- [ ] I1 and I3 pass at **2,000 VUs, 5 runs in a row**, with **2+ app instances**.
- [ ] You implemented **at least 3 strategies**, switchable with a config flag. Benchmark each one.
- [ ] You set your p99 target for the rest of the project, and wrote down why.

**Strategies to research** (understand each one before picking any):
- Pessimistic row locking
- Optimistic concurrency control (version column / compare-and-set)
- A single conditional atomic `UPDATE`
- Raising the isolation level to `SERIALIZABLE`
- (Bonus) Advisory locks

**Predict first:** Rank the strategies by throughput for *this* workload before you measure.

**Design questions:**
1. For each strategy: when two transactions collide, what does the DB actually do? Who waits, who fails, and who must retry?
2. Under optimistic locking, what happens to success rate and throughput as contention rises? What does your code do on a conflict, and how many times?
3. Under `SERIALIZABLE` in Postgres, what error do you get? What must the application do with it?
4. Does `REPEATABLE READ` prevent the oversell in Postgres? Test it. Would the answer be the same in MySQL/InnoDB? Why might two databases using the same isolation-level name behave differently?
5. **Where is the payment call** relative to the transaction? What happens to lock hold time, and to throughput, if you charge *inside* the transaction? Measure it with payment `LATENCY_MS=200`.
6. If you move the charge *outside* the transaction, what new failure did you just create? Write it down. It comes back in L3.
7. How does the per-user limit check interact with your chosen strategy? Can it still be bypassed?

**Search:** `SELECT FOR UPDATE`, `optimistic locking version column`, `compare and swap SQL`, `UPDATE ... RETURNING`, `Postgres serialization failure 40001`, `Postgres REPEATABLE READ vs MySQL REPEATABLE READ`, `pg_locks`, `pg_stat_activity wait_event`, `lock contention`.

**Measure:** For each strategy: throughput, p50/p99, conflict/retry count, error count, all at 2,000 VUs. Then rerun with payment latency 200ms.

**Self-evaluation:**
- 🟢 Your ranking comes with numbers, and you can explain each one by **mechanism** (who waits, for how long).
- 🟢 You can describe a different workload (low contention, long transactions, many events) where your ranking flips.
- 🔴 One strategy is "obviously best" and you can't say when it would be worse.
- 🔴 Your retry loop for optimistic locking has no limit.

<details><summary>Hint (strategy ranking feels random)</summary>
Look at lock hold time, not lock type. What runs while the lock is held?
</details>

---

## Level 2: The hot row

**Incident:** Stadium tour: **100,000 tickets** and a sustained **5,000 purchase attempts/sec** for minutes. DB CPU is fine, but p99 explodes, the connection pool runs out, and requests time out. Every request is queueing on one row.

**Done when:**
- [ ] You **diagnosed** the bottleneck with evidence (wait events, pool metrics) *before* changing anything.
- [ ] p99 meets your L1 target at 5,000 RPS. I1-I3 still pass.
- [ ] A **reconciliation job** detects drift between any two stores that track "remaining".
- [ ] You **caused drift on purpose** (killed a process mid-request) and the reconciler caught it.

**Predict first:** Where is time spent: CPU, disk, lock waits, or waiting for a pool connection?

**Design questions:**
1. What are your connection pool size, queue behavior, and timeout? What happens to a request when the pool is empty? How should pool size relate to DB cores? (Search the HikariCP article.)
2. **Option A: split the counter.** How do you split it? How do you know when you're sold out? What does it cost in fairness or accuracy near the end?
3. **Option B: put a gate in Redis.** Which Redis commands are atomic? Is "GET, check, then DECR" atomic? What about a Lua script? What about qty > 1 and the per-user limit in the same atomic step?
4. There are now **two places** that know "remaining". Which is the **source of truth**? Write one sentence and commit to it.
5. List **every crash point** between "Redis decremented" and "DB order committed". What is the state of the world at each one?
6. Redis restarts and loses its data. What happens to your sale? Which Redis persistence settings matter here?
7. What does your reconciler compare, how often, and what does it do when they disagree: alert, auto-fix, or stop the sale?

**Search:** `hot row contention`, `sharded counter`, `HikariCP About Pool Sizing`, `Little's Law`, `Redis DECRBY atomic`, `Redis EVAL Lua atomicity`, `Redis persistence RDB AOF`, `dual write problem`, `reconciliation job`.

**Self-evaluation:**
- 🟢 You have a before/after graph, and the "before" graph shows *where* time went.
- 🟢 You can say exactly how wrong your system can be at each crash point, and for how long.
- 🔴 Redis and the DB both act as the source of truth.

<details><summary>Hint (pool math)</summary>
In-flight requests = arrival rate × time each one holds a connection. Plug in your numbers.
</details>

---

## Level 3: The double charge

**Incident:** The payment provider has a bad day: `JITTER_MS=2000`, `HANG_RATE=0.05`, `CHARGE_THEN_DROP_RATE=0.05`. Your client has a 1s timeout and retries up to 3 times. Support tickets pile up: *"I was charged twice"* and *"I was charged but have no ticket."*

**Setup:** Add client retries to the k6 script, using the **same logical purchase** on each retry.

**Done when:**
- [ ] Every logical purchase → **at most one** successful charge and **at most one** order, even with client retries, concurrent duplicates, server `kill -9`, and payment timeouts.
- [ ] No "charged but no ticket" case is left unresolved. Each one is either completed or refunded by an automated process.
- [ ] You drew the **order state machine** (states, transitions, and who triggers each).

**Predict first:** With these knobs, how many double charges per 1,000 purchases in your current system?

**Design questions:**
1. Who generates the idempotency key, client or server? What breaks with each choice?
2. Where do you store the key? What do you store with it: just "seen", or the full response? For how long?
3. Two requests with the **same key** arrive at the **same millisecond** on **different instances**. Walk through your design. Then test it.
4. The same key arrives with a **different body**. What should you return?
5. A charge call timed out. **Did the charge happen?** How can you find out? What must the payment service support? (You may extend your fake one. Decide what a real provider would offer.)
6. The server crashed after charging but before writing the order. Who notices, and when? What process completes or reverses it?
7. What new states does your order state machine need? Which transitions must be atomic with what?
8. Is retrying a 402 decline correct? Which failures are safe to retry?

**Search:** `idempotency key`, `Stripe idempotency blog`, `Amazon Builders' Library making retries safe with idempotent APIs`, `at-least-once plus idempotency`, `order state machine`, `payment reconciliation`, `compensating transaction`.

**Chaos test:** Run under load and `kill -9` a random app instance every 10s. Then run the checker.

**Self-evaluation:**
- 🟢 I2 passes under the chaos test, 5 runs in a row.
- 🟢 You can explain what happens if the **idempotency store** is down. Fail open or fail closed?
- 🔴 Your fix is "don't retry". The client and the network will retry anyway.

<details><summary>Hint (concurrent duplicates)</summary>
Two requests with the same key must not both reach "charge". What DB feature can stop the second one from even starting?
</details>

---

## Level 4: Abandoned carts and bots

**Incident:** You switch to **reserve → pay → confirm**, with a 10-minute hold, so users have time to pay. Bots grab all 500 holds within a second and never pay. Real fans see "sold out" while the seats sit held.

**Done when:**
- [ ] New endpoints: `POST /reserve` → hold, `POST /reservations/{id}/pay` → confirm.
- [ ] Holds expire after `T` (make it 30s for testing) and return to the pool within `T + ε`. Measure ε.
- [ ] Per-user hold limit enforced across instances.
- [ ] I4 passes, including a **race test**: payment arrives at exactly the moment the hold expires.

**Predict first:** If you expire holds with a job that runs every N seconds, what's the worst-case extra time a seat stays unavailable?

**Design questions:**
1. List 4 ways to expire holds: periodic scanner, Redis TTL + keyspace notifications, delay queue, lazy expiry at read time. What is the cost and the failure mode of each?
2. **The race:** a user pays at `T−0.1s` while expiry runs at `T`. Who wins? Is the winner decided **by the database**, or by luck?
3. Payment succeeds *after* the hold expired and the seat was given to someone else. What now? Who is told, and what is refunded?
4. Are Redis keyspace notifications **reliable**? What happens to them if no subscriber is connected? (Look it up. Don't guess.)
5. What does `GET /events/{id}` show now: available only, or available + held? What will users think?
6. How do you make holding seats expensive for bots but cheap for humans?

**Search:** `Redis keyspace notifications delivery guarantee`, `delayed queue`, `Postgres SKIP LOCKED`, `lease expiration race`, `compare-and-set on status`, `compensating transaction refund`.

**Self-evaluation:**
- 🟢 The race test runs 1,000 times with 0 I4 violations.
- 🟢 You can state your ε, and what makes it larger.
- 🔴 Expiry correctness depends on a timer firing exactly on time.

---

## Level 5: The stampede (waiting room)

**Incident:** The artist's biggest tour: **200,000 users** in the first 10 seconds. Your purchase path is correct, but the app servers, LB, and DB connections are overwhelmed. Random users get 503s, and people who refresh more have a better chance.

**Done when:**
- [ ] Users join a **queue** at T0, see their **position**, and are **admitted** at rate `R`.
- [ ] Only admitted users can call reserve/purchase, enforced by a token **verified without a DB lookup**.
- [ ] At 10× load, purchase-path error rate is ~0, and the waiting-room endpoint's p99 stays low.
- [ ] Refreshing or opening 50 tabs **doesn't improve** your position.

**Predict first:** What is the cheapest request your system can possibly serve? How many of those per second can one instance handle?

**Design questions:**
1. What's on the waiting-room hot path? How do you make it as cheap as possible? Can any of it be served without touching your app at all?
2. How do you assign queue positions safely under concurrency, across instances? Which data structure fits?
3. How do clients learn their position: polling, long-polling, SSE, or WebSockets? Work out the requests/sec for 200k users for each.
4. How is the admission token built so any instance can verify it with no DB? What stops reuse, sharing, or forgery?
5. How do you choose `R`? A fixed number, or adaptive based on downstream health? What signal would you use?
6. FIFO by arrival vs a randomized lottery among everyone who joined before 10:00. Which is fairer for someone on slow mobile internet? Which is easier for bots to exploit?
7. What happens to a user's spot if they close the tab and come back?

**Search:** `virtual waiting room`, `AWS Virtual Waiting Room solution`, `Redis sorted set ZADD ZRANK`, `HMAC signed token`, `JWT`, `admission control`, `load shedding`, `Little's Law`, `token bucket`.

**Self-evaluation:**
- 🟢 You have a graph of offered load against purchase-path error rate, and it stays flat.
- 🟢 You can explain how your system degrades if Redis (or your queue store) dies mid-sale.
- 🔴 The waiting room itself becomes the new hot path and falls over.

---

## Level 6: The lost event

**Incident:** After a purchase, you publish `TicketSold` to a broker. The email service sends the ticket and analytics counts revenue. Some customers paid but never got an email. Some got an email for an order that was rolled back.

> You touched this in `../Master-System-Design/Kafka/1-order-checkout-service` (OutboxHandler). This time, **prove it holds under chaos**.

**Done when:**
- [ ] I5 passes while you randomly `kill -9` the app, the relay, and the broker during load.
- [ ] Email consumer is idempotent: duplicates in, exactly one email out (log line) per order.
- [ ] You implemented **two** relay approaches: polling and log-based CDC. Compare their latency and operational cost.

**Predict first:** List every crash point between "DB commit" and "event published". Which one causes "email for a rolled-back order"?

**Design questions:**
1. Publish **before** commit vs **after** commit: what breaks in each case?
2. Why not a distributed transaction (2PC) across the DB and the broker? What would the broker need to support it, and what would it cost?
3. Outbox: how does the relay know what's already been published? What happens if it crashes after publishing but before marking the row as sent?
4. What ordering guarantees do consumers get? Do they need any?
5. Polling relay vs CDC from the write-ahead log: latency, load on the DB, operational complexity, and failure modes.
6. How does the consumer dedupe? Where does it store what it has seen, and in the same transaction as what?
7. How big does the outbox table get, and who cleans it up?

**Search:** `dual write problem`, `transactional outbox pattern`, `inbox pattern`, `Debezium Postgres connector`, `Postgres logical replication`, `two-phase commit`, `exactly-once semantics Kafka`.

**Self-evaluation:**
- 🟢 I5 holds through 5 chaos runs.
- 🟢 You can explain why "exactly-once delivery" isn't what you built, and what you built instead.
- 🔴 Your relay marks rows as sent *before* the broker acknowledges them.

---

## Bonus level: Pick your seat

**Incident:** Seated venue. A group of 4 wants **4 adjacent seats**, all or nothing. Two groups overlap on seats and both get stuck.

**Questions:** You solved deadlock in the JVM by locking in sorted order. Does that work in Postgres? What does Postgres do when it detects a deadlock? How do you show a live seat map to 200k users without reading the seats table 200k times? Search: `Postgres deadlock detection`, `lock ordering`, `SELECT FOR UPDATE NOWAIT / SKIP LOCKED`, `seat map caching`.

---

## Graduation: answer these out loud in 2 minutes each

1. How do you prevent overselling in a flash sale? Compare 3 approaches **with your numbers**.
2. Your Redis counter says 12 left and the DB says 9. Which is right, how do you know, and what happens next?
3. A client retried a purchase after a timeout. Walk through how you guarantee one charge.
4. Design a waiting room for 1M users. What's the most expensive thing on its hot path?
5. Why not use 2PC between Postgres and Kafka?
6. What would change if this ran in two regions?

## Taking it further (if you want to build it fully)
Seat map UI with live availability · multiple events and organizers · resale marketplace with escrow · fraud scoring for bots · organizer analytics fed by your outbox events · multi-region with the sale pinned to one region.
