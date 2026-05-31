package com.ecommerce.order.application;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.response.PageResponse;
import com.ecommerce.order.application.dto.OrderSummaryView;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side + cancel command. Tách khỏi PlaceOrderUseCase vì write/read
 * có policy khác nhau (read-only tx, no cross-service call).
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public Order get(UUID orderId, UUID requesterUserId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "Order not found: " + orderId));
        if (!isAdmin && !order.getUserId().equals(requesterUserId)) {
            // 404 cố ý — không leak existence cho user khác.
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Order not found: " + orderId);
        }
        return order;
    }

    /**
     * Day 17: list "Đơn hàng của tôi" — read-side projection, KHÔNG load
     * aggregate. Dùng {@link OrderRepository#findSummariesByUserId} (nấc 3)
     * thay vì {@code findByUserId} (nấc 0 N+1) hay {@code findWithItemsByUserId}
     * (nấc 1 in-memory pagination). Lý do chọn ở {@code docs/issues/17}.
     *
     * <p>Ownership: list LUÔN scope theo {@code requesterUserId} kể cả admin
     * — đây là "đơn của tôi". Admin xem đơn user khác đi qua path khác (chưa
     * build). Tránh leak đơn người khác qua list endpoint.
     *
     * <p>Sort whitelist {@code placedAt | totalAmount} (reuse thói quen Day 3)
     * + size cap 100 chống enumerate cả bảng.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryView> listMyOrders(UUID requesterUserId, int page, int size,
                                                       String sortBy, Sort.Direction direction) {
        String safeSort = switch (sortBy == null ? "placedAt" : sortBy) {
            case "totalAmount" -> "total.amount";
            default            -> "placedAt";
        };
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 100),
                Sort.by(direction == null ? Sort.Direction.DESC : direction, safeSort));

        Page<OrderSummaryView> result = orderRepository.findSummariesByUserId(requesterUserId, pageable);
        return PageResponse.from(result);
    }

    @Transactional
    public Order cancel(UUID orderId, UUID requesterUserId, boolean isAdmin, String reason) {
        Order order = get(orderId, requesterUserId, isAdmin);
        order.transitionTo(new OrderStatus.Cancelled(reason, Instant.now()));
        return orderRepository.save(order);
        // Day 9: OrderCancelled domain event → inventory.release async.
    }
}
