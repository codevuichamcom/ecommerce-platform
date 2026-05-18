# 🏗️ ADR-006 — Sync orchestration → Async event-driven cho Order flow

- **Status**: ✅ Accepted
- **Date**: 2026-05-18
- **Deciders**: Tonny (sole-owner backend) + reviewer Hùng (Tech Lead ShopVN fictional)
- **Supersedes**: phần `placeOrder` orchestration của [ADR-003 DDD selective](003-ddd-for-order-inventory-payment.md)

## Decision

Refactor `PlaceOrderUseCase` từ sync RPC orchestration (Day 6) sang **event-driven** với Kafka: order-service save Order với `reservation_status=PENDING` → publish `order.created` → inventory consume → reserve → publish `inventory.reserved` → order consume → `markReserved`.

## Context

Day 6 sync orchestration:

- order-service gọi `inventory.reserve` per-SKU qua RestClient (Feign Day 8) → fail → compensate release đã reserve → throw.
- Pros: kết quả immediate, UX clear (200 = stock OK).
- Cons: tight coupling, inventory chậm 2s → order chậm 2s + thread block; cascade failure (inventory down → order down); không scale flash sale (Day 33).

Day 8 đã wire Kafka foundation. Day 9 modernity introduce Micrometer Tracing → cần demo trace propagation qua Kafka headers (impossible với sync RPC vì không có cross-broker propagation case).

## Alternatives considered

1. **Giữ sync (Day 6 baseline)** — đơn giản nhất.
   - ❌ Tight coupling, không scale flash sale, không demo trace propagation Kafka.
2. **Async event-driven + `reservation_status`** (chosen) — Day 9.
   - ✅ Decouple, scale, demo tracing.
   - ⚠️ Complexity + eventual consistency window + debt outbox/DLT.
3. **Sync với timeout 500ms fallback async**.
   - ⚠️ 2 code path, bug-prone ở fallback boundary.
4. **Saga orchestration** (Camunda, temporal.io).
   - ❌ Operational cost lớn (Camunda cluster + DB riêng). Over-engineer cho 200k DAU.
5. **Reserve TRƯỚC khi tạo Order** (inventory chủ động create reservation entity, order-service tham chiếu).
   - ⚠️ Đảo ownership domain — inventory không nên biết về order. Day 33 flash sale có thể revisit pattern này.

## Chosen — Rationale

**#2 async event-driven + status field**.

Lý do:

- Foundation phù hợp Kafka Day 8 + Day 33 flash sale.
- Decouple service đúng nguyên tắc microservice.
- Complexity tăng có lý do (eventual consistency, outbox debt — đều đã trên roadmap Day 12 + 13).
- Demo được trace propagation Kafka (modernity Day 9).
- FE Day 27 polling/SSE đã planning → có cách render UX rõ ràng.

## Trade-offs

**Accepted**:

- Eventual consistency window 50ms-5s (busy lag) → cần SLI + alert + UI banner "Đang giữ hàng".
- Dual-write debt (DB commit + Kafka publish không atomic) → trả ở Day 13 outbox.
- Failure handling primitive Day 9 (log warn) → tighten Day 12 DLT + compensation event.
- Sealed `OrderStatus` state machine + `reservation_status` field = 2 dimension state → reviewer phải check không trộn lẫn.

**Rejected**:

- Saga orchestration: từ chối vì operational cost không proportional với scale 200k DAU. Nếu lên 5M DAU + ≥ 5 step orchestration → revisit.
- Sync fallback: từ chối vì 2-path bug-prone.

## Consequences

**Positive**:

- order-service P95 latency giảm từ ~250ms (Day 6 sync) xuống ~80ms (Day 9 fire-and-forget publish).
- inventory-service scale horizontal qua consumer group partition.
- Foundation cho flash sale Day 33 (Redis Lua decrement nhanh, async confirm).

**Negative / Tech debt**:

- Day 12: thêm Resilience4j + DLT + compensation event `inventory.reserve.failed`.
- Day 13: thêm outbox table + relay scheduler — refactor publisher dùng outbox thay vì publish trực tiếp.
- Day 20: wire `order.reservation.lag` Timer + Grafana dashboard.
- FE Day 27: render `reservation_status=PENDING` banner.

## Related

- Code: [`PlaceOrderUseCase`](../../services/order-service/src/main/java/com/ecommerce/order/application/PlaceOrderUseCase.java) · [`OrderCreatedConsumer`](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java) · [`InventoryReservedConsumer`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/messaging/InventoryReservedConsumer.java)
- Doc: [issue 09 eventual-consistency](../issues/09-eventual-consistency-order.md) · [lesson 09 tracing](../lessons/09-distributed-tracing-otel.md) · [lesson 09b window](../lessons/09b-eventual-consistency-window.md)
- ADR liên quan: [ADR-003 DDD selective](003-ddd-for-order-inventory-payment.md) · [ADR-005 Feign vs HTTP Interface](005-feign-vs-http-interface.md)
