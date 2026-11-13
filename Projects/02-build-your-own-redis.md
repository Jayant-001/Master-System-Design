# 02: Build Your Own Redis

> You built an in-memory KV store in `../Concurrency/` (Mini Redis, TCP Server). This version speaks the **real Redis wire protocol**, so `redis-cli`, `redis-benchmark`, and any Redis client library work against it. Then you make it survive crashes, run out of memory gracefully, replicate, and shard.

---

## The story

Your team pays a lot for a managed cache, and someone says "how hard can it be?" Your job is to find out, by building a server that real Redis tools can't tell apart from Redis (for the commands you support). Then you benchmark it against real Redis on the same machine.

## What you'll learn

TCP framing · protocol parsing · I/O models (thread-per-connection, event loop, reactor) · C10K · page cache, `write` vs `fsync` · write-ahead logs · torn writes and checksums · log compaction and snapshots · fork and copy-on-write · TTL algorithms · eviction (LRU, LFU, sampling) · async replication · replication offsets · read-your-writes · split brain · hash partitioning · consistent hashing · online rebalancing · hot keys.

## Scope

| In | Out |
|---|---|
| RESP2 protocol, strings, TTL, persistence, replication, sharding | Full command set, Lua, modules, ACLs |
| Benchmarks vs real Redis | Production-grade security |

**Tech:** Go or Java (raw sockets / NIO). Docker for real Redis as the baseline. `redis-cli`, `redis-benchmark`, Toxiproxy (L4).

**Name it.** Every clone deserves a name.

---

## Base system spec

### Functional requirements (L0)
- TCP server on port `6380` (real Redis on `6379` for side-by-side comparison).
- Speaks **RESP2**, so `redis-cli -p 6380` works.
- Commands: `PING`, `ECHO`, `SET key value`, `GET`, `DEL`, `EXISTS`, `INCR`, `COMMAND` (enough for `redis-cli` to be happy).
- **Pipelining** works: multiple commands in one write get responses in order.

### Non-functional targets (final)
- 10,000 concurrent connections.
- Throughput within a factor you choose of real Redis on `redis-benchmark -t set,get`. Write the target down in L0 and revisit it in L1.
- No acknowledged write lost under `kill -9` with the strictest durability setting.

### Benchmark table: keep in `docs/results.md`

| Level | Config | Clients (`-c`) | Pipeline (`-P`) | SET ops/s | GET ops/s | p99 | vs real Redis |
|---|---|---|---|---|---|---|---|

Run every config against **real Redis too**, on the same machine, with the same flags.

---

## Level 0: Speak Redis

**Incident:** None. Get `redis-cli` and `redis-benchmark` talking to your server.

**Done when:**
- [ ] `redis-cli -p 6380` can SET/GET/DEL/INCR.
- [ ] `redis-benchmark -p 6380 -t set,get -n 100000` completes.
- [ ] `redis-benchmark ... -P 16` (pipelining) completes with correct results.
- [ ] **Fuzz test**: you feed the same command stream to your parser split at random byte boundaries, 10,000 times, and always get identical results.

**Predict first:** What fraction of real Redis's throughput will your first version get?

**Design questions:**
1. How does RESP mark where one message ends and the next begins? Write a frame for `SET foo bar` by hand.
2. TCP gives you a **byte stream**, not messages. What does your parser do when a command arrives split across two reads? When two commands arrive in one read?
3. A client sends a bulk string header claiming 1 GB. What does your server do? What *should* it do?
4. A client connects and sends nothing for an hour. Then 10,000 do. What does that cost you?
5. Where does `INCR` on a non-integer value fail, and what does real Redis reply?

**Search:** `RESP protocol specification`, `TCP stream framing`, `length-prefixed framing`, `Redis pipelining`, `redis-benchmark options`.

**Self-evaluation:**
- 🟢 The fuzz test passes, and you can explain why it exists.
- 🔴 Your parser assumes one `read()` returns one command.

<details><summary>Hint (fuzz test design)</summary>
Generate a long valid byte stream once. Feed it to the parser in random-sized chunks. Compare the list of parsed commands to the list from feeding it all at once.
</details>

---

## Level 1: 10,000 connections

**Incident:** `redis-benchmark -c 10000`. Your server falls over, slows to a crawl, or runs out of memory, and real Redis handles it with one thread.

**Done when:**
- [ ] 10,000 concurrent clients served. Throughput and p99 recorded.
- [ ] You implemented and benchmarked **at least 2** concurrency models:
  - thread-per-connection + one global lock
  - thread-per-connection + sharded locks
  - a single-threaded event loop (non-blocking I/O + readiness notification)
- [ ] A **profile / flame graph** for your best version shows where CPU goes.

**Predict first:** Which model wins at `-c 50`? At `-c 10000`? With `-P 16`?

