# Interview Q&A — Day 1 (Foundation, Architecture Setup)

> Format: **Câu hỏi → Strong answer (tiếng Việt, giữ English term) → Follow-up trap**.
> Mục tiêu: ôn lại nhanh trong 5 phút, hiểu sâu để diễn đạt mượt khi phỏng vấn.
> Khi gặp interviewer nói tiếng Anh, các English term ở đây dùng nguyên — không cần dịch ngược.

---

## Q1. Tại sao chọn monorepo thay vì polyrepo cho microservice project?

**Strong answer**

> Mình chọn Gradle multi-project monorepo vì 3 lý do: team size = 1, có
> shared infrastructure code (module `common-lib`), và muốn onboarding
> nhanh. Với scale hiện tại, cross-repo coordination chỉ thêm CI overhead
> không đáng.
>
> Trade-off mình chấp nhận: build time tăng gần tuyến tính theo số module,
> và có cám dỗ over-share qua `common-lib`. Để mitigate, mình giới hạn
> `common-lib` chỉ chứa cross-cutting infrastructure — KHÔNG có domain
> class — và CI dùng Gradle build cache + `--parallel` để incremental
> build mỗi khi 1 module đổi.
>
> Khi nào migrate sang polyrepo: lúc thêm service non-JVM, hoặc khi tách
> thành nhiều team có release cadence độc lập, hoặc khi build time monorepo
> bottleneck developer productivity. Lúc đó cân nhắc polyrepo, hoặc
> polyglot build system như Bazel.

**Follow-up traps**

- *"Nếu `common-lib` cần breaking change thì sao?"* → Bump version,
  upgrade từng service một. Trong monorepo: 1 PR. Polyrepo: N PR qua N repo
  + version skew window. **Edge case cần đề cập**: nếu 2 service cần 2
  version khác nhau cùng lúc, đó chính là signal cần split `common-lib`
  hoặc service đó nên vendor copy riêng.
- *"Làm sao tránh `common-lib` thành god JAR?"* → Hard rule: no domain
  code, no business DTO. Code review checklist mỗi PR. Khi thấy class
  thứ 3 cùng kiểu (vd: 3 cái `*Validator`), cân nhắc tách module riêng.

---

## Q2. Tại sao DDD cho một số service và Layered cho số khác?

**Strong answer**

> DDD chỉ "trả lời được tiền" khi service có ≥3 business invariants phải
> giữ cùng nhau, có concurrency contention thật, và phát domain event ra
> ngoài. `order`, `inventory`, `payment` đủ 3 tiêu chí; còn lại thì không.
>
> Ép DDD lên stateless dispatcher như `notification-service` chỉ tạo
> ceremony — Aggregate, Repository, Domain Event cho 1 method gọi SMTP.
> Đó là cargo-culting.
>
> Quy tắc của mình: chọn style **per service**, không phải per project.
> [ADR-001](../decisions/001-why-hybrid-architecture.md) document lại
> tiêu chí chọn để decision có thể audit lại sau này.

**Follow-up traps**

- *"2 style trong 1 codebase là code smell mà?"* → Inconsistency là
  smell khi tùy hứng. Inconsistency có **documented criteria** là
  *modeling* — giống cách bạn để 1 số endpoint reactive và số khác
  blocking dựa trên đặc tính. Smell là inconsistency không có tài liệu
  giải thích.
- *"Onboard dev mới với 2 style thế nào?"* → ADR-001 + mỗi service có
  `package-info.java` khai báo style của nó. Reading order cố định:
  ADR → service README → code.
- *"Khi nào 1 Layered service nên migrate sang DDD?"* → Khi xuất hiện
  invariant thứ 3 phải giữ atomically, hoặc khi có concurrency bug do
  thiếu Aggregate boundary. Không migrate vì "đẹp hơn".

---

## Q3. Tại sao DB-per-service? Vấn đề join thì sao?

**Strong answer**

