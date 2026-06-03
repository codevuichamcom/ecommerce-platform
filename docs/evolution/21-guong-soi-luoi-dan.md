# Chương 21 · 🪞 Gương soi và lưới đan

**Day 21 — Review performance + interview round (Week 3 mock)**

---

> *"Bạn không sợ gương. Bạn sợ cái gương chỉ ra nếp nhăn ở khoé mắt mà hôm qua bạn chưa thấy. Gương không tạo ra sự thật — nó chỉ không cho bạn trốn. Và phỏng vấn là ngày người khác cầm cái gương đó, giơ thẳng vào mặt bạn."*

---

## 📍 Nơi ở trong câu chuyện

Tuần trước, ông huấn luyện viên k6 chất tạ lên cho tới khi có thứ gãy — và thứ
gãy không phải cơ bắp (CPU, thread) mà là cái gân yếu nhất: connection pool
(ch.20). Anh Khải — EM, ex-Tiki — đứng bên xem số nhảy, gật gù: *"Được. Lần sau
đừng để tao phải chỉ."*

Hôm nay — Day 21, khép lại Week 3 — không tập nữa. Hôm nay là **kiểm tra**. Code
có sạch không. Tôi có thật sự hiểu thứ mình vừa build không. Và tôi có **nói ra
được** cho cả team nghe không.

Hai thứ tôi cần. Một cái **gương** 🪞 — soi vào code sáu ngày qua, chỉ ra thứ tôi
không muốn nhìn. Tấm gương này không lạ: cuối Week 2 nó đã soi tôi một lần (ch.14).
Nhưng lần này nó có bạn đồng hành — một tấm **lưới** 🕸️, chuỗi câu hỏi của Khải,
đan dần quanh tôi tới khi không còn chỗ nào để nói "đại khái".

---

## 🪞 Gương: nhìn thẳng vào code mình viết

Năm phút. Tôi lật lại sáu ngày của tuần — cache 2-tier (15), GIN index (16), N+1
(17), keyset (18), distributed lock (19), load test (20) — và bắn **23 finding**
vào từng dòng. Không soi để dằn vặt. Soi để **hiểu**: chỗ nào đúng, chỗ nào là
nợ, chỗ nào sẽ quay lại cắn.

Gương chia ba màu.

### 🔴 Sáu vết đỏ — bug logic / rủi ro production

Đây là loại làm tôi muốn đóng laptop đi pha cà phê:

- **[RED-15] Metadata phình không cắt** (Day 15) — `fetchMetadata` là một
  `ConcurrentHashMap` lớn lên mãi. Cache churn cao (key xoay liên tục) → vài
  nghìn ghost entry → memory leak chậm. *Code vẫn chạy* — nhưng "chạy" không có
  nghĩa là "đúng".
- **[RED-16] Thiếu `CONCURRENTLY`** (Day 16) — Flyway không chạy được
  `CREATE INDEX CONCURRENTLY` (cần tx riêng). Bảng 1M+ product, GIN index mất
  ~30s → **khoá bảng**. Sáng mai giờ cao điểm: "write timeout", người đang
  checkout treo cứng. Đây là cái duy nhất ở mức *sẽ gây incident*.
- **[RED-17] Đường EAGER còn sót** (Day 17) — `DebugController` chứng minh fix
  N+1 bốn tầng rất đẹp, nhưng `OrderController.listMyOrders()` vẫn xài entity
  EAGER cũ. Demo xanh, **prod vẫn N+1**. Loại bug review dễ trượt nhất: chỗ đã
  sửa thì nhìn, chỗ đang chảy traffic thật thì quên.
- **[RED-18] Cursor không ký** (Day 18) — opaque token không có checksum. Client
  tự chế base64, `decode()` vẫn nuốt → con trỏ giả, rows bị nhảy/lặp phi xác
  định. Không phải lỗ hổng đánh cắp dữ liệu — tệ hơn theo nghĩa khác: **không thể
  debug**.
