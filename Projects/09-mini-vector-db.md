# 09: Mini Vector DB

> Every "chat with your documents" (RAG) app has a vector search underneath. Here you build one from scratch, including the HNSW index that most vector databases use. It's also good DSA practice: graphs, heaps, k-means.

---

## The story

Your team is building an assistant that answers questions from company documents. The documents are split into **10 million chunks**. Each chunk is turned into a list of 768 numbers (an embedding). For every user question you must find the **10 most similar chunks in under 50 ms**.

Your first version compares the question to all 10 million chunks, one by one. It takes **seconds**.

## How it works (plain words)

- An **embedding** turns text into a point in a space with many dimensions. Texts with similar meaning land close together.
- **Search** means: given a question's point, find the nearest points.
- Checking every point is exact but slow. Vector databases find **almost** the nearest points, very fast. They trade a little accuracy for a lot of speed.
- The main measure of that accuracy is **recall**: out of the true 10 nearest, how many did you find?

## What you'll learn

Similarity metrics · brute-force search and memory layout · top-k with heaps · approximate nearest neighbor (ANN) indexes: HNSW and IVF · recall vs speed trade-offs · quantization (compressing vectors) · re-ranking · filtered search · query planning by selectivity · inserts, deletes, and index decay · immutable segments · sharding with scatter-gather · tail latency.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Vector / embedding** | A list of numbers representing meaning, e.g. 768 floats. |
| **Dimension (d)** | How many numbers are in each vector. |
| **Cosine similarity / dot product / L2 distance** | Three ways to measure how "close" two vectors are. |
| **kNN** | k-Nearest Neighbors: the exact k closest vectors. |
| **ANN** | Approximate Nearest Neighbors: close to the exact answer, much faster. |
| **Recall@10** | Of the true 10 nearest, the fraction you returned. 1.0 = perfect. |
| **Ground truth** | The true nearest neighbors, computed slowly by brute force, used to grade your index. |
| **HNSW** | A graph index: each vector links to nearby vectors, in several layers. Search walks the graph. |
| **IVF** | An index that groups vectors into clusters, then searches only the closest clusters. |
| **Quantization** | Storing vectors in fewer bits (for example 1 byte per number instead of 4) to save memory. |
| **QPS** | Queries per second. |

## Scope

| In | Out |
|---|---|
| Brute force, HNSW, IVF, quantization, filters, updates, sharding | GPUs, training embedding models |
| Benchmarks on public datasets with ground truth | A full SQL layer |

**Tech:** Java or Go (or C++/Rust if you want to go deep on speed). No vector-DB libraries for the core. Python only for making datasets and charts if you like.

**Datasets** (they come with ground truth): search `ann-benchmarks datasets`. Start with **SIFT 1M** (128 dimensions, Euclidean) and **GloVe-100** (angular / cosine). Later, make your own: embed ~1M Wikipedia sentences with a small local model (Ollama or sentence-transformers).

---

## Base system spec

### Functional requirements
1. Create a collection with a dimension and a distance metric.
2. Insert vectors with an ID and metadata (for example `{tenant: "a", year: 2025, acl: ["team-x"]}`).
3. Search: given a vector and `k`, return the k nearest IDs with scores. Optional metadata filter.
4. Delete by ID. (From L4.)

### API contract

```
POST   /collections                         {name, dim, metric: "l2"|"cosine"|"dot"}
POST   /collections/{c}/vectors             {id, vector: [...], metadata: {...}}   (batch allowed)
POST   /collections/{c}/search              {vector, k, filter?: {...}, ef?}  -> [{id, score}]
DELETE /collections/{c}/vectors/{id}                                              (L4)
```

### Benchmark harness (your referee)

- Loads a dataset and its ground truth.
- Runs all test queries and computes **recall@10**, **QPS**, **p50/p99 latency**, **memory**, and **build time**.
- Plots a **recall vs QPS curve** for each index setting. Look up how ann-benchmarks plots look. Yours should look similar.

> A number without its recall means nothing. "50k QPS" at recall 0.3 is useless.

### Results table: `docs/results.md`

| Level | Index | Params | Dataset | Recall@10 | QPS | p99 | Memory | Build time |
|---|---|---|---|---|---|---|---|---|

---

## Level 0: Brute force (the honest baseline)

**Incident:** None. Compare the query to every vector and return the top k.

**Done when:**
- [ ] Recall is 1.0. (It must be, since this is exact.)
- [ ] QPS and latency measured on SIFT 1M: single-threaded and using all cores.
- [ ] You estimated the latency at 10M vectors × 768 dimensions from your numbers.
- [ ] Top-k uses a structure better than "sort everything". Measure the difference.

**Predict first:** How many milliseconds for one query over 1M × 128 floats on your laptop?

