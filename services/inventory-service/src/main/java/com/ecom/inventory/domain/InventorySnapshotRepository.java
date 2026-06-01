package com.ecom.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventorySnapshotRepository extends JpaRepository<InventorySnapshot, UUID> {

    /**
     * Upsert snapshot ngày {@code date} VỚI fencing guard.
     *
     * <p>{@code ON CONFLICT ... WHERE last_fencing_token < EXCLUDED.last_fencing_token}:
     * chỉ ghi đè nếu token mới LỚN hơn token đã lưu. Stale writer (instance tỉnh
     * dậy sau GC pause, cầm token cũ) → WHERE fail → 0 row → bị từ chối tại DB.
     * Đây là lớp correctness thật, lock chỉ là best-effort mutual exclusion.
     *
     * @return số row bị ảnh hưởng — 0 nghĩa là write bị fence từ chối.
     */
    @Modifying
    @Query(value = """
        INSERT INTO inventory_snapshot
            (snapshot_date, total_skus, total_reserved, last_fencing_token, created_by_instance, created_at)
        VALUES (:date, :totalSkus, :totalReserved, :token, :instance, now())
        ON CONFLICT (snapshot_date) DO UPDATE
           SET total_skus          = EXCLUDED.total_skus,
               total_reserved      = EXCLUDED.total_reserved,
               last_fencing_token  = EXCLUDED.last_fencing_token,
               created_by_instance = EXCLUDED.created_by_instance,
               created_at          = now()
         WHERE inventory_snapshot.last_fencing_token < EXCLUDED.last_fencing_token
        """, nativeQuery = true)
    int upsertWithFence(
        @Param("date") LocalDate date,
        @Param("totalSkus") long totalSkus,
        @Param("totalReserved") long totalReserved,
        @Param("token") long token,
        @Param("instance") String instance);
}
