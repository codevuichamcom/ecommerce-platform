# 🎤 Interview — Day 9 · Order flow event-driven + Distributed Tracing

> **Date**: 2026-05-18 · **Modernity**: Micrometer Tracing 1.4 + OTel + Zipkin

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — Series A ecommerce, 200k DAU, chuẩn bị flash sale launch Q3.
- **Role giao việc**: Anh Hùng (Tech Lead backend, ex-Tiki). Sprint stand-up Anh nói: *"Day 6 mình ship sync orchestration để demo flow. Production lo: inventory chậm 2s = user thấy spinner 2s. Tuần này chuyển sang event-driven, sync chỉ giữ price snapshot. Đồng thời support team than 'order #12345 stock không trừ' — đào log 3 service không thấy gì connect — cần distributed tracing."*
- **Bạn**: Backend dev sole-owner order/inventory/notification flow.
- **Reviewer**: Anh Hùng review PR; chị Mai (QA Lead) test "place order → check Kafka UI → check Zipkin trace".
- **Deadline**: 1 sprint day (8h thật).
- **Constraint thực tế**:
  - KHÔNG được phá API contract `POST /orders` hiện tại (FE đang dùng).
  - Outbox pattern là Day 13 — hôm nay chấp nhận **at-most-once publish** với log warning.
  - Zipkin chạy local docker, production sẽ migrate Tempo Day 20.
- **Definition of Done**:
  - Place 1 order → Kafka UI thấy `order.created` → inventory log "reserved sku=X qty=Y" → `inventory.reserved` event → notification log.
  - Zipkin UI: 1 trace span qua 3 service với `traceparent` đúng.
  - 5 docs ship (lesson 09 + 09b, issue 09, ADR-006, interview this).

## ❓ 5 Q&A senior level

### Q1. *Tại sao chuyển sync (Day 6) sang async event-driven? Khi nào sync vẫn tốt hơn?*

**Strong answer**: Async cho 3 benefit: (1) **decouple** service không cần biết endpoint nhau; (2) **scale independent** — inventory consumer group horizontal mà order-service không quan tâm; (3) **resilience** — inventory down 30s không kéo order-service xuống, Kafka buffer event.

Sync tốt hơn khi: **immediate consistency** mandatory (payment authorization, bank debit), **latency budget < 100ms toàn flow**, KHÔNG có message infra, hoặc operation có side effect bất khả hồi (gửi SMS — không thể "undo").

**Follow-up trap**: "async is always better, modern" — KHÔNG, complexity + eventual consistency + outbox/DLT debt là cost real. Engineer phải biện minh được tại sao chấp nhận window và đo nó như thế nào.

### Q2. *W3C `traceparent` qua Kafka inject bằng cách nào? Consumer crash trước commit thì span có lost không?*

**Strong answer**: Spring Kafka 3.x khi bật `spring.kafka.template.observation-enabled=true` tự wrap `send()` vào Micrometer Observation scope → OTel `TextMapPropagator` inject `traceparent` vào `ProducerRecord.headers()`. Format: `00-{traceId}-{spanId}-{flags}`. Consumer side `listener.observation-enabled=true` extract ngược lại, span con tự link parent.

Consumer crash trước commit: span đã được **exporter flush** lên Zipkin (default 5s batch hoặc sync flush khi span end). Span KHÔNG lost. Tuy nhiên trace **incomplete** — không có span "process record" của consumer mới sau rebalance.

**Follow-up trap**: confuse span lost với offset commit. Span = telemetry (đã flush), offset commit = Kafka consumer position (chưa commit nên redeliver). 2 thứ độc lập.

### Q3. *Sampling probability=1.0 ở dev — production set bao nhiêu? Cost?*

**Strong answer**: Phụ thuộc QPS + storage. 1k QPS Zipkin in-memory + Tempo S3 → 0.1 (~100k trace/day) tốn vài GB/day. > 10k QPS → 0.01 head-based, hoặc **tail-based sampling** qua OTel collector: keep 100% trace có error/duration > P99, sample 1% normal trace.

**Follow-up trap**: "1% đủ vì rare bug có thể grep log". Sai — rare bug chính là cần trace nhất vì khó reproduce. Tail-based mới đúng cách giữ context cho debug.

### Q4. *Dual-write problem — order save DB + publish Kafka không atomic. Worst case?*

**Strong answer**: Worst case: DB commit success → Kafka publish fail (broker down 5s) → order tồn tại với `reservation_status=PENDING` nhưng inventory không bao giờ nhận event → silent inconsistency vĩnh viễn. User thấy order treo PENDING.

