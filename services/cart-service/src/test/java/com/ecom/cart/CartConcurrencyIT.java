package com.ecom.cart;

import com.ecom.cart.domain.CartId;
import com.ecom.cart.service.CartService;
import com.ecom.cart.support.RedisTestcontainerConfig;
import com.ecom.cart.web.dto.CartResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verify HINCRBY atomicity: 100 thread cùng add(sku=A, qty=1) → tổng phải
 * đúng 100, KHÔNG bị lost-update.
 *
 * <p>Đây là test phân biệt implementation đúng (HINCRBY) vs sai
 * (HGET → modify → HSET). Cái sai trên 1 thread vẫn pass mọi unit test
 * thuần — chỉ test này expose ra.
 *
 * <p>Skip default; enable bằng {@code RUN_CART_INTEGRATION_TESTS=true}.
 */
@SpringBootTest
@Import(RedisTestcontainerConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_CART_INTEGRATION_TESTS", matches = "true")
class CartConcurrencyIT {

    private static final int THREADS = 100;
    private static final String SKU = "SKU-HOT";

    @Autowired CartService cartService;
    @Autowired StringRedisTemplate redis;

    private CartId.User cartId;

    @BeforeEach
    void setup() {
        cartId = new CartId.User(UUID.randomUUID());
        redis.delete(cartId.redisKey());
    }

    @Test
    void hundredThreads_addOne_noLostUpdate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CompletableFuture<?>[] futures = new CompletableFuture[THREADS];
            for (int i = 0; i < THREADS; i++) {
                futures[i] = CompletableFuture.runAsync(() -> cartService.addItem(cartId, SKU, 1), pool);
            }
            CompletableFuture.allOf(futures).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdown();
        }

        CartResponse cart = cartService.getCart(cartId);
        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().get(0).qty()).isEqualTo(THREADS);
        assertThat(cart.totalQty()).isEqualTo(THREADS);
    }

    @Test
    void mergeAnonymousIntoUser_sumQuantityPerSku() {
        String anonToken = UUID.randomUUID().toString();
        CartId.Anonymous anon = new CartId.Anonymous(anonToken);
        UUID userId = UUID.randomUUID();
        CartId.User user = new CartId.User(userId);

        cartService.addItem(anon, "SKU-A", 2);
        cartService.addItem(anon, "SKU-B", 1);
        cartService.addItem(user, "SKU-A", 3);   // overlap → expect 5 sau merge
        cartService.addItem(user, "SKU-C", 4);

        CartResponse merged = cartService.merge(anonToken, userId);

        assertThat(merged.items())
                .extracting("sku", "qty")
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("SKU-A", 5),
                        org.assertj.core.api.Assertions.tuple("SKU-B", 1),
                        org.assertj.core.api.Assertions.tuple("SKU-C", 4));

        // Anon key phải DEL sau merge
        assertThat(redis.hasKey(anon.redisKey())).isFalse();
    }
}
