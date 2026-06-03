package com.ecom.analytics.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Day 23 — Testcontainers MongoDB 7 cho integration test. {@code @ServiceConnection}
 * auto-bind {@code spring.data.mongodb.uri} tới container.
 *
 * <p>{@code MongoDBContainer} khởi tạo single-node REPLICA SET (không phải
 * standalone) → multi-document transaction DÙNG được trong test. Lưu ý: docker-compose
 * dev dùng standalone (KHÔNG replica set) nên txn KHÔNG có ở dev — khác biệt
 * test-vs-dev này được ghi rõ ở issues/23-mongodb-no-transaction-trap.md.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MongoTestcontainerConfig {

    @Bean
    @ServiceConnection
    MongoDBContainer mongoDBContainer() {
        return new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
    }
}
