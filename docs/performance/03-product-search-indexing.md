# ⚡ Performance 03 — Product search indexing strategy

> **Day 3 deliverable.** Search hiện tại dùng `LIKE '%term%'` — KHÔNG
> scale. Lesson này note baseline + plan tune Day 16 (GIN trigram /
> full-text) + migrate Day 22 (Elasticsearch).

## 🎯 Goal

- Hiểu vì sao `LIKE '%term%'` luôn seq scan trên Postgres.
- Plan index strategy phù hợp từng tier dataset (10k / 1M / 10M+).
- Định lượng trade-off giữa B-tree, GIN trigram, full-text, ES.

## 📊 Baseline Day 3 — chấp nhận chậm

### Schema

```sql
CREATE INDEX idx_products_category    ON products (category_id);
CREATE INDEX idx_products_active      ON products (status) WHERE status = 'ACTIVE';
CREATE INDEX idx_products_name_lower  ON products (LOWER(name) text_pattern_ops);
CREATE INDEX idx_products_created     ON products (created_at DESC, id);
```

Xem [`V2__create_products.sql`](../../services/product-service/src/main/resources/db/migration/V2__create_products.sql).

### Query hiện tại

```sql
SELECT ... FROM products
WHERE LOWER(name) LIKE LOWER('%iphone%')
  AND category_id = ?
  AND status = ?
ORDER BY created_at DESC
LIMIT 20 OFFSET 0;
```

### Vì sao slow ở scale

| Pattern               | Index có dùng?                  | Plan                      |
| --------------------- | ------------------------------- | ------------------------- |
| `LIKE 'iphone%'`      | ✅ B-tree `text_pattern_ops`    | Index Range Scan          |
| `LIKE '%iphone%'`     | ❌ B-tree không match suffix    | Seq Scan toàn bảng        |
| `LIKE '%iphone'`      | ❌ same                         | Seq Scan                  |

→ Day 3 cho phép `%term%` (substring) → khi 1M rows sẽ seq scan = vài giây.

## 🚧 Day 16 — tune Postgres-native

### Approach A — GIN trigram (`pg_trgm`)

```sql
CREATE EXTENSION pg_trgm;
CREATE INDEX idx_products_name_trgm ON products
USING GIN (name gin_trgm_ops);

-- Query unchanged, planner sẽ tự dùng GIN khi pattern có ≥3 char liền.
```

- ✅ Fix `%term%` substring search.
- ✅ Tolerance fuzzy matching (sim score).
- ❌ Index size to (~3-5x B-tree). Write amplification cao.
- ❌ Vẫn không có ranking, không stem, không synonym.

### Approach B — Postgres Full-text Search (`tsvector`)

```sql
ALTER TABLE products ADD COLUMN name_tsv tsvector
GENERATED ALWAYS AS (to_tsvector('simple', name)) STORED;
CREATE INDEX idx_products_name_tsv ON products USING GIN (name_tsv);
```

- ✅ Có ranking (`ts_rank`), tokenize chuẩn.
- ❌ Không tốt cho tiếng Việt (cần config `vietnamese` dictionary custom).
- ❌ Không có typo tolerance như ES fuzzy.

## 🎯 Day 22 — migrate Elasticsearch

Khi dataset > 5M product hoặc cần:
- Faceted search (aggregation by category + price range + brand).
- Highlight matched terms.
- Synonym, typo tolerance, multilingual analyzer.
- Ranking phức tạp (boost by recency, popularity).

→ Postgres dù tune cỡ nào cũng kém. ES là right tool. Sync qua Kafka
event `product.upserted` (CDC-lite) — xem `lessons/22b-cdc-vs-app-sync-vs-debezium.md` ⏳.

## 📈 Benchmark plan (Day 16 + 22)

| Approach               | 1M rows P50 | 1M rows P95 | Index size |
| ---------------------- | ----------- | ----------- | ---------- |
| LIKE `%term%` no index | TBD (slow)  | TBD         | 0          |
| GIN trigram            | TBD         | TBD         | TBD        |
| Postgres FTS           | TBD         | TBD         | TBD        |
| Elasticsearch          | TBD         | TBD         | TBD        |

→ Fill số thật ở Day 16 (`performance/16-sql-explain-analyze.md`)
và Day 22 (`performance/22-search-postgres-vs-es.md`).

## 🔗 Related

- Code: [`ProductRepository.java`](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java)
- Migration: [`V2__create_products.sql`](../../services/product-service/src/main/resources/db/migration/V2__create_products.sql)
- Day 16 SQL tuning deep-dive ⏳
- Day 22 Elasticsearch migration ⏳
- Lesson: [`lessons/03-pagination-offset-vs-cursor.md`](../lessons/03-pagination-offset-vs-cursor.md)
