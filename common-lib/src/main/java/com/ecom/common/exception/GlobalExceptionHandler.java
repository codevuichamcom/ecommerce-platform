package com.ecom.common.exception;

import com.ecom.common.response.ApiResponse;
import com.ecom.common.response.ApiResponse.ApiError;
import com.ecom.common.response.ApiResponse.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Global exception handler dùng chung cho mọi service.
 *
 * <p>Triết lý:
 * <ul>
 *   <li>KHÔNG bao giờ leak stack trace cho client.</li>
 *   <li>Lỗi business → log {@code WARN}, không phải {@code ERROR}
 *       (tránh đổ chuông on-call vì lý do nghiệp vụ).</li>
 *   <li>Lỗi system (5xx) → log {@code ERROR} kèm stack trace, alert được.</li>
 *   <li>Mọi response đều bọc qua {@link ApiResponse} envelope.</li>
 * </ul>
 *
 * <p>Class này được auto-register qua {@code CommonAutoConfiguration}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";

    // ─── Domain ────────────────────────────────────────────────────

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBase(BaseException ex, HttpServletRequest req) {
        log.warn("[{}] BusinessException at {}: code={}, msg={}",
                traceId(), req.getRequestURI(), ex.getErrorCode(), ex.getMessage());
        return build(ex.getErrorCode(), ex.getMessage(), null);
    }

    // ─── Validation: @Valid trên @RequestBody ──────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(),
                        f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Request body validation failed", details);
    }

    // ─── Validation: @Validated trên @RequestParam / @PathVariable ─

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParamValidation(ConstraintViolationException ex) {
        List<FieldError> details = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "Parameter validation failed", details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.BAD_REQUEST,
                "Parameter '%s' has invalid value '%s'".formatted(ex.getName(), ex.getValue()),
                null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        return build(ErrorCode.BAD_REQUEST, "Malformed JSON request", null);
    }

    // ─── Security ──────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, ex.getMessage(), null);
    }

    // ─── Fallback ──────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAny(Exception ex, HttpServletRequest req) {
        // ERROR — đây mới là chỗ on-call cần biết.
        log.error("[{}] Unhandled exception at {}: {}",
                traceId(), req.getRequestURI(), ex.getMessage(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "Unexpected error", null);
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private ResponseEntity<ApiResponse<Void>> build(ErrorCode code, String message, List<FieldError> details) {
        ApiError err = new ApiError(code.name(), message, details);
        ApiResponse<Void> body = ApiResponse.error(err, traceId());
        HttpStatus status = code.httpStatus();
        return ResponseEntity.status(status).body(body);
    }

    private String traceId() {
        return MDC.get(TRACE_ID_KEY);
    }
}
