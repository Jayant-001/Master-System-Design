package model;

import java.util.List;

public class Order {
    private String orderId; // uses for idempotency as well
    private String customerId;
    private List<OrderItem> items;
    private double totalAmount;
    private OrderStatus status; // CREATED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED

    public Order(String id, String customerId) {
        this.orderId = id;
        this.customerId = customerId;
    }

    public String getOrderId() {
        return this.orderId;
    }

    public OrderStatus getOrderStatus() {
        return this.status;
    }

    public void setOrderStatus(OrderStatus status) {
        this.status = status;
    } 

    public List<OrderItem> getOrderItems() {
        return this.items;
    }

    public void setOrderItems(List<OrderItem> items) {
        this.items = items;
    }

    public String getCustomerId() {
        return this.customerId;
    }

    public void setTotalAmount(double amount) {
        this.totalAmount = amount;
    }

    public double getTotalAmount() {
        return this.totalAmount;
    }
}
