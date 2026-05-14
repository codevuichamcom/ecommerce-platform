# 🎤 Day 6 — Order Service (DDD + Sealed State Machine)

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN (mid-size ecommerce, hậu Day 5 cart đã live).
  Team chuẩn bị end-to-end checkout cho campaign tháng 6.
- **Role giao việc**: Anh Hùng (Tech Lead, ex-Tiki) giao trong sprint
  planning thứ 2 sáng. Brief: *"Order phải DDD đúng nghĩa, không phải
  anemic entity. Status transition — tao đã thấy quá nhiều team viết
  `if (status == 'PAID')` rồi typo. Dùng sealed + pattern matching, để
  compiler kill bug giùm."*
- **Bạn**: Backend dev ownership `order-service` — design domain model
  + sync orchestration (RestClient). Day 9 sẽ convert sang async Kafka.
- **Reviewer**: Anh Hùng review PR — soi: (a) invariant ở constructor
  / method? (b) status transition exhaustive? (c) reserve rồi crash →
  rollback ra sao? (d) test concurrency place-order cùng SKU.
- **Deadline**: 1 sprint day — sáng plan, chiều demo happy path + 1
  failure case ở stand-up.
- **Constraint thực tế**: chưa có Kafka (Day 8). Day 6 sync; outbox +
  event publish ở Day 13. Inventory rollback Day 6 dùng try-catch
  compensation, document trade-off.
- **Definition of Done**: (1) `POST /orders` happy path tạo Order
  PendingPayment + reserve OK; (2) inventory fail → Order không
  persist, compensate prior reservations; (3) sealed `OrderStatus` test
  exhaustive switch; (4) ≥6 unit test invariant Order aggregate pass;
  (5) docs đủ 4 file.

---

## Q1 — Aggregate boundary của Order là gì? Tại sao OrderItem không phải aggregate riêng?

**Strong answer**:

> Aggregate root là `Order`. OrderItem là entity inside aggregate, không
> có lifecycle độc lập, không có repository riêng — caller phải đi qua
> `order.addItem(...)`. Lý do: invariant `total = Σ(item.subtotal)` cần
> atomic update. Nếu tách OrderItem thành aggregate riêng, có
> `OrderItemRepository.save(item)`, total ở Order drift — bug ngầm.
>
> Money + Address là Value Object (Java record + JPA `@Embeddable`) —
> immutable, không có ID, equality theo value.

**Follow-up trap**: *"Order có 100 item → eager fetch nặng?"* → Day 6
chấp nhận EAGER vì write-heavy (place là phổ biến) và 100 item là edge
case. Day 17 N+1 sẽ benchmark + có thể đổi LAZY + JOIN FETCH cho list
order.

---

## Q2 — Sealed interface vs enum cho `OrderStatus`?

**Strong answer**:

> Enum khi state chỉ là label đồng cấu trúc. Sealed khi mỗi state có
> data riêng. Trong Day 6, `Cancelled.reason` không tồn tại ở Paid;
> `Shipped.trackingNumber` không tồn tại ở PendingPayment. Nhồi
> nullable field vào enum = nullable hell + bug at runtime.
>
> Sealed + exhaustive switch (JEP 441) cho compile-time guarantee:
> thêm permit `Refunded` → mọi switch chưa cover sẽ compile error.
> Bug-by-omission tự fail-fast.

**Follow-up trap**: *"`default -> throw new IllegalStateException(...)`
được không?"* → KHÔNG. Default branch kill exhaustive check. Team
convention: cấm default trong switch sealed; ArchUnit rule reject. Đây
là kiểu lỗi AI hay generate vì "phòng hờ" — phải catch ở review.

---

## Q3 — `placeOrder()` reserve inventory rồi crash — xử lý sao?

**Strong answer**:

> Day 6 dùng sync + try-catch compensate:
> 1. Loop reserve từng item, track danh sách `reserved` đã thành công.
> 2. Nếu item N fail → compensate release N-1 prior, rethrow.
> 3. Save Order; nếu save fail → compensate ALL reserved.
> 4. `releaseReservation()` là best-effort, log `ORPHAN-RESERVATION`
>    nếu fail (không throw vì caller không còn gì để rollback).
>
> Trade-off: window orphan 5-30 phút đến khi reconcile job chạy. Day 13
> outbox sẽ chuyển sang event-driven, reserve là async listener với
> retry + DLT — orphan biến mất.

**Follow-up trap**: *"Sao không 2PC?"* → 2PC performance kém + không
scale + vendor lock-in. Modern microservices không dùng. Trade-off
acceptable: eventual consistency + reconcile.

---

## Q4 — Exhaustive switch lợi gì so với if-else chuỗi?

**Strong answer**:

> 3 lợi:
> 1. Compile-time check: thêm permit `Refunded` → compiler báo mọi
>    switch chưa cover. If-else chain không có.
> 2. Pattern matching record component: `case Cancelled(String reason,
>    Instant at) -> ...` — destructure trực tiếp, không cast.
> 3. Đọc clear hơn — mỗi case là 1 dòng intent, không có chain `else if`.
>
> Trap: thêm `default -> ` branch = mất exhaustive. Phải KHÔNG có default.

---

