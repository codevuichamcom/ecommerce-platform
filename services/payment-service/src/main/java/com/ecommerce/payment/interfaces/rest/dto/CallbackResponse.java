package com.ecommerce.payment.interfaces.rest.dto;

import com.ecommerce.payment.application.CallbackResult;

import java.util.UUID;

/**
 * Trả về cho gateway. {@code duplicate=true} giúp gateway log "đã xử lý"
 * (tuy nhiên Day 10 spec không yêu cầu gateway phân biệt — gateway chỉ cần
 * 200 OK để dừng retry).
 */
public record CallbackResponse(
        UUID paymentId,
        String status,
        boolean duplicate
) {
    public static CallbackResponse from(CallbackResult result) {
        return new CallbackResponse(
                result.intent().getId(),
                result.intent().getStatus().name(),
                result.duplicate());
    }
}
