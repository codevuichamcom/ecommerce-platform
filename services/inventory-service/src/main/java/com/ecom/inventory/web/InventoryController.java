package com.ecom.inventory.web;

import com.ecom.common.response.ApiResponse;
import com.ecom.inventory.application.InventoryService;
import com.ecom.inventory.application.dto.ReserveRequest;
import com.ecom.inventory.application.dto.StockResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API cho Stock aggregate.
 *
 * <p>Method security: tạm dùng {@code hasAnyRole('ADMIN','SERVICE')} cho
 * write operation. Day 8 sẽ thay bằng service-token / mTLS khi build
 * cross-service auth thật.
 */
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{sku}")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE','USER')")
    public ApiResponse<StockResponse> get(@PathVariable String sku) {
        return ApiResponse.ok(inventoryService.get(sku));
    }

    @PostMapping("/reserve")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    public ApiResponse<StockResponse> reserve(@RequestBody @Valid ReserveRequest req) {
        return ApiResponse.ok(inventoryService.reserve(req.sku(), req.qty()));
    }

    @PostMapping("/release")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    public ApiResponse<StockResponse> release(@RequestBody @Valid ReserveRequest req) {
        return ApiResponse.ok(inventoryService.release(req.sku(), req.qty()));
    }
}
