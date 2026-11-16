package model;

public class OrderItem {
    private String ID;
    private String name;
    private double price;

    public OrderItem(String id, String name, double price) {
        this.ID = id;
        this.name = name;
        this.price = price;
    }

    public String getId() {
        return this.ID;
    }

    public String getName() {
        return this.name;
    }

    public double getPrice() {
        return this.price;
    }
}
