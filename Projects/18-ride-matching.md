# 18: Ride Matching (Nearby Drivers)

> Live location data is a firehose of writes that are only useful for a few seconds. Matching is a race: two riders, one driver, decided in milliseconds. This project mixes geospatial indexing, high write rates, hot spots, and graph algorithms (good DSA practice: Dijkstra, A*, assignment).

---

## The story

You're building dispatch for a ride app in a big city. **500,000 drivers** are online at peak, and each sends its GPS location **every 4 seconds**. A rider taps "Book", and within **2 seconds** you must find a good nearby driver, offer the ride, and confirm it. A driver must **never** be given two riders at once.

Then real life happens: a concert ends and 20,000 people want rides from one small area, the nearest driver by straight line is across a river, and prices must rise when demand outruns supply.

## How it works (plain words)

- Keep a **live map** of where available drivers are: in memory, indexed by **area** (cells on a grid).
- When a ride request comes in, look at the rider's cell and the cells around it. Collect candidate drivers and rank them by how quickly they can get there (ETA).
- **Offer** the ride to the best driver, and lock that driver while waiting. If they accept, it's a match. If they decline or don't answer in time, offer it to the next one.
- Locations are **temporary**: a location older than ~30 seconds is useless.

## What you'll learn

Geohash, quadtrees, S2 and H3 · nearest-neighbor search on a map · cell boundary problems · high-rate ephemeral writes · sharding by geography · hot cells · moving objects across shards · locks with timeouts · offer/accept races · shortest paths on road graphs (Dijkstra, A*) · batch matching (assignment problem) · stream aggregation with time windows · surge pricing · smoothing.

## Words you'll meet

| Word | Simple meaning |
|---|---|
| **Geohash** | A way to turn (lat, lng) into a short string. Nearby places usually share the same prefix. |
| **Quadtree** | A map split into 4 squares, and each square split again where there are many points. |
| **S2 / H3** | Libraries from Google (S2, squares on a sphere) and Uber (H3, hexagons) that divide the Earth into cells. |
| **Cell** | One area on the grid. |
| **kNN** | Find the k nearest points. |
| **Haversine** | A formula for the distance between two points on the Earth. |
| **ETA** | Estimated time of arrival: how long the driver takes to reach the rider by road. |
| **Offer** | Asking one driver "do you want this ride?", with a timeout. |
| **Surge multiplier** | A price boost in an area where demand is much higher than supply. |
| **Hot cell** | A cell with far more activity than others (a stadium after a concert). |

## Scope

| In | Out |
|---|---|
| Location ingest, geo index, dispatch, offers, sharding, ETA, surge | Payments, maps UI (a simple map is a stretch), real GPS devices |
| A **city simulator** with driver bots and rider bots | Routing turn-by-turn navigation |

**Tech:** Java or Go. Redis (try its GEO commands as a comparison in L1). Your own geohash/quadtree, plus S2 or H3 as a library for comparison. OpenStreetMap data for one city (L4). Prometheus + Grafana. A WebSocket for driver offers.

---

## Base system spec

### Functional requirements
1. Drivers go online/offline and send their location every 4 seconds.
2. A rider requests a ride (pickup, drop-off). The system finds a driver, sends an **offer**, and handles accept, decline, and timeout.
3. Both rider and driver can see the ride status.
4. A driver on a ride isn't available for new rides.

### API contract

```
POST /drivers/{id}/location   {lat, lng, ts}
POST /drivers/{id}/status     {status: "available"|"offline"}
WS   /drivers/{id}/offers     <- {offerId, rideId, pickup, expiresAt}
POST /offers/{offerId}/accept | /decline
POST /rides                   {riderId, pickup: {lat,lng}, dropoff: {lat,lng}}  -> {rideId}
GET  /rides/{rideId}          -> {status, driverId?, eta?, surge?}
```

### City simulator (build it first)
- A city bounding box (pick a real city you know).
- **Driver bots** move around (random walk first; along real roads in L4), send locations, and accept offers with some probability after a random delay.
- **Rider bots** request rides, with **hot spots**: airport, downtown at 6pm, and a **stadium that empties at 10pm** (L3).
- Trips last a few minutes, then the driver becomes available again.
- Knobs: number of drivers, request rate, hot-spot strength, accept probability, network delay.

### Dispatch checker (your referee)

| ID | Invariant | From |
|---|---|---|
| D1 | No driver is ever assigned to two active rides at the same time | L0 |
| D2 | No rider is assigned two drivers | L0 |
| D3 | Every request is matched or clearly failed within T seconds | L0 |
| D4 | The matched driver was within X km of the pickup, using a location less than 30s old | L1 |
| D5 | No driver gets a new offer while another offer to them is still open | L2 |

### Results table: `docs/results.md`

| Level | Scenario | Drivers | Location updates/s | Match p50 | Match p99 | Match rate | Avg pickup ETA | D1 | D2 | D3 | D4 | D5 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|

---

## Level 0: The naive way

**Incident:** None. Store all driver locations in a table. For each request, compute the distance to **every** driver and pick the closest.

