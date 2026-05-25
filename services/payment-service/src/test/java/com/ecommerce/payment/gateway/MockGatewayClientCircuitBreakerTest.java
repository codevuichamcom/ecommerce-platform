package com.ecommerce.payment.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test cho behavior CircuitBreaker state machine.
 *
 * <p>Strategy: tạo CB manual với config "mini" giống application.yml (window=10,
 * minCalls=5, failureRate≥50%, openDuration=100ms để test nhanh). Wrap call
 * tới {@link MockGatewayClient#verify(String)} qua {@code CircuitBreaker.decorateSupplier}
 * thay vì AOP proxy (test không boot Spring context).
 *
 * <p>Verify:
 * <ol>
 *   <li>5 call đầu fail → CB chuyển CLOSED → OPEN.</li>
 *   <li>OPEN → call bị reject ngay (fast-fail) → {@code CallNotPermittedException}.</li>
 *   <li>Sau {@code waitDuration} → HALF_OPEN; probe call success → CLOSED.</li>
 * </ol>
 */
class MockGatewayClientCircuitBreakerTest {

    private CircuitBreaker cb;
    private MockGatewayClient gateway;

    @BeforeEach
    void setup() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(GatewayUnavailableException.class)
                .build();
        cb = CircuitBreakerRegistry.of(cfg).circuitBreaker("test");
        gateway = new MockGatewayClient(0.0, 1);
    }

    @Test
    void closedToOpen_afterFailureThreshold() {
        gateway.setForceFail(true);
        Supplier<VerificationResult> decorated = CircuitBreaker.decorateSupplier(
                cb, () -> gateway.verify("txn-x"));

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 5 call đầu — đủ minimumNumberOfCalls, 100% fail → vượt 50% threshold → OPEN.
        for (int i = 0; i < 5; i++) {
            try { decorated.get(); } catch (Exception expected) { /* count by CB */ }
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void open_rejectsCallsFastFail() {
        gateway.setForceFail(true);
        Supplier<VerificationResult> decorated = CircuitBreaker.decorateSupplier(
                cb, () -> gateway.verify("txn-y"));
        for (int i = 0; i < 5; i++) {
            try { decorated.get(); } catch (Exception expected) { /* drive to OPEN */ }
        }
        long callsBeforeFastFail = gateway.getTotalCalls();

        // Tiếp tục call khi OPEN — phải bị reject TRƯỚC khi vào gateway.
        for (int i = 0; i < 5; i++) {
            try { decorated.get(); } catch (Exception expected) { /* CallNotPermitted */ }
        }

        assertThat(gateway.getTotalCalls())
                .as("OPEN state phải fast-fail, KHÔNG gọi vào gateway")
                .isEqualTo(callsBeforeFastFail);
    }

    @Test
    void halfOpenToClosed_whenProbeSucceeds() throws InterruptedException {
        gateway.setForceFail(true);
        Supplier<VerificationResult> decorated = CircuitBreaker.decorateSupplier(
                cb, () -> gateway.verify("txn-z"));
        for (int i = 0; i < 5; i++) {
            try { decorated.get(); } catch (Exception expected) { /* drive to OPEN */ }
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait open duration để auto-transition sang HALF_OPEN.
        Thread.sleep(200);

        gateway.setForceFail(false); // gateway "recover"
        // 3 probe call (permittedNumberOfCallsInHalfOpenState) — pass hết → CLOSED.
        for (int i = 0; i < 3; i++) {
            decorated.get();
        }
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
