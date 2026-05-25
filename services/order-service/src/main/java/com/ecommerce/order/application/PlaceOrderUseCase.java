package com.ecommerce.order.application;

import com.ecom.common.event.OrderCreatedV1;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.messaging.TopicNames;
import com.ecommerce.order.domain.Money;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderItem;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.domain.exception.EmptyCartException;
import com.ecommerce.order.infrastructure.client.CartClient;
import com.ecommerce.order.infrastructure.client.dto.CartView;
import com.ecommerce.order.infrastructure.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrator cho POST /orders — Day 9 refactor sang event-driven.
 *
 * <pre>
 * Day 6 (sync):     order → inventory.reserve (Feign) loop → save → publish event
 * Day 9 (async):    order → save (reservation_status=PENDING) → publish order.created
 *                   inventory-service consume → reserve → publish inventory.reserved
 *                   order-service consume inventory.reserved → markReserved
 * </pre>
 *
 * <p><b>Trade-off chính</b> (xem [`issue 09`](docs/issues/09-eventual-consistency-order.md)):
 * decouple service + scale independently NHƯNG window eventual consistency
 * (~50-500ms) mà order hiển thị `PENDING` cho user. Frontend Day 27 sẽ
 * show banner "Đang giữ hàng..." cho UX rõ ràng.
 *
 * <p><b>Dual-write debt — TRẢ Ở DAY 13</b>: trước đây DB commit + Kafka publish
 * không atomic. Bây giờ {@link OutboxRecorder#record} ghi event vào bảng
 * {@code outbox_event} cùng tx → atomic. Background {@code OutboxRelay} poll
 * và publish Kafka sau, consumer side đã idempotent (Day 12). Trade-off:
 * thêm 1-2s lag publish; SLI {@code outbox.pending.age > 30s} = alert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderUseCase {

    private final OrderRepository orderRepository;
    private final CartClient cartClient;
    private final OutboxRecorder outboxRecorder;

    @Transactional
    public Order place(PlaceOrderCommand cmd) {
        // 1. Idempotency check — POST retry với cùng key không tạo trùng.
        if (cmd.idempotencyKey() != null) {
            var existing = orderRepository.findByUserIdAndIdempotencyKey(
                    cmd.userId(), cmd.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Idempotent return: order={} for user={} key={}",
                        existing.get().getId(), cmd.userId(), cmd.idempotencyKey());
                return existing.get();
            }
        }

        // 2. Fetch cart — sync OK vì user cần biết items immediate.
        CartView cart = cartClient.fetchUserCart(cmd.bearerToken());
        if (cart.items() == null || cart.items().isEmpty()) {
            throw new EmptyCartException();
        }

        // 3. Build + persist Order với reservation_status=PENDING.
        Order order = Order.create(cmd.userId(), cmd.shippingAddress(),
                cmd.currency(), cmd.idempotencyKey());
        for (CartView.CartItem item : cart.items()) {
            String ccy = item.unitPriceCurrency() != null ? item.unitPriceCurrency() : cmd.currency();
            order.addItem(item.sku(), item.productName(), item.quantity(),
                    new Money(item.unitPriceAmount(), ccy));
        }
        order.place();
        Order saved;
        try {
            saved = orderRepository.save(order);
        } catch (RuntimeException ex) {
            log.error("Order save failed for user={} key={}", cmd.userId(), cmd.idempotencyKey(), ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to persist order");
        }

        // 4. Ghi event vào outbox CÙNG tx. Relay scheduled publish sau (Day 13).
        // Atomic guarantee: nếu save Order succeed thì insert outbox cũng succeed
        // (cùng tx Postgres). Nếu DB commit fail → cả 2 rollback. Loại bỏ
        // dual-write inconsistency của Day 9.
        OrderCreatedV1 event = toEvent(saved);
        outboxRecorder.record(
                "Order",
                saved.getId().toString(),
                "OrderCreatedV1",
                TopicNames.ORDER_CREATED,
                saved.getId().toString(),
                event);

        log.info("Order placed (PENDING reservation) id={} user={} total={} {}",
                saved.getId(), saved.getUserId(),
                saved.getTotal().amount(), saved.getTotal().currency());
        return saved;
    }

    private static OrderCreatedV1 toEvent(Order saved) {
        List<OrderCreatedV1.Item> items = saved.getItems().stream()
                .map(PlaceOrderUseCase::toEventItem)
                .toList();
        return new OrderCreatedV1(
                UUID.randomUUID(),
                Instant.now(),
                saved.getId(),
                saved.getUserId(),
                saved.getTotal().currency(),
                java.math.BigDecimal.valueOf(saved.getTotal().amount()),
                items);
    }

    private static OrderCreatedV1.Item toEventItem(OrderItem item) {
        return new OrderCreatedV1.Item(
                item.getSku(),
                item.getQuantity(),
                java.math.BigDecimal.valueOf(item.getUnitPrice().amount()));
    }
}
