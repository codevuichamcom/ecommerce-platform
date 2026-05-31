-- Day 16 — Slow query tuning cho product search.
--
-- Vấn đề: ProductRepository.search() dùng `LIKE LOWER(CONCAT('%', :keyword, '%'))`.
-- Substring match (có wildcard ở đầu) KHÔNG sargable với B-tree thường:
-- planner buộc phải Seq Scan toàn bảng. Ở 1.2M rows, p95 leo lên ~2.5s.
--
-- Giải pháp: GIN trigram (extension pg_trgm). Token hoá string thành 3-gram
-- và index ngược. `LIKE '%abc%'` thành Bitmap Index Scan qua GIN.
--
-- Tham khảo: docs/performance/16-sql-explain-analyze.md (EXPLAIN trước/sau),
-- docs/issues/16-slow-like-search-seq-scan.md (4 approaches compared).
--
-- ⚠️ Production note: ở 1.2M+ rows, index DDL dưới đây nên dùng
-- `CREATE INDEX CONCURRENTLY` để không AccessExclusiveLock bảng `products`
-- trong vài chục giây build GIN. Flyway mặc định wrap migration trong 1
-- transaction → KHÔNG dùng được `CONCURRENTLY` (yêu cầu tx riêng).
-- Cách deploy không downtime:
--   1. Tạm thời chạy lệnh CONCURRENTLY ngoài Flyway (psql) trên prod.
--   2. Sau đó dùng `flyway baseline` hoặc placeholder migration.
-- Ở env dev/test, để Flyway chạy bình thường — bảng nhỏ, lock 100ms không sao.
-- Xem `lessons/16-postgres-indexing.md` §CONCURRENTLY cho chi tiết.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GIN trigram trên LOWER(name) cho substring search case-insensitive.
-- gin_trgm_ops là operator class của pg_trgm, support `LIKE` / `ILIKE` /
-- `%` similarity / regex.
CREATE INDEX IF NOT EXISTS idx_products_name_trgm
    ON products USING GIN (LOWER(name) gin_trgm_ops);

-- Covering index cho list-by-category endpoint phổ biến nhất:
--   SELECT id, name, price, status FROM products
--   WHERE category_id = ? AND status = 'ACTIVE'
--   ORDER BY created_at DESC LIMIT 20;
--
-- INCLUDE columns cho phép Index-Only Scan: planner đọc thẳng từ index
-- mà KHÔNG cần fetch heap (nếu visibility map cho biết tuple visible).
-- Trade-off: index lớn hơn ~30%, write tốn hơn vài %.
--
-- Partial filter `WHERE status = 'ACTIVE'`: 95% query chỉ cần ACTIVE,
-- giảm index size + chỉ cập nhật khi status='ACTIVE'.
CREATE INDEX IF NOT EXISTS idx_products_category_active_covering
    ON products (category_id, created_at DESC)
    INCLUDE (id, name, price, status)
    WHERE status = 'ACTIVE';

-- Day 3 đã có idx_products_name_lower (B-tree text_pattern_ops).
-- Index đó CHỈ hỗ trợ prefix match `LIKE 'abc%'` — KHÔNG hỗ trợ
-- `LIKE '%abc%'`. Giữ lại để cover use case autocomplete prefix.
-- KHÔNG drop để tránh regression cho query prefix có sẵn.

-- Force ANALYZE để planner cập nhật statistics ngay sau khi index xong.
-- Không có ANALYZE, planner có thể chưa dùng index mới cho query đầu tiên.
ANALYZE products;
