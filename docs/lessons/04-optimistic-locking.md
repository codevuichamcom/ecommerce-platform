# Lesson 04 — Optimistic Locking với `@Version`

> **Status**: ✅ Done · 2026-05-09
> **Related day**: Day 4 (inventory-service)

---

## 🎯 TL;DR

`@Version` KHÔNG phải là lock. Nó là cơ chế **conditional UPDATE**: Hibernate generate SQL `UPDATE stock SET ... WHERE id=? AND version=?`, nếu `affected_rows=0` → ai đó đã update trước → throw `ObjectOptimisticLockingFailureException`. Caller (application service) **retry với fresh state** — không retry trong cùng transaction.

So với pessimistic `SELECT FOR UPDATE`: optimistic không giữ lock, throughput cao hơn khi contention thấp; trả giá bằng retry storm khi contention cao.

---

## 🔧 Khi nào dùng

- **Contention thấp đến trung bình** (< 500 RPS cùng row): retry rẻ, throughput cao, không khóa DB.
- Aggregate root có invariant cần enforce ở app layer (DDD): kết hợp `@Version` để bảo vệ "lost update" giữa 2 tx.
- Đa số CRUD: `@Version` là default an toàn — chi phí gần như 0.
- **Inventory `reserve(sku, qty)`** Day 4 — case điển hình: nhiều user grab 1 SKU, nhưng tổng RPS cùng SKU vẫn dưới 500 (MVP).

## ❌ Khi nào KHÔNG dùng

- **Contention cực cao** (>1000 RPS cùng row, vd: flash sale 1 SKU hot): retry storm, retry exhausted → 4xx flood. Lúc này dùng Redis atomic `DECR` + Lua (Day 33) hoặc queue.
- **Long-running tx** (>1s): khả năng version đã đổi rất cao → retry hoài không thoát. Tách tx ngắn hơn hoặc dùng pessimistic.
- **Workflow đòi atomic phức tạp cross-aggregate**: optimistic không giải được (mỗi aggregate có version riêng). Dùng saga + compensation.

## ⚠️ Cạm bẫy

1. **Retry trong cùng transaction**: nếu `@Retryable` mà tx outer vẫn open, JPA persistence context giữ entity stale → retry vô ích. Phải `@Transactional(propagation = REQUIRES_NEW)` ở method retry.
2. **Quên `@Version` field ở `BaseEntity`**: Hibernate sẽ UPDATE bình thường, lost update âm thầm. Day 4 đã có sẵn ở [`BaseEntity.java`](../../common-lib/src/main/java/com/ecom/common/audit/BaseEntity.java).
3. **AI/junior viết invariant ở service layer**: `if (stock.available() < qty) throw; stock.reserve(qty);` — TOCTOU race condition. Đúng pattern: **chỉ gọi `stock.reserve(qty)`**, để aggregate tự throw.
4. **Test integration không gate config sai**: nếu pool `maximum-pool-size=10` mà 100 thread → connection exhaustion che mất bug optimistic. Day 4 set `pool=30` cho test 100-thread.
5. **Spring wrap `OptimisticLockException` (JPA) thành `ObjectOptimisticLockingFailureException` (Spring)**. `@Retryable(retryFor=...)` phải dùng class Spring, KHÔNG phải JPA.

## 🆚 Approaches compared

Xem chi tiết ở [issue 04](../issues/04-overselling-stock.md) §Approaches compared.

| Approach | Throughput | Retry cost | Complexity |
| -------- | ---------- | ---------- | ---------- |
| Optimistic `@Version` | Cao | Có (rẻ khi contention <500 RPS) | Thấp |
| Pessimistic `SELECT FOR UPDATE` | Trung bình (lock queue) | Không | Thấp |
| Redis Lua atomic decrement | Rất cao | Không | Cao (sync với DB) |
| `SERIALIZABLE` isolation | Thấp (serialization failure) | Có | Thấp (1 dòng config) |

## 🎤 Trả lời phỏng vấn

> **Q**: "`@Version` hoạt động thế nào dưới gầm?"
>
> **A**: Hibernate generate SQL `UPDATE table SET col=?, version=? WHERE id=? AND version=?`. Trước UPDATE, persistence context có version=N. UPDATE set version=N+1 với điều kiện row hiện tại version=N. Nếu tx khác đã commit version mới → affected_rows=0 → Hibernate throw `OptimisticLockException`, Spring wrap thành `ObjectOptimisticLockingFailureException`. Application service catch → retry với fresh load.

> **Q**: "Khác gì với pessimistic `SELECT FOR UPDATE`?"
>
> **A**: Pessimistic giữ row-level lock từ lúc SELECT đến commit, tx khác đụng row đó phải đợi. Throughput thấp khi contention cao nhưng predictable. Optimistic không lock — bet rằng ít contention; khi contention cao thì retry storm. Choice phụ thuộc workload: < 500 RPS/row → optimistic; > 1000 RPS/row → pessimistic hoặc Redis.

> **Q**: "Retry vô hạn được không?"
>
> **A**: Không. Phải có `maxAttempts` + `@Recover` để fail-fast khi retry exhausted, trả CONFLICT cho client. Nếu retry vô hạn, hot SKU bị viral sẽ làm thread pool starvation.

> **Q**: "Lost update vs dirty write — khác gì?"
>
> **A**: Dirty write là 2 tx ghi đè uncommitted change của nhau (Postgres `READ COMMITTED` đã chặn). Lost update là 2 tx đọc rồi cùng update dựa trên giá trị cũ — mất 1 update. `@Version` chặn lost update; isolation level alone không chặn.

## 🔗 Related

- [Lesson 04b — Transaction isolation](04b-transaction-isolation.md)
- [Issue 04 — Overselling stock](../issues/04-overselling-stock.md)
- [ADR-003 — DDD selective](../decisions/003-ddd-for-order-inventory-payment.md)
- Code: [`Stock.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/Stock.java) · [`InventoryService.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventoryService.java)
- Test: [`InventoryConcurrencyIT.java`](../../services/inventory-service/src/test/java/com/ecom/inventory/InventoryConcurrencyIT.java)
