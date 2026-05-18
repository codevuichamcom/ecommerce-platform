package com.ecommerce.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Input cho {@link InitiatePaymentUseCase}. Record vì immutable +
 * tự cho equals/hashCode (test fixture so sánh dễ).
 */
public record InitiatePaymentCommand(
        UUID orderId,
        BigDecimal amount,
        String currency,
        String provider
) {}
