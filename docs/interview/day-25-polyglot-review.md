# Interview — Day 25: Polyglot persistence review (data ownership & anti-patterns)

> **Status**: ✅ Done · Day 25 (chốt Week 4)
> Drill chính: defend một hệ **4 storage** trước architecture review — chứng minh nó là polyglot
> *có kỷ luật* chứ không phải *poly-mess*: ai owns gì, sync chiều nào, đo drift bằng gì, store nào
> chết thì sập tới đâu.

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: NexaShop — scale-up ecommerce VN, vừa kết thúc "data-layer initiative" (thêm ES + Mongo trong 1 sprint Week 4).
- **Role giao việc**: Anh Khải — Principal Architect, lo 4 storage mới thêm sẽ thành "polyglot gone wrong" sau 6 tháng (mỗi service một DB, không ai biết source of truth, on-call ngập).
- **Bạn**: Tech Lead data-layer — đứng trước **Architecture Review Board** trình bày **data ownership map** + defend tại sao 4 storage là *có chủ ý*.
- **Reviewer**: Anh Khải soi 3 thứ — (1) có **một** source of truth rõ ràng không, (2) dual-write ở đâu + rủi ro gì, (3) storage nào down thì sập cả hệ.
- **Deadline**: 1 buổi — output 1 trang ownership map + anti-pattern checklist để onboard người mới.
- **Constraint thực tế**: KHÔNG thêm storage mới; sống chung eventual consistency đã có; on-call team 6 người không nuôi nổi 4 cluster full-HA.
- **Definition of Done**: ownership map mỗi store có owner + sync edge + consistency window; anti-pattern checklist ≥6 mục; failure-mode mỗi store có degrade behavior.

---

## Q1 — "Hệ anh có Postgres + Redis + Mongo + ES. Cho tôi xem ai là source of truth cho cái gì."

**Strong answer:**
> Em có [data-ownership-map](../architecture/data-ownership-map.md) chia 3 nhóm rõ ràng:
> - **Source of truth (Postgres)**: order, payment, stock, product-core, user — mọi data có invariant/tiền/quan hệ.
> - **Derived view (eventual)**: ES (search index), Mongo (catalog read-model), Redis (cache L2) — chép từ Postgres qua Kafka, chỉ phục vụ đọc.
> - **Đặc biệt**: **Redis-cart là primary** — không có bảng Postgres backing, ghi gốc luôn ở Redis. Và **Mongo-analytics là sink** — truth của nó là chính event stream, không chép từ bảng Postgres nào.
>
> Nguyên tắc: một mẩu data đúng **một** owner. Postgres giữ truth, mọi store khác chép lại — không nhân bản quyền ghi gốc.

**🪤 Follow-up trap: "Redis vừa là primary (cart) vừa là derived (cache) — không mâu thuẫn à?"**
> Không — đó là **một công nghệ đóng hai vai**. Cart trên Redis là primary vì không có Postgres backing
> (ghi gốc ở Redis, TTL 7d). Cache L2 trên Redis là derived (cache-aside từ Postgres). Phải tách vai,
> vì blast radius khác hẳn khi Redis chết: cart mất luôn, cache thì chỉ degrade.

---

## Q2 — "Postgres → ES, Postgres → Mongo. Sao không ghi thẳng cả 2 nơi cho đồng bộ ngay?"

**Strong answer:**
> Đó chính là **dual-write** — anti-pattern số 1. `save(postgres); esClient.index();` không atomic: crash
> giữa 2 dòng → Postgres có, ES không → drift vĩnh viễn, không ai biết. Không có transaction xuyên 2 hệ
> thống khác nhau.
>
> Cách em làm: ghi gốc **một nơi** (Postgres), lan ra qua **một** kênh async:
> - order: **outbox** (`OutboxRelay` `@Scheduled` + `SKIP LOCKED`, ghi outbox cùng tx business — Day 13).
> - product: **afterCommit publish** → Kafka `product.upserted` → fan-out 2 consumer group (`-indexer` cho ES, `-catalog` cho Mongo, Day 22-23).
>
> Đổi lại: data lan ra **eventual** — có window. Nhưng window đo được + reconcile được, còn dual-write thì lệch im lặng.