- **[RED-19] Fence token race** (Day 19) — `SET NX` rồi `INCR` là hai lệnh rời.
  Nếu `INCR` fail → token = 0, caller không biết, cầm token rỗng đi ghi → fence
  check ở DB bị bypass → snapshot nhân đôi. Đúng con quái của ch.19, nhưng ở tầng
  code khởi tạo lock.
- **[RED-20] k6 thiếu VU** (Day 20) — 100 VU cho 200 req/s. Nếu latency thật là
  500ms (không phải 200ms như ước), chính k6 nghẽn trước → con số đo được là **đồ
  giả**. Thổi kèn chiến thắng trong một trận mình chưa thật sự đánh.

Cảm giác sau khi soi xong sáu vết này không phải *"mình kém"*. Mà là *"mình làm
đúng 80%, còn 20% kia — biết rồi, ghi vào sổ, sửa tuần sau."*

### 🟡 Tám nếp vàng — design debt, giả định yếu

Loại này code vẫn chạy, cây cầu vẫn đi qua được — chỉ là sợi cáp đã chớm gỉ:

- **XFetch reset metadata** (Day 15) — `put()` reset `fetchDurationMs` → mất tín
  hiệu cost fetch → đôi khi double-refresh gần TTL.
- **Partial index lệch** (Day 16) — `WHERE status='ACTIVE'`; admin query mọi
  status không khớp index → full scan dashboard.
- **COUNT thừa** (Day 17) — projection trả `Page` → Spring tự sinh query COUNT
  thứ hai dù không cần total.
- **Keyset sort hardcode** (Day 18) — SQL sort cố định, index phải khớp y hệt; ai
  đổi sort mà quên reindex → latency tăng bí ẩn, mò chết.
- **Cursor mất micro-precision** (Day 18) — encode có thể rụng phần dưới
  microsecond → hai row tạo cùng micro-giây, tie-break keyset có thể lệch. Hiếm,
  nhưng là edge case có thật.
- **Metadata theo pod** (Day 19) — fetch metadata chết theo pod; restart pod =
  reset = XFetch lạnh máy một lúc (cluster vẫn ấm nhờ pod khác).
- **k6 không đo read** (Day 20) — load test chỉ place-order (write), thiếu hẳn
  "user xem lại đơn" → không có P95 read-after-write.
- **k6 buildTokenPool không concurrent** (Day 20) — pool setup tuần tự → méo
  latency vòng lặp đầu.

Cảm giác? Như uống cà phê lúc 10 giờ tối: lúc này thấy ổn, nhưng biết thừa 1 giờ
sáng sẽ hối.

### 🟢 Chín dấu xanh — chỗ làm đúng

Gương không toàn vết. Có chín chỗ xứng đáng để yên:

- Polymorphic deserialize whitelist chặt (Day 15)
- L2-evict-trước-L1 có giải thích, không "cứ thế mà làm" (Day 15)
- `ANALYZE` ngay sau tạo index — nhỏ mà cứu planner (Day 16)
- Projection DTO bằng constructor expression, không load full entity (Day 17)
- Cursor base64 URL-safe đúng chuẩn (Day 18)
- Xử lý lỗi tử tế khi `decode()` base64 hỏng (Day 18)
- Lua release script chặn race nhả nhầm token (Day 19)
- Fence token monotonic — đúng mô hình correctness của Kleppmann (Day 19)
- Open-model load test, né coordinated omission (Day 20)

Nhìn chín cái này tôi mới thở ra: ừ, tuần này không chỉ toàn nợ.

---

## 🕸️ Lưới đan: bị Khải vây hỏi

Gương xong, tới lưới. Khải ngồi xuống, mười câu — năm câu design, năm câu tình
huống production. Mỗi câu là một sợi: nó không cho tôi nói "hơi hơi", "chắc là",
"đại khái". Trả lời xong, anh kéo sợi tiếp theo siết lại bằng một câu follow-up.

