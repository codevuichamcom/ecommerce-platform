package com.ecommerce.order.domain;

import com.ecommerce.order.infrastructure.persistence.OrderStatusSerializer;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sealed status round-trip — JSON-ize và parse lại phải bằng nhau. Test
 * này quan trọng vì là điểm coupling domain ↔ persistence.
 */
class OrderStatusSerializationTest {

    @Test
    void pendingPayment_roundTrip() {
        OrderStatus s = new OrderStatus.PendingPayment();
        String json = OrderStatusSerializer.toJson(s);
        OrderStatus back = OrderStatusSerializer.fromDb(s.statusName(), json);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void paid_roundTrip() {
        OrderStatus s = new OrderStatus.Paid(Instant.parse("2026-05-15T10:00:00Z"));
        String json = OrderStatusSerializer.toJson(s);
        OrderStatus back = OrderStatusSerializer.fromDb(s.statusName(), json);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void shipped_roundTrip() {
        OrderStatus s = new OrderStatus.Shipped("VN-TRACK-001", Instant.parse("2026-05-15T11:00:00Z"));
        String json = OrderStatusSerializer.toJson(s);
        OrderStatus back = OrderStatusSerializer.fromDb(s.statusName(), json);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void cancelled_roundTrip() {
        OrderStatus s = new OrderStatus.Cancelled("out of stock", Instant.parse("2026-05-15T12:00:00Z"));
        String json = OrderStatusSerializer.toJson(s);
        OrderStatus back = OrderStatusSerializer.fromDb(s.statusName(), json);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void delivered_roundTrip() {
        OrderStatus s = new OrderStatus.Delivered(Instant.parse("2026-05-15T13:00:00Z"));
        String json = OrderStatusSerializer.toJson(s);
        OrderStatus back = OrderStatusSerializer.fromDb(s.statusName(), json);
        assertThat(back).isEqualTo(s);
    }
}
