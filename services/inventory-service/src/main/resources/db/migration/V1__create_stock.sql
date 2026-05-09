-- ============================================================
-- V1__create_stock.sql — Stock aggregate root.
--
-- Invariant DB-level: reserved >= 0, quantity >= reserved (CHECK).
-- App-level enforce trong Stock.reserve()/release(); CHECK ở đây
-- là defense-in-depth (admin SQL adhoc cũng không phá invariant).
--
-- Optimistic locking qua cột `version` (BaseEntity @Version map sang).
-- Hibernate generate UPDATE ... WHERE id=? AND version=? — affected_rows=0
-- → ObjectOptimisticLockingFailureException → @Retryable retry.
-- ============================================================
CREATE TABLE stock (
    sku            VARCHAR(64)  PRIMARY KEY,
    quantity       INTEGER      NOT NULL,
    reserved       INTEGER      NOT NULL DEFAULT 0,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    CONSTRAINT stock_quantity_non_negative CHECK (quantity >= 0),
    CONSTRAINT stock_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT stock_reserved_le_quantity  CHECK (reserved <= quantity)
);

-- Seed sample SKU cho smoke test + concurrency demo.
INSERT INTO stock (sku, quantity, reserved) VALUES
    ('SKU-IPHONE-15', 50, 0),
    ('SKU-AIRPODS-PRO', 200, 0);
