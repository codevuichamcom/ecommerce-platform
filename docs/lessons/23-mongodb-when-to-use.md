# Lesson 23 — MongoDB: khi nào dùng, khi nào KHÔNG

> 🎯 Mục tiêu: chốt mental model để answer câu phỏng vấn classic "khi nào Mongo
> thay Postgres?" mà KHÔNG flounder — và để tự mình không cargo-cult.

---

## TL;DR

MongoDB là **document store**: lưu JSON/BSON document, schema linh hoạt, scale
ngang qua sharding. Nó KHÔNG phải "Postgres nhưng nhanh hơn". Nó **thắng** ở
3 chỗ: schema đa hình, write throughput append-heavy, scale đọc ngang. Nó
**thua** ở chỗ Postgres mạnh: invariant multi-row cần ACID, join phức tạp,
strong consistency mặc định.

Day 23 dùng Mongo CÓ CHỦ Ý ở 2 chỗ ([ADR-011](../decisions/011-mongo-for-analytics-and-flexible-attributes.md)):
1. **analytics event store** — event đa hình + TTL + aggregation.
2. **product catalog read-model** — flexible attributes (derived, Postgres vẫn là truth).

---

## ✅ Khi nào dùng Mongo

| Tín hiệu | Vì sao Mongo hợp |
|----------|------------------|
| **Schema đa hình thật** | Mỗi record shape khác nhau (event types, product attributes theo category). Ép vào cột quan hệ = NULL la liệt hoặc EAV. |
| **Append-heavy, ít update, không invariant cross-row** | Event log, audit, activity feed. Single-doc write atomic là đủ — không cần multi-row ACID. |
| **Đọc nguyên document theo id/khoá** | Trang chi tiết: lấy 1 document giàu (denormalized) thay vì join 5 bảng. |
| **Cần TTL auto-expire** | Mongo TTL index native (event 90 ngày tự rụng). Postgres phải cron DELETE. |
| **Cần scale đọc/ghi ngang** | Sharding theo shard key (vd `occurredAt`, `userId`) — phân tán dễ hơn Postgres. |
| **Aggregation pipeline** | `$match→$group→$sort→$limit` biểu đạt mạnh, chạy gần data. |

---

## ❌ Khi nào KHÔNG dùng Mongo

| Tín hiệu | Vì sao tránh |
|----------|--------------|
| **Data có invariant chặt** (`balance ≥ 0`, `reserved ≤ quantity`) | Cần ACID multi-row + lock. Đây là sân của Postgres (Day 4 inventory). Nhét vào Mongo = mất an toàn hoặc gánh replica-set txn đắt. |
| **Quan hệ nhiều-nhiều + join phức tạp** | Mongo `$lookup` yếu + chậm so với SQL JOIN. Relational thắng. |
| **Cần strong consistency mặc định + transaction nhiều bảng** | Order + payment + inventory. Postgres `SERIALIZABLE`/`REPEATABLE READ` (Day 4b) tự nhiên hơn. |
| **Report ad-hoc kiểu BI** | SQL + cột-store (ClickHouse) tốt hơn aggregation pipeline cho query xoay nhiều chiều. |
| **"Cho hợp xu hướng"** | 🔴 Anti-pattern. Mongo không free — thêm ops/backup/skill. Không có lý do cụ thể = đừng. |

> ⚠️ **Cạm bẫy lớn nhất**: dùng Mongo làm primary store cho data có invariant
> vì "nghe nói Mongo nhanh". Single-node Mongo (dev) KHÔNG có multi-doc
> transaction → 2 write có thể nửa-vời, không rollback. Xem
> [issue 23](../issues/23-mongodb-no-transaction-trap.md).

---

## 🪤 Cạm bẫy (đã gặp khi build Day 23)

1. **TTL không real-time**: background thread chạy ~60s/lần → document quá hạn
   có thể "sống" thêm tới 60s. Report phải chịu được; đừng assume xoá tức thì.
