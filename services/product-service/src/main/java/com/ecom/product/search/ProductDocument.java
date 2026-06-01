package com.ecom.product.search;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Day 22 — Elasticsearch document cho product search index {@code products}.
 *
 * <p><b>text vs keyword — quyết định mapping QUAN TRỌNG nhất ở đây</b>:
 * <ul>
 *   <li>{@code text} = analyzed: tokenize + lowercase + (tùy analyzer) stemming.
 *       Dùng cho full-text match ({@code name}, {@code description}). "iPhone 15
 *       Pro Max" → tokens [iphone, 15, pro, max] → gõ "pro max" match được.</li>
 *   <li>{@code keyword} = NOT analyzed, lưu nguyên chuỗi. Dùng cho
 *       filter / aggregation / sort ({@code brand}, {@code categoryId},
 *       {@code status}). Aggregate brand "Apple" ra ĐÚNG 1 bucket — nếu để
 *       text thì "Apple" tách thành token "apple" + lowercase → sai facet.</li>
 * </ul>
 *
 * <p>{@code name} dùng {@link MultiField}: field chính {@code name} (text,
 * cho relevance match) + sub-field {@code name.keyword} (keyword, cho sort
 * alphabet / exact match). Đây là idiom chuẩn ES "1 field 2 cách dùng".
 *
 * <p>{@code price} là {@code Double} (ES không có BigDecimal). Precision loss
 * chấp nhận được vì ES CHỈ dùng để filter range + sort, KHÔNG phải nơi tính
 * tiền (Postgres giữ {@code NUMERIC(12,2)} là source of truth — xem
 * {@code review/performance-week3-findings.md} [YELLOW] precision loss).
 *
 * <p>{@code attributes} map {@code Object} (không index sâu) — chỉ store để
 * trả về client. Field cần facet/filter (brand) đã được product-service
 * extract ra top-level lúc publish event.
 *
 * <p>{@code @Setting}: 1 shard + 0 replica cho dev single-node (replica trên
 * 1 node = mãi mãi UNASSIGNED → cluster yellow). Prod multi-node tăng replica≥1.
 */
@Document(indexName = "products")
@Setting(shards = 1, replicas = 0)
public class ProductDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String sku;

    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "standard"),
            otherFields = { @InnerField(suffix = "keyword", type = FieldType.Keyword) })
    private String name;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String description;

    @Field(type = FieldType.Double)
    private BigDecimal price;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Keyword)
    private String categoryId;

    @Field(type = FieldType.Keyword)
    private String categorySlug;

    @Field(type = FieldType.Keyword)
    private String brand;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Object, enabled = false)
    private Map<String, Object> attributes;

    @Field(type = FieldType.Date)
    private Instant createdAt;

    public ProductDocument() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public String getCategorySlug() { return categorySlug; }
    public void setCategorySlug(String categorySlug) { this.categorySlug = categorySlug; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
