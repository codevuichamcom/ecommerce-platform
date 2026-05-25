# Lesson 12 — Retry strategy: exponential backoff + jitter + non-retryable classification

> **Day**: 12 · **Topic**: Retry pattern cho Kafka consumer + outbound HTTP call.
> **Related code**: [`RetryTopologyConfiguration`](../../services/notification-service/src/main/java/com/ecommerce/notification/messaging/RetryTopologyConfiguration.java) · [`MockGatewayClient`](../../services/payment-service/src/main/java/com/ecommerce/payment/gateway/MockGatewayClient.java)

---

## 🎯 TL;DR

> Retry chỉ giải quyết **transient failure** (network blip, broker leader election, gateway 503). KHÔNG dùng retry cho validation error / business rule violation / non-idempotent operation chưa có dedup. Default đúng: **exponential backoff + jitter + max attempts ≤ 5 + non-retryable classification**.

---

## 📚 Khi nào DÙNG retry

- Network timeout / connection reset.
- Server 5xx (gateway 502/503/504, broker NotLeaderForPartition).
- Optimistic lock conflict (Day 4 inventory pattern).
- Race window quanh DB unique constraint (Day 10 callback).
- Kafka rebalance trong-flight.

## 🚫 Khi nào KHÔNG dùng retry

- **4xx validation error** — retry không bao giờ thành công, lãng phí + nhiễu log.
- **Non-idempotent operation chưa dedup** — retry sẽ double-charge / double-send.
- **Schema/deserialization error** — payload sai cấu trúc, retry vô nghĩa.
- **Business rule violation** — "insufficient stock", "duplicate order" — retry không fix.

---

## 🆚 Approaches compared

| Approach              | Pros                                     | Cons                                                              |
| --------------------- | ---------------------------------------- | ----------------------------------------------------------------- |
| **Fixed delay**       | Đơn giản, predictable                    | **Thundering herd** khi N consumer cùng wake same time            |
| **Exponential**       | Phân tán retry, recover tốt từ overload  | Tail latency P99 tăng (lần retry cuối 16s)                        |
| **Exp + jitter**      | ✅ Default đúng — chống đồng pha          | Implement đúng cần thêm random ±20% — Spring `ExponentialBackOff` mặc định KHÔNG có jitter (phải config) |
| **Linear backoff**    | Middle-ground                            | Vẫn có nguy cơ đồng pha nhẹ                                       |
| **Decorrelated jitter** | Tốt nhất khi N client high (AWS pattern) | Complex, thường overkill cho intra-service                        |

### Math: exponential backoff

```
delay(n) = min(initial × multiplier^n, maxBackoff)
```

Day 12 config: `initial=1s, multiplier=4, maxBackoff=16s, maxAttempts=3`

| Attempt | Delay  | Cumulative wait |
| ------- | ------ | --------------- |
| 1       | 1s     | 1s              |
| 2       | 4s     | 5s              |
| 3       | 16s    | 21s             |

Sau 21s vẫn fail → **persistent failure** (bug code hoặc data corruption), không transient → DLT.

### Math: jitter (full jitter — AWS recommended)

```
delay(n) = random(0, min(initial × multiplier^n, maxBackoff))
```

Jitter biến deterministic 1s/4s/16s thành range. 100 consumer cùng wake → 100 retry phân bố trong 0-16s thay vì spike cùng millisecond.

---

## 🔧 Implementation patterns

### Kafka consumer side — `DefaultErrorHandler`

```java
ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 4.0);
backOff.setMaxInterval(16_000L);

DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
handler.addNotRetryableExceptions(
        IllegalArgumentException.class,
        DeserializationException.class);
handler.setCommitRecovered(true);
```

3 quyết định quan trọng:
1. **Non-retryable list** — schema/validation lỗi → DLT ngay, skip 21s lãng phí.
2. **`setCommitRecovered(true)`** — sau khi DLT publish thành công, commit offset gốc → KHÔNG block partition.
3. **`BackOff` instance per-handler** — KHÔNG share giữa handler (state internal).

