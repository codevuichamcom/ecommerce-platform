---
day: 13
topic: Transactional Outbox Pattern
status: ✅ done
---

# 🎤 Interview Day 13 — Transactional Outbox

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — mid-stage ecommerce VN, 50k orders/day, mở rộng TMĐT B2B Q3
- **Role giao việc**: Anh Hùng — Engineering Manager (ex-Tiki, 8 năm) — Slack sáng 09:30: *"Hôm qua Kafka broker primary restart 90s vì OOM, 23 order user paid nhưng inventory không reserve, đang bị CSKH ticket. Fix root cause hôm nay."*
- **Bạn**: Backend Tech Lead — own order-service + cross-service messaging contract
- **Reviewer**: Anh Hùng + 1 Senior DBA (Hằng) — DBA soi index outbox, lock contention với write traffic, vacuum strategy
- **Deadline**: 1 sprint day (8h coding + docs). Demo: kill Kafka 60s khi place 10 order → bring up → 10 inventory reservations thành công.
- **Constraint thực tế**: (1) KHÔNG được dùng Debezium/CDC — DBA chưa enable `wal_level=logical` trên prod; (2) Outbox table phải share Postgres của order-service (DB-per-service rule), KHÔNG dùng DB riêng; (3) Relay polling không tạo lock contention với write traffic peak (100 RPS place-order).
- **Definition of Done**: (1) `PlaceOrderUseCase` không call `kafkaTemplate.send()` trực tiếp; (2) Unit test outbox PASS; (3) ADR cân nhắc ≥ 4 alternatives; (4) Issue doc 9-section "order paid nhưng inventory không reserve"; (5) Pattern document để team áp dụng cho payment-service tuần sau.

---

## Q1 — "Em hiểu dual-write problem là gì? Tại sao không dùng 2PC (XA) giữa Postgres và Kafka?"

**Strong answer**:

Dual-write là khi 1 logical operation cần ghi vào ≥ 2 hệ thống độc lập (vd
Postgres + Kafka), không có atomic guarantee. Ở project em làm, Day 9 có
code:

```java
orderRepository.save(order);    // DB commit
kafkaTemplate.send(event);      // Kafka publish — không nằm trong DB tx
```

DB commit OK + Kafka publish fail → state divergent. Đó chính là incident
hôm 24/05: broker OOM restart 90s, 23 order DB có nhưng Kafka không có event.

**Tại sao không 2PC**: (1) Kafka không phải XA-compliant resource manager;
(2) 2PC coordinator failure khiến tx kẹt prepared state, DBA phải manual
untangle — ops nightmare; (3) Latency 2x do 2 round trip; (4) Industry đã
move away từ ~2015 — Microsoft, Netflix, LinkedIn đều dùng outbox/CDC.

**Follow-up trap**:
- *"Vậy `@Transactional` bao Kafka send được không?"* → KHÔNG. Kafka producer KHÔNG tham gia DB tx. `@Transactional` chỉ commit DB; Kafka send là I/O ngoài tx, có thể OK trong khi DB rollback hoặc ngược lại.
- *"Spring có `KafkaTransactionManager` mà?"* → Đúng, nhưng đó là tx của Kafka producer (atomic giữa nhiều send), KHÔNG đồng nhất với DB tx trừ khi chain qua `ChainedTransactionManager` (best-effort 2PC, vẫn có race window cuối phase). Outbox cleaner.

---

## Q2 — "Outbox vs CDC (Debezium) — khi nào chọn cái nào?"

**Strong answer**:

| Tiêu chí          | Outbox (poll)     | Debezium CDC          |
| ----------------- | ----------------- | --------------------- |
| Latency           | 1-2s (polling)    | sub-second            |
| Ops cost          | Low (1 cron bean) | High (Kafka Connect cluster + connector health + WAL slot monitor) |
| DBA config        | None              | `wal_level=logical` + replication slot |
| Volume sweet spot | < 10k events/s    | > 10k events/s        |
| Debug             | SELECT outbox WHERE status=FAILED | Phải đọc connector log + Kafka topic |
| Migration cost    | App code thay     | Infra change đáng kể  |

