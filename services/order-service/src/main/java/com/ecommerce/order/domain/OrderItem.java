package com.ecommerce.order.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entity bên trong Order aggregate. Lifecycle gắn chặt Order (CASCADE
 * DELETE FK). KHÔNG có repository riêng — caller phải đi qua
 * {@link Order#addItem} hoặc constructor.
 *
 * <p>Constructor visibility: package-private. Caller ngoài domain package
 * KHÔNG tự new OrderItem được — phải qua Order method. Đây là cách enforce
 * aggregate boundary mà không cần annotation magic.
 *
 * <p>Quantity + unit price là <b>snapshot tại thời điểm placeOrder</b>.
 * Product giá đổi sau đó KHÔNG ảnh hưởng order đã đặt (audit + invoice
 * correctness). Đây là rule kinh điển: order item là immutable snapshot.
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class OrderItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount",   column = @Column(name = "unit_price_amount", nullable = false, updatable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "unit_price_ccy",   nullable = false, updatable = false, length = 3))
    })
    private Money unitPrice;

    OrderItem(UUID orderId, String sku, String productName, int quantity, Money unitPrice) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("unitPrice must not be null");
        }
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.sku = sku;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public Money subtotal() {
        return unitPrice.multiply(quantity);
    }
}
