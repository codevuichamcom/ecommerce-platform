package com.ecom.product.search;

import com.ecom.common.event.ProductDeletedV1;
import com.ecom.common.event.ProductUpsertedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Day 22 — publish event sync ES khi catalog đổi. Gọi từ {@code ProductService}
 * SAU khi commit DB.
 *
 * <p><b>⚠️ Dual-write problem — thẳng thắn thừa nhận</b>: class này publish
 * Kafka SAU khi {@code @Transactional} của {@code create/update} commit. Nếu
 * publish FAIL (Kafka down) sau khi DB đã commit → ES KHÔNG bao giờ nhận event
 * → drift (Postgres có, ES không). Đây CHÍNH là dual-write problem mà Day 13
 * order-service đã giải bằng Outbox pattern.
 *
 * <p><b>Tại sao Day 22 KHÔNG dùng outbox luôn?</b> Trade-off có chủ ý:
 * <ul>
 *   <li>Search index là derived + non-critical → drift được sửa bằng nightly
 *       reconcile job (so sánh count + reindex) — không cần atomic như order.</li>
 *   <li>Outbox cho product = thêm bảng + relay scheduler vào 1 service catalog
 *       đơn giản → over-engineer ở volume hiện tại.</li>
 *   <li>Day 25 (polyglot review) sẽ đánh giá: nếu drift đo được vượt ngưỡng →
 *       nâng lên outbox / Debezium CDC. Xem ADR-010 + issue 22.</li>
 * </ul>
 *
 * <p><b>Key = productId</b>: mọi event của 1 product cùng partition → giữ
 * ordering upserted-trước-deleted (xem {@link ProductIndexer}).
 *
 * <p>{@code send()} async — KHÔNG block thread caller. Callback chỉ log; thất
 * bại được nightly reconcile cover (không retry tay ở đây).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProductEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishUpserted(ProductUpsertedV1 event) {
        String key = event.productId().toString();
        kafkaTemplate.send(TopicNames.PRODUCT_UPSERTED, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Drift sẽ xảy ra — nightly reconcile (Day 25) sửa. Alert
                        // dashboard pick log này để biết sync pipeline có vấn đề.
                        log.error("Failed publish product.upserted productId={} — ES sẽ drift đến lần reconcile",
                                event.productId(), ex);
                    } else {
                        log.debug("Published product.upserted productId={} partition={}",
                                event.productId(), result.getRecordMetadata().partition());
                    }
                });
    }

    public void publishDeleted(ProductDeletedV1 event) {
        String key = event.productId().toString();
        kafkaTemplate.send(TopicNames.PRODUCT_DELETED, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed publish product.deleted productId={} — ES sẽ giữ doc stale đến reconcile",
                                event.productId(), ex);
                    }
                });
    }
}
