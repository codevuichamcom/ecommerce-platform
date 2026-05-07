# 📖 Lesson 03 — Pagination: Offset vs Cursor (Keyset)

> **Day 3 deliverable.** Day 18 sẽ migrate sang keyset cho dataset 10M+
> rows. Lesson này giải thích lý thuyết + trade-off để hiểu vì sao Day 3
> chấp nhận offset (đơn giản) còn Day 18 phải đổi.

## TL;DR

- **Offset pagination**: `LIMIT N OFFSET M`. Đơn giản, support jump-to-page.
  Nhược: page càng sâu càng chậm vì DB vẫn phải scan + discard `M` rows.
  Còn bị **page drift** khi có insert/delete giữa các request.
- **Cursor (keyset) pagination**: `WHERE (sort_col, id) < (last_value, last_id) ORDER BY ... LIMIT N`.
  Nhanh ổn định O(N) bất kể page sâu. Nhược: không jump-to-page, UX chỉ next/prev.

## Khi nào dùng OFFSET

- Admin table với dataset nhỏ (< 100k rows) — page drift không đau, dev đơn giản.
- UI bắt buộc có "page 5 / 100" navigation (số liệu, kế toán).
- Day 3 dùng cho `/products` MVP — chưa có 1M rows.

## Khi nào dùng CURSOR (keyset)

- Infinite scroll (mobile feed, product list public).
- Dataset > 1M rows hoặc page > 1000 → offset bắt đầu chậm rõ.
- Cần stable order khi data đang thay đổi (insert/delete liên tục).

## ⚠️ Cạm bẫy

1. **Offset chậm exponential ở page sâu**: `OFFSET 1_000_000` → Postgres
   vẫn scan 1M rows trước khi return 20 → P99 nhảy từ 5ms lên 5s.
2. **Page drift**: insert mới ở giữa 2 request → user thấy duplicate hoặc
   miss row khi scroll. Offset không cách nào fix sạch — keyset miễn nhiễm.
3. **Cursor cần composite key**: chỉ `created_at` không đủ — 2 row cùng
   millisecond sẽ bị skip 1. Phải dùng `(created_at, id)` để tie-break.
4. **Cursor + sort by user-chosen column**: nếu cho phép sort động (price,
   name, popularity) → mỗi sort phải có index riêng + cursor encode khác
   nhau. Mỗi sort thêm là cost.
5. **Encode cursor đừng leak DB id**: encode base64 / opaque token —
   client không nên thấy được cursor structure.

## 📊 Approaches compared

| Approach              | Pros                              | Cons                                        |
| --------------------- | --------------------------------- | ------------------------------------------- |
| Offset/Limit          | Đơn giản, jump-to-page, SQL chuẩn | Chậm O(M+N), page drift                     |
| Keyset (cursor)       | Stable, fast O(N), miễn drift     | Không jump-to-page, cần composite index     |
| Seek + cached count   | Có total count gần đúng           | Count cache stale, phức tạp invalidation    |
| Search engine cursor (ES `search_after`) | Stable + scoring | Phụ thuộc storage khác (ES) — Day 22+   |

## 🎤 Trả lời phỏng vấn

> "Page 50000 chậm — fix sao?"

**Strong answer**: chuyển từ offset → keyset. Index composite
`(created_at DESC, id)`. Query đổi từ `LIMIT 20 OFFSET 1_000_000` sang
`WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 20`.
Trade-off: mất jump-to-page (UX phải đổi sang infinite scroll). Nếu
bắt buộc giữ jump-to-page → cap tối đa N page (vd page ≤ 100), đẩy
deeper search qua filter/search engine.

> "Tại sao không tăng limit 1000 thay vì pagination?"

Trap câu hỏi: vẫn không scale. Memory tăng tuyến tính, network payload
to, frontend render lag. Pagination khắc phục cả 3.

## 🔗 Related

- Code: [`services/product-service/src/main/java/com/ecom/product/service/ProductService.java`](../../services/product-service/src/main/java/com/ecom/product/service/ProductService.java)
- Performance deep-dive (Day 18): `performance/18-seek-pagination.md` ⏳
- Migrate ES search (Day 22): `lessons/22-elasticsearch-basics.md` ⏳
- Issue về entity leak liên quan list endpoint: [`issues/03-entity-leak-in-response.md`](../issues/03-entity-leak-in-response.md)
- Interview: [`interview/day-03-product.md`](../interview/day-03-product.md)
