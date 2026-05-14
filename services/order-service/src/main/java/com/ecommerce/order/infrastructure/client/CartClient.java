package com.ecommerce.order.infrastructure.client;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.response.ApiResponse;
import com.ecommerce.order.infrastructure.client.dto.CartView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sync HTTP client tới cart-service qua Spring 6.1 {@link RestClient}.
 *
 * <p>Tại sao RestClient thay vì WebClient / RestTemplate / Feign?
 * <ul>
 *   <li>WebClient là reactive — order-service Day 6 dùng virtual threads
 *       blocking style, đưa Mono/Flux vào sẽ rối paradigm.</li>
 *   <li>RestTemplate maintenance-only từ Spring 6 — không nên dùng cho code mới.</li>
 *   <li>Feign sẽ được introduce ở Day 8 (so sánh với HTTP Interface).
 *       Day 6 dùng RestClient để KHÔNG pre-empt decision đó.</li>
 * </ul>
 *
 * <p>Token forwarding: order-service nhận user JWT từ inbound request rồi
 * truyền tiếp xuống cart-service (chỉ cho phép user xem chính cart mình).
 * Day 6 dùng same-secret JWT; Day 8 sẽ thay bằng service token.
 */
@Slf4j
@Component
public class CartClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CartClient(RestClient.Builder builder,
                       ObjectMapper objectMapper,
                       @Value("${services.cart.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    public CartView fetchUserCart(String bearerToken) {
        try {
            String body = restClient.get()
                    .uri("/cart")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(String.class);
            ApiResponse<CartView> wrapped = objectMapper.readValue(
                    body, new TypeReference<ApiResponse<CartView>>() {});
            if (wrapped == null || wrapped.data() == null) {
                throw new BusinessException(ErrorCode.CART_EMPTY, "Cart response empty");
            }
            return wrapped.data();
        } catch (RestClientResponseException ex) {
            log.warn("cart-service returned {} body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Failed to fetch cart: " + ex.getStatusCode());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("cart-service call failed", ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "cart-service unavailable");
        }
    }
}
