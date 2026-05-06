# 🗓️ Daily Cheatsheet — Mở session là xem file này

> **1 trang. Không scroll. Mở 5 giây thấy hết.** Chi tiết link sâu tới file khác.

---

## 🌅 Morning startup (30s, mỗi day)

1. Mở [`docs/ROADMAP.md`](ROADMAP.md) § Status snapshot — xác định **Next up: Day X**.
2. (Optional) Skim doc Day X-1 nếu cần chain context.
3. Gõ `DAY X` → skill `/day` chạy 7-phase workflow.

→ Code → confirm → build → tick ROADMAP → suggest commit. Gõ `/commit` để stage.

---

## 🌙 Evening wrap-up (30s, sau khi code xong)

1. Đọc lesson day vừa code: [`docs/lessons/`](lessons/) (file có prefix `NN`).
2. Ghi 1-2 bullet AHA moment vào `docs/learning/aha-moments.md` (gitignore).
3. Trả lời 1 interview Q trong `docs/interview/day-NN-*.md` — **nói thành tiếng** 1 phút.

→ Chi tiết 3-phase ritual: [`docs/learning-guide.md`](learning-guide.md) § 3-Phase per day.

---

## 📅 Saturday (Day 7 / 14 / 21 / 30 / 37 / 39)

1. **Sáng** (1h): skim lesson + issue Day N-6 → Day N-1 (KHÔNG học lại).
2. **Chiều** (1.5h): mock interview week — gõ "INTERVIEW MODE Week N", Claude đóng vai senior interviewer 5-7 Q.
3. **Tối** (30m): note weak point → quyết supplement learning tuần sau.
4. Tạo file `docs/interview/week-NN-cv-bullets.md` — gom 1-2 bullet metric.

→ Chi tiết: [`docs/learning-guide.md`](learning-guide.md) § Weekly rhythm.

---

## 🎯 Pre-interview (T-3 ngày khi có lịch phỏng vấn)

1. Skim toàn bộ `docs/interview/week-*-cv-bullets.md` — 5 phút.
2. Recall AHA moments → consolidate 10 core insight.
3. Mock full system design (Day 31-37 problems) × 2 lần.
4. Pre-interview checklist: [`docs/learning-guide.md`](learning-guide.md) § Pre-interview checklist.

---

## ⏸️ Resume after break (skip ≥3 day)

1. Mở [`docs/ROADMAP.md`](ROADMAP.md) § Session log — xem entry cuối.
2. `git log --oneline -10` — xem commit gần đây.
3. Đọc AHA moments 1 tuần gần nhất → reload context.
4. Skim doc Day vừa hoàn thành — chain context cho Day mới.

---

## 🔗 File hierarchy (depth ngược)

```
🟢 Daily (mỗi ngày):     docs/daily.md (file này)
🟡 Sprint:               docs/ROADMAP.md
🟠 Method:               docs/learning-guide.md
🔵 Catalog:              docs/README.md
⚪ Bootstrap session AI: /CLAUDE.md
```

> Quy tắc: bắt đầu từ 🟢, nhảy sâu khi cần. Đừng đọc CLAUDE.md mỗi sáng — nó cho Claude, không cho bạn.

---

## ⚠️ KHÔNG nên làm

- ❌ Mở 4 file cùng lúc rồi không biết đọc cái nào → mở `daily.md` trước.
- ❌ Đọc lesson trước code (Phase order: code → lesson → interview).
- ❌ Skip Saturday mock — retention drop 80% nếu không recall.
- ❌ Đọc CLAUDE.md hàng sáng — file đó cho Claude tự load, không cho user.
