# Chương 3 · 📦 Kệ hàng và nghệ thuật phân trang

**Day 3 — Product Service**

---

> *"Một cửa hàng không có hàng chỉ là căn phòng trống có cổng khóa. Nhưng một chủ tiệm khôn không khoe kệ đầy — họ khoe cuốn sổ nợ, vì nợ có ghi chép là nợ trả được."*

---

> 🎬 **Chương này có gì:** một chủ tiệm dựng kệ hàng thần tốc nhưng tay luôn cầm sổ nợ, một màn hình trắng vì entity rò ra ngoài, ba cách nhét thuộc tính sản phẩm muôn hình vạn trạng, và một câu thần chú phân biệt "nợ có chủ đích" với "nợ vì không biết gì". 🧾

---

## 🎬 Bối cảnh: chủ tiệm và cuốn sổ nợ

Người gác cổng đã khóa cổng (Chương 2). Giờ vương quốc cần thứ để **bán**. Vào vai mới: **chủ tiệm** 🧑‍💼.

Chủ tiệm này có một thói quen lạ. Mỗi lần dựng kệ nhanh, mỗi lần chọn giải pháp "tạm đủ", bác không lờ đi — bác **rút sổ ra ghi**: *"Món này dựng tạm, nợ một khoản, sẽ trả ở Day X."* Đây là khác biệt sống còn giữa hai loại người mắc nợ:

| 🧾 Loại nợ | Chân dung | Số phận |
| --- | --- | --- |
| 💡 **Conscious debt** (nợ có chủ đích) | Biết mình chọn giải pháp tạm, biết tại sao, biết khi nào trả | Trả được, có kế hoạch, ngủ ngon |
| 😵 **Accidental debt** (nợ vì vô ý) | Không biết mình đang nợ, tưởng "thế là xong" | Lãi mẹ đẻ lãi con, vỡ nợ lúc scale |

`product-service` nghe đơn giản — CRUD product, CRUD category, search. Nhưng mấy quyết định "nhỏ" hôm nay quyết định hệ thống sống hay sập khi lên 1 triệu sản phẩm. Chủ tiệm dựng kệ, và mỗi nét bút tạm bợ đều có một dòng trong sổ nợ.

---

## 🩸 Cái bẫy đầu tiên: entity rò ra ngoài, màn hình hóa trắng

Ngày đầu viết API, ai cũng từng phạm tội này — và đây là loại **nợ vô ý** điển hình, vì người viết tưởng "thế là xong":

```java
@GetMapping("/{id}")
public Product getProduct(@PathVariable Long id) {
    return productRepository.findById(id).orElseThrow();
    // ↑ Trả thẳng JPA entity ra response. Đơn giản. Nhanh. VÀ SAI.
}
```

> 🎬 **Cảnh phim:** frontend gọi `GET /products/42`, hớn hở chờ JSON. Cái về tới là một mớ hổ lốn có trường `hibernateLazyInitializer` chình ình giữa response. Jackson cố serialize cái lazy proxy của `category`, JSON vỡ cấu trúc, `JSON.parse()` ở client ném exception, React render ra... **màn hình trắng** 💀. Không lỗi backend, không 500, log sạch bong — chỉ một anh frontend ngồi gãi đầu lúc 5h chiều thứ Sáu.

Vì sao trả thẳng entity là sai? Ba lý do, không cái nào lành:

1. 🩸 **Hibernate proxy leak** — `category` là lazy proxy, Jackson serialize nó → `hibernateLazyInitializer` lọt vào JSON → client parse fail (chính cái màn hình trắng trên).
2. 🔓 **Lộ trường nội bộ** — `createdBy`, `version`, `deletedAt` phơi ra ngoài. Attacker đọc được schema, biết hệ thống xài soft-delete, optimistic lock... miễn phí.
3. 🔗 **Coupling** — đổi entity = đổi luôn API contract. Một lần refactor cột DB là một lần mọi client gãy.

**Cách bịt:** dựng một **tường lửa** giữa tầng persistence và tầng API. Entity ở **trong**, DTO ra **ngoài**. Dùng MapStruct map compile-time (không reflection runtime), DTO là record immutable:

