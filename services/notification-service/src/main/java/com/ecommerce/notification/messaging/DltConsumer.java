package com.ecommerce.notification.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>DLT consumer — Day 12</h2>
 *
 * <p>Listen MỌI topic kết thúc bằng {@code .DLT} (pattern match). Mục đích KHÔNG
 * phải xử lý retry — message đã giving-up sau 3 retry chính, vào đây là <b>cần
 * human triage</b>. Consumer này chỉ:
 * <ol>
 *   <li>Log đầy đủ payload + exception headers (Spring Kafka inject sẵn).</li>
 *   <li>Increment counter {@code notification_dlt_count_total} cho Prometheus alert.</li>
 *   <li><b>Tuyệt đối KHÔNG throw</b> — DLT consumer fail = message rơi vào
 *       {@code .DLT.DLT} (infinite cascade). Try-catch swallow là rule cứng.</li>
 * </ol>
 *
 * <p><b>Tại sao topicPattern thay vì list topic</b>: thêm topic mới (vd Day 13
 * outbox publish thêm event) tự động cover. Trade-off: pattern listener share
 * cùng consumer group {@code notification-dlt} → 1 consumer xử lý tất cả DLT.
 *
 * <p><b>Runbook recovery</b>: xem [`docs/runbooks/kafka-topic-recovery.md`].
 * 5 bước: triage → inspect → classify → replay/discard → post-mortem.
 */
@Slf4j
@Component
public class DltConsumer {

    private final AtomicLong dltCount = new AtomicLong();

    @KafkaListener(
            topicPattern = ".*\\.DLT",
            groupId = "notification-dlt",
            containerFactory = "kafkaListenerContainerFactory")
    public void onDeadLetter(
            @Payload(required = false) Object payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) byte[] originalTopic,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_FQCN, required = false) byte[] exceptionFqcn,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) byte[] exceptionMessage) {

        long total = dltCount.incrementAndGet();
        String original = originalTopic != null ? new String(originalTopic) : "<unknown>";
        String exClass = exceptionFqcn != null ? new String(exceptionFqcn) : "<unknown>";
        String exMsg = exceptionMessage != null ? new String(exceptionMessage) : "<none>";

        try {
            log.error(
                    "[DLT] topic={} originalTopic={} key={} offset={} ex={} msg={} payloadType={} totalDltCount={}",
                    topic, original, key, offset, exClass, exMsg,
                    payload != null ? payload.getClass().getSimpleName() : "null",
                    total);

            // TODO Day 20: emit Micrometer counter `notification.dlt.count` tagged
            // theo `original_topic` để Grafana alert `rate > 0 over 5min`.
            // TODO Day 34: PagerDuty/Slack webhook khi count vượt threshold.

        } catch (Exception swallow) {
            // Defensive: log fail không được cascade. KHÔNG re-throw.
            log.error("[DLT] handler internal error — swallowed to avoid .DLT.DLT cascade", swallow);
        }
    }

    /** Test-only accessor. */
    public long getDltCount() {
        return dltCount.get();
    }
}
