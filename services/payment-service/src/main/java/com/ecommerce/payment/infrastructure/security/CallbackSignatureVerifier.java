package com.ecommerce.payment.infrastructure.security;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.payment.config.PaymentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verify HMAC-SHA256 signature trên callback payload + timestamp window.
 *
 * <p>Threat model bảo vệ:
 * <ul>
 *   <li><b>Tampering</b>: attacker đổi amount trong callback → HMAC mismatch.</li>
 *   <li><b>Replay attack</b>: attacker capture callback hợp lệ rồi replay
 *       → timestamp ngoài window 5 phút → reject.</li>
 *   <li><b>Spoofing</b>: attacker giả gateway gửi callback bất kỳ → không
 *       biết shared secret → không tạo HMAC hợp lệ.</li>
 * </ul>
 *
 * <p>Production thêm:
 * <ul>
 *   <li>Nonce table (DB / Redis) chống replay trong window — Day 10 chấp nhận
 *       window-only (gateway hiếm replay trong cùng 5 phút).</li>
 *   <li>IP allowlist gateway egress IPs (defense-in-depth).</li>
 * </ul>
 *
 * <p>Dùng {@link MessageDigest#isEqual} constant-time so sánh — chống timing
 * attack (so sánh String thường có early-exit bytes).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final PaymentProperties props;

    /**
     * @param signedPayload  canonical concat: {@code timestamp + "." + rawJsonBody}
     * @param signatureHex   hex-encoded HMAC-SHA256 từ gateway (header X-Signature).
     * @param timestampEpoch epoch seconds từ header X-Signature-Timestamp.
     * @throws BusinessException 401 nếu signature/timestamp invalid.
     */
    public void verify(String signedPayload, String signatureHex, long timestampEpoch) {
        long now = Instant.now().getEpochSecond();
        long skew = Math.abs(now - timestampEpoch);
        if (skew > props.getCallbackMaxSkewSeconds()) {
            log.warn("Callback timestamp skew={}s exceeds max={}s — possible replay",
                    skew, props.getCallbackMaxSkewSeconds());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Timestamp skew exceeds window");
        }

        String expectedHex = computeHmac(signedPayload, props.getCallbackSecret());
        if (!constantTimeEquals(expectedHex, signatureHex)) {
            log.warn("Callback signature mismatch — possible tampering or wrong secret");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid signature");
        }
    }

    /** Expose để client/test tạo signature đúng (mock gateway sender). */
    public String sign(String signedPayload) {
        return computeHmac(signedPayload, props.getCallbackSecret());
    }

    private static String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        // MessageDigest.isEqual là constant-time với mọi byte[] cùng length.
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
