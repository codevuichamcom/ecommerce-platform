# Day 10 — Payment callback (idempotent) — Interview drill

> Day 10 · `payment-service` mock VNPay/Momo callback
> Related: [lesson 10 idempotency](../lessons/10-idempotency.md) · [issue 10 duplicate callback](../issues/10-duplicate-payment-callback.md) · [ADR-007 payment Layered](../decisions/007-payment-service-layered-not-ddd.md)

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN (Series A ecom marketplace, ~50k DAU, đang chuẩn bị mùa flash sale T7).
- **Role giao việc**: Anh Hùng (Tech Lead) — Day 9 vừa xong order flow event-driven; giờ cần đóng loop payment cho MVP demo CTO.
- **Bạn**: Tonny — backend owner của payment-service mới (Layered, scope hẹp).
- **Reviewer**: Anh Hùng (kỹ thuật) + chị Linh (PM) — chị Linh sẽ soi reconciliation report cuối tuần.
- **Deadline**: 1 sprint day. Demo: place order → mock gateway callback → order chuyển `Paid`.
- **Constraint thực tế**:
  - Gateway thật (VNPay/Momo) **retry callback 3 lần/24h** nếu app trả 5xx — duplicate là chắc chắn.
  - Network duplicate (cell tower handover) — cùng `provider_txn_id` đến 2 lần < 1s.
  - Không được đụng `order-service` schema (Day 13 outbox mới refactor).
- **Definition of Done**:
  - Callback duplicate test mock 2 thread race → đúng 1 event published.
  - FAILED outcome → không publish, state FAILED persist.
  - Build green; ≥ 6 unit test idempotency PASS.

## 🎯 5 Câu hỏi mock interview

### Q1. "Idempotent endpoint là gì? Tại sao callback payment cần idempotent?"

**Strong answer**: Idempotent operation = N lần gọi cùng input → **side-effect xảy ra 1 lần**, response giống nhau. Khác safe method (GET) ở chỗ idempotent CÓ side-effect, chỉ là không nhân đôi khi retry.

Payment callback cần idempotent vì:
- Gateway VNPay/Momo retry 3-5 lần khi app trả 5xx (P99 spike, OOM, deploy rolling).
- Network duplicate (TCP retransmit + mobile cell tower handover).
- Operator gửi tay từ admin dashboard.

Không idempotent → 2 row CAPTURED + 2 event `payment.completed` → order-service consume 2 lần → `markPaid` lần 2 no-op nhưng reconciliation alarm vì mismatch metric.

**Follow-up trap**: *"PUT idempotent à? Sao có team trên CV ghi 'PUT idempotent API'?"*
→ PUT idempotent theo HTTP spec, nhưng **IMPLEMENTATION phải đảm bảo**. Nhiều PUT trong wild thực chất không idempotent (vd PUT increment counter). Đừng nói "PUT idempotent" mà không có UNIQUE constraint hoặc dedup logic backing — đó là CV inflate.

### Q2. "Tại sao chọn DB UNIQUE constraint thay vì Redis SETNX cache dedup?"

**Strong answer**: UNIQUE là **storage-level**, source of truth. SETNX là **app-level optimization**.

| Khía cạnh | UNIQUE | SETNX cache |
|---|---|---|
| Atomic với DB transaction | ✅ Trong cùng tx | ❌ Riêng infra |
| Survive app/cache crash | ✅ Persist | ❌ Redis OOM/restart mất state |
| Source of truth | ✅ | ❌ |
| Latency thấp khi cache hit | ❌ +1 DB call | ✅ |

Production-grade phải có UNIQUE. SETNX có thể bolt-on làm L1 (Day 15) — nhưng nếu Redis down, UNIQUE vẫn bắt được race.

**Follow-up trap**: *"SERIALIZABLE isolation thay được UNIQUE không?"*
→ Được nhưng **đắt** — throughput drop 5-10x vì lock conflict. UNIQUE là index-level check (B-tree). Production hiếm ai dùng SERIALIZABLE rộng — chỉ áp dụng cho operation đặc biệt (vd double-entry bookkeeping Day 36).

### Q3. "Code anh xử thế nào khi 2 callback đến đồng thời cùng `provider_txn_id`?"

**Strong answer**: 3 layer chống đỡ:
1. **L3 fast-path** — `findByProviderAndProviderTxnId` lookup; hit → return idempotent, no event.
2. **L4 UNIQUE constraint** — nếu race lọt L3 (cùng miss), 2 thread cùng `INSERT` → 1 thắng, 1 catch `DataIntegrityViolationException` → lookup existing → return duplicate response.
3. **Retry optimistic lock** — `@Retryable(ObjectOptimisticLockingFailureException, maxAttempts=3, backoff exp 50→500ms)` cho race UPDATE.

```java
try {
    intent.capture(txnId);
    repo.saveAndFlush(intent);   // ép flush, UNIQUE fail-fast trong tx
    publisher.publish(event);
} catch (DataIntegrityViolationException dup) {
    var winner = repo.findByProviderAndProviderTxnId(...).orElseThrow();
    return new CallbackResult(winner, true, false);  // KHÔNG publish
}
```

`saveAndFlush` quan trọng: `save()` thuần defer INSERT đến commit → exception raise sau khi ra `try` → không catch được.

