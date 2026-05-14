package com.ecommerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared secret JWT properties — issued bởi auth-service, verify ở
 * order-service. Day 8 sẽ thay bằng service token / mTLS.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secret, String issuer) {}