**🪤 Follow-up trap: "afterCommit vẫn có thể mất event nếu crash sau commit trước publish mà?"**
> Đúng — đó là lý do order dùng **outbox** chứ không afterCommit: outbox ghi cùng tx nên không mất.
> Product dùng afterCommit vì write rate thấp + có reindex bù; em **thừa nhận** trade-off này và đo drift.
> Ngưỡng đảo chiều: nếu mất event product không chấp nhận được → nâng product lên outbox như order.

---

## Q3 — "Eventual consistency window — anh đo bằng gì? Đừng nói 'chắc là nhanh'."

**Strong answer:**
> Đo bằng số, không bằng cảm giác:
> - **Consumer lag** Kafka (Postgres→derived): độ trễ thực tế event chờ consume, ~1-2s bình thường.
> - **Drift reconcile**: `GET /admin/search/drift` so id-set Postgres ↔ ES; lệch bao nhiêu doc.
> - **Reindex** từ source of truth khi cần chữa: `POST /admin/search/reindex` (Day 22).
>
> Và window **acceptable tùy use case**: search lệch 1-2s = OK (không ai chết vì sản phẩm xuất hiện chậm 2s);
> nhưng order/stock thì **không eventual** — đó là lý do chúng ở Postgres ACID, không phải derived.

**🪤 Follow-up trap: "Window bao lâu thì gọi là sự cố?"**
> Khi nó vượt SLA của use case **và không tự co lại** (consumer lag tăng đơn điệu = pipeline kẹt, không
> phải eventual bình thường). Lúc đó triage: lag ở consumer nào, có vào DLT không, reconcile từ Postgres.
> Drift *trong* window là tính năng; drift *ngoài* window và *im lặng* mới là bug.

---

## Q4 — "Storage nào down thì sập cả hệ? Vẽ cho tôi failure mode."

**Strong answer:**
> Chỉ **Postgres** down = hard fail, vì nó là source of truth — không có bản sao để serve order/payment/stock.
> Đó là lý do duy nhất Postgres cần HA thật (Multi-AZ RDS). Các store khác đều degrade graceful:
> - **ES down** → search fallback Postgres GIN, header `X-Search-Source=postgres` (Day 22).
> - **Mongo catalog down** → đọc product-core thẳng Postgres, mất filter `attributes.<key>` tạm thời.
> - **Mongo analytics down** → event đọng ở Kafka (offset không tiến), replay khi lên — checkout không ảnh hưởng.
> - **Redis cache down** → cache miss rớt thẳng Postgres (chậm hơn, đúng).
> - **Redis cart down** → cart mất (primary, không fallback) — nhưng checkout path khác, hệ vẫn đứng.
>
> Triết lý: **derived store chấp nhận degrade thay vì HA đắt**; chỉ truth-store đầu tư HA. Team 6 người
> không nuôi nổi 4 cluster full-HA — và cũng không cần.

**🪤 Follow-up trap: "Nếu derived store down lâu mà bạn lỡ để app hard-depend vào nó thì sao?"**
> Thì em đã biến derived thành dependency cứng = anti-pattern. Test để chống: chủ động tắt ES/Mongo trong
> staging, verify fallback chạy. Nếu tắt ES mà cả site 500 → thiết kế sai, phải sửa cho degrade. "Derived
> store chết = degrade, không phải outage" là **tiêu chí thiết kế**, không phải may rủi.

---

## Q5 — "4 storage cho team 6 người — đáng cái ops cost không, hay anh đang over-engineer?"

