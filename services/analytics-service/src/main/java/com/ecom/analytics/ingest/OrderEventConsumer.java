package com.ecom.analytics.ingest;

import com.ecom.analytics.domain.AnalyticsEvent;
import com.ecom.analytics.domain.EventType;
import com.ecom.common.event.OrderCreatedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Day 23 — consume {@code order.created} làm event nguồn cho stage cuối funnel
 * ({@code order_placed}). Đây là event "đáng tin" nhất: nó là domain event
 * thật từ order-service (qua outbox Day 13 → exactly-once-ish), khác beacon
 * frontend (có thể giả/mất).
 *
 * <p><b>Vì sao chỉ consume order.created mà không consume product/cart?</b>
 * product/cart KHÔNG emit "user viewed/added" — đó là hành vi UI, backend
 * không thấy. Hành vi UI đi qua HTTP beacon ({@link TrackingController}).
 * Backend domain event (order placed) đi qua Kafka. Hai nguồn, một store.
 *
 * <p><b>Idempotent?</b> Không (xem {@link EventIngestService}). Analytics
 * chịu được đếm đúp nhỏ. KHÔNG re-throw để tránh DLT storm — order.created
 * đã exactly-once ở producer; lỗi map ở đây log + bỏ qua (event analytics mất
 * 1 cái không vỡ business).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final EventIngestService ingestService;

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "${spring.application.name}")
    public void onOrderCreated(OrderCreatedV1 event) {
        log.debug("order.created → analytics event orderId={} userId={}", event.orderId(), event.userId());

        // Order có nhiều item → ghi 1 event order_placed PER item để
        // top-products đếm được sản phẩm nào bán chạy (group theo productId).
        // Mỗi item là 1 document riêng (schemaless, atomic single-write).
        for (OrderCreatedV1.Item item : event.items()) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", event.orderId().toString());
            payload.put("sku", item.sku());
            payload.put("quantity", item.quantity());
            payload.put("unitPrice", item.unitPrice());
            payload.put("currency", event.currency());

            // productId: order event mang sku (không mang productId). Dùng sku
            // làm khoá top-products — analytics nhóm theo sku là đủ (sku ↔ product
            // 1-1). Để productId null, đưa sku vào productId slot cho aggregation
            // đồng nhất với beacon (beacon gửi productId; ở đây ta dùng sku-as-key).
            AnalyticsEvent ae = new AnalyticsEvent(
                    EventType.ORDER_PLACED,
                    event.occurredAt(),
                    null,                              // sessionId — order event không có
                    event.userId().toString(),
                    item.sku(),                        // productId slot = sku (khoá top-products)
                    payload);
            ingestService.ingest(ae);
        }
    }
}
