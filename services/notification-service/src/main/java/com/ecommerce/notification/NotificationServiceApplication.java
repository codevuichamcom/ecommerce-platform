package com.ecommerce.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * notification-service entrypoint (Day 8 scaffold — Day 11 full build).
 *
 * <p>Scan thêm {@code com.ecom.common} để pickup KafkaAutoConfiguration
 * + CorrelationIdFilter (filter ko active vì không phải web app, nhưng
 * common bean được scan đúng cách).
 *
 * <p>KHÔNG có {@code @EnableWebMvc} / Web starter — consumer-only.
 */
@SpringBootApplication(scanBasePackages = {"com.ecommerce.notification", "com.ecom.common"})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
