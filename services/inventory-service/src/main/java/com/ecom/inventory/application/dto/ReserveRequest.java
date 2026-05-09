package com.ecom.inventory.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReserveRequest(
        @NotBlank @Size(max = 64) String sku,
        @Min(1) int qty
) {}
