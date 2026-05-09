# Interview — Day 5: Cart Service (Redis-primary)

> **Status**: ✅ Done · 2026-05-09
> **Mục tiêu**: drill câu phỏng vấn Senior về Redis data structure choice, atomicity (HINCRBY), TTL semantics, anonymous→user merge logic — kèm bối cảnh kể được story.

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — ecommerce Series-A Việt Nam, ~500k MAU, đang chuẩn bị campaign Black Friday peak 2k req/sec.
- **Role giao việc**: Anh Hùng (Tech Lead, ex-Tiki). Họp 1-1: "Cart cũ trên Postgres `cart_db` đang bottleneck — peak campaign trước P99 lên 800ms vì WAL contention. Build cho tôi `cart-service` mới, Redis primary, trong 1 sprint. Không dual-write, không cargo-cult cache layer."
- **Bạn**: Backend engineer L4, owner cart domain end-to-end.
- **Reviewer**: Anh Hùng (TL) review data layer choice (vì sao Redis primary, không dual-write); Chị Linh (PM) review UX merge cart sau login (3 scenario).
- **Deadline**: 1 sprint day. Demo: 5 endpoint pass smoke test + 100-thread concurrent add cùng SKU phải sum đúng (no lost-update) + merge 3 scenario (empty/overlap/disjoint) đúng.
- **Constraint thực tế**:
  - Cart phải sống qua user logout/relogin trong 7 ngày (rule UX product).
  - Không được mất item khi anonymous user login.
  - Không được introduce stock-reservation ở "add to cart" — đó là Day 6 (`placeOrder()`).
  - Peak 2k req/sec ngày sale; throughput target ≥5k để buffer.
- **Definition of Done**:
  - Build: `:services:cart-service:build` ✅, 4 unit test PASS, 2 IT skip default.
  - 100-thread concurrent add 1 SKU → tổng đúng 100 (verify HINCRBY atomicity).
  - Merge logic test: anon SKU-A=2 + user SKU-A=3 → merged SKU-A=5; anon DEL.
  - ADR-004 (Redis primary) + Lesson 05 + Lesson 05b + Issue 05 (9-section).

---

## 🎤 Q&A

### Q1 — "Tại sao em chọn Redis primary cho cart, không phải Postgres?"

**Strong answer**: 3 lý do.
1. **Domain match storage**: cart là `{cartId → {sku → qty}}` — Redis Hash chính xác là cấu trúc đó. PG phải normalize ra `cart_items` với composite index chỉ để query theo cartId.
2. **Atomic primitive**: `HINCRBY` chống lost-update field-level, không cần row lock. PG cần `SELECT FOR UPDATE` hoặc optimistic + retry — đắt và phức tạp hơn cho 1 use case không có ACID-cross-record.
3. **Transient state**: cart không phải compliance data. Mất ≤1s khi Redis crash (AOF everysec) chấp nhận được. RDBMS WAL flush mỗi op overhead ~50× cho 0 benefit.

**Trap**: "Nếu Redis crash mất cart user thấy thế nào?" → User active đang dùng cart sẽ tự re-add (cart là frontend state mirror); user inactive không quan tâm. Production có Redis Sentinel + RDB snapshot 5min hybrid → recovery <30s, data loss <1s.

**Trap**: "Vậy luôn luôn Redis primary cho cart?" → KHÔNG. B2B procurement có legal retention 7 năm → đẩy snapshot ra DB warm storage. Nếu cart cần atomic transaction với inventory — đó là design wish, thực tế reserve stock nên ở `placeOrder()`, không ở "add to cart".

---

### Q2 — "Em dùng Hash hay String JSON để lưu cart? Tại sao?"

**Strong answer**: **Hash**. Field=SKU, value=qty (integer string).