**Done when:**
- [ ] Works end to end with 1,000 drivers. D1-D3 pass.
- [ ] Match latency measured at 1k, 10k, 100k, and 500k drivers.
- [ ] You calculated the location update rate at 500k drivers.

**Predict first:** At 500k drivers, how long does "check every driver" take? And how many location writes per second must you handle?

**Design questions:**
1. 500,000 drivers ÷ 4 seconds = how many writes per second? Which databases can handle that, and do you even need a database for this?
2. Do you need to **keep** every location update, or just the latest one? (History is a separate problem: see stretch.)
3. Haversine vs simple flat (x, y) distance: when is the flat version good enough inside a city?
4. How do you stop D1 (two riders, one driver) in the simplest possible way, before scaling anything?

**Search:** `haversine formula`, `equirectangular approximation distance`, `ephemeral data storage`, `write throughput in-memory store`.

**Self-evaluation:**
- 🟢 You have a latency-vs-drivers chart, and the write-rate math.
- 🔴 Every location update is written to Postgres with fsync.

---

## Level 1: The geo index

**Incident:** At 500k drivers, a match takes **2 seconds** just to find candidates.

**Done when:**
- [ ] **Geohash (or quadtree) written yourself.** Plus one library (S2 or H3) and Redis GEO for comparison.
- [ ] "Find the 10 nearest **available** drivers" in **under 10 ms p99** at 500k drivers.
- [ ] **Boundary test:** a rider standing right at the edge of a cell, with the nearest driver just across the edge in the next cell. It's found, 100/100 times.
- [ ] Index update cost measured: each location update moves a driver, possibly to a new cell.
- [ ] D4 passes.

**Predict first:** Which approach will be fastest for kNN? Which will be fastest for **updates**?

**Design questions:**
1. How does geohash turn (lat, lng) into a string? Why do nearby places usually share a prefix, and when do they **not**?
2. The **boundary problem**: why can the nearest driver be in a cell with a completely different prefix? How do you fix it?
3. How big should cells be? What happens with tiny cells in the suburbs, or huge cells downtown?
4. Quadtrees adapt to density. When is that better than a fixed grid?
5. Why do some systems use hexagons (H3)? What's special about a hexagon's neighbors?
6. A driver moves every 4 seconds. What does each move cost in each index?

**Search:** `geohash explained`, `geohash neighbors boundary problem`, `quadtree spatial index`, `Google S2 geometry cells`, `Uber H3 hexagonal grid`, `k nearest neighbors spatial index`, `Redis GEOADD GEOSEARCH`.

**Self-evaluation:**
- 🟢 You have a comparison table (kNN latency, update cost, memory) across all approaches, and the boundary test passes.
- 🔴 You only search the rider's own cell.

<details><summary>Hint (kNN with cells)</summary>
Search the rider's cell first, then rings of neighbor cells, growing outward until you have enough candidates and the next ring can't contain anyone closer.
</details>

---

## Level 2: Two riders, one driver

**Incident:** Two riders standing near each other book at the same moment. Two dispatch servers pick the **same** driver, and both riders are told "driver on the way". One of them waits for a driver who never comes.

**Done when:**
- [ ] A driver is **locked** atomically while an offer is open. D1 and D5 pass with **1,000 concurrent requests** in one hot spot.
- [ ] Offer timeout (e.g. 15 seconds) → next candidate. Decline → next candidate.
- [ ] A dispatch server crashes while holding a driver lock. The driver becomes available again within your bound.
- [ ] **Race test:** the driver accepts at exactly the moment the offer times out, 1,000 times. Never two outcomes.

**Predict first:** Without locks, how many double assignments in 1,000 concurrent hot-spot requests?

**Design questions:**
1. Where does the driver lock live, so that every dispatch server sees it? What atomic operation do you need?
2. A server crashes while holding a lock. How does the lock get released? (Timeouts / TTLs, as in project 03 L1.)
3. Accept arrives just as the offer expires. Who wins, and is that decided by the store or by luck? (Project 01 L4.)
4. Offer to **one** driver at a time, or to **3** at once (first to accept wins)? Faster matching vs drivers getting "sorry, taken" messages. Which would you choose, and why?
5. What does the rider see while offers are going out and timing out?

**Search:** `distributed lock with TTL`, `Redis SET NX PX`, `compare-and-set status`, `lease expiration race`, `fencing token`, `offer timeout dispatch`.

**Self-evaluation:**
- 🟢 D1 and D5 pass under the hot-spot test, 5 runs in a row.
- 🔴 The lock has no timeout.

---

## Level 3: Splitting the city, and the stadium

**Incident:** One dispatch server can't handle all the updates, so you split the city into regions, one per server. Then a concert ends: **20,000 riders and 3,000 drivers** in one small area. One server melts while the others are idle. Also, drivers crossing a region boundary briefly **vanish**.

**Done when:**
- [ ] The city is **sharded by cells** across N servers. Location updates and queries are routed to the right one.
- [ ] Queries near a shard border search the neighbor shard too.
- [ ] Drivers moving across a border are **handed off** without vanishing or appearing twice.
- [ ] The stadium scenario: load spread measured before and after your hot-spot fix.

