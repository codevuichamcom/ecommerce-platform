# Chương 2 · 🔐 Người gác cổng thành

**Day 2 — Auth Service**

---

> *"Mọi vương quốc đều cần một cánh cổng. Và mọi cánh cổng đều cần người biết ai được vào, ai phải đứng ngoài."*

---

## Bối cảnh

Hệ thống đã có móng. Giờ cần trả lời câu hỏi cơ bản nhất của mọi ứng dụng: **"Ai đang nói chuyện với tôi?"**

Không có auth, mọi endpoint đều là cửa mở toang. Bất kỳ ai cũng có thể xóa product, cancel order, xem thông tin người khác. Day 2 đóng cánh cổng lại.

---

## Cuộc chiến JWT vs Session

Đây không phải quyết định kỹ thuật — đây là quyết định **kiến trúc**.

| | Session (stateful) | JWT (stateless) |
|---|---|---|
| Scale | Cần sticky session hoặc shared store | Bất kỳ instance nào cũng verify được |
| Revoke | Xóa session → instant logout | Phải chờ hết hạn (hoặc blacklist) |
| Microservice | Mỗi service phải gọi auth-service verify | Mỗi service tự verify bằng shared secret |
| Complexity | Đơn giản hơn | Phức tạp hơn (refresh rotation, token theft) |

**Chọn JWT.** Lý do: 9 microservice sẽ cần verify token. Nếu mỗi request phải gọi auth-service, auth-service trở thành single point of failure + bottleneck. JWT cho phép **verify tại chỗ** — chỉ cần biết secret key.

Trade-off chấp nhận: không instant revoke. Mitigation: access token sống 15 phút (đủ ngắn), refresh token 7 ngày (rotate mỗi lần dùng).

---

## Refresh Token Rotation — cuộc đua ngầm

Đây là nơi mọi thứ trở nên thú vị.

User mở 2 tab. Cả 2 tab đều gọi `/auth/refresh` cùng lúc với cùng 1 refresh token. Nếu không cẩn thận:

```
Tab A: đọc token "abc123" → valid → issue new token "def456"
Tab B: đọc token "abc123" → valid → issue new token "ghi789"
                                     ↑ RACE CONDITION!
```

Giờ có 2 refresh token active. Attacker steal 1 trong 2 → dùng mãi mà user không biết.

**Fix**: Atomic UPDATE với WHERE clause:

```sql
UPDATE refresh_tokens
SET token_hash = :newHash, expires_at = :newExpiry
WHERE token_hash = :oldHash AND revoked = false
-- Rows affected = 0? → Token đã bị dùng bởi tab khác → REJECT
```

Không lock. Không distributed mutex. Chỉ 1 câu SQL atomic. Elegant.

---

## 🆕 Virtual Threads — tuyên ngôn hiện đại

Một dòng config thay đổi mọi thứ:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Mọi request handler giờ chạy trên **virtual thread** — lightweight, không block OS thread khi chờ I/O. Endpoint `/auth/me` trả về:

```json
{
  "email": "tonny@example.com",
  "virtualThread": true  ← "Tôi modern từ ngày đầu"
}
```

Tại sao bật ngay Day 2 mà không đợi? Vì muốn **sống với nó** 38 ngày — phát hiện gotcha sớm (Day 19 sẽ gặp pinning problem với `synchronized`), không phải migrate cuối project.

---

## 🆕 Records thay DTO class

```java
// Trước (Java 8 style) — 40 dòng boilerplate
public class LoginRequest {
    private String email;
    private String password;
    // getter, setter, constructor, equals, hashCode, toString...
}

// Sau (Java 21) — 1 dòng, immutable, done
public record LoginRequest(
    @Email String email,
    @Size(min = 8, max = 72) String password
) {}
```

Tại sao `max = 72`? Vì BCrypt có **72-byte trap** — input dài hơn 72 bytes bị cắt âm thầm. Password "aaaa...a" (100 ký tự) và "aaaa...a" (72 ký tự) hash giống nhau. Validate ngay input, không để lọt vào hashing layer.

---

## 🆕 Testcontainers `@ServiceConnection`

Không H2. Không mock database. Test với **Postgres thật** chạy trong Docker container:

```java
@Testcontainers
@SpringBootTest
class AuthIntegrationTest {
    @Container
    @ServiceConnection  // Spring Boot 3.1+ auto-wire datasource
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
}
```

`@ServiceConnection` tự động inject connection string vào Spring context. Không cần `@DynamicPropertySource` boilerplate. Testcontainers start Postgres, Flyway migrate schema, test chạy trên real database, container die sau test.

---

## Kết thúc ngày 2

```
📊 Scorecard:
├── Services:        1 (auth-service)
├── Endpoints:       4 (register, login, refresh, /me)
├── Modernity:       Virtual Threads ✓ · Records ✓ · Testcontainers ✓
├── Security traps:  2 caught (BCrypt 72-byte, refresh race)
├── Docs:            5 (ADR-002, lesson, 2 issues, interview)
└── Vibe:            "Cổng thành đã đóng. Chỉ người có token mới vào."
```

> 💡 **Bẫy phỏng vấn**: *"JWT stateless thì làm sao force logout?"* — Câu trả lời: short-lived access (15min) + refresh rotation + optional Redis blacklist cho emergency revoke. Trade-off: thêm 1 Redis call mỗi request nếu bật blacklist.

---

*→ Cổng thành đã có. Giờ cần hàng hóa bên trong...*
