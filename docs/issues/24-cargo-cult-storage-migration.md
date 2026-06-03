# 🔥 Issue 24 — Cargo-cult storage migration: "move order/stock sang Mongo cho scale"

> **Status**: ✅ Resolved (đề xuất bị bác — và đó là cái fix) · Day 24
> Một incident-mô-phỏng kiểu *"suýt"*: không phải code chạy sai, mà là một **quyết định
> kiến trúc sai suýt được duyệt**. Đây là loại issue senior hay gặp nhất: chặn ở review,
> không phải ở prod.

---

## 1. Problem

Trong sprint planning, một dev đề xuất migrate `orders` + `stock` từ Postgres sang
MongoDB "để scale ngang dễ hơn, Mongo nhanh hơn và linh hoạt hơn". Nghe hợp lý trên slide.
Nếu duyệt, hệ thống sẽ **mất invariant `reserved ≤ quantity`** và phải tự dựng lại
transaction integrity mà Postgres đang cho free.

## 2. Symptoms

Đây là issue *trước khi* thành prod incident — nhưng nếu để lọt, symptom sẽ là:

- 📉 **Overselling**: hai reservation đồng thời trên cùng SKU đọc `reserved` cũ, cùng ghi
  → bán quá tồn kho. (Chính cái [issue 04](04-overselling-stock.md) đã chặn bằng optimistic lock.)
- 💸 **Order ghi nửa vời**: order header ghi xong, items ghi fail giữa chừng — không có
  transaction bao quanh nhiều collection → đơn rác, tiền lệch.
- 🐢 **Latency tăng** thay vì giảm: để cứu vãn, team bật multi-doc transaction trên
  replica-set → chậm hơn cả Postgres ban đầu, đúng thứ định trốn.
- 🔁 Reconcile job mọc lên khắp nơi để "dọn" inconsistency — dấu hiệu kinh điển của
  dùng sai storage.

## 3. Root cause

**Law of the instrument** — *"búa nào cũng thấy đinh"*. Lý do migrate là "Mongo nhanh +
linh hoạt", một câu **thiếu access pattern**. Order/stock là data:

- có **≥3 invariant** (status transition, `amount ≥ 0`, `reserved ≤ quantity`),
- có **concurrency thật** (nhiều người mua cùng SKU),
- cần **multi-entity atomicity** (order + items + outbox trong 1 tx).

Đây đúng là 3 tiêu chí đã chọn **DDD trên Postgres** ở [Day 4](../decisions/003-ddd-for-order-inventory-payment.md).
Mongo single-doc atomic không bao được invariant cross-document; muốn bao thì phải dùng
multi-doc txn (đắt) — tức là **mô phỏng lại Postgres bằng Mongo**, một vòng tròn vô nghĩa.
Gốc rễ: chọn storage theo *tính từ marketing* ("nhanh", "linh hoạt") thay vì *access pattern*.

## 4. Approaches compared

| Approach | Pros | Cons |
| -------- | ---- | ---- |
| **A. All-in Mongo** (order+stock+items đều Mongo) | Một storage, schema linh hoạt, scale ngang native | Mất ACID cross-doc; tự code lại invariant + integrity; overselling rủi ro; cuối cùng phải bật multi-doc txn = chậm |
| **B. Mongo + multi-doc transaction** | Có atomicity | Cần replica-set; chậm hơn Postgres; phức tạp; "dùng Mongo để giả làm Postgres" |
| **C. Postgres giữ core + Mongo làm derived read-model** ✅ | Giữ ACID + invariant ở nơi cần; Mongo gánh đúng việc (analytics, catalog read); mỗi store đúng access pattern | Có dual-write/sync drift (đo được, đã xử ở Day 22); 2 storage để vận hành |
| **D. EAV trong Postgres cho "linh hoạt"** | Một storage | Anti-pattern: query địa ngục, mất type safety, mất constraint — tệ hơn JSONB |

## 5. Chosen approach + Why

