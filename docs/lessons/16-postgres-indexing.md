# 📚 Lesson 16 — Postgres indexing cho ứng dụng OLTP

> **Status**: ✅ Done · 2026-05-31
> **Related day**: Day 16

---

## TL;DR

5 loại index Postgres + 1 quy tắc duy nhất: **index theo predicate
thực tế, không theo column ngẫu nhiên**. Mọi index thừa là write tax âm thầm.

| Loại            | Predicate phù hợp                          | Operator class chính           |
| --------------- | ------------------------------------------ | ------------------------------ |
| **B-tree**      | `=`, `<`, `>`, `BETWEEN`, `ORDER BY`        | default                        |
| **B-tree text_pattern_ops** | `LIKE 'abc%'` (prefix anchored) | `text_pattern_ops`             |
| **GIN pg_trgm** | `LIKE '%abc%'`, `ILIKE`, `~`, `%>` similarity | `gin_trgm_ops`               |
| **GIN tsvector**| Full-text với ranking (`@@`)                | `tsvector_ops`                 |
| **GIN jsonb**   | JSONB key/path lookup (`@>`, `?`)           | `jsonb_ops` / `jsonb_path_ops` |
| **GiST**        | Range overlap, geo, KNN                     | `gist_geometry_ops`, etc.      |
| **BRIN**        | Bảng huge, dữ liệu append-only sort-correlated (logs) | `brin_ops`         |
| **Hash**        | Chỉ `=`, không range. Hiếm dùng vì B-tree đã cover | `hash`                 |
| **Partial**     | Modifier — filter subset trước khi index    | `WHERE` clause                 |
| **Covering**    | Modifier — `INCLUDE` columns cho Index-Only Scan | `INCLUDE`                |

---

## Khi nào dùng

### B-tree (default)
- 95% use case OLTP: PK, FK, unique, equality filter, range filter, sort.
- Phù hợp: `WHERE id = ?`, `WHERE created_at BETWEEN ? AND ?`, `ORDER BY price`.

### B-tree với `text_pattern_ops`
- Chỉ khi cần `LIKE 'prefix%'` (left-anchored) trên text.
- Không hỗ trợ `LIKE '%suffix'` hay `'%substring%'`.
- Day 3 đã dùng cho autocomplete name prefix.

### GIN trigram (`pg_trgm`)
- Khi cần substring search `LIKE '%kw%'` hoặc fuzzy match (`name %> 'iphn'`).
- Hỗ trợ `ILIKE` case-insensitive nếu index trên `LOWER(name)`.
- Day 16 dùng cho product search.

### GIN tsvector
- Khi cần full-text search có ranking (`ts_rank`), stemming, stopword.
- Phức tạp hơn trigram, ROI cao khi user thực sự cần "phrase match" hoặc multi-word.
- ShopVN postpone đến khi có yêu cầu thật → Day 22 sẽ chuyển sang ES luôn.

### GIN JSONB
- `attributes @> '{"color":"red"}'` — `jsonb_path_ops` nhỏ hơn, query nhanh hơn `jsonb_ops` nếu chỉ dùng `@>`.

### GiST
- PostGIS, range type, KNN search. Ngoài scope project.

### BRIN
- Bảng cực lớn (>100M rows) với data append-only và sort-correlated với block layout
  (vd `created_at` log). Index size cực nhỏ (~1MB cho 1B rows) nhưng query
  chậm hơn B-tree ~10×.

### Partial
- Khi ≥80% query filter cùng 1 điều kiện cố định, vd `WHERE status='ACTIVE'`.
- Lợi: index nhỏ hơn 5-10×, write chỉ touch index khi row match filter.
- Day 3 dùng cho `status='ACTIVE'`. Day 16 thêm covering version cho list-by-category.

### Covering (`INCLUDE`)
- Khi query luôn select cùng nhóm column nhỏ + sort + filter.
- `INCLUDE` cho phép Index-Only Scan, skip heap fetch.
- Trade-off: index lớn hơn 30-50%, không tham gia ORDER BY của các INCLUDE column.

---

## Khi nào KHÔNG dùng

1. **Bảng < 10K rows** — Seq Scan vẫn nhanh hơn vì plan setup cost > scan cost.
2. **Cột selectivity thấp (cardinality < 100)** vd `status` có 3 giá trị → partial index hoặc skip.
3. **Write-heavy** với read hiếm — write amplification ăn hết lợi.
4. **Predicate biến đổi liên tục** — không thể index hết các shape.
5. **Khi cache (Day 15) đã cover** — đừng index nếu hot key đã ở cache 99% time.

---

## Cạm bẫy phổ biến

### 1. Function trên column phá index
```sql
-- ❌ Không dùng được idx_products_name_lower
WHERE LOWER(name) = LOWER(?)  -- nếu index trên name (raw)

-- ✅ Index phải match expression
CREATE INDEX ON products (LOWER(name));
```

### 2. Cast ngầm phá index
```sql
-- Cột `sku VARCHAR`, query `WHERE sku = 123` → Postgres cast cả cột → Seq Scan.
-- Luôn truyền đúng type từ app side.
```