**Predict first:** In the stadium scenario, what share of total load hits the busiest shard?

**Design questions:**
1. Fixed regions vs splitting busy cells into smaller ones as load grows: trade-offs?
2. A rider near a border: how do you search both sides without making every query slow?
3. Handoff: in what order do you remove the driver from shard A and add them to shard B so they're never missing and never double-matched?
4. What can you do for a hot cell other than splitting it? Could reads be spread across copies?
5. How does each server know which cells it owns? What happens when ownership changes?

**Search:** `geo sharding`, `hot spot mitigation`, `dynamic cell splitting`, `consistent hashing by cell id`, `handoff moving objects sharding`, `Uber Ringpop` (concept).

**Self-evaluation:**
- 🟢 The stadium test doesn't take down a shard, and border tests pass.
- 🔴 Drivers can be matched twice during a handoff.

---

## Level 4: Distance isn't time

**Incident:** The "nearest" driver is 500 m away in a straight line, but **across a river**: 15 minutes by road. Riders wait; drivers waste fuel.

**Done when:**
- [ ] A **road graph** for one city (from an OpenStreetMap extract). Drivers move along roads.
- [ ] ETA with **Dijkstra** and then **A*** written yourself. Speed compared.
- [ ] Candidates are ranked by ETA, not straight-line distance. Average pickup time measured before and after.
- [ ] **Batch matching:** collect requests for a short window (e.g. 2 seconds) and assign all at once. Compare average pickup time with "one at a time, greedy".

**Predict first:** How much does ETA ranking improve average pickup time? Does batch matching beat greedy?

**Design questions:**
1. Running Dijkstra for 20 candidates per request at 1,000 requests/sec: how expensive is that? How can A* help?
2. Can you **precompute** or **cache** ETAs between cells? How stale can they be?
3. Batch matching: what's the trade-off between waiting 2 seconds and making a better overall assignment?
4. Greedy one-by-one vs a global assignment (the Hungarian algorithm, or a simpler heuristic). When does greedy do badly?
5. Traffic changes during the day. How does that affect your ETA cache?

**Search:** `Dijkstra algorithm`, `A* search heuristic`, `road network graph OpenStreetMap`, `contraction hierarchies`, `OSRM`, `Hungarian algorithm assignment problem`, `batch matching ride hailing`.

**Self-evaluation:**
- 🟢 You have a pickup-time comparison: distance vs ETA vs batch.
- 🔴 ETA is straight-line distance ÷ average speed.

---

## Level 5: Surge pricing

**Incident:** At 6pm downtown, demand is 3× supply. Riders wait 20 minutes and leave the app. You need a **price multiplier per cell**, updated every 30 seconds from live demand and supply.

**Done when:**
- [ ] Per cell, over a **sliding time window**: count of requests and count of available drivers.
- [ ] A surge multiplier computed from them, shown to riders before they book.
- [ ] **Smoothing:** no wild jumps between neighbor cells or between updates.
- [ ] Late and out-of-order events (phones with bad network) handled.
- [ ] Measured: does surge (in the simulator: fewer requests at higher price, more drivers moving toward surge) reduce wait times?

**Predict first:** How quickly will surge react to the stadium emptying, with your window size?

**Design questions:**
1. **Tumbling** vs **sliding** windows: what's the difference, and which fits here?
2. **Event time** vs **processing time**: an event from 10 seconds ago arrives now. Which window does it belong to?
3. Where should you compute this: inside each dispatch shard (it already has the data) or in a separate stream processor?
4. How do you smooth the multiplier across time and across neighbor cells?
5. Should there be a cap? Who decides, and how is that rule written in the system?

**Search:** `stream processing windows tumbling sliding`, `event time vs processing time`, `watermarks late data`, `Kafka Streams windowing` (concept), `Apache Flink windows` (concept), `exponential smoothing`.

**Self-evaluation:**
- 🟢 You have a chart of surge and wait time around the stadium event.
- 🔴 Late events silently change surge for a window that has already been shown to riders.

---

## Stretch levels
- **Location history:** keep every location for 30 days, for disputes and analytics. Calculate the size first. (Project 17 is a good fit for storing it.)
- **Map UI:** a live map of drivers and surge cells (AI can build the UI).
- **Shared rides:** match two riders going the same way into one car.
- **Driver fairness:** spread rides fairly across drivers, not always to the closest one.

## Graduation: answer out loud
1. Design ride matching for a city with 500k drivers.
2. Geohash vs quadtree vs H3: how do they work, and when would you use each?
3. How do you make sure one driver never gets two riders?
4. How do you handle a stadium emptying?
5. Why is straight-line distance a bad matching signal, and what do you use instead?
6. How would you compute surge pricing in real time?

## Taking it further
A live map dashboard · food delivery dispatch (multi-stop routes) · use your project 17 TSDB for location history · compare your geo index with PostGIS · simulate a real city's traffic patterns.
