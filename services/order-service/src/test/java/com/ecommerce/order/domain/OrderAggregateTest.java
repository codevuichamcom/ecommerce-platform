package com.ecommerce.order.domain;

import com.ecommerce.order.domain.exception.EmptyCartException;
import com.ecommerce.order.domain.exception.InvalidOrderTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test invariant aggregate Order — KHÔNG cần Spring, KHÔNG cần DB.
 * Pure domain, chạy nhanh, không flaky.
 */
class OrderAggregateTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Address ADDR = new Address("Tonny", "0900000001",
            "1 Nguyen Hue", "HCMC", "VN");

    @Test
    @DisplayName("create() khởi tạo PendingPayment với total=0")
    void create_initialState() {
        Order o = Order.create(USER, ADDR, "VND", null);
        assertThat(o.getStatus()).isInstanceOf(OrderStatus.PendingPayment.class);
        assertThat(o.getTotal().amount()).isZero();
        assertThat(o.getTotal().currency()).isEqualTo("VND");
        assertThat(o.getItems()).isEmpty();
    }

    @Test
    @DisplayName("addItem() recompute total = Σ subtotal")
    void addItem_recomputesTotal() {
        Order o = Order.create(USER, ADDR, "VND", null);
        o.addItem("SKU-A", "Item A", 2, new Money(10_000, "VND"));
        o.addItem("SKU-B", "Item B", 3, new Money(5_000, "VND"));
        assertThat(o.getTotal().amount()).isEqualTo(2 * 10_000 + 3 * 5_000);
        assertThat(o.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("place() với 0 item → EmptyCartException")
    void place_emptyCart_throws() {
        Order o = Order.create(USER, ADDR, "VND", null);
        assertThatThrownBy(o::place).isInstanceOf(EmptyCartException.class);
    }

    @Test
    @DisplayName("place() emit OrderPlaced event")
    void place_emitsEvent() {
        Order o = Order.create(USER, ADDR, "VND", null);
        o.addItem("SKU-A", "Item A", 1, new Money(100, "VND"));
        o.place();
        assertThat(o.domainEvents()).hasSize(1)
                .first().isInstanceOf(OrderPlaced.class);
    }

    @Test
    @DisplayName("PendingPayment → Paid: OK")
    void transition_pendingToPaid_ok() {
        Order o = placed();
        o.transitionTo(new OrderStatus.Paid(Instant.now()));
        assertThat(o.getStatus()).isInstanceOf(OrderStatus.Paid.class);
    }

    @Test
    @DisplayName("PendingPayment → Shipped: invalid")
    void transition_pendingToShipped_invalid() {
        Order o = placed();
        assertThatThrownBy(() ->
                o.transitionTo(new OrderStatus.Shipped("TRACK-1", Instant.now())))
                .isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    @DisplayName("Cancelled là terminal — không transition thêm")
    void cancelled_terminal() {
        Order o = placed();
        o.transitionTo(new OrderStatus.Cancelled("user request", Instant.now()));
        assertThat(o.getStatus().isTerminal()).isTrue();
        assertThatThrownBy(() ->
                o.transitionTo(new OrderStatus.Paid(Instant.now())))
                .isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    @DisplayName("Cancel emit OrderCancelled event")
    void cancel_emitsEvent() {
        Order o = placed();
        o.transitionTo(new OrderStatus.Cancelled("changed mind", Instant.now()));
        assertThat(o.domainEvents())
                .filteredOn(e -> e instanceof OrderCancelled)
                .hasSize(1);
    }

    @Test
    @DisplayName("Full happy path PendingPayment → Paid → Shipped → Delivered")
    void transition_fullHappyPath() {
        Order o = placed();
        o.transitionTo(new OrderStatus.Paid(Instant.now()));
        o.transitionTo(new OrderStatus.Shipped("TRACK-1", Instant.now()));
        o.transitionTo(new OrderStatus.Delivered(Instant.now()));
        assertThat(o.getStatus().isTerminal()).isTrue();
    }

    private static Order placed() {
        Order o = Order.create(USER, ADDR, "VND", null);
        o.addItem("SKU-A", "Item A", 1, new Money(100, "VND"));
        o.place();
        return o;
    }
}
