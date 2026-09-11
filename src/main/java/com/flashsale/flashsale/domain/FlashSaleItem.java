package com.flashsale.flashsale.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import java.math.BigDecimal;

@Entity
@Table(name = "flash_sale_item", indexes = {
        // findByWindowIdIn (backing GET /flash-sale/current) and
        // tryReserveStock's WHERE id = ? both benefit — the latter's id
        // lookup is covered by the PK, this covers the window_id lookup.
        @Index(name = "idx_item_window", columnList = "window_id")
})
@Check(constraints = "quantity_sold <= quantity_limit")
// Last-resort safety net; the real oversell guarantee is the atomic
// conditional UPDATE in FlashSaleItemRepository.tryReserveStock().
public class FlashSaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "window_id", nullable = false)
    private Long windowId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "quantity_limit", nullable = false)
    private int quantityLimit;

    @Column(name = "quantity_sold", nullable = false)
    private int quantitySold;

    @Version
    @Column(nullable = false)
    private long version;

    protected FlashSaleItem() {}

    public Long getId() { return id; }
    public Long getWindowId() { return windowId; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getPrice() { return price; }
    public int getQuantityLimit() { return quantityLimit; }
    public int getQuantitySold() { return quantitySold; }
    public int getRemaining() { return quantityLimit - quantitySold; }
}
