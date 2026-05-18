package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.PaymentIntent;
import com.ecommerce.payment.infrastructure.persistence.PaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo PaymentIntent ở trạng thái INITIATED. Gọi từ checkout flow sau khi
 * order đã placed (PendingPayment) — return paymentId để client redirect
 * sang gateway hosted page.
 *
 * <p><b>KHÔNG idempotent ở Day 10</b>: client gọi lại tạo PaymentIntent
 * thứ 2 cho cùng orderId. Acceptable vì:
 * <ul>
 *   <li>Mỗi PaymentIntent là 1 lần "thử" thanh toán — gateway charge fail
 *       lần 1, user retry → PaymentIntent thứ 2 (audit trail rõ).</li>
 *   <li>Chỉ 1 trong số đó được phép CAPTURED (Day 36 reconciliation enforce
 *       ràng buộc cấp order).</li>
 * </ul>
 * Production: thêm rate-limit per orderId chống abuse (Day 37 rate limiter).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitiatePaymentUseCase {

    private final PaymentIntentRepository repository;

    @Transactional
    public PaymentIntent execute(InitiatePaymentCommand cmd) {
        PaymentIntent intent = PaymentIntent.initiate(
                cmd.orderId(), cmd.amount(), cmd.currency(), cmd.provider());
        PaymentIntent saved = repository.save(intent);
        log.info("PaymentIntent initiated id={} orderId={} amount={} {} provider={}",
                saved.getId(), saved.getOrderId(), saved.getAmount(), saved.getCurrency(),
                saved.getProvider());
        return saved;
    }
}
