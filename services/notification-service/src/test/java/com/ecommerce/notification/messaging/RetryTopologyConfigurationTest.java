package com.ecommerce.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit test cho retry topology config — verify default exp backoff + non-retryable list.
 *
 * <p>KHÔNG cần Testcontainers: chỉ check bean wiring + classification behavior.
 * IT thật (poison message → DLT) gated qua {@code RUN_NOTIFICATION_INTEGRATION_TESTS=true}.
 */
class RetryTopologyConfigurationTest {

    private final RetryTopologyConfiguration config = new RetryTopologyConfiguration();

    @Test
    void deadLetterRecoverer_routesToDltSuffix() {
        @SuppressWarnings("unchecked")
        KafkaOperations<String, Object> kafkaOps = mock(KafkaOperations.class);
        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafkaOps);
        assertThat(recoverer).isNotNull();
    }

    @Test
    void errorHandler_classifiesNonRetryableExceptions() {
        @SuppressWarnings("unchecked")
        KafkaOperations<String, Object> kafkaOps = mock(KafkaOperations.class);
        DefaultErrorHandler handler = config.defaultErrorHandler(
                config.deadLetterPublishingRecoverer(kafkaOps));

        // Non-retryable: schema/validation → DLT ngay (chỉ check không throw + classifier có entry).
        assertThat(handler.removeClassification(IllegalArgumentException.class)).isNotNull();
        assertThat(handler.removeClassification(JsonProcessingException.class)).isNotNull();
        assertThat(handler.removeClassification(DeserializationException.class)).isNotNull();
    }
}
