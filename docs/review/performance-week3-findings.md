# Performance Week 3 (Day 15-20) — Code Review Findings

> **Scope**: 6 days implementing cache, SQL tuning, N+1 fix, pagination, concurrency, load test.  
> **Reviewer mindset**: production-grade depth — junior/AI pattern detection + infrastructure edge case + trade-off correctness.
> **Format**: Severity (red/yellow/green) · File:Line · Finding · Root cause · Gap · Deploy risk

---

## 📊 Summary

| Severity | Count | Theme |
|----------|-------|-------|
| 🔴 Red   | 6     | Logic gaps + incomplete refactor + edge cases |
| 🟡 Yellow| 8     | Design debt + assumptions + precision loss |
| 🟢 Green | 9     | Production-grade patterns (validates code quality) |
| **Total**| **23**| Code review complete |

---

## 🔴 RED SEVERITY (Logic bugs / production risk)

### Day 15 · CacheConfig metadata unbounded growth
**File**: `services/product-service/src/main/java/com/ecom/product/config/cache/ProbabilisticExpiringCache.java:64`

**Finding**: `fetchMetadata` ConcurrentHashMap grows without bound when product SKU churn is high (key rotation). `evict()` removes entry but doesn't prevent bloat if Caffeine cache is frequently cleared + refilled (cache flush scenarios, prod debugging).

**Root cause**: XFetch metadata lifecycle tied to application logic (put/evict) not Caffeine's eviction events. ConcurrentHashMap has no TTL.

**Gap**: No size cap, no `RemovalListener` on Caffeine to cleanup stale metadata. If cache is flushed N times, map grows to 1000s entries even if only 10 keys active.

**Deploy risk**: 🟡 Medium (memory leak under pathological scenarios — cache clear loops, deployment rollback cycles). In steady state, acceptable.

**Recommendation**: Add `WeakHashMap<Object, FetchMeta>` or size-bounded `LinkedHashMap` with LRU eviction for metadata. Alternatively, co-locate metadata expiry with Caffeine eviction via `RemovalListener`.

---

### Day 16 · CONCURRENTLY index missing in production migration
**File**: `services/product-service/src/main/resources/db/migration/V5__product_search_indexes.sql:1-55`

**Finding**: Comment (line 14-21) warns Flyway can't use `CREATE INDEX CONCURRENTLY` (requires separate tx), but V5 migration doesn't wrap around explicit workaround. When deployed to production (1M+ rows product table), migration will acquire AccessExclusiveLock on `products` table for **20-60 seconds** (GIN trigram index build time) → write traffic stalls.

**Root cause**: Flyway wraps migration in implicit transaction → `CONCURRENTLY` syntax rejected by Postgres. Comment documents the gap but code doesn't implement solution.

**Gap**: No `flyway baseline` placeholder + no documented runbook for prod deployment. Engineers deploying Day 16 will run standard `./gradlew flywayMigrate` = table lock.

**Deploy risk**: 🔴 **High** — E-commerce "write stall during peak hours" is incident-level. Order placement may timeout if inventory reserve hits locked table.

**Recommendation**: 
- Add placeholder migration (`V5_index_placeholder.sql`) that does nothing.
- Create separate manual step (runbook) to run GIN index creation outside Flyway during maintenance window.
- Or: break index creation into separate Gradle task (not part of standard migration).

---

### Day 17 · Unused EAGER path in production
**File**: `services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java:41`

**Finding**: `findByUserId(UUID, Pageable)` still uses `@OneToMany(fetch = EAGER)` from Order aggregate definition. This is marked as "nấc 0" demo, but DebugController is only place using it. **Production endpoint `GET /orders` should be using `findSummariesByUserId()` projection, not this method**. Code diff shows NO refactor of actual production OrderController.

**Root cause**: Mock endpoint added (DebugController for demo), real endpoint (OrderController.listMyOrders()) left unchanged. Assumption: "once DebugController proves fix, automatically refactor." Didn't happen.

**Gap**: `OrderController` never updated to call `findSummariesByUserId()`. Prod traffic still triggers N+1. Day 21 code review should have caught before PR merge.

**Deploy risk**: 🔴 **Critical** — N+1 overhead on every order list query (100+ user lists/sec in prod) means database CPU 2-3× higher than necessary.

