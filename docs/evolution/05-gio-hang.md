# Chương 5 · 🛒 Giỏ hàng — tốc độ là tất cả

**Day 5 — Cart Service (Redis)**

---

> *"Giỏ hàng là thứ duy nhất trong ecommerce mà user tương tác nhiều nhất nhưng quan tâm ít nhất. Nó phải nhanh đến mức vô hình."*

---

## Bối cảnh

Cart không phải order. Không cần ACID. Không cần survive server restart. Mất cart ≠ mất tiền, mất order mới là mất tiền. Cart cần đúng 1 thứ: **NHANH**.

User add item — expect response < 50ms. Mở cart — expect load < 100ms. Bất kỳ delay nào = user bỏ đi = mất revenue.

Postgres cho cart? Connection pool, transaction, WAL write, fsync — tất cả cho 1 cái giỏ tạm thời? Overkill.

**Redis.** In-memory. Sub-millisecond. Perfect fit.

---

## Kiến trúc: Redis Hash — elegant simplicity

```
Key:    cart:user:12345
Field:  SKU-001  →  Value: 2
Field:  SKU-007  →  Value: 1
Field:  SKU-042  →  Value: 3
```

Tại sao Hash mà không phải String (JSON blob)?

| | String (JSON) | Hash |
|---|---|---|
| Add 1 item | GET → parse → modify → SET (read-modify-write) | `HINCRBY cart:user:123 SKU-001 1` (atomic!) |
| Get 1 item | GET → parse → extract | `HGET cart:user:123 SKU-001` |
| Race condition | 2 tab cùng GET-modify-SET → lost update | `HINCRBY` atomic — impossible to lose |
| Memory | 1 big JSON string | Redis ziplist optimization cho small hash |

**`HINCRBY` là hero.** Atomic increment tại field level. 2 tab cùng add cùng 1 SKU → quantity = 2. Không lost update. Không lock. Không retry. Redis đảm bảo.

---

## Bài toán merge — đơn giản trên giấy, tricky trong thực tế

User browse anonymous (chưa login), add 3 item vào cart. Rồi login. Chuyện gì xảy ra?

```
Cart anonymous (cart:anon:abc):     SKU-001 × 2, SKU-007 × 1
Cart user cũ (cart:user:123):       SKU-001 × 1, SKU-042 × 3
                                         ↓ MERGE
Cart user sau merge (cart:user:123): SKU-001 × 3, SKU-007 × 1, SKU-042 × 3
```

Rule: **sum quantity per SKU**. Cùng SKU → cộng dồn. Khác SKU → giữ nguyên. Anonymous cart → xóa sau merge.

Nhưng edge case:
- Sum vượt `maxQtyPerItem=999`? → Cap tại 999, rollback phần dư
- Merge xong cart có > 100 items? → Reject merge, giữ user cart cũ
- Merge giữa chừng Redis die? → Anonymous cart vẫn còn (chưa DEL), retry safe

---

## TTL strategy — subtle but important

```
TTL: 7 ngày
Refresh: CHỈ khi mutate (add/update/remove), KHÔNG khi read
```

Tại sao không refresh khi read? Vì nếu refresh khi read, user mở app hàng ngày (trigger GET cart) → cart sống mãi → Redis memory leak dần. Cart zombie.

Refresh khi mutate = user còn tương tác thật → cart đáng giữ. User bỏ quên 7 ngày → cart tự biến mất. Clean.

---

## Sealed `CartId` — namespace tách biệt

```java
public sealed interface CartId permits Anonymous, Authenticated {
    record Anonymous(String token) implements CartId {
        public String toRedisKey() { return "cart:anon:" + token; }
    }
    record Authenticated(Long userId) implements CartId {
        public String toRedisKey() { return "cart:user:" + userId; }
    }
}
```

Pattern matching khi cần xử lý khác nhau:

```java
switch (cartId) {
    case Anonymous a  -> // cho phép không cần JWT
    case Authenticated u -> // verify JWT ownership
}
```

Compiler guarantee: không bao giờ quên handle 1 case.

---

## ADR-004: Tại sao Redis primary, không phải Redis cache?

4 alternatives được cân nhắc:

| Approach | Verdict |
|----------|---------|
| Postgres only | ❌ Overkill cho ephemeral data |
| Postgres + Redis cache | ❌ Complexity không justify — cart không cần durability |
| **Redis primary** | ✅ Chosen — fast, simple, acceptable loss |
| Redis + periodic snapshot to PG | ❌ Over-engineering cho MVP |

Key insight: **Cart là ephemeral data.** Mất cart = user add lại (annoying, not catastrophic). Mất order = mất tiền (catastrophic). Dùng đúng tool cho đúng mức độ quan trọng.

---

## Kết thúc ngày 5

```
📊 Scorecard:
├── Services:        4 (auth + product + inventory + cart)
├── Storage:         Postgres (3 services) + Redis (cart)
├── Atomic ops:      HINCRBY (no lost update), EXPIRE (auto cleanup)
├── Edge cases:      Merge conflict, cap overflow, TTL zombie
├── Concurrency IT:  100-thread add cùng SKU → correct sum
├── Docs:            5 (ADR-004, 2 lessons, issue merge, interview)
└── Vibe:            "Giỏ hàng nhanh như chớp. User không biết Redis tồn tại — và đó là thành công."
```

> 💡 **Senior insight**: Không phải mọi data đều cần cùng mức durability. Cart (ephemeral) ≠ Order (permanent) ≠ Analytics (append-only). Chọn storage theo **data lifecycle**, không phải theo "cái gì quen dùng".

---

*→ Giỏ hàng đã có. Kệ hàng đã có. Kho đã canh. Giờ cần thứ kết nối tất cả: đơn hàng...*
