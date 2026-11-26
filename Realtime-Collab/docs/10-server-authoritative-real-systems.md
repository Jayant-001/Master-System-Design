# 10. Server-Authoritative Sync & Real Systems

> **In one line:** Many production apps skip "pure" OT or CRDTs: the server decides the final order, clients apply changes optimistically and rebase their pending changes when the server answers, and conflicts are resolved per property with simple rules.
>
> **You'll learn:** the optimistic-mutation + rebase pattern, and case studies of Figma, Google Docs, Excalidraw, Yjs-based apps, games, Linear-style sync engines, multi-region databases, and chat ordering. Ends with a decision guide.
>
> **Before this:** [3](03-lerp-interpolation-netcode.md) (prediction/reconciliation), [5](05-crdt-fundamentals.md), [6](06-operational-transformation.md), [7](07-sequence-crdts-text-lists.md).

**Summary:**
- **Server-authoritative + rebase:** client keeps `confirmed state + pending local mutations`; server applies mutations in its order; client replaces confirmed state and replays still-pending mutations.
- **Figma:** document = `Map<ObjectID, Map<Property, Value>>`; server-ordered **LWW per property**; fractional indexing for child order; server rejects cycles; client ignores server values for properties it has unacknowledged changes to.
- **Excalidraw:** LWW per element (`version`, `versionNonce`), `isDeleted` tombstones, fractional `index` for z-order, end-to-end encrypted relay.
- Pick per data type: text → OT/sequence CRDT; objects/properties → LWW per property; lists of objects → fractional indexing; counters/sets across regions → CRDT types.

---

## 1. The third way: the server decides

OT and CRDTs both try to make **every replica compute the same answer**. A simpler idea: let **one place (the server) compute the answer**, and make clients good at **predicting** it and **correcting** themselves.

Why it's popular:
- Most apps have a server anyway (auth, storage, billing).
- **Validation and permissions** are easy: the server checks each change.
- Arbitrary business logic works ("you can't move a card into a locked column"), which CRDTs can't express.
- Simpler to build than a full text CRDT or OT.

The cost: the final answer needs the server; long offline periods are handled less gracefully.

---

## 2. The pattern: optimistic mutations + rebase

This is the same idea as game prediction and reconciliation (Chapter 3, section 8), applied to app data.

```text
client keeps:
    confirmed = last state received from the server
    pending   = list of local mutations not yet confirmed (each with an ID)
    view      = confirmed with all pending mutations applied     ← what the UI shows

on user action:
    m = mutation(name, args, id = nextId())
    pending.append(m)
    view = apply(view, m)              # instant
    send m to server

server, for each mutation in arrival order:
    run it with the authoritative logic (may change, adjust, or reject it)
    record that mutation m.id from this client was processed
    broadcast the new state (or a patch) plus "last processed mutation IDs"

client, on server update:
    confirmed = new server state
    remove from pending every mutation the server says it processed
    view = confirmed; for m in pending: view = apply(view, m)   # rebase
```

**Walkthrough: a todo list**
```
Server: [ "buy milk" ]

Alice (offline-ish, slow network): adds "call mom"   → pending [A1], view shows both
Bob: deletes "buy milk"                               → server applies first: []

Server then applies Alice's A1 → [ "call mom" ]
Alice receives update: confirmed = ["call mom"], A1 processed → pending [] → view ["call mom"] ✓
```

**Real use:** Replicache (by Rocicorp) is built exactly around this: client-side "mutators" run optimistically; the server runs its own authoritative version of the same mutators; clients rebase pending mutations on the server's state. Many other sync engines follow the same shape.

---

## 3. Case study: Figma

From Figma's engineering blog (*How Figma's multiplayer technology works*):

### 3.1 Data model
A Figma document is a **tree of objects** (like the HTML DOM). You can think of it as:
```text
Map< ObjectID, Map< Property, Value > >

object 17 → { type: "rect", x: 10, y: 20, fill: "#f00", parent: 3, position: "a1V" }
```

### 3.2 Conflicts
- Two clients change **different properties** of the same object (one changes `fill`, the other `x`) → **no conflict**, both apply.
- Two clients change the **same property** of the same object → the **last value the server received wins** (LWW per property, ordered by the server).
- Objects are created with **client-generated unique IDs** (client ID + counter), so creating objects never needs a server round trip.

### 3.3 Avoiding flicker
When Alice drags a rectangle, her client has **unacknowledged** changes to `x`/`y`. If an older server message about `x` arrives meanwhile, applying it would make the rectangle jump back. So the client **ignores incoming server values for properties it has unacknowledged local changes to**, until its own change is acknowledged.

