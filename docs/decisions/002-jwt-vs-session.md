# ADR-002 — Stateless JWT cho auth thay vì session-cookie

- **Status**: Accepted
- **Date**: 2026-05-06
- **Deciders**: Tonny (Tech Lead)
- **Supersedes**: —

## 🏗️ Decision

Auth dùng **JWT stateless** (HS256, access 15min) + **opaque refresh token** lưu DB hash, rotation atomic. KHÔNG dùng session-cookie + sticky session.

## Context

Day 2 build `auth-service` đầu tiên. 8 service khác (product, cart, order, ...) đều phải verify identity. Bài toán: chọn cơ chế auth scale cho microservice + frontend SPA + mobile.

Constraint:
- Multi-service (9 service), không muốn mỗi service hit DB lookup session mỗi request.
- Frontend = SPA (Day 26+) + mobile tương lai → cookie cross-domain rườm rà.
- Phải support **revoke ngay** khi có sự cố (password compromise, account lock).

## Alternatives considered

| # | Approach | Pros | Cons |
| - | -------- | ---- | ---- |
| A | **Session cookie + Redis store** (Spring Session) | Revoke instant (xóa key Redis); payload nhỏ; HttpOnly chống XSS lấy token | Mỗi request phải hit Redis ở mọi service → +1 round-trip; CSRF rủi ro phải XSRF token; cross-domain SPA + mobile rườm rà |
| B | **JWT stateless thuần** (chỉ access token) | 0 lookup, scale rộng; dễ cross-domain | Không revoke được trước expire; nếu access TTL dài → leak nguy; ngắn → user logout liên tục |
| C ✅ | **JWT short-TTL + refresh rotation DB-hashed** | Access 15min (blast radius hẹp); refresh dài 7d nhưng rotate mỗi lần dùng + atomic UPDATE chống race; revoke = mark `revoked_at` ở DB | Vẫn không revoke instant 100% (access còn TTL ngắn); ops thêm bảng `refresh_tokens` |
| D | **JWT + Redis blacklist** (hybrid) | Revoke instant qua blacklist | Mất luôn benefit stateless (mỗi request hit Redis check blacklist); ops tốn 2 store |

## Chosen — Rationale

**(C)** với `tokenVersion` claim làm "panic button":
- 99% case: revoke = đợi access token hết hạn (15min) + revoke refresh ở DB. Đủ.
- Sự cố nghiêm trọng (password compromise): bump `users.token_version` → JWT cũ tất cả phiên đều fail filter check. Cost = 1 DB lookup ở filter (sẽ cache Redis ở Day 15).

Lý do KHÔNG chọn:
- **(A)** Spring Session Redis cho monolith ổn, microservice 9 nodes thì mỗi request +1 Redis hop là phí. Frontend Day 26 sẽ là SPA + mobile tương lai → cookie cross-domain phiền.
- **(B)** thuần JWT là cargo-cult — không có refresh thì user phải re-login mỗi 15min, hoặc TTL dài thì leak nguy. Production không ai làm vậy.
- **(D)** mất stateless benefit, lại ops 2 store. Chỉ dùng khi compliance ép revoke instant (banking).

## Trade-offs

**Accepted**:
- Không revoke access token ngay lập tức — tối đa 15min lag. Chấp nhận.
- Refresh token có DB lookup mỗi lần `/refresh` (rare path, không phải mỗi request).
- Payload JWT (~250 bytes) lớn hơn session cookie (~50 bytes) — Bandwidth không phải bottleneck với HTTP/2.

**Rejected**:
- Refresh token bằng JWT (stateless, không lưu DB) — sẽ KHÔNG revoke được token đã issue → loại.

## Consequences

- Mỗi service có dependency `common-lib` + JWT verify filter (Day 8 sẽ extract sang `gateway-service` để service downstream chỉ trust header).
- DB `auth_db.refresh_tokens` cần TTL cleanup job — Day 11 schedule (Quartz / Spring `@Scheduled`).
- Frontend Day 27 phải implement silent refresh interceptor khi 401.

## Related

- Code: [`services/auth-service/src/main/java/com/ecom/auth/service/JwtService.java`](../../services/auth-service/src/main/java/com/ecom/auth/service/JwtService.java)
- Code: [`services/auth-service/src/main/java/com/ecom/auth/service/RefreshTokenService.java`](../../services/auth-service/src/main/java/com/ecom/auth/service/RefreshTokenService.java)
- Lesson: [`lessons/02-jwt-vs-session.md`](../lessons/02-jwt-vs-session.md)
- Issue: [`issues/02-token-refresh-race-condition.md`](../issues/02-token-refresh-race-condition.md)
- Interview: [`interview/day-02-auth.md`](../interview/day-02-auth.md)
