package com.ecom.cart.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param cartKey   Redis key (debug only, không expose ra public client thật)
 * @param items     list ổn định sort theo SKU để response deterministic
 * @param totalQty  sum qty mọi item — convenience cho header badge
 * @param expiresAt epoch second nếu key có TTL, null nếu không
 */
public record CartResponse(
        String cartKey,
        List<CartItemResponse> items,
        int totalQty,
        Instant expiresAt) {}
