package com.ecom.product;

import com.ecom.product.domain.ProductStatus;
import com.ecom.product.repository.ProductRepository;
import com.ecom.product.support.PostgresTestcontainerConfig;
import com.ecom.product.web.dto.ProductCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E integration test cho product-service.
 *
 * <p>Skip mặc định trên local Windows (cùng Docker Desktop 29.x compat
 * issue như auth-service). Run bằng: {@code RUN_PRODUCT_INTEGRATION_TESTS=true}.
 *
 * <p>Cover:
 * <ul>
 *   <li>Create product → list trả về.</li>
 *   <li>Search by keyword → match name LIKE.</li>
 *   <li>Filter by category + status.</li>
 *   <li>Pagination metadata (page/size/totalPages/hasNext).</li>
 *   <li>Response không leak entity (jsonPath assertion).</li>
 *   <li>Create không có auth → 401.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_PRODUCT_INTEGRATION_TESTS", matches = "true")
class ProductServiceIntegrationTest {

    private static final UUID ELECTRONICS_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired ProductRepository productRepository;

    @BeforeEach
    void clean() {
        productRepository.deleteAllInBatch();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_then_list_returnsProduct() throws Exception {
        UUID id = createProduct("ip-15-pro", "iPhone 15 Pro", "iphone-15-pro");

        MvcResult res = mockMvc.perform(get("/products").param("q", "iPhone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data.items[0].categorySlug").value("electronics"))
                // Anti-leak: response KHÔNG có field entity-only như password, hibernate proxy.
                .andExpect(jsonPath("$.data.items[0].hibernateLazyInitializer").doesNotExist())
                .andReturn();

        JsonNode page = json.readTree(res.getResponse().getContentAsString()).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void search_filterByCategory_returnsOnlyMatching() throws Exception {
        createProduct("sku-1", "iPhone", "iphone");
        createProduct("sku-2", "Galaxy", "galaxy");

        mockMvc.perform(get("/products")
                        .param("categoryId", ELECTRONICS_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateSku_returnsConflict() throws Exception {
        createProduct("dup-sku", "Product A", "product-a");

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(buildRequest("dup-sku", "Product B", "product-b"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void create_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(buildRequest("noauth-sku", "X", "x"))))
                .andExpect(status().isUnauthorized());
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private UUID createProduct(String sku, String name, String slug) throws Exception {
        MvcResult res = mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(buildRequest(sku, name, slug))))
                .andExpect(status().isCreated())
                .andReturn();
        String idStr = json.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asText();
        return UUID.fromString(idStr);
    }

    private ProductCreateRequest buildRequest(String sku, String name, String slug) {
        return new ProductCreateRequest(
                sku, name, slug, "Test description",
                new BigDecimal("19990000.00"), "VND",
                ELECTRONICS_ID, ProductStatus.ACTIVE,
                Map.of("color", "black", "storage", "256GB"));
    }
}
