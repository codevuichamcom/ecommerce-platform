# ADR-007 — payment-service dùng Layered, KHÔNG DDD

- **Status**: ✅ Accepted
- **Date**: 2026-05-19
- **Deciders**: Tonny (backend lead), Anh Hùng (Tech Lead — review)
- **Supersedes**: bổ sung cho [ADR-003 DDD for order/inventory/payment](003-ddd-for-order-inventory-payment.md) — sau khi rà 3-điểm criteria thực tế, payment KHÔNG đủ tiêu chí DDD đầy đủ. Revise scope ADR-003.

## Decision

`payment-service` build theo **Layered architecture** (controller → service → repository), KHÔNG full DDD Aggregate Root. Vẫn dùng **sealed interface `PaymentStatus`** cho state machine (modernity Java 21 — CLAUDE.md không cấm Layered dùng sealed).

## Context

ADR-003 (Day 4) định nghĩa **3-điểm criteria** để chọn DDD:
1. ≥3 invariants nontrivial cần atomic enforce.
2. Concurrency thật (race condition không trivial).
3. Có domain events ra ngoài service.

Lúc đó Tonny ghi `payment-service` vào nhóm DDD vì có "payment intent state machine". Sau Day 9 wire event-driven xong, **rà lại payment domain thật**:

| Tiêu chí | payment-service | order-service (DDD) | inventory-service (DDD) |
|---|---|---|---|
| Invariants | 1 chính (`amount ≥ 0` + state machine) | 4 (items≥1, total=Σ subtotal, transition rule, terminal immutable) | 3 (reserved ≤ quantity, qty ≥ 0, no negative reserve) |
| Concurrency | Race chỉ ở callback duplicate — solve bằng UNIQUE constraint, KHÔNG bằng aggregate | Concurrent cancel + ship — cần @Version trên Order | 100-thread reserve cùng SKU — cần optimistic lock |
| Domain events | 1 (`payment.completed`) | 2+ (`OrderPlaced`, `OrderCancelled`, `OrderPaid`...) | 2 (`StockReserved`, `StockReleased`) |
| Bounded context complexity | Low — gateway integration là I/O, không phải business logic | High — orchestration, state machine 5 state | Medium — inventory rules |

payment-service đạt **1/3 tiêu chí mạnh**. DDD ở đây là **over-engineering**.

## Alternatives considered

### ❌ A. Full DDD Aggregate Root (như Order)

Tạo `PaymentIntent` aggregate với `@DomainEvents`, factory, no setter, persistence chuyển 2-column JSONB...

- **Pros**: consistency với order/inventory, ready cho khi payment phức tạp thêm (refund/dispute).
- **Cons**:
  - 1 invariant không đáng tách aggregate boundary.
  - Domain event chỉ có `PaymentCompleted` — đã định nghĩa ở common-lib từ Day 8, không cần `@DomainEvents` ở entity.
  - Persistence overhead (2 column + serializer) không có lợi khi PaymentStatus không mang data riêng từng permit.
  - Future-proofing ≠ build trước. Khi refund đến (Day 36) lúc đó refactor → còn rẻ hơn.

### ✅ B. Layered + sealed status (chosen)

Controller → UseCase → Repository (JpaRepository). PaymentIntent là JPA entity với invariant ở method (`initiate`, `capture`, `fail`, `markExpired`). Sealed `PaymentStatus` cho state machine + exhaustive switch.

- **Pros**: phù hợp scope. Idempotency logic tập trung ở `HandleCallbackUseCase`. Easy to test.
- **Cons**: nếu sau này thêm Refund + Dispute aggregate, có thể cần refactor sang DDD-lite.

### ❌ C. Functional / pipeline style (no entity state)

Mọi state tính từ event stream `payment.event` (event sourcing).

- **Pros**: audit trail tự nhiên. Replay được state bất kỳ thời điểm.
- **Cons**: 1 service mà event sourcing trong khi cả platform đang CRUD → inconsistent. Tooling debug khó. Day 13 outbox + Day 23 MongoDB event store sẽ revisit.

### ❌ D. Anemic domain model (DTO + service làm hết)

PaymentIntent chỉ là DTO bag of getters/setters. Logic transition ở service.

- **Pros**: code đơn giản nhất.
- **Cons**: invariant `amount ≥ 0` + state machine **không enforce ở entity** → bug dễ leak. Service có thể set status arbitrary. Đây là [anti-pattern Fowler 2003](https://martinfowler.com/bliki/AnemicDomainModel.html).

## Chosen — Rationale

**B (Layered + sealed status)** giữ entity-level invariant (method validate state machine + amount ≥ 0) mà không kéo theo Aggregate boilerplate. Sealed `PaymentStatus` là **modernity touch không tốn**: vẫn được exhaustive switch + compile-time check thêm state mới.

Lý do quan trọng nhất: **khớp với scope thật** — payment-service Day 10 chỉ làm callback dedup + state machine. Khi nào scope mở rộng (Refund Day 36, multi-currency conversion, multi-provider routing), lúc đó re-evaluate.

## Trade-offs

**Accepted**:
- Inconsistency với order/inventory (Layered vs DDD) — chấp nhận. Mỗi service phù hợp với complexity của nó là principle (CLAUDE.md §5 đã ghi).
- Nếu refund domain phát triển, cần migration sang DDD — chấp nhận chi phí refactor sau.

**Rejected**:
- "Build DDD ngay từ đầu cho consistency" → cargo cult. CLAUDE.md §3 explicit: "production-grade, không tutorial".
- "Anemic để code ngắn nhất" → đánh đổi safety, không OK.

## Consequences

- ✅ Phase 5 docs ngắn hơn (không cần `architecture/payment-domain.md` riêng với classDiagram + stateDiagram đầy đủ).
- ✅ Future engineer thấy code thấy ngay scope: PaymentController → UseCase → Repository, dễ navigate.
- ⚠️ Khi Day 36 reconciliation thêm Refund flow → cần evaluate lại: nếu Refund có invariant phức tạp (vd partial refund, deadline 90 ngày, multi-currency) → tách Aggregate `Refund` riêng (KHÔNG nhồi vào PaymentIntent).
- ⚠️ Code reviewer cần biết quyết định này khi đọc payment-service mà không thấy `domain/Aggregate.java` pattern — link ADR ở README service.

## Related

- ADR-003 — DDD for order/inventory/payment (bị revise scope — payment giờ Layered)
- ADR-005 — Feign vs HTTP Interface (cùng pattern "đánh giá thật mới chọn")
- Lesson [04 optimistic locking](../lessons/04-optimistic-locking.md)
- Code: [`PaymentIntent.java`](../../services/payment-service/src/main/java/com/ecommerce/payment/domain/PaymentIntent.java) · [`PaymentStatus.java`](../../services/payment-service/src/main/java/com/ecommerce/payment/domain/PaymentStatus.java) · [`HandleCallbackUseCase.java`](../../services/payment-service/src/main/java/com/ecommerce/payment/application/HandleCallbackUseCase.java)
