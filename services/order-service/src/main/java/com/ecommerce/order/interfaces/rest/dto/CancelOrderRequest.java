package com.ecommerce.order.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelOrderRequest(@NotBlank @Size(max = 255) String reason) {}
