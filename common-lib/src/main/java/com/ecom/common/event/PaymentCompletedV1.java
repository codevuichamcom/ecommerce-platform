package com.ecom.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published bởi payment-service (Day 10) khi gateway callback success.
 */
public record PaymentCompletedV1(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String transactionId,
        String currency,
        BigDecimal amount
) implements DomainEvent {

    @Override
    public String eventType() {
        return "payment.completed";
    }
}
