package com.ecom.product.web;

import com.ecom.common.response.ApiResponse;
import com.ecom.common.response.PageResponse;
import com.ecom.product.domain.ProductStatus;
import com.ecom.product.search.ProductSearchService;
import com.ecom.product.search.dto.FacetBucket;
import com.ecom.product.search.dto.ProductSearchResult;
import com.ecom.product.search.dto.SearchHitResponse;
import com.ecom.product.service.ProductService;
import com.ecom.product.web.dto.ProductResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Day 22 — endpoint search ES, thay {@code GET /products} (LIKE/GIN) khi cần
 * relevance + fuzzy + facet. Header {@code X-Search-Source} cho client +
 * observability biết kết quả tới từ ES hay Postgres-fallback.
 *
 * <p><b>Graceful degradation</b>: ES là external dependency có thể down (OOM,
 * network partition, GC pause). Search KHÔNG được 500 — fallback Postgres
 * {@code LIKE} (chậm hơn, không relevance/facet, nhưng còn trả kết quả). Đây
 * là lý do giữ GIN index Day 16 thay vì xóa sau khi có ES.
 *
 * <p><b>⚠️ Catch-all có chủ ý — KHÁC trap [05]</b>: review trap [05] là về
 * consumer NUỐT exception rồi mất event. Ở ĐÂY catch broad để DEGRADE 1
 * read-only non-critical path (search), có log WARN + header báo nguồn +
 * fallback thật sự phục vụ user. Không nuốt im lặng. Read path degrade ≠
 * write path swallow.
 */
@Slf4j
@RestController
@RequestMapping("/products/search")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductSearchService searchService;
    private final ProductService productService;

    @GetMapping
    public ResponseEntity<ApiResponse<ProductSearchResult>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) BigDecimal priceMin,
            @RequestParam(required = false) BigDecimal priceMax,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ProductSearchResult result;
        try {
            result = searchService.search(q, categoryId, brand, priceMin, priceMax, page, size);
        } catch (DataAccessException ex) {
            // ES down / unreachable → Spring Data ES dịch lỗi connection sang
            // DataAccessResourceFailureException (con của DataAccessException).
            // Degrade sang Postgres LIKE — search vẫn sống, không 500.
            log.warn("ES search failed, fallback Postgres LIKE. q='{}' reason={}", q, ex.getMessage());
            result = postgresFallback(q, categoryId, page, size);
        }
        return ResponseEntity.ok()
                .header("X-Search-Source", result.source())
                .body(ApiResponse.ok(result));
    }

    /**
     * Fallback dùng {@link ProductService#search} (Postgres LIKE/GIN). Mất
     * relevance score (đặt 0), mất highlight + facet (empty) — đánh đổi để
     * search còn trả kết quả khi ES chết. brand/price filter KHÔNG áp ở
     * fallback (Postgres path Day 16 chưa hỗ trợ) — degrade chấp nhận được.
     */
    private ProductSearchResult postgresFallback(String q, String categoryId, int page, int size) {
        UUID catUuid = parseUuidOrNull(categoryId);
        PageResponse<ProductResponse> pg = productService.search(
                q, catUuid, ProductStatus.ACTIVE, page, size, "createdAt", Sort.Direction.DESC);
        List<SearchHitResponse> hits = pg.items().stream()
                .map(p -> new SearchHitResponse(
                        p.id().toString(), p.sku(), p.name(),
                        p.attributes() == null ? null : asString(p.attributes().get("brand")),
                        p.price(), p.currency(), p.categorySlug(),
                        0.0, List.of()))
                .toList();
        Map<String, List<FacetBucket>> emptyFacets = Map.of("brand", List.of(), "category", List.of());
        return new ProductSearchResult(hits, pg.total(), page, size, emptyFacets, "postgres-fallback");
    }

    private static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
