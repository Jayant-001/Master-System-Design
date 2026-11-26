# 5. CRDT Fundamentals

> **In one line:** A CRDT is a data type designed so that replicas can be changed independently and then merged in **any order, any number of times**, always reaching the same result.
>
> **You'll learn:** state-based, op-based, and delta-state CRDTs; counters, registers, sets, maps, and JSON documents; choosing add-wins vs remove-wins vs LWW; tombstones and garbage collection; how to design and test your own CRDT; and what CRDTs can't do.
>
> **Before this:** [1. Clocks](01-time-clocks-causality.md) (Lamport timestamps, version vectors) and [2. Merging](02-replication-consistency-merging.md) (commutative, associative, idempotent).

**Summary:**
- **State-based (CvRDT):** send the whole state; `merge` must be commutative, associative, idempotent. Works over any messy network.
- **Op-based (CmRDT):** send operations; concurrent ops must commute; needs **causal, exactly-once** delivery (or dedup).
- **Delta-state:** send small pieces of state that merge like full states.
- Core types: **G-Counter, PN-Counter, LWW-Register, MV-Register, G-Set, 2P-Set, LWW-Element-Set, OR-Set, LWW-Map/OR-Map**.
- Deletes usually leave **tombstones**; removing them safely needs everyone to have seen the delete (**causal stability**).
- CRDTs **can't enforce global invariants** (unique usernames, no overselling) without coordination.

---

## 1. What a CRDT is

**C**onflict-free **R**eplicated **D**ata **T**ype. Each replica:
1. can be **updated locally** at any time, without asking anyone (works offline);
2. sends its updates to others whenever it can;
3. **merges** incoming updates with a rule that guarantees **strong eventual consistency**: replicas that received the same updates are identical.

**Analogy:** each person has a notebook. The rules for copying entries from someone else's notebook are designed so that, no matter who copies from whom, in what order, or how many times, everyone's notebook ends up the same.

The trick is always the same: **store a little extra information** (per-replica counts, timestamps, unique tags) so that the merge can be `max` or `union`-like.

---

## 2. Two (and a half) flavors

### 2.1 State-based (CvRDT, "convergent")
```
Replica A state ──── send whole state ────► Replica B: B = merge(B, A)
```
- **Requirement:** `merge` is commutative, associative, idempotent; local updates only move the state "up" (Chapter 2, section 6.5).
- **Network:** anything goes. Duplicates, reordering, and loss (just send again later) are all fine.
- **Cost:** messages can be large (the whole state).

### 2.2 Op-based (CmRDT, "commutative")
```
Replica A does op ──── send op ────► Replica B: apply(op)
```
- **Requirement:** **concurrent** operations commute (applying them in either order gives the same result).
- **Network:** needs **reliable causal delivery** (Chapter 2, section 8) and each op applied **exactly once** (dedup by op ID).
- **Cost:** small messages.

### 2.3 Delta-state (the practical middle)
Instead of the whole state, send a **delta**: a tiny state containing only what changed. Deltas merge with the same `merge` function, so you get small messages **and** tolerance of duplicates and reordering. Akka Distributed Data supports delta CRDTs, and most production libraries use some version of this idea.

| | State-based | Op-based | Delta-state |
|---|---|---|---|
| Message size | Whole state | One op | Small |
| Duplicates OK? | Yes | Only with dedup | Yes |
| Needs causal delivery? | No | Yes | Usually helps, not always required |
| Easy to reason about | Yes | Medium | Medium |

---

## 3. Counters

### 3.1 G-Counter (grow-only counter)

**Use:** view counts, likes, "number of times X happened" across replicas or regions.

**Why not just a number?** If A has 2 and B has 1, merging by adding gives 3. But if the same state arrives twice, you get 5. Adding isn't idempotent.

**Idea:** keep **one count per replica**. Each replica only increments its own entry. Merge by **max per entry**. Value = sum.

```text
state: counts = map replicaID → number (missing = 0)

increment(me):    counts[me] = counts[me] + 1
value():          sum of all counts
merge(a, b):      for each id: result[id] = max(a[id], b[id])
```

