# 🔥 Issue 17 — Trang "Đơn hàng của tôi" 3.2s: N+1 query

> **Severity**: 🟡 Sev-3 (UX degradation, không downtime) · **Service**: order-service
> **Day**: 17 · **Related**: [lesson 17 fetch strategies](../lessons/17-jpa-fetch-strategies.md) · [perf 16 EXPLAIN](../performance/16-sql-explain-analyze.md)

---

## 1. Problem

Endpoint mới `GET /orders` (list "Đơn hàng của tôi") trả về sau **3.2s** khi user có ~40 đơn. APM báo **41 query** cho 1 request. List 40 đơn không nên tốn hơn 1-2 query.

## 2. Symptoms

- Datadog APM: 1 request `GET /orders` → **41 DB query**, trong đó 40 query gần như giống hệt nhau chỉ khác tham số `order_id`.
- Hibernate SQL log (bật `org.hibernate.SQL=DEBUG`):

```
select o.* from orders o where o.user_id = ? limit ?      -- 1 query lấy page order
select i.* from order_items i where i.order_id = ?         -- lặp lại 40 lần, mỗi order 1 lần
select i.* from order_items i where i.order_id = ?
... (× 40)
```

- Latency tỉ lệ thuận số đơn: user 5 đơn → ~400ms; user 40 đơn → 3.2s. Đây là dấu hiệu kinh điển của **N+1**: cost = O(N) round-trip, không phải O(1).

## 3. Root cause

