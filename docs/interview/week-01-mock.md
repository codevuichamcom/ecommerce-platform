# 🎤 Week 1 Mock — Senior Backend (Tiki/Shopee level)

> **Day**: 7 — kết tuần 1, 5 service đã merge `master`.
> **Format**: 10 câu (5 System Design + 5 Spring/DDD/Concurrency).
> Self-grade brutally honest — câu nào borderline tuần sau ưu tiên ôn.

---

## 🏢 Bối cảnh giả lập (round phỏng vấn)

- **Company**: Tiki (mock — bạn apply Senior Backend, đã pass screen).
- **Interviewer**: Anh Việt (Tech Lead Order team, ex-Lazada). 60 phút,
  chia 30/30 System + Code-deep. Style A — brutally honest, không du di.
- **Bạn**: Senior Backend candidate. Vừa nộp portfolio link (project
  ecommerce-platform 6 day đầu).
- **Constraint**: chỉ được dùng laptop để vẽ Mermaid hoặc viết code; không
  search Google. Mỗi câu ≤ 5 phút, đủ ý chính + 1 trade-off rõ.
- **Definition of pass**: ≥ 7/10 câu **strong** (không borderline). Borderline
  ≥ 4 = recommend Mid, không Senior.

---

# Phần 1 — System Design (5 câu)

## Q1 — Vẽ high-level architecture của project. Chỉ 1 chỗ trade-off correctness lấy availability

**Strong answer**:

> 5 service hiện tại + 4 sắp build, tách theo bounded context: auth (issuer
> JWT), product (catalog + search LIKE → ES Day 22), cart (Redis primary),
> inventory (DDD aggregate Stock), order (DDD aggregate Order). DB-per-service.
> Cross-service Day 6 dùng RestClient sync; Day 8+ chuyển Kafka async.
>
> **Trade-off correctness lấy availability**: Day 6 `placeOrder()` orchestrate
> cart → inventory.reserve → save Order. Nếu reserve thành công nhưng save
> Order fail giữa chừng → compensate release best-effort, log
> `ORPHAN-RESERVATION`. Tôi **chấp nhận** orphan reservation hiếm (fail
> giữa Postgres write) thay vì 2PC distributed transaction — vì 2PC giảm
> availability của cả 2 service xuống tích, và orphan có thể reconcile
> bằng job nightly + TTL trên reservation row. Day 13 outbox sẽ làm chặt
> hơn.

**Follow-up trap**: *"Sao không 2PC?"*

> 2PC đòi coordinator có lock prepare ở cả 2 DB → throughput thảm họa.
> Latency tail tăng N lần với N participant. Trong ecom flash sale, Tiki
> không bao giờ accept. Saga + idempotent compensation mới là chuẩn
> production.

**Self-verdict**: ✅ **strong**. Có metric defendable, có rationale, có
plan tiến hóa (Day 13). Không lúng túng.

---

## Q2 — Why DDD cho inventory + order, layered cho cart + product?

**Strong answer**:

> Tôi dùng 3-điểm criteria từ ADR-003: cần **(a) ≥ 3 invariant**,
> **(b) concurrency thật**, **(c) domain event ra ngoài**. Đủ 3 → DDD.
> Thiếu 1 → Layered (đơn giản hơn).
>
> - **Inventory**: invariant `reserved ≤ quantity`, `reserved ≥ 0`,
>   `quantity ≥ 0`. Concurrency 100 user reserve cùng SKU. Event
>   `StockReserved` cho order/payment. Đủ 3 → DDD.
> - **Order**: invariant `total = Σ subtotal`, transition `PendingPayment
>   → Paid → Shipped → Delivered` chỉ theo chiều, terminal state immutable.
>   Concurrency: idempotency key chống double-submit. Event `OrderPlaced`,
>   `OrderCancelled`. Đủ 3 → DDD.
> - **Cart**: invariant đơn giản (qty ≥ 0, ≤ 999), concurrency có nhưng
>   Redis HINCRBY atomic xử ở data layer chứ không cần aggregate. Không
>   có domain event ra ngoài (cart là user state, không trigger domain
>   logic ở service khác). 1/3 → Layered.
> - **Product**: catalog CRUD đơn giản. Không có 3 invariant đáng kể.
>   Layered.

**Follow-up trap**: *"Product có category hierarchy phức tạp, sao không DDD?"*