Rule of thumb em dùng: **default outbox**, switch CDC khi (a) latency budget
< 500ms hoặc (b) volume > 10k/s hoặc (c) team có DBA dedicated quản WAL.
Ở ShopVN 50k orders/day = 0.5 event/s → outbox đủ headroom 1000x.

**Follow-up trap**:
- *"Outbox + Debezium combine được không?"* → ĐƯỢC. Debezium "Outbox Event Router" SMT đọc outbox table qua CDC → publish Kafka. Đó là pattern migration path — em đã note trong ADR-009.
- *"WAL bloat khi connector down?"* → Risk. Slot không recycle → disk full. Phải monitor `pg_replication_slots.confirmed_flush_lsn` lag.

---

## Q3 — "Outbox relay multi-instance chạy song song — làm sao chống publish duplicate?"

**Strong answer**:

3 cách common:

1. **Postgres `FOR UPDATE SKIP LOCKED`** (chosen ở project em) — native row-lock, 2 relay tick cùng lúc → mỗi cái lock 1 batch disjoint. Code:
   ```java
   @Lock(PESSIMISTIC_WRITE)
   @QueryHints(@QueryHint(name="jakarta.persistence.lock.timeout", value="-2"))  // -2 = SKIP_LOCKED
   List<OutboxEvent> fetchBatch(Pageable p);
   ```
2. **ShedLock**: single-leader, chỉ 1 relay instance active. Đơn giản hơn nhưng giảm throughput (no parallelism).
3. **Partition outbox theo `aggregate_id % N`**: mỗi instance own 1 partition. Phức tạp + rebalancing khi scale.

Bất kể cách nào, consumer vẫn PHẢI idempotent (em đã có ở Day 12
NotificationDeduplicator + Day 10 4-layer payment idempotency) vì outbox là
**at-least-once**: relay crash sau Kafka ack nhưng trước UPDATE status=SENT
→ tick sau retry → consumer thấy duplicate.

**Follow-up trap**:
- *"Sao không dùng Redis distributed lock?"* → Over-engineering. Postgres SKIP LOCKED là native, cheaper, không thêm dependency. Redlock có debate Kleppmann vs antirez về correctness — không nên dùng cho mission-critical.

---

## Q4 — "Outbox guarantee ordering không? Per-aggregate hay global?"

**Strong answer**:

**Per-aggregate**, KHÔNG global. Để per-aggregate ordering:
- Relay query `ORDER BY created_at ASC` (FIFO).
- `partition_key = aggregate_id` (orderId) — cùng order vào cùng Kafka partition.
- Kafka guarantee ordering trong cùng partition.

Cross-aggregate ordering KHÔNG có. Vd: order A (partition 0) và order B
(partition 1) publish gần như cùng lúc — consumer 2 partition đọc parallel
→ order arrival không deterministic. Đó là design Kafka, không riêng outbox.

**Follow-up trap**:
- *"Nếu 1 order có 10 event consecutive, vẫn ordered?"* → CÓ, vì cùng partition_key=orderId → cùng partition → cùng consumer thread (Kafka). Relay FIFO + Kafka FIFO = chain ordered.
- *"Producer retry có break ordering không?"* → KHÔNG nếu idempotent producer (`enable.idempotence=true`, `max.in.flight ≤ 5`). Producer dùng PID + sequence number, broker reorder đúng sequence. Em đã config sẵn ở common-lib Day 8.

---

## Q5 — "Outbox table 100M rows sau 1 năm — xử lý sao?"

**Strong answer**:

3 layer defense:

1. **Cron cleanup**: `DELETE FROM outbox_event WHERE status='SENT' AND sent_at < now() - interval '7 days'` chạy hàng đêm. Volume 50k/day × 7 = 350k row queue, manageable.
2. **Partition by `created_at` (monthly)**: nếu volume tăng → declarative partition Postgres 11+, `DROP TABLE outbox_event_2025_03` cũ. O(1) thay vì DELETE từng row.
3. **NEVER TRUNCATE toàn bảng** — mất PENDING/FAILED chưa xử lý.

Index strategy:
- `outbox_pending_idx` partial WHERE status='PENDING' → index chỉ chứa PENDING rows, size nhỏ, query relay fast.
- KHÔNG full index trên `(status, created_at)` — cover SENT rows = bloat.

