package com.ecom.analytics.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

/**
 * Day 23 — event store document trong collection {@code analytics_events}.
 *
 * <p><b>VÌ SAO MONGO CHỨ KHÔNG PHẢI POSTGRES?</b> Đây là điểm "có chủ ý":
 * <ul>
 *   <li><b>Schema đa hình</b>: mỗi {@code type} mang {@code payload} hình
 *       dạng khác nhau — {@code product_viewed} có {@code referrer/device};
 *       {@code order_placed} có {@code totalAmount/itemCount}. Ép vào bảng
 *       quan hệ = cột NULL rải rác hoặc EAV anti-pattern. Document = tự nhiên.</li>
 *   <li><b>Write-heavy append-only</b>: event chỉ ghi-thêm, KHÔNG update,
 *       KHÔNG invariant cross-row → không cần ACID multi-row. Mongo single-doc
 *       write atomic là đủ.</li>
 *   <li><b>TTL tự xoá</b>: event chỉ giữ 90 ngày. Mongo TTL index làm việc
 *       này native; Postgres phải cron {@code DELETE} + VACUUM thủ công.</li>
 * </ul>
 *
 * <p><b>FIELD NÀO TOP-LEVEL, FIELD NÀO CHÌM TRONG payload?</b> Field nào
 * dùng để <i>query/aggregate/index</i> thì kéo lên top-level (typed):
 * {@code type}, {@code occurredAt}, {@code productId}, {@code sessionId},
 * {@code userId}. Field chỉ để <i>đọc lại</i> (referrer, device, ...) nằm
 * trong {@code payload} schemaless. Đây là idiom "hybrid schema": phần biết
 * trước thì strong-type, phần đa dạng thì để map. Giống Postgres JSONB nhưng
 * Mongo cho index cả field trong payload nếu cần (Day 24 so sánh).
 *
 * <p><b>occurredAt = domain time</b>, KHÔNG phải insert time. Event từ Kafka
 * mang {@code occurredAt} gốc (outbox replay vẫn giữ nguyên — xem Day 13).
 * TTL tính theo field này → event cũ 90 ngày tự rụng dù mới index hôm nay.
 */
@Document(collection = "analytics_events")
public class AnalyticsEvent {

    @Id
    private String id;

    /** Discriminator: {@link EventType}. Top-level + indexed (compound với occurredAt). */
    private String type;

    /** Domain time của event. TTL anchor + funnel time-window filter. */
    private Instant occurredAt;

    /**
     * Session correlation key cho funnel (1 user duyệt → add cart → đặt hàng
     * trong cùng session). Frontend beacon gửi; Kafka event không có → null
     * cho {@code order_placed} (funnel join theo userId thay thế).
     */
    private String sessionId;

    /** Null cho event ẩn danh (chưa login). Lưu dạng String để khỏi ép UUID. */
    private String userId;

    /** Denormalize ra top-level cho aggregation top-products. Null nếu event không gắn product. */
    private String productId;

    /**
     * Phần schemaless — payload đặc thù theo {@code type}. KHÔNG ép schema.
     * {@code @Field} để rõ tên Mongo field (mặc định trùng tên Java).
     */
    @Field("payload")
    private Map<String, Object> payload;

    public AnalyticsEvent() {
    }

    public AnalyticsEvent(String type, Instant occurredAt, String sessionId,
                          String userId, String productId, Map<String, Object> payload) {
        this.type = type;
        this.occurredAt = occurredAt;
        this.sessionId = sessionId;
        this.userId = userId;
        this.productId = productId;
        this.payload = payload;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
