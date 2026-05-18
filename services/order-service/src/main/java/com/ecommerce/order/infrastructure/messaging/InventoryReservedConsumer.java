package com.ecommerce.order.infrastructure.messaging;

import com.ecom.common.event.StockReservedV1;
import com.ecom.common.messaging.TopicNames;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consume {@code inventory.reserved} → update {@code Order.reservationStatus}
 * = RESERVED. Đóng vòng eventual consistency mà Day 6 sync làm trong cùng
 * transaction.
 *
 * <p><b>Idempotent</b>: nhận lại cùng event (Kafka at-least-once, broker
 * có thể redeliver khi consumer rebalance) → {@link Order#markReserved()}
 * no-op nếu đã RESERVED. KHÔNG cần dedup bằng Redis cho use case này.
 *
 * <p><b>Partial reservation</b>: Day 9 đơn giản hóa — inventory publish
 * {@code inventory.reserved} cho TỪNG SKU, order chỉ cần ÍT NHẤT 1 event
 * để chuyển RESERVED. Đây là simplification — Day 12 sẽ tighten: track
 * remaining SKUs + chỉ RESERVED khi đủ TẤT CẢ. Hôm nay accept vì single-SKU
 * order phổ biến + alert SLI sẽ catch edge case.
 *
 * <p>Trace propagation: Spring Kafka 3.x auto-propagate `traceparent` qua
 * headers khi {@code spring.kafka.listener.observation-enabled=true}.
 * Span "kafka.consume" tự link vào span "kafka.send" của order-service →
 * Zipkin UI thấy trace tree 1 mạch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReservedConsumer {

    private final OrderRepository orderRepository;

    @KafkaListener(topics = TopicNames.INVENTORY_RESERVED, groupId = "${spring.application.name}")
    @Transactional
    public void onInventoryReserved(StockReservedV1 event) {
        orderRepository.findById(event.orderId()).ifPresentOrElse(
                order -> {
                    order.markReserved();
                    orderRepository.save(order);
                    log.info("Order {} reservation -> RESERVED (sku={} qty={})",
                            event.orderId(), event.sku(), event.quantity());
                },
                () -> log.warn("Received inventory.reserved for unknown orderId={} (eventId={}); ignoring",
                        event.orderId(), event.eventId()));
    }
}
