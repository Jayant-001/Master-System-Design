# 8. Sync, Storage & Offline (Local-First)

> **In one line:** Replicas tell each other what they already have (a version vector, hashes, or a revision number), send only what's missing, and servers store a log of changes plus periodic snapshots.
>
> **You'll learn:** delta sync with state vectors, the Yjs sync protocol, hash-graph sync (Automerge, git), Merkle trees and anti-entropy, gossip, server storage designs (op log, snapshots, compaction), version history, offline queues, local-first software, schema evolution, and security/permissions for CRDT data.
>
> **Before this:** [1. Clocks](01-time-clocks-causality.md) (version vectors), [5](05-crdt-fundamentals.md) and [7](07-sequence-crdts-text-lists.md) (CRDTs).

**Summary:**
- **State vector** = `clientID → number of ops I have from that client`. Exchange vectors, send the difference.
- **Yjs sync:** SyncStep1 (my state vector) → SyncStep2 (your missing updates), both ways, then live updates.
- **Hash graphs** (Automerge, git): changes reference their parents by hash; compare "heads".
- **Merkle trees:** compare hashes top-down to find which ranges differ.
- **Storage:** append-only op log + snapshots every N ops; load = snapshot + later ops.
- **Local-first:** the local copy is the source of truth for the UI; the network is for sync and backup.
- CRDTs merge anything well-formed; **the server must still enforce permissions**.

---

## 1. The sync question

Two replicas meet (a client reconnects, two servers gossip, two phones come online). Each must answer:
> **What does the other side have that I don't, and how do I send just that?**

Options, from simplest to most capable:

| Approach | What's exchanged | Good when |
|---|---|---|
| Send everything | Full state | Small documents |
| Revision number | "I'm at rev 40" → server sends ops 41+ | Central server with a total order (OT, server-authoritative) |
| **State/version vector** | `{A:5, B:3}` | Each client's ops form a numbered sequence (Yjs, many CRDTs) |
| **Hash graph** | Hashes of latest changes ("heads") | No trusted order, peer-to-peer, need integrity (Automerge, git) |
| **Merkle tree** | Hashes of ranges of data | Big key-value datasets between servers (Dynamo, Cassandra) |

---

## 2. Delta sync with state vectors

Each replica's state vector says how many operations it has from each client (ops from one client are numbered 1, 2, 3, ...).

### 2.1 Walkthrough
```
Replica A has: A1..A5 and B1..B3     state vector {A:5, B:3}
Replica B has: A1..A2 and B1..B7     state vector {A:2, B:7}

A sends {A:5, B:3} to B.  B computes what A is missing:
  client A: A has 5, B has 2 → nothing
  client B: A has 3, B has 7 → send B4..B7

B sends {A:2, B:7} to A.  A computes what B is missing:
  client A: B has 2, A has 5 → send A3..A5
  client B: nothing

After exchange both have A1..A5, B1..B7 ✓
```

### 2.2 Pseudocode
```text
function missingFor(remoteVector):
    result = []
    for each client c in my ops:
        for seq in (remoteVector[c] + 1) .. myVector[c]:
            result.append(op(c, seq))
    return result
```
This is just the version-vector idea from Chapter 1 used for sync.

---

## 3. The Yjs sync protocol

```
 Client                                   Server (or another peer)
   │  SyncStep1: my state vector  ──────────►│
   │◄──────── SyncStep2: updates you're missing
   │◄──────── SyncStep1: server's state vector
   │  SyncStep2: updates the server is missing ►│
   │                                          │
   │  Update (live edits) ◄─────────────────► │   from now on, just stream changes
```
- Both sides do Step 1 and Step 2, so the exchange is symmetric. The same protocol works client↔server and peer↔peer.
- Updates are CRDT ops, so applying one twice or in a different order is harmless.
- Yjs encodes updates in a compact binary format and can **merge many small updates into one** for storage.

---

## 4. Hash-graph sync (Automerge, git)

Each change records the **hashes of the changes it depends on** (like git commits referencing parent commits). The latest changes are the **heads**.
```
   c1 ── c2 ── c3 ── c5      ← heads: [c5, c4]
           \
            c4
```
- To sync: exchange heads. If you have the other side's heads, you know everything before them too.
- If not, find what's missing by walking backward. Automerge's sync protocol also sends a **Bloom filter** (a compact probabilistic set) of its recent changes so the other side can guess what to send in fewer round trips.
- Hashes give **integrity**: a peer can't silently alter history without changing every later hash. Useful for peer-to-peer and untrusted relays.

---

## 5. Merkle trees and anti-entropy

Used between **servers** holding big datasets (Dynamo, Cassandra repair, also Actual Budget between devices).

