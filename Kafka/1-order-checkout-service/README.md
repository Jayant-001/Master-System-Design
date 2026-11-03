# Problem 1: Order placed → side effects

Problem statement and follow-ups: [Problem.md](../Problem.md)

### How the design meets the requirements

| Requirement | How | Details |
|---|---|---|
| Checkout responds in under 200 ms | The request only writes to Checkout's own DB, in one transaction. It never waits on Kafka or downstream services. | Q1, Q2 |
| Email down for 10 minutes loses nothing | Events stay in Kafka (7-day retention). The email consumer retries in place and doesn't commit the offset until it succeeds. Kafka itself must not lose them either: replication factor 3, `min.insync.replicas=2`, producer `acks=all`, and the relay marks an outbox row sent only after Kafka acknowledges it. | Q8 |
| No duplicate emails or loyalty points | Idempotency key at the API, idempotent consumers. | Q4, Q5 |
| New side effect without changing checkout | A new service is a new consumer group on `orders`. | Q7 |

Interviewer follow-ups: 1 → Q1, 2 → Q8, 3 → Q9.

## Partition Key selection: userId or orderId
  - **Choose the smallest unit whose events must stay in order.** The requirement is ordering per order. userId gives you ordering per user, which is stronger than you need and costs you something.
  - **Hot keys.** Order counts per user are uneven. A reseller, a bot or a B2B account might place thousands of orders, and with userId as the key they all land on one partition and one consumer instance. orderId spreads the load almost evenly.
  - **Guest checkout.** Does a guest have a userId? If the key is null or a shared "guest" value, what happens to ordering and to load?

A case for userId would be a consumer that needs one user's events in order across different orders. Check whether any of your four services needs that. If none does, the reasoning above points to orderId.

**Decision:** `orderId`.


### System architecture
```
INSIDE THE REQUEST (~20–50 ms)
Client ──POST /api/order (Idempotency-Key: abc)──▶ Checkout API
                                                   │ ONE database transaction:
                                                   │   insert idempotency_keys(abc → order 42)
                                                   │   insert orders(42, status = PENDING)
                                                   │   insert outbox(OrderPlaced, order 42)
                                                   ▼ commit
Client ◀── 201 {orderId: 42, status: PENDING} ─────┘

AFTER THE REQUEST (seconds)
Checkout relay ──▶ Kafka "orders" (key = orderId): OrderPlaced
                     └─▶ Inventory: reserve stock in its own DB + outbox row, one transaction

Inventory relay ──▶ Kafka "inventory-events" (key = orderId): InventoryReserved | InventoryReservationFailed
                     └─▶ Checkout: set order CONFIRMED | REJECTED + outbox row, one transaction

Checkout relay ──▶ Kafka "orders" (key = orderId): OrderConfirmed | OrderRejected
                     ├─▶ Email: send confirmation, or "sorry" on rejection
                     ├─▶ Loyalty: add points in its own DB (confirmed only)
                     └─▶ Analytics: record the sale
```

Each consumer reads every event on its topic and ignores the types it doesn't handle.

### Order status

```
PENDING ──InventoryReserved───────────▶ CONFIRMED ──customer cancels──▶ CANCELLED
   ├────InventoryReservationFailed────▶ REJECTED
   └────customer cancels──────────────▶ CANCELLED
```

- Checkout owns this status and only moves it along these arrows. A late `InventoryReserved` for an order that is already CANCELLED is ignored, not applied.
- Cancelling works like placing: Checkout sets CANCELLED and writes an `OrderCancelled` outbox row in one transaction. Inventory releases the stock, Loyalty reverses the points, and Email sends a cancellation notice.


### Design Questions

#### 1. The app crashes after step 1 but before step 2. This is follow-up 1.

  This is the dual-write problem: two systems (database and Kafka) with no transaction covering both. The fix is the transactional outbox:

  - Write the event as a row in an outbox table, in the same transaction as the order. Either both are saved or neither is.
  - A relay process reads unsent rows, publishes them to Kafka, and marks them sent.
  - If the relay crashes after publishing but before marking the row, it publishes the event again. So the outbox gives you at-least-once delivery, and question 5 handles the duplicates.
  - With DynamoDB, use TransactWriteItems to write the order and the outbox item together. Alternatively, DynamoDB Streams can act as the outbox.
  - Why not publish first and save second? Then a crash leaves behind an event for an order that doesn't exist, which is worse.
  - The same applies to every service that updates its database and publishes an event. Inventory (reservation + `InventoryReserved`) and Checkout's reply handler (status update + `OrderConfirmed`) each need their own outbox.

