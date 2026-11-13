# 14: Mini CDN

> Caching sounds simple: keep a copy, serve the copy. At CDN scale it's a lot of hard problems: thousands of requests missing at once, purges that must reach every server in seconds, and an origin that has to survive its own popularity.

---

## The story

**PicPost** (fictional) is a photo and video site. All files live on one **origin** server in one region. Users are all over the world. Pages load slowly far from the origin, and on launch day the origin **falls over**.

You build a CDN: a set of **edge** servers close to users that keep copies of popular files.

## How it works (plain words)

- Users connect to the **nearest edge** server instead of the origin.
- If the edge has a fresh copy (a **hit**), it serves it right away.
- If not (a **miss**), it fetches the file from the origin, or from a middle layer called a **shield**, keeps a copy, and serves it.
- HTTP headers from the origin tell the edge **how long** a copy stays fresh and **who** may see it.

## What you'll learn

HTTP caching rules · cache keys · hit ratio and popularity (Zipf) · eviction by size · request coalescing · cache stampedes · early expiration · tiered caching and origin shields · consistent hashing inside a cluster · hot objects · purge and invalidation · surrogate keys · serving stale content safely · conditional requests (ETag / 304) · range requests and chunked caching · routing users to edges.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Origin** | The real source of the files. |
| **Edge / PoP** | A cache server (or group of them) near users. PoP = Point of Presence. |
| **Hit / miss** | Found a fresh copy / didn't. |
| **Hit ratio** | Hits ÷ all requests. Byte hit ratio = bytes served from cache ÷ all bytes. |
| **TTL** | How long a copy stays fresh. |
| **Cache-Control** | The HTTP header that says how something may be cached (`max-age`, `no-store`, `private`...). |
| **ETag / 304** | A version tag for a file. The edge can ask "still version X?" and get "304 Not Modified" without downloading again. |
| **Purge** | Removing something from every cache, right now. |
| **Origin shield** | A middle cache layer between edges and origin, so the origin sees fewer requests. |
| **Stampede** | Many requests miss at the same moment and all go to the origin together. |
| **Range request** | Asking for only part of a file (bytes 1,000,000-2,000,000), like when you skip ahead in a video. |

## Scope

| In | Out |
|---|---|
| Edge cache servers, shield, purge system, origin simulator, multiple "regions" in Docker | Real global network, real DNS / anycast (concept only, in L6) |
| HTTP/1.1 caching semantics | TLS certificate management at scale |

**Tech:** Java or Go (your own HTTP caching proxy, not nginx or Varnish as the core; compare with them afterwards). Docker Compose, Toxiproxy for region latency, k6, Prometheus + Grafana.

---

## Base system spec

### Topology

```
users (k6, per region) ──▶ edge cluster (2-3 nodes) in region A ──┐
                       ──▶ edge cluster in region B ───────────────┼──▶ [shield, from L2] ──▶ origin
                       ──▶ edge cluster in region C ───────────────┘
            (Toxiproxy adds ~150 ms between each region and the origin)
```

### Origin simulator (build it first)
- A catalog of objects from 1 KB to 1 GB (images, CSS, video files).
- Sets headers per object type: `Cache-Control`, `ETag`, `Last-Modified`, and some `private` user-specific pages.
- Knobs: `LATENCY_MS`, `MAX_CONCURRENT` (above it, the origin slows down and then returns 503), `DOWN` (outage switch), and an admin API to **change** an object's content (so you can test staleness).
- It counts every request it receives. **Origin load** is your most important number.

### Load generator
- Popularity follows a **Zipf** distribution (a few objects are very popular, most are rarely requested). Make the skew adjustable.
- Each region has its own users. Add a "viral" mode: one object suddenly gets 50% of all traffic.

### Freshness and safety checker (your referee)

| ID | Invariant | From |
|---|---|---|
| C1 | No user gets content older than allowed (TTL + any stale window you allow) | L0 |
| C2 | No user ever gets **another user's** private content | L0 |
| C3 | After a purge, no edge serves the purged version after your deadline | L3 |
| C4 | Range responses have exactly the right bytes | L5 |

### Results table: `docs/results.md`

| Level | Scenario | Hit ratio | Byte hit ratio | Origin RPS | p50 | p99 (per region) | C1 | C2 | C3 | C4 |
|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: A caching proxy

**Incident:** None. Build one edge server that caches according to HTTP rules.

**Done when:**
- [ ] It respects `max-age`, `no-store`, `no-cache`, `private`, and `Vary`.
- [ ] Memory is limited by **total bytes**, with eviction.
- [ ] Hit ratio measured for 3 cache sizes and 2 Zipf skews. Chart it.
- [ ] C1 and C2 pass.

**Predict first:** With a cache that holds 1% of the catalog, what hit ratio do you get under typical Zipf skew?