Lý do quyết định:
- `HINCRBY cart:user:42 SKU-A 1` atomic ở **field-level**, 2 tab cùng add 1 SKU sẽ ra qty=2 chính xác.
- String JSON (`SET cart:user:42 '{"SKU-A":3}'`) buộc phải `GET → parse → modify → SET` cho mỗi mutation → **lost-update** khi 2 op concurrent: thread A get qty=3, thread B get qty=3, A set qty=4, B set qty=4 → đáng lẽ là 5.

**Trap**: "Vậy nếu lưu Hash với JSON value?" — `HSET cart:user:42 SKU-A '{"qty":3,"addedAt":...}'`. Field atomic nhưng phải `HGET → parse → modify → HSET` → lost-update tái xuất. Nếu cần struct, tách field: `HSET cart:user:42 SKU-A:qty 3 SKU-A:addedAt 1715...`.

**Trap**: "Test 1-thread đều pass — sao biết được lost-update?" → đó chính là tại sao có IT 100-thread ([`CartConcurrencyIT.hundredThreads_addOne_noLostUpdate`](../../services/cart-service/src/test/java/com/ecom/cart/CartConcurrencyIT.java)). Concurrency bug không hiện ra ở unit test sequential.

---

### Q3 — "Anonymous user thêm 2 SKU-A, login vào account có sẵn 1 SKU-A → kết quả?"

**Strong answer**: **Sum = 3** (rule chosen ở [issue 05](../issues/05-cart-merge-conflict-on-login.md)). Logic:

```
HGETALL cart:anon:{token}        → get anonymous items
loop: HINCRBY cart:user:{userId} sku qty
DEL cart:anon:{token}
```

4 approach đã compare:
| # | Approach | Verdict |
|---|---|---|
| Overwrite (user wins) | Mất item anon vừa add → conversion drop | ❌ |
| Overwrite (anon wins) | Mất cart user cũ → tệ hơn | ❌ |
| **Sum quantity** | Tôn trọng user effort 2 chiều | ✅ |
| UI prompt | Thêm 1 step ngay điểm critical (post-login) | ❌ |

**Trap**: "Sum nhưng vượt stock available thì sao?" → Cart KHÔNG đụng inventory. Validate stock ở `placeOrder()` (Day 6). Frontend có thể show soft-warning ở cart view bằng cách query inventory riêng, nhưng KHÔNG reserve stock từ "add to cart" — flash sale sẽ "lock stock ảo 7 ngày", không chấp nhận được.

**Trap**: "User login từ 2 device cùng lúc, cùng anon token sao?" → Race window có nhưng impact thấp: HINCRBY là atomic per-field, worst case là double-merge. Anon DEL cuối → call thứ 2 không thấy gì merge tiếp. Nếu cần idempotency strict (vd: payment) → dedup token (Day 10 sẽ giải quyết kỹ).

---

### Q4 — "TTL 7 ngày — refresh khi nào?"

**Strong answer**: Chỉ ở **mutation** (add/update/remove/merge), KHÔNG ở read.

Lý do: nếu refresh ở `GET /cart`, cart không bao giờ expire khi user idle browse → vi phạm rule "7 ngày inactivity → drop". Refresh-on-read = effectively infinite TTL.

```java
// services/cart-service/.../CartService.java
private void refreshTtl(String key) {
    redis.expire(key, props.ttl().toSeconds(), TimeUnit.SECONDS);
}
// gọi sau mỗi addItem/updateItem/removeItem/merge — KHÔNG ở getCart
```

**Trap**: "User add → idle 6 ngày → add tiếp, TTL còn 1 ngày hay 7?" → 7. Mutation refresh full TTL. Đây là quyết định design (option khác: chỉ refresh phần TTL còn lại — phức tạp, không cần thiết).

**Trap**: "Redis EXPIRE atomic với operation trước không?" → KHÔNG. EXPIRE là 1 op riêng. Nếu Redis crash giữa HINCRBY và EXPIRE, cart mới mutate sẽ giữ TTL cũ. Chấp nhận được vì TTL mismatch vài giây không có user-facing impact.

---

### Q5 — "Redis xuống 30s → cart-service phản ứng thế nào?"

