package com.ecom.product.catalog;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spring Data Mongo repository cho catalog read-model.
 *
 * <p>product-service giờ có 3 loại repository, mỗi cái route tới 1 store nhờ
 * extend interface store-specific (Spring Data multi-store phân biệt bằng
 * base interface, KHÔNG mơ hồ):
 * <ul>
 *   <li>{@code ProductRepository extends JpaRepository} → Postgres (source of truth)</li>
 *   <li>{@code ProductSearchRepository extends ElasticsearchRepository} → ES (search)</li>
 *   <li>{@code ProductCatalogRepository extends MongoRepository} → Mongo (catalog read-model)</li>
 * </ul>
 *
 * <p>Derived query {@code findByCategorySlug} đủ cho "liệt kê 1 category".
 * Filter theo attribute động ({@code attributes.resolution=4K}) KHÔNG biểu đạt
 * được bằng derived-query (key động) → {@link ProductCatalogService} dùng
 * {@code MongoTemplate} + {@code Criteria}.
 */
public interface ProductCatalogRepository extends MongoRepository<ProductCatalogDocument, String> {

    List<ProductCatalogDocument> findByCategorySlug(String categorySlug);
}
