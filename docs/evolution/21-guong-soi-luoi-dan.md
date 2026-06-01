# Chương 21 · 🪞 Gương soi và lưới đan

**Day 21 — Review performance + interview round (Week 3 mock)**

---

> *"Bạn không sợ gương. Bạn sợ cái gương chỉ ra rằng cái mặt bạn nhìn thấy hôm qua đã có nếp nhăn ở góc mắt. Đó là nơi phát hiện sự thật. Và phỏng vấn là ngày người khác cầm gương đó lên mặt bạn."*

---

## 📍 Nơi ở trong câu chuyện

Tuần trước (Chương 20), hệ thống học được cách để *trỗi dậy* dưới áp lực. Load test k6 là buổi tập dượt: 200 → 2000 req/s, thấy cổ bị bóp — **connection pool bottleneck**. Bác thủ huấn luyện viên tàn nhẫn (Ông Khải từ NexaShop) đứng bên cạnh, gật đầu: *"Lần sau tránh nhé."*

Hôm nay — Day 21 — không phải tập nữa. Hôm nay là **kiểm tra**. Kiểm tra code có sạch không. Kiểm tra hiểu gì. Kiểm tra có thể giải thích cho cả team nghe được không.

Và đó là lý do chúng ta cần **gương** 🪞 — cái gương chỉ ra điều ta không muốn nhìn thấy, và **lưới đan** 🕸️ — chuỗi câu hỏi để bó quanh con người ta tới khi không còn nơi trốn.

---

## 🪞 Gương (Code review: facing truth)

Năm phút, bạn lục lại **6 ngày** của tuần (cache, SQL, pagination, lock, load test). Chạy bắn **23 findings** vào từng file, từng dòng code. Không tìm để "soi mói," tìm để **hiểu**: cách nào làm đúng, cái nào là debt, những gì sẽ cắn lại.

### 🔴 Sáu mẫu đỏ (Red findings)

Đây là loại phát hiện khiến bạn muốn lẩu quất trong nhà:

1. **Metadata unbounded** (Day 15): ConcurrentHashMap `fetchMetadata` lớn lên mãi không cắt. Khi cache churn cao (key rotation liên tục), map có thể chứa vài ngàn ghost entries → memory leak slow.
   - *Cảm xúc*: "Code tao vẫn chạy tốt mà?" — đúng, nhưng cách hoạt động sai.

2. **CONCURRENTLY missing** (Day 16): Flyway không thể dùng `CREATE INDEX CONCURRENTLY` (cần tx riêng). 1M+ product table, index GIN mất 30s → table lock. Prod sáng mai sẽ thấy "write timeout."
   - *Cảm xúc*: "Còn 20 người chờ checkout, tao lock table, CTO gọi..."

3. **Unused N+1 path** (Day 17): DebugController chứng minh 4-layer fix, nhưng `OrderController.listMyOrders()` vẫn dùng cái EAGER cu lỳ cũ. Real traffic vẫn N+1.
   - *Cảm xúc*: "Demo green, prod vẫn chậm. Code review sao nhìn không thấy?"

4. **Cursor no checksum** (Day 18): Opaque token không ký. Client craft random base64 → `decode()` nhận, giả mạo cursor, bị lọt lưới validation.
   - *Cảm xúc*: "Là lỗ hổng amà không critical — tệ hơn: khó debug."

5. **Lock fencing token race** (Day 19): `SET NX` rồi `INCR` hai lệnh riêng. INCR fail → token=0 invalid. Caller không biết, dùng token=0 → fence_version check bypass.
   - *Cảm xúc*: "Thường không bị, nhưng khi bị (Redis OOM giờ cao điểm) là data toang."

6. **k6 VU marginal** (Day 20): 100 VU cho 200 req/s. Nếu latency thực tế 500ms (không 200ms như ước), k6 bị bottleneck → test kết quả fake.
   - *Cảm xúc*: "Chính mình thổi kèn chiến thắng trong trận thua."

**Cảm xúc khi đó**: không phải *"tớ lỗi,"* mà *"tớ làm tốt 80%, nhưng 20% lỗi ơi, xin nhẹ tay."*

### 🟡 Tám cái vàng (Yellow findings)

Đây là loại **design debt** — code chạy, nhưng giả định yếu. Kiểu như bạn đi bộ qua chiếc cầu, an toàn, nhưng notice dây cáp nó hơi mục rữa:

