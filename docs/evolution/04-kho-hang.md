# Chương 4 · 🏭 Kho hàng — nơi invariant là vua

**Day 4 — Inventory Service (DDD)**

---

> *"Overselling là tội không thể tha thứ. Bạn hứa với khách có hàng, rồi nói 'xin lỗi, hết rồi'. Một lần mất niềm tin, mười lần không lấy lại. Trong cái kho này, có một ông gác cổng không bao giờ ngủ — và phương châm của ông là: not on my watch."*

---

> 🎬 **Chương này có gì:** một ông gác kho tự phong làm quan toà, một cuộc đua 100 người giành 50 món hàng, ba bức tường phòng thủ xếp chồng nhau, và một câu hỏi treo lơ lửng — *nếu cả ba tường cùng thủng thì sao?* Vào kho thôi. 🏭

---

## 🎬 Bối cảnh: kệ hàng không biết tự đếm

Chương trước, hàng đã lên kệ — đẹp, có phân trang, có search. Nhưng cái kệ đó có một bí mật xấu hổ: **nó không biết tự đếm**. Nó khoe "còn hàng" cho cả trăm người cùng lúc mà không ai kiểm xem trong kho thực sự còn mấy món.

Đến lúc thuê người canh kho.

Và đây là ngày nhân vật mới bước vào — không phải một service CRUD hiền lành, mà là một **ông gác kho** 🧐 với cái tính khó ưa: ông coi mỗi con số tồn kho như báu vật, và ông tin rằng nhiệm vụ thiêng liêng nhất đời mình là **không bao giờ để bán quá số hàng đang có**. Một câu thần chú duy nhất: *not on my watch*.

Vì sao ông phải gồng đến thế? Hình dung cảnh này:

> 🔥 **Flash sale.** 100 người cùng bấm "Mua ngay" trên một sản phẩm còn đúng **1 cái**. Ai được? Ai không? Và câu hỏi xương sống: **làm sao đảm bảo không bán 2 cái khi chỉ có 1?**

Đây không phải bài toán CRUD. Đây là **concurrency + business invariant** — đúng cái loại bài toán khiến DDD lần đầu tiên trong project này đáng được mời vào sân. Không phải vì DDD "cool". Vì bài toán *bắt buộc* phải có một pháo đài. 🏰

---

## 🏰 Aggregate `Stock` — pháo đài bất khả xâm phạm

Pháo đài đầu tiên ông gác kho dựng lên là chính cái Aggregate. Luật của pháo đài viết thẳng vào comment, in hoa, không khoan nhượng:

```java
public class Stock extends BaseEntity {
    private String sku;
    private int quantity;    // tổng tồn kho
    private int reserved;    // đã giữ chỗ, chờ thanh toán

    // INVARIANT: reserved ≤ quantity — LUÔN LUÔN. KHÔNG NGOẠI LỆ.
}
```

Để ý: **không có setter public**. Không một cách nào từ bên ngoài thò tay vào set `reserved = 999`. Cổng thành đóng. Muốn giữ chỗ hàng? Phải gõ cửa, gọi đúng method, và để ông gác kho phán xử:

```java
public void reserve(int qty) {
    if (this.reserved + qty > this.quantity) {
        throw new InsufficientStockException(sku, available(), qty);
    }
    this.reserved += qty;
    registerEvent(new StockReserved(this.sku, qty));
}
```

Method này tự kiểm tra invariant, tự `throw` nếu ai đó định vi phạm, tự phát domain event nếu mọi thứ hợp lệ. Nói cách khác: **Aggregate vừa là judge, vừa là jury, vừa là executioner** ⚖️. Một mình ông quyết, không qua trung gian, không ai cãi được. Đó chính là tinh thần của Aggregate root — gom trạng thái + luật bảo vệ trạng thái vào *một* chỗ duy nhất.

> 🧠 **Senior insight:** sức mạnh của Aggregate không nằm ở chỗ "có method `reserve()`". Nó nằm ở chỗ *không tồn tại con đường nào khác* để thay đổi `reserved`. Cổng càng ít, càng dễ canh.

---

## ⚔️ Cuộc đua 100 threads — pháo đài bị vây

Nhưng ông gác kho sớm phát hiện một sự thật phũ phàng: `reserve()` chỉ đúng khi **đúng một người** gõ cửa. Trong flash sale, 100 người gõ cửa *cùng một lúc*. Và đây là lúc pháo đài rung lên:

```
Thread A: đọc stock (version=1, reserved=0) → reserve(1) → reserved=1 → save
Thread B: đọc stock (version=1, reserved=0) → reserve(1) → reserved=1 → save
                                                                          ↑ CONFLICT!
```