> DB-per-service là nền tảng của microservice independence. Share schema
> đồng nghĩa mọi migration biến thành cross-team coordination — phá luôn
> cái lợi autonomy mà bạn build microservice để có.
>
> Vấn đề join là có thật, nhưng giải quyết được bằng 3 pattern:
> 1. **Composition ở application layer** — service orchestrate qua Feign
>    hoặc query data của nó cộng thêm 1 remote read.
> 2. **CQRS read model** — cho cross-domain query (vd: order kèm product
>    name), build 1 denormalized view qua Kafka event.
> 3. **API Gateway / BFF** — cho UI-shaped response span nhiều service.
>
> Cách (1) mua thời gian; cách (2) là long-term answer khi read pattern
> đã ổn định.

**Follow-up traps**

- *"Transaction across service thì sao?"* → KHÔNG distributed
  transaction (2PC). Dùng **Saga pattern + Outbox**. Order placement là
  ví dụ canonical — Day 13 sẽ implement đầy đủ.
- *"Như vậy chẳng phải duplicate data sao?"* → Đúng, **chủ ý**. Duplicate
  trong read model là cái giá phải trả cho service autonomy + read
  performance. Source of truth vẫn là service sở hữu domain đó.
- *"Eventual consistency user thấy được thì sao?"* → UI design phải tính
  trước: optimistic UI, status polling, hoặc explicit "đang xử lý".
  Tránh promise atomicity ngầm với user.

---

## Q4. Tại sao response wrap trong envelope `ApiResponse<T>`?

**Strong answer**

> 3 lý do:
> 1. **Một interceptor duy nhất ở frontend** xử lý mọi error consistent —
>    success/failure shape giống nhau, FE chỉ check 1 field `success`.
> 2. **Forward-compatible**: thêm `traceId`, `meta`, `pagination` không
>    đổi contract. Với naked body, bất kỳ thay đổi nào cũng là breaking
>    change.
> 3. **Logging và observability**: gateway và APM phân loại success vs
>    error theo `success: bool` mà không cần parse schema từng route.
>
> Cái giá: client phải unwrap thêm 1 lớp. Trade-off công bằng đổi lấy
> consistency.

**Follow-up traps**

- *"REST purist sẽ nói thế này không RESTful — HTTP status code mới là
  envelope chứ?"* → Đồng ý về principle. Thực tế nhiều org cuối cùng
  vẫn reinvent envelope vì: body shape khác nhau giữa success/error,
  partial success (kiểu HTTP 207) cần body convention, frontend cần
  shape ổn định. Mình treat đây là **pragmatic REST**.
- *"Downstream system mong raw body thì sao?"* → Gateway unwrap riêng
  cho endpoint đó. Internal contract giữ consistent.
- *"`success` boolean có dư khi đã có HTTP status?"* → Có dư về
  information theory, nhưng giúp FE không phụ thuộc HTTP layer parsing
  (vd: qua proxy strip status, hoặc response cached). Defensive coding.

---

## Q5. Tại sao correlation ID dùng filter + MDC?

**Strong answer**

> Tracing across service là non-negotiable cho production. Filter này làm
> 4 việc:
> 1. Đọc `X-Correlation-Id` từ request, hoặc generate UUID mới.
> 2. Ghi vào MDC để logback pattern in ra ở mọi log line trong thread đó.
> 3. Echo lại qua response header để client thấy được id.
> 4. **Clear MDC trong `finally`** để tránh leak qua thread của thread-pool
>    — bug này silent và brutal trong async code.
>
> Filter chạy ở `HIGHEST_PRECEDENCE` để cả Spring Security filter
> exception cũng được log kèm trace id.
>
> Đây là **minimum viable tracing**. Production thật sẽ layer
> OpenTelemetry lên trên, để cùng id đó trở thành W3C `traceparent`
> propagate xuống downstream service và Kafka header — Day 9 sẽ làm.

**Follow-up traps**

- *"Sao không dùng Spring Cloud Sleuth?"* → Sleuth đã merge vào
  **Micrometer Tracing** từ Spring Boot 3 — cùng ý tưởng, OTel-native.
  Mình build foundation tự tay trước để show mechanics; OTel layer là
  enhancement Day 9.
