package com.ecommerce.payment.gateway;

/**
 * Kết quả verify giao dịch với gateway. 3 trạng thái:
 * <ul>
 *   <li>{@code SUCCESS} — gateway confirm txn đúng tồn tại + captured.</li>
 *   <li>{@code FAILED}  — gateway báo txn fail/expired.</li>
 *   <li>{@code UNKNOWN} — gateway không reachable (CB open / timeout / 5xx) →
 *       fallback path; reconciliation Day 36 sẽ verify lại async.</li>
 * </ul>
 *
 * <p>KHÔNG dùng exception cho UNKNOWN — exception là transport-level error,
 * UNKNOWN là business outcome có-chủ-đích sau fallback.
 */
public record VerificationResult(Status status, String providerTxnId, String reason) {

    public enum Status { SUCCESS, FAILED, UNKNOWN }

    public static VerificationResult success(String providerTxnId) {
        return new VerificationResult(Status.SUCCESS, providerTxnId, null);
    }

    public static VerificationResult failed(String providerTxnId, String reason) {
        return new VerificationResult(Status.FAILED, providerTxnId, reason);
    }

    public static VerificationResult unknown(String providerTxnId, String reason) {
        return new VerificationResult(Status.UNKNOWN, providerTxnId, reason);
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isUnknown() { return status == Status.UNKNOWN; }
}
