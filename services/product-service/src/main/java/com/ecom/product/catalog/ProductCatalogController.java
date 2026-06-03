package com.ecom.product.catalog;

import com.ecom.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Day 23 — catalog read API (Mongo-backed). Public read (giống GET product).
 *
 * <p>Tách prefix {@code /catalog} để rõ "đây là read-model Mongo", khác
 * {@code /products} CRUD (Postgres source of truth) và {@code /products/search}
 * (ES). 3 surface, 3 store, mỗi cái 1 việc — đúng tinh thần polyglot có chủ ý.
 */
@RestController
@RequestMapping("/products/catalog")
@RequiredArgsConstructor
public class ProductCatalogController {

    private final ProductCatalogService catalogService;

    /** Trang chi tiết: full document + flexible attributes theo category. */
    @GetMapping("/{productId}")
    public ApiResponse<ProductCatalogDocument> detail(@PathVariable String productId) {
        ProductCatalogDocument doc = catalogService.getById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog document not found (chưa sync hoặc đã archive): " + productId));
        return ApiResponse.ok(doc);
    }

    /**
     * Filter động theo thuộc tính: {@code GET /products/catalog?categorySlug=tv&attr=resolution:4K}.
     * {@code attr} dạng {@code key:value}. Không truyền {@code attr} → liệt kê
     * cả category.
     */
    @GetMapping
    public ApiResponse<List<ProductCatalogDocument>> byCategory(
            @RequestParam String categorySlug,
            @RequestParam(required = false) String attr) {
        if (attr == null || attr.isBlank()) {
            return ApiResponse.ok(catalogService.byCategory(categorySlug));
        }
        int sep = attr.indexOf(':');
        if (sep <= 0 || sep == attr.length() - 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "attr phải dạng key:value (vd resolution:4K)");
        }
        String key = attr.substring(0, sep);
        String value = attr.substring(sep + 1);
        return ApiResponse.ok(catalogService.byCategoryAndAttribute(categorySlug, key, value));
    }
}
