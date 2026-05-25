# CLAUDE.md — Context cho Claude (đọc trước khi làm gì)

> **Đây là file Claude tự động đọc khi user mở session mới trong folder này.**
> Mục đích: giữ context khi user gõ "DAY 5" mà session trước đã đóng.

---

## 1. Project là gì

Ecommerce platform production-grade build trong 40 ngày (7 tuần — Core, Kafka,
Performance, Data Layer, Frontend, System Design intensive, Final mock) — **mục
đích chính là để ôn phỏng vấn Senior Fullstack Developer / Tech Lead
(backend-heavy)** ở thị trường Việt Nam. KHÔNG phải tutorial, KHÔNG phải SaaS
thực sự.

User là Tonny (`quan.le@sotatek.com`). Điểm mạnh: Spring Boot, SQL,
Microservice, System Design. React 7/10. Dùng AI để tăng tốc execution.

## 2. Đây là Full Learning System (KHÔNG chỉ là code)

Mỗi feature được build phải đi kèm:
1. **Source code** production-grade
2. **Architecture docs** — tại sao chọn design này, trade-offs
3. **Lessons** — concept đơn giản, khi nào dùng / không dùng
4. **Issues** — production incident simulation (Problem / Symptoms / Root Cause / Fix / Prevention)
5. **Performance notes** — bottleneck / cách đo / cách tune
6. **Interview Q&A** — câu hỏi + strong answer + follow-up trap
7. **ADR** khi có quyết định kiến trúc lớn
8. **Cross-reference** — mọi doc đều link tới source code và doc khác

## 3. Working principles (BẮT BUỘC)

1. **Human owns decisions — AI accelerates execution.**
2. Code phải **production-grade**, không tutorial-grade.
3. **Documentation quan trọng ngang source code.**
4. Mỗi feature lớn → docs đi kèm.
5. Mỗi issue: root cause + solution + prevention.
6. Mỗi optimization: nói rõ trade-off.
7. **Backend depth > frontend.**
8. Luôn trả lời theo **Senior mindset**.

## 4. Tech stack (đã chốt)

- **Backend**: Java 21 LTS, Spring Boot **3.4.5**, Spring Security, Spring Data JPA, OpenFeign + Spring 6.1 HTTP Interface
- **Concurrency**: Virtual Threads (Java 21 / Loom) — bật `spring.threads.virtual.enabled=true` từ Day 2
- **Resilience**: Resilience4j (circuit breaker / retry / bulkhead — Day 12)
- **Cache**: Redis 7 (distributed) + Caffeine 3 (local) — 2-tier ở Day 15
- **Messaging**: Apache Kafka 3.x (KRaft, không Zookeeper)
- **Observability**: Micrometer Tracing + OpenTelemetry (replace Sleuth deprecated) — Day 9 / 20
- **Persistence**: PostgreSQL 16, Flyway migration
- **Search**: Elasticsearch 8 (Spring Data Elasticsearch) — Day 22, sync từ Postgres (app-level → upgrade Debezium CDC nếu volume tăng)
- **Document store**: MongoDB 7 (Spring Data MongoDB) — Day 23, dùng có chủ ý cho event store + flexible product attributes (KHÔNG cargo-cult)
- **Build**: **Gradle 8.11 (Kotlin DSL) + Version Catalog** (`gradle/libs.versions.toml`)
- **Frontend (Week 5)**: React 18 + TS + Vite + TanStack Query v5 + Ant Design + Vitest + Playwright (E2E Day 30)
- **CI/CD**: GitHub Actions
- **Modernity touches**: Records cho DTO/VO, Sealed interfaces cho state machine (Order), Pattern matching switch, Testcontainers `@ServiceConnection`, JSpecify nullness annotations

## 5. Architecture rules (BẮT BUỘC)

**Hybrid: Layered + Selective DDD** (xem `docs/decisions/001-why-hybrid-architecture.md`).

| Style    | Services                                                                |
| -------- | ----------------------------------------------------------------------- |
| DDD      | `order-service`, `inventory-service`, `payment-service`                 |
| Layered  | `auth`, `product`, `cart`, `notification`, `analytics`, `gateway`       |

Tiêu chí 3-điểm để chọn DDD: ≥3 invariants + concurrency thật + có domain events ra ngoài. Không đủ 3 → Layered.

**DB-per-service**: KHÔNG service nào truy vấn DB của service khác. Cross-service: Feign (sync) hoặc Kafka (async).

