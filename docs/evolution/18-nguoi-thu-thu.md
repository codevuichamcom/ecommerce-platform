# Chương 18 · 📖 Người thủ thư và cái kẹp sách

**Day 18 — Pagination at scale (offset → keyset/seek)**

---

> *"Bạn vào thư viện, hỏi cuốn thứ 980.001. Thủ thư đãng trí gật đầu, rồi bắt đầu đếm — một, hai, ba... từ kệ đầu tiên. Bạn pha xong ấm trà, ngủ một giấc, tỉnh dậy bác vẫn đang đếm. Đó không phải bác chậm. Đó là bác **không có cái kẹp sách**."*

---

> 🎬 **Chương này có gì:** một bác thủ thư đếm-lại-từ-đầu kinh niên, một cái kẹp sách cứu cả hệ thống, lý do `created_at` một mình là kẻ phản bội, một tấm vé gửi xe không ghi biển số, và bảng benchmark khiến `OFFSET` muốn về hưu. 📚

---

## 🎬 Bối cảnh: lời nguyền của trang cuối

Chương trước, anh bồi bàn EAGER đã thôi chạy 41 vòng bếp — projection dạy anh ghi phiếu một lần, bếp tự đếm. Trang "Đơn hàng của tôi" từ 3.2s xuống 30ms. Ăn mừng.

Rồi QA mở app, giữ ngón tay kéo xuống. Kéo. Kéo nữa. Kéo mãi. 📱⬇️

Tới khoảng trang 49.000 — gateway trả **504 Timeout**. Feed đứng hình. APM hiện một con số làm cả team im lặng: p99 của `/products` nhảy từ 45ms lên **2.4 giây**. Không phải mọi request. Chỉ những request kéo sâu. Nhưng đủ để bóp nghẹt connection pool và kéo cả endpoint xuống bùn.

Thủ phạm là một dòng SQL trông hiền như nai:

```sql
SELECT ... FROM products
 ORDER BY created_at DESC
 LIMIT 20 OFFSET 980000;
```

Hiền. Nhưng nó giấu một bác thủ thư đãng trí bên trong. 🧐

---

## 🐢 Vì sao OFFSET là lời nói dối ngọt ngào

Ta cứ tưởng `OFFSET 980000` nghĩa là *"nhảy phắt tới row thứ 980.000"*. Postgres không có phép thuật đó. `OFFSET` thực chất là:

> 📖 *"Đọc từ đầu theo đúng thứ tự `ORDER BY`. Đếm. Tới row thứ 980.000 thì bắt đầu cho ra. 980.000 row trước đó? Đọc xong rồi **vứt**."*

Đọc gần một triệu dòng, để trả về hai mươi. Index `(created_at DESC, id)` ta dựng từ Day 3 có giúp không? Có — nhưng chỉ giúp **khỏi phải sort**. Nó không giúp khỏi phải **duyệt**. Bác thủ thư vẫn lê từng bước qua 980.000 cuốn, chỉ là đi trên kệ đã xếp sẵn thay vì xếp lại. Vẫn chậm. Vẫn tuyến tính theo độ sâu.

```
OFFSET 980000 LIMIT 20
   → Index Scan: đọc 980.020 entry
   → đếm, vứt 980.000 cái đầu     ← công vô ích, phình theo độ sâu
   → emit 20 cái cuối
```

Trang 0 thì offset nhanh. Trang cuối thì offset hấp hối. Đây là **lời nguyền của trang cuối**: trang sâu nhất luôn là trang chậm nhất, và không index nào phá được lời nguyền — vì bản chất là *đếm lại từ đầu*.

> ⚠️ **Bẫy che mắt:** p50 và "latency trung bình" vẫn đẹp long lanh, vì 99% người dùng chỉ xem trang 0-3. Deep-page spike chỉ ló mặt ở **p99**. Đo trung bình = không bao giờ thấy con quái vật. Alert phải đặt ở p99, theo từng endpoint.

---

## 🔖 Cái kẹp sách: keyset seek

Giải pháp không phải dạy bác thủ thư đếm nhanh hơn. Là **đưa bác cái kẹp sách**.

Thay vì *"đếm tới cuốn thứ 980.001"*, ta nói: *"đây là cuốn cuối bác đưa tôi lần trước — cho tôi cuốn **đứng ngay sau** nó."* Bác chỉ cần mở đúng chỗ kẹp, lấy 20 cuốn kế tiếp. Không đếm. Không vứt. O(20), bất kể đó là chỗ kẹp ở đầu kệ hay cuối kệ.

