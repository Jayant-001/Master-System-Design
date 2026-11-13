# 19: Collaborative Doc Editor

> In Live Canvas (04) conflicts were easy: the last pixel wins. In a text editor that rule would **delete people's words**. Here you learn how many people can edit the same text at once, even offline, and all end up with the same document without losing anything.

---

## The story

You're building a shared text editor. Many people type in the same document **at the same time**. They see each other's cursors. Someone edits on a plane for 2 hours and syncs when they land.

The rules:
- Everyone must end up with **exactly the same text**.
- Nothing anyone typed is **lost or jumbled**, unless someone deleted it on purpose.
- It must feel **instant**. Your own typing never waits for the server.

## How it works (plain words)

Each edit is an **operation**: "insert 'a' at position 5" or "delete position 9". The problem: while your edit travels to others, they're editing too, so **position 5 on your screen is not position 5 on theirs**. There are two main families of solutions:

- **OT (Operational Transformation):** when two edits happen at the same time, **adjust** (transform) one against the other. "Bob inserted 3 letters before position 5, so Alice's position 5 is now 8." Usually needs a central server to decide the order.
- **CRDTs (Conflict-free Replicated Data Types):** give **every character a unique ID**, and describe positions as "after character X" instead of a number. Then edits can be merged **in any order**, and everyone ends up the same, with no central server needed.

## What you'll learn

