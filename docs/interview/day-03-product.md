# 🎤 Day 3 — Product service interview Q&A

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN — fictional ecommerce tier 2 ở VN (mô phỏng Tiki/Shopee giai đoạn Series A, ~50 dev, 100k DAU).
- **Role giao việc**: PM Linh — ticket `CAT-103: Product CRUD MVP`, đẩy lên do marketing chuẩn bị campaign tháng sau cần cập nhật catalog.
- **Bạn**: backend dev catalog squad, ownership full service `product-service` (schema → API → docs).
- **Reviewer**: Tech Lead Hùng (8y senior, ex-Lazada) — nổi tiếng soi N+1, pagination, DTO leak entity, schema migration không backwards-compat.
- **Deadline**: 1 sprint (5 ngày), demo CRUD product end-to-end + admin Postman collection.
- **Constraint thực tế**:
  - Schema phải support flexible attribute (TV vs áo) nhưng KHÔNG được tạo EAV-table (Hùng đã reject ở review trước).
  - Auth dùng chung JWT của `auth-service` — không tự đẻ user table.
  - 1M product trong 6 tháng tới → schema/index không được "rewrite từ đầu" lúc đó (decision phải ngấm cho Day 16/22).
- **Definition of Done**:
  - 6 endpoint CRUD + search work end-to-end với JWT auth.
  - Flyway migration deterministic (rollback plan tài liệu).
  - Pagination + sort whitelist (chống user inject sort field).
  - DTO + MapStruct, không leak entity.
  - Docs: 1 lesson pagination, 1 performance note search, 1 issue entity-leak, interview Q&A.

> Liên hệ Sotatek: pattern y hệt khi build catalog cho client e-commerce
> tier 2 — schema decision phase quyết định 70% rework sau 6 tháng. Day 3
> này drill mental model đó.

---

## Q1. "Pagination 10M product, page 50000 chậm — fix sao?"

**Strong answer (Việt + English term)**

Đây là classic offset pagination problem. `OFFSET 1_000_000` bắt Postgres
scan + discard 1M rows trước khi return 20 rows → P99 nhảy từ 5ms lên
nhiều giây. Fix: chuyển sang **keyset (cursor) pagination** —
`WHERE (created_at, id) < (last_value, last_id) ORDER BY created_at DESC,
id DESC LIMIT 20`. Index composite `(created_at DESC, id)` (đã có sẵn ở
schema Day 3 — `idx_products_created`).

Trade-off: keyset không support jump-to-page → UX phải đổi (infinite
scroll hoặc next/prev only). Nếu sản phẩm bắt buộc giữ jump-to-page →
cap max page (vd ≤ 100), deeper search push qua filter / search engine.

**Follow-up traps**:
- *"Sao không tăng `LIMIT` lên 1000?"* — vẫn không scale, memory tăng tuyến tính, payload to, render lag client.
- *"Sao không cache total count?"* — count cache stale, invalidation phức tạp khi insert/delete liên tục, vẫn phải tính lại.
- *"Cursor `(created_at)` là đủ?"* — KHÔNG, 2 row cùng millisecond skip 1; phải composite `(created_at, id)` để tie-break.

Liên kết: [`lessons/03-pagination-offset-vs-cursor.md`](../lessons/03-pagination-offset-vs-cursor.md), Day 18 sẽ implement migration.

---

## Q2. "Vì sao không return entity từ controller?"

**Strong answer**

3 lý do gắn nhau:

1. **`LazyInitializationException`**: entity escape transaction; Jackson
   serialize lazy association sau khi session đóng → throw. Đây là bug
   production thực sự (xem [`issues/03-entity-leak-in-response.md`](../issues/03-entity-leak-in-response.md)).
2. **Schema coupling**: client decode trực tiếp DB column structure. Đổi
   field DB = vỡ client. API contract phải độc lập với storage.
3. **Security leak**: thêm field `internalNote` / `auditLog` vào entity →
   tự động lộ ra REST. Phải nhớ `@JsonIgnore` từng field — drift.

