package com.ecom.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT config — bind từ {@code auth.jwt.*} trong application.yml.
 *
 * <p>Record + {@link ConfigurationProperties} = immutable + type-safe.
 * Không cần setter, không cần {@code @Value} rải rác.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {}