```java
// Entity ở lại bên trong, không bao giờ ra cổng
@Entity class Product { ... }

// DTO ra ngoài — immutable, khai báo rõ ràng, an toàn
public record ProductResponse(
    Long id, String name, String sku,
    BigDecimal price, Map<String, Object> attributes
) {}
```

Thêm một dòng cấu hình khoá cửa hậu:

```yaml
spring:
  jpa:
    open-in-view: false   # tắt OSIV — chặn lazy loading ngoài transaction
```

Tắt OSIV nghĩa là: quên load relation trong service layer thì **fail ngay tại đó**, không âm thầm bắn N+1 query ngoài transaction rồi đổ bệnh sau. Lỗi to tiếng tốt hơn lỗi thì thầm.

> 🧠 **Senior insight:** entity leak là nợ **vô ý** — nguy hiểm vì người viết không biết mình đang nợ. DTO boundary biến nó thành quyết định **có ý thức**: "tôi chủ động kẻ ranh giới persistence/API". Senior không phải người không bao giờ mắc nợ — là người biết mình đang nợ gì.

---

## 🗃️ JSONB: nợ có chủ đích, ghi rõ ngày trả

Sản phẩm thì muôn hình vạn trạng. TV có `screen_size`, `resolution`. Áo có `size`, `color`, `material`. Laptop có `ram`, `cpu`, `storage`. Làm sao một bảng chứa được mọi loại thuộc tính?

Chủ tiệm cân ba cách, ghi rõ ưu nhược vào sổ:

| 🧩 Approach | Pros | Cons |
| --- | --- | --- |
| 🧱 **50 cột nullable** | Query nhanh, có type | Schema cứng đơ, 90% cột NULL, thêm loại hàng = ALTER TABLE |
| 🕸️ **EAV** (Entity-Attribute-Value) | Linh hoạt vô biên | Query chậm, JOIN hell, mất type safety, debug khóc |
| ✅ **JSONB column** | Linh hoạt + query được + index được | Không FK constraint trên attributes |

