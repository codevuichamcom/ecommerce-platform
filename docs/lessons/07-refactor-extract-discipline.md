# 📚 Lesson 07 — Refactor & Extract Discipline (rule of three)

> **Day**: 7 — Refactor + Week 1 Mock
> **Trigger**: Sau Week 1, 4/5 service có triple `JwtAuthenticationFilter` +
> `JwtVerifier` + `AuthUserPrincipal` giống ~99%. Day 7 lift lên `common-lib`.
>
> **Ngược tiếng vọng phản tỉnh**: lesson này KHÔNG phải "DRY". Mà là **khi
> nào DRY là sai** và **khi nào DRY thắng AHA (Avoid Hasty Abstraction)**.

---

## 🎯 TL;DR

> **Rule of three (Fowler)**: thấy code lặp **lần 1**, viết. **Lần 2**,
> chấp nhận duplicate, ghi note "TODO sẽ extract khi có lần 3". **Lần 3**,
> mới extract. Trước lần 3 = **abstraction premature**, càng extract càng
> đẻ ra `Abstract*Template<T>` mà chỉ 1 use case xài thật.
>
> Day 7 thỏa rule — JWT verify-only filter có **4 implementation giống nhau**
> (product / inventory / cart / order). Auth-service KHÔNG count vào (principal
> structurally khác — có `tokenVersion`).

---

## ✅ Khi nào EXTRACT (3-điểm criteria)

Cả 3 phải đúng. Thiếu 1 → **đợi**.

| # | Criterion | Day 7 verify |
|---|-----------|--------------|
| 1 | **Lặp ≥ 3 lần thật** (không phải "sẽ lặp") | ✅ 4 service: product/inventory/cart/order |
| 2 | **Contract đã ổn định** (không thay đổi 2 sprint gần đây) | ✅ filter signature ổn từ Day 3 → Day 6 không touch |
| 3 | **Cross-cutting concern** (security/logging/observability), KHÔNG phải domain logic | ✅ JWT verify là pure infra |

**Day 7 examples**:

- ✅ **Extract**: `JwtAuthenticationFilter` + `JwtVerifier` + `AuthUserPrincipal` + `JwtVerifyProperties` → `common-lib/security/`. Đủ 3 criteria.
- ❌ **KHÔNG extract**: `RestClient` config (Day 6 mới có 1 chỗ ở order-service). Đợi Day 8 service thứ 2 dùng → có 2 chỗ vẫn chưa đủ rule of three → **Day 8 vẫn duplicate có chủ ý**.
- ❌ **KHÔNG extract**: Idempotency key partial unique index pattern (Day 6 chỉ ở order-service). Đợi service thứ 2.

---

## 🚫 Khi nào KHÔNG extract (anti-pattern)

| Trap | Triệu chứng | Vì sao tệ |
|------|------------|-----------|
| **Premature abstraction** | Thấy 2 chỗ giống → tạo `BaseAbstractFilterTemplate<T extends Auth>` | 2 chỗ giống ≠ 2 chỗ sẽ tiến hóa cùng hướng. Khi divergence xảy ra, abstraction sẽ leak → mỗi caller phải override 50% method → tệ hơn duplicate |
| **DRY by syntax, not semantic** | `validateUser()` ở 2 service trông giống nhưng business rule khác (1 check email, 1 check email + phone) | Extract = ép contract chung → bug khi 1 service đổi rule |
| **Cross-domain coupling** | Đẩy `Order` lên common-lib để cart-service "reuse" | `common-lib` là **infrastructure**, không phải domain. Domain class = service ownership rõ ràng (CLAUDE.md §5) |
| **Future-proof sai** | "Extract sẵn để service tới dùng" | YAGNI. Service tới có thể có constraint khác → abstraction không fit, phải đập đi |

> **Junior heuristic**: thấy giống → DRY. **Senior heuristic**: thấy giống
> → ghi `// TODO: see if this duplicates after next service` → **đợi**.

---

## ⚠️ Cạm bẫy khi extract

Sau khi quyết extract, có 4 cạm bẫy. Day 7 dính 2/4 nếu không cẩn thận:

### 1. Extract đụng auth-service (không cùng contract)

`AuthUserPrincipal` ở auth-service có 4 field (kèm `tokenVersion`) — verify-only chỉ 3.
Nếu lift bừa → ép auth-service downgrade → mất feature `tokenVersion`-based invalidation.

**Fix**: KHÔNG động auth-service. Common-lib type là **subset** dành cho verify-only.
Auth-service tự giữ class riêng, document rõ "không reuse vì khác contract".

### 2. Auto-config bật toàn cục → notification-service Kafka-only bị kéo Web/Security dependency

Common-lib auto-config nếu `@Bean` không có condition sẽ register cho mọi service.
notification-service Day 11 sẽ là Kafka-only, không có Web → fail context start.

**Fix**: 3 layer condition:
```java
@ConditionalOnClass({Jwts.class, UsernamePasswordAuthenticationFilter.class})
@ConditionalOnProperty(prefix = "auth.jwt", name = "secret")
@EnableConfigurationProperties(JwtVerifyProperties.class)
```
- `@ConditionalOnClass` — chỉ activate nếu service có jjwt + spring-security-web.
- `@ConditionalOnProperty` — opt-in qua config (notification không set → skip).
- `@ConditionalOnMissingBean` ở từng `@Bean` — service tự override nếu cần custom.