- **XFetch reset metadata** (Day 15): `put()` reset `fetchDurationMs=1` → mất tín hiệu cost fetch. Double-refresh gần expiry.
- **Partial index bias** (Day 16): `WHERE status='ACTIVE'` → admin query all status bỏ sót index → full scan.
- **Page COUNT wasteful** (Day 17): Projection trả `Page` → Spring auto generate COUNT query (query #2) dù không cần total.
- **Keyset sort hardcoded** (Day 18): SQL sort cố định, index V6 phải match — đổi sort mà quên update index → biet đầu tìm bug ở đâu.
- **Metadata per-instance** (Day 19): Fetch metadata die theo pod → restart pod = metadata reset = XFetch ineffective sau restart.
- **k6 no read path** (Day 20): Load test chỉ place-order (write), không "user views order" → missing P95 read-after-write.

**Cảm xúc lúc đó**: *"Không critical, nhưng giống như uống cà phê lúc 10 tối — vẫn được, nhưng chắc sẽ hối hận lúc 1h sáng."*

### 🟢 Chín dấu xanh (Green findings)

Code có chỗ **làm đúng**, xứng đáng đưa lên bàn sáng suốt chiều lạnh lẽo:

- **Polymorphic deserialize security** (Day 15): Jackson whitelist chặt chẽ.
- **L2 evict-before-L1 strategy** (Day 15): Explain tại sao, không phải "cứ làm thế."
- **ANALYZE after index** (Day 16): Nhỏ nhưng cứu mạng (planner mới cập nhật stats).
- **Projection DTO** (Day 17): Không load full entity → đúng abstraction.
- **Base64 URL-safe** (Day 18): Opaque token format chọn đúng.
- **Lua release script** (Day 19): Prevent stale-token-release race.
- **Fencing token monotonic** (Day 19): Kleppmann correctness model.
- **Open-model load test** (Day 20): Tránh coordinated omission trap.
- **Profile tagging** (Day 20): VT vs platform thread tách riêng.

**Cảm xúc lúc đó**: *"Ít nhất có mấy cái tớ làm đúng. Gương không toàn đen."*

---

## 🕸️ Lưới đan (Mock interview: bị vây hỏi)

Ngồi xuống, 10 câu hỏi từ Anh Khải (CTO NexaShop). 5 câu lý thuyết (design), 5 câu thực chiến (production scenario). Mỗi câu là một sợi dây — nó **buộc bạn phải nói rõ**, không được "hơi hơi, chắc chắn, đại khái."

### 5 câu lý thuyết (design depth)

1. **Offset vs keyset** — khi nào dùng cái nào? Khi phải jump-to-page (offset) vs infinite scroll (keyset). Correctness guarantee (tie-break với id). Hybrid cho NexaShop.
   - **Trap**: "Keyset luôn tốt hơn" — sai, nếu cần jump-to-page thì offset đơn giản hơn. Keyset sort phải cố định.

2. **2-tier cache** — tại sao complexity? 20× latency win (50ns vs 1ms). L1 hit 80% traffic → tiết kiệm RTT. Khi nào KHÔNG dùng (order status, inventory = strict consistency).
   - **Trap**: "Single Redis cũng đủ nhanh" — 1ms vs 50ns là 20×, không phải tùy. SLA 99.99% thì có khác.

3. **Optimistic vs pessimistic lock** — contention cao, pick cái nào? Optimistic good ở contention low, pessimistic (serialize) good ở contention high. 100 concurrent reserve từ stock=50 → optimistic + retry tốt hơn.
   - **Trap**: "Optimistic luôn tốt vì reader không block" — sai nếu retry cost cao.

4. **Open vs closed load test model** — cách nào reveal P99 thật? Open = pump request cố định rate = latency bloom = real pain. Closed = VU loop slow down when app slow = hide tail latency (coordinated omission).
   - **Trap**: "Đã chạy load test là đủ" — sai nếu chạy closed model (false sense of security).

5. **Storage choice matrix** — B-tree, GIN trigram, tsvector, ES — khi nào pick cái nào? Trade-off latency / operability / cost / consistency. GIN cho v1 (0 infra), ES cho v2 (batch sync = eventual consistency).
   - **Trap**: "ES luôn faster" — đúng (15ms vs 45ms) nhưng async sync = lag, operational cost $$, overkill cho product catalog v1.

### 5 câu thực chiến (production incident)

6. **Flash sale P99 spike** — 10× traffic, P99 jump 50ms → 800ms. Triage 5 bước. Root cause (pool bottleneck). Fix (Little's Law resize).
   - **Giải**: (1) CPU/mem satur? (2) DB slow? Hikari pending? (3) GC pause? (4) OTel trace breakdown? (5) k6 baseline? Hypothesis: pool 30 + 20ms latency = 0.6 capacity, 2000 req/s need 40. Double to 60, retest.

7. **Cache hit 50% but P95 still 30ms** — metrics không match. Hiểu tail latency phân布: L1 hit = 50ns, L2 hit = 1ms, L2 miss + load = 50ms. P50 = 50ns, P95 = 50ms (2.5% hit DB). Hit rate 50% chỉ là average.
   - **Giải**: Hỏi "*lại* P95 hay P50?" Metrics cần phân tách percentile, không chỉ "average."

8. **Keyset edge case** — mấy users chèn between page 1-2 → rows interspersed. Bug không? Accept không? Consistent snapshot = version_id bound, trade-off consistency vs cost. NexaShop append-mostly → accept.
   - **Giải**: Not a bug, expected behavior. Fencing = snapshot đầu. New inserts behind cursor = interspersed. Acceptable risk cho use case.

9. **Network partition lock** — lock expire, 2nd acquirer lock → dual execution. Fencing token correct? Verify check trong code. Assume DB fence_version check in place.
   - **Giải**: Fencing token = monotonic INCR. 2nd acquirer higher token. DB fence_version reject old token. Kleppmann correctness model.

10. **VT vs platform production** — VT win ở load test, nhưng prod = platform legacy. Can benefit? Audit `synchronized` usage. If lib pins VT = no benefit. Upgrade lib or isolate pool.
    - **Giải**: VT benefit lost nếu pinning. Check JFR `jdk.VirtualThreadPinned`. Upgrade deps hoặc isolate thread pool.

### Cách bác CTO hỏi

Anh Khải không hỏi để bạn nói "đó là pattern A" và mình bỏ qua. Anh hỏi để **bạn phải think outloud**:

- "Nếu pool 10 thay vì 100 thì sao?"
- "Giả sử user logout giữa chừng keyset scroll, cursor cũ tính được không?"
- "Index V6 bị drop, keyset query chạy thế nào?"
- "XFetch reset metadata = gì hệ quả?"

Mỗi câu = một **cơ hội để làm lộ chỗ nối chưa cẩn thận**.

---

## 📊 Scorecard Day 21

Sau gương + lưới đan:

```
┌─────────────────────────────────────────────────┐
│ 🎯 WEEK 3 SUMMARY                               │
├─────────────────────────────────────────────────┤
│ ✅ Cache 2-tier: 20× latency                    │
│ ✅ SQL index GIN: 57× faster search             │
│ ✅ Keyset pagination: O(1) vs O(n)              │
│ ✅ Distributed lock + fence: partition safe     │
│ ✅ Load test open-model: P99 reveal             │
├─────────────────────────────────────────────────┤
│ 🔴 6 red findings (debt immediate)              │
│ 🟡 8 yellow findings (refactor soon)            │
│ 🟢 9 green findings (patterns solid)            │
├─────────────────────────────────────────────────┤
│ Interview result: 9 strong / 1 borderline / 0 f │
│ Confidence: 8.5/10                              │
├─────────────────────────────────────────────────┤
│ Vibe: "Tuần này tao hiểu gì, tao biết nói rõ"   │
└─────────────────────────────────────────────────┘
```

**Bác thủ quỹ cơm bữa nay** (nếu bác này là "bác code review"):
- ✅ Thấy tao hiểu lý thuyết (offset/keyset trade-off, fencing token)
- ✅ Thấy tao biết đọc trace (pool pending, GC pause, span latency)
- ✅ Thấy tao dám thừa nhận weakness ([RED-16] CONCURRENTLY, [RED-17] unused N+1)
- ✅ Thấy tao có plan fix (ghi vào README ngày đầu tuần sau)

**Cái bác không thấy rõ lắm**: VT pinning edge case (cần JFR để verify thực sự). Borderline Q10.

---

## 🎬 Kết thúc ngày

Tối chiều, bạn ghi 1 dòng vào ROADMAP:

```
Day 21 ✅ Done · 2026-06-01
  - Review: 23 findings (6 red, 8 yellow, 9 green)
  - Interview: 10 Q/A, 9 strong, 1 borderline
  - CV bullet: 4× latency, 10× throughput
  - Confidence: 8.5/10 — ready for Week 4 (data layer)
```

Anh Khải ghi note:
> *"Tuần này tao thấy con (bạn) không chỉ **biết chạy code**, mà **biết lý do tại sao**. Đó là khác biệt senior vs junior. Week 4 em tiếp tục, Elasticsearch + MongoDB decision matrix. Đừng panic, em đã hiểu cách đo — đo cái đó rồi quyết định."*

Gương + lưới đan đã hoàn tất công việc. Hôm nay em nhìn thấy truth. Ngày mai em phải nói chuyện với người khác về truth đó. **Đó là job của leader.**

---

*→ Tuần tới: Elasticsearch, MongoDB, cái nào khi nào. Không phỏng vấn nữa — lúc này là **xây dựng quyết định framework** để tấn công data layer theo chiều sâu.*
