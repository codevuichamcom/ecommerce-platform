package com.ecom.product.web.dto;

import com.ecom.product.domain.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response không leak entity — flatten category sang categoryId + categorySlug
 * (đủ cho client; muốn full category gọi `/categories/{id}` riêng).
 */
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String slug,
        String description,
        BigDecimal price,
        String currency,
        UUID categoryId,
        String categorySlug,
        ProductStatus status,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant updatedAt
) {}
