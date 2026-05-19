package com.ecommerce.notification.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Idempotency guard cho notification consumer: Redis SET NX với TTL 24h.
 *
 * <p>Flow:
 * <pre>
 *   if (deduplicator.tryAcquire(eventId)) {
 *       // first-time — process + dispatch
 *   } else {
 *       // duplicate — skip silently
 *   }
 * </pre>
 *
 * <p>Fail-open design: nếu Redis down → {@code tryAcquire} return {@code true}
 * (xử lý như lần đầu) để tránh miss email quan trọng. Trade-off: có thể gửi
 * duplicate khi Redis unavailable. Notification là low-priority channel →
 * fail-open hợp lý; nếu OTP/security alert → cần fail-closed.
 *
 * <p>Key pattern: {@code notif:dedup:{eventId}} — 1 UUID = 1 key.
 * TTL 24h đủ để cover Kafka max retry window (default 10 min) với margin x144.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeduplicator {

    private static final String KEY_PREFIX = "notif:dedup:";

    private final StringRedisTemplate redisTemplate;

    @Value("${app.notification.dedup-ttl-hours:24}")
    private long dedupTtlHours;

    /**
     * Cố gắng acquire lock cho {@code eventId}.
     *
     * @return {@code true} nếu đây là lần đầu xử lý event này (nên tiếp tục dispatch);
     *         {@code false} nếu đã xử lý rồi (nên skip).
     */
    public boolean tryAcquire(UUID eventId) {
        String key = KEY_PREFIX + eventId;
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofHours(dedupTtlHours));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception ex) {
            // Fail-open: Redis down → không block xử lý.
            log.warn("[dedup] Redis unavailable for eventId={}, fail-open → process anyway. error={}",
                    eventId, ex.getMessage());
            return true;
        }
    }
}
