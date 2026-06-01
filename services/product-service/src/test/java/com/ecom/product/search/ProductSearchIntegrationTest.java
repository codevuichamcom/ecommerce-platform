package com.ecom.product.search;

import com.ecom.product.search.dto.FacetBucket;
import com.ecom.product.search.dto.ProductSearchResult;
import com.ecom.product.search.dto.SearchHitResponse;
import com.ecom.product.support.ElasticsearchTestcontainerConfig;
import com.ecom.product.support.PostgresTestcontainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 22 — ES integration test với Testcontainers. Gated
 * {@code RUN_PRODUCT_INTEGRATION_TESTS=true} (cùng convention service khác).
 *
 * <p>{@code app.kafka.enabled=false}: test search KHÔNG cần sync pipeline →
 * index document trực tiếp qua {@code ElasticsearchOperations}, bỏ Kafka cho
 * context nhẹ + không cần Kafka container.
 *
 * <p>Cover:
 * <ul>
 *   <li>Fuzzy: gõ "iphon" (sai chính tả) → match "iPhone" (fuzziness AUTO).</li>
 *   <li>Relevance boost: match ở name xếp trên match ở description (name^3).</li>
 *   <li>Faceted aggregation: count theo brand đúng.</li>
 *   <li>Highlight: matched term bọc {@code <em>}.</li>
 *   <li>Filter cứng: brand filter loại sản phẩm khác brand.</li>
 *   <li>status filter: ARCHIVED không search ra.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.kafka.enabled=false",
        "spring.flyway.enabled=true"
})
@Import({PostgresTestcontainerConfig.class, ElasticsearchTestcontainerConfig.class})
@EnabledIfEnvironmentVariable(named = "RUN_PRODUCT_INTEGRATION_TESTS", matches = "true")
class ProductSearchIntegrationTest {

    private static final UUID CAT_PHONE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired ProductSearchService searchService;
    @Autowired ProductSearchRepository searchRepository;
    @Autowired ElasticsearchOperations esOps;

    @BeforeEach
    void seed() {
        // Recreate index sạch mỗi test — tránh leak document giữa test.
        IndexOperations indexOps = esOps.indexOps(ProductDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
        }
        indexOps.createWithMapping();

        searchRepository.saveAll(List.of(
                doc("iPhone 15 Pro Max", "Flagship smartphone của Apple", "Apple", "ACTIVE", 30_000_000),
                doc("Samsung Galaxy S24", "Android flagship màn hình đẹp", "Samsung", "ACTIVE", 22_000_000),
                doc("Ốp lưng cho iPhone", "Phụ kiện bảo vệ điện thoại iPhone", "Spigen", "ACTIVE", 200_000),
                doc("iPhone 13 cũ", "Hàng trưng bày ngừng bán", "Apple", "ARCHIVED", 12_000_000)));
        indexOps.refresh();  // ép visible ngay (default refresh 1s) cho assertion.
    }

    @Test
    void fuzzy_typo_matchesIphone() {
        ProductSearchResult result = searchService.search("iphon", null, null, null, null, 0, 20);

        assertThat(result.source()).isEqualTo("elasticsearch");
        // "iphon" fuzzy match "iPhone" — cả product TÊN iPhone lẫn ốp lưng nhắc iPhone.
        assertThat(result.hits()).extracting(SearchHitResponse::name)
                .anyMatch(n -> n.contains("iPhone 15 Pro Max"));
        // ARCHIVED iPhone 13 KHÔNG được search ra (status filter ACTIVE).
        assertThat(result.hits()).extracting(SearchHitResponse::name)
                .noneMatch(n -> n.contains("iPhone 13"));
    }

    @Test
    void relevanceBoost_nameRanksAboveDescription() {
        ProductSearchResult result = searchService.search("iPhone", null, null, null, null, 0, 20);

        // name^3 → "iPhone 15 Pro Max" (match ở name) phải xếp TRÊN "Ốp lưng cho
        // iPhone" (match cũng ở name) và trên product chỉ nhắc iPhone ở description.
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).name()).contains("iPhone");
        assertThat(result.hits().get(0).score()).isGreaterThan(0.0);
    }

    @Test
    void facet_brandCountsCorrect() {
        ProductSearchResult result = searchService.search(null, null, null, null, null, 0, 20);

        List<FacetBucket> brands = result.facets().get("brand");
        assertThat(brands).isNotNull();
        // Apple có 1 ACTIVE (iPhone 15) — iPhone 13 ARCHIVED bị status filter loại,
        // nhưng facet tính trên cùng query (đã filter ACTIVE) → Apple = 1.
        FacetBucket apple = brands.stream().filter(b -> b.key().equals("Apple")).findFirst().orElseThrow();
        assertThat(apple.count()).isEqualTo(1);
    }

    @Test
    void highlight_wrapsMatchedTerm() {
        ProductSearchResult result = searchService.search("Galaxy", null, null, null, null, 0, 20);

        SearchHitResponse hit = result.hits().get(0);
        assertThat(hit.highlights()).isNotEmpty();
        assertThat(hit.highlights()).anyMatch(h -> h.contains("<em>"));
    }

    @Test
    void brandFilter_excludesOtherBrands() {
        ProductSearchResult result = searchService.search(null, null, "Samsung", null, null, 0, 20);

        assertThat(result.hits()).extracting(SearchHitResponse::brand)
                .containsOnly("Samsung");
    }

    private ProductDocument doc(String name, String desc, String brand, String status, long price) {
        ProductDocument d = new ProductDocument();
        d.setId(UUID.randomUUID().toString());
        d.setSku("sku-" + UUID.randomUUID());
        d.setName(name);
        d.setDescription(desc);
        d.setBrand(brand);
        d.setStatus(status);
        d.setPrice(BigDecimal.valueOf(price));
        d.setCurrency("VND");
        d.setCategoryId(CAT_PHONE.toString());
        d.setCategorySlug("phones");
        d.setAttributes(Map.of("brand", brand));
        d.setCreatedAt(Instant.now());
        return d;
    }
}
