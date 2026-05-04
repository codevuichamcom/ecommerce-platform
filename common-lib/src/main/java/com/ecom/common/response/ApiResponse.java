package com.ecom.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standard envelope cho TẤT CẢ HTTP response của platform.
 *
 * <p>Quy ước:
 * <ul>
 *   <li>Success: {@code success=true, data=<T>, error=null}</li>
 *   <li>Error:   {@code success=false, data=null, error=<ApiError>}</li>
 * </ul>
 *
 * <p>Lý do dùng envelope thay vì raw body:
 * <ul>
 *   <li>Frontend chỉ cần 1 axios interceptor là handle được mọi error.</li>
 *   <li>Backward compatible: thêm field meta (pagination, traceId) không
 *       phá schema cũ.</li>
 *   <li>Giúp gateway/log dễ phân loại response success/error.</li>
 * </ul>
 *
 * <p>Trade-off: client phải unwrap {@code .data}. Chấp nhận, vì tính
 * nhất quán mạnh hơn so với "RESTful naked body".
 *
 * @param <T> kiểu payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        String traceId,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(true, data, null, traceId, Instant.now());
    }

    public static <T> ApiResponse<T> error(ApiError error, String traceId) {
        return new ApiResponse<>(false, null, error, traceId, Instant.now());
    }

    /**
     * Cấu trúc lỗi chi tiết.
     *
     * @param code     mã lỗi domain (vd ORDER_NOT_FOUND) — frontend dùng
     *                 cho i18n thay vì hard-code message.
     * @param message  human-readable, có thể đã i18n từ server.
     * @param details  chi tiết theo field — thường dùng cho validation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(
            String code,
            String message,
            List<FieldError> details
    ) {}

    public record FieldError(String field, String message) {}
}
