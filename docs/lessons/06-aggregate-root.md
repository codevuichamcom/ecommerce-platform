# 📖 Lesson 06 — Aggregate Root (DDD)

## 🎯 TL;DR

**Aggregate** là cluster object được treat như 1 đơn vị consistency. Có
1 **root entity** điều khiển truy cập; ngoài chỉ được gọi method của
root, không truy cập trực tiếp child. **Boundary** chính là transactional
boundary — "one transaction = one aggregate".

Day 4 build aggregate `Stock` (size=1, chỉ có root). Day 6 build aggregate
`Order` (root + OrderItem children + Money/Address VOs).

## ✅ Khi nào dùng

- Có **invariant đa-thuộc-tính** cần atomic update (vd: `total =
  Σ(item.subtotal)`, `reserved ≤ quantity`).
- Có **concurrency thật** trên cùng dữ liệu (cần optimistic/pessimistic lock).
- Có **domain event** publish ra ngoài.

→ Đủ 3-điểm = build aggregate. ≤2 điểm = Layered service đơn giản hơn.
Xem [ADR-003](../decisions/003-ddd-for-order-inventory-payment.md).

## ❌ Khi nào KHÔNG

- CRUD đơn giản, không invariant phức tạp (vd: `product` Day 3 — Layered).
- Read-heavy, ít write (vd: catalog, analytics).
- Chỉ 1 field thay đổi (vd: user profile update — không cần aggregate).

## ⚠️ Cạm bẫy (top 5)

1. **Anemic Entity** — `Order` chỉ có getter/setter, business logic chạy
   ở `OrderService`. Đây là DDD trên giấy, code procedural. Junior + AI
   hay viết kiểu này.
2. **Public setter ở root** — caller bypass invariant. `Order.setTotal(0)`
   sẽ phá rule `total = Σ subtotal`. Day 6 chỉ expose factory + method
   domain.
3. **Repository cho child entity** — `OrderItemRepository` xuất hiện =
   boundary vỡ. Caller có thể save OrderItem lẻ → drift total. KHÔNG.
4. **Reference object thay vì ID** — `OrderItem.product` hold `Product`
   instance → 2 aggregate trong 1 tx → vi phạm rule. Phải `sku: String`.
5. **Aggregate quá to** — gom `Order + Payment + Shipment` vào 1 root
   → lock contention + memory bloat. Mỗi cái nên là aggregate riêng,
   liên kết qua ID + event.

## 🆚 Approaches compared

| Approach | Pros | Cons |
|----------|------|------|
| **Aggregate (DDD)** | Invariant tự enforce, code đọc được, test domain pure | Boilerplate (factory, no setter), curve học |
| **Anemic + Service** | Đơn giản, framework-friendly | Logic phân tán, bug invariant ẩn ở service |
| **Active Record** | Cực gọn (Rails style) | Trộn persistence + domain, khó test pure |

Day 6 chọn **Aggregate** vì Order có ≥3 invariant + concurrency
(optimistic lock @Version) + domain event ra ngoài.

## 🎤 Trả lời phỏng vấn

**Q**: *"Aggregate boundary của Order là gì? Tại sao không tách OrderItem
thành aggregate riêng?"*

> Aggregate boundary là phạm vi `Order + OrderItems`. Tách OrderItem
> thành aggregate riêng có nghĩa nó có lifecycle độc lập, có repository,
> được save tự do — phá invariant `total = Σ subtotal`. Trong DDD, child
> entity chỉ tồn tại qua root. Customer KHÔNG thao tác trực tiếp 1
> OrderItem, mà gọi `order.addItem(...)` hoặc `order.removeItem(...)`.
> Root chịu trách nhiệm recompute total + emit event.

**Follow-up trap**: *"Vậy nếu cần update tracking number cho 1 item
riêng?"* → trong domain Order Day 6, tracking là ở Order level (status
Shipped mang trackingNumber). Nếu sau này có per-item tracking → method
`order.updateItemTracking(itemId, tracking)` ở root, vẫn không expose
OrderItemRepository.

**Q**: *"DDD nói 1 tx 1 aggregate. PlaceOrder đụng cart + inventory +
order — vi phạm?"*

> KHÔNG. Cart và Inventory ở service khác, DB khác. `@Transactional` JPA
> chỉ ôm Order DB. Cross-aggregate ⇒ eventual consistency qua
> compensation (Day 6 sync) hoặc saga (Day 13 outbox + Kafka). Rule "1
> tx 1 aggregate" áp dụng trong cùng bounded context, không phải
> distributed transaction.

## 🔗 Related

- Code: [`Order.java`](../../services/order-service/src/main/java/com/ecommerce/order/domain/Order.java), [`Stock.java`](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/Stock.java)
- ADR [`003-ddd-for-order-inventory-payment.md`](../decisions/003-ddd-for-order-inventory-payment.md)
- Lesson [`04-optimistic-locking.md`](04-optimistic-locking.md), [`06b-sealed-types-state-machine.md`](06b-sealed-types-state-machine.md)
- Architecture [`order-domain.md`](../architecture/order-domain.md)
