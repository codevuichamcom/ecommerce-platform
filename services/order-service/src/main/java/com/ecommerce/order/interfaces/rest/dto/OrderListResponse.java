package com.ecommerce.order.interfaces.rest.dto;

import com.ecommerce.order.application.dto.OrderSummaryView;

import java.time.Instant;
import java.util.UUID;

/**
 * REST DTO cho 1 row trong list "Đơn hàng của tôi" ({@code GET /orders}).
 *
 * <p>Map 1-1 từ {@link OrderSummaryView} (read-side projection). Tách DTO
 * REST khỏi projection vì: projection là chi tiết persistence (có thể đổi
 * khi tối ưu query), còn DTO REST là contract với frontend — không để
 * thay đổi internal làm vỡ client.
 */
public record OrderListResponse(
        UUID id,
        String status,
        long totalAmount,
        String currency,
        String reservationStatus,
        int itemCount,
        Instant placedAt) {

    public static OrderListResponse from(OrderSummaryView v) {
        return new OrderListResponse(
                v.orderId(), v.statusType(), v.totalAmount(), v.currency(),
                v.reservationStatus(), (int) v.itemCount(), v.placedAt());
    }
}
