package com.ecommerce.notification.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * <h2>Kafka retry topology — Day 12</h2>
 *
 * <p>3 lớp xử lý failure trong consumer:
 * <ol>
 *   <li><b>Retry trong-process</b> với {@link ExponentialBackOff} 1s → 4s → 16s,
 *       max 3 attempts. Listener thread blocked trong khoảng này nhưng vì virtual
 *       thread (Day 8) nên không chiếm OS thread.</li>
 *   <li><b>DLT publish</b> sau khi hết retry — {@link DeadLetterPublishingRecoverer}
 *       gửi sang {@code <originalTopic>.DLT} với cùng key (giữ partition affinity)
 *       + Kafka headers chứa exception class + stacktrace + original topic/offset.</li>
 *   <li><b>Non-retryable shortcut</b> — {@link IllegalArgumentException} +
 *       {@link com.fasterxml.jackson.core.JsonProcessingException}: schema lỗi
 *       hoặc validation fail → retry không bao giờ thành công → DLT NGAY (skip backoff).</li>
 * </ol>
 *
 * <p><b>Tại sao 1s → 4s → 16s</b> (multiplier=4, initial=1s, max=16s):
 * <ul>
 *   <li>Transient failure (network blip, broker leader election) thường resolve
 *       trong 1-5s. Lần retry thứ nhất 1s đủ recover phần lớn.</li>
 *   <li>Multiplier=4 phân tán retry tránh thundering herd khi 1 broker recover.</li>
 *   <li>Cap 16s — sau 3 lần (1+4+16=21s blocked) vẫn fail → bug code/data thật,
 *       DLT để ops triage. KHÔNG retry vô hạn (Day 11 scenario poison message).</li>
 * </ul>
 *
 * <p><b>Tại sao non-retryable cần explicit list</b>: default {@link DefaultErrorHandler}
 * retry MỌI exception. {@link IllegalArgumentException} retry 3 lần vẫn fail —
 * lãng phí 21s + log noise. Add vào {@code addNotRetryableExceptions} để DLT-ngay.
 *
 * <p><b>DLT topic naming</b>: Spring Kafka default suffix {@code .DLT}. Topic
 * {@code order.created} → {@code order.created.DLT}. KHÔNG đổi convention —
 * DLT consumer (xem {@link DltConsumer}) listen pattern {@code .*\.DLT}.
 *
 * <p><b>Override default container factory</b>: bean name {@code kafkaListenerContainerFactory}
 * collide với {@code common-lib/KafkaAutoConfiguration} → {@code @ConditionalOnMissingBean}
 * ở common-lib cho qua, override này thắng. Service khác (order/inventory/payment)
 * tiếp tục dùng default common-lib (chưa cần DLT).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class RetryTopologyConfiguration {

    /**
     * Recoverer publish failed record sang {@code <topic>.DLT} với cùng partition key.
     * Spring Kafka tự đính kèm headers: {@code kafka_dlt-original-topic},
     * {@code kafka_dlt-exception-fqcn}, {@code kafka_dlt-exception-stacktrace}.
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaOperations<String, Object> kafkaTemplate) {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    String dltTopic = record.topic() + ".DLT";
                    log.warn("[DLT] routing record topic={} partition={} offset={} → {} ex={}",
                            record.topic(), record.partition(), record.offset(),
                            dltTopic, ex.getClass().getSimpleName());
                    // Giữ cùng partition để preserve ordering của entity (orderId key).
                    return new org.apache.kafka.common.TopicPartition(dltTopic, record.partition());
                });
    }

    /**
     * Override {@code kafkaListenerContainerFactory} của common-lib để inject error handler.
     */
    @Bean(name = "kafkaListenerContainerFactory")
    public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, Object>
            kafkaListenerContainerFactory(
                    org.springframework.kafka.core.ConsumerFactory<String, Object> consumerFactory,
                    DefaultErrorHandler errorHandler) {

        var factory = new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);

        // Virtual thread cho listener (giữ behavior common-lib).
        var listenerExecutor = new org.springframework.core.task.SimpleAsyncTaskExecutor("kafka-listener-");
        listenerExecutor.setVirtualThreads(true);
        factory.getContainerProperties().setListenerTaskExecutor(listenerExecutor);

        return factory;
    }

    @Bean
    public DefaultErrorHandler defaultErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        // 1s → 4s → 16s; tổng retry budget ~21s/message. Sau 3 lần → DLT.
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 4.0);
        backOff.setMaxInterval(16_000L);
        backOff.setMaxElapsedTime(21_000L); // hard cap toàn bộ retry window

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // Non-retryable: schema/validation fail — retry không recover, DLT ngay.
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class,
                org.springframework.kafka.support.serializer.DeserializationException.class);

        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("[retry] attempt={} topic={} offset={} key={} ex={}",
                        deliveryAttempt, record.topic(), record.offset(), record.key(),
                        ex.getClass().getSimpleName()));

        // Commit offset của recovered (DLT'd) record để KHÔNG block partition.
        handler.setCommitRecovered(true);
        // ACK_MODE phải là MANUAL_IMMEDIATE hoặc default BATCH; setCommitRecovered=true
        // yêu cầu container ack-mode hỗ trợ. Default common-lib không set → BATCH OK.

        return handler;
    }
}
