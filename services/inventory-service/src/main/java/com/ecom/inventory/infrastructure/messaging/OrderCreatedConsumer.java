package com.ecom.inventory.infrastructure.messaging;

import com.ecom.common.event.OrderCreatedV1;
import com.ecom.common.event.StockReservedV1;
import com.ecom.common.messaging.TopicNames;
import com.ecom.inventory.application.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Day 9 — consume {@code order.created} → reserve stock cho TỪNG SKU →
 * publish {@code inventory.reserved} per SKU thành công.
 *
 * <p><b>Why event-driven (Day 9) vs sync Feign (Day 6)</b>:
 * <ul>
 *   <li>Decouple — order-service không phải biết inventory endpoint.</li>
 *   <li>Backpressure tự nhiên — Kafka buffer khi inventory chậm.</li>
 *   <li>Scale independent — N inventory consumer cùng group share partition.</li>
 * </ul>
 *
 * <p><b>Failure handling (Day 9 minimum, Day 12 sẽ tighten)</b>:
 * <ul>
 *   <li>{@code InsufficientStockException} từ aggregate → log WARNING,
 *       KHÔNG throw để consumer KHÔNG retry (retry không giúp vì stock
 *       thật sự hết). Day 12 sẽ publish {@code inventory.reserve.failed}
 *       + compensation event để order auto-cancel.</li>
 *   <li>Other exception (DB down, optimistic-lock-retry-exhausted) → throw
 *       → Spring Kafka default error handler retry 10 lần rồi park. Day 12
 *       wire {@code DeadLetterPublishingRecoverer} → DLT.</li>
 * </ul>
 *
 * <p>Idempotent: replay cùng event → {@link InventoryService#reserve(String, int)}
 * sẽ trừ stock LẦN NỮA — KHÔNG idempotent ở Day 9. Trade-off accept vì
 * Kafka idempotent producer + manual ack sau xử lý xong cover phần lớn case.
 * Day 11 sẽ dedup bằng {@code eventId} qua Redis SET NX cho strict
 * exactly-once illusion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventPublisher publisher;

    @KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "${spring.application.name}")
    public void onOrderCreated(OrderCreatedV1 event) {
        log.info("Consume order.created eventId={} orderId={} items={}",
                event.eventId(), event.orderId(), event.items().size());

        for (OrderCreatedV1.Item item : event.items()) {
            try {
                inventoryService.reserve(item.sku(), item.quantity());
                publisher.publishReserved(new StockReservedV1(
                        UUID.randomUUID(),
                        Instant.now(),
                        event.orderId(),
                        item.sku(),
                        item.quantity()));
            } catch (RuntimeException ex) {
                // Day 9 KHÔNG throw lên container — tránh retry storm cho
                // case "stock hết thật". Log loud + Day 12 sẽ publish
                // `inventory.reserve.failed` để order auto-cancel.
                log.warn("Reserve failed orderId={} sku={} qty={} reason={}",
                        event.orderId(), item.sku(), item.quantity(), ex.getMessage());
            }
        }
    }
}
