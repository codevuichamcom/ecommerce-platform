# Chương 8 · 📡 Đường ống ngầm

**Day 8 — Kafka Setup + HTTP Interface vs Feign**

---

> *"Một thành phố trưởng thành không phải lúc nó xây thêm nhà cao tầng. Mà là lúc nó ngừng cho từng hộ dân tự kéo ống nước sang nhà hàng xóm xin từng xô — và bắt đầu đào một hệ thống ống ngầm chạy dưới lòng đất."*

---

> 🎬 **Chương này có gì:** một thành phố ngừng xin nước hàng xóm, năm đường ống ngầm khắc tên không xoá được, một bộ van chống nước-không-tới-bể, một loại ống không bao giờ bơm trùng, vài hộ dân lấy nước độc lập từ cùng một ống chính, và một câu hỏi hóc búa — *"call đồng bộ thì nên dùng loại ống nào?"*. Đội mũ bảo hộ vào, ta xuống cống. 🦺

---

## 🏙️ Bối cảnh: cái thành phố cứ phải xin nước nhau

Chương trước, ta dọn dẹp căn nhà cho gọn, nhưng phát hiện cả khu phố vẫn chung một cầu dao: phòng này giật mình thì phòng kia ngã theo.

Cụ thể hơn, hãy hình dung Week 1 như một khu dân cư nguyên thuỷ. Nhà nào cần nước thì xách xô chạy sang nhà hàng xóm xin — **trực tiếp, mặt đối mặt, đứng chờ**. `PlaceOrder` cần hàng thì xách xô chạy sang nhà `Cart`, rồi sang nhà `Inventory`, đứng đợi từng nhà múc nước xong mới về. Trông thì tình làng nghĩa xóm. Nhưng:

- 🚱 Nhà `Inventory` đóng cửa đi vắng 3 giây → `Order` đứng gõ cửa 3 giây → user thấy spinner quay mòng mòng → bỏ đi.
- 🐌 Nhà `Cart` múc nước chậm → `Order` chờ chậm → cả dây chuyền phía sau chậm theo.
- 🔧 Sửa ống nhà `Inventory` (deploy) → 30 giây nhà đó cúp nước → 30 giây cả khu không ai đặt được hàng.

Cái bệnh này có tên: **temporal coupling** — mọi nhà phải *cùng thức, cùng có mặt một lúc* thì hệ thống mới chạy. Đây không phải kiến trúc microservice. Đây là một **distributed monolith** đội lốt — chia ra cho oai, nhưng vẫn dính nhau như sam.

Day 8 mang máy đào tới. Bắt đầu khoan đường ống ngầm. 🚜

---

## 🚇 Năm đường ống — và cái tên khắc vào bê tông

Đào ống đầu tiên là dựng Kafka (KRaft mode, không Zookeeper). Mỗi **topic** là một đường ống chạy ngầm dưới thành phố. **Event** là nước chảy trong ống. Nhà nào cần thì cứ bơm nước vào ống của mình, ai cần thì tự rút ra — không phải gõ cửa ai cả.

Năm đường ống đầu tiên được khoan:

```
order.created          ← Order bơm vào khi có đơn mới
order.cancelled        ← Order bơm vào khi đơn bị huỷ
payment.completed      ← Payment bơm vào khi thanh toán xong
inventory.reserved     ← Inventory bơm vào khi giữ hàng thành công
notification.outgoing  ← Notification bơm vào khi cần gửi thông báo
```

Đây là chỗ phải cẩn thận như đổ móng nhà: **tên ống là một bản hợp đồng (contract), và một khi đã đổ bê tông thì khắc luôn — không xoá được.** Đặt sai tên hôm nay, sáu tháng sau có 15 hộ dân (consumer) đang cắm vòi vào ống đó để lấy nước. Lúc ấy muốn đổi tên ống = đào lại cả con đường, báo lại từng hộ. Nightmare.

Nên có quy tắc khắc tên: `<domain>.<past-tense-event>` — tên ống phải là **một sự thật đã xảy ra**, không phải một mệnh lệnh.

| ❌ Sai (mệnh lệnh) | ✅ Đúng (sự thật đã xảy ra) |
| --- | --- |
| `reserve-stock` | `inventory.reserved` |
| `send-email` | `notification.outgoing` |
| `create-order` | `order.created` |

