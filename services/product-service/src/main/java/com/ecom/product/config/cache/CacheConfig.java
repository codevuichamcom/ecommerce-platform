package com.ecom.product.config.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wire 2-tier cache stack cho product-service.
 *
 * <h3>Cache name conventions</h3>
 * <p>{@code product:byId} cho {@code getProduct(UUID)}, {@code product:bySlug}
 * cho {@code getBySlug(String)}. Tách 2 cache name → 2 namespace key độc lập
 * → tránh collision khi key cùng giá trị nhưng khác type lookup.
 *
 * <h3>Stack composition</h3>
 * <pre>
 *   ProbabilisticExpiringCache (XFetch decorator)
 *     └─→ TwoTierCache (L1 Caffeine + L2 RedisCache composition)
 *           ├─→ L1: Caffeine native (in-process, ~50ns lookup)
 *           └─→ L2: Spring RedisCache (Lettuce, ~1ms RTT)
 * </pre>
 *
 * <h3>Serialization</h3>
 * <p>L2 dùng {@code GenericJackson2JsonRedisSerializer} (JSON + type info).
 * Trade-off: dễ debug bằng redis-cli (text JSON), payload lớn hơn binary
 * (~30%). Day 19+ benchmark có thể migrate sang Kryo nếu hit ratio thấp do
 * Redis bandwidth bottleneck — hiện chấp nhận vì observability > 30% size.
 *
 * <h3>Tại sao SimpleCacheManager thay vì CompositeCacheManager?</h3>
 * <p>{@code CompositeCacheManager} là pattern "try cache manager A, fallback B"
 * — KHÔNG phải 2-tier composition. Composition phải xảy ra Ở TRONG 1 Cache
 * instance — chính là {@link TwoTierCache}. SimpleCacheManager chỉ làm
 * registry các custom Cache.
 */
@Slf4j
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    public static final String CACHE_PRODUCT_BY_ID = "product:byId";
    public static final String CACHE_PRODUCT_BY_SLUG = "product:bySlug";

    @Bean
    public Clock cacheClock() {
        return Clock.systemUTC();
    }

    /**
     * JSON serializer với polymorphic type info để Redis store/load record
     * (ProductResponse) đúng concrete class.
     *
     * <p>{@link BasicPolymorphicTypeValidator} restrict các package được phép
     * deserialize — chống Jackson polymorphic deserialization vuln (CVE-2017-7525
     * style). Chỉ cho phép class từ {@code com.ecom.product.*}.
     */
    @Bean
    public GenericJackson2JsonRedisSerializer redisJsonSerializer(ObjectMapper baseObjectMapper) {
        ObjectMapper copy = baseObjectMapper.copy();
        var validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.ecom.product.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .build();
        copy.activateDefaultTyping(validator,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(copy);
    }

    /**
     * Build {@link SimpleCacheManager} chứa 2 cache name. Mỗi cache là full
     * stack: XFetch wrap TwoTier wrap (L1 Caffeine + L2 RedisCache).
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory,
                                     GenericJackson2JsonRedisSerializer jsonSerializer,
                                     CacheProperties props,
                                     Clock clock) {
        // L2 backbone — build 1 RedisCacheManager riêng để lấy ra các Cache
        // instance đã được khởi tạo đúng (constructor RedisCache là protected,
        // không new trực tiếp được — phải đi qua manager này).
        RedisCacheConfiguration redisConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(props.l2().ttl())
                .prefixCacheNameWith(props.l2().keyPrefix())
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                // disableCachingNullValues: align với TwoTierCache.allowNullValues=false.
                .disableCachingNullValues();
        RedisCacheManager redisManager = RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(redisConfig)
                .initialCacheNames(java.util.Set.of(CACHE_PRODUCT_BY_ID, CACHE_PRODUCT_BY_SLUG))
                .build();
        redisManager.initializeCaches();

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                buildCache(CACHE_PRODUCT_BY_ID, redisManager, props, clock),
                buildCache(CACHE_PRODUCT_BY_SLUG, redisManager, props, clock)
        ));
        manager.initializeCaches();
        log.info("CacheManager initialized: caches=[{}, {}], L1 TTL={}s max={}, L2 TTL={}s prefix={}",
                CACHE_PRODUCT_BY_ID, CACHE_PRODUCT_BY_SLUG,
                props.l1().ttlSeconds(), props.l1().maxSize(),
                props.l2().ttlSeconds(), props.l2().keyPrefix());
        return manager;
    }

    private Cache buildCache(String name,
                             RedisCacheManager redisManager,
                             CacheProperties props,
                             Clock clock) {
        // L1 — Caffeine.
        // recordStats() cần thiết cho CacheMetrics đọc hit/miss count.
        // expireAfterWrite (absolute TTL) chứ KHÔNG expireAfterAccess — vì cache
        // ProductResponse có ý nghĩa thời gian, không phải LRU-style "giữ lâu
        // nếu được truy cập" (giữ lâu = stale lâu).
        com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = Caffeine.newBuilder()
                .maximumSize(props.l1().maxSize())
                .expireAfterWrite(props.l1().ttlSeconds(), TimeUnit.SECONDS)
                .recordStats()
                .build();

        // L2 — lấy từ RedisCacheManager. getCache() lazily tạo nếu chưa có,
        // hoặc trả Cache đã initialize từ initialCacheNames.
        Cache l2 = redisManager.getCache(name);
        if (l2 == null) {
            throw new IllegalStateException("Redis cache not initialized: " + name);
        }

        // Compose: TwoTier(L1, L2) → wrap XFetch.
        TwoTierCache twoTier = new TwoTierCache(name, l1, l2);
        return new ProbabilisticExpiringCache(
                twoTier,
                props.l2().ttl(),                   // dùng L2 TTL làm reference cho XFetch
                props.stampede().earlyExpirationWindow(),
                props.stampede().xfetchBeta(),
                clock
        );
    }
}
