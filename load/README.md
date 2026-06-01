# 🚀 Load testing harness (Day 20)

k6 load test + observability stack (Prometheus + Tempo + Grafana) cho NexaShop.
Mục tiêu: đo **P50/P95/P99 / throughput / error rate** và **chỉ ra bottleneck
qua OTel trace timeline**, đồng thời so **virtual thread vs platform thread**
under load.

> 📖 Phân tích chi tiết: [`docs/performance/20-load-test-report-template.md`](../docs/performance/20-load-test-report-template.md)
> · [`docs/performance/20b-vt-vs-platform-thread-bench.md`](../docs/performance/20b-vt-vs-platform-thread-bench.md)
> · methodology: [`docs/lessons/20-load-testing-methodology.md`](../docs/lessons/20-load-testing-methodology.md)

---

## 📦 Cấu trúc

```
load/
├── k6/
│   ├── lib/config.js          # env-driven URLs, SKU, user pool size
│   ├── lib/auth.js            # setup(): register+login pool token (ngoài vòng đo)
│   ├── thresholds.js          # SLO gate (P95/P99/error) — fail-the-build
│   ├── place-order.js         # write-heavy: cart-add → place-order (OPEN model)
│   └── browse-products.js     # read-heavy: search (cache-hot, VT toả sáng)
├── run-load-test.sh           # warmup + run + export summary JSON
└── results/                   # summary JSON mỗi run (gitignored)
```

## ⚙️ Precondition (BẮT BUỘC trước khi chạy)

1. **Infra + service up**: Postgres, Redis, Kafka, + auth/product/cart/inventory/order.
2. **Observability stack** (xem dưới).
3. **Seed dữ liệu**: 1 SKU có inventory đủ lớn cho cả run.
   - product-service: đã seed 1M product (Day 16). Chọn 1 SKU hoặc tạo
     `SKU-LOADTEST-0001`.
   - inventory-service: seed stock cao (vd `quantity=1_000_000`) cho SKU đó —
     nếu không, place-order sẽ trả 409 InsufficientStock (đếm riêng metric
     `order_insufficient_stock`, KHÔNG tính error, nhưng làm loãng số đo).

## 🔧 Bước 1 — Bật observability stack

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d \
  prometheus tempo grafana
```

- Prometheus: http://localhost:9090 (scrape `/actuator/prometheus` của order + product)
- Tempo: nhận trace Zipkin-format ở `localhost:9412` (order-service export sang đây)
- Grafana: http://localhost:3000 (admin / admin) → dashboard **Load Test Overview** đã provision

> Zipkin (Day 9, cổng 9411) vẫn chạy song song cho dev thường. Day 20 thêm
> Tempo vì Grafana link metric ↔ trace inline (exemplars) — Zipkin UI tách rời.

## 🔧 Bước 2 — Trỏ trace order-service sang Tempo

Tempo có Zipkin receiver, nên KHÔNG cần đổi dependency Java (common-lib export
Zipkin format). Chỉ override endpoint khi start order-service:

```bash
# Run VT (mặc định — profile thường)
ZIPKIN_ENDPOINT=http://localhost:9412/api/v2/spans \
  ./gradlew :services:order-service:bootRun
```

## 🔧 Bước 3 — Chạy load test

```bash
# Lần 1 — Virtual Threads (mặc định)
./load/run-load-test.sh place-order vt

# Lần 2 — Platform threads (Tomcat pool 200) → so sánh
# Start lại order-service với profile platform:
SPRING_PROFILES_ACTIVE=platform ZIPKIN_ENDPOINT=http://localhost:9412/api/v2/spans \
  ./gradlew :services:order-service:bootRun
./load/run-load-test.sh place-order platform
```

Read-heavy (VT toả sáng nhất):

```bash
./load/run-load-test.sh browse-products vt
./load/run-load-test.sh browse-products platform
```

## 📊 Bước 4 — Đọc kết quả

- **k6 stdout**: P50/P90/P95/P99, RPS, `http_req_failed`, custom
  `place_order_latency` / `cart_add_latency`. Exit code != 0 = threshold vỡ.
- **Grafana → Load Test Overview**: RPS, latency percentile theo thời gian,
  JVM thread count, Hikari pool active/pending (bottleneck connection pool!).
- **Grafana → Explore → Tempo**: mở 1 trace `POST /orders` chậm, xem span nào
  ăn thời gian (DB insert? outbox? cart RPC?). Đây là cách chỉ bottleneck
  bằng bằng chứng, không đoán.

## ⚠️ Cảnh báo diễn giải số

- Local Docker number là **relative** (so vt vs platform), KHÔNG phải prod
  capacity. Đừng extrapolate tuyến tính.
- Nếu k6 log `insufficient VUs` → chính k6 là bottleneck, tăng `maxVUs`,
  số đo trước đó bỏ.
- Open model: khi server vỡ, request dồn ứ làm P99 vọt — đó là **đúng**,
  không phải bug script (xem methodology doc, mục coordinated omission).