**Walkthrough:**
```
A increments twice     A = {A:2}                value 2
B increments once      B = {B:1}                value 1
A sends state to B     B = max-merge({B:1},{A:2}) = {A:2, B:1}   value 3
B sends state to C     C = {A:2, B:1}           value 3
A's old state {A:2} arrives at B again (duplicate):
                       B = max-merge({A:2,B:1},{A:2}) = {A:2,B:1} value 3 ✓ unchanged
```
Each entry only grows, and only its owner increases it, so `max` always picks the newest value of each entry.

### 3.2 PN-Counter (increment and decrement)
Two G-Counters: `P` for increments, `N` for decrements.
```text
state: P, N (both G-Counters)
increment(me): P.increment(me)
decrement(me): N.increment(me)
value():       P.value() - N.value()
merge(a, b):   P = merge(a.P, b.P);  N = merge(a.N, b.N)
```
**Use:** stock levels where going briefly wrong is acceptable, upvotes/downvotes, "people in the room".

**Limitation:** you can't guarantee `value >= 0`. Two regions might each sell the last item. Preventing that needs **coordination**, or the **escrow** idea: give each region a share of the stock in advance ("EU may sell up to 30, US up to 70"); a region that runs out must ask another region to transfer part of its share. (This is the idea behind "bounded counter" CRDTs.)

**Real use:** Redis Enterprise Active-Active databases offer CRDT-based counters across regions.

---

## 4. Registers (a single value)

### 4.1 LWW-Register (last-writer-wins)
```text
state: value, ts             # ts = (lamportTime, nodeID) or an HLC timestamp

set(v):        ts = clock.stamp(); value = v
merge(a, b):   return a if b.ts < a.ts else b       # the higher timestamp wins
```
**Walkthrough:**
```
Alice: set("Q3 Plan")    ts (5, alice)
Bob:   set("Roadmap")    ts (5, bob)      concurrent
Merge on any replica: (5, alice) < (5, bob) → "Roadmap"
Alice's title is lost, but every replica agrees ✓
```
**Use:** fields where losing a concurrent write is acceptable: a name, a color, a position, a setting.

**Real use:**
- **Cassandra** stores a timestamp with every cell and keeps the latest.
- **Figma** resolves conflicts **per property of each object** by last write (at the server).
- **Excalidraw** (the tool you draw in) gives each element a `version` and a random `versionNonce`. When two copies of an element meet, the higher `version` wins; ties are broken by `versionNonce`. That's an LWW-Register per element. Look at your `design.excalidraw`: every element has those two fields.

### 4.2 MV-Register (multi-value: keep all concurrent values)
Instead of picking a winner, keep **every value that isn't dominated**, using version vectors.
```text
state: set of (value, versionVector)

set(v):      vv = merge of all current version vectors, then increment my entry
             state = {(v, vv)}
merge(a, b): keep every entry that no other entry's vector dominates (Chapter 1, section 4.3)
read():      list of values (one if no conflict, several if concurrent)
```
**Walkthrough:**
```
Alice sets "red"    {A:1}
Bob   sets "blue"   {B:1}           concurrent
Merge → {("red",{A:1}), ("blue",{B:1})}    read() = ["red", "blue"]  → app/user decides
Carol (has seen both) sets "green" {A:1, B:1, C:1}   dominates both
Merge → {("green", ...)}                   read() = ["green"]
```
**Real use:** Amazon's Dynamo (the shopping cart siblings), Riak's siblings. Like a git merge conflict: nothing is lost, but someone must resolve it.

---

## 5. Sets

The hard part of sets is **remove**. What should happen when one replica removes "milk" while another concurrently adds "milk"? Each set CRDT answers differently.

### 5.1 G-Set (grow-only)
```text
add(e):      S = S ∪ {e}
merge(a,b):  a ∪ b
```
No remove. **Use:** "users who have ever logged in", seen message IDs, append-only facts.

