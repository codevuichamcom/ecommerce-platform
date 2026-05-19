# Lesson 11 — Fire-and-Forget Pattern

> **Related day**: Day 11 (Notification service)
> **Status**: ✅ Done

---

## 🎯 TL;DR

Fire-and-forget là pattern xử lý side-effect async: handler nhận event, thực hiện tác vụ (gửi email/SMS/push), **không throw exception** dù fail — caller (Kafka consumer framework) commit offset và tiếp tục. Trade-off rõ ràng: **hy sinh delivery guarantee đổi lấy simplicity + no retry storm**.

Bắt buộc đi kèm idempotency: dù fire-and-forget, Kafka vẫn retry khi consumer crash **trước khi** commit offset → handler phải idempotent.

---

## ✅ Khi nào dùng

- **Marketing notification** (order confirmed, payment received, flash sale alert) — miss 1 email ít tác hại hơn spam 3 email.
- **Audit log** — write-and-forget to file/database. Failure = log warning, không block request.
- **Analytics event tracking** — trang product view → push event. Miss OK; block user UX = NOT OK.
- **Nội bộ ops alert** (Slack webhook, PagerDuty) — important nhưng không critical đến mức rollback transaction.

---

## ❌ Khi nào KHÔNG dùng

- **OTP / 2FA email** — user không nhận = không login được. Cần retry + DLT + alert.
- **Payment confirmation** khi đây là legal evidence — cần persistent delivery log + reconciliation.
- **Security alert** (suspicious login) — miss = security incident. Cần fail-closed.
- Bất kỳ khi nào SLA cho "email delivered" cao hơn "best-effort".

---

## ⚠️ Cạm bẫy

**Cạm bẫy 1: Fire-and-forget mà không có idempotency**
Kafka retry (do consumer crash, rebalance, network glitch) sẽ gọi handler nhiều lần. Nếu không dedup → spam. **Bắt buộc**: Redis SET NX hoặc DB idempotency key trước khi dispatch.

```java
// WRONG — sẽ gửi duplicate khi Kafka retry
@KafkaListener(topics = "order.created")
public void handle(OrderCreatedV1 event) {
    emailService.send(renderTemplate(event)); // no dedup
}

// CORRECT — dedup trước khi dispatch
@KafkaListener(topics = "order.created")
public void handle(OrderCreatedV1 event) {
    if (!deduplicator.tryAcquire(event.eventId())) return;
    try {
        emailService.send(renderTemplate(event));
    } catch (Exception ex) {
        log.error("dispatch failed", ex); // fire-and-forget: không re-throw
    }
}
```

**Cạm bẫy 2: Throw exception trong fire-and-forget handler**
Nếu throw → Kafka retry → gửi nhiều lần (dù có dedup thì Redis TTL phải đủ dài). Với Resilience4j retry (Day 12) → exponential backoff + DLT. Nhưng với fire-and-forget thuần: catch tất cả + log.

**Cạm bẫy 3: Nhầm lẫn fire-and-forget handler với exactly-once delivery**
Fire-and-forget là **at-most-once** sau khi offset commit. Không có guarantee delivery. Đừng dùng cho use case cần exactly-once.

**Cạm bẫy 4: Fail-open Redis dedup khi Redis down**
Nếu Redis unavailable, dedup bypass → có thể gửi duplicate. Với marketing email: fail-open (gửi duplicate). Với OTP: fail-closed (không gửi, throw error để retry).

---

## 🆚 Approaches compared

| Approach | Delivery Guarantee | Complexity | Use Case |
|---|---|---|---|
| **Fire-and-forget** (Day 11) | At-most-once (sau commit) | Thấp | Marketing notification |
| Retry + DLT (Day 12) | At-least-once + poison isolation | Trung bình | Business event critical |
| Outbox pattern (Day 13) | At-least-once đến Kafka | Cao | Transactional event publish |
| Exactly-once (Kafka transaction) | Exactly-once (complex) | Rất cao | Financial ledger update |

---

## 🎤 Trả lời phỏng vấn

**Q: "Fire-and-forget nghĩa là gì trong context Kafka consumer?"**

> Fire-and-forget: consumer nhận event, thực hiện side-effect, không throw exception → Kafka commit offset và tiếp tục. Delivery guarantee là at-most-once sau khi offset committed. Nếu handler fail → log error, không retry. Trade-off: hy sinh delivery guarantee đổi lấy no retry storm + simplicity.
>
> Bắt buộc đi kèm: **idempotent consumer** (Redis SET NX by eventId) vì Kafka vẫn retry khi consumer crash *trước* commit.

**Q: "Khi nào em chọn fire-and-forget vs retry + DLT?"**

> Marketing notification → fire-and-forget (miss OK, spam bad).
> Business critical event (inventory reserve, payment record) → retry + DLT (miss bad, duplicate OK nếu idempotent).
> Financial reconciliation → transactional Kafka (Day 13 outbox) hoặc saga pattern.

---

## 🔗 Related

- Code: [OrderCreatedConsumer.java](../../services/notification-service/src/main/java/com/ecommerce/notification/consumer/OrderCreatedConsumer.java)
- Code: [NotificationDeduplicator.java](../../services/notification-service/src/main/java/com/ecommerce/notification/idempotency/NotificationDeduplicator.java)
- Issue: [issues/11-notification-email-spam.md](../issues/11-notification-email-spam.md)
- Lesson Day 12: `lessons/12-retry-strategy.md` — khi nào upgrade từ fire-and-forget lên retry + DLT
- Lesson Day 10: [lessons/10-idempotency.md](10-idempotency.md) — idempotency model tổng quát
