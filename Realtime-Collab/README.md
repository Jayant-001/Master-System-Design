Learn these in this order. Each step builds on the one before, and each ends with something you build in Go. Use Go for the algorithms and the servers. Use plain browser JavaScript only for drawing, because lerping happens in the browser. You need Node.js only to try the production libraries (Yjs, ShareDB) at the end. Node 24 is already installed on your Mac.

## What each topic is

| Topic | Problem it solves | Where you see it |
|---|---|---|
| **Lerp / interpolation** | Network updates arrive about 10–20 times a second but the screen draws 60+ times a second, so other people's cursors jump around. Lerp makes them move smoothly. | Figma cursors, multiplayer games |
| **OT** (Operational Transformation) | Two people edit the same text at once. Each edit is adjusted ("transformed") against the other so everyone ends up with the same text. In practice it needs a central server. | Google Docs, ShareDB |
| **CRDT** (Conflict-free Replicated Data Type) | Data structures that merge automatically, in any order, with no central server. Works offline and peer-to-peer. | Yjs, Automerge, Redis Active-Active |

Lerp is a rendering problem. OT and CRDTs are consistency problems. A real-time collaborative app needs both.

## Suggested project layout
Put it next to your `Kafka/` folder as one Go module:
```
Master-System-Design/Realtime-Collab/
├── go.mod          module github.com/Jayant-001/realtime-collab
├── clock/          Lamport + vector clocks
├── crdt/           gcounter.go, lwwregister.go, orset.go, rga.go ...
├── ot/             text operations + transform
├── cmd/cursors/    WebSocket server for live cursors
├── cmd/editor/     collaborative editor server
└── web/            index.html + small JS clients
```

---

## Step 1: Foundations (time and causality)
**Learn:** why "last write wins by wall-clock time" breaks, what *happens-before* means, Lamport clocks, vector clocks, and how to tell "concurrent" from "before/after". Also learn what makes a merge safe: it must be **commutative, associative, and idempotent**.

**Resources:**
- Martin Kleppmann's *Distributed Systems* lecture course (Cambridge, free on YouTube, with lecture notes). Watch the lectures on time, broadcast, and the final one on collaboration software.
- The Replication chapter of *Designing Data-Intensive Applications*, especially the part on detecting concurrent writes.

**Build:** `clock/` with a Lamport clock, and a vector clock with `Compare(a, b)` that returns `Before`, `After`, `Equal`, or `Concurrent`. Write table tests.

## Step 2: Lerp and live cursors
**Learn:**
- `lerp(a, b, t) = a + (b - a) * t`, plus inverse lerp, clamp, and easing.
- **Frame-rate-independent smoothing.** The common `x = lerp(x, target, 0.1)` moves faster on faster screens. Use this instead:
  ```js
  x += (target - x) * (1 - Math.exp(-speed * dt));   // dt = seconds since last frame
  ```
- **Snapshot interpolation.** Draw other players about 100ms *in the past*, and blend between the two snapshots around that moment:
  ```js
  const t = (renderTime - s0.time) / (s1.time - s0.time);
  pos = lerp(s0.pos, s1.pos, t);
  ```
- Extrapolation (dead reckoning) for when packets arrive late. Client-side prediction and server reconciliation for your *own* avatar.
- Angle wrap-around: lerping 350° → 10° should pass through 0°, not 180°.

**Resources:**
- Gabriel Gambetta, *Fast-Paced Multiplayer*: 4 short articles with a live demo. Start here.
- Glenn Fiedler (Gaffer On Games), *Snapshot Interpolation*.
- Valve Developer Wiki, *Source Multiplayer Networking*.
- Freya Holmér's talk *Lerp smoothing is broken*.

**Build:** `cmd/cursors`, a Go WebSocket server (`github.com/coder/websocket`) that broadcasts every cursor position 10 times a second. **Listen on `127.0.0.1:8080`**, not `:8080`, so only your laptop can reach it. Give the browser client three modes you can switch between: raw (jumpy), simple lerp, and snapshot interpolation. Add a slider that adds fake delay, jitter, and packet loss, and watch how each mode copes. You'll reuse this WebSocket setup in every later step.

## Step 3: CRDT basics
**Learn:** state-based vs operation-based CRDTs, why merge must be commutative, associative, and idempotent, and why tombstones (markers for deleted items) exist.

