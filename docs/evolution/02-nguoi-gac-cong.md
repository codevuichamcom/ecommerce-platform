# Chương 2 · 🔐 Người gác cổng thành

**Day 2 — Auth Service**

---

> *"Mọi vương quốc đều cần một cánh cổng. Và mọi cánh cổng đều cần một người gác — biết ai được vào, ai phải đứng ngoài, và ai đang cầm thẻ bài giả."*

---

> 🎬 **Chương này có gì:** một người gác cổng thành, một tấm thẻ bài có dấu niêm đọc-được-mà-không-cần-hỏi-vua, một khẩu lệnh đổi mỗi lượt qua cổng, một cú suýt cho nhầm người vì hai mật khẩu cùng một dấu vân tay, và ba món vũ khí hiện đại giắt lưng. ⚔️

---

## 🎬 Bối cảnh: tuyển người đứng cổng

Chương trước, kiến trúc sư đã đổ móng và kẻ ranh giới — nhưng toà thành vẫn mở toang. Mọi endpoint là một cửa không khóa: ai cũng vào xóa product, cancel order, đọc thông tin người lạ. Một toà thành không người gác chỉ là một cái sân rộng.

Nên Day 2, ta tuyển nhân vật mới: **người gác cổng thành** 💂. Việc của bác chỉ một câu, nhưng là câu sống còn nhất của mọi hệ thống:

> 🗣️ *"Ngươi là ai? Và ngươi được phép vào không?"*

Nghe đơn giản. Cho đến khi bạn nhận ra: vương quốc này không có **một** cổng. Nó sắp có **9 cổng** (9 microservice), mỗi cổng một người lính gác. Và đây là câu hỏi định mệnh: mỗi khi có người chìa thẻ, người lính có phải **chạy về hỏi vua** không?

---

## 🏰 Thẻ bài có dấu niêm vs Sổ khách của vua — JWT vs Session

Đây không phải quyết định kỹ thuật vặt. Đây là quyết định **kiến trúc**, và nó xoay quanh đúng một câu hỏi: lính gác xác minh khách **tại chỗ**, hay phải chạy về cung tra sổ?

| | 📖 Session (sổ khách của vua) | 🎫 Thẻ bài có dấu niêm (JWT) |
| --- | --- | --- |
| **Xác minh** | Lính phải chạy về cung tra sổ mỗi lượt | Lính đọc dấu niêm tại chỗ, biết thật-giả ngay |
| **Scale** | Cần sticky session hoặc kho chung | Cổng nào cũng verify được, không phụ thuộc nhau |
| **Thu hồi** | Xé tờ sổ → đuổi tức thì | Phải chờ thẻ hết hạn (hoặc blacklist) |
| **9 cổng** | Vua thành nút thắt cổ chai + điểm chết duy nhất | Mỗi cổng tự lo, vua được nghỉ |
| **Độ phức tạp** | Đơn giản hơn | Phức tạp hơn (rotation, token theft) |

**Chọn thẻ bài — JWT.** Lý do nằm ở cột "9 cổng": nếu mỗi request đều bắt lính chạy về hỏi auth-service, thì auth-service trở thành **single point of failure** kiêm **bottleneck** — vua ốm là cả vương quốc tê liệt. JWT cho phép **verify tại chỗ**: lính chỉ cần biết con dấu của vua (shared secret) là đọc được thẻ thật hay giả, không cần hỏi ai.

**Trade-off chấp nhận:** không thu hồi tức thì. Một thẻ đã phát thì còn hiệu lực tới lúc hết hạn. Cách hoá giải: làm **thẻ bài sống ngắn** (access token 15 phút — đủ ngắn để thiệt hại có trần) kèm **khẩu lệnh đổi liên tục** (refresh token 7 ngày, rotate mỗi lần dùng).

> 💡 **Ăn điểm phỏng vấn:** đừng nói "JWT vì nó stateless cho ngầu". Nói: *"9 service đều cần verify — nếu stateful thì auth-service thành SPOF + bottleneck. JWT đẩy việc verify ra biên, đánh đổi bằng việc mất instant-revoke, và tôi bù lại bằng access token 15 phút + refresh rotation."* Nêu được cả trade-off lẫn mitigation mới là senior.

---

## 🔄 Đổi khẩu lệnh mỗi lượt qua cổng — và cuộc đua ngầm

Thẻ bài sống ngắn, nên khách phải định kỳ ra cổng đổi thẻ mới bằng **khẩu lệnh** (refresh token). Luật của người gác: mỗi lần dùng một khẩu lệnh, nó **cháy ngay**, và khách nhận một khẩu lệnh mới. Đây là **refresh token rotation** — kẻ trộm có lấy được khẩu lệnh cũ cũng vô dụng, vì nó đã cháy.

