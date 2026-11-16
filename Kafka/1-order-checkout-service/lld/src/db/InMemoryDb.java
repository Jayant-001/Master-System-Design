package db;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import model.Order;
import model.OutboxEvent;

public class InMemoryDb {
    private final Map<String, Order> orders = new HashMap<>();           // key -> orderId , used for idempotency also
    private final Map<Long, OutboxEvent> outbox = new LinkedHashMap<>(); // keeps insert order
    private long nextSeq = 0;
    private static final InMemoryDb instance = new InMemoryDb();

    private InMemoryDb() { }

    public static InMemoryDb getInstance() {
        return instance;
    }

    // Equivalent of BEGIN … COMMIT
    public synchronized Order placeOrder(Order order, OutboxEvent event) {

        Order existing = orders.get(order.getOrderId());
        if (existing != null) return existing; // duplicate request

        orders.put(order.getOrderId(), order);
        event.assignEventId(++nextSeq);
        outbox.put(event.getEventId(), event);
        return order;
    }

    // SELECT … ORDER BY id LIMIT 100
    public synchronized List<OutboxEvent> findPending(int limit) {

        return outbox.values().stream().limit(limit).toList();
    }

    // DELETE 
    public synchronized void markSent(Long eventId) {
        outbox.remove(eventId);
    }
}

// usage -> claude ignore this
// for (OutboxEvent e : db.findPending(100)) {
//     try {
//         publisher.publish("orders", e.getAggregateId(), e.getPayload()); // key = orderId
//         db.markSent(e.getSeq());        // only after Kafka confirms
//     } catch (Exception ex) {
//         break;                          // stop here to keep order; retry next run
//     }
// }
