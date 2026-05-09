package com.ecom.cart.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddItemRequest(
        @NotBlank @Size(max = 64) String sku,
        @Min(1) int qty) {}
