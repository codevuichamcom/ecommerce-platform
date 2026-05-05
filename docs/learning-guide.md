# 📖 Learning Guide — 40-Day Ecommerce Platform

> Mục tiêu: code → concept → interview mastery. KHÔNG phí thời gian.
> Dành cho Tonny — backend Tech Lead, 6 người team, chuẩn bị phỏng vấn.

---

## 🎯 Learning philosophy

**40 ngày này KHÔNG phải online course học động.**

Đây là **real project** — code production-grade rồi teach yourself lại. Model:
- **Code first** (morning) — hands-on, chịu pain của concurrency / distributed lock / GC pause
- **Understand** (evening) — đọc lesson giải thích WHY
- **Communicate** (weekly) — mock interview luyện nói

→ Bloom's taxonomy: **Remember → Understand → Apply → Analyze → Evaluate**.

---

## 🔄 3-Phase per day (core ritual)

### Phase 1 — CODE (75% day)

**Khi nào**: Morning/afternoon khi tâm trí sắc nhất.

**Làm gì**: 
- Trigger `/day X` (skill runner)
- Build code production-grade ← theo CLAUDE.md §4-7
- Test production case (vd: 100 thread concurrent reservation, GC pause scenario)
- DO NOT copy-paste. UNDERSTAND tại sao dùng @Version, KHÔNG pessimistic lock.

**Dừng ở đâu**: 
- Code done (tất cả file trong Phase 2 §3 built).
- Test pass. `./gradlew :services:<service>:build` green.
- NOT over-engineering — stub phần phụ với `// TODO` có note.

**Hỏi bản thân 3 câu**:
1. **"Tại sao approach này mà không cách khác?"** → Đáp án ở Issue "Approaches compared" (9-section format).
2. **"Nếu scale 10x?"** → Bottleneck nào? Trade-off nào?
3. **"Production failure nào dễ xảy ra?"** → Ghi vào learning log.

### Phase 2 — UNDERSTAND (20% day)

**Khi nào**: Evening, sau code, tâm trí còn liên hệ.

**Làm gì**:
- Đọc `docs/lessons/NN-topic.md` — concept core day này.
- Đọc `docs/decisions/NNN-topic.md` (ADR) — decision rationale.
- Re-read issue "Approaches compared" section + chosen rationale.

**KHÔNG đọc gì**:
- Các lesson của day sau (rộng quá, chưa thấy code).
- Code khác trong cùng service (narrow để deep).
- Competitive tool (vd: Redis vs Memcached) — project đã chốt.

**Note đơn giản**:
- 1-2 bullet per lesson — AHA moment, không tuần tự đầy đủ.
- Ghi personal learning log `docs/learning/aha-moments.md` (gitignore).

### Phase 3 — COMMUNICATE (5% day)

**Khi nào**: Before bed hoặc hôm sau.

**Làm gì**:
- **Nói 2 phút** (như nói với bạn): "Day 4 mình implement optimistic lock vì ... [trade-off vs pessimistic] ... scale 10x thì ..."
- **Trả lời interview Q** từ `docs/interview/day-X-*.md`.

**Mục tiêu**:
- "Nói được = hiểu được" — nếu lắp bắp → chưa sâu.
- Nếu có bạn → explain thật. Không có → explain to rubber duck / ghi voice note.

**KHÔNG cần**:
- Thuộc lòng. Hiểu ý là OK.
- Trả lời "học sinh model" — trả lời "senior mindset" (ghi trong Tech Lead Lens).

---

## 📅 Weekly rhythm

### Tuần 1-3 (Day 1-21) — Core + Performance

**Mỗi ngày (Mon-Fri)**:
```
Morning (2-3h):   Phase 1 code
Afternoon (1h):   Phase 2 understand + note
Evening (30m):    Phase 3 interview Q
```

**Thứ 7 (Day 7, 14, 21)**:
```
Morning (1h):     Review lesson + issue Day N-6 ~ Day N-1 (skim, không học lại)
Afternoon (1.5h): MOCK INTERVIEW Week (interactive, 5-7 Q + feedback)
Evening (30m):    Phân tích weak point → decide supplement learning nếu cần
```

### Tuần 4 (Day 22-25) — Data layer + System design prep