**Design questions:**
1. What's your **cache key**? Just the URL? What about query strings, and headers like `Accept-Encoding`?
2. What does `Vary` mean, and how does it change your cache key?
3. `private` vs `public`: what must an edge never do with a private response?
4. Eviction by **count** vs by **bytes**: why does it matter when objects range from 1 KB to 1 GB?
5. Memory or disk? What goes where?

**Search:** `HTTP caching RFC 9111`, `Cache-Control directives`, `Vary header`, `Zipf distribution`, `size-aware cache eviction`, `byte hit ratio`.

**Self-evaluation:**
- 🟢 You have a hit-ratio chart, and C2 has a dedicated test with two users.
- 🔴 The cache key ignores `Vary`.

---

## Level 1: The stampede

**Incident:** The homepage hero image is the most popular file. Its TTL ends, and in that same second **5,000 requests miss** on one edge. All 5,000 go to the origin, and the origin falls over.

**Done when:**
- [ ] **Request coalescing:** for one object, only **one** fetch goes to the origin at a time, per edge. Other requests wait for it.
- [ ] Test: 5,000 concurrent requests for an expired object → origin sees **1** request per edge.
- [ ] If that one fetch fails or is slow, the waiting requests behave as you designed (error, retry, or stale). Tested.
- [ ] Popular objects are refreshed **before** they expire, to avoid the spike entirely. Measure the effect.

**Predict first:** Without coalescing, how many origin requests for one expiry of a hot object?

**Design questions:**
1. How do waiting requests "wait" for the one in-flight fetch? What data structure tracks it?
2. The fetch takes 10 seconds. Should waiters wait 10 seconds? What timeout makes sense?
3. A huge file (1 GB): do waiters wait for the **whole** download, or can they start receiving bytes as they arrive?
4. Why might **refreshing a little early, at a random moment**, prevent stampedes? How do you choose when?
5. Is coalescing safe for **private** content? Why or why not?

**Search:** `request coalescing`, `cache stampede`, `thundering herd`, `singleflight`, `probabilistic early expiration XFetch`, `dog-pile effect`.

**Self-evaluation:**
- 🟢 The stampede test shows 1 origin request per edge, and private content is handled safely.
- 🔴 Coalescing merges requests from different users for private pages.

---

## Level 2: Too many edges asking the origin

**Incident:** You now have 3 regions × 3 edge nodes. Each node misses on its own, so the origin sees up to **9×** the requests for each object. Inside one region, the same file is stored 3 times, which wastes cache space.

**Done when:**
- [ ] An **origin shield**: edges ask the shield, and only the shield asks the origin. Origin load measured before and after.
- [ ] **Consistent hashing** inside each edge cluster: each object is cached on one node per region (not all three). Hit ratio and latency measured.
- [ ] The shield dies: edges keep working (with more origin load), as you designed.
- [ ] **Hot object:** one viral file overloads the one node that owns it. You handle it.

**Predict first:** How much does the shield cut origin requests? How much latency does it add?

**Design questions:**
1. The shield adds one hop. When is that worth it?
2. The shield is a single point of failure. What happens when it's down, and how do you make it not a disaster?
3. Inside a cluster: every node caches everything, or each object lives on one node? Compare hit ratio, latency, and failure impact.
4. Consistent hashing again (project 02 L5): what happens when a node leaves the cluster?
5. A viral object makes one node hot. Copy hot objects to more nodes? How do you detect "hot"?

**Search:** `origin shield`, `tiered caching`, `cache hierarchy`, `consistent hashing`, `hot key replication`, `bounded-load consistent hashing`.

**Self-evaluation:**
- 🟢 You have a chart of origin RPS with no shield, with a shield, and with a shield plus consistent hashing.
- 🔴 One viral object takes down one edge node.

---

## Level 3: Purge it, now

**Incident:** Legal: "That image must be gone from the site **right now**." It has a 1-day TTL and is cached on every edge. Separately, a bad deploy pushed broken CSS, and every user will see the broken site for a day.

**Done when:**
- [ ] A purge API that works **by URL** and **by tag** (for example "all images of user 123").
- [ ] A purge reaches **all** edges within **5 seconds p99**. Measured.
- [ ] An edge that was **down** during the purge doesn't serve the purged content when it comes back (C3).
- [ ] You compared purging with **versioned URLs** (`style.v42.css`) and know when to use each.

**Predict first:** What's the hardest part of making C3 pass?

**Design questions:**
1. Push purges to edges, or have edges check for them? What does each cost? What does each guarantee?
2. Purge by tag: how does an edge know which cached objects have tag `user:123`?
3. An edge was offline for 10 minutes and missed 50 purges. How does it catch up? (Project 04 L5 resume, again.)
4. How do you **confirm** that a purge reached every edge?
5. Versioned URLs make purges almost unnecessary. Why? When can't you use them?

