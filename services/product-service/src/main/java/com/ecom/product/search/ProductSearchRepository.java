package com.ecom.product.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * Day 22 — Spring Data ES repository cho CRUD đơn giản trên index
 * {@code products}: {@code save()} (index/upsert by id), {@code deleteById()},
 * {@code count()}. ProductIndexer dùng repository này cho write path.
 *
 * <p><b>Tại sao KHÔNG dùng derived query method (findByNameContaining...) ở
 * đây cho search?</b> Vì search thật cần multi_match + fuzziness + facet +
 * highlight — derived query không express được. Search query phức tạp nằm ở
 * {@link ProductSearchService} dùng {@code NativeQuery}. Repository chỉ lo
 * write + count (cho drift metric Day 25).
 */
@Repository
public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, String> {
}
