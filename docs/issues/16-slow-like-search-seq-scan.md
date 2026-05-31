# Issue 16 — 🔥 Slow `LIKE '%kw%'` search — Seq Scan ở 1M rows

> **Status**: ✅ Done · 2026-05-31
> **Related day**: Day 16

---

## 1. Problem

Endpoint `GET /products?q=iphone` p95 từ 90ms vọt lên **2.5s** sau khi
merge seller marketplace đẩy catalog từ 50K → 1.2M SKU. KH bỏ giỏ vì
search "đứng máy".

## 2. Symptoms

- Grafana p95 `GET /products`: 90ms → 2.5s, p99: 4.1s.
- Postgres `pg_stat_statements`: query `SELECT * FROM products WHERE LOWER(name) LIKE ...` có `mean_exec_time = 2300ms`, `calls = 18K/h`.
- DB CPU spike từ 25% → 85% khi traffic search peak.
- `pg_stat_activity`: nhiều query cùng SQL pattern ở state `active`, đang `Seq Scan`.
- App log `JdbcSQLTimeoutException` không có — query không timeout, chỉ chậm.
- `EXPLAIN ANALYZE` cho thấy `Seq Scan on products` + `Rows Removed by Filter: 921786`.
- Cache hit ratio (Day 15) vẫn 95% cho `/products/{id}` — nhưng search KHÔNG cache (key entropy cao).
- Connection pool Hikari max=20 → bắt đầu queue khi p95 chạm 2s × 20 pod.

## 3. Root cause

- **Predicate non-sargable**: `LIKE LOWER('%' || ? || '%')` có wildcard ở đầu chuỗi. B-tree thường lưu key sorted — không lookup được "string nào chứa ký tự X bất kỳ vị trí".
- **Index Day 3 vô dụng cho substring**: `idx_products_name_lower (LOWER(name) text_pattern_ops)` chỉ support prefix anchored. `LIKE 'iphone%'` dùng được; `LIKE '%iphone%'` thì không.
- **Selectivity thấp**: keyword phổ biến match 5-10% rows. Ngay cả với index, fetch ~70K rows từ heap vẫn đắt → planner đành Seq Scan.
- **Stats không sai** — đây không phải vấn đề stats cũ. Vấn đề là không có index nào match predicate shape này.
- **Cache không cứu được**: search query string entropy cao (mọi user gõ khác nhau), Day 15 quyết định KHÔNG cache search → đúng.

## 4. Approaches compared

| Approach                                 | Pros                                                    | Cons                                                          |
| ---------------------------------------- | ------------------------------------------------------- | ------------------------------------------------------------- |
| **B-tree prefix only** (đổi UX thành `q*`) | Index Day 3 đã có; ~5ms p95.                          | Phá UX hiện tại — user phải biết gõ prefix; mất search "iphone" khi name là "Apple iPhone 15". Không khả thi business. |
| **GIN trigram (`pg_trgm`)** ✅           | 45ms p95 ở 1M rows; substring + fuzzy `%>`; không infra mới (Postgres extension built-in); query code KHÔNG đổi. | Index 187 MB (~190 bytes/row); write amplification +50%; build chậm (~60s cho 1M rows). |
| **GIN full-text tsvector**               | 30ms p95; ranking + stemming; standard for search.      | Phải rewrite query (`@@` + `to_tsquery`); app code phải xử lý query syntax; chưa cần ranking ở phase này. |
| **Elasticsearch** (Day 22)               | Best for relevance + faceting + scale 100M+; 8ms p95.   | Infra mới: ES cluster + sync pipeline + ops cost; over-engineer cho 1.2M rows; tracking 2 source of truth. |

## 5. Chosen — GIN trigram (`pg_trgm`)

**Lý do gắn project**:
1. **Đơn giản nhất giải quyết vấn đề**: extension Postgres built-in, không thêm service. Đội infra Sotatek hiện đang quá tải → không muốn thêm ES cluster phải maintain.
2. **Query code không đổi**: JPQL `LIKE LOWER(CONCAT('%', :keyword, '%'))` tự dùng được GIN trigram (operator class `gin_trgm_ops` declare support cho `LIKE` / `ILIKE`). Không phải rewrite repository.
3. **ROI vừa đủ**: 2.5s → 45ms là cải thiện 57×, dư cho SLA (target < 100ms p95). Investment thêm để xuống 8ms (ES) chưa justify.
4. **Fuzzy bonus**: `gin_trgm_ops` cho phép similarity search `name %> 'iphn'` (typo tolerance) — option mở sau, không cần code thêm bây giờ.
5. **Day 22 ES roadmap không bị hủy**: khi catalog vọt 10M+ và cần faceting/relevance, GIN trigram sẽ đuối → migrate ES có lý do thật, không cargo-cult.

