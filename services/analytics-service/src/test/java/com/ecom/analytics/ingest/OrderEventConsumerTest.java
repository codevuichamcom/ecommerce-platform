package com.ecom.analytics.ingest;

import com.ecom.analytics.domain.AnalyticsEvent;
import com.ecom.analytics.domain.EventType;
import com.ecom.common.event.OrderCreatedV1;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test (container-free) cho mapping order.created → analytics event.
 * Verify "1 item = 1 document order_placed" + field map đúng (khoá top-products
 * = sku, userId, occurredAt giữ domain time).
 */
class OrderEventConsumerTest {

    @Test
    void onOrderCreated_emitsOneEventPerItem() {
        EventIngestService ingest = mock(EventIngestService.class);
        OrderEventConsumer consumer = new OrderEventConsumer(ingest);

        Instant occurred = Instant.parse("2026-06-03T10:00:00Z");
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderCreatedV1 event = new OrderCreatedV1(
                UUID.randomUUID(), occurred, orderId, userId, "VND", new BigDecimal("30000000"),
                List.of(
                        new OrderCreatedV1.Item("SKU-IP15", 1, new BigDecimal("28000000")),
                        new OrderCreatedV1.Item("SKU-CASE", 2, new BigDecimal("1000000"))));

        consumer.onOrderCreated(event);

        ArgumentCaptor<AnalyticsEvent> captor = ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(ingest, org.mockito.Mockito.times(2)).ingest(captor.capture());

        List<AnalyticsEvent> emitted = captor.getAllValues();
        assertThat(emitted).allSatisfy(ae -> {
            assertThat(ae.getType()).isEqualTo(EventType.ORDER_PLACED);
            assertThat(ae.getOccurredAt()).isEqualTo(occurred);   // domain time giữ nguyên
            assertThat(ae.getUserId()).isEqualTo(userId.toString());
            assertThat(ae.getSessionId()).isNull();               // order event không có session
        });
        assertThat(emitted).extracting(AnalyticsEvent::getProductId)
                .containsExactlyInAnyOrder("SKU-IP15", "SKU-CASE");  // khoá top-products = sku
    }
}
