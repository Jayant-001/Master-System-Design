# 1. Time, Clocks & Causality

> **In one line:** Computers can't agree on what time it is, so instead of asking "when did this happen?" we ask "what did this event know about?"
>
> **You'll learn:** why physical clocks fail, happens-before, Lamport clocks, vector clocks, version vectors, hybrid logical clocks, and TrueTime.
>
> **Before this:** [0. The Big Picture](00-big-picture.md)

**Summary (read this when you come back after a break):**
- Physical clocks drift, jump, and disagree, so "latest timestamp wins" can pick the wrong edit.
- `A → B` (A happens-before B) means A could have influenced B. If neither `A → B` nor `B → A`, they are **concurrent**. Concurrent means "neither knew about the other", not "same moment".
- **Lamport clock:** one counter per node. Guarantees `A → B ⇒ L(A) < L(B)`, but can't detect concurrency.
- **Vector clock:** one counter per node, *for every node*. Can tell before / after / concurrent.
- **HLC:** Lamport-style causality, but the numbers stay close to real time.

---

## 1. Why physical time is a problem

### 1.1 Clocks drift
A computer's clock is a cheap quartz crystal. It runs slightly fast or slow, often by tens of parts per million. 50 ppm is about **4 seconds per day**. Two machines left alone drift apart.

### 1.2 Syncing is never exact
Machines sync with **NTP** (Network Time Protocol): ask a time server what time it is, adjust for how long the network took. Over the internet this is usually accurate to **milliseconds or tens of milliseconds**, and much worse on slow or congested networks.

### 1.3 Clocks jump
- NTP may **step** the clock backward or forward when it finds a large error.
- A virtual machine can be paused for seconds, then resume.
- **Leap seconds** add an extra second. The 2012 leap second froze or overloaded many Linux servers (Reddit was one of the sites affected). On 1 Jan 2017, a leap second caused Cloudflare's DNS service to fail because Go code computed a **negative duration** from two wall-clock readings. Go 1.9 then added *monotonic* readings to `time.Time`, partly because of this incident.

### 1.4 Wall clock vs monotonic clock

| Clock | Question it answers | Can go backwards? | Use it for |
|---|---|---|---|
| Wall clock | "What time is it?" | **Yes** | Showing times to humans, logs |
| Monotonic clock | "How much time has passed since X?" | No | Timeouts, measuring durations, rate limits |

Neither one can order events **across machines**. Each machine has its own.

### 1.5 The consequence: "latest timestamp wins" is broken

```
Real time:            10:00:01                10:00:02
Laptop A (clock +5s)  sets title = "Hello"
                      stamped 10:00:06
Laptop B (clock OK)                           sets title = "Hi"
                                              stamped 10:00:02

Rule "higher timestamp wins" → "Hello"     ✗  B edited later
```

The way out: often we don't need *real* time at all. We need to know **whether B had seen A's edit when B made its own**. That is a question about **causality**.

---

## 2. Happens-before (→)

Defined by Leslie Lamport in 1978. `A → B` means "A happened before B **and could have influenced it**".

Three rules:
1. **Same process:** if A comes before B on the same machine, `A → B`.
2. **Messages:** the *send* of a message → the *receive* of that message.
3. **Transitivity:** if `A → B` and `B → C`, then `A → C`.

If neither `A → B` nor `B → A`, then A and B are **concurrent**, written `A ‖ B`.

### 2.1 Space-time diagrams
Time flows left to right. Each line is one machine. Dots are events. Arrows between lines are messages.

```
Alice:  a1 ───── a2 ─────────────── a3
                  │ message
                  ▼
Bob:    b1 ───── b2 ───── b3
```

- `a1 → a2` (same machine)
- `a2 → b2` (send → receive)
- `a1 → b3` (chain: a1 → a2 → b2 → b3)
- `a3 ‖ b3` (no path either way)
- `a1 ‖ b1`

**How to check:** `A → B` if you can walk from A to B moving only **forward**: rightward along a line, or along a message arrow. If you can't walk A→B *or* B→A, they're concurrent.

