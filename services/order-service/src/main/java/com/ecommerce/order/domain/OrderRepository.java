package com.ecommerce.order.domain;

import com.ecommerce.order.application.dto.OrderSummaryView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port repository cho aggregate {@link Order}. Spring Data JPA generate
 * implementation. Cùng tư tưởng StockRepository ở Day 4 — không expose
 * generic finder, chỉ method có ngữ nghĩa business.
 *
 * <h2>Day 17 — 3 nấc thang chống N+1 (đọc kèm {@code docs/issues/17-jpa-n-plus-one.md})</h2>
 *
 * <p>{@link Order#getItems()} map {@code @OneToMany(fetch = EAGER)}. List N
 * order → Hibernate bắn 1 query lấy order + N query lấy items = <b>N+1</b>.
 * 3 method dưới minh họa 3 cách fix, mỗi cách giải quyết vấn đề cách trước
 * để lại. {@code DebugController} chạy cả 3 side-by-side đếm query.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Idempotency check — tìm order theo (userId, idempotencyKey). */
    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // ─────────────────────────────────────────────────────────────────────
    // Nấc 0 — N+1 NGUYÊN BẢN (chỉ dùng để demo/đo, KHÔNG dùng production)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Derived query thường. Vì {@code items} là EAGER, mỗi Order trong page
     * trigger 1 SELECT items riêng → 1 + N query. Đây là cái bẫy kinh điển:
     * code trông sạch, JPA âm thầm bắn N+1.
     */
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    // ─────────────────────────────────────────────────────────────────────
    // Nấc 1 — @EntityGraph: ép LEFT JOIN FETCH trong 1 query
    // ─────────────────────────────────────────────────────────────────────

    /**
     * {@code @EntityGraph} override fetch plan: items được JOIN FETCH trong
     * cùng 1 query → hết N+1.
     *
     * <p><b>Cạm bẫy còn lại</b>: collection fetch + {@link Pageable} →
     * Hibernate log {@code HHH000104: firstResult/maxResults specified with
     * collection fetch; applying in memory!} Nó kéo TẤT CẢ row khớp về JVM
     * rồi mới phân trang trong bộ nhớ → nguy cơ OOM khi data lớn. Pagination
     * KHÔNG còn chạy ở DB. Vì vậy method này nhận {@code Pageable} chỉ để
     * <b>demo cái bẫy</b>, không phải production list path.
     */
    @EntityGraph(attributePaths = "items")
    @Query("select o from Order o where o.userId = :userId")
    Page<Order> findWithItemsByUserId(@Param("userId") UUID userId, Pageable pageable);

    // ─────────────────────────────────────────────────────────────────────
    // Nấc 2 — JOIN FETCH thủ công (không phân trang ở DB được khi fetch bag)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * JOIN FETCH viết tay. 1 query, hết N+1, KHÔNG phân trang.
     *
     * <p>Order chỉ có 1 collection nên an toàn. Nếu JOIN FETCH ≥2 collection
     * kiểu {@code List} (bag) cùng lúc → {@code MultipleBagFetchException}
     * (Hibernate không dựng nổi cartesian product của 2 bag). Fix: đổi
     * {@code List}→{@code Set}, hoặc tách thành nhiều query. Chi tiết ở
     * lesson 17.
     *
     * <p>Dùng cho detail/export path cần full aggregate của 1 user lượng
     * nhỏ — KHÔNG cho list phân trang.
     */
    @Query("select distinct o from Order o join fetch o.items where o.userId = :userId")
    List<Order> findAllWithItemsByUserId(@Param("userId") UUID userId);

    // ─────────────────────────────────────────────────────────────────────
    // Nấc 3 — PROJECTION DTO: production list path
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Constructor-expression projection. Select thẳng scalar, KHÔNG load
     * entity, KHÔNG vào persistence context. {@code size(o.items)} dịch sang
     * subquery {@code COUNT(*)} → vẫn 1 query chính (+1 count query cho
     * {@link Page#getTotalElements()}). Pagination chạy ở DB thật
     * ({@code LIMIT/OFFSET}) — KHÔNG in-memory như nấc 1.
     *
     * <p>Count query Spring tự suy ra được vì select clause là constructor
     * expression đơn (không GROUP BY) → {@code select count(o) ...}.
     */
    @Query("""
            select new com.ecommerce.order.application.dto.OrderSummaryView(
                o.id, o.statusType, o.total.amount, o.total.currency,
                o.reservationStatus, o.placedAt, size(o.items))
            from Order o
            where o.userId = :userId
            """)
    Page<OrderSummaryView> findSummariesByUserId(@Param("userId") UUID userId, Pageable pageable);
}
