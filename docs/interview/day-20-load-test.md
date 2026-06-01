# 🎤 Interview — Day 20: Load testing & capacity

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: NexaShop — startup Series-A, vừa qua Day 19 hardening concurrency, sắp chạy campaign 6/6.
- **Role giao việc**: Anh Khải (Engineering Manager, ex-Tiki) — "Trước campaign tao cần con số: chịu bao nhiêu RPS trước khi P99 vỡ, bottleneck ở đâu. Đừng nói 'chắc ổn'."
- **Bạn**: Tech Lead backend — own load-test harness + observability stack (Prometheus/Tempo/Grafana) + verdict capacity.
- **Reviewer**: Anh Khải soi 2 điểm — (1) percentile đo bằng **open model** hay closed (coordinated omission), (2) khẳng định "VT nhanh hơn" có số chứng minh không.
- **Deadline**: 1 sprint, demo Grafana dashboard + 1 trace timeline chỉ ra bottleneck.
- **Constraint**: chạy local Docker (số là relative, không claim prod), k6 reproducible, không sửa business logic để làm đẹp số.
- **Definition of Done**: k6 script + threshold gate · Grafana P50/95/99 + throughput · 1 trace timeline annotate bottleneck · bảng VT vs platform · report template tái dùng.

---

## Q1 — P95, P99 hay average? Vì sao?

**Strong answer**: Latency luôn report **percentile**, không average. Average bị
vài outlier 5s kéo lệch hoặc bị 999 request nhanh che giấu — không phản ánh trải
nghiệm ai cả. P95 = trải nghiệm số đông (5% chậm nhất). P99 = **tail**: 1/100
request. Ở 1M request/ngày, P99 = 500ms nghĩa là **10.000 user/ngày** chịu ≥500ms.
Em report P95 **và** P99 vì chúng kể chuyện khác nhau: P95 ổn mà P99 nổ = có
contention/GC pause/pool nghẽn ở đuôi.

> **Follow-up trap**: *"P99 cộng được qua nhiều service không?"* → **Không**.
> P99 của chain 3 service KHÔNG bằng tổng 3 P99 — xác suất 1 trong 3 hop chậm cao
> hơn từng cái, tail của cả chain tệ hơn. Phải đo end-to-end (trace), không cộng số.

## Q2 — Open model vs closed model? k6 của anh kiểu nào?

**Strong answer**: Em dùng **open model** (`ramping-arrival-rate`): bơm request
theo tốc độ cố định, không chờ response trước. Vì prod là open — user bấm "Đặt
hàng" không đợi request người khác xong. **Closed model** (`ramping-vus`, mỗi VU
loop tuần tự) có lỗi **coordinated omission**: khi server chậm, VU chờ nên tải tự
giảm → percentile đẹp giả, giấu đúng lúc hệ thống quá tải. Open model phơi bày
P99 thật vì khi server chậm, request vẫn dồn tới đúng rate → xếp hàng → tail nổ.

> **Follow-up trap**: *"Vậy P99 vọt lên khi quá tải là bug script à?"* → Không,
> đó là **đúng** — đang đo capacity. Bug là khi k6 log `insufficient VUs` (k6 thiếu
> VU để giữ rate) → lúc đó k6 mới là bottleneck, số bỏ.

## Q3 — Bật virtual thread thì app nhanh hơn bao nhiêu?

**Strong answer**: Câu này sai đề một chút — VT **không** làm từng request nhanh
hơn, nó tăng **số request song song**. Em đo 2 workload: read-heavy (browse) VT
thắng rõ vì platform cap 200 thread, request 201 xếp hàng; VT không cap → nuốt
1000+ concurrent. Write-heavy (place-order) thì **hòa**, vì cả hai bị chặn bởi
**connection pool 20**, không phải số thread — VT cho nhiều thread hơn nhưng tất
cả xếp hàng chờ connection. CPU-bound thì VT cũng không giúp (Day 19 JMH chứng
minh). Tóm lại: VT tăng throughput cho IO-bound concurrency cao, hòa khi bottleneck
ở tài nguyên bounded hoặc CPU.

> **Follow-up trap**: *"Sao bật VT mà P99 place-order lại TỆ hơn?"* → Bottleneck
> dời sang connection pool (issue 20): VT chấp nhận hàng ngàn request cùng chờ
> Hikari max=20, pending leo → P99 phình ở hàng đợi pool. Trace cho thấy span
> `Connection Acquisition` ăn 1.8s/2.1s, DB insert thật chỉ 4ms. Fix = size pool
> theo Little's Law + connection-timeout, không tăng pool mù.