### 3.4 Ordering children and reparenting
- Each object's order among its siblings is a **fractional index** (Chapter 7, section 8), stored as a property → reordering is one LWW write.
- `parent` is also a property. Concurrent reparenting could create a **cycle** (A inside B, B inside A). The **server rejects** parent changes that would create a cycle.

### 3.5 Takeaway
Figma isn't a "true" CRDT system (the server is the authority), but each rule is CRDT-inspired and works per property. For a design tool with many independent objects and properties, this is simpler and faster than a general JSON CRDT.

---

## 4. Case study: Google Docs

- **OT with a central server per document** (Chapter 6): clients send ops with a base revision; the server transforms, assigns a revision, stores, and broadcasts.
- **Presence** (cursors with names) on a separate channel.
- **Comments and suggestions** anchored to ranges of text, which must move with edits.
- **Version history** from stored revisions and snapshots.
- **Offline mode** keeps edits locally and syncs on reconnect.
- Practical limits exist on how many people can have a document open for editing at once (around 100), partly because of fan-out.

---

## 5. Case study: Excalidraw (look at your own `design.excalidraw`)

| Field in your file | What it is |
|---|---|
| `version` | Increases on every edit of the element |
| `versionNonce` | Random number regenerated on every edit |
| `isDeleted` | **Tombstone**: deleted elements are kept with `isDeleted: true` so the delete propagates to other copies |
| `index` (`"a0"`, `"a1"`, `"aCV"`) | **Fractional index** for z-order |
| `updated` | Wall-clock time, for display and housekeeping |

- **Merge rule:** when two copies of an element meet, keep the one with the higher `version`; if versions are equal, use `versionNonce` as a deterministic tie-breaker. That's an **LWW-Register per element**, state-based (whole elements are sent).
- **Collaboration server:** a WebSocket relay. Rooms are **end-to-end encrypted**: the key is in the URL fragment (after `#`), so the server only forwards encrypted blobs. Clients do all the merging.
- **Local-first:** your drawing is saved in the browser's storage and works offline.

---

## 6. Case study: Yjs-based apps

Yjs is used by many editors and tools: Tiptap (with its Hocuspocus server), JupyterLab's real-time collaboration, and editor bindings for ProseMirror, CodeMirror, Monaco, and Quill. A typical architecture:
```
browser: editor ⇄ Y.Doc ⇄ provider (WebSocket / WebRTC / IndexedDB)
                                   │
server:  WebSocket relay + auth + persistence (store merged updates)
```
The server can be "dumb" (relay + storage) because merging happens in every client. For permissions it still inspects or gates updates (Chapter 8, section 11).

The **Zed** code editor also built collaboration on CRDTs for its text buffers.

---

## 7. Case study: multiplayer games

(Chapter 3.) Authoritative server simulating at a fixed tick rate; clients send inputs; clients predict their own entity and reconcile; other entities are interpolated ~100 ms in the past; the server rewinds for hit checks (lag compensation). The same "predict, then correct" pattern as section 2.

---

## 8. Case study: Linear-style sync engines

Linear (the issue tracker) has talked publicly about its sync engine. The general shape, shared by many modern apps:
- The client keeps a **local copy** of the user's data (in IndexedDB) and renders from it → instant UI.
- Changes are applied locally and sent to the server as **transactions**.
- The server applies them in order and streams **ordered change records** (each with an increasing sync ID) to every client.
- A client that reconnects asks for "everything after sync ID N".
- Conflicts on simple fields are resolved by the server, mostly last-writer-wins; rich text uses a text-specific approach.

This is server-authoritative sync + local-first UI.

---

## 9. Case study: multi-region databases

| System | Conflict handling between replicas/regions |
|---|---|
| **Amazon DynamoDB Global Tables** (default multi-region mode) | Last-writer-wins for concurrent writes to the same item |
| **Apache Cassandra** | LWW per cell using write timestamps; tombstones with a grace period |
| **Redis Enterprise Active-Active** | CRDT-based data types (counters, sets, strings with specific merge rules) |
| **Riak** | Siblings (MV-Register) or CRDT types (counters, sets, maps) |
| **Azure Cosmos DB** (multi-region writes) | LWW by default, or a custom conflict-resolution procedure |

Takeaway for design interviews: "multi-region active-active" always means **choosing a conflict-resolution policy per data type**.

---

## 10. Case study: chat ordering

