# Lesson 25 — Polyglot persistence: làm đúng & 6 anti-pattern

> **TL;DR**: Polyglot persistence = dùng nhiều loại storage, mỗi loại cho access pattern nó giỏi.
> Nó **đúng** khi: (1) có **một** source of truth rõ ràng, (2) mọi store khác là derived view sync
> qua **một** kênh async đo được, (3) mỗi store thêm vào trả được lời cho câu "access pattern nào
> khác?". Nó thành **poly-mess** khi thiếu bất kỳ điều nào — và thường hỏng theo 6 kiểu dưới đây.
>
> Đây là lesson **chốt Week 4**: Day 22 thêm ES, Day 23 thêm Mongo, Day 24 dựng decision matrix
> (chọn cái nào), Day 25 là *vận hành* 4 cái đó cho không loạn. Đọc kèm
> [data-ownership-map](../architecture/data-ownership-map.md).

---

## 🎯 Khi nào polyglot ĐÚNG

- Có ≥2 access pattern **thật sự khác bản chất**, đo được, mà một store làm tốt thì store kia làm dở.
  Repo này: ACID money (Postgres) · full-text relevance (ES) · ephemeral TTL (Redis) · schemaless ghi-nhiều-đọc-aggregate (Mongo).
- Bạn chỉ rời source of truth cho **derived view** — không nhân bản quyền ghi gốc.
- Team **gánh nổi** ops cost: mỗi store = backup + monitor + on-call + sync pipeline. Team 6 người không nuôi nổi 4 cluster full-HA → derived store chấp nhận degrade thay vì HA đắt.

## 🚫 Khi nào KHÔNG (chọn single-store / Postgres-only)

- Access pattern chưa đo được sự khác biệt — mới "nghe nói nhanh hơn".
- Data có invariant chặt + cross-entity transaction (order/payment/stock) → đừng rời ACID.
- Postgres còn dư địa: JSONB + GIN cho schemaless nhẹ, tsvector cho search vài trăm nghìn row, table cho cache nhỏ. Vắt kiệt Postgres trước khi thêm hệ mới.
- Volume/throughput chưa chạm ngưỡng mà ngưỡng đó mới biện minh được store mới.

---

## ⚠️ 6 anti-pattern "polyglot gone wrong"

### 1. 🔴 Dual-write (ghi thẳng 2 nơi, không atomic)
**Triệu chứng**: code `save(postgres); esClient.index();` — crash giữa 2 dòng → 2 hệ lệch vĩnh viễn, không ai biết.
**Vì sao chết**: không có transaction xuyên 2 hệ thống khác nhau. Đây là root của hầu hết drift.
**Cách đúng**: outbox (order, Day 13) hoặc afterCommit publish (product, Day 22) → Kafka → derived consume. Ghi gốc 1 nơi, lan ra qua **một** kênh async.

### 2. 🔴 Không có source of truth rõ ràng
**Triệu chứng**: hỏi "data X gốc ở đâu?" → mỗi người chỉ một store. ES sửa tay, Mongo sửa tay, không ai biết cái nào đúng.
**Vì sao chết**: reconcile bất khả thi — không biết chép từ đâu sang đâu.
**Cách đúng**: [data-ownership-map](../architecture/data-ownership-map.md) — mỗi mẩu data đúng 1 owner, viết ra giấy, dán tường.

### 3. 🟠 Derived store bị dùng làm primary
**Triệu chứng**: viết thẳng vào ES/Mongo read-model, hoặc đọc-sau-ghi từ ES rồi ngạc nhiên vì miss (refresh ~1s).
**Vì sao chết**: derived store no-ACID + eventual; biến nó thành nơi ghi gốc = mất luôn correctness.
**Cách đúng**: ES/catalog **chỉ đọc**; mọi ghi vào Postgres trước. ES chết → fallback Postgres GIN chứng minh nó không giữ truth.

### 4. 🟠 Sync drift im lặng (không đo window, không reconcile)
**Triệu chứng**: "ES với Postgres chắc khớp" — không metric, không reconcile job. 3 tháng sau search thiếu sản phẩm, không ai hay.
**Vì sao chết**: eventual consistency không phải "rồi sẽ khớp", mà là "lệch trong một window — bạn phải đo nó".
**Cách đúng**: mọi derived edge có (a) đo lag/window, (b) reconcile được (`/admin/search/drift` + `/admin/search/reindex`, Day 22), (c) alert trên drift.