**Strong**: dùng DTO record + MapStruct (compile-time, không reflection).
Map sang DTO BÊN TRONG `@Transactional` (service layer) để lazy association
còn session. Set `spring.jpa.open-in-view: false` fail-fast nếu lỡ leak.

**Follow-up traps**:
- *"Bật `open-in-view=true` rồi không cần DTO?"* — band-aid, mỗi serialize trigger N+1, transaction kéo dài tới response flush, fail review senior.
- *"`@JsonIgnore` trên field lazy đủ chưa?"* — vẫn leak field khác khi entity thêm field mới; mỗi field thêm là 1 patch tay → drift.
- *"MapStruct vs ModelMapper?"* — MapStruct compile-time codegen (zero reflection); ModelMapper reflection runtime, mistype field không catch compile time.

---

## Q3. "Flexible attribute (TV: screen_size; áo: size+color) — chọn JSONB / EAV / Mongo?"

**Strong answer**

3 option, 3 context khác nhau:

| Option                      | Phù hợp khi                                                               | Cạm bẫy                                                            |
| --------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| **JSONB Postgres**          | Attribute < 30 loại, query nội thuộc tính ÍT, cần ACID + transaction      | Index nội thuộc tính (GIN expression) tốn write; query phức tạp viết khó  |
| **EAV table** (entity-attribute-value) | (Hiếm) khi attribute schema thật sự dynamic + cần normalize | Anti-pattern: join chính nó N lần, query slow, không type-safe |
| **MongoDB document**        | Attribute schema rất đa dạng (TV vs áo vs đồ ăn), query nội thuộc tính NHIỀU, không cần cross-document transaction | Operational cost (thêm 1 storage), eventual consistency, no FK     |

**Day 3 chọn JSONB** vì: (a) catalog ecommerce VN attribute đếm được
(<50 loại), (b) cần ACID khi update price + attribute cùng lúc, (c)
query nội thuộc tính có thể support đủ qua GIN expression index Day 16.
Day 23 sẽ benchmark thật vs Mongo và làm ADR-007.

**Follow-up traps**:
- *"Tại sao không đẻ table riêng cho mỗi category attribute?"* — không scale với hundreds of category; mỗi loại sản phẩm mới là 1 migration.
- *"JSONB không index được?"* — sai, GIN trên `jsonb_path_ops` index được path-value query; trade-off là write amplification + size.
- *"Mongo schemaless không phải lúc nào cũng tốt?"* — đúng. Schemaless dễ drift (mỗi service ghi field khác nhau). Schema validation Mongo 4+ là cần thiết.

---

## Q4. "PUT vs PATCH vs POST — khác gì?"

**Strong answer**

- **POST**: tạo resource mới, không idempotent (gọi 2 lần = 2 resource).
  Idempotency cần explicit qua **idempotency key** header (Day 10).
- **PUT**: full replace, **idempotent** — gọi 100 lần state cuối giống nhau.
  Day 3 dùng PUT cho update product (full replace).
- **PATCH**: partial update (RFC 6902 JSON Patch hoặc RFC 7396 merge patch).
  Phức tạp hơn, parse JSON Patch hoặc merge patch — Day 3 không cần.

**Trap quan trọng**: PUT idempotent KHÔNG có nghĩa là safe (an toàn không
đổi state). PUT idempotent (cùng input → cùng kết quả) nhưng vẫn modify
state. GET là safe + idempotent.

**Follow-up traps**:
- *"Idempotent có cần ở DB?"* — POST với idempotency-key cần unique constraint trên key + transaction để giữ atomicity. Day 10 (payment) sẽ implement chi tiết.
- *"Tại sao không cho client gửi cả PUT + PATCH support?"* — over-engineer Day 3; KISS — thêm khi business cần.

---

## Q5. "MapStruct vs Lombok @Builder + manual vs ModelMapper?"

**Strong answer**

