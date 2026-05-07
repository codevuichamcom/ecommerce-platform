package com.ecom.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind từ {@code auth.jwt.*}. Giống auth-service nhưng product-service
 * KHÔNG cần TTL — chỉ verify, không issue. Day 7 refactor lên common-lib
 * để khỏi duplicate properties class này giữa các service.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secret, String issuer) {}
