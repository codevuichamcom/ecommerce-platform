package com.ecom.product.web.dto;

import com.ecom.product.domain.ProductStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record ProductCreateRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "slug must be lowercase-dash format") String slug,
        @Size(max = 5000) String description,
        @NotNull @DecimalMin(value = "0", message = "price must be ≥ 0") BigDecimal price,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull UUID categoryId,
        @NotNull ProductStatus status,
        Map<String, Object> attributes
) {}
