# 4. Real-Time Transport & Scaling

> **In one line:** Pick a pipe (usually WebSocket), make it reliable on top (sequence numbers, acks, reconnects), route each document to one owner, and watch out for O(N²) fan-out.
>
> **You'll learn:** polling vs SSE vs WebSocket vs WebRTC vs WebTransport, message design, reliability on top of WebSocket, heartbeats, reconnect with backoff and jitter, the "one owner per document" pattern, scaling gateways, consistent hashing, fan-out math, backpressure, and security.
>
> **Before this:** [2. Replication, Consistency & Merging](02-replication-consistency-merging.md) (idempotency, dedup)

**Summary:**
- Browsers' default choice for collaboration is **WebSocket**: full-duplex, low overhead, one long-lived connection.
- TCP orders bytes on **one** connection. Reconnects break that, so add **app-level sequence numbers, acks, and dedup**.
- Detect dead connections with **heartbeats**; reconnect with **exponential backoff + jitter**.
- Route all clients of a document to **one owner process** (actor model). Scale with a gateway tier + consistent hashing + pub/sub.
- Fan-out is **N senders × N receivers**: batch per tick.
- Authenticate on connect, check `Origin`, authorize per document and per message.

---

## 1. Ways to push data to a browser

| Method | Direction | How it works | Latency | Typical use |
|---|---|---|---|---|
| Short polling | Client asks repeatedly | `GET /changes` every N seconds | Up to N seconds | Dashboards that tolerate delay |
| Long polling | Server → client (sort of) | Request stays open until there's data, then client asks again | Low, but one request per message batch | Old chat systems, fallback transport |
| **Server-Sent Events (SSE)** | Server → client only | One HTTP response that streams events; built-in auto-reconnect with `Last-Event-ID` | Low | Notifications, live feeds, LLM token streaming |
| **WebSocket** | Both ways | HTTP request upgraded to a persistent two-way connection | Low | Collaboration, chat, multiplayer |
| **WebRTC data channel** | Peer to peer | Browsers connect directly (needs a signaling server, plus STUN/TURN for NAT traversal); can be unordered and unreliable | Lowest (no server hop) | Video calls, P2P games, P2P collaboration |
| **WebTransport** | Both ways | Built on HTTP/3 (QUIC); multiple streams plus unreliable datagrams | Low, no head-of-line blocking | Newer games and media apps |

**Why WebSocket for collaboration:** two-way, works through most proxies and load balancers, supported everywhere, simple ordered stream per connection.

### 1.1 What a WebSocket connection looks like
It starts as an ordinary HTTP request asking to switch protocols (example from RFC 6455):
```
GET /doc/42 HTTP/1.1
Host: example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
Origin: https://example.com

HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```
After `101`, both sides send **frames** (text, binary, ping, pong, close) on the same TCP connection.

---

## 2. Designing messages

### 2.1 An envelope
Every message gets the same outer shape:
```text
{
  type:    "op" | "ack" | "sync" | "presence" | "join" | "error" | ...
  docId:   "doc-42"
  seq:     17          # sender's sequence number (for acks and dedup)
  payload: { ... }     # depends on type
}
```

### 2.2 A typical message set for a collaborative editor
| Type | Direction | Purpose |
|---|---|---|
| `join {docId, lastSeenRev}` | C → S | Open a document; tell the server what I already have |
| `snapshot {rev, state}` / `ops {fromRev, ops[]}` | S → C | Initial load or catch-up |
| `op {seq, baseRev, op}` | C → S | My edit |
| `ack {seq, rev}` | S → C | Your edit was accepted as revision `rev` |
| `remoteOp {rev, op, author}` | S → C | Someone else's edit |
| `presence {cursor, selection}` | both | Ephemeral, can be dropped (Chapter 9) |
| `error {code, message}` | S → C | Permission denied, invalid op, please resync |

### 2.3 Encoding
- **JSON:** easy to debug; bigger and slower.
- **Binary** (MessagePack, Protobuf, or custom): smaller and faster. Yjs uses its own compact binary encoding (`lib0`). Figma uses a custom binary format.
- Version your protocol (`v: 2`) so old clients can be detected and told to reload.