- *"Cùng 1 thread serve 2 request bị leak MDC thì sao?"* → Đó chính là
  lý do `MDC.remove` trong `finally`. Tomcat reuse thread, nếu quên
  cleanup → request 2 log ra trace id của request 1. Loại bug chỉ bắt
  được qua stress test, không bắt được qua unit test.
- *"Virtual thread (Java 21) có cần lo MDC leak không?"* → Mỗi virtual
  thread là 1 instance riêng, không reuse → ít nguy cơ leak hơn. Nhưng
  vẫn phải clear vì `MDC` dùng `ThreadLocal`, mà ThreadLocal trên
  virtual thread vẫn behave như platform thread (Loom không thay đổi
  semantics, chỉ thay đổi scheduler).

---

## Senior mindset notes (Day 1)

1. **Senior là làm decision explicit.** Junior viết code. Mid ra decision.
   Senior viết decision xuống để 10 dev tương lai không phải re-litigate.
   ADR-001 là deliverable phân biệt level rõ nhất.
2. **"Pragmatic" thắng "consistent" khi consistency là tùy hứng.** Mixed
   Layered + DDD ổn — *với documented criteria*. Tệ nhất là consistent
   sai (DDD cho mọi service "vì standard").
3. **Build boring infrastructure trước.** Correlation ID, exception
   handler, response envelope — mấy cái này skip ngày 1 thì giờ thứ 80
   sẽ trả giá. Day 1 invest để Day 5 không bleed.
4. **Luôn nói rõ cái KHÔNG làm.** Mình KHÔNG build service mesh,
   distributed tracing backend, observability stack hôm nay. Senior biết
   scope; junior cố build mọi thứ.
5. **Decision có thể sai — quan trọng là audit được.** ADR ghi cả
   alternative đã reject + lý do. 6 tháng sau revisit, biết quyết định
   dựa trên context nào, context đổi thì revisit mới có cơ sở.

---

## AI Playbook (Day 1)

> Day 1 là **infrastructure + decision-heavy** — phần AI làm tốt và phần
> KHÔNG nên giao là rất khác nhau.

- **AI làm tốt / nên giao**:
  - Boilerplate Gradle Kotlin DSL + Version Catalog (cú pháp dễ sai, AI nhớ chính xác hơn người).
  - Generate exception family (`BaseException`, `BusinessException`, ...) + javadoc.
  - Compose `docker-compose.yml` chuẩn (Postgres multi-DB init script, Kafka KRaft listeners — toàn cú pháp dễ tra cứu).
  - Draft đầu tiên cho ADR / lesson markdown (rồi mình edit lại).

- **AI làm KÉM / phải tự ra decision**:
  - Chọn DDD vs Layered cho từng service — context-specific, AI sẽ đề xuất "DDD cho mọi thứ" theo bias training data.
  - Decision split `common-lib` đến đâu — AI có xu hướng over-share.
  - Thiết kế envelope `ApiResponse<T>` — AI sẽ copy form Java reference cũ thiếu `traceId`/`meta`.

- **Prompt mẫu** (cho phần boilerplate):
  ```
  Generate a Gradle 8.11 Kotlin DSL multi-project setup for a Spring Boot
  3.4.5 + Java 21 monorepo with Version Catalog. Centralize repos in
  settings.gradle.kts (FAIL_ON_PROJECT_REPOS). 1 library subproject
  `common-lib` (java-library, no spring-boot plugin). No mavenCentral()
  duplicated in subprojects.
  ```

