# Lesson 11b — API Versioning Strategy

> **Status**: ✅ Done (filled Day 11)
> **Related day**: Day 11 (Notification service).

---

## 🎯 TL;DR

3 cách version REST API: URI prefix (`/v1/`), Header (`X-API-Version: 2`), Content Negotiation (`Accept: application/vnd.api.v2+json`). Chọn **URI versioning** vì rõ ràng nhất cho client, dễ route ở Gateway, cost thấp trong context internal microservice. Kèm **N-1 deprecation policy**: maintain 2 version song song, version N-2 mới xóa sau 90-day sunset window.

---

## 🆚 Approaches compared

| Approach              | Ví dụ                               | Pros                                   | Cons                                                  |
| --------------------- | ----------------------------------- | -------------------------------------- | ----------------------------------------------------- |
| **URI versioning** ✅ | `/api/v1/orders` → `/api/v2/orders` | Rõ ràng, dễ route ở Gateway theo path prefix, dễ debug curl, dễ cache CDN (URL = cache key) | Pollute URL; "không RESTful" theo lý thuyết (resource giống nhau, chỉ representation đổi) |
| Header versioning     | `X-API-Version: 2`                  | URL sạch, đúng REST spec               | Khó debug bằng browser/curl; dễ bị client quên set header; Gateway route phức tạp hơn |
| Accept content-type   | `Accept: application/vnd.ecom.v2+json` | Đúng HTTP content negotiation spec  | Ít team biết; khó test bằng browser; Swagger/OpenAPI support kém |
| Query param           | `/api/orders?version=2`             | Đơn giản implement                     | Cache key bị bẩn (CDN phân biệt `?v=1` vs `?v=2`); bỏ qua dễ |

---

## ✅ Chosen — URI versioning + N-1 compatibility policy

**Lý do gắn với context**:
- Gateway (Day 14+) route theo path prefix `/v1/*` → `/v2/*` — cực đơn giản, 1 route rule.
- Internal service-to-service call: Feign/HTTP Interface hard-code base URL `http://order-service/api/v1` — tường minh, không phụ thuộc header negotiation.
- Team 8 người ở ShopVN không đủ kỷ luật ép tất cả client set header đúng → URL tường minh safer.

**N-1 deprecation policy**:
- Luôn maintain **N** (latest) và **N-1** (previous) song song.
- Khi release v3: thêm `Deprecation: true` header + `Sunset: <date>` header vào v1 response.
- **90-day sunset window**: monitor metric `http_requests_total{path=~"/api/v1/.*"}` → khi về 0 → xóa safe.
- Breaking change rule: thêm optional field = non-breaking (JSON additive). Xóa field / đổi type / đổi semantic = breaking → cần vN+1 + dual-publish window.

**Demo Day 11** (xem code):
```
GET /api/v1/notifications/health → {service, version, status, timestamp}
GET /api/v2/notifications/health → {service, version, status, timestamp, channelUsed}
```
v2 thêm field `channelUsed` — v1 client JSON deserialize vẫn OK (ignore unknown field). Đây là non-breaking change.

---

## ⚠️ Cạm bẫy

**Cạm bẫy 1: "Version tất cả endpoint ngay từ đầu"**
Mỗi endpoint thêm v1 + v2 controller = 2 file. Với 50 endpoint = 100 file. Chỉ version endpoint có **khả năng contract thay đổi trong 6 tháng tới**. Internal CRUD endpoint stable → không cần version ngay.

**Cạm bẫy 2: "Internal service-to-service không cần version"**
Sai. Khi deploy rolling update, có window ngắn: service A đã update (gọi v2 contract) nhưng service B instance cũ còn chạy (chỉ có v1 code). Không có version → runtime error. Với version: service B vẫn serve `/api/v1/*` trong window đó.

**Cạm bẫy 3: "URI versioning không RESTful"**
Đúng về lý thuyết — resource giống nhau thì URL không nên khác nhau, chỉ representation đổi. Nhưng pragmatism > purism ở production. Roy Fielding cũng đồng ý rằng REST là architectural style, không phải law. Trả lời phỏng vấn: thừa nhận trade-off, giải thích tại sao context của bạn chọn URI.

**Cạm bẫy 4: Database schema migration khi break version**
Breaking API change thường đi kèm schema change. Cần **expand-contract pattern**: phase 1 — thêm column mới (nullable), deploy v2; phase 2 — backfill data cũ; phase 3 — sau khi v1 sunset, drop column cũ. KHÔNG migration 1-step vì v1 code đang chạy song song vẫn write schema cũ.

---

## 🎤 Trả lời phỏng vấn

**Q: "Em chọn URI versioning hay header versioning? Tại sao?"**

> URI versioning vì 3 lý do thực tế: (1) Gateway route bằng path prefix — 1 rule, không cần custom header matching; (2) curl debug không cần thêm flag `-H`; (3) CDN cache key = URL — không bị miss cache do client gửi header khác nhau. Trade-off hy sinh URL "purity" theo lý thuyết REST, nhưng team lớn cần tường minh hơn elegance.

**Q: "URI versioning có 'không RESTful' không?"**

> Đúng theo lý thuyết — resource giống nhau không nên có URL khác nhau. Nhưng đây là architectural trade-off, không phải lỗi. Thực tế Stripe, Twilio, GitHub đều dùng URI versioning và đây là các API được đánh giá cao nhất trong industry. Pragmatism > purism.

**Q: "Khi nào em xóa hẳn v1?"**

> Sau N-1 policy: khi v3 ra, v1 deprecated với Sunset header + 90-day window. Sau 90 ngày, monitor traffic metric `http_requests_total{path="/api/v1/*"}` → nếu về 0 → xóa safe. Không bao giờ xóa dựa theo calendar blind; phải dựa trên metric thực tế.

**Q: "Breaking change là gì?"**

> Thêm optional field (nullable, has default) = non-breaking — JSON client cũ ignore unknown field. Breaking: xóa field, đổi type, đổi semantic (field `status` từ string sang int), rename field, thay đổi endpoint path/method. Breaking change → cần version mới + dual-serve window.

---

## 🔗 Related

- ADR: [`decisions/008-api-versioning-strategy.md`](../decisions/008-api-versioning-strategy.md)
- Code v1: [NotificationStatusV1Controller.java](../../services/notification-service/src/main/java/com/ecommerce/notification/api/v1/NotificationStatusV1Controller.java)
- Code v2: [NotificationStatusV2Controller.java](../../services/notification-service/src/main/java/com/ecommerce/notification/api/v2/NotificationStatusV2Controller.java)
