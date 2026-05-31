package com.ecommerce.order.interfaces.rest;

import com.ecom.common.event.OrderCreatedV1;
import com.ecom.common.response.ApiResponse;
import com.ecommerce.order.infrastructure.client.ProductFeignClient;
import com.ecommerce.order.infrastructure.client.ProductHttpInterfaceClient;
import com.ecommerce.order.infrastructure.client.dto.ProductSnapshotV1;
import com.ecommerce.order.infrastructure.messaging.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Day 8 demo endpoint — KHÔNG production. 2 mục đích:
 * <ol>
 *   <li>Trigger publish event {@code order.created} (verify Kafka pipeline end-to-end
 *       với notification-service consumer).</li>
 *   <li>Trigger sync call sang product-service qua CẢ HAI client (Feign + HTTP Interface)
 *       để verify cả 2 work + so sánh hành vi tại runtime.</li>
 * </ol>
 *
 * <p>Production sẽ XÓA endpoint này (Day 9 wire publish vào use case thật).
 * Tạm để dưới {@code /debug/*} không protect bằng Spring Security (xem SecurityConfig).
 */
@RestController
@RequestMapping("/debug")
@RequiredArgsConstructor
public class DebugController {

    private final OrderEventPublisher publisher;
    private final ProductFeignClient feignClient;
    private final ProductHttpInterfaceClient httpInterfaceClient;
    /** Day 17 — chỉ có bean khi app.debug.explain.enabled=true. */
    private final ObjectProvider<NPlusOneDemoService> nPlusOneDemo;

    /**
     * Day 17 — đo N+1 4 nấc fetch cho list đơn của 1 user. Trả về số query
     * mỗi nấc. Gated: nếu flag {@code app.debug.explain.enabled} off →
     * {@link NPlusOneDemoService} bean không tồn tại → 400 báo bật flag.
     *
     * <p>Ví dụ: {@code GET /debug/orders/n-plus-one?userId=<uuid>&size=40}
     */
    @GetMapping("/orders/n-plus-one")
    public ApiResponse<Map<String, Object>> nPlusOneDemo(@RequestParam UUID userId,
                                                         @RequestParam(defaultValue = "20") int size) {
        NPlusOneDemoService svc = nPlusOneDemo.getIfAvailable();
        if (svc == null) {
            return ApiResponse.ok(Map.of("error",
                    "Bật app.debug.explain.enabled=true để chạy demo N+1 (kèm hibernate.generate_statistics=true)."));
        }
        return ApiResponse.ok(svc.measure(userId, size));
    }

    @PostMapping("/publish-mock-order-created")
    public ApiResponse<Map<String, Object>> publishMock() {
        OrderCreatedV1 event = new OrderCreatedV1(
                UUID.randomUUID(),
                Instant.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "VND",
                new BigDecimal("100000.00"),
                List.of(new OrderCreatedV1.Item("SKU-DEMO-001", 1, new BigDecimal("100000.00")))
        );
        publisher.publishOrderCreated(event);
        return ApiResponse.ok(Map.of(
                "eventId", event.eventId().toString(),
                "orderId", event.orderId().toString(),
                "note", "Fire-and-forget. Check kafka-ui http://localhost:8090 hoặc notification-service log."
        ));
    }

    @GetMapping("/product/{sku}/via-feign")
    public ApiResponse<ProductSnapshotV1> viaFeign(@PathVariable String sku) {
        return feignClient.getSnapshot(sku);
    }

    @GetMapping("/product/{sku}/via-http-interface")
    public ApiResponse<ProductSnapshotV1> viaHttpInterface(@PathVariable String sku) {
        return httpInterfaceClient.getSnapshot(sku);
    }
}
