-- Day 16 — Seed 1M products để benchmark slow query thật.
--
-- KHÔNG đặt trong db/migration/ vì:
--   1. Không muốn Flyway chạy seed này trên staging/prod (1M rows = 30-60s).
--   2. Dataset reproducible, run thủ công qua psql khi cần benchmark.
--
-- Cách chạy (local):
--   docker compose exec postgres psql -U ecom -d productdb \
--     -f /seed/generate_products_1m.sql
-- Hoặc mount file rồi `\i` trong psql.
--
-- Reset trước khi seed lại:
--   TRUNCATE products RESTART IDENTITY CASCADE;
--
-- ⚠️ Giả định: categories đã có ≥10 row (V3 seed có 12 root + sub categories).
-- Nếu rỗng → seed sẽ fail FK. Chạy migration V1-V4 trước.

-- Tắt synchronous_commit cho session này — bulk insert nhanh ~3x.
-- KHÔNG ảnh hưởng integrity vì đây là seed reproducible.
SET synchronous_commit = OFF;

-- Sample names để substring search có data thực tế (mix tiếng Việt + brand).
-- 1M rows × 20 prefix → mỗi prefix ~50K rows. Substring "iphone" sẽ
-- match ~50K-100K → đủ stress test selectivity thấp.
WITH name_pool AS (
    SELECT unnest(ARRAY[
        'iPhone 15 Pro', 'Samsung Galaxy', 'Xiaomi Redmi', 'OPPO Reno',
        'MacBook Air', 'Dell XPS', 'ThinkPad X1', 'Asus ROG',
        'Sony WH', 'AirPods Pro', 'Bose QuietComfort', 'JBL Flip',
        'Áo thun nam', 'Quần jean nữ', 'Giày thể thao', 'Túi xách da',
        'Nồi cơm điện', 'Máy lọc nước', 'Bàn ủi hơi', 'Quạt đứng'
    ]) AS prefix
),
cat_pool AS (
    SELECT id FROM categories ORDER BY id LIMIT 10
)
INSERT INTO products (
    id, sku, name, slug, description, price, currency,
    category_id, status, attributes, created_at, updated_at, version
)
SELECT
    gen_random_uuid(),
    'SKU-' || LPAD(s::text, 8, '0'),
    (SELECT prefix FROM name_pool ORDER BY random() LIMIT 1)
        || ' ' || (s % 5000)::text,
    'product-' || s,
    'Auto-seeded product #' || s || ' cho benchmark Day 16 EXPLAIN ANALYZE.',
    (random() * 50000000)::numeric(12, 2),
    'VND',
    (SELECT id FROM cat_pool ORDER BY random() LIMIT 1),
    CASE WHEN random() < 0.7 THEN 'ACTIVE'
         WHEN random() < 0.9 THEN 'DRAFT'
         ELSE 'ARCHIVED' END,
    jsonb_build_object('seeded', true, 'batch', s / 100000),
    NOW() - (random() * INTERVAL '365 days'),
    NOW() - (random() * INTERVAL '30 days'),
    0
FROM generate_series(1, 1000000) AS s;

-- Refresh planner statistics — bắt buộc sau bulk insert.
ANALYZE products;

-- Sanity check.
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active,
       pg_size_pretty(pg_total_relation_size('products')) AS table_size
  FROM products;
