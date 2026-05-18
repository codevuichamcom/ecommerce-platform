package com.ecom.common.messaging;

/**
 * Single source of truth cho topic name. Hard-coded constant — KHÔNG dùng
 * property override vì topic name là contract giữa services (đổi 1 chỗ
 * = break consumer).
 *
 * <p>Naming convention hiện tại: {@code <aggregate>.<event-past-tense>}.
 * Scale rule: dưới 20 service flat naming OK; > 20 service nên migrate
 * sang {@code <bounded-context>.<aggregate>.<event>.<version>}. Phiên
 * bản v1 nằm trong payload ({@code DomainEvent#eventVersion}) — breaking
 * change sẽ tạo topic mới {@code order.created.v2} để dual-consume trong
 * migration window.
 */
public final class TopicNames {

    private TopicNames() {}

    public static final String ORDER_CREATED         = "order.created";
    public static final String ORDER_CANCELLED       = "order.cancelled";
    public static final String PAYMENT_COMPLETED     = "payment.completed";
    public static final String INVENTORY_RESERVED    = "inventory.reserved";
    public static final String NOTIFICATION_OUTGOING = "notification.outgoing";
}