### Năm sợi design

1. **Offset vs keyset — khi nào cái nào?** Jump-to-page thì offset; infinite
   scroll thì keyset (tie-break bằng id để đảm bảo thứ tự). Hệ này hybrid.
   - *Sợi siết*: "Keyset luôn tốt hơn?" — Sai. Cần nhảy trang số thì offset đơn
     giản hơn; và keyset bắt buộc sort cố định.
2. **Cache 2-tier — sao phải phức tạp?** L1 50ns vs L2 1ms = 20×; L1 ăn ~80%
   traffic, tiết kiệm RTT. KHÔNG dùng cho thứ cần strict consistency (order
   status, inventory).
   - *Sợi siết*: "Một Redis là đủ nhanh rồi?" — 1ms vs 50ns vẫn là 20×; ở SLA
     99.99% nó khác nhau thật.
3. **Optimistic vs pessimistic lock — contention cao chọn gì?** Contention thấp
   → optimistic; contention cao → pessimistic (serialize). 100 luồng cùng giành
   stock=50 thì optimistic + retry vẫn nhỉnh hơn nếu retry rẻ.
   - *Sợi siết*: "Optimistic luôn tốt vì reader không block?" — Sai nếu chi phí
     retry cao.
4. **Open vs closed load model — cái nào lộ P99 thật?** Open: bơm request theo
   rate cố định → latency phình → đau thật. Closed: app chậm thì VU tự chậm theo
   → giấu tail (coordinated omission).
   - *Sợi siết*: "Chạy load test là đủ?" — Sai nếu chạy closed: cảm giác an toàn
     giả.
5. **Ma trận chọn storage** — B-tree / GIN trigram / tsvector / ES, khi nào cái
   nào? Cân latency / vận hành / chi phí / consistency. GIN cho v1 (0 hạ tầng),
   ES cho v2 (sync batch = eventual consistency).
   - *Sợi siết*: "ES luôn nhanh hơn?" — Đúng về số (15ms vs 45ms) nhưng sync
     async = lag + tốn vận hành, overkill cho catalog v1.

### Năm sợi tình huống

6. **Flash sale P99 vọt** — traffic ×10, P99 nhảy 50ms → 800ms. Triage 5 bước:
   (1) CPU/mem bão hoà? (2) DB chậm / Hikari pending? (3) GC pause? (4) bổ trace
   OTel xem span nào ăn giờ? (5) k6 baseline còn đúng? Giả thuyết: pool 30 +
   latency 20ms ≈ 0.6 connection/đơn vị; 2000 req/s cần ~40 → nâng pool 60,
   retest.
7. **Hit cache 50% mà P95 vẫn 30ms** — số không khớp vì phải nhìn phân bố tail:
   L1 hit 50ns, L2 hit 1ms, L2 miss + load 50ms. P50 = 50ns, P95 = 50ms (2.5%
   chạm DB). "Hit rate 50%" chỉ là trung bình.
   - *Sợi siết*: hỏi ngược "là P95 hay P50?" — Metric phải tách percentile, đừng
     nói "trung bình".
8. **Keyset edge case** — vài user chèn dữ liệu giữa lúc tôi đang ở trang 1→2,
   rows xen kẽ. Bug không? Không — đúng kỳ vọng. Muốn snapshot nhất quán thì bound
   theo version_id, đánh đổi consistency lấy chi phí. Hệ này append-mostly → chấp
   nhận.
9. **Network partition + lock** — lock hết hạn, kẻ thứ hai giành được → chạy đôi.
   Fencing token cứu: kẻ sau token cao hơn, DB từ chối token cũ (Kleppmann). Phải
   verify chỗ check `fence_version` thật sự nằm trong code, không phải "giả định
   có".
10. **VT vs platform ở prod** — VT thắng ở load test, nhưng prod là platform
    legacy. Có lợi không? Audit `synchronized`: lib nào pin VT thì lợi ích bay
    sạch. Đo bằng JFR `jdk.VirtualThreadPinned`, rồi upgrade lib hoặc cô lập pool.

