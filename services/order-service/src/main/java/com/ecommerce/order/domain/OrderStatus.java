package com.ecommerce.order.domain;

import java.time.Instant;

/**
 * <h2>Sealed interface — Order lifecycle state machine</h2>
 *
 * <p>Tại sao sealed thay vì {@code enum}?
 * <ul>
 *   <li>Mỗi state mang <b>data khác nhau</b>: Cancelled cần {@code reason},
 *       Shipped cần {@code trackingNumber}, Delivered cần {@code deliveredAt}.
 *       Enum mà nhồi nullable field → nullable hell.</li>
 *   <li>Exhaustive {@code switch} pattern matching (JEP 441): compiler ép
 *       cover hết permit. Thêm permit mới → mọi switch chưa cover sẽ
 *       <b>compile error</b> — đây là free safety net.</li>
 *   <li>Nếu chỉ là label (PENDING/PAID/SHIPPED) không có data → dùng enum
 *       gọn hơn. Đây không phải case đó.</li>
 * </ul>
 *
 * <p>Transition rule (enforce ở {@link Order#transitionTo}):
 * <pre>
 *   PendingPayment ─► Paid       (payment confirmed)
 *   PendingPayment ─► Cancelled  (user cancel / timeout)
 *   Paid           ─► Shipped    (fulfillment dispatch)
 *   Paid           ─► Cancelled  (refund flow)
 *   Shipped        ─► Delivered  (courier confirm)
 *   Delivered      ─► (terminal)
 *   Cancelled      ─► (terminal)
 * </pre>
 *
 * <p>{@code statusName()} = stable string lưu DB (column status_type).
 * KHÔNG dùng {@code getClass().getSimpleName()} ở persist code — refactor
 * rename class sẽ break data. Hard-code ở method này là intentional.
 */
public sealed interface OrderStatus
        permits OrderStatus.PendingPayment,
                OrderStatus.Paid,
                OrderStatus.Shipped,
                OrderStatus.Delivered,
                OrderStatus.Cancelled {

    String statusName();

    /**
     * Terminal state: không còn transition nào hợp lệ. Dùng để guard
     * cancel/update từ ngoài (vd: user gọi cancel order đã Delivered).
     */
    default boolean isTerminal() {
        // Exhaustive switch — JEP 441. Compiler kill nếu thêm permit mới.
        return switch (this) {
            case PendingPayment p -> false;
            case Paid p           -> false;
            case Shipped s        -> false;
            case Delivered d      -> true;
            case Cancelled c      -> true;
        };
    }

    record PendingPayment() implements OrderStatus {
        @Override public String statusName() { return "PendingPayment"; }
    }

    record Paid(Instant paidAt) implements OrderStatus {
        @Override public String statusName() { return "Paid"; }
    }

    record Shipped(String trackingNumber, Instant shippedAt) implements OrderStatus {
        @Override public String statusName() { return "Shipped"; }
    }

    record Delivered(Instant deliveredAt) implements OrderStatus {
        @Override public String statusName() { return "Delivered"; }
    }

    record Cancelled(String reason, Instant cancelledAt) implements OrderStatus {
        @Override public String statusName() { return "Cancelled"; }
    }
}
