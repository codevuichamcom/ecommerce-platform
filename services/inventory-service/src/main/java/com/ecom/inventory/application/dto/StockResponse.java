package com.ecom.inventory.application.dto;

import com.ecom.inventory.domain.Stock;

/**
 * Read-side projection — KHÔNG expose entity ra ngoài (chống entity leak,
 * xem [issue 03](docs/issues/03-entity-leak-in-response.md) Day 3).
 */
public record StockResponse(String sku, int quantity, int reserved, int available) {

    public static StockResponse from(Stock stock) {
        return new StockResponse(stock.getSku(), stock.getQuantity(), stock.getReserved(), stock.available());
    }
}
