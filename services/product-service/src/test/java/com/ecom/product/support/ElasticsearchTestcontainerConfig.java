package com.ecom.product.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Day 22 — Testcontainers ES 8 cho integration test. {@code @ServiceConnection}
 * (Boot 3.1+) auto-bind {@code spring.elasticsearch.uris} tới container — KHÔNG
 * cần set property tay.
 *
 * <p>{@code xpack.security.enabled=false}: tắt TLS + auth để @ServiceConnection
 * connect plaintext (giống docker-compose dev). 8.x default bật security →
 * phải tắt nếu không test phải handle cert + password.
 *
 * <p>Image pin {@code 8.15.3} khớp ES Java client 8.15.5 (Boot 3.4.5 BOM) —
 * lệch major version client/server = API incompatibility.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ElasticsearchTestcontainerConfig {

    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearchContainer() {
        return new ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
    }
}
