# Interview — Day 24: Storage decisions (SQL vs NoSQL vs ES)

> **Status**: ✅ Done · Day 24
> Drill chính: trả lời câu phỏng vấn classic *"khi nào dùng NoSQL?"* mà KHÔNG flounder,
> bằng bằng chứng từ chính repo (4 storage chạy thật).

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: NexaShop — ecommerce VN Series-A (~50 dev), đang scale từ monolith Postgres sang polyglot persistence.
- **Role giao việc**: Anh Khải — Principal Architect, chuẩn bị **kiến trúc review với CTO** tuần sau.
- **Bạn**: Tech Lead backend — vừa ship ES (Day 22) + Mongo (Day 23), giờ phải viết **"storage decision playbook"** làm chuẩn cho cả org để junior không cargo-cult.
- **Reviewer**: Anh Khải soi: *"em chọn Mongo cho analytics nhưng vẫn để attributes ở Postgres — defend đi"* và *"sao không Mongo luôn cho catalog?"*.
- **Deadline**: 1 ngày — output là 1 trang matrix + 2 lesson dùng được trong onboarding.
- **Constraint thực tế**: không refactor storage hiện tại (đã chốt ADR-010/011); chỉ document-hoá tiêu chí + chứng minh bằng quyết định đã có.
- **Definition of Done**: matrix 8×4 đầy đủ verdict + reasoning · 5-axis table · CAP/PACELC mapping 4 store · system diagram tô màu storage type · interview drill 5 Q&A.

---

## Q1 — "Khi nào em dùng NoSQL thay vì SQL?"

**Strong answer:**
> Không phải "khi data lớn" — đó là hiểu nhầm phổ biến nhất. Em chọn theo **access
> pattern + consistency requirement**. Mặc định của em là **Postgres**, vì nó cho ACID +
> quan hệ + JSONB free; em chỉ rời đi khi đo được một access pattern cụ thể mà relational
> làm dở:
> - Cần **full-text relevance / fuzzy / facet** → Elasticsearch (inverted index, BM25).
> - Cần **ephemeral + TTL + tốc độ** (cart, session, cache) → Redis (key-value).
> - Cần **schema đa hình + ghi nhiều để phân tích** → MongoDB (document + aggregation).
>
> Trong project em dùng cả 4, mỗi cái giải đúng 1 bài. Quan trọng: mọi thứ ngoài Postgres
> đều là **derived view**, Postgres vẫn là source of truth.

**🪤 Follow-up trap: "Nhưng Mongo nhanh hơn Postgres mà?"**
> Sai chiều so sánh. Mongo nhanh hơn ở *write-heavy schemaless single-doc*; Postgres nhanh
> hơn ở *quan hệ + join + transaction*. "Nhanh hơn" mà không nói access pattern thì vô nghĩa.
> Em đo bottleneck trước, không đổi storage vì một tính từ.

---

## Q2 — "Project em có Postgres + Redis + Mongo + ES. Defend từng cái. Sao không gộp lại cho đỡ vận hành?"

**Strong answer:**
> Mỗi store giải một access pattern khác hẳn:
> - **Postgres** — order/payment/stock: ≥3 invariant + concurrency + cần ACID. Source of truth.
> - **Redis** — cart/cache/session: ephemeral, cần TTL + tốc độ, mất không chết.
> - **Mongo** — analytics event store + catalog read-model: schemaless, ghi nhiều, aggregation, TTL 90d.
> - **ES** — product search: relevance + fuzzy + facet.
>
> Gộp lại thì storage còn lại phải làm việc nó dở: gộp về Postgres thì search ở scale lớn
> đuối + cache không giảm tải được chính nó; gộp về Mongo thì mất ACID cho money. Cái giá
> của polyglot em **thừa nhận**: mỗi store thêm = +1 failure mode + 1 sync drift. Nên em chỉ
> thêm khi access pattern thật sự khác — không vì CV.

