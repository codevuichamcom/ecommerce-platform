package com.ecom.inventory.domain;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;

/**
 * Business exception — request reserve quá available.
 *
 * <p>Phân biệt với {@link IllegalStateException} (programming error trong
 * release/confirm): trường hợp đó là client gọi sai sequence, không phải
 * "user mua quá stock". GlobalExceptionHandler ở common-lib map exception
 * này → 409 Conflict.
 */
public class InsufficientStockException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public InsufficientStockException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
