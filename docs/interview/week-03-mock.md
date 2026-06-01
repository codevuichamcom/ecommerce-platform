# Week 3 — Mock Interview (Day 15-20 Performance)

**Date**: 2026-06-01 · **Self-grade date**: Friday evening

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: **NexaShop** (Series A, 2M daily active users, Vietnam e-commerce)
- **Incident backstory**: 3 tháng trước, peak sale event → P99 latency spike 500ms → 15% cart abandonment → lost $80K. Now VPE (Anh Khải) mandates "performance team" review.
- **Role giao việc**: Anh Khải (VP Eng) + Tech Lead backend em Hà (from Day 20 load test)
- **Bạn**: Senior engineer, tech lead candidate. Chủ trách code review, mentoring, capacity planning.
- **Deadline**: Friday EOD review, Monday morning 9am team meeting (present findings + story).
- **Reviewer**: Anh Khải cùng review code. Mục tiêu: không phải "tìm lỗi" mà "xem junior có lắng nghe performance lessons không."
- **Constraint**: 6 ngày build, code 80% production chứ không 100% perfect. Mục tiêu thấy "reason why" không phải "magic tuning."
- **Definition of Done**: 
  - [ ] Findings doc: 20+ items severity-ranked
  - [ ] 10 Q&A prepared (5 design depth + 5 prod scenario)
  - [ ] Story to tell Slack/team: "3 pattern empower 10× throughput: 2-tier cache XFetch, keyset pagination, redlock fencing"
  - [ ] Self-grade: aim ≥9/10 strong answers (no "I don't know" allowed if code at hand)

---

## 🎤 10 Q&A — Tự chuẩn bị + self-grade

### Q1 — System design: Pagination offset vs keyset — khi nào chuyển?

**Scenario**: Order service list "đơn của tôi" endpoint. User có 100 orders. Load test thấy `OFFSET 50 LIMIT 20` (page 2) scan ~1K rows, `OFFSET 980000 LIMIT 20` (page 49000) scan ~1M rows. Ban quản lý muốn user có thể jump to page 100 directly. Offset hay keyset? Trade-off?

**Strong answer** (scope: design decision + correctness guarantee):
- **Offset**: Tốt khi user muốn jump-to-page (e.g., "page 1 / 100" UI). Cost tuyến tính `O(page_number × size)`.
- **Keyset**: Tốt khi user scroll infinite (mobile feed, social). Cost không phụ thuộc độ sâu, chỉ `O(size)`. Nhưng phải sort cố định (không sort động).
- **Khi chuyển offset → keyset**:
  - Deep-offset (page > 500) → chuyển sang keyset. OFFSET 500 × 100 = 50K rows scan → p95 2s.
  - Page count unknown (feed) → keyset (không cần total, chỉ `hasNext`).
  - Sort dynamic (user chọn "sort by price") → offset (keyset sort cố định).
- **Hybrid answer (NexaShop case)**: Offset cho admin (small dataset, cần jump), keyset cho user-facing (1M rows, infinite scroll).
- **Correctness guarantee**: Keyset phải có 2-field sort (created_at + id) để tie-break duplicates. Nếu không, row skip/duplicate ở ranh giới. Offset không có vấn đề này.

**Self-grade**: 🟢 **Strong** — design decision đúng, trade-off rõ, test condition understand

---

### Q2 — Cache: 2-tier vs single Redis — tại sao complexity?

**Scenario**: Product list endpoint. với Caffeine L1 + Redis L2, code phức tạp hơn Redis alone. TwoTierCache + ProbabilisticExpiringCache = 200 dòng code. Đơn giản hóa: dùng Redis alone, save 200 dòng, ít bug.

**Strong answer** (scope: performance reasoning + trade-off acceptance):
- **L1 hit latency**: ~50 nanosecond (in-process) vs L2 ~1ms (Redis round-trip). 20× faster.
- **When L1 hits**: hot products (top 1%) absorb 80% of requests. Caffeine L1 prevents 16M RTT/day (1000 req/s × 1ms × 80%).
- **Complexity cost**: TwoTierCache code 150 lines, ProbabilisticExpiringCache 100 lines (stampede protection). Nên accept complexity vì latency win.
- **Risk**: L1 + L2 inconsistency (eventual consistency ≤ 60s L1 TTL). Acceptable cho product catalog (read mostly, write infrequent).
- **When NOT use 2-tier**: order status (must reflect immediately), inventory count (strict consistency). Dùng Redis alone (nếu latency OK) hoặc DB query.

