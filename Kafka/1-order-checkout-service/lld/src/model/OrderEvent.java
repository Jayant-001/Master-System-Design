package model;

import java.time.Instant;
import java.util.List;

public class OrderEvent {
    
    private String orderId;     // key = orderId -> partition
    private String customerId;
    private List<OrderItem> items;   // Inventory needs sku + qty
    private double totalAmount;      // Loyalty and Analytics need it
    private Instant createdAt;

    public OrderEvent(String orderId, String customerId,
            List<OrderItem> items, double totalAmount, Instant createdAt) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.items = items;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