**`common-lib` chỉ chứa cross-cutting infrastructure** (response, exception, audit, MDC). KHÔNG chứa domain class.

## 6. Repo layout

```
ecommerce-platform/
├── CLAUDE.md                       # file này
├── README.md                       # quick start
├── settings.gradle.kts             # multi-project config
├── build.gradle.kts                # root build (toolchain, JUnit, etc.)
├── gradle.properties               # JVM args + parallel/cache options
├── gradle/
│   ├── libs.versions.toml          # Version Catalog (single source of versions)
│   └── wrapper/                    # gradle wrapper jar + properties
├── docker-compose.yml
├── infra/postgres/                 # init scripts
├── common-lib/                     # shared infrastructure (auto-config)
│   └── build.gradle.kts
├── services/                       # 9 microservices (build dần Day 2-13)
├── frontend/                       # Week 5 (Day 26-30)
└── docs/
    ├── README.md                   # 📚 HUB mục lục + lộ trình đọc (đọc đầu tiên khi cần tham chiếu doc)
    ├── ROADMAP.md                  # 40-day plan + CHECKLIST (đọc để biết đang ở đâu)
    ├── evolution/                  # 📖 Biên niên sử — narrative kể chuyện hệ thống lớn lên từng day
    ├── architecture/
    ├── decisions/                  # ADRs
    ├── lessons/
    ├── issues/
    ├── performance/
    ├── interview/
    ├── system-design/              # Week 6 whiteboard problems (capacity, flash sale, autocomplete, ...)
    ├── runbooks/
    ├── review/                     # AI/junior code review traps (cumulative)
    └── leadership/                 # incident log thật từ Sotatek (cho phỏng vấn lead)
```

## 7. Conventions Tonny đã chốt

- **Docs language**: TẤT CẢ docs (lessons / issues / architecture / interview / ADR / performance / runbooks) viết **tiếng Việt kỹ thuật, giữ nguyên English technical terms** (monorepo, Aggregate, optimistic lock, circuit breaker, MDC, traceparent, idempotent, ...). Không dịch term tiếng Anh sang Việt nửa vời. Code comment cũng theo style này.
- **File creation**: tạo trực tiếp vào folder workspace của Tonny (`D:\Develop\MySelf\ecommerce-platform`).
- **Code scope**: deep-but-narrow — production-grade ở core, stub phần phụ với `// TODO` có giải thích.
- **Commit messages**: cuối mỗi DAY suggest 1 commit message format conventional (`feat(scope): ...`, `chore(foundation): ...`).
- **Branch & PR per day** (BẮT BUỘC từ Day 2 trở đi):
  - Mỗi day build trên 1 branch riêng `day-NN-<slug>` (vd: `day-02-auth`, `day-04-inventory-ddd`, `day-15-cache`). Tạo từ `master` ở Phase 1.
  - KHÔNG commit thẳng vào `master`. `master` chỉ nhận merge từ PR.
  - Cuối day → tạo PR target `master` với title `Day NN — <topic>` + description gồm: **Mục tiêu · Code thay đổi (file list + 1 dòng why) · Docs đã build (link) · Test result · Lessons-learned · Roadmap checklist tick**.
  - Format description chuẩn ở `.claude/skills/day/SKILL.md` Phase 7.
  - Không auto-merge — Tonny tự review + merge để giữ cảm giác "PR review thật" (cho phỏng vấn nói được "tôi merge sau khi self-review").
- **Mock interview style**: Style A — interactive, brutally honest, đóng vai senior interviewer.
- **Frontend**: dồn về Week 5 (Day 26-30).
- **40-day structure**: Week 1 Core (D1-7) · Week 2 Kafka (D8-14) · Week 3 Performance (D15-21) · Week 4 Data layer — ES + Mongo + decision matrix (D22-25) · Week 5 Frontend (D26-30) · Week 6 System Design intensive (D31-37) · Week 7 Final mock + retro (D38-40).

## 8. Cách tiếp tục (cực quan trọng)

> User sẽ gõ một trong các lệnh sau. Trước khi làm bất cứ gì:
> 1. **Mở `docs/ROADMAP.md`** để xem current status & checklist.
> 2. **Đọc các docs/code đã build cho ngày trước đó** nếu cần context.
> 3. Mới tiếp tục.

### Special commands