---

## 3. Reliability on top of WebSocket

A single WebSocket (TCP) connection delivers in order without loss **while it's alive**. But connections drop: Wi-Fi switches, laptop sleeps, server deploys. When a connection drops, you don't know which of the last messages arrived.

### 3.1 The pattern: seq numbers, acks, resend, dedup
```text
CLIENT
  unacked = ordered list of sent ops
  on local op:     op.seq = nextSeq(); unacked.append(op); send(op)
  on ack(seq):     remove all ops with op.seq <= seq from unacked
  on reconnect:    send join(lastSeenRev); then resend everything in unacked (same seq numbers)

SERVER
  lastSeq[clientId] = highest seq applied for this client
  on op from clientId:
      if op.seq <= lastSeq[clientId]: re-send ack; ignore      # duplicate after reconnect
      apply op; lastSeq[clientId] = op.seq; ack
```

### 3.2 Walkthrough: connection drops mid-send
```
Client sends op seq=5 and seq=6. Connection drops.
Server had received seq=5 (applied, ack lost) but not seq=6.

Client reconnects: join(lastSeenRev = 40), resends seq=5 and seq=6.
Server: seq=5 <= lastSeq(5) → duplicate, re-ack, skip.
        seq=6 > 5            → apply, ack.
Server also sends remote ops after rev 40 that the client missed.
Result: no lost edits, no duplicated edits ✓
```

### 3.3 Catch-up after reconnect
The client says what it has (`lastSeenRev`, or a state vector for CRDTs, Chapter 8). The server replies with:
- the missing ops, if it still has them in its log, or
- a fresh snapshot, if the client is too far behind.

---

## 4. Heartbeats: detecting dead connections

A TCP connection can be **half-open**: one side vanished (phone lost signal), the other still thinks it's connected. Also, NAT routers and load balancers silently drop **idle** connections. AWS Application Load Balancers, for example, close connections idle for 60 seconds by default.

```text
every 20 seconds:
    send ping
if no pong (or any message) within 10 more seconds:
    close the connection and treat the client as disconnected
```
Both sides should do this: the server to free resources and update presence; the client to reconnect quickly.

---

## 5. Reconnecting without a stampede

If a server restarts, **all** its clients reconnect at the same moment. If they all retry at exactly 1 s, 2 s, 4 s..., the new server gets hit by waves of traffic (the **thundering herd**). Add randomness.

```text
attempt = 0
loop:
    if connect() succeeds:
        attempt = 0
        return
    delay = random(0, min(MAX_DELAY, BASE_DELAY * 2^attempt))   # "full jitter"
    sleep(delay)
    attempt = attempt + 1
```
With `BASE_DELAY = 500 ms` and `MAX_DELAY = 30 s`, retries spread out instead of arriving in synchronized waves. (The AWS Architecture Blog post *Exponential Backoff And Jitter* compares variants.)

---

## 6. One owner per document

The simplest way to get a single, agreed order of edits: **every edit for document X goes through one process that owns X.**

```
           ┌────────────┐
 Alice ───►│            │
 Bob   ───►│ Doc-42     │──► op log / storage
 Carol ───►│ session    │
           │ (in memory)│──► broadcast to Alice, Bob, Carol
           └────────────┘
 One goroutine / actor / process per open document.
 It holds: the current doc, revision number, connected clients, presence.
```

Benefits:
- **Ordering is trivial:** the owner assigns revision numbers one by one.
- **No locks across servers:** only one writer per document.
- **Fast:** the document lives in memory while it's open.

Real systems built this way:
- **Discord** models each guild (server) as an Elixir process.
- **Figma** routes all clients of a file to the same multiplayer server, which holds that file in memory.
- **Google Docs**-style OT needs a single ordering point per document (Chapter 6).

---

## 7. Scaling out

