# Lesson 13b — 🔀 Dual-write Problem

> **Day 13** · concept foundation cho [13 outbox](13-outbox-pattern.md).

---

## 🎯 TL;DR

Khi 1 transaction phải ghi vào **2 hệ thống độc lập** (vd Postgres + Kafka),
KHÔNG có nguyên tử mặc định. 1 trong 2 commit, cái còn lại fail → silent
inconsistency. Đó là **dual-write problem**.

```
                ┌─────────────┐    ┌──────────┐
   App  ───►    │  Postgres   │    │  Kafka   │
                └─────────────┘    └──────────┘
                  COMMIT OK         publish FAIL  → DB có, Kafka không
                  COMMIT FAIL       publish OK    → Kafka có, DB không
```

---

## ✅ Khi nào dùng (= khi phải care)

- Mọi pattern **save DB rồi publish event**: order placed, payment captured, inventory reserved, user registered → notification email.
- Mọi flow Saga choreography (event-driven).
- Cross-system audit trail (DB + log warehouse).

## ❌ Khi nào KHÔNG care

- Event không quan trọng (page view analytics → có thể mất).
- Single system, không cross-broker.

---

## ⚠️ Tại sao 2PC không phải answer

| Vấn đề              | Mô tả                                                                  |
| ------------------- | ---------------------------------------------------------------------- |
| Resource manager    | Kafka không XA-compliant. JMS có nhưng ops cost cao.                  |
| Coordinator failure | Coordinator crash trong PREPARE phase → resource lock prepared kẹt. DBA phải manual untangle. |
| Latency             | 2 phase ép 2 round trip → tx latency 2x.                              |
| Scalability         | Coordinator là single point. Distributed coordinator (như Paxos) còn phức tạp hơn. |

→ Industry **ditched 2PC ~2015**. Microsoft, Netflix, LinkedIn đều dùng outbox/CDC.

---

## ⚖️ Solution family

| Pattern              | Cost  | Latency  | Khi nào                                |
| -------------------- | ----- | -------- | -------------------------------------- |
| Transactional outbox | Low   | 1-2s     | Default, < 10k events/s                |
| CDC (Debezium)       | High  | sub-s    | > 10k/s, latency budget < 1s          |
| Listen/Notify        | Low   | < 100ms  | Postgres-only, push notification      |
| Reconciler batch     | Lowest| hours    | Eventual catch-up, low-priority events |
| Saga orchestration   | High  | n/a      | Cross-service multi-step workflow     |

Detail: [13 outbox pattern](13-outbox-pattern.md) + [ADR-009](../decisions/009-outbox-vs-cdc.md).

---

## 🎤 Trả lời phỏng vấn

> **"Em hiểu dual-write problem là gì?"**
>
> Đó là khi 1 logical operation cần ghi vào ≥ 2 hệ thống độc lập (vd Postgres
> + Kafka). Không có atomic guarantee — 1 thành công, 1 fail → state divergent.
> Ví dụ: ở project em làm, DB commit Order OK nhưng Kafka publish fail vì
> broker restart → inventory không reserve, customer paid nhưng không có hàng.
>
> Solution family: outbox pattern (em dùng), Debezium CDC, saga. KHÔNG dùng
> 2PC vì Kafka không XA + coordinator failure mode tệ.

---

## 🔗 Related

- [13 — Outbox pattern](13-outbox-pattern.md)
- [Issue 13 — Order paid inventory not reserved](../issues/13-order-paid-inventory-not-reserved.md)
- [Day 9 — Eventual consistency](09b-eventual-consistency-window.md)
