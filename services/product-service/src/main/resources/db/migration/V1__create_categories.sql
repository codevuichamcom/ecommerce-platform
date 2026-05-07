-- Day 3 — categories table.
--
-- Hierarchical category qua parent_id self-FK (đủ cho 3-4 level depth điển
-- hình của ecommerce: Electronics → Phones → Smartphones). Sâu hơn nên
-- xem xét closure table / materialized path; nhưng over-engineer cho Day 3.
--
-- slug UNIQUE: dùng cho SEO URL `/c/electronics`. Index B-tree mặc định
-- vì lookup bằng equality.
CREATE TABLE categories (
    id          UUID PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    slug        VARCHAR(150) NOT NULL UNIQUE,
    parent_id   UUID         REFERENCES categories(id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_categories_parent ON categories (parent_id);
