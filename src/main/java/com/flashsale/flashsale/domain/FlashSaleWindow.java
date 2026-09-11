package com.flashsale.flashsale.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "flash_sale_window", indexes = {
        // GET /flash-sale/current runs this range lookup on every request —
        // this is the highest-traffic query in the system (per the 500 TPS
        // requirement, clients typically poll it), so it must not be a full
        // table scan.
        @Index(name = "idx_window_time_range", columnList = "start_time, end_time")
})
public class FlashSaleWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WindowStatus status = WindowStatus.SCHEDULED;

    protected FlashSaleWindow() {}

    public FlashSaleWindow(String name, Instant startTime, Instant endTime) {
        this.name = name;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public WindowStatus getStatus() { return status; }

    public boolean isCurrentlyActive(Instant now) {
        return !now.isBefore(startTime) && now.isBefore(endTime);
    }
}
