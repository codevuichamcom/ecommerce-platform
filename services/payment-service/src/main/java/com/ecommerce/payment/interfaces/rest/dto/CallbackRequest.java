package com.ecommerce.payment.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Payload mock gateway gửi về callback endpoint.
 *
 * <p>Signature verify ở filter/use case dùng canonical string
 * {@code timestamp + "." + jsonBody} — vì vậy field order trong JSON ảnh
 * hưởng. Production: gateway document chính xác canonical format.
 */
public record CallbackRequest(
        @NotNull  UUID paymentId,
        @NotBlank @Size(max = 32)  String provider,
        @NotBlank @Size(max = 128) String providerTxnId,
        @NotBlank @Size(max = 16)  String outcome,        // SUCCESS / FAILED
                  @Size(max = 255) String failureReason
) {}