#### 2. Kafka is slow or down at step 2. Does checkout still respond in 200 ms? What do you return?

  With the outbox, the request never touches Kafka, so checkout still responds in time. Events wait in the outbox and go out when Kafka recovers. They're delayed, not lost. Monitor the age of the oldest unsent outbox row.

  Checkout's only hard dependency is its own database. If that's down, return 503, because you can't accept an order you can't save.

  The exception is the read-only stock check in Q3, which calls Inventory during the request. Give it a short timeout and skip the check if Inventory doesn't answer (fail open). The async reservation still catches real stock problems.

#### 3.  inventory reservation is now asynchronous, so checkout says "order placed" before any stock is reserved. What happens if the item is out of stock, and what does the customer see?

  This is a business trade-off with two options:

  - Asynchronous with compensation (the common choice):
    - Checkout does a cheap read-only stock check to reject obvious cases, then returns PENDING.
    - Inventory tries to reserve the stock and publishes either InventoryReserved or InventoryReservationFailed.
    - Checkout consumes those events and sets the order to CONFIRMED, or to REJECTED (refund it and send a "sorry" email).
    - This chain of events with an undo step is called a saga.
  - Synchronous reservation: checkout calls Inventory before responding. You never oversell, but checkout depends on Inventory's speed and uptime again.

  Two consequences:
    
  - Reserve stock atomically: UPDATE stock SET reserved = reserved + :qty WHERE sku = :sku AND total - reserved >= :qty. If zero rows are updated, the item is out of stock. This stops two orders from both taking the last item.
  - Send the confirmation email on OrderConfirmed, not OrderPlaced. Otherwise a customer can get "confirmed" and then "cancelled".

  **Should we decrement the available units or track in a separate reserve quantity**

  That single `UPDATE` is safe on its own. Postgres locks the row, so a second concurrent update waits, then re-checks the `WHERE` condition against the new value and updates zero rows if there isn't enough left.

  Note that "atomic" here means the database serializes the two updates. It still runs asynchronously from checkout, inside the Inventory consumer.

  **Decrementing stock directly is just as safe:**

  ```sql
  UPDATE stock SET quantity = quantity - :qty
  WHERE sku = :sku AND quantity >= :qty;   -- 0 rows → out of stock
  ```

  DynamoDB does the same with a conditional update: `SET quantity = quantity - :q` with the condition `quantity >= :q`. So atomicity isn't the reason for a separate `reserved` column. These are:

  1. **It keeps the physical count true.** Units only leave the shelf when they ship. With `total` and `reserved`, `total` always matches the warehouse and `available = total - reserved`. With a direct decrement, your number drifts from the shelf count between order and shipping, which confuses audits, restocking and warehouse corrections.
  2. **There's a gap between order and shipping.** During it, the customer can cancel, payment can fail, or an unpaid hold can expire. A reservation describes that state: "promised, not yet gone". On shipping you do `total -= qty, reserved -= qty`.
  3. **You can see what's promised.** You can tell how many units are held for orders and spot holds that never completed.

  **What matters more than either column:** you need a record for each order of what it actually took, for example `reservations(orderId, sku, qty, status)`. Without one, these go wrong whichever column you use:
  - A duplicate `OrderPlaced` decrements twice.
  - A duplicate `OrderCancelled` adds stock back twice.
  - An `OrderCancelled` arrives for an order whose reservation failed, and you add back stock it never took.

  Write the reservation row and the stock update in the same transaction, and only release stock if a row with status RESERVED exists. That handles all three cases.

  **For your project:** a direct decrement plus that per-order record is enough. Switch to `total` and `reserved` if you later model shipping or payment holds.
    
  
#### 4. Idempotency race at the API. Two identical requests arrive at the same moment, both check, both find nothing, and both create the order. How do you prevent that?
  
  Don't check first and insert second, because two requests can both pass the check. Let the database decide instead:

  - Put a unique constraint on the idempotency key and insert it in the same transaction as the order. The second insert fails, so you return the original order's response.
  - In Postgres, use INSERT ... ON CONFLICT DO NOTHING, then read the existing row.
  - In DynamoDB, use a conditional put with attribute_not_exists(idempotencyKey).
  - If the first request is still running, return 409 "in progress" so the client retries later.
  - The client generates the key once per checkout attempt and reuses it on every retry.
  - Don't keep the keys only in Redis. The key write and the order write would then be separate, which brings the race back.

