package com.ecom.product.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis container cho cache integration test. {@code @ServiceConnection} 6.1+
 * auto-bind {@code spring.data.redis.host/port} → không cần
 * {@code @DynamicPropertySource} thủ công.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainerConfig {

    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