| Lệnh             | Hành động                                                            |
| ---------------- | -------------------------------------------------------------------- |
| `DAY X`          | Build day X theo plan. Output 8 phần (xem mục 9).                   |
| `DOC MODE`       | Chỉ focus docs, không code.                                          |
| `ISSUE MODE`     | Tạo production incident giả lập để Tonny xử lý.                      |
| `REVIEW MODE`    | Review code brutally honest theo senior standard.                    |
| `INTERVIEW MODE` | Đóng vai senior interviewer, hỏi 1 câu/lượt + phản biện.            |
| `FAST MODE`      | Ưu tiên build nhanh, ít docs, AI-first.                              |

### Khi user gõ `DAY X`, output PHẢI gồm 8 phần:

1. Mục tiêu hôm nay
2. Task mô phỏng công ty thật (bối cảnh — ai giao, deadline, ai review)
3. Code cần build
4. Docs cần build (kèm cross-reference)
5. Lesson cần học (đọc lại đêm)
6. Issue nên simulate
7. Interview questions
8. Senior mindset notes

### 8b-evo. Evolution chapter (BẮT BUỘC mỗi day)

Sau khi build xong code + docs, viết 1 chương mới vào `docs/evolution/NN-<slug>.md`
kể câu chuyện day vừa build. Đây là **narrative** — không phải tóm tắt ROADMAP.
Chi tiết format + writing rules xem `.claude/skills/day/SKILL.md` Phase 5b.

Nguyên tắc giữ mạch:
- Mở đầu reference chương trước (1 câu). Kết thúc hook chương sau (cliffhanger).
- KHÔNG lặp giải thích đã có ở chương trước — reference rồi đi tiếp.
- Giọng kể chuyện, metaphor cụ thể, rhythm câu ngắn xen dài.
- Update `docs/evolution/README.md` mục lục sau khi viết.

### 8c. Extension sections — apply CÓ CHỌN LỌC, không phải mỗi day

Tonny là backend Tech Lead 6 người **thật**. Ngoài 8 phần trên, append các
section sau khi điều kiện đúng. KHÔNG append bừa — append sai chỗ làm docs
loãng + biến senior thinking thành cliché.

| Section          | Khi nào append                                                                            | Ghi vào đâu                       |
| ---------------- | ----------------------------------------------------------------------------------------- | --------------------------------- |
| **AI Playbook**  | **Mỗi day** (vì project này build với AI là core)                                         | Cuối file `interview/day-NN-*.md` |
| **Tech Lead Lens** | **Chỉ day có decision lớn**: Day 1, 4, 6, 8, 9, 12, 13, 15, 19, 22, 23, 24, 31, 33 (≈ ⅓ số day) | Cuối file `interview/day-NN-*.md` |
| **CV Bullet**    | **Cuối mỗi week** (Day 7, 14, 21, 25, 30, 37, 40) — gom 1 tuần thành 1-2 bullet có metric | File riêng `interview/week-NN-cv-bullets.md` |

**AI Playbook format** (gọn, 4 bullet):
- Phần nào của task hôm nay AI làm tốt / nên giao
- 1 prompt mẫu ngắn để generate (không quá 4 dòng)
- Risk khi để AI làm phần đó
- Cách validate output (test gì, đọc kỹ chỗ nào)

**Tech Lead Lens format** (gọn, 3 bullet):
- Trade-off chính — và "scale 10x thì đổi gì"
- Production failure mode + cách triage nhanh (5 bước)
- Nếu junior + AI viết phần này, **2 lỗi dễ xảy ra nhất** + chỗ phải review kỹ

**Code Review traps** (cumulative, không append vào day docs):
- Mỗi khi gặp pattern AI/junior viết sai trong code Tonny build → append 1 entry vào `docs/review/ai-junior-traps.md`. Đây là asset tích lũy 30 ngày, không phải lý thuyết.

**Leadership incidents** (cumulative):
- Khi day có quyết định kiến trúc lớn, **không bịa** team scenario. Nếu Tonny chia sẻ tình huống thật ở Sotatek → append vào `docs/leadership/incidents.md` (private, không commit nếu nhạy cảm). Không có tình huống thật → skip.

## 8b. Naming convention cho docs

Filename phải gợi ý đọc thứ tự nào trước:

