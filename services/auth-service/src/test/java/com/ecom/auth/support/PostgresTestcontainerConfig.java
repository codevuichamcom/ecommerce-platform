package com.ecom.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * @ServiceConnection — Spring Boot 3.1+ tự wire datasource từ container,
 * không cần @DynamicPropertySource manual.
 *
 * <p>Container reuse: 1 instance / JVM (singleton @Bean) — tiết kiệm
 * 5-10s startup vs spin-up per-class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainerConfig {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource") // container managed bởi Spring lifecycle
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("auth_db")
                .withUsername("ecom")
                .withPassword("ecom");
    }
}
