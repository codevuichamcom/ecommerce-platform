package com.ecommerce.order.application;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.domain.OrderStatus;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public Order cancel(UUID orderId, UUID requesterUserId, boolean isAdmin, String reason) {
        Order order = get(orderId, requesterUserId, isAdmin);
        order.transitionTo(new OrderStatus.Cancelled(reason, Instant.now()));
        return orderRepository.save(order);
        // Day 9: OrderCancelled domain event → inventory.release async.
    }
}
