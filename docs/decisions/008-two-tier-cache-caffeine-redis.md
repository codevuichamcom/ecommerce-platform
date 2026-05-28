# ADR 008 — 🏗️ 2-tier Cache: Caffeine L1 + Redis L2

- **Status**: ✅ Accepted
- **Date**: 2026-05-28
- **Deciders**: Tonny (Senior Backend, project lead)
- **Supersedes**: —

---

## Decision

Product-service dùng **2-tier cache**: Caffeine (L1, in-process, TTL 60s) + Redis (L2, distributed, TTL 5min), wrap thêm probabilistic early expiration (XFetch) chống stampede.

## Context

Day 15 mục tiêu giảm P99 `GET /products/{id}` từ 250ms → 80ms khi catalog scale từ 5k → 50k DAU. Cache là default solution, nhưng có 4 cách layer:

1. Chỉ Redis (distributed)
2. Chỉ Caffeine (in-process)
3. Caffeine + Redis (2-tier)
4. 3-tier (CDN + Caffeine + Redis)

## Alternatives considered

| Option           | Pros                                                         | Cons                                                                                                |
|------------------|--------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| ❌ Chỉ Redis      | Multi-instance consistent. Đơn giản 1 layer.                | 1ms RTT mỗi hit. Bandwidth bottleneck khi traffic peak. Redis flap = mọi pod down.                  |
| ❌ Chỉ Caffeine   | Latency ~50ns, không IO. Không cần Redis infra.            | Multi-instance inconsistent (4 pod = 4 cache độc lập). Restart pod = mất cache → cold start storm. |
| ✅ **2-tier**     | Latency mượt 50ns→1ms→30ms. Bandwidth Redis giảm 80-95%. Pod restart vẫn warm từ L2. | L1 invalidation lag (≤60s) giữa instance. Code phức tạp hơn 1-tier.                                |
| ❌ 3-tier (+ CDN) | Latency biên (Cloudflare edge ~10ms từ Asia).               | Cache invalidation qua API ngoài chậm (~30s purge). Cost CDN. **Day 15 quá sớm** — chưa có frontend deploy.|

## Chosen — 2-tier

**Rationale**:
1. **Latency ladder** match hardware reality. L1 nhanh hơn L2 ~20000× → nếu hit ratio L1 ≥80% thì effective latency ≈ L1 dominant. Không có L1 = bị Redis RTT (1ms × 1000 req/s = 1 thread-second cho Redis I/O alone).
2. **Bandwidth save**: Redis Cluster đang share cho cart-service. Product traffic ước 10× cart → giảm 80% load Redis bằng L1 là deal vital.
3. **Resilience**: Redis blip 1-2s → L1 tiếp tục serve hot key. Single-Redis setup = blip nguyên fleet.
4. **Cost effective**: Caffeine heap 5-10 MB/pod ≪ cost của 1 Redis instance dedicated.

## Trade-offs

### Accepted
- **L1 invalidation lag ≤60s**: instance A evict L1 không propagate sang B. Mitigate bằng TTL ngắn (60s). Đủ cho catalog (product update <1×/giờ điển hình).
- **Code phức tạp hơn 1-tier**: thêm `TwoTierCache` + `ProbabilisticExpiringCache` composition. Trả giá 4 file Java cho ~98% DB load reduction.
- **2 nguồn truth**: L1 và L2 có thể diverge ngắn hạn nếu Redis fail giữa `put L2 → put L1`. Mitigate bằng order: write L2 trước, L1 sau. Read backfill L1 từ L2.

### Rejected
- **Strong consistency multi-instance ngay từ Day 15**: cần Redis pub/sub invalidation. Postpone Day 20 vì hiện tại catalog không đòi 1s consistency.
- **Cache `Product` entity**: rejected vì mutable + LAZY association → cache poison. Chọn `ProductResponse` record immutable.

## Consequences

### Positive
- DB load giảm ~98% (1% effective query rate).
- P99 latency 250ms → 18ms.
- Redis bandwidth giảm 80% so với 1-tier Redis-only.
- Hit ratio observability sẵn qua Prometheus (rate(hits) / rate(hits+misses)).

### Negative
- 60s eventual consistency window giữa pod cho L1. **Phải document** với product team — đừng promise "real-time price update".
- Cache invalidation correctness là rủi ro chính — phải audit mọi write path (bulk update, native query) khi mở rộng. Day 16 SQL tuning sẽ thêm bulk operation → flag.
- `CacheMetrics` dùng reflection để unwrap `ProbabilisticExpiringCache → TwoTierCache`. Fragile nếu refactor sau này — đã có test cover.

### Follow-up actions
- [ ] Day 20: wire Grafana board "Product Cache Hit Ratio" + alert <0.7 sustained 10m.
- [ ] Day 20: thêm Redis pub/sub L1 invalidation cho stricter consistency.
- [ ] Day 21 mock interview: prep câu "stampede" + "hot key" trap.

## Related

- Code: [`CacheConfig`](../../services/product-service/src/main/java/com/ecom/product/config/cache/CacheConfig.java), [`TwoTierCache`](../../services/product-service/src/main/java/com/ecom/product/config/cache/TwoTierCache.java), [`ProbabilisticExpiringCache`](../../services/product-service/src/main/java/com/ecom/product/config/cache/ProbabilisticExpiringCache.java)
- [Lesson 15 — Cache strategies](../lessons/15-cache-strategies.md)
- [Performance 15 — Implementation](../performance/15-cache-aside.md)
- [Performance 15b — 2-tier hierarchy](../performance/15b-two-tier-cache.md)
- [Issue 15 — Stampede](../issues/15-cache-stampede.md)
- [Issue 15b — Hot key](../issues/15b-hot-key.md)
