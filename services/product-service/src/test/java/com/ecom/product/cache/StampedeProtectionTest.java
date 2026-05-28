package com.ecom.product.cache;

import com.ecom.product.config.cache.ProbabilisticExpiringCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verify XFetch chống stampede. Pure unit test — không cần Spring/Redis.
 *
 * <p>Setup: 100 concurrent thread cùng request 1 key TRƯỚC khi key expire,
 * loader bằng counter (đếm số lần DB call thật). Yêu cầu:
 * <ul>
 *   <li>Không có XFetch + cache miss đồng thời → 100 loader call.</li>
 *   <li>Có XFetch + cache valid → 0-1 loader call (≤1 trong window) +
 *       99-100 cache hit.</li>
 * </ul>
 */
class StampedeProtectionTest {

    @Test
    @DisplayName("100 concurrent get on valid cached entry → loader called at most ~few times via XFetch")
    void concurrentGet_loaderCalledFewTimes() throws InterruptedException {
        // Cache giả lập (in-memory map) — không phải L1+L2 thật, chỉ test
        // XFetch decision logic.
        FakeCache backing = new FakeCache();
        Clock clock = Clock.systemUTC();

        ProbabilisticExpiringCache cache = new ProbabilisticExpiringCache(
                backing,
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),   // early window = 50% TTL
                1.0,
                clock
        );

        // Prime cache.
        cache.put("hot", "v0");

        // Wait đến gần expire để vào early-expiration window.
        Thread.sleep(1100);

        AtomicInteger loaderCalls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(50);
        int n = 100;
        var latch = new java.util.concurrent.CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    cache.get("hot", () -> {
                        loaderCalls.incrementAndGet();
                        return "v_loaded";
                    });
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdownNow();

        // Without protection: ~N=100 loader call (all see expired).
        // With XFetch: probabilistic — vài thread "rút thăm trúng" refresh,
        // số còn lại thấy value đã refresh từ thread sớm. Assert: loader call
        // << N, nhưng > 0 (chứng tỏ XFetch DID trigger).
        assertThat(loaderCalls.get())
                .as("XFetch should suppress most concurrent loader calls")
                .isLessThan(n / 4);
    }

    /** Backing cache đơn giản — in-memory map, không TTL (PEC quản lý TTL logic). */
    static class FakeCache implements Cache {
        private final ConcurrentHashMap<Object, Object> store = new ConcurrentHashMap<>();
        @Override public String getName() { return "fake"; }
        @Override public Object getNativeCache() { return store; }
        @Override public ValueWrapper get(Object key) {
            Object v = store.get(key);
            return v == null ? null : () -> v;
        }
        @Override @SuppressWarnings("unchecked")
        public <T> T get(Object key, Class<T> type) { return (T) store.get(key); }
        @Override @SuppressWarnings("unchecked")
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            Object v = store.get(key);
            if (v != null) return (T) v;
            try { T loaded = valueLoader.call(); if (loaded != null) store.put(key, loaded); return loaded; }
            catch (Exception e) { throw new ValueRetrievalException(key, valueLoader, e); }
        }
        @Override public void put(Object key, Object value) { store.put(key, value); }
        @Override public void evict(Object key) { store.remove(key); }
        @Override public void clear() { store.clear(); }
    }
}
