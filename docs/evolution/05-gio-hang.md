# Chương 5 · 🛒 Giỏ hàng — tốc độ là tất cả

**Day 5 — Cart Service (Redis)**

---

> *"Cái giỏ hàng là thứ kỳ lạ nhất ecommerce: user chạm vào nó nhiều nhất, nhưng trân trọng nó ít nhất. Nó giống cái túi xách tay — không ai khoá, ai cũng quăng đồ vào, và nếu lỡ mất thì... ờ, hơi phiền, nhưng không ai gọi luật sư."*

---

> 🎬 **Chương này có gì:** một cái túi xách không khoá, một phép thuật tên là HINCRBY, một vụ va chạm hai cái túi khi user đăng nhập, một cái túi zombie sống mãi không chịu chết, và lý do vì sao "mất túi" lại được phép — trong khi "mất két" thì không. 🛍️

---

## 🎬 Bối cảnh: cái túi, không phải cái két

Chương trước, ông gác kho dựng pháo đài ba lớp tường để canh giữ từng con số tồn kho như báu vật. Mọi thứ ở đó là **két sắt** — chống trộm, chống đếm sai, belt and suspenders.

Giỏ hàng thì ngược lại hoàn toàn. Giỏ hàng là **cái túi xách tay** 🛍️.

Hãy nghĩ về cái túi bạn đeo mỗi ngày: không khoá, không bảo hiểm, bạn nhét đồ vào lấy đồ ra hàng chục lần mà chẳng suy nghĩ. Nó phải *nhanh* và *nhẹ*. Nếu lỡ để quên đâu đó — phiền thật, phải mua lại vài món — nhưng đó không phải thảm hoạ. So với việc mất cái két order (mất tiền thật), mất cái túi chỉ là một buổi sáng hơi bực.

Đó chính xác là tính cách của cart:

- 🪶 **Không cần ACID.** Cái túi không cần giao dịch ngân hàng.
- 💨 **Không cần survive server restart.** Mất túi ≠ mất tiền.
- ⚡ **Cần đúng một thứ: NHANH đến mức vô hình.**

Con số cụ thể ông chủ giao: user add item → response **< 50ms**. Mở giỏ → load **< 100ms**. Bất kỳ độ trễ nào cũng = user bỏ đi = mất doanh thu. Cái túi mà mở chậm thì còn ai thèm dùng.

Vậy đựng cái túi này vào đâu? Đây là quyết định đầu tiên — và là quyết định định hình cả chương.

---

## 🧰 Chọn chỗ cất túi: Redis primary, không phải cache

Câu hỏi nghe có vẻ học thuật: lưu cart ở đâu? Postgres? Redis? Cả hai? Nhưng nó thực ra là câu hỏi *"cái túi này quan trọng đến mức nào?"* — và trả lời sai sẽ kéo theo cả đống phức tạp không đáng.

Bốn phương án được mang ra cân, mỗi cái thử đeo vào người xem có vừa không:

| 🧰 Phương án | Ý tưởng | Phán quyết |
| --- | --- | --- |
| Postgres only | Cất túi trong két sắt ngân hàng | ❌ Overkill — connection pool, transaction, WAL, fsync cho một cái túi tạm? |
| Postgres + Redis cache | Cất ở két, để bản sao trong túi cho nhanh | ❌ Phức tạp không đáng — cart đâu cần durability mà phải đồng bộ hai chỗ |
| **Redis primary** | Cái túi *chính là* Redis, không có bản gốc nào khác | ✅ **Chọn** — nhanh, đơn giản, mất mát chấp nhận được |
| Redis + snapshot định kỳ về PG | Chụp ảnh cái túi mỗi giờ phòng khi mất | ❌ Over-engineering cho MVP — đang giải quyết vấn đề không tồn tại |

Cái insight cốt lõi, nói thẳng ra: **cart là ephemeral data.** Mất cart = user add lại (khó chịu, không thảm hoạ). Mất order = mất tiền (thảm hoạ). Dùng đúng tool cho đúng mức độ quan trọng — không ai bọc thép cái túi đi chợ.

Postgres in-memory? Không tồn tại. Redis thì in-memory, sub-millisecond, sinh ra để làm việc này. Cái túi tìm được đúng cái móc treo của nó. 🟢

> 🧠 **Senior insight:** không phải mọi data đều cần cùng mức durability. Cart (ephemeral) ≠ Order (permanent) ≠ Analytics (append-only). Chọn storage theo **data lifecycle**, không phải theo "cái gì mình quen dùng". Người ta loại CRUD vào Postgres theo phản xạ — senior thì hỏi trước: *dữ liệu này mất đi thì ai khóc?*

