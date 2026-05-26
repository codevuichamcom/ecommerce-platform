package com.ecommerce.order.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled relay — poll {@code outbox_event} PENDING → publish Kafka → mark SENT.
 *
 * <p><b>Tx boundary</b>: TỪNG event trong batch nằm trong tx riêng
 * ({@code REQUIRES_NEW}) — phân lập failure: 1 event lỗi không rollback
 * cả batch. Trade-off: nhiều round trip hơn, nhưng outbox volume ở Day 13
 * còn thấp (50k orders/day ≈ 0.5 event/s).
 *
 * <p><b>Multi-instance race</b>: {@code fetchBatchForRelay} dùng
 * {@code FOR UPDATE SKIP LOCKED}. Nếu 2 relay cùng tick, mỗi cái lock 1 batch
 * disjoint → no duplicate publish. Đã review query ở
 * {@link OutboxEventRepository#fetchBatchForRelay}.
 *
 * <p><b>Publish wait</b>: dùng {@code .get(timeout)} block tới khi broker ack —
 * vì cần mark SENT chỉ sau khi confirmed delivery. Block ngắn (broker timeout
 * mặc định 30s, ack thực tế ms). Acceptable vì relay là background thread.
 *
 * <p><b>Payload format</b>: parse JSON string → {@link JsonNode} → send.
 * {@code JsonSerializer} của producer sẽ output raw JSON, KHÔNG quote-wrap
 * string. Consumer dùng {@code @Payload OrderCreatedV1} vẫn deserialize đúng.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;
    private final long sendTimeoutMs;

    public OutboxRelay(
            OutboxEventRepository outboxRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.outbox.batch-size:100}") int batchSize,
            @Value("${app.outbox.max-attempts:10}") int maxAttempts,
            @Value("${app.outbox.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /**
     * Tick mỗi 1s. {@code fixedDelay} (không phải fixedRate) — tránh tick
     * chồng lấn nếu batch trước chạy lâu.
     *
     * <p><b>Tx scope</b>: outer tx bao trọn loop để giữ {@code FOR UPDATE
     * SKIP LOCKED} lock trên batch rows suốt thời gian process. Relay
     * instance khác tick cùng lúc sẽ SKIP rows đang lock → không
     * republish trùng. Mỗi {@link #publishOne(OutboxEvent)} là
     * {@code REQUIRES_NEW} → suspend outer tx, mở tx riêng để mark
     * SENT/FAILED, commit ngay, lock vẫn giữ ở outer.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    @Transactional
    public void poll() {
        List<OutboxEvent> batch = outboxRepository.fetchBatchForRelay(
                PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        log.debug("Outbox relay tick — {} pending events picked", batch.size());
        for (OutboxEvent event : batch) {
            try {
                publishOne(event);
            } catch (Exception ex) {
                // publishOne tự xử lý mark FAILED / record retry — catch ở đây
                // chỉ phòng exception leak khiến tick crash.
                log.error("Outbox relay unexpected error id={}", event.getId(), ex);
            }
        }
    }

    /**
     * Process 1 event trong tx riêng. {@code REQUIRES_NEW} để outer
     * {@link #poll()} không bị rollback toàn bộ vì 1 row lỗi.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOne(OutboxEvent event) {
        try {
            JsonNode payloadNode = objectMapper.readTree(event.getPayload());
            kafkaTemplate
                    .send(event.getTopic(), event.getPartitionKey(), payloadNode)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            event.markSent();
            outboxRepository.save(event);
            log.debug("Outbox SENT id={} topic={} key={}",
                    event.getId(), event.getTopic(), event.getPartitionKey());
        } catch (Exception ex) {
            event.recordFailure(ex.getMessage() != null ? ex.getMessage() : ex.toString());
            if (event.shouldGiveUp(maxAttempts)) {
                event.markFailed(ex.getMessage() != null ? ex.getMessage() : ex.toString());
                log.error("Outbox FAILED giving-up id={} attempts={} type={}",
                        event.getId(), event.getAttempts(), event.getEventType(), ex);
            } else {
                log.warn("Outbox publish failed id={} attempts={} — will retry next tick: {}",
                        event.getId(), event.getAttempts(), ex.getMessage());
            }
            outboxRepository.save(event);
        }
    }
}
