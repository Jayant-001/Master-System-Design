# 9. Presence & Awareness

> **In one line:** "Who's here and what are they pointing at" is high-frequency, temporary data. Send it on a separate lossy channel, expire it with heartbeats, and anchor text cursors to characters, not indexes.
>
> **You'll learn:** what presence is, why it's kept out of the document, its data model and protocol, timeouts, per-client clocks, relative cursor positions, canvas cursors, scaling presence fan-out, presence across servers (Redis TTL, CRDT-based), and typing indicators.
>
> **Before this:** [3. Lerp](03-lerp-interpolation-netcode.md) (smooth cursors) and [4. Transport](04-realtime-transport-scaling.md) (fan-out, heartbeats).

**Summary:**
- Presence = online users, cursors, selections, viewports, typing indicators, "follow me".
- **Not** part of the document: don't persist it, don't put it in history or undo, it's OK to drop updates.
- Each client publishes its **whole** presence state, throttled; others keep the latest per client (LWW with a per-client counter).
- Heartbeat every ~15 s; mark a client gone after ~30 s of silence; send "leave" on clean exit.
- Text cursors must be **relative** to characters (IDs or transformed positions).
- Fan-out is N²: batch per tick, only send to people on the same page/view.

---

## 1. What presence is

| Presence feature | Example |
|---|---|
| Who's online | Avatar stack at the top of a Google Doc |
| Live cursors | Figma's named arrows |
| Selections | Colored highlight of what Bob selected |
| Viewport | "Alice is looking at page 3" |
| Typing indicator | "Bob is typing…" |
| Follow mode | Your view follows the presenter's viewport |
| Status | "In a meeting", "Away" |

Yjs calls this **awareness**. Liveblocks and others call it **presence**.

---

## 2. Why keep it out of the document

| Document data | Presence data |
|---|---|
| Must never be lost | Losing an update is fine (the next one replaces it) |
| Stored forever, in version history | Never stored |
| Part of undo/redo | Never undone |
| Ordered, deduplicated, acked | Best-effort, latest wins |
| Changes a few times per second | Can change 20–60 times per second (mouse movement) |

Putting cursors into the CRDT/OT document would bloat history, slow undo, and waste storage. So collaborative apps run **two channels** on the same connection: a reliable one for document ops, and a lossy one for presence.

---

## 3. Data model

```text
presence[clientId] = {
    user:      { id, name, color, avatarUrl }
    cursor:    { x, y }                 # canvas apps: world coordinates
               or { anchorId }          # text apps: relative position (section 6)
    selection: { anchorId, headId } or [objectIds]
    viewport:  { page, x, y, zoom }
    clock:     17                       # increases with every update from this client
    lastSeen:  time                     # set by the receiver
}
```
A **clientId** is per connection or tab, not per user. One user with two tabs shows two cursors (or you group them by user ID in the UI).

---

## 4. Protocol

```text
on join:
    send my full presence state (clock = 1)
    receive everyone else's current presence

on my presence change (cursor moved, selection changed):
    update local state; clock = clock + 1
    send at most every ~40 ms (throttle), always the FULL state, not a diff

every ~15 s with no change:
    re-send my state (heartbeat), clock = clock + 1

on receive presence(clientId, state):
    if state.clock <= presence[clientId].clock: ignore      # stale or duplicate
    presence[clientId] = state; presence[clientId].lastSeen = now

every few seconds:
    remove any client with now - lastSeen > 30 s           # crashed, lost network

on clean leave (tab closed, navigation):
    send "remove me" (others delete my entry immediately)
```

Why send the **full state** each time? If an update is lost, the next one fixes everything. Diffs would need reliable delivery.

Why a **per-client clock**? Messages can arrive out of order through different servers. The clock is a tiny LWW-Register per client: the highest clock wins. Yjs's awareness protocol works this way, and treats a client as gone after about 30 seconds without an update.

---

## 5. Canvas cursors

- Send **world coordinates** (Chapter 3, section 12).
- Interpolate on the receiver (snapshot interpolation or smoothing).
- Hide the cursor after a few seconds idle; fade it out rather than snapping it away.
- Only render cursors that are inside the viewer's viewport.

---

## 6. Text cursors must be relative

An index goes stale as soon as someone edits before it.

```
Text: "Hello world". Bob's cursor is at index 6 (just before "w").
Alice inserts "Hey, " at index 0 → "Hey, Hello world"

If Bob's cursor is stored as index 6:  "Hey, H|ello world"   ✗ wrong place
If stored as "before the character with ID of 'w'":  "Hey, Hello |world"   ✓
```
Two ways to keep cursors correct:
- **CRDT:** store the cursor as a **relative position**: "just before (or after) the item with ID X". Yjs provides `RelativePosition` for exactly this. Convert to an index only when rendering.
- **OT:** transform cursor indexes through every op, like zero-length inserts (Chapter 6, section 9).

