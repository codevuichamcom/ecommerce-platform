package com.ecom.product.repository;

import com.ecom.product.domain.Product;
import com.ecom.product.domain.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