Why naive positions break · convergence · intention preservation · OT and transform functions · client-server OT · sequence CRDTs · unique IDs and ordering · tombstones · idempotent and commutative operations · Lamport clocks · vector clocks and causality · delta sync after offline · the interleaving problem · tombstone garbage collection · compact encoding · document sessions and presence · scaling stateful sessions.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Operation (op)** | One edit: an insert or a delete. |
| **Replica** | One copy of the document (each user's browser, and the server). |
| **Convergence** | All replicas end up with identical text after receiving all ops. |
| **Intention preservation** | The result matches what each user meant. Their word stays a word, in the place they typed it. |
| **OT** | Adjusting concurrent operations against each other so they still make sense. |
| **CRDT** | A data structure designed so that merging in any order always gives the same result. |
| **Tombstone** | A "deleted" marker left in place of a deleted character, so other replicas can still refer to it. |
| **Lamport clock** | A counter that gives events an order consistent with cause and effect. |
| **Vector clock** | One counter per replica. It can tell whether two events happened "one after the other" or "at the same time". |
| **Causal order** | If B happened because of A, everyone applies A before B. |
| **Presence / awareness** | Cursors, selections, and "who's online". Temporary and OK to lose. |

## Scope

| In | Out |
|---|---|
| Plain text only, sync engine, server, bot editors, offline sync, presence | Rich text (bold, lists) and comments (stretch) |
| A **network simulator** that delays, reorders, duplicates, and disconnects | Pretty editor UI |

**Tech:** Write the core **sync algorithm as a library** in your language (Java or Go). Test it with **bot editors**, no UI needed. A browser UI is optional: if you want it, write the client logic in TypeScript (AI can build the textarea UI, but the sync logic should be yours). WebSockets for transport. Postgres or files for storing documents.

---

## Base system spec

### Functional requirements
1. Open a document. Get its current text and the information needed to sync from there.
2. Type and delete. Changes show **locally at once**, and reach others within ~200 ms on a good network.
3. Go offline, keep editing, and come back. Everything merges.
4. See other users' cursors.

### Transport

```
GET /docs/{id}                      -> snapshot (format: your design)
WS  /docs/{id}/sync
    client -> server : ops, acks, presence updates
    server -> client : ops from others, acks, presence
```

### Network simulator + bot editors (build these first)
- **Bots** type random inserts and deletes at random positions, at a chosen speed. Some bots type **whole words** in one place (you'll need that in L4).
- Between bots and server (or between bots directly, from L2): configurable **delay**, **reordering**, **duplicate delivery**, and **disconnects** (offline for minutes).
- Every bot records which characters **it** inserted and deleted.

### Convergence checker (your referee)

| ID | Invariant | From |
|---|---|---|
| E1 | After all ops are delivered, **every replica's text is identical** | L0 |
| E2 | Every character a bot inserted is present, unless some bot deleted it | L0 |
| E3 | A word typed in one go by one user stays together (not mixed with another user's letters) | L4 |
| E4 | Memory and storage overhead stays within a bound you set | L5 |

Run each test **1,000 times with different random seeds**. One failure means a bug.

### Results table: `docs/results.md`

| Level | Algorithm | Bots | Network chaos | Runs | E1 pass | E2 pass | E3 pass | Sync after offline | Memory ÷ visible text |
|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: The naive way (and watching it break)

**Incident:** Clients send "insert char at index i" / "delete index i" to the server. The server applies them in arrival order and broadcasts them. Two users type at the same time, and the texts **diverge**: different users see different documents.

**Done when:**
- [ ] The naive version works with 1 user.
- [ ] You **reproduced divergence** with 2 bots and network delay, **deterministically** (fixed seed).
- [ ] You wrote down the **exact sequence** of ops that caused it, step by step, as a timeline.

**Predict first:** With 2 bots and 100 ms delay, how many edits until the first divergence?

**Design questions:**
1. Why does "index 5" mean different things to different users? Draw it.
2. The server applies ops in one order for everyone. Why does each **sender** still end up different? (Their own edit was applied locally **first**.)
3. What's the difference between **convergence** (same text) and **intention preservation** (the right text)? Find a case that converges but is wrong.
4. Could you just lock the document while someone types? What would that feel like?

**Search:** `operational transformation introduction`, `convergence causality intention preservation CCI`, `collaborative editing concurrency problem`.

**Self-evaluation:**
- 🟢 You can reproduce the divergence on demand and explain it with your timeline.
- 🔴 You can only make it happen "sometimes".

---

## Level 1: OT with a central server

**Incident:** You need convergence now, and you have a server.

**Done when:**
- [ ] OT for insert and delete: the **transform functions** written yourself.
- [ ] Client-server model: the server decides one order. Clients keep track of their pending (unacknowledged) ops and transform incoming ops against them.
- [ ] **E1 and E2 pass** with 10 bots and random delays, **1,000/1,000 runs**.
- [ ] Your own typing never waits for the server.

**Predict first:** Which pair of concurrent ops will be the hardest to transform correctly?

**Design questions:**
1. What does `transform(opA, opB)` return? Work through all 4 cases: insert/insert, insert/delete, delete/insert, delete/delete.
2. Two users insert at the **same position** at the same time. Who goes first? The rule must be the same on every replica. What do you use to break the tie?
3. What must a client keep in memory: sent but not acknowledged ops, plus not yet sent ops? Why both?
4. Why is OT with a central server much simpler than OT between peers with no server? (Look up the TP1/TP2 properties.)
5. What happens to a client that was offline for an hour, with OT?

**Search:** `operational transformation tutorial`, `Jupiter collaboration system`, `ot.js client server`, `transformation property TP1 TP2`, `Google Wave OT`.

**Self-evaluation:**
- 🟢 1,000/1,000 runs converge, including with duplicates and reordering handled by your server.
- 🔴 It only converges when the network is in order.

<details><summary>Hint (tie-breaking)</summary>
Every replica must make the same choice without talking to each other. What do all replicas know about an op that never changes?
</details>

---

## Level 2: CRDT (no central order needed)

**Incident:** The product wants **offline editing** for hours, and maybe peer-to-peer sync between devices without the server. OT gets very hard without a central order.

**Done when:**
- [ ] A **sequence CRDT** (RGA or similar) written yourself. Every character gets a unique ID (replica ID + counter).
- [ ] Ops can be applied **in any order**, and **duplicates are ignored**. E1 and E2 pass **1,000/1,000** with random reordering and duplicates.
- [ ] Bots can sync **directly with each other**, not only through the server.
- [ ] Memory overhead per character measured.

**Predict first:** Memory per visible character in your CRDT vs a plain string?

**Design questions:**
1. How does a unique ID per character give a **stable position** that doesn't shift when others edit?
2. Two users insert right after the same character at the same time. How do all replicas pick the same order?
3. Why can't you remove a deleted character right away? What would break if another replica's insert refers to it?
4. Why must ops be **commutative** (any order gives the same result) and **idempotent** (applying twice = once)? What does that let the network do?
5. OT vs CRDT: what did you gain, and what did you pay?

**Search:** `CRDT sequence RGA replicated growable array`, `Logoot`, `YATA Yjs algorithm`, `Automerge`, `Martin Kleppmann CRDT lectures`, `CRDTs the hard parts`, `commutative idempotent operations`.

**Self-evaluation:**
- 🟢 1,000/1,000 converge with full network chaos and peer-to-peer sync.
- 🔴 Your CRDT secretly depends on the server's order.

---

## Level 3: Causality and coming back online

**Incident:** (a) A "delete character X" arrives **before** the "insert character X" it refers to. (b) A user edits offline for 2 hours (5,000 ops) while others made 5,000 ops too. On reconnect, both sides re-send **everything** they've ever done, and the sync takes forever.

**Done when:**
- [ ] Ops whose dependencies haven't arrived yet are **buffered** until they can be applied (or your CRDT handles them another way you can explain).
- [ ] **Version vectors**: each replica can say what it has, so a sync sends **only the missing** ops.
- [ ] 2 hours offline + 5,000 ops on each side syncs in under a time you set. Bytes sent measured.
- [ ] E1 and E2 pass after every offline test.

**Predict first:** Bytes sent on reconnect: "send everything" vs "send only what's missing"?

**Design questions:**
1. Lamport clock vs vector clock: what can each one tell you? Which can tell "happened at the same time"?
2. How does a vector clock show that two edits were concurrent?
3. Version vectors grow with the number of replicas. What happens with 10,000 devices that ever edited the doc?
4. How do two replicas work out what the other is missing, in as few round trips as possible?
5. An op depends on another op that will **never** arrive (that device was lost). What happens?

**Search:** `Lamport timestamps`, `vector clocks`, `version vectors`, `causal broadcast`, `delta-state CRDT`, `anti-entropy sync`.

**Self-evaluation:**
- 🟢 Offline sync sends only missing ops, and you have the byte count.
- 🔴 Ops that arrive early are silently dropped.

---

## Level 4: The jumbled words (interleaving)

**Incident:** Alice types "Hello " and Bob types "World " at the **same spot** at the same time. Everyone converges (E1 passes!) to `HWeolrllod`. Technically consistent, but useless.

**Done when:**
- [ ] A test for E3: bots type whole words concurrently at the same position.
- [ ] You **measured** how often your CRDT interleaves.
- [ ] You changed the algorithm (or picked a different one) so E3 passes 1,000/1,000, and E1/E2 still pass.

**Predict first:** Does your L2 CRDT interleave? Does your L1 OT?

**Design questions:**
1. Why does interleaving happen in your CRDT? Trace it with two 3-letter words.
2. Which algorithms avoid it, and what do they do differently?
3. Does OT with a central server have the same problem? Test it.
4. Is E3 even always possible? Think of two users typing **one letter each** at the same time.

**Search:** `interleaving anomaly collaborative text editing Kleppmann`, `Fugue CRDT`, `RGA interleaving`, `YATA ordering`.

**Self-evaluation:**
- 🟢 You can explain the interleaving cause with a small drawn example, and E3 passes.
- 🔴 You "fixed" it by adding a random delay.

---

## Level 5: Six months later (the document got heavy)

**Incident:** A popular doc has been edited for 6 months. The visible text is 20 KB, but the CRDT state is **50 MB** of tombstones and IDs. Opening it takes 10 seconds on a phone.

**Done when:**
- [ ] Memory and storage size measured for a 6-month simulated history.
- [ ] **Compact encoding**: runs of characters typed by the same user in a row are stored together. Size measured before and after.
- [ ] **Snapshots** for fast loading. Load time measured before and after.
- [ ] **Tombstone garbage collection** when it's safe. You can explain **exactly** when that is.
- [ ] E4 passes.

**Predict first:** How much does run-length encoding shrink a realistic document?

**Design questions:**
1. When is it **safe** to remove a tombstone? What must you know about **all** replicas first?
2. A replica has been offline for a month. It still might send ops that refer to old tombstones. What do you do?
3. People usually type in runs (a whole sentence). How can you store a run with one ID instead of one per character?
4. Snapshot + recent ops vs replaying full history: what does each cost on load?

**Search:** `tombstone garbage collection CRDT`, `causal stability`, `run-length encoding CRDT`, `Automerge binary format`, `Yjs update encoding`, `snapshot compaction`.

**Self-evaluation:**
- 🟢 Size and load-time charts before and after, and a written rule for when GC is safe.
- 🔴 GC deletes tombstones on a timer, no matter what.

---

## Level 6: Busy documents and presence

**Incident:** 10,000 documents open. One company-wide doc has **300 editors**. Cursor updates from 300 people flood everyone's network. A server restart drops all sessions, and every client re-sends its whole state at once.

**Done when:**
- [ ] All editors of one document go to the **same server** (routing by document ID). Explain why.
- [ ] **Presence** (cursors, who's online) is separate from document ops: throttled, never stored, OK to lose.
- [ ] Bytes per second for presence measured at 300 editors, before and after throttling.
- [ ] **Server failover:** a document's session moves to another server. Clients reconnect with backoff + jitter and sync only what's missing (L3). E1 still passes.
- [ ] A very hot document: you have a plan, and measured where it breaks.

**Predict first:** At 300 editors sending cursor updates 10×/sec, how many messages per second does each client receive?

**Design questions:**
1. Why route all editors of a document to one server? What does that make easier, and what's the risk?
2. Presence vs document ops: what guarantees does each need?
3. How do you throttle presence without cursors feeling laggy?
4. A server dies. How do its documents move, and how do clients find the new server?
5. One document is too busy for one server. What are your options?

**Search:** `session affinity consistent hashing`, `presence awareness protocol Yjs`, `Figma multiplayer blog`, `ephemeral vs durable state`, `reconnect jitter`.

**Self-evaluation:**
- 🟢 You have a failover test under load that still converges, and presence bandwidth measured.
- 🔴 Presence is saved in the document history.

---

## Stretch levels
- **Undo** per user in a shared document: what does "undo" mean when others edited after you?
- **Rich text:** bold and italic ranges that survive concurrent edits.
- **Comments** anchored to a text range that moves when the text changes.
- **End-to-end encryption:** the server syncs data it can't read.

## Graduation: answer out loud
1. Why do naive positions break in collaborative editing? Draw it.
2. OT vs CRDT: how does each work, and when would you choose each?
3. How does a CRDT guarantee everyone ends up with the same text?
4. Lamport clocks vs vector clocks: what can each tell you?
5. What's the interleaving problem?
6. How do you keep a 6-month-old document small?
7. How would you scale to 1M open documents?

## Taking it further
A real browser editor using your library · offline-first notes app · rich text · compare your library with Yjs/Automerge on size and speed · a code editor with live collaboration.
