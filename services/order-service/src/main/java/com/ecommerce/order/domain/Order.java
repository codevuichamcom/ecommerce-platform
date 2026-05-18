package com.ecommerce.order.domain;

import com.ecom.common.audit.BaseEntity;
import com.ecommerce.order.domain.exception.EmptyCartException;
import com.ecommerce.order.domain.exception.InvalidOrderTransitionException;
import com.ecommerce.order.infrastructure.persistence.OrderStatusSerializer;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * <h2>Aggregate root — Order</h2>
 *
 * <p>Boundary: Order + OrderItem(s). OrderItem KHÔNG access trực tiếp từ
 * ngoài — phải qua Order method. Lý do: invariant {@code total =
 * Σ(item.subtotal)} cần atomic update; nếu cho phép thêm/xóa item ngoài
 * Order → drift total.
 *
 * <p>Invariant enforce:
 * <ol>
 *   <li>{@code items.size() ≥ 1} tại lúc {@link #place} — không cho phép
 *       empty order.</li>
 *   <li>{@code total = Σ(item.subtotal)} — recompute mỗi khi mutate items.</li>
 *   <li>Lifecycle transition phải hợp lệ (xem {@link OrderStatus} doc).</li>
 *   <li>Terminal state (Delivered / Cancelled) KHÔNG cho phép transition
 *       thêm — {@link InvalidOrderTransitionException}.</li>
 * </ol>
 *
 * <p>Tại sao status persist 2 column (status_type + status_data JSONB)
 * thay vì 1 enum column? Vì mỗi permit của sealed interface mang data
 * khác nhau (Cancelled.reason, Shipped.trackingNumber). 1 column enum
 * → nullable hell. JSONB linh hoạt + queryable. Hibernate {@code @Convert}
 * map sealed ↔ DB ở {@code infrastructure/OrderStatusConverter}.
 *
 * <p>Optimistic lock: {@code @Version} kế thừa từ {@link BaseEntity}.
 * Concurrent cancel + ship cùng 1 order → 1 tx thắng, 1 fail
 * {@code ObjectOptimisticLockingFailureException}. Day 6 KHÔNG retry —
 * vì state machine không idempotent (retry cancel sau khi ship đã thành
 * công sẽ gây nhầm lẫn). Caller xử lý 409 → reload + quyết định.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class Order extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Sealed status — map qua AttributeConverter ở persistence layer. */
    @Transient
    private OrderStatus status;

    @Column(name = "status_type", nullable = false, length = 32)
    private String statusType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "status_data", nullable = false, columnDefinition = "jsonb")
    private String statusDataJson;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "amount",   column = @Column(name = "total_amount",   nullable = false)),
        @AttributeOverride(name = "currency", column = @Column(name = "total_currency", nullable = false, length = 3))
    })
    private Money total;

    @Embedded
    private Address shippingAddress;

    @Column(name = "idempotency_key", length = 80, updatable = false)
    private String idempotencyKey;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    /**
     * Day 9 eventual-consistency tracker. KHÔNG trộn vào sealed
     * {@link OrderStatus} vì đây là cross-cutting concern (tracking async
     * reservation), không phải business lifecycle state.
     *
     * <p>Transition: {@code PENDING} (initial khi place) → {@code RESERVED}
     * (sau khi nhận {@code inventory.reserved} qua Kafka). Day 12 sẽ thêm
     * {@code FAILED} khi retry hết.
     */
    @Column(name = "reservation_status", nullable = false, length = 16)
    private String reservationStatus;

    // Unidirectional one-to-many. OrderItem.orderId column được FK link qua
    // @JoinColumn — Hibernate ép update bằng UPDATE phụ nếu cần. Day 6 chấp
    // nhận vì OrderItem write-once (snapshot), không có overhead update.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false, updatable = false, insertable = false)
    private List<OrderItem> items = new ArrayList<>();

    @Transient
    @Getter(AccessLevel.NONE)
    private final List<Object> domainEvents = new ArrayList<>();

    /**
     * Factory tạo Order ở trạng thái PendingPayment. Caller (use case)
     * phải gọi {@link #addItem} ≥ 1 lần rồi {@link #place} để finalize.
     *
     * <p>Tách 2 bước (create + place) giúp test invariant rõ ràng và cho
     * phép builder pattern cho client mà không cho rò rỉ aggregate ra
     * ngoài. Day 6 PlaceOrderUseCase gọi cả 3 trong cùng method.
     */
    public static Order create(UUID userId, Address shippingAddress, String currency, String idempotencyKey) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (shippingAddress == null) {
            throw new IllegalArgumentException("shippingAddress must not be null");
        }
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.userId = userId;
        order.shippingAddress = shippingAddress;
        order.total = Money.zero(currency);
        order.idempotencyKey = idempotencyKey;
        order.placedAt = Instant.now();
        order.reservationStatus = "PENDING";
        order.setStatus(new OrderStatus.PendingPayment());
        return order;
    }

    /**
     * Day 10: gọi khi nhận {@code payment.completed} từ payment-service. Idempotent
     * — gọi lại trên Order đã Paid không có side effect. Race với cancel: nếu
     * Order đã Cancelled (terminal), markPaid no-op + log warn ở caller.
     *
     * <p>Transition rule (CLAUDE.md §5 + sealed OrderStatus): chỉ PendingPayment
     * mới được chuyển sang Paid. Mọi state khác (Paid lần 2, Cancelled, Shipped,
     * Delivered) → no-op. KHÔNG throw vì consumer Kafka retry sẽ vô hạn.
     *
     * @return true nếu transition diễn ra (lần đầu), false nếu no-op (duplicate/terminal).
     */
    public boolean markPaid(Instant paidAt) {
        if (status instanceof OrderStatus.PendingPayment) {
            transitionTo(new OrderStatus.Paid(paidAt));
            return true;
        }
        return false;
    }

    /**
     * Day 9: gọi khi nhận {@code inventory.reserved} cho TẤT CẢ items. Idempotent
     * — gọi lại không có side effect (đã RESERVED rồi thì bỏ qua).
     */
    public void markReserved() {
        if ("PENDING".equals(this.reservationStatus)) {
            this.reservationStatus = "RESERVED";
        }
    }

    public void addItem(String sku, String productName, int quantity, Money unitPrice) {
        requireMutable();
        OrderItem item = new OrderItem(this.id, sku, productName, quantity, unitPrice);
        items.add(item);
        recomputeTotal();
    }

    /**
     * Finalize Order — emit {@link OrderPlaced} event. Caller chịu trách
     * nhiệm reserve inventory TRƯỚC khi gọi place (xem PlaceOrderUseCase).
     */
    public void place() {
        if (items.isEmpty()) {
            throw new EmptyCartException();
        }
        domainEvents.add(new OrderPlaced(id, userId, total.amount(), total.currency(), Instant.now()));
    }

    /**
     * Transition state machine — exhaustive switch enforce hợp lệ.
     *
     * <p>Mỗi case là 1 (from, to) tuple cho phép; default ELSE đi vào
     * {@link InvalidOrderTransitionException}. KHÔNG dùng map (from,to)
     * → set vì compiler không kiểm exhaustive. Switch sealed thì có.
     */
    public void transitionTo(OrderStatus next) {
        if (status.isTerminal()) {
            throw new InvalidOrderTransitionException(status, next);
        }
        boolean allowed = switch (status) {
            case OrderStatus.PendingPayment p -> next instanceof OrderStatus.Paid
                                              || next instanceof OrderStatus.Cancelled;
            case OrderStatus.Paid p           -> next instanceof OrderStatus.Shipped
                                              || next instanceof OrderStatus.Cancelled;
            case OrderStatus.Shipped s        -> next instanceof OrderStatus.Delivered;
            case OrderStatus.Delivered d      -> false; // unreachable do isTerminal guard
            case OrderStatus.Cancelled c      -> false; // unreachable do isTerminal guard
        };
        if (!allowed) {
            throw new InvalidOrderTransitionException(status, next);
        }
        OrderStatus prev = this.status;
        setStatus(next);

        // Emit cancel event để Day 9 inventory-service release reservation.
        if (next instanceof OrderStatus.Cancelled c) {
            domainEvents.add(new OrderCancelled(id, userId, c.reason(), c.cancelledAt()));
        }
    }

    /**
     * Setter package-private cho persistence layer (OrderStatusConverter
     * đọc từ DB rồi rebuild aggregate). KHÔNG public — caller ngoài phải
     * đi qua {@link #transitionTo}.
     */
    public void setStatus(OrderStatus status) {
        this.status = status;
        this.statusType = status.statusName();
        // status_data JSONB set bởi persistence layer (OrderStatusConverter).
    }

    /** Setter cho persistence reconstitute — KHÔNG public. */
    public void setStatusDataJson(String json) {
        this.statusDataJson = json;
    }

    /**
     * JPA lifecycle: trước khi save → serialize sealed status sang JSONB.
     * Sealed status không tự map được qua AttributeConverter vì cần 2
     * column (type + data) — callback là cách clean nhất giữ aggregate
     * không leak persistence concern.
     */
    @PrePersist
    @PreUpdate
    void syncStatusToColumns() {
        if (status == null) {
            throw new IllegalStateException("Order.status must not be null at persist time");
        }
        this.statusType = status.statusName();
        this.statusDataJson = OrderStatusSerializer.toJson(status);
    }

    /**
     * JPA lifecycle: sau khi load → reconstitute sealed status từ 2 column.
     */
    @PostLoad
    void reconstituteStatus() {
        this.status = OrderStatusSerializer.fromDb(statusType, statusDataJson);
    }

    private void recomputeTotal() {
        Money sum = Money.zero(total.currency());
        for (OrderItem item : items) {
            sum = sum.add(item.subtotal());
        }
        this.total = sum;
    }

    private void requireMutable() {
        if (status != null && status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot mutate items on terminal order: " + status.statusName());
        }
    }

    @DomainEvents
    public List<Object> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    @AfterDomainEventPublication
    public void clearDomainEvents() {
        domainEvents.clear();
    }
}
