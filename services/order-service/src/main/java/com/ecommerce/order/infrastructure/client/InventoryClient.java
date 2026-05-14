package com.ecommerce.order.infrastructure.client;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.order.infrastructure.client.dto.ReserveRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sync HTTP client tới inventory-service. Day 6 dùng sync vì chưa có
 * Kafka. Failure handling:
 * <ul>
 *   <li>409 STOCK_INSUFFICIENT → propagate lên caller (PlaceOrderUseCase
 *       rollback tx — order chưa save vì reserve trước save).</li>
 *   <li>5xx hoặc timeout → BusinessException INTERNAL_ERROR, caller
 *       rollback. Day 12 sẽ thêm Resilience4j circuit breaker.</li>
 * </ul>
 *
 * <p>Compensation method {@link #releaseReservation} dùng ở rollback flow
 * (Order save fail SAU khi reserve OK). KHÔNG retry — best-effort log
 * orphan. Day 13 outbox sẽ giải quyết.
 */
@Slf4j
@Component
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(RestClient.Builder builder,
                            @Value("${services.inventory.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public void reserve(String sku, int qty, String bearerToken) {
        try {
            restClient.post()
                    .uri("/inventory/reserve")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .body(new ReserveRequest(sku, qty))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 409) {
                throw new BusinessException(ErrorCode.STOCK_INSUFFICIENT,
                        "Insufficient stock for sku=" + sku);
            }
            log.warn("inventory.reserve returned {} body={}", status, ex.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Inventory call failed: " + status);
        } catch (Exception ex) {
            log.error("inventory.reserve unexpected failure sku={}", sku, ex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Inventory unavailable");
        }
    }

    /**
     * Compensating release — best-effort, KHÔNG throw nếu fail (Order đã
     * không save, không có gì để rollback ở DB; nhưng inventory đang giữ
     * reservation orphan → log loud cho ops triage manually).
     */
    public void releaseReservation(String sku, int qty, String bearerToken) {
        try {
            restClient.post()
                    .uri("/inventory/release")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .body(new ReserveRequest(sku, qty))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Compensated reservation sku={} qty={}", sku, qty);
        } catch (Exception ex) {
            log.error("ORPHAN-RESERVATION sku={} qty={} — release failed, manual triage needed",
                    sku, qty, ex);
        }
    }
}
