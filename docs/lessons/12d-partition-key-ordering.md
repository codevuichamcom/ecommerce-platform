# Lesson 12d — Kafka Partition Key & Ordering

> **Status**: ⏳ Skeleton — fill khi build Day 12.
> **Related day**: Day 12 (Retry + DLT).

---

## 🎯 TL;DR

> 1-2 câu: Kafka chỉ guarantee ordering **trong 1 partition**, KHÔNG phải toàn topic. Partition key quyết định message cùng key đi cùng partition. Chọn key = entity ID (`orderId`, `userId`) để đảm bảo events của cùng entity giữ thứ tự.

---

## 📚 Cơ chế

- (TODO) Hash(key) % numPartitions = partition index.
- (TODO) Cùng key → cùng partition → cùng consumer → ordered.
- (TODO) Khác key → khác partition → consumer khác nhau → KHÔNG ordered cross-partition.

---

## 🆚 Approaches compared (chọn partition key cho `order.*` events)

| Key choice           | Ordering guarantee                          | Pros                                | Cons                                                    |
| -------------------- | ------------------------------------------- | ----------------------------------- | ------------------------------------------------------- |
| `null` (round-robin) | Không có                                    | Distribute đều                      | Mất ordering — không dùng cho stateful event            |
| `orderId`            | Ordering per order                          | Tự nhiên, fine-grained              | Số lượng order rất nhiều → ổn cho hash distribution     |
| `userId`             | Ordering per user (mọi order của user)      | Có thể stream-process per user      | Hot user → hot partition (skew)                         |
| `region` / coarse    | Ordering trong region                       | Đơn giản                            | Skew nặng, không đủ fine-grained                        |

> Project dùng `orderId` cho `order.*` events. Cho `user.*` event thì `userId`.

## ⚠️ Cạm bẫy

- (TODO) Tăng partition KHÔNG retro-active: message cũ vẫn ở partition cũ, chỉ message mới hash lại → có thể đảo thứ tự khi đổi partition count.
- (TODO) Consumer rebalance: khi 1 consumer down, partition reassign → trong khoảnh khắc rebalance có thể duplicate process. Phải dedup.
- (TODO) Skew: 1 user "VIP" gửi 100x events → partition đó nóng. Có thể cần composite key `userId#bucket`.

## 🎤 Trả lời phỏng vấn

> (TODO) "Kafka guarantee ordering không?"
> (TODO) "Em chọn partition key thế nào cho order events? Tại sao không phải `null`?"
> (TODO) "Tăng partition từ 3 lên 6 — message cũ thế nào?"

## 🔗 Related

- [`lessons/12c-kafka-delivery-semantics.md`](12c-kafka-delivery-semantics.md)
- [`lessons/08-kafka-basics.md`](08-kafka-basics.md)
