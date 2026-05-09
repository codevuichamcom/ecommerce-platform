# Interview — Day 4: Inventory Service (DDD + Optimistic Locking)

> **Status**: ✅ Done · 2026-05-09
> **Mục tiêu**: drill câu phỏng vấn Senior/Tech Lead về DDD, optimistic lock, race condition, isolation level — với bối cảnh kể được story production.

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — ecommerce Series-A Việt Nam, GMV ~80B/tháng, đang chạy flash sale Tết khuyến mãi 12.12.
- **Role giao việc**: Anh Hùng (Tech Lead, ex-Tiki). Họp standup sáng: "Lần flash sale tháng trước **oversell 47 đơn iPhone** → finance phải refund + apologize trên fanpage. Ông build cho tôi inventory-service mới, ép invariant `reserved ≤ quantity` ở tầng aggregate, KHÔNG tin tưởng API caller."
- **Bạn**: Backend engineer L4, owner inventory domain.
- **Reviewer**: Anh Hùng (TL) + Chị Mai (QA Lead, sẽ chạy concurrency test 100 thread cùng SKU).
- **Deadline**: 1 sprint day (4 giờ effective). Demo: chạy được test 100-thread reserve cùng SKU, log show retry on `OptimisticLockException`, final stock đúng — không oversell.
- **Constraint thực tế**:
  - Phải dùng Postgres (đã chốt stack), KHÔNG được introduce Redis stock counter ở day này (để Day 33 system-design).
  - DB-per-service: KHÔNG được join sang `products` schema của product-service.
  - Phải pre-publish skeleton domain event `StockReserved` / `StockReleased` (Day 9 sẽ wire vào Kafka outbox).
- **Definition of Done**:
  - 100-thread concurrency test: 0 oversell, stock cuối khớp công thức `initial - successful_reservations`.
  - `Stock` aggregate enforce invariant ở constructor + method, không phải ở service layer.
  - ADR-003 commit kèm code (DDD criteria 3 điểm).
  - Lesson 04 + 04b + Issue 04 9-section.

---

## 🎤 Q&A

### Q1 — "Tại sao em chọn optimistic lock thay vì pessimistic `SELECT FOR UPDATE`?"

**Strong answer**: Optimistic phù hợp với contention thấp đến trung bình của workload MVP — <500 RPS/SKU. Throughput cao hơn vì không khóa DB, retry rẻ (50-200ms backoff). Pessimistic sẽ serialize toàn bộ request cùng SKU qua lock queue → P99 tăng theo số request đang chờ. Với inventory check-out flow, P99 latency là KPI quan trọng (UX checkout).

**Trap**: "Nếu hot SKU 10k RPS thì sao?" → Đây là edge case, đã có migration path: monitor metric `optimistic_lock_retry_total{sku=?}`, khi p99 retry > 5/sec/SKU → upgrade Redis Lua atomic decrement (Day 33 system-design). Không premature optimize.

### Q2 — "`@Version` hoạt động thế nào dưới gầm? Hibernate generate SQL ra sao?"

**Strong answer**: Hibernate generate `UPDATE stock SET quantity=?, reserved=?, version=? WHERE sku=? AND version=?`. Trước UPDATE, persistence context có version=N. UPDATE set version=N+1 với điều kiện row hiện tại version=N. Nếu tx khác đã commit version mới giữa SELECT và UPDATE của tx mình → affected_rows=0 → Hibernate throw `OptimisticLockException`, Spring wrap thành `ObjectOptimisticLockingFailureException`. Application `@Retryable` catch → retry với fresh load.

**Trap**: "Nếu UPDATE thành công nhưng response timeout, client retry — có double-reserve không?" → Có. Đây là idempotency problem, KHÁC optimistic lock. Cần idempotency token (request UUID) ở tầng caller (Day 10 payment). Optimistic lock không giải vấn đề này.

### Q3 — "Aggregate root khác Entity thường ở đâu? Có thể có 2 aggregate root trong 1 bounded context không?"

