package com.ecommerce.order.infrastructure.messaging;

import com.ecom.common.event.PaymentCompletedV1;
import com.ecom.common.messaging.TopicNames;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consume {@code payment.completed} → transition Order PendingPayment → Paid.
 *
 * <p><b>Idempotency contract</b>:
 * <ul>
 *   <li>Kafka at-least-once: cùng event có thể đến nhiều lần khi consumer
 *       rebalance hoặc payment-service publish duplicate (xem
 *       {@code docs/issues/10-duplicate-payment-callback.md}).</li>
 *   <li>{@link Order#markPaid} no-op nếu Order không còn ở PendingPayment.
 *       Caller log "skipped" + commit offset bình thường.</li>
 *   <li>Race với Cancel: nếu user vừa cancel xong (Order=Cancelled) + payment
 *       callback arrive trễ → markPaid no-op → CẦN trigger refund flow
 *       (Day 36 reconciliation). Day 10 chỉ log warn để tránh wire flow chưa
 *       tồn tại.</li>
 * </ul>
 *
 * <p>Consumer group {@code ${spring.application.name}} (= "order-service")
 * khác group "notification-inv" của notification-service → fan-out
 * independent (Day 9 pattern).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedConsumer {

    private final OrderRepository orderRepository;

    @KafkaListener(topics = TopicNames.PAYMENT_COMPLETED, groupId = "${spring.application.name}")
    @Transactional
    public void onPaymentCompleted(PaymentCompletedV1 event) {
        orderRepository.findById(event.orderId()).ifPresentOrElse(
                order -> applyPayment(order, event),
                () -> log.warn("Received payment.completed for unknown orderId={} eventId={}",
                        event.orderId(), event.eventId()));
    }

    private void applyPayment(Order order, PaymentCompletedV1 event) {
        boolean transitioned = order.markPaid(event.occurredAt());
        if (transitioned) {
            orderRepository.save(order);
            log.info("Order {} -> Paid (txn={} amount={} {})",
                    event.orderId(), event.transactionId(), event.amount(), event.currency());
        } else {
            // Idempotent skip (duplicate event) HOẶC race-with-cancel. Phân biệt qua
            // current status: nếu Paid → duplicate; nếu Cancelled → cần refund (Day 36).
            log.warn("Order {} payment.completed skipped (current status={}); eventId={}",
                    event.orderId(), order.getStatus().statusName(), event.eventId());
        }
    }
}
