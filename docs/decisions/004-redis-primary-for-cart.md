# ADR-004 — Redis làm PRIMARY store cho cart-service (không Postgres)

- **Status**: Accepted
- **Date**: 2026-05-09
- **Deciders**: Tonny (Tech Lead)
- **Supersedes**: bổ sung ADR-001 §"DB-per-service" — confirm cart không dùng RDBMS.

## 🏗️ Decision

`cart-service` lưu cart trực tiếp trên Redis 7 dạng **Hash** (`cart:{ns}:{id}` → field=SKU, value=qty), TTL 7 ngày. **KHÔNG** có Postgres `cart_db` schema, **KHÔNG** dual-write. `cart_db` trong [`docker-compose.yml`](../../docker-compose.yml) tạo nhưng để empty (loại bỏ Day 7 cleanup).

Mutation atomic ở field-level qua `HINCRBY` / `HSET`. Read qua `HGETALL`. Anonymous → user merge: `HGETALL anon → loop HINCRBY user → DEL anon`.

## 📚 Context

Cart có 3 đặc tính khiến RDBMS thừa:

1. **Transient state**: cart không phải compliance / audit data. Mất 1s cuối khi Redis crash là chấp nhận được (AOF mặc định fsync everysec). Order/payment KHÁC.
2. **High-write, low-read-aggregation**: mỗi user mutation ~10-30 lần/session, query luôn theo cartId — không có analytic JOIN.
3. **Native TTL**: Redis `EXPIRE` thay 1 cron job purge stale row. RDBMS phải tự build batch delete.

Mặt khác, ShopVN bối cảnh peak 2k req/sec ngày sale → 1 Postgres instance ~5k QPS với MVCC chưa tới giới hạn nhưng rất gần. Mỗi cart op trên PG sẽ tốn 1 transaction WAL flush — overhead cao gấp ~50× so với Redis Hash op trên cùng hardware.

## 🆚 Alternatives considered

| # | Approach | Pros | Cons |
| - | -------- | ---- | ---- |
| A | **Postgres primary, không cache** | ACID, JOIN dễ với products, dễ debug bằng SQL | Throughput thấp (~5k QPS PG vs ~100k Redis), không native TTL, mỗi cart op = 1 WAL flush. Overengineer cho transient state. |
| B | **Postgres primary + Redis cache (cache-aside)** | "An toàn" về persistence | Dual-source-of-truth: invalidation race, sync lag, code complexity gấp đôi cho 0 benefit (cart không cần ACID với data khác). Thấy nhiều ở junior project — pattern sai chỗ. |
| C ✅ | **Redis primary, Hash structure, TTL** | Throughput cao, native TTL, atomic field-level (HINCRBY), data model match domain (key→fields) | Mất ≤1s cuối nếu Redis crash (AOF everysec). Không có audit history. SCAN cross-user phiền (nhưng cart không cần). |
| D | **Redis primary + Postgres async snapshot** | Vẫn nhanh, có history cho analytic | Thêm 1 sync job, dual-write problem mini. Analytic cart dùng Day 23 MongoDB event store hợp hơn. |

## ✅ Chosen — Rationale (C)

3 lý do quyết định:

1. **Domain match storage model**. Cart = `{cartId → {sku → qty}}`. Redis Hash chính xác là cấu trúc đó. PG phải normalize ra `cart_items(cart_id, sku, qty, ...)` với index composite — chỉ để truy vấn theo cartId thuần.
2. **Atomic primitive sẵn**. `HINCRBY` chống lost-update khi 2 tab cùng add 1 SKU. PG cần `UPDATE ... WHERE qty=? RETURNING` + retry hoặc `SELECT FOR UPDATE` — đắt hơn nhiều.
3. **Operational đơn giản**. Cart không có invariant cross-aggregate (không cần tx với inventory/order). Mất sweet-spot value của RDBMS.

## ⚖️ Trade-offs

**Accepted**:
- Mất ≤1s cart data nếu Redis instance crash (AOF everysec). Mitigation: AOF + RDB hybrid + Redis Sentinel/Cluster ở prod (Day 15 sẽ đề cập 2-tier; Day 33 system-design phân tích flash sale).
- Không audit history. Nếu 6 tháng sau cần "user A đã thêm gì vào cart trong session X" → phải log event ra Mongo (Day 23). Hôm nay không cần.
- SCAN cross-user khó (vd: tìm tất cả cart chứa SKU đang sale) — nhưng đây là analytic concern, không phải transactional concern. Dùng Mongo event store cho query này.

**Rejected**:
- Postgres ACID benefit không giá trị cho cart (không có cross-record invariant cần atomic).
- "Cache layer trên PG" pattern bị reject vì dual-source-of-truth.

## 📈 Consequences

- `cart-service` không có Flyway migration, không có JPA entity. Code đơn giản hơn `auth/product` ~40% LOC.
- Nếu scale 5M MAU (~10× hiện tại), 1 Redis instance không đủ → chuyển Redis Cluster, shard theo `userId` hash slot. ADR sẽ revisit khi đó.
- Test infra: dùng GenericContainer Redis ([`RedisTestcontainerConfig.java`](../../services/cart-service/src/test/java/com/ecom/cart/support/RedisTestcontainerConfig.java)), không cần Postgres Testcontainers.

## 🔗 Related

- Code: [`services/cart-service/src/main/java/com/ecom/cart/service/CartService.java`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java)
- Lesson: [`lessons/05-redis-cart-vs-db-cart.md`](../lessons/05-redis-cart-vs-db-cart.md)
- Lesson: [`lessons/05b-redis-data-structures.md`](../lessons/05b-redis-data-structures.md)
- Issue: [`issues/05-cart-merge-conflict-on-login.md`](../issues/05-cart-merge-conflict-on-login.md)
- Interview: [`interview/day-05-cart.md`](../interview/day-05-cart.md)
- ADR-001: [`decisions/001-why-hybrid-architecture.md`](001-why-hybrid-architecture.md)
