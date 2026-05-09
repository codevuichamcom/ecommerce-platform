package com.ecom.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind từ {@code auth.jwt.*}. Cart-service chỉ verify, không issue token —
 * không cần TTL props. Day 7 sẽ lift lên common-lib.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secret, String issuer) {}