**Recommendation**: Refactor `OrderController.listMyOrders()` to use projection query. Add test asserting `<= 2 queries` for list path. Revert EAGER to LAZY on Order.items to fail fast if anyone else uses N+1 path.

---

### Day 18 · Cursor opaque contract not enforced
**File**: `services/product-service/src/main/java/com/ecom/product/web/dto/ProductCursor.java:52-69`

**Finding**: `decode()` accepts any base64-decodable string; doesn't validate if it came from our `encode()` or arbitrary client input. Client can craft `"MToxMjM0NTY3ODkw"` (random base64) → decode succeeds (may throw NumberFormatException) but then return cursor with garbage epoch+UUID → silently returns wrong page.

**Root cause**: Opaque cursor spec says "client doesn't parse," but validation doesn't enforce bidirectional encode↔decode consistency. Treat as "trusted after validation" but validation is lenient.

**Gap**: No checksum/signature, no version field, no strict format validation before parsing components.

**Deploy risk**: 🟡 **Medium** — attacker can't exfiltrate data (UUID guessing is hard), but can cause pagination logic to skip/repeat rows in non-deterministic ways. Not security breach, but observability nightmare.

**Recommendation**: Add version byte + checksum (CRC32) to token format: `base64("V1:<epochMicros>:<uuid>:<crc32>")`. Fail loudly on checksum mismatch.

---

### Day 19 · Distributed lock fencing token race
**File**: `common-lib/src/main/java/com/ecom/common/lock/RedisDistributedLock.java:44-57`

**Finding**: `tryAcquire()` does `SET NX` then `INCR fencing token`. If INCR fails (Redis OOM, connection lost after SET succeeds), `LockHandle` returned with `fencingToken=0` (nullable check `fencing == null ? 0L`). Caller uses `fencingToken=0` as guard on DB operation, but DB fence_version >= 1 check still passes with 0 → lock semantic broken.

**Root cause**: Two separate Redis calls in non-atomic sequence. INCR failure doesn't rollback SET NX. Fencing token fallback `0L` is invalid (should be > 0).

**Gap**: No atomic Lua script combining SET NX + INCR. No retry for INCR. No exception thrown when INCR fails.

**Deploy risk**: 🔴 **Medium-High** — inventory snapshot job (Day 19) uses this lock + fence. If INCR fails silently, fence_version=0 might bypass DB guard, causing duplicate snapshots or data loss in concurrent edits.

**Recommendation**: Combine into single Lua script:
```lua
if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
  return redis.call('incr', KEYS[2])  -- return fencing token
else
  return nil
end
```

---

### Day 20 · k6 insufficient VU allocation
**File**: `load/k6/place-order.js:43`

**Finding**: `preAllocatedVUs: 100` with `target: 200 req/s`. Rule: VUs needed ≥ (target_rate × latency_sec × safety). At 200 req/s, expected latency ~200ms = 0.2s, need ≥ 200 × 0.2 × 1.5 safety = **60 VUs**. With 100 VUs, marginal but OK. However, if latency grows to 500ms (measured at P99), need 150 VUs → k6 insufficient allocation warning kicks in → k6 becomes bottleneck, not app.

**Root cause**: VU calculation didn't account for P99 latency surge (observed at Day 20 load test: P99=2.1s). Estimated with P50=200ms.

**Gap**: No k6 check for "insufficient VUs warning" in CI gate. Test passes even if k6 is bottleneck.

**Deploy risk**: 🔴 **Medium** — misleading load test results. Appears app handles 200 req/s, but actual capacity unknown because k6 couldn't push full load.

**Recommendation**: Increase `preAllocatedVUs: 300` (conservative). Add k6 output check for "insufficient VUs" warnings in CI pipeline. If found, fail test.

---

## 🟡 YELLOW SEVERITY (Design debt + assumptions)

### Day 15 · XFetch metadata reset on put() loses fetch cost signal
**File**: `services/product-service/src/main/java/com/ecom/product/config/cache/ProbabilisticExpiringCache.java:136-142`

**Finding**: `put()` resets `fetchDurationMs = 1`, meaning immediately after refresh, next XFetch probability is near 0 until TTL expires again. If key refreshed at T=50s (5s before TTL expiry), next refresh at T=59s may see `fetchDurationMs=1` (reset 9s ago) not original `fetchDurationMs=50-200` (expensive query), so XFetch underestimates likelihood of regeneration → double-refresh possible near expiry.

