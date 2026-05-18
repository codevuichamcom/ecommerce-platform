package com.ecom.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published bởi order-service khi {@code Order} được persist (Day 8 publish
 * sync trong transaction để demo — Day 13 sẽ refactor sang outbox pattern
 * tránh dual-write).
 *
 * <p>Consumer: notification-service (Day 11 send email), inventory-service
 * (Day 9 sẽ consume thay vì sync Feign call), analytics-service (Day 23
 * Mongo event store).
 *
 * <p>Schema rule v1: KHÔNG bỏ field, KHÔNG đổi nghĩa field. Thêm field
 * mới ở cuối với default = OK (Jackson backward-compatible). Breaking
 * change → tạo {@code OrderCreatedV2} + topic mới {@code order.created.v2}.
 */
public record OrderCreatedV1(
        UUID eventId,
        Instant occurredAt,
        UUID orderId,
        UUID userId,
        String currency,
        BigDecimal totalAmount,
        List<Item> items
) implements DomainEvent {

    @Override
    public String eventType() {
        return "order.created";
    }

    public record Item(String sku, int quantity, BigDecimal unitPrice) {}
}
