package com.ecom.cart.web;

import com.ecom.cart.domain.CartId;
import com.ecom.cart.security.AuthUserPrincipal;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolve CartId từ request: ưu tiên JWT → fallback header X-Cart-Token →
 * không có gì thì 401.
 *
 * <p>Đặt là component thay vì static helper để dễ test (mock SecurityContext
 * trong unit test phiền hơn mock 1 bean).
 */
@Component
public class CartIdResolver {

    public static final String ANON_TOKEN_HEADER = "X-Cart-Token";

    public CartId resolve(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUserPrincipal user) {
            return new CartId.User(user.userId());
        }
        String anon = request.getHeader(ANON_TOKEN_HEADER);
        if (anon != null && !anon.isBlank()) {
            return new CartId.Anonymous(anon);
        }
        // KHÔNG auto-generate token ở backend — frontend nên tự mint UUID
        // ở first visit (localStorage) rồi gắn header. Backend tự generate
        // sẽ phá tính idempotent của GET /cart (gọi 2 lần ra 2 token khác).
        throw new BusinessException(ErrorCode.UNAUTHORIZED,
                "Cần JWT hoặc header " + ANON_TOKEN_HEADER);
    }
}
