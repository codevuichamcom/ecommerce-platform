package com.ecommerce.notification.consumer;

import com.ecom.common.event.OrderCreatedV1;
import com.ecom.common.messaging.TopicNames;
import com.ecommerce.notification.channel.NotificationChannel;
import com.ecommerce.notification.idempotency.NotificationDeduplicator;
import com.ecommerce.notification.template.NotificationPayload;
import com.ecommerce.notification.template.NotificationTemplateEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumer cho {@code order.created} — gửi email "Order Confirmed" cho user.
 *
 * <p>Idempotency: Redis SET NX by {@code eventId} (TTL 24h). Kafka retry sẽ
 * gọi lại handler — {@link NotificationDeduplicator#tryAcquire} return false
 * cho duplicate → skip render + dispatch → không spam email.
 *
 * <p>Fire-and-forget: KHÔNG throw exception sau khi render+dispatch fail —
 * Kafka sẽ commit offset, event không vào DLT. Accepted trade-off: email
 * có thể miss khi channel fail, không thể retry. Day 12 sẽ wire DLT cho
 * Kafka deserialization error (poison message), không phải dispatch fail.
 *
 * <p>Replaces Day 8 scaffold {@code OrderEventListener}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private static final String TEMPLATE_NAME = "order-created";

    private final NotificationDeduplicator deduplicator;
    private final NotificationTemplateEngine templateEngine;
    private final NotificationChannel notificationChannel;

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "${spring.application.name}")
    public void onOrderCreated(
            OrderCreatedV1 event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("[order-created] received eventId={} orderId={} partition={} offset={} virtual={}",
                event.eventId(), event.orderId(), partition, offset,
                Thread.currentThread().isVirtual());

        if (!deduplicator.tryAcquire(event.eventId())) {
            log.info("[order-created] duplicate eventId={} orderId={} → skip",
                    event.eventId(), event.orderId());
            return;
        }

        try {
            String body = templateEngine.render(TEMPLATE_NAME, Map.of(
                    "orderId", event.orderId().toString(),
                    "totalAmount", event.totalAmount().toPlainString(),
                    "currency", event.currency(),
                    "itemCount", event.items().size()
            ));

            // TODO Day 34: lấy user email từ user-service (Feign/HTTP Interface).
            // Hiện tại dùng placeholder recipient.
            NotificationPayload payload = new NotificationPayload(
                    "user+" + event.userId() + "@shopvn.com",
                    "Đơn hàng #" + event.orderId() + " đã được xác nhận",
                    body
            );

            notificationChannel.send(payload);

            log.info("[order-created] dispatched orderId={} eventId={}",
                    event.orderId(), event.eventId());

        } catch (Exception ex) {
            // Fire-and-forget: log error, không re-throw.
            // Offset sẽ được commit, event KHÔNG vào retry queue.
            log.error("[order-created] dispatch failed eventId={} orderId={} error={}",
                    event.eventId(), event.orderId(), ex.getMessage(), ex);
        }
    }
}
