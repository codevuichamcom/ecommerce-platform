package com.ecom.product.search.dto;

/**
 * Day 22 — 1 bucket trong faceted aggregation. Vd facet "brand":
 * {@code [{Apple, 42}, {Samsung, 31}, {Xiaomi, 12}]} → UI render checkbox
 * filter kèm count. Đây là thứ Postgres GIN search KHÔNG làm real-time được
 * (phải GROUP BY riêng, không cùng query với search).
 */
public record FacetBucket(String key, long count) {}
