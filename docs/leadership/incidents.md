# Leadership Incidents — Personal Log

> **Mục đích**: ghi lại tình huống leadership thật gặp ở team Sotatek (hoặc
> liên quan project này), để có ammo cụ thể khi phỏng vấn senior/lead.
>
> **Quan trọng**:
> 1. **CHỈ ghi tình huống THẬT.** Không bịa scenario "team 6 người
>    ecommerce" cho project solo này — interviewer probe sâu sẽ lộ.
> 2. **Sanitize**: không tên thật của member, không tên khách hàng, không
>    chi tiết nhạy cảm. Dùng "Dev A", "PM B", "khách hàng X".
> 3. **File này có thể KHÔNG commit** nếu chứa info nhạy cảm — thêm vào
>    `.gitignore` nếu cần. Quyết định của Tonny.
>
> **Khi phỏng vấn dùng**: format STAR (Situation / Task / Action / Result).
> Mỗi entry dưới đã sẵn sàng để kể trong 90 giây.

---

## Format mỗi entry

```
### [Date] Tiêu đề (1 dòng — vấn đề cốt lõi)

**Situation** (3-5 dòng): bối cảnh team, scope, áp lực
**Task** (1-2 dòng): vai trò Tonny, cái gì cần giải quyết
**Action** (5-7 bullet): các bước cụ thể đã làm
**Result** (2-3 dòng): outcome đo được — không nói chung
**Lesson** (1-2 dòng): rút ra gì cho lần sau
**Tag**: #conflict-resolution #technical-decision #incident #hiring #mentoring #stakeholder
```

---

## Entries

### [TEMPLATE — xóa khi fill entry thật đầu tiên] Lần override quyết định technical của 1 senior dev

**Situation**: Team N người, sprint Y, dev A đề xuất pattern P1, dev B
phản đối đề xuất P2. Bế tắc 2 ngày, ảnh hưởng deadline.

**Task**: Tonny là Tech Lead, phải ra quyết định cuối + giữ relationship
cả 2 dev.

**Action**:
- (TODO: Tonny fill)
- ...

**Result**:
- (TODO: metric — sprint kịp deadline? bug rate? satisfaction?)

**Lesson**:
- (TODO: rút ra)

**Tag**: #conflict-resolution #technical-decision

---

## Categories cần có ít nhất 1 entry mỗi loại (cho phỏng vấn)

Interviewer senior/lead thường xoáy 1 trong các category này. Mỗi loại
nên có ≥1 entry thật để không bị bí.

- [ ] **Conflict resolution** — 2 dev disagreement technical
- [ ] **Override decision** — bạn override 1 dev senior, hoặc chính bạn bị PM/CTO override
- [ ] **Production incident** — sev1/sev2 bạn lead investigation
- [ ] **Performance issue ở team** — 1 dev underperform, bạn handle thế nào
- [ ] **Cross-team dependency** — bị block bởi team khác, bạn unblock thế nào
- [ ] **Hiring / interview** — phỏng vấn ứng viên, quyết định hire/no-hire có context
- [ ] **Mentoring** — kèm 1 junior level up, có metric trước/sau
- [ ] **Stakeholder pushback** — PM/sales đẩy scope/timeline, bạn negotiate
- [ ] **Tech debt vs feature** — convince leadership cho time fix tech debt
- [ ] **AI adoption ở team** — đây là câu hỏi HOT 2026 — bạn rollout AI tool ở team thế nào, control quality ra sao

---

## Stories về personal lab project này (ecommerce platform)

> Project solo + AI. KHÔNG kể là team project. Khi interviewer hỏi:

**Pitch chuẩn (90s)**:
> "Personal R&D solo + AI-assisted, 30 ngày, 9-service Spring Boot platform.
> Mục tiêu: deep-dive các pattern team mình ở Sotatek chưa có cơ hội apply
> — Saga, Outbox, virtual thread benchmark, 2-tier cache. AI giúp accelerate
> execution; tôi own decision + validation. Kết quả: có hands-on để khi
> team gặp tình huống tương tự, tôi propose có cơ sở thay vì lý thuyết."

**Câu hỏi probe + answer**:

- *"Anh dùng AI thế nào để build solo nhanh vậy?"* → AI Playbook entries trong từng day-NN doc.
- *"Code AI viết có bug không? Anh catch thế nào?"* → Show [ai-junior-traps.md](../review/ai-junior-traps.md) — 30+ entry pattern lỗi thật đã catch.
- *"Tại sao không làm ở công ty?"* → Scope team Sotatek hiện tại không cover Saga/Outbox; project này để chuẩn bị propose khi cần.
- *"Vậy anh không có team experience?"* → "Có, ở Sotatek tôi đang lead 6 người, scope là [X]. Đây là supplementary, không phải replacement."

---

## Anti-pattern cần tránh khi kể

1. **Không bịa số**: "team tôi đạt 99.9% uptime" mà không có cách đo → interviewer hỏi sâu là vỡ.
2. **Không nói "we" mơ hồ**: phải tách rõ "tôi làm X, dev khác làm Y, decision do tôi/CTO ra".
3. **Không kể câu chuyện không có outcome**: mọi entry phải có Result đo được, dù Result là "fail và học được gì".
4. **Không kể chuyện làm bạn trông xấu**: nhưng cũng không claim toàn thắng — senior biết nhận lỗi có nuance.