> Hierarchy phức tạp ≠ business invariant. Product có invariant gì? Tên
> non-blank, price ≥ 0 — đó là validation đơn thuần, JPA `@NotNull`/`@Min`
> đủ. Khi nào nâng cấp DDD: nếu thêm rule "product hết hàng phải auto-hide"
> (cross-aggregate event với inventory), hoặc "discount rule có 3 strategy
> + scheduled" — đến lúc đó re-evaluate.

**Self-verdict**: ✅ **strong**. Defend cả 4 service, có ngưỡng nâng cấp.

---

## Q3 — Inventory bị oversell trong promotion. Mày debug từ đâu?

**Strong answer**:

> 5 step, theo issue 04 9-section đã build:
>
> 1. **Reproduce**: query `SELECT sku, quantity, reserved, sold FROM
>    stocks WHERE reserved + sold > quantity` — nếu có row → confirm
>    oversell; assert order count cùng SKU > stock.
> 2. **Metric**: Micrometer `stock.reserve.fail` count + `stock.reserve.retry`
>    count. Nếu retry cao bất thường → optimistic lock đang bị bỏ qua.
> 3. **Log**: trace từng request qua `traceId` (MDC). Tìm 2 reserve cùng
>    SKU thành công với version giống nhau → optimistic lock break.
> 4. **DB layer**: `EXPLAIN ANALYZE UPDATE stocks SET reserved = ... WHERE
>    id=? AND version=?` — đảm bảo plan dùng index, không seq scan. Check
>    isolation level (`READ COMMITTED` default Postgres OK với optimistic;
>    nếu setup nhầm `REPEATABLE READ` thì optimistic vẫn đúng nhưng retry
>    nhiều).
> 5. **Fix**: nếu lock bị bypass → check có ai gọi `repository.save()` mà
>    không qua aggregate `Stock.reserve()`; nếu retry exhaust → tăng
>    `@Retryable(maxAttempts=4, backoff exp 50→500ms)` (đã làm Day 4); nếu
>    còn fail → defense-in-depth DB CHECK constraint `reserved ≤ quantity`
>    đảm bảo invariant không bao giờ violate ở DB.

**Follow-up trap**: *"Postgres replica lag, optimistic lock ở primary đủ chưa?"*

> Optimistic lock chỉ chạy trên primary (write transaction). Replica lag
> ảnh hưởng read-after-write — nếu user thấy stock dương rồi reserve fail
> ở primary, đó là race UI chứ không phải oversell. Để chống UI race:
> read-your-write qua sticky session hoặc query primary cho stock check
> ở checkout (slight perf hit nhưng correct).

**Self-verdict**: ✅ **strong**. Có 5 step rõ + defense-in-depth. Có
follow-up đúng.

---

## Q4 — Cart Redis primary. Redis chết thì sao?

**Strong answer**:

> Day 5 ADR-004 chấp nhận trade-off: Redis chết → user mất cart phiên
> hiện tại. KHÔNG fallback Postgres vì:
>
> 1. **Cart là user state, không phải transactional data** — khác stock /
>    order. Mất cart = inconvenient, không lost-money.
> 2. **Add Postgres backup = dual-write problem**: race condition giữa
>    Redis và Postgres, sync drift, eventual consistency window phức tạp
>    cho data mà user accept tổn thất.
> 3. **Redis HA mitigation**: Sentinel + AOF persistence (fsync every-sec)
>    + replica → mất tối đa 1s data khi failover. Đủ với product manager.
>
> Day 24 sẽ document trong polyglot persistence anti-pattern: cart là case
> textbook *"đừng dual-write nếu data có thể lose"*.

**Follow-up trap**: *"Khi nào nâng cấp lên Redis Cluster?"*

> Threshold capacity: > 10k cart/sec write hoặc > 50GB cart data. Tiki
> Black Friday đợt cao điểm có thể đập 30k cart-mutation/sec — lúc đó cần
> Cluster sharding theo `cart:{user|anon}:{id}` hash. Single Redis
> standalone hiện tại đủ DAU 100k.

**Self-verdict**: ✅ **strong**. Defend trade-off bằng số + có plan
scale-up.

---

## Q5 — Order placement Day 8+ chuyển Kafka. Saga choreography hay orchestration?

**Strong answer**:

