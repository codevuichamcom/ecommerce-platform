package com.ecom.cart.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test cho sealed CartId. Đảm bảo namespace key tách biệt giữa anon
 * và user — điều này quan trọng cho merge logic (SCAN theo namespace).
 */
class CartIdTest {

    @Test
    void anonymous_redisKey_uses_cart_anon_prefix() {
        CartId id = new CartId.Anonymous("abc-123");
        assertThat(id.redisKey()).isEqualTo("cart:anon:abc-123");
    }

    @Test
    void user_redisKey_uses_cart_user_prefix() {
        UUID uid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CartId id = new CartId.User(uid);
        assertThat(id.redisKey()).isEqualTo("cart:user:" + uid);
    }

    @Test
    void anonymous_blankToken_throws() {
        assertThatThrownBy(() -> new CartId.Anonymous("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void user_nullId_throws() {
        assertThatThrownBy(() -> new CartId.User(null))
                .isInstanceOf(NullPointerException.class);
    }
}
