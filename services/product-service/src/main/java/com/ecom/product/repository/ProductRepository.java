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
 * Day 3 search dùng LIKE basic — chỉ tham khảo, sẽ rất chậm khi 1M rows
 * (Day 16 sẽ EXPLAIN ANALYZE + GIN trigram, Day 22 migrate ES).
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
