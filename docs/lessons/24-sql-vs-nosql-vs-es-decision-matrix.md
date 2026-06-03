# Lesson 24 — SQL vs NoSQL vs ES: Decision Matrix

> **Status**: ✅ Done · Day 24
> Câu phỏng vấn classic *"khi nào dùng NoSQL?"* — trả lời bằng **bằng chứng từ
> chính repo này** (Postgres + Redis + Mongo + ES đều đã chạy thật), không lý thuyết.

---

## 🎯 TL;DR

Chọn storage theo **access pattern + consistency requirement**, KHÔNG theo "data lớn"
hay "cái nào nhanh hơn". Repo này cố tình dùng cả 4 paradigm để mỗi cái giải đúng 1
bài toán:

| Paradigm | Storage | Giải bài toán gì trong repo |
| -------- | ------- | --------------------------- |
| Relational | **PostgreSQL 16** | Source of truth: order / payment / stock / product — nơi cần **invariant + ACID** |
| Key-value | **Redis 7** | Cart, cache L2, session, distributed lock — nơi cần **tốc độ + TTL**, mất không chết |
| Document | **MongoDB 7** | Analytics event store + catalog read-model — **schemaless + ghi nhiều + aggregation** |
| Inverted index | **Elasticsearch 8** | Product search — **full-text relevance + fuzzy + facet** |

> 💡 Câu chốt phỏng vấn: *"Không có kho hoàn hảo, chỉ có kho đúng access pattern.
> Postgres giữ sổ gốc, Redis chạy nhanh, Mongo nới schema, ES bói chữ. Mọi cái
> ngoài Postgres đều là **derived view** — Postgres vẫn là source of truth."*

---

## 📊 Decision matrix — 8 use case × 4 storage

Verdict: ✅ primary choice · 🟡 acceptable (có trade-off) · ❌ anti-pattern.

| # | Use case (access pattern) | 🐘 Postgres | ⚡ Redis | 🍃 Mongo | 🔎 ES |
| - | ------------------------- | ----------- | -------- | -------- | ----- |
| 1 | **Order + Payment** (≥3 invariant, ACID, money) | ✅ ACID + `@Version` + CHECK | ❌ no durable txn | 🟡 cần multi-doc txn (đắt) | ❌ no ACID |
| 2 | **Stock reservation** (concurrency, `reserved ≤ qty`) | ✅ optimistic lock (Day 4) | 🟡 Lua atomic cho flash-sale (Day 33) | ❌ invariant rơi | ❌ |
| 3 | **Cart** (ephemeral, fast write, TTL 7d) | 🟡 được nhưng nặng | ✅ Hash + HINCRBY + TTL (Day 5) | 🟡 được nhưng thừa | ❌ |
| 4 | **Session / refresh-token blacklist** | 🟡 | ✅ k-v + TTL tự hết hạn | ❌ thừa | ❌ |
| 5 | **Full-text product search** (relevance, fuzzy, facet) | 🟡 GIN tsvector (đủ tới ~vài trăm k row) | ❌ | ❌ | ✅ BM25 + fuzzy + facet (Day 22) |
| 6 | **Flexible product attributes** (TV vs áo khác shape) | ✅ JSONB `@JdbcTypeCode` (Day 3) | ❌ | 🟡 acceptable nhưng tách source of truth | ❌ |
| 7 | **Analytics event store** (ghi nhiều, schemaless, TTL, aggregation) | 🟡 phình bảng + đụng DB nghiệp vụ | ❌ không bền | ✅ append-only + TTL 90d + pipeline (Day 23) | 🟡 hợp nếu là log/observability |
| 8 | **Hot read cache** (product detail, đọc nhiều) | ❌ (chính là cái cần giảm tải) | ✅ Caffeine L1 + Redis L2 (Day 15) | ❌ | ❌ |

