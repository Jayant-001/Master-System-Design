# 15: Mini S3 (Object Store)

> S3 is often called the most important AWS service. Here you build a small one and learn where the famous "eleven nines" of durability come from, and what it takes to not lose files when disks die every day.

---

## The story

Your company stores everything in files: user photos, backups, logs, ML datasets. It will reach **petabytes**. Disks fail all the time at that scale. You need storage that:
- **Never** loses a file it said was saved.
- Never returns a **corrupted** file.
- Handles files from 1 KB to 5 TB.
- Doesn't cost 3× more than it has to.

## How it works (plain words)

An object store has two parts:
1. **Metadata:** a list of names → "where are the pieces of this file, how big is it, what's its checksum". Think of it as a phone book.
2. **Data nodes:** machines with disks that store the actual pieces (**chunks**).

To save a file, you split it into chunks, store each chunk on **several** data nodes in **different places**, and **then** write the metadata entry. Reading looks up the metadata and fetches the chunks. When a disk dies, the system notices and **re-creates** the missing copies elsewhere, before another disk dies.

## What you'll learn

Separating metadata from data · streaming uploads · atomic visibility · chunking · replication vs erasure coding · durability math · failure domains · placement · write quorums · repair and repair priority · bit rot and scrubbing · end-to-end checksums · multipart and resumable uploads · garbage collection · sharding metadata · LIST at scale.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Bucket / object / key** | A folder-like container / a file / the file's name. |
| **Metadata** | Information about the object: size, checksum, where its chunks are. |
| **Chunk** | A piece of a file. |
| **Replication factor** | How many full copies of each chunk you keep (e.g. 3). |
| **Erasure coding (k+m)** | Split data into k pieces, add m extra "parity" pieces. Any k of the k+m pieces can rebuild the data. Like RAID, but across machines. |
| **Failure domain** | A group of things that fail together: one disk, one machine, one rack, one zone. |
| **Durability** | The chance a saved object is **not** lost in a year. "Eleven nines" = 99.999999999%. |
| **Availability** | Can you read it **right now**? This is different from durability. |
| **Bit rot** | Data on a disk silently changing over time. |
| **Scrubbing** | Reading all stored data regularly to find and fix bit rot. |
| **Multipart upload** | Uploading a big file as many parts, in parallel, that can be retried one by one. |

## Scope

| In | Out |
|---|---|
| PUT/GET/DELETE/LIST, chunking, replication, erasure coding, repair, scrubbing, multipart, GC | IAM-style permissions, billing |
| 6-9 data node containers with zone labels, chaos tools | Full S3 API compatibility (stretch) |

**Tech:** Java or Go. Postgres for metadata (sharded later). Data nodes store chunks as plain files on a Docker volume. A Reed-Solomon library for erasure coding (XOR parity by hand first). Prometheus + Grafana.

---

## Base system spec

### API contract

```
PUT    /{bucket}/{key}            (body = bytes)      -> 200 {etag (checksum)}
GET    /{bucket}/{key}            (Range supported later)
HEAD   /{bucket}/{key}            -> size, etag, lastModified
DELETE /{bucket}/{key}
GET    /{bucket}?prefix=&cursor=&limit=               -> keys in sorted order, next cursor

# multipart (L4)
POST   /{bucket}/{key}?uploads                         -> {uploadId}
PUT    /{bucket}/{key}?uploadId=&partNumber=           -> {partEtag}
POST   /{bucket}/{key}?uploadId=   {parts: [...]}      # complete
DELETE /{bucket}/{key}?uploadId=                       # abort
```

### Cluster
- 1 API/front service, 1 metadata DB, and **6-9 data nodes**, each labeled with a **zone** (A, B, C).

### Chaos tools (build them first; `chaos/`)

| Tool | What it does |
|---|---|
| `kill-node` | Stop a data node |
| `wipe-node` | Delete all chunk files on a node (a disk died) |
| `rot` | Flip random bits in random chunk files (silent corruption) |
| `slow-node` | Add latency to one node (Toxiproxy) |
| `kill-zone` | Stop all nodes in one zone |

### Durability checker (your referee)

| ID | Invariant | From |
|---|---|---|
| S1 | Every object that got a `200` on PUT can be read back with **exactly** the same bytes (checksum) | L0 |
| S2 | No GET ever returns corrupted bytes with a `200` | L3 |
| S3 | Storage used ≈ expected (no growing garbage) | L5 |
| S4 | No object is visible before its upload finished | L0 |

### Results table: `docs/results.md`

| Level | Scenario | Upload MB/s | Download MB/s | Storage overhead (stored ÷ data) | Repair time | S1 | S2 | S3 | S4 |
|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: A single-node store

