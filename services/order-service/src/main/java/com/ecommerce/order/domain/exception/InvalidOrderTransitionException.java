package com.ecommerce.order.domain.exception;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.order.domain.OrderStatus;

/**
 * Throw khi caller cố transition Order qua state không hợp lệ (vd: Cancelled
 * → Paid, Delivered → Shipped). Map sang HTTP 409 qua ErrorCode.ORDER_INVALID_STATE.
 */
public class InvalidOrderTransitionException extends BusinessException {

    public InvalidOrderTransitionException(OrderStatus from, OrderStatus to) {
        super(ErrorCode.ORDER_INVALID_STATE,
                "Invalid transition: %s → %s".formatted(from.statusName(), to.statusName()));
    }
}
