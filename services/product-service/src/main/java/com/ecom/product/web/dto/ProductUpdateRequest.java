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

/**
 * Day 3 dùng PUT semantics — full replace. Day-3 chưa support PATCH partial
 * update (sẽ thêm khi cần — premature giờ).
 *
 * <p>SKU không cho đổi: SKU là natural key, đổi sẽ phá invoice/order cũ.
 * Muốn đổi thì archive product → tạo product mới.
 */
public record ProductUpdateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
        @Size(max = 5000) String description,
        @NotNull @DecimalMin("0") BigDecimal price,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull UUID categoryId,
        @NotNull ProductStatus status,
        Map<String, Object> attributes
) {}