### 2.2 The key insight
Concurrency is **not about time**. In the diagram, `a3` might happen an hour after `b3` in real time, and they'd still be concurrent: neither knew about the other. What matters is **information flow**.

**Chat analogy:** if you reply to a message, your reply happened after it, because you saw it. Two messages sent without seeing each other are concurrent, even if one was sent a minute later. This is why chat apps sometimes show replies "out of order" when the network is slow, and why Chapter 2 talks about **causal delivery**.

---

## 3. Lamport clocks: counting instead of timing

### 3.1 The rules
```text
each node keeps: time = 0

on local event (an edit, anything):
    time = time + 1

on send message m:
    time = time + 1
    attach time to m

on receive message m carrying t:
    time = max(time, t) + 1
```

Note: sending and receiving are events too, so each one moves the counter forward.

### 3.2 Walkthrough
```
Messages: e2 → f2,  f3 → g3

P1:  e1(1) ── e2(2) ─────────────────────── e3(3)
                │ sends 2
                ▼
P2:  f1(1) ──────── f2(max(1,2)+1 = 3) ── f3(4)
                                             │ sends 4
                                             ▼
P3:  g1(1) ── g2(2) ─────────────────────── g3(max(2,4)+1 = 5)
```

### 3.3 What it guarantees, and what it doesn't
- **Guarantee:** if `A → B`, then `L(A) < L(B)`. A cause always has a smaller number than its effect.
- **Not guaranteed:** `L(A) < L(B)` does **not** mean `A → B`.

| Pair | Lamport | Real relationship |
|---|---|---|
| e1, g3 | 1 < 5 | e1 → g3 |
| e3, g3 | 3 < 5 | **concurrent** |
| e2, g2 | 2 = 2 | **concurrent** |

From the numbers alone you can't tell the first row from the second. Lamport clocks **cannot detect concurrency**. Vector clocks can (section 4).

### 3.4 Total order with a tie-breaker
Two events can have the same Lamport number (e2 and g2 above). Compare the pair `(time, nodeID)` instead:

```text
function less(a, b):          # a and b are (time, node) pairs
    if a.time != b.time:
        return a.time < b.time
    return a.node < b.node    # alphabetical, just to break ties
```

Now **every node sorts all events in exactly the same order**. The order is consistent with causality (causes always come first), and concurrent events are ordered arbitrarily but identically everywhere.

### 3.5 Clocks vs timestamps
- A **clock** keeps ticking. One per node.
- A **timestamp** is the frozen value stamped on one event: `{time: 3, node: "alice"}`. It travels with the edit and never changes.

Like a token machine at a bank: the machine keeps counting; each customer keeps a printed ticket.

### 3.6 The title example, fixed
```
Alice edits "Hello"     → stamp (1, alice), sends it to Bob
Bob receives            → Bob's clock = max(0, 1) + 1 = 2
Bob edits "Hi"          → stamp (3, bob)
Compare: (1, alice) < (3, bob)  → "Hi" wins on every replica ✓
```
- If Bob **had seen** Alice's edit, Lamport guarantees Bob's edit wins (it was made "after" in the causal sense).
- If Bob **hadn't seen** it, the edits are concurrent. There's no "correct" winner. The rule only needs every replica to pick **the same** winner, and the tie-break does that.

### 3.7 Where Lamport timestamps are used
- **LWW-Register** (Chapter 5): the edit with the highest `(time, node)` wins.
- **Unique operation IDs** in CRDTs: Automerge labels each operation `counter@actorId`. RGA (Chapter 7) uses Lamport timestamps to order concurrent inserts.
- **Total order broadcast** and Lamport's distributed mutual exclusion algorithm (classic theory).

---

## 4. Vector clocks: detecting concurrency

### 4.1 The idea
Instead of one counter, each node keeps **one counter per node**. Entry `V[X]` means "how many events of node X I know about (directly or through messages)".

