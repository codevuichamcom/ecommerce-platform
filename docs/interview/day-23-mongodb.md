# Interview — Day 23: MongoDB (event store + flexible attributes)

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: NexaShop (nối tiếp Day 22) — ecommerce mid-size, vừa lên ES search, traffic tăng, team Growth muốn data-driven.
- **Role giao việc**: Anh Khải (Engineering Manager) — "Team Growth cần report **top sản phẩm + conversion funnel** mỗi ngày. Event hành vi đang trôi trong Kafka, mất sau retention 7 ngày. Cần nơi lưu event lâu dài + query linh hoạt mà KHÔNG đụng DB transactional của order/product."
- **Bạn**: Tech Lead backend — own quyết định storage cho analytics + cách trị flexible attributes của catalog.
- **Reviewer**: Anh Khải soi: "Sao không nhét vào Postgres cho gọn? Mongo có làm hệ thống phức tạp thêm không? Lúc nào nó hỏng thì sao?"
- **Deadline**: 1 sprint, demo aggregation report chạy thật trên Mongo container.
- **Constraint thực tế**: KHÔNG đụng source of truth order/product · event schema đa hình theo type · phải auto-expire để Mongo không phình vô hạn · analytics down KHÔNG được block order path.
- **Definition of Done**: analytics-service consume ≥2 event type · lưu Mongo document đa hình · ≥1 aggregation pipeline ra report · TTL + compound index verify trên container thật · defend được "vì sao Mongo, không phải Postgres".

---

## Q1 — Khi nào chọn Mongo thay vì Postgres?

**Strong answer:**
> "Tôi không nghĩ 'thay' — tôi nghĩ **polyglot, mỗi kho một việc**. Mongo khi
> data **schema đa hình + append-heavy + không invariant cross-row + cần
> TTL/scale ngang**: event store, activity log, flexible attributes. Postgres
> khi cần **ACID multi-row + invariant chặt + join**: order, inventory, payment.
>
> Ở NexaShop, analytics event store dùng Mongo: 3 event type (`product_viewed`,
> `cart_updated`, `order_placed`) mỗi cái payload khác shape, TTL 90 ngày, query
> chủ yếu là aggregation funnel. Bốn đặc tính đó trùng khít document store.
> Nhưng product với `price`/`sku` invariant thì **Postgres giữ source of truth**
> — Mongo chỉ là read-model derived."

**Follow-up trap: "Mongo nhanh hơn Postgres chứ?"**
> "Sai câu hỏi — không có 'nhanh hơn' chung. Mongo nhanh hơn cho single-document
> read/write theo khoá + write append scale ngang. Postgres nhanh hơn cho join +
> aggregate có B-tree index + transaction. Tốc độ theo access pattern, không theo nhãn."

---

## Q2 — Embed vs Reference, quyết định bằng gì?

**Strong answer:**
> "Theo **access pattern**, không theo ERD. Embed khi: đọc-cùng-nhau + bounded +
> 1-to-few — 1 read không join. Reference khi: shared / unbounded / 1-to-many —
> tránh trùng lặp + tránh document phình.
>
> Quy tắc: 1-to-few → embed (địa chỉ trong user); 1-to-many → reference (product
> trong order); 1-to-squillions → reference ngược (đừng nhồi triệu log vào host,
> document limit 16MB). Ở Day 23 tôi embed `attributes` vào product catalog
> document (bounded, đọc kèm), nhưng order-items thì Postgres giữ vì cần ACID."

**Follow-up trap: "Embed thì update brand name phải sửa mọi doc?"**
> "Đúng — embed = chấp nhận update fan-out. Nếu field đổi thường xuyên + shared
> rộng → reference thay vì embed. Trade-off đọc-nhanh vs update-rẻ."

---

## Q3 — Mongo có transaction không? Trade-off?

**Strong answer:**
> "**Single-document write là atomic** luôn. **Multi-document transaction** chỉ
> có từ 4.0 và **bắt buộc replica set** — single-node standalone không có. Trade-off
> khi dùng multi-doc txn: cần replica set (ops nặng), txn giữ lock + chậm hơn
> single-doc nhiều, không nên lạm dụng.
>
> Cách tôi né: **align aggregate boundary = document boundary**. Thứ cần atomic
> nằm trong 1 document (embed). Thứ cần ACID multi-entity (order+items+total) tôi
> để Postgres. Day 23 KHÔNG cần multi-doc txn vì design đã loại bỏ nhu cầu —
> không phải bật replica set để cứu design sai."

**Follow-up trap: "Test bạn pass mà bảo standalone không có txn?"**
> "Vì `MongoDBContainer` của Testcontainers khởi tạo single-node **replica set**
> → txn có trong test. Còn docker-compose dev là **standalone** → không có. Tôi
> ghi rõ khác biệt này để không false-confidence: test pass ≠ dev có txn."

---

## Q4 — TTL index hoạt động thế nào? Độ trễ?

