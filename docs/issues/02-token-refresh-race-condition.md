# 🔥 Issue 02 — Refresh token race condition

## 1. Problem

User mở 2 tab cùng lúc, cả 2 tab access token expire gần nhau → cả 2 cùng gọi `POST /auth/refresh` với CÙNG 1 refresh token cũ. Nếu không xử lý atomic, cả 2 đều thắng → 2 cặp token mới được issue, refresh token cũ revoke 2 lần. Một trong 2 tab bị "mất session" sau vài giây vì token bị tab kia ghi đè.

## 2. Symptoms

- Log app:
  ```
  WARN  RefreshTokenService — Refresh hash=ab12... rotated successfully (count=1)
  WARN  RefreshTokenService — Refresh hash=ab12... rotated successfully (count=1)   ← cùng hash, 2 lần
  ```
- User report: "Vừa F5 lại 2 tab thì bị logout liên tục."
- Metric: `auth_refresh_total{result="ok"}` đột biến tăng 2x trong window 10ms; tỉ lệ `/refresh` -> 401 follow-up cao bất thường.
- DB: `refresh_tokens` table có nhiều row có cùng `user_id` nhưng `revoked_at` rất gần nhau, suy hoài không hiểu.

## 3. Root cause

Code naive ban đầu (KHÔNG atomic):

```java
RefreshToken token = repo.findByTokenHash(hash);    // T1: tab A đọc, valid
if (token.isActive(now)) {                          // T2: tab B đọc, valid
    token.setRevokedAt(now);                        // T3: tab A revoke
    repo.save(token);
    issueNew(...);
}
```

Race window: từ `findByTokenHash` đến `save` có thể vài ms. 2 thread cùng pass check `isActive`, cùng issue token mới. Đây là **lost update** (theo Postgres terms) — read-then-write KHÔNG atomic ở isolation level mặc định READ COMMITTED.

## 4. Approaches compared

| Approach | Pros | Cons |
| -------- | ---- | ---- |
| **(A) DB unique constraint + atomic UPDATE** | DB-native, không infra mới; atomic guarantee mạnh | Chỉ 1 refresh-at-a-time per token (đó là điều mong muốn) |
| **(B) Redis SET NX với key = token_hash** | Atomic ở Redis, fast | Add infra mới chỉ để giải bài toán này; lock TTL phải đoán; Redis fail open thì bug quay lại |
| **(C) Optimistic lock `@Version` trên RefreshToken** | JPA pattern quen; rollback retry tự nhiên | Cần retry loop ở caller; throw `OptimisticLockException` khi conflict — UX không khác (A) |
| **(D) Pessimistic `SELECT FOR UPDATE`** | Mạnh tay, an tâm | Lock row lâu = giảm throughput; deadlock risk; overkill cho path này |

## 5. Chosen approach + Why

**(A) DB unique constraint + atomic UPDATE.**

Code hiện tại — `RefreshTokenRepository.revokeIfActive`:

```java
@Modifying
@Query("""
        UPDATE RefreshToken rt
           SET rt.revokedAt = :now
         WHERE rt.tokenHash = :hash
           AND rt.revokedAt IS NULL
        """)
int revokeIfActive(@Param("hash") String tokenHash, @Param("now") Instant now);
```

Caller pattern:

```java
int updated = repo.revokeIfActive(hash, now);
if (updated == 0) throw new BusinessException(AUTH_TOKEN_INVALID);
```

DB UPDATE là atomic ở row level (Postgres MVCC). 2 thread cùng UPDATE — đúng 1 thread thấy `rowsAffected=1`, thread kia thấy `0`. Loser bị reject với `AUTH_TOKEN_INVALID`. Tab thua sẽ trigger silent re-login (Day 27 sẽ implement ở frontend).

**Lý do KHÔNG chọn (B/C/D)**:
- (B) Redis chưa cần ở Day 2 (Day 5 mới setup). Add cho 1 use case = premature. Nếu có sẵn Redis vẫn xét lại.
- (C) Optimistic lock đúng về kỹ thuật nhưng JPA throw exception → handler phức tạp hơn `if (updated == 0)`. Đồng kết quả, code rườm hơn.
- (D) Pessimistic overkill. Lock row vài ms để revoke 1 dòng — không xứng trade-off throughput.

## 6. Fix

Code: [`services/auth-service/src/main/java/com/ecom/auth/repository/RefreshTokenRepository.java`](../../services/auth-service/src/main/java/com/ecom/auth/repository/RefreshTokenRepository.java) (`revokeIfActive`).

Caller: [`RefreshTokenService.rotate`](../../services/auth-service/src/main/java/com/ecom/auth/service/RefreshTokenService.java).

Migration: [`V2__create_refresh_tokens.sql`](../../services/auth-service/src/main/resources/db/migration/V2__create_refresh_tokens.sql) — `token_hash UNIQUE` (chống duplicate insert), `revoked_at NULLable`.

## 7. Prevention

- **Test**: smoke test bằng curl đã verify (`refresh` lần 2 với token cũ → 401 `AUTH_TOKEN_INVALID`). Day 2 integration test (Testcontainers) có case `refresh_rotation_invalidatesOldToken`.
- **Lint**: code review trap → flag pattern `findBy → if check → save` ở refresh path. Append vào [`docs/review/ai-junior-traps.md`](../review/ai-junior-traps.md).
- **Monitor**: alert nếu `auth_refresh_failed_total{reason="already_used"}` > 1% baseline → có thể là token theft (reuse detection sẽ harden Day 12).

## 8. Trade-off accepted

Nếu user mở 5 tab cùng F5 → 4/5 tab sẽ thấy `AUTH_TOKEN_INVALID` ở `/refresh`. Mỗi tab phải tự re-login hoặc đợi tab thắng phát broadcast token mới (qua BroadcastChannel API ở frontend). Day 27 implement.

Cách khác là cho cả 5 tab cùng dùng kết quả của tab thắng (race-to-cache pattern) — code phức tạp hơn nhiều, để dành cho production thực sự cần.

## 9. Related

- Code: [`services/auth-service/src/main/java/com/ecom/auth/service/RefreshTokenService.java`](../../services/auth-service/src/main/java/com/ecom/auth/service/RefreshTokenService.java)
- Code: [`services/auth-service/src/main/java/com/ecom/auth/repository/RefreshTokenRepository.java`](../../services/auth-service/src/main/java/com/ecom/auth/repository/RefreshTokenRepository.java)
- Lesson: [`lessons/02-jwt-vs-session.md`](../lessons/02-jwt-vs-session.md)
- ADR: [`decisions/002-jwt-vs-session.md`](../decisions/002-jwt-vs-session.md)
- Future: Day 4 — optimistic locking khác cho domain (Stock); Day 19 — distributed lock alternatives (Redlock, fencing token).
