# 10: Stock Exchange Matching Engine

> The most fun project here. It runs at microsecond speed, and it teaches a way of thinking you won't get from web services: one thread, no locks, no randomness, every result reproducible. It also builds on your `Concurrency/` work, where you learned when locks hurt.

---

## The story

You're building **Kestrel Exchange** (fictional). Traders send orders: "buy 100 shares of ACME at ₹500 or less". Your engine matches buyers with sellers.

The rules are strict:
- **Fast.** Traders measure you in **microseconds**.
- **Fair.** At the same price, whoever came first gets filled first. No exceptions.
- **Never lose an order and never reorder one.** Every action can be audited and replayed.
- **Survive market open.** At 9:15, traffic jumps 50× in one second.

## How it works (plain words)

Each stock has an **order book** with two lists:
- **Bids** (buyers), best (highest) price first.
- **Asks** (sellers), best (lowest) price first.

When a new buy order arrives, the engine checks: is there a seller at this price or lower? If yes, they **trade** (a "fill"). If the order isn't fully filled, the rest **waits in the book**. Sell orders work the same way in reverse. Within the same price, the oldest order goes first. This rule is **price-time priority**.

## What you'll learn

Order book data structures · price-time priority · why one thread can beat many · CPU caches and "mechanical sympathy" · ring buffers · removing allocation and GC pauses · measuring microsecond latency correctly · event sourcing · determinism · deterministic replay · sequencers · primary/backup replication · market data fan-out · gap recovery · conflation · pre-trade risk checks · kill switches.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Order book** | All waiting buy and sell orders for one stock. |
| **Bid / Ask** | A buy order / a sell order. |
| **Best bid / best ask** | Highest buy price / lowest sell price right now. The gap between them is the **spread**. |
| **Limit order** | "Buy/sell at this price or better." |
| **Market order** | "Buy/sell now at whatever price is available." |
| **Fill / partial fill** | A trade. Partial = only part of the quantity traded. |
| **Price-time priority** | Better price first; same price → earlier order first. |
| **Sequencer** | One component that stamps every incoming event with a number (1, 2, 3...), so everyone agrees on the order. |
| **Event sourcing** | Saving every input event, so the state can be rebuilt by replaying them. |
| **Deterministic** | Same inputs in the same order → exactly the same outputs, every time. |
| **Ring buffer** | A fixed-size circular array used as a very fast queue between threads. |
| **Market data** | The public feed of trades and prices sent to everyone. |

## Scope

| In | Out |
|---|---|
| Matching engine, order gateway, journal, replication, market data, risk checks | Real money, regulation, clearing and settlement |
| Benchmarks in microseconds | A trading UI (optional; AI can build it) |

**Tech:** **Java** is a great fit (the well-known LMAX exchange is Java). Go or Rust also work. Use JMH for microbenchmarks and HdrHistogram for latency. Use plain TCP; UDP multicast in L4 if you're curious.

---

## Base system spec

### Functional requirements
1. Order types: **limit buy/sell** and **cancel**. (Later: market orders, "immediate-or-cancel".)
2. Every order gets a response: `ACK`, then zero or more `FILL`s, or `CANCELLED`, or `REJECTED` with a reason.
3. Both sides of every trade are told.
4. Public **market data**: every trade, and changes to the best bid/ask.

### Order message

```
{clientOrderId, traderId, symbol, side: BUY|SELL, type: LIMIT, price, qty}
{cancel: clientOrderId, traderId}
```

> **Price is an integer number of "ticks"** (for example paise), not a floating-point number. Why? Answer it in L0.

### Protocol: you design it
Pick a binary format or a simple text line format, then document it in `docs/protocol.md`. Think about the size of each message, because you'll care in L1.

### The oracle (your referee)

Write a **deliberately simple, slow, obviously correct** matcher: plain sorted lists, no tricks, about 100 lines. Feed the **same input stream** to the oracle and to your fast engine. **Every output must match exactly.** This is called **differential testing**, and it's how you'll catch bugs in every level.

Invariants the oracle also checks:

| ID | Invariant |
|---|---|
| E1 | No trade happens at a worse price than either order's limit |
| E2 | No order is filled for more than its quantity |
| E3 | Price-time priority is respected |
| E4 | Bought quantity = sold quantity for every trade |
| E5 (L2+) | After replay, the book is **identical** (same checksum) |

### Load generator
A random order stream with modes: `normal`, `market-open burst` (50×), `hot symbol` (one stock gets 90% of the traffic), and `cancel-heavy` (in real markets, most orders are cancelled before they trade). Use a **fixed random seed**, so every run is repeatable.

### Results table: `docs/results.md`

| Level | Design | Orders/sec | p50 (µs) | p99 (µs) | p99.9 (µs) | max (µs) | GC pauses | Oracle diff |
|---|---|---|---|---|---|---|---|---|

---

## Level 0: Order book and matching

**Incident:** None. Build the book and the matching logic for one symbol: limit orders and cancels.

