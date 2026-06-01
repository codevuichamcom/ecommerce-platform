# Chương 22 · 🔮 Ông thầy bói đọc vị (và bản phô-tô cuốn sổ gốc)

**Day 22 — Elasticsearch product search**

---

> *"Ông kế toán giữ sổ gốc — chính xác từng đồng, nhưng hỏi gì ổng cũng lật từng trang. Ông thầy bói thì khác: khách lắp bắp 'cho... cho cái iphon' (thiếu chữ e), ổng cười 'iPhone 15 Pro hả, đây'. Nhanh, hiểu ý, đoán cả khi khách nói nhịu. Vấn đề duy nhất: ổng không giữ sổ gốc. Ổng đọc bản phô-tô."*

---

## Bối cảnh

Tuần trước anh Khải soi gương, đan lưới, rồi vỗ vai: *"Week 4, Elasticsearch.
Đừng panic — em đã biết cách đo."* (ch.21).

Tuần này gương cất đi. Trên bàn là một lời than từ marketing: *"Khách gõ 'iphon'
ra **không có gì**. Gõ đúng 'iPhone' thì ra, nhưng cái áo thun in hình iPhone xếp
TRÊN cái iPhone thật. Search nhà mình bị gì vậy?"*

Bạn mở lại Day 16. GIN trigram. p95 **45ms**. Nhanh lắm. Nhưng nó là **ông kế toán**:
tra đúng từng ký tự thì giỏi, mà khách viết sai 1 chữ là ổng lắc đầu "không có trong
sổ". Và ổng không biết "cái nào liên quan hơn" — với ổng, mọi dòng có chứa "iphone"
đều ngang nhau.

Marketing không cần nhanh hơn. **Họ cần một ông thầy bói.**

> 💡 **Bẫy phỏng vấn ngay câu đầu**: "ES nhanh hơn nên migrate" là SAI. GIN đã 45ms.
> Migrate vì **capability** — relevance, fuzzy, facet — không phải tốc độ. Trả lời
> "nhanh hơn" là interviewer vặn "45ms chưa đủ nhanh à?" và bạn cứng họng.

---

## 🔮 Thầy bói khác kế toán ở đâu: cuốn mục lục lật-ngược

Ông kế toán (B-tree) ghi sổ kiểu: *dòng 1 → "iPhone 15 Pro"*. Hỏi "dòng 1 là gì",
lật phát ra ngay. Hỏi "cái nào chứa chữ pro", ổng phải đọc **cả sổ**.

Ông thầy bói (inverted index) ghi **ngược**: mỗi *chữ* → *danh sách dòng chứa nó*.

```
iphone  → [Dòng1, Dòng2]      ("iPhone 15 Pro Max", "Ốp lưng cho iPhone")
pro     → [Dòng1]
max     → [Dòng1]
ốp      → [Dòng2]
samsung → [Dòng3]
```

Hỏi "pro" → ổng chỉ thẳng `[Dòng1]`. Không đọc cả sổ. Đó là vì sao thầy bói trả lời
full-text nhanh **bất kể chữ nằm đâu trong câu**.

Mà ổng còn ghi kèm: chữ này xuất hiện **mấy lần** trong dòng, **bao nhiêu dòng** có
nó. Hai con số đó → **BM25** → ổng biết dòng nào "liên quan hơn". Chữ hiếm mà xuất
hiện nhiều trong 1 dòng = dòng đó đáng nghi (theo nghĩa tốt). Ông kế toán? Ổng chỉ
biết *có* hoặc *không*. Không có khái niệm "liên quan".

---

## 🗂️ Dạy thầy bói phân loại: `text` và `keyword`

Đây là chỗ junior (và AI) ngã sấp mặt. Khi khai báo cho thầy bói biết mỗi cột là gì,
có **hai loại bút**:

- **`text`** — bút *xay nhuyễn*: "iPhone 15 Pro" → xay thành `[iphone, 15, pro]`, viết
  thường hết. Dùng cho thứ cần **tìm theo nghĩa** (tên, mô tả).
- **`keyword`** — bút *để nguyên cục*: "Apple" giữ y "Apple". Dùng cho thứ cần **đếm,
  lọc, sắp** (brand, category, status).

```java
@MultiField(                                   // name: 1 cột, 2 bút
    mainField = @Field(type = FieldType.Text, analyzer = "standard"),     // tìm theo nghĩa
    otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword)) // sắp xếp
private String name;

@Field(type = FieldType.Keyword)               // brand: chỉ bút nguyên cục
private String brand;
```

> ⚠️ **Cạm bẫy chết người #1**: để `brand` bút `text`. "Apple" bị xay thành `apple`,
> "Apple Store" thành `apple` + `store`. Lúc đếm facet "có bao nhiêu hàng Apple", thầy
> bói trả về bucket `apple: 99`, `store: 12` — **rác hoàn toàn**. Cột để đếm phải là
> `keyword`. Trùng — sai. Đếm — phải nguyên cục.

---

## 🎯 Câu thần chú: must, filter, và phép thuật `^3`

