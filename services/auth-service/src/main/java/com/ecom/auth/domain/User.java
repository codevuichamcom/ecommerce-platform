package com.ecom.auth.domain;

import com.ecom.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * User aggregate (Layered service — không phải DDD đầy đủ).
 *
 * <p>{@code tokenVersion}: bump để invalidate toàn bộ JWT đã issue.
 * Use case: user đổi password → set tokenVersion += 1 → JWT cũ vẫn
 * verify được signature nhưng filter sẽ reject vì version mismatch.
 *
 * <p>Trade-off: phải DB lookup mỗi request → KHÔNG còn pure stateless.
 * Hybrid: cache user trong Redis (Day 15) để tránh hit DB mỗi call.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    public enum Role {
        USER, ADMIN
    }
}
