# Interview · Day 17 — JPA N+1 (EntityGraph / JOIN FETCH / Projection)

> **Status**: ✅ Done · 2026-05-31
> Bối cảnh giả lập + 5 Q&A senior level + AI Playbook.

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — series A ecommerce VN, vừa qua đợt SQL tuning (Day 16). Traffic order tăng, user power-buyer có hàng chục đơn.
- **Role giao việc**: Anh Hùng — Tech Lead. "Trang 'Đơn hàng của tôi' load 3.2s với mấy ông mua sỉ 40 đơn. Datadog báo 41 query/request. Fix latency, đừng đổi contract API `OrderResponse`."
- **Bạn**: Backend owner `order-service` — own query/read path.
- **Reviewer**: Anh Hùng soi: (a) còn N+1 ẩn chỗ khác không, (b) pagination có còn chạy ở DB hay rơi vào in-memory, (c) có vô tình load full aggregate cho read path không.
- **Deadline**: 1 ngày, demo before/after số query (Hibernate statistics) tại standup.
- **Constraint thực tế**:
  - Không đổi schema (chỉ query/index).
  - Không break `GET /orders/{id}` hiện có.
  - List phải scope đúng user (không leak đơn người khác).
- **Definition of Done**:
  - `GET /orders` ≤ 2 query bất kể số đơn.
  - Pagination push xuống DB (không `HHH000104`).
  - Test đếm query verify regression.
  - Doc 9-section root cause + approaches.

---

## Q1 — "N+1 là gì? Vì sao đặt `FetchType.EAGER` không cứu mà còn tệ hơn?"

**Strong answer**:

> "N+1 là khi load N entity con bằng N query riêng sau 1 query parent — tổng
> 1+N round-trip. Cost O(N), latency tỉ lệ số parent.
>
> `FetchType` chỉ quyết định **khi nào** load (EAGER = ngay, LAZY = lúc chạm),
> KHÔNG quyết định **cách** load. Khi query trả nhiều root (list 40 order),
> EAGER ép Hibernate với mỗi order bắn 1 query lấy collection → 40 query phụ.
> Tệ hơn LAZY ở chỗ: LAZY ít ra còn **skip** được nếu không truy cập collection;
> EAGER thì luôn load, và bạn không tắt được per-query. Đó là lý do guideline
> 'collection luôn để LAZY' — rồi chủ động JOIN FETCH/EntityGraph khi cần."

**Follow-up trap**: *"Vậy đổi sang LAZY là hết N+1?"*
→ Không. LAZY chỉ **hoãn**. Nếu code loop `orders.forEach(o -> o.getItems())` thì vẫn 1+N, chỉ là lazy. Hết N+1 cần JOIN/batch/projection — fetch *plan*, không phải fetch *type*.

---

## Q2 — "Fix bằng `JOIN FETCH` rồi thêm `Pageable` — có ổn không?"

**Strong answer**:

> "Không ổn nếu fetch **collection**. JOIN FETCH collection + Pageable → SQL sinh
> ra **không có LIMIT**. Hibernate kéo toàn bộ row khớp về JVM rồi phân trang
> trong RAM, kèm WARN `HHH000104: applying in memory`. Data nhỏ thì 'chạy đúng',
> data 100K row thì OOM. Pagination không còn ở DB.
>
> Đây là cái bẫy chết người vì chỉ là **WARN**, test page nhỏ vẫn pass. Fix:
> hoặc 2-step (query page các id trước bằng pagination thật → JOIN FETCH theo
> id list), hoặc — như tôi chọn — dùng **projection DTO** cho list, pagination
> LIMIT/OFFSET chạy ở DB."

**Follow-up trap**: *"`@EntityGraph` có dính bẫy này không?"*
→ Có, y hệt — `@EntityGraph` cũng là JOIN FETCH dưới gầm. Với collection + Pageable đều in-memory. `@EntityGraph` an toàn khi load **1** entity (detail path), không phải list.

---

## Q3 — "JOIN FETCH 2 collection cùng lúc thì sao?"

**Strong answer**:

> "`MultipleBagFetchException` lúc bootstrap. `List` không có `@OrderColumn` là
> **bag** — Hibernate không dựng nổi cartesian product của 2 bag (ambiguous
> row→element mapping). Fix: (a) đổi `List`→`Set` (Set không phải bag, hợp lệ),
> hoặc (b) tách multi-query / `@EntityGraph` từng collection.
>
> Lưu ý: đổi Set chỉ giải quyết exception, KHÔNG giải quyết **cartesian blow-up**
> — JOIN 2 collection N và M phần tử cho ra N×M row, blow up băng thông. Khi 2
> collection đều lớn, multi-query (Hibernate fetch tuần tự) tốt hơn 1 query
> cartesian."

