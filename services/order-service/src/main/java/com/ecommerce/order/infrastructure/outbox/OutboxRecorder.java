package com.ecommerce.order.infrastructure.outbox;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Ghi domain event vào outbox table.
 *
 * <p><b>Hợp đồng quan trọng</b>: caller PHẢI gọi method này trong cùng
 * {@code @Transactional} với business write (vd {@code orderRepository.save()}).
 * Đó chính là điểm khiến outbox pattern atomic — DB commit hoặc rollback
 * GỘP cả Order + outbox row.
 *
 * <p><b>Không publish Kafka ở đây</b> — đó là việc của {@link OutboxRelay}
 * (scheduled, ngoài tx). Nếu publish trong cùng method này là defeat purpose
 * (quay lại dual-write).
 *
 * <p>Serialize JSON ở recorder thay vì relay vì payload schema biết tại
 * thời điểm record. Nếu lỗi serialize → fail-fast trong business tx
 * (tốt hơn fail muộn ở relay).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxEvent record(
            String aggregateType,
            String aggregateId,
            String eventType,
            String topic,
            String partitionKey,
            Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.error("Outbox payload serialize failed type={} aggregateId={}",
                    eventType, aggregateId, ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Failed to serialize outbox payload");
        }
        OutboxEvent event = OutboxEvent.of(
                aggregateType, aggregateId, eventType, topic, partitionKey, json);
        OutboxEvent saved = outboxRepository.save(event);
        log.debug("Outbox recorded id={} type={} aggregateId={}",
                saved.getId(), eventType, aggregateId);
        return saved;
    }
}
