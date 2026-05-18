package com.ecommerce.payment.domain;

import com.ecommerce.payment.domain.exception.InvalidPaymentTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test PaymentIntent state machine + invariant. Pure domain, no Spring,
 * no DB — chạy nhanh, không flaky.
 */
class PaymentIntentTest {

    private static final UUID ORDER = UUID.randomUUID();

    @Test
    @DisplayName("initiate() set status=INITIATED + giữ amount/currency/provider")
    void initiate_initialState() {
        PaymentIntent p = PaymentIntent.initiate(ORDER, new BigDecimal("100000"), "VND", "MOCK");
        assertThat(p.getStatus()).isInstanceOf(PaymentStatus.Initiated.class);
        assertThat(p.getAmount()).isEqualByComparingTo("100000");
        assertThat(p.getCurrency()).isEqualTo("VND");
        assertThat(p.getProvider()).isEqualTo("MOCK");
        assertThat(p.getProviderTxnId()).isNull();
    }

    @Test
    @DisplayName("initiate() reject amount âm + currency sai format + provider blank")
    void initiate_invalidInput() {
        assertThatThrownBy(() -> PaymentIntent.initiate(ORDER, new BigDecimal("-1"), "VND", "MOCK"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentIntent.initiate(ORDER, BigDecimal.TEN, "VN", "MOCK"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentIntent.initiate(ORDER, BigDecimal.TEN, "VND", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("capture(txn) từ INITIATED → CAPTURED + bind providerTxnId")
    void capture_fromInitiated() {
        PaymentIntent p = initiated();
        p.capture("TXN-001");
        assertThat(p.getStatus()).isInstanceOf(PaymentStatus.Captured.class);
        assertThat(p.getProviderTxnId()).isEqualTo("TXN-001");
        assertThat(p.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("capture lần 2 với cùng txnId trên terminal → throw")
    void capture_terminal_rejected() {
        PaymentIntent p = initiated();
        p.capture("TXN-001");
        assertThatThrownBy(() -> p.capture("TXN-001"))
                .isInstanceOf(InvalidPaymentTransitionException.class);
    }

    @Test
    @DisplayName("authorize → capture (2-step gateway) preserve txnId")
    void authorizeThenCapture() {
        PaymentIntent p = initiated();
        p.authorize("TXN-AUTH");
        assertThat(p.getStatus()).isInstanceOf(PaymentStatus.Authorized.class);
        p.capture("TXN-AUTH"); // same txn id — gateway re-confirm
        assertThat(p.getStatus()).isInstanceOf(PaymentStatus.Captured.class);
    }

    @Test
    @DisplayName("bindProviderTxnId conflict — txnId khác cho cùng intent → throw")
    void providerTxnId_immutable() {
        PaymentIntent p = initiated();
        p.authorize("TXN-A");
        assertThatThrownBy(() -> p.capture("TXN-B"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerTxnId conflict");
    }

    @Test
    @DisplayName("fail(reason) từ INITIATED → FAILED + truncate reason 255")
    void fail_persistReason() {
        PaymentIntent p = initiated();
        String longReason = "x".repeat(500);
        p.fail("TXN-NG", longReason);
        assertThat(p.getStatus()).isInstanceOf(PaymentStatus.Failed.class);
        assertThat(p.getFailureReason()).hasSize(255);
        assertThat(p.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("fail trên CAPTURED → throw (không cho phép un-capture)")
    void fail_onCaptured_rejected() {
        PaymentIntent p = initiated();
        p.capture("TXN-001");
        assertThatThrownBy(() -> p.fail("TXN-001", "any"))
                .isInstanceOf(InvalidPaymentTransitionException.class);
    }

    @Test
    @DisplayName("markExpired từ INITIATED → EXPIRED (TTL flow)")
    void markExpired_fromInitiated() {
        PaymentIntent p = initiated();
        p.markExpired();
        assertThat(p.getStatus()).isInstanceOf(PaymentStatus.Expired.class);
        assertThat(p.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("PaymentStatus.fromDb roundtrip cho cả 5 permit")
    void status_fromDb_roundtrip() {
        for (String name : new String[]{"INITIATED", "AUTHORIZED", "CAPTURED", "FAILED", "EXPIRED"}) {
            assertThat(PaymentStatus.fromDb(name).name()).isEqualTo(name);
        }
        assertThatThrownBy(() -> PaymentStatus.fromDb("UNKNOWN"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PaymentIntent initiated() {
        return PaymentIntent.initiate(ORDER, new BigDecimal("100000"), "VND", "MOCK");
    }
}
