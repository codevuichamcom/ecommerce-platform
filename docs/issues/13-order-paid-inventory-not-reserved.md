# Issue 13 — 🔥 Order paid nhưng inventory không reserve

> **Day 13** · severity SEV-2 · 2026-05-24 09:14 ICT · root cause: dual-write.

---

## 1. Problem

23 order trong window 90s: DB Postgres lưu thành công (`status=PendingPayment`,
user thấy "Order placed"), Kafka `order.created` publish fail silently →
inventory-service không reserve → sau khi customer paid → CSKH ticket "Đã
thanh toán nhưng không có hàng".

## 2. Symptoms

- 23 ticket CSKH trong 4h: *"Tôi paid rồi mà order vẫn pending"*.
- Grafana `kafka_producer_send_errors_total` spike từ 0 → 23 lúc 09:14:32.
- Postgres query: 23 row `orders.reservation_status='PENDING' AND placed_at > '09:14' AND placed_at < '09:16'` stuck > 1h (SLI alert "pending reservation > 30s" KHÔNG fire vì alert chưa wire — gap Day 20).
- Kafka broker `kafka-1` (primary cho partition 0 của `order.created`) restart 09:14:21 → 09:15:54 do OOM (heap config 2GB, peak workload).
- Application log: `Failed to publish order.created eventId=... orderId=...` 23 entries — log.error nhưng KHÔNG rollback (vì comment Day 9: "DB đã commit, rollback messaging vô nghĩa").

## 3. Root cause

**Dual-write problem** ([lesson 13b](../lessons/13b-dual-write-problem.md)):
[`PlaceOrderUseCase.java`](../../services/order-service/src/main/java/com/ecommerce/order/application/PlaceOrderUseCase.java) (Day 9 version) gọi
`orderRepository.save()` rồi `kafkaTemplate.send()` — KHÔNG atomic. DB commit
thành công, Kafka publish fail. App log warn, **state divergent vĩnh viễn**.

Comment trong code Day 9 đã thừa nhận debt:
```java
// Dual-write debt: DB commit + Kafka publish KHÔNG atomic.
// Day 13 outbox pattern sẽ trả debt này.
```

→ Debt thành incident thật khi broker restart 90s. Predictable.

> Diagram dưới show dual-write non-atomic của `PlaceOrderUseCase` (Day 9 version):
> `orderRepository.save()` COMMIT OK → `kafkaTemplate.send()` FAIL (broker restart)
> → chỉ `log.error` chứ KHÔNG rollback (DB đã commit) → inventory không bao giờ
> nhận `order.created` để reserve → order kẹt `reservation_status=PENDING`. Khối
> đỏ = điểm divergent state. Outbox (fix) gộp 2 write vào 1 tx.

```mermaid
sequenceDiagram
    autonumber
    participant O as PlaceOrderUseCase
    participant DB as Postgres (orders)
    participant K as Kafka (order.created)
    participant Inv as inventory-service

    O->>DB: orderRepository.save(order, reservation_status=PENDING)
    DB-->>O: COMMIT OK (user thấy "Order placed")

    rect rgb(254,202,202)
        O->>K: kafkaTemplate.send(order.created)
        Note over K: kafka-1 restart (OOM) 90s
        K-->>O: send FAIL
        Note over O: log.error "Failed to publish..."<br/>KHÔNG rollback (DB đã commit)
        Note over Inv: không nhận order.created<br/>→ không reserve
        Note over DB: order kẹt reservation_status=PENDING vĩnh viễn<br/>→ customer paid nhưng "không có hàng"
    end

    Note over O,DB: Fix: OutboxRecorder.record() ghi outbox_event<br/>TRONG cùng tx với save() → atomic;<br/>OutboxRelay poll + publish riêng (at-least-once)
```

## 4. Approaches compared

