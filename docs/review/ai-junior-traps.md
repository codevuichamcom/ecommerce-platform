# AI + Junior Code Review Traps — Cumulative Checklist

> **Mục đích**: tích lũy các pattern lỗi thật gặp khi review code do AI hoặc
> junior viết, qua 30 ngày build platform này. Đây KHÔNG phải lý thuyết
> sách giáo khoa — chỉ ghi entry sau khi **gặp lỗi thật trong code**.
>
> **Cách dùng khi review PR ở team Sotatek**: scan checklist này trước khi
> approve. Mỗi entry là 1 câu hỏi cần check.
>
> **Cách dùng khi phỏng vấn**: khi interviewer hỏi "anh review code AI
> thế nào?", có ammo cụ thể chứ không nói chung chung.

---

## Format mỗi entry

```
### [NN] Tên trap (1 dòng tóm tắt)

- **Gặp ở**: Day X, file Y (link)
- **AI/junior viết**: pattern sai (code snippet ngắn)
- **Tại sao sai**: root cause, không phải symptom
- **Đúng phải là**: code snippet đúng
- **Câu hỏi review**: 1 câu để hỏi reviewer/author
- **Tag**: #concurrency #security #performance #correctness #api-design
```

---

## Entries

### [01] Exception class thiếu `serialVersionUID`

- **Gặp ở**: Day 1 — [BaseException.java](../../common-lib/src/main/java/com/ecom/common/exception/BaseException.java)
- **AI viết**: `public class BaseException extends RuntimeException { ... }` — không khai báo `serialVersionUID`.
- **Tại sao sai**: `RuntimeException` implements `Serializable`. Mọi subclass mặc định compute `serialVersionUID` runtime → khi đổi field, deserialize từ version cũ throw `InvalidClassException`. Bug silent ở session replication / cache deserialize. `-Xlint:serial` warning đã chỉ mặt nhưng dễ bị skip.
- **Đúng phải là**: `private static final long serialVersionUID = 1L;`
- **Câu hỏi review**: "Class này có chuỗi `Serializable` không? Nếu có thì `serialVersionUID` đâu?"
- **Tag**: #correctness #serialization

---

### [02] `repositories` block khai báo cả ở settings + subprojects

- **Gặp ở**: Day 1 — [build.gradle.kts](../../build.gradle.kts) (đã fix)
- **AI viết**: `dependencyResolutionManagement { repositoriesMode.set(FAIL_ON_PROJECT_REPOS) }` ở `settings.gradle.kts`, đồng thời `subprojects { repositories { mavenCentral() } }` ở `build.gradle.kts`. Build fail với message khó hiểu: `repository 'MavenRepo' was added by build file`.
- **Tại sao sai**: AI generate 2 block từ 2 best-practice riêng biệt mà không nhận ra chúng conflict. Đây là pattern điển hình — AI ghép nhiều "đúng cục bộ" → "sai toàn cục".
- **Đúng phải là**: chỉ khai 1 chỗ — settings (centralized). Build file không touch `repositories`.
- **Câu hỏi review**: "Config X có khai báo ở đâu khác không? Nếu có, cái nào win, cái nào dead code?"
- **Tag**: #build #ai-pattern-conflict

---

## Top recurring AI failure modes (cập nhật khi gặp đủ 3+ ví dụ)

> Sau ≥3 entry cùng pattern, lift lên đây thành "rule of thumb".

- *(chưa đủ data — sẽ fill từ Day 2 trở đi)*

---

## Quick reference — câu hỏi luôn nên hỏi khi review AI code

1. **Edge case empty/null**: AI thường handle happy path, miss `Collections.emptyList()`, `Optional.empty()`, input null.
2. **Exception swallowed**: `catch (Exception e) { log.error(...); }` không rethrow → bug silent.
3. **Resource leak**: `InputStream`, `Connection`, `ExecutorService` không close → AI hay quên try-with-resources.
4. **Thread safety**: AI generate code có shared mutable state (HashMap, ArrayList field) mà không khai `synchronized` / `ConcurrentHashMap`.
5. **N+1 query**: JPA repository method generate trong loop → AI không thấy bottleneck.
6. **Magic constant không có context**: `Thread.sleep(5000)` — tại sao 5000? AI bịa số.
7. **Test happy-path only**: AI viết test đi qua được nhưng không test invariant.
8. **Outdated API**: AI dùng API deprecated từ training data cũ (vd: `WebSecurityConfigurerAdapter` Spring Security < 5.7).
9. **Copy-paste pattern lệch context**: pattern hợp module A nhưng AI áp vào module B sai context.
10. **Comment giải thích WHAT thay vì WHY**: dấu hiệu code do AI generate, không phải nghĩ ra.
