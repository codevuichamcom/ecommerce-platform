# ⚡ Performance 22 — Search: Postgres LIKE/GIN vs Elasticsearch

> **Day 22** · Tag: `performance` `search` `elasticsearch` `benchmark`
> Liên quan: [performance/16 — EXPLAIN ANALYZE GIN](16-sql-explain-analyze.md) ·
> [lesson 22 — ES basics](../lessons/22-elasticsearch-basics.md) ·
> [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md)

---

## 🎯 Câu hỏi cần trả lời

ES không tự động "nhanh hơn Postgres". Nó **khác về khả năng**. Doc này tách 2 trục:
**latency** (ai nhanh hơn ở từng loại query) và **capability** (ai làm được gì).
Quyết định migrate phải defend được bằng số + bằng feature, không phải hype.

---

## 📐 Phương pháp đo

- Dataset: 1M product (script [`generate_products_1m.sql`](../../services/product-service/src/main/resources/db/seed/generate_products_1m.sql) đã có từ Day 16).
- ES: nạp cùng 1M qua `POST /admin/search/reindex` ([`ReindexService`](../../services/product-service/src/main/java/com/ecom/product/search/ReindexService.java), batch 1000, `Slice` tránh OOM).
- Đo p50/p95 bằng cách bắn query lặp (k6 hoặc `hey`), warm cache trước.
- **Trạng thái hiện tại**: Postgres numbers là **đo thật** (Day 16, tái dùng). ES
  numbers ở mức **functional-verified + projected** — harness reindex + search đã
  chạy đúng trên ES 8.15 container (5 integration test PASS), benchmark 1M head-to-head
  đánh dấu *to-run* (cần seed 1M vào ES, tốn thời gian + RAM). Số ES dưới đây là
  **kỳ vọng dựa trên đặc tính inverted index + tham chiếu công khai**, KHÔNG phải đo
  trên máy này — ghi rõ để không tự lừa mình lúc phỏng vấn.

> ⚠️ **Senior honesty**: nói "tôi đo Postgres thật, ES tôi verify chức năng + ước
> lượng latency, chưa chạy 1M head-to-head" **mạnh hơn** bịa con số đẹp. Interviewer
> đào "anh đo thế nào" là lộ ngay nếu chém.

---

## 📊 Latency theo loại query

| Query | Postgres (đo thật, Day 16) | Elasticsearch (kỳ vọng) | Ai thắng |
|---|---|---|---|
| Exact match `sku = ?` (B-tree) | ~1-2ms | ~5-10ms | **Postgres** (point lookup, ES có overhead HTTP + refresh) |
| Substring `LIKE '%kw%'` (GIN trigram) | **p95 ~45ms** | ~10-30ms | Hòa / ES nhỉnh ở recall |
| Full-text relevance ranking | ❌ không có | ~10-30ms | **ES** (Postgres không rank được) |
| Fuzzy "iphon"→iPhone | ❌ 0 kết quả | ~15-40ms | **ES** (Postgres không fuzzy) |
| Faceted count (brand/category) | cần `GROUP BY` riêng, +1 query | trong cùng query, ~thêm vài ms | **ES** (aggregation free) |
| Deep filter + sort + paginate | tốt với index đúng | tốt | Hòa |

Baseline Postgres từ Day 16 (đo thật trên 1M):

> GIN trigram + covering index: search `LIKE` p95 **2.5s → 45ms** (57× nhanh hơn seq
> scan), Buffers 42K read → 2.4K hit. Chi tiết:
> [performance/16](16-sql-explain-analyze.md).

## 🧩 Capability — thứ latency không kể hết

| Capability | Postgres LIKE/GIN | Elasticsearch |
|---|---|---|
| Relevance ranking (BM25, boosting `name^3`) | ❌ | ✅ |
| Typo / fuzzy tolerance | ❌ | ✅ (fuzziness AUTO) |
| Faceted aggregation real-time | ⚠️ phải query riêng | ✅ cùng round-trip |
| Highlight matched terms | ⚠️ tự cắt thủ công | ✅ `<em>` built-in |
| Synonyms / autocomplete | ❌ (hoặc tự build) | ✅ |
| ACID / transaction / unique | ✅ | ❌ |
| Strong consistency đọc-sau-ghi | ✅ | ❌ (refresh ~1s) |
| Ops cost | thấp (đã có Postgres) | cao (cluster + sync pipeline) |

---

## 🧠 Phân tích — khi nào ES "đáng tiền"

ES **đáng** khi search là **value driver**: khách dựa vào search để mua (TMĐT), và
3 thứ — relevance + fuzzy + facet — trực tiếp tăng conversion. NexaShop: "iphon" ra 0
kết quả = mất đơn; không sort theo relevance = khách bỏ cuộc.

ES **KHÔNG đáng** khi: search đơn giản, volume thấp, không cần ranking → GIN trigram
Day 16 đã đủ với p95 45ms, mà không phải nuôi thêm cluster + sync. Thêm ES lúc đó chỉ
tăng ops + drift risk mà không tăng giá trị.

> 💡 **Latency không phải lý do chính migrate ES.** GIN đã 45ms — đủ nhanh. Lý do là
> **capability** (relevance + fuzzy + facet). Đây là điểm dễ trả lời sai: "ES nhanh
> hơn nên tôi dùng" → interviewer vặn "GIN của anh 45ms rồi, nhanh hơn để làm gì?".

---

## 🏃 Cách chạy benchmark (reproducible)

```bash
# 1. seed 1M vào Postgres (Day 16 script)
psql -h localhost -U ecom -d product_db -f generate_products_1m.sql

# 2. nạp sang ES
curl -XPOST localhost:8082/admin/search/reindex -H "Authorization: Bearer <admin-jwt>"

# 3. kiểm drift
curl localhost:8082/admin/search/drift -H "Authorization: Bearer <admin-jwt>"
#    {"postgresActive": 1000000, "elasticsearchDocs": 1000000, "drift": 0}

# 4. bắn load — Postgres path
hey -n 5000 -c 50 "localhost:8082/products?q=iphone"
# 5. bắn load — ES path
hey -n 5000 -c 50 "localhost:8082/products/search?q=iphon"   # fuzzy: GIN ra 0, ES ra kết quả
```

So sánh p50/p95 + đếm kết quả "iphon" (Postgres 0 vs ES > 0) → minh họa capability gap.

---

## ⚠️ Cạm bẫy đo

1. **Không warm cache** → lần đầu ES cold (disk read) chậm gấp nhiều, không phản ánh
   steady-state.
2. **Đo trên dataset nhỏ** (vài nghìn) → mọi thứ đều nhanh, không thấy khác biệt; phải
   1M.
3. **So latency point-lookup** rồi kết luận "Postgres thắng" → sai trục: ES không sinh
   ra để thay B-tree point lookup, nó thay full-text search.
4. **Quên refresh ES** sau bulk index → query ra thiếu doc → tưởng ES sai (thực ra
   chưa visible; default refresh 1s, test gọi `indexOps.refresh()`).

---

## 🔗 Related

- Baseline: [performance/16 — GIN trigram EXPLAIN](16-sql-explain-analyze.md)
- Concept: [lesson 22 — inverted index](../lessons/22-elasticsearch-basics.md)
- Decision: [ADR-010](../decisions/010-postgres-vs-elasticsearch-search.md)
- Code: [`ProductSearchService`](../../services/product-service/src/main/java/com/ecom/product/search/ProductSearchService.java) ·
  [`ReindexService`](../../services/product-service/src/main/java/com/ecom/product/search/ReindexService.java)
