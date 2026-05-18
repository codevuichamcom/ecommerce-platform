package com.ecommerce.payment.infrastructure.messaging;

import com.ecom.common.event.PaymentCompletedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publish {@link PaymentCompletedV1} lên topic {@code payment.completed}.
 *
 * <p>Key strategy: {@code orderId} (KHÔNG phải paymentId) — đảm bảo event
 * cho cùng order rơi vào cùng partition → order-service consumer xử lý
 * theo thứ tự đúng (created → reserved → paid). Trade-off: nếu 1 order
 * có nhiều PaymentIntent (user retry sau fail) → ordering theo orderId
 * vẫn đúng, chỉ event được publish là CAPTURED cuối cùng.
 *
 * <p>Day 13 outbox sẽ là source of truth, KHÔNG publish trực tiếp trong
 * use case. Hôm nay log warn nếu publish fail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPaymentCompleted(PaymentCompletedV1 event) {
        String key = event.orderId().toString();
        kafkaTemplate.send(TopicNames.PAYMENT_COMPLETED, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish payment.completed eventId={} orderId={}",
                                event.eventId(), event.orderId(), ex);
                    } else {
                        log.debug("Published payment.completed partition={} offset={} eventId={}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                event.eventId());
                    }
                });
    }
}
