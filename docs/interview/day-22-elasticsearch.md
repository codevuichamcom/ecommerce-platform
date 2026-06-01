# 🎤 Interview — Day 22: Elasticsearch product search

> **Day 22** · Tag: `search` `elasticsearch` `cdc` `interview`
> Liên quan: [lesson 22](../lessons/22-elasticsearch-basics.md) ·
> [lesson 22b](../lessons/22b-cdc-vs-app-sync-vs-debezium.md) ·
> [performance/22](../performance/22-search-postgres-vs-es.md) ·
> [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md) ·
> [issue 22](../issues/22-es-postgres-sync-drift.md)

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: **NexaShop** (Series A, ~2M DAU, TMĐT Việt Nam) — tiếp nối Week 3.
- **Role giao việc**: **Anh Khải** (Principal Engineer, reviewer các mock Week 3) —
  "GIN trigram nhanh nhưng substring match. Khách gõ 'iphon' sai 1 ký tự là mất đơn,
  không sort theo độ liên quan. Migrate search sang Elasticsearch — nhưng **KHÔNG
  được biến ES thành source of truth**, Postgres vẫn giữ data."
- **Bạn**: Tech Lead — own search migration + sync pipeline + benchmark để defend.
- **Reviewer**: Anh Khải soi 3 thứ — (1) sync có dual-write problem không, (2) ES down
  thì search degrade ra sao, (3) benchmark honest hay cherry-pick.
- **Deadline**: 1 sprint. Demo: search "iphon" ra iPhone, faceted filter, 1 slide
  benchmark LIKE vs ES.
- **Constraint thực tế**: ES single-node dev (no security); product-service đã có
  Kafka infra (common-lib) nhưng chưa wire; không làm vỡ 2-tier cache Day 15.
- **Definition of Done**: `/products/search` trả từ ES có score + highlight;
  create/update/archive → ES cập nhật < 2s; ES down → fallback Postgres LIKE; benchmark
  doc có số thật (Postgres) + harness reproducible (ES).

---

## 🎤 5 Q&A

### Q1 — Tại sao không dùng Postgres full-text (`tsvector`) mà thêm ES?

**Strong answer**: `tsvector` đã hơn `LIKE` (đánh index từ, có ranking cơ bản
`ts_rank`). Nhưng ES thắng ở 4 điểm với TMĐT: (1) **relevance tuning** sâu — BM25 +
field boosting (`name^3` > description), function score; (2) **fuzzy/typo** AUTO
(Levenshtein) — "iphon"→iPhone, `tsvector` không có; (3) **faceted aggregation**
real-time trong cùng query (count theo brand/category) — Postgres phải `GROUP BY`
riêng; (4) **scale search độc lập** khỏi OLTP. Trade-off: thêm 1 storage + sync
pipeline. Nếu search đơn giản volume thấp → `tsvector`/GIN đủ, ES là over-engineer.

> **Lưu ý**: latency KHÔNG phải lý do chính — GIN của tôi đã 45ms (Day 16). Lý do là
> **capability**, không phải tốc độ.

**Follow-up trap: "vậy sao không để ES làm luôn database?"** → ES không có ACID
transaction, không unique constraint cứng, near-real-time (refresh ~1s) → đọc-sau-ghi
stale, và reindex sai = mất data. ES là search index derived, không phải store.

---

### Q2 — `text` vs `keyword` khác gì? Khi nào dùng cái nào?

**Strong answer**: `text` = **analyzed** (tokenize + lowercase + tùy filter stemming)
→ dùng full-text match relevance (`name`, `description`). `keyword` = **không
analyze**, lưu nguyên chuỗi → dùng filter / aggregation / sort (`brand`, `categoryId`,
`status`). Field cần đếm/lọc phải keyword: để `brand` là text thì "Apple" bị analyze
thành token `apple`, aggregation ra bucket sai (tách "Apple Store" thành apple+store).
Field vừa search vừa sort → **multi-field**: `name` (text) + `name.keyword` (keyword).

**Follow-up trap: "analyzer index-time và query-time khác nhau được không?"** → Mặc
định cùng analyzer. Nếu cố tình khác (vd index có edge-ngram cho autocomplete, query
dùng standard) phải hiểu rõ — lệch vô ý là nguyên nhân "data có mà search miss".

---

### Q3 — Sync Postgres → ES thế nào? Dual-write problem giải sao?

**Strong answer**: 3 cách — (a) **app-level dual-write**: app ghi DB rồi publish Kafka
event (sau commit), đơn giản nhưng có drift khi publish fail; (b) **outbox + relay**:
ghi event vào bảng outbox cùng transaction DB → atomic, relay poll publish (tôi đã làm
cho order Day 13); (c) **Debezium CDC**: đọc WAL Postgres → stream change, app không
lo sync, ops nặng. Tôi chọn **(a) cho search** vì search là derived non-critical →
drift sửa bằng nightly reconcile (so count Postgres ACTIVE vs ES docs → reindex). Nếu
là data critical như order thì outbox. Volume lớn / nhiều nguồn ghi → Debezium.

**Follow-up trap: "publish trong hay ngoài transaction?"** → Sau commit
(`afterCommit`), không trong. Publish trong rồi rollback = bắn event phantom cho
product không tồn tại. afterCommit loại phantom, nhưng vẫn có thể mất event sau commit
(Kafka down) → đó là lý do cần reconcile.

**Follow-up trap: "sao không 2PC cho atomic?"** → XA chậm, ES không support XA, coupling
commit. Outbox mới là cách "atomic" đúng (chỉ 1 DB transaction).

---

