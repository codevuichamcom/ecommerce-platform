# ADR-008 — API Versioning Strategy

- **Status**: Accepted
- **Date**: 2026-05-19
- **Deciders**: Tonny (backend lead), Anh Hùng (Tech Lead)
- **Supersedes**: —
- **Context**: Day 11 — notification-service thêm REST endpoint, cần chọn versioning strategy trước khi rollout gateway.

---

## 🏗️ Decision

**URI versioning** với prefix `/api/vN/` + **N-1 deprecation policy** (maintain 2 version song song, 90-day sunset window).

---

## 📚 Context

Sau Day 11, ecommerce platform có 7 service đang build endpoint. Team 8 người. Thị trường VN — nhiều client mobile app không update nhanh (update rate ~60% sau 2 tuần). API Gateway (Day 14+) sẽ route traffic. Cần strategy nhất quán trước khi có nhiều endpoint public.

Breaking change trigger: marketing team yêu cầu v2 `GET /notifications/health` thêm field `channelUsed`. Dùng Day 11 này để thực nghiệm strategy và chốt ADR cho toàn platform.

---

## 🆚 Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| **URI versioning** `/api/v1/` ✅ | Rõ ràng, dễ route gateway (path prefix), dễ debug curl, dễ cache CDN | URL "không RESTful" theo lý thuyết; pollute endpoint namespace |
| Header versioning `X-API-Version: 2` | URL sạch, REST-correct | Khó debug; Gateway custom header route phức tạp; client dễ quên set |
| Content negotiation `Accept: application/vnd.api.v2+json` | Đúng HTTP spec (RFC 7231) | Ít team biết; Swagger support kém; test browser không dùng được |
| No versioning (đổi in-place) | Zero overhead | Break existing client ngay lập tức; không có backward compat |
| Feature flag per endpoint | Flexible | Complexity cao; config sprawl; không clear contract cho external client |

---

## ✅ Chosen — Rationale

1. **Gateway route simplicity**: Spring Cloud Gateway route `/api/v1/*` → service v1 bằng path predicate. Không cần custom header filter.
2. **Mobile client compatibility**: client mobile VN update rate thấp → v1 và v2 song song là bắt buộc. URI versioning làm rõ endpoint nào đang được gọi trong log/trace.
3. **Debugging + observability**: Zipkin trace + access log dễ filter `path=/api/v2/*` hơn là filter header value.
4. **Industry precedent**: Stripe, Twilio, GitHub, Shopify đều dùng URI versioning. Team mới onboard dễ hiểu ngay.

---

## ⚖️ Trade-offs

**Accepted**:
- URL "không RESTful" — resource `/notifications/health` giống nhau nhưng URI khác nhau theo version. Chấp nhận pragmatism > purism.
- Namespace proliferation — `/api/v1/` và `/api/v2/` cùng tồn tại; cần discipline không tạo v3 khi chỉ thêm optional field.

**Rejected**:
- Header versioning: routing phức tạp hơn, client error-prone khi miss header.
- No versioning: không viable khi có mobile client update chậm.

---

## 📜 Consequences

1. Tất cả public endpoint MỚI (từ Day 11 trở đi) đặt trong package `api/v1/`, path `/api/v1/*`.
2. Breaking change → tạo `api/v2/` package mới, KHÔNG sửa `api/v1/`.
3. Khi release vN, deprecated vN-2 với `Deprecation: true` header + `Sunset: <date>` (90 ngày).
4. Monitor metric `http_server_requests_seconds_count{uri=~"/api/v1/.*"}` → xóa version khi về 0.
5. Internal service-to-service call CŨNG version — không skip dù "internal": rolling deploy window.
6. **Breaking change definition** (đồng thuận team):
   - Non-breaking: thêm optional field, thêm endpoint mới, thêm HTTP method mới.
   - Breaking: xóa field, đổi type, đổi semantic, rename field, xóa endpoint, đổi method.

---

## 🔗 Related

- Lesson: [`lessons/11b-api-versioning.md`](../lessons/11b-api-versioning.md)
- Code demo: [`services/notification-service/src/main/java/com/ecommerce/notification/api/`](../../services/notification-service/src/main/java/com/ecommerce/notification/api/)
- Gateway routing: Day 14+ Spring Cloud Gateway route config
