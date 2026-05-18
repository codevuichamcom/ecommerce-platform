# 🎤 Interview — Day 8: Kafka setup + Feign vs HTTP Interface

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — series A ecommerce, ~50 dev, đang chuyển từ monolith sang microservice. Cuối quý phải release event flash-sale **11.11** (volume × 50 normal day).
- **Role giao việc**: **Anh Hùng (Tech Lead, ex-Tiki)** chỉ đạo: "Trước flash-sale, order flow phải async hoá — sync orchestration Day 6 không scale lên 5000 RPS được. Tuần này em setup Kafka foundation cho monorepo + định nghĩa schema event. Song song, PM Linh đang push migrate Feign sang HTTP Interface vì 'nghe đồn gọn hơn' — em đánh giá thật, ra 1 ADR ≥3 alternatives, team align trước khi viết code Day 9."
- **Bạn**: Backend engineer ownership messaging foundation + sync HTTP client policy cho 9 service monorepo.
- **Reviewer**: Anh Hùng (soi: topic naming convention, idempotent producer config có đúng `acks=all + idempotent + max.in.flight ≤ 5` không, schema versioning chiến lược, ADR có ≥3 alternatives + numeric trade-off không. Anh từng bị data loss ở Tiki vì `acks=1` default; sẽ hỏi rất kỹ section "issue 08").
- **Deadline**: 1 sprint day (Kafka up + 2 service publish/consume demo + ADR Feign vs HTTP Interface + 6 docs).
- **Constraint thực tế**:
  - KHÔNG break Day 6 sync `placeOrder` flow (Day 9 mới chuyển event-driven).
  - `common-lib` auto-config phải opt-in via property `app.kafka.enabled=true` — service nào không cần Kafka không bị kéo dependency runtime (auth-service ví dụ).
  - Spring Boot 3.4.5 + Java 21 + virtual thread consumer (KafkaListener task executor bật virtual).
  - Spring Cloud BOM align với Boot release train (2024.0.0 ↔ 3.4.x).
- **Definition of Done**:
  - (a) `docker compose up kafka kafka-ui` lên xanh.
  - (b) `order-service` publish `OrderCreatedV1` qua debug endpoint, `notification-service` consumer log message nhận được + verify thread virtual.
  - (c) `order-service` gọi `product-service /products/{sku}/snapshot` qua **cả** Feign + HTTP Interface — 2 client coexist, smoke test PASS cả 2.
  - (d) ADR-005 chọn HTTP Interface cho code mới + lý do evidence-based.
  - (e) Issue 08 ghi lại "acks=1 mất message" với 4 approaches compared.
  - (f) Build green 6 service + tests PASS.

---

## Q1 — Khác nhau `acks=0/1/all` và idempotent producer làm gì?

**Strong answer (Việt + English term)**:

`acks` là cờ producer quyết định **durability vs latency trade-off**:

- `acks=0` — fire-and-forget, broker chưa kịp ghi cũng trả ack. Latency thấp nhất, mất message bất kỳ lúc nào broker chậm.
- `acks=1` (default cũ ≤ Kafka 2.x) — chờ **leader ghi xong** mới ack. Nhưng nếu leader fail TRƯỚC khi follower replicate → message gone. **Đây chính là root cause issue 08 mất 0.3% event**.
- `acks=all` — chờ **tất cả ISR (in-sync replicas)** ghi xong. Mất message chỉ khi toàn bộ ISR cùng chết (rare). Latency cao nhất nhưng vẫn ~5ms.

**Idempotent producer** = producer gắn `PID (Producer ID) + sequence number` vào mỗi record. Broker dedup theo `(PID, partition, sequence)` → retry KHÔNG tạo duplicate trong cùng session. Kafka ≥ 3.0 idempotent là default mặc định bật.

