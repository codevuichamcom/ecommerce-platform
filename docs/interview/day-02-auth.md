# 🎤 Day 02 — Auth Service Interview

> Build deliverable: [`services/auth-service/`](../../services/auth-service/) — JWT stateless + refresh rotation + Virtual Threads + Records.

---

## 🏢 Bối cảnh giả lập (task mô phỏng công ty thật)

- **Company**: ShopFast — ecommerce startup Việt Nam giai đoạn pre-Series A, đang build platform microservice thay cho monolith PHP cũ.
- **Role giao việc**: CTO (ex-Shopee Tech Lead). Sprint planning đầu tuần, ép deliver auth foundation vì 8 service khác đều block chờ identity layer.
- **Bạn**: Senior Backend Engineer, own auth-service end-to-end (design + code + ops handoff). Solo trên service này, không có team.
- **Reviewer**: Tech Lead + 1 Security Engineer. Security đặc biệt soi: revoke flow, password hash cost, refresh storage, error message leak (user enumeration).
- **Deadline**: 3 ngày calendar (~12h focused work). Demo cuối Day 3: register / login / refresh chạy được trên Postgres thật + present trade-off với Security trong 10 phút.
- **Constraint thực tế**:
  - Frontend (React SPA, mobile sau) đã chốt `Authorization: Bearer` — không session cookie.
  - Compliance Việt Nam chưa ép revoke instant (không phải banking) → JWT stateless OK, hybrid cho panic case.
  - Hạ tầng đã có Postgres + Docker Compose, **chưa có Redis** (Day 5 mới setup) → mọi giải pháp dựa Redis bị reject ở Day 2.
- **Definition of Done** (CTO ký):
  1. 4 endpoint pass smoke test trên Postgres thật.
  2. Refresh rotation atomic — chứng minh chống race 2 tab (atomic UPDATE hoặc test).
  3. Password BCrypt + token storage hash, không plaintext nào trong DB.
  4. 1 ADR + ≥1 issue doc (trade-off đã chọn, alternatives đã reject) — Tech Lead đọc trước review.
  5. Virtual threads bật, verify bằng endpoint trả `Thread.isVirtual()=true`.

---

## Q1. Bạn có dùng JWT cho service auth không? Tại sao chọn JWT thay vì session?

**Strong answer**:

> Có. Lý do chính: hệ thống có 9 microservice, không muốn mỗi request mỗi service phải hit chung 1 session store. JWT stateless — mỗi service tự verify chữ ký HS256 + parse claim, 0 round-trip ra ngoài. Frontend là SPA + tương lai có mobile, header `Authorization: Bearer` đơn giản hơn cookie cross-domain.
>
> Trade-off chấp nhận: không revoke token instant trước expire. Mitigate bằng access TTL ngắn 15 phút + refresh token có state ở DB, rotate mỗi lần dùng. Khi cần "panic button" — bump `tokenVersion` ở user record → invalidate toàn bộ JWT cũ (cost: 1 DB lookup ở filter, sẽ cache Redis ở Day 15).

**Follow-up traps**:

- *"Stateless mà lại có DB lookup tokenVersion thì còn stateless gì nữa?"*
  → Đúng. Không pretend stateless. Đó là **hybrid có chủ ý**: 99% case JWT verify chữ ký xong là pass (stateless), chỉ panic mode mới hit DB. Banking compliance ép revoke instant thì hybrid Redis blacklist — mất luôn benefit, ops 2 store. Không free lunch.

- *"Vì sao 15 phút? Sao không 1 giờ?"*
  → 15 phút = blast radius khi token leak. 1h là common, nhưng cho ecommerce có payment thì 15 hợp lý hơn. Số cụ thể tùy business — quan trọng là argue được trade-off.

---

## Q2. BCrypt vs SHA-256 + salt — chọn cái nào?

**Strong answer**:

> BCrypt cho password user. Lý do:
> 1. **Adaptive cost factor** — chỉnh được, hardware nhanh hơn thì bump cost lên. SHA-256 chạy ~µs trên GPU, brute-force dễ.
> 2. **Deliberately slow** — BCrypt cost=10 ~100ms, GPU rig tốt nhất cũng chỉ ~10k hash/s vs SHA-256 cả tỉ.
> 3. **Built-in salt** — chống rainbow table; không cần tự manage salt column.
>
> Cost factor 10 là dev default, prod tôi đo trên hardware thật target ~250ms/hash → cost 12. Bump cao hơn nữa thì login latency user cảm nhận được.
>
> **Quan trọng**: BCrypt CHỈ cho password (slow là feature). Refresh token thì SHA-256 đủ — input đã 32 bytes random nên không có rainbow table risk, không cần slow.

