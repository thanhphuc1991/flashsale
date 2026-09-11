package com.flashsale.flashsale.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "purchase", uniqueConstraints = {
        // Core correctness guarantee: at most one flash-sale purchase per
        // user per calendar day, enforced at the DB level (not just in
        // application code) so it holds even under concurrent requests.
        @UniqueConstraint(name = "uq_purchase_user_per_day", columnNames = {"user_id", "purchase_date"})
})
public class Purchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "window_id", nullable = false)
    private Long windowId;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(name = "price_paid", nullable = false)
    private BigDecimal pricePaid;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Purchase() {}

    public Purchase(Long userId, Long itemId, Long windowId, LocalDate purchaseDate,
                     BigDecimal pricePaid, String idempotencyKey) {
        this.userId = userId;
        this.itemId = itemId;
        this.windowId = windowId;
        this.purchaseDate = purchaseDate;
        this.pricePaid = pricePaid;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getItemId() { return itemId; }
}
