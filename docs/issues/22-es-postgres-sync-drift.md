# 🔥 Issue 22 — Search ra sản phẩm đã xóa / giá sai (ES-Postgres sync drift)

> **Day 22** · Severity: 🟡 Sev-3 (degraded UX, không mất tiền trực tiếp)
> Liên quan: [lesson 22b — sync strategies](../lessons/22b-cdc-vs-app-sync-vs-debezium.md) ·
> [lesson 13 — outbox](../lessons/13-outbox-pattern.md) ·
> [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md)

---

## 1. Problem

Sau khi migrate search sang Elasticsearch (sync app-level qua Kafka), search bắt đầu
trả kết quả **lệch source of truth**: sản phẩm đã archive vẫn hiện trong search, giá
hiển thị là giá cũ trước lần update, hoặc sản phẩm vừa tạo không tìm thấy.

## 2. Symptoms

- Khách click product từ search → trang 404 (product đã archived nhưng ES còn doc).
- Giá ở search list khác giá ở trang chi tiết (`GET /products/{id}` từ Postgres đúng,
  search từ ES sai).
- Admin tạo product mới → 5 phút sau search vẫn không ra.
- `GET /admin/search/drift` trả `{"postgresActive": 48230, "elasticsearchDocs": 48198,
  "drift": 32}` — 32 doc lệch.
- Log: `Failed publish product.upserted productId=... — ES sẽ drift đến lần reconcile`
  vào đúng khung giờ Kafka rolling restart.

## 3. Root cause

**Dual-write problem** (xem [lesson 13b](../lessons/13b-dual-write-problem.md)): ghi 2
nơi (Postgres + Kafka) không atomic.

```
@Transactional create() { productRepository.save(product); }  // commit ✅
runAfterCommit(() -> publisher.publishUpserted(event));        // publish ❌ Kafka down
```

Khi Kafka không reachable lúc `afterCommit` (rolling restart, network blip), event
mất → `ProductIndexer` không bao giờ index → ES drift khỏi Postgres. Ngoài ra app
crash giữa DB-commit và afterCommit-callback (callback chạy in-memory, không bền) cũng
mất event.

Một nguồn drift thứ 2: **ordering**. Nếu `upserted` và `deleted` đi vào partition
khác nhau, `deleted` có thể được xử lý trước `upserted` → product "sống lại" trong
index.

## 4. Approaches compared

| Approach | Pros | Cons |
|---|---|---|
| **(A) Ignore + TTL/nightly reindex** | Đơn giản nhất; reindex ép ES = Postgres định kỳ | Drift tồn tại tới lần reindex (giờ→ngày); reindex 1M tốn tài nguyên |
| **(B) Outbox + relay** (như order Day 13) | Atomic với DB → không mất event; latency thấp (poll 1s) | Thêm bảng + scheduler vào catalog service; phức tạp hơn |
| **(C) Debezium CDC** | App không lo sync; bắt cả thay đổi SQL tay; không mất event | Ops nặng: Kafka Connect + replication slot + schema handling |
| **(D) Sync ghi thẳng ES trong request** (no Kafka) | Đọc-sau-ghi tức thì, không drift event | ES down = fail create product (coupling); không buffer/retry |

## 5. Chosen approach + Why

**Chọn (A) app-level dual-write + nightly reconcile** cho Day 22, với đường nâng cấp
sang (B) khi cần.

Lý do gắn context project:
- Search là **derived data non-critical**. Khác order (Day 13 phải outbox vì mất event
  = mất đơn = mất tiền). Search trả thiếu 32/48k doc trong vài giờ → UX hơi xấu, không
  mất tiền. "Eventually correct" chấp nhận được.
- (D) bị loại vì coupling: ES down KHÔNG được làm fail tạo product (Postgres là source
  of truth, phải ghi được kể cả khi search hỏng).
- (B)/(C) đúng nhưng **over-engineer ở volume hiện tại** (~50k product, vài chục
  update/ngày). Drift nhỏ + reconcile rẻ. Ghi rõ ngưỡng nâng cấp ở
  [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md): khi drift trung
  bình > 0.1% hoặc update rate cao → chuyển outbox.

## 6. Fix

**(1) Loại nguồn drift ordering** — key = `productId` cho cả 2 topic → cùng partition
→ `upserted`/`deleted` đúng thứ tự ([`ProductEventPublisher`](../../services/product-service/src/main/java/com/ecom/product/search/ProductEventPublisher.java),
[`TopicNames`](../../common-lib/src/main/java/com/ecom/common/messaging/TopicNames.java)):

```java
String key = event.productId().toString();
kafkaTemplate.send(TopicNames.PRODUCT_UPSERTED, key, event);
```

**(2) Publish sau commit** — loại event phantom khi transaction rollback
([`ProductService.runAfterCommit`](../../services/product-service/src/main/java/com/ecom/product/service/ProductService.java)).

**(3) Indexer idempotent** — `save()` ES là upsert by id, `deleteById` no-op nếu
không tồn tại → replay an toàn ([`ProductIndexer`](../../services/product-service/src/main/java/com/ecom/product/search/ProductIndexer.java)).

**(4) Reconcile path** — `GET /admin/search/drift` đo lệch, `POST /admin/search/reindex`
ép ES = Postgres ([`AdminSearchController`](../../services/product-service/src/main/java/com/ecom/product/web/AdminSearchController.java) +
[`ReindexService`](../../services/product-service/src/main/java/com/ecom/product/search/ReindexService.java)).

## 7. Prevention

- **Drift metric**: expose `drift` (Postgres ACTIVE count − ES doc count). Day 25 wire
  vào Micrometer + alert khi `|drift| > ngưỡng`.
- **Nightly reindex job** (cron) ép hội tụ — hiện trigger tay qua endpoint, Day 25
  schedule.
- **Test ordering**: integration test verify archived product KHÔNG search ra
  ([`ProductSearchIntegrationTest.fuzzy_typo_matchesIphone`](../../services/product-service/src/test/java/com/ecom/product/search/ProductSearchIntegrationTest.java) —
  assert iPhone 13 ARCHIVED noneMatch).
- **Alert publish failure**: log `Failed publish product.*` đẩy lên dashboard để biết
  pipeline trục trặc trước khi drift lớn.

## 8. Trade-off accepted

Chọn app-level dual-write nghĩa là **chấp nhận drift tạm thời** (cửa sổ giữa 2 lần
reconcile) để đổi lấy đơn giản (không bảng outbox, không Debezium ops). Hy sinh:
**strong consistency đọc-sau-ghi** của search. Nếu sau này search trở thành critical
(vd hiển thị tồn kho real-time để bán flash sale) → phải nâng outbox/CDC, vì lúc đó
drift = bán nhầm = mất tiền.

## 9. Related

- Code: [`ProductService`](../../services/product-service/src/main/java/com/ecom/product/service/ProductService.java) ·
  [`ProductEventPublisher`](../../services/product-service/src/main/java/com/ecom/product/search/ProductEventPublisher.java) ·
  [`ProductIndexer`](../../services/product-service/src/main/java/com/ecom/product/search/ProductIndexer.java) ·
  [`ReindexService`](../../services/product-service/src/main/java/com/ecom/product/search/ReindexService.java)
- Docs: [lesson 22b sync](../lessons/22b-cdc-vs-app-sync-vs-debezium.md) ·
  [lesson 13 outbox](../lessons/13-outbox-pattern.md) ·
  [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md)