### 3. Property prefix breaking change

4 application.yml đang dùng `auth.jwt.*`. Đổi sang `ecom.security.jwt.*` = **rolling deploy break**:
service nâng cấp common-lib trước khi update yml → context fail start.

**Fix**: Giữ prefix `auth.jwt.*` (legacy contract). Đổi prefix là task riêng cần ADR + grace period.

### 4. Filter ordering trong filter chain

Service tự `addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)`.
Nếu auto-config tự thêm vào chain → conflict với SecurityConfig của service.

**Fix**: Auto-config CHỈ register **bean**, không touch filter chain. Service tự inject + addFilter.
Lý do: filter ordering là service-specific concern (cart cho phép anonymous, product require GET public, order require auth).

---

## 📊 Approaches compared (extract JWT filter)

| Approach | Pros | Cons |
|----------|------|------|
| **A. Copy-paste 4 service (status quo trước Day 7)** | Mỗi service tự do tiến hóa | Bug fix phải sửa 4 chỗ; AI/junior mỗi lần tạo service mới copy-paste lệch dần |
| **B. Extract lên common-lib (chosen)** | 1 nguồn truth; service mới = 0 code; opt-in | Risk auto-config leak (mitigation: 3 condition layer) |
| **C. Extract thành standalone library `ecom-jwt-starter`** | Cleaner separation | Over-engineering ở 5 service scale; multi-repo overhead |
| **D. Đổi sang Spring Security Resource Server (`spring-boot-starter-oauth2-resource-server`)** | Chuẩn Spring; có sẵn JWKS support | Thay đổi lớn — Day 7 không phải day re-architect; defer Day 8+ khi cross-service mTLS |

**Chosen: B**. Rationale:
- 4 service trùng = đủ rule of three.
- Common-lib đã có pattern (CommonAutoConfiguration từ Day 1) → đồng bộ.
- D là direction đúng dài hạn nhưng Day 7 là **tuần kết** không phải tuần thay đổi cơ bản.
- Trade-off accepted: nếu Day 8 chuyển sang mTLS / OAuth2 Resource Server, common-lib filter sẽ bị deprecate → chấp nhận, sẽ remove khi đó.

---

## 🎤 Trả lời phỏng vấn

**Q**: *"Khi nào anh extract code lên common library? Có rule cụ thể không?"*

> Tôi dùng rule of three của Fowler: lần 1 viết, lần 2 chấp nhận duplicate
> với note TODO, lần 3 mới extract. Plus 3-điểm: phải có 3 use case thật,
> contract đã stable ≥ 1-2 sprint, và là cross-cutting concern (security,
> logging, observability) chứ không phải domain logic.
>
> Trong project ecommerce, Day 7 tôi lift JWT verify-only filter từ 4
> service (product, inventory, cart, order) lên common-lib. Trước đó Day
> 3-6 chấp nhận duplicate có chủ ý — comment ghi rõ "sẽ lift Day 7" để AI
> hoặc developer khác không vội DRY khi mới có 2 chỗ.

**Follow-up trap**: *"Vậy DRY không phải lúc nào cũng đúng?"*

> Đúng. Sandi Metz có câu *"duplication is far cheaper than the wrong
> abstraction"*. Khi extract sai, mỗi caller phải override 50% method —
> tệ hơn duplicate vì đã mất khả năng tiến hóa độc lập. Tôi từng nâng
> cấp 1 abstract base class lib ở Sotatek, kết quả là 2 caller mỗi lần
> upgrade phải fix break thay vì hưởng lợi.

**Follow-up trap**: *"Anh có chuyển 4 service sang `oauth2-resource-server` của Spring không?"*

> Direction đúng nhưng không Day 7. Day 7 mục tiêu là **giảm duplication
> đã tồn tại**, không phải re-architect. Khi nào chuyển: Day 8+ nếu cần
> JWKS dynamic key rotation hoặc cross-service mTLS — đó là lúc chấp nhận
> deprecate common-lib filter vừa extract. Trade-off chấp nhận trước:
> common-lib hôm nay có thể là tech debt mai, miễn là **giảm duplicate hôm nay rõ ràng**.

---

## 🔗 Related

- Code: [common-lib/security/](../../common-lib/src/main/java/com/ecom/common/security/) — JwtAuthenticationFilter, JwtVerifier, AuthUserPrincipal, JwtVerifyProperties
- Code: [common-lib/autoconfig/SecurityAutoConfiguration.java](../../common-lib/src/main/java/com/ecom/common/autoconfig/SecurityAutoConfiguration.java)
- Lesson: [01-monorepo-vs-polyrepo.md](01-monorepo-vs-polyrepo.md) — common-lib pattern đầu tiên Day 1
- ADR: [001-why-hybrid-architecture.md](../decisions/001-why-hybrid-architecture.md) — common-lib là infra, không domain
- Review: [ai-junior-traps.md](../review/ai-junior-traps.md) — AI hay vội DRY, entry [03] [04] thêm Day 7
