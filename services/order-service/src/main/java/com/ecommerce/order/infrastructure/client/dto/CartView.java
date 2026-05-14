package com.ecommerce.order.infrastructure.client.dto;

import java.util.List;

/**
 * Read model returned by cart-service. Field tên trùng JSON cart-service
 * trả (xem {@code services/cart-service/.../CartResponse}).
 *
 * <p>{@code unitPriceAmount} là long cents (cart-service Day 5 chưa wire
 * price — Day 6 stub: order-service tự lookup price từ product-service
 * hoặc cart trả về). Vì product-service chưa expose batch price endpoint,
 * Day 6 dùng cart payload nếu có hoặc fallback unitPriceAmount=0 + log
 * warning. TODO Day 8: thêm product-service batch lookup.
 */
public record CartView(String cartId, List<CartItem> items) {

    public record CartItem(
            String sku,
            String productName,
            int quantity,
            long unitPriceAmount,
            String unitPriceCurrency) {}
}