Nghe ổn. Cho đến khi khách mở **hai cánh cửa sổ cùng lúc** (hai tab trình duyệt), và cả hai cùng chìa **một** khẩu lệnh ra cổng đúng một khoảnh khắc:

```
Tab A: đọc khẩu lệnh "abc123" → hợp lệ → phát khẩu lệnh mới "def456"
Tab B: đọc khẩu lệnh "abc123" → hợp lệ → phát khẩu lệnh mới "ghi789"
                                          ↑ RACE CONDITION!
```

Giờ có **hai** khẩu lệnh cùng sống. Kẻ trộm tóm được một cái — dùng mãi mà khách không hề hay. Lưới phòng thủ vừa thủng đúng cái khe phần nghìn giây.

**Cách bịt:** không lock, không distributed mutex, không nghi lễ rườm rà. Chỉ một câu SQL **atomic** với mệnh đề `WHERE` làm trọng tài:

```sql
UPDATE refresh_tokens
SET token_hash = :newHash, expires_at = :newExpiry
WHERE token_hash = :oldHash AND revoked = false
-- Rows affected = 0?  → khẩu lệnh đã bị tab kia dùng trước → REJECT, đuổi cả hai làm lại
```

Database chọn ra **một kẻ thắng** được phép đổi. Kẻ thua nhận `rows affected = 0` và bị từ chối. Một câu SQL, không mutex, không phức tạp. Elegant.

> 🧠 **Senior insight:** rất nhiều race condition trong đời thực không cần lock phân tán hay Redis lock cho oách. Một câu `UPDATE ... WHERE <điều kiện kỳ vọng>` rồi kiểm `rows affected` là **compare-and-set** ngay trong DB — atomic, rẻ, và database đã lo phần khó nhất. Reach cho công cụ nặng trước khi thử công cụ nhẹ là một dạng over-engineering.

---

## 🔑 Cú suýt cho nhầm người: hai mật khẩu, một dấu vân tay

Người gác không cất mật khẩu của khách. Bác cất **dấu vân tay** của nó — một hash BCrypt. Khách chìa mật khẩu, bác lăn vân tay, so với cái đã lưu. Khớp thì mở cổng.

Nhưng có một đêm, suýt nữa người gác cho nhầm người.

> 🎬 **Cảnh phim:** một kẻ lạ chìa ra một mật khẩu dài 100 ký tự, **khác hẳn** mật khẩu thật của khách ở ký tự thứ 73 trở đi. Người gác lăn vân tay... và tái mặt: **vân tay khớp.** Hai mật khẩu khác nhau, một dấu vân tay y hệt. Sao có thể?

Thủ phạm là **BCrypt 72-byte trap**: BCrypt âm thầm **cắt cụt input ở byte thứ 72**. Mọi thứ sau byte 72 bị vứt không một lời cảnh báo. Nên "aaaa…a" (100 ký tự) và "aaaa…a" (72 ký tự) cho ra **cùng một hash** — cùng một dấu vân tay. Người gác không hề biết mình bị lừa.

Cách bịt: chặn ngay từ cổng ngoài, đừng để input dài lọt tới tầng hash. Và đây là lúc người gác rút món vũ khí hiện đại đầu tiên ra khỏi thắt lưng — **Record** của Java 21, gọn đến mức validate ngay tại khai báo:

```java
// Trước (Java 8 style) — 40 dòng boilerplate: getter, setter, equals, hashCode, toString...
public class LoginRequest {
    private String email;
    private String password;
    // ... mệt mỏi ...
}

// Sau (Java 21) — 1 record, immutable, validate ngay tại chỗ
public record LoginRequest(
    @Email String email,
    @Size(min = 8, max = 72) String password   // ← chặn 72-byte trap NGAY ở input
) {}
```

> ⚠️ **Trap kinh điển:** không validate `max = 72` thì hai mật khẩu khác nhau có thể đăng nhập như nhau, và tệ hơn — không ai phát hiện cho đến khi pentest hỏi. Cắt cụt **âm thầm** là loại bug tệ nhất: không lỗi, không log, chỉ sai. Validate ở input là chặn từ trứng nước.

---

## ⚔️ Trang bị cho người gác: ba món vũ khí hiện đại

Một người gác giỏi không chỉ có con mắt tinh. Bác còn cần đồ nghề tốt. Day 2 giắt cho bác ba món vũ khí Java 21 / Spring Boot hiện đại — và mỗi món được rút ra ngay từ Day 2 có chủ đích, không phải để khoe.

