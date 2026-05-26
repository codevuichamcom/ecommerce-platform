# 🎤 Week 2 Mock — Senior Backend (Kafka deep-dive)

> **Day**: 14 — kết tuần 2. Stack Kafka + outbox + retry/DLT + observability đã merge.
> **Format**: 10 câu Kafka senior — 5 fundamentals + 5 production scenario.
> Self-grade brutally honest — borderline = chưa Senior.

---

## 🏢 Bối cảnh giả lập (round phỏng vấn)

- **Company**: Tiki (mock — Senior Backend, đã pass Week 1 round).
- **Interviewer**: Anh Tuấn — Senior Backend Tiki Order team, ex-Grab. Style A.
  Anh Tuấn vừa migrate Tiki order flow sang Kafka 2024 → câu hỏi rất sát case
  thật, không lý thuyết.
- **Bạn**: Senior candidate. Portfolio đã ship đến Day 13 (outbox).
- **Constraint**: 60 phút, ≤ 5 phút/câu, vẽ Mermaid được, không Google.
- **Definition of pass**: ≥ 7/10 strong, không câu nào fail. Borderline ≤ 2.

---

# Phần 1 — Kafka fundamentals (5 câu)

## Q1 — Bạn config `acks=all + idempotence + read_committed`. Hệ thống đang ở delivery semantics nào?

**Strong answer**:

> **At-least-once delivery + dedup ở consumer = exactly-once-effects**. KHÔNG
> phải exactly-once thật.
>
> Lý do:
> - `acks=all + idempotence`: producer side **không lost, không duplicate trong cùng session**. Broker dedup theo `(PID, sequence number)`. Producer restart → PID mới → idempotence reset → broker không biết → duplicate có thể.
> - `enable.auto.commit=false` + manual ack sau process: consumer **không miss**. Nhưng rebalance / consumer crash giữa process và ack → message replay → **duplicate**.
> - Vì vậy: ta nhận at-least-once. Để consumer xử lý duplicate → dedup `eventId` (Redis SET NX 24h ở notification, Day 11). Effect = exactly-once, delivery vẫn at-least-once.
>
> **Exactly-once thật** chỉ có với **Kafka transactions** (`transactional.id` + `initTransactions()` + `sendOffsetsToTransaction()`) — producer + offset commit trong 1 atomic tx. Project hiện tại KHÔNG dùng vì: (a) overhead 5-10% throughput, (b) khó debug, (c) dedup app-level đủ với business.

**Follow-up trap**: *"Vậy nếu producer restart, duplicate xảy ra, dedup ở consumer dùng eventId của ai?"*

> `eventId` ở **payload**, không phải Kafka metadata. Producer generate `UUID.randomUUID()` LÚC TẠO event và LƯU vào outbox table. Restart → outbox row còn nguyên → eventId không đổi → consumer dedup vẫn nhận diện duplicate. Đây là **Day 13 outbox + Day 11 dedup làm việc với nhau** — producer-side persistence + consumer-side dedup.

**Self-verdict**: ✅ **strong**. Có 3 layer (broker dedup / consumer dedup / Kafka tx), defend được trade-off.

---

## Q2 — `acks=all` nghĩa là gì exactly? `min.insync.replicas` để default sao thì sao?

**Strong answer**:

> `acks=all` (alias `-1`): leader chỉ ack producer sau khi **mọi ISR (in-sync replicas)** đã ghi log. ISR động — broker nào lag quá `replica.lag.time.max.ms` (default 30s) bị remove.
>
> **Trap**: nếu `replication.factor=3` nhưng `min.insync.replicas=1` (default) → ISR có thể shrink xuống 1 (chỉ leader) → `acks=all` thành `acks=1` về effect → leader fail trước follower catch-up → **message lost**.
>
> Best practice prod:
> - `replication.factor=3`, `min.insync.replicas=2` → cần ≥ 2 broker ack → tolerate 1 broker down vẫn durable.
> - Nếu ISR < 2 → producer nhận `NotEnoughReplicasException` → **fail-fast** thay vì giả vờ OK.
>
> Project hiện tại: docker-compose single-broker → `min.insync.replicas=1` acceptable cho dev. Prod ShopVN deploy 3-broker cluster → phải set `min.insync.replicas=2` ở topic config (override broker default).