### Outbound HTTP — Spring Retry hoặc Resilience4j Retry

Day 10 đã dùng `@Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 50, multiplier = 2.0))` cho payment callback. Đây là **app-internal** retry quanh optimistic lock — KHÔNG phải outbound retry.

Outbound retry Day 12+ dùng Resilience4j `@Retry` (different annotation từ Spring Retry). Lý do: tích hợp với CircuitBreaker registry — khi CB OPEN, Retry KHÔNG re-attempt (tránh waste).

---

## ⚠️ Cạm bẫy

1. **Retry trong-process trên Kafka consumer block partition.** 21s mỗi message × 1000 message backlog = 5.8h. Day 13 outbox + retry-topic pattern non-blocking sẽ fix.
2. **Quên `setCommitRecovered(true)`** — DLT publish ok nhưng offset gốc không commit → rebalance → re-consume → DLT lại. Loop vô tận.
3. **Retry không có cap** — Spring Kafka pre-2.8 default `FixedBackOff(0, 9)` — retry 10 lần ngay lập tức. Đọc kỹ default version của library.
4. **Retry storm khi downstream recover** — 1000 consumer cùng resume → spike. Cần jitter.
5. **Idempotency assumption** — retry transient assume operation idempotent. Day 10 dedup là tiền đề cho retry an toàn.

---

## 🎤 Trả lời phỏng vấn

**Q1: Retry với fixed delay vs exp backoff — khi nào dùng cái nào?**

Fixed delay chỉ dùng cho retry ngắn N≤2 với low concurrency. Production default là exponential backoff vì 2 lý do: (a) cho downstream thời gian recover (1s → 4s → 16s, tăng dần), (b) phân tán retry tránh thundering herd. Thêm jitter biến delay từ deterministic sang range để chống đồng pha — N consumer cùng wake không cùng spike. Cap `maxBackoff` để tránh wait quá lâu — sau 16-30s mà vẫn fail thì là persistent failure, không transient.

**Q2: Khi nào KHÔNG nên retry?**

3 trường hợp: (1) validation error / 4xx — retry không bao giờ pass; (2) operation non-idempotent chưa có dedup — retry gây side-effect đôi (double-charge); (3) business rule violation — retry không fix logic. Pattern đúng là classify exception trước retry: `addNotRetryableExceptions(IllegalArgumentException.class, ...)`.

**Q3: Retry budget — bạn chọn maxAttempts thế nào?**

Trade-off recovery vs blocking. Mỗi attempt block 1 consumer thread (hoặc connection slot ngoài). Project Day 12 chọn 3 attempts × exp backoff (1+4+16=21s) — đủ recover transient (broker election ~10s, network blip <5s), không quá dài để block partition. Volume cao → switch sang non-blocking retry topic (Spring Kafka `@RetryableTopic`) để producer chính tiếp tục.

### Follow-up traps

- *"Retry trong loop là retry storm — đúng không?"* — Đúng nếu downstream chưa recover. Mitigation: CircuitBreaker phía trước Retry; CB OPEN → Retry skip.
- *"Bạn nói exp backoff — Spring `ExponentialBackOff` có jitter không?"* — KHÔNG mặc định. Phải `setRandomizationFactor` hoặc dùng `BackOffPolicy` của Spring Retry với `UniformRandomBackOffPolicy`. Trap khi candidate copy code không check.

---

## 🔗 Related

- [`lessons/12b-circuit-breaker-resilience4j.md`](12b-circuit-breaker-resilience4j.md) — CB stack với Retry
- [`lessons/12c-kafka-delivery-semantics.md`](12c-kafka-delivery-semantics.md) — at-least-once + idempotent consumer (tiền đề cho retry an toàn)
- [`lessons/10-idempotency.md`](10-idempotency.md) — Day 10 dedup pattern
- [`issues/12-poison-message.md`](../issues/12-poison-message.md) — retry-then-DLT chosen approach
- Code: [`RetryTopologyConfiguration.java`](../../services/notification-service/src/main/java/com/ecommerce/notification/messaging/RetryTopologyConfiguration.java)
