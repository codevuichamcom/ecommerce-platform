# 📋 Week 1 — CV bullets (Senior Backend, ecommerce platform)

> **Day 7 deliverable.** Compile từ Day 1-6 commits + docs thành **2 bullet
> metric-driven** dùng cho CV / LinkedIn / interview elevator pitch.
>
> **Honest disclaimer**: project này là **personal-lab learning** (40-day
> sprint solo + AI). KHÔNG claim "team 6 dev" hay "production traffic
> thật". Wording dưới đây phản ánh đúng nature: "designed and built"
> không phải "led team to deliver". Day 38 portfolio polish sẽ refine.

---

## 🎯 Bullet 1 — Backend depth (DDD + concurrency)

> **Designed and built a production-grade ecommerce backend (5 microservices,
> Java 21 LTS + Spring Boot 3.4.5) applying selective DDD where invariants
> demanded it: an `Inventory` aggregate with optimistic locking
> (`@Version` + `@Retryable` exponential backoff 50→500ms) prevents
> overselling under 100-thread concurrent reservation (verified via
> Testcontainers IT, no oversell across 50-stock × 100-reserve scenario);
> an `Order` aggregate with sealed `OrderStatus` (Java 21 sealed types
> + exhaustive pattern matching) gives compile-time guarantee on state
> transitions, eliminating an entire class of runtime bugs from
> `if (status == "PAID")` typos.**

**Evidence trail**:
- Code: [Stock.java](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/Stock.java) · [Order.java](../../services/order-service/src/main/java/com/ecommerce/order/domain/Order.java) · [OrderStatus.java](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderStatus.java)
- Tests: 9 Stock invariant + 14 Order aggregate + 5 sealed JSON round-trip = **28 unit test PASS**
- Docs: [issue 04 oversell](../issues/04-overselling-stock.md) · [lesson 04 optimistic-lock](../lessons/04-optimistic-locking.md) · [lesson 06 aggregate-root](../lessons/06-aggregate-root.md) · [lesson 06b sealed-types](../lessons/06b-sealed-types-state-machine.md) · [ADR-003](../decisions/003-ddd-for-order-inventory-payment.md)

**Talking points** (45s pitch):
1. *Why DDD*: 3-điểm criteria — ≥3 invariants + concurrency + domain events. Inventory + Order pass; Cart + Product không, Layered.
2. *Why optimistic, not pessimistic*: contention rate < 30% trong ecom checkout; pessimistic kill throughput. Trade-off retry storm chấp nhận với exp backoff + DB CHECK constraint defense-in-depth.
3. *Why sealed, not enum*: enum nullable hell khi state có data riêng (`Cancelled.reason`, `Shipped.trackingNumber`). Sealed = compile-time exhaustiveness; thêm permit `Refunded` → mọi switch chưa cover compile fail.

---

## 🎯 Bullet 2 — Modern stack + cross-cutting hygiene

> **Architected the platform with modern Java/Spring practices: Virtual
> Threads enabled across all services (`spring.threads.virtual.enabled=true`,
> Java 21 / Loom) for IO-bound throughput without reactive complexity;
> Records for DTOs and Value Objects (Money, Address) enforcing immutability;
> a Redis-primary cart-service with HINCRBY atomic field-level operations
> preventing lost-update on concurrent add-to-cart, plus anonymous→user
> cart merge with idempotent semantics (anon key DEL after merge); and a
> shared `common-lib` (auto-config starter pattern with
> `@ConditionalOnClass`/`@ConditionalOnMissingBean`) extracting JWT
> verification, exception-to-`ApiResponse` mapping, and correlation-id
> MDC propagation across 4 verify-only services after rule-of-three
> verification.**

**Evidence trail**:
- Common-lib: [common-lib/src/main/java](../../common-lib/src/main/java/com/ecom/common/) — `CommonAutoConfiguration`, `SecurityAutoConfiguration` (Day 7), `GlobalExceptionHandler`, `CorrelationIdFilter`, `BaseEntity`, `ApiResponse`
- JWT lift: Day 7 deleted **16 duplicate files** across 4 services (`JwtAuthenticationFilter` + `JwtVerifier` + `AuthUserPrincipal` + `JwtProperties`), replaced by 5 common-lib classes — see [lesson 07](../lessons/07-refactor-extract-discipline.md)
- Cart concurrency: [CartService.java](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java) HINCRBY → see [lesson 05b redis-data-structures](../lessons/05b-redis-data-structures.md), [issue 05 merge-conflict](../issues/05-cart-merge-conflict-on-login.md)
- Build: Gradle 8.11.1 Kotlin DSL + Version Catalog ([gradle/libs.versions.toml](../../gradle/libs.versions.toml))

**Talking points** (45s pitch):
1. *Virtual threads*: pin trap với `synchronized` → migrate sang `ReentrantLock` khi có; verify bằng JFR `jdk.VirtualThreadPinned`. Day 19 benchmark định lượng.
2. *Common-lib discipline*: rule of three before extracting — Day 1-6 chấp nhận duplicate có chủ ý ở 4 service, Day 7 mới lift. Avoid Hasty Abstraction (Sandi Metz).
3. *Cart Redis primary*: ADR-004 chọn Redis là source of truth không Postgres backup — vì cart loss = inconvenient không lost-money, dual-write = phức tạp không đáng. Mitigation: Sentinel + AOF.

---

## 🧠 Senior interview elevator pitch (90s, dùng cuối screen)

> *"Tôi đang build một ecommerce platform 40-day để ôn senior backend.
> 6 ngày đầu deliver 5 microservice production-grade với Java 21 + Spring
> Boot 3.4.5. Điểm tôi đầu tư sâu là chọn DDD selectively — chỉ inventory
> và order vì chúng có invariant chặt cộng concurrency thật, còn cart và
> product giữ Layered cho đơn giản. Inventory dùng `@Version` optimistic
> lock với `@Retryable` exponential backoff, verify no-oversell qua 100-thread
> Testcontainers integration test. Order dùng sealed `OrderStatus` Java 21
> để compiler enforce state transition exhaustive — thêm permit mới sẽ
> break build mọi switch chưa cover, đó là cách tôi muốn bug fail-fast.
> Cross-cutting concern thì gom vào common-lib — Day 7 vừa rồi tôi lift
> JWT filter từ 4 service lên auto-config sau khi verify rule-of-three,
> xóa 16 file duplicate. Approach của tôi là production-grade ở core,
> production-realistic ở docs (ADR, lesson, issue 9-section, interview
> Q&A) — vì 6 tháng nữa đọc lại tôi vẫn defend được trade-off."*

---

## 🔗 Related

- All 6 day docs: [day-01](day-01-foundation.md) · [day-02](day-02-auth.md) · [day-03](day-03-product.md) · [day-04](day-04-inventory.md) · [day-05](day-05-cart.md) · [day-06](day-06-order.md)
- Mock interview self-grade: [week-01-mock.md](week-01-mock.md) — 9 strong / 1 borderline / 0 fail
- Refactor discipline: [lesson 07](../lessons/07-refactor-extract-discipline.md)
- Portfolio polish (planned Day 38): [`portfolio-pitch-script.md`](portfolio-pitch-script.md) — sẽ build cuối Week 7
