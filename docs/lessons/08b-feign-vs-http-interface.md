# 📚 Lesson 08b — OpenFeign vs Spring 6.1 HTTP Interface

> Topic phỏng vấn HOT: senior dev tháng 2026 vẫn dùng Feign theo quán
> tính trong khi Spring 6.1 (2023) đã có **declarative HTTP client native**.
> Hiểu trade-off để pushback khi PM/Tech Lead bảo "dùng Feign vì
> ai cũng dùng".

## TL;DR

| Tiêu chí                | OpenFeign                        | Spring 6.1 HTTP Interface         |
| ----------------------- | -------------------------------- | --------------------------------- |
| Annotation              | `@FeignClient`, `@GetMapping`    | `@GetExchange` (Spring native)    |
| Underlying client       | Apache HC / OkHttp via feign-okhttp | `RestClient` / `WebClient` (chọn) |
| Version coupling        | Spring Cloud release train       | Spring Framework core             |
| Service discovery       | LoadBalancer + Eureka tích hợp   | Manual hoặc Spring Cloud Gateway  |
| Resilience4j            | Tích hợp sẵn `feign-resilience4j` | Manual qua decorator              |
| Virtual thread friendly | OK (sync mode)                   | OK natively (`RestClient` adapter) |
| Reactive support        | KHÔNG (sync only)                | YES (`WebClient` adapter)         |
| Boilerplate setup       | `@EnableFeignClients` xong       | Cần wire `HttpServiceProxyFactory` 1 lần |
| Codegen                 | Compile-time + runtime proxy     | Pure runtime proxy                |
| Spring Boot version     | Cần Spring Cloud BOM             | Có sẵn từ Spring Framework 6.1+   |

**Verdict (ADR-005)**: Code mới → **HTTP Interface**. Code cũ chạy Feign ổn → **giữ nguyên**, không migrate vì migrate cost.

## 🧠 Code side-by-side

### Feign (xem [`ProductFeignClient.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductFeignClient.java))

```java
@FeignClient(name = "product-service-feign", url = "${services.product.base-url}")
public interface ProductFeignClient {
    @GetMapping("/products/{sku}/snapshot")
    ApiResponse<ProductSnapshotV1> getSnapshot(@PathVariable("sku") String sku);
}
```

```java
// Application bootstrap
@SpringBootApplication
@EnableFeignClients
public class App {}
```

### HTTP Interface (xem [`ProductHttpInterfaceClient.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductHttpInterfaceClient.java))

```java
public interface ProductHttpInterfaceClient {
    @GetExchange("/products/{sku}/snapshot")
    ApiResponse<ProductSnapshotV1> getSnapshot(@PathVariable String sku);
}
```

```java
@Bean
ProductHttpInterfaceClient productHttpInterfaceClient(
        RestClient.Builder builder,
        @Value("${services.product.base-url}") String baseUrl) {
    RestClient restClient = builder.baseUrl(baseUrl).build();
    HttpServiceProxyFactory factory = HttpServiceProxyFactory
            .builderFor(RestClientAdapter.create(restClient))
            .build();
    return factory.createClient(ProductHttpInterfaceClient.class);
}
```

## ⚖️ Approaches compared

| Approach                            | Pros                                                        | Cons                                                          |
| ----------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------- |
| `RestTemplate`                      | Stable, ubiquitous                                          | Maintenance-only từ Spring 6 — **không nên dùng code mới**    |
| `RestClient` raw                    | Spring 6.1 native, fluent API                               | Imperative (boilerplate khi nhiều endpoint)                   |
| `WebClient` declarative             | Reactive, backpressure, virtual thread mode                 | Reactive paradigm overhead nếu app blocking                   |
| **OpenFeign**                       | Spring Cloud ecosystem deep, Eureka/LoadBalancer free       | Spring Cloud BOM coupling, runtime proxy chậm startup ~50ms   |
| **Spring 6.1 HTTP Interface**       | Native Spring, ít dependency, mix RestClient/WebClient adapter | Wire `HttpServiceProxyFactory` thủ công (1 lần / app)         |
| gRPC                                | Type-safe (protobuf), HTTP/2 streaming                      | Stack riêng, lib khác Java cần codegen tooling                |

## 🎯 Khi nào dùng cái nào