### 3. NULL không nằm trong index (cũ)
Postgres ≥ 8.3 đã index NULL. Nhưng cẩn thận khi dùng `WHERE col IS NULL`
trên cột nullable — có thể không hit index nếu planner đoán selectivity sai.

### 4. Quá nhiều index
Rule of thumb: insert N rows × M index = N×M index update + heap insert.
Mỗi index thừa là write tax. Drop trước khi add.

### 5. Quên `ANALYZE` sau bulk operation
Statistics cũ → planner đoán sai → chọn nhầm plan. Auto-vacuum sẽ chạy
nhưng trễ. Sau bulk insert / migration phải manual `ANALYZE table`.

---

## `CREATE INDEX CONCURRENTLY` — chi tiết

Tại sao không cứ dùng default? Vì default lấy `ShareLock` — block write trong
thời gian build index. Ở 1M+ rows GIN build mất 30-60s → user thấy 503.

`CONCURRENTLY` đánh đổi:
- Quét bảng 2 lần (1 lần build, 1 lần catch-up writes during build).
- Dùng SnapshotAny — không block write.
- KHÔNG chạy được trong transaction → KHÔNG hợp với Flyway default (Flyway
  wrap mỗi migration trong tx).
- Workaround cho Flyway 9+: `-- flyway:transactional=false` header.
- Nếu build fail giữa chừng → index vào trạng thái `INVALID`, phải DROP rồi
  tạo lại. Detect: `SELECT * FROM pg_index WHERE indisvalid = false`.

---

## Approaches compared — substring search ở 1M rows

| Approach                          | Setup                            | p95 query | Index size | Write cost     | Khi nào dùng                  |
| --------------------------------- | -------------------------------- | --------- | ---------- | -------------- | ------------------------------ |
| Seq Scan (no index)               | —                                | 2.5s      | 0          | 0              | Bảng nhỏ (<10K rows)           |
| B-tree `text_pattern_ops`         | `CREATE INDEX ON (name varchar_pattern_ops)` | 2.5s (substring vẫn Seq) / 5ms (prefix) | 80 MB | +5% insert | CHỈ prefix `LIKE 'abc%'` |
| **GIN pg_trgm**                   | `CREATE EXTENSION pg_trgm; CREATE INDEX USING GIN (lower(name) gin_trgm_ops)` | 45ms | 187 MB | +50% insert | Substring + fuzzy (Day 16) |
| GIN tsvector full-text            | `CREATE INDEX USING GIN (to_tsvector('simple', name))` + query rewrite | 30ms | 150 MB | +60% insert | Phrase + ranking (chưa cần) |
| Elasticsearch                     | New infra + sync pipeline        | 8ms       | ~250 MB (ES storage) | sync lag + ops cost | Relevance + faceting (Day 22) |

ShopVN chọn **GIN pg_trgm** — đơn giản, không infra mới, đủ tốt cho phase này.

---

## Trả lời phỏng vấn

**Q: "Khi nào dùng GIN, khi nào B-tree?"**

> "B-tree cho equality/range — primary key, foreign key, sort, `BETWEEN`.
> GIN khi giá trị column là composite mà tôi muốn lookup từng phần: substring
> (trigram), full-text token (tsvector), JSONB key. Trade-off chính: GIN build
> chậm hơn ~5x, write amplification ~50%, size lớn hơn ~2x. Ở project mình,
> Day 16 dùng GIN trigram cho `LIKE '%kw%'` product search ở 1M rows —
> p95 từ 2.5s xuống 45ms, EXPLAIN chuyển từ Seq Scan sang Bitmap Index Scan.
> Write penalty acceptable vì read:write ~100:1."

**Follow-up trap**: "Sao không full-text tsvector?" → vì chưa cần ranking/stemming,
trigram đủ cho substring + fuzzy. tsvector phức tạp hơn ở app side (phải dùng
`@@` operator + `to_tsquery`), và khi cần ranking thật sự thì Day 22 sẽ ES.

**Q: "Covering index lợi gì so với index thường?"**

> "`INCLUDE` columns vào leaf node của B-tree → Index-Only Scan, skip heap
> fetch. Nhanh hơn 2-5× khi visibility map cho biết tuple visible. Trade-off:
> index lớn hơn 30-50%, update touch nhiều page hơn. Dùng khi query luôn
> select cùng nhóm column nhỏ và predicate đơn giản."

---

## 🔗 Related

- Performance: [`16-sql-explain-analyze.md`](../performance/16-sql-explain-analyze.md)
- Issue: [`16-slow-like-search-seq-scan.md`](../issues/16-slow-like-search-seq-scan.md)
- Previous: [`performance/03-product-search-indexing.md`](../performance/03-product-search-indexing.md) — Day 3 đặt nền B-tree
- Migration: [`V5__product_search_indexes.sql`](../../services/product-service/src/main/resources/db/migration/V5__product_search_indexes.sql)
