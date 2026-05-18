package com.ecom.product.web.dto;

import java.math.BigDecimal;

/**
 * Snapshot payload trả về cho order-service khi đặt order — minimum
 * field cần thiết để capture giá tại thời điểm checkout (chống price
 * drift). Tách record riêng với {@code ProductResponse} để KHÔNG kéo
 * theo {@code attributes}, {@code description}, {@code category} —
 * data dư = network overhead × N item × 1M order/day.
 *
 * <p>v1 schema. Order-service phía consumer dùng cùng record name
 * {@code ProductSnapshotV1}.
 */
public record ProductSnapshotResponse(
        String sku,
        String name,
        BigDecimal price,
        String currency
) {}