If the character a cursor is anchored to gets deleted, anchor to the nearest visible neighbor (a tombstone still has a position in the sequence, so it's easy).

---

## 7. Scaling presence

### 7.1 Fan-out math (from Chapter 4)
```
N users × updates per second × (N − 1) receivers
50 users × 25/s × 49 ≈ 61,000 messages/s for ONE document, unbatched
```

### 7.2 Techniques
| Technique | Effect |
|---|---|
| **Batch per tick** (e.g. 10–20 ticks/s): one message per receiver containing all changed presence | N² messages → N per tick |
| **Throttle senders** (~25/s for cursors, less for selections) | Less inbound traffic |
| **Interest management**: only send to users on the same page/frame/view | Big win in large docs |
| **Degrade for large rooms**: above ~50 users, stop sending cursors, show "+120 viewing" | Keeps big rooms usable |
| **Drop for slow clients**: presence is the first thing to drop under backpressure | Protects the server |
| **Quantize**: integer coordinates, short IDs | Smaller messages |

---

## 8. Presence across many servers

### 8.1 In memory on the document owner (simplest)
If all clients of a document connect through one owner (Chapter 4, section 6), presence is just a map in that process's memory. When the owner restarts, clients reconnect and re-send their state.

### 8.2 Redis with expiry (when clients are spread across servers)
```text
# each client, every ~10-15 s (and on change, throttled):
ZADD presence:doc42 <nowMillis> <clientId>          # sorted set, score = last seen
SET  presence:doc42:<clientId> <stateJSON> EX 30    # state expires by itself

# to list who's here:
ZRANGEBYSCORE presence:doc42 (now - 30000) +inf

# cleanup:
ZREMRANGEBYSCORE presence:doc42 -inf (now - 30000)

# changes are also published on a channel so other servers can forward them:
PUBLISH presence:doc42 <update>
```

### 8.3 CRDT-based presence (no central store)
**Phoenix Presence** (Elixir/Phoenix framework) replicates presence between cluster nodes using a CRDT, with heartbeats to detect nodes that disappeared. No Redis needed, and it heals after network splits.

### 8.4 Dedicated presence services
At very large scale, presence becomes its own service. Slack, for example, has described separate presence servers in its real-time architecture.

---

## 9. Typing indicators

```text
on keypress (debounced):
    if not currently "typing": send typing=true (with a TTL of ~5 s)
    reset a local 3-second timer

when the timer fires (no keypress for 3 s) or on send:
    send typing=false

receiver:
    show "Bob is typing…" while typing=true and the TTL hasn't expired
```
The TTL protects against the "stuck typing" bug when the stop message is lost.

---

## 10. Privacy

- Don't show presence to people without access to the document.
- Some apps let users hide their cursor or appear anonymous ("Anonymous Panda").
- Presence can reveal activity patterns; don't log it unless you need to.

---

## 11. Interview angle

- **"Design 'who is viewing this page' for a wiki."** Heartbeat every 15–30 s, Redis sorted set by last-seen per page (or in-memory on a page-session owner), TTL cleanup, pub/sub for live updates, show a count above a threshold.
- **"Why not store cursors in the document?"** Too frequent, worthless after a few seconds, would bloat history and undo.
- **"How do text cursors survive others' edits?"** Relative positions anchored to character IDs (CRDT) or transforming indexes through ops (OT).

---

## 12. Common mistakes

- No timeout, so crashed clients stay "online" forever.
- Sending diffs of presence over a lossy channel (one lost diff breaks the state).
- Absolute indexes for text cursors.
- Persisting presence or including it in undo.
- Sending every mouse event to every user.

---

## Check yourself

<details>
<summary>1. Why is it fine to drop presence messages but not document ops?</summary>

Presence messages carry the full latest state, so the next message replaces any lost one, and old presence is worthless anyway. A lost document op is lost work.

</details>

<details>
<summary>2. Presence updates from Bob arrive with clocks 7, 9, 8. What do you keep?</summary>

The state with clock 9. When 8 arrives after 9, it's ignored as stale.

</details>

<details>
<summary>3. A user's laptop lid closes without a "leave" message. When do others see them leave?</summary>

When their last update is older than the timeout (for example 30 s). That's why clients heartbeat every ~15 s.

</details>

<details>
<summary>4. Why anchor a text cursor to a character ID?</summary>

Because indexes shift when others insert or delete before the cursor. An ID always refers to the same character, so the cursor stays in the right place.

</details>

---

## Practice

- **Task 18 — Presence:** extend your cursors app (Chapter 3):
  - Name and color per client; avatar list of who's online.
  - Per-client clock; ignore stale updates.
  - Heartbeat every 15 s; remove after 30 s; immediate removal on clean leave.
  - Server batches presence per tick and only sends to clients in the same room.
  - Optional: in your text editor, show other users' cursors anchored to character IDs (RGA) or transformed indexes (OT).

---

## Further reading
- y-protocols *awareness* specification (Yjs).
- Phoenix Presence and Phoenix.Tracker documentation.
- Liveblocks documentation on presence.

---

[← 8. Sync, Storage & Offline](08-sync-storage-offline.md) · [Index](README.md) · [10. Server-Authoritative Sync & Real Systems →](10-server-authoritative-real-systems.md)
