package com.ecom.analytics.domain;

import java.util.List;

/**
 * Các {@code type} mà analytics hiểu để build funnel + report. KHÔNG phải
 * enum cứng — event store là schemaless, beacon có thể gửi type lạ và vẫn
 * lưu được (forward-compatible). Đây chỉ là 3 stage funnel mà report quan tâm.
 *
 * <p>Funnel ecommerce kinh điển: <b>xem → thêm giỏ → đặt hàng</b>.
 * Conversion = (đặt hàng) / (xem). Drop-off mỗi stage chỉ ra chỗ UX rò rỉ.
 */
public final class EventType {

    private EventType() {}

    /** Frontend beacon: user mở trang chi tiết 1 product. */
    public static final String PRODUCT_VIEWED = "product_viewed";

    /** Frontend beacon: user add/update item trong giỏ. */
    public static final String CART_UPDATED = "cart_updated";

    /** Kafka order.created → đặt hàng thành công (stage cuối funnel). */
    public static final String ORDER_PLACED = "order_placed";

    /** Thứ tự stage trong funnel — dùng để render report theo đúng phễu. */
    public static final List<String> FUNNEL_STAGES =
            List.of(PRODUCT_VIEWED, CART_UPDATED, ORDER_PLACED);
}
