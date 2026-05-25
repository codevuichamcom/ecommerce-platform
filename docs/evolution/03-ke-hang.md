# Chương 3 · 📦 Kệ hàng và nghệ thuật phân trang

**Day 3 — Product Service**

---

> *"Một cửa hàng không có hàng chỉ là một căn phòng trống với cái cổng khóa."*

---

## Bối cảnh

Auth đã gác cổng. Giờ cần thứ để bán. `product-service` nghe đơn giản — CRUD product, CRUD category, search. Nhưng những quyết định "nhỏ" ở đây sẽ quyết định hệ thống sống hay chết khi scale lên 1 triệu sản phẩm.

---

## Cái bẫy đầu tiên: Entity Leak

Ngày đầu viết API, ai cũng từng làm thế này:

```java
@GetMapping("/{id}")
public Product getProduct(@PathVariable Long id) {
    return productRepository.findById(id).orElseThrow();
    // ↑ Trả thẳng JPA entity ra response. Đơn giản. Nhanh. VÀ SAI.
}
```

Tại sao sai?

1. **Hibernate proxy leak** — `category` field là lazy proxy, Jackson serialize nó → `hibernateLazyInitializer` xuất hiện trong JSON response. Client parse fail.
2. **Internal field exposure** — `createdBy`, `version`, `deletedAt` lộ ra ngoài. Attacker biết schema.
3. **Coupling** — thay đổi entity = thay đổi API contract. Mọi client break.

**Fix**: MapStruct compile-time mapping → DTO record. Entity ở trong, DTO ra ngoài. Tường lửa giữa persistence layer và API layer.

```java
// Entity stays inside
@Entity class Product { ... }

// DTO goes outside — immutable, explicit, safe
public record ProductResponse(
    Long id, String name, String sku,
    BigDecimal price, Map<String, Object> attributes
) {}
```

Thêm `spring.jpa.open-in-view: false` — tắt OSIV, chặn lazy loading ngoài transaction. Nếu quên load relation trong service layer → fail ngay, không âm thầm N+1 query.

---

## JSONB — chuẩn bị cho tương lai

Product có attributes đa dạng: TV có `screen_size`, `resolution`. Áo có `size`, `color`, `material`. Laptop có `ram`, `cpu`, `storage`.

3 cách tiếp cận:

| Approach | Pros | Cons |
|----------|------|------|
| 50 nullable columns | Query nhanh | Schema cứng, 90% column NULL |
| EAV (Entity-Attribute-Value) | Flexible | Query chậm, JOIN hell, no type safety |
| **JSONB column** | Flexible + queryable + indexable | Không FK constraint trên attributes |

Chọn JSONB. Postgres 16 index GIN trên JSONB, query `WHERE attributes->>'brand' = 'Apple'` vẫn dùng index. Day 23 sẽ migrate phần này sang MongoDB khi cần aggregation pipeline phức tạp hơn — nhưng JSONB là stepping stone hoàn hảo.

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private Map<String, Object> attributes;
```

---

## Pagination — nghệ thuật bị đánh giá thấp

*"Cho tôi tất cả products"* — câu nói phá sập database nhanh nhất.

Day 3 implement offset pagination với **3 lớp phòng thủ**:

1. **Size cap**: `@Max(100)` — client request `size=999999`? Reject.
2. **Sort whitelist**: chỉ cho sort theo `name`, `price`, `createdAt`. Sort theo `password`? Reject.
3. **Default sensible**: không truyền gì → page 0, size 20, sort by createdAt DESC.

```
GET /products?page=0&size=20&sort=price,asc
```

Tại sao offset mà không cursor? Vì Day 3 data ít, offset đủ tốt. Day 18 sẽ chứng minh offset chết ở 10M rows và migrate sang keyset/cursor pagination. **Biết giới hạn của tool mình đang dùng** quan trọng hơn dùng tool phức tạp nhất từ đầu.

---

## Search — tạm đủ, biết sẽ thay

```sql
WHERE LOWER(name) LIKE LOWER('%keyword%')
```

Chậm? Đúng. Không dùng index? Đúng. Nhưng Day 3 có 100 products. Day 16 sẽ thêm GIN trigram index. Day 22 sẽ thay hoàn toàn bằng Elasticsearch. Mỗi bước tiến hóa có lý do, có benchmark, có so sánh before/after.

> ⚠️ **Cạm bẫy**: đừng optimize quá sớm, nhưng **biết** mình đang chấp nhận technical debt nào. LIKE search là conscious debt, không phải ignorance.

---

## Kết thúc ngày 3

```
📊 Scorecard:
├── Services:        2 (auth + product)
├── Endpoints:       ~12 (CRUD + search + pagination)
├── Traps dodged:    Entity leak, OSIV, unbounded pagination
├── Conscious debt:  LIKE search (sẽ trả Day 16 + Day 22)
├── Docs:            4 (lesson pagination, perf search, issue entity-leak, interview)
└── Vibe:            "Kệ hàng đã có. Nhưng ai canh kho?"
```

> 💡 **Senior vs Junior**: Junior viết CRUD xong nói "done". Senior viết CRUD xong nói "done, nhưng search sẽ chết ở 100k rows — đây là plan migrate."

---

*→ Hàng đã trên kệ. Nhưng ai đảm bảo không bán quá số lượng tồn kho?...*
