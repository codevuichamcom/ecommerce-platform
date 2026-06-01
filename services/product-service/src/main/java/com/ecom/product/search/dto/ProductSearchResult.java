package com.ecom.product.search.dto;

import java.util.List;
import java.util.Map;

/**
 * Day 22 — kết quả search ES đầy đủ: hits (đã sort theo relevance) + facets
 * (aggregation count theo brand / category) + total.
 *
 * <p>{@code total} ở ES là "số doc match" — KHÔNG đắt như Postgres COUNT(*)
 * vì ES tính sẵn trong cùng query (inverted index biết doc frequency). Đây
 * là 1 lý do search-heavy workload thích ES: faceted count + total "free"
 * trong 1 round-trip, Postgres phải N+1 query GROUP BY.
 *
 * <p>{@code facets}: key = tên facet ("brand" / "category"), value = list
 * bucket. {@code source} = "elasticsearch" | "postgres-fallback" để client
 * (và observability) biết kết quả tới từ đâu khi ES degrade.
 */
public record ProductSearchResult(
        List<SearchHitResponse> hits,
        long total,
        int page,
        int size,
        Map<String, List<FacetBucket>> facets,
        String source
) {}