### Q4 — ES down lúc 12h trưa flash sale, search xử lý sao?

**Strong answer**: **Graceful degradation** — `ProductSearchController` try ES, catch
`DataAccessException` (Spring Data ES dịch lỗi connection) → fallback `ProductService.search`
(Postgres GIN, Day 16), set header `X-Search-Source: postgres-fallback`. Search vẫn
trả kết quả (mất relevance/facet/highlight, nhưng còn dùng được). Đây chính là lý do
**giữ GIN index** thay vì xóa sau khi có ES. Search là read non-critical → KHÔNG được
500.

**Follow-up trap: "fallback Postgres scale nổi flash sale không?"** → Đó là rủi ro:
fallback gánh full traffic search có thể đè Postgres. Giảm thiểu: 2-tier cache (Day 15)
hấp thụ hot query, circuit breaker để không hammer ES đang chết, và alert để fix ES
nhanh. Fallback là "sống sót", không phải "thay thế dài hạn".

**Follow-up trap: "catch broad thế không phải anti-pattern à (trap [05])?"** → Khác:
trap [05] là consumer NUỐT exception rồi mất event. Đây là DEGRADE 1 read path
non-critical, có log WARN + header báo nguồn + fallback thật phục vụ user. Read degrade
≠ write swallow.

---

### Q5 — Eventual consistency window của search bao lâu, đo bằng gì, chấp nhận được không?

**Strong answer**: Window = Kafka publish + consumer lag + ES refresh interval (~1s).
Bình thường < 2s. Đo bằng: timestamp diff `occurredAt` (lúc product đổi) → lúc doc
visible trong ES; và **drift metric** `GET /admin/search/drift` (Postgres ACTIVE count
− ES doc count). Chấp nhận được vì search là **derived non-critical**: trễ vài giây
search ra product mới không sao. NHƯNG **checkout (giá, tồn kho) KHÔNG đọc từ ES** —
phải đọc Postgres source of truth, vì ở đó stale = bán nhầm giá = mất tiền.

**Follow-up trap: "khi nào window này không chấp nhận được?"** → Khi search trở thành
critical path (vd hiển thị tồn kho real-time để chốt flash sale). Lúc đó phải nâng sync
lên outbox/CDC + đọc số nhạy cảm từ source, không từ index.

---

## 🤖 AI Playbook

- **AI làm tốt / nên giao**: scaffold `ProductDocument` mapping (text/keyword), boilerplate
  `NativeQuery` (multi_match + aggregation + highlight), docker-compose ES config,
  Testcontainers `ElasticsearchContainer` setup, event record + indexer consumer.
- **Prompt mẫu** (≤4 dòng):
  > "Spring Data Elasticsearch 5.4 (ES client 8.15): build NativeQuery — multi_match
  > name^3+description, fuzziness AUTO, bool filter status/category, terms aggregation
  > brand+category, range price, highlight name+description. Parse SearchHits +
  > ElasticsearchAggregations sang DTO."
- **Risk**: AI hay (1) để field facet là `text` → sai bucket; (2) quên fallback khi ES
  down; (3) dùng API ES client cũ (7.x `QueryBuilders`) không khớp 8.15 tagged-union
  `RangeQuery.untyped()`; (4) generate dual-write mà không cảnh báo drift; (5) để ES
  thành source of truth.
- **Validate**: đọc kỹ mapping text/keyword; test fallback (kill ES → header
  `postgres-fallback`); chạy integration test thật trên ES container (đừng tin code
  "trông đúng" — API ES client đổi nhiều giữa version); verify facet count + archived
  không search ra; check `BigDecimal→double` không dùng để charge.

---

## 👥 Tech Lead Lens (Day 22 — decision day)

- **Trade-off chính**: thêm ES = capability search mạnh (relevance + fuzzy + facet)
  đổi lấy +1 storage ops + sync drift risk. **Scale 10x** (1M→10M docs): app-level
  sync không kham → chuyển **Debezium CDC** + ES cluster sharded (shard theo doc count,
  replica ≥1), tách indexer ra service riêng; đọc số nhạy cảm vẫn từ Postgres.
- **Production failure mode** (ES down / sync lag) — triage 5 bước: (1) `GET
  /_cluster/health` xem ES status (red/yellow/green); (2) check Kafka consumer lag topic
  `product.upserted` (consumer group `product-service-indexer`); (3) verify fallback
  đang serve (`X-Search-Source: postgres-fallback` + Postgres không quá tải); (4) đo
  drift `GET /admin/search/drift`; (5) drift lớn → `POST /admin/search/reindex` ép hội
  tụ.
- **Junior + AI 2 lỗi dễ nhất**: (1) **để ES làm source of truth** / quên Postgres vẫn
  own → reindex sai = mất data; (2) **dual-write không nhận ra drift** → search trả
  data cũ / sản phẩm đã xóa, không có metric để biết. Review kỹ: sync atomicity (key
  ordering + afterCommit), fallback path, text/keyword mapping, và có drift metric +
  reconcile chưa.

---

## 🔗 Related

- [lesson 22 — ES basics](../lessons/22-elasticsearch-basics.md) ·
  [lesson 22b — sync](../lessons/22b-cdc-vs-app-sync-vs-debezium.md)
- [performance/22](../performance/22-search-postgres-vs-es.md) · [issue 22](../issues/22-es-postgres-sync-drift.md)
- [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md)
- Code: [`ProductSearchService`](../../services/product-service/src/main/java/com/ecom/product/search/ProductSearchService.java) ·
  [`ProductSearchController`](../../services/product-service/src/main/java/com/ecom/product/web/ProductSearchController.java)
