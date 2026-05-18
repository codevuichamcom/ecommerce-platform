# 📚 Lesson 09b — Eventual Consistency Window

> **Day**: 9 · **Status**: ✅ Done

## TL;DR

- **Eventual consistency window** = khoảng thời gian giữa "viết xong ở A" và "đọc đúng ở B" trong distributed system. Day 9 thay sync (Day 6) bằng event-driven → có window ~50-500ms mà order hiển thị `reservation_status=PENDING` cho user.
- Trade-off async: decouple service + scale + resilience NHƯNG complexity + state machine + UX phải communicate "đang xử lý".
- Window phải **đo được** + **alert được** — không phải "tin Kafka nhanh là OK".

## 🎯 Khi nào dùng (chọn eventual)

- User KHÔNG cần kết quả immediate (place order → reserve stock có thể async 500ms — chấp nhận được).
- Cần scale từng service độc lập (inventory burst flash sale không kéo order-service xuống).
- Đã có message infra (Kafka) + sẵn sàng cho debt outbox + DLT.

## ❌ Khi nào KHÔNG dùng (giữ sync)

- Latency budget < 100ms toàn flow (vd payment authorization gateway demand sync result).
- Strong consistency yêu cầu pháp lý (vd bank transaction debit + credit phải atomic).
- Không có message infra HOẶC team chưa có capacity ops DLT / outbox.
- Operation có **side effect bất khả hồi** mà không có compensation path (gửi SMS, charge card).

## ⚠️ Cạm bẫy

1. **Ẩn window khỏi UI**: user thấy "Order placed" rồi 3s sau status đổi thành `Failed - InsufficientStock` → confused. Phải show `reservation_status: PENDING` ở response + UI banner "Đang giữ hàng...".
2. **Đo window bằng `console.log`**: phải metric. Day 20 sẽ wire `Timer.builder("order.reservation.lag")` record từ `placedAt` → `markReserved()`.
3. **Đặt timeout vô tận**: nếu inventory chết, order PENDING vĩnh viễn → silent inconsistency. Phải có SLI "P95 lag < 2s" alert.
4. **Lẫn lộn với CAP / ACID**: eventual consistency là **propagation timing**, không phải C trong CAP (CAP nói về linearizability khi partition).

## 🔄 Approaches compared

| Approach | Window | UX |
| --- | --- | --- |
| **Sync RPC (Day 6)** | 0 | Spinner 200-2000ms, kết quả immediate |
| **Async event + status field (Day 9 chosen)** | 50-500ms | Show "Đang giữ hàng" 1s, refresh tự động |
| **Saga orchestration** | tương đương async | Tương đương, thêm orchestrator service operational cost |
| **Optimistic UI** | 0 (UI) / 50-500ms (backend) | Hiển thị "Đặt hàng thành công" ngay, rollback nếu fail |

## 📐 Cách đo

```java
// Pseudo — Day 20 sẽ wire thật via Micrometer.
Timer reservationLag = Timer.builder("order.reservation.lag")
    .description("Time from placedAt to inventory.reserved ack")
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(registry);

// Ở InventoryReservedConsumer:
Duration lag = Duration.between(order.placedAt(), Instant.now());
reservationLag.record(lag);
```

SLI: P95 < 2s. Vượt → alert PagerDuty (Day 20 wire).

## 🎤 Trả lời phỏng vấn

> **Q**: "Tại sao không cứ giữ sync cho gọn?"
>
> Sync = tight coupling. Inventory chậm 2s → order-service thread bị block 2s → throughput giảm linear với latency downstream. Async cho phép order-service ack user trong 100ms và inventory xử lý theo throughput thật của mình. Trade-off: window eventual consistency phải communicate ở UI + đo + alert.

> **Q**: "Window này dài bao lâu thì chấp nhận?"
>
> Tùy use case. Place order: 1-2s P95 OK (user vẫn đang ở thank-you page). Show cart count realtime: < 100ms (UX expect immediate). Bank transfer: 0 (sync mandatory). Quy tắc: SLI gắn liền business expectation, không phải number from "best practice".

## 🔗 Related

- Code: [`Order.markReserved()`](../../services/order-service/src/main/java/com/ecommerce/order/domain/Order.java) · [`PlaceOrderUseCase`](../../services/order-service/src/main/java/com/ecommerce/order/application/PlaceOrderUseCase.java)
- Doc: [issue 09](../issues/09-eventual-consistency-order.md) · [lesson 09](09-distributed-tracing-otel.md) · [ADR-006](../decisions/006-sync-orchestration-vs-async-events.md)
