# Interview — Day 19 · Java Concurrency (locks · Virtual Threads · distributed lock)

> **Status**: ✅ Done · Day 19

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopVN (Series-B, ~2M MAU), team Platform 6 người.
- **Role giao việc**: Anh Hùng (Staff Engineer) — sau sự cố báo cáo "daily
  inventory snapshot" bị nhân đôi số liệu đúng ngày cuối tháng.
- **Bạn**: Tech Lead backend — own concurrency primitives + distributed coordination.
- **Reviewer**: Anh Hùng soi 2 thứ: (1) benchmark có warmup tử tế không hay đo
  nhiễu JIT; (2) distributed lock có fencing token chưa, hay lại "trust lock".
- **Deadline**: 1 sprint. Demo: JMH report + JFR pinning trace + IT chứng minh
  fencing reject stale writer.
- **Constraint thực tế**: chỉ 1 Redis node (chưa cluster) → không giả định Redlock;
  preview API chỉ bật ở module lab, KHÔNG lan ra service production.
- **Definition of Done**: bench reproducible · pinning trace rõ có/không pin ·
  `RedisDistributedLock` owner-check release + fencing · snapshot job reject stale
  token · build green.

---

## Q1 — `synchronized` vs `ReentrantLock` vs `StampedLock`, chọn cái nào?

**Strong answer**: Ba cách giải mutual exclusion với cái giá khác nhau.
`synchronized` gọn, JVM tự release kể cả khi exception — nhưng **pin virtual
thread** nếu block bên trong (Java 21) và không có tryLock/timeout/interruptible.
`ReentrantLock` cho tryLock + timeout + fairness + interruptible, và **unpin VT**
(park qua `LockSupport`), đổi lại phải `unlock()` trong `finally`. `StampedLock`
có optimistic read **không khoá reader** → throughput read-heavy cao nhất (đo
được ~770× synchronized cho read thuần), nhưng **không reentrant** và optimistic
phải `validate()` sau khi copy field — quên = torn read.

> 🔧 Số đo thật (JMH 8 thread, read-mostly): synchronized ~7.3K, ReentrantLock
> ~20.8K, StampedLock optimistic ~5.6M ops/ms. Khoảng cách là **bậc độ lớn** vì
> optimistic read không acquire lock → không cache-line bouncing giữa core. Xem
> [lesson 19](../lessons/19-java-locking.md).

**Follow-up trap**: *"StampedLock nhanh hơn sao không luôn dùng?"* → vì không
reentrant + dễ viết sai optimistic read + ×770 chỉ đúng cho read thuần (writer
xen vào → validate fail → rớt về read-lock → khoảng cách thu hẹp). Tối ưu sai chỗ
= nợ kỹ thuật; chỉ dùng khi đo thấy lock là bottleneck thật.

---

## Q2 — Virtual thread pinning là gì? Detect & fix?

**Strong answer**: VT chạy bằng cách **mount** lên carrier (platform) thread và
**unmount** khi block I/O → 1 carrier phục vụ vạn VT. **Pinning** = VT không thể
unmount, bị "đóng đinh" vào carrier, khi (1) block bên trong `synchronized`
(monitor gắn carrier) hoặc (2) gọi native. Nhiều VT pin → cạn carrier pool →
throughput sụp về như platform pool. **Detect**: JFR event
`jdk.VirtualThreadPinned` (prod) hoặc `-Djdk.tracePinnedThreads=full` (dev).
**Fix**: đổi `synchronized` → `ReentrantLock`. (Java 24+ JEP 491 xoá pin cho
synchronized, nhưng ta chạy 21 nên vẫn phải né.)

> 🔧 Chứng minh bằng code: [`PinningDemo`](../../concurrency-lab/src/main/java/com/ecom/lab/pinning/PinningDemo.java)
> đếm event JFR — synchronized ~200 pin, ReentrantLock 0.

**Follow-up trap**: *"synchronized critical section ngắn có pin không?"* → có pin
trong lúc block, nhưng nếu KHÔNG block bên trong (chỉ tính toán nhanh) thì VT
không cần unmount nên không vấn đề. Pin chỉ hại khi có blocking trong CS.

---

## Q3 — Structured concurrency hơn gì `CompletableFuture.allOf`?

**Strong answer**: `StructuredTaskScope.ShutdownOnFailure` coi nhóm subtask như
1 đơn vị: cùng vào scope, cùng ra. 1 subtask fail → scope **tự hủy** sibling
(fail-fast), cancellation tự động khi đóng scope, quan hệ cha-con rõ → ít leak,
stacktrace sạch. `CompletableFuture.allOf`: 1 future fail thì các future khác
vẫn chạy tới hết (lãng phí), phải tự `cancel(true)` (dễ quên → leak), deadline
phải ghép thủ công từng future. Code: [`StructuredFanout`](../../concurrency-lab/src/main/java/com/ecom/lab/structured/StructuredFanout.java)
fan-out cart + product + inventory với `joinUntil(deadline)` + `throwIfFailed()`.