**Done when:**
- [ ] **Oracle diff = 0** on 1M random orders (seeded).
- [ ] Unit tests for: partial fills, an order that crosses several price levels, same-price queue order, and cancelling a partly filled order.
- [ ] Add, cancel, and match are each fast. You know the time complexity of each.

**Predict first:** Which operation will happen most often in a real market? Which should you optimize first?

**Design questions:**
1. Why are prices integers and not doubles? What could go wrong with doubles?
2. Which data structure finds the **best price** quickly? Which keeps the **time order within one price**?
3. How do you **cancel** an order by ID quickly, without searching the whole book?
4. A buy order's price crosses 5 price levels of sellers. Walk through what happens, step by step.
5. What happens to an order with quantity 0, a negative price, or an unknown symbol?

**Search:** `limit order book data structure`, `price-time priority FIFO`, `order book implementation tree map vs array price levels`, `intrusive linked list`, `differential testing`.

**Self-evaluation:**
- 🟢 Oracle diff is 0, and you have a test for each tricky case above.
- 🔴 Cancel scans the book.

<details><summary>Hint (data structures)</summary>
Think of three things together: something sorted by price, a queue at each price, and a map from order ID to where that order lives.
</details>

---

## Level 1: Speed, where locks lose

**Incident:** A colleague makes the engine "faster" with a thread pool and a lock on the order book. With more threads it gets **slower**, and p99.9 latency is terrible.

**Done when:**
- [ ] Two designs benchmarked: (a) thread pool + locks, (b) **one thread** that owns the book, fed by a queue.
- [ ] Latency measured correctly in **microseconds** with HdrHistogram: p50, p99, p99.9, max.
- [ ] Allocation on the hot path removed or reduced. GC pauses measured before and after.
- [ ] A **ring buffer** between the network thread and the matching thread. Compare it with a standard blocking queue.
- [ ] Throughput target set and reached. Write down what limits it now.

**Predict first:** How many orders/sec can one thread match? 100k? 1M? 10M?

**Design questions:**
1. Why can one thread beat many threads here? What does a lock cost when many threads want it?
2. Where does time actually go: locks, CPU cache misses, memory allocation, system calls? Use a profiler.
3. What is a **CPU cache line**? What is **false sharing**, and how can it slow down two threads that don't share any data?
4. Why is the LMAX Disruptor fast? Look at pre-allocation, no locks, and batching.
5. How do you measure microsecond latency without your measurement changing the result? What does JIT warm-up do to your first numbers?
6. Coordinated omission again (README): does your load generator hide pauses?

**Search:** `LMAX architecture Martin Fowler`, `LMAX Disruptor`, `mechanical sympathy`, `false sharing cache line padding`, `object pooling`, `GC pause`, `JMH`, `HdrHistogram`, `coordinated omission`, `async-profiler allocation profiling`.

**Self-evaluation:**
- 🟢 You can explain the gap between designs (a) and (b) with profiler evidence.
- 🔴 You only report average latency.

---

## Level 2: The crash

**Incident:** The engine crashes at 10:15. The order book lived in memory, so it's gone. Every trader's waiting orders disappeared. The market is halted.

**Done when:**
- [ ] Every input event is **numbered and written to a journal** before it's processed.
- [ ] On restart, replaying the journal rebuilds an **identical** book (E5: same checksum).
- [ ] **Snapshots** for faster restart. Recovery time measured for 100M events with and without snapshots.
- [ ] A test that replays the same journal 10 times and gets the same checksum every time.

**Predict first:** How long will replaying 100M events take?

**Design questions:**
1. Journal the **inputs** (orders) or the **outputs** (fills)? What does each let you do?
2. Replay only works if the engine is **deterministic**. List everything that can break that: reading the clock, random numbers, hash map iteration order, multiple threads...
3. If the engine needs a timestamp, where should it come from so replay still gives the same result?
4. When can you tell the trader `ACK`: before or after the journal write is durable on disk? What does each choice risk?
5. `fsync` costs time (project 02 L2). How do you keep latency low and still be durable? (Search: group commit.)
6. A snapshot is taken while orders keep arriving. How do you make sure it matches an exact event number?

**Search:** `event sourcing`, `command sourcing`, `deterministic state machine`, `journaling`, `snapshot and replay`, `group commit`, `memory-mapped file journal`.

**Self-evaluation:**
- 🟢 E5 passes: replay checksum = live checksum, 10/10.
- 🔴 The engine calls `System.currentTimeMillis()` (or `time.Now()`) inside matching.

---

## Level 3: Hot standby

**Incident:** Recovery takes 4 minutes. Regulators say a halt must be under **1 second**, with **zero lost orders**.

**Done when:**
- [ ] A **primary** and a **backup** engine both consume the **same numbered input stream**.
- [ ] The backup processes every event live. Its book matches the primary's continuously (compare checksums every N events).
- [ ] Kill the primary: the backup takes over in under 1 second. No acknowledged order is lost, and there are **no duplicate fills** sent to traders.
- [ ] No moment where **both** act as primary. Prove it with a test.

