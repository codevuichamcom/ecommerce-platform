package com.ecom.product.config.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Type-safe binding cho block {@code app.cache.*} ở application.yml.
 *
 * <p>Tách thành 3 nested record để mỗi tier (L1/L2/stampede) có namespace
 * riêng — đọc IDE autocomplete rõ, đổi 1 group không ảnh hưởng group khác.
 *
 * <p>Records chứ không class — immutable, không setter, JVM 21 native.
 * Spring Boot 3.x bind records qua canonical constructor + accessor.
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(L1 l1, L2 l2, Stampede stampede) {

    /**
     * L1 Caffeine config. {@code maxSize} là hard cap số entry; vượt → W-TinyLFU
     * eviction. {@code ttl} là time-to-live tuyệt đối tính từ lúc write.
     */
    public record L1(long ttlSeconds, long maxSize) {
        public Duration ttl() { return Duration.ofSeconds(ttlSeconds); }
    }

    /**
     * L2 Redis config. {@code keyPrefix} để namespace key trong shared Redis
     * (cart-service cũng dùng cùng instance) — tránh đụng độ.
     */
    public record L2(long ttlSeconds, String keyPrefix) {
        public Duration ttl() { return Duration.ofSeconds(ttlSeconds); }
    }

    /**
     * Stampede protection knobs. XFetch refresh sớm trong
     * {@code earlyExpirationWindowSeconds} cuối TTL với probability tăng theo
     * exponential. {@code xfetchBeta} controls aggressiveness — β càng lớn
     * càng refresh sớm. Vattani et al. 2015 chọn β=1 làm default.
     */
    public record Stampede(long earlyExpirationWindowSeconds, double xfetchBeta) {
        public Duration earlyExpirationWindow() {
            return Duration.ofSeconds(earlyExpirationWindowSeconds);
        }
    }
}
