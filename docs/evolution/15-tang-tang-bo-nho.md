# Chương 15 · ⚡ Tầng tầng bộ nhớ

**Day 15 — Two-tier cache (Caffeine L1 + Redis L2)**

---

> *"Bộ nhớ không phải là một cái hộp. Nó là một dãy phòng — phòng càng gần, càng nhanh, nhưng càng dễ quên."*

---

> 🎬 **Chương này có gì:** một dãy phòng bộ nhớ — phòng gần nhanh đến mức không kịp nhận ra mình đang gọi cache; một sản phẩm viral TikTok kéo 1000 request đập cùng một cache key; một liều thuốc chống giẫm đạp bằng *xác suất* (không lock, không infra); và căn phòng gần nhất hóa ra cũng là căn dễ lẫn đồ nhất. ⚡

---

## 🎬 Bối cảnh: anh Khải mở Slack lúc 9 giờ sáng thứ Hai

Tấm gương Week 2 vừa khép lại. Tin nhắn của anh Khải bật lên ngay đầu tuần:

> 🗣️ *"Phòng product complain trang detail load chậm. CFO hỏi tại sao infra cost x3 mà latency còn tệ hơn. Tuần này deliver cache, demo Friday."* 📅

Tonny nhìn dashboard. P99 của `GET /products/{id}` đang là **250ms**. Một tháng trước nó là 80ms. Catalog phình từ 5 nghìn lên 50 nghìn sản phẩm, traffic gấp 10. Infra cũng đã x3 — vậy mà nghẽn vẫn nghẽn.

Mạng đôi khi không trả lời được bằng tiền. Nó trả lời bằng **kiến trúc**. 🏗️

---

## 🚪 Câu hỏi cũ — "Cache là gì?" (và phòng nào?)

Một câu tưởng cũ rích. Nhưng câu trả lời thật ra phụ thuộc câu hỏi kế tiếp: cache *ở đâu*?

Phần cứng đã dạy bài này từ thập niên 70. CPU có L1, L2, L3 trước khi chạm tới RAM. Latency chênh nhau hàng nghìn lần — và **không một tier nào cân được latency, capacity, cost cùng lúc**:

| 🚪 Phòng | Latency | Đặc tính |
| --- | --- | --- |
| Register | vài nanosecond | gần nhất, nhỏ nhất |
| RAM | vài chục ns | gần, vừa |
| Disk | vài triệu ns | xa, mênh mông |

Cache application thì đến muộn hơn. Redis ra đời 2009, ngành software như tìm được liều thuốc cho mọi cơn đau latency: *"Cứ Redis là xong."* Một tier, một sự thật, dễ hiểu.

Nhưng — Redis vẫn có RTT. Một mili giây tròn trĩnh từ pod tới Redis cluster. Nhân với 10 nghìn QPS = **10 thread-second mỗi giây** chỉ để ngồi chờ Redis trả lời. Network chưa bao giờ là miễn phí. 💸

Còn **Caffeine** — thư viện cache in-process của Ben Manes — đo được **50 nanosecond** mỗi lookup. Nhanh gấp hai mươi nghìn lần Redis. Nhanh đến mức không còn nhận ra mình đang gọi cache.

Vậy xài Caffeine luôn cho khỏe? Không được. Bốn pod = bốn cache độc lập. Pod A update sản phẩm, ba pod kia **không hề hay biết**. Đó là cái giá của *"in-process"*.

Hai sự thật ngược chiều. Câu trả lời chỉ có thể là — **kết hợp cả hai**.

---

## 🏢 Hai tầng — dãy phòng RAM và kho

Tonny vẽ lên whiteboard cái dãy phòng:

```
Request → L1 Caffeine (50ns)
              ↓ miss
          L2 Redis (1ms)
              ↓ miss
          Postgres (30ms)
```

- 🚪 **L1 (Caffeine)** là căn phòng sát cửa: nhanh, gần, nhưng **mau quên**. TTL 60 giây — để khi pod khác cập nhật dữ liệu, mình cũng tự "quên" theo mà đi hỏi lại L2.
- 🏬 **L2 (Redis)** là kho chung phía sau: chậm hơn, nhưng đảm bảo bốn pod cùng nhìn thấy *một* sự thật. TTL 5 phút — chỉ là safety net phòng khi evict hỏng.

