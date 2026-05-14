package com.ecommerce.order.domain.exception;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;

/**
 * Throw khi placeOrder() nhận cart không có item nào. 400, không 5xx.
 */
public class EmptyCartException extends BusinessException {
    public EmptyCartException() {
        super(ErrorCode.CART_EMPTY, "Cannot place order with empty cart");
    }
}
