# Lesson 19 — 🔒 Java Locking: synchronized / ReentrantLock / StampedLock + DB lock

> **Status**: ✅ Done · Day 19
> **Related code**: [`concurrency-lab/.../LockThroughputBenchmark.java`](../../concurrency-lab/src/main/java/com/ecom/lab/lock/LockThroughputBenchmark.java) · [`inventory-service InventoryService`](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventoryService.java)

---

## 🎯 TL;DR

> 3 lock JVM giải cùng 1 bài (mutual exclusion) với cái giá khác nhau:
> `synchronized` rẻ + tự giải phóng nhưng **pin virtual thread**; `ReentrantLock`
> linh hoạt (tryLock, fairness, interruptible) + **unpin VT**; `StampedLock`
> optimistic read **không khoá reader** → nhanh nhất read-heavy nhưng KHÔNG
> reentrant, dễ đọc torn state nếu quên validate. Ở tầng DB là câu chuyện song
> song: **optimistic** (`@Version`) cho low-contention, **pessimistic**
> (`SELECT ... FOR UPDATE`) cho hot-row.

---

## 📊 Số đo thật (JMH, 8 thread, read-mostly, máy dev — chạy lại để có số của bạn)

```
Benchmark                                       Mode  Cnt        Score        Error   Units
LockThroughputBenchmark.synchronizedRead       thrpt    5     7271.124 ±    814.653  ops/ms
LockThroughputBenchmark.reentrantLockRead      thrpt    5    20827.085 ±   5793.575  ops/ms
LockThroughputBenchmark.stampedOptimisticRead  thrpt    5  5623426.644 ± 654988.331  ops/ms
```

→ Khoảng cách **không phải vài chục %** mà là **bậc độ lớn**: ReentrantLock ~**2.9×**
synchronized; StampedLock optimistic ~**770×** synchronized. Lý do then chốt:
optimistic read **KHÔNG lấy lock nào cả** — chỉ đọc 1 "stamp" (volatile read) rồi
validate sau. Không acquire = **không có cache-line bouncing** giữa 8 core (cái
thực sự giết throughput khi nhiều thread tranh cùng 1 lock), nên nó scale gần như
tuyến tính theo core. Cái giá: phải copy field ra local **trước khi** validate, và
fallback read-lock khi validate fail.

> ⚠️ Con số ×770 là cho **read thuần, không contention ghi**. Có writer xen vào
> liên tục → optimistic validate fail thường xuyên → rớt về read-lock → khoảng
> cách thu hẹp. Đừng nhớ "×770", nhớ *"optimistic thắng vì không acquire lock"*.

---

## 🆚 Approaches compared

| Cơ chế | Ưu | Nhược |
| --- | --- | --- |
| `synchronized` | Cú pháp gọn, JVM tự release khi thoát block (kể cả exception); biased/thin lock nhanh khi ít tranh | **Pin VT** khi block bên trong (Java 21); không tryLock/timeout; không interruptible; không fairness |
| `ReentrantLock` | tryLock + timeout, interruptible, fairness option, condition; **unpin VT** (park qua `LockSupport`) | Phải `unlock()` trong `finally` (quên = deadlock vĩnh viễn); verbose hơn |
| `StampedLock` | Optimistic read không khoá → throughput read-heavy cao nhất; có chế độ convert read↔write | **KHÔNG reentrant** (re-acquire = self-deadlock); optimistic phải validate; API dễ dùng sai |

---

## 🎯 Chọn gì cho project

- **Mặc định**: `synchronized` cho critical section ngắn, KHÔNG block bên trong.
  Nếu critical section có I/O / sleep và chạy trên virtual thread → đổi
  `ReentrantLock` để tránh pin (xem [19b](19b-virtual-threads-deep.md)).
- **Read-heavy in-memory** (cache metadata, config hot-reload): `StampedLock`
  optimistic — nhưng chỉ khi đo thấy lock là bottleneck thật.
- **State chia sẻ giữa thread**: ưu tiên `java.util.concurrent` (AtomicLong,
  ConcurrentHashMap, LongAdder) > tự cầm lock. Lock là phương án cuối.

## 🗄️ DB lock — optimistic vs pessimistic (case inventory)

| | Optimistic (`@Version`) | Pessimistic (`SELECT FOR UPDATE`) |
| --- | --- | --- |
| Cơ chế | UPDATE ... WHERE version=? ; affected=0 → fail → retry | DB giữ row lock tới hết tx, writer khác chờ |
| Hợp khi | Contention THẤP (đa số tx không đụng nhau) | Contention CAO trên cùng row (hot SKU) |
| Cái giá | Retry storm khi hot-row (lãng phí CPU/tx) | Throughput giảm (serialize), nguy cơ deadlock nếu lock nhiều row sai thứ tự |

Inventory-service Day 4 chọn **optimistic + `@Retryable`** ([InventoryService](../../services/inventory-service/src/main/java/com/ecom/inventory/application/InventoryService.java)):
đa số SKU contention thấp. Hot SKU flash-sale (1 SKU nghìn req/s) thì optimistic
retry storm → Day 33 chuyển sang **Redis Lua atomic decrement** (đẩy serialize
ra khỏi DB). Xem [issue 04](../issues/04-overselling-stock.md).

> 💡 **Senior vs junior**: junior thấy "lock = chậm" rồi bỏ lock → race. Senior
> hỏi *contention bao nhiêu* trước khi chọn: contention thấp đừng pessimistic
> (phí), contention cao đừng optimistic (retry storm). Đo, rồi chọn.

## ⚠️ Cạm bẫy

- `StampedLock` **không reentrant**: gọi đệ quy / lock lồng nhau = self-deadlock.
- Optimistic read mà KHÔNG copy field ra local trước `validate()` → đọc torn state.
- `ReentrantLock` quên `unlock()` trong `finally` → lock rò rỉ, deadlock toàn hệ.
- `synchronized` trên `Integer`/`String` cached (autobox / string pool) → vô tình
  share monitor toàn JVM.
- DB optimistic: retry trong **cùng** transaction = vô nghĩa (entity stale) — phải
  `REQUIRES_NEW` mỗi attempt (xem [04-optimistic-locking](04-optimistic-locking.md)).

## 🎤 Trả lời phỏng vấn

> **"`synchronized` vs `ReentrantLock`, khi nào dùng cái nào?"**
> `synchronized` cho block ngắn, đơn giản, JVM tự release — nhưng pin VT nếu
> block bên trong. `ReentrantLock` khi cần tryLock/timeout/interruptible/fairness,
> hoặc khi chạy virtual thread + có blocking trong CS (để unpin). Cái giá của
> ReentrantLock là phải tự `unlock()` trong finally.

> **"StampedLock nhanh hơn, sao không luôn dùng?"**
> Vì không reentrant + optimistic read dễ viết sai (quên validate → torn read),
> và chỉ thắng rõ khi read-heavy. Write-heavy hoặc cần reentrancy thì
> ReentrantLock an toàn hơn. Tối ưu sai chỗ = nợ kỹ thuật.

## 🔗 Related

- [`lessons/19b-virtual-threads-deep.md`](19b-virtual-threads-deep.md) — pinning chi tiết
- [`lessons/04-optimistic-locking.md`](04-optimistic-locking.md) · [`lessons/04b-transaction-isolation.md`](04b-transaction-isolation.md)
- [`issues/04-overselling-stock.md`](../issues/04-overselling-stock.md)
- [`interview/day-19-concurrency.md`](../interview/day-19-concurrency.md)
