package com.ecom.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * inventory-service entrypoint.
 *
 * <p>{@code @EnableRetry} bật Spring Retry AOP cho {@code @Retryable}
 * trên application service (xem InventoryService.reserve). KHÔNG đặt ở
 * common-lib auto-config vì không phải service nào cũng cần retry.
 *
 * <p>Scan thêm {@code com.ecom.common} để pickup CorrelationIdFilter,
 * GlobalExceptionHandler, JPA auditing — pattern thống nhất với
 * auth/product-service.
 */
@SpringBootApplication(scanBasePackages = {"com.ecom.inventory", "com.ecom.common"})
@EnableRetry
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