---

## ✨ Bên trong cái túi: Redis Hash và phép thuật HINCRBY

Mở cái túi ra, ta thấy nó được chia thành các **ngăn nhỏ** — mỗi món hàng một ngăn riêng. Đó là Redis Hash:

```
Key:    cart:user:12345
Field:  SKU-001  →  Value: 2
Field:  SKU-007  →  Value: 1
Field:  SKU-042  →  Value: 3
```

Tại sao chia ngăn (Hash) mà không nhét tất cả vào một cục JSON (String)? Vì cách bạn *thêm một món* khác nhau hoàn toàn:

| | 🧵 String (một cục JSON) | 🗂️ Hash (chia ngăn) |
|---|---|---|
| Add 1 item | Lôi *cả túi* ra → parse → sửa → nhét lại (read-modify-write) | `HINCRBY cart:user:123 SKU-001 1` — thò tay vào đúng ngăn, atomic! |
| Get 1 item | Lôi cả túi ra → parse → tìm món | `HGET cart:user:123 SKU-001` — móc đúng ngăn |
| Race condition | 2 tab cùng lôi túi ra sửa → mất một bản ghi (lost update) | `HINCRBY` atomic — không thể mất |
| Memory | một chuỗi JSON to | Redis ziplist optimization cho hash nhỏ |

Phép thuật nằm ở **`HINCRBY`** — nó là hero của chương này. Để thêm một món, bạn không cần *đổ cả túi ra* rồi nhét lại. Bạn thò tay vào đúng cái ngăn của món đó, cộng thêm vào, đậy lại — và Redis đảm bảo thao tác đó là **atomic** tại cấp field.

Hệ quả đẹp: hai tab cùng add cùng một SKU? Quantity = 2, chính xác. Không lost update, không lock, không retry, không một dòng code đồng bộ nào của bạn. Redis gánh hết. Cái túi tự biết cộng dồn.

---

## 💥 Hai cái túi va vào nhau: bài toán merge

Giờ đến cảnh kịch tính nhất của một cái túi không khoá. Dựng cảnh:

> 🛍️ Một anh khách lượn shop ở chế độ **ẩn danh** (chưa login). Anh nhặt 3 món, quăng vào cái túi tạm `cart:anon:abc`. Rồi anh quyết định **đăng nhập** — và hoá ra anh đã có sẵn một cái túi cũ từ lần trước, `cart:user:123`, còn vài món nằm trong đó.
>
> **Hai cái túi va vào nhau.** Cái nào thắng? Hay cả hai cùng sống?

Nếu xử ẩu — chọn một túi, vứt túi kia — anh khách sẽ chửi: *"Ơ món tôi vừa nhặt đâu rồi?"* hoặc *"Ơ món tôi để dành tuần trước đâu?"*. Cả hai đều mất khách. Nên luật merge phải công bằng với cả hai cái túi:

```
Cart anonymous (cart:anon:abc):     SKU-001 × 2, SKU-007 × 1
Cart user cũ   (cart:user:123):     SKU-001 × 1, SKU-042 × 3
                                         ↓ MERGE
Cart sau merge (cart:user:123):     SKU-001 × 3, SKU-007 × 1, SKU-042 × 3
```

Luật một dòng: **cộng dồn quantity theo SKU.** Cùng SKU → cộng lại (món `SKU-001`: 2 + 1 = 3). Khác SKU → giữ cả hai. Túi ẩn danh → xoá sau khi đổ hết sang túi chính.

Nghe đơn giản trên giấy. Nhưng cái túi không khoá nghĩa là *đủ thứ chuyện đời* có thể xảy ra giữa chừng, và mỗi cái phải có lối thoát:

- 📈 **Cộng dồn vượt `maxQtyPerItem=999`?** → Cap tại 999, rollback phần dư. (Không ai cần 1000 cái cùng món, đó là bot.)
- 🧳 **Merge xong túi có > 100 items?** → Reject merge, giữ nguyên túi user cũ. (Túi phình to bất thường = nghi vấn abuse.)
- 💀 **Đang merge nửa chừng thì Redis chết?** → Túi ẩn danh *vẫn còn* (vì ta chưa kịp `DEL` nó) → retry an toàn, không mất món nào.

> ⚠️ **Trap tinh tế ở cú cuối:** thứ tự thao tác phải là *merge trước, DEL túi anon sau*. Nếu lỡ DEL trước rồi mới merge mà chết giữa chừng — cái túi ẩn danh bay màu, món hàng bay theo. Một dòng đảo thứ tự = một khách hàng mất giỏ. Đây là kiểu lỗi mà test happy-path không bao giờ bắt được.

---

