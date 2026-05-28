# Chương 15 · ⚡ Tầng tầng bộ nhớ

**Day 15 — Two-tier cache (Caffeine L1 + Redis L2)**

---

> *"Bộ nhớ không phải là một cái hộp. Nó là một dãy phòng — phòng càng gần, càng nhanh, nhưng càng dễ quên."*

---

## Bối cảnh

Tấm gương Week 2 vừa khép lại. Anh Khải mở Slack lúc 9 giờ sáng thứ Hai: *"Phòng product complain trang detail load chậm. CFO hỏi tại sao infra cost x3 nhưng latency tệ hơn. Tuần này deliver cache, demo Friday."*

Tonny nhìn vào dashboard. P99 của `GET /products/{id}` là 250ms. Một tháng trước nó là 80ms. Catalog từ 5 nghìn lên 50 nghìn sản phẩm. Traffic gấp 10. Nhưng infra cũng đã x3 — vậy mà nghẽn vẫn nghẽn.

Mạng đôi khi không trả lời được bằng tiền. Nó trả lời bằng kiến trúc.

## Câu hỏi cũ — "Cache là gì?"

Một câu hỏi tưởng cũ. Nhưng câu trả lời thực ra phụ thuộc câu hỏi tiếp theo: cache *ở đâu*?

CPU có L1, L2, L3 cache trước khi tới RAM. Latency chênh nhau hàng nghìn lần: register vài nanosecond, RAM vài chục, disk vài triệu. Không ai thắc mắc *"vì sao cần nhiều tầng?"* — vì kiến trúc phần cứng đã dạy bài học từ thập niên 70: **không có một tier nào cân bằng được latency, capacity, và cost cùng lúc**.

Cache application thì đến muộn hơn. Khi Redis ra đời 2009, ngành software như tìm được câu trả lời cho mọi bài toán latency: *"Cứ Redis là xong."* Một tier, một sự thật, dễ hiểu.

Nhưng Redis cũng có RTT. Một mili giây tròn từ pod đến Redis cluster. Nhân với 10 nghìn QPS — đó là 10 thread-second mỗi giây chỉ để chờ Redis trả lời. Network không bao giờ là free.

Caffeine — thư viện cache in-process của Ben Manes — đo 50 nanosecond mỗi lookup. Hai mươi nghìn lần nhanh hơn Redis. **Nhanh đến mức không còn nhận ra mình đang gọi cache**.

Vậy tại sao không dùng Caffeine luôn? Vì bốn pod application = bốn cache độc lập. Pod A update sản phẩm, ba pod còn lại không biết. Đó là cái giá của "in-process".

Hai sự thật ngược nhau. Câu trả lời chỉ có thể là — kết hợp.

## Hai tầng — như RAM và disk

Tonny vẽ trên whiteboard sơ đồ:

```
Request → L1 Caffeine (50ns)
              ↓ miss
          L2 Redis (1ms)
              ↓ miss
          Postgres (30ms)
```

L1 là người gác cửa: nhanh, gần, nhưng quên dễ. TTL 60 giây — để khi pod khác cập nhật, mình cũng "quên" theo mà tự đi hỏi lại L2.

L2 là kho chung: chậm hơn, nhưng đảm bảo bốn pod cùng thấy một sự thật. TTL 5 phút — chỉ là safety net cho trường hợp evict hỏng.

Logic đọc viết bằng tay sẽ tốn 200 dòng code. Spring Cache abstraction tóm nó thành một annotation:

```java
@Cacheable(value = "product:byId", key = "#id")
public ProductResponse get(UUID id) {
    return productMapper.toResponse(loadOrThrow(id));
}
```

Annotation đẹp. Quá đẹp. Nhưng đằng sau nó là `CacheConfig` 130 dòng để wire L1 và L2 lại với nhau qua `TwoTierCache` — một class implement `org.springframework.cache.Cache` và compose cả Caffeine native lẫn `RedisCache`.

Spring có `SimpleCacheManager` để đăng ký các custom `Cache`. Constructor của `RedisCache` thì protected — không `new` trực tiếp được. Phải đi qua `RedisCacheManager.builder().initialCacheNames(...).build()` rồi `.getCache(name)`. Một chi tiết nhỏ. Một bài học cũ: framework cho bạn API, nhưng cũng định ra cách phải dùng nó.

## Thundering herd — và một bài toán xác suất

Sản phẩm viral trên TikTok. 1 product. 1 cache key. 1000 request đồng thời.

TTL hết. Cả 1000 đều thấy "expired" trong cùng một mili giây. Cả 1000 đập DB cùng lúc. DB CPU 100%. Cascading fail.

Đây là **cache stampede**, hay *thundering herd*. Một bệnh có 5 phương thuốc — Tonny đã liệt kê hết trong [issue 15](../issues/15-cache-stampede.md):

- **Distributed lock** — strict 1 process refresh. Nhưng lock orphan khi GC pause là cơn ác mộng.
- **In-process single-flight** — Caffeine `LoadingCache`. Per-JVM. Không xử lý multi-instance.
- **Refresh-ahead** — cron job. Phải biết key nào hot trước.
- **Stale-while-revalidate** — trả stale, refresh background. Phức tạp metadata.
- **XFetch** — probabilistic early expiration.