**Strong answer**: 
- Spring Data Redis với Lettuce mặc định throw `RedisConnectionFailureException` → bubble up qua `GlobalExceptionHandler` → 503 với code `INTERNAL_ERROR`.
- Day 12 sẽ wire Resilience4j circuit breaker: sau N fail liên tiếp, circuit open → trả 503 ngay với message "giỏ hàng đang khôi phục, vui lòng thử lại sau" — không spam Redis recovery.
- KHÔNG fallback Postgres. Lý do: dual-source-of-truth. Cart Redis crash → user thấy empty cart vài chục giây → re-add. UX tệ nhưng không corrupt data.

**Trap**: "Tại sao không write-through Postgres để có fallback?" → Dual-write problem: PG write thành công, Redis write fail (hoặc ngược lại) → 2 source không nhất quán. Latency cũng double. Trade-off này không đáng cho transient state.

**Trap**: "Vậy Redis primary single point of failure?" → Production: Redis Sentinel cluster (3 node), failover <30s. Hoặc Redis Cluster (Day 33) nếu cần shard.

---

## 🧠 Senior mindset notes

- **Pitfall AI/junior dễ mắc**: dùng String JSON cho cart vì "trông sạch hơn" hoặc HGET-modify-HSET vì familiar pattern. Cả 2 đều lost-update under concurrency. Test 1-thread pass → ship → bug production. Phải có IT 100-thread bắt buộc.
- **Scale 10x note**: 1 Redis instance ~50k cart ops/sec đủ cho 500k MAU peak; 5M MAU cần Redis Cluster, shard theo `userId hash slot`. Anon cart token cần hash-tag để cùng slot với user cart sau merge — vd `cart:anon:{userId}:abc` (curly braces là Cluster hash-tag). Nhưng anon chưa biết userId — đây là vấn đề real, ADR sẽ revisit khi đó.
- **Trade-off non-obvious**: cart không có audit history. Nếu PM hỏi "user X đã add gì trong session Y?" → câu trả lời là "không biết". Phải emit event (Day 9 Kafka) ra Mongo event store (Day 23) nếu cần. Đừng nhét audit log vào Redis Hash thêm field.

---

## 🤖 AI Playbook

- **AI làm tốt**: scaffold `CartController` + DTO records + Redis config bean + unit test cho `CartIdTest` (sealed pattern matching). Generate boilerplate Spring Security filter copy từ product-service.
- **Prompt mẫu** (giữ ngắn, ép pattern):
  > "Generate Spring `@RestController` for cart, 6 endpoints (add/update/remove/clear/get/merge). Java 21 records cho DTO. Inject `CartService` + `CartIdResolver`. Use HttpServletRequest, @Valid, @AuthenticationPrincipal."
- **Risk**: AI dễ generate `HGET → modify → HSET` (training data có nhiều JSON-string cart example) → silent lost-update. Cũng hay miss `EXPIRE` refresh sau mutation, hoặc refresh ở GET (sai semantic). Có thể quên cap qty/SKU → bot abuse vector.
- **Validate**: 
  1. Đọc kỹ `addItem` xem có dùng `opsForHash().increment()` (HINCRBY) không.
  2. Grep `expire(` — phải có ở mutation, KHÔNG ở `getCart`.
  3. IT 100-thread bắt buộc — nếu không pass thì code có lost-update.
  4. Verify cap: `maxQtyPerItem` rollback bằng decrement (không pre-check, tránh TOCTOU race).

---

## 🔗 Related

- ADR: [`decisions/004-redis-primary-for-cart.md`](../decisions/004-redis-primary-for-cart.md)
- Lesson: [`lessons/05-redis-cart-vs-db-cart.md`](../lessons/05-redis-cart-vs-db-cart.md)
- Lesson: [`lessons/05b-redis-data-structures.md`](../lessons/05b-redis-data-structures.md)
- Issue: [`issues/05-cart-merge-conflict-on-login.md`](../issues/05-cart-merge-conflict-on-login.md)
- Code: [`services/cart-service/`](../../services/cart-service/)
