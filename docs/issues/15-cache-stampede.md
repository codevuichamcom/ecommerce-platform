# Issue 15 — 🔥 Cache Stampede (Thundering Herd)

> **Status**: ✅ Done · 2026-05-27
> **Related day**: Day 15 (Redis cache aside + 2-tier).

---

## 1. Problem

Hot key (vd `product:viral-flash-sale`) hết TTL cùng lúc đang có 1000 request đọc → 1000 cùng miss cache → 1000 cùng đập DB → DB CPU spike 100%, P99 tăng 30×, có thể cascading fail toàn fleet.

## 2. Symptoms

- Grafana: `cache_hits / (cache_hits + cache_misses)` drop từ 95% → 60% trong 5 giây.
- Postgres `pg_stat_statements`: 1 query (cùng SQL, cùng param) tăng exec_count 1000× trong cùng phút.
- `product_cache_misses_total{tier="l2"}` spike đúng moment expire.
- App log: `SELECT * FROM products WHERE id=?` duplicate 1000 lần với cùng `id`.
- P99 `getProduct` tăng từ 20ms → 800ms (queue chờ DB connection pool).
- Connection pool exhausted log: `HikariPool — Connection is not available, request timed out after 30000ms`.

## 3. Root cause

- **TTL absolute**: Redis SET với TTL 300s — set 1 lần lúc cache, expire chính xác 1 thời điểm. Mọi request đến TRONG khoảng (T-1ms, T+1ms) đều thấy "expired" → miss.
- **Không có single-flight ở app layer**: N request đồng thời gọi loader (DB query) độc lập, không có cơ chế "1 request làm, N-1 chờ kết quả".
- **Không có read replica buffer**: tất cả query dồn primary DB → primary CPU 100% → cả write path cũng bị block.

## 4. Approaches compared

| Approach | Pros | Cons |
|----------|------|------|
| **Distributed lock** (Redis `SET NX EX`) | Strict — chỉ 1 process refresh trên cluster. Đơn giản hiểu. | Lock holder GC pause / OOM → lock orphan; thêm 1 RTT/request; cascading fail nếu Redis flap. |
| **In-process single-flight** (Caffeine `LoadingCache.get`) | Lock-free, ~50ns overhead; battle-tested ở Caffeine. | Chỉ effective per-JVM; 4 pod = 4 cache độc lập → multi-instance vẫn stampede ở L2. |
| **Probabilistic early expiration (XFetch)** | Không cần infra mới; phân tán refresh time qua randomization; scale tự nhiên. | Có chance 2-3 process duplicate compute; không strict; cần benchmark β tuning. |
| **Refresh-ahead** (cron scheduled job) | Cache hot key không bao giờ expire trong giờ peak; predictable. | Phải biết key nào "hot" trước (cold start không xử lý được); cost compute liên tục cả khi traffic thấp; complexity job scheduling. |
| **Stale-while-revalidate** | UX tốt — trả stale ngay, refresh async, không block; quen thuộc với CDN. | Stale window phụ thuộc background job latency; cần thêm "is-stale" metadata mỗi entry; phức tạp invalidation. |

## 5. Chosen — XFetch + 2-tier (Caffeine L1 + Redis L2)

**Lý do gắn project**:
- 2-tier đã chốt ở ADR 012 (latency ladder + bandwidth save). XFetch là layer protect trên cùng nó.
- Không cần infra mới (lock cluster cần High-Availability Redis nếu mở rộng). Hiện Redis dùng chung cart-service, không muốn tăng lock contention.
- Cluster scale 4-pod hiện tại → worst case 4 duplicate compute, vẫn ≪ N=1000 nếu không có XFetch. Acceptable.
- L1 Caffeine absorb 80-95% hot traffic → stampede chỉ là vấn đề ở L2 expire, không phải mỗi request.

**Trade-off chính so với lock**: chấp nhận 1-2 duplicate compute thay vì strict 1, đổi lấy zero lock orphan risk và đơn giản code.

## 6. Fix

XFetch logic ở [`ProbabilisticExpiringCache.shouldEarlyRefresh`](../../services/product-service/src/main/java/com/ecom/product/config/cache/ProbabilisticExpiringCache.java):

```java
private boolean shouldEarlyRefresh(FetchMeta meta) {
    Instant now = Instant.now(clock);
    Duration remaining = ttl.minus(Duration.between(meta.fetchedAt(), now));

    // Out of early window → để TTL Redis tự expire.
    if (remaining.compareTo(earlyExpirationWindow) > 0) return false;
    if (remaining.isNegative() || remaining.isZero()) return false;

    // XFetch (Vattani et al. 2015):
    // probabilistic refresh nếu  delta * β * -ln(rand) ≥ remainingTtl
    double random = ThreadLocalRandom.current().nextDouble(0.0, 1.0);
    double xfetchExpr = meta.fetchDurationMs() * beta * -Math.log(random);
    return xfetchExpr >= remaining.toMillis();
}
```

Mỗi lookup vào window 30s cuối TTL, gen `random ∈ (0,1)` → quyết định probabilistically. Càng gần expire, càng nhiều thread "rút thăm trúng" refresh → spread compute thay vì burst.

**Test verify**: [`StampedeProtectionTest`](../../services/product-service/src/test/java/com/ecom/product/cache/StampedeProtectionTest.java) — 100 concurrent get khi key vào early window → loader call < 25 (vs 100 nếu không có protection).

## 7. Prevention

- **Metric**: `product_cache_xfetch_early_refresh_total` track số lần XFetch trigger. Spike = nhiều hot key đang vào window cùng lúc → predictable, không phải incident.
- **Alert**: cache hit ratio < 70% sustained 10m → page (Day 20 sẽ wire Grafana board).
- **Load test**: k6 scenario 10k concurrent request cùng 1 product key, verify DB QPS < 50/s trong 1 phút expire window.
- **Pre-warm**: deploy → warm cache top-1000 product trước khi serve traffic (background job ở `@PostConstruct`). Day 20 sẽ wire.
- **Circuit breaker** trên DB call: nếu DB latency > 500ms sustained → bypass cache miss path, trả 503 với `Retry-After`. Day 12 đã có Resilience4j foundation.

## 8. Trade-off accepted

- **Duplicate compute lẻ tẻ** (≤4 process refresh cùng key trên 4-pod cluster) thay vì strict 1 với lock. Chi phí: ~4× CPU 1 lần/key/TTL — negligible.
- **Stale data trong XFetch refresh window** (≤30s cuối TTL). Acceptable cho catalog; KHÔNG dùng XFetch cho price (TTL ngắn 5s, không có early window).
- **β tuning manual**: chọn β=1.0 từ paper default. Production có thể cần tune theo workload — chấp nhận monitor `xfetch_early_refresh` rate, tune nếu DB QPS spike trở lại.

## 9. Related

- Code: [`ProbabilisticExpiringCache.java`](../../services/product-service/src/main/java/com/ecom/product/config/cache/ProbabilisticExpiringCache.java)
- Test: [`StampedeProtectionTest.java`](../../services/product-service/src/test/java/com/ecom/product/cache/StampedeProtectionTest.java)
- Lesson: [`lessons/15-cache-strategies.md`](../lessons/15-cache-strategies.md)
- Performance: [`performance/15b-two-tier-cache.md`](../performance/15b-two-tier-cache.md)
- Related issue: [`issues/15b-hot-key.md`](15b-hot-key.md) (hot key sharding khi 1 product viral chiếm 1 Redis shard)
- Paper: Vattani et al. 2015 — "Optimal Probabilistic Cache Stampede Prevention"
