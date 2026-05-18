package com.ecom.common.autoconfig;

import com.ecom.common.messaging.KafkaProperties;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-config Kafka producer/consumer cho toàn monorepo. Bật khi:
 * <ul>
 *   <li>Có {@code spring-kafka} trên classpath ({@link KafkaTemplate}).</li>
 *   <li>{@code app.kafka.enabled=true} — opt-in để notification-service Day 11
 *       chưa cần thì không bị bind bean dư.</li>
 * </ul>
 *
 * <p><b>Producer hardening — quan trọng để tránh data loss (xem issue 08)</b>:
 * <ul>
 *   <li>{@code acks=all}: leader chỉ ack sau khi tất cả ISR (in-sync replicas)
 *       đã ghi → mất leader giữa chừng vẫn không mất message.</li>
 *   <li>{@code enable.idempotence=true}: producer gắn PID + sequence number,
 *       broker dedup khi retry → KHÔNG duplicate trong cùng session. Bật cái
 *       này ép {@code acks=all} + {@code retries=Integer.MAX_VALUE} +
 *       {@code max.in.flight.requests.per.connection ≤ 5} (Kafka ≥ 3.0).</li>
 *   <li>{@code max.in.flight=5}: cap để giữ ordering trong cùng partition khi
 *       retry (lý do: sequence number đảm bảo broker reorder đúng). KHÔNG set
 *       {@code 1} (giảm throughput không cần thiết) cũng KHÔNG set {@code > 5}
 *       (idempotence ép buộc).</li>
 * </ul>
 *
 * <p><b>Consumer hardening</b>:
 * <ul>
 *   <li>{@code enable.auto.commit=false}: app phải commit manual hoặc ack-mode
 *       container — auto-commit là source of "consumed but not processed" data loss.</li>
 *   <li>{@code auto.offset.reset=earliest}: deploy mới subscribe = đọc từ
 *       đầu thay vì miss event lịch sử.</li>
 *   <li>{@code isolation.level=read_committed}: chuẩn bị cho transactional producer
 *       Day 12+; ở Day 8 chỉ producer idempotent (không transactional) thì cờ này
 *       hoạt động transparent.</li>
 * </ul>
 *
 * <p><b>Virtual threads cho listener</b>: container factory bật
 * {@code setVirtualThreads(true)} — listener I/O-bound (DB write, HTTP forward)
 * benefit từ Loom. Pinning gotcha (Day 19): listener method KHÔNG dùng
 * {@code synchronized} block; nếu cần lock → {@link java.util.concurrent.locks.ReentrantLock}.
 *
 * <p>Service muốn override (vd: thêm SASL/SSL cho prod, custom error handler)
 * declare bean cùng type → {@code @ConditionalOnMissingBean} cho qua.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaProperties.class)
@EnableKafka
public class KafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KafkaAdmin kafkaAdmin(KafkaProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        cfg.put(AdminClientConfig.CLIENT_ID_CONFIG, props.getClientId() + "-admin");
        return new KafkaAdmin(cfg);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        cfg.put(ProducerConfig.CLIENT_ID_CONFIG, props.getClientId());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Durability + dedup — KHÔNG để default acks=1.
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

        // KHÔNG add type info header — tránh leak Java class name vào payload,
        // consumer khác ngôn ngữ vẫn parse được JSON thuần.
        cfg.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumerFactory<String, Object> consumerFactory(KafkaProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, props.getConsumerGroup());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // Trust mọi package — chấp nhận vì payload qua type info đã tắt; deser
        // dựa vào @Payload generic type. Prod multi-tenant cần whitelist.
        cfg.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        cfg.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean(name = "kafkaListenerContainerFactory")
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    public KafkaListenerContainerFactory<?> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // Virtual thread cho listener I/O-bound. Spring Kafka 3.4 chưa expose
        // `setVirtualThreads(true)` direct; cấp listener task executor virtual
        // qua SimpleAsyncTaskExecutor.setVirtualThreads(true) (Spring 6.1).
        SimpleAsyncTaskExecutor listenerExecutor = new SimpleAsyncTaskExecutor("kafka-listener-");
        listenerExecutor.setVirtualThreads(true);
        factory.getContainerProperties().setListenerTaskExecutor(listenerExecutor);
        return factory;
    }
}
