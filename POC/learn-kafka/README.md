
## Setup Steps

1. **Start kafka**
    - `finch vm start`   # when you begin a session
    - `finch vm stop`    # when you're done for the day
    - `finch compose up -d`

2. **Create a topic**
    - `finch exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic orders --partitions 3`

3. **Stop**
    - `finch compose down`   # removes the Kafka container (topic data is lost)
    - `finch vm stop`




---

## 1. Why Kafka exists

Without Kafka, the Order service calls every other service directly:

```
WITHOUT KAFKA                          WITH KAFKA
                                                            ┌──► Email
Order ──► Email                        Order ──► [ KAFKA ] ──┼──► Inventory
      ──► Inventory                                         └──► Analytics
      ──► Analytics

Order must know every service,         Order writes once and moves on.
and waits for all of them.             Each service reads when it's ready.
If Email is down, the order fails.     If Email is down, it catches up later.
```

Kafka sits in the middle and **stores events safely**. This "decouples" services in three ways:
- **Time:** a reader can be offline and catch up later.
- **Speed:** a slow reader doesn't slow down the writer.
- **Knowledge:** the writer doesn't know or care who reads.

**Analogy:** Kafka is a **shared notebook**. Writers only add lines at the end. Each reader keeps their own **bookmark**. Reading a line doesn't erase it.

---

## 2. The big picture

```
 PRODUCERS                  KAFKA CLUSTER                     CONSUMERS
                     ┌───────────────────────────┐
 producer ─write──►  │ Broker 1  Broker 2  ...   │  ◄──read── group "order-service"
                     │     topic "orders"        │  ◄──read── group "analytics"
                     └───────────────────────────┘
```

**Consumers pull.** They ask Kafka for data, and Kafka never pushes. That way each consumer controls its own pace and can't be flooded.

---

## 3. Message (also called record or event)

| Field | In your code | Meaning |
|---|---|---|
| Key | `order-1` | Optional. Decides which partition the message goes to (section 6). |
| Value | `order 1 placed at ...` | The actual data. To Kafka it's just bytes; you pick the format (JSON, Protobuf, ...). |
| Timestamp | automatic | When it was produced. |
| Headers | none | Optional metadata, such as trace IDs. |
| Offset | set by Kafka | The message's position in its partition. The producer never sets it. |

---

## 4. Topic

A topic is a **named stream of messages**, like a table name: `orders`, `payments`, `user-signups`.

A topic is a **log**, meaning an append-only list:

```
┌────┬────┬────┬────┬────┬────┐
│ 0  │ 1  │ 2  │ 3  │ 4  │ 5  │ ◄── new messages are only added here
└────┴────┴────┴────┴────┴────┘
oldest                    newest
```

1. **Append-only:** you can't insert in the middle or edit.
2. **Immutable:** once written, a message never changes.
3. **Reading doesn't delete:** messages stay until **retention** expires (default 7 days). Many readers can read the same message, and can re-read it.

---

## 5. Partition

A single log would be limited to one machine's disk and CPU, and only one reader could read it at a time. So each topic is split into **partitions**, and each partition is its own independent log. You created `orders` with `--partitions 3`:

```
topic "orders"
  partition 0:  │ 0 │ 1 │ 2 │ 3 │          ◄── append
  partition 1:  │ 0 │ 1 │ 2 │              ◄── append
  partition 2:  │ 0 │ 1 │ 2 │ 3 │ 4 │      ◄── append
```

- **Each partition counts its own offsets from 0.** A message's full address is **(topic, partition, offset)**.
- **Order is guaranteed only within one partition**, not across the whole topic. That's why your consumer printed `order-5` before `order-1`: they were in different partitions.
- **Partitions are the unit of parallelism.** 3 partitions means up to 3 consumers in a group can read at the same time.
- Partitions are spread across brokers, so one topic can be bigger and faster than a single machine.
- You can **add** partitions later but never remove them. Adding changes which partition each key maps to, so plan some headroom up front.

