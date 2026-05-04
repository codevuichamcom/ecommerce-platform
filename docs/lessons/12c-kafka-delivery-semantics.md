# Lesson 12c — Kafka Delivery Semantics

> **Status**: ⏳ Skeleton — fill khi build Day 12.
> **Related day**: Day 12 (Retry + DLT).

---

## 🎯 TL;DR

> 1-2 câu: 3 mức delivery (at-most-once / at-least-once / exactly-once). Default Kafka = at-least-once với `enable.auto.commit=true`. Production phải dùng **manual ack + idempotent consumer** = at-least-once + dedup.

---

## 📚 3 mức delivery

| Semantic         | Producer config                                  | Consumer config                          | Khi nào dùng                          |
| ---------------- | ------------------------------------------------ | ---------------------------------------- | ------------------------------------- |
| At-most-once     | `acks=0`, không retry                            | auto-commit BEFORE process               | Log/metric — chấp nhận mất            |
| At-least-once    | `acks=all`, `retries>0`, `enable.idempotence=true` | manual ack AFTER process                | **Default cho business event**        |
| Exactly-once     | `transactional.id` + `isolation.level=read_committed` | + transactional consume-process-produce  | Money flow critical                   |

---

## 🔧 Default trap: `enable.auto.commit=true`

- (TODO) Auto-commit chạy mỗi 5s → process xong message nhưng chưa commit offset → crash → consumer khác replay.
- (TODO) Hoặc tệ hơn: commit TRƯỚC khi process → process throw → message LOST.
- (TODO) Production: `enable.auto.commit=false`, ack thủ công sau khi process xong.

## 🆚 Approaches compared (cho "không mất message")

| Approach                          | Pros                                  | Cons                                       |
| --------------------------------- | ------------------------------------- | ------------------------------------------ |
| Auto-commit + accept loss         | Đơn giản                              | Mất message khi crash                      |
| Manual ack + at-least-once + dedup| Phổ biến nhất, dễ implement           | Phải có idempotency key ở consumer         |
| Kafka transaction (exactly-once)  | Không cần dedup logic                 | Cost cao, throughput giảm, setup phức tạp  |

> Day 12 chọn **manual ack + idempotent consumer**. Exactly-once chỉ cho payment-callback (Day 10).

## ⚠️ Cạm bẫy

- (TODO) `acks=all` không đủ — phải bật `enable.idempotence=true` ở producer để chống duplicate khi retry.
- (TODO) Idempotent producer ≠ idempotent consumer. Consumer side vẫn phải dedup (Redis SETNX hoặc DB unique constraint).
- (TODO) `min.insync.replicas` < `replication.factor` — không thì block producer khi 1 broker down.

## 🎤 Trả lời phỏng vấn

> (TODO) "Kafka có exactly-once không? Cost ra sao?"
> (TODO) "`acks=all` + retry có gây duplicate không?"
> (TODO) "Consumer crash giữa chừng — message có mất không?"

## 🔗 Related

- [`lessons/12d-partition-key-ordering.md`](12d-partition-key-ordering.md)
- [`lessons/10-idempotency.md`](10-idempotency.md)
- [`issues/10-duplicate-payment-callback.md`](../issues/10-duplicate-payment-callback.md)
