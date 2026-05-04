package com.ecom.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Mixin cho mọi JPA entity của platform.
 *
 * <p>Cung cấp:
 * <ul>
 *   <li>Audit fields tự động: createdAt, updatedAt, createdBy, updatedBy.
 *       Cập nhật qua {@link AuditingEntityListener}.</li>
 *   <li>{@code @Version} cho optimistic locking — chống lost update khi
 *       concurrent write (xem inventory-service ở Day 4).</li>
 * </ul>
 *
 * <p>Tại sao KHÔNG để @Id ở đây?
 *  — Mỗi domain có policy ID khác nhau (UUID, Long sequence, snowflake...).
 *    Ép cha định nghĩa ID = leaky abstraction. Để con tự khai báo.
 *
 * <p>Tại sao dùng {@link Instant} thay vì {@code LocalDateTime}?
 *  — Instant = UTC mốc, không có timezone ambiguity. LocalDateTime sẽ ăn
 *    timezone của JVM → bug âm thầm khi multi-region.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
