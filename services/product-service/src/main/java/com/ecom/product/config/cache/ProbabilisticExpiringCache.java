package com.ecom.product.config.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Decorator áp dụng XFetch (probabilistic early expiration) để chống
 * cache stampede.
 *
 * <h3>Vấn đề: cache stampede</h3>
 * <p>Hot key expire lúc traffic peak → N concurrent request cùng miss
 * → N lần gọi loader (DB) → DB CPU spike → cascading fail.
 *
 * <h3>Giải pháp: XFetch (Vattani et al. 2015)</h3>
 * <p>Trước khi key expire thật, có 1 cửa sổ "early expiration window".
 * Trong cửa sổ này, mỗi lookup có {@code probability tăng dần} (gần TTL →
 * gần 100%) coi như đã expire → chủ động refresh sớm. Spread compute
 * thay vì dồn cục vào lúc expire.
 *
 * <p>Công thức:
 * <pre>
 *   shouldRefresh = (now - lastFetch) * delta * β * -ln(random)) ≥ remainingTtl
 * </pre>
 * Trong đó:
 * <ul>
 *   <li>{@code delta} = thời gian fetch lần cuối (ms) — proxy cho cost.</li>
 *   <li>{@code β} = aggressiveness (≥1 = refresh sớm hơn).</li>
 *   <li>{@code random ∈ (0,1)} — randomize để các process không cùng quyết
 *       định refresh đồng loạt.</li>
 * </ul>
 *
 * <h3>Trade-off</h3>
 * <p>Accept 1-2 process duplicate compute (vs N=1000 nếu không có XFetch).
 * KHÔNG strict như distributed lock — chọn vì: không thêm infra (Redis
 * lock + GC pause = nguy hiểm), không block thread, code đơn giản.
 *
 * <h3>Multi-instance behavior</h3>
 * <p>Metadata {@link #fetchMetadata} là per-instance — không share giữa
 * pod. Worst case: 4 pod × XFetch decision riêng = tối đa 4 duplicate
 * compute trên cluster 4-pod. Vẫn nhỏ hơn N = số concurrent request.
 */
@Slf4j
public class ProbabilisticExpiringCache extends AbstractValueAdaptingCache {

    private final Cache delegate;
    private final Duration ttl;
    private final Duration earlyExpirationWindow;
    private final double beta;
    private final Clock clock;

    /** Metadata theo key cho XFetch decision. */
    private final ConcurrentMap<Object, FetchMeta> fetchMetadata = new ConcurrentHashMap<>();

    private final LongAdder earlyRefreshCount = new LongAdder();

    public ProbabilisticExpiringCache(Cache delegate,
                                      Duration ttl,
                                      Duration earlyExpirationWindow,
                                      double beta,
                                      Clock clock) {
        super(false);
        this.delegate = delegate;
        this.ttl = ttl;
        this.earlyExpirationWindow = earlyExpirationWindow;
        this.beta = beta;
        this.clock = clock;
    }

    @Override
    @NonNull
    public String getName() {
        return delegate.getName();
    }

    @Override
    @NonNull
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    @Nullable
    protected Object lookup(@NonNull Object key) {
        Object value = delegate.get(key, () -> null);
        if (value == null) return null;

        FetchMeta meta = fetchMetadata.get(key);
        if (meta != null && shouldEarlyRefresh(meta)) {
            // Trả null → caller (Spring Cache abstraction) sẽ gọi loader.
            // Tức là: value vẫn có trong cache, nhưng MỘT trong N caller
            // được "rút thăm trúng" để refresh sớm. Còn lại tiếp tục lấy
            // value từ cache (lần sau lookup → meta sẽ thấy TTL còn nhiều
            // vì vừa refresh).
            earlyRefreshCount.increment();
            log.debug("XFetch early refresh triggered: key={} reason=probabilistic", key);
            return null;
        }
        return value;
    }

    @Override
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        Object cached = lookup(key);
        if (cached != null) {
            @SuppressWarnings("unchecked")
            T result = (T) cached;
            return result;
        }
        long startNanos = System.nanoTime();
        try {
            T loaded = valueLoader.call();
            if (loaded != null) {
                long fetchDurationMs = (System.nanoTime() - startNanos) / 1_000_000;
                put(key, loaded);
                fetchMetadata.put(key, new FetchMeta(Instant.now(clock), Math.max(1, fetchDurationMs)));
            }
            return loaded;
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    @Override
    public void put(@NonNull Object key, @Nullable Object value) {
        delegate.put(key, value);
        // Reset metadata: ngay sau put, đặt fetchAt = now, delta = ttl đầy đủ.
        // Lần lookup tiếp theo, remainingTtl = ttl → khả năng XFetch trigger
        // gần 0 cho tới khi vào earlyExpirationWindow.
        fetchMetadata.put(key, new FetchMeta(Instant.now(clock), 1));
    }

    @Override
    public void evict(@NonNull Object key) {
        delegate.evict(key);
        fetchMetadata.remove(key);
    }

    @Override
    public void clear() {
        delegate.clear();
        fetchMetadata.clear();
    }

    /**
     * Core XFetch decision. Chỉ trigger TRONG cửa sổ
     * {@link #earlyExpirationWindow} cuối TTL — ngoài cửa sổ, KHÔNG refresh
     * (tránh refresh quá sớm gây thrash).
     */
    private boolean shouldEarlyRefresh(FetchMeta meta) {
        Instant now = Instant.now(clock);
        Duration age = Duration.between(meta.fetchedAt(), now);
        Duration remaining = ttl.minus(age);

        // Out of window → để TTL Redis quyết định expire.
        if (remaining.compareTo(earlyExpirationWindow) > 0) return false;
        // Đã expire — caller sẽ thấy null từ delegate, không cần XFetch.
        if (remaining.isNegative() || remaining.isZero()) return false;

        // XFetch formula. delta = fetch cost proxy (ms). Random ∈ (0,1).
        double random = ThreadLocalRandom.current().nextDouble(0.0, 1.0);
        // Math.log(random) < 0 → -Math.log(random) > 0. Càng nhỏ random,
        // số càng lớn → càng dễ trigger.
        double xfetchExpr = meta.fetchDurationMs() * beta * -Math.log(random);
        return xfetchExpr >= remaining.toMillis();
    }

    /** Số lần XFetch chủ động refresh sớm (cho metrics). */
    public long earlyRefreshCount() { return earlyRefreshCount.sum(); }

    /** Metadata 1 cache entry — fetchedAt + thời gian fetch lần cuối. */
    private record FetchMeta(Instant fetchedAt, long fetchDurationMs) {}
}
