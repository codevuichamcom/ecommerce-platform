# Chương 20 · 🏋️ Ông huấn luyện viên tàn nhẫn

**Day 20 — Load testing (k6 + Grafana + OTel trace timeline)**

---

> *"Mày khoẻ tới đâu không tính bằng lúc thong thả. Tính bằng cái rep cuối — lúc tay run, mặt đỏ, và một thứ trong người gãy trước những thứ khác. Tao ở đây để tìm cái thứ đó."*

---

## Bối cảnh

Ngày 19 khép lại bằng một lời hứa: lock đã đúng, virtual thread không pin, fan-out
gọn gàng. Nhưng tất cả mới chỉ chạy trên **máy một người** — một anh kỹ sư gõ
`curl`, gật gù "ổn áp". Ổn áp là cảm giác. Cảm giác không lên được slide trước
campaign 6/6.

Anh Khải — EM, ex-Tiki — quăng cho tôi một câu lạnh tanh: *"Tao không cần mày nói
'chắc ổn'. Tao cần con số. Chịu bao nhiêu RPS thì P99 vỡ? Và lúc nó vỡ, cái gì
gãy trước?"*

Thế là tôi thuê một ông huấn luyện viên. Tên ổng là **k6**. Ổng không quan tâm hệ
thống của tôi đẹp cỡ nào trên giấy. Ổng chỉ làm một việc: **chất tạ lên, tăng dần,
cho tới khi có thứ gì đó gãy** — rồi chỉ tay vào đúng cái cơ yếu nhất và cười khẩy.

## 🏋️ Cú lừa của closed model: bài tập mày tự chọn nhịp

Ông HLV nghiệp dư sẽ bảo: *"Làm 100 cái, xong cái này tới cái kia."* Đó là
**closed model** — `ramping-vus`. Vấn đề? Khi mày mỏi, mày **tự chậm lại**. Mày
nghỉ giữa rep. Và thế là bài kiểm tra **giấu** mất điểm yếu — vì mày không bao giờ
bị ép tới ngưỡng dồn dập.

Trong tải thật, không ai đợi mày. User bấm "Đặt hàng" lúc 20h00 flash sale **không
hỏi** request của người bên cạnh đã xong chưa. Tạ cứ rơi xuống đúng nhịp — mày đỡ
không kịp thì nó **chồng đống** lên người.

Đó là **open model** — `ramping-arrival-rate`. Và đây là chỗ tôi bắt ông HLV ký
hợp đồng:

```javascript
scenarios: {
  place_order: {
    executor: 'ramping-arrival-rate',   // tạ rơi theo NHỊP, không đợi mày
    startRate: 10,
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 500,
    stages: [
      { target: 50,  duration: '30s' },  // khởi động
      { target: 200, duration: '1m'  },  // chất tạ
      { target: 200, duration: '2m'  },  // GIỮ — cửa sổ đo thật
      { target: 0,   duration: '30s' },  // hạ tạ
    ],
  },
},
```

> ⚠️ **Cạm bẫy #1 của AI/junior**: gõ `vus: 100, duration: '5m'` rồi khoe P99
> đẹp. Đó là **coordinated omission** (Gil Tene): server chậm → VU chờ → tải tự
> giảm → percentile bị tô hồng. Senior viết `arrival-rate`. Vì prod là open.

Một chi tiết nhỏ mà cay: `preAllocatedVUs`. Nếu thiếu, k6 in ra `insufficient
VUs` — nghĩa là **chính ông HLV hụt hơi**, không phải học viên yếu. Số đo lúc đó?
Vứt. Như đo lực kéo bằng sợi dây thừng mục — đứt dây không phải vì mày khoẻ.

## 🎯 Đừng đo lúc thong thả: P99 là cái rep tệ nhất

Anh Khải soi đúng một chỗ: *"Báo cáo average hả? Về chỗ ngồi."*

Average là trung bình. Một thằng nâng 999 cái mượt, 1 cái suýt gãy lưng — average
vẫn "đẹp". Nhưng cái rep thứ 1000 ấy mới là thứ gửi mày vào bệnh viện.

**P99 = cái rep tệ nhất trong 100 cái.** Ở 1 triệu request/ngày, P99 = 500ms nghĩa
là **10.000 user mỗi ngày** ngồi nhìn spinner ≥ nửa giây. Nên ngưỡng của tôi —
viết thẳng vào hợp đồng với ông HLV, để ổng **đá tôi ra khỏi phòng tập** (exit
code ≠ 0) nếu vi phạm:

