package com.flashsale.flashsale.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outbox_event", indexes = {
        // InventorySyncProcessor polls "WHERE status = 'PENDING'" every
        // 500ms; without this the poll becomes a full table scan as the
        // table grows with every purchase.
        @Index(name = "idx_outbox_status", columnList = "status, created_at")
})
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public void markPublished() { this.status = "PUBLISHED"; this.publishedAt = Instant.now(); }
    public void markFailed() { this.status = "FAILED"; }
}
