package com.ecommerce.notification.api.v2;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * v2 contract: thêm field {@code channelUsed} so với v1.
 *
 * <p>Breaking change definition: thêm optional field = NON-breaking (JSON
 * additive, client cũ ignore unknown field). Xóa field hoặc đổi type =
 * BREAKING → cần v3 + dual-publish window.
 *
 * <p>N-1 deprecation policy: khi release v3, v1 sẽ nhận header
 * {@code Deprecation: true} + {@code Sunset: <date>} 90 ngày trước xóa.
 * Monitor metric {@code http_requests_total{path="/api/v1/*"}} để biết
 * khi nào traffic v1 về 0 an toàn xóa.
 */
@RestController
@RequestMapping("/api/v2/notifications")
public class NotificationStatusV2Controller {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "notification-service",
                "version", "v2",
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "channelUsed", "email"   // field mới — v1 client ignore nếu không expect
        );
    }
}
