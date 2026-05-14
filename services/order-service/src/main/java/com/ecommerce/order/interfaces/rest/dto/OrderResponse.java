package com.ecommerce.order.interfaces.rest.dto;

import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID userId,
        String status,
        StatusDataDto statusData,
        long totalAmount,
        String currency,
        List<ItemDto> items,
        Instant placedAt) {

    public record ItemDto(String sku, String productName, int quantity,
                          long unitPriceAmount, String unitPriceCurrency,
                          long subtotal) {}

    /**
     * Flatten status data thành 1 DTO — frontend dễ render hơn là phải
     * discriminate type trên client. KHÔNG leak sealed class structure
     * (REST layer concern, không phải domain).
     */
    public record StatusDataDto(String reason, Instant paidAt, Instant cancelledAt,
                                 Instant shippedAt, String trackingNumber,
                                 Instant deliveredAt) {}

    public static OrderResponse from(Order order) {
        OrderStatus status = order.getStatus();
        StatusDataDto data = switch (status) {
            case OrderStatus.PendingPayment p -> new StatusDataDto(null, null, null, null, null, null);
            case OrderStatus.Paid p           -> new StatusDataDto(null, p.paidAt(), null, null, null, null);
            case OrderStatus.Shipped s        -> new StatusDataDto(null, null, null, s.shippedAt(), s.trackingNumber(), null);
            case OrderStatus.Delivered d      -> new StatusDataDto(null, null, null, null, null, d.deliveredAt());
            case OrderStatus.Cancelled c      -> new StatusDataDto(c.reason(), null, c.cancelledAt(), null, null, null);
        };
        List<ItemDto> items = order.getItems().stream()
                .map(OrderResponse::toItemDto)
                .toList();
        return new OrderResponse(
                order.getId(), order.getUserId(), status.statusName(), data,
                order.getTotal().amount(), order.getTotal().currency(),
                items, order.getPlacedAt());
    }

    private static ItemDto toItemDto(OrderItem item) {
        return new ItemDto(item.getSku(), item.getProductName(), item.getQuantity(),
                item.getUnitPrice().amount(), item.getUnitPrice().currency(),
                item.subtotal().amount());
    }
}
