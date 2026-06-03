package com.ecom.analytics.ingest;

import com.ecom.analytics.domain.AnalyticsEvent;
import com.ecom.analytics.repository.AnalyticsEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Day 23 — điểm vào DUY NHẤT để ghi event vào store. Cả 2 nguồn (Kafka
 * consumer + HTTP beacon) đều đi qua đây → một chỗ áp policy (validate,
 * enrich, sau này: sampling, PII scrub).
 *
 * <p><b>Vì sao append thuần, KHÔNG dedup?</b> Khác payment (Day 10) nơi
 * duplicate = mất tiền. Analytics đếm xấp xỉ — 1 event đúp do Kafka
 * at-least-once làm lệch top-products ~0.x%, chấp nhận được. Đánh đổi: KHÔNG
 * tốn dedup table + idempotency check. Nếu cần chính xác tuyệt đối (billing
 * analytics) thì mới thêm {@code eventId} unique index — hiện không cần.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestService {

    private final AnalyticsEventRepository repository;

    /** Single-document write — atomic ở Mongo, không cần transaction. */
    public void ingest(AnalyticsEvent event) {
        repository.save(event);
        log.debug("Ingested event type={} productId={} sessionId={}",
                event.getType(), event.getProductId(), event.getSessionId());
    }
}
