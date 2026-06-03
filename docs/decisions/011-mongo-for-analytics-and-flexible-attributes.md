# ADR-011 — MongoDB cho analytics event store + flexible product attributes

- **Status**: ✅ Accepted
- **Date**: 2026-06-03
- **Deciders**: Tonny (Tech Lead)
- **Supersedes**: none. Bổ sung cho [ADR-001](001-why-hybrid-architecture.md) (polyglot) + [ADR-010](010-postgres-vs-elasticsearch-search.md) (ES derived index).

---

## 🏗️ Decision

Đưa **MongoDB 7** vào stack cho **2 use case có chủ ý**:
1. **`analytics-service` event store** — event hành vi đa hình (`product_viewed`, `cart_updated`, `order_placed`) + aggregation report + TTL auto-expire 90 ngày.
2. **`product-service` catalog read-model** — flexible attributes theo category (TV: `screen_size/resolution`; áo: `size/color/material`), sync 1 chiều từ Postgres qua event `product.upserted` (cùng event nuôi ES).

**Postgres GIỮ source of truth** cho product (invariant `price ≥ 0`, `sku` unique, status transition). Mongo + ES đều là **derived read-model**. KHÔNG có dual-source-of-truth.

---

## 📋 Context

Sau Day 22, hệ thống có 3 kho: Postgres (sổ gốc), Redis (cache), Elasticsearch (search index). 2 nhu cầu mới không kho nào hiện có phục vụ tốt:

1. **Team Growth cần report**: top sản phẩm + conversion funnel. Event hành vi đang trôi trong Kafka, mất sau retention. Cần nơi lưu lâu dài + query linh hoạt + KHÔNG đụng OLTP DB của order/product.
2. **Flexible attributes phình**: mỗi category một bộ thuộc tính khác nhau. Postgres JSONB (`Product.attributes`) đang gánh, nhưng khi attribute trở thành **query pattern chính** (filter "TV 4K", "áo cotton size L") thì cần index + scale đọc tốt hơn.

Cả 2 đều là dữ liệu **schema đa hình + đọc-phân-tích nhiều + ghi append-heavy** — đặc tính document store hợp hơn relational.

---

## 🤔 Alternatives considered

### Cho event store (analytics):

| # | Approach | Pros | Cons |
|---|----------|------|------|
| 1 | **Postgres JSONB event table** | Không thêm storage; ACID; team đã biết | Schema đa hình → JSONB rải rác; TTL phải cron `DELETE`+VACUUM thủ công; aggregation `GROUP BY` trên JSONB chậm + scale ngang khó; trộn OLTP/OLAP cùng cluster |
| 2 | **Postgres partitioned table (theo tháng)** | Vẫn 1 DB; partition pruning nhanh; drop partition = expire rẻ | Schema vẫn phải định nghĩa cứng; vẫn trộn OLTP/OLAP; partition management ops thủ công |
| 3 | ✅ **MongoDB event store** | Document đa hình tự nhiên; TTL index native; aggregation pipeline biểu đạt mạnh; sharding theo `occurredAt` scale ngang; tách hẳn OLTP | Thêm 1 storage (ops/backup/skill); không multi-doc ACID (chấp nhận — event append-only) |
| 4 | **Elasticsearch** | Đã có sẵn (Day 22); aggregation mạnh | ES là search engine không phải event store; primary store trên ES = anti-pattern (refresh lag, không durable như DB); tốn RAM giữ event ít query |
| 5 | **ClickHouse / time-series DB** | OLAP analytics đỉnh cao; nén tốt | Over-engineer ở volume hiện tại; thêm tech lạ team chưa vận hành; Day 23 mục tiêu là học document model, không phải columnar |

### Cho flexible attributes (product catalog):

| # | Approach | Pros | Cons |
|---|----------|------|------|
| A | **Cột quan hệ cứng mỗi attribute** | Type-safe; index dễ | Mỗi category vài chục cột, đa số NULL; thêm category = ALTER TABLE; bất khả thi khi attribute mở |
| B | **EAV table `(product_id, key, value)`** | Schema mở | Query "TV 4K" = self-join nhiều lần; mất type; index kém; kinh điển anti-pattern |
| C | **Postgres JSONB (giữ nguyên Day 3)** | Đã có; ACID; `attributes->>'k'` query được; GIN index | Khi attribute là query pattern CHÍNH + scale đọc lớn, JSONB trên OLTP DB gánh nặng; trộn read-model vào source-of-truth |
| D | ✅ **Mongo catalog read-model (derived)** | Document model first-class cho shape đa dạng; dot-notation `attributes.resolution` index được; tách read khỏi OLTP; cùng event sync như ES | Thêm sync path (dual-write drift như ES); thêm storage |

