# 📚 Lesson 17 — JPA fetch strategies: EntityGraph vs JOIN FETCH vs Projection

> **Day 17** · Related: [issue 17 — N+1](../issues/17-jpa-n-plus-one.md) · [issue 03 — open-in-view](../issues/03-entity-leak-in-response.md) · [perf 16](../performance/16-sql-explain-analyze.md)

---

## 🎯 TL;DR

- **N+1** = load collection theo từng-root thay vì theo-batch/join. 1 query lấy N parent + N query lấy con = 1+N round-trip.
- `FetchType` (LAZY/EAGER) quyết định **KHI NÀO** load, KHÔNG quyết định **CÁCH** load. EAGER trên list = N+1 cưỡng bức, không tắt được.
- 3 công cụ fix, 3 ngữ cảnh:
  - **`@EntityGraph`** — ép JOIN FETCH cho 1 query khi cần **full aggregate**. ⚠️ chết với pagination collection.
  - **`JOIN FETCH`** — control thủ công. ⚠️ `MultipleBagFetchException` + không phân trang bag.
  - **Projection DTO** — read-only, nhẹ nhất, **production list path**.

## ✅ Khi nào dùng cái nào

| Tình huống | Dùng | Vì sao |
| ---------- | ---- | ------ |
| List screen, read-only, cần ít cột | **Projection DTO** | 1 query scalar, không load entity, pagination ở DB |
| Detail 1 entity + cần full graph (items, behavior) | **`@EntityGraph`** hoặc JOIN FETCH | 1 order → 1 query JOIN, không có vấn đề pagination |
| Cần aggregate để **mutate** (write path) | Load entity (LAZY + touch khi cần) | Phải vào persistence context để dirty-checking |
| Nhiều collection cùng lúc, data nhỏ | JOIN FETCH (đổi Set) hoặc multi-query | tránh cartesian/MBFE |
| Collection lớn, lazy, truy cập 1 phần | **LAZY + `@BatchSize`** | gom IN(...) giảm 1+N → 1+N/batch |

## 🚫 Khi nào KHÔNG dùng

- ❌ KHÔNG `@EntityGraph`/`JOIN FETCH` + `Pageable` trên **collection** → in-memory pagination (`HHH000104`), OOM risk khi data lớn.
- ❌ KHÔNG `FetchType.EAGER` cho collection mặc định — nó ép N+1 trên mọi query list và bạn không tắt được per-query. (Order hiện để EAGER vì Day 6 chỉ load 1 order/lần; Day 17 list lộ ra điểm yếu này → đó là lý do dùng projection thay vì sửa mapping.)
- ❌ KHÔNG load full aggregate cho màn list chỉ để đếm/hiển thị tổng.

## ⚠️ Cạm bẫy

### 1. `HHH000104: firstResult/maxResults specified with collection fetch; applying in memory!`

JOIN FETCH 1 collection + `Pageable` → SQL **không có** `LIMIT`. Hibernate kéo TẤT CẢ row khớp về JVM rồi phân trang trong RAM. Data nhỏ thì "chạy được"; 100K row thì OOM. Đây chỉ là **WARN**, không phải lỗi → rất dễ lọt review.

> 💡 **Senior vs junior**: junior thấy "fix xong, 1 query, phân trang vẫn ra đúng page" → merge. Senior đọc log thấy `HHH000104` → biết pagination đang chạy in-memory → từ chối. Fix: 2-step (query page id trước → JOIN FETCH theo id list), hoặc dùng projection.

### 2. `MultipleBagFetchException: cannot simultaneously fetch multiple bags`

`List` không có `@OrderColumn` = **bag**. JOIN FETCH ≥2 bag cùng lúc → Hibernate không dựng nổi cartesian product → throw lúc bootstrap.

Fix: (a) đổi `List`→`Set` (Set không phải bag), hoặc (b) tách thành nhiều query (`@EntityGraph` từng cái / fetch tuần tự). Đổi Set vẫn để lại **cartesian product blow-up** (N×M row) nếu 2 collection lớn → khi đó multi-query tốt hơn.

### 3. `LazyInitializationException` vs N+1 — `open-in-view`

`open-in-view: true` (default Spring Boot, **đã tắt từ Day 3**) giữ session mở tới lúc render view → lazy access lúc serialize JSON **âm thầm** bắn N+1, không lỗi. Tắt `open-in-view: false` → lazy access ngoài tx **fail-fast** `LazyInitializationException`. Nghe có vẻ tệ hơn nhưng **tốt hơn**: lỗi nổ ngay lúc dev thay vì N+1 ẩn lên prod.

### 4. Constructor expression phải đúng package + đúng kiểu

`select new com.full.Package.Dto(...)` — sai 1 ký tự package → lỗi runtime lúc bootstrap, không phải compile. `size(o.items)` trả `long` (COUNT) → field DTO phải nhận được long.

## 🔬 Approaches compared (đo query thật)

Seed 5 order × 3 item, đếm `Statistics.getPrepareStatementCount()`:

| Nấc | Code | Query | Pagination DB | Load entity? |
| --- | ---- | ----- | ------------- | ------------ |
| 0 derived (EAGER) | `findByUserId(id, page)` | **≥ 6 (1+N)** | ✅ | full |
| 1 `@EntityGraph` | `@EntityGraph("items")` | 1 (+1 count) | ❌ in-memory | full |
| 2 JOIN FETCH | `join fetch o.items` | 1 | ❌ no page | full |
| 3 projection | `new OrderSummaryView(...)` | **≤ 2** | ✅ | không |

→ Code: [OrderRepository.java](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java). Demo live: `GET /debug/orders/n-plus-one?userId=...` (gated `app.debug.explain.enabled=true`).

## 🎤 Trả lời phỏng vấn

**Q: N+1 là gì, vì sao EAGER không cứu?**
N+1 là load N collection con bằng N query riêng sau 1 query parent. EAGER chỉ đổi *thời điểm* load (ngay), không đổi *cách* (vẫn per-root). Trên query trả nhiều root, EAGER **ép** N+1 và không cho tắt per-query → tệ hơn LAZY (lazy ít ra còn skip được khi không chạm).

**Q: Fix bằng gì?**
Tùy access pattern: list read-only → projection DTO (nhẹ, pagination ở DB); detail cần full graph → `@EntityGraph`/JOIN FETCH; lazy collection lớn → `@BatchSize`. Tránh JOIN FETCH + Pageable trên collection (in-memory pagination).

**Q: JOIN FETCH 2 list cùng lúc?**
`MultipleBagFetchException`. Đổi Set hoặc tách query. Đổi Set vẫn cẩn thận cartesian blow-up.

## 🔗 Related

- [issue 17 — N+1 trang đơn hàng](../issues/17-jpa-n-plus-one.md)
- [issue 03 — entity leak + open-in-view false](../issues/03-entity-leak-in-response.md)
- [perf 16 — EXPLAIN ANALYZE](../performance/16-sql-explain-analyze.md)
- [interview day 17](../interview/day-17-n-plus-one.md)
- Code: [OrderRepository](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java) · [OrderSummaryView](../../services/order-service/src/main/java/com/ecommerce/order/application/dto/OrderSummaryView.java)
