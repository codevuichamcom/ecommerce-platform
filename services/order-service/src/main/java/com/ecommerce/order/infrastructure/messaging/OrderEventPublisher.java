package com.ecommerce.order.infrastructure.messaging;

import com.ecom.common.event.OrderCreatedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publish {@link OrderCreatedV1} lên topic {@code order.created}.
 *
 * <p><b>Key strategy</b>: {@code orderId} — đảm bảo TẤT CẢ event của cùng
 * 1 order rơi vào CÙNG partition → consumer xử theo đúng thứ tự ({@code created}
 * trước {@code cancelled} trước {@code paid}). Trade-off: nếu 1 order hot
 * (vd test load) thì partition skew, nhưng workload thực không có "order
 * hot" — order là 1-shot entity.
 *
 * <p><b>Day 13 update</b>: Production path KHÔNG dùng class này nữa —
 * {@code PlaceOrderUseCase} ghi event vào outbox table, {@code OutboxRelay}
 * publish Kafka qua {@code KafkaTemplate} trực tiếp. Class này còn lại chỉ
 * phục vụ {@code DebugController#publishMock} (Day 8 demo bypass outbox).
 * Có thể xóa khi DebugController bị remove.
 *
 * <p>{@code send()} trả {@code CompletableFuture} — KHÔNG {@code .get()}
 * block ở thread caller. Wire callback log + metric, để producer batch tiếp.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CompletableFuture<SendResult<String, Object>> publishOrderCreated(OrderCreatedV1 event) {
        String key = event.orderId().toString();
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(TopicNames.ORDER_CREATED, key, event);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // Day 13 outbox sẽ là source of truth — event sẽ retry tự động.
                // Hiện tại chỉ log để alert dashboard pick up.
                log.error("Failed to publish order.created eventId={} orderId={}",
                        event.eventId(), event.orderId(), ex);
            } else {
                log.debug("Published order.created topic={} partition={} offset={} eventId={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.eventId());
            }
        });
        return future;
    }
}
