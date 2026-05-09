package com.ecom.cart.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 2 nguồn của 1 cart: anonymous (browser cookie/header X-Cart-Token) hoặc
 * authenticated user (JWT). Sealed → exhaustive switch khi xử lý merge.
 *
 * <p>Redis key namespace tách biệt:
 * <ul>
 *   <li>{@code cart:anon:{token}} — chỉ tồn tại đến khi user login (merge)</li>
 *   <li>{@code cart:user:{userId}} — sống 7 ngày sau mutation cuối</li>
 * </ul>
 *
 * <p>Tách namespace giúp:
 * <ol>
 *   <li>Không nhầm anon-token (random UUID) với userId UUID khi grep Redis.</li>
 *   <li>Có thể scan/expire theo namespace nếu cần purge anonymous riêng.</li>
 * </ol>
 */
public sealed interface CartId {

    String redisKey();

    record Anonymous(String token) implements CartId {
        public Anonymous {
            Objects.requireNonNull(token, "anon token");
            if (token.isBlank()) throw new IllegalArgumentException("anon token blank");
        }

        @Override
        public String redisKey() {
            return "cart:anon:" + token;
        }
    }

    record User(UUID userId) implements CartId {
        public User {
            Objects.requireNonNull(userId, "userId");
        }

        @Override
        public String redisKey() {
            return "cart:user:" + userId;
        }
    }
}
