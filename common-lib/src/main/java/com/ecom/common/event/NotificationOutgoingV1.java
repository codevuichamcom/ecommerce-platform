package com.ecom.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published bởi notification-service (Day 11) để decouple đầu ra (email,
 * sms, push) khỏi service trigger. Đây là 1 fan-out asymmetry: 1 trigger
 * → N channel.
 *
 * <p>{@code channel}: {@code "EMAIL"}, {@code "SMS"}, {@code "PUSH"}.
 * {@code templateData}: payload để render template (subject, name, ...).
 */
public record NotificationOutgoingV1(
        UUID eventId,
        Instant occurredAt,
        UUID userId,
        String channel,
        String templateCode,
        Map<String, Object> templateData
) implements DomainEvent {

    @Override
    public String eventType() {
        return "notification.outgoing";
    }
}
