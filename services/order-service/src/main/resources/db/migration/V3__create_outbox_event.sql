-- ============================================================
-- V3__create_outbox_event.sql (Day 13)
--
-- Transactional outbox table — trả dual-write debt Day 9.
--
-- Pattern:
--   1. Business code save Order + INSERT outbox_event trong CÙNG tx Postgres.
--   2. Relay scheduled (1s) SELECT FOR UPDATE SKIP LOCKED PENDING rows,
--      publish Kafka, UPDATE status=SENT.
--   3. Consumer side đã idempotent (Day 12 NotificationDeduplicator pattern).
--
-- Tại sao SKIP LOCKED?
--   - Multi-instance relay race-free không cần distributed lock.
--   - Postgres native row-lock, cost rất thấp.
--
-- Tại sao partial index PENDING?
--   - 99% query là "fetch PENDING batch". SENT rows chiếm volume nhưng không
--     vào query relay → loại khỏi index → index nhỏ + fast.
--   - Index không cover FAILED vì FAILED là exception path (alert log,
--     manual triage qua runbook).
--
-- Table bloat strategy:
--   - Cron Day 20 (load test) sẽ wire DELETE SENT > 7 days.
--   - Volume estimate 50k orders/day → 350k SENT rows/week → vacuum thường
--     xuyên đủ, chưa cần partition.
-- ============================================================

CREATE TABLE outbox_event (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    topic           VARCHAR(128) NOT NULL,
    partition_key   VARCHAR(128) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,

    CONSTRAINT outbox_status_valid CHECK (status IN ('PENDING','SENT','FAILED')),
    CONSTRAINT outbox_attempts_nonneg CHECK (attempts >= 0)
);

-- Partial index: chỉ PENDING rows + ORDER BY created_at ASC (FIFO relay).
CREATE INDEX outbox_pending_idx
    ON outbox_event (created_at)
    WHERE status = 'PENDING';

-- Index cho SLI alert "outbox lag" + cho manual triage FAILED.
CREATE INDEX outbox_status_idx ON outbox_event (status);
