# 06: Mini Kafka (A Log-Based Message Broker)

> You've used Kafka in `../Kafka/`. Now you build the core of one: an append-only log on disk, partitions, consumer groups, and replication. By the end you'll know what `acks=all` actually means, because you implemented it.

---

## The story

Your team needs a durable, replayable event stream: order events, clickstream, audit logs. Consumers must be able to **rewind** and reprocess. Several teams read the same stream at their own pace. A broker can die without losing acknowledged messages.

You build **your own broker** (name it) to see how the real one works inside.

## What you'll learn

Append-only logs · record formats and checksums · offsets · segment files · sparse indexes · retention · sequential I/O and the page cache · batching and compression · zero-copy transfer · consumer offsets and delivery semantics · consumer group coordination · rebalancing · leader/follower replication · in-sync replicas · high watermark · `acks` semantics · leader election · unclean failover · log compaction and tombstones.

## Scope

| In | Out |
|---|---|
| Produce/fetch API, segments, retention, consumer groups, replication, compaction | Kafka wire-protocol compatibility (stretch), transactions, ACLs |
| Benchmarks in MB/s and msgs/s | Full controller quorum (you'll simplify; see L4) |

**Tech:** Go or Java. Plain files on disk. A simple binary or HTTP protocol you design. Toxiproxy (L4). Redpanda or Kafka in Docker as a baseline to compare against.

---

## Base system spec

### API (you design the wire format; document it in `docs/protocol.md`)

```
produce(topic, partition, records[])          -> baseOffset
fetch(topic, partition, offset, maxBytes)     -> records[] (+ nextOffset)
createTopic(name, partitions, replicationFactor)
commitOffset(group, topic, partition, offset)     (L3)
fetchCommitted(group, topic, partition)           (L3)
joinGroup / heartbeat / leaveGroup                (L3)
```

A record has: offset, timestamp, key (optional), value, and whatever integrity fields **you** decide are needed.

### Non-functional targets (final)
- Throughput target in MB/s, which **you** set in L2 after measuring your disk.
- No **acknowledged** message lost when one broker dies (at the right `acks` setting).
- Consumers can read from any retained offset.

### Results table: `docs/results.md`

| Level | Config | Record size | Batch size | Produce MB/s | Produce msgs/s | Fetch MB/s | p99 produce latency | Notes |
|---|---|---|---|---|---|---|---|---|

---

## Level 0: The append-only log

**Incident:** None. One topic, one partition, one file. Produce appends; fetch reads from an offset.

**Done when:**
- [ ] Produce and fetch work. Offsets are dense and monotonic.
- [ ] Restart the broker: all data is still there, and new offsets continue from the right place.
- [ ] A corrupted or half-written last record is detected on startup.
- [ ] Fetching offset N is fast even when N is in the middle of a 1 GB file.

**Predict first:** How long does finding offset 5,000,000 take with no index?

**Design questions:**
1. What's your record format on disk? How do you know where one record ends and the next begins? How do you detect corruption?
2. How do you find the byte position of offset N quickly?
3. On restart, how do you find the next offset? How do you handle a torn write at the end?
4. When do you `fsync`? What does that mean for "acknowledged"? (You did this in project 02 L2. Is the answer the same here?)
5. Why does an append-only design make concurrent readers and one writer easy?

**Search:** `append-only log`, `length-prefixed record`, `CRC32C`, `Kafka record batch format`, `torn write recovery`, `Jay Kreps The Log`.

**Self-evaluation:**
- 🟢 A crash-and-restart test (`kill -9` during produce, 50 times) never returns a corrupt record.
- 🔴 Fetch scans from the start of the file.

---

## Level 1: Segments and retention

**Incident:** The log is 200 GB and growing. Disk is filling up, you need to delete data older than 7 days, and you can't delete from the middle of one file. Startup index rebuilding takes 10 minutes.

**Done when:**
- [ ] The log is split into **segment** files that roll by size or time.
- [ ] Each segment has an **index**. Choose sparse or dense and justify it.
- [ ] Retention by time and by size deletes whole old segments safely, **while consumers may be reading them**.
- [ ] Startup time measured with 50 segments.

**Predict first:** Index size for a dense index vs one entry every 4 KB, for a 1 GB segment of 1 KB records?

**Design questions:**
1. Why segments instead of one big file? List at least 3 reasons.
2. **Sparse vs dense** index: what's the lookup cost and the memory cost of each? How does a lookup work with a sparse index?
3. How do you find **which segment** holds offset N?
4. A consumer is reading a segment while retention wants to delete it. What happens? What *should* happen?
5. Should you memory-map index files? What does `mmap` give you, and what are its risks?
6. What does a consumer get if it asks for an offset that has already been deleted?

**Search:** `Kafka log segments`, `sparse index`, `binary search offset index`, `mmap pros and cons`, `log retention`, `unlink open file semantics`.

**Self-evaluation:**
- 🟢 Retention runs during a fetch-heavy test with no errors, and you can explain why.
- 🔴 Startup reads every segment fully.

---

## Level 2: Throughput

**Incident:** The clickstream team needs **hundreds of MB/s**. Your broker does a fraction of that. Producers send one record per request.

**Done when:**
- [ ] You measured your disk's raw sequential write speed (`dd` or `fio`) and set a target as a percentage of it.
- [ ] **Producer batching** with a size limit and a linger time. Chart throughput vs batch size and linger.
- [ ] **Compression** per batch, comparing at least 2 codecs (MB/s, CPU, ratio).
- [ ] **Zero-copy** fetch path measured against a normal read-and-write path.
- [ ] A comparison row against **Redpanda or Kafka** on the same machine.

**Predict first:** Throughput gain from batching 1 → 100 records per request?

**Design questions:**
1. Where does time go per message: syscalls, network round trips, fsync, serialization? Profile it before changing anything.
2. Why is **sequential** disk I/O so much faster than random I/O, even on SSDs?
3. What role does the OS **page cache** play for consumers reading recent data? Why does Kafka lean on it instead of keeping its own cache?
4. **Batching:** what's the latency cost of `linger`? Who should batch, the producer or the broker?
5. What is **zero-copy** (`sendfile` / `FileChannel.transferTo`)? Why does it matter here, and when does it **stop** working (hint: TLS, compression format changes)?
6. Compress per record or per batch? Why?

**Search:** `sequential vs random I/O`, `page cache`, `Kafka design efficiency`, `sendfile zero copy`, `FileChannel transferTo`, `producer batching linger.ms`, `compression codec lz4 zstd snappy`, `fio benchmark`.

**Self-evaluation:**
- 🟢 You know your % of raw disk speed, and the profiler shows what's left.
- 🔴 Benchmarks use 10-byte records only. Test realistic sizes too.

---

## Level 3: Consumer groups

**Incident:** The analytics team runs 4 consumers to keep up with 12 partitions. They need each partition processed by exactly one consumer in the group. Consumers crash and deploy constantly, and after every crash some messages are processed **twice** and some **never**.

**Done when:**
- [ ] Multiple partitions per topic. Producers choose the partition by key hash or round robin.
- [ ] **Consumer groups:** partitions are spread across group members. Each partition has exactly one owner at a time.
- [ ] **Committed offsets** are stored by the broker. A restarted consumer resumes from its commit.
- [ ] **Rebalance** when a consumer joins, leaves, or dies (missed heartbeats).
- [ ] Chaos test: kill and restart consumers randomly. Count lost and duplicate processing, and explain both numbers.

**Predict first:** If a consumer commits **before** processing, what's the failure mode? If it commits **after**?

**Design questions:**
1. Who coordinates the group: the broker, a leader consumer, or both? What does each side know?
2. **Heartbeat interval and session timeout:** what's the trade-off between fast failure detection and false positives (GC pause)?
3. Commit **before** vs **after** processing: which gives at-most-once and which gives at-least-once? How do you get "effectively once"?
4. During a rebalance, a consumer still processing partition 3 loses it to another consumer. What happens to its in-flight work? How do real systems handle this? (Compare eager vs cooperative rebalancing.)
5. What is a **zombie** consumer, and how does a broker reject its stale commits? (Think back to fencing in project 03 L1.)
6. Where are committed offsets stored? Does Kafka use a special topic for them? Why might that be a good idea?

**Search:** `Kafka consumer group protocol`, `group coordinator`, `session.timeout.ms heartbeat`, `eager vs cooperative rebalancing`, `static group membership`, `__consumer_offsets`, `at-least-once vs at-most-once commit`, `generation id fencing`.

**Self-evaluation:**
- 🟢 Chaos test: 0 lost with commit-after, duplicates counted and explained.
- 🔴 Two consumers own the same partition during a rebalance and both commit.

---

## Level 4: Replication

**Incident:** A broker's disk dies, and with it every message on that broker. Producers had received `OK` for some of those messages.

**Done when:**
- [ ] 3 brokers. Each partition has a **leader** and followers. Followers pull from the leader.
- [ ] **In-sync replica set (ISR):** followers that fall too far behind are removed, and added back when they catch up.
- [ ] **High watermark:** consumers only see messages that are safely replicated.
- [ ] `acks=0`, `acks=1`, `acks=all` implemented. For each one, **kill the leader mid-produce** and count acknowledged-but-lost messages.
- [ ] Leader failover. Choose how the new leader is picked. You may use a simple external coordinator (one process, or a lease in a DB) at first. Note this as a known single point of failure until project 07.
- [ ] Toxiproxy tests: follower slow, follower partitioned, leader partitioned.

**Predict first:** Acknowledged-but-lost count for `acks=1` under leader kill, out of 100k messages?

**Design questions:**
1. When is a message "committed"? Who decides, and how do followers learn about it?
2. Why must consumers only read up to the **high watermark**? Build a test where reading past it shows a message that later disappears.
3. `acks=all` with ISR = {leader only}: is that still safe? What does `min.insync.replicas` add?
4. The leader is partitioned from the followers but **not** from producers. What happens to writes? What happens when it rejoins?
5. **Unclean leader election:** if no in-sync replica is alive, do you promote an out-of-sync one (availability) or stay down (consistency)? Which would you pick for payments and which for clickstream?
6. Followers **pull** rather than the leader pushing. Why?
7. Who elects the leader? What happens if your coordinator dies? (Look up ZooKeeper vs KRaft.)

**Search:** `Kafka replication ISR`, `high watermark`, `acks=all min.insync.replicas`, `unclean leader election`, `leader epoch`, `Kafka KRaft`, `ZooKeeper`, `DDIA replication chapter`.

**Self-evaluation:**
- 🟢 You have a table of acks setting vs lost acknowledged messages under leader kill, and it matches your predictions.
- 🟢 You can explain the high watermark with a timeline drawing.
- 🔴 Failover is safe only if the coordinator never dies, and you didn't write that down.

---

## Level 5: Log compaction

**Incident:** A topic stores **the latest state of each customer** (key = customerId). New services need to bootstrap by reading "the latest value for every key". With time-based retention, old customers' only record gets deleted. Without retention, the topic grows forever.

**Done when:**
- [ ] Compacted topics keep **at least the latest record for each key**.
- [ ] **Tombstones** (null value) delete a key, and are themselves removed after a safe delay.
- [ ] Compaction runs in the background on closed segments without blocking produce/fetch.
- [ ] A consumer reading from offset 0 of a compacted topic ends with the same key → value map as one that read everything live.

**Predict first:** What's the risk of deleting a tombstone too early?

**Design questions:**
1. Which segments can be compacted safely? Why not the active one?
2. How do you build "latest offset per key" without loading every key into memory? What if there are 100M keys?
3. Offsets after compaction are no longer dense. Does that break consumers or your index?
4. When is it safe to remove a tombstone? What happens to a consumer that was offline longer than that?
5. What are compacted topics used for in real systems? (Look up how Kafka stores consumer offsets, and Kafka Streams changelogs.)

**Search:** `Kafka log compaction`, `tombstone delete.retention.ms`, `compacted topic use cases`, `changelog topic`, `table-stream duality`.

**Self-evaluation:**
- 🟢 The bootstrap-equivalence test passes.
- 🔴 Compaction rewrites segments that consumers are reading, with no safe swap.

---

## Graduation: answer out loud
1. Why is Kafka fast? Name 4 mechanisms and the numbers you measured for each.
2. What does `acks=all` guarantee, and what does it **not**?
3. Explain the high watermark and why consumers can't read past it.
4. Commit before or after processing? What does each give you?
5. Walk through what happens when a consumer group rebalances.
6. When would you choose a compacted topic over a database table?
7. Log vs queue (SQS): when would you choose each?

## Taking it further
Implement enough of the real Kafka wire protocol that an official Kafka client can produce to it · tiered storage (old segments to S3) · exactly-once producer (idempotent producer IDs and sequence numbers) · a stream processor on top (windowed counts) · replace your coordinator with your Raft implementation from project 07.
