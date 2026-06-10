# Day 15 — 🎤 Interview · 2-tier Cache (Caffeine L1 + Redis L2)

> **Topic**: Cache-aside, 2-tier hierarchy, stampede protection, hot key, multi-instance invalidation.
> **Related**: [Lesson 15](../lessons/15-cache-strategies.md) · [Performance 15](../performance/15-cache-aside.md) · [Performance 15b](../performance/15b-two-tier-cache.md) · [ADR 012](../decisions/012-two-tier-cache-caffeine-redis.md) · [Issue 15](../issues/15-cache-stampede.md) · [Issue 15b](../issues/15b-hot-key.md)

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: NexaShop — ecommerce Series B, scale 5k → 50k DAU sau campaign Tết.
- **Role giao việc**: Anh Khải (Engineering Manager backend, ex-Tiki) — Slack 9h sáng "phòng product complain trang detail load chậm; CFO hỏi tại sao infra cost x3 nhưng latency tệ hơn. Tuần này deliver cache layer cho product-service, demo Friday".
- **Bạn**: Senior Backend (Tonny) — own product-service performance + lead 1 mid-level (Phong) implement.
- **Reviewer**: Anh Khải + Chị Linh (Principal Engineer) — soi invalidation correctness, hit ratio number, stampede protection có hay không, observability đầy đủ chưa.
- **Deadline**: 1 day code + 1 day demo. Friday show số trên Grafana.
- **Constraint thực tế**: Redis cluster đã có (cart-service dùng), không được tăng infra; product-service đang chạy 4 pods → invalidation phải đồng nhất multi-instance; không break consistency (price update reflect ≤1s).
- **Definition of Done**: P99 `getProduct` ≤80ms (từ 250ms), Redis hit ≥70%, Caffeine hit ≥80% với hot product, invalidation propagate ≤1s, có dashboard tile hit ratio.

---

## Q1 — "2-tier cache vs chỉ Redis vs chỉ Caffeine — khi nào chọn cái nào?"

**Strong answer**:

Lựa chọn dựa trên matrix 4 chiều: **latency / consistency / multi-instance / infra cost**.

- **Chỉ Caffeine (L1 only)** — chọn khi: single-instance service, hoặc data immutable (config flags, lookup table tĩnh). Win latency 50ns, không cần Redis. Lose multi-instance consistency.
- **Chỉ Redis (L2 only)** — chọn khi: cần consistency multi-instance mạnh, write update phải reflect ≤1s ở mọi pod. Win consistency, lose latency (1ms RTT mỗi hit) + bandwidth bottleneck khi peak.
- **2-tier** — chọn khi: read-heavy, tolerate eventual consistency ≤TTL L1, traffic Redis sắp saturate. Win latency mượt + giảm Redis load 80-95% + resilient với Redis flap. Lose: L1 inconsistency window ≤TTL.

Với NexaShop catalog: chọn 2-tier. Product update <1×/giờ thực tế, 60s stale window acceptable. Redis đang share cart-service → cần giảm bandwidth.

**Follow-up trap**: *"Vậy nếu 10 instance, L1 stale 60s — khi admin đổi giá thì sao?"*

Đúng — đây là **eventual consistency window**. 2 cách xử:
1. **Strict consistency**: thêm Redis pub/sub channel `cache:invalidate`. Instance A update → publish key → 9 instance còn lại subscribe → evict L1 local. Lag ms-scale.
2. **Bypass cache cho field critical**: price không cache (hoặc TTL 5s). Catalog metadata cache 60s. Trade-off: 2 code path, phức tạp hơn.

Tôi sẽ chọn (1) cho Day 20+, nhưng Day 15 chấp nhận (2) — vì product update không thường xuyên, 60s lag OK; pub/sub thêm code mà chưa cần.

---

## Q2 — "Cache stampede là gì, fix bằng cách nào? Giải thích XFetch."

**Strong answer**:

