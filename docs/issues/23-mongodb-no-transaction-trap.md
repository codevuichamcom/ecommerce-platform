# 🔥 Issue 23 — MongoDB no-transaction trap: order mồ côi item

> **Severity**: 🟡 SEV-3 (data integrity, không sập service) · **Status**: prevented by design
> **Context**: giả lập khi dev định nhét data multi-document vào Mongo mà tưởng có ACID như Postgres.

---

## 1. Problem

Một dev (hoặc AI) mới chuyển từ Postgres sang Mongo, viết code lưu `order` và
`order_items` thành **2 document riêng**, update 2 lần trong 1 method, tin rằng
"Mongo cũng có transaction". App crash giữa 2 write → order ghi xong, items
chưa ghi → **order mồ côi item**, KHÔNG rollback.

---

## 2. Symptoms

- Document `orders` có `orderId=X` nhưng `order_items` collection KHÔNG có record nào của `X`.
- Log: write thứ nhất `INFO saved order X`, sau đó exception, KHÔNG có `saved items X`.
- Report/đọc order X → total = 0 item, hiển thị đơn rỗng.
- Trên **single-node Mongo** (docker-compose dev), gọi `@Transactional` / `session.startTransaction()` ném:
  `Transaction numbers are only allowed on a replica set member or mongos`.

---

## 3. Root cause

MongoDB **single-document write là atomic**, nhưng **multi-document
transaction** chỉ có từ Mongo 4.0 (replica set) / 4.2 (sharded), và **bắt buộc
chạy trên replica set** — single-node standalone KHÔNG hỗ trợ.

Dev mang mental model Postgres ("mọi thứ trong `@Transactional` là all-or-nothing")
sang Mongo. Trên Mongo standalone:
- 2 `save()` vào 2 collection = 2 thao tác độc lập.
- Crash giữa chừng = thao tác 1 đã commit, thao tác 2 chưa chạy → **no rollback**.

Gốc rễ sâu hơn: **aggregate boundary ≠ document boundary**. Thứ cần atomic bị
tách ra 2 document.

---

## 4. Approaches compared

| # | Approach | Pros | Cons |
|---|----------|------|------|
| 1 | ✅ **Embed items vào order document** (aggregate = document) | Single-doc write atomic, KHÔNG cần txn, đọc order 1 phát | Document phình nếu order nghìn item (hiếm); update 1 item ghi lại cả doc |
| 2 | **Multi-document transaction (replica set)** | Giữ tách collection, ACID thật | Bắt buộc replica set (ops nặng hơn); txn Mongo đắt + giữ lock; chậm hơn single-doc nhiều |
| 3 | **2-phase app-level (saga/compensation)** | Không cần replica set | Phức tạp; phải tự viết compensation; vẫn có cửa sổ inconsistent |
| 4 | **Để data này ở Postgres, không Mongo** | ACID native, đúng tool | Mất lợi ích document cho phần schema đa hình |

---

## 5. Chosen approach + Why

**Approach 1 — embed (cho data Mongo) + approach 4 (cho data invariant)**, kết hợp theo bản chất data:

- **Data CÓ invariant multi-entity cần atomic** (order + items + total) → **để ở Postgres** (Day 6 order-service đã làm: `Order` aggregate + `OrderItem` cùng 1 transaction relational). KHÔNG đưa vào Mongo.
- **Data Mongo (Day 23)** → thiết kế để **mọi write là single-document**:
  - `analytics_events`: mỗi event 1 document độc lập, append-only — không bao giờ cần update 2 doc cùng lúc. Order có nhiều item → ghi **N document `order_placed` riêng**, mỗi cái atomic (xem [`OrderEventConsumer`](../../services/analytics-service/src/main/java/com/ecom/analytics/ingest/OrderEventConsumer.java)).
  - `product_catalog`: 1 product = 1 document (attributes embed bên trong) — update 1 product = single-doc write atomic.

→ **Day 23 KHÔNG cần multi-doc transaction** vì thiết kế đã align aggregate boundary = document boundary. Tránh cả replica-set ops lẫn saga complexity.

