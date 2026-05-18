package com.ecommerce.payment.application;

import com.ecom.common.event.PaymentCompletedV1;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.payment.domain.PaymentIntent;
import com.ecommerce.payment.domain.PaymentStatus;
import com.ecommerce.payment.infrastructure.messaging.PaymentEventPublisher;
import com.ecommerce.payment.infrastructure.persistence.PaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * <h2>Idempotent payment callback handler</h2>
 *
 * <p>Đây là điểm dễ sai nhất Day 10. Có 3 mức idempotency cùng lúc:
 *
 * <ol>
 *   <li><b>DB UNIQUE(provider, provider_txn_id)</b> — atomic source of
 *       truth. Day 10 issue 10 chọn approach này. Race window giữa
 *       SELECT + UPDATE bị close bởi DB constraint, KHÔNG bởi app.</li>
 *   <li><b>Catch {@link DataIntegrityViolationException}</b> — khi 2
 *       thread cùng cố INSERT cùng providerTxnId → 1 thắng, 1 thua.
 *       Thread thua lookup existing row → return idempotent response
 *       (NO double publish event).</li>
 *   <li><b>Catch {@link ObjectOptimisticLockingFailureException}</b> —
 *       race ở UPDATE (vd 2 callback cùng paymentId vừa lookup vừa update
 *       trước khi UNIQUE kịp commit). {@code @Retryable} retry 3 lần
 *       exponential backoff — lần 2 sẽ thấy row đã CAPTURED → return
 *       duplicate.</li>
 * </ol>
 *
 * <p><b>Tại sao @Retryable + REQUIRES_NEW</b>:
 * <ul>
 *   <li>{@code @Retryable} chỉ work với proxy AOP — cần method gọi từ
 *       ngoài Spring bean. Đây là entry method (controller gọi) nên OK.</li>
 *   <li>{@code REQUIRES_NEW}: mỗi retry attempt 1 transaction mới — tránh
 *       case lazy load entity ở failed transaction bị "stuck" trong
 *       persistence context cũ.</li>
 *   <li>Backoff 50→500ms exponential: race window thường <50ms; nếu quá
 *       3 retry vẫn fail → gateway problem, trả 5xx để gateway retry chu
 *       kỳ sau.</li>
 * </ul>
 *
 * <p><b>Dual-write debt</b>: publish Kafka sau khi save DB — KHÔNG atomic.
 * Day 13 outbox sẽ fix: lưu event vào bảng `outbox_event` cùng transaction,
 * relay async. Day 10 chấp nhận log warn nếu publish fail — DB là source
 * of truth, reconciliation Day 36 sẽ catch mismatch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandleCallbackUseCase {

    private final PaymentIntentRepository repository;
    private final PaymentEventPublisher publisher;

    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CallbackResult execute(CallbackCommand cmd) {
        // 1) Idempotent fast-path: nếu providerTxnId đã xử lý → return ngay.
        // Đây là OPTIMIZATION, KHÔNG phải correctness — UNIQUE constraint vẫn
        // là final guard nếu race lọt qua check này.
        var existing = repository.findByProviderAndProviderTxnId(cmd.provider(), cmd.providerTxnId());
        if (existing.isPresent()) {
            PaymentIntent intent = existing.get();
            log.info("Callback duplicate ignored: paymentId={} providerTxnId={} status={}",
                    intent.getId(), cmd.providerTxnId(), intent.getStatus().name());
            return new CallbackResult(intent, true, false);
        }

        // 2) Lookup PaymentIntent INITIATED theo paymentId.
        PaymentIntent intent = repository.findById(cmd.paymentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "PaymentIntent not found: " + cmd.paymentId()));

        if (!intent.getProvider().equals(cmd.provider())) {
            // Defensive: callback gửi provider khác provider intent → bug ở client/test.
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Provider mismatch: intent=" + intent.getProvider() + " callback=" + cmd.provider());
        }

        // 3) Transition + save. UNIQUE constraint trên (provider, providerTxnId) sẽ throw
        //    DataIntegrityViolationException nếu 2 thread cùng INSERT cùng txn_id.
        try {
            return applyOutcome(intent, cmd);
        } catch (DataIntegrityViolationException dup) {
            // Lost race với thread khác — lookup lại + return idempotent.
            log.warn("UNIQUE constraint hit on callback paymentId={} providerTxnId={}; lookup duplicate",
                    cmd.paymentId(), cmd.providerTxnId());
            PaymentIntent duplicate = repository.findByProviderAndProviderTxnId(
                            cmd.provider(), cmd.providerTxnId())
                    .orElseThrow(() -> new IllegalStateException(
                            "UNIQUE violation but no row found — data race or constraint bug"));
            return new CallbackResult(duplicate, true, false);
        }
    }

    private CallbackResult applyOutcome(PaymentIntent intent, CallbackCommand cmd) {
        boolean publish = false;
        switch (cmd.outcome()) {
            case SUCCESS -> {
                intent.capture(cmd.providerTxnId());
                publish = true;
            }
            case FAILED -> intent.fail(cmd.providerTxnId(), cmd.failureReason());
        }
        PaymentIntent saved = repository.saveAndFlush(intent); // flush để UNIQUE check fail-fast trong cùng tx
        if (publish && saved.getStatus() instanceof PaymentStatus.Captured) {
            PaymentCompletedV1 event = new PaymentCompletedV1(
                    UUID.randomUUID(),
                    Instant.now(),
                    saved.getOrderId(),
                    saved.getProviderTxnId(),
                    saved.getCurrency(),
                    saved.getAmount());
            // Publish ngoài transaction commit là tốt hơn — Day 13 outbox.
            // Day 10 publish trong cùng method, accept dual-write debt log warn.
            publisher.publishPaymentCompleted(event);
            log.info("payment.completed published orderId={} txn={} amount={} {}",
                    saved.getOrderId(), saved.getProviderTxnId(),
                    saved.getAmount(), saved.getCurrency());
            return new CallbackResult(saved, false, true);
        }
        return new CallbackResult(saved, false, false);
    }
}
