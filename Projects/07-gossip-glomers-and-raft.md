# 07: Gossip Glomers + Raft (Distributed Algorithms)

> Projects 01-06 used a database or a coordinator as "the one place that decides". This project removes that. Nodes only have messages, which may be delayed, lost, or reordered, and the network partitions. Here you learn how agreement is reached without a central authority.

Two parts:
- **Part A, Gossip Glomers:** short puzzles (a weekend each, or less) by Fly.io, tested by **Maelstrom**, a harness built on Jepsen that injects partitions and checks correctness. Good for warm-ups, and good for coding practice.
- **Part B, Raft:** the boss level. Implement a consensus algorithm, then a fault-tolerant KV store on top.

---

## Setup

- **Gossip Glomers:** `https://fly.io/dist-sys/`. Maelstrom: `https://github.com/jepsen-io/maelstrom`. Maelstrom needs a JVM plus Graphviz and gnuplot. There's an official Go node library. In Java you read and write JSON lines on stdin/stdout yourself, which is good practice.
- **Raft:** paper *In Search of an Understandable Consensus Algorithm (Extended Version)*, `https://raft.github.io/` (has an interactive visualization). MIT 6.5840 Distributed Systems labs: `https://pdos.csail.mit.edu/6.824/` (Go, with a test suite that injects failures). Lab numbering changes from year to year. Look for the **Raft lab** and the **fault-tolerant KV on Raft** lab.
- **Rule:** MIT asks you **not to publish lab solutions publicly**. Keep that repo private.

### Results table: `docs/results.md`

| Challenge | Valid? | msgs-per-op | median latency | max latency | Nodes / partitions | Notes |
|---|---|---|---|---|---|---|

---

# Part A: Gossip Glomers