Khách hỏi, bạn dịch sang câu thần chú thầy bói hiểu ([`ProductSearchService`](../../services/product-service/src/main/java/com/ecom/product/search/ProductSearchService.java)):

```java
Query.of(qb -> qb.bool(b -> {
    b.must(m -> m.multiMatch(mm -> mm
            .query("iphon")
            .fields("name^3", "description", "brand^2")   // ← phép thuật
            .fuzziness("AUTO")));                          // ← đọc cả khi nói nhịu
    b.filter(f -> f.term(t -> t.field("status").value("ACTIVE")));  // ← lọc cứng
    // category / price range thêm vào filter nếu có
}));
```

Ba thứ đắt giá trong đây:

1. **`name^3`** — boost. Match ở *tên* đáng gấp 3 match ở *mô tả*. Giờ cái iPhone
   thật xếp TRÊN cái áo thun in hình iPhone. Marketing hết than. 🎉
2. **`fuzziness("AUTO")`** — thầy bói cho phép khách sai 1-2 ký tự (Levenshtein).
   "iphon" → "iPhone". Khách nói nhịu, ổng vẫn hiểu.
3. **`must` vs `filter`** — câu chữ (tìm theo nghĩa) vào `must` (có **tính điểm**, ảnh
   hưởng thứ hạng). Điều kiện cứng (status, category, giá) vào `filter` (**không** tính
   điểm, được thầy bói **nhớ sẵn bằng bitset** → nhanh hơn). Junior nhét hết vào `must`
   → mất bộ nhớ đệm + điểm số bị nhiễu bởi "status=ACTIVE".

Và quà tặng kèm — **facet đếm miễn phí** trong cùng một lần hỏi:

```
facets: { brand: [{Apple, 42}, {Samsung, 31}, {Xiaomi, 12}] }
```

Ông kế toán muốn cái này phải chạy `GROUP BY` riêng. Thầy bói tính sẵn từ cuốn mục
lục lật-ngược. Tiện.

---

## 📋 Bản phô-tô: vì sao thầy bói KHÔNG giữ sổ gốc

Đây là câu mà anh Khải dặn đi dặn lại: *"KHÔNG được biến ES thành source of truth."*

Thầy bói giỏi đoán ý, nhưng ổng:
- không có **két sắt** (ACID transaction) — không đảm bảo "trừ tiền A cộng tiền B"
  toàn-hoặc-không;
- không có **khoá cửa** (unique constraint cứng);
- đọc **bản phô-tô** cập nhật trễ ~1 giây (refresh interval) — hỏi ngay sau khi sổ gốc
  vừa đổi, ổng có thể đọc bản cũ.

Nên: **Postgres giữ sổ gốc. ES đọc bản phô-tô.** Mỗi lần sổ gốc đổi, phải phô-tô lại
trang đó cho thầy bói. Bằng cách nào? Kafka.

```java
// ProductService — sau khi sổ gốc COMMIT mới đi phô-tô
runAfterCommit(() -> publisher.publishUpserted(event));   // product.upserted → Kafka
```

```java
// ProductIndexer — thầy bói nhận trang phô-tô, dán vào mục lục
@KafkaListener(topics = PRODUCT_UPSERTED, groupId = "...-indexer")
public void onUpserted(ProductUpsertedV1 e) { searchRepository.save(toDocument(e)); }
```

> 💡 **Vì sao phô-tô SAU commit, không phải trong?** Nếu phô-tô *trong* lúc ghi sổ rồi
> sổ bị **xé bỏ** (transaction rollback), bạn đã đưa thầy bói một trang **ma** — sản
> phẩm không tồn tại. `afterCommit` chỉ phô-tô khi mực sổ gốc đã khô.

---

## 🩹 Khi bản phô-tô lệch sổ gốc: drift

Nhưng đời không đẹp. Kafka đang restart lúc bạn bấm "phô-tô". Trang không tới tay thầy
bói. Sổ gốc có sản phẩm mới, thầy bói không biết → **drift**.

Đây **chính** là dual-write problem mà Day 13 (ch.13 — sợi chỉ đỏ) đã đánh nhau cho
order-service. Lần đó giải bằng **outbox**. Lần này?

```
┌─ Approach ──────────────┬─ Khi nào ───────────────────────────────┐
│ App-level dual-write    │ search = bản sao non-critical (← Day 22) │
│  + nightly reconcile    │ drift sửa bằng đếm lại + phô-tô lại toàn │
│ Outbox + relay (Day 13) │ cần không mất event (như order)          │
│ Debezium CDC            │ volume lớn / nhiều nguồn ghi             │
└─────────────────────────┴──────────────────────────────────────────┘
```

Day 22 chọn **dual-write + reconcile** — **có chủ ý**. Thầy bói đọc bản phô-tô lệch
vài trang trong vài giờ thì search hơi sai, **không mất tiền**. Khác order (mất event
= mất đơn = mất tiền → bắt buộc outbox). Có cái cân để đo:

```bash
GET /admin/search/drift
→ {"postgresActive": 48230, "elasticsearchDocs": 48198, "drift": 32}
POST /admin/search/reindex   # đếm lệch 32? phô-tô lại toàn bộ → drift 0
```

> ⚠️ **Senior vs junior**: junior viết dual-write rồi tưởng xong. Senior viết
> dual-write **kèm cái cân drift + nút reindex** — vì biết bản phô-tô SẼ lệch, câu hỏi
> chỉ là "lệch bao nhiêu, sửa lúc nào". Không có cân = 6 tháng sau search đầy rác mà
> không ai biết.

Còn một cái bẫy ordering: nếu trang "thêm" và trang "xoá" của cùng 1 sản phẩm đi **hai
ngả** (key khác nhau), trang "xoá" có thể tới trước → sản phẩm **sống lại** trong mục
lục. Nên cả hai topic đều khoá `key = productId` → cùng một ngả → đúng thứ tự. Thêm —
rồi xoá. Không bao giờ ngược.

---

## 🤕 Khi thầy bói ốm: fallback về ông kế toán

Thầy bói cũng có ngày OOM, GC pause, mất mạng. Lúc đó search **không được** ngã ra
500. Khách vẫn phải mua được hàng.

```java
try {
    result = searchService.search(...);                    // hỏi thầy bói
} catch (DataAccessException ex) {
    log.warn("ES down, fallback Postgres LIKE");
    result = postgresFallback(...);                        // quay về ông kế toán
}
// header: X-Search-Source: elasticsearch | postgres-fallback
```

Ông kế toán chậm hơn, không đoán ý, không xếp hạng — nhưng còn trả được kết quả. **Đây
là lý do KHÔNG xoá GIN index Day 16 sau khi có ES.** Giữ ông kế toán lại làm dự phòng.

> ⚠️ "Catch broad thế khác gì trap [05]?" — Khác. Trap [05] là *nuốt* exception rồi
> **mất event** (write path). Đây là *degrade* một read non-critical, có log + header
> báo nguồn + fallback thật phục vụ khách. Read ngã thì đỡ; write nuốt thì chết.

---

## 🔧 Cái dằm trong build: AI nhớ nhầm API

Lúc viết câu thần chú, AI generate kiểu ES client **7.x**:

```java
f.range(r -> { r.field("price"); r.gte(JsonData.of(min)); });  // ❌ compile fail
```

ES client **8.15** đổi `RangeQuery` thành **tagged union** — `field/gte/lte` chui
xuống `untyped`:

```java
f.range(r -> r.untyped(u -> u.field("price").gte(JsonData.of(min))));  // ✅
```

Năm phút loay hoay. Bài học vào [trap [07]](../review/ai-junior-traps.md): **API ES
client đổi nhiều giữa minor version — đừng tin code "trông đúng", phải compile + chạy
integration test thật trên container.** Mà chạy thật mới lòi ra cái dằm thứ hai:
docker-java mặc định negotiate API 1.32, daemon đòi ≥1.40 → *"client version too old"*.
Pin `DOCKER_API_VERSION` trong build.gradle. Rồi mới xanh.

5 integration test trên ES 8.15 **thật**: fuzzy "iphon"→iPhone ✅, name^3 rank ✅,
facet count ✅, highlight `<em>` ✅, brand filter ✅. Thầy bói biết bói thật.

---

## 🎬 Kết thúc ngày 22

```
Day 22 ✅ Elasticsearch search
├── 🔮 ES 8.15: inverted index + BM25 + fuzzy + facet + highlight
├── 🗂️ mapping text/keyword (multi-field cho name)
├── 🔄 sync app-level: product.upserted/deleted qua Kafka, key=productId
├── 🩹 drift = chấp nhận (search non-critical) + cân /admin/search/drift + reindex
├── 🤕 ES down → fallback Postgres GIN (giữ Day 16 làm dự phòng)
├── ✅ 5 integration test PASS trên ES container thật
└── 📚 5 docs + ADR-010 + trap [07] (AI nhớ nhầm API version)

Vibe: "Thầy bói đoán ý giỏi — nhưng nhớ giùm, ổng chỉ đọc bản phô-tô."
```

Bản phô-tô đầu tiên đã dán xong. Postgres giữ sổ gốc, ES bói theo bản sao, Kafka làm
máy phô-tô. Hệ thống giờ có **3 kho**: sổ gốc quan hệ (Postgres), bộ nhớ chớp nhoáng
(Redis), mục lục lật-ngược (Elasticsearch).

Nhưng có một loại dữ liệu mà cả ba đều thấy gượng: cái **TV** có "screen_size,
resolution", cái **áo** có "size, color, material" — mỗi loại hàng một bộ thuộc tính
khác nhau, nhét vào cột quan hệ thì thành EAV gớm ghiếc. Sổ gốc của ông kế toán bắt
đầu chật.

*→ Chương 23: một cuốn sổ **không có dòng kẻ sẵn** — MongoDB. Khi nào "không schema"
là tự do, khi nào là cái bẫy?*
