package com.ecommerce.order.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event — phát ra khi {@link Order#place} thành công. Day 9 wire
 * {@code @TransactionalEventListener(AFTER_COMMIT)} → Kafka topic
 * {@code order.placed} qua outbox table (Day 13).
 *
 * <p>Payload tối thiểu — consumer query lại order-service nếu cần detail
 * (anti-pattern: fat event mang full state, kéo theo coupling schema).
 */
public record OrderPlaced(UUID orderId, UUID userId, long totalAmount,
                           String currency, Instant occurredAt) {}
