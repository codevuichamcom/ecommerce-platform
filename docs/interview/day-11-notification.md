# Interview — Day 11: Notification Service + API Versioning

> **Status**: ✅ Done (2026-05-19)

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — Series B, 2M MAU, team backend 8 người.
- **Role giao việc**: Anh Hùng (Tech Lead) giao sau khi Day 10 payment callback xong. "Em làm notification xong trong 1 sprint — ưu tiên order confirmed email. Sau đó marketing hỏi versioning, tụi nó cần endpoint v2 thêm field `channelUsed`."
- **Bạn**: Backend dev, owner `notification-service` — design + code + test.
- **Reviewer**: Anh Hùng — soi: idempotency (tránh spam email), template injection risk (Thymeleaf `th:text` vs `th:utext`), versioning strategy coherent với gateway plan.
- **Deadline**: 1 sprint — deliver: consumer render template + dispatch log + v2 endpoint demo.
- **Constraint thực tế**: Chưa có SMTP server → adapter pattern (log-only impl). Không thêm DB cho notification (stateless + Redis dedup). Versioning phải demo được qua curl — không chỉ lý thuyết.
- **Definition of Done**: (1) `order.created` consumer log rendered template. (2) `payment.completed` consumer xử lý được. (3) Duplicate Kafka event → bỏ qua, không re-render. (4) `/api/v1/notifications/health` và `/api/v2/notifications/health` trả response khác nhau. (5) Build green.

---

## 🎤 Q&A

### Q1: "Email service của em gửi email 3 lần khi Kafka retry. Em fix thế nào?"

**Strong answer:**

> Idempotent consumer với Redis SET NX. Trước khi render + dispatch, check `setIfAbsent("notif:dedup:{eventId}", "1", TTL 24h)`. Nếu return false (key đã tồn tại) → skip silently.
>
> TTL 24h >> Kafka max retry window (~10 phút với Spring Kafka default) → đủ cover mọi scenario restart/rebalance.
>
> Key là `eventId` (UUID unique per event, từ `DomainEvent` contract Day 8), không phải `orderId` — vì cùng order có nhiều event loại khác nhau.

**Follow-up trap**: "Redis down thì sao?"

> Fail-open: catch exception → return true (xử lý như lần đầu). Trade-off: có thể gửi duplicate khi Redis unavailable. Với marketing notification → acceptable. Với OTP/security alert → fail-closed (throw, không gửi, log alert).

**Follow-up trap**: "Kafka exactly-once semantics có giải quyết được không?"

> Kafka EOS (KIP-98) cover việc producer publish exactly-once đến broker, và consumer read exactly-once từ broker. Nhưng KHÔNG cover dispatch fail sau khi event được consume — nếu SMTP timeout sau khi offset committed, event vẫn mất. Application-level idempotency vẫn cần.

---

### Q2: "Em chọn URI versioning hay header versioning? Tại sao?"

**Strong answer:**

> URI versioning (`/api/v1/`, `/api/v2/`) vì 3 lý do thực tế:
> 1. **Gateway route**: path predicate đơn giản hơn custom header matching.
> 2. **Debug**: curl không cần `-H` flag, log filter trivial.
> 3. **CDN cache**: URL là cache key — không bị cache miss do client gửi header khác.
>
> Trade-off hy sinh URL "purity" theo lý thuyết REST. Pragmatism > purism.

**Follow-up trap**: "Có người nói URI versioning 'không RESTful' — em nghĩ sao?"

> Đúng về lý thuyết — REST resource không nên thay đổi URI chỉ vì representation đổi. Nhưng đây là architectural trade-off. Stripe, GitHub, Twilio — ba API được đánh giá cao nhất — đều dùng URI versioning. Trong context team 8 người, pragmatism quan trọng hơn purist correctness.

---

### Q3: "Breaking change là gì? Em distinguish như thế nào?"

**Strong answer:**