## 🏷️ Dán nhãn cái túi: sealed `CartId`

Có hai loại túi — túi ẩn danh và túi đã đăng nhập — và chúng *không được phép lẫn lộn*. Một cái cho phép thao tác không cần JWT, một cái bắt buộc verify quyền sở hữu. Lẫn nhãn = lỗ hổng bảo mật.

Nên cái nhãn được làm bằng type system, không phải bằng String tuỳ tiện:

```java
public sealed interface CartId permits Anonymous, Authenticated {
    record Anonymous(String token) implements CartId {
        public String toRedisKey() { return "cart:anon:" + token; }
    }
    record Authenticated(Long userId) implements CartId {
        public String toRedisKey() { return "cart:user:" + userId; }
    }
}
```

Mỗi loại túi tự biết cách tự đặt tên key Redis của mình — `Anonymous` thành `cart:anon:...`, `Authenticated` thành `cart:user:...`. Không có chỗ nào ghép chuỗi key bằng tay, nên không có chỗ nào ghép sai.

Và khi cần xử lý khác nhau cho hai loại túi, pattern matching switch lo:

```java
switch (cartId) {
    case Anonymous a  -> // cho phép không cần JWT
    case Authenticated u -> // verify JWT ownership
}
```

Compiler đảm bảo: không bao giờ quên xử lý một loại túi. Thêm loại túi mới (ví dụ `Guest` cho khách vãng lai) → build break ở mọi switch chưa handle. Cùng đúng triết lý lính-canh-compiler mà ông gác kho đã mượn ở chương trước — chỉ là lần này nó canh cái nhãn trên túi.

---

## 🧟 Cái túi zombie: TTL strategy

Cái túi cuối cùng phải giải quyết một vấn đề tế nhị: **khi nào thì vứt cái túi bị bỏ quên đi?**

```
TTL: 7 ngày
Refresh: CHỈ khi mutate (add / update / remove), KHÔNG khi read
```

Cái mấu chốt nằm ở chữ *KHÔNG khi read*, và đây là lý do:

Nếu refresh TTL mỗi lần đọc giỏ, thì một user mở app mỗi sáng để xem (chỉ GET cart, không mua gì) sẽ vô tình *làm cái túi sống mãi*. Túi không bao giờ hết hạn → Redis memory phình dần → đầy những **cái túi zombie** 🧟: chủ nó quên từ đời nào, nhưng cứ mở app là nó hồi sinh.

Refresh chỉ khi *mutate* nghĩa là: user còn thực sự bỏ/lấy đồ → cái túi còn đáng giữ, gia hạn 7 ngày. User chỉ ngó qua rồi thôi → túi không được gia hạn, đúng 7 ngày sau tự tan biến. Sạch sẽ, không cần cron job đi dọn, không zombie.

> 💡 Đây là kiểu quyết định "một dòng config nhưng đổi cả hành vi hệ thống". Refresh-on-read nghe vô hại, thậm chí nghe *chu đáo* — nhưng nó là cái bẫy memory leak dài hạn mà phải vài tháng prod mới lộ ra.

---

## 🏁 Kết thúc ngày 5

```
📊 Scorecard:
├── Services:        4 (auth + product + inventory + cart)
├── Storage:         Postgres (3 services) + Redis (cart)
├── Atomic ops:      HINCRBY (no lost update), EXPIRE (auto cleanup)
├── Edge cases:      Merge conflict, cap overflow, TTL zombie
├── Concurrency IT:  100-thread add cùng SKU → correct sum
├── Docs:            5 (ADR-004, 2 lessons, issue merge, interview)
└── Vibe:            "Cái túi nhanh như chớp. User không biết Redis tồn tại — và đó là thành công." 🛍️
```

> 💡 **Senior insight:** câu hỏi đáng giá nhất khi chọn storage không phải *"cái nào nhanh nhất?"* mà là *"dữ liệu này mất đi thì sao?"*. Cart mất → user bực 5 giây. Order mất → công ty đền tiền. Cùng một codebase, hai mức độ trân trọng hoàn toàn khác nhau — và đó là dấu hiệu của một người hiểu data lifecycle, không phải người chỉ biết một cái búa Postgres rồi thấy gì cũng là đinh.

---

*→ Cái túi đã có, nhẹ và nhanh, mất cũng không sao. Nhưng đến khoảnh khắc anh khách bấm "Đặt hàng" — mọi món trong túi phải đổ về một nơi nghiêm túc hơn nhiều. Một nơi mà mất là mất tiền thật, nơi mọi mạch máu của hệ thống cùng chảy về. Cái túi chỉ là phòng chờ. Trái tim mới là đích đến...* 📋
