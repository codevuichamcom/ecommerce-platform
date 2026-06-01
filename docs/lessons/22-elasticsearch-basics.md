# 🔍 Lesson 22 — Elasticsearch basics: inverted index, analyzer, mapping

> **Day 22** · Tag: `search` `elasticsearch` `inverted-index`
> Liên quan: [22b — sync strategies](22b-cdc-vs-app-sync-vs-debezium.md) ·
> [performance/22 — Postgres vs ES](../performance/22-search-postgres-vs-es.md) ·
> [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md) ·
> [issue 22 — sync drift](../issues/22-es-postgres-sync-drift.md)

---

## TL;DR

Postgres GIN trigram (Day 16) đưa search 1M rows từ 2.5s → 45ms — **nhanh**, nhưng
nó là **substring match**: gõ "iphon" (thiếu chữ) ra **0 kết quả**, và mọi match
"bằng nhau" (không có khái niệm độ liên quan). Elasticsearch xây trên **inverted
index** + **analyzer** + scoring **BM25** → cho 3 thứ Postgres không có rẻ:
**relevance ranking**, **fuzzy/typo tolerance**, **faceted aggregation** real-time.

Đổi lại: ES là **search index** (derived data), **KHÔNG phải source of truth**.
Postgres vẫn own data. Thêm ES = thêm 1 storage phải vận hành + 1 sync pipeline
phải canh drift.

---

## 🧠 Inverted index — trái tim của ES

B-tree (Postgres index thường) map **row → giá trị**. Inverted index làm **ngược**:
map **token → danh sách document chứa token đó**.

```
Doc1: "iPhone 15 Pro Max"
Doc2: "Ốp lưng cho iPhone"
Doc3: "Samsung Galaxy"

Inverted index (sau analyze):
  iphone  → [Doc1, Doc2]
  15      → [Doc1]
  pro     → [Doc1]
  max     → [Doc1]
  ốp      → [Doc2]
  lưng    → [Doc2]
  samsung → [Doc3]
  galaxy  → [Doc3]
```

Search "iphone" → tra thẳng key `iphone` → trả `[Doc1, Doc2]` tức thì. Đây là lý do
ES match full-text nhanh **bất kể** từ khóa nằm giữa câu — khác `LIKE '%kw%'` phải
scan (GIN trigram cải thiện nhưng vẫn là substring, không phải token).

> 💡 **Vì sao ES rank được mà SQL không?** Inverted index lưu kèm **term
> frequency** (token xuất hiện bao nhiêu lần trong doc) + **document frequency**
> (bao nhiêu doc chứa token). BM25 dùng 2 số này tính điểm: token hiếm + xuất hiện
> nhiều trong 1 doc → doc đó "liên quan" hơn. `LIKE` chỉ biết match/không-match.

---

## 🔬 Analyzer — biến text thành token

Lúc **index** VÀ lúc **query**, ES chạy text qua **analyzer**: `tokenizer` cắt token
+ chuỗi `token filter` biến đổi.

```
"iPhone 15 Pro Max"
  → tokenizer (standard): ["iPhone", "15", "Pro", "Max"]
  → lowercase filter:     ["iphone", "15", "pro", "max"]
  → (tùy analyzer) stop/stemming...
```

Query "PRO max" → cùng analyzer → `["pro", "max"]` → match Doc1. **Analyzer phải
nhất quán index-time và query-time** — nếu index lowercase mà query không, sẽ miss.
Đây là cạm bẫy #1 khi tự custom analyzer.

Project này dùng `standard` analyzer (đủ cho catalog tiếng Việt + Anh lẫn lộn). Tiếng
Việt dấu thì `standard` giữ nguyên token có dấu — gõ có dấu match có dấu. Nâng cao
(Day 24+ nếu cần): `icu_analyzer` fold dấu, hoặc plugin `vi_analyzer`.

---

## 🗺️ Mapping — text vs keyword (quyết định QUAN TRỌNG nhất)

| | `text` | `keyword` |
|---|---|---|
| Có analyze? | ✅ tokenize + lowercase | ❌ lưu nguyên chuỗi |
| Dùng cho | full-text match (relevance) | filter / aggregation / sort |
| Ví dụ field | `name`, `description` | `brand`, `categoryId`, `status` |
| Aggregation? | ❌ (tốn fielddata, mặc định tắt) | ✅ |

⚠️ **Cạm bẫy chết người**: để `brand` là `text`. Khi đó "Apple" bị analyze thành
token `apple`, "Apple Store" thành `apple` + `store`. Faceted aggregation theo brand
sẽ ra bucket `apple` / `store` thay vì `Apple` / `Apple Store` → **sai facet hoàn
toàn**. Field cần đếm/lọc/sort phải là `keyword`.

Idiom "1 field 2 cách dùng" — **multi-field**: `name` vừa cần full-text (relevance)
vừa cần sort alphabet → khai báo `name` là `text` + sub-field `name.keyword` là
`keyword`. Trong [`ProductDocument`](../../services/product-service/src/main/java/com/ecom/product/search/ProductDocument.java):

```java
@MultiField(
    mainField = @Field(type = FieldType.Text, analyzer = "standard"),
    otherFields = { @InnerField(suffix = "keyword", type = FieldType.Keyword) })
private String name;
```

---

## 🎯 Query: must vs filter, multi_match, fuzziness, boost

Query thật ở [`ProductSearchService`](../../services/product-service/src/main/java/com/ecom/product/search/ProductSearchService.java):

