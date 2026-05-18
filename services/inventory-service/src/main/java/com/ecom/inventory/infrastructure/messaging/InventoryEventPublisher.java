package com.ecom.inventory.infrastructure.messaging;

import com.ecom.common.event.StockReservedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publish {@code inventory.reserved} sau khi reserve thành công cho 1 SKU
 * trong order. Key=orderId để giữ ordering per-order (cùng pattern
 * `OrderEventPublisher` Day 8).
 *
 * <p>Trace context propagate qua Kafka headers tự động — Spring Kafka 3.x
 * khi {@code template.observation-enabled=true} sẽ wrap send vào Observation
 * scope, OTel propagator inject {@code traceparent} header.
 *
 * <p>Day 9 publish per-SKU (1 order N items = N events). Trade-off:
 * <ul>
 *   <li>Pro: order-service không cần collect, cứ thấy event là markReserved
 *       (idempotent).</li>
 *   <li>Con: nếu order có 5 SKU mà 4 reserve OK + 1 fail → 4 event đã publish
 *       order chuyển RESERVED nhưng thực tế đang trong partial state. Day 12
 *       sẽ wire `inventory.reserve.failed` + compensation.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishReserved(StockReservedV1 event) {
        String key = event.orderId().toString();
        kafkaTemplate.send(TopicNames.INVENTORY_RESERVED, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Dual-write — order đã được reserve ở DB inventory nhưng
                        // event publish fail → order-service sẽ stuck PENDING.
                        // Day 13 outbox sẽ trả debt.
                        log.error("Failed publish inventory.reserved eventId={} orderId={} sku={}",
                                event.eventId(), event.orderId(), event.sku(), ex);
                    } else {
                        log.debug("Published inventory.reserved partition={} offset={} eventId={}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                event.eventId());
                    }
                });
    }
}
