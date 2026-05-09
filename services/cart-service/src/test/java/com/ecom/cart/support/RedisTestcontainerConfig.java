package com.ecom.cart.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spin up Redis 7 ephemeral cho IT. {@code @ServiceConnection} (Boot 3.1+)
 * tự wire {@code spring.data.redis.host/port} vào ApplicationContext —
 * không phải write {@code @DynamicPropertySource} thủ công.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainerConfig {

    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource") // Testcontainers tự shutdown qua Ryuk / JVM hook.
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