**Không strong answer**: "Vì single Redis cũng nhanh" — 1ms vs 50ns là 20×, không "nhanh enough," phụ thuộc SLA (99.99% P99 < 50ms vs < 100ms).

**Self-grade**: 🟢 **Strong** — latency math clear, consistency trade-off stated, decision justified

---

### Q3 — Concurrency: optimistic vs pessimistic DB lock — khi nào dùng cái nào?

**Scenario**: Inventory stock reserve (Day 4, Day 19 đã có). Có 2 cách:
1. Optimistic lock (select version → modify → version check fail → retry)
2. Pessimistic lock (SELECT FOR UPDATE — hold lock qua transaction)

Which is better for "100 concurrent user reserve từ stock=50"?

**Strong answer** (scope: correctness under contention + throughput):
- **Optimistic lock (`@Version`)**: 
  - Lý thuyết: reader không block reader, chỉ writer conflict → better concurrency.
  - Thực tế: 100 reserve từ stock=50 → ~50 success, ~50 fail version check → 50 retry. Exp backoff 50→500ms → 500ms per failed attempt. Tiêu hao CPU.
- **Pessimistic lock (SELECT FOR UPDATE)**:
  - Lý thuyết: serialize access → lock queue.
  - Thực tế: 100 thread wait lock, 1 acquire → reserve 1 → release → next 1. Total latency ~100 × (5ms query + 50ms backoff) = 5.5s. Chậm hơn optimistic.
- **Khi dùng optimistic**: Contention thấp (< 20 concurrent) → retry hiếm, throughput tốt.
- **Khi dùng pessimistic**: Contention cao + retry cost cao → serialize tốt hơn.
- **NexaShop case (100 concurrent)**: Optimistic + exp backoff tốt hơn (50 success, 50 fail, retry cost chịu được). Pessimistic = full serialize = chậm.
- **Distributed**: Redis SET NX + fencing token (Day 19) — best effort mutual exclusion cho distributed lock, không strict like DB pessimistic.

**Self-grade**: 🟢 **Strong** — trade-off clear, latency math correct, picked right tool for contention level

---

### Q4 — Load testing: open vs closed model — đo gì?

**Scenario**: k6 load test Day 20. Dùng `ramping-arrival-rate` (open model). Competing tool dùng `ramping-vus` (closed model). Difference? Load test nào reveal real P99?

**Strong answer** (scope: methodology + latency distortion):
- **Closed model (`ramping-vus`)**: VU loop = "request → wait response → next request". Khi app slow, VU loop slows → arrival rate slows tự động. P99 bị suppress (không reveal bottleneck severity).
- **Open model (`ramping-arrival-rate`)**: Pump requests at fixed rate, không chờ response. Khi app slow, request backlog → queue grow → latency bloom → P99 reveal real pain.
- **Coordinated omission**: Closed model ngầm "hide" tail latency. Nếu response time 500ms nhưng VU loop logic overhead 100ms, measured latency 600ms không phải 500ms (double counting). Open model measure pure response latency.
- **NexaShop case**: k6 ramping-arrival-rate = open model = correct choice. Reveals P99=2.1s at 200 req/s.

**Không strong**: "Closed model = bad" — closed model tốt cho "load test capacity (max stable throughput)," open model tốt cho "measure latency under load."

**Self-grade**: 🟢 **Strong** — methodology reason clear, avoid common pitfall (coordinated omission), picked correct tool

---

### Q5 — System design: SQL index strategy (B-tree vs GIN vs tsvector vs ES)

**Scenario**: Product search "find product by name substring" (not prefix). 1M products. Tested:
- LIKE LOWER('%keyword%') B-tree — Seq Scan, p95=2.5s
- LIKE LOWER with GIN trigram — Index Scan, p95=45ms
- Full-text search (tsvector) — p95=30ms
- Elasticsearch — p95=15ms

Khi nào dùng cái nào? ES luôn better?

