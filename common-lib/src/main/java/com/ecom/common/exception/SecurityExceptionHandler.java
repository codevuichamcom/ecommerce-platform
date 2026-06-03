package com.ecom.common.exception;

import com.ecom.common.response.ApiResponse;
import com.ecom.common.response.ApiResponse.ApiError;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Day 23 — handler cho {@link AccessDeniedException} (403), TÁCH khỏi
 * {@link GlobalExceptionHandler} để KHÔNG ép mọi service web phải có
 * spring-security trên classpath.
 *
 * <p><b>Vì sao tách?</b> {@code @RestControllerAdvice} (= {@code @Component})
 * bị component-scan của MỌI service ({@code scanBasePackages} có
 * {@code com.ecom.common}). Khi Spring đăng ký advice, nó reflect toàn bộ
 * {@code @ExceptionHandler} method để build {@code ExceptionHandlerMethodResolver}
 * — cần resolve kiểu tham số. Nếu {@code AccessDeniedException} KHÔNG có trên
 * classpath (service không dùng spring-security, vd {@code analytics-service}),
 * resolve kiểu này ném {@code NoClassDefFoundError} → context load fail.
 *
 * <p><b>Cách chặn</b>: {@link ConditionalOnClass} ở cấp class. Spring đọc
 * annotation này qua ASM metadata (KHÔNG load class) trong lúc classpath-scan
 * → service không có spring-security thì candidate này bị LỌC ngay, class
 * {@code SecurityExceptionHandler} không bao giờ được load → không
 * {@code NoClassDefFoundError}. Service CÓ spring-security thì advice active
 * bình thường, trả 403 envelope chuẩn.
 *
 * <p>Dùng {@code name=} (string) thay vì {@code value=} (class literal) cho
 * {@code @ConditionalOnClass} — chắc chắn không có class reference nào phải
 * resolve lúc đánh giá điều kiện.
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        ApiError err = new ApiError(ErrorCode.FORBIDDEN.name(), ex.getMessage(), null);
        ApiResponse<Void> body = ApiResponse.error(err, MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