### 5.2 2P-Set (two-phase set)
Two G-Sets: `A` (added) and `R` (removed, the **tombstones**).
```text
add(e):       A = A ∪ {e}
remove(e):    if e in A: R = R ∪ {e}
contains(e):  e in A and e not in R
merge(x,y):   A = x.A ∪ y.A;  R = x.R ∪ y.R
```
Once removed, an element can **never be added again**. Remove always wins. **Use:** rarely, but it shows the tombstone idea.

### 5.3 LWW-Element-Set
Each element has the timestamp of its latest add and latest remove.
```text
addTs[e], removeTs[e]
add(e):      addTs[e]    = max(addTs[e], now())
remove(e):   removeTs[e] = max(removeTs[e], now())
contains(e): addTs[e] > removeTs[e]       # decide which wins on an exact tie ("bias")
merge:       per element, max of each timestamp
```
Re-adding works (a newer add beats an older remove). **Real use:** SoundCloud's Roshi was an LWW-element-set built on Redis for activity feeds.

### 5.4 OR-Set (observed-remove, "add wins")
The most useful general-purpose set.

**Idea:** every `add` creates a **unique tag**. `remove(e)` removes only the tags it has **observed**. A concurrent add has a new tag the remover never saw, so it survives.

```text
state: adds    = set of (element, tag)
       removed = set of tags                       # tombstones

add(e):       tag = uniqueID()                     # e.g. (replicaID, counter)
              adds = adds ∪ {(e, tag)}
remove(e):    removed = removed ∪ { tag : (e, tag) in adds }    # only tags I can see
contains(e):  exists (e, tag) in adds with tag not in removed
merge(x, y):  adds = x.adds ∪ y.adds;  removed = x.removed ∪ y.removed
```

**Walkthrough:**
```
Both replicas start with milk, tag a1:       adds = {(milk, a1)}

Concurrently:
  Alice removes milk → removed = {a1}
  Bob adds milk again (hasn't seen the remove) → adds = {(milk,a1), (milk,b1)}

Merge:
  adds    = {(milk,a1), (milk,b1)}
  removed = {a1}
  milk present? (milk, b1) exists and b1 not removed → YES  (add wins) ✓
```
Alice removed the milk **she could see**. Bob's new milk is a different "instance".

**Optimization:** storing every removed tag forever is wasteful. The **ORSWOT** ("OR-Set without tombstones", used by Riak) uses version vectors to tell "I've seen that tag and it's gone" without keeping each tombstone.

**Use:** shopping carts, tags on a document, members of a group chat, "who has this file open".

### 5.5 Remove-wins sets
The mirror image: a remove beats concurrent adds. Use when removing is the **safe** direction, for example revoking access: if one admin removes Bob from a team while another concurrently adds him to a channel, you probably want Bob **out**.

### 5.6 Which set should I use?
| Want | Use |
|---|---|
| Never remove | G-Set |
| Concurrent add should win (carts, tags) | OR-Set |
| Concurrent remove should win (permissions, blocklists) | Remove-wins set |
| Latest action by time should win, simple | LWW-Element-Set |

---

## 6. Maps and documents

### 6.1 LWW-Map
A map where each key is an LWW-Register.
```text
state: entries = map key → (value, ts), plus a removed flag per key (a tombstone)
set(k, v):     entries[k] = (v, stamp())
delete(k):     entries[k] = (TOMBSTONE, stamp())
merge:         per key, keep the entry with the higher ts
```
**Walkthrough:** a user profile `{name, city}`:
```
Alice sets city = "Pune"    (7, alice)
Bob   sets name = "J. K."   (7, bob)
Merge → {name: "J. K.", city: "Pune"}   both changes kept: different keys don't conflict ✓
```
This is why **fine-grained** data (per field, per property) loses far less than LWW on the whole object.

### 6.2 OR-Map (nested CRDTs)
Keys form an OR-Set; each value is itself a CRDT (counter, set, register, another map). Merging merges keys and, for keys present on both sides, merges the values recursively.