**Follow-up trap**: *"Set `min.insync.replicas=replication.factor` được không?"*

> KHÔNG. Set = 3 trong cluster 3-broker → mất 1 broker → ISR còn 2 < 3 → producer fail. Availability giảm xuống tích. Quy tắc: `min.insync.replicas = replication.factor - 1` (tolerate 1 failure mà vẫn write được).

**Self-verdict**: ✅ **strong**. Defend được với số cụ thể + best practice.

---

## Q3 — Partition key = orderId cho `order.created`. Pros / cons? Hot partition khi nào?

**Strong answer**:

> **Pros**:
> - **Per-order ordering**: mọi event cùng order (created → paid → shipped) cùng partition → consumer xử lý đúng thứ tự (Kafka guarantee ordering within partition).
> - **Idempotent consumer dễ**: dedup `eventId` thuần app-level, không phải worry cross-partition race.
>
> **Cons** / **Hot partition**:
> - **Skew thấp** vì `orderId = UUID v4` distribute uniform. Hash modulo partition đều.
> - **Hot partition scenario thật**: 1 order có 100 lifecycle event (status update, log delivery, payment retry, ...). Nếu volume orders/giây thấp mà 1 order spawn quá nhiều event → partition của orderId đó nóng cục bộ trong short window. Acceptable vì transient.
> - **Hot scenario nguy hiểm hơn**: nếu đổi key thành `userId` → 1 user spam 1000 order trong promotion 30s → partition của user đó đập consumer single-thread (cùng partition không parallel). Throughput thắt cổ chai.
>
> **Quy tắc senior**: chọn key **nhỏ nhất đủ cho ordering invariant**. Order-level ordering đủ → key=orderId. KHÔNG over-key bằng userId trừ khi business cần "tất cả event của 1 user theo thứ tự".

**Follow-up trap**: *"Nếu cần thêm consumer parallelism cho 1 order — xử lý 3 item song song?"*

> KHÔNG dùng partition. Partition là **per-key serial**. Parallel ở consumer logic: consume 1 message → fan-out work vào executor (virtual thread). Hoặc đổi event schema: thay vì 1 `OrderCreatedV1` với 3 item → 3 event `OrderItemCreatedV1` key=itemId → consumer parallel. Trade-off: schema phức tạp hơn + downstream phải re-aggregate. Đa số case không cần.

**Self-verdict**: ✅ **strong**. Defend cả key choice + alternative.

---

## Q4 — Consumer group 5 instance. Rolling restart deploy → rebalance bao nhiêu lần?

**Strong answer**:

> Default strategy = **RangeAssignor / RoundRobinAssignor (eager)**: rebalance N lần với N = số instance restart, mỗi lần "stop-the-world" — toàn group dừng consume vài giây.
>
> Math: 5 instance, rolling restart 1-by-1 → **5 rebalance**, mỗi cái ~3-5s pause → tổng 15-25s consumer downtime. Trong campaign 10x traffic → lag tăng vài chục k message.
>
> **Fix senior**: chuyển sang **CooperativeStickyAssignor** (Kafka 2.4+):
> ```
> partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor
> ```
> - Incremental rebalance: chỉ revoke partition cần thiết, không stop-the-world.
> - Sticky: instance giữ lại partition cũ nếu có thể → cache state (Kafka Streams state store, local dedup cache) không vứt.
> - Pause per rebalance giảm từ giây xuống chục ms.
>
> Project hiện tại: chưa set explicit → default `RangeAssignor`. **F-finding** cần add Week 3.

**Follow-up trap**: *"Consumer mới join group middle of message processing — xử lý sao?"*

> Default: `max.poll.interval.ms=5min`. Consumer phải hoàn tất batch trong 5 phút, nếu không broker coi crashed → kick out → rebalance. Long processing message (vd: gọi external API 30s/message × batch 100) → vượt limit → infinite rebalance loop. Fix: (a) giảm `max.poll.records` xuống batch nhỏ hơn, (b) tăng `max.poll.interval.ms` lên 10min nếu legitimate, (c) chuyển heavy work ra background queue.