[Order.java:121](../../services/order-service/src/main/java/com/ecommerce/order/domain/Order.java#L121) map collection items là `@OneToMany(fetch = FetchType.EAGER)`:

```java
@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
@JoinColumn(name = "order_id", ...)
private List<OrderItem> items = new ArrayList<>();
```

`EAGER` quyết định **KHI NÀO** load (ngay lập tức), nhưng KHÔNG quyết định **CÁCH** load. Khi query trả về **nhiều** root entity (list 40 order), Hibernate:
1. Chạy query chính lấy 40 order.
2. Vì items là EAGER, với MỖI order Hibernate bắn 1 query phụ load collection → 40 query.

Tổng = **1 + N**. Đây là nghịch lý: `EAGER` không cứu N+1, nó còn **ép** N+1 xảy ra mà không tắt được (lazy ít ra còn skip được nếu không truy cập).

> ⚠️ N+1 không sinh từ "lazy". Nó sinh từ **load collection theo từng-root** thay vì theo-batch/join. Lazy collection truy cập trong loop cũng ra y hệt.

## 4. Approaches compared

| Approach | Pros | Cons |
| -------- | ---- | ---- |
| **A. Đổi sang `LAZY` + `@BatchSize`** | 1 + N/batch query (vd batch=20 → 1+2); ít đổi code | Vẫn nhiều hơn 1 query; phải nhớ set batch; vẫn load full entity vào persistence context (nặng cho read) |
| **B. `@EntityGraph(items)`** | 1 query JOIN FETCH; load đủ aggregate | Collection fetch + `Pageable` → `HHH000104` **in-memory pagination** (kéo hết về JVM rồi mới phân trang → OOM risk); cartesian product nếu nhiều collection |
| **C. `JOIN FETCH` viết tay** | 1 query; control rõ | Không phân trang ở DB được khi fetch bag; ≥2 bag → `MultipleBagFetchException`; vẫn load full entity |
| **D. Projection DTO (constructor expression)** ✅ | 1 query select scalar + 1 count; KHÔNG load entity/persistence context; pagination chạy ở DB (LIMIT/OFFSET); nhẹ heap+CPU | Phải maintain DTO/view riêng; mất lazy navigation; 2 finder cho 2 mục đích (read vs write) |

## 5. Chosen approach + Why

**Chọn D (projection DTO) cho list path; giữ B (`@EntityGraph`) cho detail/aggregate path.**

Lý do gắn context ShopVN:
- Màn "Đơn của tôi" là **read-heavy, read-only**: chỉ cần `status + total + số item + ngày`, KHÔNG cần từng `OrderItem`, KHÔNG cần behavior aggregate. Load cả aggregate (option A/B/C) là lãng phí — vừa N+1 (A), vừa in-memory pagination (B), vừa không phân trang (C).
- Projection select thẳng scalar + `size(o.items)` (subquery COUNT) → **1 query chính + 1 count query**, pagination LIMIT/OFFSET chạy ở DB. Không entity vào persistence context → không dirty-checking, nhẹ.
- Detail path (`GET /orders/{id}`) vẫn cần full aggregate (items, status data) → ở đó dùng `@EntityGraph` hoặc load aggregate bình thường (1 order → JOIN FETCH 1 query, không có vấn đề pagination).

Đây là **CQRS-lite**: read model (`OrderSummaryView`) tách write model (`Order` aggregate). Không phải over-engineer — chỉ là không ép 1 model phục vụ 2 access pattern khác nhau.

## 6. Fix

**Repository** ([OrderRepository.java](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java)) — nấc 3 production:

```java
@Query("""
        select new com.ecommerce.order.application.dto.OrderSummaryView(
            o.id, o.statusType, o.total.amount, o.total.currency,
            o.reservationStatus, o.placedAt, size(o.items))
        from Order o
        where o.userId = :userId
        """)
Page<OrderSummaryView> findSummariesByUserId(@Param("userId") UUID userId, Pageable pageable);
```

**Service** ([OrderQueryService.java](../../services/order-service/src/main/java/com/ecommerce/order/application/OrderQueryService.java)) — `listMyOrders()` dùng projection + sort whitelist (`placedAt | totalAmount`) + size cap 100.

**Controller** ([OrderController.java](../../services/order-service/src/main/java/com/ecommerce/order/interfaces/rest/OrderController.java)) — `GET /orders` paginated, scope theo `userId` của token.

Repository giữ cả 4 nấc (0 derived / 1 EntityGraph / 2 JOIN FETCH / 3 projection) để [DebugController](../../services/order-service/src/main/java/com/ecommerce/order/interfaces/rest/DebugController.java) `GET /debug/orders/n-plus-one` chạy side-by-side đếm query.

**Kết quả** (đo bằng Hibernate `Statistics.getPrepareStatementCount()`):

| Nấc | Query (5 order × 3 item) | Pagination ở DB? |
| --- | ------------------------ | ---------------- |
| 0 derived EAGER | ≥ 6 (1 + N) | ✅ nhưng N+1 |
| 1 `@EntityGraph` | 1 (+1 count) | ❌ in-memory (HHH000104) |
| 2 JOIN FETCH | 1 | ❌ không phân trang |
| 3 projection | ≤ 2 (select + count) | ✅ |

41 query → **2 query**, latency 3.2s → ~30ms.

## 7. Prevention

- **Test đếm query**: [OrderNPlusOneIntegrationTest](../../services/order-service/src/test/java/com/ecommerce/order/OrderNPlusOneIntegrationTest.java) assert `getPrepareStatementCount() ≤ 2` cho projection, `≥ 1+N` cho nấc 0 (lock behavior để regression lộ ra). Gated `RUN_ORDER_INTEGRATION_TESTS=true`.
- **`open-in-view: false`** (đã set Day 3 cho mọi service): tắt session ở view layer → lazy access ngoài tx **fail-fast** `LazyInitializationException` thay vì âm thầm N+1 trong lúc serialize JSON.
- **Bật `hibernate.generate_statistics=true` ở staging** + alert khi query/request vượt ngưỡng (vd >5).
- **Code review checklist**: thấy `List<Order>`/`Page<Order>` trả ra cho list screen → hỏi ngay "có chạm collection không?".

## 8. Trade-off accepted

- **Maintain 2 model**: `OrderSummaryView` (read) + `Order` (write) phải sync field khi schema đổi. Chấp nhận vì read/write access pattern thật sự khác nhau — ép 1 model là nguồn của N+1.
- **Mất lazy navigation trên list**: từ `OrderSummaryView` không `.getItems()` được. Đúng ý đồ — list không cần item detail; ai cần thì gọi `GET /orders/{id}`.
- **`size(o.items)` subquery COUNT**: thêm 1 correlated subquery cho mỗi row. Rẻ (có index `order_items(order_id)`), nhưng không free. Nếu cần tối ưu cực đại → denormalize cột `item_count` trên `orders` (postpone, chưa cần).

## 9. Related

- **Code**: [OrderRepository.java](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java) · [OrderQueryService.java](../../services/order-service/src/main/java/com/ecommerce/order/application/OrderQueryService.java) · [OrderSummaryView.java](../../services/order-service/src/main/java/com/ecommerce/order/application/dto/OrderSummaryView.java) · [NPlusOneDemoService.java](../../services/order-service/src/main/java/com/ecommerce/order/interfaces/rest/NPlusOneDemoService.java)
- **Test**: [OrderNPlusOneIntegrationTest.java](../../services/order-service/src/test/java/com/ecommerce/order/OrderNPlusOneIntegrationTest.java)
- **Docs**: [lesson 17 — fetch strategies](../lessons/17-jpa-fetch-strategies.md) · [interview day 17](../interview/day-17-n-plus-one.md) · [issue 03 — entity leak / open-in-view](03-entity-leak-in-response.md) · [perf 16 — EXPLAIN ANALYZE](../performance/16-sql-explain-analyze.md)
