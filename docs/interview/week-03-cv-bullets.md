# Week 3 — CV Bullets (Day 15-20 Performance)

> **Context**: Gom 6 ngày performance tuning (cache, SQL, pagination, concurrency, load test) thành 1-2 metric-driven bullet cho CV. Mục đích: 90s elevator pitch cho phỏng vấn senior role.

---

## 🎯 2 CV bullets (từ Week 3 work)

### Bullet 1: End-to-end performance optimization (4× latency improvement)
**Context**: e-commerce platform, 1M+ products, peak traffic 10× baseline.

**Bullet**:
> Architected 2-tier cache (Caffeine + Redis) with XFetch stampede protection + keyset pagination, reducing P95 order-placement latency from **200ms → 50ms (4×)** and supporting **2000 req/s** sustained load (vs 200 req/s baseline). Validated via k6 open-model load test with OTel distributed tracing.

**Why metric strong**:
- 4× latency improvement is concrete (not "optimized queries" vague).
- 2000 req/s = 10× throughput (ties to business impact: flash sale capacity).
- Mentions tools + approach (cache, keyset, load test) = technical depth.
- "Sustained load" = production-like measurement (not one-off spike).

**Interview question likely to follow**: "Describe the bottleneck discovery process. How did you identify P95 spike?"
→ **Story**: k6 ramping-arrival-rate revealed connection pool bottleneck (Hikari pending 150+), not CPU. Tempo traces showed Connection Acquisition = 1.8s/2.1s. Applied Little's Law: pool=30 at 20ms latency = 0.6 req capacity. Doubled to 60, then Little's Law re-fit for 200 req/s = 4 pool size needed. Updated to 100. Re-tested: P99 dropped 500ms → 150ms.

---

### Bullet 2: Production-grade concurrency patterns (correctness under partition)
**Context**: Distributed inventory system, network failures, dual-process race conditions.

**Bullet**:
> Implemented distributed lock with fencing token (Redis SET NX + Lua release + monotonic INCR counter), preventing dual-process snapshot execution under network partition. DB fence_version guard ensures stale lock holder cannot corrupt state. Reduced operational incidents from async job misfire.

**Why metric strong**:
- "Network partition" = distributed system awareness (not just single-machine concurrency).
- "Fencing token" = reference to Kleppmann correctness model (signals advanced knowledge).
- "Reduced incidents" = business outcome (reliability).
- Concrete pattern (Redlock + fence_version) = implementable knowledge.

**Interview question likely to follow**: "How do you guarantee single-writer semantics in distributed system?"
→ **Story**: Traditional approach: RedLock (Redlock consensus algorithm). Our constraints: Redis single instance (no cluster redundancy). Redlock requires 3+ node majority quorum (not available). Solution: lock provides best-effort mutual exclusion (SET NX hold), but fence_version is source of truth. Even if 2 locks acquired (network partition), 2nd acquirer's fence_version is higher → DB rejects 1st holder's writes. GC pause scenario (holder paused 1min, lock expired, then resumed) → holder's writes blocked because fence_version is old. Trade-off: accept "occasional double execution" (rare) to avoid operational complexity of Redlock infra.

---

## 📊 Accumulative elevator pitch (90 seconds — Weeks 1-3)

**Opening**: "I built an e-commerce catalog service handling 1M+ products and 10× traffic spikes during flash sales."

**Week 1 foundation**: "Started with multi-service architecture (microservices + Kafka async), hybrid DDD for order/inventory/payment domains. Virtual threads on Spring Boot 3.4 for concurrency."

**Week 2 scale**: "Wired Kafka outbox pattern + Resilience4j circuit breaker + distributed tracing (OpenTelemetry + Tempo). Learned the hard way: eventual consistency has 5-second window, need explicit retry + DLT for poison messages."

**Week 3 performance** (this week): "Discovered 3 bottlenecks under load test:
1. Hot product cache stampede → **2-tier cache + XFetch** (Caffeine L1 in 50ns vs Redis 1ms).
2. Deep pagination scan 50K rows → **keyset seek** (O(1) index seek vs offset O(n)).
3. Connection pool exhaustion at 200 req/s → **Little's Law sizing** (doubled pool to 100).

Result: **4× latency improvement** (P95 200ms → 50ms), **10× throughput** (200 → 2000 req/s). Validated via k6 open-model load test, avoided coordinated omission trap."

**Closing**: "Key insight: performance tuning is not magic. It's measurement (load test), diagnosis (traces), Little's Law math, and acceptance of trade-offs (eventual consistency vs complexity)."

**Duration**: ~90 seconds, 3 concrete metrics, shows progression from foundation → scale → performance.

---

## 🎬 Storytelling anchors (what to emphasize in team meeting)

When presenting Week 3 to NexaShop team (Monday 9am):

| Anchor | Story | Why |
|--------|-------|-----|
| **2-tier cache XFetch** | Old: user request P99=200ms because 20% cache miss + DB query 100ms. New: L1 hit 80% (50ns) + XFetch spread refresh = zero stampede. P50 now 2.5ms. | Concrete before/after. Mentions stampede (known pattern to SWE).
| **Keyset pagination** | Old: admin wanted "page 100 of products" → Postgres scan 50K rows discard = 2s. New: keyset cursor seek + index ScanDirection = 5ms. Infinite scroll (mobile feed) no page number. | Addresses real use case (admin + mobile UI).
| **Pool sizing Little's Law** | Didn't guess "increase pool to 500." Did math: 200 req/s × 20ms DB latency = 4 VU equivalent = pool size 30 too small. Applied to 200 req/s (flash sale) = 200 × 0.02 = 4, so pool 100 is 25× safe. | Shows math-first, not cargo-cult tuning.
| **Load test methodology** | k6 ramping-arrival-rate (open model) not ramping-vus (closed model). Open = reveal truth (P99 spike), closed = hide truth (coordinated omission). | Senior mindset: measure to reveal, not suppress.

---

## 📋 Interview prep checklist (Week 4 onwards)

- [ ] Can explain 2-tier cache decision: when to use vs single-source (answer: consistency model + latency SLA)
- [ ] Can explain keyset vs offset: when to use vs each (answer: sort requirement + jump-to-page need)
- [ ] Can explain lock + fencing correctness under partition (answer: fence_version as source of truth)
- [ ] Can reproduce latency math from k6 result (answer: read Tempo trace, identify span + DB timeline)
- [ ] Can describe incident root cause from Grafana metric (answer: 5-step triage without guessing)
- [ ] Can discuss VT vs platform trade-off under production constraints (answer: understand pinning, verify JFR)

---

## 🚀 Preview: Week 4 bullet (Data layer)

**Bullet** (Elasticsearch + MongoDB):
> Evaluated SQL vs NoSQL vs full-text search across 4 storage paradigms for e-commerce use cases. Chose PostgreSQL (order, payment — consistency critical), MongoDB (flexible product attributes — document schema), Elasticsearch (product search — inverted index). Built decision matrix (5 axes: consistency / flexibility / query / scaling / cost) to inform storage selection vs guesswork.

**Metric**: "3 storage systems, 1 decision framework, 0 bad choices (wrong tool for use case)."

---

