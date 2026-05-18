package com.ecom.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published bởi inventory-service sau khi reserve thành công. Day 8 mới
 * khai báo schema; Day 9 wire thực sự cho order flow event-driven.
 */
public record StockReservedV1(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        String sku,
        int quantity
) implements DomainEvent {

    @Override
    public String eventType() {
        return "inventory.reserved";
    }
}