| Folder              | Format                           | Ví dụ                                       |
| ------------------- | -------------------------------- | ------------------------------------------- |
| `docs/decisions/`   | `NNN-topic.md` (ADR convention)  | `001-why-hybrid-architecture.md`            |
| `docs/lessons/`     | `NN-topic.md` (NN = day intro)   | `01-monorepo-vs-polyrepo.md`, `06b-sealed-types-state-machine.md` |
| `docs/issues/`      | `NN-topic.md`                    | `04-overselling-stock.md`                   |
| `docs/performance/` | `NN-topic.md`                    | `15-cache-aside.md`                         |
| `docs/interview/`   | `day-NN-topic.md`                | `day-02-auth.md`, `week-01-mock.md`         |
| `docs/architecture/`| descriptive (không số)           | `system-overview.md`, `order-domain.md`     |
| `docs/system-design/`| descriptive (không số)          | `flash-sale.md`, `homepage-feed.md`         |
| `docs/runbooks/`    | descriptive (không số)           | `kafka-topic-recovery.md`                   |

Khi 1 day có nhiều lessons / issues / performance docs, dùng suffix
`a / b / c`: `06-aggregate-root.md` + `06b-sealed-types-state-machine.md`.

## 9. Format Docs bắt buộc

### ADR
```
- Status / Date / Deciders / Supersedes
- Decision (1-2 câu cô đọng)
- Context (vì sao có quyết định này)
- Alternatives considered (≥3 options + ưu/nhược)
- Chosen — Rationale
- Trade-offs (cả accepted lẫn rejected)
- Consequences
- Related (link code + doc)
```

### Issue (problem-solving doc — format BẮT BUỘC)
```
1. Problem (1-2 câu cô đọng — vấn đề gì xảy ra)
2. Symptoms (log / metric / user-facing — quan sát được gì)
3. Root cause (nguyên nhân kỹ thuật, không bề mặt)
4. Approaches compared (≥3 approach — bảng 3 cột: Approach / Pros / Cons)
5. Chosen approach + Why (lý do chọn — gắn với context project, không lý thuyết)
6. Fix (code/config thật, link tới file)
7. Prevention (test / lint / monitor / alert — chặn tái phát)
8. Trade-off accepted (giải pháp đã chọn HY SINH gì)
9. Related code + Related docs
```

> ⚠️ Section 4 + 5 là **điểm phân biệt senior vs junior**. Junior chỉ
> ghi "fix bằng X". Senior ghi "có 3 cách, chọn X vì context Y, hy sinh Z".
> KHÔNG được skip kể cả issue đơn giản.

### Lesson
```
TL;DR / Khi nào dùng / Khi nào KHÔNG dùng / Cạm bẫy / Approaches compared (nếu có ≥2 approach hay tranh cãi) / Trả lời phỏng vấn / Related
```

### Interview
```
Q → Strong answer (Việt + English term) → Follow-up traps
+ Senior mindset notes ở cuối
```

## 10. Quality bar

> "Output phải giúp future-Tonny ôn lại trong 5 phút."
> Practical. Production-focused. Senior-level. Không academic. Không lan man.

## 10b. Visual style cho docs (emoji + Mermaid)

### 🎯 Emoji rule

Dùng emoji ở **header section + status indicator + callout** để docs sinh
động, KHÔNG dùng decorative trong body text/code/table cell (gây nhiễu khi
grep/paste).

**Bảng emoji ngữ nghĩa cố định** — 1 ký hiệu = 1 ý nghĩa, không trộn:

| Emoji | Ý nghĩa                                      | Dùng ở                            |
| ----- | -------------------------------------------- | --------------------------------- |
| 🎯    | Goal / mục tiêu                              | Section header                    |
| 📚    | Documentation / reference                    | Section header                    |
| 🧠    | Senior thinking / mindset                    | Section header                    |
| 🤖    | AI usage / playbook                          | Section header                    |
| 👥    | Leadership / team                            | Section header                    |
| ⚠️     | Trap / pitfall / cạm bẫy                     | Callout block                     |
| 🔥    | Production incident / sev1-2                 | Issue title                       |
| 💡    | Tip / insight                                | Callout                           |
| 🏗️    | Architecture / decision                      | Section header                    |
| ⚡    | Performance / tuning                         | Section header                    |
| 🔒    | Security / auth                              | Section header                    |
| 🔧    | Setup / config / runbook                     | Section header                    |
| ✅    | Done / completed / pass                      | Status indicator                  |
| 🚧    | In progress / partial                        | Status indicator                  |
| ⏳    | Planned / pending                            | Status indicator                  |
| ❌    | Rejected option / failed                     | ADR alternatives, test result     |
| 🟢🟡🔴 | Severity / level                            | Mermaid classDef, status table    |

