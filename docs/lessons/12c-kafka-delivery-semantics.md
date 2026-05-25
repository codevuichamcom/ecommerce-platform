# Lesson 12c — Kafka Delivery Semantics

> **Day**: 12 · **Topic**: At-most/at-least/exactly-once + producer idempotence + consumer manual ack.
> **Status**: ✅ Done

---

## 🎯 TL;DR

> 3 mức delivery — at-most-once (mất OK), at-least-once (default đúng cho business event, **cần dedup phía consumer**), exactly-once (cost cao, chỉ cho money flow). Default Kafka **lừa người mới**: `enable.auto.commit=true` cho cảm giác at-least-once nhưng thực ra có thể mất message. Production: `acks=all` + `enable.idempotence=true` (producer) + manual ack + idempotent consumer (Day 10 dedup).

---

## 📚 3 mức delivery

| Semantic         | Producer config                                      | Consumer config                            | Khi nào dùng                          |
| ---------------- | ---------------------------------------------------- | ------------------------------------------ | ------------------------------------- |
| **At-most-once** | `acks=0`, no retry                                   | auto-commit BEFORE process                 | Log/metric — chấp nhận mất            |
| **At-least-once**| `acks=all` + `enable.idempotence=true` + retries     | manual ack AFTER process + **idempotent handler** | ✅ Default cho business event   |
| **Exactly-once** | `transactional.id` + producer transaction            | `isolation.level=read_committed` + transactional consume-process-produce | Money flow critical (Day 36 payment recon) |

---

## 🔧 Default trap: `enable.auto.commit=true`

- Auto-commit chạy mỗi 5s background. Process xong 100 message, commit chưa xảy ra, crash → consumer khác replay 100 message. **At-least-once + duplicate**.
- Nguy hiểm hơn: commit XẢY RA TRƯỚC khi process xong (offset đã advance, process throw) → message **LOST**. **At-most-once trá hình**.
- Spring Kafka từ 2.x default `enable.auto.commit=false` + `AckMode.BATCH` (commit sau khi batch process xong) — safer. Common-lib Day 8 đã set explicit.

```java
cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
```

---

## 🆚 Approaches compared — "không mất message"

| Approach                                | Pros                                       | Cons                                                  |
| --------------------------------------- | ------------------------------------------ | ----------------------------------------------------- |
| Auto-commit + accept loss               | Đơn giản                                   | Mất message khi crash giữa process                    |
| **Manual ack + at-least-once + dedup**  | ✅ Phổ biến nhất, simple to reason          | Phải có idempotency key ở consumer (Day 10 pattern)   |
| Kafka transaction (exactly-once)        | KHÔNG cần dedup logic phía consumer        | Cost cao (~30% throughput), setup phức tạp, chỉ giữ EOS **trong Kafka topology** — bridge ra ngoài (DB write, HTTP call) thì lại cần dedup |

> Day 12 chọn **manual ack + idempotent consumer** cho mọi topic. Exactly-once chỉ áp dụng cho payment-callback Day 10 (DB UNIQUE constraint là idempotency layer).

---

## ⚠️ Cạm bẫy

1. **`acks=all` không đủ** — phải bật `enable.idempotence=true` ở producer. Không thì producer retry trong session vẫn duplicate broker-side (Day 8 issue 08).
2. **Idempotent producer ≠ idempotent consumer** — producer dedup CHỈ trong cùng producer session (PID + sequence). Restart producer → PID mới → broker không nhận ra. Consumer side phải dedup riêng (Redis SET NX hoặc DB UNIQUE).
3. **`min.insync.replicas < replication.factor`** — không thì khi 1 broker down + producer `acks=all` → block. Recommend `min.insync.replicas=replication.factor - 1` (vd RF=3, ISR=2).
4. **Exactly-once "guarantee" ảo** — EOS chỉ work trong Kafka-to-Kafka. Consume Kafka → write DB không có atomic guarantee. Day 13 outbox + transactional outbox pattern fix.
5. **Consumer crash giữa process** — at-least-once = sẽ replay. Nếu side-effect không idempotent (gửi email, charge card) → duplicate side-effect. Dedup BẮT BUỘC.

---

## 🎤 Trả lời phỏng vấn

**Q1: "Kafka có exactly-once không? Cost ra sao?"**

Có — Kafka 0.11+ hỗ trợ EOS qua transactional producer + `isolation.level=read_committed` ở consumer. NHƯNG có 2 caveats: (1) chỉ EOS trong **Kafka topology** (consume topic A → produce topic B atomic); bridge ra DB/HTTP cần dedup riêng. (2) Cost ~30% throughput vì transaction marker + log fence. Production default vẫn là at-least-once + idempotent consumer — simpler, faster, đủ correctness.

**Q2: "`acks=all` + retry có gây duplicate không?"**

Có nếu KHÔNG bật `enable.idempotence=true`. Scenario: producer gửi, broker ghi xong, ack lost trên network → producer retry → broker ghi lần 2 → duplicate. Bật `enable.idempotence=true` thì producer gắn PID + sequence number, broker dedup. Bật flag này ép `acks=all` + `retries=Integer.MAX_VALUE` + `max.in.flight.requests.per.connection ≤ 5` (Kafka ≥ 3.0).

**Q3: "Consumer crash giữa process — message có mất không?"**

Phụ thuộc commit strategy. `enable.auto.commit=true` → có thể mất (commit chạy mỗi 5s, có thể commit trước khi process xong). `enable.auto.commit=false` + manual ack sau process xong → at-least-once = không mất nhưng có thể duplicate khi crash trước ack → consumer replay. Day 12 project: manual ack + idempotent consumer (Redis SET NX TTL 24h) — duplicate được dedup transparent.

### Follow-up traps

- *"Spring Kafka `AckMode.BATCH` vs `MANUAL_IMMEDIATE` — khác gì?"* — BATCH commit sau khi `poll()` batch process xong (default). MANUAL_IMMEDIATE cần code gọi `ack.acknowledge()` — control chính xác. Trap: candidate nhầm BATCH là auto-commit.
- *"`isolation.level=read_committed` — consumer chậm hơn không?"* — Yes, ~10-15% slower vì phải skip uncommitted/aborted records. Worth it cho consumer downstream của transactional producer.

---

## 🔗 Related

- [`lessons/12d-partition-key-ordering.md`](12d-partition-key-ordering.md) — ordering complement delivery
- [`lessons/10-idempotency.md`](10-idempotency.md) — Day 10 dedup pattern (cùng nguyên tắc cho consumer)
- [`issues/10-duplicate-payment-callback.md`](../issues/10-duplicate-payment-callback.md) — UNIQUE constraint as last-line dedup
- [`issues/08-kafka-message-loss-acks-default.md`](../issues/08-kafka-message-loss-acks-default.md) — Day 8 root cause acks default