**Self-verdict**: ✅ **strong**. Có số cụ thể + defend được cooperative migration.

---

## Q5 — Schema evolution: thêm field mới vào `OrderCreatedV1`. Additive hay breaking? Consumer cũ chưa deploy?

**Strong answer**:

> **Additive (backward + forward compatible)** nếu:
> - Field MỚI **optional** (nullable / có default value).
> - Field CŨ **không xóa, không rename, không đổi type**.
> - JSON serializer KHÔNG strict — `FAIL_ON_UNKNOWN_PROPERTIES=false` ở Jackson.
>
> Day 8 ADR-005: project dùng JSON additive contract. Consumer cũ deploy chưa kịp xử lý field mới → Jackson ignore unknown property → vẫn deserialize OK với field cũ. Backward compat.
>
> Forward compat (consumer mới deploy trước producer mới): field mới = null/default → consumer mới phải xử lý "field này có thể null" gracefully. **KHÔNG assume non-null**.
>
> **Breaking change** (cần `OrderCreatedV2`):
> - Đổi semantic field cũ (`amount` từ VND sang USD).
> - Xóa field cũ.
> - Đổi type (`amount: long` → `amount: string`).
>
> Migration breaking: producer dual-publish V1 + V2 trong 1-2 tuần, consumer migrate dần V1 → V2, rồi tắt V1. Topic riêng `order.created.v2` thay vì version trong payload — cleaner.

**Follow-up trap**: *"Tại sao không dùng Avro / Protobuf + Schema Registry từ đầu?"*

> Trade-off Day 8: JSON đơn giản, debug được bằng kafka-console-consumer, không cần schema registry infra. Đủ cho team 1-3 dev. Khi nào nâng cấp Avro + registry:
> - Team > 3 dev → review convention không catch breaking.
> - Consumer external (đối tác, mobile app version cũ) → cần schema enforcement.
> - Storage cost matter → Avro binary nhỏ hơn JSON 3-5x.
>
> Project hiện tại chưa đạt threshold → JSON OK.

**Self-verdict**: ✅ **strong**. Defend trade-off + có migration path.

---

# Phần 2 — Production scenario (5 câu)

## Q6 — DLT chứa 10k message từ poison event. Strategy re-drive?

**Strong answer**:

> 5-step:
>
> 1. **Triage**: sample 50 message DLT. Group theo exception class (đã ghi header `kafka_dlt-exception-fqcn` ở Day 12 DefaultErrorHandler). Tìm pattern: cùng `IllegalArgumentException` cùng field null → bug producer. Cùng `DeserializationException` → schema mismatch.
> 2. **Root cause fix**: nếu producer bug → fix + redeploy. Schema mismatch → check consumer version đã deploy chưa.
> 3. **Re-drive tooling**: viết 1-shot job `DltRedriver` consume `*.DLT` → republish về topic gốc. Day 12 đã thiết kế DLT preserve partition affinity → re-publish cùng partition → giữ ordering.
> 4. **Throttle**: nếu redrive 10k message một lúc → consumer overload. Rate limit 100 msg/s (Resilience4j RateLimiter).
> 5. **Mark redriven**: header `x-redrive-attempt=1` để observability biết. Nếu re-DLT lần 2 → STOP — bug chưa fix thật.
>
> **Anti-pattern**: re-drive blind không triage. DLT đầy 100k → re-drive 100k → cùng poison → DLT lại 100k. Loop.

**Follow-up trap**: *"Re-drive trong giờ peak hay đêm?"*

> Đêm. Lý do: (a) consumer đang phục vụ traffic peak, thêm DLT load → tail latency tăng; (b) nếu redrive trigger downstream side effect (email, payment retry) → user confused giờ peak. Trừ khi DLT chứa SLO-critical event (vd: order ack chưa gửi user) → ưu tiên redrive ngay với rate limit chặt.

**Self-verdict**: ✅ **strong**. Có 5-step + anti-pattern + timing.

---

## Q7 — Outbox poll fixedDelay=1s. User place order rồi F5 ngay → thấy `PENDING`. UX gì?

**Strong answer**:

