package com.ecom.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * analytics-service entrypoint (Day 23).
 *
 * <p>Scan thêm {@code com.ecom.common} để pickup KafkaAutoConfiguration
 * (consume {@code order.created}) + CorrelationIdFilter.
 *
 * <p>Đây là service ĐẦU TIÊN dùng MongoDB. Spring Boot tự kích hoạt
 * {@code MongoAutoConfiguration} khi thấy {@code spring-data-mongodb} +
 * {@code spring.data.mongodb.uri} — connect LAZY (không fail startup khi
 * Mongo down, chỉ fail lúc query đầu tiên).
 */
@SpringBootApplication(scanBasePackages = {"com.ecom.analytics", "com.ecom.common"})
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