> 💡 **Vì sao là past-tense?** Mệnh lệnh ngụ ý *"tôi ra lệnh cho anh làm X"* — tức là publisher biết và phụ thuộc vào ai đó phải thực thi. Còn sự thật đã xảy ra chỉ tuyên bố *"việc X đã xong rồi đấy, ai quan tâm thì tự lo"*. Đó chính là tinh thần decoupling: ống chỉ thông báo nước đã chảy, không chỉ định ai phải uống.

---

## 🔧 Bộ van phía đầu nguồn: cấu hình producer "hoang tưởng"

Đào ống xong, phải lắp van ở đầu nguồn — chỗ nhà mình bơm nước vào ống. Đây là cấu hình **producer**, và nó được chỉnh ở chế độ paranoid (hoang tưởng) có chủ đích:

```yaml
spring:
  kafka:
    producer:
      acks: all                              # Leader + toan bo ISR phai xac nhan
      properties:
        enable.idempotence: true             # Exactly-once per partition
        max.in.flight.requests.per.connection: 5  # Van giu dung thu tu
        retries: 2147483647                  # Retry mai (bi chan boi delivery.timeout.ms)
```

Ba con van này, mỗi con đỡ một thảm hoạ. Phải hiểu rõ từng cái.

### 🚰 Van 1 — `acks=all`: kiểm tra nước có thật sự tới bể chứa

Tưởng tượng bạn bơm nước vào ống. Làm sao biết nước *thật sự* tới bể chứa, chứ không phải mới chảy được nửa đường thì rò mất?

- Với `acks=1`: **trạm bơm đầu tiên (leader)** gật đầu *"nhận rồi"* → producer vui vẻ đi tiếp. Nhưng nếu trạm bơm đó **nổ tung trước khi kịp đẩy nước sang các bể dự phòng (ISR)** → nước biến mất, không ai hay. Xác suất thấp (~0.3% khi leader failover), nhưng **0.3% của 1 triệu order/ngày = 3000 order bốc hơi không dấu vết.** 3000 khách hàng trả tiền mà đơn không bao giờ được xử lý. Không chấp nhận được.
- Với `acks=all`: nước phải chảy tới **trạm bơm chính + tất cả bể dự phòng đồng bộ (ISR)** thì van mới gật đầu. An toàn tuyệt đối. Cái giá? Thêm ~2-5ms latency. Một cái giá rẻ mạt cho việc không mất 3000 đơn.

### 🔁 Van 2 — `enable.idempotence=true`: ống không bao giờ bơm trùng

Khi `retries` đặt ở mức *vô cực* (retry mãi cho tới khi thành công), có một rủi ro: producer bơm nước, mạng lag, nó tưởng thất bại nên **bơm lại** — và thế là cùng một xô nước vào ống hai lần. Nước trùng.

`enable.idempotence=true` gắn cho mỗi xô nước một số thứ tự. Trạm bơm thấy số trùng → vứt cái thứ hai đi. **Exactly-once per partition.** Ống tự biết cái nào đã bơm rồi.

### 🎯 Van 3 — `max.in.flight=5`: bơm nhanh mà không loạn thứ tự

Câu hỏi bẫy kinh điển: *"Muốn giữ đúng thứ tự message, chẳng phải nên đặt `max.in.flight=1` (mỗi lần chỉ bơm một xô) sao?"*

Sai, nếu đã bật idempotence. Từ Kafka 1.0+, khi `enable.idempotence=true`, Kafka **đảm bảo thứ tự kể cả khi có 5 xô đang bay trong ống cùng lúc** — nhờ số thứ tự ở Van 2. Kết quả: throughput nhanh gấp 5 lần mà **không hy sinh ordering**. Vừa nhanh vừa đúng.

---

## 🏘️ Đầu cuối: cấu hình consumer — các hộ dân lấy nước

Giờ tới đầu kia của đường ống — chỗ các hộ dân cắm vòi rút nước ra. Đây là cấu hình **consumer**:

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false              # Chi commit SAU khi xu ly xong
      isolation-level: read_committed        # Khong uong nuoc tu giao dich bi huy
    listener:
      ack-mode: record                       # Commit sau moi ban ghi, khong gom lo