---

## 6. Keys: which partition does a message go to?

The **producer** decides, using the **balancer** (called a "partitioner" in other clients).

```
partition = hash(key) % number_of_partitions

"order-1" ─hash─► partition 1
"order-3" ─hash─► partition 0
"order-1" ─hash─► partition 1    ← always the same
```

- **With a key:** the same key always goes to the same partition, so **all events for that key stay in order**. That's what `Balancer: &kafka.Hash{}` does.
- **Without a key:** kafka-go spreads messages round-robin, with no ordering guarantee.

**Choosing a key:** use the thing whose events must stay in order. For example, `orderID` keeps `created → paid → shipped` in order for each order.

**Watch out for hot partitions.** If one key is extremely common (say, a celebrity user), one partition gets most of the traffic. The single consumer reading it becomes the bottleneck.

---

## 7. Offset and lag

An offset is the **position number** of a message within its partition. Kafka assigns it, it always goes up, and it's never reused.

```
partition 1:  │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │
                          ▲               ▲
              committed offset = 3     log end = 7
              ("next one to read")

              LAG = 7 − 3 = 4 messages not read yet
```

**Consumer lag** is the most important number to monitor in production. If lag keeps growing, your consumers can't keep up.

---

## 8. Consumer groups

A **consumer group** is a team of consumers sharing the work, identified by `GroupID` (`"order-service"` in your code).

**Rules:**
1. Within a group, **each partition is read by exactly one consumer**. That keeps the order intact.
2. One consumer can own several partitions.
3. If there are more consumers than partitions, the extra ones **sit idle**.
4. **Different groups are completely independent.** Each group gets **every** message and keeps its own bookmarks.

```
topic "orders":  [P0]  [P1]  [P2]

group "order-service" (2 consumers)     group "analytics" (1 consumer)
   consumer A ◄── P0, P1                   consumer X ◄── P0, P1, P2
   consumer B ◄── P2

group with 4 consumers:
   A ◄── P0    B ◄── P1    C ◄── P2    D ◄── (nothing, idle)
```

This gives you both messaging patterns from one system:
- **Queue (share the work):** many consumers in **one** group. Each message is handled once by that group.
- **Pub/Sub (broadcast):** many **groups**. Every group gets every message.

---

## 9. Committing offsets (how Kafka remembers progress)

A consumer **commits** to tell Kafka "group `order-service` is done with partition 1 up to here." Kafka stores this in an internal topic called `__consumer_offsets`.

- Bookmarks belong to the **group**, not to the running process. If a consumer crashes and restarts, or another consumer takes over, it resumes from the group's bookmark.
- A **brand-new group** has no bookmark. `StartOffset` decides where it starts. kafka-go defaults to `FirstOffset` (the beginning). Java clients default to `latest` (only new messages).

---

## 10. Delivery guarantees, and correction #1

I checked the kafka-go source. `ReadMessage` **commits before it returns the message to you**. If your program crashes while handling that message, Kafka already thinks it's done, so the message is lost.

| Guarantee | Pattern | What can go wrong |
|---|---|---|
| **At-most-once** | commit, then process (`ReadMessage`) | Crash → message **lost** |
| **At-least-once** | process, then commit | Crash after processing but before commit → message processed **twice** |
| **Exactly-once** | Kafka transactions | Complex, and kafka-go's Reader/Writer don't support it |

Process-then-commit in kafka-go:
```go
m, err := r.FetchMessage(ctx)              // read, but don't commit yet
// handle err ...
process(m)                                  // do the work
if err := r.CommitMessages(ctx, m); err != nil { ... }  // now mark it done
```

