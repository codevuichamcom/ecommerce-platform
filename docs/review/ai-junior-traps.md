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

### [03] Premature DRY — extract khi mới có 2 chỗ trùng

- **Gặp ở**: Day 3 — `JwtAuthenticationFilter` ở product-service. AI bot đề xuất "extract lên common-lib ngay" sau khi thấy auth-service và product-service có pattern giống.
- **AI viết**: gợi ý lift class lên common-lib với `AbstractFilterTemplate<T extends Auth>` 1 generic + 3 hook method, dù chỉ 2 use case và auth-service principal có 4 field còn product-service chỉ 3 field — 2 chỗ "giống bề mặt" nhưng contract đã khác.
- **Tại sao sai**: 2 chỗ giống ≠ 2 chỗ sẽ tiến hóa cùng hướng (Sandi Metz: *"duplication is far cheaper than the wrong abstraction"*). Auth-service principal có `tokenVersion` cho invalidation; verify-only chỉ cần `userId/email/role`. Ép contract chung → auth-service mất feature, hoặc abstraction phải có generic phức tạp che đi rốt cuộc 2 caller vẫn override 50%.
- **Đúng phải là**: Day 3-6 chấp nhận duplicate **có chủ ý**, comment `// Day 7 sẽ lift sau khi rule of three`. Day 7 verify đủ 4 verify-only service (product/inventory/cart/order) trùng → chỉ extract cho 4 service đó. Auth-service giữ riêng vì principal khác structure. Xem [lesson 07](../lessons/07-refactor-extract-discipline.md).
- **Câu hỏi review**: "Có **3** chỗ thật cùng pattern chưa, hay mới 2? Contract đã stable ≥ 1 sprint chưa? Service nào KHÔNG fit pattern (do contract khác) — có dám lý luận tại sao không lift cả thằng đó không?"
- **Tag**: #abstraction #ai-pattern-conflict #refactor

---

### [04] Auto-config kéo dependency vào service không cần

- **Gặp ở**: Day 7 — [common-lib/SecurityAutoConfiguration.java](../../common-lib/src/main/java/com/ecom/common/autoconfig/SecurityAutoConfiguration.java) (đã fix trước khi commit)
- **AI viết**: `@Configuration class SecurityAutoConfiguration { @Bean JwtVerifier jwtVerifier(JwtVerifyProperties p) { ... } @Bean JwtAuthenticationFilter jwtFilter(...) { ... } }` — không có condition. Common-lib gắn vào notification-service Day 11 (Kafka-only, không Web) → `ClassNotFoundException: UsernamePasswordAuthenticationFilter` lúc context start.
- **Tại sao sai**: AI hiểu auto-config = "tự động register" nhưng skip layer condition. Common-lib là **shared infrastructure**, phải opt-in. Service nào không cần Web/Security thì auto-config phải tự tắt — AI không biết notification-service Kafka-only.
- **Đúng phải là**: 3 layer condition kết hợp:
  ```java
  @ConditionalOnClass({Jwts.class, UsernamePasswordAuthenticationFilter.class})
  @ConditionalOnProperty(prefix = "auth.jwt", name = "secret")
  @EnableConfigurationProperties(JwtVerifyProperties.class)
  ```
  Plus `@ConditionalOnMissingBean` ở từng `@Bean` để service override custom filter được. Common-lib `build.gradle.kts` dùng `compileOnly` cho jjwt + spring-security-web, KHÔNG `api`/`implementation` (consumer service tự kéo).
- **Câu hỏi review**: "Auto-config này có activate sai service không? Notification (Kafka-only), batch job (no Web), CLI tool (no Spring) — chạy thử mental thay vì assume luôn fit."
- **Tag**: #spring-boot #auto-config #dependencies #ai-pattern-conflict

---

### [05] Catch-all `RuntimeException` ép retry storm thành message loss

