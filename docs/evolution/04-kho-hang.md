# Chương 4 · 🏭 Kho hàng — nơi invariant là vua

**Day 4 — Inventory Service (DDD)**

---

> *"Trong ecommerce, overselling là tội không thể tha thứ. Bạn hứa với khách có hàng, rồi nói 'xin lỗi, hết rồi'. Một lần mất niềm tin, mười lần không lấy lại."*

---

## Bối cảnh

Đây là ngày DDD xuất hiện lần đầu tiên. Và lý do không phải vì DDD "cool" hay "trendy" — mà vì **bài toán bắt buộc**.

Hãy tưởng tượng: Flash sale. 100 người cùng click "Mua ngay" trên 1 sản phẩm còn đúng 1 cái. Ai được? Ai không? Và quan trọng nhất: **làm sao đảm bảo không bán 2 cái khi chỉ có 1?**

Đây không phải bài toán CRUD. Đây là bài toán **concurrency + business invariant**. Và đó chính xác là lúc DDD tỏa sáng.

---

## Aggregate `Stock` — pháo đài bất khả xâm phạm

```java
public class Stock extends BaseEntity {
    private String sku;
    private int quantity;    // tổng tồn kho
    private int reserved;   // đã giữ chỗ, chờ thanh toán

    // INVARIANT: reserved ≤ quantity — LUÔN LUÔN. KHÔNG NGOẠI LỆ.
}
```

Không setter public. Không cách nào từ bên ngoài set `reserved = 999`. Muốn reserve? Gọi method:

```java
public void reserve(int qty) {
    if (this.reserved + qty > this.quantity) {
        throw new InsufficientStockException(sku, available(), qty);
    }
    this.reserved += qty;
    registerEvent(new StockReserved(this.sku, qty));
}
```

Method tự kiểm tra invariant. Tự throw nếu vi phạm. Tự publish domain event nếu thành công. **Aggregate là judge, jury, và executioner.**

---

## Optimistic Locking — cuộc đua 100 threads

Nhưng `reserve()` chỉ đúng trong single-thread. 100 thread gọi đồng thời thì sao?

```
Thread A: đọc stock (version=1, reserved=0) → reserve(1) → reserved=1 → save
Thread B: đọc stock (version=1, reserved=0) → reserve(1) → reserved=1 → save
                                                                          ↑ CONFLICT!
```

Cả 2 đều nghĩ `reserved=0`, cả 2 đều set `reserved=1`. Kết quả: bán 2 cái khi chỉ có 1. **Overselling.**

**Fix: `@Version` + `@Retryable`**

```java
// BaseEntity đã có sẵn
@Version
private Long version;
```

Khi Thread B save, Hibernate check: *"version trong DB là 2 (Thread A đã update), nhưng tôi đang giữ version 1"* → `OptimisticLockingFailureException`. Thread B retry — lần này đọc lại stock mới (reserved=1), check invariant, nếu còn hàng thì reserve tiếp, nếu hết thì throw.

```java
@Retryable(
    retryFor = OptimisticLockingFailureException.class,
    maxAttempts = 4,
    backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2)
)
@Transactional(propagation = REQUIRES_NEW)
public StockReservedResult reserve(String sku, int qty) { ... }
```

Exponential backoff: 50ms → 100ms → 200ms → 500ms. Không thundering herd.

---

## Bài test quyết định: 100 threads, 1 SKU, stock = 50

```java
@Test
void concurrency_100_threads_no_oversell() {
    // Given: stock = 50
    // When: 100 threads cùng reserve(1)
    // Then: exactly 50 success, exactly 50 InsufficientStockException
    //       final reserved = 50, NO oversell
}
```

**Kết quả: PASS.** 50 success. 50 fail. `reserved = 50`. Không hơn, không kém. Toán học không nói dối.

---

## Defense-in-depth: DB CHECK constraint

Code đúng rồi, nhưng senior không tin code 100%. Thêm lớp phòng thủ cuối cùng:

```sql
ALTER TABLE stocks ADD CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0);
ALTER TABLE stocks ADD CONSTRAINT chk_quantity_non_negative CHECK (quantity >= 0);
ALTER TABLE stocks ADD CONSTRAINT chk_reserved_lte_quantity CHECK (reserved <= quantity);
```

Dù code có bug, dù ORM có quirk, dù ai đó chạy raw SQL update — database **từ chối** vi phạm invariant. Belt AND suspenders.

---

## 🆕 Sealed types cho domain state

```java
public sealed interface ReservationStatus
    permits Pending, Reserved, Released, Confirmed {

    record Pending() implements ReservationStatus {}
    record Reserved(Instant reservedAt) implements ReservationStatus {}
    record Released(String reason) implements ReservationStatus {}
    record Confirmed(String orderId) implements ReservationStatus {}
}
```

Không phải String. Không phải enum. **Sealed interface** — compiler biết tất cả các trạng thái có thể. Pattern matching switch không cần `default` branch. Thêm trạng thái mới → build break ở mọi nơi chưa handle. Zero runtime surprise.

---

## Kết thúc ngày 4

```
📊 Scorecard:
├── Services:        3 (auth + product + inventory)
├── DDD services:    1 (inventory — đủ 3 tiêu chí)
├── Invariants:      3 (reserved≤quantity, non-negative, atomic reserve)
├── Concurrency:     100-thread test PASS, zero oversell
├── Defense layers:  3 (aggregate method + optimistic lock + DB CHECK)
├── Docs:            5 (ADR-003, 2 lessons, issue overselling, interview)
└── Vibe:            "Kho hàng bất khả xâm phạm. Overselling? Not on my watch."
```

> 💡 **Câu hỏi phỏng vấn kinh điển**: *"Optimistic vs Pessimistic locking — khi nào dùng cái nào?"*
>
> **Strong answer**: Optimistic khi conflict rate thấp (<5%), read-heavy. Pessimistic khi conflict rate cao, write-heavy, hoặc operation không idempotent (không retry được). Inventory reserve: conflict rate ~2-5% bình thường, spike lên 30-50% khi flash sale → bình thường dùng optimistic, flash sale chuyển sang Redis Lua atomic decrement (Day 33).

---

*→ Kho hàng đã an toàn. Giờ khách cần chỗ để gom hàng trước khi mua...*
