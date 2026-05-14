package com.ecommerce.order.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event — Order chuyển sang Cancelled. Consumer ở Day 9 (mock):
 * inventory-service nhận để release reservation; notification-service gửi
 * email "đơn hàng đã hủy".
 */
public record OrderCancelled(UUID orderId, UUID userId, String reason,
                              Instant occurredAt) {}