> 🧠 Vì sao không bật replica set cho chắc? Vì *không có data nào cần nó*. Bật
> replica set = thêm ops (3 node, election, oplog) cho thứ design đã loại bỏ nhu
> cầu. Senior = đổi design để khỏi cần txn, không phải thêm hạ tầng để cứu design sai.

---

## 6. Fix

Thiết kế single-document (đã áp dụng):

```java
// OrderEventConsumer — order N item → N document riêng, mỗi cái atomic.
for (OrderCreatedV1.Item item : event.items()) {
    AnalyticsEvent ae = new AnalyticsEvent(
        EventType.ORDER_PLACED, event.occurredAt(),
        null, event.userId().toString(), item.sku(), payload);
    ingestService.ingest(ae);   // 1 save = 1 single-document write atomic
}
```

```java
// ProductCatalogDocument — attributes EMBED, không tách collection riêng.
@Document(collection = "product_catalog")
public class ProductCatalogDocument {
    @Id private String id;
    private Map<String, Object> attributes;  // embed, không reference
}
```

Test khác biệt **dev (standalone) vs test (replica set)**: `MongoDBContainer`
của Testcontainers khởi tạo single-node **replica set** → txn DÙNG được trong
test. docker-compose dev là **standalone** → txn KHÔNG có. Khác biệt này được
ghi rõ trong [`MongoTestcontainerConfig`](../../services/analytics-service/src/test/java/com/ecom/analytics/support/MongoTestcontainerConfig.java)
để không ai tưởng "test pass nghĩa là dev có txn".

---

## 7. Prevention

- **Lint/review rule**: bất kỳ method Mongo nào gọi `save()`/`update()` ≥2 lần
  vào ≥2 collection → cờ đỏ review. Hỏi: "có cần atomic không? Nếu có → embed
  hoặc chuyển Postgres."
- **Design check**: trước khi cho data vào Mongo, hỏi "aggregate boundary có
  trùng document boundary không?" Nếu không → redesign.
- **Doc khác biệt môi trường**: ghi rõ dev=standalone, test=replica-set để
  không false-confidence.
- **Monitor**: nếu thật sự cần multi-doc (tương lai), alert khi
  `Transaction numbers are only allowed on a replica set` xuất hiện trong log.

---

## 8. Trade-off accepted

- **Embed** → update 1 phần tử nhỏ phải ghi lại cả document. Chấp nhận vì
  product/event đọc nhiều hơn update nhiều phần.
- **N document cho order N item** (thay vì 1 doc order embed items) → tốn
  document hơn, nhưng phục vụ aggregation top-products `$group` theo từng item
  tự nhiên. Đây là **read-model analytics**, không phải order aggregate → tách
  ra là đúng cho query pattern report.
- **Không có ACID cross-document trong Mongo** → mọi nhu cầu đó đẩy về Postgres.
  Hy sinh: data Mongo không tham gia transaction chung với Postgres (dual-write
  drift, sửa bằng reconcile — như ES Day 22).

---

## 9. Related

- Code: [`OrderEventConsumer`](../../services/analytics-service/src/main/java/com/ecom/analytics/ingest/OrderEventConsumer.java), [`ProductCatalogDocument`](../../services/product-service/src/main/java/com/ecom/product/catalog/ProductCatalogDocument.java), [`MongoTestcontainerConfig`](../../services/analytics-service/src/test/java/com/ecom/analytics/support/MongoTestcontainerConfig.java)
- Docs: [lesson 23](../lessons/23-mongodb-when-to-use.md), [lesson 23b](../lessons/23b-document-vs-relational-modeling.md), [ADR-011](../decisions/011-mongo-for-analytics-and-flexible-attributes.md)
- Tiền đề: [lesson 06 aggregate-root](../lessons/06-aggregate-root.md) (aggregate boundary), [lesson 04b transaction-isolation](../lessons/04b-transaction-isolation.md) (ACID Postgres), [issue 13 dual-write](../issues/13-order-paid-inventory-not-reserved.md)