> Tôi chọn **orchestration** với state machine ở `Order` aggregate. Lý do:
>
> 1. **Order đã là aggregate root với sealed `OrderStatus`** — natural fit
>    làm orchestrator. Choreography = mỗi service tự react event → khó
>    trace flow tổng, khó xử partial failure (inventory reserve OK,
>    payment fail, ai cancel order? Order biết hay không?).
> 2. **Recovery rõ ràng**: order biết "tao đang chờ payment 5 phút rồi";
>    timeout → publish `PaymentTimeout` → tự cancel + release. Choreography
>    tương tự nhưng phân tán logic này khắp nơi.
> 3. **Test dễ**: state machine đơn vị test với mock event; choreography
>    phải spin up cả mesh.
>
> **Trade-off**: orchestrator coupling lớn hơn — Order biết về cart,
> inventory, payment. Chấp nhận vì Order là context root của
> place-order flow; không vi phạm bounded context (cart/inventory/payment
> tự enforce invariant nội bộ).
>
> Day 13 outbox đảm bảo orchestrator publish event reliable (no lost
> event between Order DB write và Kafka publish).

**Follow-up trap**: *"Orchestrator down giữa chừng?"*

> Outbox + state persistence — Order state ở DB, sau restart tiếp tục.
> Inventory/payment thấy duplicate event → idempotent xử lý (dedup theo
> `eventId` unique). Worst case: Order ở `PendingPayment`, payment đã
> thành công nhưng event chưa consume → reconciliation job cuối ngày
> match payment provider statement với order DB.

**Self-verdict**: ✅ **strong**. Có rationale chọn orchestration + plan
xử failure.

---

# Phần 2 — Spring / DDD / Concurrency (5 câu)

## Q6 — `@Version` optimistic lock — explain mechanism + khi nào fail?

**Strong answer**:

> Hibernate generate `UPDATE stocks SET ... , version = version + 1 WHERE
> id = ? AND version = ?`. Nếu row count = 0 → throw
> `OptimisticLockingFailureException`. Mechanism: 2 transaction đọc cùng
> version=5, một commit trước (version → 6), transaction còn lại update
> với `WHERE version = 5` → 0 row → fail.
>
> **Khi nào fail nhiều**:
>
> 1. **Long transaction**: read xong giữ context lâu (gọi external API,
>    lock chờ user input). Risk khác commit trước.
> 2. **High contention**: cùng 1 SKU 100 user reserve cùng lúc → 1 success,
>    99 fail → retry. Nếu retry không exponential backoff → thundering
>    herd, retry storm.
> 3. **Bad UI flow**: load form, đợi user 5 phút, submit → version đã
>    stale từ lâu.
>
> Day 4 fix: `@Retryable(OptimisticLockingFailureException, maxAttempts=4,
> backoff exp 50→500ms, REQUIRES_NEW)` để retry trong transaction mới
> (REQUIRES_NEW critical — cùng transaction sẽ rollback toàn bộ).

**Follow-up trap**: *"Vs `SELECT FOR UPDATE`?"*

> Pessimistic = lock row ở DB level. Pros: không retry, deterministic.
> Cons: throughput thấp ở high contention (mọi reader bị block), risk
> deadlock, connection pool exhaustion (long-held lock). Optimistic
> phù hợp khi conflict rate < 30%; pessimistic khi conflict > 50% hoặc
> business cần queue order. Day 4 chọn optimistic vì stock contention
> trung bình; flash sale Day 33 sẽ design Redis Lua atomic decrement
> + queue (cách 3, không phải optimistic).

**Self-verdict**: ✅ **strong**. Có ngưỡng số (30% / 50%), có alternative.

---

## Q7 — Sealed `OrderStatus` permits 5 state. `default ->` trong switch được không?

**Strong answer**:

> KHÔNG. `default ->` kill exhaustive check (JEP 441). Mất cái lợi chính
> của sealed: thêm permit `Refunded` vào enum sẽ gặp **compile error ở
> mọi switch chưa cover** — bug-by-omission tự fail-fast. Có `default`
> = compile pass nhưng `Refunded` rơi vào default branch → bug runtime.
>
> Team convention: cấm `default` trong switch sealed. ArchUnit rule có
> thể enforce. Đây là pattern AI hay generate vì "phòng hờ" — phải catch
> ở review.

**Follow-up trap**: *"Thêm `Refunded` permit, mày phát hiện ở đâu?"*

