package com.ecom.cart.web;

import com.ecom.cart.domain.CartId;
import com.ecom.cart.security.AuthUserPrincipal;
import com.ecom.cart.service.CartService;
import com.ecom.cart.web.dto.AddItemRequest;
import com.ecom.cart.web.dto.CartResponse;
import com.ecom.cart.web.dto.UpdateItemRequest;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CartIdResolver resolver;

    @GetMapping
    public ApiResponse<CartResponse> getCart(HttpServletRequest req) {
        CartId id = resolver.resolve(req);
        return ApiResponse.ok(cartService.getCart(id));
    }

    @PostMapping("/items")
    public ApiResponse<CartResponse> addItem(@Valid @RequestBody AddItemRequest body,
                                             HttpServletRequest req) {
        CartId id = resolver.resolve(req);
        return ApiResponse.ok(cartService.addItem(id, body.sku(), body.qty()));
    }

    @PutMapping("/items/{sku}")
    public ApiResponse<CartResponse> updateItem(@PathVariable String sku,
                                                @Valid @RequestBody UpdateItemRequest body,
                                                HttpServletRequest req) {
        CartId id = resolver.resolve(req);
        return ApiResponse.ok(cartService.updateItem(id, sku, body.qty()));
    }

    @DeleteMapping("/items/{sku}")
    public ApiResponse<CartResponse> removeItem(@PathVariable String sku,
                                                HttpServletRequest req) {
        CartId id = resolver.resolve(req);
        return ApiResponse.ok(cartService.removeItem(id, sku));
    }

    @DeleteMapping
    public ApiResponse<Void> clear(HttpServletRequest req) {
        CartId id = resolver.resolve(req);
        cartService.clear(id);
        return ApiResponse.ok(null);
    }

    /**
     * Sau khi user login thành công, frontend gọi endpoint này với JWT
     * (Bearer) + body chứa anonToken cũ → server merge anon → user cart.
     *
     * <p>Idempotency: anon key bị DEL sau merge → call lần 2 trả user cart
     * không thay đổi (vì anon key trống).
     */
    @PostMapping("/merge")
    public ApiResponse<CartResponse> merge(@RequestHeader(CartIdResolver.ANON_TOKEN_HEADER) String anonToken,
                                           @AuthenticationPrincipal AuthUserPrincipal user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Cần JWT để merge cart");
        }
        if (anonToken == null || anonToken.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Thiếu " + CartIdResolver.ANON_TOKEN_HEADER);
        }
        return ApiResponse.ok(cartService.merge(anonToken, user.userId()));
    }
}
