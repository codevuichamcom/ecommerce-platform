# Chương 23 · 📓 Cuốn sổ không dòng kẻ (và bác thư ký chép cái gì cũng được)

**Day 23 — MongoDB: event store + flexible attributes**

---

> *"Ông kế toán có cuốn sổ kẻ ô vuông: cột nào ra cột nấy, sai ô là ổng gắt. Đẹp, chặt, nhưng đưa ổng cái TV có 'screen_size' và cái áo có 'material' — ổng đơ. 'Hai thứ này điền chung bảng kiểu gì?'. Bên cạnh có bác thư ký tốc ký, cầm cuốn sổ TRẮNG không một dòng kẻ. Khách đọc gì bác chép nấy, hình dạng nào cũng nhận. Nhanh kinh khủng. Chỉ có một điều: đừng bao giờ nhờ bác giữ cho hai trang khớp nhau."*

---

## Bối cảnh

Cuối chương trước, ông thầy bói đã dán xong bản phô-tô đầu tiên (ch.22). Postgres
giữ sổ gốc, Elasticsearch bói theo bản sao, Kafka làm máy phô-tô. Ba kho, chạy ngon.

Nhưng còn hai lời than chưa ai dỗ.

Lời thứ nhất từ **ông kế toán**: *"Cái cột `attributes` JSONB của tôi bắt đầu chật.
TV nhét 'screen_size, resolution, panel'; áo nhét 'size, color, material'. Mỗi loại
hàng một kiểu. Tôi ép vào bảng quan hệ thì hoặc đẻ ra ba chục cột NULL, hoặc dựng cái
bảng EAV gớm ghiếc."*

Lời thứ hai từ **anh Khải**, gõ bàn: *"Team Growth cần report. Top sản phẩm, conversion
funnel — xem bao nhiêu, bỏ giỏ bao nhiêu, chốt đơn bao nhiêu. Event hành vi đang trôi
trong Kafka rồi **bốc hơi** sau retention 7 ngày. Tôi cần chỗ giữ event lâu, query linh
hoạt, mà ĐỪNG có đụng vào DB order/product đang gánh tiền."*

Hai lời than, một bản chất: **dữ liệu không có khuôn**. Schema đa hình, ghi nhiều, đọc
để phân tích. Ông kế toán kẻ-ô-vuông chịu. Cần một người chép tự do.

Hôm nay tuyển **bác thư ký tốc ký**. Tên thật: **MongoDB 7**.

> 💡 **Bẫy phỏng vấn ngay cửa**: "Mongo nhanh hơn Postgres nên dùng" là SAI y như
> "ES nhanh hơn" ở ch.22. Lý do tuyển Mongo là **schema đa hình + TTL + aggregation
> + scale ngang** — không phải tốc độ. Trả lời "nhanh hơn" → interviewer vặn "nhanh
> hơn ở access pattern nào?" và bạn đứng hình.

---

## 📓 Cuốn sổ trắng: bác chép cái gì cũng được

Ông kế toán định nghĩa bảng trước, điền sau. Bác thư ký thì ngược: **chép trước,
hình dạng tự do**. Cùng một collection `analytics_events`, ba loại event ba shape:

```
{ type: "product_viewed", occurredAt: ..., sessionId: "s1", productId: "SKU-A",
  payload: { referrer: "google", device: "mobile" } }

{ type: "order_placed", occurredAt: ..., userId: "u1", productId: "SKU-A",
  payload: { orderId: "...", quantity: 2, unitPrice: 28000000 } }
```

Hai document, hai `payload` khác hẳn nhau, nằm chung một chỗ. Ông kế toán mà thấy
cảnh này chắc ngất. Bác thư ký nhún vai: *"Khác thì khác, tôi chép thôi."*

Nhưng bác không chép loạn hoàn toàn. Cái gì **dùng để tra** thì bác kẻ riêng ra
lề trái (top-level, có index): `type`, `occurredAt`, `productId`. Cái gì chỉ để
**đọc lại** thì nhét trong `payload` tự do. Đây gọi là **hybrid schema** — phần
biết trước thì strong-type, phần đa dạng thì để map:

```java
@Document(collection = "analytics_events")
public class AnalyticsEvent {
    @Id private String id;
    private String type;           // lề trái: tra + index
    private Instant occurredAt;     // lề trái: TTL anchor + filter thời gian
    private String productId;       // lề trái: group top-products
    private Map<String, Object> payload;   // ruột sổ: muốn ghi gì thì ghi
}
```

> ⚠️ **Cạm bẫy EAV** — đây là chỗ junior hay sa: muốn flexible attributes mà cứ
> cố nhét vào SQL thì đẻ ra bảng `(product_id, attr_key, attr_value)`. Hỏi "TV nào
> 4K?" thành **self-join ba tầng**, mất type, index như hạch. Cuốn sổ trắng giải
> bài này bằng một dòng: `{ "attributes.resolution": "4K" }`. Dot-notation, xuyên
> vào field lồng nhau như field thường.

---

## 🗄️ Hai cái bàn cho bác thư ký

Bác không ngồi một chỗ. Day 23 dựng cho bác **hai cái bàn**, mỗi bàn một việc.

**Bàn 1 — `analytics-service` (event store).** Đây là nhà mới của bác, một service
riêng (thứ 8). Vì sao riêng? Vì workload **đối nghịch**: order/product là OLTP cần
low-latency; analytics là OLAP-lite, ghi append ào ào rồi quét aggregation nặng.
Trộn chung = aggregation khoá IO của DB đang gánh tiền. Tách ra, DB-per-service.

Bác nhận event từ hai nguồn. Domain event thật (order đã chốt) đi qua **Kafka**:

```java
@KafkaListener(topics = TopicNames.ORDER_CREATED, groupId = "${spring.application.name}")
public void onOrderCreated(OrderCreatedV1 event) {
    for (OrderCreatedV1.Item item : event.items()) {   // N item → N document riêng
        AnalyticsEvent ae = new AnalyticsEvent(
            EventType.ORDER_PLACED, event.occurredAt(),
            null, event.userId().toString(), item.sku(), payload);
        ingestService.ingest(ae);   // mỗi save = single-document, atomic
    }
}
```

Hành vi UI (xem trang, bỏ giỏ) thì backend **không thấy** — nó đi qua **HTTP beacon**,
y như cái "collect" của Google Analytics. Frontend bắn `POST /analytics/track` rồi
chạy tiếp, không chờ. Trả `202 Accepted`: "nhận rồi, sẽ ghi". Hai nguồn, một cuốn sổ.

**Bàn 2 — `product-service` catalog read-model.** Đây mới hay: cái event
`product.upserted` đã nuôi ông thầy bói ES ở ch.22, giờ **máy phô-tô in thêm một
bản** cho bác thư ký. Cùng một event, **fan-out** tới hai consumer-group khác nhau:

```java
@KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "${spring.application.name}-catalog")
public void onUpserted(ProductUpsertedV1 event) {
    catalogRepository.save(toDocument(event));   // attributes EMBED nguyên cụm
}
```

ES group `-indexer`, Mongo group `-catalog` — hai bản sao độc lập. ES sập không
chặn Mongo, Mongo sập không chặn ES. Một event, ba kho cùng nghe: Postgres (sổ
gốc), ES (mục lục lật-ngược), Mongo (cuốn sổ trắng).

> 🧠 **Senior vs junior**: junior nghe "flexible attributes" là đòi bê hết product
> sang Mongo. Senior giữ **Postgres làm source of truth** (product có invariant
> `price ≥ 0`, `sku` unique — cần ACID), Mongo chỉ là **read-model derived**. Mongo
> KHÔNG thay Postgres. Mỗi kho một việc. Bê data có invariant vào Mongo vì "nghe
> nói nhanh" = cargo-cult, và sẽ trả giá ở mục dưới.

---

## 🔢 Bác làm được phép tính: aggregation pipeline