2. **TTL index phải trên field kiểu Date**: `occurredAt` (Instant) → BSON Date.
   Nếu lưu epoch long thì TTL KHÔNG hoạt động.
3. **`auto-index-creation`**: bật thì Mongo tạo index theo `@Indexed` annotation
   lúc map class — khó kiểm soát + dễ "ghost index". Day 23 analytics TẮT nó,
   tạo index tường minh trong [`MongoIndexConfig`](../../services/analytics-service/src/main/java/com/ecom/analytics/config/MongoIndexConfig.java).
4. **Index field lồng nhau với key động**: filter `attributes.<key>` với key
   động → không thể index sẵn mọi key. Hoặc index có chủ đích key hot, hoặc
   wildcard index `{"attributes.$**":1}` (tốn ghi). Thu hẹp bằng `categorySlug`
   index trước.
5. **Aggregation thiếu index = COLLSCAN**: `$match` đầu pipeline phải khớp index
   (compound `type+occurredAt`) nếu không quét cả collection. `explain()` để check.
6. **uuid-representation**: không set `standard` → UUID lưu legacy subtype 3,
   lệch với driver ngôn ngữ khác. Day 23 set `standard`.

---

## ⚔️ Approaches compared — event store (rút gọn từ ADR)

| Approach | Khi nào chọn |
|----------|--------------|
| Postgres JSONB / partitioned | Volume nhỏ, không muốn thêm storage, team chỉ biết SQL |
| **Mongo (chosen)** | Schema đa hình + TTL + aggregation + scale ngang — Day 23 |
| Elasticsearch | KHÔNG (search engine, không phải event store; primary trên ES = anti-pattern) |
| ClickHouse | Volume cực lớn, OLAP columnar — over-engineer ở Day 23 |

Chi tiết 5 alternatives: [ADR-011](../decisions/011-mongo-for-analytics-and-flexible-attributes.md).

---

## 🎤 Trả lời phỏng vấn

**Q: Khi nào chọn Mongo thay Postgres?**
> "Tôi không nghĩ theo 'thay' — tôi nghĩ polyglot, mỗi kho một việc. Mongo khi
> data **schema đa hình + append-heavy + không invariant cross-row + cần TTL/scale
> ngang**: event store, activity log, flexible attributes. Postgres khi cần
> **ACID multi-row + invariant chặt + join**: order, inventory, payment. Ở
> project tôi, analytics event store dùng Mongo (event 3 type khác shape, TTL
> 90 ngày, aggregation funnel), nhưng product với `price`/`sku` invariant thì
> Postgres giữ source of truth — Mongo chỉ là read-model derived. Cargo-cult là
> nhét data có invariant vào Mongo vì 'nghe nói nhanh' rồi mất ACID."

**Follow-up trap: "Mongo có nhanh hơn Postgres không?"**
> "Sai câu hỏi. Không có 'nhanh hơn' chung chung. Mongo nhanh hơn cho
> *single-document read/write theo khoá* + *write append scale ngang*. Postgres
> nhanh hơn cho *join* + *aggregate có index B-tree* + *transaction*. Tốc độ phụ
> thuộc access pattern, không phải nhãn DB."

---

## 🔗 Related

- [Lesson 23b — document vs relational modeling](23b-document-vs-relational-modeling.md)
- [Issue 23 — no-transaction trap](../issues/23-mongodb-no-transaction-trap.md)
- [ADR-011 — Mongo cho analytics + attributes](../decisions/011-mongo-for-analytics-and-flexible-attributes.md)
- [Interview day-23](../interview/day-23-mongodb.md)
- Code: [`AnalyticsEvent`](../../services/analytics-service/src/main/java/com/ecom/analytics/domain/AnalyticsEvent.java), [`MongoIndexConfig`](../../services/analytics-service/src/main/java/com/ecom/analytics/config/MongoIndexConfig.java)
