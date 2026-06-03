package com.ecom.analytics.report;

import java.util.List;

/**
 * Conversion funnel: chuỗi stage theo thứ tự phễu (xem → thêm giỏ → đặt hàng)
 * kèm số event mỗi stage + tỉ lệ rớt.
 *
 * <p><b>Đây là VOLUME funnel</b> (đếm số event), KHÔNG phải distinct-user
 * funnel. Khác biệt quan trọng khi phỏng vấn: distinct-user funnel cần
 * {@code $group} theo (stage, userId) rồi đếm — nặng hơn. Volume funnel đủ
 * để thấy hình dạng phễu + chỗ rớt; chính xác tuyệt đối thì nâng cấp sau.
 */
public record FunnelReport(List<Stage> stages) {

    /**
     * @param stage              tên stage (product_viewed / cart_updated / order_placed)
     * @param count              số event ở stage
     * @param conversionFromTopPct  % so với stage đầu phễu (định nghĩa conversion tổng)
     */
    public record Stage(String stage, long count, double conversionFromTopPct) {
    }
}
