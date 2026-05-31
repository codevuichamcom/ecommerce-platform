-- Day 18 — Keyset (seek) pagination index.
--
-- Day 3 (V2) đã tạo: idx_products_created ON products (created_at DESC, id).
-- Index đó tie-break id theo ASC (mặc định). Keyset query của Day 18 dùng:
--   ORDER BY created_at DESC, id DESC
--   WHERE created_at < ? OR (created_at = ? AND id < ?)
-- Tie-break là id DESC. Postgres CÓ THỂ scan ngược index (created_at DESC,
-- id ASC) để phục vụ (..., id DESC) — nhưng khi 2 cột ngược chiều nhau so
-- với index, planner phải scan backward + đôi khi không tận dụng được hết.
--
-- Index dưới đây khớp CHÍNH XÁC thứ tự + chiều của ORDER BY keyset →
-- Index Scan thuần, KHÔNG Sort node, KHÔNG backward-scan ambiguity. Đây là
-- điểm dạy cốt lõi: "index ordering phải khớp ORDER BY + tie-break direction".
--
-- KHÔNG drop idx_products_created cũ: nó vẫn phục vụ các query ORDER BY
-- created_at DESC đơn thuần (không seek) + covering id cho list. Giữ cả hai,
-- chấp nhận thêm vài % write cost — read path là hot path của catalog.
--
-- ⚠️ Production: ở 1M+ rows nên CREATE INDEX CONCURRENTLY ngoài Flyway để
-- không AccessExclusiveLock (xem V5 note + lessons/16-postgres-indexing.md).
-- Dev/test để Flyway chạy thường.

CREATE INDEX IF NOT EXISTS idx_products_keyset
    ON products (created_at DESC, id DESC);

-- Force ANALYZE để planner thấy index mới ngay query đầu (giống V5).
ANALYZE products;
