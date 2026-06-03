package com.ecom.product.catalog;

import com.ecom.common.event.ProductDeletedV1;
import com.ecom.common.event.ProductUpsertedV1;
import com.ecom.common.messaging.TopicNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Day 23 — sync Postgres → Mongo catalog. "Chân thứ hai" của cùng event đã
 * nuôi ES: {@code product.upserted}/{@code product.deleted} giờ FAN-OUT tới
 * 2 derived store.
 *
 * <p><b>Vì sao 2 consumer riêng (ES indexer + Mongo indexer) cùng 1 topic?</b>
 * Mỗi indexer dùng {@code groupId} RIÊNG → Kafka fan-out: cả hai đều nhận đủ
 * mọi event (consumer-group khác nhau = bản sao độc lập). Tách group để ES
 * down KHÔNG chặn Mongo tiến, và ngược lại — 2 read-model độc lập về độ trễ.
 *
 * <p><b>Idempotent</b>: {@code save()} = upsert by id, {@code deleteById()} =
 * no-op nếu vắng. Kafka at-least-once → replay an toàn. Giống ES indexer
 * (Day 22), KHÔNG cần dedup table.
 *
 * <p><b>Ordering</b>: key = productId → cùng partition → upserted-trước-deleted
 * đúng thứ tự, product archived KHÔNG "sống lại" trong catalog.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class ProductCatalogIndexer {

    private final ProductCatalogRepository catalogRepository;

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERTED, groupId = "${spring.application.name}-catalog")
    public void onUpserted(ProductUpsertedV1 event) {
        log.debug("Catalog upsert product.upserted eventId={} productId={} sku={}",
                event.eventId(), event.productId(), event.sku());
        catalogRepository.save(toDocument(event));
    }

    @KafkaListener(topics = TopicNames.PRODUCT_DELETED, groupId = "${spring.application.name}-catalog")
    public void onDeleted(ProductDeletedV1 event) {
        log.debug("Catalog delete product.deleted eventId={} productId={}",
                event.eventId(), event.productId());
        catalogRepository.deleteById(event.productId().toString());
    }

    private ProductCatalogDocument toDocument(ProductUpsertedV1 e) {
        ProductCatalogDocument doc = new ProductCatalogDocument();
        doc.setId(e.productId().toString());
        doc.setSku(e.sku());
        doc.setName(e.name());
        doc.setSlug(e.slug());
        doc.setDescription(e.description());
        doc.setPrice(e.price());
        doc.setCurrency(e.currency());
        doc.setCategoryId(e.categoryId() == null ? null : e.categoryId().toString());
        doc.setCategorySlug(e.categorySlug());
        doc.setBrand(e.brand());
        doc.setStatus(e.status());
        // Cả bộ attributes động — chỗ Mongo toả sáng (mỗi category 1 shape).
        doc.setAttributes(e.attributes());
        doc.setCreatedAt(e.productCreatedAt());
        return doc;
    }
}
