# Chương 1 · 🧱 Ngày khai thiên lập địa

**Day 1 — Architecture, Repo, Docker, Common-lib**

---

> *"Trước khi xây nhà, người thợ giỏi không đi mua gạch. Họ ngồi xuống và vẽ bản thiết kế."*

---

## Bối cảnh

Một thư mục trống. Một terminal nhấp nháy. Và một câu hỏi nguy hiểm:

*"Nếu được build lại từ đầu — biết những gì mình biết sau 6 năm viết microservice — mình sẽ làm khác thế nào?"*

Ngày đầu tiên không có dòng business logic nào. Không endpoint. Không database table. Chỉ có **những quyết định** — loại mà 6 tháng sau, khi hệ thống có 9 service và 50 Kafka consumer, bạn sẽ cảm ơn hoặc nguyền rủa.

---

## Những quyết định định mệnh

### 🎲 Quyết định 1: Gradle Kotlin DSL + Version Catalog

Không Maven. Không `pom.xml` 500 dòng copy-paste giữa các module. Một file `libs.versions.toml` duy nhất — **single source of truth** cho mọi dependency version trong toàn bộ monorepo.

Tại sao điều này quan trọng? Vì 3 tháng sau, khi Spring Boot release patch fix CVE, bạn sửa **1 dòng** thay vì 9 file. Khi junior hỏi "service X dùng version mấy?", câu trả lời luôn là: *"Mở `libs.versions.toml`."*

### 🎲 Quyết định 2: Docker Compose — cả thế giới trong 1 lệnh

```yaml
# Một lệnh duy nhất dựng cả vũ trụ
docker compose up -d
```

Postgres multi-DB (mỗi service 1 database — **DB-per-service từ ngày đầu**, không phải afterthought khi đã có 50 table trong 1 schema). Redis. Kafka KRaft.

Tại sao KRaft mà không Zookeeper? Vì Zookeeper đã deprecated từ Kafka 3.3. Trong phỏng vấn, nói *"tôi dùng KRaft mode"* cho thấy bạn không sống trong quá khứ.

### 🎲 Quyết định 3: Hybrid Architecture

Đây là quyết định **khó nhất** và **quan trọng nhất**.

Full DDD cho mọi service? Over-engineering. Cart service có cần Aggregate Root không? Không.
Full Layered cho mọi service? Under-engineering. Order service có 5 trạng thái, 8 invariant, domain event phức tạp — nhét vào Controller-Service-Repository là tự sát.

**Tiêu chí 3 điểm:**

```
┌─────────────────────────────────────────────┐
│  ≥3 business invariant phức tạp?     ☐      │
│  Concurrency thật (race condition)?  ☐      │
│  Domain events publish ra ngoài?     ☐      │
├─────────────────────────────────────────────┤
│  ≥3 tích → DDD                              │
│  <3 tích → Layered                          │
└─────────────────────────────────────────────┘
```

Đơn giản. Rõ ràng. Defend được trong phỏng vấn 30 giây.

### 🎲 Quyết định 4: `common-lib` — đứa con đầu lòng

Chỉ chứa **cross-cutting infrastructure**. Không domain class. Không business logic. Không "tiện thể bỏ vào đây cho dễ import".

Ngày đầu nó có:
- `ApiResponse<T>` — mọi response cùng format, FE parse 1 lần dùng mãi
- `ErrorCode` enum — error có mã, có message, có HTTP status
- `BaseException` family — throw có nghĩa, catch có chủ đích
- `BaseEntity` — `id`, `createdAt`, `updatedAt`, `@Version` (optimistic lock sẵn sàng cho Day 4)
- `CorrelationIdFilter` — gắn trace ID vào **mọi** request từ ngày đầu

Cái cuối cùng — `CorrelationIdFilter` — trông vô hại. Nhưng Day 9, khi distributed tracing xuyên 3 service qua Kafka, nó sẽ là anh hùng thầm lặng.

---

## Kết thúc ngày 1

```
📊 Scorecard:
├── Services:        0 (không vội)
├── Endpoints:       0 (không vội)
├── Business logic:  0 dòng (KHÔNG VỘI)
├── Decisions made:  4 (sẽ sống với chúng 39 ngày)
├── Docs written:    4 (ADR-001, system-overview, lesson, interview Q&A)
└── Regrets:         0
```

> 💡 **Senior mindset**: Junior đo productivity bằng số dòng code viết được. Senior đo bằng số quyết định sai **không** phải đưa ra lần nữa.

---

*→ Ngày mai, hệ thống sẽ có người gác cổng đầu tiên...*
