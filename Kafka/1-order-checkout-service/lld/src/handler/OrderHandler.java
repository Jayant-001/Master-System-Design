package handler;

import java.time.Instant;
import java.util.List;

import db.InMemoryDb;
import model.Order;
import model.OrderItem;
import model.OrderEvent;
import model.OrderStatus;
import model.OutboxEvent;

public class OrderHandler {
    private final InMemoryDb db;

    public OrderHandler(InMemoryDb db) {
        this.db = db;
    }

    // [POST] / api/orders/purchase
    public String purchase(String orderId, String customerId, List<OrderItem> items) {

        Order order = new Order(orderId, customerId);

        order.setOrderItems(List.copyOf(items));

        double totalAmount = items.stream().mapToDouble(OrderItem::getPrice).sum();
        order.setTotalAmount(totalAmount);
        
        order.setOrderStatus(OrderStatus.CREATED);

        OrderEvent kafkaEvent = new OrderEvent(
                    orderId,
                    customerId, 
                    List.copyOf(items), 
                    totalAmount, 
                    Instant.now());

        OutboxEvent event = new OutboxEvent("ORDER_CREATED", kafkaEvent);
        
        Order saved = db.placeOrder(order, event);

        if(!saved.getCustomerId().equals(customerId)) {
            throw new IllegalArgumentException("orderId already used");  // 409 in API terms
        }

        return saved.getOrderId();
    }

    // [POST] /api/orders/cancel
    public boolean cancel(String customerId, String orderId) {

        return true;
    }

    public Order getStatus(String orderId) {

        return null;
    }
}
