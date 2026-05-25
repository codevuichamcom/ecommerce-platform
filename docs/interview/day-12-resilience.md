# Day 12 — Resilience interview Q&A

> **Topic**: Retry strategy, Dead Letter Topic, Resilience4j Circuit Breaker + Bulkhead, Kafka delivery semantics + partition ordering.
> **Day**: 12 · **Status**: ✅ Done

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN (Series A, ~2M MAU, Kafka cluster 3-broker mới scale-out tuần trước)
- **Role giao việc**: Anh Hùng — Tech Lead backend, 8 YOE
- **Bạn**: Senior Backend Engineer (Tonny) — own resilience layer cho Week 2 Kafka
- **Reviewer**: Anh Hùng + DevOps Lead Mạnh (soi observability: CB state metric, DLT alert)
- **Deadline**: 1 sprint day, demo cuối tuần review meeting
- **Constraint thực tế**:
  - Tuần trước có incident SEV-2: `notification-service` NPE crash loop khi consume 1 event payload schema lỗi → consumer lag 200k message trong 4h
  - Payment gateway (mock VNPay) thỉnh thoảng 503 spike → KHÔNG được block luồng order
  - Retry KHÔNG được gây retry storm — exp backoff bắt buộc
  - DLT có alert + runbook
- **Definition of Done**:
  - Integration test: throw exception → message vào `<topic>.DLT` sau 3 retry
  - CB OPEN sau 5 fail liên tục, HALF_OPEN sau 30s
  - Prometheus metric `resilience4j_circuitbreaker_state` + `notification.dlt.count` expose
  - Runbook recovery DLT có 5 bước cụ thể

---

## 🎤 Q&A

### Q1 — Retry với fixed delay vs exponential backoff, khi nào dùng cái nào?

**Strong answer (Việt + English term)**

Fixed delay chỉ dùng cho retry ngắn N≤2 với low concurrency. Production default là **exponential backoff** vì 2 lý do:
1. Cho downstream thời gian recover (1s → 4s → 16s — tăng dần)
2. Phân tán retry chống **thundering herd** — khi N consumer cùng wake same time gây spike

Thêm **jitter** (full jitter hoặc decorrelated jitter — AWS pattern) biến delay deterministic → range, chống đồng pha. Spring `ExponentialBackOff` mặc định KHÔNG có jitter — phải config `randomizationFactor` hoặc dùng `UniformRandomBackOffPolicy` của Spring Retry.

Cap `maxBackoff` (project chọn 16s) — sau threshold đó vẫn fail thì là persistent failure, không transient → DLT.

**Follow-up trap**: *"`maxAttempts` cao hơn có an toàn hơn không?"* — KHÔNG. Mỗi attempt block 1 consumer thread → backlog. Project 3 attempts × exp = ~21s/message; volume cao phải switch sang non-blocking retry topic.

---

### Q2 — Giải thích state machine Circuit Breaker?

**Strong answer**

3 state:
- **CLOSED**: default, request đi qua bình thường, CB count fail trong sliding window
- **OPEN**: failure rate vượt threshold (vd 50% / 10 call) → reject MỌI call NGAY (fast-fail sub-ms, fallback chạy), KHÔNG gọi downstream
- **HALF_OPEN**: sau `waitDurationInOpenState` (30s), cho phép N probe call (vd 3); pass hết → CLOSED, bất kỳ fail → OPEN lại

Mục đích: cho downstream thời gian recover thay vì retry storm tăng load. CB **bổ sung** Bulkhead — Bulkhead cap concurrent (chống "downstream slow"); CB fast-fail (chống "downstream down").

**Follow-up trap**: *"Sliding window count vs time, chọn cái nào?"* — Phụ thuộc traffic. Low traffic (~10/s) → count-based (mỗi N call ratio chính xác). High traffic (~1000/s) → time-based (window slide đều). Mistake: time-based cho low traffic → 1 fail = trip giả.

---

### Q3 — Khi nào KHÔNG nên retry?

**Strong answer**

3 trường hợp:
1. **Validation / 4xx error** — retry không bao giờ pass, lãng phí + log noise
2. **Non-idempotent operation chưa có dedup** — retry gây side-effect đôi (double-charge, double-email)
3. **Business rule violation** — "insufficient stock", "duplicate order" — retry không fix logic

Pattern đúng: classify exception trước retry. Spring Kafka `DefaultErrorHandler.addNotRetryableExceptions(...)` — `IllegalArgumentException`, `DeserializationException` → DLT ngay, skip retry budget.

**Follow-up trap**: *"Idempotent rồi retry vô tư?"* — KHÔNG. Retry tốn resource, vẫn cần cap maxAttempts để tránh waste.

---

### Q4 — DLT vs retry topic vs sidetrack queue?

**Strong answer**

- **DLT (Dead Letter Topic)** — terminal. Message vào đây = đã hết retry budget = cần human triage. KHÔNG auto-replay nếu chưa diagnose root cause (infinite loop).
- **Retry topic** — delay header + dedicated consumer xử lý sau N giây/phút. Pattern non-blocking — consumer chính không bị stuck.
- **Sidetrack queue** — riêng partition/topic cho slow processing. Phân biệt với retry topic ở chỗ sidetrack không có hard cap retry, dùng cho async batch.

Day 12 chọn **retry-then-DLT**: retry trong-process 3 lần exp backoff, fail → DLT. Volume thấp (~10/s notification) acceptable. Volume cao (>100/s) phải migrate sang `@RetryableTopic` non-blocking.

