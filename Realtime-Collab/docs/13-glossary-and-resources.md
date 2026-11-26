# 13. Glossary & Resources

## Glossary

| Term | Meaning | Chapter |
|---|---|---|
| **Ack** | Message confirming that something was received/applied | 4 |
| **Anti-entropy** | Background process that compares replicas and repairs differences | 2, 8 |
| **Associative** | Grouping doesn't matter: `merge(merge(a,b),c) = merge(a,merge(b,c))` | 2 |
| **At-least-once** | Messages may be delivered more than once, never zero times (with retries) | 2 |
| **Awareness** | Yjs's name for presence | 9 |
| **Backoff (exponential, with jitter)** | Wait longer after each failed retry, with randomness to avoid synchronized retries | 4 |
| **Backpressure** | Handling a receiver that can't keep up (bounded queues, dropping, disconnecting) | 4 |
| **Batching (per tick)** | Combining many updates into one message per interval | 3, 4, 9 |
| **Bloom filter** | Compact probabilistic set: "definitely not present" or "probably present" | 8 |
| **CALM theorem** | Monotonic programs can run without coordination | 5 |
| **CAP** | Under partition, choose consistency or availability | 2 |
| **Causal delivery** | If A → B, every replica delivers A before B | 2 |
| **Causal stability** | Every replica has seen an operation; its tombstone can be collected | 5 |
| **Causality / happens-before (→)** | A could have influenced B | 1 |
| **Client-side prediction** | Applying your own input immediately, before the server confirms | 3 |
| **CmRDT (op-based CRDT)** | Replicas exchange operations; needs causal, exactly-once delivery | 5 |
| **Commutative** | Order doesn't matter: `merge(a,b) = merge(b,a)` | 2 |
| **Compaction** | Rewriting a log as a snapshot to save space and load time | 8 |
| **Compose (OT)** | Combine two ops into one with the same effect | 6 |
| **Concurrent (‖)** | Neither event happened-before the other | 1 |
| **Consistent hashing** | Mapping keys to servers on a ring so few keys move when servers change | 4 |
| **Convergence** | Replicas with the same updates end up identical | 0, 2 |
| **CRDT** | Conflict-free Replicated Data Type: merges always converge | 5 |
| **CvRDT (state-based CRDT)** | Replicas exchange full states; merge is commutative, associative, idempotent | 5 |
| **Dead reckoning** | Predicting position from last known velocity | 3 |
| **Dedup** | Ignoring a message already applied, usually by `(clientId, seq)` | 2, 4 |
| **Delta-state CRDT** | Exchange small state fragments that merge like full states | 5 |
| **Dotted version vector** | Version vector variant that avoids sibling explosion | 1 |
| **Easing** | A function that reshapes `t` to change animation feel | 3 |
| **Escrow / bounded counter** | Pre-splitting a limited resource among replicas | 5 |
| **Eventual consistency** | Replicas agree eventually if writes stop | 2 |
| **Extrapolation** | Guessing beyond the last known data | 3 |
| **Fan-out** | Sending one update to many receivers | 4 |
| **Fractional indexing** | Sortable position keys between neighbors, for ordering items | 7, 10 |
| **Fugue** | Sequence CRDT designed to minimize interleaving | 7 |
| **G-Counter** | Grow-only counter: per-replica counts merged by max | 5 |
| **G-Set** | Grow-only set: merge by union | 5 |
| **Gateway** | Server tier holding client connections | 4 |
| **Gossip** | Nodes repeatedly sync with random peers | 8 |
| **Half-open connection** | One side disappeared; the other thinks it's still connected | 4 |
| **Heartbeat** | Periodic message proving liveness | 4, 9 |
| **HLC (Hybrid Logical Clock)** | Causality-respecting timestamps close to real time | 1 |
| **Idempotent** | Applying twice = applying once | 2 |
| **Intention preservation** | The merged result matches what users meant | 0 |
| **Interest management** | Only sending updates a receiver can see or needs | 3, 9 |
| **Interleaving anomaly** | Concurrent typed texts mixed character by character | 7 |
| **Interpolation** | Estimating values between two known samples | 3 |
| **Jitter** | Variation in network delay | 3 |
| **Jitter buffer** | A small delay buffer that smooths uneven arrivals | 3 |
| **Join-semilattice** | Partial order where any two states have a least upper bound (the merge) | 2 |
| **Lag compensation** | Server rewinds time to judge actions as the player saw them | 3 |
| **Lamport clock** | Counter that respects causality: `A → B ⇒ L(A) < L(B)` | 1 |
| **Lease** | Time-limited ownership, renewed while alive | 4 |
| **Lerp** | Linear interpolation `a + (b − a)·t` | 3 |
| **Linearizable** | Behaves like a single up-to-date copy | 2 |
| **Local-first** | The local copy is the source of truth; sync in the background | 8 |
| **Lockstep** | All peers run the same deterministic simulation from shared inputs | 3 |
| **Logoot / LSEQ** | Sequence CRDTs using dense position identifiers | 7 |
| **LWW (last-writer-wins)** | Keep the value with the highest timestamp | 2, 5 |
| **LWW-Element-Set** | Set with add/remove timestamps per element | 5 |
| **LWW-Map** | Map with an LWW-Register per key | 5 |
| **LWW-Register** | Single value; highest timestamp wins | 5 |
| **Merkle tree** | Tree of hashes for finding differing ranges cheaply | 8 |
| **Monotonic clock** | Clock that never goes backwards; for durations | 1 |
| **MV-Register** | Keeps all concurrent values (siblings) | 5 |
| **NTP** | Protocol to sync machine clocks over the network | 1 |
| **Op log** | Append-only list of all operations of a document | 8 |
| **Optimistic update** | Show a change before the server confirms it | 0, 10 |
| **OR-Set** | Observed-remove set; add wins over concurrent remove | 5 |
| **OT (Operational Transformation)** | Adjust concurrent operations so replicas converge | 6 |
| **PACELC** | Partition: A vs C; Else: Latency vs Consistency | 2 |
| **Peritext** | Rich-text CRDT with formatting expansion rules | 7 |
| **PN-Counter** | Increment/decrement counter from two G-Counters | 5 |
| **Presence** | Ephemeral who's-here data: cursors, selections, online status | 9 |
| **Rebase (client)** | Re-applying pending local changes on top of new server state | 10 |
| **Reconciliation (server)** | Correcting a predicted state with the server's authoritative state | 3, 10 |
| **Relative position** | A cursor/anchor expressed relative to a character ID | 9 |
| **Replica** | One copy of the shared data | 0 |
| **RGA** | Replicated Growable Array: insert-after-ID sequence CRDT | 7 |
| **Rollback netcode** | Predict, then roll back and re-simulate when real input arrives | 3 |
| **Run-length encoding** | Storing consecutive similar items as one item | 7 |
| **SEC (strong eventual consistency)** | Same updates received → same state, immediately | 2, 5 |
| **Sibling** | One of several concurrent versions kept for later resolution | 1, 5 |
| **Slerp / nlerp** | Interpolation for rotations (quaternions) | 3 |
| **Snapshot** | Full document state at a revision | 8 |
| **Snapshot interpolation** | Rendering remote objects slightly in the past between two snapshots | 3 |
| **Snowflake ID** | Time-sortable 64-bit unique ID (timestamp + worker + sequence) | 10 |
| **SSE** | Server-Sent Events: one-way server→client stream over HTTP | 4 |
| **State vector** | `clientID → ops received from that client`; used for delta sync | 1, 8 |
| **Thundering herd** | Many clients retrying at the same moment | 4 |
| **Tick** | Fixed simulation/broadcast interval on a server | 3, 4 |
| **Tombstone** | Marker that an item was deleted, kept for correct merging | 5, 7 |
| **Total order broadcast** | Everyone delivers all messages in the same order | 2 |
| **TP1 / TP2** | OT convergence properties for two / three concurrent operations | 6 |
| **Transform (OT)** | `T(a, b)`: rewrite `a` to apply after `b` | 6 |
| **TrueTime** | Spanner's clock API that returns an uncertainty interval | 1 |
| **Vector clock** | Per-node counters that detect concurrency exactly | 1 |
| **Version vector** | Per-replica counters describing which updates a version includes | 1 |
| **WebRTC data channel** | Peer-to-peer browser data connection | 4 |
| **WebSocket** | Full-duplex persistent connection upgraded from HTTP | 4 |
| **WebTransport** | HTTP/3 (QUIC) based streams and datagrams | 4 |
| **YATA** | The sequence CRDT algorithm used by Yjs | 7 |

