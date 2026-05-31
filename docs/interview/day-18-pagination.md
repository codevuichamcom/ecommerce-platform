# Interview · Day 18 — Pagination at scale (offset → keyset/seek)

> **Status**: ✅ Done · 2026-05-31
> Bối cảnh giả lập + 5 Q&A senior level + AI Playbook.

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — series A ecommerce VN, catalog đã 1M+ SKU (sau Day 16 import seller data), mobile app vừa launch infinite-scroll feed.
- **Role giao việc**: Anh Hùng — Tech Lead. "Mobile feed timeout khi user kéo sâu, gateway 504. Đừng chỉ giới hạn page — feed phải kéo được vô hạn. Admin web thì vẫn cần page number, đừng phá."
- **Bạn**: Backend owner `product-service` — own read path của catalog list.
- **Reviewer**: Anh Hùng, soi: "cursor có lộ id thô không? sort động thì sao? endpoint cũ có vỡ không?"
- **Deadline**: 1 sprint, demo: mở "page 49000" không timeout + EXPLAIN before/after tại retro.
- **Constraint thực tế**:
  - Mobile infinite-scroll (next-only) — không cần jump-to-page.
  - Admin back-office cần "page 5 / 100" + total → KHÔNG được phá endpoint offset.
  - Không thêm infra mới (ES để Day 22).
- **Definition of Done**:
  - Deep page latency phẳng (đo EXPLAIN, Buffers không tăng theo độ sâu).
  - Cursor opaque, token rác → 400 không 500.
  - Offset endpoint còn chạy + có cap chống deep-scan abuse.
  - Doc benchmark offset vs keyset.

---

## Q1 — "Page 50000 chậm 5s. Fix sao?"

**Strong answer**: Vấn đề là `OFFSET`. `LIMIT 20 OFFSET 1_000_000` không seek
tới row thứ 1M — Postgres **đọc + đếm + discard** 1M rows theo `ORDER BY` rồi
mới emit 20. Cost tuyến tính theo độ sâu, index không cứu được (index chỉ bỏ
qua *sort*, không bỏ qua *duyệt*).

Fix: chuyển sang **keyset (seek)**. Index composite `(created_at DESC, id DESC)`.
Query đổi từ `LIMIT 20 OFFSET 1_000_000` sang:

```sql
WHERE (created_at, id) < (:last_at, :last_id)
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

Cursor `(created_at, id)` lấy từ row cuối page trước. Cost ≈ hằng số mọi độ sâu.

**Follow-up trap** — *"Sao không tăng `LIMIT 1000` cho đỡ phải paginate?"* →
vẫn không scale: memory + network payload + frontend render đều tăng tuyến
tính. Pagination khắc phục cả ba; tăng limit chỉ dời điểm gãy.

## Q2 — "Tại sao keyset cần `(created_at, id)` chứ không chỉ `created_at`?"

**Strong answer**: `created_at` **không unique** — bulk import / seed tạo nhiều
row trùng timestamp tới micro. Cursor chỉ mang `created_at` thì:
- `WHERE created_at < $at` → **bỏ sót** các row trùng `$at` chưa hiện.
- `WHERE created_at <= $at` → **lặp** row đã hiện ở page trước.

Không có toán tử đúng với 1 cột non-unique. Phải composite `(created_at, id)`
để mỗi row có **total order** — `id` unique làm tie-break. Đây là cạm bẫy #1
của keyset, lỗi chỉ lộ trên data thật (trùng timestamp), không lộ khi test 5 row.

**Follow-up trap** — *"Dùng `id` (UUID) một mình làm cursor được không?"* →
không, vì sort nghiệp vụ là `created_at`, không phải `id`. UUID v4 random
không phản ánh thứ tự thời gian. Cursor phải mang **đúng các cột trong ORDER BY**.

## Q3 — "Keyset mất `total` và jump-to-page. Vẫn muốn hiện '1.2M kết quả' thì sao?"

**Strong answer**: `COUNT(*)` trên 1M+ cũng chậm — đó là lý do keyset bỏ nó
khỏi hot path. Muốn hiện số gần đúng thì lấy **approximate count** từ
`pg_class.reltuples` (planner statistics, cập nhật bởi ANALYZE) hoặc đếm cache
TTL ngắn — KHÔNG `COUNT(*)` mỗi request. Jump-to-page thì keyset không cho được
về bản chất (không biết offset của cursor) → UX phải là infinite-scroll. Nếu
nghiệp vụ *bắt buộc* page number (kế toán, export) → giữ offset + cap độ sâu.

**Follow-up trap** — *"Hybrid: keyset nhưng cho nhảy ±5 page?"* → được, vì
"trang kế" chỉ cần cursor + LIMIT n×size; nhưng nhảy tới page tuỳ ý vẫn quay về
offset. Đừng hứa jump-to-arbitrary-page trên keyset.

## Q4 — "Cursor nên encode gì? Có nên lộ DB id thô không?"

**Strong answer**: Cursor là **opaque token** — encode `(created_at, id)` qua
base64 (URL-safe), client chỉ echo lại chứ không parse. Lý do:
- Không lộ id thô / không gợi ý enumerate.
- Đổi cursor structure sau (thêm sort field) không vỡ contract — client không
  phụ thuộc format.

Có cần **ký (HMAC)** không? Tuỳ scope: list product **public** → không cần,
tamper cursor chỉ khiến user thấy data khác, không phải lỗ hổng. Nhưng cursor
**scope theo user** (vd "đơn của tôi") → phải ký, nếu không user sửa cursor để
đọc data người khác (IDOR). Ở ShopVN list product public nên để opaque, chưa ký.

**Follow-up trap** — *"Encode dùng millis cho gọn?"* → sai: Postgres
`TIMESTAMPTZ` giữ **micro**second. Encode millis → mất 3 chữ số → cursor không
khớp chính xác giá trị DB → lặp/skip row ở biên. Phải encode tới micro.

## Q5 — "Sort động (price, popularity) + keyset?"

**Strong answer**: Mỗi sort là một **total order khác** → cần:
- 1 composite index riêng cho mỗi sort: `(price, id)`, `(popularity, id)`, ...
- Cursor encode **đúng các cột của sort đó** (cursor cho sort-by-price mang
  `(price, id)`, không phải `(created_at, id)`).

Mỗi sort thêm = thêm index (write cost) + thêm nhánh code. Nên giới hạn số sort
hỗ trợ keyset. Khi cần nhiều sort + relevance + facet → đó là lúc đẩy sang
**Elasticsearch `search_after`** (Day 22) thay vì nuôi N index trong Postgres.

**Follow-up trap** — *"Sort theo cột non-unique như `price` thì tie-break sao?"*
→ vẫn `(price, id)`: price trùng nhiều → id tie-break đảm bảo total order. Mọi
keyset sort đều phải kết thúc bằng một cột unique.

---

## 🧠 Senior mindset notes

- **Offset không xấu — sai chỗ mới xấu.** Admin table 10K rows, jump-to-page:
  offset hoàn hảo. Đừng cargo-cult keyset cho mọi list. Chọn theo cardinality +
  UX (infinite-scroll → keyset, page-number → offset).
- **p99 mới lộ deep-page.** p50/trung bình bị "che" vì đa số request là page 0.
  Alert phải theo p99 per-endpoint.
- **Giữ cả hai endpoint** thay vì thay thế cứng — đừng phá contract admin để
  fix mobile. Hai nhu cầu khác nhau, hai path.

## 🤖 AI Playbook

- **Giao AI**: generate boilerplate cursor encode/decode, JPQL expand row-value,
  parse EXPLAIN output, viết unit test round-trip. Đây là phần cơ học AI làm nhanh.
- **Prompt mẫu**:
  > "Viết keyset pagination JPQL cho Product, sort created_at DESC tie-break id
  > DESC, cursor là (Instant, UUID), fetch size+1 để biết hasNext. Expand
  > row-value vì JPQL không hỗ trợ tuple compare."
- **Risk khi để AI làm**: (1) quên tie-break → mất/lặp row trùng timestamp;
  (2) quên `size+1` → hasNext sai/luôn true; (3) encode millis thay micro →
  lệch cursor; (4) leak id thô trong cursor.
- **Validate**: EXPLAIN deep page **không có Sort node** + Buffers phẳng; test 2
  row cùng timestamp không mất ở ranh giới page; decode token rác → 400; so
  page cuối của offset vs keyset ra cùng tập row.
