package com.ecommerce.order.infrastructure.outbox;

/**
 * Trạng thái 1 outbox row.
 *
 * <ul>
 *   <li>{@code PENDING} — vừa insert, relay chưa pick.</li>
 *   <li>{@code SENT}    — relay đã publish Kafka thành công, ack từ broker.</li>
 *   <li>{@code FAILED}  — relay retry quá max attempts (10) → đẩy ra alert,
 *       cần manual triage qua {@code runbooks/outbox-stuck-events.md}.</li>
 * </ul>
 *
 * <p>Sealed-interface overkill ở đây — outbox status không carry data,
 * không có state-specific behavior. Enum đủ + đơn giản hơn cho JPA mapping.
 */
public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