**Strong answer:**
> "TTL index là single-field index trên field kiểu **Date** với `expireAfterSeconds`.
> Mongo có **background thread chạy ~60s/lần** quét + xoá document quá hạn. Nên
> nó **KHÔNG real-time** — document có thể sống thêm tới ~60s sau mốc hết hạn.
> Report phải chịu được điều này, đừng assume xoá tức thì.
>
> Cạm bẫy: field phải là Date thật (tôi set `occurredAt` là Instant → BSON Date,
> `uuid-representation=standard`). Nếu lưu epoch long thì TTL không chạy. Ở Day 23
> tôi tạo TTL index tường minh trong `MongoIndexConfig` (tắt auto-index-creation)
> để kiểm soát rõ, verify bằng integration test check `expireAfter` present."

**Follow-up trap: "TTL có giải phóng disk ngay không?"**
> "Không hẳn — xoá document đánh dấu free space tái dùng, nhưng trả disk về OS
> cần `compact`. TTL chống phình logic, không tự shrink file vật lý."

---

## Q5 — Vì sao flexible attributes để Postgres JSONB chứ không Mongo làm source of truth?

**Strong answer:**
> "Vì product có **invariant cần ACID**: `price ≥ 0`, `sku` unique, status
> transition. Đó là sân Postgres. Nếu để Mongo own attributes làm source of truth,
> tôi hoặc mất ACID (single-node), hoặc gánh replica-set txn cho data vốn có chỗ
> tốt hơn.
>
> Nên: **Postgres JSONB giữ truth**, Mongo là **read-model derived** — sync 1
> chiều qua **cùng event `product.upserted`** đã nuôi ES (Day 22). 1 event fan-out
> 2 derived store: ES cho search, Mongo cho catalog detail + attribute filter.
> Đây là anti-cargo-cult: Mongo không thay Postgres, mỗi kho một việc."

**Follow-up trap: "Vậy Postgres JSONB và Mongo trùng chức năng?"**
> "Có overlap, nhưng khác mục đích: JSONB là source of truth (ACID, write path);
> Mongo là read-model tối ưu cho attribute-based filter + scale đọc ngang + tách
> khỏi OLTP. Day 24 tôi sẽ làm decision matrix rõ ranh giới này. Nếu volume nhỏ,
> bỏ Mongo, JSONB là đủ — tôi thừa nhận đây là chỗ đáng review ở Day 25."

---

## 🤖 AI Playbook

- **AI làm tốt**: generate boilerplate Spring Data Mongo (repository, `@Document`
  mapping, aggregation pipeline DSL skeleton), TTL/compound index config, mapping
  event → document. Giao phần lặp này cho AI.
- **Prompt mẫu**:
  > "Viết Spring Data Mongo aggregation: match type=X và occurredAt≥from, group
  > theo productId đếm count, sort desc, limit N, project _id→productKey. Dùng
  > MongoTemplate, không derived query."
- **Risk khi để AI làm**: (1) AI hay đề xuất nhét MỌI thứ vào Mongo / Mongo làm
  primary cho data invariant; (2) quên TTL không real-time → code assume xoá tức
  thì; (3) sinh `$lookup` join nặng thay vì embed; (4) tưởng standalone có txn.
- **Cách validate**: chạy aggregation trên **container thật** (không mock) +
  `explain()` xem index dùng chưa (tránh COLLSCAN) + integration test verify TTL
  index `expireAfter` present + verify single-document-write design (không multi-doc).

---

## 👥 Tech Lead Lens (Day 23 — decision day)

- **Trade-off chính + scale 10x**: thêm Mongo = +1 storage (ops/backup/skill) đổi
  lấy document model đúng việc cho event + attributes. **Scale 10x**: event volume
  tăng → TTL + compound index không đủ → shard theo `occurredAt`/`userId`, hoặc
  chuyển ClickHouse cho OLAP columnar; aggregation realtime nặng → pre-aggregate
  rollup (materialized) thay vì query live. Đánh giá ngưỡng ở Day 25.
- **Production failure mode + triage 5 bước**: *Mongo down → report 5xx, NHƯNG
  order path KHÔNG ảnh hưởng (analytics async, beacon fire-and-forget)*. Triage:
  (1) check Mongo container/replica health; (2) check connection pool exhaust ở
  app log; (3) xác nhận order/product OLTP vẫn xanh (degrade đúng phạm vi); (4)
  nếu drift Postgres→Mongo: chạy reconcile như ES; (5) check TTL có xoá nhầm window
  report không (sai `expireAfterSeconds`).
- **Junior + AI dễ sai 2 chỗ**: (1) **multi-document write tưởng atomic** → order
  mồ côi item (issue 23) — review kỹ method nào `save()` ≥2 collection; (2)
  **thiếu/sai TTL** → Mongo phình vô hạn hoặc xoá nhầm data trong window — review
  field TTL có phải Date + `expireAfterSeconds` đúng đơn vị.

---

## 🔗 Related

- [Lesson 23](../lessons/23-mongodb-when-to-use.md) · [Lesson 23b](../lessons/23b-document-vs-relational-modeling.md) · [Issue 23](../issues/23-mongodb-no-transaction-trap.md) · [ADR-011](../decisions/011-mongo-for-analytics-and-flexible-attributes.md)
- Evolution: [ch.23 — Cuốn sổ không dòng kẻ](../evolution/23-cuon-so-khong-dong-ke.md)
- Tiền đề: [Day 22 ES](day-22-elasticsearch.md) · [lesson 22b CDC-vs-app-sync](../lessons/22b-cdc-vs-app-sync-vs-debezium.md)
