# Interview · Day 16 — Slow query tuning (EXPLAIN ANALYZE + indexes)

> **Status**: ✅ Done · 2026-05-31
> Bối cảnh giả lập + 5 Q&A senior level + AI Playbook.

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — series A ecommerce VN, vừa merge thêm seller marketplace, catalog từ 50K → 1.2M SKU sau 2 tuần.
- **Role giao việc**: Anh Hùng — Engineering Manager. "Search box p99 lên 2.5s từ hôm import seller data, KH bỏ giỏ. Tìm root cause, đừng vội đẩy sang Elasticsearch — đó là Q3, giờ Postgres phải gánh được."
- **Bạn**: Backend owner `product-service` — own query + index strategy.
- **Reviewer**: 1 senior DBA part-time, soi kỹ "index có bị bloat / write-amplification không, GIN có maintenance cost gì."
- **Deadline**: 1 sprint, demo EXPLAIN before/after + p95 number tại retro Friday.
- **Constraint thực tế**:
  - Không được thêm infra mới (Elasticsearch để Q3).
  - Index migration phải safe production: 1.2M rows, không downtime.
  - Auto-vacuum đang lệch — không được làm tệ hơn.
- **Definition of Done**:
  - p95 `GET /products?q=` < 100ms (hiện 2.5s).
  - EXPLAIN trên hot path không còn Seq Scan.
  - Migration chạy được trên prod không lock write.
  - Doc EXPLAIN before/after cho DBA review.

---

## Q1 — "`LIKE '%abc%'` vs `LIKE 'abc%'`: cái nào dùng được index? Tại sao?"

**Strong answer**:

> "`LIKE 'abc%'` (prefix anchored) dùng được B-tree với operator class
> `text_pattern_ops` hoặc `varchar_pattern_ops`. Lý do: B-tree lưu key sorted,
> prefix match là một range scan `WHERE x >= 'abc' AND x < 'abd'`.
>
> `LIKE '%abc%'` (substring) thì không — wildcard ở đầu nghĩa là "string nào
> có chứa abc bất cứ vị trí nào", B-tree sorted theo prefix không lookup được.
> Đây gọi là **non-sargable** predicate.
>
> Để index substring, dùng GIN trigram (`pg_trgm` extension): tokenize string
> thành 3-gram, index ngược các trigram. Query `%abc%` cũng tokenize và tra
> index ngược → ra candidate rows, rồi recheck trên heap.
>
> Ở project mình Day 16, product search dùng `LIKE '%kw%'` ở 1M rows — Seq Scan
> p95 2.5s. Bật `CREATE EXTENSION pg_trgm` + `CREATE INDEX USING GIN (lower(name) gin_trgm_ops)`
> → planner chuyển sang Bitmap Index Scan, p95 45ms."

**Follow-up trap**: "ILIKE thì sao?"
> "ILIKE không tự rewrite thành `LOWER(x) LIKE LOWER(?)` để match expression
> index `LOWER(name)`. Phải align: hoặc dùng `LIKE LOWER(...)` explicit, hoặc
> tạo index trên `name` raw với `gin_trgm_ops` (operator class hỗ trợ case-insensitive
> ở pg_trgm 1.5+)."

---

## Q2 — "EXPLAIN ANALYZE cho thấy Seq Scan dù đã có index trên column đó. Vì sao?"

**Strong answer** — checklist senior reach for:

> "5 nguyên nhân thường gặp, theo thứ tự khả năng:
>
> 1. **Stats cũ**: planner đoán selectivity sai (vd nghĩ 50% rows match dù
>    thực ra 0.1%). Fix: `ANALYZE table`. Auto-vacuum sẽ chạy nhưng có trễ.
>
> 2. **Predicate không match index expression**: index trên `LOWER(name)` mà
>    query viết `name ILIKE ...` → expression khác → không hit. Phải align.
>
> 3. **Implicit cast**: cột `VARCHAR`, query `WHERE col = 123` → Postgres
>    cast cả cột sang int → biểu thức không match index.
>
> 4. **Selectivity thấp + bảng nhỏ**: nếu match > 20% rows hoặc bảng < 10K,
>    Seq Scan nhanh hơn vì avoid index lookup overhead.
>
> 5. **Index bloat hoặc INVALID**: `CREATE INDEX CONCURRENTLY` fail giữa
>    chừng → index INVALID. Check `SELECT * FROM pg_index WHERE indisvalid = false`.
>
> Workflow: chạy `EXPLAIN (ANALYZE, BUFFERS)` so estimated vs actual rows.
> Chênh > 10× → stats issue. Equal nhưng vẫn Seq Scan → selectivity issue."

---

## Q3 — "Add index 1.2M rows production không downtime — làm sao?"

**Strong answer**:

