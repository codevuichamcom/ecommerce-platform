# Chương 16 · 🔬 Kính hiển vi

**Day 16 — Slow query tuning (EXPLAIN ANALYZE + indexes)**

---

> *"Bộ nhớ giúp ta đi nhanh hơn. Nhưng nó không thay được kính hiển vi — thứ duy nhất cho ta thấy DB đang nghĩ gì."*

---

> 🎬 **Chương này có gì:** một sản phẩm seller marketplace gấp 24 lần catalog qua đêm; một câu `LIKE '%iphone%'` ngoan ngoãn hôm trước, hôm sau biến thành kẻ giết server; một cái kính hiển vi tên là `EXPLAIN ANALYZE` chiếu vào ruột planner; và một extension Postgres ba chữ thay đổi cuộc chơi: **pg_trgm**. 🔬

---

## 🎬 Bối cảnh: anh Hùng forward email lúc 7h sáng

Cache vừa được lắp tuần trước. Tonny còn đang cảm thấy "phòng bộ nhớ" mới mẻ thì Slack ping:

> 🗣️ *"Search box p99 lên 2.5s từ hôm import seller data. CS ngập complaint. Tìm root cause. Đừng nhảy bổ Elasticsearch — đó là Q3."* 📅

Catalog vừa nhảy từ **50K → 1.2M SKU** sau khi merge marketplace. Cache (Day 15) vẫn xanh — 95% hit ratio cho `/products/{id}`. Nhưng search? Search không cache. Mỗi user gõ khác nhau, key entropy cao chót vót, Day 15 đã cố ý loại nó ra.

Cache không cứu được tất cả. Có những vấn đề không nằm ở **độ xa của bộ nhớ**. Chúng nằm ở **hình dạng của câu hỏi**.

---

## 🔎 Câu hỏi đầu tiên — DB đang nghĩ gì?

Khi backend chậm, junior nhìn code app. Senior nhìn DB.

Và để nhìn được DB, có một câu lệnh mà mọi senior backend đều phải thuộc:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) <query>;
```

Tonny chạy nó trên query thật. Output xổ ra:

```text
Limit  (cost=58234.10..58236.45 rows=20 width=180) (actual time=2487.193..2487.201)
  ->  Sort  (cost=58234.10..58423.55 rows=75780)
        Sort Key: created_at DESC
        ->  Seq Scan on products p  (actual time=0.412..2456.881 rows=78214)
              Filter: (lower((name)::text) ~~ '%iphone%'::text)
              Rows Removed by Filter: 921786
              Buffers: shared hit=18 read=42819
Execution Time: 2487.241 ms
```

Một dòng nhảy vào mắt như chữ ký giết người: **`Rows Removed by Filter: 921786`**.

DB đã quét hết **1.2 triệu** dòng. Lọc ra 78 nghìn. Vứt đi 921 nghìn. Đọc 42 nghìn page từ disk. Tốn 2.5 giây.

Để làm gì? Để in ra 20 dòng đầu.

---

## 🧱 Tại sao B-tree đầu hàng?

Câu hỏi Day 3 đã trả lời nhưng giờ phải kể lại với context mới: **B-tree là cuốn từ điển sorted**.

Từ điển sorted thì lookup "abc..." dễ — mở giữa, so sánh, đi trái/phải. Đó là `LIKE 'abc%'`.

Nhưng `LIKE '%iphone%'` thì hỏi: *"trang nào có chứa chữ iphone bất cứ chỗ nào?"*

Cuốn từ điển sorted không trả lời được. Phải lật từng trang.

Đó là **non-sargable** — fancy word cho "predicate không thể chuyển thành index seek". Và đó là lý do `idx_products_name_lower` mà Day 3 đặt nền móng đứng nhìn Seq Scan diễn ra mà không làm gì được. Nó chỉ giúp `LIKE 'apple%'`. Không giúp `LIKE '%apple%'`.

> 💡 Sargable = **S**earch **ARG**ument **ABLE**. Một predicate sargable là cái mà DB có thể chuyển thành range scan. Wildcard ở đầu chuỗi → không sargable. Function trên column (`LOWER(name) =`) → không sargable trừ khi có expression index match đúng.

---

## 🧬 pg_trgm — index ngược trên ba ký tự

Đây là chỗ Postgres khoe cơ.

`pg_trgm` (trigram) tokenize string thành các đoạn 3 ký tự. `"iphone"` thành:

```
"  i", " ip", "iph", "pho", "hon", "one", "ne ", "e  "
```

Index ngược: với mỗi trigram, lưu danh sách row ID chứa nó.

Khi user gõ `LIKE '%iphone%'`, Postgres tokenize keyword cũng: `iph`, `pho`, `hon`, `one`. Tra index ngược cho từng trigram → giao tập các row ID → candidate rows. Sau đó **recheck** trên heap để loại false positive (vì trigram trùng không có nghĩa substring trùng).

Một câu lệnh DDL. Đổi luật chơi:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_products_name_trgm
    ON products USING GIN (LOWER(name) gin_trgm_ops);

ANALYZE products;
```

`gin_trgm_ops` là operator class — nó dạy GIN biết cách so sánh trigram, và khai báo support cho `LIKE`, `ILIKE`, regex, similarity `%>`.

Cái đẹp: **JPQL ở repository không cần đổi một chữ**.

---

## 🧪 Sau khi bật — kính hiển vi nói gì?

Chạy lại cùng query. Cùng keyword. Cùng dataset 1M rows.