Fix: **outbox pattern** (Day 13). Ghi event vào table `outbox_event` cùng transaction `Order.save()` → relay scheduler poll outbox → publish → mark sent. Atomic vì cả 2 nằm cùng JDBC transaction.

Hôm nay Day 9 accept debt: log error nếu publish fail + SLI alert "pending reservation > 30s".

**Follow-up trap**: "Spring `KafkaTransactionManager` chained với `JpaTransactionManager`" — `ChainedTransactionManager` đã deprecated, có race window vẫn fail. Outbox là pattern chuẩn.

### Q5. *Span vs log MDC — sao cần tracing khi đã có correlation id?*

**Strong answer**: 2 thứ khác nhau:

- **MDC `correlationId`** (Day 1): string flat, qua log aggregator (ELK) grep theo id → manual stitch. KHÔNG thấy duration mỗi step, KHÔNG thấy parent-child.
- **Trace span tree**: structured (parent-child), có **duration per span**, có **tags** (sku, http.status), visualize waterfall ở Zipkin UI. Thấy ngay "span DB query inventory chiếm 2s" mà log MDC không show.

Giữ cả 2 — `traceparent` chuẩn W3C cho cross-service tree; `correlationId` đơn giản cho log local + business-level tracking (1 user session có thể nhiều trace).

**Follow-up trap**: "thay log bằng tracing" — KHÔNG, log cần thiết cho event không phải request (cron, error stack). Tracing + Log + Metrics = 3 pillars observability.

---

## 🧠 Senior mindset notes

- Eventual consistency không phải "modern good" — phải biện minh + đo + alert. Junior dễ async bừa.
- Sampling cost real ở 10k QPS — phải design tail-based + collector từ đầu nếu prod-scale.
- Outbox debt cần ngày trả cụ thể (Day 13) — viết lên ADR, không phải để TODO mơ hồ.

## 🤖 AI Playbook

- **AI làm tốt**: Generate boilerplate consumer (`@KafkaListener` + DTO deserialize), inject/extract header utility. Sửa Spring tracing config (boilerplate yaml).
- **Prompt mẫu**: *"Spring Boot 3.4 micrometer tracing config for service that produces and consumes Kafka with W3C traceparent propagation via Spring Kafka observation. Export to Zipkin endpoint http://localhost:9411."*
- **Risk**: AI hay dùng Sleuth API cũ (`@NewSpan`, `brave.Tracer`) đã deprecated. Cũng hay quên `spring.kafka.template.observation-enabled=true` → trace dừng ở producer.
- **Validate**: Place 1 order → Zipkin UI search có đúng 1 trace với ≥ 4 span (POST /orders, kafka.send order.created, kafka.receive order.created, kafka.send inventory.reserved). Grep `@NewSpan` để confirm KHÔNG dùng API cũ.

## 👥 Tech Lead Lens (Day 9 trigger)

- **Trade-off chính**: Async decouple + scale NHƯNG complexity (state machine 2 dimension), eventual consistency, outbox/DLT debt. **Scale 10x (2M DAU)**: outbox bắt buộc; sampling tail-based; partition tăng theo order rate (key=orderId giữ ordering per-order); consumer group instance horizontal.
- **Production failure mode + 5-step triage**:
  1. Alert "P95 reservation lag > 5s" fire.
  2. Kafka UI: check consumer lag inventory-service group `order.created`.
  3. Inventory log: throughput drop? Optimistic-lock retry storm? DB pool saturated?
  4. Zipkin: tìm trace có gap dài → identify span chậm.
  5. Mitigate: scale inventory consumer instance / tăng partition / temp tăng `@Retryable` backoff.
- **Junior + AI 2 lỗi dễ nhất**:
  1. **Quên Kafka observation flag** → trace gãy ở producer→consumer boundary. Code Review: grep `observation-enabled` ở mọi service yaml có Kafka.
  2. **Skip dual-write doc** → AI publish ngay sau `repo.save()` không log warning. Code Review: bắt buộc TODO Day 13 outbox + link issue 09.

## 🔗 Related

- Code: [`PlaceOrderUseCase`](../../services/order-service/src/main/java/com/ecommerce/order/application/PlaceOrderUseCase.java) · [`OrderCreatedConsumer`](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java) · [`InventoryReservedConsumer`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/messaging/InventoryReservedConsumer.java)
- Docs: [lesson 09](../lessons/09-distributed-tracing-otel.md) · [lesson 09b](../lessons/09b-eventual-consistency-window.md) · [issue 09](../issues/09-eventual-consistency-order.md) · [ADR-006](../decisions/006-sync-orchestration-vs-async-events.md)
