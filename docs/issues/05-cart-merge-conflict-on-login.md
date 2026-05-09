# Issue 05 — Anonymous cart merge conflict khi user login

> **Status**: ✅ Done · 2026-05-09
> **Severity**: 🟡 medium — UX impact, không phải data loss
> **Service**: `cart-service`

## 1. Problem

Anonymous user duyệt site, thêm 2 áo thun M (SKU `TSHIRT-M`) vào cart. Sau đó login vào account đã có sẵn 1 áo thun M trong cart. Câu hỏi: cart sau login nên là **3** áo (sum), **1** (user wins), hay **2** (anonymous wins)?

Mỗi quyết định ảnh hưởng UX khác nhau và phải document được rationale, không phải implicit.

## 2. Symptoms

- User report: "tôi vừa add 2 áo, login xong chỉ thấy 1" (do overwrite anonymous → user).
- Khác user: "tôi đã add 1 từ tuần trước, login xong còn duy nhất 2 áo (anonymous), mất cái cũ".
- Conversion drop ~3% trong session post-login do user mất trust + phải re-add.

## 3. Root cause

KHÔNG có rule conflict-resolution rõ ràng. Lập trình viên đầu tiên implement sẽ chọn **một trong 4 cách** dựa trên gut feel; team sau không biết tại sao chọn cái đó. Khi product owner đổi ý (vd "merge sum"), code không phản ánh được vì rule scattered.

Đây là **business rule chưa được decide**, không phải bug code thuần.

## 4. Approaches compared

| # | Approach | Pros | Cons |
| - | -------- | ---- | ---- |
| A | **User cart wins (overwrite)** | Đơn giản, 1 op (DEL anon) | Mất item anonymous user vừa add → conversion drop, user trust |
| B | **Anonymous wins (overwrite)** | Đơn giản | Mất cart user đã build từ tuần trước → tệ hơn A |
| C ✅ | **Sum quantity per SKU + cap by max** | Không mất item, fair với user effort 2 chiều | Có thể vượt stock available → cần soft-cap; cần logic per-SKU loop |
| D | **UI prompt user choose** | Explicit, transparent | Thêm 1 step, conversion drop ngay điểm critical (post-login moment) |

## 5. Chosen approach + Why

**Approach C — Sum quantity per SKU**, sau đó cap by `cart.max-qty-per-item` (default 999).

Lý do:
- **User effort 2 chiều cùng được tôn trọng**: anon session gần đây + user history trước đó đều có giá trị. Mất bên nào cũng giảm trust.
- **Stock validation không ở merge**: cart không reserve stock; reserve xảy ra ở `placeOrder()` (Day 6). Merge chỉ là UI state, soft-cap đủ.
- **Idempotent**: anon key bị DEL sau merge → call lần 2 không có gì merge thêm. Không cần dedup token client-side phức tạp.

Với edge case tổng SKU vượt `max-items-per-cart` (100), log warning và để Redis tự nhiên (không trim) — known issue, document trong code [`CartService.merge:82-90`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java). Day 7 sẽ trim deterministic theo `addedAt`.

## 6. Fix

```java
// services/cart-service/src/main/java/com/ecom/cart/service/CartService.java
@Transactional
public CartResponse merge(String anonToken, UUID userId) {
    CartId.Anonymous anon = new CartId.Anonymous(anonToken);
    CartId.User user = new CartId.User(userId);

    Map<Object, Object> anonItems = redis.opsForHash().entries(anon.redisKey());
    if (anonItems.isEmpty()) return readCart(user);

    HashOperations<String, Object, Object> ops = redis.opsForHash();
    for (Map.Entry<Object, Object> e : anonItems.entrySet()) {
        String sku = e.getKey().toString();
        int qty = parseQtyOrZero(e.getValue());
        if (qty <= 0) continue;

        Long merged = ops.increment(user.redisKey(), sku, qty);
        if (merged != null && merged > props.maxQtyPerItem()) {
            ops.increment(user.redisKey(), sku, -(merged - props.maxQtyPerItem()));
        }
    }
    refreshTtl(user.redisKey());
    redis.delete(anon.redisKey());
    return readCart(user);
}
```

API endpoint: `POST /cart/merge` với header `Authorization: Bearer <jwt>` + `X-Cart-Token: <anonToken>`. Frontend gọi ngay sau login response.

Test: [`CartConcurrencyIT.mergeAnonymousIntoUser_sumQuantityPerSku`](../../services/cart-service/src/test/java/com/ecom/cart/CartConcurrencyIT.java) verify SKU-A overlap (2+3=5), SKU-B disjoint (1), SKU-C disjoint (4), anon key bị DEL.

## 7. Prevention

- **Rule trong code**, không scattered: merge logic chỉ ở `CartService.merge`, controller không có if/else conflict.
- **Test**: 3 scenario — empty anon, all-overlap, mixed overlap+disjoint — phải đi cùng feature.
- **Doc**: ADR-004 link tới issue này; tương lai đổi rule (vd UI prompt) phải tạo ADR mới, không sửa silent.
- **Frontend contract**: anon token là UUID frontend mint ở first visit (localStorage), không phải backend generate. Đảm bảo idempotent merge.

## 8. Trade-off accepted

- **Có thể vượt stock available sau merge**: user thấy 5 áo trong cart nhưng stock chỉ 3 → khi place-order sẽ fail ở inventory.reserve. Frontend nên show warning sớm bằng cách query inventory ở cart view, nhưng merge KHÔNG đụng inventory (không reserve trong cart) — đó là design quyết định. Trade-off này rẻ hơn việc lock stock từ lúc add cart (gây flash sale "stock đang giữ ảo" 7 ngày).
- **Edge case >100 SKU sau merge**: log warning, không crash. Day 7 trim deterministic.

## 9. Related

- Code: [`CartService.merge`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java)
- Test: [`CartConcurrencyIT`](../../services/cart-service/src/test/java/com/ecom/cart/CartConcurrencyIT.java)
- API: [`CartController.merge`](../../services/cart-service/src/main/java/com/ecom/cart/web/CartController.java)
- ADR: [`decisions/004-redis-primary-for-cart.md`](../decisions/004-redis-primary-for-cart.md)
- Lesson: [`lessons/05-redis-cart-vs-db-cart.md`](../lessons/05-redis-cart-vs-db-cart.md)
- Interview: [`interview/day-05-cart.md`](../interview/day-05-cart.md)
