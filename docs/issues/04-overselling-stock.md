# Issue 04 — 🔥 Overselling stock trong flash sale

> **Status**: ✅ Done · 2026-05-09
> **Severity**: Sev-1 (revenue impact + reputation)
> **Related day**: Day 4

---

## 1. Problem

Flash sale 12.12 ShopVN: SKU iPhone 15 stock = 1000, sale 30 phút. Sau sale, dashboard show **bán 1047 đơn** — overshoot 47. Warehouse báo chỉ có 1000 máy → finance phải refund + xin lỗi trên fanpage.

## 2. Symptoms

- Logs có 47 dòng `INSERT INTO orders ... iphone-15` sau khi `stock.qty = 0`.
- Postgres query `SELECT sku, quantity, reserved FROM stock WHERE sku='iphone-15'` → `quantity=1000, reserved=1047` → CHECK constraint vi phạm nếu có (lúc đó chưa có).
- Customer nhận email confirm, hôm sau nhận email refund.
- Grafana: spike `place_order_total{sku=iphone-15}` từ 50 → 3000 RPS trong 5 giây đầu.

## 3. Root cause

Code cũ (procedural ở service layer, KHÔNG có lock):

```java
@Transactional
public void placeOrder(String sku, int qty) {
    Stock stock = stockRepo.findById(sku).orElseThrow();
    if (stock.getQuantity() - stock.getReserved() >= qty) { // ← TOCTOU
        stock.setReserved(stock.getReserved() + qty);
        stockRepo.save(stock);
    } else {
        throw new InsufficientStockException();
    }
}
```

3000 RPS cùng SKU → nhiều thread cùng pass `if`-check vì lúc đó `reserved` chưa update → cùng `INSERT order` → tổng reserved vượt quantity. Postgres `READ COMMITTED` mặc định không chặn lost update — `READ COMMITTED` chỉ chặn dirty read.

Đây là **TOCTOU race condition** (Time Of Check vs Time Of Use) classic. Cộng thêm:
- KHÔNG có `@Version` ở entity → Hibernate UPDATE không có condition `WHERE version=?` → lost update âm thầm.
- KHÔNG có DB-level CHECK constraint → vi phạm invariant không bị reject ở DB layer.
- KHÔNG có concurrency test → CI không phát hiện.

## 4. Approaches compared

| Approach | Pros | Cons |
| -------- | ---- | ---- |
| **A. Optimistic lock `@Version` + retry** ✅ | Throughput cao, không khóa DB, đơn giản (chỉ thêm 1 field + `@Retryable`); retry < 500ms worst-case | Retry storm khi contention >1000 RPS/SKU; cần monitoring để detect |
| **B. Pessimistic `SELECT FOR UPDATE`** | Không retry, logic tuyến tính, predictable | Lock queue serialize toàn bộ request cùng SKU → P99 tăng theo số request đang chờ; deadlock risk khi multi-row; throughput thấp |
| **C. Redis atomic `DECR` + Lua script** | Sub-ms latency, scale 100k RPS/SKU; perfect cho flash sale | Eventual consistency với Postgres (sync background); recovery phức tạp khi Redis crash; ops 2 store; mất audit trail nếu chỉ dùng Redis |
| **D. Postgres `SERIALIZABLE` isolation + plain UPDATE** | 1 dòng config, đảm bảo invariant; không cần `@Version` | Throughput thấp (SSI predicate lock overhead 20-30%); vẫn phải retry serialization failure → cuối cùng vẫn cần `@Retryable` → không đơn giản hơn (A) |

## 5. Chosen + Why

**(A) Optimistic `@Version` + retry**, lý do gắn với context Day 4 / ShopVN:

1. **Workload thực tế MVP**: <500 RPS/SKU phổ biến — thậm chí flash sale 30 phút cũng phân tán nhiều SKU. Hot SKU >1000 RPS là edge case, có signal (metric) trước khi xảy ra.
2. **ROI**: thêm `@Version` (sẵn ở `BaseEntity` common-lib) + `@Retryable` (Spring Retry, 1 dependency) — chi phí gần như 0. So với (C) phải build infrastructure Redis sync + outbox dual-write.
3. **DDD fit**: Aggregate `Stock` đã enforce invariant `reserved ≤ quantity`. Optimistic lock bảo vệ thêm tầng race condition. Code clean, junior đọc được.
4. **Migration path rõ**: nếu metric `optimistic_lock_retry_total{sku=?}` p99 > 5/sec/SKU → biết đến lúc upgrade (C) Redis Lua. Day 33 system-design sẽ thiết kế.