---

## Resources by chapter

### Foundations (Chapters 1–2)
- Leslie Lamport, *Time, Clocks, and the Ordering of Events in a Distributed System* (1978).
- Martin Kleppmann, *Distributed Systems* lecture course, University of Cambridge (YouTube + lecture notes).
- Martin Kleppmann, *Designing Data-Intensive Applications*.
- Kulkarni et al., *Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases* (HLC).
- DeCandia et al., *Dynamo: Amazon's Highly Available Key-value Store* (2007).
- Hellerstein & Alvaro, *Keeping CALM: When Distributed Consistency Is Easy* (2019).

### Interpolation and netcode (Chapter 3)
- Gabriel Gambetta, *Fast-Paced Multiplayer* (series with live demo).
- Glenn Fiedler, Gaffer On Games: *Snapshot Interpolation*, *Fix Your Timestep!*, *Networked Physics*.
- Valve Developer Wiki, *Source Multiplayer Networking*.
- Freya Holmér, *Lerp smoothing is broken* (talk).
- Terrano & Bettner, *1500 Archers on a 28.8: Network Programming in Age of Empires and Beyond*.

### Transport and scaling (Chapter 4)
- RFC 6455, *The WebSocket Protocol*.
- AWS Architecture Blog, *Exponential Backoff And Jitter*.
- Slack Engineering, *Real-time Messaging*.
- Discord Engineering blog (Elixir scaling posts).

