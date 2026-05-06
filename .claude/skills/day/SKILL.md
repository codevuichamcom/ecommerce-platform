---
name: day
description: Workflow runner cho 1 sprint day của ecommerce platform 40-day project. Trigger khi user gõ "DAY X", "day X", "/day X", "làm day X", "bắt đầu day X". Ép 7-phase workflow (pre-flight → plan → confirm gate → build code → build docs → post-flight → suggest commit) + 8 phần output bắt buộc + extension sections theo trigger condition. KHÔNG dictate cách viết code — delegate cho CLAUDE.md §4-7. Mục tiêu: chống drift workflow giữa 40 sprint, ép tick checklist ROADMAP cuối session.
---

# /day — Sprint Day Workflow Runner

Skill này là **enforcer** của workflow per-day, KHÔNG phải template của code.
Code style + production-grade rule đã ở [`/CLAUDE.md`](../../../CLAUDE.md).

> ⚠️ **Quy tắc số 1**: Sau Phase 2 (output 8 phần), DỪNG. Đợi user confirm
> "OK build đi" rồi mới sang Phase 4. KHÔNG auto-build.

---

## 🔧 7-Phase workflow (BẮT BUỘC theo thứ tự)

### Phase 1 — Pre-flight (mechanical, ≤30s)

Chạy parallel:

```
Read: docs/ROADMAP.md (đặc biệt §Status snapshot + section của day X)
Read: CLAUDE.md §Modernity additions per day (xem day X có row không)
Glob: docs/{lessons,issues,decisions}/*${X}*.md (skeleton có sẵn)
Glob: docs/interview/day-${X-1}-*.md (chain context day trước)
```

**Verify checklist**:
- [ ] Day X có trong ROADMAP? Nếu không → STOP, hỏi user.
- [ ] Day X status `⏳ pending`? Nếu `✅ done` → hỏi: rebuild / skip / continue.
- [ ] Day X-1 đã ✅? Nếu chưa → cảnh báo "đang skip ahead, có ổn không?".
- [ ] Skeleton docs có sẵn? Liệt kê paths để Phase 5 fill.
- [ ] **Branch hiện tại = `master` + working tree clean?** Nếu không → cảnh báo trước khi tạo branch.

**Tạo branch day** (sau khi verify clean) — chạy ở Phase 1 trước khi output plan:
```
git checkout master && git pull --ff-only
git checkout -b day-NN-<slug>     # slug ngắn theo topic ROADMAP, vd day-02-auth
```
Slug rule: từ tên section ROADMAP, lowercase, dash-separate, ≤3 từ (auth, inventory-ddd, cart-redis, kafka-setup, cache, sql-tuning, ...).

**Output Phase 1** (1 đoạn ngắn ≤80 từ):
> "Day X — [tên] · Status [pending/done] · Skeleton có sẵn: [list] · Modernity: [tech] · Day X-1: [done/in-progress]. Tiếp tục?"

### Phase 2 — Plan output (8 phần BẮT BUỘC)

> ⚠️ Format theo CLAUDE.md §8 mục "Khi user gõ DAY X". KHÔNG đổi thứ tự.

```
1. 🎯 Mục tiêu hôm nay
   - 1-3 bullet, đo được, không vague.

2. 🏢 Task mô phỏng công ty thật
   - Bối cảnh: tên fictional company / role giao việc / deadline / reviewer.
   - Liên hệ tới Sotatek context nếu phù hợp.

3. 💻 Code cần build
   - List file path đầy đủ (relative từ repo root).
   - Skeleton signature (interface / method) — KHÔNG impl chi tiết.
   - Note dependencies từ day trước.

4. 📚 Docs cần build
   - List path từng doc + cross-reference plan (link tới ADR/lesson nào).
   - Note file nào đã có skeleton sẵn (Phase 1 đã liệt kê).
   - Format bắt buộc theo CLAUDE.md §9.

5. 🧠 Lesson cần học (đêm đọc)
   - 1-2 concept core day này. Liên kết file lesson sẽ build.

6. 🔥 Issue nên simulate
   - 1 production scenario. Format 9-section CLAUDE.md §9.
   - Approaches compared (≥3) là bắt buộc.

7. 🎤 Interview questions
   - 3-5 Q + strong answer outline + follow-up trap.
   - Việt + giữ English term.

8. 🧠 Senior mindset notes
   - 2-3 bullet: pitfall AI/junior, scale 10x note, trade-off non-obvious.
```

### Phase 2b — Append extension sections theo trigger

Đọc CLAUDE.md §8c để check trigger:

