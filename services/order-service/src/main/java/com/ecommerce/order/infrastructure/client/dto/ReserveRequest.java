package com.ecommerce.order.infrastructure.client.dto;

/** Payload gửi inventory-service /inventory/reserve. */
public record ReserveRequest(String sku, int qty) {}