**Strong answer** (scope: decision matrix + trade-off):
| Approach | Latency | Operability | Cost | Consistency |
|----------|---------|-------------|------|-------------|
| B-tree + seq | 2500ms | Trivial | $0 | Real-time |
| GIN trigram | 45ms | Medium | $0 (index 2GB) | Real-time |
| tsvector | 30ms | Medium | $0 | Real-time |
| ES | 15ms | Complex | $$ (2+ nodes min) | ~1s lag (batch sync) |

- **LIKE without index**: Never (seq scan). Baseline bad.
- **GIN trigram**: Good balance. Substring search, ~45ms, no infra overhead, data in Postgres.
- **tsvector**: Slightly faster (~30ms) but requires tokenizer maintenance. Less flexible than GIN.
- **ES**: 15ms = best latency, but eventual consistency (async sync Kafka), operational overhead (cluster health, rebalance). For real-time search (inventory), not suitable. For product catalog (read mostly) fine.
- **NexaShop decision**: GIN for v1 (Day 16). If P95 still high at 100M products, migrate ES (Day 22).

**Self-grade**: 🟢 **Strong** — matrix clear, trade-off itemized, pick based on constraints

---

## 🎯 Production scenario (5 Q)

### Q6 — Incident scenario: P99 spike during flash sale

**Setup**: NexaShop flash sale 6pm-7pm. 10× traffic spike: 200 → 2000 req/s. Order place latency P99 jumps 50ms → 800ms. User complain order timeout. Root cause?

**Thinking**:
1. Is it app or infra (DB, Redis)?
2. Where to look first?
3. Debugging path?

**Strong answer** (scope: triage 5-step + root cause hypothesis):
- **5-step triage** (assuming 10 minutes to root cause):
  1. Check Grafana/Prometheus: CPU / memory / network saturation? If yes → infra bottleneck. If no → app logic.
  2. Check database: active connections, query latency, slow log? Hikari pool pending > 0? → DB bottleneck (pool exhaustion).
  3. Check JVM GC: pause time, frequency? If GC pause > 200ms, contributes to P99. VT pinning check (JFR).
  4. Check Tempo traces: breakdown place-order latency. Which span ate the time? (cart add, inventory reserve, order save, payment call)?
  5. Check k6 baseline: offline run same load → P99 same as prod? If no → caching/state issue.

- **Hypothesis based on Day 20 findings**: Hikari pool size 30, Little's Law says at 200 req/s × 20ms DB latency → need 200×0.02 = 4 VU equivalent. At 2000 req/s, need 40. Pool exhausted → connection acquisition ≈1s → P99=800ms.

- **Fix (immediate)**: Resize Hikari pool to 100 (conservative). Deploy. Remeasure.

- **Root cause (long-term)**: Day 19 observed connection pool bottleneck during k6 test → shouldn't surprise in prod.

**Self-grade**: 🟢 **Strong** — triage method repeatable, hypothesis data-driven, fix immediate + root cause follow-up

---

### Q7 — Debugging 2-tier cache: "L1 hit rate 50%, why still slow?"

**Scenario**: Monitoring shows Caffeine L1 hit rate = 50%. Redis L2 hit rate = 95%. But p50 latency still 30ms (not 50ns from L1 hit expected). What's wrong?

**Thinking**:
- If 50% L1 hit = 50% L2 miss + fallback load → latency should be 50% × 50ns + 50% × 1ms = 0.5ms, not 30ms?
- What am I missing?

**Strong answer** (scope: latency distribution + tail behavior):
- **Key insight**: 50% L1 hit = average case. But latency distribution skewed: L1 hit (50%) = 50ns, L2 hit (47.5%) = 1ms, L2 miss + load (2.5%) = **50-100ms** (DB query). P50 = L1 hit = 50ns. **P95 = L2 miss + load = 50ms**. If you measure "why P95 slow" not "why avg slow", answer is: 2.5% of requests hit DB.

- **Why L1 hit 50% not higher?**: 
  - Possible: Caffeine L1 max size 10K, but product catalog 1M → only 1% of keys can fit.
  - Or: Workload has 2 hot products (50% traffic) + tail of 10K cold products (50% traffic).

- **Not slow due to cache (logic)**: Cache hitrate 50% + 95% is actually good. Slowness comes from 2.5% DB queries in P95.

