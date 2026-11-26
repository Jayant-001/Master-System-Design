# 12. System Design Interviews

> **In one line:** Every collaboration question comes down to the same choices: what's the data model, how are concurrent edits resolved, who orders them, how do you fan out, and how do you store, recover, and scale it.
>
> **You'll learn:** a framework for collaboration questions, full walkthroughs for **Google Docs** and **Figma/whiteboard**, shorter designs for presence, a multiplayer game backend, a Notion-like block editor, and distributed counters, plus common follow-up questions, numbers to remember, and pitfalls.
>
> **Before this:** ideally all earlier chapters. Each design links back to where the details live.

---

## 1. A framework for collaboration questions

1. **Clarify requirements.** Who edits what? How many concurrent editors per document? Offline? Version history? Comments? Permissions? Latency target for local echo and for remote updates?
2. **Data model.** Text? Objects with properties? Lists? Trees? Counters?
3. **Conflict strategy per data type.** OT / sequence CRDT / LWW per property / server-authoritative / coordination (Chapters 5–7, 10).
4. **Who orders edits?** One owner per document (Chapter 4), or no central order (CRDT, P2P).
5. **Transport and fan-out.** WebSocket gateways, pub/sub, batching per tick (Chapter 4).
6. **Storage.** Op log + snapshots, metadata DB, object store (Chapter 8).
7. **Presence.** Separate lossy channel (Chapter 9).
8. **Scale and failure.** Sharding by document, failover, reconnect, hot documents (Chapter 4).
9. **Extras.** Version history, comments, search, notifications, analytics (via an event stream).

Say the trade-offs out loud: "I'll use OT because we have a central server and want small metadata; if offline editing were a hard requirement I'd pick a CRDT like Yjs."

---

## 2. Design Google Docs

### 2.1 Requirements
**Functional:** create/open documents; real-time co-editing of rich text; see others' cursors; comments; version history; share with view/comment/edit permissions; limited offline.

