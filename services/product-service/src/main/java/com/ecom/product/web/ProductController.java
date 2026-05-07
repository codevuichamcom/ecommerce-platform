package com.ecom.product.web;

import com.ecom.common.response.ApiResponse;
import com.ecom.common.response.PageResponse;
import com.ecom.product.domain.ProductStatus;
import com.ecom.product.service.ProductService;
import com.ecom.product.web.dto.ProductCreateRequest;
import com.ecom.product.web.dto.ProductResponse;
import com.ecom.product.web.dto.ProductUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public list/get endpoint không cần auth. Admin write protected bằng
 * {@code @PreAuthorize("hasRole('ADMIN')")} — JWT token phải có role=ADMIN.
 */
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<PageResponse<ProductResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        return ApiResponse.ok(productService.search(q, categoryId, status, page, size, sortBy, direction));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(productService.get(id));
    }

    @GetMapping("/slug/{slug}")
    public ApiResponse<ProductResponse> getBySlug(@PathVariable String slug) {
        return ApiResponse.ok(productService.getBySlug(slug));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductCreateRequest req) {
        return ApiResponse.ok(productService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProductResponse> update(@PathVariable UUID id,
                                               @Valid @RequestBody ProductUpdateRequest req) {
        return ApiResponse.ok(productService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void archive(@PathVariable UUID id) {
        // Soft archive — KHÔNG hard-delete. Xem comment ở ProductService#archive.
        productService.archive(id);
    }
}
