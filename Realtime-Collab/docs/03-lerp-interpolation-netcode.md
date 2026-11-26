# 3. Lerp, Interpolation & Netcode

> **In one line:** The network gives you a few snapshots per second; the screen needs a smooth picture 60+ times per second. Interpolation fills the gaps.
>
> **You'll learn:** lerp and its relatives, easing, frame-rate-independent smoothing, angles, snapshot interpolation, extrapolation, client-side prediction and server reconciliation, lag compensation, game network models, bandwidth tricks, and how to build smooth live cursors.
>
> **Before this:** [0. The Big Picture](00-big-picture.md). This chapter is mostly independent of the CRDT/OT chapters.

**Summary:**
- `lerp(a, b, t) = a + (b − a) · t`. `t = 0` gives a, `t = 1` gives b.
- `x = lerp(x, target, 0.1)` every frame is **frame-rate dependent**. Use `factor = 1 − exp(−speed · dt)`.
- For **other people's** objects: buffer snapshots and render ~100 ms **in the past**, interpolating between the two snapshots around that time.
- For **your own** object: apply input immediately (**prediction**), then fix up when the server answers (**reconciliation**).
- Send **world coordinates**, throttle to ~20–30 updates/s, batch per server tick.

---

## 1. The problem

```
Network updates (10 per second):
time ms:  0         100        200        300
          ●──────────●──────────●──────────●

Screen frames (60 per second, one every 16.7 ms):
          |||||| |||||| |||||| |||||| |||||| ||||||

If you draw only the latest update:
position  ▁▁▁▁▁▁▃▃▃▃▃▃▅▅▅▅▅▅▇▇▇▇▇▇      ← moves in steps: "teleporting" cursor
```

Six frames in a row show the same position, then it jumps. Packets also arrive unevenly (**jitter**), so the steps are irregular. Interpolation turns the steps into a smooth line.

---

## 2. Lerp and its relatives

### 2.1 Lerp (linear interpolation)
```text
lerp(a, b, t) = a + (b - a) * t
```
| t | Result for a = 100, b = 200 |
|---|---|
| 0 | 100 |
| 0.25 | 125 |
| 0.5 | 150 |
| 1 | 200 |
| 1.5 | 250 (past b: this is **extrapolation**) |

Works per component for 2D/3D points, and per channel for colors:
```text
lerpPoint(p, q, t) = ( lerp(p.x, q.x, t), lerp(p.y, q.y, t) )
lerpColor(c1, c2, t) = ( lerp(c1.r, c2.r, t), lerp(c1.g, c2.g, t), lerp(c1.b, c2.b, t) )
```

### 2.2 Inverse lerp: "how far along is v?"
```text
inverseLerp(a, b, v) = (v - a) / (b - a)
inverseLerp(100, 200, 150) = 0.5
```
You'll use this for snapshot interpolation: "render time 1150 is 50% of the way from snapshot 1100 to snapshot 1200".

### 2.3 Remap and clamp
```text
clamp(v, lo, hi) = min(max(v, lo), hi)
remap(v, inLo, inHi, outLo, outHi) = lerp(outLo, outHi, inverseLerp(inLo, inHi, v))

remap(75, 0, 100, 0, 360) = 270     # 75% → 270 degrees
```

---

## 3. Easing: changing the feel of t

Lerp moves at constant speed. Easing functions bend `t` before you lerp: `lerp(a, b, ease(t))`.

| Name | ease(t) | Feels like |
|---|---|---|
| Linear | `t` | Robotic, constant speed |
| Ease-in (quad) | `t * t` | Starts slow, ends fast |
| Ease-out (quad) | `1 - (1 - t)^2` | Starts fast, settles gently (good for UI) |
| Ease-in-out | `t < 0.5 ? 2t² : 1 - (-2t + 2)² / 2` | Slow, fast, slow |
| Smoothstep | `t * t * (3 - 2t)` | Soft start and end, cheap to compute |

Use easing for **known start and end** animations (a panel sliding open, a camera moving to a selected object). For tracking a **moving target** (a remote cursor), use smoothing (section 4) or snapshot interpolation (section 6).

---

## 4. Smoothing toward a moving target

### 4.1 The common (broken) version
```text
every frame:
    x = lerp(x, target, 0.1)     # move 10% of the remaining distance
```
It looks fine on your machine, but the speed depends on the frame rate. After one second, the fraction of distance still remaining is `0.9^(frames per second)`:

| Frame rate | Remaining after 1 s |
|---|---|
| 30 fps | 0.9³⁰ ≈ **4.2%** |
| 60 fps | 0.9⁶⁰ ≈ **0.18%** |
| 144 fps | 0.9¹⁴⁴ ≈ **0.00002%** |

The same code feels sluggish on a 30 fps laptop and snappy on a 144 Hz monitor.

### 4.2 The fix: exponential decay with dt
```text
every frame (dt = seconds since last frame):
    factor = 1 - exp(-speed * dt)
    x = lerp(x, target, factor)
```
Splitting one second into 30 or 144 steps now gives the **same** result, because `exp(-speed·a)·exp(-speed·b) = exp(-speed·(a+b))`.

A friendlier knob is **half-life**: "close half the distance every `h` seconds".
```text
factor = 1 - 2^(-dt / halfLife)        # halfLife = 0.1 → half the gap closes every 100 ms
```

### 4.3 Limits of smoothing
- It **never exactly arrives**. Snap to the target when the distance is tiny.
- It **always lags** a moving target, and it can't know the path between updates.
- Springs (for example a "critically damped spring", which is what Unity's `SmoothDamp` implements) feel more natural for cameras because they also track velocity.

For remote objects whose real path matters, use **snapshot interpolation** instead.

---

## 5. Angles and rotations

### 5.1 Angle wrap-around
Lerping from 350° to 10° naively goes the long way: 350 → 180 → 10. The short way passes through 0°.
```text
function lerpAngle(a, b, t):              # degrees
    delta = ((b - a) mod 360 + 540) mod 360 - 180    # shortest signed difference, -180..180
    return a + delta * t

lerpAngle(350, 10, 0.5) = 350 + 20 * 0.5 = 360 → 0°   ✓
```
(Use a modulo that always returns a non-negative result.)

### 5.2 3D rotations
3D rotations are usually stored as **quaternions**. Interpolate with **slerp** (spherical lerp, constant angular speed) or **nlerp** (lerp the four numbers, then normalize; cheaper and fine for small steps). Always take the short path: if the dot product of the two quaternions is negative, negate one first.

---

## 6. Snapshot interpolation (the core technique)

Used for **other people's** objects: remote cursors, other players, shared objects being dragged.

### 6.1 The idea
Keep a small buffer of timestamped snapshots. Render the world a little **in the past**, so you almost always have a snapshot **before** and **after** the moment you're drawing.

```
snapshots:   s(1000)   s(1100)   s(1200)   s(1300)      ← latest received
                                    ▲
                     render time = now − 100 ms = 1150
                     → between s(1100) and s(1200), t = 0.5
```

### 6.2 Pseudocode
```text
buffer = list of (time, position), ordered by time
DELAY  = 100 ms

on snapshot received (time, position):
    insert into buffer in time order
    drop snapshots older than (now - 1 second)

every frame:
    renderTime = estimatedServerNow() - DELAY
    find s0, s1 in buffer with s0.time <= renderTime < s1.time
    if found:
        t = inverseLerp(s0.time, s1.time, renderTime)
        draw at lerpPoint(s0.position, s1.position, t)
    else if renderTime is newer than the latest snapshot:
        extrapolate briefly (section 7) or hold the last position
    else:
        draw at the oldest snapshot
```

### 6.3 Walkthrough with numbers
```
Bob's cursor snapshots (server time → x):
  1000 → 100
  1100 → 150
  1200 → 250
  1300 → 260

Server time now ≈ 1250. renderTime = 1250 − 100 = 1150.
s0 = (1100, 150), s1 = (1200, 250)
t  = (1150 − 1100) / (1200 − 1100) = 0.5
x  = 150 + (250 − 150) × 0.5 = 200
```

### 6.4 Choosing the delay
- At least **one snapshot interval**, usually **two or more** so one late or lost packet doesn't starve the buffer. At 10 updates/s, 100–200 ms. At 20 updates/s, ~100 ms. Valve's Source engine used a 100 ms default for exactly this reason.
- Bigger delay = smoother but more behind real time. For cursors, 50–150 ms is unnoticeable to humans.
- This buffer is a **jitter buffer**, the same idea video calls use to smooth audio packets.

### 6.5 Which clock are snapshot times in?
Two options:
1. **Arrival time on the receiver.** Simplest. Jitter in the network shows up as uneven spacing.
2. **Sender/server timestamps**, plus an estimate of the offset between the server clock and yours. Smoother. Estimate the offset NTP-style:

```text
client sends ping at t0          (client clock)
server receives at t1, replies at t2   (server clock, sent back in the reply)
client receives at t3            (client clock)

roundTrip = (t3 - t0) - (t2 - t1)
offset    = ((t1 - t0) + (t2 - t3)) / 2      # serverTime ≈ clientTime + offset

estimatedServerNow() = localNow() + offset   # smooth the offset over several pings
```

---

## 7. Extrapolation (dead reckoning)

When the buffer runs dry (packet late), **predict** forward from the last known velocity:
```text
velocity  = (s1.position - s0.position) / (s1.time - s0.time)
predicted = s1.position + velocity * (renderTime - s1.time)
```
- Good for things that move **predictably**: cars, ships, projectiles. Classic in flight simulators and racing games.
- Bad for things that **change direction suddenly**: mouse cursors overshoot and snap back.
- Limit it: extrapolate at most ~100–250 ms, then freeze.
- When the real update arrives and disagrees, **blend** from the shown position to the correct path over a short time instead of snapping.

---

## 8. Your own object: prediction and reconciliation

Interpolation makes *other* things smooth but adds delay. For **your own** character or drag, delay feels terrible. Games solve this with two techniques (explained well in Gabriel Gambetta's articles).

### 8.1 Client-side prediction
Apply your input **immediately** on your screen, and send it to the server with a sequence number.

### 8.2 Server reconciliation
The server is the authority. It replies with "your position after processing input #N". The client:
1. Resets to the server's position.
2. **Re-applies** every input after #N that the server hasn't processed yet.

```text
pending = []                      # inputs sent but not yet confirmed

on local input i:
    i.seq = nextSeq()
    applyInput(myState, i)        # prediction: instant
    pending.append(i)
    send i to server

on server update (state, lastProcessedSeq):
    myState = state
    remove from pending every input with seq <= lastProcessedSeq
    for i in pending:
        applyInput(myState, i)    # replay what the server hasn't seen yet
```

### 8.3 Walkthrough
```
x starts at 10. Each "right" input moves +1.

Client sends #1, #2, #3 quickly → predicted x = 13 (instant on screen)
Server processed only #1        → replies "x = 11, last = #1"
Client: x = 11, replay #2, #3   → x = 13   (no visible jump ✓)

If the server says "x = 10.5, last = #1" (it hit a wall):
Client: x = 10.5, replay #2, #3 → x = 12.5 (small correction; blend it smoothly)
```

This exact pattern reappears in collaborative apps as **optimistic updates + rebase** (Replicache, Figma; Chapter 10) and in OT's client state machine (Chapter 6).

---

## 9. Lag compensation

In shooters, by the time your "fire" reaches the server, the target has moved. The server **rewinds** other players to where *you* saw them (your time minus your latency and interpolation delay), checks the hit, then continues. It favors the shooter; the target may feel shot "behind a wall". Collaborative apps rarely need this, but it's a common interview topic for game backends.

---

## 10. Game networking models

| Model | How | Good for | Example |
|---|---|---|---|
| **Deterministic lockstep** | Everyone sends only **inputs**; every machine runs the same deterministic simulation | Many units, little bandwidth (RTS) | Age of Empires ("1500 Archers on a 28.8") |
| **Snapshot / state sync** | Server simulates, sends **snapshots**; clients interpolate | Shooters, open worlds | Quake, Source engine games |
| **Rollback** | Predict the opponent's input, simulate, and **roll back and re-simulate** when the real input differs | Fighting games, 1v1 | GGPO-based netcode |
| **Authoritative server + prediction** | Server owns truth; client predicts its own entity | Most modern online games | — |

---

## 11. Saving bandwidth

| Technique | Idea |
|---|---|
| **Throttling** | Send at most N updates/s (cursors: every 33–50 ms) |
| **Send only changes** | Skip the update if nothing moved |
| **Delta compression** | Send the difference from the last acked snapshot |
| **Quantization** | Round positions to integers or 16-bit numbers |
| **Batching** | Server combines all updates from one tick into one message per client (Chapter 4) |
| **Interest management** | Only send what the receiver can see: players nearby, cursors on the same page or inside the viewport |
| **Priority** | Nearby/important objects update more often |

---

## 12. Designing smooth live cursors (Figma-style)

Putting it together for a collaborative canvas or document:

1. **Capture:** on mouse move, store the latest position, but **send** at most every ~40 ms (25/s).
2. **Coordinates:** send **world/canvas coordinates**, not screen pixels. Each viewer has a different zoom and scroll, and converts world → screen locally.
3. **Transport:** presence channel (Chapter 9), best-effort; losing one update is fine.
4. **Server:** batch updates per tick and fan out only to people viewing the same page.
5. **Receiver:** snapshot-interpolate with ~50–100 ms delay, or use a smooth curve through recent points. Steve Ruiz's *perfect-cursors* library (from the tldraw author) interpolates cursor points with a spline for this purpose.
6. **Idle:** hide a cursor after a few seconds of no movement; remove it when the user leaves.
7. **Text documents:** a text cursor isn't an (x, y). It's a **position in the text**, and it must move when others insert before it (Chapter 9, relative positions).

---

## 13. Interview angle

- **"Design live cursors for a whiteboard."** Throttled sends, world coordinates, ephemeral presence channel, server batching per tick, interest management, interpolation with a small delay, idle timeout.
- **"How do multiplayer games hide latency?"** Prediction + reconciliation for your own entity; interpolation for others; lag compensation for hit checks.
- **"Why does my animation run at different speeds on different machines?"** Frame-rate-dependent lerp; use `dt` and exponential decay.

---

## 14. Common mistakes

- `lerp(x, target, constant)` without `dt`.
- Lerping angles without wrap-around.
- Interpolating with zero delay, so there's never an "after" snapshot.
- Extrapolating mouse cursors (overshoot).
- Sending screen coordinates.
- Sending every mouse event (hundreds per second) instead of throttling.

---

## Check yourself

<details>
<summary>1. Why render remote objects in the past?</summary>

So there is almost always a snapshot before *and* after the render time. Then you can interpolate along the real path instead of guessing. The price is a small, constant delay (~100 ms).

</details>

<details>
<summary>2. Snapshots: 2000 → x=40, 2100 → x=60. Render time 2025. Where do you draw?</summary>

t = (2025 − 2000) / (2100 − 2000) = 0.25. x = 40 + (60 − 40) × 0.25 = 45.

</details>

<details>
<summary>3. Why is <code>x = lerp(x, target, 0.1)</code> per frame a bug?</summary>

The amount of movement per second depends on how many frames run per second. Use `factor = 1 − exp(−speed · dt)` so the motion is the same at any frame rate.

</details>

<details>
<summary>4. What does the client do when the server confirms input #5 but the client has already sent #6 and #7?</summary>

Set its state to the server's state (after #5), drop #1–#5 from the pending list, and re-apply #6 and #7 on top. That's server reconciliation.

</details>

<details>
<summary>5. Why send world coordinates for cursors?</summary>

Every viewer has their own zoom and scroll. A world coordinate means the same spot on the canvas for everyone; each client converts it to its own screen position.

</details>

---

## Practice

- **Task 6 — Live cursors** (`cmd/cursors/` + `web/cursors.html`):
  - Go WebSocket server (`github.com/coder/websocket`) **listening on `127.0.0.1:8080`**. Each client sends `{x, y}` in world coordinates, throttled to ~25/s. The server keeps the latest position per client and broadcasts a **batched** message of all positions 10 times per second.
  - Browser client with a mode switch: **raw** (draw latest), **smoothing** (exp decay with dt), **snapshot interpolation** (100 ms delay buffer).
  - Server-side "bad network" knobs: extra delay, random jitter, and drop percentage. Watch how each mode copes at 0%, 10%, 30% drops.
- **Optional:** add a predicted local "drag" of a shared box with reconciliation (section 8), and see why your own box must not be interpolated.

---

## Further reading
- Gabriel Gambetta, *Fast-Paced Multiplayer* (4 short articles + live demo). Start here.
- Glenn Fiedler (Gaffer On Games), *Snapshot Interpolation*, *Fix Your Timestep!*, *Networked Physics*.
- Valve Developer Wiki, *Source Multiplayer Networking*.
- Freya Holmér, *Lerp smoothing is broken* (talk).
- Mark Terrano & Paul Bettner, *1500 Archers on a 28.8: Network Programming in Age of Empires and Beyond*.

---

[← 2. Replication, Consistency & Merging](02-replication-consistency-merging.md) · [Index](README.md) · [4. Real-Time Transport & Scaling →](04-realtime-transport-scaling.md)
