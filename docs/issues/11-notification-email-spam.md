# 🔥 Issue 11 — Notification email spam khi Kafka consumer retry

---

## 1. Problem

Kafka consumer retry gửi email "order confirmed" 3 lần cho cùng 1 đơn hàng. User phàn nàn "nhận 3 email giống nhau trong 5 giây". Xảy ra mỗi khi có Kafka rebalance hoặc consumer restart.

---

## 2. Symptoms

- **User-facing**: user nhận 2–5 email giống nhau trong vòng 30 giây.
- **Log**: `[order-created] dispatched orderId=<id>` xuất hiện nhiều lần với cùng `orderId` nhưng `eventId` khác nhau (Kafka assign offset mới khi retry).
- **Metric**: `email_sent_total{template="order-created"}` tăng gấp 3x so với `orders_created_total`.
- **Kafka dashboard**: consumer group `notification-service` lag tăng đột ngột sau restart, sau đó giảm nhanh = re-consume batch message.

---

## 3. Root cause

Kafka **at-least-once delivery**: khi consumer restart hoặc rebalance xảy ra trước khi offset được commit, broker giao lại cùng batch message. Handler không có idempotency check → gọi `emailChannel.send()` cho mỗi lần nhận event → spam.

Vấn đề cụ thể: Day 8 scaffold handler chỉ log, không có dedup. Khi nâng lên "gửi email thật" mà quên thêm idempotency → production incident.

```
timeline:
  T0: consumer nhận event orderId=123, bắt đầu xử lý
  T1: email được gửi thành công
  T2: consumer crash TRƯỚC KHI commit offset (ví dụ OOM, deploy)
  T3: consumer restart, Kafka giao lại event orderId=123
  T4: email được gửi lại → duplicate
```

---

## 4. Approaches compared

| Approach | Pros | Cons |
|---|---|---|
| **Redis SET NX by eventId** ✅ | Fast (1 Redis RTT ~1ms), stateless service, TTL tự cleanup, fail-open design rõ ràng | Redis down = dedup bypass (fail-open); phải chọn TTL đủ dài (24h vs Kafka retention) |
| DB idempotency table `notification_sent(eventId PK)` | Durable, survives Redis restart, query được audit log | Thêm DB dependency cho stateless service; write overhead mỗi event; cần cleanup job cho old records |
| Redis SETNX by `orderId + templateName` | Đơn giản hơn (key shorter) | Sai: eventId mới hơn orderId — cùng orderId có thể có nhiều event loại khác nhau (order.created, order.updated). Dedup quá rộng. |
| Kafka Exactly-Once Semantics (transactions) | True exactly-once | Phức tạp cực cao (KIP-98); cần `isolation.level=read_committed`; throughput giảm 50%; không cover dispatch fail sau Kafka commit |

---

## 5. Chosen approach + Why

**Redis SET NX by eventId** — key `notif:dedup:{eventId}` với TTL 24h.

Lý do gắn với context:
- `notification-service` là **stateless**: không có DB riêng (Day 11). Redis đã có sẵn trong stack.
- `eventId` là UUID unique per event (contract `DomainEvent` interface từ Day 8). Key granularity đúng.
- TTL 24h >> Kafka max retry window (Spring Kafka default max backoff ~10 phút) → đủ cover mọi retry scenario.
- Fail-open: Redis down → gửi duplicate (acceptable cho marketing email, không acceptable cho OTP — xem trade-off).

---

## 6. Fix

```java
// NotificationDeduplicator.java
public boolean tryAcquire(UUID eventId) {
    String key = "notif:dedup:" + eventId;
    try {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofHours(24));
        return Boolean.TRUE.equals(acquired);
    } catch (Exception ex) {
        log.warn("[dedup] Redis unavailable, fail-open eventId={}", eventId);
        return true;  // fail-open
    }
}

// OrderCreatedConsumer.java
if (!deduplicator.tryAcquire(event.eventId())) {
    log.info("[order-created] duplicate skip eventId={}", event.eventId());
    return;  // không gửi email
}
```

Code đầy đủ: [NotificationDeduplicator.java](../../services/notification-service/src/main/java/com/ecommerce/notification/idempotency/NotificationDeduplicator.java)

---

## 7. Prevention

- **Test**: unit test `OrderCreatedConsumer` với mock `NotificationDeduplicator` — verify `send()` chỉ gọi 1 lần khi cùng event gửi 2 lần.
- **Integration test** (Day 12): gửi cùng `eventId` 3 lần, assert `email_sent_total` = 1.
- **Lint rule**: không bao giờ remove idempotency check trong Kafka consumer. Code review checklist `docs/review/ai-junior-traps.md`.
- **Monitor**: alert khi `email_sent_total / orders_created_total > 1.1` trong 5 phút.
- **Runbook**: nếu duplicate vẫn xảy ra → kiểm tra Redis TTL còn đủ không; kiểm tra consumer group có bị rebalance loop không.

---

## 8. Trade-off accepted

- **Fail-open design**: Redis down → dedup bypass → có thể gửi duplicate email. Acceptable cho marketing notification. KHÔNG acceptable cho OTP/security alert → phải dùng approach fail-closed (DB table + Redis as cache layer).
- **TTL 24h**: phải đủ dài hơn Kafka retry window. Nếu sau này tăng Kafka `max.poll.interval.ms` lên > 24h thì phải tăng TTL tương ứng.
- **eventId dedup, không orderId dedup**: đúng granularity nhưng nếu producer publish cùng event nhiều lần với eventId khác nhau (producer bug) → dedup không chặn được → cần thêm `(orderId, templateName)` dedup ở layer 2 nếu cần.

---

## 9. Related

- Code: [NotificationDeduplicator.java](../../services/notification-service/src/main/java/com/ecommerce/notification/idempotency/NotificationDeduplicator.java)
- Code: [OrderCreatedConsumer.java](../../services/notification-service/src/main/java/com/ecommerce/notification/consumer/OrderCreatedConsumer.java)
- Lesson: [lessons/10-idempotency.md](../lessons/10-idempotency.md) — 4-layer idempotency model
- Lesson: [lessons/11-fire-and-forget.md](../lessons/11-fire-and-forget.md) — fire-and-forget vs retry trade-off
- Issue Day 10: [issues/10-duplicate-payment-callback.md](10-duplicate-payment-callback.md) — cùng pattern, khác context (payment vs notification)
- Day 12: retry + DLT — khi nào upgrade từ fire-and-forget lên retry queue