> Compiler. Mỗi switch không cover `Refunded` sẽ báo "switch is not
> exhaustive". Day 6 có 2 switch trong `Order`: `transitionTo()` và
> `isTerminal()`. Thêm permit, compile fail 2 chỗ → tôi handle cả 2.
> Đó là lý do chọn sealed thay enum: compiler giữ giùm.

**Self-verdict**: ✅ **strong**. Defend cả pattern + tooling enforce.

---

## Q8 — Virtual thread pinning — kể 1 case mày phải fix

**Strong answer**:

> Virtual thread carrier thread bị pin (giữ chặt platform thread, không
> unmount khi blocking) khi:
>
> 1. **`synchronized` block / method** (JEP 491 fix sắp tới Java 24,
>    hiện Java 21 vẫn pin).
> 2. **Native method có blocking**.
> 3. **`Object.wait()`** (đã fix ở Java 21 với JEP 444).
>
> Day 2 auth-service từng có `synchronized` quanh `tokenStore` — bench thấy
> throughput không scale theo số virtual thread. Fix: chuyển `ReentrantLock`
> (unmount đúng khi `lock.lock()` block). Verify bằng JFR event
> `jdk.VirtualThreadPinned` — sau fix event count = 0.

**Follow-up trap**: *"JDBC driver dùng synchronized internal?"*

> Đa số driver hiện đại (HikariCP + Postgres JDBC 42.7+) đã rewrite từ
> `synchronized` sang `ReentrantLock` cho VT compat. Nếu driver cũ vẫn
> pin → workaround: tăng `jdk.virtualThreadScheduler.maxPoolSize` để có
> thêm carrier, hoặc tách JDBC call qua dedicated platform thread pool
> với `Executors.newCachedThreadPool()`. Chấp nhận trade-off chứ không
> vứt VT.

**Self-verdict**: 🟡 **borderline**. Tự kể được mechanism + tool verify
(JFR), nhưng case Day 2 thật sự không đụng synchronized — đây là tôi
kể "as if" theo lesson đã đọc. Tuần 2 cần thực sự bench + JFR trace
để có ammo thật. **Gap to fix**.

---

## Q9 — `open-in-view: false` — tại sao mặc định Spring Boot bật, mày tắt?

**Strong answer**:

> OSIV (Open Session In View) bật mặc định để DX dễ — entity vẫn lazy-load
> được ở view layer (Thymeleaf, JSP). Hệ quả production:
>
> 1. **N+1 silent**: serialize entity → trigger lazy fetch khắp nơi → 1
>    request 100 query.
> 2. **Connection bị giữ**: DB connection bound theo lifecycle request
>    (kể cả sau service layer xong) → connection pool exhaustion ở high
>    QPS.
> 3. **Entity leak ra response**: Hibernate proxy `hibernateLazyInitializer`
>    bị Jackson serialize → response có field nội bộ (issue 03 Day 3).
>
> Day 3 product-service tắt `open-in-view: false` + ép DTO projection
> (MapStruct compile-time) + assert response không có
> `hibernateLazyInitializer` (test Testcontainers).

**Follow-up trap**: *"Tắt rồi gặp `LazyInitializationException` ở response — fix sao?"*

> 3 cách: (a) `@EntityGraph` ở repository method để fetch eager khi cần;
> (b) `JOIN FETCH` JPQL; (c) DTO projection với MapStruct (lựa chọn Day 3 —
> compile-time, không reflection, force developer nghĩ về data shape
> trước khi viết query). Tôi chọn (c) vì nó là wall: developer không
> thể "tiện tay" trả entity ra response.

**Self-verdict**: ✅ **strong**. Có 3 hệ quả + 3 fix + chọn rationale.

---

## Q10 — BCrypt 72-byte truncation trap — kể lại

**Strong answer**:

> BCrypt thuật toán gốc giới hạn input 72 byte; byte > 72 bị silent
> truncate (KHÔNG throw). Hậu quả: password 80 ký tự với prefix giống
> verify thành công với password 72 ký tự khác → security hole.
>
> Day 2 fix: validate `@Size(min=8, max=72)` ở DTO `RegisterRequest`.
> Spring `@Valid` ép trả 400 với message rõ trước khi chạm `BCryptPasswordEncoder`.
> Test case: password 73 byte UTF-8 → expect 400 VALIDATION_FAILED, không
> phải 200.

**Follow-up trap**: *"Sao không hash SHA-256 trước rồi BCrypt? Để password
dài bao nhiêu cũng được?"*

