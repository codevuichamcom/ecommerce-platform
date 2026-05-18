package com.ecommerce.payment.domain;

/**
 * <h2>Sealed interface — PaymentIntent state machine</h2>
 *
 * <p>Tại sao sealed mà không phải enum? Khác với {@code OrderStatus} ở
 * order-service, mỗi PaymentStatus permit hiện tại KHÔNG mang data riêng
 * (failure reason, provider txn id lưu ở entity column, không phải trong
 * status). Vẫn dùng sealed vì:
 * <ul>
 *   <li><b>Exhaustive switch</b>: compiler enforce cover hết permit. Thêm
 *       state mới (vd {@code RefundInitiated}) → mọi switch chưa cover
 *       sẽ compile error. Enum thì chỉ có {@code @Exhaustive} flag chứ
 *       không có compile guard cứng.</li>
 *   <li><b>Pattern consistency với OrderStatus</b>: cùng team đọc code
 *       expect cùng pattern.</li>
 *   <li><b>Tương lai-proof</b>: nếu sau này thêm `Refunded(refundId)`
 *       cần data riêng → đã ở sealed, không phải refactor enum→sealed.</li>
 * </ul>
 *
 * <p>Transition rule (enforce ở {@link PaymentIntent#authorize},
 * {@link PaymentIntent#capture}, {@link PaymentIntent#fail},
 * {@link PaymentIntent#markExpired}):
 * <pre>
 *   INITIATED  ─► AUTHORIZED  (gateway 3DS pass / pre-auth success)
 *   INITIATED  ─► FAILED      (validation/3DS reject)
 *   INITIATED  ─► EXPIRED     (TTL 15min, Day 12 scheduler)
 *   AUTHORIZED ─► CAPTURED    (merchant capture — happy path)
 *   AUTHORIZED ─► FAILED      (capture rejected by issuer)
 *   AUTHORIZED ─► EXPIRED     (auth window 7d quá hạn không capture)
 *   CAPTURED   ─► (terminal)  — refund flow là entity riêng, không ở đây.
 *   FAILED     ─► (terminal)
 *   EXPIRED    ─► (terminal)
 * </pre>
 *
 * <p><b>Day 10 simplification</b>: mock gateway 1-step (skip AUTHORIZED,
 * callback trực tiếp CAPTURED hoặc FAILED). Production VNPay/Momo 2-step
 * (auth → capture) — code đã chuẩn bị transition AUTHORIZED, chỉ chưa wire
 * endpoint capture riêng. Day 36 reconciliation sẽ tách rõ.
 */
public sealed interface PaymentStatus
        permits PaymentStatus.Initiated,
                PaymentStatus.Authorized,
                PaymentStatus.Captured,
                PaymentStatus.Failed,
                PaymentStatus.Expired {

    String name();

    /**
     * Terminal: không transition nào hợp lệ nữa. Refund (Day 36) sẽ là
     * aggregate riêng `Refund` ref PaymentIntent, không phải thêm permit
     * `Refunded` vào enum này (Aggregate boundary discipline).
     */
    default boolean isTerminal() {
        return switch (this) {
            case Initiated i  -> false;
            case Authorized a -> false;
            case Captured c   -> true;
            case Failed f     -> true;
            case Expired e    -> true;
        };
    }

    /** Parse từ DB column. Hard-code mapping — KHÔNG dùng class.getSimpleName(). */
    static PaymentStatus fromDb(String value) {
        return switch (value) {
            case "INITIATED"  -> new Initiated();
            case "AUTHORIZED" -> new Authorized();
            case "CAPTURED"   -> new Captured();
            case "FAILED"     -> new Failed();
            case "EXPIRED"    -> new Expired();
            default -> throw new IllegalStateException("Unknown PaymentStatus in DB: " + value);
        };
    }

    record Initiated()  implements PaymentStatus { @Override public String name() { return "INITIATED";  } }
    record Authorized() implements PaymentStatus { @Override public String name() { return "AUTHORIZED"; } }
    record Captured()   implements PaymentStatus { @Override public String name() { return "CAPTURED";   } }
    record Failed()     implements PaymentStatus { @Override public String name() { return "FAILED";     } }
    record Expired()    implements PaymentStatus { @Override public String name() { return "EXPIRED";    } }
}