> Eventual consistency window 1-3s (poll tick 1s + Kafka publish ~ms + downstream consumer ~100ms). User refresh trong window thấy "PENDING" → 3 cách handle UX:
>
> 1. **Optimistic UI** (TanStack Query Week 5): client cache hiển thị "Đã đặt hàng — đang xác nhận" trong 5s sau submit response. Sau 5s mới query server thật. User cảm giác instant.
> 2. **WebSocket / SSE**: server push trạng thái khi consumer update → client realtime. Phức tạp infra. Phù hợp khi window > 5s.
> 3. **Polling client-side**: client poll order detail mỗi 1s trong 10s đầu. Đơn giản nhất, throughput phụ phải chấp nhận. Phù hợp với MVP.
>
> **Senior framing**: eventual consistency window KHÔNG phải bug — là feature trade-off ta đã chọn (Day 13 outbox over Debezium CDC). Job của Tech Lead: design UX bù lại latency, đừng cố giảm latency xuống 0 vì cost vô hạn.

**Follow-up trap**: *"Nếu campaign 6/6 spike → outbox relay lag 30s, user thấy PENDING 30s, complaint nhiều?"*

> 2 lớp mitigation:
> - **Detect**: SLI `outbox_lag_seconds` (max age của PENDING row). Alert nếu > 10s. Dashboard trên Grafana.
> - **Degrade gracefully**: nếu lag > 10s sustained → frontend hiển thị banner "Đơn hàng đang xử lý chậm hơn thường — vui lòng đợi 1 phút". Honest UX.
> - **Quick fix**: F4 finding — parallel publish trong relay (Week 3 sau campaign).

**Self-verdict**: ✅ **strong**. UX + detect + degrade.

---

## Q8 — Outbox vs Debezium CDC. Bạn chọn outbox. Khi nào migrate Debezium?

**Strong answer**:

> ADR-009 chọn outbox vì:
> - **App-controlled**: dev team owns logic, không phụ thuộc DBA enable `wal_level=logical` (ShopVN DBA chưa OK).
> - **Schema control**: outbox row có `event_type`, `payload`, `partition_key` — explicit. Debezium output raw WAL → cần SMT (Single Message Transform) để format → schema dễ drift.
> - **Test dễ**: integration test mock Kafka, không cần spin up Debezium connect cluster.
>
> **Migrate Debezium khi**:
> - Volume > 10k events/s → outbox relay throughput không scale (sequential publish F4).
> - Latency budget < 500ms → CDC đọc WAL streaming sub-second.
> - Team có DevOps owner Debezium connect cluster (1 service mới phải vận hành, monitor lag, restart connector).
>
> **Migration path**: outbox table giữ nguyên, Debezium "Outbox Event Router" SMT đọc table → publish Kafka cùng schema. Relay app chỉ disable. Không phải vứt code → chỉ thay tier publish.

**Follow-up trap**: *"Postgres logical replication slot tích lũy WAL nếu Debezium chậm — DB ngập disk. Bạn handle sao?"*

> Slot WAL retention monitoring là khoảng AI/junior dễ miss khi recommend CDC. 3 cách:
> - **Alert**: `pg_replication_slots.confirmed_flush_lsn` lag vs `pg_current_wal_lsn` > threshold → page DBA.
> - **Auto-drop slot**: nếu Debezium connector down > N hours → drop slot tạm, restart connector → backfill từ snapshot. Mất data event giai đoạn đó → app phải có reconciliation (so sánh order DB vs Kafka topic offset).
> - **WAL archive**: bật `archive_mode=on` để WAL ship sang S3 trước khi recycle → recover từ archive nếu slot drop.
>
> Đây là vận hành phức tạp **outbox không có** — đó là một lý do nữa Day 13 chọn outbox cho team nhỏ.

**Self-verdict**: ✅ **strong**. Defend ADR + slot retention trap.

---

## Q9 — Distributed trace từ HTTP API → Kafka → consumer. Header gì propagate? Đảm bảo nó actually work?

**Strong answer**:

> W3C `traceparent` header (Micrometer Tracing + OpenTelemetry, Day 9):
>
> ```
> traceparent: 00-<traceId>-<parentSpanId>-01
> ```
>
> Propagation chain:
> 1. **HTTP request**: ServletFilter (Micrometer auto-config) extract `traceparent` → set MDC + Tracer current.
> 2. **Kafka produce**: `KafkaTemplate` với `observation-enabled=true` → injection `traceparent` vào Kafka record header.
> 3. **Kafka consume**: `@KafkaListener` với observation enabled → extract header → set Tracer context → log có `traceId` + child span.
>
> **Verify thật** (Day 14 review): mở Zipkin local (port 9411), tạo 1 order via curl, search by traceId → expect 1 trace có 3 span: `http.server.requests` → `spring.kafka.template` → `spring.kafka.listener`.
>
> **Pitfall** (F6 finding): listener factory phải bật explicit `setObservationEnabled(true)` ở Spring Kafka 3.4 — default false. Nếu thấy trace cắt giữa publish và consume → đó là root cause.

**Follow-up trap**: *"Outbox relay publish — trace bị mất ở đó không? Vì context HTTP đã đóng?"*

> **Có**. Outbox relay là `@Scheduled` job → KHÔNG có HTTP trace context inherit. 3 options:
> - **Bỏ qua**: chấp nhận trace cắt, mỗi outbox publish = trace mới. Đơn giản, trace thiếu E2E view.
> - **Store traceId vào outbox row**: business code lưu `traceparent` lúc INSERT outbox. Relay đọc → re-inject vào Kafka header trước send. Trace continuous. Đây là **pattern chuẩn** (Spring Cloud Sleuth doc đề cập).
> - **Custom span**: relay tạo span "outbox.publish" với parent là span lưu trong DB. Phức tạp hơn.
>
> Project chưa làm — gap cho Week 3. Honestly project chỉ verify HTTP→Kafka direct (Day 9), chưa verify outbox path.

**Self-verdict**: 🟡 **borderline**. Mechanism đúng + trap đúng — NHƯNG outbox path chưa verify thật. Đây là gap to fix Week 3.

---

## Q10 — Campaign 6/6 traffic 10x. Bottleneck đầu tiên ở stack Week 2 ở đâu?

**Strong answer**:

> Phỏng đoán → verify bằng load test (Day 20):
>
> **Candidates**:
> 1. **OutboxRelay sequential publish** (F4): hiện 1 thread, batch 100, .get(5s) per event. Worst case Kafka ack lag 500ms → 50s/tick → backlog. **Likely first bottleneck**.
> 2. **Inventory consumer single-thread per partition**: `ConcurrentKafkaListenerContainerFactory` default `concurrency=1`. Partition `order.created` mặc định 1 → 1 consumer thread cho cả service. 10x traffic → consumer lag.
> 3. **Postgres write contention** ở outbox INSERT: cùng table với business write trong 1 tx → tx lock dài hơn. Optimistic lock ở Stock có thể retry-storm.
> 4. **JWT verify** trên mỗi request: HS256 nhanh, nhưng 10x = thêm CPU. Likely không bottleneck.
>
> **Fix priority**:
> - Topic `order.created` lên 6-12 partition (idempotent reshuffle vì idempotence không ép cố định partition count).
> - Consumer `concurrency=N` (= số partition / số instance).
> - OutboxRelay parallel publish (F4).
> - Optional: Debezium nếu sau optimize vẫn không đủ.
>
> **Senior framing**: KHÔNG fix mù. Day 20 load test (k6 + Grafana + Zipkin trace timeline) chỉ exact bottleneck. Mỗi optimization có cost (partition tăng → rebalance lâu hơn, consumer concurrency tăng → ordering trong key giữ nhưng coordinate phức tạp). Đo trước, fix sau.

**Follow-up trap**: *"Tại sao không scale-out service trước? Thêm 5 instance order-service?"*

> Scale-out only helps khi bottleneck **stateless + horizontal**. Kafka consumer scale-out bị giới hạn bởi **partition count** — 6 partition × 5 instance = chỉ 6 active, 4 idle (1 partition = 1 consumer trong group). Outbox relay scale-out OK nhờ SKIP LOCKED nhưng vẫn limit ở DB write throughput. Postgres scale-up (CPU/IOPS) hoặc shard mới thật scale.
>
> Bottleneck thật ở stack này là **stateful tier** (Kafka topic config + Postgres) — scale-out service chỉ giải 1 layer.

