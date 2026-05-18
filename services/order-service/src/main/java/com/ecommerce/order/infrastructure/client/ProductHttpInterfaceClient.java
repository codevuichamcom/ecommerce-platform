package com.ecommerce.order.infrastructure.client;

import com.ecom.common.response.ApiResponse;
import com.ecommerce.order.infrastructure.client.dto.ProductSnapshotV1;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

/**
 * Spring 6.1 HTTP Interface — declarative HTTP client thuần Spring Framework
 * (KHÔNG cần Spring Cloud). Binding bằng {@code HttpServiceProxyFactory}
 * trên {@code RestClient} adapter (xem {@link com.ecommerce.order.config.HttpClientConfig}).
 *
 * <p><b>Khác Feign</b>:
 * <ul>
 *   <li>Annotation: {@code @GetExchange} (Spring) vs {@code @GetMapping} (Feign reuse Web).</li>
 *   <li>Underlying: {@code RestClient}/{@code WebClient} (chọn được) vs Apache HC/OkHttp via {@code feign-okhttp}.</li>
 *   <li>Version: gắn Spring 6.x; Feign gắn Spring Cloud release train.</li>
 *   <li>Boilerplate: cần wire {@code HttpServiceProxyFactory} thủ công (1 lần / app);
 *       Feign chỉ cần {@code @EnableFeignClients}.</li>
 *   <li>Resilience4j: cả 2 đều integrate được nhưng wire differently
 *       (Day 12 sẽ deep dive).</li>
 * </ul>
 *
 * <p>ADR-005 verdict: HTTP Interface cho code mới (greenfield Boot 3.4).
 */
public interface ProductHttpInterfaceClient {

    @GetExchange("/products/{sku}/snapshot")
    ApiResponse<ProductSnapshotV1> getSnapshot(@PathVariable String sku);
}
