# Lesson 19c — Distributed Lock (Redis SET NX, Redlock, Fencing Token)

> **Status**: ⏳ Skeleton — fill khi build Day 19.
> **Related day**: Day 19 (Java concurrency).

---

## 🎯 TL;DR

> 1-2 câu: Distributed lock cần thiết cho cross-process critical section (vd: refresh token, scheduled job leader). Redis `SET NX PX` đủ cho 95% case; Redlock controversy → cho mission-critical phải dùng **fencing token**, không lock alone.

---

## 📚 Cơ bản: Redis SET NX PX

```
SET resource_name unique_value NX PX 30000
# NX = set if not exists
# PX 30000 = expire 30s (chống deadlock khi process chết)
# unique_value = UUID, để release đúng owner (không xóa lock của process khác)
```

Release phải dùng Lua script (atomic check-and-delete):

```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
  return redis.call("del", KEYS[1])
else
  return 0
end
```

---

## ⚠️ The Big Trap — GC pause / network delay

> **Scenario** (Martin Kleppmann's argument):
> 1. Process A acquire lock, TTL 30s.
> 2. Process A vào GC pause 35s.
> 3. Lock expire ở Redis. Process B acquire same lock — Redis cho OK.
> 4. Process A wake up khỏi GC, NGHĨ rằng vẫn giữ lock → write to DB.
> 5. Cả A và B cùng write → corrupt data.

→ **Lock alone không đủ** cho mission-critical work.

## 🆚 Approaches compared

| Approach                  | Safety guarantee                           | Cost                                          | Khi nào dùng                                     |
| ------------------------- | ------------------------------------------ | --------------------------------------------- | ------------------------------------------------ |
| Single Redis SET NX       | "Best effort" — KHÔNG safe vs GC pause     | Trivial                                       | Job scheduler, refresh task — chấp nhận overlap  |
| Redlock (≥3 Redis node)   | Tốt hơn nhưng vẫn có debate                | Phức tạp, latency cao hơn                     | (controversial) — nhiều người không recommend    |
| ZooKeeper / etcd ephemeral node | Strong consistency (consensus)        | Cost ops cao, latency 10-50ms                 | Critical leader election                         |
| **Fencing token**         | Provable correct — kết hợp với resource    | Cần resource support compare-and-set          | **Recommended cho money/inventory work**         |

## 🛡️ Fencing token — cách giải đúng

- (TODO) Mỗi lần acquire lock, tăng counter, trả về token (1, 2, 3, ...).
- (TODO) Khi process A write to resource, gửi kèm token. Resource (DB / file storage) reject nếu token < token đã thấy lần trước.
- (TODO) GC pause scenario: A có token=10, B sau acquire có token=11 và write trước. A wake up gửi token=10 → DB reject vì đã thấy 11.

## 🎯 Chosen cho project

- (TODO) Default: Redis SET NX PX cho idempotency dedup, scheduled job lock.
- (TODO) Critical (payment idempotent processing): KHÔNG dùng distributed lock — dùng DB unique constraint + idempotency key (Day 10).
- (TODO) NOT use Redlock vì project không có scenario đáng dùng.

## ⚠️ Cạm bẫy

- (TODO) Quên Lua script khi release → race condition khi TTL expire giữa GET và DEL.
- (TODO) TTL quá ngắn → process bình thường cũng bị mất lock.
- (TODO) TTL quá dài → process chết = block lâu.
- (TODO) Single Redis = SPOF; nhưng Redlock cũng không cứu được hết case.

## 🎤 Trả lời phỏng vấn

> (TODO) "Redis distributed lock có safe không?"
> (TODO) "Redlock là gì? Tại sao có debate?"
> (TODO) "Fencing token giải quyết vấn đề gì?"
> (TODO) "Khi nào em chọn distributed lock vs DB unique constraint?"

## 🔗 Related

- [`issues/19-redlock-correctness.md`](../issues/19-redlock-correctness.md)
- [`lessons/10-idempotency.md`](10-idempotency.md)
- Refs: Martin Kleppmann "How to do distributed locking", antirez response.
