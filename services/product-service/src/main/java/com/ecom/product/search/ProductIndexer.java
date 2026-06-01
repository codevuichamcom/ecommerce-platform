package com.ecom.product.search;

import com.ecom.common.event.ProductDeletedV1;
import com.ecom.common.event.ProductUpsertedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Day 22 — consumer sync Postgres → Elasticsearch. Đây là "chân kia" của
 * CDC-lite: product-service publish event lúc ghi DB ({@code ProductEventPublisher}),
 * indexer này consume → index/delete document trong ES.
 *
 * <p><b>Tại sao tách qua Kafka thay vì index thẳng trong {@code create()}?</b>
 * <ul>
 *   <li>Decouple ghi DB khỏi ghi ES — ES chậm/down KHÔNG làm fail request
 *       create product (DB vẫn là source of truth, search là derived).</li>
 *   <li>Buffer + retry — Kafka giữ event khi ES down, replay khi ES sống lại.</li>
 *   <li>Day 25 tách indexer ra service riêng được mà không sửa product write path.</li>
 * </ul>
 *
 * <p><b>Idempotent</b>: {@code save()} ES là upsert by id → replay cùng event
 * = ghi đè cùng nội dung (no harm). {@code deleteById()} doc không tồn tại =
 * no-op. Kafka at-least-once → indexer phải idempotent, và nó idempotent
 * tự nhiên nhờ id-based write. KHÔNG cần dedup table như payment (Day 10).
 *
 * <p><b>Ordering</b>: cả 2 topic key = {@code productId} → CÙNG partition →
 * upserted rồi deleted xử đúng thứ tự. Nếu key khác nhau, deleted có thể tới
 * trước upserted (2 partition) → product "sống lại" trong index. Đây là cạm
 * bẫy sync drift — xem {@code issues/22-es-postgres-sync-drift.md}.
 *
 * <p><b>Failure</b>: throw ra container → Spring Kafka default error handler
 * retry; Day 12 pipeline (DLT) áp dụng nếu poison. ES connection blip = retry
 * giải quyết; mapping error (poison) = DLT.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProductIndexer {

    private final ProductSearchRepository searchRepository;

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "${spring.application.name}-indexer")
    public void onUpserted(ProductUpsertedV1 event) {
        log.debug("Index product.upserted eventId={} productId={} sku={}",
                event.eventId(), event.productId(), event.sku());
        searchRepository.save(toDocument(event));
    }

    @KafkaListener(topics = TopicNames.PRODUCT_DELETED, groupId = "${spring.application.name}-indexer")
    public void onDeleted(ProductDeletedV1 event) {
        log.debug("Delete from index product.deleted eventId={} productId={}",
                event.eventId(), event.productId());
        // deleteById doc không tồn tại = no-op (idempotent). KHÔNG existsById
        // trước rồi delete — race + thừa 1 round-trip; ES delete tự no-op 404.
        searchRepository.deleteById(event.productId().toString());
    }

    private ProductDocument toDocument(ProductUpsertedV1 e) {
        ProductDocument doc = new ProductDocument();
        doc.setId(e.productId().toString());
        doc.setSku(e.sku());
        doc.setName(e.name());
        doc.setDescription(e.description());
        doc.setPrice(e.price());
        doc.setCurrency(e.currency());
        doc.setCategoryId(e.categoryId() == null ? null : e.categoryId().toString());
        doc.setCategorySlug(e.categorySlug());
        doc.setBrand(e.brand());
        doc.setStatus(e.status());
        doc.setAttributes(e.attributes());
        doc.setCreatedAt(e.productCreatedAt());
        return doc;
    }
}
