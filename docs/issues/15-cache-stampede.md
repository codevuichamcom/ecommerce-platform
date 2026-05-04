# Issue 15 — 🔥 Cache Stampede (Thundering Herd)

> **Status**: ⏳ Skeleton — fill khi build Day 15.
> **Related day**: Day 15 (Redis cache aside + 2-tier).

---

## 1. Problem

> 1-2 câu: 1 hot key (vd `product:123` của sản phẩm viral) hết TTL cùng lúc 1000 request đang đọc → 1000 request cùng miss cache → 1000 request đập DB → DB CPU spike, latency tăng, có thể down.

## 2. Symptoms

- (TODO) Grafana: cache hit ratio drop từ 95% → 60% trong 5s.
- (TODO) DB query rate spike 10x.
- (TODO) P99 latency `getProduct` tăng từ 20ms → 800ms.
- (TODO) Application log: hàng loạt query SQL giống nhau cho cùng `productId`.

## 3. Root cause

- (TODO) TTL absolute (set 1 lần lúc cache, expire 1 lần) → mọi request expire cùng lúc.
- (TODO) Không có lock/single-flight ở app layer → N request cùng compute lại.

## 4. Approaches compared

| Approach                                | Pros                                          | Cons                                                          |
| --------------------------------------- | --------------------------------------------- | ------------------------------------------------------------- |
| Distributed lock (Redis SET NX)         | Đảm bảo chỉ 1 process refresh                  | Lock contention, lỡ lock + GC pause = nguy hiểm; thêm 1 round trip |
| In-process single-flight (Caffeine `LoadingCache.get`) | Không cần Redis, nhanh                | Chỉ effective trong 1 JVM; multi-instance vẫn stampede        |
| Probabilistic early expiration (XFetch) | Đơn giản, không cần lock                      | Cần code refresh logic, có chance 2 process cùng refresh nhẹ  |
| Refresh-ahead (background scheduled)    | Cache không bao giờ expire trong giờ peak     | Phải biết key nào "hot" trước; cost compute liên tục          |
| Stale-while-revalidate                  | UX tốt — trả stale, refresh async             | Stale data trong khoảng grace period                          |

## 5. Chosen — Probabilistic early expiration + 2-tier (Caffeine L1 + Redis L2)

- (TODO) Lý do gắn project: 2-tier giảm Redis load 80%; XFetch chặn stampede ở L2.
- (TODO) Trade-off vs lock: không cần infrastructure thêm (Redis lock); accept 1 chút duplicate compute.
- (TODO) Hot key viral → Caffeine L1 hit 99% → Redis chỉ chịu 1% traffic → DB chỉ chịu 0.01%.

## 6. Fix

```java
// (TODO) Pseudocode
// XFetch: refresh key khi (now + delta * beta * ln(rand())) > expireAt
// delta = thời gian compute, beta = constant (~1.0)
```

- (TODO) Link tới `services/product-service/src/main/java/com/ecom/product/cache/ProductCache.java`.

## 7. Prevention

- (TODO) Metric: track P99 cache miss latency per key.
- (TODO) Alert: cache hit ratio < 90% trong 1 phút → page.
- (TODO) Test: load test với 10k concurrent request cùng 1 product → đảm bảo DB query rate < 10/s.

## 8. Trade-off accepted

- (TODO) Stale data trong khoảng XFetch refresh window (~ms).
- (TODO) Compute lặp nhẹ (2-3 process refresh thay vì 1) — chấp nhận để tránh phức tạp lock.

## 9. Related

- Code: `services/product-service/.../ProductCache.java`
- Lesson: [`performance/15b-two-tier-cache.md`](../performance/15b-two-tier-cache.md)
- Related issue: [`issues/15b-hot-key.md`](15b-hot-key.md)