### 7.1 Split connections from logic
```
                         ┌────────────────── Gateway tier ──────────────────┐
 clients ─── LB ───────► │  GW1        GW2        GW3       (hold sockets, │
 (WebSocket)             │                                    auth, fan-out)│
                         └──────┬──────────┬──────────┬─────────────────────┘
                                │  route by docId     │
                         ┌──────▼──────────▼──────────▼─────────────────────┐
                         │  Session/doc tier: S1 owns doc-42, S2 owns doc-7 │
                         └──────┬───────────────────────────────────────────┘
                                ▼
                         op log DB · snapshot store · pub/sub
```
- **Gateways** hold many idle connections cheaply, terminate TLS, check auth, and forward messages.
- **Session servers** own documents and do the real work.
- Slack has described a similar split: gateway servers that hold WebSocket connections, and channel servers that own channel state, located by consistent hashing.

### 7.2 Finding the owner: consistent hashing
Put servers on a ring by hashing their names. A document belongs to the **first server clockwise** from `hash(docId)`.

```
                 hash ring (0 … 2³²)
                       S1
                  ●────●────●
               ●              ●  ← doc-42 hashes here → next server clockwise is S2
             S3                S2
               ●              ●
                  ●────●────●
```
- Adding or removing a server moves only the documents next to it, not all of them.
- **Virtual nodes:** place each server at many points on the ring so load spreads evenly.
- Alternative: a **registry** (etcd, ZooKeeper, Redis) holding `docId → owner` with a **lease**, so exactly one server owns a document at a time.

### 7.3 Fan-out across gateways
Alice is on GW1, Bob is on GW3, the doc owner is S2. S2 publishes each op to a channel `doc-42`; gateways with clients in `doc-42` subscribe and forward.
- **Redis Pub/Sub, NATS:** low latency, fire-and-forget fan-out (fine because the op log is the source of truth).
- **Kafka:** durable log; good for downstream consumers (search indexing, analytics, notifications), less suited as the live fan-out path for typing.

### 7.4 Failover
```text
owner S2 crashes
→ its lease on doc-42 expires (e.g. after 10 s)
→ another server takes the lease, loads snapshot + ops from storage (Chapter 8)
→ clients notice the broken connection, reconnect with backoff,
  resend unacked ops (deduplicated by seq), and catch up
```

### 7.5 Hot documents
One document with thousands of viewers (an all-hands agenda, a viral board):
- Separate **editors** from **viewers**; viewers get batched updates at lower frequency.
- Fan out through several gateways (a tree), not one process sending 10,000 messages.
- Throttle presence: show "+120 others" instead of 120 cursors.

---

## 8. Capacity: the numbers that matter

### 8.1 Connections
- An idle connection costs memory for buffers and bookkeeping (in Go, typically a couple of goroutines plus read/write buffers): roughly **tens of KB**. 100,000 connections ≈ a few GB of RAM.
- Each connection is a **file descriptor**: raise the OS limit (`ulimit -n`).
- Rule of thumb: tens to hundreds of thousands of mostly-idle connections per well-tuned server.

### 8.2 Fan-out is O(N²)
```
50 people in one doc, each sending cursor updates 20 times per second.

Inbound:                    50 × 20                = 1,000 messages/s
Naive outbound (each update to the 49 others): 1,000 × 49 = 49,000 messages/s  ✗

Batched per server tick (20 ticks/s, one combined message per client per tick):
Outbound:                   50 × 20                = 1,000 messages/s  ✓ (each a bit bigger)
```
Batching per tick turns N² messages into N messages per tick. This is why game servers and collaboration servers run on a **tick**.

---

## 9. Backpressure: slow clients

A client on a bad connection can't receive as fast as the server sends. Its outgoing buffer grows until the server runs out of memory.

```text
each connection has a bounded send queue (e.g. 256 messages)

when enqueueing a message:
    if it's presence and the queue is above half full:
        drop it (presence is ephemeral; only the latest matters)
    else if the queue is full:
        close the connection with "please resync"
        (the client will reconnect and catch up from the log or a snapshot)
```
Never silently drop **document ops**. Either deliver them or force a resync.

---

## 10. Security checklist

