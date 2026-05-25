package com.ecommerce.order.infrastructure.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox row — 1 event đợi relay publish.
 *
 * <p><b>Lifecycle</b>:
 * <pre>
 *   PENDING → (relay publish OK) → SENT
 *   PENDING → (relay publish fail, attempts++) → PENDING
 *   PENDING → (attempts ≥ MAX) → FAILED (manual triage)
 * </pre>
 *
 * <p><b>Payload</b>: JSONB serialize sẵn ở caller — relay không cần biết
 * schema concrete event type. Trade-off: payload deser cost ở relay khi
 * publish (Kafka producer cần Object). Chấp nhận vì cleaner abstraction.
 *
 * <p><b>partitionKey</b>: lưu sẵn để relay không cần lookup logic chọn key.
 * Cho {@code OrderCreatedV1} đó là {@code orderId.toString()} — đảm bảo
 * cùng order rơi cùng Kafka partition → ordering preserved per-aggregate.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 128)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * Factory — caller responsibility serialize event payload sang JSON string.
     * Reason: keep OutboxEvent infra-agnostic (không phụ thuộc Jackson ở entity).
     */
    public static OutboxEvent of(
            String aggregateType,
            String aggregateId,
            String eventType,
            String topic,
            String partitionKey,
            String payloadJson) {
        OutboxEvent e = new OutboxEvent();
        e.id = UUID.randomUUID();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.topic = topic;
        e.partitionKey = partitionKey;
        e.payload = payloadJson;
        e.status = OutboxStatus.PENDING;
        e.attempts = 0;
        e.createdAt = Instant.now();
        return e;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.status = OutboxStatus.FAILED;
        this.lastError = truncate(error);
    }

    /**
     * Tăng attempts + ghi error cuối, GIỮ status PENDING để retry pickup
     * tick relay tiếp theo. Caller phải check {@link #shouldGiveUp(int)}
     * trước để chuyển FAILED khi vượt ngưỡng.
     */
    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = truncate(error);
    }

    public boolean shouldGiveUp(int maxAttempts) {
        return this.attempts >= maxAttempts;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