- **Risk**:
  1. AI ghép 2 best-practice riêng biệt → conflict (đã gặp: `repositories` block ở cả settings + subprojects làm build fail).
  2. AI dùng Spring Boot version cũ trong training data (3.2 / 3.3) thay vì 3.4.5 — phải double-check `libs.versions.toml`.
  3. AI generate exception thiếu `serialVersionUID` (gặp ở Day 1 — đã log vào [ai-junior-traps.md](../review/ai-junior-traps.md#01)).

- **Validate output**:
  1. **Chạy `./gradlew build` THẬT.** Đừng tin vào "looks good". Day 1 build đã fail mặc dù code "trông đúng".
  2. So version từng dependency với release note chính chủ — AI hay nói version không tồn tại.
  3. Compile với `-Xlint:all` để bắt warning silent (đã catch 3 warning `serial`).
  4. Đọc ADR AI draft, kiểm "Alternatives" — AI thường liệt kê 3 option nhưng option 2-3 là strawman để option 1 thắng. Phải force AI argue thật.

---

## Tech Lead Lens (Day 1 — decision-heavy day)

> Day 1 toàn quyết định kiến trúc — phần này áp dụng cho cả lúc đi
> phỏng vấn lead lẫn lúc lead 6 người ở Sotatek thật.

- **Trade-off chính + scale 10x**:
  - **Hybrid DDD/Layered** thắng "DDD cho mọi service" ở scale hiện tại (1 dev, 9 service). Scale 10x (60 dev, 30 service): **đảo ngược default** — DDD-first vì lúc đó ceremony cost rẻ hơn integration risk; thêm bounded context map; mỗi context = 1 squad. **Common-lib** scale 10x phải split thành nhiều starter (`common-web`, `common-jpa`, `common-kafka`) — không còn 1 jar god.
  - **Monorepo** scale 10x: cần build cache phân tán (Gradle Build Cache server / Develocity), CODEOWNERS theo path, merge queue. Nếu CI > 30 phút → cân nhắc Bazel hoặc tách polyrepo theo bounded context.

- **Production failure mode + 5-step triage**:
  - **Failure điển hình ở foundation Day 1**: `CorrelationIdFilter` bug → MDC leak giữa request → log của user A xuất hiện trace id của user B → debug sai user, có thể leak PII vào log.
  - **5-step triage**:
    1. Xác nhận leak có thật: grep log với 1 traceId → có > 1 userId không?
    2. Rollback filter về version cũ (revert PR) — đừng debug-trên-prod.
    3. Snapshot log 30 phút gần nhất (compliance — có thể có PII leak cần report).
    4. Reproduce ở staging với load test (trace id leak chỉ lộ dưới concurrency).
    5. Root cause: thiếu `MDC.clear()` trong `finally` — fix + test bằng 100 concurrent request kiểm trace id distinct.

- **Nếu junior + AI viết phần Day 1 này, 2 lỗi dễ nhất**:
  1. **Filter không clear MDC trong `finally`** — happy path test pass, dưới load mới lộ. Reviewer phải xem `finally` block, không phải logic chính.
  2. **`ApiResponse<T>` envelope quên handle case `T = Void`** — AI generate `ApiResponse<Object>` cho endpoint không return data, FE check `data != null` → bug. Phải có `ApiResponse.empty()` factory.

  → **Chỗ review kỹ nhất**: `finally` block trong filter, factory method của envelope, và **`equals/hashCode` của Aggregate root** (Day 4+ sẽ gặp — AI rất hay viết sai).

---

## Related

- ADR: [`docs/decisions/001-why-hybrid-architecture.md`](../decisions/001-why-hybrid-architecture.md)
- Lesson: [`docs/lessons/01-monorepo-vs-polyrepo.md`](../lessons/01-monorepo-vs-polyrepo.md)
- Architecture: [`docs/architecture/system-overview.md`](../architecture/system-overview.md)
- Code:
  - [`common-lib/.../response/ApiResponse.java`](../../common-lib/src/main/java/com/ecom/common/response/ApiResponse.java)
  - [`common-lib/.../web/CorrelationIdFilter.java`](../../common-lib/src/main/java/com/ecom/common/web/CorrelationIdFilter.java)
  - [`common-lib/.../exception/GlobalExceptionHandler.java`](../../common-lib/src/main/java/com/ecom/common/exception/GlobalExceptionHandler.java)