VACUUM:
- Autovacuum threshold default OK cho 0.5 events/s. Nếu scale 10x → tune `autovacuum_vacuum_scale_factor=0.05` cho table này (mặc định 0.2).

**Follow-up trap**:
- *"Tại sao không archive sang S3?"* → Có thể, cho compliance / audit. Cron monthly export SENT > 30d → S3 + DELETE local. Em chưa làm vì 7d retention đủ cho debug.
- *"DELETE 350k rows mỗi đêm có lock không?"* → Batch `DELETE ... WHERE id IN (SELECT id FROM outbox WHERE ... LIMIT 1000)` lặp tới empty. Tránh single huge DELETE.

---

## 🧠 Senior mindset notes

- **Pitfall AI/junior**: AI dễ generate "outbox.save() + kafkaTemplate.send()" trong cùng method synchronous — quên rằng relay PHẢI là background process. Hoặc relay không batch → N+1 publish call.
- **Scale 10x**: 500k orders/day = 5 events/s, polling 1s vẫn OK. 5M/day = 50 events/s — vẫn OK với 1 relay batch 100. 50M/day → batching không kịp, switch Debezium. Outbox là **migration path** sang CDC, không phải dead-end.
- **Trade-off non-obvious**: Outbox HY SINH **latency** (1-2s lag) để có **atomicity**. Nếu use case là realtime trading thì không phù hợp; cho ecommerce order thì lag không cảm nhận được.

---

## 🤖 AI Playbook (Day 13)

- **AI làm tốt**: scaffold V3 migration SQL, JPA entity + lifecycle methods, scheduled relay skeleton, generate test case stop/start Kafka container.
- **Prompt mẫu**: *"Generate Java 21 JPA entity OutboxEvent + Flyway V3 migration cho transactional outbox: id UUID, aggregate_type/id, event_type, topic, partition_key, payload JSONB, status enum (PENDING/SENT/FAILED), attempts int, last_error text, created_at/sent_at timestamptz; partial index PENDING; lifecycle methods markSent/recordFailure/shouldGiveUp."*
- **Risk**: (1) AI quên `FOR UPDATE SKIP LOCKED` → multi-instance race; (2) AI publish Kafka trong cùng tx với insert outbox (defeat purpose); (3) AI generate `JsonSerializer` cho payload làm double-quote → consumer fail.
- **Validate**: (1) Đọc relay method — confirm tx boundary REQUIRES_NEW + KHÔNG có kafkaSend trong recorder; (2) Integration test Kafka-down phải PASS; (3) Trace 1 event end-to-end qua Zipkin (Day 9 đã wire).

---

## 👥 Tech Lead Lens (Day 13)

- **Trade-off + scale 10x**: outbox = consistency over latency. Scale 10x (5M orders/day): vẫn OK với batch tuning. Scale 100x (50M/day): switch Debezium CDC (ADR-009 đã note migration path). Cảnh báo team: đừng build "outbox v2 smarter polling" — đó là dấu hiệu wrong tool. Migrate sang đúng tool (CDC).
- **Production failure mode + 5-step triage**:
  1. Grafana check `outbox.pending.age` — > 30s?
  2. Kafka broker health (`kafka_producer_send_errors_total` rate)?
  3. Relay process alive (`/actuator/health` + log "Outbox relay tick" mỗi 1s)?
  4. DB lock contention trên `outbox_event` (`pg_locks` table)?
  5. Nếu relay stuck SENT-but-no-ack (rare crash window): manual SQL `UPDATE outbox_event SET status='PENDING' WHERE id IN (...)` + restart relay. Consumer idempotent sẽ dedup.
- **Junior + AI 2 lỗi dễ**:
  1. **Quên SKIP LOCKED** → 2 relay instance double-publish. Vẫn OK vì consumer idempotent nhưng waste resource. PR review check SQL hint.
  2. **`OutboxRelay` chạy trong cùng `@Transactional` business** → connection pool exhaust khi peak. PR review check `@Scheduled` method tx boundary là `REQUIRES_NEW` không phải REQUIRED.
