package com.ecommerce.payment.application;

import java.util.UUID;

/**
 * Input cho {@link HandleCallbackUseCase}. Gateway callback đã được
 * {@code CallbackSignatureVerifier} verify trước khi tới đây — use case
 * KHÔNG biết về HMAC/HTTP.
 *
 * @param paymentId      payment intent id (gateway giữ qua redirect URL).
 * @param providerTxnId  ID gateway sinh ra cho transaction này — UNIQUE
 *                       trong scope provider. Là dedup key.
 * @param outcome        SUCCESS / FAILED (Day 10 simplification, 2-step
 *                       Day 36 sẽ thêm AUTHORIZED outcome).
 * @param failureReason  free-text khi outcome=FAILED, null khi SUCCESS.
 */
public record CallbackCommand(
        UUID paymentId,
        String provider,
        String providerTxnId,
        Outcome outcome,
        String failureReason
) {
    public enum Outcome { SUCCESS, FAILED }
}
