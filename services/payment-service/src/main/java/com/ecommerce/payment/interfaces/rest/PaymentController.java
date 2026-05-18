package com.ecommerce.payment.interfaces.rest;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.response.ApiResponse;
import com.ecommerce.payment.application.CallbackCommand;
import com.ecommerce.payment.application.CallbackResult;
import com.ecommerce.payment.application.HandleCallbackUseCase;
import com.ecommerce.payment.application.InitiatePaymentCommand;
import com.ecommerce.payment.application.InitiatePaymentUseCase;
import com.ecommerce.payment.domain.PaymentIntent;
import com.ecommerce.payment.infrastructure.persistence.PaymentIntentRepository;
import com.ecommerce.payment.infrastructure.security.CallbackSignatureVerifier;
import com.ecommerce.payment.interfaces.rest.dto.CallbackRequest;
import com.ecommerce.payment.interfaces.rest.dto.CallbackResponse;
import com.ecommerce.payment.interfaces.rest.dto.InitiatePaymentRequest;
import com.ecommerce.payment.interfaces.rest.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final String HEADER_SIGNATURE = "X-Signature";
    private static final String HEADER_TIMESTAMP = "X-Signature-Timestamp";

    private final InitiatePaymentUseCase initiateUseCase;
    private final HandleCallbackUseCase callbackUseCase;
    private final PaymentIntentRepository repository;
    private final CallbackSignatureVerifier signatureVerifier;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> initiate(
            @Valid @RequestBody InitiatePaymentRequest req) {
        PaymentIntent intent = initiateUseCase.execute(new InitiatePaymentCommand(
                req.orderId(), req.amount(), req.currency(), req.provider()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(PaymentResponse.from(intent)));
    }

    /**
     * Mock gateway callback — PUBLIC endpoint (gateway không có JWT). Bảo
     * vệ bằng HMAC signature trong canonical payload {@code timestamp + "." + body}.
     *
     * <p><b>Idempotency contract</b>: gateway có thể gọi N lần cùng
     * {@code providerTxnId}. Endpoint LUÔN trả 200 OK + state hiện tại,
     * KHÔNG phân biệt "lần đầu xử lý" vs "duplicate" qua HTTP status
     * (gateway không cần biết — chỉ cần biết "đã nhận"). Field
     * {@code duplicate} trong body chỉ là metadata cho audit log.
     *
     * <p>Lý do dùng rawBody thay vì serialize lại JSON: HMAC tính trên bytes
     * gateway gửi nguyên gốc. Re-serialize có thể đổi field order / whitespace
     * → mismatch. Spring đọc body 2 lần là vấn đề → workaround Day 10: client
     * gửi rawBody trong header riêng (mock). Production: ContentCachingRequestWrapper
     * filter.
     */
    @PostMapping("/callback")
    public ResponseEntity<ApiResponse<CallbackResponse>> callback(
            @Valid @RequestBody CallbackRequest req,
            HttpServletRequest httpReq) {
        String signature = httpReq.getHeader(HEADER_SIGNATURE);
        String timestampStr = httpReq.getHeader(HEADER_TIMESTAMP);
        if (signature == null || timestampStr == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "Missing " + HEADER_SIGNATURE + " or " + HEADER_TIMESTAMP);
        }

        long ts;
        try {
            ts = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid timestamp header");
        }

        // Canonical payload: gateway tài liệu phải nói rõ format này.
        String signedPayload = timestampStr + "." + canonical(req);
        signatureVerifier.verify(signedPayload, signature, ts);

        CallbackCommand.Outcome outcome = parseOutcome(req.outcome());
        CallbackResult result = callbackUseCase.execute(new CallbackCommand(
                req.paymentId(), req.provider(), req.providerTxnId(),
                outcome, req.failureReason()));

        return ResponseEntity.ok(ApiResponse.ok(CallbackResponse.from(result)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentResponse>> get(@PathVariable UUID id) {
        PaymentIntent intent = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND,
                        "PaymentIntent not found: " + id));
        return ResponseEntity.ok(ApiResponse.ok(PaymentResponse.from(intent)));
    }

    private static CallbackCommand.Outcome parseOutcome(String s) {
        return switch (s.toUpperCase()) {
            case "SUCCESS" -> CallbackCommand.Outcome.SUCCESS;
            case "FAILED"  -> CallbackCommand.Outcome.FAILED;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Unknown outcome: " + s);
        };
    }

    /**
     * Canonical string cho HMAC. Day 10 dùng JSON-like concat đơn giản;
     * production: gateway document chính xác (vd VNPay: sort query param
     * alphabetically rồi concat).
     */
    private static String canonical(CallbackRequest r) {
        return new StringBuilder()
                .append("paymentId=").append(r.paymentId()).append("&")
                .append("provider=").append(r.provider()).append("&")
                .append("providerTxnId=").append(r.providerTxnId()).append("&")
                .append("outcome=").append(r.outcome())
                .toString();
    }
}
