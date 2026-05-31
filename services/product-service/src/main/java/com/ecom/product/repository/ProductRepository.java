package com.ecom.product.repository;

import com.ecom.product.domain.Product;
import com.ecom.product.domain.ProductStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 3 search dùng LIKE substring (`LIKE '%kw%'`) — non-sargable với B-tree
 * thông thường. Day 16 (V5 migration) bật {@code pg_trgm} + GIN trigram trên
 * {@code LOWER(name)} → planner chuyển Seq Scan → Bitmap Index Scan, p95
 * search ở 1M rows từ ~2.5s → ~45ms (xem `docs/performance/16-sql-explain-analyze.md`).
 * Day 22 sẽ migrate sang Elasticsearch khi cần relevance scoring + faceting.
 *
 * <p>{@code @EntityGraph} trên list query — eager-fetch category để
 * tránh N+1 khi map sang DTO (mỗi product trigger 1 query category).
 * Đây là fix N+1 lite-version; full deep-dive N+1 ở Day 17.
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    @EntityGraph(attributePaths = {"category"})
    Optional<Product> findBySlug(String slug);

    boolean existsBySku(String sku);

    boolean existsBySlug(String slug);

    /** Day 8 — order-service snapshot lookup. KHÔNG cần fetch category. */
    Optional<Product> findBySku(String sku);

    /**
     * Search theo name (LIKE case-insensitive) + filter optional category +
     * filter optional status. NULL parameter = bỏ qua filter đó.
     */
    @EntityGraph(attributePaths = {"category"})
    @Query("""
            SELECT p FROM Product p
            WHERE (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<Product> search(@Param("keyword") String keyword,
                         @Param("categoryId") UUID categoryId,
                         @Param("status") ProductStatus status,
                         Pageable pageable);

    /**
     * Day 18 — <b>keyset (seek) pagination</b>, thay offset cho deep page.
     *
     * <p>So với {@link #search} (offset): KHÔNG {@code COUNT(*)}, KHÔNG
     * {@code OFFSET M}. Thay vào đó dùng <b>row-value seek</b>:
     * {@code WHERE (created_at, id) < (cursor_at, cursor_id)}. JPQL không
     * support so sánh tuple {@code (a,b) < (c,d)} nên phải <b>expand tay</b>:
     * <pre>created_at &lt; :at OR (created_at = :at AND id &lt; :id)</pre>
     * Cặp ngoặc + tie-break {@code id} là bắt buộc — bỏ tie-break thì 2 row
     * trùng {@code created_at} ở ranh giới page sẽ bị skip hoặc lặp.
     *
     * <p>{@code ORDER BY created_at DESC, id DESC} — tie-break direction
     * (id DESC) phải khớp toán tử seek ({@code id <}). Migration V6 tạo index
     * {@code (created_at DESC, id DESC)} đúng thứ tự này → planner Index Scan,
     * KHÔNG Sort node, độ sâu page không ảnh hưởng cost.
     *
     * <p>Fetch {@code limit = size + 1} (caller truyền) để biết
     * {@code hasNext} mà không cần COUNT: nếu trả về dư 1 row ⇒ còn page sau.
     *
     * <p>{@code cursorAt == null} ⇒ trang đầu (bỏ điều kiện seek). Dùng 1
     * query duy nhất cho cả 2 trường hợp qua guard {@code :cursorAt IS NULL}.
     */
    @EntityGraph(attributePaths = {"category"})
    @Query("""
            SELECT p FROM Product p
            WHERE (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:status IS NULL OR p.status = :status)
              AND (
                    :cursorAt IS NULL
                 OR p.createdAt < :cursorAt
                 OR (p.createdAt = :cursorAt AND p.id < :cursorId)
                  )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Product> searchKeyset(@Param("keyword") String keyword,
                               @Param("categoryId") UUID categoryId,
                               @Param("status") ProductStatus status,
                               @Param("cursorAt") Instant cursorAt,
                               @Param("cursorId") UUID cursorId,
                               Limit limit);
}