Logic đọc-ghi hai tầng viết tay sẽ ngốn 200 dòng. Spring Cache abstraction tóm gọn vào đúng một annotation:

```java
@Cacheable(value = "product:byId", key = "#id")
public ProductResponse get(UUID id) {
    return productMapper.toResponse(loadOrThrow(id));
}
```

Annotation đẹp. *Quá* đẹp. Đằng sau nó là `CacheConfig` ~130 dòng để wire L1 và L2 lại với nhau qua `TwoTierCache` — một class implement `org.springframework.cache.Cache`, compose cả Caffeine native lẫn `RedisCache`. Trong đó có những chỗ framework bắt phải đi đúng đường: `RedisCache` có constructor *protected*, không `new` thẳng được, phải vòng qua `RedisCacheManager.builder().initialCacheNames(...).build()` rồi `.getCache(name)`, đăng ký vào `SimpleCacheManager`.

> 💡 Wire hai tầng tốn ~130 dòng plumbing; cái annotation một dòng phía trên đã giấu hết cho ta. Bài học cũ mà luôn đúng: **framework cho bạn API, nhưng cũng định luôn cách bạn được phép dùng nó.**

---

## 🐘 Thundering herd — và một liều thuốc bằng xác suất

Sản phẩm lên xu hướng TikTok. 1 product. 1 cache key. **1000 request đồng thời.**

TTL hết. Cả 1000 cùng thấy *"expired"* trong cùng một mili giây. Cả 1000 cùng lao xuống đập DB một lúc. DB CPU vọt **100%**. Cascading fail. 🔥

Đây là **cache stampede** (a.k.a. *thundering herd*) — một căn bệnh có tới 5 phương thuốc, Tonny đã liệt kê đủ trong [issue 15](../issues/15-cache-stampede.md):

| 💊 Thuốc | Cách hoạt động | Tác dụng phụ |
| --- | --- | --- |
| Distributed lock | strict, 1 process refresh | lock orphan khi GC pause = ác mộng |
| In-process single-flight | Caffeine `LoadingCache`, per-JVM | không xử lý multi-instance |
| Refresh-ahead | cron job refresh trước | phải biết key nào hot từ trước |
| Stale-while-revalidate | trả stale, refresh nền | metadata phức tạp |
| ⭐ **XFetch** | probabilistic early expiration | duplicate compute nhẹ |

XFetch (Vattani et al. 2015) đơn giản đến mức gần như đẹp:

```java
double xfetchExpr = fetchDurationMs * beta * -Math.log(random);
boolean shouldRefresh = xfetchExpr >= remainingTtl;
```

Trong cửa sổ 30 giây cuối TTL, mỗi lookup gen một `random ∈ (0,1)`. Càng gần expire, công thức càng dễ trigger refresh. Random hóa khiến mỗi thread *tự quyết khác nhau* — vài thread refresh sớm, đa số vẫn lấy value cũ trong căn phòng L1. **Không lock. Không block. Không infra mới.**

> 🧠 Bài học: lock đảm bảo strict, nhưng đời thực không cần strict ở *mọi* nơi. Ở đây, đổi "duplicate compute 2-3 lần thay vì 1" lấy "zero lock-orphan risk" — là một deal đẹp.

Test viết thẳng thừng:

```java
// 100 thread cùng gọi cache.get sau khi key vào early window
// Assert: loader chỉ được gọi < 25 lần
assertThat(loaderCalls.get()).isLessThan(n / 4);
```

Test pass. Một test ngắn, một bài học dài.

---

## 🧹 Invalidation — và căn phòng dễ lẫn đồ nhất

Phil Karlton có câu để đời: *"There are only two hard things in Computer Science: cache invalidation and naming things."*

Trong dãy phòng này, **căn gần nhất cũng là căn dễ lẫn đồ nhất**: L1 nhanh, nhưng nếu quên dọn thì nó sẽ tự tin phục vụ đồ cũ. Invalidation khó vì hai lý do:

🔑 **Một — nhiều cache name.** `product:byId` và `product:bySlug` cùng trỏ về một product nhưng là hai key độc lập. Mỗi update phải dọn *cả hai* phòng. `@Caching` của Spring gom lại:

```java
@Caching(evict = {
    @CacheEvict(value = CACHE_PRODUCT_BY_ID, key = "#id"),
    @CacheEvict(value = CACHE_PRODUCT_BY_SLUG, key = "#req.slug()")
})
public ProductResponse update(UUID id, ProductUpdateRequest req) { ... }
```

🔄 **Hai — slug có thể đổi.** Annotation declarative chỉ chạm được vào args và result — nó *không có khái niệm "old state"*. User đổi slug `iphone-15` → `iphone-15-pro-max`: annotation evict được slug mới (miss → reload OK), nhưng **slug cũ vẫn nằm lì trong phòng**, vẫn trỏ về dữ liệu cũ. Đồ cũ lẫn trong căn phòng gần nhất.

Phải dọn tay:

```java
String oldSlug = product.getSlug();
// ... update ...
if (!oldSlug.equals(req.slug())) {
    cacheManager.getCache(CACHE_PRODUCT_BY_SLUG).evict(oldSlug);
}
```

> ⚠️ Một dòng code. Một bug suýt để lọt. Đây đúng kiểu lỗi AI assistant hay bỏ qua — declarative annotation trông đủ rồi, nó không nghĩ tới "old slug còn nằm đâu đó". Review tay là bắt buộc.

---

## 📊 Quan sát — và sự thật của hit ratio

Cache có hai tier thì metric cũng phải tách hai tier — nếu không, một con số gộp sẽ giấu mất phòng nào đang gánh việc:

```promql
product_cache_hits_total{tier="l1"}
product_cache_misses_total{tier="l1"}
product_cache_hits_total{tier="l2"}
product_cache_misses_total{tier="l2"}
```

Bind từ `LongAdder` trong `TwoTierCache` qua Micrometer Gauge, pre-wire `/actuator/prometheus` để Day 20 ghép vào Grafana dashboard.

> 📚 Hai nguồn số liệu, đừng lẫn: con P99 ~250ms ở phần bối cảnh đến từ **production dashboard** thực; bảng dưới đây là **JMeter local smoke test** (100 thread × 5 phút, 10k product phân phối Zipf) — đo "before/after" trong môi trường lab, không phải số prod.

```
└── P50 getProduct:     35ms → 2ms
└── P99 getProduct:    250ms → 18ms
└── DB QPS:             980 → 52
└── L1 hit ratio:        — → 94%
└── L2 hit ratio:        — → 65%
└── Effective DB load:  100% → ~2%
```

Mạng đã trả lời. Không phải bằng tiền. Bằng kiến trúc. ⚡

---

## 🏁 Kết thúc ngày 15

```
📊 Scorecard:
├── Code:    7 file Java (CacheConfig, TwoTierCache, ProbabilisticExpiringCache, CacheMetrics, CacheProperties, +2 test)
├── Docs:    1 lesson · 2 performance · 1 ADR · 2 issue filled · 1 interview · 1 evolution
├── Test:    StampedeProtectionTest PASSED — loader calls < 25 với 100 concurrent
├── Build:   ./gradlew :services:product-service:build OK
└── Vibe:    "Hai tầng. Năm mươi nano đến một mili. Như nghe tim đập trong một căn phòng tối." 🫀
```

> 💡 **Senior vs Junior:** Junior paste `@Cacheable` rồi ngồi ngẩn ra tự hỏi vì sao update không reflect. Senior audit **toàn bộ write path TRƯỚC** khi paste annotation. Bug cache không nằm ở chỗ cache — nó nằm ở chỗ *quên dọn phòng*. Phil Karlton đã đúng.

---

*→ Sang chương sau, dataset 50 nghìn product sẽ chạm mốc 1 triệu. Cache chỉ lo được phần đọc lặp. Còn query chạm tới row chưa cache — SQL phải tự thân vận động. **EXPLAIN ANALYZE** sẽ trở thành bạn đồng hành. Sequential scan, index không dùng đúng, cardinality estimate sai — Postgres giấu lắm thứ sau câu `SELECT *` quen thuộc...* 🔎
