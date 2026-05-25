package com.ecommerce.payment.gateway;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>Mock outbound call tới payment gateway — Day 12</h2>
 *
 * <p>Use case: sau khi nhận callback (Day 10), payment-service muốn **verify**
 * lại với gateway rằng giao dịch thực sự thành công (chống spoofing). Đây là
 * outbound HTTP call → ứng viên kinh điển cho circuit breaker + bulkhead.
 *
 * <p><b>Resilience4j wrapping</b>:
 * <ul>
 *   <li>{@link CircuitBreaker} {@code paymentGateway} — sliding window count=10,
 *       failure rate threshold 50% → OPEN. State OPEN tồn tại 30s rồi
 *       HALF_OPEN (3 probe call). Config ở {@code application.yml}.</li>
 *   <li>{@link Bulkhead} {@code paymentGateway} type SEMAPHORE, maxConcurrent=10 —
 *       chống thread/connection exhaustion khi gateway chậm. Virtual thread (Day 8)
 *       không tự bảo vệ downstream → vẫn cần bulkhead.</li>
 *   <li>{@code fallbackMethod} {@link #verifyFallback(String, Throwable)} —
 *       degrade gracefully: trả về {@link VerificationResult#unknown(String)}
 *       với reason để caller quyết định (queue retry vs manual reconcile).</li>
 * </ul>
 *
 * <p><b>Không chặn luồng order</b>: nếu gateway down → CB mở → fast-fail
 * (sub-millisecond) → caller dùng fallback. Day 36 reconciliation job sẽ
 * pick up các txn UNKNOWN và verify lại async.
 *
 * <p><b>Mock behavior</b>: configurable failure rate qua
 * {@code app.gateway.mock.failure-rate} (0.0-1.0) để demo CB state transition.
 * Set 0.8 → ~80% call throw → 10 call → ~8 fail → CB mở. KHÔNG dùng cho prod.
 */
@Slf4j
@Component
public class MockGatewayClient {

    public static final String CB_NAME = "paymentGateway";
    public static final String BH_NAME = "paymentGateway";

    private final double failureRate;
    private final long latencyMs;
    private final AtomicBoolean forceFail = new AtomicBoolean(false);
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong successCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong fallbackCalls = new AtomicLong();

    public MockGatewayClient(
            @Value("${app.gateway.mock.failure-rate:0.0}") double failureRate,
            @Value("${app.gateway.mock.latency-ms:50}") long latencyMs) {
        this.failureRate = failureRate;
        this.latencyMs = latencyMs;
    }

    /**
     * Verify transaction với gateway. Mock behavior: sleep + có khả năng throw.
     *
     * @param providerTxnId txn id ở gateway
     * @return verification result (SUCCESS / FAILED), hoặc UNKNOWN nếu fallback
     * @throws GatewayUnavailableException khi mock cố ý fail — CB sẽ count
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "verifyFallback")
    @Bulkhead(name = BH_NAME) // semaphore mặc định
    public VerificationResult verify(String providerTxnId) {
        totalCalls.incrementAndGet();
        sleepUninterruptibly(latencyMs);

        if (forceFail.get() || ThreadLocalRandom.current().nextDouble() < failureRate) {
            failedCalls.incrementAndGet();
            throw new GatewayUnavailableException(
                    "Mock gateway unavailable for txn=" + providerTxnId);
        }

        successCalls.incrementAndGet();
        return VerificationResult.success(providerTxnId);
    }

    /**
     * Fallback signature: same params + extra {@link Throwable}. Resilience4j chọn
     * fallback có signature khớp nhất với exception. Return type phải match.
     */
    @SuppressWarnings("unused") // gọi qua reflection bởi Resilience4j
    public VerificationResult verifyFallback(String providerTxnId, Throwable ex) {
        fallbackCalls.incrementAndGet();
        log.warn("[gateway-fallback] verify({}) → UNKNOWN. Cause: {} — caller nên schedule reconcile Day 36.",
                providerTxnId, ex.getClass().getSimpleName());
        return VerificationResult.unknown(providerTxnId, ex.getClass().getSimpleName());
    }

    /** Test-helper: ép mọi call sau đây throw để demo CB transition CLOSED → OPEN. */
    public void setForceFail(boolean fail) {
        this.forceFail.set(fail);
    }

    public long getTotalCalls()    { return totalCalls.get(); }
    public long getSuccessCalls()  { return successCalls.get(); }
    public long getFailedCalls()   { return failedCalls.get(); }
    public long getFallbackCalls() { return fallbackCalls.get(); }

    private static void sleepUninterruptibly(long ms) {
        try {
            Thread.sleep(Duration.ofMillis(ms));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
