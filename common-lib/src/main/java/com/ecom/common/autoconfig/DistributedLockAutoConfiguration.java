package com.ecom.common.autoconfig;

import com.ecom.common.lock.DistributedLock;
import com.ecom.common.lock.RedisDistributedLock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-config cho {@link DistributedLock} — bật khi:
 * <ul>
 *   <li>{@link StringRedisTemplate} có trên classpath (service đã kéo
 *       {@code spring-boot-starter-data-redis}), VÀ</li>
 *   <li>{@code app.lock.enabled=true} ở service yaml.</li>
 * </ul>
 *
 * <p>Service nào không dùng (vd auth-service) → không kéo Redis → bean không
 * tạo, không tốn gì. Giống pattern Kafka/Security auto-config Day 7-8.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "app.lock", name = "enabled", havingValue = "true")
public class DistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DistributedLock distributedLock(StringRedisTemplate redis) {
        return new RedisDistributedLock(redis);
    }
}