**Strong answer**: Aggregate root là **consistency boundary** — entry point duy nhất để modify state của aggregate, đảm bảo invariant nhất quán. Entity con bên trong aggregate chỉ access qua root. Có thể có nhiều aggregate root trong 1 bounded context — mỗi aggregate là 1 boundary riêng. Vd: bounded context `inventory` có thể có aggregate `Stock` (theo SKU) và `Warehouse` (theo location). Quan trọng: cross-aggregate update phải eventual consistency (event), KHÔNG cùng tx — đó là lý do domain event tồn tại.

**Trap**: "Repository có thể inject vào Aggregate không?" → Không nên. Aggregate phải pure domain, không biết persistence. Nếu cần load thêm → inject vào application service, load rồi pass vào method.

### Q4 — "Postgres `READ COMMITTED` default — chống được lost update không?"

**Strong answer**: KHÔNG. RC chỉ chặn dirty read. Lost update (2 tx đọc giá trị cũ → cùng UPDATE → 1 update bị mất) RC không chặn. Cần thêm: optimistic lock (`@Version`), pessimistic `SELECT FOR UPDATE`, hoặc nâng isolation lên `REPEATABLE READ`/`SERIALIZABLE` (Postgres sẽ throw serialization error, caller retry).

**Trap**: "MySQL `REPEATABLE READ` chặn phantom read không?" → Khác cơ chế. Postgres `REPEATABLE READ` = snapshot isolation chặn phantom qua MVCC. MySQL InnoDB chặn phantom bằng next-key lock chỉ khi `SELECT FOR UPDATE/SHARE`; `SELECT` thường vẫn có phantom. Đây là điểm AI/junior hay confuse.

### Q5 — "Khi nào em sẽ KHÔNG dùng DDD?"

**Strong answer**: Khi không thỏa **3-điểm criteria** (xem ADR-003): (1) ≥3 invariants thật, (2) concurrency thật có race condition khi sai, (3) phát domain event ra ngoài bounded context. Ví dụ: `cart-service` chỉ là Redis CRUD, 1 user 1 cart, không invariant phức tạp → Layered. `notification-service` consume Kafka → SMTP, KHÔNG có state mutation → Layered. Áp DDD bừa cho mọi service là cargo-cult; junior + AI sẽ tạo Aggregate giả (anemic) — code phình mà không có giá trị invariant thật.

**Trap**: "Vậy `auth-service` có refresh token rotation race — sao không DDD?" → Race đó đã giải bằng atomic UPDATE + DB-level check ở Day 2, không cần aggregate. DDD ROI = invariant thực sự CẦN aggregate boundary để enforce, không phải mọi race condition đều cần.

---

## 🧠 Senior mindset notes

- **Aggregate là consistency boundary, không phải data structure**. Nhiều junior tạo `OrderAggregate` chứa cả Order + Customer + Address — sai. Customer là 1 aggregate riêng. Boundary càng nhỏ càng tốt cho concurrency.
- **Domain event raise trong aggregate, publish AFTER_COMMIT**. Nếu publish ở method `reserve()` trực tiếp → tx rollback sẽ leak event → consumer xử lý event "phantom". Day 9 sẽ wire `@TransactionalEventListener(phase = AFTER_COMMIT)` + outbox.
- **Optimistic lock không phải silver bullet**. Khi contention vượt threshold → retry storm → cascade thread pool starvation. Phải có metric + circuit breaker cho retry exhausted.
- **DB CHECK constraint là defense-in-depth**, không phải primary defense. App layer (aggregate) là primary; DB là safety net cho admin SQL adhoc / data migration sai. Nếu chỉ dựa CHECK → app throw `DataIntegrityViolationException` raw → UX tệ + log noise.
- **TOCTOU (Time of Check vs Time of Use)** là pattern AI/junior hay viết sai: `if (stock.available() >= qty) { stock.reserve(qty); }` — pass check rồi mới reserve, race ở giữa. Fix: chỉ gọi `stock.reserve(qty)`, để aggregate tự throw.

---

## 🤖 AI Playbook

- **AI làm tốt / nên giao**: scaffold Flyway migration, generate test boilerplate (100-thread `ExecutorService` template), draft 4 approach trade-off table cho ADR/issue, generate Spring Retry config với backoff exponential.
- **Prompt mẫu** (4 dòng):
  > "Generate JUnit5 test using Testcontainers Postgres `@ServiceConnection`, 100 concurrent threads call `InventoryService.reserve(sku, 1)` cùng 1 SKU stock=50. Assert: success_count=50, InsufficientStockException_count=50, final reserved=50 in DB. Skip default unless env var `RUN_INVENTORY_INTEGRATION_TESTS=true`."
