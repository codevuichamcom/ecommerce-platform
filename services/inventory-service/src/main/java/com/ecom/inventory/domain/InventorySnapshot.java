package com.ecom.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Snapshot tồn kho theo ngày. Ghi qua native upsert có fencing guard
 * (xem {@link InventorySnapshotRepository}), KHÔNG qua JPA persist — nên entity
 * này chủ yếu để map cho read + Flyway {@code ddl-auto: validate}.
 */
@Entity
@Table(name = "inventory_snapshot")
public class InventorySnapshot {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "snapshot_date", nullable = false, unique = true)
    private LocalDate snapshotDate;

    @Column(name = "total_skus", nullable = false)
    private long totalSkus;

    @Column(name = "total_reserved", nullable = false)
    private long totalReserved;

    /** Hàng rào chống stale writer — token nhỏ hơn token đã lưu bị DB từ chối. */
    @Column(name = "last_fencing_token", nullable = false)
    private long lastFencingToken;

    @Column(name = "created_by_instance", nullable = false)
    private String createdByInstance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InventorySnapshot() {
        // JPA
    }

    public UUID getId() { return id; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public long getTotalSkus() { return totalSkus; }
    public long getTotalReserved() { return totalReserved; }
    public long getLastFencingToken() { return lastFencingToken; }
    public String getCreatedByInstance() { return createdByInstance; }
    public Instant getCreatedAt() { return createdAt; }
}