####   5. Duplicates in consumers. Committing after processing means at-least-once delivery, so a message can be processed twice. Where do you stop a second confirmation email or double loyalty points (requirement 3)?
  Assume every message can arrive more than once, and make processing idempotent (running it twice has the same effect as once). Every event carries a unique eventId, such as the outbox row ID.

  - Loyalty and Inventory, which have their own databases:
    - In one local transaction, insert the eventId into a processed_events table with a unique constraint, and apply the change.
    - If the insert fails, it's a duplicate, so skip it.
    - Only then commit the Kafka offset.
    - A points ledger with a unique constraint on (orderId, eventType) does the same job. A constraint on orderId alone would block the reversal entry when the order is cancelled.
  - Email, which is an external system:
    - The email send can't be part of your database transaction.
    - If you record "sent" after sending, a crash in between causes a rare duplicate. If you record it before sending, a crash can mean the email is never sent.
    - Pick whichever is less bad, or use an idempotency key if the provider supports one.
    - Sending to an external system exactly once is impossible unless the receiver deduplicates.
  - **Kafka's "exactly-once" feature doesn't cover this.** It only covers reading from and writing back to Kafka, not emails or your database.
  
  
#### 6. Partition count. How many partitions do you need? Adding them later remaps keys and can break per-order ordering, so plan for the most instances your slowest consumer will ever need.

  Partitions ≥ peak messages per second ÷ messages per second one instance of your slowest consumer can handle.
  
  For example, at a peak of 1,000 orders/s with an email instance handling 50/s, you need 20 instances, so at least 20 partitions. Add headroom and go to about 30.

  - Partitions aren't free (more files, slower rebalances), but tens per topic is normal.
  - You can get more parallelism without more partitions: run N workers inside one instance and route each message to worker `hash(orderId) % N`. Per-order ordering still holds.
  - If you outgrow the count, create a new topic with more partitions and migrate to it, rather than adding partitions in place.

#### 7. Where a new consumer starts reading. When the fraud check service joins, does it start from the newest messages or from the beginning? How long does Kafka keep messages?

  This setting only matters when a group has no committed offset yet. Options are earliest (the oldest message Kafka still holds) or latest (only new messages).

  - The Java client defaults to latest. kafka-go's `StartOffset` defaults to `FirstOffset` (earliest), so set it explicitly.
  - For the fraud check, `latest` is probably right, because flagging long-shipped orders is of little use. Use earliest only if you want to check recent orders too.
  - Kafka keeps messages for 7 days by default, so it isn't a permanent archive. For older history, backfill from the orders database.

#### 8. Retries and failures. This comes from the problem; The email provider is down for 10 minutes, or one message always fails (follow-up 2). How does the consumer retry without blocking every message behind it in that partition?

  First classify the error:

  - **Temporary** (provider down, timeout, 5xx response): retrying can work.
  - **Permanent** (invalid email address, malformed message): retrying never helps.
  
  Then handle it with one of these:

  - **Retry in place with backoff:**
    - Don't commit the offset; retry the same message after 1 s, 2 s, 4 s, and so on, with a circuit breaker so you stop hammering a dead provider.
    - For the 10-minute outage, this is fine. Every message would fail anyway, so blocking the partition costs nothing, and the uncommitted offset means nothing is lost.
    - Alert when consumer lag grows.
  - **Dead-letter topic (DLQ):**
    - Permanent errors, or temporary ones that fail N times, go to `orders.email.dlq` along with the error details.
    - Commit the offset and move on, so the partition is unblocked.
    - Alert on anything landing there, fix the cause, and replay the messages.
  - **Retry topics:**
    - Move a failing message to something like `orders.email.retry-1m` or `orders.email.retry-10m` so the rest keep flowing.
    - A separate consumer retries it after the delay, and the last step is the DLQ.
  - **Keep retry and DLQ topics per consumer group.** Never republish a failed message to orders, because every other service would process it again.
  - **Moving a message aside breaks per-key ordering.**
    - That's fine for email but not for inventory.
    - If `OrderPlaced` sits in a retry topic while `OrderCancelled` gets processed, you release stock that was never reserved.
    - For consumers that need ordering, retry in place, or hold back later events for the same orderId until the stuck one is resolved.

#### 9. Inventory must process OrderPlaced → OrderCancelled for the same order in that order. How do you guarantee it? (Follow-up 3)

  Kafka only guarantees order within one partition, so every link in the chain has to keep it:

  - **Topic and key:** both events go to `orders` with key `orderId`, so they land in the same partition, and one instance of each consumer group reads them in order.
  - **Producer:** the outbox relay must publish rows in the order they were written. Two relay instances working on the same rows, or parallel sends, can publish `OrderCancelled` first. Producer retries can also reorder or duplicate messages unless the producer is idempotent (or allows only one in-flight request per partition).
  - **Consumer:** process each partition's messages in order. A worker pool must route by `orderId` (Q6), and retry topics break the order (Q8).
  - **Why it matters:** a customer can cancel while the order is still PENDING. Inventory should see `OrderPlaced` (reserve) and then `OrderCancelled` (release). If they arrive reversed, the release finds nothing to release, and the reservation then holds stock for a cancelled order forever.