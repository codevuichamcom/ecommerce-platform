# ⚡ Performance 20 — Load test report (template + lần chạy đầu)

> Template **tái dùng** cho mọi load test sau (Day 21 mock, flash sale Day 33).
> Copy block dưới, điền số. Triết lý: report load test phải trả lời được
> *"chịu được bao nhiêu, vỡ ở đâu, sửa gì"* — không phải dán đồ thị.

---

## 🎯 Mục tiêu đo (định trước khi chạy)

| Field | Value |
| --- | --- |
| Hệ thống | order-service `POST /orders` (cart-add → place-order) |
| SLO mục tiêu | P95 < 200ms · P99 < 500ms · error < 1% |
| Workload model | **Open** (`ramping-arrival-rate`) — đo capacity thật |
| Câu hỏi cần trả lời | (1) knee point RPS? (2) bottleneck ở đâu? (3) VT vs platform chênh bao nhiêu? |

## 🔧 Setup (ghi để reproduce)

| Field | Value |
| --- | --- |
| Hardware | _(điền: CPU core / RAM / SSD — vd local 8-core, 16GB)_ |
| JVM | Java 21, Spring Boot 3.4.5 |
| DB | Postgres 16, `max_connections=100`, Hikari `maximum-pool-size=20` |
| Profile | `vt` (default) vs `platform` (Tomcat pool 200) |
| k6 | `place-order.js`, stages 50→200→200→0 req/s, ~4 phút |
| Seed | SKU `SKU-LOADTEST-0001`, stock 1,000,000; user pool 50 |
| Observability | Prometheus 5s scrape · Tempo (Zipkin receiver) · Grafana dashboard `load-test-overview` |

## 📊 Results — điền sau khi chạy

> Lấy số từ: k6 stdout (client-side percentile) + Grafana panel (server-side).
> Ghi **cả hai** — lệch nhau lớn = network/k6 overhead, cần điều tra.

| Metric | VT run | Platform run | Ghi chú |
| --- | --- | --- | --- |
| Throughput đạt (req/s) | _(điền)_ | _(điền)_ | RPS thực server xử lý, không phải target |
| P50 (ms) | _(điền)_ | _(điền)_ | |
| P95 (ms) | _(điền)_ | _(điền)_ | SLO 200ms |
| P99 (ms) | _(điền)_ | _(điền)_ | SLO 500ms — tail |
| Error rate (%) | _(điền)_ | _(điền)_ | SLO < 1% |
| `hikaricp_connections_pending` peak | _(điền)_ | _(điền)_ | > 0 kéo dài = bottleneck pool |
| `process_cpu_usage` peak | _(điền)_ | _(điền)_ | CPU thấp + chậm = bound ở pool/lock |
| `jvm_threads_live` peak | _thấp (VT)_ | _~200 (pool)_ | dấu hiệu phân biệt 2 mode |

### Threshold verdict

```
✅ / ❌  http_req_duration p(95)<200
✅ / ❌  http_req_duration p(99)<500
✅ / ❌  http_req_failed rate<0.01
```

> k6 exit code != 0 = có threshold vỡ = **fail the build** (CI gate).

## 🔍 Bottleneck analysis (từ trace timeline)

Mở Grafana → Explore → Tempo, lọc trace `POST /orders` chậm nhất (sort by
duration). Phân rã span:

| Span | Thời gian | % tổng | Đọc ra gì |
| --- | --- | --- | --- |
| `Connection Acquisition` (Hikari) | _(điền)_ | _(điền)_ | cao = pool nghẽn (issue 20) |
| DB insert order + items | _(điền)_ | _(điền)_ | |
| Outbox record (cùng tx) | _(điền)_ | _(điền)_ | Day 13 |
| Cart RPC (sync read) | _(điền)_ | _(điền)_ | cross-service hop |

**Kết luận bottleneck**: _(điền — span nào ăn thời gian, sửa hướng nào)_

## ✅ Verdict + next action

- Knee point: ~_(điền)_ req/s trước khi P99 vỡ.
- Bottleneck chính: _(điền — vd connection pool acquisition)_.
- Action: _(điền — vd size pool 30 + connection-timeout 2s, xem issue 20)_.
- VT vs platform: xem [`20b-vt-vs-platform-thread-bench.md`](20b-vt-vs-platform-thread-bench.md).

> ⚠️ Số local Docker là **relative**, KHÔNG phải prod capacity. Để claim prod
> number phải đo trên infra thật nhiều node sau LB.

---

## 🔗 Related

- Harness: [`load/README.md`](../../load/README.md) · [`load/k6/place-order.js`](../../load/k6/place-order.js)
- Methodology: [`lessons/20-load-testing-methodology.md`](../lessons/20-load-testing-methodology.md)
- Bottleneck deep-dive: [`issues/20-connection-pool-exhaustion-under-vt.md`](../issues/20-connection-pool-exhaustion-under-vt.md)
- Dashboard: [`infra/observability/grafana/dashboards/load-test-overview.json`](../../infra/observability/grafana/dashboards/load-test-overview.json)