### 6.3 JSON document CRDTs
Combine maps, lists (Chapter 7), and registers into a whole JSON document. This is what **Automerge** provides (based on Kleppmann & Beresford's *A Conflict-Free Replicated JSON Datatype*). When two users concurrently assign the same key, Automerge deterministically picks one value as the winner but also lets the app see the conflicting values.

```
Alice: doc.todos[0].done = true
Bob:   doc.todos[0].title = "Buy oat milk"
Merge: { todos: [ { title: "Buy oat milk", done: true } ] }   ✓ different fields
```

---

## 7. Choosing semantics is a product decision

The math guarantees convergence. **What** it converges to is up to you:

| Data | Reasonable rule | Why |
|---|---|---|
| Shopping cart items | Add-wins (OR-Set) | Losing something a user added is worse than keeping something they removed |
| Team membership / access | Remove-wins | Security: when in doubt, deny |
| Profile fields | LWW per field | Rare conflicts; latest is fine |
| Likes | G-Counter or OR-Set of user IDs | A set also prevents double-liking |
| Document text | Sequence CRDT (Chapter 7) | Everyone's typing must survive |

---

## 8. Tombstones and garbage collection

Most CRDTs keep **tombstones** (records of deletions) so that an old copy of the data can't bring deleted items back during a merge. They grow forever unless removed.

**When is it safe to delete a tombstone?** When **every** replica has seen the delete. This is called **causal stability**. In practice that requires knowing **who all the replicas are**, which is hard for browsers that may come back after a month.

Practical approaches:
- **Server-side compaction:** the server, which sees everything, periodically rewrites a snapshot without old tombstones. Clients that were away too long must reload a fresh snapshot instead of merging old state.
- **Epochs:** after a compaction, old states from before the epoch are rejected.
- **Accept the cost:** tombstones for text are small if compressed (Chapter 7).

**Real-world cautionary tale:** Cassandra keeps tombstones for `gc_grace_seconds` (10 days by default). If a replica is down longer than that and isn't repaired before the tombstone is purged, the deleted data can come back to life ("zombie" data).

---

## 9. Designing your own CRDT

A checklist:
1. **State:** what extra info do you need? (per-replica counts, timestamps, unique tags)
2. **Order:** define "state X includes state Y". Updates must only move a state **up**.
3. **Merge:** the smallest state that includes both inputs (often `max` / `union` per part).
4. **Prove** (or test) merge is commutative, associative, idempotent.
5. **Query:** how do you read a normal value out (sum, set of live elements, winner)?
6. **Semantics:** what happens for each concurrent pair of operations (add/remove, set/set)? Is that what users want?
7. **Garbage:** what grows forever, and how will you trim it?

---

## 10. Testing CRDTs

1. **Merge laws** with random states (your Task 5 checker from Chapter 2):
   `merge(a,b) == merge(b,a)`, `merge(merge(a,b),c) == merge(a,merge(b,c))`, `merge(a,a) == a`.
2. **Random convergence test** (the most important one):
```text
for many random seeds:
    replicas = 3..5 empty replicas
    repeat 200 times:
        pick a random replica, do a random operation on it
        sometimes: send its state (or op) into the simulated network
        sometimes: deliver a random in-flight message (may duplicate or reorder)
    deliver everything still in flight (everyone syncs with everyone)
    assert all replicas have identical value
    on failure: print the seed
```
3. **Scenario tests** for each walkthrough in this chapter (they document the intended semantics).

More in [Chapter 11](11-testing-distributed-algorithms.md).

---

## 11. What CRDTs can't do

- **Global invariants** need coordination: "usernames are unique", "balance ≥ 0", "only one person books seat 12A". Two replicas can each check locally, both say "OK", and the merge violates the rule.
- The **CALM theorem** (Consistency As Logical Monotonicity) formalizes this: programs whose results only **grow** as they learn more (monotonic) can run without coordination; programs that must know "nothing else will arrive" (like "this seat is still free") need coordination.
- **Intent:** the merged result is always *valid*, but can still surprise users. Two people fixing the same typo differently might get both fixes.
- **Metadata cost:** IDs, timestamps, and tombstones can be larger than the data itself.
- **Authorization:** a CRDT happily merges any well-formed update. Deciding *who may* change what is a separate layer (Chapter 8).

---

## 12. Real-world uses

| Where | What |
|---|---|
| Redis Enterprise Active-Active | CRDT-based strings, counters, sets, etc. across regions |
| Riak | Counters, sets (ORSWOT), maps |
| Akka Distributed Data | Delta-state CRDTs inside a cluster |
| Phoenix Presence (Elixir) | Presence tracking built on a CRDT, no central store |
| Yjs, Automerge, Loro | Text, lists, maps for collaborative apps |
| Figma, Excalidraw | LWW per property / per element (CRDT-inspired, with a server) |
| Cassandra, DynamoDB Global Tables (default mode) | LWW conflict resolution between replicas/regions |

---

## 13. Interview angle

- **"Count likes across 3 regions without a central DB."** G-Counter per region (or an OR-Set of user IDs if each user may like once); merge by max per region; eventually consistent count.
- **"Shopping cart that works offline."** OR-Set: add wins; removes only affect items the user had seen.
- **"Why not LWW everywhere?"** It silently drops concurrent writes; fine for a color, wrong for a cart or counter.
- **"Can a CRDT stop overselling?"** Not alone. Use coordination for the final decrement, or escrow/bounded counters to pre-split the stock.

---

## 14. Common mistakes

- Merging counters by addition.
- Using wall-clock time for LWW without a tie-breaker.
- Forgetting tombstones, so deleted items come back after a merge with an old replica.
- Treating op-based CRDTs as safe over a network that duplicates or reorders.
- Choosing add-wins for permissions.

---

## Check yourself

<details>
<summary>1. A: {A:3, B:1}, B: {A:2, B:4}. Merge these G-Counters. What's the value?</summary>

Per-entry max: {A:3, B:4}. Value = 7.

</details>

<details>
<summary>2. In an OR-Set, Alice removes "x" while Bob concurrently adds "x". What's the result, and why?</summary>

"x" is present. Alice's remove only tombstoned the tags she had observed; Bob's add created a new tag that wasn't removed.

</details>

<details>
<summary>3. Why can't a 2P-Set re-add a removed element?</summary>

Removal puts the element in the remove-set (tombstone) forever, and `contains` is "in add-set and not in remove-set". A later add doesn't change the remove-set.

</details>

<details>
<summary>4. When is it safe to throw away a tombstone?</summary>

When every replica has seen the deletion (causal stability). Otherwise a replica that hasn't seen it could re-introduce the deleted item during a merge.

</details>

<details>
<summary>5. Which needs reliable causal delivery: state-based or op-based CRDTs?</summary>

Op-based. State-based merges are idempotent and order-independent, so they work over duplicating, reordering networks.

</details>

<details>
<summary>6. Why is an LWW-Map better than one LWW-Register holding the whole object?</summary>

With one register, any two concurrent edits conflict and one is lost entirely. With per-key registers, edits to different keys both survive; only edits to the same key conflict.

</details>

---

## Practice

Create `crdt/` and implement, each with scenario tests from this chapter **and** a random convergence test using your `sim/` network:
- **Task 8:** G-Counter, PN-Counter.
- **Task 9:** LWW-Register (use your `clock.Timestamp`), MV-Register (use your vector clock).
- **Task 10:** G-Set, 2P-Set, OR-Set.
- **Task 11:** LWW-Map.

For each type, write down (in a comment or the test name) what happens for each pair of concurrent operations.

---

## Further reading
- Jake Lazaroff, *An Interactive Intro to CRDTs* (builds LWW register and map, step by step).
- Matthew Weidner, *CRDT Survey* (blog series).
- Shapiro, Preguiça, Baquero, Zawirski, *A comprehensive study of Convergent and Commutative Replicated Data Types* (2011). The catalog.
- Almeida, Shoker, Baquero, *Delta State Replicated Data Types*.
- Kleppmann & Beresford, *A Conflict-Free Replicated JSON Datatype* (2017).
- crdt.tech: papers, talks, libraries.

---

[← 4. Real-Time Transport & Scaling](04-realtime-transport-scaling.md) · [Index](README.md) · [6. Operational Transformation →](06-operational-transformation.md)