**🪤 Follow-up trap: "Vậy lúc nào polyglot là sai?"**
> Khi thêm storage mà access pattern *không* khác — ví dụ thêm Mongo chỉ để "có NoSQL".
> Đó là cargo-cult: gánh ops + drift mà không được gì. Day 25 em review đúng cái bẫy này.

---

## Q3 — "CAP theorem — MongoDB là CP hay AP?"

**Strong answer:**
> Mặc định **CP** ở vế partition: write phải tới primary, partition thì secondary không
> nhận write. Nhưng câu hỏi thiếu vế — em trả lời bằng **PACELC**: Mongo là **PC/EL**.
> Tức là lúc *không* partition (99.9% thời gian), default nó ưu tiên **Latency** (đọc
> secondary nhanh, có thể stale). Em có thể kéo về **EC** bằng `writeConcern=majority` +
> `readConcern=majority` + đọc primary, nhưng **trả bằng latency**. Trong project, analytics
> để EL vì đếm xấp xỉ là đủ.

**🪤 Follow-up trap: "Postgres có dính CAP không?"**
> Single-node thì không có P để bàn. Nhưng thêm read replica để scale đọc là ngay lập tức
> có vế EL — đọc replica = stale. Lúc đó em quyết per-query: đọc-lại-order-vừa-tạo → primary
> (EC); list sản phẩm → replica (EL). PACELC, [lesson 24b](../lessons/24b-cap-pacelc-in-practice.md).

---

## Q4 — "Tại sao flexible product attributes em để Postgres JSONB chứ không Mongo? Mongo sinh ra cho cái đó mà?"

**Strong answer:** (đây là câu anh Khải sẽ đào sâu nhất)
> Em cân nhắc và chọn JSONB vì 3 lý do gắn với context project:
> 1. **Query attribute hiện tại đơn giản** — `attributes->>'screen_size'` + GIN index là đủ;
>    chưa cần aggregation pipeline phức tạp trên attribute.
> 2. **Giữ một source of truth** — product đã ở Postgres (có giá, có invariant). Đẩy attribute
>    sang Mongo = thêm một dual-write + một sync drift nữa, đúng cái đau em vừa xử ở ES (Day 22).
> 3. **Mongo vẫn có mặt** — nhưng là catalog **read-model** derived, không phải nơi ghi gốc.
>
> Ngưỡng đảo chiều: nếu attribute shape bùng nổ + cần query/aggregate phức tạp trên attribute,
> lúc đó Mongo làm primary cho catalog mới đáng. Hiện chưa tới ngưỡng → JSONB thắng.

**🪤 Follow-up trap: "Vậy khi nào JSONB không đủ, phải sang Mongo thật?"**
> Khi (a) shape đa hình rất cao + thay đổi liên tục khiến migration đau, (b) cần aggregation
> pipeline nặng trên chính các field schemaless, hoặc (c) write throughput trên attribute
> vượt sức một Postgres. Cả ba phải **đo được**, không phải cảm giác.

---

## Q5 — "Elasticsearch làm primary store luôn được không, đỡ một cái Postgres?"

**Strong answer:**
> Không. ES là **derived view**, không bao giờ primary, vì:
> - **No ACID** — không có transaction để bảo vệ money/invariant.
> - **Near-real-time refresh (~1s)** — đọc ngay sau ghi có thể miss; không chấp nhận được cho
>   read-your-write của order.
> - **Mapping cứng + reindex đau** — đổi schema = reindex toàn bộ.
>
> ES sinh ra để *bói chữ* (relevance, fuzzy, facet), Postgres giữ *sổ gốc*. Em sync
> Postgres → ES qua Kafka và đo **drift window**. Nếu ES chết, search fallback Postgres GIN
> (Day 22) — chứng tỏ ES không phải nơi giữ truth.