```javascript
export const orderThresholds = {
  http_req_duration: ['p(95)<200', 'p(99)<500'],  // tail, không average
  http_req_failed:   ['rate<0.01'],                // error budget 1%
  checks:            ['rate>0.99'],                 // đặt hàng thật phải thành công
};
```

> 💡 **Phỏng vấn**: P99 **không cộng được** qua nhiều service. P99 của chuỗi 3 hop
> KHÔNG bằng tổng 3 cái P99 — xác suất *một trong ba* chậm cao hơn từng cái. Tail
> của cả chuỗi luôn tệ hơn. Phải đo end-to-end bằng trace, đừng cộng số học.

## 💥 Và rồi có thứ gãy: không phải cơ ngực, mà là cổ tay

Tôi bật virtual thread (ngày 19 đã chứng minh nó rẻ), nghĩ bụng: *nghìn thread ảo,
nuốt hết, P99 mượt như nhung.* Ông HLV chất tạ lên 200 req/s.

P99 từ **120ms vọt lên 2.1 giây.** Throughput **không nhúc nhích.**

Ủa? VT đâu? Tôi mở Grafana lên — và đây là lúc cái dashboard tôi dựng đêm qua trả
công. Panel CPU: **35%.** CPU đang *rảnh rỗi* mà hệ thống *lết*. Panel HikariCP:

```
hikaricp_connections_active   ▔▔▔▔▔▔▔▔  ghim cứng ở 20  (= max pool)
hikaricp_connections_pending  ▁▂▄▆█████  leo lên 150+   (xếp hàng chờ)
```

Cơ ngực (CPU, thread) khoẻ phây phây. Nhưng **cổ tay gãy.** Connection pool —
`maximum-pool-size: 20` — là cái gân yếu nhất trong người. 200 virtual thread lao
tới bước ghi DB, **20 đứa** chộp được connection, **180 đứa** đứng xếp hàng trước
cánh cửa Hikari.

Để chắc, tôi mở Tempo — sợi chỉ Ariadne của ngày 9 giờ thành **máy quay chậm** soi
từng động tác:

```
POST /orders  ──────────────────────────────────  2.1s
  ├─ Connection Acquisition (chờ Hikari) ████████  1.8s   ← THỦ PHẠM
  ├─ INSERT order + items                 ▏         4ms
  └─ outbox record (cùng tx)              ▏         3ms
```

1.8 giây **đứng chờ connection**. Việc thật chỉ 4ms. Như thằng lực sĩ nâng được
100kg nhưng phải xỏ tạ qua một cái lỗ khoá bé tí — sức không thiếu, **lối vào**
thiếu.

> 💡 **Insight xương sống của cả ngày 20**: Virtual thread **không** xoá bottleneck.
> Nó **dời** bottleneck — và làm nó **khó thấy hơn**. Platform thread cạn pool →
> thread dump lòi ra ngay. VT cạn connection → không dấu vết thread nào, phải nhìn
> `hikaricp_pending` + trace span mới bắt được. Quan sát khó hơn = nguy hiểm hơn.

## 🧮 Little's Law: ông HLV biết toán

Sửa thế nào? Bản năng junior: *"Tăng pool lên 500!"* — và đẩy quả bom xuống
Postgres (`max_connections=100`, 7 service chia nhau). Connection storm, RAM phía
DB phình, context-switch tăng. Dời cục nghẽn xuống tầng dưới, không xoá.

Ông HLV biết toán. **Little's Law**:

```
concurrency = throughput × latency
```

Mỗi tx place-order ~8ms, mục tiêu 200 req/s → cần `200 × 0.008 = 1.6` connection
ở steady. Pool 20 *thừa sức* — nếu DB nhanh. P99 nổ vì dưới contention tx phình
lên ~40ms → `200 × 0.04 = 8`, cộng burst + lock chờ → vượt 20. Lời giải không phải
"to vô hạn", mà là **khớp năng lực DB** + fail-fast:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30      # khớp max_connections / số instance, KHÔNG 500
      connection-timeout: 2000   # chờ 2s không có → ném lỗi, đừng treo 30s