**Follow-up trap**: *"Sao không lock pessimistic FOR UPDATE row PaymentIntent?"*
→ Không scale. 100 concurrent callback cùng `paymentId` (test) → 99 thread block → P99 hỏng. UNIQUE + retry là optimistic → cho phép thử song song, chỉ 1 thắng.

### Q4. "Order và Payment ở 2 service riêng, eventual consistency. User app hiển thị thế nào?"

**Strong answer**: Order ở `PendingPayment` khi vừa place. Sau khi payment callback xử lý + publish `payment.completed`, order-service consume → transition `Paid`. Lag P99 ~500ms (baseline đã set Day 9 OTel Zipkin).

UX:
- Sau redirect gateway thành công → hiển thị "Đang xác nhận thanh toán..." 5 giây.
- Poll `GET /orders/{id}` mỗi 1 giây OR Server-Sent Event (Day 29).
- Nếu sau 30 giây vẫn `PendingPayment` → "Đang xử lý, sẽ gửi email khi hoàn tất" (cron job mark expired Day 12).

**Follow-up trap**: *"Sao không strong consistency? Payment + Order trong 1 transaction?"*
→ 2 service, 2 DB. 2PC (XA) thì khả thi nhưng:
- Throughput drop 10x do prepare phase blocking.
- Coordinator failure → recovery phức tạp.
- Tonny không thấy Sotatek/team thật nào dùng 2PC cross-service trong 5 năm.
→ Eventual consistency + idempotent là **industry pattern**. Reconciliation Day 36 đảm bảo correctness cuối ngày.

### Q5. "Gateway gửi callback signature HMAC — đủ chưa? Replay attack?"

**Strong answer**: HMAC-SHA256 trên `timestamp + "." + body` chống **tampering** (attacker đổi amount sẽ mismatch). Chưa đủ chống **replay**:

Cần thêm:
1. **Timestamp skew window** — reject nếu `|now - timestamp| > 5min`. Day 10 đã có (`callbackMaxSkewSeconds=300`).
2. **Nonce dedup table** — lưu signature seen trong 24h, reject nếu hit. Day 10 SKIP (rely on UNIQUE `provider_txn_id` ở business layer — nếu attacker replay valid signature, request data giống hệt, business layer dedup vẫn catch).
3. **Constant-time comparison** — `MessageDigest.isEqual()` thay vì `String.equals()` (timing attack).
4. **IP allowlist** — gateway publish egress IP list, app reject other IPs. Production-grade.

**Follow-up trap**: *"Attacker thấy log của gateway, biết secret thì sao?"*
→ Secret KHÔNG bao giờ log. Mã hóa at-rest (Vault/KMS). Rotate per quarter. Nếu compromise → revoke + re-issue, gateway support hotline. Production ShopVN: per-merchant key, gateway tự rotate.

## 🧠 Senior mindset notes

- **Idempotency là contract** giữa system, không phải implementation detail. Phỏng vấn senior: phân biệt được "idempotent op" vs "idempotent endpoint" vs "Idempotency-Key header" sẽ tách top 20%.
- **Mỗi quyết định kèm trade-off rõ**. ADR-007 ghi "scope hẹp → Layered, không DDD" có lý lẽ (3-điểm criteria). Đừng nói "pick DDD vì best practice".
- **Dual-write debt là thật**. DB commit + Kafka publish không atomic — Day 13 outbox fix. Phỏng vấn hỏi "anh có biết outbox không?" → trả lời chi tiết (poll-based vs CDC) sẽ ghi điểm.

## 🤖 AI Playbook

- **AI làm tốt**: scaffold module Gradle + Flyway migration + JPA entity boilerplate + REST DTO + signature HMAC helper. Test boilerplate Mockito.
- **Prompt mẫu**:
  ```
  Scaffold Spring Boot 3.4 service `payment-service` (Layered) tương tự
  cấu trúc `services/order-service`. PaymentIntent JPA entity với sealed
  status (Initiated/Authorized/Captured/Failed/Expired), @Version optimistic
  lock, UNIQUE(provider, provider_txn_id) partial index. KHÔNG implement
  HandleCallbackUseCase logic — tôi tự viết.
  ```
- **Risk khi AI làm**:
  1. **Pattern check-then-insert** thay vì UNIQUE + catch — sẽ test pass sequential, fail concurrent.
  2. **save()** thay vì saveAndFlush() trong try-catch — exception raise sau khi exit try.
  3. **Missing `@Retryable` + `REQUIRES_NEW`** propagation — retry trong cùng tx đã rollback → fail.
  4. **String equals() so signature** thay vì `MessageDigest.isEqual` — timing attack.
  5. **HMAC compute không UTF-8 encode rõ** — locale-dependent.
- **Validate**:
  - Chạy `HandleCallbackUseCaseTest.uniqueConstraintRace_returnsDuplicate` — verify mock throw `DataIntegrityViolationException` → catch + no publish.
  - Grep code: KHÔNG có pattern `findBy…isEmpty()` rồi `save()` không có UNIQUE backing.
  - Verify Migration V1 có `CREATE UNIQUE INDEX` với partial WHERE clause.
  - Run `CallbackSignatureVerifierTest.verify_tampered_rejected` — đảm bảo HMAC mismatch reject.
