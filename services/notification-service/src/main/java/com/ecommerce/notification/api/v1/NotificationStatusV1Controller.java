package com.ecommerce.notification.api.v1;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * v1 contract: trả về {@code service} + {@code status} + {@code timestamp}.
 *
 * <p>Đây là endpoint demo API versioning — không phải health check thực sự
 * (dùng actuator /actuator/health cho đó). Mục đích: chứng minh v1 và v2
 * co-exist, v2 thêm field {@code channelUsed} mà không break v1 client.
 *
 * <p>URI versioning strategy (xem ADR-008): prefix {@code /api/v1} để
 * Gateway route theo path. Dễ debug curl, dễ cache, hy sinh URL "purity".
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationStatusV1Controller {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "notification-service",
                "version", "v1",
                "status", "UP",
                "timestamp", Instant.now().toString()
        );
    }
}
