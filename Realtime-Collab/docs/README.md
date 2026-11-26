# Real-Time Collaboration: Study Guide

How apps like Google Docs, Figma, Excalidraw, and multiplayer games let many people change the same thing at once: clocks and causality, CRDTs, Operational Transformation, interpolation (lerp), sync, presence, and how production systems combine them.

## How to use this guide

- **First time:** read the chapters in order. Each one builds on the earlier ones.
- **Coming back after a break:** read the **Summary** box at the top of the chapter, then try its **Check yourself** questions (answers are hidden; click to open). If you can answer them, move on.
- **Practice:** each chapter ends with a **Practice** section. Tasks are numbered across the whole guide and map to folders in this repo (`clock/`, `sim/`, `crdt/`, `ot/`, `cmd/`, `web/`).
- **No real code here on purpose.** Pseudocode is language-neutral; your Go code is the implementation.
- **Diagrams** are plain text, so they render everywhere (GitHub, VS Code, terminal).

## Chapters

| # | Chapter | What it covers | Practice tasks |
|---|---|---|---|
| 0 | [The Big Picture](00-big-picture.md) | The core problems, solution families, layers of a collaborative app | — |
| 1 | [Time, Clocks & Causality](01-time-clocks-causality.md) | Physical clocks, happens-before, Lamport, vector clocks, version vectors, HLC, TrueTime | 1–3 |
| 2 | [Replication, Consistency & Merging](02-replication-consistency-merging.md) | Replication styles, consistency models, conflict strategies, merge laws, idempotency, causal broadcast | 4–5 |
| 3 | [Lerp, Interpolation & Netcode](03-lerp-interpolation-netcode.md) | Lerp, easing, frame-rate-independent smoothing, snapshot interpolation, prediction, reconciliation, live cursors | 6 |
| 4 | [Real-Time Transport & Scaling](04-realtime-transport-scaling.md) | WebSocket/SSE/WebRTC, reliability, heartbeats, reconnects, one owner per doc, fan-out, security | 7 |
| 5 | [CRDT Fundamentals](05-crdt-fundamentals.md) | State/op/delta CRDTs, counters, registers, sets, maps, tombstones, limits | 8–11 |
| 6 | [Operational Transformation](06-operational-transformation.md) | Transform cases, TP1/TP2, central-server OT, client state machine, undo, cursors | 12–13 |
| 7 | [Sequence CRDTs: Text & Lists](07-sequence-crdts-text-lists.md) | RGA, interleaving, YATA, Fugue, Logoot, fractional indexing, moves, rich text, performance | 14–15 |
| 8 | [Sync, Storage & Offline](08-sync-storage-offline.md) | State vectors, Yjs sync, hash graphs, Merkle trees, gossip, op log + snapshots, local-first, permissions | 16–17 |
| 9 | [Presence & Awareness](09-presence-awareness.md) | Cursors, who's online, heartbeats, relative positions, scaling presence | 18 |
| 10 | [Server-Authoritative Sync & Real Systems](10-server-authoritative-real-systems.md) | Optimistic + rebase, Figma, Google Docs, Excalidraw, Yjs apps, Linear-style sync, multi-region DBs, decision guide | 19 |
| 11 | [Testing Distributed Algorithms](11-testing-distributed-algorithms.md) | Property tests, fuzzing, seeded simulation, deterministic simulation, TLA+/P, Jepsen | — |
| 12 | [System Design Interviews](12-system-design-interviews.md) | Google Docs, Figma, presence, game backend, block editor, counters, follow-ups, numbers | — |
| 13 | [Glossary & Resources](13-glossary-and-resources.md) | Every term in one table; reading list; libraries | — |

## Practice tracker

Tick these off as you go (edit `[ ]` to `[x]`).

