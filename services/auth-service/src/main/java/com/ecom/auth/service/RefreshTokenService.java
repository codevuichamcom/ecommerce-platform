package com.ecom.auth.service;

import com.ecom.auth.config.JwtProperties;
import com.ecom.auth.domain.RefreshToken;
import com.ecom.auth.repository.RefreshTokenRepository;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh token lifecycle: issue, validate, rotate, revoke.
 *
 * <p>Plaintext token = base64url(32 random bytes). Lưu DB là SHA-256 hash.
 *
 * <p>Rotation flow (ở /auth/refresh):
 * <ol>
 *   <li>Hash plaintext → lookup row.</li>
 *   <li>{@link RefreshTokenRepository#revokeIfActive} atomic UPDATE.
 *       Nếu rowsAffected == 0 → losers (token đã revoke / không tồn tại).</li>
 *   <li>Issue token mới + insert row mới.</li>
 * </ol>
 *
 * <p>Edge case "reuse detection" (Day 12 sẽ harden): nếu attacker dùng
 * lại token đã revoked → revoke toàn bộ token của user (suspect theft).
 * Day 2 chỉ reject; Day 12 thêm flag + alert.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RAW_BYTES = 32;

    private final RefreshTokenRepository repo;
    private final JwtProperties props;

    /** Generate plaintext + persist hashed row. Trả plaintext cho client. */
    @Transactional
    public String issue(UUID userId, Instant now) {
        String plaintext = generatePlaintext();
        String hash = sha256(plaintext);

        RefreshToken token = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tokenHash(hash)
                .expiresAt(now.plus(props.refreshTokenTtl()))
                .build();
        repo.save(token);
        return plaintext;
    }

    /**
     * Atomic rotate: revoke token cũ, issue token mới. Trả về userId của
     * token cũ (để controller load user + issue access token).
     *
     * @throws BusinessException AUTH_TOKEN_INVALID nếu token không tồn tại,
     *         đã revoke, hoặc hết hạn.
     */
    @Transactional
    public RotationResult rotate(String plaintextOld, Instant now) {
        String hash = sha256(plaintextOld);

        RefreshToken existing = repo.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Refresh token not found"));

        if (!existing.isActive(now)) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Refresh token expired or revoked");
        }

        // Atomic — chỉ 1 caller thắng nếu race.
        int updated = repo.revokeIfActive(hash, now);
        if (updated == 0) {
            // Lost race với refresh khác / logout / revoke-all.
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Refresh token already used");
        }

        String newPlaintext = issue(existing.getUserId(), now);
        return new RotationResult(existing.getUserId(), newPlaintext);
    }

    /** Revoke 1 token (logout endpoint, Day 2 chưa expose). */
    @Transactional
    public void revoke(String plaintext, Instant now) {
        repo.revokeIfActive(sha256(plaintext), now);
    }

    /** Revoke toàn bộ token của user — dùng khi đổi password / suspect theft. */
    @Transactional
    public void revokeAllForUser(UUID userId, Instant now) {
        repo.revokeAllByUser(userId, now);
    }

    public record RotationResult(UUID userId, String newPlaintext) {}

    // ─── Helpers ───────────────────────────────────────────────────

    private String generatePlaintext() {
        byte[] bytes = new byte[RAW_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