**Stampede** = thundering herd. Hot key expire đồng thời 1000 concurrent request đang đọc → 1000 cùng miss → 1000 cùng gọi loader (DB query) → DB CPU 100% → cascading fail.

5 cách fix phổ biến — đã so sánh chi tiết ở [issue 15](../issues/15-cache-stampede.md):

1. **Distributed lock** (Redis SET NX) — strict 1 process refresh, nhưng lock orphan risk (GC pause / OOM).
2. **In-process single-flight** (Caffeine LoadingCache) — lock-free, nhưng chỉ per-JVM.
3. **XFetch (probabilistic early expiration)** — chọn cho NexaShop.
4. **Refresh-ahead** (cron job) — predictable nhưng cần biết hot key trước.
5. **Stale-while-revalidate** — UX tốt, nhưng phức tạp.

**XFetch logic (Vattani et al. 2015)**: trong cửa sổ early-expiration (ví dụ 30s cuối TTL), mỗi lookup chạy công thức:

```
delta * β * -ln(random ∈ (0,1))  ≥  remaining_ttl
```

Nếu true → coi như expired, gọi loader sớm. Probability tăng dần khi `random` nhỏ và `remaining` ngắn → **spread compute trước expire** thay vì burst tại expire.

Code thực tế: [ProbabilisticExpiringCache.shouldEarlyRefresh](../../services/product-service/src/main/java/com/ecom/product/config/cache/ProbabilisticExpiringCache.java).

**Follow-up trap**: *"XFetch vs distributed lock — chọn nào cho cache 1 báo cáo financial tốn 30s compute?"*

Lock. Vì:
- 30s compute là expensive — duplicate 2-3× = 60-90s CPU lãng phí.
- XFetch không strict, có thể 4 process duplicate compute (probabilistic).
- Financial report ít hot key — lock contention thấp.
- Trade-off lock orphan: dùng Redis lock với TTL = compute time + buffer (45s); thêm fencing token nếu paranoid.

Cache product 30ms compute, throughput cao, hot key nhiều → XFetch hợp. Cache report 30s compute, low frequency → lock.

---

## Q3 — "Invalidation strategy: TTL vs explicit evict vs pub/sub — trade-off?"

**Strong answer**:

3 chiến lược, trade-off chính là **consistency window vs complexity**:

| Strategy | Consistency window | Complexity | Khi dùng |
|----------|--------------------|------------|----------|
| **TTL only** | Đúng TTL (5s-1h) | Đơn giản nhất | Catalog static, data ít đổi |
| **Explicit `@CacheEvict`** | Cho L2 tức thì, L1 ≤TTL trên instance khác | Vừa | Default cho most case |
| **Pub/sub invalidation** | ms-scale across instance | Phức tạp + thêm infra | Strict consistency, financial, stock |

Hiện NexaShop dùng (2). Day 20+ wire (3) qua Redis channel.

**Follow-up trap**: *"Multi-instance: pod A update DB + evict L1_A, pod B vẫn cache L1_B cũ. Khi nào fix?"*

Đây CHÍNH LÀ lý do cần pub/sub. Mitigate ngắn hạn:
- Giảm L1 TTL (60s → 10s) — đánh đổi hit ratio.
- Hot path không dùng L1 (chỉ L2) — đánh đổi latency.

Long-term: pub/sub. Pod A publish key sau khi commit. Pod B subscribe → evict L1 local. Implementation đơn giản (Spring Data Redis có `RedisMessageListenerContainer`), nhưng phải handle:
- **Self-trigger**: pod A publish thì chính nó cũng subscribe → tránh evict 2 lần (đã evict explicit). Filter qua publisher-id.
- **At-least-once**: nếu pub/sub message lost (Redis down giữa publish và subscribe) → L1 stale forever cho tới TTL fallback. Pub/sub KHÔNG replace TTL.
- **Ordering**: race condition giữa 2 update gần nhau → ai evict cuối thắng. Acceptable.

---