**Follow-up traps**:

- *"Argon2 thì sao?"*
  → Modern hơn (memory-hard, chống GPU mạnh hơn BCrypt). Chuẩn OWASP 2023+ recommend Argon2id. Day 2 dùng BCrypt vì Spring Security default + đủ tốt cho ecommerce; nếu compliance ép thì migrate được (encoder chain).

- *"BCrypt có giới hạn 72 bytes — sao bạn validate password ≤ 72?"*
  → Đúng. BCrypt truncate password > 72 bytes → 2 password khác nhau có thể hash giống. Validate ở DTO (`@Size(max=72)`) chống edge case này.

---

## Q3. Refresh token rotation — vì sao cần rotate, không issue 1 token rồi xài 7 ngày?

**Strong answer**:

> Rotate để **detect token theft**. Flow: mỗi lần `/refresh` issue cặp token mới + revoke token cũ. Nếu attacker steal được refresh, dùng → ok. Nhưng victim sau đó dùng (cùng token cũ) → DB UPDATE atomic chỉ thắng 1 lần, victim sẽ thấy `AUTH_TOKEN_INVALID` → trigger reuse detection.
>
> Reuse detection (Day 12 sẽ harden): khi thấy refresh đã revoke được dùng lại → assume theft → revoke TOÀN BỘ refresh của user đó + alert. User phải re-login. Cost: false positive khi user dùng 2 device cùng lúc — chấp nhận được.

**Follow-up traps**:

- *"2 tab F5 cùng lúc thì thua?"*
  → Đúng. Race condition. Issue: [`02-token-refresh-race-condition.md`](../issues/02-token-refresh-race-condition.md). Atomic UPDATE chỉ 1 thread thắng, thread thua thấy 401. Frontend solution: BroadcastChannel cho tabs share kết quả tab thắng (Day 27).

- *"Lưu refresh plaintext trong DB được không cho dễ debug?"*
  → KHÔNG. DB leak = attacker có refresh của mọi user → issue access bypass mọi check. Phải hash. SHA-256 đủ vì input đã random — BCrypt cost cho 32-byte random là phí (BCrypt cho password vì password có entropy thấp, dễ rainbow).

---

## Q4. Virtual threads trong Spring Boot 3 — endpoint auth có lợi gì?

**Strong answer**:

> `spring.threads.virtual.enabled=true` → Tomcat dispatch request lên virtual thread. Lợi cho **IO-bound workload** — vd auth endpoint chờ DB query, virtual thread block không occupy platform thread, scale concurrent request dễ. Test Day 2 verify `Thread.currentThread().isVirtual() == true` ở `/auth/me` → ✅.
>
> **Trap quan trọng**: BCrypt là **CPU-bound**, không phải IO. Virtual thread KHÔNG tăng tốc 1 lần hash, chỉ cho phép nhiều request hash song song mà không cần 1000 platform thread. Throughput tăng, latency 1 request không đổi.
>
> **Pinning gotcha** (Day 19 sẽ benchmark): nếu code có `synchronized` block + virtual thread bên trong → pin lên platform thread = mất benefit. Spring 6.1 + Tomcat 10.1 đã fix nhiều, nhưng JDBC driver / lib third-party có thể vẫn pin. Phải test thật, không assume.

**Follow-up traps**:

- *"Virtual thread thì có cần connection pool nữa không?"*
  → Vẫn cần. Virtual thread giải bài toán "1 request 1 thread" cost; connection pool giải bài toán "DB chỉ chấp nhận N concurrent connections". 2 tầng khác nhau. HikariCP vẫn dùng bình thường, có thể bump max-pool-size lên vì không lo thread starvation phía app.

- *"Khi nào KHÔNG nên dùng virtual thread?"*
  → CPU-bound workload (image processing, BCrypt batch). Virtual thread không tăng throughput CPU — chỉ tăng số thread, mà CPU cores có hạn. Dùng `ForkJoinPool` cho parallel CPU work.

