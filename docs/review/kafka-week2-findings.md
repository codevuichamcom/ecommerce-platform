# 🔍 Kafka Week 2 — Review findings

> **Day 14 deliverable.** Review brutally honest stack event-driven Day 8-13
> trước campaign 6/6 (traffic dự kiến 10x). Mục đích: identify assumption ngầm,
> failure mode, gap. KHÔNG refactor trong Day 14 (freeze trước campaign) —
> chỉ document để Week 3+ ưu tiên.
>
> **Format**: severity 🔴 critical · 🟡 watch · 🟢 nit · với file:line + scenario thật.

---

## 🏢 Bối cảnh review

- Anh Hùng (CTO ShopVN) yêu cầu review trước campaign 6/6.
- Reviewer: tôi (Tech Lead) + 1 senior Kafka mời từ Tiki.
- Scope: 7 file core Week 2 (autoconfig + 4 consumer + outbox relay + error handler).
- Rule: review **assumption**, không tìm bug syntax. Junior tìm bug — senior tìm "code này assume gì mà nó không nói ra".

---

## 📊 Findings summary

| # | File | Severity | Topic | Action |
|---|------|----------|-------|--------|
| F1 | [inventory/OrderCreatedConsumer.java:65-71](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java#L65-L71) | 🔴 | Swallow `RuntimeException` mất message khi DB down | Week 3 fix — phân loại exception, throw infra-error |
| F2 | [inventory/OrderCreatedConsumer.java:56-71](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java#L56-L71) | 🔴 | Partial-success không atomic — item[0] published, item[1] fail → orphan reservation | Week 3 — batch reserve hoặc per-item outbox |
| F3 | [inventory/OrderCreatedConsumer.java](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java) | 🔴 | Consumer KHÔNG idempotent — replay = trừ stock lại | Week 3 — dedup `eventId` Redis SET NX (Day 11 đã làm cho notification, debt cho inventory) |
| F4 | [OutboxRelay.java:78-86](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxRelay.java#L78-L86) | 🟡 | Sequential `.get(5000ms)` block per event — throughput bottleneck ở 10x traffic | Week 3 — `CompletableFuture.allOf` hoặc parallel publish |
| F5 | [KafkaAutoConfiguration.java:130](../../common-lib/src/main/java/com/ecom/common/autoconfig/KafkaAutoConfiguration.java#L130) | 🟡 | `TRUSTED_PACKAGES=*` — deserialize gadget risk khi multi-tenant | Pre-prod — whitelist `com.ecom.common.event` |
| F6 | [KafkaAutoConfiguration.java:135-149](../../common-lib/src/main/java/com/ecom/common/autoconfig/KafkaAutoConfiguration.java#L135-L149) | 🟡 | Listener factory KHÔNG bật `observation-enabled` explicit — trace `traceparent` qua Kafka header có thể không propagate | Verify với Zipkin trace E2E HTTP→Kafka→consumer |
| F7 | [notification/OrderCreatedConsumer.java:81-87](../../services/notification-service/src/main/java/com/ecommerce/notification/consumer/OrderCreatedConsumer.java#L81-L87) | 🟡 | `deduplicator.release()` race: nếu dispatch SUCCESS rồi exception sau → release allow re-dispatch = duplicate email | Week 3 — release CHỈ khi confirmed pre-dispatch failure |
| F8 | (architecture) | 🟢 | KHÔNG có schema registry — JSON additive contract enforce bằng convention, không bằng tool | Post-Week 7 — Confluent Schema Registry hoặc Apicurio nếu volume tăng |
| F9 | (architecture) | 🟢 | Outbox single-instance scheduler — multi-instance race-free nhờ SKIP LOCKED nhưng KHÔNG có leader election để limit concurrency | Acceptable hiện tại |

---

## 🔴 F1 — Swallow RuntimeException ở inventory consumer

**Code hiện tại** ([inventory/OrderCreatedConsumer.java:65-71](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java#L65-L71)):

```java
try {
    inventoryService.reserve(item.sku(), item.quantity());
    publisher.publishReserved(new StockReservedV1(...));
} catch (RuntimeException ex) {
    log.warn("Reserve failed orderId={} sku={} qty={} reason={}", ...);
}
```

**Assumption ngầm**: mọi `RuntimeException` đều là "stock hết thật" → KHÔNG retry.

**Failure mode thật**: 6/6 lúc 20:00. Postgres inventory failover 12s. `inventoryService.reserve()` throw `CannotAcquireLockException` (DB connection mất). Catch → log warn → consumer ack → message **mất**. Order ở `PendingReservation` → user paid → không có hàng → CSKH ticket. Cùng failure mode Day 13 outbox đã trả nợ ở producer side, nhưng consumer side **vẫn còn debt**.

**Senior framing**: comment Day 9 thừa nhận "Day 11 sẽ dedup" — nhưng Day 11 chỉ làm cho notification consumer, inventory consumer bị skip. **Debt invisible** vì test không cover DB-down scenario.

**Fix đúng** (Week 3 priority):
```java
} catch (InsufficientStockException ex) {
    log.warn("Stock out — publish StockReserveFailed compensation", ...);
    publisher.publishReserveFailed(...);  // compensation event để order auto-cancel
} catch (RuntimeException ex) {
    log.error("Infra error reserving — re-throw to retry/DLT", ex);
    throw ex;  // DefaultErrorHandler retry 3x → DLT
}
```

**Tag**: #correctness #ai-pattern-conflict (AI catch-all giải pháp tiện)

---

## 🔴 F2 — Partial success ở loop reserve

**Code** ([inventory/OrderCreatedConsumer.java:56-71](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java#L56-L71)):

```java
for (OrderCreatedV1.Item item : event.items()) {
    try {
        inventoryService.reserve(item.sku(), item.quantity());
        publisher.publishReserved(new StockReservedV1(...));
    } catch (...) { log.warn(...); }
}
```

**Failure mode**: order 3 item. Item[0] reserve OK + `StockReservedV1` published. Item[1] stock hết → swallow (F1). Item[2] OK + published. Order nhận 2/3 StockReserved → state machine không có rule cho partial → stuck `PendingReservation` mãi mãi.

**Senior framing**: AI viết loop "tự nhiên" — nhưng business invariant là **all-or-nothing per order**. Code không express invariant này. Đây là pattern AI/junior dễ miss vì code "looks clean".

**Approaches compared**:
| Approach | Pros | Cons |
|---|---|---|
| Try-reserve-all-then-publish-all | Atomic publish | Reserve không có rollback nếu publish fail |
| Reserve trong 1 tx + publish via outbox | Atomic thật | Phải add outbox cho inventory-service (Week 3) |
| Order state cover partial (`PartiallyReserved`) | Express ground truth | Tăng complexity state machine |

**Recommendation**: Week 3 add outbox cho inventory-service tương tự Day 13 — reserve + publish all in 1 tx + outbox relay.

**Tag**: #correctness #atomicity #ddd-invariant-leak

---

## 🔴 F3 — Inventory consumer not idempotent

**Code**: không có dedup `eventId`.

**Failure mode**: consumer rebalance (deploy rolling, scale-out) → uncommitted offset → replay. `inventoryService.reserve()` trừ stock LẦN NỮA → oversell silent. Day 4 oversell issue đã fix optimistic lock ở **single-reservation** scenario, nhưng **multi-reserve cùng order** không cover.

**Fix** (Week 3): reuse `NotificationDeduplicator` pattern từ notification-service:
```java
if (!deduplicator.tryAcquire(event.eventId())) return;
```

**Senior framing**: pattern Day 11 đã làm 1 lần — junior nghĩ "đã giải rồi". Senior phải check **mọi consumer** chưa cover. Đây là argument cho **shared idempotency abstraction** ở common-lib (rule of three đã đạt: notification + inventory + payment).

**Tag**: #correctness #idempotency #debt

---

## 🟡 F4 — OutboxRelay sequential publish

**Code** ([OutboxRelay.java:78-86](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxRelay.java#L78-L86)): loop `publishOne` với `.get(5000ms)` blocking per event.

**Math**:
- Hiện tại: 50k orders/day ≈ 0.5 event/s → 1 thread × 100 batch/s = OK.
- Campaign 10x: 5 event/s → vẫn OK với throughput producer ack ms-level.
- **Worst case**: Kafka broker degrade, ack lag lên 500ms. Batch 100 × 500ms = 50s/tick. `@Scheduled fixedDelay=1s` next tick chờ tick trước → tích lũy 50× backlog. Outbox table grow → SLO eventual consistency window break.

**Fix** (Week 3, defer post-campaign):
```java
List<CompletableFuture<?>> futures = batch.stream()
    .map(e -> publishOneAsync(e)).toList();
CompletableFuture.allOf(futures.toArray(...)).get(maxBatchTimeout);
```

Trade-off: tx isolation phức tạp hơn (mỗi publish vẫn cần REQUIRES_NEW per event).

**Acceptable trước campaign**: monitoring + alert nếu outbox lag > 10s. Optimize khi cần.

**Tag**: #performance #scale-10x

---

## 🟡 F5 — TRUSTED_PACKAGES wildcard

**Code** ([KafkaAutoConfiguration.java:130](../../common-lib/src/main/java/com/ecom/common/autoconfig/KafkaAutoConfiguration.java#L130)): `JsonDeserializer.TRUSTED_PACKAGES = "*"`.

**Risk**: nếu attacker publish message với type info header chỉ tới class có constructor side effect → RCE (deserialize gadget).

**Mitigation hiện tại**: `USE_TYPE_INFO_HEADERS=false` → deser theo `@Payload` generic, không theo header → gadget khó exploit. Comment trong code đã note. Nhưng defense-in-depth nên whitelist explicit.

**Fix pre-prod**: `TRUSTED_PACKAGES=com.ecom.common.event`.

**Tag**: #security #defense-in-depth

---

## 🟡 F6 — Observation propagation không explicit

**Code**: factory không gọi `factory.getContainerProperties().setObservationEnabled(true)`.

**Spring Kafka 3.4**: observation-enabled là per-template + per-container, default `false`. Day 9 mention "observation-enabled producer + listener" — verify lại config có thật bật chưa.

**Test**: Zipkin UI sau Day 9 đã thấy trace HTTP → Kafka publish → consumer chưa? Nếu trace bị **cắt giữa producer và consumer** → trace propagation broken.

**Action Day 14**: verify trace E2E qua Zipkin local. Nếu broken → add `observation-enabled` explicit.

**Tag**: #observability #verify

---

## 🟡 F7 — Dedup release race ở notification

**Code** ([notification/OrderCreatedConsumer.java:81-87](../../services/notification-service/src/main/java/com/ecommerce/notification/consumer/OrderCreatedConsumer.java#L81-L87)):

```java
try {
    notificationChannel.send(payload);  // email SENT
    log.info(...);
} catch (RuntimeException ex) {
    deduplicator.release(event.eventId());  // ← bug
    throw ex;
}
```

**Failure mode**: `notificationChannel.send()` SUCCESS (email đã ra ngoài). Sau đó `log.info(...)` throw (vd: LoggerFactory bị reload). Catch → release dedup → retry → email gửi LẦN 2 → user nhận 2 email.

**Probability**: rất thấp (log throw hiếm). Nhưng pattern sai trên nguyên tắc: release CHỈ đúng khi chắc chắn side effect chưa happen. `send()` là side effect — sau nó, release là wrong.

**Fix**:
```java
boolean dispatched = false;
try {
    notificationChannel.send(payload);
    dispatched = true;
    log.info(...);
} catch (RuntimeException ex) {
    if (!dispatched) deduplicator.release(event.eventId());
    throw ex;
}
```

**Tag**: #idempotency #subtle

---

## 🟢 F8 — Schema registry gap

JSON additive contract enforce bằng **convention** (Day 8 ADR-005). Code review catch breaking change? Khi team scale ra ngoài 1 dev, convention break dễ. Schema registry (Confluent / Apicurio) enforce ở producer side compile fail.

**Khi nào nâng cấp**: team > 3 dev hoặc consumer external xuất hiện.

---

## 🟢 F9 — Outbox không leader election

Multi-instance race-free nhờ SKIP LOCKED (Day 13). Acceptable. Khi nào cần leader: nếu throughput cần limit (vd: rate-limit downstream API) → mỗi instance contribute → khó cap tổng. Hiện tại Kafka producer cap tự nhiên ở broker side.

---

## 📋 Gap list cho Week 3+

Ưu tiên theo severity, refactor sau campaign:

1. **🔴 Week 3 Day 15 (cùng cache work)**: F1 + F3 inventory consumer — phân loại exception + dedup `eventId`.
2. **🔴 Week 3 Day 17 (N+1 + JPA)**: F2 inventory outbox — reserve + publish atomic.
3. **🟡 Week 3 Day 20 (load test)**: F4 OutboxRelay parallel publish — bench trước, fix nếu lag > SLO.
4. **🟡 Pre-prod**: F5 TRUSTED_PACKAGES whitelist + F6 observation-enabled verify.
5. **🟡 Week 3**: F7 notification dispatch flag.
6. **🟢 Post-Week 7**: F8 schema registry nếu team grow.

---

## 🔗 Related

- Source code refs (file:line) đã link inline mỗi finding.
- Day docs evidence: [day-08](../interview/day-08-kafka.md) · [day-09](../interview/day-09-order-flow.md) · [day-10](../interview/day-10-payment.md) · [day-11](../interview/day-11-notification.md) · [day-12](../interview/day-12-resilience.md) · [day-13](../interview/day-13-outbox.md)
- Mock interview Q&A: [week-02-mock.md](../interview/week-02-mock.md)
- Cumulative trap checklist: [ai-junior-traps.md](ai-junior-traps.md)