### CRDTs (Chapters 5, 7)
- Jake Lazaroff, *An Interactive Intro to CRDTs*.
- Matthew Weidner, *CRDT Survey* (blog series).
- Shapiro, Preguiça, Baquero, Zawirski, *A comprehensive study of Convergent and Commutative Replicated Data Types* (2011).
- Almeida, Shoker, Baquero, *Delta State Replicated Data Types*.
- Kleppmann & Beresford, *A Conflict-Free Replicated JSON Datatype* (2017).
- Martin Kleppmann, *CRDTs: The Hard Parts* (talk).
- Seph Gentle, *5000x faster CRDTs: An Adventure in Optimization*; *I was wrong. CRDTs are the future*.
- Weidner & Kleppmann, *The Art of the Fugue* (2023).
- Roh et al. (RGA, 2011); Nicolaescu et al. (YATA, 2016).
- Kleppmann et al., *Interleaving anomalies in collaborative text editors* (2019).
- Kleppmann et al., *A highly-available move operation for replicated trees* (2021).
- Ink & Switch, *Peritext* (2022).
- crdt.tech.

### OT (Chapter 6)
- Daniel Spiewak, *Understanding and Applying Operational Transformation*.
- Tim Baumann, **ot.js** and its visualization.
- Ellis & Gibbs, *Concurrency Control in Groupware Systems* (1989).
- Nichols et al., the Jupiter collaboration system paper (1995).
- Google Wave OT whitepaper.
- Marijn Haverbeke, *Collaborative Editing in CodeMirror*.

### Sync, storage, local-first (Chapter 8)
- Ink & Switch, *Local-first software: You own your data, in spite of the cloud* (2019).
- James Long, *CRDTs for Mortals* (talk).
- Yjs docs: document updates, y-protocols (sync, awareness).
- Automerge docs: sync protocol, binary format.
- Ink & Switch, *Cambria*.

### Real systems (Chapter 10)
- Evan Wallace, *How Figma's multiplayer technology works* (2019).
- Figma blog, *Realtime editing of ordered sequences*.
- Replicache docs, "How it works".
- Twitter Engineering, *Announcing Snowflake*.

### Testing (Chapter 11)
- Will Wilson, *Testing Distributed Systems w/ Deterministic Simulation*.
- Newcombe et al., *How Amazon Web Services Uses Formal Methods* (2015).
- learntla.com; jepsen.io.

---

## Libraries and tools

| Name | What | Language |
|---|---|---|
| Yjs (+ y-websocket, y-indexeddb, editor bindings) | Sequence/map CRDTs, sync and awareness protocols | JS (Rust port: yrs) |
| Automerge | JSON document CRDT with history | Rust core, JS/WASM, others |
| Loro | CRDTs including text (Fugue), movable lists and trees | Rust core, JS/WASM |
| Diamond Types | Fast sequence CRDT | Rust |
| ShareDB | OT server and client (json0, rich-text) | Node.js |
| ot.js | Small OT library for text | JS |
| Replicache / Zero | Server-authoritative sync with client rebase | JS |
| Hocuspocus | Yjs WebSocket server (Tiptap) | Node.js |
| perfect-cursors | Smooth cursor interpolation | JS |
| coder/websocket | WebSocket library | Go |
| pgregory.net/rapid | Property-based testing with shrinking | Go |

---

[← 12. System Design Interviews](12-system-design-interviews.md) · [Index](README.md)
