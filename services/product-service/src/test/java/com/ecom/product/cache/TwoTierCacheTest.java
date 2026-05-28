package com.ecom.product.cache;

import com.ecom.product.config.cache.CacheConfig;
import com.ecom.product.config.cache.ProbabilisticExpiringCache;
import com.ecom.product.config.cache.TwoTierCache;
import com.ecom.product.support.PostgresTestcontainerConfig;
import com.ecom.product.support.RedisTestcontainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache hit/miss path qua 2-tier.
 *
 * <p>Test idea:
 * <ol>
 *   <li>put(K, V) → L1 + L2 cùng hold giá trị.</li>
 *   <li>Clear L1 (Caffeine), get(K) → L2 hit, L1 backfill.</li>
 *   <li>Evict(K) → L1 + L2 cùng mất.</li>
 * </ol>
 *
 * <p>Test gated bằng {@code RUN_PRODUCT_INTEGRATION_TESTS=true} — local
 * Windows skip Testcontainers (xem product-service build.gradle.kts).
 */
@SpringBootTest
@Import({PostgresTestcontainerConfig.class, RedisTestcontainerConfig.class})
@EnabledIfEnvironmentVariable(named = "RUN_PRODUCT_INTEGRATION_TESTS", matches = "true")
class TwoTierCacheTest {

    @Autowired CacheManager cacheManager;

    private TwoTierCache twoTier;

    @BeforeEach
    void resolveTwoTier() throws Exception {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_PRODUCT_BY_ID);
        assertThat(cache).isInstanceOf(ProbabilisticExpiringCache.class);
        // Unwrap PEC → TwoTierCache delegate.
        Field delegate = ProbabilisticExpiringCache.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        twoTier = (TwoTierCache) delegate.get(cache);
        twoTier.clear();
    }

    @Test
    @DisplayName("put → L1 hit (no L2 round trip)")
    void putThenGet_l1Hit() {
        long missesBefore = twoTier.l1MissCount();
        twoTier.put("k1", "v1");

        Cache.ValueWrapper wrapper = twoTier.get("k1");
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.get()).isEqualTo("v1");
        assertThat(twoTier.l1HitCount()).isPositive();
        assertThat(twoTier.l1MissCount()).isEqualTo(missesBefore);
    }

    @Test
    @DisplayName("L1 clear + get → L2 hit + L1 backfill")
    void l1ClearedThenGet_l2HitAndBackfill() throws Exception {
        twoTier.put("k2", "v2");

        // Clear L1 via reflection (Caffeine instance).
        Field l1Field = TwoTierCache.class.getDeclaredField("l1");
        l1Field.setAccessible(true);
        com.github.benmanes.caffeine.cache.Cache<?, ?> l1 =
                (com.github.benmanes.caffeine.cache.Cache<?, ?>) l1Field.get(twoTier);
        l1.invalidateAll();

        long l2HitsBefore = twoTier.l2HitCount();
        Cache.ValueWrapper wrapper = twoTier.get("k2");

        assertThat(wrapper).isNotNull();
        assertThat(wrapper.get()).isEqualTo("v2");
        assertThat(twoTier.l2HitCount()).isGreaterThan(l2HitsBefore);
        // L1 đã backfill → lookup tiếp theo phải L1 hit.
        long l1HitsBefore = twoTier.l1HitCount();
        twoTier.get("k2");
        assertThat(twoTier.l1HitCount()).isGreaterThan(l1HitsBefore);
    }

    @Test
    @DisplayName("evict → both L1 + L2 cleared")
    void evict_bothTiersCleared() {
        twoTier.put("k3", "v3");
        twoTier.evict("k3");

        Cache.ValueWrapper wrapper = twoTier.get("k3");
        assertThat(wrapper).isNull();
    }
}
