# 16: LSM-Tree Storage Engine

> Under Cassandra, RocksDB, LevelDB, ScyllaDB, DynamoDB's storage nodes, and many more, there is one idea: the **log-structured merge tree**. You build one as a library. After that, "why are writes fast and reads sometimes slow?" will make sense at the byte level.

---

## The story

Your team needs a key-value storage engine for **write-heavy** data: messages, events, sensor readings. Updating data in place on disk (as a B-tree does) means lots of random disk writes, which are slow. You'll build an engine that turns all writes into **fast sequential writes**, and then deal with what that costs on reads.

## How it works (plain words)

1. A **write** goes into a sorted structure in memory (the **memtable**), and is appended to a log file (the **WAL**) so it survives a crash.
2. When the memtable is full, it's written to disk as a **sorted, never-changed file** (an **SSTable**).
3. A **read** checks the memtable first, then SSTables from **newest to oldest**, until it finds the key.
4. Over time there are many SSTables, so reads get slower and old versions waste space. **Compaction** runs in the background and merges files, keeping only the newest version of each key.

The whole design is a trade-off between **write cost**, **read cost**, and **space cost**. You'll measure all three.

## What you'll learn

Write-ahead logs · skip lists · immutable file formats · block indexes · manifests and atomic file changes · bloom filters · block cache vs OS page cache · compaction strategies · write / read / space amplification · tombstones · write stalls · k-way merge iterators · sequence numbers and snapshots · group commit · reference-counted versions.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Memtable** | The in-memory sorted table that takes new writes. |
| **WAL** | Write-Ahead Log: an append-only file of every write, used to recover the memtable after a crash. |
| **SSTable** | Sorted String Table: an immutable file of sorted key-value pairs. |
| **Flush** | Writing a full memtable to disk as a new SSTable. |
| **Compaction** | Merging SSTables into new ones, dropping old versions and deleted keys. |
| **Tombstone** | A special "deleted" marker. (You can't delete from an immutable file.) |
| **Bloom filter** | A small structure that answers "is this key **definitely not** in this file?" quickly. |
| **Write amplification** | Bytes written to disk ÷ bytes the user wrote. |
| **Read amplification** | Files (or disk reads) touched per lookup. |
| **Space amplification** | Disk space used ÷ size of the live data. |
| **Manifest** | A file that records which SSTables are currently "live". |
| **Write stall** | The engine slows or blocks writes because compaction can't keep up. |

## Scope

| In | Out |
|---|---|
| Embedded library: put/get/delete/scan/snapshot, WAL, SSTables, bloom filters, compaction, block cache | Network server (a tiny one is a stretch), transactions across keys |
| Benchmarks vs RocksDB / LevelDB / Pebble / Badger | Replication (that's projects 02 and 06) |

**Tech:** Java or Go (or C++/Rust). Plain files. No storage libraries for the core.

---

## Base system spec

### Library API

```
open(path, options)         close()
put(key, value)             get(key) -> value | NOT_FOUND
delete(key)                 scan(startKey, endKey) -> iterator in key order
snapshot() -> Snapshot      get(key, snapshot)     scan(start, end, snapshot)
```

### Benchmark harness (like RocksDB's `db_bench`)
Workloads: `fillseq` (keys in order), `fillrandom`, `overwrite`, `readrandom`, `readmissing` (keys that don't exist), `readseq`, `mixed` (50/50 reads and writes), `zipf` (a few hot keys). Report ops/sec, p50/p99, write amplification, read amplification, space amplification, and number of files.

### Model checker (your referee)
Run random operations against **your engine** and a simple **in-memory sorted map** at the same time. Every `get` and `scan` must match. Then the crash version: run the engine in a child process, `kill -9` it at random moments, reopen it, and check that every **acknowledged** write is there (under your sync setting) and nothing else changed.

| ID | Invariant | From |
|---|---|---|
| K1 | get/scan results always equal the model's | L0 |
| K2 | After crash + reopen, all acknowledged writes are present (per sync policy) | L0 |
| K3 | Deleted keys never come back (no "resurrection") | L3 |
| K4 | A snapshot never changes, whatever writes or compactions happen after it | L4 |

### Results table: `docs/results.md`

| Level | Workload | Ops/sec | p99 | Write amp | Read amp | Space amp | Files | K1 | K2 | K3 | K4 |
|---|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: Memtable and WAL

**Incident:** None. Everything lives in memory, with a log for safety.

**Done when:**
- [ ] **Skip list** memtable written yourself (good DSA practice).
- [ ] WAL with record checksums. Recovery after `kill -9` works (K2).
- [ ] K1 passes for 1M random operations.
- [ ] Sync policy option: sync every write, or sync in batches. Throughput measured for each.

**Predict first:** Ops/sec with "fsync every write" vs batched sync?

**Design questions:**
1. Why must the memtable be **sorted**? What will need that later?
2. Skip list vs balanced tree: why do many LSM engines pick skip lists? (Think about concurrent readers.)
3. WAL record format: how do you detect a half-written last record? (Projects 02 L2 and 06 L0: reuse your answer.)
4. Group commit: how can many writes share one fsync without breaking durability?
5. Readers reading while a writer writes: what has to be safe?

**Search:** `skip list`, `LevelDB memtable`, `write-ahead log`, `group commit`, `CRC32C`, `lock-free skip list readers`.

**Self-evaluation:**
- 🟢 The crash test runs 100 times with K2 passing.
- 🔴 A torn last record crashes recovery.

---

## Level 1: Flush to SSTables

**Incident:** Memory is full. The data must go to disk.

**Done when:**
- [ ] A full memtable is **flushed** to an immutable SSTable file.
- [ ] SSTable layout: **data blocks** + **index block** + **footer**. Documented in `docs/format.md`.
- [ ] Reads check the memtable, then SSTables newest → oldest. K1 passes.
- [ ] A **manifest** tracks live files. A crash **during** a flush leaves the engine consistent. Test it.
- [ ] The WAL is deleted only when it's safe.

**Predict first:** How many disk reads for one `get` from an SSTable with a block index?

**Design questions:**
1. How do you find a key inside a 64 MB SSTable without reading the whole file?
2. Why does making files **immutable** make crash safety and concurrency easier?
3. When is it safe to delete the old WAL? What goes wrong if you delete it one step too early?
4. The manifest must change **atomically**. Options: write a new file and rename it, or append edits to a log. How does each survive a crash?
5. Why must you `fsync` the **directory**, not just the file, after creating or renaming files?

**Search:** `LevelDB SSTable table format`, `block index`, `footer magic number`, `LevelDB manifest VersionEdit`, `atomic rename fsync directory`.

**Self-evaluation:**
- 🟢 Crash-during-flush test passes 50/50.
- 🔴 The WAL is deleted before the SSTable and manifest are durable.

---

## Level 2: Reads get slow

**Incident:** After a day of writes there are **500 SSTables**. A `get` for a key that **doesn't exist** checks all 500 files. p99 is terrible.

**Done when:**
- [ ] A **bloom filter** per SSTable.
- [ ] Files read per `get` measured for **existing** and **missing** keys, before and after.
- [ ] Bloom false-positive rate measured at different bits-per-key, and compared with the formula.
- [ ] A **block cache** (LRU) with a hit ratio metric.

**Predict first:** With 10 bits per key, what false-positive rate do you expect? And measured?

**Design questions:**
1. How does a bloom filter work? Why can it say "definitely not here" but only "maybe here"?
2. Bits per key vs false-positive rate: find the formula. How many hash functions should you use?
3. Where does the filter live: inside the SSTable, loaded into memory on open? How much memory for 500 files?
4. Your block cache **and** the OS page cache may both hold the same data. Is that a problem? What can you do?
5. Which workload benefits most from bloom filters, and which hardly at all?

**Search:** `bloom filter false positive rate formula`, `optimal number of hash functions bloom filter`, `block cache`, `page cache double caching`, `O_DIRECT`.

**Self-evaluation:**
- 🟢 Your measured false-positive rate matches the formula within a small error.
- 🔴 Bloom filters are checked **after** reading the data block.

<details><summary>Hint (testing the bloom filter)</summary>
Insert N keys, then query N <b>different</b> keys that you know aren't there. Count the "maybe" answers.
</details>

---

## Level 3: Compaction

**Incident:** Disk usage is **5×** the real data (old versions and deleted keys). Reads keep getting slower. Then, under a heavy write load, **writes stall** for seconds because compaction can't keep up.

**Done when:**
- [ ] **Two** compaction strategies: size-tiered and leveled.
- [ ] Write amp, read amp, and space amp measured for each, under `fillrandom` and `overwrite`.
- [ ] Tombstones dropped **only when safe**. K3 passes (deleted keys never come back).
- [ ] Write **slowdown/stall** when there are too many unmerged files, instead of falling over.
- [ ] p99 of foreground writes and reads measured **during** heavy compaction.

**Predict first:** Which strategy has lower write amplification? Which has lower space amplification?

**Design questions:**
1. How do you **measure** write amplification from your own counters?
2. Size-tiered vs leveled: which is better for write-heavy, read-heavy, and space-limited workloads? Why?
3. When can a tombstone be dropped? What happens if you drop it too early? (Build a test where a deleted key "comes back".)
4. Compaction uses disk bandwidth. What happens to user requests while it runs? How would you limit it?
5. Slow writes down, or let reads get slower? Who should decide?
6. Look up the "RUM conjecture". How does it describe what you just measured?

**Search:** `LSM compaction leveled vs size-tiered`, `RUM conjecture`, `write amplification LSM`, `tombstone compaction bottommost level`, `RocksDB write stall`, `compaction rate limiter`.

**Self-evaluation:**
- 🟢 You have an amplification table for both strategies, and can explain every number.
- 🔴 Compaction blocks all reads and writes while it runs.

---

## Level 4: Range scans and snapshots

**Incident:** Users need "all keys from `user:100` to `user:200`" and a **consistent view** for backups: a frozen picture of the data, while writes continue.

**Done when:**
- [ ] `scan(start, end)` merges the memtable and all SSTables into one sorted stream. Newest version wins, and tombstones are hidden.
- [ ] Every write gets a **sequence number**.
- [ ] `snapshot()` gives a view that never changes (K4), even while writes and compactions happen.
- [ ] Compaction keeps old versions that a live snapshot still needs.

**Predict first:** What's the cost of a scan over 1,000 keys spread across 10 files?

**Design questions:**
1. How do you merge many sorted sources efficiently? (k-way merge with a heap: DSA.)
2. The same key appears in 4 sources. How do you pick the right version?
3. What exactly is stored with each entry so that snapshots work?
4. A snapshot is just a number. Why is that enough?
5. A snapshot that's held open for a week: what does it do to disk space?

**Search:** `k-way merge heap`, `merge iterator LSM`, `MVCC sequence numbers`, `LevelDB snapshots`, `long-running snapshot space amplification`.

**Self-evaluation:**
- 🟢 K4 passes with heavy writes and compactions during a 10-minute snapshot.
- 🔴 Scans copy everything into memory first.

---

## Level 5: Many threads, and the real benchmark

**Incident:** The application has 16 writer threads and 32 reader threads. Your engine has one big lock. Throughput doesn't grow beyond 1 thread.

**Done when:**
- [ ] Throughput measured with 1, 4, 8, and 16 writers, and with readers at the same time.
- [ ] Many writers share one WAL sync (**group commit**).
- [ ] Readers don't block writers. They use a stable "version" (set of files) that's reference-counted.
- [ ] Flush and compaction run in background threads.
- [ ] Your numbers compared with **RocksDB, LevelDB, Pebble, or Badger** on the same machine and workloads.

**Predict first:** What will limit write throughput at 16 threads?

**Design questions:**
1. Where's the write lock now? What must be serialized, and what can run in parallel?
2. Group commit: how do you collect writes from many threads into one batch?
3. A compaction finishes and deletes old files while a reader is still reading them. How do you prevent that? (Reference counting.)
4. How many background threads for flush vs compaction?
5. Where are you slower than RocksDB, and why? Profile it.

**Search:** `group commit write batch`, `LevelDB write queue`, `reference counting versions LSM`, `compaction scheduling threads`, `db_bench`, `Pebble`.

**Self-evaluation:**
- 🟢 Throughput scales with threads up to a point, and you know what stops it.
- 🔴 Compaction deletes a file that a reader still has open.

---

## Stretch levels
- **Key-value separation** (WiscKey): store big values outside the LSM. When does it help?
- **Block compression** (lz4 / zstd): size vs CPU.
- **Prefix bloom filters** for scans.
- **Use it:** plug it in as the storage for your Mini Redis (02) or Mini Kafka (06) offsets.

## Graduation: answer out loud
1. Explain an LSM tree from write to read.
2. Why are LSM writes fast? What does that cost?
3. Write amplification vs read amplification vs space amplification. Explain each with your numbers.
4. How does a bloom filter help, and how do you size it?
5. Leveled vs size-tiered compaction: which one, when?
6. LSM tree vs B-tree: when would you pick each?

## Taking it further
A tiny network server on top · a comparison blog post with your amplification charts · transactions on top (optimistic, using sequence numbers) · tiered storage of cold SSTables to S3 (your project 15!).