### 🧵 Vũ khí 1: Virtual Threads — một đạo quân lính gác nhẹ tênh

Một dòng config, và mỗi request được một **virtual thread** riêng đứng gác — siêu nhẹ, không ghim cứng OS thread khi ngồi chờ I/O:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Endpoint `/auth/me` còn cắm cờ tuyên ngôn:

```json
{
  "email": "tonny@example.com",
  "virtualThread": true
}
```

Vì sao bật ngay Day 2 mà không đợi? Vì người gác muốn **sống chung với đạo quân này 38 ngày** — để gặp gotcha sớm (Day 19 sẽ chạm trán bài toán **pinning** với khối `synchronized`), chứ không phải migrate cập rập vào cuối dự án rồi vỡ trận.

### 📋 Vũ khí 2: Records — sổ ghi gọn, không bịa thêm chữ

Đã gặp ở cảnh BCrypt trên: Record thay class DTO 40 dòng boilerplate bằng 1 dòng — **immutable**, có sẵn `equals`/`hashCode`/`toString`, validate cắm thẳng tại field. Người gác ghi chép gọn, không dây mực.

### 🧪 Vũ khí 3: Testcontainers `@ServiceConnection` — diễn tập với địch thật

Người gác không tập đánh với hình nộm (H2, mock DB). Bác tập với **Postgres thật** trong Docker container:

```java
@Testcontainers
@SpringBootTest
class AuthIntegrationTest {
    @Container
    @ServiceConnection   // Spring Boot 3.1+ tự wire datasource — khỏi @DynamicPropertySource boilerplate
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
}
```

`@ServiceConnection` tự bơm connection string vào Spring context. Testcontainers dựng Postgres, Flyway migrate schema, test chạy trên DB thật, container chết sau test. Diễn tập sát thực chiến — không phải "ở H2 thì pass, lên Postgres thì khóc".

| ⚔️ Vũ khí | Thay cho cái cũ | Vì sao rút ra từ Day 2 |
| --- | --- | --- |
| 🧵 Virtual Threads | Thread pool truyền thống | Sống chung 38 ngày để gặp pinning sớm (Day 19) |
| 📋 Records | DTO class 40 dòng | Immutable + validate tại field, chống 72-byte trap |
| 🧪 Testcontainers `@ServiceConnection` | H2 / mock DB | Test sát Postgres thật, khỏi "pass dỏm" |

---

## 🏁 Kết thúc ngày 2

```
📊 Scorecard:
├── Services:        1 (auth-service)
├── Endpoints:       4 (register, login, refresh, /me)
├── Vũ khí hiện đại: Virtual Threads ✓ · Records ✓ · Testcontainers ✓
├── Security traps:  2 chặn được (BCrypt 72-byte, refresh rotation race)
├── Docs:            5 (ADR-002, lesson, 2 issues, interview)
└── Vibe:            "Cổng thành đã khóa. Chỉ kẻ cầm thẻ thật mới qua." 💂
```

> 💡 **Bẫy phỏng vấn kinh điển:** *"JWT stateless thì force logout kiểu gì?"*
>
> **Strong answer:** Không có nút "đuổi tức thì" miễn phí với JWT. Cách thật dùng: access token sống ngắn (15 phút) + refresh rotation (khẩu lệnh cháy mỗi lần dùng) + optional Redis blacklist cho ca khẩn cấp. Trade-off của blacklist: thêm 1 Redis call mỗi request — nên chỉ bật khi thực sự cần, không bật mặc định kẻo mất luôn cái lợi "verify tại chỗ" của JWT.
>
> 🪤 **Follow-up trap:** *"Refresh rotation chống được token theft thật không?"* → Chống được **reuse**: nếu kẻ trộm dùng khẩu lệnh đã cháy, ta phát hiện (rows affected = 0 / token đã revoked) và có thể **thu hồi cả họ token** của user đó — coi như tín hiệu bị xâm nhập. Không chống được kẻ trộm dùng token **trước** chủ nhân, nhưng thu hẹp cửa sổ tấn công xuống còn một lượt.

---

*→ Cổng đã có người gác, thẻ bài đã có dấu niêm. Nhưng một vương quốc chỉ có cổng mà không có hàng trong kho thì gác để làm gì? Ngày mai, ta dựng kệ hàng — và một chủ tiệm khôn ngoan sẽ dạy ta một bài: đôi khi xây nhanh là phải biết mình đang **nợ** những gì...* 📦
