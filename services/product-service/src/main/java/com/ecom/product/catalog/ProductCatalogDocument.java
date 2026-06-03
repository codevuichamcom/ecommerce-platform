package com.ecom.product.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Day 23 — catalog read-model trong Mongo collection {@code product_catalog}.
 * Phục vụ trang chi tiết sản phẩm + filter theo thuộc tính động.
 *
 * <p><b>VÌ SAO MONGO CHO CÁI NÀY MÀ KHÔNG PHẢI POSTGRES?</b> Trọng tâm là
 * {@code attributes} — bộ thuộc tính KHÁC NHAU theo category:
 * <pre>
 *   TV:  { "screen_size": 55, "resolution": "4K", "panel": "OLED" }
 *   Áo:  { "size": "L", "color": "navy", "material": "cotton" }
 * </pre>
 * Ép vào bảng quan hệ → hoặc cột NULL la liệt (mỗi category vài chục cột,
 * đa số NULL), hoặc EAV table {@code (product_id, attr_key, attr_value)} —
 * query "TV 4K" thành self-join nhiều lần, mất type, khó index. Document model
 * cho phép {@code attributes} là sub-document tự do + <b>index thẳng vào field
 * lồng nhau</b> ({@code attributes.resolution}) — thứ JSONB Postgres làm được
 * nhưng Mongo làm tự nhiên + scale ngang dễ hơn (xem lesson 23b + ADR-011).
 *
 * <p><b>VÌ SAO KHÔNG bỏ Postgres, để Mongo làm source of truth?</b> Vì product
 * có invariant cần ACID: {@code price ≥ 0}, {@code sku} unique, status
 * transition. Đó là việc của Postgres. Mongo ở đây là <b>derived read-model</b>
 * — sync 1 chiều từ Postgres qua event {@code product.upserted} (CÙNG event
 * nuôi ES Day 22). Drift xử như ES: reconcile. KHÔNG dual-source-of-truth.
 *
 * <p>{@code categorySlug} {@code @Indexed}: filter "tất cả TV" hay dùng →
 * single-field index. Index cho {@code attributes.*} tạo có chủ đích trong
 * {@link ProductCatalogService} comment (không index mù mọi key — wildcard
 * index tốn ghi).
 */
@Document(collection = "product_catalog")
public class ProductCatalogDocument {

    @Id
    private String id;

    @Indexed
    private String sku;

    private String name;
    private String slug;
    private String description;
    private BigDecimal price;
    private String currency;

    private String categoryId;

    @Indexed
    private String categorySlug;

    private String brand;
    private String status;

    /** Phần "không dòng kẻ" — thuộc tính động theo category. */
    private Map<String, Object> attributes;

    private Instant createdAt;

    public ProductCatalogDocument() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

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
