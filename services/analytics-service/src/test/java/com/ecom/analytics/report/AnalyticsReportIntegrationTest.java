package com.ecom.analytics.report;

import com.ecom.analytics.domain.AnalyticsEvent;
import com.ecom.analytics.domain.EventType;
import com.ecom.analytics.repository.AnalyticsEventRepository;
import com.ecom.analytics.support.MongoTestcontainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 23 — Mongo aggregation integration test trên container thật. Gated
 * {@code RUN_ANALYTICS_INTEGRATION_TESTS=true}.
 *
 * <p>{@code app.kafka.enabled=false}: test report KHÔNG cần Kafka — ghi event
 * trực tiếp qua repository, bỏ Kafka container cho context nhẹ.
 *
 * <p>Cover:
 * <ul>
 *   <li>top-products: group + sort + limit ra đúng thứ tự bán chạy.</li>
 *   <li>funnel: count đúng từng stage + conversion % so với đỉnh phễu.</li>
 *   <li>window filter: event ngoài khoảng thời gian KHÔNG lọt report.</li>
 *   <li>index: compound + TTL được tạo (MongoIndexConfig chạy ở ApplicationReadyEvent).</li>
 * </ul>
 */
@SpringBootTest(properties = {"app.kafka.enabled=false"})
@Import(MongoTestcontainerConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_ANALYTICS_INTEGRATION_TESTS", matches = "true")
class AnalyticsReportIntegrationTest {

    @Autowired ReportService reportService;
    @Autowired AnalyticsEventRepository repository;
    @Autowired MongoTemplate mongoTemplate;

    private final Instant now = Instant.now();

    @BeforeEach
    void seed() {
        repository.deleteAll();
        // 3 view SKU-A, 2 view SKU-B, 1 view SKU-C
        view("SKU-A"); view("SKU-A"); view("SKU-A");
        view("SKU-B"); view("SKU-B");
        view("SKU-C");
        // 2 cart-updated
        cart("SKU-A"); cart("SKU-B");
        // 2 order_placed: SKU-A x2, SKU-B x1
        order("SKU-A"); order("SKU-A"); order("SKU-B");
        // 1 event QUÁ CŨ (40 ngày trước) — phải bị window filter loại
        repository.save(new AnalyticsEvent(EventType.PRODUCT_VIEWED,
                now.minus(40, ChronoUnit.DAYS), "old-session", null, "SKU-OLD", Map.of()));
    }

    @Test
    void topPurchasedProducts_rankedByCount() {
        List<TopProduct> top = reportService.topProducts(
                EventType.ORDER_PLACED, now.minus(7, ChronoUnit.DAYS), 10);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).productKey()).isEqualTo("SKU-A");   // bán chạy nhất
        assertThat(top.get(0).count()).isEqualTo(2);
        assertThat(top.get(1).productKey()).isEqualTo("SKU-B");
        assertThat(top.get(1).count()).isEqualTo(1);
    }

    @Test
    void topViewedProducts_excludesOutOfWindowEvent() {
        List<TopProduct> top = reportService.topProducts(
                EventType.PRODUCT_VIEWED, now.minus(7, ChronoUnit.DAYS), 10);

        // SKU-OLD (40 ngày trước) KHÔNG lọt window 7 ngày.
        assertThat(top).extracting(TopProduct::productKey).doesNotContain("SKU-OLD");
        assertThat(top.get(0).productKey()).isEqualTo("SKU-A");
        assertThat(top.get(0).count()).isEqualTo(3);
    }

    @Test
    void funnel_countsEachStageWithConversion() {
        FunnelReport funnel = reportService.funnel(now.minus(7, ChronoUnit.DAYS));

        assertThat(funnel.stages()).hasSize(3);
        FunnelReport.Stage viewed = funnel.stages().get(0);
        FunnelReport.Stage cart = funnel.stages().get(1);
        FunnelReport.Stage order = funnel.stages().get(2);

        assertThat(viewed.stage()).isEqualTo(EventType.PRODUCT_VIEWED);
        assertThat(viewed.count()).isEqualTo(6);          // 6 view trong window (SKU-OLD bị loại)
        assertThat(viewed.conversionFromTopPct()).isEqualTo(100.0);

        assertThat(cart.count()).isEqualTo(2);
        assertThat(order.count()).isEqualTo(3);
        // conversion order = 3/6 = 50%
        assertThat(order.conversionFromTopPct()).isEqualTo(50.0);
    }

    @Test
    void indexes_compoundAndTtlCreated() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(AnalyticsEvent.class).getIndexInfo();

        boolean hasCompound = indexes.stream().anyMatch(i -> "type_occurredAt".equals(i.getName()));
        boolean hasTtl = indexes.stream()
                .anyMatch(i -> "occurredAt_ttl".equals(i.getName()) && i.getExpireAfter().isPresent());

        assertThat(hasCompound).as("compound index type_occurredAt").isTrue();
        assertThat(hasTtl).as("TTL index occurredAt_ttl with expireAfter").isTrue();
    }

    private void view(String sku) {
        repository.save(new AnalyticsEvent(EventType.PRODUCT_VIEWED, now, "s-" + sku, null, sku, Map.of()));
    }

    private void cart(String sku) {
        repository.save(new AnalyticsEvent(EventType.CART_UPDATED, now, "s-" + sku, null, sku, Map.of()));
    }

    private void order(String sku) {
        repository.save(new AnalyticsEvent(EventType.ORDER_PLACED, now, null, "u-1", sku,
                Map.of("sku", sku, "quantity", 1)));
    }
}
