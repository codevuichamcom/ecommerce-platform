package com.ecommerce.payment.interfaces.rest.dto;

import com.ecommerce.payment.domain.PaymentIntent;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        BigDecimal amount,
        String currency,
        String status,
        String provider,
        String providerTxnId,
        String failureReason
) {
    public static PaymentResponse from(PaymentIntent intent) {
        return new PaymentResponse(
                intent.getId(),
                intent.getOrderId(),
                intent.getAmount(),
                intent.getCurrency(),
                intent.getStatus().name(),
                intent.getProvider(),
                intent.getProviderTxnId(),
                intent.getFailureReason());
    }
}