Setup chuẩn cho ecommerce: `acks=all + enable.idempotence=true + max.in.flight ≤ 5 + retries=∞`. Đây là wire ở [`KafkaAutoConfiguration:90-108`](../../common-lib/src/main/java/com/ecom/common/autoconfig/KafkaAutoConfiguration.java#L90-L108).

**Follow-up trap**: "acks=all = exactly-once?"
**Trả lời**: KHÔNG. Exactly-once cần thêm **transactional producer** (`initTransactions`, `beginTransaction`, `sendOffsetsToTransaction`) + consumer `isolation.level=read_committed`. `acks=all + idempotent` chỉ đảm bảo "at-least-once + dedup retry trong session" — đủ cho 95% use case ecommerce nếu consumer side idempotent theo `eventId`. Transactional overhead 10-20% throughput, chỉ cần khi có producer-consumer chain (vd Kafka Streams).

---

## Q2 — Partition key chọn thế nào để giữ ordering nhưng tránh hot partition?

**Strong answer**:

Key partition quyết định 2 thứ:

1. **Same key → same partition → ordered** (Kafka đảm bảo per-partition ordering).
2. **Hash key % N partition** — phân tải.

Chiến lược chọn key:
- **Per-aggregate ordering**: key = `aggregateId`. Day 8 order-service publish `order.created` với key = `orderId` → consumer xử event của cùng 1 order theo đúng thứ tự `created → paid → shipped → delivered`.
- **Cross-aggregate KHÔNG cần ordering**: key = null → round-robin partition, max throughput.

**Hot partition pitfall**: nếu key skewed (vd flash sale event toàn về cùng 1 SKU → 90% event cùng 1 key → 1 partition nghẽn). Fix:
- **Composite key**: `${sku}-${userId % 100}` → spread 100 partition.
- **Custom partitioner**: detect hot key, bypass default hash.
- **Pre-shard**: tăng partition count trước flash sale (KHÔNG giảm được sau).

Day 33 sẽ deep-dive ở flash sale system design.

**Follow-up trap**: "KHÔNG set key = round-robin = OK?"
**Trả lời**: Đúng cho throughput, **mất ordering**. Nếu downstream consumer assume order (vd state machine `created → paid`) → race condition. Phải đánh giá: workload có cần per-aggregate order không.

---

## Q3 — Consumer rebalance gây stop-the-world consume — fix?

**Strong answer**:

Rebalance = Kafka redistribute partition giữa consumer trong cùng group. Trigger:
- Consumer crash / restart.
- New consumer join group.
- `session.timeout.ms` (default 45s) vượt mà chưa heartbeat.

**Eager rebalance protocol cũ** (default ≤ Kafka 2.3): toàn bộ group stop consume, revoke all partition, redistribute. Downtime ~5-30s tuỳ partition count.

Fix modern (Kafka 2.4+):
1. **Cooperative-sticky rebalance protocol** (`partition.assignment.strategy=CooperativeStickyAssignor`) — chỉ revoke partition cần move, các consumer khác consume tiếp.
2. **Static membership** (`group.instance.id=<unique>`) — restart trong `session.timeout.ms` window thì Kafka KHÔNG trigger rebalance, dùng lại assignment cũ. Hữu ích cho rolling deploy K8s pod restart.
3. **Tăng heartbeat frequency** không phải fix — chỉ delay symptom.

**Follow-up trap**: "Tăng `session.timeout.ms` để tránh rebalance?"
**Trả lời**: SAI fix. Tăng timeout chỉ delay detection consumer chết, KHÔNG fix downtime khi thật sự cần rebalance. Đúng fix là **cooperative-sticky + static membership**.

---

## Q4 — Feign vs HTTP Interface, chọn gì cho greenfield Spring Boot 3.4?

**Strong answer**:

**HTTP Interface** cho code mới, KHÔNG migrate Feign legacy nếu chạy ổn. 3 lý do dominant (ADR-005):

1. **Version coupling thấp hơn** — HTTP Interface gắn Spring Framework 6.1 core, KHÔNG cần Spring Cloud BOM. Feign cần Spring Cloud release train alignment (Boot 3.4 ↔ Cloud 2024.0.0). Lệch = startup fail.
2. **Underlying client chọn được** — `RestClient` (blocking, virtual thread native) hoặc `WebClient` (reactive). Feign khoá Apache HC.
3. **Virtual thread paradigm fit** — project Day 2 đã chốt `spring.threads.virtual.enabled=true` blocking style. HTTP Interface + RestClient match; Feign sync cũng OK nhưng không native.

**Khi nào VẪN dùng Feign**:
- Brownfield đã có Spring Cloud LoadBalancer + Eureka/Consul.
- Team thuộc Feign pattern, migrate cost > ROI.
- Cần `feign-resilience4j` annotation tích hợp sẵn (Day 12 sẽ wire HTTP Interface decorator riêng — boilerplate nhưng controlled).

**Follow-up trap**: "Feign benchmark chậm hơn HTTP Interface?"
**Trả lời**: SAI lý do. Hai cái performance gần như identical (proxy → reflective call → HTTP). Lý do chọn KHÔNG phải performance — là **dependency footprint + paradigm fit + version coupling**. Senior interviewer hỏi câu này để bẫy candidate cargo-cult.

---

## Q5 — Schema versioning: thêm field vào `OrderCreatedV1` thì sao?

**Strong answer**:

Phân biệt 2 loại change:

| Loại change          | Ví dụ                                  | Strategy                                |
| -------------------- | -------------------------------------- | --------------------------------------- |
| **Additive**         | Thêm field `loyaltyPointsEarned` mới   | KHÔNG bump version. Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false` → consumer cũ ignore. |
| **Breaking**         | Xoá `currency`, đổi `totalAmount` type | Tạo `OrderCreatedV2` + topic mới `order.created.v2`. Migration window dual-publish. |

Day 8 wire `eventVersion=1` trong [`DomainEvent`](../../common-lib/src/main/java/com/ecom/common/event/DomainEvent.java) làm metadata; producer luôn ghi `v1`. Khi breaking change Day N → producer publish CẢ 2 topic (v1 + v2) trong migration window (vd 2 tuần). Consumer migrate sang v2 trước; producer drop v1 sau khi confirm 0 lag.

**Follow-up trap 1**: "Dùng Avro/Protobuf + Schema Registry?"
**Trả lời**: Đáng nếu org > 50 service + breaking change frequency cao. Project hiện tại 9 service + JSON đủ; Schema Registry thêm ops cost (Confluent license hoặc Apicurio self-host) + cargo-cult nếu chưa đụng pain. Trade-off: JSON breaking change phát hiện **runtime**, không compile-time. Bù bằng contract test trong CI (Day 14 wire).

**Follow-up trap 2**: "Đổi tên field `totalAmount` → `grandTotal` là additive hay breaking?"
**Trả lời**: **Breaking** — consumer code đọc `totalAmount` sẽ fail deser. Đúng cách: ADD field `grandTotal` mới (additive) + giữ `totalAmount` deprecated → migrate consumer → drop `totalAmount` ở v2.

---

## 🧠 Senior mindset notes

- AI generate Kafka config mặc định `acks=1` (training data có nhiều tutorial cũ Kafka 2.x). Review checklist: producer config phải có `acks=all` + idempotent + `max.in.flight ≤ 5`. Issue 08 ghi lại evidence cụ thể bằng staging incident.
- "Migrate tất cả Feign → HTTP Interface" là kiểu PM-decision sai hướng. Senior call: greenfield dùng HTTP Interface, brownfield giữ Feign nếu hoạt động ổn (rule of "don't migrate working code without ROI"). Doc ADR-005 evidence-based để pushback non-technical decision.
- Scale 10x: 5 topic flat naming `order.created` OK cho 9 service; ở 50+ service nên migrate namespace `<bounded-context>.<aggregate>.<event>.<version>` (vd `commerce.order.created.v1`) để rõ ownership team + tooling routing. Day 8 KHÔNG over-engineer — note trong ADR khi nào migrate.

---

## 🤖 AI Playbook

- **AI làm tốt**: scaffold `KafkaProducerFactory` config, sinh JSON event record (5 record `OrderCreatedV1` / `StockReservedV1` / ...), generate Feign vs HTTP Interface code snippet side-by-side cho lesson, draft skeleton ADR.
- **Prompt mẫu**:
  > Generate Spring Boot 3.4 `KafkaAutoConfiguration` class with **idempotent** producer (`acks=all`, `enable.idempotence=true`, `max.in.flight=5`, `delivery.timeout.ms=120s`), `JsonMessageConverter`, `ConcurrentKafkaListenerContainerFactory` with virtual threads via `SimpleAsyncTaskExecutor.setVirtualThreads(true)`. Conditional on property `app.kafka.enabled=true`. Service can override beans via `@ConditionalOnMissingBean`.
- **Risk**:
  - AI dễ generate `acks=1` (training data Kafka 2.x cũ), quên `max.in.flight` cap → idempotent KHÔNG đảm bảo ordering khi retry.
  - AI có thể đặt `KafkaTemplate.send()` không return `CompletableFuture` → caller swallow error → silent data loss.
  - AI tự bịa method `getContainerProperties().setVirtualThreads(true)` (Spring Kafka chưa expose direct — phải qua `setListenerTaskExecutor(SimpleAsyncTaskExecutor)`). **Xảy ra ngay Day 8 build đầu tiên** — fix bằng task executor adapter.
- **Validate**:
  - Integration test assert producer config: `kafkaTemplate.getProducerFactory().getConfigurationProperties().get(ProducerConfig.ACKS_CONFIG).equals("all")`.
  - Testcontainers kill-leader scenario (Day 14 wire).
  - Build green + log listener `thread=... virtual=true` khi nhận message.

---

## 👥 Tech Lead Lens (Day 8 ∈ trigger {1, 4, 6, **8**, 9, 12, 13, 15, 19, 22, 23, 24, 31, 33})

- **Trade-off chính**: JSON event schema vs Avro + Schema Registry.
  - **Hiện tại** (9 service, breaking change rare): JSON + Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false` + contract test CI. Cost ops 0.
  - **Scale 10x** (50+ service, > 5 breaking change/quarter): migrate Avro + Confluent Schema Registry (hoặc Apicurio open). Cost: 1 ops engineer maintain registry + license/self-host. Đổi lại compile-time contract enforcement.
  - Pivot signal: khi có > 1 incident/quarter do breaking change phá consumer → ADR migration plan.

- **Production failure mode**: Consumer lag spike đột ngột sau deploy. **5-step triage**:
  1. `kafka-consumer-groups --describe --group <svc>` xem lag per partition. 1 partition lag = poison message; tất cả partition lag = consumer chậm chung.
  2. Check consumer pod CPU/mem/GC pause (JVM heap > 80% → GC freeze).
  3. Check broker `UnderReplicatedPartitions` metric > 0 → broker side issue, không phải consumer.
  4. Compare consumer P99 process time với baseline trước deploy → nếu chậm hơn 2x → revert.
  5. Nếu poison message → tách partition đó (manual offset advance qua `kafka-consumer-groups --reset-offsets`) + bug report ngay.

- **Junior + AI viết phần này, 2 lỗi dễ nhất**:
  1. **`@KafkaListener` KHÔNG set `containerFactory` virtual-thread executor** → consumer chạy platform thread → 10x throughput loss khi listener I/O heavy (DB write, HTTP forward). **Review kỹ**: `KafkaAutoConfiguration` factory bean phải có `setListenerTaskExecutor(SimpleAsyncTaskExecutor.setVirtualThreads(true))`. Log message phải có `virtual=true`.
  2. **Producer KHÔNG `flush()` trước shutdown** → message in-flight mất khi pod scale down hoặc rolling deploy. **Review kỹ**: `@PreDestroy` hook trên `KafkaTemplate` hoặc set `spring.kafka.listener.shutdown-timeout=30s` để container drain. Day 12 wire chính thức.

---

## 🔗 Related

- Source: [`KafkaAutoConfiguration`](../../common-lib/src/main/java/com/ecom/common/autoconfig/KafkaAutoConfiguration.java) · [`OrderEventPublisher`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/messaging/OrderEventPublisher.java) · [`OrderEventListener`](../../services/notification-service/src/main/java/com/ecommerce/notification/listener/OrderEventListener.java) · [`ProductFeignClient`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductFeignClient.java) · [`ProductHttpInterfaceClient`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/client/ProductHttpInterfaceClient.java)
- Docs: [lesson 08 — kafka-basics](../lessons/08-kafka-basics.md) · [lesson 08b — feign-vs-http-interface](../lessons/08b-feign-vs-http-interface.md) · [issue 08 — message-loss-acks](../issues/08-kafka-message-loss-acks-default.md) · [architecture event-driven-flow](../architecture/event-driven-flow.md) · [ADR-005 feign-vs-http-interface](../decisions/005-feign-vs-http-interface.md)
- Day 9 chain: order flow event-driven + OpenTelemetry trace propagate qua Kafka headers
- Day 12 chain: Resilience4j circuit breaker wire cho HTTP Interface + retry + DLT
- Day 13 chain: outbox pattern fix dual-write