**Root cause**: Metadata reset assumes "just refreshed = cheap to refresh again," but doesn't carry forward actual fetch cost of just-computed value.

**Gap**: Metadata should capture cost from valueLoader.call(), not reset it. Current impl breaks XFetch cost signal.

**Deploy risk**: 🟡 **Low** (observable in traces: occasional 2× refresh near TTL, but XFetch still prevents N×1000 load spike). Rare under normal traffic.

**Recommendation**: Don't reset `fetchDurationMs` on put — preserve value from loader. Only reset fetchedAt. Or capture duration from `get()` method and pass into `put()`.

---

### Day 16 · Partial index excludes common queries
**File**: `services/product-service/src/main/resources/db/migration/V5__product_search_indexes.sql:42-45`

**Finding**: Covering index `idx_products_category_active_covering` has `WHERE status = 'ACTIVE'` predicate. Queries filtering by other statuses (e.g., `status IN ('ACTIVE', 'ARCHIVED')` for admin) won't use this index → still do full scan. Comment doesn't warn about this trade-off.

**Root cause**: Assumed "95% of queries are for ACTIVE only" (correct for user-facing) but didn't account for admin/analytics queries on all statuses.

**Gap**: No second index for archived product queries. No comment noting admin queries bypasses this optimization.

**Deploy risk**: 🟡 **Low** (admin queries less frequent), but could impact admin dashboard latency.

**Recommendation**: Add second partial index `WHERE status = 'ARCHIVED'` or create non-partial covering index as fallback (larger, but covers all statuses).

---

### Day 17 · Projection COUNT query duplication
**File**: `services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java:95-102`

**Finding**: `findSummariesByUserId()` returns `Page<OrderSummaryView>`. Spring automatically executes **2 queries**: (1) SELECT + size() subquery for items count, (2) count(*) query for Page.getTotalElements(). Comment says "≤1 query" but it's actually 2. If caller doesn't need total count (e.g., infinite scroll with Limit), using Page is wasteful.

**Root cause**: Assumed `Page` = 1 query, but Spring Data JPA auto-generates count query for Page interface.

**Gap**: No variant returning `Limit<OrderSummaryView>` (no total count). No comment noting "use Limit if totalElements not needed."

**Deploy risk**: 🟡 **Low** (2 queries still < 1+N), but observable: slow admin dashboard listing 50 pages × 2 count queries.

**Recommendation**: Add method `findSummariesByUserIdLimit(userId, limit)` returning `Limit` or `List` instead of Page. Document when to use each.

---

### Day 18 · Keyset sort hardcoded + index must match exactly
**File**: `services/product-service/src/main/java/com/ecom/product/service/ProductService.java:160`

**Finding**: Keyset pagination hardcodes `ORDER BY created_at DESC, id DESC` in queries (ProductRepository.searchKeyset). Index V6 migration created `(created_at DESC, id DESC)`. If future refactor changes sort to `name DESC, id DESC`, index becomes useless for sort, planner does Sort node instead of Index Scan → latency degrades silently.

**Root cause**: No assertion tying sort order to index. Sort field passed to repository but hardcoded in SQL.

**Gap**: No test verifying "keyset query uses Index Scan, not Sort" (EXPLAIN ANALYZE assertion missing). No doc warning about sort-index coupling.

**Deploy risk**: 🟡 **Medium** (latency surprise if sort refactored without reindexing).

**Recommendation**: 
1. Add EXPLAIN ANALYZE assertion in integration test: assert Sort operator absent.
2. Document in method: "Sort order MUST match index definition at services/.../V6__keyset_pagination_index.sql".

---

### Day 18 · Cursor encoding micro-precision may lose sub-microsecond data
**File**: `services/product-service/src/main/java/com/ecom/product/web/dto/ProductCursor.java:43, 61-63`

**Finding**: Encode uses `createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L`. Postgres TIMESTAMPTZ has microsecond storage (6 decimal places), but Java Instant has nanosecond precision. Encoding loses sub-microsecond data. If DB returns TIMESTAMP with 500 nanos (6.5 micros), encoding rounds to 6 micros → decoded cursor points to wrong row.

**Root cause**: Assumption "JDBC returns TIMESTAMPTZ as Instant" = full precision, but JDBC truncates nanos to micros on read.

