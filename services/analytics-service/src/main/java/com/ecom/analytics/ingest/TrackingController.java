package com.ecom.analytics.ingest;

import com.ecom.analytics.domain.AnalyticsEvent;
import com.ecom.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Day 23 — HTTP beacon ingest cho hành vi UI (frontend Day 26+ gọi). Tách
 * khỏi Kafka consumer vì 2 nguồn event khác nhau:
 * <ul>
 *   <li>Beacon = hành vi client-side (view/add-cart) backend không thấy.</li>
 *   <li>Kafka = domain event server-side (order placed).</li>
 * </ul>
 *
 * <p>{@code 202 Accepted} chứ KHÔNG {@code 200/201}: beacon là fire-and-forget,
 * client KHÔNG chờ xử lý xong. Trả 202 = "đã nhận, sẽ ghi". Frontend bắn beacon
 * rồi đi tiếp, không block UX vì analytics.
 */
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class TrackingController {

    private final EventIngestService ingestService;

    @PostMapping("/track")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<String> track(@Valid @RequestBody TrackEventRequest req) {
        Instant when = req.occurredAt() != null ? req.occurredAt() : Instant.now();
        AnalyticsEvent event = new AnalyticsEvent(
                req.type(), when, req.sessionId(), req.userId(), req.productId(), req.payload());
        ingestService.ingest(event);
        return ApiResponse.ok("accepted");
    }
}