> ⚠️ Đọc ô 🟡 kỹ hơn ô ✅: ô vàng là nơi interviewer đào. Ví dụ ô (6,Postgres)
> = ✅ nhưng ô (6,Mongo) = 🟡 — *"sao không Mongo luôn cho attributes?"* là câu bẫy.
> Trả lời ở phần [Cạm bẫy](#-cạm-bẫy--3-anti-pattern) bên dưới.

---

## 🧭 So sánh trên 5 axis

| Axis | 🐘 PostgreSQL | ⚡ Redis | 🍃 MongoDB | 🔎 Elasticsearch |
| ---- | ------------- | -------- | ---------- | ---------------- |
| **Consistency model** | Strong ACID (MVCC, tới SERIALIZABLE) | Single-cmd atomic; no cross-key ACID trong cluster | Single-doc atomic; multi-doc txn từ 4.0 (replica-set, đắt) | Eventual; near-real-time refresh ~1s; **no ACID** |
| **Schema flexibility** | Rigid + escape hatch JSONB | Schemaless k-v | Schemaless document (nhận mọi shape) | Dynamic mapping (rủi ro **mapping explosion**) |
| **Query capability** | Rich SQL: join, window, CTE, aggregate | Lookup + data-structure ops; no ad-hoc query | Aggregation pipeline mạnh; **join yếu** (`$lookup` hạn chế) | Full-text relevance / fuzzy / facet; **join yếu** |
| **Scaling pattern** | Vertical + read replica; shard thủ công (Citus) | Cluster sharding + replica; bound bởi RAM | Native sharding + replica-set | Native sharding + replica |
| **Operational cost** | Thấp (mature, 1 box đi rất xa) | Thấp–TB (memory-bound, cấu hình persistence) | TB (sharding ops, no-txn footgun) | **Cao** (JVM heap, cluster, mapping, reindex) |

> 💡 PACELC một dòng (chi tiết ở [24b](24b-cap-pacelc-in-practice.md)):
> Postgres = **PC/EC**, Mongo = **PC/EL** (default, tunable), ES = **PA/EL**,
> Redis = **PC/EL**. Mỗi chữ là một câu defend được trước architect.

---

## ✅ Khi nào dùng — quick rule per store

- 🐘 **Postgres** — mặc định cho **mọi** data có invariant / cần ACID / quan hệ. Bắt
  đầu ở đây, chỉ rời đi khi có lý do access-pattern cụ thể. JSONB cân được schemaless
  *vừa phải*.
- ⚡ **Redis** — data **ephemeral / hot / cần TTL**: cart, cache, session, rate-limit,
  distributed lock, leaderboard. Mất data = phiền, không = thảm hoạ.
- 🍃 **Mongo** — schema **đa hình thật** + **ghi nhiều, đọc phân tích** + cần TTL/scale
  ngang: event store, audit log, catalog read-model. Aggregate boundary = document boundary.
- 🔎 **ES** — **full-text relevance / fuzzy / facet / log analytics**. Luôn là **derived
  view** từ source of truth khác, không bao giờ primary.

## ❌ Khi nào KHÔNG dùng

- 🐘 **Postgres** ❌ khi: cần full-text relevance ở scale lớn (→ ES), cần cache hot-read
  giảm tải chính nó (→ Redis), throughput ghi event khổng lồ làm phình bảng nghiệp vụ (→ Mongo).
- ⚡ **Redis** ❌ làm **system of record** cho money/order — RAM-bound, persistence là
  best-effort, không có quan hệ/transaction bền.
- 🍃 **Mongo** ❌ cho data **invariant chặt + nhiều quan hệ** (order, stock, ledger) — bạn
  sẽ phải tự dựng lại txn + integrity mà Postgres cho free.
- 🔎 **ES** ❌ làm **primary store**: near-real-time refresh (đọc ngay sau ghi có thể
  miss), no ACID, reindex đau, mapping cứng sau khi tạo.

---

## ⚠️ Cạm bẫy — 3 anti-pattern (drill chính của Day 24)

### 1. 🔨 Dùng Mongo cho data có invariant chặt
*"Move `orders` + `stock` sang Mongo cho scale ngang"* — nghe hợp lý tới khi mất
`reserved ≤ quantity` và phải tự bọc multi-doc transaction (replica-set, chậm, phức tạp)
để làm cái Postgres cho free. Aggregate có **≥3 invariant + concurrency thật** (tiêu chí
DDD Day 4) → ở lại Postgres. Xem [issue 24](../issues/24-cargo-cult-storage-migration.md).

### 2. 📦 Dùng Mongo cho schemaless khi JSONB đã đủ
Đây là quyết định thật của repo (Day 23): flexible product attributes **để Postgres JSONB**,
KHÔNG đẩy hết sang Mongo. Lý do:
- Query attribute đơn giản (`attributes->>'screen_size'` + GIN) — chưa cần aggregation pipeline.
- Giữ **một source of truth** — đẩy sang Mongo = thêm 1 dual-write + 1 sync drift (Day 22 đã thấm).
- Mongo catalog vẫn tồn tại nhưng là **derived read-model**, không phải nơi ghi gốc.

> Ngưỡng đảo chiều: nếu attribute shape bùng nổ + cần query/aggregate phức tạp trên
> attribute → lúc đó Mongo làm primary cho catalog mới đáng. Chưa tới ngưỡng → JSONB thắng.

### 3. 🔎 Dùng ES làm primary store
ES nhanh và "đọc được cả khi gõ sai" (ch.22), dễ cám dỗ vứt Postgres đi. Nhưng ES
**không có ACID**, refresh near-real-time (~1s) nên đọc-sau-ghi có thể miss, và đổi
mapping = reindex toàn bộ. ES là **bản phô-tô để bói**, Postgres là **sổ gốc**. Drift
giữa hai cái là chi phí phải đo (eventual consistency window — Day 9, Day 22).

> ⚠️ Quy tắc vàng chống cargo-cult: **mỗi storage thêm vào = +1 failure mode + 1 sync edge.**
> "Đúng tool" không free. Polyglot persistence chỉ đáng khi access pattern thật sự khác nhau,
> không phải vì CV cần dòng "MongoDB".

---

## 🎤 Trả lời phỏng vấn

**Q: "Khi nào dùng NoSQL thay SQL?"**
> Không phải "khi data lớn". Mà khi **access pattern** không hợp relational: cần
> full-text relevance (→ ES), cần TTL + tốc độ ephemeral (→ Redis), cần schema đa hình
> + ghi nhiều để phân tích (→ Mongo). Mặc định của em vẫn là Postgres vì nó cho ACID +
> quan hệ + JSONB free; em chỉ rời đi khi đo được access pattern cụ thể. Trong project
> em có cả 4, mỗi cái giải đúng 1 bài.

**Trap "Mongo nhanh hơn Postgres":**
> Sai ở chỗ so sai chiều. Mongo nhanh hơn ở *write-heavy schemaless single-doc*; Postgres
> nhanh hơn ở *quan hệ + join + transaction*. "Nhanh hơn" mà không nói access pattern thì
> vô nghĩa — interviewer sẽ vặn "nhanh hơn ở đâu?".

---

## 🔗 Related

- Lesson: [24b — CAP & PACELC in practice](24b-cap-pacelc-in-practice.md)
- Lesson: [05b — Redis data structures](05b-redis-data-structures.md) · [22 — Elasticsearch basics](22-elasticsearch-basics.md) · [23 — MongoDB when to use](23-mongodb-when-to-use.md) · [23b — Document vs relational modeling](23b-document-vs-relational-modeling.md)
- Issue: [24 — Cargo-cult storage migration](../issues/24-cargo-cult-storage-migration.md)
- ADR: [004 — Redis primary for cart](../decisions/004-redis-primary-for-cart.md) · [010 — Postgres vs Elasticsearch](../decisions/010-postgres-vs-elasticsearch-search.md) · [011 — Mongo for analytics & flexible attributes](../decisions/011-mongo-for-analytics-and-flexible-attributes.md)
- Interview: [day-24 — Storage decisions](../interview/day-24-storage-decisions.md)
- Architecture: [system-overview](../architecture/system-overview.md) (4-storage topology)
</content>
</invoke>
