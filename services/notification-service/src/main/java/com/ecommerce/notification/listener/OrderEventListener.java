package com.ecommerce.notification.listener;

import com.ecom.common.event.OrderCreatedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Day 8 scaffold — chỉ log payload để verify consumer wiring (deserializer,
 * group rebalance, virtual thread). Day 11 sẽ:
 * <ul>
 *   <li>Idempotent handler (dedup by {@code eventId} qua Redis SET NX).</li>
 *   <li>Render Thymeleaf template + push qua email adapter.</li>
 *   <li>Manual ack + DLT (Day 12 wire Resilience4j retry).</li>
 * </ul>
 *
 * <p>{@code groupId} = {@code spring.application.name} → mỗi service instance
 * chung group, partition chia đều khi scale horizontally. Đổi group =
 * fan-out (cùng event nhiều consumer độc lập đọc).
 *
 * <p>Container factory: pickup default {@code kafkaListenerContainerFactory}
 * từ {@link com.ecom.common.autoconfig.KafkaAutoConfiguration} — virtual thread bật.
 */
@Slf4j
@Component
public class OrderEventListener {

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "${spring.application.name}")
    public void onOrderCreated(OrderCreatedV1 event,
                               @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                               @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("[Day 8 demo] Received order.created partition={} offset={} eventId={} orderId={} total={} {} thread={} virtual={}",
                partition,
                offset,
                event.eventId(),
                event.orderId(),
                event.totalAmount(),
                event.currency(),
                Thread.currentThread().getName(),
                Thread.currentThread().isVirtual());
        // TODO Day 11: render template + dispatch email/sms/push.
    }
}
