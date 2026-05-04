# Lesson 04b — Transaction Isolation Levels (Postgres)

> **Status**: ⏳ Skeleton — fill khi build Day 4.
> **Related day**: Day 4 (Inventory + optimistic locking).

---

## 🎯 TL;DR

> 1-2 câu: 4 isolation levels giải quyết 3 anomaly (dirty / non-repeatable / phantom). Postgres default = `READ COMMITTED`. Khi nào nâng lên `REPEATABLE READ` / `SERIALIZABLE`?

---

## 📚 4 isolation levels × 3 anomaly

| Level             | Dirty read | Non-repeatable read | Phantom read | Postgres ngầm map sang |
| ----------------- | ---------- | ------------------- | ------------ | ---------------------- |
| READ UNCOMMITTED  | ❌          | ❌                   | ❌            | (Postgres không support — = READ COMMITTED) |
| READ COMMITTED    | ✅          | ❌                   | ❌            | default                |
| REPEATABLE READ   | ✅          | ✅                   | ✅ (snapshot)  | snapshot isolation     |
| SERIALIZABLE      | ✅          | ✅                   | ✅            | SSI (predicate lock)   |

> ⚠️ Postgres khác MySQL: `REPEATABLE READ` của Postgres = snapshot isolation, đã chặn phantom read (qua snapshot, không qua lock).

---

## 🔧 Khi nào dùng cái nào (rule of thumb)

- **READ COMMITTED**: 95% case — CRUD bình thường. Default đúng cho hầu hết.
- **REPEATABLE READ**: report cần đọc snapshot ổn định trong 1 transaction.
- **SERIALIZABLE**: business invariant kiểu "tổng < limit" mà cần check + write atomically. Trade-off: serialization failure → phải retry.

## ⚠️ Cạm bẫy

- (TODO) `SELECT ... FOR UPDATE` không thay thế isolation level — chỉ row lock.
- (TODO) `REPEATABLE READ` không lock — khi 2 tx update cùng row, 1 sẽ throw `could not serialize access due to concurrent update`.
- (TODO) Trong Spring `@Transactional(isolation = ...)` — set ở method, NOT class.

## 🆚 Approaches compared (cho case "prevent overselling")

| Approach                              | Pros                                       | Cons                                            |
| ------------------------------------- | ------------------------------------------ | ----------------------------------------------- |
| Optimistic lock (`@Version`)          | High throughput, no DB lock                | Retry storm khi contention cao                  |
| Pessimistic `SELECT FOR UPDATE`       | Đơn giản, không retry                      | Lock contention, có thể deadlock                |
| `SERIALIZABLE` isolation              | Đảm bảo invariant, không cần lock thủ công | Throughput thấp, serialization failure → retry  |

> Day 4 chọn **optimistic lock** vì context inventory: contention thật nhưng không quá cao, retry rẻ.

---

## 🎤 Trả lời phỏng vấn

> (TODO) "Dirty read là gì? Postgres có support READ UNCOMMITTED không?"
> (TODO) "Tại sao Postgres REPEATABLE READ chặn được phantom read mà MySQL không?"
> (TODO) "Khi nào em dùng SERIALIZABLE?"

## 🔗 Related

- [`lessons/04-optimistic-locking.md`](04-optimistic-locking.md)
- [`issues/04-overselling-stock.md`](../issues/04-overselling-stock.md)
- Code: `services/inventory-service/src/main/java/com/ecom/inventory/domain/Stock.java` (sẽ có Day 4)
