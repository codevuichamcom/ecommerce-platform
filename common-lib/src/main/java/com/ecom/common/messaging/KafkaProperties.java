package com.ecom.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.kafka.*} properties. Đặt prefix {@code app.kafka} thay vì
 * dùng {@code spring.kafka} mặc định để TÁCH BIỆT config "có dùng Kafka
 * không" với "Spring Kafka chi tiết". Service không cần Kafka không set
 * {@code app.kafka.enabled} → auto-config skip (xem {@link KafkaAutoConfiguration}).
 *
 * <p>Service vẫn có thể override Spring Kafka raw properties ở
 * {@code spring.kafka.*} nếu cần (vd: thêm sasl/ssl prod).
 */
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaProperties {

    /** Tắt/bật toàn bộ Kafka auto-config. Default false để KHÔNG kéo tự động ở service không cần. */
    private boolean enabled = false;

    /** Comma-separated broker list (vd: {@code localhost:9092}). */
    private String bootstrapServers = "localhost:9092";

    /** Producer client.id — gắn vào metric/log để trace producer instance. */
    private String clientId = "ecom-producer";

    /** Consumer group default — service nên override per-listener nếu cần. */
    private String consumerGroup = "ecom-default";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
}
