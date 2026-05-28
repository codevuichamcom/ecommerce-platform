package com.ecom.product.config.cache;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.LongAdder;

/**
 * 2-tier read-through cache: L1 Caffeine (in-process) + L2 Redis (distributed).
 *
 * <h3>Đọc</h3>
 * <ol>
 *   <li>L1 hit  → trả ngay (~50ns, không IO).</li>
 *   <li>L1 miss → query L2 (~1ms Redis RTT). L2 hit → backfill L1, trả.</li>
 *   <li>L2 miss → gọi loader (DB), put L1 + L2.</li>
 * </ol>
 *
 * <h3>Ghi</h3>
 * <p>{@link #put}/{@link #evict} ghi/xóa CẢ 2 tier. Lưu ý multi-instance:
 * evict L1 của instance A KHÔNG propagate sang instance B. Spec hiện tại
 * (Day 15) chấp nhận eventual consistency ≤ L1 TTL (60s). Day 20+ wire
 * Redis pub/sub topic {@code cache:invalidate} để strict consistency.
 *
 * <h3>Stampede protection</h3>
 * <p>Class này CHƯA tích hợp XFetch — sẽ wrap qua
 * {@link ProbabilisticExpiringCache} ở {@code CacheConfig}. Tách concern:
 * 2-tier composition vs early-expiration là 2 vấn đề độc lập.
 *
 * <h3>Counters</h3>
 * <p>{@link LongAdder} thread-safe lock-free — đếm hit/miss để
 * {@link CacheMetrics} bind vào Micrometer. Per-instance counter, không
 * aggregate cluster-wide (Grafana sum metric từ tất cả pod).
 */
@Slf4j
public class TwoTierCache extends AbstractValueAdaptingCache {

    private final String name;
    private final Cache<Object, Object> l1;
    private final org.springframework.cache.Cache l2;

    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l1Misses = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder l2Misses = new LongAdder();

    public TwoTierCache(String name, Cache<Object, Object> l1, org.springframework.cache.Cache l2) {
        // allowNullValues=false: cache miss của method trả null sẽ KHÔNG cache
        // null sentinel. Trade-off: protect against null-poisoning, đánh đổi
        // KHÔNG chống được cache penetration (attacker query non-existent id
        // liên tục). Day 15 chấp nhận — id PRODUCT là UUID khó brute-force;
        // nếu sau này có integer id thì thêm bloom filter ở front.
        super(false);
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    @NonNull
    public Object getNativeCache() {
        // Spring exposes native cache cho actuator /caches endpoint.
        // Trả L1 vì đó là tier "primary"; L2 inspect qua redis-cli.
        return l1;
    }

    @Override
    @Nullable
    protected Object lookup(@NonNull Object key) {
        Object l1Value = l1.getIfPresent(key);
        if (l1Value != null) {
            l1Hits.increment();
            log.trace("L1 HIT  name={} key={}", name, key);
            return l1Value;
        }
        l1Misses.increment();

        // L2 lookup. Spring org.springframework.cache.Cache.lookup() đã handle deserialize.
        Object l2Value = l2.get(key, () -> null);
        if (l2Value != null) {
            l2Hits.increment();
            log.trace("L2 HIT  name={} key={} → backfill L1", name, key);
            l1.put(key, l2Value);
            return l2Value;
        }
        l2Misses.increment();
        return null;
    }

    @Override
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        // 1) L1 / L2 lookup qua super.fromStoreValue logic.
        Object cached = lookup(key);
        if (cached != null) {
            @SuppressWarnings("unchecked")
            T result = (T) cached;
            return result;
        }
        // 2) Cả 2 tier miss → loader (DB). Bao quanh try/catch để wrap
        //    exception theo contract Spring Cache.
        try {
            T loaded = valueLoader.call();
            if (loaded != null) {
                put(key, loaded);
            }
            return loaded;
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    @Override
    public void put(@NonNull Object key, @Nullable Object value) {
        if (value == null) return;
        l2.put(key, value);
        l1.put(key, value);
        log.trace("PUT name={} key={} → L1+L2", name, key);
    }

    @Override
    public void evict(@NonNull Object key) {
        // Order: L2 trước, L1 sau. Lý do: nếu L1 evict thành công nhưng L2
        // fail (Redis down), instance khác vẫn fetch L2 stale → backfill L1
        // stale lần sau. Evict L2 trước → worst case L1 còn stale ≤TTL,
        // KHÔNG bị restore từ L2 stale.
        l2.evict(key);
        l1.invalidate(key);
        log.debug("EVICT name={} key={} → L1+L2", name, key);
    }

    @Override
    public void clear() {
        l2.clear();
        l1.invalidateAll();
        log.info("CLEAR name={} → L1+L2", name);
    }

    // ─── Stats accessors cho CacheMetrics ─────────────────────────────────

    public long l1HitCount()   { return l1Hits.sum(); }
    public long l1MissCount()  { return l1Misses.sum(); }
    public long l2HitCount()   { return l2Hits.sum(); }
    public long l2MissCount()  { return l2Misses.sum(); }
    public long l1EstimatedSize() { return l1.estimatedSize(); }
}
