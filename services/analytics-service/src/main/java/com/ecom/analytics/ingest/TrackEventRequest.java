package com.ecom.analytics.ingest;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * Beacon payload từ frontend (Day 26+ React sẽ gọi). Giống "collect" endpoint
 * của Google Analytics: 1 POST nhẹ cho mỗi hành vi UI.
 *
 * <p>{@code type} bắt buộc (discriminator). Phần còn lại optional — chính sự
 * "optional + payload tự do" này là lý do event store hợp document hơn relational:
 * mỗi loại beacon mang field khác nhau, không ép schema chung.
 *
 * <p>{@code occurredAt} null → server gán {@code now()}. Cho phép client gửi
 * thời điểm thật (offline buffer rồi gửi muộn) nhưng KHÔNG tin tuyệt đối —
 * clamp ở service nếu cần (bỏ qua ở Day 23, ghi chú prevention trong issue).
 */
public record TrackEventRequest(
        @NotBlank String type,
        String sessionId,
        String userId,
        String productId,
        Instant occurredAt,
        Map<String, Object> payload
) {
}