Chép giỏi chưa đủ, anh Khải cần **báo cáo**. Đây là lúc bác thư ký lôi bàn tính ra.
Aggregation pipeline — chuỗi stage, data chảy qua từng cái như dây chuyền:

```java
// Top sản phẩm bán chạy: lọc → gom theo sản phẩm → đếm → xếp hạng → cắt top N
Aggregation agg = newAggregation(
    match(Criteria.where("type").is(type)
          .and("occurredAt").gte(from)
          .and("productId").ne(null)),       // $match  — thu hẹp
    group("productId").count().as("count"),  // $group  — gom + đếm
    sort(Sort.Direction.DESC, "count"),      // $sort   — xếp hạng
    limit(limit),                            // $limit  — cắt top
    project("count").and("_id").as("productKey"));   // $project — đổi tên
```

Funnel cũng thế, một query lấy count cả ba stage cùng lúc (rẻ hơn ba query riêng),
rồi Java xếp theo đúng phễu **xem → giỏ → chốt** và tính tỉ lệ rớt. Verify thật trên
container Mongo 7.0: 6 view, 2 cart, 3 order → funnel ra `100% → 33% → 50%`, top-products
xếp đúng SKU-A trên SKU-B. **Bốn integration test xanh trên Mongo thật**, không mock.

Nhưng aggregation mà thiếu index thì bác **quét cả cuốn sổ** (COLLSCAN). Nên Day 23
kẻ lề tường minh — không để `auto-index-creation` tự đẻ index lung tung:

```java
@EventListener(ApplicationReadyEvent.class)
public void ensureIndexes() {
    indexOps.ensureIndex(new Index()        // 1) compound: lọc type + thời gian
        .on("type", Sort.Direction.ASC)
        .on("occurredAt", Sort.Direction.DESC).named("type_occurredAt"));
    indexOps.ensureIndex(new Index()        // 2) TTL: event tự rụng sau 90 ngày
        .on("occurredAt", Sort.Direction.ASC)
        .expire(Duration.ofDays(ttlDays)).named("occurredAt_ttl"));
}
```

Cái TTL index này là lý do anh Khải không lo Mongo phình vô hạn: bác thư ký có một
**cậu phụ việc** chạy mỗi ~60 giây, đi xé những trang quá 90 ngày.

> ⚠️ Nhưng cậu phụ việc **không** xé tức thì. Document quá hạn có thể sống thêm tới
> ~60s. Report phải chịu được. Và field TTL phải là **Date** thật (`occurredAt` là
> Instant → BSON Date) — lưu epoch long thì cậu phụ việc mù, không xé gì cả.

---

## 💥 Đừng nhờ bác giữ hai trang khớp nhau

Giờ đến cái "một điều" ở epigraph. Bác thư ký chép nhanh, nhận mọi hình dạng — nhưng
nếu bạn bảo *"ghi đơn hàng ở trang 5, ghi danh sách món ở trang 12"* rồi giữa chừng
bác **ngất**, thì trang 5 có đơn, trang 12 trống, và **không ai xé lại trang 5**.

Đây là cú sốc của dev từ Postgres sang: **single-document write thì atomic, nhưng
multi-document transaction chỉ có từ Mongo 4.0 và bắt buộc replica set**. Cái Mongo
standalone ở docker-compose dev? Gọi transaction là nó cười vào mặt:

```
Transaction numbers are only allowed on a replica set member or mongos
```

→ Order mồ côi item. Không rollback. (Chi tiết: [issue 23](../issues/23-mongodb-no-transaction-trap.md).)

Cách né của senior **không phải** bật replica set cho chắc. Mà là **đổi design để
khỏi cần**: align *aggregate boundary = document boundary*. Thứ cần atomic thì nằm
gọn trong một document (embed). Thứ cần ACID nhiều thực thể (order + items + total)
thì... để **ông kế toán Postgres** giữ — Day 6 đã làm rồi. Day 23 mọi write Mongo
đều single-document: mỗi event một trang, mỗi product một trang. Bác không bao giờ
phải giữ hai trang khớp nhau.

