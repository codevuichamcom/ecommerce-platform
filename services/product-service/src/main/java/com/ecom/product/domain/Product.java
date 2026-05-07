package com.ecom.product.domain;

import com.ecom.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Product entity (Layered service — không phải DDD).
 *
 * <p>{@code attributes}: JSONB — flexible attribute đặc thù theo category
 * (TV: screen_size/resolution; áo: size/color/material). Trade-off vs
 * Mongo + vs EAV-table — xem {@code interview/day-03-product.md}.
 *
 * <p>Hibernate 6.6 native JSON mapping qua {@code @JdbcTypeCode(SqlTypes.JSON)}
 * — không cần hibernate-types lib nữa. Map giữ nguyên Java, Postgres lưu JSONB.
 *
 * <p>Lý do {@code @ManyToOne(LAZY)} cho category: list 100 product không
 * cần kéo 100 category (đa số là duplicate). Nhưng LAZY = phải map sang
 * DTO TRONG transaction để tránh {@code LazyInitializationException} —
 * xem issue 03-entity-leak-in-response.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Product extends BaseEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProductStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
}