**Self-verdict**: ✅ **strong**. Có priority + alternative + defend với math.

---

# 📊 Self-grade

| Q | Topic | Verdict | Note |
|---|-------|---------|------|
| 1 | Delivery semantics | ✅ strong | exactly-once-effects vs exactly-once distinction rõ |
| 2 | acks=all + ISR | ✅ strong | min.insync.replicas trap defend được |
| 3 | Partition key | ✅ strong | Hot partition + alternative |
| 4 | Rebalance storm | ✅ strong | Cooperative migration + max.poll.interval |
| 5 | Schema evolution | ✅ strong | Additive vs breaking + registry threshold |
| 6 | DLT re-drive | ✅ strong | 5-step + anti-pattern + timing |
| 7 | Outbox lag UX | ✅ strong | 3 mitigation + degrade gracefully |
| 8 | Outbox vs CDC | ✅ strong | Slot retention trap defend được |
| 9 | Tracing E2E | 🟡 **borderline** | Outbox trace path **chưa verify thật** — kể as-if. Gap Week 3 fix. |
| 10 | Scale 10x bottleneck | ✅ strong | Priority + math defend được |

**Score**: 9 strong / 1 borderline / 0 fail. **Pass Senior** với note verify trace E2E ở Week 3 Day 20 load test.

## 🎯 Gap to fix Week 3

1. **Verify trace E2E outbox path** (Q9): Day 20 load test → curl order → grep Zipkin → expect span continuous outbox.publish. Nếu không → store traceparent trong outbox row.
2. **F1+F2+F3 inventory consumer debt** ([review/kafka-week2-findings.md](../review/kafka-week2-findings.md)): tuần sau cùng cache work fix.
3. **F4 OutboxRelay parallel publish** post-campaign benchmark.
4. **Cooperative sticky assignor** Q4: add explicit config trước campaign.

---

## 🤖 AI Playbook

- **AI làm tốt**: generate Q outline + strong-answer skeleton + follow-up trap. Đối với Kafka có data sheet rộng, AI compile được math (rebalance time, lag calc) khá đúng.
- **Prompt mẫu**:
  ```
  Đọc 6 file docs/interview/day-0{8..13}-*.md. Generate 10 Kafka senior
  interview question — 5 fundamentals + 5 production scenario. Mỗi câu
  kèm 1 follow-up trap có thể catch borderline. KHÔNG hỏi câu đã có.
  ```
- **Risk**: AI generate câu fundamentals dễ na ná textbook (Q1 "what is at-least-once" — boring). Senior interviewer hỏi câu **gắn case cụ thể** ("config bạn vừa describe đang ở semantics nào" — Q1 phiên bản tốt). Phải edit lại Q của AI cho realistic.
- **Validate**: (a) tự nói 60s/câu thành tiếng — borderline = tự chấm; (b) câu có số (rebalance 3-5s, slot WAL lag) → check số có defend được không; (c) follow-up trap → AI hay tạo trap dễ; verify trap thật sự catch được junior.

---

## 🔗 Related

- Day docs evidence: [day-08](day-08-kafka.md) · [day-09](day-09-order-flow.md) · [day-10](day-10-payment.md) · [day-11](day-11-notification.md) · [day-12](day-12-resilience.md) · [day-13](day-13-outbox.md)
- Review findings (cùng day): [review/kafka-week2-findings.md](../review/kafka-week2-findings.md)
- ADR refs: [005 feign-vs-http-interface](../decisions/005-feign-vs-http-interface.md) · [006 sync-vs-async](../decisions/006-sync-orchestration-vs-async-events.md) · [007 payment-layered](../decisions/007-payment-service-layered-not-ddd.md) · [008 api-versioning](../decisions/008-api-versioning-strategy.md) · [009 outbox-vs-cdc](../decisions/009-outbox-vs-cdc.md)
- CV bullets Week 2: [week-02-cv-bullets.md](week-02-cv-bullets.md)
- Cumulative trap checklist: [../review/ai-junior-traps.md](../review/ai-junior-traps.md)
