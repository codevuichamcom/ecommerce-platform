# Issue 19 — 🔥 Redlock Correctness: GC Pause Splits the Lock

> **Status**: ⏳ Skeleton — fill khi build Day 19.
> **Related day**: Day 19 (Java concurrency + distributed lock).

---

## 1. Problem

> 1-2 câu: 1 scheduled job ("daily inventory snapshot") chạy multiple instance, dùng Redis SET NX để election leader. 1 lần GC pause 25s ở instance A → lock expire → instance B chạy job → A wake up tiếp tục chạy → **2 process cùng write snapshot → data corrupt**.

## 2. Symptoms

- (TODO) DB log: 2 row insert vào `inventory_snapshot` với same `snapshot_date`, khác `created_by_instance`.
- (TODO) JVM log instance A: `[GC pause] 24532ms` ngay trước log "writing snapshot".
- (TODO) Redis log: lock key `lock:snapshot:2026-05-04` có 2 lần SET với 2 UUID khác nhau.
- (TODO) Báo cáo daily sai gấp đôi.

## 3. Root cause

- (TODO) Lock TTL 30s — không đủ buffer cho GC pause / STW.
- (TODO) Code trust lock holding ("nếu acquire lock OK = an toàn write") — sai assumption.
- (TODO) Không có fencing token → DB không thể reject write của old leader.

## 4. Approaches compared

| Approach                                | Pros                                          | Cons                                                 |
| --------------------------------------- | --------------------------------------------- | ---------------------------------------------------- |
| Tăng TTL lên 5 phút                     | Đơn giản, giảm xác suất                       | Không loại trừ; nếu instance chết thật → block 5min  |
| Redlock với 5 Redis node                | Tốt hơn 1 node, nhưng vẫn không safe vs pause  | Phức tạp; Kleppmann argument vẫn áp dụng             |
| Heartbeat refresh lock                  | Lock không expire khi instance còn sống        | GC pause = không heartbeat = vẫn lose lock           |
| ZooKeeper ephemeral node                | Strong consistency (consensus)                | Phải vận hành ZK — overkill cho 1 job                |
| **Fencing token + DB unique constraint** | Provably correct                              | Phải đổi schema (thêm `fencing_token` cột UNIQUE)    |
| Idempotent operation + dedup key        | Không cần lock                                | Cần redesign job thành idempotent                    |

## 5. Chosen — Idempotent operation + DB unique constraint (no lock needed)

- (TODO) Lý do: lock không phải tool đúng. Job daily snapshot có natural dedup key = `snapshot_date`.
- (TODO) DB constraint `UNIQUE(snapshot_date)` → 2 instance chạy → 1 thành công, 1 throw `ConstraintViolation` → catch và log info.
- (TODO) Khi nào còn cần lock? — chỉ cho "best-effort" coordination (giảm duplicate work), KHÔNG dùng cho correctness.

## 6. Fix

```sql
-- (TODO)
ALTER TABLE inventory_snapshot ADD CONSTRAINT uk_snapshot_date UNIQUE (snapshot_date);
```

```java
// (TODO) Wrap insert trong try-catch DataIntegrityViolationException
// Best-effort lock acquire vẫn dùng (giảm wasted compute), nhưng KHÔNG là correctness boundary.
```

## 7. Prevention

- (TODO) Code review rule: "lock = best-effort, NOT correctness". Bất kỳ critical write nào phải có DB constraint hoặc fencing token.
- (TODO) Test: simulate GC pause bằng `jcmd <pid> GC.run` + Thread.sleep → verify chỉ 1 row được insert.
- (TODO) Metric: alert khi `ConstraintViolation` rate > 0 (signal có overlap).

## 8. Trade-off accepted

- (TODO) Compute lặp ở instance loser — nhưng cost rẻ vs data corruption.
- (TODO) Cần redesign mọi critical job theo idempotent — upfront cost.

## 9. Related

- Lesson: [`lessons/19c-distributed-lock-redlock.md`](../lessons/19c-distributed-lock-redlock.md)
- Lesson: [`lessons/10-idempotency.md`](../lessons/10-idempotency.md)
- Code: `services/inventory-service/.../SnapshotJob.java`
