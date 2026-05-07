package com.ecom.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auth-service entrypoint.
 *
 * <p>{@code common-lib} bean (CorrelationIdFilter, GlobalExceptionHandler,
 * JPA auditing) được nhặt qua component scan của package
 * {@code com.ecom.auth} — KHÔNG đủ. Nên import explicit qua
 * spring.factories ở common-lib (CommonAutoConfiguration).
 */
@SpringBootApplication(scanBasePackages = {"com.ecom.auth", "com.ecom.common"})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
