package com.ecom.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Catalog tập trung các mã lỗi business + system của toàn platform.
 *
 * <p>Quy ước đặt tên: {@code <DOMAIN>_<EVENT>}.
 *   Ví dụ: ORDER_NOT_FOUND, PAYMENT_ALREADY_PROCESSED, STOCK_INSUFFICIENT.
 *
 * <p>Một mã lỗi gồm:
 * <ul>
 *   <li>{@code httpStatus}: status code trả ra ngoài (400/404/409/500).</li>
 *   <li>{@code defaultMessage}: fallback khi không truyền message override.</li>
 * </ul>
 *
 * <p>Nguyên tắc Senior:
 * <ul>
 *   <li>Đừng dùng 500 cho lỗi business — đó là 4xx.</li>
 *   <li>Đừng dùng 404 cho lỗi authorization — phải 403.</li>
 *   <li>Conflict (409) chỉ dùng khi state hiện tại của resource không cho
 *       phép action (vd: order đã paid, user đã exist).</li>
 * </ul>
 */
public enum ErrorCode {

    // ─── Generic / System ──────────────────────────────────────────
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict with current state"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),

    // ─── Auth ──────────────────────────────────────────────────────
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token expired"),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token invalid"),
    AUTH_USER_EXISTS(HttpStatus.CONFLICT, "User already exists"),

    // ─── Product ───────────────────────────────────────────────────
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Product not found"),
    PRODUCT_INACTIVE(HttpStatus.CONFLICT, "Product is inactive"),

    // ─── Inventory ─────────────────────────────────────────────────
    STOCK_INSUFFICIENT(HttpStatus.CONFLICT, "Insufficient stock"),
    STOCK_RESERVATION_EXPIRED(HttpStatus.CONFLICT, "Stock reservation expired"),

    // ─── Cart ──────────────────────────────────────────────────────
    CART_EMPTY(HttpStatus.BAD_REQUEST, "Cart is empty"),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "Cart item not found"),

    // ─── Order ─────────────────────────────────────────────────────
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    ORDER_INVALID_STATE(HttpStatus.CONFLICT, "Order state does not allow this action"),
    ORDER_DUPLICATE_REQUEST(HttpStatus.CONFLICT, "Duplicate order request"),

    // ─── Payment ───────────────────────────────────────────────────
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "Payment already processed"),
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "Payment gateway error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() { return httpStatus; }
    public String defaultMessage() { return defaultMessage; }
}