### Dùng HTTP Interface khi:
- Greenfield Spring Boot 3.x — không có Spring Cloud từ trước.
- Muốn chọn được underlying client (`RestClient` cho blocking + virtual thread; `WebClient` cho reactive).
- Project nhỏ < 20 service không cần service discovery phức tạp.
- Muốn align version với Spring Framework, KHÔNG quản version Spring Cloud release train.

### Vẫn dùng Feign khi:
- Đã có Spring Cloud LoadBalancer + Eureka / Consul + Sleuth (legacy nhưng chưa migrate xong).
- Cần `feign-form` / `feign-jackson` plugin ecosystem.
- Team đã thuộc Feign + retrofit knowledge → migrate ROI thấp.

### KHÔNG dùng cái nào trong project ecom Day 8:
Day 6 đã wire `RestClient` cho cart/inventory. Day 8 demo CẢ HAI client mới (Feign + HTTP Interface) cho cùng 1 endpoint `/products/{sku}/snapshot` để compare hands-on. Verdict ADR-005: chọn HTTP Interface. Sau Day 8 sẽ KHÔNG add Feign call mới; Feign chỉ giữ 1 client demo cho lesson này.

## ⚠️ Cạm bẫy

1. **HTTP Interface KHÔNG có service discovery built-in** — phải set `baseUrl` cứng từ property hoặc tự wire LoadBalancer. Feign tích hợp sẵn `LoadBalancerInterceptor`. Project < 10 service local Docker thì property đủ; > 20 service prod thì cần discovery.
2. **Feign `@FeignClient` interface KHÔNG được dùng `@RequestMapping` ở class level + `@GetMapping` ở method** — Spring sẽ tưởng nhầm nó là `@RestController` và scan. Dùng full path ở method only.
3. **Generic response type** — cả 2 client đều dùng được `ApiResponse<T>` qua Jackson type erasure preserve (Spring tự handle generic ở interface signature). KHÔNG cần `ParameterizedTypeReference` boilerplate như `RestTemplate.exchange()`.
4. **Resilience4j wire khác nhau** — Feign có `feign-resilience4j` annotation; HTTP Interface phải decorate `HttpServiceProxyFactory` qua interceptor hoặc dùng `@CircuitBreaker` Spring AOP. Day 12 deep-dive.

## 🎤 Trả lời phỏng vấn

> **Q**: Greenfield project Spring Boot 3.4, em chọn Feign hay HTTP Interface?

**A**: HTTP Interface. 3 lý do (Việt + English term):

1. **Version coupling thấp hơn** — HTTP Interface gắn Spring Framework, mặc định có sẵn từ Boot 3.1. Feign cần thêm Spring Cloud BOM, phải maintain release train alignment (Boot 3.4.x ↔ Cloud 2024.0.0). Tăng cost dependency management.
2. **Underlying client linh hoạt hơn** — `RestClient` (blocking) cho normal use case + virtual thread; `WebClient` (reactive) cho streaming/SSE. Feign khoá vào Apache HC mặc định.
3. **Boilerplate setup chỉ 1 lần** — `HttpServiceProxyFactory` viết 1 bean wire cho cả ứng dụng. Đổi lại được flexibility chọn adapter + KHÔNG cần `@EnableFeignClients` scan.

**Khi nào vẫn dùng Feign?** Brownfield đã có Spring Cloud ecosystem (Eureka, LoadBalancer, Sleuth) + team thuộc Feign pattern → cost migrate > benefit.

> **Follow-up trap**: "Feign benchmark chậm hơn HTTP Interface?"

Hai cái về cơ bản identical (cả hai đều là proxy → reflective method call → HTTP). Khác biệt nhỏ ở underlying HTTP client (Apache HC vs JDK HttpClient). **Lý do chọn không phải performance — là dependency footprint + paradigm fit**.

## 🔗 Related

- Source code: [`ProductFeignClient`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductFeignClient.java) · [`ProductHttpInterfaceClient`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductHttpInterfaceClient.java) · [`HttpClientConfig`](../../services/order-service/src/main/java/com/ecommerce/order/config/HttpClientConfig.java)
- ADR: [005 — feign-vs-http-interface](../decisions/005-feign-vs-http-interface.md)
- Day 12 (Resilience4j wire cho cả 2 client): [lesson 12b — circuit-breaker-resilience4j](../lessons/12b-circuit-breaker-resilience4j.md) (planned)
