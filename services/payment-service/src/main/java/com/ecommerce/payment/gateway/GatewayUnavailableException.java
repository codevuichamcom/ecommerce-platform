package com.ecommerce.payment.gateway;

/**
 * Throw khi outbound call tới payment gateway fail (network, 5xx, timeout).
 * Resilience4j CircuitBreaker count exception này vào failure rate.
 * Subclass {@link RuntimeException} để KHÔNG ép caller declare throws — call site
 * là use-case layer dùng try-catch + fallback rõ ràng.
 */
public class GatewayUnavailableException extends RuntimeException {
    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
