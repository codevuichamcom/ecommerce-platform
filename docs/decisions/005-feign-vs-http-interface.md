# ADR-005 — Spring 6.1 HTTP Interface cho sync cross-service call

- **Status**: ✅ Accepted
- **Date**: 2026-05-18
- **Deciders**: Tonny (backend tech lead)
- **Supersedes**: none (refines decision Day 6 dùng RestClient raw — không pre-empt declarative choice)

## Decision

Dùng **Spring 6.1 HTTP Interface** (`@HttpExchange` + `HttpServiceProxyFactory` trên `RestClient` adapter) cho mọi sync cross-service HTTP call MỚI từ Day 8 trở đi. **Giữ OpenFeign 1 demo client** trong order-service làm reference + để team hiểu khi maintain code Spring Cloud legacy. KHÔNG migrate code RestClient raw đã có (Day 6 cart/inventory client) trừ khi có business reason.

## Context

Day 6 order-service wire `RestClient` raw (imperative) cho sync call sang cart-service + inventory-service. Day 8 introduce Kafka async + cần thêm 1 sync call mới (order ↔ product `/products/{sku}/snapshot` capture price). Đây là chance đầu tiên cần chuẩn hoá **declarative HTTP client** cho monorepo trước khi tech debt nhân lên.

Spring 6.1 (Nov 2023) bundle native HTTP Interface — declarative HTTP client KHÔNG cần Spring Cloud. Câu hỏi từ PM Linh: "vẫn dùng Feign hay chuyển?".

## Alternatives considered

### Option A — Giữ `RestClient` raw imperative

- ✅ Đã có sẵn, 0 migration cost.
- ✅ Code lifecycle rõ — không có proxy magic.
- ❌ Boilerplate ×N endpoint × N service (Day 9-13 sẽ ×5 service mới).
- ❌ Khó test mock — phải stub HTTP layer thay vì mock interface.
- ❌ KHÔNG type-safe URI template ở compile time.

### Option B — OpenFeign

- ✅ Spring Cloud LoadBalancer + Eureka tích hợp built-in.
- ✅ `feign-resilience4j` annotation tích hợp Day 12 dễ.
- ✅ Mature ecosystem — feign-form, feign-jackson.
- ❌ Spring Cloud BOM coupling — phải maintain version alignment với Spring Boot release train (Boot 3.4.x ↔ Cloud 2024.0.0). Lệch version = startup fail.
- ❌ Runtime proxy thêm ~50ms cold start (insignificant nhưng cộng dồn).
- ❌ KHÔNG chọn được underlying client — khoá vào Apache HC (hoặc OkHttp via feign-okhttp riêng).
- ❌ Cargo-cult risk: senior dev quen Feign từ Spring Boot 2.x, KHÔNG đánh giá lại với Spring Framework 6.x.

### Option C — Spring 6.1 HTTP Interface ⭐ **Chosen**

- ✅ Native Spring Framework — KHÔNG cần Spring Cloud BOM.
- ✅ Chọn được underlying: `RestClient` (blocking + virtual thread native) hoặc `WebClient` (reactive). Project hiện tại blocking → `RestClient`.
- ✅ Type-safe URI template + parameter binding (compile-time check qua `@PathVariable`).
- ✅ Declarative giảm boilerplate; test mock interface dễ.
- ❌ Cần wire `HttpServiceProxyFactory` thủ công 1 lần / service (boilerplate setup, không khó).
- ❌ KHÔNG có service discovery built-in — phải set `baseUrl` từ property hoặc wire Spring Cloud LoadBalancer riêng (project < 20 service local Docker thì không cần).
- ❌ Resilience4j integration cần decorator manual (Feign annotation tích hợp sẵn).

### Option D — gRPC

- ✅ Type-safe protobuf, HTTP/2 streaming.
- ❌ Stack riêng — toàn bộ team phải học protobuf + codegen tooling.
- ❌ Không có browser/curl debugging ergonomics.
- ❌ Over-engineer cho ecom 9 service.

### Option E — WebClient declarative (`@HttpExchange` + `WebClientAdapter`)

- ✅ Reactive backpressure cho streaming use case.
- ❌ Reactive paradigm overhead khi app blocking + virtual thread (mismatch).
- ❌ Mono/Flux noise trong code đa số blocking.

## Chosen — Rationale

**Spring 6.1 HTTP Interface** trên `RestClient` adapter. Lý do dominant:

1. **Version coupling** — gắn Spring Framework core thay vì Spring Cloud release train. 1 service ít deps hơn = ops thấp hơn 30 ngày tiếp theo.
2. **Virtual thread fit** — `RestClient` blocking style match paradigm Day 2 đã chốt (`spring.threads.virtual.enabled=true`). KHÔNG cần Mono/Flux.
3. **Optionality giữ** — sau này cần streaming có thể swap adapter sang `WebClientAdapter` mà giữ interface signature.
4. **Pushback cargo-cult** — Feign trở thành "default vì quán tính", quyết định này document evidence-based để team align.

## Trade-offs

### Accepted
- **+ Boilerplate `HttpServiceProxyFactory` 1 lần / service** — đổi lại được flexibility chọn adapter. Đã wire ở [`HttpClientConfig.java`](../../services/order-service/src/main/java/com/ecommerce/order/config/HttpClientConfig.java).
- **+ KHÔNG có service discovery tự động** — chấp nhận vì project local Docker, prod dùng K8s DNS service-name + Spring Cloud Gateway riêng (Day 14 sẽ wire gateway). Khi join Eureka/Consul cần đánh giá lại.

### Rejected
- **- Feign familiarity** — team đã dùng Feign ở Sotatek project khác. Đổi lại được long-term tech debt thấp.
- **- LoadBalancer Eureka free** — KHÔNG có ngay; nếu cần Day 14+ wire `Spring Cloud LoadBalancer` riêng (independent decision).

## Consequences

- Day 8 wire 2 client side-by-side trong order-service ([`ProductFeignClient`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductFeignClient.java) + [`ProductHttpInterfaceClient`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductHttpInterfaceClient.java)) cho demo + lesson.
- Sau Day 8: TẤT CẢ sync call mới (Day 9-30) dùng HTTP Interface. RestClient raw Day 6 (`CartClient`, `InventoryClient`) **KHÔNG** migrate — chạy ổn, ROI thấp.
- Feign client sẽ KHÔNG add thêm; chỉ giữ làm reference. Sau Day 14 review nếu thật sự không dùng → xoá.
- Day 12 Resilience4j sẽ wire CỤ THỂ qua decorator cho HTTP Interface.

## Related

- Source: [`ProductHttpInterfaceClient.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductHttpInterfaceClient.java) · [`ProductFeignClient.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductFeignClient.java) · [`HttpClientConfig.java`](../../services/order-service/src/main/java/com/ecommerce/order/config/HttpClientConfig.java)
- Lesson: [08b — feign-vs-http-interface](../lessons/08b-feign-vs-http-interface.md)
- Interview: [day-08-kafka](../interview/day-08-kafka.md)
- Day 6 RestClient raw context: [decisions/003-ddd-for-order-inventory-payment.md](003-ddd-for-order-inventory-payment.md)