### 📊 Mermaid rule

Dùng Mermaid khi text/table không show được:
- **Topology / dependency** → `graph TD` hoặc `graph LR`
- **Sequence flow** (request lifecycle, saga) → `sequenceDiagram`
- **State machine** (Order status) → `stateDiagram-v2`
- **Decision tree** (3-điểm criteria) → `graph TD` với diamond shape
- **Timeline** (40-day plan, sprint) → `gantt`
- **Class/aggregate relation** → `classDiagram`

**KHÔNG vẽ Mermaid khi**: list 2-3 mục, table 2 cột, code call đơn lẻ.
Diagram phải show info mà text không show tốt.

**Color convention** (dùng Mermaid `classDef`):

```
classDef done       fill:#86efac,stroke:#16a34a,color:#000
classDef inProgress fill:#fde68a,stroke:#d97706,color:#000
classDef planned    fill:#e5e7eb,stroke:#6b7280,color:#000
classDef sync       fill:#bfdbfe,stroke:#2563eb,color:#000
classDef async      fill:#fde68a,stroke:#d97706,color:#000
classDef failure    fill:#fecaca,stroke:#dc2626,color:#000
classDef decision   fill:#e9d5ff,stroke:#9333ea,color:#000
```

🟢 green = done/success path · 🟡 yellow = async/in-progress · 🔴 red =
failure/incident · 🔵 blue = sync call · 🟣 purple = decision point ·
⚪ gray = planned.

**Compatibility note**: Mermaid render được trên VSCode (built-in 1.91+),
GitHub web, IntelliJ, Obsidian. Một số viewer cũ thấy raw — chấp nhận
trade-off vì viewer chính của Tonny đều support.



## 11. Trước khi gõ tool đầu tiên trong session mới

1. Đọc [`docs/ROADMAP.md`](docs/ROADMAP.md) — xác định day hiện tại + day sắp build.
2. Nếu cần tham chiếu doc nào trước đó → mở [`docs/README.md`](docs/README.md) (hub mục lục).
3. Nếu user gõ `DAY X` mà đã có trong checklist → hỏi: rebuild hay skip ahead?
4. Nếu user mở 1 doc cũ và sửa → respect changes của họ.
5. Khi tạo doc mới → cập nhật index ở [`docs/README.md` § 2](docs/README.md#2-index--full-document-catalog).

---

## Modernity additions per day (đã chốt với Tonny ngày 2026-05-03)

| Day | Tech mới được introduce                                            |
| --- | ------------------------------------------------------------------ |
| 2   | Virtual Threads (`spring.threads.virtual.enabled=true`), Records, Testcontainers `@ServiceConnection` |
| 4   | Optimistic locking (`@Version`), Sealed types cho domain state     |
| 6   | Sealed interface cho `OrderStatus` + exhaustive pattern matching   |
| 8   | Spring 6.1 HTTP Interface vs OpenFeign — so sánh trade-off         |
| 9   | Micrometer Tracing + OpenTelemetry (W3C traceparent)               |
| 12  | Resilience4j: circuit breaker + retry + bulkhead + DLT             |
| 15  | Caffeine L1 + Redis L2 (2-tier cache)                              |
| 19  | Virtual thread benchmark + structured concurrency preview          |
| 20  | k6 load test + Grafana + OTel traces visualization                 |
| 22  | Elasticsearch 8 + Spring Data Elasticsearch, sync Postgres → ES (app-level), GIN/full-text vs ES benchmark |
| 23  | MongoDB 7 + Spring Data MongoDB cho event store + flexible product attributes; document modeling vs relational |
| 24  | SQL / NoSQL / ES decision matrix — câu hỏi phỏng vấn classic       |
| 26  | React 18 + Vite + TanStack Query v5 + Ant Design                   |
| 30  | Playwright E2E (replace Cypress)                                   |
| 31  | Capacity estimation discipline (numbers-every-engineer-should-know)|
| 33  | Flash sale design — Redis Lua atomic decrement + queue             |

## Sources of truth

- **Plan & current status**: [`docs/ROADMAP.md`](docs/ROADMAP.md)
- **Architecture decisions**: `docs/decisions/`
- **Build config**: `gradle/libs.versions.toml` (versions), `*.gradle.kts` (logic)
- **Last commit info**: chạy `git log --oneline -5` ở root nếu cần.