### 4.2 The rules
```text
each node i keeps: V = {all nodes: 0}

on local event or send:
    V[i] = V[i] + 1
    (on send, attach a copy of V)

on receive message carrying W:
    for each node n:  V[n] = max(V[n], W[n])     # learn what the sender knew
    V[i] = V[i] + 1                              # the receive itself is an event
```

### 4.3 Comparing two vectors
```text
function compare(a, b):            # missing entries count as 0
    aSmaller = false               # some entry where a < b
    bSmaller = false               # some entry where a > b
    for each node n in keys(a) ∪ keys(b):
        if a[n] < b[n]: aSmaller = true
        if a[n] > b[n]: bSmaller = true

    if not aSmaller and not bSmaller: return EQUAL
    if aSmaller and not bSmaller:     return BEFORE      # a → b
    if bSmaller and not aSmaller:     return AFTER       # b → a
    return CONCURRENT                                    # each knows something the other doesn't
```

In words: **a is before b if every entry of a is ≤ the matching entry of b, and at least one is strictly smaller.**

### 4.4 Walkthrough (same diagram, vectors written as [P1, P2, P3])
```
P1:  e1 [1,0,0] ── e2 [2,0,0] ──────────────────── e3 [3,0,0]
                     │ sends [2,0,0]
                     ▼
P2:  f1 [0,1,0] ──── f2: max([0,1,0],[2,0,0]) = [2,1,0], then P2++ → [2,2,0]
                     f3 [2,3,0]
                       │ sends [2,3,0]
                       ▼
P3:  g1 [0,0,1] ── g2 [0,0,2] ── g3: max([0,0,2],[2,3,0]) = [2,3,2], then P3++ → [2,3,3]
```

| Pair | Vectors | compare | Matches the diagram? |
|---|---|---|---|
| e1, g3 | [1,0,0] vs [2,3,3] | all ≤, some < → BEFORE | ✓ e1 → g3 |
| e3, g3 | [3,0,0] vs [2,3,3] | 3 > 2 but 0 < 3 → CONCURRENT | ✓ |
| e2, g2 | [2,0,0] vs [0,0,2] | mixed → CONCURRENT | ✓ |

Vector clocks capture happens-before **exactly**: `A → B` if and only if `V(A) < V(B)`.

### 4.5 The cost
- The vector has **one entry per node**. With 3 servers that's tiny. With thousands of browser tabs, each tab is a "node", and vectors get large.
- Fixes used in practice: use per-document client IDs, drop entries for clients that left long ago (carefully), use **dotted version vectors** (section 5), or let a server order things (Chapter 10).