**Gap**: No precision test. Comment says "micro-precision khớp TIMESTAMPTZ" but doesn't verify round-trip.

**Deploy risk**: 🟡 **Low** (rare: only if rows created same microsecond, edge case for keyset tie-break). Acceptable but not ideal.

**Recommendation**: Add test: encode-decode round-trip preserves 1-microsecond precision. Document assumption about JDBC truncation.

---

### Day 19 · Metadata survives pod recycle but loses context
**File**: `services/product-service/src/main/java/com/ecom/product/config/cache/ProbabilisticExpiringCache.java:64`

**Finding**: `fetchMetadata` is in-memory (per-instance). Pod restart wipes metadata but cached values remain (in Redis L2). After restart, cached entries appear "freshly fetched" (fetchedAt = now), delaying XFetch trigger. If pod restarts every 2 hours, metadata loss every 2 hours → temporary XFetch ineffective immediately after restart.

**Root cause**: Metadata stored in-memory, not persisted to Redis alongside cached value.

**Gap**: No "metadata store" in Redis. No synchronization point between pod instance and distributed metadata.

**Deploy risk**: 🟡 **Low** (local impact, cluster-wide XFetch still effective from other pods).

**Recommendation**: Store metadata in Redis alongside cache value. Embed `{value, fetchedAt, duration}` in Redis as JSON object, not just value. Requires decode logic change.

---

### Day 20 · k6 doesn't measure read-after-write latency
**File**: `load/k6/place-order.js:77-100`

**Finding**: Workload is write-only: each iteration places order with new idempotencyKey. Never queries "get order by id" or "list my orders" after place. Doesn't measure read-after-write latency (e.g., user refreshes page after placing order). P50/P99 only cover write path, not full user journey.

**Root cause**: Load test script treats place-order as isolated operation, not part of user session flow.

**Gap**: No measurement of "user places → user views order" round-trip latency. Missing P95/P99 for read-heavy follow-up.

**Deploy risk**: 🟡 **Medium** (post-order read might be bottleneck; current test only gates write performance). Real users see higher latency post-checkout.

**Recommendation**: Extend k6 script: 10% iteration includes `GET /orders/:id` after place-order. Measure full journey latency.

---

### Day 20 · k6 pool buildTokenPool() not concurrent
**File**: `load/k6/place-order.js:59`

**Finding**: `setup()` calls `buildTokenPool()` sequentially. If pool building does N auth login calls (POST /auth/login) per VU in sequence, ramp-up phase blocks on auth. Pool setup completes only after all tokens acquired → first stress iteration delayed.

**Root cause**: `setup()` is single-threaded. Auth endpoint not warmed up before load test starts.

**Gap**: No concurrent token acquisition. No warmup phase comment.

**Deploy risk**: 🟡 **Low** (local k6 setup overhead, not app bottleneck). But skews first iteration latency.

**Recommendation**: Add k6 warmup scenario (separate from main stress test): 30s of low-rate requests to warm JIT + pools before starting ramped-arrival-rate.

---

## 🟢 GREEN SEVERITY (Production-grade patterns)

### Day 15 · Polymorphic deserialization security
**File**: `services/product-service/src/main/java/com/ecom/product/config/cache/CacheConfig.java:76-88`

**Finding**: Redis JSON serializer uses `BasicPolymorphicTypeValidator` whitelist restricting deserialization to `com.ecom.product.*` packages. Good defense against Jackson CVE-2017-7525 gadget chains.

**Status**: ✅ **Pattern followed correctly**

---

### Day 15 · L2 evict before L1 strategy documented
**File**: `services/product-service/src/main/java/com/ecom/product/config/cache/TwoTierCache.java:130-138`

**Finding**: Comment explains "evict L2 first, L1 second" prevents L2 stale from backfilling L1 if L2 evict fails. Correct ordering for cascade safety.

**Status**: ✅ **Reasoning documented**

---

### Day 16 · ANALYZE after index creation
**File**: `services/product-service/src/main/resources/db/migration/V5__product_search_indexes.sql:54`

**Finding**: Migration ends with `ANALYZE products` to update planner statistics immediately. Prevents "index just created but planner doesn't use it yet" issue.

**Status**: ✅ **Best practice followed**

---

### Day 17 · Projection DTO constructor expression
**File**: `services/order-service/src/main/java/com/ecommerce/order/domain/OrderRepository.java:95-102`

