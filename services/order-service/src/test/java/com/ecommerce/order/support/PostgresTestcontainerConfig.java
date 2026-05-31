package com.ecommerce.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres Testcontainer cho order-service IT. Cùng pattern product-service
 * (Day 15) — {@code @ServiceConnection} auto-wire datasource, Flyway chạy
 * migration thật (V1..V3) khi context start.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainerConfig {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("order_db")
                .withUsername("ecom")
                .withPassword("ecom");
    }
}
