package com.ecom.auth;

import com.ecom.common.autoconfig.SecurityAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * auth-service entrypoint.
 *
 * <p>{@code common-lib} bean (CorrelationIdFilter, GlobalExceptionHandler,
 * JPA auditing) được nhặt qua component scan của package
 * {@code com.ecom.auth} — KHÔNG đủ. Nên import explicit qua
 * spring.factories ở common-lib (CommonAutoConfiguration).
 *
 * <p>Exclude {@link SecurityAutoConfiguration}: auth-service tự define
 * {@code com.ecom.auth.security.JwtAuthenticationFilter} với principal
 * 4-field (tokenVersion), khác common-lib filter. Cùng simple name →
 * cùng bean name → conflict nếu để auto-config kick in.
 */
@SpringBootApplication(
        scanBasePackages = {"com.ecom.auth", "com.ecom.common"},
        exclude = {SecurityAutoConfiguration.class})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
