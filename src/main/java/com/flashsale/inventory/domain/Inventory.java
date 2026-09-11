package com.flashsale.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Inventory() {}

    public Inventory(Long productId, int availableQty) {
        this.productId = productId;
        this.availableQty = availableQty;
    }

    public Long getProductId() { return productId; }
    public int getAvailableQty() { return availableQty; }
    public void adjust(int delta) { this.availableQty += delta; this.updatedAt = Instant.now(); }
}
