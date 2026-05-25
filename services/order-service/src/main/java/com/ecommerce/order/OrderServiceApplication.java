package com.ecommerce.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order-service entrypoint.
 *
 * <p>Scan thêm {@code com.ecom.common} để pickup CorrelationIdFilter,
 * GlobalExceptionHandler, JPA auditing — pattern thống nhất với
 * auth/product/inventory/cart-service.
 *
 * <p>KHÔNG bật {@code @EnableRetry} ở Day 6 — order placement không phải
 * idempotent retry-safe ở cấp ứng dụng (sẽ overcount reservation). Day 12
 * sẽ thêm Resilience4j circuit breaker cho RestClient call inventory/cart.
 */
@SpringBootApplication(scanBasePackages = {"com.ecommerce.order", "com.ecom.common"})
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
