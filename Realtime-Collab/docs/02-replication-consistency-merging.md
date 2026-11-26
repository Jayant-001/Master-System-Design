# 2. Replication, Consistency & Merging

> **In one line:** Keeping copies of data is easy; deciding what each copy is allowed to show, and how copies merge after they disagree, is the real work.
>
> **You'll learn:** replication styles, consistency models in plain words, conflict resolution strategies, the three properties that make a merge safe, delivery guarantees, idempotency, and causal broadcast.
>
> **Before this:** [1. Time, Clocks & Causality](01-time-clocks-causality.md)

**Summary:**
- Collaborative apps are an extreme form of **multi-leader replication**: every device accepts writes.
- **Strong eventual consistency (SEC):** replicas that received the same updates are in the same state, with no rollback. This is what CRDTs promise.
- A merge is safe on an unreliable network if it is **commutative** (order doesn't matter), **associative** (grouping doesn't matter), and **idempotent** (duplicates don't matter).
- Networks deliver **at least once** at best; "exactly once" = at-least-once + **deduplication/idempotency**.
- **Causal delivery** makes sure nobody sees an effect before its cause (a reply before its question).

---

## 1. Why replicate?

| Reason | Example |
|---|---|
| **Low latency** | Your edit applies on your laptop instantly; a copy in your region answers fast |
| **Offline work** | Edit a doc on a plane; sync later |
| **Availability** | One server dies; other copies keep serving |
| **Read scaling** | Many read replicas behind a popular page |

---

## 2. Three replication styles

```
SINGLE-LEADER                 MULTI-LEADER                   LEADERLESS
                                                              
 writes → [Leader]            writes → [L1] ⇄ [L2] ← writes   writes → [N1] [N2] [N3]
             │                           ⇅                       quorum: write to 2 of 3,
         ┌───┴───┐                     [L3] ← writes              read from 2 of 3
     [Follower][Follower]
                                                              
 One place orders writes.     Every leader accepts writes;   Any node accepts writes;
 No write conflicts.          conflicts are possible and     conflicts resolved on read
                              must be merged.                or in background.
 e.g. Postgres primary,       e.g. multi-region databases,   e.g. Dynamo, Cassandra
 Google Docs (one server      every client in a
 per document)                collaborative app
```

A collaborative app where every client edits locally is **multi-leader with thousands of leaders**. That's why conflict handling is central.

---

## 3. Consistency models in plain words

A consistency model is a **promise** about what reads can return. From strongest to weakest:

| Model | Promise | Anomaly it prevents |
|---|---|---|
| **Linearizable** | Acts like one single copy; once a write is done, every later read sees it | Seeing an old value after someone else saw the new one |
| **Sequential** | Everyone sees all operations in the same order (maybe delayed) | Two users seeing two edits in opposite orders |
| **Causal** | If A caused B, everyone sees A before B; concurrent things may appear in any order | A reply appearing before its question |
| **Read-your-writes** | You always see your own writes | You post a comment, refresh, and it's gone |
| **Monotonic reads** | You never see time go backwards | A like count going 10 → 12 → 11 |
| **Eventual** | If writes stop, copies *eventually* agree | (Only promises the end state; anything goes meanwhile) |
| **Strong eventual (SEC)** | Replicas that received the **same set** of updates are **identical**, immediately, with no conflict-resolution rollback | Two users with the same edits seeing different documents |

**Collaborative editors aim for:** causal consistency + strong eventual consistency + read-your-writes. Your own edits show instantly; others' edits arrive in causal order; once everyone has everything, everyone sees the same thing.

### 3.1 CAP and PACELC, briefly
- **CAP:** during a network partition, a system must choose between **Consistency** (refuse some requests) and **Availability** (answer, possibly with stale or conflicting data).
- **PACELC:** if Partition → choose A or C; Else (normal operation) → choose **L**atency or **C**onsistency.
- Collaborative apps choose **availability and low latency**: keep editing offline, merge later. That choice is exactly what forces them to use OT, CRDTs, or reconciliation.

---

## 4. What is a conflict?

A conflict happens when **concurrent** operations (Chapter 1) touch the same thing and the result depends on their order.

| Situation | Example |
|---|---|
| Two writes to the same field | Alice sets title "Q3 Plan", Bob sets "Q3 Roadmap" |
| Add vs remove | Alice removes "milk"; Bob adds "milk" |
| Concurrent inserts at the same spot | Both type at the end of the same sentence |
| Edit vs delete | Alice edits a paragraph Bob just deleted |
| Move vs move | Alice moves folder A into B; Bob moves B into A (a cycle!) |
| Invariant violation | Two people book the last seat |

Not every concurrent pair is a conflict. Alice changing a rectangle's **color** while Bob changes its **position** don't conflict if you track properties separately (this is a key Figma idea, Chapter 10).

---

## 5. Strategies for resolving conflicts

| Strategy | How | Pros | Cons | Used by |
|---|---|---|---|---|
| **Last-writer-wins (LWW)** | Keep the value with the highest timestamp | Very simple | Silently loses the other write | Cassandra (per cell), Figma (per property), DynamoDB Global Tables (default mode) |
| **Keep all versions (siblings)** | Store every concurrent value; let the app or user pick | Nothing lost | Pushes work to the app/user | Riak, Dynamo, git merge conflicts |
| **Type-specific automatic merge (CRDT)** | Each data type defines a merge that always works | Automatic, no server needed | Metadata overhead; semantics fixed by type | Yjs, Automerge, Redis Active-Active |
| **Operational transform (OT)** | Adjust operations against concurrent ones | Low overhead for text | Complex; needs central ordering | Google Docs |
| **Server decides (authoritative)** | Server orders everything; clients correct themselves | Easy validation and permissions | Needs connectivity for final answer | Figma, games, Replicache |
| **Coordination / reject** | Lock, transaction, or reject the second write | Keeps strict invariants | Slow, not available offline | Bank transfers, seat booking |

A real app uses **different strategies for different data**: LWW for a profile photo, a set CRDT for tags, OT/CRDT for the text body, coordination for payments.

---

## 6. What makes a merge safe

Messages arrive in **any order**, **in any grouping**, and **sometimes twice**. A merge function `merge(a, b)` survives all of that if it has three properties.

### 6.1 Commutative: order doesn't matter
`merge(a, b) = merge(b, a)`

Alice gets Bob's update then Carol's; Bob gets Carol's then Alice's. Same result. `max(3, 5) = max(5, 3)`.

### 6.2 Associative: grouping doesn't matter
`merge(merge(a, b), c) = merge(a, merge(b, c))`

Updates can be merged pairwise in any combination, like gossip passing through different people. `max(max(3,5),4) = max(3,max(5,4))`.

### 6.3 Idempotent: duplicates don't matter
`merge(a, a) = a`

Networks retry, so the same update may arrive twice. `max(5, 5) = 5`. But `5 + 5 = 10` ✗.

### 6.4 Test your intuition

| merge(a, b) | Commutative | Associative | Idempotent | Safe? |
|---|---|---|---|---|
| `max(a, b)` | ✓ | ✓ | ✓ | ✓ |
| set union `a ∪ b` | ✓ | ✓ | ✓ | ✓ |
| LWW by `(timestamp, node)` | ✓ | ✓ | ✓ | ✓ |
| `a + b` | ✓ | ✓ | ✗ | ✗ duplicates double-count |
| "keep whichever arrived last" | ✗ | ✓ | ✓ | ✗ depends on arrival order |
| list concatenation `a ++ b` | ✗ | ✓ | ✗ | ✗ |
| `a - b` | ✗ | ✗ | ✗ | ✗ |

**Trick:** if your natural operation isn't idempotent (like "+1"), change **what you store** so the merge becomes `max` or `union`. A counter becomes "a map of per-replica counts, merged by max" (G-Counter, Chapter 5).

### 6.5 The picture behind it: states only grow
Safe merges have a shape mathematicians call a **join-semilattice**: states are partially ordered ("this state includes at least everything that one does"), and `merge` returns the **smallest state that includes both**.

```
Sets, ordered by "contains" (higher = contains more):

          {a, b}        ← merge({a}, {b}): the lowest point above both
          /    \
        {a}    {b}      ← two replicas that each added one item
          \    /
           { }          ← where both started

Max on numbers is the same shape, just a straight line:
   1 → 3 → 5 → 7        merge(3, 5) = 5
```

States move **up** this diagram (they "inflate"): adding things, raising counters. Since merge just "goes up to the meeting point", it doesn't matter in which order or how many times you merge. You'll use this idea to design every CRDT in Chapter 5.

---

## 7. Delivery guarantees and idempotency

### 7.1 What networks actually give you

| Guarantee | Meaning | How |
|---|---|---|
| At-most-once | Delivered 0 or 1 times | Send and forget |
| At-least-once | Delivered 1 or more times | Retry until acked |
| "Exactly-once" | Effect happens once | **At-least-once + deduplication**, or an idempotent operation |

A truly exactly-once *network* doesn't exist. Ack messages can be lost, so the sender can't know whether to retry. You get exactly-once **effects** by making the receiver tolerate duplicates.

### 7.2 Two ways to tolerate duplicates
1. **Idempotent operations:** "set x = 5" or "merge state" can be applied twice safely. "Add 1" cannot.
2. **Deduplication by ID:** give each operation a unique ID, for example `(clientID, sequenceNumber)`. The receiver remembers the highest sequence number applied per client and skips anything already seen.

```text
on receive op from client c with sequence s:
    if s <= lastApplied[c]:
        return                       # duplicate, already applied
    if s > lastApplied[c] + 1:
        buffer it until the gap is filled (or ask for a resend)
    apply(op)
    lastApplied[c] = s
```

**Real use:** Stripe's API accepts an `Idempotency-Key` header so retrying a payment request doesn't charge twice. Collaborative servers do the same with `(clientID, seq)` so a client that reconnects and resends doesn't duplicate its edits (Chapter 4).

---

## 8. Broadcast orderings

When one replica's update must reach all others, the order of delivery matters.

| Ordering | Promise | Typical use |
|---|---|---|
| Best-effort | Maybe delivered | Presence/cursor updates (Chapter 9) |
| Reliable | Eventually delivered to everyone (retries) | Basis for everything else |
| FIFO | Messages from the **same sender** arrive in send order | Per-client op streams |
| **Causal** | If A → B, everyone delivers A before B | Op-based CRDTs, chat replies |
| Total order | Everyone delivers **all** messages in the **same** order | OT with a central server, state machine replication (Raft) |

Op-based CRDTs (Chapter 5) need **causal** delivery: a "remove item X" must never be applied before the "add item X" it refers to. OT with a server gets **total order** from the server.

### 8.1 Causal delivery with vector clocks
Each process keeps `D[n]` = how many messages from each sender it has **delivered** (handed to the app).

```text
broadcast(m) at process i:
    D[i] = D[i] + 1
    m.vector = copy of D
    send m to everyone; deliver m to myself

on receive m from sender j:
    add m to holdBack
    repeat until nothing changes:
        for each message x in holdBack (from sender s):
            if x.vector[s] == D[s] + 1                  # next message from s
               and x.vector[k] <= D[k] for all k != s:  # I've seen everything s had seen
                deliver x to the app
                D[s] = D[s] + 1
                remove x from holdBack
```

### 8.2 Walkthrough: a reply arrives before its question
```
Alice broadcasts  Q "lunch?"     vector [A:1, B:0, C:0]
Bob receives Q, then broadcasts  R "yes!"   vector [A:1, B:1, C:0]

Carol receives R first.  D = [0,0,0]
  R from Bob: R.vector[B] = 1 == D[B]+1 ✓   R.vector[A] = 1 <= D[A] = 0 ✗  → HOLD
Carol receives Q.
  Q from Alice: Q.vector[A] = 1 == D[A]+1 ✓, others ≤ ✓ → deliver Q.  D = [1,0,0]
  Recheck R: R.vector[B] = 1 == 0+1 ✓, R.vector[A] = 1 <= 1 ✓ → deliver R.  D = [1,1,0]

Carol sees "lunch?" then "yes!" ✓
```

---

## 9. How replicas catch up (preview)

Even with retries, replicas can miss updates (a client was offline for a week). Systems repair this with:
- **Anti-entropy:** periodically compare replicas and exchange what's missing (version vectors, Merkle trees).
- **Read repair:** when a read sees replicas disagree, fix the stale one.
- **Gossip:** each node regularly syncs with a random peer; information spreads like a rumor.

Details in [Chapter 8](08-sync-storage-offline.md).

---

## 10. Interview angle

- **"What consistency does a collaborative editor need?"** Read-your-writes (local echo), causal ordering of others' edits, and strong eventual consistency (same edits → same doc). Not linearizability; that would make typing wait on the network.
- **"How do you make retries safe?"** Idempotent operations, or unique op IDs with server-side dedup.
- **"LWW: when is it OK?"** When losing a concurrent write is acceptable and rare: a profile picture, a shape's color. Not OK for text, counters, or carts.
- **"Why can't CRDTs prevent double-booking?"** "At most one booking" is a global invariant. Checking it needs coordination (Chapter 5, section 11).

---

## 11. Common mistakes

- Assuming messages arrive once and in order because "we use TCP". TCP orders bytes on **one** connection; reconnects, retries, and multiple servers break that.
- Making "increment" the replicated operation without deduplication.
- Using LWW for data where both writes matter.
- Delivering ops in arrival order to an algorithm that requires causal order.

---

## Check yourself

<details>
<summary>1. Is "append to a log" a safe merge? Why or why not?</summary>

No. Appending isn't commutative (order changes the log) and isn't idempotent (a duplicate appends twice). You can make it safe by giving each entry a unique ID and sorting by a deterministic key, which turns the log into a set.

</details>

<details>
<summary>2. Which property protects you from network retries? Which from messages arriving in different orders?</summary>

Idempotence protects against duplicates from retries. Commutativity (and associativity) protect against different arrival orders and groupings.

</details>

<details>
<summary>3. What's the difference between eventual consistency and strong eventual consistency?</summary>

Eventual consistency only promises that replicas agree *eventually* after writes stop, possibly after resolving conflicts (and maybe rolling back what users saw). SEC promises that any two replicas that received the same set of updates are **already** identical, with no rollback.

</details>

<details>
<summary>4. In the causal delivery walkthrough, why did Carol hold back Bob's reply?</summary>

Bob's message vector said Bob had seen 1 message from Alice, but Carol had delivered 0 from Alice. Delivering Bob's reply would show an effect before its cause, so Carol waited until Alice's question arrived.

</details>

<details>
<summary>5. Give one piece of data where LWW is fine and one where it's not.</summary>

Fine: a user's avatar URL or a rectangle's fill color (losing a concurrent change is acceptable). Not fine: a like counter (two concurrent +1s must both count) or document text (both people's typing must survive).

</details>

---

## Practice

- **Task 4 — Network simulator** (`sim/`): you'll reuse this for every CRDT and OT test.
  - A `Network` that holds in-flight messages and, using a seeded random generator, can **delay, reorder, duplicate, and drop** them.
  - `Step()` delivers one random in-flight message; `Flush()` delivers everything (with drops turned off).
  - A test that sends numbered messages and checks they can arrive out of order and duplicated. Print the seed on failure so you can replay it.
- **Task 5 — Merge-law checker:** a test helper that, given a merge function and a random-state generator, checks commutative, associative, and idempotent on many random inputs. Try it on `max` (passes) and `+` (fails idempotence).
- **Optional:** causal broadcast on top of the simulator, using the algorithm in 8.1. Test the Alice/Bob/Carol walkthrough.

---

## Further reading
- *Designing Data-Intensive Applications*: Replication; Consistency and Consensus.
- Martin Kleppmann's Cambridge lectures: broadcast protocols, replication.
- Shapiro et al., *Conflict-free Replicated Data Types* (2011): the SEC definition.
- Hellerstein & Alvaro, *Keeping CALM: When Distributed Consistency Is Easy* (2019).

---

[← 1. Time, Clocks & Causality](01-time-clocks-causality.md) · [Index](README.md) · [3. Lerp, Interpolation & Netcode →](03-lerp-interpolation-netcode.md)