| Tool             | Cost                              | Catch lỗi sớm                                | Day 3 verdict        |
| ---------------- | --------------------------------- | -------------------------------------------- | -------------------- |
| MapStruct        | AP codegen, zero runtime reflection | ✅ Compile-time: thiếu field warning/error  | ✅ Chosen             |
| Manual + Builder | 0 dep, đọc rõ                     | ❌ Manual maintain                           | OK cho 1-2 mapping   |
| ModelMapper      | Reflection runtime                | ❌ Mistype phát hiện runtime                  | ❌ Reject             |

**MapStruct**: bind Lombok qua `lombok-mapstruct-binding` AP. Spring DI
qua `@Mapper(componentModel = "spring")`. Custom mapping qua
`@Mapping(target=..., source=...)` hoặc `default` method.

**Follow-up traps**:
- *"Lombok-MapStruct binding cần thiết?"* — yes, MapStruct cần thấy getter/setter; Lombok generate sau khi MapStruct chạy nếu order AP sai → fail. Binding fix order.
- *"Có cần `MapStruct unmappedTargetPolicy = ERROR`?"* — recommend yes; thiếu field forget map = compile error, force dev review.

---

## 🧠 Senior mindset notes

- **AI/junior pitfall**: AI siêu hay return `productRepository.findAll()` từ controller → entity leak. Phải có ArchUnit hoặc reviewer catch ngay.
- **Scale 10x note**: 10M product → offset chết → cần keyset (Day 18) + ES (Day 22) + 2-tier cache (Day 15). Đừng over-engineer Day 3 nhưng schema phải để mở: timestamp index ready cho cursor, JSONB ready cho mongo migrate.
- **Trade-off non-obvious**: chọn JSONB cho `attributes` Day 3 thay vì cột riêng — accept query nội thuộc tính chậm hơn (cần GIN index), đổi lấy không cần migration mỗi khi marketing thêm attribute mới. Khi đếm được rằng attribute schema hợp nhất hơn 80% → re-migrate sang cột riêng (BackwardsCompat).

---

## 🤖 AI Playbook

- **AI làm tốt phần nào**: scaffold entity / DTO record / MapStruct interface, Flyway DDL boilerplate, basic CRUD test class, validation annotation. Repetitive structural code — match training data.
- **Prompt mẫu** (≤4 dòng):
  > *"Generate Spring Data JPA entity Product với các field [sku, name, slug, price BigDecimal, currency CHAR(3), category Many-to-One LAZY, status enum, attributes JSONB Map<String,Object>], extends BaseEntity từ common-lib, dùng `@JdbcTypeCode(SqlTypes.JSON)` cho attributes, Lombok `@Getter @Setter @Builder @NoArgsConstructor(PROTECTED) @AllArgsConstructor`."*
- **Risk khi AI làm phần này**:
  - AI hay quên `@Version` (BaseEntity đã có nên ok ở project này, nhưng AI không biết).
  - Trả về `class` thay vì `record` cho DTO.
  - MapStruct interface quên `componentModel="spring"` → bean Spring không inject được.
  - Return entity từ controller (issue 03) — AI siêu hay sai.
  - Pagination quên cap max size → user gửi `size=10000` enumerate cả DB.
- **Cách validate output**:
  - Chạy `./gradlew :services:product-service:build` (compile MapStruct generate code).
  - Check `services/product-service/build/generated/sources/annotationProcessor/.../ProductMapperImpl.java` có exist không.
  - Run `ProductServiceIntegrationTest` (env `RUN_PRODUCT_INTEGRATION_TESTS=true`) — assert `hibernateLazyInitializer` không tồn tại trong response JSON.
  - Manual review: search `productRepository.findAll` ở controller → reject ngay.

---

## 🔗 Related

- Code: [`services/product-service/`](../../services/product-service/)
- Lesson: [`lessons/03-pagination-offset-vs-cursor.md`](../lessons/03-pagination-offset-vs-cursor.md)
- Performance: [`performance/03-product-search-indexing.md`](../performance/03-product-search-indexing.md)
- Issue: [`issues/03-entity-leak-in-response.md`](../issues/03-entity-leak-in-response.md)
- Day 4 next: inventory DDD + optimistic lock — sẽ pattern khác hẳn (aggregate root, domain event)
