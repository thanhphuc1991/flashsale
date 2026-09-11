package com.flashsale.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Idempotency ledger for the inventory sync processor. A batch can be
 * reprocessed after an app restart or a mid-batch failure, so the same
 * outbox event could be read twice; before applying an event we insert its
 * id here — a duplicate insert fails on the PK and we skip re-processing.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
    }
}