> Anti-pattern. SHA-256 output 32 byte, hex/base64 hóa → 64-44 byte → vẫn
> trong giới hạn 72, có vẻ "OK". Nhưng 2 vấn đề:
>
> 1. **Null byte attack**: SHA-256 binary có thể chứa byte 0x00; BCrypt
>    cũ (legacy) treat 0x00 là string terminator → truncate sớm hơn 72.
> 2. **Conceptual**: thêm pre-hash là tự design crypto — luôn sai. Dùng
>    Argon2id (winner Password Hashing Competition 2015) hoặc giới hạn
>    input length là chuẩn.
>
> Project hiện dùng BCrypt cost=10. Khi nào upgrade Argon2id: nếu CPU
> hash budget cho phép (Argon2 đắt hơn ~3-5x) hoặc audit security yêu
> cầu.

**Self-verdict**: ✅ **strong**. Trả lời được trap + có direction Argon2id.

---

# 📊 Self-grade

| Q | Topic | Verdict | Note |
|---|-------|---------|------|
| 1 | Architecture + trade-off | ✅ strong | |
| 2 | DDD vs Layered criteria | ✅ strong | |
| 3 | Oversell debug | ✅ strong | |
| 4 | Redis primary + failure | ✅ strong | |
| 5 | Saga orchestration vs choreography | ✅ strong | |
| 6 | Optimistic lock | ✅ strong | |
| 7 | Sealed switch | ✅ strong | |
| 8 | VT pinning | 🟡 **borderline** | Case Day 2 không thực sự đụng synchronized — kể as-if. Cần ammo thật từ Day 19 benchmark. |
| 9 | open-in-view | ✅ strong | |
| 10 | BCrypt 72-byte | ✅ strong | |

**Score**: 9 strong / 1 borderline / 0 fail. **Pass Senior** với note ôn
thêm VT pinning thật ở Day 19.

## 🎯 Gap to fix tuần 2

1. **VT pinning case thật**: Day 19 sẽ JMH benchmark + JFR `jdk.VirtualThreadPinned`
   trên auth-service hoặc cart-service. Có data thật mới defend được "tôi
   từng fix VT pinning" mà không cảm giác nói lý thuyết.
2. **Read source Day 4 inventory** — câu Q3 trả lời tốt nhưng dựa nhiều
   vào docs đã build. Tuần 2 tự nói lại từ memory (no docs) để kiểm tra
   nội hóa thật chưa.

---

## 🤖 AI Playbook

- **Phần AI làm tốt**: generate skeleton 10 Q + strong-answer outline; gợi
  follow-up trap mỗi câu; check trùng câu hỏi giữa các day để mock không
  nhàm.
- **Prompt mẫu**:
  ```
  Đọc 6 file docs/interview/day-0{1..6}-*.md. Generate 10 mock interview
  question Senior level, mix 5 System Design + 5 Spring/DDD/Concurrency.
  Mỗi câu kèm 1 follow-up trap. KHÔNG hỏi câu đã có trong file.
  ```
- **Risk**: AI tự chấm "strong" cho mọi câu — tôi phải tự nói thành tiếng
  60s, lúng túng = chấm borderline thật. Q8 chính là ví dụ — AI không
  biết tôi không có case thật.
- **Validate**: (a) tự nói thành tiếng từng câu; (b) câu nào dùng số / case
  cụ thể → check số có thật trong docs / commit không; (c) câu nào kể
  "tôi từng fix" → verify đã fix thật trong code.

---

## 🔗 Related

- Day docs evidence: [day-01](day-01-foundation.md) · [day-02](day-02-auth.md) · [day-03](day-03-product.md) · [day-04](day-04-inventory.md) · [day-05](day-05-cart.md) · [day-06](day-06-order.md)
- Issue references: [04 oversell](../issues/04-overselling-stock.md) · [03 entity-leak](../issues/03-entity-leak-in-response.md) · [05 cart-merge](../issues/05-cart-merge-conflict-on-login.md) · [06 orchestration-rollback](../issues/06-orchestration-rollback.md)
- ADR: [001](../decisions/001-why-hybrid-architecture.md) · [002](../decisions/002-jwt-vs-session.md) · [003](../decisions/003-ddd-for-order-inventory-payment.md) · [004](../decisions/004-redis-primary-for-cart.md)
- CV bullets compiled: [week-01-cv-bullets.md](week-01-cv-bullets.md)