**Incident:** None. Metadata in Postgres, data on one node.

**Done when:**
- [ ] PUT, GET, HEAD, DELETE, and LIST work.
- [ ] A **1 GB upload streams** through the service without loading the whole file into memory. Measure memory during upload.
- [ ] An upload killed halfway leaves **no visible object** (S4).
- [ ] LIST returns keys in order, page by page, for 1M keys.

**Predict first:** If you read the whole upload into memory first, how many concurrent 1 GB uploads before your service crashes?

**Design questions:**
1. What goes in the metadata row? What goes on the data node?
2. How do you stream an upload from the client to disk without holding it all in memory?
3. In which **order** do you write data and metadata so that a crash never leaves a "visible but broken" object?
4. Two clients PUT the same key at the same moment. Who wins? What does each client get back?
5. LIST with 1M keys: how does the cursor work? What does a client see if objects are added during paging?

**Search:** `streaming upload`, `atomic rename`, `write data then commit metadata`, `content MD5 ETag`, `S3 strong read-after-write consistency`, `cursor pagination`.

**Self-evaluation:**
- 🟢 The kill-mid-upload test passes 20/20, and memory stays flat during a 1 GB upload.
- 🔴 A half-uploaded object shows up in LIST.

---

## Level 1: A disk dies

**Incident:** `wipe-node`. Every object on that node is gone.

**Done when:**
- [ ] Each chunk is stored on **3 nodes in 3 different zones**.
- [ ] You decided and documented when a PUT counts as successful (all 3 copies? 2 of 3?).
- [ ] Reads work from any healthy copy. `kill-node` → S1 passes. `kill-zone` → S1 passes.
- [ ] You calculated your durability with real numbers (disk failure rate, repair time).

**Predict first:** With 3 copies and a 1-day repair time, roughly how many nines of durability?

**Design questions:**
1. **Placement:** how do you choose which nodes hold a chunk? Random? Least full? Hashing? Why spread across zones?
2. When is a PUT "done": after all 3 copies are written, or 2 of 3? What do you do about the third if it failed?
3. Why does **repair time** matter so much for durability? Work it out: annual disk failure rate × repair window × number of copies.
4. A node is slow, not dead. Should a write wait for it?
5. How do you read when the first node you try is down?

**Search:** `replication factor`, `failure domains`, `placement policy`, `quorum writes`, `durability calculation nines`, `Backblaze drive stats annual failure rate`, `MTTR durability`.

**Self-evaluation:**
- 🟢 You have a written durability calculation, and the kill-zone test passes.
- 🔴 All 3 copies can end up in the same zone.

---

## Level 2: Too expensive

**Incident:** Finance: "3 copies means we pay for 3 PB of disk to store 1 PB of data. Fix it."

**Done when:**
- [ ] **Erasure coding** for large objects (choose your k+m, e.g. 4+2 or 6+3). Warm up first with simple XOR parity by hand.
- [ ] Storage overhead measured for replication vs erasure coding.
- [ ] Read with one or two nodes down (reconstruct on the fly). Latency cost measured.
- [ ] Durability of your k+m compared with 3× replication, using the same math as L1.
- [ ] You decided which objects use which method, and why.

**Predict first:** Overhead of 4+2? How many node failures can it survive? Is it more or less durable than 3 copies?

**Design questions:**
1. How does XOR parity let you rebuild one missing piece? Try it on paper with 3 small byte arrays.
2. Reed-Solomon k+m: overhead = ? Failures tolerated = ?
3. Rebuilding a lost piece needs reading k other pieces. What does that cost in network and disk during repair?
4. Why is erasure coding a bad fit for **small** objects?
5. Hot vs cold data: which should use replication, and which erasure coding?

**Search:** `erasure coding Reed-Solomon explained`, `XOR parity RAID 5`, `k+m overhead`, `repair bandwidth erasure coding`, `Backblaze Reed-Solomon library`, `Facebook f4 warm BLOB storage paper`.

**Self-evaluation:**
- 🟢 You have a table comparing overhead, read latency (healthy and degraded), and durability for both methods.
- 🔴 Pieces of one object are placed in the same failure domain.

---

## Level 3: Silent failures

**Incident:** (a) A node died a week ago. Nobody noticed. Today a second node **in the same group** dies, and objects are lost. (b) `rot`: a disk flips bits silently. A GET returns a corrupted photo with `200 OK`.

**Done when:**
- [ ] Dead nodes are detected automatically.
- [ ] **Repair** starts automatically, **riskiest objects first** (those with the fewest healthy pieces).
- [ ] A **scrubber** reads every chunk on a schedule and checks its checksum. Bad chunks are repaired.
- [ ] Every GET verifies checksums. S2 passes under `rot`.
- [ ] Full-node repair time measured. Repair doesn't slow down user traffic beyond a limit you set.

