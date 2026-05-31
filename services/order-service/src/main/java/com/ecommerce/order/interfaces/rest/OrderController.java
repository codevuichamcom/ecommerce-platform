package com.ecommerce.order.interfaces.rest;

import com.ecom.common.response.ApiResponse;
import com.ecom.common.response.PageResponse;
import com.ecom.common.security.AuthUserPrincipal;
import com.ecommerce.order.application.OrderQueryService;
import com.ecommerce.order.application.PlaceOrderCommand;
import com.ecommerce.order.application.PlaceOrderUseCase;
import com.ecommerce.order.domain.Order;
import com.ecommerce.order.interfaces.rest.dto.CancelOrderRequest;
import com.ecommerce.order.interfaces.rest.dto.OrderListResponse;
import com.ecommerce.order.interfaces.rest.dto.OrderResponse;
import com.ecommerce.order.interfaces.rest.dto.PlaceOrderRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST API cho Order aggregate.
 *
 * <p>Bearer token forward xuống cart/inventory service: extract từ
 * inbound {@code Authorization} header rồi truyền qua command. KHÔNG dùng
 * RequestContextHolder trong service layer (testability + thread-safety
 * khi virtual thread).
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final OrderQueryService orderQueryService;

    @PostMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ApiResponse<OrderResponse> place(@RequestBody @Valid PlaceOrderRequest req,
                                             @AuthenticationPrincipal AuthUserPrincipal user,
                                             HttpServletRequest http) {
        PlaceOrderCommand cmd = new PlaceOrderCommand(
                user.userId(),
                extractBearer(http),
                req.shipping().toAddress(),
                req.currency(),
                req.idempotencyKey());
        Order placed = placeOrderUseCase.place(cmd);
        return ApiResponse.ok(OrderResponse.from(placed));
    }

    /**
     * Day 17: list "Đơn hàng của tôi" — paginated, projection-backed (≤2
     * query bất kể số đơn). Scope theo userId của token: KHÔNG cho liệt kê
     * đơn người khác qua endpoint này.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ApiResponse<PageResponse<OrderListResponse>> list(
            @AuthenticationPrincipal AuthUserPrincipal user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "placedAt") String sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        PageResponse<OrderListResponse> body = orderQueryService
                .listMyOrders(user.userId(), page, size, sortBy, direction)
                .map(OrderListResponse::from);
        return ApiResponse.ok(body);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ApiResponse<OrderResponse> get(@PathVariable UUID id,
                                           @AuthenticationPrincipal AuthUserPrincipal user) {
        boolean isAdmin = "ADMIN".equals(user.role());
        Order order = orderQueryService.get(id, user.userId(), isAdmin);
        return ApiResponse.ok(OrderResponse.from(order));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public ApiResponse<OrderResponse> cancel(@PathVariable UUID id,
                                              @RequestBody @Valid CancelOrderRequest req,
                                              @AuthenticationPrincipal AuthUserPrincipal user) {
        boolean isAdmin = "ADMIN".equals(user.role());
        Order cancelled = orderQueryService.cancel(id, user.userId(), isAdmin, req.reason());
        return ApiResponse.ok(OrderResponse.from(cancelled));
    }

    private static String extractBearer(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
