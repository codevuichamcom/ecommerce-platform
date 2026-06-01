# 📘 Lesson 20 — Load testing methodology (open vs closed, coordinated omission)

> **TL;DR**: Load test không phải "bắn nhiều request xem có sập không". Nó là
> thí nghiệm có **giả thuyết** (system chịu được X RPS với P99 < Y) và **mô
> hình tải đúng** (open model) để con số không nói dối. Sai mô hình → đo ra
> số đẹp nhưng prod vẫn cháy.

---

## 🎯 Khi nào dùng

- Trước campaign / sự kiện traffic spike (flash sale 6/6) — cần biết **knee
  point**: RPS mà P99 bắt đầu vỡ SLO.
- Sau optimization (cache Day 15, index Day 16, VT Day 19) — chứng minh bằng
  số before/after, không chém "thấy nhanh hơn".
- Capacity planning: bao nhiêu instance cho mục tiêu RPS (gắn Little's Law).
- Regression gate trong CI: threshold k6 fail → chặn merge.

## 🚫 Khi nào KHÔNG dùng

- Đo micro-benchmark 1 method (lock, serialize) → dùng **JMH** (Day 19,
  `concurrency-lab`), không phải k6. k6 đo end-to-end qua mạng.
- Tìm correctness bug → đó là việc của integration test, không phải load test.
- "Đo cho có KPI" mà không có SLO mục tiêu → đồ thị để ngắm, vô dụng.

## 🪤 Cạm bẫy

### 1. Closed model giấu tail latency (coordinated omission)

**Closed model**: N user ảo (VU), mỗi VU gửi 1 request, **chờ response**, rồi
gửi tiếp. Vấn đề: khi server chậm, VU cũng chậm gửi → tải tự động giảm → bạn
**không bao giờ** thấy điều gì xảy ra khi request dồn tới lúc hệ thống quá tải.
Đây là **coordinated omission** (Gil Tene): response chậm "ăn mất" các request
lẽ ra phải gửi, percentile bị tô hồng.

```
Closed (ramping-vus):   server chậm → VU chờ → ÍT request hơn → P99 đẹp giả
Open   (arrival-rate):  server chậm → request VẪN tới đúng rate → dồn ứ → P99 thật
```

> ⚠️ Junior + AI mặc định viết `vus: 100, duration: 5m` (closed). Senior viết
> `ramping-arrival-rate` (open) vì prod là open: user bấm "Đặt hàng" KHÔNG đợi
> request trước của người khác xong.

### 2. Percentile ≠ average

P99 = 500ms nghĩa là **1/100 request** chậm ≥ 500ms. Ở 1M request/ngày = 10K
user mỗi ngày bị chậm. Average che giấu điều này (vài request 5s bị 999 request
20ms kéo xuống average 25ms). **Senior không bao giờ report average cho latency.**

> 💡 Percentile **không cộng được**: P99 của chuỗi 3 service KHÔNG bằng tổng 3
> P99. Tail của cả chain tệ hơn — phải đo end-to-end.

### 3. Không warmup → cold start thổi phồng P99

JVM JIT chưa compile hot path, Hikari pool chưa mở connection, cache trống. 30s
đầu chậm gấp nhiều lần. Phải warmup (xem `run-load-test.sh`) hoặc cắt cửa sổ đo
khỏi giai đoạn ramp.

### 4. Load generator chính là bottleneck

k6 log `insufficient VUs` = k6 không kịp sinh request, không phải server chậm.
Số đo lúc đó vô nghĩa. Tăng `preAllocatedVUs` / `maxVUs`, hoặc chạy k6 distributed.

### 5. Đo trên môi trường không giống prod rồi extrapolate tuyến tính

Local Docker 1 node ≠ prod 10 node sau load balancer + connection pool khác +
network latency khác. Số local là **relative comparison** (vt vs platform),
KHÔNG phải prod capacity.

---

## ⚖️ Approaches compared — công cụ load test

| Tool | Ưu | Nhược |
| --- | --- | --- |
| **k6** (chosen) | Script JS, open model first-class, threshold gate, output Prometheus/JSON, nhẹ (Go) | Không phải JVM (không share code với app); UI phải cắm Grafana |
| JMeter | GUI, ecosystem cũ rộng | XML nặng, closed model mặc định, tốn RAM/thread (1 thread/VU) |
| Gatling | Scala DSL, report HTML đẹp, open model tốt | Phải biết Scala; CI tích hợp rườm hơn |
| wrk / wrk2 | C, throughput cực cao, wrk2 fix coordinated omission | Chỉ HTTP đơn giản, không script flow nhiều bước |

**Chọn k6**: open model (`arrival-rate`) là default-citizen, threshold = exit
code (CI gate), output Prometheus cắm thẳng Grafana — khớp stack Day 20.

---

## 🎤 Trả lời phỏng vấn

**Q: "Anh đo capacity hệ thống thế nào?"**

> Em đặt SLO trước (vd P99 < 500ms, error < 1%), rồi dùng k6 **open model**
> (`ramping-arrival-rate`) ramp RPS lên tới khi threshold vỡ — đó là knee
> point. Quan trọng là open model chứ không closed, vì closed model bị
> **coordinated omission** giấu tail latency: khi server chậm, VU chờ nên tải
> tự giảm, P99 đẹp giả. Em report P95 **và** P99 chứ không average, và đối
> chiếu số k6 (client-side) với Prometheus `http_server_requests` (server-side)
> để chắc không lệch. Số local chỉ là relative — prod capacity phải đo trên
> infra thật nhiều node.

---

## 🔗 Related

- Code: [`load/k6/place-order.js`](../../load/k6/place-order.js) · [`load/README.md`](../../load/README.md)
- [`performance/20-load-test-report-template.md`](../performance/20-load-test-report-template.md) — template điền số
- [`performance/20b-vt-vs-platform-thread-bench.md`](../performance/20b-vt-vs-platform-thread-bench.md) — VT vs platform
- [`issues/20-connection-pool-exhaustion-under-vt.md`](../issues/20-connection-pool-exhaustion-under-vt.md) — bottleneck dời chỗ
- Day 19 micro-bench: [`concurrency-lab`](../../concurrency-lab) (JMH, khác tầng đo)
