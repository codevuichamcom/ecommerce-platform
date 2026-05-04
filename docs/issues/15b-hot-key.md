# Issue 15b — 🔥 Redis Hot Key (1 product viral)

> **Status**: ⏳ Skeleton — fill khi build Day 15.
> **Related day**: Day 15 (Redis cache aside + 2-tier).

---

## 1. Problem

> 1-2 câu: 1 product (`product:viral-iphone`) chiếm 50% traffic. Redis có cluster 3 node nhưng key đó chỉ ở 1 node → node đó CPU 95%, các node khác idle. Đây là **hot key problem**, khác cache stampede.

## 2. Symptoms

- (TODO) Redis CLUSTER node 1 CPU 95%, node 2-3 CPU 20%.
- (TODO) `redis-cli --hotkeys` show `product:viral-iphone` access 50k ops/s.
- (TODO) GET latency P99 tăng từ 1ms → 30ms cho mọi key trong node 1.
- (TODO) Application: `getProduct(viralId)` chậm hơn `getProduct(normalId)` 30x.

## 3. Root cause

- (TODO) Redis Cluster slot = CRC16(key) mod 16384. Key cố định → slot cố định → 1 node duy nhất.
- (TODO) Caching không giải quyết — đây là Redis chính nó là bottleneck, không phải DB.

## 4. Approaches compared

| Approach                                  | Pros                                       | Cons                                                       |
| ----------------------------------------- | ------------------------------------------ | ---------------------------------------------------------- |
| Local cache (Caffeine L1) + short TTL     | Triệt tiêu 95%+ hot key traffic vào Redis  | Stale data per JVM; cần invalidate ở mọi instance          |
| Key sharding (`product:viral-iphone:{0..9}`) | Phân tải đều cluster                    | Phải random read shard; write phải invalidate N shard      |
| Read replica (Redis read-only replica)    | Giảm load CPU node primary                 | Eventual consistency; client phải route                    |
| CDN trước Redis (cho data immutable)      | Off-load hoàn toàn                         | Chỉ work cho data ít đổi                                   |

## 5. Chosen — Caffeine L1 + Redis L2 (đã có ở Day 15) + key sharding cho top-N

- (TODO) Lý do: 2-tier cache đã giải quyết 95% hot key tự nhiên (L1 hit).
- (TODO) Top 100 product theo traffic → bật key sharding 5-way (config flag).
- (TODO) Detect hot key qua `redis-cli --hotkeys` job chạy mỗi 5 phút.

## 6. Fix

```java
// (TODO) Pseudocode
// 2-tier: L1 Caffeine 60s, L2 Redis 5min
// hot-key list được publish qua Kafka topic `cache.hot-keys` để các instance đồng bộ
```

## 7. Prevention

- (TODO) Metric: track per-key ops/s ở Redis (top 10).
- (TODO) Alert: 1 key > 5000 ops/s → page.
- (TODO) Test: Gatling 50% traffic vào 1 product → verify L1 hit > 90%.

## 8. Trade-off accepted

- (TODO) Local cache stale data lên tới 60s.
- (TODO) Memory cost: mỗi JVM 100MB Caffeine cache.

## 9. Related

- Code: `common-lib/src/main/java/com/ecom/common/cache/`
- Issue: [`issues/15-cache-stampede.md`](15-cache-stampede.md)
- Performance: [`performance/15b-two-tier-cache.md`](../performance/15b-two-tier-cache.md)