| Section | Trigger cho day này? | Action |
|---|---|---|
| 🤖 AI Playbook | **MỌI day** — auto include | Append vào section 8 (4 bullet: làm tốt / prompt mẫu / risk / validate) |
| 👥 Tech Lead Lens | Day ∈ {1, 4, 6, 8, 9, 12, 13, 15, 19, 22, 23, 24, 31, 33} | Append vào section 8 (3 bullet: trade-off+scale 10x / failure mode + 5-step triage / junior+AI 2 lỗi dễ) |
| 📋 CV Bullet | Day ∈ {7, 14, 21, 25, 30, 37, 40} (cuối tuần) | Tạo file riêng `docs/interview/week-NN-cv-bullets.md` ở Phase 5 |
| 🔍 Code Review traps | Khi Phase 4 phát hiện AI viết sai | Append `docs/review/ai-junior-traps.md` ở Phase 6 |
| 👥 Leadership incidents | User CHIA SẺ tình huống thật từ Sotatek (KHÔNG bịa) | Hỏi user ở Phase 1; nếu có → append `docs/leadership/incidents.md` |

### Phase 3 — Confirm gate ⚠️

> **DỪNG. Output câu hỏi sau và đợi user reply.** KHÔNG sang Phase 4.

```
Plan trên OK chứ? Reply:
- "build" / "OK" → tiếp Phase 4 (build code + docs)
- "đổi X" → revise plan
- "DOC MODE" → skip Phase 4, làm chỉ docs
- "FAST MODE" → minimal docs, code-first
- "skip" → tick day done, không build (nếu đã có code sẵn)
```

### Phase 4 — Build code

> Skill KHÔNG dictate cách viết code. Delegate cho:
> - CLAUDE.md §4 (tech stack)
> - CLAUDE.md §5 (architecture rules)
> - CLAUDE.md §7 (code style)
> - CLAUDE.md §10 (quality bar)

Skill CHỈ ép:
- Build TẤT CẢ file path đã list ở Phase 2 §3.
- Mỗi file production-grade (theo CLAUDE.md §10).
- Stub phần phụ với `// TODO: ...` có giải thích (CLAUDE.md §7).
- Chạy `./gradlew :services:<service>:build` (hoặc tương đương) để verify.

Nếu build fail → fix root cause, KHÔNG `--no-verify` / skip test.

### Phase 5 — Build docs

> Format BẮT BUỘC theo CLAUDE.md §9. Skill check tick từng section.

**Cho mỗi doc trong Phase 2 §4**:
- [ ] Đúng folder + naming convention (CLAUDE.md §8b)
- [ ] Cross-link bidirectional (doc A → B → A)
- [ ] Code reference dùng `file_path:line` format
- [ ] Việt kỹ thuật, giữ English term (CLAUDE.md §7)
- [ ] Emoji header theo CLAUDE.md §10b table

**Format check theo loại doc**:

| Loại | Section bắt buộc |
|---|---|
| ADR | Status / Date / Decision / Context / ≥3 Alternatives / Chosen+Rationale / Trade-offs / Consequences / Related |
| Issue | 9 section: Problem / Symptoms / Root cause / **Approaches compared (≥3)** / **Chosen+Why** / Fix / Prevention / Trade-off accepted / Related |
| Lesson | TL;DR / Khi nào dùng / Khi nào KHÔNG / Cạm bẫy / (Approaches compared nếu có) / Trả lời phỏng vấn / Related |
| Interview | Q → Strong answer (Việt+English term) → Follow-up traps + Senior mindset + AI Playbook + Tech Lead Lens (nếu day trigger) |

**Skeleton fill rule**: nếu Phase 1 phát hiện skeleton có sẵn (vd `04b-transaction-isolation.md` cho Day 4) → fill mọi `(TODO)` placeholder, đổi `Status: ⏳ Skeleton` → `✅ Done`.

### Phase 6 — Post-flight (mechanical, BẮT BUỘC, hay quên)

Update 4 nơi theo thứ tự:

1. **`docs/ROADMAP.md` Day X section**:
   - Đổi `### ⏳ Day X` → `### ✅ Day X`
   - Đổi `**Status**: pending` → `**Status**: done · YYYY-MM-DD`
   - Tick từng `- [ ]` → `- [x]`

2. **`docs/ROADMAP.md` §Status snapshot**:
   - `Last updated`: today
   - `Current sprint`: `Day X ✅ Done — [1-line summary]`
   - `Next up`: `Day X+1 — [tên]`
   - `Sprints completed`: tăng N → N+1
   - `Docs created`: tăng theo số doc Phase 5 build
   - Counter khác (services built, ...) tăng nếu phù hợp

3. **`docs/ROADMAP.md` §Session log** cuối file:
   - Append 1 dòng: `- YYYY-MM-DD · Day X — [1-line deliverable]`

4. **`docs/README.md` §2 Index**:
   - Cho mỗi doc Phase 5 build: đổi status `⏳ Day X` → `✅`

### Phase 7 — Suggest commit message + PR description

Output 2 thứ. KHÔNG auto-commit, KHÔNG auto-push, KHÔNG auto-create PR.

**(a) Commit message** conventional theo CLAUDE.md §7.

**(b) PR description** — template cố định (Tonny copy-paste khi tạo PR):