- Messages need a **stable order** in each channel. Common approach: the server that owns the channel assigns IDs in order.
- **Snowflake IDs** (from Twitter, also used by Discord): 64-bit IDs = **timestamp** (milliseconds) + **worker ID** + **per-worker sequence**. Sortable by time, unique without coordination, generated by many servers in parallel.
- Clients show their own message immediately (optimistic, with a temporary ID), then replace it when the server's ID arrives.
- Edits and deletes are separate events referencing the message ID (LWW on the message content).

---

## 11. Comparison of approaches

| Approach | Best for | Examples | Pros | Cons |
|---|---|---|---|---|
| LWW per field/property | Objects with many independent properties | Figma, Excalidraw, Cassandra | Simple, cheap | Loses concurrent writes to the same field |
| Server-authoritative + rebase | Apps with business rules and a server | Replicache-based apps, Linear-style engines, games | Validation, permissions, custom logic | Needs server for final state; offline is limited |
| OT | Text with a central server | Google Docs, Etherpad, ShareDB | Small metadata, mature rich text | Central server; complex transforms; long offline is hard |
| Sequence CRDT | Text/lists, offline, P2P | Yjs, Automerge, Loro, Zed | Offline/P2P, server can be a relay | Metadata, tombstones, harder permissions |
| CRDT counters/sets | Multi-region data | Redis Active-Active, Riak | Coordination-free | Can't enforce global invariants |
| Coordination (locks, transactions) | Invariants: payments, bookings | Banks, ticketing | Strict correctness | Latency, not offline |

---

## 12. Decision guide

```
What are you syncing?
│
├─ Free text / rich text?
│    ├─ Need offline for long periods or peer-to-peer? ──► Sequence CRDT (Yjs, Automerge, Loro)
│    └─ Always online with a central server?          ──► OT or a CRDT with a server; both fine
│
├─ Objects with properties (shapes, cards, rows)?
│    └─ LWW per property, server-ordered (Figma-style) + client-generated IDs
│         └─ Ordering of items? ──► fractional index property
│         └─ Tree (parent links)? ──► server rejects cycles / tree-move CRDT
│
├─ Business rules or invariants (stock, bookings, permissions)?
│    └─ Server-authoritative mutations (+ optimistic UI and rebase); coordination for the invariant
│
├─ Counters / sets across regions?
│    └─ CRDT types (G-Counter, OR-Set) or the database's built-in policy
│
├─ Fast-moving positions (games, cursors)?
│    └─ Authoritative server + prediction for self + interpolation for others
│
└─ Ephemeral (cursors, typing, who's online)?
     └─ Presence channel, lossy, latest-wins, TTL (Chapter 9)
```

---

## Check yourself

<details>
<summary>1. In the rebase pattern, what does the client do when the server's update arrives?</summary>

Replace its confirmed state with the server's, drop pending mutations the server has processed, and re-apply the remaining pending mutations on top to rebuild what the UI shows.

</details>

<details>
<summary>2. Alice changes a shape's color while Bob moves it. Does Figma's model see a conflict?</summary>

No. Conflicts are per (object, property). Color and position are different properties, so both changes apply.

</details>

<details>
<summary>3. Why does Figma's client ignore some incoming server values?</summary>

If the client has unacknowledged local changes to a property, an older server value for that property would make the object flicker back. The client ignores incoming values for that property until its own change is acknowledged.

</details>

<details>
<summary>4. What two CRDT ideas can you find in an Excalidraw file?</summary>

LWW-Register per element (`version` + `versionNonce` tie-break) with `isDeleted` tombstones, and fractional indexing (`index`) for z-order.

</details>

<details>
<summary>5. Why not use a CRDT for an inventory count that must never go negative?</summary>

"Never negative" is a global invariant; replicas can't check it without coordinating. Use a server-authoritative decrement (or escrow/bounded counters).

</details>

---

## Practice (optional, a fun capstone)

- **Task 19 — Mini Figma board** (`cmd/board/` + `web/board.html`), server on `127.0.0.1`:
  - Shapes with client-generated IDs; properties `x, y, w, h, color, parent, position`.
  - Server-ordered LWW per property; client ignores server values for its unacknowledged properties.
  - Fractional index for z-order; reject reparenting cycles.
  - Reuse your cursors + presence.

---

## Further reading
- Evan Wallace, *How Figma's multiplayer technology works* (Figma blog, 2019).
- Figma blog, *Realtime editing of ordered sequences*.
- Replicache documentation, "How it works".
- Excalidraw blog posts on collaboration and end-to-end encryption.
- Talks by Tuomas Artman on Linear's sync engine.
- Twitter Engineering, *Announcing Snowflake* (2010).

---

[← 9. Presence & Awareness](09-presence-awareness.md) · [Index](README.md) · [11. Testing Distributed Algorithms →](11-testing-distributed-algorithms.md)
