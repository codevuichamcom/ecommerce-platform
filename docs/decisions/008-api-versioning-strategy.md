# ADR-008 — API Versioning Strategy

> **Status**: ⏳ Skeleton — fill khi build Day 11.

- **Status**: Proposed
- **Date**: TBD (Day 11)
- **Deciders**: Tonny

---

## 🎯 Decision

> (TODO 1-2 câu) Chọn **URI versioning** (`/api/v1/*`) + maintain **N-1 compatibility** (90-day deprecation window).

---

## 🧭 Context

> (TODO) Project có 9 microservice gọi nhau qua HTTP/Feign. Khi đổi shape response của 1 service, không thể deploy tất cả service lại cùng lúc. Cần 1 strategy versioning rõ ràng.

---

## 🔀 Alternatives considered

### Option A — URI versioning (`/api/v1/orders`)
- ✅ (TODO) Rõ ràng, dễ route ở Gateway, dễ debug bằng curl.
- ❌ (TODO) URL "không RESTful" (cùng resource, 2 URL).

### Option B — Header versioning (`X-API-Version: 2`)
- ✅ URL sạch, đúng REST.
- ❌ Khó debug, dễ quên ở client.

### Option C — Content negotiation (`Accept: application/vnd.ecom.v2+json`)
- ✅ Đúng HTTP spec.
- ❌ Phức tạp, ít team biết.

### Option D (chosen) — URI versioning + N-1 policy
- ✅ Trade-off practical nhất cho team nhỏ + AI-assisted.

---

## ✅ Chosen — Rationale

> (TODO) Gắn với context project: Gateway route prefix-based trivial; debug dễ; team familiar.

---

## ⚖️ Trade-offs

- ✅ Accept: URL pollution.
- ❌ Reject: per-endpoint versioning (sẽ rối).

---

## 🔮 Consequences

- (TODO) Tích cực: client biết chính xác version đang call.
- (TODO) Tiêu cực: maintain 2 version song song = 2x test surface.

---

## 🔗 Related

- Lesson: [`lessons/11b-api-versioning.md`](../lessons/11b-api-versioning.md)
- Code: `services/gateway-service/...` (sẽ có Day 11)
