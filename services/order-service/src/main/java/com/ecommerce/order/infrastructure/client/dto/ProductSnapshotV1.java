package com.ecommerce.order.infrastructure.client.dto;

import java.math.BigDecimal;

/**
 * Snapshot product tại thời điểm Order place — order-service capture
 * price + name vào order_items để TRÁNH PRICE DRIFT (admin đổi giá sau
 * khi user đã checkout không ảnh hưởng order cũ).
 *
 * <p>v1 schema. Field thêm sau phải có default (Jackson sẽ ignore unknown
 * field ở consumer cũ; field mới ở producer mới = null/default ở consumer cũ).
 */
public record ProductSnapshotV1(
        String sku,
        String name,
        BigDecimal price,
        String currency
) {}
