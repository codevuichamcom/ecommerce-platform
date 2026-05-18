package com.ecommerce.payment.infrastructure.security;

import com.ecom.common.exception.BusinessException;
import com.ecommerce.payment.config.PaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackSignatureVerifierTest {

    private CallbackSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        PaymentProperties props = new PaymentProperties();
        props.setCallbackSecret("test-secret-2026");
        props.setCallbackMaxSkewSeconds(300);
        verifier = new CallbackSignatureVerifier(props);
    }

    @Test
    @DisplayName("verify pass khi signature đúng + timestamp trong window")
    void verify_ok() {
        long ts = Instant.now().getEpochSecond();
        String payload = ts + ".paymentId=abc&outcome=SUCCESS";
        String sig = verifier.sign(payload);
        verifier.verify(payload, sig, ts);
    }

    @Test
    @DisplayName("verify reject khi timestamp lệch quá 5 phút (replay protection)")
    void verify_timestampSkew_rejected() {
        long staleTs = Instant.now().getEpochSecond() - 600; // 10 phút trước
        String payload = staleTs + ".paymentId=abc&outcome=SUCCESS";
        String sig = verifier.sign(payload);
        assertThatThrownBy(() -> verifier.verify(payload, sig, staleTs))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("skew");
    }

    @Test
    @DisplayName("verify reject khi signature mismatch (tampered payload)")
    void verify_tampered_rejected() {
        long ts = Instant.now().getEpochSecond();
        String payload = ts + ".amount=100000";
        String sig = verifier.sign(payload);
        String tamperedPayload = ts + ".amount=999999"; // attacker đổi amount
        assertThatThrownBy(() -> verifier.verify(tamperedPayload, sig, ts))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid signature");
    }

    @Test
    @DisplayName("sign deterministic — same input → same signature")
    void sign_deterministic() {
        long ts = 1715000000L;
        String payload = ts + ".paymentId=abc";
        assertThat(verifier.sign(payload)).isEqualTo(verifier.sign(payload));
    }
}
