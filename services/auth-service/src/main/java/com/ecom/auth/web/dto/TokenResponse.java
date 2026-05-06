package com.ecom.auth.web.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,        // seconds
        String tokenType       // "Bearer"
) {
    public static TokenResponse bearer(String access, String refresh, long expiresInSec) {
        return new TokenResponse(access, refresh, expiresInSec, "Bearer");
    }
}