**Follow-up trap**: *"Đã production-ready chưa?"* → JEP 453 là **preview** ở Java
21 (cần `--enable-preview`, ép runtime cũng có cờ). Ta cô lập ở module lab; service
production hôm nay dùng `CompletableFuture` fan-out, migrate khi final (Java 25 JEP 505).

---

## Q4 — Redis distributed lock có safe không? Redlock debate?

**Strong answer**: `SET NX PX` là **best-effort** mutual exclusion — an toàn cho
giảm-trùng thông thường, KHÔNG safe vs GC pause/STW: process pause → lock expire →
process khác chiếm → process cũ tỉnh dậy vẫn nghĩ giữ lock → cả hai write
(split-brain). **Redlock** (≥3 node, majority quorum) cải thiện availability nhưng
Kleppmann phản biện vẫn không correct vs pause/clock-skew; antirez phản biện lại.
Kết luận thực dụng: cần correctness → **fencing token** (counter tăng đơn điệu,
resource reject token cũ), đừng tin lock alone.

**Follow-up trap**: *"Project em chọn gì?"* → 1 Redis node nên Redlock vô nghĩa.
Giữ SET NX làm lớp giảm-trùng + fencing token enforce ở Postgres
(`ON CONFLICT WHERE last_fencing_token < EXCLUDED...`) làm lớp correctness. Xem
[issue 19](../issues/19-redlock-correctness.md).

---

## Q5 — Distributed lock vs DB unique constraint vs optimistic lock cho payment idempotent?

**Strong answer**: Cho payment idempotent (cùng callback đến 2 lần) tôi **không**
dùng distributed lock — dùng **DB unique constraint** trên idempotency key +
catch `DataIntegrityViolationException` (Day 10). Lý do: invariant ("1 txn xử lý
1 lần") map thẳng vào 1 key → constraint là cách đơn giản + provably correct,
không cần điều phối. Optimistic lock (`@Version`) cho concurrent update cùng row
(inventory reserve). Distributed lock chỉ khi cần serialize đoạn xử lý KHÔNG map
được vào 1 key (leader-elect job) — và vẫn nên kèm fencing nếu write quan trọng.

**Follow-up trap**: *"3 cái này thay thế nhau không?"* → Không. Khác tầng: unique
constraint = dedup theo key; optimistic = chống lost-update cùng row; distributed
lock = mutual exclusion cross-process. Chọn theo *hình dạng invariant*, không theo
"cái nào mạnh nhất".

---

## 🤖 AI Playbook

- **AI làm tốt**: scaffold JMH boilerplate (`@State/@Benchmark/@Param`), viết Lua
  release script, draft khung 9-section issue, sinh table so sánh lock.
- **Prompt mẫu**:
  > "Viết JMH benchmark so sánh synchronized / ReentrantLock / StampedLock
  > optimistic cho read-mostly counter, 8 thread, throughput mode, 1 fork."
- **Risk**: AI hay viết distributed lock **thiếu owner-check khi release** (xoá
  nhầm lock người khác) và **thiếu fencing token** (đúng bug gây sự cố); benchmark
  AI sinh thường **thiếu warmup/fork** → số sai 10×.
- **Validate**: đọc kỹ (1) release có Lua compare token không, (2) có fencing
  monotonic không, (3) benchmark có `@Warmup`+`@Fork` không. Chạy thật JMH rồi
  đối chiếu xu hướng (StampedLock optimistic phải thắng read-heavy).

## 👥 Tech Lead Lens

- **Trade-off + scale 10×**: 1 Redis lock = SPOF + điểm nghẽn. Scale 10× KHÔNG
  nhảy sang Redlock (vẫn không cứu pause) — shard key lock hoặc bỏ lock dùng
  partition-by-owner; critical write luôn giữ fencing. VT thắng IO-bound, nhưng
  10× tải thì downstream (DB pool, connection) thành bottleneck mới → cần bulkhead
  (Semaphore) chứ VT không tự giới hạn.
- **Failure mode + triage 5 bước** (snapshot nhân đôi): (1) grep log
  `STALE-WRITER blocked by fence` — có = split-brain thật; (2) check GC log
  instance nghi vấn quanh thời điểm; (3) so `created_by_instance` 2 bản ghi; (4)
  xác nhận fencing token tăng đúng trong Redis (`GET fence:...`); (5) nếu fence
  chưa bật → đó là root cause, bật + backfill.
- **Junior + AI 2 lỗi dễ nhất**: (1) benchmark không warmup → kết luận sai về
  lock; (2) "acquire lock OK = an toàn write" → bỏ fencing → split-brain. Review
  kỹ đúng 2 chỗ này.

## 🔗 Related

- [`lessons/19-java-locking.md`](../lessons/19-java-locking.md) · [`lessons/19b-virtual-threads-deep.md`](../lessons/19b-virtual-threads-deep.md) · [`lessons/19c-distributed-lock-redlock.md`](../lessons/19c-distributed-lock-redlock.md)
- [`issues/19-redlock-correctness.md`](../issues/19-redlock-correctness.md)
- [`evolution/19-anh-bao-ve.md`](../evolution/19-anh-bao-ve.md)
