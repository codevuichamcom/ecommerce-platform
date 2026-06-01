package com.ecom.product.web;

import com.ecom.common.response.ApiResponse;
import com.ecom.product.domain.ProductStatus;
import com.ecom.product.repository.ProductRepository;
import com.ecom.product.search.ProductSearchRepository;
import com.ecom.product.search.ReindexService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Day 22 — admin ops cho search index. ADMIN-only (reindex tốn tài nguyên +
 * lộ drift là info nội bộ).
 *
 * <ul>
 *   <li>{@code POST /admin/search/reindex} — full reindex Postgres → ES.
 *       Dùng initial load + reconcile drift + nạp benchmark dataset.</li>
 *   <li>{@code GET /admin/search/drift} — đếm ACTIVE products (Postgres) vs
 *       docs (ES). Lệch = drift. Đây là metric Day 25 sẽ alert + trigger
 *       reindex tự động. Hiện expose tay để demo + debug.</li>
 * </ul>
 *
 * <p>⚠️ Reindex đồng bộ (block tới khi xong) — OK cho catalog ~50k. Scale
 * 1M+ nên đẩy async (job + progress endpoint). TODO Day 25: chuyển
 * {@code @Async} + trả jobId.
 */
@RestController
@RequestMapping("/admin/search")
@RequiredArgsConstructor
public class AdminSearchController {

    private final ReindexService reindexService;
    private final ProductRepository productRepository;
    private final ProductSearchRepository searchRepository;

    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> reindex() {
        long indexed = reindexService.reindexAll();
        return ApiResponse.ok(Map.of("indexed", indexed));
    }

    @GetMapping("/drift")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> drift() {
        long pgActive = productRepository.countByStatus(ProductStatus.ACTIVE);
        long esDocs = searchRepository.count();
        return ApiResponse.ok(Map.of(
                "postgresActive", pgActive,
                "elasticsearchDocs", esDocs,
                "drift", pgActive - esDocs));
    }
}