**Resources:**
- Jake Lazaroff, *An Interactive Intro to CRDTs*. It's written in TypeScript, so read it and then rewrite it in Go.
- Matthew Weidner's *CRDT Survey* blog series.
- Shapiro et al., *A comprehensive study of Convergent and Commutative Replicated Data Types* (2011). The original catalog of CRDTs; use it as a reference.
- crdt.tech, a collection of papers and libraries.

**Build, in this order:** G-Counter → PN-Counter → LWW-Register (Lamport timestamp, with the replica ID breaking ties) → G-Set → 2P-Set → OR-Set → LWW-Map.

You've already seen a CRDT: every element in your `design.excalidraw` file has `version` and `versionNonce` fields. When two people edit the same drawing, Excalidraw keeps the element with the higher `version` and breaks ties by `versionNonce`. That's an LWW-Register for each element.

**The most important testing habit:** write a **random convergence test**. Create 3–5 replicas, apply random operations, deliver them in random order with duplicates, and assert that all replicas end up equal. Use `math/rand/v2`, and later `go test -fuzz`. These tests are how you find the real bugs in CRDT and OT code.

## Step 4: OT on plain text
**Learn:**
- Operations are index-based, like `Insert(pos, "x")` or `Delete(pos, n)`.
- `transform(a, b)` shifts the indices of one operation so it still makes sense after the other.
- In the central-server model (Jupiter / Google Docs), the server decides the order. Each client is in one of three states: synced, waiting for an ack, or waiting with more edits buffered.
- Why OT without a server is so hard: it needs a property called TP2, and several published algorithms turned out to be wrong.

**Resources:**
- Daniel Spiewak, *Understanding and Applying Operational Transformation*.
- Tim Baumann's **ot.js**: small, readable source code, plus an interactive visualization of client and server states.

**Build:** `ot/` with Insert/Delete and `Transform`, tested with a random test. For any document and two concurrent operations `a` and `b`, check that `apply(apply(doc, a), b')` equals `apply(apply(doc, b), a')`, where `b'` and `a'` are the transformed versions. Then build `cmd/editor`: a server with revision numbers, and a `<textarea>` client that two browser tabs share.

## Step 5: CRDTs for text (the hard, interesting part)
**Learn:**
- Give each character a unique ID `(replicaID, counter)` and position it relative to its neighbors (RGA), or with fractional positions (Logoot/LSEQ).
- Deletions leave tombstones.
- The **interleaving problem**: two people typing at the same spot can get their words mixed together. Newer algorithms (YATA, used by Yjs, and Fugue) fix this.

**Resources:**
- Martin Kleppmann's talk *CRDTs: The Hard Parts*.
- Seph Gentle, *5000x faster CRDTs: An Adventure in Optimization*. The best explanation of how real text CRDTs work and why they're fast.
- Weidner & Kleppmann, *The Art of the Fugue* (the interleaving paper).
- Figma, *Realtime editing of ordered sequences* (fractional indexing for ordered lists).

**Build:** an RGA in `crdt/rga.go`, then a second version of your editor that uses it. The CRDT has to run in the browser as well. Either compile the Go code to WebAssembly (`GOOS=js GOARCH=wasm`), which keeps you in Go, or write the client in JS.

**Capstone:** run your OT editor and your CRDT editor side by side, and compare offline editing, metadata size, and how much the server has to do.

## Step 6: Production concerns and system design
- **Presence and awareness** (cursors, selections) are temporary. Send them over a separate channel and keep them out of the CRDT. This is what Yjs's "awareness" does.
- **Sync protocols:** state vectors (send only the updates the other side is missing), snapshots plus compacting the operation log, cleaning up tombstones, and undo/redo when several people are editing.
- **Rich text and JSON:** Ink & Switch's *Peritext* paper and Automerge. Also read their essay *Local-first software*.
- **Case studies:** Figma, *How Figma's multiplayer technology works* (server-authoritative, last-writer-wins on each property). Google Docs (OT).
- **Production libraries to compare with yours:** Yjs + y-websocket and ShareDB on Node, Automerge, and Loro.

## OT vs CRDT (for the "Design Google Docs" interview question)
| | OT | CRDT |
|---|---|---|
| How it resolves conflicts | Transform the indices of operations | Unique IDs + merge rules |
| Central server | Needed in practice | Not needed (works P2P and offline) |
| Where the complexity lives | Transform functions + server ordering | The data structure and its metadata |
| Metadata overhead | Low | Higher (IDs, tombstones) |
| Offline editing | Hard | Natural |

**Start with** Step 1's clocks and then Gambetta's articles. Live cursors (Step 2) is the quickest visible result, and it gives you the WebSocket server you'll reuse for the editors.