**Design questions:**
1. How much memory does one OS thread cost? One goroutine / virtual thread? Do the math for 10k.
2. What was the C10K problem, and what OS feature solved it? On macOS it's **kqueue**. On Linux (Docker) it's **epoll**. What's the difference between "readiness" and "completion" models?
3. Why can Redis run commands on a **single thread** and still be fast? What *kind* of command breaks that model? Try `KEYS *` with 1M keys on real Redis while benchmarking.
4. In Go, goroutine-per-connection already uses an event loop underneath. How? Does that mean your "thread-per-connection" Go version is really an event loop? (Java devs: same question for virtual threads.)
5. Where is your bottleneck now: syscalls, allocation, locking, or parsing? Prove it with the profiler, not intuition.

**Search:** `C10K problem kegel`, `epoll vs kqueue`, `reactor pattern`, `Redis ae event loop`, `Go netpoller`, `Java NIO Selector`, `virtual threads pinning`, `io_uring`, `flame graph`.

**Self-evaluation:**
- 🟢 Your model comparison table matches your predictions, or you know exactly why it doesn't.
- 🟢 You can explain one optimization you made *because the profiler told you*.
- 🔴 Your benchmark client and server fight over the same CPU cores, and you didn't note it.

**Stretch:** Write the event loop on raw `kqueue`/`epoll` syscalls, without your language's runtime doing it for you.

---

## Level 2: `kill -9`

**Incident:** You restart the server and everything is gone. Product wants "Redis, but it doesn't lose my data."

**Done when:**
- [ ] Append-only log (AOF) of write commands, replayed on startup.
- [ ] Configurable fsync policy: `always`, `everysec`, `no`. Throughput measured for each.
- [ ] **Crash test harness**: writes keys, records which writes were **acknowledged**, `kill -9`s at a random time, restarts, and verifies. Under `always`, every acked write must be there. Under `everysec`, you **measure** the max loss window.
- [ ] A torn (half-written) last record is detected and handled on restart.
- [ ] AOF **rewrite/compaction** runs while writes continue, without blocking them for long.
- [ ] (Optional) Point-in-time snapshot. Compare recovery time with AOF.

**Predict first:** Throughput with `fsync=always` as a percentage of `fsync=no`.

**Design questions:**
1. What does `write()` returning success actually guarantee? Where is the data at that moment? What does `fsync()` add?
2. Measure one `fsync` on your laptop SSD. Then explain why `always` costs what it costs. Is there a way to fsync **many** writes at once without breaking the guarantee?
3. Crash mid-append: the last record is half on disk. How do you **detect** it? What do you do with it?
4. The AOF grows forever (`INCR counter` 1M times = 1M lines). How do you compact it **while writes keep coming**? What happens to writes that arrive during compaction?
5. Real Redis uses `fork()` for background saves. Why fork? What's copy-on-write, and what does it cost on a write-heavy workload with a large dataset?
6. Snapshot vs log: compare file size, recovery time, data-loss window, and CPU cost.
7. Does macOS `fsync` actually flush to the physical disk? (Look up `F_FULLFSYNC`. Surprising answer.)

**Search:** `fsync durability`, `write-ahead log`, `group commit`, `torn write`, `CRC32 record checksum`, `Redis AOF rewrite`, `fork copy-on-write`, `F_FULLFSYNC macOS`, `Redis persistence`.

**Self-evaluation:**
- 🟢 The crash harness runs 100 times with 0 lost acked writes under `always`.
- 🟢 You can state your `everysec` loss window from **measurement**, not the docs.
- 🔴 You test durability by stopping the server gracefully.

<details><summary>Hint (crash harness)</summary>
The client must log a write as acked only after it receives the reply. Kill the server from a separate process at a random moment, not from inside the server.
</details>

---

## Level 3: Expiry and eviction

**Incident:** A session cache stores 1M keys with a 60s TTL. Memory grows forever, because expired keys that nobody reads again are never deleted. Then the box runs out of memory.

**Done when:**
- [ ] `EXPIRE`, `TTL`, `PERSIST`, `SET key value EX n` work like Redis.
- [ ] Expired keys are reclaimed **even if never read again**. A memory graph over 10 minutes of churn stays flat.
- [ ] `maxmemory` + at least 3 eviction policies (`noeviction`, `allkeys-lru`, `volatile-ttl`, `allkeys-lfu`...).
- [ ] A **hit-ratio experiment** under a Zipfian (skewed) workload compares exact LRU, your approximate LRU, and random eviction. Chart the results.

**Predict first:** How much worse will sampled/approximate LRU be than exact LRU on hit ratio? 1%? 10%?

**Design questions:**
1. How do you find expired keys **without scanning everything**? Compare a min-heap / sorted structure, sampling, and a timing wheel. What does each cost per write and per tick?
2. How does Redis decide how much expiry work to do per cycle, and when to stop? Why not just delete all expired keys every tick?
3. What does exact LRU cost in memory per key and CPU per access? Why does Redis approximate it?
4. When is LFU better than LRU? Build a workload where LRU loses badly.
5. How do you measure your process's memory accurately? What's the overhead per key versus the bytes of actual data?
6. In replication (L4), who decides a key expired: the leader, or each replica on its own? What goes wrong if each decides independently?

