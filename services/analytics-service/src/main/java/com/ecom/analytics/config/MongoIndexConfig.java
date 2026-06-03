package com.ecom.analytics.config;

import com.ecom.analytics.domain.AnalyticsEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Day 23 — tạo index cho {@code analytics_events} một cách TƯỜNG MINH (tắt
 * {@code auto-index-creation} ở application.yml). Lý do KHÔNG dùng
 * {@code @Indexed}/{@code @CompoundIndex} annotation tự động:
 * <ul>
 *   <li>Auto-index-creation chạy lúc map class đầu tiên — khó kiểm soát thời
 *       điểm + dễ "ghost index" khi đổi annotation mà index cũ còn nằm lại.</li>
 *   <li>Tạo tay ở đây cho thấy RÕ chiến lược index — đúng tinh thần "index là
 *       quyết định, không phải side-effect của annotation".</li>
 * </ul>
 *
 * <p>Chạy ở {@link ApplicationReadyEvent} (sau khi context sẵn sàng) thay vì
 * {@code @PostConstruct} — index creation cần connection Mongo; nếu Mongo
 * tạm down thì log lỗi + tiếp tục (không crash app cho phần report degrade),
 * reconcile lần restart sau. {@code ensureIndex} idempotent: index đã tồn tại
 * cùng spec = no-op.
 *
 * <h3>2 index, 2 mục đích</h3>
 * <ol>
 *   <li><b>Compound {@code (type, occurredAt desc)}</b>: phục vụ MỌI report —
 *       luôn filter {@code type} + range {@code occurredAt}, sort theo thời
 *       gian. ESR rule (Equality → Sort → Range): {@code type} equality đứng
 *       trước, {@code occurredAt} vừa sort vừa range đứng sau.</li>
 *   <li><b>TTL {@code (occurredAt)} expireAfter 90d</b>: Mongo background
 *       thread (chạy mỗi ~60s) tự xoá document có {@code occurredAt} quá hạn.
 *       KHÔNG real-time — document có thể "sống" thêm tới 60s sau mốc; report
 *       phải chịu được điều này (Day 23 issue/interview nhấn mạnh).</li>
 * </ol>
 */
@Slf4j
@Component
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;
    private final long ttlDays;

    public MongoIndexConfig(MongoTemplate mongoTemplate,
                            @Value("${app.analytics.event-ttl-days:90}") long ttlDays) {
        this.mongoTemplate = mongoTemplate;
        this.ttlDays = ttlDays;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        try {
            var indexOps = mongoTemplate.indexOps(AnalyticsEvent.class);

            // 1) Compound cho report query (ESR: equality type → range/sort occurredAt).
            indexOps.ensureIndex(new Index()
                    .on("type", Sort.Direction.ASC)
                    .on("occurredAt", Sort.Direction.DESC)
                    .named("type_occurredAt"));

            // 2) TTL — auto-expire event sau N ngày. KHÔNG đặt cùng key pattern
            //    với compound (compound là {type,occurredAt}, TTL là {occurredAt}).
            indexOps.ensureIndex(new Index()
                    .on("occurredAt", Sort.Direction.ASC)
                    .expire(Duration.ofDays(ttlDays))
                    .named("occurredAt_ttl"));

            log.info("Mongo indexes ensured for analytics_events (compound + TTL {}d)", ttlDays);
        } catch (Exception e) {
            // Mongo down lúc boot → report sẽ lỗi khi gọi, nhưng app vẫn lên để
            // phần khác (health, beacon buffer sau này) hoạt động. Alert pick log này.
            log.error("Failed ensuring Mongo indexes — report query có thể chậm/thiếu TTL đến lần restart sau", e);
        }
    }
}