| Approach                                       | Pros                                              | Cons                                                                          |
| ---------------------------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------------- |
| **A. Sync ack `kafkaTemplate.send().get()` trong tx** | Đơn giản, no new table                            | Tx hold DB connection chờ Kafka network (slow); vẫn race nếu Kafka publish OK + DB rollback; tx-aware producer config phức tạp |
| **B. Transactional outbox + relay (chosen)**   | Atomic DB-side, single source of truth, debuggable | Thêm 1 table + relay process, polling lag 1-2s, table bloat cần cleanup       |
| **C. Debezium CDC (WAL stream)**               | Sub-second latency, no polling overhead           | DBA phải enable `wal_level=logical`, ops cost connector cluster, schema evolution phức tạp |
| **D. Reconciler batch hourly**                 | Đơn giản nhất, no realtime infra                  | Lag 1h → không acceptable cho order flow (customer chờ > 1h thấy "đang giữ hàng"?) |

## 5. Chosen approach + Why

**B. Transactional outbox + scheduled relay**.

Lý do trong context dự án:
- DBA chưa cho phép `wal_level=logical` trên prod → loại C.
- Lag 1h của D không acceptable cho order UX.
- Sync ack A tăng tx duration 50-200ms × 50k orders/day → connection pool exhaust dưới peak; còn race condition residual.
- B: 1 table thêm + 1 scheduled bean. Volume 50k orders/day = 0.5 event/s, polling 1s đủ. Migration path sang CDC khi volume tăng > 10k/s (ADR-009 đã note).

## 6. Fix

Code:
- [`V3__create_outbox_event.sql`](../../services/order-service/src/main/resources/db/migration/V3__create_outbox_event.sql) — table + partial index.
- [`OutboxEvent.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxEvent.java) — JPA entity + lifecycle.
- [`OutboxRecorder.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxRecorder.java) — record trong cùng tx.
- [`OutboxRelay.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxRelay.java) — scheduled publish.
- [`PlaceOrderUseCase.java`](../../services/order-service/src/main/java/com/ecommerce/order/application/PlaceOrderUseCase.java) — refactor bỏ direct Kafka call.

Backfill incident 23 order: manual SQL insert vào outbox table với
`status=PENDING` (relay sẽ pick up trong 1s).

```sql
INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type,
    topic, partition_key, payload, status, created_at)
SELECT gen_random_uuid(), 'Order', id::text, 'OrderCreatedV1',
    'order.created', id::text, jsonb_build_object(...), 'PENDING', now()
FROM orders
WHERE reservation_status = 'PENDING' AND placed_at < now() - interval '5 minutes';
```

## 7. Prevention

- **Test**: unit test `OutboxRelayTest` 5 case (empty / success / fail retry / give up / batch). Integration test Day 13b (TODO Day 14): stop Kafka container, place 5 order, start Kafka, assert 5 inventory.reserved arrive.
- **SLI alert**: `outbox.pending.age > 30s` → PagerDuty. Wire Micrometer ở Day 20.
- **SLI alert**: `outbox.status=FAILED count > 0` → ticket runbook `runbooks/outbox-stuck-events.md` (TODO Day 14).
- **Lint**: PR review checklist thêm rule "Bất kỳ Kafka send nào trong `@Transactional` business code = review reject". Reviewer cross-check `OutboxRecorder.record()`.

## 8. Trade-off accepted

- **Latency**: event delivery từ ~5ms (direct publish) → 1-2s (polling). Cho order flow, FE đã show banner "Đang giữ hàng" (Day 9 + Day 27) nên user không cảm nhận.
- **DB load**: thêm 1 INSERT + 1 polling query/s. Estimate 0.5% extra QPS — không đáng kể.
- **Code complexity**: thêm 4 file (entity, repo, recorder, relay) + 1 migration. Worth it vì atomic guarantee + audit log.
- **Ordering**: vẫn per-aggregate-id, không global. Đó là tradeoff của Kafka không riêng outbox.

## 9. Related

- Code: [`OutboxRelay.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxRelay.java)
- Lesson: [13 outbox pattern](../lessons/13-outbox-pattern.md), [13b dual-write](../lessons/13b-dual-write-problem.md)
- ADR: [009 outbox vs CDC](../decisions/009-outbox-vs-cdc.md)
- Interview: [day-13](../interview/day-13-outbox.md)
- Day 9 origin issue: [09 eventual consistency](09-eventual-consistency-order.md)
