package com.ecom.analytics.report;

/**
 * 1 dòng report "top products": khoá sản phẩm + số lần xuất hiện trong
 * khoảng thời gian. {@code productKey} là productId (event beacon) hoặc sku
 * (event order) tuỳ {@code type} đang xếp hạng.
 */
public record TopProduct(String productKey, long count) {
}
