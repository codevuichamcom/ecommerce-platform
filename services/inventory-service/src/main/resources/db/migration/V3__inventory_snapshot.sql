-- Day 19 — daily inventory snapshot (leader-elected job + fencing token).
--
-- Job "daily snapshot" chạy trên nhiều instance; chỉ 1 instance được phép ghi
-- mỗi ngày (leader-elect qua Redis distributed lock). NHƯNG lock alone không
-- chống được GC-pause split-brain: instance A pause, lock expire, B chiếm và
-- ghi, A tỉnh dậy ghi đè bằng dữ liệu CŨ.
--
-- `last_fencing_token` là hàng rào (fence) ở RESOURCE: mỗi lần acquire lock,
-- Redis INCR token → tăng đơn điệu. Write mang token NHỎ hơn token đã lưu bị
-- từ chối ngay tại DB (xem upsert WHERE ở InventorySnapshotRepository).
-- Đây mới là thứ bảo đảm correctness — xem issues/19-redlock-correctness.md.

CREATE TABLE inventory_snapshot (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    snapshot_date       DATE         NOT NULL,
    total_skus          BIGINT       NOT NULL,
    total_reserved      BIGINT       NOT NULL,
    last_fencing_token  BIGINT       NOT NULL,
    created_by_instance VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_inventory_snapshot PRIMARY KEY (id),
    CONSTRAINT uq_inventory_snapshot_date UNIQUE (snapshot_date),
    CONSTRAINT ck_inventory_snapshot_token_nonneg CHECK (last_fencing_token >= 0)
);