For each challenge: **read the spec on the site**, write your prediction and design answers in `docs/`, then build. Maelstrom prints `Everything looks good! ヽ(‘ー`)ノ` when your system is correct. That's the minimum. The listed performance targets are the real test.

### A1. Echo
**Goal:** Learn the Maelstrom message format and node lifecycle.
**Questions:** What does `init` give a node? What happens if you never reply? How does Maelstrom match replies to requests?
**Done when:** `valid: true`, and you understand every field in the message.

### A2. Unique ID generation
**Goal:** Every node generates globally unique IDs, totally available, **even during network partitions**.
**Questions:**
1. Can you coordinate with other nodes during a partition? Then what must uniqueness come from?
2. Compare UUIDs, node-ID + counter, and timestamp + node-ID + sequence (Snowflake-style). Which are sortable? Which survive restarts? What happens with clock skew?
3. What would break if a node restarted and its counter reset?

**Search:** `Snowflake ID`, `ULID`, `UUIDv7`, `clock skew`.
**Done when:** Valid under partitions. You can explain why your IDs can't collide.

### A3. Broadcast (a → e)
**Goal:** Gossip a set of values to all nodes. (a) single node, (b) multi-node, (c) **with partitions**, (d)/(e) efficient, with message-count and latency targets given on the challenge page.
**Questions:**
1. (b) Sending to every neighbor on every message: how many messages per broadcast? What's the topology, and are you required to use the one Maelstrom gives you?
2. (c) A partition drops messages. How does a node know a neighbor got a value? Retry until ack, or periodic anti-entropy (sync whole sets)? Cost of each?
3. (d) Targets on messages per operation **and** latency pull in opposite directions. Which **topology** (tree, grid, hub-and-spoke, random) gives low hops *and* low fan-out?
4. (e) Even fewer messages allowed, more latency allowed. What can you **batch**?
5. What happens to your hub-and-spoke if the hub is partitioned?

**Search:** `gossip protocol`, `epidemic broadcast`, `anti-entropy`, `spanning tree broadcast`, `message batching`, `fan-out vs hop count`.
**Self-evaluation:** 🟢 Each sub-challenge meets its targets, and your `results.md` shows msgs-per-op and latency for each design you tried. 🔴 You meet (d) but fail (c) with the same code.

### A4. Grow-only counter
**Goal:** A distributed counter using Maelstrom's **sequentially consistent** KV service.
**Questions:**
1. What does "sequentially consistent" mean, and how is it weaker than linearizable? Can a read return a stale value?
2. Two nodes add at the same time with read-modify-write on one key. What happens? How does compare-and-swap help, and what does it cost under contention?
3. Can you avoid contention entirely by giving each node its own key? How does a reader get the total?
4. How do you force a fresh read from a sequentially consistent store? (A trick exists. Find it.)

**Search:** `sequential consistency vs linearizability`, `CRDT G-Counter`, `compare and swap retry`, `Jepsen consistency models`.
**Done when:** Valid under partitions. You can explain the difference between the two consistency models with an example.

### A5. Kafka-style log (a → c)
**Goal:** Replicated append-only logs with offsets and committed offsets, using Maelstrom's linearizable and sequential KV stores. Compare it with your project 06.
**Questions:**
1. How do you assign **monotonic offsets** per key across nodes without a central server? Which KV store do you need for that, and why?
2. What must be linearizable, and what can be weaker? Where can you save round-trips?
3. (c) Fewer messages: can one node "own" each key? What happens when the owner dies?

**Search:** `linearizable compare-and-swap`, `per-key leader`, `Jepsen Kafka analysis`.

### A6. Totally-available transactions (a → c)
**Goal:** A transactional KV store that stays available during partitions: (a) single node, (b) read uncommitted, (c) read committed.
**Questions:**
1. What are the anomalies G0, G1a, G1b, G1c? Which does each isolation level prevent?
2. Why can **read committed** be totally available, while serializability can't be?
3. How do you replicate writes so that no one ever reads an aborted or intermediate value?

**Search:** `Adya isolation levels G0 G1a G1b`, `Jepsen consistency models map`, `highly available transactions Bailis`, `dirty write dirty read`.

---

# Part B: Raft (the boss level)

Use the MIT lab test suite. It's harsh: it drops messages, reorders them, partitions nodes, and crashes them. **A test that passes once means nothing.** Run each test hundreds of times.

### Before you code
- Read the Raft paper **twice**. Print **Figure 2** and follow it exactly. Most bugs come from deviating from it.
- Read the *Students' Guide to Raft* (search for it). It lists the bugs nearly everyone hits.
- Write a script that runs each test **N times in parallel** and saves logs from failures. Search: `6.824 test runner script parallel` (or write your own; it's good practice).
- Add structured debug logging (term, node, role, event) from day one. You'll need it.

### B1. Leader election
**Done when:** Election tests pass **500/500**.
**Design questions:**
1. What is a **term**, and why does every message carry one?
2. Why are election timeouts **randomized**? What happens if they aren't? What happens if the range is too small?
3. Why can a node vote only **once per term**? What must it remember to guarantee that across a crash?
4. A node with an old term receives a message with a newer term. What must it do, regardless of its role?
5. How do the election timeout and the heartbeat interval relate?

### B2. Log replication
**Done when:** Log/agreement tests pass **500/500**.
**Design questions:**
1. How does the leader find the point where a follower's log diverges from its own?
2. Why does checking `prevLogIndex` and `prevLogTerm` guarantee the **Log Matching Property**?
3. When is an entry **committed**? When can it be applied to the state machine?
4. **Figure 8:** why can't a leader commit an entry from a **previous term** just by counting replicas? Draw the scenario until you understand it.
5. Election restriction: which candidates are allowed to win, and why does that protect committed entries?
6. Backing up one entry at a time is slow for followers that are far behind. How can you speed it up? (The paper and guide hint at it.)

### B3. Persistence
**Done when:** Persistence tests (crash and restart) pass **500/500**.
**Design questions:**
1. Which state must be on stable storage **before** a node replies to an RPC? What breaks if you persist it *after* replying?
2. Which state is fine to lose on a crash, and why?

### B4. Snapshots / log compaction
**Done when:** Snapshot tests pass **500/500**.
**Design questions:**
1. After trimming the log, how do you translate between log indexes and array positions? Where do off-by-one bugs come from?
2. A follower needs entries the leader has already discarded. What does the leader send instead?
3. How does snapshotting interact with what you persist in B3?

### B5. Fault-tolerant KV store on Raft
**Done when:** KV-on-Raft tests pass **200/200**, including with snapshots.
**Design questions:**
1. What does **linearizable** mean for a client of your KV store? How does a client-visible stale read happen?
2. A client sends `Put`, the leader commits it, then crashes before replying. The client retries on the new leader. How do you avoid applying the `Put` twice? (Project 01 L3 again, inside consensus.)
3. Can a leader serve **reads** without going through the log? What could go wrong? (Search: `ReadIndex`, `leader lease`.)
4. How does a client find the current leader?

**Search (all of Part B):** `Raft paper extended version`, `Students' Guide to Raft`, `Raft Figure 8`, `Raft visualization`, `ReadIndex Raft`, `leader lease clock`, `linearizability`, `etcd raft library` (read it **after** you finish).

### Self-evaluation (Raft)
- 🟢 Every test passes hundreds of times, and you can debug a failure from logs alone.
- 🟢 You can explain Figure 8 on a whiteboard without notes.
- 🔴 "It passes most of the time." That means it has a bug.
- 🔴 You added `sleep()`s to make a test pass.

---

## Graduation: answer out loud
1. Why is consensus hard? What does the FLP result say, and how does Raft get around it in practice?
2. Walk through a Raft leader election, including a split vote.
3. When is a Raft log entry committed? Explain Figure 8.
4. What must Raft persist, and why?
5. How do you build a linearizable KV store on Raft, including client retries?
6. Gossip vs consensus: when do you need which?
7. Sequential consistency vs linearizability vs read committed. Give an example where each matters.

## Taking it further
Use your Raft as the coordinator in project 06 (Mini Kafka L4) or project 02 (Redis failover) · implement Raft membership changes · run it across real machines with Toxiproxy · write a Jepsen-style checker for your KV store · read the etcd and TiKV Raft implementations and compare.
