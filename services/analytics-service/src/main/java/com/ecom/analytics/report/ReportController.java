package com.ecom.analytics.report;

import com.ecom.analytics.domain.EventType;
import com.ecom.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Day 23 — report API cho team Growth. Read-only, query Mongo aggregation.
 *
 * <p>Default window = 7 ngày gần nhất nếu client không truyền {@code from}.
 * {@code limit} cap 100 chống query kéo cả collection.
 */
@RestController
@RequestMapping("/analytics/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final int MAX_LIMIT = 100;

    private final ReportService reportService;

    /**
     * Top sản phẩm bán chạy / xem nhiều. {@code type} mặc định {@code order_placed}
     * (top bán chạy); truyền {@code product_viewed} để lấy top xem nhiều.
     */
    @GetMapping("/top-products")
    public ApiResponse<List<TopProduct>> topProducts(
            @RequestParam(defaultValue = EventType.ORDER_PLACED) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(defaultValue = "10") int limit) {
        Instant since = from != null ? from : Instant.now().minus(7, ChronoUnit.DAYS);
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.ok(reportService.topProducts(type, since, capped));
    }

    /** Conversion funnel xem → giỏ → đặt hàng. */
    @GetMapping("/funnel")
    public ApiResponse<FunnelReport> funnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from) {
        Instant since = from != null ? from : Instant.now().minus(7, ChronoUnit.DAYS);
        return ApiResponse.ok(reportService.funnel(since));
    }
}