**Finding**: `OrderSummaryView` record used in constructor expression projection. Correctly avoids loading full Order entity + items → reduces memory + GC. Spring Data JPA auto-generates count query for Page.

**Status**: ✅ **Production-appropriate**

---

### Day 18 · Cursor URL-safe base64 encoding
**File**: `services/product-service/src/main/java/com/ecom/product/web/dto/ProductCursor.java:38`

**Finding**: Uses `Base64.getUrlEncoder().withoutPadding()` (URL-safe variant without `=` padding). Correct for opaque tokens in query params (no '%' encoding needed).

**Status**: ✅ **Correct choice**

---

### Day 18 · Base64 decode error handling
**File**: `services/product-service/src/main/java/com/ecom/product/web/dto/ProductCursor.java:52-69`

**Finding**: `decode()` catches `IllegalArgumentException` (covers NumberFormatException, UUID.fromString error, base64 decode error) → throws `BusinessException(BAD_REQUEST)` for client. Returns 400, not 500.

**Status**: ✅ **Input validation correct**

---

### Day 19 · Lua release script prevents stale token release
**File**: `common-lib/src/main/java/com/ecom/common/lock/RedisDistributedLock.java:27-32`

**Finding**: Release script checks `redis.call('get', key) == value` before DEL. Prevents scenario where lock TTL expired, lock reacquired by different process, then original holder tries to release → would not delete new lock owner's token.

**Status**: ✅ **Correctness guarantee**

---

### Day 19 · Fencing token monotonic increment
**File**: `common-lib/src/main/java/com/ecom/common/lock/RedisDistributedLock.java:55`

**Finding**: Fencing token via `INCR` on separate counter guarantees monotonic ordering. Protects against GC pause + stale token scenarios (GC pause = process delayed, token released, reacquired, then paused process resumes with old token — fence version guards).

**Status**: ✅ **Strong correctness model** (aligns with Kleppmann fencing token paper)

---

### Day 20 · Open model (ramping-arrival-rate) prevents coordinated omission
**File**: `load/k6/place-order.js:35-51`

**Finding**: Uses k6 `ramping-arrival-rate` executor, not closed model (`ramping-vus`). Open model doesn't artificially suppress tail latency by slowing VU loop when app slows. Correctly reveals P99 latency under load.

**Status**: ✅ **Load test methodology correct**

---

## 📋 Actionable gaps for Week 4+ (Day 22 onwards)

| Gap | Priority | Owner | Timeline |
|-----|----------|-------|----------|
| [RED-15] Unbounded metadata cleanup | High | Day 22 refactor | Day 21 PR |
| [RED-16] CONCURRENTLY workaround runbook | Critical | DevOps/SRE | Before prod deploy Day 22 |
| [RED-17] Refactor OrderController to projection | Critical | Day 22 | Before Day 22 ship |
| [RED-18] Cursor checksum + version | Medium | Day 22 | Nice-to-have |
| [RED-19] Lua atomic lock+fence | High | Day 22 | Before lock used in InventorySnapshotJob |
| [RED-20] k6 VU reallocation | Medium | Day 22 | Update K6 config |
| [YELLOW-*] Index gaps + precision + metadata restore | Medium | Day 22-24 | Cumulative |

---

## 🎯 Self-review confidence

- **Code coverage**: 6 days × 3-5 critical files = ~25 files reviewed
- **Bug confidence**: 🔴 6 findings are real (logic gaps / incomplete refactors / race conditions)
- **Debt confidence**: 🟡 8 findings are design assumptions worth documenting (not bugs, but "know tradeoffs")
- **Pattern confidence**: 🟢 9 validations of correct patterns (shows good parts, not just gaps)

**Verdict**: Week 3 is **solid core (80% production-ready)** with **actionable gaps (20% technical debt)**. No incident-level bugs except [RED-16] CONCURRENTLY (needs runbook before prod deploy) and [RED-17] unused N+1 path (affects user latency now).

---

## 📎 Next reviewer checkpoint

**Week 4 kickoff** (Day 22 Elasticsearch): 
- [ ] [RED-16] CONCURRENTLY workaround tested in pre-prod
- [ ] [RED-17] OrderController refactored to projection, N+1 test passes
- [ ] [RED-19] Lua atomic lock in place before InventorySnapshotJob goes live
- [ ] Update this findings doc with Day 22+ discoveries
