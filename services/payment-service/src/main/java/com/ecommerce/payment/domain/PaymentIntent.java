package com.ecommerce.payment.domain;

import com.ecom.common.audit.BaseEntity;
import com.ecommerce.payment.domain.exception.InvalidPaymentTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PaymentIntent — entity (Layered, KHÔNG Aggregate Root đầy đủ vì ADR-007:
 * payment-service không đủ 3-điểm DDD criteria).
 *
 * <p>Invariant enforce ở method:
 * <ol>
 *   <li>{@code amount ≥ 0} — guard ở factory + DB CHECK constraint.</li>
 *   <li>State transition tuân sealed {@link PaymentStatus} rule.</li>
 *   <li>Terminal state (CAPTURED/FAILED/EXPIRED) KHÔNG mutate được nữa
 *       — throw {@link InvalidPaymentTransitionException}.</li>
 *   <li>{@code providerTxnId} chỉ set 1 lần khi authorize/capture, không
 *       overwrite — chống nhầm gateway gửi txn_id khác nhau cho cùng
 *       PaymentIntent (đã thấy production gateway buggy).</li>
 * </ol>
 *
 * <p>Persistence: status lưu VARCHAR đơn — không cần JSONB như Order vì
 * PaymentStatus permits hiện không mang data riêng. Khi nào thêm data
 * (vd Refunded.refundId) thì migrate sang pattern 2-column như Order.
 *
 * <p>Optimistic lock qua {@code @Version} kế thừa từ {@link BaseEntity}.
 * Concurrent callback cùng providerTxnId → 99% bị chặn bởi UNIQUE
 * constraint ngay khi INSERT; nếu race ở UPDATE (INITIATED→CAPTURED) thì
 * @Version raise ObjectOptimisticLockingFailureException → caller (use
 * case) retry qua @Retryable.
 */
@Entity
@Table(name = "payment_intent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class PaymentIntent extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /** Sealed runtime view — reconstitute từ {@link #statusValue} ở @PostLoad. */
    @Transient
    private PaymentStatus status;

    @Column(name = "status", nullable = false, length = 16)
    private String statusValue;

    @Column(name = "provider", nullable = false, updatable = false, length = 32)
    private String provider;

    @Column(name = "provider_txn_id", length = 128)
    private String providerTxnId;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * Factory tạo PaymentIntent ở INITIATED. Caller (InitiatePaymentUseCase)
     * gọi sau khi validate order tồn tại + chưa có CAPTURED payment.
     */
    public static PaymentIntent initiate(UUID orderId,
                                         BigDecimal amount,
                                         String currency,
                                         String provider) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be ISO-4217 3-letter code");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        PaymentIntent p = new PaymentIntent();
        p.id = UUID.randomUUID();
        p.orderId = orderId;
        p.amount = amount;
        p.currency = currency;
        p.provider = provider;
        p.setStatus(new PaymentStatus.Initiated());
        return p;
    }

    /**
     * Gateway callback: pre-authorization pass (2-step flow). Day 10 mock
     * gateway 1-step nên ít dùng method này — Day 36 reconciliation sẽ wire.
     */
    public void authorize(String providerTxnId) {
        requireMutable("authorize");
        if (!(status instanceof PaymentStatus.Initiated)) {
            throw new InvalidPaymentTransitionException(status, "authorize");
        }
        bindProviderTxnId(providerTxnId);
        setStatus(new PaymentStatus.Authorized());
    }

    /**
     * Gateway callback: payment captured (funds settled / 1-step charged).
     * Accept transition từ INITIATED (1-step gateway như mock) hoặc
     * AUTHORIZED (2-step).
     *
     * <p>{@code providerTxnId} required — đây là correlation key dedup. Nếu
     * gateway gửi callback duplicate cùng txn_id → row đã CAPTURED →
     * use case nhận {@code DataIntegrityViolationException} từ UNIQUE
     * constraint → lookup existing → return idempotent response (NO double
     * publish event).
     */
    public void capture(String providerTxnId) {
        requireMutable("capture");
        boolean fromInitiated = status instanceof PaymentStatus.Initiated;
        boolean fromAuthorized = status instanceof PaymentStatus.Authorized;
        if (!fromInitiated && !fromAuthorized) {
            throw new InvalidPaymentTransitionException(status, "capture");
        }
        bindProviderTxnId(providerTxnId);
        setStatus(new PaymentStatus.Captured());
    }

    public void fail(String providerTxnId, String reason) {
        requireMutable("fail");
        if (status instanceof PaymentStatus.Captured) {
            throw new InvalidPaymentTransitionException(status, "fail");
        }
        if (providerTxnId != null) {
            bindProviderTxnId(providerTxnId);
        }
        this.failureReason = truncate(reason, 255);
        setStatus(new PaymentStatus.Failed());
    }

    public void markExpired() {
        requireMutable("expire");
        setStatus(new PaymentStatus.Expired());
    }

    /**
     * providerTxnId là immutable sau khi set lần đầu. Lý do: nếu gateway
     * buggy gửi 2 txn_id khác nhau cho cùng PaymentIntent → đây là DỮ LIỆU
     * KHÔNG NHẤT QUÁN ở phía gateway, phải fail-loud chứ không silent
     * overwrite (mất audit trail).
     */
    private void bindProviderTxnId(String txnId) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException("providerTxnId must not be blank");
        }
        if (this.providerTxnId != null && !this.providerTxnId.equals(txnId)) {
            throw new IllegalStateException(
                    "providerTxnId conflict: existing=" + this.providerTxnId + " new=" + txnId);
        }
        this.providerTxnId = txnId;
    }

    private void requireMutable(String action) {
        if (status != null && status.isTerminal()) {
            throw new InvalidPaymentTransitionException(status, action);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Setter package-private cho persistence reconstitute. */
    void setStatus(PaymentStatus next) {
        this.status = next;
        this.statusValue = next.name();
    }

    @PrePersist
    @PreUpdate
    void syncStatusToColumn() {
        if (status == null) {
            throw new IllegalStateException("PaymentIntent.status must not be null at persist time");
        }
        this.statusValue = status.name();
    }

    @PostLoad
    void reconstituteStatus() {
        this.status = PaymentStatus.fromDb(statusValue);
    }
}
