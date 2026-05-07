package com.ecom.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-service entrypoint.
 *
 * <p>Scan cả {@code com.ecom.common} để pick-up bean của common-lib
 * (CorrelationIdFilter, GlobalExceptionHandler, JPA auditing) — pattern
 * giống auth-service.
 */
@SpringBootApplication(scanBasePackages = {"com.ecom.product", "com.ecom.common"})
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