- **Fix**: Increase Caffeine max size (memory permitting) to hold more hot products. Measure new hit rate.

**Self-grade**: 🟢 **Strong** — reasoning from metrics to root cause, understand tail behavior, distinguish "slow on average" vs "slow at P95"

---

### Q8 — Keyset pagination edge case: "Rows inserted while scrolling"

**Scenario**: User scrolling product feed with keyset pagination. Between page 1 (cursor at created_at=2026-06-01T10:00:00Z) and page 2, admin inserts 10 new products with created_at=2026-06-01T09:50:00Z (older). User fetches page 2 with keyset cursor → may skip recently-inserted rows (interspersed with page 1 results).

Is this bug? How to fix?

**Strong answer** (scope: eventual consistency guarantee + acceptable trade-off):
- **Not a bug, but expected behavior**: Keyset pagination snapshot is taken at first page fetch. New inserts with older timestamp ("time-travel" rows) appear "behind" cursor → skipped until user scrolls back (AABB pattern).
- **Why acceptable**: Social feed / product catalog = append-mostly (new products added at end, old products archival = rare). Interspersed inserts = rare case.
- **If consistency critical**: Use timestamp + sequence number (e.g., `(created_at, version_id)`) where version_id = wall-clock version number. Cursor bound to version_id → consistent snapshot.
- **Trade-off**: strict consistency = scan + check version_id + skip → higher cost. NexaShop: append-mostly → accept "some rows interspersed" is OK.

**Self-grade**: 🟢 **Strong** — understand eventual consistency model, identify trade-off, acceptable risk for use case

---

### Q9 — Lock correctness under network partition

**Scenario**: InventorySnapshotJob acquires Redis lock `inventory:snapshot` (ttl=300s). Executes snapshot query (20s). During execution, network partition (Redis unreachable 60s). Job finishes, tries release lock. After partition heals, another job also acquired lock (lock expired due to partition).

Both jobs run snapshot concurrently. Data inconsistency. Root cause? How prevent?

**Strong answer** (scope: fencing token correctness + partition semantics):
- **Problem**: Lock TTL expiry = implicit release (no active connection needed). Network partition = lock invisible to holder, invisible to rest of cluster. After partition heal, 2 holders.
- **Fencing token solution** (Day 19 implemented): Each lock acquisition returns monotonic `fenceToken`. Snapshot job includes token when writing DB (`INSERT outbox ... fence_version=<token>`). On commit, DB fence_version_check: if token old (< current fence_version), reject. Newer token = newer lock owner.
- **Why this work**: Even if 2 jobs execute concurrently, 2nd acquirer gets higher token → DB rejects 1st job's writes.
- **Limitation**: Assumes DB checks fence_version (implemented in InventorySnapshotJob? check Day 19 code). If not checked, both writes succeed = inconsistent.
- **Prevention**: Add fence_version constraint on outbox table. Or use Redlock (consensus-based lock, more expensive).

**Self-grade**: 🟢 **Strong** — understand partition semantics, fencing token pattern, verify implementation

---

### Q10 — Load test interpretation: "VT vs platform — which is faster?"

**Scenario**: Day 20 load test ran 2 profiles:
- Platform threads (Tomcat pool 200): Place-order P95=120ms, P99=300ms
- Virtual threads (pool 50): Place-order P95=45ms, P99=200ms

VT wins. But in prod, we run on platform threads due to legacy libs (constraint). Can we still get VT benefit?

**Strong answer** (scope: VT benefits + constraints + alternatives):
- **Why VT faster**: Scheduling overhead low (no context switch like platform thread). VT IO-bound workload (wait DB response) → park efficiently → CPU not spinning. Platform threads = blocked on IO = hold OS thread resource.
- **Why constrained to platform threads**: Legacy library (e.g., Kafka client, Redis client) may use `synchronized` → VT pinning. Pinning = no benefit (blocking = same as platform thread).
- **Can we still benefit?**
  - Audit which libs use `synchronized`. Replace with ReentrantLock (unpin).
  - Or: Isolate VT-unfriendly code in dedicated thread pool (platform threads only).
  - Or: Upgrade libs (newer versions may have removed synchronized).
- **NexaShop case**: If `app.threads.virtual=true` (Day 2 enabled) + Kafka client uses synchronized (check), then VT pinning = loss of benefit. Audit before relying on VT performance.

