package com.ecommerce.payment.interfaces.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record InitiatePaymentRequest(
        @NotNull   UUID orderId,
        @NotNull   @DecimalMin(value = "0.00", inclusive = true) BigDecimal amount,
        @NotBlank  @Size(min = 3, max = 3) String currency,
        @NotBlank  @Size(max = 32) String provider
) {}
