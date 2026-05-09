package com.ecom.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bind từ {@code auth.jwt.*}. Day 7 sẽ lift lên common-lib (xem note
 * ở product-service JwtProperties).
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String secret, String issuer) {}
