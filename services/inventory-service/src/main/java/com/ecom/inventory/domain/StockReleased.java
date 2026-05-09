package com.ecom.inventory.domain;

import java.time.Instant;

/**
 * Domain event — phát ra khi {@link Stock#release(int)} thành công
 * (order cancel / payment fail).
 */
public record StockReleased(String sku, int qty, Instant occurredAt) {}