**Anti-pattern tránh được**: cargo-cult "search → Elasticsearch" mà không đo problem.

## 6. Fix

Migration [V5__product_search_indexes.sql](../../services/product-service/src/main/resources/db/migration/V5__product_search_indexes.sql):

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_products_name_trgm
    ON products USING GIN (LOWER(name) gin_trgm_ops);

ANALYZE products;
```

EXPLAIN xác minh: planner chuyển từ `Seq Scan` (cost 58234) → `Bitmap Index Scan on idx_products_name_trgm` (cost 347). Chi tiết: [`performance/16-sql-explain-analyze.md`](../performance/16-sql-explain-analyze.md) §5.

Repository javadoc cập nhật reference Day 16 ở [`ProductRepository.java:15-22`](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java#L15-L22).

## 7. Prevention

1. **Performance test trong CI**: thêm bench script chạy 1K-10K row dataset + assert p95 < threshold. Phát hiện regression khi ai đó remove index hoặc đổi query shape.
2. **Slow query alert**: Postgres `log_min_duration_statement = 500` + Grafana alert nếu `pg_stat_statements.mean_exec_time > 200ms` cho top 10 query.
3. **PR checklist**: thêm bullet "Đã EXPLAIN ANALYZE query mới chưa? Đính kèm output trong PR description nếu touch hot path."
4. **Auto-vacuum monitoring**: monitor `pg_stat_user_tables.n_dead_tup / n_live_tup` — nếu > 20% thì auto-vacuum không kịp → index bloat, planner đoán sai stats.
5. **Capacity planning doc**: ghi lại 1.2M rows = 187 MB GIN index. Khi catalog đạt 10M, recompute: 1.5GB index — pin vào shared_buffers hay swap → ra Day 22 ES decision.

## 8. Trade-off accepted

Chấp nhận:
- **Insert latency +50%** (1.2ms → 1.8ms). Catalog write rate ~50 INSERT/min ở peak (admin thao tác) → không vấn đề. Sẽ tệ hơn nếu auto-import seller catalog batch → cần `CREATE INDEX CONCURRENTLY` + monitor bloat.
- **Index size 187 MB cho 1M rows** (~190 bytes/row). Disk rẻ; shared_buffers cần đủ chứa hot working set — hiện 4GB OK.
- **Recheck cost**: GIN trả superset, Postgres recheck trên heap. Cost nhỏ với selectivity thấp (~7%); sẽ tệ nếu keyword 1-2 ký tự match toàn DB → consider min keyword length 3 ở app side.
- **VACUUM workload tăng**: GIN có `gin_pending_list` cần flush — autovacuum sẽ bận hơn. Monitor `pg_stat_progress_vacuum`.

KHÔNG chấp nhận:
- ES infra cho 1.2M rows — bị reject vì over-engineer cho phase hiện tại.
- Đổi UX sang prefix-only — phá search behavior user đang dùng.

## 9. Related

**Code**:
- [`V5__product_search_indexes.sql`](../../services/product-service/src/main/resources/db/migration/V5__product_search_indexes.sql) — migration
- [`ProductRepository.java`](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java) — JPQL search (unchanged, comment updated)
- [`DebugExplainController.java`](../../services/product-service/src/main/java/com/ecom/product/web/DebugExplainController.java) — `/debug/explain/search` để demo before/after
- [`generate_products_1m.sql`](../../services/product-service/src/main/resources/db/seed/generate_products_1m.sql) — seed reproducible

**Docs**:
- [`performance/16-sql-explain-analyze.md`](../performance/16-sql-explain-analyze.md) — full EXPLAIN breakdown
- [`lessons/16-postgres-indexing.md`](../lessons/16-postgres-indexing.md) — 5 loại index decision matrix
- [`interview/day-16-sql-tuning.md`](../interview/day-16-sql-tuning.md) — Q&A
- [`performance/03-product-search-indexing.md`](../performance/03-product-search-indexing.md) — Day 3 baseline
- Day 22 (forward ref) — ES migration khi cần relevance + faceting