**🪤 Follow-up trap: "Thế đặt ngược: dùng Postgres luôn cho search, bỏ ES đi?"**
> Được tới một ngưỡng. GIN + tsvector đủ cho ~vài trăm nghìn row + truy vấn đơn giản. Vượt
> ngưỡng (relevance scoring phức tạp, fuzzy, facet đa chiều, scale) thì ES thắng rõ. Em có
> [benchmark Postgres vs ES](../performance/22-search-postgres-vs-es.md) để nói con số, không
> nói cảm tính.

---

## 🧠 Senior mindset notes

- **Chọn storage = chọn access pattern, không chọn tính từ.** "Nhanh", "linh hoạt", "scale"
  đều vô nghĩa nếu không gắn access pattern + số đo.
- **Mọi derived store là EL** (PACELC) — sync async = hy sinh consistency lấy latency, và
  bạn phải đo cái window. Đây là bản chất polyglot (Day 25).
- **Mỗi storage thêm vào = +1 failure mode + 1 sync edge.** "Đúng tool" có giá. Polyglot chỉ
  đáng khi access pattern thật sự khác nhau.
- **Ngưỡng đảo chiều** là chữ ký của senior: mọi verdict matrix đi kèm "khi nào quyết định này
  đổi" (volume, throughput, query complexity).

---

## 🤖 AI Playbook

- **AI làm tốt**: generate skeleton của decision matrix + bảng CAP/PACELC + so sánh axis —
  việc tổng hợp kiến thức phổ biến AI rất nhanh.
- **Prompt mẫu**:
  > "Tạo bảng so sánh Postgres/Redis/Mongo/ES trên 5 axis: consistency model, schema
  > flexibility, query capability, scaling, ops cost. Mỗi ô 1 dòng, không marketing fluff."
- **Risk**: AI bịa verdict **generic** copy từ blog ("Mongo = flexible, SQL = ACID") không
  khớp quyết định thật của repo — vô giá trị khi interviewer đào "trong *project em* thì sao?".
  AI cũng hay nói "Mongo là AP" (sai — Mongo CP).
- **Validate**: grep ADR-004/010/011 + đối chiếu **từng ô** matrix với code/quyết định đã có;
  mọi verdict phải trỏ được vào một file/ADR/Day cụ thể.

---

## 👥 Tech Lead Lens

- **Trade-off chính + scale 10x**: polyglot đổi "đúng tool mỗi việc" lấy "ops phức tạp + sync
  drift". Scale 10x: app-level sync (Day 22) → **Debezium CDC**; Postgres order → **shard theo
  `customer_id`** (giữ ACID trong shard) chứ không bỏ ACID sang NoSQL; ES/Mongo replica tăng theo.
- **Production failure mode "storage sprawl" + 5-step triage**: triệu chứng = data lệch giữa các
  store, reconcile job mọc khắp nơi. Triage: (1) xác định ai là **source of truth** cho data đang
  lệch; (2) đo **drift window** (lag Postgres→derived); (3) check sync pipeline (Kafka consumer lag,
  DLT); (4) reconcile từ source of truth (reindex/replay — Day 22 `/admin/search/reindex`); (5) đặt
  alert trên drift để không tái phát thầm lặng.
- **Junior + AI dễ sai 2 chỗ**: (1) viết matrix **generic** không gắn access pattern thật → soi:
  bắt mỗi verdict trỏ vào ADR/Day cụ thể; (2) nói "Mongo/NoSQL = AP, scale tốt hơn SQL" — sai cả
  CAP (Mongo là CP) lẫn logic (CAP không map 1-1 SQL/NoSQL) → soi kỹ phần CAP/PACELC.

---

## 🔗 Related

- Lesson: [24 — Decision matrix](../lessons/24-sql-vs-nosql-vs-es-decision-matrix.md) · [24b — CAP/PACELC](../lessons/24b-cap-pacelc-in-practice.md)
- Issue: [24 — Cargo-cult storage migration](../issues/24-cargo-cult-storage-migration.md)
- Interview: [day-22 — Elasticsearch](day-22-elasticsearch.md) · [day-23 — MongoDB](day-23-mongodb.md)
- Architecture: [system-overview](../architecture/system-overview.md)
</content>
