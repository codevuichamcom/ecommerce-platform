package com.ecom.cart.web.dto;

import jakarta.validation.constraints.Min;

/**
 * qty=0 hợp lệ → coi như xóa item (UX phổ biến: kéo qty về 0). Service
 * tự normalize → HDEL field.
 */
public record UpdateItemRequest(@Min(0) int qty) {}