## Q4 — "Cache hit ratio bao nhiêu là 'good'? Measure thế nào?"

**Strong answer**:

Depends on workload, không có magic number:
- **Catalog read-heavy** (product page): L1 ≥80%, combined L1+L2 ≥95% với traffic Zipf α=1.
- **Search query**: 50-70% (cache key entropy cao hơn).
- **User session**: ≥95% (mỗi user re-read profile).
- **Real-time financial / stock**: thường KHÔNG cache hoặc TTL <5s — hit ratio không phải KPI chính.

Measure qua Prometheus (Micrometer `cache.gets{result=hit|miss}` chuẩn):

```promql
sum(rate(product_cache_hits_total[5m]))
/
sum(rate(product_cache_hits_total[5m]) + rate(product_cache_misses_total[5m]))
```

Quan trọng: **đo per-tier + per-cache-name**, không aggregate. Hit ratio overall 95% có thể che giấu "cache `product:bySlug` miss 100%" (data corruption hoặc key format wrong).

**Follow-up trap**: *"Đột nhiên hit ratio drop từ 95% → 60% — bạn debug thế nào?"*

5-step triage:
1. **Time correlation**: drop khớp deploy nào? Code change → cache key format đổi → 100% miss.
2. **Per-key analysis**: drop đều khắp keys hay 1 key prefix? Nếu 1 prefix → invalidation bug code mới.
3. **Eviction rate**: Caffeine `cache.evictions` tăng đột biến? → working set > max-size, thrashing.
4. **Redis health**: L2 miss tăng cùng lúc? Redis OOM hoặc keyspace flush?
5. **Traffic shift**: keyword search mới (long-tail) đẩy hit miss tự nhiên? Compare keyword distribution.

---

## Q5 — "Cache thrashing — phát hiện và xử lý?"

**Strong answer**:

**Thrashing** = working set > cache size → eviction liên tục → hit ratio thấp + CPU lãng phí cho put/evict.

Phát hiện:
- `cache.evictions` rate ≈ `cache.puts` rate (ratio ~1:1 = mỗi put kèm 1 evict = đầy hoài).
- Hit ratio thấp <50% nhưng traffic skewed (lý thuyết phải hit cao) → working set fit nhưng cache size không đủ.
- JVM GC frequency tăng (object churn lớn từ put/evict).

Fix:
1. **Tăng size**: trade off heap. Caffeine 100k entry ~50MB là OK; >1M cân nhắc segment hoặc L2-only.
2. **Đổi eviction policy**: LRU vanilla → LFU (Caffeine W-TinyLFU default đã LFU+window). Vanilla Java HashMap LinkedHashMap không có lựa chọn.
3. **Segment cache** theo access pattern: tách hot/cold cache, hot có TTL dài + size lớn, cold size nhỏ.
4. **Skip cache** cho query không hit: nếu `search()` luôn miss → bỏ cache hẳn, đỡ overhead put.

**Follow-up trap**: *"User profile có 50M entry, mỗi entry 2KB. Cache thế nào?"*

50M × 2KB = 100GB. Không fit 1 JVM heap. Options:
- **L2-only** (Redis cluster, ~100GB RAM split 4-8 node). Bỏ L1 — không hot enough cho local cache.
- **L1 hot subset** (top-1% active users = 500k = 1GB) + L2 full. Day 33 system design intensive sẽ deep-dive.

---

## 🧠 Senior mindset notes

- **Cache là eventual consistency, không phải free latency**. Mọi cache đều có stale window. Nếu business không tolerate (price tại checkout, stock count) → đừng cố ép cache. Skip hoặc TTL ultra-short.
- **Hit ratio overall che giấu per-key behavior**. 95% overall có thể là 1 key hit 100% + 1000 key miss 100%. Per-key histogram là must-have ở dashboard production.
- **Cache layer là single point of latency amplification**. Redis chậm 100ms thì TOÀN BỘ request chậm 100ms. Phải có **circuit breaker bypass cache** khi Redis fail (fallback DB direct, accept latency tăng tạm thời, đỡ cascading).
- **Invalidation correctness là rủi ro chính**, không phải performance. Cache wrong > cache slow. Audit mọi write path khi add cache; native query và bulk update là chỗ hay miss.

