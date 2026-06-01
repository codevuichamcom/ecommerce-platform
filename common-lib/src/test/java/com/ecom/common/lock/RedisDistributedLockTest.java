package com.ecom.common.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * IT cho {@link RedisDistributedLock} — gated bằng {@code RUN_LOCK_INTEGRATION_TESTS=true}
 * (default skip để build CI không cần Docker, giống pattern các service khác).
 */
class RedisDistributedLockTest {

    private static final boolean ENABLED =
        Boolean.parseBoolean(System.getenv().getOrDefault("RUN_LOCK_INTEGRATION_TESTS", "false"));

    private static GenericContainer<?> redis;
    private static StringRedisTemplate template;
    private static RedisDistributedLock lock;

    @BeforeAll
    static void up() {
        assumeTrue(ENABLED, "Set RUN_LOCK_INTEGRATION_TESTS=true để chạy (cần Docker).");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();

        LettuceConnectionFactory cf = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        cf.afterPropertiesSet();
        template = new StringRedisTemplate(cf);
        template.afterPropertiesSet();
        lock = new RedisDistributedLock(template);
    }

    @AfterAll
    static void down() {
        if (redis != null) {
            redis.stop();
        }
    }

    @Test
    void secondAcquireOnHeldKeyFails() {
        String key = "res-" + UUID.randomUUID();

        Optional<LockHandle> first = lock.tryAcquire(key, Duration.ofSeconds(30));
        Optional<LockHandle> second = lock.tryAcquire(key, Duration.ofSeconds(30));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    void releaseByOwnerAllowsReacquire() {
        String key = "res-" + UUID.randomUUID();

        LockHandle handle = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();
        assertThat(lock.release(handle)).isTrue();
        assertThat(lock.tryAcquire(key, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void releaseWithWrongTokenIsRejected() {
        String key = "res-" + UUID.randomUUID();

        LockHandle real = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();
        LockHandle forged = new LockHandle(key, "not-the-owner", real.fencingToken());

        assertThat(lock.release(forged)).isFalse();   // không xoá nhầm lock người khác
        assertThat(lock.release(real)).isTrue();
    }

    @Test
    void fencingTokenIsMonotonic() {
        String key = "res-" + UUID.randomUUID();

        LockHandle h1 = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();
        lock.release(h1);
        LockHandle h2 = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();

        assertThat(h2.fencingToken()).isGreaterThan(h1.fencingToken());
    }
}
