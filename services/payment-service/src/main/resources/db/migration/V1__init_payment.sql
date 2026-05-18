-- =============================================================
-- Day 10 — payment-service initial schema.
--
-- Idempotency design:
--   - UNIQUE(provider, provider_txn_id) là source of truth chống duplicate
--     callback (xem docs/issues/10-duplicate-payment-callback.md).
--   - Partial index WHERE provider_txn_id IS NOT NULL: PaymentIntent ở
--     trạng thái INITIATED chưa có txn_id từ gateway → multiple NULL OK.
--
-- State machine (xem PaymentStatus sealed):
--   INITIATED → AUTHORIZED → CAPTURED   (happy path)
--             → FAILED                  (gateway reject)
--             → EXPIRED                 (TTL — Day 12 sẽ scheduler quét)
--
-- Defense-in-depth: CHECK constraint amount ≥ 0 + status IN whitelist
-- chặn cả case bug ở app (đã thấy production "amount=-1" bug ở team cũ).
-- =============================================================

CREATE TABLE payment_intent (
    id                 UUID            PRIMARY KEY,
    order_id           UUID            NOT NULL,
    amount             NUMERIC(15,2)   NOT NULL CHECK (amount >= 0),
    currency           VARCHAR(3)      NOT NULL,
    status             VARCHAR(16)     NOT NULL
        CHECK (status IN ('INITIATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'EXPIRED')),
    provider           VARCHAR(32)     NOT NULL,
    provider_txn_id    VARCHAR(128),
    failure_reason     VARCHAR(255),

    -- BaseEntity audit fields.
    version            BIGINT          NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ     NOT NULL,
    updated_at         TIMESTAMPTZ     NOT NULL,
    created_by         VARCHAR(64),
    updated_by         VARCHAR(64)
);

-- Idempotency guard — atomic ở DB level, không thể bypass dù app race.
-- Partial index vì INITIATED rows có txn_id NULL chưa biết (multiple NULL không vi phạm UNIQUE
-- ở Postgres, nhưng partial index làm intent rõ ràng + nhỏ hơn).
CREATE UNIQUE INDEX uq_payment_provider_txn
    ON payment_intent (provider, provider_txn_id)
    WHERE provider_txn_id IS NOT NULL;

-- Truy vấn theo order phổ biến (admin xem payment của order, reconciliation report).
CREATE INDEX ix_payment_order_id ON payment_intent (order_id);

-- SLI: count(payment INITIATED quá 10 phút) → alert (gateway down / bug).
CREATE INDEX ix_payment_status_created ON payment_intent (status, created_at);
