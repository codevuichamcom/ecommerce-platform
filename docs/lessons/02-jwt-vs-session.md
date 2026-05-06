# 🔒 Lesson 02 — JWT vs Session

## TL;DR

JWT = self-contained token, server không lưu state. Session = id-tag, server giữ state ở store. Microservice scale → JWT thắng. Monolith cần revoke instant → Session thắng. Production thực tế là **hybrid**: JWT short-TTL + refresh token có state ở DB.

## 🎯 Khi nào dùng JWT

- **Microservice / multi-service**: không muốn mỗi service hit chung 1 store check session.
- **Cross-domain client**: SPA, mobile, third-party API — cookie phiền, header `Authorization: Bearer` đơn giản.
- **Stateless infra**: load balancer round-robin không cần sticky session.
- **Federation**: cần đính role / claim để service downstream tự decide authz.

## ❌ Khi nào KHÔNG dùng JWT

- **Cần revoke instant** (banking, healthcare compliance) — JWT stateless không revoke được trước expire. Phải hybrid với blacklist.
- **Monolith server-rendered web** — session cookie + Spring Session Redis đơn giản hơn, không cần JS client manage token.
- **Token cần chứa nhiều data nhạy** — JWT payload là plaintext (base64), ai có token đều decode. Đừng nhét password / phone / address.

## ⚠️ Cạm bẫy

1. **Hardcode secret trong code** → leak repo = mọi user bị giả mạo. Luôn lấy từ env/Vault.
2. **HS256 với secret yếu** (< 32 bytes) → brute-force được. jjwt 0.12 enforce min length, fail-fast tốt.
3. **Lưu refresh token plaintext** trong DB → DB leak = attacker dùng token mới. Phải hash (SHA-256 đủ vì input đã random 32 bytes — không cần BCrypt).
4. **Không rotate refresh** → token bị steal sống cả 7 ngày. Rotate mỗi lần dùng + reuse detection.
5. **Đọc `exp` ở client** → client time có thể sai. Server mới là source of truth, đừng tin client.
6. **Để JWT trong localStorage** → XSS đọc được. Nên httpOnly cookie hoặc memory-only state cho SPA.
7. **Role nhét trong claim** → admin đổi role cho user thì JWT cũ vẫn hợp lệ tới expire. Fix bằng short TTL + check role lại từ DB ở endpoint sensitive, hoặc bump `tokenVersion`.

## 📊 Approaches compared (2 axis chính)

| Axis | JWT stateless | Session cookie + Redis | JWT + refresh DB (chosen) |
| ---- | ------------- | ---------------------- | ------------------------- |
| Per-request lookup | 0 | 1 (Redis) | 0 ở common path; 1 (DB) ở /refresh |
| Revoke speed | ❌ phải đợi expire | ✅ instant (xóa key) | 🟡 lag = access TTL |
| Cross-domain SPA + mobile | ✅ | 🟡 phiền cookie | ✅ |
| Payload size | 🟡 ~250B | ✅ ~50B | 🟡 ~250B |
| Ops complexity | ✅ thấp | 🟡 vừa | 🟡 vừa |

## 🎤 Trả lời phỏng vấn

**Q: "Tại sao chọn JWT cho microservice?"**

> Stateless. 9 service không cần share session store. Mỗi service tự verify chữ ký + parse claim — 0 round-trip ra ngoài. Trade-off là không revoke instant, mitigate bằng access TTL ngắn (15min) + refresh rotation. Banking compliance ép revoke instant thì hybrid Redis blacklist, nhưng mất benefit stateless.

**Q: "JWT stolen rồi làm sao?"**

> 3 lớp: (1) access TTL 15min — blast radius hẹp; (2) refresh rotation + reuse detection — attacker dùng token đã rotate sẽ bị reject + alert; (3) `tokenVersion` claim — bump số này = invalidate toàn bộ JWT cũ của user (cost: 1 DB lookup mỗi request, cache Redis được).

**Q: "Refresh token expire bao lâu?"**

> 7 ngày. Trade-off: dài hơn = user ít re-login, ngắn hơn = bị steal cũng nhanh expire. 7d là số phổ biến (Google, Facebook). Mobile có thể 30d nếu dùng device fingerprint kèm.

**Q: "Sao không lưu access token ở DB luôn cho chắc?"**

> Mất hoàn toàn benefit stateless. JWT stateless = 0 lookup. Nếu lưu access ở DB cũng phải hit DB mỗi request → bằng session cookie nhưng phức tạp hơn. Hoặc dùng session thẳng.

## Related

- ADR: [`decisions/002-jwt-vs-session.md`](../decisions/002-jwt-vs-session.md)
- Issue: [`issues/02-token-refresh-race-condition.md`](../issues/02-token-refresh-race-condition.md)
- Code: [`services/auth-service/src/main/java/com/ecom/auth/service/JwtService.java`](../../services/auth-service/src/main/java/com/ecom/auth/service/JwtService.java)
- Code: [`services/auth-service/src/main/java/com/ecom/auth/service/RefreshTokenService.java`](../../services/auth-service/src/main/java/com/ecom/auth/service/RefreshTokenService.java)
- Interview: [`interview/day-02-auth.md`](../interview/day-02-auth.md)
