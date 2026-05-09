package com.ecom.cart.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Cart-specific tunables. Hard-cap chống abuse: bot thêm 1M item / 1 SKU
 * sẽ làm vỡ Redis memory budget; cap mỗi field + cap số field/cart.
 */
@Validated
@ConfigurationProperties(prefix = "cart")
public record CartProperties(
        @NotNull Duration ttl,
        @Min(1) int maxQtyPerItem,
        @Min(1) int maxItemsPerCart) {}