**Follow-up trap**: *"DLT có nên auto-replay không?"* — KHÔNG mặc định. Runbook ép classify (code bug / data bad / transient) trước replay. Auto-replay khi code chưa fix = infinite cycle.

---

### Q5 — Bulkhead semaphore vs threadpool, chọn cái nào?

**Strong answer**

- **Semaphore** — đếm concurrent permit, lightweight, KHÔNG isolate thread (caller thread vẫn chạy). Lock-free, overhead vài chục ns.
- **Threadpool** — call chạy trong pool riêng, isolate hoàn toàn. Overhead cao (context switch + queue) nhưng caller thread không bị block khi downstream chậm.

Hystrix default threadpool; Resilience4j default semaphore. Với **virtual thread** (Day 8 project bật), semaphore là default đúng — VT không cần isolate vì cheap (~vài KB stack). Threadpool chỉ khi cần timeout cứng mà downstream không có client-side timeout — wrap với `TimeLimiter` (Resilience4j) đủ.

**Follow-up trap**: *"Virtual thread cần Bulkhead?"* — VẪN CẦN. VT chỉ giảm cost thread; Bulkhead bảo vệ **downstream connection pool** + **memory** từ overload. VT spawn 100k thread cũng không bảo vệ DB connection pool size 20.

---

## 🤖 AI Playbook

- **AI làm tốt**: generate boilerplate `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` setup, exp backoff formula, R4j yaml config, 9-section issue template fill. Cũng tốt cho fallback method signature matching.
- **Prompt mẫu**: `"Spring Kafka 3.4 DefaultErrorHandler với DeadLetterPublishingRecoverer giữ partition affinity, ExponentialBackOff 1s/4s/16s max 3 attempts, addNotRetryableExceptions(IllegalArgumentException, DeserializationException), setCommitRecovered(true). Production-grade Java 21 record-style."`
- **Risk**: AI hay sinh `FixedBackOff` thay vì `ExponentialBackOff`; quên `addNotRetryableExceptions()` → validation lỗi retry lãng phí; default `DeadLetterPublishingRecoverer` round-robin partition → mất ordering DLT replay; `setCommitRecovered(false)` → DLT publish ok nhưng offset không commit → loop.
- **Validate**: integration test thực sự gửi poison message + assert DLT topic có message + assert original offset committed; review yaml `recordExceptions` có whitelist đúng exception; check metric `resilience4j_*` expose qua Micrometer Prometheus.

---

## 👥 Tech Lead Lens

- **Trade-off chính**: retry-then-DLT block partition ~21s/poison vs DLT-ngay false positive cao. Project Day 12 volume thấp (~10/s notification) → block acceptable. **Scale 10x** (100k/s): switch sang `@RetryableTopic` non-blocking — Spring Kafka 2.8+ pattern, mỗi delay tier có topic riêng (retry-1s, retry-10s, retry-1m...), consumer chính thoát ngay, dedicated consumer xử lý delay topic.
- **Production failure mode**: DLT đầy mà alert không kích → 1 tuần sau phát hiện 50k message orphan, schema đã đổi, không biết replay được không. **5-step triage**:
  1. Check Grafana `notification.dlt.count` dashboard rate
  2. `kafka-console-consumer --topic *.DLT --max-messages 10` dump payload + headers
  3. Classify exception FQCN → producer bug / schema drift / transient / consumer code bug
  4. Fix root cause → deploy → replay với script idempotent (dedup TTL phải còn hiệu lực)
  5. Post-mortem ghi `leadership/incidents.md` với 5-whys + prevention
- **Junior + AI 2 lỗi dễ nhất**:
  1. **Copy `DefaultErrorHandler` config từ blog cũ (pre-2.8)** → retry vô hạn vì không set `ExponentialBackOff` hoặc `FixedBackOff(0, 9)`. **Review kỹ**: `maxAttempts` cap chưa, `setCommitRecovered(true)` chưa, có DLT recoverer chưa.
  2. **DLT consumer cũng throw exception** → message rơi vào `*.DLT.DLT` cascade → infinite (Spring Kafka default suffix `.DLT` lặp). **Review kỹ**: DLT consumer phải có try-catch swallow + log + metric, **TUYỆT ĐỐI KHÔNG throw**.

---

## 🔗 Related

- [`lessons/12-retry-strategy.md`](../lessons/12-retry-strategy.md) · [`12b-circuit-breaker-resilience4j.md`](../lessons/12b-circuit-breaker-resilience4j.md) · [`12c-kafka-delivery-semantics.md`](../lessons/12c-kafka-delivery-semantics.md) · [`12d-partition-key-ordering.md`](../lessons/12d-partition-key-ordering.md)
- [`issues/12-poison-message.md`](../issues/12-poison-message.md) · [`runbooks/kafka-topic-recovery.md`](../runbooks/kafka-topic-recovery.md)
- Code: [`RetryTopologyConfiguration.java`](../../services/notification-service/src/main/java/com/ecommerce/notification/messaging/RetryTopologyConfiguration.java) · [`DltConsumer.java`](../../services/notification-service/src/main/java/com/ecommerce/notification/messaging/DltConsumer.java) · [`MockGatewayClient.java`](../../services/payment-service/src/main/java/com/ecommerce/payment/gateway/MockGatewayClient.java)