**Search:** `Redis expire algorithm active expiry`, `Redis approximated LRU`, `LFU Morris counter`, `hierarchical timing wheel`, `CLOCK page replacement`, `Zipf distribution cache benchmark`, `W-TinyLFU`.

**Self-evaluation:**
- 🟢 You have a memory-over-time graph that's flat, and a hit-ratio chart.
- 🔴 Expiry runs as one big scan that pauses the server.

---

## Level 4: Replication

**Incident:** The single node is a single point of failure, and read traffic is more than one node can serve.

**Done when:**
- [ ] `REPLICAOF host port` makes a node a follower. It gets a **full sync** first, then a **stream** of writes.
- [ ] Each side tracks a **replication offset**. `INFO replication` shows lag.
- [ ] A follower disconnected for 5s **partially resyncs**. One disconnected for 5 minutes does a full resync. You can explain the threshold.
- [ ] You **demonstrated a stale read** from a follower on purpose, and offered a way for clients to avoid it.
- [ ] Tested with **Toxiproxy** between leader and follower: latency, bandwidth limit, partition.

**Predict first:** Under 50k writes/s, what will follower lag be in milliseconds? In bytes?

**Design questions:**
1. Async replication: when the client gets `OK`, what has actually happened on the followers? What can be lost if the leader dies now?
2. How does a follower get a consistent starting copy while the leader keeps accepting writes?
3. After a short disconnect, how does the follower resume without a full copy? What buffer makes that possible, and how big should it be?
4. Which anomalies can a client see when reading from followers: read-your-writes, monotonic reads, consistent prefix? Build a test that shows one.
5. How can a client get read-your-writes if it wants to? (Look at what Redis's `WAIT` does, and what it does **not** guarantee.)
6. The leader dies. Who promotes a follower? What if the old leader comes back and still thinks it's the leader? What's split brain, and how do real systems prevent it?

**Search:** `Redis replication PSYNC`, `replication backlog`, `replication offset`, `read-your-writes consistency`, `monotonic reads`, `Redis WAIT command`, `split brain`, `Redis Sentinel`, `fencing token`, `Toxiproxy toxics`.

**Self-evaluation:**
- 🟢 Toxiproxy tests show partial vs full resync behaving exactly as you predicted.
- 🟢 You can explain why "leader + async followers" can lose acknowledged writes on failover.
- 🔴 Your failover is "promote whoever is up" with no plan for the old leader.

---

## Level 5: Sharding

**Incident:** The dataset is 3× the RAM of one machine.

**Done when:**
- [ ] 3 nodes. Every key maps deterministically to exactly one node.
- [ ] Routing works. Pick one: smart client, proxy, or redirect (`MOVED`). Justify the choice.
- [ ] **Add a 4th node online** under load. Data migrates with **zero lost writes**. You measured the fraction of keys moved.
- [ ] Multi-key commands on keys from different shards return a clear error, and you support a way to colocate keys that must be together.
- [ ] Load distribution histogram across nodes.

**Predict first:** With `hash(key) % N`, what fraction of keys move when N goes from 3 to 4? With consistent hashing?

**Design questions:**
1. Compare `% N`, consistent hashing (with virtual nodes), fixed hash slots, rendezvous hashing, and jump hash. What does each move when you add a node?
2. **During migration**, a key is half-moved. Where does a read go? A write? Look at how Redis Cluster uses `ASK` vs `MOVED`.
3. How does a client learn the cluster layout? What happens when its copy is stale?
4. One key gets 50% of all traffic (a celebrity's profile). Sharding doesn't help. What would?
5. How do you colocate related keys on one shard? (Hint word: hash tags.)

**Search:** `consistent hashing virtual nodes`, `Redis Cluster specification hash slots`, `MOVED ASK redirection`, `jump consistent hash`, `rendezvous hashing`, `hot key mitigation`, `Redis hash tags`.

**Self-evaluation:**
- 🟢 Measured fraction moved matches your math.
- 🟢 Migration under load: checker shows 0 lost writes.
- 🔴 Adding a node needs downtime.

---

## Stretch levels
- **Sorted sets** with a skiplist you write yourself: `ZADD`, `ZRANGE`, `ZRANK`. Good DSA practice.
- **Pub/Sub**: what happens to a slow subscriber? (Look up `client-output-buffer-limit`.)
- **Transactions**: `MULTI`/`EXEC`/`WATCH`. What isolation do they actually give?
- **Streams**: `XADD`/`XREAD`. This is a preview of project 06.

## Graduation: answer out loud
1. Why is Redis single-threaded, and when does that hurt?
2. Walk me through what happens between `SET` and the bytes being safe on disk.
3. AOF vs RDB: which would you pick for a cache, for a session store, and for a leaderboard?
4. How does Redis expire keys nobody reads?
5. Async replication: what can you lose on failover and why?
6. Consistent hashing vs hash slots: why did Redis Cluster pick slots?

## Taking it further
Real persistence engine (LSM tree) · RESP3 · cluster bus with gossip for failure detection · run real client libraries' test suites against it · write a blog series comparing your numbers with Redis.
