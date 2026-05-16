package com.ecom.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties bind từ {@code auth.jwt.*} cho verify-only service.
 *
 * <p>Giữ prefix {@code auth.jwt.*} thay vì đổi sang {@code ecom.security.jwt.*}
 * vì 4 application.yml đã dùng prefix này từ Day 2-6 — đổi prefix = breaking
 * change rolling deploy. Day 7 chỉ refactor code, không touch config contract.
 *
 * <p>Auth-service có {@code JwtProperties} riêng với thêm {@code accessTokenTtl}
 * + {@code refreshTokenTtl} — không reuse record này vì auth-service issue
 * token (cần TTL), verify-only chỉ cần secret + issuer.
 */
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtVerifyProperties(String secret, String issuer) {}