## Q5 — DDD nói "1 tx 1 aggregate". PlaceOrder đụng cart + inventory + order — vi phạm?

**Strong answer**:

> KHÔNG. Rule áp dụng trong cùng bounded context / cùng DB. Cart và
> Inventory ở service khác, DB-per-service — không thể `@Transactional`
> ôm 3 DB qua JDBC.
>
> Cross-aggregate consistency = eventual + compensation (Day 6 sync) /
> saga (Day 13 outbox). `@Transactional` JPA ở `PlaceOrderUseCase` chỉ
> ôm `Order` DB. Inventory call ngoài tx, không tự rollback.

**Follow-up trap**: *"Vậy có cần XA transaction không?"* → KHÔNG.
Trade-off đã discuss Q3.

---

## 🤖 AI Playbook

- **AI làm tốt**: scaffold RestClient method, JPA mapping boilerplate
  (`@Embedded`, `@AttributeOverrides`), sealed interface skeleton +
  permits, REST DTO record, basic happy-path controller.
- **Prompt mẫu**:
  > *"Generate Spring Data JPA Order aggregate. Sealed OrderStatus
  > permits PendingPayment, Paid(Instant), Shipped(String, Instant),
  > Delivered(Instant), Cancelled(String, Instant). Persist 2 column
  > status_type VARCHAR + status_data JSONB via @PostLoad/@PrePersist
  > callback. Show exhaustive switch — NO default branch."*
- **Risk khi AI làm**:
  1. AI thêm `default -> throw` → mất exhaustive.
  2. AI generate `OrderItem.setQuantity(int)` public setter → vỡ
     aggregate boundary.
  3. AI viết `placeOrder()` reserve TRƯỚC save → crash giữa chừng =
     orphan không trace được. Order: save Order PendingInventory →
     reserve → update Paid (Day 6 đơn giản hơn: reserve trước save +
     compensate).
  4. AI dùng `@TransactionalEventListener(AFTER_COMMIT)` sai phase →
     event miss khi rollback.
- **Validate output**:
  - Đọc switch trên `OrderStatus` — KHÔNG có `default ->` branch.
  - `OrderItem` constructor package-private, no public setter.
  - Thử thêm permit `Refunded` vào sealed — phải compile error ở mọi
    switch chưa update (test bằng cách thêm + run `gradle build`).
  - Chạy 9 unit test aggregate + 5 serialization test — phải PASS.

---

## 👥 Tech Lead Lens (Day 6 trigger)

- **Trade-off chính**: sync Feign/RestClient orchestration vs async
  saga. Day 6 chọn sync vì faster ship + dễ debug. **Scale 10x** (1000
  order/sec): sync tail-latency vỡ do cộng dồn (cart 30ms + N×inventory
  20ms + DB 30ms). Đổi sang outbox + Kafka Day 13, response ngay sau
  persist outbox + emit event, downstream eventual ~1-2s. Monitor
  `outbox.lag.seconds` p99 < 3s.

- **Production failure mode**: inventory timeout giữa lúc reserve →
  Order chưa save nhưng N item đã reserved. **5-step triage**:
  1. Grep `correlation_id` trong MDC log ở order-service + inventory-service.
  2. So sánh timestamps: order.created_at (nếu có) vs
     stock_reservation row ở inventory_db.
  3. Check Resilience4j circuit breaker state inventory client (Day 12).
  4. Nếu confirm orphan → manual compensate qua admin API hoặc trigger
     reconcile job.
  5. Post-mortem: tại sao timeout — DB lock contention?, GC pause?,
     network blip? Tune timeout / thread pool / pod resource.

- **Junior + AI 2 lỗi dễ nhất**:
  1. **Save Order TRƯỚC reserve**: AI nghĩ "ghi DB trước cho chắc" →
     reserve fail → Order treo ở PendingPayment mà không có stock. UX
     xấu + customer thấy đơn hàng đang chờ thanh toán mà thực ra hết
     hàng. Reviewer phải check thứ tự: reserve → save → (compensate
     nếu save fail).
  2. **`applicationEventPublisher.publishEvent()` trong `@Transactional`**:
     listener default chạy SAU `commit` mặc định KHÔNG có
     `@TransactionalEventListener(AFTER_COMMIT)`. AI hay quên — kết
     quả: event publish trước commit, nếu rollback → consumer nhận
     event nhưng DB không có. Day 13 outbox khắc phục triệt để.

---

## 🔗 Related

- Code: [`Order.java`](../../services/order-service/src/main/java/com/ecommerce/order/domain/Order.java), [`OrderStatus.java`](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderStatus.java), [`PlaceOrderUseCase.java`](../../services/order-service/src/main/java/com/ecommerce/order/application/PlaceOrderUseCase.java)
- Architecture [`order-domain.md`](../architecture/order-domain.md)
- Lessons [`06-aggregate-root.md`](../lessons/06-aggregate-root.md), [`06b-sealed-types-state-machine.md`](../lessons/06b-sealed-types-state-machine.md)
- Issue [`06-orchestration-rollback.md`](../issues/06-orchestration-rollback.md)
- ADR [`003-ddd-for-order-inventory-payment.md`](../decisions/003-ddd-for-order-inventory-payment.md)
