# 04: Live Canvas (A Shared Pixel Board for Everyone)

> You built a WebSocket server and Pub/Sub in `../../Concurrency/`. Now 10,000 people are connected at once. Every pixel any of them places has to reach all the others in under a second, across many servers, through deploys and bot attacks.

---

## The story

You're launching a 72-hour community event: a **1000 × 1000 pixel board** with a **16-color palette**. Anyone can place **one pixel every 5 seconds**. Everyone sees every change **live**. Factions form, art appears, and wars break out over territory. Traffic spikes when someone posts it on social media.

The best part: it's **visual**. You can send the link to friends and watch your system work, or watch it fail.

## What you'll learn

WebSocket protocol · slow consumers and backpressure · bit-packing and binary encoding · snapshot + delta sync · sequence numbers and gap detection · CDN caching · pub/sub semantics · horizontal scaling of stateful connections · last-writer-wins · fan-out math · batching · interest management · distributed rate limiting · fail-open vs fail-closed · reconnect storms · connection draining · event logs and capacity estimation.

## Scope

| In | Out |
|---|---|
| Board API, WebSocket push, cooldowns, multi-server, history | Accounts and login (use an anonymous ID cookie; you'll revisit it in L4) |
| A **bot swarm** load generator | Pretty UI (a basic HTML canvas is enough; AI can build it) |

**Tech:** Go or Java for the server. Redis. Later NATS or Redis Streams if *you* decide. k6 (it supports WebSockets) or your own bot swarm. Prometheus + Grafana.

---

## Base system spec

### Functional requirements
1. `GET /board` returns the full current board.
2. Clients open a WebSocket and receive **every pixel change** live.
3. Clients place a pixel `(x, y, color)`. The server enforces bounds, palette, and the **5s cooldown**.
4. When a client reconnects, it must end up with **exactly** the server's board.

### Non-functional targets (final)
- **End-to-end latency** (client A places → client B sees) p99 < 500ms. Revisit this in L3.
- 10,000+ concurrent connections across multiple servers.
- Every client's board converges to the server's board. **Checksum equal, always.**

### Protocol: you design it

Decide and **document** in `docs/protocol.md`: message types, encoding (JSON or binary), and what a client must do on connect, on gap, and on reconnect. You'll change it in L1 and L3. Version it.

### Bot swarm (your load generator)
N simulated clients that:
- Connect, fetch the board, and keep a local copy updated from the stream.
- Place pixels at a configurable rate, honoring cooldown (or violating it, in L4).
- Measure **e2e latency**: tag your own placements and time how long until you see them come back. Better: one bot places and another measures.
- At the end, compute a **checksum** of the local board and compare it with the server's.

### Metrics to export
Connections per server · messages in/out per second · bytes out per second · e2e latency histogram · per-connection send-queue depth · board fetch size and time · CPU and memory per server.

### Results table: `docs/results.md`

| Level | Servers | Clients | Placements/s | Msgs out/s | Bytes out/s | e2e p50 | e2e p99 | CPU | Checksum OK |
|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: One server

**Incident:** None. One server, an in-memory board, broadcast every placement to every connection.

**Done when:**
- [ ] A browser and 100 bots can paint and see each other.
- [ ] **Slow-consumer test:** one bot connects and **never reads** from its socket. The other 99 bots' e2e latency is unaffected, and the server's memory doesn't grow without limit.
- [ ] All bot checksums match at the end.

**Predict first:** What happens to everyone else when one client stops reading?

**Design questions:**
1. What is a WebSocket at the protocol level? What does the HTTP Upgrade handshake look like? What's a frame?
2. How does your broadcast loop write to 100 sockets? If one socket's kernel buffer is full, does your write **block**? What does that do to everyone else?
3. Per-connection send queues: how big? When one is full, do you drop messages, drop the client, or block? What does each choice mean for checksum convergence?
4. Who mutates the board, and how do you keep that safe with many connection handlers running at once?
5. How do you detect a client that disappeared without closing (laptop lid shut)? What are ping/pong frames for?

**Search:** `WebSocket protocol RFC 6455 handshake`, `WebSocket frames`, `slow consumer problem`, `backpressure`, `bounded queue drop policy`, `WebSocket ping pong keepalive`, `gorilla websocket hub example` (Go) / `Java WebSocket server` (Java).

**Self-evaluation:**
- 🟢 The slow-consumer test passes, and you can explain what happens to the slow client.
- 🔴 One slow client raises everyone's latency.

---

## Level 1: The board is too big

**Incident:** The board as JSON is many megabytes. At launch, 10,000 users load the page in the first minute. That's a large bandwidth bill and a slow first paint. Worse, some users' boards **don't match** the server's after joining during heavy painting.

**Done when:**
- [ ] Full board transfer is **under 1 MB** (work out the theoretical minimum first). Compression measured.
- [ ] Board state lives in a store that other servers will be able to share (you'll need that in L2).
- [ ] **Join-race test:** a client joins during heavy painting, 100 times. Its checksum matches the server's **100/100**.

**Predict first:** How many bytes is the board at minimum? With compression on a typical half-painted board?

**Design questions:**
1. How many bits per pixel do you need for 16 colors? What's the total board size? What about 32 colors?
2. Where do you store the board so multiple servers can read and write it? Look at Redis `BITFIELD`. What does it offer that a plain `SET` of the whole board doesn't?
3. **The join race:** a client fetches the snapshot at time T and subscribes to the live stream at T+50ms. Pixels placed in between are lost. How do you guarantee **no gap and no double-apply**? What information must the snapshot and every update carry?
4. What does the client do if it detects a gap?
5. Can the board snapshot be served from a **CDN**? How stale can it be, given your answer to Q3?
6. JSON vs binary for live updates: measure the bytes per update for each.

**Search:** `bit packing`, `Redis BITFIELD`, `snapshot and delta synchronization`, `sequence number gap detection`, `CDN Cache-Control max-age stale-while-revalidate`, `MessagePack vs JSON`, `ArrayBuffer WebSocket binary`.

**Self-evaluation:**
- 🟢 The join-race test passes 100/100, and you can explain why it can't fail.
- 🔴 Your fix for the race is "wait a bit before subscribing".

<details><summary>Hint (join race)</summary>
What if the client subscribed first and fetched second? What would it need to do with the updates it buffered?
</details>

---

## Level 2: The second server

**Incident:** One server is at 100% CPU. You add a second behind the load balancer. Now users on server A don't see pixels placed by users on server B.

**Done when:**
- [ ] 3 servers, 1,000 clients spread across them. Every placement reaches every client.
- [ ] **Conflict test:** two clients on different servers paint the same pixel within the same millisecond, 1,000 times. **All clients converge to the same final color** every time.
- [ ] Kill one server's connection to the pub/sub system for 10 seconds. You know exactly what its clients missed, and they recover.

**Predict first:** Will you need sticky sessions? Why or why not?

**Design questions:**
1. What state does each server hold? Which of it must be shared, and which can stay local?
2. **Pub/sub options:** Redis Pub/Sub, Redis Streams, NATS core, NATS JetStream, Kafka. What delivery guarantee does each give? What happens to messages published while a subscriber is disconnected?
3. Two placements on the same pixel arrive at different servers at the same moment. **Who decides the winner**, and do all clients agree on it? Where is the single point that orders writes?
4. Does the ordering decided by your store match the ordering of messages in your pub/sub? If not, how can clients still converge?
5. Does your load balancer support WebSockets properly? What timeouts on it will silently kill idle connections?

**Search:** `Redis Pub/Sub delivery semantics`, `Redis Streams consumer`, `NATS core vs JetStream`, `last writer wins`, `total order broadcast`, `WebSocket horizontal scaling`, `load balancer WebSocket idle timeout`, `sticky sessions`.

**Self-evaluation:**
- 🟢 The conflict test converges 1,000/1,000.
- 🟢 You can explain what your pub/sub choice loses during a disconnect, and how clients recover from it.
- 🔴 Convergence depends on clocks being in sync across servers.

---

## Level 3: Fan-out explosion

**Incident:** A streamer shares the link. 10,000 clients are connected and placing 2,000 pixels/sec in total. Servers are at 100% CPU, the bandwidth graph goes vertical, and e2e latency reaches 10 seconds.

**Done when:**
- [ ] You did the **fan-out math** *before* optimizing: messages/sec and bytes/sec out, per server and total.
- [ ] You applied at least 3 optimizations. Measure each one **separately** (same load, before/after).
- [ ] e2e p99 meets your target at 10k clients and 2k placements/s.
- [ ] You know your **max clients per server** at that placement rate.

**Predict first:** Messages out per second = ? Which resource saturates first: CPU, bandwidth, or memory?

**Design questions:**
1. Write the formula: placements/s × clients = messages/s. Bytes/s? Per server, with S servers?
2. **Batching:** send updates every N ms instead of immediately. What's the trade-off between latency and throughput? How do you choose N?
3. Do you serialize the same update once per client, or once total? Measure the difference.
4. **Coalescing:** the same pixel changes 5 times in one batch window. Does every client need all 5?
5. Does every client need **every** pixel? What if a client only views part of the board (zoomed in)? How would you partition subscriptions by region?
6. Where else is CPU going: TLS, syscalls per message, allocations? Use the profiler.

**Search:** `fan-out`, `message batching`, `Nagle's algorithm`, `serialize once broadcast`, `interest management`, `spatial partitioning`, `quadtree`, `zero-allocation serialization`, `writev`, `TLS CPU cost`.

**Self-evaluation:**
- 🟢 You have a before/after table per optimization and know which one mattered most.
- 🔴 You optimized before measuring, and can't say which change helped.

---

## Level 4: Bots and the cooldown

**Incident:** Factions write bots that ignore the cooldown and spray thousands of pixels. With 3 servers, a bot that spreads its requests across all of them gets **3× the rate**. Then someone creates 10,000 anonymous IDs.

**Done when:**
- [ ] **1 pixel per user per 5 seconds**, enforced across all servers. A bot spraying all servers gets exactly 1 per 5s. Measure it.
- [ ] You decided what happens when the cooldown store is **down**, and you can defend the choice.
- [ ] You picked an identity strategy and wrote down its known weaknesses.

**Predict first:** If the cooldown lives in each server's memory, what's the effective rate for a bot that sprays all servers?

**Design questions:**
1. Where must the cooldown be enforced so that it holds across servers? What atomic operation gives you "allow at most once per 5s per user"?
2. **Clock skew:** do your servers' clocks matter to your cooldown? Why or why not?
3. The cooldown store is down. Fail open (everyone can paint without limits) or fail closed (nobody can paint)? Is the right answer the same for a pixel game and for a payment system?
4. What is a "user"? IP, cookie, account, device fingerprint? What does each cost real users (shared IPs, NAT, mobile)?
5. A **Sybil attack**: one person, 10,000 identities. What makes creating identities expensive enough without making the game annoying?

**Search:** `Redis SET NX EX`, `distributed rate limiting`, `fail open vs fail closed`, `clock skew`, `Sybil attack`, `proof of work captcha`, `CGNAT shared IP`.

**Self-evaluation:**
- 🟢 You measured the spray-test rate and it's exact.
- 🔴 Cooldown is checked on one server and trusted on another.

---

## Level 5: Deploy day

**Incident:** You deploy a fix by restarting all servers. 10,000 clients reconnect **within the same second**, and all of them fetch the full board. Redis CPU hits 100%, the origin melts, clients time out and retry, and recovery takes 6 minutes. Every deploy now causes an outage.

**Done when:**
- [ ] **Graceful shutdown:** a server stops accepting new connections, tells clients to move, and drains within a bound you choose.
- [ ] Clients reconnect with **backoff + jitter**.
- [ ] Reconnecting clients **resume from their last sequence number** (deltas only). They fall back to a full snapshot only when they must.
- [ ] Servers limit how fast they accept new connections.
- [ ] **Rolling-restart test** of all servers under 10k clients: no client permanently diverges, the peak reconnect rate is bounded, and Redis/origin p99 stays under your limit. Graph it.

**Predict first:** Without jitter, what's the peak reconnects per second? Fetches per second?

**Design questions:**
1. How does a server tell a client "reconnect elsewhere, but not right now"? Look at WebSocket close codes.
2. How long do you keep recent updates for resume? What structure? How many bytes? What decides when a client is "too far behind" and needs a full snapshot?
3. Rolling restart order and pace: how many servers at a time? How do you know one is drained?
4. Plot reconnects/sec with no jitter, full jitter, and decorrelated jitter.
5. What about clients that reconnect *aggressively* on purpose (bots, broken clients)?

**Search:** `thundering herd reconnect`, `exponential backoff jitter`, `WebSocket close codes 1001 1012 1013`, `graceful shutdown connection draining`, `ring buffer`, `rolling deployment`, `admission control new connections`.

**Self-evaluation:**
- 🟢 You have the rolling-restart graph, and deploys are no longer outages.
- 🔴 Every reconnect still fetches the full board.

---

## Level 6: History and timelapse

**Incident:** The event ends. Everyone wants (a) a **timelapse video** of all 72 hours and (b) to click any pixel and see **who painted it and when**, including its full history.

**Done when:**
- [ ] Every placement is durably logged. Your size estimate is compared with the actual size.
- [ ] Point query: "history of pixel (x, y)" returns in < 100ms.
- [ ] Timelapse: you can render the board at any time T within a few seconds.
- [ ] The logging didn't slow down the live path. Measure it.

**Predict first:** Total size of 72 hours of placements at your L3 rate?

**Design questions:**
1. **Capacity estimate:** placements/s × bytes per event × 72h. Write it down **before** building.
2. Where does the log go: Postgres, Kafka, append-only files, object storage + Parquet? Compare write cost, query cost, and storage cost.
3. "Board at time T": replay from zero vs periodic snapshots + replay. How often do you snapshot? Trade-off?
4. "History of pixel (x, y)": what index or layout makes it fast? Does that conflict with the layout that makes timelapse fast?
5. How do you write the log without adding latency to placing a pixel?

**Search:** `event sourcing snapshots`, `append-only log`, `Parquet columnar format`, `row vs columnar storage`, `capacity estimation back of the envelope`, `write-behind`, `async logging`.

**Self-evaluation:**
- 🟢 Your estimate was within 2× of actual, or you know why it wasn't.
- 🔴 The history write sits on the critical path of placing a pixel.

---

## Graduation: answer out loud
1. Design a live shared board for 1M concurrent users.
2. How do you scale WebSockets horizontally? What's hard about stateful connections?
3. Walk me through how a reconnecting client gets back in sync without gaps.
4. Do the fan-out math for 1M users at 10k placements/s. What breaks first?
5. Fail open or fail closed for the cooldown? Defend it.
6. How do you deploy a WebSocket service without causing an outage?

## Taking it further
Zoomable board with region subscriptions · moderation tools (undo a region, ban a user and roll back their pixels) · teams and leaderboards · multi-region edge servers · run a real event with friends and post the timelapse.