**Tương tự tuần 1-3**, nhưng:
- **Thêm**: sketch system design problem (flash sale, autocomplete) trên paper.
- **Hỏi AI**: "design này có pitfall nào?" → feedback.
- KHÔNG detailed spec — chỉ architecture sketch + trade-off.

### Tuần 5 (Day 26-30) — Frontend

**Shift pattern**: code React + Node vẫn Phase 1-3, nhưng:
- **Mỗi test case** phải run trên browser (KHÔNG chỉ test pass).
- **Toàn bộ tuần 4 learning review** (1h): recall 4 data layer decision từ tuần trước.
- **End of tuần 5 mock** (Day 30): toàn bộ D1-30 (full stack).

### Tuần 6-7 (Day 31-40) — System design + Final

**Tuần 6 (Day 31-37)**:
- **Tonny drive** design problem (capacity, flash sale, ...).
- **Claude validate** (trả lời câu hỏi, pick apart trade-off).
- **Mock interview** D31-37 × 2-3 lần (luyện drawing + explaining).

**Tuần 7 (Day 38-40)**:
- Day 38: CV polish + portfolio pitch script.
- Day 39: Full mock (system design × 2).
- Day 40: Retrospective + gap analysis.

---

## 🎤 Mock interview — 3 formats

### 1️⃣ **Per-day interview** (Day 1-21, 26-30)

**Khi**: End of day, 10-15 phút.

**Format**: Read `docs/interview/day-X-*.md` → trigger AI Q → user answer → AI follow-up trap.

**Example Day 4**:
```
Q: Khi order bán 100 cái, 100 customer reserve cùng lúc, làm gì để không oversell?
Your answer: "Dùng optimistic lock với @Version. Mỗi cái (update order sẽ check version, 
            nếu version khác → StaleObjectStateException → retry. DB trigger constraint 
            quantity >= 0..."
Follow-up: "Tại sao KHÔNG SELECT FOR UPDATE? Khi nào thì SELECT FOR UPDATE tốt hơn?"
```

### 2️⃣ **Weekly mock** (Day 7, 14, 21, 30)

**Khi**: Day 7/14/21/30 chiều, 1-1.5 giờ.

**Format**: 5-7 Q kết hợp Day N-6 ~ Day N-1. Interactive, **Claude đóng vai senior interviewer**, hỏi liên tiếp.

**Example Week 1** (D1-7):
```
1. Tại sao chọn Gradle thay Maven? (vd: modernity, reproducible build)
2. Monorepo vs polyrepo? (tradeoff, scale-up trigger)
3. Hybrid Layered + DDD — khi nào dùng cái nào? (3-point criteria)
4. DB-per-service — data join thế nào? (Feign / Kafka)
5. Virtual thread vs platform thread? (Day 2 modernity)
6. (Tùy yếu point) Depth question.
7. (Scenario): Tại sao Day 4 chọn optimistic locking KHÔNG pessimistic?
```

**Output**: 
- ✅ Pass: user trả lời + lý do consistent
- 🟡 So-so: user trả lời nhưng follow-up không biết
- ❌ Weak: user trả lời hoặc không trả lời

**Action nếu weak**:
- Ghi note: "transaction isolation level chưa sâu"
- Ngày hôm sau review `lessons/04b-transaction-isolation.md` lại
- Skip ahead KHÔNG nên — Day 4 là foundation, chưa hiểu sẽ fail Day 12+

### 3️⃣ **System design interview** (Day 31-37, 39)

**Khi**: Day 31-37 hàng ngày (~30m planning + 1h whiteboard), Day 39 full mock (2h).

**Format**: Tonny pick 1 problem (flash sale / autocomplete / notification-at-scale) → Claude hỏi requirements → Tonny design trên paper / screen → Claude counter-question.

**Example Day 33 (Flash sale)**:
```
Claude: "Định giá sản phẩm 1000 cái, 1M user access cùng lúc. Design?"
Tonny:  [vẽ: queue + Redis atomic decrement + payment async]
Claude: "Đội A reserve thành công, thanh toán fail. Queue lúc đó?"
Tonny:  "Phải release — outbox pattern, inventory consumer subscribe release event"
Claude: "TTL queue bao lâu? Người đợi 10 phút trong queue mà sale hết rồi?"
```

