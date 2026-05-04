---
name: commit
description: Stage thay đổi và tạo commit theo Conventional Commits. Phân tích diff, group thay đổi theo scope, draft message ngắn gọn focus "why" hơn "what". Dùng khi user gõ /commit hoặc yêu cầu "commit cho tôi".
---

# /commit — Conventional Commits cho Ecommerce Platform

Skill này được tùy chỉnh cho project Ecommerce Platform 40-day. Tuân thủ
working principles ở [`/CLAUDE.md`](../../../CLAUDE.md) — đặc biệt mục 7
(commit message conventional theo từng day).

---

## 🎯 Mục tiêu

Tạo commit **gọn, rõ context, đúng scope** — đọc lại 6 tháng sau vẫn hiểu vì sao.
KHÔNG commit lan man. KHÔNG group thay đổi không liên quan vào 1 commit.

---

## 🔧 Quy trình (BẮT BUỘC theo thứ tự)

### Bước 1 — Snapshot trạng thái (chạy parallel)

```bash
git status
git diff
git diff --cached
git log --oneline -5
```

- `git status` (không bao giờ dùng `-uall`).
- `git diff` xem unstaged + `git diff --cached` xem staged.
- `git log --oneline -5` để theo style commit hiện có (nếu repo đã có history).

### Bước 2 — Phân tích thay đổi

Trả lời 4 câu hỏi trước khi viết message:

1. **Scope nào bị ảnh hưởng?** (vd: `auth`, `inventory`, `docs`, `gradle`, `foundation`)
2. **Loại thay đổi?** (`feat`, `fix`, `refactor`, `docs`, `chore`, `test`, `perf`, `build`, `ci`)
3. **Có phải nhiều scope không liên quan?** → CHIA nhiều commit.
4. **Có file sensitive không?** (`.env`, credentials, `application-local.yml`, `docs/leadership/incidents.md`) → cảnh báo, KHÔNG auto-commit.

### Bước 3 — Conventional Commits format

```
<type>(<scope>): <subject viết thường, không dấu chấm cuối, ≤72 ký tự>

[Optional body — focus "why", không "what". Wrap 72 cols.]

[Optional footer: BREAKING CHANGE / Closes #issue / Co-authored-by]
```

**Type bắt buộc dùng** (không tự đẻ thêm):

| Type     | Khi nào                                              |
| -------- | ---------------------------------------------------- |
| `feat`   | Tính năng mới (ở mức user-facing hoặc API mới)        |
| `fix`    | Sửa bug                                              |
| `refactor` | Đổi code không đổi behavior                        |
| `perf`   | Tăng performance                                     |
| `docs`   | Chỉ docs (README, ADR, lesson, issue, interview ...)  |
| `test`   | Chỉ test                                             |
| `build` / `chore` | Build script, deps, gradle, gitignore       |
| `ci`     | CI pipeline                                          |
| `style`  | Format / whitespace (RIGHT — KHÔNG dùng cho UI)      |

**Scope theo project này** (giữ ngắn):

`auth` · `product` · `inventory` · `cart` · `order` · `payment` · `notification` ·
`analytics` · `gateway` · `common-lib` · `infra` · `gradle` · `docs` · `frontend` ·
`foundation` (cho thay đổi cross-cutting Day 1)

### Bước 4 — Stage cụ thể, KHÔNG `git add -A`

- Add từng file/folder rõ ràng theo phân tích Bước 2.
- KHÔNG `git add .` hay `git add -A` — tránh lỡ commit secret hay file rác.
- Nếu user yêu cầu add tất cả, vẫn loop qua `git status` để verify từng file.

### Bước 5 — Commit qua HEREDOC (giữ format đúng)

PowerShell (Windows) — single-quoted here-string:

```powershell
git commit -m @'
docs(foundation): add 40-day learning system + 8 gap-problem skeletons

- expand roadmap from 30 to 40 days (week 4 data layer + week 6 system design)
- enforce "approaches compared" in issue template (CLAUDE.md §9)
- seed 8 skeleton docs for senior gap problems (isolation, redlock, ...)
'@
```

Bash:

```bash
git commit -m "$(cat <<'EOF'
docs(foundation): add 40-day learning system + 8 gap-problem skeletons

- expand roadmap from 30 to 40 days
- enforce "approaches compared" in issue template
- seed 8 skeleton docs for senior gap problems
EOF
)"
```

### Bước 6 — Verify

```bash
git status
git log --oneline -3
```

Đảm bảo:
- Working tree clean (hoặc còn đúng những gì user muốn giữ unstaged).
- Commit message hiện đúng format.
- KHÔNG có file sensitive bị commit (grep nhanh `.env`, `password`, `secret`).

---

## ✍️ Quy tắc viết subject (≤72 ký tự)

- Imperative mood: `add`, `fix`, `refactor` — KHÔNG `added`, `fixing`.
- Lowercase đầu câu, không dấu chấm cuối.
- Nói **WHY** hơn **WHAT** khi có thể. Tên file đã là "what".
- KHÔNG ghi: "update some files", "wip", "minor changes" — vô nghĩa.

**Ví dụ tốt**:

```
feat(inventory): add optimistic locking to prevent overselling
fix(auth): refresh token race condition under concurrent requests
docs(foundation): expand roadmap to 40 days with system design week
chore(gradle): bump spring boot to 3.4.5 for virtual thread fixes
refactor(common-lib): extract MDC filter into auto-configuration
```

**Ví dụ tệ**:

```
update files               ← không scope, không why
fix bug                    ← bug gì?
WIP                        ← không bao giờ commit WIP vào main
Added new feature.         ← capitalize + dấu chấm + không scope
```

---

## ⚠️ Safety rules (KHÔNG vượt qua)

1. **NEVER** commit khi user chưa explicit yêu cầu. /commit là explicit signal.
2. **NEVER** dùng `--no-verify` để skip pre-commit hook. Nếu hook fail → fix root cause.
3. **NEVER** dùng `--amend` mặc định. Tạo commit mới. Chỉ amend khi user yêu cầu rõ.
4. **NEVER** push trừ khi user gõ /push hoặc yêu cầu "push lên remote".
5. **NEVER** commit file match: `.env*`, `*-local.yml`, `*-secret.yml`, `credentials.*`,
   `application-local.*`, `docs/leadership/incidents.md` (private theo `.gitignore`).
   Nếu user yêu cầu commit specific file sensitive → confirm lại 1 lần nữa.
6. **NEVER** chạy `git config` để đổi global identity.
7. Khi commit có nhiều scope không liên quan → đề xuất CHIA commit, không gom 1 lần.

---

## 🆎 Khi user yêu cầu nhiều commit cùng lúc

Phân tích diff → group theo scope → đề xuất kế hoạch trước khi chạy:

```
Tôi sẽ tạo 3 commit:
1. docs(foundation): expand roadmap to 40 days
   files: docs/ROADMAP.md, docs/README.md
2. docs(foundation): add 8 gap-problem skeletons
   files: docs/lessons/*, docs/issues/*, docs/decisions/008-*
3. chore(claude): add /commit skill
   files: .claude/skills/commit/SKILL.md
Confirm để tôi proceed?
```

→ User confirm → chạy tuần tự, mỗi commit add file của commit đó rồi commit.

---

## 🔗 Related

- Project commit convention: [`/CLAUDE.md`](../../../CLAUDE.md) mục 7
- Conventional Commits spec: <https://www.conventionalcommits.org/>
- 40-day roadmap: [`/docs/ROADMAP.md`](../../../docs/ROADMAP.md)