### 4.6 Where vector clocks are used
- **Detecting conflicts** in replicated databases (Amazon's Dynamo paper, Riak).
- **Causal delivery** of messages (Chapter 2): don't show a reply before its question.
- Debugging and tracing distributed systems: which log lines could have caused which.

---

## 5. Version vectors (a close cousin)

Vector clocks label **events**. **Version vectors** label **versions of data on a replica**: "this copy of the shopping cart includes 2 updates from S1 and 1 from S2". The comparison rules are the same.

### 5.1 The Dynamo shopping cart (a famous example)
```
Cart = {milk}               version {S1:1}

Network splits. Two clients edit different replicas:
  via S1: add eggs            → {milk, eggs}  version {S1:2}
  via S2: remove milk, add bread → {bread}    version {S1:1, S2:1}

Later they meet. Compare {S1:2} vs {S1:1, S2:1}:
  S1: 2 > 1,  S2: 0 < 1  → CONCURRENT → keep both as "siblings"

The app merges siblings by union → {milk, eggs, bread}
```
The conflict was **detected** correctly, but the union merge brought **milk back**. The Dynamo paper mentions exactly this: deleted items can reappear. This is the motivation for smarter set CRDTs (OR-Set, Chapter 5) that remember removals.

### 5.2 Dotted version vectors
When many clients write through the same server, plain version vectors can create **too many siblings** ("sibling explosion"). Riak 2.0 switched to **dotted version vectors**, which track each individual write (a "dot" = node + counter) separately from the summary vector. You don't need the details; just know the problem and that a fix exists.

### 5.3 State vectors in Yjs
Yjs (a text CRDT library) keeps a **state vector**: `clientID → how many operations from that client I have`. Two replicas exchange state vectors to figure out what the other is missing. That is a version vector used for **sync** (Chapter 8).

---

## 6. Hybrid Logical Clocks (HLC)

### 6.1 Why
Lamport numbers like `17` mean nothing to humans. You can't show "edited 5 minutes ago" or query "all changes between 2 pm and 3 pm". HLC gives timestamps that are **close to real time** but still respect **happens-before**.

### 6.2 The idea
A timestamp is a pair `(l, c)`:
- `l` = the largest physical time this node has heard of (its own clock or from messages)
- `c` = a small counter to order events that share the same `l`

```text
each node keeps: l = 0, c = 0
pt = this machine's physical clock right now

on local event or send:
    old = l
    l = max(old, pt)
    if l == old: c = c + 1        # physical clock hasn't moved past l
    else:        c = 0
    stamp = (l, c)

on receive message with (lm, cm):
    old = l
    l = max(old, lm, pt)
    if   l == old and l == lm: c = max(c, cm) + 1
    elif l == old:             c = c + 1
    elif l == lm:              c = cm + 1
    else:                      c = 0
    stamp = (l, c)

compare stamps: by l first, then c, then node ID
```

### 6.3 Walkthrough: one clock is ahead
```
Node A's physical clock reads 100. Node B's reads 90 (10 behind).

A: local event at pt=100        → l = max(0,100) = 100, c = 0   → (100,0)  send to B
B: receive at pt=90             → l = max(0,100,90) = 100
                                   l == lm, l != old → c = 0+1  → (100,1)  ✓ after A's event
B: local event at pt=91         → l = max(100,91) = 100, l == old → c = 2 → (100,2)
B: local event at pt=101        → l = max(100,101) = 101, changed → c = 0 → (101,0)
```
B's events are correctly ordered after A's even though B's clock was behind. Once B's real clock passes 100, the timestamps track real time again.

### 6.4 Where HLC is used
- **CockroachDB** and **YugabyteDB** use HLC to order transactions. CockroachDB also assumes clocks stay within a maximum offset (500 ms by default) and refuses to keep running nodes whose clocks drift further.
- **MongoDB** uses a hybrid logical clock for its "cluster time".
- **Actual Budget** (an open-source, local-first budgeting app) uses HLC timestamps for last-writer-wins sync between devices (Chapter 8).

---

## 7. TrueTime and precise clocks (the other approach)

Instead of avoiding physical time, make it **very accurate and admit the error**.

- **Google Spanner's TrueTime** uses GPS receivers and atomic clocks in every data center. `TT.now()` returns an **interval** `[earliest, latest]`, usually a few milliseconds wide. To commit a transaction, Spanner waits until the uncertainty interval has passed ("commit wait"). After that, any transaction that starts later is guaranteed to get a later timestamp.
- **AWS** offers the Amazon Time Sync Service (microsecond-level accuracy on supported instances) and **ClockBound**, a daemon that reports the clock **plus its error bound**, so applications can use the same "interval" idea.

Takeaway: physical time *can* be used for ordering, but only if you know **how wrong it might be** and wait out that uncertainty.

---

## 8. Which clock should I use?

| You need | Use |
|---|---|
| Measure a timeout or duration on one machine | Monotonic clock |
| Show "edited at 3:42 pm" to a user | Wall clock (just for display) |
| A total order of events that respects causality | Lamport `(time, node)` |
| Detect whether two edits were concurrent | Vector clock / version vector |
| Causal order **and** timestamps close to real time | HLC |
| Globally consistent transactions across data centers | TrueTime-style bounded clocks (or a consensus protocol) |
| Know what updates a peer is missing | Version vector / state vector (Chapter 8) |

---

## 9. Interview angle

- **"Why not just use timestamps?"** Clock skew and jumps. Two servers can disagree by more than the time between two edits, so "latest wins" picks the wrong one and silently loses data.
- **"How does Dynamo/Riak detect conflicting writes?"** Version vectors. If neither version dominates, the writes were concurrent; keep both as siblings and merge.
- **"How do you order events across regions?"** Options: a single leader assigns order; Lamport/HLC timestamps with tie-break; TrueTime-style bounded clocks; or consensus (Raft/Paxos) when you need a strict agreed order.
- **"What's the difference between Lamport and vector clocks?"** Lamport gives a total order consistent with causality but can't detect concurrency. Vector clocks detect concurrency exactly but cost O(number of nodes) per timestamp.

---

## 10. Common mistakes

- Using `time.Now()` differences across machines to decide order.
- Using the wall clock for timeouts (breaks when the clock jumps).
- Forgetting that **receive** is an event (the `+1` after `max`).
- Treating "same Lamport number" as "same time" or "concurrent". Equal numbers are possible for concurrent events, but concurrent events can also have different numbers.
- Letting vector clocks grow forever with one entry per browser tab.

---

## Check yourself

<details>
<summary>1. Two events have Lamport times 4 and 9. Did the first happen before the second?</summary>

Not necessarily. Lamport only guarantees the other direction: if A → B, then L(A) < L(B). Events with 4 and 9 could be causally related or concurrent. You need vector clocks to tell.

</details>

<details>
<summary>2. Compare [2,1,0] and [1,2,0].</summary>

First entry: 2 > 1. Second entry: 1 < 2. Each has something the other doesn't, so they are **concurrent**.

</details>

<details>
<summary>3. Compare [1,2,0] and [1,3,1].</summary>

Every entry of the first is ≤ the second, and some are strictly smaller. So the first happened **before** the second.

</details>

<details>
<summary>4. Why does the Lamport receive rule add 1 after taking the max?</summary>

Because receiving is itself an event, and it must be ordered after both the sender's event (so it must exceed the message's time) and this node's previous event (so it must exceed the local time).