**Chọn JSONB.** Postgres 16 index được GIN trên JSONB, nên query `WHERE attributes->>'brand' = 'Apple'` vẫn xài index, không quét toàn bảng.

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private Map<String, Object> attributes;
```

Nhưng chủ tiệm không giả vờ JSONB là chân lý vĩnh cửu. Bác ghi vào sổ nợ rõ ràng: *"JSONB là stepping stone. Khi cần aggregation pipeline phức tạp hơn — Day 23 chuyển phần này sang MongoDB."* Đây là **conscious debt** mẫu mực: chọn giải pháp đủ-tốt-cho-hôm-nay, biết chính xác giới hạn của nó, và đã hẹn ngày trả.

> 💡 **Ăn điểm phỏng vấn:** khi được hỏi "sao không EAV cho linh hoạt?", trả lời bằng cons cụ thể: *"EAV mất type safety và query thành JOIN hell. JSONB cho linh hoạt tương đương mà vẫn index GIN được. Tôi biết nó không FK-constraint được attributes — đó là khoản nợ tôi chấp nhận, và đã hẹn trả bằng Mongo khi aggregation phức tạp lên."*

---

## 📖 Phân trang: nghệ thuật bị đánh giá thấp

> *"Cho tôi tất cả products"* — câu nói phá sập database nhanh nhất hành tinh.

Chủ tiệm không bao giờ để khách bê cả kho ra. Day 3 dựng offset pagination với **ba lớp phòng thủ**:

| 🛡️ Lớp | Cơ chế | Chặn cái gì |
| --- | --- | --- |
| 1️⃣ **Size cap** | `@Max(100)` | Client xin `size=999999`? Reject. |
| 2️⃣ **Sort whitelist** | Chỉ cho `name`, `price`, `createdAt` | Sort theo `password`? Reject (chống dò cột nhạy cảm). |
| 3️⃣ **Default tử tế** | Không truyền gì → page 0, size 20, sort `createdAt DESC` | Khách lười vẫn được phục vụ đúng. |

```
GET /products?page=0&size=20&sort=price,asc
```

Vì sao offset mà không cursor/keyset? Vì Day 3 data còn ít, offset đủ tốt — và đây lại là một dòng trong sổ nợ: chủ tiệm **biết** offset sẽ chết ở chục triệu rows (đếm trang càng sâu càng chậm, vì DB phải skip qua hết các row trước). Nhưng hôm nay nó đủ. Khoản nợ này ghi rõ: *"Day 18 chứng minh offset sập ở 10M rows, chuyển sang keyset/cursor."*

> 🧠 **Senior insight:** **biết giới hạn của tool mình đang dùng** quan trọng hơn dùng tool phức tạp nhất từ đầu. Cursor pagination phức tạp hơn, và với 100 sản phẩm thì nó là over-engineering. Chủ tiệm chọn đơn giản hôm nay, ghi nợ rõ ràng cho mai.

---

## 🔍 Search: khoản nợ ghi sổ lớn nhất

Cuối cùng là tìm kiếm. Day 3 làm cách thô sơ nhất có thể:

```sql
WHERE LOWER(name) LIKE LOWER('%keyword%')
```

Chậm? Đúng. Không dùng index (vì `%` đầu chuỗi giết mọi B-tree index)? Đúng. Nhưng Day 3 chỉ có 100 sản phẩm — quét toàn bảng 100 dòng còn nhanh hơn dựng cả Elasticsearch. Chủ tiệm cười, rút sổ ra ghi khoản nợ to nhất chương:

> 🧾 **LIKE search hôm nay là khoản nợ ghi sổ — Day 16 trả lãi bằng GIN trigram index, Day 22 tất toán hẳn bằng Elasticsearch.**

Một dòng sổ, hai cột mốc trả nợ, và một lương tâm trong sạch.

> ⚠️ **Cạm bẫy:** đừng optimize quá sớm — nhưng phải **biết** mình đang nợ gì. `LIKE '%...%'` là **conscious debt**, không phải ignorance. Junior viết `LIKE` rồi quên; senior viết `LIKE` rồi ghi vào backlog kèm ngày trả. Cùng một dòng code, hai tư thế hoàn toàn khác.

---

## 🏁 Kết thúc ngày 3

```
📊 Scorecard:
├── Services:        2 (auth + product)
├── Endpoints:       ~12 (CRUD + search + pagination)
├── Traps né được:   Entity leak (màn hình trắng), OSIV, unbounded pagination
├── Sổ nợ ghi rõ:    JSONB (→ Mongo Day 23) · offset (→ keyset Day 18) · LIKE (→ GIN Day 16, ES Day 22)
├── Docs:            4 (lesson pagination, perf search, issue entity-leak, interview)
└── Vibe:            "Kệ hàng đã đầy. Sổ nợ đã ghi. Nhưng ai canh kho?" 🧾
```

> 💡 **Bẫy phỏng vấn kinh điển:** *"Technical debt — khi nào chấp nhận, khi nào không?"*
>
> **Strong answer:** Phân biệt **conscious debt** với **accidental debt**. Conscious: tôi chọn LIKE search vì 100 rows, biết nó chết ở 100k, đã ghi backlog kèm ngày trả (GIN Day 16, ES Day 22) — đây là đòn bẩy hợp lý để ship nhanh. Accidental: trả thẳng entity ra API mà không biết mình đang leak proxy + coupling contract — đây là nợ phải diệt ngay, không thương lượng. Câu hỏi không phải "nợ hay không nợ", mà là "**có ghi sổ và trả được không**".
>
> 🪤 **Follow-up trap:** *"Lấy gì đảm bảo nợ được trả thật, không để mãi?"* → Conscious debt phải có **trigger cụ thể** (đo được: "khi data > 100k rows" / "khi p99 search > 200ms"), không phải "khi nào rảnh". Không có trigger đo được thì conscious debt thoái hóa thành accidental debt — vẫn vỡ nợ, chỉ chậm hơn.

---

*→ Kệ hàng đã đầy, sổ nợ đã ghi tử tế. Nhưng hàng trên kệ có một con số đáng sợ: **tồn kho**. Điều gì xảy ra khi hai khách cùng giành mua chiếc cuối cùng trong kho đúng một phần nghìn giây? Ai canh để không bán quá số hàng mình có?...* 📊