```
                 root = H(H12, H34)
                /                  \
        H12 = H(H1,H2)        H34 = H(H3,H4)
         /        \            /        \
   H1(keys 0-24) H2(25-49) H3(50-74)  H4(75-99)
```
```text
function diff(myNode, theirNode):
    if hash(myNode) == hash(theirNode): return        # this whole range matches
    if myNode is a leaf: exchange the items in this range
    else: diff(myNode.left, theirNode.left); diff(myNode.right, theirNode.right)
```
If only keys 60–62 differ, you compare a handful of hashes and send three items, instead of comparing all 100 items.

**Real example:** Actual Budget (local-first budgeting app) syncs devices using LWW-per-field messages with HLC timestamps, plus a Merkle tree over message timestamps to quickly find the point where two devices diverged.

---

## 6. Gossip

Each node periodically picks a **random peer** and syncs with it. Information spreads like a rumor: the number of nodes that know something roughly doubles each round, so it reaches all N nodes in about **log₂ N** rounds (1,000 nodes → around 10–15 rounds).

Used for: cluster membership and failure detection (Cassandra; the SWIM protocol used by HashiCorp Serf/Consul), spreading CRDT state between servers, epidemic broadcast.

---

## 7. Server-side storage for collaborative documents

### 7.1 Option A: append-only op log
```text
doc_ops(doc_id, rev, client_id, client_seq, op_bytes, created_at)
    primary key (doc_id, rev)
    unique (doc_id, client_id, client_seq)        # dedup retries (Chapter 4)
```
- Writing is fast (append).
- Loading is **slow** if a document has a million ops: replay everything.

### 7.2 Option B: snapshots + log (what most systems do)
```text
snapshots(doc_id, rev, state_bytes or object-store key)

function loadDocument(docId):
    snap  = latest snapshot for docId          # (rev, state)
    ops   = ops for docId with rev > snap.rev, ordered by rev
    state = snap.state
    for op in ops: state = apply(state, op)
    return state, lastRev

function maybeCompact(docId):                  # e.g. every 500 ops or after 5 idle minutes
    state, rev = loadDocument(docId)
    write snapshot (docId, rev, state)
    optionally archive ops older than the snapshot (keep them if you want full history)
```

### 7.3 Where to put it
| Data | Typical store |
|---|---|
| Doc metadata (title, owner, permissions) | Relational DB (Postgres, MySQL) |
| Op log | DynamoDB / Cassandra / Bigtable (key = doc ID, sort = rev), or Postgres for smaller scale |
| Snapshots | Object storage (S3) with a pointer in the DB |
| Hot, recent updates | In memory on the doc owner, optionally Redis |

**DynamoDB example:**
```text
PK = "DOC#42"   SK = "OP#00000017"   op = <bytes>, client = "c9", clientSeq = 112
PK = "DOC#42"   SK = "SNAP#00000500" s3Key = "snapshots/42/500.bin"

Write op rev 17 with a condition: attribute_not_exists(SK)
→ if two servers ever both think they own doc 42, only one can write rev 17
```

### 7.4 CRDT documents
For Yjs-like CRDTs, store **binary updates** in the log, and periodically **merge** them into one compacted update (the snapshot). Because merges are idempotent and order-independent, a client can always be sent "snapshot + any updates since", even with overlap.

---

## 8. Version history and time travel

- Keep snapshots at intervals plus the ops in between → you can rebuild the document at **any** revision.
- **Named versions** ("v1 sent to client"): store a pointer to a revision.
- **Diff two versions:** rebuild both and compare, or replay the ops between them.
- **Restore an old version:** don't delete history. Compute the changes that turn the current document into the old one and apply them as **new ops**. Other users then receive the restore like any other edit.
- Google Docs, Figma, and Notion all expose version history built roughly this way.

---

## 9. Offline support

### 9.1 Offline queue
```text
on local edit:
    apply locally
    save op to local storage (IndexedDB in browsers, SQLite on mobile)
    if online: send

on reconnect:
    sync (state vector / revision exchange)
    send all locally stored unacked ops
    delete each from the queue once acked
```

### 9.2 OT vs CRDT offline
- **OT:** a client offline for a day comes back with hundreds of ops based on an old revision. The server must transform each against everything that happened meanwhile, and must keep that much history. Possible, but painful.
- **CRDT:** just exchange missing ops; merge is designed for arbitrary delay.

### 9.3 Local-first software
Ink & Switch's essay *Local-first software* (2019) proposes seven ideals: **fast** (no spinners), **multi-device**, **offline**, **collaboration**, **longevity** (your data outlives the vendor), **privacy**, and **user control**.

Architecture:
```
UI ⇄ local database (source of truth for the UI)
          ⇅ background sync engine (CRDT/ops)
       server (relay, backup, permissions) and/or other peers
```
Examples of apps in this direction: Linear (keeps data locally in the browser and syncs changes in the background), Actual Budget, apps built on Automerge/Yjs/Replicache-style sync engines, Excalidraw (local storage + optional live collaboration).