Dịch sang SQL, cái kẹp sách chính là **row-value comparison**:

```sql
-- cursor = (created_at, id) của row CUỐI trang trước
SELECT id, name, price, created_at
  FROM products
 WHERE (created_at, id) < (:cursor_at, :cursor_id)   -- so cả cặp, lexicographic
 ORDER BY created_at DESC, id DESC
 LIMIT 20;
```

`(created_at, id) < (:at, :id)` so sánh **từng cặp theo thứ tự**: xét `created_at` trước, hoà thì xét `id`. Postgres seek thẳng tới vị trí kẹp qua index rồi đọc đúng 20 dòng. Hết.

Có một cú lừa nhỏ của JPA mà ai cũng vấp:

> ⚠️ **JPQL/HQL KHÔNG biết cú pháp `(a,b) < (c,d)`.** Viết thẳng vào `@Query` là nó cãi ngay. Phải **xòe tay ra** thành dạng tương đương:
> ```sql
> created_at < :at OR (created_at = :at AND id < :id)
> ```
> Cặp ngoặc trong vế OR là bắt buộc — quên nó là sai logic, không phải sai cú pháp (tệ hơn nhiều).

Code thật, [ProductRepository.searchKeyset](../../services/product-service/src/main/java/com/ecom/product/repository/ProductRepository.java):

```java
@Query("""
    SELECT p FROM Product p
    WHERE (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
      AND (:cursorAt IS NULL
           OR p.createdAt < :cursorAt
           OR (p.createdAt = :cursorAt AND p.id < :cursorId))
    ORDER BY p.createdAt DESC, p.id DESC
    """)
List<Product> searchKeyset(..., Instant cursorAt, UUID cursorId, Limit limit);
```

`:cursorAt IS NULL` là cái công tắc trang-đầu: cursor rỗng thì cả mệnh đề seek biến mất, query trả về đầu kệ. Một query, hai nhiệm vụ.

---

## 🕵️ `created_at` một mình là kẻ phản bội

Đây là chỗ tinh vi nhất, và là chỗ AI viết sai nhiều nhất. Vì sao cái kẹp sách phải ghi **cả hai** `(created_at, id)`, không chỉ mỗi `created_at`?

Vì `created_at` **không unique**. Seed 1M sản phẩm, hàng nghìn cuốn đóng dấu cùng một micro-giây. Hình dung kệ sách có 5 cuốn dán cùng nhãn ngày `10:15:30.123456`:

- Cursor chỉ ghi `created_at`, query `WHERE created_at < $at` → **bỏ sót** mọi cuốn còn lại cùng ngày chưa kịp hiện. Mất hàng. 😱
- Đổi sang `<= $at` → **lặp** những cuốn đã hiện ở trang trước. Khách thấy hàng đôi. 😵

Không có toán tử nào đúng với một cột non-unique. Phải thêm `id` (unique) làm **tie-break** để mỗi cuốn có một vị trí **tuyệt đối** trên kệ — một *total order*. Cặp `(created_at, id)` mới là cái kẹp sách thật. `created_at` đơn độc chỉ là cái kẹp đặt giữa 5 cuốn giống hệt nhau: bác thủ thư mở ra vẫn không biết mình đang ở cuốn nào.

> 💡 **Neo phỏng vấn:** câu *"keyset cần `(created_at, id)` hay chỉ `created_at`?"* là câu lọc người. Junior trả lời "created_at đủ rồi". Senior kể luôn cái bug trùng-timestamp — lỗi **chỉ nổ trên data thật**, không bao giờ lộ khi test 5 dòng. Mọi keyset sort đều phải kết thúc bằng một cột unique.

---

## 🧭 Cái kẹp phải vừa khít cái khe: index ordering

Có cái kẹp đúng rồi, còn phải đảm bảo cái khe trên kệ **vừa khít** với nó. Query sort `created_at DESC, id DESC` + seek `id <`. Để Postgres đọc một mạch không phải sort lại, index phải khớp **cả thứ tự lẫn chiều**:

```sql
-- V6 migration
CREATE INDEX idx_products_keyset ON products (created_at DESC, id DESC);
```

