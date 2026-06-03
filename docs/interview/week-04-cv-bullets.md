# Week 4 — CV Bullets (Day 22-25 Data layer)

> **Context**: Gom 4 ngày data-layer (Elasticsearch search + MongoDB event store/catalog + decision
> matrix + polyglot review) thành 1-2 metric-driven bullet cho CV. Mục đích: 90s elevator pitch
> cumulative Week 1-4 cho phỏng vấn senior/lead role. Nối tiếp [Week 3 CV bullets](week-03-cv-bullets.md).

---

## 🎯 2 CV bullets (từ Week 4 work)

### Bullet 1: Polyglot persistence với search + document store (disciplined, không cargo-cult)
**Context**: e-commerce platform, 1M+ products, full-text search + flexible attributes + analytics.

**Bullet**:
> Designed disciplined **polyglot persistence** across 4 storage paradigms — PostgreSQL (source of truth: order/payment/stock, ACID), Elasticsearch (product search: BM25 relevance + fuzzy + faceting), MongoDB (analytics event store + flexible-attribute catalog), Redis (cart + L2 cache) — with PostgreSQL → derived stores synced via **Kafka outbox/event** (single async channel, no dual-write). Sub-2s consistency window with drift-reconcile endpoint.

**Why metric strong**:
- "4 storage paradigms" + "single source of truth" = shows you handle complexity *with discipline*, not sprawl.
- "no dual-write" + "Kafka outbox" = knows the #1 polyglot failure mode and how to avoid it.
- "drift-reconcile endpoint" + "sub-2s window" = eventual consistency *measured*, not hand-waved.
- Names exact tool per access pattern = technical depth, not buzzword soup.

**Interview question likely to follow**: "How do you keep Elasticsearch in sync with Postgres without dual-write?"
→ **Story**: afterCommit publish `product.upserted` (key=productId for ordering) → Kafka → 2 independent consumer groups (`-indexer` → ES, `-catalog` → Mongo). Single event fans out to both derived stores; each consumer fails/replays independently. Drift measured via `/admin/search/drift` (id-set diff), healed via `/admin/search/reindex` from Postgres truth. ES down → fallback Postgres GIN (`X-Search-Source` header) — proving ES is derived, never primary.

---

### Bullet 2: Storage decision framework (matrix + reversal thresholds)
**Context**: scale-up adding NoSQL/search, risk of "wrong tool for the job" / cargo-cult migration.

**Bullet**:
> Built a **storage decision matrix** (8 use cases × 4 stores, 5 axes: consistency / schema / query / scaling / ops cost) with explicit **reversal thresholds** per choice, plus CAP/**PACELC** mapping (Postgres PC/EC · Mongo PC/EL · ES PA/EL · Redis PC/EL). Used it to reject a "migrate orders to MongoDB" proposal by tying storage choice to access pattern + invariant tests, not marketing adjectives.

**Why metric strong**:
- "decision matrix" + "reversal thresholds" = decisions framed as conditional ("right within a threshold"), the senior signature.
- "PACELC" (not just CAP) = knows the ELC clause that separates senior from junior; "Mongo CP not AP" correctness.
- "rejected a proposal" = leadership / governance outcome, not just IC coding.
- Ties choice to invariant tests = correctness-first, not hype-first.

**Interview question likely to follow**: "When would you actually move orders off Postgres to a NoSQL store?"
→ **Story**: Never for *scale* alone — orders have ≥3 invariants + cross-entity transactions + concurrency (ADR-003). MongoDB single-doc atomic can't hold a cross-document invariant without multi-doc transactions on a replica set — which rebuilds the very ACID Postgres gives free. The 100-thread no-oversell test is the red line: fails on Mongo single-doc = proposal blocked. Threshold to reconsider: only if a *specific access pattern* (not "scale") proves relational is the bottleneck, measured.

---

## 📊 Accumulative elevator pitch (90 seconds — Weeks 1-4)

**Opening**: "I built an e-commerce platform handling 1M+ products and 10× traffic spikes, with a disciplined polyglot data layer."

**Week 1 foundation**: "Multi-service architecture, hybrid DDD for order/inventory/payment (3-point criteria: invariants + concurrency + domain events). Virtual threads on Spring Boot 3.4."

**Week 2 scale**: "Kafka outbox pattern + Resilience4j circuit breaker + OpenTelemetry tracing. Eventual consistency has a 5-second window — explicit retry + DLT for poison messages."

**Week 3 performance**: "Load test revealed 3 bottlenecks → 2-tier cache + XFetch, keyset pagination, Little's-Law pool sizing. Result: 4× latency (P95 200→50ms), 10× throughput (200→2000 req/s)."

**Week 4 data layer** (this week): "Added Elasticsearch (search) + MongoDB (analytics/catalog) — but *disciplined*: Postgres stays the single source of truth, every derived store syncs through one Kafka channel (no dual-write), with measured drift + reconcile. Built a decision matrix (8 use cases × 4 stores + PACELC) so storage choice is access-pattern-driven, not cargo-cult. Used it to block a 'migrate orders to Mongo' proposal."

**Closing**: "Key insight across 4 weeks: every architectural choice is conditional. ACID where invariants live, eventual where it's a derived view — and always name the threshold where the choice flips."

**Duration**: ~90 seconds, progression foundation → scale → performance → data layer, 4 concrete metrics.

---

## 🎬 Storytelling anchors (what to emphasize)

| Anchor | Story | Why |
|--------|-------|-----|
| **Single source of truth** | 4 stores, but Postgres owns all invariant/money data; ES/Mongo/Redis-cache are derived views. Wrote data-ownership-map so nobody confuses the source. | Shows discipline over sprawl — the thing that separates polyglot from poly-mess.
| **No dual-write** | Don't `save(pg); index(es)` — not atomic. Outbox (order) / afterCommit event (product) → Kafka → derived consume. One channel, measurable. | Names #1 failure mode + the fix. Ties back to Day 13 outbox.
| **Failure-mode by role** | Only Postgres down = hard fail (it's truth). ES down → Postgres GIN fallback. Mongo analytics down → events buffer in Kafka. Redis-cart down → cart lost (primary), but checkout survives. | Shows derived stores designed to *degrade*, not stay HA — ops realism for a 6-person team.
| **Rejected migration** | Junior proposed "move orders to Mongo for scale." Blocked it: orders need cross-entity ACID; the 100-thread no-oversell test is the red line. Threshold-based, not ego-based. | Leadership + governance, framed as protecting correctness.

---

## 📋 Interview prep checklist (Week 5 onwards)

- [ ] Can name source of truth + derived/primary/sink for each of 4 stores without hesitating
- [ ] Can explain why dual-write fails + the outbox/event alternative (atomicity argument)
- [ ] Can recite PACELC for all 4 stores + why "Mongo is AP" is wrong
- [ ] Can draw the failure-mode matrix (which store down = hard fail vs graceful degrade)
- [ ] Can state reversal threshold for ≥3 storage choices (JSONB→Mongo, GIN→ES, afterCommit→outbox)
- [ ] Can defend "4 stores for 6-person team" via access-pattern + degrade-not-HA ops argument

---

## 🚀 Preview: Week 5 bullet (Frontend integration)

**Bullet** (React + TanStack Query + E2E):
> Built React 18 + TypeScript frontend (Vite + TanStack Query v5 + Ant Design) consuming the platform end-to-end — auth/token-refresh interceptor, optimistic cart mutations, Playwright E2E covering the full place-order flow.

**Metric**: "End-to-end demo: register → browse (ES search) → cart (Redis) → order (Postgres) → confirm, with E2E coverage."
</content>