- **Bind dev servers to `127.0.0.1`**, not `0.0.0.0`, so only your machine can reach them.
- **Authenticate on connect:** a session cookie or a short-lived token. Browsers can't set custom headers on WebSocket requests, so tokens usually go in a cookie or are sent as the first message (avoid URLs; they end up in logs).
- **Check the `Origin` header.** Browsers send cookies with cross-site WebSocket requests, so without an Origin check a malicious site can open a socket as the logged-in user (**Cross-Site WebSocket Hijacking**).
- **Authorize per document** on `join`, and **per message** (a viewer must not send ops).
- **Validate every op** on the server (size, shape, ranges). CRDT merges accept anything structurally valid; the server must enforce what's *allowed*.
- **Limits:** maximum message size, messages per second per client, documents per connection.
- Re-check permissions when they change (kick a user whose access was revoked).

---

## 11. Interview angle

- **"How do you scale WebSockets to millions of users?"** Gateway tier holding connections, stateless except for sockets; route by `docId` with consistent hashing or a lease registry; pub/sub between tiers; batching; heartbeats; reconnect with jitter.
- **"What happens when the server holding a document dies?"** Lease expires, new owner loads snapshot + log, clients reconnect and resend unacked ops, dedup by `(clientId, seq)`.
- **"SSE or WebSocket?"** SSE for one-way server → client streams; WebSocket when the client sends frequently too.

---

## 12. Common mistakes

- No heartbeats: "ghost" users stay online forever.
- Reconnect loops without jitter that DDoS your own servers after a deploy.
- Assuming TCP ordering survives reconnects.
- Unbounded per-client send buffers.
- Forgetting authorization on each message, not just on connect.

---

## Check yourself

<details>
<summary>1. A client resends op seq=12 after reconnecting, but the server already applied it. What should happen?</summary>

The server sees seq 12 ≤ lastSeq for that client, skips applying it, and re-sends the ack so the client can remove it from its unacked list.

</details>

<details>
<summary>2. Why add jitter to reconnect backoff?</summary>

Without randomness, every client disconnected by the same event retries at the same moments, creating synchronized traffic spikes (thundering herd) that can knock the server over again.

</details>

<details>
<summary>3. 100 users, cursor updates at 10/s each, no batching. How many outbound messages per second?</summary>

Inbound 100 × 10 = 1,000/s; each goes to 99 others → 99,000 outbound/s. With per-tick batching at 10 ticks/s: 100 × 10 = 1,000/s.

</details>

<details>
<summary>4. Why is "one owner per document" so useful?</summary>

It gives a single place that orders all edits for that document (simple revision numbers, no cross-server locking) and lets the document live in memory while it's open.

</details>

<details>
<summary>5. What is Cross-Site WebSocket Hijacking and how do you prevent it?</summary>

A malicious site opens a WebSocket to your server from the victim's browser; the browser attaches the victim's cookies. Prevent it by checking the `Origin` header against an allowlist (and/or using tokens that a third-party page can't read).

</details>

---

## Practice

- **Task 7 — WebSocket hub** (extend `cmd/cursors` or a new `cmd/hub`), **listening on `127.0.0.1`**:
  - Rooms by `docId`; join/leave; broadcast within a room.
  - Heartbeat: ping every 20 s, close after 30 s of silence.
  - Bounded send queue per client; drop presence when full, disconnect on ops.
  - Client: reconnect with full-jitter backoff; resend unacked messages with the same `seq`; server dedups.
  - Test: kill the connection randomly (or add a "drop every N messages" switch) and check that no op is lost or duplicated.

---

## Further reading
- RFC 6455 (The WebSocket Protocol), sections 1 and 4 are enough.
- AWS Architecture Blog, *Exponential Backoff And Jitter*.
- Slack Engineering, *Real-time Messaging* (gateway and channel servers).
- Discord Engineering blog posts on scaling Elixir.
- Karger et al., *Consistent Hashing and Random Trees* (1997), or any good consistent hashing tutorial.

---

[← 3. Lerp, Interpolation & Netcode](03-lerp-interpolation-netcode.md) · [Index](README.md) · [5. CRDT Fundamentals →](05-crdt-fundamentals.md)
