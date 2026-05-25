package com.ecommerce.order.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test {@link OutboxRelay} đơn vị.
 *
 * <p>Verify 3 invariant:
 * <ol>
 *   <li>Empty batch → KHÔNG publish (no waste tick).</li>
 *   <li>Publish OK → markSent + save (status SENT, sentAt set).</li>
 *   <li>Publish fail → recordFailure (attempts++, status PENDING) — retry tick sau.</li>
 *   <li>Vượt maxAttempts → markFailed (status FAILED — manual triage).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock OutboxEventRepository repository;
    @SuppressWarnings("unchecked")
    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    ObjectMapper objectMapper;
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        relay = new OutboxRelay(repository, kafkaTemplate, objectMapper, 100, 10, 5000);
    }

    @Test
    void poll_emptyBatch_doesNothing() {
        when(repository.fetchBatchForRelay(any(PageRequest.class))).thenReturn(List.of());

        relay.poll();

        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any());
        verify(repository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void publishOne_success_marksSent() {
        OutboxEvent event = OutboxEvent.of(
                "Order", "order-1", "OrderCreatedV1",
                "order.created", "key-1",
                "{\"orderId\":\"order-1\"}");
        when(kafkaTemplate.send(eq("order.created"), eq("key-1"), any(JsonNode.class)))
                .thenReturn(completedSendResult("order.created", 0, 42L));

        relay.publishOne(event);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        verify(repository, times(1)).save(event);
    }

    @Test
    void publishOne_failure_recordsAttemptAndKeepsPending() {
        OutboxEvent event = OutboxEvent.of(
                "Order", "order-2", "OrderCreatedV1",
                "order.created", "key-2",
                "{\"orderId\":\"order-2\"}");
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(eq("order.created"), eq("key-2"), any(JsonNode.class)))
                .thenReturn(failed);

        relay.publishOne(event);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING); // sẽ retry
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker down");
        verify(repository).save(event);
    }

    @Test
    void publishOne_exceededMaxAttempts_marksFailed() {
        OutboxEvent event = OutboxEvent.of(
                "Order", "order-3", "OrderCreatedV1",
                "order.created", "key-3", "{}");
        // Pre-fail 9 lần — lần này là lần thứ 10 → giveUp.
        for (int i = 0; i < 9; i++) event.recordFailure("prior");
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("still down"));
        when(kafkaTemplate.send(eq("order.created"), eq("key-3"), any(JsonNode.class)))
                .thenReturn(failed);

        relay.publishOne(event);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(10);
        verify(repository).save(event);
    }

    @Test
    void poll_batch_publishesEachEventIndividually() {
        OutboxEvent e1 = OutboxEvent.of("Order", "1", "T", "topic", "k1", "{}");
        OutboxEvent e2 = OutboxEvent.of("Order", "2", "T", "topic", "k2", "{}");
        when(repository.fetchBatchForRelay(any(PageRequest.class))).thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .thenReturn(completedSendResult("topic", 0, 1L));

        relay.poll();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq("topic"), keyCaptor.capture(), any(JsonNode.class));
        assertThat(keyCaptor.getAllValues()).containsExactly("k1", "k2");
    }

    private static CompletableFuture<SendResult<String, Object>> completedSendResult(
            String topic, int partition, long offset) {
        var record = new ProducerRecord<String, Object>(topic, "k", "{}");
        var meta = new RecordMetadata(new TopicPartition(topic, partition),
                offset, 0, System.currentTimeMillis(), 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(record, meta));
    }
}
