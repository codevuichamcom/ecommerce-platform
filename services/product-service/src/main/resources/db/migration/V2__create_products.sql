-- Day 3 — products table.
--
-- price NUMERIC(12,2): tránh dùng FLOAT/DOUBLE cho tiền (rounding error).
-- 12,2 ≈ tối đa 9,999,999,999.99 — đủ cho tier ecommerce VN (VND không
-- decimal nhưng vẫn dùng NUMERIC để consistent multi-currency Day 10+).
--
-- attributes JSONB: flexible attribute (TV: screen_size; áo: size+color).
-- Day 23 sẽ benchmark vs Mongo. JSONB cho phép GIN index nội thuộc tính
-- (Day 16 sẽ làm), khác với JSON (text) không indexable hiệu quả.
--
-- status: VARCHAR(32) thay vì SMALLINT enum-id — readable trong tool DB
-- + tránh app-DB drift khi enum đổi thứ tự. Trade-off: tốn storage hơn
-- vài byte/row, không đáng kể.
CREATE TABLE products (
    id           UUID PRIMARY KEY,
    sku          VARCHAR(64)    NOT NULL UNIQUE,
    name         VARCHAR(255)   NOT NULL,
    slug         VARCHAR(255)   NOT NULL UNIQUE,
    description  TEXT,
    price        NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    currency     CHAR(3)        NOT NULL DEFAULT 'VND',
    category_id  UUID           NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    status       VARCHAR(32)    NOT NULL DEFAULT 'DRAFT',
    attributes   JSONB          NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ    NOT NULL,
    updated_at   TIMESTAMPTZ    NOT NULL,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    version      BIGINT         NOT NULL DEFAULT 0
);

-- Index strategy Day 3 (sẽ tune Day 16):
--  * category_id: filter list-by-category — high cardinality OK với B-tree.
--  * status: dùng partial index cho query chính (chỉ ACTIVE) — giảm size.
--  * name LIKE 'prefix%': B-tree ASC hỗ trợ prefix match (left-anchored);
--    `LIKE '%term%'` (substring) vẫn seq scan — Day 16 thay GIN trigram.
CREATE INDEX idx_products_category   ON products (category_id);
CREATE INDEX idx_products_active     ON products (status) WHERE status = 'ACTIVE';
CREATE INDEX idx_products_name_lower ON products (LOWER(name) text_pattern_ops);
CREATE INDEX idx_products_created    ON products (created_at DESC, id);
