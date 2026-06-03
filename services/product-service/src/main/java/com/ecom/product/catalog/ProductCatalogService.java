package com.ecom.product.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Day 23 — đọc catalog read-model. Điểm nhấn: <b>filter theo thuộc tính động</b>
 * — thứ chứng minh vì sao document model thắng EAV cho flexible attributes.
 *
 * <p>Query "tất cả TV có resolution = 4K":
 * <pre>
 *   Mongo:    { categorySlug: "tv", "attributes.resolution": "4K" }
 *   Postgres: SELECT ... WHERE category_slug='tv' AND attributes->>'resolution'='4K'  (JSONB — cũng được)
 *   EAV:      JOIN attr a1 ON a1.key='resolution' AND a1.val='4K' ... (self-join, mất type)
 * </pre>
 * Mongo dot-notation {@code attributes.resolution} truy field lồng nhau như
 * field thường + index được. Đây KHÔNG phải "Mongo làm được mà SQL không" —
 * Postgres JSONB cũng làm — mà là "khi attribute là CORE của query pattern +
 * shape đa dạng + cần scale đọc ngang, document store là first-class fit".
 */
@Service
@RequiredArgsConstructor
public class ProductCatalogService {

    private final ProductCatalogRepository catalogRepository;
    private final MongoTemplate mongoTemplate;

    public Optional<ProductCatalogDocument> getById(String productId) {
        return catalogRepository.findById(productId);
    }

    public List<ProductCatalogDocument> byCategory(String categorySlug) {
        return catalogRepository.findByCategorySlug(categorySlug);
    }

    /**
     * Filter động: {@code categorySlug} + 1 cặp {@code attrKey=attrValue} trong
     * sub-document {@code attributes}. Production muốn nhiều cặp → build Criteria
     * loop; Day 23 giữ 1 cặp cho rõ ý.
     *
     * <p>⚠️ Index: filter {@code attributes.<key>} với key ĐỘNG → không thể tạo
     * sẵn index cho mọi key. 2 lựa chọn: (a) index có chủ đích các key hot
     * (resolution, color) qua {@code mongoTemplate.indexOps().ensureIndex()};
     * (b) wildcard index {@code {"attributes.$**": 1}} phủ mọi key nhưng tốn
     * ghi + RAM. Day 23 dùng {@code categorySlug} index để thu hẹp trước, attr
     * filter chạy trên tập nhỏ — đủ ở volume catalog.
     */
    public List<ProductCatalogDocument> byCategoryAndAttribute(String categorySlug, String attrKey, String attrValue) {
        Query query = new Query(Criteria.where("categorySlug").is(categorySlug)
                .and("attributes." + attrKey).is(attrValue));
        return mongoTemplate.find(query, ProductCatalogDocument.class);
    }
}