---

## 10. Schema evolution

Your app's data shape changes over time (`title` becomes `name`; a string becomes a list). With offline clients, **old and new versions of the app edit the same document at the same time**.
- Store a **schema version** in the document.
- Prefer **additive** changes (add fields; don't rename or remove).
- Make readers **tolerant** (ignore unknown fields, default missing ones).
- Ink & Switch's **Cambria** project explores "lenses" that translate between schema versions in both directions.
- Force very old clients to upgrade before syncing.

---

## 11. Permissions and security

A CRDT will merge **any** well-formed update. It has no idea who's allowed to do what. So:
- The **server validates** every incoming update: is this user allowed to edit this document? This part of it? Is the update well-formed and within size limits?
- **Read-only users:** never accept their updates; you may still send them updates.
- **Fine-grained permissions** (only edit your own comments): check which objects an op touches.
- **Revocation:** a revoked user's offline edits should be rejected on sync.
- **End-to-end encryption:** Excalidraw's collaboration rooms keep the encryption key in the URL fragment (after `#`, never sent to the server); the server only relays encrypted messages, and clients do all merging. Downside: the server can't validate or compact what it can't read.
- **Peer-to-peer without a server:** ops must be **signed**, and every peer must check signatures and permissions itself. An active research area.

---

## 12. Interview angle

- **"How does a client that was offline for a week catch up?"** It sends its state vector (or last revision); the server sends missing ops, or a fresh snapshot if too far behind; the client then sends its queued ops (deduplicated by ID).
- **"How do you store a collaborative document?"** Append-only op log keyed by `(docId, rev)`, periodic snapshots in object storage, metadata in a relational DB; load = snapshot + later ops.
- **"How do two data centers find which keys differ?"** Merkle trees (anti-entropy).
- **"How do you restore an old version without breaking collaboration?"** Apply the reverse changes as new ops; never rewrite history.

---

## 13. Common mistakes

- Loading documents by replaying the entire op log forever (no snapshots).
- Deleting old ops while clients may still need to catch up from them.
- Trusting client updates because "the CRDT will merge it".
- Renaming fields in a schema that offline clients still use.
- Forgetting idempotency keys on the op log (duplicate rows after retries).

---

## Check yourself

<details>
<summary>1. You have {A:4, B:9, C:1}; a peer has {A:6, B:9}. What do you send, and what do you receive?</summary>

You send C1 (the peer has 0 from C). You receive A5 and A6. B is equal on both.

</details>

<details>
<summary>2. Why is "snapshot + ops since snapshot" better than replaying the whole log?</summary>

Loading time stays bounded: you start from a recent full state and apply only a few ops, instead of replaying the document's entire history.

</details>

<details>
<summary>3. What does a Merkle tree save you during anti-entropy?</summary>

Comparing and transferring everything. Matching hashes prove whole ranges are identical, so you only descend into and exchange the ranges that differ.

</details>

<details>
<summary>4. Why is long offline editing harder for OT than for CRDTs?</summary>

OT must transform every offline op against every concurrent op that happened in the meantime, and the server must keep that history. CRDT ops carry unique IDs and merge regardless of delay.

</details>

<details>
<summary>5. A CRDT library merges any update. Why does the server still check updates?</summary>

The CRDT only guarantees convergence, not permission. The server must stop unauthorized users, malformed or oversized updates, and edits from revoked users.

</details>

---

## Practice

- **Task 16 — Delta sync:** give your CRDT replicas a state vector and a `MissingFor(remoteVector)` method. Test the walkthrough in 2.1, then a random test: random ops on 3 replicas, sync pairs randomly via vector exchange only, assert convergence.
- **Task 17 — Persistence:** for your editor server, store ops in a file or SQLite with `(docId, rev)` and `(clientId, clientSeq)` uniqueness; snapshot every N ops; restart the server and check documents load correctly and quickly.
- **Optional:** a tiny Merkle tree over a key-value map with a `Diff` that returns differing keys.

---

## Further reading
- Yjs documentation: *Document Updates*, the y-protocols sync and awareness specs.
- Automerge documentation on sync and its binary format.
- Ink & Switch, *Local-first software: You own your data, in spite of the cloud* (2019).
- James Long, *CRDTs for Mortals* (talk about Actual Budget's sync with HLC and Merkle trees).
- The Dynamo paper, sections on anti-entropy and Merkle trees.
- Ink & Switch, *Cambria* (schema evolution).

---

[← 7. Sequence CRDTs](07-sequence-crdts-text-lists.md) · [Index](README.md) · [9. Presence & Awareness →](09-presence-awareness.md)
