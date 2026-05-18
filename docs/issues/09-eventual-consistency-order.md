# 🔥 Issue 09 — Order placed nhưng stock chưa reserve (eventual consistency window)

> **Day**: 9 · **Severity**: 🟡 Sev3 (UX confusing, không data loss) · **Status**: ✅ Documented + mitigated

## 1. Problem

Sau khi refactor order flow từ sync (Day 6) sang event-driven (Day 9), user place order thành công (HTTP 200, `status=PendingPayment`), nhưng vài giây sau check lại order → `reservation_status=FAILED` (stock thật sự hết). UX confusing: "tôi đã đặt được mà sao giờ báo hết hàng?"

## 2. Symptoms

- Support nhận 4 ticket/ngày sau flash sale Day 1 launch event-driven.
- Zipkin trace: span gap 2.8s giữa `POST /orders` (order-service) và `kafka.consume order.created` (inventory-service) do partition lag dồn 200 event burst.
- Log order-service: `Order placed (PENDING reservation) id=...`
- Log inventory-service 2.8s sau: `Reserve failed orderId=... sku=... reason=InsufficientStock`
- User refresh order page sau 5s thấy status đổi → support log "khách bảo lừa đảo".

## 3. Root cause

Day 6 sync: order-service gọi `inventory.reserve` Feign **TRƯỚC** khi save Order → fail thì user nhận 409 ngay, không có window.

Day 9 async: order-service save Order với `reservation_status=PENDING` rồi publish event. Inventory consume + reserve **bất đồng bộ**. Trong window này:

1. User thấy "Order placed" ở response.
2. Order tồn tại DB với `PENDING`.
3. Inventory chưa biết → stock chưa trừ.
4. Nếu stock hết khi inventory consume → reserve fail → order STUCK ở `PENDING` (Day 9) hoặc `FAILED` (Day 12 sẽ wire compensation).

**Window**: 50-500ms ở idle hệ thống, **2-5s** khi consumer lag burst. Nhưng UI Day 6 vẫn show "Order placed" như cũ → user không biết "đang giữ hàng".

## 4. Approaches compared

| # | Approach | Pros | Cons |
| - | --- | --- | --- |
| 1 | **Quay về sync** (Day 6 baseline) | Window=0, UX clear | Tight coupling, inventory chậm = order chậm; mất resilience |
| 2 | **Async + `reservation_status` field + FE polling** (chosen) | Decouple, scale; FE show "Đang giữ hàng..."; foundation flash sale | Complexity +1; debt outbox (Day 13) + DLT (Day 12) |
| 3 | **Sync với timeout 500ms fallback async** | UX tốt + decouple khi inventory chậm | Code 2 path; bug-prone; race ở fallback boundary |
| 4 | **Saga orchestration** (Camunda / temporal.io) | Visualize state, retry built-in | Operational cost lớn; over-engineer cho 9-service monorepo |

## 5. Chosen approach + Why

**#2 — Async + status field + FE polling**.

Lý do gắn với context project:

- ShopVN 200k DAU + chuẩn bị flash sale (Day 33) → cần decouple để inventory burst không kéo order-service.
- FE Day 27 đã planning TanStack Query → polling/SSE free.
- Outbox debt (Day 13) đã trên roadmap — không phải skip.
- Đã có Kafka foundation (Day 8) — chuyển sync sang async chi phí marginal.

KHÔNG chọn #1 vì throwaway Day 8-9 work; #3 vì 2-path bug-prone; #4 vì over-engineer (Camunda 1 cluster để mature thường tốn 1 dev tháng — chưa worth ở 200k DAU).

## 6. Fix (code thật)

1. Order migration V2 thêm `reservation_status VARCHAR(16)` mặc định `PENDING` + CHECK constraint — [`V2__add_reservation_status.sql`](../../services/order-service/src/main/resources/db/migration/V2__add_reservation_status.sql).
2. `Order.markReserved()` idempotent — [`Order.java`](../../services/order-service/src/main/java/com/ecommerce/order/domain/Order.java).
3. `PlaceOrderUseCase` skip sync reserve, publish `order.created` — [`PlaceOrderUseCase.java`](../../services/order-service/src/main/java/com/ecommerce/order/application/PlaceOrderUseCase.java).
4. Order consume `inventory.reserved` → `markReserved` — [`InventoryReservedConsumer.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/messaging/InventoryReservedConsumer.java).
5. Inventory consume `order.created` → reserve + publish — [`OrderCreatedConsumer.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java).

## 7. Prevention

- **SLI**: `order.reservation.lag` P95 < 2s, P99 < 5s. Vượt → alert (Day 20 wire Prometheus + Grafana + Alertmanager).
- **Test**: Day 14 mock interview kèm chaos test "consumer lag 10s, place order → user expect gì".
- **UI contract**: response payload có `reservation_status` — FE Day 27 BẮT BUỘC render banner "Đang giữ hàng..." khi `PENDING`. Backend không enforce được, viết vào API contract doc.
- **Lint review trap**: nếu PR thêm sync HTTP call ở write path → reviewer check có thể chuyển event không (xem [`review/ai-junior-traps.md`](../review/ai-junior-traps.md)).

## 8. Trade-off accepted

- **Complexity ++**: thêm state machine reservation (PENDING/RESERVED/FAILED) ngoài state machine Order lifecycle (PendingPayment/Paid/...). 2 dimension state — dễ confuse.
- **Debt outbox** (Day 13): dual-write DB + Kafka không atomic — DB OK + Kafka fail → order PENDING vĩnh viễn.
- **Debt DLT/compensation** (Day 12): hôm nay reserve fail = log warn, order stuck. Day 12 sẽ publish `inventory.reserve.failed` để order auto-cancel.

## 9. Related

- Code: tất cả file ở §6 + [docker-compose Zipkin](../../docker-compose.yml)
- Doc: [lesson 09](../lessons/09-distributed-tracing-otel.md) · [lesson 09b](../lessons/09b-eventual-consistency-window.md) · [ADR-006](../decisions/006-sync-orchestration-vs-async-events.md)
- Future: [Day 12 retry + DLT](../../docs/ROADMAP.md#-day-12--retry--dead-letter-topic) · [Day 13 outbox](../../docs/ROADMAP.md#-day-13--outbox-pattern)
