package com.ecom.analytics.repository;

import com.ecom.analytics.domain.AnalyticsEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data Mongo repository cho {@link AnalyticsEvent}.
 *
 * <p>Chỉ cần CRUD cơ bản (save = ingest, count = sanity check). Report KHÔNG
 * đi qua đây — aggregation pipeline phức tạp dùng {@code MongoTemplate} trực
 * tiếp trong {@code ReportService} (repository derived-query KHÔNG đủ sức
 * biểu đạt {@code $group} + {@code $sort} + {@code $limit}).
 */
public interface AnalyticsEventRepository extends MongoRepository<AnalyticsEvent, String> {
}
