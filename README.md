# Ecommerce Platform — 40-Day Senior Fullstack / Tech Lead Interview Project

> Mục tiêu: build production-grade ecommerce platform trong 40 ngày
> (7 tuần), kèm full learning system (architecture / lessons / issues /
> performance / interview Q&A / system design). Project ôn phỏng vấn
> Senior Fullstack / Tech Lead backend-heavy.

## 👉 Bắt đầu từ đâu?

| Mục đích           | Đọc gì                                                                              |
| ------------------ | ----------------------------------------------------------------------------------- |
| Mở session AI mới  | [`CLAUDE.md`](CLAUDE.md) — context bootstrap cho Claude                             |
| Xem tiến độ        | [`docs/ROADMAP.md`](docs/ROADMAP.md) — 40-day plan + checklist                      |
| **Mục lục docs**   | **[`docs/README.md`](docs/README.md) — hub trung tâm + lộ trình đọc theo persona**  |
| Onboarding tech    | Tiếp tục đọc README này                                                             |

## Tech stack

| Layer        | Choice                                                                                |
| ------------ | ------------------------------------------------------------------------------------- |
| Language     | Java 21 LTS (Virtual Threads, Sealed types, Records, Pattern matching switch)         |
| Framework    | Spring Boot 3.4.5, Spring Security, Spring Data JPA, OpenFeign + HTTP Interface       |
| Resilience   | Resilience4j (circuit breaker / retry / bulkhead)                                     |
| Cache        | Redis 7 (distributed) + Caffeine 3 (local) — 2-tier                                   |
| Persistence  | PostgreSQL 16 (DB-per-service), Flyway migration                                      |
| Search       | Elasticsearch 8 + Spring Data Elasticsearch (Day 22, sync from Postgres)              |
| Document store | MongoDB 7 + Spring Data MongoDB (Day 23, event store + flexible product attrs)      |
| Messaging    | Apache Kafka 3.x (KRaft mode, no Zookeeper)                                           |
| Observability| Micrometer Tracing + OpenTelemetry (W3C traceparent)                                  |
| Build        | **Gradle 8.11 + Kotlin DSL + Version Catalog**                                        |
| Frontend     | React 18 + TypeScript + Vite + TanStack Query v5 + Ant Design + Vitest + Playwright (Week 5) |
| CI/CD        | GitHub Actions                                                                        |

## Repo layout

```
ecommerce-platform/
├── settings.gradle.kts        # multi-project config
├── build.gradle.kts           # root build (toolchain, JUnit, etc.)
├── gradle.properties          # Gradle daemon JVM, parallel, cache
├── gradle/
│   ├── libs.versions.toml     # Version Catalog (dependencies & plugins)
│   └── wrapper/               # gradle wrapper
├── docker-compose.yml         # local infra (Postgres / Redis / Kafka / Kafka UI)
├── infra/                     # init scripts cho infra
├── common-lib/                # shared infrastructure code
│   └── build.gradle.kts
├── services/                  # microservices (build dần Day 2-13)
├── frontend/                  # Week 4
└── docs/                      # full learning system
    ├── architecture/          # system & domain design
    ├── decisions/             # ADRs
    ├── lessons/               # concept giải thích
    ├── issues/                # production incident simulation
    ├── performance/           # tuning notes + benchmarks
    ├── interview/             # Q&A theo ngày + AI Playbook + Tech Lead Lens
    ├── system-design/         # Week 6 whiteboard problems
    ├── runbooks/              # operations
    ├── review/                # AI/junior code review traps (cumulative)
    └── leadership/            # incident log thật từ Sotatek
```

## Quick start

### 1. Yêu cầu local

- JDK 21 (sẽ được Foojay resolver tự kéo nếu thiếu — xem `settings.gradle.kts`)
- Gradle 8.11+ (chỉ cần lần đầu để generate wrapper, sau đó dùng `./gradlew`)
- Docker + Docker Compose v2

### 2. Bootstrap Gradle wrapper (lần đầu)

```bash
gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```

Sau bước này repo sẽ có đầy đủ `gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`.
Từ giờ mọi lệnh dùng `./gradlew` (Linux/Mac) hoặc `gradlew.bat` (Windows).

### 3. Bật hạ tầng

```bash
docker compose up -d
```

Services available:
- Postgres: `localhost:5432` (user: `ecom`, pass: `ecom`)
- Redis: `localhost:6379`
- Kafka: `localhost:9092` (external), `kafka:9094` (internal)
- Kafka UI: <http://localhost:8090>

Reset toàn bộ:
```bash
docker compose down -v
```

### 4. Build common-lib

```bash
./gradlew :common-lib:build
# hoặc nhanh hơn (skip test):
./gradlew :common-lib:assemble
```

Tới Day 2 sẽ có service đầu tiên (`auth-service`) để chạy:

```bash
./gradlew :services:auth-service:bootRun
```

## Roadmap

| Week | Days   | Focus                                                          |
| ---- | ------ | -------------------------------------------------------------- |
| 1    | 1–7    | Foundation + core services (auth, product, inventory, cart, order) |
| 2    | 8–14   | Kafka + async workflow + outbox                                |
| 3    | 15–21  | Performance — cache 2-tier, SQL tuning, N+1, concurrency, k6   |
| 4    | 22–25  | Data layer — Elasticsearch, MongoDB, SQL/NoSQL/ES decision matrix, polyglot review |
| 5    | 26–30  | Frontend — React + TanStack + Ant Design + Playwright E2E      |
| 6    | 31–37  | System Design intensive — capacity, flash sale, autocomplete, rate limiter, payment recon |
| 7    | 38–40  | CV polish + portfolio pitch + final mock + retro               |

Plan chi tiết + checklist: **[`docs/ROADMAP.md`](docs/ROADMAP.md)** (cập nhật mỗi sprint).

## Documentation philosophy

Mỗi feature build xong → 4 docs đi kèm: architecture (nếu cần),
lesson, issue (production scenario), interview Q&A. Cross-link qua
relative path. Tự ôn lại 6 tháng sau vẫn hiểu được trong 5 phút.

## License

MIT — đây là portfolio project.