```
bool {
  must:   multi_match(q, fields=[name^3, description, brand^2], fuzziness=AUTO)
  filter: term(status = ACTIVE)         // luôn có
  filter: term(categoryId)  nếu có
  filter: range(price gte/lte) nếu có
}
```

- **`name^3`** = **boost**: match ở name "đáng giá" gấp 3 description. Gõ "iphone"
  thì product TÊN iPhone xếp trên product chỉ NHẮC iPhone trong mô tả. Relevance
  tuning — thứ `LIKE` không có (mọi match ngang nhau).
- **`fuzziness=AUTO`**: cho phép sai 1-2 ký tự tùy độ dài từ (Levenshtein edit
  distance). "iphon" → match "iphone". Đây là typo tolerance khách hàng cần.
- **`must` vs `filter`**: query text vào `must` (tính score → ảnh hưởng ranking);
  category/price/status vào `filter` (KHÔNG tính score, ES **cache bitset** → nhanh
  hơn + đúng ngữ nghĩa "lọc cứng"). Junior nhét hết vào `must` → mất cache + ranking
  bị nhiễu bởi điều kiện lọc.

---

## 📊 Faceted aggregation — "free" trong cùng query

Search xong, ES trả kèm **count theo facet** trong **cùng 1 round-trip**:

```
facets: { brand: [{Apple, 42}, {Samsung, 31}], category: [...] }
```

Postgres muốn cái này phải chạy `GROUP BY` riêng (thêm query). ES tính sẵn từ inverted
index (đã biết document frequency) → faceted UI (checkbox filter kèm số) gần như miễn
phí. Đây là 1 lý do search-heavy workload thích ES.

---

## ✅ Khi nào dùng ES

- Full-text search cần **relevance ranking** (BM25, boosting, tuning).
- **Fuzzy / typo tolerance**, autocomplete, synonyms.
- **Faceted search** + aggregation real-time (count theo brand/category/price).
- Search log / observability (ELK), analytics ad-hoc trên text.
- Khi muốn **tách tải search khỏi OLTP** (search cluster scale riêng).

## ❌ Khi nào KHÔNG dùng ES

- **Làm primary store / source of truth** — ES KHÔNG có ACID transaction, không
  unique constraint cứng, near-real-time (refresh ~1s) → đọc-sau-ghi có thể stale.
  Mất data khi reindex sai. Lỗi #1 của junior.
- Data có **invariant chặt** cần transaction (tiền, tồn kho) → Postgres.
- Search đơn giản, volume thấp → `LIKE` / GIN trigram (Day 16) đủ, đừng kéo ES vào
  cho thêm 1 hệ phải vận hành.
- Cần **strong consistency** đọc-sau-ghi tức thì → ES refresh interval phá vỡ điều đó.

---

## ⚠️ Cạm bẫy

1. **text cho field aggregation** → sai bucket (xem trên). Brand/status/category =
   `keyword`.
2. **Analyzer lệch index-time vs query-time** → query miss dù data có.
3. **Coi ES là source of truth** → reindex = mất data; sync fail = data sai vĩnh
   viễn. ES là derived, Postgres own.
4. **Quên `status` filter** → search trả product DRAFT/ARCHIVED. Service luôn filter
   `status=ACTIVE`.
5. **BigDecimal → double** trong ES: precision loss. OK vì ES chỉ filter/sort giá,
   KHÔNG tính tiền (Postgres `NUMERIC(12,2)` là chuẩn). Đừng đọc giá từ ES để
   charge khách.
6. **Replica trên single-node** → shard UNASSIGNED mãi → cluster `yellow`. Dev để
   `replicas=0`; prod multi-node mới tăng.

---

## 🎤 Trả lời phỏng vấn (gọn)

**"Tại sao không dùng Postgres `tsvector` full-text mà phải thêm ES?"**
> `tsvector` ổn cho full-text cơ bản (đã hơn `LIKE`). ES thắng ở: relevance tuning
> (BM25 + boosting), fuzzy/typo, faceted aggregation real-time, và scale search độc
> lập khỏi OLTP. Trade-off: thêm 1 storage vận hành + sync pipeline. Nếu app chỉ cần
> match cơ bản volume thấp → `tsvector`/GIN đủ, ES là over-engineer.

**"text vs keyword?"**
> `text` analyzed cho full-text match (relevance); `keyword` lưu nguyên cho
> filter/aggregation/sort. Field cần đếm/lọc (brand) phải keyword, không thì
> aggregation tách "Apple" thành token "apple" → sai facet. Field vừa search vừa
> sort → multi-field.

**Follow-up trap: "ES down thì sao?"** → graceful degrade fallback Postgres LIKE
(xem [issue 22](../issues/22-es-postgres-sync-drift.md) + controller). Search là
non-critical read → không được để 500.

---

## 🔗 Related

- Code: [`ProductDocument`](../../services/product-service/src/main/java/com/ecom/product/search/ProductDocument.java) ·
  [`ProductSearchService`](../../services/product-service/src/main/java/com/ecom/product/search/ProductSearchService.java) ·
  [`ProductSearchController`](../../services/product-service/src/main/java/com/ecom/product/web/ProductSearchController.java)
- Day 16 baseline: [performance/16 — EXPLAIN ANALYZE GIN trigram](../performance/16-sql-explain-analyze.md)
- Sync: [lesson 22b](22b-cdc-vs-app-sync-vs-debezium.md)
- Decision: [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md)