```text
Limit  (cost=345.12..347.41 rows=20) (actual time=43.218..43.224)
  ->  Bitmap Heap Scan on products p  (rows=78214 width=180)
        Recheck Cond: (lower((name)::text) ~~ '%iphone%'::text)
        Heap Blocks: exact=2418
        Buffers: shared hit=2436
        ->  Bitmap Index Scan on idx_products_name_trgm  (rows=78214 width=0)
              Index Cond: (lower((name)::text) ~~ '%iphone%'::text)
              Buffers: shared hit=18
Execution Time: 43.281 ms
```

`Seq Scan` biến mất. `Bitmap Index Scan` lên thế. Buffer pages **42,819 read → 2,436 hit**. Disk im lặng. Cache nóng. CPU rảnh.

**2487ms → 43ms.** Khoảng 57 lần. Một extension. Một index. Một dòng `ANALYZE`.

Dashboard p95 ngả mình.

---

## 🪤 Hai cạm bẫy mà AI hay khen lầm

### Bẫy 1 — Index thừa "for safety"

Nếu hỏi AI "tối ưu products table", nó sẽ list 8 cái index. Mỗi column một cái. *"Để khỏi quên."*

Nhưng mỗi index thừa là **write tax âm thầm**. Insert 1 row × 8 index = 8 lần update tree. GIN còn tệ hơn — viết chậm gấp ~5× B-tree.

Senior viết index như viết test — chỉ tạo khi có predicate thật phía sau.

### Bẫy 2 — Quên `CONCURRENTLY` trên prod

`CREATE INDEX` mặc định lấy `ShareLock`. Ở 1.2M rows + GIN build, lock kéo dài 30-60 giây. Trong 30 giây đó, mọi INSERT/UPDATE bảng products bị treo. Admin đang thêm sản phẩm? Treo. Outbox đang publish event? Treo. Cache invalidation? Treo theo.

Phải dùng `CREATE INDEX CONCURRENTLY` — Postgres quét bảng 2 lần, không lock write. Đánh đổi: ~2× chậm hơn, KHÔNG chạy được trong transaction (nên KHÔNG hợp Flyway default — Flyway wrap mỗi migration trong tx).

> 💡 Kinh nghiệm: V5 migration của Day 16 để comment to đùng rằng prod phải tách lệnh CONCURRENTLY ra chạy psql trước, rồi `flyway baseline`. Đây là loại detail mà DBA review sẽ soi đầu tiên. Không có comment đó = bị quote review.

---

## 🛡️ Covering index — quà tặng kèm cho list-by-category

Endpoint thứ hai phổ biến của ShopVN: `GET /products?categoryId=X`. List trang chính.

Trước Day 16: `idx_products_category (category_id)` → Index Scan → fetch heap cho từng row để lấy `name, price, status`. Heap fetch tốn I/O.

Sau Day 16:

```sql
CREATE INDEX idx_products_category_active_covering
    ON products (category_id, created_at DESC)
    INCLUDE (id, name, price, status)
    WHERE status = 'ACTIVE';
```

`INCLUDE` columns gắn vào leaf của B-tree. Visibility map cho biết tuple visible → planner skip luôn heap. **Index-Only Scan**, `Heap Fetches: 0`. Sub-millisecond.

`WHERE status = 'ACTIVE'` cắt index size còn 70% (3 status, chỉ index 1).

Đây là khi index không chỉ là "tìm nhanh" mà là **chứa luôn câu trả lời**.

---

## 🎭 Ba sai lầm điển hình junior + AI viết EXPLAIN

1. **Đọc cost mà không đọc actual time**. Cost là estimate; actual là thật. Estimate sai 10× xảy ra liên tục — chỉ thấy nếu so 2 con số.
2. **Bỏ qua Buffers**. Buffer pages mới cho biết I/O thật. Cost cao mà buffers hit hết → vẫn nhanh; cost thấp mà buffers read disk → vẫn chậm.
3. **Chỉ EXPLAIN — không ANALYZE**. EXPLAIN không chạy query, chỉ in plan ước lượng. ANALYZE chạy thật. Hai output có thể rất khác — nhất là khi stats cũ.

---

## Kết thúc ngày 16

```
Day 16 scorecard
├── EXPLAIN ANALYZE: đọc được như senior, không còn nhìn output như chữ tượng hình
├── pg_trgm GIN: 2.5s → 45ms (57×) — substring search ở 1M rows không còn là vấn đề
├── Covering index: list-by-category sub-millisecond, Heap Fetches: 0
├── Migration safety: V5 + comment to đùng CONCURRENTLY cho prod
├── Doc 4 đầu sách: performance / lesson / issue 9-section / interview 5 Q&A
└── Mindset: index theo predicate, không theo column
```

> *Vibe: "Cache giúp ta đi nhanh hơn. Index giúp ta đi đúng đường. Hai thứ khác nhau — nhưng cùng nói một chuyện: hệ thống chỉ nhanh khi mỗi câu hỏi gặp đúng cấu trúc trả lời."*

> 💡 **Senior vs Junior**: junior nói "DB chậm, scale up". Senior nói "let me EXPLAIN". Câu trả lời thường nằm trong 10 dòng plan, không nằm trong $500/tháng instance to hơn.

---

*→ Cache đã xong. Index đã xong. Tuần này đi tiếp một lớp nữa — lớp ORM. Vì có một loại chậm mà cả cache và index đều cứu không nổi: **N+1**.*
