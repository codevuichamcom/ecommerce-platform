# 📋 Week 2 — CV bullets (Senior Backend, ecommerce platform)

> **Day 14 deliverable.** Compile từ Day 8-13 thành **2 bullet metric-driven**
> + cập nhật elevator pitch.
>
> **Honest disclaimer**: vẫn là **personal-lab learning** (40-day solo + AI),
> không phải production traffic thật. Wording chọn từ defendable: "designed",
> "implemented", "achieved at-least-once with deduplication" — KHÔNG claim
> "exactly-once" (false), KHÔNG claim "led team" (không có team).

---

## 🎯 Bullet 1 — Event-driven foundation + dual-write resolution

> **Implemented an event-driven order flow on Apache Kafka (idempotent
> producer with `acks=all` + `enable.idempotence=true` + `max.in.flight=5`,
> manual-commit consumer with `isolation.level=read_committed`) achieving
> at-least-once delivery with consumer-side deduplication (Redis SET NX,
> 24h TTL) for exactly-once-effects; resolved the dual-write hazard between
> Postgres and Kafka via a transactional outbox pattern — single-tx
> business-write + outbox INSERT, with a `@Scheduled(fixedDelay=1s)` relay
> using `SELECT FOR UPDATE SKIP LOCKED` (Postgres lock-timeout `-2`) for
> multi-instance race-free polling and `REQUIRES_NEW` per-event tx isolation;
> verified via 8 unit tests covering relay success, retry-on-failure,
> give-up-after-max-attempts, and batch-publish scenarios.**

**Evidence trail**:
- Common: [`KafkaAutoConfiguration.java`](../../common-lib/src/main/java/com/ecom/common/autoconfig/KafkaAutoConfiguration.java) (producer/consumer hardening + virtual-thread listener executor)
- Outbox: [`OutboxRelay.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxRelay.java) · [`OutboxRecorder.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/outbox/OutboxRecorder.java) · [`V3 migration`](../../services/order-service/src/main/resources/db/migration/V3__create_outbox_event.sql) (partial index PENDING)
- Tests: 8 outbox unit (3 recorder + 5 relay) — total 24 unit PASS Week 2
- Docs: [issue 08 message loss](../issues/08-kafka-message-loss-acks-default.md) · [issue 13 outbox debt](../issues/13-order-paid-inventory-not-reserved.md) · [lesson 08 Kafka basics](../lessons/08-kafka-basics.md) · [lesson 13 outbox](../lessons/13-outbox-pattern.md) · [lesson 13b dual-write](../lessons/13b-dual-write-problem.md) · [ADR-009 outbox-vs-cdc](../decisions/009-outbox-vs-cdc.md) (5 alternatives)

**Talking points** (45s pitch):
1. *Why outbox over Debezium CDC*: DBA chưa enable `wal_level=logical`; team < 3 dev không vận hành Debezium connect cluster; outbox app-controlled + schema explicit. Migration path: Debezium "Outbox Event Router" SMT đọc table giữ nguyên khi volume > 10k events/s.
2. *Why at-least-once + dedup, not Kafka transactions*: 5-10% throughput overhead + debugging cost không justify cho ecom flow. Dedup `eventId` Redis SET NX 24h đủ.
3. *Why SKIP LOCKED + REQUIRES_NEW*: multi-instance race-free + 1 event lỗi không rollback batch. Hibernate `lock.timeout=-2` magic number cho SKIP LOCKED native Postgres.

---

## 🎯 Bullet 2 — Resilience + observability + decision discipline

> **Hardened the async pipeline with Resilience4j circuit breaker
> (`paymentGateway`, sliding window 10, 50% failure → OPEN 30s → HALF_OPEN
> 3 probe) and semaphore bulkhead (max-concurrent=10, fail-fast); designed
> Kafka consumer retry topology with Spring `DefaultErrorHandler` +
> exponential backoff (1s→4s→16s, max-elapsed 21s) and
> `DeadLetterPublishingRecoverer` preserving partition affinity for poison
> messages with non-retryable classification (`IllegalArgumentException`,
> `JsonProcessingException`, `DeserializationException` → DLT immediately,
> no retry); wired distributed tracing via Micrometer + OpenTelemetry
> with W3C `traceparent` propagation across HTTP→Kafka→consumer spans
> (Zipkin local). Authored 4 ADRs in Week 2 (Feign vs HTTP Interface,
> sync orchestration vs async events, payment layered (not DDD), API
> versioning strategy, outbox vs CDC) each comparing ≥3 alternatives.**