**Search:** `cache invalidation`, `surrogate keys`, `purge propagation`, `cache busting versioned URLs`, `purge log sequence numbers`, `"two hard things in computer science"`.

**Self-evaluation:**
- 🟢 The offline-edge test passes, and you have a purge latency histogram.
- 🔴 Purge is "best effort" with no confirmation.

---

## Level 4: The origin is down

**Incident:** The origin has a 20-minute outage. Your edges have copies of almost everything, but they're **expired**, so the edges return errors. The whole site is down even though the data is right there.

**Done when:**
- [ ] **Serve stale on error**, within a limit you set per content type.
- [ ] **Stale-while-revalidate:** serve the old copy immediately and refresh it in the background.
- [ ] **Conditional requests:** when revalidating, edges ask "still the same version?" and the origin can say `304` (no body). Bandwidth saved measured.
- [ ] Origin health tracked (circuit breaker, project 05). The edges stop hammering a dead origin.
- [ ] Availability during the outage measured. C1 still holds, with your stale rules.

**Predict first:** What percentage of requests can you serve during a 20-minute outage?

**Design questions:**
1. How stale is acceptable for images? For a news homepage? For a price?
2. What content must **never** be served stale?
3. Stale-while-revalidate: what does the user see, and when does the refresh happen? What if it fails?
4. How do ETags and `If-None-Match` work? How much bandwidth do they save for a big file that rarely changes?
5. When the origin comes back, how do you avoid every edge revalidating everything at once?

**Search:** `stale-while-revalidate`, `stale-if-error`, `conditional requests ETag If-None-Match 304`, `circuit breaker`, `revalidation storm`.

**Self-evaluation:**
- 🟢 You have an availability chart during an outage, with and without your fix.
- 🔴 A price page is served hours stale without anyone deciding that was OK.

---

## Level 5: Big files and video

**Incident:** Users watch 1 GB videos and skip to the middle. The edge downloads the **whole 1 GB** from the origin before sending the first byte. Time to first frame: 40 seconds. The cache also fills up with whole videos that people only watched 10 seconds of.

**Done when:**
- [ ] **Range requests** work (`Range: bytes=...`), and C4 passes.
- [ ] Files are cached in **fixed-size chunks**, each one its own cache entry.
- [ ] Time to first byte when seeking into the middle of an uncached video, measured.
- [ ] Byte hit ratio and cache efficiency (bytes stored vs bytes actually requested) measured.

**Predict first:** What chunk size would you pick, and why?

**Design questions:**
1. How do range requests work in HTTP? What must the response look like?
2. Chunk size: small vs large. What does each mean for origin requests, metadata, and wasted bytes?
3. A request covers 3 chunks: 2 cached and 1 not. How do you serve it?
4. Coalescing (L1) per chunk: does it still work?
5. Eviction: should chunks of the same video be evicted together, or one by one?

**Search:** `HTTP range requests`, `206 Partial Content`, `byte-range caching`, `nginx slice module`, `HLS video segments`, `chunked caching`.

**Self-evaluation:**
- 🟢 You have a seek-time chart before and after, and C4 passes on random ranges.
- 🔴 Any cache miss downloads the whole file.

---

## Level 6: Getting users to the right edge (concept + small demo)

**Incident:** Region B's edges go down. Users there get errors for **5 minutes**, even though regions A and C are healthy.

**Done when:**
- [ ] You can explain DNS-based routing and anycast, and their trade-offs.
- [ ] A small demo: a "router" (a simple DNS-like service or a client-side config) sends users to the nearest **healthy** region. Measure failover time after killing region B.
- [ ] You measured how DNS TTL affects failover time.

**Design questions:**
1. How does a user get sent to a nearby edge: DNS geo-routing, or anycast? How does each one work, at a high level?
2. Why does DNS TTL slow down failover? Can you set it to 0?
3. Anycast: what happens to an open TCP connection if the network route changes?
4. How do you check a region's health from the outside?

**Search:** `GeoDNS`, `anycast CDN`, `DNS TTL failover`, `health checks global load balancing`, `BGP anycast basics`.

---

## Graduation: answer out loud
1. Design a CDN for an image-heavy site with global users.
2. What's a cache stampede, and how do you stop it?
3. Why use an origin shield, and what's its risk?
4. How do you purge content from 1,000 edges within seconds, including edges that were offline?
5. When is serving stale content the right choice?
6. How do you cache video for users who skip around?

## Taking it further
An image resizing service at the edge (resize on first request, then cache) · signed URLs for private content · compare your edge with Varnish or nginx on the same scenarios · a dashboard per region · put your Live Canvas (project 04) snapshots behind it.
