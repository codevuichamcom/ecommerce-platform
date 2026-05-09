package com.ecom.inventory.domain;

import com.ecom.common.audit.BaseEntity;
import com.ecom.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <h2>Aggregate root — Stock</h2>
 *
 * <p>Invariant (enforce trong aggregate, KHÔNG ở service):
 * <ol>
 *   <li>{@code quantity ≥ 0}</li>
 *   <li>{@code reserved ≥ 0}</li>
 *   <li>{@code reserved ≤ quantity}</li>
 *   <li>{@code reserve(qty)} với {@code qty > 0} và {@code qty ≤ available}</li>
 *   <li>{@code release(qty)} với {@code qty > 0} và {@code qty ≤ reserved}</li>
 * </ol>
 *
 * <p>Tại sao KHÔNG extends {@code AbstractAggregateRoot}? Vì Java single
 * inheritance — đã extends {@link BaseEntity} (kế thừa @Version + audit).
 * Thay vào đó tự quản event qua {@code @DomainEvents} +
 * {@code @AfterDomainEventPublication} — Spring Data hook nguyên thủy
 * (AbstractAggregateRoot chỉ là syntactic sugar trên hai annotation này).
 *
 * <p>Optimistic lock: {@code @Version} kế thừa từ {@link BaseEntity}.
 * Hibernate generate {@code UPDATE stock SET ... WHERE sku=? AND version=?}.
 * Nếu 2 tx cùng load version=5 → cùng UPDATE: tx-A thành công (version=6),
 * tx-B affected_rows=0 → throw {@code ObjectOptimisticLockingFailureException}.
 * Application service {@code @Retryable} reload và thử lại.
 *
 * <p>KHÔNG dùng {@code @Setter} — setter public phá nguyên tắc aggregate
 * (caller bypass invariant). Chỉ getter + factory + method domain.
 */
@Entity
@Table(name = "stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class Stock extends BaseEntity {

    @Id
    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Transient
    @Getter(AccessLevel.NONE)
    private final List<Object> domainEvents = new ArrayList<>();

    /**
     * Factory cho Stock mới — khởi tạo có invariant check, KHÔNG cho
     * caller new Stock() rồi setSku/setQuantity tự do.
     */
    public static Stock create(String sku, int initialQuantity) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (initialQuantity < 0) {
            throw new IllegalArgumentException("initialQuantity must be ≥ 0");
        }
        Stock stock = new Stock();
        stock.sku = sku;
        stock.quantity = initialQuantity;
        stock.reserved = 0;
        return stock;
    }

    public int available() {
        return quantity - reserved;
    }

    /**
     * Reserve {@code qty} đơn vị. Throw {@link InsufficientStockException}
     * nếu vi phạm invariant (caller phải xử lý — đây là 4xx, không 5xx).
     *
     * <p>Domain event {@link StockReserved} register để publish khi Spring
     * Data save. Day 9 wire {@code @TransactionalEventListener(AFTER_COMMIT)}
     * → Kafka outbox.
     */
    public void reserve(int qty) {
        requirePositive(qty, "reserve qty");
        if (qty > available()) {
            throw new InsufficientStockException(
                    ErrorCode.STOCK_INSUFFICIENT,
                    "SKU %s: requested=%d, available=%d".formatted(sku, qty, available()));
        }
        this.reserved += qty;
        domainEvents.add(new StockReserved(sku, qty, Instant.now()));
    }

    /**
     * Release {@code qty} đơn vị đã reserved (vd: order cancel, payment fail).
     * Vi phạm invariant → IllegalStateException (programming error, không
     * phải user input).
     */
    public void release(int qty) {
        requirePositive(qty, "release qty");
        if (qty > reserved) {
            throw new IllegalStateException(
                    "SKU %s: cannot release %d, only %d reserved".formatted(sku, qty, reserved));
        }
        this.reserved -= qty;
        domainEvents.add(new StockReleased(sku, qty, Instant.now()));
    }

    /**
     * Confirm reservation (sau khi payment OK) — giảm cả quantity lẫn
     * reserved. Day 6 order-service gọi method này.
     */
    public void confirm(int qty) {
        requirePositive(qty, "confirm qty");
        if (qty > reserved) {
            throw new IllegalStateException(
                    "SKU %s: cannot confirm %d, only %d reserved".formatted(sku, qty, reserved));
        }
        this.reserved -= qty;
        this.quantity -= qty;
    }

    @DomainEvents
    public List<Object> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    @AfterDomainEventPublication
    public void clearDomainEvents() {
        domainEvents.clear();
    }

    private static void requirePositive(int qty, String label) {
        if (qty <= 0) {
            throw new IllegalArgumentException(label + " must be > 0");
        }
    }
}
