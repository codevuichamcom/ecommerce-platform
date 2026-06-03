# Lesson 23b — Document modeling vs Relational modeling

> 🎯 Mục tiêu: biết **embed hay reference**, biết vì sao EAV trong SQL là
> anti-pattern, biết khi nào JSONB đủ vs khi nào cần document store thật.

---

## TL;DR

Relational chuẩn hoá (normalize) để tránh trùng lặp → join lúc đọc. Document
**denormalize có chủ đích**: gom thứ đọc-cùng-nhau vào 1 document → đọc 1 phát,
không join. Quyết định cốt lõi của document modeling là **embed vs reference**,
lái bởi **access pattern** chứ không phải bởi "quan hệ thực thể".

> 💡 Câu thần chú: **"What's read together, stays together."**
> Relational tối ưu ghi (không trùng). Document tối ưu đọc (không join).

---

## ✅ Embed vs Reference — quyết định bằng gì

| Tiêu chí | Embed (nhúng sub-document) | Reference (lưu id, lookup) |
|----------|----------------------------|----------------------------|
| **Quan hệ** | 1-to-few (order → vài item) | 1-to-many lớn / many-to-many |
| **Đọc cùng nhau?** | Có — luôn đọc kèm | Không — đọc độc lập |
| **Cardinality** | Bounded (vài chục) | Unbounded (1-to-squillions) |
| **Update tần suất** | Sub-doc ít đổi | Đổi nhiều → tránh ghi lại cả parent |
| **Trùng lặp chấp nhận?** | Có (denormalize) | Không (data shared, 1 nguồn) |

**Quy tắc ngón tay cái (MongoDB official)**:
- **1-to-few** → embed (vd địa chỉ trong user).
- **1-to-many** → reference (vd order → product, product dùng lại nhiều order).
- **1-to-squillions** → reference NGƯỢC (vd log → host: log giữ hostId, đừng nhồi 1 triệu log vào host doc; document có giới hạn 16MB).

> ⚠️ **Cạm bẫy embed**: nhồi mảng unbounded vào 1 document → document phình tới
> giới hạn **16MB** + mỗi update ghi lại cả document. Embed chỉ cho bounded.

---

## 🔴 Vì sao EAV trong SQL là anti-pattern

Flexible attributes (TV: `resolution`, áo: `size/color`) ép vào relational có 3 cách, 2 cách tệ:

```text
A) Cột cứng mỗi attribute:
   products(id, name, screen_size, resolution, panel, size, color, material, ...)
   → mỗi category vài chục cột, đa số NULL. Thêm category = ALTER TABLE. ❌

B) EAV table:
   product_attributes(product_id, attr_key, attr_value)
   → "TV nào 4K?" = SELECT p.* FROM products p
       JOIN product_attributes a1 ON a1.product_id=p.id
            AND a1.attr_key='resolution' AND a1.attr_value='4K'
       JOIN product_attributes a2 ON ...  (mỗi điều kiện = 1 self-join)
   → mất type (mọi value là TEXT), index kém, query 3 điều kiện = 3 join. ❌

C) JSONB column (Postgres, Day 3):
   products(id, name, attributes JSONB)
   WHERE attributes->>'resolution' = '4K'   + GIN index
   → ổn! Postgres JSONB ĐÃ là "document trong relational". ✅ (đủ ở volume vừa)

D) Document store (Mongo, Day 23):
   { name, categorySlug, attributes: { resolution: "4K", ... } }
   db.find({ "attributes.resolution": "4K" })
   → dot-notation field lồng nhau như field thường, index được, scale đọc ngang. ✅
```

> 🧠 **Điểm phỏng vấn senior**: JSONB (C) và Mongo (D) đều giải EAV. Đừng nói
> "phải dùng Mongo cho flexible attributes" — Postgres JSONB làm được. Chọn Mongo
> khi attribute là **query pattern CHÍNH + cần scale đọc ngang + đã tách read-model**.
> Day 23 giữ Postgres JSONB làm **source of truth**, Mongo chỉ là **derived
> read-model** cho catalog detail + attribute filter — không bỏ cái nào.

---

## 🪤 Cạm bẫy document modeling

1. **Denormalize rồi quên sync**: embed brand name vào product, brand đổi tên →
   phải update mọi document. Embed = chấp nhận update fan-out. Nếu đổi nhiều → reference.
2. **Embed unbounded** → 16MB limit + ghi lại cả doc mỗi update.
3. **Model theo quan hệ thực thể thay vì access pattern**: copy y nguyên ERD
   sang Mongo (mỗi bảng 1 collection + reference hết) → mất lợi thế document,
   chỉ còn nhược điểm (không join tốt). Phải model theo **cách đọc**.
4. **Quên: aggregate boundary = document boundary**: thứ cần atomic phải trong
   1 document (single-doc write atomic). Tách ra 2 doc = mất atomic (xem
   [issue 23](../issues/23-mongodb-no-transaction-trap.md)).

---

## ⚔️ Approaches compared — flexible attributes

| Approach | Pros | Cons | Verdict |
|----------|------|------|---------|
| Cột cứng | type-safe, index dễ | NULL la liệt, ALTER mỗi category | ❌ |
| EAV table | schema mở | self-join, mất type, index kém | ❌ anti-pattern |
| Postgres JSONB | ACID, đã có, GIN index | gánh OLTP khi là query chính | ✅ source of truth (Day 3) |
| Mongo derived | document first-class, scale đọc | thêm sync path + storage | ✅ read-model (Day 23) |

---

## 🎤 Trả lời phỏng vấn

**Q: Embed hay reference?**
> "Theo access pattern, không theo ERD. Đọc-cùng-nhau + bounded + 1-to-few →
> embed (1 read, không join). Shared / unbounded / 1-to-many → reference. Ví dụ
> order-items: nếu luôn đọc kèm order và mỗi order vài chục item → embed; nhưng
> product thì reference vì 1 product dùng lại nhiều order + đổi giá thường xuyên,
> embed sẽ phải update fan-out."

**Follow-up: "Flexible attributes thì bắt buộc Mongo?"**
> "Không. Postgres JSONB giải được với GIN index — nó là document-trong-relational.
> Tôi chọn Mongo chỉ khi attribute là query pattern chính + cần scale đọc ngang.
> Ở project tôi, Postgres JSONB vẫn giữ source of truth, Mongo là read-model
> derived — không bỏ ACID của product để chạy theo Mongo."

---

## 🔗 Related

- [Lesson 23 — Mongo when to use](23-mongodb-when-to-use.md)
- [Issue 23 — no-transaction trap](../issues/23-mongodb-no-transaction-trap.md)
- [ADR-011](../decisions/011-mongo-for-analytics-and-flexible-attributes.md)
- Code so sánh: [`Product.java` JSONB attributes](../../services/product-service/src/main/java/com/ecom/product/domain/Product.java) ↔ [`ProductCatalogDocument.java` Mongo](../../services/product-service/src/main/java/com/ecom/product/catalog/ProductCatalogDocument.java)
- Tiền đề: [lesson 06 aggregate-root](06-aggregate-root.md) (aggregate boundary)