KHÔNG chọn:
- (B): MVP không có dataset đủ để chứng minh lock contention chấp nhận được; với 100 thread test, lock queue sẽ làm P99 vượt 1s — UX tệ.
- (C): premature — chưa có data chứng minh cần. Build Redis sync sai → khó debug. Để Day 33 design có chủ ý.
- (D): SERIALIZABLE vẫn cần retry → cuối cùng code complexity = (A) + throughput thấp hơn → loại.

## 6. Fix

Triển khai ở Day 4 ([`services/inventory-service/`](../../services/inventory-service/)):

1. **Aggregate `Stock`** enforce invariant trong method:
   - [`Stock.java:reserve()`](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/Stock.java) — throw `InsufficientStockException` nếu `qty > available()`.
   - `@Version` kế thừa từ [`BaseEntity`](../../common-lib/src/main/java/com/ecom/common/audit/BaseEntity.java).

2. **Application service `@Retryable`** với fresh tx mỗi attempt:
   ```java
   @Retryable(retryFor = OptimisticLockingFailureException.class,
              maxAttempts = 4,
              backoff = @Backoff(delay = 50, multiplier = 2.0, maxDelay = 500))
   @Transactional(propagation = REQUIRES_NEW)
   public StockResponse reserve(String sku, int qty) { ... }
   ```
   File: [`InventoryService.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventoryService.java).

3. **DB-level defense-in-depth**: `CHECK (reserved <= quantity)` ở [`V1__create_stock.sql`](../../services/inventory-service/src/main/resources/db/migration/V1__create_stock.sql) — admin SQL adhoc cũng không phá invariant.

4. **Concurrency test 100-thread** ([`InventoryConcurrencyIT.java`](../../services/inventory-service/src/test/java/com/ecom/inventory/InventoryConcurrencyIT.java)): 100 thread reserve SKU stock=50 → đúng 50 success, 50 fail `InsufficientStockException`, final `reserved=50` (no oversell).

## 7. Prevention

- ✅ **CI gate**: concurrency test chạy mỗi PR (sau Day 8 khi GitHub Actions có Postgres service container).
- ✅ **DB CHECK constraint**: invariant ở 2 layer (app + DB) — defense-in-depth.
- ⏳ **Metric** (Day 9 wire OTel): expose counter `optimistic_lock_retry_total{sku=?}` + Grafana alert > 10/sec/SKU.
- ⏳ **Code review trap** (Day 7 cumulative): thêm vào [`docs/review/ai-junior-traps.md`](../review/ai-junior-traps.md) pattern "invariant ở service layer thay vì aggregate" → reject PR.
- ⏳ **Runbook** (Day 12): kafka-topic-recovery + retry-storm-triage.

## 8. Trade-off accepted

- **Worst-case latency**: retry 4 lần với backoff exponential → tối đa ~1s khi unlucky. Chấp nhận vì median < 50ms, P99 < 200ms ở dataset hiện tại.
- **Hot SKU >1000 RPS sẽ degrade**: optimistic không scale linear với contention. Migration path đã chốt (Day 33 Redis Lua) — KHÔNG fix sớm vì chưa có data.
- **Domain event `StockReserved` raise trong aggregate** nhưng publish chỉ AFTER_COMMIT (Day 9). Day 4 chỉ register event, chưa publish thật → chấp nhận, không leak ra ngoài.
- **`@Retryable` AOP + `REQUIRES_NEW`**: mỗi retry mở tx mới → cost +1-2ms/retry. Chấp nhận vì retry là edge case, không phải hot path.

## 9. Related

**Code**:
- [`Stock.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/Stock.java) — Aggregate root
- [`InventoryService.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventoryService.java) — `@Retryable` + REQUIRES_NEW
- [`V1__create_stock.sql`](../../services/inventory-service/src/main/resources/db/migration/V1__create_stock.sql) — CHECK constraint
- [`InventoryConcurrencyIT.java`](../../services/inventory-service/src/test/java/com/ecom/inventory/InventoryConcurrencyIT.java) — 100-thread proof

**Docs**:
- [Lesson 04 — Optimistic locking](../lessons/04-optimistic-locking.md)
- [Lesson 04b — Transaction isolation](../lessons/04b-transaction-isolation.md)
- [ADR-003 — DDD selective](../decisions/003-ddd-for-order-inventory-payment.md)
- [Interview Day 04](../interview/day-04-inventory.md)
- (Future) [Day 33 — Flash sale design](../system-design/33-flash-sale.md) — Redis Lua migration path
