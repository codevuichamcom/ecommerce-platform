package com.ecom.common.exception;

import lombok.Getter;

/**
 * Gốc của hệ exception domain. Cứ là exception "có chủ ý" (lỗi nghiệp vụ
 * mong đợi, không phải bug) đều extends class này.
 *
 * <p>So sánh với Java {@code RuntimeException} thường:
 * <ul>
 *   <li>Mang theo {@link ErrorCode} → handler dịch ra HTTP status đúng.</li>
 *   <li>Mang theo message override + cause → log đầy đủ context.</li>
 * </ul>
 *
 * <p>Quy tắc dùng:
 * <ul>
 *   <li>Đừng throw {@code RuntimeException} ở tầng service. Throw subclass
 *       của {@code BaseException}.</li>
 *   <li>Đừng catch rồi rethrow {@code Exception} chung — mất stack trace.</li>
 * </ul>
 */
@Getter
public class BaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BaseException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BaseException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BaseException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