> 🧠 Bật replica set để cứu một design sai = thêm hạ tầng (3 node, election, oplog)
> cho thứ lẽ ra không cần. Senior đổi design, không đổ hạ tầng vào.

---

## 🪤 Cú trượt vỏ chuối: bác mới vào làm, cả toà nhà sụp

Build xong, chạy `analytics-service` lên — **context load fail ngay**:

```
NoClassDefFoundError: org/springframework/security/access/AccessDeniedException
```

Ủa? Analytics có đụng gì security đâu. Hoá ra: ở sảnh `common-lib` có một **ông
quản lý điểm danh** (`GlobalExceptionHandler`, một `@RestControllerAdvice`). Mỗi
service vào toà nhà, ông điểm danh **mọi nhân viên** — trong đó có một anh chuyên
trị `AccessDeniedException`. Suốt 6 service trước, ai cũng tình cờ đeo phù hiệu
spring-security, nên ông điểm danh trót lọt. Bác thư ký analytics vào, **không đeo
phù hiệu** (service tối giản, không security) → ông quản lý gọi tên anh-trị-403,
anh này không tồn tại → ông ngã, kéo cả service sụp.

Đây là **latent coupling** ngủ 6 ngày, lộ khi có người tối giản đầu tiên. Fix:
tách anh-trị-403 ra một advice riêng, dán biển *"chỉ điểm danh khi toà nhà CÓ
security"*:

```java
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
public class SecurityExceptionHandler { ... }   // name= string → Spring đọc qua ASM, không load class
```

Service có security → anh active. Service không → Spring lọc anh ra từ vòng scan,
class không bao giờ load, không ai ngã. (Trap [08] trong [ai-junior-traps](../review/ai-junior-traps.md).)

> 💡 **Bài học review**: class shared (advice/filter trong common-lib) mà hard-reference
> class của một dependency *optional* = ép mọi consumer phải có dependency đó. "6
> service chạy ổn" KHÔNG chứng minh không coupling — chỉ chứng minh chưa ai tối giản đủ.

---

## Kết thúc ngày 23

```
Day 23 — MongoDB
├── 🗄️ analytics-service (service #8): event store đa hình + 2 nguồn (Kafka + beacon)
├── 🔢 aggregation: top-products + funnel — 4 IT xanh trên Mongo 7.0 thật
├── ⏳ index: compound (type+occurredAt) + TTL 90 ngày — verify expireAfter thật
├── 📓 product catalog read-model: cùng event product.upserted fan-out (ES + Mongo)
├── 🔍 attribute filter động: { "attributes.resolution": "4K" } — 3 IT xanh
├── 🧱 Postgres VẪN là source of truth — Mongo/ES chỉ derived (anti-cargo-cult)
├── 🪤 trap [08]: common-lib advice hard-couple spring-security → tách @ConditionalOnClass
└── 📚 5 doc: lesson 23 + 23b + issue 23 + interview + ADR-011

Vibe: "Bác thư ký chép cái gì cũng được — chỉ đừng nhờ bác giữ hai trang khớp nhau."
```

Hệ thống giờ có **bốn kho**: sổ gốc quan hệ (Postgres), bộ nhớ chớp nhoáng (Redis),
mục lục lật-ngược (Elasticsearch), cuốn sổ trắng (MongoDB). Mỗi kho một tính nết, một
việc. Đẹp.

Nhưng đẹp cũng là lúc nguy hiểm nhất. Bốn cái búa trên bàn, và con người ta có thói
quen cầm cái búa quen tay đập mọi cái đinh. Anh Khải sẽ hỏi câu mà mọi senior phải trả
lời trôi chảy hoặc lộ ngay là junior: *"Đơn hàng để đâu? Giỏ hàng để đâu? Session để
đâu? Báo cáo để đâu? Và vì sao — nói tôi nghe **vì sao** mỗi cái một chỗ."*

*→ Chương 24: bốn cái kho xếp hàng, một **bảng quyết định**. Khi nào SQL, khi nào
NoSQL, khi nào ES — và làm sao không cầm búa Mongo đập cái đinh Postgres.*
