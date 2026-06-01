# Issue 19 — 🔥 Redlock Correctness: GC Pause Splits the Lock

> **Status**: ✅ Done · Day 19
> **Related day**: Day 19 (Java concurrency + distributed lock).

---

## 1. Problem

Job "daily inventory snapshot" chạy trên nhiều instance, dùng Redis `SET NX` để
elect leader. Một lần instance A bị **GC pause 25s** → lock expire → instance B
chiếm lock và chạy job → A wake up vẫn nghĩ mình giữ lock, chạy tiếp và ghi đè
→ **2 process cùng write snapshot → số liệu báo cáo nhân đôi**.

## 2. Symptoms

- DB: 2 row trong `inventory_snapshot` cùng `snapshot_date` (trước khi có UNIQUE),
  hoặc 1 row bị ghi đè bằng dữ liệu cũ — khác `created_by_instance`.
- JVM log instance A: `[GC pause] 24532ms` ngay TRƯỚC dòng log "writing snapshot".
- Redis log: key `lock:snapshot:2026-05-31` bị SET 2 lần với 2 UUID khác nhau
  trong khoảng cách < 30s.
- Báo cáo BI: tổng tồn kho ngày đó gấp ~2× thực tế → ops mất niềm tin vào dashboard.

## 3. Root cause

- **Lock TTL (30s) ngắn hơn GC pause (25s) + thời gian xử lý** → lock expire khi
  A còn đang "giữ".
- Code **tin lock holding** ("acquire OK = an toàn write") — sai assumption nền tảng.
- **Không có fencing token** → DB không có cách nào phân biệt write của leader cũ
  (A) với leader mới (B) → nhận cả hai.
- Đây không phải bug Redis; là bug **giả định**: distributed lock chỉ là
  best-effort mutual exclusion, KHÔNG phải correctness guarantee.

## 4. Approaches compared

| Approach | Pros | Cons |
| --- | --- | --- |
| Tăng TTL lên 5 phút | Đơn giản, giảm xác suất overlap | Không loại trừ (pause dài hơn vẫn split); instance chết thật → block 5 phút |
| Redlock (≥3 Redis node) | Tốt hơn 1 node về availability | Phức tạp + latency; Kleppmann: vẫn KHÔNG safe vs pause; project chỉ 1 Redis |
| ZooKeeper/etcd ephemeral node | Strong consistency (consensus), session-based | Thêm hạ tầng vận hành; latency cao; over-engineering cho 1 daily job |
| **Fencing token + DB guard** | Provable correct; chống split-brain tại resource; rẻ (1 INCR + 1 WHERE) | Cần resource hỗ trợ compare (DB conditional update) |

## 5. Chosen approach + Why

**Fencing token + DB conditional upsert** (giữ Redis SET NX làm lớp giảm-trùng).
Lý do gắn context project:
- Project chỉ có **1 Redis node** → Redlock vô nghĩa (không có quorum thật).
- Resource đích là **Postgres** — đã hỗ trợ atomic conditional update (`ON CONFLICT
  ... WHERE`), nên enforce fencing gần như miễn phí.
- Hai lớp, đúng vai: **lock lo hiệu năng** (đỡ chạy thừa), **fence lo đúng đắn**
  (chặn stale writer). Không nhập nhằng "lock = an toàn".
- Không thêm ZooKeeper/etcd cho 1 job daily = tránh over-engineering.

> ⚠️ Phân biệt với payment idempotent (Day 10): ở đó invariant "1 txn xử 1 lần"
> map thẳng vào 1 key → **DB unique constraint là đủ**, không cần lock. Ở đây job
> cần serialize *cả đoạn xử lý* không map vào 1 key → cần lock + fencing. Chọn vũ
> khí theo **hình dạng invariant**.

## 6. Fix

Fencing token sinh khi acquire ([`RedisDistributedLock.tryAcquire`](../../common-lib/src/main/java/com/ecom/common/lock/RedisDistributedLock.java)):

```java
Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, ttl);
if (!Boolean.TRUE.equals(acquired)) return Optional.empty();
Long fencing = redis.opsForValue().increment(FENCE_PREFIX + key);  // monotonic
return Optional.of(new LockHandle(key, token, fencing));
```

Enforce ở DB ([`InventorySnapshotRepository.upsertWithFence`](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/InventorySnapshotRepository.java)):

```sql
INSERT INTO inventory_snapshot (...) VALUES (..., :token, ...)
ON CONFLICT (snapshot_date) DO UPDATE SET ...
 WHERE inventory_snapshot.last_fencing_token < EXCLUDED.last_fencing_token
```

→ stale writer (token cũ) → WHERE fail → 0 row → bị từ chối. Job log
`STALE-WRITER blocked by fence` ([`InventorySnapshotJob`](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventorySnapshotJob.java)).
Release lock atomic qua Lua compare-and-del (chỉ owner đúng token mới xoá).

## 7. Prevention

- **Test**: [`RedisDistributedLockTest`](../../common-lib/src/test/java/com/ecom/common/lock/RedisDistributedLockTest.java)
  — `fencingTokenIsMonotonic` + `releaseWithWrongTokenIsRejected` (gated Testcontainers `RUN_LOCK_INTEGRATION_TESTS=true`).
- **DB constraint**: UNIQUE `(snapshot_date)` + CHECK `last_fencing_token >= 0` —
  defense-in-depth, admin SQL adhoc cũng không phá.
- **Monitor**: alert khi log `STALE-WRITER blocked by fence` xuất hiện > 0 → có
  split-brain thật, điều tra GC/pause.
- **Lint review**: bất kỳ "distributed lock + critical write" nào KHÔNG kèm fencing
  → flag ở review ([ai-junior-traps](../review/ai-junior-traps.md)).

## 8. Trade-off accepted

- Fencing token enforce ở DB → resource đích **phải** hỗ trợ compare-and-set. Nếu
  sau này ghi snapshot ra S3/file (không có conditional write) thì fencing kiểu này
  không áp dụng — phải đổi sang object versioning / precondition header.
- Vẫn giữ Redis SET NX dù nó "không đủ" — chấp nhận 1 lớp best-effort để giảm số
  lần chạy thừa, đổi lấy đơn giản (không cần ZooKeeper).
- INCR counter bền (không TTL) → tốn 1 key vĩnh viễn mỗi resource. Chấp nhận (rẻ).

## 9. Related

- Code: [`RedisDistributedLock`](../../common-lib/src/main/java/com/ecom/common/lock/RedisDistributedLock.java) · [`LockHandle`](../../common-lib/src/main/java/com/ecom/common/lock/LockHandle.java) · [`InventorySnapshotJob`](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventorySnapshotJob.java) · [`InventorySnapshotRepository`](../../services/inventory-service/src/main/java/com/ecom/inventory/domain/InventorySnapshotRepository.java) · [V3 migration](../../services/inventory-service/src/main/resources/db/migration/V3__inventory_snapshot.sql)
- Lesson: [`lessons/19c-distributed-lock-redlock.md`](../lessons/19c-distributed-lock-redlock.md) · [`lessons/10-idempotency.md`](../lessons/10-idempotency.md)
- Interview: [`interview/day-19-concurrency.md`](../interview/day-19-concurrency.md)
- Refs: Martin Kleppmann "How to do distributed locking" · antirez response.
