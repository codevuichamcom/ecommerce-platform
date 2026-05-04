# Lesson — Monorepo vs Polyrepo (Gradle multi-project)

> **TL;DR**: Mình chọn **monorepo Gradle (Kotlin DSL) + Version Catalog**
> cho project này. 1 dev + 30 ngày + ôn phỏng vấn → tối ưu cho speed +
> learning. Khi team thật / scale lớn / multi-language → đánh giá lại
> sang polyrepo hoặc Bazel.

---

## Khái niệm 1 phút

- **Polyrepo**: mỗi service 1 repo. Build/CI/CD/release độc lập.
- **Monorepo**: tất cả service trong 1 repo. Build có thể chia subproject
  (Gradle multi-project, Maven multi-module) hoặc theo workspace (Bazel,
  Nx, Pants).

---

## Khi nào dùng monorepo

✅ **Dùng được khi**:

- Team < 10 người, share code chung nhiều (`common-lib`, internal SDK).
- Muốn 1 PR đụng nhiều service (refactor cross-cutting).
- CI/CD pipeline chưa đủ trưởng thành để publish artifact riêng.
- Tốc độ ramp-up dev mới quan trọng — clone 1 phát có cả hệ thống.

❌ **KHÔNG dùng khi**:

- Team đông + ownership rõ ràng + release cadence khác nhau giữa
  service.
- Có service ngôn ngữ khác (Go, Python, Rust) — Gradle multi-language
  có support nhưng cost cao; lúc đó nhảy lên Bazel hoặc polyrepo hợp lý
  hơn.
- Repo quá lớn (10GB+, 1M+ LoC), IDE chậm, git operations chậm.

---

## Tại sao **Gradle** (không phải Maven) cho project này

| Tiêu chí                    | Maven                          | **Gradle (Kotlin DSL)** ✓     |
| --------------------------- | ------------------------------ | ----------------------------- |
| Build speed (incremental)   | Chậm (~2-3x)                   | Nhanh hơn — task graph + cache|
| DSL                         | XML (verbose, không type-safe) | Kotlin DSL (autocomplete, refactorable) |
| Version management          | `<dependencyManagement>` trong pom | **Version Catalog** (`gradle/libs.versions.toml`) |
| Multi-language              | Tệ                             | OK (Kotlin, Scala, Groovy native; JS qua plugin) |
| Configuration cache         | Không có                       | Có (skip cấu hình ở rebuild)  |
| Build cache (cross-machine) | Hạn chế                        | Có (Gradle Build Cache, remote cache) |
| Convention plugins          | Khó (parent pom inheritance)   | Dễ (`buildSrc/` hoặc precompiled script plugin) |

Đánh đổi:
- Gradle DSL flex hơn → dễ trở thành "build script lộn xộn" nếu
  không kỷ luật. Mitigated bằng Version Catalog (single source of truth).
- Thị trường VN ~80% project Spring Boot dùng Maven → câu hỏi phỏng
  vấn về Maven vẫn cần biết. Tôi chấp nhận trade-off này, **học Maven
  ở mức trả lời được câu hỏi cơ bản** (lifecycle, scope, dependencyManagement).

---

## Cấu trúc đã chọn

```
ecommerce-platform/
├── settings.gradle.kts           # include subprojects
├── build.gradle.kts              # config chung cho subprojects
├── gradle.properties             # JVM args, parallel, cache
├── gradle/
│   ├── libs.versions.toml        # Version Catalog (TOML format)
│   └── wrapper/                  # gradle wrapper
├── common-lib/
│   └── build.gradle.kts          # library module
├── services/
│   ├── auth-service/
│   │   └── build.gradle.kts      # apply spring-boot plugin
│   └── ...
└── frontend/
    └── ...
```

### Tại sao **Version Catalog** là đáng?

Ngày xưa Maven phải:

```xml
<properties>
    <spring-boot.version>3.4.5</spring-boot.version>
</properties>
<dependencyManagement>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>${jjwt.version}</version>
    </dependency>
</dependencyManagement>
```

Gradle Version Catalog (`libs.versions.toml`) — cô đọng, type-safe,
IDE autocomplete:

```toml
[versions]
spring-boot = "3.4.5"
jjwt = "0.12.6"

[libraries]
jjwt-api = { module = "io.jsonwebtoken:jjwt-api", version.ref = "jjwt" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

Trong build script:

```kotlin
dependencies {
    implementation(libs.jjwt.api)   // IDE autocomplete cho `libs.*`
}

plugins {
    alias(libs.plugins.spring.boot)
}
```

Renovate / Dependabot hiểu format này → auto-PR khi có version mới.

---

## Cạm bẫy thực tế

### 1. Chia sẻ code chung — sướng quá thành nghiện

Khi `common-lib` ở cùng repo, ai cũng muốn ném mọi thứ vào đó. Sau 6
tháng: `common-lib` thành "god JAR" — sửa 1 dòng → rebuild 9 services.

**Rule cứng cho project này**: `common-lib` CHỈ chứa cross-cutting
infrastructure (response, exception, audit, MDC). KHÔNG chứa bất kỳ
domain class nào.

### 2. "1 PR đổi nhiều service" → tạm tiện, dài hạn nguy

Đổi 1 lần 5 service trong 1 PR thì OK lúc đầu. Nhưng cũng là dấu hiệu
service boundary đang vỡ. Nếu thấy mình hay làm vậy → review lại
boundary, không phải khen monorepo.

### 3. Configuration cache — đẹp nhưng dễ vỡ

`org.gradle.configuration-cache=true` nhanh kinh khủng nhưng:
- Plugin nào không support → fail.
- Truy cập file system trong build script kiểu cũ → fail.
- Nếu fail nhiều, tạm `org.gradle.configuration-cache.problems=warn` để
  thấy mà không block.

### 4. `bootJar` vs `jar` cho `common-lib`

Spring Boot plugin tự disable `jar` task → service consumer không
import được. Solution: `common-lib` **KHÔNG apply spring-boot plugin**,
chỉ apply `java-library` + `io.spring.dependency-management`. Đó là
lý do cấu trúc `common-lib/build.gradle.kts` của project này không có
`org.springframework.boot` plugin.

---

## Trả lời phỏng vấn

> *"Tại sao em dùng monorepo + Gradle cho project này?"*

> Tôi chọn monorepo Gradle multi-project vì 3 lý do:
> 1. **Solo dev** — không có overhead cross-repo CI.
> 2. **Có shared infrastructure code** (`common-lib`) cần share giữa 9
>    service. Polyrepo phải publish artifact lên Nexus → tốn setup.
> 3. **Tốc độ ramp-up cao**: clone 1 repo có toàn bộ context.
>
> Tôi chọn Gradle thay Maven vì:
> - Build incremental nhanh hơn ~2x (đặc biệt khi rebuild 1 module).
> - Version Catalog (TOML) cleaner hơn `<dependencyManagement>` XML.
> - Configuration cache + parallel build ra-of-the-box.
> - Kotlin DSL type-safe, IDE refactor được.
>
> Trade-off tôi ý thức: thị trường VN dùng Maven nhiều hơn → câu hỏi
> phỏng vấn về Maven tôi vẫn ôn ở mức cơ bản.

> *"Khi nào em sẽ chuyển sang polyrepo?"*

> Khi đạt 1 trong 3 điều kiện:
> - Có > 1 team với release cadence khác nhau.
> - Service mới không phải JVM (vd Go cho gateway hiệu năng cao).
> - `common-lib` cần versioning độc lập vì breaking change.

> *"Gradle vs Maven — đâu là điểm yếu thật của Gradle?"*

> Build script flex quá có thể trở thành script lộn xộn. Mitigated
> bằng:
> - **Version Catalog** ép tất cả version vào 1 chỗ.
> - **Convention plugin** (`buildSrc` hoặc precompiled script plugin)
>   ép cấu hình chung — services không có quyền override tùy ý.
> - Code review nghiêm với mọi thay đổi `build.gradle.kts`.

---

## Related

- Code: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`,
  `common-lib/build.gradle.kts`
- ADR: [`docs/decisions/001-why-hybrid-architecture.md`](../decisions/001-why-hybrid-architecture.md)
