# Issue 15b — 🔥 Redis Hot Key (1 product viral)

> **Status**: ✅ Done · 2026-05-27 (Day 15 đặt nền L1 absorb; sharding để Day 20+)
> **Related day**: Day 15 (Redis cache aside + 2-tier).

---

## 1. Problem

1 product viral (vd `iPhone-flash-sale`) chiếm 50% traffic. Redis Cluster 3 node nhưng key chỉ ở 1 node (slot map cố định) → node đó CPU 95%, 2 node khác idle. Đây là **hot key problem**, khác với cache stampede ([issue 15](15-cache-stampede.md)): stampede là spike một lần khi expire, hot key là **steady-state imbalance**.

## 2. Symptoms

- Redis Cluster: 1 node CPU 95%, 2 node còn lại 20% — load imbalance rõ rệt trên Grafana.
- `redis-cli --hotkeys`: `product-service:cache:product:byId::viral-iphone-id` ~50k ops/s, key kế tiếp ~500 ops/s (skew 100×).
- GET latency P99 toàn cluster tăng từ 1ms → 30ms (vì node hot ảnh hưởng cluster routing).
- Application: `getProduct(viralId)` latency P99 30ms, `getProduct(normalId)` P99 vẫn ~5ms — nhưng overall hit ratio L1 thấp hơn dự kiến vì viralId Caffeine bị evict hết bởi traffic của chính nó nếu max-size nhỏ.

## 3. Root cause

- **Redis Cluster hashing**: slot = `CRC16(key) mod 16384`. Key cố định → slot cố định → đúng 1 node primary. Không có "auto rebalance hot slot" trong Redis vanilla.
- **Caching ở L2 KHÔNG giải quyết**: bottleneck là Redis chính nó, không phải DB. Tăng Redis TTL = càng hot.
- **L1 chưa đủ lớn**: nếu Caffeine max-size 1000 mà workload viral chiếm 50%, các key khác bị evict nhường chỗ cho 1 key đó nhiều phiên bản (không, Caffeine deduplicate theo key) — chính xác hơn: viral key luôn hit L1, nhưng các key khác bị thrash.

## 4. Approaches compared

| Approach | Pros | Cons |
|----------|------|------|
| **Local cache L1 (Caffeine) absorb** | Triệt tiêu 95%+ traffic hot key trước khi tới Redis. Zero infra change. | Multi-instance stale ≤L1 TTL; cần L1 max-size đủ cho hot working set. |
| **Key sharding** (`product:viral-iphone#{0..9}`) | Phân tải đều N node Redis. Strict balance. | Phải random read shard (chọn shard nào để read?); write phải invalidate **mọi shard** → write amplify 10×; stale risk cao nếu 1 shard fail invalidate. |
| **Redis read replica** | Replicate primary → read từ replica giảm load primary. | Eventual consistency (replication lag ms-s); client routing phức tạp (smart client hoặc Envoy); replica down = lại 1 node. |
| **CDN trước Redis** (cho data immutable) | Off-load hoàn toàn khỏi Redis. Edge cache 10ms từ Asia. | Chỉ work cho data immutable hoặc TTL dài. Product detail có thể đổi giá → không phù hợp. |
| **Application-level routing** (đọc trực tiếp DB nếu là viral key đã biết) | Bypass Redis hoàn toàn cho viral key. | Phải biết key nào viral trước → cần hot key detection job. DB phải có read replica để chịu được. |

## 5. Chosen — L1 Caffeine absorb + plan sharding cho top-N (postpone Day 20+)

**Day 15 deliverable**:
- 2-tier cache (đã có) đã giải quyết 95% hot key tự nhiên: viral key luôn ở L1 Caffeine, KHÔNG tới Redis → Redis hot key issue **không xuất hiện** miễn là L1 hit > 90% cho viral key.
- L1 max-size 10k đủ cho top 20% (hot working set) trên catalog 50k.

**Day 20+ plan**:
- Hot key detection job (cron 5 phút): chạy `redis-cli --hotkeys`, push top-10 vào Kafka topic `cache.hot-keys`.
- Application subscribe topic → bật `key sharding` cho key trong list. Service đọc round-robin shard, write fan-out invalidate.
- Threshold: 1 key > 5000 ops/s sustained 1 phút → bật sharding tự động.

**Lý do postpone**: hiện tại chưa có scenario thật xảy ra. Tạo sharding infrastructure trước = over-engineer. Day 20 load test sẽ confirm có cần hay không.

## 6. Fix

Day 15 partial fix — L1 absorb (đã wired qua ADR 012):

```java
// services/product-service/src/main/java/com/ecom/product/config/cache/TwoTierCache.java
protected Object lookup(Object key) {
    Object l1Value = l1.getIfPresent(key);
    if (l1Value != null) {
        l1Hits.increment();
        return l1Value;  // viral key luôn hit ở đây
    }
    // ... fallback L2 → DB
}
```

Day 20+ TODO sketch:

```java
// FUTURE: services/product-service/src/main/java/com/ecom/product/config/cache/ShardedCache.java
public Object getSharded(String key) {
    if (!hotKeyRegistry.isHot(key)) return delegate.get(key);
    int shard = ThreadLocalRandom.current().nextInt(SHARD_COUNT);
    return delegate.get(key + "#" + shard);
}
public void evictSharded(String key) {
    if (!hotKeyRegistry.isHot(key)) { delegate.evict(key); return; }
    for (int i = 0; i < SHARD_COUNT; i++) delegate.evict(key + "#" + i);
}
```

## 7. Prevention

- **Metric**: track top-10 Redis key qua `redis-cli --hotkeys` cron 5 phút → publish Prometheus exporter custom (Day 20).
- **Alert**: 1 key > 5000 ops/s sustained 1m → page on-call.
- **Load test**: Gatling scenario 50% traffic dồn 1 product → verify L1 hit ratio cho viral key > 95%, Redis CPU < 60%.
- **Capacity planning**: tính trước: nếu viral campaign dự kiến 100k req/s, L1 max-size phải cover top key + Redis cluster size enough cho fan-out invalidate.

## 8. Trade-off accepted

- **L1 stale ≤60s** cho viral key. Nếu admin update giá viral product → 4 pod thấy giá mới trong 60s khác nhau. Acceptable cho catalog; **KHÔNG cho price tại checkout** (price snapshot ở order_items lúc tạo, không đọc cache).
- **Memory L1 cost**: 10k × ~500 bytes = 5 MB/JVM. Negligible.
- **Postpone sharding**: chấp nhận risk Redis CPU 80%+ nếu một campaign cực hot ngoài dự kiến. Mitigate: alert + manual key sharding ad-hoc nếu phát hiện.

## 9. Related

- Code: [`TwoTierCache.java`](../../services/product-service/src/main/java/com/ecom/product/config/cache/TwoTierCache.java)
- Related issue: [`issues/15-cache-stampede.md`](15-cache-stampede.md)
- Performance: [`performance/15b-two-tier-cache.md`](../performance/15b-two-tier-cache.md)
- ADR: [`decisions/012-two-tier-cache-caffeine-redis.md`](../decisions/012-two-tier-cache-caffeine-redis.md)
- Future system design: hot key sharding sẽ deep-dive ở Day 33 (Flash sale design — Week 6 system design intensive)
