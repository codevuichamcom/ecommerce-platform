-- ============================================================
-- V1__create_orders.sql — Order aggregate root + OrderItem entity.
--
-- Aggregate boundary: Order owns OrderItems (CASCADE DELETE).
-- OrderItem KHÔNG có lifecycle độc lập — không phải aggregate riêng.
--
-- Status persistence:
--   - status_type: sealed permit name (PendingPayment / Paid / ...).
--   - status_data JSONB: extra data per status (vd: Cancelled stores
--     {reason, cancelledAt}, Shipped stores {trackingNumber}). Cho phép
--     từng permit có data khác nhau mà KHÔNG nhồi nullable column.
--
-- Idempotency: idempotency_key UNIQUE — client retry POST /orders với
-- cùng key sẽ không tạo order trùng (return existing). Optional null
-- cho admin tạo internal.
--
-- Money: amount BIGINT lưu cents (KHÔNG dùng NUMERIC/DOUBLE để tránh
-- rounding error). currency 3-char ISO 4217.
-- ============================================================

CREATE TABLE orders (
    id                  UUID         PRIMARY KEY,
    user_id             UUID         NOT NULL,
    status_type         VARCHAR(32)  NOT NULL,
    status_data         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    total_amount        BIGINT       NOT NULL,
    total_currency      VARCHAR(3)   NOT NULL,
    shipping_recipient  VARCHAR(120) NOT NULL,
    shipping_phone      VARCHAR(20)  NOT NULL,
    shipping_line       VARCHAR(255) NOT NULL,
    shipping_city       VARCHAR(80)  NOT NULL,
    shipping_country    VARCHAR(2)   NOT NULL,
    idempotency_key     VARCHAR(80),
    placed_at           TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),

    CONSTRAINT orders_total_non_negative CHECK (total_amount >= 0),
    CONSTRAINT orders_status_type_valid CHECK (
        status_type IN ('PendingPayment','Paid','Shipped','Delivered','Cancelled')
    )
);

CREATE UNIQUE INDEX orders_idempotency_key_uk
    ON orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX orders_user_status_idx ON orders (user_id, status_type);

CREATE TABLE order_items (
    id                UUID         PRIMARY KEY,
    order_id          UUID         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    sku               VARCHAR(64)  NOT NULL,
    product_name      VARCHAR(255) NOT NULL,
    quantity          INTEGER      NOT NULL,
    unit_price_amount BIGINT       NOT NULL,
    unit_price_ccy    VARCHAR(3)   NOT NULL,

    CONSTRAINT order_items_qty_positive       CHECK (quantity > 0),
    CONSTRAINT order_items_unit_price_non_neg CHECK (unit_price_amount >= 0)
);

CREATE INDEX order_items_order_id_idx ON order_items (order_id);
CREATE INDEX order_items_sku_idx      ON order_items (sku);