Day 3 đã có `(created_at DESC, id)` — id ngầm hiểu ASC. Phục vụ được không? Được, Postgres scan ngược. Nhưng khi hai cột ngược chiều nhau so với index, planner đôi lúc lấn cấn. V6 dựng index khớp **chính xác** `(DESC, DESC)` → Index Scan thuần, không Sort node, không backward-scan mập mờ. Đây là bài học một dòng đáng dán tường:

> 📐 **Direction của mọi cột trong index phải khớp ORDER BY — cả cột tie-break.** Sai chiều một cột, planner âm thầm chèn một Sort node, và bạn mất sạch lợi ích keyset mà EXPLAIN mới chịu khai ra.

---

## 🎟️ Tấm vé gửi xe không ghi biển số: opaque cursor

Còn một câu hỏi: cái kẹp sách `(created_at, id)` này, trả cho client kiểu gì? Phang thẳng `?cursor_at=...&cursor_id=<uuid>` ra URL ư? Bác thủ thư cau mày. 🧐

Cursor là **tấm vé gửi xe**: client cầm, lần sau đưa lại để lấy đúng chỗ — nhưng **không cần đọc được** trên vé ghi gì. Ta gói `(created_at, id)` thành một token base64 mờ đục:

```java
// ProductCursor.encode()
long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
String raw = micros + ":" + id;                       // "1748686530123456:uuid"
return Base64.getUrlEncoder().withoutPadding()
             .encodeToString(raw.getBytes(UTF_8));     // → "MTc0ODY4..." opaque
```

Vì sao mờ đục? Hai lý do, đều là tư duy senior:
- 🙈 **Không lộ id thô** — client không nhìn ra cấu trúc, không đoán để enumerate.
- 🔧 **Đổi cấu trúc sau không vỡ contract** — mai mốt thêm sort field vào cursor, client vẫn chỉ "đưa lại tấm vé", chẳng biết bên trong đổi gì.

Và một chi tiết cứu mạng: **micro-giây, không phải mili-giây**.

> ⚠️ Postgres `TIMESTAMPTZ` giữ tới **micro**second. Encode cursor bằng millis là tự cắt mất 3 chữ số → cursor không khớp chính xác giá trị trong DB → lại sinh ra lặp/skip ở đúng cái khe trùng timestamp ta vừa khổ sở vá. Một lỗi làm tròn, cả cơ chế đổ.

Tấm vé rách thì sao? Client gửi token bậy bạ, `decode` không được — ta trả **400 Bad Request**, lịch sự, **không** để nó lăn thành 500:

```java
catch (IllegalArgumentException e) {   // base64 hỏng / UUID sai / thiếu ':'
    throw new BusinessException(ErrorCode.BAD_REQUEST, "Cursor không hợp lệ");
}
```

> 💡 **Có cần ký HMAC cái vé không?** Tuỳ scope. List product là **public** — sửa cursor chỉ khiến khách thấy data khác, không phải lỗ hổng → để mờ đục là đủ. Nhưng nếu cursor scope theo user (kiểu "đơn của **tôi**"), không ký = user sửa vé đọc đơn người khác = **IDOR**. Bác thủ quỹ Chương 10 sẽ gật gù: lại là chuyện "không tin client".

---

## 🪄 Đếm trang sau mà không cần đếm: chiêu `size + 1`

Keyset bỏ luôn `COUNT(*)` — đó là một phần lý do nó nhanh. Nhưng vậy làm sao biết *"còn trang sau không"* để bật nút "tải thêm"?

Chiêu cũ mà đẹp: **xin dư một cuốn**. Cần 20 thì fetch 21.

```java
List<Product> rows = repo.searchKeyset(..., Limit.of(safeSize + 1));   // xin 21
boolean hasNext = rows.size() > safeSize;                              // trả về 21 ⇒ còn trang sau
List<Product> page = hasNext ? rows.subList(0, safeSize) : rows;       // cắt cuốn thừa
// kẹp sách mới = (created_at, id) của cuốn CUỐI sau khi cắt
```

Trả về 21 cuốn? Còn trang sau — giấu cuốn thứ 21 đi, dùng cuốn thứ 20 làm kẹp mới. Trả về 20 hoặc ít hơn? Hết kệ, `nextCursor = null`. Biết "còn nữa không" mà không phải đếm cả triệu dòng. 🎩