```

> ⚠️ **Cạm bẫy #2 của AI/junior**: "pool to thì luôn nhanh hơn". Sai. Pool to quá
> = dời nghẽn xuống DB + tốn RAM (mỗi connection ~10MB phía Postgres). Có điểm tối
> ưu, không phải càng to càng tốt. Senior chọn **shed load có kiểm soát** (trả 429
> nhanh) hơn là degrade toàn cục.

## 🥊 VT vs Platform: lên đài đo bằng số

Anh Khải hỏi câu kinh điển: *"Vậy bật VT có nhanh hơn không?"* — Câu hỏi sai đề.
Tôi cho hai đứa lên đài, cùng một bài tập, đổi mỗi cái profile:

```yaml
# application-platform.yml — tắt VT, về Tomcat pool 200 cổ điển
spring:
  threads:
    virtual:
      enabled: false
server:
  tomcat:
    threads: { max: 200, min-spare: 20 }
    accept-count: 100   # hàng đợi OS khi cả 200 thread bận → vượt = từ chối
```

Kết quả — hai nửa của một sự thật:

```
┌─ Read-heavy (browse, cache-hot) ──────────────────────────────┐
│  VT        : nuốt 1000+ concurrent, P99 thấp     → THẮNG RÕ    │
│  Platform  : trần 200 thread, req 201 xếp hàng   → P99 vọt     │
└───────────────────────────────────────────────────────────────┘
┌─ Write-heavy (place-order) ───────────────────────────────────┐
│  VT        : ≈ hoà — cùng nghẽn ở pool 20                      │
│  Platform  : ≈ hoà — cùng nghẽn ở pool 20                      │
│  → Bottleneck ở GÂN (pool), không ở SỐ SỢI CƠ (thread)         │
└───────────────────────────────────────────────────────────────┘
```

VT không phải thuốc tiên. Nó cho mày **nhiều sợi cơ hơn** — tuyệt cho IO-bound
concurrency cao (read). Nhưng nếu cái **gân** (connection pool, CPU, lock) là điểm
yếu, thêm sợi cơ chỉ để chúng cùng nhau... đứng chờ. Gỡ gân ra (issue 20) thì cả
hai cùng khoẻ lên, và VT mới lại nhỉnh hơn.

> 💡 **Senior vs junior**: junior nói "bật VT cho nhanh". Senior nói "VT tăng
> throughput cho IO-bound concurrency cao; CPU-bound hoặc nghẽn tài nguyên bounded
> thì hoà — và **đo bằng số** chứ không assume". Ngày 19 đo ở tầng JMH micro, ngày
> 20 đo ở tầng end-to-end. Hai kính hiển vi, một kết luận.

## Kết thúc ngày 20

```
🏋️ Buổi tập kết thúc — biên bản của ông HLV:

├── 🎚️ k6 open model ........ ramping-arrival-rate (không coordinated omission)
├── 🚦 Threshold gate ....... p95<200 · p99<500 · err<1% → exit≠0 = chặn merge
├── 📊 Observability ........ Prometheus + Tempo (Zipkin receiver) + Grafana provisioned
├── 💥 Bottleneck ........... CPU 35% mà P99 2.1s → Hikari pending 150+ → pool nghẽn
├── 🔬 Trace timeline ....... Connection Acquisition ăn 1.8s/2.1s, DB thật 4ms
├── 🧮 Fix .................. Little's Law → pool 30 + connection-timeout 2s (không phải 500)
├── 🥊 VT vs Platform ....... read VT thắng rõ · write hoà (cùng nghẽn pool)
├── 🧪 Build ................ order-service + prometheus registry xanh, test pass
└── 📚 Docs ................. lesson 20 + issue 20 + perf 20/20b + interview + chương này

Vibe: "CPU rảnh mà hệ thống lết — thủ phạm luôn là cái gân yếu nhất, không phải cơ bắp to nhất."
```

> 💡 **Bài học xuyên ngày**: load test không phải "bắn nhiều xem có sập". Nó là
> thí nghiệm có **giả thuyết** (chịu X RPS, P99 < Y) + **mô hình đúng** (open) +
> **bằng chứng** (trace, không đoán). Và bottleneck thì không bao giờ biến mất —
> nó chỉ **dời chỗ**. Gỡ thread → lộ pool. Gỡ pool → lộ DB. Luôn hỏi: *cái gì gãy
> tiếp theo?*

---

*→ Chương sau: ông HLV đã chỉ ra điểm yếu, ta đã có số. Ngày 21 — cuối tuần 3 —
ta leo lên ghế nóng phỏng vấn, để một senior khác nhìn thẳng vào những con số ấy
và hỏi: "Em chắc chứ? Chứng minh đi." Tuần Performance khép lại không bằng code,
mà bằng việc **nói ra được** mọi thứ vừa làm.*
