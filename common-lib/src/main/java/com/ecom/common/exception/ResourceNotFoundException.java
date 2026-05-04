package com.ecom.common.exception;

/**
 * Exception chuyên cho "không tìm thấy resource" — map sang HTTP 404.
 *
 * <p>Tách riêng khỏi {@link BusinessException} để:
 * <ul>
 *   <li>Stack trace đọc rõ hơn.</li>
 *   <li>Có thể catch riêng ở filter/audit khi cần (vd: silent log 404 do
 *       crawler vs error thực sự).</li>
 * </ul>
 */
public class ResourceNotFoundException extends BaseException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND,
              "%s not found with id=%s".formatted(resource, id));
    }

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