---

## Q5. Record DTO trong Spring Boot 3 — dùng có pitfall gì không?

**Strong answer**:

> Record = immutable, auto `equals/hashCode/toString`, code gọn. Dùng cho:
> - Request/response DTO (`RegisterRequest`, `TokenResponse`).
> - Value object (`AuthUserPrincipal`).
> - `@ConfigurationProperties` (`JwtProperties`).
>
> Pitfall:
> 1. **Bean Validation** trên record component cần Java 16+ — `record RegisterRequest(@Email String email)` work.
> 2. **JPA entity KHÔNG được record** — JPA cần no-args constructor + setter cho proxy. Day 2 entity vẫn dùng class + Lombok.
> 3. **Jackson deserialize** record cần `-parameters` compile flag để map property name (đã set ở [`build.gradle.kts`](../../build.gradle.kts) root).
> 4. **MapStruct** mapping into record cần version 1.5+ (đã có 1.6.3).

**Follow-up traps**:

- *"Record có support inheritance không?"*
  → Không extends class khác (chỉ implicit Record). Implements interface OK. Không dùng được khi cần inheritance hierarchy → dùng class.

- *"Record với Lombok va chạm gì không?"*
  → Lombok không impact record (record tự generate accessor). Có thể dùng `@Builder` trên record (Lombok 1.18.20+) nhưng hơi rườm — thường viết static factory method.

---

## 🤖 AI Playbook (Day 2)

- **AI làm tốt**: Flyway migration, JPA entity boilerplate, DTO record, controller skeleton, Testcontainers config. Pattern lặp, AI generate nhanh + chính xác.
- **Prompt mẫu**:
  > "Generate Spring Boot 3.4 stateless JWT SecurityConfig: permitAll cho /auth/register, /auth/login, /auth/refresh; require auth còn lại; HttpStatusEntryPoint trả 401 JSON; KHÔNG dùng WebSecurityConfigurerAdapter (deprecated từ Spring Security 5.7)."
- **Risk**:
  - AI hay generate `WebSecurityConfigurerAdapter` cũ → Spring Security 6.x đã xóa.
  - Hardcode JWT secret → leak ngay.
  - `Jwts.parser()` deprecated jjwt 0.12 → phải dùng `Jwts.parser().verifyWith(key).build()`.
  - BCrypt cost 4 (test mode default) lọt vào prod → security hole.
- **Validate**:
  - Đọc kỹ filter chain order, exception handling — phần dễ AI hỏng nhất.
  - Verify endpoint chạy trên virtual thread bằng `Thread.currentThread().isVirtual()` (đã làm ở `/auth/me`).
  - Smoke test 2 path: refresh rotation lần 2 phải fail (atomic), wrong password phải trả `AUTH_INVALID_CREDENTIALS` chứ không leak "user not found".

---

## 🧠 Senior mindset notes

- **Đừng pretend stateless** khi đã có refresh DB + tokenVersion. Hybrid có chủ ý ≠ stateless thuần. Argue đúng trade-off.
- **Same error message cho `email không tồn tại` vs `password sai`** → chống user enumeration. Junior hay leak "User not found" ở login.
- **JWT secret lấy từ env**, fail-fast khi secret < 32 bytes (`auth.jwt.secret` validate ở `JwtService` constructor). Tốt hơn là silent run với secret yếu rồi prod mới phát hiện.
- **Test thật trên Postgres thật** (Testcontainers) — H2 không reproduce isolation level + UPDATE atomic behavior. Day 2 gặp Docker compat issue → smoke test thay thế, doc lại ở [`02b`](../issues/02b-testcontainers-docker-desktop-29.md).

## Related

- ADR: [`decisions/002-jwt-vs-session.md`](../decisions/002-jwt-vs-session.md)
- Lesson: [`lessons/02-jwt-vs-session.md`](../lessons/02-jwt-vs-session.md)
- Issue: [`issues/02-token-refresh-race-condition.md`](../issues/02-token-refresh-race-condition.md), [`issues/02b-testcontainers-docker-desktop-29.md`](../issues/02b-testcontainers-docker-desktop-29.md)
- Code: [`services/auth-service/`](../../services/auth-service/)
