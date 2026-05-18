-- ============================================================
-- V2__add_reservation_status.sql (Day 9)
--
-- Eventual consistency tracker cho event-driven reserve flow:
--   - PENDING   : order saved, đã publish `order.created`, chưa nhận
--                 `inventory.reserved` ack.
--   - RESERVED  : nhận `inventory.reserved` cho TẤT CẢ items.
--   - FAILED    : Day 12 sẽ wire — `inventory.reserve.failed` event sau khi
--                 retry hết → order auto-cancel + refund flow.
--
-- Vì sao tách column thay vì nhồi vào `status_data` JSONB?
--   - Reservation là cross-cutting concern (tracking eventual consistency
--     window) — không phải business state machine của Order. Tách column
--     giúp query "order pending reservation > 30s" làm SLI alert.
--   - JSONB query chậm hơn varchar khi index (Day 16 sẽ tune).
--
-- Default PENDING cho order cũ — Day 6 không có reservation tracking,
-- backfill mặc định PENDING không ảnh hưởng vì Day 6 orders đã ở terminal.
-- ============================================================

ALTER TABLE orders
    ADD COLUMN reservation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE orders
    ADD CONSTRAINT orders_reservation_status_valid
        CHECK (reservation_status IN ('PENDING','RESERVED','FAILED'));

-- Partial index cho SLI query "pending reservation > N seconds".
CREATE INDEX orders_pending_reservation_idx
    ON orders (placed_at)
    WHERE reservation_status = 'PENDING';