```markdown
## Day NN — <topic>

### 🎯 Mục tiêu
<1-3 bullet copy từ Phase 2 §1>

### 💻 Code thay đổi
**Added** (N file mới):
- `path/to/File.java` — <1 dòng why>
- ...

**Modified** (N file):
- `path/to/Existing.java` — <thay đổi gì + why>

**Removed**: <nếu có>

### 📚 Docs đã build
- [lesson NN](docs/lessons/NN-x.md) — <1 dòng>
- [issue NN](docs/issues/NN-x.md) — <1 dòng>
- [interview day NN](docs/interview/day-NN-x.md)
- [ADR NNN](docs/decisions/NNN-x.md) <nếu có>

### ✅ Test result
- Build: `./gradlew :services:<svc>:build` ✅ / ❌
- Unit test: N pass / N fail
- Integration test (Testcontainers): N pass / N fail
- Manual smoke (nếu có): <endpoint test outcome>

### 🧠 Lessons-learned
<2-3 bullet ngắn — gì học được, gì sẽ làm khác lần sau>

### 📋 ROADMAP checklist
Day NN tick `✅ done · YYYY-MM-DD` ở [docs/ROADMAP.md](docs/ROADMAP.md).
```

Format (tham khảo skill `/commit`):

```
<type>(<scope>): <subject ≤72 chars>

<body 2-4 bullet — focus WHY>
```

**Workflow sau Phase 7** (Tonny tự làm):
```
/commit                                  # skill commit stage + tạo commit
git push -u origin day-NN-<slug>
gh pr create --base master --title "Day NN — <topic>" --body "$(<pr-body.md)"
```
Skill KHÔNG chạy 3 lệnh trên tự động — Tonny review xong mới gõ.

**Hint type theo nature day**:
- Day có service mới (auth, product, ...): `feat(<scope>)`
- Day refactor / tăng performance: `refactor` / `perf`
- Day chỉ docs/ADR: `docs(<scope>)`
- Day infra/config: `chore(<scope>)` / `build`

**Output Phase 7**:

```
Day X build xong. Suggest commit:

  feat(inventory): add Aggregate Stock with optimistic locking

  - Stock aggregate enforces non-negative quantity invariant
  - @Version optimistic lock prevents overselling under contention
  - 100-thread reservation test verifies no oversell
  - 04b transaction isolation lesson + 04 overselling issue (9-section)

Gõ /commit để stage + commit.
```

---

## ❓ Decision points (skill chủ động hỏi user)

Skill ASK ở Phase 1 hoặc Phase 2 (KHÔNG assume default):

| Câu hỏi | Khi nào hỏi | Default nếu user skip |
|---|---|---|
| Day đã ✅ rồi — rebuild / skip / continue? | Phase 1 phát hiện done | Hỏi rõ, KHÔNG mặc định |
| Có scenario thật từ Sotatek liên quan day này không? | Phase 1, day có Tech Lead Lens trigger | Skip Leadership append |
| Scope: full / FAST MODE / DOC MODE? | Phase 3 confirm gate | Full |
| Skeleton có sẵn — fill theo outline hay redesign? | Phase 1 phát hiện skeleton | Fill outline |

---

## ⚠️ Safety rules (KHÔNG vượt qua)

1. **NEVER** auto-build sau Phase 2. Phase 3 confirm gate là bắt buộc.
2. **NEVER** skip Phase 6 post-flight. Day "build xong" mà không tick ROADMAP = chưa done.
3. **NEVER** auto-commit. Đợi user gõ `/commit`.
4. **NEVER** auto-push branch hoặc auto-`gh pr create`. Phase 7 chỉ output template.
5. **NEVER** commit thẳng vào `master`. Day branch là bắt buộc từ Day 2.
6. **NEVER** bịa Leadership incident. Chỉ append khi user share thật.
7. **NEVER** dictate code style — delegate CLAUDE.md.
8. **NEVER** rebuild day đã `✅` mà không hỏi user.
9. Khi build fail → fix root cause. KHÔNG `--no-verify` / disable test.

---

## 🆎 Special modes (CLAUDE.md §8)

User có thể gõ kèm DAY X:

| Mode | Behavior |
|---|---|
| `DAY X DOC MODE` | Skip Phase 4 (code). Chỉ Phase 5 (docs). |
| `DAY X FAST MODE` | Phase 4 production-grade. Phase 5 minimal (chỉ interview Q&A + 1 lesson). |
| `DAY X REVIEW MODE` | Skip Phase 4. Phase 5 chỉ review code đã có + traps. |

---

## 🔗 Related

- Workflow source: [`/CLAUDE.md`](../../../CLAUDE.md) §8 + §8b + §8c + §9
- Status tracking: [`/docs/ROADMAP.md`](../../../docs/ROADMAP.md)
- Doc index: [`/docs/README.md`](../../../docs/README.md)
- Commit follow-up: [`.claude/skills/commit/SKILL.md`](../commit/SKILL.md)