## Q4 — Anh chỉ bottleneck bằng cách nào, không đoán?

**Strong answer**: 3 tín hiệu chéo. (1) **Grafana metric**: P99 cao mà
`process_cpu_usage` chỉ 35% → KHÔNG phải CPU-bound, là chờ tài nguyên. Nhìn
`hikaricp_connections_pending` leo cao → pool nghẽn. (2) **Tempo trace**: mở 1
trace `POST /orders` chậm, phân rã span — span nào ăn thời gian. Thấy
`Connection Acquisition` chiếm 85% tổng → bằng chứng, không đoán. (3) Đối chiếu
k6 client-side vs Prometheus server-side percentile — lệch lớn = overhead mạng/k6.

> **Follow-up trap**: *"Throughput phẳng mà CPU 40% nghĩ gì?"* → Hệ thống đang
> bị chặn bởi **tài nguyên bounded** (pool / lock / downstream rate limit / DB),
> không phải compute. Thêm instance app sẽ vô ích — phải gỡ tài nguyên nghẽn.

## Q5 — Tính connection pool size thế nào? (Little's Law)

**Strong answer**: `concurrency = throughput × latency` (Little's Law). Mục tiêu
2000 RPS, mỗi query DB ~20ms → connection cần `2000 × 0.02 = 40`. Nhưng KHÔNG
tăng vô hạn: trần là `Postgres max_connections / số instance`. 100 / 7 service ≈
14/service — nên hoặc tăng `max_connections` có kiểm soát, hoặc dùng PgBouncer
gom connection. Pool to hơn năng lực DB = **phản tác dụng**: connection storm,
context-switch DB tăng, chỉ dời bottleneck xuống DB. Senior chọn pool khớp DB +
fail-fast (`connection-timeout` ngắn + bulkhead) thay vì để dồn ứ.

> **Follow-up trap**: *"Pool to thì luôn tốt hơn chứ?"* → Không. Pool to quá =
> dời bottleneck xuống DB + tốn RAM (mỗi connection ~10MB phía Postgres) + DB
> context-switch. Có điểm tối ưu, không phải càng to càng nhanh.

---

## 🧠 Senior mindset notes

- **Đo trước, claim sau**: mọi câu "VT nhanh hơn / cache giúp X%" phải có số từ
  open-model load test + threshold gate. Không số = ý kiến.
- **Bottleneck dời, không biến mất**: gỡ thread pool → lộ connection pool → gỡ
  pool → lộ DB/lock. Luôn hỏi "bottleneck tiếp theo là gì".
- **Số local ≠ prod**: dùng để so tương đối (vt/platform, before/after), không
  extrapolate tuyến tính sang prod multi-node.

## 🤖 AI Playbook

- **AI làm tốt**: scaffold k6 boilerplate, Grafana dashboard JSON, Prometheus/Tempo
  YAML, docker-compose observability — pattern lặp, AI sinh nhanh & đúng cú pháp.
- **Prompt mẫu**: *"Generate a k6 script using `ramping-arrival-rate` (open model)
  for POST /orders with Bearer auth from setup(), thresholds p(95)<200 p(99)<500,
  http_req_failed<0.01, custom Trend for place-order latency."*
- **Risk**: AI mặc định viết **closed model** (`vus`/`duration`) → coordinated
  omission giấu tail; hay hard-code token thay vì `setup()`; Grafana JSON AI sinh
  thường sai `datasourceUid` → panel trống.
- **Validate**: kiểm `executor` = `ramping-arrival-rate` (không phải `ramping-vus`);
  chạy thật, đối chiếu RPS k6 vs Prometheus `http_server_requests`; mở 1 Tempo
  trace xác nhận span breakdown khớp giả thuyết bottleneck.

---

## 🔗 Related

- [`lessons/20-load-testing-methodology.md`](../lessons/20-load-testing-methodology.md)
- [`performance/20-load-test-report-template.md`](../performance/20-load-test-report-template.md) · [`20b-vt-vs-platform-thread-bench.md`](../performance/20b-vt-vs-platform-thread-bench.md)
- [`issues/20-connection-pool-exhaustion-under-vt.md`](../issues/20-connection-pool-exhaustion-under-vt.md)
- Day 19 concurrency interview: [`interview/day-19-concurrency.md`](day-19-concurrency.md)