---

## 📝 Learning artifacts (cumulative)

### Personal (gitignore — KHÔNG commit)

**`docs/learning/aha-moments.md`** (daily update):
```markdown
## Day 4 — Overselling race condition

**AHA**: Optimistic lock chỉ detect conflict, KHÔNG prevent. 
Nên khi 2 thread cùng read qty=2, update qty=1 → 1 cái throw StaleObjectStateException.
Chi tiết: @Version là AUTOINCREMENT ở DB, mỗi update tăng. Spring JPA check version trước UPDATE — 
nếu khác → exception.

**Production angle**: retry logic phải exponential backoff, không busy-loop.
**Interview angle**: "Tại sao KHÔNG dùng SERIALIZABLE isolation?" → throughput, serialization failure.
```

**`docs/learning/scale-10x-notes.md`**:
```markdown
## Inventory service (Day 4-6)

**Now**: 100 QPS, optimistic lock, fail rate 0.1%.
**10x (1000 QPS)**: retry storm dính — chuyển sang queue (vd: RabbitMQ reserve request queue).
**100x**: inventory sharding theo product ID mod 10, mỗi shard 1 worker.
```

**`docs/learning/weekly-retrospective.md`** (End of Week):
```markdown
## Week 1 retrospective (Day 1-7)

**Strong**: 
- Gradle setup + common-lib structure — sẽ dùng template này mọi service.
- DDD concept clear — qua Day 4-6 coding.

**Weak**:
- Transaction isolation — chưa sâu. Day 4 skip 04b isolation lesson.
- Virtual thread — chỉ enable flag, chưa benchmark.

**Action Week 2**:
- Deep dive isolation (add performance test khi merge Day 4).
- Day 19 sẽ có virtual thread benchmark — prepare mindset từ giờ.
```

### Public (gitignore but review before interview)

**`docs/review/ai-junior-traps.md`** (cumulative per-day review):
- Đây là code review checklist — append khi phát hiện pattern sai của AI/junior.

**`docs/leadership/incidents.md`** (only if Tonny shares real Sotatek scenario):
- Ghi STAR story từ team Tonny — phục vụ lead role phỏng vấn.

---

## ⚠️ 5 Pitfalls to avoid

| Pitfall | Dấu hiệu | Cách tránh |
|---|---|---|
| **Read-heavy, no code** | Bạn đọc lesson D4 trước code D4 | Enforce: `/day X` phase order (code → lesson) |
| **Code copy-paste** | Copy template, KHÔNG thay đổi / hiểu | Sau mỗi class hỏi "tại sao dùng class này" — nếu KHÔNG biết → reread code |
| **Learn in silo** | Code Day 4, lúc Day 12 quên Day 4 context | Weekly mock force recall |
| **Skip "approaches"** | Fix bằng X, KHÔNG kể 3 approach khác | Issue template bắt buộc 9 section — `/day` enforce format |
| **No spaced repetition** | Mock 1 lần tuần, quên | Mock mỗi tuần + AHA notes + weekly retrospective |

---

## 🚀 Pre-interview checklist (Day 38+)

Trước vào phòng phỏng vấn:

- [ ] Tuần 1-3 mock review: trả lời D1-21 flow (core + performance)
- [ ] Tuần 4-5 mock review: D22-30 (data layer + frontend)
- [ ] Tuần 6 mock review: D31-37 system design (capacity, flash sale, autocomplete, ...)
- [ ] AHA moments summary (ghi lại 10 core insight)
- [ ] Trade-off mindset: mỗi decision có 3 approach + chosen reason + scale impact
- [ ] Failure mode awareness: production sẽ bị gì?
- [ ] Leadership story (Sotatek incident nếu có)
- [ ] CV bullet draft (`docs/interview/week-NN-cv-bullets.md`)

---

## 🔗 Related

- Workflow: [`.claude/skills/day/SKILL.md`](../.claude/skills/day/SKILL.md) — sprint runner
- Docs hub: [`docs/README.md`](README.md) — structure reference
- 40-day plan: [`docs/ROADMAP.md`](ROADMAP.md) — daily checklist
- Context: [`/CLAUDE.md`](../CLAUDE.md) — tech + rules