Also change the comment on [consumer/main.go:29](Master-System-Design/POC/learn-kafka/cmd/consumer/main.go#L29) to `// blocks until a message arrives; commits it before returning`.

**What real systems usually do:** at-least-once plus an **idempotent consumer**, meaning handling the same message twice has no extra effect. A common way is a database unique key on `orderID`. This comes up a lot in system design interviews.

---

## 11. Rebalancing

When a consumer **joins or leaves** a group (start, crash, Ctrl+C), Kafka **rebalances**: it redistributes the partitions among the remaining consumers.

- Consumers send **heartbeats**. If Kafka hears nothing for the session timeout (kafka-go default: **30s**), it treats that consumer as dead and rebalances.
- A clean shutdown calls `r.Close()`, which your Ctrl+C handling does. It tells Kafka "I'm leaving" right away, instead of making it wait 30s.
- The group pauses briefly during a rebalance. Frequent rebalances are a common production problem.

---

## 12. Producer acks, and correction #2

How sure is the producer that a message was actually saved?

| acks | kafka-go setting | Meaning |
|---|---|---|
| 0 | `RequireNone` (**kafka-go's default**) | Don't wait for any reply. Fastest, but messages can be lost. |
| 1 | `RequireOne` | The leader has written it. |
| all | `RequireAll` | The leader **and** all in-sync copies have it. Safest. |

Right now your producer prints `sent:` **without Kafka confirming anything**. Add this to the Writer in [producer/main.go](Master-System-Design/POC/learn-kafka/cmd/producer/main.go#L14-L19):
```go
RequiredAcks: kafka.RequireAll,
```

---

## 13. Brokers, cluster, replication

- **Broker:** one Kafka server. It stores partitions on disk and handles reads and writes. Your container is 1 broker.
- **Cluster:** several brokers working together, usually 3 or more in production.
- **Replication factor (RF):** how many copies of each partition exist, each on a different broker.
  - **Leader:** the one copy that handles reads and writes.
  - **Followers:** keep copying from the leader.
  - **ISR (in-sync replicas):** the followers that are fully caught up. With `acks=all`, a write is confirmed only once every ISR member has it.
  - If the leader's broker dies, an in-sync follower **becomes the new leader**. No data is lost, and there's only a short pause.

```
               Broker 1        Broker 2        Broker 3
orders-P0     [LEADER]  ──►   [follower]      [follower]
orders-P1     [follower] ◄──  [LEADER]  ──►   [follower]
orders-P2     [follower]      [follower] ◄──  [LEADER]

Leaders are spread across brokers so no single broker does all the work.
```

A common production setup is **RF=3, `min.insync.replicas=2`, acks=all**. It survives one broker dying without losing data or blocking writes. Your local setup is RF=1, meaning no copies, which is fine for learning.

---

## 14. Controller and KRaft (who manages the cluster)

Something has to track which brokers are alive, which broker leads each partition, and each topic's settings. That's the **controller**.
- Older Kafka used a separate system, **ZooKeeper**.
- Kafka 4.x, including your 4.4.0, uses **KRaft**: Kafka manages its own metadata using the Raft consensus algorithm. ZooKeeper support was removed in 4.0.
- Your container runs the broker and controller in one process. That's why the compose file is so short.

---

## 15. Storage, and why Kafka is fast

- Each partition is a **folder on disk**. Its log is split into **segment files**, and new messages go into the newest segment.
- **Retention** deletes whole old segments by age (default 7 days, `retention.ms`) or by size (`retention.bytes`).
- **Log compaction** is an alternative to retention. It keeps only the **latest message per key**, which suits "current state" topics such as each user's latest profile.

**Why it's fast:**
1. **Sequential disk writes:** appending to the end of a file is very fast, with no random seeking.
2. **OS page cache:** recent data is served straight from memory.
3. **Batching:** many messages are sent per request. This is your `BatchTimeout`: waiting longer gives bigger batches and more throughput, but adds latency. kafka-go's default is 1s, which is why I set 10ms.
4. **Zero-copy:** the broker sends data from disk to the network without copying it through its own memory.
5. **Partitions:** you scale out by adding more partitions and more machines.

---

## 16. One message's full journey (your code)

```
 1. Producer builds Message{Key:"order-1", Value:"..."}
 2. Writer asks a broker for metadata: "orders has P0, P1, P2. Who leads each?"
 3. Balancer: hash("order-1") % 3 → P1
 4. Writer batches it (waits ≤10ms for more) and sends it to P1's leader
 5. Broker appends it to the end of P1's log → offset 0  (followers copy it if RF>1)
 6. Broker replies (depending on acks) → producer prints "sent: order-1"
 7. Consumer joined group "order-service" → group coordinator assigned it P0, P1, P2
 8. Consumer pulls: "P1, starting at offset 0" → receives the message
 9. Offset is committed: "order-service, P1 → next is 1" saved in __consumer_offsets
10. The message stays in the log until retention. Group "analytics" can still read it.
```

---

## 17. Your step 7 experiments, explained

1. **Restart the consumer, nothing prints:** the group's bookmark is already at the end (section 9). **Change `GroupID`:** it's a new group with no bookmark, so it starts at `FirstOffset` and reads everything.
2. **Two consumers, same group:** a rebalance splits the 3 partitions 2+1 (sections 8 and 11). Stop one, and the other takes over all 3.
3. **Same key, same partition:** section 6.
4. **Stop the consumer, produce, restart:** the messages are still in the log, and the bookmark says where to resume (sections 4 and 9).

**See it live:** this command shows each partition's committed offset, log end, lag, and which consumer owns it:
```bash
finch exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group order-service
```

---

## 18. Kafka vs a traditional queue (SQS, RabbitMQ)

| | Kafka | Queue |
|---|---|---|
| After a message is read | It stays (until retention) | It's deleted after ack |
| Re-read old messages | Yes, by resetting the offset | No |
| Several independent readers | Yes, one consumer group each | Needs one queue per reader |
| Ordering | Per partition | Usually none (SQS FIFO: per message group) |
| Tracking progress | One offset per partition (cheap) | An ack for every message |
| Best for | Event streams, high throughput, replay, data pipelines | Task queues, per-message retries and delays |

---

## 19. Glossary (for your README)

| Term | One line |
|---|---|
| Producer | Writes messages to a topic |
| Consumer | Reads messages from a topic |
| Broker | One Kafka server |
| Cluster | A group of brokers |
| Topic | A named stream of messages |
| Partition | One ordered, append-only slice of a topic |
| Offset | A message's position number in its partition |
| Key | Decides the partition; same key means same partition and kept order |
| Consumer group | Consumers sharing the work; each partition goes to one member |
| Commit | Saving the group's bookmark |
| Lag | How far behind a consumer is |
| Rebalance | Redistributing partitions when group members change |
| Replication factor | Number of copies of each partition |
| Leader / follower | The copy that serves traffic / the copies that replicate it |
| ISR | Followers that are fully caught up |
| acks | How much confirmation the producer waits for |
| Retention | How long messages are kept |
| Compaction | Keep only the latest message per key |
| KRaft | Kafka's built-in cluster management (replaced ZooKeeper) |

**Diagrams worth drawing in `design.excalidraw`:**
1. Without vs with Kafka (section 1)
2. A topic split into 3 partitions with offsets (section 5)
3. Keys hashing to partitions (section 6)
4. Two consumer groups reading the same topic (section 8)
5. Offset, log end, and lag (section 7)
6. Replication across 3 brokers (section 13)
7. The 10-step message journey (section 16)


---

# How Kafka is distributed

## 1. Two roles: broker and controller

| Role | Job | Analogy |
|---|---|---|
| **Broker** | Stores partitions on disk; serves producers and consumers | Warehouse workers |
| **Controller** | Keeps the cluster's metadata: which brokers are alive, who leads each partition, ISR, configs. Makes decisions. | Warehouse manager |

In Kafka 4.x (KRaft), each node is configured with `process.roles=broker`, `controller`, or both. Your container does both ("combined mode"). That's fine for learning, but production keeps them separate.

## 2. How many machines?

| | Typical count | Why |
|---|---|---|
| **Controllers** | 3 (or 5) | They vote using Raft, which needs a **majority**. 3 survives 1 failure; 5 survives 2. Always an odd number. |
| **Brokers** | At least 3, growing to dozens or hundreds | A replication factor of 3 needs at least 3 brokers. Add more for disk space or throughput. |

- Each broker usually runs on **its own machine** (VM, EC2, or physical) with its own disks.
- Spread them across **racks or AZs** by setting `broker.rack=us-east-1a` (and so on). Kafka then puts each partition's 3 copies in 3 different AZs, so losing a whole AZ still leaves 2 copies.
- On AWS, **Amazon MSK** runs all of this for you.

```
AZ-a: broker 1 + controller 1
AZ-b: broker 2 + controller 2
AZ-c: broker 3 + controller 3
```

## 3. How a client finds the right broker

1. You give the client a few brokers as `bootstrap.servers`. Any brokers work.
2. The client asks one of them for **metadata**: "For each partition, who is the leader?" Every broker has a copy of the metadata, so any of them can answer.
3. The client then **connects directly to each leader**. There's no load balancer in front of Kafka.
4. If a leader moves, the client gets a `NOT_LEADER_OR_FOLLOWER` error, refreshes its metadata, and retries. kafka-go does this automatically.

This is why `advertised.listeners` matters: it's the address brokers tell clients to connect to. In your setup it's `localhost:9092`.

## 4. Where the copies go (automatic, but configurable)

When you create a topic with replication factor (RF) 3, the controller picks 3 brokers for each partition. It spreads them evenly, and across racks if `broker.rack` is set. **The first broker in each list is the "preferred leader"**, and the lists are rotated so leadership is spread out:

```
P0 → [1, 2, 3]    preferred leader = broker 1
P1 → [2, 3, 1]    preferred leader = broker 2
P2 → [3, 1, 2]    preferred leader = broker 3
```
This is what diagram 6A shows. You can control it with `--replication-factor`, `broker.rack`, or by listing every placement yourself with `--replica-assignment`.

## 5. How leader and followers stay in sync

- **Followers pull from the leader**, the same way a consumer does, and append what they get to their own copy of the log.
- **ISR (in-sync replicas)** is the leader plus every follower that's caught up. A follower that hasn't caught up within `replica.lag.time.max.ms` (default 30s) is **removed from the ISR**, and re-added once it catches up.
- **High watermark (HW):** consumers can only read messages that **every ISR copy has**.

```
leader     : 0 1 2 3 4 5
follower 2 : 0 1 2 3 4
follower 3 : 0 1 2 3
                     ▲ high watermark: consumers can read offsets 0–3 only
```
Why: a message that exists only on the leader could disappear if the leader crashes. Hiding it until it's copied means a consumer never reads a message that later vanishes.

- With `acks=all` and `min.insync.replicas=2`, the producer gets "OK" only after every ISR copy has the message. If the ISR shrinks below 2, Kafka **rejects writes** (`NotEnoughReplicas`) rather than keep data with too few copies.

## 6. Leader election (automatic)

Suppose broker 1, the leader of P0, crashes:

1. Broker 1 stops sending heartbeats to the active controller. After `broker.session.timeout.ms` (default 9s), the controller marks it **dead**. A clean shutdown is faster: the broker tells the controller and hands over leadership first.
2. For every partition broker 1 led, the controller picks **the first broker in the list that is alive and in the ISR**. P0's list is `[1, 2, 3]`, so **broker 2** becomes leader.
3. The controller bumps the **leader epoch**, a version number for leadership. That stops an old leader that comes back confused from accepting writes. The change goes out to every broker.
4. Broker 2 serves P0, and broker 3 now copies from broker 2.
5. Clients get `NOT_LEADER` errors, refresh metadata, and carry on. The whole thing usually takes a few seconds.
6. When broker 1 restarts, it **deletes any messages above the high watermark**. They were never confirmed, so no consumer ever saw them. It then catches up from broker 2 and rejoins the ISR as a follower.
7. Broker 1 is P0's preferred leader, so Kafka **moves leadership back** automatically to keep the load balanced. This is `auto.leader.rebalance.enable=true`, checked every 5 minutes.

**What if no ISR copy is alive?** You choose the trade-off with `unclean.leader.election.enable`:
- `false` (default): the partition stays **offline** until an ISR copy returns. No data is lost, but the partition is unavailable.
- `true`: an out-of-date copy becomes leader. The partition stays **available**, but messages that copy never received are **lost**.

This is the CAP trade-off: consistency vs availability.

**The controllers elect their own leader too.** They use Raft: one active controller, and the others replicate its metadata. If it dies, the rest vote, and the candidate with a majority (2 of 3) wins. Reads and writes keep flowing in the meantime; only metadata changes pause.

### What's automatic, and what you can control

| Decision | Made by | You control it with |
|---|---|---|
| Which brokers hold each partition's copies | Controller, when the topic is created | RF, `broker.rack`, `--replica-assignment`, reassigning later |
| Leader when everything is healthy | The preferred (first-listed) broker | List order, `auto.leader.rebalance.enable`, `kafka-leader-election.sh` |
| New leader after a failure | Controller, automatically, from the ISR | `unclean.leader.election.enable` |
| Who is in the ISR | Leader, automatically | `replica.lag.time.max.ms` |
| How safe a write is | Producer + broker | `acks`, `min.insync.replicas` |
| Active controller | Raft vote, automatically | Number of controllers (3 or 5) |

## 7. Failure scenarios (RF=3, min.insync.replicas=2, acks=all)

| What fails | What happens | Effect |
|---|---|---|
| 1 follower | Dropped from the ISR (2 copies left) | None; writes continue |
| 1 leader | An ISR follower is promoted | A few seconds of retries, no data loss |
| 2 of a partition's 3 copies | ISR = 1, below the minimum of 2 | **Writes rejected**, reads still work (safety first) |
| All 3 copies | Partition offline | Wait for recovery, or allow unclean election and lose data |
| Active controller | Another one is elected | Metadata changes pause briefly; traffic continues |
| 2 of 3 controllers | No majority | **No leader elections or metadata changes** until one returns. This is why big clusters use 5. |
| A whole AZ (rack-aware setup) | Each partition loses at most 1 copy | Same as "1 broker down" |

The number to watch in production is **under-replicated partitions** (partitions with fewer in-sync copies than they should have). It should be 0.

## 8. Adding and removing brokers

**Adding a broker:**
1. Start a new machine with a unique `node.id`, pointed at the controllers.
2. It joins right away, **but it holds nothing yet**. Existing partitions don't move by themselves.
3. Move some partitions onto it with `kafka-reassign-partitions.sh` (generate a plan, execute it, verify). Kafka adds a copy on the new broker, waits until it catches up and joins the ISR, then removes an old copy. Clients don't notice. Use `--throttle` so the copying doesn't slow down real traffic.
4. **Cruise Control**, an open-source tool from LinkedIn, automates this rebalancing.

**Removing a broker:**
1. Move all its partitions to other brokers first. Otherwise every partition on it loses a copy.
2. Stop it normally. This "controlled shutdown" moves its leadership away before it stops, so clients barely notice.
3. Remove it from the cluster metadata with `kafka-cluster.sh unregister --id N`.

**Rolling restart** (for upgrades or config changes): restart one broker at a time, and wait for under-replicated partitions to return to 0 before restarting the next.