---

## ✅ Chosen — Rationale

**Event store → MongoDB (option 3)**. Event hành vi là *append-only, schema đa hình, đọc-aggregate, cần TTL* — bốn đặc tính trùng khít sweet-spot của document store. TTL index + aggregation pipeline là native, không phải bolt-on. Tách `analytics-service` riêng (DB-per-service) để workload OLAP không khoá OLTP.

**Flexible attributes → Mongo derived read-model (option D)**, NHƯNG **giữ Postgres JSONB làm source of truth**. Đây là điểm tinh tế:
- KHÔNG bỏ Postgres → product vẫn có ACID cho invariant (price, sku unique, status).
- Mongo là read-model thứ 2 (sau ES), sync qua **cùng event `product.upserted`** — fan-out 1 event tới 2 derived store (ES cho search, Mongo cho catalog detail + attribute filter).
- Vì sao không để Mongo làm source of truth attributes? Vì product có invariant cần ACID — thứ Mongo single-node (dev) không cho multi-doc. Để Mongo own = mất ACID hoặc gánh complexity replica-set txn cho data vốn có chỗ tốt hơn (Postgres).

> 🧠 **Đây là quyết định "anti-cargo-cult" cốt lõi**: Mongo KHÔNG thay Postgres. Mỗi kho một việc. "Có Mongo cho hợp xu hướng" rồi nhét data invariant vào = dấu hiệu junior. Senior = Mongo cho data append-only/schemaless/analytical.

---

## ⚖️ Trade-offs

**Accepted:**
- **+1 storage** → ops burden (backup, monitor, version, skill). Biện minh: 2 use case thật, không phải "cho có".
- **Dual-write drift** (Postgres→Mongo qua Kafka, như ES Day 22): publish fail sau commit → Mongo stale tới lần reconcile. Chấp nhận vì derived + non-critical, reconcile sửa được.
- **Không multi-doc ACID** (dev single-node không replica set): cả 2 use case đều single-document-write nên không cần. Trap được ghi rõ ở [issue 23](../issues/23-mongodb-no-transaction-trap.md).
- **Đếm analytics xấp xỉ**: không dedup → Kafka at-least-once làm lệch ~0.x%. Chấp nhận cho report; không chấp nhận cho billing.

**Rejected trade-off:** KHÔNG chọn Postgres-only (option 1/2/C) dù đỡ 1 storage — vì aggregation OLAP + TTL + shape đa hình ở volume tăng sẽ ép OLTP DB gánh sai việc, và Day 24 cần document store thật để so sánh decision matrix.

---

## 📌 Consequences

- `analytics-service` (service thứ 8) lên đời: Mongo event store + 2 report endpoint + TTL/compound index.
- `product-service` giờ có **3 store**: Postgres (truth) + ES (search) + Mongo (catalog) — cùng 1 event `product.upserted` fan-out 2 consumer-group.
- Ngưỡng nâng cấp: nếu drift đo được vượt ngưỡng → outbox cho product (như order Day 13) / Debezium CDC. Nếu event volume tăng → Mongo sharding theo `occurredAt` / chuyển ClickHouse. Đánh giá ở **Day 25 polyglot review**.
- common-lib refactor: tách `SecurityExceptionHandler` (`@ConditionalOnClass`) khỏi `GlobalExceptionHandler` để service web không-security (analytics) dùng được — xem [trap 08](../review/ai-junior-traps.md).

---

## 🔗 Related

- Code: [`AnalyticsEvent.java`](../../services/analytics-service/src/main/java/com/ecom/analytics/domain/AnalyticsEvent.java), [`ReportService.java`](../../services/analytics-service/src/main/java/com/ecom/analytics/report/ReportService.java), [`MongoIndexConfig.java`](../../services/analytics-service/src/main/java/com/ecom/analytics/config/MongoIndexConfig.java), [`ProductCatalogDocument.java`](../../services/product-service/src/main/java/com/ecom/product/catalog/ProductCatalogDocument.java)
- Docs: [lesson 23](../lessons/23-mongodb-when-to-use.md), [lesson 23b](../lessons/23b-document-vs-relational-modeling.md), [issue 23](../issues/23-mongodb-no-transaction-trap.md), [interview day-23](../interview/day-23-mongodb.md)
- Tiền đề: [ADR-001 polyglot](001-why-hybrid-architecture.md), [ADR-010 ES derived index](010-postgres-vs-elasticsearch-search.md), [lesson 22b CDC-vs-app-sync](../lessons/22b-cdc-vs-app-sync-vs-debezium.md)