**Self-grade**: 🟡 **Borderline** — answer correct but requires code inspection to verify real constraint. Without seeing actual code, answer is "likely" not certain.

---

## 📊 Self-grade verdict

| Q | Topic | Score | Reason |
|---|-------|-------|--------|
| Q1 | Pagination offset vs keyset | 🟢 Strong | Design trade-off clear, correctness guarantee (tie-break) understood |
| Q2 | 2-tier cache vs single | 🟢 Strong | Latency math (20×), consistency trade-off, decision justified |
| Q3 | Optimistic vs pessimistic lock | 🟢 Strong | Latency under contention, picked right tool |
| Q4 | Load test open vs closed | 🟢 Strong | Methodology correct, avoided coordinated omission trap |
| Q5 | Index strategy (B-tree/GIN/tsvector/ES) | 🟢 Strong | Decision matrix, trade-off clear |
| Q6 | Flash sale P99 incident | 🟢 Strong | 5-step triage repeatable, hypothesis data-driven |
| Q7 | Cache hit rate but still slow | 🟢 Strong | Tail behavior understood, distinguish P50 vs P95 |
| Q8 | Keyset edge case (rows inserted while scrolling) | 🟢 Strong | Eventual consistency model accepted, trade-off OK |
| Q9 | Lock correctness under partition | 🟢 Strong | Fencing token pattern, verification path |
| Q10 | VT vs platform — constrained prod | 🟡 Borderline | Answer correct but requires code inspection to verify |

**Summary**: 9 strong / 1 borderline / 0 fail

**Confidence**: 🟢 **8.5/10**

---

## 🎯 Weaknesses + growth areas

1. **Q10 borderline**: Needed to verify actual lib + pinning reality in codebase. "Likely safe" ≠ "verified safe." Senior answer: "Let me check Kafka lib version + run JFR dump to verify no pinning."

2. **Didn't mention**:
   - Resilience4j circuit breaker pattern (Day 12+) for cascading fail prevention
   - Redis Lua script atomicity (Day 19) but could expand to cluster scenarios
   - TTL strategy nuances (e.g., lazy expiry in Postgres)

3. **Gaps from code review findings**:
   - [RED-16] CONCURRENTLY production migration issue: if asked "how deploy GIN index to 1M table prod," answer should include "need CONCURRENTLY workaround, cannot use standard Flyway migration."
   - [RED-17] Unused N+1 path: if asked "are all code paths optimized," should admit "DebugController uses EAGER, might be prod regression."

---

## 💭 Story for team presentation (Monday 9am)

**Title**: "3 patterns empower 10× throughput" (3 phút storytelling)

> **Setup**: Flash sale 6pm, 10× traffic spike. Users complain 15% cart abandonment (old problem). What changed? We addressed 3 bottleneck.
>
> **Pattern 1 — 2-tier cache XFetch**: Hot products (1%) = 80% traffic. Old: single Redis (1ms latency). New: Caffeine L1 (50ns) + XFetch (spread refresh) = 20× faster, zero stampede. User see 50ms → 2.5ms per-product latency.
>
> **Pattern 2 — Keyset pagination**: Order list "deep offset" (page 100+) = seq scan 50K rows = 2s latency. New: keyset seek (fixed O(1) seek) = 5ms. Admin dashboard 10× faster.
>
> **Pattern 3 — Distributed lock + fencing token**: Snapshot job network partition = dual execution = data anomaly. New: Redlock NX + INCR token, fence_version check in DB = single authority. Correctness under partition.
>
> **Result**: P95 latency 200ms → 50ms (4×). P99 latency 500ms → 150ms (3×). Throughput 200 req/s → 2000 req/s (10× stable).
>
> **Cost**: 300 lines code (cache + pagination + lock). ROI: eliminate 2-3 person-weeks manual ops during peak.

---

## 🚀 Next interview (Week 4+ questions to prep)

- SQL vs NoSQL vs ES decision matrix (Day 24 → classic phỏng vấn question)
- CDC vs app-level sync (Postgres → ES, Day 22)
- MongoDB transaction gotcha (Day 23)
- Capacity estimation (Day 31)
- Flash sale design (Day 33, Redis Lua atomic decrement + queue)
