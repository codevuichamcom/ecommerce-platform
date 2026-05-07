# 🔥 Issue 03 — Entity leak qua REST response → LazyInitializationException

> **Day 3 — production scenario simulation.** Senior dev cũ trong team
> return JPA entity trực tiếp từ controller → 30% request `/products`
> trả 500. Đây là pattern AI/junior siêu hay sai.

## 1. Problem

Endpoint `GET /products` intermittent fail với HTTP 500. Frontend nhận
được envelope `{"success":false, "error":{...}}` không có data, traceId
log lại error JSON serialization. Lỗi không reproduce 100% trên local
nhưng tỷ lệ cao trên staging.

## 2. Symptoms

- Log application:
  ```
  could not initialize proxy [com.ecom.product.domain.Category#...] - no Session
  org.hibernate.LazyInitializationException
  ```
- Metric: `/products` error rate ~30%, P99 spike khi cache miss.
- Thread dump: stack trace từ Jackson `BeanSerializerBase.serialize` →
  Hibernate proxy `ByteBuddyInterceptor` → throw.

## 3. Root cause

Controller code cũ:

```java
@GetMapping
public ApiResponse<Page<Product>> list(Pageable pageable) {
    return ApiResponse.ok(productRepository.findAll(pageable));
}
```

Vấn đề kỹ thuật:

1. `Product.category` là `@ManyToOne(fetch = LAZY)` — Hibernate chỉ load
   khi access getter.
2. Controller method return → Spring transaction (declarative qua
   `@Transactional` ở service) ĐÃ COMMIT và session đóng.
3. Jackson serialize `Product` → gọi `getCategory()` → Hibernate cố lazy
   load nhưng session đã đóng → `LazyInitializationException`.
4. Thường "nhảy" 30% vì local có khi `open-in-view=true` (default) che
   bug; staging tắt OSIV nên expose.

Đây KHÔNG chỉ là bug performance — đây là **architectural smell**:
- Schema DB leak qua API contract (đổi field DB → vỡ client).
- Có thể leak field nhạy cảm (vd `auditLog`, `internalNotes`) khi entity thêm trong tương lai.
- Hibernate proxy field (`hibernateLazyInitializer`, `handler`) lọt vào JSON.

## 4. Approaches compared

| Approach                                       | Pros                                              | Cons                                                                                |
| ---------------------------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------------------- |
| **A. Bật `open-in-view=true`**                 | Fix triệu chứng nhanh, 1 dòng config              | Hide bug, mỗi serialize trigger N+1 query, transaction kéo dài tới response flush, không pass review senior |
| **B. `JOIN FETCH` + return entity**             | Hết LazyInitException                              | Vẫn leak schema, vẫn lộ proxy field, schema coupling client-server vẫn còn          |
| **C. DTO + MapStruct + map TRONG transaction** | Boundary rõ, immutable DTO record, no leak, compile-time codegen | Boilerplate (MapStruct giảm), thêm 1 layer mapping                                  |
| D. Jackson `@JsonIgnore` trên lazy field       | Nhanh, ít code                                    | Mỗi field ignore là 1 patch tay → drift; vẫn leak field khác khi entity thêm field   |

## 5. Chosen approach + Why

**(C) DTO + MapStruct.** Lý do gắn với context project:

- Day 3 vừa scaffold service, schema sẽ thay đổi nhiều (Day 23 có thể migrate
  `attributes` sang Mongo). Boundary DTO ổn định cho client; entity thay
  đổi không phá API contract.
- MapStruct compile-time codegen — không reflection runtime cost; thêm
  field forget map = compile warning (configurable thành error).
- DTO record + `@JsonInclude(NON_NULL)` đã có sẵn ở `common-lib`.
- Set tiền lệ cho 8 service còn lại — khỏi lặp lại bug này ở
  inventory/order/cart.

## 6. Fix

Code reference:
- [`ProductMapper.java`](../../services/product-service/src/main/java/com/ecom/product/mapper/ProductMapper.java) — interface MapStruct với `@Mapping(target = "categoryId", source = "category.id")`.
- [`ProductService.java`](../../services/product-service/src/main/java/com/ecom/product/service/ProductService.java) — gọi `productMapper.toResponse(product)` BÊN TRONG `@Transactional(readOnly=true)` để LAZY association còn session.
- [`ProductController.java`](../../services/product-service/src/main/java/com/ecom/product/web/ProductController.java) — return `ProductResponse` (record), không bao giờ return `Product` entity.
- [`application.yml`](../../services/product-service/src/main/resources/application.yml) — `spring.jpa.open-in-view: false` để fail-fast nếu lỡ leak.

## 7. Prevention

1. **`open-in-view: false` ở mọi service** — fail-fast staging trước khi prod.
2. **ArchUnit test** (Day 7 sẽ add):
   ```java
   noClasses().that().resideInAPackage("..web..")
       .should().dependOnClassesThat().resideInAPackage("..domain..")
       .as("Controller không return @Entity");
   ```
3. **Code review checklist** — append vào `docs/review/ai-junior-traps.md`:
   *"Controller method return type là `@Entity` hoặc `Page<@Entity>` → reject."*
4. **Integration test** assert response không có field `hibernateLazyInitializer`
   (xem [`ProductServiceIntegrationTest.java`](../../services/product-service/src/test/java/com/ecom/product/ProductServiceIntegrationTest.java)).

## 8. Trade-off accepted

- **Boilerplate thêm**: mỗi entity cần 1-3 DTO (Create / Update / Response) + 1 mapper. MapStruct giảm hand-rolled code nhưng vẫn phải khai báo `@Mapping`. Chấp nhận vì tính kỷ luật boundary lớn hơn cost.
- **Mapping inside transaction**: phải nhớ map sang DTO trong service layer (có `@Transactional`), không phải ở controller. Reviewer dễ catch — review trap ở `ai-junior-traps.md`.

## 9. Related

- Code: [`ProductMapper.java`](../../services/product-service/src/main/java/com/ecom/product/mapper/ProductMapper.java), [`ProductService.java`](../../services/product-service/src/main/java/com/ecom/product/service/ProductService.java)
- Lesson liên quan: [`lessons/03-pagination-offset-vs-cursor.md`](../lessons/03-pagination-offset-vs-cursor.md)
- Interview: [`interview/day-03-product.md`](../interview/day-03-product.md)
- N+1 deep-dive (Day 17): `lessons/17-jpa-n-plus-one.md` ⏳ — same root family (lazy loading)
- Code review traps: [`review/ai-junior-traps.md`](../review/ai-junior-traps.md)
