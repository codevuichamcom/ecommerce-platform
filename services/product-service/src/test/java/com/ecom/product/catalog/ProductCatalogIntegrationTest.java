package com.ecom.product.catalog;

import com.ecom.product.support.ElasticsearchTestcontainerConfig;
import com.ecom.product.support.MongoTestcontainerConfig;
import com.ecom.product.support.PostgresTestcontainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 23 — catalog read-model integration test (Mongo container). Gated
 * {@code RUN_PRODUCT_INTEGRATION_TESTS=true} (cùng gate ES test).
 *
 * <p>{@code app.kafka.enabled=false}: ghi document trực tiếp qua repository,
 * bỏ Kafka pipeline cho context nhẹ. Postgres container vẫn cần vì product-service
 * load JPA (Flyway validate schema lúc boot).
 *
 * <p>Điểm test trọng tâm: <b>filter theo thuộc tính ĐỘNG</b> — chứng minh
 * document model trị flexible attributes (TV có resolution, áo có size/color)
 * mà không EAV.
 */
@SpringBootTest(properties = {
        "app.kafka.enabled=false",
        "spring.flyway.enabled=true"
})
// ES container cũng cần: product-service context khởi tạo ES client eager
// (Spring Data ES kết nối lúc bootstrap repository) → thiếu ES = ConnectException.
@Import({PostgresTestcontainerConfig.class, ElasticsearchTestcontainerConfig.class, MongoTestcontainerConfig.class})
@EnabledIfEnvironmentVariable(named = "RUN_PRODUCT_INTEGRATION_TESTS", matches = "true")
class ProductCatalogIntegrationTest {

    @Autowired ProductCatalogService catalogService;
    @Autowired ProductCatalogRepository catalogRepository;

    @BeforeEach
    void seed() {
        catalogRepository.deleteAll();
        // TV — attribute shape: screen_size / resolution / panel
        catalogRepository.saveAll(List.of(
                tv("Sony Bravia 55 OLED", "55", "4K", "OLED"),
                tv("LG C3 48 OLED", "48", "4K", "OLED"),
                tv("Samsung 43 FHD", "43", "FHD", "LED")));
        // Áo — attribute shape HOÀN TOÀN khác: size / color / material
        catalogRepository.save(shirt("Áo thun nam navy L", "L", "navy", "cotton"));
    }

    @Test
    void getById_returnsFlexibleAttributes() {
        ProductCatalogDocument tv = catalogRepository.findByCategorySlug("tv").get(0);

        ProductCatalogDocument loaded = catalogService.getById(tv.getId()).orElseThrow();
        assertThat(loaded.getCategorySlug()).isEqualTo("tv");
        assertThat(loaded.getAttributes()).containsKey("resolution");
    }

    @Test
    void byCategoryAndAttribute_filtersOnDynamicNestedField() {
        // "TV nào 4K?" → filter attributes.resolution = 4K (dot-notation nested).
        List<ProductCatalogDocument> fourK =
                catalogService.byCategoryAndAttribute("tv", "resolution", "4K");

        assertThat(fourK).hasSize(2);
        assertThat(fourK).allSatisfy(d ->
                assertThat(d.getAttributes().get("resolution")).isEqualTo("4K"));
    }

    @Test
    void differentCategories_haveDifferentAttributeShapes() {
        // Document model: TV và áo cùng collection nhưng shape attribute khác —
        // thứ relational ép vào 1 bảng sẽ thành EAV hoặc cột NULL la liệt.
        ProductCatalogDocument shirt = catalogRepository.findByCategorySlug("ao").get(0);
        assertThat(shirt.getAttributes()).containsKeys("size", "color", "material");
        assertThat(shirt.getAttributes()).doesNotContainKey("resolution");
    }

    private ProductCatalogDocument tv(String name, String size, String resolution, String panel) {
        return doc(name, "tv", Map.of("screen_size", size, "resolution", resolution, "panel", panel));
    }

    private ProductCatalogDocument shirt(String name, String size, String color, String material) {
        return doc(name, "ao", Map.of("size", size, "color", color, "material", material));
    }

    private ProductCatalogDocument doc(String name, String categorySlug, Map<String, Object> attrs) {
        ProductCatalogDocument d = new ProductCatalogDocument();
        d.setId(UUID.randomUUID().toString());
        d.setSku("SKU-" + UUID.randomUUID());
        d.setName(name);
        d.setSlug(name.toLowerCase().replace(' ', '-'));
        d.setPrice(new BigDecimal("10000000"));
        d.setCurrency("VND");
        d.setCategorySlug(categorySlug);
        d.setStatus("ACTIVE");
        d.setAttributes(attrs);
        d.setCreatedAt(Instant.now());
        return d;
    }
}