</details>

<details>
<summary>5. Why did the Dynamo shopping cart bring back deleted items?</summary>

Version vectors correctly detected the two cart versions as concurrent, but the merge rule was "union of items". Union can't express "milk was removed", so milk from the older sibling came back. A set CRDT that tracks removals (OR-Set) fixes this.

</details>

<details>
<summary>6. When would you pick HLC over Lamport?</summary>

When you need timestamps that humans or queries can relate to real time ("changes in the last hour", "edited 5 min ago") while still respecting causality, for example in databases (CockroachDB) or app sync logs.

</details>

---

## Practice

- **Task 1 — Lamport clock** (`clock/lamport.go`): `Tick`, `Receive`, `Now`, a `Timestamp{Time, Node}` with `Less`, and a `Stamp()` helper. Test: replay the e/f/g diagram and check 1,2,3 / 1,3,4 / 1,2,5.
- **Task 2 — Vector clock** (`clock/vector.go`): a map from node ID to counter. Operations: `Tick(node)`, `Merge(other)` (for receive), `Compare(a, b)` returning Before / After / Equal / Concurrent. Test: replay the same diagram with vectors and check every pair in the table in section 4.4.
- **Task 3 (optional) — HLC** (`clock/hlc.go`): implement the rules in 6.2 with an injectable "physical time" function so tests can fake a clock that is ahead or behind. Test: the walkthrough in 6.3.

---

## Further reading
- Leslie Lamport, *Time, Clocks, and the Ordering of Events in a Distributed System* (1978). Short and readable.
- Martin Kleppmann, *Distributed Systems* lecture course (Cambridge, YouTube + notes): lectures on time and broadcast.
- *Designing Data-Intensive Applications*, chapters on replication and "the trouble with distributed systems" (unreliable clocks).
- Kulkarni et al., *Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases* (the HLC paper).
- DeCandia et al., *Dynamo: Amazon's Highly Available Key-value Store* (2007).

---

[← 0. The Big Picture](00-big-picture.md) · [Index](README.md) · [2. Replication, Consistency & Merging →](02-replication-consistency-merging.md)
