package com.ecommerce.order.infrastructure.persistence;

import com.ecommerce.order.domain.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;

/**
 * Serialize sealed {@link OrderStatus} ↔ 2 column (status_type +
 * status_data JSONB).
 *
 * <p>Tại sao tách module này thay vì để Hibernate AttributeConverter map
 * thẳng sealed → 1 column?
 * <ul>
 *   <li>Sealed có data khác nhau per permit. AttributeConverter chỉ map
 *       1 attribute → 1 column. Cần 2 column (type + data) → cần 2
 *       converter song song, hoặc 1 converter trên record wrapper. Phức
 *       tạp hơn 1 static util.</li>
 *   <li>JPA lifecycle callback ({@code @PostLoad}/{@code @PrePersist}) gọi
 *       static method là pattern simple + test được. Không cần Spring DI.</li>
 * </ul>
 *
 * <p>Exhaustive switch ép compile error nếu thêm permit mới — chính là
 * lợi thế sealed mà skill 06b nhấn mạnh.
 */
public final class OrderStatusSerializer {

    // ObjectMapper là thread-safe sau cấu hình. Reuse singleton tránh GC churn.
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private OrderStatusSerializer() {}

    public static String toJson(OrderStatus status) {
        ObjectNode node = MAPPER.createObjectNode();
        // Exhaustive — JEP 441. Thêm permit mới phải update method này, compiler báo.
        switch (status) {
            case OrderStatus.PendingPayment p -> { /* no extra data */ }
            case OrderStatus.Paid p           -> node.put("paidAt", p.paidAt().toString());
            case OrderStatus.Shipped s        -> {
                node.put("trackingNumber", s.trackingNumber());
                node.put("shippedAt", s.shippedAt().toString());
            }
            case OrderStatus.Delivered d      -> node.put("deliveredAt", d.deliveredAt().toString());
            case OrderStatus.Cancelled c      -> {
                node.put("reason", c.reason());
                node.put("cancelledAt", c.cancelledAt().toString());
            }
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // Programming error — node là ObjectNode tự tạo, không thể fail.
            throw new IllegalStateException("Failed to serialize OrderStatus: " + status, e);
        }
    }

    public static OrderStatus fromDb(String type, String json) {
        try {
            ObjectNode node = json == null || json.isBlank()
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(json);
            return switch (type) {
                case "PendingPayment" -> new OrderStatus.PendingPayment();
                case "Paid"           -> new OrderStatus.Paid(Instant.parse(node.get("paidAt").asText()));
                case "Shipped"        -> new OrderStatus.Shipped(
                        node.get("trackingNumber").asText(),
                        Instant.parse(node.get("shippedAt").asText()));
                case "Delivered"      -> new OrderStatus.Delivered(Instant.parse(node.get("deliveredAt").asText()));
                case "Cancelled"      -> new OrderStatus.Cancelled(
                        node.get("reason").asText(),
                        Instant.parse(node.get("cancelledAt").asText()));
                default -> throw new IllegalStateException("Unknown status_type in DB: " + type);
            };
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to deserialize OrderStatus type=%s json=%s".formatted(type, json), e);
        }
    }
}