**Non-functional:** local echo instant (< 16 ms, it's local); remote edits visible in < 200–500 ms; no lost edits; high availability; documents up to hundreds of pages; typically a few concurrent editors, sometimes ~100.

### 2.2 Back-of-envelope (example numbers; say they're assumptions)
```
100M daily users; at peak 10M with a document open (10M WebSocket connections)
20% actively typing at any moment; edits batched to ~2 messages/s per typist
  → 10M × 0.2 × 2 = 4M edit messages/s
~100 bytes per op → 400 MB/s → ~35 TB/day of raw ops → snapshots + compaction + cold storage
Connections: at ~100k per gateway → ~100 gateways (+ headroom)
```

### 2.3 API
```text
REST:
  POST /docs                      → create
  GET  /docs/{id}                 → metadata + permissions
  GET  /docs/{id}/versions        → version history list
  POST /docs/{id}/share           → permissions

WebSocket /docs/{id}/live:
  C→S  join {lastSeenRev}
  S→C  snapshot {rev, content} | ops {fromRev, ops[]}
  C→S  op {clientId, seq, baseRev, op}
  S→C  ack {seq, rev}  |  remoteOp {rev, op, author}
  both presence {cursor, selection}  (lossy)
```

### 2.4 High-level architecture
```
 Browser ──WebSocket──► LB ──► Gateway tier (auth, sockets, fan-out)
                                   │  route by docId (consistent hashing / lease registry)
                                   ▼
                          Doc session service (one owner per open doc, in memory)
                           │      │             │
                 append ops│      │snapshots    │publish ops & presence
                           ▼      ▼             ▼
                  Op log DB   Object store   Pub/sub (to other gateways)
               (doc_id, rev)    (S3)
                           │
                           └──► Event stream (Kafka) → search indexing, notifications, analytics
 Metadata DB (Postgres): docs, owners, permissions, comments
```

### 2.5 Edit flow, step by step
1. Alice types; her client applies the op locally (instant) and sends `op {seq, baseRev}` (Chapter 6, client state machine).
2. Gateway authenticates, checks Alice has **edit** permission, forwards to the doc owner.
3. Owner transforms the op against ops after `baseRev`, applies it, assigns revision `r`.
4. Owner appends `(docId, r, op, clientId, seq)` to the op log (conditional write so only one writer per revision; unique `(clientId, seq)` for dedup).
5. Owner acks Alice and publishes the op; gateways forward it to Bob and Carol.
6. Every N ops (or idle time) the owner writes a snapshot.

### 2.6 OT vs CRDT choice
- **OT:** fits naturally because there's a central owner per document anyway; small metadata; mature rich-text OT exists. Offline is limited (reasonable for Docs).
- **CRDT (e.g. Yjs):** better for long offline and simpler server (relay + storage), more metadata.
- Either is defensible; explain why.

### 2.7 Loading a document
Snapshot + ops since the snapshot (Chapter 8). Large docs: load the visible part first.

### 2.8 Presence
Cursor and selection on the lossy channel, anchored to text positions transformed through ops; batched per tick; hidden above a threshold of viewers.

### 2.9 Comments
Stored in the metadata DB with a **range anchor** (start/end positions that are transformed with edits, or character IDs in a CRDT). Comment threads are ordinary rows; notifications go through the event stream.

### 2.10 Version history
Named and automatic versions point to revisions; rebuild from the nearest snapshot. Restoring = applying the inverse changes as new ops.

### 2.11 Failure handling
- **Gateway dies:** clients reconnect (backoff + jitter) to another gateway and resend unacked ops; dedup by `(clientId, seq)`.
- **Doc owner dies:** lease expires; a new owner loads snapshot + log; clients reconnect.
- **Op log write fails:** don't ack; client resends later.
- **Split brain (two owners):** conditional writes on `(docId, rev)` let only one succeed; the loser steps down.

### 2.12 Scaling hot documents
Viewers separated from editors; viewers get batched updates; presence summarized; fan-out across many gateways via pub/sub.

---

## 3. Design Figma / a collaborative whiteboard

### 3.1 Requirements
Many shapes per file (thousands to millions); real-time co-editing; cursors; comments; components; version history; large images.

### 3.2 Data model
```text
Map<ObjectID, Map<Property, Value>>
object = { id (client-generated), type, parent, position (fractional index), x, y, w, h, fill, text, ... }
```

### 3.3 Conflict resolution
- **LWW per property**, ordered by the server (Chapter 10, section 3).
- Client ignores server values for properties with unacknowledged local changes (no flicker).
- **Ordering:** fractional index per object among siblings.
- **Reparenting:** server rejects cycles.
- **Text inside shapes:** can use a small text CRDT/OT per text object, or LWW on the whole text for simple labels.

### 3.4 Architecture
Same as Docs: gateways, one multiplayer owner per open file (file kept in memory), op log + snapshots, presence channel. Large binary assets (images, fonts) go to **object storage + CDN**, referenced by hash from the document.

### 3.5 Large files
Split into pages; load the current page first; stream the rest. Only send updates and cursors for the page each user is viewing (interest management).

### 3.6 Cursors
World coordinates, throttled, batched per tick, interpolated on receivers (Chapter 3, section 12).

---

## 4. Design "who's viewing this page" (presence for any app)

- Clients heartbeat every 15–30 s via WebSocket (or an HTTP endpoint if no socket).
- Store in Redis: sorted set per page with score = last seen; prune older than 30–60 s (Chapter 9, section 8.2).
- Push changes over pub/sub to subscribed clients; or clients poll the count every 30 s if real-time isn't required.
- Show avatars up to N, then "+42".
- Numbers: 1M concurrent viewers heartbeating every 30 s ≈ 33k writes/s → a small Redis cluster, sharded by page ID.

---

## 5. Design a multiplayer game backend (state sync)

- Matchmaking places players into a **match server** in their region.
- Server simulates at a fixed tick (e.g. 30–60 Hz), receives inputs, sends snapshots (delta-compressed) at 20–30 Hz.
- Clients: prediction + reconciliation for self, interpolation for others (~100 ms), lag compensation on the server.
- UDP-based protocol (or WebTransport/WebRTC for browser games) to avoid head-of-line blocking.
- Persistence (inventory, scores) goes through a separate, transactional service, not the real-time loop.

---

## 6. Design a Notion-like block editor

- Document = **tree of blocks** (paragraph, heading, list item, table, embed); each block has an ID, type, properties, children order.
- Block properties: LWW per property. Children order: fractional index or a list CRDT. Moving blocks: tree move with cycle protection.
- Text inside each block: a **sequence CRDT or OT per block**, so editing different blocks never conflicts and documents stay fast.
- Permissions per page (subtree).
- Same infrastructure: owner per page, op log + snapshots, presence.

---

## 7. Design a distributed counter (likes/views at scale)

| Option | How | Trade-off |
|---|---|---|
| Single DB row with `UPDATE ... +1` | Simple | Hot row; doesn't scale for viral items |
| **Sharded counter** | N rows per item; increment a random one; sum on read (cache the sum) | Scales writes; reads cost more |
| **G-Counter per region** (CRDT) | Each region counts locally; exchange states; merge by max | Multi-region, coordination-free, eventually consistent |
| Stream + batch | Send events to Kafka; aggregate every few seconds | Very high throughput; slightly delayed |
| **Unique viewers** | HyperLogLog (approximate distinct count in ~12 KB per counter in Redis) | Small error (~1%), tiny memory |

For "each user can like once", use a set of user IDs (or an OR-Set across regions) and count its size.

---

## 8. Common follow-up questions (with short answers)

| Question | Answer |
|---|---|
| Two users type at the same position at the same time. What happens? | OT: tie-break by site ID during transform. CRDT: order concurrent inserts by ID. Both converge deterministically. |
| How do you guarantee no edit is lost? | Client keeps unacked ops; server acks only after durable append; resend on reconnect; dedup by `(clientId, seq)`. |
| What if the server crashes after writing but before acking? | Client resends; server sees duplicate seq, re-acks without re-applying. |
| How does a client catch up after being offline? | Sends last revision / state vector; server sends missing ops or a snapshot; client sends its queued ops. |
| How do you shard? | By document ID; one owner per document at a time (lease or consistent hashing). |
| A document has 10,000 viewers. | Separate viewers from editors, batch updates, summarize presence, fan out via multiple gateways. |
| How do you implement undo? | Per-user undo of own ops: inverse op, transformed through later ops (OT) or a new reversing op (CRDT). |
| How do you store history efficiently? | Op log + periodic snapshots; compress; move old ops to cold storage. |
| How are permissions enforced in a CRDT system? | Server validates every update; read-only users' updates rejected; E2EE limits what the server can check. |
| Why not lock paragraphs? | Bad UX (waiting), lock leaks on disconnect; OT/CRDT allow true simultaneous editing. |
| Why not LWW for the whole document? | Any concurrent edit would discard someone's work. |
| How do cursors stay correct when text changes? | Transform cursor positions through ops, or anchor to character IDs. |
| How do you handle a deploy without kicking everyone? | Drain gateways gradually; clients reconnect with jitter; doc owners hand over via leases and snapshots. |
| How do you search document contents? | Stream changes to a search indexer (Kafka → Elasticsearch/OpenSearch), updated asynchronously. |
| How do you keep latency low for global users? | Gateways in many regions; the doc owner in the region of most editors; local echo for your own edits. |

---

## 9. Numbers to remember (approximate)

| Thing | Number |
|---|---|
| One frame at 60 fps | 16.7 ms |
| Feels instant to humans | < 100 ms |
| Round trip within a data center | < 1 ms |
| Round trip US East ↔ US West | ~60–70 ms |
| Round trip US ↔ Europe | ~80–100 ms |
| Round trip US ↔ India | ~200–250 ms |
| Typical cursor send rate | 20–30 per second |
| Typical game tick rate | 20–60 per second |
| Interpolation delay | ~100 ms |
| Presence heartbeat / timeout | ~15 s / ~30 s |
| WebSocket ping interval | ~20–30 s (load balancers often close idle connections after 60 s) |
| Idle connections per tuned server | tens to hundreds of thousands |

---

## 10. Pitfalls in interviews

- Jumping to "use a CRDT" without saying **which** data type and **what** semantics.
- Forgetting presence is a separate channel.
- Ignoring the N² fan-out.
- No story for reconnects, duplicates, and server failover.
- Claiming exactly-once delivery instead of at-least-once + idempotency.
- Using wall-clock timestamps to order edits.
- Forgetting permissions on each message.

---

[← 11. Testing Distributed Algorithms](11-testing-distributed-algorithms.md) · [Index](README.md) · [13. Glossary & Resources →](13-glossary-and-resources.md)
