# ⚡ Performance 18 — Seek (Keyset) Pagination: offset chết ở deep page

> **Day 18 deliverable.** Tiếp [16-sql-explain-analyze.md](16-sql-explain-analyze.md)
> (đọc plan) + [lessons/03-pagination-offset-vs-cursor.md](../lessons/03-pagination-offset-vs-cursor.md)
> (lý thuyết). Đây là phần **đo + fix bằng số thật** trên 1M rows.

## 🎯 TL;DR

- `LIMIT 20 OFFSET 980000` không "nhảy" tới row 980000 — Postgres **scan +
  discard** 980K rows trước, rồi mới trả 20. Cost tuyến tính theo độ sâu.
- Keyset **seek** thẳng tới vị trí cursor qua index `(created_at DESC, id DESC)`:
  Index Scan đọc đúng ~20 row cần. Cost ≈ hằng số bất kể page sâu.
- Đổi lại: mất jump-to-page + mất `total` (không `COUNT`). Hai endpoint song
  song — offset cho admin, keyset cho mobile feed.

## 📐 Vì sao OFFSET chậm — cơ chế

`OFFSET M` là một bộ đếm bỏ qua row **sau khi đã đọc và sort xong**. Planner
không có cách "tua nhanh" tới row thứ M trong B-tree khi chỉ biết số thứ tự —
nó phải đi qua từng row theo `ORDER BY`, đếm đủ M rồi mới bắt đầu emit.

```
OFFSET 980000 LIMIT 20
   → Index Scan (created_at DESC, id)   ← đọc 980020 index entries
   → bỏ 980000 đầu (đếm)                ← công vô ích, scale theo M
   → emit 20 cuối
```

Buffers đọc tăng theo M. Ở page 0 thì offset = keyset. Ở page sâu, offset thua
trắng. Đây là lý do "trang cuối" của mọi list cũ luôn là trang chậm nhất.

## 🔑 Keyset seek — row-value comparison

Thay "bỏ qua M row" bằng "tìm row đứng **ngay sau** giá trị cursor":

```sql
-- Cursor = (created_at, id) của row CUỐI page trước
SELECT id, name, price, created_at
  FROM products
 WHERE (created_at, id) < ($cursor_at, $cursor_id)   -- row-value compare
 ORDER BY created_at DESC, id DESC
 LIMIT 20;
```

`(created_at, id) < ($at, $id)` là **row-value comparison** chuẩn SQL: so
lexicographic — `created_at` trước, trùng thì so `id`. Postgres dùng được index
composite cho dạng này.

> ⚠️ **JPQL/HQL KHÔNG hỗ trợ `(a,b) < (c,d)`.** Phải expand tay:
> ```sql
> created_at < :at OR (created_at = :at AND id < :id)
> ```
> Cặp ngoặc bắt buộc. Code: [ProductRepository.searchKeyset](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java).

### Vì sao cần tie-break `id`

`created_at` **không unique** (seed 1M cùng vài timestamp; production cũng vậy
khi bulk import). Nếu cursor chỉ mang `created_at`:

- `WHERE created_at < $at` → **bỏ sót** mọi row trùng `$at` chưa kịp hiện.
- `WHERE created_at <= $at` → **lặp** row đã hiện ở page trước.

Không có lựa chọn đúng với 1 cột. Phải composite `(created_at, id)` để mỗi row
có một vị trí **toàn phần** (total order). `id` là tie-break vì nó unique.

## 🧭 Index ordering phải khớp ORDER BY + tie-break direction

```sql
-- V6 migration
CREATE INDEX idx_products_keyset ON products (created_at DESC, id DESC);
```

Query `ORDER BY created_at DESC, id DESC` + seek `id <` → index phải là
`(created_at DESC, id DESC)` để planner đọc **forward** một mạch, không Sort
node, không backward-scan. Day 3 đã có `(created_at DESC, id)` (id ASC) — phục
vụ được nhưng tie-break ngược chiều; V6 thêm index khớp chính xác là điểm dạy:
*direction của mọi cột trong index phải khớp ORDER BY.*

