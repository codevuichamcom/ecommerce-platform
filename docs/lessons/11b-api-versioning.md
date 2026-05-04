# Lesson 11b — API Versioning Strategy

> **Status**: ⏳ Skeleton — fill khi build Day 11.
> **Related day**: Day 11 (Notification service).

---

## 🎯 TL;DR

> 1-2 câu: 3 cách version REST API (URI / header / Accept-Version). Chọn URI versioning + N-1 deprecation policy. Lý do: rõ ràng nhất cho client, dễ route, cost không cao trong context internal microservice.

---

## 🆚 Approaches compared

| Approach              | Ví dụ                              | Pros                                   | Cons                                                  |
| --------------------- | ---------------------------------- | -------------------------------------- | ----------------------------------------------------- |
| URI versioning        | `/api/v1/orders` → `/api/v2/orders` | Rõ ràng, dễ route ở Gateway, dễ debug | Pollute URL, "không RESTful" (resource giống nhau)    |
| Header versioning     | `X-API-Version: 2`                 | URL sạch, đúng REST                    | Khó debug bằng curl, dễ quên ở client                 |
| Accept content-type   | `Accept: application/vnd.ecom.v2+json` | Đúng HTTP spec (content negotiation) | Phức tạp, ít team biết, khó test bằng browser         |
| Query param           | `/api/orders?v=2`                  | Đơn giản                               | Cache key bẩn, dễ bị bỏ qua                           |

---

## ✅ Chosen — URI versioning + N-1 compatibility

- (TODO) Lý do gắn với context: Gateway route theo prefix `/v1/*` `/v2/*` rất dễ.
- (TODO) Policy: maintain N và N-1 song song. Khi release v3, deprecate v1 với 90-day window.
- (TODO) Breaking change definition: thêm field optional ≠ breaking; xóa field / đổi type / đổi semantic = breaking.

## ⚠️ Cạm bẫy

- (TODO) "Versioning tất cả endpoint cùng version" — tránh per-endpoint versioning, sẽ rối.
- (TODO) Internal service-to-service: vẫn nên version, đừng skip vì "internal".
- (TODO) Database schema migration: backward + forward compat (expand-contract pattern).

## 🎤 Trả lời phỏng vấn

> (TODO) "Khi deploy service A, service B đang gọi v1, em làm gì?"
> (TODO) "URI versioning có 'không RESTful' không?"
> (TODO) "Khi nào em xóa hẳn v1?"

## 🔗 Related

- ADR: [`decisions/008-api-versioning-strategy.md`](../decisions/008-api-versioning-strategy.md)
- Code: `services/gateway-service/src/main/java/com/ecom/gateway/route/` (sẽ có Day 11)