**Follow-up trap**: *"Order của bạn chỉ có 1 collection, sao phải lo?"*
→ Đúng, Order chỉ có `items` nên JOIN FETCH an toàn. Nhưng nếu mai thêm `payments` hoặc `statusHistory` cũng `List` và ai đó JOIN FETCH cả hai → nổ. Tôi note trong [lesson 17](../lessons/17-jpa-fetch-strategies.md) để team biết trước.

---

## Q4 — "Khi nào projection thắng `@EntityGraph`?"

**Strong answer**:

> "Khi path là **read-only** và không cần behavior/full graph. Projection
> select thẳng scalar (kể cả `size(items)` qua subquery COUNT) → không load
> entity, không vào persistence context → không dirty-checking, không snapshot,
> nhẹ heap+CPU. Pagination chạy ở DB.
>
> `@EntityGraph` tốt khi tôi **cần** aggregate đầy đủ — ví dụ detail order để
> hiển thị từng item, hoặc load để mutate (write path cần dirty-checking). Quy
> tắc của tôi: **list = projection, detail/write = entity**. Đây là CQRS-lite,
> không over-engineer — chỉ là không ép 1 model phục vụ 2 access pattern."

**Follow-up trap**: *"Vậy maintain 2 model (view + entity) không vi phạm DRY?"*
→ DRY là về *knowledge*, không phải *shape*. Read model và write model encode 2 nhu cầu khác nhau; trùng vài field không phải duplication có hại. Ép chung mới là nguồn N+1 ở đây.

---

## Q5 — "Làm sao chặn N+1 tái phát? Đừng nói 'review kỹ hơn'."

**Strong answer**:

> "3 lớp:
> 1. **Test đếm query** — bật `hibernate.generate_statistics`, assert
>    `getPrepareStatementCount() ≤ 2` cho list path. Regression làm số query tăng
>    → test đỏ. Tôi lock cả chiều ngược: test nấc-0 assert `≥ 1+N` để chứng minh
>    N+1 thật sự tồn tại (sống động cho ai đọc lại).
> 2. **`open-in-view: false`** (đã bật từ Day 3) — lazy access ngoài tx fail-fast
>    `LazyInitializationException` thay vì âm thầm N+1 lúc serialize JSON. Lỗi nổ
>    lúc dev, không lên prod.
> 3. **Observability** — staging bật statistics + alert query/request > ngưỡng;
>    APM (Datadog) flag span DB count cao."

**Follow-up trap**: *"`generate_statistics` bật prod được không?"*
→ Có overhead (counter + sync), thường để **staging/canary**, prod thì dựa APM span count. Bật prod ngắn hạn khi điều tra incident thì được, không để thường trực.

---

## 🧠 Senior mindset notes

- N+1 không phải "lỗi lazy" — là **mismatch giữa access pattern và fetch plan**. Hỏi "màn này cần gì" trước khi chọn fetch.
- `HHH000104` là WARN, không phải ERROR — đó là lý do nó lọt review. Senior đọc log; junior đọc kết quả.
- Read path và write path đáng được tách model. List screen gần như luôn nên là projection + (Day 18) keyset pagination.

## 🤖 AI Playbook

- **Giao AI**: generate boilerplate projection record + repository `@Query` constructor expression + test scaffold đếm query bằng `Statistics`.
- **Prompt mẫu**:
  > "Spring Data JPA + Hibernate 6: viết constructor-expression projection cho Order list gồm id, statusType, total.amount, placedAt, itemCount (qua size(items)) + method Page<...> với Pageable. KHÔNG load entity. Kèm test JUnit đếm prepared statement."
- **Risk**: AI default `JOIN FETCH` + `Pageable` mà **không** cảnh báo in-memory pagination (`HHH000104`); hoặc projection trỏ vào association lazy → vẫn N+1; hoặc sai package trong `new com....()` → lỗi bootstrap runtime.
- **Validate**: bật `hibernate.generate_statistics=true`, assert query count ≤ 2; grep log tìm `HHH000104`; verify `itemCount` đúng bằng test data thật.

---

## 🔗 Related

- [issue 17 — N+1 trang đơn hàng (9-section)](../issues/17-jpa-n-plus-one.md)
- [lesson 17 — fetch strategies](../lessons/17-jpa-fetch-strategies.md)
- [evolution 17 — chương N+1](../evolution/17-anh-boi-ban.md)
- Code: [OrderRepository](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java) · [OrderQueryService](../../services/order-service/src/main/java/com/ecommerce/order/application/OrderQueryService.java)
