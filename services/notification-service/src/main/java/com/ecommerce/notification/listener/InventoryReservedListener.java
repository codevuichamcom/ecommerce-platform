package com.ecommerce.notification.listener;

import com.ecom.common.event.StockReservedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Day 9 — log thông báo "stock đã giữ" cho admin/ops kênh internal. Day 11
 * sẽ wire template + email/SMS adapter thật.
 *
 * <p>Group riêng {@code notification-inv} để fan-out với order-service's
 * {@link InventoryReservedListener cousin}: order-service consume cùng topic
 * group {@code order-service} (cập nhật DB), notification-service consume
 * group {@code notification-inv} (gửi email) — 2 consumer group độc lập,
 * mỗi event được TỪNG group nhận đúng 1 lần.
 */
@Slf4j
@Component
public class InventoryReservedListener {

    @KafkaListener(topics = TopicNames.INVENTORY_RESERVED, groupId = "notification-inv")
    public void onInventoryReserved(StockReservedV1 event) {
        log.info("[notify] Stock reserved orderId={} sku={} qty={} thread={} virtual={}",
                event.orderId(),
                event.sku(),
                event.quantity(),
                Thread.currentThread().getName(),
                Thread.currentThread().isVirtual());
        // TODO Day 11: render template + dispatch email.
    }
}
