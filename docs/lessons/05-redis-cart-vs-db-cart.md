# Lesson 05 — Redis-primary cart vs DB-primary cart

> **Status**: ✅ Done · 2026-05-09
> **Day**: 5 · `cart-service`

## 🎯 TL;DR

Cart là **transient state, high-write, low-read-aggregation, có TTL tự nhiên** → Redis Hash là storage model chính xác. RDBMS overengineer cho cart trong 95% case. Pattern "PG + Redis cache" cho cart là dual-source-of-truth không cần thiết: cart không cần ACID với data khác.

## ✅ Khi nào dùng Redis-primary cho cart

- Throughput target ≥1k req/sec, latency budget P99 < 50ms.
- Cart không cần audit history (hoặc audit đẩy event ra Mongo/Kafka riêng).
- Acceptable mất ≤1s cuối khi Redis crash (AOF everysec).
- Không cần JOIN cart với data khác trong cùng query (vd: "list cart kèm price" → frontend gọi 2 endpoint).

## ❌ Khi nào KHÔNG nên Redis-primary

- Cart có **legal/compliance retention** (vd: B2B procurement audit 7 năm) → đẩy snapshot ra DB warm storage.
- Cart cần **transactional với inventory** (reserve stock đồng thời add cart) → đó là design wishful thinking; thực tế reserve nên ở `placeOrder()`, không ở "add to cart". Đừng vẽ requirement giả để biện minh PG.
- **Strict idempotency cross-restart**: backend cần biết chắc chắn 100% mutation đã persist trước khi ack client → cần fsync mỗi op (`appendfsync always`) → throughput Redis tụt còn ~10k QPS, lúc đó PG comparable.

## ⚠️ Cạm bẫy

| Cạm bẫy | Hậu quả | Cách tránh |
|---|---|---|
| Dùng `HGET → modify → HSET` thay vì `HINCRBY` | Lost-update khi 2 tab cùng add 1 SKU; test 1-thread không phát hiện | Dùng `opsForHash().increment()` ([`CartService.java:50`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java#L50)); IT 100-thread verify |
| Refresh TTL ở GET cart | Cart không bao giờ expire khi user idle browse → vi phạm "7 ngày inactivity → drop" | Chỉ `EXPIRE` ở mutation, không ở read |
| Lưu `qty` dạng JSON string (`{"qty":5}`) | `HINCRBY` reject (NaN) → phải HGET-modify-HSET → lost-update | Lưu raw integer string; nếu cần struct dùng field tách (sku.qty, sku.addedAt) |
| Dùng `KEYS cart:*` để admin scan | Block Redis main thread O(N) | Dùng `SCAN` cursor; tốt hơn nữa: emit event ra Mongo cho analytic query |
| Quên cap qty/SKU | Bot HINCRBY 1M lần → 1 cart vỡ Redis memory | Hard cap (`maxQtyPerItem=999`, `maxItemsPerCart=100`); rollback decrement nếu vượt |

## 🆚 Approaches compared

| # | Approach | Pros | Cons |
| - | -------- | ---- | ---- |
| A | Postgres primary | ACID, SQL debug | ~50× slower per op, no native TTL, overkill |
| B | PG primary + Redis cache | "Familiar" pattern | Dual-source-of-truth: invalidation race, sync lag, 2× code |
| C ✅ | Redis primary, Hash, TTL | Throughput cao, atomic field-level, native TTL | Mất ≤1s khi crash, không audit (chấp nhận) |
| D | Redis primary + async PG snapshot | Có history cho analytic | Thêm sync job; analytic dùng Mongo event store hợp hơn (Day 23) |

## 🎤 Trả lời phỏng vấn

**Q**: "Tại sao cart không dùng Postgres?"
**A**: Cart là transient state, không có invariant cross-record cần ACID. Domain model `{cartId → {sku → qty}}` map 1-1 với Redis Hash. `HINCRBY` atomic ở field-level chống lost-update mà không cần row lock. TTL native loại 1 cron job. Throughput Redis cao gấp ~50× PG cho workload này. Trade-off: mất ≤1s cuối khi Redis crash — chấp nhận vì cart không phải compliance data.

**Q**: "Redis crash → user mất cart, có ổn không?"
**A**: Mất ≤1s cuối với AOF everysec. UX impact thấp vì user đang active sẽ tự re-add (cart là frontend state mirror); user inactive không quan tâm. Production: Redis Sentinel + RDB snapshot 5min hybrid → recovery <30s với data loss <1s.

## 🔗 Related

- ADR: [`decisions/004-redis-primary-for-cart.md`](../decisions/004-redis-primary-for-cart.md)
- Lesson: [`lessons/05b-redis-data-structures.md`](05b-redis-data-structures.md)
- Issue: [`issues/05-cart-merge-conflict-on-login.md`](../issues/05-cart-merge-conflict-on-login.md)
- Code: [`CartService.java`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java)
