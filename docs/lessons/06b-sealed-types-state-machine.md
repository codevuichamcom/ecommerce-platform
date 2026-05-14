# 📖 Lesson 06b — Sealed Types cho State Machine (Java 21)

## 🎯 TL;DR

**Sealed interface** + **exhaustive switch pattern matching** (JEP 409 +
JEP 441) cho compile-time guarantee mỗi case của state machine được
cover. Khi thêm permit mới → mọi switch chưa update sẽ compile error
— free safety net.

Day 6 dùng cho `OrderStatus` permits `PendingPayment / Paid / Shipped /
Delivered / Cancelled` — mỗi permit mang data khác nhau (Shipped có
trackingNumber, Cancelled có reason).

## ✅ Khi nào dùng

- State machine có **N state, mỗi state có data riêng** (không chỉ là label).
- Muốn compiler kill case-miss bug khi thêm state.
- Hierarchy đóng — biết hết permit upfront, không cho 3rd-party extend.

## ❌ Khi nào KHÔNG (dùng enum thay)

- State chỉ là **label/tag** (PENDING/ACTIVE/DELETED) — không data, không
  behavior khác nhau → enum gọn hơn, JPA mapping native, JSON ra string thẳng.
- Cần `EnumSet`/`EnumMap` hiệu năng cao.
- API public — sealed yêu cầu pattern matching switch ở caller, không
  phải mọi client (Kotlin, Scala) handle gọn được.

## ⚠️ Cạm bẫy (top 4)

1. **Thêm `default ->` branch** trong switch → mất exhaustive. Compiler
   không còn báo lỗi khi thêm permit. Bug ngầm. KHÔNG dùng default.
2. **Persistence mapping**: sealed không có AttributeConverter native cho
   nhiều column. Day 6 dùng JPA `@PostLoad`/`@PrePersist` callback +
   util `OrderStatusSerializer` — manual nhưng simple.
3. **JSON ser/de**: Jackson default không xử lý sealed kèm data. Phải
   custom serializer hoặc dùng `@JsonTypeInfo` + `@JsonSubTypes`. Day 6
   tránh bằng cách REST response flatten thành DTO `StatusDataDto`.
4. **Hierarchy 2 tầng**: sealed A permits B, B sealed permits C/D —
   exhaustive switch ở level A phải match B (rồi tiếp tục match C/D
   ngầm). Phức tạp; tránh nếu được.

## 🆚 Approaches compared

| Approach | Compile-time safety | Per-state data | Boilerplate | DB mapping |
|----------|---------------------|----------------|-------------|------------|
| **String + if/else** | ❌ | Hard (nullable field) | Low | Trivial |
| **enum + switch** | ⚠️ (Java <21 fall-through) / ✅ (Java 21 enum switch) | ❌ (nullable hell) | Low | `@Enumerated(STRING)` |
| **Sealed interface** ✅ Day 6 | ✅ exhaustive | ✅ per record | Medium | Custom (callback) |
| **Class hierarchy (open)** | ❌ | ✅ | High | Custom |

## 🔧 Code pattern

```java
public sealed interface OrderStatus
        permits PendingPayment, Paid, Shipped, Delivered, Cancelled {
    String statusName();

    default boolean isTerminal() {
        return switch (this) {  // EXHAUSTIVE — no `default ->`
            case PendingPayment p -> false;
            case Paid p           -> false;
            case Shipped s        -> false;
            case Delivered d      -> true;
            case Cancelled c      -> true;
        };
    }

    record PendingPayment() implements OrderStatus { ... }
    record Paid(Instant paidAt) implements OrderStatus { ... }
    record Shipped(String trackingNumber, Instant shippedAt) implements OrderStatus { ... }
    record Delivered(Instant deliveredAt) implements OrderStatus { ... }
    record Cancelled(String reason, Instant cancelledAt) implements OrderStatus { ... }
}
```

Transition rule cũng exhaustive switch:

```java
boolean allowed = switch (status) {
    case PendingPayment p -> next instanceof Paid || next instanceof Cancelled;
    case Paid p           -> next instanceof Shipped || next instanceof Cancelled;
    case Shipped s        -> next instanceof Delivered;
    case Delivered d      -> false;
    case Cancelled c      -> false;
};
```

→ Thêm `Refunded` permit? Compiler báo: `'switch' expression does not
cover all possible input values`. Bug-by-omission tự fail-fast.

## 🎤 Trả lời phỏng vấn

**Q**: *"Sealed interface vs enum — khi nào chọn?"*

> Enum khi state chỉ là label đồng cấu trúc (mọi state có data giống
> nhau hoặc không có data). Sealed khi mỗi state có **data riêng**
> (Shipped có trackingNumber, Cancelled có reason) hoặc behavior riêng.
> Trong project Day 6, `Cancelled.reason` không tồn tại ở Paid →
> nullable field ở enum sẽ tạo hell. Sealed + record cho mỗi permit là
> tự nhiên + type-safe.

**Follow-up trap**: *"Bạn nói exhaustive switch là safety net. Lỡ team
viết `default -> throw`?"* → ngược intent. Default branch kill
exhaustive check của compiler. Convention team: cấm `default ->` trong
switch sealed; code review reject. Có thể enforce qua ArchUnit hoặc
ErrorProne rule.

**Q**: *"Sealed status persist DB như nào?"*

> 2 column: `status_type` (varchar) + `status_data` (JSONB). Mapping qua
> JPA `@PostLoad`/`@PrePersist` callback gọi util serializer.
> AttributeConverter không phù hợp vì cần 2 column. Day 6 chấp nhận
> boilerplate này; alternative là EAV hoặc 1-table-per-permit (phức
> tạp gấp 5).

## 🔗 Related

- Code: [`OrderStatus.java`](../../services/order-service/src/main/java/com/ecommerce/order/domain/OrderStatus.java), [`OrderStatusSerializer.java`](../../services/order-service/src/main/java/com/ecommerce/order/infrastructure/persistence/OrderStatusSerializer.java)
- JEP 409 (sealed), JEP 441 (pattern matching switch)
- Lesson [`06-aggregate-root.md`](06-aggregate-root.md)
- Architecture [`order-domain.md`](../architecture/order-domain.md)
