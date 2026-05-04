package com.ecom.common.exception;

/**
 * Exception cho lỗi business logic (vi phạm rule nghiệp vụ).
 * Ví dụ: stock không đủ, order đã paid không cancel được, payment đã callback rồi.
 */
public class BusinessException extends BaseException {

    private static final long serialVersionUID = 1L;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
