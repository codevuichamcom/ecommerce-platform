package com.ecommerce.notification.consumer;

import com.ecom.common.event.PaymentCompletedV1;
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
 * Consumer cho {@code payment.completed} — gửi email "Payment Confirmed" cho user.
 *
 * <p>Group {@code notification-payment} riêng biệt với order-service consumer
 * (fan-out pattern): cùng 1 event nhưng 2 group nhận độc lập.
 *
 * <p>Cùng idempotency + fire-and-forget pattern như {@link OrderCreatedConsumer}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedConsumer {

    private static final String TEMPLATE_NAME = "payment-completed";

    private final NotificationDeduplicator deduplicator;
    private final NotificationTemplateEngine templateEngine;
    private final NotificationChannel notificationChannel;

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "notification-payment")
    public void onPaymentCompleted(
            PaymentCompletedV1 event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("[payment-completed] received eventId={} orderId={} partition={} offset={} virtual={}",
                event.eventId(), event.orderId(), partition, offset,
                Thread.currentThread().isVirtual());

        if (!deduplicator.tryAcquire(event.eventId())) {
            log.info("[payment-completed] duplicate eventId={} orderId={} → skip",
                    event.eventId(), event.orderId());
            return;
        }

        try {
            String body = templateEngine.render(TEMPLATE_NAME, Map.of(
                    "orderId", event.orderId().toString(),
                    "transactionId", event.transactionId(),
                    "amount", event.amount().toPlainString(),
                    "currency", event.currency()
            ));

            // TODO Day 34: resolve user email bằng orderId → user-service lookup.
            NotificationPayload payload = new NotificationPayload(
                    "payment-confirm+" + event.orderId() + "@shopvn.com",
                    "Thanh toán đơn hàng #" + event.orderId() + " thành công",
                    body
            );

            notificationChannel.send(payload);

            log.info("[payment-completed] dispatched orderId={} txn={} eventId={}",
                    event.orderId(), event.transactionId(), event.eventId());

        } catch (RuntimeException ex) {
            // Day 12: release dedup + propagate → retry topology + DLT.
            deduplicator.release(event.eventId());
            log.error("[payment-completed] dispatch failed eventId={} orderId={} error={} → propagate to retry/DLT",
                    event.eventId(), event.orderId(), ex.getMessage());
            throw ex;
        }
    }
}
