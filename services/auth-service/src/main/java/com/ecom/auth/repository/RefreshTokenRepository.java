package com.ecom.auth.repository;

import com.ecom.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomic revocation — returns rows-affected.
     *
     * <p>Caller pattern:
     * <pre>
     *   int updated = repo.revokeIfActive(hash, Instant.now());
     *   if (updated == 0) throw new BusinessException(AUTH_TOKEN_INVALID);
     * </pre>
     * 2 thread cùng gọi → đúng 1 thread thắng (DB UPDATE atomic).
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :now
             WHERE rt.tokenHash = :hash
               AND rt.revokedAt IS NULL
            """)
    int revokeIfActive(@Param("hash") String tokenHash, @Param("now") Instant now);

    /** Revoke toàn bộ token của 1 user (logout-all, password change). */
    @Modifying
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :now
             WHERE rt.userId = :userId
               AND rt.revokedAt IS NULL
            """)
    int revokeAllByUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
