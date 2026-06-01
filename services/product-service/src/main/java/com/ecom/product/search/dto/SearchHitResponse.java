package com.ecom.product.search.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Day 22 — 1 hit trong kết quả search ES. Khác {@code ProductResponse}
 * (Postgres) ở 2 điểm:
 * <ul>
 *   <li>{@code score} — relevance BM25 do ES tính. Postgres LIKE không có
 *       khái niệm "độ liên quan" (chỉ match/không-match).</li>
 *   <li>{@code highlights} — đoạn text chứa từ khóa được bọc {@code <em>}.
 *       Frontend bôi vàng cho user thấy "match ở đâu".</li>
 * </ul>
 */
public record SearchHitResponse(
        String id,
        String sku,
        String name,
        String brand,
        BigDecimal price,
        String currency,
        String categorySlug,
        double score,
        List<String> highlights
) {}
