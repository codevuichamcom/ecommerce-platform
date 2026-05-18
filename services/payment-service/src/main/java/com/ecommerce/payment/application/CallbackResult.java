package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.PaymentIntent;

/**
 * Outcome của {@link HandleCallbackUseCase#execute}.
 *
 * @param intent       state hiện tại của PaymentIntent (sau khi xử lý).
 * @param duplicate    {@code true} nếu callback này là duplicate (đã xử lý
 *                     trước đó) — caller dùng để skip publish event +
 *                     trả 200 idempotent response.
 * @param eventPublished {@code true} nếu use case này publish payment.completed.
 *                       False khi: duplicate=true HOẶC outcome=FAILED.
 */
public record CallbackResult(
        PaymentIntent intent,
        boolean duplicate,
        boolean eventPublished
) {}