- **Risk khi để AI làm phần đó**:
  1. AI hay viết invariant ở service layer (procedural style) — `if (stock.available() >= qty) { stock.reserve(qty); }`. TOCTOU race condition.
  2. AI generate `@Retryable` thiếu `@Transactional(propagation=REQUIRES_NEW)` — retry trong cùng tx, persistence context stale → retry vô ích.
  3. AI hay quên bind exception class: dùng `OptimisticLockException` (JPA) thay vì `OptimisticLockingFailureException` (Spring) → `@Retryable` không catch.
  4. AI generate Aggregate có `@Setter` Lombok ở mọi field → caller bypass invariant.
- **Cách validate output**:
  1. Đọc `Stock.java` — fields phải `private`, KHÔNG có public setter; chỉ có factory + domain methods.
  2. Chạy concurrency test thật, KHÔNG trust mock. Assert final state ở DB qua `repository.findById`, không qua method.
  3. Xem Hibernate SQL log (`logging.level.org.hibernate.SQL=DEBUG`): UPDATE phải có `WHERE sku=? AND version=?`.
  4. Test edge: reserve âm, reserve 0, reserve > available — phải throw exception phù hợp (IllegalArgument vs BusinessException).

---

## 👥 Tech Lead Lens (Day 4 trigger — DDD adoption decision)

- **Trade-off chính + scale 10x**: DDD ở inventory mua **invariant safety** + **domain event clarity** với giá codebase phức tạp (4 layer thay vì 2). Scale 10x: invariant boundary trở thành cứu cánh — refactor sang event-sourcing dễ vì domain đã tách. Nếu chọn Layered từ đầu, scale 10x phải rewrite. Optimistic lock 10x → retry storm: signal đến lúc upgrade Redis Lua (Day 33 design), code aggregate KHÔNG đổi (chỉ thay storage adapter).

- **Production failure mode + 5-step triage**: Optimistic retry storm + thread pool starvation cascade.
  1. **Grafana** xem `optimistic_lock_retry_total{sku=?}` — SKU nào spike?
  2. **Postgres** `pg_stat_activity` xem có connection block ở UPDATE không.
  3. **Thread dump** (`jstack` trên pod) — xem có thread đang spin retry không.
  4. **Hot-fix** (15 phút): tăng `maxAttempts` từ 4 → 6 + add circuit breaker per-SKU (Resilience4j Day 12) → bảo vệ thread pool. Hoặc disable SKU đó tạm (config flag).
  5. **Long-fix** (1-2 sprint): Redis decrement + Lua atomic (Day 33 design) cho hot SKU; route request qua sticky-key cache.

- **Junior + AI 2 lỗi dễ nhất**:
  1. **Invariant ở service layer (TOCTOU)**: viết `if (stock.available() >= qty) throw; stock.reserve(qty);` — pass check rồi reserve, race ở giữa. **Code review BẮT BUỘC**: aggregate phải tự throw, service chỉ gọi method.
  2. **Quên `@Retryable` hoặc cấu hình sai**: mỗi `OptimisticLockException` → 500 trả client → client tự retry → cascade load DB. Review kỹ: có `@Retryable`? `retryFor` đúng class Spring? `propagation=REQUIRES_NEW`? Có `@Recover` để fail-fast khi exhausted? Có metric expose retry count?

---

## 🔗 Related

- [ADR-003 — DDD selective](../decisions/003-ddd-for-order-inventory-payment.md)
- [Lesson 04 — Optimistic locking](../lessons/04-optimistic-locking.md)
- [Lesson 04b — Transaction isolation](../lessons/04b-transaction-isolation.md)
- [Issue 04 — Overselling stock](../issues/04-overselling-stock.md)
- Code: [`Stock.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/Stock.java) · [`InventoryService.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventoryService.java)
- Test: [`InventoryConcurrencyIT.java`](../../services/inventory-service/src/test/java/com/ecom/inventory/InventoryConcurrencyIT.java)
