package com.ecom.analytics.report;

import com.ecom.analytics.domain.AnalyticsEvent;
import com.ecom.analytics.domain.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.limit;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.project;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;

/**
 * Day 23 — TRÁI TIM của việc dùng Mongo cho analytics: aggregation pipeline.
 * Đây là thứ relational làm được (GROUP BY) nhưng Mongo cho biểu đạt pipeline
 * nhiều stage gọn + chạy gần data + dễ scale ngang (sharding theo occurredAt).
 *
 * <p><b>Vì sao MongoTemplate chứ KHÔNG MongoRepository derived-query?</b>
 * Derived query ({@code countByTypeAndOccurredAtAfter}) chỉ làm được filter +
 * count đơn. {@code $group} + {@code $sort} + {@code $limit} + {@code $project}
 * nhiều stage → phải dùng aggregation DSL của {@code MongoTemplate}.
 *
 * <p><b>Index nào đỡ pipeline này?</b> Compound {@code (type, occurredAt)} —
 * stage {@code $match} đầu tiên filter đúng 2 field đó → index phủ được phần
 * lọc, {@code $group} chạy trên tập đã hẹp. Không có index = COLLSCAN toàn
 * collection (xem MongoIndexConfig + interview Q4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final MongoTemplate mongoTemplate;

    /**
     * Top sản phẩm theo loại event trong khoảng {@code [from, now]}.
     *
     * <p>Pipeline: {@code $match(type, occurredAt≥from, productId≠null)} →
     * {@code $group(productId) count} → {@code $sort(count desc)} →
     * {@code $limit(n)} → {@code $project} đổi {@code _id}→{@code productKey}.
     */
    public List<TopProduct> topProducts(String type, Instant from, int limit) {
        Aggregation agg = newAggregation(
                match(Criteria.where("type").is(type)
                        .and("occurredAt").gte(from)
                        .and("productId").ne(null)),
                group("productId").count().as("count"),
                sort(Sort.Direction.DESC, "count"),
                limit(limit),
                project("count").and("_id").as("productKey"));

        AggregationResults<TopProduct> results =
                mongoTemplate.aggregate(agg, AnalyticsEvent.class, TopProduct.class);
        return results.getMappedResults();
    }

    /**
     * Conversion funnel theo thứ tự stage trong {@link EventType#FUNNEL_STAGES}.
     *
     * <p>Pipeline: {@code $match(type IN stages, occurredAt≥from)} →
     * {@code $group(type) count}. Một query lấy count CẢ 3 stage cùng lúc (rẻ
     * hơn 3 query riêng). Phần sắp theo thứ tự phễu + tính % conversion làm ở
     * Java vì cần thứ tự logic (Mongo trả map type→count không thứ tự).
     */
    public FunnelReport funnel(Instant from) {
        Aggregation agg = newAggregation(
                match(Criteria.where("type").in(EventType.FUNNEL_STAGES)
                        .and("occurredAt").gte(from)),
                group("type").count().as("count"));

        AggregationResults<Document> results =
                mongoTemplate.aggregate(agg, AnalyticsEvent.class, Document.class);

        Map<String, Long> countByType = new HashMap<>();
        for (Document d : results.getMappedResults()) {
            // _id = type, count = số (Mongo trả Integer/Long tuỳ size) → ép long.
            countByType.put(d.getString("_id"), ((Number) d.get("count")).longValue());
        }

        long topCount = countByType.getOrDefault(EventType.FUNNEL_STAGES.get(0), 0L);
        List<FunnelReport.Stage> stages = new ArrayList<>();
        for (String stage : EventType.FUNNEL_STAGES) {
            long count = countByType.getOrDefault(stage, 0L);
            double conv = topCount == 0 ? 0.0 : round1((count * 100.0) / topCount);
            stages.add(new FunnelReport.Stage(stage, count, conv));
        }
        log.debug("Funnel from={} → {}", from, countByType);
        return new FunnelReport(stages);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