**Design questions:**
1. Write the cost formula: N vectors × d dimensions = how many multiply-adds per query?
2. Cosine vs dot product vs L2: when do they give the **same ranking**? (Think about vectors with length 1.)
3. Top-k from 1M scores: sort everything, or something smarter? What's the cost of each?
4. **Memory layout:** one big contiguous float array vs an array of objects. Measure both. Why is there a difference?
5. Can you split one query across CPU cores? Or run many queries in parallel? Which gives more QPS?

**Search:** `cosine similarity vs dot product`, `vector normalization`, `top-k min-heap`, `cache-friendly memory layout`, `SIMD`, `Java Vector API` / `Go assembly SIMD` (stretch).

**Self-evaluation:**
- 🟢 Your measured time matches your cost formula within about 2×, and you know why.
- 🔴 Vectors are stored as a list of boxed objects.

---

## Level 1: Build HNSW yourself

**Incident:** 10M vectors. Brute force takes seconds per question. The product needs under 50 ms.

**Done when:**
- [ ] HNSW implemented from the paper (no library).
- [ ] **Recall vs QPS curve**, made by changing the search parameter (`ef`), compared against brute force.
- [ ] Measured effect of build parameters (`M`, `efConstruction`) on build time, memory, and recall.
- [ ] You hit **recall@10 ≥ 0.95** at a QPS target you set.

**Predict first:** For recall 0.95, how much faster than brute force will HNSW be on SIFT 1M? 10×? 100×?

**Design questions:**
1. What is a "navigable small world" graph? Why can greedy search (always move to the closest neighbor) find near points fast?
2. Greedy search can get **stuck** in a local minimum. Why? What does a larger `ef` do about it?
3. Why does HNSW have **layers**? Compare it to a skip list. What lives on the top layer?
4. When inserting a vector, which neighbors do you connect it to? The paper has a heuristic. Why not just the closest `M`?
5. Memory per vector for the graph links: compute it for your `M`.
6. Can searches run while inserts are happening? What can go wrong?

**Search:** `HNSW paper Malkov Yashunin`, `navigable small world graph`, `skip list`, `greedy graph search`, `ann-benchmarks`, `HNSW parameters M efConstruction efSearch`.

**Self-evaluation:**
- 🟢 Your curve looks like a real ann-benchmarks curve, and you can explain its shape.
- 🔴 You report QPS without recall.

<details><summary>Hint (where to start)</summary>
Build a <b>single-layer</b> graph first, with greedy search and an <code>ef</code> candidate list. Get recall working. Add layers after.
</details>

---

## Level 2: It doesn't fit in memory

**Incident:** 10M × 768 floats. Calculate it. It's bigger than your machine's memory, and the cloud bill for a big-memory machine is painful.

**Done when:**
- [ ] **Scalar quantization** (float32 → int8) implemented. Memory, recall, and QPS measured.
- [ ] **Product quantization (PQ)** or **IVF** implemented. Compare with HNSW on memory, build time, recall, and QPS.
- [ ] **Re-ranking:** search with compressed vectors, then re-score the top candidates with full vectors. Measure the recall recovered.
- [ ] A table showing memory per vector for each method.

**Predict first:** How much recall do you lose going from float32 to int8? How much do you get back with re-ranking?

**Design questions:**
1. Memory for 10M × 768 vectors: as float32, as int8, and as PQ with your settings.
2. Why does quantization lower recall? Why does re-ranking a few hundred candidates with full vectors fix most of it?
3. Where do the full vectors live if not in memory: on disk, memory-mapped? What does a disk read cost compared with a memory read?
4. **IVF:** cluster with k-means, then search only the nearest clusters (`nprobe`). How do you pick the number of clusters? What does `nprobe` trade?
5. HNSW vs IVF: which is easier to update? Which uses less memory? Which builds faster?

**Search:** `scalar quantization int8`, `product quantization`, `k-means clustering`, `IVF index`, `IVF-PQ`, `re-ranking`, `DiskANN`, `mmap`.

**Self-evaluation:**
- 🟢 You have one table comparing memory, recall, and QPS for every method.
- 🔴 Quantization tuned on the same queries you test with.

---

## Level 3: Filtered search

**Incident:** Two new rules. Users may only see documents they have access to, and they often filter by year. Your first try finds the top 10 and then removes the ones that don't match. With a filter that matches 1% of documents, it often returns **zero results**. Your second try (filter first, then brute force) is slow.

**Done when:**
- [ ] Metadata filters work: equality, a set of allowed values, and ranges.
- [ ] For filters that match **50%, 10%, 1%, and 0.1%** of documents, you return k results whenever k matching documents exist. Recall and latency measured for each.
- [ ] Your engine **chooses a strategy** based on how selective the filter is.
- [ ] **Access-control test:** a filtered search never returns a document the user can't see, under any setting. Zero tolerance.

