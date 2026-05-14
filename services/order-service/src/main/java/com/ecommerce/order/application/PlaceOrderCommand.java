package com.ecommerce.order.application;

import com.ecommerce.order.domain.Address;

import java.util.UUID;

/**
 * Input port — chi tiết cần thiết để place order. KHÔNG dùng REST DTO ở
 * application layer (tránh REST-specific field rò rỉ vào use case).
 *
 * @param idempotencyKey optional. Nếu trùng (userId, key) → return order cũ.
 */
public record PlaceOrderCommand(
        UUID userId,
        String bearerToken,
        Address shippingAddress,
        String currency,
        String idempotencyKey) {}