**Evidence trail**:
- Resilience: [`MockGatewayClient.java`](../../services/payment-service/src/main/java/com/ecommerce/payment/infrastructure/gateway/MockGatewayClient.java) (@CircuitBreaker + @Bulkhead + fallback) · [`RetryTopologyConfiguration.java`](../../services/notification-service/src/main/java/com/ecommerce/notification/messaging/RetryTopologyConfiguration.java) · [`DltConsumer.java`](../../services/notification-service/src/main/java/com/ecommerce/notification/messaging/DltConsumer.java)
- Tracing: Spring Kafka observation-enabled + Zipkin (`docker-compose.yml` port 9411)
- Tests: `MockGatewayClientCircuitBreakerTest` (CLOSED→OPEN, fast-fail OPEN, HALF_OPEN→CLOSED) + `RetryTopologyConfigurationTest` (recoverer wiring + non-retryable classification)
- ADRs: [005](../decisions/005-feign-vs-http-interface.md) · [006](../decisions/006-sync-orchestration-vs-async-events.md) · [007](../decisions/007-payment-service-layered-not-ddd.md) · [008](../decisions/008-api-versioning-strategy.md) · [009](../decisions/009-outbox-vs-cdc.md)
- Docs: [lesson 12 retry](../lessons/12-retry-strategy.md) · [lesson 12b circuit-breaker](../lessons/12b-circuit-breaker-resilience4j.md) · [lesson 12c delivery semantics](../lessons/12c-kafka-delivery-semantics.md) · [lesson 12d partition key](../lessons/12d-partition-key-ordering.md) · [runbook kafka-topic-recovery](../runbooks/kafka-topic-recovery.md)

**Talking points** (45s pitch):
1. *Why partition affinity in DLT*: re-drive cùng partition giữ ordering invariant. Junior viết DLT thường mất affinity → ordering break sau redrive.
2. *Non-retryable classification*: retry `IllegalArgumentException` = lãng phí (data sai sẽ vẫn sai). Phân loại rõ pre-retry = chìa khóa avoid retry storm.
3. *4 ADR / week*: senior discipline — mọi quyết định lớn có ≥3 alternative compared. Junior ghi "đã chọn X" — senior ghi "có 3 cách, chọn X vì context Y, hy sinh Z".

---

## 🧠 Senior interview elevator pitch v2 (90s, dùng cuối screen — refresh Week 2)

> *"Tôi đang build ecommerce platform 40-day để ôn senior backend. Week 1
> deliver 5 microservice DDD selectively, Week 2 vừa rồi ship event-driven
> flow trên Kafka. Điểm tôi đầu tư sâu là Day 13 outbox — trả debt dual-write
> giữa Postgres và Kafka mà Day 9 đã thừa nhận. Outbox row INSERT cùng tx
> với business write, scheduled relay 1s poll + SKIP LOCKED + REQUIRES_NEW
> tx-per-event, multi-instance race-free nhờ Postgres native. Producer
> idempotent với acks=all, consumer dedup eventId qua Redis SET NX — at-least-once
> delivery với deduplication thành exactly-once-effects. KHÔNG dùng Kafka
> transactions vì 5-10% throughput overhead không justify. Resilience Day 12
> đan lưới Resilience4j circuit breaker + bulkhead cho outbound payment
> gateway, plus retry topology với DLT preserve partition affinity và
> non-retryable classification. 4 ADR / tuần — mỗi quyết định lớn so ≥3
> alternative, hy sinh cụ thể. Week 3 sắp tới tôi sẽ load test với k6,
> identify bottleneck thật ở 10x traffic — có gap đã ghi rõ trong review/kafka-week2-findings.
> Approach của tôi là production-grade ở core, production-realistic ở docs,
> và brutally honest ở review — vì 6 tháng nữa đọc lại tôi vẫn defend được."*

---

## 🔗 Related

- All 6 Week 2 day docs: [day-08](day-08-kafka.md) · [day-09](day-09-order-flow.md) · [day-10](day-10-payment.md) · [day-11](day-11-notification.md) · [day-12](day-12-resilience.md) · [day-13](day-13-outbox.md)
- Mock interview self-grade: [week-02-mock.md](week-02-mock.md) — 9 strong / 1 borderline / 0 fail
- Review findings: [../review/kafka-week2-findings.md](../review/kafka-week2-findings.md)
- Week 1 CV bullets (precedence): [week-01-cv-bullets.md](week-01-cv-bullets.md)
- Portfolio polish (Day 38): [`portfolio-pitch-script.md`](portfolio-pitch-script.md) — sẽ build cuối Week 7