Cả hai cùng nhìn thấy `reserved=0`. Cả hai cùng tự tin set `reserved=1`. Kết quả: bán 2 cái khi chỉ có 1. **Overselling** — đúng cái tội mà ông gác kho thề sẽ không bao giờ để xảy ra *on his watch*.

Bức tường thứ nhất của pháo đài: **`@Version` + `@Retryable`** — optimistic lock.

```java
// BaseEntity đã có sẵn
@Version
private Long version;
```

Cơ chế đơn giản mà hiểm: khi Thread B `save`, Hibernate liếc xuống DB và phát hiện *"version trong DB giờ là 2 rồi (Thread A vừa update), nhưng anh đang cầm version 1 — anh đọc dữ liệu cũ rồi anh bạn"* → ném `OptimisticLockingFailureException`. Thread B không được ghi đè. Thay vào đó, nó **retry**: đọc lại stock mới nhất (`reserved=1`), kiểm tra invariant lại từ đầu, còn hàng thì giữ tiếp, hết hàng thì lịch sự `throw`.

```java
@Retryable(
    retryFor = OptimisticLockingFailureException.class,
    maxAttempts = 4,
    backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2)
)
@Transactional(propagation = REQUIRES_NEW)
public StockReservedResult reserve(String sku, int qty) { ... }
```

Cái `@Backoff` mới là chỗ tinh tế. Exponential backoff: **50ms → 100ms → 200ms → 500ms**. Không phải cả 100 thread cùng retry một nhịp (thundering herd — bầy trâu cùng húc một cánh cửa), mà mỗi thằng lùi lại một quãng khác nhau, lần lượt vào. Pháo đài chịu được vây hãm vì nó không để cả đám tràn vào một lúc.

> ⚠️ **Trap kinh điển:** nhiều người để `maxAttempts` cao chót vót "cho chắc". Nhưng retry vô tận dưới flash sale = thread chết kẹt, pool cạn, service treo. Bốn lần là một con số có chủ đích — đủ để qua cú đụng độ ngẫu nhiên, không đủ để biến retry thành DoS tự gây.

---

## 🧪 Bài test quyết định: 100 threads, 1 SKU, stock = 50

Pháo đài nói thì hay, nhưng ông gác kho là người *không tin lời nói* — ông tin con số. Nên có một bài test đóng vai trò phán quyết cuối cùng:

```java
@Test
void concurrency_100_threads_no_oversell() {
    // Given: stock = 50
    // When: 100 threads cùng reserve(1)
    // Then: exactly 50 success, exactly 50 InsufficientStockException
    //       final reserved = 50, NO oversell
}
```

**Kết quả: PASS.** 50 thành công. 50 nhận `InsufficientStockException`. `reserved = 50`. Không hơn một, không kém một. 🟢

Toán học không biết nói dối, và ông gác kho ngủ thêm được một giấc — nhưng chỉ một mắt.

---

## 🧱 Bức tường cuối: DB CHECK constraint — belt AND suspenders

Code đúng rồi, test xanh rồi. Nhưng ông gác kho là dân *belt and suspenders* — thắt lưng **và** đeo quần treo, vì lỡ một cái đứt thì còn cái kia. Code có thể đúng hôm nay, nhưng:

- Một ngày đẹp trời ai đó chạy `UPDATE` raw SQL để "fix nhanh" data.
- Một quirk của ORM ghi xuống một giá trị lạ.
- Một service mới mọc lên đụng vào bảng này mà quên gọi `reserve()`.

Nên có bức tường cuối cùng, dựng thẳng dưới tầng database — nơi *không một dòng code nào* lách qua được:

```sql
ALTER TABLE stocks ADD CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0);
ALTER TABLE stocks ADD CONSTRAINT chk_quantity_non_negative CHECK (quantity >= 0);
ALTER TABLE stocks ADD CONSTRAINT chk_reserved_lte_quantity CHECK (reserved <= quantity);
```

Dù code có bug, dù ORM giở quẻ, dù ai đó `UPDATE` tay lúc nửa đêm — database **từ chối** ghi giá trị vi phạm invariant. Đây là lớp tường không cần tin ai cả: nó không quan tâm logic ứng dụng nghĩ gì, nó chỉ thừa hành đúng một luật.

Giờ ta có thể xếp ba bức tường lại để nhìn cho rõ pháo đài gồm những gì:

