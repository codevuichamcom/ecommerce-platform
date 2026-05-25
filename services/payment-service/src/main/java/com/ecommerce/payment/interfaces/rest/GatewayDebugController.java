package com.ecommerce.payment.interfaces.rest;

import com.ecom.common.response.ApiResponse;
import com.ecommerce.payment.gateway.MockGatewayClient;
import com.ecommerce.payment.gateway.VerificationResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug endpoints để demo Resilience4j wiring — KHÔNG dùng prod.
 *
 * <ul>
 *   <li>{@code GET  /debug/gateway/verify/{providerTxnId}} — gọi
 *       {@link MockGatewayClient#verify(String)}; quan sát behavior theo
 *       {@code app.gateway.mock.failure-rate}.</li>
 *   <li>{@code POST /debug/gateway/force-fail?fail=true} — ép mọi call sau đây
 *       throw để demo CLOSED → OPEN transition.</li>
 *   <li>{@code GET  /debug/gateway/state} — dump CB state + metrics counter.</li>
 * </ul>
 *
 * <p><b>Lý do public</b>: chỉ chạy dev profile. Production phải remove
 * controller này hoặc gate bằng role ADMIN.
 */
@RestController
@RequestMapping("/debug/gateway")
@RequiredArgsConstructor
public class GatewayDebugController {

    private final MockGatewayClient gateway;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @GetMapping("/verify/{providerTxnId}")
    public ResponseEntity<ApiResponse<VerificationResult>> verify(@PathVariable String providerTxnId) {
        return ResponseEntity.ok(ApiResponse.ok(gateway.verify(providerTxnId)));
    }

    @PostMapping("/force-fail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> forceFail(
            @RequestParam(defaultValue = "true") boolean fail) {
        gateway.setForceFail(fail);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("forceFail", fail)));
    }

    @GetMapping("/state")
    public ResponseEntity<ApiResponse<Map<String, Object>>> state() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(MockGatewayClient.CB_NAME);
        CircuitBreaker.Metrics m = cb.getMetrics();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", cb.getName());
        body.put("state", cb.getState().name());
        body.put("failureRate", m.getFailureRate());
        body.put("bufferedCalls", m.getNumberOfBufferedCalls());
        body.put("failedCalls", m.getNumberOfFailedCalls());
        body.put("successfulCalls", m.getNumberOfSuccessfulCalls());
        body.put("notPermittedCalls", m.getNumberOfNotPermittedCalls());
        body.put("clientTotal", gateway.getTotalCalls());
        body.put("clientSuccess", gateway.getSuccessCalls());
        body.put("clientFailed", gateway.getFailedCalls());
        body.put("clientFallback", gateway.getFallbackCalls());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