- [ ] **1** Lamport clock — `clock/lamport.go` ([Ch 1](01-time-clocks-causality.md#practice))
- [ ] **2** Vector clock — `clock/vector.go`
- [ ] **3** (optional) Hybrid logical clock — `clock/hlc.go`
- [ ] **4** Network simulator — `sim/` ([Ch 2](02-replication-consistency-merging.md#practice))
- [ ] **5** Merge-law checker (test helper)
- [ ] **6** Live cursors with raw / smoothing / interpolation modes — `cmd/cursors`, `web/` ([Ch 3](03-lerp-interpolation-netcode.md#practice))
- [ ] **7** WebSocket hub: rooms, heartbeats, reconnect, dedup ([Ch 4](04-realtime-transport-scaling.md#practice))
- [ ] **8** G-Counter, PN-Counter — `crdt/` ([Ch 5](05-crdt-fundamentals.md#practice))
- [ ] **9** LWW-Register, MV-Register
- [ ] **10** G-Set, 2P-Set, OR-Set
- [ ] **11** LWW-Map
- [ ] **12** OT core + random TP1 test — `ot/` ([Ch 6](06-operational-transformation.md#practice))
- [ ] **13** OT collaborative editor — `cmd/editor`, `web/`
- [ ] **14** RGA — `crdt/rga.go` ([Ch 7](07-sequence-crdts-text-lists.md#practice))
- [ ] **15** CRDT collaborative editor
- [ ] **16** Delta sync with state vectors ([Ch 8](08-sync-storage-offline.md#practice))
- [ ] **17** Persistence: op log + snapshots
- [ ] **18** Presence: names, colors, timeouts ([Ch 9](09-presence-awareness.md#practice))
- [ ] **19** (optional) Mini Figma board ([Ch 10](10-server-authoritative-real-systems.md#practice-optional-a-fun-capstone))

## Quick reference: which technique for which problem?

| Problem | Technique | Chapter |
|---|---|---|
| Ordering events without trusting clocks | Lamport timestamps `(time, node)` | 1 |
| Detecting concurrent edits | Vector clocks / version vectors | 1 |
| Timestamps close to real time but causal | Hybrid logical clocks | 1 |
| Retries create duplicates | Idempotent merges, or dedup by `(clientId, seq)` | 2, 4 |
| Replies shown before questions | Causal delivery | 2 |
| Jumpy remote cursors/players | Snapshot interpolation (~100 ms delay) | 3 |
| Your own movement feels laggy | Client-side prediction + server reconciliation | 3 |
| Animation speed differs per machine | `1 − exp(−speed·dt)` smoothing | 3 |
| Dead connections, ghost users | Heartbeats + timeouts | 4, 9 |
| Reconnect storms | Exponential backoff with full jitter | 4 |
| Agreeing on edit order | One owner per document | 4 |
| Too many messages in busy rooms | Batch per tick, interest management | 4, 9 |
| Counters across regions | G-Counter / PN-Counter | 5 |
| Single field edited concurrently | LWW-Register (or MV-Register to keep both) | 5 |
| Shopping cart / tags | OR-Set (add-wins) | 5 |
| Permissions / blocklists | Remove-wins set or server check | 5, 10 |
| Text editing with a central server | OT (or a sequence CRDT) | 6, 7 |
| Text editing offline / peer-to-peer | Sequence CRDT (RGA, YATA, Fugue) | 7 |
| Reordering cards, layers, z-order | Fractional indexing + LWW | 7, 10 |
| Moving folders/blocks concurrently | Tree move with cycle checks | 7 |
| Catching up after offline | State vectors / revision numbers / snapshots | 8 |
| Finding differences between big datasets | Merkle trees | 8 |
| Slow document loads | Snapshots + op log, compaction | 8 |
| Cursors, who's online, typing | Presence channel, TTL, relative positions | 9 |
| Business rules (no overselling) | Server-authoritative mutations / coordination | 5, 10 |
| Design tool objects | LWW per property, server-ordered (Figma) | 10 |
| Hard-to-find concurrency bugs | Seeded random simulation, property tests | 11 |

---

## Full table of contents

### [0. The Big Picture](00-big-picture.md)

- [1. What "real-time collaboration" means](00-big-picture.md#1-what-real-time-collaboration-means)
- [2. The six core problems](00-big-picture.md#2-the-six-core-problems)
- [3. A conflict you can see with your own eyes](00-big-picture.md#3-a-conflict-you-can-see-with-your-own-eyes)
- [4. The families of solutions](00-big-picture.md#4-the-families-of-solutions)
- [5. The layers of a collaborative app](00-big-picture.md#5-the-layers-of-a-collaborative-app)
- [6. The mental model: replicas](00-big-picture.md#6-the-mental-model-replicas)
- [7. Words you'll see everywhere](00-big-picture.md#7-words-youll-see-everywhere)
- [Check yourself](00-big-picture.md#check-yourself)

### [1. Time, Clocks & Causality](01-time-clocks-causality.md)

- [1. Why physical time is a problem](01-time-clocks-causality.md#1-why-physical-time-is-a-problem)
- [2. Happens-before (→)](01-time-clocks-causality.md#2-happens-before-)
- [3. Lamport clocks: counting instead of timing](01-time-clocks-causality.md#3-lamport-clocks-counting-instead-of-timing)
- [4. Vector clocks: detecting concurrency](01-time-clocks-causality.md#4-vector-clocks-detecting-concurrency)
- [5. Version vectors (a close cousin)](01-time-clocks-causality.md#5-version-vectors-a-close-cousin)
- [6. Hybrid Logical Clocks (HLC)](01-time-clocks-causality.md#6-hybrid-logical-clocks-hlc)
- [7. TrueTime and precise clocks (the other approach)](01-time-clocks-causality.md#7-truetime-and-precise-clocks-the-other-approach)
- [8. Which clock should I use?](01-time-clocks-causality.md#8-which-clock-should-i-use)
- [9. Interview angle](01-time-clocks-causality.md#9-interview-angle)
- [10. Common mistakes](01-time-clocks-causality.md#10-common-mistakes)
- [Check yourself](01-time-clocks-causality.md#check-yourself)
- [Practice](01-time-clocks-causality.md#practice)

### [2. Replication, Consistency & Merging](02-replication-consistency-merging.md)

- [1. Why replicate?](02-replication-consistency-merging.md#1-why-replicate)
- [2. Three replication styles](02-replication-consistency-merging.md#2-three-replication-styles)
- [3. Consistency models in plain words](02-replication-consistency-merging.md#3-consistency-models-in-plain-words)
- [4. What is a conflict?](02-replication-consistency-merging.md#4-what-is-a-conflict)
- [5. Strategies for resolving conflicts](02-replication-consistency-merging.md#5-strategies-for-resolving-conflicts)
- [6. What makes a merge safe](02-replication-consistency-merging.md#6-what-makes-a-merge-safe)
- [7. Delivery guarantees and idempotency](02-replication-consistency-merging.md#7-delivery-guarantees-and-idempotency)
- [8. Broadcast orderings](02-replication-consistency-merging.md#8-broadcast-orderings)
- [9. How replicas catch up (preview)](02-replication-consistency-merging.md#9-how-replicas-catch-up-preview)
- [10. Interview angle](02-replication-consistency-merging.md#10-interview-angle)
- [11. Common mistakes](02-replication-consistency-merging.md#11-common-mistakes)
- [Check yourself](02-replication-consistency-merging.md#check-yourself)
- [Practice](02-replication-consistency-merging.md#practice)

### [3. Lerp, Interpolation & Netcode](03-lerp-interpolation-netcode.md)

- [1. The problem](03-lerp-interpolation-netcode.md#1-the-problem)
- [2. Lerp and its relatives](03-lerp-interpolation-netcode.md#2-lerp-and-its-relatives)
- [3. Easing: changing the feel of t](03-lerp-interpolation-netcode.md#3-easing-changing-the-feel-of-t)
- [4. Smoothing toward a moving target](03-lerp-interpolation-netcode.md#4-smoothing-toward-a-moving-target)
- [5. Angles and rotations](03-lerp-interpolation-netcode.md#5-angles-and-rotations)
- [6. Snapshot interpolation (the core technique)](03-lerp-interpolation-netcode.md#6-snapshot-interpolation-the-core-technique)
- [7. Extrapolation (dead reckoning)](03-lerp-interpolation-netcode.md#7-extrapolation-dead-reckoning)
- [8. Your own object: prediction and reconciliation](03-lerp-interpolation-netcode.md#8-your-own-object-prediction-and-reconciliation)
- [9. Lag compensation](03-lerp-interpolation-netcode.md#9-lag-compensation)
- [10. Game networking models](03-lerp-interpolation-netcode.md#10-game-networking-models)
- [11. Saving bandwidth](03-lerp-interpolation-netcode.md#11-saving-bandwidth)
- [12. Designing smooth live cursors (Figma-style)](03-lerp-interpolation-netcode.md#12-designing-smooth-live-cursors-figma-style)
- [13. Interview angle](03-lerp-interpolation-netcode.md#13-interview-angle)
- [14. Common mistakes](03-lerp-interpolation-netcode.md#14-common-mistakes)
- [Check yourself](03-lerp-interpolation-netcode.md#check-yourself)
- [Practice](03-lerp-interpolation-netcode.md#practice)

### [4. Real-Time Transport & Scaling](04-realtime-transport-scaling.md)

- [1. Ways to push data to a browser](04-realtime-transport-scaling.md#1-ways-to-push-data-to-a-browser)
- [2. Designing messages](04-realtime-transport-scaling.md#2-designing-messages)
- [3. Reliability on top of WebSocket](04-realtime-transport-scaling.md#3-reliability-on-top-of-websocket)
- [4. Heartbeats: detecting dead connections](04-realtime-transport-scaling.md#4-heartbeats-detecting-dead-connections)
- [5. Reconnecting without a stampede](04-realtime-transport-scaling.md#5-reconnecting-without-a-stampede)
- [6. One owner per document](04-realtime-transport-scaling.md#6-one-owner-per-document)
- [7. Scaling out](04-realtime-transport-scaling.md#7-scaling-out)
- [8. Capacity: the numbers that matter](04-realtime-transport-scaling.md#8-capacity-the-numbers-that-matter)
- [9. Backpressure: slow clients](04-realtime-transport-scaling.md#9-backpressure-slow-clients)
- [10. Security checklist](04-realtime-transport-scaling.md#10-security-checklist)
- [11. Interview angle](04-realtime-transport-scaling.md#11-interview-angle)
- [12. Common mistakes](04-realtime-transport-scaling.md#12-common-mistakes)
- [Check yourself](04-realtime-transport-scaling.md#check-yourself)
- [Practice](04-realtime-transport-scaling.md#practice)

### [5. CRDT Fundamentals](05-crdt-fundamentals.md)

- [1. What a CRDT is](05-crdt-fundamentals.md#1-what-a-crdt-is)
- [2. Two (and a half) flavors](05-crdt-fundamentals.md#2-two-and-a-half-flavors)
- [3. Counters](05-crdt-fundamentals.md#3-counters)
- [4. Registers (a single value)](05-crdt-fundamentals.md#4-registers-a-single-value)
- [5. Sets](05-crdt-fundamentals.md#5-sets)
- [6. Maps and documents](05-crdt-fundamentals.md#6-maps-and-documents)
- [7. Choosing semantics is a product decision](05-crdt-fundamentals.md#7-choosing-semantics-is-a-product-decision)
- [8. Tombstones and garbage collection](05-crdt-fundamentals.md#8-tombstones-and-garbage-collection)
- [9. Designing your own CRDT](05-crdt-fundamentals.md#9-designing-your-own-crdt)
- [10. Testing CRDTs](05-crdt-fundamentals.md#10-testing-crdts)
- [11. What CRDTs can't do](05-crdt-fundamentals.md#11-what-crdts-cant-do)
- [12. Real-world uses](05-crdt-fundamentals.md#12-real-world-uses)
- [13. Interview angle](05-crdt-fundamentals.md#13-interview-angle)
- [14. Common mistakes](05-crdt-fundamentals.md#14-common-mistakes)
- [Check yourself](05-crdt-fundamentals.md#check-yourself)
- [Practice](05-crdt-fundamentals.md#practice)

### [6. Operational Transformation (OT)](06-operational-transformation.md)

- [1. The idea](06-operational-transformation.md#1-the-idea)
- [2. Operations](06-operational-transformation.md#2-operations)
- [3. apply](06-operational-transformation.md#3-apply)
- [4. transform: case by case](06-operational-transformation.md#4-transform-case-by-case)
- [5. Convergence properties](06-operational-transformation.md#5-convergence-properties)
- [6. The central-server model (Jupiter / Google Wave / ot.js)](06-operational-transformation.md#6-the-central-server-model-jupiter--google-wave--otjs)
- [7. compose](06-operational-transformation.md#7-compose)
- [8. Undo and redo](06-operational-transformation.md#8-undo-and-redo)
- [9. Cursors and selections](06-operational-transformation.md#9-cursors-and-selections)
- [10. OT beyond plain text](06-operational-transformation.md#10-ot-beyond-plain-text)
- [11. Where OT is used](06-operational-transformation.md#11-where-ot-is-used)
- [12. OT vs CRDT (first look)](06-operational-transformation.md#12-ot-vs-crdt-first-look)
- [13. Common mistakes](06-operational-transformation.md#13-common-mistakes)
- [Check yourself](06-operational-transformation.md#check-yourself)
- [Practice](06-operational-transformation.md#practice)

### [7. Sequence CRDTs: Text & Lists](07-sequence-crdts-text-lists.md)

- [1. Why lists are hard](07-sequence-crdts-text-lists.md#1-why-lists-are-hard)
- [2. The key idea: stable unique IDs](07-sequence-crdts-text-lists.md#2-the-key-idea-stable-unique-ids)
- [3. RGA (Replicated Growable Array)](07-sequence-crdts-text-lists.md#3-rga-replicated-growable-array)
- [4. The interleaving problem](07-sequence-crdts-text-lists.md#4-the-interleaving-problem)
- [5. YATA (used by Yjs)](07-sequence-crdts-text-lists.md#5-yata-used-by-yjs)
- [6. Fugue (brief)](07-sequence-crdts-text-lists.md#6-fugue-brief)
- [7. Dense identifiers: Logoot and LSEQ](07-sequence-crdts-text-lists.md#7-dense-identifiers-logoot-and-lseq)
- [8. Fractional indexing for ordered lists (not text)](07-sequence-crdts-text-lists.md#8-fractional-indexing-for-ordered-lists-not-text)
- [9. Moving items](07-sequence-crdts-text-lists.md#9-moving-items)
- [10. Rich text](07-sequence-crdts-text-lists.md#10-rich-text)
- [11. Performance at scale](07-sequence-crdts-text-lists.md#11-performance-at-scale)
- [12. Undo and redo in CRDTs](07-sequence-crdts-text-lists.md#12-undo-and-redo-in-crdts)
- [13. Libraries you'll meet](07-sequence-crdts-text-lists.md#13-libraries-youll-meet)
- [14. Interview angle](07-sequence-crdts-text-lists.md#14-interview-angle)
- [15. Common mistakes](07-sequence-crdts-text-lists.md#15-common-mistakes)
- [Check yourself](07-sequence-crdts-text-lists.md#check-yourself)
- [Practice](07-sequence-crdts-text-lists.md#practice)

### [8. Sync, Storage & Offline (Local-First)](08-sync-storage-offline.md)

- [1. The sync question](08-sync-storage-offline.md#1-the-sync-question)
- [2. Delta sync with state vectors](08-sync-storage-offline.md#2-delta-sync-with-state-vectors)
- [3. The Yjs sync protocol](08-sync-storage-offline.md#3-the-yjs-sync-protocol)
- [4. Hash-graph sync (Automerge, git)](08-sync-storage-offline.md#4-hash-graph-sync-automerge-git)
- [5. Merkle trees and anti-entropy](08-sync-storage-offline.md#5-merkle-trees-and-anti-entropy)
- [6. Gossip](08-sync-storage-offline.md#6-gossip)
- [7. Server-side storage for collaborative documents](08-sync-storage-offline.md#7-server-side-storage-for-collaborative-documents)
- [8. Version history and time travel](08-sync-storage-offline.md#8-version-history-and-time-travel)
- [9. Offline support](08-sync-storage-offline.md#9-offline-support)
- [10. Schema evolution](08-sync-storage-offline.md#10-schema-evolution)
- [11. Permissions and security](08-sync-storage-offline.md#11-permissions-and-security)
- [12. Interview angle](08-sync-storage-offline.md#12-interview-angle)
- [13. Common mistakes](08-sync-storage-offline.md#13-common-mistakes)
- [Check yourself](08-sync-storage-offline.md#check-yourself)
- [Practice](08-sync-storage-offline.md#practice)

### [9. Presence & Awareness](09-presence-awareness.md)

- [1. What presence is](09-presence-awareness.md#1-what-presence-is)
- [2. Why keep it out of the document](09-presence-awareness.md#2-why-keep-it-out-of-the-document)
- [3. Data model](09-presence-awareness.md#3-data-model)
- [4. Protocol](09-presence-awareness.md#4-protocol)
- [5. Canvas cursors](09-presence-awareness.md#5-canvas-cursors)
- [6. Text cursors must be relative](09-presence-awareness.md#6-text-cursors-must-be-relative)
- [7. Scaling presence](09-presence-awareness.md#7-scaling-presence)
- [8. Presence across many servers](09-presence-awareness.md#8-presence-across-many-servers)
- [9. Typing indicators](09-presence-awareness.md#9-typing-indicators)
- [10. Privacy](09-presence-awareness.md#10-privacy)
- [11. Interview angle](09-presence-awareness.md#11-interview-angle)
- [12. Common mistakes](09-presence-awareness.md#12-common-mistakes)
- [Check yourself](09-presence-awareness.md#check-yourself)
- [Practice](09-presence-awareness.md#practice)

### [10. Server-Authoritative Sync & Real Systems](10-server-authoritative-real-systems.md)

- [1. The third way: the server decides](10-server-authoritative-real-systems.md#1-the-third-way-the-server-decides)
- [2. The pattern: optimistic mutations + rebase](10-server-authoritative-real-systems.md#2-the-pattern-optimistic-mutations--rebase)
- [3. Case study: Figma](10-server-authoritative-real-systems.md#3-case-study-figma)
- [4. Case study: Google Docs](10-server-authoritative-real-systems.md#4-case-study-google-docs)
- [5. Case study: Excalidraw (look at your own `design.excalidraw`)](10-server-authoritative-real-systems.md#5-case-study-excalidraw-look-at-your-own-designexcalidraw)
- [6. Case study: Yjs-based apps](10-server-authoritative-real-systems.md#6-case-study-yjs-based-apps)
- [7. Case study: multiplayer games](10-server-authoritative-real-systems.md#7-case-study-multiplayer-games)
- [8. Case study: Linear-style sync engines](10-server-authoritative-real-systems.md#8-case-study-linear-style-sync-engines)
- [9. Case study: multi-region databases](10-server-authoritative-real-systems.md#9-case-study-multi-region-databases)
- [10. Case study: chat ordering](10-server-authoritative-real-systems.md#10-case-study-chat-ordering)
- [11. Comparison of approaches](10-server-authoritative-real-systems.md#11-comparison-of-approaches)
- [12. Decision guide](10-server-authoritative-real-systems.md#12-decision-guide)
- [Check yourself](10-server-authoritative-real-systems.md#check-yourself)
- [Practice (optional, a fun capstone)](10-server-authoritative-real-systems.md#practice-optional-a-fun-capstone)

### [11. Testing Distributed Algorithms](11-testing-distributed-algorithms.md)

- [1. Why ordinary tests aren't enough](11-testing-distributed-algorithms.md#1-why-ordinary-tests-arent-enough)
- [2. Scenario (example-based) tests](11-testing-distributed-algorithms.md#2-scenario-example-based-tests)
- [3. Property-based testing](11-testing-distributed-algorithms.md#3-property-based-testing)
- [4. Randomized simulation (the big one)](11-testing-distributed-algorithms.md#4-randomized-simulation-the-big-one)
- [5. Deterministic simulation testing in industry](11-testing-distributed-algorithms.md#5-deterministic-simulation-testing-in-industry)
- [6. Model checking (testing the design, not the code)](11-testing-distributed-algorithms.md#6-model-checking-testing-the-design-not-the-code)
- [7. Jepsen](11-testing-distributed-algorithms.md#7-jepsen)
- [8. Testing real-time UIs](11-testing-distributed-algorithms.md#8-testing-real-time-uis)
- [Check yourself](11-testing-distributed-algorithms.md#check-yourself)
- [Practice](11-testing-distributed-algorithms.md#practice)

### [12. System Design Interviews](12-system-design-interviews.md)

- [1. A framework for collaboration questions](12-system-design-interviews.md#1-a-framework-for-collaboration-questions)
- [2. Design Google Docs](12-system-design-interviews.md#2-design-google-docs)
- [3. Design Figma / a collaborative whiteboard](12-system-design-interviews.md#3-design-figma--a-collaborative-whiteboard)
- [4. Design "who's viewing this page" (presence for any app)](12-system-design-interviews.md#4-design-whos-viewing-this-page-presence-for-any-app)
- [5. Design a multiplayer game backend (state sync)](12-system-design-interviews.md#5-design-a-multiplayer-game-backend-state-sync)
- [6. Design a Notion-like block editor](12-system-design-interviews.md#6-design-a-notion-like-block-editor)
- [7. Design a distributed counter (likes/views at scale)](12-system-design-interviews.md#7-design-a-distributed-counter-likesviews-at-scale)
- [8. Common follow-up questions (with short answers)](12-system-design-interviews.md#8-common-follow-up-questions-with-short-answers)
- [9. Numbers to remember (approximate)](12-system-design-interviews.md#9-numbers-to-remember-approximate)
- [10. Pitfalls in interviews](12-system-design-interviews.md#10-pitfalls-in-interviews)

### [13. Glossary & Resources](13-glossary-and-resources.md)

- [Glossary](13-glossary-and-resources.md#glossary)
- [Resources by chapter](13-glossary-and-resources.md#resources-by-chapter)
- [Libraries and tools](13-glossary-and-resources.md#libraries-and-tools)
