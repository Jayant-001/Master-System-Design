## Problem 1: Order placed → side effects
*Fundamentals: decoupling, retries, duplicates*

> You run an e-commerce checkout service. When a customer places an order, several other things must happen: send a confirmation email, reserve inventory, add loyalty points, and record the sale for analytics. Today, checkout calls each of these services one after another, and if the email provider is slow, checkout hangs.

**Requirements**
- Checkout responds in under 200 ms, whatever the downstream services are doing.
- If the email service is down for 10 minutes, no emails are lost; they go out after it recovers.
- A customer never gets two confirmation emails or double loyalty points.
- Adding a new side effect (e.g. a fraud check) must not require changing checkout code.

**Interviewer follow-ups**
1. The order is saved in the database, then the app crashes before publishing the event. What now?
2. One message always fails, e.g. a bad email address. How do you stop it blocking everything behind it?
3. Inventory must process `OrderPlaced` → `OrderCancelled` for the same order in that order. How do you guarantee it?

**Stack:** Spring Boot or Node, Postgres, Kafka, a fake email service you can switch off.
**You'll learn:** topics, consumer groups, partition keys and ordering, at-least-once delivery, idempotent consumers, retry and dead-letter topics, and the transactional outbox pattern.

---

## Problem 2: Ad click counting / trending products
*Stream processing at scale*

> Advertisers want to see clicks per ad per minute, near real time, and you bill them on these numbers. Traffic is about 10k clicks/sec on average, and spikes 10x during a sale.

**Requirements**
- The dashboard is at most about 30 seconds behind.
- The counts used for billing must be accurate: no double counting and no lost clicks.
- Show the top 10 ads in the last 5 minutes.
- Keep the raw clicks so you can recompute if you find a bug in the counting logic.

**Interviewer follow-ups**
1. One celebrity ad gets 50% of all clicks. What breaks? (Think "hot partition".)
2. Mobile clients send clicks up to 2 minutes late. Which minute do those clicks count towards?
3. Your aggregator was buggy for 3 hours. How do you fix the numbers?
4. Consumers fall behind during the spike. How do you notice, and what do you do?

**Stack:** Kafka, Kafka Streams (or Flink), Redis for the top-10 list, Postgres or ClickHouse for the per-minute counts, and a small load generator script.
**You'll learn:** partitioning strategy, consumer lag, scaling consumers, windowing, late events, replaying from offsets, and exactly-once versus deduplication.

---

## Problem 3: Keep search in sync with the database
*Data integration (very common in real companies)*

> Your product catalog lives in Postgres, and search runs on Elasticsearch. Today a nightly job copies everything over, so a price change can take up to 24 hours to show up in search. The product team wants changes visible in seconds. Other teams also want to react to product changes, such as the cache and the recommendations service.

**Requirements**
- A database change shows up in search within about 5 seconds.
- Don't change the existing product service code. Many old code paths write to the database.
- If Elasticsearch is wiped, rebuild it without hitting the production database hard.
- Adding a column to the products table must not break downstream consumers.

**Interviewer follow-ups**
1. A price is updated twice quickly. Could search end up showing the older price?
2. How does a brand-new consumer get the current state of every product, not just recent changes?
3. Someone renames a column. What happens downstream?

**Stack:** Postgres, Debezium (CDC), Kafka, Elasticsearch, Redis, and optionally Schema Registry.
**You'll learn:** change data capture, log compaction, per-key ordering, rebuilding state by replaying the log, and schema evolution.

---

**How to use these:** before writing any code, spend 15 minutes answering the follow-ups on paper, as you would in an interview. Then build the smallest version, break it on purpose (kill a consumer, take a service down, send duplicates) and see whether your design holds up. Running everything locally with `docker-compose` is enough.

When you start one, send me your design and I'll play the interviewer and push back on it.