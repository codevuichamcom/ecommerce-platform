package com.ecommerce.order.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * <h2>Read-side projection — KHÔNG phải aggregate</h2>
 *
 * <p>Day 17 N+1 fix nấc 3. Đây là <b>view model</b> cho màn hình "Đơn hàng
 * của tôi": chỉ chứa cột list-screen cần, KHÔNG load {@link com.ecommerce.order.domain.Order}
 * entity và KHÔNG đi vào persistence context.
 *
 * <p>Vì sao tách record riêng thay vì trả {@code Order}?
 * <ul>
 *   <li><b>Tránh N+1</b>: load aggregate kéo theo collection {@code items}
 *       (EAGER) → 1 query list + N query items. Projection select thẳng
 *       scalar → 1 query, {@code itemCount} qua subquery {@code size()}.</li>
 *   <li><b>Nhẹ</b>: không dirty-checking, không snapshot trong
 *       persistence context — đỡ heap + đỡ CPU cho read path đọc nhiều.</li>
 *   <li><b>CQRS-lite</b>: read model tách write model. Màn list cần đếm
 *       item, không cần từng item; aggregate là cho write/detail path.</li>
 * </ul>
 *
 * <p>{@code totalAmount} là long cents (xem {@link com.ecommerce.order.domain.Money}).
 * {@code itemCount} dùng {@code long} vì {@code size(o.items)} dịch sang
 * subquery {@code COUNT(*)} — Hibernate trả về Long.
 *
 * @see com.ecommerce.order.domain.OrderRepository#findSummariesByUserId
 */
public record OrderSummaryView(
        UUID orderId,
        String statusType,
        long totalAmount,
        String currency,
        String reservationStatus,
        Instant placedAt,
        long itemCount) {
}
