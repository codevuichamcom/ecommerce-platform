package com.ecom.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Day 22 — published bởi product-service khi 1 product được create/update
 * (cùng nhịp ghi Postgres). Consumer {@code ProductIndexer} index document
 * này vào Elasticsearch index {@code products}.
 *
 * <p><b>Tại sao event mang FULL snapshot product (không chỉ productId)?</b>
 * Consumer KHÔNG được query DB của product-service (nó CÙNG service ở Day 22,
 * nhưng giữ contract "consumer tự đủ data" để Day 25 tách indexer ra service
 * riêng được). Event-carried state transfer: payload đủ build ES document
 * mà không cần round-trip về source. Trade-off: payload to hơn, nhưng search
 * index là derived data → chấp nhận.
 *
 * <p><b>Tại sao tách {@code brand} khỏi {@code attributes}?</b> Faceted search
 * cần brand là field riêng (ES {@code keyword}) để aggregate count. Để brand
 * chìm trong attributes (object) thì aggregation phức tạp + dễ sai bucket.
 * product-service extract {@code attributes.get("brand")} lúc publish.
 *
 * <p>Schema rule v1: KHÔNG bỏ field, KHÔNG đổi nghĩa. Thêm field mới ở cuối =
 * OK (Jackson backward-compatible). Breaking → {@code ProductUpsertedV2} +
 * topic {@code product.upserted.v2}.
 */
public record ProductUpsertedV1(
        UUID eventId,
        Instant occurredAt,
        UUID productId,
        String sku,
        String name,
        String slug,
        String description,
        BigDecimal price,
        String currency,
        UUID categoryId,
        String categorySlug,
        String brand,
        String status,
        Map<String, Object> attributes,
        Instant productCreatedAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "product.upserted";
    }
}
