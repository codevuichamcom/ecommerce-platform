package com.ecom.product.domain;

/**
 * Lifecycle của product.
 *
 * <ul>
 *   <li>{@code DRAFT}    — admin tạo, chưa publish. Không xuất hiện ở public list.</li>
 *   <li>{@code ACTIVE}   — published, có thể bán.</li>
 *   <li>{@code ARCHIVED} — ngừng bán nhưng vẫn giữ record cho historical
 *       order. KHÔNG xóa cứng — vì order/invoice cũ reference SKU này.</li>
 * </ul>
 *
 * <p>Day 6 (order-service) sẽ thêm transition rule: chỉ ACTIVE mới thêm
 * vào order; ARCHIVED không block existing order.
 */
public enum ProductStatus {
    DRAFT, ACTIVE, ARCHIVED
}
