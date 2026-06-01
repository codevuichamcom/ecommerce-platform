package com.ecom.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Day 22 — published khi product bị archive (soft-delete). Consumer
 * {@code ProductIndexer} xóa document khỏi ES index để search KHÔNG còn
 * trả product đã ngừng bán.
 *
 * <p><b>Tại sao "deleted" mà thực ra là ARCHIVE?</b> Postgres giữ row
 * (historical order còn reference SKU), nhưng search index KHÔNG nên trả
 * product archived → với ES nó là "deleted khỏi index". Tách rõ:
 * source-of-truth lifecycle (DRAFT/ACTIVE/ARCHIVED) khác index membership
 * (có/không trong ES). Đây là điểm junior hay lẫn — xem
 * {@code issues/22-es-postgres-sync-drift.md}.
 *
 * <p>Idempotent ở consumer: xóa document không tồn tại = no-op (ES delete
 * by id trả 404, swallow). Replay event = an toàn.
 */
public record ProductDeletedV1(
        UUID eventId,
        Instant occurredAt,
        UUID productId
) implements DomainEvent {

    @Override
    public String eventType() {
        return "product.deleted";
    }
}