### 5. 🟡 "Một service một database" hiểu giáo điều
**Triệu chứng**: ép mỗi microservice phải có một *loại* DB riêng cho "đúng chuẩn", thêm Mongo/Cassandra chỉ để service trông "hiện đại".
**Vì sao chết**: nhầm **ownership boundary** (service khác không query thẳng DB mình — *đúng*) với **technology diversity** (mỗi service một loại DB — *không bắt buộc*). Nhiều service chia chung Postgres (DB-per-service logic, không phải engine-per-service) là hoàn toàn ổn.
**Cách đúng**: rule thật là *"không service nào truy vấn DB của service khác"* → Feign/Kafka. Loại storage chọn theo access pattern, không theo "mỗi đứa một kiểu cho đẹp".

### 6. 🟡 Ops sprawl (thêm store không tính chi phí vận hành)
**Triệu chứng**: 4 cluster, mỗi cái cần backup/monitor/upgrade/on-call; team 6 người ngập; reconcile job mọc khắp nơi.
**Vì sao chết**: "đúng tool mỗi việc" có giá ẩn = ops. Bỏ qua giá đó → đúng về lý thuyết, chết về vận hành.
**Cách đúng**: tính ops vào quyết định. Derived store chấp nhận **degrade thay vì HA** (ES/Mongo down → fallback, không cần multi-AZ đắt). Chỉ truth-store (Postgres) đầu tư HA thật.

---

## ⚖️ Approaches compared — kiến trúc storage tổng thể

| Approach | Pros | Cons |
| --- | --- | --- |
| **Single-store (Postgres-only)** | 1 source of truth, ACID toàn bộ, 0 sync drift, ops rẻ nhất | search/scale/schemaless đuối ở ngưỡng cao; nhồi mọi access pattern vào 1 engine |
| **Polyglot-disciplined** (repo này) | mỗi access pattern đúng tool; truth tập trung Postgres; derived sync 1 kênh đo được | +1 failure mode + 1 sync edge mỗi store; cần kỷ luật ownership + reconcile |
| **Polyglot-chaos** (gone wrong) | (ảo giác) "linh hoạt, hiện đại" | dual-write khắp nơi, không source of truth, drift im lặng, ops ngập — nợ kỹ thuật phức lãi |

**Chọn**: **Polyglot-disciplined** — nhưng *kỷ luật* mới là phần khó, không phải việc thêm store.
Ranh giới disciplined ↔ chaos chính là 6 anti-pattern trên: vi phạm 1 cái là bắt đầu trượt sang chaos.

---

## 🎤 Trả lời phỏng vấn

**"Hệ anh có 4 storage — làm sao tránh nó thành mớ hỗn độn?"**
> Ba kỷ luật: (1) **một source of truth** — Postgres giữ mọi data có invariant/tiền; ES/Mongo/Redis-cache
> đều là derived view, em viết ra data-ownership-map để không ai nhầm. (2) **Không dual-write** — mọi sync
> Postgres→derived đi qua một kênh async (outbox/event), không ghi thẳng 2 nơi. (3) **Đo + reconcile** —
> mỗi derived store có drift metric + reindex endpoint + alert. Polyglot hỏng không phải vì *nhiều* store,
> mà vì thiếu 1 trong 3 kỷ luật đó.

**🪤 Follow-up: "1 microservice 1 database — anh có theo không?"**
> Theo đúng nghĩa **ownership**: không service nào query DB service khác, cross-service đi Feign/Kafka.
> KHÔNG theo nghĩa giáo điều "mỗi service một *loại* DB" — nhiều service của em chia engine Postgres,
> chọn loại storage theo access pattern chứ không phải để "trông microservice". Thêm Mongo/ES là vì
> đo được access pattern khác, không phải vì mỗi service phải có DB riêng.

**🪤 Follow-up: "ES với Postgres lệch nhau thì sao?"**
> Eventual consistency có window — em đo bằng consumer lag, và có `/admin/search/drift` so id-set +
> `/admin/search/reindex` để reconcile từ source of truth là Postgres. Drift là *được phép trong window*,
> không phải lỗi — miễn là đo được và reconcile được. Im lặng không đo mới là anti-pattern.

---

## 🔗 Related

- Architecture: [data-ownership-map](../architecture/data-ownership-map.md) (bản đồ owner + failure-mode) · [system-overview](../architecture/system-overview.md)
- Lesson: [24 — Decision matrix](24-sql-vs-nosql-vs-es-decision-matrix.md) (chọn store nào) · [24b — CAP/PACELC](24b-cap-pacelc-in-practice.md) (mọi derived là EL) · [13 — Outbox](13-outbox-pattern.md) · [13b — Dual-write problem](13b-dual-write-problem.md)
- Issue: [22 — ES/Postgres sync drift](../issues/22-es-postgres-sync-drift.md) · [24 — Cargo-cult migration](../issues/24-cargo-cult-storage-migration.md)
- Interview: [day-25 — Polyglot review](../interview/day-25-polyglot-review.md)
- Evolution: [ch.25 — Tấm bản đồ chủ quyền](../evolution/25-tam-ban-do-chu-quyen.md)
</content>