> "Default `CREATE INDEX` lấy `ShareLock` — block UPDATE/INSERT trong vài chục
> giây ở 1M+ rows. User gặp 503 hoặc connection timeout.
>
> Dùng `CREATE INDEX CONCURRENTLY`:
> - Postgres quét bảng 2 lần (1 build, 1 catch-up writes phát sinh during build).
> - Dùng SnapshotAny — không block write.
> - Đánh đổi: ~2x chậm hơn, dùng nhiều CPU hơn.
> - Constraint: KHÔNG chạy được trong transaction. Flyway default wrap migration
>   trong tx → conflict. Cách xử lý: Flyway 9+ có `-- flyway:transactional=false`
>   ở header migration; hoặc chạy lệnh `CONCURRENTLY` ngoài Flyway (psql trên prod)
>   rồi `flyway baseline`.
>
> Edge case: nếu build fail giữa chừng, index vào trạng thái `INVALID` —
> phải `DROP INDEX CONCURRENTLY` rồi tạo lại. Monitor `pg_index.indisvalid`.
>
> Ở project mình Day 16, V5 migration ghi rõ comment warning về CONCURRENTLY,
> nhưng để Flyway run trên dev/test (bảng nhỏ, lock 100ms ok). Khi deploy prod
> thật, sẽ tách script ra chạy psql trước rồi baseline."

---

## Q4 — "Covering index lợi gì? Khi nào dùng `INCLUDE`?"

**Strong answer**:

> "`INCLUDE` columns gắn vào leaf node của B-tree mà không tham gia key. Lợi:
> Index-Only Scan — planner đọc thẳng từ index, skip heap fetch nếu visibility
> map cho biết tuple visible.
>
> Khi nào dùng:
> - Query luôn select cùng nhóm column nhỏ (3-5 column, không phải `SELECT *`).
> - Predicate đơn giản dùng key của index.
> - Read-heavy table, hot working set.
>
> Trade-off:
> - Index lớn hơn 30-50% (lưu thêm column ở leaf).
> - Insert/update touch nhiều page hơn → write tax.
> - INCLUDE column KHÔNG dùng được cho ORDER BY hoặc filter — chỉ là payload.
>
> Ở project mình Day 16 thêm
> `(category_id, created_at DESC) INCLUDE (id, name, price, status) WHERE status='ACTIVE'`
> cho endpoint list-by-category. Plan chuyển từ Index Scan + Heap fetch sang
> Index-Only Scan, `Heap Fetches: 0` — sub-millisecond."

**Follow-up**: "Sao không cứ index cả 4 column thành composite key?"
> "Composite key thì 4 column join vào tree structure → planner cần ORDER BY/filter
> theo đúng thứ tự left-to-right. INCLUDE thì để payload — không ràng buộc thứ tự,
> chỉ tăng size hợp lý."

---

## Q5 — "GIN trigram vs Full-text tsvector vs Elasticsearch — chọn thế nào?"

**Strong answer**:

> "Quyết định theo 3 trục: relevance scoring cần không, scale dataset, cost ops.
>
> | Tech | Khi nào |
> | --- | --- |
> | **GIN trigram** | Substring + fuzzy match, < 10M rows, không cần ranking. Cài 1 dòng SQL. |
> | **GIN tsvector** | Full-text với stemming + phrase + ranking, vẫn trong Postgres. Phức tạp query syntax (`@@`, `to_tsquery`). |
> | **Elasticsearch** | Relevance scoring nâng cao + faceting + multi-tenant search + scale > 50M docs. Trade-off: infra mới + sync pipeline + ops cost. |
>
> ShopVN Day 16: 1.2M rows, chưa cần ranking, đội ops bận → GIN trigram là
> sweet spot. Day 22 sẽ migrate ES khi requirement có ranking + faceting thật.
>
> **Anti-pattern cần tránh**: cargo-cult "search → ES" mà không đo gì. ES tốt,
> nhưng cost ops thật: cluster 3 node, sync lag, dual write outbox, schema
> migration ES + Postgres. Justify bằng số: rows count, query mix, SLA."

---

## 🧠 Senior mindset notes

- **Đo trước khi tune**: EXPLAIN ANALYZE + dataset thật. Đừng index theo cảm tính.
- **Index thừa = write tax âm thầm**: insert N rows × M index. Drop trước khi add.
- **Read EXPLAIN ngược từ leaf**: thời gian thật ở node nào lớn nhất → bottleneck thật. Đừng nhìn root cost.
- **Estimated vs Actual rows chênh 10× → stats**. Đây là dấu hiệu rõ nhất.
- **Cache (Day 15) và Index là 2 lớp khác nhau**: cache giải hot key; index giải query shape. Không thay nhau được.

---

## 🤖 AI Playbook — Day 16

- **AI làm tốt**:
  - Generate `generate_series` seed script + sample data realistic.
  - Draft EXPLAIN ANALYZE output mẫu (verbose, đúng format).
  - Liệt kê các loại index Postgres + operator class.
- **Prompt mẫu** (4 dòng):
  > "Viết Postgres SQL seed 1M products dùng generate_series. Có 20 prefix name
  > đa dạng (tiếng Việt + brand), category random từ existing categories, status
  > 70% ACTIVE / 20% DRAFT / 10% ARCHIVED. Bao gồm `ANALYZE products` cuối file."
- **Risk khi để AI làm**:
  - AI hay đề xuất **index thừa** — index mọi column "for safety". Mỗi index thừa = write tax.
  - AI quên `CREATE INDEX CONCURRENTLY` → migration lock prod.
  - AI bịa số trong EXPLAIN output nếu không có data thật → kiểm chứng phải chạy thật.
- **Cách validate**:
  - Chạy EXPLAIN thật trên dataset 1M; so estimated vs actual rows.
  - Đếm index size bằng `SELECT pg_size_pretty(pg_total_relation_size('idx_xxx'))`.
  - Kiểm tra `pg_stat_user_indexes.idx_scan` sau 1 tuần — index nào `idx_scan=0` thì DROP.