**Predict first:** How long does it take to repair a full node at your network speed?

**Design questions:**
1. How fast must repair be? Connect it to the durability math from L1 and L2.
2. Repair order: why fix "1 healthy piece left" before "2 healthy pieces left"?
3. Scrub rate: how often can you read every byte without hurting disks or user traffic?
4. Where do you store checksums? At what levels: per chunk, per object, or both? Why "end-to-end"?
5. Repair traffic vs user traffic: how do you share bandwidth? (Rate limiting from project 05 L3.)

**Search:** `data scrubbing`, `bit rot`, `end-to-end checksums`, `repair prioritization`, `background work rate limiting`, `silent data corruption study`.

**Self-evaluation:**
- 🟢 Under `rot`, no corrupted bytes are ever returned. You have a repair-time measurement.
- 🔴 Checksums are only checked on upload.

---

## Level 4: Big uploads over bad networks

**Incident:** A 50 GB backup upload fails at **98%** over a flaky connection. It restarts from zero. It fails again.

**Done when:**
- [ ] **Multipart upload:** initiate, upload parts (in any order, in parallel), complete, abort.
- [ ] A failed part can be retried alone. Resuming after a client crash works.
- [ ] "Complete" assembles the object **without copying** all the data again.
- [ ] Abandoned multipart uploads are cleaned up after a time you choose.
- [ ] Parallel multipart throughput vs single-stream throughput, measured.

**Predict first:** Throughput gain with 8 parallel parts?

**Design questions:**
1. How does "complete" create the object from parts without moving data?
2. The same part number is uploaded twice. Which one counts?
3. An upload is started and never finished. Those parts are taking disk space. Who cleans them up, and when is it safe?
4. Should part checksums be verified on upload, on complete, or both?
5. What limits do you set on part size and number of parts, and why?

**Search:** `S3 multipart upload`, `resumable upload`, `parallel upload`, `abort incomplete multipart upload lifecycle`, `composite checksum`.

**Self-evaluation:**
- 🟢 Kill the client at random during a 5 GB upload, resume it, and the checksum matches.
- 🔴 Complete copies all parts into a new file.

---

## Level 5: Garbage and the metadata bottleneck

**Incident:** (a) Overwrites and deletes leave **orphaned chunks**, because of crashes between "delete metadata" and "delete chunks". Disks slowly fill with garbage. (b) A bucket with **100M keys**: LIST is slow and the metadata DB is at 100% CPU.

**Done when:**
- [ ] A **garbage collector** finds and deletes orphaned chunks, and **never** deletes a chunk that's in use, **including** chunks from uploads still in progress. S3 passes.
- [ ] Metadata is **sharded** across several databases.
- [ ] LIST still returns keys in **sorted order** across shards.
- [ ] A "hot prefix" (all keys starting with `2025-11-13/`) doesn't overload one shard. Or you explain why your design is fine with it.

**Predict first:** Which race is the most dangerous for your garbage collector?

**Design questions:**
1. Mark-and-sweep vs reference counting for chunks: which races does each have?
2. An upload has written chunks but not metadata yet. How does the GC know not to delete them? (Search: grace period.)
3. Shard metadata by **hash** of the key, or by key **range**? What does each do to LIST with a prefix?
4. Hot prefix: what happens with range sharding? How do real systems split hot ranges?
5. How do you move metadata between shards without downtime?

**Search:** `distributed garbage collection mark and sweep`, `reference counting races`, `grace period deletion`, `range partitioning vs hash partitioning`, `S3 prefix performance`, `range splitting hot spots`.

**Self-evaluation:**
- 🟢 GC runs during heavy upload traffic and never breaks an in-progress upload (S1 passes).
- 🔴 GC deletes anything not in metadata, right now.

---

## Stretch levels
- **S3-compatible API**: make `aws s3 cp` work against your store. (Request signing, SigV4, is a project on its own.)
- **Presigned URLs**: time-limited links to private objects.
- **Versioning**: keep old versions of overwritten objects.
- **Lifecycle tiers**: move old objects to erasure-coded cold storage automatically.

## Graduation: answer out loud
1. Design an object store. Why separate metadata from data?
2. Where do "eleven nines" come from? Do the math.
3. Replication vs erasure coding: trade-offs, and when to use each.
4. How do you find and fix silent corruption?
5. How does multipart upload work, and why is it needed?
6. How do you garbage-collect chunks safely?
7. Durability vs availability: explain the difference with an example.

## Taking it further
S3 API compatibility so real tools work · a web console · cross-region replication · use it as the storage layer for project 13 (CAS) or project 06 (tiered Kafka segments) · write a blog post with your durability math.
