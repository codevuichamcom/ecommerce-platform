package com.ecom.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Port repository cho aggregate {@link Stock}. Spring Data JPA tự generate
 * implementation. Đặt ở package domain (không phải infrastructure) vì:
 * <ul>
 *   <li>JpaRepository ở đây là Spring Data abstraction, không bind Hibernate.</li>
 *   <li>Project size MVP — tách thêm port/adapter layer = over-engineering.
 *       Khi switch persistence (vd: sang event-sourcing) mới refactor.</li>
 * </ul>
 *
 * <p>KHÔNG expose {@code findAll}, {@code findByQuantityGreaterThan}, ...
 * Tất cả query phải đi qua method tên-business (vd: tương lai
 * {@code findByLowStock}). Day 4 chỉ cần load by PK.
 */
public interface StockRepository extends JpaRepository<Stock, String> {

    /** Tổng reserved toàn kho — dùng cho daily snapshot job (Day 19). */
    @Query("select coalesce(sum(s.reserved), 0) from Stock s")
    long sumReserved();
}
