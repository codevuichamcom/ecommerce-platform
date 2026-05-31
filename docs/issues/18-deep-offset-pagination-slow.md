# 🔥 Issue 18 — Deep offset pagination timeout ở mobile feed

> **Day 18.** Sev-2: mobile infinite-scroll timeout khi user kéo sâu. Liên
> quan [performance/18-seek-pagination.md](../performance/18-seek-pagination.md)
> (benchmark) + [lessons/03-pagination-offset-vs-cursor.md](../lessons/03-pagination-offset-vs-cursor.md).

## 1. Problem

Mobile app dùng `GET /products?page=N&size=20` cho infinite-scroll. Power user
(và crawler) kéo tới page ~49000 → request mất 2-8s rồi gateway timeout 504.
P99 của toàn endpoint `/products` cũng phình theo vì các request deep page
chiếm connection pool lâu.

## 2. Symptoms

- Gateway log: `504 Gateway Timeout` cho `/products?page=49000&size=20`.
- Postgres `pg_stat_activity`: query `... ORDER BY created_at DESC LIMIT 20
  OFFSET 980000` ở trạng thái `active` 2-8s.
- APM p99 endpoint `/products` nhảy từ 45ms (Day 16) lên ~2.4s khi có traffic
  deep page; p50 vẫn ổn → bị "che" nếu chỉ nhìn trung bình.
- `EXPLAIN ANALYZE`: `actual rows=980020` ở Index Scan dưới `Limit` — đọc gần
  1M rows để trả 20.

## 3. Root cause

`OFFSET M` không phải "seek tới row thứ M". Postgres **đọc + đếm + discard** M
row đầu theo `ORDER BY` rồi mới emit `LIMIT`. Cost và Buffers tuyến tính theo
độ sâu M. Index `(created_at DESC, id)` của Day 3 vẫn được dùng — nhưng index
chỉ giúp **bỏ qua sort**, không giúp bỏ qua việc duyệt 980K entry. Deep page =
duyệt nhiều = chậm, bất kể index.

## 4. Approaches compared

| Approach | Pros | Cons |
| -------- | ---- | ---- |
| **Cap max page** (page ≤ 500) | 1 dòng code, chặn abuse ngay, giữ offset UX | Không *giải quyết* deep scroll — chỉ cấm. User thật cần xem sâu thì kẹt |
| **Keyset (seek) cursor** | O(N) ổn định mọi độ sâu, miễn page-drift, dùng index sẵn | Mất jump-to-page + mất total, đổi API sang cursor, sort cố định |
| **Offset + cached approximate count** | Giữ jump-to-page, hiện "~1.2M kết quả" | Count stale; vẫn KHÔNG fix deep-page chậm (chỉ fix count chậm) |
| **Search engine `search_after` (ES)** | Stable cursor + relevance scoring + facet | Phụ thuộc storage khác, sync Postgres→ES, overkill cho list theo thời gian |

## 5. Chosen approach + Why

**Keyset cursor cho path infinite-scroll** + **cap page cho path offset còn lại**
— kết hợp, không thay thế cứng.

Vì sao không chỉ cap page? Cap là *phòng thủ*, không phải *tính năng*: nó cấm
deep scroll chứ không cho phép. Mobile feed cần kéo vô hạn → phải keyset thật.
Vì sao giữ cả offset (đã cap)? Admin/back-office cần "page 5/100" + total để
đếm — keyset không cho. Hai nhu cầu khác nhau → hai endpoint:
`GET /products` (offset, cap 500) + `GET /products/keyset` (cursor).

Vì sao không ES ngay? List theo `created_at` không cần relevance/facet — ES là
búa quá to. Để dành Day 22 khi có search ngữ nghĩa.

## 6. Fix

- Repo: [ProductRepository.searchKeyset](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java)
  — JPQL expand `created_at < :at OR (created_at = :at AND id < :id)`,
  `ORDER BY created_at DESC, id DESC`, fetch `size+1` để dò `hasNext`.
- Cursor opaque: [ProductCursor](../../services/product-service/src/main/java/com/ecom/product/web/dto/ProductCursor.java)
  — base64 `(epochMicros:uuid)`, token rác → 400.
- Endpoint: [ProductController#searchKeyset](../../services/product-service/src/main/java/com/ecom/product/web/ProductController.java)
  `GET /products/keyset?cursor=&size=`; offset `search` thêm cap `page ≤ 500`.
- Index khớp seek: [V6__keyset_pagination_index.sql](../../services/product-service/src/main/resources/db/migration/V6__keyset_pagination_index.sql).

## 7. Prevention

- **Cap page ở offset endpoint** (`MAX_OFFSET_PAGE=500`) → 400 + hướng dùng
  keyset, chặn cả crawler lẫn vô tình.
- **Alert APM p99 theo endpoint** (không chỉ p50/trung bình) — deep-page spike
  chỉ lộ ở p99.
- **Debug compare endpoint** [DebugPaginationController](../../services/product-service/src/main/java/com/ecom/product/web/DebugPaginationController.java)
  để verify "không Sort node, Buffers phẳng" mỗi lần đổi index/sort.
- Unit test cursor codec ([ProductCursorTest](../../services/product-service/src/test/java/com/ecom/product/web/dto/ProductCursorTest.java))
  — round-trip micro precision + token rác fail-safe.

## 8. Trade-off accepted

Keyset endpoint **không có** jump-to-page và `total`. UX mobile đổi sang
infinite-scroll thuần (chấp nhận được — vốn đã infinite-scroll). Sort cố định
`created_at DESC` — sort động (price/popularity) cần thêm index + cursor riêng,
postpone tới khi có nhu cầu thật / đẩy ES. Cap page 500 cắt một nhúm use case
xem-sâu hợp lệ trên admin — đánh đổi lấy chặn deep-scan abuse.

## 9. Related

- Code: [ProductRepository](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java) · [ProductService](../../services/product-service/src/main/java/com/ecom/product/service/ProductService.java) · [ProductCursor](../../services/product-service/src/main/java/com/ecom/product/web/dto/ProductCursor.java)
- Docs: [performance/18-seek-pagination.md](../performance/18-seek-pagination.md) · [lessons/03-pagination-offset-vs-cursor.md](../lessons/03-pagination-offset-vs-cursor.md) · [interview/day-18-pagination.md](../interview/day-18-pagination.md)
- Tiền đề: [performance/16-sql-explain-analyze.md](../performance/16-sql-explain-analyze.md) (Day 16 — đọc EXPLAIN), [issues/17-jpa-n-plus-one.md](17-jpa-n-plus-one.md) (Day 17 — projection read model)