XFetch (Vattani et al. 2015) đơn giản đến mức gần như đẹp:

```java
double xfetchExpr = fetchDurationMs * beta * -Math.log(random);
boolean shouldRefresh = xfetchExpr >= remainingTtl;
```

Trong cửa sổ 30 giây cuối TTL, mỗi lookup gen một `random ∈ (0,1)`. Càng gần expire, công thức càng dễ trigger refresh. Random hóa khiến các thread quyết định khác nhau — vài thread refresh sớm, đa số tiếp tục lấy value cache. **Không lock. Không block. Không infra mới.**

Bài học: lock đảm bảo strict, nhưng đời thực không cần strict ở mọi nơi. Ở đây, duplicate compute 2-3 lần thay vì 1 — đổi lấy zero lock orphan risk — là một deal đẹp.

Test viết một cách trực tiếp:

```java
// 100 thread cùng gọi cache.get sau khi key vào early window
// Assert: loader chỉ được gọi < 25 lần
assertThat(loaderCalls.get()).isLessThan(n / 4);
```

Test pass. Một test ngắn, một bài học dài.

## Invalidation — câu khó nhất

Phil Karlton có một câu nổi tiếng: *"There are only two hard things in Computer Science: cache invalidation and naming things."*

Trong project này, invalidation khó vì hai lý do:

**Một**, nhiều cache name. `product:byId` và `product:bySlug` — cùng một product, hai cache key độc lập. Mỗi update phải evict cả hai. `@Caching` của Spring giúp gom lại:

```java
@Caching(evict = {
    @CacheEvict(value = CACHE_PRODUCT_BY_ID, key = "#id"),
    @CacheEvict(value = CACHE_PRODUCT_BY_SLUG, key = "#req.slug()")
})
public ProductResponse update(UUID id, ProductUpdateRequest req) { ... }
```

**Hai**, slug có thể đổi. Annotation declarative chỉ truy cập được args và result — không có khái niệm "old state". Khi user đổi slug từ `iphone-15` sang `iphone-15-pro-max`, annotation evict được slug mới (cache miss → reload OK), nhưng slug cũ vẫn còn trong cache, vẫn trỏ về dữ liệu cũ.

Giải pháp manual:

```java
String oldSlug = product.getSlug();
// ... update ...
if (!oldSlug.equals(req.slug())) {
    cacheManager.getCache(CACHE_PRODUCT_BY_SLUG).evict(oldSlug);
}
```

Một dòng code. Một bug suýt để lọt. Đây là loại lỗi AI assistant thường bỏ qua — review tay là bắt buộc.

## Quan sát — và sự thật của hit ratio

Cache có hai tier. Metric cũng phải tách hai tier.

```promql
product_cache_hits_total{tier="l1"}
product_cache_misses_total{tier="l1"}
product_cache_hits_total{tier="l2"}
product_cache_misses_total{tier="l2"}
```

Bind từ `LongAdder` của `TwoTierCache` qua Micrometer Gauge. Pre-wire `/actuator/prometheus` để Day 20 ghép Grafana dashboard.

Local smoke test với JMeter 100 thread × 5 phút, 10k product distribution Zipf:

```
└── P50 getProduct:     35ms → 2ms
└── P99 getProduct:    250ms → 18ms
└── DB QPS:             980 → 52
└── L1 hit ratio:        — → 94%
└── L2 hit ratio:        — → 65%
└── Effective DB load:  100% → ~2%
```

Mạng đã trả lời. Không phải bằng tiền. Bằng kiến trúc.

## Kết thúc ngày 15

```
└── Day 15
    ├── Code:    7 file Java (CacheConfig, TwoTierCache, ProbabilisticExpiringCache, CacheMetrics, CacheProperties, +2 test)
    ├── Docs:    1 lesson, 2 performance, 1 ADR, 2 issue filled, 1 interview, 1 evolution
    ├── Test:    StampedeProtectionTest PASSED — loader calls < 25 với 100 concurrent
    ├── Build:   ./gradlew :services:product-service:build OK
    └── Vibe:    "Hai tầng. Năm mươi nano đến một mili. Cảm giác như nghe tim đập trong một căn phòng tối."
```

> 💡 **Senior vs Junior**: Junior viết `@Cacheable` rồi tự hỏi vì sao update không reflect. Senior audit toàn bộ write path TRƯỚC khi paste annotation. Bug cache không nằm ở chỗ cache — nó nằm ở chỗ quên evict. Phil Karlton đã đúng.

---

*→ Sang chương sau, dataset 50 nghìn product sẽ chạm mốc 1 triệu. Cache chỉ giải quyết phần đọc lặp. Còn query chạm tới row chưa cache — SQL phải tự lo. EXPLAIN ANALYZE sẽ trở thành bạn đồng hành. Sequential scan, index không dùng đúng, cardinality estimate sai — Postgres giấu nhiều thứ sau câu `SELECT *` quen thuộc.*