> **Non-breaking**: thêm optional field (JSON additive — client cũ `@JsonIgnoreProperties(ignoreUnknown=true)` tự bỏ qua), thêm endpoint mới, thêm HTTP method.
>
> **Breaking**: xóa field, đổi type (string → int), đổi semantic (status từ "PENDING" sang 0), rename field, xóa endpoint, đổi HTTP method.
>
> Rule đơn giản: nếu client cũ parse response của version mới bị lỗi hoặc ra kết quả sai → breaking.

**Follow-up trap**: "Database schema migration khi có breaking change thì làm thế nào?"

> **Expand-contract pattern** (3 phase):
> 1. **Expand**: thêm column mới (nullable), deploy v2 API — v1 vẫn chạy, v2 write cột mới.
> 2. **Migrate**: backfill data cũ vào column mới (background job).
> 3. **Contract**: sau khi v1 sunset, drop column cũ.
> KHÔNG one-step migration vì v1 code vẫn write schema cũ trong sunset window.

---

### Q4: "Notification service của em fire-and-forget. Nếu SMTP fail thì email mất phải không?"

**Strong answer:**

> Đúng. Fire-and-forget là accepted trade-off: catch exception trong handler → log error → không re-throw → Kafka commit offset → event không retry.
>
> Acceptable cho marketing notification (order confirmed, payment received). KHÔNG acceptable cho:
> - OTP/2FA — user không login được.
> - Legal evidence email — cần audit trail.
>
> Khi upgrade: thêm `notification_outbox` table — handler persist vào outbox, background job retry SMTP với backoff. Day 34 system design sẽ cover architecture này ở scale 10M notification/day.

---

### Q5: "Thymeleaf trong notification service có bị XSS risk không?"

**Strong answer:**

> Thymeleaf auto-escape HTML khi dùng `th:text` — render `<` thành `&lt;`. Không có XSS risk với `th:text`.
>
> Risk xảy ra khi dùng `th:utext` (unescaped) với dữ liệu từ user. Rule tuyệt đối: KHÔNG bao giờ dùng `th:utext` với dữ liệu user-controlled (orderId, customerName, address).
>
> Template compile-time (không render từ String dynamic) → không có Server-Side Template Injection (SSTI) risk như Freemarker nếu dùng đúng.

**Follow-up trap**: "Em validate ở đâu?"

> Code review checklist: grep `th:utext` trong tất cả Thymeleaf template — phải return 0. Thêm vào `docs/review/ai-junior-traps.md`.

---

## 🧠 Senior mindset notes

- **Idempotency trước performance**: fire-and-forget không có nghĩa là không cần dedup. Kafka at-least-once delivery là default — mọi consumer phải assume duplicate.
- **Adapter pattern là bắt buộc**: `NotificationChannel` interface tách dispatch khỏi business logic. Khi thêm SMS/push → implement interface mới, không sửa consumer. Đây là Open/Closed Principle thật sự, không phải lý thuyết.
- **Versioning scope discipline**: đừng version tất cả endpoint. Chỉ version khi có khả năng contract thay đổi. Mỗi version thêm 1 file controller + 1 test suite = cognitive load.

---

## 🤖 AI Playbook

- **AI làm tốt**: Generate Thymeleaf template HTML + boilerplate controller pairs (v1/v2) — repetitive, low risk. Generate Redis dedup boilerplate.
- **Prompt mẫu**: "Generate Thymeleaf email template `order-created.html` với biến `orderId`, `totalAmount`, `currency`, `itemCount`. Dùng `th:text` (không dùng `th:utext`). HTML fragment, không CSS framework."
- **Risk**: AI thường dùng `th:utext` thay vì `th:text` → XSS. AI hay quên TTL cho Redis key → memory leak. AI hay tạo `Map.of()` với nhiều hơn 10 entry → `IllegalArgumentException` (Java Map.of max 10 pairs).
- **Validate**: grep `th:utext` trong template output phải = 0. Test dedup: gửi 2 event cùng `eventId` → chỉ 1 log "dispatched". Kiểm tra Redis TTL được set.