**Predict first:** How much latency does waiting for the backup add to each ACK?

**Design questions:**
1. What does a **sequencer** do, and why does it sit in front of **both** engines?
2. If both engines get the same inputs in the same order, why will they produce the same outputs? What breaks that guarantee? (Your L2 list.)
3. Who sends the fills to traders, primary or backup? How do you avoid sending them twice after a failover?
4. How does the backup know the primary is dead and not just slow? What stops two primaries (split brain, project 02 L4)?
5. Should you ACK only after the backup has the event? What's the latency cost, and what's the safety gain?

**Search:** `state machine replication`, `primary-backup replication`, `sequencer architecture exchange`, `Aeron Cluster`, `split brain`, `fencing`, `heartbeat failure detection`.

**Self-evaluation:**
- 🟢 Kill-primary test, 20 runs: 0 lost orders, 0 duplicate fills, checksums equal.
- 🔴 Failover requires a human.

---

## Level 4: Market data for 10,000 subscribers

**Incident:** 10,000 subscribers want every trade and every price change. One subscriber on a slow network falls behind, and the engine **slows down for everyone**. At market open, the feed falls minutes behind.

**Done when:**
- [ ] Market data feed: trades + best bid/ask updates, each with a **sequence number**.
- [ ] The matching thread **never waits** on publishing.
- [ ] A subscriber that misses messages **detects the gap** and recovers (re-send or snapshot).
- [ ] Slow subscribers get **conflated** data (only the latest price) where that's allowed, but never conflated trades.
- [ ] Latency from engine to subscriber measured at p99, during a market-open burst.

**Predict first:** If publishing happens on the matching thread, what does one slow subscriber do to matching latency?

**Design questions:**
1. Which thread should send data to subscriber sockets? What sits between that thread and the matching thread?
2. **Conflation:** which data can be merged (latest price), and which can't (every trade)? Why?
3. Gap recovery: snapshot + incremental updates. Compare it with project 04 L1. Is it the same problem?
4. Real exchanges often use **UDP multicast**. Why? What do they give up, and how do they recover lost packets?
5. **Fairness:** subscriber #1 gets data before subscriber #10,000. In finance, does that matter? How would you make it fairer?

**Search:** `market data feed`, `UDP multicast`, `sequence gap recovery`, `conflation`, `snapshot recovery feed`, `fan-out`, `slow consumer`.

**Self-evaluation:**
- 🟢 With one deliberately slow subscriber, matching latency doesn't change.
- 🔴 Trades get conflated.

---

## Level 5: Fat fingers and market open

**Incident:** A buggy trading bot sends **1M sell orders in 2 seconds**. Prices crash, and real traders lose money. Separately, at 9:15 traffic jumps 50×, and latency for everyone climbs.

**Done when:**
- [ ] **Pre-trade risk checks** per trader: max order size, max position, max orders per second. Measure the latency they add.
- [ ] A **kill switch** per trader: instantly cancels all their orders and blocks new ones.
- [ ] During a 50× burst, p99.9 stays under your target, or excess input is throttled **fairly per trader**.
- [ ] (Stretch) An **opening auction**: collect orders before open, then compute a single opening price.

**Predict first:** Where should risk checks run so they don't slow down matching?

**Design questions:**
1. Run risk checks inside the matching thread, or before the sequencer? What does each mean for determinism and latency?
2. Throttling: per trader or global? What happens to excess orders: rejected or queued?
3. Kill switch: what must happen, in what order, and how fast?
4. Why do exchanges sometimes **halt trading** when prices move too fast? (Search: trading circuit breakers.)
5. Opening auction: how do you find the one price that trades the most shares?

**Search:** `pre-trade risk checks`, `fat finger trade`, `kill switch trading`, `Knight Capital 2012 incident` (read the postmortem), `opening auction call auction`, `trading halt circuit breaker`.

**Self-evaluation:**
- 🟢 The fat-finger scenario is stopped by your checks within a bound you measured.
- 🔴 Risk checks make matching non-deterministic.

---

## Stretch levels
- **Many symbols:** shard by symbol, with one matching thread per group of symbols. How do you handle a hot symbol?
- **More order types:** market, immediate-or-cancel, fill-or-kill, stop orders.
- **FIX protocol:** the real industry message format.
- **Trading bots game:** let friends write bots that compete on your exchange. Fun and a good load test.

## Graduation: answer out loud
1. Explain price-time priority, and how your order book data structures support it.
2. Why is a single-threaded engine faster here? When would it not be?
3. What is event sourcing, and what must be true for deterministic replay?
4. How do the primary and backup stay identical? What happens on failover?
5. How do you handle slow market data subscribers?
6. What went wrong at Knight Capital, and which of your levels would have caught it?

## Taking it further
A live web UI with an order book depth chart · a bot competition with friends · a clearing system (who owes whom at end of day) · multi-symbol sharding · a blog post with your latency histograms.