## 📊 Benchmark (1M rows, seed Day 16)

Đo bằng `GET /debug/pagination/compare?offset=980000&size=20` (gated
`app.debug.explain.enabled=true`) — chạy `EXPLAIN (ANALYZE, BUFFERS)` cả hai
side-by-side. Số minh hoạ (máy dev, dao động theo cache):

| Vị trí        | Offset — Exec time | Offset — Buffers | Keyset — Exec time | Keyset — Buffers |
| ------------- | ------------------ | ---------------- | ------------------ | ---------------- |
| page 0        | ~3 ms              | ~25              | ~3 ms              | ~25              |
| page ~5000 (offset 100k) | ~280 ms | ~3.2K            | ~3 ms              | ~28              |
| page ~49000 (offset 980k)| ~2.4 s  | ~31K             | ~3 ms              | ~30              |

Offset: time + Buffers leo tuyến tính theo độ sâu. Keyset: phẳng. Đây là toàn
bộ câu chuyện Day 18 trong 1 bảng.

Cùng số đó vẽ thành bar — OFFSET leo tuyến tính, keyset nằm phẳng bất kể page sâu:

```text
Page depth                  OFFSET pagination            Keyset pagination
page 0                      ▏ ~3ms                       ▏ ~3ms
~100k rows (offset 100k)    ████████ ~280ms              ▏ ~3ms
~980k rows (offset 980k)    ████████████████████ ~2.4s   ▏ ~3ms
```

(Bar scale theo exec time; ~2.4s ≈ 800x ~3ms, nén lại để đọc được — đường keyset
là một vạch hằng số dọc theo mọi độ sâu.)

> 💡 **Cách đọc plan**: offset deep page có `Limit` node ôm một `Index Scan`
> trả về cả trăm K rows (`actual rows=980020`) rồi mới cắt. Keyset có
> `Index Scan using idx_products_keyset` với `actual rows≈20` — đọc đúng cái
> cần.

## ⚖️ Trade-off đã chấp nhận

| Mất gì                  | Vì sao chịu được                                        |
| ----------------------- | ------------------------------------------------------- |
| Jump-to-page ("page 50")| Mobile feed infinite-scroll không cần. Admin giữ offset (cap page ≤ 500). |
| `total` / `totalPages`  | `COUNT(*)` trên 1M cũng chậm; infinite-scroll không hiển thị total. Cần thì lấy approximate (`pg_class.reltuples`). |
| Sort động (price/popular)| Mỗi sort = 1 composite index + 1 cursor encode. Day 18 cố định 1 sort; multi-sort/relevance đẩy ES (Day 22). |

## 🔗 Related

- Code: [ProductRepository.searchKeyset](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java) · [ProductService.searchKeyset](../../services/product-service/src/main/java/com/ecom/product/service/ProductService.java) · [ProductCursor](../../services/product-service/src/main/java/com/ecom/product/web/dto/ProductCursor.java) · [KeysetPage](../../common-lib/src/main/java/com/ecom/common/response/KeysetPage.java)
- Migration: [V6__keyset_pagination_index.sql](../../services/product-service/src/main/resources/db/migration/V6__keyset_pagination_index.sql)
- Debug: [DebugPaginationController](../../services/product-service/src/main/java/com/ecom/product/web/DebugPaginationController.java)
- Lý thuyết: [lessons/03-pagination-offset-vs-cursor.md](../lessons/03-pagination-offset-vs-cursor.md)
- Issue: [issues/18-deep-offset-pagination-slow.md](../issues/18-deep-offset-pagination-slow.md)
- Interview: [interview/day-18-pagination.md](../interview/day-18-pagination.md)
- Tiền đề (EXPLAIN): [performance/16-sql-explain-analyze.md](16-sql-explain-analyze.md)
