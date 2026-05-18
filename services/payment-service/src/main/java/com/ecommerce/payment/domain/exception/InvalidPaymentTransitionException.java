package com.ecommerce.payment.domain.exception;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.payment.domain.PaymentStatus;

/**
 * State machine violation — vd: capture trên Initiated (chưa authorize),
 * fail trên Captured (terminal).
 */
public class InvalidPaymentTransitionException extends BusinessException {
    public InvalidPaymentTransitionException(PaymentStatus from, String action) {
        super(ErrorCode.ORDER_INVALID_STATE,
                "Cannot " + action + " from state " + from.name());
    }
}
