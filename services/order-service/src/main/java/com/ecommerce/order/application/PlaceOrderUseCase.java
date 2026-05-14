package com.ecommerce.order.application;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.order.domain.Money;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.domain.OrderRepository;
import com.ecommerce.order.domain.exception.EmptyCartException;
import com.ecommerce.order.infrastructure.client.CartClient;
import com.ecommerce.order.infrastructure.client.InventoryClient;
import com.ecommerce.order.infrastructure.client.dto.CartView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrator cho POST /orders. Flow Day 6 (sync):
 *
 * <pre>
 *   1. Idempotency check: tìm order theo (userId, idempotencyKey) — nếu có
 *      → return luôn (đảm bảo POST retry không tạo trùng).
 *   2. Fetch cart từ cart-service.
 *   3. Build Order aggregate (PendingPayment) + addItem từng SKU.
 *   4. Reserve inventory: lần lượt từng item. Nếu fail → compensate
 *      release những item đã reserve trước đó.
 *   5. Save Order — chỉ persist NẾU reserve all-or-nothing OK.
 *   6. domainEvents (OrderPlaced) auto-publish bởi Spring Data sau save.
 * </pre>
 *
 * <p>Vì sao reserve TRƯỚC save?
 * <ul>
 *   <li>Nếu save trước rồi reserve fail → Order đã DB → user thấy order
 *       "treo" — UX xấu.</li>
 *   <li>Nếu reserve trước rồi save fail → compensate release (best-effort).
 *       Worst case: orphan reservation 5 phút (TTL ở inventory) +
 *       inventory log loud → manual reconcile được.</li>
 *   <li>Đây là trade-off classic 2-step orchestration. Day 13 outbox sẽ
 *       biến reserve thành event async, đẹp hơn.</li>
 * </ul>
 *
 * <p>{@code @Transactional} ôm step 3-5: nếu DB save fail (vd: unique
 * constraint idempotency_key race) → tx rollback, không insert. Reserve
 * đã làm ở step 4 KHÔNG rollback tự động (Feign call ngoài JDBC tx) —
 * compensation phải thủ công ở catch block (xem implementation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderUseCase {

    private final OrderRepository orderRepository;
    private final CartClient cartClient;
    private final InventoryClient inventoryClient;

    @Transactional
    public Order place(PlaceOrderCommand cmd) {
        // 1. Idempotency check.
        if (cmd.idempotencyKey() != null) {
            var existing = orderRepository.findByUserIdAndIdempotencyKey(
                    cmd.userId(), cmd.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Idempotent return: order={} for user={} key={}",
                        existing.get().getId(), cmd.userId(), cmd.idempotencyKey());
                return existing.get();
            }
        }

        // 2. Fetch cart.
        CartView cart = cartClient.fetchUserCart(cmd.bearerToken());
        if (cart.items() == null || cart.items().isEmpty()) {
            throw new EmptyCartException();
        }

        // 3. Build Order aggregate.
        Order order = Order.create(cmd.userId(), cmd.shippingAddress(),
                cmd.currency(), cmd.idempotencyKey());
        for (CartView.CartItem item : cart.items()) {
            String ccy = item.unitPriceCurrency() != null ? item.unitPriceCurrency() : cmd.currency();
            order.addItem(item.sku(), item.productName(), item.quantity(),
                    new Money(item.unitPriceAmount(), ccy));
        }

        // 4. Reserve inventory với compensation pattern.
        List<CartView.CartItem> reserved = new ArrayList<>();
        try {
            for (CartView.CartItem item : cart.items()) {
                inventoryClient.reserve(item.sku(), item.quantity(), cmd.bearerToken());
                reserved.add(item);
            }
        } catch (RuntimeException ex) {
            log.warn("Reserve failed at item {}/{} — compensating {} prior reservations",
                    reserved.size() + 1, cart.items().size(), reserved.size());
            for (CartView.CartItem done : reserved) {
                inventoryClient.releaseReservation(done.sku(), done.quantity(), cmd.bearerToken());
            }
            throw ex;
        }

        // 5. Save Order — emit OrderPlaced event via @DomainEvents.
        order.place();
        Order saved;
        try {
            saved = orderRepository.save(order);
        } catch (RuntimeException ex) {
            // DB save fail SAU khi reserve OK — compensate toàn bộ.
            log.error("Order save failed AFTER reserve — compensating all {} reservations",
                    reserved.size(), ex);
            for (CartView.CartItem done : reserved) {
                inventoryClient.releaseReservation(done.sku(), done.quantity(), cmd.bearerToken());
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to persist order");
        }
        log.info("Order placed id={} user={} total={} {}",
                saved.getId(), saved.getUserId(),
                saved.getTotal().amount(), saved.getTotal().currency());
        return saved;
    }
}