```

Dòng quan trọng nhất, **non-negotiable** cho production, là `enable-auto-commit: false`.

Hãy hình dung `auto-commit` như một hộ dân ký biên nhận *"đã nhận đủ nước"* **ngay khi nước vừa chạm vòi**, trước cả khi kịp hứng vào xô. Rủi ro: hứng được nửa xô thì nhà mất điện (process crash) — nhưng biên nhận đã ký, Kafka tưởng nhà này xong rồi, nước không gửi lại. **Message mất.**

Với `enable-auto-commit=false`, hộ dân chỉ ký biên nhận **sau khi đã hứng đầy xô và cất vào bể** (process xong). Crash giữa chừng? Chưa ký → Kafka gửi lại từ đầu. Đây là **at-least-once delivery**: thà nhận thừa rồi tự lọc, còn hơn nhận thiếu mà mất luôn. Với business event (đơn hàng, thanh toán), at-least-once luôn thắng at-most-once.

> ⚠️ **Cạm bẫy:** at-least-once nghĩa là *có thể nhận trùng*. Hộ dân phải tự biết cách lọc nước trùng — và đó chính là câu chuyện **idempotent consumer** mà chương sau sẽ kể.

---

## 🚿 Chọn loại ống nào cho call đồng bộ: HTTP Interface vs OpenFeign

Không phải mọi thứ trong thành phố đều chuyển sang ống ngầm. Vẫn có những lúc một nhà cần *hỏi nhanh* nhà khác một câu rồi đợi trả lời ngay — call **đồng bộ**. Ví dụ: order cần lấy snapshot thông tin một sản phẩm *ngay lập tức* để hiển thị. Việc này không hợp để bơm qua ống ngầm (async); nó cần một đường ống nổi, hai chiều, hỏi-đáp tức thì.

Câu hỏi là: **dùng loại ống nổi nào?** Day 8 đặt hai thế hệ ống cạnh nhau để so:

```java
// OpenFeign — loại ống quen thuộc, đã dùng nhiều năm
@FeignClient(name = "product-service", url = "${app.services.product.url}")
public interface ProductFeignClient {
    @GetMapping("/products/{sku}/snapshot")
    ProductSnapshot getSnapshot(@PathVariable String sku);
}