| 🧱 Lớp phòng thủ | Cơ chế | Chặn cái gì | Nằm ở đâu |
| --- | --- | --- | --- |
| 🏰 **Aggregate method** | `reserve()` tự check invariant | Code gọi sai, set bừa giá trị | Trong domain (Java) |
| ⚔️ **Optimistic lock** | `@Version` + `@Retryable` | Race condition khi ghi đồng thời | Hibernate ↔ DB |
| 🧱 **CHECK constraint** | `reserved <= quantity` ở SQL | Raw SQL, ORM quirk, service mới ẩu | Trong database |

> 💡 Ba lớp này không thừa thãi — mỗi lớp bắt một *loại* lỗi mà lớp khác bỏ lọt. Aggregate bắt lỗi logic. Optimistic lock bắt lỗi đồng thời. CHECK constraint bắt lỗi *ngoài tầm với của code*. Đây là defense-in-depth thật sự, không phải gắn cho có.

---

## 🚪 Trạng thái reservation cũng là một bức tường

Ông gác kho không chỉ canh *con số* tồn kho. Ông còn canh *vòng đời* của mỗi lượt giữ chỗ. Một reservation đi từ "đang chờ" → "đã giữ" → "đã xác nhận" hoặc "đã nhả ra". Và ở đây, ông mượn thêm một anh lính canh không ăn lương, không ngủ, không bao giờ nghỉ phép: **compiler**.

```java
public sealed interface ReservationStatus
    permits Pending, Reserved, Released, Confirmed {

    record Pending() implements ReservationStatus {}
    record Reserved(Instant reservedAt) implements ReservationStatus {}
    record Released(String reason) implements ReservationStatus {}
    record Confirmed(String orderId) implements ReservationStatus {}
}
```

Để ý chữ `sealed`. Nó không phải String tuỳ tiện, cũng không phải enum trơ trọi. Nó là một **danh sách đóng** — bốn trạng thái này thôi, không hơn. Mỗi trạng thái mang đúng dữ liệu nó cần: `Reserved` có `reservedAt`, `Confirmed` có `orderId`, `Released` có `reason`. Không có cái field nullable kiểu *"chỉ có giá trị khi status = X"*.

Cái hay nằm ở pattern matching switch: vì danh sách đóng, compiler *biết hết* các nhánh có thể, nên không cần `default`. Và đây là phần khiến nó trở thành một bức tường thật:

> 🧠 Một ngày nào đó ai đó thêm trạng thái `Expired` vào sealed interface. Lập tức **build break** ở *mọi* chỗ switch chưa xử lý `Expired`. Compiler chỉ tay vào từng dòng: "anh quên chỗ này, và chỗ này nữa". Zero runtime surprise — không có chuyện `IllegalStateException` nhảy ra lúc 3h sáng vì quên một nhánh.

Nói cách khác: con số tồn kho được CHECK constraint canh, còn *trạng thái* reservation được compiler canh. Cùng một triết lý pháo đài — chỉ là bức tường này được dựng bằng type system thay vì SQL. Ông gác kho có thêm một lính canh, và lính này tuần tra ngay tại lúc *compile*, trước cả khi code kịp chạy.

---

## 🏁 Kết thúc ngày 4

```
📊 Scorecard:
├── Services:        3 (auth + product + inventory)
├── DDD services:    1 (inventory — đủ 3 tiêu chí)
├── Invariants:      3 (reserved≤quantity, non-negative, atomic reserve)
├── Concurrency:     100-thread test PASS, zero oversell
├── Defense layers:  3 (aggregate method + optimistic lock + DB CHECK)
├── Docs:            5 (ADR-003, 2 lessons, issue overselling, interview)
└── Vibe:            "Kho hàng bất khả xâm phạm. Overselling? Not on my watch." 🧐
```

> 💡 **Câu hỏi phỏng vấn kinh điển:** *"Optimistic vs Pessimistic locking — khi nào dùng cái nào?"*
>
> **Strong answer:** Optimistic khi conflict rate thấp (<5%), read-heavy — đặt cược là *hiếm khi đụng nhau*, đụng thì retry. Pessimistic khi conflict rate cao, write-heavy, hoặc operation không idempotent (không retry an toàn được). Inventory reserve: bình thường conflict ~2-5% → optimistic là đúng. Nhưng flash sale spike lên 30-50% → optimistic retry storm, lúc đó chuyển sang **Redis Lua atomic decrement** (Day 33). Tức là: chọn lock theo *conflict rate thực tế*, không theo niềm tin.

---

*→ Pháo đài kho hàng đã dựng, ba bức tường xếp chồng, ông gác kho ngủ một mắt. Nhưng khách thì chưa mua ngay — họ cần một chỗ để gom hàng, lượn lờ, đắn đo. Một cái túi nào đó. Mà túi thì... ai khoá làm gì?...* 🛒
