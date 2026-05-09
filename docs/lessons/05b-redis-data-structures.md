# Lesson 05b — Redis data structures cho cart use case

> **Status**: ✅ Done · 2026-05-09
> **Day**: 5 · ngầm trong [`CartService`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java)

## 🎯 TL;DR

Cart là `{cartId → {sku → qty}}`. **Hash** là chính xác data structure đó: `HSET cart:user:42 SKU-A 3`. Đừng dùng String JSON (`SET cart:user:42 '{"SKU-A":3}'`) vì mất atomicity field-level và phải read-modify-write toàn cart cho mỗi mutation.

## ✅ Khi nào dùng Hash

- Object có nhiều field, cần read/write từng field độc lập.
- Cần tăng/giảm 1 field counter atomic (`HINCRBY`) — vd cart qty, view count.
- Read toàn object 1 lần (`HGETALL`) phổ biến.

## ❌ Khi nào KHÔNG dùng Hash

- Cần TTL từng field (Redis chỉ có TTL key-level, không field-level). Workaround: dùng Sorted Set với score=expireAt + cron sweep, hoặc tách key/field. Đối với cart: TTL cart-level đủ, không cần per-SKU.
- Cần range query (`SKU bắt đầu A%`, `qty > 5`) — Hash không có index. Dùng Sorted Set + secondary structure, hoặc chuyển sang DB phù hợp.
- Field value lớn (>1KB) — Hash với value lớn không nén tốt, ảnh hưởng O(N) cho HGETALL.

## ⚠️ Cạm bẫy

| Cạm bẫy | Hậu quả | Cách tránh |
|---|---|---|
| Dùng String JSON cho cart | Lost-update vì phải HGET-modify-HSET | Hash + HINCRBY |
| Dùng List cho cart | Append-only, không random access theo SKU | Hash |
| Dùng Set cho cart | Set lưu unique element, không có qty | Hash với value=qty |
| Sorted Set cho cart không cần ranking | Overhead skiplist (O(log N)) cho không có lý do | Hash O(1) |

## 🆚 Approaches compared

| Structure | Atomicity field | TTL field | Read toàn cart | Update 1 field |
|-----------|-----------------|-----------|----------------|----------------|
| **Hash** ✅ | HINCRBY/HSET atomic | Không (key-level) | HGETALL O(N) | O(1) |
| String JSON | Phải read-modify-write | Không | GET O(1) | Read-modify-write O(N) lost-update |
| Hash JSON value | HSET atomic field nhưng value JSON phải parse | Không | HGETALL + parse N JSON | Phải HGET-parse-modify-HSET |
| Sorted Set (score=qty) | ZINCRBY atomic | Không | ZRANGE | ZINCRBY O(log N) |

> Sorted Set dùng được nhưng overhead skiplist không cần thiết — chọn Hash.

## 🎤 Trả lời phỏng vấn

**Q**: "Tại sao Hash thay vì String JSON?"
**A**: 2 lý do. (1) Atomicity field-level: `HINCRBY` tăng qty 1 SKU không ảnh hưởng SKU khác; String JSON phải GET → parse → modify → SET, race condition mất update. (2) Bandwidth: thêm 1 SKU không phải gửi lại toàn cart.

**Q**: "Cart sống 7 ngày — refresh TTL khi nào?"
**A**: Chỉ ở mutation (add/update/remove/merge). Read không refresh — nếu refresh ở GET, cart không bao giờ expire khi user idle browse, vi phạm rule "7 ngày inactivity → drop". Code: [`CartService.refreshTtl`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java).

## 🔗 Related

- Lesson: [`lessons/05-redis-cart-vs-db-cart.md`](05-redis-cart-vs-db-cart.md)
- Code: [`CartService.java`](../../services/cart-service/src/main/java/com/ecom/cart/service/CartService.java)
- ADR: [`decisions/004-redis-primary-for-cart.md`](../decisions/004-redis-primary-for-cart.md)