Cách Khải hỏi không phải để tôi đáp "đây là pattern A" rồi cho qua. Anh hỏi để ép
tôi **nghĩ thành tiếng**:

- "Pool 10 thay vì 100 thì sao?"
- "User logout giữa lúc scroll keyset, cursor cũ còn tính được không?"
- "Index V6 bị drop, keyset query chạy thế nào?"
- "XFetch reset metadata — hệ quả là gì?"

Mỗi câu là một chỗ tôi buộc phải lộ ra: mối nối nào mình nối ẩu.

---

## 📊 Scorecard Day 21

```
┌─────────────────────────────────────────────────┐
│ 🎯 WEEK 3 — TỔNG KẾT                            │
├─────────────────────────────────────────────────┤
│ ✅ Cache 2-tier ........... 20× latency         │
│ ✅ GIN index .............. 57× faster search   │
│ ✅ Keyset pagination ...... O(1) vs O(n) offset │
│ ✅ Distributed lock + fence  partition-safe     │
│ ✅ Load test open-model ... lộ P99 thật         │
├─────────────────────────────────────────────────┤
│ 🔴 6 đỏ ..... bug/rủi ro — sửa ngay tuần sau    │
│ 🟡 8 vàng ... design debt — refactor sớm        │
│ 🟢 9 xanh ... pattern chắc — giữ nguyên         │
├─────────────────────────────────────────────────┤
│ Phỏng vấn: 9 strong · 1 borderline · 0 fail     │
│ Confidence: 8.5 / 10                            │
├─────────────────────────────────────────────────┤
│ Vibe: "Tuần này hiểu gì, tôi nói rõ được nấy"   │
└─────────────────────────────────────────────────┘
```

Cái gương cho tôi thấy gì sau cùng:
- ✅ Tôi hiểu lý thuyết — offset/keyset trade-off, fencing token, Kleppmann.
- ✅ Tôi đọc được trace — Hikari pending, GC pause, span latency.
- ✅ Tôi **dám nhận nợ** — [RED-16] CONCURRENTLY, [RED-17] N+1 còn sót.
- ✅ Tôi có kế hoạch sửa — ghi thẳng vào README đầu tuần sau.

Chỗ gương soi chưa rõ: VT pinning edge case — cần JFR verify thật mới chắc. Đúng
câu borderline Q10.

---

## 🎬 Kết thúc ngày 21

Tối, tôi ghi một dòng vào ROADMAP:

```
Day 21 ✅ Done · 2026-06-01
  - Review: 23 finding (6 đỏ, 8 vàng, 9 xanh)
  - Interview: 10 Q&A — 9 strong, 1 borderline
  - CV bullet: 4× latency, 10× throughput
  - Confidence: 8.5/10 — sẵn sàng Week 4 (data layer)
```

Khải đóng buổi bằng một câu, không khen suông:

> *"Tuần này tao thấy mày không chỉ **chạy được code**, mà **biết tại sao nó
> chạy**. Đó mới là khác biệt senior với junior. Week 4: Elasticsearch, rồi
> MongoDB, rồi cái decision matrix. Đừng cuống — mày biết cách đo rồi. Đo, rồi
> mới quyết."*

Gương soi xong, lưới gỡ ra. Hôm nay tôi nhìn thấy sự thật về code của mình. Ngày
mai, tôi phải ngồi với người khác và **nói về** sự thật đó — bình tĩnh, có số,
không trốn. Đó mới là việc của một người dẫn team.

---

*→ Tuần tới đổi vai: hết phỏng vấn, sang dựng **khung quyết định** cho tầng dữ
liệu. Elasticsearch trước — khi khách gõ "iphon" thiếu chữ "e" mà vẫn phải ra
iPhone, ông kế toán Postgres chịu thua chỗ này. Cần một người mới: ông thầy bói.*