// Spring 6.1 HTTP Interface — loại ống mới, hãng làm sẵn trong nhà
public interface ProductHttpInterfaceClient {
    @GetExchange("/products/{sku}/snapshot")
    ProductSnapshot getSnapshot(@PathVariable String sku);
}
```

Nhìn ngoài giống hệt. Khác nhau nằm ở vật liệu bên trong:

| Tiêu chí | OpenFeign | HTTP Interface |
| --- | --- | --- |
| Nguồn gốc | Netflix OSS (maintenance mode) | Spring native (đang phát triển) |
| HTTP client bên dưới | Apache HttpClient (blocking) | RestClient / WebClient (linh hoạt) |
| Virtual Thread | Phải config thêm | Hỗ trợ native |
| Xử lý lỗi | `FeignException` riêng | `RestClientException` chuẩn Spring |
| Interceptor | `RequestInterceptor` của Feign | `ClientHttpRequestInterceptor` chuẩn |
| Tương lai | Hướng deprecated | Con đường Spring khuyến nghị |

**Phán quyết: chọn HTTP Interface.** ✅ Ống do chính hãng Spring làm, không cần kéo thêm dependency ngoài, compile-time safe, và đặc biệt là *thân thiện với Virtual Thread* — vốn đã bật từ Day 2. OpenFeign giữ lại cho mấy service legacy chưa kịp migrate, nhưng đồ mới thì đi đường mới. (Quyết định này được chốt thành ADR-005.)

> 💡 **Senior note:** đừng chọn HTTP client theo cảm tính "cái nào quen". Chọn theo *hướng đi của framework* + *tính tương thích với hạ tầng hiện tại* (ở đây là Virtual Thread). Một dependency ở maintenance mode hôm nay là một khoản nợ kỹ thuật của ngày mai.

---

## 📜 Khắc nhãn lên dòng nước: event schema & versioning

Nước chảy trong ống cần có nhãn — để hộ dân đầu cuối biết đang hứng cái gì, version mấy. Đây là **event schema**, và nó dùng lại đúng món đồ chơi sealed interface đã quen từ chương Order:

```java
public sealed interface DomainEvent permits
    OrderCreatedV1, StockReservedV1, PaymentCompletedV1, NotificationOutgoingV1 {

    String eventId();       // UUID — dedup key
    Instant occurredAt();   // Khi nao event xay ra
    String eventType();     // "order.created.v1"
    int eventVersion();     // Schema version
}
```

Hai nguyên tắc khắc nhãn, để hợp đồng không vỡ:

- ➕ **Additive-only contract:** thêm field mới thì OK (hộ dân cũ kệ field lạ, vẫn uống được nước). Nhưng **xoá hoặc đổi tên field = breaking change** → phải mở ống v2 + dual-publish (bơm song song cả v1 lẫn v2 một thời gian) cho tới khi mọi hộ dân chuyển sang v2 xong.
- 🔑 **`eventId` là dedup key:** nhớ at-least-once có thể gửi nước trùng chứ? Consumer thấy hai xô cùng `eventId` → chỉ xử lý một. Công thức vàng: **at-least-once delivery + idempotent consumer = effectively exactly-once.** Vừa không mất, vừa không trùng.

---

## 🧵 Hộ dân chạy trên sợi chỉ nhẹ: Virtual Thread listener

Mỗi xô nước (Kafka message) được xử lý trên một virtual thread riêng:

```java
factory.getContainerProperties().setListenerTaskExecutor(
    new SimpleAsyncTaskExecutor(threadName -> Thread.ofVirtual().name(threadName).start())
);
```

1000 xô nước cùng lúc? 1000 virtual thread — mỗi cái tốn ~1KB thay vì ~1MB như platform thread. Không lo cạn thread pool. Đường ống có thể chảy ào ào mà nhà không bị nghẹt người xử lý.

---

## 🏁 Kết thúc ngày 8

```
📊 Scorecard:
├── Infrastructure:  Kafka KRaft + 5 topic + Zipkin (tracing)
├── Events:          4 domain event record (v1 schema)
├── Producers:       order-service (order.created)
├── Consumers:       notification-service (scaffold, mới log)
├── HTTP client:     2 loại đặt cạnh nhau (Feign + HTTP Interface)
├── Decision:        Chọn HTTP Interface (ADR-005)
├── Producer config: acks=all + idempotence + max.in.flight=5
├── Consumer config: manual commit + at-least-once
├── Docs:            6 (lesson kafka, lesson feign-vs-http, architecture event-flow, ADR, issue, interview)
└── Vibe:            "Ống đã đặt xong. Nước chưa chảy. Mai mở van." 🚰
```

```mermaid
graph LR
    subgraph "Day 8 — Ong da dat, nuoc chua chay"
        Order[📋 Order] -->|publish| T1[order.created]
        T1 -.->|consume| Notif[📬 Notification]
        T1 -.->|Day 9| Inv[🏭 Inventory]

        Inv -.->|Day 9| T2[inventory.reserved]
        T2 -.->|Day 9| Order
        T2 -.->|Day 9| Notif
    end

    classDef topic fill:#fde68a,stroke:#d97706,color:#000
    classDef service fill:#bfdbfe,stroke:#2563eb,color:#000
    classDef future fill:#e5e7eb,stroke:#6b7280,color:#000

    class T1,T2 topic
    class Order,Notif,Inv service
```

> 💡 **Senior mindset:** Kafka không phải đũa thần. Đào ống ngầm là tốn kém: thêm eventual consistency, lo message ordering, theo dõi consumer lag, xử lý DLT. Chỉ đào khi **temporal decoupling** đáng giá hơn **sự đơn giản**. Cho luồng đặt đơn? Đào ngay. Cho việc "lấy product theo ID"? Tuyệt đối đừng — cứ một đường ống nổi hỏi-đáp là đủ.

---

*→ Đường ống đã đặt xong, các con van đã siết chặt. Ngày mai, ai đó sẽ mở van đầu nguồn — nước bắt đầu chảy. Và lần đầu tiên, nhà `Order` sẽ phải học một điều khó: bơm nước vào ống rồi **buông tay**, tin rằng phía cuối ống có người hứng...* 💧