**Strong answer:**
> Em không thêm store vì hype — mỗi cái trả lời được "access pattern nào khác?":
> Postgres (ACID money), ES (relevance/fuzzy/facet), Redis (ephemeral TTL), Mongo (schemaless ghi-nhiều-aggregate).
> Gộp lại thì store còn lại phải làm việc nó dở. Decision matrix [lesson 24](../lessons/24-sql-vs-nosql-vs-es-decision-matrix.md) chứng minh từng ô.
>
> Ops cost em **giảm bằng phân loại**: chỉ Postgres cần HA thật; derived store chấp nhận degrade. Sync đi
> qua một kênh chuẩn (Kafka outbox/event), không phải N pipeline tự chế. Nếu mai team không gánh nổi, ngưỡng
> đảo chiều rõ: gộp catalog về Postgres JSONB, hoặc bỏ ES quay về GIN cho tới khi volume biện minh lại.

**🪤 Follow-up trap: "Vậy lúc nào anh đồng ý là over-engineer thật?"**
> Khi một store **không** trả được câu "access pattern nào khác mà store hiện có làm dở" — ví dụ thêm
> Cassandra chỉ để "có thêm NoSQL". Đó là cargo-cult ([issue 24](../issues/24-cargo-cult-storage-migration.md)):
> gánh +1 failure mode + 1 sync drift mà không được gì. Ranh giới over-engineer là *access pattern đo được*,
> không phải số lượng store.

---

## 🧠 Senior mindset notes

- **Polyglot hỏng không vì *nhiều* store, mà vì thiếu kỷ luật**: một source of truth + không dual-write + đo/reconcile được. Đếm được 3 cái này là đếm được ranh giới disciplined ↔ chaos.
- **Một công nghệ có thể đóng nhiều vai** (Redis = cart-primary + cache-derived). Phân loại theo **vai**, không theo công nghệ — vì degrade behavior bám vào vai.
- **Derived store phải được thiết kế để chết được** (degrade), không phải để luôn sống (HA đắt). Nếu tắt nó làm sập site → bạn đã hard-depend nhầm.
- **"1 service 1 DB" là về ownership boundary, không phải technology diversity.** Junior hay đọc thành "mỗi service một loại DB" → ép thêm store vô nghĩa.

---

## 🤖 AI Playbook

- **AI làm tốt**: generate khung bảng ownership matrix (data / owner / derived / sync / window) + Mermaid diagram skeleton từ list service-storage; draft anti-pattern checklist; gom CV bullet.
- **Prompt mẫu**:
  > "Cho 4 storage [Postgres=truth order/payment/stock/product; Redis=cart-primary + cache-derived; Mongo=analytics-sink + catalog-derived; ES=search-derived], sync qua Kafka outbox/event. Sinh bảng owner/derived?/sync-mechanism/consistency-window + Mermaid graph LR có label edge."
- **Risk**: AI dễ gán **Redis-cart = cache/derived** (sai — cart là primary, không Postgres backing) và **Mongo-analytics = derived của một bảng** (sai — nó là sink, truth = event stream); còn bịa window bằng số đẹp ("50ms").
- **Validate**: đối chiếu code thật — cart-service KHÔNG có Postgres cart table (Redis primary); analytics-service ingest từ Kafka + beacon, không chép bảng nào (sink); product catalog có Postgres source (derived). Số window phải khớp cơ chế (afterCommit + consumer lag ~1-2s), không bịa.

---

## 🔗 Related

- Architecture: [data-ownership-map](../architecture/data-ownership-map.md) (output chính của day) · [system-overview](../architecture/system-overview.md)
- Lesson: [25 — Polyglot anti-patterns](../lessons/25-polyglot-persistence-anti-patterns.md) · [24 — Decision matrix](../lessons/24-sql-vs-nosql-vs-es-decision-matrix.md) · [24b — CAP/PACELC](../lessons/24b-cap-pacelc-in-practice.md)
- Issue: [22 — ES/Postgres sync drift](../issues/22-es-postgres-sync-drift.md) · [24 — Cargo-cult migration](../issues/24-cargo-cult-storage-migration.md)
- Interview: [day-22 — Elasticsearch](day-22-elasticsearch.md) · [day-23 — MongoDB](day-23-mongodb.md) · [day-24 — Storage decisions](day-24-storage-decisions.md)
- CV: [week-04 — CV bullets](week-04-cv-bullets.md)
</content>
