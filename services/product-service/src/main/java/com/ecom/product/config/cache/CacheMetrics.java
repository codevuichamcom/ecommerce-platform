package com.ecom.product.config.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Bind hit/miss counter của {@link TwoTierCache} + {@link ProbabilisticExpiringCache}
 * vào Micrometer.
 *
 * <h3>Metrics expose</h3>
 * <ul>
 *   <li>{@code product_cache_hits_total{tier="l1|l2", cache="<name>"}}</li>
 *   <li>{@code product_cache_misses_total{tier="l1|l2", cache="<name>"}}</li>
 *   <li>{@code product_cache_l1_size{cache="<name>"}}</li>
 *   <li>{@code product_cache_xfetch_early_refresh_total{cache="<name>"}}</li>
 * </ul>
 *
 * <p>Tại sao gauge thay vì counter cho hit/miss? — Counter ở Micrometer phải
 * tăng monotonic; LongAdder của ta tăng monotonic OK, nhưng dùng Gauge với
 * supplier function đơn giản hơn (không phải intercept mỗi increment).
 * Prometheus scrape gauge → tính rate qua PromQL {@code rate(...[1m])}.
 *
 * <p>Day 20 sẽ build Grafana board hit ratio = hits / (hits + misses)
 * windowed 5m, alert nếu ratio < 0.7 trong 10m liên tiếp.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheMetrics {

    private final CacheManager cacheManager;
    private final MeterRegistry registry;

    @PostConstruct
    void bind() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache == null) continue;

            // Unwrap qua các tầng decorator: ProbabilisticExpiringCache → TwoTierCache.
            // Spring Cache không có API "unwrap" → reflect qua getNativeCache()
            // KHÔNG dùng được vì nó trả Caffeine. Cần access trực tiếp delegate
            // → cast theo type chúng ta build ở CacheConfig.
            TwoTierCache twoTier = findTwoTier(cache);
            ProbabilisticExpiringCache xfetch = (cache instanceof ProbabilisticExpiringCache pc) ? pc : null;

            if (twoTier != null) {
                Tags base = Tags.of("cache", name);

                Gauge.builder("product.cache.hits", twoTier, TwoTierCache::l1HitCount)
                        .tags(base.and("tier", "l1"))
                        .description("L1 (Caffeine) cache hits")
                        .register(registry);
                Gauge.builder("product.cache.misses", twoTier, TwoTierCache::l1MissCount)
                        .tags(base.and("tier", "l1"))
                        .description("L1 (Caffeine) cache misses")
                        .register(registry);
                Gauge.builder("product.cache.hits", twoTier, TwoTierCache::l2HitCount)
                        .tags(base.and("tier", "l2"))
                        .description("L2 (Redis) cache hits")
                        .register(registry);
                Gauge.builder("product.cache.misses", twoTier, TwoTierCache::l2MissCount)
                        .tags(base.and("tier", "l2"))
                        .description("L2 (Redis) cache misses")
                        .register(registry);
                Gauge.builder("product.cache.l1.size", twoTier, TwoTierCache::l1EstimatedSize)
                        .tags(base)
                        .description("L1 (Caffeine) estimated entry count")
                        .register(registry);

                log.info("Bound 2-tier cache metrics: cache={}", name);
            }

            if (xfetch != null) {
                Gauge.builder("product.cache.xfetch.early.refresh", xfetch,
                                ProbabilisticExpiringCache::earlyRefreshCount)
                        .tags(Tags.of("cache", name))
                        .description("Number of times XFetch triggered probabilistic early refresh")
                        .register(registry);
            }
        }
    }

    private TwoTierCache findTwoTier(Cache cache) {
        if (cache instanceof TwoTierCache t) return t;
        if (cache instanceof ProbabilisticExpiringCache pc) {
            // Native cache của PEC là delegate (xem PEC.getNativeCache → trả native
            // của TwoTier = L1 Caffeine). Cần access delegate. Pattern: dùng
            // reflection 1 lần ở đây — đơn giản hơn là expose getter trong PEC.
            try {
                var field = ProbabilisticExpiringCache.class.getDeclaredField("delegate");
                field.setAccessible(true);
                Object delegate = field.get(pc);
                if (delegate instanceof TwoTierCache t) return t;
            } catch (ReflectiveOperationException e) {
                log.warn("Cannot reflect TwoTierCache from PEC: {}", e.getMessage());
            }
        }
        return null;
    }
}