---

## 🤖 AI Playbook

- **AI làm tốt**: boilerplate `CacheConfig`, generate `@Cacheable`/`@CacheEvict` annotation, viết Testcontainers Redis setup, tạo XFetch formula skeleton, generate `CacheMetrics` binding pattern.
- **Prompt mẫu**: *"Generate a Spring Cache `CacheManager` bean composing Caffeine (60s TTL, 10k max) as L1 and Redis (5min TTL, JSON serializer) as L2, with metrics binding to Micrometer. Show only the bean + 1 unit test."*
- **Risk**: AI hay quên **invalidation 2 tier** (chỉ evict L2, quên L1) → multi-instance stale. AI cũng hay generate `@Cacheable` lên method nhận DTO mutable → cache poisoning khi caller mutate object. Đặc biệt: AI hay miss case **slug đổi** → @CacheEvict declarative không evict được old slug, phải manual.
- **Validate**: chạy `StampedeProtectionTest` (verify loader count << N với 100 concurrent miss). Đọc kỹ logic `evict` — phải xóa cả L1 + L2 + verify bằng test multi-instance. Inspect cache value — phải là **immutable record** không phải entity managed. Test invalidation cho **mọi write path** (create / update / archive / bulk).

---

## 👥 Tech Lead Lens

- **Trade-off chính**: 2-tier giảm Redis load + latency, đánh đổi multi-instance L1 inconsistency window (≤60s). **Scale 10x**: thêm Redis pub/sub topic `cache:invalidate` để instance khác evict L1 real-time; nâng L2 lên Redis Cluster sharded theo key hash; xem xét client-side caching protocol Redis 6 (RESP3 push notification).
- **Production failure mode**: Redis flap → app traffic dồn vào DB (L2 miss → DB) → DB CPU 100% → cascading fail. **5-step triage**: (1) Grafana xem cache hit ratio drop time + Redis health; (2) Redis `INFO stats` xem connection count / evicted_keys / mem usage; (3) check Redis network/CPU nodes; (4) bật circuit breaker bypass cache miss path, return 503 với Retry-After cho client; (5) scale read replica DB tạm thời để chịu áp lực trong khi Redis recover.
- **Junior + AI 2 lỗi dễ nhất** (review phải soi):
  1. **Cache key format collision** — AI generate `key="#id"` mà 2 method `getProductById(Long)` và `getProductBySlug(String)` cùng cache name → 2 type khác nhau lưu trùng key 123 → poison. **Review kỹ**: cache key namespace phải prefix method name hoặc tách cache name riêng (ta đã làm: `product:byId` vs `product:bySlug`).
  2. **`@CacheEvict` không cover update bulk** — AI annotate `@CacheEvict` lên `updateOne()`, nhưng repo có `updateBatchPrices(List<Long>)` không annotate → cache stale toàn bộ batch. **Review kỹ**: audit TOÀN BỘ write path khi mở rộng (native query, bulk update, async event handler).

---

## Related

- Code: [`services/product-service/src/main/java/com/ecom/product/config/cache/`](../../services/product-service/src/main/java/com/ecom/product/config/cache/)
- Test: [`StampedeProtectionTest`](../../services/product-service/src/test/java/com/ecom/product/cache/StampedeProtectionTest.java), [`TwoTierCacheTest`](../../services/product-service/src/test/java/com/ecom/product/cache/TwoTierCacheTest.java)
- [Lesson 15](../lessons/15-cache-strategies.md)
- [Performance 15 / 15b](../performance/15-cache-aside.md)
- [ADR 012](../decisions/012-two-tier-cache-caffeine-redis.md)
- [Issue 15 / 15b](../issues/15-cache-stampede.md)
