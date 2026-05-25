package com.ecommerce.order.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Test {@link OutboxRecorder} đơn vị.
 *
 * <p>Mục đích: verify recorder ghi đúng schema vào outbox repository — KHÔNG
 * publish Kafka (đó là job của relay). Test ngăn AI regenerate nhầm "record"
 * thành "record + publish" → quay lại dual-write.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRecorderTest {

    @Mock OutboxEventRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void record_savesEventWithPendingStatus_andSerializesPayloadAsJson() {
        var r = new OutboxRecorder(repository, objectMapper);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID orderId = UUID.randomUUID();
        var payload = new SamplePayload(orderId, "VND", 100_000L);

        OutboxEvent saved = r.record(
                "Order",
                orderId.toString(),
                "OrderCreatedV1",
                "order.created",
                orderId.toString(),
                payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());

        OutboxEvent entity = captor.getValue();
        assertThat(entity.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entity.getAggregateType()).isEqualTo("Order");
        assertThat(entity.getAggregateId()).isEqualTo(orderId.toString());
        assertThat(entity.getEventType()).isEqualTo("OrderCreatedV1");
        assertThat(entity.getTopic()).isEqualTo("order.created");
        assertThat(entity.getPartitionKey()).isEqualTo(orderId.toString());
        assertThat(entity.getAttempts()).isZero();
        assertThat(entity.getSentAt()).isNull();
        assertThat(entity.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
        // Payload phải là JSON object hợp lệ — KHÔNG quote-wrap string.
        assertThat(entity.getPayload()).startsWith("{").contains("\"currency\":\"VND\"");

        assertThat(saved).isSameAs(entity);
    }

    @Test
    void markSent_setsSentAtAndClearsError() {
        OutboxEvent e = OutboxEvent.of("Order", "x", "T", "topic", "k", "{}");
        e.recordFailure("network timeout");
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).isEqualTo("network timeout");

        e.markSent();

        assertThat(e.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(e.getSentAt()).isNotNull();
        assertThat(e.getLastError()).isNull();
    }

    @Test
    void shouldGiveUp_afterMaxAttempts() {
        OutboxEvent e = OutboxEvent.of("Order", "x", "T", "topic", "k", "{}");
        for (int i = 0; i < 10; i++) e.recordFailure("boom");
        assertThat(e.shouldGiveUp(10)).isTrue();
        assertThat(e.shouldGiveUp(11)).isFalse();
    }

    record SamplePayload(UUID orderId, String currency, long amount) {}
}