**Predict first:** With post-filtering at 1% selectivity, how many of 10 results do you get on average?

**Design questions:**
1. Three strategies: filter **after** search, filter **before** search, filter **during** graph traversal. When does each one win?
2. Why does graph search struggle with very selective filters? (Picture the graph with 99% of nodes "turned off".)
3. How do you estimate how selective a filter is **before** running the search? This is the same idea as a database query planner.
4. What data structure lets you check "is this ID allowed?" quickly for millions of IDs?
5. Access control: what's the difference between a filter for relevance and a filter for **security**? What extra testing does security need?

**Search:** `filtered vector search`, `pre-filtering vs post-filtering`, `query planner selectivity estimation`, `bitmap index`, `roaring bitmaps`, `ACORN filtered HNSW`.

**Self-evaluation:**
- 🟢 You have a selectivity vs recall/latency chart for each strategy, and your planner picks well.
- 🔴 The access-control test is "it looked fine".

---

## Level 4: Updates and deletes

**Incident:** Documents are edited all day. After a week, recall has dropped from 0.95 to 0.85. Deleted documents still show up sometimes. A restart rebuilds the index from scratch and takes 20 minutes.

**Done when:**
- [ ] Inserts and deletes while serving searches.
- [ ] Deleted IDs **never** appear in results.
- [ ] Recall measured after 30% churn (deletes + inserts), compared with a fresh build. Then after your fix.
- [ ] The index is saved and loaded quickly. Restart time measured. Recent writes are safe in a write-ahead log.
- [ ] A background rebuild or merge restores recall without stopping searches.

**Predict first:** Recall after 30% churn with "mark as deleted, never repair"?

**Design questions:**
1. Can you remove a node from an HNSW graph safely? What happens to the neighbors that pointed to it?
2. Tombstones vs repairing links vs periodic rebuild: what does each cost?
3. **Segments:** small immutable indexes plus a background merge, like Lucene. How does a search work across segments? How do you merge results?
4. Read-your-writes: a document inserted 1 second ago. Must it be searchable? How would you make that true?
5. What exactly goes into the write-ahead log, and how do you replay it on startup?

**Search:** `HNSW deletion`, `tombstones`, `Lucene segments merge`, `near real-time search`, `write-ahead log`, `index rebuild`.

**Self-evaluation:**
- 🟢 You have a recall-over-time chart during churn: before and after your fix.
- 🔴 Deletes are filtered after search with no plan for the graph.

---

## Level 5: Sharding and scatter-gather

**Incident:** 100M vectors and 5,000 QPS. One machine isn't enough.

**Done when:**
- [ ] N shards. A query goes to all shards, each returns its top results, and a coordinator merges them.
- [ ] Results are identical to the single-machine version for the same data (recall unchanged).
- [ ] **p99 measured with 1, 4, and 8 shards.** Explain the trend.
- [ ] Replicas added for QPS. Routing between replicas works.
- [ ] One slow shard: you decided whether to wait, or to return partial results, and you tested it.

**Predict first:** If each shard's p99 is 20 ms, what's the p99 of a query that waits for 8 shards?

**Design questions:**
1. How do you split data: random, by tenant, or by cluster (similar vectors together)? What does each mean for queries?
2. Each shard returns how many results so that the merged global top-k is correct?
3. Why does the p99 of a fan-out query get **worse** as you add shards? Work it out with probability.
4. **Hedged requests:** send to a second replica if the first is slow. When does this help, and what does it cost?
5. Splitting by tenant: one huge tenant. Now what?

**Search:** `scatter-gather`, `The Tail at Scale`, `hedged requests`, `partial results`, `sharding by tenant`, `replica selection`.

**Self-evaluation:**
- 🟢 Your measured p99 vs shard count matches your probability math.
- 🔴 Each shard returns top-1 and you merge those.

---

## Stretch levels
- **Hybrid search:** keyword search (BM25) plus vector search, combined with reciprocal rank fusion. When does keyword search beat vectors?
- **Many small tenants:** 10,000 collections of 10k vectors each. Does one HNSW per tenant still make sense?
- **Plug it into project 08:** use it as the semantic cache's index.

## Graduation: answer out loud
1. Explain HNSW in 2 minutes, with a drawing.
2. What's recall, and why must every speed number include it?
3. How do you fit 10M × 768 vectors in a small memory budget?
4. How do you make filtered search correct and fast?
5. Why does tail latency grow with the number of shards?
6. When would you just use `pgvector` in Postgres instead of building or buying a vector DB?

## Taking it further
A real RAG app on top (your own docs or notes) · compare with pgvector, FAISS, and a hosted vector DB on the same dataset · disk-based index for 1B vectors · open-source it with a benchmark report.
