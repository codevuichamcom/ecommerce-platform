package com.ecom.cart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cart dùng {@link StringRedisTemplate} thay vì JSON serializer:
 * <ul>
 *   <li>Field value là số nguyên (qty) — khỏi cần serialize struct.</li>
 *   <li>{@code HINCRBY} chỉ chạy được trên numeric string field — đụng tới
 *       {@code @Value} JSON serializer (vd `"5"` quoted) là Redis reject.</li>
 *   <li>Lettuce + StringRedisTemplate share connection an toàn cross-thread —
 *       phù hợp virtual threads.</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
}
