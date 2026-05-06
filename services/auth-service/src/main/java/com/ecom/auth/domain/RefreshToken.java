package com.ecom.auth.domain;

import com.ecom.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token record.
 *
 * <p>QUAN TRỌNG: {@code tokenHash} là SHA-256 hash của plaintext token.
 * KHÔNG bao giờ lưu plaintext (xem issues/02-token-refresh-race-condition.md).
 *
 * <p>{@code revokedAt = null} → token còn valid. Atomic rotation:
 * <pre>
 *   UPDATE refresh_tokens
 *   SET revoked_at = NOW()
 *   WHERE token_hash = ? AND revoked_at IS NULL
 * </pre>
 * Update count = 1 → caller thắng race; = 0 → losers, reject request.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RefreshToken extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