- **Gặp ở**: Day 14 review — [inventory/OrderCreatedConsumer.java:65-71](../../services/inventory-service/src/main/java/com/ecom/inventory/infrastructure/messaging/OrderCreatedConsumer.java#L65-L71)
- **AI viết**: `catch (RuntimeException ex) { log.warn(...); }` với comment "tránh retry storm cho case stock hết thật". Catch-all bao luôn `CannotAcquireLockException` (DB down) + `OptimisticLockingFailureException` exhausted + `InsufficientStockException` (stock hết).
- **Tại sao sai**: AI nắm 1 nửa requirement ("stock hết → đừng retry") và biến nó thành **catch-all** thay vì **catch riêng exception type đó**. Failure mode thật: DB failover 12s, mọi reserve throw infra exception → swallow → ack → message MẤT. Comment Day 9 hứa "Day 11 sẽ dedup" — nhưng Day 11 chỉ làm cho notification, inventory bị quên. **Debt ẩn**: code nói "sẽ fix", không ai track, fix không xảy ra.
- **Đúng phải là**:
  ```java
  } catch (InsufficientStockException ex) {
      publisher.publishReserveFailed(...);  // compensation, không retry
  } catch (RuntimeException ex) {
      throw ex;  // infra error → retry/DLT pipeline xử lý
  }
  ```
- **Câu hỏi review**: "Catch-all có phân biệt được **business failure** (đừng retry) và **infra failure** (phải retry) không? Comment 'sẽ fix sau' có ai track không?"
- **Tag**: #correctness #error-handling #debt-tracking

---

### [06] Dedup release sau side effect — duplicate dispatch

- **Gặp ở**: Day 14 review — [notification/OrderCreatedConsumer.java:81-87](../../services/notification-service/src/main/java/com/ecommerce/notification/consumer/OrderCreatedConsumer.java#L81-L87)
- **AI viết**:
  ```java
  try {
      notificationChannel.send(payload);  // email SENT
      log.info(...);  // ← nếu throw ở đây ...
  } catch (RuntimeException ex) {
      deduplicator.release(event.eventId());  // ← release sai
      throw ex;
  }
  ```
- **Tại sao sai**: `send()` đã thực hiện side effect (email ra ngoài). Bất kỳ exception sau `send()` đều **không nên** release dedup — vì retry sẽ send LẦN 2 = user nhận 2 email. AI ghép pattern "release on failure" mà không hỏi "failure trước hay sau side effect". Probability thấp nhưng đúng/sai principle.
- **Đúng phải là**: flag `dispatched` track explicit, release CHỈ khi `!dispatched`.
- **Câu hỏi review**: "Release dedup có phân biệt được failure **trước** vs **sau** side effect không? Side effect có rollback được không (email gửi rồi không rollback được)?"
- **Tag**: #idempotency #subtle #side-effect-ordering

---

### [07] ES Java client API cũ (7.x) không khớp 8.15 — `RangeQuery` tagged union

- **Gặp ở**: Day 22 build — [product/search/ProductSearchService.java](../../services/product-service/src/main/java/com/ecom/product/search/ProductSearchService.java)
- **AI viết** (compile fail):
  ```java
  b.filter(f -> f.range(r -> {
      r.field("price");                 // ❌ field() không có trên RangeQuery.Builder 8.15
      r.gte(JsonData.of(min));          // ❌
  }));
  ```
- **Tại sao sai**: ES Java client 8.14+ đổi `RangeQuery` thành **tagged union**
  (`untyped` / `date` / `number` / `term`). Method `field/gte/lte` nằm trên
  `UntypedRangeQuery.Builder`, không trên `RangeQuery.Builder`. AI generate theo memory
  API 7.x / 8.early → "cannot find symbol method field". Tương tự `withHighlightQuery`
  cần `HighlightQuery` (wrap `Highlight`), không nhận `Highlight` trực tiếp.
- **Đúng phải là**: `f.range(r -> r.untyped(u -> u.field("price").gte(JsonData.of(min))))`.
- **Câu hỏi review**: "Code ES client này compile + chạy trên ĐÚNG version trong BOM
  chưa, hay AI nhớ API version khác? Đã chạy integration test thật trên container
  chưa?" — API ES client đổi nhiều giữa minor version; **đừng tin code 'trông đúng',
  phải compile + IT thật**.
- **Tag**: #elasticsearch #api-version-drift #ai-stale-knowledge #verify-by-running

---

## Top recurring AI failure modes (cập nhật khi gặp đủ 3+ ví dụ)

> Sau ≥3 entry cùng pattern, lift lên đây thành "rule of thumb".

- **AI ghép 2 best-practice → conflict** (entry [02], [03], [04] đều có dấu hiệu này): AI generate từng đoạn từ best-practice riêng biệt mà không reasoning về interaction global. Heuristic review: mỗi config block tự hỏi "có chỗ khác cùng concern không, ai win".

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