**Chọn C** — Postgres giữ source of truth cho order/stock/payment (ACID + invariant +
`@Version`), Mongo chỉ làm **derived read-model** (analytics event store + catalog) đúng
như đã build Day 23. Lý do gắn context project:

- Invariant `reserved ≤ quantity` + concurrency là **lý do tồn tại** của inventory aggregate
  (Day 4). Vứt nó đi = vứt cả tháng chống overselling.
- "Scale ngang" của order **chưa phải bottleneck thật** — capacity hiện tại Postgres dư sức
  (xem [scaling plan](../architecture/system-overview.md)). Migrate vì lo xa = tối ưu non.
- Mongo *vẫn được dùng* — nhưng cho việc nó giỏi (schemaless, ghi nhiều, aggregation), không
  phải cho money/invariant.

> Khi nào C đảo? Nếu order volume thật sự vượt ngưỡng shard Postgres (chục triệu
> order/ngày), giải pháp đúng là **shard Postgres theo `customer_id`** (giữ ACID trong shard)
> chứ không phải bỏ ACID sang Mongo.

## 6. Fix

Không có code thay đổi — fix là **một quyết định review + một tài liệu chặn tái phát**:

- Decision matrix làm "luật" cho org: [lesson 24](../lessons/24-sql-vs-nosql-vs-es-decision-matrix.md)
  ô (1,Mongo)=🟡 và (2,Mongo)=❌ với reasoning.
- Giữ nguyên Postgres làm source of truth: [order aggregate](../../services/order-service/src/main/java/com/ecommerce/order/domain/) +
  [stock optimistic lock](../../services/inventory-service/src/main/java/) (Day 4/6).
- Tiêu chí 3-điểm chọn storage = chính tiêu chí DDD ở [ADR-003](../decisions/003-ddd-for-order-inventory-payment.md).

## 7. Prevention

- 📋 **Decision-matrix-as-gate**: mọi PR đề xuất storage mới phải điền verdict + access
  pattern vào matrix (lesson 24). Không điền được lý do access-pattern = reject.
- 🧪 **Invariant test là red line**: concurrency test 100-thread no-oversell (Day 4) phải
  pass trên storage mới *trước* khi bàn migrate. Mongo single-doc fail test này ngay.
- 🚫 **Lint câu hỏi**: ban từ "nhanh hơn" / "linh hoạt hơn" trong design doc nếu không kèm
  *"ở access pattern nào, đo bằng gì"*.
- 📊 **Đo trước khi migrate**: chứng minh bottleneck thật (metric) trước khi đổi storage.
  Không có metric = tối ưu non = reject.

## 8. Trade-off accepted

Chọn C nghĩa là **chấp nhận sống với polyglot persistence**: 2+ storage để vận hành,
dual-write giữa Postgres → Mongo/ES, và **sync drift window** phải đo + alert (Day 22).
Đổi lại: mỗi store làm đúng việc, không hy sinh invariant tiền-bạc lấy "scale" tưởng tượng.
Cái giá là **ops phức tạp hơn** — và đó là cái giá *có chủ đích*, không phải vô tình.

## 9. Related

- Lesson: [24 — Decision matrix](../lessons/24-sql-vs-nosql-vs-es-decision-matrix.md) · [24b — CAP/PACELC](../lessons/24b-cap-pacelc-in-practice.md) · [23 — MongoDB when to use](../lessons/23-mongodb-when-to-use.md)
- Issue: [04 — Overselling stock](04-overselling-stock.md) · [22 — ES/Postgres sync drift](22-es-postgres-sync-drift.md) · [23 — Mongo no-transaction trap](23-mongodb-no-transaction-trap.md)
- ADR: [003 — DDD for order/inventory/payment](../decisions/003-ddd-for-order-inventory-payment.md) · [011 — Mongo for analytics & flexible attributes](../decisions/011-mongo-for-analytics-and-flexible-attributes.md)
- Interview: [day-24 — Storage decisions](../interview/day-24-storage-decisions.md)
</content>