---

## 🚧 Không phá nhà cũ: hai cánh cửa song song

Anh Hùng dặn một câu sắc: *"Đừng chỉ giới hạn page — feed phải kéo vô hạn. Mà admin vẫn cần số trang, đừng phá."* Hai nhu cầu, **hai cánh cửa**:

| Cửa | Endpoint | Cho ai | Đánh đổi |
| --- | --- | --- | --- |
| 🔢 Offset | `GET /products` (cap page ≤ 500) | Admin: "trang 5/100" + total | Deep page chậm → chặn bằng cap |
| 🔖 Keyset | `GET /products/keyset?cursor=` | Mobile feed: kéo vô hạn | Mất jump-to-page + mất total |

Offset không bị xoá — bị **đóng cọc giới hạn**: quá page 500 thì trả 400, mời sang cửa keyset. Vì offset không xấu; nó chỉ xấu khi **đứng sai chỗ**. Admin table jump-to-page thì offset hoàn hảo. Mobile infinite-scroll thì keyset. Chọn theo access pattern, đừng cargo-cult.

> 🧠 **Senior vs junior:** junior thay luôn offset bằng keyset cho "sạch", rồi vỡ trang admin cần số trang. Senior giữ cả hai, vì hiểu hai access pattern là hai bài toán khác nhau — y hệt bài học CQRS-lite của Chương 17: đừng ép một thứ gánh hai vai.

---

## 📊 Bảng số khiến OFFSET muốn về hưu

Chạy `/debug/pagination/compare?offset=980000` — `EXPLAIN (ANALYZE, BUFFERS)` cả hai cửa, cùng độ sâu, trên seed 1M:

```
                     OFFSET                          KEYSET
trang 0:        ~3ms,   ~25 buffers          ~3ms,   ~25 buffers
offset 100k:  ~280ms,  ~3.2K buffers         ~3ms,   ~28 buffers
offset 980k:   ~2.4s,   ~31K buffers         ~3ms,   ~30 buffers
```

Offset: thời gian và buffers leo **tuyến tính** theo độ sâu. Keyset: **phẳng lì**, trang 0 hay trang 49.000 cũng như nhau. Toàn bộ câu chuyện Day 18 nằm gọn trong ba dòng số này — và trong cái kẹp sách. 🔖

---

## 🏁 Kết thúc ngày 18

```
Trang feed sản phẩm (1M rows)
├── 🐢 OFFSET 980k: ~2.4s, đọc 31K buffer — bác thủ thư đếm lại từ kệ đầu
├── 🔖 KEYSET: ~3ms, ~30 buffer — mở đúng chỗ kẹp, lấy 20 cuốn kế
├── 🕵️ Cursor = (created_at, id): tie-break id chống lặp/skip trùng timestamp
├── 🎟️ Opaque base64, micro-precision, token rác → 400 không 500
├── 🪄 size+1: biết "còn trang sau" mà khỏi COUNT(*)
├── 📐 V6 index (created_at DESC, id DESC) khớp khít ORDER BY — no Sort node
├── 🚧 Giữ cả 2 cửa: offset (cap 500) cho admin · keyset cho mobile
└── 🧪 Build green · 4 unit test cursor codec pass (round-trip micro + token rác)

Vibe: "Đừng dạy thủ thư đếm nhanh hơn. Đưa bác cái kẹp sách." 🔖
```

> 💡 **Bẫy phỏng vấn kinh điển:** *"Trang 50.000 chậm, fix sao?"* — đừng phun ngay "keyset". Hãy hỏi ngược: *"UX cần jump-to-page không?"* Cần số trang → offset + cap + approximate count. Chỉ next/prev → keyset. Rồi mới nói tới `(sort_col, id)` composite + opaque cursor. Hỏi-trước-khi-fix là chữ ký của senior.

---

*→ Cái kẹp sách giải quyết xong chuyện một người đọc lật trang. Nhưng nếu **một trăm người** cùng thò tay vào kệ một lúc — cùng giật cùng một cuốn sách cuối cùng thì sao? Khoá thế nào để không ai giẫm chân ai, mà cũng đừng khoá chặt tới mức cả thư viện xếp hàng? Ngày mai ta bước vào mê cung của `synchronized`, `ReentrantLock`, và một con quái tên là **pinning** rình sẵn dưới gầm virtual thread...* 🧵
