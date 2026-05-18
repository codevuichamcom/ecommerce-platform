package com.ecommerce.order.infrastructure.client;

import com.ecom.common.response.ApiResponse;
import com.ecommerce.order.infrastructure.client.dto.ProductSnapshotV1;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * OpenFeign client — pattern cũ Spring Cloud.
 *
 * <p><b>Day 8 demo cả 2 client (Feign vs HTTP Interface)</b> để so sánh.
 * ADR-005 chọn HTTP Interface cho code mới. Feign giữ trong codebase
 * chỉ ở client này — KHÔNG dùng cho call mới.
 *
 * <p>Lưu ý: {@code url} hard-coded từ property — KHÔNG dùng service
 * discovery (Eureka). Spring Cloud LoadBalancer cần thêm starter, tradeoff
 * thảo luận ở doc {@code lessons/08b-feign-vs-http-interface.md}.
 */
@FeignClient(name = "product-service-feign", url = "${services.product.base-url}")
public interface ProductFeignClient {

    @GetMapping("/products/{sku}/snapshot")
    ApiResponse<ProductSnapshotV1> getSnapshot(@PathVariable("sku") String sku);
}
