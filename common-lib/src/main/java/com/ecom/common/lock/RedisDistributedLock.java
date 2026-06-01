package com.ecom.common.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Distributed lock dựa trên Redis {@code SET key token NX PX ttl}.
 *
 * <p>Ba điểm production-grade dễ bị junior bỏ:
 * <ol>
 *   <li><b>Owner token</b>: value lock = UUID. Release so token trước khi DEL →
 *       không xoá nhầm lock người khác (sau TTL expire, lock có thể đã đổi chủ).</li>
 *   <li><b>Atomic release qua Lua</b>: GET-rồi-DEL ở 2 lệnh có race (TTL expire
 *       xen giữa). Lua chạy nguyên tử trong Redis.</li>
 *   <li><b>Fencing token</b>: mỗi lần acquire {@code INCR} 1 counter bền (không
 *       expire) → token tăng đơn điệu. Đây mới là thứ bảo đảm correctness;
 *       SET NX chỉ là best-effort mutual exclusion.</li>
 * </ol>
 */
public class RedisDistributedLock implements DistributedLock {

    /** Lua compare-and-delete: chỉ DEL khi value khớp owner token. */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('del', KEYS[1]) "
            + "else return 0 end",
        Long.class);

    private static final String LOCK_PREFIX = "lock:";
    private static final String FENCE_PREFIX = "fence:";

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
        String lockKey = LOCK_PREFIX + key;
        String token = UUID.randomUUID().toString();

        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }

        // INCR sau khi chiếm lock thành công → fencing token tăng đơn điệu.
        // Counter KHÔNG đặt TTL: phải sống lâu hơn mọi lock để token không reset.
        Long fencing = redis.opsForValue().increment(FENCE_PREFIX + key);
        long fencingToken = fencing == null ? 0L : fencing;
        return Optional.of(new LockHandle(key, token, fencingToken));
    }

    @Override
    public boolean release(LockHandle handle) {
        Long deleted = redis.execute(
            RELEASE_SCRIPT,
            Collections.singletonList(LOCK_PREFIX + handle.key()),
            handle.ownerToken());
        return Long.valueOf(1L).equals(deleted);
    }
}
